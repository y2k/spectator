(ns effect-test-entrypoint
  (:require [main :as main]))

(defn- database [effects]
  {:prepare (fn [sql]
              (.push effects {:type "d1.prepare" :sql sql})
              {:bind (fn [first second]
                       (.push effects {:type "d1.bind" :params (if second [first second] [first])})
                       {:run (fn [] (.resolve Promise {}))
                        :all (fn [] (.resolve Promise {:results (if (= "empty-user" first)
                                                                   []
                                                                   [{:text "first"}
                                                                    {:text "second"}])}))})})})

(export-default
 {:fetch (fn [request env ctx]
            (let [effects (Array.)]
             (main/with_fetch
              (fn [url props]
                (.push effects {:type "fetch" :url url :props props})
                (.resolve Promise nil))
               (fn []
                (.then
                  (.resolve Promise (main/handle-fetch request (Object.assign {} env {"TASKS" (database effects)})))
                  (fn [response]
                   (.then
                    (.text response)
                    (fn [body]
                      (Response.
                       (JSON.stringify {:effects effects
                                        :response body})
                       {:headers {"content-type" "application/json"}})))))))))})
