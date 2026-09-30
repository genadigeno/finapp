# ADR-0065 — Each counterparty's clearing position is discharged in two evidence hops; cash moves only on the bank's statement

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Settlement · Reconciliation · Ledger · Payments · Merchant
Supersedes: nothing. Implements the discharge that ADR-0048 §3, ADR-0049, ADR-0050 §2,
ADR-0051 §3, ADR-0057 §8, ADR-0059 §4, ADR-0061 and ADR-0062 §4 each left to Phase 8 ("Phase 8's
settlement will debit it against cash", ADR-0057 §8). Adds four operational purposes to
ADR-0040's chart, each with the task that first posts to it. Settles, with ADR-0072, the per-rail
cost meter that ADR-0060 §6 deferred (`DECISIONS.md` §Deliberately Deferred). Rests on ADR-0064
(the two modules), ADR-0066 (the evidence), ADR-0067 (the expectations), ADR-0070 (suspense) and
ADR-0071 (resolution authority).

## Context

Phases 5 to 7 post every external completion to its counterparty's own clearing position
(`INV-RAIL-04`, ADR-0059 §4), and then nothing moves the position onward:

- `SETTLEMENT_CLEARING` (ASSET) is the card PSP's net receivable. Captures and dispute wins debit
  it; card refunds, chargebacks and dispute fees credit it.
- `INSTANT_CLEARING` (ASSET) is the instant scheme's. Pay-ins and unmatched confirmations debit
  it; withdrawals and returns credit it.
- `PAYOUT_CLEARING` (LIABILITY) is the payout provider's. A completed payout credits it:
  instructed, not settled (ADR-0057 §8).
- `SUSPENSE_UNMATCHED` has one poster, the Phase 7 unmatched confirmation, and no releaser.
  Since the Phase 7 → 8 transition's repair (payments `V023`), each parking records its `cause`:
  - `UNATTRIBUTED`;
  - `ATTEMPT_CONCLUDED`, for an execution on a concluded attempt;
  - `AMOUNT_MISMATCH`, for the executed value of a pay-in that failed `DECLINED` because the
    scheme executed a different amount.

  It also records the `named_reference` the statement named, when that had our minted shape, the
  `settlement_cycle`, and the `attempt_id`, exactly when the parking is attributed. Its raw
  statement is found by stored identifier (`payments.provider_evidence.unmatched_confirmation_id`).
  It still has no state and no resolution. Those are this phase's (ADR-0070).

The three clearings only accumulate. The chart has no account for the platform's cash, so the
"debit against cash" the earlier ADRs promise has no counter-account. The card PSP's processing
fees are posted nowhere: no Phase 7 rail reports a cost, and the per-rail cost meter was deferred
to this phase, "where the processor's fees arrive with its settlement evidence" (ADR-0060 §6).

Two kinds of evidence can discharge a position, and they come from two different parties:

- **The counterparty's settlement report** (the PSP's day, the scheme's cycle, the payout
  provider's day) says which of our operations it settled, what it charged, and what net it will
  remit.
- **The bank statement** says what cash actually moved in the platform's own account at its
  settlement bank.

They arrive in either order and days apart, and either can be wrong. The vocabulary is easy to
collapse, so it is fixed here (`GLOSSARY.md` and `DOMAIN_MODEL.md` gain the new terms at the
transition):

| Term | Is | Is not |
|---|---|---|
| **Payment Completion** | An operation's internal terminal success (`CAPTURED`, `EXECUTED`, `COMPLETED`, a dispute stage applied, a payout `COMPLETED`) with its posting to the rail's clearing position | Settlement (`INV-SET-01`) |
| **Clearing position** | The counterparty's own ledger account for value in flight: `SETTLEMENT_CLEARING`, `INSTANT_CLEARING`, `PAYOUT_CLEARING` | The network's clearing exchange, which `payments.clearing_record` evidences and which moves nothing |
| **Settlement** | Discharge of the counterparty's obligation, recognised from external evidence: **reported** when its accepted report allocates our operations, **final** when the bank statement moves the cash | Completion; finality (`RailCapabilities.Finality`), which says whether the payee's credit can be taken back |
| **Remittance** | The net funds movement a counterparty batch implies: N = T_in − T_out − F. N > 0 means the counterparty pays us | A bank line; a settlement instruction |
| **Settlement Account** | The platform's own account at its settlement bank, mirrored per currency by `CASH_AT_BANK` and known externally only by the bank's opaque account reference (`INV-RAIL-03`) | A wallet, a customer's bank account, or a clearing position (ADR-0042) |
| **Settlement Instruction** | An outbound instruction to move settlement funds | Anything Phase 8 creates: it originates no sweep, net settlement, prefunding or liquidity movement. Payouts and withdrawals are existing payment instructions (ADR-0057, ADR-0062) |

**Clearing-level evidence is this phase's.** Phase 7 keeps a card capture's network clearing only
as its ARN, in `payments.clearing_record`, with no amount and no date (payments `V015`).
- Since the Phase 7 → 8 transition's repair (ADR-0059 §5), a second, *different* clearing of one
  capture is its own outcome, `SECOND_PRESENTMENT`. It is loud and counted as unmappable, the
  first record stands, and it is never absorbed.
- But it rests only in the retained provider evidence. There is no clearing-notice table and no
  cleared amount on the wire.
- The cleared-amount and second-presentment evidence is therefore owned by this ADR's hop 1.
  Its table is the PSP report's canonical line in `settlement.line`, not a new `payments` table.
  The report's `CAPTURE` line carries the cleared amount and date and is copied as an external
  item. It is matched to the capture's expectation by `PSP_CAPTURE_REF`, or by the ARN through
  its alias (ADR-0067 §5, ADR-0068).
- A second presentment either claims the capture's expectation a second time, becoming
  `DUPLICATE_EXTERNAL` or an `AMOUNT_MISMATCH` excess, or quotes only an ARN that no alias
  records (a `SECOND_PRESENTMENT` registers none, ADR-0067 §5) and becomes `UNKNOWN_EXTERNAL`.
  Either way it is parked with its break (ADR-0068 §3, §6), never absorbed.
- The Phase 7 notice's own evidence is reached from the expectation through the internal chain
  (ADR-0064 §4).

*(Added by the Phase 7 → 8 transition's consistency review, which carried the gate's repair into
this ADR.)*

The decision is constrained by facts already in the repository:

- **`INV-SET-01`, one level up.** A counterparty's report that it paid is still that
  counterparty's obligation until cash arrives.
- **`INV-RAIL-04`.** No clearing account nets two counterparties, because each is discharged
  against its own evidence.
- **One operational account per (purpose, currency)** (`ledger_account_one_operational_per_purpose_currency`,
  ledger `V002`). An account of one purpose per counterparty would need a new owner kind.
- **The posting fingerprint binds both dates** (`PostingService.java:205-228`). A recognition
  dated by the clock and replayed on a later day would conflict rather than converge, which
  `MerchantPayoutOutcomes.java:215-217` already warns about.
- **Ledger `V004` re-validates an entry once per inserted line** (`journal_entry_balances`), so
  validating an entry costs O(N²) in its line count.
- **The multi-entry lock-order rule** (DISTRIBUTED_EXECUTION §3; the seeded-accounts-sort-first rule
  until the Phase 7 → 8 transition restated it). The balance projection takes one entry's rows
  sorted by account id, so single-entry postings never cycle; two entries in one transaction do
  not share that order. A transaction posting several entries over shared rows therefore
  pre-locks the union of the platform's rows it will touch in the projection's own order before
  its first posting (`PostingService.lockBalancesInOrder`, DISTRIBUTED_EXECUTION.md §3's
  multi-entry lock-order rule), and takes any runtime counterparty's ACCOUNT row — `FOR SHARE`
  where a share lock suffices — before any projection row. Seed order is no part of the
  argument: the transition's gate found "seeded accounts sort before runtime ones" wrong on a
  longer-lived database, and silent on seeded rows against each other across two entries. Seed
  order stays load-bearing only for `SETTLEMENT_CLEARING` (`V003`). Every Phase 8 multi-entry
  transaction follows the rule (point 7).
- **`CLAUDE.md` rule 11.** A balance must be explainable from authoritative records. A clearing
  balance that cannot be decomposed into the operations it holds cannot be reconciled.

Three designs were weighed at the transition:
- A recognised cash when the counterparty reported payment.
- B posted nothing until the report and the bank statement were both present.
- C recognised the report into one shared `SETTLEMENT_IN_TRANSIT` account and the bank statement
  out of it.

## Decision

1. **A clearing position is discharged in two evidence hops, both on that counterparty's own
   position. There is no in-transit account.**

   | Step | Evidence | Posts | Opens or allocates |
   |---|---|---|---|
   | Completion (existing, unchanged) | Our own operation | The operation's existing entry to the rail's clearing position | **In the same transaction**, one settlement expectation per clearing line (ADR-0067) |
   | **Hop 1:** the report is accepted | The counterparty's accepted settlement batch | **Only the counterparty's fees**: DR `PROCESSING_COSTS` / CR the position (point 2) | Its transaction lines become items allocated to their expectations; one `REMITTANCE` expectation of \|N\| opens **on the same position** |
   | **Hop 2:** the bank statement is accepted | The bank's accepted statement | **Cash**: DR/CR `CASH_AT_BANK` against each **attributed counterparty's own position** (point 3) | Each bank line allocates to that position's `REMITTANCE` expectation |

   The "reported, awaiting cash" state is a fact about expectations, not a ledger balance. It is
   served by `GET /v1/operator/reconciliation/settlement-status` (`PENDING`, `REPORTED`,
   `CASH_CONFIRMED`, `OVERDUE`, `RESOLVED`, with the identifier trail) and by the positions report.
   Kafka, the balance projection and the events carry none of it (`CLAUDE.md` rule 12).

2. **Hop 1 posts only what the platform had not already recorded: the counterparty's fees.**
   A report's transaction lines (captures, refunds, chargebacks, reversals, dispute fees, scheme
   credits and debits, payouts and their returns) describe value that is already sitting in the
   position, posted by the completion. Posting them again would double it. They post nothing and
   become items (ADR-0068). Let F be the fee lines (processing, scheme and bank fees; a rebate
   carries the opposite direction), and N = T_in − T_out − F.

   | Source | Recognition lines (entry `POSTING`, system actor) | Remittance expectation opened |
   |---|---|---|
   | PSP report | DR `PROCESSING_COSTS` F / CR `SETTLEMENT_CLEARING` F; the mirror for a net rebate | \|N\| on `SETTLEMENT_CLEARING`, direction sign(N), key `REMITTANCE_REF`, `expected_by = value_date + funding_lag_days` |
   | Scheme cycle report | DR `PROCESSING_COSTS` F / CR `INSTANT_CLEARING` F | \|N\| on `INSTANT_CLEARING` |
   | Payout provider report | DR `PROCESSING_COSTS` F / CR `PAYOUT_CLEARING` F (the liability grows: the platform owes the provider its fee) | \|N\| on `PAYOUT_CLEARING`, typically OUTBOUND |

   - **The fee is expensed as the evidence states it.** The PSP's processing fee is then checked
     against the pinned `provider_fee_schedule` (the `CHECK` cardinality: `numeric(7,6)` rate,
     named rounding, `INV-MON-03`). A difference beyond the pinned tolerance is a `FEE_MISMATCH`
     break: a commercial dispute with no residual, because the reported fee is already in the
     books (ADR-0069). An overcharge the counterparty returns arrives as a rebate line in a later
     batch, which is new evidence and never an edit.
   - **A dispute fee is not a processing fee.** It was posted at its stage (`dispute-fee:<id>`,
     ADR-0061 §4), so its `DISPUTE_FEE` line is an allocating transaction line and posts nothing.
   - **Recognition is independent of matching.** The fees are recognised on acceptance whether or
     not a single line has matched yet, so a matcher defect can delay explanation but never
     posting.

3. **Hop 2: cash moves only on the bank's own statement** (`INV-SET-06`).
   - **Attribution is normalisation, not matching.** Each source descriptor declares a
     `remittancePattern`. The bank adapter extracts only the structured remittance reference, never
     a name or an account identifier. A line is attributed to the **unique** source whose pattern
     matches, and zero or two matches leave it unattributed (ADR-0066; the compiled register pins
     it).
   - **The recognition entry**, one per accepted statement:

     | Lines | Amount |
     |---|---|
     | `CASH_AT_BANK`: DR Σ credits, CR Σ debits, **netted to one line** | the statement's net movement, bank fees included |
     | Per attributed source s: CR its clearing position, or DR when negative | credits_s − debits_s |
     | DR `PROCESSING_COSTS` | the bank's fees |
     | `SUSPENSE_UNMATCHED`: CR Σ unattributed credits, DR Σ unattributed debits | each unattributed line opening a suspense item with an `UNKNOWN_EXTERNAL` break (cause `BANK_LINE_UNATTRIBUTED`) **in this transaction** (`INV-REC-09`, ADR-0070) |

     Cash always follows the statement, even when attribution fails. An unattributed line moves
     cash and parks its counterparty side in suspense with its owning break. It never reaches a
     position it was not attributed to (`INV-SET-05`, `INV-RAIL-04`).
   - **Bank items allocate to remittances** (`REMITTANCE_REF`, then the value-date group, ADR-0068).
     A bank amount that differs from its remittance leaves a remainder or parks an excess with a
     `SETTLEMENT_MISMATCH` (cause `REMITTANCE_DIFFERS`). It is never absorbed (`INV-REC-08`).
     `REMITTANCE_DIFFERS` is the only `SETTLEMENT_MISMATCH` cause that admits a `WRITE_OFF`, a
     `TRANSFER_TO_ACCOUNT` or, for a CREDIT excess, a `RECOGNISE_GAIN` (after the minimum age,
     four-eyes). The statement causes below (`STATEMENT_GAP`, `OPENING_BALANCE`) close only
     `EVIDENCED` (ADR-0069's per-type table; the Phase 7 → 8 transition's consistency review, A3;
     the gain added by its re-check, R14).
   - **Continuity.** DR−CR of `CASH_AT_BANK` per currency equals the closing balance of the
     highest-sequence accepted statement of an unbroken chain. A sequence gap, or an opening
     balance different from the previous closing, raises `SETTLEMENT_MISMATCH` (`STATEMENT_GAP`),
     and the cash proof fails loudly until evidence fills the gap.
   - **The simulated bank opens at zero** (owner decision O4, below). A non-zero first opening
     raises `SETTLEMENT_MISMATCH` (cause `OPENING_BALANCE`) and posts nothing. The only honest
     counter-account for an opening balance is equity, which is Phase 14's.
   - **Cash is never adjusted to fit.** No resolution kind names `CASH_AT_BANK`, and the binding
     that closes reconciled positions to free adjustments covers it (ledger `V015`, re-stated by
     `V018`; `422 ledger.AdjustmentOnReconciledPosition`, ADR-0071).
   - **Exactly two posters.** `CASH_AT_BANK` is posted by the recognition of an accepted bank
     statement and by the repudiation of one (point 10), and by nothing else. A static rule refuses
     any other code that names it.

4. **When finality, reporting and settlement occur, per flow.** The completion postings and keys
   are Phase 5–7's and unchanged. Phase 8 adds the last three columns.

   | Flow | Completion (existing key) | Finality | Expectation opened (kind, direction) | Reported (hop 1) | Settled (hop 2) |
   |---|---|---|---|---|---|
   | Card capture | `payment-capture:<attemptId>`, DR `SETTLEMENT_CLEARING` gross | Revocable by chargeback (a new movement) | `CARD_CAPTURE`, INBOUND | PSP `CAPTURE` line allocated | Bank credit matching the batch's remittance: DR `CASH_AT_BANK` / CR `SETTLEMENT_CLEARING` |
   | Card refund | `payment-refund:<refundId>`, CR `SETTLEMENT_CLEARING` | Final to us once the PSP reports it completed | `CARD_REFUND`, OUTBOUND | `REFUND` line | Netted in the remittance |
   | Chargeback; won; dispute fee | `dispute-chargeback:`, `dispute-won:`, `dispute-fee:<disputeId>` (against a closed merchant the share parks in `CHARGEBACK_RECOVERABLE`, and the clearing line is unchanged) | Provisional until the network rules; final at `WON`; final | `CHARGEBACK` OUTBOUND; `CHARGEBACK_REVERSAL` INBOUND; `DISPUTE_FEE` OUTBOUND | `CHARGEBACK`, `CHARGEBACK_REVERSAL`, `DISPUTE_FEE` lines | Netted in the remittance |
   | Card processing fee | Never posted before Phase 8 | — | None | **Recognised at acceptance**: DR `PROCESSING_COSTS` / CR `SETTLEMENT_CLEARING` | The remittance is net of it |
   | Instant pay-in | `payment-execution:<attemptId>`, DR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` | `PUSH_PAY_IN`, INBOUND (the confirmation's announced cycle recorded as the expectation's cycle attribute) | Cycle report `CREDIT_IN` line | The cycle's net against `INSTANT_CLEARING` |
   | Unmatched pay-in confirmation | `unmatched-confirmation:<rail>:<ref>`, DR `INSTANT_CLEARING` / CR `SUSPENSE_UNMATCHED`, for each cause the parking records (`UNATTRIBUTED`, `ATTEMPT_CONCLUDED`, `AMOUNT_MISMATCH`; payments `V023`) | Final | `UNMATCHED_CONFIRMATION`, INBOUND (the parking's stored cycle as its cycle attribute), plus a CREDIT suspense item with its `UNKNOWN_EXTERNAL` break (`PARKED_ON_RECEIPT`) | `CREDIT_IN` line | The cycle's net; the suspense is released only by resolution |
   | Instant withdrawal; return payment | `wallet-withdrawal:<withdrawalId>`; `payment-refund:<refundId>`, CR `INSTANT_CLEARING` | `FINAL_ON_ACCEPTANCE` (`INV-REV-03`) | `PUSH_WITHDRAWAL` (its stored cycle recorded as the expectation's cycle attribute, never a key); `PUSH_RETURN` (no cycle at completion: the cycle is learned from the report and recorded on the item, closing ADR-0062's follow-up with no payments migration); both OUTBOUND | `DEBIT_OUT` line | The cycle's net |
   | Merchant payout | `merchant-payout:<payoutId>`, CR `PAYOUT_CLEARING` | Instructed; returnable by the beneficiary bank | `MERCHANT_PAYOUT`, OUTBOUND | Payout report `PAYOUT_EXECUTED` line | Bank debit matching the day's remittance: DR `PAYOUT_CLEARING` / CR `CASH_AT_BANK` |
   | Payout return (new, ADR-0073) | `merchant-payout-return:<payoutId>`, DR `PAYOUT_CLEARING` / CR `MERCHANT_PAYABLE` (a closed merchant's payable account is closed, so the return does not apply and parks with its break, ADR-0073 §5) | A new operation; the payout stays `COMPLETED` | `PAYOUT_RETURN`, INBOUND, reached through its operation and opening no key of its own (ADR-0067 §5) | `PAYOUT_RETURNED` line | Netted in the day's remittance |
   | Wallet book payment, book refund, transfer | Wallet ↔ payable, wallet ↔ wallet | `FINAL_ON_POSTING` | **None.** `SettlementModel.NONE` is `INV-SET-01`'s documented per-rail guarantee (ADR-0059 §4) | — | — |

   *(The Phase 7 → 8 transition's consistency review, A5 and C11: the withdrawal's cell read "its
   stored cycle as a key". The announced or stored settlement cycle is an attribute recorded on
   the expectation row, never an `expectation_key` kind. It is a tie-breaker and a report
   dimension at matching, and a different cycle is a `TIMING_DIFFERENCE` (ADR-0067 §5,
   ADR-0068 §6).)*

   The unmatched confirmation's parking has carried its `cause` and `attempt_id` since the
   Phase 7 → 8 transition's repair, and its suspense item keys on them (`P8-TSK-020`, ADR-0070).
   An `ATTEMPT_CONCLUDED` or `AMOUNT_MISMATCH` parking is attributed to a named attempt. Its
   natural resolution is a four-eyes `TRANSFER_TO_ACCOUNT` crediting that attempt's
   counterparty, or a return to the payer, and never a guess (ADR-0071). An `UNATTRIBUTED`
   parking names no one.

   A card capture's finality does not end in Phase 8, because the dispute window is not modelled
   as closing. Each source's position is read from its counterparty's own declaration when `app`
   composes the register (`RailCapabilities.clearingPurpose()` for a rail whose `settlement()` is
   not `NONE`, `merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE` for the payout provider,
   `CASH_AT_BANK` for the bank). Neither `settlement` nor `reconciliation` code names a
   `*_CLEARING` purpose (`INV-SET-05`, ADR-0064).

5. **Four operational purposes, each arriving with its first poster** (ADR-0040; the ledger
   `V011`–`V014` ceremony: the four generated constraints recreated, one seeded row per currency
   (EUR, GBP, USD) with hand-minted UUIDv7 literals below the `2026-09-28T00:00:00Z` ceiling and
   literal timestamps, `OperationalChartMigrationTest.SEED_MIGRATIONS` extended, `SEEDED_TYPES`
   moved to `Map.ofEntries` (9 → 13), `everySeededIdSortsBeforeEveryRuntimeId` green):

   | Purpose | Type | Migration (task) | Posted by, and only by |
   |---|---|---|---|
   | `PROCESSING_COSTS` | EXPENSE | ledger `V016` (`P8-TSK-009`) | Hop-1 recognition (counterparty fees) and hop-2 recognition (bank fees), from their evidence, and a repudiation's reversal of either |
   | `RECONCILIATION_LOSSES` | EXPENSE | ledger `V017` (`P8-TSK-015`) | An approved `WRITE_OFF` resolution |
   | `RECONCILIATION_GAINS` | REVENUE | ledger `V017` (`P8-TSK-015`) | An approved `RECOGNISE_GAIN` resolution, after the pinned minimum age |
   | `CASH_AT_BANK` | ASSET | ledger `V018` (`P8-TSK-016`) | Bank-statement recognition, and the repudiation of a statement |

   Ledger `V015` is `P8-TSK-006`'s adjustment change (ADR-0071). `AccountPurpose.reconciledPositions()`
   is the three clearings, `SUSPENSE_UNMATCHED`, `CASH_AT_BANK`, `PROCESSING_COSTS`,
   `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS`, and each of `V016`–`V018` re-states the
   binding function's generated list. Gains and losses are two accounts, never one that nets
   both. There is no equity or opening-balance account in Phase 8.

6. **Dates come from stored facts, never from a clock read at the posting.**
   - `posting_date` is `batch.accepted_on`: the UTC business date stamped **once** on the batch
     row in the acceptance transaction, and read only from the row thereafter. It falls in an open
     period by construction, and it is never back-dated to the bank's booking date.
   - `value_date` is the value date the evidence states for the batch. A late earlier-dated file
     is accepted in arrival order; its value date precedes its system time, which ledger
     `V004:38-42` permits (`INV-SET-03`).
   - A retried acceptance, a crash-and-reclaim or a replay on a later clock day computes the same
     fingerprint and converges on the key.
   - The same discipline governs every Phase 8 poster, each decided in its own ADR: a park's
     entry takes the park row's `decided_on` and the item's settlement date (ADR-0070), a
     resolution's lines take the frozen `proposed_on` (ADR-0071), and a payout return takes its
     item's batch `accepted_on` and settlement date (ADR-0073).
   - Phase 5–7's clock-read posting dates are not retrofitted. `X-TSK-009` reconciles
     `DOMAIN_MODEL.md:116-121` and `LEDGER_MODEL.md:41-44` with that practice.

7. **A batch is recognised once, keyed by the batch, last in its transaction, and bounded**
   (`INV-SET-04`).
   - **The key.** `settlement-batch:<batchId>` under scope `ledger.post`, reference = the batch
     id, posted by `settlement` through `PostingService` as the system actor. No module writes
     journal rows directly (`INV-LED-04`).
   - **One effect, whatever the retention.** The posting key's one-effect guarantee is
     `platform.idempotency_record`'s primary key, and that row's retention is not a financial
     guarantee (it carries an `expires_at`). So every recognition also has domain uniques that do
     not depend on it, as `INV-IDEM-02` requires ("unique on (process, entity, period)"): the
     content unique on `file`, the live-batch uniques `(source_id, external_batch_ref, currency)`
     and `(source_id, currency, statement_sequence)` on `batch`, `UNIQUE (batch_id)` on the run,
     and the conditional `PARSED → ACCEPTED`. A re-delivery, a conflicting re-issue, a retried
     acceptance or a later-day replay produces no second effect.
   - **Ten acceptors.** The file is claimed `FOR UPDATE SKIP LOCKED`, and the source row is locked
     `FOR UPDATE` to assign a gapless `source_sequence` under `UNIQUE (source_id,
     source_sequence)`. Ten instances produce one entry, one run and gapless sequences.
   - **Order inside the acceptance transaction.** Source sequence and `accepted_on`; the run,
     items and keys; the remittance expectation, or for a statement the unattributed suspense items
     and their breaks; batch `ACCEPTED`; event and audit; **then the recognition posting, as the
     last statement**. The hot clearing projection row is therefore locked for as short a time as
     possible. A crash anywhere leaves nothing, and the file is swept again.
   - **Money.** Amounts are folded with `Money`, never SQL `SUM`. The declared trailer net must
     equal the fold, or the file was already `REJECTED(CONTROL_TOTAL_MISMATCH)` (`INV-SET-07`,
     ADR-0066).
   - **Zero lines are omitted.** An all-zero batch is accepted with `posting_omitted = true` and
     `journal_entry_id NULL`.
   - **At most 16 lines**, pinned by a test, because `V004` re-validates the entry once per
     line. The per-item detail lives in expectations and items, not in journal lines.
   - **Only seeded accounts, one entry.** Recognition touches the positions, `PROCESSING_COSTS`,
     `CASH_AT_BANK` and `SUSPENSE_UNMATCHED`, all seeded, in one entry, whose projection rows the
     ledger takes sorted by account id. DISTRIBUTED_EXECUTION §3's Phase 8 lock-order row puts
     ledger projection rows last: **postings are last in every transaction.** A Phase 8
     transaction posting several entries over shared rows pre-locks the union of the platform's
     rows it will touch in the projection's own order before its first posting
     (`PostingService.lockBalancesInOrder`, DISTRIBUTED_EXECUTION.md §3's multi-entry lock-order
     rule), and takes any runtime counterparty's ACCOUNT row — `FOR SHARE` where a share lock
     suffices — before any projection row. A repudiation does this (point 10), and so does any
     resolution approval that posts more than one entry. The order relied on is the pre-lock's,
     never the seeds' stamps (the Context).

8. **"Explained" is an identity, checked by report-only verifiers** (`INV-REC-06`). For each
   clearing position P and currency c, with s(INBOUND) = +1 and s(OUTBOUND) = −1:

   ```
   DR−CR(P,c) = Σ_{expectations e on P,c} s(e)·(amount − allocated − resolved)
              − Σ_{allocating items i on P,c, status ∈ {PENDING, UNMATCHED}} s(i)·(amount − allocated − parked − offset)
   ```

   Remittance expectations are expectations. Fee items are excluded, because their effect is in
   the recognition entry. For a capture of g with a PSP fee f, settled by one report and one bank
   credit:

   | Step | DR−CR(`SETTLEMENT_CLEARING`) | Expectation remainders − unallocated items |
   |---|---|---|
   | Capture completed | g | g − 0 |
   | Report accepted: fee recognised, remittance g − f opened, capture item pending | g − f | (g + (g − f)) − g |
   | Report matched | g − f | (g − f) − 0 |
   | Statement accepted: cash g − f, bank item pending | 0 | (g − f) − (g − f) |
   | Bank item matched | 0 | 0 − 0 |

   The identity also holds after over- and under-matches, after parks (the park's entry and the
   item's `parked` amount move together, so both sides change by the same amount), after cash, and
   after resolutions (a `WRITE_OFF` credits P and raises `resolved`). Beside it:
   - **Suspense:** CR−DR(`SUSPENSE_UNMATCHED`, c) equals the CREDIT items remaining less the DEBIT
     items remaining, plus Phase 7's unmatched confirmations not yet adopted — a named term that
     reads 0 once `P8-TSK-020` adopts them — and every item with a remainder names an existing
     break.
   - **Cash:** point 3's continuity rule.
   - **Completeness** (ADR-0067 §9): every journal line on the three clearings and on
     `SUSPENSE_UNMATCHED` is known to reconciliation or settlement. A line is known when an
     expectation names its `(journal_entry_id, ledger_account_id)`, when a suspense item owns it
     (`INV-REC-09`), or when its entry is a batch's, park's, resolution's, repudiation's or
     payout return's. *(The suspense item was missing from this list until the Phase 7 → 8
     transition's consistency review, A7. Without it, an unmatched confirmation's suspense line,
     which no expectation names, would read as unknown forever.)* The count reads 0 on the
     clearing purposes over the Phase 7 history once the backfill has run (`P8-TSK-007`).
     `SUSPENSE_UNMATCHED` reaches 0 with `P8-TSK-020`, which adopts the Phase 7 parkings as
     suspense items.

   The verifiers have the `TrialBalance` shape: lock-free, "the scrape is the schedule", report
   and never repair. They are computed in `app` in one `REPEATABLE READ` transaction on one
   connection, composing the ledger's `BalanceDerivation` with reconciliation's and settlement's
   read APIs, folded with `Money`. They publish verdict counts only, behind a refresh floor, NaN
   never zero: `finapp.reconciliation.position.proof`, `finapp.reconciliation.cash.proof` and
   `finapp.reconciliation.line.unattributed`, each of which must read 0. No amount enters a
   metric (ADR-0072). The positions, cash and provider costs are audited operator reports.

9. **Unexplained value leaves a position only by parking with its break, and reaches profit or
   loss only by an approved resolution.**
   - **Parking** (ADR-0070) moves an item's unexplained remainder u into suspense, in the
     transaction that decides it: INBOUND DR P u / CR `SUSPENSE_UNMATCHED` u, OUTBOUND the mirror.
     An unpark or a correction offset is the exact inverse. These entries are aggregated per
     transaction and position (at most 4 lines), keyed `recon-suspense:<parkId>`, and touch only
     seeded accounts.
   - **Losses and gains** are `ADJUSTMENT` entries that `ledger.AdjustmentService` posts at
     four-eyes approval, with origin `RECONCILIATION` and a closed reason code (ADR-0071).
     `WRITE_OFF` is DR `RECONCILIATION_LOSSES` / CR P (or CR `SUSPENSE_UNMATCHED`).
     `RECOGNISE_GAIN` is DR `SUSPENSE_UNMATCHED` / CR `RECONCILIATION_GAINS`, for a CREDIT suspense
     item older than the rule set's `gain_min_age_days` (seeded 90, owner decision O5, below).
     Each kind is available only where ADR-0069's per-type table admits it, and that table is the
     one authority. `REVERSAL_MISMATCH` and `REFUND_MISMATCH` admit no gain, because their value
     belongs to a merchant or a customer and goes back by `TRANSFER_TO_ACCOUNT` or `EVIDENCED`.
     `CURRENCY_MISMATCH` admits no gain either, because a currency break is never income. *(The
     Phase 7 → 8 transition's consistency review, A1.)*
   - **No tolerance absorbs value** (`INV-REC-08`, ADR-0068). A principal difference of one minor
     unit is a remainder or a parked item with its break, never a quiet write-off.

10. **Accepted evidence is reversed only by a four-eyes repudiation, through `ReversalService`.**
    An accepted batch proven fabricated or mis-normalised is repudiated by a `REPUDIATE_BATCH`
    resolution (reason `EVIDENCE_REPUDIATED`, `RECONCILIATION_RESOLVE`), proposed by one person and
    approved by another. In the approval transaction:
    1. `ReversalService` reverses the recognition entry (scope `ledger.reverse`, key
       `settlement-batch:<batchId>`; the `TransferReversal` precedent, `INV-REV-01`, bounded by
       `V009`);
    2. every allocation of the batch's items gets an append-only counter-allocation
       (`reverses_allocation_id`), which restores the expectation remainders. The expectations
       those allocations filled reopen. A `REMITTANCE` expectation that the repudiated report's
       acceptance opened leaves the proof together with the fee entry that justified it. It
       closes by the repudiation (`RESOLVED_BY_ADJUSTMENT`), whether it was paid or not. If a
       bank item of a statement that stands had already allocated it, that allocation is
       counter-allocated first, in the same transaction. The bank item then reopens
       `MATCHED → UNMATCHED` by the item machine's repudiation edge
       (`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §5.4) and waits for the genuine report's
       remittance.

       For a capture of g with a fee f, the position then reads DR−CR = f (the fee's reversal),
       and the identity's right-hand side reads g − (g − f) = f: the capture's reopened remainder,
       less the reopened bank item;
    3. the items' parks are released by an unpark, and the suspense items the recognition itself
       opened are released by the repudiation. A suspense item that a posting resolution already
       released is never released twice (ADR-0070 §10):
       - for a statement's unattributed item (`BANK_UNATTRIBUTED`), the reversal still carries
         its suspense line, which opens a new item on the opposite side, owned by a new
         `PROCESSING_ERROR` break;
       - an already-released parked item posts no unpark, and what its position then owes is
         `P8-TSK-023`'s question;
    4. the items and the batch move to `REPUDIATED`, which frees the live-batch unique;
    5. `settlement.SettlementBatchRepudiated` is published.

    The list names effects, not their order. The transaction writes in ADR-0064 §6's order for
    the repudiation seam: `reconciliation`'s rows, then `settlement`'s batch transition, then the
    `ledger` postings last. The reversal and the unparks are several entries over shared hot rows,
    so their union is pre-locked in the balance projection's order before the first of them
    (`PostingService.lockBalancesInOrder`, point 7).

    A payout return already applied from an item of the repudiated batch stands as a merchant
    fact, and its reopened `PAYOUT_RETURN` expectation ages into `MISSING_EXTERNAL` (ADR-0073).
    `P8-TSK-023` states what follows for it. The item's `REPUDIATED` and its
    `MATCHED → UNMATCHED` reopening (a bank item of another batch, step 2), the expectations'
    reopening edges, the suspense item's `REPUDIATION` origin (ADR-0070 point 2), the
    `REPUDIATE_BATCH` kind and its batch subject arrive with
    `P8-TSK-023`'s reconciliation `V010`, after `P8-TSK-022`'s `V009` (renumbered when `P8-TSK-016` took `V008`).

    *(The remittance rule, the write order, the already-released rule and the migration were
    settled by the Phase 7 → 8 transition's consistency review: A8, A9, A10 and B5.)*

    The file and its content are retained, and the genuine file is then re-presented and accepted
    normally. If the genuine file was already rejected `CONFLICTING_BATCH` beside the fabricated
    batch, it is readmitted instead (ADR-0066 §8). Repudiating a statement is the only poster of
    `CASH_AT_BANK` besides statement recognition. It is the recovery for the case the attestation
    and pull controls exist to
    prevent (`INV-SET-07`, ADR-0066), a fabricated report and statement discharging clearing into
    fictitious cash, and for our own adapter's mis-normalisation.

**Owner decisions settled at the transition.** Each is recorded as a transition decision that the
owner may revisit.
- **O4:** the simulated bank opens at zero, and an equity account waits for Phase 14 (point 3).
- **O5:** the gain's minimum age is 90 days, four-eyes (point 9; ADR-0070).
- **O6:** if scope must shrink, cut `P8-TSK-021`, then `P8-TSK-019`, then `P8-TSK-023`, keeping
  `P8-TSK-023` if possible. Without it, accepted evidence has no reversal path and `CASH_AT_BANK`
  has one poster.
- **O2:** payout returns are applied automatically, with the four-eyes transfer as the fallback
  (ADR-0073). If `P8-TSK-019` is cut, a returned payout parks with its break, and a four-eyes
  `TRANSFER_TO_ACCOUNT` restores the payable.

O1 (two disjoint roles), O3 (door refusal of PII-bearing files) and O7 (a high-value threshold of
1,000.00 per currency) are recorded in ADR-0071, ADR-0066 and ADR-0069.

## Alternatives Considered

### Recognise cash when the counterparty reports payment (A)
Pros: one hop, one posting per batch; `SETTLEMENT_CLEARING` reaches zero as soon as the PSP's day
is reported.
Cons: it is `INV-SET-01` one level up. A report of payment is still the counterparty's
obligation, and the ledger's cash would state money the bank never received. The bank statement
would have nothing left to discharge, so a missing remittance would be invisible.

### Post nothing until the report and the bank statement are both present (B)
Pros: the ledger moves only on complete evidence, and cash moves only on the bank (B's merit,
kept as point 3).
Cons: it couples posting to matching. A matcher defect, an ambiguity or a late statement leaves
the counterparty's fees unrecognised and the position overstated for as long as the pair is
incomplete. Recognition becomes an outcome of the comparison instead of a fact the evidence
states.

### Two hops through one shared `SETTLEMENT_IN_TRANSIT` account (C)
Pros: keeps "reported, awaiting cash" visible as a balance and keeps recognition independent of
matching (C's merit, kept).
Cons: one account would net the PSP's receivable against the payout provider's payable, which is
`INV-RAIL-04`'s netting reached through settlement. A remittance difference could then no longer
be attributed to its counterparty from the ledger. It also doubles every recognition entry's
lines for information the expectations already carry.

### One in-transit account per counterparty
Pros: the in-transit balance without the netting.
Cons: ledger `V002` allows exactly one operational account per (purpose, currency), so it needs
a new owner kind in the ledger's most constrained table. It doubles recognition entries against
`V004`'s O(N²) validation, and it adds no information that the `REMITTANCE` expectation on the
position does not already hold.

### Post the recognition on the bank's booking date (B)
Pros: the ledger's date equals the counterparty's.
Cons: it back-dates into periods that Phase 14 will close (`INV-ACC-03`), and the evidence's own
date already has a column, `value_date`. A stored `accepted_on` gives a posting date in an open
period that is still stable under a later-day replay.

### Discharge in chunks under a lease, with a `HELD` batch state (B)
Pros: bounded transactions for very large files.
Cons: a partially discharged batch is half a settlement posted. The file bounds (8 MiB and 50,000
lines, ADR-0066) keep acceptance within one transaction, and a control-total mismatch rejects the
file whole (`MODULE_ARCHITECTURE.md`: "a partially corrupt file fails the batch").

### Accepted evidence is never reversed (C)
Pros: no reversal path to abuse.
Cons: it is unsafe once evidence can be fabricated past the controls or mis-normalised by our own
adapter. The fictitious cash would stand, with only a resolution per item to unwind it.
Four-eyes repudiation through `ReversalService` (point 10) is the controlled path.

### Open a non-zero first statement against an equity or opening-balance account
Pros: the cash proof passes from the first statement.
Cons: an equity account is Phase 14's chart and close. Posting against a placeholder account
would create an unexplained balance on day one. A `SETTLEMENT_MISMATCH(OPENING_BALANCE)` break
that posts nothing keeps the disagreement visible instead (owner decision O4).

## Consequences

Positive:
- Every clearing position acquires a discharge that is keyed to its own counterparty's evidence,
  and cash appears in the books only when the bank reports it.
- The per-rail cost meter that Phase 7 deferred becomes a ledger fact. Provider costs sit in
  `PROCESSING_COSTS`, recognised from evidence and read through the audited provider-costs report
  (ADR-0072).
- Recognition does not wait on matching. A late, wrong or ambiguous comparison delays explanation,
  never posting, and the position identity says exactly what remains unexplained.
- Replays converge. Stored dates and domain uniques make recognition idempotent per batch without
  relying on retained idempotency rows.
- `SETTLEMENT_CLEARING` stays what ADR-0061 said it would be, "Phase 8's opening position",
  explained line by line after the opening-position backfill (ADR-0067).

Negative:
- The ledger alone cannot say which captures have been reported. That lives in the expectations,
  so a balance-sheet reader sees one clearing balance per counterparty and needs
  `/settlement-status` or the positions report for the split.
- A wrong fee is expensed as reported, and the commercial dispute is a `FEE_MISMATCH` break with no
  money. Recovery needs the counterparty's own rebate line.
- A missing statement leaves the cash proof failing until the gap is filled. It is loud by design,
  and it is still an alert that operators must answer.
- Acceptance serialises per source on the source row, and every recognition touches one hot
  projection row per position and currency. This is mitigated by posting last and bounding entries
  to 16 lines; the partition path (source, currency) is recorded.
- A non-zero opening balance cannot be represented until Phase 14.

Operational impact: `finapp.reconciliation.position.proof`, `finapp.reconciliation.cash.proof`
and `finapp.reconciliation.line.unattributed` alert on anything but 0;
`finapp.reconciliation.expectation.overdue` covers a remittance whose cash never arrives
(`MISSING_EXTERNAL`, HIGH for `REMITTANCE`). The settlement-status trail and the positions,
suspense and provider-costs reports are audited operator reads. The spans are `settlement.accept`
and `reconciliation.resolve`.
Security impact: cash moves only on evidence that was pulled over its source's confined
credential or attested by a second person (`INV-SET-07`, ADR-0066). The bank's account reference
stays opaque (`INV-RAIL-03`). Amounts never enter metrics, events or logs (ADR-0072;
`RESTRICTED-FINANCIAL`). Events carry identifiers only: `settlement.SettlementBatchAccepted`
(`journalEntryId?`) and `settlement.SettlementBatchRepudiated` (`reversalEntryId`).
Financial impact: four operational purposes (twelve seeded accounts); the three clearings
discharged against evidence; `SUSPENSE_UNMATCHED` gains a releaser; profit or loss moves only by
recognised fees and approved resolutions; no settlement instruction, FX conversion, GL mapping or
close.

## Invariants / Constraints

`INV-SET-01` (at the last hop), `INV-SET-03`, `INV-SET-04` (new: recognised once, from stored
evidence), `INV-SET-05` (new: one source per settling position, no cross-counterparty discharge),
`INV-SET-06` (new: cash only on the bank's statement), `INV-SET-07` (new: whole and authenticated
evidence), `INV-REC-06` (new: every reconciled position explained by its open items), `INV-REC-08`
(new), `INV-REC-09` (new), `INV-RAIL-03`, `INV-RAIL-04`, `INV-IDEM-02`, `INV-LED-01`, `INV-LED-04`,
`INV-BAL-03`, `INV-REV-01`, `INV-HIST-01`, `INV-MON-01`…`INV-MON-06` (a currency mismatch is never
converted, `INV-MON-04`).

## Follow-up

- **Implemented so far** *(every other statement is the decided design, corrected by the tasks that build it)*: `P8-TSK-009` (2026-09-29) shipped HOP 1 for the card PSP — point 2's recognition (DR `PROCESSING_COSTS` / CR `SETTLEMENT_CLEARING` for the fee fold, the mirror for a net rebate, honestly omitted at zero), the `REMITTANCE` expectation of |N| keyed `REMITTANCE_REF` with `expected_by = value date + funding_lag_days`, and every line an item in a run born in the acceptance transaction. Two recorded build facts: the batch's stored value date IS its business date until a format carries a distinct one, and the recognition posts before the batch's accepting `UPDATE` (the honesty `CHECK` wants the entry id in that statement) — the last CONTENDED write, the rows after it the transaction's own claims.
  `P8-TSK-016` (2026-09-30) shipped HOP 2 — `CASH_AT_BANK` (ledger `V018`, ASSET, one row per
  currency, joining `reconciledPositions()` so a `MANUAL` line is refused at both ranks), the
  `SIM_STATEMENT_TAGGED` v1 statement (settlement `V005`: the statement's sequence and signed
  opening and closing balances on the batch, the live statement-sequence unique), attribution at
  parse (`SettlementSources.attribute`: the unique FULL match of the compiled patterns, written on
  the line), point 3's recognition (`BankRecognition`: DR/CR `CASH_AT_BANK` the statement's net on
  one line, each attributed source's clearing position the opposite of its lines' fold, the bank's
  fees DR `PROCESSING_COSTS`, and `SUSPENSE_UNMATCHED` a CREDIT line of the unattributed credits and
  a DEBIT line of the unattributed debits, never netted), continuity (`StatementChain`), the cash
  proof and the single-poster rule (`CashAtBankHasOnePosterTest`). Recorded deviations: (1) point
  7's order — the recognition posts BEFORE the batch's accepting `UPDATE`, as `P8-TSK-009` built,
  and each unattributed line's suspense item is inserted AFTER the posting through the intake's new
  `recognised` call, carrying the entry id whole (ADR-0070's constraint, its first option); the
  unattributed lines' breaks and the items' `PENDING → PARKED` are written before it, in the
  intake; (2) the backlog's "no settlement or reconciliation migration" did not hold — the
  statement's columns and unique were settlement `V005`'s to add, and the item's bank types,
  nullable position and attribution reconciliation `V008`'s (the plan renumbered `-022` and `-023`
  to `V009` and `V010`); (3) the statement's value date is its closing balance's date (the
  batch's business date), point 6's rule unchanged; (4) point 3's "exactly two posters" holds one
  until `P8-TSK-023` builds the repudiation.
- `P8-TSK-009` (`PROCESSING_COSTS`, acceptance, fee recognition and the remittance expectation),
  `P8-TSK-016` (`CASH_AT_BANK`, attribution, bank recognition, continuity, the cash proof and the
  single-poster rule), `P8-TSK-015` (`RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` with
  their resolutions), `P8-TSK-017` and `P8-TSK-018` (the scheme's and the payout provider's
  positions discharged the same way), `P8-TSK-007` (the position proof and the completeness
  verifier), `P8-TSK-012` (the fee check), `P8-TSK-023` (repudiation, with reconciliation
  `V010`), `P8-TST-001` (the proofs and the trial balance in every round of the storm),
  `P8-DOC-001`.
- **The repudiated report's `REMITTANCE`, now decided** (point 10, step 2). This read "recorded
  for `P8-TSK-023`'s design, not decided here": the paid case needed a rule the item machine did
  not have. The Phase 7 → 8 transition's consistency review (A9) gave the machine that edge, and
  an approved `REPUDIATE_BATCH` is its only driver: the repudiated batch's own items leave
  `MATCHED` by `→ REPUDIATED`; a bank item of another batch whose allocation named the
  repudiated batch's remittance expectation reopens `MATCHED → UNMATCHED`, that allocation
  counter-allocated in the same transaction. The batch's own items never reopen. *(This read
  "for two kinds of item: the repudiated batch's own items, and a bank item" until the
  transition's re-check, R2.)* `P8-TSK-023` builds it and proves it with the position proof as
  its test. The task also carries the two inputs point 10 records: the already-released suspense
  item (ADR-0070 §10) and the return applied from a repudiated batch (ADR-0073).
- Annotations at the transition: ADR-0040 (the four purposes) and ADR-0060 §6 (the cost meter
  settled, with ADR-0072); ADR-0057 (its payout-return follow-up, paid by ADR-0073) and ADR-0062
  (the return's cycle learned from the scheme's report; §7's trigger not fired, ADR-0073).
  ADR-0059 §5 says the clearing-notice record with the cleared amount is Phase 8's. The
  Context's clearing-level evidence answers it: the PSP report's line is that record, and hop 1
  owns it.
- `X-TSK-009`: the posting-date doctrine reconciled with Phase 5–7 practice, with no retrofit.
- Recorded, not scheduled: an equity account for a non-zero opening (Phase 14); partitioning
  acceptance and allocation by (source, currency); incremental watermarks for the verifiers;
  settlement instructions and liquidity management (never originated in Phase 8).
- The Phase 8 review reads this ADR against the code before accepting it.
