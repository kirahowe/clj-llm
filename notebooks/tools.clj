;; # Tools

^{:kindly/hide-code true}
(ns tools
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]))

^{:kindly/hide-code true}
(def config demo/config)

;; ## Tools are maps; the loop is automatic

;; A tool is a map with a `:name`, a `:description`, and a JSON Schema under `:parameters`. Add a `:fn` if you want clj-llm to run it. When every requested tool has a matching function, clj-llm calls those functions with keywordized arguments, adds their results to the conversation, and asks the model to continue. The loop stops when the model answers, reaches `:llm/max-tool-rounds`, or exhausts `:llm/max-tool-calls`. Both limits default to 10; rounds bound provider continuations while calls bound the total number of function invocations across all rounds.

;; Tool calls and arguments are untrusted, model-controlled provider output. The JSON Schema under `:parameters` guides the model; it is not runtime validation or authorization.

(def weather-tool
  {:name "get-weather"
   :description "Look up current weather for a city"
   :parameters {:type "object"
                :properties {:city {:type "string"}}
                :required ["city"]}
   :fn (fn [{:keys [city]}]
         {:city city :temperature-c 21 :sky "clear"})})

(def r (llm/generate config "What's the weather in Berlin?"
                     {:llm/tools [weather-tool]
                      :llm/max-tool-calls 4
                      :llm/tool-policy
                      (fn [tool-call tool]
                        (and (= "get-weather" (:name tool-call))
                             (= "get-weather" (:name tool))))
                      :llm/tool-argument-validator
                      (fn [arguments _tool]
                        (string? (:city arguments)))}))

(:llm/text r)

;; The response messages contain the full exchange: the user prompt, assistant tool call, tool result, and final answer:

(mapv :role (:llm/messages r))

;; A tool call is `{:id ... :name ... :arguments {...}}` on the assistant message. Its result is a `{:role :tool :tool-call-id ... :content ...}` message:

(filter #(#{:assistant :tool} (:role %)) (butlast (:llm/messages r)))

;; A `:fn` may return a string or any JSON-encodable value (it will be serialized for the model). The library catches exceptions inside a `:fn` and reports a generic, redacted tool error back to the model instead of crashing the call. Exception details are not sent to the provider.

;; Usage accounting sums over all rounds, so `:llm/usage` on the final response reflects the whole loop, and `:llm/latency-ms` is wall-clock for everything.

;; ## Preflight controls for automatic execution

;; `:llm/tool-policy` is an optional `(fn [tool-call tool-metadata])`. `tool-call` is the normalized provider call (`{:id :name :arguments}`); `tool-metadata` is its matching tool definition with executable `:fn` removed. Return truthy to approve that invocation and false/nil to reject it.

;; `:llm/tool-argument-validator` is an optional `(fn [arguments tool-metadata])` with the same truthy/false contract. Supply a real validator appropriate to your application here if desired. clj-llm deliberately does not implement a partial JSON Schema validator.

;; Every provider-returned batch is preflighted before any `:fn` runs. An unknown tool, policy rejection or exception, validator rejection or exception, or a batch larger than the remaining `:llm/max-tool-calls` budget rejects automatic execution for the whole batch. A later oversized batch therefore cannot partially consume its remaining budget. Set the call budget to 0 to disable automatic execution while retaining structured rejection reporting.

;; Rejected calls remain under `:llm/tool-calls` and local rejection records appear under `:llm/tool-rejections`. Each record contains `{:tool-call call :reason keyword}` plus local `:message` data for a policy/validator exception or `:budget` data for a budget rejection. These records and hook functions are never sent to the provider or included in the replayable `:llm/request`; exception messages remain local. Actual exceptions thrown by an approved tool function keep the existing behavior: the provider receives only `Error executing tool <name>`.

;; ## Taking the loop into your own hands

;; Omit `:fn` to approve and handle calls yourself. The response uses `:llm/finish-reason :tool-calls` and puts pending calls under `:llm/tool-calls`. Manual handling is appropriate when execution needs human approval or application-specific validation, authorization, idempotency, queueing, or budget controls:

(def pending (llm/generate config "What's the weather in Berlin?"
                           {:llm/tools [(dissoc weather-tool :fn)]}))

(select-keys pending [:llm/finish-reason :llm/tool-calls])

;; To continue, append one `{:role :tool :tool-call-id id :name name :content result}` message per call and generate again over the extended conversation:

(let [call (first (:llm/tool-calls pending))
      result "21°C and clear"
      messages (conj (:llm/messages pending)
                     {:role :tool
                      :tool-call-id (:id call)
                      :name (:name call)
                      :content result})]
  (:llm/text (llm/generate config {:llm/messages messages
                                   :llm/tools [(dissoc weather-tool :fn)]})))

;; Automatic and manual handling use the same message shapes. Handling is decided for the whole batch of calls returned by a model. If every call matches a tool but any matching tool omits `:fn`, clj-llm preserves the manual path and returns the entire pending batch without running policy/validator hooks or any function. Unknown tool names are rejected locally instead. The evals chapter shows how to score a tool-using request with `:llm/tools` on a variant, or a custom loop inside `:llm/task`.
