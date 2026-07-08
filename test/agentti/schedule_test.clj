(ns agentti.schedule-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [agentti.schedule :as sched])
  (:import
   (java.time Instant)))

(set! *warn-on-reflection* true)

;; Helper to convert Instants to longs for easy math
(defn- ->ms ^long [^Instant inst]
  (.toEpochMilli inst))

(deftest validation-tests
  (testing "Interval must be strictly positive"
    (is (thrown? Exception (sched/periodic-seq 0)))
    (is (thrown? Exception (sched/periodic-seq -50))))

  (testing "Cannot provide both jitter parameters"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sched/periodic-seq 1000 {:jitter-ms 100 :jitter-frac 0.1})))))

(deftest no-jitter-exact-intervals
  (testing "Returns standard chime sequence with exact spacing"
    (let [interval 100
          sq       (sched/periodic-seq interval)
          t0       (->ms (first sq))
          t1       (->ms (second sq))]
      ;; chime/periodic-seq starts exactly at `now`, so next is `now + interval`
      (is (= interval (- t1 t0))))))

(deftest bounded-random-walk-behavior
  (let [interval 1000
        jitter   200
        now-ms   (.toEpochMilli (Instant/now))

        ;; Grab 100 ticks to ensure the bounds hold over time
        sq       (sched/periodic-seq interval {:jitter-ms jitter})
        times    (map ->ms (take 100 sq))
        t0       (first times)

        ;; Calculate the time difference between every consecutive tick
        deltas   (map (fn [[a b]] (- b a)) (partition 2 1 times))]

    (testing "First tick is within [now, now + j]"
      ;; We add a small 50ms buffer to the lower/upper bounds just to account
      ;; for the microsecond delay between evaluating `now-ms` and the function running.
      (is (>= (- t0 now-ms) -50) "First tick shouldn't be heavily in the past")
      (is (<= (- t0 now-ms) (+ jitter 50)) "First tick shouldn't exceed max jitter"))

    (testing "Subsequent ticks are strictly within [interval-j, interval+j]"
      (let [min-delta (- interval jitter)
            max-delta (+ interval jitter)]
        (is (every? #(<= min-delta % max-delta) deltas)
            "A tick fell outside the allowed bounded random walk!")))))

(deftest fractional-jitter-translation
  (testing "Fractional jitter is correctly floored into milliseconds"
    (let [interval 1000
          frac     0.15 ;; 15% of 1000 = 150ms
          sq       (sched/periodic-seq interval {:jitter-frac frac})
          times    (map ->ms (take 100 sq))
          deltas   (map (fn [[a b]] (- b a)) (partition 2 1 times))
          min-delta (- interval 150)
          max-delta (+ interval 150)]
      (is (every? #(<= min-delta % max-delta) deltas)))))

(deftest clamping-prevents-time-travel
  (testing "Jitter larger than the interval is clamped to prevent negative deltas"
    (let [interval 100
          ;; Request a massive jitter that would normally cause negative time steps
          jitter   5000
          sq       (sched/periodic-seq interval {:jitter-ms jitter})
          times    (map ->ms (take 100 sq))
          deltas   (map (fn [[a b]] (- b a)) (partition 2 1 times))]

      ;; If clamping failed, (- interval jitter) would equal -4900, and a step
      ;; could evaluate to the past.
      (is (every? pos? deltas) "Deltas must remain strictly positive; time only moves forward."))))
