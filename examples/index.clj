(ns examples.index
  {:nextjournal.clerk/visibility {:code :hide :result :hide}
   :nextjournal.clerk/no-cache true}
  (:require [nextjournal.clerk :as clerk]))

;; # 🪅 Gluon Examples
(defn example [[name {:as opts :keys [config]}]]
  (let [url (str "http://localhost:" (:port config))]
    (clerk/fragment
     (clerk/html {::clerk/width :full}
                 [:div.max-w-prose.mx-auto
                  [:iframe.w-full.rounded-md.border.shadow-lg
                   {:src url
                    :style {:min-height 170}}]
                  [:div.px-6.py-2.font-sans.text-xs.text-right [:a {:href url} url]]])))
  )

{:nextjournal.clerk/visibility {:result :show}}
(clerk/fragment (mapv example user/examples))
