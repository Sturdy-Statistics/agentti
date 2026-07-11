(ns agentti.admin
  (:require
   [agentti.util :as util]
   [agentti.registry :as reg])
  (:import
   (java.time Instant)))

(set! *warn-on-reflection* true)

(defn- dref
  "Deref an atom-like value, returning nil if it is nil."
  [a]
  (when a @a))

(defn- fmt-instant
  "Format epoch-ms as an Instant string, or nil."
  [epoch-ms]
  (some-> epoch-ms Instant/ofEpochMilli str))

(defn- fmt-eta-in
  "Format seconds until eta-ms, or nil if eta-ms missing."
  [now-ms eta-ms]
  (when eta-ms
    (util/format-duration (max 0 (quot (- eta-ms now-ms) 1000)))))

(defn- render-last-error
  "Human-readable summary for last error map, or nil."
  [err]
  (when err
    (let [msg (some-> ^Throwable (:error err) .getMessage)]
     (case (:type err)
       :timeout     (or msg "Timed out")
       :exception   (str "Exception: " msg)
       :rejected    "Rejected (executor shutdown?)"
       :cancelled   "Canceled"
       :interrupted "Interrupted"
       (str "Error: " (pr-str err))))))

(defn- uptime-str
  [now-ms started-at]
  (if started-at
    (util/format-duration (quot (- now-ms started-at) 1000))
    "unknown"))

(defn- since-last-run-str
  [now-ms last-run-ms]
  (when last-run-ms
    (util/format-duration (quot (- now-ms last-run-ms) 1000))))

(defn- worker->admin-row
  "Build the admin/status map for a single worker registry entry."
  [now-ms wname {:keys [started-at
                        running?
                        next-eta
                        timeout-ms
                        num-runs num-errors last-error
                        in-flight? dropped-count
                        last-run last-duration _total-runtime avg-duration]}]
  (let [lr       (dref last-run)
        eta-ms   (some-> next-eta deref)
        is-infl? (boolean (dref in-flight?))
        is-run?  (boolean (dref running?))
        status   (cond
                   is-infl? :running
                   is-run?  :idle
                   :else    :stopped)]

    {:worker-name    wname
     :status         status
     :running?       is-run?
     :timeout-ms     timeout-ms

     :num-runs       (dref num-runs)
     :num-errors     (dref num-errors)
     :last-error     (-> last-error dref render-last-error)

     :in-flight?     is-infl?
     :dropped        (dref dropped-count)
     :last-run       (fmt-instant lr)
     :last-duration  (dref last-duration)
     :avg-duration   (dref avg-duration)

     :uptime         (uptime-str now-ms started-at)
     :since-last-run (since-last-run-str now-ms lr)
     :next-run-eta   (fmt-instant eta-ms)
     :next-run-in    (fmt-eta-in now-ms eta-ms)}))

(defn list-workers
  "Return a vector of worker status maps for use in admin UIs."
  []
  (let [now-ms   (System/currentTimeMillis)
        snapshot (into (sorted-map) (reg/registry-snapshot))]
    (mapv (fn [[wname entry]]
            (worker->admin-row now-ms wname entry))
          snapshot)))
