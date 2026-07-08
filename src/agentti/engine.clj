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
  "runs `body-fn` in the given `executor` and puts the result on `exec-ch`.
   Swaps nils for ::success to prevent core.async crashes."
  ^Future [^ExecutorService executor ^Callable body-fn exec-ch]
  (letfn [(runme []
            (let [res (try (body-fn) (catch Throwable e e))]
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

  (let [work-chan (async/chan) ;; Unbuffered, direct handoff
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

          (cond
            ;; CASE 1: The tick is STALE.
            ;; Recur immediately and fast-forward to the present.
            (< wait-ms -2000)
            (do
              (swap! dropped-count inc)
              (recur (next sq)))

            ;; CASE 2: The wait is HUGE. Sleep for a day, then recalculate.
            ;; Keeps thread from going to sleep forever and corrects for clock drift.
            (< max-sleep-ms wait-ms)
            (let [[_ port] (async/alts! [(async/timeout max-sleep-ms) stop-chan])]
              (if (= port stop-chan)
                (t/log! {:level :info :id ::scheduler-stopped :data {:worker-name worker-name}})
                (recur sq)))

            ;; CASE 3: We are within the final window. Sleep the exact remaining amount.
            :else
            (let [tick-ch  (if (pos? wait-ms)
                             (async/timeout wait-ms)
                             (doto (async/chan) (async/close!)))
                  [_ port] (async/alts! [tick-ch stop-chan])]

              (if (= port stop-chan)
                (t/log! {:level :info :id ::scheduler-stopped :data {:worker-name worker-name}})
                ;; CAS: Atomically acquire the lock.
                (if (compare-and-set! in-flight? false true)
                  ;; Lock acquired. Hand the tick directly to the worker.
                  (let [[accepted? port] (async/alts! [[work-chan t-ms] stop-chan])]
                    (if (and (= port work-chan) accepted?)
                      ;; Worker accepted the tick and will release in-flight? after processing.
                      (recur (next sq))
                      ;; Handoff failed or stop won; scheduler must release in-flight?.
                      (reset! in-flight? false)))

                  ;; Lock denied. The worker is busy.
                  (do
                    (swap! dropped-count inc)
                    (t/log! {:level :warn :id ::drop :data {:worker-name worker-name}})
                    (recur (next sq))))))))))

    ;; 2. THE WORKER LOOP
    (async/go-loop []
      (let [[t-ms port] (async/alts! [work-chan stop-chan])]
        (when (and (= port work-chan) (some? t-ms)) ; t-ms is nil if work-chan closed
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

          ;; Release the lock AFTER processing is complete
          (reset! in-flight? false)
          (recur))))

    ;; Return a tear-down function
    (fn stop-fn []
      (reset! running? false)
      (async/close! stop-chan)
      (async/close! work-chan)
      (future (shutdown-executor executor worker-name)))))
