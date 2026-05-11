(ns semi-dev-menu
  "Interactive menu: sync Semi-0 CentralServer + ESP-Reveiver, run server, flash, or monitor."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str])
  (:import
    [java.awt BorderLayout Dimension Font GraphicsEnvironment GridLayout]
    [java.awt.event WindowAdapter]
    [javax.swing BorderFactory JButton JFrame JOptionPane JPanel JScrollPane JTextArea SwingUtilities WindowConstants])
  (:gen-class))

(defonce ^:private log-sink (atom nil))

(defn- ui! [& args]
  "Print line for CLI, or append to GUI log when the Swing UI is open."
  (let [s (str (str/join " " (map str args)) "\n")]
    (if-let [append-fn @log-sink]
      (append-fn s)
      (do (print s) (flush)))))

(defn- ui-err! [& args]
  (binding [*out* *err*] (apply ui! args)))

(defn- with-git-suffix [url]
  (cond
    (str/blank? url) url
    (str/ends-with? url ".git") url
    :else (str url ".git")))

(def ^:private default-branch
  (or (System/getenv "SEMI0_GIT_BRANCH") "main"))

(def ^:private central-url
  (with-git-suffix
    (or (System/getenv "SEMI0_CENTRAL_URL")
        "https://github.com/Semi-0/CentralServer.git")))

(def ^:private esp-url
  (with-git-suffix
    (or (System/getenv "SEMI0_ESP_URL")
        "https://github.com/Semi-0/ESP-Reveiver.git")))

(defn- workspace-root []
  (io/file (or (System/getenv "SEMI0_WORKSPACE")
               (System/getProperty "user.dir"))))

(defn- central-dir [] (io/file (workspace-root) "CentralServer"))
(defn- esp-dir [] (io/file (workspace-root) "ESP-Reveiver"))

(defn- mac-os? []
  (str/starts-with? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(defn- this-jar-path ^String []
  "Absolute path to this uberjar when run as java -jar (nil in clojure -M dev)."
  (try
    (let [url (.. (Class/forName "semi_dev_menu") getProtectionDomain getCodeSource getLocation)
          uri (.toURI url)]
      (when (= "file" (.getScheme uri))
        (str (io/file (java.net.URLDecoder/decode (.getPath uri) "UTF-8")))))
    (catch Exception _ nil)))

(defn- uberjar-location ^String []
  (or (this-jar-path)
      (let [cp (System/getProperty "java.class.path")]
        (when (and cp (not (str/includes? cp (java.io.File/pathSeparator))))
          cp))))

(defn- ensure-console-or-relaunch! []
  "Finder / Java Launcher runs the uberjar without a TTY: nothing visible.
  When we detect packaged jar + no console, macOS opens Terminal.app with the same jar.
  Skipped for clojure -M (no uberjar) or when SEMI0_NO_TERMINAL_FIX is set (e.g. CI)."
  (when-not (System/getenv "SEMI0_NO_TERMINAL_FIX")
    (when (and (some? (uberjar-location))
               (nil? (System/console)))
      (let [jar (uberjar-location)
            sep (System/getProperty "line.separator")]
        (if (mac-os?)
          (try
            (let [cmd-file (java.io.File/createTempFile "Semi0DevMenu-" ".command")]
              (spit cmd-file (str "#!/bin/bash" sep "exec java -jar " (pr-str jar) sep))
              (.setExecutable cmd-file true false)
              (let [{:keys [exit err]} (sh/sh "open" "-a" "Terminal" (.getAbsolutePath cmd-file))]
                (if (zero? exit)
                  (do
                    (Thread/sleep 1200)
                    (System/exit 0))
                  (binding [*out* *err*]
                    (when-not (str/blank? err) (println err))
                    (println "[semi-dev-menu] open -a Terminal failed, exit" exit)))))
            (catch Exception e
              (binding [*out* *err*] (println "[semi-dev-menu]" (.getMessage e)))))
          (when-not (GraphicsEnvironment/isHeadless)
            (try
              (JOptionPane/showMessageDialog
               nil
               (str "This app needs a terminal.\n\nRun:\n  java -jar " jar)
               "Semi-0 dev menu"
               JOptionPane/INFORMATION_MESSAGE)
              (catch Exception _))))
        (binding [*out* *err*]
          (println "No terminal attached. Run:")
          (println "  java -jar" jar))
        (System/exit 1)))))

(defn- sh-seq! [dir & cmd]
  ;; Clojure 1.12+: sh takes command strings first, then option pairs (e.g. :dir).
  ;; Old :dir-first style breaks parse-args and yields "No value supplied for key: …".
  (let [cmdv (vec (map str cmd))
        {:keys [exit out err]} (apply sh/sh (concat cmdv [:dir dir]))]
    (when-not (zero? exit)
      (ui-err! "[fail]" (str/join " " cmdv) "exit" exit)
      (when-not (str/blank? err) (ui-err! (str/trim err)))
      (throw (ex-info "command failed" {:cmd cmdv :exit exit :dir (str dir)})))
    (when-not (str/blank? out) (ui! (str/trim out)))
    nil))

(defn- git-dir? [d]
  (.exists (io/file d ".git")))

(defn- bun-install-central-if-present! []
  (let [root (central-dir)
        pkg (io/file root "package.json")]
    (when (.exists pkg)
      (ui! "[CentralServer] bun install…")
      (sh-seq! root "bun" "install"))))

(defn- sync-repo!
  "Clone or hard-reset to origin/branch so local conflicting changes are discarded."
  [label url ^java.io.File dir branch]
  (ui! (str "[" label "] " (.getPath dir)))
  (cond
    (not (.exists dir))
    (do
      (ui! "  cloning…")
      (sh-seq! (.getParentFile dir) "git" "clone" "-b" branch "--" url (.getName dir)))

    (git-dir? dir)
    (do
      (ui! "  fetching + reset --hard to origin/" branch "…")
      (sh-seq! dir "git" "fetch" "origin")
      (try
        (sh-seq! dir "git" "checkout" branch)
        (catch Exception _
          (ui! "  branch" branch "missing locally, trying to track origin…")
          (sh-seq! dir "git" "checkout" "-B" branch (str "origin/" branch))))
      (sh-seq! dir "git" "reset" "--hard" (str "origin/" branch)))

    :else
    (throw (ex-info (str "Path exists and is not a git repo: " (.getPath dir))
                    {:path (.getPath dir)}))))

(defn- pull-both! [branch]
  (sync-repo! "CentralServer" central-url (central-dir) branch)
  (try (bun-install-central-if-present!)
       (catch Exception e
         (ui-err! "[CentralServer] bun install failed:" (.getMessage e))))
  (sync-repo! "ESP-Reveiver" esp-url (esp-dir) branch)
  (ui! "Done: both repos synced to origin/" branch "."))

(defn- script-path [^java.io.File dir name]
  (let [f (io/file dir name)]
    (when-not (.exists f)
      (throw (ex-info (str "Missing script: " (.getPath f)) {:script name})))
    (.getAbsolutePath f)))

(defn- exec-interactive!
  "Run a shell script with stdin/stdout/stderr inherited (for interactive tools)."
  [^java.io.File work-dir argv]
  (let [pb (ProcessBuilder. ^java.util.List (map str argv))]
    (.directory pb work-dir)
    (.inheritIO pb)
    (let [p (.start pb)
          code (.waitFor p)]
      (when-not (zero? code)
        (ui! "Process exited with code" code)))))

(defn- exec-interactive-in-terminal!
  "For GUI: open macOS Terminal.app to run an interactive script (TTY)."
  [^java.io.File work-dir argv]
  (if-not (mac-os?)
    (JOptionPane/showMessageDialog
     nil
     "Interactive tools need a terminal on this OS.\nRun the CLI from a shell instead."
     "Semi-0 dev menu"
     JOptionPane/INFORMATION_MESSAGE)
    (let [sep (System/getProperty "line.separator")
          dir (pr-str (.getAbsolutePath work-dir))
          line (str "exec " (str/join " " (map pr-str argv)))
          f (java.io.File/createTempFile "semi0-exec-" ".command")]
      (spit f (str "#!/bin/bash" sep "cd " dir sep line sep))
      (.setExecutable f true false)
      (let [{:keys [exit err]} (sh/sh "open" "-a" "Terminal" (.getAbsolutePath f))]
        (if (zero? exit)
          (ui! "Opened Terminal for interactive session.")
          (ui! "[terminal] open failed, exit" exit (or err "")))))))

(defn- gui-async! [^JTextArea ta f]
  (future
    (try (f)
         (catch Exception e
           (SwingUtilities/invokeLater
            #(JOptionPane/showMessageDialog
              ta (.getMessage e) "Command failed" JOptionPane/ERROR_MESSAGE))))))

(defn- show-gui! []
  (let [branch default-branch
        ta (doto (JTextArea. 20 84)
             (.setEditable false)
             (.setFont (Font. Font/MONOSPACED Font/PLAIN 13)))
        scroll (doto (JScrollPane. ta)
                 (.setPreferredSize (Dimension. 760 380)))
        _ (reset! log-sink
                  (fn [^String s]
                    (SwingUtilities/invokeLater
                     #(let [doc (.getDocument ta)]
                        (.append ta s)
                        (.setCaretPosition ta (.getLength doc))))))
        frame (JFrame. "Semi-0 dev menu")
        mk (fn [^String label f]
             (doto (JButton. label)
               (.addActionListener
                (reify java.awt.event.ActionListener
                  (actionPerformed [_ _] (gui-async! ta f))))))
        bp (doto (JPanel.)
             (.setLayout (GridLayout. 0 1 8 8))
             (.setBorder (BorderFactory/createEmptyBorder 10 10 10 10))
             (.add (mk "Pull both repositories"
                       #(pull-both! branch)))
             (.add (mk "Pull CentralServer (+ bun if package.json)"
                       #(do (sync-repo! "CentralServer" central-url (central-dir) branch)
                            (bun-install-central-if-present!))))
             (.add (mk "Pull ESP-Reveiver only"
                       #(sync-repo! "ESP-Reveiver" esp-url (esp-dir) branch)))
             (.add (mk "Run CentralServer in Terminal…"
                       #(exec-interactive-in-terminal!
                         (central-dir)
                         ["bash" (script-path (central-dir) "run_server.sh")])))
             (.add (mk "Flash ESP32 in Terminal…"
                       #(exec-interactive-in-terminal!
                         (esp-dir)
                         ["bash" (script-path (esp-dir) "flash_interactive.sh")])))
             (.add (mk "Serial monitor in Terminal…"
                       #(exec-interactive-in-terminal!
                         (esp-dir)
                         ["bash" (script-path (esp-dir) "monitor_interactive.sh")])))
             (.add (mk "Quit"
                       #(do (reset! log-sink nil) (.dispose frame)))))]
    (doto frame
      (.setDefaultCloseOperation WindowConstants/DISPOSE_ON_CLOSE)
      (.addWindowListener
       (proxy [WindowAdapter] []
         (windowClosed [_evt] (reset! log-sink nil))))
      (.setLayout (BorderLayout.))
      (.add bp BorderLayout/NORTH)
      (.add scroll BorderLayout/CENTER)
      (.pack)
      (.setLocationRelativeTo nil)
      (.setVisible true))
    (ui! "Workspace:" (.getPath (workspace-root)))
    (ui! "Branch:" branch)
    (ui! "Git / bun output appears below. Interactive actions open Terminal.app.")
    nil))

(defn- want-gui? [args]
  (or (some #{"--gui" "-g"} args)
      (= "1" (System/getenv "SEMI0_GUI"))))

(defn- read-choice []
  (print "Choice (number, empty = cancel): ")
  (flush)
  (when-let [line (read-line)]
    (let [line (str/trim line)]
      (when-not (str/blank? line)
        (try (Long/parseLong line)
             (catch NumberFormatException _ nil))))))

(defn- read-menu-choice []
  "Like read-choice but never nil (uses ::invalid for blank/EOF) so `case` is safe."
  (or (read-choice) ::invalid))

(defn- pause []
  (println "Press Enter to continue…")
  (flush)
  (read-line)
  nil)

(defn- central-submenu [branch]
  (loop []
    (println "\n── Central Server ──")
    (println "1) Run ./run_server.sh (interactive)")
    (println "2) Pull / hard-reset CentralServer only")
    (println "0) Back")
    (case (read-menu-choice)
      ::invalid (do (println "Choose a number from the menu.") (recur))
      0 nil
      1 (try
          (exec-interactive! (central-dir) ["bash" (script-path (central-dir) "run_server.sh")])
          (catch Exception e (println (.getMessage e)))
          (finally (pause)))
      2 (try
          (sync-repo! "CentralServer" central-url (central-dir) branch)
          (bun-install-central-if-present!)
          (catch Exception e (println (.getMessage e)))
          (finally (pause)))
      (do (println "Unknown choice.") (recur)))))

(defn- esp-submenu [branch]
  (loop []
    (println "\n── ESP32 (ESP-Reveiver) ──")
    (println "1) Flash (flash_interactive.sh)")
    (println "2) Serial monitor (monitor_interactive.sh)")
    (println "3) Pull / hard-reset ESP-Reveiver only")
    (println "0) Back")
    (case (read-menu-choice)
      ::invalid (do (println "Choose a number from the menu.") (recur))
      0 nil
      1 (try
          (exec-interactive! (esp-dir) ["bash" (script-path (esp-dir) "flash_interactive.sh")])
          (catch Exception e (println (.getMessage e)))
          (finally (pause)))
      2 (try
          (exec-interactive! (esp-dir) ["bash" (script-path (esp-dir) "monitor_interactive.sh")])
          (catch Exception e (println (.getMessage e)))
          (finally (pause)))
      3 (try
          (sync-repo! "ESP-Reveiver" esp-url (esp-dir) branch)
          (catch Exception e (println (.getMessage e)))
          (finally (pause)))
      (do (println "Unknown choice.") (recur)))))

(defn- main-menu [branch]
  (loop []
    (println "\n=== Semi-0 dev menu ===")
    (println "Workspace:" (.getPath (workspace-root)))
    (println "Branch:   " branch " (override with SEMI0_GIT_BRANCH)")
    (println)
    (println (str "1) Pull both repos (clone or reset --hard to origin/" branch ")"))
    (println "2) Step in → Central Server…")
    (println "3) Step in → ESP32…")
    (println "0) Exit")
    (flush)
    (case (read-menu-choice)
      ::invalid (do (println "Choose a number from the menu.") (recur))
      0 (println "Bye.")
      1 (do
          (try
            (pull-both! branch)
            (catch Exception e (println (.getMessage e)))
            (finally (pause)))
          (recur))
      2 (do (central-submenu branch) (recur))
      3 (do (esp-submenu branch) (recur))
      (do (println "Unknown choice.") (recur)))))

(defn -main [& args]
  (if (and (want-gui? args) (not (GraphicsEnvironment/isHeadless)))
    (SwingUtilities/invokeLater #(show-gui!))
    (do
      (ensure-console-or-relaunch!)
      (let [branch default-branch]
        (println "SEMI0_WORKSPACE =" (.getPath (workspace-root)))
        (println "Repos: CentralServer, ESP-Reveiver under that directory.")
        (println "Tip: java -jar … --gui  or  SEMI0_GUI=1  for a simple Swing window.")
        (main-menu branch)))))
