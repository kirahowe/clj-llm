(ns assay.provider
  "The adapter boundary. An adapter is a set of multimethod
  implementations dispatching on the :assay/adapter key of a provider
  config map. assay ships adapters for :anthropic, :openai (anything
  speaking the OpenAI chat-completions protocol) and :ollama; add your
  own by requiring this namespace and implementing `generate!` (and
  optionally `embed!`, `start`, `stop`) for your own dispatch keyword.

  All four multimethods take the raw provider config (so custom,
  adapter-owned keys flow through untouched) and a trailing `opts` map.
  `opts` is harness-level context — empty today, reserved for future
  cross-cutting concerns like cancellation or telemetry. Adapters should
  accept it and may ignore it.

  `generate!` receives a normalized request whose assay-owned keys are
  namespaced:

    #:assay{:model       \"model-id\"          string, already resolved
            :messages    [{:role :user :content \"...\"} ...]
            :system      \"...\"               optional system prompt
            :max-tokens  4096                  optional
            :temperature 0.7                   optional
            :tools       [{:name \"...\" :description \"...\"
                           :parameters {...json schema...} :fn (fn [args] ...)}]
            :on-chunk    (fn [{:keys [type text]}])  optional streaming callback;
                                               chunks have :type (:text today)
            :options     {...}}                provider-specific passthrough,
                                               merged into the wire request

  Messages, tools, tool calls and usage are plain-keyed protocol
  structures (see assay.spec). Message roles are :system, :user,
  :assistant and :tool. An assistant message may carry :tool-calls
  [{:id :name :arguments}], and a :tool message carries :tool-call-id,
  :name and :content (the tool's result). Message :content is a string
  today; a vector of content-part maps is reserved for future multimodal
  content — adapters should tolerate (or reject clearly) what they don't
  support.

  Adapters return a plain-keyed result map:

    {:message       {:role :assistant :content \"...\" :tool-calls [...]}
     :model         \"model-id-as-reported\"
     :usage         {:input-tokens n :output-tokens n}
     :finish-reason :stop | :length | :tool-calls | :refusal | <other kw>
     :raw           <parsed wire response>}

  ## Compatibility contract

  These are the rules assay commits to so that adapters written today
  keep working with every future version:

  - New request keys are additive; adapters may ignore keys they don't
    understand.

  - New result keys are always optional; :message, :usage,
    :finish-reason and :raw remain sufficient.

  - The multimethod signatures are frozen: (generate! provider-config
    request opts), (embed! provider-config request opts),
    (start provider-config opts), (stop provider-config opts). Anything
    new travels inside `request` or `opts`, never as a new positional
    argument.

  - Any future multimethod added to this namespace ships with a
    :default implementation, so existing adapters never have to change
    to keep loading.

  - Unqualified keys in provider config maps other than those read by
    the adapter itself will never gain library-level meaning; only
    :assay/-qualified keys are assay's.")

(defn- dispatch [provider-config & _]
  (:assay/adapter provider-config))

(defmulti generate!
  "Execute one text-generation request against a provider:
  (generate! provider-config request opts). Dispatches on the provider
  config's :assay/adapter. See the namespace docstring for the
  request/result contract. Most callers want assay.core/generate, which
  resolves config, applies defaults and runs the tool loop."
  dispatch)

(defmulti embed!
  "Compute embeddings: (embed! provider-config request opts). Request:
  #:assay{:model \"...\" :input [\"text\" ...] :options {...}}. Returns
  {:embeddings [[floats] ...] :model ... :usage ... :raw ...}."
  dispatch)

(defmulti start
  "Optional lifecycle hook: (start provider-config opts). Prepare a
  provider for use (e.g. fetch an OAuth token) and return the (possibly
  augmented) provider config. Called by the integrant bindings on system
  start. Defaults to identity."
  dispatch)

(defmulti stop
  "Optional lifecycle hook: (stop provider-config opts). Release
  anything `start` acquired. Defaults to a no-op."
  dispatch)

(defmethod start :default [provider-config _opts] provider-config)
(defmethod stop :default [_provider-config _opts] nil)

(defn- unknown-adapter! [provider-config op]
  (throw (ex-info (str "No " op " implementation for adapter "
                       (pr-str (:assay/adapter provider-config))
                       ". Built-in adapters (:anthropic, :openai, :ollama) are "
                       "loaded by requiring assay.core.")
                  {:type :assay/unknown-adapter
                   :adapter (:assay/adapter provider-config)
                   :op op})))

(defmethod generate! :default [provider-config _request _opts]
  (unknown-adapter! provider-config "generate!"))

(defmethod embed! :default [provider-config _request _opts]
  (unknown-adapter! provider-config "embed!"))
