(ns clj-llm.providers.anthropic-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.providers.anthropic :as anthropic]))

(deftest build-request-basics
  (let [body (anthropic/build-request
              {:llm/model "claude-sonnet-4-6"
               :llm/messages [{:role :user :content "hi"}]})]
    (is (= "claude-sonnet-4-6" (:model body)))
    (is (= anthropic/default-max-tokens (:max_tokens body)))
    (is (= [{:role "user" :content "hi"}] (:messages body)))
    (is (not (contains? body :system)))
    (is (not (contains? body :stream)))))

(deftest build-request-system-handling
  (testing "explicit :llm/system"
    (is (= "be brief"
           (:system (anthropic/build-request
                     {:llm/model "m" :llm/system "be brief"
                      :llm/messages [{:role :user :content "hi"}]})))))
  (testing ":system role messages are lifted out of :llm/messages"
    (let [body (anthropic/build-request
                {:llm/model "m"
                 :llm/messages [{:role :system :content "be brief"}
                                {:role :user :content "hi"}]})]
      (is (= "be brief" (:system body)))
      (is (= [{:role "user" :content "hi"}] (:messages body)))))
  (testing ":llm/system wins over an inline system message"
    (let [body (anthropic/build-request
                {:llm/model "m" :llm/system "override"
                 :llm/messages [{:role :system :content "inline"}
                                {:role :user :content "hi"}]})]
      (is (= "override" (:system body)))
      (is (= [{:role "user" :content "hi"}] (:messages body))
          "no system-role message leaks into :messages"))))

(deftest build-request-tools-and-options
  (let [body (anthropic/build-request
              {:llm/model "m"
               :llm/messages [{:role :user :content "hi"}]
               :llm/max-tokens 100
               :llm/temperature 0.5
               :llm/tools [{:name "get-weather"
                            :description "weather lookup"
                            :parameters {:type "object"}}]
               :llm/options {:top_k 5}})]
    (is (= 100 (:max_tokens body)))
    (is (= 0.5 (:temperature body)))
    (is (= [{:name "get-weather"
             :description "weather lookup"
             :input_schema {:type "object"}}]
           (:tools body)))
    (is (= 5 (:top_k body)) ":llm/options merge into the wire request")))

(deftest build-request-options-nil-removes-keys
  (let [body (anthropic/build-request
              {:llm/model "m"
               :llm/messages [{:role :user :content "hi"}]
               :llm/options {:max_tokens nil :top_k 5}})]
    (is (= 5 (:top_k body)))
    (is (not (contains? body :max_tokens))
        "nil in :llm/options removes a key the adapter always injects")))

(deftest build-request-streaming
  (let [body (anthropic/build-request
              {:llm/model "m"
               :llm/messages [{:role :user :content "hi"}]}
              {:stream? true})]
    (is (true? (:stream body)))))

(deftest tool-conversation-wire-format
  (let [body (anthropic/build-request
              {:llm/model "m"
               :llm/messages [{:role :user :content "Weather in Berlin and Paris?"}
                              {:role :assistant :content "Checking."
                               :tool-calls [{:id "t1" :name "get-weather"
                                             :arguments {:city "Berlin"}}
                                            {:id "t2" :name "get-weather"
                                             :arguments {:city "Paris"}}]}
                              {:role :tool :tool-call-id "t1" :name "get-weather"
                               :content "21C"}
                              {:role :tool :tool-call-id "t2" :name "get-weather"
                               :content "19C"}]})
        [_ assistant results] (:messages body)]
    (testing "assistant tool calls become tool_use content blocks"
      (is (= "assistant" (:role assistant)))
      (is (= [{:type "text" :text "Checking."}
              {:type "tool_use" :id "t1" :name "get-weather"
               :input {:city "Berlin"}}
              {:type "tool_use" :id "t2" :name "get-weather"
               :input {:city "Paris"}}]
             (:content assistant))))
    (testing "parallel tool results are batched into ONE user message"
      (is (= 3 (count (:messages body))))
      (is (= "user" (:role results)))
      (is (= [{:type "tool_result" :tool_use_id "t1" :content "21C"}
              {:type "tool_result" :tool_use_id "t2" :content "19C"}]
             (:content results))))))

(deftest parse-response-text
  (let [parsed (anthropic/parse-response
                {:content [{:type "text" :text "Hello!"}]
                 :model "claude-sonnet-4-6"
                 :stop_reason "end_turn"
                 :usage {:input_tokens 12 :output_tokens 4}})]
    (is (= {:role :assistant :content "Hello!"} (:message parsed)))
    (is (= :stop (:finish-reason parsed)))
    (is (= {:input-tokens 12 :output-tokens 4} (:usage parsed)))))

(deftest parse-response-tool-use
  (let [parsed (anthropic/parse-response
                {:content [{:type "text" :text "Let me check."}
                           {:type "tool_use" :id "toolu_1" :name "get-weather"
                            :input {:city "Berlin"}}]
                 :stop_reason "tool_use"
                 :usage {}})]
    (is (= :tool-calls (:finish-reason parsed)))
    (is (= "Let me check." (get-in parsed [:message :content])))
    (is (= [{:id "toolu_1" :name "get-weather" :arguments {:city "Berlin"}}]
           (get-in parsed [:message :tool-calls])))))

(deftest streaming-accumulation
  (let [events [{:type "message_start"
                 :message {:model "claude-sonnet-4-6"
                           :usage {:input_tokens 25}}}
                {:type "content_block_start" :index 0
                 :content_block {:type "text" :text ""}}
                {:type "content_block_delta" :index 0
                 :delta {:type "text_delta" :text "Hel"}}
                {:type "content_block_delta" :index 0
                 :delta {:type "text_delta" :text "lo"}}
                {:type "content_block_stop" :index 0}
                {:type "content_block_start" :index 1
                 :content_block {:type "tool_use" :id "toolu_1"
                                 :name "get-weather"}}
                {:type "content_block_delta" :index 1
                 :delta {:type "input_json_delta" :partial_json "{\"city\":"}}
                {:type "content_block_delta" :index 1
                 :delta {:type "input_json_delta" :partial_json "\"Berlin\"}"}}
                {:type "content_block_stop" :index 1}
                {:type "message_delta"
                 :delta {:stop_reason "tool_use"}
                 :usage {:output_tokens 9}}
                {:type "message_stop"}]
        chunks (atom [])
        on-chunk #(swap! chunks conj %)
        parsed (-> (reduce #(anthropic/reduce-event %1 %2 on-chunk)
                           anthropic/initial-stream-state
                           events)
                   anthropic/finalize-stream)]
    (is (= ["Hel" "lo"] (map :text @chunks)))
    (is (every? #(= :text (:type %)) @chunks))
    (is (= "Hello" (get-in parsed [:message :content])))
    (is (= [{:id "toolu_1" :name "get-weather" :arguments {:city "Berlin"}}]
           (get-in parsed [:message :tool-calls])))
    (is (= "claude-sonnet-4-6" (:model parsed)))
    (is (= :tool-calls (:finish-reason parsed)))
    (is (= {:input-tokens 25 :output-tokens 9} (:usage parsed)))))

(deftest stream-error-events-throw
  (is (thrown-with-msg?
       Exception #"Anthropic stream error: overloaded"
       (anthropic/reduce-event anthropic/initial-stream-state
                               {:type "error"
                                :error {:type "overloaded_error"
                                        :message "overloaded"}}
                               nil))))
