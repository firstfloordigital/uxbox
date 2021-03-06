;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.pages-helpers :as cph]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as geom]
   [app.main.data.messages :as dm]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.libraries-helpers :as dwlh]
   [app.common.pages :as cp]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.util.color :as color]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.time :as dt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

(declare sync-file)

(defn default-color-name [color]
  (or (:color color)
      (case (get-in color [:gradient :type])
        :linear (tr "workspace.gradients.linear")
        :radial (tr "workspace.gradients.radial"))))

(defn add-color
  [color]
  (let [id   (uuid/next)
        color (assoc color
                     :id id
                     :name (default-color-name color))]
    (us/assert ::cp/color color)
    (ptk/reify ::add-color
      ptk/WatchEvent
      (watch [_ state s]
        (let [rchg {:type :add-color
                    :color color}
              uchg {:type :del-color
                    :id id}]
          (rx/of #(assoc-in % [:workspace-local :color-for-rename] id)
                 (dwc/commit-changes [rchg] [uchg] {:commit-local? true})))))))

(defn add-recent-color
  [color]
  (us/assert ::cp/recent-color color)
  (ptk/reify ::add-recent-color
    ptk/WatchEvent
    (watch [_ state s]
      (let [rchg {:type :add-recent-color
                  :color color}]
        (rx/of (dwc/commit-changes [rchg] [] {:commit-local? true}))))))

(def clear-color-for-rename
  (ptk/reify ::clear-color-for-rename
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :color-for-rename] nil))))

(defn update-color
  [{:keys [id] :as color}]
  (us/assert ::cp/color color)
  (ptk/reify ::update-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :mod-color
                  :color color}
            uchg {:type :mod-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true})
               (sync-file nil))))))

(defn delete-color
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-color
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :colors id])
            rchg {:type :del-color
                  :id id}
            uchg {:type :add-color
                  :color prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(defn add-media
  [{:keys [id] :as media}]
  (us/assert ::cp/media-object media)
  (ptk/reify ::add-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [rchg {:type :add-media
                  :object media}
            uchg {:type :del-media
                  :id id}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))


(defn delete-media
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-media
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :media id])
            rchg {:type :del-media
                  :id id}
            uchg {:type :add-media
                  :object prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))

(def add-component
  (ptk/reify ::add-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id  (:current-page-id state)
            objects  (dwc/lookup-page-objects state page-id)
            selected (get-in state [:workspace-local :selected])
            shapes   (dws/shapes-for-grouping objects selected)]
        (when-not (empty? shapes)
          (let [;; If the selected shape is a group, we can use it. If not,
                ;; we need to create a group before creating the component.
                [group rchanges uchanges]
                (if (and (= (count shapes) 1)
                         (= (:type (first shapes)) :group))
                  [(first shapes) [] []]
                  (dws/prepare-create-group page-id shapes "Component-" true))

                [new-shape new-shapes updated-shapes]
                (dwlh/make-component-shape group objects)

                rchanges (conj rchanges
                               {:type :add-component
                                :id (:id new-shape)
                                :name (:name new-shape)
                                :shapes new-shapes})

                rchanges (into rchanges
                               (map (fn [updated-shape]
                                      {:type :mod-obj
                                       :page-id page-id
                                       :id (:id updated-shape)
                                       :operations [{:type :set
                                                     :attr :component-id
                                                     :val (:component-id updated-shape)}
                                                    {:type :set
                                                     :attr :component-file
                                                     :val nil}
                                                    {:type :set
                                                     :attr :component-root?
                                                     :val (:component-root? updated-shape)}
                                                    {:type :set
                                                     :attr :shape-ref
                                                     :val (:shape-ref updated-shape)}
                                                    {:type :set
                                                     :attr :touched
                                                     :val (:touched updated-shape)}]})
                                    updated-shapes))

                uchanges (conj uchanges
                               {:type :del-component
                                :id (:id new-shape)})

                uchanges (into uchanges
                               (map (fn [updated-shape]
                                      (let [original-shape (get objects (:id updated-shape))]
                                        {:type :mod-obj
                                         :page-id page-id
                                         :id (:id updated-shape)
                                         :operations [{:type :set
                                                       :attr :component-id
                                                       :val (:component-id original-shape)}
                                                      {:type :set
                                                       :attr :component-file
                                                       :val (:component-file original-shape)}
                                                      {:type :set
                                                       :attr :component-root?
                                                       :val (:component-root? original-shape)}
                                                      {:type :set
                                                       :attr :shape-ref
                                                       :val (:shape-ref original-shape)}
                                                      {:type :set
                                                       :attr :touched
                                                       :val (:touched original-shape)}]}))
                                    updated-shapes))]


            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
                   (dws/select-shapes (d/ordered-set (:id group))))))))))

(defn delete-component
  [{:keys [id] :as params}]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component (get-in state [:workspace-data :components id])

            rchanges [{:type :del-component
                       :id id}]

            uchanges [{:type :add-component
                       :id id
                       :name (:name component)
                       :shapes (vals (:objects component))}]]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn instantiate-component
  [file-id component-id position]
  (us/assert (s/nilable ::us/uuid) file-id)
  (us/assert ::us/uuid component-id)
  (ptk/reify ::instantiate-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [component (if (nil? file-id)
                        (get-in state [:workspace-data :components component-id])
                        (get-in state [:workspace-libraries file-id :data :components component-id]))
            component-shape (get-in component [:objects (:id component)])

            orig-pos  (gpt/point (:x component-shape) (:y component-shape))
            delta     (gpt/subtract position orig-pos)

            page-id   (:current-page-id state)
            objects   (dwc/lookup-page-objects state page-id)
            unames    (atom (dwc/retrieve-used-names objects))

            all-frames (cph/select-frames objects)

            update-new-shape
            (fn [new-shape original-shape]
              (let [new-name 
                    (dwc/generate-unique-name @unames (:name new-shape))]

                (swap! unames conj new-name)

                (cond-> new-shape
                  true
                  (as-> $
                    (assoc $ :name new-name)
                    (geom/move $ delta)
                    (assoc $ :frame-id
                           (dwc/calculate-frame-overlap all-frames $))
                    (assoc $ :parent-id
                           (or (:parent-id $) (:frame-id $)))
                    (assoc $ :shape-ref (:id original-shape))
                    (dissoc $ :touched))

                  (nil? (:parent-id original-shape))
                  (assoc :component-id (:id original-shape)
                         :component-root? true)

                  (and (nil? (:parent-id original-shape)) (some? file-id))
                  (assoc :component-file file-id)

                  (and (nil? (:parent-id original-shape)) (nil? file-id))
                  (dissoc :component-file)

                  (some? (:parent-id original-shape))
                  (dissoc :component-root?))))

            [new-shape new-shapes _]
            (cph/clone-object component-shape
                              nil
                              (get component :objects)
                              update-new-shape)

            rchanges (map (fn [obj]
                            {:type :add-obj
                             :id (:id obj)
                             :page-id page-id
                             :frame-id (:frame-id obj)
                             :parent-id (:parent-id obj)
                             :obj obj})
                          new-shapes)

            uchanges (map (fn [obj]
                            {:type :del-obj
                             :id (:id obj)
                             :page-id page-id})
                          new-shapes)]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})
               (dws/select-shapes (d/ordered-set (:id new-shape))))))))

(defn detach-component
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::detach-component
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            shapes (cph/get-object-with-children id objects)

            rchanges (map (fn [obj]
                            {:type :mod-obj
                             :page-id page-id
                             :id (:id obj)
                             :operations [{:type :set
                                           :attr :component-id
                                           :val nil}
                                          {:type :set
                                           :attr :component-file
                                           :val nil}
                                          {:type :set
                                           :attr :shape-ref
                                           :val nil}]})
                          shapes)

            uchanges (map (fn [obj]
                            {:type :mod-obj
                             :page-id page-id
                             :id (:id obj)
                             :operations [{:type :set
                                           :attr :component-id
                                           :val (:component-id obj)}
                                          {:type :set
                                           :attr :component-file
                                           :val (:component-file obj)}
                                          {:type :set
                                           :attr :shape-ref
                                           :val (:shape-ref obj)}]})
                          shapes)]

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn nav-to-component-file
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::nav-to-component-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [file (get-in state [:workspace-libraries file-id])
            pparams {:project-id (:project-id file)
                     :file-id (:id file)}
            qparams {:page-id (first (get-in file [:data :pages]))}]
        (st/emit! (rt/nav-new-window :workspace pparams qparams))))))

(defn ext-library-changed
  [file-id modified-at changes]
  (us/assert ::us/uuid file-id)
  (us/assert ::cp/changes changes)
  (ptk/reify ::ext-library-changed
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (assoc-in [:workspace-libraries file-id :modified-at] modified-at)
          (d/update-in-when [:workspace-libraries file-id :data]
                            cp/process-changes changes)))))

(defn reset-component
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::reset-component
    ptk/WatchEvent
    (watch [_ state stream]
      ;; ===== Uncomment this to debug =====
      ;; (js/console.info "##### RESET-COMPONENT of shape" (str id))
      (let [page-id (:current-page-id state)
            page    (get-in state [:workspace-data :pages-index page-id])
            objects (dwc/lookup-page-objects state page-id)
            shape   (get objects id)
            file-id (get shape :component-file)

            [all-shapes component root-component]
            (dwlh/resolve-shapes-and-components shape
                                                objects
                                                state
                                                true)

            ;; ===== Uncomment this to debug =====
            ;; _ (js/console.info "shape" (:name shape) "<- component" (:name component))
            ;; _ (js/console.debug "all-shapes" (clj->js all-shapes))
            ;; _ (js/console.debug "component" (clj->js component))
            ;; _ (js/console.debug "root-component" (clj->js root-component))

            [rchanges uchanges]
            (dwlh/generate-sync-shape-and-children-components shape
                                                              all-shapes
                                                              component
                                                              root-component
                                                              (:id page)
                                                              nil
                                                              true)]

        ;; ===== Uncomment this to debug =====
        ;; (js/console.debug "rchanges" (clj->js rchanges))

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(defn update-component
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::update-component
    ptk/WatchEvent
    (watch [_ state stream]
      ;; ===== Uncomment this to debug =====
      ;; (js/console.info "##### UPDATE-COMPONENT of shape" (str id))
      (let [page-id (:current-page-id state)
            objects (dwc/lookup-page-objects state page-id)
            shape   (get objects id)
            file-id (get shape :component-file)

            [all-shapes component root-component]
            (dwlh/resolve-shapes-and-components shape
                                                objects
                                                state
                                                true)

            ;; ===== Uncomment this to debug =====
            ;; _ (js/console.info "shape" (:name shape) "-> component" (:name component))
            ;; _ (js/console.debug "all-shapes" (clj->js all-shapes))
            ;; _ (js/console.debug "component" (clj->js component))
            ;; _ (js/console.debug "root-component" (clj->js root-component))

            [rchanges uchanges]
            (dwlh/generate-sync-shape-inverse shape
                                              all-shapes
                                              component
                                              root-component
                                              page-id)]

        ;; ===== Uncomment this to debug =====
        ;; (js/console.debug "rchanges" (clj->js rchanges))

        (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true}))))))

(declare sync-file-2nd-stage)

(defn sync-file
  [file-id]
  (us/assert (s/nilable ::us/uuid) file-id)
  (ptk/reify ::sync-file
    ptk/UpdateEvent
    (update [_ state]
      (if file-id
        (assoc-in state [:workspace-libraries file-id :synced-at] (dt/now))
        state))

    ptk/WatchEvent
    (watch [_ state stream]
      ;; ===== Uncomment this to debug =====
      ;; (js/console.info "##### SYNC-FILE" (str (or file-id "local")))
      (let [library-changes [(dwlh/generate-sync-library :components file-id state)
                             (dwlh/generate-sync-library :colors file-id state)
                             (dwlh/generate-sync-library :typographies file-id state)]
            file-changes    [(dwlh/generate-sync-file :components file-id state)
                             (dwlh/generate-sync-file :colors file-id state)
                             (dwlh/generate-sync-file :typographies file-id state)]
            rchanges (d/concat []
                               (->> library-changes (remove nil?) (map first) (flatten))
                               (->> file-changes (remove nil?) (map first) (flatten)))
            uchanges (d/concat []
                               (->> library-changes (remove nil?) (map second) (flatten))
                               (->> file-changes (remove nil?) (map second) (flatten)))]
        ;; ===== Uncomment this to debug =====
        ;; (js/console.debug "rchanges" (clj->js rchanges))
        (rx/concat
          (rx/of (dm/hide-tag :sync-dialog))
          (when rchanges
            (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})))
          (when file-id
            (rp/mutation :update-sync
                         {:file-id (get-in state [:workspace-file :id])
                          :library-id file-id}))
          (when (some? library-changes)
            (rx/of (sync-file-2nd-stage file-id))))))))

(defn sync-file-2nd-stage
  "If some components have been modified, we need to launch another synchronization
  to update the instances of the changed components."
  ;; TODO: this does not work if there are multiple nested components. Only the
  ;;       first level will be updated.
  ;;       To solve this properly, it would be better to launch another sync-file
  ;;       recursively. But for this not to cause an infinite loop, we need to
  ;;       implement updated-at at component level, to detect what components have
  ;;       not changed, and then not to apply sync and terminate the loop.
  [file-id]
  (us/assert (s/nilable ::us/uuid) file-id)
  (ptk/reify ::sync-file-2nd-stage
    ptk/WatchEvent
    (watch [_ state stream]
      ;; ===== Uncomment this to debug =====
      ;; (js/console.info "##### SYNC-FILE" (str (or file-id "local")) "(2nd stage)")
      (let [[rchanges1 uchanges1] (dwlh/generate-sync-file :components nil state)
            [rchanges2 uchanges2] (dwlh/generate-sync-library :components file-id state)
            rchanges (d/concat rchanges1 rchanges2)
            uchanges (d/concat uchanges1 uchanges2)]
        (when rchanges
          ;; ===== Uncomment this to debug =====
          ;; (js/console.debug "rchanges" (clj->js rchanges))
          (rx/of (dwc/commit-changes rchanges uchanges {:commit-local? true})))))))

(def ignore-sync
  (ptk/reify ::sync-file
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-file :ignore-sync-until] (dt/now)))

    ptk/WatchEvent
    (watch [_ state stream]
      (rp/mutation :ignore-sync
                   {:file-id (get-in state [:workspace-file :id])
                    :date (dt/now)}))))

(defn notify-sync-file
  [file-id]
  (us/assert ::us/uuid file-id)
  (ptk/reify ::notify-sync-file
    ptk/WatchEvent
    (watch [_ state stream]
      (let [libraries-need-sync (filter #(> (:modified-at %) (:synced-at %))
                                        (vals (get state :workspace-libraries)))
            do-update #(do (apply st/emit! (map (fn [library]
                                                  (sync-file (:id library)))
                                                libraries-need-sync))
                           (st/emit! dm/hide))
            do-dismiss #(do (st/emit! ignore-sync)
                            (st/emit! dm/hide))]
        (rx/of (dm/info-dialog
                 (tr "workspace.updates.there-are-updates")
                 :inline-actions
                 [{:label (tr "workspace.updates.update")
                   :callback do-update}
                  {:label (tr "workspace.updates.dismiss")
                   :callback do-dismiss}]
                 :sync-dialog))))))

(defn add-typography
  ([typography] (add-typography typography true))
  ([typography edit?]
   (let [typography (update typography :id #(or % (uuid/next)))]
     (us/assert ::cp/typography typography)
     (ptk/reify ::add-typography
       ptk/WatchEvent
       (watch [_ state s]
         (let [rchg {:type :add-typography
                     :typography (assoc typography :ts (.now js/Date))}
               uchg {:type :del-typography
                     :id (:id typography)}]
           (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true})
                  #(cond-> %
                     edit?
                     (assoc-in [:workspace-local :rename-typography] (:id typography))))))))))

(defn update-typography
  [typography]
  (us/assert ::cp/typography typography)

  (ptk/reify ::update-typography
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :typographies (:id typography)])
            rchg {:type :mod-typography
                  :typography typography}
            uchg {:type :mod-typography
                  :typography prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true})
               (sync-file nil))))))

(defn delete-typography
  [id]
  (us/assert ::us/uuid id)
  (ptk/reify ::delete-typography
    ptk/WatchEvent
    (watch [_ state stream]
      (let [prev (get-in state [:workspace-data :typographies id])
            rchg {:type :del-typography
                  :id id}
            uchg {:type :add-typography
                  :typography prev}]
        (rx/of (dwc/commit-changes [rchg] [uchg] {:commit-local? true}))))))
