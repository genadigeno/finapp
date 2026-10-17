# Phase 12 — Buy Now, Pay Later (BNPL)

Written by the Phase 11 → 12 transition (2026-10-16), after the Phase 11 completion gate confirmed Phase 11
`COMPLETE` after repair ([`reviews/PHASE_11_TO_12_TRANSITION.md`](reviews/PHASE_11_TO_12_TRANSITION.md)). Decisions in
ADR-0101 (the BNPL bounded context: `bnpl` owns the agreement, purchase, financing and servicing facts and none of the
money; lending's pure engines shared through `creditmath`, lending's `loan` not composed), ADR-0102 (BNPL products, terms
and the versioned agreement: pay-in-4 and pay-in-N), ADR-0103 (BNPL accounting: per-obligation accounts, the financing
entry, the merchant discount and the posting rules), ADR-0104 (BNPL funded from lending capital: the shared headroom and
credit exposure version 3), ADR-0105 (the refund policy: latest instalments first, the excess to the customer, the
merchant fee pro-rated back, and the merchant payable's third bounded negative source) and ADR-0106 (BNPL disputes and
the single refund bound). The machines are in [`BNPL_LIFECYCLES.md`](../domain/BNPL_LIFECYCLES.md); the invariants are
`INV-BNPL-01`…`10` in [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md) (and `INV-MER-07`'s amendment);
the exit criteria are `PHASE_GATES.md` §5 Phase 12, written by this transition; the tasks are `P12-TSK-001`…`026`,
`P12-TST-001`…`002` and `P12-DOC-001` in [`BACKLOG.md`](BACKLOG.md).

*Nothing in this document is built yet. Each task that builds a part corrects this plan where the code teaches
otherwise, and the Phase 12 exit review (`P12-DOC-001`) reads the whole of it against the code.* The owner's transition
decisions B1–B8 (§2.2) are folded into the sections below; the recorded assumptions A1–A22 (§2.3) are the product
defaults the plan builds on, each a field of a versioned terms row; the draft's open questions OQ-1…OQ-20 are settled
in §24 — two by explicit decision (B5, B6, B7) and the rest by the owner's acceptance of the draft's default (B8),
each recorded for later review.

**Grounding.** Read in the documents (`PHASE_11_PLAN.md`, `DISTRIBUTED_EXECUTION.md` §3, `FINANCIAL_INVARIANTS.md`,
`MODULE_ARCHITECTURE.md`, `GLOSSARY.md`, `DELIVERY_PLAN.md` §Phase 12, `PHASE_GATES.md` §5, `PHASE_11_REVIEW.md`,
`PHASE_11_TO_12_TRANSITION.md`) and in the code (`checkout/`, `merchant/`, `payments/`, `settlement/`,
`reconciliation/`, `ledger/`, `credit/`, `lending/`, `app/`) on `phase-11-to-12-transition` at `36f1b201`. **Highest
migrations today** (re-verified at the transition): checkout `V003`, merchant `V009`, payments `V032`, settlement
`V017`, reconciliation `V022`, ledger `V026`, credit `V021` (no `V018`), identity `V021`, lending **`V030`** (the gate's
`V029` and `V030`). **Next free:** credit `V022`, ledger `V027`, identity `V022`, merchant `V010`, checkout `V004`,
lending `V031` (Phase 12 plans no lending migration — §8), `bnpl` `V001` (new); advisory namespace **`12`**;
**ADR-0101**; `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` holds **26**. The one point the draft
left to confirm — whether a wallet-paid checkout capture can lock the merchant payable before a wallet row — was
settled by reading the code (§7.3 (b)): **it can**, so BNPL takes the two as one pair element in the same order.

**Code facts this plan rests on** (file paths relative to the repo root):
- `checkout` depends on `platform` only (`CheckoutModuleIsolationTest`); the orchestration is `app`'s
  (`app/.../checkout/CheckoutSessions` — `open`, `paymentOpened`, `completed`, `abandon`; `CheckoutService.confirm`).
  The session machine (`CheckoutSessionStatus`, checkout `V002`'s edge trigger): `OPEN → PAYMENT_PENDING | EXPIRED |
  ABANDONED`, `PAYMENT_PENDING → COMPLETED | EXPIRED`, `EXPIRED → COMPLETED_LATE`. `checkout_session.payment_intent_ref`
  is set once (`V003` partial unique); `checkout_order` is born in the capture's transaction with `captured_entry_ref`.
  The payment choice is made only at confirmation: `ConfirmSessionRequest(sessionToken, paymentMethodId, instrument)`,
  `instrument ∈ {"WALLET", null}` — **the seam where `"BNPL"` plugs in**. Completion today is
  `CheckoutSessions.completed(uow, intentRef, entryRef, correlation)`, called from `MerchantBoundCaptureComposition`'s
  completion consumer (`CheckoutBeans.captureCompletion`) **inside the capture's transaction, after the capture's
  postings** — it locks the session (`findByIntentForUpdate`) after the payable's rows. It is keyed by a payment intent,
  so a BNPL completion needs a new, intent-less path.
- `merchant`: `MerchantSettlement.settle` posts the capture as `DR <clearing> G / CR MERCHANT_PAYABLE G; DR
  MERCHANT_PAYABLE f / CR FEE_REVENUE f` under `payment-capture:`; `PaymentFeePin` (merchant `V005`) pins the fee
  version per payment intent; `RefundFeePolicy {RETAINED, RETURNED}` on `fee_schedule_version`; `FeeCalculation.
  leastReturnedFee`; `MerchantPayable`'s drill-down and its `COUNTERPARTIES` list (`SETTLEMENT_CLEARING`,
  `INSTANT_CLEARING`, `CUSTOMER_WALLET`, `PAYOUT_CLEARING`, `CHARGEBACK_RECOVERABLE`, `DISPUTE_COSTS`). **Payouts are on
  demand** (`POST /v1/merchant/payouts`, `MerchantPayouts`: lock the payable, hold, commit `DISPATCHED`, wire;
  `DISPATCHED → COMPLETED | FAILED | UNKNOWN`, `UNKNOWN → COMPLETED | FAILED` by `MerchantPayoutResolution`'s query
  sweep; a negative payable refuses every payout). **Refunds today are operator-only** (`POST /v1/payments/{id}/refund`,
  `PAYMENT_REFUND`; `MerchantBoundRefundComposition`) — there is no merchant-key refund route.
- `payments`: no system- or merchant-initiated card charge exists (the only `SYSTEM` door is the outbound
  `Withdrawals.withdrawOnInstruction`); `CaptureComposition` is the seam a capture's lines come from. **The book (wallet)
  rail locks the two participating ledger-account rows — the customer's wallet and the merchant's payable — `FOR UPDATE`
  in `UUID.compareTo` order** (`PaymentOutcomes.lockPairInFixedOrder`, taken by `dispatchBook` inside the confirmation's
  transaction — `PaymentConfirmation` deliberately takes no `FOR SHARE` on the book branch for that reason — and by
  `PaymentRefund`'s book arm before its payable hold).
- `credit`: `CreditProduct` is a closed enum {`PERSONAL_LOAN`, `CREDIT_LINE`} with `TermBounds` in **months only**,
  mirrored by `product IN (…)` `CHECK`s in credit `V004`, `V008`, `V010`, `V011`, `V013`; `DecisionRequests.Terms`
  carries `product`, `requested`, `Optional<Integer> termMonths`; the term rule `product.revolving() ==
  termMonths.isPresent()`; request validity 7 days, decision validity 30 days. `ConsumerKind {LOAN_AGREEMENT}` — its
  javadoc reserves BNPL for Phase 12 and `CreditDecisionConsumptionDatabaseTest` asserts `'BNPL'` refused today.
- `lending`: `RepaymentFrequency {MONTHLY}` only; `LoanProduct` deliberately excludes BNPL (`LoanProductTest`;
  `lending/package-info.java`: "bnpl will reference lending's concepts by identifier and own neither; lending never knows
  a merchant"). `ScheduleEngineV1` takes a `LoanTerms` whose product is a monthly `INSTALMENT` (r = 0 already supported:
  `P/n` rounded `UP`, the last the remainder); `AccrualEngineV1` takes `AccrualTerms` and `AllocationEngineV1` takes
  `AllocationRules` + `DueState` — **both product-agnostic records**. Every stateful service and proof is bound to
  `lending.loan`. Capital: `LendingCapitalHeadroom.underLock(uow, currency)` (L6) sums `LOAN_PRINCIPAL +
  LOAN_PRINCIPAL_DUE` **by purpose across every account** of the currency, in Java (no lending migration holds it).
  Since the gate: every principal fact is floored at `lending.principal_value_floor` (R1), and held credit is applied to
  past-due items by a catch-up application (R2) — both patterns BNPL inherits (§12.2, §12.6).
- `ledger`: `OwnerKind {CUSTOMER, MERCHANT, OPERATIONAL, SUSPENSE, COUNTERPARTY, LOAN}`; `FEE_REVENUE` exists;
  `MERCHANT_PAYABLE` is not in `closedToFreeAdjustments()`.
- `reconciliation`: `ExpectationKind` has no BNPL member and BNPL needs none (it adds no external evidence).

---

## 1. Objective

Combine merchant commerce with credit so that every figure is explainable years later:
**checkout → eligibility (credit decision) → offer and acceptance → purchase authorisation → merchant financing
(merchant payable, net of the merchant fee) → customer obligation (a receivable funded from lending capital) →
instalment collection and allocation → refunds, disputes, delinquency → ledger → merchant payout → settlement →
reconciliation.**

Two products (owner decision B1):

- **`BNPL_PAY_IN_4`** — 4 equal instalments, the first collected at purchase, the next three every 14 days; no
  customer interest; the merchant pays a merchant discount fee; late fees per pinned terms.
- **`BNPL_PAY_IN_N`** — 3, 6 or 12 monthly instalments with customer interest (fixed rate, ACT/365F daily accrual —
  the Phase 11 conventions); may also carry a merchant fee.

One economic event, two financial flows that must never be collapsed (`GLOSSARY.md` §Customer Payment / Merchant
Settlement; `DELIVERY_PLAN.md` §Phase 12 §5):

| Flow | Direction | Amount | When | Accounts |
|---|---|---|---|---|
| **Merchant financing** | platform → merchant (a liability, then a payout) | purchase − merchant fee | at purchase (payable); paid out by the merchant's existing payout machinery | `MERCHANT_PAYABLE`, `BNPL_MERCHANT_FEE_INCOME` |
| **Customer obligation** | customer → platform (a receivable) | the purchase amount (+ interest for PN) | born at purchase, collected per schedule | the per-obligation receivable accounts (§12) |

The platform carries the customer's credit risk; the receivable is funded from **`LENDING_CAPITAL`** (owner
decision B2) and is subject to Phase 11's headroom invariant (`INV-LND-13`) under L6.

### 1.1 The production reality (inherited, not new)

Phase 11 §1.1 / L11 holds unchanged: production composes only fail-safe credit sources, so **no production credit
decision can be `APPROVED`**; BNPL therefore cannot originate in production in Phase 12 either. BNPL ships closed the
same way lending did: a BNPL product is offered only while an `ACTIVE`, four-eyes BNPL terms version exists for it
(never migration-activated); production activates none (`422 bnpl.ProductNotOffered`); every exit criterion is
proven against the simulators, the storm and the battery; the runbook gains the BNPL activation preconditions
(the lending ones, plus legal review of the merchant agreement and the consumer terms, and the merchant-fee schedule
approved). Recorded as an accepted limitation, not a deviation, and attached to the existing debt row
"Lending cannot originate in production (#13, #14)".

---

## 2. Why this phase is shaped by decisions already taken

### 2.1 Reuse map (never re-implement)

| Need | Existing abstraction | How BNPL uses it | Reasoning |
|---|---|---|---|
| Eligibility / credit decision | `credit.DecisionRequests.submit`, `CreditDecisions`, the policy/scorecard versions, underwriting (ADR-0084…0089) | Two new `CreditProduct`s `BNPL_PAY_IN_4`, `BNPL_PAY_IN_N`; a BNPL application opens one decision request per purchase (§12.1) | Credit owns every judgement; BNPL never reads a score (`INV-CRD-04`) and never leaks credit data to the merchant |
| Taking up an approval | `credit.CreditDecisionConsumptions.consume` (the `SECURITY DEFINER` path, `P11-TSK-001`) | New `ConsumerKind.BNPL_AGREEMENT` (reserved in `ConsumerKind`'s javadoc and ADR-0091 for Phase 12) | One decision funds at most one agreement (`INV-LND-06`'s rule extended) |
| Exposure | `credit.PlatformCreditExposure` v2 (`app`'s `LendingCreditExposure`, lending `V007`'s view) | **Version 3** adds BNPL committed + outstanding principal from a bnpl definer view, one statement (§12.10) | Credit keeps the judgement; a BNPL purchase must be visible to the next decision |
| Profile lock | `CreditProfiles.lockForDecision` — L0 | Taken first by every BNPL transaction that raises a party's exposure (acceptance/purchase, a repayment reversal) | The Phase 10/11 no-gap rule |
| Capital | `LENDING_CAPITAL` + headroom under L6 (ADR-0096, `INV-LND-13`) | Headroom formula extended with BNPL outstanding principal (there is no BNPL committed term - acceptance and financing are one commit, §12.4; *read "BNPL committed and outstanding principal" until `P12-TSK-005` (2026-10-17) built it*); judged under L6 at purchase | Owner decision B2; one capital pool, one proof |
| Servicing engines | `AllocationEngineV1` (`AllocationRules`, `DueState` — product-agnostic), `AccrualEngineV1`/`Act365Fixed` (`AccrualTerms` — product-agnostic), `ScheduleEngineV1` (takes `LoanTerms` of a monthly `INSTALMENT` product) | **Reused as libraries**: allocation (both products) and accrual (PN) as they stand, built from their records' canonical constructors, not `of(LoanTerms)`; PN's schedule through a `ScheduleTerms` overload of `ScheduleEngineV1` added beside the `LoanTerms` one (golden hashes unchanged); a new pure `BnplScheduleEngineV1` for P4's biweekly split (§12.2) | `RepaymentFrequency` is `MONTHLY` only; `LoanProduct` excludes BNPL by design; P4 has no accrual |
| Servicing patterns | Lending's born-once rows + triggers, the one-statement `SKIP LOCKED` sweep with a database-stamped permit, conditions (DPD) derived and append-only, late fee once per item, four-eyes waivers/reversals, payoff, the subledger and replay proofs | Re-applied in `bnpl`'s own schema and code (§3.2 decision) — the *shape*, not the rows | See §3.2: lending's `loan` row cannot be a BNPL obligation without bending its lifecycle, disbursement and amendment rules |
| Posting / reversal | `ledger.PostingService.post`, `ReversalService`, `lockBalancesInOrder`, `HoldService` | Every BNPL money effect one entry in bnpl's transaction (ADR-0043 shape) | `INV-LED-04` |
| Merchant payable | `MERCHANT_PAYABLE` (ledger's, merchant's purpose; `INV-MER-02`) | Credited at purchase, net of the BNPL merchant fee; refunds debit it | Owner decision B2: the merchant is paid on its **normal** cycle — the existing on-demand payout (ADR-0051, ADR-0057; there is no payout cycle, `GLOSSARY.md` §Merchant Settlement) |
| Merchant payout | `merchant`'s payout, destination four-eyes, provider ambiguity, payout return (ADR-0051, -0056, -0057, -0073) | Unchanged — BNPL never pays a merchant itself | No second payout path |
| Merchant fee pricing | `merchant`'s `FeeSchedule`/`FeeScheduleVersion` (immutable, `INV-MER-03`), `FeeCalculation`, `RefundFeePolicy`, `PaymentFeePin` (keyed by payment intent), ADR-0058 | A BNPL fee schedule assignment per (merchant, product) whose versions are existing `fee_schedule_version` rows with `refund_fee_policy = RETURNED` (B3's pro-rated return); a **`bnpl_fee_pin`** keyed by the purchase (the intent-keyed pin cannot hold it) (§12.5) | Fees are deterministic and version-pinned; one fee arithmetic |
| Checkout | `checkout`'s session → order (ADR-0053; `INV-MER-06`); `CheckoutSessions` in `app`; the `instrument` seam of `ConfirmSessionRequest` | A new edge `OPEN → COMPLETED` "by financing" (checkout `V004`), an intent-less `CheckoutSessions.completedByFinancing(uow, sessionId, purchaseRef, entryRef, …)`, the order born with the financing entry as its `captured_entry_ref` (§12.4) | BNPL is not a payment-method flag, but checkout needs one completion signal and one order |
| Merchant API identity | ADR-0052 (merchant keys, tenant isolation, `INV-MER-01`) | Merchant-initiated refund and the purchase read over the merchant's key | No new identity mechanism |
| Wallet | `accounts.WalletAccounts`, lending's `LoanWallets` shape | Instalment source (auto-collection), refund excess destination | Phase 11's A11 stands: money reaches the platform through existing pay-ins to the wallet |
| Disputes | ADR-0061, `INV-DSP-01` | A chargeback is only possible on the **card-paid** part of a purchase; for a BNPL purchase the customer's dispute is a BNPL dispute (§12.7) | "Chargeback plus refund cannot double-credit" (gate) |
| Settlement / reconciliation | `settlement` evidence, `reconciliation` expectations (ADR-0064…0071), bank recognition the one poster of `CASH_AT_BANK` (`INV-SET-06`) | No new external evidence: the merchant payout's existing expectation; capital through existing recognition | BNPL moves no money across a rail itself |
| Idempotency, events, audit | `IdempotentExecutor`, `OutboxWriter`, audit records | Every keyed door; every event in its state change's transaction | — |

**Not reusable, and why.**
- **Lending's `loan` aggregate as the customer obligation.** Considered and refused in §3.2.
- **`payments` card capture as the BNPL purchase.** A card capture credits the merchant gross-minus-fee from money
  the network will settle; a BNPL purchase credits the merchant from the platform's capital against a customer
  receivable. Routing it through a "BNPL rail" would make an internal financing act look like an external payment
  with a clearing position (`INV-RAIL-04`) that never settles. Refused: BNPL completes the session through checkout's
  completion seam, not through a payment attempt (§12.4).
- **`transfers.TransferExecution`** — wallet to wallet only (Phase 11's finding).

### 2.2 Owner decisions (2026-10-16, binding)

| # | Decision | Where it lands |
|---|---|---|
| **B1** | **Both products**: P4 (`BNPL_PAY_IN_4` — 4 equal instalments, the first at purchase, then every 2 weeks; no customer interest; a merchant fee; late fees per the pinned terms) and PN (`BNPL_PAY_IN_N` — 3, 6 or 12 monthly instalments with interest; may carry a merchant fee) | §12.1–§12.3, ADR-0102, `P12-TSK-001`, `-004`, `-008` |
| **B2** | **Merchant paid upfront, net of fee**, on its normal settlement cycle (the existing on-demand payout); the platform carries the credit risk, **funded from `LENDING_CAPITAL`** under L6 headroom | §12.4, §12.9, ADR-0104, `INV-BNPL-02`, `-04`, `-10`, `P12-TSK-005`, `-013` |
| **B3** | **Refunds latest-instalments-first**: cancel unpaid instalments from the last backwards; any excess beyond what is unpaid goes back to the customer; the merchant fee pro-rated back | §12.6, ADR-0105, `INV-BNPL-06`, `-07`, `P12-TSK-009`, `-021` |
| **B4** | Fleet `databaseTest`/`kafkaTest` tiers stay skipped through Phase 12 — the standing criterion-7 deviation; per task: touched modules + own/adjacent DB suites; fleet hermetic + architecture once per merged wave; asked again at the Phase 12 → 13 transition | §13.1, `PHASE_GATES.md` §5 Phase 12 |
| **B5** (OQ-1) | **Approval per purchase** — each BNPL application opens one credit decision request; **no pre-approved spending limit** in Phase 12, so **no ADR-0107** | §12.1, ADR-0102, `P12-TSK-010` |
| **B6** (OQ-5) | **Instalments collected from the wallet only** — the customer's `CUSTOMER_WALLET`, funded by the existing pay-ins; no card-funded instalment and no new system-initiated card charge | §12.4, §12.2, `P12-TSK-013`, `-018`, `-019` |
| **B7** (OQ-7) | **A refund after payout may push the merchant payable negative, bounded** — by the purchase's own net (what the financing credited less what its earlier refunds took back), the debt recovered from later settlements before any payout (`INV-MER-05`); **`INV-MER-07` amended explicitly** with a third bounded source | §5.6, §12.6, ADR-0105, `INV-MER-07` (amended, effective when `P12-TSK-021` lands), `INV-BNPL-07` |
| **B8** | **Every other open question** (OQ-2, -3, -4, -6, -8 … -20) takes the draft's default, each recorded in §24 as "owner accepted default, 2026-10-16, review later" | §24 and each section it shapes |

### 2.3 Recorded assumptions (product defaults — each a field of a versioned terms row; A3 and A7 are owner decisions)

| # | Assumption | Default |
|---|---|---|
| A1 | Jurisdiction | Phase 11's neutral EUR reference product (L4); no consumer-credit-law features; legal review before activation |
| A2 | Currency | EUR only; the purchase's currency = the session's = the product's; a session in another currency is not BNPL-eligible |
| A3 | Decision per purchase (B5) | One credit decision request per BNPL application (per checkout session) — no pre-approved spending limit in Phase 12 (§12.1; **owner decision B5**) |
| A4 | P4 instalment split | `A = floor(P / 4)` to the minor unit for instalments 2–4 and instalment 1 `= P − 3A` (the remainder at purchase) — principal conserved exactly (OQ-3, owner accepted default) |
| A5 | P4 dates | Instalment 1 at purchase (business date of the purchase), instalment k due `D1 + 14·(k−1)` calendar days; no business-day adjustment (Phase 11 A6) |
| A6 | PN first due date | One month after the purchase on the purchase's day-of-month (clamped), i.e. no down payment; `ScheduleEngineV1`'s level annuity with ACT/365F (OQ-4, owner accepted default); the PN terms pin a minimum first period of 1–28 days, since a longer one would skip a month for purchases on the 29th–31st |
| A7 | Instalment source (B6) | The customer's `CUSTOMER_WALLET`; instalment 1 of P4 taken in the purchase transaction (refused `422 bnpl.InsufficientFunds` if unavailable); later instalments auto-collected on the due date (lending A21's shape: partial allowed, retried daily while due) — no card-funded instalment (**owner decision B6**) |
| A8 | Merchant fee basis | Basis points of the gross purchase (+ optional fixed part) from the merchant's pinned BNPL fee schedule version; rounded once `HALF_EVEN`; a purchase must cover its fee (ADR-0058) |
| A9 | Merchant fee recognition | Earned at purchase as `BNPL_MERCHANT_FEE_INCOME` (operational ledger); effective-interest amortisation is Phase 14's (lending A17 precedent) |
| A10 | Merchant fee on refund | Pro-rated by the cumulative-allocation method merchant already uses (`MerchantSettlement.refund`'s `cum(after) − cum(before)`), so a full refund returns exactly the fee charged (B3) |
| A11 | PN refund vs interest | A refund reduces principal latest-first (schedule v n+1, same instalment, least term — `ScheduleEngineV1.recalculateAtInstalment`); interest accrued up to the refund date stays owed, **except** a refund that cancels the whole remaining principal, which also waives accrued and billed-unpaid interest so the agreement closes with no residual obligation (gate criterion 2); interest already paid is not refunded (OQ-6, owner accepted default) |
| A12 | Refund excess | Credited to the customer's wallet (not the original payment means) |
| A13 | Late fee | Once per instalment on the pinned `late_fee_day`, capped per agreement; tests configure one, production none until activation (Phase 11 A18) |
| A14 | Default | Flag at DPD ≥ 90 (L7); no acceleration, no write-off posting (Phase 14) |
| A15 | Collections case | **Out of scope** — Phase 13; bnpl emits the condition facts (§12.8) |
| A16 | Purchase amount bounds | Credit's product bounds (one truth, the `LoanProductBounds` precedent): P4 e.g. EUR 50–1,000; PN EUR 200–5,000 (configured, not code) |
| A17 | Offer validity | `LEAST(decision.valid_until, checkout session expiry, offered_at + 30 min)` — a BNPL offer cannot outlive its checkout session |
| A18 | MFA | `MULTI_FACTOR` for the application and the acceptance (credit-creating, R14/A19); repayment and quotes the conditional step-up |
| A19 | Merchant refund authority | Bounded by the purchase's gross less prior refunds; merchant key scope required; no four-eyes (a merchant act) — no threshold review (OQ-9, owner accepted default); an operator refund door beside it |
| A20 | Disputes | A customer dispute on a BNPL purchase **pauses** collection and late fees on the disputed amount (the servicing-hold slot Phase 11 designed, A9) and resolves as a refund (merchant liable) or a reinstatement (§12.7) |
| A21 | Early payoff | Any time, no fee (L8); P4: the unpaid instalments; PN: Phase 11's payoff quote shape |
| A22 | Servicing zone | `UTC`, a terms field (Phase 11 A2) |

---

## 3. Bounded contexts and modules

### 3.1 Ownership

| Concept | Owner | Phase 12 | Relationships, lifecycle |
|---|---|---|---|
| Checkout Session, Order | `checkout` | exists | BNPL is chosen inside a session; the order is born when BNPL's purchase commits (§12.4) |
| Merchant, Fee Schedule, Merchant Payable (purpose), Payout, Payout Destination | `merchant` (payable position: `ledger`) | exists (+ BNPL fee schedule) | BNPL credits the payable; never pays out |
| Payment Intent / Attempt / Refund / Chargeback / Dispute (card) | `payments` | exists | Unchanged; touched only for a card-paid share (none in Phase 12, A7) |
| **BNPL Application** | `bnpl` | yes | One per (checkout session, customer) choice of a BNPL product; opens one credit decision request in its transaction; `SUBMITTED → AWAITING_DECISION → OFFERED / DECLINED / CLOSED_UNDECIDED / WITHDRAWN` |
| **Eligibility Assessment** | `credit` (the decision request + assessment) | exists | BNPL never computes eligibility; it reads the outcome. The merchant sees only `ELIGIBLE / NOT_ELIGIBLE / PENDING` — never a reason, score or amount (`DELIVERY_PLAN.md` §9) |
| **Credit Decision** | `credit` | exists | Read via `CreditDecisions`; consumed via `consume(BNPL_AGREEMENT, agreementId)` |
| **BNPL Terms Version** | `bnpl` | yes | Versioned per product: plan shapes, rate (PN), late fee, allocation order, DPD bounds, grace, engines; four-eyes; pinned on the agreement |
| **BNPL Offer** | `bnpl` | yes | The plan the platform will grant for this purchase: product, plan (P4, or PN 3/6/12), illustrative schedule, total payable, expiry |
| **BNPL Agreement** (versioned) | `bnpl` | yes | The immutable contract binding customer, merchant, purchase and plan; v1 at acceptance, v n+1 per refund adjustment or dispute outcome that changes the schedule |
| **Acceptance Evidence** | `bnpl` | yes | Phase 11's shape (A13 there) |
| **Purchase** | `bnpl` | yes | The financed commercial fact: session/order reference, merchant, gross amount, fee pin, capture instant. Distinct from the Order (checkout's) and from the Payment (none) |
| **Merchant Financing record** | `bnpl` | yes | Born once with the purchase: gross, fee, net credited to `MERCHANT_PAYABLE`, the entry id; its refund-adjustment children |
| **Merchant Transaction** | `merchant` (as the payable's postings) | exists | The financing entry *is* the merchant transaction on the payable; merchant's drill-down gains the `BNPL_FINANCING` and `BNPL_REFUND` kinds (their exact shape is `P12-TSK-016`'s design) |
| **Merchant Settlement** (payout) | `merchant` | exists | Unchanged; the payable is the source (`INV-MER-05`) |
| **Customer Repayment Obligation** | `bnpl` (facts) / `ledger` (balances) | yes | The obligation account: identity, product, status, permits — **no money column**; its figures are per-obligation ledger balances (`OwnerKind.BNPL_OBLIGATION`) |
| **Instalment Schedule** (versioned) + **BNPL Instalment** | `bnpl` | yes | Projected from an agreement version by a pinned schedule engine; v n+1 on a refund or payoff — billed instalments kept, unbilled superseded (Phase 11 §12.2 point 7 shape) |
| **Instalment Billing** | `bnpl` | yes | What became due at a due date (P4 instalment 1 billed and collected at purchase) |
| **Interest Accrual** (PN only) | `bnpl` | yes | Born once per (obligation, date) — lending's `ACCRUAL_ENGINE_V1` |
| **Repayment** + **Allocation** | `bnpl` | yes | Born `ALLOCATED`; `ALLOCATION_ENGINE_V1` |
| **Collection Attempt** | `bnpl` | yes | Born once per (instalment, date) |
| **Refund** (merchant-initiated) / **Partial Refund** | `bnpl` (the BNPL refund adjustment), `merchant` (the authority) | yes | One BNPL Refund per merchant refund request; its split: instalments cancelled latest-first, excess to customer, fee returned |
| **Refund Adjustment** | `bnpl` | yes | The born-once allocation of a refund across instalments (per-instalment rows) |
| **Dispute** (BNPL) | `bnpl` | yes | The customer contests the purchase with the platform (no network): `OPEN → MERCHANT_RESPONDED → RESOLVED_REFUND / RESOLVED_UPHELD / WITHDRAWN` (§12.7) |
| **Delinquency** (DPD, bucket, cure) / Default | `bnpl` | yes | Derived conditions, append-only changes (Phase 11 §12.7 shape) |
| **Late Fee** | `bnpl` | yes | Born once per (instalment, kind) |
| **Early Payoff** | `bnpl` | yes | Quote + execution |
| **Collections Case** | future collections owner | **out of scope** (Phase 13) | Consumes `BnplDelinquencyChanged` / `BnplDefaulted` |
| Write-off, provisioning | `accounting` | Phase 14 | `BNPL_WRITE_OFF_EXPENSE` seeded, unposted |

**Canonical names** (`GLOSSARY.md` §7b, `MODULE_ARCHITECTURE.md`): the financed commercial fact is the **BNPL Purchase**
(never a bare "Purchase" beside the Order and the Payment), the instalment schedule is the **BNPL Schedule** and its
rows **BNPL Instalments** (the register's "Instalment Plan" renamed so that "plan" means only the pay-in-4 / pay-in-N
choice), and the plain glossary term **Instalment** is the loan's — the "Instalment claimed by two modules" note
resolved. Table names below keep their short forms (`purchase`, `bnpl_instalment`).

**Never one row of amount and balance**: as lending — every changing amount is a ledger balance; every operational
amount an `INSERT`-only born-once row proven equal to the ledger (`BnplSubledgerProof`).

### 3.2 The module decision: `bnpl` owns its agreement and its servicing facts, reusing lending's pure engines — it does not compose lending's `loan` as a back-end

**Decision (ADR-0101, proposed).** A new module **`bnpl`**, schema `bnpl`, Flyway `bnpl/V001…`. It owns the BNPL
application, offer, agreement, purchase, merchant financing record, customer obligation, its schedule, billings,
accruals (PN), repayments, allocations, collection attempts, refunds and refund adjustments, disputes, conditions, late
fees, payoff — and none of the money. It **reuses** lending's pure engines and the capital discipline, and **does not**
create a lending `loan` for the customer obligation.

**Alternatives weighed.**

1. *BNPL as a payment-method flag* (a "BNPL rail" in payments). Refused: loses the agreement, the receivable, the
   capital, the refund policy and the credit decision; makes financing look like external settlement.
2. *BNPL as an unstructured loan subtype* (a `PERSONAL_LOAN` disbursed to the merchant). Refused by the delivery plan's
   named risk "modelling BNPL as an unsecured loan and ignoring the merchant leg".
3. *`bnpl` composes lending as the servicing back-end* — bnpl owns purchase, financing and refund policy; the customer
   obligation is a lending `loan` of a new product, born through a new lending port. Attractive: lending's accrual,
   billing, collection, DPD, late fee, payoff, reversal, proofs, exposure view and capital headroom would cover BNPL
   for free. **Refused**, for five reasons read in the Phase 11 design:
   - **Birth and disbursement.** Lending's loan is born `PENDING_DISBURSEMENT` and disbursed by rule 1 `DR
     LOAN_PRINCIPAL / CR CUSTOMER_WALLET` (`INV-LND-07`: "the receivable born exactly with the wallet credit"). A BNPL
     receivable is born with a *merchant payable* credit and a fee income line. Either lending learns to post to
     `MERCHANT_PAYABLE` and merchant fee income (merchant economics leaking into lending, a second poster of the
     payable) or two modules post halves of one economic event through a clearing account (a new transit position
     whose zero must be proven) — both weaken `INV-LND-07` and ADR-0090's "lending owns none of the merchant".
   - **The refund is not a lending act.** Lending changes a schedule only by a four-eyes amendment with the customer's
     acceptance (ADR-0099) or a customer prepayment. A merchant refund is a third party's automatic, non-cash reduction
     of the customer's debt, funded by a merchant clawback, with a pro-rated fee return — a new kind of act lending's
     machines (`INV-LND-11`) do not admit.
   - **P4 is not a lending product.** `RepaymentFrequency` is `MONTHLY` only, `ScheduleEngineV1` is a monthly annuity
     whose first due date is ≥ 15 days after the accrual start, and P4 has no accrual — its first instalment is
     collected *in the origination transaction*. Fitting it would change lending's engines, vocabularies and `CHECK`s
     after their exit gate.
   - **Disputes.** A BNPL purchase dispute suspends servicing on part of the debt pending the merchant — a servicing
     hold lending designed but never built (A9) and would have to build for a reason that is not lending's.
   - **Ownership.** `MODULE_ARCHITECTURE.md` already records that a BNPL Instalment "belongs to an Instalment Plan and
     can be reduced by a merchant refund … They share a word, not a lifecycle" (the "Instalment claimed by two
     modules" note). Composition would re-merge exactly what that note split.
4. *`bnpl` owns everything and reuses lending's engines and patterns* — **chosen.** The cost is re-applying lending's
   proven *patterns* (born-once rows, triggers, the sweep, the proofs) in bnpl's schema — real work, but each pattern is
   now documented with its races and probes, and BNPL's machines are smaller (no disbursement, no payout, no line).
   What is genuinely shared — the arithmetic — is reused, not copied.

**How the engines are shared without a cycle or a duplicate.** Lending's engines are pure and static, and lending's
package is flat (no engine sub-package to fence). Two options for `P12-TSK-002`'s design: (a) a build edge `bnpl →
lending` with an architecture test allowing only the engine types and their records (`AllocationEngineV1`,
`AllocationRules`, `DueState`, `AccrualEngineV1`, `AccrualTerms`, `AccrualPeriod`, `InterestAccrual`,
`ScheduleEngineV1`, `Schedule`, …) — it breaks the "no module depends on lending" rule `LendingModuleIsolationTest`
pins; or (b) extract those types and `Act365Fixed` into a new pure module `creditmath` (depends on `sharedkernel`
only), lending and bnpl both depending on it, `ScheduleEngineV1` gaining a `ScheduleTerms` input (the `LoanTerms` entries stay on lending's side —
`LoanTerms`, `LoanProductBounds`, `LoanKind` and `RepaymentFrequency` are lending's types, which `creditmath` cannot
see — delegating to it; where `EngineVersion` lives is `P12-TSK-002`'s design, no member's name, number or hash
changing), every `EngineVersion` identity and golden hash unchanged so every Phase 11 agreement replays
`IDENTICAL` (proven by re-running `P11-TST-002`'s battery and `LoanReplayProof`). **Decided: (b)** (OQ-2, owner accepted default under B8) — BNPL can then
never reach a lending store.

**Capital is shared, not duplicated.** There is one `LENDING_CAPITAL` per currency and one L6 row, both lending's.
`LendingCapitalHeadroom` already sums principal **by ledger purpose across every account of the currency**, so the
smallest change is: lending's headroom (and `LendingCapitalProof`) also sum `BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE` —
ledger purposes, not bnpl types, so lending learns nothing of bnpl's module — and bnpl judges headroom under L6 through
an `app` port (`BnplCapital`) over `LendingCapitalHeadroom.underLock` in bnpl's own transaction (ADR-0104). BNPL has no
committed-but-undisbursed principal (§12.4), so the formula's committed term needs no BNPL arm.

### 3.3 Module, edges and ports

- **New module `bnpl`**, schema `bnpl`. Build edges: `bnpl → creditmath, ledger, platform, sharedkernel`; `BnplModuleIsolationTest`; no module depends on `bnpl`.
- **New module `creditmath`** (pure, `sharedkernel` only): lending's engines and their records extracted unchanged (OQ-2);
  `lending → creditmath`; `CreditmathModuleIsolationTest`; every `EngineVersion` and golden hash unchanged.
- **Ports declared by `bnpl`, implemented in `app`** (the crossborder/lending precedent):
  - `BnplCreditDecisions` — `open`, `cancel`, `decisionFor`, `consume(uow, decisionId, ConsumerRef(BNPL_AGREEMENT, agreementId))`, `lockExposure(uow, party)` (L0).
  - `BnplPartyStanding` — over `CreditPartyStanding`.
  - `BnplWallets` — the customer's `CUSTOMER_WALLET` (existing only, for P4's instalment 1 at the acceptance and for collection; resolved or opened for a refund excess).
  - `BnplCheckoutSessions` — read the session (merchant, amount, currency, expiry, status, token) and, in the purchase transaction, lock it `FOR UPDATE` and **complete it by financing** (`app`'s `CheckoutSessions.completedByFinancing`, §12.4).
  - `BnplMerchants` — the merchant's standing (`ACTIVE`, BNPL-enabled; `merchant.merchant` read `FOR SHARE` as `CheckoutSessions.requireTrading` does), its `MERCHANT_PAYABLE` account, the BNPL fee schedule version to pin, the fee split through `FeeCalculation`, and the returned fee through its exact cumulative arithmetic (`FeeCalculation.returnedFee`; *corrected by `P12-TSK-006`: this read "its least-share arithmetic", which is the card refund's dispatch-time reservation - a BNPL refund's `before` is committed, so §12.5's exact share applies*).
  - `BnplCapital` — `underLock(uow, currency)` over lending's `LendingCapitalHeadroom` (L6), extended to the BNPL purposes.
  - `BnplUnitOfWork`.
- **Credit** (credit `V022`): `CreditProduct.BNPL_PAY_IN_4` and `BNPL_PAY_IN_N` — the five `product IN (…)` `CHECK`s widened in one migration (`V004`, `V008`, `V010`, `V011`, `V013`'s tables); a **term shape** per product replacing the boolean `revolving() == termMonths.isPresent()` rule (`NONE` for a line, `MONTHS` for a loan and PN with PN's allowed set {3, 6, 12}, `FIXED_PLAN` for P4 — no term sent); BNPL validities (request ≤ 1 h, decision ≤ 1 day; OQ-18, owner accepted default); affordability rule kinds for both; `ConsumerKind.BNPL_AGREEMENT` and the consumption kind `CHECK`; a policy fixture per product for the simulators; and `PlatformCreditExposure` **v3** in `app` (`P12-TSK-012`).
- **Merchant** (merchant `V010`): BNPL enablement (OQ-8: a four-eyes operator act; a per-merchant schedule), the BNPL fee schedule assignment per (merchant, product), `bnpl_fee_pin` (`-006`); `MerchantPayable`'s drill-down learning the BNPL lines (`BNPL_PRINCIPAL` as a new counterparty purpose; kinds `BNPL_FINANCING`, `BNPL_REFUND`) — **owned by `-016`**, a code change in `merchant` with a migration only if its design needs one; and the **first merchant-key refund door** (`-021`) (merchant refunds are operator-only today — OQ-9: a merchant-key door with a refund scope, an operator door beside it).
- **Checkout** (checkout `V004`): the edge `OPEN → COMPLETED` by financing (the edge trigger widened; the `payment_intent_ref` left null, a `financed_by_ref` set once), and the `"BNPL"` instrument on `ConfirmSessionRequest` routed to the BNPL application rather than to `PaymentCreation`.
- **Ledger** `V027`: `OwnerKind.BNPL_OBLIGATION`; the per-obligation purposes and the operational purposes (§12.11).
- **Lending**: `LendingCapitalHeadroom` and `LendingCapitalProof` sum the BNPL principal purposes too (a ledger-purpose read; `P12-TSK-005`); the engine extraction (`P12-TSK-002`). Nothing else.

---

## 4. Aggregates and commands

| Aggregate | Commands (actor) | Born-once facts it owns |
|---|---|---|
| `BnplTermsVersion` | propose (P), approve (P ≠ proposer), reject (P) | the version, its history |
| `BnplApplication` | submit (C, at checkout), withdraw (C), offer / decline / close (S) | application, history |
| `BnplOffer` | accept (C — this *is* the purchase authorisation), decline (C), expire (S) | offer, canonical terms + hash |
| `BnplAgreement` + `Purchase` + `MerchantFinancing` + `BnplObligation` | born at acceptance (C); service a date (S); repay (C); auto-collect (S); payoff (C); close (S) | agreement versions, evidence, purchase, financing, schedule versions, instalments, billings, accruals, repayments, allocations, attempts, conditions, fees, quotes |
| `BnplRefund` | request (M — merchant key), applied (S, same tx) | refund, refund adjustment rows, fee return |
| `BnplDispute` | open (C), respond (M), resolve (P, four-eyes above threshold), withdraw (C) | dispute, its history and outcome |
| `Waiver`, `RepaymentReversal` | propose (P), approve (P ≠ proposer), reject (P) | proposal, decision |

C = customer (keyed, MFA per A18), M = merchant (merchant API key, scope, tenant-isolated), P = operator, S = system.

---

## 5. Lifecycles

Three ranks for every machine, as Phase 11: the domain's conditional from-set under the row lock, an every-writer
edge trigger, a status `CHECK`. Machines go to a new `docs/domain/BNPL_LIFECYCLES.md`.

### 5.1 BNPL Application
```
SUBMITTED ──(credit request opened, same tx)──▶ AWAITING_DECISION ──(APPROVED)──▶ OFFERED
                                               ├──(DECLINED)──▶ DECLINED
                                               ├──(credit request CANCELLED/EXPIRED/ABANDONED, or the checkout session expired/closed)──▶ CLOSED_UNDECIDED
                                               └──(customer withdraws / abandons checkout while cancellable)──▶ WITHDRAWN
```
One open application per (party, product) (credit's partial unique) **and** per checkout session (`UNIQUE
(checkout_session_id)` over open states). A session that expires while awaiting a decision closes the application
(the credit request cancelled; an approval arriving later is never offered — it lapses unconsumed).

### 5.2 BNPL Offer
`OFFERED → ACCEPTED` (C; the decision consumed, the agreement, purchase, financing and obligation born) ·
`→ DECLINED` (C) · `→ EXPIRED` (S, `expires_at <= statement_timestamp()`, A17). Complementary conditionals at the
boundary (Phase 11 `-011`/`-012`): exactly one of `ACCEPTED`, `EXPIRED`.

### 5.2a The checkout session, as BNPL changes it

The session stays `OPEN` while the BNPL application and offer run (no new state — the card door must stay usable if
BNPL is declined). The acceptance takes the session row `FOR UPDATE`, requires `OPEN` and `expires_at >
statement_timestamp()`, and moves it **`OPEN → COMPLETED`** (new edge, by financing) in the purchase transaction. A
card or wallet confirmation that won first moved it `OPEN → PAYMENT_PENDING`, so the acceptance finds it not `OPEN` and
is refused `409 bnpl.SessionNotPayable` (nothing consumed); an acceptance that won first leaves the card confirmation
refused by checkout's existing `NOT_CONFIRMABLE`. The expiry sweeper's `OPEN → EXPIRED` and the acceptance are
complementary on the database clock. There is no `COMPLETED_LATE` for BNPL: no money lands late — financing exists only
in the commit that completes the session.

### 5.3 Purchase (the financed commercial fact)
```
(born at acceptance) AUTHORISED ──(merchant financing posted, same tx)──▶ FINANCED
FINANCED ──(refunds total the gross)──▶ FULLY_REFUNDED   (terminal)
FINANCED ──(a partial refund)──▶ FINANCED (partially refunded is a derived condition, not a state)
```
`AUTHORISED` is transient (kept for history; the platform's commitment to pay the merchant exists from acceptance,
and the financing posts in the same transaction — there is no committed-but-unfinanced purchase). **Invalid:**
anything out of `FULLY_REFUNDED`; `FINANCED → AUTHORISED`. A **cancellation before financing** is not reachable
(one transaction); a cancellation after it is a full refund (the merchant's act, §12.6) — the platform never
"un-finances" by editing.

### 5.4 Financing agreement (`BnplAgreement` — versioned, immutable rows; status on the obligation)
Versions: v1 at acceptance; v n+1 for a refund that cancels or shortens an unbilled instalment (a dispute resolved as a
refund included); a payoff bills early beside its execution and makes no version (lending's shape); a refund that cancels only billed principal is recorded in its
adjustment rows and makes no new version; an upheld dispute makes none. Never edited (`INV-HIST-04`).

### 5.5 Customer repayment obligation (`BnplObligation`)
```
(born at acceptance) ACTIVE ──(every receivable zero and every elapsed date accrued, credit balance refunded)──▶ CLOSED
```
Terminal `CLOSED` with reason `REPAID` | `PAID_OFF_EARLY` | `REFUNDED` (a full refund settled it) |
`REFUNDED_PARTIALLY_REPAID` (refunds and repayments together). No `PENDING_DISBURSEMENT` (nothing to disburse), no
`CANCELLED` (once financed, an obligation ends only by being settled — or, deferred, written off). Conditions, not
states: delinquency/DPD, default, **disputed** (servicing hold on an amount), late-fee eligibility. **Invalid:**
anything out of `CLOSED` (`INV-LIFE-04`); a repayment, collection or fee on a `CLOSED` obligation
(`409 bnpl.ObligationClosed`).

### 5.6 Merchant settlement (as BNPL sees it)
BNPL owns no settlement machine. The merchant leg's states are the existing ones: the payable credited at purchase
(`FINANCED`) → paid out by `merchant`'s payout (`merchant`'s payout machine, ADR-0057; its `UNKNOWN` held and
concluded by inquiry, ADR-0083) → reported and final by Phase 8's two evidence hops (ADR-0065). A refund after a
payout drives the payable down; recovery from later captures before any payout (`INV-MER-05`); below zero only by
fee and chargeback per `INV-MER-07`/ADR-0061 — **BNPL adds a third bounded source** (owner decision B7): a BNPL refund after a payout may drive the payable below
zero, by at most what that purchase's own financing credited it less what its earlier refunds took back (its net,
§12.6), the debt recovered from later captures and financings before any payout (`INV-MER-05`). `INV-MER-07` is
amended explicitly (effective when `P12-TSK-021` lands) and `INV-BNPL-07` holds the per-purchase bound.

### 5.7 Refund (`BnplRefund`)
`(born) APPLIED` — a refund row exists only once its effects posted (lending's repayment precedent): one
transaction claims the merchant's key, locks, judges the bound, allocates latest-first, posts, and writes the
adjustment rows. Refusals write nothing: `422 bnpl.RefundExceedsPurchase`, `409 bnpl.PurchaseFullyRefunded`,
`403 merchant.*` (not its purchase — `404`). No `PENDING` state: there is no provider.

### 5.8 Dispute (`BnplDispute`)
```
OPEN ──(merchant responds with evidence or accepts)──▶ MERCHANT_RESPONDED ──(operator resolves)──▶ RESOLVED_REFUND | RESOLVED_UPHELD
OPEN ──(merchant accepts liability)──▶ RESOLVED_REFUND        OPEN|MERCHANT_RESPONDED ──(customer withdraws)──▶ WITHDRAWN
OPEN ──(response deadline passes)──▶ RESOLVED_REFUND (S; the merchant's silence is acceptance — OQ-10, 14 days, owner accepted default)
```
While `OPEN`/`MERCHANT_RESPONDED` the disputed amount is held: no late fee, no DPD on instalments covered by the
hold, no auto-collection beyond the undisputed part. `RESOLVED_REFUND` executes a `BnplRefund` (the merchant's
liability); `RESOLVED_UPHELD` lifts the hold and re-bills nothing (the instalments were never cancelled). One open
dispute per purchase.

### 5.9 Other machines
Terms version `PROPOSED → ACTIVE → RETIRED | REJECTED`; waiver, reversal `PROPOSED → APPROVED | REJECTED`; payoff
quote immutable, its execution born once (`UNIQUE (quote_id)`); repayment born `ALLOCATED`, `REVERSED` derived.

---

## 6. Financial invariants

**New (proposed) `INV-BNPL-01`…`10`** — the catalogue 142 → 152:

| ID | Statement (short) | Enforce (planned) |
|---|---|---|
| `INV-BNPL-01` | An obligation's figures are ledger balances; no mutable money column in `bnpl` | `BnplSchemaHasNoMutableMoneyTest`; subledger proof |
| `INV-BNPL-02` | **Financing and obligation are born atomically**: the merchant payable credit, the fee income, the customer receivable, the decision's consumption and the capital commitment commit in one transaction, or none does | one transaction (T-pur), deferred trigger: a `merchant_financing` row exists iff its obligation, purchase and entry exist |
| `INV-BNPL-03` | One decision funds at most one agreement; one checkout session at most one purchase; one purchase at most one financing entry | `bnpl_offer UNIQUE (decision_id)` and credit's consumption uniques (`UNIQUE (decision_id)`, `UNIQUE (consumer_kind, consumer_ref)`), `purchase UNIQUE (checkout_session_id)`, `merchant_financing UNIQUE (purchase_id)`, ledger key `bnpl.financing:<purchase>` |
| `INV-BNPL-04` | The financing conserves the purchase: receivable principal = gross = merchant net + merchant fee, exactly | the entry's lines `CHECK`ed by trigger; `MerchantFeeSplit` property-tested |
| `INV-BNPL-05` | A schedule conserves principal: Σ instalment principal = purchase principal (P4 exactly `P`, A4; PN `ScheduleEngineV1`'s `INV-LND-03`) | deferred conservation trigger; property tests |
| `INV-BNPL-06` | **A refund preserves value**: refund R = Σ instalment principal cancelled (latest first) + excess credited to the customer; and the merchant payable is debited R − fee returned; nothing else moves but the releases the refund's own entry names (F5a: late fees on cancelled instalments, PN interest at a full cancellation) | per-instalment adjustment rows summed by a deferred trigger; the entry's lines; the battery |
| `INV-BNPL-07` | Refunds never exceed the purchase: Σ refunds ≤ gross, judged under the purchase lock; a refund after a full refund is refused | domain + trigger under the purchase row lock |
| `INV-BNPL-08` | **Refund and dispute never double-credit**: a dispute resolved as a refund *is* a refund under the same bound; at most `gross` is ever returned to the customer side across refunds and dispute outcomes | one bound (`INV-BNPL-07`) for both paths, the purchase lock |
| `INV-BNPL-09` | A full refund closes the obligation with every receivable zero and no stranded value (credit balance refunded, fee fully returned, accrued interest waived per A11) | closure in the refund's transaction when the excess reaches the wallet; when the wallet cannot be credited the excess is held as `BNPL_CREDIT_BALANCE` and the obligation closes in the transaction that refunds it (OQ-13 — `P12-TSK-021`'s design settles the edge); proof arm |
| `INV-BNPL-10` | BNPL principal is counted in capital headroom and in credit exposure from the purchase's commit, with no gap | L0 then L6 in T-pur; exposure v3 one statement; census |

**Restated for Phase 12:** `INV-MON-*`, `INV-LED-*`, `INV-BAL-*`, `INV-HIST-01/-04`, `INV-IDEM-*`, `INV-CON-01/-02`,
`INV-LIFE-*`, `INV-REV-*`, `INV-EVT-*`, `INV-AUD-*`, `INV-ACC-01`; `INV-CRD-02`, `-04`, `-09`; `INV-LND-02` (PN accrual),
`-04` (allocation), `-09`, `-10`, `-11`, `-12` (applied to obligations by analogy; catalogued separately as `INV-BNPL-*` — OQ-11, owner accepted default), `-13`
(capital, extended); `INV-MER-01`, `-02`, `-03`, `-05`, `-07` (amended); `INV-DSP-01` (where a card share exists).

---

## 7. Multi-instance architecture

Every instance runs every door, sweep and consumer; correctness rests on PostgreSQL; every window judged on
`statement_timestamp()` after the relevant row lock; no connection across a provider call (ADR-0046). BNPL itself
calls **no provider**: its only external ambiguities are inherited (merchant payout; wallet top-ups). Every principal
fact is value-dated no earlier than the obligation's own floor, `bnpl.principal_value_floor(obligation)` (created by
`P12-TSK-013`) — the later of the day after its latest accrual (PN) and the date of its latest billing itself (so a
same-day refund of a P4 purchase is dated the purchase's date), never before the purchase's business date — read under B4 (the Phase 11 gate's R1, applied from the first table; `P12-TSK-013`/`-015` confirm the arms).

### 7.1 Transactions (each one commit)

| Tx | Steps |
|---|---|
| **T-app** application | claim `bnpl.application:CUSTOMER:<id>` (`MULTI_FACTOR`); standing; the session read (open, unexpired, merchant BNPL-enabled, EUR, amount in bounds); active terms `FOR SHARE`; `DecisionRequests.submit(product, amount = session amount)` through the BNPL door only; application `AWAITING_DECISION` (`UNIQUE (checkout_session_id)` over open, `UNIQUE (decision_request_id)`); events |
| **T-off** offer | origination sweep claim; (B1) application `FOR UPDATE` conditional `AWAITING_DECISION`; decision read; session still open; terms `ACTIVE` `FOR SHARE`, pinned; plan(s) and illustrative schedule from the engines; offer born (`UNIQUE (application_id)`); else `CLOSED_UNDECIDED (reason)`; events |
| **T-pur** acceptance = purchase authorisation | claim `bnpl.offer-acceptance:CUSTOMER:<id>` (`MULTI_FACTOR`, echoed `terms_sha256`); **(L0)** profile; **(B2)** offer `FOR UPDATE` `OFFERED`, unexpired; **(B3)** the checkout session row `FOR UPDATE` through checkout's port — **judged at once**: not `OPEN` or expired → refused `409 bnpl.SessionNotPayable` with nothing else taken; standing; `consume`; **(L6)** capital row + headroom ≥ principal; agreement v1, evidence, purchase, obligation (`ACTIVE`), schedule v1, the per-obligation accounts opened; **(P)** the merchant's `MERCHANT_PAYABLE` and — for P4 — the customer's `CUSTOMER_WALLET` ledger-account rows `FOR UPDATE` **as one pair in `UUID.compareTo` order** (§7.2), the wallet's available ≥ instalment 1 (P4); **(L8)** `lockBalancesInOrder`; posting F1 (financing) and, for P4, billing of instalment 1 + repayment R1 (collected at purchase); the session completed by financing → checkout's order; events; audit |
| **T-acc** accrual (PN) | sweep claim, or run first by any act that needs the record caught up; **(B4)** obligation `FOR UPDATE`; DB clock; accrue every elapsed date (F2a) under `bnpl.accrual:<obligation>:<date>`; commit. **Its own transaction, so the currency's `BNPL_INTEREST_INCOME` row is never held across a wallet wait** (the Phase 11 gate's A2-F1 lesson) |
| **T-svc** servicing step (one obligation, one business date) | sweep claim; **(B4)** obligation `FOR UPDATE`; DB clock; **every elapsed date already accrued, else nothing is written and T-acc runs first**; bill due instalments (F2); the catch-up credit-balance application over past-due items (F3a, the gate's R2); auto-collect (the wallet row (P), F3); closure; condition; late fee (F4) |
| **T-rep** repayment | claim `bnpl.repayment:CUSTOMER:<id>`; (B4); accrued to date (else T-acc first); due state derived; allocate; the wallet row (P); (L8); post; maybe close |
| **T-ref** merchant refund | claim `bnpl.refund:MERCHANT:<id>` (merchant key); **(B5)** the purchase `FOR UPDATE` (the refund bound's arbiter; disputes take it too); **(B4)** obligation; DB clock; PN accrued to date (else T-acc first); the refund's split (latest-first) over the due state derived under B4; schedule v n+1 + agreement v n+1 when instalments are cancelled; **(P)** merchant payable and — when an excess is credited — the customer wallet, one pair in `UUID.compareTo` order; (L8); posting F5/F5a; refund + adjustment rows; closure if settled; events; audit |
| **T-dsp** dispute acts | claim; (B5) purchase; (B4) obligation; (B6) the dispute row; condition hold written; on `RESOLVED_REFUND` → T-ref's body in the same transaction |
| **T-pay** payoff | claim; accrued to date (else T-acc first); (B4); catch-up application; quote recomputed (`409 bnpl.PayoffQuoteStale`); (P) wallet; (L8); repayment; closure |
| **T-rev / T-wvr** corrections | claim `bnpl.servicing:EMPLOYEE:<id>`; (L0) for a repayment reversal (outstanding rises); (B4); (B6) proposal; (L6) if principal re-instated; (P) wallet; (L8) post; audit |
| **T-terms** | claim; advisory namespace `12` (`hashtext(product)`); the proposal |

### 7.2 The BNPL lock order (to be registered in `DISTRIBUTED_EXECUTION.md` §3 as "The Phase 12 lock order")

One order for every bnpl writer holding more than one of these (the lock positions (B1)…(B6) are named for `bnpl`'s rows
and are **not** the owner decisions B1–B8 of §2.2; the documents write lock positions in parentheses):

**(L0)** the party's `credit_profile` row `FOR UPDATE` — first, and only where exposure rises (acceptance, repayment
reversal); **(B1)** `bnpl_application`; **(B2)** `bnpl_offer`; **(B3)** the checkout session row (through checkout's
port — only in T-pur, and judged at once: a session not `OPEN` is refused before any further lock); **(B5)** the
`purchase` row (refund and dispute bound); **(B4)** the `bnpl_obligation` row — the serialisation point of every
servicing act on one obligation; **(B6)** the act's own row (dispute, proposal, quote); **(L6)** `LENDING_CAPITAL` of the
currency; **(P) = (M1 ∪ L7)** the merchant's `MERCHANT_PAYABLE` and the customer's `CUSTOMER_WALLET` `ledger_account`
rows `FOR UPDATE`, **taken together as one element in `UUID.compareTo` order of the two account ids** — the order
payments' book rail already takes for exactly this pair (`PaymentOutcomes.lockPairInFixedOrder`); a transaction that
needs only one of them takes it at this position; **(L8)** projections via `lockBalancesInOrder`, postings last — the
currency's shared `BNPL_MERCHANT_FEE_INCOME`, `BNPL_FEE_INCOME` and `BNPL_INTEREST_INCOME` rows inside that one sorted
call. **No shared income row is ever taken before (P)**: the accrual commits alone (T-acc, which takes no (P)), and every
other writer that posts to an income row (a refund releasing interest or fees, a waiver, the late fee) does so at L8,
after its wallet and payable — so no bnpl transaction holds a currency-wide income row while it waits on a wallet or a
payable (the Phase 11 gate's A2-F1, designed out rather than inherited). Then only inserts.

B5 before B4: a refund and a dispute lock the purchase first, then its obligation; servicing takes only B4. No writer
holding B4 waits on B5.

### 7.3 Why there is no cycle (to be proven by the counted races)

(a) **Credit** never waits on a bnpl lock (it reads bnpl's view in one plain statement); L0 is first wherever taken.

(b) **The merchant payable and the wallet — the draft's open confirmation, settled by reading the code.** The draft proposed
"M1 before L7" and asked whether a wallet-paid checkout capture ever takes the payable's account row before a wallet
row. **It does**: the book rail's `dispatchBook` (inside `PaymentConfirmation`'s transaction) and `PaymentRefund`'s book
arm lock the **pair** `{wallet, payable}` with `lockPairInFixedOrder` — by `UUID.compareTo` of the two account ids, so
for half of all (customer, merchant) pairs the payable is locked first and for the other half the wallet. A fixed "M1
then L7" in BNPL would close a cycle with a wallet-paid capture by the same customer at the same merchant whenever the
wallet's id sorts first (BNPL holds the payable, waits on the wallet; the capture holds the wallet, waits on the
payable — `40P01`). **Decision: BNPL takes the two as one element, (P), in the same `UUID.compareTo` order** — a total
order shared with payments over exactly the rows both touch. Merchant's payout dispatch locks the payable alone,
places its hold and commits before the wire (`MerchantPayouts`), taking no wallet row and no L6; merchant-bound card
captures and refunds take the payable (and the card rail's clearing, not a wallet) — none of them holds a payable while
waiting on a BNPL row. Lending never takes the payable; lending's L6 → L7 and BNPL's L6 → (P) agree. `P12-TSK-013`
counts the race (a BNPL acceptance against a wallet-paid capture for the same customer and merchant, both id orders).

(c) **The checkout session (B3).** Checkout's capture completion (`CheckoutSessions.completed`, called by
`MerchantBoundCaptureComposition` after the capture's postings) takes the session row **after** the payable's rows —
the reverse of T-pur's B3 → (P). It is still acyclic: a capture completes only a session already `PAYMENT_PENDING`
(the card or wallet confirmation moved it in an earlier transaction), and T-pur, on finding a session not `OPEN`
under B3, refuses at once without taking (P); while a session is `OPEN` no capture can be completing it. The expiry
sweeper locks the session alone. No bnpl writer holding a ledger row ever waits on a session.

(d) Payments' writers never touch bnpl. (e) No bnpl transaction is open across a provider call — BNPL calls none.
(f) `LENDING_CAPITAL` (L6) is taken by lending's acceptance, draw and reversal and by BNPL's acceptance and reversal,
each before any wallet or payable row — one order across both modules.

### 7.4 Contention table (the owner's list, each with its arbiter)

| Contention | Arbiter | Loser's answer | Proven by |
|---|---|---|---|
| Duplicate checkout financing request (same key) | `IdempotentExecutor` claim | the stored answer | `-010` |
| Two applications for one session (different keys / instances) | `UNIQUE (checkout_session_id)` over open + credit's one-open-request partial unique | `409 bnpl.ApplicationOpen` | `-010` |
| Concurrent eligibility/decision for one party (BNPL + a loan) | L0 in credit's deciding tx and in every exposure-raising acceptance | serialised; the decision sees reservation or commitment, never neither | `-012` |
| Ten origination sweepers | `SKIP LOCKED` + conditional `AWAITING_DECISION → OFFERED` + `UNIQUE (application_id)` | nothing written | `-011` |
| **Duplicate purchase authorisation** (two acceptances, one key or several; acceptance vs card payment of the same session) | claim; offer conditional `OFFERED`; B3 session conditional "open, unpaid"; `UNIQUE (checkout_session_id)` on purchase; consumption's uniques | one purchase, one order; `409 bnpl.OfferNotAcceptable` / `409 bnpl.SessionNotPayable` (a losing card or wallet confirmation: checkout's existing `NOT_CONFIRMABLE`) | `-013` |
| Acceptance vs offer expiry vs session expiry at the boundary | complementary conditionals on the DB clock under B2/B3 | exactly one of `ACCEPTED`, `EXPIRED` | `-013` |
| Acceptances racing for the last capital (BNPL vs loan vs draw) | L6 + shared headroom | one admitted; `422 bnpl.CapitalUnavailable` | `-013`, `-005` |
| **Concurrent merchant settlement** (a payout racing a BNPL financing credit or a BNPL refund on one payable) | the payable row `FOR UPDATE` (existing; within (P)) | serialised; payout bounded by the payable (`INV-MER-05`); a refund after payout bounded by `INV-BNPL-07` | `-016` |
| **A BNPL acceptance or refund racing a wallet-paid capture or refund** for the same customer at the same merchant | (P): the wallet and payable rows taken as one pair in `UUID.compareTo` order — payments' book-rail order (§7.3 (b)) | serialised, never `40P01`, in both id orders | `-013`, `-021` |
| **Duplicate instalment collection** (two sweepers; sweep vs customer repayment) | B4; `collection_attempt UNIQUE (instalment_id, attempt_date)`; repayment key | one collection | `-019` |
| **Allocation races** (two repayments on one instalment; repayment vs refund) | B4; due state re-derived under it; refund takes B5 then B4 | conserved; never over-allocated; refund cancels only what is unpaid at its turn | `-018`, `-021` |
| **Duplicate refund** (merchant retries; two instances) | claim (same key); B5 + `UNIQUE (merchant_id, refund_key)` (different instances, lapsed claim) | one refund | `-021` |
| **Duplicate reversal** | one live proposal per repayment (partial unique); proposal conditional | one reversal | `-024` |
| Refund racing a dispute resolution / chargeback | B5 purchase lock; one bound for both | never double-credited (`INV-BNPL-08`) | `-023` |
| Refund racing instalment collection (delivery plan §12) | B5 → B4 vs B4 (collection) | either order conserves: collected-then-refunded → excess to customer; refunded-then-nothing due → nothing collected | `-021` |
| Payoff with a refund in flight (delivery plan §11) | B4; quote recomputed | `409 bnpl.PayoffQuoteStale` or the refund waits and sees `CLOSED` → excess to customer only | `-022` |
| **Provider timeout / ambiguous outcome** (merchant payout of BNPL-financed payable) | merchant/payments' `UNKNOWN` held, inquiry-only (ADR-0083); payout key replay | one payout; BNPL agreement unaffected | scenario 5: `-016` (the payout), `P12-TST-001` (the wallet top-up variant) |
| **Delayed / duplicated events** (`CreditDecisionRecorded`, checkout events) | events are hints; the sweep reads authoritative rows | converges within one sweep interval | `-011` |
| **Concurrent delinquency** (two condition evaluations; payoff during the worker) | B4 + `UNIQUE (obligation_id, business_date, kind)`; closed skipped | one condition change; none after closure | `-020` |
| PN accrual twice / ten sweepers / restart | `UNIQUE (obligation_id, accrual_date)` + ledger key + B4 | one accrual | `-017` |
| **Partial failures / restarts** (crash after posting, before event; crash between T-pur's steps) | one transaction per step; outbox in the same tx | both or neither; relay re-publishes | `-013`, `P12-TST-001` |

### 7.5 Schedules (leaderless; `SmartLifecycle`; off in test contexts; `…sweeper.enabled` gauges)

| Schedule | Claims | Permit |
|---|---|---|
| `BnplOriginationSchedule` | applications `AWAITING_DECISION`; offers past `expires_at`; applications whose session closed | `next_step_at` |
| `BnplServicingSchedule` | obligations `ACTIVE` with `next_servicing_at <= now()` | next business date or retry cadence |
| `BnplDisputeSchedule` | disputes past the merchant's response deadline (the window and the four-eyes threshold are fields of the pinned terms version) | the deadline — a database-stamped permit that holds no row, so each step then takes (B5) → (B4) → (B6) in order (ADR-0106) |

`NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` 26 → 29, each with its `DISTRIBUTED_EXECUTION.md` §3
row. Advisory namespace **`12`** (the next free) reserved for bnpl's administrations (terms versions, `hashtext(product)`).

---

## 8. Data architecture

Schema `bnpl`, `REVOKE ALL FROM PUBLIC`; `SELECT, INSERT` on facts; conditional `UPDATE` only of status/permit
columns on machine tables; no `DELETE`; every money column `*_minor bigint` + `currency`, on `INSERT`-only tables.

| Table | Task | Key arbiters |
|---|---|---|
| `bnpl_terms_version` (+ `_event`), `bnpl_terms` | `-004` | one `PROPOSED`, one `ACTIVE` per product; term set INSERT-only |
| `bnpl_application` (+ `_event`) | `-010` | `UNIQUE (decision_request_id)`; partial `UNIQUE (checkout_session_id)` over open |
| `bnpl_offer` (+ `_event`), `bnpl_offer_terms` | `-011` | `UNIQUE (application_id)`, `UNIQUE (decision_id)`; `terms_sha256` |
| `bnpl_agreement`, `bnpl_acceptance` | `-013` | `UNIQUE (obligation_id, version)`; INSERT-only, canonical terms text |
| `purchase` (+ `_event`) | `-013` | `UNIQUE (checkout_session_id)`, `UNIQUE (offer_id)`; merchant id, fee schedule pin |
| `merchant_financing` | `-013` | `UNIQUE (purchase_id)`, `UNIQUE (journal_entry_id)`; gross/fee/net with `gross = fee + net` `CHECK` |
| `bnpl_obligation` (+ `_event`) | `-013` | `UNIQUE (purchase_id)`; no money column; `next_servicing_at` |
| `bnpl_schedule`, `bnpl_instalment` | `-013` | `UNIQUE (obligation_id, schedule_version)`, `UNIQUE (schedule_id, sequence)`; deferred conservation |
| `bnpl_interest_accrual` (PN) | `-017` | `UNIQUE (obligation_id, accrual_date)` |
| `bnpl_billing` (born by `-013` for P4's instalment 1, extended by `-015`), `bnpl_credit_balance_application` | `-013`, `-015` | `UNIQUE (instalment_id)` |
| `bnpl_repayment`, `bnpl_repayment_allocation` (born by `-013` with the kind `AT_PURCHASE` for P4's instalment 1, extended by `-018`); `bnpl.principal_value_floor(obligation)` and its ranks (`-013`, extended by every later mover) | `-013`, `-018` | `UNIQUE (obligation_id, kind, request_key)`; Σ allocation = amount (deferred) |
| `bnpl_collection_attempt` | `-019` | `UNIQUE (instalment_id, attempt_date)` |
| `bnpl_refund`, `bnpl_refund_adjustment` | `-021` | `UNIQUE (merchant_id, refund_key)`; Σ adjustment + excess = amount (deferred); per-instalment rows |
| `bnpl_condition_event` | `-020` | `UNIQUE (obligation_id, business_date, kind)` |
| `bnpl_fee_assessment`, `bnpl_waiver` (+ decision) | `-020`, `-024` | `UNIQUE (instalment_id, kind)`; four-eyes `CHECK` |
| `bnpl_payoff_quote`, `bnpl_payoff_execution` | `-022` | `UNIQUE (quote_id)`, `UNIQUE (obligation_id)` |
| `bnpl_dispute` (+ `_event`) | `-023` | partial `UNIQUE (purchase_id)` over open |
| `bnpl_repayment_reversal` (+ allocation reversal) | `-024` | one live proposal per repayment |

**Other modules' migrations (expected order; the building task may renumber):** credit `V022` (`-001`: products,
consumer kind); ledger `V027` (`-003`); identity `V022` (`-002`: BNPL permissions/roles); merchant `V010` (`-006`: BNPL fee
schedule, BNPL enablement); checkout `V004` (`-007`: BNPL completion path); lending — no migration (`LendingCapitalHeadroom` and
`LendingCapitalProof` stay lending's and sum the BNPL purposes by ledger purpose; `app`'s `BnplCapital` port adapts
them, `-005`).

**Provider references, audit records, reconciliation evidence.** BNPL stores no provider reference (it calls none).
It stores: the checkout session and order ids; the merchant id and fee schedule version; the journal entry id on every
money fact; the consumed decision id. Reconciliation evidence for the merchant leg is merchant/payments/settlement's
existing payout expectation; for capital, reconciliation's recognition.

**Retention:** agreements, evidence and servicing rows for the obligation's life plus the limitation period; the purge
is Phase 15's (beside lending's debt row).

---

## 9. API architecture

All `/v1`, closed bodies, the error contract, `Idempotency-Key` on every keyed command, owner-scoped (another party's
or merchant's id → `404`).

| Route | Who | Command | Idempotency | Answer |
|---|---|---|---|---|
| `GET /v1/checkout/sessions/{id}/bnpl-options` (`-011`) | customer (session holder) | plans offered for this session's amount (no eligibility — products and illustrative plans only) | read | — |
| `POST /v1/me/bnpl-applications` | customer, `MULTI_FACTOR` | the session **token** (a capability, not a bare id — the `CheckoutSessions.byToken` precedent), product, plan | `bnpl.application:CUSTOMER:<id>` | `202` |
| `GET /v1/me/bnpl-applications/{id}` | customer | status; decline texts via credit | read | — |
| `POST /v1/me/bnpl-applications/{id}/withdrawal` | customer | withdraw | `bnpl.application-withdrawal:…` | sync |
| `GET /v1/me/bnpl-offers/{id}` | customer | plan, schedule, totals, expiry, document | read | — |
| `POST /v1/me/bnpl-offers/{id}/acceptance` | customer, `MULTI_FACTOR` | accept (echo `terms_sha256`) — **the purchase** | `bnpl.offer-acceptance:CUSTOMER:<id>` | `201` (agreement id; the session paid) |
| `POST /v1/me/bnpl-offers/{id}/decline` | customer | decline | key | sync |
| `GET /v1/me/bnpl-agreements` · `/{id}` · `/{id}/schedule` · `/{id}/repayments` | customer | state, conditions, balances (derived), schedule, refunds | read | — |
| `POST /v1/me/bnpl-agreements/{id}/repayments` | customer, step-up | repay from wallet | `bnpl.repayment:CUSTOMER:<id>` | sync |
| `POST /v1/me/bnpl-agreements/{id}/payoff-quotes` · `/payoff` | customer (payoff `MULTI_FACTOR`) | quote; execute | keys | sync |
| `POST /v1/me/bnpl-agreements/{id}/disputes` | customer | open a dispute (reason, amount ≤ purchase) | `bnpl.dispute:CUSTOMER:<id>` | `201` |
| `GET /v1/merchant/bnpl-purchases/{id}` | merchant key | purchase, financed net, fee, refunds — **never** customer credit data, schedule or delinquency | read | — |
| `POST /v1/merchant/bnpl-purchases/{id}/refunds` | merchant key, refund scope | amount (≤ gross − refunded), reason | `bnpl.refund:MERCHANT:<id>` | sync |
| `POST /v1/merchant/bnpl-disputes/{id}/response` | merchant key | evidence / accept | `bnpl.dispute-response:MERCHANT:<id>` | sync |
| `POST /v1/operator/bnpl-disputes/{id}/resolution` | `BNPL_DISPUTE_RESOLVE` (four-eyes above threshold) | refund / uphold | `bnpl.servicing:EMPLOYEE:<id>` | sync |
| `POST /v1/operator/bnpl-agreements/{id}/waivers` · `…/repayments/{rid}/reversal` (+ approval, rejection) | `BNPL_SERVICE` / `BNPL_SERVICE_APPROVE` | waive; reverse | `bnpl.servicing:EMPLOYEE:<id>` | sync |
| `POST /v1/operator/bnpl/terms` (+ approval, rejection) | `BNPL_ADMINISTER` | terms versions | `bnpl.terms:EMPLOYEE:<id>` | sync |
| `GET /v1/operator/bnpl-agreements/{id}` (+ `/explanation`), `POST …/replay`, `GET /v1/operator/reports/bnpl/{portfolio,merchant-financing,refunds,delinquency}` | `BNPL_INVESTIGATE` | read, explain, replay, reports (audited) | — | — |

**Errors** (catalogued by the building task): `409 bnpl.ApplicationOpen`, `ApplicationNotWithdrawable`,
`OfferNotAcceptable`, `SessionNotPayable`, `TermsChanged`, `ObligationClosed`, `PayoffQuoteStale`, `PurchaseFullyRefunded`,
`DisputeOpen`, `ProposalPending`; `422 bnpl.ProductNotOffered`, `MerchantNotEnabled`, `SessionNotEligible`,
`AmountOutOfRange`, `InsufficientFunds`, `CapitalUnavailable`, `RefundExceedsPurchase`, `ReasonRequired`,
`WaiverExceedsDue`; `403 bnpl.SelfApprovalRefused`, `SelfDealingRefused`; `404 bnpl.NotFound`; credit's and checkout's
refusals passed through.

---

## 10. Event architecture

Outbox, the platform envelope (event id, type, aggregate id, event version, schema version, timestamp, producer
`bnpl`, correlation, causation), event version 1, additive evolution; every event in its state change's transaction.

| Event | Aggregate | When | Carries (never a credit figure to merchant consumers) |
|---|---|---|---|
| `BnplApplicationSubmitted` / `BnplApplicationClosed` | application | T-app / close | ids, product, session id; status |
| `BnplEligibilityAssessed` | application | offer or decline | session id, outcome `ELIGIBLE`/`NOT_ELIGIBLE` only |
| `BnplOffered` / `BnplOfferClosed` | offer | T-off / expiry, decline | plan, expiry |
| `BnplAgreementCreated` | agreement | T-pur | agreement, purchase, session, order ids, product, plan, hash |
| `MerchantFinanced` | purchase | T-pur | merchant id, purchase id, gross, fee, net, entry id |
| `InstalmentDue` / `InstalmentPaid` | obligation | billing / allocation settles it | instalment, due date, amounts |
| `BnplCollectionFailed` | obligation | auto-collection got nothing | instalment |
| `BnplRefundApplied` | purchase | T-ref | refund id, amount, instalments cancelled, excess, fee returned |
| `BnplDisputeOpened` / `BnplDisputeResolved` | dispute | T-dsp | outcome |
| `BnplLateFeeAssessed` / `BnplFeeWaived` / `BnplRepaymentReversed` | obligation | corrections | ids, amounts |
| `BnplDelinquencyChanged` / `BnplDefaulted` / `BnplDefaultCleared` | obligation | condition | bucket, DPD |
| `BnplAgreementClosed` | obligation | closure | reason |
| `BnplTermsVersionActivated` | terms | activation | version, product |

BNPL consumes no event for correctness; `credit.CreditDecisionRecorded` and checkout's session events are hints (an
`InboxEventHandler` pulling a permit).

---

## 11. Security, audit and privacy

- **Customer authorisation:** keyed, owner-scoped; `MULTI_FACTOR` for the application and the acceptance (credit is
  created and an obligation born); repayment and quote the conditional step-up; standing checked in-transaction at
  application and acceptance (not at repayment).
- **Merchant authorisation and tenant isolation:** merchant API key (ADR-0052) with a BNPL refund scope; every merchant
  read and refund owner-scoped to the purchase's merchant (`INV-MER-01`); refunds bounded by the original purchase
  (`INV-BNPL-07`); the merchant never sees eligibility reasons, scores, credit amounts, the customer's schedule,
  repayments or delinquency (`DELIVERY_PLAN.md` §9) — only `ELIGIBLE/NOT_ELIGIBLE/PENDING`, the purchase, its financed
  net, fee and refunds.
- **Consent:** credit's consent gate for bureau/financial-data sources applies unchanged (`INV-CRD-03`); acceptance is
  not consent (`INV-IDN-04`) — no consent purpose created. Whether the merchant may learn that the customer *applied*
  for BNPL is a privacy decision (OQ-12, owner accepted default: only `NOT_ELIGIBLE` for that session, never a reason).
- **Operator roles (identity `V022`):** `BNPL_ADMINISTER`, `BNPL_INVESTIGATE` → `BNPL_OFFICER`;
  `BNPL_SERVICE`, `BNPL_SERVICE_APPROVE`, `BNPL_DISPUTE_RESOLVE` → `BNPL_SERVICING_AGENT`; no role approves its own
  act; self-dealing refused (`bnpl.SelfDealingRefused`, audited `FAILED`). **No operator creates a purchase, finances a
  merchant, cancels instalments outside a refund or dispute resolution, or edits a figure.**
- **Audit acts:** `bnpl.ApplicationSubmitted`, `bnpl.ApplicationWithdrawn`, `bnpl.OfferDeclined`, `bnpl.OfferAccepted` (= purchase), `bnpl.MerchantFinanced` (system), `bnpl.RefundApplied` (merchant
  actor, reason), `bnpl.DisputeOpened` / `…Responded` / `…Resolved`, `bnpl.WaiverProposed/Approved/Rejected`,
  `bnpl.RepaymentReversalProposed/Approved/Rejected`, `bnpl.TermsVersionProposed/Activated/Rejected`,
  `bnpl.AgreementRead`, `bnpl.ExplanationRead`, `bnpl.Replayed`, `bnpl.ReportRead`; credit's `credit.DecisionConsumed`.
- **Data classification:** amounts on agreements, schedules, refunds — `RESTRICTED-FINANCIAL`; party, merchant and
  agreement references `CONFIDENTIAL`; acceptance session/IP `CONFIDENTIAL`; person-written reasons `RESTRICTED-PII`
  (the Phase 11 review's finding 19 resolved forward).
- **Logging:** no amount, party, merchant, agreement or DPD in a log line, tag, span attribute or exception message
  (`INV-AUD-02`, ADR-0072).
- **Provider boundaries:** none new; merchant payouts stay behind payments' adapters (`INV-PAY-03`).
- **Jurisdiction (recorded, not invented):** neutral EUR reference; no statutory BNPL disclosures, affordability
  rules, cooling-off/withdrawal rights, late-fee caps, or regulated-credit classification of interest-free products —
  each a terms field and a legal review before activation (A1).

---

## 12. The BNPL model

### 12.1 Products, eligibility and the offer

Credit owns eligibility. Each BNPL application opens a `DecisionRequest(product = BNPL_PAY_IN_4 | BNPL_PAY_IN_N,
amount = the session's gross, term = plan)`; the policy for each product is credit's versioned data (affordability:
P4 — the largest instalment × stress factor; PN — the annuity at the policy's stress rate — **credit's design at
`P12-TSK-001`**). The offer is exactly the approved amount (A20 analogue) — a reduced approval below the session amount
is **not** offerable (the purchase price is fixed): the application closes `CLOSED_UNDECIDED (APPROVED_BELOW_PURCHASE)`.
**Latency (B5, owner decision):** one decision per purchase — credit decides asynchronously through a sweep and simulated
providers; the checkout waits on `202` and polls. A pre-approved spending limit (a decision for a limit, each purchase
drawn against it — ADR-0100's exposure shape) was considered and refused for Phase 12; no ADR-0107.

### 12.2 Schedules — reuse vs a BNPL engine

- **P4 — `BNPL_SCHEDULE_ENGINE_V1` (new, pure; where it lives is `P12-TSK-008`'s design, never a new member of lending's
  `EngineVersion` — *placed in `bnpl` by `P12-TSK-008`, 2026-10-17: `BnplScheduleEngineV1` over the pinned `BnplTerms`*)**: `n = 4`, frequency `BIWEEKLY` (a BNPL
  vocabulary member, not lending's `RepaymentFrequency`), `D1` = the purchase's business date, `Dk = D1 + 14(k−1)`;
  amounts per A4; no interest; refuses any other shape. Reason for a new engine: `ScheduleEngineV1` is a monthly
  annuity with a ≥ 15-day first period and actual-day interest; P4 has none of these.
- **PN — `SCHEDULE_ENGINE_V1` reused** (fixed rate, monthly, level actual-day annuity, ACT/365F, `UP` instalment
  rounding) with `n ∈ {3, 6, 12}`, `D1` per A6, and its `recalculateAtInstalment` for refunds (same instalment, least
  term = latest-first) and payoff. `ACCRUAL_ENGINE_V1` for daily accrual, cumulative rounding once per period.
- **Allocation — `ALLOCATION_ENGINE_V1` reused** for both: oldest due first, `FEES → INTEREST → PRINCIPAL`, excess
  `HOLD_AS_CREDIT` (applied at the next billing, refunded at closure).
- **The projection is not the bill** (PN): billing bills actual accrued interest (Phase 11 §12.2 point 5).

### 12.3 Interest and fees

PN interest per ADR-0094 (lending's conventions pinned on the BNPL terms). P4: none — the terms `CHECK` rate = 0 and no
accrual rows. Late fee (A13): `DR BNPL_FEES_DUE / CR BNPL_FEE_INCOME`, once per instalment, capped. No penalty
interest, no prepayment fee (L8).

### 12.4 The purchase: authorisation, financing and the obligation (T-pur)

```
checkout session OPEN (merchant, gross G, EUR, expires_at)
→ BNPL application (credit request) → decision APPROVED → offer (plan, schedule, expiry ≤ session's)
→ T-pur (one transaction): L0 · offer OFFERED · session open & unpaid (B3) · consume · L6 headroom ≥ G
    · agreement v1 · purchase · financing · obligation ACTIVE · schedule v1 · accounts opened
    · (P) the payable and (P4) the wallet, one pair in UUID.compareTo order · L8
    · F1  DR BNPL_PRINCIPAL G; DR MERCHANT_PAYABLE f / CR MERCHANT_PAYABLE G; CR BNPL_MERCHANT_FEE_INCOME f
    · P4: F2 bill instalment 1 (DR BNPL_PRINCIPAL_DUE a1 / CR BNPL_PRINCIPAL a1)
          F3 repay instalment 1 from wallet (DR CUSTOMER_WALLET a1 / CR BNPL_PRINCIPAL_DUE a1)
    · session completed "paid by BNPL" → checkout's Order born
→ merchant payout (existing, on demand) → payments' outbound / settlement → reconciliation (existing)
```

**Distinguishing the six things** (the owner's list): *merchant payment* = F1's `MERCHANT_PAYABLE` credit (an
obligation of the platform to the merchant), later discharged by `merchant`'s payout; *credit exposure* = the
decision's reservation → the agreement's commitment (counted by exposure v3 from T-pur's commit); *financing
agreement* = the agreement version and its evidence; *merchant settlement* = the payout and its Phase 8 evidence;
*customer debt* = the obligation's receivable balances; *repayment collection* = repayments and their allocations.

**When does the receivable exist?** At T-pur's commit, in the entry that credits the merchant (`INV-BNPL-02`). There is
no pre-financing commitment state: acceptance and financing are one commit, so lending's "committed principal of loans
`PENDING_DISBURSEMENT`" has no BNPL analogue — BNPL counts only outstanding principal in headroom and exposure (from the
same commit; no gap).

**"Merchant settled but agreement creation fails"** (delivery plan §12) cannot occur: one transaction. **The
compensating path the gate allows is therefore not needed**; the test proves a forced failure at each step leaves no
financing, no consumption, no order and no capital commitment.

**Instalment 1 failure (P4):** wallet short → `422 bnpl.InsufficientFunds`, nothing committed; the customer may top up
and retry with the same offer before it expires (no card-funded instalment — B6).

### 12.5 The merchant fee (merchant discount)

Fee `f = round_HALF_EVEN(G · bps / 10 000) + fixed`, from the merchant's BNPL fee schedule version pinned on the purchase
(`INV-MER-03`), `f < G` (ADR-0058). Recognised at purchase (A9). Pro-rated back on refund by the cumulative method:
`fee_returned(refund) = cum_fee(refunded_after) − cum_fee(refunded_before)` with `cum_fee(x) = round(f · x / G)` and
`cum_fee(G) = f` — order-independent, telescoping to exactly `f` at a full refund; a zero fee (PN's is optional,
OQ-17) posts no fee pair at all, the ledger refusing zero-amount lines (the `MerchantSettlement.refund`
precedent, DISTRIBUTED_EXECUTION §3).

### 12.6 Refunds — latest instalments first (B3, ADR-0105)

Inputs under B5 then B4: the refund amount `R ≤ G − refunded`; the due state (each instalment's unpaid principal, from
billings and allocations, plus unbilled instalments' principal).

1. Walk instalments from the **last** to the first; at each, cancel `min(remaining R, instalment's unpaid principal)`.
   Unbilled instalments: principal not yet due (`CR BNPL_PRINCIPAL`). Billed-unpaid ones: `CR BNPL_PRINCIPAL_DUE`
   (cures its DPD). A partially paid instalment cancels only its unpaid part.
2. If `R` exceeds the total unpaid principal: the excess `E` is owed to the customer — credited to the wallet
   (`CR CUSTOMER_WALLET`, A12) in the same transaction (or held in `BNPL_CREDIT_BALANCE` if the wallet cannot be
   credited, refunded at closure or when postable — OQ-13, owner accepted default).
3. The merchant pays the refund out of its payable: `DR MERCHANT_PAYABLE (R − fee_returned)`, `DR BNPL_MERCHANT_FEE_INCOME
   fee_returned` — total debit `R` against the receivable reduction + excess. The payable may go below zero only within
   `INV-MER-07`'s amended bound (B7): Σ over the purchase's refunds of `R − fee_returned` ≤ `G − f` (its net), which
   `R ≤ G − refunded` and the telescoping fee guarantee; the debt is recovered before any payout (`INV-MER-05`).
4. Late fees already assessed on a cancelled instalment: waived in the same entry (`DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE`)
   — a fee on an obligation that no longer exists is not owed (OQ-14, owner accepted default).
5. PN: accrue every elapsed date first (T-acc); the refund's unbilled principal reduction is applied by
   `recalculateAtInstalment` (the same instalment, the least term), which removes instalments from the end — the
   latest first, as B3 requires — and the refund's per-instalment adjustment rows are **defined as the difference
   between schedule v n and v n+1** (instalments removed from the end, the new last one shortened), with any part of
   `R` beyond the unbilled principal cancelling billed-unpaid principal from the latest billed instalment backwards;
   accrued and billed interest stays owed except at a full cancellation (A11). `P12-TSK-009` proves the rows sum to
   the reduction exactly (`INV-BNPL-06`). *(Read with `P12-TSK-009`'s design, 2026-10-17: that task is the pure allocator, and
   `recalculateAtInstalment` is out of its scope (its backlog entry), so it proves its own split - Σ cancelled + E = R,
   latest first, by class (unbilled / billed-unpaid) - and the proof that pay-in-N's version-difference rows sum to that
   split's unbilled total is `P12-TSK-021`'s, which composes the two.)*
6. A schedule v n+1 and agreement v n+1 whenever any unbilled instalment is cancelled or shortened.
7. If every receivable is now zero → closure (`REFUNDED` / `REFUNDED_PARTIALLY_REPAID`), credit balance refunded — in the
   refund's transaction when the excess reached the wallet, else in the transaction that refunds the held credit (OQ-13).
8. **The B7 bound holds by arithmetic, not by a check alone**: Σ over a purchase's refunds of `R − fee_returned` = `x −
   cum_fee(x)` for the cumulative refunded `x ≤ G`, which never decreases in `x` when `f < G` and equals `G − f` at
   `x = G` — so a purchase's refunds never take back more than its financing credited (`INV-BNPL-07`).

**Before, during, after** (delivery plan §11): before instalment 2 (P4: instalment 1 paid) — full refund cancels 2–4,
excess = a1 to wallet; during — cancels from the last; after completion — every instalment paid, the whole `R` is
excess to the customer; a refund on a `CLOSED` obligation is admitted (the purchase is not terminal) and posts only the
merchant debit and the customer credit (OQ-15, owner accepted default) — refused with nothing written when the wallet
cannot take the credit (a `CLOSED` obligation holds no credit balance; the merchant retries, ADR-0105). **A refund while
a dispute is open** is refused `409 bnpl.DisputeOpen` by both refund doors — the merchant settles a disputed purchase by
accepting the dispute (ADR-0106). **Customer repays after full refund:** `409 bnpl.ObligationClosed`.

### 12.7 Disputes and chargebacks

There is no card network on a BNPL purchase, so no chargeback on the financed amount: a customer's contest is a BNPL
dispute (§5.8). A chargeback can only arise on a *card-funded wallet top-up* the customer later used — that is payments'
dispute on the top-up, unrelated to the agreement. **Double-credit is impossible by construction**: a dispute's refund
outcome executes a `BnplRefund` under the same purchase lock and the same `INV-BNPL-07` bound (`INV-BNPL-08`). If Phase 12
ever adds card-funded instalments, `INV-DSP-01` must be extended to the BNPL bound — recorded.

### 12.8 Delinquency, default and the collections boundary

Phase 11 §12.7's definitions applied per obligation: due, past due, DPD from the oldest unpaid instalment, buckets from
the terms, cure, default at 90 (A14); daily, derived, append-only; a dispute hold suspends DPD on the held instalments.
**Collections case: out of scope** (Phase 13) — bnpl emits `BnplDelinquencyChanged`, `BnplDefaulted`.

### 12.9 Capital (B2, ADR-0104)

```
headroom(c) = CR−DR(LENDING_CAPITAL, c)
            − Σ committed principal of loans PENDING_DISBURSEMENT in c         (lending)
            − Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) over loans/lines in c  (lending)
            − Σ DR−CR(BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE) over obligations in c   (bnpl, new)   ≥ 0
```
Judged under L6 at every BNPL purchase (G), every principal-re-instating BNPL reversal, and lending's existing
consumers. A BNPL refund or repayment restores headroom at commit. **Merchant fee income is not capital** (lending A29).
`LendingCapitalProof` gains the BNPL term; the safeguarding adjustment `lending_funded(c)` adds BNPL principal
outstanding (BNPL creates no wallet money but it *does* create merchant-payable liabilities without cash entering —
the same safeguarding reasoning, ADR-0096 §Why).

### 12.10 Exposure (credit `PlatformCreditExposure` v3)

v3 = v2 + per party and currency `Σ DR−CR(BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE)` of its obligations not `CLOSED`
(principal only, lending A3), one statement over lending's and bnpl's definer views and the journal lines. Decisions
under v1/v2 replay identically (the version recorded). L0 first in T-pur and the reversal (`INV-BNPL-10`). The
`ExposureCensus` extended.

### 12.11 Ledger integration (ledger `V027`, ADR-0103)

**Account plan.** `OwnerKind.BNPL_OBLIGATION` (`owner_ref` = the obligation id):

| Purpose | Owner | Type | Holds |
|---|---|---|---|
| `BNPL_PRINCIPAL` | BNPL_OBLIGATION | ASSET | purchase principal not yet due |
| `BNPL_PRINCIPAL_DUE` | BNPL_OBLIGATION | ASSET | billed principal unpaid |
| `BNPL_INTEREST_ACCRUED` | BNPL_OBLIGATION | ASSET | PN: earned, not due |
| `BNPL_INTEREST_DUE` | BNPL_OBLIGATION | ASSET | PN: billed interest unpaid |
| `BNPL_FEES_DUE` | BNPL_OBLIGATION | ASSET | late fees unpaid |
| `BNPL_CREDIT_BALANCE` | BNPL_OBLIGATION | LIABILITY | overpayment / unrefundable excess owed to the customer |
| `BNPL_MERCHANT_FEE_INCOME` | OPERATIONAL | REVENUE | merchant discount |
| `BNPL_INTEREST_INCOME` | OPERATIONAL | REVENUE | PN interest |
| `BNPL_FEE_INCOME` | OPERATIONAL | REVENUE | late fees |
| `BNPL_WRITE_OFF_EXPENSE` | OPERATIONAL | EXPENSE | seeded, unposted (Phase 14) |

Separate purposes from `LOAN_*` (OQ-11, owner accepted default; it weighed reusing them under a new owner kind — which `LendingCapitalHeadroom`
would then count for free, but which would make `LendingBooksHaveOnePosterTest`'s "only lending posts loan purposes"
false): `BnplBooksHaveOnePosterTest` holds the mirror; all join `closedToFreeAdjustments()`. `MERCHANT_PAYABLE` gains
bnpl as a poster; `MerchantPayable`'s drill-down adds `BNPL_PRINCIPAL` to its `COUNTERPARTIES` and the kinds
`BNPL_FINANCING`, `BNPL_REFUND` (`GET /v1/merchant/transactions`), so `INV-MER-02`'s equation gains "+ financed −
BNPL fees − BNPL refunds + BNPL fees returned".

**Posting rules** (every entry dated the business date of the DB clock under the obligation's lock; reference = the
bnpl operation id; balanced per currency):

| # | Economic event | Debit | Credit | Reasoning | Key |
|---|---|---|---|---|---|
| F1 | Purchase financed | `BNPL_PRINCIPAL` G; `MERCHANT_PAYABLE` f | `MERCHANT_PAYABLE` G; `BNPL_MERCHANT_FEE_INCOME` f | the platform acquires a claim on the customer for the price, owes the merchant the price, and takes its discount out of that payable — the **same four-line shape** as `MerchantSettlement.settle`'s capture (`DR clearing G / CR MERCHANT_PAYABLE G; DR MERCHANT_PAYABLE f / CR FEE_REVENUE f`), with the receivable in the clearing account's place, so `MerchantPayable`'s "captured − fees" reading holds unchanged; no cash moves (capital headroom binds it) | `bnpl.financing:<purchase>` |
| F2 | Instalment falls due | `BNPL_PRINCIPAL_DUE` p; PN `BNPL_INTEREST_DUE` i | `BNPL_PRINCIPAL` p; PN `BNPL_INTEREST_ACCRUED` i | reclassification | `bnpl.billing:<instalment>` |
| F2a | PN interest accrued (daily) | `BNPL_INTEREST_ACCRUED` | `BNPL_INTEREST_INCOME` | income as earned | `bnpl.accrual:<obligation>:<date>` |
| F3 | Repayment / auto-collection (incl. P4 instalment 1) | `CUSTOMER_WALLET` A | due accounts per allocation; `BNPL_CREDIT_BALANCE` rest | the customer's funds settle the claim | `bnpl.repayment:<repayment>` |
| F3a | Credit balance applied — at a billing, or as the catch-up over past-due items (the Phase 11 gate's R2) | `BNPL_CREDIT_BALANCE` | due accounts | excess settles what became due | `bnpl.credit-application:<billing>`; catch-up `bnpl.credit-application:<obligation>:catch-up:<n>` (`UNIQUE (obligation_id, catch_up_sequence)`) |
| F4 | Late fee | `BNPL_FEES_DUE` | `BNPL_FEE_INCOME` | contractual fee | `bnpl.fee:<instalment>:LATE` |
| F5 | Merchant refund R (cancel latest-first) | `MERCHANT_PAYABLE` R; `BNPL_MERCHANT_FEE_INCOME` r_f | `BNPL_PRINCIPAL` (unbilled cancelled); `BNPL_PRINCIPAL_DUE` (billed cancelled); `CUSTOMER_WALLET` E (excess); `MERCHANT_PAYABLE` r_f | the merchant gives back the price (the `RETURNED` refund shape of `MerchantBoundRefundComposition`: `DR MERCHANT_PAYABLE R`, the fee returned `DR fee income / CR MERCHANT_PAYABLE r_f`); the customer's debt falls by what it still owed, and what it had already paid comes back | `bnpl.refund:<refund>` |
| F5a | Fees/interest released on cancellation | `BNPL_FEE_INCOME` / `BNPL_INTEREST_INCOME` | `BNPL_FEES_DUE` / `BNPL_INTEREST_DUE`, `…ACCRUED` | released per §12.6 points 4–5 | inside F5's entry |
| F6 | Payoff | rules F2a, F2, F3, then credit-balance refund | … `CUSTOMER_WALLET` (refund) | every receivable to zero | each rule's own key; refund `bnpl.closure-refund:<obligation>` |
| F7 | Fee waived (four-eyes) | `BNPL_FEE_INCOME` | `BNPL_FEES_DUE` | `ReversalService` of F4, bounded | `ledger.reverse` |
| F8 | Repayment reversed (four-eyes) | F3's credits | `CUSTOMER_WALLET` | exact swap, `ReversalService` | `bnpl.repayment-reversal:<id>` |
| F9 | Dispute resolved as refund | = F5 | = F5 | a refund under the same bound | `bnpl.refund:<refund>` |
| F10 | Merchant payout | `MERCHANT_PAYABLE` | payout clearing (merchant's existing rule) | merchant's own entry | merchant's keys |
| F11 | Write-off (deferred, Phase 14) | `BNPL_WRITE_OFF_EXPENSE` | every BNPL asset account | loss | — |

**Worked check (P4, G = 100.00, f = 4.00, a = 25.00 ×4):** T-pur: DR BNPL_PRINCIPAL 100, DR MERCHANT_PAYABLE 4 / CR
MERCHANT_PAYABLE 100, CR FEE 4 (payable +96); bill 1: DR PD 25 / CR P 25; repay 1: DR WALLET 25 / CR PD 25. Full refund
now: unpaid principal 75 (instalments 4, 3, 2), excess 25; fee returned 4: DR MERCHANT_PAYABLE 100, DR FEE 4 / CR
BNPL_PRINCIPAL 75, CR WALLET 25, CR MERCHANT_PAYABLE 4 (payable −96). Result: every BNPL account 0, merchant payable back
to 0 (if not yet paid out), fee income 0, customer wallet −25 +25 = 0. Value preserved; each entry balances (104 = 104;
104 = 104).

**Open accounting questions (never invented):** (a) whether the merchant discount is income at purchase or a yield
adjustment amortised over the instalments (A9 defaults to purchase; Phase 14 owns EIR); (b) whether the receivable
should be recorded at G or at G − f with the discount as unearned (an IFRS 9 question — Phase 14); (c) credit-impaired
treatment, provisioning — Phase 14.

### 12.12 Reconciliation — the three-way proof (gate criterion 5)

`BnplThreeWayProof`, in one `REPEATABLE READ` snapshot, per currency, gauged and alerting:
1. **Merchant leg:** Σ over purchases (gross − fee) − Σ refunds' payable debits = Σ BNPL lines on `MERCHANT_PAYABLE`
   (by entry id) — per merchant; and each merchant's payable position still satisfies `INV-MER-02`'s equation with the
   BNPL terms added.
2. **Customer leg:** per obligation, the subledger arms (Phase 11 `LoanSubledgerProof` shape): `BNPL_PRINCIPAL` = G −
   billed − cancelled-unbilled; `BNPL_PRINCIPAL_DUE` = billed − allocated − cancelled-billed (± reversals); interest,
   fees, credit balance likewise; a `CLOSED` obligation all zero.
3. **Ledger / economic identity:** per purchase, `G = principal repaid + principal cancelled by refunds + principal
   outstanding (+ written off, 0)`, and `Σ refunds = Σ principal cancelled + Σ excess to customer`, and
   `fee recognised − fee returned = BNPL_MERCHANT_FEE_INCOME`'s BNPL lines for the purchase.
4. Trial balance zero per currency; `BnplReplayProof` IDENTICAL; capital proof with the BNPL term.

External reconciliation is unchanged: the merchant payout's expectation and its settlement evidence (Phase 8), capital
through bank recognition. BNPL adds no external evidence.

### 12.13 Integration boundaries (reuse, never duplicate)

| Context | What BNPL needs | Through | What BNPL must not do |
|---|---|---|---|
| **Checkout / merchant** | the session (merchant, amount, currency, expiry, token), its completion by financing and the order; the merchant's standing, BNPL enablement, payable account and pinned BNPL fee schedule; the merchant-key refund and read doors | `BnplCheckoutSessions` and `BnplMerchants` (ports declared by `bnpl`, implemented in `app`); checkout `V004`, merchant `V010` | create a payment intent or attempt; post a capture; pay a merchant out; read a merchant's other payments |
| **Payments** | nothing at purchase; the merchant's payouts (existing) and the customer's wallet top-ups (existing pay-ins) | unchanged — merchant's payout machinery calls payments' outbound adapters | add a "BNPL rail" (§2.1); call a provider; hold a connection across any call |
| **Credit** | eligibility and the decision for a BNPL product; the decision consumed by the agreement; exposure version 3 | `BnplCreditDecisions` (submit through the BNPL door, `consume(BNPL_AGREEMENT, agreementId)`, `lockExposure` = L0), credit `V022`; `app`'s exposure v3 | compute a score or an affordability figure; show a reason to a merchant; open a BNPL request outside a checkout |
| **Lending** | the capital row and headroom (L6); the pure engines (via `creditmath`) | `BnplCapital` over `LendingCapitalHeadroom.underLock`; the `creditmath` module | read or write a lending store; create a `loan`; post a `LOAN_*` purpose |
| **Ledger** | per-obligation accounts, the posting and reversal services, holds, `lockBalancesInOrder` | `PostingService`, `ReversalService`, `BnplAccounts` opener; ledger `V027` | mutate a balance; post through `AdjustmentService` on a BNPL account (closed to free adjustments) |
| **Settlement / reconciliation** | none new — the merchant payout's expectation and evidence (Phase 8); capital through bank recognition | existing openers and proofs | open a BNPL expectation (BNPL moves no money across a rail) |
| **Identity / KYC / consent** | the customer's session and assurance (`MULTI_FACTOR` for the application, acceptance and payoff); standing (KYC verified, not suspended) read in-transaction; credit's consent gate for its sources | identity's session, `BnplPartyStanding` over `CreditPartyStanding`; identity `V022` (permissions, roles) | create a consent purpose (acceptance is not consent, `INV-IDN-04`); store identity documents |
| **Risk / fraud / AML** | nothing in Phase 12 — risk is Phase 13; credit's `CreditRiskSignal` seam stays `NotAssessedUntilPhase13` | — | build a rule, a velocity counter or a case; a BNPL purchase is screened only through credit and KYC standing |
| **Accounting (Phase 14)** | the seeded `BNPL_WRITE_OFF_EXPENSE`; the merchant discount's EIR; provisioning | — | post a write-off or a provision |
| **Collections (Phase 13)** | consumes `BnplDelinquencyChanged`, `BnplDefaulted` | outbox events | open a case, contact a customer, assign an agency |

---

## 13. Testing strategy

### 13.1 Tiers (B4 — the standing criterion-7 deviation)
Hermetic (engines, fee split, refund allocator — property tests); database (Testcontainers) for the task's own and
adjacent suites; architecture (`BnplModuleIsolationTest`, `BnplSchemaHasNoMutableMoneyTest`,
`NoSingleInstanceAssumptionRulesTest` +3, `ColumnClassificationTest`, `RoutePermissionRegisterTest`,
`BnplBooksHaveOnePosterTest`, `NoFloatingPointMoneyRulesTest` over `bnpl`); fleet hermetic + architecture once per merged
wave; fleet `databaseTest`/`kafkaTest` skipped, recorded per task as a criterion-7 deviation.

### 13.2 Mandatory scenarios (each a named test in its task and in the storm)

| # | Scenario | Test (task) |
|---|---|---|
| 1 | Duplicate checkout financing request (same key; two instances) | `BnplPurchaseDatabaseTest#theSameAcceptanceTwiceFinancesOnce` (`-013`) |
| 2 | Duplicate purchase authorisation: BNPL acceptance racing a card payment of the same session | `#bnplAndCardOnOneSessionCompleteOnce` (`-013`) |
| 3 | Concurrent eligibility: a BNPL acceptance and a loan decision for one party | `#acceptanceAndADecisionSerialiseOnTheProfile` (`-013`) |
| 4 | Duplicate instalment collection (ten sweepers; sweep vs customer repayment) | `BnplCollectionDatabaseTest#tenSweepersCollectOnce` (`-019`) |
| 5 | **A payment provider succeeds but the response is lost, then a retry** — the merchant payout of a BNPL-financed payable: dispatch accepted, response lost, the merchant retries under its key | `BnplMerchantPayoutDatabaseTest#aLostPayoutResponseIsHeldAndTheRetryReplays` (`-016`): the `SimulatedPayoutProvider` executes and the answer is lost → `merchant_payout` `UNKNOWN`, the hold standing; the merchant's retry under the same `merchant.payout:<merchantId>` key replays (no second dispatch); `MerchantPayoutResolution`'s query concludes `COMPLETED` and posts `DR MERCHANT_PAYABLE / CR PAYOUT_CLEARING` once; one payout at the provider; the BNPL-financed payable bounded throughout and the agreement unchanged; and the wallet top-up variant (customer tops up by card for an instalment, response lost, retry) in `P12-TST-001` |
| 6 | Two repayments on one instalment | `BnplRepaymentDatabaseTest#twoRepaymentsSerialiseAndConserve` (`-018`) |
| 7 | Refund racing instalment collection | `BnplRefundDatabaseTest#aRefundRacingACollectionConservesInBothOrders` (`-021`) |
| 8 | Duplicate refund (merchant retry; two instances; lapsed claim) | `#theSameRefundTwiceRefundsOnce` (`-021`) |
| 9 | Refund before / during / after instalments; refund exceeding remaining → customer credit; full refund closes | `#refundBeforeDuringAfter`, `#excessGoesToTheCustomer`, `#aFullRefundClosesWithNothingStranded` (`-021`) |
| 10 | Chargeback/dispute plus refund cannot double-credit | `BnplDisputeDatabaseTest#disputeRefundAndMerchantRefundShareOneBound` (`-023`) |
| 11 | Early payoff with a refund in flight | `BnplPayoffDatabaseTest#payoffBesideARefundIsStaleOrClosedNeverWrong` (`-022`) |
| 12 | Repayment reversal after allocation | `BnplRepaymentReversalDatabaseTest#aReversalReopensWhatItPaid` (`-024`) |
| 13 | Concurrent delinquency evaluation / payoff during the worker | `BnplConditionDatabaseTest#noConditionAfterClosure` (`-020`) |
| 14 | Crash after posting before publication; crash at every T-pur step | `BnplOutboxAtomicityDatabaseTest`, `BnplPurchaseDatabaseTest#aFailureAtEveryStepLeavesNothing` (`-013`) |
| 15 | Delayed / duplicated `CreditDecisionRecorded`; session expiry while awaiting a decision | `BnplOriginationDatabaseTest#hintsTwiceLateOrNeverConverge`, `#aSessionThatExpiresClosesTheApplication` (`-011`) |
| 16 | Customer repays after a full refund | `#aRepaymentAfterAFullRefundIsRefused` (`-021`) |
| 17 | Late fee application, and its release on a refund-cancelled instalment | `BnplLateFeeDatabaseTest` (`-020`, `-021`) |
| 18 | PN accrual run twice / restart / skew | `BnplAccrualDatabaseTest#accruesOncePerDate` (`-017`) |

### 13.3 Exit tests
- **`P12-TST-001` — the BNPL storm:** two instances, clocks ±5 s; shared parties holding loans, lines and BNPL; merchants
  shared; checkout sessions racing BNPL and card; capital contested across lending and BNPL; refunds, partial refunds,
  disputes, payoffs, collections, reversals racing; merchant payouts with lost responses; ≥ 3 business-date boundaries;
  every scenario of §13.2; crash points. At rest each round: three-way proof zero, subledger zero, replay IDENTICAL,
  capital proof, exposure census, trial balance zero, one financing per purchase, Σ refunds ≤ gross per purchase, every
  act counted (at every round, not only the final rest — the Phase 11 I2 lesson). PASS = three consecutive green runs
  and its probes caught.
- **`P12-TST-002` — the BNPL battery:** ≥ 10,000 generated purchases across both products, all plans, amounts incl.
  non-divisible-by-4, purchase dates across leap years and month ends, random repayment, refund and dispute sequences on a
  simulated calendar; every figure replayed IDENTICAL in two JVMs; the value-preservation and fee-telescoping properties
  for every refund order; a perturbed allocation, refund adjustment or agreement byte flips the verdict.

### 13.4 Probes (recorded in `MUTATION_TESTING.md` §2)
Drop `UNIQUE (checkout_session_id)` on purchase **with the B3 session lock and its `OPEN` check bypassed** → scenario 2 red (every born-once arbiter is probed alone, the locks and conditions above it bypassed — the Phase 11 review's correction; dropped alone it would survive behind B3); refund earliest-first → `RefundAllocationEngineV1Test#refundCancelsLatestFirst` (`-009`) and `BnplRefundDatabaseTest#refundBeforeDuringAfter` red; skip the purchase lock in a refund → scenario 10 red; fee pro-rated by a running clamp → the telescoping property
red; skip L6 → two purchases over-deploy; read the projection for allocation → scenario 6 red under a racing reversal;
re-dispatch a payout on `UNKNOWN` → scenario 5 red **by a count of dispatches at the simulated provider and on the merchant side** (the probe survived in Phase 11 as an equivalent mutant because the key replays; the count is what makes it catchable — the Phase 11 review's finding 16 applied forward).

---

## 14. Failure scenarios

| Situation | Outcome |
|---|---|
| Request times out; client retries | the key replays (`INV-IDEM-01`) |
| DB commits, response lost (acceptance) | the retry replays the agreement id; the session is already paid; the merchant sees one order |
| Service crashes mid-T-pur | nothing committed: no financing, no order, decision unconsumed; the offer still acceptable until it expires |
| Event duplicated / late / missing | hints only; sweeps read rows |
| Merchant payout provider unavailable / unknown | merchant/payments hold it; BNPL unaffected |
| A webhook duplicated | payments' dedupe; BNPL consumes none |
| Two requests race | §7.4 |
| Settlement arrives late | merchant payout's expectation ages; BNPL unaffected |
| Reconciliation break on a BNPL-financed payout | Phase 8's resolution; the agreement is unaffected |
| Refund after the merchant was paid out | the payable goes negative within the amended `INV-MER-07` bound, recovered from later captures and financings before any payout (`INV-MER-05`); counted by `finapp.ledger.negative.positions` (B7) |
| Merchant suspended with open BNPL purchases | existing obligations serviced unchanged; refunds still admitted; new BNPL refused `422 bnpl.MerchantNotEnabled` |
| Customer's wallet closed | collection records `WALLET_NOT_POSTABLE`; DPD runs; a refund excess held in `BNPL_CREDIT_BALANCE` |
| Checkout session expires during the decision | application `CLOSED_UNDECIDED`; the approval lapses unconsumed |
| Capital exhausted | `422 bnpl.CapitalUnavailable` at acceptance; checkout may fall back to another payment method |
| **Cancellation** (the customer returns the goods before or after instalment 1) | there is no cancellation state: the platform never "un-finances" by editing; the merchant refunds the full gross (T-ref) — every unpaid instalment cancelled latest-first, instalment 1's payment (P4) returned to the wallet as the excess, the fee returned in full, the obligation `CLOSED (REFUNDED)` (`INV-BNPL-09`) |
| **Partial refund** | latest instalments reduced first; schedule and agreement v n+1; the obligation stays `ACTIVE` with the remaining instalments; the fee pro-rated back by the cumulative method |
| **Failed instalment** (wallet short on the due date) | the collection attempt records `INSUFFICIENT_FUNDS` (born once per (instalment, date)); a partial amount collected if available (A7); retried daily while due; DPD from the due date; late fee once on the pinned day; default at 90 (A14); the customer may repay any time; nothing is written off (Phase 14) |
| **Repayment reversal** (a wallet payment later found wrong) | four-eyes T-rev: the exact swap of F3 through `ReversalService`, L0 first (outstanding rises), L6 if principal is re-instated; the instalments re-opened, DPD recomputed; never edits |
| **Early payoff** | the quote recomputed under B4 (`409 bnpl.PayoffQuoteStale` if anything moved); F3 for every receivable; any credit balance refunded; `CLOSED (PAID_OFF_EARLY)`; a refund racing it waits on B5/B4 and finds `CLOSED` (excess to the customer only) |
| **A payment provider succeeds but the response is lost, then a retry** (the merchant payout of a BNPL-financed payable; the customer's card top-up for an instalment) | the payout held `UNKNOWN` with its hold standing, the merchant's retry under the same key replays, never re-dispatches; the inquiry concludes it once (ADR-0083); the BNPL agreement unaffected — scenario 5 |

---

## 15. Observability

Counters (closed tags, no amounts — ADR-0072): `finapp.bnpl.application{product,outcome}`, `.eligibility{product,outcome}`
(approval rate), `.offer`, `.purchase{product,plan,outcome}` (plan mix), `.refund{kind=full|partial,outcome}` (refund
rate), `.dispute{outcome}`, `.repayment{kind,outcome}`, `.collection{outcome}`, `.accrual{outcome}`, `.fee{outcome}`,
`.payoff`, `.reversal`, `.waiver`, `.allocation.anomaly` (alert > 0). Gauges: `finapp.bnpl.delinquency{product,bucket}`,
`.default{product}`, `.obligations{product,status}`, `.threeway.proof{verdict}` (alert), `.subledger.proof`, `.replay`
(alert DIVERGED), `.capital.headroom.low` (shared), `.terms.active{product}`, the three `…sweeper.enabled`. **Amounts**
— merchant financing exposure, outstanding, refund impact on outstanding — are audited reports, never series.
Spans per step (`BnplFlowScope`, no attributes). `infra/prometheus/rules/bnpl.yml`; a dashboard row.

---

## 16. Milestones

| Milestone | Acceptance | Tasks |
|---|---|---|
| **M12.1 Foundations** | credit knows BNPL; the module, vocabularies and roles; the chart; shared capital headroom; terms versions | `-001`…`-005` |
| **M12.2 Merchant and checkout seams** | BNPL fee schedule and enablement; checkout completed by a BNPL purchase | `-006`, `-007` |
| **M12.3 Engines** | P4 schedule engine; engine sharing (PN reuse); the refund allocator and fee pro-rating, pure | `-008`, `-009` |
| **M12.4 Origination and purchase** | application → offer → acceptance = purchase, financing and obligation atomic, exposure v3, capital | `-010`…`-013` |
| **M12.5 Merchant leg** | merchant reads, the payable drill-down, payouts of BNPL-financed payables proven | `-014`, `-016` |
| **M12.6 Servicing** | billing, PN accrual, repayment, auto-collection, conditions and late fees | `-015`, `-017`…`-020` |
| **M12.7 Refunds, disputes, payoff, corrections** | latest-first refunds, disputes, payoff, reversal/waiver | `-021`…`-024` |
| **M12.8 Proof and operations** | three-way proof, subledger, replay, explanation, reports, alerts, runbook | `-025`, `-026` |
| **M12.9 Exit** | the storm, the battery, the review | `P12-TST-001`, `P12-TST-002`, `P12-DOC-001` |

**Cut order:** `-023` disputes' operator resolution (keep merchant-accepted refunds — the cut then makes the merchant's
response accept-only, or `MERCHANT_RESPONDED` would have no exit but withdrawal), then `-022` PN payoff (P4 payoff
kept). **Never cut:** atomic financing, latest-first refunds, the shared capital headroom, exposure v3, the three-way
proof, the storm, the battery.

---

## 17. Backlog (small vertical slices)

Every item is elaborated with the gate's fields in [`BACKLOG.md`](BACKLOG.md) §Phase 12 (objective, bounded context and
dependencies, scope and exclusions, domain/persistence/API/event changes, financial impact and invariants, concurrency,
idempotency and consistency, failure handling, security and audit, reconciliation, tests, probe, acceptance criteria,
Definition of Done profiles). Migration numbers are the next free per module at the transition; the building task may
renumber within its module and says so.

| ID | Title | Milestone | Depends on | Migrations | DoD profiles |
|---|---|---|---|---|---|
| `P12-TSK-001` | Credit learns BNPL: products, the term shape and the consumer kind | M12.1 | Phase 11 `COMPLETE` | credit `V022` | `DOD-FIN`, `DOD-DOMAIN`, `DOD-SEC` |
| `P12-TSK-002` | The `bnpl` module boundary, floors, roles and the `creditmath` engine extraction | M12.1 | `-001` | `bnpl V001`, identity `V022` | `DOD-ARCH`, `DOD-SEC`, `DOD-FIN` |
| `P12-TSK-003` | The BNPL chart of accounts | M12.1 | `-002` | ledger `V027` | `DOD-FIN` |
| `P12-TSK-004` | BNPL terms as versioned data | M12.1 | `-002` | `bnpl V002` | `DOD-FIN`, `DOD-API`, `DOD-SEC` |
| `P12-TSK-005` | Capital headroom counts BNPL principal | M12.1 | `-003` | none (Java) | `DOD-FIN` |
| `P12-TSK-006` | Merchant BNPL enablement and fee schedule | M12.2 | `-002` | merchant `V010` | `DOD-FIN`, `DOD-SEC`, `DOD-API` |
| `P12-TSK-007` | Checkout completed by a BNPL purchase | M12.2 | `-002` | checkout `V004` | `DOD-DOMAIN`, `DOD-API` |
| `P12-TSK-008` | The P4 schedule engine | M12.3 | `-002` | none | `DOD-FIN` |
| `P12-TSK-009` | The refund allocator and fee pro-rating (pure) | M12.3 | `-008` | none | `DOD-FIN` |
| `P12-TSK-010` | The BNPL application at checkout | M12.4 | `-001`, `-004`, `-006` (merchant enablement), `-007` | `bnpl V003` (`V005` left unused unless the `-012`/`-013` pair's design renumbers — the credit `V018` precedent) | `DOD-FIN`, `DOD-API`, `DOD-SEC` |
| `P12-TSK-011` | The offer and the origination schedule | M12.4 | `-010`, `-008` | `bnpl V004` | `DOD-FIN`, `DOD-EVENT` |
| `P12-TSK-012` | Platform credit exposure version 3 | M12.4 | `-001`, `-003`; **paired with `-013`** (one branch, one merge - the view reads `-013`'s obligation table, the Phase 11 `-012`/`-013` precedent) | `bnpl V007` (definer view) | `DOD-FIN` |
| `P12-TSK-013` | The acceptance: purchase, merchant financing and the obligation, atomically | M12.4 | `-005`, `-006`, `-007`, `-009`, `-011`; paired with `-012` | `bnpl V006` | `DOD-FIN`, `DOD-API`, `DOD-SEC`, `DOD-EVENT` |
| `P12-TSK-014` | Merchant reads of BNPL purchases | M12.5 | `-013` | none | `DOD-API`, `DOD-SEC` |
| `P12-TSK-015` | Instalment billing, the catch-up application and the servicing schedule | M12.6 | `-013` | `bnpl V008` | `DOD-FIN` |
| `P12-TSK-016` | The merchant payable drill-down and payouts of BNPL-financed money | M12.5 | `-013` | none (the drill-down is merchant code; a migration only if its design needs one) | `DOD-FIN`, `DOD-API` |
| `P12-TSK-017` | PN interest accrual | M12.6 | `-015` | `bnpl V009` | `DOD-FIN` |
| `P12-TSK-018` | Customer repayment from the wallet and closure | M12.6 | `-015` | `bnpl V010` | `DOD-FIN`, `DOD-API`, `DOD-SEC` |
| `P12-TSK-019` | Auto-collection | M12.6 | `-018` | `bnpl V011` | `DOD-FIN` |
| `P12-TSK-020` | Delinquency, default and late fees | M12.6 | `-019` | `bnpl V012` | `DOD-FIN` |
| `P12-TSK-021` | Merchant refunds, latest instalments first | M12.7 | `-009`, `-016`, `-018` | `bnpl V013`, merchant `V011` (the merchant key's refund scope) | `DOD-FIN`, `DOD-API`, `DOD-SEC` |
| `P12-TSK-022` | Early payoff (both products) | M12.7 | `-021`, `-017` | `bnpl V014` | `DOD-FIN`, `DOD-API` |
| `P12-TSK-023` | BNPL disputes | M12.7 | `-021`, `-020` | `bnpl V015` | `DOD-FIN`, `DOD-SEC`, `DOD-API` |
| `P12-TSK-024` | Repayment reversal and fee waiver (four-eyes) | M12.7 | `-020` | `bnpl V016` | `DOD-FIN`, `DOD-SEC` |
| `P12-TSK-025` | The BNPL proofs and the explanation door | M12.8 | `-021`…`-024` | none | `DOD-FIN`, `DOD-OBS` |
| `P12-TSK-026` | Operating BNPL: meters, reports, alerts, runbook | M12.8 | `-025` | none | `DOD-OBS`, `DOD-DOC` |
| `P12-TST-001` | The BNPL storm | M12.9 | every task; the settlement-suite `X-TSK` (B8, OQ-20) | none | `DOD-TEST`, `DOD-FIN` |
| `P12-TST-002` | The BNPL battery | M12.9 | `-008`, `-009`, `-021`, `-022` | none | `DOD-TEST`, `DOD-FIN` |
| `P12-DOC-001` | The Phase 12 exit review | M12.9 | everything | none | `DOD-DOC`, `DOD-TEST` |

**Critical path:** `-001` → `-002` → `-003` → `-005` → `-013` → `-015` → `-018` → `-019` → `-020` → `-023` →
`-025` → `-026` → `TST-001` → `DOC-001`. `-004`, `-006`, `-007`, `-008` after `-002` (in parallel, at most two at a
time per the standing rule); `-009` after `-008`; `-010` after `-001`, `-004`, `-007`; `-011` after `-010`, `-008`;
`-012` with `-013` (one branch); `-014`, `-016` after `-013`; `-017` after `-015`; `-021` after `-009`, `-016`, `-018`; `-022`
after `-021`, `-017`; `-024` after `-020`; `TST-002` after `-022`.

---

## 18. Exit criteria (summary — the gate is `PHASE_GATES.md` §5 Phase 12)

The five original criteria kept as written and made measurable:
1. *Refund at every point produces correct, value-preserving adjustments* — `INV-BNPL-06` property over every refund
   sequence in the battery; before/during/after tests; the three-way proof zero in every storm round.
2. *Full refund closes the agreement, no residual obligation or stranded value* — `#aFullRefundClosesWithNothingStranded`;
   every account zero, fee fully returned, credit balance refunded; a proof arm.
3. *Chargeback plus refund cannot double-credit* — scenario 10; `INV-BNPL-08`; probe.
4. *Financing and obligation atomic* — `INV-BNPL-02`; a forced failure at every T-pur step leaves nothing; a deferred trigger.
5. *Three-way reconciliation balances* — `BnplThreeWayProof` zero at rest in every storm round and over the battery.

Per area (each a block in the gate, naming its owning tasks): **functional correctness** (both products end to end
against the simulators; every API route with negatives); **financial integrity** (F1–F11 as built; trial balance zero;
replay `IDENTICAL`; capital proof with BNPL; exposure census; the merchant payable's equation); **multi-instance
execution** (every §7.4 row counted; lock-waits asserted for L0, B3, B5, B4, L6 and (P) both id orders — the Phase 11
M3 lesson; every storm census at every round — the I2 lesson); **security** (MFA, merchant scopes and tenant
isolation, no credit data to merchants — negative tests; four-eyes; self-dealing; self-approval refusals audited);
**auditability** (every act in `AUDITABLE_ACTIONS.md` with actor, time, target, reason, correlation, outcome);
**observability** (every series registered eagerly, alerts fire on their condition only, reports audited);
**testing** (§13.2 all named and green, the storm three times green, the battery, probes caught; criterion 7 the
standing deviation, B4); **documentation** (plan, lifecycles, glossary, ADR-0101…0106 accepted, `CURRENT_STATE.md`).

---

## 19. ADRs (next free number ADR-0101; all `Proposed` at the transition)

| ADR | Title |
|---|---|
| ADR-0101 | The BNPL bounded context: `bnpl` owns the agreement, purchase, financing and servicing facts and none of the money; lending's pure engines shared through `creditmath`, lending's `loan` not composed |
| ADR-0102 | BNPL products, terms and the versioned agreement: pay-in-4 on a BNPL schedule engine, pay-in-N on lending's engines, one decision per purchase |
| ADR-0103 | BNPL accounting: per-obligation accounts, the financing entry, the merchant discount and the posting rules |
| ADR-0104 | BNPL funding from lending capital: the shared headroom and credit exposure version 3 |
| ADR-0105 | The BNPL refund policy: latest instalments first, the excess to the customer, the merchant fee pro-rated back, the PN interest rule, and the merchant payable's third bounded negative source |
| ADR-0106 | BNPL disputes and the single refund bound: a customer's contest holds servicing on the disputed amount and resolves only as a refund under the purchase's one bound, so nothing is credited twice |

No ADR-0107: the owner chose approval per purchase (B5), so the spending-limit ADR the draft anticipated is not needed.

---

## 20. What Phase 12 must NOT implement

Merchant credit risk or merchant financing limits beyond the payable bound; BNPL funding, securitisation or a separate
capital pool; collections agencies and collections case management (Phase 13); risk, fraud and AML decisioning (Phase 13
— BNPL passes through credit only); write-off, provisioning, IFRS 9, effective-interest amortisation of the merchant
discount (Phase 14); card-funded instalments or any new system-initiated card charge (B6); a pre-approved BNPL spending
limit (B5); multi-currency BNPL; consumer-credit-law features (statutory disclosures, cooling-off, caps); penalty interest;
**refinance** (decided by the transition: not Phase 12); the evidence purge (Phase 15); any change to lending's engines'
behaviour or vocabularies beyond the `creditmath` extraction (every Phase 11 golden hash unchanged).

---

## 21. Risks

- **Refund logic creating or destroying value** — the pure allocator, its properties, the three-way proof, the battery.
- **Modelling BNPL as a loan and ignoring the merchant leg** — ADR-0101's refusal; F1 credits the merchant in the
  obligation's own entry.
- **Chargeback/dispute plus refund double-crediting** — one bound under the purchase lock.
- **The merchant payable driven negative by refunds after payout** — B7: bounded per purchase by its net, counted by
  `finapp.ledger.negative.positions`, recovered before any payout; named in the drill-down.
- **Hot rows at BNPL volume** — the currency's `LENDING_CAPITAL` row (L6) now shared by every BNPL purchase, and the
  currency-wide `BNPL_MERCHANT_FEE_INCOME` row every purchase posts to (L8): correct, a throughput ceiling. The Phase 11
  debt rows (the capital row's unmeasured contention; A2-F1's income rows) become urgent — `P12-TST-001` **measures**
  lock waits on both, not only proves them; tranches or batched income recognition are Phase 16's.
- **Checkout latency of an asynchronous credit decision** — B5: per-purchase decisions; the checkout waits on `202`
  and polls; recorded.
- **Lock-order coupling with merchant, payments and checkout** — §7.3 (b)'s pair element and (c)'s session argument,
  each counted in `P12-TSK-013` and `-021` (both id orders).
- **Engine extraction regressing lending** — the battery and golden hashes re-run in `-002`.
- **The Phase 11 gate's residual RG-M1** (lending's payoff applications not floored) — BNPL's payoff must floor every
  application from its first line (`-022`); lending's remediation is recommended before it.
- **Production cannot originate** — inherited, accepted (§1.1).

---

## 22. The first task

**`P12-TSK-001` — Credit learns BNPL: products, the term shape and the consumer kind** — `READY`, marked by this
transition. Credit-owned, no bnpl code, mirroring `P11-TSK-001`: credit `V022` admits `BNPL_PAY_IN_4` and
`BNPL_PAY_IN_N` (the five `product IN (…)` `CHECK`s widened in one migration, the term shape replacing `revolving() ==
termMonths.isPresent()`, BNPL validities, affordability rule kinds, a policy fixture per product for the simulators) and
`ConsumerKind.BNPL_AGREEMENT` through the existing `SECURITY DEFINER` consumption path, with counted races. It is first
because every BNPL agreement needs an approved decision of a BNPL product consumed under L0, and the consumer kind is
the one change in credit's guarded table. `P12-TSK-002` (the module boundary and the engine extraction, mirroring
`P11-TSK-002`) follows.

---

## 23. The transition's agenda carried in (from `PHASE_11_REVIEW.md` §Decisions for the owner)

Items that bear on Phase 12's shape (the full table, after the gate, is `PHASE_11_TO_12_TRANSITION.md` §9): #1
criterion 7 — **answered** (B4, skip continues through Phase 12); #2 the three red settlement suites — **answered** (B8,
OQ-20: an `X-TSK` fixes them before `P12-TST-001`); #3 the storm's accepted deviations — **not repeated** (§18: every
census at every round, every named lock-wait counted); #4 capital return and income recycling — open (BNPL fee income
is not capital, lending's A29 kept); #5 refinance — **not Phase 12** (§20); #16 dispatch counts — applied forward
(§13.4); #19 reason classification — resolved forward (§11, `RESTRICTED-PII`); #21 hot-row characterisation — measured
in the storm (§21).

---

## 24. The open questions, settled (owner decisions B5–B8, 2026-10-16)

OQ-1, OQ-5 and OQ-7 were decided explicitly; every other question took the draft's default under B8 — **owner
accepted default, 2026-10-16, review later**. A task's design may raise one again only by saying so.

| # | Question | Settled as | How |
|---|---|---|---|
| OQ-1 | Per-purchase credit decision, or a pre-approved BNPL spending limit? | **Per purchase**; no limit, no ADR-0107 | **B5** (decided) |
| OQ-2 | Share lending's engines by extracting a pure `creditmath` module, or a restricted `bnpl → lending` edge? | **Extract `creditmath`** (`P12-TSK-002`), every golden hash and `P11-TST-002` re-run `IDENTICAL` | B8 — owner accepted default, 2026-10-16, review later |
| OQ-3 | P4 rounding: remainder on instalment 1 or on the last? | **Instalment 1** (collected at purchase) | B8 — owner accepted default, review later |
| OQ-4 | PN: first instalment one month after purchase (no down payment)? Rate fixed per terms version? | **Yes; fixed** | B8 — owner accepted default, review later |
| OQ-5 | Wallet only, or card-funded collections? | **Wallet only** | **B6** (decided) |
| OQ-6 | PN refunds: does interest accrued before the refund stay owed (except a full refund)? Is paid interest refunded? | **Stays owed; a full refund waives unpaid interest; paid interest kept** | B8 — owner accepted default, review later |
| OQ-7 | May a refund after payout drive the payable below zero? | **Yes, bounded by the purchase's net, recovered before the next payout; `INV-MER-07` amended** | **B7** (decided) |
| OQ-8 | Merchant BNPL enablement: four-eyes operator act or merchant opt-in? Fee schedule per merchant or platform-wide? | **Four-eyes enablement; per-merchant schedule** | B8 — owner accepted default, review later |
| OQ-9 | Does BNPL open the first merchant-key refund door? Operator review above a threshold? | **Merchant-key door with a refund scope, bounded by the purchase; an operator door beside it; no threshold review** | B8 — owner accepted default, review later |
| OQ-10 | Does merchant silence past the dispute deadline resolve as a refund? Response window? | **Yes; 14 days** | B8 — owner accepted default, review later |
| OQ-11 | Separate `BNPL_*` ledger purposes or `LOAN_*` under a new owner kind? Separate invariants? | **Separate purposes; `INV-BNPL-*` catalogued separately** | B8 — owner accepted default, review later |
| OQ-12 | May the merchant learn that a customer applied and was declined? | **Only `NOT_ELIGIBLE` for that session, no reason** | B8 — owner accepted default, review later |
| OQ-13 | Refund excess when the wallet cannot be credited? | **Held as `BNPL_CREDIT_BALANCE`, refunded at closure or when postable** | B8 — owner accepted default, review later |
| OQ-14 | Late fees on an instalment later cancelled by a refund? | **Released** | B8 — owner accepted default, review later |
| OQ-15 | A refund after the obligation closed (all paid)? | **Admitted: merchant payable → customer wallet only** | B8 — owner accepted default, review later |
| OQ-16 | Merchant discount: income at purchase or amortised? | **At purchase; EIR is Phase 14's** | B8 — owner accepted default, review later |
| OQ-17 | PN with a merchant fee by default? P4 with a customer fee? | **PN fee optional per schedule; no customer fee on P4** | B8 — owner accepted default, review later |
| OQ-18 | Offer validity cap and BNPL decision validity? | **Offer ≤ 30 min (and ≤ the session); request ≤ 1 h; decision ≤ 1 day** | B8 — owner accepted default, review later |
| OQ-19 | The merchant discount's income account: new `BNPL_MERCHANT_FEE_INCOME` or the existing `FEE_REVENUE`? | **New purpose** | B8 — owner accepted default, review later |
| OQ-20 | The three settlement suites red since `P9-TSK-026` — fix before `P12-TST-001`? | **Fix first (an `X-TSK`)** | B8 — owner accepted default, review later |
