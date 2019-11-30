(ns starmen.utils
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [taoensso.timbre :as timbre]
   [clojure.string :as s])
  (:gen-class))

(defn uuid [] (.toString (java.util.UUID/randomUUID)))

(defn parse-db-uri
  [uri]
  (drop 1 (s/split uri #"://|:|@|/")))

(defn create-map-from-uri
  [uri]
  (let [parsed (parse-db-uri uri)]
    (into {:dbtype "postgresql"}
      (zipmap [:user :password :host :port :dbname] parsed))))

(defn parse-json
  "Parse JSON into a map with keys"
  [file]
  (json/parsed-seq (clojure.java.io/reader file)
                   true))

(defn log-http-status
  "Log API response"
  [{:keys [status body]} service type]
  (if (not (= status 200))
    (timbre/error "Failed, exception is" body)
    (timbre/info (str service " async HTTP " type " success: ") status)))

(defn night?
  "Determine if it's night or day based on openweather API response"
  [weather-response]
  (let [sys (:sys weather-response)
        localtime (:dt weather-response)
        sunrise (:sunrise sys)
        sunset (:sunset sys)]
    (or (< localtime sunrise) (> localtime sunset))))
