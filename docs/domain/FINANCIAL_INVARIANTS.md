# Financial Invariants

This is the **invariant catalog**: the complete set of properties that must hold across the
platform, each with a stable identifier, an enforcement mechanism and a verification method.

An invariant in this catalog is not a guideline. It is a property the system must never
violate, and every violation is a defect regardless of business justification. Weakening an
invariant requires a superseding ADR, not a code review comment.

## How to use this catalog

- **Phase entry gate** requirement 4 requires naming the `INV-*` IDs a phase must protect.
- **Phase exit gate** criterion 3 requires each in-scope invariant to have a test that
  *fails when the invariant is deliberately broken*.
- **Definition of Done** §1.12 requires that demonstration, not an assertion of it.

## Field meanings

- **Statement** — the property, stated as an absolute.
- **Why** — the financial or operational consequence of violation.
- **Enforce** — where the property is mechanically prevented from being violated.
  Application-level enforcement alone is marked as such and is weaker.
- **Verify** — how we detect a violation that reaches production.
- **Phase** — the phase that first introduces enforcement.

Enforcement strength, strongest first: `DB-CONSTRAINT` > `DB-PRIVILEGE` > `STATIC` (build
fails) > `DOMAIN` (application rejects) > `PROCESS` (human control).

---

# Money and Precision — `INV-MON`

### INV-MON-01 — No binary floating point in monetary values
**Statement:** No monetary value is represented, computed, transported or persisted using a
binary floating-point type at any point on its path.
**Why:** Binary floating point cannot represent decimal fractions exactly; errors accumulate
and money is silently created or destroyed.
**Enforce:** `STATIC` — architecture test forbidding `float`/`double` in monetary code paths.
**Verify:** Build failure; periodic code audit.
**Phase:** 0

### INV-MON-02 — Currency is always explicit
**Statement:** Every monetary value carries an explicit currency. There is no default,
implied, ambient or system currency, and no monetary type may be constructed without one.
**Why:** An implied currency produces cross-currency arithmetic that appears correct.
**Enforce:** `DOMAIN` (no no-currency constructor) + `DB-CONSTRAINT` (`currency NOT NULL`).
**Verify:** Type system; schema constraint.
**Phase:** 0

### INV-MON-03 — Rounding is explicit and named
**Statement:** Every rounding operation specifies its rounding mode explicitly. No rounding
occurs implicitly through type coercion, division or persistence.
**Why:** Implicit rounding is the most common source of unexplainable cent-level drift.
**Enforce:** `DOMAIN` — rounding mode is a required parameter.
**Verify:** Unit tests across 0-, 2- and 3-minor-unit currencies.
**Phase:** 0

### INV-MON-04 — Arithmetic across different currencies is rejected
**Statement:** Adding, subtracting or comparing monetary values of different currencies is
an error. It is never resolved by conversion, coercion or ignoring the currency.
**Why:** Silent cross-currency arithmetic corrupts balances irrecoverably.
**Enforce:** `DOMAIN`.
**Verify:** Unit test; the operation throws rather than converting.
**Phase:** 0

### INV-MON-05 — Precision semantics are preserved in persistence
**Statement:** A persisted monetary value round-trips to exactly the value written,
including its scale, for every supported currency.
**Why:** A currency's minor-unit definition may change; historical amounts must remain
interpretable as originally written.
**Enforce:** `DB-CONSTRAINT` — integer minor units plus stored scale (ADR-0003).
**Verify:** Round-trip integration tests.
**Phase:** 0

### INV-MON-06 — Overflow is rejected, never wrapped
**Statement:** Monetary arithmetic that would exceed the representable range fails
explicitly.
**Why:** Silent wraparound converts a large credit into a large debit.
**Enforce:** `DOMAIN`.
**Verify:** Unit tests at boundary values.
**Phase:** 0

---

# Ledger and Double Entry — `INV-LED`

### INV-LED-01 — Every journal entry balances
**Statement:** For every journal entry, total debits equal total credits, **per currency**.
**Why:** The foundational property of double-entry accounting. Without it, no report,
balance or reconciliation is trustworthy.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (entry-level balance check).
**Verify:** Continuous trial-balance job asserting zero per currency, with alerting.
**Phase:** 3

### INV-LED-02 — A journal entry has at least two lines
**Statement:** No single-line journal entry exists.
**Why:** A single-sided posting is by definition unbalanced value movement.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT`.
**Verify:** Domain test; constraint test.
**Phase:** 3

### INV-LED-03 — Posted entries and lines are immutable
**Statement:** Once committed, a journal entry and its lines are never updated or deleted.
**Why:** Financial history is evidence. Mutable history is not auditable.
**Enforce:** `DB-PRIVILEGE` — application role holds `INSERT`/`SELECT` only.
**Verify:** Test asserting `UPDATE`/`DELETE` fail with the application role.
**Phase:** 3

### INV-LED-04 — The ledger is the sole writer of postings
**Statement:** No module other than the Ledger writes journal entries or lines. Other
modules request postings through the ledger's command API.
**Why:** Multiple writers means multiple sets of accounting rules and no single authority.
**Enforce:** `STATIC` (boundary test) + `DB-PRIVILEGE` where module-level roles exist.
**Verify:** Architecture test.
**Phase:** 3

### INV-LED-05 — Every posting is attributable
**Statement:** Every journal entry records the actor, the originating economic event, and a
correlation identifier.
**Why:** A posting nobody can explain is a posting nobody can defend.
**Enforce:** `DB-CONSTRAINT` (`NOT NULL`).
**Verify:** Schema; audit sampling.
**Phase:** 3

### INV-LED-06 — Ledger accounts have a declared type and normal balance
**Statement:** Every ledger account declares its account type and normal balance side, and
these never change after postings exist.
**Why:** Reclassifying an account retroactively changes the meaning of historical reports.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT`.
**Verify:** Test attempting reclassification of a posted-to account.
**Phase:** 3

---

# Balance Correctness — `INV-BAL`

### INV-BAL-01 — Balances are derived from postings
**Statement:** Every balance is either the authoritative ledger position derived from
postings, or a documented projection of it. No balance is an independent authority.
**Why:** `CLAUDE.md` rule 5 — money is never created by mutating a balance.
**Enforce:** `DOMAIN` + `STATIC` (no balance-mutation API outside the ledger projector).
**Verify:** Continuous recomputation comparison with alerting.
**Phase:** 3

### INV-BAL-02 — A balance is reproducible from zero
**Statement:** Replaying all postings for an account from zero reproduces its current
balance exactly.
**Why:** This is the operational definition of "explainable balance".
**Enforce:** `DOMAIN`.
**Verify:** Automated recomputation job; test under sustained concurrent posting.
**Phase:** 3

### INV-BAL-03 — Value is neither created nor destroyed
**Statement:** No operation increases or decreases the total value in the system without a
corresponding balanced posting. Rounding residuals are posted to a designated account, never
absorbed or discarded.
**Why:** Absorbed residual is money creation or destruction, at scale.
**Enforce:** `DOMAIN` (allocation with zero residual) + `INV-LED-01`.
**Verify:** Allocation property tests; system-wide trial balance.
**Phase:** 0 (allocation), 3 (posting)

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0074 (`Proposed`), in force when
`P9-TSK-002` and `-009` land: the designated account gains its first production poster — a
conversion's residual, computed from stored amounts and bounded, posts to `ROUNDING_RESIDUAL`
in the computed leg's own currency (`INV-FX-07`), never absorbed into margin, the customer
amount or the position.)*

### INV-BAL-04 — Available balance accounts for holds
**Statement:** Available balance equals ledger balance minus active holds. A hold cannot be
placed that would make available balance negative unless the account explicitly permits it.
**Why:** Authorising against funds already committed produces overdrawn accounts.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` where representable.
**Verify:** Concurrency tests placing simultaneous holds.
**Phase:** 3

*(Lending's wallet debits - `P11-TSK-018`'s repayment and `P11-TSK-019`'s auto-collection - judge the wallet's available
balance (`AvailableBalance.underLock`: settled less standing holds) under the wallet's ledger row lock (L7), re-read after
every collection of a step; a collection takes at most what is available, and nothing where partial collection is not
allowed. `AutoCollectionDatabaseTest#partialCollectionWhenAllowed`, `#allOrNothingWhenNot` (a hold making settled money
unavailable).)*

### INV-BAL-05 — Projections are never authoritative for a financial decision
**Statement:** A financial decision (sufficient funds, credit exposure, settlement
eligibility) is never made from a projection whose staleness is unbounded.
**Why:** `CLAUDE.md` rule 12 — caches and projections are not financial truth.
**Enforce:** `DOMAIN` + `PROCESS` (review).
**Verify:** Design review; projection-lag metric with a hard threshold.
**Phase:** 3

---

# Immutable History — `INV-HIST`

### INV-HIST-01 — Financial history is never edited
**Statement:** No financial record is updated or deleted after it becomes authoritative.
Corrections occur only through reversals, adjustments or compensating entries.
**Why:** `CLAUDE.md` rule 3.
**Enforce:** `DB-PRIVILEGE`.
**Verify:** Privilege test; schema review.
**Phase:** 3

### INV-HIST-02 — External evidence is retained verbatim
**Statement:** Raw external payloads (provider responses, webhooks, settlement files) are
retained unmodified, with a checksum where the source is a file.
**Why:** Reconciliation and dispute resolution require original evidence, not our
interpretation of it.
**Enforce:** `DOMAIN` + `DB-PRIVILEGE` on evidence tables.
**Verify:** Test asserting stored payload matches the received bytes; for a settlement file, the
stored bytes equal the received bytes by checksum, the whole-plaintext SHA-256 verified on every
read.
**Phase:** 2 (screening), 5 (providers), 8 (files)

*(Amended at the Phase 7 → 8 transition, ADR-0066 (`Proposed`), in force when `P8-TSK-002`
lands. **For a refused delivery, `INV-PAY-02` and `INV-RAIL-03` take precedence over this
invariant:** a settlement file whose bytes the door screen finds carrying a card-number or
bank-identifier shape is retained as one `settlement.refused_delivery` metadata row only —
source, SHA-256, length, format version, reason, line number, field name, channel, actor and
correlation, never the value. A refused delivery decides nothing: it posts, allocates and opens
nothing, the counterparty still holds its bytes, and its checksum ties it to any later
re-presentation. Every delivery admitted is retained verbatim, encrypted (`INV-REC-10`). The
Verify line read "Test asserting stored payload matches the received bytes." Refusal at the door,
rather than verbatim retention of PII-bearing files, is owner decision O3, settled at the
transition on the design's recommendation and open to revision.)*

### INV-HIST-03 — Audit records are append-only
**Statement:** Audit records cannot be updated or deleted by any application role.
**Why:** `CLAUDE.md` — application logs are not a regulatory-grade audit trail; an audit
trail that can be edited is worth less than none, because it invites false confidence.
**Enforce:** `DB-PRIVILEGE`.
**Verify:** Privilege test.
**Phase:** 0

### INV-HIST-04 — Decisions record the version of the policy that produced them
**Statement:** Any versioned artefact (credit policy, fee schedule, routing policy, matching
rule, rounding policy, risk rule set) used in a decision is pinned and recorded on that decision.
**Why:** Without version pinning, a past decision cannot be reproduced or defended.
**Enforce:** `DB-CONSTRAINT` (`NOT NULL` version reference).
**Verify:** Replay test reproducing a stored decision exactly — for matching, decision replay
(`POST /v1/operator/reconciliation/runs/{id}/replay`) re-running every stored decision over its
candidate snapshot under its pinned rule set, `IDENTICAL` or `DIVERGED`, a divergence raising a
CRITICAL `PROCESSING_ERROR` break. *(Amended at the Phase 7 → 8 transition, ADR-0068
(`Proposed`), in force when `P8-TSK-022` lands; the line ended at "exactly".)* *(As built by
`P8-TSK-022`, 2026-10-01: the snapshot was completed first - `V012`'s six replay inputs and
`match_parked_original`, required on every new decision - so the replay re-runs the pure function
each decision's verdict names under its pinned version; golden snapshots catch the
perturbation probe, and a replay reading the active version instead of the pinned one is
caught by a later version that would decide otherwise - the completion gate's find, a probe
that first survived.)*
**Phase:** 6 (fees), 7 (routing), 8 (matching), 10 (credit), 13 (risk)

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0075 and ADR-0080 (`Proposed`), in
force when `P9-TSK-007` and `-015` land: the pricing policy version, the corridor policy
version and the rounding-policy names join the pinned subjects — pinned at the claim
(`fx.quote_request`, `crossborder.offer_request`) before any provider call, refused
`409 PolicyStale` past a successor, and replayed by `FxPlanVerification` under the pinned
version, the ADR-0068 §9 replay discipline applied to FX.)*

---

# Idempotency — `INV-IDEM`

### INV-IDEM-01 — Same business command, same key, one financial effect
**Statement:** Retrying a business command with the same idempotency key produces exactly
one financial effect and returns the original outcome.
**Why:** `CLAUDE.md` rules 7–8; clients retry, and networks lose responses.
**Enforce:** `DB-CONSTRAINT` — unique on (scope, idempotency key). Not a cache, not an
HTTP filter.
**Verify:** Concurrent-duplicate integration tests.
**Phase:** 0 (kernel), 4 (transfers), 5 (payments)

### INV-IDEM-02 — Scheduled financial processes are idempotent per period
**Statement:** Re-running an accrual, fee assessment, settlement or close process for the
same period produces no additional financial effect.
**Why:** Batch jobs crash and are re-run; double accrual is money creation.
**Enforce:** `DB-CONSTRAINT` — unique on (process, entity, period).
**Verify:** Crash-and-rerun integration tests; for settlement, the live-batch unique —
`UNIQUE (source_id, external_batch_ref, currency)` among batches neither `REJECTED` nor
`REPUDIATED`, independent of `platform.idempotency_record` retention — with a re-acceptance on a
later clock day converging on `settlement-batch:<batchId>` (`INV-SET-04`). *(Amended at the
Phase 7 → 8 transition, ADR-0065 (`Proposed`), in force when `P8-TSK-009` lands; the line ended
at "tests".)*
**Phase:** 8, 11, 14

*(As built by `P11-TSK-019`, 2026-10-13 - for auto-collection, the scheduled lending process "per period" is per billed item
and business date: `lending.collection_attempt UNIQUE (due_item_id, attempt_date)` beneath the loan's row lock (L3), and the
attempt's repayment born under the key `<item>:<date>` (`repayment UNIQUE (loan_id, kind, request_key)`); an
`AUTO_COLLECTION` repayment without its attempt refused at commit. `AutoCollectionDatabaseTest#twoSweepersCollectOnce`
(scenario 2's sweeper half: two and ten racing steps - one attempt, one repayment, one entry, one event) and
`#aCrashMidStepLeavesNothing`. The probe drops the unique - `MUTATION_TESTING.md` section 2.)*

*(As built by `P12-TSK-020`, 2026-10-19 - BNPL's condition and late fee, per (obligation, business date, kind) and per
(instalment, kind): `bnpl_condition_event UNIQUE (obligation_id, business_date, kind)` and `bnpl_fee_assessment UNIQUE
(instalment_id, kind)` beneath B4, the fee's ledger key `bnpl.fee:<instalment>:LATE` a second arbiter.
`BnplConditionsTest#tenStepsOneConditionChange`, `BnplLateFeesTest#tenStepsAssessOnce` (hermetic, run),
`BnplConditionDatabaseTest#tenSweepersOneConditionChange` and `BnplLateFeeDatabaseTest#tenSweepersAssessOnce` (owed).)*

*(As built by `P12-TSK-019`, 2026-10-19 - BNPL's auto-collection, the same shape: per billed instalment and business date,
`bnpl.bnpl_collection_attempt UNIQUE (instalment_id, attempt_date)` beneath the obligation's row lock (B4), the attempt's
repayment born under `<instalment>:<date>` (`bnpl_repayment UNIQUE (obligation_id, kind, request_key)`), never back-filled
(no attempt dated before a later one of its instalment), and an `AUTO_COLLECTION` repayment without its attempt refused at
commit. `BnplCollectionsTest#theSameDateCollectsOnce`/`#tenStepsCollectOnce` (hermetic, run) and
`BnplCollectionDatabaseTest#tenSweepersCollectOnce` (scenario 4: ten sweepers and a customer's repayment; owed a first
run). The probes - `MUTATION_TESTING.md` section 2.)*

*(As built by `P11-TSK-022`, 2026-10-13 - the statement, per (line, cycle end): `credit_line_statement UNIQUE (loan_id, cycle_end)` beneath the line's lock and the ledger key `lending.statement:<line>:<statement date>`, whose replay the step refuses as a defect; the credit balance applied at it once (`credit_balance_application UNIQUE (billing_id)`); its minimum auto-collected once per business date (`-019`'s unique). `CreditLineStatementDatabaseTest#tenSweepersOneStatement` (thirty racing steps over three lines, the unique proven with the triggers suspended) and `#theMinimumPaymentIsAutoCollectedOnItsDueDate` (ten racing steps, one collection).)*

### INV-IDEM-03 — Key reuse with a different request is rejected
**Statement:** Presenting a known idempotency key with a materially different request is an
error, never a silent success and never a second effect.
**Why:** Silently returning the first response to a different request hides a client defect
and can conceal a fraud attempt.
**Enforce:** `DOMAIN` — request fingerprint comparison.
**Verify:** Integration test asserting a distinct conflict error.
**Phase:** 0

### INV-IDEM-04 — Duplicate inbound events produce no second effect
**Statement:** The same external event, webhook or message delivered any number of times
produces at most one financial effect.
**Why:** At-least-once delivery is the norm; duplicate webhooks are expected.
**Enforce:** `DB-CONSTRAINT` — inbox dedupe key, committed with the side effect.
**Verify:** Duplicate-delivery integration tests.
**Phase:** 0 (kernel), 5 (webhooks)

---

# Concurrency — `INV-CON`

### INV-CON-01 — No lost updates on financial state
**Statement:** Concurrent operations on the same financial resource never produce a lost
update, a double spend, or a balance inconsistent with its postings.
**Why:** The classic financial race; it produces losses that are only found at
reconciliation, if at all.
**Enforce:** `DB-CONSTRAINT` + explicit isolation level and locking strategy (ADR at Phase 3).
**Verify:** Concurrency integration tests with real contention, not mocked.
**Phase:** 3

### INV-CON-02 — Racing money-moving requests produce one effect
**Statement:** Two simultaneous requests to move the same funds result in exactly one
successful movement; the other fails with a domain outcome.
**Why:** `CLAUDE.md` §Failure Engineering — "two requests race".
**Enforce:** `DB-CONSTRAINT` + `DOMAIN`.
**Verify:** Multi-threaded integration tests.
**Phase:** 4

### INV-CON-03 — Velocity and limit counters are correct under concurrency
**Statement:** Limit and velocity evaluation is not bypassable by concurrent requests.
**Why:** Limits enforced non-atomically are limits that do not exist.
**Enforce:** `DOMAIN` with atomic counter semantics; authoritative check anchored to durable
state, not to cache alone.
**Verify:** Concurrency tests; counter-store loss test.
**Phase:** 13

---

# Lifecycle and State — `INV-LIFE`

### INV-LIFE-01 — Every money-moving operation has an explicit state machine
**Statement:** Transfers, payments, refunds, disputes, settlements, loans and agreements
each have an explicit, enumerated lifecycle with defined transitions.
**Why:** `CLAUDE.md` rule 6. Implicit state is unrecoverable state.
**Enforce:** `DOMAIN`.
**Verify:** Exhaustive invalid-transition tests.
**Phase:** 4 onward

### INV-LIFE-02 — Invalid transitions are rejected by the domain
**Statement:** An invalid state transition is rejected by the aggregate itself, not merely
unreachable through the API or UI.
**Why:** Every aggregate will eventually be driven by a second caller — a job, a webhook, an
operator tool.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` where representable.
**Verify:** Direct-aggregate transition tests.
**Phase:** 4 onward

### INV-LIFE-03 — Unknown external state is a modelled state
**Statement:** When a provider's outcome is unknown, the operation enters an explicit
`UNKNOWN`/indeterminate state with a defined resolution path. It is never recorded as
success or failure by assumption.
**Why:** `CLAUDE.md` — never assume a timeout means the operation failed. This is the single
most expensive assumption in payments.
**Enforce:** `DOMAIN`.
**Verify:** Provider-timeout-then-success contract tests; unknown-state age metric. *(Every Phase 7
machine with a modelled unknown has its age metric since `P7-TSK-015`: attempts of every model and
refunds of every rail in `finapp.payments.unknown.*`, withdrawals in
`finapp.payments.withdrawal.unknown.*`, dispute answers in `finapp.payments.dispute.response.unknown.*`
— each counting every `UNKNOWN` and every dispatch past its sweep's own bound, NaN when unreadable.)*
**Phase:** 5

### INV-LIFE-04 — Terminal states are terminal
**Statement:** Once an operation reaches a terminal state, no transition out of it occurs.
Subsequent economic changes are new operations (reversal, refund, chargeback).
**Why:** Reopening terminal states makes history non-monotonic and reports unreproducible.
**Enforce:** `DOMAIN`.
**Verify:** Transition tests from every terminal state.
**Phase:** 4 onward

---

# Reversal and Correction — `INV-REV`

### INV-REV-01 — A reversal is a new financial effect
**Statement:** A reversal creates new balanced postings that reference the original entry.
It never edits, deletes or negates the original in place.
**Why:** `FINANCIAL_INVARIANTS` original text; auditors must see both the error and its
correction.
**Enforce:** `DB-PRIVILEGE` (original immutable) + `DB-CONSTRAINT` (reversal references
original).
**Verify:** Test asserting the original is byte-identical after reversal.
**Phase:** 3

*(Extended by `P8-TSK-023`, 2026-10-01, to accepted settlement evidence and its matches: a
batch's repudiation reverses its recognition through `ReversalService` and undoes each
allocation by an append-only counter-allocation bound to its original - once, its exact mirror -
for every writer (reconciliation `V013`); the batch, its file and its lines stay as they were.)*

### INV-REV-02 — A reversal is bounded by the original
**Statement:** The reversed amount never exceeds the original effect, accounting for
previous partial reversals.
**Why:** Over-reversal creates money.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` where representable.
**Verify:** Concurrent partial-reversal tests.
*(Applied to FX conversions by `P9-TSK-025` (2026-10-06): an operator's approved trade reversal posts the
conversion's exact mirror through `ReversalService`, ledger `V009`'s bound refusing a second; ten racing approvers make
one mirror entry. Verified by `FxTradeReversalRaceDatabaseTest`.)*
**Phase:** 3, 5 (refunds)

### INV-REV-03 — Reversal on an irrevocable rail is rejected
**Statement:** Where a rail's finality semantics make reversal impossible, the domain
rejects the reversal rather than attempting it.
**Why:** Attempting the impossible produces indeterminate state and stranded value.
**Enforce:** `DOMAIN` — rail capability model.
**Verify:** Per-rail finality tests.
**Phase:** 7

### INV-REV-04 — Adjustments carry a reason and authorisation
**Statement:** Every manual adjustment records a reason code and the authorising actor, with
four-eyes approval above defined thresholds.
**Why:** Manual postings are the highest-risk financial action in any platform.
**Enforce:** `DOMAIN` + `PROCESS` (four-eyes) + `DB-CONSTRAINT` (`NOT NULL` reason, and since
ledger `V015` a closed `reason_code` on every adjustment proposal — `MANUAL_CORRECTION`,
`RECONCILIATION_WRITE_OFF`, `RECONCILIATION_TRANSFER`, `RECONCILIATION_GAIN` or
`RECONCILIATION_OFFSET`, `UNCODED` reserved to proposals that predate it and refused on every new
one by a `BEFORE INSERT` trigger — frozen with the payload beside an `origin` of `MANUAL` or
`RECONCILIATION`; each origin's door refuses the other's proposals).
**Verify:** Authorization negative tests; a raw insert without a reason code refused; approval
and `DELETE` of a `RECONCILIATION`-origin proposal through the generic door refused (`409
ledger.AdjustmentOriginMismatch`).
**Phase:** 3, 8

*(Amended at the Phase 7 → 8 transition, ADR-0071 (`Proposed`), in force when `P8-TSK-006`
lands: the Enforce line ended "`DB-CONSTRAINT` (`NOT NULL` reason)." and the Verify line read
"Authorization negative tests." Until then the reason is ledger `V010`'s free text, and the
statement's "reason code" has no column. The generic `POST /v1/ledger/adjustments` assigns
`MANUAL_CORRECTION` server-side, so its request is unchanged — additive under ADR-0015 — and
`ADD COLUMN ... DEFAULT` fires no update trigger, so existing proposals stay valid and read
`UNCODED` and `MANUAL`. `RECONCILIATION_OFFSET`, which no Phase 8 resolution kind produces
(`OFFSET_SUSPENSE` posts nothing; a correction offset is a system `POSTING`), is kept or dropped
by `P8-TSK-006`'s design before `V015` is written, because migrations are forward-only and the
closed set cannot wait for `P8-TSK-015` — recorded by the transition's consistency review, B14.)*

---

# Events and Publication — `INV-EVT`

### INV-EVT-01 — State change and publication commit atomically
**Statement:** A domain fact and its publication record are written in the same transaction.
Publication never occurs outside the transaction that produced the fact.
**Why:** Otherwise the system either publishes facts that were rolled back, or loses facts
that were committed.
**Enforce:** `DOMAIN` (outbox writer) + `STATIC` (no direct broker publish from domain code).
**Verify:** Crash-between-commit-and-publish integration test.
**Phase:** 0

### INV-EVT-02 — Events are not the accounting source of truth
**Statement:** No financial balance, position or decision is derived solely from the event
stream. Kafka is transport.
**Why:** `CLAUDE.md` rule 12.
**Enforce:** `STATIC` (boundary rules) + `PROCESS` (review).
**Verify:** Architecture review.
**Phase:** 0

### INV-EVT-03 — Every event carries the full envelope
**Statement:** Every published event carries all ten envelope fields, including correlation
and causation identifiers.
**Why:** Traceability across the full chain is mandated by `CLAUDE.md` §Events.
**Enforce:** `DOMAIN` — envelope construction requires all fields.
**Verify:** Serialisation tests; consumer-side assertion.
**Phase:** 0

### INV-EVT-04 — Consumers tolerate duplication, delay, reordering and replay
**Statement:** Every consumer is safe under duplicate, late, out-of-order and replayed
delivery.
**Why:** `EVENT_ARCHITECTURE.md` delivery assumptions.
**Enforce:** `DOMAIN` (inbox dedupe + order-independent handlers).
**Verify:** Per-consumer duplicate/reorder/replay tests.
**Phase:** 0 onward

---

# Settlement — `INV-SET`

### INV-SET-01 — Internal completion is not settlement
**Statement:** Internal "completed"/"captured" state and external settlement are separate
states, unless the rail itself guarantees immediate settlement — in which case that
guarantee is documented per rail.
**Why:** Conflating them overstates available funds and misstates the balance sheet.
**Enforce:** `DOMAIN` — distinct state fields and distinct postings.
**Verify:** Lifecycle tests per rail.
**Phase:** 5, 7, 8

*(Phase 8 on the instant rail, as built by `P8-TSK-017`: a pay-in `EXECUTED`, a withdrawal or
return `COMPLETED` is the expectation's `PENDING`, the scheme's cycle report its `REPORTED`, and
only the bank statement's recognition of the cycle's net its `CASH_CONFIRMED` —
`SchemeCycleCashDatabaseTest` walks all three for a net-receivable and a net-payable cycle.)*
*(And for the payout, as built by `P8-TSK-018`: a payout `COMPLETED` is instructed, its
expectation `PENDING`; the provider's report its `REPORTED`; the bank's debit its
`CASH_CONFIRMED` — and settlement moves no payout out of `COMPLETED` (`INV-LIFE-04`),
`PayoutSettlementCashDatabaseTest` walking it over the wire.)*

### INV-SET-02 — Settlement expectations are tracked
**Statement:** Every operation expected to settle externally creates a tracked expectation
that ages and alerts when unmet.
**Why:** Value that never settles must be visible, not silently assumed received.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (the expectation uniques, `UNIQUE (kind, operation_ref)`
and `UNIQUE (journal_entry_id, ledger_account_id)`, written in the completion's own transaction
through the `SettlementExpectations` and `PayoutSettlementExpectations` ports) + `PROCESS` (the
completeness verifier, `finapp.reconciliation.line.unattributed`).
**Verify:** The expectation-opener register — every externally settling completion's posting key
has a test proving its expectation; the expectation gauges
`finapp.reconciliation.expectation.overdue` and `.overdue.age` with alerting; missing-settlement
tests.
**Phase:** 8

*(Amended at the Phase 7 → 8 transition, ADR-0067 (`Proposed`), in force when `P8-TSK-004`,
`-005` and `-007` land: the Enforce line read "`DOMAIN`." and the Verify line "Ageing metric with
alerting; missing-settlement tests." The expectation is opened past the completion's acting exit,
keyed on the stored rail's declared clearing purpose, and the port is infallible for valid input:
a colliding key is recorded as a counted `KEY_COLLISION` and later raised as a
`DUPLICATE_INTERNAL` break, never a failed payment. The register covers the completions' posting
keys (a payout return's included, ADR-0073 §3), not every entry touching a clearing position: a
recognition, a park, a resolution and a repudiation open no expectation and are known to the
completeness verifier instead (ADR-0067 §9).)*

### INV-SET-03 — Late settlement is handled, not rejected
**Statement:** Settlement arriving later than expected is processed correctly rather than
discarded as stale.
**Why:** `CLAUDE.md` §Failure Engineering — "settlement arrives late".
**Enforce:** `DOMAIN`.
**Verify:** Late-arrival tests.
**Phase:** 8

*`INV-SET-04`…`INV-SET-07` catalogued by the Phase 7 → 8 transition (2026-09-28), the `INV-RAIL`
and `INV-DSP` precedent: Phase 8's gate properties given stable IDs before any settlement code
exists, so the register can demand their demonstrations by identifier rather than by prose.
Decisions in ADR-0064…0067. `INV-SET-02` and `INV-SET-03`, catalogued at initiation and
subjectless until now, are Phase 8's too, and so is `INV-SET-01` at the last hop. Until Phase 8's
first task lands, nothing these entries name is implemented; every statement is the decided
design, corrected by the tasks that build it.*

### INV-SET-04 — A settlement batch is recognised once, from its own stored evidence
**Statement:** A settlement batch is accepted at most once per (source, external batch reference,
currency) among live batches — those neither `REJECTED` nor `REPUDIATED`. Its recognition posting
is keyed by the batch, `settlement-batch:<batchId>`, and dated only from facts stored with it: the
posting date is the batch's `accepted_on`, stamped once in the acceptance transaction, and the
value date is the evidence's. A re-delivery, a conflicting re-issue, a retried acceptance or a
later-day replay produces no second effect.
**Why:** `INV-IDEM-02` for settlement: a batch recognised twice expenses its fees twice and opens
a remittance nobody owes. The posting fingerprint binds the entry's dates, so a recognition dated
from the clock would conflict on a later day instead of converging, and a guarantee resting only
on `platform.idempotency_record` lasts only as long as that row is retained (ADR-0065 §6, §7).
**Enforce:** `DB-CONSTRAINT` — the content unique on `settlement.file`, the live-batch uniques
`(source_id, external_batch_ref, currency)` and `(source_id, currency, statement_sequence)` on
`settlement.batch`, `UNIQUE (batch_id)` on the run, none depending on `idempotency_record`
retention — + `DOMAIN` (the conditional `PARSED → ACCEPTED`, the posting key, dates read from the
row and never from a clock).
**Verify:** The same file sequentially and ten ways across upload and racing pulls — one file,
batch, run and recognition entry; a conflicting re-issue refused as `REJECTED(CONFLICTING_BATCH)`
and retained; a re-acceptance on a later clock day converging on the key.
**Phase:** 8

### INV-SET-05 — Every externally settling position has exactly one declared source, and only that source's evidence discharges it
**Statement:** The clearing position of every rail declaring `settlement() != NONE`, and the
payout position, is discharged by exactly one declared source, whose position is read from that
counterparty's own declaration — `RailCapabilities.clearingPurpose()`,
`merchant.PayoutSettlementDeclaration.CLEARING_PURPOSE` — when `app` composes the register, and
stored nowhere else. `CASH_AT_BANK` is recognised by exactly one source, the bank's. No source's
evidence posts to, or allocates against, another counterparty's position.
**Why:** A position without a source is never reconciled, and its value ages unexplained;
discharging one counterparty's position with another's evidence is `INV-RAIL-04`'s netting
reached through settlement (ADR-0064 §5, ADR-0065 §4).
**Enforce:** `STATIC` — `EverySettlingPositionHasASource`, and
`RailVocabularyIsConfinedTest#clearingPositionsAreNamedOnlyByTheirDeclarations` widened to
`settlement` and `reconciliation`, neither of which may name a `*_CLEARING` purpose — + `DOMAIN`
(the register composed from the declarations in `app`'s `SettlementBeans`, `settlement.source`
holding no position column; a bank line attributed only to the unique source whose declared
`remittancePattern` matches, zero or two matches leaving it unattributed and parked with its
break; matching candidates restricted to the item's own source — for an attributed bank item,
its attributed source, the key scope `COALESCE(attributed_source_id, source_id)`).
**Verify:** The coverage test failing on a planted uncovered rail; per-source position proofs in
the settlement storm, each clearing position discharged only by its own source's evidence.
*As built (`P8-TSK-016`):* `SettlementSources.attribute` answers the unique FULL match of the
compiled patterns, empty for zero or two (`SettlementSourcesTest`, the two-pattern case), written
on `settlement.line.attributed_source_id` at parse and copied to the item; the probe taking the
first of two matches is recorded in `MUTATION_TESTING.md` §2.
*(Per counterparty under two counterparties of one kind since `P9-TSK-026` (2026-10-06): each of `fx-sim-a`/`-b`
and `corridor-sim-a`/`-b` has its own source and its own remittance shape - two sources sharing a shape refuse
composition - and a settlement source resolves to its own counterparty's rail; a line on one counterparty's report
naming the other's operation is never allocated. Verified by `SecondFxProviderDatabaseTest`,
`SecondCorridorRailDatabaseTest`, `EverySettlingPositionHasASourceTest` and `RailOfSourceTest`.)*
**Phase:** 8

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0078 (`Proposed`), in force when
`P9-TSK-010`, `-011` and `-014` land: "every externally settling position" is read per
(purpose, counterparty) for the counterparty-owned purposes — `FX_PROVIDER_CLEARING(fx-sim-a)`
and `CORRIDOR_CLEARING(corridor-sim-a)` each have exactly one declared source, read from the
declaration when `app` composes the register — and a second provider of either kind is a
declaration, a seed migration and its own source, never a second writer of an existing
position.)*

*(As built by `P9-TSK-010` (2026-10-05): `SettlementSourceDescriptor` carries
`settledCounterparty` (present exactly on a counterparty-owned position) and its
`settledCurrencies`; `SettlementSources.of` refuses two sources on one (purpose, counterparty)
and admits two counterparties on one purpose as two positions; the composition proves both ways
that every declared counterparty position (`CounterpartyClearings`) has its one source and every
counterparty a source names is declared (`EverySettlingPositionHasASourceTest`, planted);
recognitions and remittances resolve the counterparty's own account; `PositionProof`'s proven
purposes are derived from the register and completeness walks every account of a reconciled
purpose. No counterparty is declared until `P9-TSK-011`.)*

### INV-SET-06 — Cash moves only on the bank's own statement
**Statement:** `CASH_AT_BANK` is posted only by the recognition of an accepted bank statement, or
by the repudiation of one. Whenever the chain of accepted statements for a currency is unbroken —
each statement's sequence following the last, its opening balance equal to the previous closing,
the first opening at zero — the position equals the latest statement's closing balance. A gap or
an opening mismatch raises a break, fails the cash proof until evidence fills it, and is never
adjusted to fit.
**Why:** `INV-SET-01` at the last hop: a counterparty's report that it paid is still that
counterparty's obligation, not cash. Cash recognised from anything but the bank's own evidence, or
adjusted to agree with it, states money the platform may not hold (ADR-0065 §3).
**Enforce:** `STATIC` (one poster: only bank-statement recognition and a statement's repudiation
name `CASH_AT_BANK`) + `DOMAIN` (continuity checked for every accepted statement; a
`SETTLEMENT_MISMATCH` with cause `STATEMENT_GAP` or `OPENING_BALANCE`, closed only `EVIDENCED`,
nothing posted to fit) + `DB-CONSTRAINT` (ledger `V015`'s binding refuses a `MANUAL`-origin
adjustment line on a `reconciledPositions()` purpose, `CASH_AT_BANK` among them).
**Verify:** The static rule with a planted violation; gap, out-of-order and non-zero-opening
tests; the cash proof, `finapp.reconciliation.cash.proof`, at 0 at rest and failing while a
statement break is open.
*As built (`P8-TSK-016`):* the static rule is `CashAtBankHasOnePosterTest` — the token, statically
imported or by name, permitted only in `BatchAcceptance` (the poster, resolving the account for
`BankRecognition`) and `PositionProof` (the reader), a planted poster in each spelling caught;
continuity is `StatementChain` inside every statement's acceptance, under the settlement source
row lock (`StatementChainTest`, `StatementChainDatabaseTest`, and `BankStatementCashDatabaseTest`'s
gap filled `EVIDENCED`, non-zero first opening and ten racing acceptors); the cash proof is
`PositionProof.CashVerdict` in the sweep's one `REPEATABLE READ` snapshot, published per currency
and shown as the positions report's cash rows; the `MANUAL` refusal on `CASH_AT_BANK` at both ranks
is `AdjustmentEndpointDatabaseTest#cashAtBankIsClosedToFreeAdjustments` (ledger `V018` re-stating
the binding). The repudiation poster arrives with `P8-TSK-023`; until then the rule permits one.
*(Corrected 2026-10-02 by the Phase 8 -> 9 transition, SET-2: "a gap raises a break" now holds on
the repudiation path too. Repudiating a statement in the middle of its chain opens a gap before its
accepted successor; the approval raises `STATEMENT_GAP` on the successor's run, read under the same
settlement source row lock every acceptance holds - a fresh break even where the repudiated
statement had filled an earlier gap - and the genuine statement's acceptance closes it `EVIDENCED`
(`BatchRepudiationDatabaseTest` case 18).)*
**Phase:** 8
*(The corridor's half in force since `P9-TSK-022` (2026-10-06): `CORRIDOR_CLEARING` reaches cash only on the
bank's statement discharging the corridor report's remittance; section 12.4(g)'s USD cash identity holds exactly.
Verified by `CorridorSettlementToCashDatabaseTest`.)*

*(The first statement opening at zero is owner decision O4, settled at the Phase 7 → 8 transition
on the design's recommendation and open to revision: a non-zero first opening raises
`SETTLEMENT_MISMATCH` (`OPENING_BALANCE`) and posts nothing, because its only honest
counter-account is equity, which is Phase 14's. A statement cause closes only `EVIDENCED`: ADR-0069's
per-type table admits `WRITE_OFF`, `TRANSFER_TO_ACCOUNT` and, for a credit excess after the minimum
age and four-eyes, `RECOGNISE_GAIN` for `SETTLEMENT_MISMATCH` under the `REMITTANCE_DIFFERS` cause
alone — the transition's consistency review, A3, the gain added by its re-check, R14.)*

### INV-SET-07 — Settlement evidence takes effect only whole and authenticated
**Statement:** No line of a settlement file drives matching or posting unless every line of the
file is valid and its control totals equal the lines, and unless the file was pulled over its
source's own confined credential or, when uploaded, attested by a second person holding
`SETTLEMENT_INGEST`, distinct from the uploader. A readmitted file inherits its original's
authentication, its checksum being identical, when the original was pulled or attested; the
readmission of an original never attested is inert until a second holder of `SETTLEMENT_INGEST`,
distinct from the readmitter and from the original's uploader, attests the readmission itself.
Unattested evidence is retained but inert.
**Why:** Half a file posts half a settlement, and a partly applied batch cannot be told from a
short-paid one. A single insider able to introduce both a counterparty's report and a bank
statement could discharge clearing into fictitious cash — `INV-PAY-01`'s authenticated source
applied to files (ADR-0066 §2, §8, §9).
**Enforce:** `DOMAIN` (the parse is one transaction — lines, references, batch and totals, or the
errors and `REJECTED` — and so is the acceptance; the accept leg admits an upload, or an
unattested original's readmission, only once attested; our own parser failure leaves the file
`RECEIVED`, never `REJECTED`; a pull source URL that is neither `https` nor `sftp` off loopback
refuses startup, `ProviderTransportGuard` extended to the pull sources) + `DB-CONSTRAINT` (the
attestation `CHECK`s on `settlement.file`: `attested_by IS NULL OR attested_by <> received_by`,
and no `ACCEPTED` upload without `attested_by`; `P8-TSK-022` holds a readmission's attester apart
at both ranks).
**Verify:** Partially corrupt files and failure injection mid-parse and mid-acceptance, each
rejected or rolled back whole; self-attestation refused at the domain and by the `CHECK`; an
unattested upload never accepted, and an unattested original's readmission accepted only once a
person distinct from the readmitter and from the original's uploader attests it.
**Phase:** 8

*(The transport clause rests on the Phase 7 → 8 transition's repair: `ProviderTransportGuard`
refuses a non-`https` provider URL off loopback at startup, and `P8-TSK-021` extends it to the
settlement pull's own source URLs, so a source's confined credential never rides a cleartext
channel. The readmission clause is ADR-0066 §8 as the transition's consistency review settled it
(A11): an original never attested passes no authentication on, and a `CONFLICTING_BATCH` original
whose conflicting batch is now `REPUDIATED` is readmissible; whether a declined upload is, is
`P8-TSK-022`'s to decide, and such a readmission would inherit nothing and be attested.)*
*(Corrected 2026-10-02 by the Phase 8 -> 9 transition, MI-2: an `ACCEPTED` file whose batch is
`REPUDIATED` is readmissible too, inheriting its original's authentication - the recovery of our
own adapter's mis-normalisation, whose genuine evidence is the accepted file's own bytes; settlement
`V011` refuses any other original for every writer.)*
*(Corrected 2026-10-03 by the Phase 8 -> 9 transition's re-gate, NEW-SEC-1: "inheriting its
original's authentication" was itself the finding - it made the four-eyes repudiation verdict
reversible by one readmitter, who alone re-posted the repudiated recognition. A repudiated
batch's file passes nothing on, exactly as a `DECLINED` one - settlement `V014` re-states
`V009`'s walk, the one function the trigger, the accept leg's eligibility and the attestation's
rank all read - and such a readmission is inert until a holder of `SETTLEMENT_INGEST` distinct
from every submitter along its chain attests it.)*

*(As built by `P8-TSK-021`, 2026-10-01: the pulled channel exists — `SettlementPull` receives
through the one door with `received_via = PULL` after a fetch over the source's own confined
credential, and the accept leg's eligibility admits it unattested; `ProviderTransportGuard` reads
the four source URLs at startup. Demonstrated: a pulled file held for attestation, and the guard
not consulted for a source URL, each caught — `MUTATION_TESTING.md` §2.)*

*(As built by `P8-TSK-022`, 2026-10-01: the readmission clause holds at both ranks. Settlement
`V009`'s functions decide what a readmission inherits - a pulled or attested original's
authentication, or what a readmitted original itself inherited, and nothing past a `DECLINED`
file - and who may attest one that inherits nothing: a person distinct from every submitter
along its chain. Its trigger refuses, on insert and on every update, an attester among the
submitters and an `ACCEPTED` readmission that inherits nothing unattested; the accept leg's
eligibility and the attestation's domain check read the same functions. Decided: a `DECLINED`
file is readmissible and inherits nothing. Demonstrated: the trigger dropped and the domain's
eligibility widened, each caught — `MUTATION_TESTING.md` §2.)*

---

# Reconciliation — `INV-REC`

*(As built by `P9-TSK-011` (2026-10-05): a parsed batch in a currency its source's counterparty does not settle is refused at the parse leg `CURRENCY_NOT_SETTLED` and retained - no batch, nothing posted - and never readmitted (settlement `V015` restates the readmission rule); proven by `FxProviderSourceDatabaseTest` and the probe in `MUTATION_TESTING.md` §2.)*

### INV-REC-01 — Evidence is preserved
**Statement:** When internal and external records disagree, both sides of the evidence are
preserved in full.
**Why:** `FINANCIAL_INVARIANTS` original text; `RECONCILIATION_MODEL.md` — never erase
evidence of a break.
**Enforce:** `DB-PRIVILEGE` on evidence tables.
**Verify:** Privilege tests; break-lifecycle tests.
**Phase:** 8

### INV-REC-02 — Breaks are classified, never discarded
**Statement:** Every unmatched or mismatched record becomes a classified break record. No
record is silently dropped, auto-cleared or suppressed. An `EVIDENCED` resolution — the platform
closing a break because later evidence explains it: a zero-residual allocation or offset, stored
as a Resolution row naming that decision and posting, or an accepted bank statement restoring the
chain, stored naming that statement — is recorded, not silent, and is the only resolution no person
decides.
**Why:** An unexplained difference that disappears is a loss nobody noticed.
**Enforce:** `DOMAIN`.
**Verify:** Tests for every break type; unmatched-count metric.
**Phase:** 8

*(Amended at the Phase 7 → 8 transition, ADR-0069 (`Proposed`), in force when `P8-TSK-012`
stores the first `EVIDENCED` row: the statement's last sentence was added. Closing a break on
evidence is not the auto-clearing this invariant forbids, because the closure is itself a record
— kind `EVIDENCED`, reason code `EVIDENCE_RECEIVED`, born `APPROVED` under the platform, naming
the decision and, where parked value moved, the park whose `recon-suspense:` entry is the
posting. The alternative, recorded and not taken: the platform proposes and a person confirms.
The wording is to be widened, with provenance, to the non-allocation evidence the break taxonomy
also accepts — an accepted statement restoring the chain, a requeued run completing — by
`P8-TSK-016` and `P8-TSK-022` (ADR-0069 §8).)* *(Widened by `P8-TSK-016` for the first: a
`STATEMENT_GAP` closes `EVIDENCED` when the missing statement is accepted and its closing is the
successor's opening, the resolution's narrative naming the filling statement's batch and its
`decision_id` NULL — no decision is made there; `Resolutions.Evidence` names its decision or its
filling statement, exactly one. The requeued run remains `P8-TSK-022`'s.)*

### INV-REC-03 — Resolution is a controlled adjustment
**Statement:** A break is resolved by posting a compensating entry with a reason code and
authorisation — never by editing either record.
**Why:** `FINANCIAL_INVARIANTS` original text.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` + `PROCESS` (four-eyes). At the database rank: a posting
resolution, its ledger adjustment proposal and its journal entry are one-to-one (`UNIQUE
adjustment_proposal_id` and `UNIQUE journal_entry_id` on `reconciliation.resolution`, ledger
`V010`'s deferred rule that an `ADJUSTMENT` entry needs an `APPROVED` proposal); four-eyes
whenever value is at issue or the resolution posts — a zero-value `ACKNOWLEDGE` of a
`TIMING_DIFFERENCE` raised by a timing detector alone is single-person *(corrected 2026-10-01, `P8-TST-002`: a diverged
replay's zero-value acknowledgement is four-eyes, derived from the break by reconciliation `V014`)*, and `EVIDENCED` is the platform's alone — refused at three ranks (the domain, the
`resolution` four-eyes `CHECK`, `V010`'s approver ≠ initiator `CHECK`); closed reason codes at
both ranks (`ResolutionReasonCode` with an allowed subset per kind, ledger `V015`'s
`reason_code`); a kind admitted only where ADR-0069's per-type table lists it for the break's type
(`reconciliation.ResolutionKindNotAllowed`); a stale approval refused (`409
reconciliation.ResolutionStale` when the break's `residual_version` or the subject's remainder has
moved since the proposal froze them).
**Verify:** Resolution authorization tests; immutability tests.
**Phase:** 8

*(Amended at the Phase 7 → 8 transition, ADR-0071 (`Proposed`), in force when `P8-TSK-006` and
`P8-TSK-015` land: the Enforce line read "`DOMAIN` + `PROCESS` (four-eyes above threshold)." The
threshold is now defined — any value at issue or any posting — and a resolution's lines are
derived from its subject's current remainder, never typed. The statement reads as: where a
resolution moves value, it moves it only by a compensating entry, never by an edit. A resolution
whose effect is not an adjustment entry — `ACKNOWLEDGE` and `OFFSET_SUSPENSE`, which post
nothing; `EVIDENCED` and `MANUAL_MATCH`, whose posting is an allocation's unpark or offset;
`REPUDIATE_BATCH`, a ledger reversal — still carries a reason code and its authorisation. The
per-type admission is the transition's consistency review's, A1: ADR-0069's table is the one
authority on which kinds each break type admits.)*

### INV-REC-04 — Matching is deterministic and explainable
**Statement:** Every match records the rule version and tolerance that produced it, and the
same stored inputs always produce the same matches.
**Why:** `.claude/rules/reconciliation-domain.md` — matching must be deterministic and
explainable.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (`NOT NULL` rule version) — with claimant order, every
allocation to an expectation made through `allocate(E)` in `(source_sequence, line_no)` order, the
sequence assigned gaplessly at acceptance and the run leg held under advisory namespace `4` per
source; and decision snapshots, every evaluation writing a `match_decision` that carries its
`rule_set_id` and one `match_candidate` row per candidate it considered. *(Corrected 2026-10-02 by
the Phase 8 -> 9 transition: claimant order is each LEG's worklist order - the run and reprocess
legs, and now the rematch leg across a source's runs and the grace leg within its expired set,
which had read `(line_no, id)` and `(grace_until, id)`; across legs the sweep's order decides, so a
later run's line judged by the run leg can claim an expectation that opened after an earlier
residual's last decision before the rematch leg reaches that residual - value conserved, ADR-0068
point 4. Every late leg also judges with the item's true fingerprint, stored on its decision.)*
**Verify:** Replay test producing identical matches: decision replay re-running every stored
decision over its candidate snapshot under its pinned rule set (`IDENTICAL`, and the perturbation
probe `DIVERGED`); the shuffled-order property test over the pure layer.
**Phase:** 8

*(Amended at the Phase 7 → 8 transition, ADR-0068 (`Proposed`), in force when `P8-TSK-011` lands
and, for replay, `P8-TSK-022`: the statement read "the same inputs always produce the same
matches". Stated honestly, the property holds for stored inputs, not for the world: a decision is
a pure function of its stored candidate snapshot and its pinned rule set, while which candidates
it saw depends on what had been recorded when it ran — an expectation's `opened_at`,
first-writer-wins keys, grace expiry on the database clock — which is why the snapshot is stored.
Items whose rematch is merely pending are reported by replay as `PENDING_REMATCH`, not as
divergence. Enforce gained claimant order and decision snapshots; Verify, the shuffled-order
property test and snapshot replay.)*

### INV-REC-05 — Suspense is temporary and aged
**Statement:** Value parked in a suspense account is tracked, aged, reported and alerted on.
Suspense is never a permanent resting place.
**Why:** Ageing suspense is an unrecognised loss or liability.
**Enforce:** `PROCESS` + `DOMAIN` — value enters `SUSPENSE_UNMATCHED` only with its owning break
(`INV-REC-09`) and leaves only by evidence, an approved four-eyes resolution or a repudiation; an
unclaimed CREDIT item becomes revenue only by an approved `RECOGNISE_GAIN` to
`RECONCILIATION_GAINS`, four-eyes, once older than the rule set's pinned `gain_min_age_days`
(seeded 90), and is refused before it (`reconciliation.GainNotYetEligible`) — and only under a
break type ADR-0069's per-type table admits it for, never `REVERSAL_MISMATCH`, `REFUND_MISMATCH`
or `CURRENCY_MISMATCH`, whose value is a counterparty's or never income
(`reconciliation.ResolutionKindNotAllowed`).
**Verify:** Suspense age metrics with alerting — `finapp.reconciliation.suspense.open`, `.age`
(NaN when unreadable, never zero) and `.unowned`; the balance through the audited suspense report,
`GET /v1/operator/reports/reconciliation/suspense`, its CREDIT and DEBIT items gross and never
netted; the suspense proof at 0; `RECOGNISE_GAIN` refused before the minimum age.
**Phase:** 3 (accounts), 8 (management)

*(Amended at the Phase 7 → 8 transition, ADR-0070 (`Proposed`), in force when `P8-TSK-010`,
`P8-TSK-015` and `P8-TSK-024` land: the Enforce line read "`PROCESS` + `DOMAIN`." and the Verify
line "Suspense age and balance metrics with alerting." A balance is an amount, and amounts never
enter metrics (ADR-0072, ADR-0018): the age is a metric, the balance an audited report. The
90-day minimum age is owner decision O5, settled at the transition on the design's recommendation
and open to revision. The per-type limit on the gain was added by the transition's consistency
review (A1): ADR-0069's table is the one authority on which kinds each break type admits.)*

*(Phase 7's parking as it stands, after the same transition's repairs: a parking CLAIMS its scheme
execution first (`payments.scheme_execution_claim`, `V023`) — one money fact per execution, so a
credited execution, a withdrawal's or a return's echo never parks — and records why it parked
(`UNATTRIBUTED`, `ATTEMPT_CONCLUDED`, `AMOUNT_MISMATCH`), the reference it named, its cycle, the
attempt it named and its raw statements by the fifth evidence subject; it is counted where it is
written and aged by `finapp.payments.unmatched`. Phase 8's suspense item adopts each (`P8-TSK-020`).
Verified by `PayByBankDatabaseTest`'s parking, claim and race tests.)*

*`INV-REC-06`…`INV-REC-10` catalogued by the Phase 7 → 8 transition (2026-09-28), on the same
precedent. Decisions in ADR-0065…0068 and ADR-0070. `INV-REC-01`…`05`, catalogued at initiation,
are Phase 8's too; four of them are amended at the same transition, each with its provenance.
Until Phase 8's first task lands, nothing these entries name is implemented; every statement is
the decided design, corrected by the tasks that build it.*

### INV-REC-06 — Every reconciled position is explained by its open items
**Statement:** For each clearing position and currency, DR−CR equals the signed remainders of its
open settlement expectations less the signed unallocated, unparked remainders of its pending and
unmatched allocating external items. Every journal line on a clearing position or on
`SUSPENSE_UNMATCHED` belongs to an entry that reconciliation or settlement knows: an expectation
names its `(journal_entry_id, ledger_account_id)`, a suspense item owns it (`INV-REC-09`), or its
entry is a batch's recognition, a park's, a resolution's, a repudiation's or a payout return's.
Every externally settling completion opens its expectation in its own transaction. Value nothing
explains leaves the position only by parking with a break.
**Why:** `CLAUDE.md` rule 11 at the counterparty boundary, and the ground `INV-BAL-03`,
`INV-REC-02` and `INV-SET-02` stand on: a position that cannot be decomposed into the items that
make it up cannot be reconciled, and a difference nobody can attribute is a loss nobody noticed
(ADR-0065 §8, ADR-0067).
**Enforce:** `DOMAIN` (the expectation opened inside the completing transaction through the
infallible `SettlementExpectations` and `PayoutSettlementExpectations` ports, past the acting
exit; parking in the transaction that decides it) + `DB-CONSTRAINT` (the expectation uniques
`UNIQUE (kind, operation_ref)` and `UNIQUE (journal_entry_id, ledger_account_id)`; ledger `V015`'s
binding refusing a free adjustment on a reconciled position) + `PROCESS` (the report-only position
proof and completeness verifier: lock-free, report and never repair).
**Verify:** `finapp.reconciliation.position.proof` and `finapp.reconciliation.line.unattributed`
at 0 in every storm round and at rest, NaN never zero — on `SUSPENSE_UNMATCHED` once `P8-TSK-020`
has given each of Phase 7's parkings its suspense item; a removed-expectation probe and a raw-SQL
line probe each flip a gauge.
**Phase:** 8

*(The known-entry list is ADR-0067 §9's, the suspense item included by the transition's
consistency review, A7: without it an unmatched confirmation's suspense line would read as
unknown for ever.)*

### INV-REC-07 — A match allocates no more than either side holds, records what it saw, and is never undone
**Statement:** An allocation joins one external item and one settlement expectation of the same
source, currency and direction. The allocations on each, net of any counter-allocation, never
exceed its amount less what was resolved, parked or offset. Every allocation references a match
decision that stores its pinned rule set and a snapshot of every candidate it considered.
Allocations and decisions are append-only; only a batch repudiation adds counter-allocations.
**Why:** Over-allocation creates or destroys explained value — an expectation shown settled that
was not, or an excess hidden inside another item's match — and a match that can be edited is
financial history that can be edited (ADR-0064, ADR-0068).
**Enforce:** `DB-CONSTRAINT` (the `CHECK`s `allocated_minor + parked_minor + offset_minor ≤
amount_minor` on the item and `allocated_minor + resolved_minor ≤ amount_minor` on the
expectation; deferred Σ triggers on both sides; `UNIQUE (external_item_id, expectation_id) WHERE
reverses_allocation_id IS NULL`; no `UPDATE` or `DELETE` grant on `allocation`, `match_decision`
or `match_candidate`) + `DOMAIN` (every allocation through `allocate(E)`).
**Verify:** Ten-way and lock-bypass races on one batch, leaving at most one positive allocation
per (item, expectation) and no over-allocation; privilege tests for every writer; a raw-SQL
over-allocation refused; snapshot replay `IDENTICAL`.
**Phase:** 8

*(The bound is net of counter-allocations because a repudiated batch leaves its positive
allocation beside the counter-allocation that reverses it, and the genuine re-presented batch
adds another: the positive sum then exceeds the amount while the net, which the Σ triggers hold,
does not. ADR-0068's catalogued text was aligned to this wording by the transition's consistency
review, B7.)*

### INV-REC-08 — A tolerance never absorbs value
**Statement:** Tolerances exist only for comparisons against values the ledger has not posted —
a processing fee against its pinned terms, a date. No tolerance can exist on an amount already in
a ledger position, and every principal difference, down to one minor unit, becomes a remainder on
its expectation or a parked item with its break.
**Why:** A tolerance that absorbs a principal difference is an unrecorded write-off: value leaves
a position with no entry, no reason code and no approver (`INV-BAL-03`, `INV-REC-02`; ADR-0068
§7).
**Enforce:** `DB-CONSTRAINT` (`reconciliation.tolerance.comparison` admits only
`PROCESSING_FEE_PER_LINE`, `PROCESSING_FEE_PER_BATCH` and `SETTLEMENT_DATE_DAYS`: the type has no
amount member) + `DOMAIN` (a request for an amount tolerance refused, `422
reconciliation.ToleranceNotPermitted`).
**Verify:** A one-minor-unit difference raising a break in both directions; an amount-tolerance
row unstorable.
**Phase:** 8

*(As built by `P9-TSK-011` (2026-10-05): reconciliation never converts - `ReconciliationNeverConvertsTest` refuses any `settlement` or `reconciliation` class that can reach an `ExchangeRate`, transitively, with a planted two-hop reach; an FX rate difference is a leg's `AMOUNT_MISMATCH` with cause `FX_LEG_DIFFERS`, and the FX source's v1 carries no amount tolerance.)*

### INV-REC-09 — Every suspense item is owned by exactly one break
**Statement:** Value enters `SUSPENSE_UNMATCHED` only in a transaction that records its owning
break, and leaves only in a transaction that resolves that break, releases the item by evidence —
an unpark or a correction offset — or repudiates the item's batch.
**Why:** It gives `INV-REC-05`'s "never a permanent resting place" an owner for every unit of
parked value; a suspense balance nobody owns is the unrecognised loss or liability that invariant
exists to surface (ADR-0070).
**Enforce:** `DOMAIN` (each of the four openers records the break in its own transaction:
reconciliation's parks, bank recognition's unattributed lines, payments' `UnmatchedConfirmations`
through the port, and a `REPUDIATE_BATCH` approval whose recognition reversal carries the suspense
line of a `BANK_UNATTRIBUTED` item a posting resolution had already released — a new
opposite-side item owned by a new `PROCESSING_ERROR` break raised in the same transaction,
ADR-0070 §2 and §10) + `DB-CONSTRAINT` (`suspense_item.break_id NOT NULL`) + `PROCESS` (the
ownership gauge).
**Verify:** Each poster asserted to commit its suspense item with its break;
`finapp.reconciliation.suspense.unowned` at 0 at rest, after the backfill that adopts Phase 7's
parked confirmations — each keyed on the parking's stored `cause`, `named_reference` and, exactly
when attributed, `attempt_id` (payments `V023`); a probe bypassing the port caught.
**Phase:** 8

*(Built by `P8-TSK-020` for the parkings: the owner of a parking's value is born beside it: `UnmatchedConfirmations.park` calls the port's `parked` after its expectation, the claim winner only, and `app`'s recorder opens, through reconciliation's `ParkedConfirmations`, the CREDIT suspense item (its value, side and `opened_on` read off the parking entry's `SUSPENSE_UNMATCHED` line) and the `UNKNOWN_EXTERNAL` break (`PARKED_ON_RECEIPT`) standing on that item — in one transaction, the owner's suspense-item subject made deferrable by reconciliation `V011` for this opener alone. Verified by `ParkedConfirmationsDatabaseTest` (born together, ten racers owning one parking once) and `UnmatchedConfirmationSuspenseDatabaseTest` over the real door and the backfill.)*

*(The backfill keys on what the Phase 7 → 8 transition's gate repair made each parking record:
an attributed parking, `ATTEMPT_CONCLUDED` or `AMOUNT_MISMATCH`, names the attempt its value
belongs to, and its owning break is resolved by a four-eyes `TRANSFER_TO_ACCOUNT` crediting that
attempt's counterparty, never by a guess. The repudiation is the fourth opener, origin
`REPUDIATION`, admitted by reconciliation `V013` (`P8-TSK-023`), its `origin_ref` the released
item's id — the transition's re-check, R3.)*

### INV-REC-10 — Settlement evidence is screened, encrypted, and every content access audited
**Statement:** A settlement file's raw bytes are held only as AES-256-GCM ciphertext under a key
held outside the database, each chunk's associated data binding the file, its source, its
checksum and the chunk's position (`file_id ‖ source_id ‖ content_sha256 ‖ seq`), and the
whole-plaintext SHA-256 is verified on every read. Card-number and bank-identifier shapes are
refused before anything is stored. Every read of content is audited with its reason. Canonical
settlement records carry references, amounts and dates only.
**Why:** The `INV-KYC-06` and `INV-DSP-03` regime applied to this phase's evidence — bank
statements carry names and counterparty details — and `INV-PAY-02` and `INV-RAIL-03`'s "refused
at the surface" applied to files: a PAN encrypted at rest is still a PAN at rest (ADR-0066).
**Enforce:** `DOMAIN` (the door screen, by field class, before anything is stored — a value
failing its field's declared class screened as free text; one content path,
`POST /v1/operator/settlement/files/{id}/content-reads` with a reason under
`RECONCILIATION_INVESTIGATE`, writing `settlement.SettlementFileContentRead` per read; a checksum
mismatch serves nothing) + `DB-PRIVILEGE` (`SELECT, INSERT` only on chunks, lines, references,
receipts, refusals and ingestion errors, each append-only by trigger) + `STATIC`
(`FINAPP_SETTLEMENT_FILE_KEY`, the twelfth confined credential and one key per concern, held by
`ConfinedCredentialVariablesTest`).
**Verify:** A plaintext needle absent from `settlement.file_chunk` and the captured logs; a
tampered chunk and an AAD swap refused; read audits counted; refusals storing nothing but the
metadata row.
**Phase:** 8

---

# Foreign Exchange — `INV-FX`

### INV-FX-01 — Conversion preserves value through an FX position
**Statement:** A currency conversion posts both legs through an FX position account. No
entry silently changes currency, and total value is preserved across the conversion.
**Why:** A currency change without a balanced counterpart creates or destroys value in one
currency's books.
**Enforce:** `DOMAIN` + `INV-LED-01` (balance per currency).
**Verify:** Per-currency trial balance after high-volume conversion tests.
**Phase:** 9

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0076 (`Proposed`), in force when
`P9-TSK-009` and `-013` land: Enforce gains `STATIC` — `FxBooksHaveOnePosterTest`, only `fx`'s
line composers name `FX_POSITION` and the FX revenue, result and residual purposes — and
Verify gains the FX books proof, every storm round and at rest (`INV-FX-06`).)*

### INV-FX-02 — Rates are server-authoritative and time-bounded
**Statement:** Conversion rates originate from the platform, carry an explicit validity
window, and expired or stale rates are rejected. Client-supplied rates are never trusted.
**Why:** Client-controlled rates are a direct financial exploit.
**Enforce:** `DOMAIN`.
**Verify:** Expired-quote and stale-rate rejection tests.
**Phase:** 9

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0075 (`Proposed`), in force when
`P9-TSK-005` and `-008` land: "server-authoritative" is a plausibility band against an
independent, fresh reference source, failing closed when the reference is stale; the validity
window is computed from durations on the database clock —
`expires_at = least(requested_at + provider_valid_for − cover_margin, issued_at + window)` —
so neither provider skew nor network time can lengthen it; and a client-supplied rate or
amount at acceptance is *refused* (`422 VALIDATION_FAILED`), never ignored.)*

### INV-FX-03 — Spread is recognised explicitly
**Statement:** Any margin between the sourced rate and the customer rate is posted as
revenue explicitly, not concealed inside the applied rate.
**Why:** Hidden margin is unreportable and unauditable revenue.
**Enforce:** `DOMAIN`.
**Verify:** Posting-composition tests.
**Phase:** 9

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0074 (`Proposed`), in force when
`P9-TSK-002` and `-009` land: spread and markup post as **one** `FX_SPREAD_REVENUE` line,
attributed to the two by `Money.allocateByWeights`, and the attribution is stored on the
trade — a fact, not a second line, so fewer rounding terms tighten the residual bound.)*

### INV-FX-04 — An FX quote is a frozen posting plan, executed at most once
**Statement:** An FX quote is a frozen posting plan, balanced per currency. It is executed at
most once, only while valid on the database clock, only by its owner, and the trade posts
exactly the quote's stored amounts.
**Why:** A plan re-priced at execution can create or destroy value between what the customer
accepted and what posts; a plan executed twice converts once-accepted funds twice.
**Enforce:** `DB-CONSTRAINT` (the per-currency plan-identity `CHECK`, the freeze and edge
triggers, `UNIQUE (fx.trade.quote_id)`) + `DOMAIN`.
**Verify:** A plan refusal by a raw-SQL writer; ten-way and two-context races; the expiry race
in both orders.
**Phase:** 9

### INV-FX-05 — Every executed conversion carries its complete, frozen rate provenance
**Statement:** Every executed conversion carries its complete, frozen rate provenance —
provider, provider quote, obtained-at, reference, policy version, rates, margins, amounts and
rounding names — and replaying it reproduces its posted entry exactly; its internal rate and
disclosed margin are each derived with a named rounding, never left to a column.
**Why:** A conversion whose price cannot be reproduced from its own stored facts cannot be
defended to a customer, an auditor or a regulator — `INV-HIST-04` pointed at pricing.
**Enforce:** `DB-CONSTRAINT` (`NOT NULL` + the freeze trigger) + `DOMAIN`.
**Verify:** Golden replay; `FxPlanVerification` (report-only, a divergence CRITICAL); a
perturbation demonstration; the 10-decimal property test on every pair.
**Phase:** 9

*(As built by `P9-TSK-013` (2026-10-05): `FxPlanVerification` replays every booked trade from its quote's frozen inputs through `ConversionPlan.compute` - the trade's amounts, the customer and internal rates, the disclosed margin against the stored reference, and the posted entry line by line; a divergence is CRITICAL and `finapp.fx.plan.verdict` reads 0. A perturbed stored rate flips it (`FxProofDatabaseTest`).)*

### INV-FX-06 — The FX books are explainable and have one poster
**Statement:** Per currency, `FX_POSITION` equals the sum of its open legs and is zero at rest
once covers execute. Spread, residual and realised results equal their trades' and executions'
stored parts. These books have one poster and accept no free adjustment.
**Why:** An FX position that cannot be decomposed into named open legs is an unexplained
currency exposure; a book any module may post to stops being evidence (`INV-BAL-01`'s
discipline for the platform's own currency risk).
**Enforce:** `STATIC` (one poster, `FxBooksHaveOnePosterTest`) + `DB-CONSTRAINT` (the
`closedToFreeAdjustments()` binding trigger) + `DOMAIN`.
**Verify:** The FX books proof every storm round and at rest; a planted raw line flips it.
**Phase:** 9
*(The unwind's half in force since `P9-TSK-021` (2026-10-06): an executed cover whose quote is abandoned is
unwound exactly once, its entry closing the cover's position legs reversed with one realised line, so
`FX_POSITION` returns to zero and the books proof holds at rest. Verified by `UnwindRetryDatabaseTest`.)*

*(As built by `P9-TSK-013`: `FxBooksProof` per book and currency, `finapp.fx.proof{purpose}` the failing currencies; a raw line planted on each of the five books flips that book alone (`FxProofDatabaseTest`); `FxBooksHaveOnePosterTest` permits the proof as the books' one reader.)*

### INV-FX-07 — The rounding residual is bounded and posted
**Statement:** A conversion's residual is computed from stored amounts, bounded — |r| ≤ 2
minor units by `CHECK`, ≤ 1 under half policies by the domain — and posted to
`ROUNDING_RESIDUAL` in its own currency, never folded into margin, the customer amount or the
position.
**Why:** `INV-BAL-03` at conversion scale: an absorbed residual is value creation or
destruction hidden inside rounding, and an unbounded one is a mispricing detector switched
off.
**Enforce:** `DB-CONSTRAINT` + `DOMAIN`.
**Verify:** Property tests; the value battery's residual identity.
**Phase:** 9

### INV-FX-08 — A provider FX execution is idempotent at the provider and closes exactly its plan
**Statement:** A provider FX execution carries a reference stored before sending; it is
re-sent only under that reference; a new reference is minted only after a definitive
rejection; and a cover always closes exactly its plan's position legs, the difference posted
as realised FX result.
**Why:** `INV-PAY-04` pointed at the FX provider: without it, the recovery path for an unknown
outcome — retry or requote — is itself a double-trade mechanism, and a cover that closes
anything but the plan leaves `FX_POSITION` unexplainable.
**Enforce:** `DB-CONSTRAINT` (`UNIQUE client_reference` per attempt, the conditional attempt
edge, the `fx.cover_execution` PK) + `DOMAIN`.
**Verify:** Response-lost, unrecognised and lock-expiry tests; simulator execution counts.
**Phase:** 9

*(As built by `P9-TSK-012` (2026-10-05): `fx V007`'s `cover_execution` - PK `cover_id`, `UNIQUE (provider_code, provider_trade_ref)`, the plan's legs copied from the quote and checked at birth, realised = executed - plan and `executed_off_plan` by `CHECK`; the cover trigger admits `-> EXECUTED` only with the fact and `REJECTED -> DISPATCHED` only once attempt n+1's reference is stored; `CoverLines` closes exactly the plan, the difference to `FX_REALISED_GAINS`/`LOSSES` (ledger `V023`). Proven by `FxCoverDatabaseTest` (scenarios 3 and 4, the same-`T` re-send), `FxCoverRaceDatabaseTest` and `CoverLinesTest`; the probes in `MUTATION_TESTING.md` section 2.)*

### INV-FX-09 — A booked conversion never waits on a provider
**Statement:** A customer's booked conversion never waits on, and is never changed by, a
provider outcome.
**Why:** The platform is principal (ADR-0076): a provider's `UNKNOWN`, failure or re-pricing
reaching a customer balance would make the customer's money depend on a counterparty's
behaviour.
**Enforce:** `STATIC` (no provider port reachable from the conversion transaction) + `DOMAIN`.
**Verify:** Cover-failure tests leaving customer lines byte-identical.
**Phase:** 9

*`INV-FX-04`…`INV-FX-09` catalogued by the Phase 8 → 9 transition (2026-10-02), the `INV-SET`
and `INV-REC` precedent: Phase 9's gate properties given stable IDs before any FX code
exists, so the register can demand their demonstrations by identifier rather than by prose.
Decisions in ADR-0074…0077 and ADR-0082. `INV-FX-01`…`-03`, catalogued at initiation and
subjectless until now, are Phase 9's too, and are amended at the same transition, each with
its provenance. Until Phase 9's first task lands, nothing these entries name is implemented;
every statement is the decided design, corrected by the tasks that build it.*

---

# Cross-Border Payments — `INV-XB`

*(As built by `P9-TSK-012`: every cover suite asserts the conversion's entry byte-identical before and after the cover's outcome, a requote's realised loss included.)*

### INV-XB-01 — A cross-border payment debits once, at acceptance, or not at all
**Statement:** A cross-border payment is priced by one accepted quote, holds the customer's
funds until the corridor provider accepts, then posts its debit, conversion, fee and clearing
credit in one entry, once. A payment that fails debits the customer nothing.
**Why:** Debit-then-reverse turns every provider failure into customer-visible financial
history; a double posting pays the beneficiary once and charges the customer twice.
**Enforce:** `DB-CONSTRAINT` (`UNIQUE (crossborder.payment.quote_id)`, the claim PK, the
posting key) + `DOMAIN`.
**Verify:** Response-lost, retry, callback, ten-applier and failure tests.
**Phase:** 9
*(In force since `P9-TSK-020` (2026-10-06): the completion posts the debit, the conversion, the fee and the clearing
credit in one entry under `outbound-credit:<id>`, behind the locked row's acting edge, the claim's primary key and the
posting key; a failure releases the hold and posts nothing. Verified by `OutboundCreditResolutionDatabaseTest` - ten
appliers and a racing sweep, a lost response, ten callbacks, a rejection and `NEVER_RECEIVED`.)*
*(The cancellation's half in force since `P9-TSK-024` (2026-10-06): a cancelled payment debits nothing - only the
provider's definitive `RECALLED` concludes it, through the same failure path (the hold released, nothing posted), and a
recall refused as too late leaves the one completion to post as ever; a recall racing the acceptance ends in exactly
one of the two. Verified by `CrossBorderCancellationDatabaseTest`, scenarios 29 to 31.)*

### INV-XB-02 — Funds move only toward a screened, payable beneficiary
**Statement:** Funds are instructed, and offers priced, only for a beneficiary that is
`ACTIVE` with a current `CLEAR` or person-`RELEASED` screening, on an available corridor. An
automatic `CLEAR` exists only where the provider's payee check matched; a hit, an
indeterminate result or an unverified payee is never auto-cleared and never auto-rejected.
Every non-payable state is refused with the same response.
**Why:** Instructing funds toward an unscreened or blocked counterparty is a sanctions breach
executed by software; and a refusal that varies by reason tips off the holder of a blocked
destination (`INV-KYC-04` extended, ADR-0081).
**Enforce:** `DOMAIN` (judged in-lock at the quote's Tx1 and the authorization's Tx1) +
`DB-CONSTRAINT` (the screening's decision-basis `CHECK`s: a person with a reason for
`REVIEWER`; no `AUTOMATIC` `CLEAR` without a payee `MATCH`).
**Verify:** Each non-payable state at both doors; a lapsed clearance re-screened, or refused
`409 ScreeningRequired`; a counted ten-way race of Tx1 against a concurrent screening hit; an
unverified payee with a clear name; byte-identical refusals across screening, review, block
and revocation (`P9-TSK-017`, `-018`, `-019`).
**Phase:** 9

*(Partly in force since `P9-TSK-017` (2026-10-06): the beneficiary is `ACTIVE` only on a cleared or
person-released screening, moved in kyc's deciding transaction (T-e); a `NO_MATCH` payee with a clear
name goes `IN_REVIEW`, never `ACTIVE` without a person; a revoked beneficiary is never moved; the read
and the revocation answer byte-identically across screening, review and block. The doors that move
money - the quote and the authorization - judge payability from `-018` and `-019`.)*

*(The quote's half in force since `P9-TSK-018` (2026-10-06): payability judged in the quote's Tx1 with
the beneficiary `FOR SHARE` - the owner's, `ACTIVE`, clear, on an available corridor its issuing rail
serves - and one byte-identical `422 crossborder.BeneficiaryNotPayable` for every other state; a
clearance older than the corridor's screening validity re-screened between the transactions, a hit, an
indeterminate answer or an unverified payee moving the beneficiary `IN_REVIEW` in kyc's T-e and refusing
with nothing priced. Verified by `CrossBorderOfferDatabaseTest`.)*

*(The authorization's half in force since `P9-TSK-019` (2026-10-06): payability re-judged in the
authorization's one transaction with the beneficiary `FOR SHARE` - a move committed while it waits is
seen and refused - the same byte-identical `BeneficiaryNotPayable` for a beneficiary in review, blocked or
revoked since its offer, and a clearance lapsed since the offer `409 crossborder.ScreeningRequired`;
nothing accepted, held or sent. Verified by `CrossBorderPaymentDatabaseTest`.)*

### INV-XB-03 — What was shown is what is held, posted and instructed
**Statement:** What the customer was shown — destination amount, fee, total debit — is
exactly what is held, posted and instructed.
**Why:** Any drift between disclosure and execution is a silent re-pricing of a customer who
already accepted a price.
**Enforce:** `DB-CONSTRAINT` (the offer's amounts copied onto the payment and the outbound
credit, frozen by trigger) + `DOMAIN`.
**Verify:** The disclosure-to-posting test: offer = hold (`P9-TSK-019`) = completion posting =
instruction (`P9-TSK-020`).
*(The hold's half in force since `P9-TSK-019` (2026-10-06): the accepted quote's amounts are asserted
equal to the frozen offer inside the authorization, the hold is the offer's total debit and the outbound
credit instructs the offer's guaranteed destination amount, all frozen at birth. Verified by
`CrossBorderPaymentDatabaseTest`.)*
*(The posting's half in force since `P9-TSK-020` (2026-10-06): the completion entry's lines are checked against
the offer by crossborder and against the held and instructed amounts by payments before posting - the wallet
debited exactly the total debit, the corridor's clearing credited exactly the instructed amount. Verified by
`OutboundCreditResolutionDatabaseTest#theCompletionPostsExactlyAndOpensItsExpectation`, line for line.)*
**Phase:** 9

### INV-XB-04 — A return is applied once, automatically only when it is exact
**Statement:** A return is applied once. It is applied automatically only when it is exactly
the instructed credit coming back — the instructed currency and amount — credited in that
currency and never re-converted at the original rate, with the fee refunded. Any other return
is never posted automatically: it parks with its break, and a person's resolution is the only
way it reaches the customer, recording the return on the payment in the same transaction.
**Why:** A partial return credited automatically could never raise a break — the provider's
line and our posting would agree — so the customer would silently bear an intermediary's
deduction under a promise of `OUR`; a re-converted return meets a rate the customer never
accepted; a return applied twice credits twice.
**Enforce:** `DB-CONSTRAINT` (`UNIQUE (outbound_credit_return.outbound_credit_id)`, the claim,
the applier-amount trigger, the `applied_by`/`resolution_id` `CHECK`) + `DOMAIN`.
**Verify:** Return tests in every ordering, before the completion is known included; ten
redeliveries; partial and other-currency returns parked; the resolution racing an
inquiry-applied return.
*(In force since `P9-TSK-023` (2026-10-06): payments `V027`'s `UNIQUE (outbound_credit_id)`, the
`applied_by`/`resolution_id` `CHECK` and the applier-amount trigger, and the one applier's rule judged on
the locked credit - exact `Money`, `COMPLETED`, an `ACTIVE` customer - on both channels; a parked return
reaches the customer only through the four-eyes transfer, which records the return in its approval.
Verified by `CrossBorderReturnDatabaseTest`: section 12.4(i) line for line, the report channel, the
return before the completion, ten concurrent appliers, the partial, other-currency and suspended-customer
returns parked, the resolution, and the resolution losing to an inquiry-applied return.)*
**Phase:** 9

*`INV-XB-01`…`INV-XB-04` catalogued by the Phase 8 → 9 transition (2026-10-02), on the same
precedent — a new group, because a cross-border payment's properties are not
foreign-exchange properties: they bind the hold, the beneficiary, the corridor and the
return, not the rate. Decisions in ADR-0079…0081. Until Phase 9's first task lands, nothing
these entries name is implemented; every statement is the decided design, corrected by the
tasks that build it.*

---

# Accounting and Reporting — `INV-ACC`

### INV-ACC-01 — Trial balance is zero per currency
**Statement:** Across all postings, total debits equal total credits for every currency, at
all times.
**Why:** The system-level expression of `INV-LED-01`; the primary continuous correctness
signal.
**Enforce:** `DOMAIN` (per entry) aggregated system-wide.
**Verify:** Continuous job with alerting; gate criterion F1.
**Phase:** 3, 9 (per currency), 14 (reported)

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0074 (`Proposed`), in force when
`P9-TSK-003` lands: "every currency" becomes five at three scales — EUR, GBP and USD at 2
minor units, JPY at 0, BHD at 3 — the minor units pinned by a test and a startup guard, and
the per-currency trial balance and `finapp.ledger.trial.balance{currency}` eager for all
five.)*

### INV-ACC-02 — Every reported figure drills down to postings
**Statement:** Any figure in any financial report traces to the individual journal lines
composing it.
**Why:** A report that cannot be substantiated is not auditable.
**Enforce:** `DOMAIN` (derived read models only).
**Verify:** Drill-down tests from report to lines.
**Phase:** 14

### INV-ACC-03 — Closed periods reject postings
**Statement:** No posting is written into a closed accounting period. Corrections use a
prior-period adjustment in an open period.
**Why:** Back-dated postings change reports that have already been issued.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT`.
**Verify:** Closed-period rejection tests; prior-period adjustment tests.
**Phase:** 14

### INV-ACC-04 — Reports are reproducible
**Statement:** Regenerating a report for a past period produces identical output, and the
originally issued output is retained.
**Why:** Two different answers to the same question destroys confidence in all of them.
**Enforce:** `DOMAIN` (immutable report runs) + `INV-HIST-04` (mapping version pinning).
**Verify:** Regeneration comparison tests.
**Phase:** 14

### INV-ACC-05 — Unreconciled positions are disclosed
**Statement:** Open breaks and suspense balances are explicitly disclosed at period close,
never netted away or omitted.
**Why:** Hiding unreconciled positions is the mechanism by which small breaks become
material losses.
**Enforce:** `PROCESS` + `DOMAIN`.
**Verify:** Close-report content tests.
**Phase:** 14

---

# Security and Audit — `INV-AUD`

### INV-AUD-01 — Privileged actions are audited
**Statement:** Every financial and administrative action of consequence produces an audit
record containing actor, time, operation, target, reason (where applicable), correlation and
outcome.
**Why:** `CLAUDE.md` §Security and Audit.
**Enforce:** `DOMAIN` + auditable-action registry.
**Verify:** Audit completeness verification against the registry (Phase 15 gate).
**Phase:** 0 onward

### INV-AUD-02 — Sensitive data never enters logs, events or responses
**Statement:** Credentials, tokens, PANs, sensitive authentication data and unnecessary PII
never appear in application logs, event payloads or API responses.
**Why:** `.claude/rules/security.md`; the most common severe fintech incident class.
**Enforce:** `DOMAIN` — default-deny redaction.
**Verify:** Log redaction tests; event payload review.
**Phase:** 0

### INV-AUD-03 — Authorisation is explicit for privileged financial actions
**Statement:** Every privileged financial action has an explicit authorisation check with a
passing negative test.
**Why:** Least privilege is only real if it is tested from the attacker's direction.
**Enforce:** `DOMAIN`.
**Verify:** Negative authorization tests per endpoint.
**Phase:** 1 onward

### INV-AUD-04 — Four-eyes on high-consequence actions
**Statement:** Manual adjustments, break resolutions above threshold, policy activations,
period close, payout destination changes and risk overrides require a second authorised
approver distinct from the initiator.
**Why:** Single-actor control over financial correction is an unacceptable internal-fraud
exposure.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (approver ≠ initiator).
**Verify:** Self-approval rejection tests.
**Phase:** 3, 6, 8, 10, 13, 14

*(The list read "3, 8, 10, 13, 14" until the 2026-09-21 transition into the checkout phase:
the statement has named "payout destination changes" — that phase's subject — since this
row was written, while the phase list omitted it. The note lives on its own line because the
register guard token-parses the Phase line — prose digits there would change which phases
demand this row.)*

*(Implemented subjects: **manual adjustments** first, by `P3-TSK-021` (ledger `V010`), and
**payout destination changes** second, by `P6-TSK-011` (merchant `V006`, ADR-0056) — the same
shape both times: two authenticated acts, approver ≠ initiator as a `CHECK`, a frozen payload,
lock-then-look approval. This note read "the first implemented four-eyes subject is
`P6-TSK-011`" until that task's design found adjustments had already been one since Phase 3.)*

*(Amended at the Phase 7 → 8 transition, ADR-0066, ADR-0068 and ADR-0071 (`Proposed`): Phase 8
adds four subjects, none implemented until its task lands. **Upload attestation** (`P8-TSK-003`,
`INV-SET-07`): an uploaded settlement file is inert until a second holder of `SETTLEMENT_INGEST`
attests it, `CHECK (attested_by IS NULL OR attested_by <> received_by)`; the readmission of an
original never attested is attested the same way, by a person distinct from the readmitter and
from the original's uploader (`P8-TSK-022`, ADR-0066 §8). **Break resolutions** (`P8-TSK-015`):
the statement's "above threshold" is defined as any value at issue or any posting — a zero-value
`ACKNOWLEDGE` of a `TIMING_DIFFERENCE` raised by a timing detector alone is single-person (corrected 2026-10-01, `P8-TST-002`), and `EVIDENCED` is the platform's alone — refused at three
ranks: the domain, the `reconciliation.resolution` four-eyes `CHECK` and ledger `V010`'s. **Batch
repudiation** (`P8-TSK-023`, ADR-0065 §10): a `REPUDIATE_BATCH` resolution under the same rule.
**Rule-set activation** (`P8-TSK-022`): one of the statement's policy activations, refused when
the activator proposed it (`CHECK decided_by <> proposed_by` on an `ACTIVE` rule set).
Person-distinctness is by actor id; resolution rows hold both ids, so the recorded debt of
operators audited as `CUSTOMER` does not weaken it.)*

*(As built by `P9-TSK-015` (2026-10-05): the corridor policy and the corridor enable request are four-eyes at
every rank - the domain refuses the proposer's approval, and crossborder `V002`'s `CHECK`s refuse it for a raw
writer, with no seed exemption, so no corridor is offered without two named persons (`CorridorPolicyDatabaseTest`,
`CorridorAvailabilityDatabaseTest`, `CorridorAdministrationEndpointDatabaseTest`; the probes in
`MUTATION_TESTING.md` §2).)*

---

# Credit and Decisioning — `INV-CRD`

### INV-CRD-01 — Decisions are reproducible
**Statement:** Replaying a decision's recorded inputs against its pinned policy and model
versions produces the identical outcome and reason codes.
**Why:** `CREDIT_MODEL.md`; a decision that cannot be reproduced cannot be defended to a
regulator or a declined applicant.
**Enforce:** `DOMAIN` (input snapshotting) + `INV-HIST-04`.
**Verify:** Replay tests.
**Phase:** 10

*(Phase 10 subject named by the Phase 9 → 10 transition, 2026-10-07: the recorded inputs are the
decision's sealed `decision_snapshot` (`INV-CRD-07`); the pinned versions are the credit policy
version, the scorecard model version and the evaluator's `engine_version`, an old engine kept in
the code for replay. Measurable as: `CreditReplayProof` replays every decision per reading in one
`REPEATABLE READ` snapshot, comparing outcome, approved amount and ordered reason codes and
re-verifying the snapshot's hash, with `finapp.credit.replay{verdict}` alerting on any
`DIVERGED`; every decision of `P10-TST-002`'s ≥ 10,000 generated applicants and of the storm
replays `IDENTICAL`; a perturbed snapshot or rule row flips the verdict. Built by `P10-TSK-013`
and `-019`, proven by `P10-TST-001` and `-002`; ADR-0086, ADR-0087.)*

### INV-CRD-02 — Decisions are immutable and carry reason codes
**Statement:** A recorded decision is never modified, and every adverse decision carries
reason codes sufficient for an adverse-action explanation.
**Why:** Regulatory explainability.
**Enforce:** `DB-PRIVILEGE` + `DB-CONSTRAINT`.
**Verify:** Immutability tests; reason-code coverage tests.
**Phase:** 10

*(Phase 10 subject named by the Phase 9 → 10 transition, 2026-10-07: the recorded decision is
the born-once `credit_decision` row with its ordered `credit_decision_reason` rows, a person's
decision on a referral included — there is no `CreditDecisionUpdated`, and a change of mind is a
new request. Measurable as: `UPDATE` and `DELETE` on the decision tables refused for every role,
by privilege and by an every-writer trigger, each proven alone by a raw-SQL writer; an adverse
outcome with no reason row unstorable and unrepresentable in the domain; every code of the
closed `reason_code` catalogue carrying a category, an adverse flag and a customer text, every
code exercised by `P10-TST-002`; the customer's read returning the adverse reasons' customer
texts in order and no internal figure. Built by `P10-TSK-001`, `-013`, `-016` and `-017`;
ADR-0087.)*

### INV-CRD-03 — Bureau access requires recorded consent
**Statement:** No external credit data is retrieved without a recorded, current lawful basis.
**Why:** Consent is a legal precondition, and it is distinct from authentication and
authorization.
**Enforce:** `DOMAIN`.
**Verify:** Consent-absent rejection tests.
**Phase:** 10

*(Phase 10 subject named by the Phase 9 → 10 transition, 2026-10-07: the lawful basis is a
current grant of `CREDIT_BUREAU_ACCESS` or `FINANCIAL_DATA_ACCESS` (consent `V003`), read
authoritatively in the transaction that opens a data request and again in the one that records
its answer. Measurable as: a submission without the purpose refused `409 consent.ConsentRequired`
with every provider's pull count unchanged; a withdrawal between the ask and the record ending
`CONSENT_WITHDRAWN` with the payload discarded unread and its decision request `ABANDONED`
(reason `CONSENT_WITHDRAWN`), nothing decided; every pull audited as
`credit.BureauDataRequested` or `credit.FinancialDataRequested`. Built by `P10-TSK-002`, `-006`
and `-014`; ADR-0085.)*

*(Corrected at the Phase 10 exit review, `P10-DOC-001`, 2026-10-09: the refusal read
`403 credit.ConsentRequired`, a code credit never defined. As built, `CreditDecisionRequestDesk`
raises the platform's one consent refusal — `ConsentNotGrantedException`, mapped to
`409 consent.ConsentRequired` naming the purpose, the `accounts.AccountOpeningRefused` shape. The
gate is read at submission, at the data request's opening, at every retry, at the recording, at
the freeze and in the deciding transaction.)*

### INV-CRD-04 — Score is not decision
**Statement:** A credit score, risk score, decision and outcome are separately modelled and
separately recorded.
**Why:** `CLAUDE.md` §Domain Distinctions.
**Enforce:** `DOMAIN`.
**Verify:** Domain review; model inspection.
**Phase:** 10

*(Phase 10 subject named by the Phase 9 → 10 transition, 2026-10-07: the credit score is the
scorecard's points on the `credit_assessment`; the risk score is `risk`'s (Phase 13, ADR-0084),
and credit records only the `RISK_SIGNAL` attribute its `CreditRiskSignal` seam answered —
`NOT_ASSESSED` throughout Phase 10, with the seam's version; the evaluation is the
`policy_evaluation`; the decision and its outcome are the `credit_decision`. Measurable as: each
in its own born-once table, none written by updating another; a person's decision differing
from its evaluation, with both kept; no score, signal or threshold in a customer response. Built
by `P10-TSK-001`, `-011`, `-013`, `-016` and `-018`; ADR-0084.)*

### INV-CRD-05 — A policy or model version is immutable, and its active period is answerable
**Statement:** A credit policy version and a scorecard model version are immutable once
proposed: their rules and bands are born with the version, in its proposing transaction, and no
writer changes them — a correction is a rejection and a new proposal. At every instant at most
one version is `ACTIVE` per product (per model family), and which version was active at any past
instant is answerable from the rows alone.
**Why:** A decision is defended by the versions it pinned (`INV-HIST-04`). A version changed
after a decision pinned it, or two versions active at once, makes "which policy decided this?"
unanswerable — the first question of an adverse-action review and of a regulator.
**Enforce:** `DB-CONSTRAINT` (the freeze trigger on the version; an every-writer trigger refusing
a rule or band row inserted outside its version's proposing transaction, and any `UPDATE` or
`DELETE` of one, from insert; one `PROPOSED` and one `ACTIVE` partial uniques; retirement only
beside its successor by a deferred constraint trigger, the predecessor's `effective_to` equal to
the successor's `effective_from`; both stamped by the database and clamped never to fall behind the
version before them, so a database clock that steps back neither refuses an activation nor
overlaps two periods (`X-TSK-017`, `credit V014`); the approver ≠ proposer `CHECK`, no seed
exemption) + `DOMAIN`.
**Verify:** A rule or band changed, deleted or added later by a raw-SQL writer refused, a
`PROPOSED` version's included; ten racing approvers leave one `ACTIVE`; the active-at-instant
query over a generated history; an activation mid-decision leaving the pinned version deciding;
an activation on a database clock behind its predecessor's start committing, the periods meeting
(`CreditPolicyVersionDatabaseTest`, `ScorecardVersionDatabaseTest`
`#anActivationSurvivesADatabaseClockBehindItsPredecessor`); the pin's share locks — an
activation's row lock (`FOR NO KEY UPDATE`) on the `ACTIVE` policy or scorecard row makes the
production `SUBMITTED -> COLLECTING` step wait at its share statement, and on the pinned rows the
deciding transaction (`CreditDecisionDatabaseTest#theCollectingStepSharesTheActivePolicy`,
`#theCollectingStepSharesTheActiveScorecard`, `#theDecidingTransactionSharesThePinnedPolicy`,
`#theDecidingTransactionSharesThePinnedScorecard`).
**Phase:** 10

### INV-CRD-06 — A decision request is decided once, on one basis
**Statement:** A decision request has at most one decision; each of its snapshots has at most
one assessment and one evaluation, and the decision names the snapshot it was made from.
**Why:** Two decisions for one request are two answers to one applicant, and an assessment or
evaluation that is not the one of its snapshot leaves a decision's basis ambiguous. A request may
hold a successor snapshot — frozen by the deciding transaction only when it finds the reserved
exposure changed — so the decision must say which snapshot decided. Retries, duplicate events
and ten sweepers are expected (`CLAUDE.md`), so "once" must be a constraint, not a hope.
**Enforce:** `DB-CONSTRAINT` (★ `UNIQUE (decision_request_id)` on the decision, its snapshot
reference `NOT NULL` and of the same request; `UNIQUE (decision_request_id, sequence)` on the
snapshot; `UNIQUE (snapshot_id)` on the assessment; `UNIQUE (assessment_id)` on the evaluation)
+ `DOMAIN` (the conditional transitions).
**Verify:** Ten progress sweepers per request, and a crash after each step re-driven by another
instance, each counted one; a successor snapshot only where the reserved exposure changed, and
the decision naming it; a lock-bypass probe per arbiter.
*Since the Phase 10 to 11 transition* (2026-10-10, which found "of the same request" enforced by
nothing - the decision's, the assessment's and the evaluation's input keys on the input's id alone,
their copied hash and pins unchecked): composite foreign keys `(snapshot_id, decision_request_id)`,
`(assessment_id, decision_request_id)` and `(basis_evaluation_id, decision_request_id)` hold each row
to its own request's input, and credit `V017`'s birth triggers seal the assessment's hash and pins to
its snapshot, the evaluation's policy and engine to its assessment, and the decision's hash and pins
to its request's LATEST snapshot and (once pinned) the request's versions; the replay's seal checks
the snapshot is the decision's own request's (`Divergence.HASH`)
(`UnderwritingCaseDatabaseTest#aDecisionRestsOnItsOwnRequestsSnapshot`,
`DecisionReplayDatabaseTest#aDecisionOnAnotherRequestsSnapshotDivergesByHash`).
**Phase:** 10

### INV-CRD-07 — A decision's snapshot is complete and sealed
**Statement:** Every attribute any rule, the scorecard or the affordability and exposure
arithmetic read for a decision is in its snapshot with its provenance — a credit record and its
source, `DECLARED`, or a port and its version. An attribute the snapshot lacks is an evaluation
error, never a default, and the SHA-256 over the snapshot's canonical form is stored at the
freeze and re-verified at every replay.
**Why:** A decision reproducible only from data that may since have changed is not
reproducible (`INV-CRD-01`), and a default standing in for a missing input is an undisclosed
input the applicant was decided on.
**Enforce:** `DOMAIN` (the evaluator reads only the snapshot; a missing attribute raises) +
`DB-CONSTRAINT` (the snapshot insert-only, its hash `NOT NULL`).
**Verify:** Replay re-verifying the hash, a tampered snapshot `DIVERGED`; a rule reading an
attribute the snapshot lacks failing evaluation; canonical-form golden files. *Since
`P10-TSK-021`*: a record's source names the provider its data request was born naming - the
request, its audit, its record and its event name one provider, and an answer naming another is
never recorded (`BureauSelectionDatabaseTest#theRecordNamesItsProvider`,
`#anAnswerNamingAnotherProviderIsNeverRecorded`).
**Phase:** 10

### INV-CRD-08 — Stale data never decides
**Statement:** Every credit record a snapshot uses was retrieved within the pinned policy's
declared maximum age for its source kind, judged on the database clock at the freeze **and again
at the decision**. A record past it is never used: at the freeze it is re-collected once if it was
fresh when recorded, else (already stale when recorded, or stale a second time on one request) its
source kind is unavailable for the request and the policy's fallback decides (`INV-CRD-10`); past
it at the decision, nothing is decided on it.
**Why:** A decision on an out-of-date bureau file decides a different applicant from the one
who applied; and an instance clock judging the age lets skew admit stale data or refuse fresh.
**Enforce:** `DOMAIN` (judged against `DatabaseTime.now` in the freezing transaction). The age
runs from the earlier of the provider's stated retrieval and our own recording —
`LEAST(retrieved_at, recorded_at) >= transaction_timestamp() - max age`
(`JdbcDecisionSnapshotStore`), so a provider clock ahead of ours, or a re-stamped file, never
keeps stale data fresh. *(Added at the Phase 10 exit review, `P10-DOC-001`, 2026-10-09, which
found the age judged on `retrieved_at` alone — the provider's word — and corrected it.)*
Re-collection is bounded (`SnapshotFreezer`, ADR-0085 §11): only a record with `retrieved_at >=
recorded_at − max age`, and only the kind's first data request on the decision request, re-collects.
*(Added at the Phase 10 → 11 transition, 2026-10-10, whose audit found a provider stamping every
report past the age looping paid pulls until the request expired.)*
The re-judgement at the decision *(added at the Phase 10 to 11 transition, 2026-10-10, which
found a taken case decidable on its basis's records however long it was held, and a successor
snapshot copying them unjudged)*: the deciding transaction re-judges every record the deciding
snapshot froze, on the same expression (`DecisionSnapshotStore.staleRecords`), before it writes
anything - a person is refused `422 credit.DataStale` with nothing recorded (the case is released
and expires with its request), the system decides nothing (the request waits for its expiry), and a
successor refuses to copy a stale record (`SnapshotFreezer.RecordsStale`).
**Verify:** A record that aged one second past the maximum age re-collected; a record stale when
recorded, and a second staleness, frozen unavailable with no further data request
(`DecisionSnapshotDatabaseTest`, `DecisionOrchestrationDatabaseTest#aProviderStampingEveryReportStaleIsUnavailableNotALoop`);
an instance skewed ±5 s neither accepting stale data nor refusing fresh; a record aged past the
maximum between a referral and its person's decision refusing approval and decline alike, and the
system's decision, and a successor (`UnderwritingCaseDatabaseTest#nothingIsDecidedOnStaleData`).
**Phase:** 10

### INV-CRD-09 — Decisions for one party are serialised on its exposure
**Statement:** Every deciding transaction for a party holds that party's `credit_profile` row
lock and re-reads the party's reserved exposure **and outstanding platform credit** under it, so
concurrent approvals — across products, and beside a person's approval — never together exceed the
policy's exposure limit. An exposure that cannot be assessed — the bureau's total balance absent —
is never approved, by the platform or by a person: **no exception**.
**Why:** Exposure judged outside a lock is a limit two requests each pass alone and break
together — `INV-CON-03`'s lesson pointed at credit.
**Enforce:** `DOMAIN` (lock-then-look: the profile row `FOR UPDATE` first in every deciding
transaction, the reserved exposure summed under it). The evaluator judges exposure only through
rules, so the policy proposal door refuses `422 credit.PolicyIncomplete` a policy with no rule
guaranteed to stop an approval past its maximum exposure — `EXPOSURE_HEADROOM` `LT`/`LE` a
non-negative amount, or `EXPOSURE` `GT`/`GE` at most the limit, with a non-approving effect
(`CreditPolicy.boundsExposure`, `PolicyRule.refusesExposurePast`), judged at proposal so a stored
version always reads back. *(Added at the Phase 10 exit review, `P10-DOC-001`, 2026-10-09, which
found a policy without such a rule admitted, and so able to approve past its own limit.)*
*(Amended at the Phase 10 to 11 transition, 2026-10-10.)* Three holes found and closed: (1) the
deciding transaction re-read the reservation alone and a successor copied the first freeze's
outstanding credit, so once Phase 11 consumes an approval into a loan - reserved down, outstanding up
by the same amount - the decision would judge the limit on a stale outstanding figure; both terms are
now re-read under the profile lock and a successor replaces both, and **Phase 11's loan /
consumption writer must hold the party's `credit_profile` row `FOR UPDATE`** in the transaction that
moves the exposure from reserved to outstanding. (2) A person's exposure check counted an absent
bureau balance as zero; by the owner's decision of 2026-10-10 a person's approval on an
unassessable exposure is refused `422 credit.ExposureUnassessable` - they may only decline. The
recorded consequence: production, which has only fail-safe sources, can approve nothing until a
real bureau is connected. (3) The person-versus-system race asserted a count that could not fail;
it now asserts both orders occur, and a lock-wait test proves the person's deciding transactions
wait on the profile lock.
**Verify:** Two products for one party at the limit raced a hundred rounds, both deciders observed
waiting at the profile lock together in every round, the second seeing the first's reservation
(`CreditDecisionDatabaseTest#twoProductsAtTheExposureLimitSerialise`); the storm's exposure census,
no party's reserved exposure above its limit at rest; an outstanding figure moved between the
evaluation and the decision seen by both deciders
(`UnderwritingCaseDatabaseTest#theOutstandingCreditIsReReadAtTheDecision`); a person refused
approval on an absent bureau balance (`#aPersonCannotApproveAnUnassessableExposure`); the
person's deciding transactions blocked on a held profile lock
(`#thePersonsDecidingTransactionWaitsOnTheProfile`).
**Phase:** 10

### INV-CRD-10 — Missing data never approves
**Statement:** A provider's unavailability, a partial or malformed answer, or an unknown status
never becomes an approval: the source's attributes are `ABSENT`, `SOURCE_UNAVAILABLE` is
recorded, and the policy's declared fallback — refer or decline — decides, recorded as such.
**Why:** The fail-safe direction: an outage that approves extends credit on no evidence.
**Enforce:** `DOMAIN` (every approving path requires the source's attributes present; a policy
lacking the fallback rule for a source kind it reads refused at proposal,
`credit.PolicyIncomplete`). Every data request reaches an outcome the freeze can read: an answer
Tx2 cannot record is `UNAVAILABLE` (attempt `UNRECORDED`, its bytes kept), and a `REQUESTED` request past
its deadline is never re-asked and is reported `UNAVAILABLE` once (`CreditDataCollection`,
`JdbcCreditDataRequestStore.claimDue`/`claimOverdue`); a provider body is malformed unless it is
exactly one strict JSON object (`CreditProviderJson`). *(Added at the Phase 10 → 11 transition,
2026-10-10.)*
**Verify:** Every fault of the provider contract suite ending in refer or decline with
`CRD-SOURCE-UNAVAILABLE`; an incomplete policy refused; the missing-data census
(`MissingDataCensus`) — zero `SYSTEM` decisions `APPROVED`, and zero evaluations `APPROVE`, on a
snapshot whose `SOURCE_UNAVAILABLE` names a source kind the pinned policy reads, or on which a
comparison rule of the pinned policy reads an attribute the snapshot holds `ABSENT` or a figure the
assessment could not compute; recomputed from the snapshot's canonical text, the pinned rules and
the assessment, never from the evaluator's own record — in every storm round's one
`REPEATABLE READ` snapshot, with its subjects shown at rest
(`CreditDecisionStormDatabaseTest`), and over the battery's ten thousand
(`DecisionReproducibilityBatteryTest#missingDataNeverApproves`), where replay alone could not see
it (it re-runs the same evaluator). *(The census was added at the Phase 10 → 11 transition: this
line had claimed it before the storm had it.)* An
unrecordable answer and a `REQUESTED` request past its deadline each ending `UNAVAILABLE`, reported
once (`BureauCollectionDatabaseTest`); concatenated, duplicate-key and trailing-token bodies
`MALFORMED` in every adapter's golden files.
**Phase:** 10

### INV-CRD-11 — A person's credit decision is bounded
**Statement:** A person's decision on a referral is never the second approval of their own first
decision, never approves a request whose evaluation included a hard decline, always carries
at least one reason code, and is never made - taken, decided, second-approved or refused - by a
person whose own party is the applicant.
**Why:** One person approving their own exception, or overriding a hard decline, is the
internal-fraud and regulatory exposure four-eyes exists for (`INV-AUD-04`); a human decision
without a reason is no explanation.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (the four-eyes `CHECK`; the case's insert trigger
refusing a hard-declined basis; the first-decision `CHECK` - the codes held on the case row, at least
one, each the catalogue's by the machine trigger - in place of the planned reason-count deferred
trigger; *wording corrected by `P10-TSK-018`*).
**Verify:** Self-approval refused at the domain and at the `CHECK`, each alone;
`422 credit.HardDeclineNotOverridable`; `422 credit.ReasonRequired`; each by a raw-SQL writer too.
*Since the Phase 10 to 11 transition* (2026-10-10, which found no comparison of the case's party with
the actor's - an underwriter who is also a customer could take and approve their own referral below
the four-eyes threshold): every act refused `403 credit.SelfDealingRefused` when the actor's party
(`CreditActingParty`, composed over the identity store) is the case's, or cannot be resolved, and the
attempt recorded `FAILED` `credit.ReviewOwnCaseRefused`
(`UnderwritingCaseDatabaseTest#anUnderwriterNeverActsOnTheirOwnPartysCase`,
`#aCustomerUnderwriterCannotTakeTheirOwnReferralOverHttp`).
**Phase:** 10

### INV-CRD-12 — Credit arithmetic is exact
**Statement:** Credit money arithmetic — income, expenditure, obligations, repayment,
disposable income and exposure — is exact decimal in one explicit currency per assessment, the
product's, never converted, and rounded once at declared points under named rounding modes.
**Why:** `INV-MON-01` and `INV-MON-03` pointed at credit: an affordability verdict that flips on
a floating-point error or an implicit rounding is neither reproducible nor defensible.
**Enforce:** `STATIC` (`NoFloatingPointMoneyRulesTest` over `credit`) + `DOMAIN` (`Money`; the
annuity at scale 10 `HALF_EVEN`, rounded once to minor units `HALF_UP`; money a source reports in
another currency normalised `ABSENT` with the recorded `CURRENCY_NOT_SUPPORTED` marker - an
attribute value, never an error code and never converted, ADR-0088 §3 - and an assessment's
inputs in any other currency refused; *wording corrected by `P10-TSK-009`'s gate*).
**Verify:** Property tests, monotone in income, amount and rate; worked cases exact; the build
rule with a planted violation.
**Phase:** 10

*`INV-CRD-05`…`INV-CRD-12` catalogued by the Phase 9 → 10 transition (2026-10-07), the
`INV-FX` and `INV-XB` precedent: Phase 10's gate properties given stable IDs before any credit
code exists, so the register can demand their demonstrations by identifier rather than by
prose. Decisions in ADR-0084…0089; the machines in `CREDIT_DECISIONING_LIFECYCLES.md`.
`INV-CRD-01`…`-04`, catalogued at initiation and subjectless until now, are Phase 10's too, each
given its Phase 10 subject at the same transition. Until Phase 10's first task lands, nothing
these entries name is implemented; every statement is the decided design, corrected by the
tasks that build it.*

*(Built, 2026-10-09: every entry above is implemented by `P10-TSK-001`…`-021` and proven by
`P10-TST-001` and `P10-TST-002`; each carries its `MUTATION_TESTING.md` §2 rows, read at the Phase
10 exit review (`P10-DOC-001`), which recorded Phase 10 `COMPLETE` and so made the register guard
demand them. The tasks' wording corrections stand in the entries where they were made.)*

---

# Lending — `INV-LND`

### INV-LND-01 — A loan's figures are ledger balances
**Statement:** Every principal, drawn principal, interest, fee and credit-balance figure of a loan
or credit line is a balance of one of its six per-account ledger accounts (`LOAN_PRINCIPAL`,
`LOAN_PRINCIPAL_DUE`, `LOAN_INTEREST_ACCRUED`, `LOAN_INTEREST_DUE`, `LOAN_FEES_DUE`,
`LOAN_CREDIT_BALANCE`), derived from journal lines. No lending table holds a mutable monetary
value; every operational amount (billed, drawn, allocated, accrued, assessed) is an immutable,
born-once row whose sum equals the ledger, and a difference is an alert, never self-corrected.
**Why:** A stored loan balance is a second financial truth that drifts from the ledger — `INV-BAL-01`
and `INV-LED-04` pointed at lending. A figure the borrower is charged must be explainable from
journal lines, not from a column someone updated.
**Enforce:** `DB-PRIVILEGE` (the application role holds `SELECT, INSERT` on lending's fact tables and
conditional `UPDATE` only of status and permit columns on the machine tables — never `UPDATE` of a
money column, never `DELETE`) + `STATIC` (`LendingSchemaHasNoMutableMoneyTest`: every `*_minor`
column on an `INSERT`-only table; `LendingBooksHaveOnePosterTest`: only lending's operations post to
the loan purposes) + `DOMAIN` (every decision — allocation, accrual base, payoff, available limit —
derives from journal lines through `BalanceDerivation` under the account's lock, never from the
display-only projection, `INV-BAL-05`; lending posts only through `PostingService` /
`ReversalService`; every loan purpose joins `AccountPurpose.closedToFreeAdjustments()`).
**Verify:** `LendingSchemaHasNoMutableMoneyTest` with a planted mutable money column
(`P11-TSK-002`); the adjustment guard refusing every loan purpose (`P11-TSK-003`);
`LoanSubledgerProof` zero for every account — each of the six balances equal to its rows' sums, no
due account negative — gauged `finapp.lending.subledger.proof{verdict}` and alerting on any failure
(`P11-TSK-029`); the storm's at-rest subledger proof for every account in every round
(`P11-TST-001`).
**Phase:** 11

*(Proven continuously by `P11-TSK-029`, 2026-10-14 - `LoanSubledgerProof`: per account, each of the six balances folded from zero over its journal lines (never the projection) equals its rows' figure - `LOAN_PRINCIPAL` disbursed or drawn less billed, plus re-scheduled, less paid down (paydowns and prepayments) plus reversed paydowns; `LOAN_PRINCIPAL_DUE` billed less re-scheduled, paid, plus reversed; `LOAN_INTEREST_ACCRUED` accruals less billed interest (no waiver releases accrued interest as built); `LOAN_INTEREST_DUE` billed less waived, paid, plus reversed; `LOAN_FEES_DUE` assessed less waived, paid, plus reversed; `LOAN_CREDIT_BALANCE` held plus fee refunds less reversed held, applications and the closure's refund - with every journal entry on the six accounts named by a row, the six accounts exactly the loan's, none on its wrong side and a terminal account's six zero. Read by `LendingProofs` in one read-only `REPEATABLE READ` snapshot, gauged `finapp.lending.subledger.proof{verdict}`, explained by `GET /v1/operator/loans/{id}/explanation`. Verified by `LendingProofDatabaseTest` - a posting no row names fails it, a raw projection edit does not; probe in `MUTATION_TESTING.md` §2.)*

*(As built by `P11-TSK-017`, 2026-10-12 - the billing arms: every `instalment_billing` row is born with posting rule 3's entry
(or none, for a billing of nothing) and every `credit_balance_allocation` with rule 6's, in one transaction, so
`LOAN_INTEREST_DUE` and `LOAN_PRINCIPAL_DUE` are the billings less the allocations and `LOAN_INTEREST_ACCRUED` the accruals less
the billed interest. The credit-balance application derives its due state from those rows under L3 and refuses to allocate
unless they explain the due accounts' ledger balances exactly - a difference is an alert, never allocated over
(`InstalmentBillingDatabaseTest#aCreditBalancePaysTheBill`); every case of the suite asserts the arms.)*

*(As built by `P11-TSK-022`, 2026-10-13 - the statement arms: every `credit_line_statement` is born with posting rule 3's entry (or none, for a statement of nothing) in the servicing step's transaction, its interest exactly the cycle's accrual rows (`lending V017`'s trigger, for every writer) and the accrual's trigger refusing any date inside a stated cycle, so a line's `LOAN_INTEREST_DUE` and `LOAN_PRINCIPAL_DUE` are its statements less every payer's allocations, `LOAN_INTEREST_ACCRUED` the accruals less what statements billed and `LOAN_PRINCIPAL` the draws less what statements billed (`CreditLineStatementDatabaseTest#assertLineSubledgerProves`, every case). The statement stores only what it billed; the owner's read derives opening and closing balances from journal lines (`#balancesAreDerivedNotStored`).)*

### INV-LND-02 — Interest accrues once per account per date
**Statement:** For each loan or credit line and each accrual date at most one accrual row exists and
at most one journal entry records it, whatever reruns, instances, crashes, restarts or clock skew
occur; every elapsed accruable date of an account in servicing is accrued exactly once, oldest
first, a zero-interest day writing its row and no entry.
**Why:** A double accrual is interest the borrower never owed — money created by a scheduler
(`INV-IDEM-02` pointed at lending); a missed date is income silently forgone and a schedule that no
longer explains the bill.
**Enforce:** `DB-CONSTRAINT` (`interest_accrual UNIQUE (loan_id, accrual_date)`; the ledger's
idempotency key `lending.accrual:<loan>:<date>`) + `DOMAIN` (each servicing step under the account's
`loan` row lock, L3, conditional on the date's absence; a payoff accrues missing dates itself under
the same uniques).
**Verify:** Scenario 4 —
`P11-TSK-016`'s `InterestAccrualDatabaseTest#theAccrualRunTwiceAccruesOnce`, with ten sweepers and a
crash mid-run, one row and one entry per (account, date); the unique dropped as the task's probe
turns scenario 4 red (`MUTATION_TESTING.md` §2); the storm's completeness census — every accrual date
of every account present exactly once at rest (`P11-TST-001`); `finapp.lending.accrual.lag` alerting
past one day.
**Phase:** 11

*(As built by `P11-TSK-016`, 2026-10-12 - `LoanServicing` over `lending V010`: three arbiters - L3 (each step reads the
latest accrual after the loan's lock), `interest_accrual UNIQUE (loan_id, accrual_date)` (and `UNIQUE (journal_entry_id)`),
and the ledger key, whose replay - an entry for a date with no row - the step refuses as a defect; the row's trigger also
refuses a date at or before one already accrued (oldest first) for every writer. Scenario 4 counted by
`InterestAccrualDatabaseTest#theAccrualRunTwiceAccruesOnce` (twice, ten racing steps, ten racing sweepers over three
accounts, a crash mid-run; the unique proven beneath L3 with the triggers suspended; the key's replay refused); catch-up
oldest first by `#threeMissedDaysAreCaughtUpOldestFirst`; zero days by `#zeroDaysWriteARowAndNoEntry`. The lag gauge reads
instalment loans - a line's zero-principal days write no row, so its arm waits for `-021`.)*

*(As built by `P11-TSK-026`, 2026-10-13 - a payoff accrues for itself (ADR-0094 point 10): its quote and its execution run
`LoanServicing`'s own accrual under L3, with the same rows, uniques and keys, so the sweep and a payoff racing on the same
date accrue it once (`PayoffDatabaseTest#theSweepAndThePayoffAccrueTheSameDateOnce`); the quote's projection for
`[today, G - 1]` is exactly what the payoff's accrual then posts (`#aQuoteIsExecutableOnlyOnItsDate`).)*

*(As built for BNPL by `P12-TSK-017`, 2026-10-19 - by analogy, pay-in-N: `BnplAccruals` (T-acc) over `bnpl V009`'s writer of `bnpl_interest_accrual`: B4 (the obligation `FOR UPDATE`) with the database clock read after it, every elapsed date of the accrual span - the latest schedule version's instalment periods from the purchase's business date, nothing at or after the final due date - with principal outstanding accrued oldest first in its own transaction, at most 92 a call; `UNIQUE (obligation_id, accrual_date)`, the trigger's oldest-first, never-early, instalment-period, agreement-in-force and engine ranks re-judged under B4 for every writer, and the key `bnpl.accrual:<obligation>:<date>` (a replay the book refuses). Every other act checks `BnplAccruals.caughtUp` under its own B4 and never accrues. Verified hermetically by `BnplAccrualsTest#accruesOncePerDate`, `#skewNeverAccruesEarlyOrTwice`, `#threeMissedDaysAreCaughtUpOldestFirst`, `#aLongOutageIsCaughtUpInBoundedCalls`, `#nothingAccruesAfterTheFinalDueDate`; across instances by `BnplAccrualDatabaseTest` - written, owed a first run.)*

### INV-LND-03 — A schedule conserves principal exactly
**Statement:** Every repayment schedule version's principal portions sum to its principal exactly,
and its instalment amounts to that principal plus the sum of its period interest, every rounding at a
declared point of a declared schedule engine version; terms that would amortise negatively are
refused at the offer, never scheduled.
**Why:** Rounding leakage of one minor unit per loan is, at portfolio scale, money created or
destroyed with no economic event behind it; a negatively amortising schedule is a different product
from the one offered.
**Enforce:** `DOMAIN` (`SCHEDULE_ENGINE_V1`: the level instalment rounded once `UP`, period interest
rounded once `HALF_EVEN`, the final principal portion the remaining balance, conservation asserted at
construction; a non-positive principal portion refused `422 lending.TermsNotAmortising`) +
`DB-CONSTRAINT` (`repayment_schedule UNIQUE (loan_id, schedule_version)`, the rows `INSERT`-only — a
recalculation is a new version, never an edit).
**Verify:** The schedule engine's property tests — Σ principal = P and Σ amount = P + Σ interest for
every term 6–60, every repayment day 1–28 with the month-end clamp, rates including 0, disbursement on
every day of a leap and a non-leap year — and golden worked examples checked by hand
(`P11-TSK-006`…`009`, the engine tasks); the battery's ≥ 10,000 generated loans each conserving
exactly and replayed `IDENTICAL` (`P11-TST-002`); `LoanReplayProof` re-running the pinned engine over
every schedule version (`P11-TSK-029`).
**Phase:** 11

*(Replayed by `P11-TSK-029`, 2026-10-14 - `LoanReplayProof` re-derives every schedule version by the engine version it pinned: version 1 projected from the accrual start, an amendment's re-calculated and a prepayment's re-calculated at the superseded instalment, each from the principal the ledger held at its birth over the open period it superseded - every instalment compared; `LendingProofDatabaseTest#anInstalmentLoansWholeLifeReplaysIdentically`.)*

*(As built by `P11-TSK-006`, 2026-10-11 - `SCHEDULE_ENGINE_V1`: the level instalment is the actual-day annuity rounded
once `UP` (raised by the least amount only if the period roundings would leave the final instalment above it), period
interest rounded once `HALF_EVEN` through `Act365Fixed`, the final principal portion the remaining balance, conservation
asserted by `Schedule`'s construction; a non-positive principal portion a typed `NOT_AMORTISING`, and terms under which
any loan within credit's bounds could not amortise refused at the proposal (`422 lending.TermsNotAmortising`). Verified
by `ScheduleEngineV1Test` (golden cases checked by hand and by an independent exact-rational oracle) and
`ScheduleEngineV1PropertiesTest` (every day of 2027 and 2028 x every repayment day 1-28 x every term 6-60, rates
including 0, the clamp from an intended 29th-31st, monotone in rate, two JVMs byte-identical); the "refused at the
offer" half is `P11-TSK-011`'s.)*

*(The credit line's analogue, as built by `P11-TSK-009`, 2026-10-11 - a line has no schedule, so what it conserves is
that a statement never bills more principal than is drawn and not yet due: `STATEMENT_ENGINE_V1`'s principal part is
`min(N, max(round_UP(ratio x D), floor - interest - fees, 0))`, so the minimum payment never exceeds the statement's
interest, fees and principal not yet due, and `Statement`'s construction asserts the minimum is exactly its parts.
Verified by `StatementEngineV1Test#drawnPrincipalBelowTheFloorIsCapped` and `StatementEngineV1PropertiesTest` - the
principal part at most `N`, the minimum at most what can be billed and at least the floor or all of it, over 3,000
generated lines, two JVMs identical.)*

*(As built by `P11-TSK-014`, 2026-10-12 - the database rank: `lending V008`'s `repayment_schedule` and `loan_instalment` are
INSERT-only for every role, and a deferred check holds each schedule to its rows at commit - n periods numbered 1..n that chain
from the start date and principal, level instalments then the final one, closing at zero, the principal portions summing to the
principal and the interest to the header's total; a row added to a committed schedule fails it. Schedule v1 is the pinned
engine's projection from the accrual start, re-asserted by `Schedule`'s construction on every read
(`LoanDisbursementDatabaseTest#theScheduleIsGeneratedOnce`).)*

*(As built by `P11-TSK-017`, 2026-10-12 - what is billed: interest due exactly the sum of the period's accruals, principal due
`max(0, A - I)` never beyond the principal not yet due, the final instalment all of it, so the principal billed over a loan's
life is its principal whatever its history; `lending V012`'s trigger refuses, for every writer, principal billed beyond the
agreement's. On an on-time path the bills equal the projection to the minor unit
(`InstalmentBillingDatabaseTest#onTimeBillingEqualsTheProjection`); after an irregular one the final instalment clears principal
(`#theFinalInstalmentClearsPrincipal`).)*

*(The Phase 11 gate's R5, 2026-10-16 - a schedule the engine would refuse is never asked for: the terms door judges with an
exact margin at the smallest principal (`ScheduleEngineV1.judgeAmortisation`, ADR-0093's Follow-up), so no principal between
the product's bounds and no start date - a disbursement's or a payout conclusion's - is refused NOT_AMORTISING; the old
door's rounded two-principal judgement admitted 23.10 %, at which a real EUR 500.19 loan could not be scheduled.
`ScheduleEngineV1Test#theOldDoorsGap`, `#theExhaustionBound`, `ScheduleEngineV1PropertiesTest#theJudgementIsSoundAndTight`.)*

### INV-LND-04 — Allocation conserves and never over-pays a component
**Statement:** A repayment's allocation lines sum to its amount exactly; no line exceeds what its
component (fee, interest or principal of one due item) had due when the repayment was allocated; no
due account's balance ever becomes negative; anything beyond what is due goes, by the agreement's
pinned overpayment treatment, to principal paydown (line) or to the credit balance, never to a due
account.
**Why:** An allocation that over-pays a component leaves a negative due balance — an unexplained
credit to the borrower and a broken subledger; one that under-allocates loses the borrower's money.
**Enforce:** `DB-CONSTRAINT` (a deferred trigger on `repayment` / `repayment_allocation`: Σ allocation
= amount at commit) + `DOMAIN` (`ALLOCATION_ENGINE_V1` is pure, `allocate(amount, dueState, rules)`,
with `dueState` derived from postings and rows under the account's `loan` row lock, L3, never from the
projection; concurrent repayments serialise on L3 and each allocates against what it finds).
**Verify:** Scenario 3 —
`RepaymentDatabaseTest#twoRepaymentsOnOneInstalmentSerialiseAndConserve` (`P11-TSK-018`): Σ
allocations = Σ amounts, no due account negative, never over-allocated; the allocation engine's
properties — conservation, order, never above due, both overpayment treatments (`P11-TSK-006`…`009`);
the task's probe — reading the projection for allocation, under a planted projection drift, caught by
`RepaymentDatabaseTest#theDueStateIsDerivedFromRowsNotTheProjection` (`AllocationAnomaly`, not the scenario 3 case
above) and, as the storm's P5, by `LendingStormDatabaseTest#theStormLeavesEveryLoanProvable` at scenario 3's census
(C15) *(read "turns scenario 3 red" - naming the scenario 3 case above as the catcher, which no performance had - until
the Phase 11 completion gate's repair, 2026-10-16, finding A6-05)*
*(read "under a racing reversal" until `P11-DOC-001`, 2026-10-15: the projection is maintained in each posting's own
transaction, so no racing reversal can open the gap - every performance planted the drift, `P11-TSK-018`'s and the
storm's P5)* (`MUTATION_TESTING.md` §2); the subledger proof's non-negativity arm (`P11-TSK-029`); the storm's Σ
allocations = Σ repayments at rest (`P11-TST-001`).
*(As built by `P11-TSK-008`, 2026-10-11 - the engine half: `AllocationEngineV1` asserts conservation and never-above-due
before its lines return, and `AllocationEngineV1PropertiesTest` holds them over generated inputs under all six component
orders and both treatments - `#conserves`, `#neverAboveDue`, `#respectsTheOrderAndTheTreatment` (oldest first, the
pinned order, overflow only past every billed item and by the treatment), `#independentOfInputOrder`,
`#aSecondJvmAllocatesIdentically`; the case-by-case `AllocationEngineV1Test`; the probes are `MUTATION_TESTING.md` §2's
`P11-TSK-008` rows. The deferred trigger, the lock and scenario 3 remain `-018`'s.)*
**Phase:** 11

*(The Phase 11 gate's R2, 2026-10-16 - the credit balance settles what is already due: a credit-balance application is
also born at no billed item, as the loan's next catch-up application (`lending V029`: `billing_id` NULL,
`catch_up_sequence` numbered under L3 for every writer by the born-once trigger, `UNIQUE (loan_id, catch_up_sequence)`, key
`lending.credit-application:<loan>:catch-up:<n>`), by the servicing step before its collection and by the payoff before its
recomputation - and `V012`'s deferred conservation holds it exactly as an application at a billed item: its rows sum to it,
no billed component paid beyond what it billed. `FeeAndWaiverDatabaseTest#aRefundHeldBesidePastDueArrearsIsApplied`,
`#aMaturedLoansArrearsAreSettledByCreditHeld`, `RepaymentReversalDatabaseTest#aReversalReopeningAnItemBesideHeldCreditIsSettledByIt`,
`PayoffDatabaseTest#aPayoffBesideCreditHeldDebitsOnlyTheNet`; probes in `MUTATION_TESTING.md` §2.)*

*(Replayed by `P11-TSK-029`, 2026-10-14 - every repayment's and credit-balance application's lines re-derived by the pinned allocation engine over the due state the rows and the ledger held just before it (the history walked in the lock's order, lending `V025` stamping the money decisions after L3); the subledger proof's no-wrong-side arm; `LendingProofDatabaseTest#aByteFlippedInAnyFactDiverges` - an allocation line moved by a minor unit is `DIVERGED`.)*

*(As built by `P11-TSK-017`, 2026-10-12 - the credit-balance application, the first caller of `allocate` against real rows:
the credit balance as the amount over the due state derived under L3, its lines on billed items recorded as
`credit_balance_allocation` rows; `lending V012`'s deferred check refuses at commit, for every writer, an application whose rows
do not sum to it or that pays any billed component beyond what it billed (the paid sum `lending.billing_component_paid`, which
`-018` extends with its repayment allocations so the rule covers every payer). The repayment's own trigger and scenario 3 stay
`-018`'s.)*

*(As built by `P11-TSK-018`, 2026-10-12 - the repayment: `lending V014`'s deferred check refuses at commit, for every writer, a
repayment whose `repayment_allocation` rows do not sum to it or that pays a billed component beyond what it billed
(`lending.billing_component_paid` now counts both payers); the due state is derived from rows under L3 and refused unless it
explains the due accounts (`AllocationAnomaly`); concurrent repayments serialise on L3 and each allocates what the last left -
scenario 3 (`RepaymentDatabaseTest#twoRepaymentsOnOneInstalmentSerialiseAndConserve`, beside a loan-then-wallet writer) and
`#tenKeysOneLoanConserve`; every case of the suite closes on the subledger proof's arms over both payers. Probes in
`MUTATION_TESTING.md` section 2.)*

*(As built by `P11-TSK-019`, 2026-10-13 - auto-collection: a collection's amount is never more than its item has due (its
due, or less where partial collection is allowed) and its every allocation line pays that item - asserted by
`LoanCollections` (an `AllocationAnomaly` otherwise) and, for every writer, by `lending V015`'s trigger (a collection's
repayment holding credit or paying another item refused; what was due less what was collected equal to what the item still
has due). `AutoCollectionDatabaseTest#neverMoreThanDue` and `#theDueDateCollectsOnceWhateverRaces` (steps racing the
borrower's repayment: the bill paid exactly, never over-allocated, the rest held). Probes in `MUTATION_TESTING.md` section
2.)*

*(As built by `P11-TSK-022`, 2026-10-13 - a line's statement is a billed item: `lending.billing_component_billed` reads its interest and principal, so `-017`'s, `-018`'s and `-019`'s never-over-paid rules hold for statements unchanged (`CreditLineStatementDatabaseTest#theRowsAreHeldForEveryWriter`: an over-paying application refused at commit). Rule 6 - one implementation for billings and statements (`CreditBalanceApplications`) - settles only what became due: its remainder stays held whatever the agreement's overpayment treatment (the treatment is a repayment's), proven by `#aCreditBalancePaysTheStatement`.)*

### INV-LND-05 — Terms are pinned and immutable
**Statement:** A loan agreement version never changes once written; it carries the full,
self-contained canonical term set, its SHA-256 and the agreement template and engine versions; every
servicing row names the agreement version it was computed under; a change of contractual terms is a
new agreement version, approved four-eyes and accepted by the customer; a terms version is immutable
once proposed, and an agreement keeps the terms version it was pinned to whatever later becomes
active.
**Why:** A figure must be explainable years later, after any product change (`INV-HIST-04` pointed at
lending): "why is this instalment this amount?" is answered from agreement vN (hash) → schedule vN →
billing → allocations → journal entries, or not at all.
**Enforce:** `DB-PRIVILEGE` (`loan_agreement` and `loan_acceptance` `INSERT`-only for every role;
`loan_terms_version` immutable once proposed) + `DB-CONSTRAINT` (`loan_agreement UNIQUE (loan_id,
version)`; foreign keys from servicing rows to the agreement version; one `PROPOSED` and one `ACTIVE`
terms version per product by partial uniques) + `DOMAIN` (the acceptance echoes the offer's
`terms_sha256`, a mismatch refused `409 lending.TermsChanged`; an amendment's acceptance conditional on
the agreement version it was proposed against).
**Verify:** Raw-SQL `UPDATE` and `DELETE` on the agreement, acceptance and terms tables refused for
every role (`P11-TSK-005`, `P11-TSK-012`); a changed offer refused at acceptance (`P11-TSK-012`);
`LoanReplayProof` re-verifying every agreement version's `terms_sha256`, a perturbed agreement byte
flipping the verdict to `DIVERGED` (`P11-TSK-029`, `P11-TST-002`); scenario 8 —
`AmendmentDatabaseTest#anApprovedAmendmentChangesTheScheduleOnce` (`P11-TSK-027`), the agreement and
schedule version n+1 written once and a moved agreement lapsing the amendment.
*(The agreement half built by `P11-TSK-012`, 2026-10-11 - `lending V006`'s `loan_agreement` and `loan_acceptance`, INSERT-only by grant and immutable by trigger for every role, version 1 held to an exact copy of its offer's terms (canonical `text`, its hash by `CHECK`); verified by `LoanAcceptanceDatabaseTest#theAgreementIsNeverUpdatedByAnyRole` and `#aMismatchedHashRefuses` (`409 lending.TermsChanged`, nothing written); probes in `MUTATION_TESTING.md` §2.)*
*(Canonical format 2 by `P11-TSK-031`, 2026-10-12 - `lending V011`: the canonical terms' stored format is the bytes' own
first token, held by `CHECK` with each format's token set for every writer; format 1 rows unchanged and re-renderable
byte for byte, so replay re-verifies every hash in the format it was written in. Its gate, 2026-10-13: the agreement's
`CHECK` proven alone (`LoanAcceptanceDatabaseTest#anOfferEastOfUtcIsAccepted`, the triggers suspended), and the four
probes performed - `MUTATION_TESTING.md` §2's `P11-TSK-031` rows.)*
**Phase:** 11

*(Re-verified by `P11-TSK-029`, 2026-10-14 - `LoanReplayProof` re-renders every agreement version's canonical terms from its rows in its stored format (1 and 2 from the offer and the version's columns, 3 from the amendment and the schedule it fixed, 4 from the prepayment and the schedule it fixed - `LoanReplayer.render`) and re-verifies their SHA-256 against `terms_sha256`; a canonical byte flipped and re-hashed (the `CHECK` satisfied, as a forger would) is `DIVERGED` - `LendingProofDatabaseTest#aByteFlippedInAnyFactDiverges`, probe in `MUTATION_TESTING.md` §2.)*

*(As built by `P11-TSK-005`, 2026-10-11 - the terms half: `lending V003` splits a version into the money-free machine
row `loan_terms_version` (identity frozen by the edge trigger, the decision columns its only `UPDATE`, one `PROPOSED`
and one `ACTIVE` per product by partial uniques, activator ≠ proposer by `CHECK`, the effective period clamped on a
backward clock) and the term set `loan_terms` (INSERT-only by grant, immutable from insert by trigger for every role,
born only for a `PROPOSED` version, a version committing only with it). Verified by
`LoanTermsDatabaseTest#immutableForEveryRole` (raw `UPDATE`, `DELETE`, `TRUNCATE` and a late term set refused for the
application role by privilege and for the owner by trigger, while `PROPOSED` and again once `ACTIVE`), `#fourEyesAtBothRanks`,
`#oneActivePerProduct`, `#activeAtAnInstantOverAHistory`; the probes are `MUTATION_TESTING.md` §2's `P11-TSK-005` rows.)*

### INV-LND-06 — One decision funds at most one loan or line, and exposure has no gap
**Statement:** A credit decision is consumed at most once — only while `APPROVED` and before its
`valid_until`, only through credit's consumption port — in the same transaction and under the same
party `credit_profile` row lock that commits the agreement whose principal or limit replaces the
decision's reservation in the party's exposure. The exposure lending supplies counts the committed
principal of loans awaiting disbursement, the outstanding principal of loans, and the **limit** of
every open credit line; every lending transaction that raises a party's exposure or moves it between
reserved, committed and outstanding takes the party's profile lock first; and no instant sees neither
the reservation nor the commitment.
**Why:** A gap between reservation and commitment, or an undrawn limit assumed away, lets a party
borrow its limit twice — `INV-CRD-09` broken from the lending side. A consumption written outside
credit's ownership is a second writer of credit's state.
**Enforce:** `DB-CONSTRAINT` (`credit_decision_consumption UNIQUE (decision_id)` and `UNIQUE
(consumer_kind, consumer_ref)`; a trigger refusing any insert not made inside the definer function;
`loan UNIQUE (offer_id)`) + `DB-PRIVILEGE` (the `SECURITY DEFINER` `credit.consume_decision`, owned by
credit's owner role, the only path to the table; the application role's `INSERT` revoked) + `DOMAIN`
(the profile lock, L0, first in acceptance, disbursement and repayment reversal;
`PlatformCreditExposure` version 2 one SQL statement over journal lines, never `account_balance`;
lapse judged `valid_until <= statement_timestamp()`, the complement of the reservation's predicate).
**Verify:** `P11-TSK-001`'s counted races — ten consumers of one decision, one consumed; consume
versus decide for one party serialised; the `valid_until` boundary under ±5 s skew, exactly one of
*reserves* and *consumable*; a direct `INSERT` by the application role refused; the probe consuming
without the profile lock turning the serialisation test red; ten acceptances of one offer, one
account (`P11-TSK-012`); acceptance versus a credit decision for the same party serialised, and the
exposure read versus a committing disbursement never torn (`P11-TSK-013`); the `ExposureCensus` —
credit's reserved plus lending's committed and outstanding never above the limit the party's policy
declared at the deciding instant — in every storm round (`P11-TST-001`), gauged
`finapp.credit.exposure.census{verdict}`.
*(The consumption half built by `P11-TSK-001`, 2026-10-10 — `CreditDecisionConsumptionDatabaseTest`,
seven probes, each caught (`MUTATION_TESTING.md` §2). Two refinements of the wording above, both on the
side of the invariant: the lapse is judged on the database clock read **after** the profile lock
(`clock_timestamp()` inside `credit.consume_decision`, through `credit.decision_reserves`, the
negation of the reservation's `valid_until > now`) — `statement_timestamp()` is the calling statement's
start, from before any wait on the lock; and an existing consumption is judged before the lapse, so a
consumer's retry after `valid_until` replays its own consumption rather than reading a lapse.)*
*(The acceptance half built by `P11-TSK-012`, 2026-10-11 - L0 first through `LoanCreditDecisions.lockExposure`, the consumption in the acceptance's transaction with the agreement as the consumer, `loan UNIQUE (offer_id)` and `UNIQUE (decision_id)`, the loan and the offer's `ACCEPTED` edge committed only together (deferred checks); verified by `LoanAcceptanceDatabaseTest#tenAcceptancesOneLoan`, `#acceptanceTakesTheProfileFirst`, `#aCrashAfterEachInsertLeavesNothing`, `#theAgreementIsNeverUpdatedByAnyRole` and `app`'s `LoanAccountDatabaseTest#acceptanceWaitsOnCreditsProfileLock`, `#tenAcceptancesUnderOneKeyOneLoan`. The exposure half - the commitment visible to credit's deciding transaction - is `P11-TSK-013`'s, shipped in the same release.)*
*(The exposure half built by `P11-TSK-013`, 2026-10-12 - the pair complete: `app`'s `LendingCreditExposure` (`PlatformCreditExposure` version 2) wired into credit's freezer in place of Phase 10's zero, ONE statement over lending `V007`'s definer view `party_principal_exposure` and the loans' `LOAN_PRINCIPAL` + `LOAN_PRINCIPAL_DUE` journal lines, read under L0 in the deciding transaction beside the reservation; verified by `LendingCreditExposureDatabaseTest` - `#acceptanceAndADecisionForThePartySerialise` (both orders against the real acceptance, the waiting statement asserted on `credit_profile`; the decision reads the approval reserved or the loan committed, exactly once), `#aDisbursementDuringTheReadIsNeverMissed` (a disbursement committed between a read's statements, never torn), the arms (an open line's limit, drawn beyond it, a closing line, a cancelled loan, principal repaid, interest and fees not exposure) and `#theCensusFindsAnOverLimitParty` (`ExposureCensus`, gauged `finapp.credit.exposure.census{verdict}`) - probes in `MUTATION_TESTING.md` §2. The outstanding arms rest on owner-role fixtures until `-014`, `-018`, `-023` and the storm re-assert them through the real paths; `-014` must commit the `PENDING_DISBURSEMENT -> ACTIVE` edge and the principal posting in one transaction, L0 first.)*
**Phase:** 11

*(Extended at the Phase 11 → 12 transition (2026-10-16), ADR-0101 and ADR-0104 (`Proposed`), in force when `P12-TSK-001` and `-013` land: the consumer kind `BNPL_AGREEMENT` joins `LOAN_AGREEMENT` — one decision funds at most one loan, line or BNPL agreement, consumed under the party's profile lock with no exposure gap (`INV-BNPL-03`, `-10`).)*
*(The credit half built by `P12-TSK-001`, 2026-10-16 - credit `V022`: `ConsumerKind.BNPL_AGREEMENT` and the widened kind `CHECK`, through the unchanged `SECURITY DEFINER` path; `UNIQUE (decision_id)` stays the one arbiter across kinds, and a kind takes up only its own family's decisions (`ConsumerKind.takesUp`, refused before any lock by the port and by `credit.consume_decision`); verified by `CreditDecisionConsumptionDatabaseTest#tenBnplConsumersOneConsumption`, `#aLoanAndABnplConsumerCannotShareADecision`, `#aBnplDecisionLapsesAtTheMicrosecond`, `#aBnplConsumptionAndADecisionForThePartySerialise`.)*

### INV-LND-07 — A loan is disbursed at most once, its receivable born exactly with the wallet credit, and no payout outcome creates, duplicates or undoes it
**Statement:** A loan has at most one disbursement and at most one disbursement journal entry; its
receivable (`LOAN_PRINCIPAL`) is debited in the very entry that credits the borrower's wallet, on
both disbursement paths, and the loan is `ACTIVE` exactly when that entry exists; a failed
disbursement posts nothing. On the external path the payout is dispatched at most once, through
payments' withdrawal under one key, and is never re-dispatched by lending; no payout outcome —
`PAID_OUT`, `FAILED`, `RETURNED`, `NOT_DISPATCHED`, or payments' `UNKNOWN` — creates, duplicates or
reverses the disbursement or the receivable: it changes only when interest starts.
**Why:** A double disbursement is a direct loss; a receivable without a disbursement is a phantom
asset; a loan whose birth depends on a provider's ambiguous answer cannot say whether it exists. The
wallet as transit account keeps the receivable's birth internal and final, and puts the external
leg's ambiguity on machinery already proven for it (`INV-RAIL-02`).
**Enforce:** `DB-CONSTRAINT` (`loan_disbursement UNIQUE (loan_id)`; `loan_payout UNIQUE (loan_id)`; the
ledger key `lending.disbursement:<loan>`; the loan's machine trigger refusing
`ACTIVE → PENDING_DISBURSEMENT` and `ACTIVE → CANCELLED`) + `DOMAIN` (the conditional
`PENDING_DISBURSEMENT → ACTIVE` under the `loan` row lock in the posting's transaction; the payout's
conditional claim committed before payments is called, payments keyed
`payments.withdrawal:lending:<loanId>` so a re-drive replays; lending reads payments' outcome and
never calls payments again on `UNKNOWN`; the hold placed at disbursement adopted, not duplicated, by
payments' withdrawal).
**Verify:** Scenario 1 —
`LoanDisbursementDatabaseTest#theSameDisbursementOnTwoInstancesPostsOnce` (`P11-TSK-014`), one entry,
one `ACTIVE` edge, the wallet credited once, and `LoanPayoutDatabaseTest#tenDispatchersOneWithdrawal`
(`P11-TSK-015`); scenario 5 —
`LoanPayoutDatabaseTest#aLostResponseIsHeldThenConcludedByInquiryAndNeverResent` (`P11-TSK-015`), the
payout `DISPATCHED` while payments is `UNKNOWN`, no re-dispatch, accrual from the conclusion, one
withdrawal at the provider; scenario 9 —
`LendingOutboxAtomicityDatabaseTest#aCrashBetweenPostingAndPublicationLosesNothing` (`P11-TSK-014`);
a returned or failed payout leaving the receivable and the disbursement entry unchanged
(`P11-TSK-015`); the probe re-dispatching a payout on `UNKNOWN` turning scenario 5 red
(`MUTATION_TESTING.md` §2) *(as performed by `P11-DOC-001`, 2026-10-15 - the tasks had performed "conclude on
`UNKNOWN`" in its place: it SURVIVED, an equivalent mutant, payments' key being the loan's own instruction - one
withdrawal, one send; "never re-dispatched" rests on the machine and payments' key, the money on scenario 5's one send)*;
the born-once disbursement and payout uniques each refusing a copy alone with every trigger off and every `CHECK`
dropped (`LendingBornOnceArbitersDatabaseTest`, `P11-DOC-001`); no lending connection or transaction held across
payments' dispatch (`LoanPayoutDatabaseTest#noConnectionIsHeldAcrossPaymentsDispatch`, `P11-DOC-001`); the storm's one disbursement per non-cancelled loan at rest
(`P11-TST-001`).
**Phase:** 11

*(As built by `P11-TSK-014`, 2026-10-12 - path W: the disbursement's journal entry is named on its row by value with
`UNIQUE (journal_entry_id)` beside `UNIQUE (loan_id)` and the ledger key; `lending V008`'s triggers admit `PENDING_DISBURSEMENT -> ACTIVE`
only beside the disbursement `POSTED` and refuse every edge out of `ACTIVE` but the later tasks' closures, and two deferred checks
hold the pair together at commit; a failed attempt rolls back to a savepoint and posts nothing. Verified by
`LoanDisbursementDatabaseTest` (scenario 1, ten disbursers, ten sweepers) and `LendingOutboxAtomicityDatabaseTest` (scenario 9).)*
*(As built by `P11-TSK-015`, 2026-10-12 - path X: the same one-transaction birth, the hold of `P - f` placed under the wallet's
row lock (L7, before the posting) and the payout born `PENDING` beside the `ACTIVE` edge (a deferred check); `loan_payout`
`UNIQUE (loan_id)`, `UNIQUE (hold_id)`, `UNIQUE (withdrawal_id)`; payments' key is `payments.withdrawal:lending` / `<loanId>` with
`UNIQUE (instruction_reference)` and one withdrawal per hold (payments `V032`); each payout step acts only on the permit its claim
stamped; lending only reads payments' withdrawal, and an `UNKNOWN` one is observed, never re-sent. No payout step touches the
receivable or the disbursement entry. `RETURNED` is not a state (payments keeps no return record for an instant withdrawal, final
on acceptance): a return after `PAID_OUT` leaves the payout and the loan untouched. Verified by `LoanPayoutDatabaseTest`
(scenario 1's payout half, scenario 5, the failure, the unroutable payout, the return) and `LoanPayoutStepsDatabaseTest`.)*

### INV-LND-08 — Closed is closed, and closed means settled
**Statement:** A loan or line reaches `CLOSED` only in the transaction that brings its last
receivable to zero and refunds any credit balance — closure is a consequence, never a command — so a
`CLOSED` account has all six per-account balances at zero. A `CLOSED` or `CANCELLED` account accepts
no repayment, accrual, billing, statement, fee, draw, amendment or condition change, and never leaves
its terminal state.
**Why:** Money taken for a closed loan has nowhere honest to go; a condition or accrual written after
closure tells the borrower and the collections owner something false; a closed loan with a residual
balance is either a hidden debt or a hidden refund.
**Enforce:** `DB-CONSTRAINT` (the loan's every-writer machine trigger and status `CHECK` refusing any
edge out of `CLOSED` or `CANCELLED`, `INV-LIFE-04`) + `DOMAIN` (closure only inside the transaction
that zeroes the last receivable, under the `loan` row lock; repayment on a terminal account refused
`409 lending.LoanNotRepayable` with nothing posted; the servicing sweep skipping terminal accounts and
re-deriving the condition under L3).
**Verify:** Raw-SQL edges out of `CLOSED` and `CANCELLED` refused for every role (`P11-TSK-012`); a
repayment for a closed loan answering `409 lending.LoanNotRepayable` with nothing posted
(`P11-TSK-018`); scenario 7 — `PayoffDatabaseTest#payoffDuringARepaymentIsStaleOrClosedNeverWrong`,
and scenario 10 —
`PayoffDatabaseTest#payoffBesideTheDelinquencyWorkerWritesNoConditionAfterClosure` (`P11-TSK-026`);
the subledger proof's closure arm — every `CLOSED` account's six balances zero (`P11-TSK-029`,
`P11-TST-001`).
**Phase:** 11

*(As built by `P11-TSK-018`, 2026-10-12 - an instalment loan's closure: `LoanClosures`, in the repayment that zeroes the last
receivable and as the servicing step's last step after a credit-balance application that does; settled means every receivable
zero on the ledger under L3 **and** no elapsed date left unaccrued with principal outstanding; the credit balance refunded
to the wallet (L7 after L3) before `CLOSED (REPAID)`, so all six balances are zero at commit
(`RepaymentDatabaseTest#theLastRepaymentClosesAndRefunds`, `#aCreditBalanceThatSettlesTheLoanClosesAtServicing`,
`#aLoanIsNotClosedOverAnUnaccruedDay`); `lending V014` holds the edge to its `loan_closure` row, the closure to a fully billed
and paid schedule and to its loan `CLOSED` at commit; a repayment on a closed loan `409 lending.LoanNotRepayable`, nothing
posted (`#aClosedLoanRefuses`), and refused by the repayment trigger for every writer (`#theRowsAreHeldForEveryWriter`).)*

*(As built by `P11-TSK-023`, 2026-10-13 - a credit line's closure: the same `LoanClosures` closes a `CLOSING` line
`CLOSED_BY_CUSTOMER` in any transaction that settles it - the customer's repayment, its automatic collection, or the servicing
step after rule 6 at a statement - and an `ACTIVE` line at its closure request when nothing is outstanding then; "settled"
again includes every elapsed date accrued (`CreditLineRepaymentDatabaseTest#theLastPaymentClosesAClosingLine`,
`#aClosingLineClosesAtServicingWhenItsStatementIsSettled`, `#aLineWithNothingOutstandingClosesAtTheRequest` - all six
balances zero, the excess refunded - and `#aLineIsNotClosedOverAnUnaccruedDay`); `lending V018` holds a line's closure to
its request and every statement paid, for every writer (`#theRowsAreHeldForEveryWriter`); a closed line refuses a repayment
(`LoanNotRepayable`), a draw and a second request (`LoanClosed`).)*

*(As built by `P11-TSK-026`, 2026-10-13 - the payoff: T-pay accrues every elapsed date, bills everything unbilled (a line's
current cycle stated early), recomputes under L3 and, only when the figure is the quote's, repays everything receivable and
closes through the same `LoanClosures` - `CLOSED (PAID_OFF_EARLY)`, a line `CLOSED (CLOSED_BY_CUSTOMER)` - all six balances
zero and the credit balance refunded (`PayoffDatabaseTest#payoffZeroesEveryBalance`, `#aLinesPayoffClosesIt`,
`#aCreditBalanceThatCoversEverythingIsRefunded`); a figure that moved is `PayoffQuoteStale` with nothing posted, a loan closed
first refuses the racing repayment (`#payoffDuringARepaymentIsStaleOrClosedNeverWrong`, scenario 7); ten executions close it
once (`#tenExecutionsOneClosure`); no condition is written after the closure and the database refuses one
(`#payoffBesideTheDelinquencyWorkerWritesNoConditionAfterClosure`, scenario 10); `lending V022` admits the early billing,
the `PAYOFF` repayment and the `PAID_OFF_EARLY` closure only beside the loan's `payoff_execution`, which cannot commit
without its loan `CLOSED` (`#theRowsAreHeldForEveryWriter`).)*

### INV-LND-09 — Interest arithmetic is exact and its conventions declared
**Statement:** Interest is computed in exact decimal under the day count, servicing zone and rounding
modes the agreement version declares (`ACT_365F`, simple daily interest on principal outstanding,
never on interest or fees), by the accrual engine version the agreement pins; a period's posted
accruals sum to the period's exact interest rounded once; terms naming a convention no engine version
implements are refused.
**Why:** An undeclared convention is unreproducible; rounding per day leaks a minor unit per period
per account; floating point makes the same loan bill differently on two machines (`INV-MON-01`,
`INV-MON-03` pointed at lending).
**Enforce:** `STATIC` (`NoFloatingPointMoneyRulesTest` extended to `lending`) + `DOMAIN`
(`ACCRUAL_ENGINE_V1`: the cumulative rounding — `posted(d) = round_HALF_EVEN(cum_exact(d)) −
round_HALF_EVEN(cum_exact(d−1))` within a period, `BigDecimal` at scale 20 only inside the engine,
the division once per day; the engine and conventions pinned on the agreement; an engine change a new
engine version, the old one kept for replay).
**Verify:** The accrual engine's hermetic tests — cumulative rounding equal to one rounding per
period, 29 February accruing one day, zero days writing no entry (`P11-TSK-006`…`009`); billed
amounts equal to the projection to the minor unit on an on-time path, property-tested (`P11-TSK-017`);
the build rule with a planted `double` in `lending`; the battery's every accrual, billing and
statement replayed `IDENTICAL` in two JVMs, a perturbed accrual flipping the verdict (`P11-TST-002`).
**Phase:** 11

*(Replayed by `P11-TSK-029`, 2026-10-14 - every accrual (period, base from the journal lines dated on or before it, cumulative figure, posting; no elapsed date with principal outstanding missing), billing and statement re-derived by the engine version its row pinned, dispatched on the stored number by `LoanReplayer` - an exhaustive switch over `EngineVersion`, so a new version is a compile error until its replay exists; a number this build does not hold is `DIVERGED` (`ENGINE_ABSENT`), never skipped.)*

*(As built by `P11-TSK-005`, 2026-10-11 - "terms naming a convention no engine version implements are refused": at the
domain by `LoanTerms`' construction (`422 lending.TermsInvalid` - a rate type, rounding mode, day count, zone or
allocation order no engine version of the build implements, an engine version it does not hold, `LoanTermsTest`) and for
every writer by `lending V003`'s `loan_terms_conventions_implemented`, `_rate_type_implemented`,
`_allocation_complete` and `_kind_coherent` `CHECK`s (`LoanTermsDatabaseTest#anUnimplementedConventionIsRefused`,
a raw-SQL term set with each defect refused `23514`). A new convention is a new engine version and a migration
widening those `CHECK`s.)*

*(As built by `P11-TSK-007`, 2026-10-11 - the cumulative rounding: `AccrualEngineV1` posts the change in the
period's once-rounded scale-20 cumulative figure, the `ACT_365F` arithmetic shared with the schedule engine
(`Act365Fixed`); `AccrualMatchesSchedulePropertiesTest` proves every on-time period's postings equal to the
schedule's one rounding and every varying-base period's to its cumulative figure rounded once, two-JVM
identical; `AccrualEngineV1Test` holds 29 February, the reset, zero days and the typed refusals - a convention,
engine version or rate the version does not implement is refused at computation as well as at the terms. Its
half of `INV-LND-02`: a predecessor must be an earlier accrual of the same period, so a date accrued twice
computes nothing.)*

*(As built by `P11-TSK-022`, 2026-10-13 - each statement names the governing agreement version, `STATEMENT_ENGINE_V1` and the accrual engine whose rows it bills; the step refuses terms and an agreement pinning different statement or allocation engines, and `lending V017`'s trigger refuses, for every writer, a statement naming any but the agreement's pinned engines (`CreditLineStatementDatabaseTest#theRowsAreHeldForEveryWriter`); the minimum follows A24 exactly (`#theMinimumPaymentFollowsTheRule`).)*

### INV-LND-10 — Servicing time is the database's
**Statement:** Every business date or instant that decides an accrual, a billing, a statement, a
delinquency condition, a fee, an offer's expiry, an acceptance window or a posting's value date is
read from the database clock after the relevant row lock, in the servicing zone the agreement
declares; no instance clock decides any of them.
**Why:** An instance clock accrues early, late or twice across a midnight; two instances skewed by
seconds disagree about whether an offer has expired or a payment is late.
**Enforce:** `DOMAIN` (`statement_timestamp()` read after the `loan` row lock; offer acceptance
conditional on `expires_at > statement_timestamp()` and expiry on `<=`, exactly one at the boundary;
every lending posting's `posting_date = value_date =` the business date of the database clock under
the lock; no back-valued postings).
**Verify:** Accrual across midnight under ±5 s skew — a date accrued once and never early
(`P11-TSK-016`); acceptance versus expiry at the boundary, exactly one of `ACCEPTED` and `EXPIRED`
(`P11-TSK-012`); the probe judging the accrual boundary on the instance clock turning the skew test
red (`MUTATION_TESTING.md` §2); the storm's two instances with clocks ±5 s across at least three real
business-date boundaries (`P11-TST-001`).
**Phase:** 11

*(As built by `P11-TSK-016`, 2026-10-12 - the accrual: the step reads `statement_timestamp()` after the loan's lock and
accrues only dates that have ended in the agreement's pinned zone; `lending V010`'s trigger refuses, for every writer, an
accrual date that has not ended on the database clock in that zone (never early, whatever a writer's clock says); rule
2's posting and value date are the accrual date - the only lending posting dated before its transaction, and only for a
date already ended. `InterestAccrualDatabaseTest#skewNeverAccruesEarlyOrTwice` runs the step on instances whose own
clocks sit fourteen hours ahead and twelve behind UTC, racing two at a time, over accounts pinned to UTC and to
`-14:00`: each accrues exactly through the day before the database's business date in its own zone, once. Found on the
way, not this task's: a positive servicing zone (`+HH:MM`, admitted by `LoanTerms`) cannot be offered - the canonical
offer terms' escape-free character set and `lending V005`/`V006`'s `CHECK`s exclude `+` - recorded for the owner.)*

*(As built by `P11-TSK-017`, 2026-10-12 - the billing: an instalment is billed only once its due date has come on the database
clock read after the loan's lock, in the pinned zone - `lending V012`'s trigger refuses an earlier billing for every writer
(`InstalmentBillingDatabaseTest#billedOnTheDueDateOnce`); rules 3 and 6 are dated the business date of their transaction, never
back-valued to the due date (ADR-0094 point 8).)*

*(As built by `P11-TSK-022`, 2026-10-13 - the statement: a cycle is stated only once its statement date has come on the database clock read after the line's lock, in the pinned zone - `lending V017`'s trigger refuses an earlier statement for every writer - and only once every date of it is accrued; rule 3 is dated the business date of its transaction. A draw on the statement date belongs to the next cycle whichever order it and the step meet the line's lock in (`CreditLineStatementDatabaseTest#aStatementRacingADrawAtTheCycleEndSerialises`).)*

*(The tests' own time, `P11-TSK-032`, 2026-10-13: the lending database fixtures now derive every date they plant or
compare from the database clock in the pinned zone, as production does (`DisbursementWorld.businessDate`), never from
`current_date` (the session's zone, set from the test JVM's) or an instance clock - two suites had failed from 20:00 to
24:00 UTC on a UTC+4 machine for it. `:lending:databaseTest` runs date-shifted at every hour, so a fixture that dates by
the session or the machine is red whenever it runs (`TESTING.md` §5; `MUTATION_TESTING.md` §2's `P11-TSK-032` row). No
production rule changed.)*

*(The accrual start under a backward clock, `P11-TSK-033`, 2026-10-13: a path X accrual start is the business date of the
payout's conclusion, and `lending V009` clamped that instant with the recording clock's ceiling last - so on a database
clock stepped back behind the payout's birth the conclusion, and the date interest starts, could precede the payout.
`lending V019` applies the birth's floor last (`GREATEST(LEAST(instant, statement_timestamp()), created_at)`; the
`NOT_DISPATCHED` stamp `GREATEST(statement_timestamp(), created_at)`), and the accrual start's "never after today" rank
reads `GREATEST(the clock, the payout's birth)` - the order of facts wins over a clock reading (ADR-0063 decision 2).
Still the database's time, still never an instance's. Verified by
`LoanPayoutStepsDatabaseTest#aConclusionOnAClockBehindThePayoutsBirthIsHeldAtTheBirth`; three probes,
`MUTATION_TESTING.md` §2's `P11-TSK-033` rows.)*

*(Today is a range under a backward clock, `P11-TSK-036`, 2026-10-14: within one transaction the database clock can read
earlier in a later statement than in an earlier one, so "today" read once by the step and judged against another single
reading by the trigger could fall on different days across a midnight - a repayment, draw, disbursement, accrual,
billing, statement, collection attempt, condition, fee, payoff or schedule version refused as dated in the future or the
past. `lending V028`'s ranks judge a ceiling against `lending.business_date_latest(zone)` and a floor against
`lending.business_date_earliest(zone)` - the later and the earlier of the transaction's start's and this statement's
business dates: still the database's time and never an instance's, still never early (a date after every reading the
database clock showed the transaction is refused), and one date on a clock that did not step. The offering instant
(`INV-LND-05`'s pinned offer) is the insert's own stamp. Verified by `BackwardClockDatabaseTest` (`ClockStep` steps the
database clock on demand); `MUTATION_TESTING.md` §2's `P11-TSK-036` rows.)*

*(A principal value date never falls on a date the record has closed, the Phase 11 completion gate's R1, 2026-10-16 -
found three times over, A1-F1 = A2-F4 = A3-I1: the range above admitted any date the clock showed the transaction, and
after a step back across a midnight the clock can show a date the record already passed. Date d accrued just after
d + 1's midnight, the clock stepped back, and a repayment dated d left d's base holding the principal it paid - a day's
interest over-charged, for ever (`INV-LND-02`: interest accrues once, on the principal valued through the date) - or a
draw dated d left it missing - a day under-charged. The order of facts wins (ADR-0063 decision 2): every principal-moving
fact - a repayment of any kind (customer, collection, payoff, prepayment), a draw, a reversal re-instating principal - is
value-dated `max(today, lending.principal_value_floor(loan))`, read under L3: the day after the latest accrual, and for
a line its latest statement's cycle end (no line valued before it may join a stated cycle; the payoff's own early
statement excluded). `lending V030`'s repayment and draw ranks refuse a value date before the floor and admit the floor
though the clock shows the day before it. A servicing step on a clock behind its record writes nothing and is due again
at the floor's midnight. The replay proof now also compares every accrual's base with the final ledger's principal lines
valued through its date, so a principal line value-dated into an accrued date is `DIVERGED` (`ACCRUAL`). Verified by
`BackwardClockDatabaseTest`'s six R1 cases and `LendingProofDatabaseTest#aPrincipalLineValuedIntoAnAccruedDateDiverges`;
`MUTATION_TESTING.md` §2's Phase 11 gate rows.)*

*(The acceptance window at the loan's birth, `P11-TSK-034`, 2026-10-15: the acceptance judges the offer's expiry on the
database clock after L2 and the loan's birth trigger re-judges it on its own statement's clock - two ranks, one clock. An
offer that expired between them (the acceptance waiting on credit's consumption or L6, found by `P11-TST-001`'s storm)
was refused by the backstop in the generic `P0001`, so the customer's lapse surfaced as a `500`. `lending V026` gives the
expiry refusals - the loan's birth, the offer's `ACCEPTED`/`DECLINED` past the instant - their own SQLSTATE, `LNEXP`, and
the acceptance maps exactly that code to `409 lending.OfferNotAcceptable`; no rule changed. Verified by
`LoanAcceptanceDatabaseTest#anOfferExpiringWhileTheAcceptanceWaitsIsNotAcceptable`, `#onlyTheExpiryIsTheCustomers` and
`app`'s `LoanAccountDatabaseTest#anOfferExpiringWhileTheAcceptanceWaitsIsA409`; two probes, `MUTATION_TESTING.md` §2's
`P11-TSK-034` rows.)*

*(The amendment's window at its version's birth, `P11-TSK-035`, 2026-10-15: the same two ranks on the amendment - the
acceptance judges the amendment's offer on the database clock at its read under L3, then accrues, bills and plans, and
`loan_agreement_is_born_once` re-judges the decision's `expires_at` on its own statement's clock. An offer that expired
between them (found by `P11-TSK-034`'s gate) was refused in the generic `P0001` - a `500`. `lending V027` judges that
expiry last, after every identity and credit check, in its own SQLSTATE, `LNAEX`, and the acceptance maps exactly that
code to `409 lending.AmendmentLapsed`; no rule changed. Verified by
`AmendmentDatabaseTest#anAmendmentLapsingWhileTheAcceptanceWaitsIsLapsed`, `#onlyTheLapseIsTheCustomers` and `app`'s
`LoanAccountDatabaseTest#anAmendmentLapsingWhileTheAcceptanceWaitsIsA409`; two probes, `MUTATION_TESTING.md` §2's
`P11-TSK-035` rows.)*

### INV-LND-11 — A servicing correction is a reasoned, four-eyes act that posts
**Statement:** Fee waivers, interest waivers, repayment reversals and amendments (restructuring)
carry a reason, are proposed by one person and approved by another — never the proposer, never an
employee whose own party holds the loan — and change figures only by new journal entries, a reversal
or fee waiver bounded by the entry it corrects; an amendment also takes the customer's acceptance.
No person edits a computed figure, adjusts a loan account freely, creates a loan, reprices outside an
amendment, draws or disburses.
**Why:** One person forgiving debt or reversing a repayment is the classic insider loss
(`INV-AUD-04`); a correction that edits a figure erases the history the borrower was billed on.
**Enforce:** `DB-CONSTRAINT` (the four-eyes `CHECK` on every proposal table; `repayment_reversal UNIQUE
(repayment_id)`; the reversal bound, `INV-REV-02`) + `DB-PRIVILEGE` (the proposal and correction rows
`INSERT`-only, status columns conditionally updatable only) + `DOMAIN` (the proposal row's conditional
`PROPOSED` edge under its lock; `422 lending.ReasonRequired`, the reasons screened for card and account
numbers; `403 lending.SelfApprovalRefused`; `lending.SelfDealingRefused` audited `FAILED`; every loan
purpose closed to free adjustments; a repayment that closed a loan not reversible).
**Verify:** Self-approval refused at the domain and at the `CHECK`, each alone, by a raw-SQL writer
too; ten approvers of one proposal, one decision (`P11-TSK-020`, `P11-TSK-025`, `P11-TSK-027`);
scenario 6 — `RepaymentReversalDatabaseTest#aReversalAfterAllocationReopensWhatItPaid`
(`P11-TSK-020`), the entry swapped exactly, allocations negated by rows, later allocations untouched;
a waiver above what is due refused `422 lending.WaiverExceedsDue` (`P11-TSK-025`); scenario 8
(`P11-TSK-027`); the adjustment guard refusing every loan purpose (`P11-TSK-003`).
**Phase:** 11

*(The Phase 11 gate's R3, 2026-10-16 - a fee waiver is bounded by what of the fee was never forgiven too:
`least(billed - paid, assessed - fee waived - refunded)`, at the domain and by `lending V029`'s decision trigger for every
writer - so a fee refunded and re-opened by a reversal is never forgiven again, and its income never goes below zero; the
subledger proof's `FEE_INCOME_NEGATIVE` arm watches every assessment's income. `FeeAndWaiverDatabaseTest#aRefundedFeeIsNeverForgivenTwice`.)*

*(As built by `P11-TSK-020`, 2026-10-13 - the repayment reversal: `lending V016`'s `repayment_reversal` (money-free machine row,
column-scoped `UPDATE` of its decision columns, four-eyes `CHECK`, one live proposal per repayment by the partial `UNIQUE
(repayment_id) WHERE status IN ('PROPOSED', 'APPROVED')`, reasons screened by twin `CHECK`s) and
`repayment_allocation_reversal` (INSERT-only negating rows, one per allocation, the same amount, once ever, whole at
commit); `RepaymentReversals` refuses the proposer's approval, an operator of the loan's own party (`SelfDealingRefused`,
recorded `FAILED`), a loan not `ACTIVE` (A12), a repayment already reversed or whose held credit was since applied; the
approval posts rule 10 by `ReversalService` (the exact swap, `INV-REV-02`). Verified by `RepaymentReversalDatabaseTest` -
scenario 6, ten approvers one reversal, self-approval at both ranks, self-dealing, the closed loan, the original
byte-identical, the bound.)*

*(As built by `P11-TSK-025`, 2026-10-13 - fee and interest waivers: `lending V021`'s `waiver` (the proposal, carrying the
amount, so INSERT-only - no lending money table takes an UPDATE) and `waiver_decision` (born once, `UNIQUE (waiver_id)`;
four eyes by `CHECK` over the proposer copied and pinned by the composite key `(waiver_id, proposed_by)`; reasons screened by
twin `CHECK`s); `Waivers` refuses the proposer's approval, an operator of the loan's own party (`SelfDealingRefused`,
recorded `FAILED`), an account not `ACTIVE` or `CLOSING`, a second proposal of the kind on an item while one awaits, and
**any amount above what is due now** - `422 lending.WaiverExceedsDue`, at the proposal and again at the approval, from the
rows the ledger is proven against under L3, and once more by the decision trigger for every writer; the fee waiver posts
rule 8 by `ReversalService` (bounded by the fee's own entry, `INV-REV-02`), a refund rule 8a, an interest waiver rule 9 -
figures change only by new entries. Verified by `FeeAndWaiverDatabaseTest` - ten approvers one decision, self-approval at
both ranks (and a forged proposer copy refused by the composite key), self-dealing, the unpaid remainder only, the refund,
the interest waiver, a waiver racing a repayment of the fee, a waiver of the last receivable closing.)*

### INV-LND-12 — Delinquency is derived and its history append-only
**Statement:** Days past due is the number of days between the oldest past-due item's due date and
the current business date (the database clock, the agreement's zone), zero when nothing is past due —
never a stored counter; the bucket and the default flag (DPD ≥ the agreement's threshold, 90 by
default, held until cure - as built, `DPD ≥ threshold` or (already defaulted and DPD > 0), `DelinquencyState`;
the statement read "follow from it" until `P11-DOC-001`, 2026-10-15) follow from it; each condition change is recorded once, only on a change, and never
rewritten or deleted.
**Why:** A counter drifts with every missed or repeated run; a rewritten condition history hides what
the borrower and the collections owner were told and when.
**Enforce:** `DB-CONSTRAINT` (`loan_condition_event UNIQUE (loan_id, business_date, kind)`) +
`DB-PRIVILEGE` (`loan_condition_event` `INSERT`-only for every role) + `DOMAIN` (the condition derived
daily under the `loan` row lock from billed and allocated rows; re-running a day writes nothing; a
terminal account skipped).
**Verify:** Delinquency transitions current → each bucket → cure → default → cure, a missed day caught
up and a re-run day writing nothing (`P11-TSK-024`); raw-SQL `UPDATE` and `DELETE` on the condition
table refused for every role; scenario 10 (`P11-TSK-026`); the storm's DPD equal to the date difference
for every account at rest (`P11-TST-001`).
**Phase:** 11

*(As built by `P11-TSK-024`, 2026-10-13 - `lending V020`'s `loan_condition_event`, `UNIQUE (loan_id, business_date, kind)`,
granted `SELECT, INSERT` only, its trigger refusing UPDATE, DELETE and TRUNCATE for every role and holding every row to the
derivation: `days_past_due = lending.days_past_due(loan, business_date)` (the oldest billed item unpaid at the end of its due
date), the bucket that DPD's under the governing agreement's pinned bounds, the flag set at the threshold and cleared only at
cure, a change from the latest row of its kind, dated the transaction's business date, never on a terminal account - so the
`DB-CONSTRAINT` rank covers the derivation as well as the unique. T-svc records at most once a business date, only on a
change; every decision derives the condition live. Verified by `DelinquencyDatabaseTest` (eleven cases, among them
`#currentToEachBucketAndCure`, `#defaultAtNinetyClearedAtCure`, `#reRunningADayWritesNothing`,
`#aMissedDayComputesFromTheDates`, `#theHistoryIsAppendOnlyForEveryRole`) and `DelinquencyStateTest`; probes in
`MUTATION_TESTING.md` section 2.)*

*(As built by `P12-TSK-020`, 2026-10-19 - BNPL's conditions by analogy, the same shape: `bnpl V013`'s `bnpl_condition_event`,
`UNIQUE (obligation_id, business_date, kind)`, `SELECT, INSERT` only, its trigger re-taking B4 and holding every row to the
derivation - `days_past_due = bnpl.days_past_due(obligation, business_date)` over every component (an unpaid late fee keeps
its instalment past due), the bucket under the pinned bounds, the flag's rule, a change from the latest row of its kind,
never dated before a recorded change, never on a closed obligation. Verified hermetically by `BnplConditionsTest` and
`BnplDelinquencyStateTest` (run) and by `BnplConditionDatabaseTest` (owed a first run); probes in `MUTATION_TESTING.md`
section 2.)*

### INV-LND-13 — Lending capital is recognised only from bank evidence and never over-deployed
**Statement:** The `LENDING_CAPITAL` account of a currency is credited only by bank recognition of a
statement line matched to a four-eyes capital contribution's expectation; and at every commit, for
every currency, recognised capital less the committed principal of loans awaiting disbursement less
the principal outstanding of every loan and line is at least zero, judged under the capital
account's row lock at every loan acceptance, every draw and every repayment reversal that re-instates principal.
**Why:** A disbursement or draw raises the platform's wallet liabilities without cash entering;
unless recognised platform money backs it, customers' safeguarded money silently funds loans and the
safeguarding position becomes false. Capital posted by a person, or adjusted to fit, is not capital.
**Enforce:** `DB-CONSTRAINT` (the contribution's four-eyes `CHECK`; `capital_contribution UNIQUE
(expectation_ref)`) + `STATIC` (`LendingBooksHaveOnePosterTest`: only capital recognition posts to
`LENDING_CAPITAL`) + `DOMAIN` (`LENDING_CAPITAL` in `closedToFreeAdjustments()` and in
`reconciledPositions()`'s single-poster discipline; bank recognition the one poster of `CASH_AT_BANK`,
`INV-SET-06`; headroom derived from journal lines under the capital row lock, L6, a shortfall refused
`422 lending.CapitalUnavailable`; interest and fee income never counted as capital). *As built by `P11-TSK-004`:*
the four-eyes `CHECK` and `UNIQUE (expectation_ref)` stand on `capital_contribution_event`'s decision row (the
contribution's tables are INSERT-only), the proposer copy pinned by a composite key; rule 0 is the bank statement's own
recognition entry crediting `LENDING_CAPITAL` for a line `CapitalRecognition` claimed - an approved contribution's
expectation under the statement's source, exact amount, inside its window, locked - and `capital_recognition` is born
once per contribution and per line (reconciliation `V022`), which also refuses any allocation to a contribution but a
`RECOGNITION` decision's and never lets one age; the resolution machine disposes of nothing on it.
**Verify:** `LendingCapitalProof` (`P11-TSK-004`) per currency in one `REPEATABLE READ` snapshot —
every capital journal line a recognised contribution's, headroom ≥ 0 at rest, the safeguarding
adjustment `lending_funded(c)` published — gauged `finapp.lending.capital.proof{verdict}` and alerting
on any failure (built: `LendingCapitalDatabaseTest#theProofFindsAForeignPosting`); capital recognised only from a
statement line, a duplicate line recognising once, an unmatched contribution yielding nothing, headroom reading the
ledger not the status, the recognition's one-way wait on L6 and two headroom consumers racing for the last capital
with one admitted (`LendingCapitalDatabaseTest`, `P11-TSK-004`); acceptances racing for the last capital, one
admitted (`P11-TSK-012`);
draws racing for the last capital (`P11-TSK-021`); the probe skipping the capital lock letting two
acceptances over-deploy (`MUTATION_TESTING.md` §2); the capital proof in every storm round with
headroom contested (`P11-TST-001`).
*(The first consumer built by `P11-TSK-012`, 2026-10-11 - a loan's acceptance judges headroom under L6, the committed term the agreement principal of the loans `PENDING_DISBURSEMENT`, read before the outstanding; a line commits none (A28); verified by `LoanAcceptanceDatabaseTest#acceptancesRacingForTheLastCapitalAdmitOne` (the lock-wait and ten loans for one loan's headroom, one admitted) and `#aLineCommitsNoCapital`.)*
*(The second consumer built by `P11-TSK-021`, 2026-10-12 - a draw judges headroom under L6 after the line's lock (L3) and its available limit; verified by `CreditLineDrawDatabaseTest#drawsRacingForTheLastCapitalAdmitOne` (the lock-wait, then five draws and five acceptances for exactly one's headroom - one admitted, headroom exactly zero) and `#drawingTakesTheLineThenTheCapital`.)*
*(Read at the exit review, `P11-DOC-001` (2026-10-15): "at every commit" holds for every commit that DEPLOYS capital -
each judges headroom under L6. One commit withdraws it: a repudiated bank statement reversing a recognised contribution
(`REPUDIATE_BATCH`), serialised against every L6 holder by its foreign-key share but free to leave headroom below zero
when the capital was already deployed - the bank's evidence withdrawn after the fact. The capital proof reports it
(`finapp.lending.capital.proof`, alerting) and every later consumer is refused; decided by `P11-TSK-012` (neither
prevented nor locked further) and confirmed by this review - `CURRENT_STATE.md` §Known Architectural Debt.)*
**Phase:** 11

*(Extended at the Phase 11 → 12 transition (2026-10-16), ADR-0104 (`Proposed`), in force since `P12-TSK-005` (2026-10-17; read "in force when `P12-TSK-005` lands" until its gate): the headroom also subtracts every BNPL obligation's outstanding principal (`BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE`, read by ledger purpose so lending learns no bnpl type) and is judged under the same capital row lock (L6) at every BNPL purchase and every principal-re-instating BNPL repayment reversal; a BNPL purchase has no committed-but-undisbursed term. `INV-BNPL-10` is the BNPL side.)*

### INV-LND-14 — A draw never exceeds the available limit
**Statement:** A credit line draw is admitted only if its amount is at most the line's available
limit — the agreement's limit less drawn principal (`LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE`), derived
from journal lines under the line's row lock — and only while the line is `ACTIVE` with draws not
suspended; the available limit is never a stored number; interest and fees never consume it and a
principal repayment restores it at commit.
**Why:** A draw past the limit is credit no decision approved; a stored available limit drifts from
the ledger and lets two concurrent draws each pass alone.
**Enforce:** `DB-CONSTRAINT` (`credit_line_draw UNIQUE (loan_id, draw_key)`; the ledger key
`lending.draw:<drawId>`) + `DOMAIN` (the available limit derived under the `loan` row lock, L3, in the
draw's transaction; `422 lending.LimitExceeded`; `409 lending.DrawsSuspended` while past due or
defaulted).
**Verify:** Two draws racing for the available limit, one admitted and the other `422
lending.LimitExceeded`; ten draws under one key, one entry; a suspended line refusing a draw
(`P11-TSK-021`); the battery's ≥ 2,000 generated lines replayed `IDENTICAL` (`P11-TST-002`); the
storm's no line's drawn principal above its limit at rest (`P11-TST-001`) - *but by the repayments a four-eyes
reversal restored (`-023`'s owner-approved path: a paydown reversed after a re-draw re-establishes drawn principal and
may lift it above the limit, the available limit then zero); since `P11-DOC-001` (2026-10-15) the storm's C10 bounds
such a line by exactly its approved reversals, where it had excused it for ever*.
*(Built by `P11-TSK-021`, 2026-10-12 - `CreditLineDraws` derives the limit from the line's journal lines under L3 and refuses
`422 lending.LimitExceeded` before the capital lock; `lending V013`'s trigger holds every writer to an `ACTIVE` line, its
governing agreement version and an amount within the limit; verified by `CreditLineDrawDatabaseTest#twoDrawsRacingForTheLimitAdmitOne`
(two for one limit, one admitted; ten for its last 300.00, exactly three), `#aDrawBeyondTheAvailableLimitIsRefused`,
`#interestAndFeesNeverConsumeTheLimit` and `#tenDrawsOneKey`; the suspension check built over its derived condition, brought
into force and tested biting by `P11-TSK-024`.)*
*(As built by `P11-TSK-023`, 2026-10-13 - the restoring side: a line's repayment pays what is due, then principal not yet
due (`CR LOAN_PRINCIPAL`), so the limit the next draw derives under L3 is restored at the repayment's commit with no stored
figure moving (`CreditLineRepaymentDatabaseTest#aPaydownRestoresTheAvailableLimit` - a paydown racing a draw for the restored
limit admits the draw only after it, never beyond the limit); a closing line takes no draw (`#closureStopsDraws`,
`#closureRacingADrawSerialises`); a paydown reversed after a re-draw is admitted and leaves drawn principal above the limit
with the available limit zero (`#aLinePaydownReversedAfterARedrawIsAdmitted`).)*
**Phase:** 11

*`INV-LND-01`…`INV-LND-14` catalogued by the Phase 10 → 11 transition (2026-10-10), the `INV-CRD`
precedent: Phase 11's gate properties given stable IDs before any lending code exists, so the
register can demand their demonstrations by identifier rather than by prose. Decisions in
ADR-0090…0100; the machines in `LENDING_LIFECYCLES.md`; the design in `PHASE_11_PLAN.md` (§6 names
these entries, §7.4 their contention rows, §13.2 the ten scenarios they cite). Task and test names
are the plan's; the building task may rename a test and says so. Until Phase 11's first task lands,
nothing these entries name is implemented; every statement is the decided design, corrected by the
tasks that build it.*

---

# BNPL — `INV-BNPL`

### INV-BNPL-01 — An obligation's figures are ledger balances
**Statement:** Every principal, interest, fee and credit-balance figure of a BNPL obligation is a
balance of one of its six per-obligation ledger accounts (`BNPL_PRINCIPAL`, `BNPL_PRINCIPAL_DUE`,
`BNPL_INTEREST_ACCRUED`, `BNPL_INTEREST_DUE`, `BNPL_FEES_DUE`, `BNPL_CREDIT_BALANCE`, owner kind
`BNPL_OBLIGATION`), derived from journal lines. No `bnpl` table holds a mutable monetary value: the
obligation row carries identity, product, status and permits only, and every operational amount
(financed, billed, accrued, allocated, cancelled by a refund, assessed, returned as excess) is an
immutable, born-once row whose sum equals the ledger; a difference is an alert, never self-corrected.
**Why:** A stored BNPL balance is a second financial truth that drifts from the ledger — `INV-BAL-01`
and `INV-LED-04` pointed at bnpl. What a customer still owes after partial repayments and refunds
must be explainable from journal lines, and a refund's latest-first walk cancels against that
figure: read from a drifted column, it cancels what was never owed or leaves owed what was paid.
**Enforce:** `DB-PRIVILEGE` (schema `bnpl` `REVOKE ALL FROM PUBLIC`; the application role holds
`SELECT, INSERT` on the fact tables and conditional `UPDATE` only of status and permit columns on the
machine tables — never `UPDATE` of a money column, never `DELETE`) + `STATIC`
(`BnplSchemaHasNoMutableMoneyTest`: every `*_minor` column on an `INSERT`-only table;
`BnplBooksHaveOnePosterTest`: only bnpl's operations post to the BNPL purposes) + `DOMAIN` (every
deciding figure — the due state an allocation or a refund's split runs over, a payoff amount —
derives from journal lines and rows through `BalanceDerivation` under the obligation's row lock,
B4, never from the display-only projection, `INV-BAL-05`; bnpl posts only through `PostingService` /
`ReversalService`; every BNPL purpose joins `AccountPurpose.closedToFreeAdjustments()`).
**Verify:** `BnplSchemaHasNoMutableMoneyTest` with a planted mutable money column (`P12-TSK-002`);
the adjustment guard refusing every BNPL purpose and `BnplBooksHaveOnePosterTest` refusing a planted
poster (`P12-TSK-003`); `BnplSubledgerProof` zero for every obligation — each of the six balances
equal to its rows' sums, no due account negative, a `CLOSED` obligation's six zero — gauged
`finapp.bnpl.subledger.proof` and alerting on any failure (`P12-TSK-025`); the storm's at-rest
subledger proof for every obligation in every round (`P12-TST-001`).
**Phase:** 12

*(As built by `P12-TSK-018`: the customer's repayment allocates over the due state `BnplReceivables` derives under B4 - each billed item's billed less every payer's paid, refused (`AllocationAnomaly`, metered `finapp.bnpl.allocation.anomaly`) unless those rows explain the due accounts' ledger balances - and `bnpl V010` holds every repayment allocation to a billed instalment, never principal not yet due, and no component paid beyond what it billed at commit, over every payer. The closure's "every receivable zero" is the five receivable accounts' ledger balances under B4 beside the rows' side its trigger re-judges. Hermetic: `BnplRepaymentsTest#theDueStateIsDerivedFromRowsNotTheProjection`, `#tenKeysOneObligationConserve`; database: `BnplRepaymentDatabaseTest` (owed).)*

*(As built by `P12-TSK-025`, 2026-10-20: `BnplSubledgerProof` judges every obligation in one read-only `REPEATABLE READ` snapshot from its journal lines (never `ledger.account_balance`) against its rows - the six identities with reversals (allocated − negated) and waivers (assessed − allocated + negated − released − waived), every entry on the six accounts named by a row, no due account negative, a `CLOSED` obligation all zero, fee income never negative - gauged `finapp.bnpl.subledger.proof{verdict}`; `BnplReplayProof` re-derives every figure by its pinned engines and terms, `finapp.bnpl.replay{verdict}`; `BnplThreeWayProof` adds the merchant leg, the economic identity per purchase and the ledger arms per currency, `finapp.bnpl.threeway.proof{verdict}`. `BnplBooksHaveOnePosterTest` is green again - red since `-016`, merchant's `MerchantPayable` now its reader permit - and `#theProofsSeeEveryWriter` non-vacuous. Hermetic: `BnplSubledgerProofTest`, `BnplReplayProofTest`, `BnplThreeWayProofTest`, `BnplProofsAtRestTest`; database: `app`'s `BnplProofDatabaseTest` (owed).)*

### INV-BNPL-02 — Financing and obligation are born atomically
**Statement:** A BNPL purchase's merchant financing and the customer's obligation are one commit:
the credit decision's consumption, the capital judgement, agreement version 1 and its acceptance
evidence, the purchase, the merchant financing record, the obligation (`ACTIVE`) with schedule
version 1 and its per-obligation accounts, the financing entry F1 (`BNPL_PRINCIPAL` debited the gross
G, `MERCHANT_PAYABLE` credited G and debited the merchant fee f, `BNPL_MERCHANT_FEE_INCOME` credited f),
for pay-in-4 instalment 1's billing and its collection from the wallet, and the checkout session
completed by financing with its order — all commit in the acceptance's one transaction (T-pur), or
none does. No merchant is financed without a customer receivable, no receivable exists without its
financing, and there is no committed-but-unfinanced purchase state. The original criterion's
"or with a tested compensating path" is not taken: with one transaction, no compensation is reachable.
**Why:** A merchant credited without a customer receivable is platform money given away with no
claim behind it; a receivable without a financing is a debt the customer owes for nothing. A
compensating path would open a window in which one leg exists without the other — visible to a
merchant payout (`INV-MER-05`) and to the next credit decision's exposure read.
**Enforce:** `DOMAIN` (T-pur is one transaction in the Phase 12 lock order — L0, B2, B3 judged at
once, L6, (P), L8 — and every refusal, `422 bnpl.CapitalUnavailable` and `422 bnpl.InsufficientFunds`
included, rolls back everything) + `DB-CONSTRAINT` (a deferred trigger: a `merchant_financing` row
exists iff its purchase, its obligation and its journal entry exist; `merchant_financing UNIQUE
(purchase_id)`, `UNIQUE (journal_entry_id)`; the session's `financed_by_ref` set once, checkout
`V004`).
**Verify:** Scenario 14 — `#aFailureAtEveryStepLeavesNothing` (`P12-TSK-013`): a forced failure at
each T-pur step leaves no financing, no consumption, no capital commitment, no obligation and no
order; the deferred trigger proven alone by a raw-SQL writer (`P12-TSK-013`);
`BnplOutboxAtomicityDatabaseTest` — a crash between posting and publication loses nothing
(`P12-TSK-013`); the three-way proof's merchant and customer legs agreeing for every purchase,
gauged `finapp.bnpl.threeway.proof{verdict}` (`P12-TSK-025`), at rest in every storm round
(`P12-TST-001`).
*(As built by `P12-TSK-013` (2026-10-18): `BnplAcceptances` (T-pur) - one transaction in the Phase 12 lock order; `bnpl V006`'s three deferred constraint triggers on `purchase_is_financed_at_commit` (a purchase, its financing and its obligation commit together, the purchase `FINANCED` beside its `ACCEPTED` offer, agreement version 1, its evidence and schedule version 1). Refined: the financing entry is named by id (`NOT NULL`, `UNIQUE`) - no bnpl trigger reads a ledger table (each module migrates its own schema in no guaranteed order, the `P11-TSK-013` refinement), so the entry's existence and lines are the one transaction's and the three-way proof's (`-025`). Hermetic: `BnplAcceptancesTest#aFailureAtEveryStepLeavesNothing` (23 steps), `BnplAcceptanceMigrationTest`. Database, written and owed a first run: `BnplPurchaseDatabaseTest#aFailureAtEveryStepLeavesNothing`, `BnplAcceptanceDatabaseTest#aFinancingRowWithoutItsObligationIsRefused`, `BnplOutboxAtomicityDatabaseTest`.)*
**Phase:** 12

### INV-BNPL-03 — One decision, one agreement; one session, one purchase; one purchase, one financing
**Statement:** A credit decision funds at most one BNPL agreement; a checkout session is paid by at
most one purchase, and never by both a BNPL purchase and a payment; a purchase has at most one
merchant financing record and one financing journal entry. Each holds under one key replayed, under
many keys, across instances, and against a card or wallet confirmation of the same session.
**Why:** A decision consumed twice is credit no one approved (`INV-LND-06`'s rule extended to a new
consumer kind); a session paid twice credits the merchant twice for one sale; a second financing
entry is merchant money created from nothing and a second receivable the customer never agreed to.
**Enforce:** `DB-CONSTRAINT` (credit's consumption uniques through `consume(BNPL_AGREEMENT,
agreementId)`, the `SECURITY DEFINER` path; `bnpl_offer UNIQUE (application_id)`, `UNIQUE
(decision_id)`; `purchase UNIQUE (checkout_session_id)`, `UNIQUE (offer_id)`; `merchant_financing
UNIQUE (purchase_id)`; the ledger key `bnpl.financing:<purchase>`) + `DOMAIN` (the claim
`bnpl.offer-acceptance:CUSTOMER:<id>`; the offer conditional `OFFERED` under B2; the session judged
`OPEN` and unexpired under B3 — a session not `OPEN` refused `409 bnpl.SessionNotPayable` before any
further lock, and a card or wallet confirmation that loses refused by checkout's `NOT_CONFIRMABLE`).
**Verify:** Scenario 1 — `BnplPurchaseDatabaseTest#theSameAcceptanceTwiceFinancesOnce` — and scenario
2 — `BnplPurchaseDatabaseTest#bnplAndCardOnOneSessionCompleteOnce` (`P12-TSK-013`); BNPL completion
against card confirmation against expiry on one session, exactly one (`P12-TSK-007` — built: checkout
`V004`'s financing edge, `financed_by_ref` set once and exclusive with the intent, one session per
purchase; `CheckoutFinancingDatabaseTest` written and not run under the owner's instruction, its
hermetic half `CheckoutBnplSessionsTest` and `CheckoutSessionMigrationTest`); ten consumers
of one decision for `BNPL_AGREEMENT`, one consumption (`P12-TSK-001`); the probe dropping `UNIQUE
(checkout_session_id)` on purchase (`MUTATION_TESTING.md` §2); one financing per purchase and one
purchase per session at every storm round (`P12-TST-001`). Upstream of the purchase, one session holds at most one
open application (`P12-TSK-010` — built: `bnpl V003`'s partial unique over `SUBMITTED`, `AWAITING_DECISION`,
`OFFERED`, `UNIQUE (decision_request_id)`; `BnplApplicationDatabaseTest#tenApplicationsForOneSessionOpenOne` written
and not run under the owner's instruction, its hermetic half `BnplApplicationsTest` and `BnplApplicationMigrationTest`).
One approved decision is offered once, for exactly the purchase (`P12-TSK-011` — built: `bnpl V004`'s `UNIQUE
(application_id)`, `UNIQUE (decision_id)`, the application's `→ OFFERED` only beside its offer, and an `OFFERED`
application holding its session for good - a declined or expired offer frees no second application on it;
`BnplOriginationDatabaseTest#tenSweepersOfferOnce` written and not run under the owner's instruction, its hermetic half
`BnplOriginationTest#tenSweepersOfferOnce` and `BnplOfferMigrationTest#theArbitersArePresent`).
*(As built by `P12-TSK-013`: `purchase UNIQUE (checkout_session_id)`, `UNIQUE (offer_id)`; `merchant_financing` keyed by its purchase, `UNIQUE (journal_entry_id)`; `bnpl_obligation UNIQUE (purchase_id)`, `(agreement_id)`, `(decision_id)`; the claim `bnpl.offer-acceptance:CUSTOMER:<id>`; B2's conditional `OFFERED` and B3 judged at once. Hermetic: `BnplAcceptancesTest#theSameAcceptanceTwiceFinancesOnce`; database, owed: `BnplPurchaseDatabaseTest#theSameAcceptanceTwiceFinancesOnce`, `#bnplAndCardOnOneSessionCompleteOnce`, `#bnplAndAWalletPaymentOnOneSessionCompleteOnce`.)*
**Phase:** 12

### INV-BNPL-04 — The financing conserves the purchase
**Statement:** For every purchase, the receivable principal debited to `BNPL_PRINCIPAL` equals the
checkout session's gross G exactly, and G = the net credited to the merchant's `MERCHANT_PAYABLE` + the
merchant fee f credited to `BNPL_MERCHANT_FEE_INCOME`, to the minor unit, in one entry balanced in the
purchase's currency. f is computed once, from the merchant's BNPL fee schedule version pinned on the
purchase — `f = round_HALF_EVEN(G · bps / 10 000) + fixed`, `0 ≤ f < G` — and the net is derived by
subtraction, never rounded on its own.
**Why:** `INV-MER-04` at the BNPL boundary. A receivable above G charges the customer for nothing; one
below it finances a sale the platform cannot recover; a net rounded independently creates or destroys
a minor unit per purchase, invisibly, at volume.
**Enforce:** `DOMAIN` (`MerchantFeeSplit` — the split is subtraction by construction; the fee version
pinned by `bnpl_fee_pin`, frozen, `INV-MER-03`; a purchase must cover its fee, ADR-0058) +
`DB-CONSTRAINT` (`merchant_financing` `CHECK (gross = fee + net)`; a trigger holding the financing
entry's lines to its row) + the journal's per-currency balance (`INV-LED-01`).
**Verify:** `MerchantFeeSplit` property tests over amounts, basis points and fixed parts
(`P12-TSK-006`); the financing row and entry asserted by every acceptance case (`P12-TSK-013`); the
three-way proof's merchant leg — Σ (gross − fee) over purchases less refunds' payable debits equal to
the BNPL lines on `MERCHANT_PAYABLE`, per merchant — and its identity leg per purchase (`P12-TSK-025`);
the battery (`P12-TST-002`).
*(As built by `P12-TSK-013`: F1 `DR BNPL_PRINCIPAL G; DR MERCHANT_PAYABLE f / CR MERCHANT_PAYABLE G; CR BNPL_MERCHANT_FEE_INCOME f` (a zero fee two lines) from merchant's pinned split; `merchant_financing CHECK (gross = fee + net)`. Refined: no trigger holds the entry's lines to the row (no bnpl trigger reads the ledger) - one code path builds both from one `MerchantFeeSplit`, proven by `BnplAcceptancesTest#theFinancingConservesThePurchase`; database, owed: `BnplPurchaseDatabaseTest#theFinancingConservesThePurchase`.)*
**Phase:** 12

### INV-BNPL-05 — A schedule conserves principal exactly
**Statement:** Every BNPL schedule version's instalment principals sum exactly to the principal it
schedules — version 1 to G; version n+1 to the principal outstanding at its birth after a refund's
cancellation or a payoff recalculation, the billed instalments of the version it supersedes kept.
Pay-in-4 (`BNPL_SCHEDULE_ENGINE_V1`): four instalments, instalments 2–4 `A = floor(P / 4)` to the
minor unit and instalment 1 `P − 3A`, due on the purchase's business date D1 and on D1 + 14, + 28 and
+ 42 calendar days, no interest. Pay-in-N (`SCHEDULE_ENGINE_V1`, through `creditmath`): n ∈ {3, 6, 12}
monthly level instalments under `INV-LND-03`. Every rounding sits at a declared point of a pinned
engine version; a recalculation is a new version, never an edit.
**Why:** Rounding leakage of one minor unit per purchase is, across a portfolio, money created or
destroyed with no economic event; a schedule that does not sum to what was bought makes the customer
owe more or less than the price, and the refund's latest-first walk cancels against it.
**Enforce:** `DOMAIN` (each engine asserts conservation at construction; the pay-in-4 engine refuses
any other shape) + `DB-CONSTRAINT` (`bnpl_schedule UNIQUE (obligation_id, schedule_version)`,
`bnpl_instalment UNIQUE (schedule_id, sequence)`, both `INSERT`-only; a deferred conservation trigger
at commit).
**Verify:** `BnplScheduleEngineV1`'s property tests and golden cases — amounts not divisible by four, a
leap day, a year end (`P12-TSK-008` - built: `BnplScheduleEngineV1Test`, `BnplScheduleEngineV1PropertiesTest` with its
two-JVM digest; the record `BnplSchedule` refuses a non-conserving value at construction); pay-in-N on the extracted engines with every Phase 11 engine
identity and golden hash unchanged and `P11-TST-002`'s battery re-run `IDENTICAL` (`P12-TSK-002`); the
deferred trigger proven alone by a raw-SQL writer (`P12-TSK-013`); the battery's ≥ 10,000 generated
purchases conserving exactly and replayed `IDENTICAL` in two JVMs (`P12-TST-002`); `BnplReplayProof`
re-running the pinned engine over every schedule version (`P12-TSK-025`).
*(As built by `P12-TSK-013`: schedule version 1 stored (`bnpl_schedule`, `bnpl_instalment`) from the pinned engine over the purchase's business date; `bnpl_schedule_conserves`, deferred, holds Σ instalment principal = the schedule's principal = the financed gross and the numbering 1..n; pay-in-4's 14-day dates ranked at insert. Hermetic: `BnplAcceptanceMigrationTest#theScheduleConserves`; database, owed: `BnplAcceptanceDatabaseTest#aScheduleThatDoesNotConserveIsRefused`.)*
*(As built by `P12-TSK-015`: billing conserves the schedule over the obligation's life - `bnpl V008`'s billing trigger holds every instalment's principal to `least(remaining, max(0, amount - interest))` and the last instalment's to the remainder, so Σ billed principal = the version's principal; pay-in-N's billed interest is exactly the period's posted accruals. Hermetic: `BnplServicingTest#theLastInstalmentBillsTheRemainder`, `#theLastInstalmentBillsTheRemainderWhateverTheInterest`, `#pnBillsTheAccruedInterestNotTheProjection`; database, owed: `BnplBillingDatabaseTest#theLastInstalmentBillsTheRemainder`.)*
**Phase:** 12

### INV-BNPL-06 — A refund preserves value
**Statement:** Every BNPL refund R splits exactly as R = Σ unpaid instalment principal it cancels + the
excess E returned to the customer. Instalments are walked from the last to the first, each cancelling
`min(remaining R, its unpaid principal)` — unbilled principal from `BNPL_PRINCIPAL`, billed-unpaid
principal from `BNPL_PRINCIPAL_DUE`, a partly paid instalment only its unpaid part, never an amount
already paid — and E = R − Σ cancelled is credited to the customer's `CUSTOMER_WALLET` (held in
`BNPL_CREDIT_BALANCE` while the wallet cannot be credited). The merchant funds it: `MERCHANT_PAYABLE`
is debited R − r_f and `BNPL_MERCHANT_FEE_INCOME` r_f, where `r_f = cum_fee(refunded after) −
cum_fee(refunded before)` and `cum_fee(x) = round(f · x / G)`, so the fee returned over any sequence of
refunds is order-independent and telescopes to exactly f at a full refund. Nothing else moves but the
releases the refund's own entry names (F5a): a late fee assessed on a cancelled instalment, and — when
a pay-in-N refund cancels the whole remaining principal — accrued and billed-unpaid interest (A11);
interest already paid is never refunded.
**Why:** Refund logic creating or destroying value is the phase's first named risk. Cancelling the
earliest instalments first would cure a delinquency the customer never cured and leave later
instalments owed on returned goods; cancelling a paid amount takes the customer's money twice; a fee
pro-rated by a running clamp leaves a residue at a full refund that no event explains.
**Enforce:** `DOMAIN` (the pure refund allocator, `P12-TSK-009`, over the due state derived under B5
then B4, pay-in-N accrued to date by T-acc first) + `DB-CONSTRAINT` (per-instalment
`bnpl_refund_adjustment` rows; a deferred trigger: Σ adjustments + excess = the refund's amount at
commit) + the one entry F5/F5a under the key `bnpl.refund:<refund>`, balanced per currency.
**Verify:** The allocator's properties — value preserved, latest first, never a paid amount cancelled,
the fee order-independent and telescoping to f, a full refund leaving exactly zero (`P12-TSK-009`);
scenario 9's `BnplRefundDatabaseTest#refundBeforeDuringAfter` and `#excessGoesToTheCustomer`, and
scenario 7 — `BnplRefundDatabaseTest#aRefundRacingACollectionConservesInBothOrders` (`P12-TSK-021`);
`#refundCancelsLatestFirst`, the earliest-first probe's catcher (`P12-TSK-009`, `P12-TSK-021`); the late
fee's release on a cancelled instalment, `BnplLateFeeDatabaseTest` (`P12-TSK-020`, `P12-TSK-021`);
the probes refunding earliest first and pro-rating the fee by a running clamp (`MUTATION_TESTING.md`
§2); the battery's value-preservation and fee-telescoping properties for every refund order
(`P12-TST-002`); the three-way proof's identity arm, Σ refunds = Σ principal cancelled + Σ excess, per
purchase (`P12-TSK-025`), at rest in every storm round (`P12-TST-001`). *(Since `P12-TSK-009`: the
allocator's half is `RefundAllocationEngineV1Test` - the plan's worked check, `#refundCancelsLatestFirst`, a
partly paid instalment, a refund after completion all excess - and `RefundAllocationEngineV1PropertiesTest` over
generated refund sequences in three currencies and every rounding policy, two JVMs; the fee is merchant's
`FeeCalculation.returnedFee` bound in as a parameter, its hermetic reference proven equal by `app`'s
`BnplFeeArithmeticAgreesWithMerchantTest`; eleven probes, `MUTATION_TESTING.md` §2.)* *(As built by `P12-TSK-021`: T-ref
composes the allocator over the position it derives under B5 then B4 from the rows - the schedule's instalments, the billed
items billed less settled (every payer's and every earlier refund's), the adjustments and `BNPL_PRINCIPAL` - and posts the
split in one entry F5 + F5a under `bnpl.refund:<refund>`; `bnpl V012` holds R = r_f + payable debit = cancelled + excess on
the row and Σ adjustments = the cancellations at commit; pay-in-N's version identity `principal(v n+1) = principal(v n) -
unbilled cancelled` at the schedule's commit. Hermetic: `BnplRefundsTest#aFullRefundClosesWithNothingStranded`,
`#refundBeforeDuringAfter`, `#refundCancelsLatestFirst`, `#aRefundRacingACollectionConservesInBothOrders`,
`#theFeeTelescopesToExactlyFOverSplitRefunds`; database, owed: `BnplRefundDatabaseTest`. The late fee's release (OQ-14) is
`-020`'s, carried.)*
**Phase:** 12

### INV-BNPL-07 — Refunds never exceed the purchase
**Statement:** For every purchase, Σ refunds ≤ G, judged under the purchase row's lock (B5) at every
refund, whichever door admits it — the merchant's key, the operator's door or a dispute's resolution.
A refund above `G − refunded` is refused `422 bnpl.RefundExceedsPurchase`, and one after a full refund
`409 bnpl.PurchaseFullyRefunded`, each with nothing written. It follows that one purchase's refunds
debit the merchant payable by at most its net in total — Σ (R − r_f) ≤ G − f, since `x − cum_fee(x)`
never decreases while f < G — the per-purchase bound on which `INV-MER-07`'s third source rests
(owner decision B7).
**Why:** Refunding more than the sale pays the customer money no sale funded, taken from the merchant
or, after a payout, from the platform; it is also the one bound the dispute path shares
(`INV-BNPL-08`) and the bound that keeps a negative payable finite.
**Enforce:** `DOMAIN` (the purchase `FOR UPDATE` before the obligation, B5 then B4, and the bound judged
under it) + `DB-CONSTRAINT` (a trigger bounding Σ refunds by the purchase's gross for every writer;
`bnpl_refund UNIQUE (merchant_id, refund_key)`; the purchase's terminal `FULLY_REFUNDED` admitting no
edge out).
**Verify:** Scenario 8 — `BnplRefundDatabaseTest#theSameRefundTwiceRefundsOnce` (merchant retry, two
instances, a lapsed claim) (`P12-TSK-021`); a refund above the remainder refused with nothing written,
and refunds racing on one purchase counted (`P12-TSK-021`); the probe skipping the purchase lock in a
refund, caught by scenario 10 (`MUTATION_TESTING.md` §2); Σ refunds ≤ gross for every purchase at
every storm round (`P12-TST-001`).
*(As built by `P12-TSK-021`: the purchase `FOR UPDATE` first (B5) - merchant-scoped at the merchant's door - the bound
judged over the committed refunds under it, `422 bnpl.RefundExceedsPurchase` / `409 bnpl.PurchaseFullyRefunded`; `bnpl
V012`'s refund trigger re-takes B5 and refuses Σ refunds above the gross for every writer, the purchase's edge `FINANCED ->
FULLY_REFUNDED` only at the gross and nothing out of it. Hermetic: `BnplRefundsTest#refundsNeverExceedTheGross`,
`#theSameRefundTwiceRefundsOnce`; database, owed: `BnplRefundDatabaseTest#twoRefundsOnTwoInstancesNeverExceedTheGross`,
`#theSameRefundTwiceRefundsOnce`.)*
**Phase:** 12

### INV-BNPL-08 — Refund and dispute never double-credit
**Statement:** A BNPL dispute resolved for the customer — `RESOLVED_REFUND` by the operator, by the
merchant's acceptance or by the merchant's silence past the response deadline — executes a
`BnplRefund` under the same purchase lock and the same `INV-BNPL-07` bound: it is a refund, never a
second credit path. Across merchant refunds and dispute outcomes at most G is ever returned to the
customer side of a purchase. A BNPL-financed amount has no card network and admits no chargeback; a
chargeback on a card-funded wallet top-up is payments' dispute on that top-up (`INV-DSP-01`) and
touches no agreement. At most one dispute per purchase is open.
**Why:** "Chargeback plus refund on the same order cannot double-credit the customer" is the phase's
third original exit criterion: two paths each judging their own bound return the price twice.
**Enforce:** `DOMAIN` (T-dsp takes B5, then B4, then the dispute row B6; a refund outcome runs T-ref's
body in the same transaction) + `DB-CONSTRAINT` (`bnpl_dispute` partial `UNIQUE (purchase_id)` over
open states; the refund bound's trigger shared by both paths). If card-funded instalments are ever
admitted (excluded by owner decision B6), `INV-DSP-01` must be extended to this bound first.
**Verify:** Scenario 10 — `BnplDisputeDatabaseTest#disputeRefundAndMerchantRefundShareOneBound`
(`P12-TSK-023`, written and owed a first run), hermetically `BnplDisputesTest#aRefundThenADisputeShareOneBound` and
`#aDisputeThenARefundShareOneBound`; the probe skipping the purchase lock in a refund turning scenario 10 red
(`MUTATION_TESTING.md` §2); disputes and refunds racing on shared purchases in the storm, Σ refunds ≤
gross at every round (`P12-TST-001`).
**Phase:** 12

### INV-BNPL-09 — A full refund closes the obligation with nothing stranded
**Statement:** A refund that leaves every receivable of an obligation at zero — refunds totalling G
among them — closes the obligation in that refund's transaction (`CLOSED`, reason `REFUNDED` or
`REFUNDED_PARTIALLY_REPAID`): its five receivable accounts zero, any `BNPL_CREDIT_BALANCE` refunded to
the customer's wallet, the merchant fee returned in full (Σ r_f = f), pay-in-N accrued and
billed-unpaid interest released (A11) and late fees on cancelled instalments released. Thereafter a
repayment, collection or fee on it is refused `409 bnpl.ObligationClosed`. A refund on an obligation
already `CLOSED` by repayment is admitted and posts only the merchant's debit and the customer's
credit. Where the wallet cannot be credited the excess is held in `BNPL_CREDIT_BALANCE` and refunded
when postable (OQ-13), the closure following that refund.
**Why:** "Full refund closes the agreement and leaves no residual obligation or stranded value" is the
phase's second original exit criterion: a residual cent of principal, interest or fee is a debt for
goods returned, and an unreturned fee or credit balance is value the platform kept without an event.
**Enforce:** `DOMAIN` (closure under B4 in T-ref when every receivable is zero and every elapsed date
accrued) + `DB-CONSTRAINT` (the obligation's every-writer edge trigger admitting nothing out of
`CLOSED`, `INV-LIFE-04`).
**Verify:** Scenario 9 — `BnplRefundDatabaseTest#aFullRefundClosesWithNothingStranded` — and scenario
16 — `BnplRefundDatabaseTest#aRepaymentAfterAFullRefundIsRefused` (`P12-TSK-021`); the subledger
proof's `CLOSED` arm all zero (`P12-TSK-025`); the battery's full refunds each leaving exactly zero
(`P12-TST-002`); every closed obligation zero at every storm round (`P12-TST-001`).
**Phase:** 12

*(Extended by `P12-TSK-021`: a refund that leaves nothing owed closes in its own transaction - `REFUNDED` when the
purchase is `FULLY_REFUNDED` (also when a later servicing step refunds a credit balance the refund held, OQ-13),
`REFUNDED_PARTIALLY_REPAID` otherwise - pay-in-N's accrued and billed-unpaid interest released in its entry (A11), an empty
schedule version when nothing was ever billed; a refund after closure is all excess to the wallet, refused `409
bnpl.RefundNotPostable` when the wallet cannot take it. Hermetic: `BnplRefundsTest#aFullRefundClosesWithNothingStranded`,
`#aFullPnRefundReleasesUnpaidInterest`, `#anUnpostableWalletHoldsTheExcessAsCredit`, `#aRepaymentAfterAFullRefundIsRefused`,
`#aRefundAfterClosurePaysTheCustomerOnly`.)*

*(The closure mechanism built by `P12-TSK-018` for the `REPAID` reason, which `-021` extends with its two: `BnplClosures` - called by every zeroing transaction under B4 - closes only when the five receivables are zero, every instalment of the latest schedule is billed and (pay-in-N) no elapsed date with principal outstanding is unaccrued, refunds the credit balance to the wallet under `bnpl.closure-refund:<obligation>` in the same transaction, and `bnpl V010`'s every-writer edge function admits `ACTIVE -> CLOSED` only beside a `bnpl_obligation_closure` row for the same reason and nothing out of `CLOSED`; a repayment on a closed obligation answers `409 bnpl.ObligationClosed`. Hermetic: `BnplRepaymentsTest#theLastRepaymentClosesAndRefunds`, `#aClosedObligationRefuses`.)*

*(Extended by `P12-TSK-022`: the payoff closes `PAID_OFF_EARLY` through the same `BnplClosures`, in its own transaction -
every unbilled instalment billed beside its execution, the held credit applied before the wallet is debited (A4-M1), the
amount recomputed under B4 and refused stale rather than adjusted, one `PAYOFF` repayment to every component, the credit
balance refunded - so after it every BNPL account of the obligation is zero and nothing is owed either way; `bnpl V014`
admits the reason only beside the obligation's payoff execution and ties the execution to it at commit. Every payoff fact
is dated `max(G, floor)`, the floor read before it writes (the Phase 11 gate's RG-M1 not repeated). Hermetic:
`BnplPayoffsTest#p4PayoffIsTheUnpaidInstalments`, `#payoffZeroesEveryBalance`, `#heldCreditIsAppliedBeforeTheWalletIsDebited`,
`#aCreditBeyondEverythingIsRefunded`, `#everyPayoffApplicationIsFloored`, `#payoffBesideARefundIsStaleOrClosedNeverWrong`;
database: `BnplPayoffDatabaseTest` (owed a first run).)*

### INV-BNPL-10 — BNPL principal counts in capital headroom and credit exposure from the purchase's commit
**Statement:** From the commit of the purchase that creates it, BNPL principal outstanding
(`BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE` of every obligation not `CLOSED`) is counted (a) in its
currency's lending capital headroom — recognised `LENDING_CAPITAL` less the committed principal of
loans `PENDING_DISBURSEMENT`, less loan and line principal outstanding, less BNPL principal
outstanding, at least zero, judged under the capital row's lock (L6) at every BNPL purchase for its
gross and every BNPL repayment reversal that re-instates principal (`INV-LND-13` extended) — and (b)
in the party's credit exposure by `PlatformCreditExposure` version 3, one statement over journal
lines, decisions made under versions 1 and 2 replaying identically. There is no gap: the decision's
reservation ends in the commit in which the obligation's principal begins to count, both under the
party's profile lock (L0), taken first. Merchant fee income is never capital.
**Why:** A purchase invisible to the next decision lets a party borrow twice on one judgement; a
purchase not counted against capital lets customers' safeguarded money fund merchants — BNPL creates
merchant-payable liabilities without cash entering, ADR-0096's safeguarding reasoning.
**Enforce:** `DOMAIN` (L0, then L6, in T-pur and in a principal-re-instating reversal; `BnplCapital`
over `LendingCapitalHeadroom.underLock`, which sums the BNPL purposes by ledger purpose, lending
learning no bnpl type; a shortfall refused `422 bnpl.CapitalUnavailable`) + `DB-PRIVILEGE` (bnpl's
`SECURITY DEFINER` view read by `app`'s exposure version 3).
**Verify:** A planted BNPL principal line lowering headroom and refusing the last acceptance, and
lending's acceptance, draw and reversal races re-run unchanged (`P12-TSK-005`); BNPL purchases, loan
acceptances and draws racing for the last capital, one admitted (`P12-TSK-013`, `P12-TSK-005`);
scenario 3 — `BnplPurchaseDatabaseTest#acceptanceAndADecisionSerialiseOnTheProfile` (`P12-TSK-013`);
exposure version 3, the extended `ExposureCensus` and the replay of version 1 and 2 decisions
(`P12-TSK-012`); `LendingCapitalProof` with the BNPL term, `finapp.lending.capital.proof{verdict}`
(`P12-TSK-005`); the probe skipping L6 letting two purchases over-deploy (`MUTATION_TESTING.md` §2);
the capital proof and the exposure census in every storm round (`P12-TST-001`).
*(As built by `P12-TSK-005` (2026-10-17), part (a): `LendingCapitalHeadroom.PRINCIPAL` is the four purposes, read in
one `PurposeBalances` statement, so `underLock`, `atRest`, `lending_funded` and `LendingCapitalProof` count BNPL
principal; bnpl's `BnplCapital` port (`app`'s `LendingBnplCapital`) judges it under L6 in bnpl's transaction, returning
`BnplHeadroom`. Hermetic: `LendingCapitalHeadroomTest` (the set, the one read, the lock and read order, the proof,
`lending_funded`, the `headroom.low` count), `LendingBnplCapitalTest`, `BnplHeadroomTest`, `BnplBooksHaveOnePosterTest`'s
reader permit. Database, written and owed a first run: `LendingCapitalDatabaseTest` orders 15 to 20 and
`BnplCapitalDatabaseTest` (the L6 race against a real loan acceptance, both orders; the last capital admitting one).
The `422 bnpl.CapitalUnavailable` refusal, (b) and the real purchase are `-012`/`-013`'s.)*
*(As built by the pair (2026-10-18), part (b) and the purchase: `app`'s `BnplCreditExposure` (credit's `PlatformCreditExposure` version 3) over `bnpl V007`'s view - one statement with lending's arm; T-pur takes L0 first and judges L6 for the gross after the consumption. Hermetic: `BnplCreditExposureTest`, `BnplAcceptancesTest#acceptanceAndADecisionSerialiseOnTheProfile`, `#acceptancesRacingForTheLastCapitalAdmitOne`; database, owed: `BnplCreditExposureDatabaseTest`, `BnplPurchaseDatabaseTest#acceptanceAndADecisionSerialiseOnTheProfile`, `#acceptancesRacingForTheLastCapitalAdmitOne`.)*
*(As built by `P12-TSK-024` (2026-10-19), the reversal arm: T-rev's approval takes L0 first (always) and, when the reversed repayment paid principal, L6 with headroom at least that principal before the wallet; a shortfall is `422 bnpl.CapitalUnavailable`, nothing posted, the proposal still `PROPOSED`. Hermetic: `BnplRepaymentReversalsTest#reversalTakesTheProfileFirst`, `#exposureRisesByThePrincipalReopened`, `#aReversalNeverOverDeploysCapital`, `#aHeldCreditRepaymentIsReversedWithoutCapital`; database, owed: `BnplRepaymentReversalDatabaseTest`'s, and `app`'s own-container `BnplServicingApiTest#reversalTakesTheProfileFirst` on the real credit profile.)*
**Phase:** 12

*`INV-BNPL-01`…`INV-BNPL-10` catalogued by the Phase 11 → 12 transition (2026-10-16), the `INV-LND`
precedent: Phase 12's gate properties given stable IDs before any bnpl code exists, so the register
can demand their demonstrations by identifier rather than by prose. Catalogued separately from
`INV-LND` (OQ-11, owner accepted default, 2026-10-16, review later). Decisions in ADR-0101…0106
(`Proposed`); the machines in `BNPL_LIFECYCLES.md`; the design in `PHASE_12_PLAN.md` (§6 names these
entries and the invariants it restates for Phase 12, which keep their own entries here unchanged but
for `INV-MER-07`'s amendment; §7.4 their contention rows; §13.2 the eighteen scenarios they cite).
Task and test names are the plan's; the building task may rename a test and says so. None built yet:
until Phase 12's first task lands, nothing these entries name is implemented, and every statement is
the decided design, corrected by the tasks that build it.*

---

# Identity, Credentials and Sessions — `INV-IDN`

Added by the Phase 0 → Phase 1 transition (2026-09-03). **Why this group did not exist and now
does:** these seven properties were written down as Phase 1 *exit criteria* in `PHASE_GATES.md`,
which is a materially weaker regime than every other property in the platform gets — no stable ID
to cite, no named enforcement mechanism ranked by strength, no named verification method, and no
row in `MUTATION_TESTING.md`. The asymmetry was backwards: Phase 1 is the phase whose *product* is
security. Catalogued now, before any credential-handling code is written against prose.

### INV-IDN-01 — A credential is never recoverable
**Statement:** No credential is stored, logged, transmitted or backed up in a form from which the
original secret can be recovered. Verification compares derivations, never values.
**Why:** A credential store that can be reversed turns one breach into an account takeover at
every other platform the customer reused that password on.
**Enforce:** `DOMAIN` (no reversible field) + `STATIC` (`secretsAreWrapped`) + `DB-CONSTRAINT` —
and the constraint is stronger than "classification forbids it": `credential_derivation_is_encoded`
requires the value to be in its algorithm's encoded form, so **a plaintext cannot physically be
stored in the column** by any writer, including one that never passes through the domain
(`P1-TSK-007`).
**Verify:** `CredentialNeverLeaksDatabaseTest` asserts the input appears in **no column** of the
stored row — the column list derived from `information_schema`, so a column added later is
inspected without anyone remembering. Demonstrated to fail when the constraint is dropped.
**Scope (added by `P1-TSK-017`):** this governs secrets the platform **verifies by comparing
derivations**. A *shared* secret — one whose mechanism requires the platform to hold it, such as a
TOTP seed — cannot satisfy it, and `INV-IDN-08` states what replaces it. That boundary is written
down because the alternative is a reader finding a recoverable secret in an identity table and
having to guess whether it is a defect.
**Phase:** 1

### INV-IDN-02 — Credential derivation parameters are recorded per credential
**Statement:** Every stored credential records the algorithm and parameters used to derive it.
**Why:** Parameters must increase as hardware improves, and a store with one global setting cannot
be migrated without either invalidating every credential or knowing what each one used. This is
`INV-HIST-04`'s rule applied to the thing that authenticates a person.
**Enforce:** `DB-CONSTRAINT` (`NOT NULL` algorithm and the three cost factors, each `> 0`).
**Verify:** Schema tests asserting each is refused when null (`P1-TSK-007`), and that the values in
the queryable columns are the ones inside the encoded derivation — the two are stored separately on
purpose (ADR-0032 Option D) and duplication nothing reconciles is drift waiting to happen. The
upgrade-on-use half is `P1-TSK-008`; `isWeakerThan` exists and is tested, and nothing calls it yet.
**Phase:** 1

### INV-IDN-03 — Session revocation is immediate
**Statement:** A revoked session is refused on the next request, on every instance. Revocation is
never eventually consistent.
**Why:** An eventually-revoked session is an unrevoked session. "Log out everywhere" after a
suspected compromise is worthless if it takes effect when a cache expires.
**Enforce:** `DOMAIN` — authoritative session state in the database, checked per request; no
process-local session cache (ADR-0030, ADR-0024). Expiry, the other way a session ends, is the same
fact on every instance for the same reason. Both bounds are stamped from the database's clock and
judged against it, so no instance's clock can lengthen or shorten a session (`X-TSK-007`, ADR-0030
amendment). `SessionTimeIsTheDatabasesTest` fails the build if a liveness method takes an instant.
**Verify:** Multi-instance test: revoke on one instance, assert refusal on another (`P0-TST-009`
convention). Skewed-instance tests: instances an hour fast and an hour slow against the server
issue, rotate, judge and measure one session alike (`SessionClockSkewDatabaseTest`,
`SessionClockSkewEndpointDatabaseTest`).
**Phase:** 1

### INV-IDN-04 — Authentication is not authorization, and neither is consent
**Statement:** An authenticated session grants no permission by itself. Every protected operation
evaluates authorization explicitly, and neither authentication nor authorization is ever treated
as a lawful basis for processing.
**Why:** `CLAUDE.md` §Domain Distinctions. Treating authentication as authorization means anyone
who logs in can do anything; treating either as consent means data is processed with no lawful
basis (`INV-CRD-03`).
**Enforce:** `DOMAIN` — deny by default; no operation is permitted by the absence of a rule.
**Verify:** A passing **negative** authorization test for every protected endpoint.
**Phase:** 1

### INV-IDN-05 — MFA cannot be bypassed by an alternative path
**Statement:** Where MFA is required, no alternative route — recovery, a second factor enrolment,
a refresh, an older session, a different endpoint — yields an equivalent session without it.
**Why:** MFA is defeated by its weakest alternative path, not by its strongest factor. Every real
bypass is a path nobody enumerated.
**Enforce:** `DOMAIN` — the session records the assurance level it was established at, and an
operation requiring MFA checks that level rather than a boolean.
**Verify:** Enumerated bypass-attempt tests, one per alternative path, each asserting refusal.
**Phase:** 1

### INV-IDN-06 — Recovery cannot elevate an attacker
**Statement:** No account-recovery flow grants access, changes a credential or removes a factor
without proving control of a previously registered and verified channel. Recovery never lowers the
assurance required to reach an account.
**Why:** Recovery is the classic account-takeover vector precisely because it exists to bypass the
credential. `DELIVERY_PLAN.md` §17 names it as a top Phase 1 risk.
**Enforce:** `DOMAIN` + `PROCESS` (rate limits, cooling-off, notification to the registered
channel).
**Verify:** Abuse-case tests: unverified channel, recently changed channel, concurrent recovery and
login, replayed recovery token.
**Phase:** 1

### INV-IDN-07 — Authentication outcomes do not disclose whether an account exists
**Statement:** Responses and timing for a failed authentication, a registration collision and a
recovery request are indistinguishable between an existing and a non-existent account.
**Why:** Account enumeration turns a credential-stuffing list into a targeted one, and it is
usually leaked by an error message or a status code rather than by an intentional API.
**Enforce:** `DOMAIN` — one response shape for all outcomes of the class.
**Verify:** Tests comparing responses across existing and absent accounts; enumeration review of
every new endpoint.
**Phase:** 1

### INV-IDN-08 — A shared authentication secret is encrypted at rest under a key held outside the database
**Statement:** Where a factor's mechanism requires the platform to hold the secret itself — so that
`INV-IDN-01`'s irreversibility is unavailable — the secret is encrypted at rest with an
authenticated cipher, under a key that is not stored in the database. A database leak alone never
yields the secret, and a database *write* never substitutes one.
**Why:** `INV-IDN-01` governs secrets verified by comparing derivations, and its rationale is
credential reuse across platforms. A TOTP shared secret is neither: the server computes the expected
code *from* it, and it is minted here and used nowhere else. So irreversibility is not merely
inconvenient, it is **impossible**, and stating that plainly is better than a reader finding a
recoverable secret in an identity table and having to guess whether it is a defect.
**The harm this replaces it against is different and in one way worse.** A leaked TOTP secret lets an
attacker generate valid codes indefinitely while the customer's authenticator keeps working — so
nothing looks wrong to anybody. A stolen password is at least changeable; a silently cloned second
factor defeats the control that exists to survive a stolen password.
**Enforce:** `DOMAIN` (the plaintext is never a field on an aggregate or a row) + `DB-CONSTRAINT`
(nonce and ciphertext sized for AES-GCM, so a plaintext cannot be passed off as a ciphertext) +
`PROCESS` (the key is externalised configuration, and the published local default is refused
anywhere the database is not on loopback).
**Verify:** `MfaEnrolmentDatabaseTest` asserts the secret appears in **no column** of the stored row
— the column list derived from `information_schema`, so a column added later is inspected without
anyone remembering — that a tampered ciphertext is refused rather than decrypting to something else,
and that a different key cannot read it.
**Phase:** 1 (`P1-TSK-017`)

---

# Verification and Case Management — `INV-KYC`

Added by the Phase 1 → 2 transition (2026-09-09), on the Phase 0 → 1 precedent: Phase 2's exit
criteria existed only as prose bullets in `PHASE_GATES.md` §5 — no stable ID, no ranked
enforcement mechanism, no named verification method — which is the weaker regime the `INV-IDN`
group was created to escape, and Phase 2 is the phase whose product is *defensible decisions*.
Catalogued before any case-handling code is written against prose.

### INV-KYC-01 — A provider verdict is evidence, never the decision
**Statement:** No provider response, screening result or verification verdict is ever itself the
platform's decision. Every decision is a separately recorded act of the platform, referencing the
evidence it rested on; the raw provider payload is retained verbatim (`INV-HIST-02`).
**Why:** The platform, not the vendor, answers to the regulator. A decision that IS a provider's
JSON cannot be defended, reproduced or reviewed — and providers time out, disagree and revise.
**Enforce:** `DOMAIN` (the decision record references checks; no code path maps a provider outcome
onto a case status directly) + `DB-PRIVILEGE` (evidence tables are append-only).
**Verify:** A test drives a provider verdict and asserts no decision exists until the platform's
own decisioning ran; evidence bytes asserted identical to the bytes received.
**Phase:** 2

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0081 (`Proposed`), in force when
`P9-TSK-016` lands: extended to counterparty screening — every outcome of
`kyc.counterparty_screening`, `CLEAR` included, is a recorded platform decision carrying its
`decision_basis` (`AUTOMATIC` | `REVIEWER`), the kyc policy version and decided-at, with the
provider's verdict retained as evidence only.)*

*(In force since `P9-TSK-016` (2026-10-06). Enforced at `DB-CONSTRAINT` by `kyc V009`:
`counterparty_screening_every_outcome_is_decided` (a basis, policy version and time exactly when no
longer `REQUESTED`), `..._basis_matches_the_outcome` (`REVIEWER` exactly for `RELEASED`/`BLOCKED`) and
`..._reviewer_names_a_person`; the provider's answer is the append-only
`counterparty_screening_attempt` row, its bytes sealed. Verified by `CounterpartyScreeningDatabaseTest`
- every outcome carries its basis, policy and time; the raw-SQL refusals - and
`CounterpartyScreeningRuleTest`.)*

### INV-KYC-02 — A decision is immutable, attributable and reproducible
**Statement:** A recorded KYC/KYB decision is never updated or deleted; it names its actor
(a reviewer, or the platform under a stated automatic policy), its reason, the policy version
applied (`INV-HIST-04`) and the evidence it rested on. Changed circumstances produce a new case
event, never an edit.
**Why:** An onboarding decision is the record later financial phases gate on and the record a
dispute replays. `INV-CRD-02`'s regime, three phases early, for the same reason.
**Enforce:** `DB-PRIVILEGE` (no `UPDATE`/`DELETE` for the application role) + `DB-CONSTRAINT`
(`NOT NULL` actor, reason, policy version).
**Verify:** Privilege tests; a replay test re-deriving the decision from retained evidence and the
pinned policy.
**Phase:** 2

### INV-KYC-03 — Duplicate provider callbacks produce at most one decision
**Statement:** The same provider callback, webhook or result delivered any number of times — or
concurrently to N instances — advances a case at most once and produces at most one decision.
**Why:** `INV-IDEM-04` is the general rule; it is restated here with a stable ID because a
duplicated *decision* is the phase's most damaging duplicate — two decisions on one case make the
answer to "may this party transact?" ambiguous forever.
**Enforce:** `DB-CONSTRAINT` — the platform inbox dedupe key (`P0-TSK-021`), plus conditional
state transitions whose row count is the outcome.
**Verify:** Duplicate- and concurrent-delivery tests against a real database.
**Phase:** 2

### INV-KYC-04 — A screening hit is resolved by a person, never by silence
**Statement:** A screening hit (sanctions, PEP, adverse media) never auto-clears and never
auto-rejects. It becomes an explicit review item, and its resolution requires elevated
authorization, a recorded reason, and an audit record naming the reviewer.
**Why:** A name match is a probability. Silently cleared is a sanctions breach; silently rejected
is a person refused service by string similarity. Both are invisible without the review record.
**Enforce:** `DOMAIN` (no code path from hit to terminal case state without a review decision) +
`DOMAIN` (`@RequiresPermission` on review endpoints).
**Verify:** Negative authorization tests; a test asserting a hit case cannot reach a terminal
state without a recorded review decision.
**Phase:** 2

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0081 (`Proposed`), in force when
`P9-TSK-016` lands: extended to counterparty screening, where an **unverified payee is
handled as a hit** — a `CLEAR` on a name whose payee check answered `NO_MATCH` or
`UNAVAILABLE` goes to a person (reason `PAYEE_UNVERIFIED`), an `AUTOMATIC` `CLEAR` exists
only with a payee `MATCH` (a kyc `CHECK` on the stored payee verdict), and release or block
takes `COUNTERPARTY_SCREENING_REVIEW` with a recorded reason (`INV-XB-02`).)*

*(In force since `P9-TSK-016` (2026-10-06). `DOMAIN`: `CounterpartyScreenings.route` - only a
provider `CLEAR` beside a payee `MATCH` clears; a hit, an indeterminate answer or an unverified payee
goes `IN_REVIEW` with its reason, and the machine has no automatic edge to `RELEASED` or `BLOCKED`.
`DB-CONSTRAINT`: `counterparty_screening_automatic_clear_needs_a_payee_match`. A person's decision
takes `COUNTERPARTY_SCREENING_REVIEW` (held by `KYC_REVIEWER`), a reason code that justifies it and a
narrative, audited as `kyc.CounterpartyScreeningDecided`. Verified by `CounterpartyScreeningRuleTest`
and `CounterpartyScreeningDatabaseTest` - including the review door's negative authorization.)*

### INV-KYC-05 — The verification outcome has one authority
**Statement:** The KYC context owns the verification decision. Any copy elsewhere — including
`party.customer.status` — is a projection: updated in reaction to the decision, never computed or
edited independently, and never authoritative in a dispute.
**Why:** Two writable authorities for "is this party verified?" is the shared-mutable-ownership
defect `CLAUDE.md` forbids, on the field every financial phase will gate on.
**Enforce:** `DOMAIN` + `STATIC` (module boundaries: nothing outside `kyc` writes a decision;
nothing outside the orchestration transitions customer status from a verification outcome).
**Verify:** A reconciliation test asserting projection and decision agree; boundary tests.
**Phase:** 2

### INV-KYC-06 — Documents and evidence are least-privilege, encrypted, and every access audited
**Statement:** Document content and screening evidence are classified at their ceiling
(`RESTRICTED-PII`), encrypted at rest under a key held outside the database, readable only
through an access-controlled path, and every read of document content produces an audit record
naming the actor.
**Why:** Identity documents are the most sensitive bytes the platform holds before card data, and
the reader is an insider threat surface: the trail of who looked is the control.
**Enforce:** `DOMAIN` (one read path, audited) + `DB-PRIVILEGE` (append-only content tables) +
`PROCESS` (key externalised, published default confined to loopback — the `INV-IDN-08` mechanism).
**Verify:** Column-sweep tests that plaintext content appears nowhere; tamper and wrong-key
refusal; an access-without-audit mutation caught.
**Phase:** 2

---

# Consent — `INV-CNS`

### INV-CNS-01 — No processing without a recorded, current, purpose-scoped basis
**Statement:** A capability declared consent-gated proceeds only when a current grant for that
specific purpose exists. Absence of a record is refusal, indistinguishable to the caller from an
explicit withdrawal. Authentication and authorization never substitute (`INV-IDN-04`).
**Why:** Consent is a legal precondition; a default-permit consent check is not a consent check.
**Enforce:** `DOMAIN` — the gate queries the consent history per decision; no cached or assumed
basis.
**Verify:** Consent-absent and consent-withdrawn refusal tests per gated capability; the
withdrawal test proves the dependent capability blocks (`PHASE_GATES.md` §5 Phase 2).
**Phase:** 2

### INV-CNS-02 — Consent history is append-only
**Statement:** Grants and withdrawals are immutable facts. Withdrawal is a new record; no consent
record is ever updated or deleted by any application role.
**Why:** "Was there a basis on the day it happened?" is answerable only from history; an updated
row has destroyed the evidence the question needs.
**Enforce:** `DB-PRIVILEGE` — `INSERT`/`SELECT` only, the audit-table mechanism.
**Verify:** Privilege tests on every column; the derived current basis flips on a new record.
**Phase:** 2

### INV-CNS-03 — Withdrawal is immediate on every instance
**Statement:** From the transaction that records a withdrawal, every instance refuses the gated
capability on its next decision. Consent state is never held in process memory.
**Why:** An eventually-withdrawn consent is an unwithdrawn consent — `INV-IDN-03`'s reasoning
applied to lawful basis.
**Enforce:** `DOMAIN` — authoritative reads per decision; no process-local consent cache
(ADR-0024's rules apply).
**Verify:** Multi-instance test: withdraw on one connection, refused on another (`P0-TST-009`).
**Phase:** 2

### INV-CNS-04 — A grant is bound to the version of the text it was given against
**Statement:** Every consent record carries the version of the consent text presented. Whether a
new version requires re-consent is a recorded property of the version, never a guess.
**Why:** `INV-HIST-04`'s rule applied to the artefact a person agreed to: a grant against text v3
proves nothing about v4.
**Enforce:** `DB-CONSTRAINT` (`NOT NULL` version reference to a versioned, immutable text record).
**Verify:** Schema tests; a gate test under a version requiring re-consent.
**Phase:** 2

---

# Payments and Providers — `INV-PAY`

Added by the Phase 4 → 5 transition (2026-09-20), on the Phase 0 → 1 and 1 → 2 precedent:
Phase 5's defining properties existed only as prose gate bullets in `PHASE_GATES.md` §5 — no
stable ID, no ranked enforcement mechanism, no named verification method, no
`MUTATION_TESTING.md` row — and Phase 5 is the phase whose product is *surviving an
unreliable third party*. The financial core of that survival was catalogued at initiation
(`INV-LIFE-03`, `INV-IDEM-04`, `INV-HIST-02`, `INV-SET-01`, `INV-REV-02`); these five are the
properties the gate demanded and nothing catalogued. Written before any payment code exists
against prose.

### INV-PAY-01 — A provider's outcome is adopted only from an authenticated source
**Statement:** No payment state transition is driven by an inbound provider message whose
authenticity has not been verified — signature checked over the raw bytes, before parsing,
in constant time, within the provider's declared freshness window. An unauthenticated or
stale message produces no transition and no financial effect; whether its evidence is
retained is a per-provider decision, recorded.
**Why:** A webhook that clears a payment is the input that credits a wallet. A "signature"
anyone can compute, or a replayable one, is an open door to payment-outcome forgery — the
`P2-TSK-011` reasoning, now with money on the other side of the door.
**Enforce:** `DOMAIN` — verification before parsing, before any read, at the one ingestion
path; per-provider keys under the externalised-secret regime.
**Verify:** Negative tests per cause (missing, wrong, stale signature), each asserting
nothing was written; the signature scheme proven against the provider's own test vectors
where published.
**Phase:** 5

### INV-PAY-02 — Raw card data never enters the platform
**Statement:** No PAN, CVV, track data or any value from which an instrument could be
reconstructed is stored, logged, transported or exposed anywhere in the platform. The
platform holds tokenised references only, and the tokenisation boundary is the
`paymentmethods` module: a tokenisation provider being unavailable fails the operation and
never falls back to holding raw detail.
**Why:** PCI scope is the one boundary far cheaper to keep closed than to reopen
(`MODULE_ARCHITECTURE.md` M7); a stored PAN converts a database leak into card fraud at
every merchant the customer ever used.
**Enforce:** `STATIC` (the `secretsAreWrapped` vocabulary already refuses card-named
fields) + `DOMAIN` (no type exists to carry a PAN) + `PROCESS` (schema review: no column
may be classified to admit one).
**Verify:** `information_schema`-derived column sweeps asserting instrument input appears in
no column of any row; the module isolation tests holding the `paymentmethods` boundary. *(The
sweep covered the payment-method row alone until the Phase 7 review, `P7-DOC-001`:
`PaymentEndpointDatabaseTest#instrumentInputRestsNowhereButItsReference` now reads every
text-like column of every base table in every schema after a routed card payment - the grant in
none, the token in its payment method's reference alone.)* *(Extended to settlement at the
Phase 7 → 8 transition, ADR-0066 (`Proposed`), in force when `P8-TSK-002`, `-008` and `-016`
land: the sweep runs after a settlement flow as well, over the `settlement` and `reconciliation`
schemas with the rest; and a Luhn-valid 13–19-digit run in a declared free-text field of a
settlement file — or in a field that fails its declared class, screened as free text — is refused
at the door, storing nothing but metadata, while a Luhn-valid 15-digit network transaction id in
a reference field is not refused.)*
**Phase:** 5

*(Corrected 2026-10-02 by the Phase 8 → 9 transition, the audit's `SEC-02`, `SEC-03` and `SEC-04`:
three Phase 8 paths stored a card number this statement forbids. The PSP format's screen admitted
a dash-grouped or letter-prefixed card number in its reference columns and a bare one in its
acquirer column — its screen classes now exclude instrument shapes as the later formats' do
(ADR-0066 §3), and a stored batch reference that is not a reference shape is withheld from the
read. The case file's screen scanned contiguous digit runs alone, so `4111 1111 1111 1111` passed
notes, narratives and reasons. And six person-written reason doors — the settlement decline,
readmission, verification and content read, the reconciliation reprocess and requeue, and the
opening position — screened nothing. Every person-written reason, note and narrative now passes
the platform's one screen, `com.finapp.sharedkernel.security.InstrumentShapes`: a Luhn-valid
12–19-digit run however a person groups it with single spaces or dashes (ISO/IEC 7812's band;
the settlement door keeps 13–19 for whole files), the platform's own UUIDs masked — at the domain
before any claim, and by its PL/pgSQL twin on every column that stores such prose (settlement
`V012`, reconciliation `V019`), so a raw writer is refused too. `platform.audit_record.reason` has
no CHECK twin; every Phase 8 door screens before writing it, and the reason doors of earlier
modules stay with the cross-cutting owner.)*

### INV-PAY-03 — Provider vocabulary is confined behind the adapter
**Statement:** No provider-specific state, error code, field name or enum value appears in
the domain model, a persisted domain column, an event payload or a public API contract. A
provider answer is normalised through a **total** mapping whose default branch is
indeterminate — never success — and the raw answer is retained as evidence (`INV-HIST-02`).
**Why:** `CLAUDE.md` §Integration and ADR-0008: a domain that speaks one provider's language
belongs to that vendor, and an unmapped state silently treated as success is a wrong
financial fact.
**Enforce:** `STATIC` (module boundaries: no provider adapter type reachable from the
domain) + `DOMAIN` (the mapping's default branch).
**Verify:** State-mapping table tests covering every provider state including an unknown
one; contract review of the published API.
**Phase:** 5

### INV-PAY-04 — A provider-bound money operation is idempotent at the provider
**Statement:** Every request that asks a provider to move money (authorize, capture, refund)
carries a platform-minted idempotency reference, stored durably **before** the request is
sent, so a platform retry or a resolution-by-query can never cause the provider to perform
the operation twice.
**Why:** `INV-IDEM-01` protects the platform's own boundary; this is the same rule pointed
outward. Without it, the recovery path for an unknown outcome — retry or query — is itself
a double-charge mechanism.
**Enforce:** `DOMAIN` (the reference is minted and persisted in the dispatch transaction;
the port's contract requires it) + `DB-CONSTRAINT` (`NOT NULL`, unique per operation).
**Verify:** Contract tests asserting a re-dispatched operation presents the same reference;
schema tests on the uniqueness.
**Phase:** 5

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0077 and ADR-0079 (`Proposed`), in
force when `P9-TSK-012`, `-019` and `-024` land - for the FX cover since `P9-TSK-012`: the rule extends to the FX cover — the
client reference `T`, one per attempt, re-sent only under the same reference until the
provider knows of it, a new reference only after a definitive rejection (`INV-FX-08`) — to
the outbound credit's end-to-end reference `E`, minted and stored before any send, and to
the recall, itself idempotent at the provider and never followed by a re-send.)*

### INV-PAY-05 — Capture is bounded by authorization; refund is bounded by capture
**Statement:** The captured amount never exceeds the authorized amount, and the sum of
non-failed refunds never exceeds the captured amount — including under concurrent partial
refunds, for every writer.
**Why:** Over-capture takes money the customer never approved; over-refund creates money
(`INV-REV-02`'s reasoning, applied at the payment boundary where the amounts live in the
payment domain rather than the journal).
**Enforce:** `DOMAIN` (the aggregate refuses) + `DB-CONSTRAINT` where representable (the
`V009` in-trigger bound pattern for the concurrent-sum half).
**Verify:** Concurrent partial-refund tests counted in the tables; raw-SQL refusal tests.
**Phase:** 5

---

# Merchants and checkout — `INV-MER`

*Catalogued by the Phase 5 → 6 transition (2026-09-21) — the `INV-PAY` precedent: Phase 6's
gate properties given stable IDs before any merchant code exists, so the register can demand
their demonstrations by identifier rather than by prose. Decisions in ADR-0050…0053.*

### INV-MER-01 — A merchant reads and writes only its own data
**Statement:** Every merchant-scoped read and write derives its tenant from the
authenticated merchant credential and carries it **in the SQL statement** (`merchant_id =
?`), never as a post-filter. A cross-tenant row is unreadable and unwritable; addressing
another merchant's resource is indistinguishable from addressing one that does not exist.
**Why:** The first actor population the platform does not own. A tenant leak is another
company's revenue, customers and dispute posture disclosed — commercially fatal and, unlike
a bug, unforgivable.
**Enforce:** `DOMAIN` — the statement-scoping discipline (ADR-0031's shape at the tenant
boundary, ADR-0052); no merchant-facing query path without the predicate.
**Verify:** Negative tests per endpoint: authenticate as merchant A, address merchant B's
resource, assert the one refusal and zero rows touched.
**Phase:** 6

### INV-MER-02 — The merchant payable is derived from postings, never stored
**Statement:** What the platform owes a merchant is the merchant's payable ledger account
position — captured minus fees, minus refunded plus fees returned, minus payouts, minus
chargebacks plus chargebacks reversed, plus payouts returned, plus or minus reconciliation
attributions, plus BNPL financed, minus BNPL fees, minus BNPL refunded, plus BNPL fees returned —
and exists nowhere else. *(The BNPL terms were added by `P12-TSK-016`, the plan's §6 reading
built: bnpl posts to the payable as a second poster - F1, the financing, keyed
`bnpl.financing:<purchase>`, and F5, the merchant refund, keyed `bnpl.refund:<refund>` (posted from
`P12-TSK-021`) - and `MerchantPayable` names them `bnplFinanced`, `bnplFees`, `bnplRefunded` and
`bnplFeesReturned`, classified by the entry's posting-key namespace, which the ledger reports
beside the counterparty, after the attribution rule and BEFORE any counterparty rule: F5's excess
line credits the customer's wallet, the book rail's sale counterparty, so by counterparty alone a
BNPL refund would read as a wallet-paid capture's refund. The BNPL fee is the entry's own fee line,
never recomputed.)* *(The refund terms were added by the Phase 6 review,
`P6-DOC-001`: ADR-0054 made them part of the position, and this statement predated it.)* *(The
chargeback terms were added at the Phase 7 → 8 transition: `MerchantPayable` has named
`chargedBack` and `chargebacksReversed` since `P7-TSK-013`, `INV-MER-07`'s amendment made a
chargeback part of the position, and this statement predated it.)* *(The return and attribution
terms were amended at the Phase 7 → 8 transition, ADR-0073 and ADR-0070 (`Proposed`): a payout
return is a new merchant fact that credits the payable back from `PAYOUT_CLEARING` in its own
posting while the payout stays `COMPLETED` (`P8-TSK-019`, which names it `payoutsReturned`), and
a reconciliation attribution is every payable line in a `RECONCILIATION`-origin `ADJUSTMENT`
entry — an approved resolution such as a `TRANSFER_TO_ACCOUNT`, whatever the line faces, suspense
or a clearing position (`P8-TSK-015`, with its first poster, which names it
`reconciliationAttributed`); until each is built it falls into
`MerchantPayable`'s `other`. If `P8-TSK-019` is deferred (owner decision O6), a returned payout
reaches the payable only as an attribution. The attribution is ADR-0073 §6's origin rule, widened
from "an approved `TRANSFER_TO_ACCOUNT` credits it from `SUSPENSE_UNMATCHED`", and its ownership
settled on `P8-TSK-015`, by the transition's consistency review, A12, A13 and C8.)* *(Built by
`P8-TSK-015`: `MerchantPayable.reconciliationAttributed` is the signed term — every payable line
of an `ADJUSTMENT` entry whose ledger proposal is of origin `RECONCILIATION`, classified before
any counterparty rule, so a transfer of an OUTBOUND clearing remainder never reads as a capture —
and the customer statement labels a wallet line of such an entry `RECONCILIATION_ATTRIBUTION` by
the same rule; `payoutsReturned` stays `P8-TSK-019`'s.)* *(Built by `P8-TSK-019`:
`MerchantPayable.payoutsReturned` is every payable CREDIT facing `PAYOUT_CLEARING` — the return's
posting `merchant-payout-return:<payoutId>` — classified after the attribution rule, so a
reconciliation transfer never reads as a return, and served on the payable endpoint's line.)* No table
stores a merchant balance; every payout decision derives the available payable inside the account
lock.
**Why:** A stored payable is a second balance authority (`INV-BAL-01`'s reasoning at the
merchant boundary); two authorities drift, and drift in a liability to a counterparty is a
dispute the books cannot win.
**Enforce:** `DOMAIN` + schema review — no payable column exists to mutate.
**Verify:** The payable reconciles against independent SQL over the payment, fee and payout
records; schema sweep asserts no stored-balance column in `merchant`; the resolution suite
counts an approved transfer to a payable — from suspense and from an OUTBOUND clearing
remainder — as `reconciliationAttributed`, never `captured` (`P8-TSK-015`); a BNPL refund whose excess credits a wallet is `bnplRefunded`, never `refunded`, and a line facing `BNPL_PRINCIPAL` under no BNPL key is `other`, never financed (`BnplMerchantDrillDownTest`, hermetic; the independent-SQL reconciliation re-run with BNPL lines is `BnplMerchantPayoutDatabaseTest#thePayableReconcilesWithIndependentSql`, first run owed - `P12-TSK-016`).
**Phase:** 6

### INV-MER-03 — Fees are deterministic and version-pinned
**Statement:** Every fee assessment records the fee schedule version that produced it;
recomputing under that version reproduces the amount to the minor unit; schedule versions are
immutable from creation — change creates a new version effective forward, repricing
nothing already offered.
**Why:** `INV-HIST-04` with money attached: an unpinned fee makes revenue unexplainable and
merchant statements unreproducible, and a repriced history is a restatement.
**Enforce:** `DOMAIN` (immutable versions) + `DB-CONSTRAINT` (the payment's fee pin,
`merchant.payment_fee_pin`, carries the version `NOT NULL` and is frozen by trigger; schedule
versions frozen by trigger; the session carries the version it was priced under). There is no
assessment row: an assessment is the pinned version applied to the captured gross, posted in
the capture's entry and announced by `merchant.FeeAssessed` with the version on the wire.
*(Corrected at the Phase 6 review, `P6-DOC-001`, which found this naming rows that do not
exist.)* *(Since `P12-TSK-006` a BNPL purchase's version is pinned the same way by
`merchant.bnpl_fee_pin` - keyed by the purchase, `INSERT`-only, frozen by V004's trigger, and held by
a trigger to a returning version of the merchant's BNPL schedule in force by its instant; the BNPL
fee and its returns are that version applied to the pinned gross, never stored.)*
**Verify:** Recomputation tests per rounding mode; a schedule-change-mid-flight test
proving the pinned version priced the capture.
**Phase:** 6

### INV-MER-04 — A fee split conserves the captured amount exactly
**Statement:** For every merchant-bound capture, fee + net credited to the payable equals
the captured amount to the minor unit, per currency, for every assessment in any batch. The
fee is computed once; the net is derived by subtraction, never rounded independently.
**Why:** Two independent roundings can create or destroy a cent per transaction —
`INV-BAL-03` violated at volume, invisibly, one minor unit at a time.
**Enforce:** `DOMAIN` — the split is subtraction by construction (ADR-0050 §4); the
one-entry posting makes the journal reject any drift (`INV-LED-01`).
**Verify:** Property tests across amounts, rates and 0/2/3-minor-unit currencies; the
high-volume batch asserting zero cumulative residual.
**Phase:** 6

### INV-MER-05 — A payout is bounded by the payable
**Statement:** The sum of in-flight and completed payouts never exceeds the merchant's
ledger-derived payable — judged inside the payable account's lock at dispatch, with
in-flight payouts held so the bound is cumulative. A payout against insufficient payable is
a committed domain refusal.
**Why:** An over-payout is the platform giving away money it is not holding for that
merchant — value destroyed against `INV-BAL-03`, discovered only at settlement.
**Enforce:** `DOMAIN` (lock-then-look on the payable account) + the hold mechanism
(`INV-BAL-04` doing payout duty, ADR-0051 §2).
**Verify:** Concurrent payout races counted in the tables; the funded-then-drained probe;
sequential overrun refused with the honest domain error.
**Phase:** 6

### INV-MER-06 — Landed money is never orphaned by checkout expiry
**Statement:** A payment outcome that arrives after its checkout session expired lands in a
modelled, countable state that credits the merchant and completes the order late — never
dropped, never silently absorbed, never auto-reversed by a clock.
**Why:** The provider answers on its own schedule (ADR-0046); a session's expiry is the
platform's clock, not the money's. Money whose commercial fact is decided by a race between
a webhook and a sweeper is money the books cannot explain.
**Enforce:** `DOMAIN` — the `EXPIRED → COMPLETED_LATE` edge (ADR-0053 §5), conditional like
every transition. *(Since `P12-TSK-007` the late edge stays a capture's: a session completed by a BNPL
financing is `COMPLETED` only — checkout `V004`'s `CHECK` — because a financing exists only in the
commit that completes the session, so no financed money can land late.)*
**Verify:** The expiry-vs-capture race driven both ways; the late completion counted in a
meter and visible to an operator view.
**Phase:** 6

### INV-MER-07 — A refund is funded by its net; the only credit it extends is the fee kept
**Statement:** A refund of a merchant-bound payment reserves, inside the payable account's lock
at dispatch, what it takes from the payable net of the least fee share any completion order
can attribute to it, so the fee share it keeps owing is the only part of a refund the payable
may leave unfunded. A merchant's payable therefore goes below zero only by fee the platform has
charged and not collected (a retained refund share; a fee that exceeded its sale was the other,
closed at the price by ADR-0058, so only a pin written before that rule can still reach it) —
never by money the platform paid out. A refund beyond that is a committed domain refusal.
**Why:** A refund judged on its gross refuses what the merchant can fund (a `RETURNED` full
refund lands the payable at exactly zero). An unbounded one turns every refund after a payout
into the platform funding a merchant's customers with its own money. Bounded by the retained
fee, the platform's worst case on a payment is forgoing its fee (ADR-0054).
**Enforce:** `DOMAIN`: the reservation is asked of the refund's own composition and held
through `INV-BAL-04`'s hold, judged under the payable's lock (ADR-0054).
**Verify:** A full refund end to end under both policies (zero under `RETURNED`, exactly minus
the fee under `RETAINED`); a refund beyond the allowance refused with nothing written; the
least-share property swept over every completion order; refunds racing on one payable counted.
**Phase:** 6

*Amended by ADR-0061 §5 (`Proposed`, the Phase 6 → 7 transition), **in force since the first
chargeback posted (`P7-TSK-013`)**: a chargeback is the second, bounded source of merchant debt — it
leaves the payable below zero by no more than the sale credited it, and the debt is recovered from
later captures before any payout (`INV-MER-05`). `INV-DSP-01` holds the bound. The merchant's
processing fee is not returned by a chargeback, so a fully charged-back sale leaves the payable
down by exactly that fee — the first source's arithmetic, reached by the second. A payable below
zero is counted by `finapp.ledger.negative.positions` and named `chargedBack` in the merchant's
drill-down.*

*Amended by ADR-0105 (`Proposed`, the Phase 11 → 12 transition, 2026-10-16, owner decision B7),
**in force since `P12-TSK-021` (2026-10-19; read "proposed - in force when `P12-TSK-021` lands" until its gate)**: a
BNPL refund after a payout is the third, bounded
source of merchant debt. It may leave the payable below zero by at most that purchase's own net — what
its financing credited the payable (G − f) less what its earlier refunds' debits took back — and the
debt is recovered from later captures and BNPL financings before any payout (`INV-MER-05`).
`INV-BNPL-07` holds the bound: Σ refunds ≤ G under the purchase's lock and the fee returned by the
cumulative method telescoping to f, so a purchase's refunds debit the payable by at most G − f in
total. A BNPL refund posts in its own transaction under the payable's lock — there is no provider, no
dispatch and no reservation — so the platform's worst case on one purchase is the net it financed and
the merchant has since been paid. A payable below zero is counted by `finapp.ledger.negative.positions`
and named in the merchant's drill-down (the `BNPL_REFUND` kind, `P12-TSK-016`). The statement above is
unchanged; read with this note, its "never by money the platform paid out" admits, for a BNPL purchase
only, that purchase's own financed net already paid out.* *(As built by `P12-TSK-021`: `BnplRefunds` debits the payable the
refund's net, `R - r_f`, never refused for a short payable; the bound holds by the fee's arithmetic and is re-judged as a
second rank by `bnpl V012`'s refund trigger for every writer - Σ the purchase's payable debits ≤ its financed net, Σ fees
returned ≤ its fee; `finapp.ledger.negative.positions{purpose=MERCHANT_PAYABLE}` counts a payable below zero whatever the
source. Hermetic: `BnplRefundsTest#aRefundAfterPayoutIsBoundedByThePurchasesNet` (the payable at exactly -(G - f) after
the payout and two refunds), `#theB7BoundHoldsForEverySplit`; database, owed:
`BnplRefundDatabaseTest#aRefundAfterPayoutIsBoundedByThePurchasesNet`.)*

---

---

# Rails — `INV-RAIL`

*Catalogued by the Phase 6 → 7 transition (2026-09-24), the `INV-PAY` and `INV-MER` precedent:
Phase 7's gate properties given stable IDs before any rail code exists, so the register can
demand their demonstrations by identifier rather than by prose. Decisions in ADR-0059…0062.
`INV-REV-03`, catalogued at initiation and subjectless until now, is Phase 7's too.*

### INV-RAIL-01 — A rail's capabilities are declared, and the domain acts on them, never on a rail's name
**Statement:** Every rail-dependent decision — whether an operation can be reversed, how a
refund executes, which clearing position a completion posts to, whether a dispute can follow,
how long an unknown outcome may last — is taken by reading the capability descriptor the
attempt's rail declared, and the descriptor in force is recorded with the payment's routing
decision. No code outside a rail's adapter branches on a rail's name.
**Why:** A core that knows rail names turns the second scheme into a core change, and a
behaviour that differs from what the rail declared is a finality or reversal decision nobody
can explain afterwards (ADR-0059).
**Enforce:** `DOMAIN` + `STATIC` — a build rule refusing rail-name literals outside adapter
packages and configuration.
**Verify:** Per-rail contract tests asserting each adapter behaves as its descriptor says; the
static rule's own planted-violation test.
**Phase:** 7

*(As built by `P9-TSK-014` (2026-10-05): the corridor rail declares only what is true -
`RefundMode.NONE`, coherent only on a push rail (`RailCapabilitiesTest`), and routing refuses a
`PAY_IN` judged on such a rail with `RoutingRejection.DIRECTION_UNSUPPORTED` before any other reason
(`RoutingPolicyVersionTest`). Operations are looked up per routed rail in `RailOperations`, never a
wired singleton: a withdrawal routed to the corridor rail is refused inside Tx1 with nothing written or
sent (`WithdrawalDatabaseTest`). `corridor-sim-a`'s money semantics are frozen in
`RailMoneySemanticsArePinnedTest`, its name confined by `RailVocabularyIsConfinedTest`; the probes are
recorded in `MUTATION_TESTING.md` §2.)*

### INV-RAIL-02 — A payment is routed once, deterministically, and never re-routed after an ambiguous dispatch
**Statement:** A payment's rail is chosen by a pinned routing-policy version over stored
inputs, before anything is sent; recomputing the pinned version over the stored inputs
reproduces the choice. A decision advances to another rail only on knowledge that nothing was
sent on the current one (an eligibility refusal before dispatch, or `NOTHING_SENT`), never after
a dispatch whose outcome is unknown. A payment is dispatched on at most one rail at a time.
**Why:** An unexplainable route is an unexplainable finality, and a fallback after an ambiguous
dispatch is a second payment — `INV-PAY-04`'s double effect reached through routing (ADR-0060).
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (the decision row exists before the attempt can dispatch,
is frozen by trigger, and has one open step).
**Verify:** Recomputation tests over stored inputs across policy versions; a fallback test on
`NOTHING_SENT` and a refusal test on `INDETERMINATE`; a ten-way race confirming one decision
and one dispatch per payment. *(The Phase 7 review, `P7-DOC-001`: recomputation from a STORED
row - `PaymentEndpointDatabaseTest#routingPinsTheConfirm` re-decides a refused and a chosen
decision from their own columns, pinned version and recorded availability; "fallback" reads as
the `ABANDONED` step on `NOTHING_SENT`, Phase 7 having no cross-rail advance (ADR-0060 §5 as
corrected); an availability change after the decision reroutes nothing in flight -
`PaymentSweeperDatabaseTest#anAvailabilityChangeReroutesNothingInFlight`; and the decision
commits with its attempt - `PaymentAuthorizationDatabaseTest#aCrashMidCallStrandsTheDispatchVisibly`.)*
**Phase:** 7

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0080 (`Proposed`), in force when
`P9-TSK-017` and `-019` land: the corridor provider that tokenises a beneficiary is selected
once, at registration, recomputable from `corridor_selection`'s stored steps; every outbound
credit is then routed through the one routing door as routing's third subject, storing
`destination_country` and per-candidate reachability — and only the beneficiary's issuing
rail is reachable, so a re-route is impossible by construction.)*

### INV-RAIL-03 — Bank account identifiers and payment aliases never enter the platform
**Statement:** An external account is known to the platform only by an opaque reference
issued by its rail's provider, a four-character display suffix and the confirmation-of-payee
result. No account number, international account identifier, routing code or alias value is
stored, logged, published or returned; values of those shapes are refused at the surface, in
the domain types and by `CHECK` constraints.
**Why:** The provider already holds the details and the reference is all a payment needs;
holding them would put bank data and personal identifiers in scope for no gain — `INV-PAY-02`'s
reasoning for bank data (ADR-0056 §7, ADR-0062 §2).
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` + `STATIC` (the wrapping rule's vocabulary).
**Verify:** Shaped-value refusal tests at each layer; the `information_schema` column sweep; a
needle test over logs, events and responses. *(The needle covered the registration alone until
the Phase 7 review, `P7-DOC-001`: `PayByBankDatabaseTest#theDestinationReachesNoSink` carries a
registered destination and its grant through a pay-in funding the wallet and a withdrawal out
of it, and asserts both absent from the captured log output, every response, every audit record
and every event, the destination resting on its instrument alone.)* *(Extended to settlement at
the Phase 7 → 8 transition, ADR-0066 (`Proposed`), in force when `P8-TSK-002`, `-008` and `-016`
land: the sweep covers the `settlement` and `reconciliation` schemas; the needle rides a bank
statement whose free text carries an IBAN shape, and the delivery is refused at the door, storing
metadata only; `settlement.line_reference` refuses bank-identifier and alias shapes by `CHECK`;
the bank adapter extracts only the structured remittance reference, never a name or an account
identifier; and a break note refuses an IBAN shape.)* *(As built by `P8-TSK-016`: the statement
needle is `BankStatementCashDatabaseTest#theIbanNeedleReachesNoSink` — an IBAN in a statement's
`:86:` refused at the door as `ACCOUNT_IDENTIFIER`, recorded in `refused_delivery` by field name
only, and absent from the captured log, every audit record, every event, `refused_delivery`,
`line_reference` and every meter's tags — beside, rather than inside, `PayByBankDatabaseTest`,
whose needle is the payment rail's; the adapter's reference classes exclude instrument shapes, so
an IBAN put where a reference belongs meets the same screen; and the platform's own settlement
account is configured only by the bank's opaque reference.)*
**Phase:** 7

*(Corrected 2026-10-02 by the Phase 8 → 9 transition, the audit's `SEC-03` and `SEC-04`: "refused
at the surface" held for the contiguous international shape alone, and six person-written reason
doors screened nothing. An account identifier in its ISO 13616 printed form — groups of four
separated by single spaces or dashes, `GB82 WEST 1234 5698 7654 32` — is now refused wherever
the platform screens prose: by the settlement door's free-text screen and, through the platform's
one `InstrumentShapes` rule, by every person-written reason, note and narrative at the domain and
by `CHECK` (settlement `V012`, reconciliation `V019`). The printed form is checksum-gated (mod 97)
because groups of four collide with ordinary words; the contiguous shape stays shape-alone.)*

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0080 and ADR-0081 (`Proposed`), in
force when `P9-TSK-016` and `-017` land: a cross-border beneficiary is held to the same
rule — the corridor provider's opaque `destination_reference`, a four-character display
suffix and the payee-check verdict, with the provider-attested destination country, currency
and entity type admitted as attributes. The beneficiary's name is held only by `kyc`,
encrypted with AAD bound to its screening, and never by `crossborder` or `payments`.)*

*(In force since `P9-TSK-017` (2026-10-06). `crossborder V003`'s `beneficiary` holds the provider's
reference (shape `CHECK` plus the instrument-shape twin), a suffix, the payee check with its
acknowledgement and the attested attributes; the nickname is screened at the domain and by `CHECK`;
the grant is exchanged and never stored (the registration keeps only a SHA-256-derived exchange
reference). The guard is extended by `CrossBorderBeneficiaryDatabaseTest#theSchemaHoldsTheLine` -
every text column of `crossborder.beneficiary`, swept from `information_schema`, refuses an IBAN by
`CHECK` - and the registration's needle, `#theNameAndGrantReachNoSink`: the name and the grant in no
table the application reads, no event and no response.)*

### INV-RAIL-04 — Every external rail's value in flight has its own clearing position
**Statement:** A completion on an external rail posts to that rail's own clearing account —
the card PSP's `SETTLEMENT_CLEARING`, the instant scheme's `INSTANT_CLEARING` — and never to
another rail's; no clearing account nets two counterparties.
**Why:** Phase 8 discharges each position against its own counterparty's settlement evidence;
a shared position nets one counterparty's receivable against another's payable and makes the
break unexplainable (`INV-SET-01`, ADR-0059 §4).
**Enforce:** `DOMAIN` — the rail's descriptor names its clearing purpose; postings assert it
(`DIRECTION:PURPOSE`).
**Verify:** Per-rail posting tests asserting the purpose; the multi-rail storm reconciling each
clearing position against its own rail's records.
**Phase:** 7

*(Phase 8's discharge side, as built by `P8-TSK-017`: the scheme's evidence — its report's fees and
its remittance's cash — reaches only `INSTANT_CLEARING`, the position read from the source's
declaration; `SETTLEMENT_CLEARING` stands unmoved beside it (`SchemeCycleCashDatabaseTest`, the
position probe recorded in `MUTATION_TESTING.md` §2).)*

*(Amended at the Phase 8 → 9 transition (2026-10-02), ADR-0078 (`Proposed`), in force when
`P9-TSK-010` lands: "its own clearing position" is read per (purpose, counterparty) for the
counterparty-owned purposes — `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` are
`OwnerKind.COUNTERPARTY`, one account per declared counterparty and currency, seeded by the
migration that admits the counterparty, never minted at runtime — and still never nets two
counterparties. The existing operational clearings are untouched.)*

*(As built by `P9-TSK-010` (2026-10-05): ledger `V021` admits `OwnerKind.COUNTERPARTY`, the
append-only registry `ledger.counterparty` and `FX_PROVIDER_CLEARING` as the first
counterparty-owned purpose — no account yet; `fx-sim-a` and its five arrive with `V022`. A
`COUNTERPARTY` account's `owner_ref` names a registry row by trigger for every writer;
`ChartOfAccounts.resolve(purpose, currency)` refuses a counterparty purpose and
`resolve(purpose, counterpartyCode, currency)` serves it; `CounterpartyClearingIsNamedByDeclarationsTest`
confines the purpose to its declarations. Proven by `CounterpartyChartDatabaseTest` and
`ChartOfAccountsTest`; the probes are recorded in `MUTATION_TESTING.md` §2.)*

*(As built by `P9-TSK-014` (2026-10-05): ledger `V024` admits `CORRIDOR_CLEARING` - a LIABILITY,
credit-normal, counterparty-owned - with `corridor-sim-a`'s registry row and its USD, JPY and BHD
accounts, joining the reconciled positions; the corridor rail declares it and its counterparty is its
rail id, so the rail, the chart and the source `corridor-sim-a.settlement` are composed from one
declaration and startup is refused without any of them (`EverySettlingPositionHasASourceTest`,
`CorridorSourceDatabaseTest`). A merchant return and a corridor return are never mistaken for each
other: the waiting-return reader is scoped by source and the reference lookup resolves within the
item's source family (`JdbcInternalReferenceLookupTest`, `CorridorSourceDatabaseTest`).)*

---

# Disputes — `INV-DSP`

*Catalogued by the Phase 6 → 7 transition (2026-09-24). Decisions in ADR-0061.*

### INV-DSP-01 — Refunds and chargebacks together never take more from the counterparty than the capture credited it
**Statement:** For every captured card payment, the sum of non-failed refunds and of the
chargeback amounts debited to the counterparty never exceeds the captured amount, judged under
the attempt row lock. A chargeback's excess over what remains — value the network took that
the platform had already returned — posts to `CHARGEBACK_RECOVERABLE`, never to the
counterparty; a refund that would breach the bound is refused.
**Why:** The phase's named risk: a merchant that refunded properly would otherwise pay twice,
and a refund after a chargeback would give away the same money twice (ADR-0061 §3).
**Enforce:** `DOMAIN` — the combined bound under the attempt lock, both money paths — **and
`DB-CONSTRAINT`** since `P7-TSK-013`: payments `V021`'s dispute-attribution trigger and the
re-stated refund-bound trigger hold the same arithmetic for every writer, both under advisory
namespace 3. *"Debited to the counterparty" reads as ATTRIBUTED to it — posted, or parked in
`CHARGEBACK_RECOVERABLE` when its account takes no postings (ADR-0061 §5): a parked share is the
counterparty's, and a second cycle must not attribute it twice. The excess comes back to the
counterparty whenever headroom is freed — a capture landing after the chargeback was stated,
a counted refund failing, or a sibling chargeback won — so the split is always the one a
chargeback arriving now would take.*
**Verify:** A chargeback on a fully and a partially refunded payment; a refund after a
chargeback refused; refunds racing a chargeback counted in the tables; a counted refund that
later fails re-attributing its share. *(All in `ChargebackAccountingDatabaseTest`; the raw-writer
ranks in `PaymentsSchemaDatabaseTest`; the arithmetic swept in `ChargebackSplitTest`.)* *Under
load (`P7-TST-002`): `DisputeBatteryDatabaseTest` - the bound raced both ways (the
chargeback's, whatever the interleaving; the refund's, forced beside a standing partial
chargeback), a second cycle after a loss and after a win, a parked share on a closed wallet,
and every battery attempt read against the bound at rest.*
**Phase:** 7

### INV-DSP-02 — Every dispute stage posts once, and a resolution reverses exactly what it resolves
**Statement:** Each financial stage of a dispute — the chargeback, the win, the write-off, a
reported dispute fee — posts at most once, keyed by the dispute and the stage, behind a
conditional stage transition; a win's posting is the exact inverse of the chargeback's
principal lines, and the card rail's clearing position moves by exactly what the network did.
**Why:** Duplicate and out-of-order notifications are expected (`CLAUDE.md` rule 8); a second
posting per stage double-debits, and a win that does not mirror its chargeback leaves a
residue nobody can explain (ADR-0061 §2, §4).
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (the posting claim's unique key; `UNIQUE (provider,
provider_dispute_reference)`). *Since `P7-TSK-013` the keys are `dispute-chargeback:`,
`dispute-attribution:`, `dispute-won:`, `dispute-restoration:`, `dispute-loss:` and
`dispute-fee:<id>` — two entries per financial stage, the external fact and the attribution,
whose per-account effect is ADR-0061 §4's table — plus `dispute-reattribution:<dispute>:<cause>`;
each behind the stage's conditional transition, so a duplicate finds both taken.*
**Verify:** Ten-way duplicate notification races counted in the tables; out-of-order stage
delivery; a win netting its chargeback to zero per account. *Under load (`P7-TST-002`):
`DisputeBatteryDatabaseTest` - every stage of thirteen scenarios delivered ten ways (five under
one event id, five fresh), each dispute's journal lines exactly the lines its own record
implies, one trail row per edge, one record and one fact per stage, a win leaving nothing but
its fee, a loss writing off exactly the excess.*
**Phase:** 7

### INV-DSP-03 — Dispute evidence is least-privilege, encrypted, and every access audited
**Statement:** Representment evidence is readable only by the payment's merchant (tenant-scoped)
and by operators holding the dispute permission; it is encrypted at rest under a key held
outside the database, and every read and submission is audited with actor, dispute and outcome.
**Why:** Dispute evidence carries customer details, receipts and correspondence; it is
`INV-KYC-06`'s class of material with money attached (ADR-0061 §7).
**Enforce:** `DOMAIN` + `DB-PRIVILEGE`. *Since `P7-TSK-014`: one store reaches the content
(`DisputeEvidenceStore`, holding the dispute-evidence key alone - never the provider-evidence or
document key), every content read writes `payments.DisputeEvidenceRead` in the read's own
transaction and every wire transmission to the PSP writes `payments.DisputeEvidenceTransmitted`
before the bytes leave; the merchant's reads carry the tenant predicate in the statement
(`INV-MER-01`), the operator's sit behind `DISPUTE_ADMINISTER`; `payments.dispute_evidence` is
`SELECT, INSERT` only for the application role, GCM's tag arithmetic a `CHECK`, the SHA-256
verified on every read.*
**Verify:** Cross-tenant and unprivileged negative tests; a ciphertext-at-rest test; the audit
record per access. *(`DisputeResponseDatabaseTest` - `#evidenceIsUnreadableAcrossTenants`,
`#evidenceRestsAsCiphertextAndEveryReadIsOnTheRecord`, `#aTamperedDocumentIsNeverServedOrSent`;
`MerchantTenancyBatteryDatabaseTest`'s addressed routes; `PaymentsSchemaDatabaseTest#theRepresentmentSchemaBindsEveryWriter`;
and, the operator's own unprivileged negatives added by the Phase 7 review,
`#anOperatorAnswersOnlyAPaymentWithNoMerchant` - a merchant administrator refused on the
operator's evidence upload, evidence read and representment, nothing written and no read
recorded.)*
*Under load (`P7-TST-002`): `DisputeBatteryDatabaseTest` - answers racing the network's verdict
and each other, every admitted answer's one transmission on the record, and answers and
documents after resolution refused with nothing written or sent.*
**Phase:** 7

---

# Invariant Index

| Group | IDs | Concern |
|-------|-----|---------|
| `INV-MON` | 01–06 | Money representation and precision |
| `INV-LED` | 01–06 | Double-entry ledger |
| `INV-BAL` | 01–05 | Balance correctness |
| `INV-HIST` | 01–04 | Immutable history and evidence |
| `INV-IDEM` | 01–04 | Idempotency |
| `INV-CON` | 01–03 | Concurrency |
| `INV-LIFE` | 01–04 | Lifecycle and state |
| `INV-REV` | 01–04 | Reversal and correction |
| `INV-EVT` | 01–04 | Events and publication |
| `INV-SET` | 01–07 | Settlement |
| `INV-REC` | 01–10 | Reconciliation |
| `INV-FX` | 01–09 | Foreign exchange |
| `INV-XB` | 01–04 | Cross-border payments |
| `INV-ACC` | 01–05 | Accounting and reporting |
| `INV-AUD` | 01–04 | Security and audit |
| `INV-CRD` | 01–12 | Credit decisioning |
| `INV-LND` | 01–14 | Lending |
| `INV-BNPL` | 01–10 | Buy now, pay later |
| `INV-IDN` | 01–08 | Identity, credentials and sessions |
| `INV-KYC` | 01–06 | Verification and case management |
| `INV-CNS` | 01–04 | Consent |
| `INV-PAY` | 01–05 | Payments and providers |
| `INV-MER` | 01–07 | Merchants and checkout |
| `INV-RAIL` | 01–04 | Payment rails and routing |
| `INV-DSP` | 01–03 | Disputes and chargebacks |

**152 invariants.** Every one must be enforced and verified before the phase that owns it can
pass its exit gate. *(The count moved from 110 to 120 at the Phase 8 → 9 transition,
2026-10-02: `INV-FX-04`…`INV-FX-09` and `INV-XB-01`…`INV-XB-04` catalogued, and thirteen
entries restated, each with its dated provenance.)* *(The count moved from 120 to 128 at the
Phase 9 → 10 transition, 2026-10-07: `INV-CRD-05`…`12` catalogued.)* *(The count moved from 128
to 142 at the Phase 10 → 11 transition, 2026-10-10: `INV-LND-01`…`14` catalogued, a new group.)*
*(The count moved from 142 to 152 at the Phase 11 → 12 transition, 2026-10-16: `INV-BNPL-01`…`10`
catalogued, a new group, and `INV-MER-07` amended — proposed, in force when `P12-TSK-021` lands.)*
