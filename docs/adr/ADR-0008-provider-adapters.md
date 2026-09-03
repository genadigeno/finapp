# ADR-0008 — External providers sit behind anti-corruption adapters

Status: Accepted

Date: 2026-08-31

## Context

The platform integrates with PSPs, processors, card networks, banks, credit bureaus,
sanctions-screening vendors, document-verification vendors and FX rate sources.

`CLAUDE.md` rule 9 states these are unreliable: slow, duplicated, inconsistent, late, or
unavailable. Rule: provider-specific details must not leak into the core domain.

The failure mode when this is ignored is well documented in practice: a provider's state
strings appear in the domain model, then in the database, then in the public API contract.
At that point the provider cannot be replaced, and the domain's vocabulary belongs to a
vendor.

## Decision

**Every external provider is accessed through an adapter implementing a domain-owned
interface.**

- The **domain** defines the interface in its own vocabulary. The adapter conforms to it.
  The interface is never shaped around a provider's API.
- Provider states are mapped to the internal lifecycle through an **explicit, testable
  mapping table**. Provider vocabulary never appears in the domain model or in a public API
  contract.
- An unrecognised provider state maps to a modelled indeterminate state, never to success or
  failure by assumption (`INV-LIFE-03`).
- Adapters retain **raw request/response evidence** — payload, provider reference,
  timestamps — because this is the material reconciliation and dispute resolution require
  (`INV-HIST-02`).
- **No transaction spans a provider call.** State is committed before the call; the outcome
  is applied in a separate transaction.
- Every adapter defines explicit timeouts, retry policy and backoff, and declares which
  operations may not be blindly retried.
- Every adapter is contract-tested against simulated failure: timeout, 5xx, malformed
  response, delayed response, duplicate callback, unknown state.
- Provider credentials live in secret management, never in source or committed config.
- No real provider connectivity in this platform. All providers are simulated; the adapter
  boundary is what makes that indistinguishable from the domain's perspective.

## Alternatives Considered

### Option A — Direct provider SDK use in domain services
Pros: Fastest to write; fewer layers.
Cons: Provider vocabulary contaminates the domain and, inevitably, the database and public
API. Provider replacement becomes a rewrite. Failure simulation requires mocking the SDK.
Explicitly forbidden by `CLAUDE.md`.

### Option B — Thin pass-through wrapper
Pros: Some indirection; small effort.
Cons: If the interface mirrors the provider's API, the abstraction is nominal. The second
provider forces a redesign of every caller — which is exactly when the abstraction was
supposed to pay off.

### Option C — Domain-owned interface with anti-corruption adapter (chosen)
Pros: Domain vocabulary stays the platform's own. Providers are substitutable. Failure modes
are simulated at a clean boundary. Provider-state mapping is explicit and testable — a table
that can be reviewed rather than logic that must be traced. Evidence retention has an
obvious home.
Cons: More code and an explicit mapping to maintain. Risk of designing an interface around
the first provider — mitigated by designing against the *domain lifecycle*
(`PAYMENT_LIFECYCLES.md`) rather than against any provider.

## Consequences

Positive:
- The domain remains expressed in domain language.
- Adding a second provider is additive, not a refactor.
- Provider failure modes are testable via a reusable WireMock harness (`P0-TSK-037`).
- Reconciliation evidence is captured by construction.

Negative:
- Additional layer per integration.
- State mapping must be maintained as providers evolve, and an unmapped state must fail
  loudly rather than default silently.
- Some provider capability is deliberately not exposed.

Operational impact: Per-provider metrics — success rate, latency, error rate, unknown-state
count and age — with alerting. Provider outage is an expected, handled condition.

Security impact: Credentials in secret management with rotation. Webhook signature
verification and replay-window enforcement at the adapter boundary. Tokenisation at the
boundary keeps raw card data out of the platform entirely.

Financial impact: Prevents the most expensive assumption in payments — treating a timeout as
a definitive failure. The adapter surfaces indeterminacy instead of resolving it by guess.

## Invariants / Constraints

`INV-LIFE-03`, `INV-HIST-02`, `INV-IDEM-04`, `INV-SET-01`.

## Follow-up

- ~~Phase 0: reusable WireMock failure-simulation harness.~~ **Delivered by `P0-TSK-037`**
  (2026-09-03): `SimulatedProvider` in `platform`'s test fixtures, with every mode named above
  reproduced through a real HTTP client and `ProviderFailureCoverageTest` holding this ADR,
  `CLAUDE.md` §Failure Engineering and the harness to each other. No separate ADR: this is this
  decision's follow-up rather than a new decision, and the design notes are in
  [`TESTING.md`](../project/TESTING.md) §5a.
- Phase 2: first real adapters (screening, document verification).
- Phase 5: PSP adapter; provider-state mapping tables; reconciliation-by-query sweeper for
  unknown states.
- Phase 7: multi-rail adapters with differing finality semantics.
