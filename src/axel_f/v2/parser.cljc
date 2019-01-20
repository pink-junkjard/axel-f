(ns axel-f.v2.parser
  (:require [axel-f.v2.reader :as reader]
            [axel-f.v2.lexer :as lexer]
            [axel-f.functions.core :refer [*functions-store*]]
            axel-f.functions
            [clojure.string :as string]
            #?(:cljs [cljs.reader :as edn]
               :clj [clojure.edn :as edn])
            [clojure.walk :as walk]
            :reload-all))

(def constant?
  (some-fn lexer/number-literal? lexer/text-literal?))

(defn expression? [{:keys [kind]}]
  (= kind ::fncall))

(defn ->function-node [{:keys [value begin] :as f} args]
  {:kind ::fncall
   :f value
   :args args
   :begin begin
   :end (-> args last :end)})

(defn into-reader [ts]
  (-> ts reader/reader reader/push-back-reader))

(declare parse-primary parse-expression)

(defn parse-prefix-function-call [ts]
  (let [f (reader/read-elem ts)]
    (->function-node f [(parse-primary ts true)])))

(defn parse-keyword [ts]
  (loop [acc [(reader/read-elem ts)] next (reader/peek-elem ts)]
    (cond
      (and (lexer/operator-literal? (last acc) ["/"])
           ((some-fn lexer/symbol-literal? lexer/text-literal?) next))
      {:kind ::fncall
       :f ::keyword
       :arg (keyword (string/join "." (->> acc rest butlast
                                           (remove #(lexer/punctuation-literal? % ["."]))
                                           (map :value)))
                     (:value (reader/read-elem ts)))
       :begin (-> acc first :begin)
       :end (:end next)}

      (or (and ((some-fn lexer/symbol-literal? lexer/text-literal?) (last acc))
               (or (lexer/punctuation-literal? next ["."])
                   (lexer/operator-literal? next ["/"])))
          (and ((some-fn lexer/symbol-literal? lexer/text-literal?) next)
               (or (lexer/punctuation-literal? (last acc) ["."])
                   (lexer/operator-literal? (last acc) ["/"])))
          (and (lexer/operator-literal? (last acc) [":"])
               (= 1 (count acc))
               ((some-fn lexer/symbol-literal? lexer/text-literal?) next)))
      (recur (conj acc (reader/read-elem ts))
             (reader/peek-elem ts)))))

(defn parse-constant [ts]
  (let [{:keys [value] :as c} (reader/read-elem ts)]
    (assoc c :kind ::constant)))

(defn parse-symbol-expression [ts]
  (let [current (reader/read-elem ts)
        next (reader/peek-elem ts)
        in-reference? (or (lexer/punctuation-literal? next ["."])
                          (lexer/bracket-literal? next ["["]))]
    (cond
      (and (re-matches #"NULL|TRUE|True|true|FALSE|False|false" (:value current))
           (not in-reference?))
      {:kind ::fncall
       :f ::const
       :arg (case (string/lower-case (:value current))
              "true" true
              "false" false
              "null" nil)
       :begin (:begin current)
       :end (:end current)}

      :otherwise
      {:kind ::fncall
       :f ::reference
       :args [{:kind ::fncall
               :f ::symbol
               :arg (:value current)
               :begin (:begin current)
               :end (:end current)}]
       :begin (:begin current)
       :end (:end current)})))

(defn parse-many [ts pred]
  (loop [acc [] tokens (reader/read-until ts pred)]
    (if (not-empty tokens)
      (recur (conj acc (parse-primary (into-reader tokens)))
             (do (reader/read-elem ts)
                 (reader/read-until ts pred)))
      acc)))

(defn parse-function-call [ts f]
  (let [{:keys [begin depth] :as ob} (reader/read-elem ts)
        tokens (reader/read-until ts #(and (lexer/bracket-literal? % [")"])
                                           (= depth (:depth %))))
        {:keys [end] :as cb} (reader/read-elem ts)
        ts' (into-reader tokens)
        div-pred #(or (and (lexer/punctuation-literal? % [","])
                           (= (inc depth) (:depth %)))
                      (nil? %))]
    {:kind ::fncall
     :f (assoc f :f ::fnname)
     :args (parse-many ts' div-pred)}))

(defn begin-array? [t]
  (lexer/bracket-literal? t ["["]))

(defn parse-array [ts]
  (let [{:keys [begin depth] :as ob} (reader/read-elem ts)
        tokens (reader/read-until ts #(and (lexer/bracket-literal? % ["]"])
                                           (= depth (:depth %))))
        {:keys [end] :as cb} (reader/read-elem ts)
        ts' (into-reader tokens)
        div-pred #(or (and (lexer/punctuation-literal? % [","])
                           (= (inc depth) (:depth %)))
                      (nil? %))]
    {:kind ::fncall
     :f ::vector
     :args (parse-many ts' div-pred)}))

(defn begin-group? [t]
  (lexer/bracket-literal? t ["("]))

(defn parse-group [ts]
  (let [{:keys [begin depth] :as ob} (reader/read-elem ts)
        tokens (reader/read-until ts #(and (lexer/bracket-literal? % [")"])
                                           (= depth (:depth %))))
        {:keys [end] :as cb} (reader/read-elem ts)
        ts' (into-reader tokens)]
    (parse-primary ts')))

(defn parse-postfix [operator arg]
  {:kind ::fncall
   :f (:value operator)
   :args [arg]
   :begin (:begin arg)
   :end (:end operator)})

(defn fncall? [{:keys [kind]}]
  (= ::fncall kind))

(defn const->fncall [{:keys [value begin end]}]
  {:kind ::fncall
   :f ::const
   :arg value
   :begin begin
   :end end})

;; Operator precedence

(defn- -op? [v]
  (fn [op]
    (lexer/operator-literal? op [v])))

(def ^:private concat-op? (-op? "&"))

(def ^:private eq-op? (-op? "="))

(def ^:private not-eq-op? (-op? "<>"))

(def ^:private less-or-eq-op? (-op? "<="))

(def ^:private more-or-eq-op? (-op? ">="))

(def ^:private less-op? (-op? "<"))

(def ^:private more-op? (-op? ">"))

(def ^:private sub-op? (-op? "-"))

(def ^:private add-op? (-op? "+"))

(def ^:private div-op? (-op? "/"))

(def ^:private mult-op? (-op? "*"))

(def ^:private exp-op? (-op? "^"))

(def ^:private next-op-pred
  {concat-op? eq-op?
   eq-op? not-eq-op?
   not-eq-op? less-or-eq-op?
   less-or-eq-op? more-or-eq-op?
   more-or-eq-op? less-op?
   less-op? more-op?
   more-op? sub-op?
   sub-op? add-op?
   add-op? div-op?
   div-op? mult-op?
   mult-op? exp-op?})

(defn- infix->fncall
  ([infix-args] (infix->fncall infix-args concat-op?))
  ([infix-args op-pred]
   (if (and op-pred (not= 1 (count infix-args)))
     (let [infix-args' (partition-by op-pred infix-args)]
       (if (= 1 (count infix-args'))
         (if-let [op-pred' (next-op-pred op-pred)]
           (infix->fncall infix-args op-pred')
           (first infix-args'))
         (let [f (-> infix-args' second first)]
           {:kind ::fncall
            :f (:value f)
            :args (map #(infix->fncall (first %) (next-op-pred op-pred))
                       (partition 1 2 infix-args'))})))
     (first infix-args))))

(defn parse-reference [ts reference]
  (let [div (reader/read-elem ts)]
    (cond
      (= ::keyword (:f reference))
      {:kind ::fncall
       :f ::reference
       :args [reference]
       :begin (:begin reference)
       :end (:end reference)}

      (lexer/punctuation-literal? div ["."])
      (if (lexer/bracket-literal? (reader/peek-elem ts) ["["])
        reference
        (let [n (parse-expression ts)]
          (update reference :args conj n)))

      (lexer/bracket-literal? div ["["])
      (update reference :args conj
              (let [{:keys [begin depth]} div
                    tokens (reader/read-until ts #(and (lexer/bracket-literal? % ["]"])
                                                       (= depth (:depth %))))
                    {:keys [end]} (reader/read-elem ts)]
                (cond
                  (and (= 1 (count tokens))
                       (lexer/operator-literal? (first tokens) ["*"]))
                  {:kind ::fncall
                   :f ::nth
                   :arg {:kind ::fncall
                         :f ::ALL
                         :arg nil}
                   :begin begin
                   :end end}

                  :otherwise
                  (let [expr (parse-primary (into-reader tokens))]
                    {:kind ::fncall
                     :f ::nth
                     :arg expr
                     :begin begin
                     :end end})))))))

(defn parse-expression [ts]
  (let [{:keys [value] :as next-token} (reader/peek-elem ts)]
    (cond
      (constant? next-token)
      (let [c (parse-constant ts)
            n (reader/peek-elem ts)]
        (if (lexer/operator-literal? n ["%"])
          c
          (const->fncall c)))

      (lexer/operator-literal? next-token ["+" "-" "!"])
      (parse-prefix-function-call ts)

      (lexer/operator-literal? next-token [":"])
      (parse-keyword ts)

      (begin-group? next-token)
      (parse-group ts)

      (begin-array? next-token)
      (parse-array ts)

      (lexer/symbol-literal? next-token)
      (parse-symbol-expression ts)

      (expression? next-token)
      (let [next-token (reader/read-elem ts)
            next-token' (reader/peek-elem ts)]
        (cond
          (or (lexer/punctuation-literal? next-token' ["."])
              (lexer/bracket-literal? next-token' ["["]))
          (parse-reference ts next-token)

          (lexer/bracket-literal? next-token' ["("])
          (parse-function-call ts next-token)

          (lexer/operator-literal? next-token' ["%"])
          (parse-postfix (reader/read-elem ts) next-token)

          (lexer/operator-literal? next-token' ["+" "-" "*" "/" "&" "<" ">" "<=" ">=" "=" "<>" "^"])
          (loop [acc [next-token (reader/read-elem ts)]]
            (let [expr (parse-primary ts true)
                  next-op (reader/peek-elem ts)]
              (if (not-empty expr)
                (if (lexer/operator-literal? next-op ["+" "-" "*" "/" "&" "<" ">" "<=" ">=" "=" "<>" "^"])
                  (recur (conj acc expr (reader/read-elem ts)))
                  (infix->fncall (conj acc expr)))
                (infix->fncall acc))))

          :otherwise
          (throw (ex-info (str "Expression must be followed by postfix or infix operator, got `" (:value next-token') "` instead.")
                          {:position ((juxt :begin :end) next-token')
                           :token next-token'}))))

      :otherwise
      (let [error-data {:position ((juxt :begin :end) next-token)
                        :token next-token}]
        (throw
         ;; todo crossplatform hepler for cljs/clj
         (ex-info "Unexpected token"
                  #?(:clj error-data
                     :cljs (clj->js error-data))))))))

(defn const? [{:keys [kind]}]
  (= ::constant kind))

(defn parse-primary
  ([tokens-rdr] (parse-primary tokens-rdr false))
  ([tokens-rdr stop-on-complete-expr?]
   (let [expr (parse-expression tokens-rdr)]
     (cond
       ;; Some tokens left in the reader and parser is not forced to stop after first parsed fncall
       (and (reader/peek-elem tokens-rdr)
            (not (and stop-on-complete-expr? (fncall? expr))))
       (do (reader/unread-elem tokens-rdr expr)
           (parse-primary tokens-rdr stop-on-complete-expr?))

       ;; Continue parsing when no tokens to parse but expression not in form of fncall
       (and (empty? (reader/peek-elem tokens-rdr))
            (or (= ::symbol (:kind expr))
                (lexer/symbol-literal? expr)))
       (do (reader/unread-elem tokens-rdr expr)
           (parse-primary tokens-rdr stop-on-complete-expr?))

       :otherwise
       expr))))

;; (def default-functions
;;   {"*" *
;;    "/" /
;;    "&" str
;;    "=" =
;;    "<" <
;;    ">" >
;;    "<=" <=
;;    ">=" >=
;;    "<>" not=
;;    "!" not
;;    "^" #(Math/pow %1 %2)
;;    ;; TODO implement *real* get-in
;;    "get-in" #(get-in %1 %2)})

;; (defn resolve-function [f]
;;   (get (merge @axel-f.functions.core/*functions-store*
;;               default-functions)
;;        f))

;; (defn- select-ctx [xs]
;;   (if-let [[_ position] (when (string? (first xs)) (re-matches #"_([1-9][0-9]*)" (first xs)))]
;;     [(list 'nth 'args (dec (Integer/parseInt position)) nil) (rest xs)]
;;     (if (= "_" (first xs))
;;       [(list 'nth 'args 0) (rest xs)]
;;       ['ctx xs])))

;; (defmulti emit-node* (fn [fnname _] fnname))

;; (defmethod emit-node* "const" [_ [c]] c)

;; (defmethod emit-node* "vec" [_ args] args)

;; (defmethod emit-node* "MAP" [_ [f & cs]]
;;   (let [f' (list 'fn '[& args] f)]
;;     (concat (list 'map f')
;;             cs)))

;; (defmethod emit-node* "FILTER" [_ [f items]]
;;   (let [f' (list 'fn '[& args] f)]
;;     (concat (list 'filter f')
;;             (list items))))

;; (defmethod emit-node* "get-in" [_ args]
;;   (let [[ctx path] (select-ctx args)]
;;     (concat (list (resolve-function "get-in"))
;;             (list ctx)
;;             (list path))))

;; (defmethod emit-node* :default [fnname args]
;;   (let [f (resolve-function fnname)]
;;     (cons f args)))

;; (defn emit-node [{{fnname :value} :f
;;                   args :args}]
;;   (emit-node* fnname args))

(defn ast [formula]
  (-> formula
      lexer/read-formula
      reader/reader
      reader/push-back-reader
      parse-primary
      ))

;; (parse "1 + 1 * :fo.bo/ba.foo.bar")
;; => (fn [ctx]
;;      (axel-f.functions/+
;;         1
;;         (axel-f.functions/*
;;            1
;;            (axel-f.functions/get-in*
;;               ctx
;;               [:fo.bo/ba "foo" "bar"]))))
(defn parse [formula]
  (let [ast (ast formula)]
    (list 'fn '[ctx]
          (walk/postwalk
           (fn [node]
             (if (and (map? node) (fncall? node))
               (emit-node node)
               node))
           ast))))

(comment

  (lexer/read-formula "FOO(1,2,3)")

  (walk/postwalk
   (fn [x] (if (and (map? x)
                    (fncall? x))
             (do (prn x)
                 x)
             x))
   (parse "2 + 2 * JSON.DECODE('{\\'x\\':1}')")
   )

  (ast ":axel\\-f.v2.parser\\-next/foo")

  ((eval (parse "MAP(:axel\\-f.v2.parser\\-next/foo & !_, [TRUE, FALSE, TRUE])")
         ) {::foo false})

  (str true true)

  ((eval (parse "OR(1,2,3,4)")) {})
  #?(:cljs
     (.log js/console (str " AST "
                           (ast "OR(1,2,3,4)")
                           (ast "MAP(!_, [TRUE, FALSE, TRUE])")))

     ;; => AST  {:kind :axel-f.v2.parser/fncall, :f {:kind :axel-f.v2.parser/fnname, :value "OR", :begin {:line 1, :column 1}, :end {:line 1, :column 2}}, :args [{:kind :axel-f.v2.parser/fncall, :f {:kind :axel-f.v2.parser/fnname, :value "const"}, :args [1], :begin {:line 1, :column 4}, :end {:line 1, :column 4}} {:kind :axel-f.v2.parser/fncall, :f {:kind :axel-f.v2.parser/fnname, :value "const"}, :args [2], :begin {:line 1, :column 6}, :end {:line 1, :column 6}} {:kind :axel-f.v2.parser/fncall, :f {:kind :axel-f.v2.parser/fnname, :value "const"}, :args [3], :begin {:line 1, :column 8}, :end {:line 1, :column 8}} {:kind :axel-f.v2.parser/fncall, :f {:kind :axel-f.v2.parser/fnname, :value "const"}, :args [4], :begin {:line 1, :column 10}, :end {:line 1, :column 10}}]}
     )

  )