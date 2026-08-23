(ns clj-llm.provider-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-llm.core :as llm]
            [clj-llm.provider :as provider]))

(def response-format
  {:type :json-schema :name "answer" :schema {:type "object"}})

(defmethod provider/-generate! ::custom
  [{:keys [invocations]} request _opts]
  (when invocations (swap! invocations conj request))
  {:message {:role :assistant :content "{}"}
   :model (:llm/model request)
   :usage {}
   :finish-reason :stop
   :raw {}})

(defmethod provider/-supports? ::contextual
  [{:keys [support-context]} capability opts]
  (reset! support-context [capability opts])
  (= "supported-model" (get-in opts [:request :llm/model])))

(defmethod provider/-generate! ::contextual
  [{:keys [invocations]} request _opts]
  (swap! invocations conj request)
  {:message {:role :assistant :content "{}"}
   :model (:llm/model request)
   :usage {}
   :finish-reason :stop
   :raw {}})

(deftest support-dispatch-and-overrides
  (testing "custom adapters default to unsupported"
    (is (false? (provider/supports? {:llm/adapter ::custom}
                                    :json-schema-response))))
  (testing "built-in adapters opt in"
    (doseq [adapter [:anthropic :openai :ollama]]
      (is (true? (provider/supports? {:llm/adapter adapter}
                                     :json-schema-response)))))
  (testing "provider configuration explicitly overrides the SPI"
    (is (true? (provider/supports?
                {:llm/adapter ::custom
                 :llm/capabilities {:json-schema-response true}}
                :json-schema-response)))
    (is (false? (provider/supports?
                 {:llm/adapter :openai
                  :llm/capabilities {:json-schema-response false}}
                 :json-schema-response)))))

(deftest capability-guard
  (testing "unsupported structured requests fail before adapter invocation"
    (let [invocations (atom [])
          provider-config {:llm/adapter ::custom
                           :llm/name :configured
                           :invocations invocations}
          request {:llm/model "resolved-model"
                   :llm/messages [{:role :user :content "hi"}]
                   :llm/response-format response-format}
          exception (try
                      (provider/generate! provider-config request)
                      nil
                      (catch Exception e e))]
      (is (= [] @invocations))
      (is (= {:type :llm/unsupported-capability
              :provider :configured
              :adapter ::custom
              :model "resolved-model"
              :capability :json-schema-response}
             (ex-data exception)))))
  (testing "ordinary requests are unchanged and do not require support"
    (let [invocations (atom [])
          request {:llm/model "m"
                   :llm/messages [{:role :user :content "hi"}]}]
      (provider/generate! {:llm/adapter ::custom :invocations invocations}
                          request)
      (is (= [request] @invocations))))
  (testing "an opted-in custom adapter receives the request unchanged"
    (let [invocations (atom [])
          request {:llm/model "m"
                   :llm/messages [{:role :user :content "hi"}]
                   :llm/response-format response-format}]
      (provider/generate!
       {:llm/adapter ::custom :invocations invocations
        :llm/capabilities {:json-schema-response true}}
       request)
      (is (= [request] @invocations)))))

(deftest normalized-request-is-capability-context
  (let [support-context (atom nil)
        invocations (atom [])
        config {:llm/providers
                {:custom {:llm/adapter ::contextual
                          :support-context support-context
                          :invocations invocations}}
                :llm/models
                {:default {:llm/provider :custom
                           :llm/model "supported-model"}}
                :llm/defaults {:llm/model :default}}
        response (llm/generate config "hi" {:llm/response-format response-format})
        [capability {:keys [request]}] @support-context]
    (is (= :json-schema-response capability))
    (is (= "supported-model" (:llm/model request)))
    (is (= [{:role :user :content "hi"}] (:llm/messages request)))
    (is (= request (first @invocations)))
    (is (= {} (:llm/structured response)))))
