;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.util
  "Impl, don't call directly."
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.core.async :as a])
  (:import [java.time ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Date]
           [java.util UUID]
           [java.io InputStream]
           [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.nio ByteBuffer]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.net URLEncoder]
           [java.util Base64]))

(set! *warn-on-reflection* true)

(defn format-date
  ([fmt]
   (format-date fmt (Date.)))
  ([^DateTimeFormatter fmt ^Date inst]
   (.format fmt (.atZone (.toInstant inst) ZoneOffset/UTC))))

(defn format-timestamp
  "Format a timestamp in milliseconds."
  [inst]
  (str (long (/ (.getTime ^Date inst) 1000))))

(defn parse-date
  [^DateTimeFormatter fmt ^String s]
  (Date/from (.toInstant (ZonedDateTime/parse s fmt))))

(def ^DateTimeFormatter x-amz-date-format
  "Custom formatter for x-amz-date header. Used exclusively for formatting.

  https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-signing-elements.html#date

  e.g. '20220830T123600Z'"
  (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssXXX"))

(def ^DateTimeFormatter iso8601-date-format
  "ISO 8601 formatter. Used exclusively for formatting. Fractions of seconds are always omitted.

  e.g. '2008-06-03T11:05:30Z'"
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssXXX"))

(def ^DateTimeFormatter iso8601-msecs-date-format
  "ISO 8601 formatter. Used exclusively for parsing. Fractions of seconds are optional.

  e.g. '2008-06-03T11:05:30.123Z'"
  DateTimeFormatter/ISO_OFFSET_DATE_TIME)

(def ^DateTimeFormatter rfc822-date-format
  "RFC 1123 formatter. Used for formatting and parsing.

  e.g. 'Tue, 3 Jun 2008 11:05:30 GMT'"
  DateTimeFormatter/RFC_1123_DATE_TIME)

(let [hex-chars (char-array [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \a \b \c \d \e \f])]
  (defn hex-encode
    [^bytes bytes]
    (let [bl (alength bytes)
          ca (char-array (* 2 bl))]
      (loop [i (int 0)
             c (int 0)]
        (if (< i bl)
          (let [b (long (bit-and (long (aget bytes i)) 255))]
            (aset ca c ^char (aget hex-chars (unsigned-bit-shift-right b 4)))
            (aset ca (unchecked-inc-int c) (aget hex-chars (bit-and b 15)))
            (recur (unchecked-inc-int i) (unchecked-add-int c 2)))
          (String. ca))))))

(defprotocol ByteSource
  (slurp-bytes [_] "Returns copy unless owner always treats arrays as values. Does not consume owner."))

(extend-protocol ByteSource
  ByteBuffer
  (slurp-bytes
    [buff]
    (let [buff (.duplicate buff)
          n (.remaining buff)
          bytes (byte-array n)]
      (.get buff bytes)
      bytes)))

(defn bbuff->byte-array
  "Returns a byte array with the content that is between the ByteBuffer current position and limit."
  [^ByteBuffer buff]
  (if (and (.hasArray buff)
           (= (.remaining buff) (alength (.array buff))))
    (.array buff)
    (slurp-bytes buff)))

(defn sha-256
  "Returns the sha-256 digest (bytes) of data, which can be a
  byte-array, an input-stream, or nil, in which case returns the
  sha-256 of the empty string."
  [data]
  (cond (string? data)
        (sha-256 (.getBytes ^String data "UTF-8"))
        (instance? ByteBuffer data)
        (sha-256 (bbuff->byte-array ^ByteBuffer data))
        :else
        (let [digest (MessageDigest/getInstance "SHA-256")]
          (when data
            (.update digest ^bytes data))
          (.digest digest))))

(defn hmac-sha-256
  [key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac (.getBytes data "UTF-8"))))

(defn input-stream->byte-array ^bytes [is]
  (let [os (ByteArrayOutputStream.)]
    (io/copy is os)
    (.toByteArray os)))

(defn bbuf->bytes
  [^ByteBuffer bbuf]
  (when bbuf
    (let [bytes (byte-array (.remaining bbuf))]
      (.get (.duplicate bbuf) bytes)
      bytes)))

(defn bbuf->str
  "Creates a string from java.nio.ByteBuffer object.
   The encoding is fixed to UTF-8."
  [^ByteBuffer bbuf]
  (when-let [bytes (bbuf->bytes bbuf)]
    (String. ^bytes bytes "UTF-8")))

(defn bbuf->input-stream
  [^ByteBuffer bbuf]
  (when bbuf
    (io/input-stream (bbuf->bytes bbuf))))

(defprotocol BBuffable
  (->bbuf [data]))

(extend-protocol BBuffable
  (Class/forName "[B")
  (->bbuf [bs] (ByteBuffer/wrap bs))

  String
  (->bbuf [s] (->bbuf (.getBytes s "UTF-8")))

  InputStream
  (->bbuf [is] (->bbuf (input-stream->byte-array is)))

  ByteBuffer
  (->bbuf [bb] bb)

  nil
  (->bbuf [_]))

(defn xml-read
  "Parse the UTF-8 XML string."
  [s]
  (xml/parse (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
             :namespace-aware false
             :skip-whitespace true))

(defn xml->map [element]
  (cond
    (nil? element)        nil
    (string? element)     element
    (sequential? element) (if (> (count element) 1)
                            (into {} (map xml->map) element)
                            (xml->map (first element)))
    (map? element)
    (cond
      (empty? element) {}
      (:attrs element) {(:tag element)                                (xml->map (:content element))
                        (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
      :else            {(:tag element) (xml->map  (:content element))})
    :else                 nil))

(defn xml-write
  [e]
  (if (instance? String e)
    (print e)
    (do
      (print (str "<" (name (:tag e))))
      (when (:attrs e)
        (doseq [attr (:attrs e)]
          (print (str " " (name (key attr)) "=\"" (val attr) "\""))))
      (if-not (empty? (:content e))
        (do
          (print ">")
          (doseq [c (:content e)]
            (xml-write c))
          (print (str "</" (name (:tag e)) ">")))
        (print " />")))))

(defn url-encode
  "Percent encode the string to put in a URL."
  [^String s]
  (-> s
      (URLEncoder/encode "UTF-8")
      ;; https://github.com/aws/aws-sdk-java-v2/blob/61d16e0/utils/src/main/java/software/amazon/awssdk/utils/http/SdkHttpUtils.java#L170-L176
      (.replace "+" "%20")
      (.replace "*" "%2A")))

(defn query-string
  "Create a query string from a list of parameters. Values must all be
  strings."
  [params]
  (when-not (empty? params)
    (str/join "&" (map (fn [[k v]]
                         (str (url-encode (name k))
                              "="
                              (url-encode v)))
                       params))))

(defprotocol Base64Encodable
  (base64-encode [data]))

(extend-protocol Base64Encodable
  (Class/forName "[B")
  (base64-encode [ba] (.encodeToString (Base64/getEncoder) ba))

  ByteBuffer
  (base64-encode [bb] (base64-encode (bbuff->byte-array bb)))

  java.lang.String
  (base64-encode [s] (base64-encode (.getBytes s))))

(defn base64-decode
  "base64 decode a base64-encoded string to an input stream"
  [s]
  (io/input-stream (.decode (Base64/getDecoder) ^String s)))

(defn encode-jsonvalue [data]
  (base64-encode (.getBytes ^String (json/write-str data))))

(defn parse-jsonvalue [data]
  (-> data
      base64-decode
      io/reader
      slurp
      (json/read-str :key-fn keyword)))

(defn md5
  "returns an MD5 hash of the content of bb as a byte array"
  ^bytes [^ByteBuffer bb]
  (let [ba     (bbuff->byte-array bb)
        hasher (MessageDigest/getInstance "MD5")]
    (.update hasher ^bytes ba)
    (.digest hasher)))

(defn uuid-string
  "returns a string representation of a randomly generated UUID"
  []
  (str (UUID/randomUUID)))

(defn with-defaults
  "Given a shape and data of that shape, add defaults for the
  following required keys if they are missing or bound to nil

      :idempotencyToken"
  [shape data]
  (reduce (fn [m [member-name member-spec]]
            (cond
              (not (nil? (get data member-name)))
              m

              (:idempotencyToken member-spec)
              (assoc m member-name (uuid-string))

              :else
              m))
          (or data {})
          (:members shape)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; used to fetch creds and region

(defn fetch-async
  "Internal use. Do not call directly."
  [fetch provider item]
  (a/thread
    (try
      ;; lock on the provider to avoid redundant concurrent requests
      ;; before the provider has a chance to cache the results of the
      ;; first fetch.
      (or (locking provider
            (fetch provider))
          {:cognitect.anomalies/category :cognitect.anomalies/fault
           :cognitect.anomalies/message (format "Unable to fetch %s. See log for more details." item)})
      (catch Throwable t
        {:cognitect.anomalies/category :cognitect.anomalies/fault
         ::throwable t
         :cognitect.anomalies/message (format "Unable to fetch %s." item)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Wrappers - here to support testing with-redefs since
;;;;            we can't redef static methods

(defn getenv
  ([] (System/getenv))
  ([k] (System/getenv k)))

(defn getProperty [k]
  (System/getProperty k))
