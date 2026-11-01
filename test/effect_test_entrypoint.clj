(ns effect-test-entrypoint
  (:require [db :as db])
  (:require [main :as main])
  (:require [telegram :as telegram]))

(defn- database [effects tasks]
  {:prepare (fn [sql]
              (.push effects {:type "d1.prepare" :sql sql})
              {:all (fn []
                      (.resolve
                       Promise
                       {:results (if (.startsWith sql "SELECT id, telegram_user_id")
                                   (or tasks [{:id 1 :telegram_user_id "user-1" :text "https://t.me/serbia" :cursor 10 :selection_rule nil}
                                              {:id 2 :telegram_user_id "user-2" :text "https://t.me/broken" :cursor 20 :selection_rule nil}
                                              {:id 3 :telegram_user_id "user-3" :text "https://t.me/cursorfail" :cursor 30 :selection_rule nil}
                                              {:id 4 :telegram_user_id "user-4" :text "https://t.me/quiet" :cursor 40 :selection_rule "+C++ +.NET +@account +мат +AT&T +© -реклама"}
                                              {:id 5 :telegram_user_id "user-5" :text "https://t.me/prohibited" :cursor 50 :selection_rule "-реклама"}
                                              {:id 6 :telegram_user_id "user-6" :text "https://t.me/required" :cursor 60 :selection_rule "+clojure"}])
                                   [])}))
               :bind (fn [first second third]
                       (.push effects {:type "d1.bind"
                                       :params (cond
                                                 third [first second third]
                                                 second [first second]
                                                 :else [first])})
                       {:run (fn []
                               (if (and (.startsWith sql "UPDATE") (= 3 second))
                                 (.reject Promise (Error. "cursor write failed"))
                                 (.resolve Promise {})))
                        :all (fn []
                               (.resolve
                                Promise
                                {:results (cond
                                            (.startsWith sql "INSERT")
                                            (if (= "duplicate-user" first) [] [{:id 3}])

                                            (.startsWith sql "UPDATE tasks SET selection_rule")
                                            (if (= "empty-user" second) [] [{:id 1}])

                                            (= "empty-user" first) []
                                            (.startsWith sql "DELETE") [{:id 1}]
                                            :else [{:text "first" :selection_rule "+C++ -реклама"}
                                                   {:text "second" :selection_rule nil}])}))})})})

(defn- external-fetch [effects]
  (fn [url props]
    (.push effects {:type "fetch" :url url :props props})
    (cond
      (.startsWith url "https://t.me/s/broken")
      (.resolve Promise (Response. "unavailable" {:status 500}))

      (.startsWith url "https://t.me/s/quiet")
      (.resolve
       Promise
       (Response. "<div data-post='quiet/49'><div class='tgme_widget_message_text js-message_text'>&copy; notice</div></div><div data-post='quiet/48'><div class='tgme_widget_message_text js-message_text'>AT&amp;T update</div></div><div data-post='quiet/47'><div class='tgme_widget_message_text js-message_text'>From @ACCOUNT.</div></div><div data-post='quiet/46'><div class='tgme_widget_message_text js-message_text'>Use <b>.NET</b>!</div></div><div data-post='quiet/45'><div class='tgme_widget_message_text js-message_text'>Математика</div></div><div data-post='quiet/44'></div><div data-post='quiet/43'><div class='tgme_widget_message_text js-message_text'><div>quoted</div> МАТ news</div></div><div data-post='quiet/42'><div class='tgme_widget_message_text js-message_text'>C++ реклама</div></div><div data-post='quiet/41'><div class='tgme_widget_message_text js-message_text'><b>C</b>++ release</div></div>"))

      (.startsWith url "https://t.me/s/prohibited")
      (.resolve
       Promise
       (Response. "<div data-post='prohibited/52'><div class='js-message_text'>Реклама</div></div><div data-post='prohibited/51'><div class='js-message_text'>Новости</div></div>"))

      (.startsWith url "https://t.me/s/required")
      (.resolve
       Promise
       (Response. "<div data-post='required/62'><div class='js-message_text'>Java</div></div><div data-post='required/61'><div class='js-message_text'>Clojure</div></div>"))

      (.startsWith url "https://t.me/s/")
      (.resolve
       Promise
       (Response.
        (cond
          (.startsWith url "https://t.me/s/cursorfail")
          "<div data-post='cursorfail/32'></div><div data-post='cursorfail/31'></div>"
          (.includes url "?after=")
          "<div data-post='serbia/13'></div><div data-post='serbia/11'></div>"
          :else
          "<div data-post='serbia/4'></div><div data-post='serbia/9'></div>")))

      :else
      (let [body (if props (JSON.parse (get props "body")) nil)]
        (.resolve
         Promise
         (Response.
          (JSON.stringify {:ok (if (= "https://t.me/serbia/11" (get body "text")) false true)})
          {:headers {"content-type" "application/json"}}))))))

(defn- selection-response [request env]
  (.then (.json request)
         (fn [{:rule rule :html html}]
           (let [effects (Array.)]
             (telegram/with-fetch
               (fn [url props]
                 (if (.startsWith url "https://t.me/s/")
                   (.resolve Promise (Response. html))
                   ((external-fetch effects) url props)))
               (fn []
                 (db/with-db
                   (database effects [{:id 7 :telegram_user_id "owner" :text "https://t.me/selection" :cursor 10 :selection_rule rule}])
                   (fn []
                     (.then (main/handle-scheduled env)
                            (fn [] (Response. (JSON.stringify effects))))))))))))

(export-default
 :fetch (fn [request env ctx]
          (if (.endsWith (get request "url") "/selection")
            (selection-response request env)
            (let [effects (Array.)]
              (telegram/with_fetch
               (external-fetch effects)
               (fn []
                 (db/with_db
                  (database effects nil)
                  (fn []
                    (.then
                     (.resolve Promise (main/handle-fetch request env))
                     (fn [response]
                       (.then
                        (.text response)
                        (fn [body]
                          (Response.
                           (JSON.stringify {:effects effects
                                            :response body})
                           {:headers {"content-type" "application/json"}}))))))))))))
 :scheduled (fn [controller env ctx]
              (let [effects (Array.)]
                (telegram/with_fetch
                 (external-fetch effects)
                 (fn []
                   (db/with_db
                    (database effects nil)
                    (fn []
                      (.then
                       (main/handle-scheduled env)
                       (fn []
                         (globalThis.console.log
                          (JSON.stringify {:event "scheduled_effects" :effects effects})))))))))))
