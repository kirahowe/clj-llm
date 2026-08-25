(ns clj-llm.core-test
  "Core API tests against a scripted fake adapter — no network involved."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-llm.core :as llm]
            [clj-llm.provider :as provider]))

;; A fake adapter whose provider config carries an atom of canned
;; responses; each generate! call pops one and records the request.
(defmethod provider/-generate! ::scripted
  [{:keys [responses requests]} request _opts]
  (when requests (swap! requests conj request))
  (let [[response] @responses]
    (swap! responses subvec 1)
    (if (fn? response) (response request) response)))

(defn scripted-config [responses & {:keys [requests defaults capabilities]}]
  {:llm/providers {:fake (cond-> {:llm/adapter ::scripted
                                  :responses (atom (vec responses))
                                  :requests requests}
                           capabilities (assoc :llm/capabilities capabilities))}
   :llm/models {:default {:llm/provider :fake :llm/model "fake-1"}}
   :llm/defaults (merge {:llm/model :default} defaults)})

(defn text-response [text]
  {:message {:role :assistant :content text}
   :model "fake-1"
   :usage {:input-tokens 10 :output-tokens 5}
   :finish-reason :stop
   :raw {:fake true}})

(def structured-format
  {:type :json-schema
   :name "answer"
   :schema {:type "object" :properties {:answer {:type "string"}}}})

(deftest zero-shot-generation
  (let [requests (atom [])
        config (scripted-config [(text-response "The sky is blue.")]
                                :requests requests)
        response (llm/generate config "Why is the sky blue?")]
    (is (= "The sky is blue." (:llm/text response)))
    (is (= :stop (:llm/finish-reason response)))
    (is (= :fake (:llm/provider response)))
    (is (= "fake-1" (:llm/model response)))
    (is (= {:input-tokens 10 :output-tokens 5} (:llm/usage response)))
    (testing ":llm/messages contains the full conversation"
      (is (= [{:role :user :content "Why is the sky blue?"}
              {:role :assistant :content "The sky is blue."}]
             (:llm/messages response))))
    (testing "the adapter saw the resolved model and normalized messages"
      (let [request (first @requests)]
        (is (= "fake-1" (:llm/model request)))
        (is (= [{:role :user :content "Why is the sky blue?"}]
               (:llm/messages request)))))
    (testing "the response doubles as an interaction record"
      (is (= :generate (:llm/op response)))
      (is (number? (:llm/latency-ms response)))
      (is (inst? (:llm/started-at response)))
      (is (= "fake-1" (get-in response [:llm/request :llm/model])))
      (is (= [{:role :user :content "Why is the sky blue?"}]
             (get-in response [:llm/request :llm/messages]))))))

(deftest interaction-records
  (testing ":llm/on-interaction from config :llm/defaults receives the full record"
    (let [records (atom [])
          config (scripted-config [(text-response "ok")]
                                  :defaults {:llm/on-interaction
                                             #(swap! records conj %)})
          response (llm/generate config "hi")]
      (is (= [response] @records))))

  (testing "tool :fns are scrubbed from the :llm/request echo"
    (let [config (scripted-config [(text-response "ok")])
          response (llm/generate config "hi"
                                 {:llm/tools [{:name "t" :parameters {}
                                               :fn (fn [_] "x")}]})]
      (is (= [{:name "t" :parameters {}}]
             (get-in response [:llm/request :llm/tools])))))

  (testing "a failing hook never breaks the call"
    (let [config (scripted-config [(text-response "ok")]
                                  :defaults {:llm/on-interaction
                                             (fn [_] (throw (ex-info "boom" {})))})]
      (is (= "ok" (:llm/text (llm/generate config "hi")))))))

(deftest request-shapes-and-defaults
  (testing ":llm/prompt shorthand and opts merging"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:llm/max-tokens 512})]
      (llm/generate config {:llm/prompt "hi"} {:llm/temperature 0.2})
      (let [request (first @requests)]
        (is (= [{:role :user :content "hi"}] (:llm/messages request)))
        (is (= 512 (:llm/max-tokens request)) "config :llm/defaults flow into requests")
        (is (= 0.2 (:llm/temperature request))))))

  (testing "request values override config defaults"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:llm/max-tokens 512})]
      (llm/generate config {:llm/prompt "hi" :llm/max-tokens 64})
      (is (= 64 (:llm/max-tokens (first @requests))))))

  (testing "invalid requests throw"
    (let [config (scripted-config [])]
      (is (thrown-with-msg? Exception #":llm/messages or :llm/prompt"
                            (llm/generate config {})))
      (is (thrown-with-msg? Exception #"prompt string or a request map"
                            (llm/generate config 42)))
      (testing "malformed request maps fail malli validation"
        (let [ex (try (llm/generate config {:llm/messages "not-a-vector"})
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :llm/invalid-request (:type (ex-data ex))))))
      (testing "a non-string, non-map argument fails with :llm/invalid-request"
        (let [ex (try (llm/generate config 42)
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :llm/invalid-request (:type (ex-data ex)))))))))

(deftest multi-turn-threading
  (let [requests (atom [])
        config (scripted-config [(text-response "17")] :requests requests)
        history [{:role :user :content "Pick a number."}
                 {:role :assistant :content "42"}
                 {:role :user :content "Now a prime."}]
        response (llm/generate config {:llm/messages history})]
    (is (= history (:llm/messages (first @requests))))
    (is (= (conj history {:role :assistant :content "17"})
           (:llm/messages response)))))

(deftest prompt-folds-into-messages
  (let [history [{:role :user :content "Pick a number."}
                 {:role :assistant :content "42"}]
        expected (conj history {:role :user :content "next"})]
    (testing ":llm/messages plus :llm/prompt appends the prompt in order"
      (let [requests (atom [])
            config (scripted-config [(text-response "43")] :requests requests)]
        (llm/generate config {:llm/messages history :llm/prompt "next"})
        (is (= expected (:llm/messages (first @requests))))))

    (testing "a prompt string plus opts :llm/messages appends the same way"
      (let [requests (atom [])
            config (scripted-config [(text-response "43")] :requests requests)]
        (llm/generate config "next" {:llm/messages history})
        (is (= expected (:llm/messages (first @requests))))))))

(def weather-tool-call
  {:id "call_1" :name "get-weather" :arguments {:city "Berlin"}})

(defn tool-call-response [tool-calls]
  {:message {:role :assistant :content "" :tool-calls tool-calls}
   :model "fake-1"
   :usage {:input-tokens 7 :output-tokens 3}
   :finish-reason :tool-calls
   :raw {}})

(deftest tool-loop
  (testing "tools with :fn are executed and the conversation continues"
    (let [calls (atom [])
          requests (atom [])
          config (scripted-config [(tool-call-response [weather-tool-call])
                                   (text-response "It's 21°C in Berlin.")]
                                  :requests requests)
          tool {:name "get-weather"
                :description "weather"
                :parameters {:type "object"}
                :fn (fn [args] (swap! calls conj args) {:temperature-c 21})}
          response (llm/generate config "Weather in Berlin?" {:llm/tools [tool]})]
      (is (= [{:city "Berlin"}] @calls) "tool invoked with parsed arguments")
      (is (= "It's 21°C in Berlin." (:llm/text response)))
      (is (nil? (:llm/tool-calls response)))
      (testing "usage is summed across rounds"
        (is (= {:input-tokens 17 :output-tokens 8} (:llm/usage response))))
      (testing "the tool result message was threaded back to the provider"
        (let [tool-message (->> (:llm/messages (second @requests))
                                (filter #(= :tool (:role %)))
                                first)]
          (is (= "call_1" (:tool-call-id tool-message)))
          (is (= "{\"temperature-c\":21}" (:content tool-message)))))
      (testing "the final conversation retains all rounds"
        (is (= [:user :assistant :tool :assistant]
               (map :role (:llm/messages response)))))))

  (testing "tool errors are redacted before they are reported to the model"
    (let [secret "database-password=hunter2"
          requests (atom [])
          config (scripted-config [(tool-call-response [weather-tool-call])
                                   (fn [request]
                                     (text-response
                                      (:content (last (:llm/messages request)))))]
                                  :requests requests)
          tool {:name "get-weather"
                :fn (fn [_] (throw (ex-info secret {})))}
          response (llm/generate config "Weather?" {:llm/tools [tool]})
          tool-message (->> (:llm/messages (second @requests))
                            (filter #(= :tool (:role %)))
                            first)]
      (is (= "Error executing tool get-weather" (:content tool-message)))
      (is (re-find #"Error executing tool" (:llm/text response)))
      (is (not (str/includes? (:content tool-message) secret)))
      (is (not (str/includes? (:llm/text response) secret)))))

  (testing "tools without :fn are returned for manual handling"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])])
          response (llm/generate config "Weather?"
                                 {:llm/tools [{:name "get-weather"
                                               :parameters {:type "object"}}]})]
      (is (= [weather-tool-call] (:llm/tool-calls response)))
      (is (= :tool-calls (:llm/finish-reason response)))))

  (testing "the loop is bounded by :llm/max-tool-rounds"
    (let [n (atom 0)
          config (scripted-config
                  (repeat 10 (tool-call-response [weather-tool-call])))
          tool {:name "get-weather" :fn (fn [_] (swap! n inc) "sunny")}
          response (llm/generate config "Weather?"
                                 {:llm/tools [tool] :llm/max-tool-rounds 2})]
      (is (= 2 @n) "tool ran once per allowed round")
      (is (= [weather-tool-call] (:llm/tool-calls response))
          "unresolved tool calls surface to the caller when the cap is hit"))))

(deftest streaming-callback-passthrough
  (let [config (scripted-config
                [(fn [{:llm/keys [on-chunk]}]
                   (doseq [t ["Once" " upon" " a time"]]
                     (on-chunk {:type :text :text t}))
                   (text-response "Once upon a time"))])
        chunks (atom [])
        response (llm/generate config "story"
                               {:llm/on-chunk #(swap! chunks conj %)})]
    (is (= ["Once" " upon" " a time"] (map :text @chunks)))
    (is (every? #(= :text (:type %)) @chunks))
    (is (= "Once upon a time" (:llm/text response)))))

(deftest structured-responses
  (testing "objects are recursively keywordized"
    (let [records (atom [])
          config (scripted-config
                  [(text-response "{\"outer\":{\"inner\":1}}")]
                  :capabilities {:json-schema-response true}
                  :defaults {:llm/on-interaction #(swap! records conj %)})
          response (llm/generate config "structured"
                                 {:llm/response-format structured-format})]
      (is (= {:outer {:inner 1}} (:llm/structured response)))
      (is (= response (first @records))
          "the hook sees structured data in the finished record")
      (is (= structured-format
             (get-in response [:llm/request :llm/response-format])))
      (is (= "{\"outer\":{\"inner\":1}}" (:llm/text response)))
      (is (= {:fake true} (:llm/raw response)))))

  (testing "all JSON value types, including a present nil for null"
    (doseq [[text expected] [["[1,{\"two\":2}]" [1 {:two 2}]]
                             ["42" 42]
                             ["true" true]
                             ["null" nil]]]
      (let [response (llm/generate
                      (scripted-config [(text-response text)]
                                       :capabilities {:json-schema-response true})
                      "structured"
                      {:llm/response-format structured-format})]
        (is (contains? response :llm/structured) text)
        (is (= expected (:llm/structured response)) text)
        (is (not (contains? response :llm/structured-error)) text))))

  (testing "decoding failures become response data without throwing"
    (doseq [[text reason] [["not json" :stop]
                           ["" :stop]
                           ["{} trailing" :stop]
                           ["{\"answer\":" :length]
                           ["I cannot comply" :refusal]]]
      (let [provider-response (assoc (text-response text) :finish-reason reason)
            response (llm/generate
                      (scripted-config [provider-response]
                                       :capabilities {:json-schema-response true})
                      "structured"
                      {:llm/response-format structured-format})]
        (is (= text (:llm/text response)))
        (is (= reason (:llm/finish-reason response)))
        (is (= :llm/invalid-structured-response
               (get-in response [:llm/structured-error :type])))
        (is (string? (get-in response [:llm/structured-error :message])))
        (is (not (contains? response :llm/structured))))))

  (testing "valid JSON is decoded independently of finish reason"
    (doseq [reason [:length :refusal]]
      (let [response (llm/generate
                      (scripted-config
                       [(assoc (text-response "{\"answer\":\"partial\"}")
                               :finish-reason reason)]
                       :capabilities {:json-schema-response true})
                      "structured"
                      {:llm/response-format structured-format})]
        (is (= {:answer "partial"} (:llm/structured response)))
        (is (= reason (:llm/finish-reason response))))))

  (testing "streaming parses the final accumulated text"
    (let [chunks (atom [])
          config (scripted-config
                  [(fn [{:llm/keys [on-chunk]}]
                     (doseq [text ["{\"answer\":" "\"yes\"}"]]
                       (on-chunk {:type :text :text text}))
                     (text-response "{\"answer\":\"yes\"}"))]
                  :capabilities {:json-schema-response true})
          response (llm/generate config "structured"
                                 {:llm/response-format structured-format
                                  :llm/on-chunk #(swap! chunks conj %)})]
      (is (= "{\"answer\":\"yes\"}" (apply str (map :text @chunks))))
      (is (= {:answer "yes"} (:llm/structured response))))))

(deftest structured-responses-with-tools
  (testing "only the final automatic tool-loop answer is parsed"
    (let [requests (atom [])
          config (scripted-config
                  [(tool-call-response [weather-tool-call])
                   (text-response "{\"answer\":\"sunny\"}")]
                  :requests requests
                  :capabilities {:json-schema-response true})
          response (llm/generate
                    config "weather"
                    {:llm/response-format structured-format
                     :llm/tools [{:name "get-weather" :fn (constantly "sunny")}]})]
      (is (= {:answer "sunny"} (:llm/structured response)))
      (is (= [structured-format structured-format]
             (map :llm/response-format @requests))
          "the response format is sent on every provider round")))

  (testing "pending manual tool calls have no structured result"
    (let [response (llm/generate
                    (scripted-config [(tool-call-response [weather-tool-call])]
                                     :capabilities {:json-schema-response true})
                    "weather"
                    {:llm/response-format structured-format
                     :llm/tools [{:name "get-weather"}]})]
      (is (not (contains? response :llm/structured)))
      (is (not (contains? response :llm/structured-error)))))

  (testing "round-limited pending tool calls have no structured result"
    (let [config (scripted-config
                  (repeat 3 (tool-call-response [weather-tool-call]))
                  :capabilities {:json-schema-response true})
          response (llm/generate
                    config "weather"
                    {:llm/response-format structured-format
                     :llm/max-tool-rounds 1
                     :llm/tools [{:name "get-weather" :fn (constantly "sunny")}]})]
      (is (seq (:llm/tool-calls response)))
      (is (not (contains? response :llm/structured)))
      (is (not (contains? response :llm/structured-error)))))

  (testing ":llm/options does not disable central parsing"
    (let [requests (atom [])
          response (llm/generate
                    (scripted-config [(text-response "{\"answer\":\"yes\"}")]
                                     :requests requests
                                     :capabilities {:json-schema-response true})
                    "structured"
                    {:llm/response-format structured-format
                     :llm/options {:response_format nil}})]
      (is (= {:response_format nil} (:llm/options (first @requests))))
      (is (= {:answer "yes"} (:llm/structured response))))))

(deftest embeddings
  (let [seen (atom nil)]
    (defmethod provider/-embed! ::scripted
      [_ request _opts]
      (reset! seen request)
      {:embeddings (mapv (constantly [0.1 0.2]) (:llm/input request))
       :model (:llm/model request)
       :usage {:input-tokens 2}
       :raw {}})
    (let [config {:llm/providers {:fake {:llm/adapter ::scripted}}
                  :llm/models {:emb {:llm/provider :fake :llm/model "embedder-1"}}
                  :llm/defaults {:llm/embedding-model :emb}}]
      (testing "single string input"
        (let [response (llm/embed config "hello")]
          (is (= ["hello"] (:llm/input @seen)))
          (is (= "embedder-1" (:llm/model @seen)))
          (is (= [0.1 0.2] (:llm/embedding response)))
          (is (= :fake (:llm/provider response)))))
      (testing "seq input has no :llm/embedding, only :llm/embeddings"
        (let [response (llm/embed config ["a" "b"])]
          (is (= [[0.1 0.2] [0.1 0.2]] (:llm/embeddings response)))
          (is (nil? (:llm/embedding response))))))))
