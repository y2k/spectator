(ns commands.core
  (:require [commands.add :as add])
  (:require [commands.delete :as delete_cmd])
  (:require [commands.filter :as filter_cmd])
  (:require [commands.start :as start])
  (:require [commands.tasks :as tasks]))

(def commands
  [start/command add/command tasks/command delete_cmd/command filter_cmd/command])

(defn- help-text []
  (reduce
   (fn [text {:name name :args args :description description}]
     (str text "\n/" name (if (= "" args) "" (str " " args)) " - " description))
   "Доступные команды:"
   commands))

(defn handle [env message]
  (or
   (if-let [text (:text message)
            command (.find commands
                           (fn [{:name name}]
                             (or (= text (str "/" name))
                                 (.startsWith text (str "/" name " ")))))]
     (let [handler (:handler command)]
       (if (= "start" (:name command))
         (handler env message (help-text))
         (handler env message))))
   (Response. "OK")))
