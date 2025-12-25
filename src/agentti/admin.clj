(ns agentti.admin
  (:require
   [agentti.util :as util]
   [agentti.registry :as reg])
  (:import
   (java.time Instant)
   (java.util.concurrent ExecutorService)))

(set! *warn-on-reflection* true)

(defn- dref
  "Deref an atom-like value, returning nil if it is nil."
  [a]
  (when a @a))

(defn- executor-running?
  [executor]
  (and executor
       (not (.isShutdown ^ExecutorService executor))))

(defn- worker-status
  "Compute {:running? <bool> :status <kw>} from executor + in-flight? flag."
  [executor in-flight]
  (let [running? (executor-running? executor)
        status   (cond
                   in-flight :running
                   running?  :idle
                   :else     :stopped)]
    {:running? running?
     :status   status}))

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
    (case (:type err)
      :timeout     "Timed out"
      :exception   (str "Exception: " (some-> ^Throwable (:error err) .getMessage))
      :rejected    "Rejected (executor shutdown?)"
      :cancelled   "Cancelled"
      :interrupted "Interrupted"
      (str "Error: " (pr-str err)))))

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
  [now-ms wname {:keys [executor next-eta
                        interval-ms timeout-ms jitter-ms
                        started-at
                        num-runs num-errors last-error
                        in-flight? dropped-count
                        last-run last-duration _total-runtime avg-duration]}]
  (let [lr     (dref last-run)
        ld     (dref last-duration)
        av     (dref avg-duration)
        nr     (dref num-runs)
        ne     (dref num-errors)
        dr     (dref dropped-count)
        infl   (boolean (dref in-flight?))
        err    (dref last-error)
        eta-ms (some-> next-eta deref)
        {:keys [running? status]} (worker-status executor infl)]
    {:worker-name     wname
     :status          status
     :running?        (boolean running?)

     :interval-ms     interval-ms
     :timeout-ms      timeout-ms
     :jitter-ms       jitter-ms

     :num-runs        nr
     :num-errors      ne
     :last-error      (render-last-error err)
     :in-flight?      infl
     :dropped         dr
     :last-run        (fmt-instant lr)
     :last-duration   ld
     ;; total-runtime
     :avg-duration    av

     :uptime          (uptime-str now-ms started-at)
     :since-last-run  (since-last-run-str now-ms lr)
     :next-run-eta    (fmt-instant eta-ms)
     :next-run-in     (fmt-eta-in now-ms eta-ms)}))

(defn list-workers
  "Return a vector of worker status maps for use in admin UIs."
  []
  (let [now-ms   (System/currentTimeMillis)
        snapshot (into (sorted-map) (reg/registry-snapshot))]
    (mapv (fn [[wname entry]]
            (worker->admin-row now-ms wname entry))
          snapshot)))
