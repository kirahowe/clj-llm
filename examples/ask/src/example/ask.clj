(ns example.ask
  "Sends one prompt to an explicitly configured language model."
  (:require [clj-llm.core :as llm]
            [clojure.string :as str]))

(def ^:private config
  #:llm{:providers {:ollama {:llm/adapter :ollama}}
        :defaults #:llm{:model "ollama/llama3.2"}})

(defn ask
  "Returns the generated text for `prompt` using `config`."
  [config prompt]
  (:llm/text (llm/generate config prompt)))

(defn -main
  "Reads a prompt from command-line arguments or standard input and prints its answer."
  [& args]
  (let [prompt (if (seq args)
                 (str/join " " args)
                 (slurp *in*))]
    (println (ask config prompt))))
