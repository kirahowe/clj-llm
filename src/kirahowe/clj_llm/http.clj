(ns kirahowe.clj-llm.http
  "HTTP support on clj-http: JSON in, JSON (or a reduction over response
  lines, for SSE/NDJSON streaming) out."
  (:require [charred.api :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-timeout-ms 120000)

(defn- request-options [{:keys [headers body timeout-ms]}]
  {:headers headers
   :body (json/write-json-str body)
   :content-type :json
   :accept :json
   :socket-timeout (or timeout-ms default-timeout-ms)
   :connection-timeout (or timeout-ms default-timeout-ms)
   :throw-exceptions false})

(defn- parse-json [s]
  (when-not (str/blank? s)
    (try
      (json/read-json s :key-fn keyword)
      (catch Exception _ s))))

(defn- error! [url status body]
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
  (let [{:keys [status body]} (client/post url (request-options request))]
    (if (<= 200 status 299)
      {:status status :body (parse-json body)}
      (error! url status (parse-json body)))))

(defn post-json-lines
  "POST `body` as JSON to `url` and reduce (f acc line) over each line of
  the response as it arrives — the streaming transport for both SSE and
  NDJSON. Blank lines are skipped. Returns the final accumulator.
  Throws like `post-json` on a non-2xx response."
  [{:keys [url] :as request} f init]
  (let [{:keys [status body]} (client/post url (assoc (request-options request)
                                                      :as :stream))]
    (if (<= 200 status 299)
      (with-open [reader (io/reader body)]
        (reduce (fn [acc line]
                  (if (str/blank? line) acc (f acc line)))
                init
                (line-seq reader)))
      (error! url status (parse-json (slurp body))))))

(defn sse-data
  "Given one line of a server-sent-events stream, return the data payload
  string, or nil for non-data lines (event names, comments, blanks)."
  [^String line]
  (when (str/starts-with? line "data:")
    (str/triml (subs line 5))))
