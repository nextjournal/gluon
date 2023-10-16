(ns nextjournal.gluon
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [editscript.core :as editscript]
            [nextjournal.clerk :as clerk]
            [nextjournal.clerk.config :as config]
            [nextjournal.clerk.eval :as eval]
            [nextjournal.clerk.view :as view]
            [nextjournal.clerk.viewer :as viewer]
            [nextjournal.clerk.webserver :as webserver]
            [nextjournal.garden-id :as garden-id]
            [org.httpkit.server :as httpkit]
            [ring.middleware.session :as session]
            [ring.middleware.session.cookie :refer [cookie-store]]))

(defn build-doc [result]
  {:blocks [{:settings {:nextjournal.clerk/visibility {:code :hide, :result :show}}
             :type :code
             :result {:nextjournal/value result}}]})

(def empty-header-viewer
  (assoc viewer/header-viewer :transform-fn (comp clerk/mark-presented (clerk/update-val (fn [_] (clerk/html [:<>]))))))

(defn notebook-layout [content]
  (->> (build-doc content)
       (clerk/with-viewers (viewer/add-viewers [empty-header-viewer]))
       clerk/notebook))

(defn render-app [render-page system session]
  {:pre [(ifn? render-page)]}
  (notebook-layout (render-page system session)))

(defn get-session [system ch]
  (get @(:!client->session system) ch))

(defn apply-op! [{::keys [system impl]} ch {:as evt :keys [op arg]}]
  (let [[op-var perform-fx!] (binding [*ns* (:ns impl)] ;; should come from system
                               (mapv resolve [op 'perform-fx!]))]
    #_ (prn :apply-op! evt (::op (meta op-var)))
    (when-not op-var
      (throw (ex-info (format "op `%s` could not be resolved" op) {:op op})))

    (let [{:keys [time-ms result]}
          (eval/time-ms
           (case (::op (meta op-var))
             :session (swap! (:!client->session system) update ch @op-var arg)
             ;; FIXME error handling
             :fx (perform-fx! system (@op-var (cond-> system
                                                (::system-fn (meta op-var))
                                                (::system-fn (meta op-var)))
                                      (get-session system ch) arg))
             (throw (ex-info (format "`%s` is not whitelisted as allowed operation, missing `%s` on var metadata." op-var ::op) {:op-var op-var :meta (meta op-var)}))))]
      (if (contains? result ::reply)
        result
        {:applied-op op :time-ms time-ms}))))

(defn sync-swap! [{::keys [system impl]} ch msg]
  (when-some [sswap! (get impl 'sync-swap!)]
    (let [ret (sswap! system (get-session system ch) msg)]
      (when (and (map? ret) (contains? ret ::reply))
        (webserver/send! ch (::reply ret))))))

#_(apply-op! system (first (keys @(:!client->session system))) {:op 'set-selected-month!, :arg 11})

(defn present+send! [{::keys [system impl]} ch]
  (webserver/send! ch {:type :set-state! :doc (viewer/present (render-app ('render-page impl) system (get-session system ch)))}))

#_(get-session system no-session-ch)

(defn build-system [m]
  (assoc m :!client->session (atom {})))

(defn get-user [req]
  (cond-> (garden-id/get-user req)
    (:uuid (garden-id/get-user req))
    (update :uuid parse-uuid)))

(defn ws-handlers [{:as req ::keys [system impl]}]
  {:on-open (fn [ch]
              (when (or (not (:garden-id impl))
                        (garden-id/logged-in? req))
                ;; TODO: check origin header
                (swap! (:!client->session system) assoc ch (('build-session-state impl) (cond-> {} (get-user req) (assoc :current-user (get-user req)))))
                (present+send! req ch)))
   :on-close (fn [ch _reason] (swap! (:!client->session system) dissoc ch))
   :on-receive (fn [sender-ch edn-string]
                 (let [{:as msg :keys [type form]} (edn/read-string edn-string)]
                   (case type ;; using `:eval` for now, should get its own type when we merge this back into Clerk
                     :eval (webserver/send! sender-ch (merge {:type :eval-reply :eval-id (:eval-id msg)}
                                                             (try {:reply (if (not= 'apply-op! (first form))
                                                                            (throw (ex-info "eval is not allowed" {:form form}))
                                                                            (let [op-result (apply-op! req sender-ch (second form))]
                                                                              (if (contains? op-result ::reply)
                                                                                (::reply op-result)
                                                                                (do (present+send! req sender-ch)
                                                                                    op-result))))}
                                                                  (catch Exception e
                                                                    (prn :on-recieve/error e)
                                                                    {:error (Throwable->map e)}))))
                     :swap! (sync-swap! req sender-ch msg))))})

(defn app-handler
  "The default request handler, only called if no middleware handles the request."
  [{:as req ::keys [system impl]}]
  (if (:websocket? req)
    (httpkit/as-channel req (ws-handlers req))
    (case (:uri req)
      "/clerk_service_worker.js" (webserver/serve-resource (io/resource (str "public" (:uri req))))
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (view/->html {:doc (viewer/present
                                 (if (and (:garden-id impl) (garden-id/logged-in? req))
                                   (notebook-layout (clerk/html [:h3 "Loading…"]))
                                   (render-app ('render-page impl) system nil)))
                           :resource->url @config/!resource->url
                           :conn-ws? true #_(garden-id/logged-in? req)})})))

(defn wrap-system
  [handler {:keys [system impl]}]
  {:pre [(some? system)
         (some? impl)]}
  (fn [req]
    (handler (assoc req ::system system ::impl impl))))


(defn wrap-session [handler]
  (session/wrap-session handler {:store (cookie-store)}))

(defn app [config]
  "The gluon app"
  (prn :app/garden-id (:garden-id config) (keys config))
  (cond-> (wrap-system #'app-handler config)
    (:garden-id config)
    (-> (garden-id/wrap-auth)
        (wrap-session))))

(def required-impl-names
  '#{render-page
     build-session-state})

(defn build-impl [{:as config :keys [ns]}]
  (when-let [missing-vars (not-empty (clojure.set/difference required-impl-names (set (keys (ns-publics ns)))))]
    (throw (ex-info (format "ns `%s` is missing the following vars required by gluon: `%s`" ns missing-vars) {:missing-vars missing-vars})))
  (-> (ns-publics ns)
      (select-keys required-impl-names)
      (merge (select-keys config [:garden-id]))
      (assoc :ns ns)))

(comment
  (ns-publics (find-ns 'example.counter))
  (build-impl (find-ns 'example.counter)))

(defn load-impl-ns [{:as config :keys [ns]}]
  (when (simple-symbol? ns)
    (require ns))
  (build-impl (update config :ns the-ns)))

(comment
  (load-impl-ns 'example.counter)
  (load-impl-ns (find-ns 'example.counter))
  (load-impl-ns 'example.counter-404))

(def default-opts
  {:port 8888
   :garden-id false
   :system {}})

(defn parse-opts [opts]
  (let [merged-opts (merge default-opts opts)]
    (when-not (:ns merged-opts)
      (throw (ex-info "`:ns` is required in opts" {:opts opts})))
    (-> merged-opts
        (update :system build-system)
        (assoc :impl (load-impl-ns merged-opts)))))

(comment
  ('render-page (:impl (parse-opts {:ns 'example.counter}))))

(defn serve! [opts]
  (let [{:as config :keys [port]} (parse-opts opts)]
    {:config config
     :instance (httpkit/run-server (app config)  {:port port :legacy-return-value? false})}))

#_(user/restart!)
