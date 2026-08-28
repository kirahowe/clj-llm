(ns build
  "Build tasks. Run with: clojure -T:build <task>"
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.kirahowe/clj-llm)
(def version "0.1.0-alpha1")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- required-env [env-var guidance]
  (let [value (System/getenv env-var)]
    (when (str/blank? value)
      (throw (ex-info guidance
                      {:type ::missing-deploy-credential
                       :environment-variable env-var})))
    value))

(defn- deployment-detail [exception deploy-token]
  (let [detail (ex-message exception)]
    (if (str/blank? detail)
      "No additional details were provided."
      (str/replace detail deploy-token "[REDACTED]"))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/kirahowe/clj-llm"
                      :connection "scm:git:git://github.com/kirahowe/clj-llm.git"
                      :developerConnection "scm:git:ssh://git@github.com/kirahowe/clj-llm.git"
                      :tag (str "v" version)}
                :pom-data [[:description "A small, functional, provider-agnostic Clojure library for calling LLMs, with evals built in."]
                           [:url "https://github.com/kirahowe/clj-llm"]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/license/mit/"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (let [username (required-env
                  "CLOJARS_USERNAME"
                  "Set CLOJARS_USERNAME to a nonblank Clojars username before deploying.")
        deploy-token (required-env
                      "CLOJARS_PASSWORD"
                      "Set CLOJARS_PASSWORD to a nonblank Clojars deploy token before deploying.")]
    (jar nil)
    (try
      (dd/deploy {:installer :remote
                  :artifact (b/resolve-path jar-file)
                  :pom-file (b/pom-path {:lib lib :class-dir class-dir})
                  :repository {"clojars" {:url "https://clojars.org/repo"
                                          :username username
                                          :password deploy-token}}})
      (catch Exception exception
        (let [detail (deployment-detail exception deploy-token)]
          (throw (ex-info (str "Clojars deployment failed: " detail)
                          {:type ::deployment-failed
                           :exception-class (.getName (class exception))
                           :detail detail})))))))
