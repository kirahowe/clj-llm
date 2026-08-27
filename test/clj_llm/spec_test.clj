(ns clj-llm.spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.spec :as spec]
            [malli.core :as m]))

(def valid-format
  {:type :json-schema
   :name "proposal_v1"
   :schema {:type "object" :properties {:title {:type "string"}}}})

(deftest response-format-schema
  (is (m/validate spec/ResponseFormat valid-format))
  (doseq [format [(assoc valid-format :type :json-object)
                  (dissoc valid-format :type)
                  (dissoc valid-format :name)
                  (dissoc valid-format :schema)
                  (assoc valid-format :name "")
                  (assoc valid-format :name :proposal)
                  (assoc valid-format :name "contains spaces")
                  (assoc valid-format :name (apply str (repeat 65 "a")))
                  (assoc valid-format :schema true)]]
    (is (not (m/validate spec/ResponseFormat format)) (pr-str format))))

(deftest request-and-provider-capabilities-schema
  (is (m/validate spec/Request
                  {:llm/messages [{:role :user :content "hi"}]
                   :llm/response-format valid-format}))
  (is (m/validate spec/Request
                  {:llm/messages [{:role :user :content "hi"}]
                   :llm/max-tool-calls 0
                   :llm/tool-policy (constantly true)
                   :llm/tool-argument-validator (constantly true)}))
  (is (not (m/validate spec/Request
                       {:llm/messages [{:role :user :content "hi"}]
                        :llm/max-tool-calls -1})))
  (is (m/validate spec/Config
                  {:llm/providers {:p {:llm/adapter :custom
                                       :llm/capabilities
                                       {:json-schema-response true}}}}))
  (is (not (m/validate spec/Config
                       {:llm/providers {:p {:llm/adapter :custom
                                            :llm/capabilities
                                            {:json-schema-response :yes}}}}))))

(deftest structured-response-schema
  (testing "structured success includes JSON null as a present nil value"
    (is (m/validate spec/Response {:llm/structured nil})))
  (is (m/validate spec/Response
                  {:llm/structured-error
                   {:type :llm/invalid-structured-response
                    :message "invalid JSON"}}))
  (is (m/validate
       spec/Response
       {:llm/tool-rejections
        [{:tool-call {:id "call_1" :name "write" :arguments {:path "/tmp/x"}}
          :reason :tool-call-budget-exceeded
          :budget {:limit 1 :used 0 :requested 2 :remaining 1}}]}))
  (is (not (m/validate spec/Response
                       {:llm/structured-error
                        {:type :wrong :message "invalid JSON"}})))
  (is (not (m/validate spec/Response
                       {:llm/structured-error
                        {:type :llm/invalid-structured-response}})))
  (is (not (m/validate spec/Response
                       {:llm/structured {:ok true}
                        :llm/structured-error
                        {:type :llm/invalid-structured-response
                         :message "invalid JSON"}}))))
