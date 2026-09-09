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

### Single-instance assumptions
ADR-0014 made multi-instance execution a design rule, and design rules decay - the audit that
produced it found a real defect in reviewed code, where a lease was judged against two instances'
clocks and could produce two financial effects for one request. Four patterns now fail the build:
`synchronized` (method **and** block), process-local locks, ambient scheduling, and static mutable
state. Each means something only within one process, so its presence is a claim about coordination
that is false the moment a second instance starts - and the code reads as though the race was
handled, which is why it survives review. The exemption set is `DISTRIBUTED_EXECUTION.md` §3, named
individually rather than by type. The block check is not an ArchUnit rule: ArchUnit models accesses,
a block is a `MONITORENTER` instruction, so that one reads bytecode. **The limit is recorded**: the
defect that motivated all this used none of the four, so the rules narrow the ways to be wrong
rather than closing them. &rarr;
[ADR-0024](../adr/ADR-0024-single-instance-assumptions-fail-the-build.md), ADR-0014

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

**Both halves were tested by a real case in `P1-TSK-029` and both held.** The tag allow-list met a
key that would have satisfied the rule — `stage`, a bounded compile-time set — and was **not**
widened, because a naming existed that needed no widening and the list exists to make such an
addition an explicit decision rather than an autocomplete. And the naming pattern refused three
meter names the Phase 1 plan had written with **underscores**: the plan was corrected rather than
the convention relaxed, since Micrometer translates dots to the backend's idiom and both forms
produce the identical Prometheus series. **A convention that costs nothing to obey is one there is
never a reason to bend.**

That task also found the property none of this covered: `MeterRegistry.counter(...)` creates a meter
on first use, so every counter here published **no series at all** until its flow had run — and an
alert on a rate has nothing to evaluate at the moment it is needed. Counters are registered at
construction now, and `PlannedMetersExistTest` boots a context and runs nothing, so it can only pass
against that.

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

### Supply chain
Every artefact the build resolves is checksum-verified (`gradle/verification-metadata.xml`) and
version-locked (a `gradle.lockfile` per project), both enforced by Gradle on every build and both proven by
mutation. They are **not** redundant, and measuring showed why: verification recorded **69 of 342
modules at more than one version** on its first generation, because different classpaths legitimately
resolve different versions - so it cannot tell a deliberate resolution from drift between versions it
already trusts. The lockfile can, and it is the only place the thirteen BOM-managed versions are
written down at all. **The limit is stated rather than glossed: this is trust on first use.** The
checksums record what was downloaded when they were written, so they catch a later substitution and
not a first download that was already compromised. Signature verification is the answer to that and
was measured - one narrow slice produced 11 signed artefacts and 49 trusted keys - then deferred to
Phase 15, because a keyring is a trust decision of its own and every unsigned artefact still needs a
checksum. &rarr;
[ADR-0025](../adr/ADR-0025-dependency-verification-and-locking.md), [`README.md`](../../README.md) §7a

### Keeping pins fresh
Pinning removes one risk and creates another: a SHA cannot be repointed, and it also freezes the
thing, so without an update path the pins rot and a fix is never picked up. Dependabot proposes
updates for what it can read - the SHA-pinned actions and the version catalogues - and a Gradle
pull request from it **will fail its own build**, which is correct rather than a misconfiguration:
a version bump leaves the verification metadata and lockfiles stale, and the alternative is a bot
with permission to write checksums. The two scanner digests stay in `infra/scanner-pins.sh` where
Dependabot cannot read them, because moving them into the workflow would undo the single definition
that lets a developer and CI run the identical image; they get a weekly check instead, which
distinguishes a **moved tag** - the attack pinning defends against - from **rot**. A pull-request
bot for those was rejected in favour of a check that could actually be **proven**, since this
repository has no remote and no workflow has ever run. &rarr;
[ADR-0026](../adr/ADR-0026-keeping-pins-fresh.md), ADR-0025

### Test tiers
A test's tier is **what it needs in order to run**, never what it proves. That is the only axis on
which membership can be decided mechanically, and it is the axis that matters for scheduling: a
tier mixing requirements produces a task that costs what its heaviest member costs and fails
wherever that member's infrastructure is absent. Four tiers — unit, architecture, slice, database
— each its own task. The default tier selects by **excluding** the others' tags rather than
including one of its own, so a test can never belong to no tier at all; that failure is silent,
which is the worst kind. `contract` is deliberately not a tier: it is a kind, and its members have
different requirements. Detection is one-directional and over-declaration is permitted, because a
`@SpringBootTest` reaching a database through the application's own pool has nothing in its
bytecode to detect. Splitting one task into four multiplies the ways to make the
`:platform:databaseTest` mistake, so the split ships with a guard holding the tiers, the tags, the
build and CI to each other. &rarr;
[ADR-0028](../adr/ADR-0028-test-tiers-by-requirement.md),
[`TESTING.md`](TESTING.md)

### Party, Customer and Identity
Three aggregates, in two modules, with three lifecycles — never one `users` table. Party is *who
exists*, Customer is *a role a Party plays toward the platform*, Identity is *a means of proving
presence*. The pressure to collapse comes from the simplest first story, and the cost arrives in
four places the collapsed model cannot represent: a person who is not a customer (a beneficial
owner), a customer who is not a person, a person whose login is retired and replaced, and staff —
who are Identities and never Customers. Unpicking it later means migrating identity data out of a
table financial records already reference, at which point `INV-HIST-01` forbids rewriting the
history that points at it. `identity` references `PartyId` **by value**: no cross-module foreign
key, because a database-level FK across a module boundary is coupling Gradle and ArchUnit cannot
see. &rarr; [ADR-0029](../adr/ADR-0029-party-customer-identity-are-three-aggregates.md)

### Sessions and assurance
Sessions are **server-side and authoritative in PostgreSQL**, so revocation is immediate by
construction on every instance (`INV-IDN-03`). A self-contained JWT was rejected on exactly that
point: validity is a property of the signature rather than of any current state, so every
revocation mitigation reintroduces the lookup the token was chosen to avoid — and "logout
everywhere" becomes a promise the architecture cannot keep. Redis was rejected as the *authority*:
a session store whose durability is weaker than the account it protects can resurrect a revoked
session after a restore, and no test on a healthy system finds that.
**Assurance is a level, not an MFA boolean.** Every real MFA bypass is a route that produces a
session a boolean says is fine; a level moves the check from every producer to every consumer, and
consumers are the ones with the requirement. &rarr;
[ADR-0030](../adr/ADR-0030-server-side-sessions-and-assurance-level.md)

### Authorization
Two checks, always both: **permission** at the boundary (may an actor of this kind do this at all?)
and **ownership** in the domain (may *this* actor do it to *this* resource?). Collapsing them is
the most common authorization defect in financial software — a customer with a legitimate
`transfer:create` permission uses it against someone else's account, every check passes, and
nothing is logged as a denial. Ownership is never checked at the boundary, because the boundary
knows only an identifier from the request and trusting that *is* the defect. RBAC rather than a
policy engine: a rules engine is right when policy changes faster than code, which is true for
Phase 13's **risk** decisions and not for authorization. Authorization stays in `identity` as a
recorded merge with a named split trigger, and `BOUNDED_CONTEXTS.md` context 2 is renamed so the
list stops omitting a concept `CLAUDE.md` forbids collapsing. &rarr;
[ADR-0031](../adr/ADR-0031-authorization-model.md)

**Implemented by `P1-TSK-020`** (the boundary half), and one part landed stronger than the ADR asked
for: *"a rule's absence is never a grant"* is enforced **twice** - refused at run time, and a
**build failure** if any handler in `com.finapp` declares nothing. Neither replaces the other, since
a static sweep cannot see a handler registered at run time and a runtime check cannot fail a build.
Permissions are resolved from authoritative state **per request**, never stamped on the session, so
a revoked role stops granting on the very next request rather than at session expiry. A denial is
audited **after** the security scope opens, because a refused privileged attempt is the only trace
an attacker leaves and the record must name the person rather than the platform.

**And by `P1-TSK-021`** (the ownership half), which **narrows the ADR's own recorded limit rather
than removing it**. *"No build rule closes this"* was right about the boundary rule and too broad
about everything else: `OwnershipIsScopedTest` fails the build when a persistence operation takes a
resource identifier and nobody has classified how ownership is established. It forces classification
and does not decide safety — the `MfaBypassPathsAreEnumeratedTest` shape, because *a list of tests
is a snapshot*. **Five correct statements look exactly like the defect** — an identifier from an
owner-constrained read is safe and one from a request is not, and in SQL they are indistinguishable
— so the rule classifies rather than forbids, since a rule with five false positives is one somebody
turns off.

### Credential storage
A credential row stores the derivation **and the algorithm and parameters that produced it**. The
decision usually missed is not which algorithm but where the parameters live: a global work factor
cannot be raised, because raising the setting changes only new credentials and nothing records what
the old ones used. The store becomes a mix of strengths with no way to find or upgrade the weak
ones. Argon2id via a vetted library, upgrade-on-use inside the verification transaction — the only
moment the platform legitimately holds the plaintext — and a dummy verification of equivalent cost
for an absent identity, because skipping the work turns response time into an account oracle.
&rarr; [ADR-0032](../adr/ADR-0032-credential-storage-and-rotation.md)

**Implemented by `P1-TSK-007`**, and one part landed stronger than the ADR asked for: `INV-IDN-01`
is enforced at **`DB-CONSTRAINT`**, not only at `DOMAIN` and `STATIC`. The derivation column refuses
a value that is not in its algorithm's encoded form, so a plaintext cannot physically be stored by
any writer - a migration, an operator, or code nobody has written yet. The measured cost is ~46 ms
per derivation at the shipped parameters, recorded rather than asserted, because a *stated*
verification time that nobody measured is not stated.

### Data access
Authoritative writes and aggregate loads use **explicit SQL through `JdbcClient`**. No ORM, no
persistence context, no generated repositories. The decisive argument is not taste: three of the
strongest invariants in the catalogue — `INV-HIST-03`, `INV-HIST-01`, `INV-LED-03` — are enforced
at `DB-PRIVILEGE` by the application role holding **no `UPDATE` and no `DELETE`**, and a privilege
model is worth exactly as much as the guarantee that nothing emits a statement nobody wrote.
Hibernate's dirty checking emits `UPDATE` on its own initiative, at a flush point decided by code
far from the write. Spring Data JDBC came far closer and was rejected on two concrete behaviours:
`save()` deletes and re-inserts child collections — against superseded credentials those children
*are* the history — and application-minted UUIDv7 identifiers make it default to `UPDATE` on a new
aggregate. Every concurrency protocol Phase 0 proved (claim-by-insert, bounded `lock_timeout`,
conditional `UPDATE … WHERE`, transaction-scoped advisory locks, savepoints, writing on the
caller's connection) stays expressible, and `DISTRIBUTED_EXECUTION.md` §3 gains no row. Transactions
are begun explicitly, because `@Transactional` fails *silently* on self-invocation. Enforced rather
than recorded: `NoObjectRelationalMapperTest` fails the build if an ORM artefact reaches the
application's runtime classpath — closing a gap where §6 had forbidden JPA in `sharedkernel` only,
which is not where anyone would add one. &rarr;
[ADR-0033](../adr/ADR-0033-explicit-sql-and-no-object-relational-mapper.md)

### Correlation identifiers
The platform **mints the correlation identifier on every request** and never adopts an inbound one.
That value reaches every log line, every span, four durable columns and every problem-detail body,
so accepting a caller's string let a caller write personal or financial data into systems with
different access control and months of retention (`INV-AUD-02`) — `jane.doe@example.com`,
`acct:GB29NWBK60161331926819`, `customer-1990-05-14` and `+447700900123` were all confirmed
accepted by probe. **Narrowing the charset was the obvious repair and does not work**: a date of
birth, a phone number and an account number are alphanumeric, and any charset still able to carry a
UUID or a W3C trace value carries them too, so a lexical control cannot express the property. A
well-formed caller value is echoed back in `X-Client-Correlation-Id` and reaches no sink; the client
keeps its join by logging the identifier we return. What is deliberately lost is the ability to
search our logs by a caller-chosen string — which is precisely the property that made the
disclosure possible. &rarr;
[ADR-0034](../adr/ADR-0034-the-platform-owns-the-correlation-identifier.md)

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
Eighty-two financial, security and operational invariants are catalogued with stable IDs,
enforcement mechanisms and verification methods. (This line said "seventy-one" until the
Phase 1 → 2 transition — stale since `INV-IDN-08` — and now derives its correction from the
catalogue's own index.) Phases declare the invariants they protect
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
