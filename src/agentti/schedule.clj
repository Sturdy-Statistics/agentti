(ns agentti.schedule
  "Utilities for generating time sequences for agentti workers."
  (:require
   [chime.core :as chime]
   [taoensso.truss :refer [have]])
  (:import
   (java.time Instant Duration)
   (java.util.concurrent ThreadLocalRandom)))

(set! *warn-on-reflection* true)

(defn- clamp-long ^long [^long x ^long lo ^long hi]
  (max lo (min hi x)))

(defn- uniform-jitter-ms ^long [^long j]
  (if (pos? j)
    (let [r (ThreadLocalRandom/current)
          u (.nextLong r (inc (* 2 j)))]
      (- u j))
    0))

(defn- half-uniform-jitter-ms ^long [^long j]
  (if (pos? j)
    (.nextLong (ThreadLocalRandom/current) (inc j))
    0))

(defn periodic-seq
  "Produces a lazy, infinite sequence of `java.time.Instant`s.

   Unlike a standard fixed-rate schedule, if jitter is provided, it is applied
   cumulatively to create a bounded random walk. This avoids synchronized
   thundering herds across processes.

   Options:
     :jitter-ms   - Max jitter in milliseconds (0 <= jitter-ms < interval-ms)
     :jitter-frac - Fractional jitter (e.g. 0.1 for 10%). Mutually exclusive with :jitter-ms."
  [interval-ms & [{:keys [jitter-ms jitter-frac]}]]
  (have pos? interval-ms)
  (when (and jitter-ms jitter-frac)
    (throw (ex-info "Cannot specify both jitter-ms and jitter-frac" {})))

  (let [j-ms (cond
               (some? jitter-ms)   jitter-ms
               (some? jitter-frac) (Math/floor (* (double interval-ms) (double jitter-frac)))
               :else               0)
        j    (clamp-long (long j-ms) 0 (max 0 (dec interval-ms)))
        now  (Instant/now)]

    (if (zero? j)
      ;; Standard non-jittered schedule
      (chime/periodic-seq now (Duration/ofMillis interval-ms))

      ;; Jittered bounded random walk
      (letfn [(step [^long prev-ms]
                (lazy-seq
                 (let [delta (if (neg? prev-ms)
                               (half-uniform-jitter-ms j)
                               (+ interval-ms (uniform-jitter-ms j)))
                       tms   (if (neg? prev-ms)
                               (+ (.toEpochMilli now) delta)
                               (+ prev-ms delta))]
                   (cons (Instant/ofEpochMilli tms) (step tms)))))]
        (step -1)))))
