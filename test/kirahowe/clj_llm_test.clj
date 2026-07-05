(ns kirahowe.clj-llm-test
  "Core API tests against a scripted fake adapter — no network involved."
  (:require [clojure.test :refer [deftest is testing]]
            [kirahowe.clj-llm :as llm]
            [kirahowe.clj-llm.provider :as provider]))

;; A fake adapter whose provider config carries an atom of canned
;; responses; each generate! call pops one and records the request.
(defmethod provider/generate! ::scripted
  [{:keys [responses requests]} request]
  (when requests (swap! requests conj request))
  (let [[response] @responses]
    (swap! responses subvec 1)
    (if (fn? response) (response request) response)))

(defn scripted-config [responses & {:keys [requests defaults]}]
  {:providers {:fake {:adapter ::scripted
                      :responses (atom (vec responses))
                      :requests requests}}
   :models {:default {:provider :fake :model "fake-1"}}
   :defaults (merge {:model :default} defaults)})

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
    (is (= "The sky is blue." (:text response)))
    (is (= :stop (:finish-reason response)))
    (is (= :fake (:provider response)))
    (is (= "fake-1" (:model response)))
    (is (= {:input-tokens 10 :output-tokens 5} (:usage response)))
    (testing ":messages contains the full conversation"
      (is (= [{:role :user :content "Why is the sky blue?"}
              {:role :assistant :content "The sky is blue."}]
             (:messages response))))
    (testing "the adapter saw the resolved model and normalized messages"
      (let [request (first @requests)]
        (is (= "fake-1" (:model request)))
        (is (= [{:role :user :content "Why is the sky blue?"}]
               (:messages request)))))
    (testing "the response doubles as an interaction record"
      (is (= :generate (:op response)))
      (is (number? (:latency-ms response)))
      (is (inst? (:started-at response)))
      (is (= "fake-1" (get-in response [:request :model])))
      (is (= [{:role :user :content "Why is the sky blue?"}]
             (get-in response [:request :messages]))))))

(deftest interaction-records
  (testing ":on-interaction from config :defaults receives the full record"
    (let [records (atom [])
          config (scripted-config [(text-response "ok")]
                                  :defaults {:on-interaction
                                             #(swap! records conj %)})
          response (llm/generate config "hi")]
      (is (= [response] @records))))

  (testing "tool :fns are scrubbed from the :request echo"
    (let [config (scripted-config [(text-response "ok")])
          response (llm/generate config "hi"
                                 {:tools [{:name "t" :parameters {}
                                           :fn (fn [_] "x")}]})]
      (is (= [{:name "t" :parameters {}}]
             (get-in response [:request :tools])))))

  (testing "a failing hook never breaks the call"
    (let [config (scripted-config [(text-response "ok")]
                                  :defaults {:on-interaction
                                             (fn [_] (throw (ex-info "boom" {})))})]
      (is (= "ok" (:text (llm/generate config "hi")))))))

(deftest request-shapes-and-defaults
  (testing ":prompt shorthand and opts merging"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:max-tokens 512})]
      (llm/generate config {:prompt "hi"} {:temperature 0.2})
      (let [request (first @requests)]
        (is (= [{:role :user :content "hi"}] (:messages request)))
        (is (= 512 (:max-tokens request)) "config :defaults flow into requests")
        (is (= 0.2 (:temperature request))))))

  (testing "request values override config defaults"
    (let [requests (atom [])
          config (scripted-config [(text-response "ok")]
                                  :requests requests
                                  :defaults {:max-tokens 512})]
      (llm/generate config {:prompt "hi" :max-tokens 64})
      (is (= 64 (:max-tokens (first @requests))))))

  (testing "invalid requests throw"
    (let [config (scripted-config [])]
      (is (thrown-with-msg? Exception #":messages or :prompt"
                            (llm/generate config {})))
      (is (thrown-with-msg? Exception #"prompt string or a request map"
                            (llm/generate config 42))))))

(deftest multi-turn-threading
  (let [requests (atom [])
        config (scripted-config [(text-response "17")] :requests requests)
        history [{:role :user :content "Pick a number."}
                 {:role :assistant :content "42"}
                 {:role :user :content "Now a prime."}]
        response (llm/generate config {:messages history})]
    (is (= history (:messages (first @requests))))
    (is (= (conj history {:role :assistant :content "17"})
           (:messages response)))))

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
          response (llm/generate config "Weather in Berlin?" {:tools [tool]})]
      (is (= [{:city "Berlin"}] @calls) "tool invoked with parsed arguments")
      (is (= "It's 21°C in Berlin." (:text response)))
      (is (nil? (:tool-calls response)))
      (testing "usage is summed across rounds"
        (is (= {:input-tokens 17 :output-tokens 8} (:usage response))))
      (testing "the tool result message was threaded back to the provider"
        (let [tool-message (->> (:messages (second @requests))
                                (filter #(= :tool (:role %)))
                                first)]
          (is (= "call_1" (:tool-call-id tool-message)))
          (is (= "{\"temperature-c\":21}" (:content tool-message)))))
      (testing "the final conversation retains all rounds"
        (is (= [:user :assistant :tool :assistant]
               (map :role (:messages response)))))))

  (testing "tool errors are reported back to the model, not thrown"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])
                                   (fn [request]
                                     (text-response
                                      (:content (last (:messages request)))))])
          tool {:name "get-weather"
                :fn (fn [_] (throw (ex-info "socket timeout" {})))}
          response (llm/generate config "Weather?" {:tools [tool]})]
      (is (re-find #"Error executing tool get-weather: socket timeout"
                   (:text response)))))

  (testing "tools without :fn are returned for manual handling"
    (let [config (scripted-config [(tool-call-response [weather-tool-call])])
          response (llm/generate config "Weather?"
                                 {:tools [{:name "get-weather"
                                           :parameters {:type "object"}}]})]
      (is (= [weather-tool-call] (:tool-calls response)))
      (is (= :tool-calls (:finish-reason response)))))

  (testing "the loop is bounded by :max-tool-rounds"
    (let [n (atom 0)
          config (scripted-config
                  (repeat 10 (tool-call-response [weather-tool-call])))
          tool {:name "get-weather" :fn (fn [_] (swap! n inc) "sunny")}
          response (llm/generate config "Weather?"
                                 {:tools [tool] :max-tool-rounds 2})]
      (is (= 2 @n) "tool ran once per allowed round")
      (is (= [weather-tool-call] (:tool-calls response))
          "unresolved tool calls surface to the caller when the cap is hit"))))

(deftest streaming-callback-passthrough
  (let [config (scripted-config
                [(fn [{:keys [on-chunk]}]
                   (doseq [t ["Once" " upon" " a time"]]
                     (on-chunk {:text t}))
                   (text-response "Once upon a time"))])
        chunks (atom [])
        response (llm/generate config "story"
                               {:on-chunk #(swap! chunks conj (:text %))})]
    (is (= ["Once" " upon" " a time"] @chunks))
    (is (= "Once upon a time" (:text response)))))

(deftest embeddings
  (let [seen (atom nil)]
    (defmethod provider/embed! ::scripted
      [_ request]
      (reset! seen request)
      {:embeddings (mapv (constantly [0.1 0.2]) (:input request))
       :model (:model request)
       :usage {:input-tokens 2}
       :raw {}})
    (let [config {:providers {:fake {:adapter ::scripted}}
                  :models {:emb {:provider :fake :model "embedder-1"}}
                  :defaults {:embedding-model :emb}}]
      (testing "single string input"
        (let [response (llm/embed config "hello")]
          (is (= ["hello"] (:input @seen)))
          (is (= "embedder-1" (:model @seen)))
          (is (= [0.1 0.2] (:embedding response)))
          (is (= :fake (:provider response)))))
      (testing "seq input has no :embedding, only :embeddings"
        (let [response (llm/embed config ["a" "b"])]
          (is (= [[0.1 0.2] [0.1 0.2]] (:embeddings response)))
          (is (nil? (:embedding response))))))))
