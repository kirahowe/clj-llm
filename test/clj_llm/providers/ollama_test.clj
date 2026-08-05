(ns clj-llm.providers.ollama-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.providers.ollama :as ollama]))

(deftest build-request-basics
  (let [body (ollama/build-request
              {:llm/model "llama3.2"
               :llm/messages [{:role :user :content "hi"}]
               :llm/max-tokens 64
               :llm/temperature 0.1})]
    (is (= "llama3.2" (:model body)))
    (is (= [{:role "user" :content "hi"}] (:messages body)))
    (is (false? (:stream body)) "non-streaming must be explicit for Ollama")
    (testing "sampling knobs map to Ollama's :options"
      (is (= {:temperature 0.1 :num_predict 64} (:options body))))))

(deftest build-request-streaming
  (let [body (ollama/build-request
              {:llm/model "llama3.2"
               :llm/messages [{:role :user :content "hi"}]}
              {:stream? true})]
    (is (true? (:stream body)))))

(deftest build-request-options-nil-removes-keys
  (let [body (ollama/build-request
              {:llm/model "llama3.2"
               :llm/messages [{:role :user :content "hi"}]
               :llm/temperature 0.1
               :llm/options {:options nil :keep_alive "5m"}})]
    (is (= "5m" (:keep_alive body)))
    (is (not (contains? body :options))
        "nil in :llm/options removes the sampling :options key")))

(deftest build-request-system-and-tools
  (let [body (ollama/build-request
              {:llm/model "m" :llm/system "be brief"
               :llm/messages [{:role :user :content "hi"}]
               :llm/tools [{:name "get-weather" :description "d"
                            :parameters {:type "object"}}]})]
    (is (= {:role "system" :content "be brief"} (first (:messages body))))
    (is (= [{:type "function"
             :function {:name "get-weather" :description "d"
                        :parameters {:type "object"}}}]
           (:tools body)))))

(deftest build-request-system-overrides-inline
  (let [body (ollama/build-request
              {:llm/model "m" :llm/system "override"
               :llm/messages [{:role :system :content "inline"}
                              {:role :user :content "hi"}]})]
    (is (= [{:role "system" :content "override"}
            {:role "user" :content "hi"}]
           (:messages body))
        ":llm/system wins over an inline system message, which is dropped")))

(deftest tool-conversation-wire-format
  (let [body (ollama/build-request
              {:llm/model "m"
               :llm/messages [{:role :user :content "weather?"}
                              {:role :assistant :content ""
                               :tool-calls [{:id "call_0" :name "get-weather"
                                             :arguments {:city "Berlin"}}]}
                              {:role :tool :tool-call-id "call_0"
                               :name "get-weather" :content "21C"}]})
        [_ assistant result] (:messages body)]
    (testing "arguments stay a structured map (Ollama-native, not JSON string)"
      (is (= [{:function {:name "get-weather" :arguments {:city "Berlin"}}}]
             (:tool_calls assistant))))
    (is (= {:role "tool" :content "21C"} result))))

(deftest parse-response-text
  (let [parsed (ollama/parse-response
                {:model "llama3.2"
                 :message {:role "assistant" :content "Hello!"}
                 :done true
                 :done_reason "stop"
                 :prompt_eval_count 14
                 :eval_count 6})]
    (is (= {:role :assistant :content "Hello!"} (:message parsed)))
    (is (= :stop (:finish-reason parsed)))
    (is (= {:input-tokens 14 :output-tokens 6} (:usage parsed)))))

(deftest parse-response-tool-calls
  (let [parsed (ollama/parse-response
                {:message {:content ""
                           :tool_calls [{:function {:name "get-weather"
                                                    :arguments {:city "Berlin"}}}]}
                 :done_reason "stop"})]
    (is (= :tool-calls (:finish-reason parsed)))
    (is (= [{:id "call_0" :name "get-weather" :arguments {:city "Berlin"}}]
           (get-in parsed [:message :tool-calls])))))

(deftest streaming-accumulation
  (let [chunks-in [{:model "llama3.2" :message {:content "Hel"} :done false}
                   {:message {:content "lo"} :done false}
                   {:message {:content ""} :done true :done_reason "stop"
                    :prompt_eval_count 10 :eval_count 2}]
        streamed (atom [])
        on-chunk #(swap! streamed conj %)
        parsed (-> (reduce #(ollama/reduce-chunk %1 %2 on-chunk)
                           ollama/initial-stream-state
                           chunks-in)
                   ollama/finalize-stream)]
    (is (= ["Hel" "lo"] (map :text @streamed)))
    (is (every? #(= :text (:type %)) @streamed))
    (is (= "Hello" (get-in parsed [:message :content])))
    (is (= :stop (:finish-reason parsed)))
    (is (= "llama3.2" (:model parsed)))
    (is (= {:input-tokens 10 :output-tokens 2} (:usage parsed)))))
