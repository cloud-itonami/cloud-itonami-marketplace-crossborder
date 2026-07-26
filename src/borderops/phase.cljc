(ns borderops.phase
  "Phase 0->3 staged rollout for the marketplace cross-border actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- disputes may be opened, every write
                                   needs human approval.
    Phase 2  assisted-quoting   -- adds landed-cost quoting and dispute
                                   evidence, still approval-gated.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                   `:quote-landed-cost`, `:open-dispute`
                                   and `:add-dispute-evidence` may
                                   auto-commit.

  `:propose-hs-classification` and `:flag-crossborder-concern` are
  deliberately ABSENT from every phase's `:auto` set, INCLUDING phase 3
  -- a permanent structural fact, not a rollout milestone still to come.

  ## Why quoting may auto-commit but classifying may not

  Both produce numbers a buyer will see, so the distinction is worth
  stating rather than leaving as an inconsistency:

    - A landed-cost quote is an ESTIMATE derived mechanically from the
      operator's own rate table, and it is stamped `:landed/estimate?
      true`. When the table has no row the governor HARD-blocks it, so
      the only quotes that can auto-commit are ones the operator's own
      data already determined. The actor adds no judgement.
    - An HS classification is a LEGAL ACT with real liability. Choosing
      a heading is a judgement about what the goods *are*, and getting
      it wrong is the operator's problem at the border. Only a human may
      accept one, which is why the store's classification directory is
      written solely from an approved proposal.

  Dispute intake auto-commits because recording that someone complained
  asserts nothing about who is right -- and nothing anywhere in this
  actor ever decides that (ADR-2607264000 D5).

  `borderops.governor`'s own `always-escalate-ops` enforces the same
  invariant independently -- two layers, not one, agree on this."
  (:require [borderops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

;; NOTE the invariant: `:propose-hs-classification` and
;; `:flag-crossborder-concern` are members of `write-ops` (governor-gated
;; like any write) but are NEVER members of any phase's `:auto` set
;; below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                 :auto #{}}
   1 {:label "assisted-intake"  :writes #{:open-dispute}      :auto #{}}
   2 {:label "assisted-quoting" :writes #{:open-dispute :quote-landed-cost
                                          :add-dispute-evidence} :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:quote-landed-cost :open-dispute :add-dispute-evidence}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE
    (:phase-approval), even if the governor was clean."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a CrossBorderGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
