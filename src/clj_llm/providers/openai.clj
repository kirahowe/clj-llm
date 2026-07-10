(ns clj-llm.providers.openai
  "Adapter for the OpenAI chat-completions protocol. Because the protocol
  is a de-facto standard, this one adapter covers OpenAI itself plus
  OpenRouter, Groq, Together, Mistral's La Plateforme, vLLM, LM Studio,
  llama.cpp server and anything else exposing /chat/completions — just
  point :base-url at the service.

  Provider config keys (adapter-owned, unqualified by design):
    :clj-llm/adapter       :openai
    :api-key             usually required — e.g. #env OPENAI_API_KEY;
                         may be omitted for local servers that don't check auth
    :base-url            optional, defaults to https://api.openai.com/v1
    :headers             optional map of extra headers
    :timeout-ms          optional request timeout
    :legacy-max-tokens?  optional; the request's max-tokens is sent as
                         max_completion_tokens (the current protocol field)
                         by default — set true for older OpenAI-compatible
                         servers that only understand max_tokens"
  (:require [charred.api :as json]
            [clj-llm.http :as http]
            [clj-llm.provider :as provider]))

;; ---------------------------------------------------------------------------
;; Request building

(defn- tool->wire [{:keys [name description parameters]}]
  {:type "function"
   :function {:name (clojure.core/name name)
              :description description
              :parameters parameters}})

(defn- message->wire [{:keys [role content tool-calls tool-call-id] :as _message}]
  (case role
    :tool {:role "tool"
           :tool_call_id tool-call-id
           :content content}
    :assistant (cond-> {:role "assistant" :content content}
                 (seq tool-calls)
                 (assoc :tool_calls
                        (mapv (fn [{:keys [id name arguments]}]
                                {:id id
                                 :type "function"
                                 :function {:name name
                                            :arguments (json/write-json-str arguments)}})
                              tool-calls)))
    {:role (name role) :content content}))

(defn build-request
  "Build the wire-format request body (a map ready to be sent as JSON)."
  ([request] (build-request request {}))
  ([{:clj-llm/keys [model messages system max-tokens temperature tools options]}
    {:keys [stream? legacy-max-tokens?]}]
   (let [messages (if (and system (not-any? #(= :system (:role %)) messages))
                    (into [{:role :system :content system}] messages)
                    messages)]
     (cond-> {:model model
              :messages (mapv message->wire messages)}
       max-tokens (assoc (if legacy-max-tokens? :max_tokens :max_completion_tokens)
                         max-tokens)
       temperature (assoc :temperature temperature)
       (seq tools) (assoc :tools (mapv tool->wire tools))
       stream? (assoc :stream true
                      :stream_options {:include_usage true})
       options (merge options)))))

;; ---------------------------------------------------------------------------
;; Response parsing

(defn- finish-reason [reason]
  (case reason
    "stop" :stop
    "length" :length
    "tool_calls" :tool-calls
    "content_filter" :refusal
    (keyword (or reason "unknown"))))

(defn- parse-arguments [arguments]
  (cond
    (map? arguments) arguments
    (or (nil? arguments) (= "" arguments)) {}
    :else (json/read-json arguments :key-fn keyword)))

(defn parse-response
  "Normalize a (parsed) chat-completions response body."
  [body]
  (let [choice (first (:choices body))
        message (:message choice)
        tool-calls (mapv (fn [tc]
                           {:id (:id tc)
                            :name (get-in tc [:function :name])
                            :arguments (parse-arguments
                                        (get-in tc [:function :arguments]))})
                         (:tool_calls message))]
    {:message (cond-> {:role :assistant :content (or (:content message) "")}
                (seq tool-calls) (assoc :tool-calls tool-calls))
     :model (:model body)
     :usage {:input-tokens (get-in body [:usage :prompt_tokens])
             :output-tokens (get-in body [:usage :completion_tokens])}
     :finish-reason (finish-reason (:finish_reason choice))
     :raw body}))

;; ---------------------------------------------------------------------------
;; Streaming (server-sent events; terminated by "data: [DONE]")

(def initial-stream-state
  {:content "" :tool-calls (sorted-map) :model nil :finish-reason nil :usage nil})

(defn reduce-chunk
  "Fold one parsed SSE chunk into the stream accumulator, invoking
  on-chunk with {:type :text :text delta} for each content delta."
  [state chunk on-chunk]
  (let [choice (first (:choices chunk))
        delta (:delta choice)
        state (cond-> state
                (:model chunk) (assoc :model (:model chunk))
                (:usage chunk) (assoc :usage (:usage chunk))
                (:finish_reason choice) (assoc :finish-reason (:finish_reason choice)))]
    (as-> state state
      (if-let [text (:content delta)]
        (do (when on-chunk (on-chunk {:type :text :text text}))
            (update state :content str text))
        state)
      (reduce (fn [state tc]
                (let [i (:index tc)]
                  (update-in state [:tool-calls i]
                             (fn [acc]
                               (-> (or acc {:arguments ""})
                                   (cond->
                                    (:id tc) (assoc :id (:id tc))
                                    (get-in tc [:function :name])
                                    (assoc :name (get-in tc [:function :name])))
                                   (update :arguments str
                                           (get-in tc [:function :arguments])))))))
              state
              (:tool_calls delta)))))

(defn finalize-stream
  "Assemble the accumulated stream state into the same normalized shape
  as parse-response."
  [{:keys [content tool-calls model finish-reason usage]}]
  (parse-response
   {:choices [{:message {:content content
                         :tool_calls (mapv (fn [[_ tc]]
                                             {:id (:id tc)
                                              :function {:name (:name tc)
                                                         :arguments (:arguments tc)}})
                                           tool-calls)}
               :finish_reason finish-reason}]
    :model model
    :usage usage}))

;; ---------------------------------------------------------------------------
;; Adapter implementation

(defn- base-url [provider-config]
  (or (:base-url provider-config) "https://api.openai.com/v1"))

(defn- headers [provider-config]
  (merge (when-let [key (:api-key provider-config)]
           {"authorization" (str "Bearer " key)})
         (:headers provider-config)))

(defmethod provider/generate! :openai
  [provider-config {:clj-llm/keys [on-chunk] :as request} _opts]
  (let [http-req {:url (str (base-url provider-config) "/chat/completions")
                  :headers (headers provider-config)
                  :timeout-ms (:timeout-ms provider-config)
                  :body (build-request request
                                       {:stream? (boolean on-chunk)
                                        :legacy-max-tokens? (:legacy-max-tokens? provider-config)})}]
    (if on-chunk
      (-> (http/post-json-lines
           http-req
           (fn [state line]
             (let [data (http/sse-data line)]
               (if (or (nil? data) (= "[DONE]" data))
                 state
                 (reduce-chunk state (json/read-json data :key-fn keyword) on-chunk))))
           initial-stream-state)
          finalize-stream)
      (-> (http/post-json http-req) :body parse-response))))

(defmethod provider/embed! :openai
  [provider-config {:clj-llm/keys [model input options]} _opts]
  (let [{:keys [body]} (http/post-json
                        {:url (str (base-url provider-config) "/embeddings")
                         :headers (headers provider-config)
                         :timeout-ms (:timeout-ms provider-config)
                         :body (merge {:model model :input input} options)})]
    {:embeddings (->> (:data body) (sort-by :index) (mapv :embedding))
     :model (:model body)
     :usage {:input-tokens (get-in body [:usage :prompt_tokens])}
     :raw body}))
