# ADR-0045 — Payment intent and payment attempt: two aggregates, three machines

Status: Accepted (2026-09-21, `P5-DOC-001` — read against the implementation at the phase review)
Date: 2026-09-20
Phase: 5
Context: Payments
Supersedes: nothing. The "payment intent vs attempt modelling" decision the ADR register has
anticipated for Phase 5 since initiation.

## Context

Phase 5 introduces money movement whose outcome is decided by an unreliable third party.
`DELIVERY_PLAN.md` §Phase 5 requires the customer-facing objective and the provider-facing
try to be distinct — *"one intent may have many attempts"* — and `PAYMENT_LIFECYCLES.md`'s
original stub required provider states to map into a stable internal lifecycle. What that
lifecycle **is** had never been decided, and ADR-0044 established the discipline it must be
decided under: every state is earned by a producer, every state is durably observable, and a
fact is stored in one place.

The flow Phase 5 carries is the **wallet top-up**: a verified customer funds their platform
wallet from an external instrument through a simulated PSP (ADR-0049). No merchant exists
until Phase 6; one direction of external movement, one variable changed at a time — the
roadmap's stated reason for Phase 5's position.

## Decision

**Two aggregates in `payments` — `PaymentIntent` and `PaymentAttempt` — plus a bounded
`Refund` aggregate referencing a captured attempt. Three machines, every state
producer-earned:**

1. **Intent**: `REQUIRES_CONFIRMATION → {PROCESSING, CANCELLED}`,
   `PROCESSING → {SUCCEEDED, FAILED}`. Creation and confirmation are separate acts, which is
   what gives `CANCELLED` the producer ADR-0044 found missing for transfers: a real window
   between acceptance and execution. `PROCESSING` — refused for transfers — is **earned**
   here, because the outcome now belongs to a third party. `SUCCEEDED` is stable with **no
   outgoing edge**: refund state is the refund rows' fact, and storing it on the intent
   would be one fact in two places free to disagree.

2. **Attempt**: `AUTH_DISPATCHED → {AUTHORIZED, FAILED, AUTH_UNKNOWN}`,
   `AUTH_UNKNOWN → {AUTHORIZED, FAILED}`, `AUTHORIZED → CAPTURE_DISPATCHED`,
   `CAPTURE_DISPATCHED → {CAPTURED, FAILED, CAPTURE_UNKNOWN}`,
   `CAPTURE_UNKNOWN → {CAPTURED, FAILED}`. The `*_DISPATCHED` states are durable **on
   purpose** — committed before the provider is asked (ADR-0046) — and the `*_UNKNOWN`
   states are `INV-LIFE-03` made concrete. **Two unknown states rather than one**, so that
   *what* is unknown is a property of the machine itself: the resolution question, the
   query, and the legal exits differ between "did the authorization happen?" and "did the
   capture happen?", and a single `UNKNOWN` would need a second column the machine cannot
   see to answer them.

3. **Refund**: `DISPATCHED → {COMPLETED, FAILED}`, `DISPATCHED → UNKNOWN → {COMPLETED,
   FAILED}` — one operation, so one unknown state suffices. Bounded by the captured amount
   (`INV-PAY-05`), including under concurrent partials, at database rank.

4. **One attempt per intent in Phase 5, a schema built for N.** The attempt is its own
   aggregate with its own identity and a **partial unique one-live-attempt-per-intent
   index** as the concurrency arbiter. An automatic retry policy is a versioned artefact
   with nothing to calibrate it (the `P3-TSK-021` argument), and provider routing is
   Phase 7's — so a `FAILED` attempt fails its intent, and a customer who still wants to
   pay creates a new intent.

5. **Deliberately absent states, each named with its producer-to-come**: `REQUIRES_ACTION`
   (a challenge flow no simulated provider issues yet), `VOIDED` (abandoning an
   authorization has no producer in a flow that always captures; arrives with checkout
   expiry, Phase 6), `PARTIALLY_REFUNDED`/`REFUNDED` (derived, never stored),
   `DISPUTED`/`CHARGEBACK` (Phase 7's lifecycle), `CLEARING`/`SETTLED` (Phase 8's facts —
   `INV-SET-01` forbids Phase 5 pretending to know them).

## Why

The conventional single "payment transaction" entity collapses the customer's question
("did my top-up work?") into the provider's history (three tries, two timeouts, one
capture), and the collapse fails in both directions: the customer-facing state churns with
provider noise, and the provider evidence loses which try produced which reference. Two
aggregates keep each question answerable from its own row — and the attempt is where every
provider reference, evidence row and idempotency reference lives, which is what Phase 8
reconciles on.

The machines follow ADR-0044 rather than the conventional rich list because the discipline
transfers unchanged: a state nothing can produce is a branch somebody eventually writes
code for, and a state that cannot be durably observed is a comment. What changes is which
states *earn* their place — `PROCESSING`, `CANCELLED`, the dispatched and unknown states
all have producers here that transfers structurally lacked, which is exactly why ADR-0044
named Phase 5 as their home.

## Consequences

- The status endpoints answer from the intent for customers and from the attempt for
  operators/reconciliation; neither is a projection of the other — they are different
  questions.
- Every machine is enforced three ways (aggregate sweep, generated schema `CHECK` +
  transition trigger, append-only history) — the established ceremony.
- The intent's `SUCCEEDED` having no outgoing edge means refund views join the refund rows;
  the API shape must render refund totals from them.
- `FAILED` carries a mapped, enumerated reason (`INV-PAY-03`); the provider's own code
  lives only in retained evidence.

## Alternatives rejected

- **One payment entity with a rich state list** — collapses two questions into one row
  (above), and most of its states would be fictions here.
- **A single generic `UNKNOWN`** — needs a side channel to say what is unknown; the machine
  should carry its own semantics.
- **Refund state on the intent** — one fact in two places; the refund rows are the record.
- **Automatic retries / multiple live attempts now** — a retry policy with nothing to
  calibrate it, and routing without a second provider to route to.

## Follow-ups

- `P5-TSK-006/-007` implement the machines; `P5-TSK-008` generates the schema from them.
- Phase 6 revisits `VOIDED` with checkout expiry; Phase 7 revisits multi-attempt with
  routing; Phase 8 attaches settlement facts to captures without touching these machines.
