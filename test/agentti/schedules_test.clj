(ns agentti.schedules-test
  (:require
   [clojure.test :refer [deftest is]]
   [agentti.schedules :as sched]))

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
