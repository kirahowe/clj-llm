(ns clj-llm.core
  "A small, functional, provider-agnostic library for calling LLMs, with
  evals built in from the ground up.

  Everything is plain data: configuration is an EDN map (loaded from a
  file — see clj-llm.config), a conversation is a vector of message maps,
  and every function here is stateless — pass the config in, get a value
  back. That makes the library context agnostic: use it from a web
  handler, a CLI, a background job, or a one-off REPL session.

    (require '[clj-llm.core :as llm])

    (def config (llm/read-config \"llm.edn\"))

    ;; zero-shot
    (llm/generate config \"Why is the sky blue?\")
    ;; => #:llm{:text \"...\" :messages [...] :usage {...} ...}

    ;; multi-turn is just data — thread :llm/messages back in
    (let [r1 (llm/generate config \"Name a prime number.\")]
      (llm/generate config {:llm/messages (conj (:llm/messages r1)
                                                {:role :user :content \"Why is it prime?\"})}))

    ;; streaming — chunks carry a :type so future chunk kinds can be
    ;; introduced without breaking existing callbacks; ignore types you
    ;; don't recognize
    (llm/generate config \"Tell me a story\"
                  {:llm/on-chunk (fn [{:keys [type text]}]
                                   (when (= :text type) (print text) (flush)))})

    ;; tools — maps with a :fn are executed in an automatic loop
    (llm/generate config \"What's the weather in Berlin?\"
                  {:llm/tools [{:name \"get-weather\"
                                :description \"Look up current weather for a city\"
                                :parameters {:type \"object\"
                                             :properties {:city {:type \"string\"}}
                                             :required [\"city\"]}
                                :fn (fn [{:keys [city]}] (fetch-weather city))}]})

    ;; embeddings
    (llm/embed config \"some text\")

  Keyspace: every key the library defines in the maps you author or
  store (config, requests, responses, eval suites) is namespaced
  :llm/...; unqualified keys and your own namespaced keys in those
  maps are yours forever. Messages, tools, tool calls, usage and stream
  chunks are plain-keyed protocol structures whose plain keyspace is
  reserved — see clj-llm.spec for the full schemas."
  (:require [cheshire.core :as json]
            [clj-llm.config :as config]
            [clj-llm.provider :as provider]
            [clj-llm.spec :as spec]
            ;; Loading the bundled adapters registers their multimethods.
            [clj-llm.providers.anthropic]
            [clj-llm.providers.ollama]
            [clj-llm.providers.openai]))

(def default-max-tool-rounds 10)

(defn read-config
  "Read a config EDN file; see clj-llm.config/read-config."
  ([source] (config/read-config source))
  ([source opts] (config/read-config source opts)))

;; ---------------------------------------------------------------------------
;; Request normalization

(defn- ->raw-request [prompt-or-request]
  (cond
    (string? prompt-or-request)
    {:llm/prompt prompt-or-request}

    (map? prompt-or-request)
    prompt-or-request

    :else
    (throw (ex-info (str "generate takes a prompt string or a request map, got: "
                         (pr-str prompt-or-request))
                    {:type :llm/invalid-request}))))

(defn- fold-prompt
  "Fold :llm/prompt into :llm/messages as the trailing user message, if
  present. Applied after config defaults, the positional argument and
  opts are all merged, so :llm/prompt always lands after whatever
  history :llm/messages already carries (vec first — callers may pass
  a list, and conj on a list prepends)."
  [request]
  (if-let [prompt (:llm/prompt request)]
    (-> request
        (dissoc :llm/prompt)
        (assoc :llm/messages (conj (vec (:llm/messages request))
                                   {:role :user :content prompt})))
    request))

(defn- request-defaults
  "Request-level defaults from config (everything under :llm/defaults
  except the model aliases, which are resolved separately)."
  [config]
  (dissoc (:llm/defaults config) :llm/model :llm/embedding-model))

;; ---------------------------------------------------------------------------
;; Tool execution loop

(defn- find-tool [tools tool-call]
  (some #(when (= (name (:name %)) (name (:name tool-call))) %) tools))

(defn- run-tool [tools {:keys [id name] :as tool-call}]
  (let [tool (find-tool tools tool-call)
        result (try
                 (let [value ((:fn tool) (:arguments tool-call))]
                   (if (string? value) value (json/generate-string value)))
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
;; resolved :llm/request (replayable — tool :fns removed), :llm/latency-ms,
;; :llm/started-at and :llm/op alongside the result. Records are the raw
;; material for evals (see clj-llm.eval): collect them from live traffic by
;; setting :llm/on-interaction under :llm/defaults in config (or per call)
;; to a function of one record.

(defn- request-record [request]
  (cond-> (dissoc request :llm/on-chunk :llm/on-interaction)
    (:llm/tools request) (update :llm/tools (partial mapv #(dissoc % :fn)))))

(defn- finish-record [response op request started-at start-nanos]
  (let [response (assoc response
                        :llm/op op
                        :llm/request (request-record request)
                        :llm/started-at started-at
                        :llm/latency-ms (quot (- (System/nanoTime) start-nanos)
                                              1000000))]
    (when-let [on-interaction (:llm/on-interaction request)]
      (try (on-interaction response) (catch Exception _ nil)))
    response))

(defn generate
  "Generate a response from an LLM. Stateless: takes the config and a
  prompt string or request map, returns a response map.

  Request keys (all optional except one of :llm/messages,
  :llm/prompt):
    :llm/model       alias keyword, \"provider/model-id\" string, or
                     #:llm{:provider <name> :model \"id\"} — defaults
                     to the :llm/model alias under :llm/defaults
    :llm/messages    the conversation so far (vector of {:role :content})
    :llm/prompt      appended to :llm/messages as the next user message —
                     zero-shot shorthand on its own, or a way to continue
                     a conversation when :llm/messages is also given
    :llm/system      system prompt
    :llm/max-tokens, :llm/temperature
    :llm/tools       tool maps {:name :description :parameters :fn};
                     when every requested tool has a :fn it is invoked
                     and the conversation continues automatically
                     (bounded by :llm/max-tool-rounds, default 10).
                     Tools without :fn are returned to you under
                     :llm/tool-calls to handle manually.
    :llm/on-chunk    (fn [{:keys [type text]}]) — called with each
                     streamed chunk; :type is :text today and new types
                     may appear, so ignore chunks you don't recognize.
                     The full response is still returned.
    :llm/on-interaction  (fn [response]) — called with the finished
                     response record; usually set once under
                     :llm/defaults in config to collect interactions
                     for evals
    :llm/options     provider-specific map merged into the wire request

  Unqualified keys and your own namespaced keys are never interpreted by
  the library and flow through to the :llm/request record untouched.

  A third argument merges into the request, so
  (generate config \"hi\" {:llm/model :fast}) works.

  Returns:
    #:llm{:text          the assistant's reply
          :messages      full conversation including the reply (and any
                         tool rounds) — conj your next user message
                         onto this
          :tool-calls    unhandled tool calls, if any
          :model         model id as reported by the provider
          :provider      provider name keyword
          :usage         {:input-tokens n :output-tokens n} summed over rounds
          :finish-reason :stop | :length | :tool-calls | :refusal | ...
          :request       the fully resolved request (replayable; tool
                         :fns removed) — with :llm/latency-ms,
                         :llm/started-at and :llm/op this makes
                         every response a complete interaction record
          :latency-ms    wall-clock time for the call (all tool rounds)
          :started-at    java.time.Instant when the call began
          :op            :generate
          :raw           the provider's parsed wire response (last round)}"
  ([config prompt-or-request]
   (generate config prompt-or-request nil))
  ([config prompt-or-request opts]
   (spec/assert-config! config)
   (let [request (fold-prompt (merge (request-defaults config)
                                     (->raw-request prompt-or-request)
                                     opts))
         _ (when (empty? (:llm/messages request))
             (throw (ex-info "Request needs :llm/messages or :llm/prompt"
                             {:type :llm/invalid-request :request request})))
         request (spec/assert-request! request)
         {:keys [provider model]} (config/resolve-model config (:llm/model request))
         request (assoc request :llm/model model)
         max-rounds (or (:llm/max-tool-rounds request) default-max-tool-rounds)
         tools (:llm/tools request)
         started-at (java.time.Instant/now)
         start-nanos (System/nanoTime)
         result (loop [messages (vec (:llm/messages request))
                       usage nil
                       round 0]
                  (let [response (provider/generate! provider
                                                     (assoc request :llm/messages messages))
                        message (:message response)
                        messages (conj messages message)
                        usage (add-usage usage (:usage response))
                        tool-calls (:tool-calls message)]
                    (if (and (executable? tools tool-calls) (< round max-rounds))
                      (recur (into messages (map #(run-tool tools %)) tool-calls)
                             usage
                             (inc round))
                      (cond-> #:llm{:text (:content message)
                                    :messages messages
                                    :model (:model response)
                                    :provider (config/provider-name provider)
                                    :usage usage
                                    :finish-reason (:finish-reason response)
                                    :raw (:raw response)}
                        (seq tool-calls) (assoc :llm/tool-calls tool-calls)))))]
     (finish-record result :generate request started-at start-nanos))))

(defn embed
  "Compute embeddings for a string or a sequence of strings. The model
  defaults to the :llm/embedding-model alias under :llm/defaults in
  config; override with {:llm/model ...} in opts.

  Returns #:llm{:embeddings [[floats] ...] :model ... :provider ...
  :usage ...}, plus :llm/embedding (the single vector) when the input
  was a single string. Like generate, the response doubles as an
  interaction record (:llm/request, :llm/latency-ms,
  :llm/started-at, :llm/op :embed) and is passed to the
  :llm/on-interaction hook (from opts or config :llm/defaults)."
  ([config input] (embed config input nil))
  ([config input opts]
   (spec/assert-config! config)
   (let [{:keys [provider model]} (config/resolve-model config (:llm/model opts)
                                                        :llm/embedding-model)
         inputs (if (string? input) [input] (vec input))
         request (spec/assert-embed-request!
                  (merge (when-let [hook (get-in config [:llm/defaults :llm/on-interaction])]
                           {:llm/on-interaction hook})
                         (dissoc opts :llm/model)
                         {:llm/model model :llm/input inputs}))
         started-at (java.time.Instant/now)
         start-nanos (System/nanoTime)
         response (provider/embed! provider (dissoc request :llm/on-interaction))
         result (cond-> #:llm{:embeddings (:embeddings response)
                              :model (:model response)
                              :provider (config/provider-name provider)
                              :usage (:usage response)
                              :raw (:raw response)}
                  (string? input) (assoc :llm/embedding (first (:embeddings response))))]
     (finish-record result :embed request started-at start-nanos))))
