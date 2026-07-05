(ns kirahowe.clj-llm.eval
  "First-class evals: run cases against variants, score the results.

  There is no iterating toward 'the best' model/prompt/parameters without
  measuring, so clj-llm bakes evals in rather than leaving them as an
  exercise:

  1. Every kirahowe.clj-llm/generate response is already a complete
     interaction record (:request, :usage, :latency-ms, ...). Set
     :on-interaction under :defaults in config to collect records from
     live traffic — collected records replay directly as eval cases,
     since a case accepts the same :messages a record carries.
  2. This namespace runs suites: cases × variants → scored results plus
     a per-variant summary, so changing a model or prompt becomes a
     benchmarked decision instead of a vibe.

  A suite is plain data — inline, or an EDN file read with the same
  aero reader as config files:

    {:cases    [{:id :capital
                 :input \"What is the capital of France?\"
                 :expected \"Paris\"}]
     :variants [{:id :baseline :model :smart}
                {:id :cheap    :model :fast :system \"Answer in one word.\"}]
     :scorers  [:includes]}

    (eval/run config \"evals/suite.edn\")
    ;; => {:results [{:case-id ... :variant-id ... :response ... :scores ...} ...]
    ;;     :summary {:by-variant {...}}}

  Cases:    :id, plus :input (a prompt string) or :messages (a full
            conversation), optional :expected (whatever your scorers
            need), and any custom keys your scorers read.
  Variants: :id plus any generate request keys (:model, :system,
            :temperature, :tools, ...) — a variant IS the thing you are
            comparing.
  Scorers:  keywords naming built-ins (:exact-match, :includes,
            :matches), maps {:id kw :fn f}, or bare fns. A scorer
            receives {:config :case :variant :response} and returns a
            map with :score (0.0–1.0) plus anything else worth keeping
            (e.g. :reasoning). Use (llm-judge {...}) for model-graded
            scoring — the sensible default when there is no mechanical
            ground truth."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [kirahowe.clj-llm :as llm]
            [kirahowe.clj-llm.config :as config])
  (:import (java.util.concurrent Callable Executors)))

;; ---------------------------------------------------------------------------
;; Built-in scorers

(defn exact-match
  "1.0 when the trimmed response text equals the trimmed :expected."
  [{:keys [case response]}]
  {:score (if (= (str/trim (str (:expected case)))
                 (str/trim (str (:text response))))
            1.0 0.0)})

(defn includes
  "1.0 when the response text contains :expected (case-insensitive)."
  [{:keys [case response]}]
  {:score (if (str/includes? (str/lower-case (str (:text response)))
                             (str/lower-case (str (:expected case))))
            1.0 0.0)})

(defn matches
  "1.0 when the regex :expected (string or pattern) matches the response."
  [{:keys [case response]}]
  (let [pattern (let [e (:expected case)]
                  (if (instance? java.util.regex.Pattern e) e (re-pattern (str e))))]
    {:score (if (re-find pattern (str (:text response))) 1.0 0.0)}))

(def built-in-scorers
  {:exact-match {:id :exact-match :fn exact-match}
   :includes {:id :includes :fn includes}
   :matches {:id :matches :fn matches}})

;; ---------------------------------------------------------------------------
;; Model-graded scoring

(def judge-system-prompt
  (str "You are a strict, consistent evaluator. Judge only against the "
       "given criteria. Reply with ONLY a JSON object of the form "
       "{\"score\": <number from 0.0 to 1.0>, \"reasoning\": \"<one sentence>\"} "
       "and nothing else."))

(defn- judge-prompt [criteria case response]
  (str "Evaluate the RESPONSE below.\n\n"
       "Criteria: " criteria "\n\n"
       (when-let [input (:input case)]
         (str "Task given to the model:\n" input "\n\n"))
       (when (some? (:expected case))
         (str "Reference answer:\n" (:expected case) "\n\n"))
       "RESPONSE:\n" (:text response)))

(defn parse-judge-reply
  "Extract {:score <0.0-1.0> :reasoning ...} from a judge's reply,
  scoring 0.0 (with an :error) when the reply is unusable."
  [text]
  (let [parsed (try
                 (some-> (re-find #"(?s)\{.*\}" (str text))
                         (json/read-json :key-fn keyword))
                 (catch Exception _ nil))]
    (if (number? (:score parsed))
      {:score (-> (:score parsed) double (max 0.0) (min 1.0))
       :reasoning (:reasoning parsed)}
      {:score 0.0 :error (str "unparseable judge reply: " text)})))

(defn llm-judge
  "Returns a scorer that asks a model to grade each response against
  `:criteria` (a plain-language rubric). `:model` picks the judge model
  (any model designator; defaults to the config's default model — using
  a different/stronger model than the one under test is wise). `:id`
  names the scorer in results (default :llm-judge)."
  [{:keys [model criteria id] :or {id :llm-judge}}]
  {:id id
   :fn (fn [{:keys [config case response]}]
         (let [reply (llm/generate config
                                   (cond-> {:system judge-system-prompt
                                            :prompt (judge-prompt criteria case response)}
                                     model (assoc :model model)))]
           (parse-judge-reply (:text reply))))})

;; ---------------------------------------------------------------------------
;; Running suites

(defn- normalize-scorer [i scorer]
  (cond
    (keyword? scorer)
    (or (built-in-scorers scorer)
        (throw (ex-info (str "Unknown built-in scorer " scorer ". Known: "
                             (pr-str (keys built-in-scorers)))
                        {:type ::unknown-scorer :scorer scorer})))

    (map? scorer) scorer
    (fn? scorer) {:id (keyword (str "scorer-" i)) :fn scorer}
    :else (throw (ex-info (str "Unsupported scorer: " (pr-str scorer))
                          {:type ::unknown-scorer :scorer scorer}))))

(defn- case->request [case variant]
  (merge (cond
           (:messages case) {:messages (:messages case)}
           (:input case) {:messages [{:role :user :content (:input case)}]}
           :else (throw (ex-info (str "Case " (:id case)
                                      " needs :input or :messages")
                                 {:type ::invalid-case :case case})))
         (dissoc variant :id)))

(defn- run-one [config case variant scorers]
  (let [base {:case-id (:id case) :variant-id (:id variant)}]
    (try
      (let [response (llm/generate config (case->request case variant))
            context {:config config :case case :variant variant
                     :response response}
            scores (into {}
                         (map (fn [{scorer-id :id scorer-fn :fn}]
                                [scorer-id
                                 (try (scorer-fn context)
                                      (catch Exception e
                                        {:score 0.0 :error (ex-message e)}))]))
                         scorers)]
        (assoc base :response response :scores scores))
      (catch Exception e
        (assoc base :error (ex-message e) :ex-data (ex-data e))))))

(defn- run-all [config jobs scorers concurrency]
  (if (<= concurrency 1)
    (mapv (fn [[case variant]] (run-one config case variant scorers)) jobs)
    (let [pool (Executors/newFixedThreadPool concurrency)]
      (try
        (->> jobs
             (mapv (fn [[case variant]]
                     (.submit pool ^Callable #(run-one config case variant scorers))))
             (mapv #(.get ^java.util.concurrent.Future %)))
        (finally
          (.shutdown pool))))))

(defn- mean [xs]
  (when (seq xs)
    (/ (reduce + 0.0 xs) (count xs))))

(defn summarize
  "Aggregate eval results per variant: score means, error counts, token
  usage and latency — the numbers to compare when deciding whether a
  model/prompt change is an improvement."
  [results]
  {:by-variant
   (into {}
         (for [[variant-id variant-results] (group-by :variant-id results)]
           (let [ok (remove :error variant-results)
                 score-ids (distinct (mapcat (comp keys :scores) ok))]
             [variant-id
              {:cases (count variant-results)
               :errors (count (filter :error variant-results))
               :scores (into {}
                             (for [scorer-id score-ids]
                               [scorer-id
                                {:mean (mean (keep #(get-in % [:scores scorer-id :score]) ok))}]))
               :latency-ms {:mean (mean (keep #(get-in % [:response :latency-ms]) ok))
                            :max (reduce max 0 (keep #(get-in % [:response :latency-ms]) ok))}
               :usage {:input-tokens (reduce + 0 (keep #(get-in % [:response :usage :input-tokens]) ok))
                       :output-tokens (reduce + 0 (keep #(get-in % [:response :usage :output-tokens]) ok))}}])))})

(defn run
  "Run an eval suite against `config`. `suite` is a map
  {:cases [...] :variants [...] :scorers [...]} or anything
  config/read-config accepts (a path to an EDN suite file).

  Options:
    :concurrency  how many cases to run in parallel (default 4)

  Returns {:results [...] :summary {:by-variant {...}}} — plain data;
  print it with print-summary, diff it, or store it next to the config
  that produced it."
  ([config suite] (run config suite nil))
  ([config suite {:keys [concurrency] :or {concurrency 4}}]
   (let [suite (if (map? suite) suite (config/read-config suite))
         scorers (vec (map-indexed normalize-scorer (:scorers suite)))
         variants (or (not-empty (:variants suite)) [{:id :default}])
         jobs (for [variant variants, case (:cases suite)] [case variant])
         results (run-all config (vec jobs) scorers concurrency)]
     {:results results
      :summary (summarize results)})))

;; ---------------------------------------------------------------------------
;; Reporting

(defn- fmt [x]
  (cond
    (nil? x) "-"
    (float? x) (format "%.3f" (double x))
    :else (str x)))

(defn print-summary
  "Print a per-variant comparison table for a `run` report."
  [{:keys [summary]}]
  (let [by-variant (:by-variant summary)
        scorer-ids (->> (vals by-variant) (mapcat (comp keys :scores)) distinct sort)
        headers (concat ["variant" "cases" "errors"]
                        (map name scorer-ids)
                        ["latency(mean ms)" "in-tok" "out-tok"])
        rows (for [[variant-id s] (sort-by key by-variant)]
               (concat [(str variant-id) (str (:cases s)) (str (:errors s))]
                       (map #(fmt (get-in s [:scores % :mean])) scorer-ids)
                       [(fmt (some-> (get-in s [:latency-ms :mean]) long))
                        (fmt (get-in s [:usage :input-tokens]))
                        (fmt (get-in s [:usage :output-tokens]))]))
        widths (map (fn [i]
                      (apply max (count (nth (vec headers) i))
                             (map #(count (nth (vec %) i)) rows)))
                    (range (count headers)))
        line (fn [cells]
               (println (str/join "  " (map (fn [cell width]
                                              (format (str "%-" width "s") cell))
                                            cells widths))))]
    (line headers)
    (line (map #(apply str (repeat % "-")) widths))
    (run! line rows)))

(defn -main
  "Run a suite from the command line and print the summary.

    clojure -M:dev -m kirahowe.clj-llm.eval [suite.edn [llm.edn [profile]]]

  Defaults: evals/suite.edn and llm.edn. Exits non-zero when any case
  errored (scores are yours to judge; errors are not)."
  [& [suite-path config-path profile]]
  (let [config (llm/read-config (or config-path "llm.edn")
                                (if profile {:profile (keyword profile)} {}))
        report (run config (or suite-path "evals/suite.edn"))]
    (print-summary report)
    (let [errors (filter :error (:results report))]
      (doseq [{:keys [case-id variant-id error]} errors]
        (println "ERROR" case-id variant-id "-" error))
      (System/exit (if (seq errors) 1 0)))))
