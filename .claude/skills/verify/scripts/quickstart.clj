;; Exercises every README "Get a response" / "Common tasks" snippet against
;; live Ollama, exactly as documented. Keep the forms in sync with README.md;
;; when a README snippet changes, change it here too.
;;
;; Run: clojure -M .claude/skills/verify/scripts/quickstart.clj
;; Exit 0 = every step passed; exit 1 = at least one FAIL line above.

(require '[clj-llm.core :as llm]
         '[clj-llm.eval :as eval])

(def failures (atom []))

(defn check [label ok?]
  (println (str label ": " (if ok? "OK" "FAIL")))
  (when-not ok? (swap! failures conj label)))

;; --- README: minimal inline configuration + first call ---
(def config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(def r1 (llm/generate config "Why is the sky blue?"))
(check "quickstart-generate"
       (and (string? (:llm/text r1)) (pos? (count (:llm/text r1)))))

;; --- README: conversation continuation ---
(def first-answer
  (llm/generate config "Name a prime number between 100 and 200."))
(def r2
  (llm/generate config
                "Why is it prime?"
                {:llm/messages (:llm/messages first-answer)}))
(check "conversation-continuation"
       (and (string? (:llm/text r2))
            (> (count (:llm/messages r2)) (count (:llm/messages first-answer)))))

;; --- README: streaming ---
(def chunk-count (atom 0))
(defn print-chunk [{:keys [type text]}]
  (when (= :text type)
    (swap! chunk-count inc)
    (print text)
    (flush)))
(def streaming-response
  (llm/generate config
                "Tell me a short story."
                {:llm/on-chunk print-chunk}))
(println)
(check "streaming"
       (and (> @chunk-count 1) (string? (:llm/text streaming-response))))

;; --- README: structured response ---
(def r4
  (llm/generate
   config
   "Give me three names for a coffee shop."
   {:llm/response-format
    {:type :json-schema
     :name "coffee_shop_names"
     :schema {:type "object"
              :properties {:names {:type "array"
                                   :items {:type "string"}}}
              :required ["names"]}}}))
(check "structured-response"
       (and (map? (:llm/structured r4))
            (sequential? (:names (:llm/structured r4)))))

;; --- README: tools ---
(def tool-called (atom nil))
(def r5
  (llm/generate
   config
   "What is the weather in Berlin?"
   {:llm/tools
    [{:name "get-weather"
      :description "Look up current weather for a city"
      :parameters {:type "object"
                   :properties {:city {:type "string"}}
                   :required ["city"]}
      :fn (fn [{:keys [city]}]
            (reset! tool-called city)
            {:city city :temperature-c 21 :sky "clear"})}]}))
(check "tools" (and (some? @tool-called) (string? (:llm/text r5))))

;; --- README: embeddings ---
(def r6
  (llm/embed config "some text"
             {:llm/model "ollama/nomic-embed-text"}))
(check "embeddings"
       (and (sequential? (:llm/embedding r6))
            (> (count (:llm/embedding r6)) 100)))

;; --- README: evals ---
(def report
  (eval/run
   config
   #:llm{:cases [#:llm{:id :capital
                       :input "What is the capital of France?"
                       :expected "Paris"}]
         :variants [#:llm{:id :default :model "ollama/llama3.2"}]
         :scorers [:includes]}))
(eval/print-summary report)
(check "evals" (map? report))

(println (if (seq @failures)
           (str "QUICKSTART FAILED: " (pr-str @failures))
           "QUICKSTART PASSED"))
(System/exit (if (seq @failures) 1 0))
