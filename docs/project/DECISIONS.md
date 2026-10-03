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

### Boilerplate: Lombok, compile time only
Lombok is the project standard for Java boilerplate. It is `compileOnly` + `annotationProcessor`, wired once in the convention plugin from a catalog pin that matches the Spring Boot BOM, so it is on no runtime classpath (the lockfiles show it) and in no jar. The rule is [`.claude/rules/java-lombok.md`](../../.claude/rules/java-lombok.md); `lombok.config` is the half the compiler enforces:
- refused as compile errors: `@Data`, `@SneakyThrows`, `@Synchronized`, `val`/`var`, `@Cleanup`, experimental features and non-SLF4J loggers;
- a generated `toString` shows only fields explicitly included.

**Records stay first** for values. Aggregates, value objects, secrets and `Money` keep their hand-written construction and equality. Existing code was converted in nine module batches (`X-TSK-001`, 152 classes), each proved byte-equivalent with `javap` before it was committed. &rarr; [ADR-0055](../adr/ADR-0055-lombok-compile-time-boilerplate.md)

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

### Recovery channels
One **verified** contact channel per identity per kind, held by `V011`'s partial unique index
(`P1-TSK-023`). An unverified channel never blocks another, so a typo is not a lockout. **A second
verification is refused, not replaced** (`X-TSK-004`, `INV-IDN-06`). A channel is added with a
session alone, so a verification that displaced the verified channel would turn a stolen password
into a durable recovery route, with no step-up and no word to the address replaced. The refusal is
`409 identity.VerifiedChannelAlreadyExists` and writes nothing; the index is the arbiter, so ten
instances verifying at once verify one. Changing the verified channel is a flow of its own,
deferred below. &rarr; [`BACKLOG.md`](BACKLOG.md), `X-TSK-004`

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

### Verification decisions
**The KYC context owns the verification decision; Party projects it.** A decision is the
platform's own recorded act — immutable, attributable, reason-carrying, policy-pinned and
evidence-referencing — and `party.customer.status` is a *projection* of it, moved in the
decision's own transaction and never computed independently (`INV-KYC-05`). Two writable
authorities for *"is this party verified?"* would be the shared-mutable-ownership defect
`CLAUDE.md` forbids, on the field every later financial phase gates on. The mapping lives in
`app` because `kyc` cannot see `party`: the projection is precisely the cross-context fact an
orchestration exists to carry, and the module boundary is what keeps anything else from
computing it. &rarr;
[ADR-0035](../adr/ADR-0035-kyc-owns-the-verification-decision.md)

**A provider verdict is evidence, never the decision** (`INV-KYC-01`). Providers time out,
disagree and revise; the platform, not the vendor, answers to the regulator, so a decision that
*is* a provider's JSON cannot be defended, reproduced or reviewed. Provider answers are
normalised into our own vocabulary with the default branch `INDETERMINATE` and never success,
and the raw payload is retained verbatim (`INV-HIST-02`). **A screening hit is resolved by a
person, never by silence** (`INV-KYC-04`): a name match is a probability, silently cleared is a
sanctions breach and silently rejected is a person refused service by string similarity. There
is no edge from `IN_REVIEW` to a terminal state, and after three unknown answers the platform
**stops asking machines** and routes the question to a person. &rarr;
[ADR-0038](../adr/ADR-0038-provider-verdicts-are-evidence.md)

### Evidence and document storage
Verification evidence and document content are held **verbatim in PostgreSQL behind a port**,
encrypted with AES-256-GCM under a key held outside the database, checksummed at capture and
re-verified on every read. Object storage is deferred with a named trigger rather than adopted
speculatively, and `DocumentStore` is the seam. The two questions are separated deliberately:
**GCM answers *is this the ciphertext this key wrote*, and the checksum answers *are these the
bytes received*** — proven by substituting a ciphertext the same key genuinely wrote, which
only the checksum catches. Every read of content produces an audit record naming the actor,
because the threat a permission wall cannot answer is the *legitimate* reader (`INV-KYC-06`).
&rarr; [ADR-0036](../adr/ADR-0036-verification-evidence-and-document-storage.md)

### Consent
**Consent is an append-only history and the current basis is derived, never stored**
(`INV-CNS-02`). *"Was there a basis on the day it happened?"* is answerable only from history,
and an updated row has destroyed the evidence the question needs — so grants and withdrawals
are immutable facts, ordered by a server-assigned sequence rather than by any instance's clock,
and the derivation re-judges every read. **Absence and withdrawal are one answer to every
caller** (`INV-CNS-01`), and the gate reads authoritative state per decision with no cache
anywhere, so a withdrawal committed on one instance refuses on every other at its very next
decision (`INV-CNS-03`). A grant is bound to the version of the text it was given against
(`INV-CNS-04`): recording a grant against words the person may never have seen would write a
consent that consents to nothing. &rarr;
[ADR-0037](../adr/ADR-0037-consent-is-an-append-only-history.md)

### Posting concurrency
**`READ COMMITTED`, because postings are inserts.** A journal entry is appended; nothing is
updated; there is no row whose previous value must be read, so there is no lost update to have.
**`SERIALIZABLE` was rejected and that is the decision worth reading**: it would put a retry loop
around every money-moving command, and a retry loop around a money-moving command is exactly
where *"the database committed but the response was lost"* becomes two effects. The operations
that genuinely need mutual exclusion — a hold, an overdraft check, anything that reads a
balance and acts on it — are a small enumerable set, and each takes
`SELECT … FOR UPDATE` on the **account row**, stating the requirement at the site that has
it. A balance is never read-then-written; `balance = balance + amount` is the row whose
concurrent update is lost, which is why `INV-BAL-01` forbids it. &rarr;
[ADR-0039](../adr/ADR-0039-posting-concurrency-and-isolation.md)

### Chart of accounts
**A flat account with a typed classification, not a tree.** Roll-up is a `GROUP BY` over
attributes — type, purpose, a nullable `gl_code` for Phase 14 — rather than a walk over
ancestry. A hierarchy buys roll-up by ancestry and costs three things: recursive queries on the
reporting path, a second source of truth about classification free to disagree with the account's
own type, and re-parenting, which is reclassification and which `INV-LED-06` forbids once
postings exist — so the tree would be a structure that must never be edited. **One currency
per account, always**: a multi-currency account is a product concept, and at the ledger it is *n*
accounts. &rarr; [ADR-0040](../adr/ADR-0040-chart-of-accounts-structure.md)

### Balance projection
**Transactional, ledger-owned, and never the authority.** ADR-0009 settled that a balance is
derived; this settles where the derived copy lives and — the part that matters — **what
may read it**. It is updated in the posting's own transaction, so it is never *behind*; and
**no financial decision reads it**: a hold or an overdraft check derives its number from the
postings inside the account lock. An asynchronous projector is the standard answer and buys a lag
that `INV-BAL-05` then forces us to bound, monitor and exclude from every decision path —
three mechanisms and a metric to avoid one `UPDATE` in a transaction that is already open. The
cost is stated rather than hidden: every posting contends on its accounts' projection rows, and
the operational-account hot row has a recorded mitigation. &rarr;
[ADR-0041](../adr/ADR-0041-balance-projection-placement.md)

### The account model
**Customer Account, Ledger Account, Wallet and Operational Account are four things**, and the
boundary between the product and the accounting is the important one: a Customer Account is an
agreement with a lifecycle and **carries no balance**, while a Ledger Account is an accounting
position with a type, a normal balance and one currency. ADR-0029's test applied one layer down
— the four cases a collapsed model cannot represent are a product in two currencies, the
platform's own money (every customer credit is a platform liability, and a model with only
customer accounts cannot post anything), a closed product whose accounting history must survive
it, and a statement. `accounts` and `wallet` stay one module with a recorded split trigger.
&rarr; [ADR-0042](../adr/ADR-0042-account-model-four-distinct-concepts.md)

### Transfer execution
**The transfer and its posting commit in one local transaction, and no internal saga
exists.** Both legs are internal and both modules share one database (ADR-0001's principal
benefit), so the execution transaction holds everything — claim, transfer row, history,
journal entry, audit, outbox — and a crash leaves *nothing* rather than a half-transfer to
repair. A failed transfer is a **committed domain outcome** with its reason and no posting,
never an exception leak; "compensation" means the business reversal (a new referencing entry,
`INV-REV-01`, atomic with the state move) and nothing else, because no partial state exists
to compensate. The boundary at which this answer changes is named: an outcome a third party
decides is a *payment*, and Phase 5's lifecycle owns that shape — this ADR must not be
inherited by analogy. Closes unresolved question 5, open since initiation. &rarr;
[ADR-0043](../adr/ADR-0043-transfer-and-posting-commit-together.md)

**The transfer lifecycle is four states, and every state is earned by a producer**:
`INITIATED → {COMPLETED, FAILED}`, `COMPLETED → REVERSED`. The conventional rich machine was
rejected because ADR-0043 makes most of its states unobservable — a state that begins and
ends inside one uncommitted transaction is a comment wearing a status's clothes, and a state
no command can produce is a branch somebody eventually writes code for. `PROCESSING` belongs
to Phase 5's payments, `CANCELLED` to scheduled transfers, `VALIDATED`/`AUTHORIZED` to no
durable fact at all. `COMPLETED` is **stable, not terminal** — one outgoing edge, driven by
its own reversal command — a recorded reading of `INV-LIFE-04` chosen over the alternative
that stores "what happened to this transfer?" in two places free to disagree. The events
follow the machine: the terminal facts publish, and `TransferInitiated` does not, because it
would commit beside its own outcome. &rarr;
[ADR-0044](../adr/ADR-0044-transfer-lifecycle-states-are-earned.md)

### Payment execution
**The payment lifecycle is two aggregates and three machines, every state earned by a
producer** (ADR-0044's doctrine applied where its refused states finally have producers):
the intent (`REQUIRES_CONFIRMATION → {PROCESSING, CANCELLED}`, `PROCESSING → {SUCCEEDED,
FAILED}`) answers the customer's question; the attempt — with durable `*_DISPATCHED` and
`*_UNKNOWN` states — owns every provider interaction and reference; refunds are their own
bounded aggregate, never a state stored twice on the intent. One attempt per intent in
Phase 5, a schema built for N. →
[ADR-0045](../adr/ADR-0045-payment-intent-and-attempt.md)

**No transaction spans a provider call.** Atomicity with a third party is unavailable at
any price, so the discipline is dispatch-before-call: the operation's state and its
platform-minted provider idempotency reference commit **before** the provider is asked, the
call holds no connection, and the outcome — including the capture's posting — applies in a
second transaction through conditional transitions. Ambiguity commits `*_UNKNOWN`
(`INV-LIFE-03`), never an assumed failure — the single most expensive assumption in
payments, made structurally impossible rather than discouraged. Resolution is by query:
every instance sweeps, **no lease and no leader**, because queries are idempotent and the
conditional transition arbitrates. →
[ADR-0046](../adr/ADR-0046-no-transaction-spans-a-provider-call.md)

**Webhooks are authenticated before parsing, freshness-bounded, evidence-first and
order-blind.** Signature over the raw bytes per provider key with a signed timestamp
window (`INV-PAY-01`); verbatim evidence and inbox dedupe commit together before any state
effect; every effect is a conditional machine edge, so duplicates, out-of-order arrivals
and webhook-before-response are the same harmless race and the losers are evidence. An
authentic unmappable webhook is acknowledged with its evidence retained — alerting, not a
stalled provider queue, is the escalation. →
[ADR-0047](../adr/ADR-0047-webhook-ingestion.md)

**Authorization is a payment-domain fact; the ledger's first touch is capture.** An
authorization is the issuer's promise against the customer's external instrument — nothing
about our books has changed, so it posts nothing and holds nothing. Capture posts debit
`PSP_CLEARING` / credit wallet atomically with the state transition; the clearing balance
is continuously the captured-but-unsettled position (`INV-SET-01`). A refund holds the
wallet funds at dispatch inside the account lock and releases-and-posts on completion.
Closes unresolved question 6, open since initiation. →
[ADR-0048](../adr/ADR-0048-authorization-is-not-a-posting.md)

**The first provider is a simulated card-style PSP, and nothing is final before
settlement.** Card-style auth/capture is the maximal exercise of the lifecycle
distinctions — a simpler rail would ship a port too thin and close the first-rail trap.
Authorization is revocable, capture reversible by bounded refund, settlement never
recorded in Phase 5; `INV-REV-03` has no subject until the second rail (Phase 7). The
port stays one provider wide deliberately. Closes unresolved question 9. →
[ADR-0049](../adr/ADR-0049-first-provider-simulated-card-psp.md)

### Checkout and merchants
**The fee model: gross to the books, net to the merchant, in one entry.** A merchant-bound
capture posts, atomically with the attempt's `CAPTURED` transition, DR `SETTLEMENT_CLEARING` /
CR `MERCHANT_PAYABLE` for the gross and DR `MERCHANT_PAYABLE` / CR `FEE_REVENUE` for the fee.
Revenue is recognised at capture. The fee is computed once and the net derived by subtraction, so
no rounding residual exists to strand. The fee schedule version is chosen when the session opens
and pinned onto the payment, so nothing already offered is repriced. `payments` posts the lines it
is handed and knows no merchant. Closes unresolved question 8. →
[ADR-0050](../adr/ADR-0050-fee-model-gross-capture-net-payable.md)

**A payout is hold-then-dispatch on the payable, and nothing is final before settlement.** The
bound is judged inside the payable account's lock with every in-flight hold counted, so ten
concurrent payouts dispatch exactly the affordable set. Completion posts DR `MERCHANT_PAYABLE` /
CR `PAYOUT_CLEARING`, failure releases the hold, and `UNKNOWN` leaves it standing. →
[ADR-0051](../adr/ADR-0051-merchant-payout-accounting.md)

**A merchant authenticates with a scoped API key, and tenancy is in the statement.** The key is
hashed, shown once and revoked immediately. `ActorType.MERCHANT` is its own population, and every
record a merchant command writes names the key that acted. Every merchant-scoped read and write
carries the merchant derived from the key, idempotency claims included, and another tenant's row is
the same one refusal as a row that does not exist. →
[ADR-0052](../adr/ADR-0052-merchant-api-identity.md)

**The checkout session and the order are two aggregates: expiry gates new work, and landed money
always wins.** A session expires by a leaderless sweeper's conditional transition, never by a
filter. A capture that lands after expiry moves the session `EXPIRED → COMPLETED_LATE` and still
creates the order, never an automatic refund. `checkout` depends on `platform` alone, and the
orchestration lives in `app`. Closes unresolved question 7. →
[ADR-0053](../adr/ADR-0053-checkout-session-and-order.md)

**A merchant refund is funded by its net, and the only credit it extends is the fee the platform
keeps.** The refund holds on the payable what its composition will take; under `RETAINED` the
payable may end below zero by exactly the fee kept, and a negative payable refuses every payout. →
[ADR-0054](../adr/ADR-0054-merchant-refund-funded-by-its-net.md)

**A payout destination changes by two operators, a conditional step-up and a cancellable
cooling-off, and bank details never enter.** The proposer is refused as approver at the aggregate,
in the statement and by `CHECK`, and the refusal is itself a committed audit record. Approval pins
a cooling-off that the change can be withdrawn during; a leaderless sweep makes it effective.
Only an opaque provider reference and a four-character suffix are stored. Second subject of
`INV-AUD-04`. → [ADR-0056](../adr/ADR-0056-payout-destination-four-eyes.md)

**The payout dispatches behind a send permit, fails only on what it knows, and resolves by
query.** Every send is preceded by a committed permit, so the sweep concludes `NEVER_RECEIVED` only
past a positive bound, re-judged under the row lock. A refused connection fails a payout only on
its first send, and a takeover re-sends the stored reference to the destination it was bound to. →
[ADR-0057](../adr/ADR-0057-payout-dispatch-and-resolution.md)

**A sale that does not cover its fee is refused at the price.** The offer is priced when the
session opens, under the version it will carry, and `net <= 0` is `checkout.SaleBelowFee` with
nothing written and the key unspent; the pin re-asserts it, and a capture is never refused.
Decides ADR-0054's open item. → [ADR-0058](../adr/ADR-0058-a-sale-must-cover-its-fee.md)

### Rails, routing and disputes (Phase 7, `Proposed` at the Phase 6 → 7 transition; `Accepted` at the Phase 7 review, `P7-DOC-001`, each read against the code with its corrected passages marked in place)
**A payment rail declares its capabilities, and the domain acts on them, never on a rail's
name.** Three interaction models — two-step (the card rail), push (credit transfers) and book (the
platform's own wallet) — each with its own attempt machine; finality, reversal, refund mode,
outcome deadline, disputes and the clearing position are the descriptor's to declare. Internal
completion is never settlement; each external rail has its own clearing position. Card issuing is
external, and the wallet stays in `accounts`. Gives `INV-REV-03` its subject. A rail's money
semantics are frozen per rail name - a change is a new rail - so a payment is always read under
the semantics it was made under (ruled at the review). →
[ADR-0059](../adr/ADR-0059-payment-rails-capabilities-and-finality.md)

**Routing is a versioned policy, decided once per payment, pinned and explainable.** The decision
is born in the confirmation's first transaction with every rejected candidate and its reason;
Phase 7 has no cross-rail fallback after dispatch, and any future advance moves only on knowledge
that nothing was sent, never after an ambiguous dispatch; rail availability is a recorded
database fact, never an instance's opinion. The third
subject of `INV-HIST-04`. → [ADR-0060](../adr/ADR-0060-rail-routing-pinned-and-explainable.md)

**A dispute is its own lifecycle on a card payment, and a chargeback never takes more than the
capture credited.** Refunds and chargebacks together are bounded by the capture under the attempt
lock; the excess, and the share of a counterparty that can no longer take a posting, go to
`CHARGEBACK_RECOVERABLE`; each stage posts once under its own key, and a win mirrors its
chargeback exactly. Disputes are context 29, merged into `payments`. →
[ADR-0061](../adr/ADR-0061-disputes-and-chargeback-accounting.md)

**Account-to-account payments run on a provider-neutral push rail, and bank details never
enter.** External accounts arrive through the grant exchange as opaque references; an instant
payment is final on acceptance, with the scheme's outcome deadline, and settled on the scheme's
cycle; every outbound push carries the send permit; pay-by-bank waits in `AWAITING_PAYER` for the
payer PSP. The merchant payout keeps its own port. →
[ADR-0062](../adr/ADR-0062-account-to-account-and-instant-payments.md)

### Business stamps under N instances (cross-cutting, `Proposed` by `X-TSK-005`)
**The order of an aggregate's facts is the database's, and a business stamp never contradicts
it.** Stamps are still read from the acting instance's clock, but a later fact's stamp is the
later of that reading and the latest stamp the aggregate already carries. The twenty-one ordering
`CHECK`s therefore hold by construction on every instance instead of only while clocks agree, and
they stay as the rank against corrupt writers. Judgements of one instance's stamp by another's
clock (expiries, cooling-off, sweep bounds, session liveness) stay bounded-skew premises, each
dominated by its margin. The clamp removes the refusal that was a skewed instance's only symptom,
so clock offset needs its own signal. Implemented by `X-TSK-006` once accepted. →
[ADR-0063](../adr/ADR-0063-business-stamps-never-contradict-the-order-of-facts.md), ADR-0014

### Settlement and reconciliation (Phase 8, `Proposed` at the Phase 7 → 8 transition; `Accepted` at the Phase 8 review, `P8-DOC-001`, each read against the code with its corrected passages marked in place)
Built by `P8-TSK-001`…`P8-TSK-024`, `P8-TST-001` and `P8-TST-002`, and corrected by the review's
own `V015` (a break becomes `RESOLVED` only beside the approved resolution its closing event names)
and its reading of a gain's minimum age from the owning break's pinned rule set. ADR-0063 is
`X-TSK-005`'s (above), so the phase's numbering starts at ADR-0064.

**Settlement holds the external evidence; reconciliation holds the expectations, the comparison
and its outcome.** Contexts 13 and 14 are two modules, each depending only on `ledger`, `platform`
and `sharedkernel`, with no build edge between them; `app` composes them. The Settlement
Expectation moves from `settlement` to `reconciliation`, so `INV-REC-07` holds inside one schema.
Completions reach reconciliation through required ports that `payments` and `merchant` declare,
and four named seams join two modules' writes in one transaction: a completion opening its
expectation, acceptance handing a batch over, a payout return, and a batch repudiation. Neither
module writes a journal row, and no correctness rests on Kafka. →
[ADR-0064](../adr/ADR-0064-settlement-holds-evidence-reconciliation-holds-expectations.md)

**Each counterparty's clearing position is discharged in two evidence hops, and cash moves only
on the bank's statement.** Hop 1, an accepted counterparty report, posts only what the platform
had not recorded - the counterparty's fees, to `PROCESSING_COSTS` - and opens one `REMITTANCE`
expectation of the batch's net on that counterparty's own position; its transaction lines post
nothing and are allocated. Hop 2, an accepted bank statement, moves `CASH_AT_BANK` against each
attributed counterparty's position and parks an unattributed line in suspense with its break.
There is no in-transit account; a batch is recognised once, dated from stored facts, and reversed
only by a four-eyes repudiation; four purposes arrive, each with its first poster. A capture's
cleared amount and a second presentment, which Phase 7 keeps only in its retained evidence, are
this phase's clearing-level evidence. →
[ADR-0065](../adr/ADR-0065-clearing-discharged-in-two-evidence-hops.md)

**A settlement file is screened at the door, authenticated by a pull credential or a second
person's attestation, and retained encrypted in PostgreSQL behind a port.** The screen checks
every field against its declared class in memory, and a field failing its class is screened as
free text. A delivery carrying a card number or bank details is refused and keeps its metadata
only: for a refused delivery `INV-PAY-02` and `INV-RAIL-03` take precedence over `INV-HIST-02`. An
upload is inert until a second person attests it, and a readmission of an unattested original is
attested itself. Files rest under their own key, bound by associated data to file, source, content
and position, with every content read audited; ADR-0036 is re-assessed with named triggers for
object storage. Every pull URL joins `ProviderTransportGuard`: `https` or `sftp` off loopback, or
the application does not start. →
[ADR-0066](../adr/ADR-0066-settlement-file-screening-authentication-and-storage.md)

**Every externally settling completion opens its settlement expectation in its own
transaction.** The applier calls a required port past its acting exit, after the posting whose
clearing line the expectation copies, keyed on the rail's declared clearing purpose and never on
its name; the completion, its posting and its expectation commit together or not at all. A key
collision is recorded and raised as a break, never a failed payment. Keys are scoped per source; a
settlement cycle is an attribute of the expectation row, never a key; a payout return opens no key
of its own and is reached through its operation. Earlier history is adopted once by a keyed,
audited backfill, and a report-only verifier counts every clearing or suspense line that no
expectation, suspense item or Phase 8 record accounts for. →
[ADR-0067](../adr/ADR-0067-every-settling-completion-opens-its-expectation.md)

**Matching allocates by key in acceptance order under a pinned, versioned rule set, stores every
candidate it saw, and posts nothing of its own.** One pure decision function; claimants served in
`(source_sequence, line_no)` order on every instance, advisory namespace 4 ordering allocation and
PostgreSQL's uniques and deferred triggers arbitrating it; no fuzzy, subset-sum or learned
matching. A scheme line's reference resolves to exactly one internal explanation through Phase 7's
`payments.scheme_execution_claim`, for break typing only. Rule set v1, seeded and frozen per
source, makes the payout return's rule operation-anchored, so a return line waits for its return
instead of meeting its payout. A tolerance exists only for fees and dates - no amount tolerance can
be represented (`INV-REC-08`) - and a rule change governs only later decisions. →
[ADR-0068](../adr/ADR-0068-matching-strategy-rule-versioning-and-tolerance-model.md)

**A break is a classified, immutable record of a fact the platform detected.** Fourteen closed
types, each with its subject, value at issue, base severity and the resolution kinds it admits;
that per-type table is the one authority. Every suspense-owning type admits `WRITE_OFF` for a
DEBIT item and `RECOGNISE_GAIN` for a CREDIT item after the minimum age, except that
`REVERSAL_MISMATCH`, `REFUND_MISMATCH` and `CURRENCY_MISMATCH` admit no gain, and
`SETTLEMENT_MISMATCH`'s statement causes close only by evidence. A break is born only in the
transaction that detects its fact, one open per type and subject; severity is computed from the
pinned rule set and only rises; `EVIDENCED` is a stored resolution, never a silent clearing
(`INV-REC-02` amended); nothing is deleted or edited. →
[ADR-0069](../adr/ADR-0069-break-taxonomy-and-lifecycle.md)

**Value enters suspense only with the break that owns it, and leaves only by evidence, a
four-eyes resolution or a repudiation.** `SUSPENSE_UNMATCHED` stays one account per currency,
decomposed into items, each owned by exactly one break (`INV-REC-09`); CREDIT and DEBIT items are
never netted, and age runs from a stored `opened_on` on the database clock. An unclaimed credit
becomes a gain only after the pinned minimum age of 90 days, and only where the break's type admits
it. Phase 7's unmatched confirmations are adopted as suspense items keyed on each parking's
recorded cause and attempt (payments `V023`); an attributed parking resolves by a four-eyes
transfer to the named attempt's counterparty, never a guess. The balance is an audited report,
never a metric. Return-to-sender of unattributed funds is a new payment capability, deferred. →
[ADR-0070](../adr/ADR-0070-suspense-account-policy-and-ageing.md)

**A break closes only by evidence or by a template-bound, reason-coded resolution, and two people
decide whenever value is at issue or the resolution posts.** Eight kinds; the proposer chooses the
kind, a closed reason code and a narrative, and the lines are derived from the current remainder,
never typed. Posting kinds go through `ledger.AdjustmentService` as proposals of origin
`RECONCILIATION`, approver and proposer distinct at the domain, by `CHECK` and by ledger `V010`;
the generic adjustment door refuses them, and reconciled positions take no free adjustment. There
is no de-minimis band, and two pairwise-disjoint roles keep whoever loosens a tolerance from
resolving the breaks it would hide. A transfer is labelled on the merchant's breakdown and the
customer's statement by its entry's origin. A multi-entry approval pre-locks its ledger rows in
the projection's order (`PostingService.lockBalancesInOrder`, the transition's dispute repair). →
[ADR-0071](../adr/ADR-0071-break-resolution-authority-and-four-eyes-thresholds.md)

**Amounts never enter metrics: unmatched value, the suspense balance and provider costs are
audited operator reports.** No series carries an amount as a tag or as its value - ADR-0018 §2
widened from the tag to the sample. Five reports, each one audited `REPEATABLE READ` snapshot,
folded with `Money`, per currency and bounded. Value reaches alerting only as a break's severity
under the pinned rule set. The per-rail cost meter Phase 7 deferred is settled as the
`PROCESSING_COSTS` ledger fact plus the provider-costs report, never a meter (built by
`P8-TSK-024`, 2026-10-01). →
[ADR-0072](../adr/ADR-0072-amounts-never-enter-metrics.md)

**A payout return is a merchant fact applied from settlement evidence, and the payout stays
`COMPLETED`.** A leaderless worker re-reads the reported line's item under a share lock, then
applies the return under the payout row's lock: it posts DR `PAYOUT_CLEARING` / CR the payable,
keyed by the payout and dated from stored evidence, then inserts the append-only return, its money
held equal to the payout's by a composite foreign key, and opens its expectation in the same
transaction. A return that cannot apply - a merchant closed since, whose close closed its payable
account - writes nothing, and at grace parks as a `REVERSAL_MISMATCH` for a four-eyes transfer,
never a gain. ADR-0062 §7's second convergence trigger did not fire: the canonical settlement line
already gives every outbound credit transfer one evidence shape. →
[ADR-0073](../adr/ADR-0073-payout-return-applied-from-settlement-evidence.md)

### FX and cross-border payments (Phase 9, `Proposed` at the Phase 8 → 9 transition)
Planned by the Phase 8 → 9 transition (2026-10-02) from the Phase 9 design's thirty-two
decisions (D1–D32), to be built by `P9-TSK-001`…`P9-TSK-027`, `P9-TST-001` and `P9-TST-002`,
and read against the code and accepted by the Phase 9 review (`P9-DOC-001`). The phase's ADRs
are ADR-0074…ADR-0083 (ADR-0063 is `X-TSK-005`'s).

**Conversion arithmetic is exact, directional and bounded.** `ExchangeRate` lives in the shared
kernel as `NUMERIC(20,10)`, directional (units of destination per one unit of source), with no
inversion and no cross rates in arithmetic; an adapter refuses a provider rate past ten decimals
and never rounds one (D2). Both fixed sides exist — `FIXED_SOURCE` and `FIXED_DESTINATION`
— and the fixed-destination arithmetic is one exactly-rounded division (D3). Position legs
are the provider's stated amounts, accepted only if coherent, judged by cross-multiplication
(D4). Spread and markup post as one `FX_SPREAD_REVENUE` line whose attribution is stored (D5),
and the rounding residual is its own bounded line — |r| ≤ 1 under half policies, ≤ 2
under any named policy, `CHECK`-bounded — posted to `ROUNDING_RESIDUAL` in its own currency
(D6). JPY (0 minor units) and BHD (3) become postable, pinned by a test and a startup guard, and
the Phase 6 0/3-minor fee-batch deferral is paid in the same task (D27). →
[ADR-0074](../adr/ADR-0074-conversion-arithmetic.md)

**The rate chain is reference → provider → internal → customer →
executed → cover-executed, with every link stored.** The reference comes from an
independent source, is used for plausibility and disclosure only, is never executable, and fails
closed when stale (D8). Quote validity is computed from durations on the database clock, never
from a provider's absolute expiry (D9). Quote creation is keyed and two-transaction —
claim, then the provider call with no transaction open, then the insert — with the pricing
version pinned at the claim, failover recorded per candidate, and a live-quote cap arbitrated by
an every-writer trigger (D10). The quote machine has six states, no state without a producer,
and expiry is one event written by whichever conditional fires (D11). Pricing policy is
versioned and four-eyes with no seeded version (D26), and there is no conversion fee in Phase 9
— margin only (O10). → [ADR-0075](../adr/ADR-0075-the-rate-chain-and-the-quote.md)

**A quote is a frozen posting plan, and the platform is principal.** Every amount the trade will
post is computed once, at quote time, and frozen on the quote; execution posts the plan and
never re-prices (D7). The customer's conversion is booked when the quote is accepted, in one
local transaction with no provider call in it — acceptance, trade and posting commit
together (D1, D12). `FX_POSITION` stays ASSET/DEBIT with its sign defined, explained by open
legs and zero at rest; the FX books have one poster and accept no free adjustment, proven by the
FX books proof and plan verification. A wallet product holds one `CUSTOMER_WALLET` per currency,
every resolver keyed by currency, with open-if-absent inside the caller's transaction (D28). No
revaluation, no functional currency, no unrealised P&L — Phase 14 owns them (D30). →
[ADR-0076](../adr/ADR-0076-multi-currency-accounting-through-fx-position.md)

**The cover is decoupled, and never concluded "never received".** One back-to-back cover per
accepted quote, booked at acceptance and dispatched separately behind a database-stamped permit;
it is re-sent under the same reference until the provider knows of it, and only a definitive
rejection mints a new reference — and then only after a fresh firm quote has passed the
band (D13). The cover closes exactly the plan's position legs, with the difference posted as
realised FX result. No netting, no timing discretion, no limits: treasury stays out (D1).
→ [ADR-0077](../adr/ADR-0077-the-decoupled-cover.md)

**The new clearing positions are keyed by counterparty from birth.** `OwnerKind.COUNTERPARTY` is
added, `owner_ref` names a row of the seeded `ledger.counterparty` registry, and every
counterparty clearing account is seeded by migration below the UUIDv7 ceiling, never minted at
runtime; `INV-SET-05` and `INV-RAIL-04` are restated per counterparty, and the existing
operational clearings are untouched — the split trigger ADR-0062 recorded fires on
accounts that have no history (D18). A second provider of each kind arrives in M9.8, the first
cut if scope must shrink (D19). →
[ADR-0078](../adr/ADR-0078-counterparty-keyed-clearing-positions.md)

**A cross-border payment holds the customer's funds until the corridor provider accepts, posts
once, and fails debiting nothing.** `crossborder` decides, `fx` prices and books, `payments`
executes, `kyc` screens, with no build edge between them and every seam a port `app` implements
(D15). Money waits under a hold, and one entry — debit, conversion, fee, clearing credit
— posts at acceptance (D14). The Outbound Credit is a new `payments` aggregate carrying
the provider's ambiguity: ADR-0057's four states plus `RECEIVED` (D16); the payment machine
keeps `UNKNOWN` off the customer's object and admits a return even after delivery, because the
external fact comes first (D21). Cancellation is a recall request, honoured only on the
provider's definitive answer, and an instruction with a recall requested is never re-sent (D22).
A return is applied automatically only when it is exactly the instructed credit coming back,
credited in that currency and never re-converted, with the fee refunded and the spread standing;
every other return parks for a person whose four-eyes resolution also records the return on the
payment (D23, O2). The charge bearer is `OUR` only (O9). ADR-0062 §7's convergence trigger
is fired by the corridor rail and convergence is declined with reasons, the trigger re-recorded.
The registered name is Cross-Border Payment, never Transfer (D31). →
[ADR-0079](../adr/ADR-0079-cross-border-payments.md)

**The corridor rail declares only what is true, and the provider is selected twice because two
questions are asked.** `RefundMode.NONE` is the one new capability value — the rail
carries no pay-in, so a `PAY_IN` routing to it is refused — and the corridor's own facts
live in its `CorridorDeclaration`, with existing declarations unchanged (D17). A beneficiary is
held by provider reference with provider-attested country, currency and entity type; no account
identifier, and no name outside kyc's ciphertext (`INV-RAIL-03` restated). The tokenising
provider is chosen at beneficiary registration, recorded and recomputable; carriage is routed
per payment as ADR-0060's third subject with `destination_country` and stored per-candidate
reachability; routing policy v5 goes through ADR-0060's existing single-person door, by that
ADR's own decision — whether corridor-bearing routing activation should instead be
four-eyes is recorded here as an open owner question, and raising it would be a superseding
ADR of ADR-0060; and fees are per corridor, never per rail, so the price never varies with
the rail (D20, D26). →
[ADR-0080](../adr/ADR-0080-corridors-beneficiaries-and-selection.md)

**Counterparty screening is kyc's.** One screening authority, on the existing adapter and
credential; every outcome — `CLEAR` included — is a recorded decision carrying its
basis, policy version and time, never the provider's verdict alone; the compliance hold sits on
the beneficiary, before pricing, so a review lasting hours never sits behind a locked rate; a
hit, an indeterminate result or an unverified payee always meets a person, never auto-cleared or
auto-rejected; screening unavailable means the beneficiary is unpayable and nothing is held;
revocation works from every state with one identical response; and no new consent purpose is
minted — legal obligation and contract, recorded (D24, O4). →
[ADR-0081](../adr/ADR-0081-counterparty-screening-is-kycs.md)

**FX legs reconcile as single-currency expectations, and reconciliation still never converts.**
`FX_PROVIDER_REPORT` arrives; the corridor reuses the payout provider's source kind; the source
descriptor names its counterparty and its settled currencies, and a batch in a currency its
counterparty does not settle is rejected at the door, retained and never posted. Two new causes
under existing break types — `FX_LEG_DIFFERS` under `AMOUNT_MISMATCH` and
`VALUE_DATE_DIFFERS` under `TIMING_DIFFERENCE` — and no fifteenth type; a missing FX leg
whose paired leg settled escalates to CRITICAL (D29). No migration seeds a rule set: each new
source's version 1 goes through a four-eyes door, and a source without one refuses loudly with
a typed `RuleSetMissing` (D26). `FX_FEE` is a priced fee line, and every source carries a fee
schedule in every currency it can settle, the four existing sources through v2 successors (O6,
O7). No amount ever enters a metric: position, spread, residual and P&L are audited operator
reports (D32, ADR-0072 reaffirmed). →
[ADR-0082](../adr/ADR-0082-fx-and-corridor-settlement-and-reconciliation.md)

**Callbacks are hints.** ADR-0047's pipeline, amended for outbound money flows: authenticate,
retain evidence, dedupe through the inbox — then adopt the outcome only from an
authenticated inquiry over the outbound credential. It is the doctrine going forward, and
`X-TSK-015` (owner Phase 15, with an earlier trigger on a suspected key compromise) aligns the
Phase 5 and Phase 7 providers. It costs one provider call per callback, and buys immunity to a
stolen webhook key (D25, O5). →
[ADR-0083](../adr/ADR-0083-callbacks-are-hints.md)

**Owner decisions (O1–O10), settled at the transition and the owner's to revisit:**
principal, booked at acceptance, one back-to-back cover per accepted quote (O1); a return
applied automatically only as exactly the instructed credit, in its currency, fee refunded,
spread standing, anything else decided by a person (O2); `FIXED_SOURCE` and `FIXED_DESTINATION`
for both conversions and cross-border payments (O3); compliance review on the beneficiary,
before pricing, decided by kyc, unavailable meaning unpayable (O4); provider callbacks are
hints (O5); JPY and BHD as the new currencies, the four existing sources carrying their
per-currency rows through v2 successors (O6); the pricing and corridor policy v1 defaults
— spreads, bands, windows, notional bounds, corridors, fees and first rule sets, activated
four-eyes (O7); the cut order — M9.8's second providers first, then the operator FX trade
reversal, each recorded with Phase 15 as owner (O8); charge bearer `OUR` only (O9); no
conversion fee, margin only (O10).

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
One hundred and twenty financial, security and operational invariants are catalogued with stable
IDs, enforcement mechanisms and verification methods. (This line said "seventy-one" until the
Phase 1 → 2 transition — stale since `INV-IDN-08` — and "eighty-seven" from the Phase 4 → 5
transition until the Phase 6 → 7 one, through two groups it never counted, and "one hundred and
one" until the Phase 7 → 8 transition catalogued nine more, and "one hundred and ten" until the
Phase 8 → 9 transition catalogued the ten FX and cross-border invariants; it takes its number
from the catalogue's own index.) Phases declare the invariants they protect
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
| Instant-payment recall requests; batch credit-transfer rails with return windows | A later payments phase, when a rail that needs them is added | A recall is a request the payee's PSP may refuse, days later - a new operation with its own lifecycle, never a reversal (ADR-0059's rejected alternative); a batch rail's return window is a second finality model. Neither exists in any Phase 7 rail (ADR-0062's follow-up; recorded here by the Phase 7 review). *(The recall half fired at the Phase 8 → 9 transition: the corridor rail is a rail that needs them — a customer cancellation after authorization is a recall request, honoured only on the provider's definitive answer — scheduled as `P9-TSK-024` (D22, ADR-0079 `Proposed`). The batch-rail return-window half stays open.)* |
| Moving the merchant payout onto the push rail | A second outbound rail | Two outbound disciplines coexist by design: the payout keeps its own port (ADR-0057) and the push rail can implement it in `app` without a `merchant` change (ADR-0062 §7). *(The row's second trigger, "Phase 8 needing one evidence shape for every outbound credit transfer", was evaluated at the Phase 7 → 8 transition and did not fire: the canonical settlement line already gives every outbound credit transfer one evidence shape, and converging would re-declare the payout's position and source (ADR-0073 §8, `Proposed`). The row stays open on its first trigger.)* *(The first trigger fired at the Phase 8 → 9 transition, when the corridor rail arrived, and convergence was declined with reasons: the canonical settlement line already gives every outbound credit transfer one evidence shape, and the payout's port carries a single-currency merchant flow Phase 9 does not touch (ADR-0079 §9, `Proposed`). The trigger is re-recorded as: a merchant payout in a currency other than the settlement currency, or on a rail other than `PayoutProvider`.)* |
| Automatic rail availability from observed failure rates | Phase 15 | Availability is an operator's recorded, audited fact read inside each decision; automation must write the same fact, never an instance's opinion (ADR-0060 §4 - "Phase 15 or 16" until the Phase 7 review settled one owner) |
| ~~A per-rail cost meter~~ | ~~Phase 8~~ — **settled 2026-10-01** by `P8-TSK-024` | Not a meter: the processor's fees post as `PROCESSING_COSTS` at the batch's recognition and are read per source and month in the audited provider-costs report (ADR-0060 §6, ADR-0072). Struck by the Phase 8 review |
| An equity account for a non-zero first bank opening | Phase 14 | A non-zero first opening raises `SETTLEMENT_MISMATCH(OPENING_BALANCE)` and posts nothing (O4, ADR-0065); recognising history the platform never posted is general-ledger close work, not reconciliation's (recorded by the Phase 8 review) |
| Return-to-sender, and a payout return to a closed merchant | Phase 15 | Residual value with no attributable owner stays in owned suspense, aged and alerted; returning it, or settling a returned payout against a closed merchant, is a new operation with its own consent - Phase 15 writes its operating procedure or schedules it by its own ADR (ADR-0070 §4, ADR-0073; owner named by the Phase 8 review) |
| Recovering a DEBIT suspense item from a customer or merchant | Phase 13 | A DEBIT item leaves today by late allocation, a correction offset, `OFFSET_SUSPENSE` or `WRITE_OFF`; collecting it from a counterparty is debt collection, beside chargeback debt's reserve and collection (owner named by the Phase 8 review) |
| Dispute-fee pass-through to merchants | A merchant-risk phase (Phase 13's neighbourhood) | The PSP's dispute fee posts to `DISPUTE_COSTS`; charging it on is a commercial term with its own consent and statement consequences (ADR-0061's follow-up; recorded here by the review, with reserves the Known Architectural Debt row in `CURRENT_STATE.md`). *(The Phase 7 → 8 transition adds the counterparties' processing costs to the same deferral: they post to `PROCESSING_COSTS`, and no price varies with the rail (ADR-0072 §6, `Proposed`).)* |
| A secrets manager (Vault, cloud KMS) | Phase 15 | No deployment, no key material and one local database password. A manager chosen with no real requirement to shape it is the wrong manager; the seam - configuration read from the environment - is established now (ADR-0020) |
| Changing the verified contact channel | Phase 15, with the notifier | A safe change needs a step-up, a notice to the channel being replaced and a cooling-off - `INV-IDN-06`'s own enforcement - and the notice needs the channel notifier Phase 15 brings. Until then a second verification is refused (`X-TSK-004`, §Recovery channels). Nothing delivers a challenge before that notifier either, so the refusal cannot yet strand a customer. **The flow must spend every pending challenge of the kind**: a refused verification writes nothing, so its challenge stays live until it expires, and a flow that freed the kind without spending them would let a parked challenge verify the moment the verified channel is gone |
| Revaluation, a functional or reporting currency, unrealised P&L and FX P&L reporting | Phase 14 | Covered positions are zero at rest, and realised results come only from slipped covers and unwinds; revaluing `FX_POSITION` before a reporting currency exists would be a plug, not a fact (D30, ADR-0076 `Proposed`; recorded by the Phase 8 → 9 transition) |
| Hedging, netting of covers, position limits, treasury, liquidity and prefunding | A treasury capability of its own; none scheduled (DELIVERY_PLAN §18) | The platform is principal with one back-to-back cover per accepted quote; netting would make the position unexplainable trade by trade (D1, ADR-0077 `Proposed`) |
| `SHA`/`BEN` charge bearers and correspondent-chain deductions | A corridor whose provider cannot guarantee the delivered amount | `OUR` only: the beneficiary receives the quoted destination amount, so a deduction from principal is an `AMOUNT_MISMATCH` break, never a silent short delivery (O9, ADR-0079 `Proposed`) |
| Merchant multi-currency settlement and cross-currency merchant fees | A merchant multi-currency phase of its own | `merchant.FeeCurrencyMismatch` and `PayoutCurrencyMismatch` stay; the corridor is a customer rail, and the merchant payout stays single-currency on its own provider (ADR-0050 annotated at the Phase 8 → 9 transition; ADR-0079 §9) |
| Funds owed to a closed customer by a parked corridor return | Phase 15 | There is no `ACTIVE` wallet to credit; the value stays parked with its HIGH break, aged and escalated, beside ADR-0070 §4's residual and the closed merchant's return (ADR-0079 `Proposed`) |
| Cross-border KYC tiers, residence attributes, velocity limits, transaction monitoring and risk scoring | Phase 13 | The `CrossBorderLimitCheck` and `CrossBorderRiskDecision` seams are required parameters from birth with reserved refusal codes; screening validity and static corridor limits are Phase 9's only compliance controls (ADR-0081 `Proposed`) |
