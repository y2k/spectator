(ns main-test
  (:require ["node:assert/strict" :as assert])
  (:require ["node:test" :as t])
  (:require ["wrangler" :as wrangler]))

(def- server
  (wrangler/createTestHarness
   {:root "."
    :workers [{:config {:name "spectator-effect-test"
                        :main "bin/test/effect_test_entrypoint.js"
                        :compatibility_date "2026-08-24"}
               :vars {"TELEGRAM_BOT_TOKEN" "test-token"
                      "TELEGRAM_CHAT_ID" "test-chat"
                      "TELEGRAM_WEBHOOK_SECRET" "test-secret"}}]}))

(t/before (fn [] (.listen server)))
(t/after (fn [] (.close server)))

(t/test "worker sends help for /start"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/start"
                                                     :chat {:id "test-chat"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "test-chat"
                                                    :text "Доступные команды:
/add <текст> - добавить задачу
/tasks - показать задачи
/delete <номер> - удалить задачу"})}}])))))))))

(t/test "worker stores /add task for the Telegram user"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add Buy milk"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "INSERT INTO tasks (telegram_user_id, text) VALUES (?1, ?2)"}
                    {:type "d1.bind" :params ["user-1" "Buy milk"]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задача добавлена."})}}])))))))))

(t/test "scheduled handler logs task owners"
        (fn []
          (.clearLogs server)
          (.then
           (.scheduled (.getWorker server) {:cron "* * * * *"})
           (fn []
             (assert/equal
              (get
               (.find (.getLogs server)
                      (fn [log] (= "log" (get log "level"))))
               "message")
              (JSON.stringify {:event "scheduled_users"
                               :users ["user-1" "user-2"]
                               :count 2}))))))

(t/test "worker lists tasks for the Telegram user"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/tasks"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "SELECT text FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"}
                    {:type "d1.bind" :params ["user-1"]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "1. first\n2. second"})}}])))))))))

(t/test "worker deletes the numbered task for the Telegram user"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete 2"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"}
                    {:type "d1.bind" :params ["user-1" 1]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задача удалена."})}}])))))))))

(t/test "worker reports a missing numbered task"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete 1"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "empty-user"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"}
                    {:type "d1.bind" :params ["empty-user" 0]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задача не найдена."})}}])))))))))

(t/test "worker explains how to use /delete without a task number"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Использование: /delete <номер>"})}}])))))))))

(t/test "worker explains how to use /delete with an invalid task number"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete second"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Использование: /delete <номер>"})}}])))))))))

(t/test "worker explains how to use /delete with zero"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete 0"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Использование: /delete <номер>"})}}])))))))))

(t/test "worker explains how to use empty /add"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Укажите текст задачи: /add <текст>"})}}])))))))))

(t/test "worker does not store whitespace-only /add"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add   "
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Укажите текст задачи: /add <текст>"})}}])))))))))

(t/test "worker reports no tasks for an empty list"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/tasks"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "empty-user"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "SELECT text FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"}
                    {:type "d1.bind" :params ["empty-user"]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задач пока нет."})}}])))))))))

(t/test "worker ignores non-text Telegram updates"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:chat {:id "chat-1"}
                                                     :from {:id "user-1"}
                                                     :sticker {:emoji "!"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual (get body "effects") [])))))))

(t/test "worker ignores task commands without a Telegram user ID"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add Buy milk"
                                                     :chat {:id "chat-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual (get body "effects") [])))))))
