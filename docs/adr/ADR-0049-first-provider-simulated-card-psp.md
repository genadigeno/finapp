# ADR-0049 — The first provider is a simulated card-style PSP, and nothing is final before settlement

Status: Proposed
Date: 2026-09-20
Phase: 5
Context: Payments
Supersedes: nothing. Closes unresolved architectural question 9 (Medium), open since
initiation: *"Which payment rail to simulate first, and its finality semantics."*

## Context

The question's recorded risk is that **the first rail shapes the abstraction**: a port
designed around one provider's shape quietly becomes that provider's SPI, and every later
rail is bent to fit it. The recorded mitigation is to design to `PAYMENT_LIFECYCLES.md`'s
distinctions rather than to any provider — which requires the first rail to *exercise*
those distinctions, not merely tolerate them. The platform's standing non-goal applies:
no real connectivity, ever; all providers are simulated behind adapters
(`ROADMAP.md` §Non-Goals, ADR-0008, the `P0-TSK-037` harness).

## Decision

1. **The first provider is a simulated card-style PSP** — an adapter speaking a
   Stripe-shaped HTTP vocabulary (authorize / capture / refund / query, signed timestamped
   webhooks, request idempotency keys) against the `SimulatedProvider` harness in tests and
   a deployable simulated endpoint for the running instance, exactly as Phase 2's
   verification providers are built.

2. **Its finality semantics, stated per operation**: an authorization is **revocable** (it
   expires issuer-side; never final); a capture is **reversible** (by refund, bounded by
   `INV-PAY-05`); **nothing is final until settlement**, which Phase 5 never records
   (`INV-SET-01` — the clearing balance stays open until Phase 8 reconciles it). Chargeback
   exposure — capture reversible *against* the platform — is Phase 7's lifecycle; Phase 5
   preserves its evidence and identifiers, nothing more.

3. **`INV-REV-03` (reversal on an irrevocable rail is rejected) has no subject in Phase 5**,
   stated rather than left to be discovered: the card-style rail is revocable at every
   Phase 5 stage. The rail capability model that gives the invariant its subject arrives
   with the second rail (Phase 7), which the gate there already requires to have
   *materially different* finality semantics.

4. **The port is one provider wide, and the rail abstraction is deliberately not built.**
   `PaymentProvider` (the port) carries the operations, our vocabulary in and out
   (`INV-PAY-03`), the query-by-reference contract (ADR-0046) and the idempotency-reference
   contract (`INV-PAY-04`). Multi-rail routing, capability declaration and per-rail
   finality modelling are Phase 7's, and building them against a sample of one is how the
   sample becomes the design.

## Why

**Card-style auth/capture is the maximal exercise of the lifecycle distinctions.** It is
the one common rail where authorization and capture are genuinely separate acts with
separate provider interactions, separate ambiguity windows and separate accounting
consequences (ADR-0048) — so it forces the intent/attempt split, both `*_UNKNOWN` states,
the webhook machinery and the refund bound to all be real. An A2A-style push rail — the
plausible alternative — has no authorization step: it would let Phase 5 ship a thinner port
that collapses auth-and-capture into "execute", and Phase 7 would then be *widening* the
abstraction under two rails' pressure instead of specialising it, which is the exact
first-rail trap the question warns about. Simpler rails are subsets of this one; the
reverse is not true.

**Simulated, because the design pressure is the point and the risk is not.** The harness
already reproduces every failure mode the phase exists to handle — timeout, 5xx, malformed,
unknown state, received-before-lost-response, duplicate and late webhooks — which is
precisely the behaviour a sandbox of a real PSP is worst at producing on demand.

## Consequences

- The simulated provider's vocabulary is itself treated as foreign: it exists only in the
  adapter and the retained evidence, so swapping in a second provider shape is an adapter,
  not a domain change — the property `INV-PAY-03`'s boundary test holds.
- The deployable simulated endpoint gives the running instance something to call, so the
  meters, the sweeper and the dashboard row are observable live (`DOD-OBS`), not only in
  tests.
- Phase 7's rail abstraction starts from two data points (this rail and its first
  contrasting one), which is the smallest honest sample.
- The provider's API credential and webhook key are the credentials the
  confinement-generalisation task (`P5-TSK-002`) generalises over.

## Alternatives rejected

- **An A2A/instant push rail first** — no authorization step, so the port ships too thin
  and the first-rail trap closes (above).
- **Two providers in Phase 5** — doubles adapter work to buy an abstraction Phase 7 owns,
  against the one-variable-at-a-time reason Phase 5 exists.
- **A real PSP sandbox** — violates the programme's standing non-goal, and sandboxes
  produce the interesting failures neither on demand nor deterministically.

## Follow-ups

- `P5-TSK-003` builds the port and adapter with contract tests over the harness;
  `P5-TSK-011`'s surface and `P5-TSK-017`'s meters carry the per-provider tags.
- Phase 7 writes the rail-abstraction ADR against two rails and gives `INV-REV-03` its
  subject.
