(ns main
  (:require ["node:async_hooks" :as async_hooks]))

(def fetch-fx (async_hooks/AsyncLocalStorage.))

(defn fetch! [url options]
  ((.getStore fetch-fx) url options))

(defn handle-fetch [request env ctx]
  (.run
   fetch-fx
   (fn [url options]
     (.waitUntil ctx (globalThis.fetch url options)))
   (fn []
     (fetch!
      (str "https://api.telegram.org/bot" (get env "TELEGRAM_BOT_TOKEN") "/sendMessage")
      {:method "POST"
       :headers {"content-type" "application/json"}
       :body (JSON.stringify {:chat_id (get env "TELEGRAM_CHAT_ID")
                              :text "telegram bot started"})})
     (if (= "POST" (get request "method"))
       (let [secret (get env "TELEGRAM_WEBHOOK_SECRET")]
         (if (and secret
                  (= secret (.get (get request "headers") "X-Telegram-Bot-Api-Secret-Token")))
           (do
             (.waitUntil
              ctx
              (.then
               (.json request)
               (fn [update]
                 (let [message (get update "message")]
                   ;; ponytail: only /start is supported; add dispatch when a second command is needed.
                   (if (and message (= "/start" (get message "text")))
                     (fetch!
                      (str "https://api.telegram.org/bot" (get env "TELEGRAM_BOT_TOKEN") "/sendMessage")
                      {:method "POST"
                       :headers {"content-type" "application/json"}
                       :body (JSON.stringify {:chat_id (get (get message "chat") "id")
                                               :text "OK"})})
                     nil)))))
             (Response. "OK"))
           (Response. "Unauthorized" {:status 401})))
       (Response. "OK")))))

(export-default
 {:fetch (fn [request env ctx]
           (handle-fetch request env ctx))})
