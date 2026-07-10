(ns book
  "Render the documentation book: clojure -M:book (or bb book). Evaluates the .clj chapters with Clay and renders everything as a Quarto book into docs/. Requires the quarto CLI for the final HTML; chapter evaluation itself is pure JVM."
  (:require [scicloj.clay.v2.api :as clay]))

(def chapters
  ["index.md"
   "getting_started.clj"
   "conversations_and_streaming.clj"
   "tools.clj"
   "evals.clj"
   "design.md"
   "adapters.md"
   "roadmap.md"])

(defn -main [& _]
  (clay/make! {:format [:quarto :html]
               :book {:title "clj-llm — LLM calls you can measure"}
               :base-source-path "notebooks"
               :source-path chapters
               :base-target-path "docs"
               :clean-up-target-dir true
               :show false})
  (System/exit 0))
