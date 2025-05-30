(ns metabase.search.appdb.specialization.postgres
  (:require
   [clojure.string :as str]
   [metabase.search.appdb.specialization.api :as specialization]
   [metabase.util :as u]
   [metabase.util.string :as u.str]
   [toucan2.core :as t2]))

(def ^:private tsv-language "english")

(defmethod specialization/table-schema :postgres [base-schema]
  (into [[:id :bigint [:primary-key] [:raw "GENERATED BY DEFAULT AS IDENTITY"]]
         [:search_vector :tsvector :not-null]
         [:with_native_query_vector :tsvector :not-null]]
        base-schema))

;; TODO I strongly suspect that there are more indexes that would help performance, we should examine EXPLAIN.
;; Another idea to try, is using a tsvector for all the non-range filter fields.
(defmethod specialization/post-create-statements :postgres [prefix table-name]
  (mapv
   (fn [template] (format template prefix table-name))
   ["CREATE UNIQUE INDEX IF NOT EXISTS %s_identity_idx ON %s (model, model_id)"
    "CREATE INDEX IF NOT EXISTS %s_tsvector_idx ON %s USING gin (search_vector)"
    "CREATE INDEX IF NOT EXISTS %s_native_tsvector_idx ON %s USING gin (with_native_query_vector)"
    ;; Spam all the indexes for now, let's see if they get used on Stats / Ephemeral envs.
    "CREATE INDEX IF NOT EXISTS %s_model_archived_idx ON %s (model, archived)"
    "CREATE INDEX IF NOT EXISTS %s_archived_idx ON %s (archived)"]))

(defmethod specialization/batch-upsert! :postgres [table entries]
  (when (seq entries)
    (t2/query
     ;; The cost of dynamically calculating these keys should be small compared to the IO cost, so unoptimized.
     (let [update-keys (vec (disj (set (keys (first entries))) :id :model :model_id))
           excluded-kw (fn [column] (keyword (str "excluded." (name column))))]
       {:insert-into   table
        :values        entries
        :on-conflict   [:model :model_id]
        :do-update-set (zipmap update-keys (map excluded-kw update-keys))}))))

(defn- quote* [s]
  (str "'" (str/replace s "'" "''") "'"))

(defn- process-phrase [word-or-phrase]
  ;; a phrase is quoted even if the closing quotation mark has not been typed yet
  (cond
    ;; trailing quotation mark
    (= word-or-phrase "\"") nil
    ;; quoted phrases must be matched sequentially
    (str/starts-with? word-or-phrase "\"")
    (as-> word-or-phrase <>
      ;; remove the quote mark(s)
      (str/replace <> #"^\"|\"$" "")
      (str/trim <>)
      (str/split <> #"\s+")
      (map quote* <>)
      (str/join " <-> " <>))

    ;; negation
    (re-find #"^-\w" word-or-phrase)
    (str "!" (quote* (subs word-or-phrase 1)))

    ;; just a regular word
    :else
    (quote* word-or-phrase)))

(defn- split-preserving-quotes
  "Break up the words in the search input, preserving quoted and partially quoted segments."
  [s]
  (re-seq #"\"[^\"]*(?:\"|$)|[^\s\"]+|\s+" (u/lower-case-en s)))

(defn- process-clause [words-and-phrases]
  (->> words-and-phrases
       (remove #{"and"})
       (map process-phrase)
       (remove str/blank?)
       (str/join " & ")))

(defn- complete-last-word
  "Add wildcards at the end of the final word, so that we match ts completions."
  [expression]
  (str/replace expression #"(\S+)(?=\s*$)" "$1:*"))

(defn- to-tsquery-expr
  "Given the user input, construct a query in the Postgres tsvector query language."
  [input]
  (str
   (when input
     (let [trimmed        (str/trim input)
           complete?      (not (str/ends-with? trimmed "\""))
           ;; TODO also only complete if the :context is appropriate
           maybe-complete (if complete? complete-last-word identity)]
       (->> (str/replace trimmed "\\" "\\\\")
            split-preserving-quotes
            (remove str/blank?)
            (partition-by #{"or"})
            (remove #(= (first %) "or"))
            (map process-clause)
            (str/join " | ")
            maybe-complete)))))

(defmethod specialization/base-query :postgres
  [active-table search-term search-ctx select-items]
  {:select select-items
   :from   [[active-table :search_index]]
   ;; Using a join allows us to share the query expression between our SELECT and WHERE clauses.
   :join   [[[:raw "to_tsquery('"
              tsv-language "', "
              [:lift (to-tsquery-expr search-term)] ")"]
             :query] [:= 1 1]]
   :where  (if (str/blank? search-term)
             [:= [:inline 1] [:inline 1]]
             [:raw
              (str
               (if (:search-native-query search-ctx)
                 "with_native_query_vector"
                 "search_vector")
               " @@ query")])})

(defn- weighted-tsvector [weight text]
  ;; tsvector has a max value size of 1048575 bytes, limit to less than that because the multiple values get concatenated together
  [:setweight [:to_tsvector [:inline tsv-language] [:cast (u.str/limit-bytes text 500000) :text]] [:inline weight]])

(defmethod specialization/extra-entry-fields :postgres [entity]
  {:search_vector
   [:||
    (weighted-tsvector "A" (:name entity))
    (weighted-tsvector "B" (:searchable_text entity ""))]

   :with_native_query_vector
   [:||
    (weighted-tsvector "A" (:name entity))
    (weighted-tsvector "B" (str/join " " (keep entity [:searchable_text :native_query])))]})

;; See https://www.postgresql.org/docs/current/textsearch-controls.html#TEXTSEARCH-RANKING
;;  0 (the default) ignores the document length
;;  1 divides the rank by 1 + the logarithm of the document length
;;  2 divides the rank by the document length
;;  4 divides the rank by the mean harmonic distance between extents (this is implemented only by ts_rank_cd)
;;  8 divides the rank by the number of unique words in document
;; 16 divides the rank by 1 + the logarithm of the number of unique words in document
;; 32 divides the rank by itself + 1
(def ^:private ts-rank-normalization 0)

(defmethod specialization/text-score :postgres
  []
  [:ts_rank :search_vector :query [:inline ts-rank-normalization]])

(defmethod specialization/view-count-percentile-query :postgres
  [index-table p-value]
  (let [expr [:raw "percentile_cont(" [:lift p-value] ") WITHIN GROUP (ORDER BY view_count)"]]
    {:select   [:search_index.model [expr :vcp]]
     :from     [[index-table :search_index]]
     :group-by [:search_index.model]
     :having   [:is-not expr nil]}))
