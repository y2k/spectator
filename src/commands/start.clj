(ns commands.start
  (:require [telegram :as telegram]))

(defn handle [env message]
  (if (and message (= "/start" (get message "text")))
    (.then
     (telegram/send-message
      env
      (get (get message "chat") "id")
      "Доступные команды:\n/add https://t.me/<канал> - добавить канал\n/tasks - показать каналы\n/delete <номер> - удалить канал")
     (fn [] (Response. "OK")))
    nil))
