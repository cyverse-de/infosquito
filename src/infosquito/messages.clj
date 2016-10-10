(ns infosquito.messages
  (:require [clojure.tools.logging :as log]
            [infosquito.actions :as actions]
            [infosquito.props :as cfg]
            [infosquito.amqp :as amqp]
            [langohr.consumers :as lc]
            [langohr.basic :as lb]))

(defn exchange-config
  [props]
  (amqp/exchange-config
   props
   cfg/get-amqp-exchange-name
   cfg/amqp-exchange-durable?
   cfg/amqp-exchange-auto-delete?))

(defn queue-config
  [props]
  (amqp/queue-config
   props
   cfg/get-amqp-reindex-queue
   (fn [_] true)
   (fn [_] false)
   (fn [_] ["index.all" "index.data"])))

(defn reindex-handler
  [props ch {:keys [delivery-tag]} _]
  (try
    (actions/reindex props)
    (lb/ack ch delivery-tag)
    (catch Throwable t
      (log/error t "data store reindexing failed")
      (log/warn "requeuing message after" (cfg/get-retry-interval props) "seconds")
      (Thread/sleep (cfg/get-retry-millis props))
      (lb/reject ch delivery-tag true))))
