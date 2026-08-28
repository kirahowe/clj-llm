(ns example.chat
  "A streaming terminal chat example."
  (:require [clj-llm.core :as llm]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- print-chunk
  "Print a streamed text chunk."
  [{:keys [type text]}]
  (when (= :text type)
    (print text)
    (flush)))

(defn -main
  "Start a terminal chat, loading its classpath config once."
  [& _]
  (let [resource-name "example/chat/llm.edn"
        resource (or (io/resource resource-name)
                     (throw
                      (ex-info
                       (str "Chat config resource not found on classpath: "
                            resource-name)
                       {:type :llm/config-not-found
                        :resource resource-name})))
        config (llm/read-config resource)]
    (println "Chat with the model. Enter :quit to stop.")
    (loop [messages []]
      (print "you> ")
      (flush)
      (when-let [prompt (read-line)]
        (when-not (= ":quit" (str/trim prompt))
          (print "assistant> ")
          (flush)
          (let [response (llm/generate
                          config
                          prompt
                          {:llm/messages messages
                           :llm/on-chunk print-chunk})]
            (println)
            (recur (:llm/messages response))))))))
