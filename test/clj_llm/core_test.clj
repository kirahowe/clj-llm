(ns clj-llm.core-test
  "Core API tests against a scripted fake adapter — no network involved."
  (:require [clojure.test :refer [deftest is testing]]
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

(defn scripted-config [responses & {:keys [requests defaults]}]
  {:lib/providers {:fake {:lib/adapter ::scripted
                          :responses (atom (vec responses))
                          :requests requests}}
   :lib/models {:default {:lib/provider :fake :lib/model "fake-1"}}
   :lib/defaults (merge {:lib/model :default} defaults)})

(defn text-response [text]
  {:message {:role :assistant :content text}
   :model "fake-1"
   :usage {:input-tokens 10 :output-tokens 5}
   :finish-reason :stop
   :raw {:fake true}})

(deftest zero-shot-generation
  (let [requests (atom [])
        config (scripted-config [(text-response "The sky is blue.")]
                                :requests requests)
        response (llm/generate config "Why is the sky blue?")]
    (is (= "The sky is blue." (:lib/text response)))
    (is (= :stop (:lib/finish-reason response)))
    (is (= :fake (:lib/provider response)))
    (is (= "fake-1" (:lib/model response)))
    (is (= {:input-tokens 10 :output-tokens 5} (:lib/usage response)))
    (testing ":lib/messages contains the full conversation"
      (is (= [{:role :user :content "Why is the sky blue?"}
              {:role :assistant :content "The sky is blue."}]
             (:lib/messages response))))
    (testing "the adapter saw the resolved model and normalized messages"
      (let [request (first @requests)]
        (is (= "fake-1" (:lib/model request)))
        (is (= [{:role :user :content "Why is the sky blue?"}]
               (:lib/messages request)))))
    (testing "the response doubles as an interaction record"
      (is (= :generate (:lib/op response)))
      (is (number? (:lib/latency-ms response)))
      (is (inst? (:lib/started-at response)))
      (is (= "fake-1" (get-in response [:lib/request :lib/model])))
      (is (= [{:role :user :content "Why is the sky blue?"}]
             (get-in response [:lib/request :lib/messages]))))))

(deftest interaction-records
  (testing ":lib/on-interaction from config :lib/defaults receives the full record"
    (let [records (atom [])
          config (scripted-config [(text-response "ok")]
                                  :defaults {:lib/on-interaction
                                             #(swap! records conj %)})
          response (llm/generate config "hi")]
      (is (= [response] @records))))

  (testing "tool :fns are scrubbed from the :lib/request echo"
    (let [config (scripted-config [(text-response "ok")])
          response (llm/generate config "hi"
                                 {:lib/tools [{:name "t" :parameters {}
                                               :fn (fn [_] "x")}]})]
      (is (= [{:name "t" :parameters {}}]
             (get-in response [:lib/request :lib/tools])))))

  (testing "a failing hook never breaks the call"
    (let [config (scripted-config [(text-response "ok")]
                                  :defaults {:lib/on-interaction
                                             (fn [_] (throw (ex-info "boom" {})))})]
      (is (= "ok" (:lib/text (llm/generate config "hi")))))))

(deftest request-shapes-and-defaults
  (testing ":lib/prompt shorthand and opts merging"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:lib/max-tokens 512})]
      (llm/generate config {:lib/prompt "hi"} {:lib/temperature 0.2})
      (let [request (first @requests)]
        (is (= [{:role :user :content "hi"}] (:lib/messages request)))
        (is (= 512 (:lib/max-tokens request)) "config :lib/defaults flow into requests")
        (is (= 0.2 (:lib/temperature request))))))

  (testing "request values override config defaults"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:lib/max-tokens 512})]
      (llm/generate config {:lib/prompt "hi" :lib/max-tokens 64})
      (is (= 64 (:lib/max-tokens (first @requests))))))

  (testing "invalid requests throw"
    (let [config (scripted-config [])]
      (is (thrown-with-msg? Exception #":lib/messages or :lib/prompt"
                            (llm/generate config {})))
      (is (thrown-with-msg? Exception #"prompt string or a request map"
                            (llm/generate config 42)))
      (testing "malformed request maps fail malli validation"
        (let [ex (try (llm/generate config {:lib/messages "not-a-vector"})
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :lib/invalid-request (:type (ex-data ex))))))
      (testing "a non-string, non-map argument fails with :lib/invalid-request"
        (let [ex (try (llm/generate config 42)
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :lib/invalid-request (:type (ex-data ex)))))))))

(deftest multi-turn-threading
  (let [requests (atom [])
        config (scripted-config [(text-response "17")] :requests requests)
        history [{:role :user :content "Pick a number."}
                 {:role :assistant :content "42"}
                 {:role :user :content "Now a prime."}]
        response (llm/generate config {:lib/messages history})]
    (is (= history (:lib/messages (first @requests))))
    (is (= (conj history {:role :assistant :content "17"})
           (:lib/messages response)))))

(deftest prompt-folds-into-messages
  (let [history [{:role :user :content "Pick a number."}
                 {:role :assistant :content "42"}]
        expected (conj history {:role :user :content "next"})]
    (testing ":lib/messages plus :lib/prompt appends the prompt in order"
      (let [requests (atom [])
            config (scripted-config [(text-response "43")] :requests requests)]
        (llm/generate config {:lib/messages history :lib/prompt "next"})
        (is (= expected (:lib/messages (first @requests))))))

    (testing "a prompt string plus opts :lib/messages appends the same way"
      (let [requests (atom [])
            config (scripted-config [(text-response "43")] :requests requests)]
        (llm/generate config "next" {:lib/messages history})
        (is (= expected (:lib/messages (first @requests))))))))

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
          response (llm/generate config "Weather in Berlin?" {:lib/tools [tool]})]
      (is (= [{:city "Berlin"}] @calls) "tool invoked with parsed arguments")
      (is (= "It's 21°C in Berlin." (:lib/text response)))
      (is (nil? (:lib/tool-calls response)))
      (testing "usage is summed across rounds"
        (is (= {:input-tokens 17 :output-tokens 8} (:lib/usage response))))
      (testing "the tool result message was threaded back to the provider"
        (let [tool-message (->> (:lib/messages (second @requests))
                                (filter #(= :tool (:role %)))
                                first)]
          (is (= "call_1" (:tool-call-id tool-message)))
          (is (= "{\"temperature-c\":21}" (:content tool-message)))))
      (testing "the final conversation retains all rounds"
        (is (= [:user :assistant :tool :assistant]
               (map :role (:lib/messages response)))))))

  (testing "tool errors are reported back to the model, not thrown"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])
                                   (fn [request]
                                     (text-response
                                      (:content (last (:lib/messages request)))))])
          tool {:name "get-weather"
                :fn (fn [_] (throw (ex-info "socket timeout" {})))}
          response (llm/generate config "Weather?" {:lib/tools [tool]})]
      (is (re-find #"Error executing tool get-weather: socket timeout"
                   (:lib/text response)))))

  (testing "tools without :fn are returned for manual handling"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])])
          response (llm/generate config "Weather?"
                                 {:lib/tools [{:name "get-weather"
                                               :parameters {:type "object"}}]})]
      (is (= [weather-tool-call] (:lib/tool-calls response)))
      (is (= :tool-calls (:lib/finish-reason response)))))

  (testing "the loop is bounded by :lib/max-tool-rounds"
    (let [n (atom 0)
          config (scripted-config
                  (repeat 10 (tool-call-response [weather-tool-call])))
          tool {:name "get-weather" :fn (fn [_] (swap! n inc) "sunny")}
          response (llm/generate config "Weather?"
                                 {:lib/tools [tool] :lib/max-tool-rounds 2})]
      (is (= 2 @n) "tool ran once per allowed round")
      (is (= [weather-tool-call] (:lib/tool-calls response))
          "unresolved tool calls surface to the caller when the cap is hit"))))

(deftest streaming-callback-passthrough
  (let [config (scripted-config
                [(fn [{:lib/keys [on-chunk]}]
                   (doseq [t ["Once" " upon" " a time"]]
                     (on-chunk {:type :text :text t}))
                   (text-response "Once upon a time"))])
        chunks (atom [])
        response (llm/generate config "story"
                               {:lib/on-chunk #(swap! chunks conj %)})]
    (is (= ["Once" " upon" " a time"] (map :text @chunks)))
    (is (every? #(= :text (:type %)) @chunks))
    (is (= "Once upon a time" (:lib/text response)))))

(deftest embeddings
  (let [seen (atom nil)]
    (defmethod provider/-embed! ::scripted
      [_ request _opts]
      (reset! seen request)
      {:embeddings (mapv (constantly [0.1 0.2]) (:lib/input request))
       :model (:lib/model request)
       :usage {:input-tokens 2}
       :raw {}})
    (let [config {:lib/providers {:fake {:lib/adapter ::scripted}}
                  :lib/models {:emb {:lib/provider :fake :lib/model "embedder-1"}}
                  :lib/defaults {:lib/embedding-model :emb}}]
      (testing "single string input"
        (let [response (llm/embed config "hello")]
          (is (= ["hello"] (:lib/input @seen)))
          (is (= "embedder-1" (:lib/model @seen)))
          (is (= [0.1 0.2] (:lib/embedding response)))
          (is (= :fake (:lib/provider response)))))
      (testing "seq input has no :lib/embedding, only :lib/embeddings"
        (let [response (llm/embed config ["a" "b"])]
          (is (= [[0.1 0.2] [0.1 0.2]] (:lib/embeddings response)))
          (is (nil? (:lib/embedding response))))))))
