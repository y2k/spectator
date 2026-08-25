(ns build (:require [make :as m]))

(deps {:make "0.6.0"})

(m/makefile
 {:target "js"
  :dirs [{:path "src" :build-dir ".wrangler/bin/src"}
         {:path "src" :build-dir ".wrangler/bin/test"}
         {:path "test" :build-dir ".wrangler/bin/test"}]})
