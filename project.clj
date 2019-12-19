(defproject com.sixthnormal/pullql "0.1.0"
  :description "A GraphQL-like DataScript query language."
  :license {:name "EPL-2.0"}
  :url "https://github.com/sixthnormal/pullql"
  :plugins [[lein-tools-deps "0.4.5"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :lein-tools-deps/config {:config-files ["deps.edn" :install :user :project]})
