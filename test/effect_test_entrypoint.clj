(ns effect-test-entrypoint
  (:require [main :as main]))

(export-default
 {:fetch (fn [request env ctx]
           (let [effects (Array.)]
             (main/with_fetch
              (fn [url props]
                (.push effects {:type "fetch" :url url :props props}))
              (fn []
                (let [response (main/handle_fetch request env ctx)]
                  (.then
                   (.text response)
                   (fn [body]
                     (Response.
                      (JSON.stringify {:effects effects
                                       :response body})
                      {:headers {"content-type" "application/json"}}))))))))})
