(ns clj-llm.integration-test
  "End-to-end tests over real HTTP against an in-process server standing
  in for each provider — exercises the java.net.http transport, JSON
  encoding, header handling and SSE/NDJSON streaming with no network."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-llm.core :as llm])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(def ^:dynamic *base-url* nil)
(def requests (atom []))

(defn- read-request [^HttpExchange exchange]
  {:path (.getPath (.getRequestURI exchange))
   :headers (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)]))
                  (.getRequestHeaders exchange))
   :body (json/parse-string (slurp (.getRequestBody exchange)) true)})

(defn- respond-json [^HttpExchange exchange data]
  (let [bytes (.getBytes (json/generate-string data) StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange 200 (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- respond-lines [^HttpExchange exchange content-type lines]
  (.add (.getResponseHeaders exchange) "Content-Type" content-type)
  (.sendResponseHeaders exchange 200 0)
  (with-open [out (.getResponseBody exchange)]
    (doseq [^String line lines]
      (.write out (.getBytes (str line "\n") StandardCharsets/UTF_8))
      (.flush out))))

(defn- sse [events]
  (mapcat (fn [event] [(str "event: " (:type event))
                       (str "data: " (json/generate-string event))
                       ""])
          events))

(defn- handle [^HttpExchange exchange]
  (let [request (read-request exchange)]
    (swap! requests conj request)
    (case (:path request)
      ;; Anthropic Messages API, non-streaming
      "/anthropic/v1/messages"
      (respond-json exchange
                    {:content [{:type "text" :text "Hi from fake Claude"}]
                     :model "claude-sonnet-4-6"
                     :stop_reason "end_turn"
                     :usage {:input_tokens 11 :output_tokens 5}})

      ;; OpenAI-compatible chat completions; streams SSE when asked
      "/openai/v1/chat/completions"
      (if (:stream (:body request))
        (respond-lines exchange "text/event-stream"
                       (concat
                        (sse [{:choices [{:delta {:content "str"}}]
                               :model "test-model"}
                              {:choices [{:delta {:content "eamed"}}]}
                              {:choices [{:delta {} :finish_reason "stop"}]}
                              {:choices []
                               :usage {:prompt_tokens 8 :completion_tokens 2}}])
                        ["data: [DONE]" ""]))
        (respond-json exchange
                      {:choices [{:message {:role "assistant" :content "plain"}
                                  :finish_reason "stop"}]
                       :model "test-model"
                       :usage {:prompt_tokens 8 :completion_tokens 1}}))

      ;; Ollama native chat (NDJSON stream) and embeddings
      "/ollama/api/chat"
      (respond-lines exchange "application/x-ndjson"
                     [(json/generate-string {:model "llama3.2"
                                             :message {:content "lo"} :done false})
                      (json/generate-string {:model "llama3.2"
                                             :message {:content "cal"} :done false})
                      (json/generate-string {:model "llama3.2"
                                             :message {:content ""}
                                             :done true :done_reason "stop"
                                             :prompt_eval_count 4 :eval_count 2})])

      "/ollama/api/embed"
      (respond-json exchange {:model "nomic-embed-text"
                              :embeddings [[0.25 0.5 0.75]]
                              :prompt_eval_count 3})

      ;; Error path
      "/broken/v1/messages"
      (let [bytes (.getBytes (json/generate-string {:error {:message "bad key"}})
                             StandardCharsets/UTF_8)]
        (.sendResponseHeaders exchange 401 (alength bytes))
        (with-open [out (.getResponseBody exchange)]
          (.write out bytes))))))

(defn- with-server [run-tests]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange] (handle exchange))))
    (.start server)
    (try
      (binding [*base-url* (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
        (reset! requests [])
        (run-tests))
      (finally
        (.stop server 0)))))

(use-fixtures :each with-server)

(defn- config []
  #:lib{:providers {:anthropic {:lib/adapter :anthropic
                                :base-url (str *base-url* "/anthropic")
                                :api-key "test-key"}
                    :compat {:lib/adapter :openai
                             :base-url (str *base-url* "/openai/v1")
                             :api-key "compat-key"}
                    :local {:lib/adapter :ollama
                            :base-url (str *base-url* "/ollama")}
                    :broken {:lib/adapter :anthropic
                             :base-url (str *base-url* "/broken")
                             :api-key "wrong"}}
        :models {:default #:lib{:provider :anthropic :model "claude-sonnet-4-6"}
                 :embeddings #:lib{:provider :local :model "nomic-embed-text"}}
        :defaults #:lib{:model :default :embedding-model :embeddings}})

(deftest anthropic-round-trip
  (let [response (llm/generate (config) "hello")]
    (is (= "Hi from fake Claude" (:lib/text response)))
    (is (= :stop (:lib/finish-reason response)))
    (is (= {:input-tokens 11 :output-tokens 5} (:lib/usage response)))
    (testing "wire request carried auth and version headers"
      (let [{:keys [headers body]} (first @requests)]
        (is (= ["test-key"] (get headers "x-api-key")))
        (is (= ["2023-06-01"] (get headers "anthropic-version")))
        (is (= "claude-sonnet-4-6" (:model body)))
        (is (= [{:role "user" :content "hello"}] (:messages body)))))))

(deftest openai-compatible-round-trip
  (let [response (llm/generate (config) "hello" {:lib/model "compat/test-model"})]
    (is (= "plain" (:lib/text response)))
    (testing "bearer auth header"
      (is (= ["Bearer compat-key"]
             (get-in (first @requests) [:headers "authorization"]))))))

(deftest openai-streaming-round-trip
  (let [chunks (atom [])
        response (llm/generate (config) "hello"
                               {:lib/model "compat/test-model"
                                :lib/on-chunk #(swap! chunks conj %)})]
    (is (= "streamed" (str/join (map :text @chunks))))
    (is (every? #(= :text (:type %)) @chunks))
    (is (= "streamed" (:lib/text response)))
    (is (= :stop (:lib/finish-reason response)))
    (is (= {:input-tokens 8 :output-tokens 2} (:lib/usage response)))))

(deftest ollama-streaming-round-trip
  (let [chunks (atom [])
        response (llm/generate (config) "hello"
                               {:lib/model "local/llama3.2"
                                :lib/on-chunk #(swap! chunks conj %)})]
    (is (= "local" (str/join (map :text @chunks))))
    (is (every? #(= :text (:type %)) @chunks))
    (is (= "local" (:lib/text response)))
    (is (= {:input-tokens 4 :output-tokens 2} (:lib/usage response)))))

(deftest embeddings-round-trip
  (let [response (llm/embed (config) "embed me")]
    (is (= [0.25 0.5 0.75] (:lib/embedding response)))
    (is (= :local (:lib/provider response)))
    (is (= {:model "nomic-embed-text" :input ["embed me"]}
           (:body (first @requests))))))

(deftest http-errors-carry-status-and-body
  (let [ex (try
             (llm/generate (config) "hello" {:lib/model "broken/any"})
             nil
             (catch Exception e e))]
    (is (some? ex))
    (is (= :lib/http-error (:type (ex-data ex))))
    (is (= 401 (:status (ex-data ex))))
    (is (= "bad key" (get-in (ex-data ex) [:body :error :message])))))

(deftest network-errors-are-typed
  (let [config (assoc-in (config) [:lib/providers :anthropic :base-url]
                         "http://127.0.0.1:9")
        ex (try (llm/generate config "hello") nil (catch Exception e e))]
    (is (some? ex))
    (is (= :lib/network-error (:type (ex-data ex))))
    (is (instance? java.io.IOException (ex-cause ex)))))
