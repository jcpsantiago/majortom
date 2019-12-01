(ns starmen.core
  (:require
   [cheshire.core :as json]
   [clojure.core.async :refer [thread]]
   [clojure.string :refer [upper-case]]
   [compojure.core :refer [defroutes GET POST]]
   [compojure.route :as route]
   [java-time :as jt]
   [org.httpkit.server :as server]
   [org.httpkit.client :as http]
   [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
   [starmen.utils :as utils]
   [starmen.landingpage :as landing]
   [taoensso.timbre :as timbre]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql])
  (:gen-class))

(def maps-api-key (System/getenv "GOOGLE_MAPS_API_KEY"))
(def openweather-api-key (System/getenv "OPENWEATHER_API_KEY"))
(def port (Integer/parseInt (or (System/getenv "PORT") "3000")))
(def mapbox-api-key (System/getenv "MAPBOX_ACCESS_TOKEN"))
(def satellite-image-url (System/getenv "STARMEN_SATELLITE_IMAGE_URL"))
(def n2yo-api-key (System/getenv "N2YO_API_KEY"))
(def postgresql-host (let [heroku-url (System/getenv "DATABASE_URL")]
                       (if (nil? heroku-url)
                         {:host "0.0.0.0"
                          :user "postgres"
                          :dbtype "postgresql"}
                         (utils/create-map-from-uri heroku-url))))
(def slack-client-id (System/getenv "STARMEN_CLIENT_ID"))
(def slack-client-secret (System/getenv "STARMEN_CLIENT_SECRET"))
(def slack-oauth-url-state (System/getenv "STARMEN_SLACK_OAUTH_STATE"))

(def db postgresql-host)
(def ds (jdbc/get-datasource db))

(defn migrated?
  [table]
  (-> (sql/query ds
                 [(str "select * from information_schema.tables "
                       "where table_name='" table "'")])
      count pos?))

(defn migrate []
  (when (not (migrated? "requests"))
    (timbre/info "Creating requests table...")
    (jdbc/execute! ds ["
      create table requests (
        id varchar(255) primary key,
        user_id varchar(255),
        team_id varchar(255),
        channel_id varchar(255),
        channel_name varchar(255),
        team_domain varchar(255),
        created_at timestamp default current_timestamp
      )"]))
  (when (not (migrated? "connected_teams"))
    (timbre/info "Creating connected_teams table...")
    (jdbc/execute! ds ["
        create table connected_teams (
          id serial primary key,
          slack_team_id varchar(255),
          team_name varchar(255),
          registering_user varchar (255),
          scope varchar(255),
          access_token varchar(255),
          created_at timestamp default current_timestamp
        )"]))
  (timbre/info "Database ready!"))

(defn post-to-slack!
  "Post message to Slack"
  [payload url]
  (-> @(http/post
        url
        {:body (json/generate-string payload)
         :content-type :json})
      (utils/log-http-status "Slack" "POST")))

(defn get-api-data!
  "GET an API and pull only the body"
  [url]
  (json/parse-string
   (:body @(http/get url))
   true))

(defn get-weather!
  "Get current weather condition for a city"
  [latitude longitude]
  (timbre/info "Checking the weather...")
  (-> (str "http://api.openweathermap.org/data/2.5/weather?lat="
           latitude "&lon=" longitude
           "&appid=" openweather-api-key)
      get-api-data!))

(defn get-weather-description
  "Get description for current weather from openweather API response"
  [weather-response]
  (-> weather-response
      :weather
      first
      :description))

(defn create-gmaps-str
  "Creates the url needed for geocoding an address with google maps API"
  [latitude longitude]
  (str "https://maps.googleapis.com/maps/api/geocode/json?latlng="
       latitude "," longitude "&key=" maps-api-key))

(defn create-satellite-str
  "Creates a string with information about the flight"
  [address altitude speed]
  (str "This is Major Tom to Ground Control: we're"
       " currently moving at " (int speed) " km/h"
       (if (nil? address)
         ""
         (str " over " address))
       " at an altitude of " (int altitude) " kilometers."))

(defn create-mapbox-str
  "Creates mapbox string for image with map and airplane"
  [image-url longitude latitude night-mode zoom]
  (str "https://api.mapbox.com/styles/v1/mapbox/"
       "satellite-v9"
       "/static/" "url-" image-url
       "(" longitude "," latitude ")/"
       longitude "," latitude
       "," zoom ",0,0/500x300?attribution=false&logo=false&access_token="
       mapbox-api-key))

(defn create-payload
  "Create a map to be converted into JSON for POST"
  [position satellite]
  (let [latitude (:latitude position)
        longitude (:longitude position)
        altitude (:altitude position)
        speed (:velocity position)
        weather-response (get-weather! latitude longitude)
        night-mode (utils/night? weather-response)
        gmaps-response (-> (create-gmaps-str latitude longitude)
                           get-api-data!
                           :results
                           first)
        address (:formatted_address gmaps-response)
        zoom (if (or (nil? address) (re-find #"Ocean" address))
               2
               10)]
    (timbre/info (str "Creating payload for " satellite))
    {:blocks [{:type "section"
               :text {:type "plain_text"
                      :text (create-satellite-str address altitude speed)}}
              {:type "image"
               :title {:type "plain_text"
                       :text "Satellite location"
                       :emoji true}
               :image_url (create-mapbox-str satellite-image-url
                                             longitude
                                             latitude
                                             night-mode
                                             zoom)
               :alt_text "flight overview"}]}))

(defn unix->datetime
  [unixtimestamp]
  (println unixtimestamp)
  (println "")
  (jt/format "HH:mm" (-> (* unixtimestamp 1000)
                         (jt/instant)
                         (jt/zoned-date-time "CET"))))

(defn pass-schedule-payload
  [n2yo-response satellite]
  (let [start-instant (unix->datetime (:startUTC n2yo-response))
        max-instant (unix->datetime (:maxUTC n2yo-response))
        end-instant (unix->datetime (:endUTC n2yo-response))
        start-compass (:startAzCompass n2yo-response)
        max-compass (:maxAzCompass n2yo-response)
        end-compass (:endAzCompass n2yo-response)
        date-response (jt/format "yyyy-MM-dd" (-> (* (:startUTC n2yo-response) 1000)
                                                  (jt/instant)
                                                  (jt/zoned-date-time "CET")))]
    {:status 200
     :blocks [{:type "section",
               :text {:type "mrkdwn", :text "Next visual contact is"}}
              {:type "section",
               :text {:type "mrkdwn",
                      :text (str "*" date-response " between " start-instant " and "
                                 end-instant "*\nFrom " start-compass ", peaking in "
                                 max-compass " and disappearing in " end-compass)}}
              {:type "section",
               :text {:type "mrkdwn",
                      :text "*<https://www.n2yo.com/passes/?s=25544|Show more times>*"}}]}))

(defn get-satellite!
  "Get the current position of a satellite"
  [satellite]
  (-> (str "https://api.wheretheiss.at/v1/satellites/25544/positions?timestamps="
           (quot (System/currentTimeMillis) 1000))
      get-api-data!
      first))

(defn print-and-pass
  [x]
  (println x)
  x)

(defn get-passes!
  "Get predictions for visible passes for a satellite"
  [satellite]
  (-> (str "http://www.n2yo.com/rest/v1/satellite/visualpasses/25544/52.52/13.38/0/5/350/&apiKey="
           n2yo-api-key)
      get-api-data!
      :passes))

(defn post-satellite!
  "Gets satellite position, create string and post it to Slack"
  [satellite response-url]
  (-> (get-satellite! satellite)
      (create-payload satellite)
      (post-to-slack! response-url)))

(defn post-passes!
  "Gets satellite position, create string and post it to Slack"
  [satellite response-url]
  (-> (get-passes! satellite)
      first
      (pass-schedule-payload satellite)
      (post-to-slack! response-url)))

(defn request-satellite-position
  [satellite user-id]
  {:status 200
   :blocks [{:type "section"
             :text {:type "mrkdwn"
                    :text (str "This is `"
                               "` to user " user-id
                               " say again!")}}
            {:type "actions"
             ;;FIXME should automatically get this from bouncing-boxes
             :elements [{:type "button"
                         :text {:type "plain_text"
                                :text "East"
                                :emoji false}
                         :value "e"
                         :action_id "txl-east"}
                        {:type "button",
                         :text {:type "plain_text"
                                :text "West"
                                :emoji false},
                         :value "w"
                         :action_id "txl-west"}]}]})

;; routes and handlers
(defn simple-body-page
  "Simple page for healthchecks"
  [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "Yep, it's one of those empty pages..."})

(defn satellite-position
  "Return a satellite's current position"
  [user-id satellite response-url]
  (thread (post-satellite! satellite response-url))
  (timbre/info "Replying immediately to slack")
  {:status 200
   :body (str "Ground control please standby...")})

(defn satellite-passes
  "Return a satellite's current position"
  [user-id satellite response-url]
  (thread (post-passes! satellite response-url))
  (timbre/info "Replying immediately to slack")
  {:status 200
   :body (str "Ground control please standby...")})

(defn insert-slack-token!
  [access-token-response connection]
  (sql/insert! connection :connected_teams {:slack_team_id (:team_id access-token-response)
                                            :team_name (:team_name access-token-response)
                                            :registering_user (:user_id access-token-response)
                                            :scope (:scope access-token-response)
                                            :access_token (:access_token access-token-response)})

  (timbre/info (str "Done! Team " (:team_name access-token-response)
                    " is connected!")))

(defn slack-access-token!
  [request]
  (println request)
  (if (= (:state request) slack-oauth-url-state)
    (do
      (timbre/info "Replying to Slack OAuth and saving token to db")
      (-> @(http/post "https://slack.com/api/oauth.access"
                      {:form-params {:client_id slack-client-id
                                     :client_secret slack-client-secret
                                     :code (:code request)
                                     :state slack-oauth-url-state}})
           :body
           (json/parse-string true)
           (insert-slack-token! ds)))
    (timbre/error "OAuth state parameter didn't match!")))

(defn nil->string
  [x]
  (if (nil? x)
    ""
    x))

(defroutes app-routes
  (GET "/" [] (landing/homepage))
  (GET "/slack" req
       (let [request (:params req)]
         (timbre/info "Received OAuth approval from Slack!")
         (thread (slack-access-token! request))
         (landing/homepage)))

  (POST "/satellitegazing" req
    (let [request-id (utils/uuid)
          request (:params req)
          user-id (:user_id request)
          command (->> (:command request)
                       nil->string
                       (re-find #"[a-z]+")
                       keyword)
          command-text (:text request)
          response-url (:response_url request)]
      (timbre/info (str "Slack user " user-id
                        " is requesting " command "..."))
      (timbre/info (str request-id " saving request in database"))
      (sql/insert! ds :requests {:id request-id :user_id user-id
                                 :team_domain (:team_domain request)
                                 :team_id (:team_id request)
                                 :channel_id (:channel_id request)
                                 :channel_name (:channel_name request)})
      (case command-text
        "" (satellite-position user-id command response-url)
        "pass" (satellite-passes user-id command response-url))))

  (route/resources "/")
  (route/not-found "Error: endpoint not found!"))

(defn -main
  "This is our main entry point"
  []
  (migrate)
  (server/run-server (wrap-defaults #'app-routes api-defaults) {:port port})
  (timbre/info
   (str "Major Tom👨‍🚀 is listening for requests 🚀🛰 on port:" port "/")))
