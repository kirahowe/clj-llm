(ns example.ask
  (:require [clj-llm.core :as llm]
            [clojure.string :as str]))

(def config (llm/read-config "llm.edn"))

(defn ask
  "Return the answer to one prompt. Useful from a REPL or another function."
  [prompt]
  (:llm/text (llm/generate config prompt)))

(defn -main
  [& args]
  (let [prompt (if (seq args)
                 (str/join " " args)
                 (slurp *in*))]
    (println (ask prompt))))
