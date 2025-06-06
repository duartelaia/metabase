(ns metabase.search.appdb.core
  (:require
   [clojure.string :as str]
   [environ.core :as env]
   [honey.sql.helpers :as sql.helpers]
   [java-time.api :as t]
   [metabase.config :as config]
   [metabase.db :as mdb]
   [metabase.search.appdb.index :as search.index]
   [metabase.search.appdb.scoring :as search.scoring]
   [metabase.search.appdb.specialization.postgres :as specialization.postgres]
   [metabase.search.config :as search.config]
   [metabase.search.engine :as search.engine]
   [metabase.search.filter :as search.filter]
   [metabase.search.ingestion :as search.ingestion]
   [metabase.search.permissions :as search.permissions]
   [metabase.search.settings :as search.settings]
   [metabase.settings.core :as setting]
   [metabase.util :as u]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [toucan2.core :as t2])
  (:import
   (java.time OffsetDateTime)
   (java.util Queue)))

;; Register the multimethods for each specialization
(comment
  specialization.postgres/keep-me)

(set! *warn-on-reflection* true)

;; Make sure the legacy cookies still work.
(derive :search.engine/fulltext :search.engine/appdb)

(def supported-db?
  "All the databases which we have implemented fulltext search for."
  #{:postgres :h2})

(defmethod search.engine/supported-engine? :search.engine/appdb [_]
  (and (or (not config/is-prod?)
           (= "appdb" (some-> (search.settings/search-engine) name)))
       (supported-db? (mdb/db-type))))

(defn- parse-datetime [s]
  (when s (OffsetDateTime/parse s)))

(defn- collapse-id [{:keys [id] :as row}]
  (assoc row :id (if (number? id) id (parse-long (last (str/split (:id row) #":"))))))

(defn- rehydrate [weights active-scorers index-row]
  (-> (json/decode+kw (:legacy_input index-row))
      collapse-id
      (assoc
       ;; this relies on the corresponding scorer, which is not great coupling.
       ;; ideally we would make per-user computed attributes part of the spec itself.
       :bookmark   (pos? (:bookmarked index-row 0))
       :score      (:total_score index-row 1)
       :all-scores (mapv (fn [k]
                           ;; we shouldn't get null scores, but just in case (i.e., because there are bugs)
                           (let [score  (or (get index-row k) 0)
                                 weight (or (weights k) 0)]
                             {:score        score
                              :name         k
                              :weight       weight
                              :contribution (* weight score)}))
                         active-scorers))
      (update :created_at parse-datetime)
      (update :updated_at parse-datetime)
      (update :last_edited_at parse-datetime)))

(defn add-table-where-clauses
  "Add a `WHERE` clause to the query to only return tables the current user has access to"
  [search-ctx qry]
  (sql.helpers/where qry
                     [:or
                      [:= :search_index.model nil]
                      [:!= :search_index.model [:inline "table"]]
                      [:and
                       [:= :search_index.model [:inline "table"]]
                       (search.permissions/permitted-tables-clause search-ctx :search_index.model_id)]]))

(defn add-collection-join-and-where-clauses
  "Add a `WHERE` clause to the query to only return Collections the Current User has access to; join against Collection,
  so we can return its `:name`."
  [search-ctx qry]
  (let [collection-id-col :search_index.collection_id
        permitted-clause  (search.permissions/permitted-collections-clause search-ctx collection-id-col)
        personal-clause   (search.filter/personal-collections-where-clause search-ctx collection-id-col)
        excluded-models   (search.filter/models-without-collection)
        or-null           #(vector :or [:in :search_index.model excluded-models] %)]
    (cond-> qry
      true (sql.helpers/left-join [:collection :collection] [:= collection-id-col :collection.id])
      true (sql.helpers/where (or-null permitted-clause))
      personal-clause (sql.helpers/where (or-null personal-clause)))))

(defmethod search.engine/results :search.engine/appdb
  [{:keys [search-engine search-string] :as search-ctx}]
  ;; Check whether there is a query-able index.
  (when-not (search.index/active-table)
    (let [index-state  @@#'search.index/*indexes*
          ;; Sync, in case we're just out of sync with the database.
          found-active (:active (#'search.index/sync-tracking-atoms!))
          ;; If there's really no index, and we're running in prod - gulp, try to initialize now.
          init-now? (and (not found-active) config/is-prod?)]
      (when init-now?
        (log/warnf "Triggering a late initialization of the %s search index." search-engine)
        (try
          (future
            (search.engine/init! search-engine {:force-reset? false}))
          (catch Exception e
            (log/error e))))
      ;; Even if the index exists now, return an error so that we don't obscure that there was an issue.
      (throw (ex-info "Search Index not found."
                      {:search-engine      search-engine
                       :db-type            (mdb/db-type)
                       :version            @#'search.index/*index-version-id*
                       :forced-init?       init-now?
                       :index-state-before index-state
                       :index-state-after  @@#'search.index/*indexes*
                       :index-metadata     (t2/select :model/SearchIndexMetadata :engine :appdb)}))))

  (try
    (when (setting/string->boolean (:mb-experimental-search-block-on-queue env/env))
      ;; wait for a bit for the queue to be drained
      (let [pending-updates #(.size ^Queue @#'search.ingestion/queue)]
        (when-not (u/poll {:thunk       pending-updates
                           :done?       zero?
                           :timeout-ms  2000
                           :interval-ms 100})
          (log/warn "Returning search results even though they may be stale. Queue size:" (pending-updates)))))

    (let [weights (search.config/weights search-ctx)
          scorers (search.scoring/scorers search-ctx)]
      (->> (search.index/search-query search-string search-ctx [:legacy_input])
           (add-collection-join-and-where-clauses search-ctx)
           (add-table-where-clauses search-ctx)
           (search.scoring/with-scores search-ctx scorers)
           (search.filter/with-filters search-ctx)
           t2/query
           (map (partial rehydrate weights (keys scorers)))))
    (catch Exception e
      ;; Rule out the error coming from stale index metadata.
      (#'search.index/sync-tracking-atoms!)
      (throw e))))

(defmethod search.engine/model-set :search.engine/appdb
  [search-ctx]
  ;; We ignore any current models filter
  (let [unfiltered-context (assoc search-ctx :models search.config/all-models)
        applicable-models  (search.filter/search-context->applicable-models unfiltered-context)
        search-ctx         (assoc search-ctx :models applicable-models)]
    (->> (search.index/search-query (:search-string search-ctx) search-ctx [[[:distinct :model] :model]])
         (add-collection-join-and-where-clauses search-ctx)
         (search.filter/with-filters search-ctx)
         t2/query
         (into #{} (map :model)))))

(defn- populate-index! [context]
  (search.index/index-docs! context (search.ingestion/searchable-documents)))

(defmethod search.engine/init! :search.engine/appdb
  [_ {:keys [re-populate?] :as opts}]
  (let [index-created (search.index/when-index-created)]
    (if (and index-created (< 3 (t/time-between (t/instant index-created) (t/instant) :days)))
      (do
        (log/debug "Forcing early reindex because existing index is old")
        (search.engine/reindex! :search.engine/appdb {}))

      (let [created? (search.index/ensure-ready! opts)]
        (when (or created? re-populate?)
          (log/debug "Populating index")
          (populate-index! :search/updating))))))

(defmethod search.engine/reindex! :search.engine/appdb
  [_ {:keys [in-place?]}]
  (search.index/ensure-ready!)
  (if in-place?
    (when-let [table (search.index/active-table)]
      ;; keep the current table, just delete its contents
      (t2/delete! table))
    (search.index/maybe-create-pending!))
  (u/prog1 (populate-index! (if in-place? :search/updating :search/reindexing))
    (search.index/activate-table!)))
