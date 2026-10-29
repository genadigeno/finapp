# Bounded Contexts

Initial candidate bounded contexts.

**Transfers (8)** and **Case Management (23)** were added by `P0-TSK-006`. Both are real
bounded contexts with their own aggregates and lifecycles (`DELIVERY_PLAN.md` Phases 4 and
13) that the original list omitted; the mapping exercise found modules planned for them with
no declared context. This records decisions already taken elsewhere — it is not a new
decision.

**Disputes (29)** was added by the Phase 6 → 7 transition (ADR-0061 §1). `DELIVERY_PLAN.md`
already named it a new context for Phase 7; this list never had. It maps to `payments`, merged,
with its split trigger recorded (`MODULE_ARCHITECTURE.md` M11).

**Settlement (13)** and **Reconciliation (14)** were re-bounded by the Phase 7 → 8 transition
(ADR-0064), not renamed or merged: they stay two contexts in two modules with no build edge
between them (`MODULE_ARCHITECTURE.md` M8). Settlement is the external side — each counterparty's
reports and the bank's statements, received, retained and recognised. Reconciliation is the
internal side, the comparison and its outcome — the settlement expectations every externally
settling completion opens, the matching, the breaks, the suspense and the resolutions. The
expectation belonged to Settlement until then; it is internal state that allocation and ageing
drive, so it is Reconciliation's.

**FX (15)** and **Cross-Border Payments (16)** were bounded by the Phase 8 → 9 transition
(ADR-0074…0081, 2026-10-02): two contexts in two modules, `fx` and `crossborder`, with no build
edge between them and none to `payments`, `kyc` or `accounts` (`MODULE_ARCHITECTURE.md` §4). FX
is pricing and conversion — the server-authoritative quote that is a frozen posting plan, the
trade that posts it, and the cover that squares the platform's position with the FX provider.
Cross-Border Payments is the customer's instruction to pay a beneficiary abroad — corridors,
beneficiaries, offers and the payment's business lifecycle — delegating pricing to `fx` and
execution to `payments`, whose Outbound Credit owns the provider's ambiguity (ADR-0079). Two
boundary decisions worth recording: counterparty screening is KYC/KYB's (context 3) — a
transaction-time sanctions check on an external party is a kyc decision with evidence, review
and a recorded basis, not a payment state (ADR-0081) — and the FX and corridor counterparties'
clearing positions are the Ledger's (context 7), keyed per counterparty through
`ledger.counterparty`, reconciled by Settlement and Reconciliation (13, 14) over their own
report sources (ADR-0078, ADR-0082).

*As built by Phase 9 (`P9-TSK-001`…`-027`, `X-TSK-013`, `P9-TST-001`; read at the exit review,
`P9-DOC-001`, 2026-10-07):* `fx` (schema `fx`, migrations `V001`…`V009`) and `crossborder` (schema
`crossborder`, `V001`…`V006`) each depend on `ledger` and `platform` only. Their joins are ports
composed in `app`: a cross-border payment prices through crossborder's `CrossBorderFx` port (implemented
in `app` over fx) and executes as payments' outbound credit (payments `V025`…`V027`), whose completion, failure and return call back through
payments' `OutboundCreditComposition`, implemented by `app`'s `CrossBorderCompletion` over crossborder's
payment and fx's `CrossBorderCompletionBooking`. Counterparty screening is kyc's
`counterparty_screening` (kyc `V009`, `P9-TSK-016`). A parked cross-border return is resolved by
Reconciliation's four-eyes `TRANSFER_TO_ACCOUNT` through its `ResolvedCorridorReturns` port, implemented
in `app` (`CorridorReturnResolutions`) - Reconciliation still names no payments or crossborder type.

**Credit (17)** was bounded by the Phase 9 → 10 transition (ADR-0084, 2026-10-07;
`PHASE_10_PLAN.md` §3) and built by `P10-TSK-001`…`-021` (credit `V001`…`V015`) — *as built,
read against the code by the Phase 10 exit review, `P10-DOC-001`, 2026-10-09, which accepted
ADR-0084; this read "`Proposed` … planned: nothing of it is built" until then.* One context in one module, `credit`, with one consistency
boundary: the decision and the snapshot it was made from commit together, and the exposure an
approval reserves is judged in the transaction that records it. Inside it credit data, the credit
profile, the assessment (affordability, exposure, the scorecard's score), the versioned policy and
model, underwriting and the decision stay separate aggregates — `CLAUDE.md`'s Credit Score /
Risk Score / Credit Decision / Underwriting distinction made physical; collecting credit data as a
second context was weighed and refused (its only consumer is credit's own snapshot). **Credit moves
no money**: it posts nothing to the Ledger (7), holds nothing and disburses nothing. Its
relationships, every one through a port `app` implements and none a build edge (`credit` depends on
`platform` and `sharedkernel` only):
- **Consent (4)** — upstream, conformist: no credit data is retrieved without a recorded, current
  lawful basis (`INV-CRD-03`). Two new purposes, `CREDIT_BUREAU_ACCESS` and
  `FINANCIAL_DATA_ACCESS`, each with its own consent text; the gate (`ConsentBackedCreditConsentGate`
  over consent's `ConsentGate`) is read authoritatively in the acting transaction at submission, at
  a data request's opening, at every retry, at the recording of its answer, at the freeze and in the
  deciding transaction. A submission without it is refused with consent's own
  `409 consent.ConsentRequired`.
- **Party & Customer (1) and KYC/KYB (3)** — upstream: the applicant's standing is Party's
  customer status, read through credit's `CreditPartyStanding` port, implemented in `app` by
  `PartyCreditStanding` over party's `PartyStore` — the live customer `ACTIVE`, the
  `VerifiedAccountHolder` gate, which `INV-KYC-05` makes a projection of KYC's approval, so KYC is
  never read directly — in the acting transaction, per decision. The party facts a snapshot
  records (age, residence) are answered `ABSENT` until the platform holds them (unresolved question
  #13). Credit adds nothing to either context. *(This read "(`ACTIVE`, KYC `VERIFIED`) … through
  kyc's existing customer-standing port" until the Phase 10 exit review.)*
- **Identity, Authentication & Authorization (2)** — upstream: the sessions every credit door
  authenticates; the MFA enrolment the customer's submission and cancellation step up on (the
  submission requires a `MULTI_FACTOR` session unconditionally since the Phase 10 → 11 transition
  — R14, the owner's decision; the cancellation a `MULTI_FACTOR` session only when a TOTP factor is
  enrolled; `identity.AssuranceRequired` otherwise); and the three permissions `CREDIT_POLICY_ADMINISTER`, `CREDIT_INVESTIGATE` and
  `CREDIT_UNDERWRITE` with the two roles `CREDIT_POLICY_OFFICER` and `UNDERWRITER`, declared in
  identity's `PermissionName` and `RoleName` and admitted by identity `V020`. *(Added by the Phase
  10 exit review, `P10-DOC-001`, 2026-10-09.)*
- **Risk (20)** — upstream, through a seam: the **risk score is Risk's** (Phase 13), settled by the
  same transition (the glossary's §10). Credit declares the `CreditRiskSignal` port and records
  the answer it was given in the decision's snapshot; until Phase 13 the composition answers
  `NOT_ASSESSED` for every party, so a decision made before Risk exists replays identically after.
- **Lending (18)** — downstream, from Phase 11: a Loan Application references a credit decision
  through credit's published decision-read port (`CreditDecisions`) and may take
  `CreditDecisionRecorded` as a hint; the loan, its offer, disbursement and servicing are Lending's.
  **Taking up an approval is credit's write, not Lending's**: the born-once
  `credit_decision_consumption` fact is written only by credit's own command port
  (`CreditDecisionConsumptions.consume`, `P11-TSK-001`), under the party's `credit_profile` lock,
  behind a database guard; Lending's acceptance calls it and never writes a credit table. *(This read
  "and so is consuming the exposure an approval reserves — by writing credit's born-once
  `credit_decision_consumption` fact" until the Phase 10 → 11 transition, which found the table
  with no owner and the application role holding `INSERT` — R13.)* Lending supplies the facts
  credit's `PlatformCreditExposure` version 2 reads; credit keeps the judgement. **BNPL (19)** is a
  second downstream consumer of the same decision, through the same port — *planned by
  the Phase 11 → 12 transition, 2026-10-16, and built (`P12-TSK-001`, `-012`, `-013`; read against the code by
  `P12-DOC-001`, 2026-10-22, which read "a later downstream consumer … in Phase 12" until then):* two new credit products (`BNPL_PAY_IN_4`,
  `BNPL_PAY_IN_N`, credit `V022`, `P12-TSK-001`), one decision request per purchase, taken up as
  `ConsumerKind.BNPL_AGREEMENT`, and exposure version 3 adding the party's BNPL outstanding principal.
- **External credit bureaus and financial-data providers** — reached only through provider-neutral
  adapters (ADR-0008), their formats never leaking into the domain; the raw answer is kept as
  encrypted evidence with a declared retention deadline. Only simulators exist (`bureau-sim-a`,
  `bureau-sim-b`, `findata-sim-a`): production names no provider and selects each kind's fail-safe
  until unresolved questions #13 and #14 are answered.

**Lending (18)** was bounded by the Phase 10 → 11 transition (ADR-0090, 2026-10-10;
`PHASE_11_PLAN.md` §3) and built by `P11-TSK-002`…`-036` (lending `V001`…`V028`; the build edges below
build-graph facts, pinned by `LendingModuleIsolationTest`) — *as built, read against the code by the
Phase 11 exit review, `P11-DOC-001`, 2026-10-15, which accepted ADR-0090; this read "`Proposed` …
built so far: the module's boundary, its schema floor and its closed vocabularies … everything else is
planned" until then.* One context in one module, `lending`, serving two products —
the amortising personal loan and the revolving credit line — on one account model. **It owns the
contract and its servicing facts and none of the money**: the application, the versioned product
terms, the offer, the immutable versioned agreement and its acceptance evidence, the loan account
(identity, kind and state only), the schedule and instalments, billings, statements, draws,
accruals, fee assessments, repayments and their allocations, collection attempts, the delinquency
and default conditions, payoff quotes, amendments and lending-capital contributions — while every
principal, interest, fee and credit-balance figure is a balance of a per-loan account in the Ledger
(7), posted through the ledger's services in Lending's own transaction, never stored. Its consistency
boundary is the loan account: every servicing act on one account serialises on its row, and each
money effect commits with the fact that explains it. Its relationships, every one through a port
`app` implements and none a build edge (`lending` depends on `ledger`, `platform` and `sharedkernel`
only):
- **Credit (17)** — upstream, customer-supplier: the decision is Credit's; Lending's application
  opens Credit's decision request in its own transaction, its offer reads the decision
  authoritatively, and its acceptance takes the approval up through Credit's consumption port under
  the party's profile lock — taken first, by the acceptance, the disbursement and the repayment
  reversal. The one other credit lock Lending holds is Credit's decision-request row, which the
  application's withdrawal takes through Credit's cancellation after its own application row; Credit
  never waits on a lending lock, so neither closes a cycle *(this read "the only credit lock Lending ever holds,
  always first" until `P11-DOC-001`, 2026-10-15; ADR-0091 point 8)*. Exposure stays
  Credit's judgement over facts Lending supplies (`PlatformCreditExposure` version 2: committed and
  outstanding principal, and an open credit line's limit, in one statement).
- **Ledger (7)** — downstream conformist to the ledger's contract: ten new account purposes under a
  new `OwnerKind.LOAN`, `LENDING_CAPITAL` among them — the platform's own funds, recognised only from
  bank evidence, from which every loan and draw is funded and which no acceptance or draw may
  over-deploy.
- **Accounts / Wallet (5, 6)** — the borrower's wallet is the disbursement's destination and the
  repayment's source; Lending never owns it.
- **Payments (9)** — an external payout is a system-initiated withdrawal of the borrower's disbursed
  funds on their recorded instruction, through Payments' own machinery (routing, ambiguity held,
  inquiry-only outcomes, returns, reconciliation's expectation); **Lending reads Payments' outcome
  and is never called by Payments**, so no lock-order cycle can form. Lending does not implement
  `OutboundCreditComposition`.
- **Reconciliation (14) and Settlement (13)** — a lending-capital contribution is an expectation
  bank recognition matches; cash is never adjusted to fit.
- **Party & Customer (1), Identity (2)** — standing read in the acting transaction; the permissions
  `LENDING_ADMINISTER`, `LENDING_INVESTIGATE`, `LENDING_CAPITAL_ADMINISTER`, `LOAN_SERVICE` and
  `LOAN_SERVICE_APPROVE`; the customer's credit-creating acts on a `MULTI_FACTOR` session.
- **BNPL (19)** — downstream, from Phase 12 *(planned by the Phase 11 → 12 transition,
  2026-10-16, ADR-0101 and ADR-0104; built by `P12-TSK-002` and `-005`, its consumers by `-013` and `-024`, and read
  against the code by `P12-DOC-001`, 2026-10-22)*: shares two things and nothing else. Its **capital** — BNPL
  principal is funded from the same `LENDING_CAPITAL` and judged under the same L6 row and headroom,
  which Lending's `LendingCapitalHeadroom` and `LendingCapitalProof` widen by summing the BNPL
  principal *ledger purposes* (no bnpl type enters lending), BNPL reading it through `app`'s port. Its
  **arithmetic** — the pure schedule, accrual and allocation engines moved unchanged into `creditmath`,
  on which both depend (`MODULE_ARCHITECTURE.md` M12; *built by `P12-TSK-002`, 2026-10-16, every Phase 11
  figure replaying identically — this read "move" until then*). BNPL never creates a `loan`, reads or writes a
  lending store or posts a `LOAN_*` purpose, and Lending never knows a merchant. The capital port runs Lending's own
  `LendingCapitalHeadroom.underLock` in BNPL's transaction - L6, the `LENDING_CAPITAL` ledger row `FOR UPDATE`, then
  Lending's unlocked read of its committed principal and the journal lines - the only Lending code a BNPL
  transaction runs, and no Lending table row is locked *(`P12-DOC-001`, 2026-10-22: added by the Phase 12 exit review)*.
- **Downstream, deferred:** collections (Case Management, 23 — Phase 13) consumes
  `LoanDelinquencyChanged` and `LoanDefaulted`; write-off, provisioning and the GL (Accounting, 25 —
  Phase 14) map Lending's purposes; refinance is decided by the Phase 11 → 12 transition — *decided
  2026-10-16: not Phase 12 (`PHASE_12_PLAN.md` §20)*.
  **Production originates nothing** until a real bureau is connected (unresolved #13/#14): Phase 11
  proves origination against the simulators.

**BNPL (19)** was bounded by the Phase 11 → 12 transition (ADR-0101, `Proposed`, 2026-10-16;
`PHASE_12_PLAN.md` §3 and §12.13) — *the boundary, schema floor and vocabularies built by `P12-TSK-002`
(2026-10-16; this read "planned: nothing of it is built" until then); the rest built by `P12-TSK-001`…`-028`, and
the paragraph read against the code by the Phase 12 exit review, `P12-DOC-001`, 2026-10-22 (it read "the rest
planned, and the task that builds each part corrects this paragraph to the code" until then).* One context in one module, `bnpl`, serving two products —
pay-in-4 (four instalments, the first at purchase, then every 14 days, no interest - the three later ones equal,
`floor(P / 4)`, the first carrying the remainder *(`P12-DOC-001`, 2026-10-22: read "four equal instalments")*) and pay-in-N
(3, 6 or 12 monthly instalments with interest). **One economic event, two financial flows never
collapsed**: the merchant is financed upfront, net of the merchant discount, and paid out on its normal
cycle; the customer owes the platform a receivable funded from lending capital. **It owns the
agreement, the purchase, the financing and the servicing facts and none of the money**: the versioned
terms, the application, the offer, the immutable versioned agreement and its acceptance evidence, the
purchase (the financed commercial fact — neither checkout's order nor a payment), the merchant
financing record, the customer repayment obligation (identity, product, plan and state only), its
schedule and instalments, billings, pay-in-N accruals, repayments and their allocations, collection
attempts, conditions, late fees, payoff quotes, refunds and their adjustments, disputes, waivers and
reversals — while every principal, interest, fee and credit-balance figure is a balance of a
per-obligation account in the Ledger (7), posted in BNPL's own transaction, never stored. Its
consistency boundaries are two: the purchase, under whose lock every refund and dispute is bounded by
the gross; and the obligation, on whose row every servicing act serialises. The purchase, the
financing, the receivable, the decision's consumption, the capital commitment and checkout's order
commit together or not at all. **The obligation is not a lending loan**: composing Lending as BNPL's
servicing back-end was weighed and refused (a merchant's refund, a merchant-payable birth, pay-in-4's
first-instalment-at-origination and the dispute hold do not fit Lending's machines without bending
them). Its relationships, every one through a port `app` implements and none a build edge but the pure
`creditmath` (`bnpl` depends on `creditmath`, `ledger`, `platform` and `sharedkernel` only, and no
module depends on `bnpl`):
- **Checkout (11)** — upstream, customer-supplier, with one new seam on checkout's side: BNPL is
  chosen inside an `OPEN` session through BNPL's own doors (the session's BNPL options, then an application
  presenting the session token) - checkout's confirmation refuses a `"BNPL"` instrument as unknown
  (`422 platform.ValidationFailed`), so no payment is ever opened for it *(`P12-DOC-001`, 2026-10-22: read "the `"BNPL"`
  instrument on confirmation routed to the application, not to a payment")*; the session stays `OPEN` while the decision runs, so the card door stays usable;
  the acceptance locks the session (`FOR UPDATE`, B3 - the only Checkout row BNPL locks; every other read of
  the session is unlocked) and completes it **by financing** — a new `OPEN → COMPLETED` edge,
  intent-less, with checkout's order born carrying the financing entry (checkout `V004`). A card
  confirmation and a BNPL acceptance of one session complete it once. BNPL never creates a payment
  intent or attempt.
- **Merchant (12)** — upstream for standing, enablement and pricing, downstream for the payable: a
  four-eyes BNPL enablement and a per-merchant BNPL fee schedule pinned per purchase (merchant `V010`);
  the merchant's payable credited by BNPL's financing entry and debited by its refunds (BNPL becomes a
  poster of `MERCHANT_PAYABLE`; `INV-MER-02`'s equation and the drill-down gain the BNPL lines); the
  first merchant-key refund door, bounded by the purchase; the merchant's payouts unchanged —
  **BNPL never pays a merchant**. A refund after a payout may drive the payable below zero only within
  that purchase's own net, recovered before any payout (owner decision B7, `INV-MER-07` amended). The
  merchant never sees an eligibility reason, a score, a credit amount, the customer's schedule or
  delinquency. The Merchant rows BNPL locks: the merchant row and its `ACTIVE` enablement `FOR SHARE` (the standing read
  at the application and the acceptance, re-read by the fee pin after L6, re-entrant; the pin itself an insert),
  and the merchant's `MERCHANT_PAYABLE` ledger row `FOR UPDATE` at (P) - the acceptance, a refund and a dispute's
  opening. A close (the merchant row, then the payables `FOR UPDATE`, then an unlocked read of open BNPL
  disputes), a suspension, a fee-schedule assignment and an enablement's withdrawal each wait one way on them
  *(`P12-DOC-001`, 2026-10-22: added by the Phase 12 exit review)*.
- **Payments (9)** — nothing at purchase: BNPL is not a rail, opens no intent and has no clearing
  position. Payments' outbound machinery pays the merchant out (through Merchant's payout) and its
  pay-ins top up the customer's wallet; a chargeback can arise only on a card-funded top-up, payments'
  dispute, unrelated to the agreement. Payments never calls BNPL. BNPL's acceptance and refund take the
  merchant's payable and the customer's wallet in payments' own book-rail lock order, so the two never
  deadlock.
- **Credit (17)** — upstream, customer-supplier: one decision request per purchase (owner decision
  B5, no spending limit), opened in the application's transaction for the two BNPL credit products; the
  offer reads the decision authoritatively; the acceptance takes the approval up through Credit's
  consumption port (`ConsumerKind.BNPL_AGREEMENT`) under the party's profile lock, taken first. The Credit
  rows BNPL locks: the party's `credit_profile` `FOR UPDATE` (L0) - first, in the acceptance and in a repayment
  reversal's approval, Credit's consumption re-taking it re-entrantly; the decision request's row `FOR UPDATE`
  only inside Credit's own cancellation, after B1 (an application's withdrawal, and the origination step on a
  closed session). The submission inserts Credit's request (the profile ensured, never locked) and the
  offering step reads the request and the decision unlocked *(`P12-DOC-001`, 2026-10-22: added by the Phase 12 exit review)*.
  Exposure stays Credit's judgement over facts BNPL supplies (`PlatformCreditExposure` version 3: the
  party's BNPL outstanding principal, beside Lending's facts, in one statement). BNPL never computes a
  score or an affordability figure and never opens a request outside a checkout.
- **Lending (18)** — a sibling, sharing the `LENDING_CAPITAL` headroom under its L6 row (ADR-0104)
  and the pure engines through `creditmath`; no build edge, no Lending table row locked or written, no `loan` -
  the L6 row is the Ledger's `LENDING_CAPITAL` account row *(`P12-DOC-001`, 2026-10-22: read "no shared row")*.
- **Ledger (7)** — downstream conformist: a new `OwnerKind.BNPL_OBLIGATION` with six per-obligation
  purposes and four operational ones (`BNPL_MERCHANT_FEE_INCOME`, `BNPL_INTEREST_INCOME`,
  `BNPL_FEE_INCOME`, `BNPL_WRITE_OFF_EXPENSE` — seeded, unposted), all closed to free adjustments
  (ledger `V027`); posting and reversal through the ledger's services only. The Ledger rows BNPL locks:
  `ledger_account` rows `FOR UPDATE` at L6 (`LENDING_CAPITAL`) and (P) (`MERCHANT_PAYABLE`, `CUSTOMER_WALLET`),
  and at L8, in one sorted call, `FOR KEY SHARE` on every account posted to and then its `account_balance` rows
  `FOR UPDATE` - the currency's shared `BNPL_MERCHANT_FEE_INCOME`, `BNPL_INTEREST_INCOME` and `BNPL_FEE_INCOME`
  rows only there, after any (P) *(`P12-DOC-001`, 2026-10-22: added by the Phase 12 exit review)*.
- **Accounts / Wallet (5, 6)** — the customer's wallet is the only instalment source (owner decision
  B6 — no card-funded instalment) and a refund's excess destination - the customer's existing wallet only,
  never opened by BNPL (with none to credit, the excess is held as the obligation's credit balance), its ledger
  row locked at (P); BNPL never owns it *(`P12-DOC-001`, 2026-10-22: the existing-wallet rule added)*.
- **Settlement (13) and Reconciliation (14)** — nothing new: BNPL moves no money across a rail, so it
  opens no expectation and adds no evidence; the merchant payout's existing expectation and Phase 8's
  evidence settle the merchant leg, and capital is recognised by the existing bank recognition. BNPL's
  own proof is internal — the three-way proof of the merchant leg, the customer leg and the economic
  identity.
- **Party & Customer (1), KYC/KYB (3), Identity (2), Consent (4)** — standing (the live customer,
  KYC-verified, not suspended) read in the acting transaction at application and acceptance; the
  customer's application, acceptance and payoff on a `MULTI_FACTOR` session; the permissions
  `BNPL_ADMINISTER`, `BNPL_INVESTIGATE`, `BNPL_SERVICE`, `BNPL_SERVICE_APPROVE` and
  `BNPL_DISPUTE_RESOLVE` (identity `V022`); Credit's consent gate for its sources applies unchanged,
  and acceptance is not consent (`INV-IDN-04`) — BNPL creates no consent purpose and stores no identity
  document.
- **Risk (20), Fraud (21), AML (22)** — nothing in Phase 12: a BNPL purchase is screened only through
  Credit and KYC standing; Credit's `CreditRiskSignal` seam still answers `NOT_ASSESSED`.
- **Downstream, deferred:** collections (Case Management, 23 — Phase 13) consumes
  `BnplDelinquencyChanged` and `BnplDefaulted`; write-off, provisioning and the merchant discount's
  effective-interest treatment (Accounting, 25 — Phase 14) map BNPL's purposes. **Production
  originates nothing**: like Lending, BNPL is offered only while an `ACTIVE`, four-eyes BNPL terms
  version exists, production activates none until a real bureau is connected (unresolved #13/#14) and the
  activation gate of `OPERATIONS_RUNBOOK.md` §8.1 is passed (among it the legal review of the merchant
  agreement and the consumer terms and an approved merchant-fee schedule - procedure, not code), and
  Phase 12 proves every flow against the simulators *(`P12-DOC-001`, 2026-10-22: the activation gate added)*.

**Risk (20), Fraud (21), AML / Transaction Monitoring (22) and Case Management (23)** were bounded by the Phase 12 → 13
transition (ADR-0107, written `Proposed`, 2026-10-22; `PHASE_13_PLAN.md` §3) — built by `P13-TSK-001`…`-025` (risk
`V001`…`V018`) and `P13-TST-002`, and read against the code by the Phase 13 exit review, `P13-DOC-001`, 2026-10-27;
the storm, `P13-TST-001`, POSTPONED by the owner until after the project's phases (`POSTPONED_ISSUES.md` PI-004)
*(this read "planned: nothing of it is built; `P13-TSK-001` builds the boundary, schema floor and vocabularies, and
each task corrects this paragraph to the code" until then; where a clause below still stated the design, it is
corrected in place and marked)*. Four contexts
in **one module, `risk`** (`MODULE_ARCHITECTURE.md` M3 and M6; schema `risk`, Flyway `risk/V001…`), kept distinct
inside it by timing and obligation: fraud decisioning is synchronous, in the caller's transaction, latency-bounded; AML
monitoring is asynchronous, retrospective, a leaderless sweep in its own transactions; case management serves both and
never decides money; the risk capability beneath them owns the policy model, the engine, the signal store and the
profile they share. **The module moves no money and owns none**: it never posts, never holds, never releases a hold and
never writes another module's row (ADR-0107, ADR-0112). `risk` depends on `platform` and `sharedkernel` only — no
ledger edge, no edge to any business module; every other domain is reached through ports `risk` declares and `app`
implements, and every seam the consuming modules declare (`transfers.TransferLimitCheck` and `TransferRiskDecision`,
`crossborder.CrossBorderLimitCheck` and `CrossBorderRiskDecision`, `credit.CreditRiskSignal`, payments' new
`WithdrawalRiskDecision` and `PaymentRiskDecision`) is implemented in `app` as an adapter over `risk`'s one
channel-neutral evaluation API; `RiskModuleIsolationTest` refuses every business module and `app`, and every sibling's
isolation test refuses `risk`. The machines are [`RISK_LIFECYCLES.md`](../domain/RISK_LIFECYCLES.md)'s; the invariants
`INV-RSK-01`…`-10`. M3's split triggers stand: AML acquiring retention or isolation obligations fraud does not share, or
monitoring workload harming the synchronous budget — the storm (`P13-TST-001`) measures the second *(`P13-DOC-001`,
2026-10-27: unmeasured — the storm is postponed, PI-004; the deferral is for the owner's ratification,
`PHASE_13_PLAN.md` §1.2 item 76)*.
- **Risk (20)** — *responsibility:* the judgement of every money-moving command before it happens, channel-neutral:
  versioned policies of rules as data, a deterministic scoring engine (`RISK_ENGINE_V1`), durable velocity windows
  written in the caller's own transaction, an immutable assessment per judged command, an explicit decision
  (`ALLOW | CHALLENGE | DENY | HOLD | REVIEW`) with reason codes, a fail-safe answer that is a recorded decision and
  never a silent default, a manual override under four eyes, and the customer risk profile credit's seam reads.
  *Owned state:* Risk Policy Version and its Rules, Risk Assessment (with its Risk Score, Risk Decision and Transaction
  Risk Profile), Decision Override, Velocity Window, Risk Signal, Customer Risk Profile. *Not owned:* any money — the
  judged amount appears only on `INSERT`-only rows and as window sums; the movement's outcome (`transfers`'
  `FAILED(RISK_REFUSED)`, payments' `HELD` and `RISK_REFUSED`, crossborder's `RiskRefused`) is the owning domain's
  committed row; the credit decision and its snapshot (`credit` records the rating it was given). *Transaction
  boundary:* **T-eval** runs inside the caller's transaction after the caller's locks — the pin of the `ACTIVE` policy
  `FOR SHARE`, the inputs, the windows' one-statement upserts in sorted order, the assessment's insert — and returns
  the decision; T-advise is a read at a door with no row; T-pol and T-ovr are their own. *(As built, `P13-DOC-001`,
  2026-10-27: before the inputs, the subjects' advisory locks (R2a, namespace `131`, `P13-TSK-006`); after the seal,
  the alerts and, for a `REVIEW` or `HOLD`, the case (R4); T-advise a risk-only transaction at the door — the pin and
  peeks, nothing written; the order per transaction is `DISTRIBUTED_EXECUTION.md` §3's "The Phase 13 lock order".)*
  *Consistency boundary:* the
  judged command — exactly one assessment per (operation, command ref), sealed with the money's commit; the policy pin
  never answers "neither" during an activation; the latency budget p99 ≤ 50 ms, enforced by a 250 ms statement
  timeout behind a savepoint whose breach becomes the fail-safe decision *(`P13-DOC-001`: the p99 is unmeasured — the
  storm postponed, PI-004, item 76; the 250 ms bounds the windows' locks and reads and the alerts, not the pin (by
  design) nor the case opening (item 95); a movement queued on R2a past it is a `TIMEOUT` fail-safe judged by the
  amount band alone, its count possibly `UNCOUNTED` — a concurrent burst can pass a velocity limit, item 77)*. *APIs:* the internal evaluation and advice
  API; `POST /v1/operator/risk/policies` (+ activation, rejection; `RISK_POLICY_ADMINISTER`),
  `GET …/assessments/{id}` and `…/replay` (`RISK_INVESTIGATE`), `POST …/assessments/{id}/overrides` (+ approval,
  rejection; `RISK_OVERRIDE`), `GET …/parties/{partyId}/profile`; **no customer route reads a risk table**. *Events
  produced:* `RiskDecisionMade`, `LimitBreached`, `RiskSignalRecorded`, `CustomerRiskRatingChanged`,
  `RiskPolicyActivated`; *consumed:* identity's credential, recovery and suspension events (`CredentialChanged`,
  `RecoveryInitiated`, `RecoveryCompleted`, `IdentitySuspended`), `accounts.AccountOpened`, crossborder's
  `BeneficiaryRegistered`, `BeneficiaryActivated` and `BeneficiaryBlocked`, `merchant.MerchantOnboarded`, lending's and
  bnpl's delinquency and default, bnpl's `BnplApplicationSubmitted` and payments' `RefundCompleted` and
  `ChargebackReceived` — each as a signal born once, under the one consumer `risk.signals` *(`P13-DOC-001`,
  2026-10-27: this read "identity's authentication, credential, recovery and session events, kyc's outcomes, … merchant
  enablement and suspension" — `AuthenticationFailed` names no identity, the session's acts are audit actions, kyc
  publishes no case outcome and merchant no enablement or suspension event; none is consumed)*. *Failure behaviour:*
  "unavailable" (a failed read, a throwing rule, the timeout) is the band's fail-safe decision recorded
  `RISK_UNAVAILABLE` and an operational alert (owner decision C1); no `ACTIVE` policy is the compiled `ALLOW` recorded
  `NO_POLICY_ACTIVE`; a duplicate event is one signal; a policy activated mid-evaluation applies to the next.
  *(`P13-DOC-001`, as coded — this read "(no `ACTIVE` policy, a failed read, a throwing rule, the timeout) is the band's
  fail-safe decision recorded `RISK_UNAVAILABLE` / `NO_POLICY_ACTIVE`": with no policy there is no band; the pin's own
  read failing is the compiled `ALLOW` under `RISK_UNAVAILABLE` for every operation, `WITHDRAWAL` and `CROSS_BORDER`
  included — item 75; an amount in another currency than the version's takes the open-ended last band, and its scale is
  not judged — item 89.)* *Security boundary:* the permissions
  `RISK_POLICY_ADMINISTER` and `RISK_OVERRIDE` in the role `RISK_OFFICER`, `RISK_INVESTIGATE` and `RISK_CASE_APPROVE` in
  `RISK_ANALYST` (identity `V023`, `P13-TSK-001`), a partition pairwise-disjoint with every existing role; four eyes on activation and on an override above the
  threshold (C7); self-dealing fails closed; assessment inputs and rule hits `CONFIDENTIAL`, device attributes
  `RESTRICTED-PII` and hashed at the source. *Operational responsibility:* `finapp.risk.evaluation{operation,verdict}`,
  `evaluation.latency`, `evaluation.unavailable`, `rule.hit{operation,rule}`, `policy.active`,
  `velocity.proof{verdict}`, `profile.rating{bucket}`; the activation gate of `OPERATIONS_RUNBOOK.md` §9 — production
  evaluates nothing until a version is activated under four eyes (`PHASE_13_PLAN.md` §1.1).
- **Fraud (21)** — *responsibility:* the fraud rules themselves, run synchronously in T-eval and retrospectively over
  signals: account takeover (a credential change or recovery followed within the lookback by a new payee, a new device
  and a withdrawal), payment and merchant abuse (chargeback and refund ratios per merchant and per customer, a
  merchant's refunds to new accounts, BNPL applications across merchants within a window, a session confirming many
  checkouts), each a `RuleKind` with parameters in a policy version. *(`P13-DOC-001`, 2026-10-27, as built: a
  merchant's refunds to new accounts is not built — deferred, `PHASE_13_PLAN.md` §1.2 item 18; "a session confirming
  many checkouts" is a `VELOCITY_COUNT` rule over the `SESSION` dimension weighted toward `CHALLENGE` — it raises no
  alert and opens no case (item 80); the account takeover's new device is `ABSENT` on every movement, no seam carrying a
  device, so the sequence is judged on the anchor, the new payee and the movement (the device hash an unsalted SHA-256
  of a `User-Agent`, item 87).)* *Owned state:* none of its own — its rules are
  rows of Risk's policy versions; its alerts and cases are Case Management's. *Not owned:* the identity, session,
  merchant or beneficiary it judges; the money. *Transaction boundary:* T-eval's (synchronous) and T-mon's
  (retrospective). *Consistency boundary:* an alert born once per (subject, rule, window start) and folded into one
  open case per (subject, kind). *APIs:* none of its own. *Events:* `AlertRaised`. *Failure behaviour:* a rule that
  throws is "unavailable" and the fail-safe applies; a replayed event raises no second alert. *Security boundary:*
  Risk's; a confirmed account-takeover case may propose `REVOKE_SESSIONS` and `SUSPEND_IDENTITY` through identity's
  existing commands under four eyes and never a credential reset (C10). *Operational responsibility:*
  `finapp.risk.alert{kind,severity}`, `challenge{operation}`; the block and false-positive rates read from the audited
  reports.
- **AML / Transaction Monitoring (22)** — *responsibility:* watching money after it moves and keeping the regulatory
  filing physically distinct from the investigation: the leaderless sweep over the ledger journal through the
  `MonitoredLedger` port from a watermark, every entry classified by posting-key namespace into a monitored
  transaction (an unknown namespace `UNCLASSIFIED` and alerted), the monitoring policy's rules per subject over
  monitored transactions and velocity windows, alerts born once folded into the subject's open `AML` case, the
  customer risk profile recomputed through the `MONITORING` policy version's rating bands, rescreening of `ACTIVE` parties on a screening list version's activation through
  kyc's ports *(`P13-DOC-001`, 2026-10-27, as built: "alerted" is the gauge and the Prometheus rule
  `RiskMonitoringUnclassified` — `AlertKind.UNCLASSIFIED_TRANSACTION` is never raised, item 93; the rules run per
  **party** over its monitored transactions by value date, inside the intake's claim (`-020`); the rescreening covers
  `ACTIVE` and `PENDING` parties, its permits enqueued by `RiskActionSchedule`'s leg in bounded batches and kyc asked
  through its re-screening door, the owner's decision O1)*, and the regulatory report — drafted from a disposed `CONFIRMED_SUSPICIOUS` case, approved by a second
  person, filed through the `RegulatoryFilingProvider` port (simulated; the reference jurisdiction the neutral EUR
  one, C4). *Owned state:* Monitored Transaction and the monitoring watermark, Screening List Version and the
  rescreening permit, Regulatory Report. *Not owned:* the Journal Entry (`ledger`'s truth, referenced never copied),
  the Screening Result (`kyc`'s — risk asks, kyc screens), the money. *Transaction boundary:* **T-mon** — the claim
  `FOR UPDATE SKIP LOCKED`, the intake's batch and the rule leg per party in **one** transaction, the watermark held to
  its commit; the profile leg one head per transaction *(`P13-DOC-001`, 2026-10-27: this read "the claim … commits
  alone; then the intake and the per-subject evaluation in their own transactions" — the design `-019` and `-020`
  replaced)*; **T-rep** and **T-list** their own; the filing call holds no connection. *Consistency boundary:* the journal —
  every monitored transaction born once per entry, a census proving the record never diverges and reprocessing from
  any watermark idempotent; the report immutable once `FILED`. *(`P13-DOC-001`: the census compares the journal's entry
  ids behind the watermark with the record's, never amounts — item 82; the intake's settle horizon
  (`finapp.risk.monitoring.horizon`, 60 s) is a bound, not enforced — a posting committing later than it after its id
  was minted is skipped until an operator orders reprocessing, the census naming it — item 92.)* *APIs:* `POST /v1/operator/risk/cases/{id}/reports`
  (+ approval, filing; `AML_REPORT_APPROVE` / `AML_REPORT_FILE`), `POST …/screening-lists` (+ activation) and
  `POST …/monitoring/reprocessing` (`RISK_POLICY_ADMINISTER`, audited `risk.ReprocessingOrdered`), the audited reports `GET /v1/operator/reports/risk/{decisions,alerts,cases,velocity}`.
  *Events produced:* `AlertRaised`, `RegulatoryReportFiled` (report id and jurisdiction — never the subject),
  `RiskSignalRecorded`; *consumed:* `transfers`, `payments` and `crossborder` completions as hints that pull the
  watermark's permit forward — never as the record *(built by `P13-TSK-005`, 2026-10-23: `app`'s `RiskSignalIntake`
  under consumer `risk.signals` — identity's credential, recovery and suspension events, `accounts.AccountOpened`,
  crossborder's beneficiary registered / activated / blocked, `merchant.MerchantOnboarded`, lending's and bnpl's
  delinquency and default events as born-once signals; the completions as hints to `MonitoringHints`, `NONE` until
  `-019`; `RiskSignalRecorded` the signal id, kind, subject kind and reference)* *(`P13-DOC-001`, 2026-10-27: since
  `P13-TSK-014` also bnpl's `BnplApplicationSubmitted` and payments' `RefundCompleted` and `ChargebackReceived`; since
  `-019` the hints pull the watermark's permit through `WatermarkMonitoringHints`; risk's own `SCREENING_CLEARED` and
  `SCREENING_HIT` are recorded by the rescreening leg, never consumed)*. *Failure behaviour:* a sweep crashing mid-claim lapses its permit
  and the next claim re-reads from the committed watermark; a filing provider unavailable or unknown leaves `FILED`
  committed with the reference pending, reconciled by the inquiry leg (`risk.ReportAcknowledged` / `risk.ReportRejected`),
  never re-filed (the payout precedent). *Security
  boundary:* `AML_OFFICER` {AML_REPORT_APPROVE, AML_REPORT_FILE} — the filing desk, disjoint from the analyst's
  (`P13-TSK-001`: an analyst never files, an AML officer never investigates) *(`P13-DOC-001`, 2026-10-27: the roles'
  grants are disjoint, their holders need not be — identity's arbiter is one live assignment **of each role** per
  identity, so one identity may hold `RISK_ANALYST`, `RISK_OFFICER` and `AML_OFFICER` at once; for the owner's
  ratification, `PHASE_13_PLAN.md` §1.2 item 86)*; the report narrative
  `RESTRICTED-PII`, readable by `AML_REPORT_*` holders only; **tipping-off**: no AML alert, case, report or evidence
  reaches a customer- or merchant-facing surface, a log, an event payload or a metric tag. *Operational
  responsibility:* `finapp.risk.monitoring.lag`, `monitoring.unclassified`, `report{status}`, `sweeper.enabled`; the
  alerts "monitoring lag over an hour", "unclassified entries > 0", "a filing unknown".
- **Case Management (23)** — *responsibility:* the unit of a person's judgement for fraud, ATO, merchant abuse, AML and
  (C3, a thin slice) collections — the case opened by T-eval, by the sweep or by hand, taken, worked by append-only
  investigator actions under a `revision` compare-and-set, escalated, disposed under four eyes where the disposition
  confirms or acts, and every downstream effect a risk action: a keyed command through the owning domain's existing
  door, proposed and approved in the case and dispatched by `RiskActionSchedule`. *Owned state:* Alert, Case,
  Investigator Action, Disposition and Escalation (as actions), Risk Action (the record). *Not owned:* the effect —
  identity's suspension and session revocation, accounts' suspension, merchant's suspension, crossborder's beneficiary
  block, payments' held-withdrawal release or denial and kyc's rescreening are their owners' commands, keyed
  `risk.action:<id>` and audited by them; a reversal, which a case may only recommend (`REFERRED`); kyc's Review Task
  and reconciliation's Investigation (other case-like states with their own owners, `MODULE_ARCHITECTURE.md` §5);
  collections operations beyond the case's opening. *(`P13-DOC-001`, 2026-10-27, as built by `P13-TSK-017`/`-022`:
  accounts has no suspension command and crossborder no block command — `SUSPEND_ACCOUNT`, `REINSTATE_ACCOUNT` and
  `BLOCK_BENEFICIARY` are unavailable, never proposable; payments' held card capture and void join the held
  withdrawal's release and denial; kyc's rescreening is the rescreening leg's question, keyed
  `risk.rescreen:<permit id>#<n>` — `RESCREEN_PARTY` reads available but nothing proposes or dispatches it, item 93.)*
  *Transaction boundary:* **T-case** — every act under the case row
  and the compare-and-set; **T-act** — the action row, then the owning domain's command on **its** lock order, never
  holding the case row *(as built: the claim of the action row committed first, the owner's command run holding no
  risk row at all, the outcome recorded after — `DISTRIBUTED_EXECUTION.md` §3)*. *Consistency boundary:* one open case per (subject, kind) — an alert raised while the subject has an open case of
  the kind attaches to it; both concurrent investigators'
  actions kept, one answering `409 risk.CaseRevisionStale`; a disposed case never re-opened — a late event opens a
  new case referencing it; ten approvers, one dispatch; a duplicated downstream command replayed by the domain's key.
  *APIs:* `GET /v1/operator/risk/alerts`, `POST …/alerts/{id}/suppression` (+ approval, rejection — four eyes),
  `POST`/`GET /v1/operator/risk/cases` (+ `{id}`),
  `POST …/cases/{id}/actions`, `…/dispositions` (+ approval, rejection), `…/risk-actions` (+ approval, rejection) —
  `RISK_INVESTIGATE` to work, `RISK_CASE_APPROVE` to approve another holder's act; never the operator's own party.
  *Events:* `CaseOpened`, `CaseEscalated`, `CaseDisposed`, `RiskActionRequested`, `RiskActionCompleted` — ids, codes
  and statuses only. *Failure behaviour:* an owning domain's refusal (already suspended) is the action's recorded
  outcome, never an error; a domain timeout is re-driven under the same key; a `FAILED` action is re-proposable, never
  re-dispatched by itself; a downstream payment failure after a decision leaves the decision standing. *(`P13-DOC-001`,
  as coded: an effect already in place is `DONE(ALREADY)`, an owner's refusal `FAILED(REFUSED)` — each recorded once,
  never retried; an approved prohibition is written to the profile after the case commits, and
  `CaseProhibitionDriver`'s re-drive has no per-item failure handling — one failing prohibition holds up those behind
  it, item 84.)* *Security
  boundary:* four eyes on every `CONFIRMED_*` disposition and every action; self-approval and self-dealing refused
  and recorded `FAILED`; step-up for `CONFIRMED_*`; notes bounded and screened, evidence as references; the
  tipping-off control above. *(`P13-DOC-001`: and on `REFERRED` and any disposition that sets `PROHIBITED`; no
  disposition issues an action ("where the disposition confirms or acts" above reads "confirms, refers or prohibits").
  One `RISK_INVESTIGATE` holder may open a case by hand on an open alert and close it `CLOSED_*` alone, reaching what the
  suppression's four eyes control — for the owner's ratification, `PHASE_13_PLAN.md` §1.2 item 78.)* *Operational responsibility:* `finapp.risk.case{kind,status}`, `case.age{kind}`,
  `case.backlog{kind}`, `case.resolution{kind}`, `action{kind,outcome}`, `action.failed{kind}`,
  `alert.suppressed{reason_code}`; the alerts "a case older than its SLA", "an action failed".

Its relationships, every one through a port `app` implements or a seam the consumer declares, and none a build edge:
- **Transfers (8)** — upstream for the judgement, downstream for the outcome: `TransferExecution` consults
  `TransferLimitCheck` and `TransferRiskDecision` in-lock after the source and destination locks; `app`'s adapters run
  T-eval there; a refusal commits transfers' own `FAILED(RISK_REFUSED)` / `FAILED(LIMIT_REFUSED)`, unchanged since
  `P4-TSK-010`; the door's step-up advice runs before the command.
- **Payments (9)** — two new seams, payments' own: `WithdrawalRiskDecision`, consulted under the wallet account's lock
  after the hold is placed and before the dispatch is committed — `HOLD` commits payments' new `HELD` with the hold
  kept until `held_until` (the policy version's hold validity, default 72 h — reached, a leg of payments' existing
  `WithdrawalResolutionSchedule` fails it `RISK_REFUSED` with detail `HOLD_EXPIRED` and releases the hold, the case
  staying open), `DENY` `FAILED(RISK_REFUSED)` with the hold released (payments `V033`, `P13-TSK-009`); `PaymentRiskDecision`,
  consulted in the checkout confirmation's Tx1 after the eligibility checks and before the routing decision commits —
  `DENY` terminal for the intent (not re-confirmable; the customer creates a new intent), its decision row sealed in Tx1
  with `PaymentFailureReason.RISK_REFUSED` (payments `V034`, `P13-TSK-010`), `HOLD` in slice 2 authorising and deferring
  the capture. A held withdrawal's release or denial goes through payments'
  existing door on payments' lock order — an approved override's release keyed `risk.action:<override id>` from its
  approval door (`P13-TSK-012`), a Risk Action once `P13-TSK-017` lands; the customer reads `HELD` as a status word only (C6).
  *(`P13-DOC-001`, 2026-10-27: `-017` landed — an approved override's action and a case's are both T-act's, dispatched
  under `risk.action:<id>` through payments' four held doors, a held card payment's capture and void beside the
  withdrawal's release and denial.)*
- **Cross-Border Payments (16)** — `CrossBorderAuthorization` consults `CrossBorderLimitCheck` and
  `CrossBorderRiskDecision` in its first transaction before the offer is accepted; a refusal consumes nothing; the
  contract unchanged since `P9-TSK-016`.
- **Credit (17)** — downstream of the Customer Risk Profile: `app`'s `RiskCreditRiskSignal` implements
  `credit.CreditRiskSignal` with seam version 2, answering the current rating's code and the profile version, recorded
  in the Decision Snapshot; a decision made under version 1 replays with `NOT_ASSESSED` (`INV-CRD-01`);
  `NotAssessedUntilPhase13` is retired by `P13-TSK-007`. BNPL and loan applications are judged through credit's
  signal, not a seam of their own (B5).
- **Ledger (7)** — read only, through `MonitoredLedger`: the journal entries since the watermark, ordered by sequence;
  **no posting, no hold, no release** — `risk` has no ledger edge. *(`P13-DOC-001`, 2026-10-27: the journal has no
  sequence column — the entries are read by their UUIDv7 ids as a keyset after the watermark, behind a settle horizon on
  the database clock, `-019`.)*
- **Identity, Authentication & Authorization (2)** — upstream for signals (`AuthenticationFailed`,
  `AuthenticationLocked`, `MfaChallengeFailed`, `CredentialChanged`, `ContactChannelAdded`, `RecoveryInitiated`,
  `RecoveryCompleted`, `SessionRotated`, `IdentitySuspended`; the session's assurance read by the `CHALLENGE` mapping;
  the device hashed at the source) and downstream for actions (`IdentityAdministration.suspend`, reinstatement,
  session revocation — keyed, audited by identity; never a credential reset); the six permissions and three roles
  (identity `V023`). *(`P13-DOC-001`, 2026-10-27, as built: the signals are `CredentialChanged`, `RecoveryInitiated`,
  `RecoveryCompleted` and `IdentitySuspended` only — `AuthenticationFailed` names no identity, and the others are audit
  actions, not events; the session's assurance is read live through `IdentitySessionAssurance`; no device reaches risk —
  no identity event or seam carries one.)*
- **KYC/KYB (3)** — upstream: kyc stays the screening owner — clearances read through a port and recorded as signals;
  rescreening on a list version's activation requested through `CounterpartyScreenings` and the case-bound screening
  (`P13-TSK-022`); `KYC_NOT_APPROVED` and `SCREENING_HIT` as rule kinds; kyc's Review Task is not risk's Case.
  *(`P13-DOC-001`, 2026-10-27, as built: the rescreening goes through kyc's **re-screening door** — the owner's decision
  O1, `VerificationRunService.rescreen`, kyc `V011`, a post-decision `RESCREEN` check keyed `risk.rescreen:<permit
  id>#<n>` — and `CounterpartyScreenings` is not used; kyc publishes no event risk consumes.)*
- **Accounts / Wallet (5, 6), Merchant (12), Cross-Border Beneficiary (16)** — upstream for signals (`AccountOpened`,
  merchant enablement and suspension, `BeneficiaryRegistered`) and downstream for actions through their existing
  commands (`SUSPEND_ACCOUNT` / `REINSTATE_ACCOUNT`, `SUSPEND_MERCHANT`, `BLOCK_BENEFICIARY`); a wallet with a held
  withdrawal cannot close while the hold is `ACTIVE` (accounts' existing rule); a merchant sees only the operation's
  generic refusal (OQ-9). *(`P13-DOC-001`, 2026-10-27, as built: the signals are `AccountOpened`,
  `merchant.MerchantOnboarded` — merchant publishes no enablement or suspension event — and crossborder's
  `BeneficiaryRegistered`, `BeneficiaryActivated` and `BeneficiaryBlocked`; of the actions only `SUSPEND_MERCHANT` has a
  command — accounts has no suspension and crossborder no block, so the other three are unavailable (`P13-TSK-017`).)*
- **Lending (18), BNPL (19)** — upstream for signals (`LoanDelinquencyChanged` / `LoanDefaulted`,
  `BnplDelinquencyChanged` / `BnplDefaulted`); `P13-TSK-025` opens a `COLLECTIONS` case on default, facts only (C3);
  contact strategy, promises to pay, placement and write-off stay out. *(Built 2026-10-26: the borrower is placed for the
  case through the two modules' published definer views (`party_principal_exposure`, read by id in `app`), neither module
  changed; nothing of the case reaches either.)*
- **Consent (4)** — nothing new: the evaluation is a platform control over the platform's own records; no consent
  purpose is created (`P13-TSK-001` confirms).
- **Audit (26)** — every act in `AUDITABLE_ACTIONS.md`'s `risk.*` set with actor, time, target, reason where
  required, correlation and outcome; summaries carry counts and codes, never amounts or names *(`P13-DOC-001`,
  2026-10-27: this read "planned `risk.*` set" — built)*.
- **Accounting / General Ledger (25), Reporting (27)** — nothing: no GL mapping; the four risk reports are audited
  operator reads.

**Production evaluates nothing** until a risk policy version is activated under four eyes and
`OPERATIONS_RUNBOOK.md` §9's activation gate is passed; until then every seam answers `ALLOW` with `NO_POLICY_ACTIVE`,
recorded, and the monitoring sweep runs from the first deployment and raises no alert until a monitoring policy is
active (`PHASE_13_PLAN.md` §1.1). *(`P13-DOC-001`, 2026-10-27: the sweep's alerts — two facts alert with no policy in
force: a default's `OBLIGATION_DEFAULTED` (`-025`) and a rescreening's `SCREENING_HIT` (`-022`).)*

**Accounting / General Ledger (25) and Reporting (27)** were bounded by the Phase 13 → 14 transition (ADR-0113,
`Proposed`, 2026-10-29; `PHASE_14_PLAN.md` §4) — *the boundary built by `P14-TSK-001` (2026-10-29): the module with
its edges (`AccountingModuleIsolationTest`), the schema floor (`V001`, no table), the closed vocabularies, the nine ports
declared empty, the roles as a partition (identity `V025`; the security boundary below); no state yet - each task
corrects this paragraph to the code.* Two contexts in **one module, `accounting`** (schema `accounting`,
Flyway `accounting/V001…`), regulatory reporting inside it behind an adapter (split trigger: regulatory reporting
acquiring retention or segregation obligations the GL does not share). **The module creates no competing record and
holds no balance**: every GL figure is a function of the ledger's journal lines × the mapping version the period pins;
`accounting` depends on `platform` and `sharedkernel` only — no ledger edge — and reaches the journal, the chart, the
posting cutoff, the prior-period adjustment door, the product proofs, the accrual completeness, reconciliation's
disclosure and fx's reference rates through ports `app` implements.
- **Accounting / General Ledger (25)** — *responsibility:* the GL as a versioned mapping over the one journal; accounting
  periods and their four-eyes close; the posting rule register and GL ↔ ledger ↔ source checks; valuation rate sets;
  prior-period adjustments. *Owned state:* GL Mapping Version (with its GL Accounts and rules), Accounting Period (with
  its Period Close, close run and Close Checks), Valuation Rate Set, Accounting Break, Prior-Period Adjustment record,
  the Posting Rule register. *Not owned:* any balance or line (the ledger's); the Posting Cutoff (the ledger's row, moved
  by the ledger's API in accounting's lock transaction); the product subledgers and their proofs (lending, bnpl, fx,
  reconciliation, merchant — read, never re-implemented); reconciliation's breaks and suspense (disclosed, never
  copied); write-off (lending's and bnpl's event, owner decision C6). *Transaction boundary:* own; the lock, unlock and
  reopen edges carry the ledger's cutoff edge in the same transaction under namespace `14` (ADR-0114). *Consistency:*
  strong at the edges (conditional `UPDATE`s, unique births), derived for every figure. *APIs:*
  `/v1/operator/accounting/…`. *Events:* `AccountingPeriod*`, `GlMappingVersionActivated`, `AccountingBreakRaised`,
  `PriorPeriodAdjustmentPosted`; consumes none for correctness. *Failure:* a posting dated into a locked period is
  refused by the ledger; a crashed check is re-claimed by lease. *Security:* `ACCOUNTANT` proposes,
  `FINANCIAL_CONTROLLER` approves, four-eyes by `CHECK` *(`P14-TSK-001`: the grants a partition - `ACCOUNTING_READ` the
  accountant's alone, the controller reading what it decides under its approve permissions; the holders need not be
  disjoint, so approver ≠ proposer is the binding control, `accounting.SelfApprovalRefused`)*.
- **Reporting (27)** — *responsibility:* immutable, reproducible report runs (trial balance, GL activity, statements,
  revenue, receivables and payables, settlement positions, suspense, FX results, loan portfolio, BNPL exposure,
  reconciliation and close status) with drill-down to journal lines; regulatory obligations and submissions behind
  `RegulatoryReportingProvider` (simulated, owner decision C4). *Owned state:* Report Run, Regulatory Obligation,
  Regulatory Submission. *Not owned:* the operational live reports (lending, bnpl, reconciliation, merchant, risk keep
  theirs, labelled operational). *Transaction boundary:* claim and completion its own; reads `REPEATABLE READ READ ONLY`
  (`INV-ACC-14`). *Failure:* a generator's death re-claimed by lease; a provider's unknown answer an inquiry.
- **Ledger (7)** — gains the Posting Cutoff, an index for period reads and the `ACCOUNTING` adjustment origin; its
  grants, its single-writer rule and its trial balance are unchanged.
- **Lending (18)** — its daily accrual floors its posting date at the first open date when the accrual date is locked
  (C3, `P14-TSK-006`); write-off, if C6 (A), is its four-eyes command.
- **Reconciliation (14), FX (15), BNPL (19), Merchant (12)** — read through ports; nothing of theirs changes.

1. Party & Customer
2. Identity, Authentication & Authorization
3. KYC/KYB
4. Consent
5. Accounts
6. Wallet
7. Ledger
8. Transfers
9. Payments
10. Payment Methods
11. Checkout
12. Merchant
13. Settlement
14. Reconciliation
15. FX
16. Cross-Border Payments
17. Credit
18. Lending
19. BNPL
20. Risk
21. Fraud
22. AML / Transaction Monitoring
23. Case Management
24. Notifications
25. Accounting / General Ledger
26. Audit
27. Reporting
28. API / Integration Platform
29. Disputes

Every context is mapped to exactly one module in
[`MODULE_ARCHITECTURE.md`](MODULE_ARCHITECTURE.md) §3, with merges justified and split
triggers recorded.

Important rule:

These are domain boundaries, not an automatic list of microservices.

For every proposed technical service, document:
- bounded context
- ownership
- authoritative state
- consistency boundary
- data store
- commands
- queries
- domain events
- integration events
- failure behavior
- security boundary

Prefer fewer, stronger modules over premature distribution.
