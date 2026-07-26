(ns borderops.store
  "SSoT for the marketplace cross-border actor -- duty rates, HS
  classifications, and disputes.

  Directories, keyed by STRING ids (never keywords):

    rates           [destination hs6] -> operator-supplied duty rate,
                    built through `marketplace.crossborder/rate-table`
                    which REFUSES a row with no `:rate/source` or no
                    `:rate/as-of`. An unattributed, undated rate cannot
                    be audited and mis-quotes every order that hits it.
    classifications product id -> the HS heading a HUMAN accepted. The
                    actor's own proposals never land here; only an
                    approved one does.
    quotes          quote id -> a landed-cost estimate that was recorded.
    disputes        dispute id -> dispute record with its evidence log.

  ## This store ships no tariff data

  The demo rates below are TEST FIXTURES with `:rate/source
  \"test-fixture\"`, not real tariff schedules. Real rates are
  jurisdiction-, product-, treaty- and date-specific, they change
  constantly, and a wrong rate is a financially consequential
  fabrication: a buyer quoted a landed cost that under-states duty pays
  the difference at the border. An operator supplies their own table.

  The ledger stays append-only."
  (:require [marketplace.crossborder :as cb]))

(defprotocol Store
  (rates [s] "The operator's rate table, indexed by [destination hs6].")
  (rate-row [s destination hs6])
  (classification [s product-id] "The HS heading a human accepted, or nil.")
  (all-classifications [s])
  (quote-record [s quote-id])
  (all-quotes [s])
  (dispute [s dispute-id])
  (all-disputes [s])
  (ledger [s])
  (crossborder-log [s])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-rates [s rows]))

;; ----------------------------- demo data -----------------------------

(def demo-rate-rows
  "TEST FIXTURES, not real tariff data -- see the namespace docstring.
  `:rate/source \"test-fixture\"` says so on every row, and
  `rate-table` would refuse them if it did not.

    JPN/220210  5% duty, 10% consumption tax, de minimis 10,000
    USA/220210  0% / 0%, no de minimis -- proves a zero rate still
                COMPUTES rather than looking like a missing rate
    (DEU is deliberately absent, so the uncomputable path is reachable)"
  [{:destination "JPN" :hs6 "220210" :ad-valorem-bps 500 :vat-bps 1000
    :de-minimis-minor 10000 :source "test-fixture" :as-of "2026-01-01"}
   {:destination "USA" :hs6 "220210" :ad-valorem-bps 0 :vat-bps 0
    :source "test-fixture" :as-of "2026-01-01"}
   {:destination "JPN" :hs6 "851713" :ad-valorem-bps 0 :vat-bps 1000
    :de-minimis-minor 10000 :source "test-fixture" :as-of "2026-01-01"}])

(defn demo-data []
  {:rates (cb/rate-table (map cb/duty-rate demo-rate-rows))
   :classifications {}
   :quotes {}
   :disputes {}})

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (rates [_] (:rates @a))
  (rate-row [_ dest hs6] (get-in @a [:rates [dest hs6]]))
  (classification [_ pid] (get-in @a [:classifications pid]))
  (all-classifications [_] (:classifications @a))
  (quote-record [_ qid] (get-in @a [:quotes qid]))
  (all-quotes [_] (sort-by key (:quotes @a)))
  (dispute [_ did] (get-in @a [:disputes did]))
  (all-disputes [_] (sort-by :dispute/id (vals (:disputes @a))))
  (ledger [_] (:ledger @a))
  (crossborder-log [_] (:crossborder-log @a))
  (commit-record! [_ record]
    (swap! a update :crossborder-log conj record)
    (let [{:keys [op value]} record]
      (case op
        ;; Only an APPROVED classification lands. The actor's own
        ;; proposal is a candidate until a human accepts it, which is why
        ;; :propose-hs-classification always escalates.
        :propose-hs-classification
        (when-let [p (:proposal value)]
          (swap! a assoc-in [:classifications (:proposal/product p)]
                 {:classification/product (:proposal/product p)
                  :classification/hs6 (:proposal/hs6 p)
                  :classification/basis (:proposal/basis p)
                  :classification/accepted-by (:approved-by record)
                  :classification/accepted? true}))

        :quote-landed-cost
        (when-let [q (:quote value)]
          (swap! a assoc-in [:quotes (:quote-id value)] q))

        (:open-dispute :open-referred-dispute)
        (when-let [d (:dispute value)]
          (swap! a assoc-in [:disputes (:dispute/id d)] d))

        :add-dispute-evidence
        (swap! a update-in [:disputes (:dispute-id value)]
               (fn [d] (when d (cb/add-evidence d (:evidence value)))))

        nil))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-rates [s rows] (swap! a assoc :rates (cb/rate-table (map cb/duty-rate rows))) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :ledger [] :crossborder-log []))))

(defn mem-store [m]
  (->MemStore (atom (merge {:rates {} :classifications {} :quotes {} :disputes {}
                            :ledger [] :crossborder-log []}
                           m))))

;; ----------------------------- derived views -----------------------------

(defn landed-cost
  "Estimate a landed cost against THIS store's rate table.

  Returns `:landed/computable? false` naming the missing input when no
  rate exists, rather than a plausible-looking guess -- see
  `marketplace.crossborder/landed-cost`."
  [s shipment]
  (cb/landed-cost shipment (rates s)))
