(ns clj-llm.config
  "Loading and resolving clj-llm configuration.

  Configuration is plain EDN data, read with aero — so API keys, base
  URLs and model names live in config files, never in source, and you
  get aero's full tag set (#env, #or, #profile, #include, #ref, ...):

    #:lib{:providers
          {:anthropic {:lib/adapter :anthropic
                       :api-key #env ANTHROPIC_API_KEY}}
          :models {:smart #:lib{:provider :anthropic
                                :model \"claude-sonnet-4-6\"}}
          :defaults #:lib{:model :smart
                          :max-tokens #profile {:dev 1024 :default 4096}}}

  Providers are *accounts/endpoints* (an Anthropic account, a Groq
  account, a local Ollama server). The :lib/adapter key selects the
  wire protocol — see clj-llm.provider; every other key in a provider map
  belongs to that adapter (:api-key, :base-url, ...) and flows through
  untouched. Models are aliases so application code can say :fast or
  :smart and the vendor mapping lives in config. :lib/defaults are
  merged into every request; that includes :lib/on-interaction, a hook
  that receives every response record (see clj-llm.core/generate) — the
  raw material for evals.

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
  "The name a provider config was registered under in :lib/providers."
  [provider-config]
  (::name provider-config))

(defn provider-config
  "Look up a provider by name, tagging it with ::name for error reporting.
  Throws when the provider is not configured."
  [config provider-name]
  (if-let [p (get-in config [:lib/providers provider-name])]
    (assoc p ::name provider-name)
    (throw (ex-info (str "No provider named " provider-name " in config. "
                         "Known providers: "
                         (pr-str (keys (:lib/providers config))))
                    {:type :lib/config-error
                     :provider provider-name
                     :known (keys (:lib/providers config))}))))

(defn resolve-model
  "Resolve a model designator into {:provider <provider-config> :model <string>}.

  Designators:
    nil       use the default alias from [:lib/defaults default-key]
    keyword   an alias defined under :lib/models
    string    \"provider-name/model-id\" — splits on the first slash
    map       #:lib{:provider <name> :model \"model-id\"} used directly

  default-key is :lib/model or :lib/embedding-model (defaults to
  :lib/model)."
  ([config designator] (resolve-model config designator :lib/model))
  ([config designator default-key]
   (cond
     (nil? designator)
     (if-let [d (get-in config [:lib/defaults default-key])]
       (resolve-model config d default-key)
       (throw (ex-info (str "No model given and no " default-key
                            " configured under :lib/defaults")
                       {:type :lib/config-error :default-key default-key})))

     (keyword? designator)
     (if-let [alias-config (get-in config [:lib/models designator])]
       (resolve-model config alias-config default-key)
       (throw (ex-info (str "No model alias " designator " in config. "
                            "Known aliases: "
                            (pr-str (keys (:lib/models config))))
                       {:type :lib/config-error
                        :alias designator
                        :known (keys (:lib/models config))})))

     (string? designator)
     (let [i (str/index-of designator "/")]
       (when-not i
         (throw (ex-info (str "String model designators must look like "
                              "\"provider-name/model-id\", got: " designator)
                         {:type :lib/config-error :designator designator})))
       {:provider (provider-config config (keyword (subs designator 0 i)))
        :model (subs designator (inc i))})

     (map? designator)
     {:provider (provider-config config (:lib/provider designator))
      :model (:lib/model designator)}

     :else
     (throw (ex-info (str "Unsupported model designator: " (pr-str designator))
                     {:type :lib/config-error :designator designator})))))
