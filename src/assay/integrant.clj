(ns assay.integrant
  "Optional Integrant bindings. Integrant is not a dependency of assay —
  add it to your own deps and require this namespace to get the keys below.

  The library itself is stateless, so the component is simply the resolved
  config map; init also gives each provider's `start` lifecycle hook a
  chance to run (custom adapters can use it for things like OAuth token
  acquisition), and halt calls `stop`.

    {:assay/config {:path \"llm.edn\" :profile :prod}}

    (defmethod ig/init-key ::my-handler [_ {:keys [llm]}]
      (fn [request] ... (assay/generate llm ...) ...))

    {:assay/config {:path \"llm.edn\"}
     ::my-handler {:llm (ig/ref :assay/config)}}

  Init options — exactly one config source:
    :path      path to an EDN config file
    :resource  classpath resource name of an EDN config file
    :config    an inline config map (already read)
  Remaining options (e.g. :profile) are passed through to aero."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [assay.config :as config]
            [assay.provider :as provider]))

(defn- load-config [{:keys [path resource config] :as opts}]
  (let [reader-opts (dissoc opts :path :resource :config)]
    (cond
      config config
      path (config/read-config path reader-opts)
      resource (config/read-config
                (or (io/resource resource)
                    (throw (ex-info (str "Config resource not found on classpath: "
                                         resource)
                                    {:type :assay/config-not-found :resource resource})))
                reader-opts)
      :else (throw (ex-info "Provide one of :path, :resource or :config"
                            {:type :assay/config-not-found :opts opts})))))

(defmethod ig/init-key :assay/config
  [_ opts]
  (-> (load-config opts)
      (update :assay/providers update-vals #(provider/start % {}))))

(defmethod ig/halt-key! :assay/config
  [_ config]
  (run! #(provider/stop % {}) (vals (:assay/providers config))))
