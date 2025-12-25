(ns agentti.util
  (:require
   [clojure.string :as string]))

(defn normalize-name
  "Coerce worker identifier to canonical string key."
  [x]
  (cond
    (string? x) x
    (keyword? x) (name x)
    :else (str x)))

(defn format-duration
  "Format seconds as \"Dd HHh MMm SSs\" (compacts leading zeros)."
  [total-secs]
  (let [secs        (mod total-secs 60)
        total-mins  (quot total-secs 60)
        mins        (mod total-mins 60)
        total-hours (quot total-mins 60)
        hours       (mod total-hours 24)
        days        (quot total-hours 24)]
    (string/trim
     (str (when (pos? days)  (str days  "d "))
          (when (or (pos? days) (pos? hours)) (str hours "h "))
          (when (or (pos? days) (pos? hours) (pos? mins)) (str mins  "m "))
          secs "s"))))

(defn human-interval
  "Human-friendly milliseconds (e.g., 1250 → \"1.3 sec\")."
  [ms]
  (cond
    (< ms 1000)    (str ms " ms")
    (< ms 60000)   (format "%.1f sec" (/ ms 1000.0))
    (< ms 3600000) (format "%.1f min" (/ ms 60000.0))
    :else          (format "%.1f hr"  (/ ms 3600000.0))))
