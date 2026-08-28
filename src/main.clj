(ns main
  (:require ["node:async_hooks" :as async_hooks]))

(def- fetch-fx (async_hooks/AsyncLocalStorage.))

(defn with-fetch [fetch f]
  (.run fetch-fx fetch f))

(defn- fetch! [url options]
  ((.getStore fetch-fx) url options))

(defn- send-message [env chat-id text]
  (fetch!
   (str "https://api.telegram.org/bot" (get env "TELEGRAM_BOT_TOKEN") "/sendMessage")
   {:method "POST"
    :headers {"content-type" "application/json"}
    :body (JSON.stringify {:chat_id chat-id :text text})}))

(defn handle-fetch [request env]
  (if (= "POST" (get request "method"))
    (let [secret (:TELEGRAM_WEBHOOK_SECRET env)]
      (if (and secret
               (= secret (.get (get request "headers") "X-Telegram-Bot-Api-Secret-Token")))
        (.then
          (.json request)
          (fn [update]
            (let [message (get update "message")
                  text (if message (get message "text") nil)
                  chat (if message (get message "chat") nil)
                  sender (if message (get message "from") nil)
                  chat-id (if chat (get chat "id") nil)
                  user-id (if sender (get sender "id") nil)]
               (if (= "/start" text)
                 (.then (send-message env chat-id "OK") (fn [] (Response. "OK")))
                 (if (or (= "/add" text)
                         (and text (.startsWith text "/add ")
                              (= "" (.trim (.slice text 4)))))
                   (.then
                    (send-message env chat-id "Укажите текст задачи: /add <текст>")
                    (fn [] (Response. "OK")))
                   (if (and user-id (= "/tasks" text))
                     (.then
                      (.all
                      (.bind
                        (.prepare (get env "TASKS") "SELECT text FROM tasks WHERE telegram_user_id = ?1 ORDER BY id")
                        user-id))
                      (fn [result]
                        (let [tasks (.join
                                     (.map (get result "results")
                                           (fn [task index] (str (+ index 1) ". " (get task "text"))))
                                     "\n")]
                          (.then
                           (send-message env chat-id (if (= "" tasks) "Задач пока нет." tasks))
                           (fn [] (Response. "OK"))))))
                     (if (and user-id text (.startsWith text "/add "))
                       (.then
                        (.run
                         (.bind
                          (.prepare (get env "TASKS") "INSERT INTO tasks (telegram_user_id, text) VALUES (?1, ?2)")
                          user-id
                          (.trim (.slice text 4))))
                        (fn []
                          (.then (send-message env chat-id "Задача добавлена.") (fn [] (Response. "OK")))))
                       (Response. "OK"))))))))
         (Response. "Unauthorized" {:status 401})))
    (Response. "OK")))

(export-default
 {:fetch (fn [request env ctx]
           (with-fetch
             (fn [url options] (globalThis.fetch url options))
             (fn [] (handle-fetch request env))))})
