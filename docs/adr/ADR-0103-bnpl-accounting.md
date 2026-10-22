# ADR-0103 — BNPL accounting: per-obligation accounts, the financing entry, the merchant discount and the posting rules

Status: Accepted (2026-10-22, `P12-DOC-001` — read against the code and corrected first)
Date: 2026-10-16
Phase: 12
Context: BNPL · Ledger · Merchant · Lending · Reconciliation
Supersedes: nothing. Applies ADR-0002 (the journal is the record), ADR-0040 (a flat account with a
typed classification), ADR-0041 and ADR-0009 (balances derived, the projection never decides),
ADR-0042 (wallet, ledger account and operational account are distinct), ADR-0043 (posting in the
owner's transaction), ADR-0051 and ADR-0054 (the merchant payable, payouts and refunds funded by
their net), ADR-0058 (a sale must cover its fee) and ADR-0096 (loan accounting — the shape this
mirrors). Rests on `PHASE_12_PLAN.md` §2.3 (A8–A12), §3.1, §12.4–§12.6, §12.11, §12.12, §24
(OQ-11, -13, -14, -16, -19) and `INV-BNPL-01`, `-04`, `-06`, `-09`.

## Context

1. **An obligation is several claims, not one** (ADR-0096's reasoning). Principal not yet due,
   principal billed, interest earned, interest billed, fees billed and money owed back to the
   customer behave differently for delinquency, income, refunds and payoff.
2. **The financing creates a liability without cash.** Crediting the merchant's payable for a
   purchase no network will settle raises what the platform owes merchants with no cash entering;
   the receivable that balances it is funded from lending capital, bound by the headroom invariant
   (ADR-0104), as a disbursement's wallet credit is (ADR-0096 §Context 3).
3. **The merchant payable already has a shape and readers.** `MerchantSettlement.settle` posts a
   capture as one entry, `DR <clearing> G / CR MERCHANT_PAYABLE G; DR MERCHANT_PAYABLE f /
   CR FEE_REVENUE f` (`payment-capture:`); `MerchantPayable`'s drill-down reads "captured − fees −
   refunded + fees returned − paid out …" by counterparty (`INV-MER-02`), and payouts are bounded by
   the position (`INV-MER-05`). A BNPL purchase must be readable in the same terms.
4. **The merchant discount's nature is an accounting question**, not a code one: income when the
   purchase is financed, or a yield adjustment earned over the instalments. The platform has no
   general ledger yet; effective-interest treatment is Phase 14's (lending's A17 precedent).
5. **Lending's purposes are posted by lending only** (`LendingBooksHaveOnePosterTest`), and its
   capital headroom sums them by purpose across every account of the currency.

## Decision

1. **The account plan** (ledger `V027`, `P12-TSK-003`). A new `OwnerKind.BNPL_OBLIGATION`
   (`owner_ref` = the obligation id):

   | Purpose | Owner | Type (normal) | Per | Holds |
   |---|---|---|---|---|
   | `BNPL_PRINCIPAL` | BNPL_OBLIGATION | ASSET (debit) | obligation | purchase principal not yet due |
   | `BNPL_PRINCIPAL_DUE` | BNPL_OBLIGATION | ASSET | obligation | billed principal unpaid |
   | `BNPL_INTEREST_ACCRUED` | BNPL_OBLIGATION | ASSET | obligation | PN: interest earned, not due |
   | `BNPL_INTEREST_DUE` | BNPL_OBLIGATION | ASSET | obligation | PN: billed interest unpaid |
   | `BNPL_FEES_DUE` | BNPL_OBLIGATION | ASSET | obligation | late fees unpaid |
   | `BNPL_CREDIT_BALANCE` | BNPL_OBLIGATION | LIABILITY (credit) | obligation | overpayment, or a refund excess the wallet could not take, owed to the customer |
   | `BNPL_MERCHANT_FEE_INCOME` | OPERATIONAL | REVENUE | currency | the merchant discount |
   | `BNPL_INTEREST_INCOME` | OPERATIONAL | REVENUE | currency | PN interest |
   | `BNPL_FEE_INCOME` | OPERATIONAL | REVENUE | currency | late fees |
   | `BNPL_WRITE_OFF_EXPENSE` | OPERATIONAL | EXPENSE | currency | seeded, posted by nothing in Phase 12 (Phase 14) |

   The per-obligation accounts are opened by `createOrConverge` in the purchase transaction, in the
   obligation's currency, by bnpl's opener declaring each type (`INV-LED-06` at the opener, the
   `LoanAccounts` precedent); the interest pair carries no line for P4. The operational accounts are
   seeded per supported currency. All ten join `AccountPurpose.closedToFreeAdjustments()` — no
   `AdjustmentService` line ever touches them, the binding trigger re-stated — and none joins
   `reconciledPositions()` (bnpl opens no expectation). No `*_CLEARING` purpose is added
   (`INV-RAIL-04`): BNPL moves no money across a rail.

2. **Separate purposes, separate posters** (OQ-11, owner accepted default). Reusing `LOAN_*` under a
   new owner kind would have made lending's headroom count BNPL for free, but would make
   `LendingBooksHaveOnePosterTest`'s "only lending posts loan purposes" false and tie BNPL's figures
   to lending's subledger identities. Instead **`BnplBooksHaveOnePosterTest`** holds the mirror: only
   bnpl's operations post to the ten BNPL purposes (the write-off expense by nothing yet); lending's
   headroom and proof read the BNPL principal purposes by purpose (ADR-0104) without learning bnpl's
   module. The merchant discount gets its own `BNPL_MERCHANT_FEE_INCOME` rather than merchant's
   `FEE_REVENUE` (OQ-19): the card processing fee and a credit-funded discount are different income
   whose Phase 14 treatment may differ (decision 10), and a separate purpose lets that decision move one without splitting history.

3. **Posting conventions.** Every entry: `posting_date = value_date =` the business date of the
   database clock read under the obligation's lock (B4) — in the purchase transaction, the
   purchase's business date — and never before the obligation's principal value floor (the Phase 11
   gate's R1 — the bnpl analogue of lending `V030`'s `principal_value_floor`, from bnpl's first
   table); `reference` = the bnpl operation's id; balanced per currency by construction; posted through
   `PostingService.post` or `ReversalService.reverse` in bnpl's own transaction. A transaction that
   posts several entries (the purchase, a servicing step, a payoff) pre-locks the union of its
   projection rows through `lockBalancesInOrder` before its first post (the Phase 9 rule), the shared
   income rows inside that one sorted call at L8 (ADR-0101 decision 11). A journal line is never zero
   (`JournalLine`), so an entry's fee pair is absent when its fee is zero.
   *(Corrected by `P12-DOC-001`, 2026-10-22: this read "Every entry: `posting_date = value_date =` the
   business date …". As built every entry's **posting** date is the business date of the database clock
   read under B4 (the purchase's, in T-pur), but the **value** date is the rule's own: F1 the purchase's
   business date; F2 the instalment's due date (a payoff's early billing the payoff's value date `V`); F2a
   the accrual date; F3, F3a, F5/F9 on an `ACTIVE` obligation and F8 `max(today, floor)`, the floor
   `bnpl.principal_value_floor` read under B4; F4, F7 and the closure refund today. No value date is ever
   before the floor — the R1 rule as stated.)*

4. **The posting rules.**

   | # | Economic event | Debit | Credit | Reasoning | Key |
   |---|---|---|---|---|---|
   | F1 | Purchase financed | `BNPL_PRINCIPAL` G; `MERCHANT_PAYABLE` f | `MERCHANT_PAYABLE` G; `BNPL_MERCHANT_FEE_INCOME` f | the platform acquires a claim on the customer for the price, owes the merchant the price, and takes its discount out of that payable — the capture's four-line shape with the receivable in the clearing's place; no cash moves (headroom binds it) | `bnpl.financing:<purchase>` |
   | F2 | Instalment falls due | `BNPL_PRINCIPAL_DUE` p; PN `BNPL_INTEREST_DUE` i | `BNPL_PRINCIPAL` p; PN `BNPL_INTEREST_ACCRUED` i | reclassification, not income | `bnpl.billing:<instalment>` |
   | F2a | PN interest accrued (daily) | `BNPL_INTEREST_ACCRUED` | `BNPL_INTEREST_INCOME` | income as earned; its own transaction, T-acc | `bnpl.accrual:<obligation>:<date>` (`bnpl.accrual-correction:<obligation>:<date>:<engine>` reserved, posted by nothing) |
   | F3 | Repayment, auto-collection, P4 instalment 1 | `CUSTOMER_WALLET` A | due accounts per allocation; `BNPL_CREDIT_BALANCE` the rest | the customer's own funds settle the claim | `bnpl.repayment:<repayment>` |
   | F3a | Credit balance applied | `BNPL_CREDIT_BALANCE` | due accounts per allocation | held credit settles what fell due (the gate's R2 catch-up) | `bnpl.credit-application:<billing>`; a catch-up `bnpl.credit-application:<obligation>:catch-up:<n>` |
   | F4 | Late fee assessed | `BNPL_FEES_DUE` | `BNPL_FEE_INCOME` | contractual fee, once per instalment, capped | `bnpl.fee:<instalment>:LATE` |
   | F5 | Merchant refund R, latest instalments first | `MERCHANT_PAYABLE` R; `BNPL_MERCHANT_FEE_INCOME` r_f | `BNPL_PRINCIPAL` (unbilled cancelled); `BNPL_PRINCIPAL_DUE` (billed cancelled); `CUSTOMER_WALLET` E (excess) — `BNPL_CREDIT_BALANCE` E when the customer has no `ACTIVE` wallet and the obligation is `ACTIVE` *(as built, `P12-TSK-021`, OQ-13; a `CLOSED` obligation with no wallet is refused `bnpl.RefundNotPostable`)*; `MERCHANT_PAYABLE` r_f | the merchant gives back the price and the platform its discount share (the `RETURNED` refund shape); the customer's debt falls by what it still owed and what it had already paid comes back | `bnpl.refund:<refund>` |
   | F5a | Fees and interest released on cancellation | `BNPL_FEE_INCOME`; `BNPL_INTEREST_INCOME` | `BNPL_FEES_DUE`; `BNPL_INTEREST_DUE`, `BNPL_INTEREST_ACCRUED` | a fee on a cancelled instalment is not owed (OQ-14); PN interest only at a full cancellation (A11) *(as built, `P12-TSK-020` / `-021`: the late fee still due on every billed instalment cancelled whole — on every billed one when no principal is left unpaid — is released, a partial cancellation releasing none and a paid fee never refunded; PN releases the accrued and billed-unpaid interest when no principal is left unpaid, and the accrued interest no billing could make due when nothing is left to bill)* | inside F5's entry |
   | F6 | Payoff | rules F2 (early), F3a, F3 (`PAYOFF`); then `BNPL_CREDIT_BALANCE` | … `CUSTOMER_WALLET` (refund) | every receivable to zero | each rule's own key; the refund `bnpl.closure-refund:<obligation>` *(corrected by `P12-DOC-001`, 2026-10-22: this read "rules F2a, F2, F3"; T-pay posts no F2a — pay-in-N must be caught up by T-acc in its own transaction first, else `BehindAccrual` with nothing written — and applies the held credit (F3a) before the wallet's F3)* |
   | F7 | Fee waived (four-eyes) | `BNPL_FEE_INCOME` | `BNPL_FEES_DUE` | `ReversalService` of F4's entry, bounded | `bnpl.waiver:<waiver>` *(corrected by `P12-DOC-001`, 2026-10-22: this read "scope `ledger.reverse`"; `BnplWaivers` reverses under its own key)* |
   | F8 | Repayment reversed (four-eyes) | F3's credits | `CUSTOMER_WALLET` | the exact swap through `ReversalService`; re-instated principal consumes headroom (ADR-0104) | `bnpl.repayment-reversal:<id>` |
   | F8a | Late fee a reversal re-opened, released *(the Phase 12 → 13 transition gate's A7-3, `bnpl V019`, 2026-10-22)* | `BNPL_FEE_INCOME` | `BNPL_FEES_DUE` | F8 re-opened a fee paid before a refund cancelled its instalment whole; released by F5a's rule in the reversal's transaction, so OQ-14 holds whichever act came first; one row per instalment in `bnpl_reversal_fee_release` | `bnpl.reversal-fee-release:<reversal>` |
   | F9 | Dispute resolved as a refund | = F5 | = F5 | a refund under the same bound (ADR-0106, `INV-BNPL-08`) | `bnpl.refund:<refund>` |
   | F10 | Merchant payout | `MERCHANT_PAYABLE` | `PAYOUT_CLEARING` | merchant's own entry, unchanged — BNPL never pays a merchant | merchant's keys |
   | F11 | Write-off (deferred, Phase 14) | `BNPL_WRITE_OFF_EXPENSE` | every BNPL asset account | loss | — |

   F1 is `INV-BNPL-04` made concrete: receivable principal = G = merchant net (G − f) + merchant fee
   f, exactly, the entry's lines checked by trigger. F5 is `INV-BNPL-06`: R = Σ principal cancelled +
   E, and the payable falls by R − r_f; nothing else moves. In the purchase transaction P4 posts F1,
   F2 for instalment 1 and F3 for its collection; a refund's F5a lines and its F5 lines are one entry.

5. **Why F1 has four lines, not three.** `CR MERCHANT_PAYABLE (G − f) / CR fee income f` balances
   too, but the payable would then show a net with no gross: the drill-down could not read a BNPL
   purchase as "captured − fees", the fee's return on refund would face no fee line, and
   `INV-MER-02`'s equation would need a term that is not a movement. The four-line entry keeps the
   payable's gross and fee visible exactly as a card capture's are.

6. **The worked check (P4, G = 100.00, f = 4.00, instalments 25.00 × 4).**
   - Purchase: F1 `DR BNPL_PRINCIPAL 100, DR MERCHANT_PAYABLE 4 / CR MERCHANT_PAYABLE 100,
     CR BNPL_MERCHANT_FEE_INCOME 4` (104 = 104; payable +96); F2 `DR BNPL_PRINCIPAL_DUE 25 /
     CR BNPL_PRINCIPAL 25`; F3 `DR CUSTOMER_WALLET 25 / CR BNPL_PRINCIPAL_DUE 25`.
   - Full refund now: unpaid principal 75 (instalments 4, 3, 2), excess 25, fee returned 4: F5
     `DR MERCHANT_PAYABLE 100, DR BNPL_MERCHANT_FEE_INCOME 4 / CR BNPL_PRINCIPAL 75,
     CR CUSTOMER_WALLET 25, CR MERCHANT_PAYABLE 4` (104 = 104; payable −96).
   - Result: every BNPL account zero, the payable back to zero (if not yet paid out), fee income
     zero, the wallet −25 + 25 = 0. Value preserved; every entry balances.
   - Partial path, same purchase: a refund of 40 cancels instalment 4 (25) and 15 of instalment 3,
     fee returned `cum_fee(40) − cum_fee(0) = 1.60` (`DR MERCHANT_PAYABLE 40, DR fee income 1.60 /
     CR BNPL_PRINCIPAL 40, CR MERCHANT_PAYABLE 1.60`; payable 57.60 = 60 − 2.40); a later refund of
     the remaining 60 returns `4.00 − 1.60 = 2.40` — the fee telescopes to exactly f (A10).

7. **The merchant discount at purchase** (A8, A9; OQ-16, owner accepted default).
   `f = round_HALF_EVEN(G · bps / 10 000) + fixed` from the merchant's BNPL fee schedule version
   (`refund_fee_policy = RETURNED`), pinned on the purchase in `bnpl_fee_pin` (merchant `V010`; the
   intent-keyed `PaymentFeePin` cannot hold a purchase), `f < G` (ADR-0058), `INV-MER-03`. Recognised
   in full as `BNPL_MERCHANT_FEE_INCOME` in F1. On a refund `r_f = cum_fee(after) − cum_fee(before)`,
   `cum_fee(x) = round(f · x / G)`, `cum_fee(G) = f` — order-independent, the
   `MerchantSettlement.refund` precedent (ADR-0105 owns the refund policy). Merchant fee income is not
   capital (lending's A29; ADR-0104).
   *(Corrected by the Phase 12 → 13 transition gate, 2026-10-22: `round_HALF_EVEN` above is the default,
   not the rule. As built the fee and `cum_fee` are each rounded once under the **pinned fee schedule
   version's own `rounding_policy`** — merchant `V004`'s required, named attribute (`INV-MON-03`), one of
   six — by merchant's `FeeCalculation` (`assess`, and `returnedFee` through `BnplMerchants`). `HALF_EVEN`
   is A8's accepted default, the policy a BNPL fee schedule is expected to carry; the code imposes none.)*

8. **The merchant payable gains `bnpl` as a poster.** F1, F5 and F9 post to `MERCHANT_PAYABLE`;
   `MERCHANT_PAYABLE` stays outside `closedToFreeAdjustments()` (reconciliation attributions still
   reach it). `MerchantPayable`'s drill-down adds `BNPL_PRINCIPAL` to its `COUNTERPARTIES` and the
   kinds `BNPL_FINANCING` and `BNPL_REFUND` (served by `GET /v1/merchant/transactions`; their exact
   shape is `P12-TSK-016`'s design), so **`INV-MER-02`'s equation gains "+ financed − BNPL fees −
   BNPL refunds + BNPL fees returned"**. A BNPL refund after a payout may drive the payable below
   zero only within the amended `INV-MER-07` bound, the debt recovered before any payout
   (`INV-MER-05`; B7, ADR-0105). *(As built by `P12-TSK-016`: the lines are classified by their entry's posting-key namespace (`bnpl.financing:`, `bnpl.refund:`) - which the ledger's `PositionBreakdown` and `StatementDerivation` now report beside the counterparty, in the ledger's own vocabulary, for the namespaces a caller names - after the attribution rule and before any counterparty rule, because F5's excess credits `CUSTOMER_WALLET`, the book rail's sale counterparty; `COUNTERPARTIES` gains `BNPL_PRINCIPAL`, `BNPL_PRINCIPAL_DUE` and `BNPL_MERCHANT_FEE_INCOME`, descriptive only; the payable gains `bnplFinanced`, `bnplFees`, `bnplRefunded`, `bnplFeesReturned`; a `BNPL_FINANCING` movement carries the purchase (the entry's reference), a `BNPL_REFUND` the BNPL refund.)*

9. **Three numbers, one authority.** *Ledger* (authoritative): the per-obligation balances. *BNPL's
   rows*: immutable facts that explain the ledger and must sum to it — `BnplSubledgerProof`
   (`P12-TSK-025`): `BNPL_PRINCIPAL` = G − billed − cancelled unbilled; `BNPL_PRINCIPAL_DUE` = billed −
   allocated − cancelled billed (± reversals); interest, fees and the credit balance likewise; no due
   account negative; a `CLOSED` obligation all zero; every entry on the accounts named by a row of
   the obligation. A difference is an alert, never self-corrected. *GL* (Phase 14): a mapping of these
   purposes, `gl_code` null now.

10. **Open accounting questions — recorded, never invented; each Phase 14's.** (a) Whether the
    merchant discount is income at purchase or a yield adjustment amortised over the instalments
    (effective interest) — Phase 12 recognises it at purchase (A9). (b) Whether the receivable should
    be recorded at G or at G − f with the discount unearned — an IFRS 9 question. (c) Credit-impaired
    treatment, provisioning and write-off (F11 seeded, posted by nothing). No Phase 12 code anticipates
    an answer.

## Alternatives Considered

### `LOAN_*` purposes under a new owner kind
Pros:
- Lending's headroom, exposure view and proofs count BNPL with no change.

Cons:
- Two modules posting one purpose; `LendingBooksHaveOnePosterTest` false; lending's subledger
  identities would have to know a refund.

Refused (OQ-11).

### The merchant discount on merchant's `FEE_REVENUE`
Refused (OQ-19): one account for two kinds of income whose Phase 14 treatments may part.

### A three-line F1 (`CR MERCHANT_PAYABLE G − f`)
Refused: decision 5.

### The receivable at G − f, the discount unearned
Pros:
- Closer to an effective-interest view of the discount.

Cons:
- An accounting policy the platform has not decided; the customer owes G, and the subledger and the
  three-way proof would carry a contra-asset with no owner of its release.

Deferred to Phase 14 (decision 10 (b)); not invented now.

### One receivable account per obligation
Refused: ADR-0096's reasons — past-due becomes a calculation over rows, not a balance.

### A BNPL clearing account between `bnpl` and the payable
Refused: a transit position whose zero must be proven, for one economic event that `bnpl` posts
whole (ADR-0101 decision 4).

## Consequences

Positive:
- Every BNPL figure is a ledger balance and every operational figure is proven against it; the
  merchant leg and the customer leg sit in one entry, so the three-way proof (plan §12.12) is
  arithmetic over lines, not a correlation of two books.
- The merchant reads a BNPL purchase as it reads a capture; the payable's equation stays one sum.

Negative:
- Six accounts per obligation — at BNPL volume the chart grows fastest here.
- `BNPL_MERCHANT_FEE_INCOME` is posted by every purchase of a currency: a hot row at L8, correct but
  a throughput ceiling, measured by `P12-TST-001` (plan §21). *(As built (`P12-DOC-001`, 2026-10-22):
  measured on the storm's final run — 12 waits on `BNPL_MERCHANT_FEE_INCOME`, 0 on `BNPL_FEE_INCOME`
  and `BNPL_INTEREST_INCOME`, beside `LENDING_CAPITAL`'s 65; no deadlock.)*
- The discount is income at purchase until Phase 14 says otherwise, and a refund reverses income
  already recognised.

Operational impact: `finapp.bnpl.subledger.proof{verdict}`, `finapp.bnpl.threeway.proof{verdict}` and
`finapp.bnpl.replay{verdict}` (alerting; *the tag and the replay gauge as built by `P12-TSK-025`, recorded
by `P12-DOC-001`, 2026-10-22*);
a negative payable counted by `finapp.ledger.negative.positions`; amounts only in audited reports
(ADR-0072).
Security impact: no person posts to a BNPL account; waivers and reversals are four-eyes
`ReversalService` acts.
Financial impact: the operational ledger gains the BNPL book, the merchant discount and BNPL interest
and fee income; the merchant payable gains its third poster.

## Invariants / Constraints

`INV-BNPL-01`, `INV-BNPL-04`, `INV-BNPL-06`, `INV-BNPL-09`, `INV-LED-01`…`06`, `INV-BAL-01`…`05`,
`INV-MER-02` (its equation extended), `INV-MER-03`, `INV-MER-04`, `INV-MER-05`, `INV-MER-07`
(amended, ADR-0105), `INV-RAIL-04`, `INV-REV-01`…`04`, `INV-MON-01`…`06`, ADR-0040, ADR-0042,
ADR-0058, ADR-0096.

## Follow-up

- Built by `P12-TSK-003` (ledger `V027`, the opener, `BnplBooksHaveOnePosterTest`) and each rule by
  the task that posts it: F1 and P4's F2/F3 at purchase `-013`; F2, F3a `-015`; F2a `-017`; F3 and
  the closure refund `-018`, `-019`; F4 `-020`; F5, F5a `-021`; F6 `-022`; F9 `-023`; F7, F8 `-024`;
  F10 proven over BNPL-financed money and the drill-down `-016`; the proofs `-025`.
- **Decision 1 as built by `P12-TSK-003`** (2026-10-17): ledger `V027` (the four enum-fed `CHECK`s
  recreated from `OwnerKind` / `AccountPurpose`; the four operational accounts seeded in all five
  supported currencies, ids `01a0e2bc-8200-7027-…` stamped 2026-09-27T12:00:00Z, below the
  ceiling; the binding trigger restated with all ten), bnpl's `BnplAccounts` and
  `BnplLedgerAccounts`. Refinements, each on the invariant's side: the opener reads the obligation's
  existing accounts once (`findAllOwned`, no lock) and judges them before its first insert, then
  judges each converged account again - so a mis-shaped account already there (another type,
  normal balance or currency, or another purpose under the obligation's id) is refused with nothing
  written, and one a concurrent writer lands between the read and the insert is refused before the
  caller can commit; the refusals are typed by exception class, the `LoanAccounts` precedent
  (`IllegalArgumentException` for an unsupported currency, before the ledger is asked;
  `IllegalStateException` for a mis-shaped account, naming no obligation id); `BnplLedgerAccounts`
  holds each named component to its purpose. The ledger declares no per-obligation type (the opener
  does), so the plan's types are held in two places by design: `BnplAccountsTest` (per obligation)
  and `OperationalChartMigrationTest`'s pinned seed types, which `BnplBookPurposesTest` holds to the
  plan with every normal side.
- `P12-TSK-021` decides what happens to a late fee already **paid** on an instalment a refund
  cancels (F5a releases only the unpaid fee; the plan names none for the paid part) and to a fee on
  an instalment a refund only shortens.
  - *Decided and built by `P12-TSK-020` (2026-10-19; recorded by `P12-DOC-001`):* a paid late fee is
    never refunded by a release — it stays income; only what is still due of `FEES` on an instalment
    cancelled whole (on every billed one when no principal is left unpaid) is released, in the refund's
    own entry (`bnpl V013`: `late_fee_released_minor` on the adjustment); a refund that only shortens an
    instalment releases nothing.
- `P12-TSK-016` classifies a BNPL refund by its entry's origin, not by counterparty alone: a refund
  on a `CLOSED` obligation (OQ-15) posts only `DR MERCHANT_PAYABLE / CR CUSTOMER_WALLET` (+ the fee
  pair), which the drill-down's counterparty rule would read as a book-rail refund.
  - *Built by `P12-TSK-016`:* decision 8's as-built note — classified by the posting-key namespace
    before any counterparty rule.
- `P12-TSK-013` and `-015` state the floor's billing arm: "the day after its latest billing" would
  value a same-day P4 refund on the day after the purchase; lending's statement arm uses the cycle
  end itself.
  - *Stated and built (`P12-TSK-013`, `-015`, `-022`):* the billing arm is the latest billing's value
    date **itself**, not the day after (a billing beside a payoff execution counted at most at the
    execution's value date); the accrual arm the day after the latest accrual; never before the
    purchase's business date (`bnpl.principal_value_floor`, last replaced by `bnpl V014`).
- **F8a, and F8's value date (the Phase 12 → 13 transition gate's repair R3, 2026-10-22; `bnpl V019`).** (A7-3) F8 is the
  exact swap of F3, so a reversal of a repayment that paid a late fee re-opens it - and where a refund had cancelled that
  instalment whole after the payment (releasing nothing: the fee was paid), the fee stood owed, though the same two acts in
  the other order release it (F5a, OQ-14). The approval now posts F8a beside F8 - `DR BNPL_FEE_INCOME / CR BNPL_FEES_DUE`,
  exactly the fee due once the negating rows stand, under an entry of its own; the currency's income row inside T-rev's one
  L8 call after (P). The subledger identity for `BNPL_FEES_DUE` subtracts it, R3's income arm counts it, and the replay
  re-derives it (`REVERSAL`). (A2-1) F8 is valued `max(today, floor, the reversed repayment's value date)` - never before the
  repayment it reverses (the floor alone let a clock stepped back value it there; `VALUE_DATE`).
- **Acceptance.** **As built** (`P12-DOC-001`, 2026-10-22): `Proposed` at the Phase 11 → 12 transition
  (2026-10-16); the Phase 12 review read this ADR against the code, corrected it in place above with dated
  notes, and accepted it.
