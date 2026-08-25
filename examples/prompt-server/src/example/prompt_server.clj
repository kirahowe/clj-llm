(ns example.prompt-server
  (:require [clj-llm.core :as llm]
            [ring.adapter.jetty :as jetty])
  (:import (java.nio.charset StandardCharsets)))

(def config (llm/read-config "llm.edn"))

(def max-prompt-bytes 65536)

(defn handler
  [{:keys [request-method uri body]}]
  (if (and (= :post request-method) (= "/generate" uri))
    (let [bytes (.readNBytes body (inc max-prompt-bytes))]
      (if (> (alength bytes) max-prompt-bytes)
        {:status 413
         :headers {"content-type" "text/plain; charset=utf-8"}
         :body "Prompt too large\n"}
        {:status 200
         :headers {"content-type" "text/plain; charset=utf-8"}
         :body (:llm/text
                (llm/generate config
                              (String. bytes StandardCharsets/UTF_8)))}))
    {:status 404
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body "Not found\n"}))

(defn -main
  [& _]
  (println "Listening on http://127.0.0.1:3000")
  (jetty/run-jetty handler {:host "127.0.0.1" :port 3000}))
