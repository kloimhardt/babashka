(ns babashka.impl.reitit
  {:no-doc true}
  (:require [reitit.ring :as ring]
            [ring.middleware.defaults :as rmd]
            [ring.util.response :as response]
            [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'reitit.ring nil))

#_(def reitit-ring-namespace
  { 'ring-handler (copy-var ring/ring-handler tns)
    'router (copy-var ring/router tns)
    'routes (copy-var ring/routes tns)
    'create-resource-handler (copy-var ring/create-resource-handler tns)
   })

(def ring-middleware-defaults-namespace
  { 'wrap-defaults (copy-var rmd/wrap-defaults tns)
    ;;'api-defaults (copy-var rmd/api-defaults tns)
   })

(def ring-util-response-namespace
  {;;'response (copy-var response/response tns)
   ;;'resource-data (copy-var response/resource-data tns)
   ;;'header (copy-var response/header tns)
   })

;; none 67850600
;; all 106431008
;; 'ring-handler 'router 'api-defaults 'header 102855080
;; 'resource-data 'response 'wrap-defaults 'create-resource-handler 'routes 106435104
;; all ring, no reitit; 'header 'resource-data 'response 'api-defaults 'wrap-defaults 106431048
;; 'api-defaults 67920248
;; 'api-defaults 'wrap-defaults 106439208
;; 'api-defaults 'response 64848152
;; 'api-defaults 'response 'header 67907960
;; 'api-defaults 'response 'header 'resource-data 67916160
