(ns agentti.core
  (:require
   [agentti.lifecycle :as lifecycle]
   [agentti.admin :as admin]
   [agentti.registry :as registry]))

(set! *warn-on-reflection* true)

;;; Lifecycle

(defn add-worker!
  "Register and start a worker. See `agentti.lifecycle/add-worker!` for options."
  [config]
  (lifecycle/add-worker! config))

(defn stop-worker!
  "Stop a worker by name. With `force?` truthy, interrupts in-flight work."
  ([name] (lifecycle/stop-worker! name))
  ([name force?] (lifecycle/stop-worker! name force?)))

(defn stop-all-workers!
  "Stop all workers. With `force?` truthy, interrupts in-flight work.
  Returns a map of {worker-name -> true|nil}."
  ([] (lifecycle/stop-all-workers!))
  ([force?] (lifecycle/stop-all-workers! force?)))

;;; Introspection / admin

(defn list-workers
  "Return a vector of worker status maps for admin/dashboards."
  []
  (admin/list-workers))

;;; Registry

(defn registry-snapshot
  "Return a snapshot of the internal worker registry (name->entry).
  Intended for debugging/tests; dashboards should usually prefer `list-workers`."
  []
  (registry/registry-snapshot))

(defn get-worker
  "Return the raw worker entry for `name`, or nil."
  [name]
  (registry/get-worker name))

(defn worker-exists?
  "True if a worker named `name` exists."
  [name]
  (registry/worker-exists? name))
