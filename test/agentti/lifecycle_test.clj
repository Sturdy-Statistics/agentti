(ns agentti.lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [agentti.engine :as engine]
   [agentti.lifecycle :as lc]
   [agentti.registry :as reg]
   [agentti.schedule :as sched])
  (:import
   (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(defn- await-latch
  [^CountDownLatch latch timeout-ms]
  (.await latch timeout-ms TimeUnit/MILLISECONDS))

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

(deftest concurrent-add-worker-starts-once
  (let [started (atom 0)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        cfg     {:worker-name :race
                 :body-fn     (fn [])
                 :schedule    []
                 :timeout-ms  1000}]
    (with-redefs [engine/start-worker!
                  (fn [_worker-name _config _worker-props]
                    (swap! started inc)
                    (.countDown entered)
                    (await-latch release 1000)
                    (fn [] nil))]
      (let [calls (doall (repeatedly 8 #(future (lc/add-worker! cfg))))]
        (is (await-latch entered 1000) "at least one add attempt should start the worker")
        (Thread/sleep 100)
        (.countDown release)
        (doseq [call calls]
          @call)
        (is (= 1 @started) "concurrent duplicate add-worker! calls must start only one worker")
        (is (some? (reg/get-worker :race)))))))

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

(deftest concurrent-stop-worker-stops-once
  (let [stopped (atom 0)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (reg/put-worker! :race-stop
                     {:stop-fn (fn []
                                 (swap! stopped inc)
                                 (.countDown entered)
                                 (await-latch release 1000))})
    (let [calls (doall (repeatedly 8 #(future (lc/stop-worker! :race-stop))))]
      (is (await-latch entered 1000) "at least one stop attempt should invoke stop-fn")
      (Thread/sleep 100)
      (.countDown release)
      (let [results (mapv deref calls)]
        (is (= 1 (count (filter true? results)))
            "only one concurrent stop-worker! caller should stop the registered worker"))
      (is (= 1 @stopped) "concurrent stop-worker! calls must invoke stop-fn once")
      (is (nil? (reg/get-worker :race-stop))))))

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
                                  :timeout-ms 100})))) ;; Missing worker-name

  (testing "Requires a positive integer timeout"
    (doseq [timeout-ms [0 -1 1.5 nil]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lc/add-worker! {:worker-name :invalid-timeout
                                    :body-fn     (fn [])
                                    :schedule    []
                                    :timeout-ms  timeout-ms}))
          (str "should reject timeout-ms " (pr-str timeout-ms))))

    (is (nil? (lc/add-worker! {:worker-name :minimum-timeout
                               :body-fn     (fn [])
                               :schedule    []
                               :timeout-ms  1})))
    (is (some? (reg/get-worker :minimum-timeout))))

  (testing "Requires a nonblank string or keyword worker name"
    (doseq [worker-name ["" "  " (keyword "") 42]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (lc/add-worker! {:worker-name worker-name
                                    :body-fn     (fn [])
                                    :schedule    []
                                    :timeout-ms  100}))
          (str "should reject worker-name " (pr-str worker-name))))))

(deftest namespaced-keyword-workers-remain-distinct
  (let [config {:body-fn    (fn [])
                :schedule   []
                :timeout-ms 100}]
    (lc/add-worker! (assoc config :worker-name :jobs/refresh))
    (lc/add-worker! (assoc config :worker-name :refresh))

    (is (= #{"jobs/refresh" "refresh"}
           (set (keys (reg/registry-snapshot)))))
    (is (some? (reg/get-worker :jobs/refresh)))
    (is (some? (reg/get-worker :refresh)))
    (is (not (identical? (reg/get-worker :jobs/refresh)
                         (reg/get-worker :refresh))))
    (is (true? (lc/stop-worker! :jobs/refresh)))
    (is (some? (reg/get-worker :refresh)))
    (is (true? (lc/stop-worker! :refresh)))))
