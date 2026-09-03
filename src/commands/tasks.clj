(ns commands.tasks
  (:require [db :as db])
  (:require [telegram :as telegram]))

(defn handle [env message]
  (let [text (if message (get message "text") nil)
        sender (if message (get message "from") nil)
        user-id (if sender (get sender "id") nil)]
    (if (and user-id (= "/tasks" text))
      (.then
       (db/all
        "SELECT text FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"
        [user-id])
       (fn [result]
         (let [tasks (.join
                      (.map (get result "results")
                            (fn [task index] (str (+ index 1) ". " (get task "text"))))
                      "\n")]
           (.then
            (telegram/send-message env (get (get message "chat") "id") (if (= "" tasks) "Задач пока нет." tasks))
            (fn [] (Response. "OK"))))))
      nil)))
