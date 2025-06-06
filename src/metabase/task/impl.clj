(ns metabase.task.impl
  "Background task scheduling via Quartzite. Individual tasks are defined in `metabase.<module>.task.*`.

  ## Regarding Task Initialization:

  The most appropriate way to initialize tasks in any `metabase.<module>.task.*` namespace is to implement
  the [[init!]] multimethod in your `<module>.task.<task>` namespace, then add that namespace to `<module>.init`, and
  add `<module>.init` to `core.init` (as needed). All implementations of this method are called when the application
  goes through normal startup procedures. Inside this function you can do any work needed and add your task to the
  scheduler as usual via `schedule-task!`.

  ## Documentation

  For more detailed information about using Quartz in Metabase, including examples and best practices,
  see the [QUARTZ.md](src/metabase/task/QUARTZ.md) documentation.

  ## Quartz JavaDoc

  Find the JavaDoc for Quartz here: http://www.quartz-scheduler.org/api/2.3.0/index.html"
  (:require
   [clojure.string :as str]
   [clojurewerkz.quartzite.jobs :as jobs]
   [clojurewerkz.quartzite.scheduler :as qs]
   [environ.core :as env]
   [metabase.classloader.core :as classloader]
   [metabase.db :as mdb]
   [metabase.task.bootstrap]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms])
  (:import
   (org.quartz CronTrigger JobDetail JobExecutionContext JobExecutionException JobKey JobListener
               JobPersistenceException ObjectAlreadyExistsException Scheduler Trigger TriggerKey
               TriggerListener)))

(set! *warn-on-reflection* true)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               SCHEDULER INSTANCE                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(defonce ^:dynamic ^{:doc "Override the global Quartz scheduler by binding this var."}
  *quartz-scheduler*
  (atom nil))

(defn scheduler
  "Fetch the instance of our Quartz scheduler."
  ^Scheduler []
  @*quartz-scheduler*)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            FINDING & LOADING TASKS                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti init!
  "Initialize (i.e., schedule) Job(s) with a given name. All implementations of this method are called once and only
  once when the Quartz task scheduler is initialized. Task namespaces (`metabase.<module>.task.*`) should add new
  implementations of this method to schedule the jobs they define (i.e., with a call to `schedule-task!`.)

  The dispatch value for this function can be any unique keyword, but by convention is a namespaced keyword version of
  the name of the Job being initialized; for sake of consistency with the Job name itself, the keyword should be left
  CamelCased.

    (defmethod task/init! ::SendPulses [_]
      (task/schedule-task! my-job my-trigger))"
  {:arglists '([job-name-string])}
  keyword)

(defn- init-tasks!
  "Call all implementations of `init!`"
  []
  (doseq [[k f] (methods init!)]
    (try
      ;; don't bother logging namespace for now, maybe in the future if there's tasks of the same name in multiple
      ;; namespaces we can log it
      (log/info "Initializing task" (u/format-color 'green (name k)) (u/emoji "📆"))
      (f k)
      (catch Throwable e
        (log/errorf e "Error initializing task %s" k)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          STARTING/STOPPING SCHEDULER                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- set-jdbc-backend-properties! []
  (metabase.task.bootstrap/set-jdbc-backend-properties! (mdb/db-type)))

(defn- delete-jobs-with-no-class!
  "Delete any jobs that have been scheduled but whose class is no longer available."
  []
  (when-let [scheduler (scheduler)]
    (doseq [job-key (.getJobKeys scheduler nil)]
      (try
        (qs/get-job scheduler job-key)
        (catch JobPersistenceException e
          (when (instance? ClassNotFoundException (.getCause e))
            (log/warnf "Deleting job %s due to class not found (%s)"
                       (.getName ^JobKey job-key)
                       (ex-message (.getCause e)))
            (qs/delete-job scheduler job-key)))))))

(defn- reset-errored-triggers!
  "Quartz does not play well with rolling updates. For example, if a new instance adds and schedules a new job, an older
  instance may pick this up, but be unable to construct the job. It will then put the trigger into the `ERROR` state,
  from which it will never recover.

  Actually fixing this odd and undesirable behavior would be the ideal solution, but as a stopgap, let's just
  automatically reset all `ERROR`ed triggers to `WAITING` on startup."
  [^Scheduler scheduler]
  (doseq [^TriggerKey tk (.getTriggerKeys scheduler nil)]
    ;; From dox: "Only affects triggers that are in ERROR state - if identified trigger is not
    ;; in that state then the result is a no-op."
    (.resetTriggerFromErrorState scheduler tk)))

(defn init-scheduler!
  "Initialize our Quartzite scheduler which allows jobs to be submitted and triggers to scheduled. Puts scheduler in
  standby mode. Call [[start-scheduler!]] to begin running scheduled tasks."
  []
  (classloader/the-classloader)
  (when-not @*quartz-scheduler*
    (set-jdbc-backend-properties!)
    (let [new-scheduler (qs/initialize)]
      (when (compare-and-set! *quartz-scheduler* nil new-scheduler)
        (qs/standby new-scheduler)
        (log/info "Task scheduler initialized into standby mode.")
        ;; Register Prometheus listeners

        (delete-jobs-with-no-class!)
        (reset-errored-triggers! new-scheduler)
        (init-tasks!)))))

;;; this is a function mostly to facilitate testing.
(defn- disable-scheduler? []
  (some-> (env/env :mb-disable-scheduler) Boolean/parseBoolean))

(defn start-scheduler!
  "Start the task scheduler. Tasks do not run before calling this function."
  []
  (if (disable-scheduler?)
    (log/warn  "Metabase task scheduler disabled. Scheduled tasks will not be ran.")
    (do (init-scheduler!)
        (qs/start (scheduler))
        (log/info "Task scheduler started"))))

(defn stop-scheduler!
  "Stop our Quartzite scheduler and shutdown any running executions."
  []
  (let [[old-scheduler] (reset-vals! *quartz-scheduler* nil)]
    (when old-scheduler
      (qs/shutdown old-scheduler))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           SCHEDULING/DELETING TASKS                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(mu/defn- reschedule-task!
  "Assuming that [[job]] is already registered, ensure that [[new-trigger]] is scheduled to trigger it."
  [job         :- (ms/InstanceOfClass JobDetail)
   new-trigger :- (ms/InstanceOfClass Trigger)]
  (try
    (when-let [scheduler (scheduler)]
      (let [job-key          (.getKey ^JobDetail job)
            new-trigger-key  (.getKey ^Trigger new-trigger)
            triggers         (try (qs/get-triggers-of-job scheduler job-key) (catch Exception _))
            matching-trigger (first (filter (comp #{new-trigger-key} #(.getKey ^Trigger %)) triggers))
            replaced-trigger (or matching-trigger (first triggers))]
        (log/debugf "Rescheduling job %s" (.getName job-key))
        (if-not replaced-trigger
          (.scheduleJob scheduler new-trigger)
          (let [replaced-key (.getKey ^Trigger replaced-trigger)]
            (when-not matching-trigger
              (log/warnf "Replacing trigger %s with trigger %s%s"
                         (.getName replaced-key)
                         (.getName new-trigger-key)
                         (when (> (count triggers) 1)
                           ;; We probably want more intuitive rescheduling semantics for multi-trigger jobs...
                           ;; Ideally we would pass *all* the new triggers at once, so we can match them up atomically.
                           ;; The current behavior is especially confounding if replacing N triggers with M ones.
                           (str " (chosen randomly from " (count triggers) " existing ones)"))))
            (.rescheduleJob scheduler replaced-key new-trigger)))))
    (catch Throwable e
      (log/error e "Error rescheduling job"))))

(mu/defn reschedule-trigger!
  "Reschedule a trigger with the same key as the given trigger.

  Used to update trigger properties like priority."
  [trigger :- (ms/InstanceOfClass Trigger)]
  (when-let [scheduler (scheduler)]
    (.rescheduleJob scheduler (.getKey ^Trigger trigger) trigger)))

(mu/defn schedule-task!
  "Add a given job and trigger to our scheduler."
  [job :- (ms/InstanceOfClass JobDetail) trigger :- (ms/InstanceOfClass Trigger)]
  (when-let [scheduler (scheduler)]
    (try
      (qs/schedule scheduler job trigger)
      (catch ObjectAlreadyExistsException _
        (log/debug "Job already exists:" (-> ^JobDetail job .getKey .getName))
        (reschedule-task! job trigger)))))

(mu/defn trigger-now!
  "Immediately trigger execution of task"
  [job-key :- (ms/InstanceOfClass JobKey)]
  (try
    (when-let [scheduler (scheduler)]
      (.triggerJob scheduler job-key))
    (catch Throwable e
      (log/errorf e "Failed to trigger immediate execution of task %s" job-key))))

(mu/defn delete-task!
  "Delete a task from the scheduler"
  [job-key :- (ms/InstanceOfClass JobKey) trigger-key :- (ms/InstanceOfClass TriggerKey)]
  (when-let [scheduler (scheduler)]
    (qs/delete-trigger scheduler trigger-key)
    (qs/delete-job scheduler job-key)))

(mu/defn add-job!
  "Add a job separately from a trigger, replace if the job is already there"
  [job :- (ms/InstanceOfClass JobDetail)]
  (when-let [scheduler (scheduler)]
    (qs/add-job scheduler job true)))

(mu/defn add-trigger!
  "Add a trigger. Assumes the trigger is already associated to a job (i.e. `trigger/for-job`)"
  [trigger :- (ms/InstanceOfClass Trigger)]
  (when-let [scheduler (scheduler)]
    (qs/add-trigger scheduler trigger)))

(mu/defn delete-trigger!
  "Remove `trigger-key` from the scheduler"
  [trigger-key :- (ms/InstanceOfClass TriggerKey)]
  (when-let [scheduler (scheduler)]
    (qs/delete-trigger scheduler trigger-key)))

(mu/defn delete-all-triggers-of-job!
  "Delete all triggers for a given job key."
  [job-key :- (ms/InstanceOfClass JobKey)]
  (when-let [scheduler (scheduler)]
    (qs/delete-triggers scheduler (map #(.getKey ^Trigger %) (qs/get-triggers-of-job scheduler job-key)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 Scheduler Info                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- job-detail->info [^JobDetail job-detail]
  {:key                              (-> (.getKey job-detail) .getName)
   :class                            (-> (.getJobClass job-detail) .getCanonicalName)
   :description                      (.getDescription job-detail)
   :concurrent-execution-disallowed? (.isConcurrentExectionDisallowed job-detail)
   :durable?                         (.isDurable job-detail)
   :requests-recovery?               (.requestsRecovery job-detail)})

(defmulti ^:private trigger->info
  {:arglists '([trigger])}
  class)

(defmethod trigger->info Trigger
  [^Trigger trigger]
  {:description        (.getDescription trigger)
   :end-time           (.getEndTime trigger)
   :final-fire-time    (.getFinalFireTime trigger)
   :key                (-> (.getKey trigger) .getName)
   :state              (some->> (.getKey trigger) (.getTriggerState (scheduler)) str)
   :next-fire-time     (.getNextFireTime trigger)
   :previous-fire-time (.getPreviousFireTime trigger)
   :priority           (.getPriority trigger)
   :start-time         (.getStartTime trigger)
   :may-fire-again?    (.mayFireAgain trigger)
   :data               (into {} (.getJobDataMap trigger))})

(defmethod trigger->info CronTrigger
  [^CronTrigger trigger]
  (assoc
   ((get-method trigger->info Trigger) trigger)
   :schedule
   (.getCronExpression trigger)

   :timezone
   (.getID (.getTimeZone trigger))

   :misfire-instruction
   ;; not 100% sure why `case` doesn't work here...
   (condp = (.getMisfireInstruction trigger)
     CronTrigger/MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY "IGNORE_MISFIRE_POLICY"
     CronTrigger/MISFIRE_INSTRUCTION_SMART_POLICY          "SMART_POLICY"
     CronTrigger/MISFIRE_INSTRUCTION_FIRE_ONCE_NOW         "FIRE_ONCE_NOW"
     CronTrigger/MISFIRE_INSTRUCTION_DO_NOTHING            "DO_NOTHING"
     (format "UNKNOWN: %d" (.getMisfireInstruction trigger)))))

(defn- ->job-key ^JobKey [x]
  (cond
    (instance? JobKey x) x
    (string? x)          (JobKey. ^String x)))

(defn job-exists?
  "Check whether there is a Job with the given key."
  [job-key]
  (boolean
   (let [s (scheduler)]
     (when (and s (not (.isShutdown s)))
       (qs/get-job s (->job-key job-key))))))

(defn job-info
  "Get info about a specific Job (`job-key` can be either a String or `JobKey`).

    (task/job-info \"metabase.task.sync-and-analyze.job\")"
  [job-key]
  (when-let [scheduler (scheduler)]
    (let [job-key (->job-key job-key)]
      (try
        (assoc (job-detail->info (qs/get-job scheduler job-key))
               :triggers (for [trigger (sort-by #(-> ^Trigger % .getKey .getName)
                                                (qs/get-triggers-of-job scheduler job-key))]
                           (trigger->info trigger)))
        (catch ClassNotFoundException _
          (log/infof "Class not found for Quartz Job %s. This probably means that this job was removed or renamed." (.getName job-key)))
        (catch Throwable e
          (log/warnf e "Error fetching details for Quartz Job: %s" (.getName job-key)))))))

(defn- jobs-info []
  (->> (some-> (scheduler) (.getJobKeys nil))
       (sort-by #(.getName ^JobKey %))
       (map job-info)
       (filter some?)))

(defn existing-triggers
  "Get the existing triggers for a job by key name, if it exists."
  [job-key trigger-key]
  (filter #(= (:key %) (.getName ^TriggerKey trigger-key)) (:triggers (job-info job-key))))

(defn scheduler-info
  "Return raw data about all the scheduler and scheduled tasks (i.e. Jobs and Triggers). Primarily for debugging
  purposes."
  []
  {:scheduler (some-> (scheduler) .getMetaData .getSummary str/split-lines)
   :jobs      (jobs-info)})

(defmacro rerun-on-error
  "Retry the current Job if an exception is thrown by the enclosed code."
  {:style/indent 1}
  [^JobExecutionContext ctx & body]
  `(let [msg# (str (.getName (.getKey (.getJobDetail ~ctx))) " failed, but we will try it again.")]
     (try
       ~@body
       (catch Exception e#
         (log/error e# msg#)
         (throw (JobExecutionException. msg# e# true))))))

#_{:clj-kondo/ignore [:discouraged-var]}
(defmacro defjob
  "Like `clojurewerkz.quartzite.task/defjob` but with a log context."
  [jtype args & body]
  `(jobs/defjob ~jtype ~args
     (log/with-context {:quartz-job-type (quote ~jtype)}
       ~@body)))

(defn add-job-listener!
  "Add a [Quartz Joblistener](https://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/tutorial-lesson-07.html). That will
  be called turing Job activation."
  [^JobListener job-listener]
  (when-let [scheduler (scheduler)]
    (.. scheduler
        getListenerManager
        (addJobListener job-listener))))

(defn add-trigger-listener!
  "Add a [Quartz Trigger listener](https://www.quartz-scheduler.org/documentation/quartz-2.3.0/tutorials/tutorial-lesson-07.html). That will
  be called turing trigger activation."
  [^TriggerListener trigger-listener]
  (when-let [scheduler (scheduler)]
    (.. scheduler
        getListenerManager
        (addTriggerListener trigger-listener))))
