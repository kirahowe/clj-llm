;; # Tools

^{:kindly/hide-code true}
(ns tools
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]))

^{:kindly/hide-code true}
(def config demo/config)

;; ## Tools are maps; the loop is automatic

;; A tool is a map with a `:name`, a `:description`, and a JSON Schema under `:parameters`. Add a `:fn` if you want clj-llm to run it. When every requested tool has a matching function, clj-llm calls those functions with keywordized arguments, adds their results to the conversation, and asks the model to continue. The loop stops when the model answers or reaches `:llm/max-tool-rounds`, which defaults to 10:

;; Tool calls and arguments are untrusted, model-controlled provider output. The JSON Schema under `:parameters` guides the model; clj-llm does not use it as runtime validation or authorization. An automatic `:fn` must validate and authorize its own inputs and enforce appropriate idempotency, rate or cost budgets, and other side-effect controls.

(def weather-tool
  {:name "get-weather"
   :description "Look up current weather for a city"
   :parameters {:type "object"
                :properties {:city {:type "string"}}
                :required ["city"]}
   :fn (fn [{:keys [city]}]
         {:city city :temperature-c 21 :sky "clear"})})

(def r (llm/generate config "What's the weather in Berlin?"
                     {:llm/tools [weather-tool]}))

(:llm/text r)

;; The response messages contain the full exchange: the user prompt, assistant tool call, tool result, and final answer:

(mapv :role (:llm/messages r))

;; A tool call is `{:id ... :name ... :arguments {...}}` on the assistant message. Its result is a `{:role :tool :tool-call-id ... :content ...}` message:

(filter #(#{:assistant :tool} (:role %)) (butlast (:llm/messages r)))

;; A `:fn` may return a string or any JSON-encodable value (it will be serialized for the model). The library catches exceptions inside a `:fn` and reports a generic, redacted tool error back to the model instead of crashing the call. Exception details are not sent to the provider.

;; Usage accounting sums over all rounds, so `:llm/usage` on the final response reflects the whole loop, and `:llm/latency-ms` is wall-clock for everything.

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

;; Automatic and manual handling use the same message shapes. Handling is decided for the whole batch of calls returned by a model: if any requested tool has no matching `:fn`, clj-llm returns the pending calls without running any of them. The evals chapter shows how to score a tool-using request with `:llm/tools` on a variant, or a custom loop inside `:llm/task`.
