(ns user
  "Development scratchpad. Start with `bb repl` (or clojure -M:dev)."
  (:require [clj-llm.core :as llm]))

(comment
  ;; Copy resources/clj-llm/config.example.edn to llm.edn (gitignored)
  ;; and set the relevant API keys in your environment.
  (def config (llm/read-config "llm.edn"))

  ;; Zero-shot
  (llm/generate config "In one sentence, why is the sky blue?")

  ;; Pick a model alias per call
  (llm/generate config "Say hi." {:llm/model :fast})

  ;; Multi-turn: conversations are data — thread :llm/messages back in
  (def r1 (llm/generate config "Name a prime number between 100 and 200."))
  (llm/generate config {:llm/messages (conj (:llm/messages r1)
                                            {:role :user :content "Why is it prime?"})})

  ;; Streaming — chunks carry :type; ignore types you don't recognize
  (llm/generate config "Tell a two-sentence story."
                {:llm/on-chunk (fn [{:keys [type text]}]
                                 (when (= :text type) (print text) (flush)))})

  ;; Tools
  (llm/generate config "What's the weather in Berlin?"
                {:llm/tools [{:name "get-weather"
                              :description "Look up current weather for a city"
                              :parameters {:type "object"
                                           :properties {:city {:type "string"}}
                                           :required ["city"]}
                              :fn (fn [{:keys [city]}]
                                    {:city city :temperature-c 21 :sky "clear"})}]})

  ;; Embeddings
  (llm/embed config "A short sentence to embed.")

  ;; Evals: copy resources/clj-llm/eval-suite.example.edn to
  ;; evals/suite.edn, then compare variants (also: bb eval)
  (require '[clj-llm.eval :as eval])
  (def report (eval/run config "evals/suite.edn"))
  (eval/print-summary report)

  ;; Model-graded scoring for criteria without mechanical ground truth
  (eval/run config
            #:llm{:cases [#:llm{:id :tone :input "Explain TCP to a five-year-old."}]
                  :variants [#:llm{:id :baseline :model :smart}
                             #:llm{:id :fast :model :fast}]
                  :scorers [(eval/llm-judge
                             {:model :smart
                              :criteria "Age-appropriate, accurate, no jargon."})]})

  ;; System-level evals: :llm/task runs your whole pipeline instead of
  ;; a single LLM call — scorers see whatever it returns
  (eval/run config
            #:llm{:cases [#:llm{:id :faq :input "How do I reset my password?"
                                :expected "reset link"}]
                  :task (fn [{:keys [config case]}]
                            ;; e.g. retrieval + prompt assembly + generate
                          (llm/generate config (:llm/input case)))
                  :scorers [:includes]}))
