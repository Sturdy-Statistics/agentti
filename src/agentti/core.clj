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
  "Stops a worker by name. Initiates a graceful shutdown, falling back
   to a forced interrupt if the worker does not exit within 3 seconds."
  [name]
  (lifecycle/stop-worker! name))

(defn stop-all-workers!
  "Stops all registered workers. Initiates a graceful shutdown, falling back
   to a forced interrupt if a worker does not exit within 3 seconds.
   Returns a map of {worker-name -> true|nil}."
  []
  (lifecycle/stop-all-workers!))

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
