(ns borderops.governor
  "CrossBorderGovernor -- the independent compliance layer for customs
  classification, landed-cost quoting and dispute intake.

  The advisor has no notion of whether a rate exists for the border it
  is quoting, whether the HS heading it picked is even well-formed,
  whether its own `:effect` claims a direct actuation, or whether it has
  drifted into claiming to have FILED a declaration or DECIDED a
  dispute. So this MUST be a separate system able to *reject* a proposal
  and fall back to HOLD.

  ## Two things this actor is defined not to do

  **It never classifies.** Customs classification is a legal act with
  real liability. `:propose-hs-classification` produces a CANDIDATE with
  its basis and confidence, for a human to accept or reject, and always
  escalates. Only an accepted proposal lands in the store's
  classification directory.

  **It never adjudicates.** Disputes are INTAKE ONLY (ADR-2607264000
  D5). This actor records that a dispute exists and what each side
  filed. There is deliberately no op that decides one, and
  `marketplace.crossborder` deliberately contains no function that reads
  the evidence and returns an outcome -- `record-decision` only records
  what a NAMED HUMAN decided. The fleet-wide invariant ISIC 4791
  established holds: no actor adjudicates.

  Five HARD checks, ALL permanent, un-overridable by any human approval:

    1. Uncomputable quote  -- a `:quote-landed-cost` whose
                              (destination, HS heading) pair has no rate
                              in the operator's table. Refusing is the
                              whole point: a buyer quoted a landed cost
                              that under-states duty pays the difference
                              at the border, so a plausible-looking guess
                              is worse than an honest 'cannot compute'.
    2. Malformed HS        -- delegated to
                              `marketplace.crossborder/hs-proposal-errors`
                              (6-digit subheading, known basis,
                              confidence in [0,1], a named proposer, and
                              never self-declared as adjudicated).
    3. Malformed dispute   -- delegated to `dispute-errors`, which
                              includes the permanent refusal of any
                              record claiming an ACTOR adjudicated.
    4. Effect not :propose -- any other value is a claim to directly
                              actuate outside governance.
    5. Scope exclusion     -- any claim to have filed/finalized a customs
                              declaration or ruled on a dispute, plus any
                              op outside the closed allowlist.

  Two ESCALATE (SOFT) gates:
    - LLM confidence below the floor.
    - `:propose-hs-classification` and `:flag-crossborder-concern`
      ALWAYS escalate. `borderops.phase` keeps both out of every phase's
      `:auto` set independently -- two layers, not one."
  (:require [borderops.store :as store]
            [clojure.string :as str]
            [marketplace.crossborder :as cb]
            [marketplace.support :as support]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist. CRITICAL: no op that files a customs
  declaration or decides a dispute is EVER a member -- such an op would
  be a permanent scope violation, not merely un-implemented."
  #{:propose-hs-classification :quote-landed-cost :open-dispute
    ;; ADR-2607264000: the receiving half of the support bridge. A
    ;; contact taken by `cloud-itonami-isic-8220` arrives here as a
    ;; `marketplace.support` referral and becomes an ordinary dispute --
    ;; with the referral's own no-verdict rule re-checked on THIS side,
    ;; because a bridge is only as honest as its far end.
    :open-referred-dispute
    :add-dispute-evidence :flag-crossborder-concern})

(def always-escalate-ops
  #{:propose-hs-classification :flag-crossborder-concern})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as claiming an
  authority this actor lacks.

  CRITICAL: every term is phrased as the COMPLETED action ('filed the
  customs declaration'), never a bare noun like 'customs declaration',
  'classification' or 'dispute' -- a bare noun would match inside this
  actor's own legitimate proposals (whose whole job is to talk about
  classifications and disputes) and self-block the happy path. See
  `borderops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  ["filed the customs declaration" "filed the declaration"
   "submitted the customs declaration" "lodged the customs declaration"
   "finalized the customs declaration" "finalised the customs declaration"
   "classified the goods as" "have classified the goods"
   "determined the tariff classification" "ruled on the classification"
   "decided the dispute" "ruled on the dispute" "resolved the dispute in"
   "found in favour of the buyer" "found in favour of the seller"
   "found in favor of the buyer" "found in favor of the seller"
   "awarded the refund" "denied the claim"
   "通関申告を提出した" "通関申告を確定した" "関税分類を確定した"
   "品目分類を確定した" "紛争を裁定した" "紛争を解決した"
   "買い手の主張を認めた" "売り手の主張を認めた"])

;; ----------------------------- checks -----------------------------

(defn- uncomputable-quote-violations
  "A `:quote-landed-cost` must actually be computable against the
  operator's rate table.

  This is the one check that exists because a WRONG NUMBER is worse than
  NO NUMBER. `marketplace.crossborder/landed-cost` already refuses to
  guess -- it returns `:landed/computable? false` naming the missing
  input. This governor turns that into a HARD block so the honest
  'cannot compute' can never be recorded as if it were a quote."
  [proposal st]
  (when (= :quote-landed-cost (:op proposal))
    (let [q (get-in proposal [:value :quote])]
      (cond
        (not (map? q))
        [{:rule :quote-missing :detail "見積の草案がない"}]

        (not (:landed/computable? q))
        [{:rule :uncomputable-quote
          :detail (str "税率表に該当行が無いため見積を出せない: "
                       (pr-str (:landed/missing q))
                       " -- 推測値の提示は永久に禁止")}]

        ;; Re-derive against the store rather than trusting the drafted
        ;; quote: an advisor cannot manufacture a rate by asserting one.
        (nil? (store/rate-row st
                              (get-in proposal [:value :shipment :destination])
                              (get-in proposal [:value :shipment :hs6])))
        [{:rule :rate-not-in-table
          :detail "提示された税率が運用者の税率表に存在しない"}]))))

(defn- hs-proposal-violations
  [proposal]
  (when (= :propose-hs-classification (:op proposal))
    (let [p (get-in proposal [:value :proposal])]
      (if-not (map? p)
        [{:rule :hs-proposal-missing :detail "分類候補の草案がない"}]
        (when-let [errs (seq (cb/hs-proposal-errors p))]
          (mapv (fn [e] {:rule (:crossborder.error/code e)
                         :detail (or (:crossborder.error/detail e)
                                     (name (:crossborder.error/code e)))})
                errs))))))

(defn- dispute-violations
  [proposal]
  (when (= :open-dispute (:op proposal))
    (let [d (get-in proposal [:value :dispute])]
      (if-not (map? d)
        [{:rule :dispute-missing :detail "紛争レコードの草案がない"}]
        (when-let [errs (seq (cb/dispute-errors d))]
          (mapv (fn [e] {:rule (:crossborder.error/code e)
                         :detail (or (:crossborder.error/detail e)
                                     (name (:crossborder.error/code e)))})
                errs))))))

(defn- referral-violations
  "For `:open-referred-dispute` ONLY: the incoming referral must be
  structurally sound and must NOT carry a verdict.

  Re-checked here even though `cloud-itonami-isic-8220`'s governor
  already checks it. The two actors are separately deployable and
  separately forkable -- a marketplace operator may accept referrals
  from a call centre they do not run -- so trusting the sender's
  validation would make this side's guarantee only as strong as
  whoever is on the other end of the wire."
  [proposal]
  (when (= :open-referred-dispute (:op proposal))
    (let [r (get-in proposal [:value :referral])]
      (if-not (map? r)
        [{:rule :referral-missing :detail "照会レコードがない"}]
        (when-let [errs (seq (support/referral-errors r))]
          (mapv (fn [e] {:rule (:support.error/code e)
                         :detail (or (:support.error/detail e)
                                     (name (:support.error/code e)))})
                errs))))))

(defn- unknown-dispute-violations
  [proposal st]
  (when (= :add-dispute-evidence (:op proposal))
    (let [did (get-in proposal [:value :dispute-id])]
      (when-not (and did (store/dispute st did))
        [{:rule :dispute-unknown
          :detail (str (or did "(dispute-id missing)") " という紛争は存在しない")}]))))

(defn- effect-not-propose-violations [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "通関申告の確定・関税分類の確定・紛争の裁定など確定行為に触れる提案は永久に禁止"}])))

(defn check
  "Censors a CrossBorderAdvisor proposal. Returns
  {:ok? bool :violations [..] :confidence c :escalate? bool
   :high-stakes? bool :hard? bool}."
  [_request _context proposal store]
  (let [hard (into []
                   (concat (uncomputable-quote-violations proposal store)
                           (hs-proposal-violations proposal)
                           (dispute-violations proposal)
                           (referral-violations proposal)
                           (unknown-dispute-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
