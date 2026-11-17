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
- *The Phase 6 → 7 transition*: a claim whose key the platform **derives** takes a scope no
  client command claims in. The checkout's payment was keyed `checkout:<checkoutId>` in
  `payment.create`, where any customer could claim that predictable key first through
  `POST /v1/payments` and leave the session unpayable; it claims in `checkout.payment` now.
  `X-TSK-003` carries the rest of this ADR's principal clause, and must account for the
  refund's `V008` dispatch key — a second rank beneath the refund's claim, and one that is
  unscoped by principal.

## Amendment — a scope moves to its principal over two releases (`X-TSK-003`, 2026-11-17)

**The principal clause now holds for every client-keyed command.** Eleven scopes that every client
shared — `transfer.execute`, `payment.create`, `payment.refund`, `payments.routing.version`,
`accounts.open`, `accounts.add-currency`, `merchant.onboard`, `merchant.api-key.issue`,
`merchant.payout-destination.propose`, `ledger.adjust` and `ledger.projection-rebuild` with its
approval and rejection — are claimed as `<scope>:<keyedType>:<id>`, the form every desk already used,
so one client's key value no longer refuses another's request.

**A scope is never moved in one release.** A scope is a namespace: a retry spanning the deploy looks
under the new scope, misses the claim its first attempt made under the old, and acts again — for a
transfer, a second money movement with nothing behind the claim to stop it. The release that moves a
scope claims the **old scope first**, then the new (`IdempotentExecutor`'s overloads taking a
`movedFrom` key, built by `IdempotencyKey.movedFrom`):

- the old claim carries **this request's fingerprint** — made before the move, or by a replica of the
  previous release mid-roll — so it is resolved there exactly as an existing claim always is;
- it carries **another fingerprint** — another principal's, since every moved scope's fingerprint names
  its caller — so it is no claim of this request's, and the command claims under its own scope alone;
- the old scope is **free**: the request holds both, acts once, and records its outcome under both, so a
  replica that knows only the old scope replays it.

Claiming the old scope first makes every flight of one request, from either release, meet at the same
unique index, and no two flights wait in opposite orders. While another principal's transaction holds the
old key, its neighbour is told `api.IdempotencyInProgress` for that moment, never refused. The cost of the
transition is that release's second insert per command; its end is `X-TSK-028`, which drops the old claim
once no record under an old scope is inside its expiry.

**What stays constant, and why** — the register is `IdempotencyScopesNameTheirPrincipalTest`'s:

- **the platform's own keys**, which no client chooses, so two principals cannot meet on one:
  `ledger.post` and `ledger.reverse` (ledger `V037`'s unique index and three readers key on
  `ledger.post:<key>`, so they never move), the held payment's capture and void, a withdrawal's release
  and denial, and the two risk-action doors (each keyed by the risk action), and `checkout.payment`
  (keyed by the session);
- **registration** (`party.RegisterCustomer`): no principal exists before it; the login identifier's
  unique index stands behind the claim.

**The refund's dispatch key stays the client's raw key in this release.** A replica of the previous
release that takes over a crashed flight's old-scope claim re-dispatches by that key and must converge on
the refund this release wrote; scoping it waits for `X-TSK-028`, when no such replica can exist. Until
then a second operator reusing a key value for another payment is refused by the dispatch key
(`payments.RefundKeyReused`), and the same payment, amount and reason converges on the first refund.
