(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'local/semi-dev-menu)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/semi-dev-menu.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]})
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :ns-compile '[semi-dev-menu]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'semi-dev-menu}))
  (println "Wrote" uber-file))
