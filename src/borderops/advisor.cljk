(ns borderops.advisor
  "CrossBorderAdvisor -- the *contained intelligence node* for the
  marketplace cross-border actor.

  It drafts exactly six kinds of proposal from a closed allowlist:
  proposing an HS classification candidate, quoting a landed cost,
  opening a dispute, opening one from a support referral, adding
  evidence to one, and flagging a cross-border concern.

  CRITICAL: every proposal's `:effect` is always `:propose`; every output
  is censored downstream by `borderops.governor`.

  Two things the advisor is structurally prevented from faking:

    - **The landed cost is not its opinion.** `quote-landed-cost` calls
      `marketplace.crossborder/landed-cost` against the operator's rate
      table, which returns `:landed/computable? false` when no rate
      exists. The advisor cannot fill that gap with a plausible number,
      and the governor independently re-checks the table.
    - **The classification is not its decision.** `hs-proposal` builds a
      CANDIDATE stamped `:proposal/adjudicated? false`, and the governor
      rejects any proposal that claims otherwise.

  Like every sibling actor's advisor this is a deterministic mock so the
  actor graph runs offline. In production this calls a real LLM (or a
  customs broker's API) with the same proposal shape."
  (:require [borderops.store :as store]
            [marketplace.crossborder :as cb]
            [marketplace.support :as support]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn- propose-hs
  "Draft a CANDIDATE HS subheading. ALWAYS escalates -- customs
  classification is a legal act, not a model output."
  [_st {:keys [patch]}]
  (let [p (cb/hs-proposal {:product (:product patch)
                           :hs6 (:hs6 patch)
                           :basis (or (:basis patch) :sibling-product)
                           :confidence (or (:confidence patch) 0.74)
                           :rationale (:rationale patch)
                           :proposed-by (or (:proposed-by patch) "crossborder-advisor")})]
    {:op      :propose-hs-classification
     :subject (:product patch)
     :summary (str (:product patch) " の HS 分類候補 " (:hs6 patch) " を提示（人間の受理待ち）")
     :rationale "類似品目からの分類候補の提示のみ。品目分類の確定は税関と人間の権限であり行わない。"
     :cites   (vec (keep identity [(:product patch) (:hs6 patch)]))
     :effect  :propose
     :value   {:proposal p}
     :confidence (:proposal/confidence p)}))

(defn- propose-quote
  "Draft a landed-cost estimate. The numbers come from the operator's
  rate table via `marketplace.crossborder/landed-cost`; when the table
  has no row, the drafted quote says so and the governor HARD-blocks it
  rather than letting an honest 'cannot compute' be recorded as a quote."
  [st {:keys [patch]}]
  (let [shipment (select-keys patch [:goods-minor :shipping-minor :insurance-minor
                                     :destination :hs6 :currency])
        q (store/landed-cost st shipment)]
    {:op      :quote-landed-cost
     :subject (:hs6 patch)
     :summary (if (:landed/computable? q)
                (str (:destination patch) " 向け着地コスト見積: 合計 "
                     (:landed/total-minor q) " " (:currency patch)
                     "（関税 " (:landed/duty-minor q) " / 付加価値税 " (:landed/vat-minor q) "）")
                (str (:destination patch) " 向け着地コストは算出不能（税率表に該当行なし）"))
     :rationale "運用者の税率表に基づく見積のみ。実際の課税額は税関が決定する。該当税率が無い場合は推測せず算出不能として返す。"
     :cites   (vec (keep identity [(:hs6 patch) (:landed/rate-source q)]))
     :effect  :propose
     :value   {:quote-id (or (:quote-id patch) (str "q-" (:hs6 patch) "-" (:destination patch)))
               :shipment shipment
               :quote q}
     :confidence (if (:landed/computable? q) 0.9 0.3)}))

(defn- propose-dispute
  "Open a dispute. INTAKE ONLY -- this asserts that someone complained,
  never that they were right."
  [_st {:keys [patch]}]
  (let [d (cb/dispute {:id (:id patch) :order (:order patch)
                       :buyer (:buyer patch) :seller (:seller patch)
                       :reason (:reason patch) :narrative (:narrative patch)
                       :opened-at (:opened-at patch)})]
    {:op      :open-dispute
     :subject (:id patch)
     :summary (str (:id patch) " の紛争を受付: " (pr-str (:reason patch)))
     :rationale "買い手からの申し立て事実の受付記録のみ。どちらの主張が正しいかの判断は行わない。"
     :cites   (vec (keep identity [(:order patch) (:buyer patch) (:seller patch)]))
     :effect  :propose
     :value   {:dispute d}
     :confidence 0.92}))

(defn- propose-evidence
  [_st {:keys [patch]}]
  {:op      :add-dispute-evidence
   :subject (:dispute-id patch)
   :summary (str (:dispute-id patch) " に " (pr-str (:party patch)) " 側の証跡を追加")
   :rationale "各当事者が提出した証跡の追記のみ。証跡の評価や裁定は行わない。"
   :cites   [(:dispute-id patch)]
   :effect  :propose
   :value   {:dispute-id (:dispute-id patch)
             :evidence (select-keys patch [:party :kind :ref :filed-at :note])}
   :confidence 0.9})

(defn- propose-concern
  [_st {:keys [patch]}]
  {:op      :flag-crossborder-concern
   :subject (:subject patch)
   :summary (str (:subject patch) " の越境取引に関する懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale "観察された越境上の懸念事実の報告のみ。分類の確定や紛争の裁定は行わず、常に人間の確認を要する。"
   :cites   [(str (:subject patch))]
   :effect  :propose
   :value   patch
   :confidence (or (:confidence patch) 0.8)})

(defn- propose-referred-dispute
  "Open a dispute from a support referral.

  `marketplace.support/open-with-evidence` builds the dispute AND files
  the support contact as the buyer's first evidence, so the call the
  complaint came from is in the same append-only log as everything else
  either side files. It returns nil for a verdict-carrying referral, and
  the governor refuses one independently."
  [_st {:keys [patch]}]
  (let [r (:referral patch)
        d (support/open-with-evidence r)]
    {:op      :open-referred-dispute
     :subject (:referral/id r)
     :summary (str "応対 " (:referral/ticket r) " からの照会を紛争として受付: "
                   (pr-str (:referral/reason r)))
     :rationale "応対記録からの紛争受付のみ。申し立て内容の当否は判断しない。"
     :cites   (vec (keep identity [(:referral/ticket r) (:referral/order r)]))
     :effect  :propose
     :value   {:referral r :dispute d}
     :confidence 0.9}))

(defn infer
  [st {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :open-referred-dispute     (propose-referred-dispute st request)
                   :propose-hs-classification (propose-hs st request)
                   :quote-landed-cost         (propose-quote st request)
                   :open-dispute              (propose-dispute st request)
                   :add-dispute-evidence      (propose-evidence st request)
                   :flag-crossborder-concern  (propose-concern st request)
                   {})]
    ;; Test hook: inject scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Clear before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str
              " -- actually filed the customs declaration and decided the dispute")
      proposal)))

(defn trace [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :subject    (:subject proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request]
      (infer store request))))
