(ns clj-llm.providers.openai-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.providers.openai :as openai]))

(deftest build-request-basics
  (let [body (openai/build-request
              {:clj-llm/model "gpt-4o-mini"
               :clj-llm/messages [{:role :user :content "hi"}]
               :clj-llm/max-tokens 128
               :clj-llm/temperature 0.3})]
    (is (= "gpt-4o-mini" (:model body)))
    (is (= [{:role "user" :content "hi"}] (:messages body)))
    (is (= 128 (:max_completion_tokens body))
        "max-tokens is sent as max_completion_tokens by default")
    (is (not (contains? body :max_tokens)))
    (is (= 0.3 (:temperature body)))
    (is (not (contains? body :stream)))))

(deftest build-request-legacy-max-tokens
  (let [body (openai/build-request
              {:clj-llm/model "gpt-4o-mini"
               :clj-llm/messages [{:role :user :content "hi"}]
               :clj-llm/max-tokens 128}
              {:legacy-max-tokens? true})]
    (is (= 128 (:max_tokens body))
        ":legacy-max-tokens? true sends the older max_tokens field")
    (is (not (contains? body :max_completion_tokens)))))

(deftest build-request-system-prepended
  (let [body (openai/build-request
              {:clj-llm/model "m" :clj-llm/system "be brief"
               :clj-llm/messages [{:role :user :content "hi"}]})]
    (is (= [{:role "system" :content "be brief"}
            {:role "user" :content "hi"}]
           (:messages body)))))

(deftest build-request-streaming-asks-for-usage
  (let [body (openai/build-request
              {:clj-llm/model "m"
               :clj-llm/messages [{:role :user :content "hi"}]}
              {:stream? true})]
    (is (true? (:stream body)))
    (is (= {:include_usage true} (:stream_options body)))))

(deftest tool-conversation-wire-format
  (let [body (openai/build-request
              {:clj-llm/model "m"
               :clj-llm/messages [{:role :user :content "weather?"}
                                  {:role :assistant :content nil
                                   :tool-calls [{:id "call_1" :name "get-weather"
                                                 :arguments {:city "Berlin"}}]}
                                  {:role :tool :tool-call-id "call_1"
                                   :name "get-weather" :content "21C"}]
               :clj-llm/tools [{:name "get-weather" :description "d"
                                :parameters {:type "object"}}]})
        [_ assistant result] (:messages body)]
    (is (= [{:type "function"
             :function {:name "get-weather" :description "d"
                        :parameters {:type "object"}}}]
           (:tools body)))
    (testing "assistant tool calls carry JSON-encoded arguments"
      (is (= "{\"city\":\"Berlin\"}"
             (get-in assistant [:tool_calls 0 :function :arguments]))))
    (testing "tool results use the tool role with tool_call_id"
      (is (= {:role "tool" :tool_call_id "call_1" :content "21C"} result)))))

(deftest parse-response-text
  (let [parsed (openai/parse-response
                {:choices [{:message {:role "assistant" :content "Hello!"}
                            :finish_reason "stop"}]
                 :model "gpt-4o-mini"
                 :usage {:prompt_tokens 9 :completion_tokens 3}})]
    (is (= {:role :assistant :content "Hello!"} (:message parsed)))
    (is (= :stop (:finish-reason parsed)))
    (is (= {:input-tokens 9 :output-tokens 3} (:usage parsed)))))

(deftest parse-response-tool-calls
  (let [parsed (openai/parse-response
                {:choices [{:message {:content nil
                                      :tool_calls [{:id "call_1"
                                                    :function {:name "get-weather"
                                                               :arguments "{\"city\":\"Berlin\"}"}}]}
                            :finish_reason "tool_calls"}]})]
    (is (= :tool-calls (:finish-reason parsed)))
    (is (= [{:id "call_1" :name "get-weather" :arguments {:city "Berlin"}}]
           (get-in parsed [:message :tool-calls])))
    (is (= "" (get-in parsed [:message :content])) "nil content normalizes to \"\"")))

(deftest streaming-accumulation
  (let [chunks-in [{:choices [{:delta {:role "assistant" :content ""}}]
                    :model "gpt-4o-mini"}
                   {:choices [{:delta {:content "Hel"}}]}
                   {:choices [{:delta {:content "lo"}}]}
                   {:choices [{:delta {:tool_calls [{:index 0 :id "call_1"
                                                     :function {:name "get-weather"
                                                                :arguments ""}}]}}]}
                   {:choices [{:delta {:tool_calls [{:index 0
                                                     :function {:arguments "{\"city\""}}]}}]}
                   {:choices [{:delta {:tool_calls [{:index 0
                                                     :function {:arguments ":\"Berlin\"}"}}]}}]}
                   {:choices [{:delta {} :finish_reason "tool_calls"}]}
                   {:choices [] :usage {:prompt_tokens 20 :completion_tokens 11}}]
        streamed (atom [])
        on-chunk #(swap! streamed conj %)
        parsed (-> (reduce #(openai/reduce-chunk %1 %2 on-chunk)
                           openai/initial-stream-state
                           chunks-in)
                   openai/finalize-stream)]
    (is (= ["" "Hel" "lo"] (map :text @streamed)))
    (is (every? #(= :text (:type %)) @streamed))
    (is (= "Hello" (get-in parsed [:message :content])))
    (is (= [{:id "call_1" :name "get-weather" :arguments {:city "Berlin"}}]
           (get-in parsed [:message :tool-calls])))
    (is (= :tool-calls (:finish-reason parsed)))
    (is (= "gpt-4o-mini" (:model parsed)))
    (is (= {:input-tokens 20 :output-tokens 11} (:usage parsed)))))
