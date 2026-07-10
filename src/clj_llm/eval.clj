(ns clj-llm.eval
  "First-class evals: run cases against variants, score the results.

  There is no iterating toward 'the best' model/prompt/parameters without
  measuring, so clj-llm bakes evals in rather than leaving them as an
  exercise:

  1. Every clj-llm.core/generate response is already a complete
     interaction record (:lib/request, :lib/usage, :lib/latency-ms,
     ...). Set :lib/on-interaction under :lib/defaults in config to
     collect records from live traffic — collected records replay
     directly as eval cases, since a case accepts the same
     :lib/messages a record carries.
  2. This namespace runs suites: cases × variants → scored results plus
     a per-variant summary, so changing a model or prompt becomes a
     benchmarked decision instead of a vibe.

  A suite is plain data — inline, or an EDN file read with the same
  aero reader as config files:

    #:lib{:cases    [#:lib{:id :capital
                               :input \"What is the capital of France?\"
                               :expected \"Paris\"}]
            :variants [#:lib{:id :baseline :model :smart}
                       #:lib{:id :cheap :model :fast :system \"Answer in one word.\"}]
            :scorers  [:includes]}

    (eval/run config \"evals/suite.edn\")
    ;; => #:lib{:results [...] :summary {:by-variant {...}}
    ;;            :run-at #inst \"...\" :passed? true}

  Cases:    :lib/id, plus :lib/input (a prompt string) or
            :lib/messages (a full conversation), optional
            :lib/expected (whatever your scorers need), and any custom
            keys your scorers read — unqualified and your-namespaced
            keys are yours.
  Variants: :lib/id plus any generate request keys (:lib/model,
            :lib/system, :lib/temperature, :lib/tools, ...) — a
            variant IS the thing you are comparing.
  Scorers:  keywords naming built-ins (:exact-match, :includes,
            :matches), maps #:lib{:id kw :fn f}, bare fns, or
            qualified symbols (resolved with requiring-resolve, so EDN
            suites can name scorers defined in your code). A scorer
            receives {:config ... :case ... :variant ... :response ...}
            and returns a map with :score (0.0–1.0) plus anything else
            worth keeping (e.g. :reasoning). Use (llm-judge {...}) for
            model-graded scoring — the sensible default when there is no
            mechanical ground truth.
  Task:     :lib/task (a fn or qualified symbol) is what a case×variant
            actually runs — (fn [{:keys [config case variant]}]) returning
            a response map. The default task builds a request from the
            case and variant and calls clj-llm.core/generate, which evals a
            single LLM call. Supply your own task to eval any system
            *containing* LLM calls — a RAG pipeline, an agent loop, a
            whole handler — as long as it returns a map your scorers can
            read (conventionally at least :lib/text; return real
            generate responses and latency/usage summaries stay accurate).
  Thresholds: :lib/thresholds {scorer-id min-mean-score} makes the
            report (and the CLI exit code) fail when a variant's mean for
            that scorer drops below the minimum — evals as a CI gate, not
            just a report."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clj-llm.core :as llm]
            [clj-llm.config :as config]
            [clj-llm.spec :as spec])
  (:import (java.util.concurrent Callable Executors)))

;; ---------------------------------------------------------------------------
;; Built-in scorers

(defn exact-match
  "1.0 when the trimmed response text equals the trimmed :lib/expected."
  [{:keys [case response]}]
  {:score (if (= (str/trim (str (:lib/expected case)))
                 (str/trim (str (:lib/text response))))
            1.0 0.0)})

(defn includes
  "1.0 when the response text contains :lib/expected (case-insensitive)."
  [{:keys [case response]}]
  {:score (if (str/includes? (str/lower-case (str (:lib/text response)))
                             (str/lower-case (str (:lib/expected case))))
            1.0 0.0)})

(defn matches
  "1.0 when the regex :lib/expected (string or pattern) matches the
  response text."
  [{:keys [case response]}]
  (let [pattern (let [e (:lib/expected case)]
                  (if (instance? java.util.regex.Pattern e) e (re-pattern (str e))))]
    {:score (if (re-find pattern (str (:lib/text response))) 1.0 0.0)}))

(def built-in-scorers
  {:exact-match #:lib{:id :exact-match :fn exact-match}
   :includes #:lib{:id :includes :fn includes}
   :matches #:lib{:id :matches :fn matches}})

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
       (when-let [input (:lib/input case)]
         (str "Task given to the model:\n" input "\n\n"))
       (when (some? (:lib/expected case))
         (str "Reference answer:\n" (:lib/expected case) "\n\n"))
       "RESPONSE:\n" (:lib/text response)))

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
  names the scorer in results (default :llm-judge; give each judge its
  own :id when a suite uses several)."
  [{:keys [model criteria id] :or {id :llm-judge}}]
  #:lib{:id id
        :fn (fn [{:keys [config case response]}]
              (let [reply (llm/generate config
                                        (cond-> #:lib{:system judge-system-prompt
                                                      :prompt (judge-prompt criteria case response)}
                                          model (assoc :lib/model model)))]
                (parse-judge-reply (:lib/text reply))))})

;; ---------------------------------------------------------------------------
;; Running suites

(defn- resolve-symbol [sym error-type]
  (try
    @(requiring-resolve sym)
    (catch Exception e
      (throw (ex-info (str "Could not resolve " sym ": " (ex-message e))
                      {:type error-type :symbol sym})))))

(defn- normalize-scorer [i scorer]
  (cond
    (keyword? scorer)
    (or (built-in-scorers scorer)
        (throw (ex-info (str "Unknown built-in scorer " scorer ". Known: "
                             (pr-str (keys built-in-scorers)))
                        {:type :lib/unknown-scorer :scorer scorer})))

    (qualified-symbol? scorer)
    (normalize-scorer i (resolve-symbol scorer :lib/unknown-scorer))

    (map? scorer) scorer
    (fn? scorer) #:lib{:id (keyword (str "scorer-" i)) :fn scorer}
    :else (throw (ex-info (str "Unsupported scorer: " (pr-str scorer))
                          {:type :lib/unknown-scorer :scorer scorer}))))

(defn case->request
  "The request the default task runs for a case × variant: the case's
  conversation with the variant's request keys merged in."
  [case variant]
  (merge (cond
           (:lib/messages case) {:lib/messages (:lib/messages case)}
           (:lib/input case) {:lib/messages [{:role :user
                                              :content (:lib/input case)}]}
           :else (throw (ex-info (str "Case " (:lib/id case)
                                      " needs :lib/input or :lib/messages")
                                 {:type :lib/invalid-case :case case})))
         (dissoc variant :lib/id)))

(defn- default-task [{:keys [config case variant]}]
  (llm/generate config (case->request case variant)))

(defn- resolve-task [suite]
  (let [task (:lib/task suite)]
    (cond
      (nil? task) default-task
      (fn? task) task
      (qualified-symbol? task) (resolve-symbol task :lib/invalid-suite)
      :else (throw (ex-info (str "Unsupported :lib/task: " (pr-str task))
                            {:type :lib/invalid-suite :task task})))))

(defn- run-one [config case variant scorers task]
  (let [base #:lib{:case-id (:lib/id case) :variant-id (:lib/id variant)}]
    (try
      (let [response (task {:config config :case case :variant variant})
            context {:config config :case case :variant variant
                     :response response}
            scores (into {}
                         (map (fn [{scorer-id :lib/id scorer-fn :lib/fn}]
                                [scorer-id
                                 (try (scorer-fn context)
                                      (catch Exception e
                                        {:score 0.0 :error (ex-message e)}))]))
                         scorers)]
        (assoc base :lib/response response :lib/scores scores))
      (catch Exception e
        (assoc base :lib/error (ex-message e) :lib/ex-data (ex-data e))))))

(defn- run-all [config jobs scorers task concurrency]
  (if (<= concurrency 1)
    (mapv (fn [[case variant]] (run-one config case variant scorers task)) jobs)
    (let [pool (Executors/newFixedThreadPool concurrency)]
      (try
        (->> jobs
             (mapv (fn [[case variant]]
                     (.submit pool ^Callable #(run-one config case variant scorers task))))
             (mapv #(.get ^java.util.concurrent.Future %)))
        (finally
          (.shutdown pool))))))

(defn- mean [xs]
  (when (seq xs)
    (/ (reduce + 0.0 xs) (count xs))))

(defn summarize
  "Aggregate eval results per variant: score means, error counts, token
  usage, latency and the model that actually served the variant — the
  numbers to compare when deciding whether a model/prompt change is an
  improvement."
  [results]
  {:by-variant
   (into {}
         (for [[variant-id variant-results] (group-by :lib/variant-id results)]
           (let [ok (remove :lib/error variant-results)
                 score-ids (distinct (mapcat (comp keys :lib/scores) ok))]
             [variant-id
              {:cases (count variant-results)
               :errors (count (filter :lib/error variant-results))
               :model (some #(get-in % [:lib/response :lib/model]) ok)
               :scores (into {}
                             (for [scorer-id score-ids]
                               [scorer-id
                                {:mean (mean (keep #(get-in % [:lib/scores scorer-id :score]) ok))}]))
               :latency-ms {:mean (mean (keep #(get-in % [:lib/response :lib/latency-ms]) ok))
                            :max (reduce max 0 (keep #(get-in % [:lib/response :lib/latency-ms]) ok))}
               :usage {:input-tokens (reduce + 0 (keep #(get-in % [:lib/response :lib/usage :input-tokens]) ok))
                       :output-tokens (reduce + 0 (keep #(get-in % [:lib/response :lib/usage :output-tokens]) ok))}}])))})

(defn- thresholds-met? [summary thresholds]
  (every? (fn [[_variant-id s]]
            (every? (fn [[scorer-id min-mean]]
                      (let [m (get-in s [:scores scorer-id :mean])]
                        (and (some? m) (>= m min-mean))))
                    thresholds))
          (:by-variant summary)))

(defn run
  "Run an eval suite against `config`. `suite` is a map
  #:lib{:cases [...] :variants [...] :scorers [...] :task ...
  :thresholds {...}} or anything clj-llm.config/read-config accepts (a
  path to an EDN suite file).

  Options:
    :concurrency  how many cases to run in parallel (default 4)

  Returns #:lib{:results [...] :summary {:by-variant {...}}
  :run-at <Instant> :passed? <bool, present when the suite has
  thresholds>} — plain data; print it with print-summary, diff it, or
  store it next to the config that produced it."
  ([config suite] (run config suite nil))
  ([config suite {:keys [concurrency] :or {concurrency 4}}]
   (let [suite (spec/assert-suite!
                (if (map? suite) suite (config/read-config suite)))
         task (resolve-task suite)
         scorers (vec (map-indexed normalize-scorer (:lib/scorers suite)))
         variants (or (not-empty (:lib/variants suite)) [{:lib/id :default}])
         thresholds (:lib/thresholds suite)
         run-at (java.time.Instant/now)
         jobs (for [variant variants, case (:lib/cases suite)] [case variant])
         results (run-all config (vec jobs) scorers task concurrency)
         summary (summarize results)]
     (cond-> #:lib{:results results
                   :summary summary
                   :run-at run-at
                   :case-count (count (:lib/cases suite))
                   :variant-count (count variants)}
       (seq thresholds) (assoc :lib/thresholds thresholds
                               :lib/passed? (thresholds-met? summary thresholds))))))

;; ---------------------------------------------------------------------------
;; Reporting

(defn- fmt [x]
  (cond
    (nil? x) "-"
    (float? x) (format "%.3f" (double x))
    :else (str x)))

(defn print-summary
  "Print a per-variant comparison table for a `run` report."
  [{:lib/keys [summary]}]
  (let [by-variant (:by-variant summary)
        scorer-ids (->> (vals by-variant) (mapcat (comp keys :scores)) distinct sort)
        headers (concat ["variant" "model" "cases" "errors"]
                        (map name scorer-ids)
                        ["latency(mean ms)" "in-tok" "out-tok"])
        rows (for [[variant-id s] (sort-by key by-variant)]
               (concat [(str variant-id) (fmt (:model s)) (str (:cases s)) (str (:errors s))]
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

    clojure -M:dev -m clj-llm.eval [suite.edn [llm.edn [profile]]]

  Defaults: evals/suite.edn and llm.edn. Exits non-zero when any case
  errored or a threshold was missed (see :lib/thresholds)."
  [& [suite-path config-path profile]]
  (let [config (llm/read-config (or config-path "llm.edn")
                                (if profile {:profile (keyword profile)} {}))
        report (run config (or suite-path "evals/suite.edn"))]
    (print-summary report)
    (let [errors (filter :lib/error (:lib/results report))
          failed? (false? (:lib/passed? report))]
      (doseq [{:lib/keys [case-id variant-id error]} errors]
        (println "ERROR" case-id variant-id "-" error))
      (when failed?
        (println "FAILED: score thresholds not met:" (pr-str (:lib/thresholds report))))
      (System/exit (if (or (seq errors) failed?) 1 0)))))
