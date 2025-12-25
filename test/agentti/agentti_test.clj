(ns agentti.agentti-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [agentti.registry :as reg]
   [agentti.runner :as run]
   [agentti.lifecycle :as l]
   [agentti.admin :as a]
   [agentti.test-support :as ts]
   [taoensso.telemere :as t])
  (:import
   (java.util.concurrent Executors TimeUnit)
   (java.time Instant)))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Test helpers / fixtures

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        ;; make sure we don't bleed workers across tests
        (ts/with-quiet-logging
          (l/stop-all-workers! true))))))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Unit tests for helpers

(deftest should-skip?
  (let [flag (atom false)
        drops (atom 0)]
    (is (false? (#'run/should-skip? "w" flag drops))
        "first call should mark in-flight and NOT skip")
    (is (true? (#'run/should-skip? "w" flag drops))
        "second call should skip because in-flight is true")
    (is (= 1 @drops))
    ;; release and try again
    (reset! flag false)
    (is (false? (#'run/should-skip? "w" flag drops)))
    (is (= 1 @drops) "drop count only increments when we actually skip")))

(deftest make-task-runner-updates-metrics
  (ts/with-quiet-logging
    (let [num-runs       (atom 0)
          num-errors     (atom 0)
          last-error     (atom nil)
          in-flight?     (atom false)
          dropped-count  (atom 0)
          last-run       (atom nil)
          last-duration  (atom nil)
          total-runtime  (atom 0)
          avg-duration   (atom nil)
          exec           (Executors/newSingleThreadExecutor)
          watch          (Executors/newSingleThreadExecutor)
          runner         (#'run/make-task-runner
                          "unit"
                          (fn [] (Thread/sleep 30))
                          exec
                          watch
                          200
                          {:num-runs num-runs
                           :num-errors num-errors
                           :last-error last-error
                           :in-flight? in-flight?
                           :dropped-count dropped-count
                           :last-run last-run
                           :last-duration last-duration
                           :total-runtime total-runtime
                           :avg-duration avg-duration})]
      (try
        ;; invoke once
        (runner (Instant/now))
        (is (ts/eventually #(pos? @num-runs) 500) "should count a success")
        (is (some? @last-run))
        (is (pos? (or @last-duration 0)))
        (is (ts/eventually #(false? @in-flight?) 500) "must release in-flight flag")
        ;; trigger drop-if-running
        (reset! in-flight? true)
        (runner (Instant/now))
        (is (= 1 @dropped-count))
        (finally
          (.shutdownNow exec)
          (.shutdownNow watch)
          (.awaitTermination exec  500 TimeUnit/MILLISECONDS)
          (.awaitTermination watch 500 TimeUnit/MILLISECONDS))))))

(deftest make-task-runner-records-timeout
  (let [num-runs       (atom 0)
        num-errors     (atom 0)
        last-error     (atom nil)
        in-flight?     (atom false)
        dropped-count  (atom 0)
        last-run       (atom nil)
        last-duration  (atom nil)
        total-runtime  (atom 0)
        avg-duration   (atom nil)
        exec           (Executors/newSingleThreadExecutor)
        watch          (Executors/newSingleThreadExecutor)
        runner         (#'run/make-task-runner
                        "timeout"
                        (fn [] (Thread/sleep 200))
                        exec
                        watch
                        50
                        {:num-runs num-runs
                         :num-errors num-errors
                         :last-error last-error
                         :in-flight? in-flight?
                         :dropped-count dropped-count
                         :last-run last-run
                         :last-duration last-duration
                         :total-runtime total-runtime
                         :avg-duration avg-duration})]
    (try
      (runner (Instant/now))
      (is (ts/eventually #(= :timeout (:type @last-error)) 500))
      (is (pos? @num-errors))
      (is (ts/eventually #(false? @in-flight?) 500))
      (finally
        (.shutdownNow exec)
        (.shutdownNow watch)
        (.awaitTermination exec  500 TimeUnit/MILLISECONDS)
        (.awaitTermination watch 500 TimeUnit/MILLISECONDS)))))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Integration tests (chime + lifecycle)

(deftest add-and-stop-worker-basic
  (ts/with-quiet-logging
    (let [w :hello
          _ (l/add-worker! {:worker-name w
                            :interval-ms 100
                            :timeout-ms  250
                            :jitter-frac 0.1
                            :body-fn     (fn [] (t/log! {:level :debug :id :test/tick}))})]
      (is (contains? (reg/registry-snapshot) "hello"))
      (is (ts/eventually #(-> (reg/registry-snapshot) (get "hello") :num-runs deref pos?) 3000))
      (is (true? (l/stop-worker! w)))
      (is (nil? (l/stop-worker! w)) "stopping twice returns nil (not found)"))))

(deftest drop-if-running-behavior
  (ts/with-quiet-logging
    (let [w :slow
          _ (l/add-worker! {:worker-name w
                            :interval-ms 30
                            :timeout-ms  500
                            :jitter-frac 0.1
                            :body-fn     (fn [] (Thread/sleep 120))})]
      (is (ts/eventually #(-> (reg/registry-snapshot) (get "slow")) 200))
      (Thread/sleep 400) ;; let it run / drop a few ticks
      (let [{:keys [num-runs dropped-count in-flight?]} (get (reg/registry-snapshot) "slow")]
        (is (>= @dropped-count 1) "should have dropped at least one tick while job was running")
        (is (pos? @num-runs))
        (is (boolean? @in-flight?)))
      (l/stop-worker! w))))

(deftest timeout-path-integration
  (ts/with-quiet-logging
    (let [w :timeout-int
          _ (l/add-worker! {:worker-name w
                            :interval-ms 50
                            :timeout-ms  30
                            :jitter-frac 0.1
                            :body-fn     (fn [] (Thread/sleep 200))})]
      (is (ts/eventually #(-> (reg/registry-snapshot)
                              (get "timeout-int") :num-errors deref pos?) 3000))
      (let [{:keys [last-error]} (get (reg/registry-snapshot) "timeout-int")]
        (is (= :timeout (:type @last-error))))
      (l/stop-worker! w))))

(deftest list-workers-shape-and-next-eta
  (let [w1 :l1
        w2 :l2]
    (l/add-worker! {:worker-name w1 :interval-ms 100 :timeout-ms 200 :body-fn (fn [])})
    (l/add-worker! {:worker-name w2 :interval-ms 200 :timeout-ms 200 :body-fn (fn [])})
    (let [rows (a/list-workers)]
      (is (= #{"l1" "l2"} (set (map :worker-name rows))))
      (doseq [row rows]
        (is (contains? row :next-run-eta))
        (is (contains? row :next-run-in))
        (is (some? (:status row)))))
    (l/stop-all-workers!)))
