(ns kirahowe.clj-llm.providers.anthropic
  "Adapter for the Anthropic Messages API (api.anthropic.com/v1/messages).

  Provider config keys:
    :adapter    :anthropic
    :api-key    required — usually #env ANTHROPIC_API_KEY
    :base-url   optional, defaults to https://api.anthropic.com
    :version    optional anthropic-version header, defaults to 2023-06-01
    :headers    optional map of extra headers (e.g. anthropic-beta)
    :timeout-ms optional request timeout"
  (:require [charred.api :as json]
            [clojure.string :as str]
            [kirahowe.clj-llm.http :as http]
            [kirahowe.clj-llm.provider :as provider]))

(def default-max-tokens 4096)

;; ---------------------------------------------------------------------------
;; Request building

(defn- tool->wire [{:keys [name description parameters]}]
  {:name (clojure.core/name name)
   :description description
   :input_schema parameters})

(defn- assistant->wire [{:keys [content tool-calls]}]
  {:role "assistant"
   :content (cond-> []
              (not (str/blank? content)) (conj {:type "text" :text content})
              :always (into (map (fn [{:keys [id name arguments]}]
                                   {:type "tool_use"
                                    :id id
                                    :name name
                                    :input arguments})
                                 tool-calls)))})

(defn- tool-results->wire
  "Anthropic expects tool results as tool_result blocks in a single user
  message; batching parallel results into one message is required for the
  model to keep making parallel calls."
  [tool-messages]
  {:role "user"
   :content (mapv (fn [{:keys [tool-call-id content]}]
                    {:type "tool_result"
                     :tool_use_id tool-call-id
                     :content content})
                  tool-messages)})

(defn messages->wire [messages]
  (->> messages
       (remove #(= :system (:role %)))
       (partition-by #(= :tool (:role %)))
       (mapcat (fn [group]
                 (if (= :tool (:role (first group)))
                   [(tool-results->wire group)]
                   (map (fn [{:keys [role content] :as m}]
                          (if (and (= :assistant role) (seq (:tool-calls m)))
                            (assistant->wire m)
                            {:role (name role) :content content}))
                        group))))
       vec))

(defn build-request
  "Build the wire-format request body (a map ready to be sent as JSON)."
  [{:keys [model messages system max-tokens temperature tools stream options]}]
  (let [system (or system
                   (some #(when (= :system (:role %)) (:content %)) messages))]
    (cond-> {:model model
             :max_tokens (or max-tokens default-max-tokens)
             :messages (messages->wire messages)}
      system (assoc :system system)
      temperature (assoc :temperature temperature)
      (seq tools) (assoc :tools (mapv tool->wire tools))
      stream (assoc :stream true)
      options (merge options))))

;; ---------------------------------------------------------------------------
;; Response parsing

(defn- finish-reason [stop-reason]
  (case stop-reason
    "end_turn" :stop
    "stop_sequence" :stop
    "max_tokens" :length
    "tool_use" :tool-calls
    "refusal" :refusal
    (keyword (or stop-reason "unknown"))))

(defn parse-response
  "Normalize a (parsed) Messages API response body."
  [body]
  (let [blocks (:content body)
        text (->> blocks
                  (filter #(= "text" (:type %)))
                  (map :text)
                  (str/join))
        tool-calls (->> blocks
                        (filter #(= "tool_use" (:type %)))
                        (mapv (fn [{:keys [id name input]}]
                                {:id id :name name :arguments input})))]
    {:message (cond-> {:role :assistant :content text}
                (seq tool-calls) (assoc :tool-calls tool-calls))
     :model (:model body)
     :usage {:input-tokens (get-in body [:usage :input_tokens])
             :output-tokens (get-in body [:usage :output_tokens])}
     :finish-reason (finish-reason (:stop_reason body))
     :raw body}))

;; ---------------------------------------------------------------------------
;; Streaming (server-sent events)

(def initial-stream-state
  {:blocks (sorted-map) :model nil :stop-reason nil :usage {}})

(defn reduce-event
  "Fold one parsed SSE event into the stream accumulator, invoking
  on-chunk with {:text delta} for each text delta."
  [state {:keys [type index] :as event} on-chunk]
  (case type
    "message_start"
    (-> state
        (assoc :model (get-in event [:message :model]))
        (update :usage assoc :input-tokens (get-in event [:message :usage :input_tokens])))

    "content_block_start"
    (let [block (:content_block event)]
      (assoc-in state [:blocks index]
                (case (:type block)
                  "text" {:type "text" :text (or (:text block) "")}
                  "tool_use" {:type "tool_use" :id (:id block)
                              :name (:name block) :json ""}
                  {:type (:type block)})))

    "content_block_delta"
    (let [delta (:delta event)]
      (case (:type delta)
        "text_delta"
        (do (when on-chunk (on-chunk {:text (:text delta)}))
            (update-in state [:blocks index :text] str (:text delta)))
        "input_json_delta"
        (update-in state [:blocks index :json] str (:partial_json delta))
        state))

    "message_delta"
    (-> state
        (assoc :stop-reason (get-in event [:delta :stop_reason]))
        (update :usage assoc :output-tokens (get-in event [:usage :output_tokens])))

    "error"
    (throw (ex-info (str "Anthropic stream error: "
                         (get-in event [:error :message]))
                    {:type ::stream-error :event event}))

    state))

(defn finalize-stream
  "Assemble the accumulated stream state into the same normalized shape
  as parse-response."
  [{:keys [blocks model stop-reason usage]}]
  (let [content (mapv (fn [[_ b]]
                        (if (= "tool_use" (:type b))
                          {:type "tool_use"
                           :id (:id b)
                           :name (:name b)
                           :input (if (str/blank? (:json b))
                                    {}
                                    (json/read-json (:json b) :key-fn keyword))}
                          b))
                      blocks)]
    (parse-response {:content content
                     :model model
                     :stop_reason stop-reason
                     :usage {:input_tokens (:input-tokens usage)
                             :output_tokens (:output-tokens usage)}})))

;; ---------------------------------------------------------------------------
;; Adapter implementation

(defn- api-key! [provider-config]
  (or (:api-key provider-config)
      (throw (ex-info (str "Provider " (or (:kirahowe.clj-llm.config/name provider-config)
                                           ":anthropic")
                           " has no :api-key. Set it in your config file, e.g. "
                           ":api-key #env ANTHROPIC_API_KEY")
                      {:type ::missing-api-key}))))

(defn- endpoint [provider-config]
  (str (or (:base-url provider-config) "https://api.anthropic.com")
       "/v1/messages"))

(defn- headers [provider-config]
  (merge {"x-api-key" (api-key! provider-config)
          "anthropic-version" (or (:version provider-config) "2023-06-01")}
         (:headers provider-config)))

(defmethod provider/generate! :anthropic
  [provider-config {:keys [on-chunk] :as request}]
  (let [http-req {:url (endpoint provider-config)
                  :headers (headers provider-config)
                  :timeout-ms (:timeout-ms provider-config)
                  :body (build-request (assoc request :stream (boolean on-chunk)))}]
    (if on-chunk
      (-> (http/post-json-lines
           http-req
           (fn [state line]
             (if-let [data (http/sse-data line)]
               (reduce-event state (json/read-json data :key-fn keyword) on-chunk)
               state))
           initial-stream-state)
          finalize-stream)
      (-> (http/post-json http-req) :body parse-response))))

(defmethod provider/embed! :anthropic
  [provider-config _]
  (throw (ex-info (str "Anthropic has no embeddings API. Configure an :openai "
                       "or :ollama provider for embeddings.")
                  {:type ::unsupported
                   :provider (:kirahowe.clj-llm.config/name provider-config)})))
