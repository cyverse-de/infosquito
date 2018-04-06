(ns infosquito.icat
  (:use [clojure.pprint :only [pprint]]
        [clojure-commons.progress :only [notifier]])
  (:require [clojure.java.jdbc.deprecated :as sql]  ; TODO move away from deprecated namespace
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.core.cache :as cache]
            [clojure-commons.file-utils :as file]
            [infosquito.es :as es]
            [infosquito.es-if :as es-if]
            [infosquito.index :as index]
            [slingshot.slingshot :refer [try+]]))

(def ^:private file-type "file")
(def ^:private dir-type "folder")

(def hitcount (atom 0))
(def misscount (atom 0))
(def misscount-t (atom 0))
(def misscount-f (atom 0))

(defn- new-existence-cache [] (cache/fifo-cache-factory {} :threshold 100000))
(def ^:private existence-cache (atom (new-existence-cache)))
(defn reset-existence-cache []
  (reset! hitcount 0)
  (reset! misscount 0)
  (reset! misscount-t 0)
  (reset! misscount-f 0)
  (reset! existence-cache (new-existence-cache)))

(defn fmt-query-list
  [vals]
  (if (empty? vals)
    ""
    (loop [[f & r] vals
           v-str   ""]
      (if (empty? r)
        (str v-str f)
        (recur r (str v-str f \,))))))


(defn- mk-acl-query
  [entry-ids]
  (str "SELECT a.object_id  \"db-id\",
               u.user_name  \"username\",
               u.zone_name  \"zone\",
               t.token_name \"permission\"
          FROM r_objt_access AS a
            JOIN r_user_main AS u ON a.user_id = u.user_id
            LEFT JOIN r_tokn_main AS t ON a.access_type_id = t.token_id
          WHERE a.object_id in (" (fmt-query-list entry-ids) ")"))


(defn- mk-colls-query
  [coll-base]
  (str "SELECT coll_id                                         \"db-id\",
               coll_name                                       \"path\",
               REPLACE(coll_name, parent_coll_name || '/', '') \"label\",
               coll_owner_name                                 \"creator-name\",
               coll_owner_zone                                 \"creator-zone\",
               CAST(create_ts AS bigint)                       \"date-created\",
               CAST(modify_ts AS bigint)                       \"date-modified\"
          FROM r_coll_main
          WHERE coll_name LIKE '" coll-base "/%' AND coll_type = ''"))


(defn- mk-data-objs-query
  [coll-base]
  (str "SELECT d1.data_id                           \"db-id\",
               (c.coll_name || '/' || d1.data_name) \"path\",
               d1.data_name                         \"label\",
               d1.data_owner_name                   \"creator-name\",
               d1.data_owner_zone                   \"creator-zone\",
               CAST(d1.create_ts AS bigint)         \"date-created\",
               CAST(d1.modify_ts AS bigint)         \"date-modified\",
               d1.data_size                         \"file-size\",
               d1.data_type_name                    \"info-type\"
          FROM r_data_main AS d1 LEFT JOIN r_coll_main AS c ON d1.coll_id = c.coll_id
          WHERE c.coll_name LIKE '" coll-base "/%'
            AND d1.data_repl_num = (SELECT MIN(d2.data_repl_num)
                                      FROM r_data_main AS d2
                                      WHERE d2.data_id = d1.data_id)"))


(defn- mk-meta-query
  [entry-ids]
  (str "SELECT map.object_id        \"db-id\",
               main.meta_attr_name  \"attribute\",
               main.meta_attr_value \"value\",
               main.meta_attr_unit  \"unit\"
          FROM r_objt_metamap AS map LEFT JOIN r_meta_main AS main ON main.meta_id = map.meta_id
          WHERE map.object_id in (" (fmt-query-list entry-ids) ")"))


(defn- query-paged
  [cfg sql-query result-receiver]
  (sql/transaction (sql/with-query-results*
                     [{:fetch-size (:result-page-size cfg)} sql-query]
                     result-receiver)))


(defn- get-acls
  [cfg entry-ids acl-receiver]
  (query-paged cfg (mk-acl-query entry-ids) acl-receiver))


(defn- get-colls-wo-acls
  "This higher order function retrieves a list of collections from the ICAT and passes them one at a
   time to the receiving function following CPS.

   Parameters:
     cfg           - the configuration mapping.
     coll-receiver - This is a function that receives individual collections. It must receive a
                     collection as its only parameter."
  [cfg coll-receiver]
  (query-paged cfg (mk-colls-query (:collection-base cfg)) coll-receiver))


(defn- get-data-objs-wo-acls
  "This higher order function retrieves a list of data objects from the ICAT and passes them one at
   a time to the receiving function following CPS.

   Parameters:
     cfg               - the configuration mapping.
     data-obj-receiver - This is a function that receives individual data objects. It must receive a
                         data object as its only parameter."
  [cfg data-obj-receiver]
  (query-paged cfg (mk-data-objs-query (:collection-base cfg)) data-obj-receiver))


(defn- get-metadata
  [cfg entry-ids meta-receiver]
  (query-paged cfg (mk-meta-query entry-ids) meta-receiver))


(defn- harvest-props
  [props prop-key entity]
  (->> props
    (filter #(= (:db-id %) (:db-id entity)))
    (map #(dissoc % :db-id))
    (assoc entity prop-key)))


(defn- attach-acls
  [cfg entries]
  (let [acls (get-acls cfg (map :db-id entries) (partial mapv identity))]
    (map (partial harvest-props acls :user-permissions) entries)))


(defn- attach-metadata
  [cfg entries]
  (let [avus (get-metadata cfg (map :db-id entries) (partial mapv identity))]
    (map (partial harvest-props avus :metadata) entries)))


(defn- filter-indexable-collections
  [cfg collections]
  (filter (comp (partial index/indexable? (:collection-base cfg)) :path) collections))


(defn init
  "This function creates the map containing the configuration parameters.

   Parameters:
     icat-host        - the name of the server hosting the ICAT database
     icat-port        - the ICAT port number (defaults to '5432')
     icat-db          - the name of the ICAT database (defaults to 'ICAT')
     icat-user        - the ICAT database user name
     icat-password    - the ICAT database user password
     collection-base  - the root collection contain all the entries of interest
     es-url           - the base URL to use when connecting to ElasticSearch
     es-index         - the ElasticSearch index
     notify?          - true if progress notifications are enabled
     notify-count     - the number of items to process before logging a notification
     index-batch-size - the number of items to be processed at once during an indexing pass"
  [{:keys [icat-host icat-port icat-db icat-user icat-password collection-base
           es-url es-user es-password es-index
           notify? notify-count index-batch-size]
    :or   {icat-port        "5432"
           icat-db          "ICAT"
           notify?          false
           notify-count     0
           index-batch-size 100}}]
  {:collection-base  collection-base
   :icat-host        icat-host
   :icat-port        icat-port
   :icat-db          icat-db
   :icat-user        icat-user
   :icat-password    icat-password
   :result-page-size (* 8 index-batch-size)
   :index-batch-size index-batch-size
   :es-url           es-url
   :es-user          es-user
   :es-password      es-password
   :es-index         es-index
   :notify?          notify?
   :notify-count     notify-count})


(defn get-db-spec
  "Generates a database connection specification from a configuration map."
  [{:keys [icat-host icat-port icat-db icat-user icat-password]}]
  {:subprotocol "postgresql"
   :subname     (str "//" icat-host ":" icat-port "/" icat-db)
   :user        icat-user
   :password    icat-password})


(defmacro with-icat
  "This opens the database connection and excecutes the provided operation within a transaction.
   Operating within a transaction ensures that the autocommit is off, allowing a cursor to be used
   on the database.  This in turn allows for large result sets that don't fit in memory.

   Parameters:
     cfg - The configuration mapping
     ops - The operations that will be executed on the open connection."
  [cfg & ops]
  `(sql/with-connection (get-db-spec ~cfg) ~@ops))


(defn- prepare-entries-for
  [cfg entries group-receiver]
  (letfn [(process-groups [groups] (->> groups
                                     (attach-acls cfg)
                                     (attach-metadata cfg)
                                     group-receiver))]
    (dorun
      (pmap process-groups (partition-all (:index-batch-size cfg) entries)))))


(defn get-collections
  "Given an open connection, this function executes appropriate queries on the connection requesting
   all collections under the collection-base with each collection having an attached ACL. It passes
   a lazy stream of collections to the given continuation following CPS.

   Parameters:
     cfg - the configuration mapping
     coll-receiver - The continuation that will receive the stream of collections

   Returns:
     It returns whatever the continuation returns."
  [cfg coll-receiver]
  (get-colls-wo-acls cfg (comp #(prepare-entries-for cfg % coll-receiver)
                               (partial filter-indexable-collections cfg))))


(defn get-data-objects
  "Given an open connection, this function executes appropriate queries on the connection requesting
   all data objects under the collection-base with each data object having an attached ACL. It
   passes a lazy stream of data objects to the given continuation following CPS.

   Parameters:
     cfg - the configuration mapping
     obj-receiver - The continuation that will receive the stream of data objects"
  [cfg obj-receiver]
  (get-data-objs-wo-acls cfg #(prepare-entries-for cfg % obj-receiver)))


(defn- fmt-user
  [name zone]
  (str name "#" zone))


(defn- fmt-access
  [access]
  (letfn [(fmt-perm [perm] (condp = perm
                             "read object"   "read"
                             "modify object" "write"
                             "own"           "own"
                                             nil))]
    {:permission (fmt-perm (:permission access))
     :user       (fmt-user (:username access) (:zone access))}))


(defn- split-uuid-from
  [metadata]
  (let [grp      (group-by #(= "ipc_UUID" (:attribute %)) metadata)
        id       (-> (get grp true) first :value)
        metadata (get grp false)]
    [id (if metadata metadata [])]))


(defn- mk-index-doc
  [entry-type entry]
  (let [s->ms         (partial * 1000)
        [id metadata] (split-uuid-from (:metadata entry))
        doc           {:id              id
                       :path            (:path entry)
                       :userPermissions (map fmt-access (:user-permissions entry))
                       :creator         (fmt-user (:creator-name entry) (:creator-zone entry))
                       :dateCreated     (s->ms (:date-created entry))
                       :dateModified    (s->ms (:date-modified entry))
                       :label           (:label entry)
                       :metadata        metadata}]
    (if (= entry-type dir-type)
      doc
      (assoc doc
        :fileSize (:file-size entry)
        :fileType (:info-type entry)))))


(defn- has-id?
  [doc]
  (if (:id doc)
    true
    (log/warn "missing UUID for filesystem entry" doc)))


(defn index-entries
  [indexer index entry-type entries]
  (log/debug "indexing" entry-type (map :path entries))
  (letfn [(log-failures [bulk-result]
            (doseq [{result :index} (:items bulk-result)]
              (when (or (< (:status result) 200)
                        (>= (:status result) 300))
                (log/error "failed to index" (:_id result) "-" result))))]
    (try
      (->> (map (partial mk-index-doc entry-type) entries)
           (filter has-id?)
           (map #(assoc % :_id (:id %)))
           (log/spy :trace)
           (es-if/put-bulk indexer index entry-type)
           log-failures)
      (catch Exception e
        (log/error e "failed to index" entry-type (map :path entries))))))


(def ^:private count-collections-query
  "SELECT count(*) \"count\"
     FROM r_coll_main
    WHERE coll_name LIKE ? AND coll_type = ''")


(defn- count-collections
  [cfg]
  (sql/with-query-results rs [count-collections-query (str (:collection-base cfg) "/%")]
    (:count (first rs))))


(def ^:private count-data-objects-query
  "SELECT count(DISTINCT d.data_id) \"count\"
     FROM r_data_main d
     JOIN r_coll_main c ON d.coll_id = c.coll_id
    WHERE c.coll_name like ?")


(defn- count-data-objects
  [cfg]
  (sql/with-query-results rs [count-data-objects-query (str (:collection-base cfg) "/%")]
    (:count (first rs))))


(defn- index-collections
  [cfg indexer]
  (log/info "indexing" (count-collections cfg) "collections")
  (->> (partial index-entries indexer (:es-index cfg) dir-type)
       (notifier (:notify? cfg) #(log/info %) (:notify-count cfg))
       (get-collections cfg))
  (log/info "collection indexing complete"))


(defn- index-data-objects
  [cfg indexer]
  (log/info "indexing" (count-data-objects cfg) "data objects")
  (->> (partial index-entries indexer (:es-index cfg) file-type)
       (notifier (:notify? cfg) #(log/info %) (:notify-count cfg))
       (get-data-objects cfg))
  (log/info "data object indexing complete"))

(defn reindex
  [cfg]
  (let [indexer (es/mk-indexer (:es-url cfg) (:es-user cfg) (:es-password cfg))]
    (try+
      (index-collections cfg indexer)
      (catch Object _
        (log/error (:throwable &throw-context) "Error while indexing collections, continuing")))
    (try+
      (index-data-objects cfg indexer)
      (catch Object _
        (log/error (:throwable &throw-context) "Error while indexing data objects")))))

(def ^:private existence-query
  (str "SELECT count(*)"
       "  FROM r_objt_metamap"
       "  WHERE meta_id IN (SELECT meta_id"
       "                      FROM r_meta_main"
       "                      WHERE meta_attr_name = 'ipc_UUID' AND meta_attr_value = ?)"))


(defn- exists?*
  [uuid]
  (sql/with-query-results rs [existence-query uuid]
    ((comp pos? :count first) rs)))

(defn exists?
  [uuid]
  (cache/lookup
    (if (cache/has? @existence-cache uuid)
      (do (swap! hitcount inc)
        (swap! existence-cache #(cache/hit % uuid)))
      (do (swap! misscount inc)
        (let [exists (exists?* uuid)]
          (if exists (swap! misscount-t inc) (swap! misscount-f inc))
          (swap! existence-cache #(cache/miss % uuid exists)))))
    uuid))

(defn summarize-counts [] (str "hits:" @hitcount " misses:" @misscount " (t:" @misscount-t " f:" @misscount-f ")"))

(def ^:private preseed-query-colls
  "SELECT meta_attr_value AS uuid from r_meta_main join r_objt_metamap USING (meta_id) JOIN r_coll_main ON (r_coll_main.coll_id = r_objt_metamap.object_id) WHERE meta_attr_name = 'ipc_UUID' AND meta_attr_value >= ? ORDER BY meta_attr_value LIMIT ?")

(def ^:private preseed-query-data
  "SELECT meta_attr_value AS uuid from r_meta_main join r_objt_metamap USING (meta_id) JOIN r_data_main ON (r_data_main.data_id = r_objt_metamap.object_id) WHERE meta_attr_name = 'ipc_UUID' AND meta_attr_value >= ? GROUP BY meta_attr_value ORDER BY meta_attr_value LIMIT ?")

(defn preseed-cache
  "preseed-cache loads up `limit` number of UUIDs for objects of type `objtype` (:file or :folder) starting at `start` into the cache for `exists?`. nonexistent items will still have to individually revalidate with this implementation, but they should be more uncommon"
  [start limit objtype]
  (let [preseed-query (get {:file preseed-query-data :folder preseed-query-colls} objtype)]
    (sql/with-query-results rs [preseed-query start limit]
      (doseq [row rs]
        (swap! existence-cache #(cache/miss % (str (:uuid row)) true))))))
