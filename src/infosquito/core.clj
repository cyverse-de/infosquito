(ns infosquito.core
  "This namespace defines the entry point for Infosquito. All state should be in here."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [slingshot.slingshot :as ss]
            [clojure-commons.config :as config]
            [infosquito.actions :as actions]
            [infosquito.amqp :as amqp]
            [infosquito.props :as cfg]
            [infosquito.exceptions :as exn]
            [infosquito.events :as events]
            [infosquito.icat :as icat]
            [infosquito.messages :as messages]
            [infosquito.props :as props]
            [common-cli.core :as ccli]
            [me.raynes.fs :as fs]
            [service-logging.thread-context :as tc])
  (:import [java.util Properties]))


(defn- load-config-from-file
  [config-path]
  (let [p (ref nil)]
    (config/load-config-from-file config-path p)
    @p))


(defn- exit
  [msg]
  (log/fatal msg)
  (System/exit 1))


(defmacro ^:private trap-exceptions!
  [& body]
  `(ss/try+
     (do ~@body)
     (catch Object o# (log/error (exn/fmt-throw-context ~'&throw-context)))))


(defn cli-options
  []
  [["-c" "--config PATH" "sets the local configuration file to be read."
    :default "/etc/iplant/de/infosquito.properties"]
   ["-r" "--reindex" "reindex the iPlant Data Store and exit"]
   ["-v" "--version" "print the version and exit"]
   ["-h" "--help" "show help and exit"]])


(def svc-info
  {:desc "An ICAT database crawler used to index the contents of iRODS."
   :app-name "infosquito"
   :group-id "org.cyverse"
   :art-id "infosquito"
   :service "infosquito"})

(defn- connect-for-reindexing
  "Starts a thread for handling reindexing messages. Non-blocking."
  [props]
  (.start (Thread. (fn [] (amqp/repeatedly-connect
                           props
                           (cfg/get-amqp-uri props)
                           (messages/exchange-config props)
                           (messages/queue-config props)
                           (partial messages/reindex-handler props))))))

(defn- connect-for-events
  "Sets up the AMQP broker connection for handling event messages. Does not
   start up a separate thread -- unlike (connect-for-reindexing) -- so this
   function blocks and should be called last in (-main)."
  [props]
  (amqp/repeatedly-connect props
                           (cfg/get-amqp-uri props)
                           (events/exchange-config props)
                           (events/queue-config props)
                           (partial events/event-handler props)))

(defn -main
  [& args]
  (tc/with-logging-context
   svc-info
   (let [{:keys [options arguments errors summary]} (ccli/handle-args svc-info args cli-options)]
     (when-not (fs/exists? (:config options))
       (ccli/exit 1 "The config file does not exist."))
     (when-not (fs/readable? (:config options))
       (ccli/exit 1 "The config file is not readable."))
     (let [props (load-config-from-file (:config options))]
       (if (:reindex options)
         (do
           (actions/reindex props)
           (connect-for-reindexing props))
         (connect-for-events props))))))
