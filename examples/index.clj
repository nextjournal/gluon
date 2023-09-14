(ns examples.index
  {:nextjournal.clerk/visibility {:code :hide :result :hide}
   :nextjournal.clerk/no-cache true}
  (:require [nextjournal.clerk :as clerk]))

;; # 🪅 Gluon Examples
(defn example [[name {:as opts :keys [config]}]]
  (let [url (str "http://localhost:" (:port config))]
    (clerk/fragment
     (clerk/html [:div.p-1.font-mono.text-xs [:a {:href url} url]])
     (clerk/html {::clerk/width :full}
                 [:iframe.w-full.border {:src url}])))
  )

{:nextjournal.clerk/visibility {:result :show}}
(clerk/fragment (mapv example user/examples))
