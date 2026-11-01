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
                      "TELEGRAM_WEBHOOK_SECRET" "test-secret"}}]}))

(t/before (fn [] (.listen server)))
(t/after (fn [] (.close server)))

(defn- check-selection [rule html notifications cursors]
  (.then (.fetch server "/selection"
                 {:method "POST" :body (JSON.stringify {:rule rule :html html})})
         (fn [response]
           (.then (.json response)
                  (fn [effects]
                    (assert/deepStrictEqual
                     (-> effects
                         (.filter (fn [effect] (= "fetch" (get effect "type"))))
                         (.map (fn [effect] (JSON.parse (get-in effect ["props" "body"])))))
                     (JSON.parse (JSON.stringify notifications)))
                    (assert/deepStrictEqual
                     (-> effects
                         (.filter (fn [effect] (= "d1.bind" (get effect "type"))))
                         (.map (fn [effect] (get effect "params"))))
                     cursors))))))

(.forEach ["+ALTA" "-реклама" "+ALTA -реклама"]
          (fn [rule]
            (t/test (str "configured rule rejects unavailable text: " rule)
                    (fn []
                      (check-selection
                       rule
                       "<div data-post='selection/11'><a class='tgme_widget_message_photo_wrap'></a></div><div data-post='selection/12'><div class='js-message_text'></div></div><div data-post='selection/13'><div class='js-message_text'> \n\t </div></div><div data-post='selection/14'><div class='js-message_text'>&copy;</div></div>"
                       [] [[11 7] [12 7] [13 7] [14 7]])))))

(t/test "unfiltered photo without caption is delivered"
        (fn []
          (check-selection nil "<div data-post='selection/11'><a class='tgme_widget_message_photo_wrap'></a></div>"
                           [{:chat_id "owner" :text "https://t.me/selection/11"}]
                           [[11 7]])))

(defn- poll-html [id question answer]
  (str "<div data-post='selection/" id "'><div class='tgme_widget_message_poll js-poll multiple'>"
       "<div class='tgme_widget_message_poll_question'>" question "</div>"
       "<div class='tgme_widget_message_poll_type'>Anonymous Poll</div>"
       "<a class='tgme_widget_message_poll_options'><div class='tgme_widget_message_poll_option'>"
       "<div class='tgme_widget_message_poll_option_percent'>53%</div>"
       "<div class='tgme_widget_message_poll_option_value'><div class='tgme_widget_message_poll_option_text'>"
       answer "</div><div class='tgme_widget_message_poll_option_bar'></div></div></div></a></div>"
       "<div class='tgme_widget_message_reactions'>реклама ALTA</div></div>"))

(.forEach
 [["poll question satisfies required keyword" "+alta" "У вас есть счет в ALTA Banka?" "Да, есть"
   [{:chat_id "owner" :text "https://t.me/selection/11"}]]
  ["required keyword only in poll answer is ignored" "+alta" "Где у вас счет?" "ALTA Banka" []]
  ["prohibited keyword only in poll answer is ignored" "+alta -реклама" "У вас есть счет в ALTA Banka?" "реклама"
   [{:chat_id "owner" :text "https://t.me/selection/11"}]]
  ["prohibited keyword in poll question wins" "+alta -реклама" "ALTA реклама?" "Да, есть" []]
  ["prohibited-only rule checks poll question" "-реклама" "РЕКЛАМА?" "Нет" []]]
 (fn [[name rule question answer notifications]]
   (t/test name (fn [] (check-selection rule (poll-html 11 question answer) notifications [[11 7]])))))

(.forEach
 [["formatted question preserves keyword boundaries" "+C++" "Use<br><b>c</b>++<br>today"
   [{:chat_id "owner" :text "https://t.me/selection/11"}]]
  ["poll question decodes supported entities" "+AT&T" "Use AT&amp;T?"
   [{:chat_id "owner" :text "https://t.me/selection/11"}]]
  ["whitespace entities do not bypass a prohibited-only rule" "-реклама" "&nbsp;&#32;&#x20;" []]
  ["unfiltered poll is delivered" nil "У вас есть счет в ALTA Banka?"
   [{:chat_id "owner" :text "https://t.me/selection/11"}]]
  ["poll statistics and type do not satisfy required keywords" "+53 +Anonymous" "Где у вас счет?" []]]
 (fn [[name rule question notifications]]
   (t/test name (fn [] (check-selection rule (poll-html 11 question "Да") notifications [[11 7]])))))

(t/test "selection isolates adjacent posts and skips posts at or below the cursor"
        (fn []
          (check-selection
           "+ALTA -реклама"
           (str (poll-html 9 "ALTA?" "Да")
                (poll-html 10 "ALTA?" "Да")
                "<div data-post='selection/11'><a class='tgme_widget_message_photo_wrap'></a></div>"
                (poll-html 12 "ALTA?" "реклама")
                (poll-html 13 "Другой банк?" "ALTA")
                "<div data-post='selection/14'><a class='tgme_widget_message_photo_wrap'></a><div class='js-message_text'>ALTA новости</div></div>"
                "<div data-post='selection/15'><a class='tgme_widget_message_photo_wrap'></a><div class='js-message_text'>ALTA реклама</div></div>")
           [{:chat_id "owner" :text "https://t.me/selection/12"}
            {:chat_id "owner" :text "https://t.me/selection/14"}]
           [[11 7] [12 7] [13 7] [14 7] [15 7]])))

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
/start - показать справку
/add https://t.me/<канал> - добавить канал
/tasks - показать каналы и их номера
/delete ID - удалить канал по номеру из списка
/filter ID +слово | -слово... | номер off - Настроить фильтр: + требует слово, - исключает; off отключает фильтр."})}}])))))))))

(.forEach
 ["/tasks extra" "/start extra" "/tasksx" "/unknown" "hello" "(tasks)"
  " /tasks" "/tasks@bot" "/delete\t2" nil]
 (fn [text]
   (t/test (str "worker ignores unsupported command: " text)
           (fn []
             (.then
              (.fetch server "/"
                      {:method "POST"
                       :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                       :body (JSON.stringify {:message {:text text
                                                        :from {:id "owner"}
                                                        :chat {:id "test-chat" :type "private"}}})})
              (fn [response]
                (.then (.json response)
                       (fn [body]
                         (assert/deepStrictEqual (get body "effects") [])))))))))

(t/test "worker stores a Telegram channel with its latest post cursor"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/Serbia"
                                                     :chat {:id "chat-1" :type "private"}
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
                     :url "https://t.me/s/serbia"
                     :props {}}
                    {:type "d1.prepare"
                     :sql "INSERT OR IGNORE INTO tasks (telegram_user_id, text, cursor) VALUES (?1, ?2, ?3) RETURNING id"}
                    {:type "d1.bind" :params ["user-1" "https://t.me/serbia" 9]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Канал добавлен."})}}])))))))))

(t/test "worker rejects non-Telegram tasks in a private chat"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add Buy milk"
                                                     :chat {:id "chat-1" :type "private"}
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
                                                    :text "Поддерживается только ссылка вида https://t.me/<канал>."})}}])))))))))

(t/test "worker rejects an unreadable Telegram channel"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/broken"
                                                     :chat {:id "chat-1" :type "private"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch" :url "https://t.me/s/broken" :props {}}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Не удалось прочитать Telegram-канал."})}}])))))))))

(t/test "worker ignores /add outside a private chat"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/serbia"
                                                     :chat {:id "group-1" :type "group"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual (get body "effects") [])))))))

(t/test "worker reports a duplicate lowercased channel"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/Serbia"
                                                     :chat {:id "duplicate-user" :type "private"}
                                                     :from {:id "duplicate-user"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/equal
                 (get
                  (JSON.parse (get-in body [:effects 3 :props :body]))
                  "text")
                 "Канал уже добавлен.")))))))

(t/test "task owner sets a selection rule"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/filter 2 -реклама"
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
                     :sql "UPDATE tasks SET selection_rule = ?1 WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?2 ORDER BY id LIMIT 1 OFFSET ?3) AND telegram_user_id = ?2 RETURNING id"}
                    {:type "d1.bind" :params ["-реклама" "user-1" 1]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Фильтр сохранён."})}}])))))))))

(t/test "task owner removes a selection rule"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/filter 2 off"
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
                     :sql "UPDATE tasks SET selection_rule = ?1 WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?2 ORDER BY id LIMIT 1 OFFSET ?3) AND telegram_user_id = ?2 RETURNING id"}
                    {:type "d1.bind" :params [nil "user-1" 1]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Фильтр удалён."})}}])))))))))

(t/test "worker rejects an invalid selection rule"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/filter 2 clojure"
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
                             :body (JSON.stringify
                                    {:chat_id "chat-1"
                                     :text "Использование: /filter <номер> +нужное -запрещённое или /filter <номер> off"})}}])))))))))

(t/test "worker rejects an invalid task number for a selection rule"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/filter second +clojure"
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
                             :body (JSON.stringify
                                    {:chat_id "chat-1"
                                     :text "Использование: /filter <номер> +нужное -запрещённое или /filter <номер> off"})}}])))))))))

(t/test "worker reports a missing task for a selection rule"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/filter 1 +clojure"
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
                     :sql "UPDATE tasks SET selection_rule = ?1 WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?2 ORDER BY id LIMIT 1 OFFSET ?3) AND telegram_user_id = ?2 RETURNING id"}
                    {:type "d1.bind" :params ["+clojure" "empty-user" 0]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задача не найдена."})}}])))))))))

(t/test "scheduled handler advances ordered posts and isolates failures"
        (fn []
          (.clearLogs server)
          (.then
           (.scheduled (.getWorker server) {:cron "* * * * *"})
           (fn []
             (let [logs (.getLogs server)
                   {:message message} (.find logs
                                             (fn [{:level level :message message}]
                                               (and (= "log" level)
                                                    (.includes message "scheduled_effects"))))
                   {:effects effects} (JSON.parse message)]
               (assert/deepStrictEqual
                effects
                (JSON.parse
                 (JSON.stringify
                  [{:type "d1.prepare" :sql "SELECT id, telegram_user_id, text, cursor, selection_rule FROM tasks ORDER BY id"}
                   {:type "fetch" :url "https://t.me/s/serbia?after=10" :props {}}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-1" :text "https://t.me/serbia/11"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [11 1]}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-1" :text "https://t.me/serbia/13"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [13 1]}
                   {:type "fetch" :url "https://t.me/s/broken?after=20" :props {}}
                   {:type "fetch" :url "https://t.me/s/cursorfail?after=30" :props {}}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-3" :text "https://t.me/cursorfail/31"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [31 3]}
                   {:type "fetch" :url "https://t.me/s/quiet?after=40" :props {}}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-4" :text "https://t.me/quiet/41"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [41 4]}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [42 4]}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-4" :text "https://t.me/quiet/43"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [43 4]}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [44 4]}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [45 4]}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-4" :text "https://t.me/quiet/46"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [46 4]}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-4" :text "https://t.me/quiet/47"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [47 4]}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-4" :text "https://t.me/quiet/48"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [48 4]}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [49 4]}
                   {:type "fetch" :url "https://t.me/s/prohibited?after=50" :props {}}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-5" :text "https://t.me/prohibited/51"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [51 5]}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [52 5]}
                   {:type "fetch" :url "https://t.me/s/required?after=60" :props {}}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-6" :text "https://t.me/required/61"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [61 6]}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [62 6]}])))
               (assert/equal
                (count (.filter logs (fn [{:level level}] (= "error" level))))
                3))))))

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
                     :sql "SELECT text, selection_rule FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"}
                    {:type "d1.bind" :params ["user-1"]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "1. first (+C++ -реклама)\n2. second"})}}])))))))))

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
                                                     :chat {:id "chat-1" :type "private"}
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
                                                    :text "Укажите ссылку: /add https://t.me/<канал>"})}}])))))))))

(t/test "worker does not store whitespace-only /add"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add   "
                                                     :chat {:id "chat-1" :type "private"}
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
                                                    :text "Укажите ссылку: /add https://t.me/<канал>"})}}])))))))))

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
                     :sql "SELECT text, selection_rule FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"}
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
