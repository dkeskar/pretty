(ns io.aviso.exception
  "Format and present exceptions in pretty (structured, formatted) way."
  (:import [java.lang StringBuilder StackTraceElement]
           [clojure.lang Compiler])
  (:use io.aviso.ansi)
  (:require [clojure
             [pprint :as pp]
             [set :as set]
             [string :as str]]
            [io.aviso
             [columns :as c]
             [writer :as w]]))

(defn- length [^String s] (.length s))

;;; Obviously, this is making use of some internals of Clojure that
;;; could change at any time.

(def ^:private clojure->java
  (->> (Compiler/CHAR_MAP)
       set/map-invert
       (sort-by #(-> % first length))
       reverse))


(defn- match-mangled
  [^String s i]
  (->> clojure->java
       (filter (fn [[k _]] (.regionMatches s i k 0 (length k))))
       ;; Return the matching sequence and its single character replacement
       first))

(defn demangle
  "De-mangle a Java name back to a Clojure name by converting mangled sequences, such as \"_QMARK_\"
  back into simple characters."
  [^String s]
  (let [in-length (.length s)
        result (StringBuilder. in-length)]
    (loop [i 0]
      (cond
        (>= i in-length) (.toString result)
        (= \_ (.charAt s i)) (let [[match replacement] (match-mangled s i)]
                               (.append result replacement)
                               (recur (+ i (length match))))
        :else (do
                (.append result (.charAt s i))
                (recur (inc i)))))))

(defn- match-keys
  "Apply the function f to all values in the map; where the result is truthy, add the key to the result."
  [m f]
  ;; (seq m) is necessary because the source is via (bean), which returns an odd implementation of map
  (reduce (fn [result [k v]] (if (f v) (conj result k) result)) [] (seq m)))

(defn- expand-exception
  [^Throwable exception]
  (let [properties (bean exception)
        cause (:cause properties)
        nil-property-keys (match-keys properties nil?)
        throwable-property-keys (match-keys properties #(.isInstance Throwable %))
        remove' #(remove %2 %1)
        nested-exception (-> properties
                             (select-keys throwable-property-keys)
                             vals
                             (remove' nil?)
                             ;; Avoid infinite loop!
                             (remove' #(= % exception))
                             first)
        ;; Ignore basic properties of Throwable, any nil properties, and any properties
        ;; that are themselves Throwables
        discarded-keys (concat [:suppressed :message :localizedMessage :class :stackTrace]
                               nil-property-keys
                               throwable-property-keys)
        retained-properties (apply dissoc properties discarded-keys)]
    [{:exception  exception
      :class-name (-> exception .getClass .getName)
      :message    (.getMessage exception)
      :properties retained-properties}
     nested-exception]))

(defn analyze-exception
  "Converts an exception into a seq of maps representing nested exceptions. Each map
  contains:

  - :exception - the original Throwable instance
  - :class-name - the name of the Java class for the exception
  - :message - the value of the exception's message property (possibly nil)
  - :properties - a map of properties to present

  The :properties map does not include any properties that are assignable to type Throwable.

  The first property that is assignable to type Throwable (not necessarily the rootCause property)
  will be used as the nested exception (for the next map in the sequence).

  The final map in the sequence will have an additional value, :root, set to true. This is used to indicate
  which exception should present the stack trace."
  [^Throwable e]
  (loop [result []
         current e]
    (let [[expanded nested] (expand-exception current)]
      (if nested
        (recur (conj result expanded) nested)
        (conj result (assoc expanded :root true))))))

(defn- update-keys [m f]
  "Builds a map where f has been applied to each key in m."
  (into {} (map (fn [[k v]] [(f k) v]) m)))

(defn- convert-to-clojure
  [class-name method-name]
  (let [[namespace-name & raw-function-ids] (str/split class-name #"\$")
        ;; Clojure adds __1234 unique ids to the ends of things, remove those.
        function-ids (map #(str/replace % #"__\d+" "") raw-function-ids)
        ;; In a degenerate case, a protocol method could be called "invoke" or "doInvoke"; we're ignoring
        ;; that possibility here and assuming it's the IFn.invoke() or doInvoke().
        all-ids (if (#{"invoke" "doInvoke"} method-name)
                  function-ids
                  (-> function-ids vec (conj method-name)))]
    ;; The assumption is that no real namespace or function name will contain underscores (the underscores
    ;; are name-mangled dashes).
    (->>
      (cons namespace-name all-ids)
      (map demangle))))

(defn- expand-stack-trace-element
  [^StackTraceElement element]
  (let [class-name (.getClassName element)
        method-name (.getMethodName element)
        dotx (.lastIndexOf class-name ".")
        file-name (or (.getFileName element) "")
        is-clojure? (.endsWith file-name ".clj")
        names (if is-clojure? (convert-to-clojure class-name method-name) [])
        name (str/join "/" names)
        line (-> element .getLineNumber)]
    {:file         file-name
     :line         (if (pos? line) line)
     :class        class-name
     :package      (if (pos? dotx) (.substring class-name 0 dotx))
     :simple-class (if (pos? dotx)
                     (.substring class-name (inc dotx))
                     class-name)
     :method       method-name
     ;; Used to calculate column width
     :name         name
     ;; Used to present compound Clojure name with last term highlighted
     :names        names}))

(def ^:private empty-stack-trace-warning
  "Stack trace of root exception is empty; this is likely due to a JVM optimization that can be disabled with -XX:-OmitStackTraceInFastThrow.")

(defn expand-stack-trace
  "Extracts the stack trace for an exception and returns a seq of expanded element maps:
  - :file file name
  - :line line number as an integer, or nil
  - :class complete Java class name
  - :package Java package name, or nil for root package
  - :simple-class simple name of Java class (without package prefix)
  - :method Java method name
  - :name - Fully qualified Clojure name, or the empty string for non-Clojure stack frames
  - :names - Clojure name split at slashes (empty for non-Clojure stack frames)"
  [^Throwable exception]
  (let [elements (map expand-stack-trace-element (.getStackTrace exception))]
    (when (empty? elements)
      (binding [*out* *err*]
        (println empty-stack-trace-warning)
        (flush)))
    elements))

(def ^:dynamic *fonts*
  "ANSI fonts for different elements in the formatted exception report."
  {:exception     bold-red-font
   :reset         reset-font
   :message       italic-font
   :property      bold-font
   :source        green-font
   :function-name bold-yellow-font
   :clojure-frame yellow-font
   :java-frame    white-font})

(defn- preformat-stack-frame
  [element]
  (let [names (:names element)]
    (if (-> element :names empty?)
      ;; Case 1: names is empty, it's a Java frame
      (let [full-name (str (:class element) "." (:method element))]
        (assoc element
          :formatted-name (str (:java-frame *fonts*) full-name (:reset *fonts*))))
      ;; Case 2: it's a Clojure name
      (assoc element
        :formatted-name (str
                          (:clojure-frame *fonts*)
                          (->> names drop-last (str/join "/"))
                          "/"
                          (:function-name *fonts*) (last names) (:reset *fonts*))))))

(defn- write-stack-trace
  [writer exception frame-limit]
  (let [elements (->> exception expand-stack-trace (map preformat-stack-frame))
        elements' (if frame-limit (take frame-limit elements) elements)
        formatter (c/format-columns [:right (c/max-value-length elements' :formatted-name)]
                                    "  " (:source *fonts*)
                                    [:right (c/max-value-length elements' :file)]
                                    2
                                    [:right (->> elements' (map :line) (map str) c/max-length)]
                                    (:reset *fonts*))]
    (c/write-rows writer formatter [:formatted-name
                                    :file
                                    #(if (:line %) ": ")
                                    :line]
                  elements')))

(defn- format-property-value
  [value]
  (pp/write value :stream nil :length (or *print-length* 10)))

(defn write-exception
  "Writes a formatted version of the exception to the writer. By default, writes to *out* and includes
  the stack trace, with no frame limit.

  When set, the frame limit is the number of stack frames to display; if non-nil, then some of the outer-most
  stack frames may be omitted. It may be set to 0 to omit the stack trace entirely (but still display
  the exception stack).

  Properties of exceptions will be output using Clojure's pretty-printer, honoring all of the normal vars used
  to configure pretty-printing; however, if *print-length* is left as its default (nil), the print length will be set to 10.
  This is to ensure that infinite lists do not cause endless output or other exceptions.

  The *fonts* var contains ANSI definitions for how fonts are displayed; bind it to nil to remove ANSI formatting entirely."
  ([exception] (write-exception *out* exception))
  ([writer exception & {show-properties? :properties
                        frame-limit      :frame-limit
                        :or              {show-properties? true}}]
   (let [exception-font (:exception *fonts*)
         message-font (:message *fonts*)
         property-font (:property *fonts*)
         reset-font (:reset *fonts* "")
         exception-stack (->> exception
                              analyze-exception
                              (map #(assoc % :name (-> % :exception class .getName))))
         exception-formatter (c/format-columns [:right (c/max-value-length exception-stack :name)]
                                               ": "
                                               :none)]
     (doseq [e exception-stack]
       (let [^Throwable exception (-> e :exception)
             class-name (:name e)
             message (.getMessage exception)]
         (exception-formatter writer
                              (str exception-font class-name reset-font)
                              (str message-font message reset-font))
         (when show-properties?
           (let [properties (update-keys (:properties e) name)
                 prop-keys (keys properties)
                 ;; Allow for the width of the exception class name, and some extra
                 ;; indentation.
                 property-formatter (c/format-columns "    "
                                                      [:right (c/max-length prop-keys)]
                                                      ": "
                                                      :none)]
             (doseq [k (sort prop-keys)]
               (property-formatter writer
                                   (str property-font k reset-font)
                                   (-> properties (get k) format-property-value)))))
         (if (:root e)
           (write-stack-trace writer exception frame-limit)))))
   (w/flush-writer writer)))

(defn format-exception
  "Formats an exception as a multi-line string using write-exception."
  [exception & options]
  (apply w/into-string write-exception exception options))