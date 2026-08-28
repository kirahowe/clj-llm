(ns example.prompt-server
  "A loopback-only HTTP prompt server example."
  (:require [clj-llm.core :as llm]
            [clojure.java.io :as io]
            [ring.adapter.jetty :as jetty])
  (:import (java.nio.charset StandardCharsets)))

(def ^:private config-resource "example/prompt-server/llm.edn")
(def ^:private max-prompt-bytes 65536)

(defn- generate-response!
  [config body]
  (let [bytes (.readNBytes body (inc max-prompt-bytes))]
    (if (> (alength bytes) max-prompt-bytes)
      {:status 413
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body "Prompt too large\n"}
      {:status 200
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body (:llm/text
              (llm/generate config
                            (String. bytes StandardCharsets/UTF_8)))})))

(defn handler
  "Return a Ring handler that generates responses using `config`."
  [config]
  (fn [{:keys [request-method uri body]}]
    (if (and (= :post request-method) (= "/generate" uri))
      (generate-response! config body)
      {:status 404
       :headers {"content-type" "text/plain; charset=utf-8"}
       :body "Not found\n"})))

(defn -main
  "Load the example config and start the loopback-only HTTP server."
  [& _]
  (let [resource (or (io/resource config-resource)
                     (throw
                      (ex-info
                       (str "Config resource not found on classpath: "
                            config-resource)
                       {:type :llm/config-not-found
                        :resource config-resource})))
        config (llm/read-config resource)]
    (println "Listening on http://127.0.0.1:3000")
    (jetty/run-jetty (handler config)
                     {:host "127.0.0.1" :port 3000})))
