(ns kirahowe.clj-llm.config
  "Loading and resolving clj-llm configuration.

  Configuration is plain EDN data, read with aero — so API keys, base
  URLs and model names live in config files, never in source, and you
  get aero's full tag set (#env, #or, #profile, #include, #ref, ...):

    {:providers
     {:anthropic {:adapter :anthropic
                  :api-key #env ANTHROPIC_API_KEY}}
     :models {:smart {:provider :anthropic :model \"claude-sonnet-4-6\"}}
     :defaults {:model :smart
                :max-tokens #profile {:dev 1024 :default 4096}}}

  Providers are *accounts/endpoints* (an Anthropic account, a Groq
  account, a local Ollama server). The :adapter key selects the wire
  protocol — see kirahowe.clj-llm.provider. Models are aliases so
  application code can say :fast or :smart and the vendor mapping lives
  in config. :defaults are merged into every request; that includes
  :on-interaction, a hook that receives every response record (see
  kirahowe.clj-llm/generate) — the raw material for evals.

  The rest of the library only ever sees the resulting map, so configs
  built by hand, by aero directly, or by an integrant system all work
  the same."
  (:require [aero.core :as aero]
            [clojure.string :as str]))

(defn read-config
  "Read an EDN config file with aero. `source` is anything
  clojure.java.io/reader accepts — a path string, File, URL, or
  (io/resource ...). `opts` are aero options, e.g. {:profile :prod}."
  ([source] (read-config source {}))
  ([source opts] (aero/read-config source opts)))

(defn read-config-string
  "Parse a config EDN string (mostly useful in tests)."
  ([s] (read-config-string s {}))
  ([s opts] (read-config (java.io.StringReader. s) opts)))

(defn provider-name
  "The name a provider config was registered under in :providers."
  [provider-config]
  (::name provider-config))

(defn provider-config
  "Look up a provider by name, tagging it with ::name for error reporting.
  Throws when the provider is not configured."
  [config provider-name]
  (if-let [p (get-in config [:providers provider-name])]
    (assoc p ::name provider-name)
    (throw (ex-info (str "No provider named " provider-name " in config. "
                         "Known providers: " (pr-str (keys (:providers config))))
                    {:type ::config-error
                     :provider provider-name
                     :known (keys (:providers config))}))))

(defn resolve-model
  "Resolve a model designator into {:provider <provider-config> :model <string>}.

  Designators:
    nil       use the default alias from [:defaults default-key]
    keyword   an alias defined under :models
    string    \"provider-name/model-id\" — splits on the first slash
    map       {:provider <name> :model \"model-id\"} used directly

  default-key is :model or :embedding-model (defaults to :model)."
  ([config designator] (resolve-model config designator :model))
  ([config designator default-key]
   (cond
     (nil? designator)
     (if-let [d (get-in config [:defaults default-key])]
       (resolve-model config d default-key)
       (throw (ex-info (str "No model given and no " default-key
                            " configured under :defaults")
                       {:type ::config-error :default-key default-key})))

     (keyword? designator)
     (if-let [alias-config (get-in config [:models designator])]
       (resolve-model config alias-config default-key)
       (throw (ex-info (str "No model alias " designator " in config. "
                            "Known aliases: " (pr-str (keys (:models config))))
                       {:type ::config-error
                        :alias designator
                        :known (keys (:models config))})))

     (string? designator)
     (let [i (str/index-of designator "/")]
       (when-not i
         (throw (ex-info (str "String model designators must look like "
                              "\"provider-name/model-id\", got: " designator)
                         {:type ::config-error :designator designator})))
       {:provider (provider-config config (keyword (subs designator 0 i)))
        :model (subs designator (inc i))})

     (map? designator)
     {:provider (provider-config config (:provider designator))
      :model (:model designator)}

     :else
     (throw (ex-info (str "Unsupported model designator: " (pr-str designator))
                     {:type ::config-error :designator designator})))))
