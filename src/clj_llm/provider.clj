(ns clj-llm.provider
  "The adapter boundary. An adapter is a set of multimethod
  implementations dispatching on the :llm/adapter key of a provider
  config map. clj-llm ships adapters for :anthropic, :openai (anything
  speaking the OpenAI chat-completions protocol) and :ollama; add your
  own by requiring this namespace and implementing `-generate!` (and
  optionally `-embed!`, `-start`, `-stop`) for your own dispatch keyword.

  The namespace is split into an SPI and an API, in the style of
  integrant's init-key/init:

  - The `-`-prefixed multimethods (`-generate!`, `-embed!`, `-start`,
    `-stop`) are the SPI: adapters implement them, nobody calls them
    directly. Each has exactly one fixed signature, with a trailing
    `opts` map of harness-level context — empty today, reserved for
    cross-cutting concerns like cancellation or telemetry. Implement the
    full signature; ignore `opts` until it means something.

  - The unprefixed functions (`generate!`, `embed!`, `start`, `stop`)
    are the API: callers use them, and `opts` is genuinely optional.
    They also give the library a permanent seam for future validation or
    instrumentation around adapter calls.

  `-generate!` receives the raw provider config (so custom, adapter-owned
  keys flow through untouched) and a normalized request whose
  library-owned keys are namespaced:

    #:llm{:model       \"model-id\"          string, already resolved
          :messages    [{:role :user :content \"...\"} ...]
          :system      \"...\"               optional system prompt
          :max-tokens  4096                  optional
          :temperature 0.7                   optional
          :tools       [{:name \"...\" :description \"...\"
                         :parameters {...json schema...} :fn (fn [args] ...)}]
          :on-chunk    (fn [{:keys [type text]}])  optional streaming callback;
                                             chunks have :type (:text today)
          :options     {...}}                provider-specific passthrough,
                                             merged into the wire request;
                                             nil values remove keys the
                                             adapter would otherwise set

  Messages, tools, tool calls and usage are plain-keyed protocol
  structures (see clj-llm.spec). Message roles are :system, :user,
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

  These are the rules clj-llm commits to so that adapters written today
  keep working with every future version:

  - New request keys are additive; adapters may ignore keys they don't
    understand.

  - New result keys are always optional; :message, :usage,
    :finish-reason and :raw remain sufficient.

  - The SPI signatures are frozen: (-generate! provider-config request
    opts), (-embed! provider-config request opts),
    (-start provider-config opts), (-stop provider-config opts).
    Anything new travels inside `request` or `opts`, never as a new
    positional argument. (The unprefixed API functions may grow
    conveniences — they are the library's, not the SPI.)

  - Any future SPI multimethod added to this namespace ships with a
    :default implementation, so existing adapters never have to change
    to keep loading.

  - Unqualified keys in provider config maps other than those read by
    the adapter itself will never gain library-level meaning; only
    :llm/-qualified keys are the library's. One exception: the library
    injects :llm/name at resolution time, the name the provider was
    registered under in :llm/providers (handy in adapter error
    messages).")

(defn- dispatch [provider-config & _]
  (:llm/adapter provider-config))

;; ---------------------------------------------------------------------------
;; SPI — adapters implement these; each has exactly one fixed signature

(defmulti -generate!
  "SPI: execute one text-generation request against a provider.
  Implement as (-generate! provider-config request opts) for your
  :llm/adapter keyword; see the namespace docstring for the
  request/result contract. Callers should use `generate!` instead."
  dispatch)

(defmulti -embed!
  "SPI: compute embeddings. Implement as (-embed! provider-config
  request opts); request is #:llm{:model \"...\" :input [\"text\" ...]
  :options {...}} and the result is {:embeddings [[floats] ...]
  :model ... :usage ... :raw ...}. Callers should use `embed!` instead."
  dispatch)

(defmulti -start
  "SPI: optional lifecycle hook, (-start provider-config opts). Prepare
  a provider for use (e.g. fetch an OAuth token) and return the
  (possibly augmented) provider config. Defaults to identity. Callers
  should use `start` instead."
  dispatch)

(defmulti -stop
  "SPI: optional lifecycle hook, (-stop provider-config opts). Release
  anything `-start` acquired. Defaults to a no-op. Callers should use
  `stop` instead."
  dispatch)

(defmethod -start :default [provider-config _opts] provider-config)
(defmethod -stop :default [_provider-config _opts] nil)

(defn- unknown-adapter! [provider-config op]
  (throw (ex-info (str "No " op " implementation for adapter "
                       (pr-str (:llm/adapter provider-config))
                       ". Built-in adapters (:anthropic, :openai, :ollama) are "
                       "loaded by requiring clj-llm.core.")
                  {:type :llm/unknown-adapter
                   :adapter (:llm/adapter provider-config)
                   :op op})))

(defmethod -generate! :default [provider-config _request _opts]
  (unknown-adapter! provider-config "-generate!"))

(defmethod -embed! :default [provider-config _request _opts]
  (unknown-adapter! provider-config "-embed!"))

;; ---------------------------------------------------------------------------
;; API — callers use these; opts is optional

(defn generate!
  "Execute one text-generation request against a provider. Dispatches
  to the adapter's `-generate!` implementation; `opts` defaults to {}.
  Most callers want clj-llm.core/generate, which resolves config,
  applies defaults and runs the tool loop."
  ([provider-config request] (-generate! provider-config request {}))
  ([provider-config request opts] (-generate! provider-config request opts)))

(defn embed!
  "Compute embeddings via the adapter's `-embed!` implementation;
  `opts` defaults to {}. Most callers want clj-llm.core/embed."
  ([provider-config request] (-embed! provider-config request {}))
  ([provider-config request opts] (-embed! provider-config request opts)))

(defn start
  "Run a provider's `-start` lifecycle hook, returning the (possibly
  augmented) provider config; `opts` defaults to {}. Called by the
  integrant bindings on system start."
  ([provider-config] (-start provider-config {}))
  ([provider-config opts] (-start provider-config opts)))

(defn stop
  "Run a provider's `-stop` lifecycle hook; `opts` defaults to {}.
  Called by the integrant bindings on system halt."
  ([provider-config] (-stop provider-config {}))
  ([provider-config opts] (-stop provider-config opts)))

(defn merge-options
  "Merge a request's :llm/options into an adapter-built wire-format
  request body. Non-nil values override what the adapter built; nil
  values remove the key entirely — the escape hatch when an adapter
  injects a default a particular server rejects (e.g.
  #:llm{:options {:stream_options nil}} for OpenAI-compatible servers
  that predate stream_options). Adapters should apply this last, so
  options always win."
  [body options]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
             body
             (or options {})))
