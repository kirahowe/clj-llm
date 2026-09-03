(ns clj-llm.http-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clj-llm.http :as http]
            [clj-llm.provider :as provider]
            [clj-llm.providers.anthropic]
            [clj-llm.providers.ollama]
            [clj-llm.providers.openai])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException)
           (java.net ConnectException InetSocketAddress)
           (java.net.http HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent CountDownLatch ScheduledThreadPoolExecutor
                                 TimeUnit)))

(def ^:dynamic *base-url* nil)
(def stream-closed (atom nil))
(def requests-seen (atom 0))
(def large-payload
  (.repeat "x" (* 64 1024)))

(defn- utf8-bytes [s] (.getBytes ^String s StandardCharsets/UTF_8))

(defn- respond
  ([exchange body] (respond exchange 200 body))
  ([^HttpExchange exchange status body]
   (let [content (utf8-bytes body)]
     (.sendResponseHeaders exchange status (alength content))
     (with-open [out (.getResponseBody exchange)]
       (.write out content)))))

(defn- respond-stream [^HttpExchange exchange body]
  (.sendResponseHeaders exchange 200 0)
  (with-open [out (.getResponseBody exchange)]
    (.write out (utf8-bytes body))
    (.flush out)))

(defn- slow-body [^HttpExchange exchange]
  (let [content (utf8-bytes "\"slow\"")]
    (.sendResponseHeaders exchange 200 (alength content))
    (with-open [out (.getResponseBody exchange)]
      (try
        (.write out content 0 1)
        (.flush out)
        (Thread/sleep 150)
        (.write out content 1 (dec (alength content)))
        (catch IOException _)))))

(defn- slow-stream [^HttpExchange exchange idle?]
  (let [closed @stream-closed]
    (.sendResponseHeaders exchange 200 0)
    (with-open [out (.getResponseBody exchange)]
      (try
        (if idle?
          (do
            (.flush out)
            (Thread/sleep 150)
            (loop []
              (.write out (byte-array 65536))
              (.flush out)
              (recur)))
          (loop []
            (.write out (utf8-bytes "x\n"))
            (.flush out)
            (Thread/sleep 15)
            (recur)))
        (catch IOException _
          (deliver closed true))))))

(defn- handle [^HttpExchange exchange]
  (swap! requests-seen inc)
  (case (.getPath (.getRequestURI exchange))
    "/body" (respond exchange "\"é\"")
    "/error" (respond exchange 500 "\"é\"")
    "/large-body" (respond exchange large-payload)
    "/slow-body" (slow-body exchange)
    "/line" (respond-stream exchange "éé\n")
    "/large-line" (respond-stream exchange large-payload)
    "/cumulative" (respond-stream exchange "é\nx\n")
    "/sse" (respond-stream exchange
                           "event: message\r\ndata: {\"x\":\"é\"}\r\n\r\n")
    "/ndjson" (respond-stream exchange "{\"x\":1}\n\n{\"x\":2}")
    "/reducer-latency" (respond-stream exchange "one\ntwo\n")
    "/idle" (slow-stream exchange true)
    "/whole" (slow-stream exchange false)))

(defn- with-server [run-tests]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange] (handle exchange))))
    (.start server)
    (try
      (binding [*base-url* (str "http://127.0.0.1:"
                                (.getPort (.getAddress server)))]
        (reset! requests-seen 0)
        (run-tests))
      (finally
        (.stop server 0)))))

(use-fixtures :each with-server)

(defn- request [path options]
  (merge {:url (str *base-url* path) :body {}} options))

(defn- response-limit [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      e)))

(deftest invalid-http-options-fail-before-request
  (doseq [key [:timeout-ms
               :max-response-bytes
               :max-stream-line-bytes
               :max-stream-bytes
               :stream-timeout-ms
               :stream-idle-timeout-ms]]
    (let [data (ex-data
                (response-limit
                 #(http/post-json (request "/body" {key 0}))))]
      (is (= {:type :llm/config-error :key key :value 0}
             (select-keys data [:type :key :value])))))
  (doseq [value [nil -1 1.5 "10"]]
    (let [data (ex-data
                (response-limit
                 #(http/post-json
                   (request "/body" {:max-response-bytes value}))))]
      (is (= {:type :llm/config-error
              :key :max-response-bytes
              :value value}
             (select-keys data [:type :key :value])))))
  (is (zero? @requests-seen)))

(deftest endpoint-normalization
  (doseq [[url expected]
          [["HTTP://LOCALHOST:11434"
            {:scheme "http" :host "localhost" :port 11434 :path "/"}]
           ["http://127.0.0.1:8080/a/../v1"
            {:scheme "http" :host "127.0.0.1" :port 8080 :path "/v1"}]
           ["http://10.0.0.7/internal"
            {:scheme "http" :host "10.0.0.7" :port 80 :path "/internal"}]
           ["http://169.254.169.254/latest"
            {:scheme "http" :host "169.254.169.254" :port 80 :path "/latest"}]
           ["http://[::1]:65535/api"
            {:scheme "http" :host "::1" :port 65535 :path "/api"}]
           ["https://[fd00::1]/models"
            {:scheme "https" :host "fd00::1" :port 443 :path "/models"}]]]
    (let [seen (atom nil)
          data
          (ex-data
           (response-limit
            #(http/post-json
              {:url url
               :endpoint-policy
               (fn [destination]
                 (reset! seen destination)
                 false)
               :body {}})))]
      (is (= {:type :llm/endpoint-rejected} data))
      (is (= expected @seen))))
  (is (zero? @requests-seen)))

(deftest invalid-endpoints-fail-before-request
  (doseq [[url reason]
          [[nil :malformed]
           ["http://[" :malformed]
           ["/body" :relative]
           ["file:///etc/passwd" :unsupported-scheme]
           ["http:///body" :missing-host]
           [(str/replace-first *base-url* "http://" "http://user:secret@")
            :user-info]
           [(str *base-url* "/body#fragment") :fragment]
           [(str *base-url* "/body?api-key=secret") :query]
           ["http://localhost:0/body" :invalid-port]
           ["http://localhost:65536/body" :invalid-port]]]
    (let [data (ex-data
                (response-limit
                 #(http/post-json {:url url
                                   :headers {"authorization" "secret"}
                                   :body {:prompt "secret prompt"}})))]
      (is (= {:type :llm/invalid-endpoint :reason reason}
             data))
      (is (not (str/includes? (pr-str data) "secret")))))
  (is (zero? @requests-seen)))

(deftest endpoint-policy-runs-before-network-io
  (let [destinations (atom [])
        allow (fn [destination]
                (swap! destinations conj destination)
                true)]
    (is (= "é"
           (:body
            (http/post-json
             (request "/a/../body" {:endpoint-policy allow})))))
    (is (= [{:scheme "http"
             :host "127.0.0.1"
             :port (-> *base-url* (str/split #":") last parse-long)
             :path "/body"}]
           @destinations))
    (is (= 1 @requests-seen))
    (doseq [decision [false nil]]
      (let [data
            (ex-data
             (response-limit
              #(http/post-json
                (request "/body"
                         {:endpoint-policy (constantly decision)
                          :headers {"authorization" "secret"}
                          :body {:prompt "secret prompt"}}))))]
        (is (= {:type :llm/endpoint-rejected} data))
        (is (not (str/includes? (pr-str data) "secret")))))
    (let [data
          (ex-data
           (response-limit
            #(http/post-json-lines
              (request "/ndjson" {:endpoint-policy (constantly false)})
              conj
              [])))]
      (is (= {:type :llm/endpoint-rejected} data)))
    (let [data
          (ex-data
           (response-limit
            #(http/post-json
              (request "/body"
                       {:endpoint-policy
                        (fn [_]
                          (throw (ex-info "policy secret" {:secret "value"})))
                        :headers {"authorization" "header secret"}
                        :body {:prompt "prompt secret"}}))))]
      (is (= {:type :llm/endpoint-policy-error} data))
      (is (not (str/includes? (pr-str data) "secret"))))
    (let [hostname (atom nil)
          data
          (ex-data
           (response-limit
            #(http/post-json
              {:url "https://does-not-resolve.invalid/v1"
               :endpoint-policy
               (fn [destination]
                 (reset! hostname (:host destination))
                 false)
               :body {}})))]
      (is (= "does-not-resolve.invalid" @hostname))
      (is (= {:type :llm/endpoint-rejected} data)))
    (is (= 1 @requests-seen)
        "rejected and failed policies never reach either local or DNS I/O")))

(deftest invalid-endpoint-policy-fails-before-request
  (is (= {:type :llm/config-error :key :endpoint-policy}
         (ex-data
          (response-limit
           #(http/post-json
             (request "/body" {:endpoint-policy :not-a-function}))))))
  (is (zero? @requests-seen)))

(deftest network-error-messages-include-actionable-reasons
  (let [network-error! (ns-resolve 'clj-llm.http 'network-error!)
        url "http://127.0.0.1:1/v1"]
    (testing "blank cause messages use the cause class"
      (doseq [cause [(ConnectException.) (ConnectException. "   ")]]
        (let [exception (response-limit #(network-error! url cause))]
          (is (= (str "Network error calling " url ": ConnectException")
                 (ex-message exception)))
          (is (= {:type :llm/network-error :url url}
                 (ex-data exception)))
          (is (identical? cause (ex-cause exception))))))
    (testing "nonblank cause messages remain readable"
      (let [cause (ConnectException. "Connection refused")
            exception (response-limit #(network-error! url cause))]
        (is (= (str "Network error calling " url ": Connection refused")
               (ex-message exception)))
        (is (= {:type :llm/network-error :url url}
               (ex-data exception)))
        (is (identical? cause (ex-cause exception)))))))

(deftest non-streaming-timeout-covers-body-consumption
  (let [exception
        (response-limit
         #(http/post-json
           (request "/slow-body" {:timeout-ms 30})))]
    (is (= :llm/network-error (:type (ex-data exception))))
    (is (instance? HttpTimeoutException (ex-cause exception)))))

(deftest non-streaming-timeout-survives-delayed-deadline-timer
  ;; Occupy the single deadline-executor thread so the JDK request timeout,
  ;; not the library's close-timer, aborts the exchange — the ordering that
  ;; machine load produces, which used to leak a plain IOException cause.
  (let [executor ^ScheduledThreadPoolExecutor
        @(ns-resolve 'clj-llm.http 'deadline-executor)
        gate (CountDownLatch. 1)]
    (.schedule executor ^Runnable (fn [] (.await gate)) 0 TimeUnit/MILLISECONDS)
    (try
      (let [exception
            (response-limit
             #(http/post-json
               (request "/slow-body" {:timeout-ms 30})))]
        (is (= :llm/network-error (:type (ex-data exception))))
        (is (instance? HttpTimeoutException (ex-cause exception))))
      (finally
        (.countDown gate)))))

(deftest deadline-failure-reports-timeouts-past-the-deadline
  (let [deadline-failure @(ns-resolve 'clj-llm.http 'deadline-failure)
        url "http://localhost/v1"
        expired (- (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS 100))
        live (System/nanoTime)]
    (testing "an IOException before the deadline passes through"
      (let [e (IOException. "reset")]
        (is (identical? e (deadline-failure url live 30000 e)))))
    (testing "an HttpTimeoutException passes through unchanged"
      (let [e (HttpTimeoutException. "already a timeout")]
        (is (identical? e (deadline-failure url expired 30 e)))))
    (testing "an IOException past the deadline becomes the timeout"
      (let [e (IOException. "closed")
            converted (deadline-failure url expired 30 e)]
        (is (instance? HttpTimeoutException converted))
        (is (identical? e (ex-cause converted)))
        (is (= "Request timed out after 30ms calling http://localhost/v1"
               (ex-message converted)))))))

(deftest built-in-adapters-forward-every-http-option
  (let [policy (constantly true)
        options {:timeout-ms 110
                 :max-response-bytes 101
                 :max-stream-line-bytes 102
                 :max-stream-bytes 103
                 :stream-timeout-ms 104
                 :stream-idle-timeout-ms 105
                 :endpoint-policy policy}
        providers [(merge {:llm/adapter :anthropic :api-key "test"} options)
                   (merge {:llm/adapter :openai} options)
                   (merge {:llm/adapter :ollama} options)]
        generation {:llm/model "m"
                    :llm/messages [{:role :user :content "hi"}]}
        captured (atom [])
        capture (fn [http-request & _]
                  (swap! captured conj http-request)
                  (throw (ex-info "captured" {:captured true})))]
    (with-redefs [http/post-json capture
                  http/post-json-lines capture]
      (doseq [provider-config providers
              request [generation
                       (assoc generation :llm/on-chunk identity)]]
        (response-limit
         #(provider/generate! provider-config request)))
      (doseq [provider-config [(second providers) (nth providers 2)]]
        (response-limit
         #(provider/embed! provider-config
                           {:llm/model "m" :llm/input ["text"]}))))
    (is (= 8 (count @captured)))
    (doseq [http-request @captured]
      (is (= options (http/request-options http-request))))
    (is (= {"https://api.anthropic.com/v1/messages" 2
            "https://api.openai.com/v1/chat/completions" 2
            "https://api.openai.com/v1/embeddings" 1
            "http://localhost:11434/api/chat" 2
            "http://localhost:11434/api/embed" 1}
           (frequencies (map :url @captured))))))

(deftest non-streaming-body-boundaries
  (testing "a multibyte UTF-8 success body is accepted at exactly its byte limit"
    (is (= "é" (:body (http/post-json
                       (request "/body" {:max-response-bytes 4}))))))
  (testing "the next byte is rejected without exposing the success body"
    (let [data (ex-data (response-limit
                         #(http/post-json
                           (request "/body" {:max-response-bytes 3}))))]
      (is (= {:type :llm/response-limit
              :limit-kind :response-bytes
              :limit 3
              :status 200}
             (select-keys data [:type :limit-kind :limit :status])))
      (is (not (contains? data :body)))))
  (testing "error bodies use the same exact and over-limit behavior"
    (let [within (response-limit
                  #(http/post-json
                    (request "/error" {:max-response-bytes 4})))
          over (response-limit
                #(http/post-json
                  (request "/error" {:max-response-bytes 3})))]
      (is (= {:type :llm/http-error :status 500 :body "é"}
             (select-keys (ex-data within) [:type :status :body])))
      (is (= {:type :llm/response-limit
              :limit-kind :response-bytes
              :limit 3
              :status 500}
             (select-keys (ex-data over)
                          [:type :limit-kind :limit :status])))
      (is (not (contains? (ex-data over) :body))))))

(deftest streaming-line-byte-boundaries
  (testing "UTF-8 bytes, not characters, determine the exact line boundary"
    (is (= ["éé"]
           (http/post-json-lines
            (request "/line" {:max-stream-line-bytes 4
                              :max-stream-bytes 5})
            conj
            []))))
  (testing "one byte over the line limit is typed and body-free"
    (let [data (ex-data
                (response-limit
                 #(http/post-json-lines
                   (request "/line" {:max-stream-line-bytes 3})
                   conj
                   [])))]
      (is (= {:type :llm/response-limit
              :limit-kind :stream-line-bytes
              :limit 3}
             (select-keys data [:type :limit-kind :limit])))
      (is (not (contains? data :body))))))

(deftest materially-large-body-and-line-are-bounded
  (let [body-data
        (ex-data
         (response-limit
          #(http/post-json
            (request "/large-body" {:max-response-bytes 1024}))))
        line-data
        (ex-data
         (response-limit
          #(http/post-json-lines
            (request "/large-line" {:max-stream-line-bytes 1024})
            conj
            [])))]
    (is (= {:type :llm/response-limit
            :limit-kind :response-bytes
            :limit 1024}
           (select-keys body-data [:type :limit-kind :limit])))
    (is (= {:type :llm/response-limit
            :limit-kind :stream-line-bytes
            :limit 1024}
           (select-keys line-data [:type :limit-kind :limit])))
    (is (not (contains? body-data :body)))
    (is (not (contains? line-data :body)))))

(deftest cumulative-stream-byte-boundaries
  (testing "cumulative bytes include both UTF-8 bytes and line delimiters"
    (is (= ["é" "x"]
           (http/post-json-lines
            (request "/cumulative" {:max-stream-line-bytes 2
                                    :max-stream-bytes 5})
            conj
            []))))
  (testing "the byte after an exact cumulative boundary is rejected"
    (let [data (ex-data
                (response-limit
                 #(http/post-json-lines
                   (request "/cumulative" {:max-stream-bytes 4})
                   conj
                   [])))]
      (is (= {:type :llm/response-limit
              :limit-kind :stream-bytes
              :limit 4}
             (select-keys data [:type :limit-kind :limit]))))))

(deftest ordinary-sse-and-ndjson-streams
  (is (= [{:x "é"}]
         (http/post-json-lines
          (request "/sse" {})
          (fn [events line]
            (if-let [data (http/sse-data line)]
              (conj events (json/parse-string data true))
              events))
          [])))
  (is (= [{:x 1} {:x 2}]
         (http/post-json-lines
          (request "/ndjson" {})
          (fn [events line]
            (conj events (json/parse-string line true)))
          []))))

(deftest idle-read-deadline-closes-stream
  (reset! stream-closed (promise))
  (let [data (ex-data
              (response-limit
               #(http/post-json-lines
                 (request "/idle" {:stream-idle-timeout-ms 30
                                   :stream-timeout-ms 500})
                 conj
                 [])))]
    (is (= {:type :llm/response-limit
            :limit-kind :stream-idle-timeout-ms
            :limit 30}
           (select-keys data [:type :limit-kind :limit])))
    (is (true? (deref @stream-closed 1000 false)))))

(deftest whole-stream-deadline-closes-endless-stream
  (reset! stream-closed (promise))
  (let [data (ex-data
              (response-limit
               #(http/post-json-lines
                 (request "/whole" {:stream-idle-timeout-ms 100
                                    :stream-timeout-ms 70})
                 conj
                 [])))]
    (is (= {:type :llm/response-limit
            :limit-kind :stream-timeout-ms
            :limit 70}
           (select-keys data [:type :limit-kind :limit])))
    (is (true? (deref @stream-closed 1000 false)))))

(deftest reducer-work-does-not-count-as-provider-idle-time
  (is (= ["one" "two"]
         (http/post-json-lines
          (request "/reducer-latency" {:stream-idle-timeout-ms 20
                                       :stream-timeout-ms 500})
          (fn [lines line]
            (Thread/sleep 40)
            (conj lines line))
          []))))
