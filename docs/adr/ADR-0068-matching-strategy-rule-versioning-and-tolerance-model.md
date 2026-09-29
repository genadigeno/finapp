# ADR-0068 — Matching strategy, rule versioning and tolerance model

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Reconciliation · Settlement · Ledger
Supersedes: nothing. Writes the Phase 8 decision `docs/adr/README.md` anticipates — "Matching
strategy, rule versioning and tolerance model" — and `DELIVERY_PLAN.md` Phase 8 §14 asks for.
Applies `INV-HIST-04` to its matching element, after fees (ADR-0050) and routing (ADR-0060).
Amends `INV-REC-04`; catalogues `INV-REC-07` and `INV-REC-08`. Builds on ADR-0064 (the two
modules), ADR-0065 (the accounting) and ADR-0067 (the expectations it matches against).

*(The title is the anticipated one, the same in the README and the Phase 8 plan. It was
shortened by the Phase 7 → 8 transition's consistency review, B10, from a sentence that states
the decision: matching allocates by key in acceptance order under a pinned rule set and stores
every candidate it saw; a rule change governs only later decisions; and no tolerance absorbs
value already in a position.)*

## Context

Phase 8 answers one question, per counterparty, position, currency and item: does our internal
state match what the PSP, the instant scheme, the payout provider and our bank say happened?
ADR-0067 supplies the internal side. Every externally settling completion opens a **settlement
expectation** in its own transaction: the immutable facts of one clearing line, plus its
disposition. ADR-0066 supplies the external side. A counterparty's accepted batch is normalised
into canonical lines, and each line is copied into reconciliation as an **external item**
(ADR-0064). Matching is the step between the two. It decides which external value explains which
internal value, and what happens to whatever nothing explains.

The gate is explicit: "every match records the rule version and tolerance that produced it", and
a matching job that crashes mid-batch "resumes without duplicate or lost matches"
(`PHASE_GATES.md` Phase 8). `INV-REC-04` asks that "the same inputs always produce the same
matches". The delivery plan's risk list names "non-deterministic matching that cannot be
explained to an auditor".

Six failure shapes make this a financial decision rather than a search problem:

1. **A tolerance that absorbs value.** "Match if within 0.05" makes a difference disappear from
   both sides. It is a write-off that nobody approved and nobody recorded (`INV-BAL-03`,
   `INV-REC-02`).
2. **An order decided by scheduling.** Two lines can claim one expectation: the PSP settles a
   capture twice, one line appears in two files, ten matcher instances work one batch. If the
   winner is whichever instance ran first, the outcome cannot be reproduced, and an
   over-allocation creates explained value that does not exist.
3. **"The same inputs" is not a fact over time.** The expectations a decision could see depend on
   what had been recorded when it ran: a capture the sweeper resolved an hour after the PSP
   settled it, a key whose first writer won, a grace window that expired on the database clock. A
   replay that re-resolves candidates today reports history as divergence.
4. **A rule change that rewrites history.** A corrected rule that re-matches committed
   allocations moves money between explanations with no record and no second person.
5. **Heuristic matching.** Amount-and-date proximity, a subset-sum search over a bank credit, or a
   learned model all produce plausible matches that are wrong in exactly the cases that matter.
   None can be explained from stored data, and the search cost is combinatorial.
6. **Ordinary lag treated as a break.** A report arriving before its webhook, or a bank credit
   before its report, is normal. Parking every unallocated line at once turns routine ordering
   into breaks and suspense churn. Waiting indefinitely leaves value unowned.

The platform already holds the keys a deterministic matcher needs, each with a unique arbiter
where it is minted: the PSP's `capture_provider_reference` (payments `V003`); the acquirer
reference in `payments.clearing_record`, "Phase 8's primary match key" (`DATA_CLASSIFICATION.md`),
which carries no amount and no date (`V015`); the refund's `provider_reference` and our `rfd-…`;
`provider_dispute_reference`; the scheme's `scheme_reference` and our `end_to_end_reference`; the
payout's `provider_reference` and our `pyo-…`. Since the Phase 7 → 8 transition's repair, a scheme
reference has one claim per `(rail, scheme_reference)` in `payments.scheme_execution_claim`
(payments `V023`), taken by whichever producer explains it (a pay-in, a withdrawal, a return or a
parking) before money moves. The card rail's second, different clearing of one capture
(`SECOND_PRESENTMENT`) registers no `payments.clearing_record` row and rests in the retained evidence
(ADR-0065, "Clearing-level evidence"). Advisory-lock namespaces 1–3 are registered and 4
is free (`DISTRIBUTED_EXECUTION.md` §3). A versioned, pinned, recomputable policy has two
precedents: the fee schedule (ADR-0050, `merchant.fee_schedule_version`, a `numeric(7,6)` rate
with a named rounding) and the routing policy (ADR-0060, version 1 seeded with its migration as
provenance, `V013`).

## Decision

1. **Matching is key-based allocation, not pairing, and it posts nothing of its own.** An
   external item **allocates** amounts to expectations. An allocation is an append-only row: the
   item, the expectation, the amount, and the decision that made it. An item may be fully
   allocated, partly allocated, checked, offset, or left with a remainder; an expectation may be
   filled by one item or by several. Allocation moves no money, because a report's transaction
   lines describe value already sitting in the counterparty's clearing position (ADR-0065). The
   only postings matching causes are the parks, unparks and offsets of unexplained value — one
   aggregated `recon-suspense:<parkId>` entry per transaction and position (ADR-0070), always the
   last statement of its transaction.

   Keys, never heuristics:
   - Internal keys live in `reconciliation.expectation_key (source_id, key_kind, key_value,
     expectation_id)`, `UNIQUE (source_id, key_kind, key_value)`, written when the expectation is
     opened (ADR-0067). A reference that arrives before or after its expectation — the ARN — goes
     to `reconciliation.reference_alias (source_id, key_kind, key_value → anchor_kind,
     anchor_value)` under the same unique. The matcher resolves an alias to its anchor (for the
     ARN, `CARD_ATTEMPT` and the attempt) and the anchor to its expectation, in a local, immutable
     two-hop join. An **operation-anchored** rule (point 2) makes the same kind of local join:
     key → the operation the keyed expectation records → that operation's expectation of the
     rule's kind, by `UNIQUE (kind, operation_ref)`. The keyed expectation is only the anchor,
     never a candidate.
   - **Every key and alias is scoped per source.** Counterparty tokens (`REMITTANCE_REF`,
     `PAYOUT_PROVIDER_REF`, PSP references) therefore never collide across counterparties, and our
     own references lose nothing by the scoping.
   - External keys are the line's typed references (`settlement.line_reference`), copied to the
     item's key index `(source_id, key_kind, key_value)`, which is deliberately not unique.
   - A key collision when an expectation opens never fails the payment. The colliding key is
     skipped, recorded as a `KEY_COLLISION` event and raised as `DUPLICATE_INTERNAL` (ADR-0067,
     ADR-0069).
   - `InternalReferenceLookup`, the read-only port over payments' and merchant's public read stores
     (ADR-0064), **types a remainder's break (point 6) and never chooses an allocation.**
     Allocation depends only on facts reconciliation owns — the expectation's copy of its
     completion's facts and the item — so a decision can be stored whole and replayed exactly.
     For a scheme line the lookup reads `payments.scheme_execution_claim`. A claim names exactly
     one subject (`PAY_IN`, `WITHDRAWAL`, `RETURN` or `UNMATCHED`) and types the break from that
     subject's state. A scheme reference no claim holds names no completed execution: after
     grace it types `MISSING_INTERNAL` when its other references name an operation still in
     flight, and `UNKNOWN_EXTERNAL` otherwise.
   - There is no fuzzy, amount-proximity, subset-sum or learned matching.
   - Rules speak the canonical vocabulary only: sources, line types, reference kinds and
     expectation kinds. They never name a provider code (`SettlementVocabularyIsConfinedTest`), a
     rail (`RailVocabularyIsConfinedTest`) or a clearing purpose
     (`clearingPositionsAreNamedOnlyByTheirDeclarations`, widened to `reconciliation`).

2. **Rule set version 1, per source.** Keys are tried in priority order, and the first rule that
   yields any candidate is the rule that fires.

   | Source | Line type | Keys (priority) | Expectation kind | Cardinality |
   |---|---|---|---|---|
   | `simulated-psp.settlement` | `CAPTURE` | `PSP_CAPTURE_REF`, then `ACQUIRER_REF` through its alias to a `CARD_ATTEMPT` anchor | `CARD_CAPTURE` | `ONE_TO_ONE` |
   | | `REFUND` | `PSP_REFUND_REF`, then `OUR_REF` (`rfd-…`) | `CARD_REFUND` | `ONE_TO_ONE` |
   | | `CHARGEBACK`, `CHARGEBACK_REVERSAL`, `DISPUTE_FEE` | `DISPUTE_REF` plus stage (`DISPUTE_CB_REF`, `DISPUTE_REV_REF`, `DISPUTE_FEE_REF`) | `CHARGEBACK`, `CHARGEBACK_REVERSAL`, `DISPUTE_FEE` | `ONE_TO_ONE` |
   | | `PROCESSING_FEE` | `ORIGINAL_REF` → the capture | — | `CHECK` against `provider_fee_schedule` |
   | | `COUNTERPARTY_ADJUSTMENT` | `ORIGINAL_REF` | the original's expectation remainder, or the original item's parked excess | `CORRECTION` |
   | `simulated-scheme.cycle-report` | `CREDIT_IN` | `SCHEME_REF`, then `END_TO_END_REF` | `PUSH_PAY_IN` or `UNMATCHED_CONFIRMATION` (both reachable → `AMBIGUOUS_MATCH`) | `ONE_TO_ONE` |
   | | `DEBIT_OUT` | `SCHEME_REF`, then `END_TO_END_REF`, then `OUR_REF` | `PUSH_WITHDRAWAL` or `PUSH_RETURN` | `ONE_TO_ONE` |
   | | `SCHEME_FEE` | — | — | `CHECK` |
   | `simulated-payout.settlement` | `PAYOUT_EXECUTED` | `PAYOUT_PROVIDER_REF`, then `OUR_REF` (`pyo-…`) | `MERCHANT_PAYOUT` | `ONE_TO_ONE` |
   | | `PAYOUT_RETURNED` | **Operation-anchored:** `PAYOUT_PROVIDER_REF`, then `OUR_REF`, each reaching the payout's operation as an anchor, never its `MERCHANT_PAYOUT` expectation as a candidate | `PAYOUT_RETURN` of that operation, by `UNIQUE (kind, operation_ref)` (opened by the return worker, ADR-0073) | `ONE_TO_ONE` |
   | `simulated-bank.statement` | `BANK_CREDIT`, `BANK_DEBIT` (attributed) | `REMITTANCE_REF`, then the value-date group | `REMITTANCE` of the attributed position | `ONE_TO_ONE`, then `GROUP_BY_VALUE_DATE` |
   | | `BANK_FEE` | — | — | `CHECK` (the fee is posted at recognition) |

   Bank-line attribution is normalisation, not matching: a bank line reaches the matcher already
   attributed to exactly one source by its declared remittance pattern, and an unattributed line
   is parked at recognition (ADR-0065). Rule set v1 seeds no `PARTIAL` rule; that cardinality
   exists for a counterparty that settles one operation in parts.

   **The `PAYOUT_RETURNED` rule is operation-anchored in rule set v1.** `P8-TSK-004` seeds v1 and
   it is frozen, so no later version is needed for returns.
   - A return quotes its payout's references, and those keys belong to the OUTBOUND
     `MERCHANT_PAYOUT` expectation. The return expectation opens no key of its own (ADR-0067 §5).
   - Under the anchored rule the INBOUND line is never key-matched against the payout, so it
     never raises a direction mismatch. The keys resolve the payout's operation, and the
     candidate is that operation's `PAYOUT_RETURN` expectation.
   - Until `P8-TSK-019`'s return worker has applied the return and opened that expectation, the
     line has no candidate. It waits `UNMATCHED` inside its grace (point 6), and the run leg
     raises no break for it. The worker resolves the line through the payout's stored provider
     reference to the payout row (ADR-0073).
   - Once the expectation exists, the rematch leg allocates the item in claimant order.
   - If the grace ends first, the grace leg parks the item and ADR-0073 §5 types its break.

   *(The row read "`PAYOUT_PROVIDER_REF`, then `OUR_REF`" unqualified until the Phase 7 → 8
   transition's consistency review, A4. Under that rule an INBOUND return line would hit the
   OUTBOUND payout, park at once as `REVERSAL_MISMATCH`, and leave the return worker nothing to
   apply.)*

   *(Since the Phase 7 → 8 transition's repair (payments `V023`), every scheme reference on the
   instant rail has at most one claim in `payments.scheme_execution_claim`, and a parking yields
   when another subject already
   explains its execution. A `CREDIT_IN` line whose `SCHEME_REF` reaches both a `PUSH_PAY_IN` and
   an `UNMATCHED_CONFIRMATION` therefore marks a defect. The `AMBIGUOUS_MATCH` rule stays as the
   guard that makes it loud.)*

3. **One pure decision function, five cardinalities.** `decide(item, candidates, ruleSet) →
   Decision` is pure: no I/O, no clock, no database. A chunk transaction supplies its inputs and
   commits its outputs.
   - **Candidates** are the expectations the item's keys reach under the rule that fires,
     restricted to the item's source, currency and direction, with a remainder above zero. Under
     an operation-anchored rule the keys reach an anchor operation, and the only candidate is
     that operation's expectation of the rule's kind. The anchor's own expectation is never
     tested on currency or direction, so it cannot produce the mismatches below. No such
     expectation yet is the waiting class (point 6).
     - A key hit that fails only on currency is `CURRENCY_MISMATCH`; one that fails only on
       direction is `REVERSAL_MISMATCH`. Neither ever allocates, and a currency is never
       converted (`INV-MON-04`; FX is Phase 9's).
     - A key hit whose remainder is zero — fully allocated, or `RESOLVED_BY_ADJUSTMENT` — makes the
       item `DUPLICATE_EXTERNAL`.
     - Keys reaching two candidates are `AMBIGUOUS_MATCH`.
   - **`ONE_TO_ONE`** allocates min(item amount, remainder). An under-payment leaves the remainder
     on the expectation with `AMOUNT_MISMATCH` (subject: the expectation). An over-payment parks
     the excess at once with `AMOUNT_MISMATCH` (subject: the item).
   - **`PARTIAL`** fills in claimant order.
   - **`GROUP_BY_VALUE_DATE`** takes as its candidate set **all** open remittances of the source,
     direction, currency and value date that no other item claims. The item matches if and only if
     it equals their total exactly. **There is no subset search.**
   - **`CORRECTION`**: a `COUNTERPARTY_ADJUSTMENT` naming its original by `ORIGINAL_REF` tops up
     the original's expectation remainder (same direction). When the original item holds a parked
     excess of the opposite direction and the same amount, it offsets that excess instead: the item
     goes `OFFSET`, the suspense item is released with cause `CORRECTION_OFFSET` by an unpark
     posting, and the original's `AMOUNT_MISMATCH` resolves `EVIDENCED` (ADR-0069). An offset with
     no correlating reference stays a four-eyes `OFFSET_SUSPENSE` (ADR-0071).
   - **`CHECK`**: a fee line allocates nothing. Its expected value is `round(rate × gross +
     fixed)` under the pinned `provider_fee_schedule` row for its line type and currency, where the
     gross is that of the capture its `ORIGINAL_REF` reaches, as snapshotted (`numeric(7,6)`, the
     rounding named on the row, `INV-MON-03`). A difference beyond the tolerance (point 7) raises
     `FEE_MISMATCH`. The item goes `CHECKED` either way.
   - **A poisoned item** — the engine throwing on it — is contained: a `match_decision` with
     outcome `ERRORED`, the remainder parked, a `PROCESSING_ERROR` break, and the chunk carries on,
     because the rows behind a poisoned one are other people's money. A run that fails N times in a
     row (the bound fixed by `P8-TSK-011`) moves to `BLOCKED` and raises a CRITICAL
     `PROCESSING_ERROR` (cause `RUN_BLOCKED`) in the same transaction.

4. **Claimant order: the earlier record always wins, on every instance.**
   - Every allocation to an expectation goes through one function, `allocate(E)`, shared by the
     run, rematch, reprocess and manual legs. It serves the items whose keys reach E in
     `(source_sequence, line_no)` order, residual items of earlier batches included.
   - `source_sequence` is assigned gaplessly when a batch is accepted, under the
     `settlement.source` row lock, behind `UNIQUE (source_id, source_sequence)`. It is arrival
     order, not business date: a late, earlier-dated file takes the next sequence and is processed
     normally (`INV-SET-03`).
   - The run leg of `ReconciliationSchedule` holds `pg_try_advisory_xact_lock(4,
     hashtext(source_id::text))` for each chunk; an instance refused the lock moves to another
     source (the `OutboxRelay` argument). The rematch and grace legs take the same lock per
     transaction.
   - A run is eligible only when every lower-sequence `BATCH` run of its source has completed. A
     `BLOCKED` run therefore holds its source, visibly and alerting, until a person requeues it
     (`POST /v1/operator/reconciliation/runs/{id}/requeue`, `RECONCILIATION_ADMINISTER`, reasoned,
     audited). There is no silent skip.
   - Each chunk re-reads the run's cursor, disposes of at most 200 items and advances the cursor
     **in the same transaction**; the next chunk may run on any instance. A crash rolls back to the
     last cursor, and the run resumes elsewhere in the same order.
   - **Namespace 4 orders allocation; it does not arbitrate it.** It is registered in
     `DISTRIBUTED_EXECUTION.md` §3, pinned by `ReconciliationMigrationTest`, and transaction-scoped:
     it is released on commit, rollback or connection death, so nothing leaks and no lease clock is
     needed. The arbiters are PostgreSQL's, and each is proven with the try-lock bypassed:

     | Contention | PostgreSQL arbiter | Loser |
     |---|---|---|
     | Ten instances on one batch or source | namespace 4 per chunk; the cursor advanced in the chunk's own transaction | Moves on |
     | Duplicate matching (retry, takeover, rematch vs run, manual vs engine) | `UNIQUE (external_item_id, expectation_id) WHERE reverses_allocation_id IS NULL`; deferred Σ triggers (the allocations equal `allocated_minor`; allocated + resolved ≤ amount on both sides); `CHECK (allocated_minor + parked_minor + offset_minor <= amount_minor)` on the item and `CHECK (allocated_minor + resolved_minor <= amount_minor)` on the expectation; conditional item and expectation transitions; append-only grants | Rolls back; a person gets `409 reconciliation.RecordAlreadyMatched` |
     | Allocation vs ageing on one expectation | the expectation row `FOR UPDATE`, plus conditionals | Either order converges: no break, or `EVIDENCED` |
     | Duplicate park, unpark or offset | the item's conditional transition; `UNIQUE suspense_item (external_item_id)`; the park row's key | No second entry |
     | The return worker against the grace leg on one `PAYOUT_RETURNED` item | the item row: the worker re-reads it under a share lock and proceeds only while it is `UNMATCHED`; the grace leg judges it on the locked row (ADR-0073 §7) | Either order converges: allocated after the return, or parked with no return applied. The race is `P8-TSK-013`'s and `P8-TSK-019`'s counted test |
     | Rule-set activation race | partial `UNIQUE (source_id) WHERE status = 'ACTIVE'`; retirement inside the activation; `CHECK` activator ≠ proposer | 409 |
     | Concurrent reprocess requests | partial `UNIQUE (source_id) WHERE kind = 'REPROCESS' AND status <> 'COMPLETED'`; the idempotency key | 409 |
     | Window expiry judged by instances with skewed clocks | judged in SQL, on the database clock, against stored dates | — |

   - There are **no per-item advisory locks**, so the lock-table exhaustion a per-item design
     invites cannot arise. The lock order, recorded as a `DISTRIBUTED_EXECUTION.md` §3 row, is:
     1. advisory namespace 4 for the source, when the transaction allocates, parks or unparks:
        the run, rematch and grace legs, and a `MANUAL_MATCH` or `REPUDIATE_BATCH` approval;
     2. the break row, then the resolution row;
     3. expectation rows, then external item rows, then suspense item rows, each sorted by id;
     4. the merchant payout row, taken by the return worker only;
     5. the attribution target's ledger account `FOR SHARE`, before any posting (the chargeback
        precedent: share, never upgraded);
     6. inside `approveOwned`, the ledger proposal row, then the ledger projection rows sorted by
        account id. A transaction posting several entries over shared rows pre-locks the union
        of the platform's rows it will touch in the projection's own order before its first
        posting (`PostingService.lockBalancesInOrder`, `DISTRIBUTED_EXECUTION.md` §3's
        multi-entry lock-order rule), and takes any runtime counterparty's ACCOUNT row —
        `FOR SHARE` where a share lock suffices — before any projection row (step 5). A
        repudiation does, and so does any approval that posts more than one entry. That is the
        rule the Phase 7 → 8 transition's dispute repair wrote into `DISTRIBUTED_EXECUTION.md` §3;
        seed order is no part of it.

     **Postings are last in every transaction.** Payments' and merchant's completions only
     insert reconciliation rows, and no matcher waits on a payments or merchant lock. The return
     worker sits outside the matching chunk and takes its payout row only after its item row. So
     no cycle exists. *(Steps 4 and 6's proposal row were missing until the Phase 7 → 8
     transition's consistency review, B9, aligned this list with the `DISTRIBUTED_EXECUTION.md`
     row that cites it.)*
   - Duplicate lines fall to the same order. A line's `canonical_fingerprint` is indexed and
     deliberately not unique, so both copies are kept. The later claimant — because the same
     fingerprint appeared earlier, or because its expectation is already filled — parks as
     `DUPLICATE_EXTERNAL`.

5. **Every decision stores what it saw.** Each evaluation writes one `match_decision`: the item,
   its origin (`RUN`, `REMATCH`, `REPROCESS`, `MANUAL`), `rule_set_id NOT NULL`, `rule_priority`,
   `strategy`, `matched_key_kind`, the outcome, `claimant_rank`, `claimant_count`,
   `date_deviation_days`, the applied `timing_tolerance_days`, the fee's expected, reported and
   tolerance values, `decided_by`, `decided_at` and `decided_on`. It writes one `match_candidate`
   per candidate considered: the expectation id, key kind, the amount, currency and direction
   snapshot, `remainder_before` and `opened_at`. Every allocation references its decision.
   - `GET /v1/operator/reconciliation/decisions/{id}` and `/allocations/{id}`
     (`RECONCILIATION_INVESTIGATE`) answer "why were these two records matched?" from these rows
     alone. For example: `rule 1 PSP_CAPTURE_REF cap-psp-9f2; candidates 1 (remainder 2007
     EUR/2, opened 2026-10-01); allocated 2007; date deviation 1 day ≤ tolerance 2; claimant 1 of
     1`.
   - The durable trace is the stored identifier chain, walked with no timestamp join: `item →
     decision (candidates) → allocation | park | break`, and from the expectation to its journal
     entry and operation (ADR-0067).
   - Every allocation, park and release that touches a break's subject bumps the break's
     `residual_version`. That is how a resolution proposed before a late allocation is refused at
     approval (`409 reconciliation.ResolutionStale`, ADR-0071).

6. **Grace or definitive: the remainder's class decides between waiting and parking.** Whatever a
   decision leaves unallocated is classified through the lookup:
   - **Definitive — parked at once, in the deciding transaction, with its break:**
     `DUPLICATE_EXTERNAL`, `CURRENCY_MISMATCH`, the `AMOUNT_MISMATCH` excess, `AMBIGUOUS_MATCH`,
     `REFUND_MISMATCH` against a terminal failed refund, and `REVERSAL_MISMATCH` against a
     terminal state (a capture on a `VOIDED` or `FAILED` attempt; a reversal on a dispute that is
     `LOST` or `ACCEPTED`). No later internal record can change these.
   - **Waiting — late internal evidence could still explain it:** `UNKNOWN_EXTERNAL` (the key is
     unknown); `MISSING_INTERNAL` (the operation is known but not completed: a capture `UNKNOWN`,
     a refund `DISPATCHED`, a dispute stage not yet applied); a `PAYOUT_RETURNED` line with no
     return yet, whose operation-anchored rule has no candidate until the return worker applies
     it (point 2, ADR-0073). For a scheme line, a reference no `payments.scheme_execution_claim`
     row holds names no completed execution: after grace it types `MISSING_INTERNAL` when its
     other references name an operation still in flight, and `UNKNOWN_EXTERNAL` otherwise. The
     item stays `UNMATCHED` until its `grace_until`, stamped from the firing rule's
     `grace_hours` and judged in SQL on the database clock. If the internal record
     lands meanwhile, the rematch leg allocates the item. Otherwise the grace leg parks it and
     raises the break.
   - When several classes apply, precedence is: the definitive specific types, then
     `MISSING_INTERNAL`, then `UNKNOWN_EXTERNAL` (ADR-0069).
   - **Timing** is judged on every match. A settlement date later than `expected_by +
     SETTLEMENT_DATE_DAYS`, or a cycle token other than the one announced, raises a zero-value
     `TIMING_DIFFERENCE`. If the expectation already has an overdue `MISSING_EXTERNAL` break, that
     break resolves `EVIDENCED` with the timing recorded instead (`INV-SET-03`). Nothing is ever
     refused as stale.
   - Unallocated value therefore rests in exactly one of two counted places: `UNMATCHED` inside
     its grace (`finapp.reconciliation.item.unmatched`), or parked with its break (ADR-0070). No
     run completes with an item `PENDING`.

7. **The tolerance model: no tolerance on value already in a position.** A tolerance is a
   versioned row of the rule set: `reconciliation.tolerance (rule_set_id, comparison, currency
   NULL, scale NULL)`, with `comparison ∈ {PROCESSING_FEE_PER_LINE, PROCESSING_FEE_PER_BATCH,
   SETTLEMENT_DATE_DAYS}` and `absolute_minor ≥ 0` or `days ≥ 0`. **There is no amount member.** A
   tolerance on a principal amount is not refused by a check someone must remember; it cannot be
   represented (`INV-REC-08` at database rank), and the domain refuses a request for one with `422
   reconciliation.ToleranceNotPermitted`.
   - **Why fees and dates are different.** No processing fee was posted before Phase 8. A report's
     fee is recognised **as reported** at acceptance (DR `PROCESSING_COSTS`, ADR-0065), so the fee
     tolerance decides only whether a commercial `FEE_MISMATCH` is raised. That break's value is
     the difference, it carries no residual, and it moves nothing. The date tolerance decides only
     whether a zero-value `TIMING_DIFFERENCE` is raised.
   - `PROCESSING_FEE_PER_LINE` bounds a fee reported against one transaction line.
     `PROCESSING_FEE_PER_BATCH` bounds a fee reported once for the batch, compared with the
     `Money` fold of the expected fees of the lines it covers. Exactly at the tolerance raises
     nothing; one minor unit beyond it raises the break.
   - Every **principal** difference — one minor unit included, in either direction — becomes a
     remainder on the expectation or a parked excess, each with its break. A dispute-fee line is
     principal, not a processing fee: it allocates to its `DISPUTE_FEE` expectation, and a
     difference is an `AMOUNT_MISMATCH`.
   - The applied tolerance is recorded on the decision that used it (`timing_tolerance_days`; the
     fee's expected, reported and tolerance values), so the gate's "and tolerance" is a column, not
     a claim.

8. **The rule set is versioned data, activated under four-eyes, and pinned on everything it
   decides.**
   - **Content.** `reconciliation.rule_set` per source and version (lag days per expectation kind
     in `rule_set_lag`, `funding_lag_days`, `gain_min_age_days`, `effective_from`, proposer,
     decider, reason); `rule`, PK `(rule_set_id, priority)`, carrying `line_type`,
     `expectation_kind`, `key_kind`, `cardinality`, `grace_hours` and whether the key reaches its
     candidate directly or anchors an operation (point 2: v1's one operation-anchored rule is
     `PAYOUT_RETURNED`); `tolerance`;
     `provider_fee_schedule` (rate, fixed amount, scale and `rounding_policy` per line type and
     currency); `severity_threshold` (`high_value_minor` per currency, read by ADR-0069's severity).
   - **Version 1** is seeded `ACTIVE` for each of the four sources, with the migration as its
     provenance (the `V013` routing precedent). Seeded values: lag days 3 for card captures, 3 for
     refunds and disputes, 1 for instant, 2 for payouts; `funding_lag_days` 2;
     `SETTLEMENT_DATE_DAYS` 2; `gain_min_age_days` 90; `high_value_minor` 1,000.00 per currency.
   - **Machine.** `PROPOSED → ACTIVE` only by a different person holding
     `RECONCILIATION_ADMINISTER` (`CHECK (decided_by <> proposed_by)` when `ACTIVE`; `409
     reconciliation.RuleSetActivationBySameActor`). The prior version moves `ACTIVE → RETIRED`
     **in the activating transaction**, so every source always has exactly one active version.
     `PROPOSED → REJECTED` is also allowed. Content is frozen from `PROPOSED` by trigger. `RETIRED`
     and `REJECTED` are terminal. `UNIQUE (source_id, version)`.
   - **Doors.** `GET` and `POST /v1/operator/reconciliation/rule-sets`, `POST
     .../rule-sets/{id}/approval` and `.../rule-sets/{id}/rejection`, keyed per principal, audited
     `reconciliation.RuleSetProposed`, `reconciliation.RuleSetActivated` and
     `reconciliation.RuleSetRejected`, each with a reason. Acting on a version that is no longer
     proposed is `409 reconciliation.RuleSetNotPending`.
   - **Who.** `RECONCILIATION_ADMINISTER` is held by `RECONCILIATION_CONTROLLER` alone, a role
     disjoint from `RECONCILIATION_OPERATOR`, which resolves breaks. **Whoever can loosen a
     tolerance cannot resolve the breaks it would hide** (ADR-0071).
   - **Pinned.** An expectation pins the version that dated its `expected_by`. A run pins the
     version active when its batch was accepted, and its decisions carry it. A rematch or
     reprocess decision pins the version active when it ran. Allocations and breaks carry their
     decision's version.
   - **Forward only.** A new version governs new runs, rematches and explicit `REPROCESS` runs.
     **It never alters a committed allocation, park or break**, never re-dates an open
     expectation's `expected_by`, and never re-stamps a running grace. A test pins this: activate
     v2, then every v1 decision still replays `IDENTICAL` and no position has changed. The
     committed matches of a defective version stand as history. Money they mis-explained is
     corrected by a resolution or, for a whole batch, by repudiation (ADR-0071). Re-allocating
     committed matches outside repudiation is out of scope.

9. **Replay has three meanings, and none of them edits anything.**
   1. **Decision replay.** `POST /v1/operator/reconciliation/runs/{id}/replay`
      (`RECONCILIATION_INVESTIGATE`, audited `reconciliation.RunReplayed`) re-runs `decide` over
      the stored candidate snapshot of every decision on the run's items, under each decision's
      pinned rule set, and compares outcome and allocations. It appends one `run_replay` row —
      verdict `IDENTICAL` or `DIVERGED`, the divergence count, the first divergent decision, and the
      items whose rematch is merely pending, reported as `PENDING_REMATCH` rather than as
      divergence — and writes nothing else. `DIVERGED` raises a CRITICAL `PROCESSING_ERROR` break.
      A `MANUAL` decision replays as its recorded choice applied to its snapshot: replay proves the
      chosen expectation was a candidate and the allocation is the one its cardinality gives.
   2. **Reprocess.** `POST /v1/operator/reconciliation/sources/{code}/reprocessing {reason}`
      (`RECONCILIATION_ADMINISTER`, keyed, one open per source, `409
      reconciliation.ReprocessingInProgress`, audited `reconciliation.ReprocessingRequested`)
      opens a `REPROCESS` run over **residual items only** (`UNMATCHED` or `PARKED`) and
      re-resolves their candidates now, under the active version. Its decisions are new rows, so
      drift in candidate resolution appears as new decisions, never as edits. A parked item it
      allocates is unparked.
   3. **Order-independence.** A property test over the pure layer: shuffled processing orders
      yield identical allocations, because every allocation goes through `allocate(E)` in claimant
      order.

   Replay of *ingestion* is ADR-0066's: a re-delivery is a no-op, a file our own validation wrongly
   rejected is readmitted (so is one rejected `CONFLICTING_BATCH` beside a batch since
   repudiated), and a stored file's fingerprints are re-verified under its recorded
   format version without replacing a line. Reversing accepted evidence is repudiation's, the only
   path that adds counter-allocations (`reverses_allocation_id`, append-only — `INV-REV-01`'s shape
   applied to matches; ADR-0071).

10. **`INV-REC-04`, stated honestly.** "The same inputs, the same matches" holds for stored inputs,
    not for the world. A decision is a pure function of its stored candidate snapshot and its
    pinned rule set, so replay is exact. Which candidates a decision saw depends on what had been
    recorded when it ran — an expectation's `opened_at`, first-writer-wins keys, grace expiry on
    the database clock — and that is exactly why the snapshot is stored. The statement is amended
    to "the same stored inputs always produce the same matches". Enforce gains claimant order under
    namespace 4 and decision snapshots. Verify gains the shuffled-order property test and snapshot
    replay. `INV-HIST-04`'s Verify names decision replay.

11. **Events carry dispositions, never amounts, and no correctness rests on them.** The planned
    per-record `SettlementMatched` event is replaced by `reconciliation.ReconciliationRunCompleted`
    (the run's counts per outcome) and `reconciliation.SettlementExpectationSettled`. The decision
    rows are the record. Phase 8 has no Kafka consumer, and its events are notifications for
    future consumers (`INV-EVT-04`).

12. **Owner decisions this ADR carries.** The owner's open decisions were settled at the
    transition on the design's recommendations, each recorded as one the owner may revisit. Three
    of them live in this ADR's rule set or its doors:
    - **O1, roles:** two pairwise-disjoint roles, `RECONCILIATION_OPERATOR` and
      `RECONCILIATION_CONTROLLER` (point 8; ADR-0071). *(Settled 2026-09-28 at the Phase 7 → 8
      transition, on the recommendation; revisitable by the owner.)*
    - **O5, the gain minimum age:** `gain_min_age_days` seeded at 90 in rule set v1, the gain
      itself four-eyes (ADR-0070). *(Settled 2026-09-28 at the Phase 7 → 8 transition, on the
      recommendation; revisitable by the owner.)*
    - **O7, the high-value severity threshold:** `high_value_minor` seeded at 1,000.00 for EUR,
      GBP and USD in rule set v1 (ADR-0069). *(Settled 2026-09-28 at the Phase 7 → 8 transition,
      on the recommendation; revisitable by the owner.)*

    O5 and O7 are rule-set content, so revisiting either is a new version under four-eyes, not a
    migration.

## Alternatives Considered

### Recompute candidates at replay instead of storing them
Pros: no candidate rows; the replay also exercises today's key resolution.
Cons: the claim is time-dependent. A key's first writer, an ambiguity created by a later
expectation, a grace that expired: each makes an honest past decision look divergent, and a real
divergence hides among them. Reprocess (point 9) keeps what this option is good at, as new
decisions rather than as a verdict on old ones.

### Snapshots without a claimant order
Pros: replay is exact, with no ordering machinery.
Cons: the live outcome between racing instances stays arbitrary. Which of two identical lines is
`MATCHED` and which is `DUPLICATE_EXTERNAL` would depend on scheduling, the order-independence
property could not hold, and the storm's "ten instances give the single-worker result" could not
be asserted.

### Order claimants by a clock (receipt time or business date) instead of a gapless sequence
Pros: no source row lock at acceptance.
Cons: instance clocks skew (the Docker VM was measured gaining 55–77 ms a second, with ~1.6 s
step-backs), ties need a tie-breaker, and business dates arrive out of order. A gapless sequence
minted under the source row lock is the database's order, and it is the same on every instance.

### A single elected matcher
Pros: trivially ordered.
Cons: correctness around one process is what the multi-instance rule forbids (ADR-0014,
ADR-0024), and a leader lease brings a clock into correctness. The per-source try-lock gives the
same order with no leader, and it releases on connection death.

### Per-item advisory locks arbitrating allocation (B's ledger open items)
Pros: fine-grained, with pairing for every writer.
Cons: an `AFTER INSERT` trigger on every clearing journal line and a lock per item. It changes the
platform's most-probed posting path and invites lock-table exhaustion under volume. Here the lock
only orders; uniques and Σ triggers arbitrate, and the lock-bypass probes prove they suffice.

### Match directly on `settlement.line` (a `reconciliation → settlement` edge)
Pros: one copy of each line.
Cons: the item's disposition is contended, mutable state. It would live in another module's rows
or need a hot-path cross-module read. The working copy keeps `settlement.line` immutable evidence
(ADR-0064).

### One treatment for every remainder: park at once (C), or wait out grace (B)
Pros: one rule. Parking at once means nothing unexplained rests outside suspense, even briefly.
Grace for every class means fewer breaks.
Cons: parking at once turns every report-before-webhook ordering into a break and a park-unpark
pair — noise that trains operators to approve without reading. Grace for every class only delays
the owner of a duplicate, a wrong currency or an excess, which no later internal record can
explain.

### Amount tolerances
Pros: common practice; they absorb small rounding noise between systems.
Cons: an absorbing tolerance is an unrecorded write-off (`INV-BAL-03`). The noise it suppresses
does not exist here: money is integer minor units (ADR-0003), a fee net derived by subtraction
leaves no rounding residual (`INV-MER-04`), and conversion is Phase 9's, with its own rounding
policy. A difference worth ignoring is ignored by a person, on the record: `ACKNOWLEDGE` or
`WRITE_OFF`, four-eyes whenever value is at issue (ADR-0071).

### Fuzzy, subset-sum or learned matching
Pros: it finds explanations for lines without usable references, and for bank credits that
aggregate an unknown subset.
Cons: it cannot be explained from stored data in the sense the gate means, its cost is
combinatorial, and it is wrong precisely in the ambiguous cases. Every simulated counterparty
carries references, and the bank's aggregation is covered by `REMITTANCE_REF` and the exact
value-date group. Recorded as deferred.

### Rule changes that re-match committed allocations
Pros: a defective rule's past is corrected automatically.
Cons: history is edited, and money moves between explanations without a second person.
Forward-only rules, with correction by resolution or repudiation, keep every correction a new,
four-eyes record.

### Rules as compiled code only
Pros: reviewed with the code; no activation surface to secure.
Cons: a decision cannot pin a version that is not data (`INV-HIST-04`'s `NOT NULL` reference), and
a change to what counts as a match would be a deploy rather than a four-eyes act by the
controller role. Compiled code keeps the pure `decide` and the five cardinalities; the rule set
parameterises them.

## Consequences

Positive:
- The gate's "every match records the rule version and tolerance" is a schema property
  (`rule_set_id NOT NULL`, the applied-tolerance columns), checked by a schema scan.
- "Resumes without duplicate or lost matches" is the cursor advanced in the chunk's own
  transaction plus the allocation uniques. Ten instances produce the single-worker result, and
  the uniques and Σ triggers alone hold with the try-lock bypassed.
- Replay is exact and can be run on demand. A perturbation (a strategy constant changed) is
  caught as `DIVERGED` with a CRITICAL break, a `MUTATION_TESTING.md` row.
- No tolerance can hide a loss: one minor unit is a break, in both directions.
- What counts as a match changes only by a four-eyes, audited, forward-only act, made by a role
  that cannot resolve the breaks it affects.

Negative:
- Per-source serialisation caps throughput at one allocating transaction per source. It is
  accepted for determinism; the recorded scale path is to lock by (source, currency) under the
  same arbiters.
- A `BLOCKED` run holds its source's later runs until requeued: correctness over liveness,
  visible and alerting.
- Every evaluation writes a decision and its candidate rows, rematches included. The tables grow
  with traffic, are append-only, and are not pruned in Phase 8 (retention deletion is out of
  scope).
- A line with no usable reference and no exact group total becomes a break for a person; no
  heuristic rescues it.
- The waiting classes become breaks only after `grace_hours`; they are counted meanwhile.
- The committed matches of a defective rule version are corrected by resolution or repudiation:
  operator work, not an automatic re-run.
- Lag days and grace windows are calendar time; business-day calendars are deferred.

Operational impact: `finapp.reconciliation.item` by `source` and `outcome` (matched, unmatched,
parked, checked, offset, errored), `finapp.reconciliation.item.unmatched`,
`finapp.reconciliation.rematch`, `finapp.reconciliation.replay` by `outcome` (identical,
diverged), `finapp.reconciliation.run.pending`, `finapp.reconciliation.run.age`,
`finapp.reconciliation.run.blocked` (alert, must be 0) and `finapp.reconciliation.run.latency` —
counts, ages and verdicts only (ADR-0072), under the new `source` tag. Spans
`reconciliation.chunk` and `reconciliation.rematch`, with the ingesting request's correlation
restored per chunk. The explanation, replay, reprocess and requeue doors. `ReconciliationSchedule`
(run, rematch, grace) joins `NoSingleInstanceAssumptionRulesTest.LEASE_PROTECTED_SCHEDULERS` and
the scheduler register with its argument.
Security impact: decisions, candidates and allocations are read under
`RECONCILIATION_INVESTIGATE`; references are `CONFIDENTIAL` and amounts `RESTRICTED-FINANCIAL`,
never in logs, traces, metrics or events. Rule-set activation is a four-eyes policy activation
(`INV-AUD-04`) under `RECONCILIATION_ADMINISTER`, held by a role disjoint from the one that
resolves. Reprocess and requeue are reasoned and audited.
Financial impact: allocation posts nothing. Unexplained value leaves a clearing position only by
a park with its break (ADR-0070), so the position proof (`INV-REC-06`) holds across every
matching step. A reported processing fee is expensed as reported and checked against a pinned
schedule. No value is absorbed by a tolerance.

## Invariants / Constraints

`INV-REC-04` (amended: "the same stored inputs always produce the same matches"; claimant order
and decision snapshots), `INV-HIST-04` (its matching element; Verify names decision replay),
`INV-REC-02`, `INV-REC-06`, `INV-SET-03`, `INV-MON-03`, `INV-MON-04`, `INV-BAL-03`, `INV-AUD-04`
(rule-set activation), `INV-CON-01`, `INV-PAY-03` (provider vocabulary confined to the format
adapters). Catalogued with this ADR:

- **`INV-REC-07` — A match allocates no more than either side holds, records what it saw, and is
  never undone.** An allocation joins one item and one expectation of the same source, currency
  and direction. The allocations on each, net of any counter-allocation, never exceed its amount
  less what was resolved, parked or offset. Every allocation references a decision that stores
  its pinned rule set and a snapshot of every candidate considered. Allocations and decisions are
  append-only; only a batch repudiation adds counter-allocations. Enforce: `DB-CONSTRAINT`
  (CHECKs, deferred Σ triggers, uniques, no `UPDATE`/`DELETE` grant) + `DOMAIN`. *(It read "the
  positive allocations on each" until the Phase 7 → 8 transition's consistency review, B7,
  aligned it with the catalogue. After a repudiation and the genuine batch, an expectation holds
  its first allocation, the counter-allocation that reverses it and a new one. The positive sum
  then exceeds the amount while the net, which the Σ triggers hold, does not. "Positive" stays
  true in Verify: at most one positive allocation per (item, expectation).)*
- **`INV-REC-08` — A tolerance never absorbs value.** Tolerances exist only for comparisons
  against unposted expectations (processing fees, dates). No tolerance can exist on an amount
  already in a ledger position, and every principal difference becomes a remainder or a parked
  item with its break. Enforce: `DB-CONSTRAINT` (the tolerance type has no amount member) +
  `DOMAIN`.

## Follow-up

- `P8-TSK-004` seeds rule set v1 and its tables (reconciliation `V002`: `rule_set`, `rule`,
  `tolerance`, `provider_fee_schedule`, `severity_threshold`, `expectation_key`,
  `reference_alias`). Every rule of point 2's table is seeded there, the payout source's
  operation-anchored `PAYOUT_RETURNED` rule among them, because a seeded rule set is frozen.
  **Implemented** (2026-09-29): v1 `ACTIVE` per source with the migration as its provenance;
  the values fixed at that task's design — grace 48 hours per rule with the payout return at
  72, `SETTLEMENT_DATE_DAYS` 2, `PROCESSING_FEE_PER_LINE` 2 minor and `_PER_BATCH` 50 minor
  (the PSP source), the PSP's terms 1.5% + 0.25 (`numeric(7,6)`, `HALF_UP`), the scheme's
  fixed 0.10 and the bank's fixed 0.50, `gain_min_age_days` 90 (O5) and `high_value_minor`
  1,000.00 per currency (O7). Point 7's "no amount member" holds at the database rank
  (`tolerance_shape` and the closed comparison list), the content is frozen by trigger for
  every writer, and one `ACTIVE` per source is a partial unique. The matcher that reads the
  rules is `P8-TSK-011`'s; the proposal/activation machine is `P8-TSK-022`'s.
  `P8-TSK-009` assigns the gapless `source_sequence` at acceptance and creates
  the run and its items (reconciliation `V003`). `P8-TSK-010` supplies `InternalReferenceLookup`,
  the breaks and the parks the matcher raises.
- `P8-TSK-011` builds the matcher: reconciliation `V005` (`match_decision`, `match_candidate`,
  `allocation` with its Σ triggers), the run leg under namespace 4, `allocate(E)`, `ONE_TO_ONE`,
  aliases, the definitive classes, poisoned items, `BLOCKED` runs and the explanation doors.
  **Implemented** (2026-09-30), with the deviations recorded. The engine is one pure
  `MatchEngine.decide` (no I/O, clock or database; the shuffled-order property and a
  stored-snapshot re-decide prove `INV-REC-04` as amended), and the chunk maintains the
  DIMINISHING remainder in claimant order in memory, so one chunk can never allocate one
  expectation twice over before the deferred Σ triggers judge the commit
  (`INV-REC-07` at both ranks; `claimant_rank` counts the chunk's claimants per
  expectation). `AMBIGUOUS_MATCH` is stated and proven hermetically but UNPRODUCED:
  `V002`'s `expectation_key_once` means one key reaches at most one expectation per
  source, so no produced path yields two candidates yet — the edge waits for a
  producer exactly as the run machine's unproduced edges did. §6's ≤ bounds are
  the standing `V002`/`V003` table `CHECK`s (stronger: judged per statement); `V005` adds
  the equality Σ on BOTH sides — the allocation insert and either denormalised
  column — so a writer can neither allocate without recording nor record without
  allocating. The rule table's `key_kind` on a `CHECK` or `CORRECTION` row is ITEM-side
  vocabulary (`ORIGINAL_REF`), so the store surfaces the expectation-side `KeyKind` only
  for landed rules; the unlanded rows' keys are `P8-TSK-012`'s to read in their own
  shape. `allocate(E)`'s shared path is the store pair `insertAllocation →
  allocateToExpectation` under the pair unique and the Σ discipline — the run
  leg is its first caller; the rematch, reprocess and manual legs compose the same pair.
  An allocation bumps every OPEN break's `residual_version` on the subjects it touches
  (ADR-0071's staleness counter; a break the chunk itself raises is born after the bump
  and carries 0 honestly). The events carry `sourceId`, not the drafted `sourceCode`
  (the `P8-TSK-008` `EventPayload` stance); the completion's audit action is
  `reconciliation.RunCompleted`; the leg carries no bespoke span (the `P8-TSK-008`
  precedent — correlation is the trace); the run gauges are per declared source
  (tagged `source`, the codes mapped in the app over the settlement source rows); and
  the namespace-4 pin lives at the code rank (`AdvisoryNamespaceIsPinnedTest`) because
  no migration statement carries the number — the run leg's TRY form and the park
  path's blocking form are proven to share it. Ten sweepers converge WITH the lock and
  with it BYPASSED (`MatchingDatabaseTest`); a bypassed loser may record its losing
  evaluation — an honest `ERRORED` decision — while money moves once.
  `P8-TSK-012` adds `CHECK` and `CORRECTION`, and `P8-TSK-013` the grace and rematch legs on the
  database clock. The grace leg judges a `PAYOUT_RETURNED` item on its locked row, and its race
  against the return worker is counted both ways with `P8-TSK-019`. `P8-TSK-015` adds
  `MANUAL_MATCH`; `P8-TSK-016` adds `REMITTANCE_REF` and `GROUP_BY_VALUE_DATE`; `P8-TSK-017` and
  `P8-TSK-018` bring the instant and payout sources' reports under their v1 rules. The instant
  rules' break typing reads `payments.scheme_execution_claim`.
- `P8-TSK-019` builds the return worker that the operation-anchored `PAYOUT_RETURNED` rule waits
  for, and the `PAYOUT_RETURN` expectation it reaches (ADR-0067 §5, ADR-0073).
- `P8-TSK-022` builds rule-set administration under four-eyes, `REPROCESS` runs, requeue,
  `run_replay` (reconciliation `V008`) and the replay-perturbation probe. `P8-TSK-023`'s
  repudiation, the only path that adds counter-allocations, follows in reconciliation `V009`.
- `P8-TST-001` (the storm: replay `IDENTICAL` every round, at most one positive allocation per item
  and expectation, ten matcher instances) and `P8-TST-002` (the break and resolution battery).
- Deferred and recorded as not implemented in Phase 8: fuzzy or subset-sum matching, business-day
  calendars, multi-part or superseding files, re-allocating committed matches outside
  repudiation, and partitioning by (source, currency).
- Until the remaining tasks land, the statements they own are decided design, corrected by the
  tasks that build them; the seeded rule content (`P8-TSK-004`), the intake hand-off (`-009`),
  the records the matcher raises (`-010`) and the matcher itself (`-011`) are implemented, each
  with its note above.
- The Phase 8 review (`P8-DOC-001`) reads this ADR against the code before accepting it.
