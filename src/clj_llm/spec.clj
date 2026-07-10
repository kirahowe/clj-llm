(ns clj-llm.spec
  "Malli schemas for every public data contract — the source of truth
  for what each map looks like, and the boundary validation that turns
  a malformed input into an immediate, humanized error instead of a
  confusing one three layers down.

  Two kinds of keys appear in these contracts, by design:

  - Keys in maps that users author, store or extend — config, requests,
    responses/records, cases, variants, suites, reports — are namespaced
    :lib/... Any key that is not :lib/-qualified in those maps is
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
    [:map [:lib/provider :keyword] [:lib/model :string]]]))

(def ProviderConfig
  (m/schema [:map [:lib/adapter :keyword]]))

(def Config
  (m/schema
   [:map
    [:lib/providers [:map-of :keyword [:map [:lib/adapter :keyword]]]]
    [:lib/models {:optional true}
     [:map-of :keyword [:or :keyword :string
                        [:map [:lib/provider :keyword] [:lib/model :string]]]]]
    [:lib/defaults {:optional true} :map]]))

;; ---------------------------------------------------------------------------
;; Requests and responses

(def Request
  "A generate request after normalization (prompt string / :lib/prompt
  already folded into :lib/messages)."
  (m/schema
   [:map
    [:lib/messages [:sequential Message]]
    [:lib/model {:optional true} ModelDesignator]
    [:lib/system {:optional true} [:maybe :string]]
    [:lib/max-tokens {:optional true} pos-int?]
    [:lib/temperature {:optional true} number?]
    [:lib/tools {:optional true} [:sequential Tool]]
    [:lib/max-tool-rounds {:optional true} pos-int?]
    [:lib/on-chunk {:optional true} fn?]
    [:lib/on-interaction {:optional true} fn?]
    [:lib/options {:optional true} :map]]))

(def EmbedRequest
  (m/schema
   [:map
    [:lib/model {:optional true} ModelDesignator]
    [:lib/input [:sequential :string]]
    [:lib/on-interaction {:optional true} fn?]
    [:lib/options {:optional true} :map]]))

(def Response
  "What generate/embed return — every response doubles as a replayable
  interaction record. Documentation schema; responses are constructed by
  the library and not validated at runtime."
  (m/schema
   [:map
    [:lib/text {:optional true} [:maybe :string]]
    [:lib/messages {:optional true} [:sequential Message]]
    [:lib/tool-calls {:optional true} [:sequential ToolCall]]
    [:lib/model {:optional true} [:maybe :string]]
    [:lib/provider {:optional true} :keyword]
    [:lib/usage {:optional true} [:maybe Usage]]
    [:lib/finish-reason {:optional true} [:maybe :keyword]]
    [:lib/request {:optional true} :map]
    [:lib/latency-ms {:optional true} number?]
    [:lib/started-at {:optional true} inst?]
    [:lib/op {:optional true} :keyword]
    [:lib/raw {:optional true} :any]
    [:lib/embedding {:optional true} [:sequential number?]]
    [:lib/embeddings {:optional true} [:sequential [:sequential number?]]]]))

;; ---------------------------------------------------------------------------
;; Eval suites

(def Case
  (m/schema
   [:and
    [:map
     [:lib/id {:optional true} :keyword]
     [:lib/input {:optional true} :string]
     [:lib/messages {:optional true} [:sequential Message]]
     [:lib/expected {:optional true} :any]]
    [:fn {:error/message "needs :lib/input or :lib/messages"}
     (fn [{:lib/keys [input messages]}]
       (boolean (or input messages)))]]))

(def Variant
  "A variant is :lib/id plus any generate request keys; extra
  (non-lib) keys are yours and flow through to scorers."
  (m/schema [:map [:lib/id {:optional true} :keyword]]))

(def Scorer
  "A scorer designator: a built-in's keyword, a function, a qualified
  symbol resolving to either, or a map of :lib/id and :lib/fn."
  (m/schema
   [:or :keyword fn? qualified-symbol?
    [:map [:lib/id :keyword] [:lib/fn fn?]]]))

(def Suite
  (m/schema
   [:map
    [:lib/cases [:sequential Case]]
    [:lib/variants {:optional true} [:sequential Variant]]
    [:lib/scorers {:optional true} [:sequential Scorer]]
    [:lib/task {:optional true} [:or fn? qualified-symbol?]]
    [:lib/thresholds {:optional true} [:map-of :keyword number?]]]))

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
  (assert! Config config :lib/invalid-config "Invalid clj-llm config"))

(defn assert-request! [request]
  (assert! Request request :lib/invalid-request "Invalid request"))

(defn assert-embed-request! [request]
  (assert! EmbedRequest request :lib/invalid-request "Invalid embed request"))

(defn assert-suite! [suite]
  (assert! Suite suite :lib/invalid-suite "Invalid eval suite"))
