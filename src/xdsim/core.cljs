(ns xdsim.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [clojure.string :as string]
            [sablono.core :as html :refer [html] :include-macros true]))

(enable-console-print!)

(def empty-node
  {:update-seq 0
   :editable false
   :name "node"
   :log []
   :current {}
   :new-rev 0})

(defn update-current [current-map item]
  (let [itemkey (:key item)]
    (assoc current-map itemkey item)))

(defn setm-item
  "Set item into node, using metadata as is."
  [node item]
  (let [iseq (inc (:update-seq node))
        item (assoc item
                    :seq iseq)]
    (-> node
        (assoc :update-seq iseq)
        (update-in [:new-rev] max (if (:deleted item) (:rev item) 0))
        (update-in [:log] conj item)
        (update-in [:current] update-current item))))

(defn set-item
  "Set item into node, and assign it a rev and cas"
  [node item]
  (let [oldrev (-> node :current (get (:key item)) :rev)
        rev (if oldrev (inc oldrev) (:new-rev node))]
    (setm-item node (assoc item
                           :rev rev
                           :cas (rand-int 0xFFFFFFFF)))))

(defn set-kv
  "Set item with key k in node to value v"
  [node k v]
  (let [item {:value v
              :key k
              :seq (inc (:update-seq node))}]
    (set-item node item)))

(defn modify-item
  "Set item with key k in node to result of (f current-value ...) (cas)"
  [node k f & args]
  (let [prev-item (get (:current node) k)
        item (-> prev-item
                 (assoc :value (apply f (:value prev-item) args)))]
    (set-item node item)))

(defn incr-counter
  "Increment a counter item in node with key k"
  [node k]
  (modify-item node k (fn [curr]
                        (str (let [p (js/parseInt curr 10)]
                               (if (= p js/NaN) 0 (inc p)))))))

(defn delete-item
  "Delete item with key k from node"
  [node k]
  (let [prev-item (get (:current node) k)
        item (-> prev-item
                 (assoc :deleted true)
                 (assoc :value "(deleted)"))]
    (set-item node item)))

(defn node-current-list
  "Get list of current items in node (no obsolete values) in the order they were added"
  [node]
  (filter (fn [item] (and (= (:seq item)
                             (:seq (-> node :current (get (:key item)))))))
          (:log node)))

(defn without-deletes
  "Remove deleted items from a list of items"
  [items]
  (remove :deleted items))

(defn node-receive-reset-log
  "Reset node and restore from the log of remote-node"
  [node remote-node]
  (reduce (fn [node item]
            (setm-item node item))
          (assoc node :log [] :current {} :update-seq 0 :new-rev 0)
          (:log remote-node)))

(defn node-xdc-receive
  "Use XDCR rules to apply remote-node's log to node"
  [node remote-node]
  (reduce (fn [node remote-item]
            (let [local-item (get (:current node) (:key remote-item))
                  local-rev (:rev local-item 0)
                  remote-rev (:rev remote-item)]
              (if (or (< local-rev remote-rev)
                      (and (= local-rev remote-rev)
                           (< (:cas local-item) (:cas remote-item))))
                ;; if remote rev is higher, or if equal cas is higher, do update
                (setm-item node remote-item)
                ;; otherwise leave things the same
                node)))
          node (:log remote-node)))

(defn fixup-current
  "Fix :current map in node to reflect log"
  [node]
  (assoc node :current (reduce update-current {} (:log node))))

(defn node-compact
  "Remove obsoleted entries from node's log"
  [node]
  (-> node
      (assoc :log (node-current-list node))
      fixup-current))

(defn node-purge
  "Compact node's log and also remove deleted items (tombstones) from log."
  [node]
  (-> node
      (assoc :log (without-deletes (node-current-list node)))
      fixup-current))

(def app-state (atom {:source-master
                      (assoc empty-node
                             :name "West DC (Master)"
                             :class "master"
                             :editable true)
                      :source-replica (assoc empty-node
                                             :class "replica"
                                             :name "West DC (Replica)")
                      :target (assoc empty-node
                                     :class "target"
                                     :editable true
                                     :name "East DC")}))

(defn node
  "Render a node"
  [node-data owner]
  (html
    [:div {:class (str "node " (:class node-data))}
     [:h2 (:name node-data)]
     [:span {:class "newrev"} "New Item Rev: "(:new-rev node-data)]
     [:span {:class "logsz"} "Size of Log: " (count (:log node-data))]
     [:button.btn {:on-click #(om/update! node-data node-compact)}
      "Compact"]
     [:button.btn {:on-click #(om/update! node-data node-purge)}
      "Purge Tombstones"]
     (when (pos? (count (:current node-data)))
       [:table
        [:thead {:class "headrow"}
         [:tr
          [:th {:class "keyname"} "Key"]
          [:th {:class "valc"} "Value"]
          [:th {:class "revnum"} "Rev#"]]]
        [:tbody
         (for [{k :key v :value rev :rev} (->> node-data node-current-list without-deletes (sort-by :key))]
           [:tr
            [:td {:class "keyname"} (str k)]
            [:td {:class "valc"} (str v)]
            [:td {:class "revnum"} (str rev)]
            (if (:editable node-data)
              [:td
               [:button.btn {:on-click #(om/update! node-data incr-counter k)} "+"]
               [:button.btn {:on-click #(om/update! node-data modify-item k (constantly (rand-int 0xFFFF)))} "R"]
               [:button.btn {:on-click #(om/update! node-data delete-item k)} "X"]])])]])]))

(def ENTER_KEY 13)

(defn do-set-key [e app owner cid]
  (let [name-field (om/get-node owner "setkey")
        val-field (om/get-node owner "setval")
        valstr (.. val-field -value trim)
        valstr (if (= valstr "") "0" valstr)
        kname (.. name-field -value trim)]
    (when (and (not (string/blank? kname))
               (== (.-which e) ENTER_KEY))
      (set! (.-value (.-which e)) "")
      (om/transact! app cid set-kv kname valstr))))

(defn do-xdcr-sim [app sid did]
  (assoc app did (node-xdc-receive (get app did) (get app sid))))

(defn do-reset-sim [app sid did]
  (assoc app did (node-receive-reset-log (get app did) (get app sid))))

(defn do-promote-sim [app sid did]
  (assoc app
         did (node-receive-reset-log (get app did) (get app sid))
         sid (node-receive-reset-log (get app sid) empty-node)))

(defn setter-input [app owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [node]}]
      (dom/div #js {:className "setter"}
               "Set "
               (dom/input
                 #js {:className "set-field"
                      :ref "setkey"
                      :placeholder "Key"
                      :onKeyDown #(do-set-key % app owner node)})
               (str " on " (:name (get app node)) " to ")
               (dom/input
                 #js {:className "set-field"
                      :ref "setval"
                      :placeholder "0"
                      :onKeyDown #(do-set-key % app owner node)})))))

(defn xdsim-app
  [app owner]
  (reify
    om/IRenderState
    (render-state [_ _]
      (dom/div nil
               (dom/div #js {:className "nodepane"}
                        (om/build node (:target app))
                        (om/build node (:source-master app))
                        (om/build node (:source-replica app)))
               (dom/div #js {:className "controls"}
                        (dom/div #js {:className "controlpane"}
                                 (dom/div #js {:className "setters"}
                                          (om/build setter-input app {:init-state {:node :target}})
                                          (om/build setter-input app {:init-state {:node :source-master}}))
                                 (html
                                   [:button.btn {:class "targsrc"
                                                 :on-click #(om/update! app do-xdcr-sim
                                                                        :target
                                                                        :source-master)}
                                    "XDCR East DC to West DC ->"]
                                   [:button.btn {:class "srctarg"
                                                 :on-click #(om/update! app do-xdcr-sim
                                                                        :source-master
                                                                        :target)}
                                    "<- XDCR West DC to East DC"]
                                   [:button.btn {:class "replicate"
                                                 :on-click #(om/update! app do-reset-sim
                                                                        :source-master :source-replica)}
                                    "Replicate Master to Replica ->"]
                                   [:button.btn {:class "promote"
                                                 :on-click #(om/update! app do-promote-sim
                                                                        :source-replica :source-master)}
                                    "<- Replace Master with Replica (Failover)"])))))))

(om/root app-state xdsim-app (. js/document (getElementById "app")))

;; http://swannodette.github.io/2013/12/31/time-travel/
;;
;;
;;;; Store the history of all app states
;;(def app-history (atom [@app-state]))
;;
;;(add-watch app-state :history
;;  (fn [_ _ _ n]
;;    (when-not (= (last @app-history) n)
;;      (swap! app-history conj n))))
