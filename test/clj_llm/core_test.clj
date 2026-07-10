(ns clj-llm.core-test
  "Core API tests against a scripted fake adapter — no network involved."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.core :as llm]
            [clj-llm.provider :as provider]))

;; A fake adapter whose provider config carries an atom of canned
;; responses; each generate! call pops one and records the request.
(defmethod provider/generate! ::scripted
  [{:keys [responses requests]} request _opts]
  (when requests (swap! requests conj request))
  (let [[response] @responses]
    (swap! responses subvec 1)
    (if (fn? response) (response request) response)))

(defn scripted-config [responses & {:keys [requests defaults]}]
  {:clj-llm/providers {:fake {:clj-llm/adapter ::scripted
                              :responses (atom (vec responses))
                              :requests requests}}
   :clj-llm/models {:default {:clj-llm/provider :fake :clj-llm/model "fake-1"}}
   :clj-llm/defaults (merge {:clj-llm/model :default} defaults)})

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
    (is (= "The sky is blue." (:clj-llm/text response)))
    (is (= :stop (:clj-llm/finish-reason response)))
    (is (= :fake (:clj-llm/provider response)))
    (is (= "fake-1" (:clj-llm/model response)))
    (is (= {:input-tokens 10 :output-tokens 5} (:clj-llm/usage response)))
    (testing ":clj-llm/messages contains the full conversation"
      (is (= [{:role :user :content "Why is the sky blue?"}
              {:role :assistant :content "The sky is blue."}]
             (:clj-llm/messages response))))
    (testing "the adapter saw the resolved model and normalized messages"
      (let [request (first @requests)]
        (is (= "fake-1" (:clj-llm/model request)))
        (is (= [{:role :user :content "Why is the sky blue?"}]
               (:clj-llm/messages request)))))
    (testing "the response doubles as an interaction record"
      (is (= :generate (:clj-llm/op response)))
      (is (number? (:clj-llm/latency-ms response)))
      (is (inst? (:clj-llm/started-at response)))
      (is (= "fake-1" (get-in response [:clj-llm/request :clj-llm/model])))
      (is (= [{:role :user :content "Why is the sky blue?"}]
             (get-in response [:clj-llm/request :clj-llm/messages]))))))

(deftest interaction-records
  (testing ":clj-llm/on-interaction from config :clj-llm/defaults receives the full record"
    (let [records (atom [])
          config (scripted-config [(text-response "ok")]
                                  :defaults {:clj-llm/on-interaction
                                             #(swap! records conj %)})
          response (llm/generate config "hi")]
      (is (= [response] @records))))

  (testing "tool :fns are scrubbed from the :clj-llm/request echo"
    (let [config (scripted-config [(text-response "ok")])
          response (llm/generate config "hi"
                                 {:clj-llm/tools [{:name "t" :parameters {}
                                                   :fn (fn [_] "x")}]})]
      (is (= [{:name "t" :parameters {}}]
             (get-in response [:clj-llm/request :clj-llm/tools])))))

  (testing "a failing hook never breaks the call"
    (let [config (scripted-config [(text-response "ok")]
                                  :defaults {:clj-llm/on-interaction
                                             (fn [_] (throw (ex-info "boom" {})))})]
      (is (= "ok" (:clj-llm/text (llm/generate config "hi")))))))

(deftest request-shapes-and-defaults
  (testing ":clj-llm/prompt shorthand and opts merging"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:clj-llm/max-tokens 512})]
      (llm/generate config {:clj-llm/prompt "hi"} {:clj-llm/temperature 0.2})
      (let [request (first @requests)]
        (is (= [{:role :user :content "hi"}] (:clj-llm/messages request)))
        (is (= 512 (:clj-llm/max-tokens request)) "config :clj-llm/defaults flow into requests")
        (is (= 0.2 (:clj-llm/temperature request))))))

  (testing "request values override config defaults"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:clj-llm/max-tokens 512})]
      (llm/generate config {:clj-llm/prompt "hi" :clj-llm/max-tokens 64})
      (is (= 64 (:clj-llm/max-tokens (first @requests))))))

  (testing "invalid requests throw"
    (let [config (scripted-config [])]
      (is (thrown-with-msg? Exception #":clj-llm/messages or :clj-llm/prompt"
                            (llm/generate config {})))
      (is (thrown-with-msg? Exception #"prompt string or a request map"
                            (llm/generate config 42)))
      (testing "malformed request maps fail malli validation"
        (let [ex (try (llm/generate config {:clj-llm/messages "not-a-vector"})
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :clj-llm/invalid-request (:type (ex-data ex))))))
      (testing "a non-string, non-map argument fails with :clj-llm/invalid-request"
        (let [ex (try (llm/generate config 42)
                      nil
                      (catch Exception e e))]
          (is (some? ex))
          (is (= :clj-llm/invalid-request (:type (ex-data ex)))))))))

(deftest multi-turn-threading
  (let [requests (atom [])
        config (scripted-config [(text-response "17")] :requests requests)
        history [{:role :user :content "Pick a number."}
                 {:role :assistant :content "42"}
                 {:role :user :content "Now a prime."}]
        response (llm/generate config {:clj-llm/messages history})]
    (is (= history (:clj-llm/messages (first @requests))))
    (is (= (conj history {:role :assistant :content "17"})
           (:clj-llm/messages response)))))

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
          response (llm/generate config "Weather in Berlin?" {:clj-llm/tools [tool]})]
      (is (= [{:city "Berlin"}] @calls) "tool invoked with parsed arguments")
      (is (= "It's 21°C in Berlin." (:clj-llm/text response)))
      (is (nil? (:clj-llm/tool-calls response)))
      (testing "usage is summed across rounds"
        (is (= {:input-tokens 17 :output-tokens 8} (:clj-llm/usage response))))
      (testing "the tool result message was threaded back to the provider"
        (let [tool-message (->> (:clj-llm/messages (second @requests))
                                (filter #(= :tool (:role %)))
                                first)]
          (is (= "call_1" (:tool-call-id tool-message)))
          (is (= "{\"temperature-c\":21}" (:content tool-message)))))
      (testing "the final conversation retains all rounds"
        (is (= [:user :assistant :tool :assistant]
               (map :role (:clj-llm/messages response)))))))

  (testing "tool errors are reported back to the model, not thrown"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])
                                   (fn [request]
                                     (text-response
                                      (:content (last (:clj-llm/messages request)))))])
          tool {:name "get-weather"
                :fn (fn [_] (throw (ex-info "socket timeout" {})))}
          response (llm/generate config "Weather?" {:clj-llm/tools [tool]})]
      (is (re-find #"Error executing tool get-weather: socket timeout"
                   (:clj-llm/text response)))))

  (testing "tools without :fn are returned for manual handling"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])])
          response (llm/generate config "Weather?"
                                 {:clj-llm/tools [{:name "get-weather"
                                                   :parameters {:type "object"}}]})]
      (is (= [weather-tool-call] (:clj-llm/tool-calls response)))
      (is (= :tool-calls (:clj-llm/finish-reason response)))))

  (testing "the loop is bounded by :clj-llm/max-tool-rounds"
    (let [n (atom 0)
          config (scripted-config
                  (repeat 10 (tool-call-response [weather-tool-call])))
          tool {:name "get-weather" :fn (fn [_] (swap! n inc) "sunny")}
          response (llm/generate config "Weather?"
                                 {:clj-llm/tools [tool] :clj-llm/max-tool-rounds 2})]
      (is (= 2 @n) "tool ran once per allowed round")
      (is (= [weather-tool-call] (:clj-llm/tool-calls response))
          "unresolved tool calls surface to the caller when the cap is hit"))))

(deftest streaming-callback-passthrough
  (let [config (scripted-config
                [(fn [{:clj-llm/keys [on-chunk]}]
                   (doseq [t ["Once" " upon" " a time"]]
                     (on-chunk {:type :text :text t}))
                   (text-response "Once upon a time"))])
        chunks (atom [])
        response (llm/generate config "story"
                               {:clj-llm/on-chunk #(swap! chunks conj %)})]
    (is (= ["Once" " upon" " a time"] (map :text @chunks)))
    (is (every? #(= :text (:type %)) @chunks))
    (is (= "Once upon a time" (:clj-llm/text response)))))

(deftest embeddings
  (let [seen (atom nil)]
    (defmethod provider/embed! ::scripted
      [_ request _opts]
      (reset! seen request)
      {:embeddings (mapv (constantly [0.1 0.2]) (:clj-llm/input request))
       :model (:clj-llm/model request)
       :usage {:input-tokens 2}
       :raw {}})
    (let [config {:clj-llm/providers {:fake {:clj-llm/adapter ::scripted}}
                  :clj-llm/models {:emb {:clj-llm/provider :fake :clj-llm/model "embedder-1"}}
                  :clj-llm/defaults {:clj-llm/embedding-model :emb}}]
      (testing "single string input"
        (let [response (llm/embed config "hello")]
          (is (= ["hello"] (:clj-llm/input @seen)))
          (is (= "embedder-1" (:clj-llm/model @seen)))
          (is (= [0.1 0.2] (:clj-llm/embedding response)))
          (is (= :fake (:clj-llm/provider response)))))
      (testing "seq input has no :clj-llm/embedding, only :clj-llm/embeddings"
        (let [response (llm/embed config ["a" "b"])]
          (is (= [[0.1 0.2] [0.1 0.2]] (:clj-llm/embeddings response)))
          (is (nil? (:clj-llm/embedding response))))))))
