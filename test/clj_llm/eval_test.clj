(ns clj-llm.eval-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-llm.eval :as eval]
            [clj-llm.provider :as provider]))

;; A fake adapter that answers from a lookup in the provider config:
;; {"<model>|<last user content>" "answer"} — unknown keys throw.
(defmethod provider/-generate! ::lookup
  [{:keys [answers]} {:lib/keys [model messages]} _opts]
  (let [prompt (:content (last (filter #(= :user (:role %)) messages)))
        key (str model "|" prompt)]
    (if-let [answer (get answers key)]
      {:message {:role :assistant :content answer}
       :model model
       :usage {:input-tokens 10 :output-tokens 5}
       :finish-reason :stop
       :raw {}}
      (throw (ex-info (str "no canned answer for " key) {:key key})))))

(defn lookup-config [answers]
  #:lib{:providers {:fake {:lib/adapter ::lookup :answers answers}}
        :models {:a #:lib{:provider :fake :model "model-a"}
                 :b #:lib{:provider :fake :model "model-b"}}
        :defaults #:lib{:model :a}})

(def suite
  #:lib{:cases [#:lib{:id :capital
                      :input "Capital of France?"
                      :expected "Paris"}
                #:lib{:id :sum
                      :input "2+2?"
                      :expected "4"}]
        :variants [#:lib{:id :good :model :a}
                   #:lib{:id :bad :model :b}]
        :scorers [:includes]})

(def answers
  {"model-a|Capital of France?" "The capital of France is Paris."
   "model-a|2+2?" "4"
   "model-b|Capital of France?" "London, obviously."
   "model-b|2+2?" "5"})

(deftest run-suite
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})
        {:lib/keys [results summary]} report]
    (testing "one result per case x variant"
      (is (= 4 (count results)))
      (is (= #{[:capital :good] [:sum :good] [:capital :bad] [:sum :bad]}
             (set (map (juxt :lib/case-id :lib/variant-id) results)))))
    (testing "scores per result"
      (let [by-key (into {} (map (juxt (juxt :lib/case-id :lib/variant-id) identity))
                         results)]
        (is (= 1.0 (get-in by-key [[:capital :good] :lib/scores :includes :score])))
        (is (= 0.0 (get-in by-key [[:capital :bad] :lib/scores :includes :score])))))
    (testing "summary aggregates per variant"
      (is (= 1.0 (get-in summary [:by-variant :good :scores :includes :mean])))
      (is (= 0.0 (get-in summary [:by-variant :bad :scores :includes :mean])))
      (is (= 2 (get-in summary [:by-variant :good :cases])))
      (is (= 0 (get-in summary [:by-variant :good :errors])))
      (is (= 20 (get-in summary [:by-variant :good :usage :input-tokens])))
      (is (number? (get-in summary [:by-variant :good :latency-ms :mean]))))
    (testing "results carry the full interaction record"
      (is (every? #(get-in % [:lib/response :lib/request :lib/model]) results)))))

(deftest run-suite-concurrently
  (let [report (eval/run (lookup-config answers) suite {:concurrency 4})]
    (is (= 4 (count (:lib/results report))))
    (is (= 1.0 (get-in report [:lib/summary :by-variant :good :scores :includes :mean])))))

(deftest errors-are-contained
  (let [;; model-b answers are missing -> adapter throws for :bad variant
        config (lookup-config (select-keys answers
                                           ["model-a|Capital of France?"
                                            "model-a|2+2?"]))
        report (eval/run config suite {:concurrency 1})]
    (is (= 2 (get-in report [:lib/summary :by-variant :bad :errors])))
    (is (= 0 (get-in report [:lib/summary :by-variant :good :errors])))
    (is (every? :lib/error (filter #(= :bad (:lib/variant-id %)) (:lib/results report))))))

(deftest suite-from-edn-file
  (let [dir (java.nio.file.Files/createTempDirectory
             "clj-llm-eval" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir "/suite.edn")]
    (spit path (pr-str suite))
    (let [report (eval/run (lookup-config answers) path {:concurrency 1})]
      (is (= 4 (count (:lib/results report)))))))

(deftest variant-request-keys-flow-through
  (let [seen (atom [])]
    (defmethod provider/-generate! ::spy [_ {:lib/keys [model] :as request} _opts]
      (swap! seen conj request)
      {:message {:role :assistant :content "ok"} :model model
       :usage {} :finish-reason :stop :raw {}})
    (eval/run #:lib{:providers {:s {:lib/adapter ::spy}}
                    :models {:m #:lib{:provider :s :model "m-1"}}
                    :defaults #:lib{:model :m}}
              #:lib{:cases [#:lib{:id :c :input "q"}]
                    :variants [#:lib{:id :v :model :m :system "terse" :temperature 0.1}]
                    :scorers []}
              {:concurrency 1})
    (is (= "terse" (:lib/system (first @seen))))
    (is (= 0.1 (:lib/temperature (first @seen))))))

(deftest built-in-scorers
  (let [response #:lib{:text "The answer is Paris. "}]
    (is (= 1.0 (:score (eval/exact-match {:case #:lib{:expected "The answer is Paris."}
                                          :response response}))))
    (is (= 0.0 (:score (eval/exact-match {:case #:lib{:expected "Paris"}
                                          :response response}))))
    (is (= 1.0 (:score (eval/includes {:case #:lib{:expected "paris"}
                                       :response response}))))
    (is (= 0.0 (:score (eval/includes {:case #:lib{:expected "Lyon"}
                                       :response response}))))
    (is (= 1.0 (:score (eval/matches {:case #:lib{:expected "(?i)paris\\."}
                                      :response response}))))
    (is (= 0.0 (:score (eval/matches {:case #:lib{:expected "^\\d+$"}
                                      :response response}))))))

(deftest judge-reply-parsing
  (testing "well-formed JSON"
    (is (= {:score 0.8 :reasoning "solid"}
           (eval/parse-judge-reply
            "{\"score\": 0.8, \"reasoning\": \"solid\"}"))))
  (testing "JSON embedded in chatter"
    (is (= 1.0 (:score (eval/parse-judge-reply
                        "Sure! Here is my verdict: {\"score\": 1.0, \"reasoning\": \"x\"} hope that helps")))))
  (testing "scores are clamped"
    (is (= 1.0 (:score (eval/parse-judge-reply "{\"score\": 3}")))))
  (testing "garbage scores 0.0 with an error"
    (let [parsed (eval/parse-judge-reply "I think it's pretty good?")]
      (is (= 0.0 (:score parsed)))
      (is (:error parsed)))))

(defmethod provider/-generate! ::judge [_ {:lib/keys [model]} _opts]
  {:message {:role :assistant
             :content "{\"score\": 0.75, \"reasoning\": \"mostly right\"}"}
   :model model :usage {} :finish-reason :stop :raw {}})

(deftest llm-judge-scorer
  (let [config #:lib{:providers {:j {:lib/adapter ::judge}}
                     :models {:judge #:lib{:provider :j :model "judge-1"}}
                     :defaults #:lib{:model :judge}}
        scorer (eval/llm-judge {:model :judge :criteria "Is it French?"})
        result ((:lib/fn scorer) {:config config
                                  :case #:lib{:id :c :input "q" :expected "Paris"}
                                  :response #:lib{:text "Paris"}})]
    (is (= :llm-judge (:lib/id scorer)))
    (is (= 0.75 (:score result)))
    (is (= "mostly right" (:reasoning result)))))

(deftest print-summary-renders
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})
        out (with-out-str (eval/print-summary report))]
    (is (str/includes? out "variant"))
    (is (str/includes? out "model"))
    (is (str/includes? out ":good"))
    (is (str/includes? out "includes"))))

;; ---------------------------------------------------------------------------
;; :lib/task — evaluating something other than a single LLM call

(defmethod provider/-generate! ::should-not-be-called [_ _ _]
  (throw (ex-info "adapter should not have been called" {})))

(def custom-task-suite
  #:lib{:cases [#:lib{:id :c :input "ignored" :expected "custom"}]
        :variants [#:lib{:id :v}]
        :scorers [:includes]
        :task (fn [{:keys [case]}]
                #:lib{:text (str "the answer is " (:lib/expected case))})})

(deftest custom-task-runs-without-llm-adapter
  (let [config #:lib{:providers {:fake {:lib/adapter ::should-not-be-called}}
                     :defaults {}}
        report (eval/run config custom-task-suite {:concurrency 1})]
    (is (= 1 (count (:lib/results report))))
    (is (= 0 (get-in report [:lib/summary :by-variant :v :errors]))
        "the task ran without invoking the LLM adapter")
    (is (= 1.0 (get-in report [:lib/summary :by-variant :v :scores :includes :mean])))))

;; ---------------------------------------------------------------------------
;; :lib/thresholds — evals as a CI gate

(deftest thresholds-pass-and-fail
  (testing "a suite whose thresholds are met reports :lib/passed? true"
    (let [passing-suite (assoc suite
                               :lib/variants [#:lib{:id :good :model :a}]
                               :lib/thresholds {:includes 1.0})
          report (eval/run (lookup-config answers) passing-suite {:concurrency 1})]
      (is (true? (:lib/passed? report)))))

  (testing "a suite whose thresholds are missed reports :lib/passed? false"
    (let [failing-suite (assoc suite :lib/thresholds {:includes 1.0})
          report (eval/run (lookup-config answers) failing-suite {:concurrency 1})]
      (is (false? (:lib/passed? report)))))

  (testing "a suite with no thresholds carries no :lib/passed? key"
    (let [report (eval/run (lookup-config answers) suite {:concurrency 1})]
      (is (not (contains? report :lib/passed?))))))

;; ---------------------------------------------------------------------------
;; Report provenance

(deftest report-provenance
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})]
    (is (instance? java.time.Instant (:lib/run-at report)))
    (is (= 2 (:lib/case-count report)))
    (is (= 2 (:lib/variant-count report)))))

;; ---------------------------------------------------------------------------
;; Scorers as qualified symbols

(defn always-one [_] {:score 1.0})

(deftest qualified-symbol-scorer
  (let [suite (assoc suite :lib/scorers ['clj-llm.eval-test/always-one])
        report (eval/run (lookup-config answers) suite {:concurrency 1})]
    (is (= 1.0 (get-in report [:lib/summary :by-variant :good :scores :scorer-0 :mean])))
    (is (= 1.0 (get-in report [:lib/summary :by-variant :bad :scores :scorer-0 :mean])))))
