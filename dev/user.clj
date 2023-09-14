(ns user)

(try
  ;; in a try/cath to enable repl-debugging startup errors
  (require '[nextjournal.clerk :as clerk]
           '[nextjournal.gluon :as gluon])
  (catch Exception e
    (prn ::startup-error e)))


(try
  (defn start! [examples]
    (into {}
          (comp (map-indexed (fn [idx k]
                               [k {:ns (symbol (str "example." (name k)))
                                   :port (+ 8888 idx)}]))
                (map (fn [[k opts]]
                       [k (gluon/serve! opts)])))
          examples))
  
  (def examples
    (start! [:counter :garden-id]))

  (defn stop!
    ([] (stop! examples))
    ([ex]
     (doseq [{:keys [instance]} (vals examples)]
       (org.httpkit.server/server-stop! instance))))

  #_(stop!)

  (defn restart!
    ([] (alter-var-root #'examples (constantly
                                    (restart! examples))))
    ([ex]
     (stop! ex)
     (start! (keys examples))))

  #_(restart!)

  (clerk/serve! {:port 8887 :browse true})
  (clerk/show! "examples/index.clj")
  
  (catch Exception e
    (prn ::startup-error e)))




