# cloud-itonami-marketplace-crossborder

Open Business Blueprint (implemented actor): **what an item really costs
to land across a border, and what to do when the buyer says it went
wrong — without any automated party filing a declaration or deciding a
dispute.**

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime — here it is **CrossBorderAdvisor ⊣ CrossBorderGovernor**.
Landed-cost arithmetic, HS proposal validation and the dispute record
come from
[`kotoba-lang/marketplace`](https://github.com/kotoba-lang/marketplace).
Design record:
[ADR-2607264000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607264000-marketplace-federated-commerce-layer.edn).

## This repository ships no tariff data

Real duty rates are jurisdiction-, product-, treaty- and date-specific,
they change constantly, and **a wrong rate is a financially
consequential fabrication**: a buyer quoted a landed cost that
under-states duty pays the difference at the border.

So every rate is an operator input, and a row without a
`:rate/source` and an `:rate/as-of` is **refused** — an unattributed,
undated rate cannot be audited and mis-quotes every order that hits it.
The rates in `demo-rate-rows` are test fixtures and say
`"test-fixture"` on every row.

When no rate exists for a (destination, HS heading) pair, the quote
comes back `:landed/computable? false` naming the missing input, and the
governor turns that into a **HARD block** so an honest "cannot compute"
can never be recorded as if it were a quote. The refusal routes straight
to `:hold` — offering it for approval would invite someone to wave
through a number that does not exist.

```clojure
;; JPN/220210 at 5% duty + 10% consumption tax, CIF 53,000
{:landed/customs-value-minor 53000
 :landed/duty-minor 2650
 :landed/vat-minor 5565        ; on the duty-inclusive base…
 :landed/vat-base :duty-inclusive   ; …and the assumption is recorded, not inferred
 :landed/total-minor 61215
 :landed/estimate? true             ; the border decides the real number
 :landed/rate-source "test-fixture" :landed/rate-as-of "2026-01-01"}
```

An advisor cannot manufacture a rate by asserting one: the governor
re-derives against the store's table
(`an-advisor-cannot-manufacture-a-rate-by-asserting-one`).

## Two things this actor is defined not to do

**It never classifies.** Customs classification is a legal act with real
liability — choosing a heading is a judgement about what the goods *are*,
and getting it wrong is the operator's problem at the border.
`:propose-hs-classification` produces a candidate stamped
`:proposal/adjudicated? false`, always escalates, and the store's
classification directory is written **solely** from an approved
proposal. A candidate claiming to be adjudicated is permanently refused.

**It never adjudicates.** Disputes are intake only. This actor records
that a dispute exists and what each side filed, append-only. There is no
op that decides one, `marketplace.crossborder` deliberately contains no
function that reads evidence and returns an outcome, and
`record-decision` only records what a **named human** decided. This
preserves the fleet-wide invariant
[ISIC 4791](https://github.com/cloud-itonami/cloud-itonami-isic-4791)
established and ADR-2607264000 D5 keeps: *no actor adjudicates.*

`no-op-in-the-allowlist-decides-a-dispute` asserts that
`:resolve-dispute`, `:decide-dispute` and `:file-customs-declaration`
are not in the allowlist — the absence is tested, not just intended.

## Five HARD checks (permanent, un-overridable)

| Check | What it catches |
|---|---|
| **Uncomputable quote** | a quote for a (destination, HS) pair with no rate in the operator's table |
| **Malformed HS** | bad subheading, unknown basis, confidence outside [0,1], no named proposer, or a self-declared adjudication |
| **Malformed dispute** | unknown reason, or any record claiming an *actor* adjudicated |
| **Effect not `:propose`** | a proposal claiming to directly actuate |
| **Scope exclusion** | any claim to have filed/finalized a declaration or ruled on a dispute; any op outside the allowlist |

## Why quoting may auto-commit but classifying may not

Both produce numbers a buyer sees, so the distinction is worth stating:

- A **quote** is an estimate derived mechanically from the operator's own
  rate table, stamped `:landed/estimate? true`. Where the table is
  silent the governor blocks it, so the only quotes that auto-commit are
  ones the operator's own data already determined. The actor adds no
  judgement.
- A **classification** is a legal act. Only a human accepts one.

Dispute intake auto-commits because recording that someone complained
asserts nothing about who is right.

```bash
clojure -M:dev:run   # a real quote, a refused guess, a human-accepted classification
clojure -M:test      # 27 tests, 93 assertions
clojure -M:lint
```

## Rollout phases

| Phase | Writes | Auto-commits |
|---|---|---|
| 0 read-only | — | — |
| 1 assisted-intake | `:open-dispute` | — |
| 2 assisted-quoting | + `:quote-landed-cost` `:add-dispute-evidence` | — |
| 3 supervised-auto | all | `:quote-landed-cost` `:open-dispute` `:add-dispute-evidence` |

`:propose-hs-classification` and `:flag-crossborder-concern` never appear
in the right-hand column, at any phase.
