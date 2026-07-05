(ns kirahowe.clj-llm.http
  "Minimal HTTP support on top of java.net.http — no extra dependencies.
  JSON in, JSON (or a reduction over response lines, for SSE/NDJSON
  streaming) out."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient
                          HttpClient$Redirect
                          HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

(def default-timeout-ms 120000)

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn- ^HttpRequest build-request
  [{:keys [url headers body timeout-ms]}]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis (or timeout-ms default-timeout-ms)))
                    (.header "content-type" "application/json")
                    (.header "accept" "application/json"))
        builder (reduce-kv (fn [b k v]
                             (if (some? v) (.header b (name k) (str v)) b))
                           builder
                           (or headers {}))]
    (-> builder
        (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
        (.build))))

(defn- parse-json [s]
  (when-not (str/blank? s)
    (try
      (json/read-str s :key-fn keyword)
      (catch Exception _ s))))

(defn- error! [url ^long status body]
  (throw (ex-info (str "LLM provider returned HTTP " status " for " url)
                  {:type ::error
                   :status status
                   :url url
                   :body body})))

(defn post-json
  "POST `body` as JSON to `url`. Returns {:status n :body <parsed JSON,
  keyword keys>}. Throws ex-info {:type ::error :status ... :body ...}
  on a non-2xx response."
  [{:keys [url] :as request}]
  (let [^HttpResponse response (.send ^HttpClient @client
                                      (build-request request)
                                      (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        body (parse-json (.body response))]
    (if (<= 200 status 299)
      {:status status :body body}
      (error! url status body))))

(defn post-json-lines
  "POST `body` as JSON to `url` and reduce (f acc line) over each line of
  the response as it arrives — the streaming transport for both SSE and
  NDJSON. Blank lines are skipped. Returns the final accumulator.
  Throws like `post-json` on a non-2xx response."
  [{:keys [url] :as request} f init]
  (let [^HttpResponse response (.send ^HttpClient @client
                                      (build-request request)
                                      (HttpResponse$BodyHandlers/ofLines))
        status (.statusCode response)
        lines (iterator-seq (.iterator ^java.util.stream.Stream (.body response)))]
    (if (<= 200 status 299)
      (reduce (fn [acc ^String line]
                (if (str/blank? line) acc (f acc line)))
              init
              lines)
      (error! url status (parse-json (str/join "\n" lines))))))

(defn sse-data
  "Given one line of a server-sent-events stream, return the data payload
  string, or nil for non-data lines (event names, comments, blanks)."
  [^String line]
  (when (str/starts-with? line "data:")
    (str/triml (subs line 5))))
