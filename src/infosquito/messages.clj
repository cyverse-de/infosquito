(ns infosquito.messages
  (:require [clojure.tools.logging :as log]
            [infosquito.actions :as actions]
            [infosquito.props :as cfg]
            [infosquito.amqp :as amqp]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.consumers :as lc]
            [langohr.basic :as lb]
            [langohr.exchange :as le])
  (:import [java.io IOException]))

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

(def ^:const initial-sleep-time 5000)
(def ^:const max-sleep-time 320000)

(defn- sleep
  [millis]
  (try
    (Thread/sleep millis)
    (catch InterruptedException _
      (log/warn "sleep interrupted"))))

(defn- connection-attempt
  [uri millis-to-next-attempt]
  (try
    (rmq/connect {:uri uri})
    (catch IOException e
      (log/error e "unable to establish AMQP connection - trying again in"
                 millis-to-next-attempt "milliseconds")
      (sleep millis-to-next-attempt))))

(defn- next-sleep-time
  [curr-sleep-time]
  (min max-sleep-time (* curr-sleep-time 2)))

(defn- amqp-connect
  "Repeatedly attempts to connect to the AMQP broker, sleeping for increasing periods of
   time when a connection can't be established."
  [uri]
  (->> (iterate next-sleep-time initial-sleep-time)
       (map (partial connection-attempt uri))
       (remove nil?)
       (first)))

(defn- declare-queue
  [ch exchange queue-name]
  (lq/declare ch queue-name
              {:durable     true
               :auto-delete false
               :exclusive   false})
  (doseq [key ["index.all" "index.data"]]
    (lq/bind ch queue-name exchange {:routing-key key})))

(defn- reindex-handler
  [props ch {:keys [delivery-tag]} _]
  (try
    (actions/reindex props)
    (lb/ack ch delivery-tag)
    (catch Throwable t
      (log/error t "data store reindexing failed")
      (log/warn "requeuing message after" (cfg/get-retry-interval props) "seconds")
      (Thread/sleep (cfg/get-retry-millis props))
      (lb/reject ch delivery-tag true))))

(defn- rmq-close
  [c]
  (try
    (rmq/close c)
    (catch Exception _)))

(defn- subscribe
  [conn props]
  (let [ch           (lch/open conn)
        exchange-cfg (exchange-config props)
        queue-cfg    (queue-config props)]
    (try
      (amqp/configure-channel ch exchange-cfg queue-cfg)
      (lc/blocking-subscribe ch (:name queue-cfg) (partial reindex-handler props))
      (catch Exception e (log/error e "error occurred during message processing"))
      (finally (rmq-close ch)))))

(defn repeatedly-connect
  "Repeatedly attempts to connect to the AMQP broker subscribe to incomming messages."
  [props]
  (let [conn         (amqp-connect (cfg/get-amqp-uri props))]
    (log/info "successfully connected to AMQP broker")
    (try
      (subscribe conn props)
      (catch Exception e (log/error e "reconnecting to AMQP in" initial-sleep-time "milliseconds"))
      (finally (rmq-close conn))))
  (Thread/sleep initial-sleep-time)
  (recur props))
