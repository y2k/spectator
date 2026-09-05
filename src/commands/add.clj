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
  (if-let [text (if message (get message "text") nil)
           command (or (= "/add" text) (.startsWith text "/add "))]
    (let [{:id chat-id :type chat-type} (get message "chat")]
      (if (or (= "/add" text)
              (= "" (.trim (.slice text 4))))
        (if (= "private" chat-type)
          (prompt env chat-id)
          (Response. "OK"))
        (if-let [sender (get message "from")
                 user-id (get sender "id")]
          (if (= "private" chat-type)
            (if-let [task-channel (telegram/channel (.slice text 4))]
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
                    (fn [{:results results}]
                      (.then
                       (telegram/send-message env chat-id (if (= 0 (count results)) "Канал уже добавлен." "Канал добавлен."))
                       (fn [] (Response. "OK")))))
                   (.then
                    (telegram/send-message env chat-id "Не удалось прочитать Telegram-канал.")
                    (fn [] (Response. "OK"))))))
              (.then
               (telegram/send-message env chat-id "Поддерживается только ссылка вида https://t.me/<канал>.")
               (fn [] (Response. "OK"))))
            (Response. "OK")))))))
