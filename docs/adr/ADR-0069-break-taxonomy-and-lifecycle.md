# ADR-0069 — Break taxonomy and lifecycle

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Reconciliation · Settlement · Ledger
Supersedes: nothing. Gives the Reconciliation Break (`GLOSSARY.md`), the discrepancies
`RECONCILIATION_MODEL.md` lists and `MODULE_ARCHITECTURE.md`'s hard rule ("no code path deletes a
break") one closed taxonomy and one machine. Amends `INV-REC-02`: an `EVIDENCED` resolution is
recorded, not silent. Detection is ADR-0068's matching; the suspense a break owns is ADR-0070's;
who may resolve a break, with how many people and by which postings, is ADR-0071's.

## Context

Reconciliation compares three records per counterparty: our ledger position decomposed into
settlement expectations (ADR-0067), the counterparty's report, and the bank statement (ADR-0065).
Most of the time they agree. When they do not, `INV-REC-02` says what must happen: "every
unmatched or mismatched record becomes a classified break record. No record is silently dropped,
auto-cleared or suppressed." A difference that disappears is a loss nobody noticed.

The repository names the break without defining it:
- `RECONCILIATION_MODEL.md` lists what an engine must detect — missing internal and missing
  external records, amount, currency, fee and timing differences, reversals, duplicates — with no
  types, subjects or severities. The Phase 8 gate's first bullet asks that "every break type in
  `RECONCILIATION_MODEL.md` is detectable and covered by a test", which needs a finite list.
- `MODULE_ARCHITECTURE.md` gives `reconciliation` the break and the rule that no code path deletes
  one. The gate's third bullet adds "no code path deletes or overwrites a break; resolution is
  always a new record plus a compensating posting".
- Phase 7 already parks value with no break at all. An instant confirmation the platform cannot
  credit posts DR `INSTANT_CLEARING` / CR `SUSPENSE_UNMATCHED`, and
  `finapp.payments.unmatched.active` counts every row ever parked
  (`JdbcUnmatchedConfirmationStore`). Since the transition's repairs (payments `V023`) each parking
  records why it parked — the statement named nothing the platform made (`UNATTRIBUTED`), named an
  attempt already concluded (`ATTEMPT_CONCLUDED`), or executed an amount other than the attempt's
  ask (`AMOUNT_MISMATCH`) — and, exactly when attributed, the attempt it named. The value still
  sits in suspense with no owner and no lifecycle.

Phase 8 raises breaks from many places: the matcher's chunks, the grace and rematch legs, the
ageing sweep, bank-statement recognition, the unmatched confirmation's port, a failing run, a
diverged replay and the payout-return path, each on N instances. Without a closed taxonomy and a
uniqueness rule, the same discrepancy is raised N times, the resolution kinds cannot be bound to
what they fix, and severity — what an operator reads first — becomes opinion.

Three existing texts pull against each other, and this ADR settles them:
1. **"Never auto-cleared" against evidence that genuinely explains a break.** A late file, a
   counterparty's correction line, or a capture the sweeper completes after the PSP settled it
   leaves nothing at issue. Leaving such breaks open floods the desk with work that needs no
   judgement. Closing them quietly is exactly what `INV-REC-02` forbids.
2. **"Resolved by a compensating posting" (`GLOSSARY.md`, `INV-REC-03`, the gate) against breaks
   whose value is in no position:** a processing fee already expensed at recognition, a timing
   difference of zero, a reference collision between two of our own records.
3. **A recurrence against terminal-is-terminal** (`INV-LIFE-04`'s discipline). The same subject
   can go wrong again after its break was resolved.

## Decision

1. **A break is a reconciliation record: a subject, a type, a cause and a value at issue, fixed
   when it is raised.** It lives in `reconciliation.break` with `break_event`, `break_note` and
   `break_evidence_link` (reconciliation `V004`, `P8-TSK-010`).
   - **Subject.** At least one of `expectation_id`, `external_item_id`, `suspense_item_id`,
     `run_id` and `decision_id` is set, and every one is a reconciliation row. There are no
     cross-schema foreign keys (ADR-0006). The subject reaches settlement's line and file, and the
     payments or merchant record, through its own stored identifiers. A statement-level break —
     the "batch" subject of `SETTLEMENT_MISMATCH`'s statement causes — is carried by that batch's
     run, because acceptance creates exactly one run per batch (`UNIQUE (batch_id)` on
     `reconciliation_batch`).
   - **Frozen at raise:** the subject; `cause`; `source_id`; `value_at_issue_*` (the ADR-0003
     triple, ≥ 0, in the subject's own currency and never converted, `INV-MON-04`); `rule_set_id
     NOT NULL` (the source's active rule set, whose thresholds grade the break for life,
     `INV-HIST-04`); `raised_at` from the injected clock; and the internal classification
     (`internal_classification`, `internal_operation_ref`, `internal_state`) as
     `InternalReferenceLookup` answered at raise. The lookup reads payments' and merchant's live
     state, which moves on, so the break keeps what it saw. The lookup types breaks and never
     allocates (ADR-0068).
   - **Moving, each by an edge trigger for every writer:** `status`, `type` (reclassification,
     point 7), `severity` (upward only, point 5), `assignee`, `residual_version` (upward only) and
     `resolved_at` (once, with `RESOLVED`).
   - **The value at issue is not the residual.** The value at issue is a fact of the raise and is
     never revised. The residual is what the subject still holds on the break's account, read
     from the subject: an expectation's remainder, an item's unallocated and parked remainder, a
     suspense item's unreleased amount. `residual_version` moves on every allocation, park,
     release or reclassification touching the subject. ADR-0071 freezes it in a proposal and
     refuses an approval that finds it moved (`409 reconciliation.ResolutionStale`).
   - A break moves no money. Parking (ADR-0070) and resolution postings (ADR-0071) do, and
     every suspense item names the one break that owns it (`suspense_item.break_id NOT NULL`,
     `INV-REC-09`).

2. **Fourteen types, closed.** A generated `CHECK` and the `type` metric tag bound them. A
   fifteenth type is an amendment of this ADR and a migration.

   | Type | Detected by | Subject | Value at issue | Parked? | Base severity | Allowed resolutions |
   |---|---|---|---|---|---|---|
   | `MISSING_EXTERNAL` (internal missing externally) | Ageing sweep: an expectation with `allocated = 0` past `expected_by + SETTLEMENT_DATE_DAYS` (database clock) | expectation | remainder | No (it stays in the position) | MEDIUM; HIGH for `MERCHANT_PAYOUT` and `REMITTANCE` | `EVIDENCED` (late), `WRITE_OFF` (INBOUND), `TRANSFER_TO_ACCOUNT` (OUTBOUND) |
   | `MISSING_INTERNAL` (external known, internal not completed) | Grace leg, via the lookup | item | unallocated remainder | Yes | HIGH | `EVIDENCED` (the operation completes; rematch unparks), `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT), `OFFSET_SUSPENSE`, `RECOGNISE_GAIN` (CREDIT, after the minimum age) |
   | `UNKNOWN_EXTERNAL` (external transaction unknown) | Grace leg; an unattributed bank line at recognition; `PARKED_ON_RECEIPT` for unmatched confirmations | item or suspense item | amount | Yes | HIGH; CRITICAL when OUTBOUND | as above |
   | `AMOUNT_MISMATCH` | `ONE_TO_ONE` with a different amount | expectation (under) or item (over) | difference | the over-part only | HIGH | `EVIDENCED` (a correction fills or offsets it), `WRITE_OFF` (an INBOUND remainder; a DEBIT item), `TRANSFER_TO_ACCOUNT`, `RECOGNISE_GAIN` (CREDIT, after the minimum age) |
   | `CURRENCY_MISMATCH` | Key hit, currency differs; never converted (`INV-MON-04`; FX is Phase 9) | item | item amount (its own currency) | Yes | HIGH | `EVIDENCED` (a counterparty correction offsets it), `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT), `OFFSET_SUSPENSE` — never `RECOGNISE_GAIN` |
   | `FEE_MISMATCH` | Fee check beyond tolerance (a dispute-fee line ≠ its expectation is an `AMOUNT_MISMATCH` instead) | item | \|difference\| (commercial) | No (already expensed) | MEDIUM | `ACKNOWLEDGE` (four-eyes) |
   | `DUPLICATE_EXTERNAL` | Expectation already fully allocated, or a repeated fingerprint | item | amount | Yes | HIGH | `EVIDENCED` (a claw-back correction offsets it), `OFFSET_SUSPENSE`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (a recovery after a write-off, CREDIT, after the minimum age) |
   | `DUPLICATE_INTERNAL` | A key collision recorded at opening; or an investigator's reclassification | expectation | the colliding expectation's amount | No | HIGH | `ACKNOWLEDGE`, `WRITE_OFF` |
   | `AMBIGUOUS_MATCH` | Two or more candidates | item | amount | Yes | MEDIUM | `MANUAL_MATCH`, `TRANSFER_TO_ACCOUNT`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (CREDIT, after the minimum age) |
   | `TIMING_DIFFERENCE` | Late match beyond tolerance; cycle mismatch | decision | 0 | No | LOW | `ACKNOWLEDGE` (one person) |
   | `REVERSAL_MISMATCH` | Direction contradicts the record; a capture on a voided or failed attempt; a reversal without `WON`; a `PAYOUT_RETURNED` line that cannot be applied (payable not postable, amount ≠ payout) | item | amount | Yes | HIGH | `EVIDENCED` (a counterparty correction offsets it), `TRANSFER_TO_ACCOUNT` (for example, re-credit the payable), `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT) — never `RECOGNISE_GAIN` |
   | `REFUND_MISMATCH` | A `REFUND` line against a refund that failed internally, or against a capture with no such refund | item | amount | Yes | CRITICAL | `EVIDENCED` (a late completion), `WRITE_OFF` (DEBIT), `TRANSFER_TO_ACCOUNT` — never `RECOGNISE_GAIN` |
   | `SETTLEMENT_MISMATCH` | Causes `REMITTANCE_DIFFERS` (bank ≠ remittance, surfacing as a remittance remainder or an item excess), `STATEMENT_GAP` (a sequence gap, or opening ≠ previous closing), `OPENING_BALANCE` (the first statement opens ≠ 0) | expectation, item, or the statement's run | difference | per side | HIGH; CRITICAL for statement causes | `EVIDENCED` (the gap fills, or funds arrive), `WRITE_OFF` (an INBOUND remainder; a DEBIT excess), `TRANSFER_TO_ACCOUNT`, `RECOGNISE_GAIN` (a CREDIT excess, after the minimum age) — the last three for `REMITTANCE_DIFFERS` only; the statement causes close only `EVIDENCED` (point 9) |
   | `PROCESSING_ERROR` | An errored item; a blocked run; a diverged replay | item, run or decision | amount or 0 | items: yes | CRITICAL | reprocess or requeue, then `EVIDENCED`; for a parked item, `TRANSFER_TO_ACCOUNT`, `OFFSET_SUSPENSE`, `WRITE_OFF` (DEBIT), `RECOGNISE_GAIN` (CREDIT, after the minimum age) |

   *(`DUPLICATE_EXTERNAL`'s `RECOGNISE_GAIN` goes beyond the break table the transition drafted,
   derived from its own late-settlement rule: a line arriving after its expectation was written
   off parks as a recovery and is closed by "`RECOGNISE_GAIN` or `TRANSFER`", because an offset
   against the loss is not possible. `SETTLEMENT_MISMATCH`'s restriction is point 9's. The
   transition's consistency review (A1–A3) then made this table the one authority: `WRITE_OFF` on
   a DEBIT item and `RECOGNISE_GAIN` on a CREDIT item joined every suspense-owning row except the
   three exclusions below, and `EVIDENCED` joined `CURRENCY_MISMATCH` and `REVERSAL_MISMATCH`,
   whose parked line a counterparty's correlated correction offsets like any other (point 8).)*

   - **This table is the one authority on which kinds a type admits.** ADR-0070 §4 and ADR-0071 §2
     point at it, and `PHASE_8_PLAN.md` §12.6, `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §6 and
     `RECONCILIATION_MODEL.md` §8 carry it identically. Every type that owns a suspense item
     (ADR-0070 §2) admits, besides its row's specific kinds, the two exits that need no
     counterparty: `WRITE_OFF` for a DEBIT item, at any age, and `RECOGNISE_GAIN` for a CREDIT
     item, only after the pinned minimum age (ADR-0070 §4), both four-eyes (ADR-0071). Three types
     admit no `RECOGNISE_GAIN`, and their rows say so:
     - `REVERSAL_MISMATCH` and `REFUND_MISMATCH`, because the value belongs to a counterparty — a
       merchant or a customer — and leaves by `TRANSFER_TO_ACCOUNT` or `EVIDENCED`, never as the
       platform's gain;
     - `CURRENCY_MISMATCH`, because a currency break is never income: it leaves by `EVIDENCED` or
       `TRANSFER_TO_ACCOUNT`.

     A listed kind is admissible only when the subject has the side its lines require (ADR-0071
     §2), and `SETTLEMENT_MISMATCH`'s posting kinds only for `REMITTANCE_DIFFERS` (point 9).
     *(The Phase 7 → 8 transition's consistency review, A1, A2, A3.)*
   - **One remainder, one type.** When several classes apply to an unallocated external
     remainder, precedence is: the definitive specific types (`REFUND_MISMATCH`,
     `REVERSAL_MISMATCH`, `CURRENCY_MISMATCH`, `DUPLICATE_EXTERNAL`, `AMBIGUOUS_MATCH`), then
     `MISSING_INTERNAL` (the operation is known but not completed), then `UNKNOWN_EXTERNAL`.
   - **One scheme reference, one internal explanation.** On the instant rail, Phase 7's
     `payments.scheme_execution_claim` (payments `V023`, the transition's repairs) holds one row per
     `(rail, scheme_reference)`, naming the pay-in, withdrawal, return or parking that explains the
     execution, claimed by every producer before money moves. `InternalReferenceLookup` resolves a
     scheme line's reference through it to exactly one subject, so the platform's own records do
     not collide on a scheme reference written since `V023`. A scheme line whose reference no
     claim holds names no completed execution: after grace it is `MISSING_INTERNAL` when its other
     references name an operation still in flight, and `UNKNOWN_EXTERNAL` otherwise.
   - **A card capture cleared twice.** Since the transition's repairs, a second, different network
     clearing of one capture is its own Phase 7 outcome (`SECOND_PRESENTMENT`, ADR-0059): loud and
     counted unmappable, but kept only in the retained evidence, with no clearing-notice table and
     no cleared amount. Phase 8 hears it at the clearing hop, whose cleared-amount and
     second-presentment evidence belongs to ADR-0065's two evidence hops: the PSP report's line in
     `settlement.line` is the clearing record, and there is no separate clearing-notice table. The
     line has three outcomes. It claims the capture's expectation a second time, becoming
     `DUPLICATE_EXTERNAL` or an `AMOUNT_MISMATCH` excess, or it quotes only an ARN no alias records
     (a `SECOND_PRESENTMENT` registers none, ADR-0067 §5) and becomes `UNKNOWN_EXTERNAL`. Each
     parks with its break, never absorbed (ADR-0065). *(This gave a second presentment only
     `DUPLICATE_EXTERNAL`; aligned to ADR-0065's three outcomes by the Phase 7 → 8 transition's
     re-check, R10.)*
   - **Why these fourteen.** Every discrepancy `RECONCILIATION_MODEL.md` names has at least one
     type, and three types cover what it does not name:

     | `RECONCILIATION_MODEL.md` names | Types |
     |---|---|
     | missing internal record | `MISSING_INTERNAL` (the operation is known but not completed), `UNKNOWN_EXTERNAL` (nothing internal is known) |
     | missing external record | `MISSING_EXTERNAL` |
     | amount difference | `AMOUNT_MISMATCH`; `SETTLEMENT_MISMATCH` (`REMITTANCE_DIFFERS`) at the bank hop |
     | currency difference | `CURRENCY_MISMATCH` |
     | fee difference | `FEE_MISMATCH` |
     | timing difference | `TIMING_DIFFERENCE` |
     | reversal | `REVERSAL_MISMATCH`, `REFUND_MISMATCH` |
     | duplicate detection | `DUPLICATE_EXTERNAL`, `DUPLICATE_INTERNAL` |
     | *(not named)* | `AMBIGUOUS_MATCH` (the engine refuses to guess; the one break `MANUAL_MATCH` resolves), `SETTLEMENT_MISMATCH`'s statement causes (the cash proof's continuity, `INV-SET-06`), `PROCESSING_ERROR` (our own defect, loud and CRITICAL rather than a silent stall) |

     Each split exists because resolution or severity differs. `MISSING_INTERNAL` usually heals
     itself: the sweeper completes the capture and the rematch unparks it. `UNKNOWN_EXTERNAL` may
     be misdirected money: nothing internal names its owner, so when nobody claims it, its CREDIT
     side ends in `RECOGNISE_GAIN` after the minimum age — the exit every suspense-owning type has
     except the three the table excludes. *(This sentence read "only it and `AMOUNT_MISMATCH` may
     end in `RECOGNISE_GAIN`", against the table's own rows; aligned by the transition's
     consistency review, A2.)* `REFUND_MISMATCH` is CRITICAL because money left for a refund the
     platform does not hold as completed. `DUPLICATE_INTERNAL` is a question about our own
     records, not about the counterparty.
   - **Causes.** `cause` records which detector raised the break, and it is frozen with it: a
     reclassified break still says how it was found. The causes the design names are
     `PARKED_ON_RECEIPT` and `BANK_LINE_UNATTRIBUTED` (`UNKNOWN_EXTERNAL`), `REMITTANCE_DIFFERS`,
     `STATEMENT_GAP` and `OPENING_BALANCE` (`SETTLEMENT_MISMATCH`), `RUN_BLOCKED`
     (`PROCESSING_ERROR`) and `RETURN_NOT_APPLICABLE` (`REVERSAL_MISMATCH`). The rest of the closed
     enum, one member per detector in the table above, is named by `P8-TSK-010` and restated in
     `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`.

3. **A break is born only from a stored fact the platform detected, in the transaction that
   detects it.**

   | Raiser | Types | Transaction | Task |
   |---|---|---|---|
   | The matcher's chunk (`ReconciliationSchedule`'s run leg; also rematch and `REPROCESS`) | The definitive classes: `DUPLICATE_EXTERNAL`, `CURRENCY_MISMATCH`, `AMOUNT_MISMATCH`, `AMBIGUOUS_MATCH`, and `REFUND_MISMATCH` or `REVERSAL_MISMATCH` against a terminal state; `TIMING_DIFFERENCE`; `FEE_MISMATCH`; `PROCESSING_ERROR` for an errored item | The deciding chunk, with its park | `P8-TSK-011`, `P8-TSK-012` |
   | The run leg, on N consecutive failures | `PROCESSING_ERROR` (`RUN_BLOCKED`), as the run enters `BLOCKED` | The same | `P8-TSK-011` |
   | The grace leg | `UNKNOWN_EXTERNAL`, `MISSING_INTERNAL`, or the specific type the lookup then finds, for example `REVERSAL_MISMATCH` (`RETURN_NOT_APPLICABLE`) for a payout return that could not be applied (ADR-0073) | The leg's bounded batch, with its park | `P8-TSK-013`; `P8-TSK-018` types `RETURN_NOT_APPLICABLE`, which `P8-TSK-019`'s worker pre-empts inside grace by applying the return *(the Phase 7 → 8 transition's re-check, R8)* |
   | `ReconciliationSweepSchedule` | `MISSING_EXTERNAL`, with `overdue_since` and `reconciliation.SettlementExpectationOverdue`; `DUPLICATE_INTERNAL` from recorded `KEY_COLLISION` events | Per row | `P8-TSK-010`, `P8-TSK-013` |
   | Statement acceptance, through `AcceptedBatchIntake` | `UNKNOWN_EXTERNAL` (`BANK_LINE_UNATTRIBUTED`), one per unattributed line with its suspense item; `SETTLEMENT_MISMATCH` (`STATEMENT_GAP`, `OPENING_BALANCE`) | Acceptance | `P8-TSK-016` |
   | A bank item matched to its remittance | `SETTLEMENT_MISMATCH` (`REMITTANCE_DIFFERS`) | The deciding chunk | `P8-TSK-016` |
   | `UnmatchedConfirmations`, through the `SettlementExpectations` port | `UNKNOWN_EXTERNAL` (`PARKED_ON_RECEIPT`) with its suspense item; existing rows adopted by an idempotent backfill | The confirmation's | `P8-TSK-020` |
   | Decision replay | `PROCESSING_ERROR` on `DIVERGED` (subject: the replayed run) | The replay's | `P8-TSK-022` |

   - **No person raises a break**, and there is no route for it. Every break points at a stored
     fact. An investigator who finds a break mis-typed reclassifies it (point 7). A batch proven
     fabricated or mis-normalised is repudiated as a batch (ADR-0071, `P8-TSK-023`); that
     resolution's subject is the batch, not a break.
   - **Definitive now, or after grace (ADR-0068).** Classes no later internal record can change
     park at once with their break. Classes that late internal evidence could explain
     (`UNKNOWN_EXTERNAL`, `MISSING_INTERNAL`, a `PAYOUT_RETURNED` line with no return yet) wait in
     `UNMATCHED` until `grace_until`, judged in SQL on the database clock. Only then does the grace
     leg park the item and raise the break. A `PAYOUT_RETURNED` line's rule is operation-anchored
     in rule set v1 (ADR-0073 §3): it is never key-matched against the outbound payout's own
     expectation, so the matcher raises nothing for it before grace.
   - **Nothing waits unowned.** A run never completes with an item `PENDING` (domain plus a
     deferred trigger). Every unallocated remainder either waits in `UNMATCHED` inside its grace,
     counted by `finapp.reconciliation.item.unmatched`, or is parked with its break in its own
     transaction (`INV-REC-09`).
   - **Raising converges.** A raise is an insert that yields to the partial uniques of point 4
     (`ON CONFLICT DO NOTHING`), behind the raising leg's own conditional on its subject (the
     item's transition, `overdue_since` NULL → value). A loser writes no event and no audit, and
     counts nothing.
   - **Each raise records itself.** `reconciliation.ReconciliationBreakRaised` (breakId, type,
     cause, severity, sourceCode, subject ids; never an amount or a reference value) goes through
     the outbox on the acting connection (`INV-EVT-01`, the full envelope of `INV-EVT-03`). The
     audit action `reconciliation.BreakRaised` is written acting-only, with identifiers in its
     change summary. After commit, `finapp.reconciliation.break.raised` {`type`, `severity`} is
     counted.
   - **A file is never a break subject.** Refused deliveries and rejected files are settlement
     states with alerts (`finapp.settlement.delivery.refused`, `finapp.settlement.file.rejected`).
     Their value is represented by the expectations that age into `MISSING_EXTERNAL` on schedule.
     A break needs a subject that holds value.

4. **One open break per (type, subject); a recurrence is a new break that names its
   predecessor.**
   - There are four partial uniques, `(type, expectation_id)`, `(type, external_item_id)`,
     `(type, suspense_item_id)` and `(type, run_id)`, each `WHERE status <> 'RESOLVED'`. The only
     break whose sole subject is a decision, `TIMING_DIFFERENCE`, is raised in that decision's own
     transaction, and a decision is written once. A diverged replay's `PROCESSING_ERROR` stands on
     the replayed run, with the first divergent decision named beside it, so a repeated replay —
     or a replay of a run already blocked — converges on the run's open break.
   - Different types may stand on one subject at once: an expectation with a `DUPLICATE_INTERNAL`
     break can still age into `MISSING_EXTERNAL`. Two breaks of one type on one subject never
     stand open together.
   - `follows_break_id` names the break a new one continues:
     - a **recurrence**: the same (type, subject) going wrong again after its break was resolved;
     - a **succession**: evidence that disproves a break's type but leaves a residual. The earlier
       break resolves `EVIDENCED`, and the residual's break is raised following it, in the same
       transaction. A late but short line on an overdue expectation resolves `MISSING_EXTERNAL`,
       with the timing recorded, and raises `AMOUNT_MISMATCH` (subject: the expectation)
       following it.
   - **A resolved break is never reopened.** The chain keeps both the finished case and its
     continuation, and the trace reads the case's age from the oldest `raised_at` in it. The
     successor's severity is its own (point 5).

5. **Severity is computed, stored, and only ever rises.**
   - The levels are `LOW`, `MEDIUM`, `HIGH` and `CRITICAL`.
   - **The base** comes from the type, refined where the table says by direction
     (`UNKNOWN_EXTERNAL` OUTBOUND), by expectation kind (`MISSING_EXTERNAL` on `MERCHANT_PAYOUT`
     or `REMITTANCE`) or by cause (`SETTLEMENT_MISMATCH`'s statement causes).
   - **One level up** when the value at issue is ≥ `high_value_minor`, read from the
     `severity_threshold` row of the break's pinned rule set for its currency.
   - **One level up per ageing band crossed**, measured from `raised_at` on the database clock.
     The bands are 0–2, 3–7, 8–30 and over 30 days.
   - The result is capped at `CRITICAL`. It is stored at raise. Escalations are applied by
     `ReconciliationSweepSchedule`'s severity leg as conditional, forward-only updates
     (`WHERE severity < :computed`), each appending a `break_event`, so ten sweepers produce one
     escalation per band.
   - **Reclassification** recomputes the base under the new type and keeps the higher of the
     stored and the recomputed severity. A severity that could fall would let a relabel quiet an
     alert.
   - Severity is therefore a deterministic function of stored data: type, direction or kind,
     cause, value at issue, `raised_at` and the pinned rule set.
   - **The threshold is rule-set content.** It is frozen from `PROPOSED` and activated under
     four-eyes by `RECONCILIATION_ADMINISTER`, whose role is disjoint from the resolvers'
     (ADR-0068, ADR-0071). A new version grades only the breaks raised under it, because every
     break pins its own rule set. A tolerance or threshold loosened today cannot quiet a break
     already raised.
   - Rule set v1 seeds `high_value_minor` at 1,000.00 for EUR, GBP and USD. *(Owner decision O7,
     settled 2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the
     owner. Revisiting it is a new rule-set version under four-eyes, not a migration.)*
   - **Alerting** on `finapp.reconciliation.break.age` {`severity`}: CRITICAL over 0 h, HIGH over
     1 d, MEDIUM over 5 d, LOW over 15 d. The backlog is on `finapp.reconciliation.break.open`
     {`type`, `severity`}. `severity` joins `MetricNames`' tag vocabulary with its written
     argument (a closed enum of four); the break type uses the existing `type` key. No series
     carries an amount (ADR-0072): the value at issue is reported by the audited
     `/reports/reconciliation/unmatched`.

6. **The lifecycle.**

   ```
   OPEN ──assign──▶ INVESTIGATING ──propose──▶ RESOLUTION_PROPOSED ──approve──▶ RESOLVED (terminal)
     └──propose────────────────────────────────▶      │ reject / withdraw
                                                       ▼
                                                 INVESTIGATING
   OPEN | INVESTIGATING | RESOLUTION_PROPOSED ──EVIDENCED (platform only)──▶ RESOLVED
   OPEN | INVESTIGATING ──single-person zero-value ACKNOWLEDGE──▶ RESOLVED
   ```

   | Edge | Driver | Condition |
   |---|---|---|
   | (birth) → `OPEN` | The platform (point 3) | A stored fact; the (type, subject) has no open break |
   | `OPEN → INVESTIGATING` | The first assignment (`RECONCILIATION_INVESTIGATE`) | Publishes `reconciliation.BreakInvestigationStarted` (breakId, assigneeId) |
   | `OPEN \| INVESTIGATING → RESOLUTION_PROPOSED` | A proposal (`RECONCILIATION_RESOLVE`, ADR-0071) | The kind is in the type's row (otherwise `reconciliation.ResolutionKindNotAllowed`); one `PROPOSED` resolution per break |
   | `RESOLUTION_PROPOSED → INVESTIGATING` | Rejection (another `RECONCILIATION_RESOLVE` holder, reasoned) or withdrawal (the proposer) | — |
   | `RESOLUTION_PROPOSED → RESOLVED` | Approval (a second person where ADR-0071 requires it) | The residual and `residual_version` re-read under lock equal the frozen ones; the posting commits in the same transaction |
   | `OPEN \| INVESTIGATING \| RESOLUTION_PROPOSED → RESOLVED` | The platform, `EVIDENCED` (point 8) | A stored fact leaves nothing at issue; a pending proposal is withdrawn in the same transaction |
   | `OPEN \| INVESTIGATING → RESOLVED` | One person, a zero-value `ACKNOWLEDGE` (born `APPROVED`) | Value at issue 0 and no posting |

   - **A stale approval moves nothing.** It is refused `409 reconciliation.ResolutionStale` and
     the resolution stays `PROPOSED`. The way back is the proposer's withdrawal, then a new
     proposal.
   - The machine has the platform's three layers: the aggregate refuses invalid edges; a
     generated `CHECK` and an every-writer transition trigger come from `permittedTransitions()`;
     and `break_event` is the append-only history (actor id, actor type, occurred at, reason).
     `CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))`.
   - **Invalid:** any edge out of `RESOLVED`; any `DELETE`; `RESOLVED` by a person without an
     approved resolution; `EVIDENCED` by a person; `RESOLVED` while the residual is non-zero,
     except as point 9 allows; reclassification outside `OPEN` and `INVESTIGATING`; lowering
     `severity` or `residual_version`.
   - A `RESOLVED` break has exactly one `APPROVED` resolution naming it. This is refused otherwise
     at the domain and, for every writer, by a deferred trigger (the shape of ledger `V010`'s
     `adjustment_entry_is_approved`). The terminal state makes it the only one.
   - **Resolving a break resolves its subject.** When an approved closing resolution removes an
     item's parked value, the item moves to `RESOLVED`; when it removes an expectation's
     remainder, the expectation moves to `RESOLVED_BY_ADJUSTMENT`.

7. **The case file is the investigation: append-only, audited, and never a second machine.**
   Investigation has no aggregate of its own. `INVESTIGATING` is a break state, and the case file
   hangs off the break. All routes are under `/v1/operator/reconciliation/`, held by
   `RECONCILIATION_INVESTIGATE` (the `RECONCILIATION_OPERATOR` role).
   - **Reads:** `GET breaks?type=&status=&severity=&source=&assignee=&agedOver=` (bounded at 100
     with `truncated`), `breaks/{id}` and `breaks/{id}/trace`. The trace walks the stored
     identifier chain with no timestamp join: `file → batch → recognition entry → run → item →
     decision (candidates) → allocation | park | break → notes → resolution → adjustment proposal
     → journal entry`, and on the internal side `expectation → journal entry → operation →
     provider_evidence`. Raw file content is read only through settlement's audited, reasoned
     content reads (`settlement.SettlementFileContentRead`).
   - **Assignment** (`POST breaks/{id}/assignment`) sets `assignee` and appends a `break_event`
     (the assignee history). The first one moves `OPEN → INVESTIGATING`. Audited
     `reconciliation.BreakAssigned`; concurrent assignments serialise on the break row.
   - **Notes** (`POST breaks/{id}/notes`, keyed) go to `break_note`, append-only, with a body of
     1..4000 characters. A body holding a Luhn-valid 13–19-digit run or an IBAN shape is refused
     `422 api.ValidationFailed` with nothing stored (`INV-PAY-02`, `INV-RAIL-03`). Bodies are
     `CONFIDENTIAL`: never logged, evented or audited. `reconciliation.BreakNoteAdded`
     carries no body.
   - **Evidence links** (`POST breaks/{id}/evidence-links`, keyed) go to `break_evidence_link`,
     append-only, audited `reconciliation.BreakEvidenceLinked`.
   - **Reclassification** (`POST breaks/{id}/classification {type, reason}`) is allowed in `OPEN`
     and `INVESTIGATING` only, because a proposal's frozen lines depend on the type. It is
     reasoned, audited `reconciliation.BreakReclassified`, appended as a `break_event`, and it
     moves `residual_version` and severity (point 5). The new type must accept the break's
     subject kind, and must park exactly when the break's subject holds parked value. Otherwise a
     parked item's owner could become a type whose closure leaves its suspense behind
     (`INV-REC-09`). A reclassification onto a (type, subject) that already has an open break is
     refused by the partial unique (`409 api.Conflict`); the investigator links the two instead.
     `cause`, the subject and the value at issue never change.
   - On a `RESOLVED` break every write is refused (`409 reconciliation.BreakTerminal`), and the
     case continues on its successor (point 4). An unknown id is `404
     reconciliation.BreakNotFound`.
   - Idempotency scopes are per principal from birth (`<command>:<actorType>:<actorId>`).

8. **`EVIDENCED` is a stored resolution, not a clearing.** `INV-REC-02` is amended at the
   transition, with provenance: "an `EVIDENCED` resolution — the platform closing a break because
   a later zero-residual allocation or offset explains it, stored as a Resolution row naming that
   decision and posting — is recorded, not silent, and is the only resolution no person decides".
   - **The row.** `reconciliation.resolution` (reconciliation `V006`, `P8-TSK-012`) holds kind
     `EVIDENCED` with reason code `EVIDENCE_RECEIVED`, which is reserved to the platform. It is
     born `APPROVED`, proposed and decided by the platform (actor type `SYSTEM`), with
     `four_eyes` false: `CHECK (kind = 'EVIDENCED' ⇒ proposed_by = system AND status =
     'APPROVED')`. No person can propose or approve one, or be recorded on one.
   - **What it names.** It names the decision (`decision_id`) whose allocation or correction offset
     explained the break. Where that evidence moved parked value, it also names the park
     (`park_id`), whose entry `recon-suspense:<parkId>` is the posting. It never holds
     `adjustment_proposal_id` or `journal_entry_id`: those belong to a posting resolution and are
     unique, while one aggregated park entry can explain several breaks at once. The exact
     columns are `P8-TSK-012`'s, under that rule.
   - **The evidence that closes a break:**

     | Evidence | Closes | The row names |
     |---|---|---|
     | A late allocation: rematch, a later run, `REPROCESS` | `MISSING_INTERNAL`, `UNKNOWN_EXTERNAL`, `REFUND_MISMATCH` (a late completion), `SETTLEMENT_MISMATCH` (`REMITTANCE_DIFFERS`; the balance arrives), `PROCESSING_ERROR` of an item | The decision, and the park that unparked it |
     | A `CORRECTION` line filling a remainder or offsetting a parked excess (`CORRECTION_OFFSET`) | `AMOUNT_MISMATCH`, `DUPLICATE_EXTERNAL` (a claw-back), `CURRENCY_MISMATCH` and `REVERSAL_MISMATCH` (the counterparty's own correction of the line; the consistency review, A1) | The decision, and the park of the offset |
     | A late match of an overdue expectation | `MISSING_EXTERNAL`, with the timing recorded on the decision instead of a `TIMING_DIFFERENCE` | The decision |
     | An accepted statement that restores the chain | `SETTLEMENT_MISMATCH` (`STATEMENT_GAP`) | The run of that statement |
     | A requeued run completing; a later replay of the run `IDENTICAL` after a fix | `PROCESSING_ERROR` of a run | The run |

     The last two rows are not allocations or offsets. They follow the same rule — a stored fact
     the platform did not decide, named on the row, leaving nothing at issue — and the
     amendment's wording is to be widened to them by `P8-TSK-016` and `P8-TSK-022`, with
     provenance.
   - **Evidence racing a person.** Both take the break row first. If the evidence commits first,
     the pending proposal is withdrawn in the same transaction: the resolution becomes `WITHDRAWN`
     by the platform, and its ledger proposal is rejected through `AdjustmentService.rejectOwned`.
     The approver then finds nothing pending (`409 reconciliation.ResolutionNotPending`). If the
     approval commits first, the late evidence finds no open break. A late line then parks as a
     recovery (`DUPLICATE_EXTERNAL`, a new break). Reversing a write-off automatically is out of
     scope.
   - It is audited `reconciliation.BreakResolvedByEvidence` (acting-only) and publishes
     `reconciliation.BreakResolved` (breakId, resolutionId, kind, reasonCode; no `journalEntryId`,
     because it has no posting of its own). It is counted by `finapp.reconciliation.resolution`
     {`type`, `outcome` = `evidenced`}.

9. **A break closes when nothing is left at issue; two exceptions close with nothing to
   remove.** A break closes when its residual is zero, reached either by evidence (`EVIDENCED`)
   or by an approved resolution whose posting removes it (ADR-0071). There are two exceptions:
   - **`ACKNOWLEDGE`**, and only on three types:
     - `TIMING_DIFFERENCE`, whose value is zero;
     - `FEE_MISMATCH`, whose reported fee was expensed at recognition, so no position holds the
       difference (ADR-0065);
     - `DUPLICATE_INTERNAL`, where the expectation keeps its remainder and keeps ageing under its
       own keys. A real duplicate is written off instead.
     It is one person when the value at issue is zero, and four-eyes otherwise (ADR-0071).
   - **`EVIDENCED` of a break on a run** (point 8's last two rows), where no subject row holds
     value.

   **The statement causes never post.** `SETTLEMENT_MISMATCH` with `STATEMENT_GAP` or
   `OPENING_BALANCE` closes only `EVIDENCED`. `INV-SET-06` makes `CASH_AT_BANK` postable only by
   recognising or repudiating a statement, and forbids adjusting it to fit, so the row's
   `WRITE_OFF`, `TRANSFER_TO_ACCOUNT` and `RECOGNISE_GAIN` apply to `REMITTANCE_DIFFERS` alone. The simulated bank
   opens at zero, so outside tests an `OPENING_BALANCE` break is never raised. If one is, it
   stays open and CRITICAL, with the cash proof failing loudly, because the only honest
   counter-account for a non-zero opening is equity, which is Phase 14's. *(Owner decision O4,
   settled 2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the
   owner.)*

   This is how `INV-REC-03` ("resolved by posting a compensating entry") and the gate's "a new
   record plus a compensating posting" are read. The transition's "Break immutability" criterion
   restates the bullet measurably as a new `resolution` row plus, for a posting kind, a
   compensating `ADJUSTMENT` entry. *(Cited by number until the transition's consistency review,
   B15: the gate's criteria are labels.)* For `EVIDENCED`,
   the compensating posting is the evidence's own unpark or offset, when value was parked. For
   `ACKNOWLEDGE`, no position holds value to compensate. Every closure is a new record, and no
   record is edited.

10. **Nothing is deleted or edited.**
    - `finapp_app` holds no `DELETE` on any table of the reconciliation schema. A `BEFORE DELETE`
      trigger on `break` refuses every writer, which puts `MODULE_ARCHITECTURE.md`'s hard rule at
      two ranks.
    - `break_event`, `break_note` and `break_evidence_link` are granted `SELECT, INSERT` only,
      with append-only triggers.
    - `break`'s `UPDATE` is narrowed to the moving columns of point 1; the transition trigger
      refuses edges and any change to a frozen column.
    - A break raised in error is not removed. It is reclassified, or resolved by a kind its row
      allows, and the record of the error stays.
    - **Evidence (`INV-REC-01`).** A break's subject reaches the settlement line, file, batch and
      raw content; the payments or merchant record, its journal entry and
      `payments.provider_evidence`; and the match decision with its candidates. Every table on
      that path is append-only except the dispositions. Privilege tests cover every writer
      (the transition's "Break immutability" criterion).

11. **Ten instances.** Every contention a break meets has a PostgreSQL arbiter and a counted
    ten-way test:

    | Contention | Arbiter | Loser |
    |---|---|---|
    | Legs or sweepers raising one discrepancy | The raising leg's conditional on its subject; the partial unique of point 4 | Converges; records nothing |
    | Sweepers escalating one break | The conditional forward-only update | One `break_event` per band |
    | Concurrent assignment, reclassification, notes and links | The break row `FOR UPDATE`; keyed notes and links | Serialised; replay or 409 |
    | `EVIDENCED` against a pending approval; ten approvers | The break row first, then the resolution row | The first wins; `ResolutionNotPending`, `BreakTerminal` or `ResolutionStale` |
    | Allocation against ageing on one expectation | The expectation row `FOR UPDATE`, plus conditionals | Either order converges: no break, or `EVIDENCED` |

    - **The lock order** is DISTRIBUTED_EXECUTION §3's row: (1) advisory namespace 4 for the
      source, when the transaction allocates, parks or unparks; (2) the break row, then the
      resolution row; (3) expectation rows, then external item rows, then suspense item rows, each
      sorted by id; (4) the merchant payout row, taken by the return worker only; (5) the
      attribution target's account `FOR SHARE`; (6) inside `approveOwned`, the ledger proposal row,
      then the projection rows sorted by account id. Postings are last. A transaction posting
      several entries over shared rows — a chunk parking on two positions, a repudiation's
      reversal and its unparks — pre-locks the union of the platform's rows it will touch in the
      projection's own order before its first posting (`PostingService.lockBalancesInOrder`,
      `DISTRIBUTED_EXECUTION.md` §3's multi-entry lock-order rule, which the transition's repairs
      made for dispute postings, ADR-0061 §5), and takes any runtime counterparty's `ACCOUNT` row
      — `FOR SHARE` where a share lock suffices — before any projection row; seed order is never
      the argument. *(Aligned to the row by the Phase 7 → 8 transition's consistency review, B9;
      the multi-entry rule is the repairs'.)*
    - A leg that may close a break takes the break's row before its subject's rows, the order
      approval keeps. A chunk that meets, under its subject locks, an open break it did not lock
      first never takes that row out of order. It rolls back, and the idempotent chunk retries.
    - Windows (grace, overdue, the ageing bands) are judged in SQL on the database clock against
      stored dates.
    - No correctness rests on an event. Phase 8 has no Kafka consumer, and its events are
      notifications (`INV-EVT-04`).

## Alternatives Considered

### Clear a break when later evidence explains it (delete it, or flag it cleared)
Pros: the simplest engine; the desk sees only live problems.
Cons: it is the auto-clearing `INV-REC-02` forbids. The record of what went wrong and how it was
explained disappears, and a matcher defect that "explains" breaks wrongly leaves nothing to find.
`EVIDENCED` keeps the closure and names its evidence.

### The platform proposes and a person confirms every closure (design A)
Pros: every closed break carries a human decision, and no code path closes a break alone.
Cons: a zero-residual allocation leaves nothing to judge. At volume the confirmation becomes a
rubber stamp, and a queue of explained-but-open breaks pollutes ageing and alerts. It is recorded
as the alternative in `INV-REC-02`'s amendment. A pinned policy could still route classes of
evidence to a person later.

### Fewer, open-ended types (one `UNMATCHED` with a free-text reason)
Pros: simple to raise; a new situation needs no migration.
Cons: resolution kinds cannot be bound to what they fix (ADR-0071), severity cannot be computed,
the `type` tag becomes unbounded (ADR-0018), and "every break type is detectable" becomes
untestable.

### Raise a new break on every detection
Pros: no uniqueness logic; every detection is recorded.
Cons: ten sweepers and every rematch would raise duplicates. One resolution would leave siblings
open over the same value, and every report would count the value at issue twice. With the uniques,
a repeated detection converges instead.

### Reopen a resolved break when its subject goes wrong again
Pros: one case file per subject.
Cons: a resolved break has an approved resolution and, for posting kinds, an entry. Reopening
makes `RESOLVED` non-final and the resolution's meaning ambiguous. A new break with
`follows_break_id` keeps both the finished case and its continuation.

### Severity set by operators, or computed only at read time
Pros: operators know the context; read-time computation needs no stored column.
Cons: set by operators, it is opinion and can be lowered to quiet an alert. Computed at read time,
it has no history and changes silently with a new rule set. Stored, forward-only and pinned to the
break's rule set, it is reproducible and auditable.

### File-level control breaks (design B)
Pros: a rejected or refused file shows on the break desk.
Cons: a break needs a subject that holds value, and a file holds none of its own. Its value is
represented by the expectations that age into `MISSING_EXTERNAL`; file states and alerts show the
file itself.

### Park everything at once, with no grace (design C)
Pros: fewer states; unexplained value leaves the position immediately.
Cons: ordinary orderings — the report before the bank, the file before the webhook, the sweeper
completing a capture — raise breaks that are explained moments later, burying the ones that
matter. Definitive classes park at once; classes that late internal evidence can change wait out a
grace window.

### An investigation (or exception) aggregate of its own
Pros: a richer case-management workflow, and a home for Phase 13's case work.
Cons: two lifecycles over one discrepancy, which must agree. `INVESTIGATING` is a break state, the
case file hangs off the break, and an exception is a view over refused deliveries, rejected files,
breaks, silent sources and blocked runs.

## Consequences

Positive:
- Every discrepancy has at most one open record per (type, subject) under N instances, and repeated
  detections converge.
- The desk sees what needs a person. Explained breaks close themselves on the record, naming their
  evidence.
- Severity is reproducible from stored data, with its escalations in history, and no threshold
  change or relabel can lower it.
- Each type carries its allowed resolution kinds, so ADR-0071's template-bound resolutions have a
  table to be checked against.
- The gate's first bullet becomes a finite, testable list of fourteen, and its third is held by
  privilege and trigger.

Negative:
- Fourteen types plus their causes are vocabulary operators must learn. A misclassification at
  raise costs a person's reclassification.
- The platform closes breaks with no person. A matcher defect could close one wrongly, and a
  resolved break is never reopened. The mitigations are the stored decision snapshot and replay
  (ADR-0068), the resolution naming its evidence, and the proofs (the position proof,
  `INV-REC-06` and ADR-0067; the suspense proof, ADR-0070). Committed allocations stand as
  history, and money is corrected by resolution or repudiation.
- No person raises a break. A discrepancy no detector recognises has no break until one exists;
  meanwhile the position proof and `finapp.reconciliation.line.unattributed` still show the
  unexplained value.
- A successor break starts young. The case's age is read from the chain, not from one row.
- A severity raised by a mistaken reclassification stays loud until the break is resolved.

Operational impact: per-severity age alerts (CRITICAL at once, HIGH after a day, MEDIUM after five,
LOW after fifteen); the backlog by type and severity; the case file, trace and settlement-status
reads; the dashboard's break panels. Amounts are reported only by the audited reports (ADR-0072).
Security impact: the case file needs `RECONCILIATION_INVESTIGATE`; note bodies are screened for
card-number and bank-identifier shapes, and never reach a log, event or audit body; reclassification
is reasoned and audited; raw evidence is read only through settlement's audited content reads.
Financial impact: a break moves no money. It records the value at issue per break in its own
currency and owns every unit of parked value (`INV-REC-09`). Parking (ADR-0070) and resolution
postings (ADR-0071) move money, each in a transaction that names its break.

## Invariants / Constraints

`INV-REC-02` (amended: `EVIDENCED` is recorded, not silent), `INV-REC-01`, `INV-REC-03`,
`INV-REC-05`, `INV-REC-06`, `INV-REC-09`, `INV-SET-02`, `INV-SET-03`, `INV-SET-06`, `INV-HIST-04`,
`INV-MON-04`, `INV-AUD-01`, `INV-AUD-02`, `INV-AUD-03`, `INV-PAY-02`, `INV-RAIL-03`, `INV-EVT-01`,
`INV-EVT-03`, `INV-EVT-04`, and `INV-LIFE-04`'s discipline applied to breaks (terminal is terminal).

## Follow-up

- `P8-TSK-010` builds the records: reconciliation `V004` (`break` with its history, notes and
  evidence links, the four partial uniques, the refusing trigger, no `DELETE` grant),
  `BreakRegister.raise`, the `InternalReferenceLookup` port, `DUPLICATE_INTERNAL` from recorded
  collisions, `reconciliation.ReconciliationBreakRaised`, and every type raisable with its severity.
  It also closes the enum of causes.
- `P8-TSK-010` — **implemented** (2026-09-29), with three recorded clarifications. The type—cause pairing of point 2 binds the RAISE by a generated `BEFORE INSERT` trigger, deliberately not a table `CHECK`: point 7's reclassification moves the type while the cause stays frozen, so a life-long pairing would refuse the edge this ADR allows. "An investigator's reclassification" is a detector in point 2's table but not a raise, so the closed cause enum carries no member for it — a reclassified break keeps the cause of the detector that found it. And the severity policy is one pure seat (`BreakSeverity`), applied inside `JdbcBreakRegister.raise` with the threshold read from the pinned rule set's own `severity_threshold` row — a raiser cannot pass a quieter grade; the ageing bands escalate on the stored row with `P8-TSK-013`. The one-open uniques, the raise's convergence (losers record no history, no audit, no event), the recurrence via `follows_break_id`, the notes' database-rank PAN and IBAN screens and the every-writer no-DELETE (grant absent AND trigger, the migrator included) are all proven by `BreakAndSuspenseDatabaseTest`; the key-collision leg raises `DUPLICATE_INTERNAL` from the recorded events, once under ten legs, and a collision recorded after an earlier break resolved is suppressed by the leg's any-status guard — the recurrence refinement is the sweep's (`P8-TSK-013`, recorded). `InternalReferenceLookup` answers the strongest knowledge across the given references (`COMPLETED` over `TERMINAL` over `IN_FLIGHT` over `UNKNOWN`), frozen on the break; `ACQUIRER_REF` answers `UNKNOWN` in this task — the ARN's alias resolution is the matcher's key business (`P8-TSK-011`, recorded).
- `P8-TSK-011` — **implemented** (2026-09-30): the run leg produces the definitive detectors — `AMOUNT_DIFFERS` on both sides of a one-to-one mismatch (the excess parked on the item's subject, the shortfall recorded on the expectation's, never parked), `CURRENCY_DIFFERS`, `EXPECTATION_EXHAUSTED` and `REPEATED_FINGERPRINT` (both `DUPLICATE_EXTERNAL`, parked whole), `DIRECTION_CONTRADICTED`, `TERMINAL_STATE_CONTRADICTED` and `REFUND_CONTRADICTED` typed by the frozen lookup answer, `LATE_MATCH` as the zero-value observation naming its decision (the one decision-subject type), and `ITEM_ERRORED`/`RUN_BLOCKED` for the contained poison and the held source; `MULTIPLE_CANDIDATES` is stated and hermetically proven but unproduced (`expectation_key_once` admits one expectation per key), and §2's precedence over coexisting hit-failures — direction, then currency, then exhausted — is the engine's recorded reading, proven in `MatchEngineTest`.
- `P8-TSK-011` (the definitive classes, errored items, blocked runs), `P8-TSK-012` (`FEE_MISMATCH`,
  `CORRECTION` and the `EVIDENCED` resolution row, reconciliation `V006`, and the first
  `reconciliation.BreakResolved`, for `EVIDENCED`), `P8-TSK-013` (grace, ageing, rematch, severity
  escalation, run-block detection), `P8-TSK-014` (break reads, the case file, the trace,
  `reconciliation.BreakInvestigationStarted`), `P8-TSK-015` (four-eyes resolution, extending
  `reconciliation.BreakResolved` to the person's kinds), `P8-TSK-016` (bank recognition's
  unattributed lines and the statement causes), `P8-TSK-017` (cycle timing; scheme references
  resolved through Phase 7's execution claims, so a scheme-reference collision can come only from
  rows recorded before payments `V023`, which its backfill left unclaimed), `P8-TSK-018`
  (`RETURN_NOT_APPLICABLE`, typed with the payout source's first `PAYOUT_RETURNED` items whether or
  not `P8-TSK-019` lands), `P8-TSK-019` (the return worker, which pre-empts that typing inside grace
  by applying the return) *(this named `P8-TSK-019` for the typing; aligned to the backlog by the
  Phase 7 → 8 transition's re-check, R8)*, `P8-TSK-020` (`PARKED_ON_RECEIPT` and the adoption of
  Phase 7's rows), `P8-TSK-022` (replay's `PROCESSING_ERROR`, requeue) and `P8-TSK-024` (the break
  meters, the dashboard row and the alerts).
- `P8-TST-001` (each seeded fault produces exactly its break type and no extra breaks) and
  `P8-TST-002` (every break type crossed with every allowed resolution kind, evidence against
  approval, immutability by privilege and trigger).
- `INV-REC-02`'s amended wording is widened to the non-allocation evidence of point 8
  (`P8-TSK-016`, `P8-TSK-022`). `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` holds the break's
  machine, and the rewrite of `RECONCILIATION_MODEL.md` carries point 2's mapping.
- If the phase must shrink (owner decision O6: cut `P8-TSK-021`, then `P8-TSK-019`, then
  `P8-TSK-023`), the taxonomy is unchanged. Without `P8-TSK-019`, every returned payout takes the
  `RETURN_NOT_APPLICABLE` path to a four-eyes `TRANSFER_TO_ACCOUNT`. Without `P8-TSK-023`, a
  fabricated batch has no repudiation, and its breaks are resolved kind by kind. *(Settled
  2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the owner.)*
- Deferred and recorded as not implemented in Phase 8: automatic reversal of a write-off on late
  evidence, a person raising a break, value-banded (six-eyes) approval, and Phase 13's fraud or AML
  scoring of breaks.
- Until those tasks land, nothing in this ADR is implemented; every statement is the decided
  design, corrected by the tasks that build it.
- The Phase 8 review (`P8-DOC-001`) reads this ADR against the code before accepting it.
