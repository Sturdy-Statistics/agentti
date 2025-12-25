(ns agentti.schedules
  (:require
   [chime.core :as chime]
   [taoensso.truss :refer [have]])
  (:import
   (java.time Instant Duration)
   (java.util.concurrent ThreadLocalRandom)))

(set! *warn-on-reflection* true)

(defn- clamp-long
  ^long [^long x ^long lo ^long hi]
  (max lo (min hi x)))

(defn- uniform-jitter-ms
  "Uniform integer jitter in [-j, +j]. Returns 0 if j <= 0."
  ^long [^long j]
  (if (pos? j)
    (let [r (ThreadLocalRandom/current)
          u (.nextLong r (inc (* 2 j)))]  ; u ∈ [0, 2*j+1) = [0, 2*j]
      (- u j)) ; ∈ [-j,+j]
    0))

(defn- half-uniform-jitter-ms
  "Uniform integer jitter in [0, +j]. Returns 0 if j <= 0."
  ^long [^long j]
  (if (pos? j)
    (let [r (ThreadLocalRandom/current)
          u (.nextLong r (inc j))]  ; u ∈ [0, j+1) = [0, j]
      u)
    0))

(defn- jittered-periodic-seq!
  "Produce a lazy, infinite sequence of Instants suitable for use with chime.

  IMPORTANT SEMANTICS

  This function is written with Chime’s consumption model in mind.

  Chime does NOT call the task function and then ask for the next time.
  Instead, it *realizes the next Instant from the time sequence first*,
  and only then schedules a wakeup to invoke the callback at that time.

  As a result:

  - The sequence is advanced *ahead of execution*.
  - Side effects that occur during realization of the next Instant
    reflect the time of the upcoming tick, not the tick currently running.

  We rely on this behavior to maintain `next-eta*`.

  `next-eta*` is updated as a side effect at the moment the scheduler
  realizes the next Instant from the sequence. In practice, this means
  `next-eta*` holds the next scheduled execution time that Chime is
  currently waiting for.

  Note that while a task is executing, the scheduler thread may be blocked
  (depending on the callback implementation), and the sequence will not
  advance during that time. Consequently, `next-eta*` may remain unchanged
  during long-running executions. This is expected and intentional.

  SCHEDULE SHAPE

  - First tick: start + U[0, j]
  - Subsequent ticks: previous + interval + U[-j, +j]

  This is a fixed-delay schedule with bounded jitter, not a fixed-rate
  schedule anchored to the original start time.

  PARAMETERS

  - start        : Instant at which scheduling begins
  - interval-ms : base interval in milliseconds (must be positive)
  - j-ms        : maximum jitter in milliseconds (clamped to < interval)
  - next-eta*   : atom updated to the next scheduled tick’s epoch-ms

  The returned sequence is lazy and infinite."

  [^Instant start ^long interval-ms ^long j-ms next-eta*]
  (let [start-ms (.toEpochMilli start)
        j        (clamp-long j-ms 0 (max 0 (dec interval-ms)))] ;; j ∈ [0,interval)
    (letfn [(step [^long prev-ms]
              (lazy-seq
               (let [delta (if (neg? prev-ms)
                             (half-uniform-jitter-ms j)             ; first: step=-1 ⇒ u ∈ [0,j]
                             (+ interval-ms (uniform-jitter-ms j))) ; all others: u ∈ [interval-j,interval+j]
                     tms   (if (neg? prev-ms)
                             (+ start-ms delta) ; ∈ [start,start+j]
                             (+ prev-ms delta)) ; ∈ [prev+interval-j,prev+interval+j]
                     inst  (Instant/ofEpochMilli tms)]
                 (reset! next-eta* tms)
                 (cons inst (step tms)))))]
      (step -1))))

(defn- attach-next-eta!
  "Wrap a time sequence so that realization of each Instant updates `next-eta*`.

  This helper exists to support the non-jittered scheduling case, where the
  underlying time sequence is produced by `chime/periodic-seq`.

  Semantics mirror those described in `jittered-periodic-seq!`:

  - `next-eta*` is updated as a *side effect of sequence realization*,
    not as a result of task execution.
  - In practice, this corresponds to the next scheduled tick that Chime
    has already pulled from the time sequence and is waiting on.

  While a task is executing, the scheduler may not advance the sequence,
  so `next-eta*` may remain unchanged during long-running executions.
  This is expected and consistent with Chime’s scheduling model.

  See `jittered-periodic-seq!` for a detailed discussion of how Chime
  consumes time sequences and how `next-eta*` is intended to be interpreted."
  [times next-eta*]
  (map (fn [^Instant inst]
         (reset! next-eta* (.toEpochMilli inst))
         inst)
       times))

(defn build-times!
  "Construct a Chime-compatible time sequence for a periodic task.

  Returns a map with:
    :times      a lazy, infinite sequence of `java.time.Instant`s suitable
                for use with `chime/chime-at`
    :next-eta*  an atom containing the epoch-millis of the next scheduled tick
    :jitter-ms  the effective jitter in milliseconds (after validation)

  Options map:
    :interval-ms  (required)
        Base interval between executions, in milliseconds. Must be positive.

    :jitter-ms    (optional)
        Maximum jitter in milliseconds. Each execution time is offset by a
        random amount within the allowed jitter range. Must satisfy
        0 <= jitter-ms < interval-ms.

    :jitter-frac  (optional)
        Fractional jitter relative to the interval (e.g. 0.1 for ±10%).
        Mutually exclusive with :jitter-ms. Must satisfy 0.0 <= jitter-frac < 1.0.

  If neither :jitter-ms nor :jitter-frac is provided, executions occur at a
  fixed interval with no jitter.

  The returned `:times` sequence is intended to be passed directly to
  `chime/chime-at`. The `:next-eta*` atom can be used for monitoring or
  administrative interfaces to display the next scheduled execution time."

  [{:keys [interval-ms jitter-frac jitter-ms]}]

  ;; validate input
  (have pos? interval-ms)
  (when (and (some? jitter-ms) (some? jitter-frac))
    (throw (ex-info "cannot specify both jitter-ms and jitter-frac"
                    {:jitter-ms jitter-ms
                     :jitter-frac jitter-frac})))
  (when (some? jitter-ms)
    (when-not (< 0.0 jitter-ms interval-ms)
      (throw (ex-info "jitter-ms must be between 0 and interval-ms"
                      {:jitter-ms jitter-ms
                       :interval-ms interval-ms}))))
  (when (some? jitter-frac)
    (when-not (< 0.0 jitter-frac 1.0)
      (throw (ex-info "jitter-frac must be between 0.0 and 1.0" {:jitter-frac jitter-frac}))))

  (let [next-eta* (atom nil)
        j-ms      (cond
                    (some? jitter-ms) jitter-ms
                    (some? jitter-frac) (Math/floor (* (double interval-ms) (double jitter-frac)))
                    :else 0)
        j-ms      (clamp-long (long j-ms)
                              0
                              (max 0 (dec (long interval-ms))))
        base-now  (Instant/now)
        times     (if (pos? j-ms)
                    (jittered-periodic-seq! base-now interval-ms j-ms next-eta*)
                    ;; no jitter -> periodic; still keep next-eta up to date
                    (attach-next-eta! (chime/periodic-seq base-now (Duration/ofMillis interval-ms))
                                      next-eta*))]
    {:times times :next-eta* next-eta* :jitter-ms j-ms}))

;; Local Variables:
;; fill-column: 100000
;; End:
