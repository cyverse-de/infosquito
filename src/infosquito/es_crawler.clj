(ns infosquito.es-crawler
  (:use [clojure-commons.progress :only [notifier]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.bulk :as bulk]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.response :as resp]
            [clojurewerkz.elastisch.query :as q]
            [infosquito.icat :as icat]
            [infosquito.index :as index]
            [infosquito.props :as cfg]
            [clojurewerkz.elastisch.arguments :as ar]
            [slingshot.slingshot :refer [try+]])
  (:import (clojurewerkz.elastisch.rest Connection)))

(defn scroll
  "Performs a scroll query, fetching the next page of results from a
   query given a scroll id"
  [^Connection conn scroll-id & args]
  (let [opts (ar/->opts args)
        qk   [:search_type :scroll :routing :preference]
        qp   (select-keys opts qk)
        body {:scroll_id scroll-id}]
    (esr/post conn (esr/scroll-url conn)
                      {:body body
                       :query-params qp})))

(defn- seed-item-seq
  [es item-type props]
  ; The scan search type does not return any results with its first call, unlike all the other
  ; search types. A second call is needed to kick off the sequence.
  (let [res (esd/search es (cfg/get-es-index props) (name item-type)
              :query       (q/match-all)
              :_source     ["_id"]
              :sort        ["id"]
              :scroll      "1m"
              :size        (cfg/get-es-scroll-size props))]
    (log/info "got" (resp/total-hits res) "results")
    (if (resp/any-hits? res)
      (scroll es (:_scroll_id res) :scroll "1m")
      res)))

(defn- item-seq
  [es item-type props]
  (esd/scroll-seq es (seed-item-seq es item-type props)))

(defn- log-deletion
  [item-type item]
  (log/trace "deleting index entry for" (name item-type) (:_id item)))

(defn- log-failure
  [item-type item]
  (log/trace "unable to remove the index entry for" (name item-type) (:_id item)))

(defn- log-failures
  [res]
  (->> (:items res)
       (map :delete)
       (remove #(and (>= 200 (:status %)) (< 300 (:status %))))
       (map (fn [{id :_id type :_type}] (log-failure type id)))
       (dorun)))

(defn- delete-items
  [es index item-type items]
  (dorun (map (partial log-deletion item-type) items))
  (try+
    (let [req (bulk/bulk-delete items)
          res (bulk/bulk-with-index-and-type es index (:name item-type) req :refresh true)]
      (log-failures res))
    (catch Object _
      (log/error (:throwable &throw-context) "Error deleting items from elasticsearch")
      (dorun (map (partial log-failure (name item-type)) (map :id items))))))

(defn- retention-logger
  [item-type keep-item?]
  (fn [id]
    (let [keep? (keep-item? id)]
      (log/trace (name item-type) id (if keep? "exists" "does not exist"))
      keep?)))

(defn- purge-deleted-items
  [es item-type keep? props]
  (let [should-preseed (atom true)
        ;; notify-prog* does the actual notification, but also resets the atom for preseeding
        ;; this way, we can rely on the notifier's logic for calling only periodically
        notify-prog*   (notifier (cfg/notify-enabled? props)
                                 #(do (log/info % (icat/summarize-counts)) (reset! should-preseed true))
                                 (cfg/get-notify-count props))
        ;; notify-prog wraps notify-prog* with the logic that does the actual preseeding
        ;; it adds a slush factor of 20% for safety (it's still just one SQL command)
        notify-prog    (fn [entries]
                         (let [e (notify-prog* entries)]
                           (when @should-preseed
                             (icat/preseed-cache (:_id (first entries)) (Math/floor (* 1.2 (cfg/get-notify-count props))) item-type)
                             (reset! should-preseed false))
                           e))]
    (log/info "purging non-existent" (name item-type) "entries")
    (->> (item-seq es item-type props)
      (mapcat (comp notify-prog vector))
      (remove (comp (retention-logger item-type keep?) :_id))
      (partition-all (cfg/get-index-batch-size props))
      (map (partial delete-items es (cfg/get-es-index props) item-type))
      dorun)
    (log/info (name item-type) "entry purging complete")))

(defn- purge-deleted-files
  [es props]
  (purge-deleted-items es :file icat/exists? props))

(defn- purge-deleted-folders
  [es props]
  (let [index-base (cfg/get-base-collection props)]
    (purge-deleted-items es
                         :folder
                         #(and (index/indexable? index-base %) (icat/exists? %))
                         props)))

(defn purge-index
  [props]
  (icat/reset-existence-cache)
  (let [http-opts (if (or (empty? (cfg/get-es-user props)) (empty? (cfg/get-es-password props)))
                    {}
                    {:basic-auth [(cfg/get-es-user props) (cfg/get-es-password props)]})
        es (esr/connect (cfg/get-es-uri props) http-opts)]
    (purge-deleted-files es props)
    (purge-deleted-folders es props))
  (icat/reset-existence-cache))

