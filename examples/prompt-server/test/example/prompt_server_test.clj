(ns example.prompt-server-test
  (:require [clj-llm.core :as llm]
            [clojure.test :refer [deftest is run-tests successful? testing]]
            [example.prompt-server :as server]
            [ring.adapter.jetty :as jetty])
  (:import (java.io ByteArrayInputStream)))

(defn- request-with-body-size
  [size]
  {:request-method :post
   :uri "/generate"
   :body (ByteArrayInputStream. (byte-array size))})

(deftest prompt-body-size-boundary
  (testing "exactly 64 KiB reaches the model"
    (let [prompt (atom nil)]
      (with-redefs [llm/generate
                    (fn [_config value]
                      (reset! prompt value)
                      {:llm/text "generated"})]
        (is (= {:status 200
                :headers {"content-type" "text/plain; charset=utf-8"}
                :body "generated"}
               (server/handler
                (request-with-body-size 65536))))
        (is (= 65536 (count @prompt))))))
  (testing "64 KiB plus one is rejected before model work"
    (let [generate-calls (atom 0)]
      (with-redefs [llm/generate
                    (fn [& _]
                      (swap! generate-calls inc)
                      {:llm/text "unexpected"})]
        (is (= {:status 413
                :headers {"content-type" "text/plain; charset=utf-8"}
                :body "Prompt too large\n"}
               (server/handler
                (request-with-body-size 65537))))
        (is (zero? @generate-calls))))))

(deftest jetty-launch-is-loopback-only
  (let [launch (atom nil)]
    (with-redefs [jetty/run-jetty
                  (fn [handler options]
                    (reset! launch {:handler handler :options options}))]
      (with-out-str (server/-main)))
    (is (identical? server/handler (:handler @launch)))
    (is (= {:host "127.0.0.1" :port 3000}
           (:options @launch)))))

(defn -main
  [& _]
  (let [summary (run-tests 'example.prompt-server-test)]
    (System/exit (if (successful? summary) 0 1))))
