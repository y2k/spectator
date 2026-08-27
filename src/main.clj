(ns main
  (:require ["node:async_hooks" :as async_hooks]))

(def- fetch-fx (async_hooks/AsyncLocalStorage.))

(defn with-fetch [fetch f]
  (.run fetch-fx fetch f))

(defn- fetch! [url options]
  ((.getStore fetch-fx) url options))

(defn handle-fetch [request env]
  (if (= "POST" (get request "method"))
    (let [secret (:TELEGRAM_WEBHOOK_SECRET env)]
      (if (and secret
               (= secret (.get (get request "headers") "X-Telegram-Bot-Api-Secret-Token")))
        (.then
         (.json request)
         (fn [update]
           (let [message (get update "message")]
              ;; ponytail: only /start is supported; add dispatch when a second command is needed.
             (if (and message (= "/start" (get message "text")))
               (.then
                (fetch!
                 (str "https://api.telegram.org/bot" (get env "TELEGRAM_BOT_TOKEN") "/sendMessage")
                 {:method "POST"
                  :headers {"content-type" "application/json"}
                  :body (JSON.stringify {:chat_id (get (get message "chat") "id")
                                         :text "OK"})})
                (fn [] (Response. "OK")))
               (Response. "OK")))))
        (Response. "Unauthorized" {:status 401})))
    (Response. "OK")))

(export-default
 {:fetch (fn [request env ctx]
           (with-fetch
             (fn [url options] (globalThis.fetch url options))
             (fn [] (handle-fetch request env))))})
