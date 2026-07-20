(ns book
  "Render the documentation book: clojure -M:book (or bb book). Evaluates the .clj chapters with Clay and renders everything as a Quarto book into docs/. Requires the quarto CLI for the final HTML; chapter evaluation itself is pure JVM."
  (:require [babashka.fs :as fs]
            [scicloj.clay.v2.api :as clay]))

(def chapters
  ["getting_started.clj"
   "conversations_and_streaming.clj"
   "tools.clj"
   "evals.clj"
   "design.md"
   "adapters.md"
   "roadmap.md"])

(defn -main [& _]
  ;; Clay writes a title-only stub index.qmd when the target dir has none, but
  ;; leaves an existing one alone. Cleaning docs/ and seeding it with the real
  ;; intro makes notebooks/index.md the book's landing page.
  (fs/delete-tree "docs")
  (fs/create-dirs "docs")
  (fs/copy "notebooks/index.md" (fs/file "docs" "index.qmd"))
  (clay/make! {:format [:quarto :html]
               :book {:title "clj-llm: LLM calls you can measure"}
               :base-source-path "notebooks"
               :source-path chapters
               :base-target-path "docs"
               :show false})
  (System/exit 0))
