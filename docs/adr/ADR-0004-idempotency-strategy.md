# ADR-0004 — Idempotency for money-moving commands is enforced at the database

Status: Accepted

Date: 2026-08-31

## Context

`CLAUDE.md` rule 7 requires critical money-moving commands to be idempotent, and rule 8
requires duplicate requests, events and webhooks to be safe.

The scenario is unavoidable: a client sends a transfer request, the server commits, the
response is lost to a network failure, and the client retries. Without idempotency, the
customer is debited twice. `CLAUDE.md` §Failure Engineering lists this explicitly.

The design questions are where the guarantee lives, what the key scopes to, and what happens
when the same key arrives with a different request body.

## Decision

**Idempotency is enforced by a unique database constraint at the financial boundary.**

- An idempotency record table keyed uniquely on `(scope, idempotency_key)`.
- `scope` identifies the command type and the owning principal, so keys cannot collide
  across different commands or across clients.
- The record stores a **request fingerprint** (a hash of the semantically significant
  request fields), a state (`IN_PROGRESS` / `COMPLETED` / `FAILED`), the stored response,
  and timestamps.
- Execution: claim the key by inserting the record; on unique-constraint violation, the key
  is already claimed. Execute the command, then persist the outcome. A retry after
  completion replays the stored response.
- **A matching key with a differing fingerprint is rejected with a distinct conflict error**
  — never a silent success, never a second effect.
- The idempotency record and the command's financial effect commit in the same transaction.
- `IN_PROGRESS` claims have a defined resolution path (bounded wait then conflict, plus
  reclaim after a crash timeout) so a crashed request cannot block a key forever.
- Retention is bounded by an explicit expiry policy.
- Inbound external events (webhooks, messages) use the same mechanism via the inbox
  (ADR-0005).

## Alternatives Considered

### Option A — In-memory or Redis cache of seen keys
Pros: Fast; no schema.
Cons: **Not a guarantee.** Cache eviction, restart or a split-brain admits a duplicate
financial effect. Not atomic with the financial transaction, so a crash between the effect
and the cache write duplicates on retry. Violates `CLAUDE.md` rule 12 — Redis is not
financial truth. Rejected outright.

### Option B — HTTP-layer filter storing responses
Pros: Transparent to domain code; applies uniformly.
Cons: The guarantee is at the wrong boundary. Any non-HTTP path — an event consumer, a
scheduled job, an internal call — bypasses it entirely. Not atomic with the financial
transaction.

### Option C — Natural business key deduplication
Pros: No extra infrastructure.
Cons: Two genuinely distinct transfers of the same amount to the same beneficiary on the
same day are legitimate. Business keys cannot distinguish a retry from a real repeat.

### Option D — Unique constraint at the financial boundary (chosen)
Pros: The database is the only component that can arbitrate under true concurrency. Atomic
with the financial effect. Works for HTTP, events, jobs and internal calls alike. Survives
crash and restart. Fingerprint comparison catches client defects instead of hiding them.
Cons: A table and a wrapper to maintain. Retention policy required. Clients must supply
keys, which must be documented and enforced.

## Consequences

Positive:
- Duplicate submission is safe under genuine concurrency, proven by test rather than
  assumed.
- Key reuse with a different payload surfaces a client bug instead of silently returning the
  wrong response.
- The same mechanism serves API commands, event consumers and scheduled processes.

Negative:
- Every money-moving endpoint must declare and require an idempotency key.
- Contention on a hot key serialises; the `IN_PROGRESS` path must be handled deterministically.
- The idempotency table grows and needs a retention job.

Operational impact: Idempotency-conflict rate is a monitored metric — a spike indicates a
client defect or an attack.

Security impact: Keys are client-supplied and untrusted: format-validated, scoped to the
principal, and never usable to read another principal's stored response.

Financial impact: Directly prevents duplicate financial effects — one of the highest-impact
defect classes in payments.

## Invariants / Constraints

`INV-IDEM-01`, `INV-IDEM-02`, `INV-IDEM-03`, `INV-IDEM-04`, `INV-CON-02`.

## Follow-up

- Phase 4: apply to transfers; concurrency tests with two threads on one key.
- Phase 5: apply to payment intents, captures and refunds.
- Phase 8/11/14: `INV-IDEM-02` period-keyed idempotency for accrual, settlement and close.
- Revisit retention policy when volume data exists.
