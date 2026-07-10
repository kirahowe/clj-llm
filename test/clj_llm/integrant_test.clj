(ns clj-llm.integrant-test
  "Exercises the optional integrant bindings. Integrant is not a
  dependency of clj-llm, so these tests are skipped (with a notice)
  when it isn't on the classpath — run them via `clojure -M:test-integrant`."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.provider :as provider]))

(def integrant-available?
  (try (require 'integrant.core) true
       (catch Throwable _ false)))

(when integrant-available?
  (require 'clj-llm.integrant))

(def started (atom []))
(def stopped (atom []))

(defmethod provider/-start ::lifecycle [provider-config _opts]
  (swap! started conj (:lib/adapter provider-config))
  (assoc provider-config :token "acquired"))

(defmethod provider/-stop ::lifecycle [provider-config _opts]
  (swap! stopped conj (:token provider-config)))

(deftest init-and-halt
  (if-not integrant-available?
    (println "[skip] integrant not on the classpath;"
             "run `clojure -M:test-integrant` to cover clj-llm.integrant")
    (let [init-key (requiring-resolve 'integrant.core/init-key)
          halt-key! (requiring-resolve 'integrant.core/halt-key!)]
      (reset! started [])
      (reset! stopped [])
      (testing "init resolves inline config and starts providers"
        (let [config (init-key :clj-llm/config
                               {:config {:lib/providers {:p {:lib/adapter ::lifecycle}}
                                         :lib/defaults {}}})]
          (is (= [::lifecycle] @started))
          (is (= "acquired" (get-in config [:lib/providers :p :token]))
              "start's return value replaces the provider config")
          (testing "halt stops providers"
            (halt-key! :clj-llm/config config)
            (is (= ["acquired"] @stopped)))))
      (testing "init reads config files with reader options"
        (let [dir (java.nio.file.Files/createTempDirectory
                   "clj-llm-test" (make-array java.nio.file.attribute.FileAttribute 0))
              file (str dir "/llm.edn")]
          (spit file "{:lib/providers {:a {:lib/adapter :anthropic
                                             :api-key #env \"NOT_SET_ANYWHERE\"}}
                       :lib/defaults {:lib/max-tokens #profile {:dev 1 :default 2}}}")
          (let [config (init-key :clj-llm/config
                                 {:path file :profile :dev})]
            (is (= 1 (get-in config [:lib/defaults :lib/max-tokens])))
            (is (nil? (get-in config [:lib/providers :a :api-key]))))))
      (testing "init throws without a config source"
        (is (thrown-with-msg? Exception #":path, :resource or :config"
                              (init-key :clj-llm/config {})))))))
