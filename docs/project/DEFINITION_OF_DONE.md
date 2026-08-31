# Definition of Done

The standard a change must satisfy before it is considered complete.

This document is binding. "It compiles", "it works locally", "tests pass" and "the demo
worked" are **not** definitions of done for financial software.

Scope: this applies to every change that touches domain, financial, security or platform
behaviour. Cosmetic changes (typos, formatting) are exempt and should be identified as such.

---

## 1. The Thirteen Dimensions

A significant change is done when every applicable dimension below is satisfied. If a
dimension is not applicable, that must be stated explicitly, not left silent.

### 1.1 Domain correctness
- The implemented concepts match `DOMAIN_MODEL.md`, and no distinction listed in
  `CLAUDE.md` §Domain Distinctions has been collapsed.
- The aggregate and bounded context that own the change are identified.
- The lifecycle/state machine is explicit, and invalid transitions are rejected by the
  domain, not merely unreachable through the UI or API.
- Domain language is used in names; framework or provider vocabulary does not appear in
  domain types.

### 1.2 Financial correctness
- No floating-point type appears in any monetary code path.
- Currency is explicit on every monetary value; there is no default or implied currency.
- Rounding is explicit, named and tested; no rounding residual is silently created or
  destroyed.
- Every journal entry balances, per currency.
- No balance is mutated without an authoritative financial record behind it.
- Every monetary change is explainable as:
  economic event → domain operation → financial transaction → journal entry →
  debit/credit lines → resulting balances → settlement → reconciliation.

### 1.3 Persistence
- The authoritative store for the changed state is identified and has exactly one owner.
- Invariants that can be enforced by database constraints are enforced there, not only in
  application code.
- Financial history is protected at the privilege level: posted entries and audit records
  cannot be updated or deleted by the application role.
- Migrations are forward-only and applied cleanly to an empty database.

### 1.4 Transaction boundaries
- The transaction boundary is explicit and deliberate, not an accident of annotation
  placement.
- State change and its outbox publication commit atomically.
- No transaction spans an external provider call.
- Where atomicity is impossible, a compensating path exists and is tested.

### 1.5 Consistency
- Every piece of state is classified as authoritative or derived; derived state is never
  written by anything other than its projector.
- Staleness semantics of any projection consumed by the change are documented.
- No two modules write the same authoritative state.

### 1.6 Idempotency
- Every money-moving command has an idempotency guarantee enforced at the financial
  boundary — a database constraint, not an in-memory cache or an HTTP-layer filter alone.
- Retrying with the same key produces one financial effect and an identical response.
- Reusing a key with a different request is rejected with a distinct, documented error.
- Inbound external events (webhooks, messages) are deduplicated.

### 1.7 Failure handling
- The change has been assessed against every applicable scenario in `CLAUDE.md`
  §Failure Engineering: timeout, client retry, commit-but-lost-response, crash, duplicate
  event, late or missing event, provider unavailable, provider unknown state, duplicate
  webhook, racing requests, late settlement, reconciliation break.
- Each applicable scenario has a test or a written, accepted rationale for why it does not
  apply.
- Timeouts, retries and backoff are explicit; operations that must never be blindly retried
  are identified.
- A provider timeout is never treated as a definitive failure.

### 1.8 Security
- Authentication and authorization are enforced for every new surface, and each has a
  passing **negative** test.
- Privileged financial and administrative actions require appropriate elevation; four-eyes
  where value or sensitivity warrants it.
- No secret, credential, token, PAN or unnecessary PII appears in source, configuration,
  logs, events or API responses.
- New data is classified, and handling matches its classification.
- All external input is treated as untrusted and validated at the boundary.
- No security control was weakened to make a test pass.

### 1.9 Audit
- Every privileged, financial or administrative action produces an audit record with actor,
  time, operation, target, reason (where applicable), correlation and outcome.
- Audit records are append-only and are not application logs.
- The action is registered in the auditable-action registry.

### 1.10 Reconciliation
- External evidence (raw request, response, webhook payload, provider reference, file,
  checksum) is retained wherever an external party is involved.
- Internal completion and external settlement are represented distinctly.
- If the change can produce a reconciliation break, the break is classifiable and the
  resolution path exists — or the owning phase for that work is named explicitly.
- No code path deletes or overwrites evidence.

### 1.11 Observability
- Metrics, traces and structured logs exist for the change's critical path.
- Correlation and causation identifiers propagate through logs, traces, events and audit.
- Failure and stuck states are detectable and alertable, not only visible in hindsight.
- Any invariant that can be continuously verified in production is.

### 1.12 Testing
- Tests exist at the appropriate tier; financial behaviour that depends on persistence or
  transaction boundaries has **integration** tests against real infrastructure.
- Every invariant the change touches has a test that **fails when the invariant is
  deliberately broken** — this must be demonstrated, not assumed.
- State machines are tested including invalid transitions.
- Concurrency, retry, duplicate delivery and failure paths are tested — not only the happy
  path.
- No test weakens a production security or financial control to pass.

### 1.13 Documentation
- Domain, architecture and lifecycle documentation matches the implementation, not the
  intention.
- An ADR exists for every architectural decision taken, in at least `Proposed` status.
- `CURRENT_STATE.md` reflects reality after the work.
- Anything knowingly deferred is recorded as architectural debt with the owning phase.

---

## 2. Task-Type DoD Profiles

The backlog references these profiles. Each profile is a subset of §1 — the dimensions that
must be satisfied for that kind of task. Profiles never *reduce* the requirement for a
change that also touches money: `DOD-FIN` always applies in addition where money is
involved.

### `DOD-BUILD` — build, tooling, infrastructure
1.3 (migrations), 1.8 (no secrets), 1.13. Plus: reproducible from a clean clone; CI green;
version pinning; no developer-machine-specific assumptions.

### `DOD-KERNEL` — platform/shared-kernel primitives
1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.11, 1.12, 1.13. Kernel components are held to the
strictest standard because every later defect inherits from them. Additionally: the
component's failure behaviour under crash and concurrency is proven by integration test.

### `DOD-DOMAIN` — domain logic without direct money movement
1.1, 1.5, 1.7, 1.8, 1.9, 1.11, 1.12, 1.13.

### `DOD-FIN` — anything affecting money, balances, settlement, fees, credit exposure or accounting
**All thirteen dimensions**, plus the financial-phase supplement in `PHASE_GATES.md` §3.
No exceptions and no partial application.

### `DOD-API` — API surface
1.1 (contract vocabulary), 1.6 (idempotency declaration), 1.7 (error and timeout
semantics), 1.8, 1.11, 1.12, 1.13. Plus: error contract conformance; backwards-compatible
evolution or a documented breaking-change decision.

### `DOD-EVENT` — events and messaging
1.4 (outbox atomicity), 1.5, 1.6 (consumer dedupe), 1.7 (duplicate, late, out-of-order,
replay), 1.11, 1.12, 1.13. Plus: full envelope populated; schema evolution compatibility
considered.

### `DOD-SEC` — security controls
1.8, 1.9, 1.11, 1.12, 1.13. Plus: negative tests for every control; no control bypassable
by a documented path; threat considered, not just feature implemented.

### `DOD-OBS` — observability
1.11, 1.12, 1.13. Plus: verified against a running instance, not only asserted in code.

### `DOD-TEST` — test tasks
1.12, 1.13. Plus: the test must be demonstrated to fail when the behaviour under test is
deliberately broken.

### `DOD-ARCH` — architecture decisions and boundaries
1.1, 1.3 (ownership), 1.4, 1.5, 1.13. Plus: an ADR; boundary enforced by an executable rule
where enforceable.

### `DOD-DOC` — documentation
1.13. Plus: accurate against the implementation at the time of writing; no aspirational
statements presented as current fact.

---

## 3. Anti-Patterns That Block Done

A change is **not** done if any of these is true. These are non-negotiable.

- A monetary value is held in `float` or `double` anywhere on its path.
- A balance is updated without a corresponding authoritative financial record.
- Financial history is edited, deleted or overwritten instead of reversed or adjusted.
- A journal entry can be posted that does not balance.
- A money-moving command's idempotency relies only on an application-layer cache or an HTTP
  filter.
- A webhook or event handler with financial side effects is not deduplicated.
- A provider timeout is treated as a definitive failure.
- Provider-specific state strings appear in the domain model or in a public API contract.
- A reconciliation break can be deleted, auto-resolved or silently suppressed.
- A privileged action produces no audit record.
- A test passes because a security or financial control was weakened for testing.
- A test would still pass if the invariant it claims to protect were removed.
- Documentation describes behaviour that does not exist.
- Two modules write the same authoritative state.
- `CURRENT_STATE.md` is stale.
