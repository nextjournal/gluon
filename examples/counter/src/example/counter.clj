;; ## 🧮 Counter Example

(ns example.counter
  (:require [nextjournal.clerk :as clerk]
            [nextjournal.clerk.viewer :as viewer]
            [nextjournal.gluon :as gluon]))

(defn increment
  {::gluon/op :session}
  [session _]
  (inc session))

#_(::op (meta #'increment))

(defn decrement
  {::gluon/op :session}
  [session _]
  (prn :decrement session)
  (dec session))

(def bootstrap-cljs
  '(defn perform-op!
     ([op] (perform-op! op {}))
     ([op arg] (nextjournal.clerk.render/clerk-eval {:recompute? true} (list 'apply-op! {:op op :arg arg})))))

(clerk/eval-cljs bootstrap-cljs)

(defmacro viewer-fn [& args]
  (viewer/->viewer-eval (concat ['fn] args)))

(clerk/example
 (macroexpand '(viewer-fn [evt] (perform-op! 'increment))))

(defn build-session-state [_]
  0)


(defn counter-view [session-state]
  [:div [:h1 "🧮 Counter: "  session-state]
   [:button.bg-sky-400.m-1.p-1.rounded.text-white
    {:on-click (viewer-fn [evt] (perform-op! 'increment))}
    "Increment"]
   [:button.bg-sky-400.m-1.p-1.rounded.text-white
    {:on-click (viewer-fn [evt] (perform-op! 'decrement))}
    "Decrement"]])

(defn render-page [system session-state]
  (clerk/fragment (concat [(-> (clerk/eval-cljs bootstrap-cljs)
                               (assoc-in [:nextjournal/viewer :render-fn] '(fn [] [:<>])))
                           (clerk/html (counter-view session-state))])))

#_(clerk/html (render-view @!counter-system))




(comment
  (gluon/serve! {:port 8889 :ns *ns*})
  (keys (ns-publics *ns*))
  
  (serve! {:port 8899}))
