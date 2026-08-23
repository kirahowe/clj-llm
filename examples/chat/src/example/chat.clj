(ns example.chat
  (:require [clj-llm.core :as llm]
            [clojure.string :as str]))

(def config (llm/read-config "llm.edn"))

(defn -main
  [& _]
  (println "Chat with the model. Enter :quit to stop.")
  (loop [messages []]
    (print "you> ")
    (flush)
    (when-let [prompt (read-line)]
      (when-not (= ":quit" (str/trim prompt))
        (print "assistant> ")
        (flush)
        (let [response
              (llm/generate config
                            {:llm/messages
                             (conj messages {:role :user :content prompt})}
                            {:llm/on-chunk
                             (fn [{:keys [type text]}]
                               (when (= :text type)
                                 (print text)
                                 (flush)))})]
          (println)
          (recur (:llm/messages response)))))))
