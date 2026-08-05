(ns clj-llm.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.config :as config]))

;; A variable virtually guaranteed to exist, and one guaranteed not to.
(def set-var "HOME")
(def unset-var "CLJ_LLM_TEST_DEFINITELY_NOT_SET")

(deftest reader-tags
  (testing "#env reads from the environment"
    (is (= (System/getenv set-var)
           (:key (config/read-config-string
                  (str "{:key #env \"" set-var "\"}"))))))

  (testing "#env is nil for unset variables"
    (is (nil? (:key (config/read-config-string
                     (str "{:key #env \"" unset-var "\"}"))))))

  (testing "#or picks the first non-nil value"
    (is (= "fallback"
           (:url (config/read-config-string
                  (str "{:url #or [#env \"" unset-var "\" \"fallback\"]}")))))
    (is (= (System/getenv set-var)
           (:url (config/read-config-string
                  (str "{:url #or [#env \"" set-var "\" \"fallback\"]}"))))))

  (testing "#profile selects the active profile, falling back to :default"
    (let [s "{:max-tokens #profile {:dev 128 :default 4096}}"]
      (is (= {:max-tokens 128} (config/read-config-string s {:profile :dev})))
      (is (= {:max-tokens 4096} (config/read-config-string s {:profile :prod}))))))

(def test-config
  #:llm{:providers {:acme {:llm/adapter :openai
                           :base-url "https://llm.acme.test/v1"
                           :api-key "k"}
                    :local {:llm/adapter :ollama}}
        :models {:smart #:llm{:provider :acme :model "acme-large"}
                 :embeddings #:llm{:provider :local :model "nomic-embed-text"}}
        :defaults #:llm{:model :smart
                        :embedding-model :embeddings
                        :max-tokens 512}})

(deftest resolve-model
  (testing "nil designator uses the configured default alias"
    (let [{:keys [provider model]} (config/resolve-model test-config nil)]
      (is (= "acme-large" model))
      (is (= :openai (:llm/adapter provider)))
      (is (= :acme (config/provider-name provider)))))

  (testing "keyword designator resolves an alias"
    (is (= "nomic-embed-text"
           (:model (config/resolve-model test-config :embeddings)))))

  (testing "string designator splits provider/model on the first slash"
    (let [{:keys [provider model]} (config/resolve-model test-config
                                                         "acme/vendor/model-x")]
      (is (= :acme (config/provider-name provider)))
      (is (= "vendor/model-x" model))))

  (testing "map designator is used directly"
    (is (= "whatever"
           (:model (config/resolve-model test-config
                                         #:llm{:provider :local :model "whatever"})))))

  (testing "embedding default key"
    (is (= "nomic-embed-text"
           (:model (config/resolve-model test-config nil :llm/embedding-model)))))

  (testing "helpful errors"
    (is (thrown-with-msg? Exception #"No model alias"
                          (config/resolve-model test-config :nope)))
    (is (thrown-with-msg? Exception #"No provider named"
                          (config/resolve-model test-config
                                                #:llm{:provider :nope :model "x"})))
    (is (thrown-with-msg? Exception #"No model given"
                          (config/resolve-model {} nil)))))
