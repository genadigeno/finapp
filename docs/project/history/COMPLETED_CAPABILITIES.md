# Completed Capabilities

What each closed phase delivered, with the reasoning recorded at the time.

**Archive.** These records were moved verbatim out of
[`CURRENT_STATE.md`](../CURRENT_STATE.md) on 2026-09-20 so that the canonical description of
where the project *is* stops carrying the project's entire narrative of where it *has been*.
Nothing was edited, summarised or dropped in the move. Section headings below read as they did
when they were written.

Current state: [`CURRENT_STATE.md`](../CURRENT_STATE.md) ·
Authoritative backlog: [`BACKLOG.md`](../BACKLOG.md)

---

## Completed Capabilities

**Business capabilities: none** — by design (Phase 0 §2 of the delivery plan).

Platform foundation (2026-08-31), `P0-TSK-001`:
- Gradle 9.7.1 wrapper; distribution **and** wrapper jar verified by SHA-256 against
  `services.gradle.org`
- Version catalog as the single source of dependency versions
- Java 21 toolchain pinned via a `build-logic` convention plugin, with the foojay resolver
  provisioning a JDK where the machine lacks one
- Spring Boot 4.1.1 BOM; the Boot plugin applied only to the executable module, so library
  modules can take version alignment without `bootJar` packaging
- Placeholder `app` module carrying no business capability
- Reproducible archives; `-Werror`; LF enforced on `gradlew` so Linux CI is not broken by a
  Windows checkout

Module skeleton (2026-08-31), `P0-TSK-002`:
- `sharedkernel`, `platform` and `app` with the direction `app -> platform -> sharedkernel`,
  enforced structurally by Gradle and asserted by classpath tests
- `sharedkernel` is framework-free and provably carries no Spring artefact
- `java-library` everywhere, making `api`/`implementation` a deliberate boundary control
- No production code in either new module beyond boundary documentation

Local infrastructure (2026-08-31), `P0-TSK-003`:
- `compose.yaml` with PostgreSQL 18.6, Kafka 4.3.1 (KRaft) and Redis 8.10.1, pinned,
  health-checked, on named volumes, bound to loopback only
- Image versions single-sourced in the version catalog, with a build task that fails on
  drift between `compose.yaml` and the catalog

Migration tooling (2026-08-31), `P0-TSK-005`:
- Flyway 12.4.0, pinned to the Spring Boot BOM version, applied to the `platform` module
- Forward-only migrations with module-owned schema history (ADR-0011)
- `V001` creates the `platform` schema, documents its ownership, and revokes `PUBLIC`
- Migration conventions documented, including irreversible financial migrations and the
  privilege model the DB-level invariants require

Continuous integration (2026-08-31), `P0-TSK-004`:
- Four gates on every change: build/tests/boundary checks, migrations against a real
  PostgreSQL, secret scan over full git history, dependency scan of a CycloneDX SBOM
- Every third-party action pinned to a commit SHA; both scanners pinned to image digests
- The Java version CI installs is read from the version catalog rather than duplicated
- The `migrations` job starts PostgreSQL from the project's own `compose.yaml`, so CI and a
  developer run the identical pinned image

Architecture baseline (2026-08-31), `P0-TSK-006`:
- Context-to-module map: all 28 bounded contexts mapped to 24 modules, merges justified with
  recorded split triggers (ADR-0012)
- Single ownership verified by script over the register, not by reading it
- Module register with all nine `CLAUDE.md` boundary attributes for every module
- Authoritative-state ownership table proving no state has two owners

Boundary enforcement (2026-08-31), `P0-TSK-007`:
- Six ArchUnit rules on every build: dependency direction (defence in depth over Gradle),
  framework leakage into `sharedkernel`, cross-module internal access, cross-module entity
  references
- Each proven by a deliberate violation, then reverted
- A guard test asserting the analysis actually sees production classes, so the suite cannot
  become silently vacuous

Monetary type enforcement (2026-09-01), `P0-TSK-008`:
- `INV-MON-01` enforced statically on every build: no `float`, `double`, `Float` or `Double` in
  any field, signature, call target or field access in production code
- Scoped default-deny over every class rather than by a list of financial packages, with an
  empty, named exemption set
- Each rule proven to reject its violation and accept clean code on every build, and the whole
  sweep proven end to end by a `double` planted in `Money`, a `float` in a signature, and a
  `Double.parseDouble` call — each in a different module
- Coverage derived from the classpath by a shared `ProductionModules` helper, so a module that
  stops being analysed fails the build rather than silently losing its protection

Developer documentation (2026-09-01), `P0-DOC-001`:
- `README.md`: prerequisites, build, test, infrastructure lifecycle, migrations, CI gates and
  the failures this stack actually produces
- Every command verified from a clean clone, with infrastructure stopped first so the hermetic
  build claim was tested rather than assumed
- States the CI limitation rather than implying green

Architecture documentation (2026-09-01), `P0-DOC-002`:
- `MODULE_ARCHITECTURE.md` §6 names the rule behind every claim of mechanical enforcement
- `ArchitectureRulesAreDocumentedTest` fails the build when the document and the enforced rule
  set stop agreeing, in either direction
- The document is a declared input of `:app:test`, so a doc-only edit re-runs the check

Financial kernel (2026-08-31), `P0-TSK-009`:
- `Money`: integer minor units, explicit `CurrencyCode`, stored scale (ADR-0003)
- Exact arithmetic only — cross-currency, cross-scale and overflow all rejected with distinct
  domain exceptions under one `MonetaryException` supertype
- `CurrencyCode` validates against ISO 4217 and rejects codes with no minor unit
- No floating point anywhere on the monetary path

Correlation propagation (2026-09-01), `P0-TST-003`:
- One request's identifier proven identical in the log and in a committed database row, across
  a thread handoff, for both an accepted and a generated identifier
- A negative control asserting an unwrapped handoff loses it, so the test cannot pass by accident
- `CorrelationSinkCoverageTest` fails the build when a new platform concern appears without a
  decision about whether correlation must reach it

Domain glossary (2026-09-03), `P0-DOC-011`:
- [`GLOSSARY.md`](../domain/GLOSSARY.md): 62 terms, each with an `Is:`, a `Not:` and the module
  that will own it; all eight `CLAUDE.md` distinction groups contrasted
- The two lists **disagree**, found by comparing them mechanically: seven terms are forbidden from
  being collapsed that the canonical list never names, so the glossary is the **union** of both
- `DomainGlossaryTest` enforces it in both directions, so the glossary cannot become a second home
  for vocabulary its owning document should define
- `external` is an owner rather than a blank - modelling a PSP as our own state is the first step
  towards a domain that belongs to a vendor (ADR-0008)
- Nothing in it is implemented, and it says so: no production class is named for any of the terms
- `Installment` corrected to `Instalment` in the canonical list, against twenty uses elsewhere
- Nine mutations caught; review found `Risk Score` contradicting the module register, and added
  guards for that and for every `INV-*` citation

Reinstatement — the other half of suspension (2026-09-09), `P1-TSK-032`:
- `DELETE /v1/identities/{id}/suspension` moves `SUSPENDED` back to `ACTIVE`; suspension stops
  being a one-way door whose remedy was an operator with database access
- **The acceptance is driven end to end with a registered person** — a fixture row without a
  credential could never authenticate, so the claim would have been unreachable. Suspend → login
  refused → reinstate → login succeeds, **and the pre-suspension session stays dead**
  (`INV-HIST-01`: revocations do not un-happen)
- **Both self-refusal decisions revisited together**: self-suspension stays refused because the
  door is two-way only when a second administrator exists, which nothing guarantees; the `SELF`
  branch on reinstatement is nearly unreachable and kept for the no-self-loop trail property
- **One permission for both directions** (`IDENTITY_SUSPEND`), the `ROLE_ASSIGN` "grant or revoke"
  shape; the reason travels in a `DELETE` body because free prose must not reach access logs
- **`NOT_SUSPENDED` named for what is checked** — `CLOSED` lands there too, stays closed
  (`INV-LIFE-04`), and the conditional plus the aggregate each refuse it independently
- Audited as `identity.IdentityReinstated` (reason required), announced on the outbox, and one
  transition under ten concurrent instances

A person's own profile (2026-09-08), `P1-TSK-030`:
- `GET /v1/me` and `PATCH /v1/me` - declared by the plan for the whole phase and owned by no task
  until the review found them, which was the eighth backlog defect of that class in Phase 1
- **Ownership is enforced by there being no parameter**: no path variable, no query parameter, no
  body field naming a party. An attacker cannot name a victim, so the usual negative test is
  impossible to write and the test proves the **resolution chain** instead
- **The catalogue description promised what the classification forbids** - before/after are display
  names (`RESTRICTED-PII`) and `change_summary` is `RESTRICTED-FINANCIAL`, which are **peers, not a
  hierarchy**. The record names the field, never the value; the description was corrected
- **`AUTHORITATIVE_ID` was tried and the guard refused it**, because the read in the chain is
  classified `ADMINISTERED`. A sixth class, `SESSION_DERIVED`, records what is true - the identifier
  comes from a proven `Session` held in memory, `P1-TSK-021`'s recorded uncheckable case
- **Two guards were written to break on this day and both did** - `party` owning no ownership
  surface, and *"the grant arrives with the capability"*
- **`V004` grants `UPDATE (display_name)` and nothing else**, so `kind` and `registered_at` stay
  unwritable. Column-level grants are what `P0-TST-007` found can widen a privilege invisibly, used
  here to narrow
- **A no-op rename succeeds and writes no audit record** - an entry reading *"changed from Ada to
  Ada"* is noise, and would let anybody pad the trail

The two administrative endpoints (2026-09-08), `P1-TSK-028`:
- `POST /v1/identities/{id}/suspension` and `POST /v1/identities/{id}/roles` - the only two
  endpoints in the phase behind `@RequiresPermission`, and the two the plan listed that nobody owned
- **Suspension did not suspend anybody, and that was the blocking finding.** The session lookup
  filters on the SESSION's status and never joins the identity, so a suspension stopped the next
  login and left a live session working until its absolute bound expired. Every session is now
  revoked in the same transaction, asserted with the **same token** across the suspension
- **Ownership is inverted**: the subject must NOT be the actor, which no boundary annotation can
  express - it is static per handler and knows nothing about which identity the path names
- **What refusing self-elevation buys is stated honestly**: not containment, since an administrator
  can escalate through a second account, but that the trail **never holds a self-loop** - every
  escalation names two parties
- **`OwnershipIsScopedTest` gained `ADMINISTERED`**, because this task broke the assumption that an
  `IdentityId` parameter is always the caller's own; and a pre-existing blind spot was closed, where
  a statement built from a table-name **constant** was invisible to the owner check
- **The first administrator is created out of band** (`README.md` §5e) - a bootstrap endpoint would
  be a privileged surface with nothing in front of it, and a seeded migration row would put an
  administrator into production for ever. That first grant has **no actor in the audit trail**
- **A mutation found a test passing for the wrong reason**: `UUID.randomUUID()` is v4 and
  `IdentityId.of` validates v7, so the "unknown identity" was refused as *malformed* and never
  reached the service
- **Recorded rather than built**: reinstatement (`P1-TSK-032`) - suspension is currently a one-way
  door, which is also what makes refusing self-suspension correct rather than merely tidy

Registration takes a credential (2026-09-08), `P1-TSK-026`:
- **A person who registers can now log in.** `P1-TSK-006` left an Identity that could never
  authenticate, recorded as its own remainder because `P1-TSK-007` had not landed
- **Asserted end to end**: register over HTTP, authenticate over HTTP with that password, use the
  token on `GET /v1/sessions` - nothing inserted by the test, with a fabricated password as the
  negative control. *"A credential row exists"* would have passed against one stored under the wrong
  identity or a status nothing can verify, which the `SUPERSEDED` mutation proves
- **The derivation is structurally unconditional**: `prepare` mints the identifier and derives,
  `create` takes the result, and the second cannot be called without the first - so no database
  outcome decides whether the expensive work happens
- **The item's stated reason for that was corrected rather than repeated.** A `201` and a `422` are
  already distinguishable, necessarily; equal work is defence in depth. **The load-bearing reason is
  operational** - ~46 ms and ~19 MiB must not be paid holding one of eight pooled connections
- **An Identity is no longer constructible without a credential** - the path is removed, not
  deprecated
- **The password stays out of the request fingerprint**, asserted structurally because there is
  nothing to vary, with the consequence written down: a retry with a different password **replays**
  rather than conflicting, because the alternative stores a crackable derivation for ever
- **A short password is a `422`** - the opposite of authentication's answer, and for a stated reason:
  the caller chose this value and must be able to correct it. `@Size` cannot express it, because
  Bean Validation cannot see inside `Sensitive` and unwrapping would put a plaintext in `app`
- **The leak sweep covers every table in every schema**, derived from `information_schema`: the new
  sinks are the idempotency record, the audit record and the outbox row
- **The contract diff is two lines and BREAKING**, accepted on ADR-0015 - nothing consumes this API,
  and the alternative is a `/v2` for a version that was never usable

The architecture tier runs the architecture rules (2026-09-08), `P1-TSK-025`:
- **The rules were not missing from the tier - they were in the wrong one.** `unitTest` selects by
  *exclusion* and took all **28** untagged `@ArchTest` fields; `architectureTest` selects by
  *inclusion* and got none
- **`ModuleBoundaryRulesTest` produced no result file at all** in the architecture tier - it has no
  `@Test` method, so it was not a suite reporting zero cases, it was a suite that did not appear.
  That is the oldest rule suite here, enforcing `app → platform → sharedkernel`
- **Root cause established by disassembling the engine**: `AbstractArchUnitTestDescriptor.findTagsOn`
  loads exactly one annotation, `com.tngtech.archunit.junit.ArchTag`, and cannot see JUnit's `@Tag`
- Fixed with `@ArchTag` beside `@Tag` on all seven `@AnalyzeClasses` suites - ArchUnit's own
  mechanism, never used here because nobody had asked what its engine does with a tag
- **No existing guard could see it: the partition check asserts a SUM, and the sum was right.** Every
  rule was in exactly one tier. A check on a total cannot see a misallocation that preserves it
- `everyArchUnitSuiteIsTaggedForBothEngines` states the property - *both engines must agree which
  tier this class is in* - with its limit recorded, because it errs toward a false requirement
  (a build failure somebody investigates) rather than a false pass (silence)
- Acceptance proven by **performing** it: a `double` planted in production code took
  `./gradlew :app:architectureTest` from exit **0** to exit **1**

Every planned meter exists, and criterion 6 is a build failure (2026-09-08), `P1-TSK-029`:
- Four meters added — `finapp.identity.mfa.challenge`, `session.lifetime`, `recovery.initiation`,
  `recovery.completion`, `session.active` — five instruments, because recovery splits
- **`PlannedMetersExistTest` reads the plan's own §10 table** and asserts every meter it names is in
  the live registry, bidirectionally: a meter renamed and a plan naming one nobody built both fail
  the build. The phase is derived from `CURRENT_STATE.md`, so Phase 2 needs no edit
- **Three of the four planned names could not be registered**: they carried underscores, which
  `MetricNames.NAME` forbids. The plan was corrected rather than the convention widened, because
  Micrometer translates dots to the backend's idiom and both forms produce the same Prometheus series
- **The meters that "existed" did not exist until the flow ran.** `MeterRegistry.counter(...)`
  creates on first call, so a fresh instance published nothing for authentication or lockout — and an
  alert on a rate had no series at exactly the moment it was needed. All counters are eager now, and
  the guard runs no flow, so it can only pass against that
- **Recovery is two meters, not one tagged by `stage`** — widening `ALLOWED_TAG_KEYS` was available
  and refused, and *"recovery initiation rate"* is now one series
- **The initiation counter distinguishes what the `202` hides**, which is correct rather than a leak:
  a metric is never visible to the caller, and a rise in `refused` is a probe
- **`session.active` counts LIVE sessions, not `ACTIVE` ones** — with no `EXPIRED` status and no
  sweep the obvious query is wrong in the *reassuring* direction. `NaN` when unreadable, never zero
- **`session.lifetime` measures one population and says so**: expiry is never observed, bulk
  revocation is one decision, supersession is a replacement
- Dashboard gains an *Identity — security signals* row; `DashboardQueriesResolveTest` caught its
  first version querying `_seconds_bucket`, which a `Timer` does not publish

Authentication issues a session (2026-09-08), `P1-TSK-027`:
- `POST /v1/authentications` answers **201 with the session**, issued inside the authentication
  transaction and the same security scope as the success audit record
- **Closes exit criterion 1.** Before it, `Session.issue` had no production caller, so the eight
  endpoints marked `Auth: session` were unreachable by any real client - the suite could reach what a
  customer could not
- Verified **end to end**: the token a login returns opens `GET /v1/sessions` over HTTP with nothing
  inserted by the test, with a fabricated token as the negative control
- **Always `PASSWORD`, and the level is not a parameter** - a caller able to ask for more would have
  found the bypass `INV-IDN-05` exists to prevent
- **Issued even when a second factor is enrolled**, because `MfaChallenge.elevate` takes a *current*
  session and withholding one would make step-up unreachable. Refused by a `MULTI_FACTOR` handler,
  which is what makes that safe rather than merely conservative (ADR-0030)
- **One audit record**, naming the session it produced; **one transaction**, so neither can exist
  without the other
- `DeviceDescription.fromUserAgent` gets its first production caller, so `GET /v1/sessions` shows a
  person something they recognise
- **The contract change is BREAKING** (`204` removed) and accepted with the reasoning recorded; the
  backlog had described it as additive and is corrected
- **M1.2 closes**, two days after its last numbered task: its acceptance names a session, and a
  milestone means its acceptance rather than its task count

Phase 1 reviewed, and the gate does not pass (2026-09-08), `P1-DOC-001`:
- [`reviews/PHASE_1_REVIEW.md`](reviews/PHASE_1_REVIEW.md): eight areas, twelve universal criteria,
  six Phase 1-specific ones, each with evidence
- **Criterion 1 fails**: no production path issues a first session, so eight endpoints are
  unreachable by any real client and the phase objective is not met end to end
- **Criterion 6 fails**: four of six meters do not exist, including the recovery rate the plan calls
  *a security signal*
- **Phase 1 returns to `IN_PROGRESS`** (`PHASE_GATES.md` §4) - which is the review succeeding
- **Three documentation drifts found by hand-diffing what no guard covers**, all corrected: two
  planned endpoints owned by nobody, an exemption resting on a **false** statement, and an
  imprecision of mine in `CURRENT_STATE.md`
- **Area 2 has no subject and says so** rather than reporting a pass
- ADR-0029…0034 to `Accepted`, on Phase 0's reasoning that criterion 10 is a **precondition** of the
  gate rather than a reward for passing it

The mutation register covers every phase reached (2026-09-08), `P1-TSK-024`:
- **`PHASE_GATES.md` criterion 3 is now enforced for Phase 1**, not only Phase 0 - which left the
  `INV-IDN` group in exactly the weaker regime the Phase 0 → 1 transition created it to escape
- **The acceptance as written was too narrow twice**: there are **eight** `INV-IDN-*` rather than
  seven, and **nine** Phase 1 invariants, because `INV-AUD-03` is `Phase: 1 onward` and is not in
  that group. A guard extended only to `INV-IDN-*` would have missed it
- **Nine register rows did not parse**, eight written during Phase 1, so the guard was not checking
  that the tests they name exist. Proven precisely: a second-position reference naming a missing test
  **survives** the old parser and is **caught** by the widened one
- **The current phase is derived from `CURRENT_STATE.md`**, so Phase 2 needs no change - the
  "extension not needed again" the task asked for
- **§4 finds a test item wherever a bold heading declares it**, because Phase 0 and Phase 1 declare
  them differently and anchoring to either shape passes vacuously for the other
- **A mutation survived and found a defect in this task's own new assertion** - it merged references
  per invariant, so an unreadable row hid behind a readable sibling. Per row now
- **And the gate found the same defect one level out**: a row failing the pattern *entirely* was
  still invisible, so every §2 line that looks like a row must now parse as one

Recovery that is not the way in (2026-09-08), `P1-TSK-023`:
- **`INV-IDN-06` had no subject.** It requires a *previously registered and verified channel*, and
  none existed - no type, no table, no verification, **and no backlog task owning one**. Seventh
  backlog defect of this class in Phase 1 and the most consequential, because it is a missing
  **precondition of the invariant** rather than a missing endpoint
- **Recovery issues no session**, which is the sharpest decision: the conventional design logs you in
  on completion, and that IS the lowering `INV-IDN-06`'s second clause forbids. So the fifth abuse
  case cannot be **attempted**, and `MfaBypassPathsAreEnumeratedTest`'s statement stays true - the
  recorded remainder it left for M1.6 now carries the answer
- **It does not remove the MFA factor either**, straight from the same clause
- **Bound to the credential it was raised against** - a predicate, not a procedure, so a future
  credential-change caller cannot forget it
- **Every refusal is the same refusal, in one statement**: unknown identifier, unverified channel and
  cooling-off are all `202` and all cost the same work
- **The token is delivered nowhere**, `PHASE_1_PLAN.md` §8's recorded seam - and the event carries no
  token, which `EventPayload`'s charset would **not** have prevented (`P1-TSK-009`'s stated limit)
- **Four existing guards refused the new code and all four were right**, two of them written in the
  previous two tasks
- **One assertion of mine was a stale list one task after I wrote it**, and is replaced by the
  property it was reaching for

Audit that names the actor (2026-09-08), `P1-TSK-022`:
- **Six of seven implementation clauses were already true**, probed rather than assumed: a scope per
  authenticated request, **thirteen** audit sites all calling `require()` with **none** defaulting,
  every writer on the caller's connection, immutability at `DB-PRIVILEGE`, an unestablished actor
  refused, fifteen actions catalogued
- **`P0-TSK-023`'s recorded limit closed** — *"it cannot detect a privileged action that writes no
  record at all"*, which is the failure that matters, because a registry agreeing with a catalogue
  while nothing emits half of it looks complete **from both sides**. Every action is now emitted or
  **declared not to be** with the owning task, so an action that *stops* being emitted fails the build
- **`enterSystem()` is enumerated, not grepped** — two sites, both unauthenticated, a third fails the
  build. The authentication justification existed only as a code comment and is now in
  `SECURITY_ARCHITECTURE.md`
- **Method granularity is a real gap and is closed where it exists**: `AuthenticationService.attempt`
  holds both branches, so a separate assertion requires the success branch to still name the person
- **The headline property is asserted over the rows, not per action** — nothing had asserted that
  *no* record from an authenticated request names the platform, which is the claim that fails when
  somebody adds an audit call in a hurry
- **Which assertion is load-bearing was established by probing**: a blank target is refused by
  `AuditRecord` at construction, so the field assertions are recorded as defence in depth; the
  **correlation** assertion is the one nothing else makes, and a record joining to no flow satisfies
  every constraint while being exactly the record an investigator cannot use

Ownership, and the rule that holds it against the code (2026-09-08), `P1-TSK-021`:
- **ADR-0031's second half**: permission asks *may an actor of this kind do this at all?* and cannot
  answer *may **this** actor do it to **this** resource?* — the boundary knows only an identifier
  out of the request, and trusting it **is** the defect
- **The acceptance criterion was already met**, probed rather than assumed: `identity_id = ?` lives
  in the **statement** for session listing and revocation, and MFA resolves the enrolment *from* the
  session's identity. Dropping the predicate fails **six** tests
- **So the deliverable is `OwnershipIsScopedTest`** — every persistence method taking a resource
  identifier classified `OWNER_SCOPED`, `AUTHORITATIVE_ID` or `NOT_OWNED`, with a new one failing
  the build. A list of tests is a snapshot; the operation added in Phase 4 will not be in it
- **Five correct statements look exactly like the defect**, which is why the rule classifies rather
  than forbids — `revoke`, `touch`, `confirm`, `consumeStep`, `supersede` all target a row by primary
  key, and all five are safe because the identifier came from an owner-constrained read
- **The detector was more accurate than my register twice**: it found the `private` helper where the
  statement actually is, and it surfaced a platform row with no owner at all
- **The first version survived its own mutation** — it searched the whole method body, which contains
  the comment *"identity_id = ? IS the ownership check"*. A `contains` over source text matches
  prose. String literals only now
- **An `AUTHORITATIVE_ID` claim now has to be true where that is checkable**: the named provenance
  must itself carry an ownership predicate. Five of seven entries rest on that class
- **ADR-0031's *"no build rule closes this"* is narrowed, not withdrawn** — the rule forces
  classification and does not decide safety, and the three remaining limits are stated in the test
- **The gate found it aimed at the wrong half**: the defect this repository actually shipped was a
  method that takes an owner and **never uses it** (`P1-TSK-016`). A second assertion now requires a
  statement handed an `IdentityId` to name the owner — twelve of thirteen satisfy it, and the
  thirteenth locks `identity.identity` where `id = ?` *is* the owner
- **It also covers the bulk disclosure the first half cannot see** — `findLiveFor` takes no resource
  identifier, so nothing would have noticed it losing its scope
- **The coverage guard had deviated from its four siblings** — a bare `isNotEmpty()` where every
  other rule suite asserts equality against the classpath's modules, leaving `party` unprotected

Deny by default, and the permission check (2026-09-07), `P1-TSK-020`:
- **A rule's absence is never a grant** (ADR-0031, `INV-IDN-04`). Every handler in `com.finapp`
  declares `@Unauthenticated`, `@RequiresSession`, `@RequiresAssurance` or `@RequiresPermission`,
  and one that declares nothing is **refused** — asserted with a **valid** session, because the
  property is about the declaration rather than the caller
- **Enforced twice, and neither replaces the other**: the interceptor refuses at run time, and
  `EveryEndpointDeclaresARuleTest` fails the **build** — a static sweep cannot see a handler
  registered at run time, and a runtime check cannot fail a build. The routes come from
  `RequestMappingHandlerMapping`, never from a list
- **`P1-TSK-016` had recorded that this failed closed *by accident*** — a throw at
  `SecurityContext.require()` deep inside the handler, which is a **500 standing in for a security
  decision** and depends on the handler happening to need an actor
- **Permissions are resolved per request, never stamped on the session** — a role carried on a
  session survives its own revocation until that session expires, and *"remove their access now"*
  becomes a promise the architecture cannot keep. Asserted with the **same** token across the revoke
- **A denial is audited, after the security scope opens** — a permitted privileged action is audited
  by the operation, and a refused one has no operation to do it; checking before the scope would
  name the platform rather than the person
- **Roles are marked revoked, never deleted** (no `DELETE` grant), and assignment is
  `ON CONFLICT DO NOTHING` against a partial unique index, so the row count is the outcome
- **`api.Forbidden`, deliberately not a distinct code** — unlike `identity.AssuranceRequired` this
  is not actionable, and a client cannot grant itself a role
- **The two endpoints this exists for are owned by no task**, recorded as `P1-TSK-028` rather than
  invented here: an admin endpoint added to give the annotation a caller is a security surface
  chosen to suit a test
- **One mutation was a no-op and re-aiming it is the finding** — with one role holding both
  permissions, "returns all permissions" changes nothing; the mapping becomes testable at the second
  role, and that limit is stated rather than papered over
- **The gate found an endpoint that reads as protected and is public**: `@Unauthenticated` beside
  `@RequiresPermission` was answered `200` with no session, and **both** guards passed it. Refused
  now in both places rather than resolved to the stricter reading, which would have hidden it
- **`V010` named a migration test that did not exist** — fifth occurrence this phase. Written, along
  with the first test `RoleName` has ever had

MFA cannot be bypassed (2026-09-07), `P1-TSK-019`:
- One named test per enumerated path, so a failure says **which** route opened rather than *"MFA is
  bypassable"* — an older `PASSWORD` session, a refresh, re-enrolment, and a direct call to the one
  endpoint that issues a session
- **Found a real bypass by probing**: a `PASSWORD` session could begin a second enrolment with an
  attacker-controlled secret. Only a partial unique index stopped the confirmation, and it did so as
  an unhandled storage exception — a **500**. The property held by accident of a constraint rather
  than by a decision, and removing that index would have made a stolen password enough to **swap
  somebody's authenticator**
- **The rule now: replacing a confirmed factor requires that factor.** A first enrolment does not,
  because it cannot — refused at `begin`, so the attacker never receives a secret
- **The check is in the domain, not the annotation**: the requirement is conditional on whether a
  factor exists, and a boundary annotation is static per handler
- **The enumeration is held against the code.** `MfaBypassPathsAreEnumeratedTest` fails when a new
  session-issuing path appears without being named with its reason — the `P0-TSK-037` shape, because
  a list of tests is a snapshot and the path added next phase will not be in it
- **What that guard says today**: nothing in production calls `Session.issue`, so the only way a
  session comes into existence is a **proven second factor**. An accident of sequencing, and exactly
  why the guard exists — `P1-TSK-027` must come here and say so
- **`STRONG` satisfies a `MULTI_FACTOR` requirement**, which is the assertion a boolean cannot be
  written against (ADR-0030), proven by a mutation turning `atLeast` into equality
- Recovery is a **recorded remainder** for M1.6: asserting absence over an unmapped route would pass
  vacuously

Second-factor challenge and step-up (2026-09-07), `P1-TSK-018`:
- `POST /v1/authentications/mfa` — a verified code elevates the session to `MULTI_FACTOR` by
  **rotating its identifier**, which gives `P1-TSK-015`'s rotation its first caller
- **Replay needed a new mechanism**: enrolment consumed its `PENDING` row, and a challenge has
  nothing to spend. The last accepted **time step** is recorded, and anything at or before it is
  refused — stronger than refusing exact repeats, and what RFC 6238 §5.2 actually asks for
- **Confirmation consumes its step too**, closing a replay across the two operations
- **Throttling is not optional**: three valid values in a million per attempt (RFC 4226 §7.3). MFA
  failures share the account's lockout budget, because an attacker guessing codes already has the
  password — and a **replay does not count**, so a client retrying after a timeout cannot lock its
  own account
- **The response carries a session token**, and one decision met three guards: `secretsAreWrapped`
  gained its **first exemption** (serialisation only — the `toString` harm is closed by an override
  and asserted), and the contract guard was narrowed precisely for the second time
- **`@RequiresAssurance` ships with no production caller**, which is the plan rather than an
  omission: Phase 1 has no high-value action, so inventing one would be a control chosen to suit a
  test. A distinct error code, because *"you may never"* and *"step up and retry"* are different
  instructions
- Two mutations survived: a detail added to **every** refusal walked through an equality-between-
  causes, and a `PENDING` factor was refused by a second independent guard — both closed and
  verified

TOTP enrolment (2026-09-07), `P1-TSK-017`:
- `POST /v1/me/mfa` and `POST /v1/me/mfa/confirmation` — a second factor, which does **nothing**
  until the customer proves they hold the secret
- **`INV-IDN-01` cannot apply**, and that is catalogued rather than glossed: a TOTP secret must be
  recoverable, so irreversibility is impossible and **`INV-IDN-08`** states what replaces it —
  encryption at rest under a key held outside the database. The platform now has **72 invariants**
- **AES-256-GCM, and the authentication half is the point**: a substituted ciphertext must fail
  rather than decrypt to a secret an attacker controls — a second factor that authenticates the
  attacker, silently
- **The secret is emitted exactly once**, because the QR code *is* the secret. The plan's *"never
  emitted"* cannot be met literally, so the deliverable is the tightest honest bound
- **The build rule was right about a `String sharedSecret`, and the code changed rather than the
  rule** — wrapping was not available either, because the serialiser masks `Sensitive` and the
  customer would have received «redacted»
- **"Vetted library" deviated from, with the reason**: no library implements HMAC, and the RFC
  publishes **test vectors** — 22 of them are in the suite, so correctness is against the
  specification rather than a reputation. No primitive is invented
- **Constant-time comparison asserted structurally**, because no behavioural test can distinguish it
  from `equals` — only timing can, and a timing test measures the machine
- Four mutations survived first and each found a real gap: no concurrency test, no `MfaKey` test, no
  `SecretCipher` test, and a property only a structural assertion can reach

Session and device endpoints (2026-09-07), `P1-TSK-016`:
- `GET /v1/sessions`, `DELETE /v1/sessions/{id}`, `DELETE /v1/sessions/current` — a person can see
  where they are logged in and end a session, which is the visible half of `INV-IDN-03`
- **Found: `SessionRevocation.revoke` took an `owner` and did not check it.** Any caller could end
  any session by identifier, while the audit record asserted an owner nobody verified — worse than
  an absent parameter, because the signature reads as though ownership is enforced
- **Found: nothing could authenticate a request, and no task owned it.** Eight endpoints are marked
  `Auth: session`; permission and ownership both presuppose a caller, and `P1-TSK-027` hands a token
  out rather than consuming one. Fifth backlog defect of this class in Phase 1, and the widest
- **Ownership is in the `WHERE` clause**, never a load-compare-act: the compare-then-act is a TOCTOU
  race, and ADR-0031 wants the check against authoritative state rather than a copy of it
- `SessionQueries` takes the **proven `Session`**, never an `IdentityId` — an identifier parameter
  would be satisfied just as well by one read out of the request, which is the defect itself
- **Not yours, does not exist and malformed are one answer**: `404`, byte-identical, because `403`
  would confirm the identifier belongs to somebody
- **The platform's first real inbound actor** — every request establishes a scope naming the proven
  identity, which is what ADR-0021 said `enterSystem()` was a placeholder for
- **Device sanitises rather than refuses**, the opposite of `PartyName` and deliberately so: a
  `User-Agent` is a header the person did not choose, so an unscored label must never refuse a login
- Two mutations survived first and each found a real gap — a raw header tested against a *revoked*
  session proved nothing, and nothing asserted the security scope was ever closed

Rotation (2026-09-07), `P1-TSK-015`:
- A new identifier on every privilege change - elevating in place lets an identifier stolen *before*
  the elevation become elevated behind the legitimate user's back
- **Login needed no code**: a token comes from `SecureRandom` inside the server and no client can
  supply one, so the deliverable is a test that the mechanism refuses
- **Rotation preserves the absolute bound**, which nothing had written down: resetting it would let
  anyone able to trigger a rotation stay logged in for ever
- **Revoke first, issue only if the revoke won** - inserting first would hand out two usable
  identifiers where there should be one
- Audited as a **rotation**, never as a revocation, naming both identifiers
- Reusing the predecessor's token is **structurally unreachable**, and now asserted: the component
  never receives the plaintext

Revocation (2026-09-07), `P1-TSK-014`:
- Revoke one, revoke all, revoke all **except** one - the last being what a credential change does,
  so the attacker's session ends and yours does not
- `INV-IDN-03` verified as the invariant itself specifies: revoke on one instance, refused on
  another, own connection each
- **A session issued concurrently with a revoke-all was surviving it**, verified broken before the
  fix - an attacker holding the old password kept a live session across a password change
- **One explicit lock, not two**: bulk revocation takes `FOR UPDATE`, and a session insert already
  takes `FOR KEY SHARE` through its foreign key. A surviving mutation established that; the
  redundant lock was removed rather than left to read as the mechanism
- **The race test asserts the coordination** - it waits for PostgreSQL to report the issuer blocked -
  because both mechanisms leave identical rows
- One audit record per **operation**: the rows say when each session ended, the trail says who decided

Sessions (2026-09-07), `P1-TSK-013`:
- Server-side and authoritative in PostgreSQL, so `INV-IDN-03` holds **by construction** - and
  `NoProcessLocalSessionStateTest` fails the build on a field holding sessions, which is the shape
  ADR-0024's four patterns cannot see and transition risk **R7** named
- **The token is stored hashed**: a bearer credential, and a leak with plaintext tokens hands an
  attacker every live session with no work at all
- **SHA-256, not Argon2** - a work factor protects a low-entropy password and buys nothing against
  256 random bits, while costing ~46 ms on every authenticated request
- **Two identifiers**: a UUIDv7 for foreign keys and logs, 32 random bytes for the client, because a
  UUIDv7 encodes its creation time and ADR-0030 forbids structure in the presented value
- **No `EXPIRED` status**: expiry is derived, because a stored one needs a sweep and until it runs
  the database would say `ACTIVE` about a session that is not
- **Both bounds on the row**, so a policy change cannot reach backwards; each asserted alone
- Assurance is a **level with an ordering**, never a boolean - `MULTI_FACTOR` and `STRONG` have no
  producer yet, and one value would have been a boolean wearing an enum's clothes

Authentication failure modes (2026-09-06), `P1-TSK-012`:
- **Two of four scenarios were already met**, verified rather than restated - a second copy of a
  working assertion is duplication, not coverage
- **A login racing a credential change must not reinstate the replaced password**, which is the
  hazard upgrade-on-use creates and which no earlier suite exercised
- **The coordination is asserted, not the outcome**: three mechanisms produce the right end state
  here, and an outcome-only assertion let two mutations survive
- **Fails closed is two claims**: no success reported, and no durable trace claiming otherwise
- The mid-flight kill is **deterministic** - the flow terminates its own backend - after a timed
  version raced a one-millisecond derivation and disrupted nothing

Brute-force controls (2026-09-06), `P1-TSK-011`:
- **Two controls, two keys**: lockout (per identity) stops guessing and must not change cost or
  response; a rate limit (per source) stops exhaustion and may refuse cheaply
- **A lock never makes an attempt cheaper** - a locked account answering faster than an unknown one
  is an account-existence oracle, so verification runs first, unconditionally, and is counted
- `INV-CON-03` **first enforced, three phases early**: one row per identity, one atomic
  `INSERT … ON CONFLICT DO UPDATE … RETURNING`; ten instances produce exactly ten
- **A correct password is still refused while locked** - a lock a correct guess clears is a signal
  that the guess was right
- **Self-healing, no operator unlock**: lockout is itself an attack, and an admin unlock turns a
  cheap one into a support-desk denial of service
- **Per-source is deliberately not built**: behind the load balancer this architecture commits to,
  it would take authentication down for everyone

Authentication, enumeration-safe (2026-09-06), `P1-TSK-010`:
- `POST /v1/authentications` — **204 or 401, and only those two.** Unknown identity, wrong password,
  suspended identity and an identity with no credential are byte-identical **and cost the same work**
- The response half is asserted as an **equality between the four causes**, not four assertions
  against a remembered expectation - the second form passes against four different bodies that each
  happen to match what their author wrote down
- **The timing half exists because a mutation survived the first one**: skipping the derivation for
  a malformed password left every response identical and changed only the clock
- **The refusal is returned, never thrown** - the audit record of a failed attempt is written in the
  same transaction, and a credential-stuffing campaign is visible only through those rows
- **Not idempotent, deliberately**: a key is not a secret, so a stored success keyed on one would let
  anybody holding it replay a successful authentication
- **The platform's first real actor** - a success names the proven identity, a failure names the
  platform, and the asymmetry is correct information rather than a channel
- **No session**, which is `P1-TSK-027`'s recorded remainder: M1.2 cannot close on `P1-TSK-012`
- `secretsAreWrapped` found a real defect in the request DTO and the fix needed the Jackson
  **deserialiser** - the symmetric half of `P0-TSK-030`, never needed until a body carried a secret

Credentials never leak (2026-09-06), `P1-TSK-009`:
- The stated criterion was **already met** and was probed rather than assumed: an unwrapped
  `String lastPassword` planted in real production code fails the build twice
- The deliverable is the gap between the task's two clauses - the field rule governs what a **type
  stores** and cannot see a local, a third-party message, the MDC, an event payload or a tag
- **The output scrubber was rejected, and the reason is that it is a deny-list**: to recognise a
  secret it must be *given* the secret, so the plaintext travels further rather than less far
- **The whitelist replaces it**: every unwrap is an `expose()` call, pinned to **four production
  classes, all in `identity`** - method references as well as calls, since a reference is an
  `invokedynamic` and is one syntax away from bypassing a call-only rule
- `INV-AUD-02`'s **first real subject**: every demonstration before it used a fixture written by
  somebody who already knew the rule
- The one production log call on the credential path - on the *failure* path, which is the half
  nobody reads until something is wrong - asserted to name neither the password, the derivation,
  the login identifier nor the identity
- **Two limits stated in the tests themselves**: `EventPayload` is a charset and would publish
  `hunter2`; the whitelist says *where* a secret may be unwrapped, not what happens next

Verification and upgrade-on-use (2026-09-06), `P1-TSK-008`:
- A weak credential verifies and is **re-derived at current policy in the same transaction** - the
  only moment the platform legitimately holds the plaintext, and the only moment an upgrade is
  possible without involving the customer
- **Four failing paths, all of which do the full work**: no identity, an identity that cannot
  authenticate, an identity with no credential, a wrong password. Counting derivations rather than
  reading a clock is what makes "equivalent cost" a fact instead of a hope
- `VerificationOutcome` gives a caller **nothing to branch on** when it fails - no reason, no status,
  and every failure the same object - so `INV-IDN-07` cannot be lost by a future author mapping a
  reason to a message
- An upgrade failure is discarded behind a savepoint: a correct password is a successful login
  whatever the upgrade did
- The platform's **first genuine read-then-write**, made safe by a conditional `UPDATE ... WHERE`
  whose row count is the outcome; ten instances produce one upgrade and **one insert attempted**

Credential storage (2026-09-06), `P1-TSK-007`:
- `identity.credential`: an Argon2id derivation **plus the algorithm and cost factors that produced
  it**, per credential (`INV-IDN-02`) - because a global work factor cannot be raised
- **A plaintext cannot physically be stored**: the derivation column refuses a value that is not in
  its algorithm's encoded form, so `INV-IDN-01` holds at `DB-CONSTRAINT` and not only in code
- Superseded, never edited - a `BEFORE UPDATE` trigger, because the application role needs `UPDATE`
  to supersede and the grant would otherwise be wider than the intent
- A partial unique index gives at most one active credential per identity and type; ten instances
  racing produce exactly one, and a conditional supersede tells the loser it lost
- ~46 ms per derivation, **measured** and recorded in ADR-0032's follow-up rather than asserted
- The library needs BouncyCastle and spring-core at run time despite declaring neither - found by
  running the real encoder, which a test double would not have found
- Nothing verifies yet: that is `P1-TSK-008`, and `isWeakerThan` is written and called by nothing

Registration, end to end (2026-09-06), `P1-TSK-006`:
- `POST /v1/registrations` - the platform's **first endpoint**, first domain events, first emitted
  audit records, and the first declared `@RequiresIdempotencyKey`
- One transaction across `party` and `identity`: Party, Customer, Identity, two audit records and
  three outbox rows commit together or not at all, proven by injecting a failure at the **last**
  write of the command and by terminating the connection mid-transaction
- **The response body is empty**, because the idempotency key is explicitly not a secret and anyone
  holding one can replay an unauthenticated endpoint; and there is no replay header, because that
  would tell a replaying stranger the login identifier exists (`INV-IDN-07`)
- A collision and any other refusal are byte-identical - `422 party.RegistrationRefused`, no detail
- **A savepoint**, because a unique violation aborts the transaction: without it the idempotency
  outcome could not be recorded and a retry would re-run rather than replay. A pre-flight `SELECT`
  is documented as *not* a substitute
- Ten concurrent racers for one identifier produce exactly one person; ten retries of one request
  produce exactly one effect, and every non-201 is a 409 rather than an assumed failure
- `EventPayload` refuses any value that is not an identifier or an enumerated name, so
  `INV-AUD-02` is enforced rather than remembered - and it caught a real mistake on its first run
- **No credential**, which is `P1-TSK-026`'s recorded remainder rather than an omission

Mutation demonstrations enforced (2026-09-03), `P0-TSK-038`:
- [`MUTATION_TESTING.md`](MUTATION_TESTING.md): the convention, plus a register covering all **17**
  Phase 0 invariants and all **9** `P0-TST-*` items
- `PHASE_GATES.md` criterion 3 is now checked on every build rather than verified once at the gate
- Two admissible forms, labelled per row: an **in-suite** proof runs continuously and cannot rot; a
  **recorded** procedure proves the test had teeth on the day it was written
- An in-suite row must name a **method that still exists**, so a claim of continuous proof cannot
  point at something renamed away
- The audit found `INV-MON-05` had a test and **no recorded demonstration**; closed by performing
  it - re-deriving the scale from the currency fails **exactly one** test, and not the general
  round-trip test, which is why the register names a method rather than only a class
- `INV-AUD-01` recorded as demonstrated only in half, and the un-reproducibility of a recorded
  procedure recorded as the residual risk the form column exists to make visible
- Nine mutations, all caught, each by the intended assertion

Provider failure simulation (2026-09-03), `P0-TSK-037`:
- `SimulatedProvider` in two halves, because a provider is unreliable in **both directions**:
  outbound, a real HTTP server we call; inbound, the provider calling us
- Outbound covers timeout, unavailable, 5xx, delayed, malformed body, garbage, unknown state, the
  retry sequence, and the request **received** before the response is lost
- Inbound covers the duplicated webhook and the late settlement - the provider acting on its own
  schedule, which no stubbing of its API reproduces
- `requestCount` separates a request that never arrived from one that arrived and was acted on,
  which is the distinction `INV-LIFE-03` exists for
- No WireMock type in the harness's signature: ADR-0008's anti-corruption argument, one layer down
- `ProviderFailureCoverageTest` makes the criterion real in three links - classified, exists,
  actually called - so a mode cannot be covered on paper; seven mutations, all caught
- Two defects found by its own assertions: a regex that read past the section into the next
  heading, and a literal match defeated by ADR-0008 wrapping mid-phrase
- WireMock **standalone**, measured: Jetty and Jackson relocated, one lockfile entry, no servlet
  container added to `app`'s test classpath
- No new ADR - this is ADR-0008's own recorded follow-up, and it now says so

Test taxonomy (2026-09-03), `P0-TSK-036`:
- Four tiers - unit, architecture, slice, database - defined by **what a test needs in order to
  run**, which is the only axis on which membership can be decided mechanically
- Each its own task; the tiers partition the hermetic suite exactly (418 + 54 + 68 = 540 = `test`),
  and `unitTest` is ~14s against `build`'s minute
- The default tier selects by **excluding** the others' tags, so a test can never run in no tier -
  the silent failure an includeTags-only set of tasks creates
- `contract` and `integration` deliberately not tiers, with the reason recorded rather than dropped
- `TestTaxonomyTest` holds the Gradle declaration, `TestTier`, every class's tag, `TESTING.md` and
  CI to each other; seven mutations caught, after the first one survived on a stale class file
- Its own guards found `ModuleBoundaryRulesTest` and `MoneyTest` being skipped entirely, and a
  false positive that narrowed detection from mentioning a connection to acquiring one
- Thirteen test classes migrated off private connection helpers onto `DatabaseRoles`
- ADR-0028 records the reasoning and the five rejected alternatives

Tests bring their own database (2026-09-02), `P0-TSK-035`:
- One PostgreSQL container per test JVM, with the same role script and the real migrations
- All 173 database tests pass with nothing running locally; the hermetic suite is untouched
- No test changed: the harness publishes the system properties they already read
- PostgreSQL only - there is no Kafka or Redis client, and a container nothing connects to tests
  nothing
- Two ArchUnit rules fired on the fixtures and both were fixed at source rather than exempted
- ADR-0027 records the reasoning and the four rejected alternatives

Keeping the pins fresh (2026-09-02), `P0-TSK-040`:
- Dependabot for the four SHA-pinned actions and both version catalogues; a weekly registry check
  for the two scanner digests it cannot read
- Each scanner pin is repository, version **and** digest, so "is this digest still v8.30.1?" is an
  answerable question - it had been a comment, which nothing can check
- The check distinguishes a **moved tag** from **rot**, and both were proven against the real
  registries
- Trivy's digest moved out of a workflow `env:` value; both scanners now run through
  `infra/scripts/`, so CI and a developer use the identical image
- A PR-opening bot was rejected for a check that could actually be demonstrated, since no workflow
  in this repository has ever executed
- ADR-0026 records the reasoning and the four rejected alternatives
- The pin record is `scanner-pins.sh`, not `.env`: `.gitignore` ignores `*.env` under its
  Secrets section, so the first version was silently **never committed** and every CI job
  calling a scanner would have failed sourcing a missing file. Caught by inspecting what was
  actually staged rather than what was written

Dependency verification and locking (2026-09-02), `P0-TSK-039`:
- 456 artefacts checksum-verified and six lockfiles enforced, closing the largest remaining
  supply-chain hole: a build-time dependency runs with full build privileges
- Both proven by mutation - an altered checksum fails naming the artefact, a changed locked version
  fails naming the lock state
- Locking is **not** redundant, and measuring showed why: 69 of 342 modules are recorded at more
  than one version, so verification cannot tell a resolution from drift between versions it trusts
- The lockfile is the only place the thirteen BOM-managed dependency versions are written down
- Trust-on-first-use stated as the limit; PGP measured (11 signed, 49 keys, one slice) and deferred
- The update procedure is three steps and a diff review; regeneration verified to **merge**
- ADR-0025 records the reasoning and the four rejected alternatives

Multi-instance test convention (2026-09-02), `P0-TST-009`:
- `SimulatedInstance`: own connection, own clock, and skew anchored on `SELECT now()` rather than a
  fixture constant
- The existing skew test was **named for a property it did not exercise** - its "fast" clock was
  forty hours behind the server, so it passed under a deliberate reintroduction of the defect
- Corrected, and now fails under that reintroduction, with a precondition asserting the skew is
  real and in the dangerous direction
- Seven concurrency tests audited: all give each instance its own connection; a shared clock is
  correct everywhere because every cross-instance decision uses the server's clock
- The declared `P0-TSK-035` dependency removed as a backlog defect, with the reasoning recorded
- `DatabaseRoles` moved to a shared test-support package

Single-instance assumptions fail the build (2026-09-02), `P0-TSK-041`:
- Four rules: `synchronized` (method **and** block), process-local locks, ambient scheduling,
  static mutable state
- The exemption set is `DISTRIBUTED_EXECUTION.md` SS3 rather than a list the rule keeps, named
  individually so a type-wide `ThreadLocal` exemption cannot admit the next one silently
- Both exemptions proven load-bearing: the same rule with an empty exemption set fires on both
- The block check reads **bytecode**, because ArchUnit models accesses and a `MONITORENTER` has no
  access flag - verified by probe rather than assumed
- Three defects caught by the task's own teeth tests: two rules that could not fail
  (`noClasses()` inversion, and `haveModifier` on the wrong target) and a sweep that missed every
  module arriving as a jar
- The limit is stated: the defect that motivated ADR-0014 used none of the four patterns
- ADR-0024 records the reasoning and the four rejected alternatives

Transport and at-rest encryption (2026-09-02), `P0-TSK-034`:
- `TransportSecurityGuard`: a non-loopback database must be reached with `sslmode=verify-full`, or
  the application refuses to start
- Built because the driver's default was measured to **connect unencrypted and report nothing** -
  the configuration that is correct locally is a plaintext remote connection in a deployment
- `require` rejected as insufficient: it encrypts and authenticates nothing
- Every configured source of `sslmode` must agree, so the control does not rest on a driver
  precedence that was measured and could change
- `DatabaseEndpoint` extracted, so "is this database on this machine?" has one definition shared
  with the credential guard
- Kafka, Redis and inbound HTTP documented rather than guarded - no client exists for the first two
- At-rest expectations recorded per concern with owning phases; nothing is encrypted today and
  nothing holds data that needs it
- ADR-0023 records the reasoning and the four rejected alternatives

Data classification (2026-09-02), `P0-TSK-033`:
- Five levels, applied **per column at its ceiling** - what a column may ever hold, not what it
  holds today, because a column cannot be reclassified once it has data
- Written in the phase that holds nothing sensitive, which is the only phase where the decision is
  still free
- All 46 platform columns registered, reconciled against the **live schema** in both directions, so
  a migration adding an unclassified column fails the build
- Handling rules referenced rather than restated - every one is already enforced by an existing rule
- The weak point named: no build rule checks what a caller writes into a free-text column
- ADR-0022 records the reasoning and the five rejected alternatives

Security context (2026-09-02), `P0-TSK-032`:
- `SecurityContext` carries the acting party per flow and across thread handoffs, so an actor need
  not be threaded through every signature between entry point and audit write
- **An unestablished actor is an error, never `Actor.SYSTEM`** - a default is correct today and
  silently wrong the moment real identity arrives, and the resulting record is permanent
- `enterSystem()` is the greppable list of places claiming the platform acted; reading
  `Actor.SYSTEM` anywhere else fails the build
- `Actor`/`ActorType` moved to `platform.security`; audit records an actor, it does not own one
- Not merged into `Correlation`: an identifier that names one execution and one that names a party
  are different things, and merging them would put a customer identifier in every log line and span
- Captured at submission, never `InheritableThreadLocal`, with the pooled-worker leak asserted
  against a genuinely dirty thread
- Phase 1's substitution proven against the real schema rather than asserted, using the identifier
  shapes real identity providers issue
- ADR-0021 records the reasoning and the five rejected alternatives

Secret management (2026-09-02), `P0-TSK-031`:
- No credential literal can reach committed configuration: a credential-named key must be a
  placeholder or the one marked local default, over files the rule **discovers** rather than lists
- Built because the CI scanner was measured and does **not** catch `password: hunter2` - the shape
  a human actually commits, and the shape this repository's own configuration has
- The marked local default is single-sourced by that same rule across six files in three languages
  that cannot share a constant; a comment claiming "three places" was already wrong
- `DatabaseCredentialGuard` confines the marked default to loopback, closing the one documented
  bypass of externalised configuration - forgetting to set the variable
- Fails closed on an unreadable host, and checks every host in a failover list
- `infra/scripts/secret-scan.sh` is one definition: CI calls the script a developer runs, so the
  pinned digest and the arguments cannot drift apart
- Clause 3 proven against a throwaway clone, never against this repository - a dummy secret
  committed here would make the scan red for ever and need a history rewrite to undo
- ADR-0020 records the reasoning and the five rejected alternatives
- Review found the rule read only the **first** token of a line, so the SQL role script - one of the
  four files it names - was not being checked at all; now every match on a line, with SQL's
  quoted-literal form handled and prose still excluded
- Review also found `.sh` unscanned, `authorization`/`bearer` missing from the vocabulary, and
  `PGPASSWORD` unsplittable; all closed, with twenty-two shapes now asserted on every build
- The guard read `spring.datasource.url` while Hikari's own `jdbc-url` wins - a proven bypass,
  now closed by reading what the pool actually connects with

Log redaction proven rather than trusted (2026-09-02), `P0-TST-008`:
- `secretsAreWrapped` fixed: it could not fail at all, because `noClasses().should(...)` inverts a
  condition that only ever emitted violations
- Its fixture test now evaluates the **rule** rather than the condition, which is what let the
  defect sit behind a green test
- Both production shapes proven to fail: a record component, and a field reachable only by a getter
- `onlyCorrelationContextWritesTheMdc`: the MDC is a `String` map the ECS encoder lifts to
  top-level fields, so it bypasses the wrapper entirely - writes are confined to one component
- Redaction asserted on console **and** file appenders, each with a precondition and a negative
  control, because value-level and encoder-level redaction look identical to a one-appender test
- Both rules rejected on every build in the four sibling suites' own idiom, after review found
  this suite had invented a third way of proving teeth - the deviation that hid the defect

Structured logging and default-deny redaction (2026-09-02), `P0-TSK-030`:
- `Sensitive<T>`: every rendering path masks - `toString`, interpolation, concatenation, a record's
  generated `toString`, and JSON
- `secretsAreWrapped` fails the build on an unwrapped secret field **or accessor**, which is what
  makes the redaction default-deny rather than something to remember
- Identity equality, so the wrapper cannot be used as an oracle for the value it hides
- Masked serialisation stated explicitly, because Jackson's non-disclosure was accidental and would
  end the day somebody added a getter
- ECS JSON logs everywhere, with `correlationId`, `traceId` and `spanId` as queryable fields
- Tests read emitted output rather than a list appender, each with a negative control
- Verified on a running instance: JSON on real stdout, correlation flowing through, and the
  database password absent even from a logged authentication failure
- ADR-0019 records the reasoning and the four rejected alternatives

Metrics and dashboard (2026-09-02), `P0-TSK-029`:
- `finapp.<module>.<noun>` enforced against the live registry, so a meter from a module that does
  not exist yet is covered without anyone remembering
- No tag value may come from a request; correlation is deliberately kept off metrics, and
  `CorrelationSinkCoverageTest` records `metrics` as the one concern where it must be kept out
- Outbox depth and age as gauges over the database - readable when the relay is down, which is
  when they matter - from one statement so the pair cannot describe two instants
- An unreadable backlog reports NaN, never zero, so an alert still fires
- `/actuator/prometheus` exposed, `prom/prometheus` and `grafana/grafana` pinned in `compose.yaml`
  and covered by the drift check
- The dashboard is a reviewed file in git with UI edits disabled, and was verified rendering live
  data in a browser against a running instance
- ADR-0018 records the convention, the cardinality rule and the four rejected alternatives
- Every dashboard query is resolved against the live registry, so a renamed metric fails the
  build rather than turning a panel into "No data" - added by review, proven by mutation

Distributed tracing (2026-09-02), `P0-TSK-028`:
- Every span carries `finapp.correlation_id`, stamped once by a span processor in the composition
  root, so the property holds for spans this codebase does not produce
- A trace identifier is explicitly not a substitute: sampling would make a flow unfindable from
  the value a customer quotes
- Inbound W3C `traceparent` is joined rather than replaced, with a negative control proving two
  unrelated requests remain two traces
- HTTP and database in one connected trace against a real PostgreSQL, the database span a child of
  the request rather than a sibling
- `finapp.db.connection` and no statement text: SQL on a span would carry amounts and account
  identifiers into a backend with different access control (`INV-AUD-02`)
- No exporter endpoint in source; sampling at 100% and recorded as a Phase 15 decision
- ADR-0017 records the reasoning and the four rejected alternatives
- A log line inside a request carries `traceId`, `spanId` and `correlationId` together, so a
  line found in a search leads to both the trace and the durable record - added by review,
  where it was found to be working by coincidence of two mechanisms and asserted nowhere
- A span outside any flow carries no correlation rather than a fabricated one

API conventions (2026-09-02), `P0-DOC-003`:
- [`API_CONVENTIONS.md`](../architecture/API_CONVENTIONS.md): versioning, the published contract,
  errors, correlation, request limits, idempotency, pagination and deprecation in one place
- Every section labelled `Implemented` or `Decided, not yet implemented`, with the owning task -
  and an unlabelled section fails the build, so the distinction cannot erode
- Every stated value pinned against the code: the prefix, the handler package, the correlation
  header, its charset and 128-character bound, the size limit and its property, the problem-detail
  members and the media type
- The error-code catalogue is referenced, never restated; a pasted table fails the build
- Cursor pagination decided on a correctness argument, not a performance one
- Six mutations caught in both directions - document wrong, and implementation moved
- Review added three more: every error code the document names must exist, the documented
  charset must be the whole of the implemented one, and the deprecation windows must agree
  with ADR-0015 rather than being a second unguarded copy of it

Health, readiness and build info (2026-09-02), `P0-TSK-027`:
- Liveness depends on nothing external; readiness includes PostgreSQL; both proven in both
  directions, with a real database for the positive control
- 503 rather than a DOWN body behind a 200, because a load balancer acts on the status line
- The application starts with its database unreachable and says NOT_READY, rather than crash-looping
- Checked through the application's own pool, as `finapp_app` and never a superuser - asserted, so
  the check cannot pass on privileges the application would not hold
- Allow-list exposure: `health` and `info`; twelve other actuator endpoints asserted absent
- No health body names a dependency, URL, host, database, driver, error or exception
- `/actuator/info` carries build identity with no timestamp, so reproducible archives stay so
- Every wait on the readiness path is bounded; `socketTimeout` deliberately left to Phase 3
- Flyway is absent from the application's runtime classpath, so ADR-0011's "no migrations at
  startup" is structural rather than a setting - and is asserted
- ADR-0015's claim that operational endpoints escape the `/v1` prefix is now verified by test in
  both directions; it was unverifiable when written

API versioning and contract publication (2026-09-02), `P0-TSK-026`:
- `/v1` applied once in the composition root to every handler under `com.finapp`; controllers
  declare no version, and the unprefixed path is proven not to be served as well
- Operational endpoints are deliberately unversioned, and that falls out of the mechanism rather
  than needing an exception - actuator has its own handler mapping
- The OpenAPI document is generated from the running application on every build and compared byte
  for byte against [`docs/api/openapi.json`](../api/openapi.json); any difference fails the build
- Each difference is labelled `BREAKING` or `COMPATIBLE`, and the failure message says what to do
- Every error code is published as a reusable response keyed by the code, pinning the `status`,
  `code` and `type` it always carries as data rather than prose
- springdoc is test-scope: the running application serves no `/v3/api-docs` and ships no
  documentation library
- ADR-0015 records the strategy, the four rejected alternatives and the deprecation policy
- Every `$ref` in the published document is proven to resolve, and the contract is proven to
  contain no test fixture - both added by review, both proven by mutation

Boundary validation and ingress correlation (2026-09-01), `P0-TSK-025`:
- Declarative constraints on the request type, rejected before any domain invocation - proven by
  counting handler entries rather than by reading the response
- Constraint failures render 422; the detail names fields and constraints and never the rejected
  values, which are the caller's own input (`INV-AUD-02`)
- `api.PayloadTooLarge` made real: a declared over-limit `Content-Length` is refused without
  reading a byte, and a chunked body - which declares no length - is bounded by a counting stream
- Filters render the contract themselves, because an exception in a filter never reaches
  `@ExceptionHandler` and would produce the container's default page
- Every request gets a correlation identifier and every response carries it, in the body and in
  `X-Correlation-Id`; the scope wraps error handling, which is what makes the contract's member
  populated rather than always absent
- An untrusted inbound header is replaced rather than sanitised, and never fails the request

Error contract (2026-09-01), `P0-TSK-024`:
- RFC 9457 problem details on **every** error path, including the four the framework raises before
  our code runs - each proven over real HTTP, each failing if its handler is removed
- No exception message, type, stack frame or framework member reaches a client; the response is
  built from the error code alone, and `ProblemDetail` has no factory taking a `Throwable`
- `ApiException` keeps the log message and the client detail in separate fields, so the unsafe
  default is unreachable rather than merely discouraged
- The JSON is decided in one place (`ProblemDetailBody`), after direct serialisation was found to
  drop the correlation identifier and render absent members as null
- Codes are namespaced, enumerable and catalogued, reconciled with
  [`ERROR_CONTRACT.md`](../architecture/ERROR_CONTRACT.md) in both directions
- The contract lives in `platform`, the rendering in `app` - a published contract must not be a
  function of the web stack under it

Delivery assumptions made executable (2026-09-01), `P0-TST-006`:
- Deduplication proven independent of arrival order, with duplicates interleaved and backwards
- The document's sharpest claim demonstrated: an order-dependent handler is **still wrong** under
  the inbox - it ends believing a completed transfer is in flight, with nothing failing anywhere
- An ordering key shown to fix it on the same deliveries, with a positive control so a handler
  that ignored messages could not pass
- A swept dedupe record admits the effect again, which is what `DATA_MIGRATIONS.md` §9 means by
  retention being a correctness bound rather than housekeeping
- "Dedupe disabled" demonstrated against the live database: dropping the inbox primary key fails
  nine tests across three classes

Outbox crash recovery, end to end (2026-09-01), `P0-TST-005`:
- The full chain asserted in one test: business fact and outbox row in one transaction, the relay
  restarted, the event published once with the correlation of the flow that produced the fact
- An instance killed with `pg_terminate_backend` mid-publication releases its aggregate, and a
  surviving instance finishes the job - the property that makes the transaction-scoped lock
  load-bearing
- A rolled-back fact leaves the relay nothing to announce, asserted at the relay rather than at
  the writer, because the consequence is an announcement nobody can retract
- Exercised through the application role, so the outbox grants are proven rather than assumed
- The criterion demonstrated: moving the write onto its own connection fails three tests, every run

Audit immutability under privilege widening (2026-09-01), `P0-TST-007`:
- `UPDATE` proven denied on **every** column of the audit trail, not merely the one a test happens
  to set, with the column list derived from the catalogue
- Column-level grants - invisible in `information_schema.table_privileges` - are checked against
  the table grant for every platform table
- Found by trying it: `GRANT UPDATE (reason)` let the application rewrite a committed record's
  justification while the whole audit suite stayed green
- Both widenings now demonstrated to fail the suite: table-level fails six tests, column-level two

Auditable-action registry (2026-09-01), `P0-TSK-023`:
- `AuditableAction`: an interface each module implements as an enum, because the platform sits
  below every business module and cannot enumerate their vocabulary
- `AuditRecord.operation` is typed, so an action outside the registry cannot be recorded at all -
  the type system, not review, is what keeps the trail's vocabulary closed
- [`AUDITABLE_ACTIONS.md`](../architecture/AUDITABLE_ACTIONS.md) and the code are one definition,
  reconciled in three directions by `AuditableActionRegistryTest`, each proven by planting the fault
- `requiresReason()` decides per action whether a justification is mandatory, enforced by
  `AuditRecord` - the decision `V009` deferred to the domain
- The registry's limit is stated: it cannot detect a privileged action that writes no record

Audit trail and database role split (2026-09-01), `P0-TSK-022`:
- `platform.audit_record`: append-only at the **privilege** level - the application role holds
  `INSERT` and `SELECT` and nothing else (`INV-HIST-03`)
- Two ordinary roles, both `NOSUPERUSER`: `finapp_migrator` owns the schema and Flyway connects
  as it; `finapp_app` is what the application connects as
- Roles provisioned by infrastructure, grants by the migration that creates each table - a role
  is a cluster object and cannot belong to one schema's migration history
- `UPDATE`, `DELETE`, `TRUNCATE`, `DROP`, `ALTER` and self-granting all proven denied, with the
  vacuity precondition asserted first because a superuser would pass all of it
- `V008` pays the grants `V002`, `V005` and `V007` each promised; every table's granted set is
  checked against its design, so too-wide fails as loudly as too-narrow
- `AuditRecord` makes all seven questions mandatory at construction; `ActorType` and
  `AuditOutcome` generate their own `CHECK` constraints, guarded hermetically against drift

Inbox deduplication (2026-09-01), `P0-TSK-021`:
- `platform.inbox_message`: the dedupe record and the side effect commit in one transaction, so
  the row exists if and only if the effect happened (`INV-IDEM-04`)
- Keyed on **(consumer, dedupe_key)**, so one event's many consumers each handle it exactly once
  rather than the first one silently suppressing the rest
- Eight concurrent instances handed the same redelivery produce one effect, counted in a
  side-effect table rather than inferred from the wrapper's return value
- A handler that throws takes its dedupe record with it, and the redelivery is then handled
- Contention is reported after a 500ms bound, not waited on: losing costs one redelivery, which
  an at-least-once transport was going to perform anyway
- An auto-commit connection is refused explicitly, because the record would otherwise commit
  alone and lose the message
- Retention documented as a correctness bound (`DATA_MIGRATIONS.md` §9); every record is terminal,
  so there is no "never sweep a non-terminal record" caveat

Outbox relay (2026-09-01), `P0-TSK-020`:
- Every instance polls; a transaction-scoped advisory lock **per aggregate** means one instance
  drains a given aggregate at a time, so ordering survives concurrency instead of depending on
  there being one relay
- At-least-once, said plainly: a crash between publishing and recording it republishes, proven by
  a publisher that delivers and then dies
- Exponential backoff with a ceiling, attempt counting, and a poison path that **blocks** its
  aggregate rather than skipping it, so consumers never get an undetectable gap
- Eight concurrent instances drain a 36-event backlog with no duplicate and no loss
- `V006` adds `next_attempt_at`, `dead_lettered_at` and `last_error`, with eligibility and
  abandonment decided by the server's clock; three new constraints, all exercised at the schema
- Publishes through an `EventPublisher` port; no broker client on the classpath, so the
  direct-publish rule still exempts nothing

Transactional outbox (2026-09-01), `P0-TSK-019`:
- `platform.outbox_event`: the full envelope as columns, all ten NOT NULL, so an untraceable
  event cannot be queued any more than it can be constructed
- `INV-EVT-01` proven both ways: a rolled-back fact loses its outbox row, a committed one keeps it
- `nothingPublishesToABrokerDirectly` fails the build on a direct publish anywhere, matched by
  package name so the rule exists before the dependency does
- Correlation now proven to reach a third sink — the outbox row — closing one of `P0-TSK-014`'s
  deferred clauses

Event envelope (2026-09-01), `P0-TSK-018`:
- `EventEnvelope`: all ten `INV-EVT-03` fields mandatory at construction, so an untraceable
  event cannot be built; the field set and the null checks are derived from the record by test
- Metadata only — no payload — so relays and consumers handle events they cannot deserialise,
  and no log line can spill event contents
- `EventId` as a typed, time-ordered `EntityId`: the value an inbox deduplicates on
  (`INV-IDEM-04`), so it is fixed when the event is created rather than regenerated on redelivery
- Canonical form pinned by exact-match test; `eventVersion` and `schemaVersion` distinguished
  and documented
- Correlation and causation **identifiers** moved to `sharedkernel`; the context mechanism stays
  in `platform`

Idempotency failure modes (2026-09-01), `P0-TST-004`:
- Contention proven by observing PostgreSQL's own lock waits rather than by hoping threads
  overlap, so the test cannot pass while contention is broken
- Lost response, expired key, swept key, and a crashed instance mid-command each driven to a
  single effect
- Dropping the unique constraint fails 17 tests, demonstrated against the live database

Idempotent execution (2026-09-01), `P0-TSK-016`:
- Claim, execute, record outcome — all in the caller's transaction, so a crash cannot leave an
  effect without a record or a record without an effect
- 8-way concurrent duplicates: one execution, one effect, eight identical responses
- `INV-IDEM-03` enforced by fingerprint comparison, refusing rather than guessing when the
  algorithm differs
- A live `IN_PROGRESS` claim is reported; a stale one is taken over, with the staleness test in
  the database so two reclaims cannot both win
- The data-access mechanism stays undecided: the wrapper depends on a port

Idempotency schema (2026-09-01), `P0-TSK-015`:
- `platform.idempotency_record`: `INV-IDEM-01` enforced by a unique key on
  (scope, idempotency_key), proven under 16-way contention against a real PostgreSQL
- `V003`: terminal claims frozen and identity immutable, enforced by trigger because a `CHECK`
  constraint cannot see the previous row (`INV-LIFE-04`)
- The state machine checked in the schema, not only in code: an `IN_PROGRESS` claim cannot
  carry an outcome, a terminal one must be timestamped
- Fingerprint length bounded and its algorithm recorded, so `INV-IDEM-03` cannot be weakened by
  truncation or by a silent algorithm change
- `IdempotencyState` and the `CHECK` constraint generated from one definition, guarded
  hermetically

Correlation kernel (2026-09-01), `P0-TSK-014`:
- `CorrelationId` / `CausationId`: distinct types, validated against log injection with a
  default-deny charset, bounded, rejected rather than sanitised
- `Correlation`: correlation inherited, causation replaced by the emitting message, so the
  causal tree survives rather than flattening
- `CorrelationContext`: scope entry/exit with restore-not-clear, and explicit capture at
  submission so a pooled worker never inherits an unrelated flow
- Log lines proven to carry the identifier by reading a real appender, not by mocking a logger
- No Spring: the kernel is framework-free and serves an HTTP filter, a job and a consumer alike

Time discipline (2026-09-01), `P0-TSK-013`:
- Ambient time is a build failure: no zero-argument `now()`, `System.currentTimeMillis()`,
  `nanoTime()` or `new Date()` anywhere in production code
- `Clock.systemUTC()` permitted in the composition root alone, proven from both sides — the
  root is exempt, another module is not
- `Instant.now(clock)` and `LocalDate.now(clock)` deliberately allowed
- `TestClock` moves time forwards, backwards and to an instant, so time-dependent behaviour is
  tested at boundaries rather than by sleeping
- Posting date, value date and system time distinguished in `DOMAIN_MODEL.md` §Time

Identifier kernel (2026-09-01), `P0-TSK-012`:
- `EntityId`: typed per-aggregate identifiers; substitution is a compile error, proven by
  invoking `javac`; identity includes the concrete type so two kinds never compare equal
- `IdGenerator`: UUIDv7, monotonic within a millisecond, through counter exhaustion, and
  across a backwards clock; clock and randomness injected
- Concurrency proven at 80,000 identifiers across 16 threads on one frozen millisecond
- ADR-0013 records the decision, the rejected alternatives, and the creation-time disclosure
  a UUIDv7 inherently carries

Allocation residual proof (2026-09-01), `P0-TST-002`:
- `INV-BAL-03` swept across the criterion's full 1..100 range for even allocation and up to 100
  weights for weighted allocation, with amounts from the whole representable range
- Both sweeps assert they actually encountered indivisible remainders, so neither can pass by
  allocating only divisible amounts
- Evenness asserted separately from totality, since a first-part-takes-all allocator satisfies
  totality

Kernel property tests (2026-09-01), `P0-TST-001`:
- `MoneyPropertiesTest`: commutativity, associativity, additive identity and inverse,
  subtraction as negated addition, multiplication as repeated addition, and the reversal
  round-trip, over 20,000 generated trials per law across JPY (0), USD (2) and BHD (3)
- Exactness checked against `BigDecimal` as an independent implementation
- Rounding bounded within one minor unit, with each policy pinned by its defining direction
- Every law asserts its own coverage, so it cannot pass by rejecting everything

Rounding and allocation (2026-08-31), `P0-TSK-010`:
- `RoundingPolicy`: six named policies with a stable name for `INV-HIST-04` recording
- `Money.of(BigDecimal, CurrencyCode, RoundingPolicy)` — rounding requires a named policy
- `Money.allocateEvenly(int)` and `Money.allocateByWeights(long...)` — the parts always sum
  back to the original, so no residual is ever absorbed (`INV-BAL-03`)

Money persistence (2026-08-31), `P0-TSK-011`:
- `MoneyColumns` in `platform`: the single definition of the three-column shape from ADR-0003,
  with the DDL fragment migrations use so the shape cannot drift between tables
- Round-trip verified against a real PostgreSQL, including the `BIGINT` extremes
- Mechanism-agnostic: no ORM is chosen, so none is chosen by accident

Project initiation (2026-08-31):
- Master delivery plan for all seventeen phases — [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md)
- Phase gate model, status model and per-phase exit criteria — [`PHASE_GATES.md`](PHASE_GATES.md)
- Engineering backlog with Phase 0 elaborated to task granularity — [`BACKLOG.md`](BACKLOG.md)
- First architecture baseline — [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- Invariant catalog, 64 invariants — [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md)
- Definition of Done with task-type profiles — [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md)
- Execution protocol — [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md)
- ADR-0001 through ADR-0010 (`Proposed`) — [`docs/adr/`](../adr/README.md)
- Roadmap with sequencing rationale and seam register — [`ROADMAP.md`](../product/ROADMAP.md)

