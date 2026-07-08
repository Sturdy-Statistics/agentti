(ns agentti.agentti-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [agentti.registry :as reg]
   [agentti.engine :as engine]
   [agentti.lifecycle :as l]
   [agentti.schedule :as sched]
   [agentti.admin :as a]
   [agentti.test-support :as ts]
   [taoensso.telemere :as t])
  (:import
   (java.time Instant)))

(set! *warn-on-reflection* true)

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Test helpers / fixtures

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        ;; Make sure we don't bleed workers across tests.
        (ts/with-quiet-logging
          (l/stop-all-workers!))))))

(defn- mock-worker-props []
  {:started-at    (System/currentTimeMillis)
   :running?      (atom true)
   :next-eta      (atom nil)
   :num-runs      (atom 0)
   :num-errors    (atom 0)
   :last-error    (atom nil)
   :in-flight?    (atom false)
   :dropped-count (atom 0)
   :last-run      (atom nil)
   :last-duration (atom nil)
   :total-runtime (atom 0)
   :avg-duration  (atom nil)})

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Unit tests for the core.async engine
;;; We can test the engine safely by providing finite sequences of Instants.

(deftest engine-success-and-metrics
  (ts/with-quiet-logging
    (let [props (mock-worker-props)
          now   (Instant/now)
          ;; Passing a single-element list causes the engine to execute it and naturally exit
          stop! (engine/start-worker!
                 "unit-success"
                 {:schedule     [now]
                  :timeout-ms   1000
                  :body-fn      (fn [] (Thread/sleep 10))}
                 props)]
      (is (ts/eventually #(pos? @(:num-runs props)) 500) "should record a success")
      (is (some? @(:last-run props)))
      (is (pos? (or @(:last-duration props) 0)))
      (is (false? @(:in-flight? props)) "must release in-flight flag")
      (stop!))))

(deftest engine-timeout-handling
  (ts/with-quiet-logging
    (let [props (mock-worker-props)
          now   (Instant/now)
          stop! (engine/start-worker!
                 "unit-timeout"
                 {:schedule     [now]
                  :timeout-ms   50
                  :body-fn      (fn [] (Thread/sleep 200))}
                 props)]
      (is (ts/eventually #(= :timeout (:type @(:last-error props))) 500))
      (is (pos? @(:num-errors props)))
      (is (false? @(:in-flight? props)))
      (stop!))))

(deftest engine-stop-releases-in-flight
  (ts/with-quiet-logging
    (let [props (mock-worker-props)
          stop! (engine/start-worker!
                 "unit-stop-release"
                 {:schedule   [(Instant/now)]
                  :timeout-ms 1000
                  :body-fn    (fn [] (Thread/sleep 200))}
                 props)]
      (is (ts/eventually #(true? @(:in-flight? props)) 500)
          "worker should enter in-flight state")
      (stop!)
      (is (false? @(:running? props)))
      (is (ts/eventually #(false? @(:in-flight? props)) 500)
          "stop should release the in-flight flag"))))

(deftest engine-stop-does-not-treat-closed-work-chan-as-tick
  (ts/with-quiet-logging
    (let [runs (atom 0)]
      (dotimes [i 100]
        (let [props (mock-worker-props)
              stop! (engine/start-worker!
                     (str "unit-stop-closed-work-" i)
                     {:schedule   [(.. Instant now (plusMillis 10000))]
                      :timeout-ms 1000
                      :body-fn    (fn [] (swap! runs inc))}
                     props)]
          (stop!)))
      (Thread/sleep 100)
      (is (zero? @runs)
          "closed work-chan reads during stop must not be treated as ticks"))))

(deftest engine-drop-if-running-behavior
  (ts/with-quiet-logging
    (let [props (mock-worker-props)
          now   (Instant/now)
          ;; Send two ticks simultaneously. The first will block the dropping-buffer,
          ;; causing the second to be instantly dropped.
          stop! (engine/start-worker!
                 "unit-drop"
                 {:schedule     [now now]
                  :timeout-ms   1000
                  :body-fn      (fn [] (Thread/sleep 150))}
                 props)]
      (is (ts/eventually #(pos? @(:dropped-count props)) 500) "should drop the overlapping tick")
      ;; Wait for the running job to finish
      (Thread/sleep 200)
      (is (= 1 @(:num-runs props)) "Only one run should have completed")
      (is (false? @(:in-flight? props)))
      (stop!))))

(deftest engine-stale-tick-fast-forward
  (ts/with-quiet-logging
    (let [props (mock-worker-props)
          now   (Instant/now)

          ;; Create 50 ticks that are 10 minutes in the past
          stale-ticks (repeat 50 (.minusSeconds now 600))

          ;; Append one tick for right now
          schedule    (concat stale-ticks [now])

          stop! (engine/start-worker!
                 "unit-stale"
                 {:schedule     schedule
                  :timeout-ms   1000
                  :body-fn      (fn [] (Thread/sleep 10))}
                 props)]

      ;; Wait for the ONE valid run to finish
      (is (ts/eventually #(pos? @(:num-runs props)) 500))

      ;; Assert the exact machine-gun skip behavior
      (is (= 1 @(:num-runs props)) "Only the current tick should have executed")
      (is (= 50 @(:dropped-count props)) "All 50 stale ticks should be instantly dropped")

      (stop!))))

(deftest engine-stale-tick-grace-period
  (ts/with-quiet-logging
    (let [props (mock-worker-props)
          now   (Instant/now)

          ;; Create 1 tick that is exactly 1 second in the past.
          ;; This is safely inside the -2000ms grace period.
          grace-tick (.minusSeconds now 1)

          stop! (engine/start-worker!
                 "unit-grace"
                 {:schedule     [grace-tick]
                  :timeout-ms   1000
                  :body-fn      (fn [] (Thread/sleep 10))}
                 props)]

      (is (ts/eventually #(pos? @(:num-runs props)) 500))

      ;; Assert that the grace period caught it
      (is (= 1 @(:num-runs props)) "A tick within the 2-second grace period should execute")
      (is (= 0 @(:dropped-count props)) "It should NOT be dropped as a stale tick")

      (stop!))))

;;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Integration tests (chime/schedule -> lifecycle -> engine)

(deftest add-and-stop-worker-basic
  (ts/with-quiet-logging
    (let [w :hello
          _ (l/add-worker! {:worker-name w
                            :schedule    (sched/periodic-seq 100 {:jitter-frac 0.1})
                            :timeout-ms  250
                            :body-fn     (fn [] (t/log! {:level :debug :id :test/tick}))})]
      (is (contains? (reg/registry-snapshot) "hello"))
      (is (ts/eventually #(-> (reg/registry-snapshot) (get "hello") :num-runs deref pos?) 3000))
      (is (true? (l/stop-worker! w)))
      (is (nil? (l/stop-worker! w)) "stopping twice returns nil (not found)"))))

(deftest lifecycle-drop-if-running-integration
  (ts/with-quiet-logging
    (let [w :slow
          _ (l/add-worker! {:worker-name w
                            :schedule    (sched/periodic-seq 30 {:jitter-frac 0.1})
                            :timeout-ms  500
                            :body-fn     (fn [] (Thread/sleep 120))})]
      (is (ts/eventually #(-> (reg/registry-snapshot) (get "slow")) 200))
      (Thread/sleep 400) ;; let it run and drop a few rapid ticks
      (let [{:keys [num-runs dropped-count in-flight?]} (get (reg/registry-snapshot) "slow")]
        (is (>= @dropped-count 1) "should have dropped at least one tick while job was running")
        (is (pos? @num-runs))
        (is (boolean? @in-flight?)))
      (l/stop-worker! w))))

(deftest lifecycle-timeout-path-integration
  (ts/with-quiet-logging
    (let [w :timeout-int
          _ (l/add-worker! {:worker-name w
                            :schedule    (sched/periodic-seq 50 {:jitter-frac 0.1})
                            :timeout-ms  30
                            :body-fn     (fn [] (Thread/sleep 200))})]
      (is (ts/eventually #(-> (reg/registry-snapshot)
                              (get "timeout-int") :num-errors deref pos?) 3000))
      (let [{:keys [last-error]} (get (reg/registry-snapshot) "timeout-int")]
        (is (= :timeout (:type @last-error))))
      (l/stop-worker! w))))

(deftest list-workers-shape-and-next-eta
  (let [w1 :l1
        w2 :l2]
    (l/add-worker! {:worker-name w1
                    :schedule    (sched/periodic-seq 100)
                    :timeout-ms  200
                    :body-fn     (fn [])})
    (l/add-worker! {:worker-name w2
                    :schedule    (sched/periodic-seq 200)
                    :timeout-ms  200
                    :body-fn     (fn [])})
    (let [rows (a/list-workers)]
      (is (= #{"l1" "l2"} (set (map :worker-name rows))))
      (doseq [row rows]
        (is (contains? row :next-run-eta))
        (is (contains? row :next-run-in))
        (is (some? (:status row)))))
    (l/stop-all-workers!)))
