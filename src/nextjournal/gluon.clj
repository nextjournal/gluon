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

(defn render-app [system session]
  (notebook-layout ((:render-page (::impl system)) system session)))

(defn get-session [system ch]
  (get @(:!client->session system) ch))

(defn apply-op! [system ch {:as evt :keys [op arg]}]
  (let [[op-var perform-fx!] (binding [*ns* (:*ns* (::impl system))] ;; should come from system
                               (mapv resolve [op 'perform-fx!]))]
    #_ (prn :apply-op! evt (::op (meta op-var)))
    (when-not op-var
      (throw (ex-info (format "op `%` could not be resolved" op) {:op op})))

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

#_(apply-op! system (first (keys @(:!client->session system))) {:op 'set-selected-month!, :arg 11})

(defn present+send! [system ch]
  (webserver/send! ch {:type :set-state! :doc (viewer/present (render-app system (get-session system ch)))}))

#_(get-session system no-session-ch)

(defn build-system [m]
  (assoc m :!client->session (atom {})))

(defn get-user [req]
  (-> (garden-id/get-user req)
      ;; TODO: upstream
      (update :uuid parse-uuid)))

(defn ws-handlers [{:as req ::keys [system]}]
  {:on-open (fn [ch]
              (when (garden-id/logged-in? req)
                ;; TODO: check origin header
                (swap! (:!client->session system) assoc ch ((:build-session-state (::impl system)) {:current-user (get-user req)}))
                (present+send! system ch)))
   :on-close (fn [ch _reason] (swap! (:!client->session system) dissoc ch))
   :on-receive (fn [sender-ch edn-string]
                 (let [{:as msg :keys [type form]} (edn/read-string edn-string)]
                   (case type ;; using `:eval` for now, should get its own type when we merge this back into Clerk
                     :eval (webserver/send! sender-ch (merge {:type :eval-reply :eval-id (:eval-id msg)}
                                                             (try {:reply (if (not= 'apply-op! (first form))
                                                                            (throw (ex-info "eval is not allowed" {:form form}))
                                                                            (let [op-result (apply-op! system sender-ch (second form))]
                                                                              (if (contains? op-result ::reply)
                                                                                (::reply op-result)
                                                                                (do (present+send! system sender-ch)
                                                                                    op-result))))}
                                                                  (catch Exception e
                                                                    {:error (Throwable->map e)})))))))})

(defn app-handler
  "The default request handler, only called if no middleware handles the request."
  [{:as req ::keys [system]}]
  (if (:websocket? req)
    (httpkit/as-channel req (ws-handlers req))
    (case (:uri req)
      "/clerk_service_worker.js" (webserver/serve-resource (io/resource (str "public" (:uri req))))
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (view/->html {:doc (viewer/present (if (garden-id/logged-in? req)
                                                  (notebook-layout (clerk/html [:h3 "Loading…"]))
                                                  (render-app system {})))
                           :resource->url @config/!resource->url
                           :conn-ws? (garden-id/logged-in? req)})})))

(defn wrap-system
  [handler system]
  {:pre [(some? system)]}
  (fn [req]
    (handler (assoc req ::system system))))

(defn wrap-session [handler]
  (session/wrap-session handler {:store (cookie-store)}))

(defn app [system]
  "The gluon app"
  (-> #'app-handler
      (wrap-system system)
      (garden-id/wrap-auth)
      (wrap-session)))

(defn serve! [{:keys [port system] :or {port 8888}}]
  (httpkit/run-server (app system) {:port port :legacy-return-value? false}))
