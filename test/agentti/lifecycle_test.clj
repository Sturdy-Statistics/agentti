(ns agentti.lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [agentti.lifecycle :as lc]
   [agentti.registry :as reg]
   [agentti.schedule :as sched]))

;; ensure tests don't leak workers across runs
(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (lc/stop-all-workers!)))))

(deftest add-worker-registers-once
  (let [cfg {:worker-name :w
             :body-fn     (fn [])
             :schedule    (sched/periodic-seq 1000 {:jitter-frac 0.1})
             :timeout-ms  1000}
        _   (lc/add-worker! cfg)
        e1  (reg/get-worker :w)
        _   (lc/add-worker! cfg)
        e2  (reg/get-worker :w)]
    (is (some? e1))
    (is (some? e2))
    ;; Since it's a no-op the second time, the generated stop-fn should be identical
    (is (identical? (:stop-fn e1) (:stop-fn e2)))))

(deftest stop-worker-removes-from-registry
  (lc/add-worker! {:worker-name "w"
                   :body-fn     (fn [])
                   :schedule    (sched/periodic-seq 1000)
                   :timeout-ms  1000})
  (let [e (reg/get-worker "w")]
    (is (some? e))
    (is (true? (lc/stop-worker! "w")))
    (is (nil? (reg/get-worker "w")))
    (is (nil? (lc/stop-worker! "w")) "Stopping twice returns nil")))

(deftest stop-all-workers-stops-everything
  (lc/add-worker! {:worker-name "a" :body-fn (fn []) :schedule (sched/periodic-seq 1000) :timeout-ms 1000})
  (lc/add-worker! {:worker-name "b" :body-fn (fn []) :schedule (sched/periodic-seq 1000) :timeout-ms 1000})
  (let [res (lc/stop-all-workers!)]
    (is (= #{"a" "b"} (set (keys res))))
    (is (every? true? (vals res)))
    (is (empty? (reg/registry-snapshot)))))

(deftest add-worker-validates-config
  (testing "Throws on missing required keys"
    (is (thrown? clojure.lang.ExceptionInfo
                 (lc/add-worker! {:worker-name "w"
                                  :body-fn     (fn [])}))) ;; Missing schedule and timeout

    (is (thrown? clojure.lang.ExceptionInfo
                 (lc/add-worker! {:worker-name "w"
                                  :schedule    (fn [] [])
                                  :timeout-ms  100}))) ;; Missing body-fn

    (is (thrown? clojure.lang.ExceptionInfo
                 (lc/add-worker! {:body-fn    (fn [])
                                  :schedule   (fn [] [])
                                  :timeout-ms 100}))))) ;; Missing worker-name
