(ns clj-llm.eval-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-llm.eval :as eval]
            [clj-llm.provider :as provider]))

;; A fake adapter that answers from a lookup in the provider config:
;; {"<model>|<last user content>" "answer"} — unknown keys throw.
(defmethod provider/-generate! ::lookup
  [{:keys [answers]} {:llm/keys [model messages]} _opts]
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
  #:llm{:providers {:fake {:llm/adapter ::lookup :answers answers}}
        :models {:a #:llm{:provider :fake :model "model-a"}
                 :b #:llm{:provider :fake :model "model-b"}}
        :defaults #:llm{:model :a}})

(def suite
  #:llm{:cases [#:llm{:id :capital
                      :input "Capital of France?"
                      :expected "Paris"}
                #:llm{:id :sum
                      :input "2+2?"
                      :expected "4"}]
        :variants [#:llm{:id :good :model :a}
                   #:llm{:id :bad :model :b}]
        :scorers [:includes]})

(def answers
  {"model-a|Capital of France?" "The capital of France is Paris."
   "model-a|2+2?" "4"
   "model-b|Capital of France?" "London, obviously."
   "model-b|2+2?" "5"})

(deftest run-suite
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})
        {:llm/keys [results summary]} report]
    (testing "one result per case x variant"
      (is (= 4 (count results)))
      (is (= #{[:capital :good] [:sum :good] [:capital :bad] [:sum :bad]}
             (set (map (juxt :llm/case-id :llm/variant-id) results)))))
    (testing "scores per result"
      (let [by-key (into {} (map (juxt (juxt :llm/case-id :llm/variant-id) identity))
                         results)]
        (is (= 1.0 (get-in by-key [[:capital :good] :llm/scores :includes :score])))
        (is (= 0.0 (get-in by-key [[:capital :bad] :llm/scores :includes :score])))))
    (testing "summary aggregates per variant"
      (is (= 1.0 (get-in summary [:by-variant :good :scores :includes :mean])))
      (is (= 0.0 (get-in summary [:by-variant :bad :scores :includes :mean])))
      (is (= 2 (get-in summary [:by-variant :good :cases])))
      (is (= 0 (get-in summary [:by-variant :good :errors])))
      (is (= 20 (get-in summary [:by-variant :good :usage :input-tokens])))
      (is (number? (get-in summary [:by-variant :good :latency-ms :mean]))))
    (testing "results carry the full interaction record"
      (is (every? #(get-in % [:llm/response :llm/request :llm/model]) results)))))

(deftest run-suite-concurrently
  (let [report (eval/run (lookup-config answers) suite {:concurrency 4})]
    (is (= 4 (count (:llm/results report))))
    (is (= 1.0 (get-in report [:llm/summary :by-variant :good :scores :includes :mean])))))

(deftest errors-are-contained
  (let [;; model-b answers are missing -> adapter throws for :bad variant
        config (lookup-config (select-keys answers
                                           ["model-a|Capital of France?"
                                            "model-a|2+2?"]))
        report (eval/run config suite {:concurrency 1})]
    (is (= 2 (get-in report [:llm/summary :by-variant :bad :errors])))
    (is (= 0 (get-in report [:llm/summary :by-variant :good :errors])))
    (is (every? :llm/error (filter #(= :bad (:llm/variant-id %)) (:llm/results report))))))

(deftest suite-from-edn-file
  (let [dir (java.nio.file.Files/createTempDirectory
             "clj-llm-eval" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir "/suite.edn")]
    (spit path (pr-str suite))
    (let [report (eval/run (lookup-config answers) path {:concurrency 1})]
      (is (= 4 (count (:llm/results report)))))))

(deftest variant-request-keys-flow-through
  (let [seen (atom [])]
    (defmethod provider/-generate! ::spy [_ {:llm/keys [model] :as request} _opts]
      (swap! seen conj request)
      {:message {:role :assistant :content "ok"} :model model
       :usage {} :finish-reason :stop :raw {}})
    (eval/run #:llm{:providers {:s {:llm/adapter ::spy}}
                    :models {:m #:llm{:provider :s :model "m-1"}}
                    :defaults #:llm{:model :m}}
              #:llm{:cases [#:llm{:id :c :input "q"}]
                    :variants [#:llm{:id :v :model :m :system "terse" :temperature 0.1}]
                    :scorers []}
              {:concurrency 1})
    (is (= "terse" (:llm/system (first @seen))))
    (is (= 0.1 (:llm/temperature (first @seen))))))

(deftest built-in-scorers
  (let [response #:llm{:text "The answer is Paris. "}]
    (is (= 1.0 (:score (eval/exact-match {:case #:llm{:expected "The answer is Paris."}
                                          :response response}))))
    (is (= 0.0 (:score (eval/exact-match {:case #:llm{:expected "Paris"}
                                          :response response}))))
    (is (= 1.0 (:score (eval/includes {:case #:llm{:expected "paris"}
                                       :response response}))))
    (is (= 0.0 (:score (eval/includes {:case #:llm{:expected "Lyon"}
                                       :response response}))))
    (is (= 1.0 (:score (eval/matches {:case #:llm{:expected "(?i)paris\\."}
                                      :response response}))))
    (is (= 0.0 (:score (eval/matches {:case #:llm{:expected "^\\d+$"}
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

(defmethod provider/-generate! ::judge [_ {:llm/keys [model]} _opts]
  {:message {:role :assistant
             :content "{\"score\": 0.75, \"reasoning\": \"mostly right\"}"}
   :model model :usage {} :finish-reason :stop :raw {}})

(deftest llm-judge-scorer
  (let [config #:llm{:providers {:j {:llm/adapter ::judge}}
                     :models {:judge #:llm{:provider :j :model "judge-1"}}
                     :defaults #:llm{:model :judge}}
        scorer (eval/llm-judge {:model :judge :criteria "Is it French?"})
        result ((:llm/fn scorer) {:config config
                                  :case #:llm{:id :c :input "q" :expected "Paris"}
                                  :response #:llm{:text "Paris"}})]
    (is (= :llm-judge (:llm/id scorer)))
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
;; :llm/task — evaluating something other than a single LLM call

(defmethod provider/-generate! ::should-not-be-called [_ _ _]
  (throw (ex-info "adapter should not have been called" {})))

(def custom-task-suite
  #:llm{:cases [#:llm{:id :c :input "ignored" :expected "custom"}]
        :variants [#:llm{:id :v}]
        :scorers [:includes]
        :task (fn [{:keys [case]}]
                #:llm{:text (str "the answer is " (:llm/expected case))})})

(deftest custom-task-runs-without-llm-adapter
  (let [config #:llm{:providers {:fake {:llm/adapter ::should-not-be-called}}
                     :defaults {}}
        report (eval/run config custom-task-suite {:concurrency 1})]
    (is (= 1 (count (:llm/results report))))
    (is (= 0 (get-in report [:llm/summary :by-variant :v :errors]))
        "the task ran without invoking the LLM adapter")
    (is (= 1.0 (get-in report [:llm/summary :by-variant :v :scores :includes :mean])))))

;; ---------------------------------------------------------------------------
;; :llm/thresholds — evals as a CI gate

(deftest thresholds-pass-and-fail
  (testing "a suite whose thresholds are met reports :llm/passed? true"
    (let [passing-suite (assoc suite
                               :llm/variants [#:llm{:id :good :model :a}]
                               :llm/thresholds {:includes 1.0})
          report (eval/run (lookup-config answers) passing-suite {:concurrency 1})]
      (is (true? (:llm/passed? report)))))

  (testing "a suite whose thresholds are missed reports :llm/passed? false"
    (let [failing-suite (assoc suite :llm/thresholds {:includes 1.0})
          report (eval/run (lookup-config answers) failing-suite {:concurrency 1})]
      (is (false? (:llm/passed? report)))))

  (testing "a suite with no thresholds carries no :llm/passed? key"
    (let [report (eval/run (lookup-config answers) suite {:concurrency 1})]
      (is (not (contains? report :llm/passed?))))))

;; ---------------------------------------------------------------------------
;; Report provenance

(deftest report-provenance
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})]
    (is (instance? java.time.Instant (:llm/run-at report)))
    (is (= 2 (:llm/case-count report)))
    (is (= 2 (:llm/variant-count report)))))

;; ---------------------------------------------------------------------------
;; Scorers as qualified symbols

(defn always-one [_] {:score 1.0})

(deftest qualified-symbol-scorer
  (let [suite (assoc suite :llm/scorers ['clj-llm.eval-test/always-one])
        report (eval/run (lookup-config answers) suite {:concurrency 1})]
    (is (= 1.0 (get-in report [:llm/summary :by-variant :good :scores :scorer-0 :mean])))
    (is (= 1.0 (get-in report [:llm/summary :by-variant :bad :scores :scorer-0 :mean])))))
