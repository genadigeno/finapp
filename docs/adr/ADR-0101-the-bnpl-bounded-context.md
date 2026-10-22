# ADR-0101 — The BNPL bounded context: `bnpl` owns the agreement, purchase, financing and servicing facts and none of the money; lending's pure engines shared through `creditmath`, lending's `loan` not composed

Status: Accepted (2026-10-22, `P12-DOC-001` — read against the code and corrected first)
Date: 2026-10-16
Phase: 12
Context: BNPL · Lending · Credit · Merchant · Checkout · Payments · Ledger · Platform
Supersedes: nothing. Applies ADR-0001 and ADR-0012 (a modular monolith, contexts mapped to modules
deliberately), ADR-0006 (boundaries enforced mechanically), ADR-0002 and ADR-0009 (the journal is
the record, balances are derived), ADR-0043 (a money effect posts in its owner's transaction),
ADR-0053 (the checkout session and its order), ADR-0090 (lending owns the contract and none of the
money, and none of the merchant) and ADR-0091 (decision consumption through credit's port). Rests on
`PHASE_12_PLAN.md` §1, §1.1, §2.1, §3, §7.1–§7.3, §12.13 and `INV-BNPL-01`, `-02`, `-03`.

## Context

1. **One economic event, two financial flows.** A BNPL purchase is a merchant financing (the
   platform owes the merchant the price less its discount, a liability later paid out by the
   merchant's existing payout) and a customer obligation (the customer owes the platform the price,
   plus interest for pay-in-N, a receivable funded from `LENDING_CAPITAL`). `GLOSSARY.md` §Customer
   Payment / Merchant Settlement and `DELIVERY_PLAN.md` §Phase 12 forbid collapsing them; the delivery
   plan names the risk "modelling BNPL as an unsecured loan and ignoring the merchant leg".
2. **Lending already services credit, proven.** Phase 11 is `COMPLETE` after its gate's repairs:
   accrual, billing, allocation, auto-collection, DPD, late fees, payoff, reversals, the subledger,
   replay and capital proofs, the exposure view. Composing it would look free.
3. **Lending's arithmetic is pure, its state is not.** `ScheduleEngineV1`, `AccrualEngineV1`
   (`AccrualTerms`) and `AllocationEngineV1` (`AllocationRules`, `DueState`) are static and
   deterministic; the last two take product-agnostic records. But lending's package is flat (no
   engine sub-package to fence), `LendingModuleIsolationTest` pins "no module depends on lending",
   and every stateful service and proof is bound to `lending.loan`. `RepaymentFrequency` is `MONTHLY`
   only and `LoanProduct` excludes BNPL by design (`LoanProductTest`; `lending/package-info.java`:
   bnpl references lending's concepts by identifier and owns neither, lending never knows a merchant).
4. **The neighbours' seams exist and are narrow.** Checkout chooses the payment at confirmation
   (`ConfirmSessionRequest.instrument ∈ {"WALLET", null}` — where `"BNPL"` plugs in); its completion
   is keyed by a payment intent and runs inside the capture's transaction after the payable's rows.
   Payments' book rail locks the customer's wallet and the merchant's payable as a pair in
   `UUID.compareTo` order (`PaymentOutcomes.lockPairInFixedOrder`). Merchant refunds are operator-only
   today and payouts are on demand (`MerchantPayouts`).
5. **Production cannot originate** (plan §1.1, inherited from Phase 11 §1.1 / L11): no production
   credit decision can be `APPROVED` until unresolved questions #13 and #14 are answered.

## Decision

1. **One new module, `bnpl`, one bounded context.** Schema `bnpl`, Flyway `bnpl/V001…`,
   `REVOKE ALL FROM PUBLIC`. It owns, as separate aggregates or born-once facts: the BNPL
   application, the terms version, the offer, the versioned agreement and its acceptance evidence,
   the purchase (the financed commercial fact — distinct from checkout's order and from any payment),
   the merchant financing record, the customer obligation, its schedule versions and instalments,
   billings, accruals (pay-in-N), repayments and allocations, collection attempts, refunds and refund
   adjustments, disputes, conditions, late fees, payoff quotes and executions, waivers and repayment
   reversals. Its ownership table is `PHASE_12_PLAN.md` §3.1; nothing in it is co-owned.

2. **None of the money.** The `bnpl_obligation` row holds identity, product, status and permits — no
   amount, rate or balance column. Every changing amount is a ledger balance of a per-obligation
   account under `OwnerKind.BNPL_OBLIGATION` (ADR-0103); every operational amount is an
   `INSERT`-only, born-once row proven equal to the ledger by `BnplSubledgerProof` (`INV-BNPL-01`).
   Held mechanically by `BnplSchemaHasNoMutableMoneyTest` (lending's test re-applied): every
   `*_minor` column on an `INSERT`-only table, `SELECT, INSERT` on facts, a conditional `UPDATE` of
   status and permit columns only on machine tables, never `DELETE`.

3. **What `bnpl` does not own.** The session and order stay checkout's; the merchant, its fee
   schedules, BNPL enablement and payouts stay merchant's; the payable's position stays the ledger's
   (`INV-MER-02`); eligibility, the decision and the exposure judgement stay credit's (`INV-CRD-04`);
   the `LENDING_CAPITAL` row and its headroom stay lending's (ADR-0104); the wallet stays accounts' and
   the ledger's, reached through `BnplWallets`. BNPL calls no provider and adds no external evidence.

4. **Lending's `loan` is not composed as the servicing back-end** — five reasons, each read in the
   Phase 11 design (plan §3.2, alternative 3):
   - *Birth and disbursement.* A loan is born `PENDING_DISBURSEMENT` and its receivable is born with a
     wallet credit (`INV-LND-07`). A BNPL receivable is born with a merchant-payable credit and a fee
     line: either lending posts to `MERCHANT_PAYABLE` (merchant economics in lending, a second poster
     of the payable) or two modules post halves of one event through a transit account whose zero
     must be proven — both weaken `INV-LND-07` and ADR-0090's "lending owns none of the merchant".
   - *The refund is not a lending act.* Lending changes a schedule only by a four-eyes amendment the
     customer accepts (ADR-0099) or a prepayment; a merchant refund is a third party's automatic,
     non-cash reduction of the debt, funded by a merchant clawback, with a pro-rated fee return — an
     act lending's machines (`INV-LND-11`) do not admit.
   - *Pay-in-4 is not a lending product.* Monthly only, a first due date ≥ `min_first_period_days`
     after the accrual start, and P4 has no accrual and collects instalment 1 in the origination
     transaction. Fitting it would change lending's engines, vocabularies and `CHECK`s after their
     exit gate.
   - *Disputes.* A BNPL dispute holds servicing on part of the debt pending the merchant — the
     servicing hold lending designed (Phase 11 A9) and never built, for a reason not lending's.
   - *Ownership.* `MODULE_ARCHITECTURE.md`'s note "Instalment was claimed by two modules" already
     split the BNPL Instalment from the loan instalment: they share a word, not a lifecycle.

   Lending's servicing *patterns* — born-once rows and their triggers, the one-statement `SKIP LOCKED`
   sweep with a database-stamped permit, derived append-only conditions, the late fee once per item,
   four-eyes corrections, payoff, the subledger and replay proofs, the R1 value-date floor and the R2
   catch-up application — are re-applied in `bnpl`'s own schema and code. The shape is reused; the
   rows are not shared.

5. **The arithmetic is shared through a new pure module, `creditmath`** (option (b), OQ-2, owner
   accepted default under B8). `P12-TSK-002` extracts lending's engines and their records —
   `AllocationEngineV1`, `AllocationRules`, `DueState`, `AccrualEngineV1`, `AccrualTerms`,
   `AccrualPeriod`, `InterestAccrual`, `ScheduleEngineV1`, `Schedule`, `Act365Fixed` and what they
   need — unchanged, into `creditmath`, which depends on `sharedkernel` only.
   `ScheduleEngineV1` gains a product-agnostic `ScheduleTerms` entry; its `LoanTerms` entries stay on
   lending's side of the edge (creditmath cannot see a lending type), delegating to it. Every
   `EngineVersion` identity (name and number) and every golden hash is unchanged, so every Phase 11
   agreement replays `IDENTICAL` — proven by re-running `P11-TST-002`'s battery and `LoanReplayProof`
   in the extracting task. BNPL can therefore never reach a lending store. The P4 engine is new and
   BNPL's (ADR-0102).

6. **Build edges: `bnpl → creditmath, ledger, platform, sharedkernel`; `lending → creditmath`.** No
   module depends on `bnpl`. `BnplModuleIsolationTest` and `CreditmathModuleIsolationTest` pin both
   directions with planted probes; `LendingModuleIsolationTest` keeps "no module depends on lending".

7. **Everything else through ports `bnpl` declares and `app` implements**, each in the caller's unit
   of work (the crossborder and lending precedent):

   | Port | Over | Used by |
   |---|---|---|
   | `BnplCreditDecisions` | `open`, `cancel`, `decisionFor`, `consume(uow, decisionId, ConsumerRef(BNPL_AGREEMENT, agreementId))`, `lockExposure` (L0) | application, offer, acceptance, a principal-re-instating reversal |
   | `BnplPartyStanding` | `CreditPartyStanding` | application, acceptance |
   | `BnplWallets` | `existingWallet(uow, party, currency)` — the customer's existing `CUSTOMER_WALLET` ledger account in exactly that currency, never opened (`app`'s `AccountsBnplWallets`) | acceptance (P4 instalment 1), repayment, collection, closure, refund, payoff, waiver |
   | `BnplCheckoutSessions` | `read` (by the wrapped token) and `byRef`, unlocked; `lockForPurchase` (B3, judged at once); `completeByFinancing` (`app`'s `CheckoutBnplSessions` over `CheckoutSessions.lockForFinancing` and `completedByFinancing`) | application, offer, acceptance |
   | `BnplMerchants` | `standing` (`ACTIVE`, BNPL-enabled; `merchant` read `FOR SHARE` as `requireTrading` does), `coversFee`, `payableAccount` (the `MERCHANT_PAYABLE` account), `pinFee` (the BNPL fee schedule version pinned and the split, `MerchantFeeSplit`) and `returnedFee` (`app`'s `MerchantBnplMerchants` over merchant's `BnplPricing`) | application, acceptance, refund |
   | `BnplCapital` | `underLock(uow, currency)` → `BnplHeadroom` (L6), over `LendingCapitalHeadroom.underLock`, extended to the BNPL purposes (`app`'s `LendingBnplCapital`) | acceptance, reversal (ADR-0104) |
   | `BnplProductBounds` | credit's `CreditProduct` amount and term bounds (`app`'s `CreditBnplProductBounds`) | the terms proposal's amortisation judgement (ADR-0102) |
   | `BnplActingParty` | identity's party of an acting person (`app`'s `IdentityActingParty`, shared with lending and credit) | the operator refund door's self-dealing refusal (ADR-0105) |
   | `BnplProofReads` | the merchant payable leg, the trial balance, the capital and exposure censuses (`app`'s `LedgerBnplProofReads`) | the three-way proof (ADR-0103) |

   Plus the one-transaction seam, which is `app`'s `BnplTransactions` (`inTransaction`, and
   `inSnapshot` for the proofs): every bnpl act takes the caller's `Connection` as its unit of work, so
   no `BnplUnitOfWork` type was declared. No score, attribute, threshold or policy figure crosses into
   `bnpl`; no customer credit datum crosses to a merchant (only `ELIGIBLE`, `NOT_ELIGIBLE`, `PENDING`).
   *(Corrected by `P12-DOC-001`, 2026-10-22: this table read "`BnplWallets` | … existing only for
   collection; resolved or opened for a refund excess", "`BnplCheckoutSessions` | read the session; in
   T-pur lock it `FOR UPDATE` and complete it …", the merchant and capital rows without their methods,
   and "`BnplUnitOfWork` | the one-transaction seam | every act". As built the wallet is never opened —
   a refund's excess with no existing wallet stays as the obligation's credit balance while it is
   `ACTIVE` and is refused on a `CLOSED` one (ADR-0105); no `BnplUnitOfWork` port exists; and the last
   three rows were built (`P12-TSK-004`, `-021`, `-025`) without being listed. `BnplCreditDecisions` also
   carries `reasonTexts` (a decided request's customer-readable reasons) and `requestOf` (the decision
   event's hint resolved to its request) — neither a score, attribute or threshold.)*
   *(Corrected by the Phase 12 → 13 transition gate, 2026-10-22: the "Used by" column is incomplete;
   read it as the call sites in `bnpl`'s main code show. **`BnplCreditDecisions`**: the application
   (`open`, `cancel`, `reasonTexts`), the offer step (`requestOf`, `decisionFor`, `cancel`), the
   acceptance (`lockExposure` L0, `consume`) and **every** repayment-reversal approval — L0 is taken
   first on each approval, not only on a principal-re-instating one; only L6 (`BnplCapital`) is
   conditional on principal re-instated. **`BnplCheckoutSessions`**: also the session's options read
   (`BnplOptions`, `read`), and the offer step's `byRef`. **`BnplMerchants`**: also the options read
   (`standing`, `coversFee`), the dispute's opening (`payableAccount`, the payable alone at (P)), the
   record store behind the proofs (`payableAccount`) and the three-way proof (`returnedFee`).
   **`BnplProductBounds`**: also the application and the options read (the gross within the product's
   bounds). **`BnplActingParty`**: every self-dealing refusal, not the operator refund door alone — the
   dispute's operator resolution and its four-eyes approval and rejection (`BnplDisputes`), the
   repayment reversal's proposal, approval and rejection, the waiver's proposal, approval and rejection
   (`BnplWaivers`, through `BnplRepaymentReversals.judgeSelfDealing`), and the investigation doors
   (`BnplExplanations`). The other rows stand as written.)*

8. **The neighbours' changes are theirs, each by a named task.** Credit `V022` (`P12-TSK-001`:
   products, the term shape, `ConsumerKind.BNPL_AGREEMENT`); identity `V022` (`-002`: permissions and
   roles); ledger `V027` (`-003`: the owner kind and purposes); lending, Java only (`-002`: the
   extraction; `-005`: headroom and `LendingCapitalProof` sum the BNPL principal purposes — a
   ledger-purpose read, so lending learns nothing of `bnpl`); merchant `V010` (`-006`: enablement, the
   BNPL fee schedule, `bnpl_fee_pin`); checkout `V004` (`-007`: the edge `OPEN → COMPLETED` by
   financing, `financed_by_ref` set once); `app`'s `PlatformCreditExposure` v3 (`-012`).
   *(As built (`P12-DOC-001`, 2026-10-22): a sixth neighbour migration, merchant `V011` (`P12-TSK-021`:
   the merchant API key's `BNPL_REFUND` scope, ADR-0105), was added by the refund task; exposure v3 is
   `app`'s `BnplCreditExposure`, credit's `PlatformCreditExposure` read over `bnpl V007`'s definer view.)*

9. **The purchase is one transaction.** Acceptance, consumption, capital commitment, agreement v1,
   purchase, financing, obligation, accounts, the financing entry, (P4) instalment 1's collection and
   the session's completion commit together or not at all (`INV-BNPL-02`, T-pur). "Merchant settled
   but agreement creation fails" cannot occur, so no compensating path is built; a forced failure at
   every step leaves nothing (`P12-TSK-013`).

10. **The Phase 12 lock order** (plan §7.2, registered in `DISTRIBUTED_EXECUTION.md` §3 as "The Phase
    12 lock order"). One order for every bnpl writer holding more than one of these; the positions are
    the plan's labels, written in parentheses here to keep them apart from the owner decisions B1–B8:
    **(L0)** the party's `credit_profile` row `FOR UPDATE` — first, and only where exposure rises
    (acceptance, repayment reversal); **(B1)** `bnpl_application`; **(B2)** `bnpl_offer`; **(B3)** the
    checkout session row, through checkout's port, only in T-pur and **judged at once** — not `OPEN`,
    or expired, is refused `409 bnpl.SessionNotPayable` before any further lock; **(B5)** the
    `purchase` row (the refund and dispute bound); **(B4)** the `bnpl_obligation` row — the
    serialisation point of every servicing act on one obligation; **(B6)** the act's own row (dispute,
    proposal, quote); **(L6)** the currency's `LENDING_CAPITAL`; **(P)** the merchant's
    `MERCHANT_PAYABLE` and the customer's `CUSTOMER_WALLET` `ledger_account` rows `FOR UPDATE`, **taken
    together as one element in `UUID.compareTo` order of the two account ids** — a transaction needing
    only one takes it at this position; **(L8)** projections through `lockBalancesInOrder`, postings
    last, the currency's shared `BNPL_MERCHANT_FEE_INCOME`, `BNPL_FEE_INCOME` and
    `BNPL_INTEREST_INCOME` rows inside that one sorted call. Then only inserts. B5 precedes B4: a
    refund and a dispute lock the purchase, then its obligation; servicing takes only B4, so no writer
    holding B4 waits on B5.
    *(As built (`P12-DOC-001`, 2026-10-22), three refinements, none changing the order: **B6** is taken
    `FOR UPDATE` on the dispute (T-dsp), the dispute proposal and the repayment-reversal proposal
    (T-rev); the waiver's proposal is `INSERT`-only and read under B4, its decision a born-once row; the
    payoff **quote** is never locked — it is immutable and read plainly, the execution's uniques beneath
    B4 arbitrating (T-pay) — so "(B6) … quote" above is not taken. **B4** is taken in a statement of its
    own and the agreement version in force read by the next one (`P12-TST-001`, 2026-10-21: a taker that
    locked and read in one statement, queued behind a refund writing agreement version n+1, read version n
    and was refused as a `500`). **The obligation's own projection rows** — written only by holders of its
    B4 (T-pur creating them, T-acc, T-svc, T-rep, T-ref, T-pay) — are taken by T-svc's collection and
    closure and by T-pay before (P), after B4: a step holding them while it waits on a wallet waits on
    nothing that can wait on it; every currency-wide income row stays inside the L8 call after (P).
    `DISTRIBUTED_EXECUTION.md` §3's lock-order row records all three.)*

11. **No shared income row before (P).** Pay-in-N accrual is its own transaction, **T-acc** — B4,
    the accrual postings, commit, no wallet. Any act needing the record caught up (servicing,
    repayment, refund, payoff) runs T-acc first and then verifies under B4 that every elapsed date is
    accrued, writing nothing otherwise. Every other income posting (a refund releasing interest or
    fees, a waiver, the late fee) is at L8, after the wallet and the payable. No bnpl transaction
    holds a currency-wide income row while waiting on a wallet or a payable — the Phase 11 gate's
    A2-F1 (ADR-0096's Follow-up) designed out, not inherited; lending's `LOAN_INTEREST_INCOME` → L7
    exception has no BNPL analogue.

12. **Why (P) is one element, and why there is no cycle** (plan §7.3, each counted by `P12-TSK-013`
    and `-021` in both id orders):
    - *The pair.* The draft proposed the payable before the wallet ("M1 then L7") and asked whether
      a wallet-paid capture ever takes the payable first. It does: `dispatchBook` (inside
      `PaymentConfirmation`'s transaction) and `PaymentRefund`'s book arm lock `{wallet, payable}` by
      `lockPairInFixedOrder`'s `UUID.compareTo`, so for half of all (customer, merchant) pairs the
      payable is first. A fixed payable-then-wallet order in BNPL would deadlock (`40P01`) with a
      wallet-paid capture by the same customer at the same merchant whenever the wallet's id sorts
      first. Taking the pair in payments' own order makes one total order over exactly the rows both
      modules touch.
    - *Merchant and lending.* Merchant's payout dispatch locks the payable alone, holds and commits
      before the wire; merchant-bound card captures and refunds take the payable and a card clearing,
      not a wallet; none waits on a bnpl row. Lending never takes the payable; its L6 → L7 and BNPL's
      L6 → (P) agree, and L6 is taken by both modules before any wallet or payable row.
    - *The session.* Checkout's capture completion takes the session after the payable — the reverse
      of B3 → (P) — yet no cycle closes: a capture completes only a session already
      `PAYMENT_PENDING`, T-pur refuses a session not `OPEN` before taking (P), and while a session is
      `OPEN` no capture can be completing it. The expiry sweeper locks the session alone.
    - *Credit* never waits on a bnpl lock (it reads bnpl's definer view in one statement); payments'
      writers never touch bnpl; no bnpl transaction is open across a provider call — it calls none.

13. **What `bnpl` never does.** Write another module's table (consumption is credit's port,
    ADR-0091); read a score, attribute or threshold; judge exposure; create a payment intent or
    attempt, post a capture, call a provider or pay a merchant out; create a `loan` or post a `LOAN_*`
    purpose; post except through `PostingService` and `ReversalService` in its own transaction, and
    never through `AdjustmentService` on a BNPL account; consume an event for correctness
    (`CreditDecisionRecorded` and checkout's session events are hints — the sweep reads rows).

14. **Production ships closed.** A product is offered only while an `ACTIVE`, four-eyes BNPL terms
    version exists for it, never migration-activated (ADR-0102); production activates none
    (`422 bnpl.ProductNotOffered`). Every exit criterion is proven against the simulators, the storm
    and the battery; `OPERATIONS_RUNBOOK.md` gains the BNPL activation preconditions — lending's, plus
    legal review of the merchant agreement and the consumer terms and an approved merchant-fee
    schedule. An accepted limitation, attached to the debt row "Lending cannot originate in production
    (#13, #14)".

## Alternatives Considered

### BNPL as a payment-method flag (a "BNPL rail" in payments)
Pros:
- Checkout's existing confirmation, capture and completion path; no new module.

Cons:
- Loses the agreement, the receivable, the capital, the refund policy and the credit decision. A
  card capture credits the merchant from money a network will settle; a BNPL purchase credits it from
  the platform's capital against a receivable. A rail would open a clearing position
  (`INV-RAIL-04`) that never settles.

Refused: BNPL completes the session through checkout's completion seam, not a payment attempt.

### BNPL as an unstructured loan subtype (a `PERSONAL_LOAN` disbursed to the merchant)
Refused: the delivery plan's named risk — the merchant leg, its fee and its refunds disappear.

### `bnpl` composes lending as the servicing back-end
Pros:
- Accrual, billing, collection, DPD, late fees, payoff, reversals, the proofs, the exposure view and
  the capital headroom for free.

Cons:
- The five reasons of decision 4: `INV-LND-07` weakened, a non-lending act in lending's machines,
  lending's engines and `CHECK`s changed after their exit gate, a hold built for another context,
  and the instalment the architecture already split re-merged.

Refused.

### A `bnpl → lending` build edge restricted to the engine types (option (a))
Pros:
- No extraction; nothing in lending moves.

Cons:
- Breaks the rule `LendingModuleIsolationTest` pins; with a flat package, an architecture test
  allow-listing types is all that keeps `bnpl` from lending's stores.

Refused (OQ-2, owner accepted default).

### Copy the engines into `bnpl`
Refused: two copies of one arithmetic drift, and a fix in one is a silent divergence in the other.

### The payable before the wallet as two fixed positions (the draft's "M1 then L7")
Refused: deadlocks with payments' book rail for half of all (customer, merchant) pairs (decision 12).

### A BNPL microservice
Refused by ADR-0001: consumption, capital commitment, financing and the session's completion must
commit atomically; extraction is Phase 16's question, answered by measurement.

## Consequences

Positive:
- Every BNPL figure is explainable from records: agreement version → engine version → born-once row
  → journal entry → derived balance; the merchant leg is in the obligation's own entry.
- The arithmetic is one code base for lending and BNPL, and BNPL cannot reach lending's state.
- The lock order is one total order across bnpl, payments, merchant, checkout and lending.

Negative:
- Lending's patterns are re-applied in a second schema — real work, each pattern already documented
  with its races and probes; the machines are smaller (no disbursement, no payout, no line).
- Nine ports and the unit of work in `app`; six neighbour migrations (credit `V022`, identity
  `V022`, ledger `V027`, merchant `V010` and `V011`, checkout `V004`), lending's Java and `app`'s
  exposure v3 make the phase wide. *(Corrected by `P12-DOC-001`, 2026-10-22: this read "Six ports and
  the unit of work in `app`; five neighbour migrations (credit, identity, ledger, merchant, checkout) …
  make the first two milestones wide" — decision 7's three unlisted ports and merchant `V011`, built by
  `P12-TSK-021`, were not counted.)*
- The extraction touches lending after its exit gate; only a byte-identical replay justifies it.
- Production originates nothing in Phase 12 — accepted (decision 14).

Operational impact: three leaderless schedules (`BnplOriginationSchedule`, `BnplServicingSchedule`,
`BnplDisputeSchedule`; `LEASE_PROTECTED_SCHEDULERS` 26 → 29); advisory namespace `12` reserved for
bnpl's administrations.
Security impact: five permissions and two roles (identity `V022`, plan §11); merchant-key reads and
refunds tenant-isolated (`INV-MER-01`); no credit datum reaches a merchant.
Financial impact: BNPL originates and services credit funded from lending capital; it holds no
balance of its own and every money effect is one journal entry in its own transaction.

## Invariants / Constraints

`INV-BNPL-01` (figures are ledger balances), `INV-BNPL-02` (financing and obligation atomic),
`INV-BNPL-03` (one decision, one session, one financing), `INV-BNPL-10` (counted from the commit),
`INV-LED-04`, `INV-BAL-05`, `INV-CRD-04`, `INV-LND-07` (not weakened), `INV-LND-13`, `INV-MER-01`,
`INV-MER-02`, `INV-RAIL-03`, `INV-RAIL-04`, `INV-LIFE-01`…`04`, ADR-0001, ADR-0006, ADR-0090.

## Follow-up

- Built by `P12-TSK-002` (the module, `bnpl V001`'s floor, the isolation tests, identity `V022`, the
  `creditmath` extraction with the battery and replay re-run), the ports landing in the tasks that
  first use them (`-005` `BnplCapital`, `-006` `BnplMerchants`, `-007` `BnplCheckoutSessions`, `-010`
  `BnplCreditDecisions` and `BnplPartyStanding`, `-018` `BnplWallets`) *(corrected by `P12-DOC-001`,
  2026-10-22: `BnplWallets` landed with `-013`, the acceptance's instalment 1, not `-018`; the unlisted
  ports landed with `-004` `BnplProductBounds`, `-021` `BnplActingParty` and `-025` `BnplProofReads`)*.
  `BOUNDED_CONTEXTS.md` and
  `MODULE_ARCHITECTURE.md` carry the planned entries; each building task corrects them as built.
  - *Built by `P12-TSK-002` (2026-10-16):* `creditmath` (decision 5) and `bnpl`'s boundary — the
    module, `bnpl V001` (the migrator-owned floor, `USAGE` alone to the application role, no default
    privileges, no tables), the edges of decision 6 pinned by `BnplModuleIsolationTest` and
    `CreditmathModuleIsolationTest` with planted probes and every other module's isolation test
    refusing both, `BnplSchemaHasNoMutableMoneyTest` (decision 2's mechanical hold) through the one
    catalogue judgement lending's guard now shares (`platform`'s test fixture `MoneyColumnFloor`), the
    closed vocabularies and identity `V022`.
- `P12-TSK-002`'s design names where `EngineVersion` and `LendingEngine` land and how lending's
  `LoanTerms` entries (and `judgeAmortisation` over `LoanProductBounds`) delegate; nothing it names
  may change a member's name, number or a golden hash.
  - *Named and built (`P12-TSK-002`, 2026-10-16):* `EngineVersion` and `LendingEngine` moved **whole**
    to `creditmath` — all four members, names and numbers unchanged, `STATEMENT_ENGINE_V1` included
    although `StatementEngineV1` stays in lending (one registry of the versions an agreement pins; a
    split would make lending map between two enums for one persisted name); bnpl's own engines are
    `bnpl`'s `BnplEngine`, never members of it. The `LoanTerms` entries are lending's `LoanEngines`:
    `accrualTerms` and `allocationRules` (the records' former `of(LoanTerms)`), and `project`,
    `recalculate`, `recalculateAtInstalment` and `judgeAmortisation(LoanTerms, LoanProductBounds.Bounds)`,
    each judging the product's kind, then `ScheduleEngineV1.conventions(ScheduleTerms)`, then the rate
    type and frequency — the engine's former order and words — and delegating to the `ScheduleTerms`
    entries; `judgeAmortisation` now reads the allowed days, the smallest principal and the longest
    term as arguments. Proven behaviour-preserving: `P11-TST-002`'s battery re-run with its population
    fingerprint (`b833918a…a433`) and two-JVM totals byte-identical before and after, 12,000 accounts
    `IDENTICAL`; the moved engine suites green with every golden figure unedited; `LoanEnginesTest`
    holding every entry to the engine's answer. Refinements made in the building: `AllocationAnomaly`
    stays in lending (a defect of lending's rows against its ledger, not an engine's); the backlog's
    `PurchaseStatus` is `BnplPurchaseStatus` (and the id `BnplPurchaseId`) — the plan's canonical name
    "BNPL Purchase", §3.1; the application's closure reasons are `BNPL_LIFECYCLES.md` §4.2's seven, the
    backlog's `REQUEST_CLOSED` read as its three `CREDIT_REQUEST_*` reasons; `ScheduleTerms` is a terms
    record (the `AccrualTerms` shape) and the principal, term and dates stay arguments, so each
    `LoanTerms` entry delegates argument for argument.
- `P12-TSK-013` and `-021` count the (P) race against a wallet-paid capture and refund in both id
  orders; `P12-TST-001` asserts lock waits on L0, B3, B5, B4, L6 and (P) and measures the shared
  capital and fee-income rows (plan §21).
  - *As built (`P12-TST-001`, 2026-10-21; recorded by `P12-DOC-001`):* every named lock wait — L0, B3,
    B5, B4, L6 and (P) in both id orders — seen as the act's own backend and counted
    (`BnplStormLocks`, `BnplStormCatalogue`), 0 deadlocks; the hot rows measured on the final run:
    `LENDING_CAPITAL` 65 waits (p95 142 ms), the shared `BNPL_MERCHANT_FEE_INCOME`, `BNPL_FEE_INCOME`
    and `BNPL_INTEREST_INCOME` rows 12, 0 and 0.
- **Acceptance.** **As built** (`P12-DOC-001`, 2026-10-22): `Proposed` at the Phase 11 → 12 transition
  (2026-10-16); the Phase 12 review read this ADR against the code, corrected it in place above with dated
  notes, and accepted it.
- **The activation gate, written (`P12-TSK-026`, 2026-10-20).** `PHASE_12_PLAN.md` §1.1's closed production state and its
  preconditions are `OPERATIONS_RUNBOOK.md` §8.1, in order, each a person's recorded act: lending's §7.4 preconditions
  (#13 and #14 answered, a real bureau adapter, a credit policy reading it, lending capital recognised), legal review of
  the merchant agreement and of the consumer terms, the merchant-fee schedule approved and assigned, a four-eyes BNPL terms
  activation - and, last, the scrape targets labelled `bnpl_offered="true"`, which arms `BnplTermsNoneActive`
  (`infra/prometheus/rules/bnpl.yml`).
