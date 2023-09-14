(ns example.todo-list
  (:require [datomic.client.api :as d]
            [nextjournal.clerk :as clerk]
            [nextjournal.gluon :as gluon]))
;; Db fuss

(def schema
  [{:db/ident :task/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :task/id
    :db/valueType :db.type/uuid
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :task/completed?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}

   {:db/ident :task/category
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(def conn
  (delay
   (let [client (d/client {:server-type :datomic-local
                           :storage-dir "/tmp/garden/storage"
                           :system "garden"})
         _ (d/create-database client {:db-name "gluon-todo-list"})
         conn (d/connect client {:db-name "gluon-todo-list"})]
     (d/transact conn {:tx-data schema})
     conn)))

(defn db [] (d/db @conn))

(defn tasks []
  (->> (d/q '[:find (pull ?t [:task/id :task/description :task/completed?]) ?txinst
              :where [?t :task/id _ ?txinst]]
            (db))
       (sort-by second >)
       (mapv first)))

#_(tasks)

;; Ops

(defn add-task
  {::gluon/op :fx}
  [_ _ text]
  {:tx-data [{:task/id (random-uuid) :task/description text}]})

#_(add-task '_ '_ "Do that")

(defn update-task
  {::gluon/op :fx}
  [_ _ {:keys [id done?]}]
  (let [ref [:task/id id]
        updated-entity (assoc (d/pull (db) '[:task/id :task/description :task/completed?] ref)
                         :task/completed? done?)]
    {:tx-data [updated-entity]}))

(defn remove-task
  {::gluon/op :fx}
  [_ _ id]
  {:tx-data [[:db/retractEntity [:task/id id]]]})

;; Clerk Mole

(def task-viewer
  {:transform-fn clerk/mark-presented
   :render-fn '(fn [{:as m :task/keys [description completed? id]} _]
                 [:div.mb-1.flex.bg-amber-200.border.border-amber-400.rounded-md.p-2.justify-between
                  [:div.flex
                   [:input.mt-2.ml-3.cursor-pointer {:type :checkbox :checked (boolean completed?)
                                                     :class (str "appearance-none h-4 w-4 rounded bg-amber-300 border border-amber-400 relative"
                                                                 "checked:border-amber-700 checked:bg-amber-700 checked:bg-no-repeat checked:bg-contain")
                                                     :on-change (fn [e]
                                                                  (.catch (perform-op! 'update-task {:id id :done? (.. e -target -checked)})
                                                                          (fn [err] (js/console.error err))))}]

                   [:div.text-xl.ml-2.mb-0.font-sans description]]
                  [:button.flex-end.mr-2.text-sm.text-amber-600.font-bold
                   {:on-click #(perform-op! 'remove-task id)} "⛌"]])})

(def tasks-viewer
  {:transform-fn (clerk/update-val (partial mapv (partial clerk/with-viewer task-viewer)))
   :render-fn '(fn [coll opts] (into [:div] (nextjournal.clerk.render/inspect-children opts) coll))})

(def input-controls
  (clerk/with-viewer
   '(fn [_ _]
      (let [text (nextjournal.clerk.render.hooks/use-state nil)
            ref (nextjournal.clerk.render.hooks/use-ref nil)
            handle-key-press (nextjournal.clerk.render.hooks/use-callback
                              (fn [e]
                                (when (and (= "Enter" (.-key e)) (= (.-target e) @ref) (not-empty @text))
                                  (reset! text nil)
                                  (.catch (perform-op! 'add-task @text)
                                          (fn [err] (js/console.error err))))) [text])]

        (nextjournal.clerk.render.hooks/use-effect
         (fn []
           (.addEventListener js/window "keydown" handle-key-press)
           #(.removeEventListener js/window "keydown" handle-key-press)) [handle-key-press])

        [:div.p-1.flex.bg-amber-100.border-amber-200.border.rounded-md.h-10.w-full.pl-8.font-sans.text-xl
         [:input.bg-amber-100.focus:outline-none.text-md.w-full
          {:on-change #(reset! text (.. % -target -value))
           :placeholder "Enter text and press Enter…" :ref ref
           :value @text :type "text"}]])) nil))

(def bootstrap-cljs
  '(defn perform-op!
     ([op] (perform-op! op {}))
     ([op arg] (nextjournal.clerk.render/clerk-eval (list 'apply-op! {:op op :arg arg})))))

(defn hide-result [w] (assoc-in w [:nextjournal/viewer :render-fn] '(fn [_ _] [:<>])))

;; Implementation

(defn build-session-state [m] m)

(defn perform-fx! [_ data] (d/transact @conn data))

(defn render-page [system session-state]
  (clerk/fragment [(hide-result (clerk/eval-cljs bootstrap-cljs))
                   (clerk/html [:h1.my-10 "📝 A Datomic Local Todo List"])
                   input-controls
                   (clerk/with-viewer tasks-viewer (tasks))]))

(comment
 (do
   (some-> 'server resolve deref :instance org.httpkit.server/server-stop!)
   (def server (gluon/serve! {:port 8989 :ns *ns*}))))
