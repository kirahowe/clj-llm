(ns kirahowe.clj-llm.test-runner
  "Zero-dependency test runner: finds *-test namespaces under test/ and
  runs them with clojure.test. Invoked via `bb test` / clojure -M:test."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [clojure.tools.namespace.find :as find]))

(defn -main [& _]
  (let [test-namespaces (->> (find/find-namespaces-in-dir (io/file "test"))
                             (filter #(re-find #"-test$" (name %)))
                             sort)]
    (run! require test-namespaces)
    (let [summary (apply t/run-tests test-namespaces)]
      (System/exit (if (t/successful? summary) 0 1)))))
