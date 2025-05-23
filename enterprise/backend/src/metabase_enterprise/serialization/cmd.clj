(ns metabase-enterprise.serialization.cmd
  (:refer-clojure :exclude [load])
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [metabase-enterprise.serialization.dump :as dump]
   [metabase-enterprise.serialization.load :as load]
   [metabase-enterprise.serialization.serialize :as serialize]
   [metabase-enterprise.serialization.v2.entity-ids :as v2.entity-ids]
   [metabase-enterprise.serialization.v2.extract :as v2.extract]
   [metabase-enterprise.serialization.v2.ingest :as v2.ingest]
   [metabase-enterprise.serialization.v2.load :as v2.load]
   [metabase-enterprise.serialization.v2.storage :as v2.storage]
   [metabase.analytics.core :as analytics]
   [metabase.db :as mdb]
   [metabase.models.serialization :as serdes]
   [metabase.plugins.core :as plugins]
   [metabase.premium-features.core :as premium-features]
   [metabase.setup.core :as setup]
   [metabase.util :as u]
   [metabase.util.i18n :refer [deferred-trs trs]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.warehouse-schema.models.field :as field]
   [toucan2.core :as t2])
  (:import
   (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)

(def ^:private Mode
  (mu/with-api-error-message [:enum :skip :update]
                             (deferred-trs "invalid --mode value")))

(def ^:private OnError
  (mu/with-api-error-message [:enum :continue :abort]
                             (deferred-trs "invalid --on-error value")))

(def ^:private Context
  (mu/with-api-error-message
   [:map {:closed true}
    [:on-error {:optional true} OnError]
    [:mode     {:optional true} Mode]]
   (deferred-trs "invalid context seed value")))

(defn- check-premium-token! []
  (premium-features/assert-has-feature :serialization (trs "Serialization")))

(mu/defn v1-load!
  "Load serialized metabase instance as created by [[dump]] command from directory `path`."
  [path context :- Context]
  (plugins/load-plugins!)
  (mdb/setup-db! :create-sample-content? false)
  (check-premium-token!)
  (when-not (load/compatible? path)
    (log/warn "Dump was produced using a different version of Metabase. Things may break!"))
  (let [context (merge {:mode     :skip
                        :on-error :continue}
                       context)]
    (try
      (log/infof "BEGIN LOAD from %s with context %s" path context)
      (let [all-res    [(load/load! (str path "/users") context)
                        (load/load! (str path "/databases") context)
                        (load/load! (str path "/collections") context)
                        (load/load-settings! path context)]
            reload-fns (filter fn? all-res)]
        (when (seq reload-fns)
          (log/info "Finished first pass of load; now performing second pass")
          (doseq [reload-fn reload-fns]
            (reload-fn)))
        (log/infof "END LOAD from %s with context %s" path context))
      (catch Throwable e
        (log/errorf e "ERROR LOAD from %s: %s" path (.getMessage e))
        (throw e)))))

(mu/defn v2-load-internal!
  "SerDes v2 load entry point for internal users.

  `opts` are passed to [[v2.load/load-metabase]]."
  [path :- :string
   opts :- [:map
            [:backfill? {:optional true} [:maybe :boolean]]
            [:continue-on-error {:optional true} [:maybe :boolean]]]
   ;; Deliberately separate from the opts so it can't be set from the CLI.
   & {:keys [token-check?
             require-initialized-db?]
      :or   {token-check? true
             require-initialized-db? true}}]
  (plugins/load-plugins!)
  (mdb/setup-db! :create-sample-content? false)
  (when (and require-initialized-db? (not (setup/has-user-setup)))
    (throw (ex-info "You cannot `import` into an empty database. Please set up Metabase normally, then retry." {})))
  (when token-check?
    (check-premium-token!))
  ; TODO This should be restored, but there's no manifest or other meta file written by v2 dumps.
  ;(when-not (load/compatible? path)
  ;  (log/warn "Dump was produced using a different version of Metabase. Things may break!"))
  (log/infof "Loading serialized Metabase files from %s" path)
  (serdes/with-cache
    (v2.load/load-metabase! (v2.ingest/ingest-yaml path) opts)))

(mu/defn v2-load!
  "SerDes v2 load entry point.

   opts are passed to load-metabase"
  [path :- :string
   opts :- [:map
            [:backfill? {:optional true} [:maybe :boolean]]
            [:continue-on-error {:optional true} [:maybe :boolean]]
            [:full-stacktrace {:optional true} [:maybe :boolean]]]]
  (let [timer    (u/start-timer)
        err      (atom nil)
        report   (try
                   (v2-load-internal! path opts :token-check? true)
                   (catch ExceptionInfo e
                     (reset! err e))
                   (catch Exception e
                     (reset! err e)))
        imported (into (sorted-set) (map (comp :model last)) (:seen report))]
    (analytics/track-event! :snowplow/serialization
                            {:event         :serialization
                             :direction     "import"
                             :source        "cli"
                             :duration_ms   (int (u/since-ms timer))
                             :models        (str/join "," imported)
                             :count         (if (contains? imported "Setting")
                                              (inc (count (remove #(= "Setting" (:model (first %))) (:seen report))))
                                              (count (:seen report)))
                             :error_count   (count (:errors report))
                             :success       (nil? @err)
                             :error_message (when @err
                                              (u/strip-error @err nil))})
    (when @err
      (if (:full-stacktrace opts)
        (log/error @err "Error during deserialization")
        (log/error (u/strip-error @err "Error during deserialization")))
      (throw (ex-info (ex-message @err) {:cmd/exit true})))
    imported))

(defn- select-entities-in-collections
  ([model collections]
   (select-entities-in-collections model collections :all))
  ([model collections state]
   (let [state-filter (case state
                        :all nil
                        :active [:= :archived false])]
     (t2/select model {:where [:and
                               [:or [:= :collection_id nil]
                                (if (not-empty collections)
                                  [:in :collection_id (map u/the-id collections)]
                                  false)]
                               state-filter]}))))

(defn- select-segments-in-tables
  ([tables]
   (select-segments-in-tables tables :all))
  ([tables state]
   (case state
     :all
     (mapcat #(t2/select :model/Segment :table_id (u/the-id %)) tables)
     :active
     (filter
      #(not (:archived %))
      (mapcat #(t2/select :model/Segment :table_id (u/the-id %)) tables)))))

(defn- select-collections
  "Selects the collections for a given user-id, or all collections without a personal ID if the passed user-id is nil.
  If `state` is passed (by default, `:active`), then that will be used to filter for collections that are archived (if
  the value is passed as `:all`)."
  ([users]
   (select-collections users :active))
  ([users state]
   (let [state-filter     (case state
                            :all nil
                            :active [:= :archived false])
         base-collections (t2/select :model/Collection {:where [:and [:= :location "/"]
                                                                [:or [:= :personal_owner_id nil]
                                                                 [:= :personal_owner_id
                                                                  (some-> users first u/the-id)]]
                                                                state-filter]})]
     (if (empty? base-collections)
       []
       (-> (t2/select :model/Collection
                      {:where [:and
                               (reduce (fn [acc coll]
                                         (conj acc [:like :location (format "/%d/%%" (:id coll))]))
                                       [:or] base-collections)
                               state-filter]})
           (into base-collections))))))

(defn v1-dump!
  "Legacy Metabase app data dump"
  [path {:keys [state user include-entity-id] :or {state :active} :as opts}]
  (log/infof "BEGIN DUMP to %s via user %s" path user)
  (mdb/setup-db! :create-sample-content? false)
  (check-premium-token!)
  (t2/select :model/User) ;; TODO -- why??? [editor's note: this comment originally from Cam]
  (let [users       (if user
                      (let [user (t2/select-one :model/User
                                                :email        user
                                                :is_superuser true)]
                        (assert user (trs "{0} is not a valid user" user))
                        [user])
                      [])
        databases   (if (contains? opts :only-db-ids)
                      (t2/select :model/Database :id [:in (:only-db-ids opts)] {:order-by [[:id :asc]]})
                      (t2/select :model/Database))
        tables      (if (contains? opts :only-db-ids)
                      (t2/select :model/Table :db_id [:in (:only-db-ids opts)] {:order-by [[:id :asc]]})
                      (t2/select :model/Table))
        fields      (if (contains? opts :only-db-ids)
                      (t2/select :model/Field :table_id [:in (map :id tables)] {:order-by [[:id :asc]]})
                      (t2/select :model/Field))
        collections (select-collections users state)]
    (binding [serialize/*include-entity-id* (boolean include-entity-id)]
      (dump/dump! path
                  databases
                  tables
                  (mapcat field/with-values (u/batches-of 32000 fields))
                  (select-segments-in-tables tables state)
                  collections
                  (select-entities-in-collections :model/NativeQuerySnippet collections state)
                  (select-entities-in-collections :model/Card collections state)
                  (select-entities-in-collections :model/Dashboard collections state)
                  (select-entities-in-collections :model/Pulse collections state)
                  users)))
  (dump/dump-settings! path)
  (dump/dump-dimensions! path)
  (log/infof "END DUMP to %s via user %s" path user))

(defn v2-dump!
  "Exports Metabase app data to directory at path"
  [path {:keys [collection-ids] :as opts}]
  (log/infof "Exporting Metabase to %s" path)
  (mdb/setup-db! :create-sample-content? false)
  (check-premium-token!)
  (t2/select :model/User) ;; TODO -- why??? [editor's note: this comment originally from Cam]
  (let [f (io/file path)]
    (.mkdirs f)
    (when-not (.canWrite f)
      (throw (ex-info (format "Destination path is not writeable: %s" path) {:filename path}))))
  (let [start  (System/nanoTime)
        err    (atom nil)
        opts   (cond-> opts
                 (seq collection-ids)
                 (assoc :targets (v2.extract/make-targets-of-type "Collection" collection-ids)))
        report (try
                 (serdes/with-cache
                   (-> (v2.extract/extract opts)
                       (v2.storage/store! path)))
                 (catch Exception e
                   (reset! err e)))]
    (analytics/track-event! :snowplow/serialization
                            {:event           :serialization
                             :direction       "export"
                             :source          "cli"
                             :duration_ms     (int (/ (- (System/nanoTime) start) 1e6))
                             :count           (count (:seen report))
                             :error_count     (count (:errors report))
                             :collection      (str/join "," collection-ids)
                             :all_collections (and (empty? collection-ids)
                                                   (not (:no-collections opts)))
                             :data_model      (not (:no-data-model opts))
                             :settings        (not (:no-settings opts))
                             :field_values    (boolean (:include-field-values opts))
                             :secrets         (boolean (:include-database-secrets opts))
                             :success         (nil? @err)
                             :error_message   (when @err
                                                (u/strip-error @err nil))})
    (when @err
      (if (:full-stacktrace opts)
        (log/error @err "Error during serialization")
        (log/error (u/strip-error @err "Error during deserialization")))
      (throw (ex-info (ex-message @err) {:cmd/exit true})))
    (log/info (format "Export to '%s' complete!" path) (u/emoji "🚛💨 📦"))
    report))

(defn seed-entity-ids!
  "Add entity IDs for instances of serializable models that don't already have them.

  Returns truthy if all entity IDs were added successfully, or falsey if any errors were encountered."
  []
  (v2.entity-ids/seed-entity-ids!))

(defn drop-entity-ids!
  "Drop entity IDs for all instances of serializable models.

  This is needed for some cases of migrating from v1 to v2 serdes. v1 doesn't dump `entity_id`, so they may have been
  randomly generated independently in both instances. Then when v2 serdes is used to export and import, the randomly
  generated IDs don't match and the entities get duplicated. Dropping `entity_id` from both instances first will force
  them to be regenerated based on the hashes, so they should match up if the receiving instance is a copy of the sender.

  Returns truthy if all entity IDs have been dropped, or falsey if any errors were encountered."
  []
  (v2.entity-ids/drop-entity-ids!))
