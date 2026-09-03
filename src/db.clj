(ns db
  (:require ["node:async_hooks" :as async_hooks]))

(def- db-fx (async_hooks/AsyncLocalStorage.))

(defn with-db [d1 f]
  (.run db-fx d1 f))

(defn- statement [sql params]
  (let [statement (.prepare (.getStore db-fx) sql)]
    (if (> (count params) 0)
      (Reflect.apply (get statement "bind") statement params)
      statement)))

(defn all [sql params]
  (.all (statement sql params)))

(defn run [sql params]
  (.run (statement sql params)))
