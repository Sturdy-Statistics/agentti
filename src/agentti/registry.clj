(ns agentti.registry
  (:require
   [agentti.util :refer [normalize-name]]))

(set! *warn-on-reflection* true)

;; workers* maps canonical string worker-name -> entry map
(defonce ^:private workers* (atom {}))

(defn registry-snapshot
  "Return the raw registry map (name->entry)."
  []
  @workers*)

(defn get-worker
  "Return the worker entry for `name`, or nil."
  [name]
  (get @workers* (normalize-name name)))

(defn worker-exists?
  "True if a worker with `name` (string/keyword) is registered."
  [name]
  (some? (get-worker name)))

(defn put-worker!
  "Associate `entry-map` under `wname` (string/keyword/etc). Returns entry-map."
  [wname entry-map]
  (let [k (normalize-name wname)]
    (swap! workers* assoc k entry-map)
    entry-map))

(defn remove-worker!
  "Remove worker `name` from the registry. Returns the removed entry (or nil)."
  [name]
  (let [k (normalize-name name)
        old (get @workers* k)]
    (swap! workers* dissoc k)
    old))
