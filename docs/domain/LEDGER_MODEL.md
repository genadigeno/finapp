# Ledger Model

The ledger is the **authoritative financial record**. Every balance, statement, report and
reconciliation in this platform is derived from it, and nothing else is financial truth
(`CLAUDE.md` rule 12, `INV-EVT-02`).

*Decided by the Phase 2 → 3 transition (2026-09-13) in ADR-0039…0042; implemented by Phase 3
and updated by `P3-DOC-001` (2026-09-17) against what was built. Two spots had gone stale on the
adjustment — `P3-TSK-021` replaced the one-person write with two authenticated acts after they
were written — and were corrected as review findings rather than silent edits
([`reviews/PHASE_3_REVIEW.md`](../project/reviews/PHASE_3_REVIEW.md) area 7). Phase 9's
additions - two more currencies, the FX books, counterparty-keyed clearings and the FX and
cross-border entries - are §9, written as built by `P9-DOC-001` (2026-10-07).*

---

## 1. The concepts, and what each is not

| Concept | Is | Is **not** |
|---|---|---|
| **Chart of Accounts** | The set of ledger accounts and their classification | A hierarchy. It is flat, with roll-up by attribute (ADR-0040) |
| **Ledger Account** | An accounting position: type, normal balance, one currency | A customer's product; something a customer "has" |
| **Operational Account** | A ledger account holding the platform's own position | A customer's account. Mixing them is how a shortfall becomes invisible |
| **Journal Entry** | The atomic accounting unit: ≥2 lines that balance per currency | A transaction, a payment, or a transfer — those are *economic events* that cause entries |
| **Journal Line** | One account, one direction, one amount | A signed number. Direction carries the sign |
| **Debit / Credit** | A `Direction` on a line | "Money in" / "money out". Which of those a debit means depends on the account's normal balance |
| **Posting** | The act of writing a balanced entry durably | A balance update |
| **Reversal** | A *new* entry with directions swapped, referencing the original | An edit, a delete, or a negation in place |
| **Adjustment** | A manual entry proposed with a reason by one authorised person and posted by a second who approves it (`INV-AUD-04`) | A correction any one person may make alone |
| **Suspense** | An account where unmatched value is parked, aged and reported | A resting place. `INV-REC-05` requires it be temporary |
| **Currency** | Explicit on every account, line and balance | Ever implied, defaulted, or converted silently |

## 2. The three dates

`DOMAIN_MODEL.md` §Time governs, and the ledger is where getting it wrong becomes permanent.

| Date | Decides | Comes from |
|---|---|---|
| **System time** (`created_at`) | When the machine wrote the row | The injected `Clock`. **Never a business fact** |
| **Posting date** | Which accounting period the entry falls in | A **domain input** to the command |
| **Value date** | When value is available / interest accrues | A rail, product or contract rule — also an **input** |

**Posting date and value date are never clock reads.** A component that derives a posting date by
calling the clock has silently decided that execution time and accounting date are the same
thing, and no rule can catch that substitution — it is a design-review question, which is why it
is written here.

## 3. The rules a posting must satisfy

1. **It balances, per currency** (`INV-LED-01`) — enforced by the domain *and* by the database,
   because the domain is not the only writer a schema will ever have.
2. **It has at least two lines** (`INV-LED-02`). A single-sided posting is unbalanced value
   movement by definition.
3. **It is immutable once written** (`INV-LED-03`, `INV-HIST-01`) — at `DB-PRIVILEGE`: the
   application role holds no `UPDATE` and no `DELETE`.
4. **It is attributable** (`INV-LED-05`): actor, correlation, causation, `NOT NULL`.
5. **It is idempotent at the financial boundary** (`INV-IDEM-01`): one command, one key, one
   effect.
6. **Its publication commits with it** (`INV-EVT-01`): the outbox row is in the same transaction.
7. **Only the ledger writes it** (`INV-LED-04`). Other modules *request* postings through a
   command API.

## 4. Balances

**A balance is derived from postings and is never an independent authority** (`INV-BAL-01`,
ADR-0002, ADR-0009). Four distinct numbers, never collapsed:

| Balance | Definition |
|---|---|
| **Settled / ledger** | Sum of posted lines, signed by the account's normal balance |
| **Pending** | Value from entries not yet final — a Phase 5 seam; nothing creates one yet |
| **Holds** | Sum of active holds |
| **Available** | `settled − holds` (`INV-BAL-04`) |

**Replaying every posting from zero reproduces the balance exactly** (`INV-BAL-02`). That is the
operational definition of an explainable balance, and it is verified continuously rather than
believed.

A **projection** exists for read performance (ADR-0041): a table in the ledger schema, updated in
the posting's own transaction, never behind. **No financial decision reads it** — a hold or an
overdraft check derives its number from the postings inside the account lock, because a decision
made from a projection is a balance-as-truth model wearing a different word (`INV-BAL-05`).

## 5. Concurrency

ADR-0039. Two halves, and conflating them is the mistake:

- **Postings are inserts.** No row is updated, so under `READ COMMITTED` there is no lost update
  to have. No lock is taken on any account.
- **Decisions that depend on a balance take `SELECT … FOR UPDATE` on the account row**, then
  derive, then act. This covers holds and any future non-overdraw check, and nothing else.

`SERIALIZABLE` is deliberately not used: it would put a retry loop around every money-moving
command, which is where "the database committed but the response was lost" becomes two effects.

## 6. Correction

**Financial history is never edited** (`INV-HIST-01`). A mistake is corrected by a new entry:

- a **reversal** references the original, swaps directions, and is bounded by what remains
  un-reversed (`INV-REV-01`, `INV-REV-02`). The original is byte-identical afterwards — asserted,
  not assumed;
- an **adjustment** is **two authenticated acts** (`P3-TSK-021`): an initiator *proposes* —
  reason required (`INV-REV-04`), nothing posts — and a **second** `LEDGER_ADJUST` holder
  *approves*, which posts the entry in the approval's own transaction. Approver ≠ initiator
  holds at `DB-CONSTRAINT` (`INV-AUD-04`), and there is no approver column on the entry because
  there are two acts, each with one actor, paired on the proposal row. Four-eyes applies to
  **every** adjustment — a threshold is a versioned per-currency policy artefact with nothing to
  calibrate it yet, recorded as a future seam on the proposal row;
  *(Corrected 2026-10-02 by the Phase 8 -> 9 transition, ARCH-P8-03: the second `LEDGER_ADJUST`
  holder approves a `MANUAL` adjustment only. Since `P8-TSK-006` (ADR-0071 §6) an adjustment has
  an origin, frozen on the proposal by ledger `V015`, and each origin has its own door, which
  refuses the other's proposals (`ledger.AdjustmentOriginMismatch`). A `MANUAL` adjustment
  (`MANUAL_CORRECTION`, or `UNCODED` for one proposed before `V015`) is proposed and approved at
  `/v1/ledger/adjustments` under `LEDGER_ADJUST`, and may never touch a reconciled position
  (`V015`'s trigger). A `RECONCILIATION` adjustment (`RECONCILIATION_WRITE_OFF`,
  `RECONCILIATION_GAIN`, `RECONCILIATION_TRANSFER`) is proposed and approved only inside a break
  resolution's own transaction, through `AdjustmentService.proposeOwned` and `approveOwned` —
  called only from `reconciliation` — by a second holder of `RECONCILIATION_RESOLVE`, the
  `RECONCILIATION_OPERATOR` role, which holds no ledger permission (ADR-0071 §10). Whoever traces
  the approvers of adjusting entries reads both populations. Four-eyes holds for both at the same
  rank: `V010`'s approver ≠ proposer `CHECK` is origin-agnostic, and reconciliation's
  `resolution_four_eyes_distinct` `CHECK` holds it again on the resolution row, every posting
  kind being four-eyes by `resolution_four_eyes_derived`.)*
- a **rounding residual** is posted to a designated account, never absorbed (`INV-BAL-03`).
  Absorbed residual is money creation or destruction, at scale.

## 7. What a posting must be explainable as

`CLAUDE.md` requires this chain to be traceable for any monetary change, and every link is a row:

```
economic event → domain operation → financial transaction → journal entry
              → debit/credit lines → resulting balances → settlement → reconciliation
```

Phase 3 builds the middle: entry, lines, balances. The economic event is whatever later phase
causes the posting; settlement and reconciliation are Phases 5 and 8. **Suspense accounts exist
from Phase 3** so Phase 8 can park unmatched value without corrupting customer balances. A conversion or a cross-border payment walks the
same chain with one more link: the quote's frozen plan is the domain operation, and each currency
of the entry balances on its own (§9).

## 8. The trial balance

Total debits equal total credits **for every currency, at all times** (`INV-ACC-01`) — the
system-level expression of `INV-LED-01` and the primary continuous correctness signal. A job
asserts it with alerting, and it **never self-corrects**: a ledger that repairs itself has
destroyed the evidence of what went wrong.

## 9. FX and cross-border — *as built by Phase 9*

Phase 9 (ADR-0074, ADR-0076…0078, ADR-0082; `PHASE_9_PLAN.md` §12.6) added no rule above: every
entry still balances **per currency** under ledger `V004`'s unchanged trigger, and **nothing is
converted inside a line** - a conversion is two single-currency legs meeting on `FX_POSITION`.

**Currencies.** JPY (0 minor units) and BHD (3) are postable since ledger `V019` (`P9-TSK-003`):
the thirteen operational purposes seeded for both together, after their minor units were pinned
(`SupportedCurrencies.PINNED_MINOR_UNITS` and its startup guard, `P9-TSK-002`). Every line carries
its currency's own scale.

**The FX books** - posted only by `fx`'s `ConversionLines` and `CoverLines`
(`FxBooksHaveOnePosterTest`):

| Purpose | Type / normal | Arrived | Meaning |
|---|---|---|---|
| `FX_POSITION` | ASSET / DEBIT | seeded `V003`; JPY/BHD `V019` | Per currency, what open conversion legs will receive from (debit) or deliver to (credit) a cover; **zero at rest** |
| `ROUNDING_RESIDUAL` | EXPENSE / DEBIT | `V003`; `V019` | A conversion's rounding, either sign, in the computed leg's currency (`INV-BAL-03`); `ConversionLines` is its first production poster |
| `FX_SPREAD_REVENUE` | REVENUE / CREDIT | `V020` (`P9-TSK-009`) | The spread and markup, recognised explicitly in the computed leg's currency, never inside the rate (`INV-FX-03`) |
| `FX_REALISED_GAINS` / `FX_REALISED_LOSSES` | REVENUE / CREDIT; EXPENSE / DEBIT | `V023` (`P9-TSK-012`) | A cover or unwind executed off its plan, in that leg's currency; never netted with each other |

All five are **closed to free adjustments** (`AccountPurpose.closedToFreeAdjustments()`, the
`V015` binding trigger restated by `V020`, `V022`, `V023` and `V024`) and none is a reconciled
position: they open no expectations, so completeness would otherwise report every conversion line
unattributed.

**Counterparty-keyed clearings** (ADR-0078, `INV-RAIL-04`). Ledger `V021` (`P9-TSK-010`) added the
append-only `ledger.counterparty` registry, the `COUNTERPARTY` owner kind and a `BEFORE INSERT`
trigger holding `owner_ref` to a registry row. A counterparty clearing account is keyed
(counterparty, purpose, currency) - there is no shared account for two providers to net in - and
is seeded by the migration that admits its counterparty, never minted at runtime
(`CounterpartyChartGuard` refuses startup without them):

| Purpose | Type / normal | Counterparties and currencies |
|---|---|---|
| `FX_PROVIDER_CLEARING` | ASSET / DEBIT - what the provider owes the platform | `fx-sim-a`, all five currencies (`V022`, `P9-TSK-011`); `fx-sim-b`, EUR and USD (`V025`, `P9-TSK-026`) |
| `CORRIDOR_CLEARING` | LIABILITY / CREDIT - what the platform owes the corridor provider | `corridor-sim-a`, USD, JPY and BHD (`V024`, `P9-TSK-014`); `corridor-sim-b`, USD (`V025`) |

Both are reconciled positions: each is discharged only by its own counterparty's declared source
(settlement `V015`-`V017`). `ChartOfAccounts.resolve(uow, purpose, counterpartyCode, currency)`
serves them; the clearing purpose is read off the provider's or rail's declaration
(`CounterpartyClearingIsNamedByDeclarationsTest`).

**The entries** - each through `PostingService` under its posting key:

| Posting key | Event | Lines |
|---|---|---|
| `fx-trade:<tradeId>` | A wallet conversion (`P9-TSK-009`) | S: DR wallet(S) / CR `FX_POSITION`(S); D: DR `FX_POSITION`(D) / CR wallet(D); in the computed leg CR `FX_SPREAD_REVENUE` and `ROUNDING_RESIDUAL` CR when positive, DR when negative (no line at zero). Posting date `trade.booked_on`, the database's |
| `fx-cover:<coverId>` | A cover executed (`P9-TSK-012`) | The plan's `FX_POSITION` legs closed exactly onto `FX_PROVIDER_CLEARING`(provider) at the executed amounts, the difference to `FX_REALISED_GAINS`/`LOSSES` per leg. Posting date `cover_execution.recorded_on`, the database's; value date the provider's |
| `fx-cover:<unwindId>` | An unwind executed (`P9-TSK-021`, cover kind `UNWIND`, fx `V008`) | The same composer over the quote's plan reversed: the position bought back from the same provider, `FX_POSITION` zero again, the platform's result one realised line |
| `fx-trade:<tradeId>` in the `ledger.reverse` scope | An approved trade reversal (`P9-TSK-025`, fx `V009`) | The exact mirror of the trade's entry through the ledger's `ReversalService`, referencing it (ledger `V009`'s bound); in the same transaction the trade `REVERSED` and its cover unwound if executed, voided if rejected |
| `outbound-credit:<creditId>` | The corridor provider accepted a cross-border credit (`P9-TSK-020`) | S: DR wallet(S) the total debit / CR `FEE_REVENUE`(S) the fee / CR `FX_POSITION`(S); D: DR `FX_POSITION`(D) / CR `CORRIDOR_CLEARING`(provider, D) the instructed amount, with `FX_SPREAD_REVENUE` and `ROUNDING_RESIDUAL` - the hold released and the trade booked onto this entry in the same transaction; there is no separate `fx-trade:` entry |
| `crossborder-return:<creditId>` | A return applied from evidence (`P9-TSK-023`) | D: DR `CORRIDOR_CLEARING`(provider) / CR wallet(D), opened if absent; S: DR `FEE_REVENUE` / CR wallet(S) - the fee refund. The spread stands; nothing is re-converted |
| `crossborder-return-fee:<creditId>` | A parked return resolved four-eyes (`P9-TSK-023`) | DR `FEE_REVENUE`(S) / CR wallet(S) only; the principal is the resolution's own `TRANSFER_TO_ACCOUNT` (DR `SUSPENSE_UNMATCHED` / CR wallet(D)). Both entries post in the approval's transaction, the union of their projection rows locked first by `lockBalancesInOrder` (`CorridorReturnResolutions`, `P9-DOC-001`) |

**Dates.** The conversion and cover entries are dated from stored, database-stamped rows. The
`outbound-credit:`, `crossborder-return:`, `crossborder-return-fee:` and trade-reversal entries
take posting and value date as the UTC date of the writing instance's `Clock` - the §2 substitution,
found by the exit review (`P9-DOC-001`). Ruled there as the Phase 5–7 practice `X-TSK-011` records, not a
new class: each entry posts inside one conditional transition that admits a single application - the
credit's edge, the return fact under the credit's lock, the resolution's and the reversal's approvals -
so no later-day replay can re-post it and meet a conflicting fingerprint (ADR-0065 §6's argument for the
repudiation's reversal). The four sites are named in `X-TSK-011`'s scope; new posters still take both
dates from stored rows.

**Hot rows.** Every conversion updates the `FX_POSITION`, `FX_SPREAD_REVENUE` and
`ROUNDING_RESIDUAL` projection rows of its two currencies - a per-currency serialisation point,
correct by the projection's row lock; a transaction posting more than one entry locks the union of
their projection rows in order first (`PostingService.lockBalancesInOrder`, ADR-0076 §9). No
revaluation (ADR-0076): the position is closed by covers, never marked to market.

## 10. Lending — *as built by Phase 11*

**The account plan** (ledger `V026`, `P11-TSK-003`; ADR-0096 §1, `PHASE_11_PLAN.md` §12.8–§12.9).
`OwnerKind.LOAN` - `owner_ref` names the loan or credit line by value, opaque, no foreign key into
lending's schema (the `CUSTOMER` reasoning) - owns six accounts per loan, opened together in the
loan's one currency by lending's `LoanAccounts.openFor` over `LedgerAccountStore.createOrConverge`
(the partial unique index on `(owner_ref, purpose, currency)` arbitrates ten openers into one set):

| Purpose | Owner | Type / normal | Holds |
|---|---|---|---|
| `LOAN_PRINCIPAL` | LOAN | ASSET / DEBIT | principal outstanding, not yet due (a line: drawn, not billed) |
| `LOAN_PRINCIPAL_DUE` | LOAN | ASSET / DEBIT | billed principal unpaid |
| `LOAN_INTEREST_ACCRUED` | LOAN | ASSET / DEBIT | interest earned, not yet due |
| `LOAN_INTEREST_DUE` | LOAN | ASSET / DEBIT | billed interest unpaid |
| `LOAN_FEES_DUE` | LOAN | ASSET / DEBIT | assessed fees unpaid |
| `LOAN_CREDIT_BALANCE` | LOAN | LIABILITY / CREDIT | money received beyond every receivable, owed back |
| `LOAN_INTEREST_INCOME` | OPERATIONAL | REVENUE / CREDIT | seeded per supported currency |
| `LOAN_FEE_INCOME` | OPERATIONAL | REVENUE / CREDIT | seeded per supported currency |
| `LOAN_WRITE_OFF_EXPENSE` | OPERATIONAL | EXPENSE / DEBIT | seeded; posted by nothing in Phase 11 |
| `LENDING_CAPITAL` | OPERATIONAL | EQUITY / CREDIT | the platform's own funds committed to lending, credited only by bank recognition (rule 0, `P11-TSK-004`) |

The per-loan type is declared once, by the opener (`LoanAccounts.declaredTypeOf`), and a converged
account of any other type, currency or owner is refused, never adopted (`INV-LED-06`). The four
operational accounts are seeded with UUIDv7 literals below the id ceiling.

**Guarded.** All ten are closed to free adjustments (`AccountPurpose.closedToFreeAdjustments()`, the
`V015` binding trigger restated by `V026`): a `MANUAL` line on any of them is refused at the domain
and by the trigger. `LENDING_CAPITAL` is also a reconciled position, so completeness reports any line
on it no recognition explains; the loan book is not - it opens no expectations - and is proven by the
subledger proof (`LoanSubledgerProof`, built by `P11-TSK-029`: each of a loan's six balances folded from zero over its
journal lines equals its rows' figure, and every entry on them is named by a row of the loan). Statically, `LendingBooksHaveOnePosterTest` lets only lending name a
`LOAN_*` purpose and only the declaration (and, from `P11-TSK-004`, capital recognition and its
readers) name `LENDING_CAPITAL`. No `*_CLEARING` purpose arrived (`INV-RAIL-04`): lending moves no
money across a rail itself. The posting rules are ADR-0096 §2's, each built by the task that posts it:
every one of the ten is posted except `LOAN_WRITE_OFF_EXPENSE` - seeded, posted by nothing, its write-off
deferred to Phase 14 - `LENDING_CAPITAL` by bank recognition alone (rule 0), the other eight by lending's own
transactions (rules 1 to 13). *(This read "Nothing posts to any of the ten yet" until the Phase 11 exit review,
`P11-DOC-001`, 2026-10-15.)*

## 11. BNPL — *as built by Phase 12*

**The account plan** (ledger `V027`, `P12-TSK-003`; ADR-0103 decision 1, `PHASE_12_PLAN.md` §12.11).
`OwnerKind.BNPL_OBLIGATION` - `owner_ref` names the customer repayment obligation by value, opaque, no
foreign key into bnpl's schema (the `LOAN` reasoning) - owns six accounts per obligation, opened together in
the obligation's one currency by bnpl's `BnplAccounts.openFor` over `LedgerAccountStore.createOrConverge`
(the partial unique index on `(owner_ref, purpose, currency)` arbitrates concurrent openers into one set):

| Purpose | Owner | Type / normal | Holds |
|---|---|---|---|
| `BNPL_PRINCIPAL` | BNPL_OBLIGATION | ASSET / DEBIT | purchase principal not yet due |
| `BNPL_PRINCIPAL_DUE` | BNPL_OBLIGATION | ASSET / DEBIT | billed principal unpaid |
| `BNPL_INTEREST_ACCRUED` | BNPL_OBLIGATION | ASSET / DEBIT | pay-in-N interest earned, not yet due (pay-in-4: none) |
| `BNPL_INTEREST_DUE` | BNPL_OBLIGATION | ASSET / DEBIT | pay-in-N billed interest unpaid |
| `BNPL_FEES_DUE` | BNPL_OBLIGATION | ASSET / DEBIT | assessed late fees unpaid |
| `BNPL_CREDIT_BALANCE` | BNPL_OBLIGATION | LIABILITY / CREDIT | an overpayment, or a refund's excess the wallet could not take, owed back |
| `BNPL_MERCHANT_FEE_INCOME` | OPERATIONAL | REVENUE / CREDIT | the merchant discount; seeded per supported currency |
| `BNPL_INTEREST_INCOME` | OPERATIONAL | REVENUE / CREDIT | pay-in-N interest; seeded per supported currency |
| `BNPL_FEE_INCOME` | OPERATIONAL | REVENUE / CREDIT | BNPL late fees; seeded per supported currency |
| `BNPL_WRITE_OFF_EXPENSE` | OPERATIONAL | EXPENSE / DEBIT | seeded; posted by nothing in Phase 12 (Phase 14) |

Separate purposes and a separate owner kind from the loan book (OQ-11): lending's posters, proofs and
identities never see a BNPL line. The merchant discount is its own income, never merchant's `FEE_REVENUE`
(OQ-19). The per-obligation type is declared once, by the opener (`BnplAccounts.declaredTypeOf`); the
obligation's existing accounts are judged before the first insert and each converged account again, so an
account of any other purpose, type, normal balance, currency or owner is refused, never adopted
(`INV-LED-06`). The four operational accounts are seeded with UUIDv7 literals below the id ceiling
(`01a0e2bc-8200-7027-…`) and hold zero - every currency's trial balance is unchanged (`INV-ACC-01`).

**Guarded.** All ten are closed to free adjustments (`AccountPurpose.closedToFreeAdjustments()`, the `V015`
binding trigger restated by `V027`): a `MANUAL` line on any of them is refused at the domain and by the
trigger. None is a reconciled position - bnpl opens no expectation, so `finapp.reconciliation.line.unattributed`
(one series per reconciled position) gains no BNPL series - and no `*_CLEARING` purpose arrived
(`INV-RAIL-04`): BNPL moves no money across a rail. The book is proven by bnpl's subledger and three-way
proofs (`P12-TSK-025`). Statically, `BnplBooksHaveOnePosterTest` lets only bnpl (and the declaration) name a
`BNPL_*` purpose; its reader-permit list is empty until lending's capital headroom reads the BNPL principal
purposes (`P12-TSK-005`).

**`MERCHANT_PAYABLE` gains bnpl as a poster** - the financing credits it the gross and debits it the discount
(F1), a refund debits it (F5) - beside merchant settlement's captures and refunds and merchant's payouts. No
single-poster rule binds `MERCHANT_PAYABLE` (it is not a reconciled position, and no `*HasOnePosterTest`
names it), so admitting a third poster changes no guard; `INV-MER-02`'s equation gains the BNPL terms with the
drill-down (`P12-TSK-016`) - until then a BNPL line on the payable reads in `MerchantPayable`'s `other` bucket,
never a failure. The posting rules are ADR-0103's F1–F11, each built by the task that posts it; **nothing posts
to any of the ten yet.**
