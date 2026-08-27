(ns clj-llm.eval-code-fixture)

(System/setProperty "clj-llm.eval-code-fixture-loaded" "true")

(defn task [{:keys [case]}]
  #:llm{:text (:llm/expected case)})

(defn scorer [{:keys [case response]}]
  {:score (if (= (:llm/expected case) (:llm/text response)) 1.0 0.0)})
