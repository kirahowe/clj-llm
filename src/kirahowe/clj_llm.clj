(ns kirahowe.clj-llm
  "A small, functional, provider-agnostic library for calling LLMs.

  Everything is plain data: configuration is an EDN map (loaded from a
  file — see kirahowe.clj-llm.config), a conversation is a vector of
  message maps, and every function here is stateless — pass the config
  in, get a value back. That makes the library context agnostic: use it
  from a web handler, a CLI, a background job, or a one-off REPL session.

    (require '[kirahowe.clj-llm :as llm])

    (def config (llm/read-config \"llm.edn\"))

    ;; zero-shot
    (llm/generate config \"Why is the sky blue?\")
    ;; => {:text \"...\" :messages [...] :usage {...} ...}

    ;; multi-turn is just data — thread :messages back in
    (let [r1 (llm/generate config \"Name a prime number.\")]
      (llm/generate config {:messages (conj (:messages r1)
                                            {:role :user :content \"Why is it prime?\"})}))

    ;; streaming
    (llm/generate config \"Tell me a story\"
                  {:on-chunk (fn [{:keys [text]}] (print text) (flush))})

    ;; tools — maps with a :fn are executed in an automatic loop
    (llm/generate config \"What's the weather in Berlin?\"
                  {:tools [{:name \"get-weather\"
                            :description \"Look up current weather for a city\"
                            :parameters {:type \"object\"
                                         :properties {:city {:type \"string\"}}
                                         :required [\"city\"]}
                            :fn (fn [{:keys [city]}] (fetch-weather city))}]})

    ;; embeddings
    (llm/embed config \"some text\")"
  (:require [clojure.data.json :as json]
            [kirahowe.clj-llm.config :as config]
            [kirahowe.clj-llm.provider :as provider]
            ;; Loading the bundled adapters registers their multimethods.
            [kirahowe.clj-llm.providers.anthropic]
            [kirahowe.clj-llm.providers.ollama]
            [kirahowe.clj-llm.providers.openai]))

(def default-max-tool-rounds 10)

(defn read-config
  "Read a config EDN file; see kirahowe.clj-llm.config/read-config."
  ([source] (config/read-config source))
  ([source opts] (config/read-config source opts)))

;; ---------------------------------------------------------------------------
;; Request normalization

(defn- ->request [prompt-or-request]
  (cond
    (string? prompt-or-request)
    {:messages [{:role :user :content prompt-or-request}]}

    (map? prompt-or-request)
    (cond
      (:messages prompt-or-request) prompt-or-request
      (:prompt prompt-or-request) (-> prompt-or-request
                                      (dissoc :prompt)
                                      (assoc :messages [{:role :user
                                                         :content (:prompt prompt-or-request)}]))
      :else (throw (ex-info "Request map needs :messages or :prompt"
                            {:type ::invalid-request
                             :request prompt-or-request})))

    :else
    (throw (ex-info (str "generate takes a prompt string or a request map, got: "
                         (pr-str prompt-or-request))
                    {:type ::invalid-request}))))

(defn- request-defaults
  "Request-level defaults from config (everything under :defaults except
  the model aliases, which are resolved separately)."
  [config]
  (dissoc (:defaults config) :model :embedding-model))

;; ---------------------------------------------------------------------------
;; Tool execution loop

(defn- find-tool [tools tool-call]
  (some #(when (= (name (:name %)) (name (:name tool-call))) %) tools))

(defn- run-tool [tools {:keys [id name arguments] :as tool-call}]
  (let [tool (find-tool tools tool-call)
        result (try
                 (let [value ((:fn tool) arguments)]
                   (if (string? value) value (json/write-str value)))
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

(defn generate
  "Generate a response from an LLM. Stateless: takes the config and a
  prompt string or request map, returns a response map.

  Request keys (all optional except one of :messages/:prompt):
    :model       alias keyword, \"provider/model-id\" string, or
                 {:provider <name> :model \"id\"} — defaults to the
                 :model alias under :defaults in config
    :messages    the conversation so far (vector of {:role :content})
    :prompt      shorthand for a single user message
    :system      system prompt
    :max-tokens, :temperature
    :tools       tool maps {:name :description :parameters :fn}; when
                 every requested tool has a :fn it is invoked and the
                 conversation continues automatically (bounded by
                 :max-tool-rounds, default 10). Tools without :fn are
                 returned to you under :tool-calls to handle manually.
    :on-chunk    (fn [{:keys [text]}]) — called with each streamed text
                 delta; the full response is still returned
    :options     provider-specific map merged into the wire request

  A third argument merges into the request, so
  (generate config \"hi\" {:model :fast}) works.

  Returns:
    {:text          the assistant's reply
     :messages      full conversation including the reply (and any tool
                    rounds) — conj your next user message onto this
     :tool-calls    unhandled tool calls, if any
     :model         model id as reported by the provider
     :provider      provider name keyword
     :usage         {:input-tokens n :output-tokens n} summed over rounds
     :finish-reason :stop | :length | :tool-calls | :refusal | ...
     :raw           the provider's parsed wire response (last round)}"
  ([config prompt-or-request]
   (generate config prompt-or-request nil))
  ([config prompt-or-request opts]
   (let [request (merge (request-defaults config)
                        (->request prompt-or-request)
                        opts)
         {:keys [provider model]} (config/resolve-model config (:model request))
         request (assoc request :model model)
         max-rounds (or (:max-tool-rounds request) default-max-tool-rounds)
         tools (:tools request)]
     (loop [messages (vec (:messages request))
            usage nil
            round 0]
       (let [response (provider/generate! provider (assoc request :messages messages))
             message (:message response)
             messages (conj messages message)
             usage (add-usage usage (:usage response))
             tool-calls (:tool-calls message)]
         (if (and (executable? tools tool-calls) (< round max-rounds))
           (recur (into messages (map #(run-tool tools %)) tool-calls)
                  usage
                  (inc round))
           (cond-> {:text (:content message)
                    :messages messages
                    :model (:model response)
                    :provider (config/provider-name provider)
                    :usage usage
                    :finish-reason (:finish-reason response)
                    :raw (:raw response)}
             (seq tool-calls) (assoc :tool-calls tool-calls))))))))

(defn embed
  "Compute embeddings for a string or a sequence of strings. The model
  defaults to the :embedding-model alias under :defaults in config;
  override with {:model ...} in opts.

  Returns {:embeddings [[floats] ...] :model ... :provider ... :usage ...},
  plus :embedding (the single vector) when the input was a single string."
  ([config input] (embed config input nil))
  ([config input opts]
   (let [{:keys [provider model]} (config/resolve-model config (:model opts)
                                                        :embedding-model)
         inputs (if (string? input) [input] (vec input))
         response (provider/embed! provider (merge (dissoc opts :model)
                                                   {:model model :input inputs}))]
     (cond-> (assoc response :provider (config/provider-name provider))
       (string? input) (assoc :embedding (first (:embeddings response)))))))
