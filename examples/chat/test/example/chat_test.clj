(ns example.chat-test
  "Tests for the streaming terminal chat example."
  (:require [clj-llm.core :as llm]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests successful?]]
            [example.chat :as chat]))

(def ^:private expected-config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(deftest config-resource-resolves-and-parses
  (let [resource (io/resource "example/chat/llm.edn")]
    (is (some? resource))
    (when resource
      (is (= expected-config (llm/read-config resource))))))

(deftest main-loads-real-config-and-quits-before-generation
  (let [resource-names (atom [])
        generate-calls (atom 0)
        resolve-resource io/resource]
    (with-redefs [io/resource
                  (fn [resource-name]
                    (swap! resource-names conj resource-name)
                    (resolve-resource resource-name))
                  llm/generate
                  (fn [& _]
                    (swap! generate-calls inc)
                    (throw (ex-info "Unexpected provider work" {})))]
      (is (= "Chat with the model. Enter :quit to stop.\nyou> "
             (with-in-str ":quit\n"
               (with-out-str (chat/-main)))))
      (is (= ["example/chat/llm.edn"] @resource-names))
      (is (zero? @generate-calls)))))

(defn -main
  "Run this example's isolated tests."
  [& _]
  (let [summary (run-tests 'example.chat-test)]
    (System/exit (if (successful? summary) 0 1))))
