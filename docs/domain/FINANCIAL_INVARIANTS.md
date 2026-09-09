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
**Verify:** Test asserting stored payload matches the received bytes.
**Phase:** 2 (screening), 5 (providers), 8 (files)

### INV-HIST-03 — Audit records are append-only
**Statement:** Audit records cannot be updated or deleted by any application role.
**Why:** `CLAUDE.md` — application logs are not a regulatory-grade audit trail; an audit
trail that can be edited is worth less than none, because it invites false confidence.
**Enforce:** `DB-PRIVILEGE`.
**Verify:** Privilege test.
**Phase:** 0

### INV-HIST-04 — Decisions record the version of the policy that produced them
**Statement:** Any versioned artefact (credit policy, fee schedule, matching rule, rounding
policy, risk rule set) used in a decision is pinned and recorded on that decision.
**Why:** Without version pinning, a past decision cannot be reproduced or defended.
**Enforce:** `DB-CONSTRAINT` (`NOT NULL` version reference).
**Verify:** Replay test reproducing a stored decision exactly.
**Phase:** 6 (fees), 8 (matching), 10 (credit), 13 (risk)

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
**Verify:** Crash-and-rerun integration tests.
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
**Verify:** Provider-timeout-then-success contract tests; unknown-state age metric.
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
**Enforce:** `DOMAIN` + `PROCESS` (four-eyes) + `DB-CONSTRAINT` (`NOT NULL` reason).
**Verify:** Authorization negative tests.
**Phase:** 3, 8

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

### INV-SET-02 — Settlement expectations are tracked
**Statement:** Every operation expected to settle externally creates a tracked expectation
that ages and alerts when unmet.
**Why:** Value that never settles must be visible, not silently assumed received.
**Enforce:** `DOMAIN`.
**Verify:** Ageing metric with alerting; missing-settlement tests.
**Phase:** 8

### INV-SET-03 — Late settlement is handled, not rejected
**Statement:** Settlement arriving later than expected is processed correctly rather than
discarded as stale.
**Why:** `CLAUDE.md` §Failure Engineering — "settlement arrives late".
**Enforce:** `DOMAIN`.
**Verify:** Late-arrival tests.
**Phase:** 8

---

# Reconciliation — `INV-REC`

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
record is silently dropped, auto-cleared or suppressed.
**Why:** An unexplained difference that disappears is a loss nobody noticed.
**Enforce:** `DOMAIN`.
**Verify:** Tests for every break type; unmatched-count metric.
**Phase:** 8

### INV-REC-03 — Resolution is a controlled adjustment
**Statement:** A break is resolved by posting a compensating entry with a reason code and
authorisation — never by editing either record.
**Why:** `FINANCIAL_INVARIANTS` original text.
**Enforce:** `DOMAIN` + `PROCESS` (four-eyes above threshold).
**Verify:** Resolution authorization tests; immutability tests.
**Phase:** 8

### INV-REC-04 — Matching is deterministic and explainable
**Statement:** Every match records the rule version and tolerance that produced it, and the
same inputs always produce the same matches.
**Why:** `.claude/rules/reconciliation-domain.md` — matching must be deterministic and
explainable.
**Enforce:** `DOMAIN` + `DB-CONSTRAINT` (`NOT NULL` rule version).
**Verify:** Replay test producing identical matches.
**Phase:** 8

### INV-REC-05 — Suspense is temporary and aged
**Statement:** Value parked in a suspense account is tracked, aged, reported and alerted on.
Suspense is never a permanent resting place.
**Why:** Ageing suspense is an unrecognised loss or liability.
**Enforce:** `PROCESS` + `DOMAIN`.
**Verify:** Suspense age and balance metrics with alerting.
**Phase:** 3 (accounts), 8 (management)

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

### INV-FX-02 — Rates are server-authoritative and time-bounded
**Statement:** Conversion rates originate from the platform, carry an explicit validity
window, and expired or stale rates are rejected. Client-supplied rates are never trusted.
**Why:** Client-controlled rates are a direct financial exploit.
**Enforce:** `DOMAIN`.
**Verify:** Expired-quote and stale-rate rejection tests.
**Phase:** 9

### INV-FX-03 — Spread is recognised explicitly
**Statement:** Any margin between the sourced rate and the customer rate is posted as
revenue explicitly, not concealed inside the applied rate.
**Why:** Hidden margin is unreportable and unauditable revenue.
**Enforce:** `DOMAIN`.
**Verify:** Posting-composition tests.
**Phase:** 9

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
**Phase:** 3, 8, 10, 13, 14

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
process-local session cache (ADR-0030, ADR-0024).
**Verify:** Multi-instance test: revoke on one instance, assert refusal on another (`P0-TST-009`
convention).
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
| `INV-SET` | 01–03 | Settlement |
| `INV-REC` | 01–05 | Reconciliation |
| `INV-FX` | 01–03 | Foreign exchange |
| `INV-ACC` | 01–05 | Accounting and reporting |
| `INV-AUD` | 01–04 | Security and audit |
| `INV-CRD` | 01–04 | Credit decisioning |
| `INV-IDN` | 01–08 | Identity, credentials and sessions |
| `INV-KYC` | 01–06 | Verification and case management |
| `INV-CNS` | 01–04 | Consent |

**82 invariants.** Every one must be enforced and verified before the phase that owns it can
pass its exit gate.
