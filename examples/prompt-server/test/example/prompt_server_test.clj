(ns example.prompt-server-test
  "Tests for the loopback-only HTTP prompt server example."
  (:require [clj-llm.core :as llm]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests successful? testing]]
            [example.prompt-server :as server]
            [ring.adapter.jetty :as jetty])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)))

(def ^:private expected-config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(defn- request-with-bytes
  [bytes]
  {:request-method :post
   :uri "/generate"
   :body (ByteArrayInputStream. bytes)})

(defn- request-with-body-size
  [size]
  (request-with-bytes (byte-array size)))

(defn- request-with-prompt
  [prompt]
  (request-with-bytes (.getBytes prompt StandardCharsets/UTF_8)))

(deftest config-resource-resolves-and-parses
  (let [resource (io/resource "example/prompt-server/llm.edn")]
    (is (some? resource))
    (when resource
      (is (= expected-config (llm/read-config resource))))))

(deftest handler-uses-supplied-config
  (let [config ::supplied-config
        generate-call (atom nil)]
    (with-redefs [llm/generate
                  (fn [actual-config prompt]
                    (reset! generate-call [actual-config prompt])
                    {:llm/text "generated"})]
      (is (= {:status 200
              :headers {"content-type" "text/plain; charset=utf-8"}
              :body "generated"}
             ((server/handler config)
              (request-with-prompt "Hello"))))
      (is (= [config "Hello"] @generate-call)))))

(deftest prompt-body-size-boundary
  (testing "exactly 64 KiB reaches the model"
    (let [config ::supplied-config
          generate-call (atom nil)]
      (with-redefs [llm/generate
                    (fn [actual-config prompt]
                      (reset! generate-call [actual-config prompt])
                      {:llm/text "generated"})]
        (is (= {:status 200
                :headers {"content-type" "text/plain; charset=utf-8"}
                :body "generated"}
               ((server/handler config)
                (request-with-body-size 65536))))
        (is (= config (first @generate-call)))
        (is (= 65536 (count (second @generate-call)))))))
  (testing "64 KiB plus one is rejected before model work"
    (let [generate-calls (atom 0)]
      (with-redefs [llm/generate
                    (fn [& _]
                      (swap! generate-calls inc)
                      {:llm/text "unexpected"})]
        (is (= {:status 413
                :headers {"content-type" "text/plain; charset=utf-8"}
                :body "Prompt too large\n"}
               ((server/handler ::supplied-config)
                (request-with-body-size 65537))))
        (is (zero? @generate-calls))))))

(deftest main-loads-config-and-launches-composed-loopback-handler
  (let [calls (atom [])
        launch (atom nil)
        loaded-config ::loaded-config]
    (with-redefs [io/resource
                  (fn [resource-name]
                    (swap! calls conj [:resource resource-name])
                    ::config-resource)
                  llm/read-config
                  (fn [resource]
                    (swap! calls conj [:read-config resource])
                    loaded-config)
                  llm/generate
                  (fn [config prompt]
                    (swap! calls conj [:generate config prompt])
                    {:llm/text "generated"})
                  jetty/run-jetty
                  (fn [handler options]
                    (reset! launch
                            {:handler? (fn? handler)
                             :options options
                             :response (handler
                                        (request-with-prompt "Hello"))}))]
      (with-out-str (server/-main)))
    (is (= [[:resource "example/prompt-server/llm.edn"]
            [:read-config ::config-resource]
            [:generate loaded-config "Hello"]]
           @calls))
    (is (= {:handler? true
            :options {:host "127.0.0.1" :port 3000}
            :response {:status 200
                       :headers {"content-type"
                                 "text/plain; charset=utf-8"}
                       :body "generated"}}
           @launch))))

(deftest main-reports-a-typed-missing-config-error
  (with-redefs [io/resource (constantly nil)]
    (try
      (server/-main)
      (is false "Expected missing config to prevent server startup")
      (catch clojure.lang.ExceptionInfo exception
        (is (= "Config resource not found on classpath: example/prompt-server/llm.edn"
               (ex-message exception)))
        (is (= {:type :llm/config-not-found
                :resource "example/prompt-server/llm.edn"}
               (ex-data exception)))))))

(defn -main
  "Run this example's isolated tests."
  [& _]
  (let [summary (run-tests 'example.prompt-server-test)]
    (System/exit (if (successful? summary) 0 1))))
