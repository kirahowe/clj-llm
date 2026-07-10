(ns assay.core-test
  "Core API tests against a scripted fake adapter — no network involved."
  (:require [clojure.test :refer [deftest is testing]]
            [assay.core :as llm]
            [assay.provider :as provider]))

;; A fake adapter whose provider config carries an atom of canned
;; responses; each generate! call pops one and records the request.
(defmethod provider/generate! ::scripted
  [{:keys [responses requests]} request _opts]
  (when requests (swap! requests conj request))
  (let [[response] @responses]
    (swap! responses subvec 1)
    (if (fn? response) (response request) response)))

(defn scripted-config [responses & {:keys [requests defaults]}]
  {:assay/providers {:fake {:assay/adapter ::scripted
                            :responses (atom (vec responses))
                            :requests requests}}
   :assay/models {:default {:assay/provider :fake :assay/model "fake-1"}}
   :assay/defaults (merge {:assay/model :default} defaults)})

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
    (is (= "The sky is blue." (:assay/text response)))
    (is (= :stop (:assay/finish-reason response)))
    (is (= :fake (:assay/provider response)))
    (is (= "fake-1" (:assay/model response)))
    (is (= {:input-tokens 10 :output-tokens 5} (:assay/usage response)))
    (testing ":assay/messages contains the full conversation"
      (is (= [{:role :user :content "Why is the sky blue?"}
              {:role :assistant :content "The sky is blue."}]
             (:assay/messages response))))
    (testing "the adapter saw the resolved model and normalized messages"
      (let [request (first @requests)]
        (is (= "fake-1" (:assay/model request)))
        (is (= [{:role :user :content "Why is the sky blue?"}]
               (:assay/messages request)))))
    (testing "the response doubles as an interaction record"
      (is (= :generate (:assay/op response)))
      (is (number? (:assay/latency-ms response)))
      (is (inst? (:assay/started-at response)))
      (is (= "fake-1" (get-in response [:assay/request :assay/model])))
      (is (= [{:role :user :content "Why is the sky blue?"}]
             (get-in response [:assay/request :assay/messages]))))))

(deftest interaction-records
  (testing ":assay/on-interaction from config :assay/defaults receives the full record"
    (let [records (atom [])
          config (scripted-config [(text-response "ok")]
                                  :defaults {:assay/on-interaction
                                             #(swap! records conj %)})
          response (llm/generate config "hi")]
      (is (= [response] @records))))

  (testing "tool :fns are scrubbed from the :assay/request echo"
    (let [config (scripted-config [(text-response "ok")])
          response (llm/generate config "hi"
                                 {:assay/tools [{:name "t" :parameters {}
                                                 :fn (fn [_] "x")}]})]
      (is (= [{:name "t" :parameters {}}]
             (get-in response [:assay/request :assay/tools])))))

  (testing "a failing hook never breaks the call"
    (let [config (scripted-config [(text-response "ok")]
                                  :defaults {:assay/on-interaction
                                             (fn [_] (throw (ex-info "boom" {})))})]
      (is (= "ok" (:assay/text (llm/generate config "hi")))))))

(deftest request-shapes-and-defaults
  (testing ":assay/prompt shorthand and opts merging"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:assay/max-tokens 512})]
      (llm/generate config {:assay/prompt "hi"} {:assay/temperature 0.2})
      (let [request (first @requests)]
        (is (= [{:role :user :content "hi"}] (:assay/messages request)))
        (is (= 512 (:assay/max-tokens request)) "config :assay/defaults flow into requests")
        (is (= 0.2 (:assay/temperature request))))))

  (testing "request values override config defaults"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:assay/max-tokens 512})]
      (llm/generate config {:assay/prompt "hi" :assay/max-tokens 64})
      (is (= 64 (:assay/max-tokens (first @requests))))))

  (testing "invalid requests throw"
    (let [config (scripted-config [])]
      (is (thrown-with-msg? Exception #":assay/messages or :assay/prompt"
                            (llm/generate config {})))
      (is (thrown-with-msg? Exception #"prompt string or a request map"
                            (llm/generate config 42)))
      (testing "malformed request maps fail malli validation"
        (let [ex (try (llm/generate config {:assay/messages "not-a-vector"})
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :assay/invalid-request (:type (ex-data ex))))))
      (testing "a non-string, non-map argument fails with :assay/invalid-request"
        (let [ex (try (llm/generate config 42)
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :assay/invalid-request (:type (ex-data ex)))))))))

(deftest multi-turn-threading
  (let [requests (atom [])
        config (scripted-config [(text-response "17")] :requests requests)
        history [{:role :user :content "Pick a number."}
                 {:role :assistant :content "42"}
                 {:role :user :content "Now a prime."}]
        response (llm/generate config {:assay/messages history})]
    (is (= history (:assay/messages (first @requests))))
    (is (= (conj history {:role :assistant :content "17"})
           (:assay/messages response)))))

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
          response (llm/generate config "Weather in Berlin?" {:assay/tools [tool]})]
      (is (= [{:city "Berlin"}] @calls) "tool invoked with parsed arguments")
      (is (= "It's 21°C in Berlin." (:assay/text response)))
      (is (nil? (:assay/tool-calls response)))
      (testing "usage is summed across rounds"
        (is (= {:input-tokens 17 :output-tokens 8} (:assay/usage response))))
      (testing "the tool result message was threaded back to the provider"
        (let [tool-message (->> (:assay/messages (second @requests))
                                (filter #(= :tool (:role %)))
                                first)]
          (is (= "call_1" (:tool-call-id tool-message)))
          (is (= "{\"temperature-c\":21}" (:content tool-message)))))
      (testing "the final conversation retains all rounds"
        (is (= [:user :assistant :tool :assistant]
               (map :role (:assay/messages response)))))))

  (testing "tool errors are reported back to the model, not thrown"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])
                                   (fn [request]
                                     (text-response
                                      (:content (last (:assay/messages request)))))])
          tool {:name "get-weather"
                :fn (fn [_] (throw (ex-info "socket timeout" {})))}
          response (llm/generate config "Weather?" {:assay/tools [tool]})]
      (is (re-find #"Error executing tool get-weather: socket timeout"
                   (:assay/text response)))))

  (testing "tools without :fn are returned for manual handling"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])])
          response (llm/generate config "Weather?"
                                 {:assay/tools [{:name "get-weather"
                                                 :parameters {:type "object"}}]})]
      (is (= [weather-tool-call] (:assay/tool-calls response)))
      (is (= :tool-calls (:assay/finish-reason response)))))

  (testing "the loop is bounded by :assay/max-tool-rounds"
    (let [n (atom 0)
          config (scripted-config
                  (repeat 10 (tool-call-response [weather-tool-call])))
          tool {:name "get-weather" :fn (fn [_] (swap! n inc) "sunny")}
          response (llm/generate config "Weather?"
                                 {:assay/tools [tool] :assay/max-tool-rounds 2})]
      (is (= 2 @n) "tool ran once per allowed round")
      (is (= [weather-tool-call] (:assay/tool-calls response))
          "unresolved tool calls surface to the caller when the cap is hit"))))

(deftest streaming-callback-passthrough
  (let [config (scripted-config
                [(fn [{:assay/keys [on-chunk]}]
                   (doseq [t ["Once" " upon" " a time"]]
                     (on-chunk {:type :text :text t}))
                   (text-response "Once upon a time"))])
        chunks (atom [])
        response (llm/generate config "story"
                               {:assay/on-chunk #(swap! chunks conj %)})]
    (is (= ["Once" " upon" " a time"] (map :text @chunks)))
    (is (every? #(= :text (:type %)) @chunks))
    (is (= "Once upon a time" (:assay/text response)))))

(deftest embeddings
  (let [seen (atom nil)]
    (defmethod provider/embed! ::scripted
      [_ request _opts]
      (reset! seen request)
      {:embeddings (mapv (constantly [0.1 0.2]) (:assay/input request))
       :model (:assay/model request)
       :usage {:input-tokens 2}
       :raw {}})
    (let [config {:assay/providers {:fake {:assay/adapter ::scripted}}
                  :assay/models {:emb {:assay/provider :fake :assay/model "embedder-1"}}
                  :assay/defaults {:assay/embedding-model :emb}}]
      (testing "single string input"
        (let [response (llm/embed config "hello")]
          (is (= ["hello"] (:assay/input @seen)))
          (is (= "embedder-1" (:assay/model @seen)))
          (is (= [0.1 0.2] (:assay/embedding response)))
          (is (= :fake (:assay/provider response)))))
      (testing "seq input has no :assay/embedding, only :assay/embeddings"
        (let [response (llm/embed config ["a" "b"])]
          (is (= [[0.1 0.2] [0.1 0.2]] (:assay/embeddings response)))
          (is (nil? (:assay/embedding response))))))))
