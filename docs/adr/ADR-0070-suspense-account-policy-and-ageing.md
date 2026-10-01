# ADR-0070 — Suspense account policy and ageing

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Reconciliation · Settlement · Ledger · Payments · Merchant
Supersedes: nothing. Writes the Phase 8 decision `docs/adr/README.md` anticipates — "Suspense
account policy and ageing" — and `DELIVERY_PLAN.md` Phase 8 §14 asks for ("ADR on suspense account
policy"). Implements the seam `ROADMAP.md` records ("Suspense account + break record — introduced
Phase 3, implemented Phase 8") and meets the condition ADR-0040 set on it, which Phase 7's first
poster did not. Adopts ADR-0062 §5's parking of unmatched confirmations. Amends `INV-REC-05`;
catalogues `INV-REC-09`. Written beside ADR-0065 (bank recognition parks unattributed lines),
ADR-0067 (the port the unmatched confirmation opens through), ADR-0068 (when value parks),
ADR-0069 (the breaks that own it), ADR-0071 (the resolutions that release it) and ADR-0072 (why
its balance is a report).

The decision in one sentence: value enters suspense only with the break that owns it and leaves
only by evidence, a four-eyes resolution or a repudiation; an unclaimed credit becomes a gain only
after a pinned minimum age. *(The title was this sentence until the Phase 7 → 8 transition's
consistency review, B10, gave it the anticipated title.)*

## Context

`SUSPENSE_UNMATCHED` has existed since Phase 3 as a seam: one `LIABILITY` account per currency,
owner kind `SUSPENSE`, "unattributable value, parked, aged and reported" (`AccountPurpose`).
ADR-0040 set one condition on its use: "Value parked there must be trackable and ageable before
anything can park value there."

Phase 7 gave the account its first poster and did not meet that condition. A pay-in confirmation
the platform cannot credit posts DR the rail's clearing / CR `SUSPENSE_UNMATCHED`
(`UnmatchedConfirmations`, key `unmatched-confirmation:<rail>:<ref>`, ADR-0062 §5), and nothing
releases it. Since the transition's repairs there are three such causes: the statement names no
initiation the platform made (`UNATTRIBUTED`), names an attempt already concluded
(`ATTEMPT_CONCLUDED`), or executed an amount other than the attempt's ask (`AMOUNT_MISMATCH`, the
pay-in failing `DECLINED` in the same commit).
- `payments.unmatched_confirmation` has no state, no resolution and no link to a later match.
  Payments `V023` (the transition's repairs) gave each parking what Phase 8 needs to resolve it:
  the end-to-end reference the statement named when it had the platform's minted shape
  (`named_reference`), the settlement cycle, the `cause`, and `attempt_id` exactly when
  attributed. `payments.provider_evidence` gained a fifth subject, `unmatched_confirmation_id`,
  so a parking's raw statement is found by stored identifier. The state and the resolution stay
  this ADR's;
- its gauges `finapp.payments.unmatched.active` and `finapp.payments.unmatched.age` read
  `count(*)` and `min(received_at)` over every row ever parked
  (`JdbcUnmatchedConfirmationStore.parkedReading`), so they can only rise. An alert nobody can
  clear is not an alert;
- the ledger's generic adjustment door (`POST /v1/ledger/adjustments`, `LEDGER_ADJUST`) could move
  the value out, or put value in, with a reason in free prose, no break and no owner.

*(Found at the transition, read-only at `7598332`; the parking's recorded columns and its claim
are the transition's repairs, payments `V023`.)*

Phase 8 adds four more ways value reaches suspense:
1. the matcher parks what it cannot allocate: definitive anomalies at once, and what late internal
   evidence could still explain once a grace window has passed (ADR-0068);
2. bank recognition parks a bank line that matches no counterparty's remittance pattern
   (ADR-0065);
3. the over-part of an amount mismatch and a bank credit's excess over its remittance park with
   their breaks;
4. the repudiation of a statement batch reverses a suspense line whose value a resolution had
   already released, and that line reaches suspense again on the opposite side (point 10).

`INV-REC-05` states the outcome — tracked, aged, reported, alerted, "never a permanent resting
place" — but not the policy that produces it. It does not say who answers for a unit of suspense
value, what may take it out, how its age is measured, where its balance may be shown, or what
becomes of a credit nobody ever claims. The delivery plan names the failure directly: "Suspense
used as a dumping ground with no ageing discipline". It also asks for a "suspense account balance"
among the metrics (§10), which ADR-0018 forbids.

Two kinds of value rest in the one account, and they must not be confused:
- A **CREDIT** item is value the platform holds and cannot attribute: a payment received for no
  known payee, a counterparty's overpayment. It is an unrecognised liability to someone.
- A **DEBIT** item is value that left and that no record explains: a refund the PSP paid that the
  platform never completed, a bank debit with no reference. It is an unrecognised loss, or a
  receivable from someone.

Netting them inside one balance hides two open problems behind one number.

## Decision

1. **One suspense account per currency, decomposed into items; the item is the unit of policy.**
   - `SUSPENSE_UNMATCHED` stays one account per (purpose, currency), as ledger `V002`'s
     `ledger_account_one_operational_per_purpose_currency` requires. There is no per-counterparty
     suspense account and no new owner kind (Alternatives).
   - Every unit of value in it belongs to a `reconciliation.suspense_item` (reconciliation `V004`,
     `P8-TSK-010`): `break_id NOT NULL`, `external_item_id UNIQUE NULL`, `origin` (`RECON_PARK` |
     `BANK_UNATTRIBUTED` | `UNMATCHED_CONFIRMATION`; `REPUDIATION` added by reconciliation `V013`,
     `P8-TSK-023`), `origin_ref UNIQUE`, `side` (CREDIT | DEBIT), the ADR-0003 money triple,
     `released_minor`, `status`, `opened_on`, `entry_id`.
   - Its machine is `OPEN → PARTIALLY_RELEASED → RELEASED` (terminal), with
     `CHECK (released_minor <= amount_minor)`. It is held at the platform's three layers: the
     aggregate refuses invalid edges; a generated `CHECK` plus an every-writer transition trigger;
     and an append-only history, the `reconciliation.suspense_release` rows (`item_id`, amount,
     `park_id NULL`, cause, `cause_ref`), one per release, each naming the path that took the value
     and the decision, park or resolution behind it. Only an item's release columns are
     updatable, and `finapp_app` holds no `DELETE` on any of it.
   - `side` is fixed at birth. CREDIT and DEBIT items are never netted: not in a posting, not in
     the report (point 6), and not in an offset except the four-eyes one (point 3).

2. **Value enters suspense only in the transaction that records the break that owns it**
   (`INV-REC-09`). There are four openers, and no fifth:

   | Origin | Opened by, in its own transaction | Lines | Owning break | `opened_on` |
   |---|---|---|---|---|
   | `RECON_PARK` | the run leg's chunk (a definitive class, an errored item), the grace leg (after `grace_until`), the rematch leg | INBOUND remainder u: DR P u / CR `SUSPENSE_UNMATCHED` u, a CREDIT item. OUTBOUND: DR `SUSPENSE_UNMATCHED` u / CR P u, a DEBIT item. P is the item's clearing position. One entry per transaction and position, at most four lines, key `recon-suspense:<parkId>`, the `reconciliation.park` row minted in that transaction | the classified type (ADR-0069), raised in the same transaction | the park row's `decided_on` |
   | `BANK_UNATTRIBUTED` | bank statement acceptance, through `AcceptedBatchIntake` | inside the batch's recognition entry `settlement-batch:<batchId>`: CR `SUSPENSE_UNMATCHED` Σ unattributed credits, DR Σ unattributed debits; one item per line | `UNKNOWN_EXTERNAL`, cause `BANK_LINE_UNATTRIBUTED` | the batch's `accepted_on` |
   | `UNMATCHED_CONFIRMATION` | payments' `UnmatchedConfirmations`, through the `SettlementExpectations` port (`P8-TSK-020`), whatever the parking's stored cause (payments `V023`) | its existing entry `unmatched-confirmation:<rail>:<ref>`: DR the rail's clearing / CR `SUSPENSE_UNMATCHED` | `UNKNOWN_EXTERNAL`, cause `PARKED_ON_RECEIPT` | the posting date of that entry |
   | `REPUDIATION` | the `REPUDIATE_BATCH` approval (`P8-TSK-023`), for each `BANK_UNATTRIBUTED` item of the batch a posting resolution had already released (point 10) | the suspense line the recognition's reversal (`ReversalService`, scope `ledger.reverse`, key `settlement-batch:<batchId>`) carries for that already-released item, so the new item is of the opposite side; `origin_ref` the already-released item's id — an item is repudiated once, so `origin_ref UNIQUE` holds | a new `PROCESSING_ERROR`, raised in the same transaction | the reversal entry's posting date |

   *(The fourth opener was named by the Phase 7 → 8 transition's re-check, R3: point 10 already
   opened this item, while the list said "three openers, and no fourth". Its enum value arrives
   with reconciliation `V013`, not `V004`, which creates the three others.)*

   - **`opened_on` comes from stored data and never restarts.** It is the date the value entered
     suspense: the park row's `decided_on` (stamped once), the batch's `accepted_on`, the
     confirmation entry's posting date, or the repudiation's reversal entry's posting date. The
     confirmation's date is a Phase 7 clock read, stored with its entry; `X-TSK-009` reconciles the
     documentation and nothing is retrofitted. An adopted Phase 7 row keeps the date it was parked
     (point 8). A park's own entry is dated the same way: posting date the park row's
     `decided_on`, value date the item's settlement date.
   - **The break types that can own an item** are those whose value parks: `MISSING_INTERNAL`,
     `UNKNOWN_EXTERNAL`, the over-part of `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`,
     `DUPLICATE_EXTERNAL`, `AMBIGUOUS_MATCH`, `REVERSAL_MISMATCH`, `REFUND_MISMATCH`, the excess
     side of `SETTLEMENT_MISMATCH`, and the `PROCESSING_ERROR` of an errored item or of a
     repudiation (point 10). `MISSING_EXTERNAL`,
     `FEE_MISMATCH`, `DUPLICATE_INTERNAL` and `TIMING_DIFFERENCE` never own suspense.
   - **No other writer.** Ledger `V015` (`P8-TSK-006`) refuses a `MANUAL`-origin adjustment line
     on any `AccountPurpose.reconciledPositions()` purpose, `SUSPENSE_UNMATCHED`,
     `RECONCILIATION_GAINS` and `RECONCILIATION_LOSSES` among them. It refuses at the domain and in
     a `BEFORE INSERT` trigger (`422 ledger.AdjustmentOnReconciledPosition`). A raw-SQL poster or
     a missed opener is counted by the completeness verifier (point 7).
   - **Waiting is not suspense.** An item whose remainder late internal evidence could still
     explain (`UNKNOWN_EXTERNAL`, `MISSING_INTERNAL`, a `PAYOUT_RETURNED` with no return yet)
     waits in `UNMATCHED` until its `grace_until`, judged in SQL on the database clock. It is
     counted by `finapp.reconciliation.item.unmatched` and explained meanwhile by the position
     proof (`INV-REC-06`). An internal record that was never reported is not parked at all: its
     expectation explains it, and it ages into `MISSING_EXTERNAL` in its own position.
   - **Suspense is never a plug.** A non-zero first statement opening raises
     `SETTLEMENT_MISMATCH` (cause `OPENING_BALANCE`) with nothing posted (O4, point 12). A tolerance
     never absorbs a principal difference (`INV-REC-08`). A currency mismatch parks in the suspense
     account of the item's own currency and is never converted (`INV-MON-04`).

3. **Value leaves suspense only by evidence, by an approved resolution, or by the repudiation of
   the batch that brought it** (`INV-REC-09`). A resolution's lines are derived from the item's
   current remainder, never typed (ADR-0071).

   | Exit | A CREDIT item | A DEBIT item | Decided by |
   |---|---|---|---|
   | **Evidence**, for a `RECON_PARK` item: a late allocation unparks it (rematch, a `REPROCESS` run), or a counterparty's correction offsets it (release cause `CORRECTION_OFFSET`) | the park's exact inverse, DR `SUSPENSE_UNMATCHED` / CR P, under a new `recon-suspense:<parkId>` | DR P / CR `SUSPENSE_UNMATCHED`, likewise | the platform. The owning break resolves `EVIDENCED`, a stored resolution naming the decision and the park, and a pending proposal is withdrawn through `AdjustmentService.rejectOwned` in the same transaction |
   | `MANUAL_MATCH`, for an `AMBIGUOUS_MATCH` item | a `MANUAL`-origin decision allocates to the chosen expectation and unparks as any late allocation does | likewise | four-eyes: it stands in for the engine |
   | `TRANSFER_TO_ACCOUNT` | DR `SUSPENSE_UNMATCHED` / CR a named `CUSTOMER_WALLET` or `MERCHANT_PAYABLE` (point 9) | — | four-eyes |
   | `WRITE_OFF` | — | DR `RECONCILIATION_LOSSES` / CR `SUSPENSE_UNMATCHED` | four-eyes |
   | `RECOGNISE_GAIN` | DR `SUSPENSE_UNMATCHED` / CR `RECONCILIATION_GAINS`, only after the minimum age (point 4) | — | four-eyes |
   | `OFFSET_SUSPENSE` | one CREDIT and one DEBIT item with equal remainders in one currency; no posting, because the account already nets them; both released | the same resolution | four-eyes |
   | `REPUDIATE_BATCH` | point 10 | point 10 | four-eyes |

   - **A posting resolution** posts an `ADJUSTMENT` entry under scope
     `ledger.adjust.approve:<proposalId>`, through a `ledger.adjustment_proposal` of origin
     `RECONCILIATION` carrying its ledger reason code (`RECONCILIATION_TRANSFER`,
     `RECONCILIATION_WRITE_OFF`, `RECONCILIATION_GAIN`), beside the reconciliation reason code and
     the narrative. Four-eyes applies whenever value is at issue or the resolution posts. It is
     refused at the domain and by the `resolution` `CHECK`, and for a posting kind by ledger `V010`
     beneath (ADR-0071). Every exit a person decides carries value, so none is single-person;
     the one exit no person decides is evidence.
   - **A release is taken under the item's lock.** The item row is locked `FOR UPDATE`, the
     release is conditional, and `CHECK (released_minor <= amount_minor)` refuses a second release
     of the same value (`409`). Every release bumps the owning break's `residual_version`, so a
     proposal frozen against the old remainder is refused at approval
     (`409 reconciliation.ResolutionStale`).
   - **A partial release**, a late allocation of part of the parked value, leaves the item
     `PARTIALLY_RELEASED` and its break open over the remainder. When the remainder reaches zero
     the item is `RELEASED` and its owning break resolves in the same transaction. No break
     outlives its value, and no value outlives its break.
   - **An offset closes two breaks with one resolution.** `OFFSET_SUSPENSE` is proposed on one
     item's break and names the other item (`offset_item_id`). Approval locks both breaks, sorted
     by id, then the resolution, then both items, sorted by id. It releases both items and resolves
     both breaks by the one approved resolution, each break's history naming it. The offset is
     refused (`reconciliation.ResolutionTargetRefused`) while the other break has a live proposal
     of its own. An equal amount is not evidence that two items are the same money, so an
     uncorrelated offset is always four-eyes. The automatic offset is the `CORRECTION`
     cardinality's, which a counterparty reference correlates (ADR-0068).
   - **A DEBIT item has no counterparty template.** It leaves by evidence (the counterparty's own
     correction, or a late internal completion the rematch can allocate), by `OFFSET_SUSPENSE`, or
     by `WRITE_OFF`. No Phase 8 resolution debits a customer's wallet or a merchant's payable:
     `TRANSFER_TO_ACCOUNT` credits its target. Recovering value from a customer or a merchant is a
     collection, a new operation with its own owner (the receivable and merchant-debt row Phase 7
     recorded for Phase 13), and never a reconciliation adjustment.
   - **An unmatched confirmation's item is released only by a resolution.** Its clearing side is
     explained by evidence: the scheme's cycle report allocates to its `UNMATCHED_CONFIRMATION`
     expectation on `INSTANT_CLEARING` (ADR-0067), the line's scheme reference resolved through
     Phase 7's `payments.scheme_execution_claim` to exactly that parking. But no report says whose
     money it is, so only a person can attribute it (`P8-TSK-020`). What the person starts from is
     the parking's stored cause *(the transition's repairs, payments `V023`)*:
     - `UNATTRIBUTED`: the statement named nothing the platform made. Attribution is a
       judgement, never a guess (ADR-0062 §5), and after the minimum age the gain is the exit that
       needs no owner (point 4).
     - `ATTEMPT_CONCLUDED` or `AMOUNT_MISMATCH`: the parking is attributed. It names the attempt
       (`attempt_id`), which the item reaches through its stored origin, so its natural exit is a
       four-eyes `TRANSFER_TO_ACCOUNT` crediting that attempt's counterparty. A return to the payer
       is return-to-sender, deferred (`PHASE_8_PLAN.md` §17). A gain taken instead records in its
       narrative why the named owner was not credited (point 4).

4. **Every item has an exit: its counterparty's, or one that needs no counterparty.**
   - **ADR-0069's per-type table is the one authority on which kinds a type admits**, and this
     point states only what that table guarantees. Every type that owns suspense admits the exit
     per side that needs no counterparty: a CREDIT item may be recognised as a gain once past its
     minimum age, and a DEBIT item may be written off at any age, both four-eyes. Three types
     admit no gain, because their value is not the platform's to take: `REVERSAL_MISMATCH` and
     `REFUND_MISMATCH` (it belongs to a merchant or a customer) and `CURRENCY_MISMATCH` (a currency
     break is never income). Their CREDIT items leave by the counterparty's exit — a
     `TRANSFER_TO_ACCOUNT` to the owner the break identifies — or by evidence. Where the owner is
     identifiable under a type that does admit the gain — an attributed unmatched confirmation
     (point 3) — the transfer is the expected exit, and a gain's narrative records why it was not
     taken.
   - **The one residual, recorded.** A CREDIT item of an excluded type whose owner holds no account
     that can take the posting — a merchant closed since, whose close closed its payable's ledger
     account (ADR-0073), or an owner with no account in the item's currency — rests owned, aged
     and alerting until one can. Returning it outside the platform is return-to-sender, deferred
     (`PHASE_8_PLAN.md` §17).

     *(Resolved at the transition, and narrowed by its consistency review, A1. The per-type lists
     had left a CREDIT item under `CURRENCY_MISMATCH` or a `REMITTANCE_DIFFERS` excess, and a DEBIT
     item under `DUPLICATE_EXTERNAL`, with no exit once the counterparty never corrects. This point
     first answered with a blanket rule over every type, which contradicted the per-type lists. The
     review made ADR-0069's table the one authority instead: the two exits joined every
     suspense-owning row there, the gain excluded where the value is a counterparty's or a
     currency break's, and ADR-0069's "only `UNKNOWN_EXTERNAL` and `AMOUNT_MISMATCH`" remark
     aligned.)*
   - **The minimum age** is the rule set's `gain_min_age_days`, seeded at 90 in every source's
     version 1 (O5). It is read from the rule set the owning break pins (`rule_set_id NOT NULL`),
     because a rule change governs only later decisions and never a standing break (ADR-0068). It
     is judged in SQL on the database clock against the item's stored `opened_on`, at proposal and
     again at approval under the lock. Before it, the proposal is refused
     (`reconciliation.GainNotYetEligible`). The gain's reason code comes from `RECOGNISE_GAIN`'s
     allowed subset (ADR-0071), `UNATTRIBUTABLE_AGED` among them.
   - **Why the asymmetry.** Recognising a loss early is prudent; recognising a gain early is not.
     A credit recognised too soon can hide a liability to an owner who has not yet appeared, and
     it is the resolution an operator cleaning a dashboard reaches for first (the delivery plan's
     "auto-resolving breaks to make dashboards look clean"). A loss written off early understates
     the books, and evidence corrects it upward.
   - **Who can shorten the wait.** `gain_min_age_days` changes only through a new rule-set
     version, frozen from `PROPOSED` and activated by a second person holding
     `RECONCILIATION_ADMINISTER`. That permission is held by `RECONCILIATION_CONTROLLER` alone,
     disjoint from `RECONCILIATION_OPERATOR`'s `RECONCILIATION_RESOLVE` (O1). Whoever shortens the
     minimum age cannot approve the gains it would release.
   - **Two accounts, not one.** `RECONCILIATION_GAINS` (`REVENUE`) and `RECONCILIATION_LOSSES`
     (`EXPENSE`) arrive with ledger `V017` and their first posters in `P8-TSK-015`, one seeded row
     per currency with hand-minted UUIDv7 literals below the `2026-09-28T00:00:00Z` ceiling. Both
     are reconciled positions, so no free adjustment reaches them. The gains account is posted only
     by an approved `RECOGNISE_GAIN` and the losses account only by an approved `WRITE_OFF`
     (proven by `P8-TST-002`).
   - **A gain is a recognition, not a release of the obligation.** The platform stops carrying the
     value as owed to an unknown party; it does not decide that no one is owed. A claimant who
     appears afterwards is a new operation. Returning unattributed funds to their sender is a
     payment capability Phase 8 does not build (`PHASE_8_PLAN.md` §17).

5. **Age is measured from `opened_on`, on the database clock, and it is visible twice.**
   - Three gauges carry suspense: `finapp.reconciliation.suspense.open` counts items with a
     remainder, `finapp.reconciliation.suspense.age` is the oldest one's age in seconds, and
     `finapp.reconciliation.suspense.unowned` counts items no open break answers for and must read
     0 (point 7). They carry counts, ages and verdicts only. They are registered eagerly, NaN when
     unreadable and never zero, behind a refresh floor, and aggregated with `max()` across
     instances. Each gauge class joins `NoFloatingPointMoneyRulesTest.EXEMPT_CLASSES`.
   - **The owning break carries the escalation.** Its severity is stored at raise and escalated
     one level for each ageing band crossed (0–2, 3–7, 8–30, over 30 days), and one level when its
     value reaches the rule set's per-currency `high_value_minor` (seeded at 1,000.00 in EUR, GBP
     and USD, O7). Each escalation is an appended `break_event`.
     `finapp.reconciliation.break.age` (tag `severity`) alerts at CRITICAL > 0 h, HIGH > 1 d,
     MEDIUM > 5 d and LOW > 15 d.
   - `suspense.age` is alertable in its own right, its rule set with the dashboard row
     (`P8-TSK-024`), and `suspense.unowned` alerts on anything above 0. Suspense that ages is
     therefore paged twice: by its own age and by its break's severity.

6. **The amounts are an audited operator report, never a metric** (ADR-0072; ADR-0018 §2;
   amounts are `RESTRICTED-FINANCIAL`).
   - `GET /v1/operator/reports/reconciliation/suspense`, under `RECONCILIATION_INVESTIGATE`, is
     bounded at 100 rows with a `truncated` flag and audited as `reconciliation.ReportRead`, naming
     the report and period only (the `payments.ChargebackRatioRead` precedent).
   - Per currency it shows three things:
     - the ledger balance of `SUSPENSE_UNMATCHED`, derived through `BalanceDerivation` and folded
       with `Money`, never a SQL `SUM`;
     - the CREDIT items and the DEBIT items remaining, gross, never netted;
     - the owning breaks, by type, severity and age.
   - It is gross because a net hides two open problems behind one small number. That is
     `INV-ACC-05`'s "never netted away or omitted", applied before Phase 14's close needs it, and
     Phase 14 discloses from these rows.
   - The delivery plan's "suspense account balance" (§10) is answered by this report, and the age
     by point 5's gauges. `INV-REC-05`'s Verify line ("suspense age and balance metrics with
     alerting") reads the same way: age by metric, balance by the audited report. The catalogue
     states it with provenance.

7. **Three proofs, report-only.** They take the `TrialBalance` shape: lock-free, "the scrape is
   the schedule", report and never repair. They are computed in `app` in one `REPEATABLE READ`
   transaction on one connection, composing the ledger's `BalanceDerivation` and line reads with
   reconciliation's and settlement's read APIs, folded with `Money` (`P8-TSK-010`, `P8-TSK-007`).
   - **The suspense proof**, per currency:
     `CR−DR(SUSPENSE_UNMATCHED, c) = Σ CREDIT remainders − Σ DEBIT remainders + Σ Phase 7
     unmatched confirmations not yet adopted`. The last is a named term: the parkings no suspense
     item's `origin_ref` names. Without it the proof would fail on any database holding a
     Phase 7 parking until `P8-TSK-020` adopts it; with it the proof is exact before and after
     adoption, and the term reads 0 once `P8-TSK-020` has adopted the parkings (`P8-TSK-010`).
   - **Ownership:** every item with a remainder names a break that exists and is not `RESOLVED`
     (`finapp.reconciliation.suspense.unowned`, which must read 0). `break_id NOT NULL` makes the
     first half structural. The second half catches a break closed over value it still owns: the
     break's own rule refuses that (ADR-0069), and this gauge shows it if a defect lets one
     through.
   - **Completeness:** every `SUSPENSE_UNMATCHED` journal line is known — a suspense item owns it,
     or its entry is a batch's recognition, a park's, a resolution's or a repudiation's
     (ADR-0067 §9). Any other line is counted by `finapp.reconciliation.line.unattributed` (tag
     `purpose`), which must read 0 — under `SUSPENSE_UNMATCHED`, once `P8-TSK-020` has adopted
     Phase 7's parkings.

8. **Phase 7's parking is adopted, and its gauges' meaning is corrected** (`P8-TSK-020`).
   - `UnmatchedConfirmations` opens the CREDIT item and its `UNKNOWN_EXTERNAL` break (cause
     `PARKED_ON_RECEIPT`) through the port, inside the delivery's transaction, for each of the
     parking's three causes. The item reaches the parking's stored columns (payments `V023`) — the
     cause, the named reference, the cycle and, when attributed, the attempt — through its origin,
     and the parking's raw statement through `provider_evidence`'s `unmatched_confirmation_id`.
     The `UNMATCHED_CONFIRMATION` expectation for its clearing line is `P8-TSK-005`'s (ADR-0067).
   - Rows parked before Phase 8 are adopted by an idempotent, keyed, leaderless backfill that
     converges on `origin_ref` under ten racing instances. Each keeps the posting date of the entry
     that parked it as its `opened_on`, so an item parked in Phase 7 is exactly as old as it is.
   - **A parking `V023`'s backfill left unclaimed is not unattributed value.** Where a credit or a
     completion and a parking had already recorded one scheme execution before `V023`, its
     backfill gave the claim to the credit or completion and left the parking without one — the
     same execution recorded twice, standing visible in both tables for Phase 8. Adopting such a
     row as an ordinary unattributed credit would let the value be transferred or recognised a
     second time. Its handling is `P8-TSK-020`'s recorded design input (Follow-up).
   - `finapp.payments.unmatched.active` and `finapp.payments.unmatched.age` keep counting every row
     ever parked, which is payments' own table, measured truthfully. Their descriptions in
     `PayInMetrics` and in `PHASE_7_PLAN.md` §15 are corrected to "parked, ever", with provenance.
     The names stay, so the Phase 7 plan's rows still parse (`PlannedMetersExistTest`). The
     alertable signal becomes `finapp.reconciliation.suspense.*`. Payments does not read
     reconciliation's state to repair its own gauge; that would be a cross-module read for a meter.

9. **Attribution is visible where it lands.**
   - A `TRANSFER_TO_ACCOUNT` target is a `CUSTOMER_WALLET` or a `MERCHANT_PAYABLE`, `ACTIVE` and in
     the item's currency, read `FOR SHARE` before any posting (the chargeback precedent). A target
     that ledger `V007` refuses fails the approval with a `409` and leaves the resolution
     `PROPOSED` — among them a closed merchant's payable, whose ledger account the close now closes
     in its own transaction (the transition's repairs).
   - The merchant's payable breakdown gains `reconciliationAttributed`: every payable line in a
     `RECONCILIATION`-origin `ADJUSTMENT` entry (ledger `V015`'s `origin`), whatever it faces —
     ADR-0073 §6's origin rule. Without it the line falls into `other` (`MerchantPayable`) or,
     when a transfer of an `OUTBOUND` clearing remainder faces a clearing position, reads as a
     capture or a payout returned. The customer statement labels the same lines of a wallet
     `RECONCILIATION_ATTRIBUTION`. `INV-MER-02` gains "plus or minus reconciliation attributions".
   - **The labels are the attribution's, built with its first poster.** ADR-0073 §6 fixes their
     classification rule beside its own `payoutsReturned` term. `P8-TSK-015`, which ships the first
     `TRANSFER_TO_ACCOUNT`, builds `reconciliationAttributed`, the customer statement label and
     `INV-MER-02`'s attribution clause. `P8-TSK-019`, a deferral candidate (O6), keeps only
     `payoutsReturned`, so cutting it takes no label with it. *(The Phase 7 → 8 transition's
     consistency review, A12, A13 and C10. This point had defined the term as a line facing
     `SUSPENSE_UNMATCHED`, which misreads a transfer that faces a clearing position; it had also
     claimed the labels for this ADR, while ADR-0071 credited ADR-0073, and left them in
     `P8-TSK-019` with only an input recorded for `P8-TSK-015`.)*

10. **Repudiation reaches suspense without releasing anything twice** (`REPUDIATE_BATCH`,
    `P8-TSK-023`, a deferral candidate; ADR-0065 §10).
    - A `RECON_PARK` item of the batch that still holds value is released by an unpark under
      `recon-suspense:<parkId>`.
    - A `BANK_UNATTRIBUTED` item is released by the reversal of the recognition entry itself
      (`ReversalService`, scope `ledger.reverse`, key `settlement-batch:<batchId>`), which already
      carries its suspense line. No unpark is posted as well, which would release the value twice.
    - **An item a posting resolution already released cannot be released again.** Its value went
      to a wallet, a payable or the gains account on evidence now repudiated. For a
      `BANK_UNATTRIBUTED` item the recognition's reversal still carries the suspense line, because
      `ReversalService` reverses the entry whole. That line opens a new item of the opposite side,
      origin `REPUDIATION` (point 2's fourth opener: its `origin_ref` the released item's id, its
      `opened_on` the reversal entry's posting date), owned by a new `PROCESSING_ERROR` break
      raised in the approval transaction — the type accepted evidence proven fabricated or
      mis-normalised already takes — and a person decides where the loss falls. A `RECON_PARK`
      item already released posts no unpark; what its position then owes is `P8-TSK-023`'s
      question, with the position proof as its test, and if that task posts a line into suspense
      for it, the same rule binds the line. `INV-REC-09` binds repudiation like every other
      poster. *(`P8-TSK-023`, 2026-10-01, decided the question: the park's exact inverse is posted
      for the released part - the position restored as if the value were still parked - and its
      suspense line opens a `REPUDIATION` item on the opposite side, owned by a new
      `PROCESSING_ERROR` break; a value released by a late allocation's unpark or a correction's
      offset went back to the position already and needs no answer.)* *(Resolved at the
      transition: the design's
      repudiation steps assumed every parked item was still parked. The new item was named point
      2's fourth opener by the transition's re-check, R3.)*

11. **Ten instances.** Every suspense contention names its PostgreSQL arbiter
    (`DISTRIBUTED_EXECUTION.md` §3):

    | Contention | PostgreSQL arbiter | Loser |
    |---|---|---|
    | Ten parks of one item (retry, takeover, rematch vs run) | the external item's conditional transition; `UNIQUE (external_item_id)` on `suspense_item`; the park row's key | No second entry and no second item |
    | A release taken twice (two approvers; evidence vs approval) | the break row first, then the item `FOR UPDATE`; `CHECK (released_minor <= amount_minor)`; the resolution's conditional `PROPOSED → APPROVED` | `409`, or it sees `RESOLVED` or `ResolutionStale` |
    | The backfill racing live parkings | `UNIQUE (origin_ref)` | Converges |
    | An offset racing either item's own exit | both breaks locked, sorted by id, before the resolution | The later one sees a released item and is stale |
    | Gain eligibility judged by instances with skewed clocks | judged in SQL, on the database clock, against the stored `opened_on`; a window of days makes VM drift noise | — |

    - **The lock order** is the §3 row: (1) advisory namespace 4 for the source, when the
      transaction allocates, parks or unparks; (2) break rows, then the resolution row;
      (3) expectation rows, then external item rows, then suspense item rows, each sorted by id;
      (4) the merchant payout row, taken by the return worker only; (5) the attribution target
      `FOR SHARE`; (6) inside `approveOwned`, the ledger proposal row, then ledger projection rows
      sorted by account id. **Postings are last.** A transaction posting several entries over
      shared rows — a chunk parking on two positions, a repudiation's reversal and its unparks —
      pre-locks the union of the platform's rows it will touch in the projection's own order
      before its first posting (`PostingService.lockBalancesInOrder`, `DISTRIBUTED_EXECUTION.md`
      §3's multi-entry lock-order rule, which the transition's repairs made for dispute postings,
      ADR-0061 §5), and takes any runtime counterparty's `ACCOUNT` row — `FOR SHARE` where a share
      lock suffices — before any projection row. *(Aligned to the row by the Phase 7 → 8
      transition's consistency review, B9; the multi-entry rule is the repairs'.)* Suspense
      postings touch only the platform's own seeded accounts (`SUSPENSE_UNMATCHED`, the clearings,
      `CASH_AT_BANK`, `RECONCILIATION_GAINS`, `RECONCILIATION_LOSSES`), except a transfer's target,
      whose account row is share-locked at (5), before any projection row. The order rests on that
      pre-lock and that share lock, never on seed order: seed order is load-bearing only for
      `SETTLEMENT_CLEARING` (`V003`), and "seeded accounts sort before runtime ones" was found wrong
      on a longer-lived database (`DISTRIBUTED_EXECUTION.md` §3).
    - The gauges decide nothing. They read the shared database behind a refresh floor and
      aggregate with `max()`.
    - **Would this remain correct if ten instances ran it concurrently?** Yes. The verdict rests on
      counted ten-way tests, not on construction: ten parks of one item give one entry and one item
      (`P8-TSK-010`); ten approvers give one entry (`P8-TSK-015`); the adoption runs ten ways
      (`P8-TSK-020`); and the storm's parking lock-bypass probe (`P8-TST-001`) is caught.

12. **Owner decisions this ADR carries.** The owner's open decisions were settled at the
    transition on the design's recommendations, each recorded as one the owner may revisit. Six
    of them bear on suspense:
    - **O5, the gain minimum age:** 90 days, the gain itself four-eyes (point 4). *(Settled
      2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the owner.)*
    - **O1, roles:** two pairwise-disjoint roles, so the controller who can shorten
      `gain_min_age_days` cannot approve a gain (point 4; ADR-0071). *(Settled 2026-09-28 at the
      Phase 7 → 8 transition, on the recommendation; revisitable by the owner.)*
    - **O7, the high-value severity threshold:** `high_value_minor` 1,000.00 for EUR, GBP and USD
      in rule set v1, escalating a suspense-owning break one level (point 5; ADR-0069). *(Settled
      2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the owner.)*
    - **O4, a non-zero opening cash balance:** the simulated bank opens at zero, and suspense is
      never the counter-account for an opening difference (point 2). *(Settled 2026-09-28 at the
      Phase 7 → 8 transition, on the recommendation; revisitable by the owner.)*
    - **O2, payout returns:** automated, with the four-eyes fallback. A return the worker cannot
      apply parks under `REVERSAL_MISMATCH` (cause `RETURN_NOT_APPLICABLE`) and leaves suspense by
      a four-eyes `TRANSFER_TO_ACCOUNT` to the payable (ADR-0073). *(Settled 2026-09-28 at the
      Phase 7 → 8 transition, on the recommendation; revisitable by the owner.)*
    - **O6, the cut order if scope must shrink:** `P8-TSK-021`, then `P8-TSK-019`, then
      `P8-TSK-023`. Cutting `P8-TSK-019` routes every payout return through suspense and a
      four-eyes transfer, and takes none of point 9's labels with it: they are `P8-TSK-015`'s.
      Cutting `P8-TSK-023` removes point 10. *(Settled 2026-09-28 at the Phase 7 → 8 transition, on the recommendation;
      revisitable by the owner.)*

    O5 and O7 are rule-set content, so revisiting either is a new version under four-eyes, not a
    migration.

## Alternatives Considered

### Never recognise an unclaimed credit
Pros: the platform never books as revenue value that may belong to someone, and nobody has to
judge when a claim has become unlikely.
Cons: it makes suspense a permanent resting place for every credit no one claims, which
`INV-REC-05` forbids in terms. The age gauge rises for ever, and an alert that can never clear is
ignored. It only postpones the recognition to Phase 14's close, where it arrives with no owner and
no age rule.

### Recognise a gain at any age, under four-eyes
Pros: an operator who is certain need not wait, and suspense clears fastest.
Cons: the resolution that makes a dashboard clean is the one most likely to be approved in a
hurry. A late internal record or a counterparty's correction arriving after the gain finds its item
terminal and has no way back. A pinned minimum age costs a wait and buys the window in which
evidence usually arrives.

### One reconciliation account for gains and losses
Pros: one account to reason about, and symmetric resolutions.
Cons: it nets gains against losses inside one balance, so a large loss beside a large gain reads
as a small number. The two have different account types (`REVENUE`, `EXPENSE`), and Phase 14 must
present them apart. The objection was raised at synthesis for exactly this reason.

### A suspense account per counterparty or per source
Pros: each counterparty's unexplained value is a ledger balance of its own.
Cons: `V002` allows one operational account per purpose and currency, so it needs a new owner kind
and a chart change. It doubles lines for information the items already carry (`origin`, the owning
break's `source_id`). And it invites reading a counterparty's suspense balance as that
counterparty's debt, which unexplained value is not. This is ADR-0065's argument against a
per-counterparty in-transit account, applied here.

### Park every unexplained item at once
Pros: nothing waits, and value leaves the position the moment it is unexplained.
Cons: ordinary orderings — a report before its webhook, a bank credit before its report — would
park and unpark within days, doubling postings and breaks for no information. Definitive classes
park at once; only the classes late internal evidence can change wait, and never past their grace
(ADR-0068).

### Enforce ownership by a trigger on every suspense journal line
Pros: ownership for every writer at the database rank, raw SQL included.
Cons: it is the ledger open-item trigger ADR-0064 and ADR-0067 reject, narrowed to one account. It changes
the posting path every flow shares, and pairing each line with its break under per-item advisory
locks brings the lock-table exhaustion that design itself found. Instead, the binding trigger
closes the one other door (free adjustments), the domain opens every item with its break, and the
completeness and ownership verifiers count anything else.

### Publish the suspense balance as a metric
Pros: the delivery plan asks for it, and one dashboard shows everything.
Cons: ADR-0018 forbids amounts in metrics, `RESTRICTED-FINANCIAL` data never reaches them, and a
metric is neither access-controlled nor audited. ADR-0072 gives the amount to an audited report and
leaves counts and ages to the meters.

### Plug a non-zero opening cash balance into suspense
Pros: the cash proof balances on the first day.
Cons: suspense would hold value no break could ever evidence away, owned by nothing that can be
resolved. The only honest counter-account is equity, which is Phase 14's (O4).

### Repair the payments gauges in place
Pros: one suspense signal, under its Phase 7 name.
Cons: renaming breaks the Phase 7 plan's parsed rows. Re-querying them to exclude released items
makes `payments` read reconciliation's state for a meter. Once described truthfully, they measure
payments' own table correctly.

## Consequences

Positive:
- Every unit of suspense value has an owner, an age and an exit. ADR-0040's condition is met, and
  the Phase 3 → 8 seam is closed.
- Suspense cannot become the dumping ground the delivery plan warns of. Nothing enters without a
  break, nothing leaves without evidence or two people, and every item has an exit: its
  counterparty's, or one that needs none (point 4, whose one residual is recorded).
- The suspense balance is explained by its items at every commit (the suspense proof), and its
  amounts are shown only to those entitled, with every read audited.
- Phase 7's parked confirmations gain the releaser they lacked, and keep their age.
- Phase 14 inherits a gross, aged and owned suspense for `INV-ACC-05`'s disclosure.

Negative:
- A credit an operator is sure is unattributable still waits ninety days to become a gain. A
  recovery of an amount already written off waits with it: automatic reversal of a write-off on
  late evidence is deferred, so the recovery rests in suspense, owned and aged, until it is
  transferred or recognised.
- A claimant who appears after a gain has no path in Phase 8.
- A remittance paid without its reference parks as an unattributed bank credit, while its
  remittance expectation ages into `MISSING_EXTERNAL`. No Phase 8 kind attributes a parked bank
  line to a counterparty's clearing position: `MANUAL_MATCH` serves `AMBIGUOUS_MATCH` only, and
  `TRANSFER_TO_ACCOUNT` credits wallets and payables. The honest path today is a `WRITE_OFF` of
  the remittance and, after the minimum age, a `RECOGNISE_GAIN` of the credit, both four-eyes and
  grossing up losses and gains. The alternative is an `OFFSET_SUSPENSE` when the bank itself
  corrects with a debit. Recorded for `P8-TSK-016`'s design (Follow-up).
- Every value-bearing exit needs two people, so the operator burden is real. Grace windows,
  `EVIDENCED` closure and template-derived lines are the mitigation.
- Two suspense readings coexist, payments' "parked, ever" and reconciliation's open items, and the
  documentation must tell them apart.

Operational impact: the three suspense gauges and the break-age alerts; the audited suspense
report; the dashboard row's suspense panels; the ownership and completeness gauges alert on
anything above zero.
Security impact: every exit needs `RECONCILIATION_RESOLVE` under four-eyes, refused at up to three
ranks. The minimum age is `RECONCILIATION_ADMINISTER`'s, held by a disjoint role. Amounts reach only
the audited report: never a metric, an event, a log, a trace or an audit body, and change summaries
carry identifiers only.
Financial impact: `SUSPENSE_UNMATCHED` gains releasers and joins `reconciledPositions()`.
`RECONCILIATION_GAINS` and `RECONCILIATION_LOSSES` are added per currency (ledger `V017`).
`INV-REC-05` is amended, `INV-REC-09` is new and `INV-MER-02` is amended. Merchant payables and
customer statements name attributions.

## Invariants / Constraints

`INV-REC-05` (amended: Enforce names `RECOGNISE_GAIN` after the pinned minimum age, four-eyes;
Verify reads the balance through the audited report, point 6), `INV-REC-09` (new), `INV-REC-02` (a
parked item is a break, never a silent drop), `INV-REC-03`, `INV-REC-06` (value nothing explains
leaves a position only by parking with a break), `INV-REC-08`, `INV-BAL-03`, `INV-LED-01`,
`INV-MON-04`, `INV-SET-03` (a late line unparks; nothing is refused as stale), `INV-REV-01`,
`INV-REV-04`, `INV-AUD-02`, `INV-AUD-04`, `INV-MER-02` (amended), and `INV-ACC-05` (Phase 14's
disclosure reads point 6's report).

Constraints this decision must preserve:
- Postings stay the last statement of every transaction that moves suspense value
  (`DISTRIBUTED_EXECUTION.md` §3). A row that records the id of an entry its own transaction posts
  last — a park's `journal_entry_id`, an item's `entry_id` — is either written after that posting,
  as an insert no other transaction waits on once the arbiter has decided, or reaches the entry
  through its batch's `journal_entry_id`. Which one is `P8-TSK-010`'s and `P8-TSK-016`'s design;
  neither moves a posting earlier.
- Every date on a suspense row or posting comes from stored data, and windows are judged in SQL on
  the database clock. Fixtures stamp from the test clock.
- Nothing about suspense is deleted or edited: items, releases and parks are append-only apart from
  an item's release columns, and `finapp_app` holds no `DELETE` in the `reconciliation` schema.
- Suspense postings touch seeded accounts only, except a transfer's target, whose account row is
  share-locked before any projection row; their deadlock-freedom is the multi-entry pre-lock's,
  never seed order's.
- No amount enters a metric, an event, a log, a trace or an audit body.

## Follow-up

- Until Phase 8's first task lands, nothing in this ADR is implemented; every statement is the
  decided design, corrected by the tasks that build it.
- `P8-TSK-016` — **implemented** (2026-09-30): §2's `BANK_UNATTRIBUTED` opener. The statement's
  acceptance raises each unattributed line's `UNKNOWN_EXTERNAL(BANK_LINE_UNATTRIBUTED)` break on
  its item (CRITICAL for a debit by the severity seat's direction rule), moves the item
  `PENDING → PARKED` with its whole value (`Suspense.bornParked`), and — after the recognition
  posts its `SUSPENSE_UNMATCHED` lines, a CREDIT line of the unattributed credits and a DEBIT line
  of the unattributed debits, never netted — opens one owned item per line carrying the entry id
  whole (`Suspense.openUnattributed`; the constraint's FIRST option: written after the posting,
  never moving it earlier), side from the direction, `opened_on` the batch's `accepted_on`, no
  park and no position. The item leaves only through a resolution's release — `unpark` refuses it,
  as before. **The unattributed-remittance question, decided (no amendment):** a parked bank
  credit that was really a counterparty's remittance is disposed by the honest path this ADR's
  negative consequence names — a four-eyes `WRITE_OFF` of the aged remittance and a later
  `RECOGNISE_GAIN` of the credit after the minimum age, or `OFFSET_SUSPENSE` when the bank reverses
  with an equal debit; widening `MANUAL_MATCH` to unattributed bank items stays an amendment the
  owner may revisit (ADR-0071).
- `P8-TSK-006` (the binding that closes suspense to free adjustments, and the reason codes),
  `P8-TSK-010` (suspense items, releases and parks; park and unpark; the suspense proof and
  gauges), `P8-TSK-011` (definitive parks and errored items in the chunk), `P8-TSK-012` (the
  correction offset), `P8-TSK-013` (the grace leg's parks and the rematch's unparks), `P8-TSK-015`
  (`RECONCILIATION_GAINS` and `RECONCILIATION_LOSSES`, the exits, the minimum-age refusal, and
  point 9's `reconciliationAttributed`, customer statement label and `INV-MER-02` clause, with the
  first `TRANSFER_TO_ACCOUNT`), `P8-TSK-016` (bank recognition's unattributed lines), `P8-TSK-019`
  (the payout return's fallback through suspense), `P8-TSK-020` (the unmatched confirmation
  adopted; the payments gauges' descriptions), `P8-TSK-023` (repudiation's reach into suspense and
  its `REPUDIATION` origin, under reconciliation `V013`), `P8-TSK-024` (the suspense
  report, the dashboard row and its alerts), `P8-TST-001` (the suspense proof and ownership in every
  round of the storm), `P8-TST-002` (every exit crossed with every owning type; the gains and losses
  accounts posted only by approvals), `P8-DOC-001`.
- **Recorded for task designs, not decided here:**
  - *(point 9's attribution labels, recorded here for `P8-TSK-015`, were decided by the transition's
    consistency review, A12: they are `P8-TSK-015`'s, above;)*
  - *(decided by `P8-TSK-020`, below: the unclaimed parking is owned as a `DUPLICATE_EXTERNAL`
    under `EXECUTION_ALREADY_EXPLAINED`, which admits no transfer;)* for `P8-TSK-020`: a parking
    payments `V023`'s backfill left unclaimed (point 8) — one scheme
    execution a credit or completion already explains — must be adopted so that its value is not
    attributed a second time. Its entry still put a real line into suspense and a second debit on
    the clearing, so it is resolved by the kinds the table admits, never deleted. The candidates
    are a four-eyes `WRITE_OFF` of the doubled clearing remainder beside a `RECOGNISE_GAIN` of the
    item after its minimum age, or evidence if the scheme corrects, with the position and suspense
    proofs as the test;
  - for `P8-TSK-016`: attributing a parked bank line to a counterparty's remittance (a remittance
    paid without its reference). The candidates are a `MANUAL_MATCH` widened to unattributed bank
    items, carried by ADR-0071, or accepting the loss-and-gain pair as the honest record;
  - for `P8-TSK-023`: point 10's new item for a value a resolution had already released, with the
    suspense and position proofs as its test;
  - for `P8-TSK-010` and `P8-TSK-016`: the entry-id ordering in the constraints above.
- Deferred, each recorded in `PHASE_8_PLAN.md` §17: returning unattributed funds to their sender
  (a new payment capability); automatic reversal of a write-off on late evidence; value-banded
  approver escalation (six-eyes); a de-minimis policy, with the proposal row as its seam
  (`AdjustmentService`); collection from customers and merchants (Phase 13); disclosure at close
  (Phase 14, `INV-ACC-05`).
- `P8-TSK-010` — **implemented** (2026-09-29): points 1, 2 (the `RECON_PARK` opener), 5 and 7 as decided, and the entry-id ordering question above ANSWERED — the posting stays the last CONTENDED write, and the `park` and `suspense_item` rows are inserted AFTER it carrying `journal_entry_id`/`entry_id` whole (`NOT NULL`), because the arbiters (the item's conditional transition, the break locks) were taken before the posting and the late inserts are the transaction's own rows (the `P8-TSK-009` D3 shape; the `merchant.payout_return` precedent). A `RECON_PARK` item also carries `park_id` and `position_account_id` (`NOT NULL` exactly for that origin, by `CHECK`): the park names its items through them, and the unpark's exact inverse — side, position and the original park's value date — reads frozen facts instead of re-deriving. `origin_ref` is uniformly the origin's own row id. Ownership holds at three ranks: `break_id NOT NULL`, the owner-type trigger (a never-parking or RESOLVED owner refused for every writer, `V004`), and the domain's pre-park verification; the suspense proof, the ownership reading and the three gauges are `PositionProof`'s/`ReconciliationMetrics`' with the named Phase 7 term paged through payments' own read — proven over the composed wiring with the planted defect observed in its own uncommitted transaction (`ReconciliationSuspenseDatabaseTest`). The park entries and every item's entry join the completeness verifier's known classes (point 7). The unpark alone restores the position and releases the item; the item's return to the fold is its caller's allocation in the same transaction (`P8-TSK-013`'s rematch), which the suites simulate — recorded.
- `P8-TSK-020` — **implemented** (2026-09-30): point 2's `UNMATCHED_CONFIRMATION` row and point 8. the owner of a parking's value is born beside it: `UnmatchedConfirmations.park` calls the port's `parked` after its expectation, the claim winner only, and `app`'s recorder opens, through reconciliation's `ParkedConfirmations`, the CREDIT suspense item (its value, side and `opened_on` read off the parking entry's `SUSPENSE_UNMATCHED` line) and the `UNKNOWN_EXTERNAL` break (`PARKED_ON_RECEIPT`) standing on that item — in the delivery's transaction, owned from birth (`INV-REC-09`). The owner's subject is the item it owns, which neither immediate key let be born, so reconciliation `V011` made `break_suspense_item_fk` deferrable, initially immediate, and only this opener defers it; a concurrent opener that loses `UNIQUE (origin_ref)` rolls back to a savepoint, discarding its break. The opening-position backfill adopts every Phase 7 parking the same way, dated from its own entry; the recorded design input is decided — a parking whose execution a credit already explains is owned as a `DUPLICATE_EXTERNAL` under `EXECUTION_ALREADY_EXPLAINED`, which admits the gain, the offset and a DEBIT write-off but never a transfer. A `CREDIT_IN` allocation of the parking's expectation leaves the item `OPEN` (`INV-REC-05`); the payments gauges read "parked, ever", with provenance. Two finds before the suite first ran, both fixed: the item's side had been read through the clearing mapping (a CREDIT on a position is OUTBOUND), which made every parking a DEBIT item; and an `AMOUNT_MISMATCH` parking — and a stale-read `ATTEMPT_CONCLUDED` one — filed its raw statement on the attempt, never the parking, contrary to payments `V023`'s own comment: the applier now reports the parking it made (`PaymentOutcomes.Applied.parking`) and both producers, the instant door and the inquiry sweep, address the bytes to it, so every parking's owner traces to its statement.
- The Phase 8 review reads this ADR against the code before accepting it.
