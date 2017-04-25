(ns infosquito.props-test
  (:use [clojure.java.io :only [reader]]
        [clojure.test]
        [infosquito.props])
  (:import [java.util Properties]))


(def ^:private props
  (doto (Properties.)
    (.load (reader "dev-resources/local.properties"))))


(def ^:private bad-props
  (doto (Properties.)
    (.load (reader "dev-resources/empty.properties"))))


(deftest test-get-es-host
  (is (= "elastic-host" (get-es-host props))))

(deftest test-get-es-port
  (is (= "31338" (get-es-port props))))

(deftest test-get-es-index
  (is (= "data" (get-es-index props))))

(deftest test-get-es-scroll-size
  (is (= "1m" (get-es-scroll-size props))))

(deftest test-get-icat-host
  (is (= "icat-host" (get-icat-host props))))

(deftest test-get-icat-port
  (is (= "5432" (get-icat-port props))))

(deftest test-get-icat-user
  (is (= "icat-user" (get-icat-user props))))

(deftest test-get-icat-pass
  (is (= "icat-pass" (get-icat-pass props))))

(deftest test-get-icat-db
  (is (= "icat-db" (get-icat-db props))))

(deftest test-get-base-collection
  (is (= "/iplant/home" (get-base-collection props))))

(deftest test-get-index-batch-size
  (is (= 100 (get-index-batch-size props))))

(deftest test-get-amqp-uri
  (is (= "amqp://user:password@amqp-host:9999" (get-amqp-uri props))))

(deftest test-get-amqp-reindex-queue
  (is (= "amqp-reindex-queue" (get-amqp-reindex-queue props))))

(deftest test-get-default-es-host
  (is (= "elasticsearch" (get-es-host bad-props))))

(deftest test-get-default-es-port
  (is (= "9200" (get-es-port bad-props))))

(deftest test-get-default-es-scroll-size
  (is (= "1000" (get-es-scroll-size bad-props))))

(deftest test-get-default-icat-host
  (is (= "irods" (get-icat-host bad-props))))

(deftest test-get-default-icat-port
  (is (= "5432" (get-icat-port bad-props))))

(deftest test-get-default-icat-user
  (is (= "rods" (get-icat-user bad-props))))

(deftest test-get-default-icat-pass
  (is (= "notprod" (get-icat-pass bad-props))))

(deftest test-get-default-icat-db
  (is (= "ICAT" (get-icat-db bad-props))))

(deftest test-get-default-base-collection
  (is (= "/iplant" (get-base-collection bad-props))))

(deftest test-get-default-index-batch-size
  (is (= 1000 (get-index-batch-size bad-props))))

(deftest test-get-default-amqp-uri
  (is (= "amqp://guest:guestPW@localhost:5672" (get-amqp-uri bad-props))))

(deftest test-get-default-amqp-reindex-queue
  (is (= "infosquito.reindex" (get-amqp-reindex-queue bad-props))))

(deftest test-get-default-exchange-name
  (is (= "de" (get-amqp-exchange-name bad-props))))

(deftest test-get-default-exchange-durability
  (is (true? (amqp-exchange-durable? bad-props))))

(deftest test-get-default-exchange-auto-deletion
  (is (false? (amqp-exchange-auto-delete? bad-props))))

(deftest test-default-notify-enabled
  (is (true? (notify-enabled? bad-props))))

(deftest test-default-notify-count
  (is (= 10000 (get-notify-count bad-props))))

(deftest test-get-default-retry-interval
  (is (= 900 (get-retry-interval bad-props))))
