;; Exercises the :openai adapter live without an API key, using Ollama's
;; OpenAI-compatible endpoint at /v1 — the same path OpenRouter, Groq, vLLM,
;; and LM Studio users take. Covers generation, streaming, and structured
;; output through the Chat Completions protocol.
;;
;; Run: clojure -M .claude/skills/verify/scripts/openai_compat.clj

(require '[clj-llm.core :as llm])

(def failures (atom []))

(defn check [label ok?]
  (println (str label ": " (if ok? "OK" "FAIL")))
  (when-not ok? (swap! failures conj label)))

(def config
  #:llm{:providers {:local {:llm/adapter :openai
                            :base-url "http://localhost:11434/v1"}}
        :defaults #:llm{:model "local/llama3.2"}})

(let [r (llm/generate config "Say hello in three words.")]
  (check "openai-compat-generate" (boolean (seq (:llm/text r)))))

(let [chunks (atom 0)
      r (llm/generate config "Count to five."
                      {:llm/on-chunk (fn [{:keys [type]}]
                                       (when (= :text type)
                                         (swap! chunks inc)))})]
  (check "openai-compat-streaming"
         (and (seq (:llm/text r)) (> @chunks 1))))

(let [r (llm/generate config "Give me two colors."
                      {:llm/response-format
                       {:type :json-schema
                        :name "colors"
                        :schema {:type "object"
                                 :properties {:colors {:type "array"
                                                       :items {:type "string"}}}
                                 :required ["colors"]}}})]
  (check "openai-compat-structured"
         (sequential? (:colors (:llm/structured r)))))

(println (if (seq @failures)
           (str "OPENAI-COMPAT FAILED: " (pr-str @failures))
           "OPENAI-COMPAT PASSED"))
(System/exit (if (seq @failures) 1 0))
