(ns agentti.engine
  (:require
   [clojure.core.async :as async]
   [taoensso.telemere :as t])
  (:import
   (java.util Date)
   (java.time Instant ZonedDateTime)
   (java.util.concurrent Future ThreadFactory TimeUnit
                         ExecutorService Executors
                         RejectedExecutionException)))

(set! *warn-on-reflection* true)

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; helpers

(defn- ->epoch-milli [t]
  (cond
    (instance? Long t)          t
    (instance? Instant t)       (.toEpochMilli ^Instant t)
    (instance? Date t)          (.toEpochMilli (.toInstant ^Date t))
    (instance? ZonedDateTime t) (.toEpochMilli (.toInstant ^ZonedDateTime t))

    :else (throw (ex-info "Unsupported time type in schedule" {:type (type t)}))))

(defn- update-success-metrics!
  [start-ms {:keys [num-runs total-runtime last-duration avg-duration last-error]}]
  (let [dur (- (System/currentTimeMillis) start-ms)
        n   (swap! num-runs inc)
        rt  (swap! total-runtime + dur)]
    (reset! last-error nil)
    (reset! last-duration dur)
    (reset! avg-duration (-> rt double (/ n) Math/round long))))

(defn- update-error-metrics!
  [err-type err-obj {:keys [num-errors last-error]}]
  (swap! num-errors inc)
  (reset! last-error (cond-> {:type err-type}
                       err-obj (assoc :error err-obj))))

(defn- named-thread-factory [wname]
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. r)
        (.setName (str "worker-" wname))
        (.setDaemon true))))) ;don't prevent java from exiting if thread is running

(defn- run-task
  "runs `body-fn` in the given `executor` and puts the result on `exec-ch`"
  ^Future [^ExecutorService executor ^Callable body-fn exec-ch]
  (letfn [(runme []
            (let [res (try (body-fn) (catch Throwable e e))]
              ;; core.async channels crash if you put nil. Use a sentinel.
              (async/put! exec-ch (if (nil? res) ::success res))))]
    (try
      (.submit executor ^Callable runme)
      (catch RejectedExecutionException _
        ;; If the executor is shutting down during a race condition, return nil
        nil))))

(defn- shutdown-executor
  [^ExecutorService executor worker-name]
  (.shutdown executor)

  (when-not (.awaitTermination executor 3000 TimeUnit/MILLISECONDS)
    (t/log! {:level :warn :id ::stop-timeout
             :msg "Graceful stop timed out, forcing..."
             :data {:worker-name worker-name}})

    (.shutdownNow executor)

    (when-not (.awaitTermination executor 500 TimeUnit/MILLISECONDS)
      (t/log! {:level :warn :id ::stop-still-running :data {:worker-name worker-name}}))))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; start worker

(defn start-worker!
  "Starts a core.async orchestration loop for a periodic worker.
   Returns a 0-arity function to gracefully stop the worker."
  [worker-name
   {:keys [schedule timeout-ms body-fn]}
   ;; these are all atoms
   {:keys [next-eta dropped-count in-flight? last-run running?] :as props}]

  (let [work-chan (async/chan) ; no buffer!
        stop-chan (async/chan)
        executor  (Executors/newSingleThreadExecutor
                   (named-thread-factory worker-name))]

    ;; 1. THE SCHEDULER LOOP
    (async/go-loop [sq (seq schedule)]
      (when sq
        (let [t-ms         (->epoch-milli (first sq))
              now-ms       (System/currentTimeMillis)
              wait-ms      (- t-ms now-ms)
              max-sleep-ms (* 24 3600 1000)] ;; 1 day

          (reset! next-eta t-ms)

          (if (< max-sleep-ms wait-ms)
            ;; CASE 1: The wait is huge. Sleep for a day, then recalculate.
            ;; keeps thread from going to sleep, and corrects for clock drift
            (let [[_ port] (async/alts! [(async/timeout max-sleep-ms) stop-chan])]
              (if (= port stop-chan)
                (t/log! {:level :info :id ::scheduler-stopped :data {:worker-name worker-name}})
                (recur sq)))

            ;; CASE 2: We are within the final window. Sleep the exact remaining amount.
            (let [tick-ch (if (pos? wait-ms) (async/timeout wait-ms) (async/chan))
                  [_ port]   (if (pos? wait-ms)
                               (async/alts! [tick-ch stop-chan])
                               [nil tick-ch])]

              (if (= port stop-chan)
                (t/log! {:level :info :id ::scheduler-stopped :data {:worker-name worker-name}})
                (do
                  (if @in-flight?
                    (do
                      (swap! dropped-count inc)
                      (t/log! {:level :warn :id ::drop :data {:worker-name worker-name}}))

                    ;; The worker is NOT busy. Safely block until it takes the tick.
                    ;; We include `stop-chan` so we can still shut down cleanly during this micro-wait.
                    (async/alts! [[work-chan t-ms] stop-chan]))
                  ;; use next not rest!
                  (recur (next sq)))))))))

    ;; 2. THE WORKER LOOP
    (async/go-loop []
      (let [[_t-ms port] (async/alts! [work-chan stop-chan])]
        (when (= port work-chan)
          (reset! in-flight? true)
          (reset! last-run (System/currentTimeMillis))

          (let [start-ms     (System/currentTimeMillis)
                timeout-ch   (async/timeout timeout-ms)
                exec-ch      (async/chan)
                ^Future task (run-task executor body-fn exec-ch)]

            #_{:clj-kondo/ignore [:redundant-let]}
            (let [[result port] (async/alts! [exec-ch timeout-ch stop-chan])]
              (cond
                (= port timeout-ch)
                (do (when task (.cancel ^Future task true))
                    (update-error-metrics! :timeout nil props)
                    (t/log! {:level :warn :id ::timeout :data {:worker-name worker-name}}))

                (= port stop-chan)
                (do (when task (.cancel ^Future task true))
                    (t/log! {:level :info :id ::interrupted :data {:worker-name worker-name}}))

                (instance? Throwable result)
                (do (update-error-metrics! :exception result props)
                    (t/log! {:level :error :id ::error :data {:worker-name worker-name} :error result}))

                :else
                (update-success-metrics! start-ms props))))

          (reset! in-flight? false)
          (recur))))

    ;; Return a tear-down function
    (fn stop-fn []
      (reset! running? false)
      (async/close! stop-chan)
      (async/close! work-chan)
      (future (shutdown-executor executor worker-name)))))
