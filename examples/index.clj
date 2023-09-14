(ns examples.index
  {:nextjournal.clerk/visibility {:code :hide :result :hide}}
  (:require [nextjournal.clerk :as clerk]))

;; # Gluon Examples
(defn example [port]
  (clerk/html {::clerk/width :full} [:iframe.w-full {:src (str "http://localhost:" port)}]))

{:nextjournal.clerk/visibility {:result :show}}
(example 8888)
(example 8889)

