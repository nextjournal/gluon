;; ## 🔑 Garden ID

(ns example.garden-id
  (:require [nextjournal.clerk :as clerk]
            [nextjournal.clerk.viewer :as viewer]
            [nextjournal.gluon :as gluon]))


(defn build-session-state [opts]
  opts)

(defn render-page [system {:as session-state :keys [current-user]}]
  (clerk/html [:div.mt-6 [:h1 "🔑 Garden ID"]
               (if current-user
                 [:div
                  [:div {:nextjournal/value session-state}]
                  [:a {:data-ignore-anchor-click true :href "/logout"} "Logout"]]
                 [:a {:data-ignore-anchor-click true :href "/login"} "Login"])]))
