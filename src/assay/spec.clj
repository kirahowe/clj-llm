(ns assay.spec
  "Malli schemas for every public data contract — the source of truth
  for what each map looks like, and the boundary validation that turns
  a malformed input into an immediate, humanized error instead of a
  confusing one three layers down.

  Two kinds of keys appear in these contracts, by design:

  - Keys in maps that users author, store or extend — config, requests,
    responses/records, cases, variants, suites, reports — are namespaced
    :assay/... Any key that is not :assay/-qualified in those maps is
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
    [:map [:assay/provider :keyword] [:assay/model :string]]]))

(def ProviderConfig
  (m/schema [:map [:assay/adapter :keyword]]))

(def Config
  (m/schema
   [:map
    [:assay/providers [:map-of :keyword [:map [:assay/adapter :keyword]]]]
    [:assay/models {:optional true}
     [:map-of :keyword [:or :keyword :string
                        [:map [:assay/provider :keyword] [:assay/model :string]]]]]
    [:assay/defaults {:optional true} :map]]))

;; ---------------------------------------------------------------------------
;; Requests and responses

(def Request
  "A generate request after normalization (prompt string / :assay/prompt
  already folded into :assay/messages)."
  (m/schema
   [:map
    [:assay/messages [:sequential Message]]
    [:assay/model {:optional true} ModelDesignator]
    [:assay/system {:optional true} [:maybe :string]]
    [:assay/max-tokens {:optional true} pos-int?]
    [:assay/temperature {:optional true} number?]
    [:assay/tools {:optional true} [:sequential Tool]]
    [:assay/max-tool-rounds {:optional true} pos-int?]
    [:assay/on-chunk {:optional true} fn?]
    [:assay/on-interaction {:optional true} fn?]
    [:assay/options {:optional true} :map]]))

(def EmbedRequest
  (m/schema
   [:map
    [:assay/model {:optional true} ModelDesignator]
    [:assay/input [:sequential :string]]
    [:assay/on-interaction {:optional true} fn?]
    [:assay/options {:optional true} :map]]))

(def Response
  "What generate/embed return — every response doubles as a replayable
  interaction record. Documentation schema; responses are constructed by
  the library and not validated at runtime."
  (m/schema
   [:map
    [:assay/text {:optional true} [:maybe :string]]
    [:assay/messages {:optional true} [:sequential Message]]
    [:assay/tool-calls {:optional true} [:sequential ToolCall]]
    [:assay/model {:optional true} [:maybe :string]]
    [:assay/provider {:optional true} :keyword]
    [:assay/usage {:optional true} [:maybe Usage]]
    [:assay/finish-reason {:optional true} [:maybe :keyword]]
    [:assay/request {:optional true} :map]
    [:assay/latency-ms {:optional true} number?]
    [:assay/started-at {:optional true} inst?]
    [:assay/op {:optional true} :keyword]
    [:assay/raw {:optional true} :any]
    [:assay/embedding {:optional true} [:sequential number?]]
    [:assay/embeddings {:optional true} [:sequential [:sequential number?]]]]))

;; ---------------------------------------------------------------------------
;; Eval suites

(def Case
  (m/schema
   [:and
    [:map
     [:assay/id {:optional true} :keyword]
     [:assay/input {:optional true} :string]
     [:assay/messages {:optional true} [:sequential Message]]
     [:assay/expected {:optional true} :any]]
    [:fn {:error/message "needs :assay/input or :assay/messages"}
     (fn [{:assay/keys [input messages]}]
       (boolean (or input messages)))]]))

(def Variant
  "A variant is :assay/id plus any generate request keys; extra
  (non-assay) keys are yours and flow through to scorers."
  (m/schema [:map [:assay/id {:optional true} :keyword]]))

(def Scorer
  "A scorer designator: a built-in's keyword, a function, a qualified
  symbol resolving to either, or a map of :assay/id and :assay/fn."
  (m/schema
   [:or :keyword fn? qualified-symbol?
    [:map [:assay/id :keyword] [:assay/fn fn?]]]))

(def Suite
  (m/schema
   [:map
    [:assay/cases [:sequential Case]]
    [:assay/variants {:optional true} [:sequential Variant]]
    [:assay/scorers {:optional true} [:sequential Scorer]]
    [:assay/task {:optional true} [:or fn? qualified-symbol?]]
    [:assay/thresholds {:optional true} [:map-of :keyword number?]]]))

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
  (assert! Config config :assay/invalid-config "Invalid assay config"))

(defn assert-request! [request]
  (assert! Request request :assay/invalid-request "Invalid request"))

(defn assert-embed-request! [request]
  (assert! EmbedRequest request :assay/invalid-request "Invalid embed request"))

(defn assert-suite! [suite]
  (assert! Suite suite :assay/invalid-suite "Invalid eval suite"))
