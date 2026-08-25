(ns main-test
  (:require ["node:assert/strict" :as assert])
  (:require ["node:test" :as t])
  (:require ["wrangler" :as wrangler]))

(def server
  (wrangler/createTestHarness
   {"workers" [{"configPath" "wrangler.toml"}]}))

(t/before (fn [] (.listen server)))
(t/after (fn [] (.close server)))

(t/test "worker returns OK"
        (fn []
          (.then (.fetch server "/")
                 (fn [response]
                   (assert/strictEqual response.status 200)
                   (.then (.text response)
                          (fn [body]
                            (assert/strictEqual body "OK")))))))
