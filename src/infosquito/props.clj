(ns infosquito.props
  "This namespace holds all of the logic for managing the configuration values"
  (:require [clojure.tools.logging :as log])
  (:import [java.net URL]))


(def ^:private DEFAULT-AMQP-PORT        5672)
(def ^:private DEFAULT-INDEX-BATCH-SIZE 100)


(def ^:private prop-defaults
  {"infosquito.es.uri"                    "http://elasticsearch:9200"
   "infosquito.es.index"                  "data"
   "infosquito.es.scroll-size"            "1000"
   "infosquito.icat.host"                 "irods"
   "infosquito.icat.port"                 "5432"
   "infosquito.icat.user"                 "rods"
   "infosquito.icat.password"             "notprod"
   "infosquito.icat.db"                   "ICAT"
   "infosquito.base-collection"           "/iplant"
   "infosquito.index-batch-size"          1000
   "infosquito.amqp.uri"                  "amqp://guest:guestPW@localhost:5672"
   "infosquito.amqp.reindex-queue"        "infosquito.reindex"
   "infosquito.amqp.exchange.name"        "de"
   "infosquito.amqp.exchange.durable"     "True"
   "infosquito.amqp.exchange.auto-delete" "False"
   "infosquito.notify.enabled"            "True"
   "infosquito.notify.count"              10000
   "infosquito.retry-interval"            900})

(def ^:private prop-names (into [] (keys prop-defaults)))

(defn- get-str
  [props prop-name]
  (or (get props prop-name)
      (get prop-defaults prop-name)))

(defn- get-int
  [props prop-name description]
  (if-let [string-value (get props prop-name)]
    (try
      (Integer/parseInt string-value)
      (catch NumberFormatException e
        (log/fatal "invalid" description "-" string-value)
        (System/exit 1)))
    (get prop-defaults prop-name)))


(defn- get-long
  [props prop-name description]
  (if-let [string-value (get props prop-name)]
    (try
      (Long/parseLong string-value)
      (catch NumberFormatException e
        (log/fatal "invalid" description "-" string-value)
        (System/exit 1)))
    (get prop-defaults prop-name)))


(defn get-es-uri
  [props]
  (get-str props "infosquito.es.uri"))

(defn get-es-index
  [props]
  (get-str props "infosquito.es.index"))


(defn get-es-scroll-size
  [props]
  (get-str props "infosquito.es.scroll-size"))


(defn get-icat-host
  [props]
  (get-str props "infosquito.icat.host"))


(defn get-icat-port
  [props]
  (get-str props "infosquito.icat.port"))


(defn get-icat-user
  [props]
  (get-str props "infosquito.icat.user"))


(defn get-icat-pass
  [props]
  (get-str props "infosquito.icat.password"))


(defn get-icat-db
  [props]
  (get-str props "infosquito.icat.db"))


(defn get-base-collection
  [props]
  (get-str props "infosquito.base-collection"))


(defn get-index-batch-size
  [props]
  (Math/abs (get-int props "infosquito.index-batch-size" "indexing batch size")))


(defn get-amqp-uri
  [props]
  (get-str props "infosquito.amqp.uri"))


(defn get-amqp-exchange-name
  [props]
  (get-str props "infosquito.amqp.exchange.name"))

(defn amqp-exchange-durable?
  [props]
  (Boolean/parseBoolean (get-str props "infosquito.amqp.exchange.durable")))


(defn amqp-exchange-auto-delete?
  [props]
  (Boolean/parseBoolean (get-str props "infosquito.amqp.exchange.auto-delete")))


(defn get-amqp-reindex-queue
  [props]
  (get-str props "infosquito.amqp.reindex-queue"))


(defn notify-enabled?
  [props]
  (Boolean/parseBoolean (get-str props "infosquito.notify.enabled")))


(defn get-notify-count
  [props]
  (get-int props "infosquito.notify.count" "notify count"))


(defn get-retry-interval
  [props]
  (get-long props "infosquito.retry-interval" "retry interval"))


(defn get-retry-millis
  [props]
  (long (* 1000 (get-retry-interval props))))
