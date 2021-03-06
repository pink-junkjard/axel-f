(ns axel-f.excel.date
  (:refer-clojure :exclude [format])
  (:require #?(:clj [java-time :as jt]
               :cljs [goog.i18n.DateTimeFormat])
            [axel-f.excel.coerce :as coerce])
  #?(:clj (:import java.time.ZoneOffset
                   java.time.ZoneId)))

(defn format [date fmt]
  #?(:clj (jt/format fmt (coerce/inst date))
     :cljs (let [formatter (goog.i18n.DateTimeFormat. fmt)]
             (.format formatter (coerce/inst date)))))

(defmethod coerce/inst "LocalDate" [[_ millis]]
  #?(:clj (.. (java.time.Instant/ofEpochMilli millis) (atZone (java.time.ZoneId/systemDefault)) toLocalDate)
     :cljs (js/Date. millis)))

(defmethod coerce/inst "LocalDateTime" [[_ millis]]
  #?(:clj (.. (java.time.Instant/ofEpochMilli millis) (atZone (java.time.ZoneId/systemDefault)) toLocalDateTime)
     :cljs (js/Date. millis)))

(defmethod coerce/excel-number "LocalDate" [[_ millis]]
  (Math/round (double (/ millis 1000))))

(defmethod coerce/excel-number "LocalDateTime" [[_ millis]]
  (Math/round (double (/ millis 1000))))

(defmethod coerce/excel-string "LocalDate"
  ([d] (format d "YYYY-MM-dd"))
  ([d fmt] (format d fmt)))

(defmethod coerce/excel-string "LocalDateTime"
  ([d] (format d "YYYY-MM-dd'T'HH:mm:ss.SSS"))
  ([d fmt] (format d fmt)))

(defn NOW*
  "Returns the current date and time as a date value."
  []
  ["LocalDateTime"
   #?(:clj (.toEpochMilli (.toInstant (.atZone (jt/local-date-time) (ZoneId/ofOffset "UTC" (ZoneOffset/ofHours 0)))))
      :cljs (let [n (js/Date.)
                  ldt (js/Date. (js/Date.UTC (.getUTCFullYear n)
                                             (.getUTCMonth n)
                                             (.getUTCDate n)
                                             (.getUTCHours n)
                                             (.getUTCMinutes n)
                                             (.getUTCSeconds n)
                                             (.getUTCMilliseconds n)))]
              ;; (Math/round (/ (.getTime ldt) 1000))
              (.getTime ldt)))])

(def NOW #'NOW*)

(defn TODAY*
  "Returns the current date as a date value."
  []
  ["LocalDate"
   #?(:clj (.toEpochMilli (.toInstant (.atStartOfDay (jt/local-date) (ZoneId/ofOffset "UTC" (ZoneOffset/ofHours 0)))))
      :cljs (let [n (js/Date.)
                  ld (js/Date. (js/Date.UTC (.getUTCFullYear n)
                                            (.getUTCMonth n)
                                            (.getUTCDate n)))]
              (.getTime ld)))])

(def TODAY #'TODAY*)

(defn DATE*
  "Converts a year, month, and day into a date."
  [^{:doc "The year component of the date."} year
   ^{:doc "The month component of the date."} month
   ^{:doc "The day component of the date."} day]
  ["LocalDate"
   #?(:clj (.toEpochMilli (.toInstant (.atStartOfDay (jt/local-date (coerce/excel-number year)
                                                                    (coerce/excel-number month)
                                                                    (coerce/excel-number day))
                                                     (ZoneId/ofOffset "UTC" (ZoneOffset/ofHours 0)))))
      :cljs (let [d (js/Date. (js/Date.UTC (coerce/excel-number year)
                                           (dec (coerce/excel-number month))
                                           (coerce/excel-number day)))]
              (.getTime d)))])

(def DATE #'DATE*)

(defn DAY*
  "Returns the day of the month that a specific date falls on, in numeric format."
  [^{:doc "The date from which to extract the day. Must be a reference containing a date, or a function returning a date type.
"} date]
  #?(:clj (jt/as (coerce/inst date) :day-of-month)
     :cljs (.getUTCDate (coerce/inst date))))

(def DAY #'DAY*)

(defn MONTH*
  "Returns the month of the year a specific date falls in, in numeric format."
  [^{:doc "The date from which to extract the month. Must be a reference containing a date, or a function returning a date type"} date]
  #?(:clj (jt/as (coerce/inst date) :month-of-year)
     :cljs (inc (.getUTCMonth (coerce/inst date)))))

(def MONTH #'MONTH*)

(defn YEAR*
  "Returns the year specified by a given date."
  [^{:doc "The date from which to calculate the year. Must be a reference containing a date, or a function returning a date type."} date]
  #?(:clj (jt/as (coerce/inst date) :year)
     :cljs (.getUTCFullYear (coerce/inst date))))

(def YEAR #'YEAR*)

(def env
  {"YEAR" YEAR
   "MONTH" MONTH
   "NOW" NOW
   ;; "NETWORKDAYS" NETWORKDAYS
   ;; "DAYS" DAYS
   "DATE" DATE
   ;; "DAYS360" DAYS360
   ;; "YEARFRAC" YEARFRAC
   ;; "WEEKDAY" WEEKDAY
   ;; "TIME" TIME
   ;; "WORKDAY" WORKDAY
   ;; "EDATE" EDATE
   ;; "WEEKNUM" WEEKNUM
   ;; "EOMONTH" EOMONTH
   ;; "ISOWEEKNUM" ISOWEEKNUM
   "DAY" DAY
   ;; "MINUTE" MINUTE
   ;; "WORKDAY.INTL" WORKDAY.INTL
   ;; "NETWORKDAYS.INTL" NETWORKDAYS.INTL
   ;; "DATEDIF" DATEDIF
   ;; "HOUR" HOUR
   ;; "TIMEVALUE" TIMEVALUE
   ;; "DATEVALUE" DATEVALUE
   ;; "SECOND" SECOND
   "TODAY" TODAY
   })
