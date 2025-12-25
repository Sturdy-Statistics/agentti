(ns agentti.lifecycle
  (:require
   [chime.core :as chime]
   [agentti.util :as u]
   [agentti.registry :as reg]
   [agentti.runner :as run]
   [agentti.schedules :as s]
   [taoensso.telemere :as t])
  (:import
   (clojure.lang LazySeq)
   (java.lang AutoCloseable)
   (java.util.concurrent Executors ExecutorService TimeUnit
                         ThreadFactory ThreadPoolExecutor)))

(set! *warn-on-reflection* true)

(defn- named-thread-factory [wname]
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. r)
        (.setName (str "worker-" wname))
        (.setDaemon true))))) ;don't prevent java from exiting if thread is running

(defn- make-scheduler
  ^AutoCloseable [^LazySeq times
                  run-once-fn
                  ^String worker-name
                  last-error-atom]
  (chime/chime-at
   times
   run-once-fn
   {:thread-factory (named-thread-factory (str worker-name "-scheduler"))
    :error-handler
    (fn [e]
      ;; Exceptions escaping our run-once! wrapper
      (reset! last-error-atom {:type :exception :error e})
      (t/log! {:level :error
               :id ::schedule-error
               :data {:worker worker-name}
               :error e})
      ;; return true to continue schedule after error
      true)
    :on-finished (fn []
                   (t/log! {:level :info
                            :id ::shutdown
                            :data {:worker worker-name}}))}))

(defn add-worker!
  "Register and launch a periodic worker using chime.

  Required keys:
    :worker-name  (string/keyword, unique)
    :body-fn      (0-arg fn)
    :timeout-ms   (ms)
    :interval-ms  (ms; periodic cadence)

  No-op if a worker with same name exists.

  Returns nil."
  [{:keys [worker-name interval-ms timeout-ms body-fn jitter-frac jitter-ms] :as config}]
  (when-not (and worker-name body-fn interval-ms timeout-ms)
    (throw (ex-info "Missing required worker config keys"
                    {:expected [:worker-name :body-fn :interval-ms :timeout-ms]
                     :config config})))

  (let [wname (u/normalize-name worker-name)]
    (when-not (reg/get-worker wname)
      (let [worker-props   {:started-at     (System/currentTimeMillis)
                            :num-runs       (atom 0)
                            :num-errors     (atom 0)
                            :last-error     (atom nil)
                            :in-flight?     (atom false)
                            :dropped-count  (atom 0)
                            :last-run       (atom nil)
                            :last-duration  (atom nil)
                            :total-runtime  (atom 0)
                            :avg-duration   (atom nil)}

            ;; single-threaded executor isolates jobs and lets us cancel on timeout
            executor       (Executors/newSingleThreadExecutor
                            (named-thread-factory wname))

            ;; Create the watcher executor per worker
            watcher        (Executors/newSingleThreadExecutor
                            (named-thread-factory (str wname "-watcher")))

            ;; single-arg function to pass to chime
            ;; - wraps body-fn with logging, metrics, and error handling
            run-once!      (run/make-task-runner
                            wname body-fn executor watcher timeout-ms
                            worker-props)

            ;; Build a periodic schedule starting from now
            {:keys [times next-eta* jitter-ms]} (s/build-times!
                                                 {:interval-ms interval-ms
                                                  :jitter-frac jitter-frac
                                                  :jitter-ms   jitter-ms})

            ;; Start schedule; returns AutoCloseable
            schedule       (make-scheduler times
                                           run-once!
                                           wname
                                           (:last-error worker-props))]

        (reg/put-worker!
         wname
         (assoc worker-props
                :schedule       schedule
                :executor       executor
                :watcher        watcher
                :next-eta       next-eta*
                ;;
                :interval-ms    interval-ms
                :timeout-ms     timeout-ms
                :jitter-ms      jitter-ms))

        (t/log! {:level :info :id ::start
                 :msg (str "Started worker: " wname
                           (when (pos? jitter-ms)
                             (str " (jitter ±" jitter-ms " ms)")))})))))

(defn- force-shutdown-executor
  [wname executor]

  (t/log! {:level :info :id ::stop
           :msg (str "Force-stopping worker: " wname)})

  (.shutdownNow ^ExecutorService executor)

  (when-not (.awaitTermination ^ExecutorService executor 500 TimeUnit/MILLISECONDS)
    (t/log! {:level :warn
             :id ::stop-still-running
             :msg (str "Executor still running after forced stop: " wname)})))

(defn- shutdown-executor
  [wname executor force?]
  (if force?
    ;; shutdown immediately
    (force-shutdown-executor wname executor)

    ;; shutdown gracefully; immediate after 3 sec
    (do
      (.shutdown ^ExecutorService executor)
      ;; Wait briefly for the current task to finish
      (when-not (.awaitTermination ^ExecutorService executor
                                   3000 TimeUnit/MILLISECONDS)
        (t/log! {:level :warn
                 :id ::stop-timeout
                 :msg (str "Graceful stop timed out for " wname ", forcing...")})
        (force-shutdown-executor wname executor))))

  ;; Purge cancelled tasks if it's a ThreadPoolExecutor
  (when (instance? ThreadPoolExecutor executor)
    (.purge ^ThreadPoolExecutor executor)))

(defn- cancel-schedule
  "stop future ticks for chime `schedule`"
  [wname schedule]
  (when schedule
    (try
      (.close ^AutoCloseable schedule)
      (catch Throwable e
        (t/log! {:level :warn
                 :id ::schedule-close-error
                 :msg (str "Error closing schedule for " wname)
                 :error e})))))

(defn stop-worker!
  "Stops the named worker. If `force?` is truthy, interrupts in-flight task(s).
   Returns true if a worker was found (and shutdown initiated), else nil."
  ([name] (stop-worker! name false))
  ([name force?]
   (let [wname (u/normalize-name name)]
     (when-let [{:keys [schedule watcher executor]} (reg/get-worker wname)]

       ;; 1) Stop future ticks
       (cancel-schedule wname schedule)

       ;; 2) Stop executor (graceful or forceful)
       (try
         (shutdown-executor wname executor force?)
         (shutdown-executor (str wname "-watcher") watcher force?)

         (catch InterruptedException _e
           ;; Preserve interrupt status and force shutdown
           (.interrupt (Thread/currentThread))
           (.shutdownNow ^ExecutorService executor)
           (.shutdownNow ^ExecutorService watcher)
           (t/log! {:level :info
                    :id ::stop-interrupted
                    :msg (str "Interrupted while stopping " wname)}))

         (catch Throwable e
           (t/log! {:level :error
                    :id ::stop-error
                    :msg (str "Error stopping worker resources (executor/watcher threads) for " wname)
                    :error e})))

       ;; 3) Remove from registry last so we still had handles if something failed above
       (reg/remove-worker! wname)

       (t/log! {:level :info :id ::stop
                :msg (str "Stopped worker: " wname)})
       true))))

(defn stop-all-workers!
  "Stops all workers. With `force?` truthy, interrupts in-flight tasks.
   Returns a map of {worker-name -> true|nil}."
  ([] (stop-all-workers! false))
  ([force?]
   (let [names   (vec (keys (reg/registry-snapshot)))
         _       (t/log! {:level :info
                          :id ::stop-all
                          :msg (str "Stopping all workers (" (count names) "), force?=" (boolean force?) )})
         results (into {} (map (fn [n] [n (stop-worker! n force?)]) names))
         stopped (count (filter some? (vals results)))]
     (t/log! {:level :info
              :id ::stop-all-done
              :msg (str "Stopped " stopped " of " (count names) " workers")})
     results)))
