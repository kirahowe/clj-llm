;; # Evals

;; This is the chapter the library is built around. You can't iterate toward better models, prompts, parameters, or pipelines without measuring, so measurement should not be an optional add-on with its own framework to adopt. It should fall out of the calls you are already making.

;; clj-llm's eval system is two layers. The first layer is passive: every response is already a measurement. The second is active: suites run cases against variants and score the results. Between them sits the workflow this chapter builds up to: real traffic becomes cases, cases become scored comparisons, and comparisons gate changes.

(ns evals
  (:require [clj-llm.core :as llm]
            [clj-llm.eval :as eval]
            [book.demo :as demo]
            [scicloj.kindly.v4.kind :as kind]))

(def config demo/config)

;; ## Layer 1: every call is already a measurement

;; Look again at any response. Alongside the answer it carries the fully resolved request (replayable; tool functions are scrubbed), token usage, latency, a timestamp and the operation:

(select-keys (llm/generate config "What is the capital of France?")
             [:lib/request :lib/usage :lib/latency-ms :lib/started-at :lib/op])

;; A response with these keys is called an *interaction record*. To collect records from live traffic, set `:lib/on-interaction` (a function of one record) once in your config's defaults. Here we collect into an atom; in production this is typically an append to a log, a queue, or a table:

(def interactions (atom []))

(def traffic-config
  (assoc-in config [:lib/defaults :lib/on-interaction]
            (fn [record] (swap! interactions conj record))))

(run! #(llm/generate traffic-config %)
      ["What is the capital of France?"
       "What is 17 * 23? Reply with only the number."])

(count @interactions)

;; The hook is fire-and-forget: it must not block for long (it runs on the calling thread), and the library swallows exceptions inside it rather than failing the user's request.

;; ## Layer 2: suites score cases against variants

;; A suite is plain data: **cases** say what to test, **variants** say what to compare, **scorers** say what good looks like. Inline as a map, or in an EDN file read with the same aero reader as config:

(def suite
  #:lib{:cases [#:lib{:id :capital
                      :input "What is the capital of France?"
                      :expected "Paris"}
                #:lib{:id :arithmetic
                      :input "What is 17 * 23? Reply with only the number."
                      :expected "391"}]
        :variants [#:lib{:id :baseline :model :smart}
                   #:lib{:id :cheap :model :fast}]
        :scorers [:includes]})

(def report (eval/run config suite))

;; `print-summary` renders the per-variant comparison (score means, error counts, the model that actually served each variant, latency and token totals):

(kind/code (with-out-str (eval/print-summary report)))

;; The report is data all the way down. `:lib/results` holds one entry per case×variant with the full response and its scores; `:lib/summary` aggregates per variant; and the report records its own provenance (when it ran and how big it was), so a stored report is meaningfully comparable with next month's:

(select-keys report [:lib/run-at :lib/case-count :lib/variant-count])

(first (:lib/results report))

;; A **variant** is just a bundle of request keys (model, system prompt, temperature, tools, anything `generate` accepts), so "compare two models", "compare two prompts" and "compare with/without tools" are all the same operation. Cases accept either `:lib/input` (a prompt) or `:lib/messages` (a full conversation), which is exactly what makes collected records replayable:

(let [record (first @interactions)]
  (-> (eval/run config
                #:lib{:cases [#:lib{:id :replayed
                                    :messages (:lib/messages (:lib/request record))
                                    :expected "Paris"}]
                      :scorers [:includes]})
      :lib/summary))

;; That loop (traffic in, records out, records back in as cases) is how suites are meant to grow. You don't invent test cases; you harvest them.

;; ## Scoring

;; Three built-ins cover mechanical ground truth: `:exact-match` (trimmed equality with `:lib/expected`), `:includes` (case-insensitive containment), and `:matches` (regex). A custom scorer is any function of the context map `{:config _ :case _ :variant _ :response _}` returning `{:score <0.0-1.0>}` plus anything else worth keeping. Unqualified keys on a case are yours, so scorers can read custom fields:

(defn terse-enough?
  "Full marks under 60 characters, scaled down to zero at 300."
  [{:keys [response]}]
  (let [n (count (str (:lib/text response)))]
    {:score (max 0.0 (min 1.0 (/ (- 300.0 n) 240.0)))
     :length n}))

(-> (eval/run config (assoc suite :lib/scorers [:includes terse-enough?]))
    :lib/summary)

;; In EDN suite files, scorers can be qualified symbols like `my.app.evals/terse-enough?`, resolved with `requiring-resolve` at run time, so file-based suites reach scorers defined in your codebase.

;; For qualities with no mechanical ground truth (tone, groundedness, helpfulness), model-graded scoring is one call away. `llm-judge` returns a scorer that asks a model to grade each response against a plain-language rubric (use a different, ideally stronger, model than the one under test; and give each judge its own `:id` if a suite uses several):

(kind/code
 "(eval/run config
           (assoc suite :lib/scorers
                  [:includes
                   (eval/llm-judge {:model :smart
                                    :criteria \"Factually accurate, and answers the question directly.\"})]))")

;; A judge's reply is parsed into `{:score ... :reasoning ...}`; unusable replies score 0.0 with an `:error`, so a misbehaving judge shows up in the numbers instead of vanishing.

;; ## Evals for systems, not just calls

;; By default, a case×variant runs a single `generate` call. But the question you actually care about is usually one level up: is the whole *pipeline* good, retrieval plus prompt assembly plus the model plus post-processing? Point `:lib/task` at any function of `{:keys [config case variant]}` that returns a response-shaped map, and the same cases, scorers, thresholds and reports apply to the whole system:

(defn faq-pipeline
  "A toy 'system': look up a canned document, then generate with it in the prompt. A real one would do retrieval, ranking, templating..."
  [{:keys [config case]}]
  (let [doc "Support doc: to reset a password, click 'Forgot password' and follow the emailed reset link."]
    (llm/generate config
                  #:lib{:system (str "Answer using this document:\n" doc)
                        :prompt (:lib/input case)})))

(-> (eval/run config
              #:lib{:cases [#:lib{:id :password-reset
                                  :input "How do I reset my password?"
                                  :expected "reset link"}]
                    :task faq-pipeline
                    :scorers [:includes]})
    :lib/summary)

;; The task contract is thin on purpose: return at least `:lib/text` (or whatever your scorers read). Return real `generate` responses, as `faq-pipeline` does, and latency and token summaries stay accurate for free. Variants still work with a custom task: the variant map is passed to your task, so variants can select pipeline configurations, not just request keys. In EDN suites, `:lib/task` can be a qualified symbol.

;; This is the sense in which evals here are "tests for LLM calls at the system level": the unit under test is whatever function you hand the harness, and a raw LLM call is merely the default.

;; ## Thresholds: evals as a CI gate

;; A report someone has to remember to read eventually stops being read. `:lib/thresholds` sets a minimum mean score per scorer — one that **every** variant must clear; the report then carries `:lib/passed?`, and the CLI (`bb eval`, or `clojure -M:dev -m clj-llm.eval`) exits non-zero when a threshold is missed or any case errors, so a suite drops into CI like any other test suite. That "every variant" rule means gating suites and exploratory comparisons want to be separate files: a comparison where the cheap model is allowed to lose shouldn't fail your build.

(let [gated (assoc suite :lib/thresholds {:includes 0.9})]
  (select-keys (eval/run config gated) [:lib/passed? :lib/thresholds]))

;; ## Concurrency and cost

;; `eval/run` takes `{:concurrency n}` (default 4) and runs cases in a fixed thread pool. Every result row carries its full response, including usage, so the report also tells you what the eval itself cost in tokens, and the summary totals make cost comparisons between models concrete.

;; ## The workflow, end to end

;; 1. Ship with `:lib/on-interaction` collecting records from day one; it's one line of config.
;; 2. When behavior matters enough to protect, promote records (or write cases by hand) into a suite file; start with `:includes`-style mechanical scorers.
;; 3. When you want to change something (model, prompt, temperature, pipeline), add it as a variant and run the suite. The summary table answers the question.
;; 4. Add an `llm-judge` for the qualities you can't regex.
;; 5. Set `:lib/thresholds` and wire `bb eval` into CI, so quality regressions fail builds the way broken tests do.

;; Planned extensions, all additive (see the roadmap chapter): response caching for cheap re-runs, EDN-expressible judges, per-case weights, and report-diffing helpers.
