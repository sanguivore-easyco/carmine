(ns taoensso.carmine-v4.tests.main
  "High-level Carmine tests.
  These need an active Redis server."
  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [clojure.test        :as test :refer [deftest testing is]]
   [taoensso.encore     :as enc  :refer [throws?]]
   [taoensso.carmine-v4 :as car  :refer [wcar with-replies]]
   [taoensso.carmine-v4.resp :as resp]))

(comment
  (remove-ns      'taoensso.carmine-v4.tests.main)
  (test/run-tests 'taoensso.carmine-v4.tests.main)
  (core/run-all-carmine-tests))

;;;; TODO
;; - Re-enable tests, using new ns structure
;; - Isolated test db/keys
;; - Interactions between systems (read-opts, parsers, etc.)

(deftest ^:private _wcar-basics
  [(is (= (wcar {}                 (resp/ping))  "PONG"))
   (is (= (wcar {} {:as-vec? true} (resp/ping)) ["PONG"]))

   (is (= (wcar {} (resp/local-echo "hello")) "hello") "Local echo")

   (let [v1 (str (rand-int 1e6))]
     (is
       (= (wcar {}
            (resp/ping)
            (resp/rset "k1" v1)
            (resp/echo (wcar {} (resp/rget "k1")))
            (resp/rset "k1" "0"))

         ["PONG" "OK" v1 "OK"])

       "Flush triggered by `wcar` in `wcar`"))

   (let [v1 (str (rand-int 1e6))]
     (is
      (= (wcar {}
           (resp/ping)
           (resp/rset "k1" v1)
           (resp/echo         (with-replies (resp/rget "k1")))
           (resp/echo (str (= (with-replies (resp/rget "k1")) v1)))
           (resp/rset "k1" "0"))

        ["PONG" "OK" v1 "true" "OK"])

      "Flush triggered by `with-replies` in `wcar`"))

   (is (= (wcar {} (resp/ping) (wcar {}))      "PONG") "Parent replies not swallowed by `wcar`")
   (is (= (wcar {} (resp/ping) (with-replies)) "PONG") "Parent replies not swallowed by `with-replies`")

   (is (= (wcar {}
            (resp/rset "k1" "v1")
            (resp/echo
              (with-replies
                (car/skip-replies (resp/rset "k1" "v2"))
                (resp/echo
                  (with-replies (resp/rget "k1"))))))))

   (is (=
         (wcar {}
           (resp/ping)
           (resp/echo       (first (with-replies {:as-vec? true} (resp/ping))))
           (resp/local-echo (first (with-replies {:as-vec? true} (resp/ping)))))

         ["PONG" "PONG" "PONG"])

     "Nested :as-vec")])

;;;; Scratch

(comment ; TODO Testing v3 conn closing
  ;; TODO Make a test
  (def c (nconn))

  ;; Push
  (let [{:keys [in out]} c]
    (resp/with-replies in out false false
      (fn [] (resp/redis-call "lpush" "l1" "x"))))

  ;; Pop
  (future
    (let [{:keys [in out]} c
          reply
          (try
            (resp/with-replies in out false false
              (fn []
                (resp/redis-call "blpop" "l1" 3)))
            (catch Throwable t t))]
      (println "RESPONSE: " reply)))

  (let [{:keys [in out]} c]
    (resp/with-replies in out false false
      (fn []
        (resp/redis-call "blpop" "l1" "3"))))

  (v3-conns/close-conn c))

(comment
  (v3-protocol/with-context (nconn)
    (v3-protocol/with-replies
      (v3-cmds/enqueue-request 1 ["SET" "KX" "VY"])
      (v3-cmds/enqueue-request 1 ["GET" "KX"])))

  (let [c (nconn)] (.read (:in c))) ; Inherently blocking
  (let [c (nconn)] (v3-conns/close-conn c) (.read (:in c))) ; Closed
  )
