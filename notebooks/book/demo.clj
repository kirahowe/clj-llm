(ns book.demo
  "A canned, deterministic provider adapter (:demo) plus a ready-made config, so the documentation book renders offline with no API keys — every example in the book actually executes against this. The code in the chapters is exactly what you would run against a real provider; only the config differs. It is also a compact end-to-end example of writing an adapter (see the adapters chapter for the annotated version)."
  (:require [clj-llm.provider :as provider]
            [clojure.string :as str]))

(defn- canned-answer [text]
  (condp (fn [re s] (re-find re s)) (str text)
    #"(?i)capital of france" "Paris"
    #"(?i)17 \* 23" "391"
    #"(?i)sky.*blue" "Because air molecules scatter short (blue) wavelengths of sunlight more strongly than long ones — Rayleigh scattering."
    #"(?i)prime number between" "127 is a prime between 100 and 200."
    #"(?i)why is it prime" "127 has no divisors other than 1 and itself; trial division up to its square root (≈11.3) finds none."
    #"(?i)story" "Once upon a time, a parenthesis opened. Everything since is still in scope."
    #"(?i)mitochondria" "The mitochondria converts nutrients into ATP, powering the cell."
    #"(?i)reset.*password" "Click \"Forgot password\" on the sign-in page and we'll email you a reset link."
    (str "A canned demo answer to: " text)))

(defn- last-user-text [messages]
  (:content (last (filter #(= :user (:role %)) messages))))

(defn- wants-tool? [request]
  (and (seq (:lib/tools request))
       (re-find #"(?i)weather" (str (last-user-text (:lib/messages request))))
       (not-any? #(= :tool (:role %)) (:lib/messages request))))

(defn- respond [request text]
  (let [on-chunk (:lib/on-chunk request)
        words (count (str/split (str (last-user-text (:lib/messages request))) #"\s+"))]
    (when on-chunk
      (doseq [piece (partition-all 12 text)]
        (on-chunk {:type :text :text (apply str piece)})))
    {:message {:role :assistant :content text}
     :model (:lib/model request)
     :usage {:input-tokens (+ 8 words) :output-tokens (count (str/split text #"\s+"))}
     :finish-reason :stop
     :raw {:demo true}}))

(defmethod provider/-generate! :demo
  [_provider-config request _opts]
  (if (wants-tool? request)
    {:message {:role :assistant
               :content ""
               :tool-calls [{:id "call_0"
                             :name "get-weather"
                             :arguments {:city "Berlin"}}]}
     :model (:lib/model request)
     :usage {:input-tokens 21 :output-tokens 9}
     :finish-reason :tool-calls
     :raw {:demo true}}
    (if-let [tool-result (:content (last (filter #(= :tool (:role %)) (:lib/messages request))))]
      (respond request (str "According to the tool, conditions are: " tool-result))
      (respond request (canned-answer (last-user-text (:lib/messages request)))))))

(defmethod provider/-embed! :demo
  [_provider-config request _opts]
  (let [embed (fn [s] (mapv #(/ (double (mod (hash [s %]) 1000)) 1000.0) (range 4)))]
    {:embeddings (mapv embed (:lib/input request))
     :model (:lib/model request)
     :usage {:input-tokens (reduce + (map #(count (str/split % #"\s+")) (:lib/input request)))}
     :raw {:demo true}}))

(def config
  "A config shaped exactly like a real one, pointing at the :demo adapter. Swap this for (llm/read-config \"llm.edn\") and every example in the book runs against your real providers."
  #:lib{:providers {:demo {:lib/adapter :demo}}
        :models {:smart #:lib{:provider :demo :model "demo-smart-1"}
                 :fast #:lib{:provider :demo :model "demo-fast-1"}
                 :embeddings #:lib{:provider :demo :model "demo-embed-1"}}
        :defaults #:lib{:model :smart
                        :embedding-model :embeddings}})
