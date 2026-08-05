(ns clj-llm.spec
  "Malli schemas for every public data contract — the source of truth
  for what each map looks like, and the boundary validation that turns
  a malformed input into an immediate, humanized error instead of a
  confusing one three layers down.

  Two kinds of keys appear in these contracts, by design:

  - Keys in maps that users author, store or extend — config, requests,
    responses/records, cases, variants, suites, reports — are namespaced
    :llm/... Any key that is not :llm/-qualified in those maps is
    yours, forever: the library will never assign meaning to it.

  - Protocol structures the library defines end-to-end — messages, tool
    definitions, tool calls, usage, stream chunks, scorer returns — keep
    plain, industry-familiar keys (:role, :content, :name, :score ...).
    The plain keyspace *inside these structures* is reserved: extend
    them only with namespaced keys of your own.

  All map schemas are open: extra keys always validate."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Protocol structures (plain keys, reserved)

(def Role
  (m/schema [:enum :system :user :assistant :tool]))

(def ContentPart
  "Reserved for future multimodal content: a content part is a map with
  a :type. Only string content is produced or consumed today."
  (m/schema [:map [:type :keyword]]))

(def Content
  (m/schema [:or :string [:sequential [:map [:type :keyword]]]]))

(def ToolCall
  (m/schema
   [:map
    [:id {:optional true} [:maybe :string]]
    [:name [:or :string :keyword]]
    [:arguments {:optional true} [:maybe :map]]]))

(def Message
  (m/schema
   [:map
    [:role [:enum :system :user :assistant :tool]]
    [:content {:optional true} [:maybe [:or :string [:sequential [:map [:type :keyword]]]]]]
    [:tool-calls {:optional true} [:sequential [:map
                                                [:id {:optional true} [:maybe :string]]
                                                [:name [:or :string :keyword]]
                                                [:arguments {:optional true} [:maybe :map]]]]]
    [:tool-call-id {:optional true} [:maybe :string]]
    [:name {:optional true} [:maybe [:or :string :keyword]]]]))

(def Tool
  (m/schema
   [:map
    [:name [:or :string :keyword]]
    [:description {:optional true} [:maybe :string]]
    [:parameters {:optional true} [:maybe :map]]
    [:fn {:optional true} fn?]]))

(def Usage
  (m/schema [:map-of :keyword [:maybe number?]]))

(def Chunk
  "One streaming callback payload. Today always {:type :text :text s};
  callbacks must ignore chunks whose :type they don't recognize."
  (m/schema [:map [:type :keyword] [:text {:optional true} :string]]))

;; ---------------------------------------------------------------------------
;; Configuration

(def ModelDesignator
  (m/schema
   [:or :keyword :string
    [:map [:llm/provider :keyword] [:llm/model :string]]]))

(def ProviderConfig
  (m/schema [:map [:llm/adapter :keyword]]))

(def Config
  (m/schema
   [:map
    [:llm/providers [:map-of :keyword [:map [:llm/adapter :keyword]]]]
    [:llm/models {:optional true}
     [:map-of :keyword [:or :keyword :string
                        [:map [:llm/provider :keyword] [:llm/model :string]]]]]
    [:llm/defaults {:optional true} :map]]))

;; ---------------------------------------------------------------------------
;; Requests and responses

(def Request
  "A generate request after normalization (prompt string / :llm/prompt
  already folded into :llm/messages)."
  (m/schema
   [:map
    [:llm/messages [:sequential Message]]
    [:llm/model {:optional true} ModelDesignator]
    [:llm/system {:optional true} [:maybe :string]]
    [:llm/max-tokens {:optional true} pos-int?]
    [:llm/temperature {:optional true} number?]
    [:llm/tools {:optional true} [:sequential Tool]]
    [:llm/max-tool-rounds {:optional true} pos-int?]
    [:llm/on-chunk {:optional true} fn?]
    [:llm/on-interaction {:optional true} fn?]
    [:llm/options {:optional true} :map]]))

(def EmbedRequest
  (m/schema
   [:map
    [:llm/model {:optional true} ModelDesignator]
    [:llm/input [:sequential :string]]
    [:llm/on-interaction {:optional true} fn?]
    [:llm/options {:optional true} :map]]))

(def Response
  "What generate/embed return — every response doubles as a replayable
  interaction record. Documentation schema; responses are constructed by
  the library and not validated at runtime."
  (m/schema
   [:map
    [:llm/text {:optional true} [:maybe :string]]
    [:llm/messages {:optional true} [:sequential Message]]
    [:llm/tool-calls {:optional true} [:sequential ToolCall]]
    [:llm/model {:optional true} [:maybe :string]]
    [:llm/provider {:optional true} :keyword]
    [:llm/usage {:optional true} [:maybe Usage]]
    [:llm/finish-reason {:optional true} [:maybe :keyword]]
    [:llm/request {:optional true} :map]
    [:llm/latency-ms {:optional true} number?]
    [:llm/started-at {:optional true} inst?]
    [:llm/op {:optional true} :keyword]
    [:llm/raw {:optional true} :any]
    [:llm/embedding {:optional true} [:sequential number?]]
    [:llm/embeddings {:optional true} [:sequential [:sequential number?]]]]))

;; ---------------------------------------------------------------------------
;; Eval suites

(def Case
  (m/schema
   [:and
    [:map
     [:llm/id {:optional true} :keyword]
     [:llm/input {:optional true} :string]
     [:llm/messages {:optional true} [:sequential Message]]
     [:llm/expected {:optional true} :any]]
    [:fn {:error/message "needs :llm/input or :llm/messages"}
     (fn [{:llm/keys [input messages]}]
       (boolean (or input messages)))]]))

(def Variant
  "A variant is :llm/id plus any generate request keys; extra
  (non-lib) keys are yours and flow through to scorers."
  (m/schema [:map [:llm/id {:optional true} :keyword]]))

(def Scorer
  "A scorer designator: a built-in's keyword, a function, a qualified
  symbol resolving to either, or a map of :llm/id and :llm/fn."
  (m/schema
   [:or :keyword fn? qualified-symbol?
    [:map [:llm/id :keyword] [:llm/fn fn?]]]))

(def Suite
  (m/schema
   [:map
    [:llm/cases [:sequential Case]]
    [:llm/variants {:optional true} [:sequential Variant]]
    [:llm/scorers {:optional true} [:sequential Scorer]]
    [:llm/task {:optional true} [:or fn? qualified-symbol?]]
    [:llm/thresholds {:optional true} [:map-of :keyword number?]]]))

;; ---------------------------------------------------------------------------
;; Validation

(defn assert!
  "Validate `value` against `schema`; return it unchanged when valid,
  throw ex-info {:type error-type :explain <humanized>} when not."
  [schema value error-type message]
  (if (m/validate schema value)
    value
    (let [explanation (me/humanize (m/explain schema value))]
      (throw (ex-info (str message ": " (pr-str explanation))
                      {:type error-type
                       :explain explanation})))))

(defn assert-config! [config]
  (assert! Config config :llm/invalid-config "Invalid clj-llm config"))

(defn assert-request! [request]
  (assert! Request request :llm/invalid-request "Invalid request"))

(defn assert-embed-request! [request]
  (assert! EmbedRequest request :llm/invalid-request "Invalid embed request"))

(defn assert-suite! [suite]
  (assert! Suite suite :llm/invalid-suite "Invalid eval suite"))
