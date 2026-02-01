(ns agentti.schedules-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [agentti.schedules :as sched])
  (:import
   (java.time Instant ZonedDateTime ZoneId)))

(deftest build-times-no-jitter-shape
  (let [interval 200
        {:keys [times jitter-ms next-eta*]}
        (sched/build-times! {:interval-ms interval})]

    (is (= 0 jitter-ms))

    ;; realize *one* tick and check next-eta*
    (let [i1 (first times)]
      (is (= (inst-ms i1) @next-eta*)))

    ;; realize more ticks; next-eta* should track the *most recently realized* tick
    (let [insts  (doall (take 10 times))
          ms     (map inst-ms insts)
          deltas (map - (rest ms) ms)]
      (is (every? #(= interval %) deltas))
      (is (= (inst-ms (last insts)) @next-eta*)))))

(deftest build-times-jitter-bounds
  (let [interval 200
        j        50
        {:keys [times jitter-ms next-eta*]}
        (sched/build-times! {:interval-ms interval :jitter-ms j})]

    (is (= j jitter-ms))

    ;; realize *one* tick and check next-eta*
    (let [i1 (first times)]
      (is (= (inst-ms i1) @next-eta*)))

    ;; realize more ticks; check bounds and that next-eta* tracks last realized
    (let [insts  (doall (take 50 times))
          ms     (map inst-ms insts)
          deltas (map - (rest ms) ms)]
      (is (every? pos? deltas))
      (is (every? #(<= (- interval j) % (+ interval j)) deltas))
      (is (= (inst-ms (last insts)) @next-eta*)))))

(deftest build-times-custom-schedule
  (testing "Using a raw sequence of Instants"
    (let [now    (Instant/now)
          custom (iterate #(.plusMillis ^Instant % 1000) now) ;; 0s, 1s, 2s...
          {:keys [times jitter-ms next-eta*]}
          (sched/build-times! {:schedule custom})]

      (is (= 0 jitter-ms) "Jitter should be 0 for custom schedules")
      (is (some? next-eta*) "Should create a tracking atom")
      (is (nil? @next-eta*) "Atom starts nil before realization")

      ;; Realize the first item
      (let [t1 (first times)]
        (is (= now t1))
        (is (= (.toEpochMilli now) @next-eta*) "next-eta* should track first realized instant"))

      ;; Realize deeper
      (let [t3 (nth times 2)]
        (is (= (.toEpochMilli (.plusMillis now 2000)) @next-eta*)
            "next-eta* should update to the most recently realized time"))))

  (testing "Using a sequence of ZonedDateTimes (common with Chime)"
    (let [zone   (ZoneId/of "UTC")
          now    (ZonedDateTime/now zone)
          custom (iterate #(.plusHours ^ZonedDateTime % 1) now) ;; 0h, 1h, 2h...
          {:keys [times next-eta*]}
          (sched/build-times! {:schedule custom})]

      ;; Realize first item
      (let [t1 (first times)]
        (is (= now t1))
        (is (= (.toEpochMilli (.toInstant now)) @next-eta*)
            "Should correctly convert ZonedDateTime to epoch millis for the atom")))))
