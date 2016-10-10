(ns infosquito.events
  (:require [clojure.tools.logging :as log]
            [infosquito.amqp :as amqp]
            [infosquito.props :as cfg]
            [langohr.basic :as lb])
  (:import [org.cyverse.events.ping PingMessages$Ping PingMessages$Pong]
           [com.google.protobuf.util JsonFormat]))

(defn exchange-config
  [props]
  (amqp/exchange-config
   props
   cfg/events-exchange-name
   cfg/events-exchange-durable?
   cfg/events-exchange-auto-delete?))

(defn queue-config
  [props]
  (amqp/queue-config
   props
   cfg/events-queue-name
   cfg/events-queue-durable?
   cfg/events-queue-auto-delete?
   (fn [_] ["events.infosquito.#"])))

(defn- ping-handler
  [props channel {:keys [routing-key]} msg]
  (log/info (format "[events/ping-handler] [%s] [%s]" routing-key (String. msg)))
  (lb/publish channel (cfg/events-exchange-name props) "events.infosquito.pong"
    (.print (JsonFormat/printer)
      (.. (PingMessages$Pong/newBuilder)
        (setPongFrom "infosquito")
        (build)))))

(def handlers
  {"events.infosquito.ping" ping-handler})

(defn event-handler
  [props channel {:keys [delivery-tag routing-key] :as metadata} ^bytes payload]
  (lb/ack channel delivery-tag)
  (let [handler (get handlers routing-key)]
    (if-not (nil? handler)
      (handler props channel metadata payload))))
