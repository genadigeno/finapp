# Phase 11 — Lending Infrastructure

Written by the Phase 10 → 11 transition (2026-10-10). Decisions in ADR-0090 (the lending bounded
context: the contract and its servicing facts, none of the money; two products, one account
model), ADR-0091 (taking up a credit decision: a credit-owned consumption under the party's
profile lock, and exposure that counts committed principal, outstanding principal and open credit
lines' limits), ADR-0092 (loan terms as versioned data and the immutable, versioned agreement),
ADR-0093 (the amortisation schedule: a deterministic projection, billed from actual accrual),
ADR-0094 (interest accrual and day count: ACT/365F simple daily interest, once per date, rounded
once per period, on the database clock in a declared zone), ADR-0095 (repayment allocation: an
explicit, versioned order pinned by the agreement), ADR-0096 (loan accounting: per-loan ledger
accounts, due split from not-due, the posting rules, and the lending-capital account that funds
every loan), ADR-0097 (disbursement to the borrower's wallet and to an external bank account
through payments' withdrawal machinery), ADR-0098 (delinquency, default and the collections
boundary), ADR-0099 (servicing corrections: waivers, reversals and restructuring as reasoned,
four-eyes acts that post, never edits) and ADR-0100 (the revolving credit line: draws, statements,
the minimum payment and the available limit). The machines are in
[`LENDING_LIFECYCLES.md`](../domain/LENDING_LIFECYCLES.md); the invariants are `INV-LND-01`…`14`
in [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md); the exit criteria are
`PHASE_GATES.md` §5 Phase 11, extended by this transition; the tasks are `P11-TSK-001`…`030`,
`P11-TST-001`…`002` and `P11-DOC-001` in [`BACKLOG.md`](BACKLOG.md).

*Nothing in this document is built yet. Each task that builds a part corrects this plan where the
code teaches otherwise, and the Phase 11 exit review (`P11-DOC-001`) reads the whole of it against
the code.* The owner's transition decisions L1–L12 (§2.3, recorded in `BACKLOG.md` §Phase 11) are
folded into the sections below; the recorded assumptions A1–A30 (§2.4) are the product defaults
the plan builds on, each configurable on a versioned terms row.

---

## 1. Objective

Originate and service credit whose every figure the platform can explain from authoritative records
years later: **economic event → lending operation (with its pinned agreement version and engine
versions) → journal entry → per-loan ledger lines → derived balances → (for money leaving the
platform) settlement → reconciliation.** Two products: the amortising **`PERSONAL_LOAN`** and the
revolving **`CREDIT_LINE`**, both built on Phase 10's credit decisions. Time-based mechanics —
accrual, billing, statements, delinquency — are scheduled, idempotent, replayable financial
processes that remain correct with N instances, crashes, re-runs and clock skew.

**Phase 11 moves money.** Unlike Phase 10, every loan and every draw is funded from the platform's
**lending capital** (L1), credited to the borrower's wallet, and — when the borrower chose it —
paid out to their external bank account through payments' existing withdrawal machinery (L2);
repayments move wallet money into the loan's receivables. Every lending money effect is one
journal entry through `PostingService` or `ReversalService` in lending's own transaction (the
ADR-0043 shape): lending holds no balance of its own.

### 1.1 The production reality: origination only against the simulators (L11)

As built, production composes only `UnconfiguredBureau` and `UnconfiguredFinancialData`
(`CreditSourceOrder` refuses a named provider at startup until unresolved questions #13 and #14 are
answered); every production request reaches the policy's fallback (`REFER`), and since the Phase 10
→ 11 transition a person may not approve while the bureau's total balance is absent
(`422 credit.ExposureUnassessable`, R11). **No production credit decision can be `APPROVED`, so no
production loan or credit line can be originated in Phase 11.** The phase plans around this
deliberately rather than weakening credit:

1. **Origination is built and proven end to end against the simulators** (`bureau-sim-a`,
   `bureau-sim-b`, `findata-sim-a`) in database tests, the storm (`P11-TST-001`) and the `local`
   profile — the evidence standard Phase 10 met. No test-only "seeded approval" exists in
   production code; an approval comes only from credit's real deciding transaction over simulated
   sources.
2. **Production ships closed, explicitly.** A product is *offered* only while an `ACTIVE` lending
   terms version exists for it — four-eyes activated, never migration-activated (the credit
   policy's precedent, `OPERATIONS_RUNBOOK.md` §6.1). Production activates none; the application
   door answers `422 lending.ProductNotOffered`. The runbook's new §7 (`P11-TSK-030`) states what
   must be true before the first activation: #13 and #14 answered, a real bureau adapter
   contract-tested, a credit policy version reading it, legal review of the terms (A1), and
   lending capital recognised (§12.9).
3. **Servicing is independent of origination's source.** Every servicing path is exercised on
   loans and lines born through the real origination chain in tests; nothing in servicing reads
   credit.
4. **The tests carry the evidence.** Every exit criterion is about correctness, not production
   volume; each is proven by the test tiers and the storm. The gate review records the production
   constraint as a known, accepted limitation, not a deviation from a criterion.
5. **A debt row** (owner: the phase that answers #13/#14 — Phase 15 at the latest): "Lending
   cannot originate in production until a real bureau exists; the terms activation is the gate."

---

## 2. Why this phase is shaped by decisions already taken

### 2.1 What Phase 11 reuses (never re-implements)

| Need | Existing abstraction | How lending uses it |
|---|---|---|
| Posting | `ledger.PostingService.post(Connection, PostingCommand)`; `lockBalancesInOrder` | Every lending money effect is one `post` in lending's transaction; multi-entry transactions pre-lock the union first (the Phase 9 rule) |
| Reversal | `ledger.ReversalService.reverse`, `ReversalBound` (`INV-REV-02`, namespace 2) | Repayment reversal; fee waiver |
| Manual correction | `ledger.AdjustmentService` | **Never** on a loan account: every lending purpose joins `AccountPurpose.closedToFreeAdjustments()` |
| Accounts | `AccountPurpose` + `OwnerKind`, `LedgerAccountStore.createOrConverge` / `lockForUpdate`, `ChartOfAccounts.resolve` | New `OwnerKind.LOAN`; six per-loan purposes, three operational income/expense purposes and **`LENDING_CAPITAL`** (§12.8, §12.9) |
| Balances | `BalanceDerivation.derive`, `AvailableBalance.underLock`; the projection display-only (`INV-BAL-05`) | Every decision (allocation, accrual base, payoff, available limit, capital headroom, exposure) derives from journal lines under the account's lock |
| Holds | `ledger.HoldService` | The external payout's funds held on the wallet from the disbursement's commit until payments adopts the hold (§12.4) |
| Wallet | `accounts.WalletAccounts`, `AccountOpening` | Disbursement and draw destination; repayment source — through `app`'s `LoanWallets` port |
| Withdrawal | `payments.Withdrawals` (hold-then-dispatch, routing, `UNKNOWN` held, inquiry-only outcomes per ADR-0083, returns), `WithdrawalResolution`, the expectation opener (ADR-0067) | The external payout (`P11-TSK-015`): a system-initiated withdrawal on the borrower's recorded instruction, adopting lending's hold (a payments change, payments `V032`) |
| Bank recognition | settlement/reconciliation's bank statement recognition — the one poster of `CASH_AT_BANK` (`INV-SET-06`) | Lending capital is recognised only from a bank statement line matched to a four-eyes contribution expectation (`P11-TSK-004`) |
| Money | `Money`, `RoundingPolicy`, `CurrencyCode` | Exact arithmetic; `BigDecimal` only inside the engines, rounded once at declared points (`NoFloatingPointMoneyRulesTest` extended to `lending`) |
| Idempotency | `IdempotentExecutor`, `RequestFingerprint` | Every keyed door; internal steps born-once and conditional |
| Events | `OutboxWriter` (the platform envelope, schema v1) | Every lending event in the state change's transaction |
| Credit read | `credit.CreditDecisions<T>` | The offer's basis, read authoritatively in lending's transaction (through `app`) |
| Credit submission | `credit.DecisionRequests.submit` (MFA-assured since the transition, R14) | Called by `app`'s adapter inside the application's transaction — one customer act opens both |
| Credit lock | `credit.CreditProfiles.lockForDecision` — Phase 10 lock element (1) | Taken first, through the new credit-owned port, by every lending transaction that raises a party's exposure or moves it from reserved to committed to outstanding (ADR-0088 as amended by R12) |
| Exposure seam | `credit.PlatformCreditExposure<T>` (v1 `NoLoansUntilPhase11`, zero) | Replaced by `app`'s `LendingCreditExposure`, **version 2** (`P11-TSK-013`) |
| Consumption table | `credit.credit_decision_consumption` (V011, `UNIQUE (decision_id)`, append-only) | Written **only** by credit's new consumption port (`P11-TSK-001`); the application role's direct `INSERT` revoked |
| Scheduling | per-feature `SmartLifecycle`, one-statement `FOR UPDATE SKIP LOCKED` claim with a database-stamped permit | Four lending schedules (`LEASE_PROTECTED_SCHEDULERS` twenty-two → twenty-six) |

**Not reusable, and why.** `transfers.TransferExecution` posts wallet to wallet only, so it cannot
credit a receivable — a repayment is a lending-owned posting. `payments.OutboundCreditComposition`
has exactly one implementer, bound to crossborder; lending does **not** become a second implementer
— that would make payments call lending (its outcome appliers drive the composition) while lending
calls payments, a cycle (§7.3). The external payout composes the other way: lending *reads*
payments' outcome; payments never calls lending.

### 2.2 The transition's findings, designed around

- **R13 — the consumption write had no owner** → `P11-TSK-001`, the phase's first task: a
  credit-owned command port `CreditDecisionConsumptions.consume(uow, decision, consumerRef)` under
  the party's `credit_profile` row `FOR UPDATE`, refusing a non-`APPROVED`, lapsed or consumed
  decision, idempotent for the same consumer, behind a database guard (a `SECURITY DEFINER`
  function the only path to the table; the application role's `INSERT` revoked), so lending never
  writes credit's tables (`BOUNDED_CONTEXTS.md` corrected). §12.10.
- **R12 — exposure blind to outstanding credit** → repaired in Phase 10 (reserved and outstanding
  re-read together under the profile lock in the deciding transaction; a successor snapshot
  replaces both). Phase 11 supplies the outstanding figure: `PlatformCreditExposure` version 2,
  one SQL statement, and every lending writer that raises a party's exposure holds the profile lock
  first (`P11-TSK-013`). §12.10.
- **R11 — no approval on an absent bureau balance** → the production constraint of §1.1.
- **R14 — MFA enforced on credit submission** → every lending act that opens credit or creates an
  obligation requires a `MULTI_FACTOR` session (A19).
- **R15 — universal criterion 7** → the owner re-affirmed skipping the fleet-wide `databaseTest`
  and `kafkaTest` tiers for Phase 11; asked again at the Phase 11 → 12 transition.

### 2.3 Owner decisions (2026-10-10, binding)

| # | Decision | Where it lands |
|---|---|---|
| **L1** | **Funding.** Loans and draws are funded from a platform **lending-capital** ledger account (`LENDING_CAPITAL`, platform-owned, a new purpose), recognised only from bank evidence, never over-deployed — so loan-funded wallet money is platform-funded and the safeguarding position stays exact | §12.9, ADR-0096, `P11-TSK-003`, `-004`, `INV-LND-13` |
| **L2** | **Disbursement** to the borrower's wallet **and** to an external bank account, the external leg through payments' withdrawal machinery with its ambiguity, return and reconciliation; no lock-order cycle | §12.4, ADR-0097, `P11-TSK-014`, `-015` |
| **L3** | **Products:** `PERSONAL_LOAN` and the revolving `CREDIT_LINE`; refinance, write-off and collections deferred with named owners (§17) | §12.6, ADR-0100, `P11-TSK-021`…`-023` |
| **L4** | A **neutral EUR reference jurisdiction**, no consumer-credit-law features (no statutory APR formula, withdrawal right, statutory notices or caps) | §12.1, A1 |
| **L5** | **ACT/365F simple daily interest**, rounded once per period | §12.3, ADR-0094 |
| **L6** | **Allocation** oldest-due first; fees → interest → principal; any excess held as a credit balance | §12.5, ADR-0095 |
| **L7** | **Default** at 90 days past due | §12.7, ADR-0098 |
| **L8** | **No** penalty interest, **no** prepayment fee, **no** APR display | §12.1, §12.3 |
| **L9** | **Migrations:** credit's run to `V020` (`V018` unused) — Phase 11's credit changes start at **credit `V021`**; `lending` owns its schema, Flyway `lending/V001…` | §8 |
| **L10** | **`P11-TSK-001` is the credit-owned consumption port** (R13), an entry condition for any loan | §12.10, §19 |
| **L11** | **Origination only against the simulators** until a real bureau is connected (unresolved #13/#14) | §1.1 |
| **L12** | **The ten mandatory test scenarios** (§13.2) — each a named test, in the storm and in a task | §13 |

Every convention L4–L8 is a field of the versioned terms row and is pinned on the agreement, so a
jurisdiction's rules become configuration plus review, never code archaeology.

### 2.4 Recorded assumptions (the draft's recommended defaults, kept)

| # | Assumption | Default |
|---|---|---|
| A1 | Jurisdiction | Neutral EUR reference product; legal review a precondition of production activation (§1.1) |
| A2 | Servicing zone (what "a day" is) | `UTC`, a terms field |
| A3 | Exposure measure | Principal only: committed + outstanding principal of loans; the **limit** of an open credit line (§12.10) |
| A4 | Interest model | Fixed nominal rate; simple daily interest on principal outstanding; no compounding (interest never accrues on interest or fees) |
| A5 | Cost disclosure | The offer shows the illustrative schedule's total interest and total payable (loan), or the rate and the minimum-payment rule (line); no APR (L8) |
| A6 | Business-day adjustment of due dates | None — calendar days; the end-of-month clamp from the intended day |
| A7 | A person releasing a disbursement above a threshold | Off — credit already applies four-eyes above its threshold |
| A8 | Default cure | Cleared when the account is current again; no probation |
| A9 | Servicing holds (legal, dispute, insolvency, deceased) | Not built; the condition slot designed |
| A10 | Back-valued postings | None — every lending posting's value date is the business date of the database clock read under the account lock; the interest gap after a reversed repayment is not recharged |
| A11 | External inbound repayment (bank transfer to a loan reference, direct debit) | Deferred; money arrives through the existing wallet top-ups and is repaid from the wallet |
| A12 | A repayment that closed a loan | Not reversible — terminal is terminal (`INV-LIFE-04`) |
| A13 | Acceptance evidence | Stores the session, the assurance level, client IP and user agent as `CONFIDENTIAL` |
| A14 | Fee-waiver four-eyes threshold | Always four-eyes |
| A15 | Long horizons in tests | Fixed-offset `servicing_zone` per storm cohort; owner-role fixtures for long histories; pure engines for decades — no production clock seam |
| A16 | `lending` → `credit` build edge | None — `app` ports (the crossborder precedent) |
| A17 | Origination fee | Default 0; when set, `DEDUCTED` from the disbursement; recognised upfront in the operational ledger, effective-interest amortisation Phase 14's |
| A18 | Late fee | Configured per terms version; tests configure one; production none until activation |
| A19 | MFA | A `MULTI_FACTOR` session for the application, the acceptance, a draw, a payout destination and a payoff (R14's reasoning); repayment and quotes follow the platform's conditional step-up |
| A20 | Offer amount | Exactly the approved amount and term (loan) or limit (line); no counter-offer |
| A21 | Auto-collection | On; partial allowed; retried daily while past due |
| A22 | Day count | `ACT_365F` only; another convention is a new engine version |
| A23 | Credit line statement | Monthly on the agreement's statement day (1–28); payment due 25 days after the statement date |
| A24 | Credit line minimum payment | Interest billed + fees billed + a principal part of `max(round_UP(3 % of the drawn principal), EUR 25.00 − interest − fees, 0)`, capped at the drawn principal not yet due — so the whole minimum payment is at least EUR 25.00, or the balance if smaller |
| A25 | Credit line draws | To the borrower's wallet only (an external leg is the borrower's own withdrawal); suspended while any amount is past due or the line is defaulted |
| A26 | Credit line overpayment | Beyond what is due, a payment pays down drawn principal (restoring the limit); only what exceeds every receivable is held as a credit balance |
| A27 | Credit line term | No expiry in Phase 11; periodic limit review deferred |
| A28 | Capital and undrawn limits | Lending capital is consumed by committed and drawn principal, never by an undrawn limit — a draw can be refused for capital (`422 lending.CapitalUnavailable`) |
| A29 | Income recycling | Interest and fee income is never counted as lending capital in Phase 11 |
| A30 | External payout interest start | Interest on a loan paid out externally starts at the payout's terminal outcome (§12.4) — the platform, not the borrower, bears provider ambiguity |

---

## 3. Bounded contexts and modules

### 3.1 Ownership (owner brief 9.1)

| Concept | Owner | Phase 11 | Relationships, lifecycle |
|---|---|---|---|
| Credit Application (the decision request envelope) | `credit` (`DecisionRequest`) | exists | Lending's application *opens* one through `DecisionRequests.submit`; never lending's |
| Credit Assessment, Score, Policy Evaluation | `credit` | exists | Lending never reads a score, attribute or threshold |
| Credit Decision, Underwriting | `credit` | exists | Read through `CreditDecisions`; lending stores only the decision id |
| Decision Consumption | `credit` — its **only writer** is credit's port | `-001` | Lending's acceptance calls the port; it never writes credit's table |
| Exposure | `credit` | `-013` | Lending supplies facts (one statement over its rows and the ledger); credit judges |
| **Loan Application** | `lending` | yes | One per customer request for a product; references exactly one credit decision request; `SUBMITTED → AWAITING_DECISION → OFFERED / DECLINED / CLOSED_UNDECIDED / WITHDRAWN` |
| **Loan Product Terms Version** | `lending` | yes | Versioned data per product: rate, fees, servicing parameters, allocation order, statement rules; four-eyes |
| **Loan Offer** | `lending` | yes | Terms the platform will grant, from an approved decision + the active terms version, expiring; `OFFERED → ACCEPTED / DECLINED / EXPIRED` |
| **Loan Agreement** (versioned) | `lending` | yes | The immutable contract — version 1 at acceptance, n+1 per accepted amendment or recalculation |
| **Acceptance Evidence** | `lending` | yes | What the customer saw and agreed to, and how they were authenticated |
| **Loan** (the loan account) | `lending` | yes | Identity, product kind (`INSTALMENT` or `REVOLVING`), lifecycle state, permits — **no amount, rate or balance column** |
| **Repayment Schedule** (versioned) + **Loan Instalment** | `lending` | yes (loan) | Projection generated from an agreement version by a schedule engine version |
| **Instalment Billing** | `lending` | yes (loan) | Born once per instalment at its due date — what became due |
| **Credit Line Draw** | `lending` | yes (line) | Born once per draw — principal taken against the available limit |
| **Credit Line Statement** | `lending` | yes (line) | Born once per (line, cycle end) — what became due, the minimum payment and its due date |
| **Interest Accrual** | `lending` | yes | Born once per (account, date) |
| **Fee Assessment** | `lending` | yes | Origination (as a term) and late fee; born once per (instalment or statement, kind) |
| **Disbursement** | `lending` | yes | Born once per loan; to the wallet, then (path X) an external payout |
| **Loan Payout** | `lending` | yes (path X) | Born once per loan whose borrower chose an external account; waits for payments' outcome |
| **Repayment** + **Repayment Allocation** | `lending` | yes | Money received; its split born with it |
| **Collection Attempt** | `lending` | yes | Born once per (due item, attempt date) |
| **Waiver** / **Repayment Reversal** | `lending` (the act), `ledger` (the entry) | yes | Four-eyes |
| **Delinquency** (DPD, bucket, cure) | `lending` | yes | A derived **condition**, recorded as append-only changes |
| **Default** | `lending` | yes, a flag | No acceleration, no write-off posting |
| **Early Repayment — Payoff** | `lending` | yes | Quote + execution, both products |
| **Early Repayment — partial Prepayment** | `lending` | cut candidate (`-028`) | A repayment kind with recalculation |
| **Restructuring** (amendment) | `lending` | yes (`-027`) — scenario 8 | Agreement version n+1, four-eyes plus the customer's acceptance |
| **Lending Capital** | `lending` (the administration), `ledger` (the account) | yes (`-004`) | Contributions four-eyes, recognised from bank evidence |
| Refinance | `lending` + `credit` | **deferred** — owner: the Phase 11 → 12 transition decides the later lending phase that takes it | A new decision and a two-account atomic payoff |
| Write-off / charge-off, provisioning, IFRS 9 staging | `lending` (event), `accounting` (GL) | **deferred** — Phase 14 | The account plan seats `LOAN_WRITE_OFF_EXPENSE` now (§12.8) |
| Collections case and operations | a future collections owner | **deferred** — Phase 13 (case management) | Consumes lending's `LoanDelinquencyChanged` / `LoanDefaulted` |
| GL mapping, period close | `accounting` | Phase 14 | Operational ledger only |

**Never one row of principal, rate and balance** (owner brief 9.1): the `loan` row holds identity,
kind, status and permits; the contractual principal, limit and rate live on the immutable agreement
version; every changing amount is a ledger balance; every operational amount (billed, drawn,
allocated, accrued, assessed) is an immutable born-once row whose sum is proven equal to the ledger
(§13.3). There is no mutable money column anywhere in the `lending` schema
(`LendingSchemaHasNoMutableMoneyTest`: every `*_minor` column on an `INSERT`-only table).

### 3.2 Module, edges and ports

- **New module `lending`**, schema `lending`, Flyway `lending/V001…`.
- **Build edges:** `lending → ledger, platform, sharedkernel` only (the `crossborder` precedent);
  `LendingModuleIsolationTest` pins it with planted probes. No module depends on `lending`.
- **Ports declared by `lending`, implemented in `app`:**
  - `LoanCreditDecisions` — `open(uow, party, terms, actor, correlation)` over
    `DecisionRequests.submit`; `cancel(…)`; `decisionFor(uow, decisionRequestId)` over
    `CreditDecisions`; `consume(uow, decisionId, consumerRef)` over `CreditDecisionConsumptions`;
    `lockExposure(uow, party)` over the profile lock (exposed alone so every exposure-raising
    lending transaction takes element (1) first).
  - `LoanPartyStanding` — over `CreditPartyStanding` / `AccountHolderVerification`.
  - `LoanWallets` — resolve, open if absent, the borrower's `CUSTOMER_WALLET` in the currency.
  - `LoanPayouts` — `dispatch(key, instruction, amount, adoptHold)` over payments' system-actor
    withdrawal entry; `outcome(withdrawalReference)` — a **read** over payments' withdrawal store.
  - `LoanPayoutDestinations` — verify the borrower's own payment method at acceptance (the
    ADR-0056 shape), returning an opaque reference (`INV-RAIL-03`: no account identifier enters
    lending).
  - `LendingObserver` (meters), `TransactionRunner`.
- **Credit publishes** `CreditDecisionConsumptions` (new, `-001`); `app` gains
  `LendingCreditExposure` (v2, `-013`). `credit` keeps its edges (`platform`, `sharedkernel`).
- **Payments publishes** a system-actor withdrawal entry admitting a recorded instruction and a
  hold to adopt (`-015`, payments `V032`); `merchant`, `crossborder` and the person's withdrawal
  door unchanged.
- **Reconciliation** gains the `LENDING_CAPITAL_CONTRIBUTION` expectation kind and its recognition
  line (`-004`, reconciliation `V022`); bank recognition stays the one poster of `CASH_AT_BANK`.

---

## 4. Aggregates and commands

| Aggregate | Commands (actor) | Born-once facts it owns |
|---|---|---|
| `LoanTermsVersion` | propose (P), approve (P, ≠ proposer), reject (P) | the version, its event history |
| `LendingCapitalContribution` | propose (P), approve (P, ≠ proposer) — opens a bank expectation; recognised (S, bank recognition) | the contribution, its recognition |
| `LoanApplication` | submit (C), withdraw (C), offer / decline / close (S) | the application, its event history |
| `LoanOffer` | accept (C), decline (C), expire (S) | the offer, its canonical terms + hash |
| `Loan` (both kinds) | disburse (S), service a date (S), repay (C), auto-collect (S), draw (C, line), close (S), request closure (C, line) | agreement versions, acceptance evidence, schedule versions, instalments, billings, statements, draws, accruals, fee assessments, repayments and allocations, collection attempts, condition changes, payout, payoff quotes and executions |
| `Amendment` | propose (P), approve (P, ≠ proposer), reject (P), accept (C) | the amendment, its acceptance evidence |
| `Waiver`, `RepaymentReversal` | propose (P), approve (P, ≠ proposer), reject (P) | the proposal and its decision |

C = the customer (keyed, MFA per A19), P = a person (operator permission; four-eyes where marked),
S = the system (a sweep or an in-transaction step).

---

## 5. The lifecycles (owner brief 9.2)

Lifecycle state is kept apart from conditions. Every machine is held at three ranks, as Phase 10's
are: the domain's conditional from-set under the row lock, a hand-written every-writer trigger
(`*_permits_only_machine_edges`), and a status `CHECK`. The full machines, with every invalid
transition, are in [`LENDING_LIFECYCLES.md`](../domain/LENDING_LIFECYCLES.md).

### 5.1 Loan Application

```
SUBMITTED ──(credit request opened, same tx)──▶ AWAITING_DECISION ──(decision APPROVED)──▶ OFFERED
                                                 ├──(decision DECLINED)──▶ DECLINED
                                                 ├──(credit request CANCELLED/EXPIRED/ABANDONED)──▶ CLOSED_UNDECIDED
                                                 └──(customer withdraws while credit's request is cancellable)──▶ WITHDRAWN
```

`SUBMITTED` is transient (kept so the history records submission). A decline is never
reconsidered (a new application). Withdrawal after credit's evaluation is `409
lending.ApplicationNotWithdrawable` — the decision will arrive and the customer may decline the offer.

### 5.2 Loan Offer

`OFFERED → ACCEPTED` (C; the decision consumed, agreement v1, the loan account born) ·
`OFFERED → DECLINED` (C) · `OFFERED → EXPIRED` (S, `expires_at <= statement_timestamp()`).
`expires_at = LEAST(decision.valid_until, offered_at + terms.offer_validity)` (default 14 days),
stamped on the database clock. Acceptance is conditional on `expires_at > statement_timestamp()`,
expiry on `<=` — exactly one at the boundary. An expired or declined offer releases nothing: the
unconsumed decision lapses at its own `valid_until`. Never `EXPIRED → ACCEPTED`.

### 5.3 Loan (both kinds) — the lifecycle

```
INSTALMENT:  PENDING_DISBURSEMENT ──(disbursement credited to the wallet)──▶ ACTIVE ──(every receivable zero, credit balance refunded)──▶ CLOSED
                  └──(standing lost / wallet not postable past the deadline)──▶ CANCELLED
REVOLVING:   (born at acceptance) ACTIVE ──(customer requests closure)──▶ CLOSING ──(every receivable zero)──▶ CLOSED
                                    └──(nothing drawn, closure requested)──────────────────────────────▶ CLOSED
```

Terminal: `CLOSED` (reason `REPAID` | `PAID_OFF_EARLY` | `CLOSED_BY_CUSTOMER`), `CANCELLED`
(reason `STANDING_LOST` | `DISBURSEMENT_FAILED`). **A restructuring is not a state** — it is
agreement version n+1 on an `ACTIVE` loan. **Delinquency, default, draws suspended, awaiting payout
and the future servicing holds are not states** — they are conditions (§5.5); an `ACTIVE` loan is
`ACTIVE` whether current or 120 days past due.

| Edge | Actor | Consent / authority |
|---|---|---|
| (birth) → `PENDING_DISBURSEMENT` / `ACTIVE` (line) | C | the acceptance (MFA, acceptance evidence) |
| `PENDING_DISBURSEMENT → ACTIVE` | S | the acceptance is the instruction; no further act (A7) |
| `PENDING_DISBURSEMENT → CANCELLED` | S | standing lost, or the wallet not postable past `disbursement_deadline` (24 h) |
| `ACTIVE → CLOSING` (line) | C | the customer's closure request (MFA) |
| `ACTIVE → CLOSED` / `CLOSING → CLOSED` | S | the transaction that zeroes the last receivable — a repayment, a payoff, a credit-balance application or a waiver; closure is a consequence, never a command |
| Invalid | — | anything out of `CLOSED` or `CANCELLED` (`INV-LIFE-04`); `ACTIVE → PENDING_DISBURSEMENT`; `PENDING_DISBURSEMENT → CLOSED`; `ACTIVE → CANCELLED` (after disbursement a loan ends only by being repaid, or — deferred — written off); `CLOSING → ACTIVE` (a closing line is never reopened — a new application) |

### 5.4 Other machines

- **Disbursement** — `PENDING → POSTED | FAILED` (born once per loan).
- **Loan Payout** (path X) — `PENDING → DISPATCHING → DISPATCHED → PAID_OUT | FAILED | RETURNED`;
  `DISPATCHING → NOT_DISPATCHED` (payments refuses or cannot route: the hold released, funds free in
  the wallet — the refusal is met after the claim, so in `DISPATCHING`). Lending only *observes*
  payments' outcome; `UNKNOWN` at payments is `DISPATCHED` here until payments concludes from an
  authenticated inquiry. Payments' withdrawal machine has no `RETURNED` state — `-015` maps
  `RETURNED` from payments' return record of a `COMPLETED` withdrawal.
- **Repayment** — born `ALLOCATED` (a row exists only once its money moved); the row is
  `INSERT`-only, so `REVERSED` is derived from its approved reversal, never written onto it (P,
  four-eyes). A failed collection is a `collection_attempt`, never a repayment.
- **Waiver, repayment reversal, amendment proposals** — `PROPOSED → APPROVED (posted) | REJECTED`;
  an amendment never raises principal outstanding or a line's limit — more credit is a new
  application and a new decision;
  an amendment `APPROVED → ACCEPTED | LAPSED` (customer declines, its offer expires, or the agreement
  version moved). One live reversal proposal per repayment (partial `UNIQUE (repayment_id) WHERE
  status IN ('PROPOSED','APPROVED')`), so a rejected proposal can be proposed again.
- **Terms version** and **capital contribution** — `PROPOSED → ACTIVE → RETIRED` /
  `PROPOSED → REJECTED` (terms; one `PROPOSED` and one `ACTIVE` per product); `PROPOSED → APPROVED`
  / `PROPOSED → REJECTED` (contribution). **Recognition is derived, never a lending-written edge**:
  reconciliation never calls lending, so lending reads the contribution's expectation and its
  recognised ledger line through `app`'s read port — *recognised* when the line exists, *lapsed*
  when the expectation aged out unmatched. Headroom reads the `LENDING_CAPITAL` balance, never the
  contribution's status.
- **Payoff quote** — immutable; its execution a separate born-once fact (`UNIQUE (quote_id)`).

### 5.5 Conditions (orthogonal; append-only histories)

| Condition | Values | Set / cleared by | Effect |
|---|---|---|---|
| Delinquency | `CURRENT`; `PAST_DUE` with DPD and bucket (`B1_1_29`, `B2_30_59`, `B3_60_89`, `B4_90_PLUS` — the terms version's bounds) | S, daily, derived (§12.7) | late-fee eligibility; draws suspended (line); events |
| Default | `DEFAULTED` flag | S at DPD ≥ 90 (L7); S at cure (A8) | event; draws suspended (line); no acceleration, no posting |
| Draws suspended (line) | derived | S | a draw refused `409 lending.DrawsSuspended` |
| Awaiting payout (loan, path X) | derived from the payout | S | no schedule, no accrual until the payout's terminal outcome (A30) |
| Servicing hold | *designed, not built* (A9) | P | would suspend late fees and auto-collection |

---

## 6. Financial invariants Phase 11 must preserve

**New** (`INV-LND-01`…`14`, catalogued by this transition): 01 a loan's figures are ledger balances
· 02 interest accrues once per account per date · 03 a schedule conserves principal exactly · 04
allocation conserves and never over-pays a component · 05 terms are pinned and immutable · 06 one
decision funds at most one loan or line, and exposure has no gap · 07 a loan is disbursed at most
once, its receivable born exactly with the wallet credit, and no payout outcome creates, duplicates
or undoes it · 08 closed is closed, and closed means settled · 09 interest arithmetic is exact and
its conventions declared · 10 servicing time is the database's · 11 a servicing correction is a
reasoned, four-eyes act that posts · 12 delinquency is derived and its history append-only · 13
lending capital is recognised only from bank evidence and never over-deployed · 14 a draw never
exceeds the available limit. The catalogue moves from **128 to 142**.

**Restated for Phase 11:** `INV-MON-01`…`06`, `INV-LED-01`…`05`, `INV-BAL-01`…`05`, `INV-HIST-01`,
`-04`, `INV-IDEM-01`…`04`, `INV-CON-01`…`02`, `INV-LIFE-01`…`04`, `INV-REV-01`…`04`, `INV-EVT-01`…`04`,
`INV-AUD-01`…`04`, `INV-ACC-01`, `INV-CRD-02`, `-09`; for the external payout `INV-RAIL-02`, `-03`,
`INV-PAY-01`, `-04`, `INV-SET-02`; for capital recognition `INV-SET-06`, `INV-REC-06`.

---

## 7. Multi-instance architecture (owner brief 9.10)

Every instance runs every door, sweep and consumer. Correctness rests on PostgreSQL alone; no
`synchronized`, no JVM lock, no in-memory authoritative state, no process-local idempotency; every
window is judged on `statement_timestamp()` read **after** the relevant row lock; no connection is
held across a provider call (ADR-0046).

### 7.1 Transactions (each one commit)

| Tx | Steps |
|---|---|
| **T-app** application | claim `lending.application:CUSTOMER:<id>`; standing; active terms `FOR SHARE`; `DecisionRequests.submit` (credit's arbiters); application `AWAITING_DECISION`; events |
| **T-off** offer | origination sweep claim; (L1) application `FOR UPDATE` conditional `AWAITING_DECISION`; the decision read; the terms version `ACTIVE` at this instant read `FOR SHARE` and pinned (the version recorded on the application is informational); a non-positive principal portion closes the application `CLOSED_UNDECIDED (TERMS_NOT_AMORTISING)` with no offer; else the offer born (`UNIQUE (application_id)`); events |
| **T-acc** acceptance | claim `lending.offer-acceptance:CUSTOMER:<id>`; **(L0) profile `FOR UPDATE`**; (L2) offer `FOR UPDATE` conditional `OFFERED`, unexpired; standing; `consume`; **(L6) capital row `FOR UPDATE`** and headroom ≥ principal (loan only); loan born (`PENDING_DISBURSEMENT` or line `ACTIVE`), agreement v1, evidence, disbursement `PENDING` (loan), payout instruction (path X); the six ledger accounts opened; offer `ACCEPTED`; events; audit |
| **T-dis** disbursement | sweep claim; (L0) profile; (L3) loan `FOR UPDATE` conditional `PENDING_DISBURSEMENT`; (L5) disbursement `PENDING`; standing re-read; wallet resolved; path W: schedule v1, accrual start; path X: (L7) wallet `FOR UPDATE` and a hold of P; posting rule 1; loan `ACTIVE`; events; audit |
| **T-pyo** payout (path X) | (a) claim the payout `PENDING → DISPATCHING` (permit) and commit; (b) `LoanPayouts.dispatch` — payments' own Tx1 / wire / Tx2, keyed `payments.withdrawal:lending:<loanId>`, adopting the hold; (c) (L3) loan, (L5) payout → `DISPATCHED` with payments' reference. A crash anywhere re-drives under the same key (payments replays) |
| **T-pyr** payout outcome | sweep claim; (L3) loan; (L5) payout; payments' outcome **read** (plain, through `app`); a terminal outcome recorded, accrual start and schedule v1 set (A30) |
| **T-svc** servicing step (one account, one business date) | sweep claim; (L3) loan `FOR UPDATE`; database clock read; accrue each elapsed date; bill instalments / produce statements due; auto-collect ((L7) wallet, `AvailableBalance.underLock`); condition; late fee; events |
| **T-rep** repayment | claim `lending.repayment:CUSTOMER:<id>`; (L3) loan `ACTIVE`/`CLOSING`; (L7) wallet, available ≥ amount; allocate; `lockBalancesInOrder` if more than one entry; post; maybe close |
| **T-drw** draw (line) | claim `lending.draw:CUSTOMER:<id>`; (L3) line `FOR UPDATE` `ACTIVE`, draws not suspended; (L5) draw born; (L6) capital headroom ≥ amount; available limit ≥ amount (derived under L3); post; events |
| **T-pay** payoff | claim; (L3) loan; (L5) quote; catch-up accrual and billing; recompute; repayment, refund; `CLOSED` |
| **T-rev / T-wvr / T-amd** corrections | claim `lending.servicing:EMPLOYEE:<id>`; (L0) profile for a repayment reversal (outstanding rises); (L3) loan; (L5) the proposal `FOR UPDATE` conditional; for a reversal that re-instates principal, (L6) the capital row and headroom ≥ the principal re-instated, else `422 lending.CapitalUnavailable` and the proposal stays `PROPOSED`; post; audit |
| **T-cap** capital contribution | claim `lending.capital:EMPLOYEE:<id>`; advisory namespace `11` (`hashtext(currency)`); (L5) the proposal; the expectation opened (reconciliation's opener, in the same transaction); recognition later in reconciliation's own bank-recognition transaction |

### 7.2 The lending lock order (a planned `DISTRIBUTED_EXECUTION.md` §3 row)

One order for every lending writer holding more than one of them:

**(L0)** the party's `credit.credit_profile` row `FOR UPDATE` — through credit's port, only in
transactions that raise the party's exposure or move it between reserved, committed and
outstanding (acceptance, disbursement, repayment reversal), and then always first;
**(L1)** `loan_application`; **(L2)** `loan_offer`; **(L3)** `loan` — the serialisation point of
every servicing act on one account, both kinds; **(L4)** the account's instalment, billing or
statement rows by due date (only where a step must lock them; reads under L3 normally suffice);
**(L5)** the act's own row (disbursement, payout, draw, payoff quote, amendment, waiver, reversal or
capital proposal); **(L6)** the `LENDING_CAPITAL` ledger account row of the currency `FOR UPDATE`
(acceptance of a loan, a draw, a repayment reversal that re-instates principal); **(L7)** the borrower's `CUSTOMER_WALLET` `ledger_account` row
`FOR UPDATE` (funds checks and the payout hold); **(L8)** ledger projection rows via
`lockBalancesInOrder` — postings last. Then only inserts (rows, events, audit, outbox).

### 7.3 Why there is no cycle

(a) Credit's deciding transaction takes the profile, then credit rows, then **reads** lending's
facts in one plain statement — it never waits on a lending lock; lending takes the profile only
first. (b) L7 and L8 are the Phase 9 order's wallet and projection elements: every wallet writer in
the platform takes wallet rows after its subject rows, and no writer that holds a wallet row ever
waits on a lending row (no payments, transfer or fx transaction touches lending). (c) L6, the
capital row, is locked by lending alone, always before any wallet row. (d) The external payout:
lending's transactions never hold a lending lock while waiting on a payments row — T-pyo commits its
claim before calling payments, and payments' withdrawal runs its own transactions (its order:
withdrawal row, then the wallet, then projections; it adopts a hold row lending created and nobody
else locks); T-pyr *reads* payments' row with no lock. Payments' outcome appliers never touch
lending — the payout's money outcome is the wallet's, not the loan's. Had lending implemented
`OutboundCreditComposition`, payments' appliers would call lending (payments → lending) while
lending's disbursement called payments (lending → payments): a cycle; that is why §2.1 refused it.
(e) Credit consumption takes L0 inside the acceptance — the only place a lending transaction holds a
credit lock — and credit never locks a lending row. (f) Reconciliation's bank recognition of a
capital contribution posts to `LENDING_CAPITAL` under its own order (namespace 4, its rows, then the
projections); its journal line's foreign-key share on the capital account's row may wait on a
lending transaction holding L6, but no lending transaction ever waits on anything the recognition
holds (reconciliation's rows, `CASH_AT_BANK`) — a one-way wait, never a cycle.

### 7.4 Contention table

| Contention | Arbiter | Loser's answer | Proven by |
|---|---|---|---|
| Ten application submissions, one key | `IdempotentExecutor` claim | the same response; `409` in progress | `-010` |
| Two keys, one party and product | credit's partial unique (one open request) | `409 lending.ApplicationOpen` | `-010` |
| Ten origination sweepers, one application | `FOR UPDATE SKIP LOCKED`; conditional `AWAITING_DECISION → OFFERED`; `UNIQUE (application_id)` | nothing written | `-011` |
| `CreditDecisionRecorded` twice / late / never | the event is a hint; the sweep reads `CreditDecisions` | converges within one sweep interval | `-011` |
| Ten acceptances, one key or several | claim; offer conditional `OFFERED`; consumption's uniques | one account; `409 lending.OfferNotAcceptable` | `-012` |
| Acceptance vs offer expiry at the boundary | complementary conditionals on the database clock | exactly one of `ACCEPTED`, `EXPIRED` | `-012` |
| Acceptance vs a credit decision for the same party | L0 in both | serialised; the decision sees the reservation or the commitment, never neither | `-013` |
| Acceptances racing for the last capital | L6 capital row; headroom derived under it | one admitted; `422 lending.CapitalUnavailable` | `-004`, `-012` |
| **Scenario 1** — the same disbursement on two instances | `UNIQUE (loan_id)` on disbursement + conditional `PENDING_DISBURSEMENT → ACTIVE` + ledger key `lending.disbursement:<loan>` | one entry | `-014` |
| Ten payout dispatchers | the payout's conditional claim + payments' key (replay) | one withdrawal | `-015` |
| **Scenario 5** — provider succeeded, response lost | payments' `UNKNOWN` held, inquiry-only outcome; lending reads, never re-dispatches | one payout; accrual from payments' conclusion | `-015` |
| **Scenarios 2 and 3** — the same repayment twice; two repayments on one instalment | claim (same key → replay); L3 (different keys → serialised, allocation re-derived) | one entry / conserved, never over-allocated | `-018` |
| Repayment vs auto-collection vs billing on the due date | L3; `collection_attempt UNIQUE (item, date)` | no double collection | `-019` |
| **Scenario 4** — accrual twice / ten sweepers / restart mid-run | `interest_accrual UNIQUE (loan_id, accrual_date)` + ledger key + L3 | no second accrual | `-016` |
| Accrual across midnight under ±5 s skew | the database clock read after L3 | a date accrued once and never early | `-016` |
| Duplicate schedule generation | `UNIQUE (loan_id, schedule_version)` | one schedule | `-014`, `-027` |
| Two draws racing for the available limit | L3 line lock; available derived under it | one admitted; `422 lending.LimitExceeded` | `-021` |
| Statement produced twice | `UNIQUE (loan_id, cycle_end)` + ledger key | one statement | `-022` |
| **Scenario 6** — a reversal after allocation | proposal row conditional; L0, L3; `ReversalService` bound | one reversal; allocations negated by rows, later allocations untouched | `-020` |
| **Scenario 7** — payoff while a repayment is processing | L3; the quote recomputed under it | `409 lending.PayoffQuoteStale`, never a wrong payoff | `-026` |
| **Scenario 8** — schedule changed under an approved amendment | L3; acceptance conditional on the agreement version | agreement and schedule v n+1 once; a moved agreement lapses it | `-027` |
| **Scenario 9** — crash after posting, before event publication | the outbox row in the posting's transaction | both or neither; the relay publishes after a restart | `-014`, `TST-001` |
| **Scenario 10** — payoff while the delinquency worker runs | L3; the condition step re-derives under it; terminal loans skipped | no condition written after closure | `-026` |
| Ten approvers of a waiver / reversal / amendment / terms / contribution | proposal row conditional `PROPOSED`; four-eyes `CHECK` | one decision | `-005`, `-004`, `-020`, `-025`, `-027` |
| Exposure read vs a disbursement committing | one-statement read + L0 on increases | never torn | `-013` |

### 7.5 Schedules (leaderless; each a `SmartLifecycle`, off in test contexts, its `…sweeper.enabled` gauge)

| Schedule | Claims | Permit |
|---|---|---|
| `LoanOriginationSchedule` | applications `AWAITING_DECISION`; offers past `expires_at` | `next_step_at`, re-stamped when nothing to do (the starvation lesson) |
| `LoanDisbursementSchedule` | disbursements `PENDING` | `next_attempt_at` |
| `LoanPayoutSchedule` | payouts `PENDING`, `DISPATCHING` past permit, `DISPATCHED` awaiting an outcome | `next_attempt_at`, rotated by `last_observed_at` |
| `LoanServicingSchedule` | accounts `ACTIVE`/`CLOSING` whose `next_servicing_at <= statement_timestamp()` | the start of the next business date in the pinned zone, or a retry cadence |

All claim in one `UPDATE … WHERE id IN (SELECT … ORDER BY permit, id LIMIT ? FOR UPDATE SKIP
LOCKED)` statement stamping the permit from `statement_timestamp()`, then act per account in its own
transaction under L3 with conditional steps. A duplicate tick, two instances, a crash after any
statement or a restart re-drives to the same rows. `NoSingleInstanceAssumptionRulesTest.
LEASE_PROTECTED_SCHEDULERS` twenty-two → twenty-six.

**Advisory namespace `11`:** lending's administrations — the terms proposals (`hashtext(product)`)
and the capital contributions (`hashtext(currency)`) — blocking; registered by `-005`, its first
writer, and extended by `-004`'s administration (the building order may swap them; whichever lands
first registers it).

**Isolation** is `READ COMMITTED` plus row locks, conditionals and uniques (ADR-0039). The proofs,
replay and reports run in one `REPEATABLE READ` read-only snapshot. **Recovery** is from rows: a crash
at any point leaves a state a sweep re-drives; every step is born-once or conditional.

---

## 8. Data architecture

Schema `lending`, owned by the module (Flyway `lending/V001…`), `REVOKE ALL FROM PUBLIC`, the
application role granted `SELECT, INSERT` on fact tables and conditional `UPDATE` only of status and
permit columns on the machine tables (`loan_application`, `loan_offer`, `loan`, `loan_disbursement`,
`loan_payout`, the proposal tables) — never `DELETE`, never `UPDATE` of a money column (there are
none mutable). Every money column is `*_minor bigint` beside a `currency` and the scale the currency
declares; every table carrying money is `INSERT`-only.

| Table (lending) | Born | Key arbiters |
|---|---|---|
| `loan_terms_version` (+ `_event`) | `-005` | one `PROPOSED`, one `ACTIVE` per product (partial uniques); immutable once proposed |
| `capital_contribution` (+ `_event`) | `-004` | four-eyes `CHECK`; `UNIQUE (expectation_ref)` |
| `loan_application` (+ `_event`) | `-010` | `UNIQUE (decision_request_id)` |
| `loan_offer` (+ `_event`) | `-011` | `UNIQUE (application_id)`; canonical terms + `terms_sha256` |
| `loan` (+ `loan_event`), `loan_agreement`, `loan_acceptance`, `loan_disbursement` | `-012` | `UNIQUE (offer_id)`; `UNIQUE (loan_id, version)`; `UNIQUE (loan_id)` |
| `repayment_schedule`, `loan_instalment`, `accrual_start` | `-014` | `UNIQUE (loan_id, schedule_version)`; `UNIQUE (schedule_id, sequence)`; `UNIQUE (loan_id)` |
| `loan_payout` | `-015` | `UNIQUE (loan_id)`; payments' reference |
| `interest_accrual` | `-016` | `UNIQUE (loan_id, accrual_date)` |
| `instalment_billing`, `credit_balance_application` | `-017` | `UNIQUE (instalment_id)`; `UNIQUE (billing_id)` |
| `repayment`, `repayment_allocation` | `-018` | a deferred trigger: Σ allocation = amount |
| `collection_attempt` | `-019` | `UNIQUE (due_item_id, attempt_date)` |
| `repayment_reversal`, `repayment_allocation_reversal` | `-020` | one live proposal per repayment (partial unique over `PROPOSED`/`APPROVED`); four-eyes `CHECK` |
| `credit_line_draw` | `-021` | `UNIQUE (loan_id, draw_key)` |
| `credit_line_statement` | `-022` | `UNIQUE (loan_id, cycle_end)` |
| `line_closure_request` | `-023` | `UNIQUE (loan_id)` |
| `loan_condition_event` | `-024` | `UNIQUE (loan_id, business_date, kind)` |
| `fee_assessment`, `waiver` | `-025` | `UNIQUE (due_item_id, kind)`; four-eyes `CHECK` |
| `payoff_quote`, `payoff_execution` | `-026` | `UNIQUE (quote_id)` |
| `loan_amendment` | `-027` | four-eyes `CHECK`; acceptance conditional on the agreement version |

**Other modules' migrations (the expected order; the building task may renumber within its module
and says so):** credit `V021` (`-001`: the consumption columns, the definer function, the `INSERT`
revoked); ledger `V026` (`-003`: `OwnerKind.LOAN`, the ten purposes, the operational accounts and
`LENDING_CAPITAL` seeded per supported currency, the adjustment guard extended); identity `V021`
(`-002`: permissions and roles); reconciliation `V022` (`-004`: the contribution expectation kind and
its recognition); payments `V032` (`-015`: the system-actor withdrawal on instruction, hold adoption);
lending's definer view for the exposure read (`-013`).

**Retention.** Agreement versions, acceptance evidence, servicing rows and journal entries are kept
for the loan's life plus the jurisdiction's limitation period; `retain_until` is set at closure; the
purge is Phase 15's (a debt row beside credit's evidence purge).

---

## 9. API architecture (owner brief 9.12)

All `/v1`, `@ClosedBody`, the error contract, `Idempotency-Key` on every keyed command, owner-scoped
(another party's id → `404 lending.NotFound`), every route in `RoutePermissionRegisterTest` with its
negatives.

| Route | Who | Command | Idempotency | Answer |
|---|---|---|---|---|
| `POST /v1/me/loan-applications` | customer, `MULTI_FACTOR` | product, amount or limit, term (loan), repayment day, declared income/expenditure (passed to credit), repayment method, payout destination (optional) | `lending.application:CUSTOMER:<id>` | `202` |
| `GET /v1/me/loan-applications/{id}` | customer | status; when declined, credit's reason texts through credit's read | read | — |
| `POST /v1/me/loan-applications/{id}/withdrawal` | customer, step-up | withdraw (cancels credit's request) | `lending.application-withdrawal:CUSTOMER:<id>` | sync |
| `GET /v1/me/loan-offers/{id}` | customer | terms, illustrative schedule or minimum-payment rule, totals (A5), expiry, the document | read | — |
| `POST /v1/me/loan-offers/{id}/acceptance` | customer, `MULTI_FACTOR` | accept (echoes `terms_sha256`; `409 lending.TermsChanged` on mismatch); optional verified payout destination | `lending.offer-acceptance:CUSTOMER:<id>` | `201` (the account id) |
| `POST /v1/me/loan-offers/{id}/decline` | customer | decline | `lending.offer-decline:CUSTOMER:<id>` | sync |
| `GET /v1/me/loans` · `/{id}` | customer | state, conditions, balances (derived), available limit (line), next due, payout status | read | — |
| `GET /v1/me/loans/{id}/schedule?version=` · `/statements` | customer | instalments with billed and paid status; statements | read | — |
| `GET /v1/me/loans/{id}/repayments` | customer | repayments and allocations | read | — |
| `POST /v1/me/loans/{id}/repayments` | customer, step-up | repay from the wallet | `lending.repayment:CUSTOMER:<id>` | sync |
| `POST /v1/me/loans/{id}/draws` | customer, `MULTI_FACTOR` | draw to the wallet (line) | `lending.draw:CUSTOMER:<id>` | sync |
| `POST /v1/me/loans/{id}/closure` | customer, `MULTI_FACTOR` | request the line's closure | `lending.closure:CUSTOMER:<id>` | sync |
| `POST /v1/me/loans/{id}/payoff-quotes` · `/payoff` | customer (payoff `MULTI_FACTOR`) | quote; execute | `lending.payoff-quote:…`, `lending.payoff:CUSTOMER:<id>` | sync |
| `POST /v1/me/loans/{id}/prepayments` | customer, step-up | partial prepayment (cut candidate) | `lending.prepayment:CUSTOMER:<id>` | sync |
| `GET /v1/me/loans/{id}/amendments/{aid}` · `POST …/acceptance` | customer, `MULTI_FACTOR` | read; accept | `lending.amendment-acceptance:CUSTOMER:<id>` | sync |
| `GET /v1/operator/loans/{id}` (+ `/explanation`) | `LOAN_SERVICE` / `LENDING_INVESTIGATE` | read; explanation from rows (audited) | read | — |
| `POST /v1/operator/loans/{id}/waivers` (+ `/{wid}/approval`, `/rejection`) | `LOAN_SERVICE` / `LOAN_SERVICE_APPROVE` | waive | `lending.servicing:EMPLOYEE:<id>` | sync |
| `POST /v1/operator/loans/{id}/repayments/{rid}/reversal` (+ approval, rejection) | same | reverse | same | sync |
| `POST /v1/operator/loans/{id}/amendments` (+ approval, rejection) | same | restructure | same | sync |
| `POST /v1/operator/lending/terms` (+ `/{v}/approval`, `/rejection`), `GET …?product=&at=` | `LENDING_ADMINISTER` / `LENDING_INVESTIGATE` | terms versions | `lending.terms:EMPLOYEE:<id>` | sync |
| `POST /v1/operator/lending/capital-contributions` (+ approval, rejection), `GET /v1/operator/lending/capital` | `LENDING_CAPITAL_ADMINISTER` / `LENDING_INVESTIGATE` | contribute capital; read headroom (a report) | `lending.capital:EMPLOYEE:<id>` | sync |
| `POST /v1/operator/loans/{id}/replay` | `LENDING_INVESTIGATE` | replay: `IDENTICAL` / `DIVERGED` | none (audited) | sync |
| `GET /v1/operator/reports/lending/{portfolio,delinquency,accrual,capital}` | `LENDING_INVESTIGATE` | amounts — reports, never metrics (ADR-0072) | read (audited) | — |

**Idempotency and authorization per financial command.** Acceptance (commits exposure and capital),
disbursement (system, no door), payout (system, payments' key), draw, repayment, payoff, prepayment:
each keyed per principal from birth; each authorised by ownership plus the assurance level of A19;
each with a database arbiter behind the key (§7.4). The operator's waiver, reversal, amendment, terms
and capital acts are keyed per employee and four-eyes at the domain and by `CHECK`. **No operator can
create a loan, change a rate outside an amendment, draw, or disburse.**

**Errors** (each catalogued in `ERROR_CONTRACT.md` by the task that builds it): `409
lending.ApplicationOpen`, `ApplicationNotWithdrawable`, `OfferNotAcceptable`, `TermsChanged`,
`LoanNotRepayable`, `LoanClosed`, `PayoffQuoteStale`, `AmendmentLapsed`, `TermsStale`,
`ProposalPending`, `ArrearsFirst`, `DrawsSuspended`; `422 lending.ProductNotOffered`,
`AmountOutOfRange`, `TermsNotAmortising`, `TermsInvalid`, `InsufficientFunds`, `ReasonRequired`,
`WaiverExceedsDue`, `LimitExceeded`, `CapitalUnavailable`; `403 lending.SelfApprovalRefused`, `SelfDealingRefused`; `404
lending.NotFound`; credit's refusals passed through unchanged where they are the cause.

## 10. Event architecture

Outbox, the platform envelope (event id, type, aggregate id, event version, schema version,
timestamp, producer `lending`, correlation, causation), `eventVersion` 1, additive evolution. Every
event is written in its state change's transaction (scenario 9: a crash between posting and
publication cannot lose or invent one).

| Event | Aggregate | When | Carries |
|---|---|---|---|
| `LoanApplicationSubmitted` / `LoanApplicationClosed` | application | T-app / declined, undecided, withdrawn | ids, product, credit request id; status, reason |
| `LoanOffered` / `LoanOfferClosed` | offer | T-off / declined, expired | offer id, decision id, principal or limit, currency, term, rate, expiry, terms version |
| `LoanAccepted` | loan | T-acc | account id, kind, agreement id + hash, decision id, offer id |
| `LoanDisbursed` / `LoanCancelled` | loan | T-dis / cancellation | entry id, value date, principal, currency, path; reason |
| `LoanPayoutConcluded` | loan | T-pyr | outcome (`PAID_OUT`/`FAILED`/`RETURNED`/`NOT_DISPATCHED`), payments' reference |
| `InstalmentBilled` / `CreditLineStatementIssued` | loan | billing / statement | due date, interest, principal, fees due; minimum payment |
| `CreditLineDrawn` | loan | T-drw | draw id, amount, entry id |
| `RepaymentReceived` / `RepaymentReversed` | loan | T-rep, collection, payoff / reversal | amount, allocation summary per component, entry id |
| `CollectionFailed` | loan | auto-collection got nothing | due item, outcome |
| `LoanFeeAssessed` / `LoanFeeWaived` / `LoanInterestWaived` | loan | rules 4, 8, 9 | ids, amount |
| `LoanDelinquencyChanged` / `LoanDefaulted` / `LoanDefaultCleared` | loan | condition change | old/new bucket, DPD |
| `LoanAmended` | loan | amendment accepted, prepayment | agreement version n+1, schedule version |
| `LoanClosed` | loan | closure | reason |
| `LoanTermsVersionActivated` | terms | activation | version, product, predecessor |

**Refused:** a per-day `InterestAccrued` (`DELIVERY_PLAN.md` §8 lists it) — an internal process
nobody consumes, one event per account per day; the accrual is the row and the entry, and the
billing or statement event carries what became due. `LoanDelinquent` is renamed
`LoanDelinquencyChanged` (it also announces cure). Lending consumes no event for correctness; it may
consume `credit.CreditDecisionRecorded` v2 and `credit.CreditDecisionRequestClosed` as hints (an
`InboxEventHandler`; v1 refused or handled, ADR-0087 as amended). No event carries a wallet id, an
account identifier or a party's figures beyond its own amounts.

---

## 11. Security, audit and privacy (owner brief 9.11)

**Permissions and roles** (identity `V021`): `LENDING_ADMINISTER` (terms versions) and
`LENDING_INVESTIGATE` (explanation, replay, reports) → role `LENDING_OFFICER`;
`LENDING_CAPITAL_ADMINISTER` → role `LENDING_TREASURY_OFFICER`; `LOAN_SERVICE` (read any loan,
audited; propose waivers, reversals, amendments) and `LOAN_SERVICE_APPROVE` (approve them — never
one's own, domain + `CHECK`) → role `LOAN_SERVICING_AGENT`. Least privilege: no role both proposes
and approves its own act; an employee never services their own party's loan (the R1 lesson —
`lending.SelfDealingRefused`, audited `FAILED`).

**Origination is never a person's act** in Phase 11: the offer comes from credit's decision (a
person's approval there is credit's four-eyes underwriting), acceptance is the customer's.

**Customer acts**: keyed, owner-scoped, MFA per A19; standing checked in-transaction for application,
acceptance and draw (not for repayment — a suspended customer may still repay).

**Acceptance evidence** (`loan_acceptance`, `INSERT` only): offer id, agreement version, `terms_sha256`,
template id and version, the SHA-256 of the document bytes shown, identity, session, assurance level,
`accepted_at` (database clock), channel, client IP and user agent (`CONFIDENTIAL`, A13). Acceptance is
not consent (`INV-IDN-04`): no consent purpose is created.

**Privileged acts and four-eyes**: fee waiver (always, A14), interest waiver, repayment reversal,
amendment, terms activation and capital contribution — each four-eyes and reasoned (`422
lending.ReasonRequired`), the reasons screened for card and account numbers (the `CreditReasons` /
R2 precedent, `INV-AUD-02`). No "override" of a computed figure exists: a person changes outcomes only
through these acts, each a posting with a reason, never an edit.

**Data classification** (`ColumnClassificationTest`, each task classifies its columns): amounts and
rates on agreements, billings, statements, draws, accruals, allocations, quotes —
`RESTRICTED-FINANCIAL`; party and loan references `CONFIDENTIAL`; acceptance session and IP
`CONFIDENTIAL`; product codes, statuses, engine versions `INTERNAL`; the payout destination is an
opaque payments reference, never an account identifier (`INV-RAIL-03`).

**Logging**: no amount, rate, balance, limit, DPD, party or loan id in a log line, metric tag, span
attribute or exception message (`INV-AUD-02`, ADR-0072); records carrying money redact `toString`;
storage failures carry no driver cause (the Phase 9 rule); the needle walk extends to lending's doors.

**Audit acts** (registered in `AUDITABLE_ACTIONS.md` by their tasks; actor, time, operation, target,
reason where required, correlation, outcome; losers record nothing): `lending.OfferAccepted`,
`lending.LoanDisbursed` (system), `lending.LoanRead`, `lending.WaiverProposed` / `…Approved` /
`…Rejected`, `lending.RepaymentReversalProposed` / `…Approved` / `…Rejected`,
`lending.AmendmentProposed` / `…Approved` / `…Rejected` / `lending.AmendmentAccepted`,
`lending.TermsVersionProposed` / `…Activated` / `…Rejected`, `lending.CapitalContributionProposed` /
`…Approved` / `…Rejected`, `lending.ExplanationRead`, `lending.LoanReplayed`, `lending.ReportRead`,
`lending.SelfDealingRefused`; credit adds `credit.DecisionConsumed`.

---

## 12. The lending model

### 12.1 Products, terms and agreement versioning (owner brief 9.3)

| Term | Source | Pinned on |
|---|---|---|
| Principal (loan) / limit (line) | the decision's approved amount — the offer equals it (A20) | offer → agreement v1 |
| Currency | the product's (EUR for both) = the decision's | agreement |
| Nominal annual rate, rate type | the active terms version (`FIXED` only; variable deferred — no reference-rate source) | agreement |
| Term (loan) | the decision's approved months | agreement |
| Frequency | `MONTHLY` | agreement |
| Repayment / statement day | the customer's choice within the terms' allowed set (1–28) | agreement |
| First due date (loan) | at disbursement — the first repayment day ≥ `min_first_period_days` (15) after the accrual start | schedule v1 |
| Fees | origination (amount or bps; `DEDUCTED`; default 0, A17), late fee (amount, day, cap; A18) | agreement |
| Allocation order, overpayment treatment, auto-collection | the terms version (L6, A21, A26) | agreement |
| Day count, zone, rounding | `ACT_365F`; `servicing_zone` (`UTC`); instalment rounding `UP`, interest `HALF_EVEN` | agreement |
| Delinquency bounds, default threshold, grace | the terms version (L7: 90 days) | agreement |
| Minimum payment (line) | floor, ratio, payment-due days (A23, A24) | agreement |
| Engine versions | `SCHEDULE_ENGINE_V1`, `ACCRUAL_ENGINE_V1`, `ALLOCATION_ENGINE_V1`, `STATEMENT_ENGINE_V1` | agreement |
| Agreement template version | code-registered template id + version | agreement + acceptance evidence |

`loan_agreement` is `INSERT`-only for every role, one row per version: `(id, loan_id, version,
offer_id | amendment_id, decision_id, terms_version_id, template_id, template_version, engines,
canonical_terms jsonb, terms_sha256, effective_from, created_at)`. `canonical_terms` is the full,
self-contained term set (money as minor units + currency, rates as decimal strings), readable
without joining a terms version later retired; the hash re-verified by replay. A terms change is a
new terms version; existing agreements keep theirs (`INV-HIST-04`). An engine change is a new engine
version; old versions stay in the code. **Every servicing row names the agreement version it was
computed under**, so "why is this instalment €213.47?" is answered from rows: agreement vN (hash) →
schedule vN → billing (period, accruals summed) → allocations → journal entries.

**No consumer-credit-law features** (L4): no statutory APR formula, no withdrawal right, no
statutory notices or caps; **no penalty interest, no prepayment fee, no APR display** (L8). Each
would be a terms field and a reviewed engine change.

### 12.2 The schedule (loan; owner brief 9.4) — `SCHEDULE_ENGINE_V1`

V1 implements one family — fixed rate, monthly, level-payment annuity with actual-day interest —
and refuses any terms naming a convention it does not implement.

1. **Due dates.** `D1` = the first date with day-of-month `k` ≥ `d0 + min_first_period_days`
   (`d0` = the accrual start); `Dj = D1 + (j−1)` months, each from the intended day `k` (so 31 Jan →
   28/29 Feb → 31 Mar), clamped to the month's last day; no business-day adjustment (A6).
2. **Level instalment.** `i = r / 12` (scale 20, `HALF_EVEN`); `A = P · i / (1 − (1+i)^−n)` with
   `(1+i)^n` by exact `BigDecimal.pow(n)`, one division at scale 20; `r = 0` → `A = P / n`; `A`
   rounded once to minor units `UP` (so the final instalment ≤ a regular one).
3. **Periods.** `days_j = Dj − D(j−1)`; `interest_j = round_HALF_EVEN(B(j−1) · r · days_j / 365)`
   — exact product, one division, one rounding; `principal_j = A − interest_j` (`j < n`),
   `principal_n = B(n−1)`.
4. **Conservation, asserted:** Σ `principal_j` = P exactly; Σ `amount_j` = P + Σ `interest_j`; a
   terms version that cannot amortise at its declared bounds is refused at its proposal (`422
   lending.TermsNotAmortising`); a specific offer still yielding a non-positive principal portion
   closes its application `CLOSED_UNDECIDED (TERMS_NOT_AMORTISING)` with no offer — never
   negatively amortised.
5. **The projection is not the bill.** Billing (§12.3) bills interest actually accrued (on actual
   principal outstanding, so a late payment raises it); principal due = `max(0, A − interest due)`;
   the final instalment all remaining principal. On an on-time path the billed amounts equal the
   projection to the minor unit (property-tested).
6. **Grace** delays only the late fee, never DPD.
7. **Recalculation after an approved contractual change** (amendment, prepayment — scenario 8): a
   new schedule version from agreement v n+1 with `P` = principal outstanding (derived from
   postings) at `effective_from`, the remaining or new term, `D1` the next due date. Billed
   instalments of the old version are kept; unbilled ones are superseded (`UNIQUE (loan_id,
   schedule_version)`; nothing updated).
8. **Generated at the accrual start** — at disbursement (path W) or at the payout's terminal
   outcome (path X, A30) — in that transaction; the offer shows an illustrative schedule with
   `d0` = today, labelled as such, with total interest and total payable (A5).

### 12.3 Interest and fees (owner brief 9.5) — `ACCRUAL_ENGINE_V1`

**The four words, kept apart.** *Accrued* — earned day by day, not yet due (`interest_accrual`
rows; `LOAN_INTEREST_ACCRUED`). *Due* — became payable at a due date or statement
(`instalment_billing` / `credit_line_statement`; `LOAN_INTEREST_DUE`, `LOAN_PRINCIPAL_DUE`,
`LOAN_FEES_DUE`). *Paid* — settled by an allocation of money received (`repayment_allocation`;
credits to the due accounts). *Outstanding* — owed now, derived: `LOAN_PRINCIPAL +
LOAN_PRINCIPAL_DUE + LOAN_INTEREST_ACCRUED + LOAN_INTEREST_DUE + LOAN_FEES_DUE − LOAN_CREDIT_BALANCE`.

**Accrual** (L5): one row per (account, date `d`) from the accrual start while principal
outstanding at end of `d` > 0; base = `LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE` over journal lines with
`value_date ≤ d`, read under L3 — never interest or fees (A4). **Cumulative rounding**: within a
period (instalment period or statement cycle), `cum_exact(d) = Σ base(x) · r / 365` (scale 20, the
division once per day, fixed by the engine version); `posted(d) = round_HALF_EVEN(cum_exact(d)) −
round_HALF_EVEN(cum_exact(d−1))`. A period's postings sum to its exact interest rounded **once**. A
zero day writes the row and no entry. Posting `DR LOAN_INTEREST_ACCRUED / CR LOAN_INTEREST_INCOME`,
key `lending.accrual:<loan>:<date>`. **When is `d` accruable?** When the database clock, read after
L3, is past the end of `d` in the pinned zone — never an instance clock (`INV-LND-10`). Catch-up
oldest-first; a payoff accrues missing dates itself under the same uniques. **Leap years**: ACT/365F
divides by 365 every day; 29 February accrues one day.

**Billing** (loan; one transaction per instalment, born once, only after every accrual through
`Dj − 1` exists): interest due = Σ the period's `posted_minor`; principal due = `max(0, A −
interest due)` (final: all remaining principal); one entry `DR LOAN_INTEREST_DUE / CR
LOAN_INTEREST_ACCRUED` and `DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL`; then any credit balance
applied (rule 6).

**Fees**: origination (A17: `DEDUCTED` at disbursement, upfront in the operational ledger, EIR
amortisation Phase 14's); late fee (A18: once per due item on the day DPD reaches `late_fee_day` >
grace, born once, capped per account; `DR LOAN_FEES_DUE / CR LOAN_FEE_INCOME`); **no penalty
interest, no prepayment fee** (L8).

**Waivers, corrections, reversals.** A fee waiver reverses the assessment's entry through
`ReversalService` (bounded by the original, `INV-REV-02`) — only the unpaid remainder; a paid fee
is refunded to the credit balance (`DR LOAN_FEE_INCOME / CR LOAN_CREDIT_BALANCE`, kind `FEE_REFUND`).
An interest waiver is an explicit, reasoned posting `DR LOAN_INTEREST_INCOME / CR LOAN_INTEREST_DUE`
(or `…ACCRUED`). A wrong accrual (an engine defect) is never edited: a new engine version and an
explicit correction run posting deltas (`lending.accrual-correction:<loan>:<date>:<engine>`) — not
built in Phase 11 (nothing to correct), keys reserved so nothing collides.

### 12.4 Disbursement (owner brief 9.6) — the two paths (L2)

```
credit decision APPROVED → offer → accept (T-acc: L0; decision consumed; capital headroom under L6;
  agreement v1; loan PENDING_DISBURSEMENT; disbursement PENDING; [path X: payout instruction])
→ T-dis (L0, L3, L5): standing re-read; wallet resolved;
     DR LOAN_PRINCIPAL P / CR CUSTOMER_WALLET P (− f, CR LOAN_FEE_INCOME f);   loan ACTIVE
     path W: accrual start = the entry's value date; schedule v1 generated
     path X: a ledger hold of P (− f) on the wallet (L7); the payout PENDING; no schedule, no accrual yet
→ path X: T-pyo — payments' system-actor withdrawal on the recorded instruction, adopting the hold
     (payments' own routing, dispatch, UNKNOWN held, inquiry-only outcome, return, expectation)
  → T-pyr — lending reads payments' outcome: PAID_OUT | FAILED | RETURNED | NOT_DISPATCHED;
     accrual start = payments' conclusion instant in the pinned zone; schedule v1 generated
→ rail → settlement → reconciliation (payments' and Phase 8's existing machinery)
```

**When the loan becomes a receivable — both paths:** when the borrower's wallet is credited
(`LOAN_PRINCIPAL` debited in the same entry), in T-dis. Before that there is a commitment (counted
in exposure and in capital headroom), no ledger effect.

**When disbursement is successful.** *Path W (wallet):* at T-dis's commit — internal and final, one
local transaction, no provider, no ambiguity (ADR-0043); interest accrues from that entry's value
date. *Path X (external bank account):* the **disbursement** (lending's financial act: the funds
made the borrower's) succeeds at T-dis's commit exactly as in path W; the **payout** succeeds when
payments concludes the withdrawal `COMPLETED` from an authenticated answer (ADR-0083) and is later
matched to the settlement by Phase 8. Interest starts at the payout's terminal outcome (A30): on
`PAID_OUT` the funds reached the borrower's bank; on `FAILED`, `RETURNED` or `NOT_DISPATCHED` the
funds are back (or still) in the borrower's wallet, available — either way the borrower has the money
from that instant, and the platform bore the ambiguity in between.

**Why the wallet is the transit account, and why there is no cycle.** A direct `DR LOAN_PRINCIPAL /
CR clearing` would make the loan's birth depend on a provider's ambiguous answer and would make
lending a second implementer of `OutboundCreditComposition` (§7.3). Composing *disbursement-to-wallet,
then a withdrawal* keeps the receivable's birth unambiguous and puts the external leg on machinery
already proven for ambiguity; lending's payout machine *waits* for payments' outcome by reading it.
The hold placed in T-dis — adopted, not duplicated, by payments' withdrawal (a payments change,
payments `V032`: `ActorType.SYSTEM` with the instruction reference in the fingerprint and an optional
hold to adopt under the wallet lock) — guarantees the funds cannot be spent between the disbursement
and the payout. The loan never re-dispatches; payments never re-routes after an ambiguous dispatch
(`INV-RAIL-02`); a return lands in the wallet by the withdrawal's own return path, and the loan is
unaffected (`INV-LND-07`).

**Failure modes.** Standing lost between acceptance and disbursement → `CANCELLED (STANDING_LOST)`,
the commitment released under L0, capital headroom restored, the decision stays consumed. Wallet not
postable → savepoint rollback, retried until `disbursement_deadline`, then `CANCELLED
(DISBURSEMENT_FAILED)`. Crash after acceptance → the sweep disburses once. Ten disbursers → one entry.
Payout unroutable → `NOT_DISPATCHED`, the hold released, interest starts. Payout `UNKNOWN` →
`DISPATCHED`, held, no interest; resolved only by payments' inquiry.

### 12.5 Repayment and allocation (owner brief 9.7) — `ALLOCATION_ENGINE_V1`

**Sources in Phase 11:** customer-initiated from the wallet (`POST …/repayments`, amount ≤ wallet
available under L7); scheduled auto-collection on the due date (A21); credit-balance application at
billing; payoff (§12.5.3); external inbound deferred (A11 — money tops up the wallet through existing
pay-ins, whose duplicate notifications and returns payments already makes harmless).

**The order (L6), data on the terms version and pinned:** (1) billed items — instalments or statements
— oldest due date first, within each `FEES → INTEREST → PRINCIPAL`; (2) the remainder: loan →
`HOLD_AS_CREDIT` (`CR LOAN_CREDIT_BALANCE`, applied automatically at the next billing; refunded at
closure); line → `PAY_DOWN_PRINCIPAL` (`CR LOAN_PRINCIPAL`, restoring the limit), then any excess over
every receivable held as credit (A26). The engine is pure: `allocate(amount, dueState, rules) →
lines`; Σ lines = amount exactly; no line above its component's due; `dueState` derived from postings
and rows under L3. One entry `DR CUSTOMER_WALLET A / CR <due accounts …> / CR LOAN_CREDIT_BALANCE
rest` (lines aggregated per account; the per-item split in `repayment_allocation`, naming the entry),
key `lending.repayment:<repaymentId>`.

| Case | Behaviour |
|---|---|
| Full instalment | allocated; paid (billed − allocated = 0); cure if it was the oldest past-due |
| Partial | allocated in order; the remainder stays due; DPD continues from the oldest unpaid due date |
| Over | the remainder by the overpayment treatment |
| Early (before any billing) | nothing due — held as credit (loan) or principal paid down (line); an explicit payoff is a separate command |
| Failed (insufficient funds, wallet not postable) | no repayment, no entry; a `collection_attempt` records it; a customer's command is `422 lending.InsufficientFunds` |
| Returned | not applicable to wallet repayments (internal, final); an external top-up's return is payments' |
| Reversed (operator mistake — **scenario 6**) | four-eyes; `ReversalService` of the repayment's entry (exact swap); `REVERSED` as a born-once `repayment_reversal`; allocations negated by rows, never deleted; later allocations are not re-cut — the reversed amount simply becomes due again; L0 first (outstanding rises); refused for a closing repayment (A12) or a wallet that cannot be credited |
| Duplicate (**scenario 2**) | the same key → the same answer, one entry (`INV-IDEM-01`); auto-collection's `(item, date)` unique |
| Concurrent (**scenario 3**) | serialised on L3; each allocates against what it finds; conserved, never over-allocated |
| After a state change — `CLOSED` / `CANCELLED` | `409 lending.LoanNotRepayable`, nothing posted |

**Payoff** (both products). *Quote* (keyed): good-through date `G` (today, up to 30 days); amount =
derived outstanding + the accrual the engine would post through `G − 1` on the current base —
deterministic, stored immutable with its inputs. Daily accrual means no rebate arithmetic exists
(`DELIVERY_PLAN.md`'s "early settlement rebate" is zero by construction, tested). *Execution* (keyed,
`MULTI_FACTOR`, naming the quote, on `G` only): under L3 accrue missing dates, bill everything
unbilled, recompute; ≠ the quote → `409 lending.PayoffQuoteStale`, nothing posted (**scenario 7**:
a repayment that committed first moved the balance; one that waits sees `CLOSED`); else one repayment
of the amount allocated to every component, the credit balance refunded, `CLOSED (PAID_OFF_EARLY)`,
`payoff_execution` born — one transaction. A line's payoff also closes it (`CLOSED_BY_CUSTOMER`).

**Partial prepayment** (cut candidate `-028`): a repayment kind `PREPAYMENT` crediting
`LOAN_PRINCIPAL`, with agreement and schedule version n+1 (shorter term, same instalment) in one
transaction; refused while past due (`409 lending.ArrearsFirst`).

### 12.6 The revolving credit line (L3) — ADR-0100

**Agreement.** Limit `L` (the decision's approved amount), rate, statement day, payment-due days, the
minimum-payment rule, the engines; versioned like the loan's.

**Draws** (T-drw): under L3, `available = L − (LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE)` derived from
journal lines; refused `422 lending.LimitExceeded` when the amount exceeds it (`INV-LND-14`),
`409 lending.DrawsSuspended` while past due or defaulted (A25), `422 lending.CapitalUnavailable`
when capital headroom under L6 is short (A28). Posting `DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET`, key
`lending.draw:<drawId>`. Draws go to the wallet only (A25).

**Revolving interest.** The same accrual engine on drawn principal, the period being the statement
cycle; interest never accrues on interest or fees (A4).

**Statement** (`STATEMENT_ENGINE_V1`; born once per (line, cycle end) in T-svc after the cycle's
accruals exist): bills the cycle's accrued interest (`ACCRUED → DUE`), carries assessed fees, and
bills the minimum payment's principal part — `min(drawn principal not yet due, max(round_UP(3 % ×
drawn principal), EUR 25.00 − interest − fees, 0))` (A24: the whole minimum payment at least EUR
25.00, or the balance) — `DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL`. Minimum payment = interest + fees + that principal; due date = statement date + 25
days (A23). The statement's opening and closing balances are derived from the ledger, never stored as
figures that could drift (the statement row stores only what it billed).

**Available limit recomputation** is never a stored number: every draw derives it under L3; a
principal repayment (due or not-due) restores it at commit; interest and fees never consume it.

**Closure.** The customer requests closure (`ACTIVE → CLOSING`, no further draws); the line closes in
the transaction that zeroes its last receivable; a line with nothing outstanding closes at the request.

**Exposure — the committed limit, not the drawn balance** (§12.10): while a line is `ACTIVE`, a party
can draw to `L` at any moment without a new decision, so a decision that counted only the drawn
balance could approve a second credit assuming the undrawn limit away, after which both could be
drawn — `INV-CRD-09` broken. So `PlatformCreditExposure` counts an open line's **limit** — or its
drawn principal if that is higher, which only a repayment reversal after a re-draw can cause (the
reversal is admitted, a correction must stay possible, and the line's available limit is then zero
until repaid below it) — and a `CLOSING` line its drawn principal (no further draws possible). Capital, by contrast, is consumed only by drawn
principal (A28): exposure is the promise to the customer; capital is the money actually deployed.

### 12.7 Delinquency and the collections boundary (owner brief 9.8)

| Concept | Phase 11 definition |
|---|---|
| Upcoming | a due item (instalment or statement minimum) whose due date is in the future |
| Due | billed, due today (DPD 0) |
| Past due | billed, unpaid at the end of its due date |
| **DPD** | the days between the oldest past-due item's due date and the current business date (database clock, pinned zone); 0 when nothing is past due — derived, never stored as a mutable counter |
| Bucket | from the terms bounds (default `1–29`, `30–59`, `60–89`, `90+`) |
| Cure | DPD back to 0 because every billed amount is paid |
| **Default** | DPD ≥ 90 (L7): `LoanDefaulted`, the flag set; cleared at cure (A8) |
| Restructuring | an amendment (§12.5, `-027`) — may re-schedule arrears (`DR LOAN_PRINCIPAL / CR LOAN_PRINCIPAL_DUE`); overdue interest never capitalised in Phase 11 |
| Payoff | permitted in any condition |
| Collections case | **deferred** (Phase 13); lending emits the facts |

The servicing sweep evaluates the condition daily under L3 and appends a `loan_condition_event`
**only on a change**, born once per (account, business date, kind) — re-running a day writes
nothing; a closed account is skipped (scenario 10). Lending owns the contractual facts; Risk (Phase
13) owns fraud and abuse judgements; a future collections context owns contact strategy,
promises-to-pay and placement — none of them writes lending's rows. **Not built** (legally
sensitive, each recorded with its default): acceleration, default or penalty interest, interest on
interest, arrears notifications, bureau reporting, statutory contact limits, insolvency handling.

### 12.8 Ledger integration (owner brief 9.9) — ledger `V026`

**The account plan.** `OwnerKind.LOAN` (`owner_ref` = the loan id) for both kinds:

| Purpose | Owner | Type (normal) | Per | Holds |
|---|---|---|---|---|
| `LOAN_PRINCIPAL` | LOAN | ASSET (debit) | account | principal outstanding, not yet due (drawn principal for a line) |
| `LOAN_PRINCIPAL_DUE` | LOAN | ASSET | account | billed principal unpaid |
| `LOAN_INTEREST_ACCRUED` | LOAN | ASSET | account | earned, not yet due |
| `LOAN_INTEREST_DUE` | LOAN | ASSET | account | billed interest unpaid |
| `LOAN_FEES_DUE` | LOAN | ASSET | account | assessed fees unpaid |
| `LOAN_CREDIT_BALANCE` | LOAN | LIABILITY (credit) | account | money received beyond every receivable, owed to the borrower |
| `LOAN_INTEREST_INCOME` | OPERATIONAL | REVENUE | currency | |
| `LOAN_FEE_INCOME` | OPERATIONAL | REVENUE | currency | |
| `LOAN_WRITE_OFF_EXPENSE` | OPERATIONAL | EXPENSE | currency | seeded, posted by nothing in Phase 11 |
| `LENDING_CAPITAL` | OPERATIONAL | EQUITY (credit) | currency | the platform's own funds committed to lending, recognised from bank evidence (§12.9) |

The six per-loan accounts are opened by `createOrConverge` in the acceptance transaction, in the
account's currency only. All ten join `closedToFreeAdjustments()`; `LENDING_CAPITAL` also joins
`reconciledPositions()`'s single-poster discipline (only capital recognition credits it). No
`*_CLEARING` purpose is named (`INV-RAIL-04`): lending moves no money across a rail itself.

**Posting rules** (every entry `posting_date = value_date =` the business date of the database clock
under L3 — rule 2: the accrual date; `reference` = the lending operation's id; each balances per
currency by construction):

| # | Economic event | Operation | Debit | Credit | Reasoning | Key |
|---|---|---|---|---|---|---|
| 0 | Capital contributed (bank evidence) | `CapitalRecognition` (reconciliation's bank recognition) | `CASH_AT_BANK` | `LENDING_CAPITAL` | the platform's own cash enters the pool as lending capital; cash recognised only from a statement (`INV-SET-06`) | reconciliation's recognition key |
| 1 | Loan disbursed / line drawn | `Disbursement` / `Draw` | `LOAN_PRINCIPAL` P | `CUSTOMER_WALLET` P − f; `LOAN_FEE_INCOME` f | the platform acquires a claim on the borrower and owes the borrower the funds now in their wallet — wallet money backed by recognised capital, never created unbacked (`INV-LND-13`) | `lending.disbursement:<loan>` / `lending.draw:<draw>` |
| 2 | A day's interest earned | `InterestAccrual` | `LOAN_INTEREST_ACCRUED` | `LOAN_INTEREST_INCOME` | income recognised as earned; a receivable not yet payable | `lending.accrual:<loan>:<date>` |
| 3 | An instalment falls due / a statement is issued | `InstalmentBilling` / `Statement` | `LOAN_INTEREST_DUE` I; `LOAN_PRINCIPAL_DUE` Pj | `LOAN_INTEREST_ACCRUED` I; `LOAN_PRINCIPAL` Pj | re-classification: the same claim becomes payable (DPD readable from the ledger) | `lending.billing:<instalment>` / `lending.statement:<statement>` |
| 4 | A late fee assessed | `FeeAssessment` | `LOAN_FEES_DUE` | `LOAN_FEE_INCOME` | contractual fee earned and payable | `lending.fee:<assessment>` |
| 5 | Repayment from the wallet | `Repayment` | `CUSTOMER_WALLET` A | due accounts per allocation; `LOAN_PRINCIPAL` (line paydown); `LOAN_CREDIT_BALANCE` rest | the borrower's funds settle the claim; any excess is owed back | `lending.repayment:<repayment>` |
| 6 | Credit balance applied | `CreditBalanceApplication` | `LOAN_CREDIT_BALANCE` | due accounts per allocation | the excess held now settles what became due | `lending.credit-application:<billing>` |
| 7 | Payoff | `Payoff` | rules 2, 3, 5, then `LOAN_CREDIT_BALANCE` | … `CUSTOMER_WALLET` (refund) | every receivable to zero, nothing owed back | `lending.payoff:<quote>` (+ the rule keys) |
| 8 | Fee waived | `Waiver(FEE)` | `LOAN_FEE_INCOME` | `LOAN_FEES_DUE` | `ReversalService` of rule 4's entry, bounded | scope `ledger.reverse` |
| 8a | Paid fee refunded | `Waiver(FEE_REFUND)` | `LOAN_FEE_INCOME` | `LOAN_CREDIT_BALANCE` | the fee already paid is owed back | `lending.waiver:<waiver>` |
| 9 | Interest waived | `Waiver(INTEREST)` | `LOAN_INTEREST_INCOME` | `LOAN_INTEREST_DUE` / `…ACCRUED` | income forgone, claim released | `lending.waiver:<waiver>` |
| 10 | Repayment reversed | `RepaymentReversal` | rule 5's credits | `CUSTOMER_WALLET` | `ReversalService` of rule 5's entry, exact | `lending.repayment-reversal:<reversal>` |
| 11 | Disbursement failed | — | — | — | nothing to compensate: a failed disbursement posts nothing (whole commit or savepoint rollback) | — |
| 12 | Payout (path X) | payments' `Withdrawal` | `CUSTOMER_WALLET` | `INSTANT_CLEARING` (payments' own posting) | the borrower's funds leave for their bank — payments' entry, payments' reversal on failure, payments' return | payments' keys |
| 13 | Arrears re-scheduled | `Amendment` | `LOAN_PRINCIPAL` | `LOAN_PRINCIPAL_DUE` | overdue principal re-classified as not due under the new schedule | `lending.amendment:<amendment>` |
| 14 | Write-off (**deferred**, Phase 14) | `WriteOff` | `LOAN_WRITE_OFF_EXPENSE` | every LOAN asset account | loss recognised; the account plan unchanged later | — |

**Three outstanding numbers, one authority.** *Ledger (authoritative):* the six per-account
balances, derived from journal lines. *Operational (lending's rows):* billings, statements, draws,
allocations, accruals, assessments — immutable facts that **explain** the ledger and must sum to it
(the subledger proof, §13.3; a difference is an alert, never self-corrected). *GL (Phase 14):* a
mapping of these purposes; `gl_code` null now. Lending never stores a balance, never decides from the
projection, and posts only through `PostingService` / `ReversalService` — no duplicated ledger
authority (`INV-LED-04`).

### 12.9 Lending capital and the safeguarding position (L1) — ADR-0096

**Why.** `DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET` raises the platform's wallet liabilities without
cash entering. Unless the platform's own money backs it, the safeguarded cash (customers' money)
would silently fund loans, and any proof of wallets against cash would show the loan book as a
shortfall. So every loan and draw is funded from **lending capital**.

**The account.** `LENDING_CAPITAL` — OPERATIONAL, per currency, **EQUITY**, credit-normal: the
platform's own funds committed to lending. Recognised **only from bank evidence**: a treasury officer
proposes a contribution (amount, currency, reference) and a second approves it (four-eyes,
`LENDING_CAPITAL_ADMINISTER`, namespace `11`); the approval opens a `LENDING_CAPITAL_CONTRIBUTION`
expectation through reconciliation's opener; when the platform's corporate transfer appears on the
settlement bank's statement, bank recognition — still the one poster of `CASH_AT_BANK` — matches the
line to the expectation and posts `DR CASH_AT_BANK / CR LENDING_CAPITAL` (rule 0). An unmatched line
is a break like any other; an expectation ageing out unmatched lapses the contribution. Capital is
never adjusted to fit, never posted by a person, and never withdrawn in Phase 11 (a capital return is
a later treasury task).

**Why EQUITY, and why the disbursement entry does not debit it.** Capital is a *source* of funds;
the loan is a *use*. In the platform's consolidated books the platform cannot owe itself, so a
platform-owned LIABILITY "lending wallet" would be fiction, and a four-line disbursement debiting
equity would record a capital reduction that did not happen. The ledger already shows the funding:
the contribution put cash beside the capital; the disbursement turns wallet capacity into a
receivable. What makes "funded from capital" **binding** is the headroom invariant, judged under the
capital account's row lock:

```
headroom(c) = CR−DR(LENDING_CAPITAL, c)
            − Σ committed principal of loans PENDING_DISBURSEMENT in c
            − Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) over every loan and line in c   ≥ 0
```

Checked at every loan acceptance (the commitment), every draw and every repayment reversal that
re-instates principal, under L6 (`INV-LND-13`); a shortfall refuses the act (`422
lending.CapitalUnavailable`; a refused reversal's proposal stays `PROPOSED`). Disbursement needs no re-check — its
principal was committed at acceptance and no capital leaves the pool in Phase 11. Repaid principal
restores headroom at commit; interest and fee income are not capital (A29). The one capital row per
currency is a hot row — every acceptance and draw in a currency serialise on it; correct by
construction, measured in the storm, and partitioned into capital tranches by Phase 16 if the
characterisation demands.

**The safeguarding position stays exact.** Every unit of wallet money a disbursement or draw creates
is matched by a unit of recognised platform capital still in the pool and not yet deployed:
`Σ lending-funded principal outstanding + committed ≤ capital recognised from bank evidence`. The
**capital proof** (`LendingCapitalProof`, `-004`, gauged, alerting on any failure) asserts per
currency, in one `REPEATABLE READ` snapshot: (a) every journal line on `LENDING_CAPITAL` is a
recognised contribution's (completeness — no other poster); (b) headroom ≥ 0 at rest; (c) the
**safeguarding adjustment** — the published term `lending_funded(c) = Σ principal outstanding +
committed`, by which a future safeguarding proof (Phase 14/15's, comparing customer liabilities with
cash) subtracts loan-funded wallet money from customer-funded money: customer liabilities −
`lending_funded` is unchanged by any lending act except repayments of interest and fees (platform
income) and the loans' own principal flows, each explained by its entry. A capital return, income
recycling and write-off's effect on capital are recorded for the phases that build them.

### 12.10 Consumption and exposure (R13, R12) — ADR-0091

**`P11-TSK-001` — the consumption port** (credit `V021`):

```java
// credit (published surface). Implemented by credit's JdbcCreditDecisionConsumptions.
public interface CreditDecisionConsumptions<T> {
    /** Takes up an approved decision for {consumer}. Runs in the caller's transaction. */
    Consumption consume(T unitOfWork, CreditDecisionId decision, ConsumerRef consumer);
    sealed interface Consumption {
        record Consumed(CreditDecisionConsumptionId id, CreditDecision decision, boolean replayed) implements Consumption {}
        record NotApproved(CreditDecisionId decision) implements Consumption {}
        record Lapsed(CreditDecisionId decision, Instant validUntil) implements Consumption {}
        record AlreadyConsumed(CreditDecisionId decision, ConsumerRef by) implements Consumption {}
        record NotFound(CreditDecisionId decision) implements Consumption {}
    }
}
record ConsumerRef(ConsumerKind kind, UUID ref) {}   // LOAN_AGREEMENT (Phase 11); BNPL reserved (Phase 12)
```

In order, in the caller's transaction: read the decision's party (plain) and take the party's
`credit_profile` row `FOR UPDATE` (Phase 10 element (1)) — so a consumption and a decision for one
party serialise; re-read the decision; `DECLINED` → `NotApproved`; `valid_until <=
statement_timestamp()` → `Lapsed` (the complement of `JdbcReservedExposure`'s `valid_until >
statement_timestamp()`: at the boundary exactly one of *reserves* and *consumable* holds); insert —
the same `ConsumerRef` already there → `Consumed(replayed)`; another → `AlreadyConsumed`; audit
`credit.DecisionConsumed` (decision, consumer; never an amount). **The database guard:** credit
`V021` adds `consumer_kind`, `consumer_ref`, `UNIQUE (consumer_kind, consumer_ref)` (the table holds
no rows, asserted), and a `SECURITY DEFINER` function `credit.consume_decision(...)` owned by credit's
owner role — taking the profile lock and re-judging outcome and validity itself — becomes the only
path to the table: `INSERT` revoked from the application role, a trigger refusing any insert not made
inside the function. `JdbcReservedExposure` is unchanged (it already excludes consumed decisions).

**`P11-TSK-013` — `PlatformCreditExposure` version 2** (`app`'s `LendingCreditExposure`), one SQL
statement over lending's definer view and the ledger's journal lines (never `account_balance`),
answering for (party, currency):

```
loans:  committed   = Σ agreement principal of the party's loans PENDING_DISBURSEMENT
        outstanding = Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) of its loans not CANCELLED
lines:  committed   = Σ GREATEST(agreement limit, DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE)) of its lines ACTIVE
        outstanding = Σ DR−CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) of its lines CLOSING
outstandingFor = Σ committed + Σ outstanding
```

**Principal only** (A3): the policy's maximum exposure is a principal concept and the bureau's total
balance its comparable external figure; interest and fees due are not credit the platform extended by
a decision. **No gap:** consumption and the commitment commit together under L0 (T-acc), so no
instant sees neither the reservation nor the commitment; every exposure-raising lending transaction
(acceptance, disbursement, repayment reversal) takes L0 first; the read is one statement; and the
deciding transaction already re-reads outstanding beside reserved under L0, a change in either
freezing a successor snapshot (R12, ADR-0087 §5 / ADR-0088 as amended). Decisions made under v1 replay
identically (their snapshots carry zero with v1); the version is recorded as provenance.

---

## 13. Testing strategy (owner brief 9.13)

### 13.1 Tiers (ADR-0028)

- **Hermetic (pure engines):** the schedule engine — property tests (Σ principal = P; Σ amount = P +
  Σ interest; monotone in rate; `r = 0`; every term 6–60; every repayment day 1–28 with the clamp;
  disbursement on every day of a leap and a non-leap year) and golden worked examples checked by
  hand; the accrual engine — cumulative rounding equals one rounding per period, 29 Feb, zero days;
  the allocation engine — conservation, order, never above due, both overpayment treatments; the
  statement engine — the minimum payment under every floor/ratio interaction; `NoFloatingPointMoney
  RulesTest` over `lending`.
- **Database (Testcontainers, each task):** machines at three ranks (raw-SQL terminal edges refused
  for every role), born-once arbiters with triggers off, every contention row of §7.4 raised ten ways
  and counted, crash points by forced rollback, lock-wait tests asserting the waiting statement (the
  R7 lesson) for L0, L3 and L6.
- **Architecture:** `LendingModuleIsolationTest`, `LendingSchemaHasNoMutableMoneyTest`,
  `NoSingleInstanceAssumptionRulesTest` (+4), `ColumnClassificationTest`, `RoutePermissionRegisterTest`,
  the `AccountPurpose` `CHECK` guard, `LendingBooksHaveOnePosterTest` (only lending's operations post
  to loan purposes; only capital recognition to `LENDING_CAPITAL`).
- **Proofs (continuous, gauged, alerting on non-zero):** `LoanSubledgerProof` — per account,
  `LOAN_INTEREST_ACCRUED` = Σ accruals − Σ billed interest − waived from accrued;
  `LOAN_INTEREST_DUE` = Σ billed − allocated − waived; `LOAN_PRINCIPAL_DUE` = Σ billed − allocated
  (± reversals, re-schedules); `LOAN_PRINCIPAL` = Σ disbursed / drawn − Σ billed − Σ paid down;
  `LOAN_FEES_DUE` = Σ assessed − allocated − waived; `LOAN_CREDIT_BALANCE` = Σ overpayments −
  applications − refunds; no due account negative; a `CLOSED` account's six balances zero.
  `LoanReplayProof` — re-runs the pinned engines over each agreement version and the ledger's history
  (schedule rows, every accrual's `posted_minor`, every billing and statement, every allocation):
  `IDENTICAL` or `DIVERGED`; re-verifies `terms_sha256`. `ExposureCensus` — per party, credit's
  reserved + lending's committed + outstanding never exceeds the limit its policy declared at the
  deciding instant. `LendingCapitalProof` (§12.9).

### 13.2 The ten mandatory scenarios (L12 — the owner's list, each a named test)

| # | Scenario | Test (task) | Asserts |
|---|---|---|---|
| 1 | The same disbursement command reaches two service instances | `LoanDisbursementDatabaseTest#theSameDisbursementOnTwoInstancesPostsOnce` (`-014`); `LoanPayoutDatabaseTest#tenDispatchersOneWithdrawal` (`-015`) | one entry, one `ACTIVE` edge, the wallet credited once; one withdrawal |
| 2 | The same repayment is received more than once | `RepaymentDatabaseTest#theSameRepaymentTwiceRepaysOnce` (`-018`); `AutoCollectionDatabaseTest#twoSweepersCollectOnce` (`-019`) | one repayment, one entry, the same answer replayed |
| 3 | Two repayments concurrently target the same instalment | `RepaymentDatabaseTest#twoRepaymentsOnOneInstalmentSerialiseAndConserve` (`-018`) | Σ allocations = Σ amounts; no due account negative; never over-allocated |
| 4 | An accrual job runs twice | `InterestAccrualDatabaseTest#theAccrualRunTwiceAccruesOnce` (+ ten sweepers, a crash mid-run) (`-016`) | one row and one entry per (account, date) |
| 5 | A provider succeeds but the response is lost | `LoanPayoutDatabaseTest#aLostResponseIsHeldThenConcludedByInquiryAndNeverResent` (`-015`) | the payout `DISPATCHED` while payments is `UNKNOWN`; no re-dispatch; concluded by inquiry; accrual starts at the conclusion; one withdrawal at the provider |
| 6 | A repayment is reversed after allocation | `RepaymentReversalDatabaseTest#aReversalAfterAllocationReopensWhatItPaid` (`-020`) | the entry swapped exactly; allocations negated by rows; later allocations untouched; the subledger proof zero |
| 7 | Payoff is requested while a repayment is processing | `PayoffDatabaseTest#payoffDuringARepaymentIsStaleOrClosedNeverWrong` (`-026`) | exactly one outcome in both orders; balances zero only after the payoff |
| 8 | A loan schedule is changed under an approved contractual operation | `AmendmentDatabaseTest#anApprovedAmendmentChangesTheScheduleOnce` (`-027`) | agreement and schedule v n+1 once; billed instalments kept; principal conserved; a moved agreement lapses it |
| 9 | A service crashes after ledger posting but before event publication | `LendingOutboxAtomicityDatabaseTest#aCrashBetweenPostingAndPublicationLosesNothing` (`-014`); the storm's crash points | rollback → neither; commit then relay killed → the event published once after restart |
| 10 | A loan is paid off while a scheduled delinquency worker is running | `PayoffDatabaseTest#payoffBesideTheDelinquencyWorkerWritesNoConditionAfterClosure` (`-026`) | serialised on L3; no condition written after `CLOSED`; DPD never computed on a closed account |

Also every `DELIVERY_PLAN.md` §11/§12 item: accrual idempotency across reruns and restarts (#4);
schedule totals exact with zero leakage (hermetic + `TST-002`); the early-settlement "rebate" zero by
construction; partial and over-payment allocation; delinquency transitions (current → each bucket →
cure → default → cure); leap year and month-end (31 Jan disbursement, 29 Feb accrual, repayment day
28 and the clamp); disbursement posted but payout fails (path X, `-015`); repayment for a closed loan;
clock skew across the accrual boundary.

### 13.3 Exit tests

- **`P11-TST-001` — the lending storm:** two application instances, clocks ±5 s, shared parties
  across both products; applications through credit's simulators; acceptances racing decisions for
  the other product; capital headroom contested; disbursements on both paths with payout faults
  (lost responses, failures, returns); draws, repayments, auto-collections, reversals, waivers,
  amendments, payoffs and statements; the servicing sweep across ≥ 3 real business-date boundaries
  (fixed-offset zones per cohort, A15); every one of the ten scenarios exercised; crash points incl.
  two killed backends. At rest, every round, one `REPEATABLE READ` snapshot: subledger proof zero for
  every account, replay `IDENTICAL` for every account, exposure census, capital proof, trial balance
  zero per currency, every accrual date present exactly once, one disbursement per non-cancelled
  loan, Σ allocations = Σ repayments, every act counted, gauges zero. `PASS` = three consecutive green
  runs and its probes caught.
- **`P11-TST-002` — the amortisation, accrual and statement battery:** ≥ 10,000 generated loans and
  ≥ 2,000 generated lines (amounts, rates incl. 0, terms, days, start dates across leap/non-leap years
  and month ends; draw and payment behaviour on a simulated calendar), every figure replayed
  `IDENTICAL` in two JVMs; a perturbed accrual, billing, statement, allocation or agreement byte flips
  the verdict.

### 13.4 Probes

Each task names the code mutation its tests must catch, recorded in `MUTATION_TESTING.md` §2 (drop
the accrual unique → scenario 4 red; read the projection for allocation → scenario 3 red under a
racing reversal; judge the accrual boundary on the instance clock → the skew test red; take the
wallet before the loan → `40P01` in scenario 3; consume without the profile lock → the serialisation
test red; skip the capital lock → two acceptances over-deploy; re-dispatch a payout on `UNKNOWN` →
scenario 5 red).

---

## 14. Failure scenarios

| Situation | Outcome |
|---|---|
| The request times out; the client retries | the same key replays the stored answer (`INV-IDEM-01`) |
| The database commits, the response is lost | the retry replays; nothing is done twice |
| A service crashes mid-step | one commit per step; the sweep re-drives from rows |
| An event is duplicated / late / missing | events are hints; the authoritative read decides; nothing depends on event timing |
| A provider is unavailable / returns an unknown state | payments' machinery holds the funds; the loan's receivable stands; interest waits (A30) |
| A webhook is duplicated | payments' and the inbox's dedupe; lending never consumes provider callbacks |
| Two requests race | §7.4: an arbiter per contention |
| Settlement arrives late | payments' expectation ages; lending unaffected |
| Reconciliation detects a break on a payout | payments' and Phase 8's resolution; the loan is unaffected; the funds are the borrower's |
| Capital contribution never arrives at the bank | the expectation ages out; the contribution lapses; no capital, no acceptance |
| Accrual job crashes mid-run / runs twice | born-once per date; catch-up; no double accrual |
| Repayment arrives for a closed loan | `409 lending.LoanNotRepayable`, nothing posted |
| Clock skew across the accrual boundary | the database clock under L3 decides |
| A payoff races a repayment / the delinquency worker | L3; the quote recomputed; terminal skipped |

---

## 15. Observability

| Series | Type | Tags | Alert |
|---|---|---|---|
| `finapp.lending.application` | counter | product, outcome | — |
| `finapp.lending.offer` | counter | product, outcome | — |
| `finapp.lending.acceptance` | counter | product, outcome | — |
| `finapp.lending.disbursement` | counter | path, outcome | — |
| `finapp.lending.disbursement.pending.age` | gauge (seconds, oldest) | — | above the disbursement deadline |
| `finapp.lending.payout` | counter | outcome | — |
| `finapp.lending.payout.dispatched.age` | gauge (seconds, oldest awaiting an outcome) | — | above payments' outcome objective |
| `finapp.lending.draw` | counter | outcome | — |
| `finapp.lending.accrual` | counter | outcome | — |
| `finapp.lending.accrual.lag` | gauge (seconds since the oldest unaccrued elapsed date) | — | above one day |
| `finapp.lending.billing` | counter | kind (instalment/statement), outcome | — |
| `finapp.lending.repayment` | counter | kind, outcome | — |
| `finapp.lending.collection` | counter | outcome | — |
| `finapp.lending.allocation.anomaly` | counter | — | above 0 |
| `finapp.lending.reversal` | counter | outcome | — |
| `finapp.lending.fee` | counter | kind, outcome | — |
| `finapp.lending.waiver` | counter | kind, outcome | — |
| `finapp.lending.payoff` | counter | outcome | — |
| `finapp.lending.amendment` | counter | outcome | — |
| `finapp.lending.delinquency` | gauge (account counts) | product, bucket | — |
| `finapp.lending.default` | gauge (count) | product | — |
| `finapp.lending.loans` | gauge (counts) | product, status | — |
| `finapp.lending.subledger.proof` | gauge | verdict | any failure |
| `finapp.lending.replay` | gauge | verdict | any `DIVERGED` |
| `finapp.lending.capital.proof` | gauge | verdict | any failure |
| `finapp.lending.capital.headroom.low` | gauge (currencies below the configured headroom floor) | — | above 0 |
| `finapp.lending.terms.active` | gauge (version) | product | none active for an offered product (enabled per environment — production's expected state until §1.1's gate opens) |
| `finapp.lending.origination.sweeper.enabled` | gauge | — | 0 in a non-test profile |
| `finapp.lending.disbursement.sweeper.enabled` | gauge | — | 0 in a non-test profile |
| `finapp.lending.payout.sweeper.enabled` | gauge | — | 0 in a non-test profile |
| `finapp.lending.servicing.sweeper.enabled` | gauge | — | 0 in a non-test profile |
| `finapp.credit.consumption` | counter | outcome | — |
| `finapp.credit.exposure.census` | gauge | verdict | any failure |

ADR-0072: counts, ages and verdicts only — never an amount, party or loan id in a tag. Portfolio
outstanding, delinquency sums, capital headroom amounts and schedule-vs-actual drift are **audited
reports**, not series. Spans per step linked by the account's correlation (the `CreditFlowScope`
precedent). A dashboard row and `infra/prometheus/rules/lending.yml`, resolved against a live scrape
(`P11-TSK-030`). Every series registered eagerly at startup with its closed tags.

---

## 16. Milestones

| Milestone | Acceptance | Tasks |
|---|---|---|
| **M11.1 Foundations** | consumption owned by credit; the module, the chart, lending capital recognised and proven, terms versions | `-001`…`-005` |
| **M11.2 Engines** | schedule, accrual, allocation and statement engines exact and deterministic | `-006`…`-009` |
| **M11.3 Origination** | application → offer → acceptance, exposure counted, capital committed, across instances | `-010`…`-013` |
| **M11.4 Disbursement** | loans born once, unambiguously, to the wallet and to an external account | `-014`, `-015` |
| **M11.5 Loan servicing** | accrual, billing, repayment, auto-collection, reversal | `-016`…`-020` |
| **M11.6 Revolving credit line** | draws, statements and minimum payment, repayment and closure | `-021`…`-023` |
| **M11.7 Delinquency, fees and change** | conditions derived; fees once; waivers four-eyes; payoff; restructuring; prepayment (if not cut) | `-024`…`-028` |
| **M11.8 Proof and operations** | every account proven and replayed; operable | `-029`, `-030` |
| **M11.9 Exit** | the storm, the battery, the review | `P11-TST-001`, `P11-TST-002`, `P11-DOC-001` |

**Cut order** if the phase must shrink: `-028` (partial prepayment) first, then the late fee half of
`-025` (the fee lifecycle stays exercised by the origination fee and the waivers). Each deferral is
recorded with its owner. **Never cut:** the consumption port, the exposure read, lending capital, both
disbursement paths, the credit line, the accrual uniqueness, the allocation engine, the reversal,
payoff, restructuring (scenario 8), the proofs, the storm, the battery.

## 17. What Phase 11 must NOT implement

BNPL, merchant-financed agreements (Phase 12); refinance (owner: the Phase 11 → 12 transition);
write-off, charge-off, provisioning, IFRS 9 staging and the GL (Phase 14 — the write-off account is
seeded, unposted); collections operations and case management (Phase 13); real bureau connectivity
(unresolved #13/#14); notifications (a later module); consumer-credit-law features — statutory APR,
withdrawal rights, statutory notices, caps (L4); penalty or default interest, prepayment fees,
acceleration, interest on interest (L8, §12.7); variable rates; external inbound repayment rails
(A11); capital return or income recycling (A29); the evidence and agreement purge (Phase 15). A
reviewer finding any of it in a Phase 11 change refuses the change.

## 18. Risks

- **Double accrual or rounding leakage** — born-once per date, cumulative rounding, the battery.
- **Exposure under-counted** — consumption under L0, the one-statement read, an open line's limit,
  the census.
- **Capital over-deployed or unbacked wallet money** — headroom under L6, recognition only from bank
  evidence, the capital proof.
- **A payout's ambiguity leaking into the loan** — the wallet as transit, payments' machinery, lending
  reading never called, interest from the conclusion.
- **The capital row as a hot row** — measured in the storm; tranches in Phase 16 if needed.
- **Production cannot originate** (§1.1) — accepted and recorded; the activation runbook is the gate.
- **Two products in one phase** — one account model and one set of engines; the line adds only draws,
  statements and closure.

## 19. The first task

**`P11-TSK-001` — Credit decision consumption port** (`READY`). The credit-owned
`CreditDecisionConsumptions` port and `JdbcCreditDecisionConsumptions`; credit `V021` (the consumer
columns and their unique, the `SECURITY DEFINER` `credit.consume_decision`, the application role's
`INSERT` revoked and a trigger refusing any other insert path); `ConsumerKind { LOAN_AGREEMENT }`;
the audit act `credit.DecisionConsumed`; `finapp.credit.consumption{outcome}`; the counted races (ten
consumers, consume vs decide for one party, the `valid_until` boundary under ±5 s skew); no lending
code. Its entry in `BACKLOG.md` carries every field.

## 20. Exit criteria (summary — the gate is `PHASE_GATES.md` §5 Phase 11)

The five original criteria, made measurable, and one block per area — loan lifecycle, offer and
contract, disbursement, schedule, interest and fees, allocation, payoff, delinquency, ledger, credit
line, capital and exposure, multi-instance, idempotency, failure recovery, security and audit,
observability, testing, documentation — each with its evidence, are written into `PHASE_GATES.md`
§5 Phase 11 by this transition.
