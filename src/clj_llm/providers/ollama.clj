(ns clj-llm.providers.ollama
  "Adapter for Ollama's native API (/api/chat, /api/embed) — for models
  running locally or on your own hardware. The native API is preferred
  over Ollama's OpenAI-compatibility shim because it exposes model
  options and embeddings more completely, but nothing stops you from
  pointing an :openai provider at http://localhost:11434/v1 instead.

  Provider config keys (adapter-owned, unqualified by design):
    :llm/adapter  :ollama
    :base-url       optional, defaults to http://localhost:11434
    :headers        optional map of extra headers
    :timeout-ms     optional request timeout (local models can be slow to
                    load — the default is 120s)"
  (:require [cheshire.core :as json]
            [clj-llm.http :as http]
            [clj-llm.provider :as provider]))

;; ---------------------------------------------------------------------------
;; Request building

(defn- tool->wire [{:keys [name description parameters]}]
  {:type "function"
   :function {:name (clojure.core/name name)
              :description description
              :parameters parameters}})

(defn- message->wire [{:keys [role content tool-calls] :as _message}]
  (case role
    :tool {:role "tool" :content content}
    :assistant (cond-> {:role "assistant" :content (or content "")}
                 (seq tool-calls)
                 (assoc :tool_calls
                        (mapv (fn [{:keys [name arguments]}]
                                {:function {:name name :arguments arguments}})
                              tool-calls)))
    {:role (name role) :content content}))

(defn build-request
  "Build the wire-format request body (a map ready to be sent as JSON)."
  ([request] (build-request request {}))
  ([{:llm/keys [model messages system max-tokens temperature tools options]}
    {:keys [stream?]}]
   (let [messages (if system
                    (into [{:role :system :content system}]
                          (remove #(= :system (:role %)) messages))
                    messages)
         model-options (cond-> (:model-options options {})
                         temperature (assoc :temperature temperature)
                         max-tokens (assoc :num_predict max-tokens))]
     (cond-> {:model model
              :messages (mapv message->wire messages)
              :stream (boolean stream?)}
       (seq tools) (assoc :tools (mapv tool->wire tools))
       (seq model-options) (assoc :options model-options)
       :always (provider/merge-options (dissoc options :model-options))))))

;; ---------------------------------------------------------------------------
;; Response parsing

(defn- finish-reason [done-reason tool-calls?]
  (cond
    tool-calls? :tool-calls
    (= "stop" done-reason) :stop
    (= "length" done-reason) :length
    (nil? done-reason) :stop
    :else (keyword done-reason)))

(defn parse-response
  "Normalize a (parsed) /api/chat response body."
  [body]
  (let [message (:message body)
        tool-calls (vec (map-indexed
                         (fn [i tc]
                           {:id (str "call_" i)
                            :name (get-in tc [:function :name])
                            :arguments (get-in tc [:function :arguments])})
                         (:tool_calls message)))]
    {:message (cond-> {:role :assistant :content (or (:content message) "")}
                (seq tool-calls) (assoc :tool-calls tool-calls))
     :model (:model body)
     :usage {:input-tokens (:prompt_eval_count body)
             :output-tokens (:eval_count body)}
     :finish-reason (finish-reason (:done_reason body) (seq tool-calls))
     :raw body}))

;; ---------------------------------------------------------------------------
;; Streaming (newline-delimited JSON)

(def initial-stream-state
  {:content "" :tool-calls [] :model nil :done nil})

(defn reduce-chunk
  "Fold one parsed NDJSON chunk into the stream accumulator, invoking
  on-chunk with {:type :text :text delta} for each content delta."
  [state chunk on-chunk]
  (let [text (get-in chunk [:message :content])
        state (cond-> state
                (:model chunk) (assoc :model (:model chunk))
                (seq (get-in chunk [:message :tool_calls]))
                (update :tool-calls into (get-in chunk [:message :tool_calls]))
                (:done chunk) (assoc :done chunk))]
    (if (seq text)
      (do (when on-chunk (on-chunk {:type :text :text text}))
          (update state :content str text))
      state)))

(defn finalize-stream
  "Assemble the accumulated stream state into the same normalized shape
  as parse-response."
  [{:keys [content tool-calls model done]}]
  (parse-response {:message {:role "assistant"
                             :content content
                             :tool_calls tool-calls}
                   :model model
                   :done_reason (:done_reason done)
                   :prompt_eval_count (:prompt_eval_count done)
                   :eval_count (:eval_count done)}))

;; ---------------------------------------------------------------------------
;; Adapter implementation

(defn- base-url [provider-config]
  (or (:base-url provider-config) "http://localhost:11434"))

(defmethod provider/-generate! :ollama
  [provider-config {:llm/keys [on-chunk] :as request} _opts]
  (let [http-req {:url (str (base-url provider-config) "/api/chat")
                  :headers (:headers provider-config)
                  :timeout-ms (:timeout-ms provider-config)
                  :body (build-request request {:stream? (boolean on-chunk)})}]
    (if on-chunk
      (-> (http/post-json-lines
           http-req
           (fn [state line]
             (reduce-chunk state (json/parse-string line true) on-chunk))
           initial-stream-state)
          finalize-stream)
      (-> (http/post-json http-req) :body parse-response))))

(defmethod provider/-embed! :ollama
  [provider-config {:llm/keys [model input options]} _opts]
  (let [{:keys [body]} (http/post-json
                        {:url (str (base-url provider-config) "/api/embed")
                         :headers (:headers provider-config)
                         :timeout-ms (:timeout-ms provider-config)
                         :body (provider/merge-options {:model model :input input} options)})]
    {:embeddings (vec (:embeddings body))
     :model (:model body)
     :usage {:input-tokens (:prompt_eval_count body)}
     :raw body}))
