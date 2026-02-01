(ns agentti.lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [agentti.lifecycle :as lc]
   [agentti.registry :as reg])
  (:import
   (java.time Instant)
   (java.util.concurrent ExecutorService)))

;; ensure tests don't leak workers across runs
(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (lc/stop-all-workers! true)))))

(deftest add-worker-registers-once
  (let [cfg {:worker-name :w
             :body-fn (fn [])
             :interval-ms 1000
             :timeout-ms  1000
             :jitter-frac 0.1}
        _   (lc/add-worker! cfg)
        e1  (reg/get-worker :w)
        _   (lc/add-worker! cfg)
        e2  (reg/get-worker :w)]
    (is (some? e1))
    (is (some? e2))
    (is (identical? (:executor e1) (:executor e2)))
    (is (identical? (:schedule e1) (:schedule e2)))))

(deftest stop-worker-removes-and-shuts-down-executor
  (lc/add-worker! {:worker-name "w"
                   :body-fn (fn [])
                   :interval-ms 1000
                   :timeout-ms  1000
                   :jitter-frac 0.1})
  (let [e (reg/get-worker "w")]
    (is (some? e))
    (is (lc/stop-worker! "w"))
    (is (nil? (reg/get-worker "w")))
    (is (.isShutdown ^ExecutorService (:executor e)))))

(deftest stop-all-workers-stops-everything
  (lc/add-worker! {:worker-name "a" :body-fn (fn []) :interval-ms 1000 :timeout-ms 1000})
  (lc/add-worker! {:worker-name "b" :body-fn (fn []) :interval-ms 1000 :timeout-ms 1000})
  (let [res (lc/stop-all-workers! true)]
    (is (= #{"a" "b"} (set (keys res))))
    (is (every? true? (vals res)))
    (is (empty? (reg/registry-snapshot)))))

(deftest add-worker-with-custom-schedule
  (testing "Worker accepts :schedule instead of :interval-ms"
    (let [start-time (Instant/now)
          ;; Create a mock infinite schedule (every second)
          custom-sched (iterate #(.plusMillis ^Instant % 1000) start-time)

          cfg {:worker-name :scheduled-worker
               :body-fn (fn [])
               :schedule custom-sched
               :timeout-ms 1000
               ;; Provide jitter to ensure it gets ignored
               :jitter-ms 500}

          _   (lc/add-worker! cfg)
          w   (reg/get-worker :scheduled-worker)]

      (is (some? w) "Worker should be registered")
      (is (nil? (:interval-ms w)) "Interval should be nil for custom schedule")
      (is (= 0 (:jitter-ms w)) "Jitter should be forced to 0 for custom schedule")

      (testing "next-eta integration"
        (is (some? (:next-eta w)))
        (is (instance? clojure.lang.Atom (:next-eta w)))))))
