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
            [clojure.string :as str]
            [clj-llm.config :as config]
            [clj-llm.provider :as provider]
            [clj-llm.spec :as spec]
            ;; Loading the bundled adapters registers their multimethods.
            [clj-llm.providers.anthropic]
            [clj-llm.providers.ollama]
            [clj-llm.providers.openai])
  (:import (java.io BufferedReader StringReader)))

(def default-max-tool-rounds 10)
(def default-max-tool-calls 10)

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

(defn- tool-metadata [tool]
  (dissoc tool :fn))

(defn- rejection
  ([tool-call reason]
   {:tool-call tool-call :reason reason})
  ([tool-call reason key value]
   {:tool-call tool-call :reason reason key value}))

(defn- hook-rejection [hook args tool-call rejected-reason error-reason]
  (when hook
    (try
      (when-not (apply hook args)
        (rejection tool-call rejected-reason))
      (catch Exception e
        (rejection tool-call error-reason
                   :message (or (ex-message e) (str e)))))))

(defn- preflight-tool-batch
  [tools tool-calls policy argument-validator max-tool-calls tool-call-count]
  (let [resolved (mapv (fn [tool-call]
                         {:tool-call tool-call
                          :tool (find-tool tools tool-call)})
                       tool-calls)
        missing (keep (fn [{:keys [tool-call tool]}]
                        (when-not tool
                          (rejection tool-call :tool-not-found)))
                      resolved)]
    (cond
      (seq missing)
      {:status :rejected :rejections (vec missing)}

      (some (comp not :fn :tool) resolved)
      {:status :manual}

      (> (count tool-calls) (- max-tool-calls tool-call-count))
      (let [budget {:limit max-tool-calls
                    :used tool-call-count
                    :requested (count tool-calls)
                    :remaining (- max-tool-calls tool-call-count)}]
        {:status :rejected
         :rejections (mapv #(rejection % :tool-call-budget-exceeded
                                       :budget budget)
                           tool-calls)})

      :else
      (let [rejections
            (keep (fn [{:keys [tool-call tool]}]
                    (let [metadata (tool-metadata tool)]
                      (or (hook-rejection policy
                                          [tool-call metadata]
                                          tool-call
                                          :tool-policy-rejected
                                          :tool-policy-error)
                          (hook-rejection argument-validator
                                          [(:arguments tool-call) metadata]
                                          tool-call
                                          :tool-arguments-rejected
                                          :tool-argument-validator-error))))
                  resolved)]
        (if (seq rejections)
          {:status :rejected :rejections (vec rejections)}
          {:status :execute :resolved resolved})))))

(defn- run-tool [{:keys [tool-call tool]}]
  (let [{:keys [id name]} tool-call
        result (try
                 (let [value ((:fn tool) (:arguments tool-call))]
                   (if (string? value) value (json/generate-string value)))
                 (catch Exception _
                   (str "Error executing tool " name)))]
    {:role :tool
     :tool-call-id id
     :name name
     :content result}))

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
  (cond-> (dissoc request
                  :llm/on-chunk
                  :llm/on-interaction
                  :llm/tool-policy
                  :llm/tool-argument-validator)
    (:llm/tools request) (update :llm/tools
                                 (partial mapv tool-metadata))))

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

(defn- parse-structured-response [response request]
  (if (and (:llm/response-format request)
           (not (seq (:llm/tool-calls response))))
    (try
      (let [text (:llm/text response)]
        (when (str/blank? text)
          (throw (ex-info "Structured response text is blank" {})))
        (with-open [reader (BufferedReader. (StringReader. text))]
          (let [values (doall (json/parsed-seq reader true))]
            (when-not (= 1 (count values))
              (throw (ex-info "Structured response has multiple JSON values" {})))
            (assoc response :llm/structured (first values)))))
      (catch Exception e
        (assoc response :llm/structured-error
               {:type :llm/invalid-structured-response
                :message (or (ex-message e) (str e))})))
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
                     and the conversation continues automatically.
                     Automatic batches are all-or-none and bounded by
                     :llm/max-tool-rounds and :llm/max-tool-calls,
                     both defaulting to 10. Tools without :fn are
                     returned under :llm/tool-calls for manual handling.
    :llm/tool-policy (fn [tool-call tool-metadata]) called before each
                     automatic invocation; false/nil rejects the batch
    :llm/tool-argument-validator
                     (fn [arguments tool-metadata]); false/nil rejects
                     the batch. :parameters is not runtime validation.
    :llm/on-chunk    (fn [{:keys [type text]}]) — called with each
                     streamed chunk; :type is :text today and new types
                     may appear, so ignore chunks you don't recognize.
                     The full response is still returned.
    :llm/on-interaction  (fn [response]) — called with the finished
                     response record; usually set once under
                     :llm/defaults in config to collect interactions
                     for evals
    :llm/response-format  {:type :json-schema :name \"name\" :schema {...}}
                     requests a portable JSON Schema response; terminal
                     text is decoded under :llm/structured, or a decoding
                     error is returned under :llm/structured-error
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
          :tool-rejections local-only rejection records, if automatic
                         batch preflight fails; never sent to a provider
          :provider      provider name keyword
          :usage         {:input-tokens n :output-tokens n} summed over rounds
          :finish-reason :stop | :length | :tool-calls | :refusal | ...
          :structured    decoded JSON for a structured terminal answer
          :structured-error decoding failure data, mutually exclusive
                         with :structured
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
         max-tool-calls (or (:llm/max-tool-calls request) default-max-tool-calls)
         tools (:llm/tools request)
         policy (:llm/tool-policy request)
         argument-validator (:llm/tool-argument-validator request)
         provider-request (cond-> (dissoc request
                                          :llm/tool-policy
                                          :llm/tool-argument-validator)
                            tools (update :llm/tools
                                          (partial mapv tool-metadata)))
         started-at (java.time.Instant/now)
         start-nanos (System/nanoTime)
         result (loop [messages (vec (:llm/messages request))
                       usage nil
                       round 0
                       tool-call-count 0]
                  (let [response (provider/generate!
                                  provider
                                  (assoc provider-request :llm/messages messages))
                        message (:message response)
                        messages (conj messages message)
                        usage (add-usage usage (:usage response))
                        tool-calls (:tool-calls message)
                        terminal (cond-> #:llm{:text (:content message)
                                               :messages messages
                                               :model (:model response)
                                               :provider (config/provider-name provider)
                                               :usage usage
                                               :finish-reason (:finish-reason response)
                                               :raw (:raw response)}
                                   (seq tool-calls)
                                   (assoc :llm/tool-calls tool-calls))]
                    (if (and (seq tool-calls) (< round max-rounds))
                      (let [{:keys [status resolved rejections]}
                            (preflight-tool-batch tools tool-calls policy
                                                  argument-validator
                                                  max-tool-calls
                                                  tool-call-count)]
                        (case status
                          :execute
                          (recur (into messages (map run-tool resolved))
                                 usage
                                 (inc round)
                                 (+ tool-call-count (count tool-calls)))

                          :rejected
                          (assoc terminal :llm/tool-rejections rejections)

                          :manual
                          terminal))
                      terminal)))]
     (finish-record (parse-structured-response result request)
                    :generate request started-at start-nanos))))

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
