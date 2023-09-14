(ns user)

(try
  ;; in a try/cath to enable repl-debugging startup errors
  (require '[nextjournal.clerk :as clerk]
           '[nextjournal.gluon :as gluon])
  (catch Exception e
    (prn ::startup-error e)))


(try
  (def examples
    (into {}
          (comp (map-indexed (fn [idx k]
                               [k {:ns (symbol (str "example." (name k)))
                                   :port (+ 8888 idx)}]))
                (map (fn [[k opts]]
                       [k (gluon/serve! opts)])))
          [:counter :garden-id :todo-list]))

  (clerk/serve! {:port 8887 :browse true})
  (clerk/show! "examples/index.clj")

  (catch Exception e
    (prn ::startup-error e)))
