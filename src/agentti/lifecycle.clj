(ns agentti.lifecycle
  (:require
   [agentti.util :as u]
   [agentti.registry :as reg]
   [agentti.engine :as engine]
   [taoensso.telemere :as t]))

(set! *warn-on-reflection* true)

(defn add-worker!
  "Register and launch a periodic worker using chime.

  Required keys:
    :worker-name  (string/keyword, unique)
    :body-fn      (0-arg fn)
    :timeout-ms   (ms)
    :schedule     (a chime sequence of Instants/ZonedDateTimes)

  No-op if a worker with same name exists.

  Returns nil."
  [{:keys [worker-name body-fn schedule timeout-ms] :as config}]
  (when-not (and worker-name
                 (fn? body-fn)
                 (seqable? schedule)
                 (nat-int? timeout-ms))
    (let [safe-config (cond-> config
                        schedule (assoc :schedule "<infinite-sequence>"))]
     (throw (ex-info "Missing required worker config keys"
                     {:expected [:worker-name :body-fn :schedule :timeout-ms]
                      :config safe-config}))))

  (let [wname (u/normalize-name worker-name)]
    (when-not (reg/get-worker wname)
      (let [worker-props {:started-at     (System/currentTimeMillis)
                          :running?       (atom true)
                          :next-eta       (atom nil)
                          :num-runs       (atom 0)
                          :num-errors     (atom 0)
                          :last-error     (atom nil)
                          :in-flight?     (atom false)
                          :dropped-count  (atom 0)
                          :last-run       (atom nil)
                          :last-duration  (atom nil)
                          :total-runtime  (atom 0)
                          :avg-duration   (atom nil)}

            stop-fn      (engine/start-worker! wname config worker-props)]

        (reg/put-worker!
         wname
         (assoc worker-props
                :stop-fn    stop-fn
                :timeout-ms timeout-ms))

        (t/log! {:level :info :id ::start :data {:worker-name wname}})))))

(defn stop-worker!
  "Stops the named worker. If `force?` is truthy, interrupts in-flight task(s).
   Returns true if a worker was found (and shutdown initiated), else nil."
  [worker-name]
  (let [wname (u/normalize-name worker-name)]
    (when-let [{:keys [stop-fn]} (reg/get-worker wname)]
      (stop-fn)
      (reg/remove-worker! wname)
      (t/log! {:level :info :id ::stop :data {:worker-name wname}})
      true)))

(defn stop-all-workers!
  "Stops all workers. Returns a map of {worker-name -> true|nil}."
  []
  (let [names   (vec (keys (reg/registry-snapshot)))
        _       (t/log! {:level :info :id ::stop-all :msg "Stopping all workers"
                         :data {:number (count names)}})
        results (into {} (map (fn [n] [n (stop-worker! n)]) names))
        stopped (count (filter some? (vals results)))]
    (t/log! {:level :info :id ::stop-all-done
             :msg (str "Stopped " stopped " of " (count names) " workers")
             :data {:stopped stopped :total (count names)}})
    results))
