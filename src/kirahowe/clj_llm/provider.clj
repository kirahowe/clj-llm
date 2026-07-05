(ns kirahowe.clj-llm.provider
  "The adapter boundary. An adapter is a set of multimethod
  implementations dispatching on the :adapter key of a provider config
  map. clj-llm ships adapters for :anthropic, :openai (anything speaking
  the OpenAI chat-completions protocol) and :ollama; add your own by
  requiring this namespace and implementing `generate!` (and optionally
  `embed!`, `start`, `stop`) for your own dispatch keyword.

  Adapters receive the raw provider config (so custom keys flow through)
  and a normalized request:

    {:model       \"model-id\"           string, already resolved
     :messages    [{:role :user :content \"...\"} ...]
     :system      \"...\"                optional system prompt
     :max-tokens  4096                   optional
     :temperature 0.7                    optional
     :tools       [{:name \"...\" :description \"...\"
                    :parameters {...json schema...} :fn (fn [args] ...)}]
     :on-chunk    (fn [{:keys [text]}])  optional streaming callback
     :options     {...}                  provider-specific passthrough,
                                         merged into the wire request}

  Message roles are :system, :user, :assistant and :tool. An assistant
  message may carry :tool-calls [{:id :name :arguments}], and a :tool
  message carries :tool-call-id, :name and :content (the tool's result).

  Adapters return:

    {:message       {:role :assistant :content \"...\" :tool-calls [...]}
     :model         \"model-id-as-reported\"
     :usage         {:input-tokens n :output-tokens n}
     :finish-reason :stop | :length | :tool-calls | :refusal | <other kw>
     :raw           <parsed wire response>}")

(defn- dispatch [provider-config _request]
  (:adapter provider-config))

(defmulti generate!
  "Execute one text-generation request against a provider. Dispatches on
  the provider config's :adapter. See the namespace docstring for the
  request/response contract. Most callers want kirahowe.clj-llm/generate,
  which resolves config, applies defaults and runs the tool loop."
  dispatch)

(defmulti embed!
  "Compute embeddings. Request: {:model \"...\" :input [\"text\" ...]}.
  Returns {:embeddings [[floats] ...] :model ... :usage ... :raw ...}."
  dispatch)

(defmulti start
  "Optional lifecycle hook: prepare a provider for use (e.g. fetch an
  OAuth token) and return the (possibly augmented) provider config.
  Called by the integrant bindings on system start. Defaults to identity."
  (fn [provider-config] (:adapter provider-config)))

(defmulti stop
  "Optional lifecycle hook: release anything `start` acquired.
  Defaults to a no-op."
  (fn [provider-config] (:adapter provider-config)))

(defmethod start :default [provider-config] provider-config)
(defmethod stop :default [_] nil)

(defn- unknown-adapter! [provider-config op]
  (throw (ex-info (str "No " op " implementation for adapter "
                       (pr-str (:adapter provider-config))
                       ". Built-in adapters (:anthropic, :openai, :ollama) are "
                       "loaded by requiring kirahowe.clj-llm.")
                  {:type ::unknown-adapter
                   :adapter (:adapter provider-config)
                   :op op})))

(defmethod generate! :default [provider-config _]
  (unknown-adapter! provider-config "generate!"))

(defmethod embed! :default [provider-config _]
  (unknown-adapter! provider-config "embed!"))
