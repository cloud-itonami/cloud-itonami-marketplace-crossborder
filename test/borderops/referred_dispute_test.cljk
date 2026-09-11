(ns borderops.referred-dispute-test
  "The receiving half of the support bridge (ADR-2607264000): a contact
  taken by `cloud-itonami-isic-8220` arriving here as a referral."
  (:require [borderops.advisor :as advisor]
            [borderops.governor :as governor]
            [borderops.operation :as operation]
            [borderops.store :as store]
            [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketplace.crossborder :as cb]
            [marketplace.support :as support]))

(def ctx {:actor-id "border-actor" :phase 3})

(defn- referral [& {:as over}]
  (support/referral
   (merge {:id "ref-1" :ticket-id "tkt-9" :order "ord-1"
           :buyer "buyer-1" :seller "merchant.alpha"
           :reason :not-received
           :claimed-by-caller "3週間待っても届かない"
           :agent "agent-07" :agent-note "追跡番号は発行済みだが更新なし"
           :referred-at "2026-06-01T00:00:00Z"}
          over)))

(defn- advise [st r]
  (advisor/-advise (advisor/mock-advisor) st
                   {:op :open-referred-dispute :patch {:referral r}}))

(defn- check [st r]
  (governor/check {:op :open-referred-dispute} ctx (advise st r) st))

(deftest a-clean-referral-opens-a-dispute
  (let [st (store/seed-db)
        v (check st (referral))]
    (is (false? (:hard? v)) (pr-str (:violations v)))
    (is (true? (:ok? v))
        "recording that someone complained asserts nothing about who is right")))

(deftest the-referral-rule-is-re-checked-on-THIS-side
  (testing "the two actors are separately deployable and separately
            forkable — a marketplace operator may accept referrals from a
            call centre they do not run, so trusting the sender's
            validation would make this side's guarantee only as strong as
            whoever is on the other end of the wire"
    (doseq [k [:referral/outcome :referral/fault :referral/liable :fault]]
      (let [v (check (store/seed-db) (assoc (referral) k :seller))]
        (is (true? (:hard? v)) (str k))
        (is (some #{:referral-carries-a-verdict} (mapv :rule (:violations v))) (str k))))))

(deftest an-untraceable-referral-is-refused-here-too
  (doseq [[k v* expected] [[:ticket-id "" :missing-ticket]
                           [:agent "" :missing-agent]
                           [:reason :vibes :unknown-reason]]]
    (let [v (check (store/seed-db) (referral k v*))]
      (is (true? (:hard? v)) (str k))
      (is (some #{expected} (mapv :rule (:violations v))) (str k)))))

(deftest a-missing-referral-is-refused
  (let [st (store/seed-db)
        v (governor/check {:op :open-referred-dispute} ctx
                          {:op :open-referred-dispute :effect :propose
                           :confidence 0.9 :value {}}
                          st)]
    (is (true? (:hard? v)))
    (is (some #{:referral-missing} (mapv :rule (:violations v))))))

;; ───────────────────── end to end ─────────────────────

(deftest the-bridge-runs-through-the-compiled-graph
  (let [st (store/seed-db)
        actor (operation/build st)
        r (referral)
        result (g/run* actor
                       {:request {:op :open-referred-dispute :patch {:referral r}}
                        :context ctx}
                       {:thread-id "t-ref"})]
    (is (= :done (:status result)))
    (is (= :commit (:disposition (:state result))))
    (let [d (store/dispute st "disp.ref-1")]
      (is (some? d))
      (is (= :opened (:dispute/state d)))
      (is (= :support-referral (:dispute/source d)))
      (is (= "tkt-9" (:dispute/ticket d)))
      (testing "the call is already in the evidence log as the buyer's"
        (let [[e] (:dispute/evidence d)]
          (is (= :buyer (:evidence/party e)))
          (is (= :support-contact (:evidence/kind e)))))
      (testing "and nothing about it is decided"
        (is (nil? (:dispute/decision d)))
        (is (false? (:dispute/adjudicated-by-actor? d)))))))

(deftest a-verdict-carrying-referral-never-reaches-the-store
  (let [st (store/seed-db)
        actor (operation/build st)
        result (g/run* actor
                       {:request {:op :open-referred-dispute
                                  :patch {:referral (assoc (referral) :fault :seller)}}
                        :context ctx}
                       {:thread-id "t-bad"})]
    (is (= :done (:status result)) "not :interrupted — no human is asked")
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/all-disputes st)))))

(deftest a-referred-dispute-still-needs-a-human-to-resolve
  (testing "the bridge moves a complaint; it does not shorten the path to
            a decision"
    (let [st (store/seed-db)
          actor (operation/build st)]
      (g/run* actor {:request {:op :open-referred-dispute :patch {:referral (referral)}}
                     :context ctx}
              {:thread-id "t-r"})
      (let [d (store/dispute st "disp.ref-1")
            under (cb/advance-dispute d :under-review)]
        (is (nil? (cb/record-decision under {:outcome :buyer-favoured :decided-by ""})))
        (is (some? (cb/record-decision under {:outcome :buyer-favoured
                                              :decided-by "ops-01" :decided-at "t"})))))))

(deftest the-existing-ops-are-unchanged
  (testing "this was an additive change"
    (doseq [op [:propose-hs-classification :quote-landed-cost :open-dispute
                :add-dispute-evidence :flag-crossborder-concern]]
      (is (contains? governor/allowed-ops op) (str op)))
    (is (= #{:propose-hs-classification :flag-crossborder-concern}
           governor/always-escalate-ops)
        "the always-escalate set is exactly what it was")))
