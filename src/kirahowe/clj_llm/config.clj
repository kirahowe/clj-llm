(ns kirahowe.clj-llm.config
  "Loading and resolving clj-llm configuration.

  Configuration is plain EDN data, typically loaded from a file so that
  nothing — API keys, base URLs, model names — is ever hard coded in
  source. The reader supports a small set of tags for wiring in values
  from the environment:

    #env \"ANTHROPIC_API_KEY\"      value of an environment variable (nil if unset)
    #or [#env \"A\" \"fallback\"]     first non-nil value in the vector
    #profile {:dev ... :prod ...}   value for the active profile

  A config map has this shape:

    {:providers {<name> {:adapter <adapter-kw> :api-key ... :base-url ...}}
     :models    {<alias> {:provider <name> :model \"model-id\"}}
     :defaults  {:model <alias> :embedding-model <alias> :max-tokens 4096 ...}}

  Providers are *accounts/endpoints* (an Anthropic account, a Groq account,
  a local Ollama server). The :adapter key selects the wire protocol —
  see kirahowe.clj-llm.provider. Models are aliases so application code can
  say :fast or :smart and the mapping lives in config."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- getenv [v]
  (System/getenv (str v)))

(defn readers
  "EDN reader map for config files. opts:
    :profile  keyword selecting the branch of #profile maps (default :default)
    :env      1-arg fn used to look up #env vars (default System/getenv);
              override in tests."
  [{:keys [profile env] :or {env getenv}}]
  {'env     (fn [v] (env (name v)))
   'or      (fn [vs] (reduce (fn [acc v] (if (some? acc) (reduced acc) v)) nil vs))
   'profile (fn [m] (if (contains? m profile)
                      (get m profile)
                      (get m :default)))})

(defn read-config-string
  "Parse a config EDN string. See `read-config` for opts."
  ([s] (read-config-string s {}))
  ([s opts]
   (edn/read-string {:readers (readers opts)} s)))

(defn read-config
  "Read a config EDN file. `source` is anything clojure.java.io/reader
  accepts — a path string, File, URL, or (io/resource ...).

  opts:
    :profile  keyword selecting the branch of #profile maps
    :env      1-arg fn used to look up #env vars (default System/getenv)"
  ([source] (read-config source {}))
  ([source opts]
   (with-open [r (java.io.PushbackReader. (io/reader source))]
     (edn/read {:readers (readers opts)} r))))

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
