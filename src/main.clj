(ns main
  (:require [commands.add :as add])
  (:require [commands.delete :as delete_cmd])
  (:require [commands.start :as start])
  (:require [commands.tasks :as tasks])
  (:require [db :as db])
  (:require [telegram :as telegram]))

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
    (telegram/send-message
     env
     (get task "telegram_user_id")
     (str (get task "text") (if (.endsWith (get task "text") "/") "" "/") post-id))
    (fn [error]
      (log-task-error task "send" error)))
   (fn [] (update-cursor task post-id))))

(defn- process-task [env task]
  (let [task-channel (telegram/channel (get task "text"))]
    (.then
     (telegram/fetch-post-ids (str (telegram/preview-url task-channel) "?after=" (get task "cursor")) false)
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
           (let [message (get update "message")]
             (or (start/handle env message)
                 (delete_cmd/handle env message)
                 (add/handle env message)
                 (tasks/handle env message)
                 (Response. "OK")))))
        (Response. "Unauthorized" {:status 401})))
    (Response. "OK")))

(export-default
 {:fetch (fn [request env ctx]
           (telegram/with-fetch
             (fn [url options] (globalThis.fetch url options))
             (fn []
               (db/with-db
                 (get env "TASKS")
                 (fn [] (handle-fetch request env))))))
  :scheduled (fn [controller env ctx]
               (telegram/with-fetch
                 (fn [url options] (globalThis.fetch url options))
                 (fn []
                   (db/with-db
                     (get env "TASKS")
                     (fn [] (handle-scheduled env))))))})
