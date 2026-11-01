(ns commands.start
  (:require [telegram :as telegram]))

(defn handle [env message help-text]
  (if (= "/start" (:text message))
    (.then
     (telegram/send-message
      env
      (get-in message [:chat :id])
      help-text)
     (fn [] (Response. "OK")))
    nil))

(def command
  {:name "start"
   :args ""
   :description "показать справку"
   :handler handle})
