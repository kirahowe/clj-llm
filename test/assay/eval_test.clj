(ns assay.eval-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [assay.eval :as eval]
            [assay.provider :as provider]))

;; A fake adapter that answers from a lookup in the provider config:
;; {"<model>|<last user content>" "answer"} — unknown keys throw.
(defmethod provider/generate! ::lookup
  [{:keys [answers]} {:assay/keys [model messages]} _opts]
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
  #:assay{:providers {:fake {:assay/adapter ::lookup :answers answers}}
          :models {:a #:assay{:provider :fake :model "model-a"}
                   :b #:assay{:provider :fake :model "model-b"}}
          :defaults #:assay{:model :a}})

(def suite
  #:assay{:cases [#:assay{:id :capital
                          :input "Capital of France?"
                          :expected "Paris"}
                  #:assay{:id :sum
                          :input "2+2?"
                          :expected "4"}]
          :variants [#:assay{:id :good :model :a}
                     #:assay{:id :bad :model :b}]
          :scorers [:includes]})

(def answers
  {"model-a|Capital of France?" "The capital of France is Paris."
   "model-a|2+2?" "4"
   "model-b|Capital of France?" "London, obviously."
   "model-b|2+2?" "5"})

(deftest run-suite
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})
        {:assay/keys [results summary]} report]
    (testing "one result per case x variant"
      (is (= 4 (count results)))
      (is (= #{[:capital :good] [:sum :good] [:capital :bad] [:sum :bad]}
             (set (map (juxt :assay/case-id :assay/variant-id) results)))))
    (testing "scores per result"
      (let [by-key (into {} (map (juxt (juxt :assay/case-id :assay/variant-id) identity))
                         results)]
        (is (= 1.0 (get-in by-key [[:capital :good] :assay/scores :includes :score])))
        (is (= 0.0 (get-in by-key [[:capital :bad] :assay/scores :includes :score])))))
    (testing "summary aggregates per variant"
      (is (= 1.0 (get-in summary [:by-variant :good :scores :includes :mean])))
      (is (= 0.0 (get-in summary [:by-variant :bad :scores :includes :mean])))
      (is (= 2 (get-in summary [:by-variant :good :cases])))
      (is (= 0 (get-in summary [:by-variant :good :errors])))
      (is (= 20 (get-in summary [:by-variant :good :usage :input-tokens])))
      (is (number? (get-in summary [:by-variant :good :latency-ms :mean]))))
    (testing "results carry the full interaction record"
      (is (every? #(get-in % [:assay/response :assay/request :assay/model]) results)))))

(deftest run-suite-concurrently
  (let [report (eval/run (lookup-config answers) suite {:concurrency 4})]
    (is (= 4 (count (:assay/results report))))
    (is (= 1.0 (get-in report [:assay/summary :by-variant :good :scores :includes :mean])))))

(deftest errors-are-contained
  (let [;; model-b answers are missing -> adapter throws for :bad variant
        config (lookup-config (select-keys answers
                                           ["model-a|Capital of France?"
                                            "model-a|2+2?"]))
        report (eval/run config suite {:concurrency 1})]
    (is (= 2 (get-in report [:assay/summary :by-variant :bad :errors])))
    (is (= 0 (get-in report [:assay/summary :by-variant :good :errors])))
    (is (every? :assay/error (filter #(= :bad (:assay/variant-id %)) (:assay/results report))))))

(deftest suite-from-edn-file
  (let [dir (java.nio.file.Files/createTempDirectory
             "clj-llm-eval" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir "/suite.edn")]
    (spit path (pr-str suite))
    (let [report (eval/run (lookup-config answers) path {:concurrency 1})]
      (is (= 4 (count (:assay/results report)))))))

(deftest variant-request-keys-flow-through
  (let [seen (atom [])]
    (defmethod provider/generate! ::spy [_ {:assay/keys [model] :as request} _opts]
      (swap! seen conj request)
      {:message {:role :assistant :content "ok"} :model model
       :usage {} :finish-reason :stop :raw {}})
    (eval/run #:assay{:providers {:s {:assay/adapter ::spy}}
                      :models {:m #:assay{:provider :s :model "m-1"}}
                      :defaults #:assay{:model :m}}
              #:assay{:cases [#:assay{:id :c :input "q"}]
                      :variants [#:assay{:id :v :model :m :system "terse" :temperature 0.1}]
                      :scorers []}
              {:concurrency 1})
    (is (= "terse" (:assay/system (first @seen))))
    (is (= 0.1 (:assay/temperature (first @seen))))))

(deftest built-in-scorers
  (let [response #:assay{:text "The answer is Paris. "}]
    (is (= 1.0 (:score (eval/exact-match {:case #:assay{:expected "The answer is Paris."}
                                          :response response}))))
    (is (= 0.0 (:score (eval/exact-match {:case #:assay{:expected "Paris"}
                                          :response response}))))
    (is (= 1.0 (:score (eval/includes {:case #:assay{:expected "paris"}
                                       :response response}))))
    (is (= 0.0 (:score (eval/includes {:case #:assay{:expected "Lyon"}
                                       :response response}))))
    (is (= 1.0 (:score (eval/matches {:case #:assay{:expected "(?i)paris\\."}
                                      :response response}))))
    (is (= 0.0 (:score (eval/matches {:case #:assay{:expected "^\\d+$"}
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

(defmethod provider/generate! ::judge [_ {:assay/keys [model]} _opts]
  {:message {:role :assistant
             :content "{\"score\": 0.75, \"reasoning\": \"mostly right\"}"}
   :model model :usage {} :finish-reason :stop :raw {}})

(deftest llm-judge-scorer
  (let [config #:assay{:providers {:j {:assay/adapter ::judge}}
                       :models {:judge #:assay{:provider :j :model "judge-1"}}
                       :defaults #:assay{:model :judge}}
        scorer (eval/llm-judge {:model :judge :criteria "Is it French?"})
        result ((:assay/fn scorer) {:config config
                                    :case #:assay{:id :c :input "q" :expected "Paris"}
                                    :response #:assay{:text "Paris"}})]
    (is (= :llm-judge (:assay/id scorer)))
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
;; :assay/task — evaluating something other than a single LLM call

(defmethod provider/generate! ::should-not-be-called [_ _ _]
  (throw (ex-info "adapter should not have been called" {})))

(def custom-task-suite
  #:assay{:cases [#:assay{:id :c :input "ignored" :expected "custom"}]
          :variants [#:assay{:id :v}]
          :scorers [:includes]
          :task (fn [{:keys [case]}]
                  #:assay{:text (str "the answer is " (:assay/expected case))})})

(deftest custom-task-runs-without-llm-adapter
  (let [config #:assay{:providers {:fake {:assay/adapter ::should-not-be-called}}
                       :defaults {}}
        report (eval/run config custom-task-suite {:concurrency 1})]
    (is (= 1 (count (:assay/results report))))
    (is (= 0 (get-in report [:assay/summary :by-variant :v :errors]))
        "the task ran without invoking the LLM adapter")
    (is (= 1.0 (get-in report [:assay/summary :by-variant :v :scores :includes :mean])))))

;; ---------------------------------------------------------------------------
;; :assay/thresholds — evals as a CI gate

(deftest thresholds-pass-and-fail
  (testing "a suite whose thresholds are met reports :assay/passed? true"
    (let [passing-suite (assoc suite
                               :assay/variants [#:assay{:id :good :model :a}]
                               :assay/thresholds {:includes 1.0})
          report (eval/run (lookup-config answers) passing-suite {:concurrency 1})]
      (is (true? (:assay/passed? report)))))

  (testing "a suite whose thresholds are missed reports :assay/passed? false"
    (let [failing-suite (assoc suite :assay/thresholds {:includes 1.0})
          report (eval/run (lookup-config answers) failing-suite {:concurrency 1})]
      (is (false? (:assay/passed? report)))))

  (testing "a suite with no thresholds carries no :assay/passed? key"
    (let [report (eval/run (lookup-config answers) suite {:concurrency 1})]
      (is (not (contains? report :assay/passed?))))))

;; ---------------------------------------------------------------------------
;; Report provenance

(deftest report-provenance
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})]
    (is (instance? java.time.Instant (:assay/run-at report)))
    (is (= 2 (:assay/case-count report)))
    (is (= 2 (:assay/variant-count report)))))

;; ---------------------------------------------------------------------------
;; Scorers as qualified symbols

(defn always-one [_] {:score 1.0})

(deftest qualified-symbol-scorer
  (let [suite (assoc suite :assay/scorers ['assay.eval-test/always-one])
        report (eval/run (lookup-config answers) suite {:concurrency 1})]
    (is (= 1.0 (get-in report [:assay/summary :by-variant :good :scores :scorer-0 :mean])))
    (is (= 1.0 (get-in report [:assay/summary :by-variant :bad :scores :scorer-0 :mean])))))
