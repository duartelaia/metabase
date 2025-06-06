(ns metabase.query-analysis.settings
  (:require
   [metabase.settings.core :refer [defsetting]]
   [metabase.util.i18n :as i18n]))

(defsetting query-analysis-enabled
  (i18n/deferred-tru "Whether or not we analyze any queries at all")
  :visibility :admin
  :export?    false
  :default    false
  :type       :boolean)

(defsetting sql-parsing-enabled
  (i18n/deferred-tru "SQL Parsing is disabled")
  :visibility :internal
  :export?    false
  :default    true
  :type       :boolean)
