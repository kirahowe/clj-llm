(ns clj-llm.spec
  "Malli schemas for every public data contract — the source of truth
  for what each map looks like, and the boundary validation that turns
  a malformed input into an immediate, humanized error instead of a
  confusing one three layers down.

  Two kinds of keys appear in these contracts, by design:

  - Keys in maps that users author, store or extend — config, requests,
    responses/records, cases, variants, suites, reports — are namespaced
    :clj-llm/... Any key that is not :clj-llm/-qualified in those maps is
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
    [:map [:clj-llm/provider :keyword] [:clj-llm/model :string]]]))

(def ProviderConfig
  (m/schema [:map [:clj-llm/adapter :keyword]]))

(def Config
  (m/schema
   [:map
    [:clj-llm/providers [:map-of :keyword [:map [:clj-llm/adapter :keyword]]]]
    [:clj-llm/models {:optional true}
     [:map-of :keyword [:or :keyword :string
                        [:map [:clj-llm/provider :keyword] [:clj-llm/model :string]]]]]
    [:clj-llm/defaults {:optional true} :map]]))

;; ---------------------------------------------------------------------------
;; Requests and responses

(def Request
  "A generate request after normalization (prompt string / :clj-llm/prompt
  already folded into :clj-llm/messages)."
  (m/schema
   [:map
    [:clj-llm/messages [:sequential Message]]
    [:clj-llm/model {:optional true} ModelDesignator]
    [:clj-llm/system {:optional true} [:maybe :string]]
    [:clj-llm/max-tokens {:optional true} pos-int?]
    [:clj-llm/temperature {:optional true} number?]
    [:clj-llm/tools {:optional true} [:sequential Tool]]
    [:clj-llm/max-tool-rounds {:optional true} pos-int?]
    [:clj-llm/on-chunk {:optional true} fn?]
    [:clj-llm/on-interaction {:optional true} fn?]
    [:clj-llm/options {:optional true} :map]]))

(def EmbedRequest
  (m/schema
   [:map
    [:clj-llm/model {:optional true} ModelDesignator]
    [:clj-llm/input [:sequential :string]]
    [:clj-llm/on-interaction {:optional true} fn?]
    [:clj-llm/options {:optional true} :map]]))

(def Response
  "What generate/embed return — every response doubles as a replayable
  interaction record. Documentation schema; responses are constructed by
  the library and not validated at runtime."
  (m/schema
   [:map
    [:clj-llm/text {:optional true} [:maybe :string]]
    [:clj-llm/messages {:optional true} [:sequential Message]]
    [:clj-llm/tool-calls {:optional true} [:sequential ToolCall]]
    [:clj-llm/model {:optional true} [:maybe :string]]
    [:clj-llm/provider {:optional true} :keyword]
    [:clj-llm/usage {:optional true} [:maybe Usage]]
    [:clj-llm/finish-reason {:optional true} [:maybe :keyword]]
    [:clj-llm/request {:optional true} :map]
    [:clj-llm/latency-ms {:optional true} number?]
    [:clj-llm/started-at {:optional true} inst?]
    [:clj-llm/op {:optional true} :keyword]
    [:clj-llm/raw {:optional true} :any]
    [:clj-llm/embedding {:optional true} [:sequential number?]]
    [:clj-llm/embeddings {:optional true} [:sequential [:sequential number?]]]]))

;; ---------------------------------------------------------------------------
;; Eval suites

(def Case
  (m/schema
   [:and
    [:map
     [:clj-llm/id {:optional true} :keyword]
     [:clj-llm/input {:optional true} :string]
     [:clj-llm/messages {:optional true} [:sequential Message]]
     [:clj-llm/expected {:optional true} :any]]
    [:fn {:error/message "needs :clj-llm/input or :clj-llm/messages"}
     (fn [{:clj-llm/keys [input messages]}]
       (boolean (or input messages)))]]))

(def Variant
  "A variant is :clj-llm/id plus any generate request keys; extra
  (non-clj-llm) keys are yours and flow through to scorers."
  (m/schema [:map [:clj-llm/id {:optional true} :keyword]]))

(def Scorer
  "A scorer designator: a built-in's keyword, a function, a qualified
  symbol resolving to either, or a map of :clj-llm/id and :clj-llm/fn."
  (m/schema
   [:or :keyword fn? qualified-symbol?
    [:map [:clj-llm/id :keyword] [:clj-llm/fn fn?]]]))

(def Suite
  (m/schema
   [:map
    [:clj-llm/cases [:sequential Case]]
    [:clj-llm/variants {:optional true} [:sequential Variant]]
    [:clj-llm/scorers {:optional true} [:sequential Scorer]]
    [:clj-llm/task {:optional true} [:or fn? qualified-symbol?]]
    [:clj-llm/thresholds {:optional true} [:map-of :keyword number?]]]))

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
  (assert! Config config :clj-llm/invalid-config "Invalid clj-llm config"))

(defn assert-request! [request]
  (assert! Request request :clj-llm/invalid-request "Invalid request"))

(defn assert-embed-request! [request]
  (assert! EmbedRequest request :clj-llm/invalid-request "Invalid embed request"))

(defn assert-suite! [suite]
  (assert! Suite suite :clj-llm/invalid-suite "Invalid eval suite"))
