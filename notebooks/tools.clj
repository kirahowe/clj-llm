;; # Tools

(ns tools
  (:require [clj-llm.core :as llm]
            [book.demo :as demo]))

(def config demo/config)

;; ## Tools are maps; the loop is automatic

;; A tool is a map: a `:name` and `:description` for the model, a `:parameters` JSON-schema map describing the arguments, and, if you want clj-llm to run it, a `:fn`. When every tool the model calls has a `:fn`, the library invokes it with the parsed arguments (keyword keys), appends the result to the conversation as a `:tool` message, and asks the model again, looping until the model answers or `:llm/max-tool-rounds` (default 10) is hit:

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

;; The full exchange is visible in the messages (user, the assistant's tool call, the tool result, and the final answer) because tool rounds are ordinary messages in the conversation, not hidden machinery:

(mapv :role (:llm/messages r))

;; The assistant's tool-call message and the tool-result message are plain data too. Note the shapes: a tool call is `{:id ... :name ... :arguments {...}}` on the assistant message, and the result travels back as `{:role :tool :tool-call-id ... :content ...}`:

(filter #(#{:assistant :tool} (:role %)) (butlast (:llm/messages r)))

;; A `:fn` may return a string or any JSON-encodable value (it will be serialized for the model). The library catches exceptions inside a `:fn` and reports them back to the model as tool errors instead of crashing the call, so the model gets a chance to recover or explain.

;; Usage accounting sums over all rounds, so `:llm/usage` on the final response reflects the whole loop, and `:llm/latency-ms` is wall-clock for everything.

;; ## Taking the loop into your own hands

;; Omit `:fn` and clj-llm stays out of the way: the call returns with `:llm/finish-reason :tool-calls` and the pending calls under `:llm/tool-calls`, and continuing is your responsibility. This is the right shape when tool execution needs human approval, a queue, budget checks, or your own agent loop:

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

;; Both styles produce the same message shapes, so you can start with the automatic loop and graduate to a manual one (or mix them per tool call) without changing how conversations are stored or evaluated. The evals chapter shows how a tool-using pipeline gets scored just like a plain call: with `:llm/tools` on a variant, or the whole loop inside an `:llm/task`.
