(ns commands.tasks
  (:require [db :as db])
  (:require [telegram :as telegram]))

(defn handle [env message]
  (if-let [text (if message (get message "text") nil)
           sender (get message "from")
           user-id (get sender "id")
           command (= "/tasks" text)]
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
          (fn [] (Response. "OK"))))))))
