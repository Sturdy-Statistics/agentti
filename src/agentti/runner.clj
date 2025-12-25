(ns agentti.runner
  (:require
   [taoensso.telemere :as t])
  (:import
   (java.time Instant)
   (java.util.concurrent Future Callable ExecutorService TimeUnit
                         TimeoutException ExecutionException
                         RejectedExecutionException CancellationException)))

(set! *warn-on-reflection* true)

(defn- should-skip?
  "Returns true iff a run is already in-flight (and increments drop count).
   Otherwise marks in-flight and returns false."
  [wname in-flight? dropped-count]
  (if-not (compare-and-set! in-flight? false true)
    (do
      (swap! dropped-count inc)
      (t/log! {:level :info :id ::drop
               :msg   (str "Dropping tick for " wname " (previous run still active)")})
      true)
    false))

(defn- wait-future-with-timeout
  "Wait for `task` up to timeout-ms. Returns nil on success, or {:type ... :error ...}."
  [^Future task timeout-ms]
  (try
    (.get task (long timeout-ms) TimeUnit/MILLISECONDS)
    nil

    (catch TimeoutException e
      (.cancel task true)
      {:type :timeout :error e})

    (catch ExecutionException e
      {:type :exception :error (.getCause e)})

    (catch CancellationException e
      {:type :cancelled :error e})

    ;; TODO: confirm this only happens on submit?  never on get?
    ;; (catch RejectedExecutionException e
    ;;   {:type :rejected :error e})

    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      {:type :interrupted :error e})))

(defn- log-task-error!
  [{:keys [wname timeout-ms error last-error num-errors]}]

  (reset! last-error error)
  (swap! num-errors inc)

  (case (:type error)
    :timeout
    (t/log! {:level :warn
             :id ::timeout
             :msg (str "Worker " wname " timed out after " timeout-ms " ms")})
    :exception
    (t/log! {:level :error
             :id ::error
             :msg (str "Worker " wname " threw")
             :error (:error error)})
    :rejected
    (t/log! {:level :error
             :id ::rejected
             :msg (str "Worker " wname " rejected task (executor shutdown?)")
             :error (:error error)})
    :cancelled
    (t/log! {:level :info
             :id ::cancelled
             :msg (str "Worker " wname " task cancelled")})
    :interrupted
    (t/log! {:level :info
             :id ::interrupted
             :msg (str "Worker " wname " interrupted")})
    ;; default
    (t/log! {:level :error
             :id ::error-unknown
             :msg (str "Worker " wname " failed")
             :error (:error error)})))

(defn- finalize-run!
  [{:keys [wname timeout-ms future start-ms
           num-runs num-errors last-error
           last-run total-runtime last-duration avg-duration
           in-flight?]}]
  (try
    (let [err (wait-future-with-timeout future timeout-ms)]
      (if err
        (log-task-error! {:wname wname
                          :timeout-ms timeout-ms
                          :error err
                          :last-error last-error
                          :num-errors num-errors})
        (let [end (System/currentTimeMillis)
              dur (- end start-ms)
              _   (reset! last-error nil)
              n   (swap! num-runs inc)
              rt  (swap! total-runtime + dur)]
          (reset! last-duration dur)
          (reset! avg-duration (-> (/ rt n) double Math/round long)))))
    (finally
      ;; last-run records attempt start time (even on failure/timeout)
      (reset! last-run start-ms)
      (reset! in-flight? false))))

(defn make-task-runner
  "Return a single-arg fn suitable for `chime/chime-at` callbacks.

  IMPORTANT: The returned fn is non-blocking. It submits work to the worker executor
  and submits a watcher task that waits with timeout, updates metrics, and clears
  `in-flight?`. This allows accurate drop-while-running accounting."
  [wname body-fn ^ExecutorService executor ^ExecutorService watcher timeout-ms
   {:keys [in-flight? dropped-count
           num-runs num-errors last-error
           last-run total-runtime last-duration avg-duration]}]
  (fn [^Instant _instant]
    (when-not (should-skip? wname in-flight? dropped-count)
      (let [start-ms (System/currentTimeMillis)]
        (try
          (let [^Future f (.submit executor ^Callable (fn [] (body-fn)))]
            ;; watcher task does the blocking wait + bookkeeping
            (.submit watcher
                     ^Callable
                     (fn []
                       (finalize-run! {:wname wname
                                       :timeout-ms timeout-ms
                                       :future f
                                       :start-ms start-ms
                                       :num-runs num-runs
                                       :num-errors num-errors
                                       :last-error last-error
                                       :last-run last-run
                                       :total-runtime total-runtime
                                       :last-duration last-duration
                                       :avg-duration avg-duration
                                       :in-flight? in-flight?})
                       nil)))
          (catch RejectedExecutionException e
            ;; (.cancel f true) ;; best-effort
            ;; If we couldn't submit at all, treat it as an immediate error and release in-flight.
            (log-task-error! {:wname wname
                              :timeout-ms timeout-ms
                              :error {:type :rejected :error e}
                              :last-error last-error
                              :num-errors num-errors})
            (reset! last-run start-ms)
            (reset! in-flight? false))
          (catch Throwable e
            ;; same idea: do not leave in-flight? stuck true
            (log-task-error! {:wname wname
                              :timeout-ms timeout-ms
                              :error {:type :exception :error e}
                              :last-error last-error
                              :num-errors num-errors})
            (reset! last-run start-ms)
            (reset! in-flight? false)))))))
