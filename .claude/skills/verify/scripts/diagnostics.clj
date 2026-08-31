;; Verifies the README "Ollama diagnostics" table: the documented exception
;; types and ex-data shapes for an unreachable server, a missing model, and
;; (beyond the table) an unknown provider prefix.
;;
;; Run: clojure -M .claude/skills/verify/scripts/diagnostics.clj
;; Exit 0 = all diagnostics match the documented shapes.
;;
;; NOTE: adapter-level provider keys are unqualified (:base-url, :api-key).
;; A qualified near-miss such as :llm/base-url is silently ignored and the
;; adapter falls back to its default URL — do not "fix" the key below to a
;; qualified one.

(require '[clj-llm.core :as llm])

(def failures (atom []))

(defn check [label ok?]
  (println (str label ": " (if ok? "OK" "FAIL")))
  (when-not ok? (swap! failures conj label)))

;; --- :llm/network-error: unreachable port ---
(let [config #:llm{:providers {:ollama {:llm/adapter :ollama
                                        :base-url "http://localhost:59999"}}
                   :defaults #:llm{:model "ollama/llama3.2"}}
      e (try (llm/generate config "hi") nil (catch Exception e e))]
  (check "network-error"
         (and (some? e)
              (= :llm/network-error (:type (ex-data e)))
              (string? (:url (ex-data e)))
              (instance? Exception (ex-cause e)))))

;; --- :llm/http-error: missing model ---
(let [config #:llm{:providers {:ollama {:llm/adapter :ollama}}
                   :defaults #:llm{:model "ollama/model-that-does-not-exist"}}
      e (try (llm/generate config "hi") nil (catch Exception e e))]
  (check "http-error"
         (and (some? e)
              (= :llm/http-error (:type (ex-data e)))
              (number? (:status (ex-data e)))
              (contains? (ex-data e) :body)
              (contains? (ex-data e) :url))))

;; --- :llm/config-error: unknown provider prefix ---
(let [config #:llm{:providers {:ollama {:llm/adapter :ollama}}
                   :defaults #:llm{:model "nonexistent/llama3.2"}}
      e (try (llm/generate config "hi") nil (catch Exception e e))]
  (check "config-error-unknown-provider"
         (and (some? e)
              (= :llm/config-error (:type (ex-data e)))
              (seq (:known (ex-data e))))))

(println (if (seq @failures)
           (str "DIAGNOSTICS FAILED: " (pr-str @failures))
           "DIAGNOSTICS PASSED"))
(System/exit (if (seq @failures) 1 0))
