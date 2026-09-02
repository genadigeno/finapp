# Project Decisions

Human-readable index of important architectural decisions.

Detailed reasoning belongs in `docs/adr/` — see [`docs/adr/README.md`](../adr/README.md) for
the full index and the list of anticipated decisions.

---

## Accepted So Far

### Financial truth
Immutable double-entry journal postings are the authoritative financial record. Balances are
derived from postings; no balance is an independent authority. → [ADR-0002](../adr/ADR-0002-ledger-authoritative-record.md), [ADR-0009](../adr/ADR-0009-balance-as-projection.md)

### Architecture
Prefer modularity and clear domain ownership; do not create microservices by default. The
platform is built as a modular monolith over one PostgreSQL database, with boundaries
enforced mechanically so that extraction remains feasible. → [ADR-0001](../adr/ADR-0001-modular-monolith.md), [ADR-0006](../adr/ADR-0006-module-boundary-enforcement.md)

### Module boundaries
Each of the 28 bounded contexts maps to exactly one of 24 modules; a context is never split
across modules, because that would give its state two owners. Merges are deliberate and each
records the evidence that would trigger a split — starting merged is the reversible
direction, since splitting a module later is a package move whereas merging two modules that
have both grown authoritative state is not. → [ADR-0012](../adr/ADR-0012-context-to-module-mapping.md),
[`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)

### Distributed execution
Every service runs as N concurrent instances, and N is never 1. Authoritative state — uniqueness,
idempotency, limits, leases, workflow state, financial position — lives in the database, never in
process memory. Coordination that involves time uses the database's clock, because a lease
judged by two instances' clocks is a race against skew rather than a boundary. Process-local
mechanisms are permitted only where they are explicitly non-authoritative. This does not change
ADR-0001: one deployable is not one instance. &rarr;
[ADR-0014](../adr/ADR-0014-multi-instance-execution.md),
[`DISTRIBUTED_EXECUTION.md`](../architecture/DISTRIBUTED_EXECUTION.md)

### API evolution
The public HTTP contract is versioned in the path (`/v1`), applied once in the composition root so
no controller declares or forgets it. The number increments only for a change that breaks a client;
everything compatible happens inside the current version. The OpenAPI document is generated from
the running application on every build and compared byte for byte against the committed copy, so a
contract change cannot reach a client without a human seeing a build failure that names it — and
each difference is labelled breaking or compatible. Nothing about OpenAPI is deployed: the
generator is test-scope, and the contract is an artefact in git rather than a live endpoint. &rarr;
[ADR-0015](../adr/ADR-0015-api-versioning-and-contract-publication.md),
[`docs/api/openapi.json`](../api/openapi.json)

### Operability
Liveness and readiness answer different questions and must not share a health check. Liveness
depends on nothing external - a liveness probe that consulted the database would restart every
instance during a blip, converting a degradation into an outage and destroying the evidence.
Readiness includes PostgreSQL, checked through the pool the application actually uses, and
excludes Kafka and Redis because transport and cache are not truth. The application starts when
its database is unreachable, so it can report NOT_READY rather than crash-loop. Status is
published; detail is not, until there is an authority to authorize against. &rarr;
[ADR-0016](../adr/ADR-0016-health-liveness-and-readiness.md)

### Observability
Distributed tracing carries the flow's correlation identifier on every span, applied once by a span
processor rather than by each component - because "every span" is a property no per-component
discipline delivers, and forgetting is silent. A trace identifier never substitutes for a
correlation identifier: it is subject to sampling, and a sampled-out flow would become unfindable
from the one value a customer holds. Inbound W3C trace context is joined rather than replaced,
because a request crossing instances is the normal case. No JDBC tracing library and no statement
text on spans - SQL would carry amounts and account identifiers into a telemetry backend with
different access control (`INV-AUD-02`). Telemetry is never the record. &rarr;
[ADR-0017](../adr/ADR-0017-tracing-and-correlation-on-spans.md)

Metric names are a contract that outlives the code: every alert rule, dashboard and runbook written
against them lives outside this repository, so `finapp.<module>.<noun>` is enforced by the build
rather than documented. No tag value may come from a request - an identifier in a tag is both a
cardinality explosion and a disclosure with months of retention, and correlation is the one thing
deliberately kept off metrics because a metric answers how many, not which one. An unreadable
metric reports absent, never zero, because a zero silences the alert that should fire. &rarr;
[ADR-0018](../adr/ADR-0018-metric-naming-and-cardinality.md)

### Sensitive data
`INV-AUD-02` is the one invariant that specifies its own enforcement - default-deny redaction - and
that is a property of a build rule, not of a wrapper people must remember. A secret is held in
`Sensitive<T>`, whose every rendering path is a mask, and the build rejects any field or accessor
whose name says it holds a secret unless it is wrapped. Accessors as well as fields, because a
serialiser reads accessors. The vocabulary is narrow on purpose - an idempotency key is not a
secret - because a rule with false positives is a rule somebody turns off. Masked serialisation is
stated rather than inherited: Jackson happened not to reveal the value, and accidental safety ends
the day somebody adds a getter. &rarr;
[ADR-0019](../adr/ADR-0019-default-deny-redaction.md)

### Secrets
No credential value lives in this repository, and that is a build rule rather than a promise: a
credential-named key in committed configuration must be externalised or hold the one marked local
default, with the files discovered rather than listed. The CI secret scanner is a **net, not the
control** - probing it showed gitleaks catches a private key and misses `password: hunter2`, so
"scanning green" and "no secret in the repository" are different claims, and the two mechanisms
are blind in different directions. A name is not a control either: the marked local default is
published deliberately, so the application refuses to start when it is aimed at a database that is
not on loopback, closing the one documented way around externalised configuration - forgetting to
set the variable. Nothing is encrypted into git, because ciphertext in permanent history cannot be
rotated by deletion. A secrets manager is deferred to Phase 15, where there is a deployment to
shape it. &rarr;
[ADR-0020](../adr/ADR-0020-secret-management.md),
[`SECRET_MANAGEMENT.md`](../architecture/SECRET_MANAGEMENT.md)

### Security context
Who is acting travels with the flow in a `SecurityContext`, and **an unestablished actor is an
error rather than the system actor**. Defaulting would be convenient and correct today, and wrong
silently the moment Phase 1 lands: an authenticated request whose scope was never established would
record the platform as having done what a customer did - complete, plausible, about the wrong
party, and permanent under `INV-HIST-03`. Phase 0 says "the platform is acting" out loud through
`enterSystem()`, which is the greppable list of places Phase 1 must revisit, and reading
`Actor.SYSTEM` anywhere else fails the build. The actor is deliberately **not** merged into the
correlation context: a correlation identifier names one execution and attributes nothing to anybody,
while an actor names a party, and merging them would put a customer identifier into every log line
and span (`INV-AUD-02`). &rarr;
[ADR-0021](../adr/ADR-0021-security-context-and-the-absent-actor.md), ADR-0010

### Data classification
Five levels - public, internal, confidential, restricted-financial, restricted-PII - applied **per
column at its ceiling**: the most sensitive thing a column may ever hold, not what it holds today.
A column cannot be reclassified once it has data, because by then the handling it was given for its
whole life is already settled and may be in a log aggregator, an event stream or a backup. Phase 0
holds nothing sensitive, which is precisely why the scheme is written now. The register is compared
against the live schema in both directions, so a migration adding an unclassified column fails the
build - that guard, not the document, is what later data-model tasks actually meet. Handling rules
are referenced rather than restated, because a second copy drifts while looking authoritative.
&rarr; [ADR-0022](../adr/ADR-0022-data-classification-at-the-ceiling.md),
[`DATA_CLASSIFICATION.md`](../architecture/DATA_CLASSIFICATION.md)

### Transport and at-rest encryption
A database that is not on loopback must be reached with `sslmode=verify-full`, and the application
refuses to start otherwise. The default being closed is the driver's own: `sslmode` unset or
`prefer` **connects unencrypted and reports nothing** - measured, not assumed. `require` is not
enough, because it encrypts and verifies nothing, so it stops passive eavesdropping and not an
active attacker presenting their own certificate. Loopback is exempt, because a connection that
does not leave the host would otherwise cost every developer a certificate for a container.
Every configured source of `sslmode` must agree rather than trusting a measured precedence, since a
driver detail should not be what the control rests on. Kafka, Redis and inbound HTTP are documented
rather than guarded - there is no client for the first two and the application is never the TLS
endpoint. Nothing is encrypted at rest and nothing needs to be yet; the expectations and their
owning phases are recorded so the absence is a decision. &rarr;
[ADR-0023](../adr/ADR-0023-transport-security-confined-to-loopback.md),
[`SECURITY_ARCHITECTURE.md`](../architecture/SECURITY_ARCHITECTURE.md)

### Integration
External financial providers are accessed through adapters and treated as unreliable.
Provider vocabulary never enters the domain or a public API contract; unknown provider state
is a modelled state, never an assumed outcome. → [ADR-0008](../adr/ADR-0008-provider-adapters.md)

### State
Important financial lifecycles use explicit state machines. Invalid transitions are rejected
by the domain, not merely made unreachable through the API. → `INV-LIFE-01`, `INV-LIFE-02`

### Money representation
Monetary values are integer minor units with an explicit currency and a stored scale.
High-precision rates are a separate type; converting a rate result to money is an explicit,
named rounding step. → [ADR-0003](../adr/ADR-0003-monetary-representation.md)

### Identifiers
Aggregate identifiers are typed subclasses of `EntityId` carrying a UUIDv7 value. Passing one
aggregate's identifier where another's is required is a compile error, and two identifiers of
different kinds are never equal even with the same value. Time ordering is not cosmetic: a
random primary key turns the ledger's append-only write pattern into a random one. The shared
kernel holds the mechanism; each aggregate's identifier type belongs to its owning module.
&rarr; [ADR-0013](../adr/ADR-0013-typed-time-ordered-identifiers.md)

### Idempotency
Money-moving commands are made idempotent by a unique database constraint at the financial
boundary — never by a cache or an HTTP-layer filter. Key reuse with a different request is
rejected, not silently accepted. → [ADR-0004](../adr/ADR-0004-idempotency-strategy.md)

### Event publication
Domain facts and their publication records commit in the same transaction via a transactional
outbox; consumers deduplicate via an inbox. Kafka is transport, never the accounting source
of truth. → [ADR-0005](../adr/ADR-0005-transactional-outbox.md)

### Schema evolution
Database migrations are forward-only with no undo scripts: a mistake is corrected by a new
migration, structurally the same rule as correcting a financial error with a compensating
entry. Each schema-owning module owns its migrations and its own migration history table.
Migrations never run as a side effect of application startup. → [ADR-0011](../adr/ADR-0011-forward-only-migrations.md),
[`DATA_MIGRATIONS.md`](../architecture/DATA_MIGRATIONS.md)

### Audit
The audit trail is a dedicated append-only store, immutable at the database privilege level
and written in the same transaction as the action it records. Application logs are not an
audit trail. → [ADR-0010](../adr/ADR-0010-audit-trail.md)

### Delivery process
Work proceeds phase by phase through formal entry and exit gates with a defined status model.
A phase is not complete because it compiles. Future-phase functionality is not implemented;
where later capability is structurally needed earlier, the earlier phase defines a seam only.
→ [ADR-0007](../adr/ADR-0007-phase-gated-delivery.md), [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md)

### Invariant governance
Sixty-four financial, security and operational invariants are catalogued with stable IDs,
enforcement mechanisms and verification methods. Phases declare the invariants they protect
at the entry gate and prove them by test at the exit gate. →
[`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md)

---

## Deliberately Deferred

Recorded so these are not mistaken for oversights.

| Deferred | Until | Why |
|----------|-------|-----|
| Service extraction | Phase 16 | Requires measurement; anticipating it costs atomicity now (ADR-0001) |
| Kubernetes and Terraform | Phase 15 | Deployment topology should follow a proven architecture, not precede it |
| Real provider connectivity | Never | Adapters plus simulation give the same design pressure without the risk |
| Jurisdiction-specific compliance | Per phase | Jurisdiction-neutral core; specifics behind policy/configuration/adapters |
| Machine-learning risk models | Beyond scope | Versioned rules first; models add reproducibility burden without domain insight |
| Handling raw card data | Never | Tokenised at the boundary; PCI scope deliberately minimised |
| A secrets manager (Vault, cloud KMS) | Phase 15 | No deployment, no key material and one local database password. A manager chosen with no real requirement to shape it is the wrong manager; the seam - configuration read from the environment - is established now (ADR-0020) |
