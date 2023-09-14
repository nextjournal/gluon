(ns user)

(try
  ;; in a try/cath to enable repl-debugging startup errors
  (require '[nextjournal.clerk :as clerk]
           '[nextjournal.gluon :as gluon])
  (catch Exception e
    (prn ::startup-error e)))


(try
  (def examples (atom {}))
  
  (def example->opts
    (into {}
          (map-indexed (fn [idx [k opts]]
                         [k (merge {:ns (symbol (str "example." (name k)))
                                    :port (+ 8888 idx)}
                                   opts)]))
          {:counter {}
           :garden-id {:garden-id true}}))

  (defn stop! []
    (doseq [{:keys [instance]} (-> 'examples resolve deref vals)]
      (org.httpkit.server/server-stop! instance))
    (alter-var-root #'examples (constantly nil)))

  #_(stop!)
  
  (defn start!
    ([] (start! (keys example->opts)))
    ([example-keys]
     (stop!)
     (def examples
       (into {}
             (map (fn [k]
                    [k (gluon/serve! (example->opts k))]))
             example-keys))
     (clerk/recompute!)))

  #_(start!)
  #_(start! #{:counter})
  
  (clerk/serve! {:port 8887 :browse true})
  (clerk/show! "examples/index.clj")
  
  (catch Exception e
    (prn ::startup-error e)))




