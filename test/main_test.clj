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

(t/test "worker sends OK for /start"
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
                                                     :text "OK"})}}])))))))))
