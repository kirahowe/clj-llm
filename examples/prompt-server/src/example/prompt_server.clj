(ns example.prompt-server
  (:require [clj-llm.core :as llm]
            [ring.adapter.jetty :as jetty]))

(def config (llm/read-config "llm.edn"))

(defn handler
  [{:keys [request-method uri body]}]
  (if (and (= :post request-method) (= "/generate" uri))
    {:status 200
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body (:llm/text (llm/generate config (slurp body)))}
    {:status 404
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body "Not found\n"}))

(defn -main
  [& _]
  (println "Listening on http://localhost:3000")
  (jetty/run-jetty handler {:port 3000}))
