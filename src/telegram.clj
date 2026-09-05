(ns telegram
  (:require ["node:async_hooks" :as async_hooks]))

(def- fetch-fx (async_hooks/AsyncLocalStorage.))

(defn with-fetch [fetch f]
  (.run fetch-fx fetch f))

(defn- fetch! [url options]
  ((.getStore fetch-fx) url options))

(defn channel [text]
  (if-let [canonical (if text (.toLowerCase (.trim text)) "")
           match (.match canonical (RegExp. "^https://t[.]me/([a-z0-9_]+)/?$"))]
    {:text canonical :username (get match 1)}))

(defn preview-url [channel]
  (str "https://t.me/s/" (get channel "username")))

;; ponytail: Telegram does not document preview markup; replace this regex only when it breaks.
(defn- post-ids [html]
  (if-let [matches (.match html (RegExp. "data-post=[^/]+/[0-9]+" "g"))]
    (.map matches
          (fn [match]
            (Number (.slice match (+ 1 (.lastIndexOf match "/"))))))
    (Array.)))

(defn fetch-post-ids [url require-post]
  (.then
   (fetch! url {})
   (fn [response]
     (if (and response (get response "ok"))
       (.then
        (.text response)
        (fn [html]
          (let [ids (post-ids html)]
            (if (or (> (count ids) 0) (= false require-post))
              ids
              (.reject Promise (Error. "Telegram preview has no posts"))))))
       (.reject Promise (Error. "Telegram preview request failed"))))))

(defn send-message [env chat-id text]
  (.then
   (fetch!
    (str "https://api.telegram.org/bot" (get env "TELEGRAM_BOT_TOKEN") "/sendMessage")
    {:method "POST"
     :headers {"content-type" "application/json"}
     :body (JSON.stringify {:chat_id chat-id :text text})})
   (fn [response]
     (if (and response (get response "ok"))
       (.then
        (.json response)
        (fn [result]
          (if (get result "ok")
            result
            (.reject Promise (Error. "Telegram sendMessage failed")))))
       (.reject Promise (Error. "Telegram sendMessage request failed"))))))
