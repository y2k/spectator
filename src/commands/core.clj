(ns commands.core
  (:require [commands.add :as add])
  (:require [commands.delete :as delete_cmd])
  (:require [commands.filter :as filter_cmd])
  (:require [commands.start :as start])
  (:require [commands.tasks :as tasks])
  (:require [telegram :as telegram]))

(def commands
  [start/command add/command tasks/command delete_cmd/command filter_cmd/command])

(def- literal-pattern "\"(?:[^\"\\\\]|\\\\[\"\\\\])*\"|[0-9]+")

(defn parse-command [text]
  (if-let [form (.match (.trim text)
                        (RegExp. (str "^\\(\\s*([a-z][a-z0-9-]*)((?:\\s+(?:"
                                      literal-pattern "))*)\\s*\\)$")))]
    {:name (get form 1)
     :args (map (fn [token]
                  (if (.startsWith token "\"")
                    (.replace (.slice token 1 -1) (RegExp. "\\\\([\"\\\\])" "g")
                              (fn [match character] character))
                    (Number token)))
                (or (.match (get form 2) (RegExp. literal-pattern "g")) []))}))

(defn- valid-args? [name args]
  (let [size (count args)
        first-arg (get args 0)
        task-number (and (.isSafeInteger Number first-arg) (> first-arg 0))]
    (case name
      "start" (= size 0)
      "tasks" (= size 0)
      "add" (and (= size 1) (string? first-arg) (not (= "" (.trim first-arg))))
      "delete" (and (= size 1) task-number)
      "filter" (and (> size 1) task-number
                    (.every (drop 1 args) (fn [arg] (string? arg)))
                    (or (and (= size 2) (= "off" (get args 1)))
                        (.every (drop 1 args)
                                (fn [arg]
                                  (and (re-find (re-pattern "^[+-]\\S+") arg)
                                       (not (re-find (re-pattern "\\s") arg)))))))
      false)))

(defn- usage-example [name]
  (case name
    "add" "(add \"https://t.me/<канал>\")"
    "delete" "(delete 2)"
    "filter" "(filter 2 \"+слово\" \"-слово\") или (filter 2 \"off\")"
    (str "(" name ")")))

(defn- format-error [env message command]
  (.then (telegram/send-message env (get-in message [:chat :id])
                                (if command
                                  (str "Использование: " (usage-example (:name command)))
                                  "Неверная команда. Справка: (start)"))
         (fn [] (Response. "OK"))))

(defn- help-text []
  (reduce
   (fn [text {:name name :description description}]
     (str text "\n`" (.replaceAll (usage-example name) " или " "` или `") "` - " description))
   "Доступные команды:"
   commands))

(defn handle [env message]
  (or
   (if-let [text (:text message)]
     (if (or (= text "/start") (.startsWith (.trim text) "("))
       (let [parsed (if (= text "/start") {:name "start" :args []} (parse-command text))
             name (:name parsed)
             args (:args parsed)
             command (.find commands (fn [item] (= name (:name item))))]
         (if (and command (valid-args? name args))
           (let [handler (:handler command)
                 adapted (assoc message :text
                                (str "/" name (if (= 0 (count args)) "" (str " " (.join args " ")))))]
             (if (= name "start")
               (handler env adapted {:text (help-text) :parse_mode "Markdown"})
               (handler env adapted)))
           (format-error env message command)))))
   (Response. "OK")))
