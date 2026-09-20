# ADR-0046 — No transaction spans a provider call: dispatch-before-call, UNKNOWN, and reconciliation by query

Status: Accepted (2026-09-21, `P5-DOC-001` — read against the implementation at the phase review)
Date: 2026-09-20
Phase: 5
Context: Payments
Supersedes: nothing. The "unknown-state handling and reconciliation-by-query sweeper"
decision anticipated for Phase 5; the counterpart ADR-0043's boundary clause demanded —
*"Phase 5 must not inherit this ADR by analogy."*

## Context

ADR-0043 gave transfers one local transaction because both legs were internal. Phase 5's
defining property is that the decisive step is **not**: a provider call crosses a network to
a party that may answer late, wrongly, twice or never. A transaction held open across that
call would pin a connection for the provider's worst-case latency, and — decisively — it
cannot help: the provider's side effect is not part of our transaction, so atomicity with it
is not available at any price. The design question is therefore not "how do we make it
atomic" but "which facts do we commit on each side of the call, so that every crash and
every ambiguity leaves a visible, resolvable state".

`MODULE_ARCHITECTURE.md` has recorded the answer's outline since the module was registered
(*"state is committed before the call and the outcome applied in a separate transaction"*),
and `P2-TSK-009` proved the choreography for KYC checks. What Phase 5 adds is money — and
`INV-LIFE-03`, catalogued at initiation for exactly this phase.

## Decision

1. **Two transactions per provider operation, with the call between them, holding no
   database connection.** The dispatch transaction commits the operation's `*_DISPATCHED`
   state, the platform-minted **provider idempotency reference** (`INV-PAY-04`, unique,
   `NOT NULL`, stored before anything is sent), the intent/attempt bookkeeping, and the
   audit record of the initiation. The outcome transaction applies the provider's answer
   through a **conditional transition**, retains the raw answer verbatim as evidence
   (`INV-HIST-02`), writes the outcome's audit record and outbox row, and — for capture and
   refund completion — the ledger posting, atomically with the transition (ADR-0048).

2. **Ambiguity commits `*_UNKNOWN`, never an assumed outcome.** A timeout, a 5xx, a
   malformed body, an unrecognised state (`INV-PAY-03`'s indeterminate default) or a
   transport failure **after send** each commit the operation's unknown state. A connection
   refused **before anything was sent** is knowledge, not ambiguity: it commits
   `FAILED(PROVIDER_UNAVAILABLE)`.

3. **A crash mid-call strands `*_DISPATCHED`, visibly, and the sweeper resolves it.** The
   provider received the request (dispatch committed first), so the stranded state is
   exactly the case a query can answer. This is the sweeper Phase 2 recorded as owed and
   ADR-0043 recorded as subjectless — it has a subject now, because the durable
   intermediate state genuinely exists.

4. **Reconciliation by query: every instance sweeps, no lease, no leader.** The sweeper
   polls for `*_DISPATCHED` and `*_UNKNOWN` rows older than their bounds, queries the
   provider **by our idempotency reference**, and applies the answer through the same
   conditional transitions. Concurrency needs no coordination mechanism: the query is
   read-only and idempotent at the provider, and the conditional transition's row count
   makes exactly one resolver's write land — concurrent sweepers, a racing webhook and a
   late synchronous response are all the same harmless race. No `DISTRIBUTED_EXECUTION.md`
   §3 exemption question arises beyond the schedule itself, which follows the relay's
   pattern (property-gated, every instance, registered).

5. **An unresolved `UNKNOWN` is a published, aging fact** — the unknown-state count and age
   meters, alertable, never silently expired into failure or success.

## Why

**Assuming a timeout means failure is the single most expensive mistake in payments**
(`DELIVERY_PLAN.md` §Phase 5.17), and it is exactly what a one-transaction design forces: a
transaction that must end when the call ends has only COMMIT-as-success and
ROLLBACK-as-failure to say, and rollback erases the evidence that anything was ever asked.
Dispatch-before-call inverts it: the platform's record of *having asked* is durable before
the provider can possibly have acted, so no crash, timeout or lost response can produce a
provider-side effect the platform has no record of. That record — the stored idempotency
reference — is simultaneously what makes recovery safe (`INV-PAY-04`: the retry and the
query name the same operation) and what Phase 8 reconciles on.

The no-lease sweeper follows from the same shape: because every resolver's write is a
conditional transition, resolution is idempotent end to end, and a lease would be a
coordination mechanism protecting nothing (the `P0-TSK-020` lesson inverted — the relay
needed its per-aggregate lock for *ordering*; resolution has no ordering to protect).

## Consequences

- The attempt machine needs its `*_DISPATCHED` and `*_UNKNOWN` states durable — ADR-0045's
  shape, and the exact inversion of ADR-0044's refusal of `PROCESSING`.
- The provider port must offer **query by our reference** as a first-class operation; a
  provider that cannot be queried cannot be safely integrated (contract requirement,
  `P5-TSK-003`).
- The customer-facing answer to "confirm" may honestly be *pending*: the intent's
  `PROCESSING` is a real, queryable state, and the API contract carries the
  asynchronous-outcome shape Phase 4 already established.
- Structural assertion owed by the implementation: **no connection is held during the
  call** (the `P1-TSK-026` discipline, asserted rather than described).

## Alternatives rejected

- **One transaction spanning the call** — atomicity with the provider is unavailable at any
  price; the design buys a pinned connection and evidence-destroying rollbacks.
- **Timeout-means-failure with compensation** — records a guess as a fact and compensates
  the wrong side of the truth; the compensation is itself a money-moving retry loop.
- **A leased/leader sweeper** — coordination protecting nothing, plus a new failure mode
  (the expired-lease race) in exchange for it.
- **Webhook-only resolution** — "the webhook never arrives" is one of the twelve named
  failure scenarios; a resolution path the provider controls is not a resolution path.

## Follow-ups

- `P5-TSK-009/-010` implement the discipline for authorization and capture; `P5-TSK-014`
  the sweeper; `P5-TST-001` demonstrates timeout-then-success end to end with exactly one
  financial effect.
- The schedule joins `DISTRIBUTED_EXECUTION.md` §3 with the relay's justification shape.
