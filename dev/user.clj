(ns user
  "Development scratchpad. Start with `bb repl` (or clojure -M:dev)."
  (:require [kirahowe.clj-llm :as llm]))

(comment
  ;; Copy resources/clj-llm/config.example.edn to llm.edn (gitignored)
  ;; and set the relevant API keys in your environment.
  (def config (llm/read-config "llm.edn"))

  ;; Zero-shot
  (llm/generate config "In one sentence, why is the sky blue?")

  ;; Pick a model alias per call
  (llm/generate config "Say hi." {:model :fast})

  ;; Multi-turn: conversations are data — thread :messages back in
  (def r1 (llm/generate config "Name a prime number between 100 and 200."))
  (llm/generate config {:messages (conj (:messages r1)
                                        {:role :user :content "Why is it prime?"})})

  ;; Streaming
  (llm/generate config "Tell a two-sentence story."
                {:on-chunk (fn [{:keys [text]}] (print text) (flush))})

  ;; Tools
  (llm/generate config "What's the weather in Berlin?"
                {:tools [{:name "get-weather"
                          :description "Look up current weather for a city"
                          :parameters {:type "object"
                                       :properties {:city {:type "string"}}
                                       :required ["city"]}
                          :fn (fn [{:keys [city]}]
                                {:city city :temperature-c 21 :sky "clear"})}]})

  ;; Embeddings
  (llm/embed config "A short sentence to embed."))
