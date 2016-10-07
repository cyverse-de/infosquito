(ns infosquito.amqp
  (:require [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.consumers :as lc]
            [langohr.basic :as lb]
            [langohr.exchange :as le]))

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

(defn declare-topic-exchange
  [channel exchange-cfg-map]
  (le/topic channel       (:name exchange-cfg-map)
            {:durable     (:durable? exchange-cfg-map)
             :auto-delete (:auto-delete? exchange-cfg-map)}))

(defn declare-queue
  [channel exchange-name queue-cfg-map]
  (lq/declare channel (:name queue-cfg-map)
              {:durable     (:durable? queue-cfg-map)
               :auto-delete (:auto-delete? queue-cfg-map)
               :exclusive   false})
  (doseq [key (:routing-keys queue-cfg-map)]
    (lq/bind channel (:name queue-cfg-map) exchange-name {:routing-key key})))

(defn configure-channel
 [channel exchange-cfg queue-cfg]
   (declare-topic-exchange channel exchange-cfg)
   (declare-queue channel (:name exchange-cfg) queue-cfg))
