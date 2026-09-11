(ns borderops.governor-test
  (:require [borderops.advisor :as advisor]
            [borderops.governor :as governor]
            [borderops.store :as store]
            [clojure.test :refer [deftest is testing]]
            [marketplace.crossborder :as cb]))

(def ctx {:actor-id "border-actor" :phase 3})
(def product "gtin.05449000000996")

(defn- db [] (store/seed-db))

(defn- advise [st op & [patch]]
  (advisor/-advise (advisor/mock-advisor) st {:op op :patch (or patch {})}))

(defn- check [st op & [patch]]
  (governor/check {:op op} ctx (advise st op patch) st))

;; ───────────────────── a wrong number is worse than no number ─────────────────────

(deftest a-quote-with-no-rate-row-is-a-hard-block
  (testing "DEU is deliberately absent from the table. A buyer quoted a
            landed cost that under-states duty pays the difference at the
            border, so a plausible-looking guess is worse than an honest
            'cannot compute'"
    (let [v (check (db) :quote-landed-cost
                   {:goods-minor 50000 :shipping-minor 2000
                    :destination "DEU" :hs6 "220210" :currency "JPY"})]
      (is (true? (:hard? v)))
      (is (some #{:uncomputable-quote} (mapv :rule (:violations v)))))))

(deftest a-computable-quote-passes-and-carries-its-provenance
  (let [st (db)
        p (advise st :quote-landed-cost
                  {:goods-minor 50000 :shipping-minor 2000 :insurance-minor 1000
                   :destination "JPN" :hs6 "220210" :currency "JPY"})
        v (governor/check {:op :quote-landed-cost} ctx p st)
        q (get-in p [:value :quote])]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v)))
    (testing "the arithmetic is the library's, not the model's"
      (is (= 53000 (:landed/customs-value-minor q)))
      (is (= 2650 (:landed/duty-minor q)) "5% of 53000")
      (is (= 5565 (:landed/vat-minor q)) "10% of duty-inclusive 55650")
      (is (= 61215 (:landed/total-minor q))))
    (testing "and it is labelled an estimate carrying its rate's provenance"
      (is (true? (:landed/estimate? q)))
      (is (= "test-fixture" (:landed/rate-source q)))
      (is (= "2026-01-01" (:landed/rate-as-of q))))))

(deftest a-zero-rate-still-computes
  (testing "USA is 0%/0% — that must produce a quote, not look like a
            missing rate"
    (let [st (db)
          p (advise st :quote-landed-cost
                    {:goods-minor 50000 :destination "USA" :hs6 "220210" :currency "USD"})
          v (governor/check {:op :quote-landed-cost} ctx p st)]
      (is (false? (:hard? v)))
      (is (= 50000 (:landed/total-minor (get-in p [:value :quote])))))))

(deftest de-minimis-is-applied-and-recorded
  (let [st (db)
        p (advise st :quote-landed-cost
                  {:goods-minor 5000 :destination "JPN" :hs6 "220210" :currency "JPY"})
        q (get-in p [:value :quote])]
    (is (true? (:landed/de-minimis-applied? q)))
    (is (= 0 (:landed/duty-minor q)))
    (is (= 5000 (:landed/total-minor q)))))

(deftest an-advisor-cannot-manufacture-a-rate-by-asserting-one
  (testing "the governor re-derives against the store's table"
    (let [st (db)
          forged {:op :quote-landed-cost :effect :propose :confidence 0.99
                  :value {:shipment {:destination "DEU" :hs6 "220210"}
                          :quote {:landed/computable? true
                                  :landed/total-minor 1
                                  :landed/duty-minor 0
                                  :landed/vat-minor 0}}}
          v (governor/check {:op :quote-landed-cost} ctx forged st)]
      (is (true? (:hard? v)))
      (is (some #{:rate-not-in-table} (mapv :rule (:violations v)))))))

;; ───────────────────────── classification ─────────────────────────

(deftest an-hs-candidate-always-escalates
  (testing "customs classification is a legal act, not a model output"
    (let [v (check (db) :propose-hs-classification
                   {:product product :hs6 "220210" :basis :broker-supplied
                    :confidence 0.99})]
      (is (false? (:hard? v)) (pr-str (:violations v)))
      (is (true? (:high-stakes? v)))
      (is (false? (:ok? v)) "no confidence value can make it automatic"))))

(deftest a-malformed-hs-candidate-is-a-hard-block
  (doseq [[patch expected]
          [[{:product product :hs6 "22021" :basis :prior-ruling} :invalid-hs6]
           [{:product product :hs6 "220210" :basis :vibes} :unknown-basis]
           [{:product product :hs6 "220210" :basis :prior-ruling :confidence 1.5} :invalid-confidence]]]
    (let [v (check (db) :propose-hs-classification patch)]
      (is (true? (:hard? v)) (pr-str patch))
      (is (some #{expected} (mapv :rule (:violations v))) (pr-str patch)))))

(deftest a-candidate-claiming-to-be-adjudicated-is-refused
  (let [st (db)
        p {:op :propose-hs-classification :effect :propose :confidence 0.9
           :value {:proposal (assoc (cb/hs-proposal {:product product :hs6 "220210"
                                                     :basis :prior-ruling :confidence 0.9
                                                     :proposed-by "a"})
                                    :proposal/adjudicated? true)}}
        v (governor/check {:op :propose-hs-classification} ctx p st)]
    (is (true? (:hard? v)))
    (is (some #{:adjudicating-proposal} (mapv :rule (:violations v))))))

;; ───────────────────────── disputes ─────────────────────────

(deftest dispute-intake-is-clean-and-asserts-nothing-about-who-is-right
  (let [st (db)
        p (advise st :open-dispute {:id "disp-1" :order "ord-1" :buyer "b" :seller "s"
                                    :reason :not-received :narrative "届かない"
                                    :opened-at "2026-06-01T00:00:00Z"})
        v (governor/check {:op :open-dispute} ctx p st)
        d (get-in p [:value :dispute])]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v)) "recording that someone complained may be automatic")
    (is (false? (:dispute/adjudicated-by-actor? d)))
    (is (true? (:dispute/non-adjudicating d)))))

(deftest a-dispute-with-an-unknown-reason-is-a-hard-block
  (let [v (check (db) :open-dispute {:id "disp-1" :order "ord-1" :reason :vibes
                                     :opened-at "t"})]
    (is (true? (:hard? v)))
    (is (some #{:unknown-dispute-reason} (mapv :rule (:violations v))))))

(deftest a-record-claiming-an-actor-adjudicated-is-permanently-refused
  (let [st (db)
        p {:op :open-dispute :effect :propose :confidence 0.9
           :value {:dispute (assoc (cb/dispute {:id "d" :order "o" :reason :damaged
                                                :opened-at "t"})
                                   :dispute/adjudicated-by-actor? true)}}
        v (governor/check {:op :open-dispute} ctx p st)]
    (is (true? (:hard? v)))
    (is (some #{:actor-adjudicated-dispute} (mapv :rule (:violations v))))))

(deftest evidence-on-an-unknown-dispute-is-a-hard-block
  (let [v (check (db) :add-dispute-evidence
                 {:dispute-id "disp-nope" :party :buyer :kind :photo :ref "r"})]
    (is (true? (:hard? v)))
    (is (some #{:dispute-unknown} (mapv :rule (:violations v))))))

(deftest no-op-in-the-allowlist-decides-a-dispute
  (testing "the fleet-wide invariant: no actor adjudicates"
    (is (not (contains? governor/allowed-ops :resolve-dispute)))
    (is (not (contains? governor/allowed-ops :decide-dispute)))
    (is (not (contains? governor/allowed-ops :file-customs-declaration)))
    (let [v (governor/check {:op :resolve-dispute} ctx
                            {:op :resolve-dispute :effect :propose :confidence 0.99}
                            (db))]
      (is (true? (:hard? v)))
      (is (some #{:op-not-allowed} (mapv :rule (:violations v)))))))

;; ───────────────────────── structural checks ─────────────────────────

(deftest effect-must-be-propose
  (let [st (db)
        v (governor/check {:op :open-dispute} ctx
                          (assoc (advise st :open-dispute
                                         {:id "d" :order "o" :reason :damaged :opened-at "t"})
                                 :effect :commit)
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:effect-not-propose} (mapv :rule (:violations v))))))

(deftest scope-exclusion-blocks-filing-and-ruling-claims
  (let [st (db)
        p (advisor/infer st {:op :open-dispute
                             :patch {:id "d" :order "o" :reason :damaged :opened-at "t"}
                             :out-of-scope? true})
        v (governor/check {:op :open-dispute} ctx p st)]
    (is (true? (:hard? v)))
    (is (some #{:scope-excluded} (mapv :rule (:violations v))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every legitimate proposal talks about classifications, customs
            and disputes — the excluded terms are phrased as COMPLETED
            actions so the happy path never self-blocks"
    (let [st (db)]
      (store/commit-record! st {:op :open-dispute
                                :value {:dispute (cb/dispute {:id "disp-1" :order "o"
                                                              :reason :damaged
                                                              :opened-at "t"})}})
      (doseq [[op patch]
              [[:propose-hs-classification {:product product :hs6 "220210" :basis :prior-ruling}]
               [:quote-landed-cost {:goods-minor 5000 :destination "JPN" :hs6 "220210"
                                    :currency "JPY"}]
               [:open-dispute {:id "disp-2" :order "o" :reason :customs-cost-unexpected
                               :opened-at "t"}]
               [:add-dispute-evidence {:dispute-id "disp-1" :party :buyer :kind :photo :ref "r"}]
               [:flag-crossborder-concern {:subject product :concern "関税分類が不明確"}]]]
        (let [v (check st op patch)]
          (is (not-any? #{:scope-excluded} (mapv :rule (:violations v))) (str op)))))))

(deftest crossborder-concern-always-escalates
  (let [v (check (db) :flag-crossborder-concern
                 {:subject product :concern "x" :confidence 0.99})]
    (is (true? (:high-stakes? v)))
    (is (false? (:ok? v)))))

(deftest low-confidence-escalates
  (let [st (db)
        v (governor/check {:op :open-dispute} ctx
                          (assoc (advise st :open-dispute
                                         {:id "d" :order "o" :reason :damaged :opened-at "t"})
                                 :confidence 0.2)
                          st)]
    (is (false? (:hard? v)))
    (is (true? (:escalate? v)))))

;; ───────────────────────── rate hygiene ─────────────────────────

(deftest an-unattributed-rate-cannot-enter-the-table
  (testing "one bad row mis-quotes every order that hits it"
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/mem-store {:rates (cb/rate-table
                                           [(cb/duty-rate {:destination "JPN" :hs6 "220210"
                                                           :ad-valorem-bps 500 :vat-bps 1000})])})))))
