(ns borderops.operation-graph-test
  "Integration tests for `borderops.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end.

  The two headline tests are the two things this actor is defined not to
  do: `classification-can-only-be-accepted-by-a-human` and
  `an-uncomputable-quote-never-reaches-a-human`."
  (:require [borderops.operation :as operation]
            [borderops.store :as store]
            [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]))

(def ^:private op-context {:actor-id "border-01" :phase 3})
(def product "gtin.05449000000996")

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(deftest a-computable-quote-auto-commits
  (testing "a quote derived mechanically from the operator's own rate
            table adds no judgement, so it may run unattended"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-quote"
                       {:op :quote-landed-cost
                        :patch {:goods-minor 50000 :shipping-minor 2000
                                :destination "JPN" :hs6 "220210" :currency "JPY"
                                :quote-id "q-1"}})]
      (is (= :done (:status result)))
      (is (= :commit (:disposition (:state result))))
      (let [q (store/quote-record st "q-1")]
        (is (some? q))
        (is (true? (:landed/estimate? q)) "always labelled an estimate")
        ;; CIF 52000 (no insurance on this shipment) -> duty 5% = 2600
        ;; -> VAT 10% of the duty-inclusive 54600 = 5460 -> total 60060.
        (is (= 52000 (:landed/customs-value-minor q)))
        (is (= 2600 (:landed/duty-minor q)))
        (is (= 5460 (:landed/vat-minor q)))
        (is (= 60060 (:landed/total-minor q)))))))

(deftest an-uncomputable-quote-never-reaches-a-human
  (testing "offering a 'cannot compute' for approval would invite someone
            to wave through a number that does not exist"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-deu"
                       {:op :quote-landed-cost
                        :patch {:goods-minor 50000 :destination "DEU" :hs6 "220210"
                                :currency "JPY" :quote-id "q-deu"}})]
      (is (= :done (:status result)) "not :interrupted")
      (is (= :hold (:disposition (:state result))))
      (is (nil? (store/quote-record st "q-deu")) "no number was recorded")
      (is (some #{:uncomputable-quote}
                (map :rule (:violations (first (store/ledger st)))))))))

(deftest classification-can-only-be-accepted-by-a-human
  (testing "customs classification is a legal act — the classification
            directory is written solely from an approved proposal"
    (let [st (store/seed-db)
          actor (operation/build st)
          held (exec actor "t-hs"
                     {:op :propose-hs-classification
                      :patch {:product product :hs6 "220210"
                              :basis :broker-supplied :confidence 0.95}})]
      (is (= :interrupted (:status held)))
      (is (nil? (store/classification st product)) "nothing classified yet")
      (let [approved (g/run* actor {:approval {:status :approved :by "customs-01"}}
                             {:thread-id "t-hs" :resume? true})]
        (is (= :commit (:disposition (:state approved))))
        (let [c (store/classification st product)]
          (is (= "220210" (:classification/hs6 c)))
          (is (true? (:classification/accepted? c))))))))

(deftest a-rejected-classification-never-lands
  (let [st (store/seed-db)
        actor (operation/build st)
        _held (exec actor "t-hs-rej"
                    {:op :propose-hs-classification
                     :patch {:product product :hs6 "220210" :basis :sibling-product}})
        rejected (g/run* actor {:approval {:status :rejected :by "customs-01"}}
                         {:thread-id "t-hs-rej" :resume? true})]
    (is (= :hold (:disposition (:state rejected))))
    (is (nil? (store/classification st product)))))

(deftest a-malformed-classification-hard-holds
  (let [st (store/seed-db)
        actor (operation/build st)
        result (exec actor "t-hs-bad"
                     {:op :propose-hs-classification
                      :patch {:product product :hs6 "22021" :basis :prior-ruling}})]
    (is (= :done (:status result)) "not :interrupted")
    (is (= :hold (:disposition (:state result))))
    (is (some #{:invalid-hs6} (map :rule (:violations (first (store/ledger st))))))))

(deftest dispute-intake-auto-commits-and-evidence-appends
  (testing "recording that someone complained asserts nothing about who is
            right, so it may be automatic"
    (let [st (store/seed-db)
          actor (operation/build st)
          opened (exec actor "t-disp"
                       {:op :open-dispute
                        :patch {:id "disp-1" :order "ord-1" :buyer "b" :seller "s"
                                :reason :not-received :narrative "届かない"
                                :opened-at "2026-06-01T00:00:00Z"}})]
      (is (= :commit (:disposition (:state opened))))
      (is (= :opened (:dispute/state (store/dispute st "disp-1"))))

      (exec actor "t-ev1" {:op :add-dispute-evidence
                           :patch {:dispute-id "disp-1" :party :buyer
                                   :kind :photo :ref "r1" :filed-at "t1"}})
      (exec actor "t-ev2" {:op :add-dispute-evidence
                           :patch {:dispute-id "disp-1" :party :seller
                                   :kind :tracking :ref "r2" :filed-at "t2"}})
      (let [d (store/dispute st "disp-1")]
        (is (= 2 (count (:dispute/evidence d))))
        (is (= [:buyer :seller] (mapv :evidence/party (:dispute/evidence d)))
            "append-only: what each side filed cannot be edited after the fact"))

      (testing "and the dispute is still un-decided — nothing here decides one"
        (is (= :opened (:dispute/state (store/dispute st "disp-1"))))
        (is (nil? (:dispute/decision (store/dispute st "disp-1"))))))))

(deftest crossborder-concern-escalates-and-threads-the-real-proposal
  (let [distinctive (str "TEST-CONCERN-" (rand-int 1000000000))
        st (store/seed-db)
        actor (operation/build st)
        held (exec actor "t-concern"
                   {:op :flag-crossborder-concern
                    :patch {:subject product :concern distinctive}})]
    (is (= :interrupted (:status held)))
    (let [approved (g/run* actor {:approval {:status :approved :by "customs-01"}}
                           {:thread-id "t-concern" :resume? true})]
      (is (= :done (:status approved)))
      (is (= distinctive (:concern (:payload (first (store/crossborder-log st)))))))))

(deftest phase-gates-are-wired-into-the-compiled-graph
  (testing "phase 1 has not enabled quoting yet"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-phase1"
                       {:op :quote-landed-cost
                        :patch {:goods-minor 5000 :destination "JPN" :hs6 "220210"
                                :currency "JPY"}}
                       (assoc op-context :phase 1))]
      (is (= :hold (:disposition (:state result))))
      (is (= :phase-disabled (:phase-reason (first (store/ledger st)))))))
  (testing "phase 0 writes nothing"
    (let [st (store/seed-db)
          actor (operation/build st)
          result (exec actor "t-phase0"
                       {:op :open-dispute
                        :patch {:id "d" :order "o" :reason :damaged :opened-at "t"}}
                       (assoc op-context :phase 0))]
      (is (= :hold (:disposition (:state result))))
      (is (empty? (store/crossborder-log st))))))
