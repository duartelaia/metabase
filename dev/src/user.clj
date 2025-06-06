(ns user
  (:require
   [clojure.java.io :as io]
   [environ.core :as env]
   [hashp.preload]
   [metabase.classloader.core :as classloader]
   [metabase.core.bootstrap]
   [metabase.util :as u]
   [nrepl.cmdline]))

(set! *warn-on-reflection* true)

(comment
  metabase.core.bootstrap/keep-me
  hashp.preload/keep-me)

;; Load all user.clj files (including the system-wide one).
(when *file* ; Ensure we don't load ourselves recursively, just in case.
  (->> (.getResources (.getContextClassLoader (Thread/currentThread)) "user.clj")
       enumeration-seq
       rest ; First file in the enumeration will be this file, so skip it.
       (run! #(do (println "Loading" (str %))
                  (clojure.lang.Compiler/load (io/reader %))))))

;; Wrap these with ignore-exceptions to reduce the "required" deps of this namespace
;; We sometimes need to run cmd stuffs like `clojure -M:migrate rollback n 3` and these
;; libraries might not be available in the classpath
(u/ignore-exceptions
 ;; make sure stuff like `=?` and what not are loaded
  (classloader/require 'mb.hawk.assert-exprs))

(u/ignore-exceptions
  (classloader/require 'metabase.test-runner.assert-exprs))

(u/ignore-exceptions
  (classloader/require 'humane-are.core)
  ((resolve 'humane-are.core/install!)))

(u/ignore-exceptions
  (classloader/require 'pjstadig.humane-test-output)
 ;; Initialize Humane Test Output if it's not already initialized. Don't enable humane-test-output when running tests
 ;; from the CLI, it breaks diffs. This uses [[env/env]] rather than [[metabase.config]] so we don't load that namespace
 ;; before we load [[metabase.core.bootstrap]]
  (when-not (= (env/env :mb-run-mode) "test")
    ((resolve 'pjstadig.humane-test-output/activate!))))

(defn dev
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev)
  (in-ns 'dev)
  :loaded)

(def ^:dynamic *enable-hot-reload* false)

(defn -main
  "This is called by the `:dev-start` cli alias.

  Try it out: `clj -M:dev:dev-start:drivers:drivers-dev:ee:ee-dev`

  Command Line Args:

  `--hot` - Checks for modified files and reloads them during a request."
  [& args]
  (when (contains? (set args) "--hot")
    (alter-var-root #'*enable-hot-reload* (constantly true)))
  (future (nrepl.cmdline/-main "-p" "50605" "-b" "0.0.0.0"))
  ((requiring-resolve 'dev/start!))
  (deref (promise)))
