(ns starmen.landingpage
  (:require
   [hiccup.core :refer :all]
   [hiccup.page :refer :all])
  (:gen-class))

(def slack-oauth-url-state (System/getenv "STARMEN_SLACK_OAUTH_STATE"))
(def slack-client-id (System/getenv "STARMEN_CLIENT_ID"))

(def add-slack-btn
  [:a {:class "f6 link pl2 pr3 pv2 mv2 dib black ba br3 b--near-white bg-white"
       :href (str "https://slack.com/oauth/authorize?scope=commands,incoming-webhook&client_id="
                  slack-client-id
                  "&state=" slack-oauth-url-state)}
   [:img {:src "/img/Slack_Mark_Web.svg" :class "pr2 v-mid"
          :height 20 :width 20}]
   [:span {:class "dark-grey"} "Add to "
    [:span {:class "b"} "Slack"]]])

(defn homepage
  "Single page website"
  []
  (html5 {:lang "en"}
         [:head (include-css "https://unpkg.com/tachyons@4.10.0/css/tachyons.min.css"
                             "https://rsms.me/inter/inter.css")
          [:title "Starmen"]
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport"
                  :content "width=device-width, initial-scale=1.0"}]]
         [:body
          [:div {:class "ph0"}
           [:div {:class "min-vh-100"}
            [:div {:class "min-vh-100 w-90 w-80-ns center pt5"}
             [:div {:class "w-100 w-two-thirds-ns ph2 pr4-ns center pt6"}
              [:h2 {:class "f3 f2-ns mt0 mb0" :style "font-weight:200"}
               "Don't stop looking at the stars"]
              [:h1 {:class "f2 f1-ns mt0 lh-title"} "they'll wave back at you."]
              add-slack-btn]]]]]))
