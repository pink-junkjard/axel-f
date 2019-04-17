(ns axel-f.comments-test
  (:require [axel-f.excel :as af]
            #?(:clj [clojure.test :as t]
               :cljs [cljs.test :as t :include-macros true]))
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(t/deftest comments
  (t/is (= 1
           ((af/eval ";; Line comment
                      1"))))

  (t/is (= 1
           ((af/eval "1 ;; Line comment"))))

  (t/is (= 1
           ((af/eval "1
                      ;; Line comment"))))

  (t/is (= 1
           ((af/eval ";~ Block comment ~; 1"))))

  (t/is (thrown-with-msg?
         ExceptionInfo
         #"Unclosed comment block"
         ((af/eval "1 ;~ Unclosed comment block")))))