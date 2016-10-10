(ns infosquito.amqp
  (:require [clojure.tools.logging :as log]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.consumers :as lc]
            [langohr.basic :as lb]
            [langohr.exchange :as le])
  (:import [java.io IOException]))

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

(defn- connect
  "Repeatedly attempts to connect to the AMQP broker, sleeping for increasing periods of
   time when a connection can't be established."
  [uri]
  (->> (iterate next-sleep-time initial-sleep-time)
       (map (partial connection-attempt uri))
       (remove nil?)
       (first)))

(defn- close
  [c]
  (try
    (rmq/close c)
    (catch Exception _)))

(defn exchange-config
  [props name-fn durable-fn auto-delete-fn]
  {:name         (name-fn props)
   :durable?     (durable-fn props)
   :auto-delete? (auto-delete-fn props)})

(defn queue-config
  [props name-fn durable-fn auto-delete-fn routing-keys-fn]
  {:name         (name-fn props)
   :durable?     (durable-fn props)
   :auto-delete? (auto-delete-fn props)
   :routing-keys (routing-keys-fn props)})

(defn- declare-topic-exchange
  [channel exchange-cfg-map]
  (le/topic channel       (:name exchange-cfg-map)
            {:durable     (:durable? exchange-cfg-map)
             :auto-delete (:auto-delete? exchange-cfg-map)}))

(defn- declare-queue
  [channel exchange-name queue-cfg-map]
  (lq/declare channel (:name queue-cfg-map)
              {:durable     (:durable? queue-cfg-map)
               :auto-delete (:auto-delete? queue-cfg-map)
               :exclusive   false})
  (doseq [key (:routing-keys queue-cfg-map)]
    (lq/bind channel (:name queue-cfg-map) exchange-name {:routing-key key})))

(defn- configure-channel
 [channel exchange-cfg queue-cfg]
   (declare-topic-exchange channel exchange-cfg)
   (declare-queue channel (:name exchange-cfg) queue-cfg))

(defn- subscribe
 [ch queue-name handler-fn]
 (try
   (lc/blocking-subscribe ch queue-name handler-fn)
   (catch Exception e (log/error e "error occurred during message processing"))
   (finally (close ch))))

(defn repeatedly-connect
  "Repeatedly attempts to connect to the AMQP broker subscribe to incomming messages.
  Paramters:
    props        - the return value of (clojure-commons.config/load-config-from-file)
    uri          - string containing the URI of the message broker to connect to
    exchange-cfg - the return value of (infosquito.amqp/exchange-config)
    queue-cfg    - the return value of (infosqito.amqp/queue-config)
    handler-fn   - a Langohr message delivery handler function"
  [props uri exchange-cfg queue-cfg handler-fn]
  (let [conn (connect uri)]
    (log/info "successfully connected to AMQP broker")
    (try
      (let [channel (lch/open conn)]
        (configure-channel channel exchange-cfg queue-cfg)
        (subscribe channel (:name queue-cfg) handler-fn))
      (catch Exception e (log/error e "reconnecting to AMQP in" initial-sleep-time "milliseconds"))
      (finally (close conn))))
  (Thread/sleep initial-sleep-time)
  (recur props uri exchange-cfg queue-cfg handler-fn))
