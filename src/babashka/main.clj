(ns babashka.main
  {:no-doc true}
  (:refer-clojure :exclude [error-handler])
  (:require
   [babashka.impl.bencode :refer [bencode-namespace]]
   [babashka.impl.cheshire :refer [cheshire-core-namespace]]
   [babashka.impl.classes :as classes]
   [babashka.impl.classpath :as cp]
   [babashka.impl.clojure.core :as core :refer [core-extras]]
   [babashka.impl.clojure.java.browse :refer [browse-namespace]]
   [babashka.impl.clojure.java.io :refer [io-namespace]]
   [babashka.impl.clojure.java.shell :refer [shell-namespace]]
   [babashka.impl.clojure.main :as clojure-main :refer [demunge]]
   [babashka.impl.clojure.pprint :refer [pprint-namespace]]
   [babashka.impl.clojure.stacktrace :refer [stacktrace-namespace]]
   [babashka.impl.clojure.zip :refer [zip-namespace]]
   [babashka.impl.common :as common]
   [babashka.impl.curl :refer [curl-namespace]]
   [babashka.impl.data :as data]
   [babashka.impl.datafy :refer [datafy-namespace]]
   [babashka.impl.error-handler :refer [error-handler]]
   [babashka.impl.features :as features]
   [babashka.impl.http-kit :refer [http-kit-server-namespace]]
   [babashka.impl.reitit :refer
    [reitit-ring-namespace
     ring-middleware-defaults-namespace
     ring-util-response-namespace
     ]]
   [babashka.impl.pods :as pods]
   [babashka.impl.protocols :refer [protocols-namespace]]
   [babashka.impl.repl :as repl]
   [babashka.impl.socket-repl :as socket-repl]
   [babashka.impl.test :as t]
   [babashka.impl.tools.cli :refer [tools-cli-namespace]]
   [babashka.nrepl.server :as nrepl-server]
   [babashka.wait :as wait]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hf.depstar.uberjar :as uberjar]
   [sci.addons :as addons]
   [sci.core :as sci]
   [sci.impl.namespaces :as sci-namespaces]
   [sci.impl.unrestrict :refer [*unrestricted*]]
   [sci.impl.vars :as vars])
  (:gen-class))

(def windows?
  (some-> (System/getProperty "os.name")
          (str/lower-case)
          (str/index-of "win")))

(if-not windows?
  (do ;; see https://github.com/oracle/graal/issues/1784
    (require 'babashka.impl.pipe-signal-handler)
    (let [handle-pipe! (resolve 'babashka.impl.pipe-signal-handler/handle-pipe!)]
      (def handle-pipe! @handle-pipe!))
    (let [pipe-signal-received? (resolve 'babashka.impl.pipe-signal-handler/pipe-signal-received?)]
      (def pipe-signal-received? @pipe-signal-received?))
    ;; JVM_FindSignal called:  Unimplemented
    (require 'babashka.impl.sigint-handler)
    (def handle-sigint! @(resolve 'babashka.impl.sigint-handler/handle-sigint!)))
  (do
    (def handle-pipe! (constantly nil))
    (def pipe-signal-received? (constantly false))
    (def handle-sigint! (constantly nil))))

(when features/xml?
  (require '[babashka.impl.xml]))

(when features/yaml?
  (require '[babashka.impl.yaml]
           '[babashka.impl.ordered]))

#_(when features/jdbc?
  (require '[babashka.impl.jdbc]))

(when features/core-async?
  (require '[babashka.impl.async]))

(when features/csv?
  (require '[babashka.impl.csv]))

(when features/transit?
  (require '[babashka.impl.transit]))

(when features/datascript?
  (require '[babashka.impl.datascript]))

(sci/alter-var-root sci/in (constantly *in*))
(sci/alter-var-root sci/out (constantly *out*))
(sci/alter-var-root sci/err (constantly *err*))

(set! *warn-on-reflection* true)
;; To detect problems when generating the image, run:
;; echo '1' | java -agentlib:native-image-agent=config-output-dir=/tmp -jar target/babashka-xxx-standalone.jar '...'
;; with the java provided by GraalVM.

(defn parse-opts [options]
  (let [opts (loop [options options
                    opts-map {}]
               (if options
                 (let [opt (first options)]
                   (case opt
                     ("--") (assoc opts-map :command-line-args (next options))
                     ("--version") {:version true}
                     ("--help" "-h" "-?") {:help? true}
                     ("--verbose")(recur (next options)
                                         (assoc opts-map
                                                :verbose? true))
                     ("--describe") (recur (next options)
                                           (assoc opts-map
                                                  :describe? true))
                     ("--stream") (recur (next options)
                                         (assoc opts-map
                                                :stream? true))
                     ("-i") (recur (next options)
                                   (assoc opts-map
                                          :shell-in true))
                     ("-I") (recur (next options)
                                   (assoc opts-map
                                          :edn-in true))
                     ("-o") (recur (next options)
                                   (assoc opts-map
                                          :shell-out true))
                     ("-O") (recur (next options)
                                   (assoc opts-map
                                          :edn-out true))
                     ("-io") (recur (next options)
                                    (assoc opts-map
                                           :shell-in true
                                           :shell-out true))
                     ("-iO") (recur (next options)
                                    (assoc opts-map
                                           :shell-in true
                                           :edn-out true))
                     ("-Io") (recur (next options)
                                    (assoc opts-map
                                           :edn-in true
                                           :shell-out true))
                     ("-IO") (recur (next options)
                                    (assoc opts-map
                                           :edn-in true
                                           :edn-out true))
                     ("--classpath", "-cp")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map :classpath (first options))))
                     ("--uberscript")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :uberscript (first options))))
                     ("--uberjar")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :uberjar (first options))))
                     ("-f" "--file")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :file (first options))))
                     ("--jar" "-jar")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :jar (first options))))
                     ("--repl")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map
                                     :repl true)))
                     ("--socket-repl")
                     (let [options (next options)
                           opt (first options)
                           opt (when (and opt (not (str/starts-with? opt "-")))
                                 opt)
                           options (if opt (next options)
                                       options)]
                       (recur options
                              (assoc opts-map
                                     :socket-repl (or opt "1666"))))
                     ("--nrepl-server")
                     (let [options (next options)
                           opt (first options)
                           opt (when (and opt (not (str/starts-with? opt "-")))
                                 opt)
                           options (if opt (next options)
                                       options)]
                       (recur options
                              (assoc opts-map
                                     :nrepl (or opt "1667"))))
                     ("--eval", "-e")
                     (let [options (next options)]
                       (recur (next options)
                              (update opts-map :expressions (fnil conj []) (first options))))
                     ("--main", "-m")
                     (let [options (next options)]
                       (recur (next options)
                              (assoc opts-map :main (first options))))
                     (if (some opts-map [:file :jar :socket-repl :expressions :main])
                       (assoc opts-map
                              :command-line-args options)
                       (let [trimmed-opt (str/triml opt)
                             c (.charAt trimmed-opt 0)]
                         (case c
                           (\( \{ \[ \* \@ \#)
                           (-> opts-map
                               (update :expressions (fnil conj []) (first options))
                               (assoc :command-line-args (next options)))
                           (assoc opts-map
                                  (if (str/ends-with? opt ".jar")
                                    :jar :file) opt
                                  :command-line-args (next options)))))))
                 opts-map))]
    opts))

(defn edn-seq*
  [^java.io.BufferedReader rdr]
  (let [edn-val (edn/read {:eof ::EOF} rdr)]
    (when (not (identical? ::EOF edn-val))
      (cons edn-val (lazy-seq (edn-seq* rdr))))))

(defn edn-seq
  [in]
  (edn-seq* in))

(defn shell-seq [in]
  (line-seq (java.io.BufferedReader. in)))

(def version (str/trim (slurp (io/resource "BABASHKA_VERSION"))))

(defn print-version []
  (println (str "babashka v" version)))


(defn print-help []
  (println (str "Babashka v" version))
  ;; (println (str "sci v" (str/trim (slurp (io/resource "SCI_VERSION")))))
  (println)
  (println "Options must appear in the order of groups mentioned below.")
  (println "
Help:

  --help, -h or -?    Print this help text.
  --version           Print the current version of babashka.
  --describe          Print an EDN map with information about this version of babashka.

In- and output flags:

  -i                  Bind *input* to a lazy seq of lines from stdin.
  -I                  Bind *input* to a lazy seq of EDN values from stdin.
  -o                  Write lines to stdout.
  -O                  Write EDN values to stdout.
  --stream            Stream over lines or EDN values from stdin. Combined with -i or -I *input* becomes a single value per iteration.

Uberscript:

  --uberscript <file> Collect preloads, -e, -f and -m and all required namespaces from the classpath into a single executable file.

Evaluation:

  -e, --eval <expr>   Evaluate an expression.
  -f, --file <path>   Evaluate a file.
  -cp, --classpath    Classpath to use.
  -m, --main <ns>     Call the -main function from namespace with args.
  --verbose           Print debug information and entire stacktrace in case of exception.

REPL:

  --repl              Start REPL. Use rlwrap for history.
  --socket-repl       Start socket REPL. Specify port (e.g. 1666) or host and port separated by colon (e.g. 127.0.0.1:1666).
  --nrepl-server      Start nREPL server. Specify port (e.g. 1667) or host and port separated by colon (e.g. 127.0.0.1:1667).

If neither -e, -f, or --socket-repl are specified, then the first argument that is not parsed as a option is treated as a file if it exists, or as an expression otherwise. Everything after that is bound to *command-line-args*. Use -- to separate script command line args from bb command line args."))

(defn print-describe []
  (println
   (format
    (str/trim "
{:babashka/version   \"%s\"
 :feature/core-async %s
 :feature/csv        %s
 :feature/java-nio   %s
 :feature/java-time  %s
 :feature/xml        %s
 :feature/yaml       %s
 :feature/jdbc       %s
 :feature/postgresql %s
 :feature/hsqldb     %s}")
    version
    features/core-async?
    features/csv?
    features/java-nio?
    features/java-time?
    features/xml?
    features/yaml?
    features/jdbc?
    features/postgresql?
    features/hsqldb?)))

(defn read-file [file]
  (let [f (io/file file)]
    (if (.exists f)
      (as-> (slurp file) x
        ;; remove shebang
        (str/replace x #"^#!.*" ""))
      (throw (Exception. (str "File does not exist: " file))))))

(def reflection-var (sci/new-dynamic-var '*warn-on-reflection* false))

(def load-file*
  (with-meta
    (fn [sci-ctx f]
      (let [f (io/file f)
            s (slurp f)]
        (sci/with-bindings {sci/ns @sci/ns
                            sci/file (.getAbsolutePath f)}
          (sci/eval-string* sci-ctx s))))
    {:sci.impl/op :needs-ctx}))

(defn start-socket-repl! [address ctx]
  (socket-repl/start-repl! address ctx)
  ;; hang until SIGINT
  @(promise))

(defn start-nrepl! [address ctx]
  (let [dev? (= "true" (System/getenv "BABASHKA_DEV"))
        nrepl-opts (nrepl-server/parse-opt address)
        nrepl-opts (assoc nrepl-opts
                          :debug dev?
                          :describe {"versions" {"babashka" version}}
                          :thread-bind [reflection-var])]
    (nrepl-server/start-server! ctx nrepl-opts)
    (binding [*out* *err*]
      (println "For more info visit https://github.com/borkdude/babashka/blob/master/doc/repl.md#nrepl.")))
  ;; hang until SIGINT
  @(promise))

(defn exit [n]
  (throw (ex-info "" {:bb/exit-code n})))

(def aliases
  (cond->
      '{tools.cli clojure.tools.cli
        edn clojure.edn
        wait babashka.wait
        signal babashka.signal
        shell clojure.java.shell
        io clojure.java.io
        json cheshire.core
        curl babashka.curl
        bencode bencode.core}
    features/xml?        (assoc 'xml 'clojure.data.xml)
    features/yaml?       (assoc 'yaml 'clj-yaml.core)
    features/jdbc?       (assoc 'jdbc 'next.jdbc)
    features/core-async? (assoc 'async 'clojure.core.async)
    features/csv?        (assoc 'csv 'clojure.data.csv)
    features/transit?    (assoc 'transit 'cognitect.transit)))

(def cp-state (atom nil))

(defn add-classpath* [add-to-cp]
  (swap! cp-state
         (fn [{:keys [:cp]}]
           (let [new-cp
                 (if-not cp add-to-cp
                         (str cp (System/getProperty "path.separator") add-to-cp))]
             {:loader (cp/loader new-cp)
              :cp new-cp})))
  nil)

(def namespaces
  (cond->
      {'clojure.tools.cli tools-cli-namespace
       'clojure.java.shell shell-namespace
       'babashka.wait {'wait-for-port wait/wait-for-port
                       'wait-for-path wait/wait-for-path}
       'babashka.signal {'pipe-signal-received? pipe-signal-received?}
       'clojure.java.io io-namespace
       'cheshire.core cheshire-core-namespace
       'clojure.data data/data-namespace
       'clojure.stacktrace stacktrace-namespace
       'clojure.zip zip-namespace
       'clojure.main {'demunge demunge
                      'repl-requires clojure-main/repl-requires}
       'clojure.test t/clojure-test-namespace
       'babashka.classpath {'add-classpath add-classpath*}
       'clojure.pprint pprint-namespace
       'babashka.curl curl-namespace
       'babashka.pods pods/pods-namespace
       'bencode.core bencode-namespace
       'clojure.java.browse browse-namespace
       'clojure.datafy datafy-namespace
       'clojure.core.protocols protocols-namespace
       'org.httpkit.server http-kit-server-namespace
       'reitit.ring reitit-ring-namespace
       'ring.middleware.defaults ring-middleware-defaults-namespace
       'ring.util.response ring-util-response-namespace
       }
    features/xml?  (assoc 'clojure.data.xml @(resolve 'babashka.impl.xml/xml-namespace))
    features/yaml? (assoc 'clj-yaml.core @(resolve 'babashka.impl.yaml/yaml-namespace)
                          'flatland.ordered.map @(resolve 'babashka.impl.ordered/ordered-map-ns))
;;    features/jdbc? (assoc 'next.jdbc @(resolve 'babashka.impl.jdbc/njdbc-namespace)
;;                          'next.jdbc.sql @(resolve 'babashka.impl.jdbc/next-sql-namespace))
    features/core-async? (assoc 'clojure.core.async @(resolve 'babashka.impl.async/async-namespace)
                                'clojure.core.async.impl.protocols @(resolve 'babashka.impl.async/async-protocols-namespace))
    features/csv?  (assoc 'clojure.data.csv @(resolve 'babashka.impl.csv/csv-namespace))
    features/transit? (assoc 'cognitect.transit @(resolve 'babashka.impl.transit/transit-namespace))
    features/datascript? (assoc 'datascript.core @(resolve 'babashka.impl.datascript/datascript-namespace))))

(def bindings
  {'java.lang.System/exit exit ;; override exit, so we have more control
   'System/exit exit})

(def imports
  '{ArithmeticException java.lang.ArithmeticException
    AssertionError java.lang.AssertionError
    BigDecimal java.math.BigDecimal
    BigInteger java.math.BigInteger
    Boolean java.lang.Boolean
    Byte java.lang.Byte
    Character java.lang.Character
    Class java.lang.Class
    ClassNotFoundException java.lang.ClassNotFoundException
    Comparable java.lang.Comparable
    Double java.lang.Double
    Exception java.lang.Exception
    IllegalArgumentException java.lang.IllegalArgumentException
    Integer java.lang.Integer
    File java.io.File
    Float java.lang.Float
    Long java.lang.Long
    Math java.lang.Math
    Number java.lang.Number
    NumberFormatException java.lang.NumberFormatException
    Object java.lang.Object
    Runtime java.lang.Runtime
    RuntimeException java.lang.RuntimeException
    Process        java.lang.Process
    ProcessBuilder java.lang.ProcessBuilder
    Short java.lang.Short
    String java.lang.String
    StringBuilder java.lang.StringBuilder
    System java.lang.System
    Thread java.lang.Thread
    Throwable java.lang.Throwable})

(def input-var (sci/new-dynamic-var '*input* nil))

(defn main
  [& args]
  (handle-pipe!)
  (handle-sigint!)
  (binding [*unrestricted* true]
    (sci/binding [reflection-var false
                  core/data-readers @core/data-readers]
      (let [{version-opt :version
             :keys [:shell-in :edn-in :shell-out :edn-out
                    :help? :file :command-line-args
                    :expressions :stream?
                    :repl :socket-repl :nrepl
                    :verbose? :classpath
                    :main :uberscript :describe?
                    :jar :uberjar] :as _opts}
            (parse-opts args)
            _ (do ;; set properties
                (when main (System/setProperty "babashka.main" main))
                (System/setProperty "babashka.version" version))
            read-next (fn [*in*]
                        (if (pipe-signal-received?)
                          ::EOF
                          (if stream?
                            (if shell-in (or (read-line) ::EOF)
                                (edn/read {;;:readers *data-readers*
                                           :eof ::EOF} *in*))
                            (delay (cond shell-in
                                         (shell-seq *in*)
                                         edn-in
                                         (edn-seq *in*)
                                         :else
                                         (edn/read *in*))))))
            uberscript-sources (atom ())
            env (atom {})
            classpath (or classpath
                          (System/getenv "BABASHKA_CLASSPATH"))
            _ (when classpath
                (add-classpath* classpath))
            abs-path (when file
                       (let [abs-path (.getAbsolutePath (io/file file))]
                         (vars/bindRoot sci/file abs-path)
                         (System/setProperty "babashka.file" abs-path)
                         abs-path))
            _ (when jar
                (add-classpath* jar))
            load-fn (fn [{:keys [:namespace :reload]}]
                      (when-let [{:keys [:loader]}
                                  @cp-state]
                        (if ;; ignore built-in namespaces when uberscripting, unless with :reload
                            (and uberscript
                                 (not reload)
                                 (or (contains? namespaces namespace)
                                     (contains? sci-namespaces/namespaces namespace)))
                          ""
                          (let [res (cp/source-for-namespace loader namespace nil)]
                            (when uberscript (swap! uberscript-sources conj (:source res)))
                            res))))
            main (if (and jar (not main))
                   (when-let [res (cp/getResource
                                   (cp/loader jar)
                                   ["META-INF/MANIFEST.MF"] {:url? true})]
                     (cp/main-ns res))
                   main)

            ;; TODO: pull more of these values to compile time
            opts {:aliases aliases
                  :namespaces (-> namespaces
                                  (assoc 'clojure.core
                                         (assoc core-extras
                                                '*command-line-args*
                                                (sci/new-dynamic-var '*command-line-args* command-line-args)
                                                '*warn-on-reflection* reflection-var
                                                'load-file load-file*))
                                  (assoc-in ['clojure.java.io 'resource]
                                            (fn [path]
                                              (when-let [{:keys [:loader]} @cp-state]
                                                (if (str/starts-with? path "/") nil ;; non-relative paths always return nil
                                                    (cp/getResource loader [path] {:url? true})))))
                                  (assoc-in ['user (with-meta '*input*
                                                     (when-not stream?
                                                       {:sci.impl/deref! true}))] input-var)
                                  (assoc-in ['clojure.main 'repl]
                                            ^{:sci.impl/op :needs-ctx}
                                            (fn [ctx & opts]
                                              (let [opts (apply hash-map opts)]
                                                (repl/start-repl! ctx opts)))))
                  :bindings bindings
                  :env env
                  :features #{:bb :clj}
                  :classes classes/class-map
                  :imports imports
                  :load-fn load-fn
                  :uberscript uberscript
                  :readers core/data-readers}
            opts (addons/future opts)
            sci-ctx (sci/init opts)
            _ (vreset! common/ctx sci-ctx)
            preloads (some-> (System/getenv "BABASHKA_PRELOADS") (str/trim))
            [expressions exit-code]
            (cond expressions [expressions nil]
                  main [[(format "(ns user (:require [%1$s])) (apply %1$s/-main *command-line-args*)"
                                 main)] nil]
                  file (try [[(read-file file)] nil]
                            (catch Exception e
                              (error-handler e {:expression expressions
                                                 :verbose? verbose?
                                                 :preloads preloads
                                                 :loader (:loader @cp-state)}))))
            expression (str/join " " expressions) ;; this might mess with the locations...
            exit-code
            ;; handle preloads
            (if exit-code exit-code
                (do (when preloads
                      (sci/binding [sci/file "<preloads>"]
                        (try
                          (sci/eval-string* sci-ctx preloads)
                          (catch Throwable e
                            (error-handler e {:expression expression
                                               :verbose? verbose?
                                               :preloads preloads
                                               :loader (:loader @cp-state)})))))
                    nil))
            exit-code
            (or exit-code
                (second
                 (cond version-opt
                       [(print-version) 0]
                       help?
                       [(print-help) 0]
                       describe?
                       [(print-describe) 0]
                       repl [(repl/start-repl! sci-ctx) 0]
                       socket-repl [(start-socket-repl! socket-repl sci-ctx) 0]
                       nrepl [(start-nrepl! nrepl sci-ctx) 0]
                       uberjar [nil 0]
                       expressions
                       (sci/binding [sci/file abs-path]
                         (try
                           (loop []
                             (let [in (read-next *in*)]
                               (if (identical? ::EOF in)
                                 [nil 0] ;; done streaming
                                 (let [res [(let [res
                                                  (sci/binding [sci/file (or @sci/file "<expr>")
                                                                input-var in]
                                                    (sci/eval-string* sci-ctx expression))]
                                              (when (some? res)
                                                (if-let [pr-f (cond shell-out println
                                                                    edn-out prn)]
                                                  (if (coll? res)
                                                    (doseq [l res
                                                            :while (not (pipe-signal-received?))]
                                                      (pr-f l))
                                                    (pr-f res))
                                                  (prn res)))) 0]]
                                   (if stream?
                                     (recur)
                                     res)))))
                           (catch Throwable e
                             (error-handler e {:expression expression
                                                :verbose? verbose?
                                                :preloads preloads
                                                :loader (:loader @cp-state)}))))
                       uberscript [nil 0]
                       :else [(repl/start-repl! sci-ctx) 0]))
                1)]
        (flush)
        (when uberscript
          (let [uberscript-out uberscript]
            (spit uberscript-out "") ;; reset file
            (doseq [s (distinct @uberscript-sources)]
              (spit uberscript-out s :append true))
            (spit uberscript-out preloads :append true)
            (spit uberscript-out expression :append true)))
        (when uberjar
          (uberjar/run {:dest uberjar
                        :jar :uber
                        :classpath classpath
                        :main-class main
                        :verbose verbose?}))
        exit-code))))

(defn -main
  [& args]
  (if-let [dev-opts (System/getenv "BABASHKA_DEV")]
    (let [{:keys [:n]} (if (= "true" dev-opts) {:n 1}
                           (edn/read-string dev-opts))
          last-iteration (dec n)]
      (dotimes [i n]
        (if (< i last-iteration)
          (with-out-str (apply main args))
          (do (apply main args)
              (binding [*out* *err*]
                (println "ran" n "times"))))))
    (let [exit-code (apply main args)]
      (System/exit exit-code))))

;;;; Scratch

(comment
  )
