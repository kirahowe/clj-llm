(ns clj-llm.http
  "HTTP support on java.net.http — the JDK's built-in client, so the
  library adds no HTTP dependencies. JSON in, JSON (or a reduction over
  response lines, for SSE/NDJSON streaming) out.

  Every request destination is normalized and must be absolute HTTP(S)
  with a host, no user-info, query, or fragment, and a valid effective
  port. Optional `:endpoint-policy` receives only the immutable
  `{:scheme :host :port :path}` destination before JDK request
  construction or network I/O. False/nil rejects with
  `:llm/endpoint-rejected`; policy exceptions become
  `:llm/endpoint-policy-error`. Invalid destinations
  use `:llm/invalid-endpoint` with a safe `:reason`; these local error maps
  never contain URLs, headers, request bodies, credentials, or prompts.

  The policy does not resolve or pin hostnames. It is suitable for
  scheme/host/port/path allowlists, not DNS-stable IP/network-range
  enforcement."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream IOException InputStream)
           (java.net URI URISyntaxException)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers HttpTimeoutException)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory
                                 TimeUnit)
           (java.util.concurrent.atomic AtomicReference)))

(def default-timeout-ms
  "Default request timeout. It covers the complete non-streaming response
  and reaches through response headers for a streaming response."
  120000)

(def default-max-response-bytes
  "Default decoded byte limit for a non-streaming success or error body."
  (* 8 1024 1024))

(def default-max-stream-line-bytes
  "Default decoded byte limit for one SSE or NDJSON line, excluding its
  line delimiter."
  (* 1024 1024))

(def default-max-stream-bytes
  "Default cumulative decoded byte limit for a stream, including line
  delimiters."
  (* 32 1024 1024))

(def default-stream-timeout-ms
  "Default deadline for consuming a streaming response after its headers
  arrive."
  (* 10 60 1000))

(def default-stream-idle-timeout-ms
  "Default time a streaming response may spend blocked on one provider
  read. Time spent in the line reducer does not count as idle."
  60000)

(def ^:private positive-request-option-keys
  [:timeout-ms
   :max-response-bytes
   :max-stream-line-bytes
   :max-stream-bytes
   :stream-timeout-ms
   :stream-idle-timeout-ms])

(def ^:private request-option-keys
  (conj positive-request-option-keys :endpoint-policy))

(defn request-options
  "Select the shared HTTP request options from a provider config. Numeric
  values must be positive integers. `:endpoint-policy`, when present, is
  called with the normalized destination before JDK request construction
  and before network I/O."
  [provider-config]
  (select-keys provider-config request-option-keys))

(defn- validate-request-options! [request]
  (doseq [key positive-request-option-keys
          :when (contains? request key)]
    (let [value (get request key)]
      (when-not (and (integer? value)
                     (pos? value)
                     (<= value Long/MAX_VALUE))
        (throw (ex-info (str "HTTP option " key
                             " must be a positive integer")
                        {:type :llm/config-error
                         :key key
                         :value value})))))
  (when (and (contains? request :endpoint-policy)
             (not (fn? (:endpoint-policy request))))
    (throw (ex-info "HTTP option :endpoint-policy must be a function"
                    {:type :llm/config-error
                     :key :endpoint-policy})))
  request)

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NEVER)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(def ^:private ^ScheduledThreadPoolExecutor deadline-executor
  (doto
   (ScheduledThreadPoolExecutor.
    1
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable "clj-llm-http-deadlines")
          (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)
    (.setExecuteExistingDelayedTasksAfterShutdownPolicy false)))

(defn- invalid-endpoint! [reason]
  (throw (ex-info "Invalid HTTP endpoint configuration"
                  {:type :llm/invalid-endpoint
                   :reason reason})))

(defn- destination
  [url]
  (when-not (string? url)
    (invalid-endpoint! :malformed))
  (let [uri (try
              (URI. ^String url)
              (catch URISyntaxException _
                (invalid-endpoint! :malformed)))]
    (when-not (.isAbsolute uri)
      (invalid-endpoint! :relative))
    (let [scheme (some-> (.getScheme uri) str/lower-case)]
      (when-not (#{"http" "https"} scheme)
        (invalid-endpoint! :unsupported-scheme))
      (when (some? (.getRawUserInfo uri))
        (invalid-endpoint! :user-info))
      (when (some? (.getRawFragment uri))
        (invalid-endpoint! :fragment))
      (when (some? (.getRawQuery uri))
        (invalid-endpoint! :query))
      (let [server-uri (try
                         (.parseServerAuthority uri)
                         (catch URISyntaxException _
                           (invalid-endpoint! :malformed)))
            normalized-uri (.normalize server-uri)
            raw-host (.getHost normalized-uri)]
        (when (str/blank? raw-host)
          (invalid-endpoint! :missing-host))
        (let [explicit-port (.getPort normalized-uri)]
          (when (or (< explicit-port -1)
                    (zero? explicit-port)
                    (> explicit-port 65535))
            (invalid-endpoint! :invalid-port))
          (let [host (str/lower-case raw-host)
                host (if (and (str/starts-with? host "[")
                              (str/ends-with? host "]"))
                       (subs host 1 (dec (count host)))
                       host)
                path (.getRawPath normalized-uri)]
            {:uri normalized-uri
             :destination {:scheme scheme
                           :host host
                           :port (if (= -1 explicit-port)
                                   (if (= "https" scheme) 443 80)
                                   explicit-port)
                           :path (if (str/blank? path) "/" path)}}))))))

(defn- enforce-endpoint-policy! [policy normalized-destination]
  (when policy
    (let [accepted?
          (try
            (policy normalized-destination)
            (catch Exception e
              (throw (ex-info "HTTP endpoint policy failed"
                              {:type :llm/endpoint-policy-error}
                              e))))]
      (when-not accepted?
        (throw (ex-info "HTTP endpoint rejected by policy"
                        {:type :llm/endpoint-rejected}))))))

(defn- build-request ^HttpRequest [request]
  (validate-request-options! request)
  (let [{:keys [url headers body timeout-ms endpoint-policy]} request
        {:keys [uri destination]} (destination url)
        _ (enforce-endpoint-policy! endpoint-policy destination)
        builder (-> (HttpRequest/newBuilder uri)
                    (.timeout (Duration/ofMillis (or timeout-ms default-timeout-ms)))
                    (.header "content-type" "application/json")
                    (.header "accept" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/generate-string body))))]
    (doseq [[k v] headers]
      (.header builder (name k) (str v)))
    (.build builder)))

(defn- parse-json [s]
  (when-not (str/blank? s)
    (try
      (json/parse-string s true)
      (catch Exception _ s))))

(defn- error! [url status body]
  (throw (ex-info (str "LLM provider returned HTTP " status " for " url)
                  {:type :llm/http-error
                   :status status
                   :url url
                   :body body})))

(defn- limit! [url kind limit extra]
  (throw (ex-info (str "LLM provider response exceeded " (name kind)
                       " limit of " limit " for " url)
                  (merge {:type :llm/response-limit
                          :url url
                          :limit-kind kind
                          :limit limit}
                         extra))))

(defn- network-error! [url e]
  (let [message (ex-message e)
        reason (if (str/blank? message)
                 (.getSimpleName (class e))
                 message)]
    (throw (ex-info (str "Network error calling " url ": " reason)
                    {:type :llm/network-error :url url}
                    e))))

(defn- request-timeout-exception [url timeout-ms]
  (HttpTimeoutException.
   (str "Request timed out after " timeout-ms "ms calling " url)))

(defn- deadline-failure
  "The JDK's request-level timeout races this namespace's deadline timer;
  when it wins it tears the exchange down and the blocked read surfaces a
  plain IOException (e.g. \"closed\") instead of a timeout. Once the
  request deadline has expired, any IOException IS the timeout, so report
  it as one and keep the original as its cause."
  [url started-ns timeout-ms e]
  (if (or (instance? HttpTimeoutException e)
          (< (- (System/nanoTime) started-ns)
             (.toNanos TimeUnit/MILLISECONDS (long timeout-ms))))
    e
    (doto ^Exception (request-timeout-exception url timeout-ms)
      (.initCause e))))

(defn- request-timed-read
  [^InputStream input ^bytes buffer requested url started-ns timeout-ms]
  (let [remaining (- (.toNanos TimeUnit/MILLISECONDS (long timeout-ms))
                     (- (System/nanoTime) started-ns))]
    (when (<= remaining 0)
      (throw (request-timeout-exception url timeout-ms)))
    (let [state (AtomicReference.)
          task (.schedule
                deadline-executor
                ^Runnable
                (reify Runnable
                  (run [_]
                    (when (.compareAndSet state nil :timed-out)
                      (try
                        (.close input)
                        (catch IOException _)))))
                (long remaining)
                TimeUnit/NANOSECONDS)]
      (try
        (let [result (try
                       [:read (.read input buffer 0 requested)]
                       (catch IOException e [:error e]))
              read-completed? (.compareAndSet state nil :read-completed)]
          (.cancel task false)
          (if read-completed?
            (if (= :read (first result))
              (second result)
              (throw (second result)))
            (throw (request-timeout-exception url timeout-ms))))
        (finally
          (.cancel task false))))))

(defn- body-bytes
  [^InputStream input url status limit started-ns timeout-ms]
  (with-open [input input
              out (ByteArrayOutputStream. (int (min limit 8192)))]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [remaining (- limit total)
              requested (if (< remaining 8192)
                          (inc (int remaining))
                          8192)
              read (request-timed-read input buffer requested url started-ns
                                       timeout-ms)]
          (cond
            (= -1 read)
            (.toByteArray out)

            (> read remaining)
            (limit! url :response-bytes limit {:status status})

            :else
            (do
              (.write out buffer 0 read)
              (recur (+ total read)))))))))

(defn- check-whole-deadline!
  [url started-ns timeout-ms]
  (when (>= (- (System/nanoTime) started-ns)
            (.toNanos TimeUnit/MILLISECONDS (long timeout-ms)))
    (limit! url :stream-timeout-ms timeout-ms {})))

(defn- timed-read
  [^InputStream input ^bytes buffer url started-ns whole-timeout-ms
   idle-timeout-ms]
  (let [whole-remaining (- (.toNanos TimeUnit/MILLISECONDS
                                     (long whole-timeout-ms))
                           (- (System/nanoTime) started-ns))
        idle-ns (.toNanos TimeUnit/MILLISECONDS (long idle-timeout-ms))]
    (when (<= whole-remaining 0)
      (limit! url :stream-timeout-ms whole-timeout-ms {}))
    (let [[kind limit delay-ns]
          (if (<= whole-remaining idle-ns)
            [:stream-timeout-ms whole-timeout-ms whole-remaining]
            [:stream-idle-timeout-ms idle-timeout-ms idle-ns])
          state (AtomicReference.)
          task (.schedule
                deadline-executor
                ^Runnable
                (reify Runnable
                  (run [_]
                    (when (.compareAndSet state nil [kind limit])
                      (try
                        (.close input)
                        (catch IOException _)))))
                (long delay-ns)
                TimeUnit/NANOSECONDS)]
      (try
        (let [result (try
                       [:read (.read input buffer)]
                       (catch IOException e [:error e]))
              read-completed? (.compareAndSet state nil :read-completed)]
          (.cancel task false)
          (if read-completed?
            (if (= :read (first result))
              (second result)
              (throw (second result)))
            (let [[expired-kind expired-limit] (.get state)]
              (limit! url expired-kind expired-limit {}))))
        (finally
          (.cancel task false))))))

(defn- emit-line
  [f acc ^ByteArrayOutputStream line-buffer url started-ns timeout-ms]
  (let [line (.toString line-buffer StandardCharsets/UTF_8)]
    (.reset line-buffer)
    (if (str/blank? line)
      acc
      (do
        (check-whole-deadline! url started-ns timeout-ms)
        (let [next-acc (f acc line)]
          (check-whole-deadline! url started-ns timeout-ms)
          next-acc)))))

(defn- reduce-lines
  [^InputStream input request f init]
  (let [{:keys [url]} request
        max-line (or (:max-stream-line-bytes request)
                     default-max-stream-line-bytes)
        max-stream (or (:max-stream-bytes request)
                       default-max-stream-bytes)
        whole-timeout (or (:stream-timeout-ms request)
                          default-stream-timeout-ms)
        idle-timeout (or (:stream-idle-timeout-ms request)
                         default-stream-idle-timeout-ms)
        started-ns (System/nanoTime)
        buffer (byte-array 8192)
        line-buffer (ByteArrayOutputStream. (int (min max-line 8192)))]
    (letfn [(consume [read acc total skip-lf?]
              (loop [index 0
                     acc acc
                     total total
                     skip-lf? skip-lf?]
                (if (= index read)
                  [acc total skip-lf? false]
                  (let [byte-value (bit-and (aget buffer index) 0xff)
                        next-total (inc total)]
                    (when (> next-total max-stream)
                      (limit! url :stream-bytes max-stream {}))
                    (cond
                      (and skip-lf? (= byte-value 10))
                      (recur (inc index) acc next-total false)

                      (or (= byte-value 10) (= byte-value 13))
                      (let [next-acc (emit-line f acc line-buffer url
                                                started-ns whole-timeout)]
                        (if (reduced? next-acc)
                          [@next-acc next-total (= byte-value 13) true]
                          (recur (inc index) next-acc next-total
                                 (= byte-value 13))))

                      :else
                      (do
                        (when (>= (.size line-buffer) max-line)
                          (limit! url :stream-line-bytes max-line {}))
                        (.write line-buffer byte-value)
                        (recur (inc index) acc next-total false)))))))]
      (with-open [input input]
        (loop [acc init
               total 0
               skip-lf? false]
          (let [read (timed-read input buffer url started-ns whole-timeout
                                 idle-timeout)]
            (if (= -1 read)
              (if (pos? (.size line-buffer))
                (let [result (emit-line f acc line-buffer url started-ns
                                        whole-timeout)]
                  (if (reduced? result) @result result))
                acc)
              (let [[next-acc next-total next-skip-lf? done?]
                    (consume read acc total skip-lf?)]
                (if done?
                  next-acc
                  (recur next-acc next-total next-skip-lf?))))))))))

(defn post-json
  "POST `body` as JSON to `url`. Returns {:status n :body <parsed JSON,
  keyword keys>}. `:timeout-ms` covers the complete request, including
  bounded body consumption. `:max-response-bytes` bounds both success and
  error bodies before they are buffered. A limit failure throws ex-info
  {:type :llm/response-limit :limit-kind :response-bytes :limit n ...}
  without a response body. Non-2xx responses within the limit throw
  :llm/http-error; network and request-timeout failures throw
  :llm/network-error."
  [{:keys [url] :as request}]
  (let [started-ns (System/nanoTime)
        timeout-ms (or (:timeout-ms request) default-timeout-ms)]
    (try
      (let [^HttpResponse response
            (.send ^HttpClient @client
                   (build-request request)
                   (HttpResponse$BodyHandlers/ofInputStream))
            status (.statusCode response)
            limit (or (:max-response-bytes request)
                      default-max-response-bytes)
            bytes (body-bytes (.body response) url status limit
                              started-ns timeout-ms)
            body (parse-json (String. ^bytes bytes StandardCharsets/UTF_8))]
        (if (<= 200 status 299)
          {:status status :body body}
          (error! url status body)))
      (catch IOException e
        (network-error! url (deadline-failure url started-ns timeout-ms e))))))

(defn post-json-lines
  "POST `body` as JSON to `url` and reduce (f acc line) over non-blank
  SSE/NDJSON lines. `:timeout-ms` reaches through response headers.
  `:max-stream-line-bytes` excludes delimiters; `:max-stream-bytes`
  includes every decoded UTF-8 body byte and delimiter.
  `:stream-timeout-ms` bounds the body after headers, while
  `:stream-idle-timeout-ms` bounds each provider read (not reducer work).
  Non-2xx bodies use `:max-response-bytes`. Limit failures throw typed
  :llm/response-limit data containing :limit-kind and :limit, never a
  response body. The response stream is closed on every exit path."
  [{:keys [url] :as request} f init]
  (let [started-ns (System/nanoTime)
        timeout-ms (or (:timeout-ms request) default-timeout-ms)]
    (try
      (let [^HttpResponse response
            (.send ^HttpClient @client
                   (build-request request)
                   (HttpResponse$BodyHandlers/ofInputStream))
            status (.statusCode response)]
        (if (<= 200 status 299)
          (reduce-lines (.body response) request f init)
          (let [limit (or (:max-response-bytes request)
                          default-max-response-bytes)
                bytes (body-bytes (.body response) url status limit
                                  started-ns timeout-ms)]
            (error! url status
                    (parse-json (String. ^bytes bytes
                                         StandardCharsets/UTF_8))))))
      (catch IOException e
        (network-error! url e)))))

(defn sse-data
  "Given one line of a server-sent-events stream, return the data payload
  string, or nil for non-data lines (event names, comments, blanks)."
  [^String line]
  (when (str/starts-with? line "data:")
    (str/triml (subs line 5))))
