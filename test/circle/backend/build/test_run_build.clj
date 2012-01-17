(ns circle.backend.build.test-run-build
  (:use midje.sweet)
  (:use [circle.backend.build.test-utils])
  (:use [circle.backend.action :only (defaction action)])
  (:use [circle.backend.build :only (build successful?)])
  (:use [circle.backend.build.run :only (run-build)])
  (:require [circle.model.project :as project])
  (:use [circle.backend.build.config :only (build-from-url)])
  (:require circle.system)
  (:require [somnium.congomongo :as mongo])
  (:use [arohner.utils :only (inspect)])
  (:use [circle.util.predicates :only (ref?)]))

(circle.db/init)
(ensure-test-user-and-project)
(ensure-test-build)

(defaction successful-action [act-name]
  {:name act-name}
  (fn [build]
    nil))

(defn successful-build []
  (minimal-build :actions [(successful-action "1")
                           (successful-action "2")
                           (successful-action "3")]))

(fact "successful build is successful"
  (let [build (run-build (successful-build))]
    build => ref?
    @build => map?
    (-> @build :action-results) => seq
    (-> @build :action-results (count)) => 3
    (for [res (-> @build :action-results)]
      (> (-> res :stop-time) (-> res :start-time)) => true)
    (successful? build) => truthy))

(fact "dummy project does not start nodes"
  (ensure-test-project)
  ;;; This should be using the empty template, which does not start nodes
  (-> "https://github.com/arohner/circle-dummy-project"
      (build-from-url)
      deref
      :actions
      (count)) => 0)

(fact "build of dummy project is successful"
  (-> "https://github.com/arohner/circle-dummy-project" (build-from-url) (run-build) (successful?)) => true)

(fact "builds insert into the DB"
  (let [build (run-build (successful-build))]
    (successful? build) => truthy
    (-> @build :_project_id) => truthy
    (-> @build :build_num) => integer?
    (-> @build :build_num) => pos?
    (let [builds (mongo/fetch :builds :where {:_id (-> @build :_id)})]
      (count builds) => 1)))

(fact "builds using the provided objectid"
  (let [build (run-build (successful-build) :id test-build-id)
        builds (mongo/fetch :builds :where {:_id test-build-id})]
    (count builds) => 1))

(fact "successive builds use incrementing build-nums"
  (let [first-build (run-build (successful-build))
        second-build (run-build (successful-build))]
    (> (-> @second-build :build_num) (-> @first-build :build_num)) => true))

(fact "running an inferred build with zero actions marks the project disabled"
  (let [build (minimal-build :actions [])
        _ (ensure-test-user-and-project)
        test-project (ensure-test-project)]
    (dosync
     (alter build assoc :_project_id (-> test-project :_id))) => anything
     (run-build build) => anything
     (-> (mongo/fetch-one :projects :where {:_id (-> test-project :_id)}) :state) => "disabled"))

(fact "running a disabled build"
  (let [build (minimal-build :actions [])
        _ (ensure-test-user-and-project)
        test-project (ensure-test-project)
        _ (project/set-uninferrable test-project)]
    (run-build build) => anything
    (-> @build :error_message) => string?
    (-> @build :stop_time) => truthy
    (provided
      (circle.backend.build.run/do-build* anything) => anything :times 0)))
