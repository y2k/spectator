(ns main
  (:require ["node:async_hooks" :as async_hooks])
  (:require [db :as db]))

(def- fetch-fx (async_hooks/AsyncLocalStorage.))

(defn with-fetch [fetch f]
  (.run fetch-fx fetch f))

(defn- fetch! [url options]
  ((.getStore fetch-fx) url options))

(defn- channel [text]
  (let [canonical (if text (.toLowerCase (.trim text)) "")
        match (.match canonical (RegExp. "^https://t[.]me/([a-z0-9_]+)/?$"))]
    (if match {:text canonical :username (get match 1)} nil)))

(defn- preview-url [channel]
  (str "https://t.me/s/" (get channel "username")))

;; ponytail: Telegram does not document preview markup; replace this regex only when it breaks.
(defn- post-ids [html]
  (let [matches (.match html (RegExp. "data-post=[^/]+/[0-9]+" "g"))]
    (if matches
      (.map matches
            (fn [match]
              (Number (.slice match (+ 1 (.lastIndexOf match "/"))))))
      (Array.))))

(defn- latest-id [ids]
  (reduce (fn [latest id] (if (> id latest) id latest)) 0 ids))

(defn- fetch-post-ids [url require-post]
  (.then
   (fetch! url {})
   (fn [response]
     (if (and response (get response "ok"))
       (.then
        (.text response)
        (fn [html]
          (let [ids (post-ids html)]
            (if (or (> (count ids) 0) (= false require-post))
              ids
              (.reject Promise (Error. "Telegram preview has no posts"))))))
       (.reject Promise (Error. "Telegram preview request failed"))))))

(defn- send-message [env chat-id text]
  (.then
   (fetch!
    (str "https://api.telegram.org/bot" (get env "TELEGRAM_BOT_TOKEN") "/sendMessage")
    {:method "POST"
     :headers {"content-type" "application/json"}
     :body (JSON.stringify {:chat_id chat-id :text text})})
   (fn [response]
     (if (and response (get response "ok"))
       (.then
        (.json response)
        (fn [result]
          (if (get result "ok")
            result
            (.reject Promise (Error. "Telegram sendMessage failed")))))
       (.reject Promise (Error. "Telegram sendMessage request failed"))))))

(defn- log-task-error [task stage error]
  (globalThis.console.error
   (JSON.stringify {:event "task_error"
                    :task_id (get task "id")
                    :stage stage
                    :error (str error)})))

(defn- update-cursor [task post-id]
  (db/run
   "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"
   [post-id (get task "id")]))

(defn- notify-post [env task post-id]
  (.then
   (.catch
    (send-message
     env
     (get task "telegram_user_id")
     (str (get task "text") (if (.endsWith (get task "text") "/") "" "/") post-id))
    (fn [error]
      (log-task-error task "send" error)))
   (fn [] (update-cursor task post-id))))

(defn- process-task [env task]
  (let [task-channel (channel (get task "text"))]
    (.then
     (fetch-post-ids (str (preview-url task-channel) "?after=" (get task "cursor")) false)
     (fn [ids]
       (let [new-ids (.sort
                      (.filter ids (fn [id] (> id (get task "cursor"))))
                      (fn [left right] (- left right)))]
         (reduce
          (fn [promise post-id]
            (.then promise (fn [] (notify-post env task post-id))))
          (.resolve Promise nil)
          new-ids))))))

;; ponytail: tasks run sequentially; batch them only when Worker limits are measured.
(defn handle-scheduled [env]
  (.then
   (db/all
    "SELECT id, telegram_user_id, text, cursor FROM tasks ORDER BY id"
    [])
   (fn [result]
     (reduce
      (fn [promise task]
        (.then
         promise
         (fn []
           (.catch
            (process-task env task)
            (fn [error] (log-task-error task "process" error))))))
      (.resolve Promise nil)
      (get result "results")))))

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
               (.then
                (send-message env chat-id "Доступные команды:\n/add https://t.me/<канал> - добавить канал\n/tasks - показать каналы\n/delete <номер> - удалить канал")
                (fn [] (Response. "OK")))
               (if (= "/delete" text)
                 (.then
                  (send-message env chat-id "Использование: /delete <номер>")
                  (fn [] (Response. "OK")))
                 (if (or (= "/add" text)
                         (and text (.startsWith text "/add ")
                              (= "" (.trim (.slice text 4)))))
                   (if (= "private" (get chat "type"))
                     (.then
                      (send-message env chat-id "Укажите ссылку: /add https://t.me/<канал>")
                      (fn [] (Response. "OK")))
                     (Response. "OK"))
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
                           (send-message env chat-id (if (= "" tasks) "Задач пока нет." tasks))
                           (fn [] (Response. "OK"))))))
                     (if (and user-id text (.startsWith text "/delete "))
                       (let [task-number (Number (.trim (.slice text 8)))]
                         (if (and (.isInteger Number task-number)
                                  (> task-number 0))
                           (.then
                            (db/all
                             "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"
                             [user-id (- task-number 1)])
                            (fn [result]
                              (.then
                               (send-message env chat-id (if (= 0 (count (get result "results"))) "Задача не найдена." "Задача удалена."))
                               (fn [] (Response. "OK")))))
                           (.then
                            (send-message env chat-id "Использование: /delete <номер>")
                            (fn [] (Response. "OK")))))
                       (if (and user-id text (.startsWith text "/add "))
                         (if (= "private" (get chat "type"))
                           (let [task-channel (channel (.slice text 4))]
                             (if task-channel
                               (.then
                                (.catch
                                 (fetch-post-ids (preview-url task-channel) true)
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
                                        (send-message env chat-id (if (= 0 (count (get result "results"))) "Канал уже добавлен." "Канал добавлен."))
                                        (fn [] (Response. "OK")))))
                                    (.then
                                     (send-message env chat-id "Не удалось прочитать Telegram-канал.")
                                     (fn [] (Response. "OK"))))))
                               (.then
                                (send-message env chat-id "Поддерживается только ссылка вида https://t.me/<канал>.")
                                (fn [] (Response. "OK")))))
                           (Response. "OK"))
                         (Response. "OK"))))))))))
        (Response. "Unauthorized" {:status 401})))
    (Response. "OK")))

(export-default
 {:fetch (fn [request env ctx]
           (with-fetch
             (fn [url options] (globalThis.fetch url options))
             (fn []
               (db/with-db
                 (get env "TASKS")
                 (fn [] (handle-fetch request env))))))
  :scheduled (fn [controller env ctx]
               (with-fetch
                 (fn [url options] (globalThis.fetch url options))
                 (fn []
                   (db/with-db
                     (get env "TASKS")
                     (fn [] (handle-scheduled env))))))})
