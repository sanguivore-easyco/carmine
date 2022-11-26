(ns taoensso.carmine-v4.sentinel
  "Private ns, implementation detail.

  Implementation of the Redis Sentinel protocol,
  Ref. https://redis.io/docs/reference/sentinel-clients/

  A set of Sentinel servers (usu. >= 3) basically provides a
  quorum mechanism to resolve the current master Redis server address
  for a given \"master name\" (service name):

    (fn resolve-redis-master-addr
      [master-name stateful-sentinel-addresses]) -> <redis-master-address>

  Requests to the service then go through an indirection step:
    request -> resolve -> master"

  {:author "Peter Taoussanis (@ptaoussanis)"}
  (:require
   [clojure.test    :as test :refer [deftest testing is]]
   [taoensso.encore :as enc  :refer [have have? throws?]]
   [taoensso.carmine-v4.utils :as utils]
   [taoensso.carmine-v4.conns :as conns]
   [taoensso.carmine-v4.resp  :as resp]
   [taoensso.carmine-v4.opts  :as opts])

  (:import
   [java.util.concurrent.atomic AtomicLong]))

(comment
  (remove-ns      'taoensso.carmine-v4.sentinel)
  (test/run-tests 'taoensso.carmine-v4.sentinel))

(enc/declare-remote
  ^:dynamic taoensso.carmine-v4/*conn-cbs*)

(alias 'core 'taoensso.carmine-v4)

;;;; Dev/test config

(defn- spit-sentinel-test-config
  [{:keys [n-sentinels first-sentinel-port master-name master-addr quorum]
    :or   {n-sentinels 2
           first-sentinel-port 26379
           master-name "my-master"
           master-addr ["127.0.0.1" 6379]
           quorum n-sentinels}}]

  (dotimes [idx n-sentinels]
    (let [[master-ip master-port] master-addr
          sentinel-port (+ first-sentinel-port idx)
          fname (str "sentinel" (inc idx) ".conf")

          content
          (format
            "# Redis Sentinel test config generated by Carmine
# Start Sentinel server with `redis-sentinel %1$s`

port %2$s

# sentinel monitor <master-group-name> <ip> <port> <quorum>
sentinel monitor %3$s %4$s %5$s %6$s
sentinel down-after-milliseconds %3$s 60000"

            fname
            sentinel-port
            master-name master-ip master-port
            quorum)]

      (spit fname content ))))

(comment (spit-sentinel-test-config {}))

;;;; Sentinel addrs maps
;; {<master-name> [[<sentinel-server-ip> <sentinel-server-port>] ...]}

(defn- remove-sentinel-addr [old-addrs addr]
  (let [addr (opts/parse-sock-addr addr)]
    (transduce (remove #(= % addr)) conj [] old-addrs)))

(defn- add-sentinel-addr->front [old-addrs addr]
  (let [addr (opts/parse-sock-addr addr)]
    (if (= (get old-addrs 0) addr)
      old-addrs
      (transduce (remove #(= % addr))
        conj [addr] old-addrs))))

(defn- add-sentinel-addrs->back [old-addrs addrs]
  (if (empty? addrs)
    old-addrs
    (let [old-addrs   (or  old-addrs [])
          old-addr?   (set old-addrs)]
      (transduce (comp (map opts/parse-sock-addr) (remove old-addr?))
        conj old-addrs addrs))))

(defn- clean-sentinel-addrs-map [addrs-map]
  (reduce-kv
    (fn [m master-name addrs]
      (assoc m (enc/as-qname master-name)
        (transduce (comp (map opts/parse-sock-addr) (distinct))
          conj [] addrs)))
    {} addrs-map))

(deftest ^:private _addr-utils
  [(let [sm (add-sentinel-addrs->back nil [["ip1" 1] ["ip2" "2"] ^{:server-name "server3"} ["ip3" 3]])
         sm (add-sentinel-addr->front sm  ["ip2" 2])
         sm (add-sentinel-addrs->back sm [["ip3" 3] ["ip6" 6]])]

     [(is (= sm [["ip2" 2] ["ip1" 1] ["ip3" 3] ["ip6" 6]]))
      (is (= (mapv opts/descr-sock-addr sm)
            [["ip2" 2] ["ip1" 1] ["ip3" 3 {:server-name "server3"}] ["ip6" 6]]))])

   (let [sm (add-sentinel-addrs->back nil [["ip4" 4] ["ip5" "5"]])
         sm (remove-sentinel-addr     sm   ["ip4" 4])]
     [(is (= sm [["ip5" 5]]))])

   (let [sm (clean-sentinel-addrs-map {:m1 [^:my-meta ["ip1" 1] ["ip1" "1"] ["ip2" "2"]]})]
     [(is (= sm {"m1" [["ip1" 1] ["ip2" 2]]}))
      (is (= (mapv opts/descr-sock-addr (get sm "m1"))
            [["ip1" 1 {:my-meta true}] ["ip2" 2]]))])])

;;;; SentinelSpec

(defprotocol ^:private ISentinelSpec
  "Internal protocol, not for public use or extension."
  (^:private    get-sentinel-addrs     [spec     master-name] [spec])
  (^:private update-sentinel-addrs!    [spec cbs master-name f])
  (^:private remove-sentinel-addr!     [spec cbs master-name addr])

  (^:private add-sentinel-addrs->back! [spec cbs master-name addrs])
  (^:private add-sentinel-addr->front! [spec cbs master-name addr])

  (^:private reset-master-addr!        [spec cbs master-name addr])

  (    get-master-addr [spec master-name] "Returns currently resolved master address, or nil.")
  (resolve-master-addr [spec master-name sentinel-opts]
    "Given a Redis master server name, returns [<master-ip> <master-port>]
    as reported by current Sentinel cluster consensus, or throws.

    Follows resolution procedure as per Sentinel spec,
    Ref. https://redis.io/docs/reference/sentinel-clients/

    Options will be the nested merge of the following in descending order:
      - `sentinel-opts` provided here.
      - `sentinel-opts` provided when creating `sentinel-spec`.
      - `*default-sentinel-opts*`.

    For options docs, see `*default-sentinel-opts*` docstring."))

(def ^:dynamic *mgr-cbs*
  "Private, implementation detail.
  Mechanism to allow ConnManagers to easily request cbs from
  a resolution that they've requested."
  nil)

(defn- inc-stat! [stats_ k1 k2]
  (swap! stats_
    (fn [m]
      (enc/update-in m [k1 k2]
        (fn [?n] (inc (or ?n 0)))))))

(comment (inc-stat! (atom {}) "foo" :k1))

(defn- sentinel-addrs-count [sentinel-addrs-map]
  (count (into #{} cat (vals sentinel-addrs-map))))

(comment (enc/qb 1e6 (sentinel-addrs-count {:a [[1] [2] [3]] :b [[1] [3] [4]]})))

(defn- kvs->map [x] (if (map? x) x (into {} (comp (partition-all 2)) x)))
(comment [(kvs->map {"a" "A" "b" "B"}) (kvs->map ["a" "A" "b" "B"])])

(deftype SentinelSpec
  [base-sentinel-opts
   sentinel-addrs-map_ ; {<master-name> [[<sentinel-ip> <sentinel-port>] ...]} delay
   resolved-addrs-map_ ; {<master-name> [<redis-ip> <redis-port>]}

   resolve-stats_      ; {<master-name>   {:keys [n-requests n-attempts n-successes n-errors n-changes]}}
   sentinel-stats_     ; {<sentinel-addr> {:keys [           n-attempts n-successes n-errors n-ignorant n-unreachable n-misidentified]}
   ]

  Object
  (toString [this] ; "SentinelSpec[3 masters, 6 sentinels]"
    (str "SentinelSpec["
      (do                   (count @resolved-addrs-map_)) " masters, "
      (sentinel-addrs-count (force @sentinel-addrs-map_)) " sentinels]"))

  clojure.lang.IDeref
  (deref [this]
    (let [sentinel-addrs-map (force @sentinel-addrs-map_)
          resolved-addrs-map        @resolved-addrs-map_]

      {:sentinel-opts      base-sentinel-opts
       :sentinel-addrs-map sentinel-addrs-map
       :resolved-addrs-map resolved-addrs-map
       :stats
       {:n-masters      (count                resolved-addrs-map)
        :n-sentinels    (sentinel-addrs-count sentinel-addrs-map)
        :resolve-stats  @resolve-stats_
        :sentinel-stats @sentinel-stats_}}))

  ISentinelSpec
  (get-sentinel-addrs     [_                     ]      (force @sentinel-addrs-map_))
  (get-sentinel-addrs     [_        master-name  ] (get (force @sentinel-addrs-map_) (enc/as-qname master-name)))
  (update-sentinel-addrs! [this cbs master-name f]
    (let [master-name (enc/as-qname master-name)
          [old-addrs-map_ new-addrs-map_]
          (swap-vals! sentinel-addrs-map_
            (fn [old-addrs-map_]
              (delay ; To minimize contention during expensive updates
                (let [old-addrs-map (force old-addrs-map_)
                      old-addrs     (get   old-addrs-map master-name)
                      new-addrs     (f     old-addrs)]
                  (assoc old-addrs-map master-name new-addrs)))))

          old (get (force old-addrs-map_) master-name)
          new (get (deref new-addrs-map_) master-name)]

      (if (= old new)
        false
        (do
          (utils/cb-notify!
            (get core/*conn-cbs* :on-sentinels-change)
            (get       *mgr-cbs* :on-sentinels-change)
            (get            cbs  :on-sentinels-change)
            (delay
              {:cbid :on-sentinels-change
               :master-name    master-name
               :sentinel-spec  this
               :sentinel-opts  base-sentinel-opts
               :sentinel-addrs {:old old, :new new}}))
          true))))

  (add-sentinel-addrs->back! [this cbs master-name addrs] (update-sentinel-addrs! this cbs master-name #(add-sentinel-addrs->back % addrs)))
  (add-sentinel-addr->front! [this cbs master-name addr]  (update-sentinel-addrs! this cbs master-name #(add-sentinel-addr->front % addr)))
  (remove-sentinel-addr!     [this cbs master-name addr]  (update-sentinel-addrs! this cbs master-name #(remove-sentinel-addr     % addr)))

  (  get-master-addr         [this     master-name] (get @resolved-addrs-map_ (enc/as-qname master-name)))
  (reset-master-addr!        [this cbs master-name addr]
    (let [master-name (enc/as-qname master-name)
          new-addr    (opts/parse-sock-addr addr)
          old-addr    (enc/reset-val! resolved-addrs-map_ master-name new-addr)]

      (if (= new-addr old-addr)
        false
        (do
          (inc-stat! resolve-stats_ master-name :n-changes)
          (utils/cb-notify!
            (get core/*conn-cbs* :on-resolve-change)
            (get       *mgr-cbs* :on-resolve-change)
            (get            cbs  :on-resolve-change)
            (delay
              {:cbid :on-resolve-change
               :master-name   master-name
               :master-addr   {:old old-addr :new new-addr}
               :sentinel-spec this
               :sentinel-opts base-sentinel-opts}))
          true))))

  (resolve-master-addr [this master-name sentinel-opts]
    (let [t0 (System/currentTimeMillis)

          master-name    (enc/as-qname master-name)
          sentinel-addrs (get @sentinel-addrs-map_ master-name)

          sentinel-opts
          (opts/parse-sentinel-opts :with-dynamic-default
            base-sentinel-opts sentinel-opts)

          {:keys [conn-opts cbs add-missing-sentinels?]}
          sentinel-opts]

      (if (empty? sentinel-addrs)
        (do
          (inc-stat! resolve-stats_ master-name :n-errors)
          (utils/cb-notify-and-throw! :on-resolve-error
            (get core/*conn-cbs*      :on-resolve-error)
            (get       *mgr-cbs*      :on-resolve-error)
            (get            cbs       :on-resolve-error)
            (ex-info "[Carmine] [Sentinel] No Sentinel server addresses configured for requested master"
              {:eid :carmine.sentinel/no-sentinel-addrs-in-spec
               :master-name   master-name
               :sentinel-spec this
               :sentinel-opts sentinel-opts}
              (Exception. "No Sentinel server addresses in spec"))))

        (let [n-attempts* (java.util.concurrent.atomic.AtomicLong. 0)

              attempt-log_  (volatile! []) ; [<debug-entry> ...]
              error-counts_ (volatile! {}) ; {<sentinel-addr> {:keys [unreachable ignorant misidentified]}}
              record-error!
              (fn [sentinel-addr t0-attempt error-kind ?data]

                (inc-stat! sentinel-stats_ sentinel-addr :n-errors)
                (inc-stat! sentinel-stats_ sentinel-addr
                  (case error-kind
                    :ignorant      :n-ignorant
                    :unreachable   :n-unreachable
                    :misidentified :n-misidentified
                                   :n-other-errors))

                ;; Add entry to attempt log
                (let [attempt-ms (- (System/currentTimeMillis) t0-attempt)]
                  (vswap! attempt-log_ conj
                    (assoc
                      (conj
                        {:attempt       (.get n-attempts*)
                         :sentinel-addr sentinel-addr
                         :error         error-kind}
                        ?data)
                      :attempt-ms attempt-ms)))

                ;; Increment counter for error kind
                (vswap! error-counts_
                  (fn [m]
                    (enc/update-in m [sentinel-addr error-kind]
                      (fn [?n] (inc ^long (or ?n 0)))))))

              ;; All sentinel addrs reported during resolution process
              reported-sentinel-addrs_ (volatile! #{}) ; #{[ip port]}
              complete!
              (fn [?sentinel-addr ?master-addr ?error]
                (update-sentinel-addrs! this cbs master-name
                  (fn [addrs]
                    (cond-> addrs
                      ?master-addr           (add-sentinel-addr->front ?master-addr)
                      add-missing-sentinels? (add-sentinel-addrs->back @reported-sentinel-addrs_))))

                (if-let [error ?error]
                  (do
                    (inc-stat! resolve-stats_ master-name :n-errors)
                    (utils/cb-notify-and-throw! :on-resolve-error
                      (get core/*conn-cbs*      :on-resolve-error)
                      (get       *mgr-cbs*      :on-resolve-error)
                      (get            cbs       :on-resolve-error)
                      error))

                  (let [sentinel-addr (opts/parse-sock-addr ?sentinel-addr)
                        master-addr   (opts/parse-sock-addr ?master-addr)]
                    (inc-stat! sentinel-stats_ sentinel-addr :n-successes)
                    (inc-stat! resolve-stats_  master-name   :n-successes)
                    (utils/cb-notify!
                      (get core/*conn-cbs* :on-resolve-success)
                      (get       *mgr-cbs* :on-resolve-success)
                      (get            cbs  :on-resolve-success)
                      (delay
                        {:cbid :on-resolve-success
                         :master-name   master-name
                         :master-addr   master-addr
                         :sentinel-spec this
                         :sentinel-opts sentinel-opts
                         :ms-elapsed (- (System/currentTimeMillis) t0)}))

                    (reset-master-addr! this cbs master-name master-addr)
                    (do                                      master-addr))))]

          (loop [n-retries 0]

            (let [t0-attempt (System/currentTimeMillis)
                  [?sentinel-addr ?master-addr] ; ?[<addr> <addr>]
                  (reduce
                    ;; Try each known sentinel addr, sequentially
                    (fn [acc sentinel-addr]
                      (.incrementAndGet n-attempts*)
                      (inc-stat! resolve-stats_  master-name   :n-attempts)
                      (inc-stat! sentinel-stats_ sentinel-addr :n-attempts)
                      (let [[ip port] sentinel-addr
                            [?master-addr ?sentinels-info]
                            (case ip
                              "unreachable"   [::unreachable                 nil]
                              "misidentified" [["simulated-misidentified" 0] nil]
                              "ignorant"      nil
                              (try
                                (conns/with-conn
                                  ;; (conns/new-conn ip port conn-opts)
                                  (conns/get-conn (assoc conn-opts :server [ip port]) false true)
                                  (fn [_ in out]
                                    (resp/with-replies in out :natural-reads :as-vec
                                      (fn []

                                        ;; Does this sentinel know the master?
                                        (resp/redis-call "SENTINEL" "get-master-addr-by-name"
                                          master-name)

                                        (when add-missing-sentinels?
                                          ;; Ask sentinel to report on other known sentinels
                                          (resp/redis-call "SENTINEL" "sentinels" master-name))))))

                                (catch Throwable _
                                  [::unreachable nil])))]

                        (when-let [sentinels-info ?sentinels-info] ; [<sentinel1-info> ...]
                          (enc/run!
                            (fn [info] ; Info may be map (RESP3) or kvseq (RESP2)
                              (let [info (kvs->map info)]
                                (enc/when-let [ip   (get info "ip")
                                               port (get info "port")]
                                  (vswap! reported-sentinel-addrs_ conj [ip port]))))
                            sentinels-info))

                        (enc/cond
                          (vector?    ?master-addr)                        (reduced [sentinel-addr (opts/parse-sock-addr ?master-addr)])
                          (nil?       ?master-addr)               (do (record-error! sentinel-addr t0-attempt :ignorant    nil) acc)
                          (identical? ?master-addr ::unreachable) (do (record-error! sentinel-addr t0-attempt :unreachable nil) acc))))

                    nil sentinel-addrs)]

              (if-let [[sentinel-addr master-addr]
                       (enc/when-let [sentinel-addr ?sentinel-addr
                                      master-addr   ?master-addr]
                         (let [role
                               (let [[ip port] master-addr
                                     reply
                                     (try
                                       (conns/with-conn
                                         ;; (conns/new-conn ip port conn-opts)
                                         (conns/get-conn (assoc conn-opts :server [ip port]) false true)
                                         (fn [_ in out]
                                           (resp/with-replies in out :natural-reads :as-vec
                                             (fn [] (resp/redis-call "ROLE")))))
                                       (catch Throwable _ nil))]

                                 (when (vector? reply) (get reply 0)))]

                           ;; Confirm that master designated by sentinel actually identifies itself as master
                           (if (= role "master")
                             [sentinel-addr master-addr]
                             (do
                               (record-error! sentinel-addr t0-attempt :misidentified
                                 {:resolved-to master-addr :actual-role role})
                               false))))]

                (complete! sentinel-addr master-addr nil)

                (let [{:keys [timeout-ms retry-delay-ms]} sentinel-opts
                      elapsed-ms  (- (System/currentTimeMillis) t0)
                      retry-at-ms (+ elapsed-ms retry-delay-ms)]

                  (if (> retry-at-ms timeout-ms)
                    (do
                      (vswap! attempt-log_ conj
                        [:timeout
                         (str
                           "(" elapsed-ms " elapsed + " retry-delay-ms " delay = " retry-at-ms
                           ") > " timeout-ms " timeout")])

                      (complete! nil nil
                        (ex-info "[Carmine] [Sentinel] Timed out while trying to resolve requested master"
                          {:eid :carmine.sentinel/resolve-timeout
                           :master-name     master-name
                           :sentinel-spec   this
                           :sentinel-opts   sentinel-opts
                           :sentinel-errors @error-counts_
                           :n-attempts      (.get n-attempts*)
                           :n-retries       n-retries
                           :ms-elapsed      (- (System/currentTimeMillis) t0)
                           :attempt-log     @attempt-log_})))
                    (do
                      (vswap! attempt-log_ conj [:retry-after-sleep retry-delay-ms])
                      (Thread/sleep retry-delay-ms)
                      (recur (inc n-retries)))))))))))))

(let [ns *ns*] (defmethod print-method SentinelSpec [^SentinelSpec spec ^java.io.Writer w] (.write w (str "#" ns "." spec))))

(defn ^:public sentinel-spec?
  "Returns true iff given argument is a Carmine SentinelSpec."
  [x] (instance? SentinelSpec x))

(defn ^:public sentinel-spec
  "Given a Redis Sentinel server addresses map of form
    {<master-name> [[<sentinel-server-ip> <sentinel-server-port>] ...]},
  returns a new stateful SentinelSpec for use in `conn-opts`.

    (def my-sentinel-spec
      \"Stateful Redis Sentinel server spec. Will be kept
       automatically updated by Carmine.\"
      (sentinel-spec
        {:caching       [[\"192.158.1.38\" 26379] ...]
         :message-queue [[\"192.158.1.38\" 26379] ...]}))
      => stateful SentinelSpec

  For options docs, see `*default-sentinel-opts*` docstring."

  ([sentinel-addrs-map              ] (sentinel-spec sentinel-addrs-map nil))
  ([sentinel-addrs-map sentinel-opts]
   (SentinelSpec.
     (have [:or nil? map?] sentinel-opts)
     (atom (clean-sentinel-addrs-map (have map? sentinel-addrs-map)))
     (atom {})
     (atom {})
     (atom {}))))

(deftest ^:private _spec-protocol
  [(is (enc/submap? @(sentinel-spec {:m1  [["ip1" "1"] ["ip1" "1"] ["ip2" "2"]]})
         {:sentinel-addrs-map       {"m1" [["ip1"  1]              ["ip2"  2]]}}))

   (let [sp (sentinel-spec {:m1 [["ip1" "1"] ["ip1" "1"] ["ip2" "2"]]})]
     (add-sentinel-addr->front! sp nil :m1 ["ip3" "3"])
     (add-sentinel-addrs->back! sp nil :m1 [["ip4" "4"] ["ip5" "5"]])
     (remove-sentinel-addr!     sp nil :m1 ["ip5" "5"])
     (is (enc/submap?          @sp
           {:sentinel-addrs-map {"m1" [["ip3" 3] ["ip1" 1] ["ip2" 2] ["ip4" 4]]}})))])

(comment
  ;; Use ip e/o #{"unreachable" "ignorant" "misidentified"} to simulate errors
  (resolve-master-addr {}
    {"my-master" [^:my-meta ["ignorant" #_"misidentified" #_"127.0.0.1" 26379]]}
    "my-master")

  (core/wcar {:ip "127.0.0.1" :port "6379"}
    (core/redis-call "ROLE"))

  (core/wcar {:port 26379}
    (core/redis-call "SENTINEL" "get-master-addr-by-name" "my-master"))

  (core/wcar {:port 26379}
    (core/redis-call "HELLO" 3)
    (binding [core/*keywordize-maps?* false]
      (core/redis-call "SENTINEL" "sentinels" "my-master"))))
