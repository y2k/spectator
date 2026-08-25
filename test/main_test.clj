(ns main-test
  (:require ["node:assert/strict" :as assert])
  (:require ["node:test" :as t])
  (:require ["wrangler" :as wrangler]))

(def- server
  (wrangler/createTestHarness
   {:root "."
    :workers [{:config {:name "spectator-effect-test"
                        :main "bin/test/effect_test_entrypoint.js"
                        :compatibility_date "2026-08-24"
                        :vars {"TELEGRAM_BOT_TOKEN" "test-token"
                               "TELEGRAM_CHAT_ID" "test-chat"}}}]}))

(t/before (fn [] (.listen server)))
(t/after (fn [] (.close server)))

(t/test "worker emits Telegram fetch effect"
        (fn []
          (.then (.fetch server "/")
                 (fn [response] (.json response))
                 (fn [body]
                   (assert/deepStrictEqual
                    body
                    {:effects [{"type" "fetch"
                                "url" "https://api.telegram.org/bottest-token/sendMessage"
                                "props" {"method" "POST"
                                         "headers" {"content-type" "application/json"}
                                         "body" "{\"chat_id\":\"test-chat\",\"text\":\"telegram bot started\"}"}}]
                     :response "OK"})))))
