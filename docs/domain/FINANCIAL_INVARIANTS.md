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

### INV-XB-03 — What was shown is what is held, posted and instructed
**Statement:** What the customer was shown — destination amount, fee, total debit — is
exactly what is held, posted and instructed.
**Why:** Any drift between disclosure and execution is a silent re-pricing of a customer who
already accepted a price.
**Enforce:** `DB-CONSTRAINT` (the offer's amounts copied onto the payment and the outbound
credit, frozen by trigger) + `DOMAIN`.
**Verify:** The disclosure-to-posting test: offer = hold (`P9-TSK-019`) = completion posting =
instruction (`P9-TSK-020`).
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

### INV-CRD-02 — Decisions are immutable and carry reason codes
**Statement:** A recorded decision is never modified, and every adverse decision carries
reason codes sufficient for an adverse-action explanation.
**Why:** Regulatory explainability.
**Enforce:** `DB-PRIVILEGE` + `DB-CONSTRAINT`.
**Verify:** Immutability tests; reason-code coverage tests.
**Phase:** 10

### INV-CRD-03 — Bureau access requires recorded consent
**Statement:** No external credit data is retrieved without a recorded, current lawful basis.
**Why:** Consent is a legal precondition, and it is distinct from authentication and
authorization.
**Enforce:** `DOMAIN`.
**Verify:** Consent-absent rejection tests.
**Phase:** 10

### INV-CRD-04 — Score is not decision
**Statement:** A credit score, risk score, decision and outcome are separately modelled and
separately recorded.
**Why:** `CLAUDE.md` §Domain Distinctions.
**Enforce:** `DOMAIN`.
**Verify:** Domain review; model inspection.
**Phase:** 10

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
attributions — and exists nowhere else. *(The refund terms were added by the Phase 6 review,
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
remainder — as `reconciliationAttributed`, never `captured` (`P8-TSK-015`).
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
exist.)*
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
every transition.
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
| `INV-CRD` | 01–04 | Credit decisioning |
| `INV-IDN` | 01–08 | Identity, credentials and sessions |
| `INV-KYC` | 01–06 | Verification and case management |
| `INV-CNS` | 01–04 | Consent |
| `INV-PAY` | 01–05 | Payments and providers |
| `INV-MER` | 01–07 | Merchants and checkout |
| `INV-RAIL` | 01–04 | Payment rails and routing |
| `INV-DSP` | 01–03 | Disputes and chargebacks |

**120 invariants.** Every one must be enforced and verified before the phase that owns it can
pass its exit gate. *(The count moved from 110 to 120 at the Phase 8 → 9 transition,
2026-10-02: `INV-FX-04`…`INV-FX-09` and `INV-XB-01`…`INV-XB-04` catalogued, and thirteen
entries restated, each with its dated provenance.)*
