(ns starmen.mastodon
  (:require
   [cheshire.core :as json]
   [org.httpkit.client :as http]
   [ring.util.codec :refer [url-encode]])
  (:gen-class))


(def mastodon-api-token (System/getenv "MASTODON_API_TOKEN"))

(defn post-media!
  [file]
  @(http/request
    {:url "https://botsin.space/api/v1/media"
     :method :post
     :headers {"Authorization" (str "Bearer " mastodon-api-token)
               "Content-Type" "multipart/form-data; boundary=--------------------------multipartboundary"}
     :multipart [{:name "description" :content "satellite position"}
                 {:name "file" :content file :filename "position.png"}]}))

(defn toot-media-status!
  "Uploads a file to Google Drive"
  [file status]
  (let [media-res (post-media! file)
        media-body (json/parse-string (:body media-res) true)
        media-id (:id media-body)]
    (http/request
      {:url "https://botsin.space/api/v1/statuses"
       :method :post
       :headers {"Authorization" (str "Bearer " mastodon-api-token)}
       :query-params {"visibility" "public"
                      "status" status
                      "media_ids[]" media-id}})))
