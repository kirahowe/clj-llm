(ns assay.core
  "A small, functional, provider-agnostic library for calling LLMs, with
  evals built in from the ground up.

  Everything is plain data: configuration is an EDN map (loaded from a
  file — see assay.config), a conversation is a vector of message maps,
  and every function here is stateless — pass the config in, get a value
  back. That makes the library context agnostic: use it from a web
  handler, a CLI, a background job, or a one-off REPL session.

    (require '[assay.core :as assay])

    (def config (assay/read-config \"llm.edn\"))

    ;; zero-shot
    (assay/generate config \"Why is the sky blue?\")
    ;; => #:assay{:text \"...\" :messages [...] :usage {...} ...}

    ;; multi-turn is just data — thread :assay/messages back in
    (let [r1 (assay/generate config \"Name a prime number.\")]
      (assay/generate config {:assay/messages (conj (:assay/messages r1)
                                                    {:role :user :content \"Why is it prime?\"})}))

    ;; streaming — chunks carry a :type so future chunk kinds can be
    ;; introduced without breaking existing callbacks; ignore types you
    ;; don't recognize
    (assay/generate config \"Tell me a story\"
                    {:assay/on-chunk (fn [{:keys [type text]}]
                                       (when (= :text type) (print text) (flush)))})

    ;; tools — maps with a :fn are executed in an automatic loop
    (assay/generate config \"What's the weather in Berlin?\"
                    {:assay/tools [{:name \"get-weather\"
                                    :description \"Look up current weather for a city\"
                                    :parameters {:type \"object\"
                                                 :properties {:city {:type \"string\"}}
                                                 :required [\"city\"]}
                                    :fn (fn [{:keys [city]}] (fetch-weather city))}]})

    ;; embeddings
    (assay/embed config \"some text\")

  Keyspace: every key the library defines in the maps you author or
  store (config, requests, responses, eval suites) is namespaced
  :assay/...; unqualified keys and your own namespaced keys in those
  maps are yours forever. Messages, tools, tool calls, usage and stream
  chunks are plain-keyed protocol structures whose plain keyspace is
  reserved — see assay.spec for the full schemas."
  (:require [charred.api :as json]
            [assay.config :as config]
            [assay.provider :as provider]
            [assay.spec :as spec]
            ;; Loading the bundled adapters registers their multimethods.
            [assay.providers.anthropic]
            [assay.providers.ollama]
            [assay.providers.openai]))

(def default-max-tool-rounds 10)

(defn read-config
  "Read a config EDN file; see assay.config/read-config."
  ([source] (config/read-config source))
  ([source opts] (config/read-config source opts)))

;; ---------------------------------------------------------------------------
;; Request normalization

(defn- ->request [prompt-or-request]
  (cond
    (string? prompt-or-request)
    {:assay/messages [{:role :user :content prompt-or-request}]}

    (map? prompt-or-request)
    (cond
      (:assay/messages prompt-or-request) prompt-or-request
      (:assay/prompt prompt-or-request) (-> prompt-or-request
                                            (dissoc :assay/prompt)
                                            (assoc :assay/messages
                                                   [{:role :user
                                                     :content (:assay/prompt prompt-or-request)}]))
      :else (throw (ex-info "Request map needs :assay/messages or :assay/prompt"
                            {:type :assay/invalid-request
                             :request prompt-or-request})))

    :else
    (throw (ex-info (str "generate takes a prompt string or a request map, got: "
                         (pr-str prompt-or-request))
                    {:type :assay/invalid-request}))))

(defn- request-defaults
  "Request-level defaults from config (everything under :assay/defaults
  except the model aliases, which are resolved separately)."
  [config]
  (dissoc (:assay/defaults config) :assay/model :assay/embedding-model))

;; ---------------------------------------------------------------------------
;; Tool execution loop

(defn- find-tool [tools tool-call]
  (some #(when (= (name (:name %)) (name (:name tool-call))) %) tools))

(defn- run-tool [tools {:keys [id name] :as tool-call}]
  (let [tool (find-tool tools tool-call)
        result (try
                 (let [value ((:fn tool) (:arguments tool-call))]
                   (if (string? value) value (json/write-json-str value)))
                 (catch Exception e
                   (str "Error executing tool " name ": " (ex-message e))))]
    {:role :tool
     :tool-call-id id
     :name name
     :content result}))

(defn- executable? [tools tool-calls]
  (and (seq tool-calls)
       (every? (fn [tc] (some-> (find-tool tools tc) :fn)) tool-calls)))

(defn- add-usage [a b]
  (merge-with (fn [x y] (+ (or x 0) (or y 0))) (or a {}) (or b {})))

;; ---------------------------------------------------------------------------
;; Interaction records
;;
;; Every response doubles as an *interaction record*: it carries the fully
;; resolved :assay/request (replayable — tool :fns removed), :assay/latency-ms,
;; :assay/started-at and :assay/op alongside the result. Records are the raw
;; material for evals (see assay.eval): collect them from live traffic by
;; setting :assay/on-interaction under :assay/defaults in config (or per call)
;; to a function of one record.

(defn- request-record [request]
  (cond-> (dissoc request :assay/on-chunk :assay/on-interaction)
    (:assay/tools request) (update :assay/tools (partial mapv #(dissoc % :fn)))))

(defn- finish-record [response op request started-at start-nanos]
  (let [response (assoc response
                        :assay/op op
                        :assay/request (request-record request)
                        :assay/started-at started-at
                        :assay/latency-ms (quot (- (System/nanoTime) start-nanos)
                                                1000000))]
    (when-let [on-interaction (:assay/on-interaction request)]
      (try (on-interaction response) (catch Exception _ nil)))
    response))

(defn generate
  "Generate a response from an LLM. Stateless: takes the config and a
  prompt string or request map, returns a response map.

  Request keys (all optional except one of :assay/messages,
  :assay/prompt):
    :assay/model       alias keyword, \"provider/model-id\" string, or
                       #:assay{:provider <name> :model \"id\"} — defaults
                       to the :assay/model alias under :assay/defaults
    :assay/messages    the conversation so far (vector of {:role :content})
    :assay/prompt      shorthand for a single user message
    :assay/system      system prompt
    :assay/max-tokens, :assay/temperature
    :assay/tools       tool maps {:name :description :parameters :fn};
                       when every requested tool has a :fn it is invoked
                       and the conversation continues automatically
                       (bounded by :assay/max-tool-rounds, default 10).
                       Tools without :fn are returned to you under
                       :assay/tool-calls to handle manually.
    :assay/on-chunk    (fn [{:keys [type text]}]) — called with each
                       streamed chunk; :type is :text today and new types
                       may appear, so ignore chunks you don't recognize.
                       The full response is still returned.
    :assay/on-interaction  (fn [response]) — called with the finished
                       response record; usually set once under
                       :assay/defaults in config to collect interactions
                       for evals
    :assay/options     provider-specific map merged into the wire request

  Unqualified keys and your own namespaced keys are never interpreted by
  the library and flow through to the :assay/request record untouched.

  A third argument merges into the request, so
  (generate config \"hi\" {:assay/model :fast}) works.

  Returns:
    #:assay{:text          the assistant's reply
            :messages      full conversation including the reply (and any
                           tool rounds) — conj your next user message
                           onto this
            :tool-calls    unhandled tool calls, if any
            :model         model id as reported by the provider
            :provider      provider name keyword
            :usage         {:input-tokens n :output-tokens n} summed over rounds
            :finish-reason :stop | :length | :tool-calls | :refusal | ...
            :request       the fully resolved request (replayable; tool
                           :fns removed) — with :assay/latency-ms,
                           :assay/started-at and :assay/op this makes
                           every response a complete interaction record
            :latency-ms    wall-clock time for the call (all tool rounds)
            :started-at    java.time.Instant when the call began
            :op            :generate
            :raw           the provider's parsed wire response (last round)}"
  ([config prompt-or-request]
   (generate config prompt-or-request nil))
  ([config prompt-or-request opts]
   (spec/assert-config! config)
   (let [request (spec/assert-request!
                  (merge (request-defaults config)
                         (->request prompt-or-request)
                         opts))
         {:keys [provider model]} (config/resolve-model config (:assay/model request))
         request (assoc request :assay/model model)
         max-rounds (or (:assay/max-tool-rounds request) default-max-tool-rounds)
         tools (:assay/tools request)
         started-at (java.time.Instant/now)
         start-nanos (System/nanoTime)
         result (loop [messages (vec (:assay/messages request))
                       usage nil
                       round 0]
                  (let [response (provider/generate! provider
                                                     (assoc request :assay/messages messages)
                                                     {})
                        message (:message response)
                        messages (conj messages message)
                        usage (add-usage usage (:usage response))
                        tool-calls (:tool-calls message)]
                    (if (and (executable? tools tool-calls) (< round max-rounds))
                      (recur (into messages (map #(run-tool tools %)) tool-calls)
                             usage
                             (inc round))
                      (cond-> #:assay{:text (:content message)
                                      :messages messages
                                      :model (:model response)
                                      :provider (config/provider-name provider)
                                      :usage usage
                                      :finish-reason (:finish-reason response)
                                      :raw (:raw response)}
                        (seq tool-calls) (assoc :assay/tool-calls tool-calls)))))]
     (finish-record result :generate request started-at start-nanos))))

(defn embed
  "Compute embeddings for a string or a sequence of strings. The model
  defaults to the :assay/embedding-model alias under :assay/defaults in
  config; override with {:assay/model ...} in opts.

  Returns #:assay{:embeddings [[floats] ...] :model ... :provider ...
  :usage ...}, plus :assay/embedding (the single vector) when the input
  was a single string. Like generate, the response doubles as an
  interaction record (:assay/request, :assay/latency-ms,
  :assay/started-at, :assay/op :embed) and is passed to the
  :assay/on-interaction hook (from opts or config :assay/defaults)."
  ([config input] (embed config input nil))
  ([config input opts]
   (spec/assert-config! config)
   (let [{:keys [provider model]} (config/resolve-model config (:assay/model opts)
                                                        :assay/embedding-model)
         inputs (if (string? input) [input] (vec input))
         request (spec/assert-embed-request!
                  (merge (when-let [hook (get-in config [:assay/defaults :assay/on-interaction])]
                           {:assay/on-interaction hook})
                         (dissoc opts :assay/model)
                         {:assay/model model :assay/input inputs}))
         started-at (java.time.Instant/now)
         start-nanos (System/nanoTime)
         response (provider/embed! provider (dissoc request :assay/on-interaction) {})
         result (cond-> #:assay{:embeddings (:embeddings response)
                                :model (:model response)
                                :provider (config/provider-name provider)
                                :usage (:usage response)
                                :raw (:raw response)}
                  (string? input) (assoc :assay/embedding (first (:embeddings response))))]
     (finish-record result :embed request started-at start-nanos))))
