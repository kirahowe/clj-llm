(ns kirahowe.clj-llm.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [kirahowe.clj-llm.config :as config]))

(def fake-env
  {"API_KEY" "sk-from-env"
   "EMPTYABLE" nil})

(deftest reader-tags
  (testing "#env reads from the environment lookup"
    (is (= {:key "sk-from-env"}
           (config/read-config-string "{:key #env \"API_KEY\"}" {:env fake-env}))))

  (testing "#env is nil for unset variables"
    (is (= {:key nil}
           (config/read-config-string "{:key #env \"NOPE\"}" {:env fake-env}))))

  (testing "#or picks the first non-nil value"
    (is (= {:url "fallback"}
           (config/read-config-string "{:url #or [#env \"NOPE\" \"fallback\"]}"
                                      {:env fake-env})))
    (is (= {:url "sk-from-env"}
           (config/read-config-string "{:url #or [#env \"API_KEY\" \"fallback\"]}"
                                      {:env fake-env}))))

  (testing "#profile selects the active profile, falling back to :default"
    (let [s "{:max-tokens #profile {:dev 128 :default 4096}}"]
      (is (= {:max-tokens 128} (config/read-config-string s {:profile :dev})))
      (is (= {:max-tokens 4096} (config/read-config-string s {:profile :prod})))
      (is (= {:max-tokens 4096} (config/read-config-string s {}))))))

(def test-config
  {:providers {:acme {:adapter :openai
                      :base-url "https://llm.acme.test/v1"
                      :api-key "k"}
               :local {:adapter :ollama}}
   :models {:smart {:provider :acme :model "acme-large"}
            :embeddings {:provider :local :model "nomic-embed-text"}}
   :defaults {:model :smart
              :embedding-model :embeddings
              :max-tokens 512}})

(deftest resolve-model
  (testing "nil designator uses the configured default alias"
    (let [{:keys [provider model]} (config/resolve-model test-config nil)]
      (is (= "acme-large" model))
      (is (= :openai (:adapter provider)))
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
                                         {:provider :local :model "whatever"})))))

  (testing "embedding default key"
    (is (= "nomic-embed-text"
           (:model (config/resolve-model test-config nil :embedding-model)))))

  (testing "helpful errors"
    (is (thrown-with-msg? Exception #"No model alias"
                          (config/resolve-model test-config :nope)))
    (is (thrown-with-msg? Exception #"No provider named"
                          (config/resolve-model test-config
                                                {:provider :nope :model "x"})))
    (is (thrown-with-msg? Exception #"No model given"
                          (config/resolve-model {} nil)))))
