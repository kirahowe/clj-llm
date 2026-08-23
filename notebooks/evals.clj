;; # Evals

;; Evals let you compare models, prompts, settings, or complete application functions with repeatable cases and scores.

;; There are two parts: responses record the details of each call, and eval suites run cases against variants. You can turn useful production interactions into cases, compare a proposed change, and set score thresholds for CI.

^{:kindly/hide-code true}
(ns evals
  (:require [clj-llm.core :as llm]
            [clj-llm.eval :as eval]
            [book.demo :as demo]
            [scicloj.kindly.v4.kind :as kind]))

^{:kindly/hide-code true}
(def config demo/config)

;; ## Layer 1: every call is already a measurement

;; Each response contains the normalized request, token use, latency, start time, and operation. Tool functions are removed from the stored request; add them again before replaying a tool-using call.

(select-keys (llm/generate config "What is the capital of France?")
             [:llm/request :llm/usage :llm/latency-ms :llm/started-at :llm/op])

;; This response is also an *interaction record*. Set `:llm/on-interaction` in the config defaults to collect records from normal application traffic. This example uses an atom; an application might append them to a log, queue, or database table:

(def interactions (atom []))

(def traffic-config
  (assoc-in config [:llm/defaults :llm/on-interaction]
            (fn [record] (swap! interactions conj record))))

(run! #(llm/generate traffic-config %)
      ["What is the capital of France?"
       "What is 17 * 23? Reply with only the number."])

(count @interactions)

;; The hook runs on the calling thread, so keep it quick. If it throws, the model response is still returned.

;; ## Layer 2: suites score cases against variants

;; A suite has **cases** to run, **variants** to compare, and **scorers** to grade the answers. Pass it as a map or as the path to an EDN file:

(def suite
  #:llm{:cases [#:llm{:id :capital
                      :input "What is the capital of France?"
                      :expected "Paris"}
                #:llm{:id :arithmetic
                      :input "What is 17 * 23? Reply with only the number."
                      :expected "391"}]
        :variants [#:llm{:id :baseline :model :smart}
                   #:llm{:id :cheap :model :fast}]
        :scorers [:includes]})

(def report (eval/run config suite))

;; `print-summary` shows the mean scores, errors, model ids, call counts, latency, and token totals for each variant:

(kind/code (with-out-str (eval/print-summary report)))

;; The report is a map. `:llm/results` has one entry per case and variant, including the response and scores. `:llm/summary` groups the totals by variant. The report also records when it ran and how many cases and variants it used:

(select-keys report [:llm/run-at :llm/case-count :llm/variant-count])

(first (:llm/results report))

;; A **variant** contains request keys such as model, system prompt, temperature, or tools. A case contains either `:llm/input` for a prompt or `:llm/messages` for a conversation. For a simple recorded call, the messages in its stored request can become a new case:

(let [record (first @interactions)]
  (-> (eval/run config
                #:llm{:cases [#:llm{:id :replayed
                                    :messages (:llm/messages (:llm/request record))
                                    :expected "Paris"}]
                      :scorers [:includes]})
      :llm/summary))

;; Production records are useful starting points, but review them before adding them to a suite: remove sensitive data, attach the expected result, and restore tool functions or other application setup that was not stored.

;; ## Scoring

;; Three built-in scorers cover simple checks: `:exact-match` compares trimmed text with `:llm/expected`, `:includes` checks case-insensitive containment, and `:matches` uses a regular expression. A custom scorer receives `{:config _ :case _ :variant _ :response _ :interactions _}` and returns `{:score <0.0-1.0>}` plus any other details you want to keep. Cases may include your own keys for the scorer to read:

(defn terse-enough?
  "Full marks under 60 characters, scaled down to zero at 300."
  [{:keys [response]}]
  (let [n (count (str (:llm/text response)))]
    {:score (max 0.0 (min 1.0 (/ (- 300.0 n) 240.0)))
     :length n}))

(-> (eval/run config (assoc suite :llm/scorers [:includes terse-enough?]))
    :llm/summary)

;; In EDN suite files, scorers can be qualified symbols like `my.app.evals/terse-enough?`, resolved with `requiring-resolve` at run time, so file-based suites reach scorers defined in your codebase.

;; For qualities such as tone, grounding, or helpfulness, `llm-judge` creates a scorer that asks a model to grade each response against written criteria. Prefer a different, stronger model than the one under test. Give each judge an `:id` when a suite uses more than one:

(kind/code
 "(eval/run config
           (assoc suite :llm/scorers
                  [:includes
                   (eval/llm-judge {:model :smart
                                    :criteria \"Factually accurate, and answers the question directly.\"})]))")

;; A judge's reply is parsed into `{:score ... :reasoning ...}`; unusable replies score 0.0 with an `:error`, so a misbehaving judge shows up in the numbers instead of vanishing.

;; The default `judge-prompt` includes the criteria, the case's `:llm/input` and `:llm/expected` when present, and the response's `:llm/text`. For structured responses or cases with additional domain data, pass `:prompt-fn` and build the judge prompt yourself.

;; ## Evals for systems, not just calls

;; By default, each case and variant runs one `generate` call. Set `:llm/task` to a function of `{:keys [config case variant]}` when you want to evaluate a larger unit, such as retrieval, prompt construction, one or more model calls, and post-processing. The function returns a map that your scorers can read:

(defn faq-pipeline
  "Look up a support document, then answer from it."
  [{:keys [config case variant]}]
  (let [doc "Support doc: to reset a password, click 'Forgot password' and follow the emailed reset link."]
    (llm/generate config
                  (merge (eval/variant->request variant)
                         #:llm{:system (str "Answer using this document:\n" doc)
                               :prompt (:llm/input case)}))))

(def pipeline-report
  (eval/run config
            #:llm{:cases [#:llm{:id :password-reset
                                :input "How do I reset my password?"
                                :expected "reset link"}]
                  :variants [#:llm{:id :smart :model :smart}
                             #:llm{:id :fast :model :fast}]
                  :task faq-pipeline
                  :scorers [:includes]}))

(:llm/summary pipeline-report)

;; A task should return a map your scorers understand, usually with at least `:llm/text`. Two details matter:

;; **Use the config passed to the task.** The runner adds an `:llm/on-interaction` collector to that config. Every `generate` or `embed` call made with it is stored under `:llm/interactions` for that result. Scorers receive the same records as `:interactions`, and the summary includes all of their models, calls, latency, and token use:

(map :llm/model (:llm/interactions (first (:llm/results pipeline-report))))

;; **Apply the variant.** `eval/variant->request` removes `:llm/id` and returns the request settings. Merge those settings into each model call unless the task intentionally overrides them. Ignored variant keys do not affect the run.

;; With a custom task, cases do not need `:llm/input` or `:llm/messages`; they can contain the domain data the task needs. If an LLM judge needs that data, include it with `:prompt-fn`:

(kind/code
 "(eval/llm-judge
  {:model :smart
   :criteria \"Every suggested slug must fit the article's actual topic.\"
   :prompt-fn (fn [{:keys [criteria case response]}]
                (str \"Criteria: \" criteria \"\\n\\n\"
                     \"Article body:\\n\" (:article/body case) \"\\n\\n\"
                     \"Suggested slugs: \" (pr-str (:slugs response))))})")

;; In EDN suites, `:llm/task` can be a qualified symbol.

;; The unit being evaluated is the task function. A single model call is only the default task.

;; ## Thresholds: evals as a CI gate

;; `:llm/thresholds` sets a minimum mean score for each scorer. Every variant must meet every configured threshold. The report then includes `:llm/passed?`, and the CLI (`bb eval`, or `clojure -M:dev -m clj-llm.eval`) exits non-zero when a threshold is missed or a case errors. Keep exploratory comparisons separate from CI suites when some variants are expected to score lower.

(let [gated (assoc suite :llm/thresholds {:includes 0.9})]
  (select-keys (eval/run config gated) [:llm/passed? :llm/thresholds]))

;; ## Concurrency and cost

;; `eval/run` accepts `{:concurrency n}` and defaults to 4. The summary totals token use across every recorded call made by each task.

;; ## The workflow, end to end

;; 1. Ship with `:llm/on-interaction` collecting records from day one; it's one line of config.
;; 2. When behavior matters enough to protect, promote records (or write cases by hand) into a suite file; start with `:includes`-style mechanical scorers.
;; 3. When you want to change something (model, prompt, temperature, pipeline), add it as a variant and run the suite. The summary table answers the question.
;; 4. Add an `llm-judge` for the qualities you can't regex.
;; 5. Set `:llm/thresholds` and wire `bb eval` into CI, so quality regressions fail builds the way broken tests do.
