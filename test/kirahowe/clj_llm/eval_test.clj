(ns kirahowe.clj-llm.eval-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kirahowe.clj-llm.eval :as eval]
            [kirahowe.clj-llm.provider :as provider]))

;; A fake adapter that answers from a lookup in the provider config:
;; {"<model>|<last user content>" "answer"} — unknown keys throw.
(defmethod provider/generate! ::lookup
  [{:keys [answers]} {:keys [model messages]}]
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
  {:providers {:fake {:adapter ::lookup :answers answers}}
   :models {:a {:provider :fake :model "model-a"}
            :b {:provider :fake :model "model-b"}}
   :defaults {:model :a}})

(def suite
  {:cases [{:id :capital
            :input "Capital of France?"
            :expected "Paris"}
           {:id :sum
            :input "2+2?"
            :expected "4"}]
   :variants [{:id :good :model :a}
              {:id :bad :model :b}]
   :scorers [:includes]})

(def answers
  {"model-a|Capital of France?" "The capital of France is Paris."
   "model-a|2+2?" "4"
   "model-b|Capital of France?" "London, obviously."
   "model-b|2+2?" "5"})

(deftest run-suite
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})
        {:keys [results summary]} report]
    (testing "one result per case x variant"
      (is (= 4 (count results)))
      (is (= #{[:capital :good] [:sum :good] [:capital :bad] [:sum :bad]}
             (set (map (juxt :case-id :variant-id) results)))))
    (testing "scores per result"
      (let [by-key (into {} (map (juxt (juxt :case-id :variant-id) identity))
                         results)]
        (is (= 1.0 (get-in by-key [[:capital :good] :scores :includes :score])))
        (is (= 0.0 (get-in by-key [[:capital :bad] :scores :includes :score])))))
    (testing "summary aggregates per variant"
      (is (= 1.0 (get-in summary [:by-variant :good :scores :includes :mean])))
      (is (= 0.0 (get-in summary [:by-variant :bad :scores :includes :mean])))
      (is (= 2 (get-in summary [:by-variant :good :cases])))
      (is (= 0 (get-in summary [:by-variant :good :errors])))
      (is (= 20 (get-in summary [:by-variant :good :usage :input-tokens])))
      (is (number? (get-in summary [:by-variant :good :latency-ms :mean]))))
    (testing "results carry the full interaction record"
      (is (every? #(get-in % [:response :request :model]) results)))))

(deftest run-suite-concurrently
  (let [report (eval/run (lookup-config answers) suite {:concurrency 4})]
    (is (= 4 (count (:results report))))
    (is (= 1.0 (get-in report [:summary :by-variant :good :scores :includes :mean])))))

(deftest errors-are-contained
  (let [;; model-b answers are missing -> adapter throws for :bad variant
        config (lookup-config (select-keys answers
                                           ["model-a|Capital of France?"
                                            "model-a|2+2?"]))
        report (eval/run config suite {:concurrency 1})]
    (is (= 2 (get-in report [:summary :by-variant :bad :errors])))
    (is (= 0 (get-in report [:summary :by-variant :good :errors])))
    (is (every? :error (filter #(= :bad (:variant-id %)) (:results report))))))

(deftest suite-from-edn-file
  (let [dir (java.nio.file.Files/createTempDirectory
             "clj-llm-eval" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str dir "/suite.edn")]
    (spit path (pr-str suite))
    (let [report (eval/run (lookup-config answers) path {:concurrency 1})]
      (is (= 4 (count (:results report)))))))

(deftest variant-request-keys-flow-through
  (let [seen (atom [])]
    (defmethod provider/generate! ::spy [_ request]
      (swap! seen conj request)
      {:message {:role :assistant :content "ok"} :model (:model request)
       :usage {} :finish-reason :stop :raw {}})
    (eval/run {:providers {:s {:adapter ::spy}}
               :models {:m {:provider :s :model "m-1"}}
               :defaults {:model :m}}
              {:cases [{:id :c :input "q"}]
               :variants [{:id :v :model :m :system "terse" :temperature 0.1}]
               :scorers []}
              {:concurrency 1})
    (is (= "terse" (:system (first @seen))))
    (is (= 0.1 (:temperature (first @seen))))))

(deftest built-in-scorers
  (let [response {:text "The answer is Paris. "}]
    (is (= 1.0 (:score (eval/exact-match {:case {:expected "The answer is Paris."}
                                          :response response}))))
    (is (= 0.0 (:score (eval/exact-match {:case {:expected "Paris"}
                                          :response response}))))
    (is (= 1.0 (:score (eval/includes {:case {:expected "paris"}
                                       :response response}))))
    (is (= 0.0 (:score (eval/includes {:case {:expected "Lyon"}
                                       :response response}))))
    (is (= 1.0 (:score (eval/matches {:case {:expected "(?i)paris\\."}
                                      :response response}))))
    (is (= 0.0 (:score (eval/matches {:case {:expected "^\\d+$"}
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

(defmethod provider/generate! ::judge [_ {:keys [model]}]
  {:message {:role :assistant
             :content "{\"score\": 0.75, \"reasoning\": \"mostly right\"}"}
   :model model :usage {} :finish-reason :stop :raw {}})

(deftest llm-judge-scorer
  (let [config {:providers {:j {:adapter ::judge}}
                :models {:judge {:provider :j :model "judge-1"}}
                :defaults {:model :judge}}
        scorer (eval/llm-judge {:model :judge :criteria "Is it French?"})
        result ((:fn scorer) {:config config
                              :case {:id :c :input "q" :expected "Paris"}
                              :response {:text "Paris"}})]
    (is (= :llm-judge (:id scorer)))
    (is (= 0.75 (:score result)))
    (is (= "mostly right" (:reasoning result)))))

(deftest print-summary-renders
  (let [report (eval/run (lookup-config answers) suite {:concurrency 1})
        out (with-out-str (eval/print-summary report))]
    (is (str/includes? out "variant"))
    (is (str/includes? out ":good"))
    (is (str/includes? out "includes"))))
