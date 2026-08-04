(ns clj-llm.http
  "HTTP support on java.net.http — the JDK's built-in client, so the
  library adds no HTTP dependencies. JSON in, JSON (or a reduction over
  response lines, for SSE/NDJSON streaming) out."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

(def default-timeout-ms
  "Default request timeout: time allowed until the response (for
  streaming: until the response headers) arrives."
  120000)

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(defn- build-request ^HttpRequest [{:keys [url headers body timeout-ms]}]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
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
                  {:type :lib/http-error
                   :status status
                   :url url
                   :body body})))

(defn post-json
  "POST `body` as JSON to `url`. Returns {:status n :body <parsed JSON,
  keyword keys>}. Throws ex-info {:type :lib/http-error :status ...
  :body ...} on a non-2xx response."
  [{:keys [url] :as request}]
  (let [^HttpResponse response (.send ^HttpClient @client
                                      (build-request request)
                                      (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)]
    (if (<= 200 status 299)
      {:status status :body (parse-json (.body response))}
      (error! url status (parse-json (.body response))))))

(defn post-json-lines
  "POST `body` as JSON to `url` and reduce (f acc line) over each line of
  the response as it arrives — the streaming transport for both SSE and
  NDJSON. Blank lines are skipped. Returns the final accumulator.
  Throws like `post-json` on a non-2xx response."
  [{:keys [url] :as request} f init]
  (let [^HttpResponse response (.send ^HttpClient @client
                                      (build-request request)
                                      (HttpResponse$BodyHandlers/ofInputStream))
        status (.statusCode response)]
    (if (<= 200 status 299)
      (with-open [reader (io/reader (.body response))]
        (reduce (fn [acc line]
                  (if (str/blank? line) acc (f acc line)))
                init
                (line-seq reader)))
      (error! url status (parse-json (slurp (.body response)))))))

(defn sse-data
  "Given one line of a server-sent-events stream, return the data payload
  string, or nil for non-data lines (event names, comments, blanks)."
  [^String line]
  (when (str/starts-with? line "data:")
    (str/triml (subs line 5))))
