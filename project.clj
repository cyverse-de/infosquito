(use '[clojure.java.shell :only (sh)])
(require '[clojure.string :as string])

(defn git-ref
  []
  (or (System/getenv "GIT_COMMIT")
      (string/trim (:out (sh "git" "rev-parse" "HEAD")))
      ""))

(defproject org.cyverse/infosquito "2.8.1-SNAPSHOT"
  :description "An ICAT database crawler used to index the contents of iRODS."
  :url "https://github.com/cyverse-de/infosquito"
  :license {:name "BSD"
            :url "http://iplantcollaborative.org/sites/default/files/iPLANT-LICENSE.txt"}
  :manifest {"Git-Ref" ~(git-ref)}
  :uberjar-name "infosquito-standalone.jar"
  :aot [infosquito.core]
  :main infosquito.core
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [postgresql "9.1-901-1.jdbc4"]
                 [org.clojure/java.jdbc "0.3.5"]
                 [cheshire "5.5.0"
                  :exclusions [[com.fasterxml.jackson.dataformat/jackson-dataformat-cbor]
                               [com.fasterxml.jackson.dataformat/jackson-dataformat-smile]
                               [com.fasterxml.jackson.core/jackson-annotations]
                               [com.fasterxml.jackson.core/jackson-databind]
                               [com.fasterxml.jackson.core/jackson-core]]]
                 [clojurewerkz/elastisch "2.2.1"]
                 [com.novemberain/langohr "3.5.1"]
                 [slingshot "0.10.3"]
                 [me.raynes/fs "1.4.6"]
                 [org.cyverse/clojure-commons "2.8.0"]
                 [org.cyverse/common-cli "2.8.0"]
                 [org.cyverse/service-logging "2.8.0"]
                 [org.cyverse/event-messages "0.0.1"]]
  :eastwood {:exclude-namespaces [:test-paths]
             :linters [:wrong-arity :wrong-ns-form :wrong-pre-post :wrong-tag :misplaced-docstrings]}
  :plugins [[jonase/eastwood "0.2.3"]
            [test2junit "1.1.3"]]
  :profiles {:dev {:resource-paths ["dev-resources"]}})
