(ns commands.add
  (:require [db :as db])
  (:require [telegram :as telegram]))

(defn- latest-id [ids]
  (reduce (fn [latest id] (if (> id latest) id latest)) 0 ids))

(defn- prompt [env chat-id]
  (.then
   (telegram/send-message env chat-id "Укажите ссылку: /add https://t.me/<канал>")
   (fn [] (Response. "OK"))))

(defn handle [env message]
  (let [text (if message (get message "text") nil)]
    (if (or (= "/add" text)
            (and text (.startsWith text "/add ")))
      (let [chat (get message "chat")
            chat-id (get chat "id")
            sender (get message "from")
            user-id (if sender (get sender "id") nil)]
        (if (or (= "/add" text)
                (= "" (.trim (.slice text 4))))
          (if (= "private" (get chat "type"))
            (prompt env chat-id)
            (Response. "OK"))
          (if user-id
            (if (= "private" (get chat "type"))
              (let [task-channel (telegram/channel (.slice text 4))]
                (if task-channel
                  (.then
                   (.catch
                    (telegram/fetch-post-ids (telegram/preview-url task-channel) true)
                    (fn [error]
                      (globalThis.console.error
                       (JSON.stringify {:event "task_add_error"
                                        :telegram_user_id user-id
                                        :error (str error)}))))
                   (fn [ids]
                     (if ids
                       (.then
                        (db/all
                         "INSERT OR IGNORE INTO tasks (telegram_user_id, text, cursor) VALUES (?1, ?2, ?3) RETURNING id"
                         [user-id (get task-channel "text") (latest-id ids)])
                        (fn [result]
                          (.then
                           (telegram/send-message env chat-id (if (= 0 (count (get result "results"))) "Канал уже добавлен." "Канал добавлен."))
                           (fn [] (Response. "OK")))))
                       (.then
                        (telegram/send-message env chat-id "Не удалось прочитать Telegram-канал.")
                        (fn [] (Response. "OK"))))))
                  (.then
                   (telegram/send-message env chat-id "Поддерживается только ссылка вида https://t.me/<канал>.")
                   (fn [] (Response. "OK")))))
              (Response. "OK"))
            nil)))
      nil)))
