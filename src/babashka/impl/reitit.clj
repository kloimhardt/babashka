(ns babashka.impl.reitit
  {:no-doc true}
  (:require [reitit.ring :as ring]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'reitit.ring nil))

(def reitit-ring-namespace
  {'ring-handler (copy-var ring/ring-handler tns)
   'routes (copy-var ring/routes tns)
   'create-resource-handler (copy-var ring/create-resource-handler tns)})
