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
**Enforce:** `DOMAIN` (no reversible field) + `STATIC` (`secretsAreWrapped`) + `DB-CONSTRAINT`
(the column stores a derivation, and its classification forbids anything else).
**Verify:** Test asserting no persisted or emitted representation contains the input; schema review.
**Phase:** 1

### INV-IDN-02 — Credential derivation parameters are recorded per credential
**Statement:** Every stored credential records the algorithm and parameters used to derive it.
**Why:** Parameters must increase as hardware improves, and a store with one global setting cannot
be migrated without either invalidating every credential or knowing what each one used. This is
`INV-HIST-04`'s rule applied to the thing that authenticates a person.
**Enforce:** `DB-CONSTRAINT` (`NOT NULL` algorithm and parameters).
**Verify:** Test that a credential written under old parameters still verifies and is upgraded on
next successful use.
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
| `INV-IDN` | 01–07 | Identity, credentials and sessions |

**71 invariants.** Every one must be enforced and verified before the phase that owns it can
pass its exit gate.
