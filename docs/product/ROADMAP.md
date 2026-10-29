# Roadmap

This document is the **phase sequence and sequencing rationale**.

The full per-phase engineering plan (objective, capabilities, contexts, dependencies,
architecture/data/API/event/security/observability/testing/failure work, reconciliation
implications, deliverables, exit criteria, risks, and explicit out-of-scope) lives in
[`docs/project/DELIVERY_PLAN.md`](../project/DELIVERY_PLAN.md).

Gate definitions and the phase status model live in
[`docs/project/PHASE_GATES.md`](../project/PHASE_GATES.md).

---

## Phase Sequence

| # | Phase | Primary Outcome |
|---|-------|-----------------|
| 0 | Domain and Architecture Foundation | Buildable modular-monolith skeleton, financial kernel, platform seams |
| 1 | Identity and Customer Foundation | Party/Customer, authentication, authorization, session, audit |
| 2 | KYC/KYB and Consent | Verification lifecycle, screening adapters, consent lifecycle |
| 3 | Accounts and Financial Ledger | Chart of accounts, double-entry ledger, balances, holds |
| 4 | Internal Transfers | First end-to-end money movement on the ledger |
| 5 | Payment Infrastructure | Payment intent/attempt, provider adapters, auth/capture, refunds, webhooks |
| 6 | Checkout and Merchant Platform | Merchants, checkout sessions, fees, merchant payouts |
| 7 | Cards, Wallets, A2A and Instant Payments | Multi-rail abstraction, disputes/chargebacks |
| 8 | Settlement and Reconciliation | Settlement ingestion, matching, breaks, suspense, investigation |
| 9 | FX and Cross-Border Payments | Quotes, rate locks, multi-currency conversion, cross-border workflow |
| 10 | Credit Decisioning | Credit profile, bureau adapters, versioned policy, explainable decisions |
| 11 | Lending | Applications, offers, disbursement (to the wallet and external accounts), schedules, accrual, repayment, revolving credit lines, delinquency, lending capital |
| 12 | BNPL | Pay-in-4 and pay-in-N at checkout, the merchant paid upfront net of fee from lending capital, latest-instalment-first refunds, disputes under one bound, the three-way proof |
| 13 | Risk, Fraud and AML | Signals, rules, decisions, cases, ongoing monitoring |
| 14 | Accounting and Financial Reporting | The GL as a versioned mapping over the one journal, accounting periods with a posting cutoff the ledger enforces and a four-eyes close, the period trial balance, reproducible report runs and statements with drill-down, EUR translation at pinned valuation rates, the posting rule register and accounting breaks, regulatory reporting behind a simulated adapter *(planned by the Phase 13 → 14 transition, 2026-10-29 — `PHASE_14_PLAN.md`; this row read "GL mapping, trial balance, period close, reporting abstraction" until then)* |
| 15 | Production Hardening | Security hardening, SLOs, runbooks, operational readiness |
| 16 | Scale, Resilience and Disaster Recovery | Load characterisation, degradation modes, backup/restore, DR |

**Current position (2026-10-29, the Phase 13 → 14 transition).** **Phases 0 through 13 are `COMPLETE`; Phase 14 — Accounting and Financial Reporting — is `READY`** (initialised 2026-10-29 as documents only, `PHASE_14_PLAN.md`; Phase 13 `COMPLETE` 2026-10-27, confirmed by the completion gate `P13-GATE` (2026-10-29)).

**Position at 2026-10-29 (the Phase 13 completion gate).** **Phases 0 through 13 are `COMPLETE`.** Phase 13 — Risk, Fraud and AML — closed on its exit review (`P13-DOC-001`, 2026-10-27), its storm (`P13-TST-001`) closed by the owner's acceptance on its partial evidence, and was **confirmed `COMPLETE` after repair by the Phase 13 completion gate** (2026-10-29, `docs/project/reviews/PHASE_13_TO_14_TRANSITION.md`): the owner ratified the review's recommendations, and the gate fixed the three it put inside it - the case file's evidence door now judges each served reference's own parties, a case holding an alert is closed only by two persons (risk `V019`), and a risk action reinstates only identities risk itself suspended (identity `V024`). Thirteen remediation entries (`P13-REM-001`…`-013`) are `PLANNED` for before production use or before the first policy activation; none blocks Phase 14. **Next: Phase 14 — Accounting and Financial Reporting — initialised by the coordinator.** *(The position below is the exit review's, kept.)*

**Position at 2026-10-27 (the Phase 13 exit review).** **Phases 0 through 12 are `COMPLETE`; Phase 13 — Risk, Fraud and AML — is built and closing on its exit review, `P13-DOC-001`, in progress - its verdict not yet issued.** *(This paragraph was still headed "Current position" after the review's verdict and the storm's acceptance - corrected by the completion gate, 2026-10-29.)* Every build task is `COMPLETE` (`P13-TSK-001`…`-025`, 2026-10-22…2026-10-26), and the policy battery (`P13-TST-002`, 2026-10-26: the C8 fixtures judged over a generated population of 10,000 subjects, the fingerprint committed). **The risk storm, `P13-TST-001`, is POSTPONED by the owner until after the project's phases** (2026-10-27, `POSTPONED_ISSUES.md` PI-004 - parked on branch `P13-TST-001` at `e353e11c`, its partial ninth run green on all 57 census checks but never finished: no latency figure, no probe, no final run), so Phase 13 is not proven by a storm as Phases 10, 11 and 12 were: its evidence is each task's hermetic proofs, the battery, the owed database suites (run for the first time in the owner-authorised database pass, 2026-10-27, green after test-only repairs), the production censuses and proofs (`AssessmentCensus`, `MonitoringCensus`, `VelocityProof`, `RescreeningCensus`, `FilingCensus`, `AlertBornOnceCensus`, `CaseOneOpenCensus`) and the independent reviews. Built: one `risk` module behind every seam the platform carried since Phase 4 and the two Phase 13 added (the withdrawal's, with its `HELD` state, and the checkout payment's, a card payment's capture deferred on `HOLD`); versioned policies under four eyes, a deterministic engine, durable velocity windows in the caller's transaction, a sealed and replayable assessment per judged command, overrides; the AML sweep over the ledger's journal, alerts born once, cases - collections' thin slice among them (`-025`) - with four-eyes dispositions and downstream actions through the owning domains' doors, the regulatory report behind a simulated filing port, rescreening on list updates through kyc's new rescreen door (the owner's decision O1); the meters, thirty alert rules, four operations reports and `OPERATIONS_RUNBOOK.md` §9 (`docs/product/CAPABILITY_MAP.md` §Risk has each as built). Production evaluates nothing until §9.1's activation gate is passed - whose step 2, the storm's latency figures, cannot be met until the storm runs - so every seam answers `ALLOW` with the reason code `NO_POLICY_ACTIVE`, recorded - an accepted limitation. The open owner items are gathered in `PHASE_13_PLAN.md` §1.2. *(This position read "(2026-10-22, the Phase 12 → 13 transition) … Phase 13 … is `READY`" until `P13-DOC-001`; the paragraph below is that position, kept.)*

**Position at 2026-10-22 (the Phase 12 → 13 transition).** **Phases 0 through 12 are `COMPLETE`; Phase 13 — Risk, Fraud and AML — is `READY`** (initialised 2026-10-22; Phase 12 `COMPLETE` 2026-10-22, confirmed after repair by the transition's completion gate, below). The transition initialised Phase 13 as documents only (`PHASE_13_PLAN.md`; ADR-0107…0112 `Proposed`; `INV-RSK-01`…`-10`, the catalogue 152 → 162; `RISK_LIFECYCLES.md`; the gate's Phase 13 extension; `P13-TSK-001`…`-025`, `P13-TST-001`…`-002`, `P13-DOC-001`): one `risk` module - no money, every domain through ports - implementing the seams carried since Phase 4 (`TransferLimitCheck`, `TransferRiskDecision`), Phase 9 (`CrossBorderLimitCheck`, `CrossBorderRiskDecision`) and Phase 10 (`CreditRiskSignal`, seam version 2) and adding the two the money-moving commands never had (the wallet withdrawal, with a `HELD` state, and the checkout payment confirmation); versioned policies of rules as data under four eyes, a deterministic engine, durable velocity windows written in the caller's transaction, an immutable assessment per judged command with a fail-safe that is a recorded decision and never a silent default, an override under four eyes; AML monitoring as a leaderless sweep over the ledger's own journal, alerts born once, cases with four-eyes dispositions, downstream actions as keyed commands through the owning domains, a regulatory report artefact distinct from the investigation behind a simulated filing port, and rescreening on list updates through kyc's ports (Refinements 2 and 3 below, now being implemented by Phase 13). Owner decisions C1–C10 are recorded as accepted defaults pending ratification; `P13-TSK-001` — the `risk` module boundary, floors, vocabularies and roles — is the first task. Production evaluates nothing until a risk policy version is activated under four eyes and `OPERATIONS_RUNBOOK.md` §9's activation gate is passed: until then every seam answers `ALLOW` with the reason code `NO_POLICY_ACTIVE`, recorded - an accepted limitation. *(This position opened "**Current position (2026-10-22, the Phase 12 → 13 transition gate).** **Phases 0 through 12 are `COMPLETE`.** … **Phase 13 initialisation is the owner's to allow.**" until the owner allowed it; the paragraph below is that position, kept.)*

**Position at 2026-10-22 (the Phase 12 → 13 transition gate).** **Phases 0 through 12 are `COMPLETE`.** Phase 12 — Buy Now, Pay Later — closed on its exit review (`P12-DOC-001`, 2026-10-22; 29 of 29) and was **confirmed `COMPLETE` only after repair by the Phase 12 → 13 transition's completion gate** (2026-10-22, `reviews/PHASE_12_TO_13_TRANSITION.md`): seven independent audits found no critical defect and ten IMPORTANT ones the review had not - a dispute refund that could never be paid stuck silently, a contested dispute with no deadline, queue or alert, an insider able to mint a refund-capable merchant key and refund their own purchase, a customer able to open and withdraw disputes forever to avoid default, a terms read during an activation closing approved applications for good, and five test gaps - each repaired (bnpl `V018`, `V019`, merchant `V012`, lending `V031`, lending's RG-M1 with them), tested and broken on purpose; criterion 7 stays `PARTIAL`, and by the owner's decision the repaired tree had no full re-run, no independent verifier and no storm. **Phase 13 initialisation is the owner's to allow.** *(This opening read "(2026-10-22, the Phase 12 exit review) … closed on its exit review (`P12-DOC-001`, 2026-10-22; 29 of 29), pending the Phase 12 → 13 transition gate, which is the owner's to open" until the gate.)* The review found no critical and no production defect, closed seventeen gate-clause test gaps (every born-once arbiter probed alone, the plan's purchase-lock probe performed, the terminal edges, the one-key races, DPD at every storm reading among them), accepted ADR-0101…0106 and recorded criterion 7 `PARTIAL` (the owner's standing deviation). Phase 12 built BNPL at checkout end to end: pay-in-4 and pay-in-N chosen inside a checkout session, one credit decision per purchase taken up through credit's consumption port, the merchant's payable credited the gross less its fee and paid through its existing payout, the receivable funded from lending capital under the one shared headroom and counted by credit exposure version 3, instalments collected from the wallet only, early payoff, delinquency, default and capped late fees, refunds cancelling the latest instalments first with the excess to the customer and the fee pro-rated back, a refund after payout bounded by the purchase's own net and recovered from the merchant's later trade, disputes held and resolved under the one refund bound, four-eyes reversals and waivers, and the three-way, subledger and replay proofs with the investigator's explanation door, four audited reports and the BNPL alerts (`OPERATIONS_RUNBOOK.md` §8) - proven by the BNPL storm on two instances five seconds either side of the database (`P12-TST-001`, which found and fixed two production defects: the replay now walks the record's own order, bnpl `V017`, and B4's taker reads the agreement version a racing refund committed) and by the battery's ten thousand generated purchases replayed in two JVMs (`P12-TST-002`). Production originates no BNPL purchase until a real bureau is connected (#13, #14) and `OPERATIONS_RUNBOOK.md` §8.1's activation gate is passed - legal review of the merchant agreement and of the consumer terms and an approved merchant-fee schedule among its steps, none yet met - an accepted limitation. *(This opening read "**Phases 0 through 11 are `COMPLETE`; Phase 12 … closing on its exit review, `P12-DOC-001`, in progress - its verdict not yet issued**" until the review's verdict. This position read "(2026-10-16, the Phase 11 → 12 transition) … Phase 12 … is `READY`" until `P12-DOC-001`; the paragraph below is that position, kept.)*

**Position at 2026-10-16 (the Phase 11 → 12 transition).** **Phases 0 through 11 are `COMPLETE`; Phase 12 — Buy Now, Pay Later — is `READY`.** The transition confirmed Phase 11 after repair (below) and initialised Phase 12 as documents only: pay-in-4 and pay-in-N chosen at checkout, one credit decision per purchase, the merchant paid upfront net of its fee through its existing payout, the receivable funded from lending capital, instalments from the wallet, refunds cancelling the latest instalments first with the excess to the customer and the fee pro-rated back, a refund after payout bounded by the purchase's net, disputes resolved under the one refund bound, and the three-way proof (`PHASE_12_PLAN.md`, ADR-0101…0106 `Proposed`, `INV-BNPL-01`…`10`); `P12-TSK-001` — credit learns the two BNPL products and the consumer kind — is the first task. *(This position opened "**Current position (2026-10-16).** **Phases 0 through 11 are `COMPLETE`.**" until the transition; what follows is that position, kept.)* Phase 11 — Lending Infrastructure — closed on its exit review (`P11-DOC-001`, 2026-10-16): the personal loan and the revolving credit line from application to closure, every figure a ledger balance, every loan funded from lending capital recognised from bank evidence, proven by the storm on two instances and the battery; the review corrected a lock-order cycle in the interest waiver, probed every born-once arbiter alone, closed twenty test gaps on gate clauses and accepted ADR-0090…0100. Production originates no loan until a real bureau is connected (#13, #14) - an accepted limitation. **The Phase 11 → 12 transition's completion gate (2026-10-16) confirmed Phase 11 `COMPLETE` only after repair**: six independent audits found six important defects the exit review had not - a principal fact value-dated into an already-accrued day after a backward clock step, held credit never applied to past-due items, a refunded fee forgivable twice, self-approval refusals unaudited, an admitted terms version unschedulable at disbursement, and twenty lending database cases red in one suite order - each repaired (lending `V029`, `V030`), tested, broken on purpose and closed by an independent verifier (`reviews/PHASE_11_TO_12_TRANSITION.md`). *(This read "**The Phase 11 → 12 transition is the owner's to open.**" until the gate.)* *(This position read "(2026-10-10) … Phase 11 … is `READY`" until `P11-DOC-001`; the paragraph below is that position, kept.)*

**Position at 2026-10-10.** **Phases 0 through 10 are `COMPLETE`; Phase 11 — Lending Infrastructure — is `READY`.** Phase 10 closed on its
exit review (`P10-DOC-001`, 2026-10-09 — `PHASE_10_PLAN.md` and `CREDIT_DECISIONING_LIFECYCLES.md`
read against the code and made true, inline where a statement was wrong and in the plan's errata
for the rest; ADR-0084…0089 read against the code and accepted). Among what the review found and
fixed: a policy whose rules did not bound its own maximum exposure would have let the platform
approve past it; the retry sweep kept asking providers for requests already closed; the evaluating
step took the pinned versions before the data requests, against the one lock order; and a record's
age ran from the provider's own date. **The platform decides credit and moves no money**: a keyed
request, data collected under consent re-read at every step, a sealed snapshot, an exact
affordability and exposure judged under the party's lock, a versioned scorecard and a policy of
rules as data through a versioned engine, an immutable decision with ordered reason codes, a
referral decided by people under four-eyes, and every decision replayed `IDENTICAL` - all
twenty-four items `COMPLETE`, the credit decision storm (`P10-TST-001`) answering the ten-instances
question on two instances five seconds either side of the database, the battery (`P10-TST-002`)
replaying ten thousand generated applicants in two JVMs. The fleet-wide database and kafka tiers
were skipped on the owner's standing instruction. **The Phase 10 → 11 transition** (2026-10-10)
confirmed Phase 10 only after repair: its six audits found no critical defect but fifteen important
ones — an underwriter able to decide their own application, free-text reasons never screened for
card or account numbers, a decision not sealed to its own request's snapshot, a person able to
decide on data past its maximum age or on an absent bureau balance counted as zero, exposure blind
to outstanding credit (latent until loans existed), an answer that could retry forever, and gaps in
the tests that claimed to prove them — every Phase 10 one repaired, tested and broken on purpose;
the owner enforced MFA on credit submission and refused a person's approval on an absent bureau
balance, so **production approves no credit until a real bureau is connected**. **Phase 11 —
Lending Infrastructure — is `READY`**: the personal loan and the revolving credit line serviced
from authoritative records, every figure a ledger balance, every loan funded from recognised
lending capital and disbursed to the wallet or an external account; `P11-TSK-001` — the
credit-owned consumption port, the transition's one deferred finding — is the first task; and
origination is proven against the simulators until production can approve.

**Phase 9** closed on its
exit review (`P9-DOC-001`, 2026-10-07 — `PHASE_9_PLAN.md` and
`FX_AND_CROSS_BORDER_LIFECYCLES.md` read against the code and made true, inline where a
statement was wrong and in the plan's §20 errata for the rest). Among what the review found and fixed: the beneficiary's
screening validity and the outbound credit's `NEVER_RECEIVED` deadline judged on an instance's
clock instead of the database's, and a four-eyes return approval posting two entries without
the multi-entry pre-lock; ADR-0074…0083 were read against the code and accepted. **The platform converts and pays abroad as principal**: a
conversion at a server-authoritative, single-use quote that is a frozen posting plan, booked
locally through `FX_POSITION` and covered with the FX provider exactly once however it answers,
unwound when no longer wanted; payments abroad through declared corridor rails to a beneficiary
payee-checked and screened before pricing, debited once at the provider's acceptance or not at
all, returned exactly or parked for a person, cancelled only by a recall the provider confirms;
and every position, fee, spread and residual reconciled per currency, never by conversion — all
thirty items `COMPLETE`, the FX and cross-border storm (`P9-TST-001`) answering the ten-instances
question across two instances whose clocks disagree. The fleet-wide database tiers were skipped
on the owner's standing instruction. **The Phase 9 → 10 transition** (2026-10-07) confirmed Phase 9
only after repair: its seven audits found two critical defects the exit review had passed over — a
concluded cross-border credit re-sendable to a provider that had never seen it, and a parked return
creditable twice — and twenty-three important ones (two deadlocks, one introduced by the exit
review's own repair; a sweep page that could starve; storage failures logging refused rows; an
exponent amount exhausting every money door), every one repaired, tested and broken on purpose.
**Phase 10 — Credit Decisioning** opened `READY`: decisions as immutable, reproducible facts from
versioned policy and models, with no money moved; `P10-TSK-001` the first task; all twenty-four
`COMPLETE` by 2026-10-09 (`P10-DOC-001`).

**Phase 8** closed on its
exit review (`P8-DOC-001`, 2026-10-01 — 8 areas, 12 universal criteria, F1–F8 re-assessed and 28
phase-specific criteria met, ADR-0064…0073 read against the code and accepted, with the
fleet-wide database and kafka tiers' skip recorded as a deviation on the owner's standing
instruction). The review found two critical gaps — a `RESOLVED` break whose approved resolution
was held by nothing, and a gain judged against the source's active rule set instead of the
owning break's pinned one — and corrected and probed both. **Internal truth is proven against
external reality on every rail**: four settlement sources ingested authenticated, screened and
retained encrypted, every externally settling completion expected, every difference a
classified, aged break, suspense owned, resolution four-eyes through the ledger, and cash
confirmed only by the bank's own statements. **The Phase 8 → 9 transition** (2026-10-02)
confirmed Phase 8 only after repairing what the review had missed — among them `FeeCheck`
throwing on a cross-currency fee line instead of raising a typed `CURRENCY_MISMATCH` — and
entered the inherited debts in the register. **Phase 9 — FX and Cross-Border Payments**
opened `READY` behind its entry gate: planned in `PHASE_9_PLAN.md` on ADR-0074…0083 — a conversion
at a server-authoritative, single-use quote that is a frozen posting plan, covered with the FX
provider exactly once however it answers; payments abroad through declared corridor rails, the
beneficiary screened before pricing; and every position, fee, spread and residual reconciled
per currency, never by conversion. Thirty items across nine milestones, with `X-TSK-013`
scheduled inside the phase; first task `P9-TSK-001`; all thirty — the storm among them —
`COMPLETE` by 2026-10-07 (`P9-DOC-001`).

**Phase 7** closed on its
exit review (`P7-DOC-001` — 8 areas, 12 universal criteria, F1–F8 re-assessed and met, 21
phase-specific criteria, the ten-instances question over every contended decision, ADR-0059…0062
read against the code, corrected where they had drifted, and `Accepted`). The review found a
withdrawal posting to a clearing position it named, a "recorded" capability descriptor that was
one integer while every resolver read the running build — ruled: a rail's money semantics are
frozen per rail name — bank details printable by three port records, an unstated lock order, and
nineteen gate claims with partial tests, and fixed and probed each. **Money moves on three rails
the domain tells apart by their declarations** — card, instant credit transfer and the platform's
own books — every payment routed once and explainable, every external rail's money in flight on
its own clearing position, bank details never entering, and a chargeback never taking more than
the capture credited. **The Phase 7 → 8 transition** (2026-09-28) confirmed Phase 7 only after
repairing what the review had missed — a withdrawal's own confirmation parked as inbound value, an
inquiry sweep crediting an amount the scheme never executed, and twelve important defects besides,
among them one scheme execution credited and parked at once, authorizations left standing, a
merchant closed with a winnable chargeback and a dispute deadlock — broke every repair on purpose,
and ran the fleet-wide battery before and after. **Phase 8 — Settlement and Reconciliation**
opened `READY` behind its entry gate: planned in `PHASE_8_PLAN.md` on ADR-0064…0073 — settlement holds
the external evidence and reconciliation the expectations, each clearing position discharged in
two evidence hops, every disagreement a classified, aged break resolved by evidence or a four-eyes
adjustment through the ledger. Twenty-seven items across eight milestones; first task
`P8-TSK-001`; all twenty-seven — the storm and the battery among them — `COMPLETE`
by 2026-10-01 (`P8-DOC-001`).

**Phase 6** closed on
its exit review (`P6-DOC-001` — 8 areas, 12 universal criteria, F1–F8 re-assessed and met, 16
phase-specific criteria, the ten-instances question over every contended decision,
ADR-0050…0054 and ADR-0056…0058 read against the code, corrected where they had drifted, and
`Accepted`). The review found and fixed more than it was sent — a suspended merchant still
payable, an index ADR-0053 named and nothing built, wrong operator instructions for five
credentials, and four contended decisions nobody had raced — and probed every fix. **A merchant
the platform does not own can sell through it and be paid**: 18 of 18 backlog items, the fee
taken in the capture's one entry, refunds funded by the net, payouts bounded by the ledger and
sent to a destination no one person can change, and tenancy in every statement.

**Phase 7 — Cards, Wallets, A2A and Instant Payments** opened `READY` behind its entry gate,
passed by the Phase 6 → 7 transition (2026-09-24). The transition's independent audit confirmed Phase 6
only after repairing what the review had missed — a taken-over refund that released a hold a first
send may have paid, a wallet that could be closed beneath a card capture, and nine important
defects besides — and ran the full battery fleet-wide before and after. Phase 7 is planned in
`PHASE_7_PLAN.md` on ADR-0059…0062: rails that declare their capabilities, routing that is pinned
and explainable, disputes whose chargebacks can never debit a merchant twice, and account-to-account
and instant payments that keep bank details out of the platform. Eighteen items across eight
milestones, all eighteen `COMPLETE` by 2026-09-28. `X-TSK-003` carries the remaining
idempotency-scope work.

*(This section read "Phase 6 … is `READY`, first task `P6-TSK-001`" through the whole of Phase 6
— frozen at the Phase 5 → 6 transition, the stale-second-copy class this section has now fallen
to three times. Corrected at the Phase 6 review.)*

**Phase 4 was the first customer-visible money movement** — and deliberately the *easy* half
of moving money: both legs internal, one database, one transaction, no third party. Its job
was to prove the lifecycle, idempotency, conservation-under-contention and reversal
disciplines on the rail the platform controls entirely, so that Phase 5 — where an
unreliable provider decides outcomes and `UNKNOWN` becomes a modelled state — changes one
variable at a time, not four.

---

## Sequencing Rationale

The requested ordering is **retained**. Three refinements are applied, each justified below.

### Refinement 1 — Phase 0 is not a documentation phase

The original roadmap treated Phase 0 as "fintech foundations" (glossary and concepts).
That is insufficient as a gate. Financial correctness depends on primitives that are
extremely expensive to retrofit once money-moving code exists:

- monetary representation
- idempotency
- transactional outbox
- audit trail
- correlation/causation propagation
- module boundary enforcement

Phase 0 therefore delivers a **buildable, tested platform skeleton with a financial
kernel and no business capability**. This is deliberate: it is the only phase where these
primitives can be introduced without migrating existing financial history.

### Refinement 2 — Cross-phase seams instead of reordering

Several capabilities are conceptually "later" but structurally needed earlier. Rather than
reorder phases (which would collapse domain boundaries), each early phase must expose an
explicit **seam**: a named extension point with a no-op or trivially-configured default,
owned by the earlier phase and implemented by the later one.

| Seam | Introduced | Implemented |
|------|-----------|-------------|
| Limit / velocity check | Phase 4 | Phase 13 — implemented by `P13-TSK-008` (transfers) and `P13-TSK-011` (cross-border, the Phase 9 seam) *(read "being implemented" until `P13-DOC-001`, 2026-10-27)* |
| Risk decision hook on money movement | Phase 4 | Phase 13 — implemented by `P13-TSK-008` (transfers), `-011` (cross-border), `-007` (credit's `CreditRiskSignal`, the Phase 10 seam), and the two new seams by `-009` (the withdrawal) and `-010` (the checkout payment) *(read "being implemented", without the new two, until `P13-DOC-001`, 2026-10-27)* |
| Suspense account + break record | Phase 3 | Phase 8 |
| GL account mapping | Phase 3 | Phase 14 — planned: the mapping as accounting's versioned data keyed by ledger purpose, `P14-TSK-003` (ADR-0113, `Proposed`); the Phase 3 seam `ledger_account.gl_code` stays unwritten *(the Phase 13 → 14 transition, 2026-10-29)* |
| Provider settlement evidence capture | Phase 5 | Phase 8 |
| Multi-currency account/posting support | Phase 3 | Phase 9 |
| Dispute/chargeback financial effect | Phase 5 | Phase 7 |

A seam is a documented interface, not a stub implementation of the later domain.
Introducing a seam does **not** authorise implementing the later phase's logic.

*(2026-10-22, the Phase 12 → 13 transition: the Phase 13 seams are now being implemented by Phase 13 -
`PHASE_13_PLAN.md` §12.1 - in `app` as adapters over `risk`'s one channel-neutral evaluation API, each seam's
contract unchanged (two values, no reason; the caller commits its own reserved outcome). Phase 13 also adds the two
seams the money-moving commands never had - payments' `WithdrawalRiskDecision` (`P13-TSK-009`, with the `HELD`
withdrawal) and `PaymentRiskDecision` (`P13-TSK-010`) - and replaces `PermitAllUntilPhase13` in `app`'s wiring only.
This refinement stands for the rows that remain: GL account mapping, Phase 14.)* *(P13-DOC-001, 2026-10-27: built as
said - every seam implemented by Phase 13's tasks, each contract unchanged.)*

### Refinement 3 — Screening is split across two phases

Sanctions/PEP screening appears in both Phase 2 and Phase 13. These are different
capabilities and must not be merged:

- **Phase 2** owns *onboarding-time* screening of a party against sanctions/PEP lists as
  part of the KYC/KYB decision.
- **Phase 13** owns *ongoing* transaction monitoring, rescreening on list updates, and
  behavioural AML detection.

They share adapter infrastructure but have different owners, lifecycles and audit
obligations.

*(2026-10-22, the Phase 12 → 13 transition: Phase 13's half is now being implemented by Phase 13 -
`PHASE_13_PLAN.md` §12.7 (the monitoring intake reads the ledger journal, `P13-TSK-019`…`-021`) and §12.10
(rescreening, `P13-TSK-022`: a screening list version recorded and activated by an operator enqueues the rescreening
of every active party, performed through kyc's existing screening ports, the clearance a risk signal). kyc stays the
screening owner; `risk` asks, kyc screens.)* *(P13-DOC-001, 2026-10-27, as built: not through the existing screening
ports - kyc gained a **new** rescreen door (the owner's decision O1, `P13-TSK-022`): a `RESCREEN` check on the party's
latest KYC case, asked for every party whose customer relationship is `ACTIVE` or `PENDING`; a hit on a decided case
leaves the case and the customer untouched and is risk's `SCREENING_HIT` alert, a hit on an undecided one is kyc's
review item too.)*

---

## Dependency Graph

```
0 ──┬──> 1 ──> 2
    │         │
    └──> 3 ────┴──> 4 ──> 5 ──┬──> 6 ──> 12
                              │
                              ├──> 7 ──> 8 ──> 14
                              │         │
                              └──> 9 ───┘
    1 ──> 10 ──> 11 ──> 12
    4,5 ──> 13
    3,4,5,8 ──> 14
    all ──> 15 ──> 16
```

Hard dependencies (a phase cannot enter its gate without these):

- 3 requires 0 (money kernel, outbox, audit)
- 4 requires 3 (ledger) and 1 (actor identity for audit)
- 5 requires 4 (internal money movement proven end to end)
- 8 requires 5 (external evidence exists to reconcile against)
- 9 requires 3 (multi-currency ledger), 5 (payment lifecycle) and 8 (settlement and
  reconciliation: the FX provider and the corridor rail settle through Phase 8's machinery)
  *(the Phase 8 dependency added by the Phase 8 → 9 transition, 2026-10-02 —
  `DELIVERY_PLAN.md` §Phase 9 has named it since Phase 0, and this list omitted it)*
- 11 requires 3 (ledger), 10 (decisioning)
- 12 requires 6 (merchant) and 11 (lending mechanics)
- 14 requires 3, 4, 5, 8 (there must be real postings to report on)

---

## Non-Goals for the Whole Programme

- Full regulatory compliance for any specific jurisdiction. The core is
  jurisdiction-neutral; country specifics sit behind policy/configuration/adapters.
- Real connectivity to real payment networks, bureaus, or banks. All external providers
  are simulated behind adapters with contract tests and fault injection.
- Handling real cardholder data. Card data is tokenised at the boundary; the platform is
  designed to stay out of PCI DSS scope wherever possible.
- Being a production system carrying real customer funds.
