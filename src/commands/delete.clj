(ns commands.delete
  (:require [db :as db])
  (:require [telegram :as telegram]))

(defn- usage [env chat-id]
  (.then
   (telegram/send-message env chat-id "Использование: /delete <номер>")
   (fn [] (Response. "OK"))))

(defn handle [env message]
  (if-let [text (if message (get message "text") nil)]
    (if (= "/delete" text)
      (usage env (get (get message "chat") "id"))
      (if-let [sender (get message "from")
               user-id (get sender "id")
               command (.startsWith text "/delete ")]
        (let [chat-id (get (get message "chat") "id")
              task-number (Number (.trim (.slice text 8)))]
          (if (and (.isInteger Number task-number)
                   (> task-number 0))
            (.then
             (db/all
              "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"
              [user-id (- task-number 1)])
             (fn [{:results results}]
               (.then
                (telegram/send-message env chat-id (if (= 0 (count results)) "Задача не найдена." "Задача удалена."))
                (fn [] (Response. "OK")))))
            (usage env chat-id)))))))
