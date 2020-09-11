(ns babashka.impl.reitit
  {:no-doc true}
  (:require [reitit.ring :as ring]
            [ring.middleware.defaults :as rmd]
            [ring.util.response :as response]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'reitit.ring nil))

(def reitit-ring-namespace
  {'ring-handler (copy-var ring/ring-handler tns)
   'routes (copy-var ring/routes tns)
   'create-resource-handler (copy-var ring/create-resource-handler tns)})

(def ring-middleware-defaults-namespace
  {'wrap-defaults (copy-var rmd/wrap-defaults tns)
   'api-defaults (copy-var rmd/api-defaults tns)})

(def ring-util-response-namespace
  {'response (copy-var response/response tns)
   'header (copy-var response/header tns)})
