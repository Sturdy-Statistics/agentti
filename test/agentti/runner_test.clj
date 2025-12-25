(ns agentti.runner-test
  (:require
   [clojure.test :refer [deftest is]]
   [agentti.runner :as runner]
   [agentti.test-support :as ts])
  (:import
   (java.time Instant)
   (java.util.concurrent Executors ExecutorService)))

(defn- mk-metrics []
  {:num-runs       (atom 0)
   :num-errors     (atom 0)
   :last-error     (atom nil)
   :in-flight?     (atom false)
   :dropped-count  (atom 0)
   :last-run       (atom nil)
   :last-duration  (atom nil)
   :total-runtime  (atom 0)
   :avg-duration   (atom nil)})

(deftest task-runner-success-updates-metrics
  (let [calls   (atom 0)
        body-fn (fn [] (swap! calls inc))
        ^ExecutorService ex  (Executors/newSingleThreadExecutor)
        ^ExecutorService wch (Executors/newSingleThreadExecutor)
        m       (mk-metrics)
        f       (runner/make-task-runner "w" body-fn ex wch 1000 m)]
    (try
      (f (Instant/now))
      (is (ts/eventually #(= 1 @calls) 200))
      (is (ts/eventually #(= 1 @(:num-runs m)) 500))
      (is (= 0     @(:num-errors m)))
      (is (nil?    @(:last-error m)))
      (is (some?   @(:last-duration m)))
      (is (<= 0    @(:last-duration m)))
      (is (<= 0    @(:total-runtime m)))
      (is (number? @(:avg-duration m)))
      (is (false?  @(:in-flight? m)))
      (finally
        (.shutdownNow ex)
        (.shutdownNow wch)))))

(deftest task-runner-exception-records-error-and-clears-inflight
  (let [body-fn (fn [] (throw (ex-info "boom" {})))
        ^ExecutorService ex  (Executors/newSingleThreadExecutor)
        ^ExecutorService wch (Executors/newSingleThreadExecutor)
        m       (mk-metrics)
        f       (runner/make-task-runner "w" body-fn ex wch 1000 m)]
    (try
      (f (Instant/now))
      (is (ts/eventually #(= 1 @(:num-errors m)) 500))
      (is (= 0 @(:num-runs m)))
      (is (= :exception (:type @(:last-error m))))
      (is (false? @(:in-flight? m)))
      (finally
        (.shutdownNow ex)
        (.shutdownNow wch)))))

(deftest task-runner-skip-when-inflight-increments-dropped
  (let [calls   (atom 0)
        body-fn (fn [] (swap! calls inc))
        ^ExecutorService ex  (Executors/newSingleThreadExecutor)
        ^ExecutorService wch (Executors/newSingleThreadExecutor)
        m       (mk-metrics)
        f       (runner/make-task-runner "w" body-fn ex wch 1000 m)]
    (try
      (reset! (:in-flight? m) true) ;; simulate already running
      (f (Instant/now))
      (is (= 0 @calls))
      (is (= 1 @(:dropped-count m)))
      (is (= 0 @(:num-runs m)))
      (finally
        (.shutdownNow ex)
        (.shutdownNow wch)))))
