(ns scicloj.note-to-test.v1.impl
  (:require [clojure.string :as string]
            [clojure.pprint :as pp]
            [clojure.tools.reader]
            [clojure.tools.reader.reader-types]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(def *special-value-representations
  (atom {}))

(defn define-value-representation!
  [name {:keys [predicate representation]}]
  (swap! *special-value-representations
         assoc name
         {:predicate predicate
          :representation representation})
  :ok)

(defn represent-value [v]
  (-> (->> @*special-value-representations
           (map (fn [[_ {:keys [predicate representation]}]]
                  (if (predicate v)
                    (representation v))))
           (filter identity)
           first)
      (or v)))

(defn indent [code n-spaces]
  (let [whitespaces (apply str (repeat n-spaces \space))]
    (-> code
        (string/split #"\n")
        (->> (map (fn [line]
                    (str whitespaces line)))
             (string/join "\n")))))

(def test-template
  "
(deftest %s
  (is (=
%s
    ;; =>
%s)))
")

(defn ->test [code index source-ns]
  (let [output (try (binding [*ns* source-ns]
                      (load-string code))
                    (catch Exception e
                      (throw (ex-info "note-to-test: Exception on load-string"
                                      {:source-ns source-ns
                                       :code code
                                       :exception e}))))]
    (if (var? output)
      ;; if the output is a var,
      ;; just keep the code (so that we run things in order)
      code
      ;; else - actually create a test
      (format test-template
              (str "test-" index)
              (indent code 4)
              (-> output
                  represent-value
                  pp/pprint
                  with-out-str
                  (indent 4))))))



(defn ->test-ns-symbol [ns-symbol]
  (format "%s-generated-test"
          (name ns-symbol)))

(defn ->test-path [test-ns-symbol]
  (-> test-ns-symbol
      name
      (string/replace #"-" "_")
      (string/replace #"\." "/")
      (->> (format "test/%s.clj"))))

(defn ->test-ns-requires [ns-symbol ns-requires]
  (-> (concat (list
               :require
               '[clojure.test :refer [deftest is]])
              ns-requires)
      pp/pprint
      with-out-str))

(defn ->test-ns [test-ns-symbol test-ns-requires]
  (format "(ns %s\n%s)"
          test-ns-symbol
          (-> test-ns-requires
              (indent 2))))

(defn code->forms [code]
  (->> code
       clojure.tools.reader.reader-types/source-logging-push-back-reader
       repeat
       (map #(clojure.tools.reader/read % false ::EOF))
       (take-while (partial not= ::EOF))))

(defn read-forms [source-path]
  (->> source-path
       slurp
       code->forms))

(defn begins-with? [value-or-set-of-values]
  (if (set? value-or-set-of-values)
    (fn [form]
      (and (list? form)
           (-> form first value-or-set-of-values)))
    ;; else
    (fn [form]
      (and (list? form)
           (-> form first (= value-or-set-of-values))))))



(defn test-form->original-form [test-form]
  (-> test-form
      meta
      :source
      (string/split #"\n")
      (->> (drop 2)
           (string/join "\n"))
      code->forms
      first))


(defn read-tests [test-path]
  (when (-> test-path
            io/file
            (.exists))
    (->> test-path
         read-forms
         (filter (begins-with? 'deftest))
         (map (fn [test-form]
                {:test-form test-form
                 :original-form (test-form->original-form
                                 test-form)})))))

#_(defn git-hash []
    (-> (shell/sh "git" "rev-parse" "HEAD")
        :out
        (string/replace #"\n" "")))

(defn prepare-context [source-path]
  (try
    (load-file source-path)
    (catch Exception e
      (throw (ex-info "note-to-test: Exception on lode-file"
                      {:source-path source-path
                       :exception e}))))
  (let [forms (read-forms source-path)
        ns-form (->> forms
                     (filter (begins-with? 'ns))
                     first)
        ns-symbol (second ns-form)
        ns-requires (some->> ns-form
                             (filter (begins-with? :require))
                             first
                             rest)
        test-ns-symbol (->test-ns-symbol ns-symbol)
        test-ns-requires (->test-ns-requires ns-symbol ns-requires)
        test-path (->test-path test-ns-symbol)
        codes-for-tests (->> forms
                             (remove (begins-with? '#{ns comment}))
                             (map (fn [form]
                                    (-> form
                                        meta
                                        :source)))
                             (remove nil?))]
    {:ns-symbol ns-symbol
     :ns-requires ns-requires
     :test-ns-symbol test-ns-symbol
     :test-ns-requires test-ns-requires
     :test-path test-path
     :codes-for-tests codes-for-tests}))


(defn write-tests! [context]
  (let [{:keys [ns-symbol
                ns-requires
                test-ns-symbol
                test-ns-requires
                test-path
                codes-for-tests]} context]
    (io/make-parents test-path)
    (->> codes-for-tests
         (map-indexed (fn [i code]
                        (->test code
                                i
                                (find-ns ns-symbol))))
         (cons (->test-ns test-ns-symbol
                          test-ns-requires))
         (string/join "\n")
         (#(spit test-path
                 %
                 :append true)))
    [:wrote test-path]))
