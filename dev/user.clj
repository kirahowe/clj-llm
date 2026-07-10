(ns user
  "Development scratchpad. Start with `bb repl` (or clojure -M:dev)."
  (:require [assay.core :as assay]))

(comment
  ;; Copy resources/assay/config.example.edn to llm.edn (gitignored)
  ;; and set the relevant API keys in your environment.
  (def config (assay/read-config "llm.edn"))

  ;; Zero-shot
  (assay/generate config "In one sentence, why is the sky blue?")

  ;; Pick a model alias per call
  (assay/generate config "Say hi." {:assay/model :fast})

  ;; Multi-turn: conversations are data — thread :assay/messages back in
  (def r1 (assay/generate config "Name a prime number between 100 and 200."))
  (assay/generate config {:assay/messages (conj (:assay/messages r1)
                                                {:role :user :content "Why is it prime?"})})

  ;; Streaming — chunks carry :type; ignore types you don't recognize
  (assay/generate config "Tell a two-sentence story."
                  {:assay/on-chunk (fn [{:keys [type text]}]
                                     (when (= :text type) (print text) (flush)))})

  ;; Tools
  (assay/generate config "What's the weather in Berlin?"
                  {:assay/tools [{:name "get-weather"
                                  :description "Look up current weather for a city"
                                  :parameters {:type "object"
                                               :properties {:city {:type "string"}}
                                               :required ["city"]}
                                  :fn (fn [{:keys [city]}]
                                        {:city city :temperature-c 21 :sky "clear"})}]})

  ;; Embeddings
  (assay/embed config "A short sentence to embed.")

  ;; Evals: copy resources/assay/eval-suite.example.edn to
  ;; evals/suite.edn, then compare variants (also: bb eval)
  (require '[assay.eval :as eval])
  (def report (eval/run config "evals/suite.edn"))
  (eval/print-summary report)

  ;; Model-graded scoring for criteria without mechanical ground truth
  (eval/run config
            #:assay{:cases [#:assay{:id :tone :input "Explain TCP to a five-year-old."}]
                    :variants [#:assay{:id :baseline :model :smart}
                               #:assay{:id :fast :model :fast}]
                    :scorers [(eval/llm-judge
                               {:model :smart
                                :criteria "Age-appropriate, accurate, no jargon."})]})

  ;; System-level evals: :assay/task runs your whole pipeline instead of
  ;; a single LLM call — scorers see whatever it returns
  (eval/run config
            #:assay{:cases [#:assay{:id :faq :input "How do I reset my password?"
                                    :expected "reset link"}]
                    :task (fn [{:keys [config case]}]
                            ;; e.g. retrieval + prompt assembly + generate
                            (assay/generate config (:assay/input case)))
                    :scorers [:includes]}))
