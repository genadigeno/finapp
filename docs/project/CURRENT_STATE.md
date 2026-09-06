# Current Project State

**This document is the canonical description of where the project is.**
Conversation history is not. Read this first in every session
([`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Working Session Procedure).

Last updated: 2026-09-06

---

## Current Phase

**Phase 0 — Domain and Architecture Foundation**
Status: ✅ **`COMPLETE`** (2026-09-04) — **all twelve exit criteria hold.**

**Phase 1 — Identity and Customer Foundation**
Status: **`IN_PROGRESS`** — entry gate passed, all twelve criteria. Started 2026-09-04.

## Current Milestone

**M1.2 — That person can authenticate.** `P1-TSK-007` … `P1-TSK-012`; **5 of 6 complete.**
Objective: password authentication that is enumeration-safe and cannot be brute-forced. Credentials
are stored and verified, and a weak one is upgraded on use; nothing calls it over HTTP yet.

**M1.1 — A person exists and is registered.** `P1-TSK-001` … `P1-TSK-006`; **6 of 6 complete**
(2026-09-06). Objective: one transaction creates a Party, a Customer and an Identity, and the three
are provably separate. **Met**, over real HTTP against a real PostgreSQL.

**The credential is not part of it**, and that is `P1-TSK-006`'s recorded consequence rather than an
oversight — see §Just completed and the new `P1-TSK-026`.

The formal Phase 0 → Phase 1 transition was conducted on 2026-09-04:
[`reviews/PHASE_0_TO_1_TRANSITION.md`](reviews/PHASE_0_TO_1_TRANSITION.md), with an addendum
recording the close.

**Phase 0 closed on its last criterion, and the closing was not a formality.** A git remote was
added and the CI workflow executed for the first time (`P0-TSK-042`): run
[33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202), all four jobs green —
606 hermetic tests, 173 database tests, migrations applied to an empty database and re-applied
idempotently, 119 commits scanned, SBOM clean. It took **three runs**. The two failures were
defects no local run on this machine could reach, and neither was in what Phase 0 designed:
`gradlew` committed without its executable bit, and dependency-verification metadata that was
complete only for a *warm* cache. Both are recorded under Change Log and in the review addendum.

**The transition's judgement was tested by that.** It had declined to record Phase 1 as `READY`
while criterion 7 failed, on the argument that the blocking criterion was the one whose purpose is
to prove the gates execute at all. The first CI run failed. Had the gate been waived, Phase 1 would
have been entered on a build that could not run anywhere but one Windows machine.

### What Phase 1 will build

Objective: **a Party can exist, become a Customer, hold an Identity, prove it, hold a session with
a recorded assurance level, and have every privileged action authorised and audited** — with no
money, no account and no ledger anywhere in it.

Six aggregates in three bounded contexts (`party`, `identity`, `audit`), nine tables, fourteen
endpoints, seven milestones, 25 backlog items. Planned in
[`PHASE_1_PLAN.md`](PHASE_1_PLAN.md); decisions in ADR-0029 through ADR-0032; the properties it
must protect are the new `INV-IDN-01`…`INV-IDN-07` group in
[`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md).

**The transition's own finding**: the seven identity properties existed only as Phase 1 *exit
criteria* prose — no stable ID, no ranked enforcement mechanism, no named verification method, no
mutation-demonstration row. That is a materially weaker regime than every other property on the
platform gets, and it was backwards: Phase 1 is the phase whose *product* is security. Catalogued
before any credential-handling code is written against prose.

Entry gate passed on 2026-08-31. All twelve entry-gate criteria in
[`PHASE_GATES.md`](PHASE_GATES.md) §2 are satisfied: the delivery plan is written, bounded
contexts and module boundaries are defined, the invariant catalog exists, the backlog is
elaborated to task granularity, and ADR-0001 through ADR-0012 are recorded as `Proposed`.

Phase 0 delivers a buildable, boundary-enforced modular monolith containing the financial and
platform kernel, with **zero business capability**. That constraint is deliberate: money
representation, idempotency, outbox, audit and correlation cannot be retrofitted once
financial history exists.

## Current Milestone

**M0.5 — Test infrastructure and phase review**
`P0-EPIC-11` and `P0-EPIC-12`, both **COMPLETE**. The last milestone of Phase 0, and with it the
phase backlog.

The test infrastructure is finished. The suite brings its own database (`P0-TSK-035`), knows what
kind of test each of its members is (`P0-TSK-036`), can make a provider fail in every way the
platform says it must (`P0-TSK-037`), and now records - and enforces - that every Phase 0 invariant
has a test demonstrated to **fail** when the invariant is broken (`P0-TSK-038`).

The glossary is written and the phase review is conducted. All 28 ADRs are now `Accepted`. What
remains is not backlog work: the review found two gate failures, and both are recorded under
Blockers rather than left to be discovered at the gate.

**M0.4 — API, observability and security baseline** — `P0-EPIC-08`, `-09` and `-10`, all
`COMPLETE` (2026-09-02).

The platform gained a versioned HTTP surface with an RFC 9457 error contract on every path and a
published OpenAPI document compared byte for byte on every build; correlation on every log line,
span and durable record, metrics whose names the build enforces, and a dashboard verified against a
running instance; and a security baseline in which the unsafe option is generally *unreachable*
rather than discouraged — no credential literal in committed configuration, no unestablished actor,
no unclassified column, no remote database without verified TLS, no single-instance coordination
primitive, and no artefact whose bytes or version nobody recorded.

One exception is recorded rather than hidden: `P0-TSK-017` (`Idempotency-Key` header) remains
`BLOCKED` — the HTTP surface it waited for now exists, so it is unblocked in fact and needs
rescheduling rather than unblocking.

**M0.3 — Correctness primitives** — `P0-EPIC-05`, `P0-EPIC-06` and `P0-EPIC-07`, all `COMPLETE`
(2026-09-01), with one exception recorded rather than hidden: `P0-TSK-017` (`Idempotency-Key`
header) is `BLOCKED` on the HTTP surface `P0-EPIC-08` brings in M0.4, and moves with it.

Every claim the milestone was for is now enforced and proven: money-moving commands are idempotent
under genuine concurrency; domain facts and their publication records commit together via an
outbox whose relay is safe across N instances; consumers deduplicate through an inbox; and
privileged actions produce append-only audit records the application role cannot edit — enforced
at the database privilege level, not in code.

**M0.2 — Financial kernel** — `P0-EPIC-03` and `P0-EPIC-04`, both `COMPLETE` (2026-09-01).

**M0.1 — Buildable, boundary-enforced skeleton** — `P0-EPIC-01` and `P0-EPIC-02`, both
`COMPLETE`. Three of its four completion criteria are met; the fourth, "green **in CI** from a
clean clone", cannot be met while the repository has no git remote. A clean clone was verified
to reach a green build locally during `P0-DOC-001`.

Remaining Phase 0 milestone:
- **M0.5** Test infrastructure and phase review — `P0-EPIC-11`, `P0-EPIC-12`

## Current Task

**None in progress.** `P1-TSK-011` completed 2026-09-06. **M1.2 is 5 of 6.**
**Next: `P1-TSK-012`** — `P1-TST-002`, the authentication failure modes.

### Just completed

**`P1-TSK-011` — Brute-force and credential-stuffing controls** — `COMPLETE` (2026-09-06).

**The task is two controls with two keys, and separating them is the whole design.**

| | Key | Stops | May it change cost or response? |
|---|---|---|---|
| **Lockout** | the identity | credential *guessing* | **No** |
| **Rate limit** | the source | resource *exhaustion* | Yes — it says nothing about any account |

Conflating them produces the defect the instinct leads straight to: refuse a locked account *without*
paying for a derivation, which is the CPU relief lockout appears to be for — and is an
**account-existence oracle**. Attempt often enough against any identifier; if it exists it locks, if
not nothing happens, and afterwards the locked one answers in a millisecond while the unknown one
still costs ~46 ms. `INV-IDN-07` lost to the control added beside it. So **a lock costs exactly what
every other failure costs**, asserted by counting derivations.

### Per-source is not built, and building it would be harmful

`SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution commits to **N replicas behind a load balancer**,
so `getRemoteAddr()` is the balancer: every user shares one bucket, the threshold is reached in
seconds, and **authentication goes down for everyone**. `X-Forwarded-For` is caller-supplied, and
ADR-0034 settled that caller-supplied values are not trusted — there is no trusted-proxy
configuration anywhere here. The missing input is a **deployment topology**, not effort. Recorded as
debt with that trigger.

### `INV-CON-03`, three phases early

*"Limits enforced non-atomically are limits that do not exist."* Catalogued at Phase 13; enforced
here because ADR-0032 makes verification expensive and names lockout as part of the same design.

**One row per identity, not one row per attempt.** An append-only attempt log is this platform's
usual idiom and is wrong here: counting rows in a window is a read-then-count, so ten concurrent
attempts at the threshold all read nine and all proceed. The whole protocol is one statement —
`INSERT … ON CONFLICT DO UPDATE … RETURNING` — where the post-increment count is produced **by the
write**. Ten simulated instances, own connection each, produce exactly ten.

**Keyed on the login identifier, resolved by a subselect inside the same statement.** The obvious
alternative — look the identity up, then record if found — runs one query when the account is absent
and two when it is present, which is a timing difference that discloses existence. It also keeps
`VerificationOutcome` opaque: having verification *report* which identity it tried would put back
exactly the field `P1-TSK-008` removed, and its reflective guard would fail.

**The lock is time-bounded and self-healing, with no operator unlock.** Lockout is itself an attack —
anyone who knows a login identifier can lock its owner out by failing ten times — and a lock needing
an operator to clear it converts that cheap attack into a support-desk denial of service, and gives
an insider a standing reason to touch other people's accounts.

### The platform's own guard found the one defect

`recordFailure` wrote its audit record **outside** the security scope, and every lockout test failed
with *"no actor has been established for this flow"*. That is `P0-TSK-032`'s refusal to default the
actor doing precisely its job: a default would have accepted the mistake silently and recorded the
wrong party — permanently, under `INV-HIST-03`.

### The completion gate found the lock was permanent

**The most serious defect this phase has produced, and the suite passed over it.** After a lock
expired, **one** failure re-locked the account for another full period — so an account locked once
was locked **for ever**, at one attempt per lock period. That is precisely the attack the design
claims to avoid and the migration's own comment says cannot happen.

**Why the tests missed it.** `anExpiredLockClearsItself` authenticates *successfully* after the lock
expires, and a success deletes the row — so the path where the next attempt is another **failure**
was never exercised.

**Why the code was wrong.** The reset was guarded on `locked_until IS NULL AND the window elapsed`.
Since `window_started_at` always precedes `locked_until`, an expired lock implies an expired window
— so the `IS NULL` half blocked the reset at exactly the moment it was due.

**And the first fix was still wrong, which the new test caught.** Relaxing it to *"no live lock and
the window elapsed"* works only because the shipped policy makes window and lock both 15 minutes.
That is coincidence, not equivalence: `LockoutPolicy(3, 60min, 1min)` is legal and expires the lock
while the window is live, restoring the permanent lockout. The rule had to be stated as two
independent clauses:

- **a served lock ends the run**, whatever the window says;
- **an elapsed window ends it**, provided no lock is live — without which an attacker waits out the
  window instead of the lock.

Both are now proven load-bearing by separate mutations, and a test runs under a policy whose window
and lock differ, so the coincidence cannot hide it again.

**Seven mutations, all caught.** 737 hermetic tests, 272 database tests.

### Previously

**`P1-TSK-010` — `POST /v1/authentications`, enumeration-safe** — `COMPLETE` (2026-09-06). The
platform's second endpoint, and the first that can say *no* without saying *why*.

| Acceptance criterion | Evidence |
|---|---|
| One response shape for every failure (`INV-IDN-07`) | `everyFailureLooksTheSame` — the four causes asserted **equal to each other** |
| Equivalent cost for every failure | `everyFailingPathDoesTheWork` — five paths, one derivation each, **counted** |
| A success is audited and announced | Audit record with a real actor; `identity.AuthenticationSucceeded` |
| A failure is audited **and committed** | The refusal is returned, never thrown |

**The plan contradicts itself about the session, and the contradiction is recorded rather than
resolved by an implementation task.** `P1-TSK-010` declares `Deps: P1-TSK-013`, which is `TODO`;
`PHASE_1_PLAN.md` §11 puts *session issuance* in **M1.2's scope** and makes M1.2's acceptance *"an
identity authenticates and receives a session"* — while numbering the session tasks into M1.3.
Fourth backlog defect of this class. Unlike `P1-TSK-006`'s, here the **`Deps` line is right and the
milestone boundary is wrong**: "that person can authenticate" is not a milestone without a session.

**So this delivers the endpoint and not the session.** A success returns **204 with no body**, and
**M1.2 cannot close on `P1-TSK-012`**. Carried as the new **`P1-TSK-027`**.

**The refusal is returned, never thrown, and that is structural.** A failed authentication writes an
audit record in the same transaction — the only way credential stuffing is visible at all — so
throwing to produce the 401 would roll it back and destroy it. Proven: making the service throw
fails the suite.

**Not idempotent, and that is stronger than "the money-moving clause is vacuous."** An idempotency
key is explicitly *not a secret* (`API_CONVENTIONS.md` §6), so a stored success keyed on one would
let anybody who saw the key in a proxy log **replay a successful authentication**. The mechanism
that makes registration safe would make this endpoint an authentication bypass.

**The platform's first real actor.** A success is attributed to `Actor(identityId, CUSTOMER)`; a
failure to the platform, because there may be no identity at all. The asymmetry is *correct
information* rather than a channel — registration's uniformity argument does not transfer, because
there the success actor would have been circular and here it is proven. `PHASE_1_PLAN.md` §5 says
the `enterSystem()` call sites are revisited in this phase; this is the first one with a better
answer available.

### The build found a real defect in the request DTO, and it was right

`secretsAreWrapped` rejected `String password` — twice, once for the component and once for the
accessor a serialiser reads. **The rule was right, and this is the most important subject it has
had.** A record's generated `toString` prints every component, so `log.info("{}", request)` would
print a customer's password: no getter call, no concatenation, nothing a reviewer stops at. This DTO
is the exact place a plaintext enters the platform, which makes it the first place it could leave.

Wrapping it required a Jackson **deserialiser** — the symmetric half of `P0-TSK-030`'s masking
serialiser, which had never been needed because no request body had ever carried a secret.

### Two contract defects, both found by generating the document rather than reasoning about it

**The wrapper published as an empty schema.** `Sensitive<T>` has no accessible property, so springdoc
emitted `password: {$ref: SensitiveString}` pointing at `{}` — a generated client would model a
password as an untyped object and would not know to send a string. Every other check passed over it:
it parses, it diffs, and `everyReferenceResolves` is satisfied because the schema exists. Fixed by
telling springdoc the wire type, in **test scope**, because the running application ships no
documentation library (ADR-0015) — and a new guard, `everyPublishedSchemaSaysWhatItIs`, now fails
the build on any empty schema, because the next wrapper will not be called `Sensitive`.

**`P1-TSK-009`'s contract guard fired on this task, one task later — which is what it was for.** It
was right to fire and wrong about why: it forbade a credential-named member *anywhere*, and an
authentication request body must declare a password or no client can call the endpoint. The precise
property is narrower and more useful: **a secret may be sent, never returned, and never put where a
URL or a header goes** — those reach access logs, proxies and browser history. The permitted set is
pinned to two named request schemas, so widening it is visible.

### One mutation survived and produced a new test

Making a malformed password fail **without doing the derivation** left all four responses
byte-identical and changed only how long one of them took. `everyFailureLooksTheSame` could never
have caught it — it compares responses, and the disclosure is through the clock.

That is `P1-TSK-008`'s own finding — *assert by counting work, not by reading a clock* — reproduced
one task later by the person who wrote it down. Closed by `AuthenticationCostsTheSameDatabaseTest`,
which counts derivations across **five** failing paths and one success.

**A second mutation survived correctly**, and that is worth separating from a miss: making the
refusal echo the attempted identifier changed only the **log** message, because `ApiException` keeps
that separate from what a client is told (`P0-TSK-024`). The mutation was aimed at the wrong field.
Re-aimed at the first step a diverging response would actually need — a `reason` field on
`VerificationOutcome` — it is caught by `P1-TSK-008`'s reflective guard.

### The completion gate found a documented test that did not exist

`AuthenticationRequest` restates `LoginIdentifier`'s bounds as literals, and its javadoc said *"a
test asserts they still match."* **No such test existed** — while the sibling `RegistrationRequest`
had one all along, which is what made the sentence read as true.

The drift it claims to guard is real: if the domain charset narrowed, the boundary would accept a
value the domain then refuses, `new LoginIdentifier(...)` would throw inside the service, and the
caller would get `api.InternalError` — **our fault reported for their input**, which
`ERROR_CONTRACT.md` §3 forbids and which a client may retry for ever. `AuthenticationRequestTest`
now sweeps **every code point the boundary admits** rather than sampling, because nobody guesses in
advance which character the mismatch will be.

### Three more, all found by probing shapes the code was not designed against

**No request shape produces a 500** — eight of them driven over real HTTP: absent, null, empty,
numeric, object, array and oversized passwords, and a login identifier the charset refuses. All land
on 400, 401 or 422. Now pinned by test, including the two decided by *different* mechanisms: a
number is **coerced** and fails authentication, an object **never reaches the deserialiser** and is a
400.

**A "hardening" fix that was unreachable code.** The gate first added a null-check to the
deserialiser for the object case, with a comment explaining what it handled. Probing Jackson
directly showed it throws `MismatchedInputException` **before** the deserialiser runs — so the branch
never executed and its comment described a mechanism that is not the real one. Removed, and the
comment now states what was measured. A fix that reads correctly and does nothing is the shape this
repository keeps meeting; the only thing that separates it from a real one is running it.

**The correlation exclusion was a hole, not an allowance.** `everyFailureLooksTheSame` strips the
correlation identifier before comparing, so its *absence* would have been invisible — four responses
missing it entirely would still compare equal. The raw value is now asserted present first.

**The throttling debt was named in the design and had not been recorded.** Now a row: this endpoint
is a **CPU and memory amplifier**, because ADR-0032 makes each attempt cost ~46 ms and ~19 MiB *by
design* — the work factor that protects a stolen credential store is the same work factor an attacker
spends for free.

**Six mutations, all resolved.** 737 hermetic tests, 260 database tests.

### Previously

**`P1-TSK-009` — `P1-TST-001`: credentials never leak** — `COMPLETE` (2026-09-06).

**The stated acceptance criterion was already met, and checking rather than assuming is what made
the task worth doing.** *"Fails when a credential field is added without wrapping"* is
`secretsAreWrapped`, which `P0-TST-008` found **structurally incapable of failing** and then fixed.
Probed here against **real production code** rather than the fixture — an unwrapped `String
lastPassword` planted in `CredentialVerifier` fails the build twice, once for the field and once for
the accessor. Implementing that criterion again would have been a second copy of a working rule.

**So the deliverable is the gap between the task's two clauses**, which are not the same claim. The
field rule governs what a **type stores**. It cannot see a secret held only in a **local**, one
inside a message the platform did not write, the **MDC**, an **event payload**, a **metric tag** or
a **span attribute** — none of which is a field on our types.

| Sink | What was added |
|---|---|
| **Log** | `CredentialNeverReachesALogTest` — `RawPassword` and `Credential` logged the careless way, through the **real ECS encoder on captured output**, with a negative control |
| **Log (production)** | `CredentialVerifierLogsNothingSensitiveDatabaseTest` — the platform's *one* production log call on the credential path, driven by a real injected failure against a real PostgreSQL |
| **Event** | `EventPayload` **cannot carry a derivation at all**, asserted rather than left coincidental |
| **Response** | The published contract declares no member whose name says it holds a secret |
| **Span, metric** | **Cited, not duplicated** — `MetricConventionTest` and ADR-0017 already fail the build on these |

**Three of the five named sinks have no credential-carrying producer yet**, and that is a reason to
write the guard now rather than to defer it — but **the artefact has to be the right one**. An
assertion that a credential is absent from an empty event stream passes vacuously, which is the
"green while checking nothing" shape this repository has met six times. So for a sink with no
producer this asserts **the mechanism that will refuse the producer**, which has a subject today and
becomes load-bearing the moment `P1-TSK-010` or `P1-TSK-026` lands.

**The recorded Phase 0 debt was answered rather than carried forward.** *"No output scrubber for
text the platform does not control"* named its trigger as *"a business module logging real flows"*
and its owning phase as Phase 1; `identity` is that module. **The scrubber is not built**: it is a
deny-list, and to recognise a secret it must be *given* the secret — so the plaintext travels
**further**, into a filter on every log statement, rather than less far.

**What replaces it is the opposite shape and is checkable.** A plaintext reaches a sink only if
something first *unwraps* it, and every unwrap is a call to `expose()` — named to be found,
deliberately. `SecretsAreUnwrappedInOnePlaceTest` pins that set to **four production classes, all in
`identity`**, so a new unwrap anywhere fails the build. It reads method **references** as well as
calls, because a method reference is an `invokedynamic` with no call site — the bypass the
`P0-TSK-013` review found in the ambient-time rule.

**The limits are stated rather than glossed**, in the tests themselves:

- **`EventPayload` is a charset, not a secret detector.** A password of `hunter2` satisfies
  `[A-Za-z0-9_-]` perfectly and would be published. What actually keeps credentials out of events is
  that no event declares a credential field — a `P1-TSK-010` design property, not this one. A test
  asserts the *acceptance*, so the limit is visible rather than inferred.
- **The unwrap whitelist says where, not what happens next.** Inside `identity` a plaintext could
  still be handed to a log call and nothing mechanical would catch it.

**One mutation reported SURVIVED and had never landed** — the fourth occurrence of that class here.
The planted contract member targeted `"loginIdentifier" : {`, and the document is formatted
`"loginIdentifier": {`, so the replacement matched nothing and the guard was reported as toothless
when it had never been tested. Re-run with the marker **asserted present before the edit**, it is
caught. Checking that a mutation actually landed is the only thing that separates a proof from a
reassuring message.

### The completion gate found the rule's vocabulary was one third dead

**Measured, not read.** `secretsAreWrapped` splits a field name on camel-case boundaries and
compares each **word** against a vocabulary — and that vocabulary uses **compound** forms where the
bare word has innocent uses, exactly as ADR-0019 and the rule's own javadoc describe. Those two
facts cannot both be delivered: the splitter turns `apiKey` into `[api, Key]`, so a compound entry
can never match.

**Six of the twenty-two entries were structurally unreachable** — `apikey`, `privatekey`,
`signingkey`, `cardnumber`, `mfacode`, `sessionid`. `secretKey` was caught, but only by accident,
because `secret` is separately an entry.

**Proven in both directions against the build**, not argued: a production `String cardNumber` field
**passed cleanly** before the fix and fails after it. That is the PAN field ADR-0019 added
specifically so that widening PCI scope *"fails the build rather than arriving quietly"*, and
`sessionId` — Phase 1's next subject, three tasks away — was in the same state.

**This is `P0-TST-008`'s finding again, one layer in.** That review found the rule *could not fail
at all*; this found that a third of what it claims to check, it does not. A control reporting
coverage it does not have is worse than none because it is believed — and `P1-TST-001` exists
precisely because this rule's teeth were once imaginary.

Closed by matching **adjacent word pairs** as well as single words. Precise rather than fuzzy:
`idempotencyKey` yields the pair `idempotencykey`, which is not in the vocabulary and stays clean,
as do `companyName` and `spinLock`. Substring matching would have caught the six and reintroduced
exactly the false positives ADR-0019 excluded `key` to avoid. One fixture per dead entry, because an
aggregate probe reporting "caught" says nothing about which of six it caught.

### Three further gate findings, all in this task's own work

**The coverage guard could not see a module leave the sweep.** It asserted
`contains("identity", "platform", "sharedkernel")` where every sibling rule suite asserts
**equality** against `ProductionModules.onClasspathWithProductionClasses()`. Narrowing the sweep so
`app` was never analysed left it green — and `app` is where a credential would most plausibly reach
a response, since that is where the controllers are. The **exact** finding the `P0-TSK-008` review
made, with the corrected idiom sitting two files away. Deviating from a sibling idiom is what hid
`secretsAreWrapped`'s inversion in the first place.

**The contract guard read keys and not values.** OpenAPI declares a parameter as
`{"name": "token", "in": "query"}` — the secret name is a **value** there, so a key-only scan
reported a clean contract for `GET /v1/things?token=…`. Found by probing shapes it was not designed
against. Its remaining limit is now asserted rather than implied: it matches names, so a credential
in an `example` **value** is not caught, and the control for that is the repository-wide secret scan.

**The secret vocabulary had already drifted.** This task's second copy was missing `signingkey` and
`cvv2`, so a `signingKey` property could have reached the published contract while the build rule
forbade the field holding it. Reconciled by test rather than merged — the two lists stay where they
are and disagreeing fails the build.

**One mutation reported SURVIVED and had never landed, twice.** First the contract member, whose
marker did not match the document's formatting; then three vocabulary probes whose backup file had
silently failed to be written, so every result read `NOT CAUGHT` against unmutated code. Fifth and
sixth occurrences of that class here. Every mutation in this task is now applied with the plant
**asserted present before the build runs**.

**Eleven mutations, all caught.** 732 hermetic tests, 250 database tests.

### Previously

**`P1-TSK-008` — Verification and upgrade-on-use** — `COMPLETE` (2026-09-06). The platform can
decide whether somebody knows the secret, and strengthens the credential while it is legitimately in
hand.

| Acceptance criterion | Evidence |
|---|---|
| A credential under weak parameters verifies and is upgraded | `aWeakCredentialIsUpgraded`: old superseded, new at policy, same password still works |
| Timing for an absent identity is equivalent to a wrong credential (`INV-IDN-07`) | `everyFailingPathDoesTheWork`, counting derivations rather than reading a clock |
| The store converges without a forced reset | The same test; nothing is asked of the customer |

**Every failing path performs a full Argon2id verification** — four of them: no identity, an
identity that cannot authenticate, an identity with no credential, and a wrong password. The middle
two are the ones an implementation skips, and they are the ones that matter: **a suspended account
answering instantly tells an attacker both that it exists and that it is suspended.** That is
`INV-IDN-07` lost through the timing channel rather than the response body, which is the harder half
to notice and the harder half to test.

**Asserted by counting work, not by reading a clock.** A wall-clock timing test is flaky and
measures the machine; a counting deriver is deterministic and measures the property actually at
stake — *did the expensive path run at all?* Each failing path is asserted to perform exactly one
verification.

**`VerificationOutcome` carries no reason, and that is structural.** A failure has no reason code, no
status, no `Optional` that is empty in one case and populated in another — and every failure is
literally the *same object*, so not even reference identity distinguishes them. A caller branches on
whatever it is handed; a `reason` field is an enumeration oracle with a delay fuse, harmless the day
it is added and a second response shape the day somebody maps it to a message. A test derives the
type's members reflectively, so adding one fails the build.

**An upgrade failure never fails a correct authentication.** The customer typed the right thing;
refusing them because a background optimisation collided would be a self-inflicted outage. The
upgrade sits behind a savepoint and every failure of it is discarded — proven by injecting one and
asserting both that the login succeeds *and* that the credential is left intact rather than
superseded with no replacement, which would lock the person out permanently.

**This is the platform's first genuine read-then-write**, so `P1-TSK-007`'s claim that there is "no
read-then-write anywhere" does not extend here and is not relied on. What makes it safe is that the
write is **conditional**: `supersede` moves the row only while it is still `ACTIVE`, and its row
count is the outcome.

### Two defects found while building it

**An auto-commit connection made the upgrade silently not happen.** `setSavepoint` throws on such a
connection, and the upgrade's catch-all discarded it as an ordinary collision — so every login would
have verified correctly and upgraded **nothing, permanently**, with a warning nobody reads and no
test failing. A control reporting success for work it did not do, which is the shape this repository
keeps meeting. Closed by refusing an auto-commit connection **up front**, with a message about the
mistake rather than about the mechanism — the `JdbcInboxRecordStore` precedent.

**A surviving mutation showed the concurrency test asserted the outcome rather than the
coordination.** Ignoring the conditional supersede's answer *still* produced exactly one upgrade —
because the nine losers then collided with the partial unique index and the catch-all swallowed it.
Same result, worse mechanism, and the javadoc's claim that *"the index is never even reached"* would
have been false with nothing failing. The test now counts **inserts attempted**: one, not ten.

**A third security-rule false positive, answered by narrowing the rule compositionally.**
`secretsAreWrapped` fired on a `CredentialStore` collaborator field and on a private factory
returning a `RawPassword`. Renaming was not available — the `identity` module's collaborators are
named after credentials because that is what they are for. The narrowing is principled rather than
convenient: **a field whose type is one of our own types is already checked at its own declaration**,
so wrapping the reference protects nothing, and `Sensitive<RawPassword>` would double-wrap a type
whose whole job is to wrap. It touches no JDK type, which is where a secret actually lives, and it is
**proven load-bearing** — removed, the rule fires three times again.

### The completion gate found a leak in the mechanism protecting the secret

**PostgreSQL puts the entire refused row in a `CHECK` violation's `DETAIL`**, and the driver puts
that in `SQLException.getMessage()`. Probed rather than reasoned about:

```
ERROR:  new row for relation "credential" violates check constraint
        "credential_derivation_is_encoded"
DETAIL:  Failing row contains (…, ARGON2ID, 1024, 1, 1, hunter2-my-actual-secret-password, …)
```

Our storage exceptions carried that `SQLException` as a cause, so **the constraint that exists to
stop a plaintext being stored caused the plaintext to be logged when it fired** — `INV-AUD-02`
defeated by the mechanism protecting `INV-IDN-01`.

**It was never confined to credentials.** The same `DETAIL` carries a person's name out of
`party.party` (`display_name`, `RESTRICTED-PII`, and `V003`'s control-character `CHECK` fires on
exactly the input a caller controls) and a login identifier out of `identity.identity`
(`CONFIDENTIAL`, because it carries existence). Three tables, one defect, introduced progressively
across `P1-TSK-005`, `-006` and `-007` — each new `CHECK` constraint made it reachable in one more
place.

**Closed by making the safe path the only path.** `DatabaseFailure.describe` keeps the operation,
the identifier and the **SQLState** — five characters saying which class of failure — and drops the
`SQLException` entirely. The three storage exceptions **no longer have a constructor taking a
cause**, so attaching one is a compile error rather than a decision somebody makes at 5pm. The cost
is real and accepted: an operator loses the driver's own frames on these tables, which is a smaller
loss than a log aggregator holding passwords with months of retention.

**The guard that survives is the reflective one.** An earlier version drove the `party` path and
could pass vacuously — it guarded on whether anything was thrown at all, which is the "green while
checking nothing" shape this repository has now met six times. The constructor check cannot: it
fails the moment the unsafe path exists again, proven by mutation.

**And the correlation-sink guard fired for the eighth time**, on the new `platform.persistence`
package, forcing a decision about whether correlation reaches it before the package could land. It
does not — the concern shapes what a log line *says* and carries no identifier of its own.

**Seven mutations, all caught** — after one survived and improved a test.

718 hermetic tests, 249 database tests.

### Previously

**`P1-TSK-007` — Credential storage** — `COMPLETE` (2026-09-06). The platform can hold a secret it
cannot recover, and can say per credential how strongly it was protected.

| Acceptance criterion | Evidence |
|---|---|
| No persisted or emitted representation contains the input (`INV-IDN-01`) | `CredentialNeverLeaksDatabaseTest`; dropping the encoded-form `CHECK` fails it |
| Parameters recorded (`INV-IDN-02`) | `credential_derivation`, algorithm and three cost factors, all `NOT NULL`; making one nullable fails a test |
| A credential is superseded, never updated | A `BEFORE UPDATE` trigger; removing it fails two tests |
| Both invariants demonstrated to fail when broken | **Seven mutations, all caught** |

**The decision this task exists for is not which algorithm — it is where the parameters live**
(ADR-0032). A platform whose work factor is a global setting cannot raise it: changing the setting
changes what *new* credentials use, nothing records what the old ones used, and the only exits are a
forced reset for every customer or a guess. Recorded per credential, *"how strongly was this one
protected?"* is answerable permanently and *"which are below current policy?"* is an **indexed
query** — which is the whole reason the parameters are columns as well as being inside the encoded
derivation.

**`INV-IDN-01` landed at `DB-CONSTRAINT`, which is stronger than the task asked for.** The derivation
column will not accept a value that is not in its algorithm's encoded form, so **a plaintext password
cannot physically be stored** — not by a migration, not by an operator, not by code nobody has
written yet. `DB-CONSTRAINT` outranks `DOMAIN` and `STATIC` in the catalogue, and this is the
platform's most consequential secret, so it gets the strongest mechanism rather than the most
convenient one. The leak test asserts against **every column of the row**, with the column list
derived from `information_schema` rather than listed — the obvious version of that test checks the
column its author was thinking of and would pass against an implementation that wrote the password
somewhere else as well.

**Measured, not asserted: ~46 ms per derivation** at m=19456 / t=2 / p=1. ADR-0032 asks for
parameters chosen against a *stated* verification time, and a stated time nobody measured is not
stated. The assertion is a **floor, not a ceiling** — a ceiling is a flaky test on a loaded machine,
whereas a derivation completing in under a millisecond is the failure actually worth catching. 46 ms
is at the fast end of the usual target and is deliberately **not** raised here: raising the work
factor is a capacity decision belonging beside the rate limiting ADR-0032 already names as part of
the same design (`P1-TSK-011`), and 19 MiB *per concurrent derivation* means ten simultaneous logins
on one instance is ~190 MiB.

**The library needs more at run time than its POM declares, and only running it found that.**
`spring-security-crypto` 7.1.1 lists exactly one dependency — an *optional* assertj. It in fact needs
**BouncyCastle** to derive and **spring-core** to verify, each arriving as a separate
`NoClassDefFoundError` from a test using the real encoder: one at construction, one at `matches`. A
test double would have found neither, and the failure would have arrived at the first real login. So
`identity` does take a Spring Framework runtime dependency — recorded plainly rather than described
away, because the tidy description ("a standalone jar") was mine and was wrong three times running.

**The cold regeneration earned its place on its first outing since `P0-TSK-042`.** Regenerating the
verification metadata against a **warm** cache recorded three new components; the mandated **cold**
run added a fourth — `jackson-base-2.21.5.pom`, a **descriptor, not a jar**, which is exactly the
signature that finding identified. Proven complete by a second run against a separate empty home
with enforcement on and no write flags.

**Two existing security rules fired, and they got different answers.** `secretsAreWrapped` flagged
`CredentialType.PASSWORD` — an **enum constant**, which is a value of its own enum type and can
never be a secret. Renaming it was the alternative, and `PASSWORD` is exactly what that constant
should be called; every future `TokenType.BEARER` hits the same thing. So the rule gained a
**structural exclusion for enum constants**, with the `P0-TSK-041` precedent (the synthetic
`$VALUES` array, excluded for the same reason), and the exclusion is **proven load-bearing**: removed,
the rule fires again.

**The second one I answered by deleting my own code, and that is the more useful finding.** The rule
then flagged `passwordDeriver()` and `credentialStore()` — `@Bean` factory methods, not accessors.
That was the *second* security-rule modification in one task, which is a signal worth heeding rather
than pushing through. The honest answer was that **the wiring should not exist**: nothing consumes
either bean, `EXECUTION_PROTOCOL.md` rule 3 says a seam only, and the port is the seam. Removing the
two beans removed the false positives without touching a control. A third false positive — a
`COMMENT ON` body reading as `secret: <value>` — was answered by rewording prose, which cost nothing;
the rule's inability to tell a SQL comment from a credential assignment is recorded rather than
widened.

**Scope kept, with the owning task named for each omission.** Verification and upgrade-on-use are
`P1-TSK-008` — `isWeakerThan` exists and is tested and nothing calls it. The endpoint and
registration integration are `P1-TSK-026`. Session revocation on change is M1.3. One value in each
enum, because `EXECUTION_PROTOCOL.md` rule 3 forbids WebAuthn now and the enum *existing* is the
seam ADR-0032's follow-up needs.

**The completion gate found the defect that matters, and it was in what the design got right on
paper.** `Credential.derived` took the algorithm, the parameters **and** the finished derivation as
three independent arguments. A probe passed `DerivationParameters.current()` alongside a derivation
produced at m=1024/t=1/p=1 and it was accepted: the columns said the credential was strong, the
encoded string said it was weak, and **`isWeakerThan(current())` answered `false`**.

That is `INV-IDN-02` satisfied in form and defeated in substance. The invariant is not *"the columns
are populated"* - it is *"the recorded parameters are the ones that produced this derivation"* - and
a credential that misreports its strength is **worse than one recording nothing**, because an
upgrade campaign skips it while believing it was assessed. The duplication ADR-0032 Option D takes
deliberately was, until the gate, duplication that nothing reconciled - which is the exact risk this
task's own commentary described and did not close.

Closed by construction rather than by a check: `Credential.forPassword` takes the **deriver** and the
plaintext, so the algorithm, the parameters and the derivation all come from one place and there is
no argument left for a caller to get wrong. `derived` is gone; the two ways in are now *derive a new
one* and *rehydrate a row the database already validated*.

**One javadoc claim was corrected rather than left tidy.** `isWeakerThan` asserted that "any factor
being lower" means weaker, which is true of memory and iterations and **not** of parallelism - more
lanes spread the same total work rather than adding to it. Parallelism stays in the comparison,
because the goal is convergence on current policy rather than strength alone, and the cost of
including it is an occasional unnecessary re-derivation at the one moment the plaintext is
legitimately in hand.

**Eight mutations, all caught.**

713 hermetic tests, 235 database tests.

### Previously

**`P1-TSK-006` — `POST /v1/registrations`, idempotent** — `COMPLETE` (2026-09-06). The platform's
**first endpoint**, its **first domain events**, its **first emitted audit records**, and the first
real user of `P0-TSK-017`'s `@RequiresIdempotencyKey`.

| Acceptance criterion | Evidence |
|---|---|
| Atomicity — a failure leaves no Party, Customer, Identity, audit row or outbox row | `RegistrationAtomicityDatabaseTest`; an intermediate commit fails all four of its tests |
| A retry with the same key creates nothing more and replays the original response | `aRetryIsIdempotent`, `aReplayIsNotAnnounced` |
| A differing fingerprint on a known key is a distinct conflict (`INV-IDEM-03`) | `409 api.Conflict`, and nothing created |
| A collision is indistinguishable from an unrelated failure (`INV-IDN-07`) | One refusal shape; a mutation that says *why* fails the test |

**Delivered without the credential leg.** The task declares `Deps: P1-TSK-007`, which is `TODO`, and
the instruction was to implement this task alone. Two consequences are recorded rather than absorbed,
and carried as **`P1-TSK-026`**: a registered Identity **cannot yet acquire a credential** —
`POST /v1/me/credential` needs a session, a session needs authentication, authentication needs a
credential — and adding a required `password` later is a **`BREAKING`** change to a published `/v1`
contract, on the platform's first endpoint. Neither is fatal, since there is no client; both are
worse if left implicit.

**The response body is empty, and that is a security decision rather than laziness.**
`API_CONVENTIONS.md` §6 states that the idempotency key **is not a secret and is not redacted**, so
anyone who has seen a key — from a proxy log, an access log, a client's own logging — can replay this
unauthenticated endpoint and receive whatever it returns. Publishing the Party, Customer and Identity
identifiers would hand a stranger three identifiers belonging to someone else. Nothing in Phase 1's
API surface consumes them. There is no replay header for the same reason: telling the caller it was a
replay tells a replaying stranger that the login identifier exists.

**Registration is permanently the one endpoint whose idempotency scope cannot carry a principal**,
because it is the endpoint that creates one — ADR-0004 asks for the command type **and** the owning
principal. The residual is stated rather than glossed: an attacker holding a key *and* knowing the
exact login identifier and display name can obtain a replay, and what bounds it is precisely the
empty body, so what they learn is that the request succeeded and nothing more.

**The credential is deliberately excluded from the request fingerprint, and stays excluded.**
`request_fingerprint` is a durable single-round SHA-256; hashing a body containing a password would
store an offline-crackable derivation of it — `INV-IDN-01` violated by the idempotency mechanism.
`RequestFingerprint` leaves the choice of significant fields to each command precisely so a command
can make that call.

**A savepoint is what makes a collision reportable at all.** A taken login identifier arrives as a
unique-index violation, and PostgreSQL *aborts the transaction* when it raises one — so without a
savepoint nothing further could be written, the idempotency outcome included, and the client's retry
would re-run the command rather than replay its refusal. **A pre-flight `SELECT` is not a substitute
and is documented as such**: two instances would both see the identifier free, both insert, and one
would get `23505` anyway. A pre-check makes the defect rarer, not absent, which is worse.

**`enterSystem()` — and this one stays.** The caller is unauthenticated, so the platform is the only
honest actor. Attributing the action to the Party it creates is circular and, decisively,
*unavailable on the refusal path* where nothing was created; an actor that differs between success
and failure is worse than a uniform honest one. What carries the information is the audit record's
**target**, which is the attempted login identifier on both paths — the one place `PHASE_1_PLAN.md`
§10 permits an attempted identifier to appear. "Revisit every `enterSystem()`" reads as "remove every
`enterSystem()`", and `SECURITY_ARCHITECTURE.md` now says why that is wrong here.

**`app` orchestrates and owns nothing.** Registration spans two bounded contexts and belongs wholly
to neither; either module hosting it would have to depend on the other, which the isolation tests
forbid. So `app` contributes **two calls and a transaction**, and each module writes its own rows,
its own events and its own audit record. `MODULE_ARCHITECTURE.md` §Transaction boundary listed the
permitted cross-module transactions and **was stale** — it named only transfer-plus-posting and
resolution-plus-adjustment, neither of which exists yet; registration is the first of the three to be
real.

**`EventPayload` is a builder with a charset, not an object mapper, and it earned that on its first
run.** `INV-AUD-02` keeps personal data out of event payloads, and a general mapper would serialise
`put("displayName", name)` happily. It rejects any value that is not an identifier or an enumerated
name — and it immediately caught a real mistake, because `EntityId.toString()` renders `PartyId(uuid)`
rather than a bare UUID. Its limit is written down: an event needing richer structure needs the
wire-format decision taken, not worked around here.

**Causation at a flow root had no answer and now has one.** `Correlation` leaves `causationId` null
at a root, deliberately, so a root is distinguishable from a cycle; `EventEnvelope` requires it
non-null. The request is the cause — a value that looks self-referential and is not, because the
correlation identifier is on the idempotency record and on the audit record of the same transaction,
so the chain terminates at something real rather than at nothing.

**Two defects in the published contract, both found by generating it rather than reasoning about
it.** springdoc published **`"200": "OK"`** for an endpoint that has never returned 200, because a
`ResponseEntity` gives it no status to read — a generated client would have treated the real response
as unexpected. Fixed with `@ResponseStatus(CREATED)`, which is the only form that reaches the
document. And it tagged the operation **`registration-controller`**, publishing an internal class
name that an ordinary rename would turn into a contract diff; stripped, for the same reason `servers`
already was.

**A third defect was in the contract harness itself.** `OpenApiDocument` *replaced* the whole
`components` node, which was correct while `paths` was empty and silently wrong the moment a handler
declared a request body: the published document referenced
`#/components/schemas/RegistrationRequest`, which had just been discarded. Caught by
`everyReferenceResolves` — a guard the `P0-TSK-026` review added against exactly this class of
defect, working two tasks later.

**The `BREAKING` labels on the contract diff were reviewed and accepted.** `/paths` going from `{}`
to populated, a new schema's `required` list, and `requestBody: required` are all additions of
structure that did not exist; no client can be broken by an endpoint that was never there.
`PHASE_1_PLAN.md` §7 says additive endpoints are compatible, and the classifier erring in the safe
direction is the design (`P0-TSK-026`: a false BREAKING is visible and fixable, a false COMPATIBLE
fails at the customer).

**Seven mutations. One survived, and it found a real gap in a security test.**
`aReplayIsNotAnnounced` compared response header **names**, so an injected `Idempotent-Replay:
false`/`true` walked straight through — the header name is identical on both, and the value is the
whole disclosure. It now compares names *and* values, excluding only the correlation identifiers and
`Date`, which differ per request by design. All seven are caught now.

**One defect in my own test, found by the full tier rather than in isolation.** The
referential-integrity check asked whether *any* orphaned identity existed anywhere, and
`PartyAndIdentitySchemaDatabaseTest` creates orphans **on purpose**, to prove ADR-0029's missing
foreign key really is missing. Both facts are true and about different things: the schema permits an
orphan, and the registration transaction does not produce one. Scoped to the registration under test.

**One guard was generalised rather than extended.** `FinappApplicationTest` listed the three modules
allowed to contribute beans, and `party` and `identity` now legitimately do. The allowed set is
**derived from the classpath** instead — the stale-list defect this repository has met in CI's task
list, in a coverage guard and in a privilege check, closed the way it has been closed each time.

**The completion gate found two more, both by probing rather than reading.**

**A NUL byte in `displayName` produced `500 api.InternalError`** — a caller's mistake reported as a
platform failure, which `ERROR_CONTRACT.md` §3 forbids and which the `P0-TSK-024` review already
fixed once for a different input. PostgreSQL cannot store U+0000 in a `text` column at all, so the
driver rejected it three layers below the boundary. The same probe showed CR, LF, tab and a
bidirectional override being accepted **into a `RESTRICTED-PII` column** — a forged log line waiting
for the first component that ever prints a name, which is the weak point `DATA_CLASSIFICATION.md` §5
names in this exact scheme. Closed in three places: `PartyName` (five Unicode categories), the
request boundary (so a caller gets `422` naming the field), and `V003` as a `CHECK`, because
`DEFINITION_OF_DONE.md` §1.3 says an invariant a database constraint can carry is enforced there and
the application is not the only thing that will ever write that table. **The constraint is
deliberately narrower than the domain rule and says so**: a POSIX class expresses the C0/C1 ranges
exactly, and one written to *look* like parity while silently missing three categories would be
worse, because the next reader would trust it. `PartyName` still refuses a charset restriction, and
that is not a contradiction — what is excluded is in nobody's name, which is the same test that
rejects an allow-list of scripts.

**The `409 api.IdempotencyInProgress` branch was never exercised**, and the ten-way race hid it:
printing the status distribution showed **all ten racers got 201**, because the winner commits in
milliseconds and the losers replay. An untested error path on an `INV-LIFE-03` contract — *an
unknown outcome is reported as unknown, never assumed failed*. Now driven deterministically by
writing the row a crashed or still-running instance leaves behind: a committed `IN_PROGRESS` claim
with a live lease and a **matching fingerprint**, since a different one would produce
`api.Conflict` and the test would pass for the wrong reason.

**And the gate nearly repeated a trap this repository has already recorded.** The first attempt to
demonstrate the new constraint dropped it from the **compose** database — which the test harness
never uses, since `P0-TSK-035` gives each test JVM its own container. That is the false pass the
`P1-TSK-003` review found. Mutating the migration instead is what actually proves it.

**Eleven mutations, all caught.**

686 hermetic tests, 215 database tests.

### Previously

**`P1-TSK-005` — Party, Customer and Identity aggregates** — `COMPLETE` (2026-09-05). The phase's
highest-risk task: `DELIVERY_PLAN.md` §17 names collapsing the three as Phase 1's top risk.

| Acceptance criterion | Evidence |
|---|---|
| The three are separately persisted with distinct lifecycles, proven by a test that fails if any two are merged | `ThreeAggregatesAreSeparateTest`; a status added to `Party` fails it |
| Invalid transitions rejected **by the aggregate** (`INV-LIFE-02`) | `CustomerLifecycleTest`, `IdentityLifecycleTest` — every state pair enumerated from the machine, not listed by hand |
| A closed Customer cannot be reopened (`INV-LIFE-04`) | Asserted separately from the sweep; `CLOSED` made non-terminal fails four tests |
| Every column classified | Fifteen new rows in `DATA_CLASSIFICATION.md` §4; the guard is green |

**Three aggregates, two modules, three tables, two schemas.** The separation test is written as the
four shapes a merged model **cannot represent** rather than as an abstract claim: a person who is
not a customer (a beneficial owner), a customer who is not a person (an organisation), one Party
holding a retired login and its replacement, and lifecycles that move independently.

**Two invariants are enforced only by the database, because no aggregate can enforce them.** At most
one *live* relationship per party, and a login identifier used once ever, are rules **across**
aggregates of the same type — an aggregate sees only itself, so only the database arbitrates
between two concurrent transactions, which ADR-0014 says is the normal case rather than the
exception.

**The two uniqueness rules deliberately point opposite ways**, and that asymmetry is the sharpest
decision here. A closed relationship frees the party for a new one — a partial index, because
re-establishing a relationship is legitimate. A closed login **never** frees its identifier — a
total index, because reissuing it would let a new person authenticate with a name that appears in
someone else's audit history, making every record naming it ambiguous about which person it meant.

**`identity.identity.party_id` carries no `REFERENCES` clause**, and the cost is stated rather than
hidden: the database will accept an identity for a party that does not exist. What prevents it is
the registration transaction writing both in one commit (`P1-TSK-006`), which is a property a test
can assert — not the schema. An FK there would be coupling neither Gradle nor ArchUnit can see, and
would turn ADR-0001's stated escape into a data migration.

**`Party` has no lifecycle**, which reads as an omission and is the design. Existence has no states,
and every state people reach for — inactive, closed, archived — is a statement about a
*relationship* or a *login*, each of which has its own table. A status on `Party` would mean one
fact recorded in two places, free to disagree.

**`LoginIdentifier` is deliberately not an email address.** An identifier that is also a contact
channel cannot be changed without changing how someone logs in, nor verified without blocking login.
The charset excludes `@` specifically, so the confusion cannot arrive silently through the first
person who types an address.

**One deliberate non-change, recorded.** `IllegalCustomerTransitionException` carries its states but
not the identifier, because an exception is serializable and `EntityId` is not — and making it so
would oblige every existing identifier type to declare a `serialVersionUID`, a change to proven
Phase 0 code this task has no business making (`EXECUTION_PROTOCOL.md` rule 4). It is the third time
this project has met that requirement, after `CurrencyCode` and `IdempotencyKey`.

**The completion gate found two gaps, both in what the work claimed rather than in what it did, and
both closed.**

- **`DOD-KERNEL` requires behaviour under concurrency proven by integration test, and there was
  none.** Worse than a missing test here: the whole argument for putting these two rules in the
  database is that *only the database can arbitrate between two concurrent transactions*, and that
  claim was asserted **sequentially** - tested in the one mode where it is not the interesting one.
  `PartyAndIdentityConcurrencyDatabaseTest` now races ten instances, each with its own connection
  (the `P0-TST-009` convention), and adds the crash half: a rolled-back attempt must not consume
  the uniqueness slot.
- **`INV-AUD-02` was claimed and asserted nowhere.** `PartyName` and `LoginIdentifier` mask
  themselves and `Party`/`Identity` omit them, and nothing tested any of it - while a record's
  generated `toString` prints every component, so the override was the only thing standing between
  a name and a log line. This is the accidental-safety shape `P0-TSK-030` found with Jackson:
  correct today, silent the day somebody changes the shape.

**The concurrency harness was wrong on its first version, and the symptom was that it passed.** It
held every racer at a barrier until all ten had attempted, so the winner's transaction would stay
open across the others' inserts - but the losers were blocked *inside* their insert, waiting for the
winner's lock, and could never reach the barrier. It expired every time, and the suite passed in 30
seconds per race by timing out. The overlap needs no arranging: releasing the racers together and
letting the database block them **is** the contention. 12 seconds now.

Seven mutations, all caught.

### Previously

**`P1-TSK-004` — connection-pool sizing for N instances** — `COMPLETE` (2026-09-04). Closes
recorded debt and transition risk **R6**.

| Acceptance criterion | Evidence |
|---|---|
| The relationship is written down and checked rather than assumed | `DISTRIBUTED_EXECUTION.md` §4a; `ConnectionPoolSizingGuard` at startup; `ConnectionPoolSizingIsConfiguredTest` in the build |

**The rule:** `instances × maximum-pool-size ≤ server max_connections − reserved`. Shipped as
10 × 8 = 80 against 100 − 12 = 88.

**The defaults fail it, which is why this is a guard and not a note.** Hikari's default pool is 10
and PostgreSQL's `max_connections` is 100, so ten instances exhaust the server before a single
connection does any work — and ADR-0014 says N is never 1. The instances that lose the race fail
readiness with *connection is not available*, which reads as the pool being too small or the
database being slow. It is neither.

**The obvious repair is the wrong one, and that is the finding.** Dividing `max_connections` by the
instance count treats the limit as a budget to spend; it is a ceiling not to hit. Every connection
is a backend process, and PostgreSQL throughput stops improving once the cores are busy — past that,
extra connections queue **inside** the database, where the queueing is invisible to the application
and appears as latency on every query rather than as a pool timeout on one. So the pool is sized
small for throughput and "does the fleet fit" is a separate question asked afterwards.

**Checked in two places, because they are two claims.** The guard proves the rule at startup; the
test proves the *shipped numbers* satisfy it in the build. A guard alone would leave a violating
configuration to be found by a rolling restart, one instance at a time.

**Verified against a running instance**, which `DOD-OBS` requires, in all three directions: the
shipped configuration starts; `FINAPP_DB_INSTANCES=20` is refused with the arithmetic and the fix in
the message; and raising `max_connections` to 200 is accepted — so the guard never forces the pool
to be the thing that gives way.

**The gate found one inaccuracy, in what the test claimed about itself.** The guard is a bean, so a
violating configuration fails the test's *context* before any assertion in it is reached — which
means the "shipped numbers fit" assertion can only ever be evaluated in the case where the guard
already passed. The build does fail, and the guard is what fails it; the test's unique contribution
is the other two assertions, that the pool is fixed-size and that the guard is wired at all. The
javadoc said otherwise, and a later reader who deleted the guard believing the test covered it would
have removed the only thing that does.

**Two limits stated rather than implied.** The guard cannot verify `max_connections` against the
live server and does not try — it runs before the pool is used, and one that queried the database
would fail for a database that is merely down; it is a *declaration*, and a wrong declaration is a
wrong answer. And the arithmetic assumes each instance holds its **full** pool, which is why
`minimum-idle` equals `maximum-pool-size` and why a test asserts that rather than trusting it.

### Previously

**`P1-TSK-003` — `party` and `identity` module skeletons** — `COMPLETE` (2026-09-04). The first
modules other than `platform` to own a schema.

| Acceptance criterion | Evidence |
|---|---|
| `./gradlew build` green with both modules | 619 hermetic, 173 database |
| `ProductionModules` coverage includes them | A `double` planted in `PartyAuditAction` fails **two** floating-point rules in `:app:test` — proven, not assumed |

**Three schemas now exist**, all owned by `finapp_migrator` and never a superuser, each with its
own Flyway history, each `REVOKE ALL ... FROM PUBLIC` with `finapp_app` granted `USAGE` and nothing
else — checked against a live database rather than asserted. Migrations apply to an empty database,
validate, and re-apply idempotently, which is what CI does.

**No cross-module dependency, enforced structurally.** `PartyModuleIsolationTest` and
`IdentityModuleIsolationTest` assert neither module sees the other nor `app`, with a non-vacuity
half asserting each *does* see `platform` and `sharedkernel`. That is ADR-0029's boundary at the
classpath: ArchUnit's `entitiesAreNotReferencedAcrossModules` catches the reference, and this
catches the dependency that would make one possible.

**The tests exist because a guard demanded them.** `TestTaxonomyTest` failed with *"a module
contributing no test classes means the sweep did not reach it"* — a module added without tests is
a module the sweep silently skips, and the guard would not let that pass.

**CI's `:platform:flywayMigrate` was a list of one**, and two more schema-owning modules made it
stale. Now unqualified, so Gradle runs the task in every project that has it and a fourth module is
covered without anyone remembering — the `:platform:databaseTest` shape the `P0-TSK-027` review
found.

**Three auditable actions catalogued** — one in `party`, two in `identity`, reconciled in both
directions by the existing registry test. Deliberately few: a registry may list an action before
its code exists, but not before its *design* does, so authentication, session revocation and
credential change are left to the tasks that build them.

**One defect, found by applying the migration rather than reading it**: an unescaped apostrophe in
a schema `COMMENT` (`the platform's`), which PostgreSQL rejected at SQLState 42601.

**Two new lockfiles, and four stale counts corrected.** Locking is per project (ADR-0025), so two
new projects mean two new `gradle.lockfile`s, generated together with the verification metadata in
one invocation as `README.md` §7a requires. Four documents said "three projects" or "six
lockfiles"; there are now eight files. They are restated as **the rule** - one per project, plus
one per settings buildscript - which cannot go stale the next time a module is added. The counts
had been correct when written, which is exactly how this class of defect arrives.

**The completion gate found two more instances of the same defect class**, both created by adding
the schemas, and both fixed here because the next task walks straight into them:

- `ColumnClassificationTest` queried `table_schema = 'platform'`, so ADR-0022's guarantee — *a
  migration adding an unclassified column fails the build* — had silently become true for **one
  schema in three**, immediately before the task that creates the platform's first bulk
  `RESTRICTED-PII` columns in the other two.
- `DatabaseUnderTest` applied `platform`'s migrations **only**, so every database test ran against
  a database in which two thirds of the schemas did not exist.

Both now derive their set from the system rather than naming it. With CI's
`:platform:flywayMigrate`, that is **three** hardcoded names in one task, each correct when
written and stale the moment there were two — which is why the fix in every case is derivation, not
a longer list.

**Proven, not assumed**: an unclassified column added to `party` by migration now fails the
classification guard. It passed silently before, twice — the first probe was planted in the compose
database, which the harness does not use, and reported a false pass. A cross-module dependency added
to `party` fails its isolation test.

**And one pre-existing defect found by the acceptance probe** — recorded as `P1-TSK-025`, not fixed
here. See Known Architectural Debt.

### Previously

**`P1-TSK-002` — constrain the correlation identifier** — `COMPLETE` (2026-09-04). Closes the
widest-reaching disclosure channel in the platform, recorded as debt since `P0-TSK-033` and named
risk **R1** by the transition.

| Acceptance criterion | Evidence |
|---|---|
| No caller-controlled value reaches an unbounded-retention sink | `CallerCorrelationIsNotPropagatedTest`, asserted at the source every sink reads from |
| The debt row is closed | §Known Architectural Debt |

**Decision (ADR-0034): the platform mints the correlation identifier on every request and never
adopts an inbound one.** A well-formed caller value becomes a *client reference* — echoed in
`X-Client-Correlation-Id`, carried nowhere else.

**The finding is that narrowing the charset does not work**, and it is the option the task offered
first. A date of birth, a phone number and an account number are alphanumeric, so any charset still
able to carry a UUID or a W3C trace value carries them too. Of the four values `P0-TSK-033` probed,
narrowing to `[A-Za-z0-9_-]` would have stopped `jane.doe@example.com` and `acct:GB29NWBK…` and
**left `customer-1990-05-14` and `447700900123`** — a fix that closes the debt row and leaves half
the risk. A lexical control cannot express the property; the control had to be structural.

**The test asserts at the source rather than sink by sink.** All four durable columns, the MDC and
the span attribute read from one `CorrelationContext`, so what that context holds during a request
is the property — and it covers sinks that do not exist yet. The span is checked separately because
it is stamped by a span processor rather than by anything reading the context on that thread. The
four probed values are the test data on purpose: a synthetic `client-flow-77` would prove the
mechanism and not the risk.

**The client keeps its join.** It logs the identifier we return, and `X-Client-Correlation-Id` lets
a gateway match a response to a request it no longer holds a connection for. What is deliberately
lost is searching *our* logs by a caller-chosen string — precisely the property that made the
disclosure possible.

**A recorded flake was closed on the way past.** `doesNotContain("bad")` fails about one run in 137,
because a UUIDv7 hex string contains `bad` roughly 0.7% of the time; the intent was right and the
method was wrong. Replaced by pinning the *shape* — a platform-minted UUIDv7 — which nothing derived
from caller input can satisfy.

**A flake I introduced was caught by CI on the first run and not locally** - the fifth finding of this class, and the clearest. `correlationAttributes()` was sampled immediately, but the server span ends *after* the response is written, so the client can hold a complete response while the span it produced has not reached the exporter. It raced in the worst direction: `isNotEmpty` on an empty list fails, but the `noneMatch` beside it **passes** over an empty list - so on a loaded machine the guard would have stopped checking the span sink while still reporting green. `RecordedSpans`' own javadoc had predicted it. Fixed by waiting on the **condition** rather than for a duration, with the bound generous because exceeding it is a failure and never a pass; five consecutive runs green, and the mutations re-proven to still fail with the wait in place, since a wait must not be what makes a test pass. `TracingTest` has the same latent race and has not yet been bitten - recorded, not fixed here. Four mutations, all caught. The MDC leak was caught **twice**, the second time by
`onlyCorrelationContextWritesTheMdc` from `P0-TST-008` — defence in depth working without being
asked to.

### Previously

**`P1-TSK-001` — ADR: data-access mechanism** — `COMPLETE` (2026-09-04). **Phase 1's first task,
and it closes unresolved question 12**, open since `P0-TSK-011` and brought forward from Phase 3 by
the transition.

| Acceptance criterion | Evidence |
|---|---|
| ADR-0033 exists in `Proposed` | [ADR-0033](../adr/ADR-0033-explicit-sql-and-no-object-relational-mapper.md) |
| Explains the interaction with append-only tables and the application role's privileges | The Context and Option A sections; it is the decisive argument rather than a consideration |
| Unresolved question 12 closed in `CURRENT_STATE.md` | Moved to §Unresolved Architectural Questions → *Resolved since* |

**Decision: explicit SQL through `JdbcClient`. No ORM, no persistence context, no generated
repositories.** No new dependency — `spring-jdbc` has been on the runtime classpath since
`P0-TSK-027` added a `DataSource` for the readiness check.

**The decisive argument is the privilege model, not taste.** `INV-HIST-03`, `INV-HIST-01` and
`INV-LED-03` are enforced at `DB-PRIVILEGE` by `finapp_app` holding **no `UPDATE` and no
`DELETE`** — and that is worth exactly as much as the guarantee that nothing emits a statement
nobody wrote. Hibernate's dirty checking emits `UPDATE` on its own initiative, at a flush point
decided by code far from the write, so whether the forbidden statement is issued depends on whether
an entity happened to be dirty. That is the shape of defect that passes every test and fails in
production. `DB-PRIVILEGE` ranks second to `DB-CONSTRAINT` in the catalogue, but it is the
strongest mechanism available for *forbidding an operation*: a `CHECK` constraint cannot express
"this role may not `UPDATE`".

**Spring Data JDBC came far closer and was rejected on two concrete behaviours**, not general
unease: `save()` deletes and re-inserts child collections, and against superseded credentials those
children *are* the history; and application-minted UUIDv7 identifiers arrive non-null, so it
defaults to `UPDATE` on a new aggregate — the `Persistable.isNew()` trap, sitting precisely on the
registration path. Both are workaroundable; needing a workaround on the **first** aggregate is the
signal.

**The seam it closes was explicit in the code.** Four kernel ports are generic over the unit of
work, and three said *"a JDBC `Connection` today, whatever the Phase 3 decision produces later"*.
`T` is now `Connection` permanently. The type parameter **stays** — removing it is a refactor of
proven Phase 0 code with no correctness benefit (`EXECUTION_PROTOCOL.md` rule 4) — and all five
javadocs were corrected, because they described a decision that had moved phases and then been
taken.

**Enforced rather than recorded**, which `DOD-ARCH` requires. `NoObjectRelationalMapperTest` fails
the build if a JPA, Hibernate or Spring Data artefact reaches the application's **runtime**
classpath, catching one that arrives transitively behind a starter — the way it would actually
arrive. Writing it **found a real gap**: `MODULE_ARCHITECTURE.md` §6 had forbidden JPA in
`sharedkernel` only, and `sharedkernel` is not where anyone would add an ORM.

**A false positive was caught before commit, and it is the finding worth keeping.** The first
forbidden list matched `hibernate-` and **failed on the real classpath**: `hibernate-validator` is
Bean Validation, arrives with `spring-boot-starter-validation` from `P0-TSK-025`, and has nothing
to do with persistence. A rule that forbids a correct dependency is a rule somebody turns off —
ADR-0019's own reasoning for keeping `key` out of the `secretsAreWrapped` vocabulary. The list now
names the ORM's own artefacts, and a test keeps the carve-out honest by asserting it is still
needed, the way `P0-TSK-041` proved its two exemptions load-bearing.

**No new shared state.** `DISTRIBUTED_EXECUTION.md` §3 gains no row, and that absence is the
argument: every Phase 0 concurrency protocol — claim-by-insert, bounded `lock_timeout`, conditional
`UPDATE … WHERE`, transaction-scoped advisory locks, savepoints, writing on the caller's connection
— stays expressible unchanged. Four mutations, all caught.

### Just completed

**Phase 0 → Phase 1 transition** — **CONDUCTED** (2026-09-04).
[`reviews/PHASE_0_TO_1_TRANSITION.md`](reviews/PHASE_0_TO_1_TRANSITION.md)

| Part | Outcome |
|---|---|
| Phase 0 gate audit, 13 areas | 12 `PASS`, 1 `PARTIAL` (invariants — see the finding below) |
| 12 universal exit criteria | 11 `PASS`, **criterion 7 `FAIL`** — never run in CI |
| Phase 0 verdict | **remains `IN_PROGRESS`**; remediation task `P0-TSK-042` created |
| Phase 1 established | Objective, 3 contexts, 6 aggregates, 9 tables, 14 endpoints, 7 milestones |
| Phase 1 backlog | 25 items at task granularity, each with acceptance criteria |
| Phase 1 decisions | ADR-0029…0032, all `Proposed` |
| Phase 1 status | **`PLANNED`**, entry gate satisfied except criterion 1 |

**The transition's own finding, and it is the reason a transition is a separate act rather than a
formality.** Phase 1's seven identity properties existed **only as exit-criteria prose** in
`PHASE_GATES.md` — no stable ID to cite, no enforcement mechanism ranked by strength, no named
verification method, and no row in `MUTATION_TESTING.md`. Every other property on this platform
gets all four. The asymmetry was backwards, because Phase 1 is the phase whose *product* is
security, and it would have meant writing credential-handling code against prose. Catalogued as
`INV-IDN-01`…`INV-IDN-07`, taking the platform to **71 invariants**.

**Phase 1 is `PLANNED` rather than `READY`, deliberately, and against the letter of the request.**
Eleven of twelve entry criteria are met; criterion 1 — *the previous phase is `COMPLETE`* — is not,
because Phase 0's criterion 7 fails. `PHASE_GATES.md` §1 forbids `READY` while a hard dependency is
not `COMPLETE`, and the criterion in question is the one that proves the gates execute at all.
Marking it `READY` would be the failure §1 names. It flips on the first green CI run with no
further planning work.

**Four decisions were taken rather than deferred into implementation**, because each is
irreversible once data exists: Party/Customer/Identity as three aggregates (ADR-0029); server-side
sessions with assurance as a *level* rather than an MFA boolean (ADR-0030); permission at the
boundary **and** ownership in the domain, always both (ADR-0031); and credentials storing the
derivation *with* the parameters that produced it (ADR-0032). A fifth — the data-access mechanism,
unresolved question 12 — is `P1-TSK-001` rather than an ADR written here, because it is a Phase 1
decision and writing it during the transition would have been Phase 1 work under another name.

### Previously

**`P0-TSK-017` - `Idempotency-Key` header handling** - `COMPLETE` (2026-09-03).
**The Phase 0 backlog is now 62 of 62.**

| Acceptance criterion | Evidence |
|---|---|
| A declared endpoint rejects a request without the header | 422 `api.IdempotencyKeyRequired`, with the handler proven never entered |
| Key format validated | Blank, over-long and outside the charset all refused, at the bound the store enforces |
| Never logged as sensitive data | Not wrapped, not redacted, accepted keys pass through intact, rejected ones are never echoed |
| Recorded in audit | **Corrected** - no subject; transfers to Phase 4. See below |

**An interceptor, not a filter, and that is load-bearing twice.** A filter runs before the
dispatcher has chosen a handler, so it could not know whether *this* endpoint declares the
requirement without a second, drifting copy of the routing table. And a filter runs outside
`@ExceptionHandler`, so its rejection would be the container's default page rather than the error
contract - the problem `P0-TSK-025` had to work around by rendering the contract by hand inside its
filters. Rejection happens **before the handler is entered**, asserted by counting handler entries:
for a money-moving command, the half of the work done before a late rejection is the half that
matters.

**The requirement is declared, not defaulted.** `@RequiresIdempotencyKey` on a handler or its
controller. Requiring the header everywhere would force it onto reads, where it means nothing and
would train clients to send a value nobody uses; the annotation is also the greppable list of
endpoints claiming to move money.

**A real gap was found by following `DATA_CLASSIFICATION.md` §5**, which classifies this column as a
caller-supplied identifier. `IdempotencyKey` bounds length and blankness because those are the
table's `CHECK` constraints, and carries **no charset** - so a caller could put CR/LF into a value
the platform logs, stores durably and will put on an audit record. A newline in it is a forged log
line. Closed with the same default-deny charset the correlation identifier uses, unit-tested rather
than driven over HTTP because the JDK's own `HttpClient` refuses to *send* CR/LF - and a hostile
client writing raw bytes to a socket is not bound by that politeness.

**The audit clause was corrected rather than approximated.** The registry now exists, but
`AuditRecord` has no field for a key and **nothing in Phase 0 writes an audit record in an HTTP
flow** - the three registered platform actions are outbox operations and none is emitted. Adding a
column now would be a schema change nothing populates, and unlike actor attribution no history is
lost by waiting, which is the test ADR-0010 applies. Transferred to Phase 4.

### Previously

**`P0-DOC-012` - Phase 0 review record** - `COMPLETE` (2026-09-03).
**`P0-EPIC-12` closes with it.**

| Acceptance criterion | Evidence |
|---|---|
| Review record covering all eight review areas | [`reviews/PHASE_0_REVIEW.md`](reviews/PHASE_0_REVIEW.md) |
| ADRs moved to `Accepted` | ADR-0001…0028, all 28 |

**The review finds the exit gate does not pass, and that is what conducting one is for.** Ten of
the twelve universal criteria hold. Criterion 11 fails on three HIGH/CRITICAL Tomcat CVEs and
criterion 7 on a suite that has never run in CI. `PHASE_GATES.md` §4 prescribes the consequence -
the phase **remains `IN_PROGRESS`** - and §1 is explicit that moving backwards from review is
normal while *"shipping through a failed gate"* is the failure.

**Two of the eight areas could not be conducted as written, and say so rather than reporting a
pass.** Area 2 asks for one real posting walked end to end and Phase 0 creates none; what it can
verify - the kernel a posting will be built from - it does. Area 5 enumerates three registered
privileged actions and finds **none of them is emitted**, which is the most important sentence in
that section.

**The ADRs were accepted despite the open failures, and the reasoning is recorded.** Criterion 10
is a *precondition* of the gate rather than a reward for passing it: the gate requires the ADRs to
be accepted, so accepting them is work toward it. Holding ADR-0003 at `Proposed` because Tomcat has
a CVE would be theatre - the decisions were taken, implemented and tested, and none is contingent
on either failure.

**Two documentation drifts, found by hand-diffing what no guard covers.** The pinned-version table
in `SYSTEM_ARCHITECTURE.md` omitted Prometheus, Grafana and WireMock - the first two being
`compose.yaml` images that `verifyInfrastructureVersions` guards, so the table under-reported the
coverage of the check described in the same section. Both closed.

### Previously

**`P0-DOC-011` - Domain glossary** - `COMPLETE` (2026-09-03).

| Acceptance criterion | Evidence |
|---|---|
| Every term in `DOMAIN_MODEL.md` defined | All 55, each with an `Is:`, a `Not:` and an owning module |
| "Do not collapse" pairs explicitly contrasted | All eight groups, under headings repeating `CLAUDE.md`'s wording exactly |

**Comparing the two lists mechanically found they disagree.** Seven terms are forbidden from being
collapsed that the canonical list never names - `Authentication`, `Transaction`,
`Operational Account`, `Underwriting`, `Customer Payment`, `Merchant Settlement`, and `KYC`, which
is the *process* and distinct from the canonical `KYC Case`. A glossary covering only the canonical
list would have left undefined exactly the terms the rule is about. It is therefore the **union** of
both lists, and `DomainGlossaryTest` enforces that in both directions - so the glossary also cannot
become a second home for vocabulary its owning document should define.

**The `Not:` line is the deliverable, not decoration.** A definition alone does not stop a collapse:
two definitions can each be correct and still be applied to the same thing by two people. Naming the
concept a term is confused with is what makes a violation something a reviewer can point at.

**`external` is an owner, not a blank.** A PSP is a company we contract with; modelling one as our
own state is the first step towards a domain that belongs to a vendor (ADR-0008).

**Nothing in the glossary is implemented, and it says so** - checked rather than assumed: no
production class is named for any of the 62 terms. Phase 0 delivers the kernel and zero business
capability, so `DOD-DOC`'s ban on aspirational statements presented as current fact bites here more
than anywhere.

**A spelling was settled.** The canonical list had `Installment` once, against twenty uses of
`Instalment` elsewhere including the module register that assigns its ownership. Corrected.

Nine mutations, all caught - after the guard found two defects in itself: `^` without
`Pattern.MULTILINE` (the defect the `P0-TSK-033` review found in a register parser, reproduced
here) and a `### Term` inside a fenced code block being read as a definition.

### Previously

**`P0-TSK-038` - Mutation-style invariant verification convention** - `COMPLETE` (2026-09-03).
**`P0-EPIC-11` closes with it.**

| Acceptance criterion | Evidence |
|---|---|
| Convention documented | [`MUTATION_TESTING.md`](MUTATION_TESTING.md) |
| Applied to every `P0-TST-*` item | All nine, and all **17** Phase 0 invariants besides - enforced, not asserted |

**The task's criterion is the narrower of two obligations.** It asks for the nine `P0-TST-*` items;
`PHASE_GATES.md` criterion 3 asks for **every in-scope `INV-*`** to have a test that fails when the
invariant is broken. The register covers both, and `MutationDemonstrationTest` checks it on every
build - which converts criterion 3 from something a human verifies once at the gate into something
the build verifies continuously.

**A demonstration has two admissible forms, and the distinction is the whole point.** An *in-suite*
proof - a fixture that violates the rule, asserted to be rejected - runs on every build and cannot
rot. A *recorded* procedure proves the test had teeth **on the day it was written**; it is what a
mutation must be when it drops a constraint, widens a grant or edits production code, because a
build must not do those things to itself. The register labels each row, and the guard verifies that
an in-suite row names a **method that still exists** - so a claim of continuous proof cannot point
at something renamed away.

**The audit found a real gap, which is what an audit is for.** `INV-MON-05` (precision preserved in
persistence) had a test and **no recorded demonstration** - the identifier appeared nowhere in the
project's records. Closed by performing the demonstration rather than asserting it: re-deriving the
scale from the currency in `MoneyColumns.read` fails **exactly one** test, and notably **not** the
general round-trip test, which writes amounts whose scale already matches the currency's current
minor units so re-derivation gives the same answer. That is why the register names a **method**
rather than only a class.

**Two limits recorded rather than glossed.** `INV-AUD-01` is demonstrated only in half - the
registry proves a *recorded* action is catalogued and cannot detect a privileged action that writes
no record at all, which needs Phase 15's audit-completeness verification. And nothing checks that a
*recorded* procedure still reproduces; that residual risk is exactly why the form column exists.

Seven mutations, all caught, each by the intended assertion. Review added two more guards - the
register was checked in one direction only - and found one row whose observed result had been
inferred rather than recorded; nine in total.

### Previously

**`P0-TSK-037` - WireMock harness for provider adapters** - `COMPLETE` (2026-09-03).

| Acceptance criterion | Evidence |
|---|---|
| Reproduces every `CLAUDE.md` §Failure Engineering mode involving a provider | Seven modes, each driven through a real HTTP client over a real socket; and the claim is *enforced* rather than asserted - see below |

**A provider is unreliable in both directions, so the harness has two halves.** Outbound is the
provider's API, a real HTTP server we call: timeout, unavailable, 5xx, delayed, malformed body,
garbage, unknown state, the retry sequence, and the request that is **received** before the
response is lost. Inbound is the provider calling **us** - a duplicated webhook (`INV-IDEM-04`) and
a late settlement (`INV-SET-03`) are the provider acting on its own schedule, and no amount of
stubbing its API reproduces them. A simulator with only the first half cannot reach the modes that
cost the most.

**The single most useful thing the harness offers is `requestCount`.** It separates two failures
that are identical from the caller's side - a request that never arrived, and one that arrived and
was acted on before the answer was lost. That distinction is exactly why `INV-LIFE-03` requires an
explicit indeterminate state rather than a guess in either direction.

**The criterion is a checkable claim, so it is checked.** `ProviderFailureCoverageTest` holds three
links that must all hold at once: every bullet in `CLAUDE.md` §Failure Engineering is classified as
a provider concern or explicitly not one, with the reason and where it *is* covered; every provider
concern names a harness method that **exists**; and every such method is **actually called** by the
suite that proves the harness. The third link is the one that stops a mode being covered on paper.
Seven mutations, all caught - including a bullet added to `CLAUDE.md`, which also proved the new
build-input declaration works.

**Two defects, both found by the guard's own assertions rather than by review.**
- The section regex **read straight past** `## Failure Engineering` into `## Definition of Done`,
  returning "auditability" and "observability" as failure modes - because `DOTALL` makes `.` match
  newlines, so a single `- .*` swallows the rest of the file. Replaced by line-walking with an
  explicit stop at the next heading, which **cannot** over-read; that is better than a guard
  against over-reading, and the vacuity check now also asserts the set is bounded.
- A literal match reported that **ADR-0008 had stopped requiring "malformed response"**. It had
  not: the ADR wraps mid-phrase. Whitespace-normalised.

**WireMock is the standalone artefact, and that was measured.** It relocates Jetty and Jackson
under `wiremock/` - zero classes at `org/eclipse/jetty` - so the harness cannot change which
servlet container Spring Boot picks for every `@SpringBootTest` in `app`, and it resolves to
exactly **one** lockfile entry rather than a tree. Version **3.13.2**, the current stable: Maven
Central's `<latest>` *and* `<release>` markers both point at `4.0.0-beta.38`, so "the newest
version" and "the newest version you should use" are different answers here.

**`unit` tier, decided on measurement**: the whole provider suite costs 1.3 seconds including
server start. WireMock needs a loopback port and nothing else - no container, no Docker, no
external service.

**No new ADR**, deliberately: ADR-0008 already decided the harness exists and lists the modes, so
this is its recorded follow-up rather than a new decision. ADR-0008's follow-up section now says so
and points at [`TESTING.md`](TESTING.md) §5a.

### Previously

**`P0-TSK-036` - Test taxonomy and conventions** - `COMPLETE` (2026-09-03).

| Acceptance criterion | Evidence |
|---|---|
| Tiers runnable independently | `unitTest`, `architectureTest`, `sliceTest`, `databaseTest`, each registered once in the convention plugin. All four run and pass alone |
| Documented | [`TESTING.md`](TESTING.md), enforced against the build in both directions |
| CI runs all tiers | Asserted by test, and asserted **unqualified** - the `:platform:databaseTest` defect the `P0-TSK-027` review found |

**A tier is what a test needs in order to run, and nothing else.** That is the only axis on which
membership can be decided mechanically, and it is the one that matters for scheduling: a tier mixing
requirements produces a task costing what its heaviest member costs and failing wherever that
member's infrastructure is absent. Grouping by *intent* reads better in a document and cannot be
checked.

**Two of the five names the task asks for are deliberately not tiers.** `contract` is a *kind*, and
its members have different requirements - `OpenApiContractTest` needs a Spring context,
`ColumnClassificationTest` needs a database - so making it a tier would group two requirements under
one name. `integration` is replaced by `database`, which says what is integrated with; a Kafka
client gets its own tier rather than being folded into a word that would then mean two things.

**The tiers partition the hermetic suite exactly**: 418 + 54 + 68 = 540 = `test`. `build` still runs
all three hermetic tiers, so nothing left CI's coverage as a side effect of the split.
`./gradlew unitTest` is ~14 seconds against `build`'s minute.

**The split ships with its guard, because splitting one task into four multiplies the ways to make
the stale-list mistake.** `TestTaxonomyTest` holds the Gradle declaration, `TestTier`, every class's
tag, `TESTING.md` and CI to each other. Nine mutations, all caught - **after the first one
survived**.

**Four defects, every one found by the task's own guards rather than by review.**
- **The first mutation survived.** Removing `@Tag("database")` from a platform test left the guard
  green, because it reads sibling modules' compiled test classes from disk and nothing had told
  Gradle that - so it read a **stale class file**. Closed with `dependsOn` and a declared input,
  derived from the subprojects rather than listed. Same defect class as the seven document-input
  lines beside it, one module across.
- **`ModuleBoundaryRulesTest` was skipped entirely** - the oldest and most fundamental rule suite
  here, the one enforcing `app -> platform -> sharedkernel`. ArchUnit executes `@ArchTest`
  **fields** under a class-level `@AnalyzeClasses`, and it declares no `@Test` method at all.
- **`MoneyTest` was skipped**, because every test method lives in a `@Nested` class and the outer
  class - where the tag has to go, since JUnit inherits it downward - carries no marker.
- **A false positive narrowed the detection.** `NoDirectBrokerPublicationRulesTest`'s fixture
  declares `OutboxWriter<java.sql.Connection>` to model production's shape and opens nothing.
  Detection now keys on **acquisition** - `DriverManager`, a `DataSource`, a container, the harness
  - because a rule that pushes a hermetic ArchUnit suite into the database tier is a rule somebody
  turns off.

**Twelve Spring-context tests and nine ArchUnit suites turned out to be in the default tier**, which
was invisible before there was anything to be in the wrong tier of.

**Two further guards added by review**, both from asking what CI actually executes: `build` runs
`test` and **never the tier tasks**, and an empty tier task passes in a second having selected
nothing and written no result file - so `noTierIsEmpty` checks repository-wide. And an unrecognised
`@Tag` is *ignored* rather than rejected, so the vocabulary is now closed, with an empty non-tier
list.

**The recorded duplication is paid down.** Thirteen test classes opened connections through their own
private helper; all now use `DatabaseRoles`, and what they had been copying was a connection as the
**superuser**. All 173 database tests still pass, unchanged.

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

## Active Work

**None in progress.** Phase 0 is `COMPLETE` and Phase 1 is `READY` but not started.

The last work performed was `P0-TSK-042` — a git remote, and the first three CI runs — and before
it the **Phase 0 → Phase 1 transition** (2026-09-04), which is planning and governance rather than
implementation: the gate audit, the phase review, the `INV-IDN` group, ADR-0029 through ADR-0032,
`PHASE_1_PLAN.md`, and the elaboration of Phase 1 to task granularity. No application code was
written, which is the constraint the transition was performed under.

## Blockers

**None.**

~~**The suite has never run in CI.**~~ — **resolved 2026-09-04** by `P0-TSK-042`. The remote is
`https://github.com/genadigeno/finapp`, and the four jobs run on every push to `main`. Run
[33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202) is green on all four.
This closes exit criterion 7, the Phase 0-specific "build green in CI from a clean clone", and the
`DOD-BUILD` item outstanding against `P0-TSK-001`–`005` since the first week.

**It took three runs, and *green locally* versus *green in CI* turned out to be exactly the
distinction the criterion exists for.** Two defects, neither reachable from this machine:

1. **`gradlew` was committed mode `100644`.** Four jobs died on `Permission denied`, exit 126.
   `core.filemode` is false on Windows, so nothing here could observe it — and `P0-TSK-001` had
   enforced *LF line endings* on that same file so *"Linux CI is not broken by a Windows
   checkout"*, reasoning about the file's bytes and not its mode.
2. **`gradle/verification-metadata.xml` was complete for a warm cache only.** Gradle does not
   re-read metadata descriptors it has already parsed, so generation over a warm
   `GRADLE_USER_HOME` records fewer artefacts than a cold resolution needs. Cold regeneration added
   **10 components and 23 artefacts, every one a parent POM or a BOM `.module`** — not one jar,
   which is what identifies the mechanism rather than guessing at it. The file had been complete
   for this machine and incomplete for CI and for any new developer. `README.md` §7a now
   regenerates against a temporary home.

A third finding belongs to the secret scan rather than the build, and is recorded under Known
Architectural Debt: gitleaks met this repository's own history for the first time and produced one
false positive.

~~**The `dependency-scan` CI gate fails.**~~ — **resolved 2026-09-03.** Three **CRITICAL**
advisories in `org.apache.tomcat.embed:tomcat-embed-core:11.0.24`, which Spring Boot 4.1.1 brings:
`CVE-2026-65182` (security-constraint bypass), `CVE-2026-65905` (DIGEST authenticator replay) and
`CVE-2026-68525` (FORM authentication bypass).

Fixed by pinning Tomcat to **11.0.25** in the version catalog and applying it as a dependency
**constraint** — Spring Boot 4.1.1 is the latest stable 4.1.x, so there was no patch release to
move to, and 4.2.0-M1 is a milestone. A constraint rather than `force`, so a future Boot managing
11.0.26 still wins. The scan now reports **zero** vulnerabilities, and the 68 slice tests boot a
real Tomcat 11.0.25, so compatibility is proven rather than assumed.

**The exposure was recorded honestly rather than overstated**: all three are authentication and
authorization bypasses, and Phase 0 has no authentication at all. Practically unexploitable here —
but the gate does not grade on exploitability, and Phase 1 brings exactly what they attack.

**One claim was corrected by probing.** The first version of the build comment said the lockfile
would reject removing the constraint. It does not: with the block deleted, resolution still yields
11.0.25 because the lock applies its own `{strictly 11.0.25}`. The lock *keeps* the version; it
does not object to the loss. A regression needs both the deletion and a lock regeneration, and the
`dependency-scan` job is the control.


`P0-TSK-004` (CI pipeline) was recorded as blocked. The 2026-08-31 task completion review
found the blocker was a defect in the backlog, not in the work: `P0-TSK-004` declared
dependencies on `P0-TSK-011` (Money persistence mapping) and `P0-TSK-036` (test taxonomy),
neither of which is required to run a build with its tests. Because both are scheduled after
several tasks carrying `DOD-BUILD`, whose "CI green" criterion they could therefore never
satisfy, the plan contained an unsatisfiable requirement. Dependencies corrected to
`P0-TSK-001, P0-TSK-002`; CI is now startable and closes the outstanding `DOD-BUILD` gap
across all four completed tasks.

---

## Local Environment Prerequisites

Machine-specific setup that the repository deliberately does **not** contain. The build must
work on any machine without local edits (`DOD-BUILD`: "no developer-machine-specific
assumptions"), so anything below belongs in `GRADLE_USER_HOME`, never in the repo.

**TLS interception by antivirus (this development machine).** AVG "Web/Mail Shield"
intercepts HTTPS and re-signs it with its own root CA. Windows trusts that CA; the JDK's
bundled `cacerts` does not. Java tooling therefore fails with:

```
PKIX path building failed ... unable to find valid certification path to requested target
```

while `curl` and the browser work — which makes it look like a Gradle fault rather than a
TLS-trust one. Resolved in `~/.gradle/gradle.properties` (outside the repo):

```properties
org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Xmx2g -XX:MaxMetaspaceSize=512m
```

That covers the Gradle daemon. Bootstrapping the distribution runs in a separate JVM that
reads `GRADLE_OPTS`, so on a machine with no Gradle distribution cached also export:

```
GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

Alternatives: import the AVG root into the JDK `cacerts` with `keytool`, or disable HTTPS
scanning in AVG.

**Resolved for CI (`P0-TSK-004`):** this is specific to this machine. GitHub-hosted runners
perform no TLS interception, so the workflow needs no equivalent setting. If CI ever moves
to a self-hosted runner behind an intercepting proxy, that runner needs the same treatment —
in its own environment, never in the repository.

**Git Bash rewrites container paths.** Running a command inside a container with an absolute
path from Git Bash (MSYS) silently rewrites it:

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh ...
  -> exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh": no such file
```

Prefix with `MSYS_NO_PATHCONV=1`, or use PowerShell. This affects interactive use only —
health checks and container entrypoints run inside Docker and are unaffected.

**`clean` fails with "Unable to delete directory".** On Windows an orphaned Gradle daemon
keeps module jars open, so `clean` cannot remove `build/`. It is leftover state, not a repo
defect. `./gradlew --stop` handles the usual case; a daemon whose `GRADLE_USER_HOME` has been
deleted survives that and must be killed by PID:

```
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'GradleDaemon' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**The container clock drifts behind the host and is corrected backwards.** PostgreSQL's
`now()` is therefore not monotonic across two statements seconds apart: a row written before a
correction and read after it can have a `now()`-derived timestamp *in the future*. Observed at
542 ms during `P0-TSK-020`, where it made the relay suite fail about one run in fourteen —
always as "the relay published nothing", never anywhere near the clock.

This is a property of the local Docker VM, not of the code, and the platform is already built
for it: coordination timestamps are set **and** compared by the server, so a step affects both
sides equally and correctness never depends on the step's direction. What it does break is a
*test* that assumes a row written a moment ago is eligible a moment later. Such fixtures
back-date the row explicitly rather than relying on the clock (`OutboxRelayTest.backDate`).

A time-dependent test failing intermittently on this machine is worth checking against
`SELECT now()` before it is treated as a defect.

**Resetting local infrastructure.** `docker compose down` keeps data; `docker compose down -v`
discards it. A reset is required after changing Kafka's `CLUSTER_ID`, or when moving to a new
PostgreSQL major version without running `pg_upgrade` — the volume is formatted for the major
version that created it.

---

## Partially Satisfied Definition of Done

Recorded so it is not mistaken for a completed criterion.

| Task | DoD item not yet met | Owning task |
|------|---------------------|-------------|
| ~~`P0-TSK-001` — `P0-TSK-005`~~ | ~~`DOD-BUILD` requires "CI green"~~ — **closed 2026-09-04** by `P0-TSK-042`. All four jobs green on a runner, from a clean checkout: run [33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202). Outstanding since the first week, and closing it found two defects local runs could not reach. | — |
| `P0-TSK-004` | The CycloneDX SBOM covers the whole resolved dependency set, test scope included (21 of ~61 components). Plugin 3.4.1 exposes no configuration filter. Adequate for vulnerability scanning — test libraries execute on CI runners, so they are legitimately in scope — but it means a HIGH/CRITICAL advisory in a test-only library fails the build though nothing vulnerable ships, and **the SBOM must not be published as shipping provenance in this form** because it overstates what is deployed. | Phase 15 (supply chain and provenance) |
| `P0-TSK-004` | CI actions and scanner images are pinned by SHA/digest with no automated update path, so the pins will rot. | `P0-TSK-040` |
| ~~`P0-TSK-002`~~ | ~~Boundary enforcement partial~~ — **closed**. Cross-module internals and entity references by `P0-TSK-007`; `INV-MON-01` by `P0-TSK-008`. | — |
| ~~`P0-TSK-014`~~ | ~~Correlation must reach four sinks; the trace one is unverifiable~~ - **closed** by `P0-TSK-028`. All four sinks are now asserted: the log (`P0-TSK-014`), the outbox row (`P0-TSK-019`), the audit record (`P0-TSK-022`) and the trace, where every span carries `finapp.correlation_id`. The clause survived four tasks and a milestone because `CorrelationSinkCoverageTest` refused to let a new platform concern land unclassified - which is what closing on arrival rather than on memory means. | — |
| ~~`P0-TSK-003`, `P0-TSK-005`~~ | ~~Local PostgreSQL runs as the cluster superuser, so the database-privilege invariants cannot be exercised~~ — **closed** by `P0-TSK-022`. `finapp_migrator` and `finapp_app` exist, both `NOSUPERUSER`; Flyway connects as the migrator and every table grants the application role only the DML it requires. `INV-HIST-03` is now enforced and proven; `INV-LED-03` and `INV-HIST-01` have the mechanism they need and close when the ledger tables exist (Phase 3). | — |

---

## Known Architectural Debt

Debt is recorded here as it is deliberately accepted, with: what was deferred, why, what risk it
carries, what triggers paying it down, and the owning phase.

| Deferred | Why | Risk carried | Trigger | Owning phase |
|---|---|---|---|---|
| **Broker adapter behind `EventPublisher`.** The relay publishes through a port; nothing implements it | An adapter decides the topic scheme, the broker wire format and the producer acknowledgement configuration, and puts a broker client on the classpath. The **stored** payload format is no longer deferred - `P1-TSK-006` settled it as `application/json` via `EventPayload`, because the first producer could not leave it open | **The trigger has now been reached**: `P1-TSK-006` emits three domain events, so the outbox is no longer empty and nothing publishes them. The risk is still bounded rather than absent - there is no consumer either, so the events are durable and unread rather than lost, and `INV-EVT-01` holds. It becomes real with the first consumer | Reached 2026-09-06. **No backlog task owns it**, although `PHASE_1_PLAN.md` §12 names it a required minimal foundation - a gap recorded here rather than closed, since creating it is another task | Phase 1 |
| **Outbox retention.** Published rows are never deleted | `V005` says a published row may be deleted once retained long enough for diagnosis; the sweep is a scheduled job with its own cluster-safety question, and no task owned it | Unbounded table growth. The partial pending index does **not** grow with it — published rows leave it — so the cost is storage and vacuum, not relay latency | Table size becoming operationally material | Phase 15 (data retention and deletion) |
| ~~**Relay metrics.**~~ - **partly paid** by `P0-TSK-029`. Outbox depth and age are gauges over the database (`finapp.outbox.pending`, `finapp.outbox.oldest`), so a stalled aggregate is alertable rather than discoverable by reading logs - and readable precisely when the relay is down. **Still open:** throughput, failure and dead-letter counts from `RelayPollResult`, which need a relay that actually runs | Nothing schedules a relay, so those meters would be structurally always zero - which reads as "nothing is failing" rather than "nothing is running" | The remaining risk is narrower: a relay that is running but failing is visible as a growing backlog, not as a failure count | A scheduled relay | Phase 3 |
| **Inbox retention sweep.** Records are never deleted | The sweep is a scheduled job with its own cluster-safety question, and `V007` deliberately adds no `expires_at` index until its predicate is written | Unbounded growth of a table whose only index is its primary key. **Not** a correctness risk in this direction: a record that is never swept deduplicates forever, and it is early expiry that admits a duplicate (`DATA_MIGRATIONS.md` §9) | Table size becoming operationally material, or the first consumer going live | Phase 15 (data retention and deletion) |
| **Inbox metrics.** Duplicate and contention rates are returned as outcomes and aggregated nowhere | The metrics infrastructure now exists (`P0-TSK-029`), but nothing consumes messages: a counter incremented by no one is a meter that is structurally always zero | A rising duplicate rate is a signal about the transport and a rising contention rate about consumer concurrency; both remain visible only as log lines, one at debug | The first live consumer | Phase 3 |
| **Audit retention and archival.** Records are never deleted, and the application role cannot delete them | ADR-0010 is explicit that deletion is not an option and that archival must preserve queryability - which is a Phase 15 deliverable, not a sweep | Unbounded growth of a table written on every privileged action. **Not** a correctness risk: the inability to delete is the invariant working, and archival must preserve the trail rather than trim it | Table size becoming operationally material | Phase 15 (retention and archival) |
| **Four-eyes approver is not modelled.** `audit_record` records one actor | `INV-AUD-04` applies to manual adjustments, break resolutions, policy activations and period close - none of which exist yet. ADR-0010 schedules it for Phases 3, 8 and 14 | None today: there is no four-eyes action to under-record. When one arrives it needs a second actor column, which is an ordinary forward migration | The first action requiring a second approver | Phase 3 |
| **The three registered platform actions are not emitted.** `outbox.EventAbandoned`, `outbox.EventRetryAuthorised`, `outbox.EventDiscarded` | Two describe the manual procedure in `EVENT_ARCHITECTURE.md` §Handling an abandoned event, performed today with raw SQL; the third is a relay decision currently only logged. Wiring them is a change to `P0-TSK-020`'s relay and to tooling that does not exist | An abandoned event - consumers permanently not receiving a fact that happened - is recorded only in logs, which ADR-0010 is explicit do not count as an audit trail. This is exactly the gap the registry exists to make visible | Dead-letter tooling, or the relay taking an `AuditWriter` | Phase 15 (dead-letter handling), or sooner if the relay is revisited |
| ~~**No ingress correlation filter.**~~ — **closed** by `P0-TSK-025`. `CorrelationFilter` establishes a scope per request at `HIGHEST_PRECEDENCE` and echoes the identifier in `X-Correlation-Id`; every response carries it, error or not. | — | — | — | — |
| ~~**The ingress filter must wrap error handling.**~~ — **closed** by `P0-TSK-025`. The filter is ordered outside the dispatcher and its scope closes only after the whole chain, error handling included. | — | — | — | — |
| ~~**Thirteen test classes open connections through their own private helper.**~~ — **closed** by `P0-TSK-036`. All thirteen now use `DatabaseRoles`, so the property names and the driver call have one definition. What they had been copying was a connection as the **superuser**, which `DatabaseRoles.bootstrap()` now documents as the wrong default and confines to tests making no privilege claim. All 173 database tests pass unchanged. | — | — | — | — |
| **Kafka and Redis are plaintext with no enforcement.** The transport guard covers PostgreSQL only | There is no Kafka or Redis client on the classpath, so a guard for those connections would be guarding nothing - the same argument that kept a `Classification` enum out of `P0-TSK-033` | **None today**, because nothing connects to either. The expectations are documented per hop in `SECURITY_ARCHITECTURE.md`, so the gap is a decision rather than an omission; the risk arrives with the first client, which is also when it becomes enforceable | The first Kafka or Redis client | Phase 3 (broker adapter) |
| ~~**A caller can put personal or financial data into the correlation identifier.**~~ - **closed 2026-09-04** by `P1-TSK-002` / ADR-0034. The platform now mints the identifier on every request and never adopts an inbound one; a well-formed caller value is echoed in `X-Client-Correlation-Id` and reaches no sink. **Narrowing the charset was the obvious repair and does not work** - a date of birth, a phone number and an account number are alphanumeric, so any charset still able to carry a UUID carries them; of the four probed values it would have stopped two and left two. The control had to be structural. | - | - | - | - |
| ~~**No production code establishes a security scope.**~~ - **closed 2026-09-06** by `P1-TSK-006`. `RegistrationService` establishes one for `POST /v1/registrations`, and the actor is `enterSystem()` because the caller is **unauthenticated** - which is a call site that *stays* after Phase 1 revisits it, not one to be removed. The alternative, attributing the action to the Party it creates, is circular and is unavailable on the refusal path where nothing was created; an actor that differs between success and failure is worse than a uniform honest one. The information is carried by the audit record's **target** instead - the attempted login identifier, on both paths. | - | - | - | - |
| **The loopback guard covers one credential.** `DatabaseCredentialGuard` knows about the datasource password and nothing else | It is the only credential that exists. A general mechanism - every externalised credential declaring its own marked default and being checked - would be designed against one example, which is how you get an abstraction that fits nothing later | **Low today.** The build rule is already general: any credential-named key in any configuration file is covered, so a second credential cannot arrive as a literal. What it would not get is the loopback confinement, so a second published default could be aimed anywhere | The second credential, which is Phase 1's authentication or Phase 5's provider adapters | Phase 1 |
| ~~**No output scrubber for text the platform does not control.**~~ - **answered 2026-09-06** by `P1-TSK-009`, and the answer is that the scrubber is **not built**. A scrubber is a deny-list over emitted text, and to recognise a secret it must be *given* the secret - which makes the plaintext travel **further**, into a filter invoked on every log statement in the platform, rather than less far; it also produces exactly the false confidence ADR-0019 warns about, since a deny-list that misses one shape is indistinguishable from one that misses none. **What replaces it is the opposite shape and is checkable**: a plaintext can only reach any sink if something first *unwraps* it, and every unwrap is a call to `expose()` - named to be found, deliberately. `SecretsAreUnwrappedInOnePlaceTest` pins that set to **four production classes, all in `identity`**, so a new unwrap anywhere fails the build and forces a decision. **The residual is stated rather than closed**: inside `identity` a plaintext could still be handed to a log call and nothing mechanical would catch it - bounded by the set being four classes rather than a codebase, and by the one production log call on that path being asserted quiet against a real database. |
| **The scrape endpoint widens the unauthenticated surface to three.** `/actuator/prometheus` joins health and info | `DOD-OBS` requires the dashboard to render live data from a running instance, which needs a scrape endpoint, and there is no authentication anywhere yet | A scrape publishes JVM internals, HTTP route templates and pool statistics - a description of the running system rather than its secrets. The **content** is constrained by a build failure: no tag may carry a request-influenced value | `P0-EPIC-10` landing | Phase 0, M0.4 |
| **The operational endpoints are unauthenticated.** `/actuator/health/*` and `/actuator/info` are reachable by anyone who can reach the port | `DOD-API` requires a negative authentication test for every new surface, and there is no authentication anywhere in the platform yet - `P0-EPIC-10` is the epic that brings it. Building one authentication mechanism for the actuator alone would be a second scheme to retire | **Low, and bounded by what is published.** The bodies are pinned by exact-match test to a status and, for the aggregate, its group names; details, components, environment, JVM and OS are all off, and twelve other endpoints are proven absent. What remains is that an unauthenticated caller can learn the instance is up and which build it runs | `P0-EPIC-10` landing, at which point `show-details: when-authorized` also becomes available | Phase 0, M0.4 |
| ~~**Connection-pool sizing is not reasoned about across instances.**~~ - **closed 2026-09-04** by `P1-TSK-004`. The relationship `instances x pool <= max_connections - reserved` is declared as configuration and enforced by `ConnectionPoolSizingGuard` at startup, with the shipped numbers additionally checked in the build. **The obvious repair - divide `max_connections` by the instance count - is the wrong one**: that treats the limit as a budget to spend when it is a ceiling not to hit, and PostgreSQL throughput stops improving once the cores are busy, after which extra connections queue *inside* the database where the queueing is invisible. The pool is sized small for throughput and the fleet check is a separate question asked afterwards. `DISTRIBUTED_EXECUTION.md` §4a. | - | - | - | - |
| **`@ArchTest` rules do not run in the `architectureTest` tier.** `./gradlew architectureTest` executes an ArchUnit suite's `@Test` methods and **not its `@ArchTest` rule fields** - `NoFloatingPointMoneyRulesTest` contributes 2 cases there and 7 to `test` | ArchUnit executes rule fields under its own JUnit engine, and the tier task's tag filtering does not select them. Same root cause as the `ModuleBoundaryRulesTest` skip `P0-TSK-036` found | **No enforcement gap**: `build` runs `test`, which runs all seven, so CI has always checked them. What is lost is the tier task's meaning - a developer running `architectureTest` before pushing is told the architecture is fine by a task that checked none of it, which is the "green while checking nothing" failure this repository has met five times | Found by `P1-TSK-003`'s acceptance probe; owned by `P1-TSK-025` | Phase 1 |
| **No per-source rate limiting.** Lockout bounds *guessing* per identity; nothing bounds the *volume* one source can generate | **Building it now would be harmful, not merely premature.** `SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution commits to N replicas behind a load balancer, so `getRemoteAddr()` is the balancer: every user shares one bucket, the threshold is reached in seconds, and authentication goes down for everyone. `X-Forwarded-For` is caller-supplied and ADR-0034 settled that such values are not trusted; no trusted-proxy configuration exists. The missing input is a deployment topology, not effort (`P1-TSK-011`) | **Resource exhaustion, and it is the platform's most expensive unauthenticated operation**: ADR-0032 makes each attempt cost ~46 ms and ~19 MiB *by design*, so the work factor protecting a stolen credential store is the one an attacker spends for free. Ten concurrent attempts is ~190 MiB on one instance. `INV-IDN-07` still holds - every response is identical, so flooding discloses nothing - and lockout now bounds what an attacker learns, though not what they cost. Bounded today only by the fact that nothing is deployed | A deployment topology and a trusted-proxy declaration | Phase 15 |
| **`POST /v1/registrations` is unauthenticated and unthrottled.** Anyone who can reach the port can create Parties, Customers and Identities without limit | There is no rate-limiting mechanism anywhere on the platform. `P1-TSK-011` builds one for **authentication** - failure counting and lockout keyed on an identity - and none of that applies to an endpoint whose whole point is that no identity exists yet. Building a second, differently-shaped mechanism here before that one exists would be designing the general case from one example | **Resource exhaustion, not disclosure.** Every response is identical whatever is sent, so flooding discloses nothing (`INV-IDN-07` holds); what it does is fill three tables and the outbox. The idempotency key does not help - a flooder simply generates a fresh one. Bounded today only by the fact that nothing is deployed | `P1-TSK-011` landing, which is when a throttling mechanism exists to extend rather than invent | Phase 1 |
| **Dead-letter tooling.** Resolving an abandoned event is a manual `UPDATE` | The mechanism is needed now; the tooling is a Phase 15 concern | An operator resolving a stalled aggregate acts by hand against a live table. Acceptable only because the outbox is transport, not financial history (`INV-EVT-02`) — the same action against a ledger table would not be. The procedure is documented in `EVENT_ARCHITECTURE.md` §Handling an abandoned event | Abandonment occurring in practice | Phase 15 |

None of these is financial-correctness debt.

Per [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Architectural Debt,
**financial-correctness debt is never accepted** — an invariant is either protected or the
work is not done.

Note: items in [`DECISIONS.md`](DECISIONS.md) §Deliberately Deferred are scoping decisions,
not debt.

---

## Unresolved Architectural Questions

Ordered by when they must be answered. Each requires an ADR before the work that depends on
it begins.

| # | Question | Must resolve by | Risk if unresolved |
|---|----------|-----------------|--------------------|
| 1 | Isolation level and locking strategy for concurrent postings | Phase 3 | **High** — lost updates or double spend under contention (`INV-CON-01`) |
| 2 | Chart-of-accounts structure and its relationship to the Phase 14 GL | Phase 3 | High — a narrow structure forces retroactive remapping, breaking `INV-ACC-04` |
| 3 | Balance projection placement (ledger schema vs separate read store) | Phase 3 | Medium — affects contention and rebuild cost (ADR-0009) |
| 4 | Whether `accounts` and `wallet` are one module or two | Phase 3 | Medium — **working position recorded** (one module, `MODULE_ARCHITECTURE.md` §3 M1) with a named split trigger; still to be confirmed |
| 5 | Transfer/ledger transaction boundary and compensation strategy | Phase 4 | High — determines whether a saga is ever needed internally |
| 6 | Accounting treatment of authorization (memo/hold) vs capture (posting) | Phase 5 | High — misstates available funds if wrong |
| 7 | Whether `checkout` is its own module or part of `merchant` | Phase 6 | Low — **working position recorded** (own module, §3 M2) with a named merge trigger |
| 8 | Fee model: who pays, when recognised, gross vs net settlement | Phase 6 | High — changing revenue recognition after postings exist is a restatement |
| 9 | Which payment rail to simulate first, and its finality semantics | Phase 5 | Medium — first rail shapes the abstraction (mitigated by designing to `PAYMENT_LIFECYCLES.md`) |
| 10 | Which jurisdiction-neutral compliance abstractions belong in the MVP | Phase 2 | Medium |
| 11 | Fail-safe policy for risk evaluation: block or allow on unavailability | Phase 13 | High — a wrong default is either an outage or an open door |

Resolved since:
- ~~Data-access mechanism: JPA/Hibernate, Spring Data JDBC, or plain JDBC?~~ &rarr; [ADR-0033](../adr/ADR-0033-explicit-sql-and-no-object-relational-mapper.md) (`P1-TSK-001`, 2026-09-04). Explicit SQL through `JdbcClient`; no ORM. Open since `P0-TSK-011`, scheduled for Phase 3, brought forward because Phase 1 creates nine tables

Resolved during initiation:
- ~~Which modules form the initial modular-monolith cut?~~ → [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- ~~Deployment topology?~~ → ADR-0001
- ~~Money representation?~~ → ADR-0003
- ~~Idempotency mechanism?~~ → ADR-0004
- ~~Reliable event publication?~~ → ADR-0005
- ~~Is balance authoritative or derived?~~ → ADR-0009

---

## Next Task

**`P1-TSK-012` — `P1-TST-002`: authentication failure modes.**

The `PHASE_1_PLAN.md` §8 scenarios that concern authentication: invalid credential, lockout,
concurrent login and credential change, and **database unavailable failing closed with no session
issued**. The last is the one with no subject yet — there is no session to withhold until
`P1-TSK-013` — so it needs the same treatment `P1-TSK-009` gave a sink with no producer: assert the
mechanism that will refuse it, never an absence over an empty set.

## Change Log

| Date | Change |
|------|--------|
| 2026-09-06 | **`P1-TSK-011` complete - M1.2 is 5 of 6.** **The task is two controls with two keys, and separating them is the whole design**: lockout, keyed on the identity, stops credential *guessing* and **must not change cost or response**; a rate limit, keyed on the source, stops resource *exhaustion* and may refuse cheaply because it says nothing about any account. Conflating them produces the defect the instinct leads straight to - refuse a locked account *without* paying for a derivation, which is the CPU relief lockout appears to be for and is an **account-existence oracle**: attempt often enough against any identifier, and afterwards the locked one answers in a millisecond while the unknown one still costs ~46 ms. `INV-IDN-07` lost to the control added beside it. So a lock costs exactly what every other failure costs, asserted by **counting derivations** rather than reading a clock. **Per-source is deliberately not built, and the reason is that building it would be harmful rather than merely premature.** `SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution commits to **N replicas behind a load balancer**, so `getRemoteAddr()` is the balancer: every user shares one bucket, the threshold is reached in seconds, and **authentication goes down for everyone**. `X-Forwarded-For` is caller-supplied and ADR-0034 settled that such values are not trusted; no trusted-proxy configuration exists anywhere here. The missing input is a **deployment topology**, not effort - recorded as debt with that trigger. **`INV-CON-03` is enforced for the first time, three phases before the catalogue schedules it**, because ADR-0032 makes verification expensive and names lockout as part of the same design. **One row per identity, not one row per attempt**: an append-only attempt log is this platform's usual idiom and is wrong here, because counting rows in a window is a read-then-count and ten concurrent attempts at the threshold all read nine and all proceed. The whole protocol is one statement - `INSERT … ON CONFLICT DO UPDATE … RETURNING` - where the post-increment count is produced **by the write**, so there is nothing to lose between two instances; ten simulated instances with their own connections produce exactly ten. **Keyed on the login identifier and resolved by a subselect inside the same statement.** The obvious alternative - look the identity up, then record if found - runs one query when the account is absent and two when it is present, which is a timing difference that discloses existence; and it keeps `VerificationOutcome` opaque, because having verification *report* which identity it tried would put back exactly the field `P1-TSK-008` removed and its reflective guard would fail. **The lock is time-bounded and self-healing, with no operator unlock**: lockout is itself an attack - anyone who knows a login identifier can lock its owner out - and a lock needing an operator to clear it converts that cheap attack into a support-desk denial of service while giving an insider a standing reason to touch other people's accounts. A **correct** password is still refused while locked and does not clear it, because a lock a correct guess clears is a signal that the guess was right. **The platform's own guard found the one defect**: `recordFailure` wrote its audit record outside the security scope, and every lockout test failed with *"no actor has been established for this flow"* - `P0-TSK-032`'s refusal to default the actor doing precisely its job, since a default would have accepted the mistake silently and recorded the wrong party permanently under `INV-HIST-03`. **The completion gate then found the most serious defect this phase has produced, and the suite passed straight over it: the lock was PERMANENT.** After a lock expired, **one** failure re-locked the account for another full period, so an account locked once was locked for ever at one attempt per lock period - precisely the attack the design claims to avoid and which the migration's own comment says cannot happen. The tests missed it because `anExpiredLockClearsItself` authenticates *successfully* after the lock expires and a success deletes the row, so the path where the next attempt is another **failure** was never exercised. The code was wrong because the reset was guarded on `locked_until IS NULL AND the window elapsed`, and since `window_started_at` always precedes `locked_until` an expired lock implies an expired window - so the `IS NULL` half blocked the reset at exactly the moment it was due. **And the first fix was still wrong, which the new test caught**: relaxing it to "no live lock and the window elapsed" works only because the shipped policy makes window and lock both 15 minutes, which is coincidence rather than equivalence - `LockoutPolicy(3, 60min, 1min)` is legal, expires the lock while the window is live, and restores the permanent lockout. The rule is now two independent clauses - a **served lock** ends the run whatever the window says, and an **elapsed window** ends it provided no lock is live - both proven load-bearing by separate mutations, with a test running under a policy whose window and lock differ so the coincidence cannot hide it again. **Seven mutations, all caught.** 737 hermetic tests, 272 database tests. |
| 2026-09-06 | **`P1-TSK-010` complete - M1.2 is 4 of 6.** The platform's second endpoint, and the first that can say *no* without saying *why*: `POST /v1/authentications` returns **204 or 401 and only those two**, with unknown identity, wrong password, suspended identity and an identity with no credential all byte-identical **and all costing the same work**. **The plan contradicts itself about the session, and the contradiction is recorded rather than resolved by an implementation task.** The item declares `Deps: P1-TSK-013`, which is `TODO`; `PHASE_1_PLAN.md` §11 puts *session issuance* in **M1.2's scope** and makes M1.2's acceptance *"an identity authenticates and receives a session"* - while numbering the session tasks into M1.3. Fourth backlog defect of this class, and unlike `P1-TSK-006`'s the **`Deps` line is right and the milestone boundary is wrong**: "that person can authenticate" is not a milestone without a session. So this delivers the endpoint and not the session, a success returns **204 with no body**, **M1.2 cannot close on `P1-TSK-012`**, and the remainder is carried as the new `P1-TSK-027`. **The acceptance criterion is an equality, so it is asserted as one**: the four failing causes are collected and compared **to each other**, rather than each against a remembered expectation - the second form passes against an implementation returning four different bodies that each happen to match what its author wrote down. **The refusal is returned, never thrown, and that is structural**: a failed authentication writes an audit record in the same transaction, and a credential-stuffing campaign is visible only through those rows, so throwing to produce the 401 would roll it back and destroy it. Proven by making the service throw. **Not idempotent, and that is stronger than "the money-moving clause is vacuous"**: an idempotency key is explicitly *not a secret*, so a stored success keyed on one would let anybody who saw the key in a proxy log **replay a successful authentication** - the mechanism that makes registration safe would make this endpoint an authentication bypass. **The platform's first real actor**: a success is attributed to `Actor(identityId, CUSTOMER)` and a failure to the platform, because on that path there may be no identity at all; the asymmetry is *correct information* rather than a channel, since registration's uniformity argument does not transfer - there the success actor would have been circular, here it is proven. **The build found a real defect in the request DTO and was right to.** `secretsAreWrapped` rejected `String password` twice, for the component and for the accessor a serialiser reads - and a record's generated `toString` prints every component, so `log.info("{}", request)` would print a customer's password with no getter call and nothing a reviewer stops at. This DTO is the exact place a plaintext enters the platform, which makes it the first place it could leave. Wrapping it needed the Jackson **deserialiser**, the symmetric half of `P0-TSK-030`'s masking serialiser, never required until a request body carried a secret. **Two contract defects, both found by generating the document rather than reasoning about it.** The wrapper published as an **empty schema** - `password: {$ref: SensitiveString}` pointing at `{}` - so a generated client would model a password as an untyped object and would not know to send a string, and every other check passed over it because it parses, it diffs and `everyReferenceResolves` is satisfied by a schema that exists. Fixed by telling springdoc the wire type in **test scope**, since the running application ships no documentation library (ADR-0015), plus a new guard that fails the build on **any** empty published schema - because the next wrapper will not be called `Sensitive`. And **`P1-TSK-009`'s contract guard fired on this task one task later, which is what it was for**: right to fire, wrong about why, because it forbade a credential-named member *anywhere* and an authentication request body must declare a password or no client can call the endpoint. Narrowed to the property that is actually true - **a secret may be sent, never returned, and never put where a URL or a header goes** - with the permitted set pinned to two named request schemas so widening it is visible. **One mutation survived and produced a new test.** Making a malformed password fail *without doing the derivation* left all four responses byte-identical and changed only how long one took; `everyFailureLooksTheSame` could never have caught it, because it compares responses and the disclosure is through the clock. That is `P1-TSK-008`'s own finding - *assert by counting work, not by reading a clock* - reproduced one task later by the person who wrote it down, and closed by a test that counts derivations across five failing paths and one success. **A second mutation survived correctly**, which is worth separating from a miss: making the refusal echo the attempted identifier changed only the **log** message, because `ApiException` keeps that separate from what a client is told (`P0-TSK-024`) - the mutation was aimed at the wrong field, and re-aimed at the first step a diverging response would actually need (a `reason` field on `VerificationOutcome`) it is caught by `P1-TSK-008`'s reflective guard. **The completion gate then found a documented test that did not exist**: `AuthenticationRequest` restates `LoginIdentifier`'s bounds as literals and its javadoc claimed *"a test asserts they still match"* - while the sibling `RegistrationRequest` had one all along, which is exactly what made the sentence read as true. The drift is real rather than cosmetic: a narrowed domain charset would have the boundary accept what the domain refuses, throwing inside the service and surfacing as `api.InternalError` - our fault for the caller's input, which `ERROR_CONTRACT.md` §3 forbids. The new test **sweeps every code point the boundary admits** rather than sampling, because nobody guesses which character the mismatch will be. **Three more findings, all from probing shapes the code was not designed against.** Eight request shapes driven over real HTTP - absent, null, empty, numeric, object, array and oversized passwords, and a refused login identifier - and **none produces a 500**; now pinned, including the two decided by different mechanisms, since a number is *coerced* and fails authentication while an object *never reaches the deserialiser* and is a 400. **A "hardening" fix turned out to be unreachable code**: the gate first added a null-check to the deserialiser with a comment explaining what it handled, and probing Jackson directly showed it throws `MismatchedInputException` **before** the deserialiser runs - so the branch never executed and its comment described a mechanism that is not the real one. Removed, and the comment now states what was measured; a fix that reads correctly and does nothing is the shape this repository keeps meeting, and the only thing separating it from a real one is running it. **And the correlation exclusion was a hole rather than an allowance**: the comparison strips the correlation identifier, so four responses missing it entirely would still have compared equal - the raw value is now asserted present first. **The throttling debt named in the design had not been recorded** and now is: this endpoint is a CPU and memory amplifier, because ADR-0032 makes each attempt cost ~46 ms and ~19 MiB *by design*, so the work factor protecting a stolen credential store is the same one an attacker spends for free. **Six mutations, all resolved.** 737 hermetic tests, 260 database tests. |
| 2026-09-06 | **`P1-TSK-009` complete - M1.2 is 3 of 6.** **The stated acceptance criterion was already met, and checking rather than assuming is what made the task worth doing.** *"Fails when a credential field is added without wrapping"* is `secretsAreWrapped` - the rule `P0-TST-008` found **structurally incapable of failing** and then fixed - and probing it here against **real production code** rather than its own fixture, with an unwrapped `String lastPassword` planted in `CredentialVerifier`, fails the build twice: once for the field and once for the accessor. Implementing that criterion again would have been a second copy of a working rule. **So the deliverable is the gap between the task's two clauses, which are not the same claim.** The field rule governs what a **type stores**; it cannot see a secret held only in a **local** (no declaration to inspect), one inside a message the platform did not write, the **MDC** (a `String` map the ECS encoder lifts to top-level fields), an **event payload**, a **metric tag** or a **span attribute** - none of which is a field on any of our types. **Three of the five named sinks have no credential-carrying producer yet**, which is a reason to write the guard now rather than to defer it - but **the artefact has to be the right one**: an assertion that a credential is absent from an empty event stream passes vacuously, which is the "green while checking nothing" shape this repository has now met six times. So for a sink with no producer the deliverable is **the mechanism that will refuse the producer**, which has a subject today and becomes load-bearing the moment `P1-TSK-010` or `P1-TSK-026` lands. **The Phase 0 debt row was answered rather than carried forward.** *"No output scrubber for text the platform does not control"* named its trigger as *"a business module logging real flows"* and its owning phase as Phase 1; `identity` is that module and `CredentialVerifier` holds that log call. **The scrubber is not built**, and the reason is structural rather than budgetary: a scrubber is a **deny-list over emitted text**, and to recognise a secret it must be *given* the secret - which makes the plaintext travel **further**, into a filter invoked on every log statement in the platform, rather than less far. It also produces exactly the false confidence ADR-0019 warns about, since a deny-list that misses one shape is indistinguishable from one that misses none. **What replaces it is the opposite shape and is checkable**: a plaintext reaches any sink only if something first *unwraps* it, and every unwrap is a call to `expose()` - named to be found, deliberately. `SecretsAreUnwrappedInOnePlaceTest` pins that set to **four production classes, all in `identity`**, so a new unwrap anywhere in the platform fails the build and forces a decision rather than being a diff line nobody stops at; it reads method **references** as well as calls, because a method reference compiles to an `invokedynamic` with no call site, which is precisely the bypass the `P0-TSK-013` review found in the ambient-time rule when `Instant::now` walked straight through it. **`INV-AUD-02` gets its first real subject.** Every demonstration protecting it so far used a synthetic fixture record written by somebody who already knew the rule; `RawPassword` and `Credential` were written to do a job, and whether they are safe when logged is a fact about them. Asserted on **emitted output through the real ECS encoder**, not on `toString()` - between the two sit a structured-logging layer that can lift an argument into a top-level field and Jackson's fallback for a type it cannot introspect, each of which has been a real defect here. **And the one production log call on the credential path is asserted quiet against a real database** - it is on the *failure* path, the half nobody reads until something is wrong and therefore the half where a disclosure survives longest, and it must name neither the password, nor the derivation (not a password, so a rule about passwords would let it through, and offline-crackable), nor the login identifier (`CONFIDENTIAL`, because it carries existence), nor the identity. **Two limits are stated in the tests themselves rather than glossed**: `EventPayload` is a **charset, not a secret detector** - a password of `hunter2` satisfies `[A-Za-z0-9_-]` perfectly and would be published, so what actually keeps credentials out of events is that no event declares a credential field, which is a `P1-TSK-010` design property and not this one; and the whitelist says *where* a secret may be unwrapped, not what happens to it afterwards. A suite believed to cover more than it does is worse than one that covers less, because the second gets a second control and the first does not. **One mutation reported SURVIVED and had never landed** - the fourth occurrence of that class here. The planted contract member targeted `"loginIdentifier" : {` and the document is formatted `"loginIdentifier": {`, so the replacement matched nothing and a working guard was reported as toothless. Re-run with the marker **asserted present before the edit**, it is caught. Checking that a mutation actually landed is the only thing separating a proof from a reassuring message. **The completion gate then found the rule's vocabulary was one third dead, and that is the task's most consequential finding.** `secretsAreWrapped` splits a field name on camel-case boundaries and compares each **word** against a vocabulary that uses **compound** forms where the bare word has innocent uses - and those two facts cannot both be delivered, because the splitter turns `apiKey` into `[api, Key]`. **Six of twenty-two entries were structurally unreachable**: `apikey`, `privatekey`, `signingkey`, `cardnumber`, `mfacode`, `sessionid`; `secretKey` was caught only by accident, because `secret` is separately an entry. **Measured against the build in both directions rather than argued**: a production `String cardNumber` field **passed cleanly** before the fix and fails after it - the PAN field ADR-0019 added specifically so that widening PCI scope *"fails the build rather than arriving quietly"* - and `sessionId`, Phase 1's subject three tasks away, was in the same state. This is `P0-TST-008`'s finding one layer in: that review found the rule *could not fail at all*, and this found that a third of what it claims to check, it does not. Closed by matching **adjacent word pairs** as well as single words, which is precise rather than fuzzy - `idempotencyKey` yields `idempotencykey`, not in the vocabulary, so it stays clean, as do `companyName` and `spinLock`; substring matching would have caught the six and reintroduced exactly the false positives ADR-0019 excluded `key` to avoid. One fixture per dead entry, because an aggregate probe reporting "caught" says nothing about which of six it caught. **Three further findings, all in this task's own work.** The coverage guard asserted `contains(...)` where every sibling suite asserts **equality** against the classpath's module set, so narrowing the sweep until `app` was never analysed left it green - the exact `P0-TSK-008` finding, with the corrected idiom two files away, and `app` is where a credential would most plausibly reach a response. The contract guard read JSON **keys and not values**, so an OpenAPI parameter declared as `{"name": "token"}` - where the secret name is a value - reported a clean contract; found by probing shapes it was not designed against, and its remaining limit is now asserted rather than implied, since a credential in an `example` **value** is not catchable by name matching and the control for that is the repository-wide secret scan. And the second copy of the secret vocabulary **had already drifted**, missing `signingkey` and `cvv2`, so a `signingKey` property could have reached the published contract while the build rule forbade the field that would hold it; reconciled by test rather than merged. **And a mutation reported SURVIVED having never landed, twice** - first a contract marker that did not match the document's formatting, then three vocabulary probes whose backup file had silently failed to be written, so every result read `NOT CAUGHT` against unmutated code. Fifth and sixth occurrences of that class here; every mutation is now applied with the plant **asserted present before the build runs**. **Eleven mutations, all caught.** 732 hermetic tests, 250 database tests. |
| 2026-09-06 | **`P1-TSK-008` complete - M1.2 is 2 of 6.** Verification, and the upgrade that makes `P1-TSK-007`'s per-credential parameters do something. **The store converges with no forced reset**, proven rather than argued: a credential written under weaker parameters verifies, is re-derived at current policy inside the same transaction, and the same password works afterwards - the customer notices nothing. **Every failing path performs a full Argon2id verification**, and there are four: no identity, an identity that cannot authenticate, an identity with no credential, and a wrong password. The middle two are the ones an implementation skips and the ones that matter - **a suspended account answering instantly tells an attacker both that it exists and that it is suspended**, which is `INV-IDN-07` lost through the timing channel rather than the response body, the harder half to notice and the harder half to test. **Asserted by counting work, not by reading a clock**: a wall-clock timing test is flaky and measures the machine, while a counting deriver is deterministic and measures the property actually at stake - did the expensive path run at all? **One residual is stated rather than glossed**: the dummy derivation runs at *current* parameters while a real credential may be at weaker ones, so verifying a stale credential is genuinely cheaper than failing against the dummy - and what bounds it is precisely the upgrade, because the store converges and the gap closes itself. **`VerificationOutcome` carries no reason, and that is structural rather than stylistic**: a failure has no reason code, no status and no `Optional` that is empty in one case and populated in another, and every failure is literally the **same object**, so not even reference identity distinguishes them. A caller branches on whatever it is handed, so a `reason` field is an enumeration oracle with a delay fuse - harmless the day it is added, a second response shape the day somebody maps it to a message. A test derives the type's members reflectively, so adding one fails the build. **An upgrade failure never fails a correct authentication**: the customer typed the right thing, and refusing them because a background optimisation collided would be a self-inflicted outage - so the upgrade sits behind a savepoint and every failure of it is discarded, proven by injecting one and asserting both that the login succeeds *and* that the credential is left intact rather than superseded with no replacement, which would lock the person out permanently. **This is the platform's first genuine read-then-write**, so `P1-TSK-007`'s claim that there is "no read-then-write anywhere" does not extend here and is deliberately not relied on; what makes it safe is that the write is **conditional** - `supersede` moves the row only while it is still `ACTIVE`, and its row count is the outcome. **Two defects found while building it.** An **auto-commit connection made the upgrade silently not happen**: `setSavepoint` throws on such a connection and the upgrade's catch-all discarded it as an ordinary collision, so every login would have verified correctly and upgraded **nothing, permanently**, with a warning nobody reads and no test failing - a control reporting success for work it did not do, which is the shape this repository keeps meeting. Closed by refusing an auto-commit connection **up front**, with a message about the mistake rather than about the mechanism, which is the `JdbcInboxRecordStore` precedent. And **a surviving mutation showed the concurrency test asserted the outcome rather than the coordination**: ignoring the conditional supersede's answer *still* produced exactly one upgrade, because the nine losers then collided with the partial unique index and the catch-all swallowed it - same result, worse mechanism, and the javadoc's claim that *"the index is never even reached"* would have been false with nothing failing. The test now counts **inserts attempted**: one, not ten. **A third security-rule false positive, answered by narrowing the rule compositionally rather than by renaming.** `secretsAreWrapped` fired on a `CredentialStore` collaborator field and on a private factory returning a `RawPassword`, and renaming was not available - the `identity` module's collaborators are named after credentials because that is what they are for, and every future `TokenStore` would hit the same thing. The narrowing is principled: **a field whose type is one of our own types is already checked at its own declaration**, so wrapping the reference protects nothing, and `Sensitive<RawPassword>` would double-wrap a type whose whole job is to wrap. It touches no JDK type, which is where a secret actually lives, and it is **proven load-bearing** - removed, the rule fires three times again. **Six mutations, all caught**, after one survived and improved a test. 718 hermetic tests, 249 database tests. |
| 2026-09-06 | **`P1-TSK-007` complete - milestone M1.2 opens, 1 of 6.** The platform can hold a secret it cannot recover and can say, per credential, how strongly it was protected. **The decision this task exists for is not which algorithm - it is where the parameters live** (ADR-0032). A platform whose work factor is a global setting **cannot raise it**: changing the setting changes what *new* credentials use, nothing records what the old ones used, the store silently becomes a mix of strengths, and the only exits are a forced reset for every customer or a guess. Recorded per credential, *"how strongly was this one protected?"* is answerable permanently and *"which are below current policy?"* is an **indexed query** - which is the entire reason the cost factors are columns as well as being inside the encoded derivation, a duplication ADR-0032 Option D takes deliberately and which a test reconciles, because duplication nothing reconciles is drift waiting to happen. **`INV-IDN-01` landed at `DB-CONSTRAINT`, stronger than the task asked for**: the derivation column refuses a value that is not in its algorithm's encoded form, so **a plaintext password cannot physically be stored** - not by a migration, not by an operator, not by code nobody has written yet. `DB-CONSTRAINT` outranks `DOMAIN` and `STATIC` in the catalogue, and this is the platform's most consequential secret, so it gets the strongest mechanism rather than the most convenient one. The leak test asserts against **every column of the row**, with the column list derived from `information_schema` rather than listed - the obvious version checks the column its author was thinking of and would pass against an implementation that also wrote the password somewhere else. **A `BEFORE UPDATE` trigger makes "superseded, never edited" a schema property**: the application role holds `UPDATE` because superseding needs it, so without the trigger the grant would be wider than the intent, and a rewritten derivation is how the evidence of *when protection changed* disappears. The `P0-TSK-015` pattern, applied for the same reason. **The two uniqueness rules in `identity` now point opposite ways, and that is the second time this phase has had to say so**: a superseded credential **frees** its slot, because replacing a password is the ordinary thing a person does, while a retired login identifier **never** frees its name. Same mechanism, opposite answers, both deliberate, and both asserted so that making them "consistent" is a failing test rather than a tidy-up. **Measured, not asserted: ~46 ms per derivation** at m=19456 / t=2 / p=1. ADR-0032 asks for parameters chosen against a *stated* verification time, and a stated time nobody measured is not stated. The assertion is a **floor, not a ceiling** - a ceiling is a flaky test on a loaded machine, while a derivation completing in under a millisecond is the failure actually worth catching. 46 ms is at the fast end of the usual target and is deliberately **not** raised here: raising the work factor is a capacity decision belonging beside the rate limiting ADR-0032 already names as part of the same design (`P1-TSK-011`), and 19 MiB *per concurrent derivation* means ten simultaneous logins on one instance is ~190 MiB of transient allocation. **The library needs more at run time than its POM declares, and only running it found that**: `spring-security-crypto` 7.1.1 lists exactly one dependency, an *optional* assertj, and in fact needs **BouncyCastle** to derive and **spring-core** to verify - each arriving as a separate `NoClassDefFoundError` from a test using the real encoder, one at construction and one at `matches`. A test double would have found neither and the failure would have arrived at the first real login. So `identity` **does** take a Spring Framework runtime dependency, recorded plainly rather than described away, because the tidy description - "a standalone jar" - was mine and was wrong three times running. **The cold regeneration earned its place on its first outing since `P0-TSK-042`**: a **warm** regeneration recorded three new components, and the mandated **cold** run added a fourth - `jackson-base-2.21.5.pom`, a **descriptor and not a jar**, which is exactly that finding's signature. Proven complete by a second run against a separate empty `GRADLE_USER_HOME` with enforcement on and no write flags. **Two existing security rules fired, and they got different answers.** `secretsAreWrapped` flagged `CredentialType.PASSWORD` - an **enum constant**, a value of its own enum type that can never be a secret; renaming was the alternative and `PASSWORD` is exactly what that constant should be called, with every future `TokenType.BEARER` hitting the same thing. The rule gained a **structural exclusion for enum constants**, with the `P0-TSK-041` precedent (the synthetic `$VALUES` array, excluded for the same reason), **proven load-bearing** by removing it and watching the rule fire again. **The second I answered by deleting my own code, and that is the more useful finding**: the rule then flagged `passwordDeriver()` and `credentialStore()` - `@Bean` factory methods, not accessors - which was the *second* security-rule modification in one task and a signal worth heeding rather than pushing through. The honest answer was that **the wiring should not exist**: nothing consumes either bean, `EXECUTION_PROTOCOL.md` rule 3 asks for a seam only, and the port is the seam. Deleting them removed the false positives without touching a control. A third - a `COMMENT ON` body reading as `secret: <value>` - was answered by rewording prose, which cost nothing; the rule's inability to tell a SQL comment from a credential assignment is recorded rather than widened. **Scope kept, with the owning task named for each omission**: verification and upgrade-on-use are `P1-TSK-008` (`isWeakerThan` exists, is tested, and is called by nothing), the endpoint and registration integration are `P1-TSK-026`, session revocation on change is M1.3, and each enum carries one value because rule 3 forbids WebAuthn now while the enum *existing* is the seam ADR-0032's follow-up needs. **Seven mutations, all caught.** 713 hermetic tests, 235 database tests. |
| 2026-09-06 | **`P1-TSK-006` complete - milestone M1.1 closes, 6 of 6.** The platform's **first endpoint**, its **first domain events**, its **first emitted audit records**, and the first real user of `P0-TSK-017`'s `@RequiresIdempotencyKey` - which arrived two phases earlier than `API_CONVENTIONS.md` expected. One transaction creates a Party, a Customer and an Identity or none of them, across two modules and two schemas, and it is what makes ADR-0029's deliberately absent cross-schema foreign key true. **Delivered without the credential leg**, on instruction to implement this task alone: the item declares `Deps: P1-TSK-007`, which is `TODO`. Two consequences are recorded rather than absorbed and carried as the new `P1-TSK-026` - a registered Identity **cannot yet acquire a credential**, because `POST /v1/me/credential` needs a session, a session needs authentication and authentication needs a credential; and adding a required `password` later is a **`BREAKING`** change to a published `/v1` contract on the platform's first endpoint. Neither is fatal, since no client exists; both are worse left implicit. **A backlog defect was found in the course of it**, the third of its class here: this task sits in M1.1 and depends on a task in M1.2, while `PHASE_1_PLAN.md` §11 states M1.1's acceptance as *"a Party, a Customer and an Identity"* with no credential - so the plan and the item's own `Deps` disagree, and the plan is the internally consistent one. **The response body is empty, and that is a security decision rather than laziness.** `API_CONVENTIONS.md` §6 states plainly that the idempotency key **is not a secret and is not redacted**, so anybody who has seen one - from a proxy log, an access log, a client's own logging - can replay this unauthenticated endpoint and receive whatever it returns; publishing the three identifiers would hand a stranger identifiers belonging to somebody else, and nothing in Phase 1's API surface consumes them. There is no replay header for the same reason: telling a caller it was a replay tells a replaying stranger that the login identifier exists. **Registration is permanently the one endpoint whose idempotency scope cannot carry a principal**, because it is the endpoint that creates one, and ADR-0004 asks for the command type *and* the owning principal. The residual is stated rather than glossed - an attacker holding a key *and* knowing the exact login identifier and display name can obtain a replay - and what bounds it is precisely the empty body, so what they learn is that the request succeeded and nothing more. Scoping by the login identifier instead was considered and **rejected**: it is `CONFIDENTIAL` and `idempotency_record.scope` is `INTERNAL`, so it would have forced a Phase 0 column to be reclassified, which is the one thing ADR-0022 says must not happen. **The credential is deliberately excluded from the request fingerprint and stays excluded** when `P1-TSK-007` lands: `request_fingerprint` is a durable single-round SHA-256, so hashing a body containing a password would store an offline-crackable derivation of it - `INV-IDN-01` violated by the idempotency mechanism itself. **A savepoint is what makes a collision reportable at all**: a taken login identifier arrives as a unique-index violation and PostgreSQL *aborts the transaction* when it raises one, so without a savepoint nothing further could be written - the idempotency outcome included - and the client's retry would re-run the command rather than replay its refusal. **A pre-flight `SELECT` is not a substitute and is documented as such**: two instances would both see the identifier free, both insert, and one would get `23505` anyway, so a pre-check makes the defect rarer rather than absent, which is worse. **`enterSystem()`, and this call site stays.** The caller is unauthenticated, so the platform is the only honest actor; attributing the action to the Party it creates is circular and, decisively, unavailable on the refusal path where nothing was created, and an actor that differs between success and failure is worse than a uniform honest one. What carries the information is the audit record's **target** - the attempted login identifier, on both paths, which is the one place `PHASE_1_PLAN.md` §10 permits it. `SECURITY_ARCHITECTURE.md` now says why "revisit every `enterSystem()`" does not mean "remove every `enterSystem()`". **`app` orchestrates and owns nothing**: registration spans two bounded contexts and belongs wholly to neither, and either module hosting it would have to depend on the other, which the isolation tests forbid - so `app` contributes two calls and a transaction while each module writes its own rows, events and audit record. `MODULE_ARCHITECTURE.md` §Transaction boundary listed the permitted cross-module transactions and **was stale**, naming only transfer-plus-posting and resolution-plus-adjustment, neither of which exists; registration is the first of the three to be real. **`EventPayload` is a builder with a charset rather than an object mapper, and it earned that on its first run** - `INV-AUD-02` keeps personal data out of event payloads and a general mapper would serialise `put("displayName", name)` happily, so it refuses any value that is not an identifier or an enumerated name, and it immediately caught a real mistake because `EntityId.toString()` renders `PartyId(uuid)` rather than a bare UUID. Its limit is written down: an event needing richer structure needs the wire-format decision taken, not worked around. **Causation at a flow root had no answer and now has one** - `Correlation` leaves it null so a root is distinguishable from a cycle while `EventEnvelope` requires it non-null, and the honest answer is that the request caused it: a value that looks self-referential and is not, because the correlation identifier is on the idempotency record and on the audit record of the same transaction. **Two defects in the published contract, both found by generating it rather than reasoning about it**: springdoc published **`"200": "OK"`** for an endpoint that has never returned 200, because a `ResponseEntity` gives it no status to read and a generated client would have treated the real response as unexpected - fixed with `@ResponseStatus(CREATED)`, the only form that reaches the document; and it tagged the operation **`registration-controller`**, publishing an internal class name that an ordinary rename would turn into a contract diff, now stripped for the same reason `servers` already was. **A third defect was in the contract harness itself**: `OpenApiDocument` *replaced* the whole `components` node, correct while `paths` was empty and silently wrong the moment a handler declared a request body, so the published document referenced a `RegistrationRequest` schema that had just been discarded - caught by `everyReferenceResolves`, a guard the `P0-TSK-026` review added against exactly this class of defect, working two tasks later. **The `BREAKING` labels on the diff were reviewed and accepted**: `/paths` going from `{}` to populated, a new schema's `required` list and `requestBody: required` are all additions of structure that did not exist, and no client can be broken by an endpoint that was never there. **Seven mutations. One survived, and it found a real gap in a security test** - `aReplayIsNotAnnounced` compared response header **names**, so an injected `Idempotent-Replay: false`/`true` walked straight through it, the name being identical on both while the value is the whole disclosure; it now compares names *and* values, excluding only the correlation identifiers and `Date`. **One defect in my own test, found by the full tier rather than in isolation**: the referential-integrity check asked whether *any* orphaned identity existed anywhere, and `PartyAndIdentitySchemaDatabaseTest` creates orphans **on purpose** to prove ADR-0029's missing foreign key really is missing - both facts are true and about different things, so it is now scoped to the registration under test. **And one guard was generalised rather than extended**: `FinappApplicationTest` listed the three modules allowed to contribute beans and `party` and `identity` now legitimately do, so the allowed set is derived from the classpath - the stale-list defect this repository has met in CI's task list, in a coverage guard and in a privilege check, closed the way it has been closed each time. 686 hermetic tests, 215 database tests. |
| 2026-09-05 | **`P1-TSK-005` complete - M1.1 is 5 of 6.** The phase's highest-risk task: three aggregates in two modules, three tables in two schemas, fifteen columns each classified at its ceiling. `DELIVERY_PLAN.md` §17 names collapsing them as Phase 1's top risk, so **the acceptance criterion is a test that fails if any two are merged** - written as the four shapes a merged model *cannot represent* rather than as an abstract claim: a person who is not a customer (a beneficial owner we must record for KYB), a customer who is not a person (an organisation), one Party holding a retired login and its replacement, and lifecycles that move independently, because a credential compromise must suspend the login and not the commercial relationship. A status added to `Party` fails it. **Two invariants are enforced only by the database, because no aggregate can enforce them**: at most one *live* relationship per party, and a login identifier used once ever, are rules **across** aggregates of the same type - an aggregate sees only itself, so only the database arbitrates between two concurrent transactions, which ADR-0014 says is the normal case rather than the exception. **The two uniqueness rules deliberately point opposite ways, and that asymmetry is the sharpest decision here**: a closed relationship frees the party for a new one (a partial index, because re-establishing a relationship is legitimate), while a closed login **never** frees its identifier (a total index, because reissuing it would let a new person authenticate with a name appearing in someone else's audit history, making every record naming it ambiguous about which person it meant). **`identity.identity.party_id` carries no `REFERENCES` clause**, asserted in the migration and by a test that fails if one is added, and the cost is stated rather than hidden: the database will accept an identity for a party that does not exist, and what prevents it is the registration transaction writing both in one commit - a property a test can assert, not the schema. An FK there would be coupling neither Gradle nor ArchUnit can see and would turn ADR-0001's stated escape into a data migration. **`Party` has no lifecycle**, which reads as an omission and is the design: existence has no states, and every state people reach for - inactive, closed, archived - is a statement about a relationship or a login, each of which has its own table, so a status on `Party` would be one fact recorded in two places and free to disagree. **`LoginIdentifier` is deliberately not an email address** - an identifier that is also a contact channel cannot be changed without changing how someone logs in, nor verified without blocking login - and its charset excludes `@` specifically, so the confusion cannot arrive silently through the first person who types an address. **One deliberate non-change, recorded rather than left implicit**: the transition exceptions carry their states but not the identifier, because an exception is serializable and `EntityId` is not, and making it so would oblige every existing identifier type to declare a `serialVersionUID` - a change to proven Phase 0 code this task has no business making (`EXECUTION_PROTOCOL.md` rule 4). It is the third time this project has met that requirement, after `CurrencyCode` and `IdempotencyKey`. **Five mutations, all caught**: `CLOSED` made non-terminal (four tests), the aggregate's transition check removed (six), the partial unique index dropped, a status added to `Party`, and a cross-schema foreign key introduced. 668 hermetic tests, 187 database tests. |
| 2026-09-04 | **`P1-TSK-004` complete - M1.1 is 4 of 6.** The connection budget: `instances x maximum-pool-size <= server max_connections - reserved`, declared as configuration and enforced by `ConnectionPoolSizingGuard` at startup. Shipped as 10 x 8 = 80 against 100 - 12 = 88. **The defaults fail it, which is why this is a guard and not a note**: Hikari's default pool is 10 and PostgreSQL's `max_connections` is 100, so ten instances exhaust the server **before a single connection does any work** - and ADR-0014 says N is never 1. Nothing in either default notices; the instances that lose the race fail readiness with *connection is not available*, which reads as the pool being too small or the database being slow, and is neither. It is the worst shape of operational failure, appearing only during a deploy, a scale-out or a restart storm - the moments when diagnosis is hardest - with the symptom pointing away from the cause. **The obvious repair is the wrong one, and that is the finding**: dividing `max_connections` by the instance count treats the limit as a budget to spend when it is a ceiling not to hit. Every connection is a backend process with its own memory, and PostgreSQL throughput stops improving once the machine's cores are busy - past that the extra connections queue **inside** the database, where the queueing is invisible to the application and appears as latency on every query rather than as a pool timeout on one. So the pool is sized small for throughput, and "does the fleet fit" is a separate question asked afterwards; conflating them produces a pool that is both too large and, at scale, still not enough. **Checked in two places because they are two claims**: the guard proves the rule at startup, and `ConnectionPoolSizingIsConfiguredTest` proves the shipped numbers satisfy it in the build - a guard alone would leave a violating configuration to be discovered by a rolling restart, one instance at a time. **Verified against a running instance**, which `DOD-OBS` requires, in all three directions: the shipped configuration starts; `FINAPP_DB_INSTANCES=20` is refused with the arithmetic and the fix in the message; and raising `max_connections` to 200 is accepted, so the guard never forces the pool to be the thing that gives way. **Two limits stated rather than implied**: it cannot verify `max_connections` against the live server and does not try - it runs before the pool is used and one that queried the database would fail for a database that is merely down, so the value is a **declaration** and a wrong declaration is a wrong answer; and the arithmetic assumes each instance holds its **full** pool, which is why `minimum-idle` equals `maximum-pool-size` and why a test asserts that rather than trusting it. **It also does not shrink the pool to make the numbers work** - that would change a deployment's capacity on its own initiative when the right answer is often to raise `max_connections` or run fewer instances. `DISTRIBUTED_EXECUTION.md` gains §4a, the one contended resource none of the protocols in §3 can help with: no lock, no constraint and no idempotency key makes a connection available. 628 hermetic tests, 174 database tests. |
| 2026-09-04 | **`P1-TSK-003` complete - M1.1 is 3 of 6.** `party` and `identity` exist: two modules, the documented dependency direction, a schema each with its own Flyway history, and an `AuditableAction` enum each. **Three schemas now, all owned by `finapp_migrator` and never a superuser**, each `REVOKE ALL ... FROM PUBLIC` with `finapp_app` granted `USAGE` and nothing else - checked against a live database rather than asserted, and the migrations apply to an empty database, validate, and re-apply idempotently, which is exactly what CI does. **The acceptance criterion was proven rather than assumed**: a `double` planted in `PartyAuditAction` fails **two** floating-point rules in `:app:test`, so every existing architecture rule protects the new modules without being edited - which is what deriving coverage from the classpath was for. **No cross-module dependency, enforced structurally**: `PartyModuleIsolationTest` and `IdentityModuleIsolationTest` assert neither module sees the other nor `app`, each with a non-vacuity half asserting it *does* see `platform` and `sharedkernel`. That is ADR-0029's boundary at the classpath - `entitiesAreNotReferencedAcrossModules` catches the reference, this catches the dependency that would make one possible, and a compile-time edge between them is the first step toward the shared `users` table the ADR exists to prevent. **Those tests exist because a guard demanded them**: `TestTaxonomyTest` failed with *"a module contributing no test classes means the sweep did not reach it"*. **CI's `:platform:flywayMigrate` was a list of one** and two more schema-owning modules made it stale; now unqualified, so a fourth is covered without anyone remembering - the `:platform:databaseTest` shape the `P0-TSK-027` review found. **Three auditable actions catalogued**, one in `party` and two in `identity`, both admin actions requiring a reason because they are taken against someone else's account and this is the module where an insider with a legitimate permission does the most damage; deliberately few, because a registry may list an action before its code exists but not before its **design** does. **One defect found by applying the migration rather than reading it**: an unescaped apostrophe in a schema `COMMENT` (`the platform's`), rejected at SQLState 42601. **And one pre-existing defect found by the acceptance probe, recorded not fixed** (`P1-TSK-025`): `./gradlew architectureTest` runs an ArchUnit suite's `@Test` methods and **not its `@ArchTest` rule fields**, so the tier named for architecture rules executes none of them - 2 cases against `test`'s 7. Enforcement is intact because `build` runs `test`; what is lost is the tier task's meaning, which is the "green while checking nothing" failure met five times here and the same root cause as the `ModuleBoundaryRulesTest` skip `P0-TSK-036` found. 619 hermetic tests, 174 database tests. |
| 2026-09-04 | **`P1-TSK-002` complete — M1.1 is 2 of 6.** ADR-0034: **the platform mints the correlation identifier on every request and never adopts an inbound one.** This closes the widest-reaching disclosure channel in the platform, recorded as debt since `P0-TSK-033` and named risk **R1** by the transition: the value reaches every log line as a top-level ECS field, every span, four durable columns and every problem-detail body, and a caller could put anything in it — `jane.doe@example.com`, `acct:GB29NWBK60161331926819`, `customer-1990-05-14` and `+447700900123` were all confirmed accepted by probe. **The finding is that narrowing the charset does not work, and it is the option the task offered first.** A date of birth, a phone number and an account number are alphanumeric, so any charset still able to carry a UUID or a W3C trace value carries them too; of the four probed values, narrowing to `[A-Za-z0-9_-]` would have stopped two and **left two** — a fix that closes the debt row and leaves half the risk. A lexical control cannot express the property, so the control had to be **structural**. A well-formed caller value now becomes a *client reference*: echoed in `X-Client-Correlation-Id` and carried nowhere else. **The client keeps its join** — it logs the identifier we return, and the echo lets a gateway match a response to a request it no longer holds a connection for; what is deliberately lost is searching *our* logs by a caller-chosen string, which is precisely the property that made the disclosure possible. **The test asserts at the source rather than sink by sink**: all four columns, the MDC and the span attribute read from one `CorrelationContext`, so `CallerCorrelationIsNotPropagatedTest` asserts what that context holds during a request — covering sinks that do not exist yet — and checks the span separately, because it is stamped by a span processor rather than by anything reading the context on that thread. The four probed values are the test data on purpose: a synthetic `client-flow-77` would prove the mechanism and not the risk. **A recorded flake was closed on the way past**: `doesNotContain("bad")` fails about one run in 137, because a UUIDv7 hex string contains `bad` roughly 0.7% of the time — the intent was right and the method was wrong, so it now pins the **shape**, a platform-minted UUIDv7, which nothing derived from caller input can satisfy. **The completion gate found the one real gap**, and it was in the published contract rather than the code: `X-Client-Correlation-Id` is set on every response and documented in `API_CONVENTIONS.md`, and it was **absent from `openapi.json`** - because that document's response headers are hand-injected by `OpenApiDocument`, so nothing would ever have added it. A response header a client is told to use, missing from the machine-readable artefact client generators read. Published, with `required: false` since it appears only when a well-formed value was supplied. The classifier labelled `ADDED required=false` **BREAKING**, which is it erring in the safe direction by design (`P0-TSK-026`: a false BREAKING is visible and fixable, a false COMPATIBLE fails at the customer); the diff was reviewed and is **43 added lines and zero removed**, so the baseline was accepted rather than the classifier changed. Inbound behaviour was never in the contract - `X-Correlation-Id` is declared as a *response* header only - so that half needed no change. Four mutations, all caught — and the MDC leak was caught **twice**, the second time by `onlyCorrelationContextWritesTheMdc` from `P0-TST-008`, which is defence in depth working without being asked to. 615 hermetic tests, 173 database tests. |
| 2026-09-04 | **`P1-TSK-001` complete — Phase 1 is `IN_PROGRESS`, milestone M1.1, 1 of 6.** ADR-0033: **explicit SQL through `JdbcClient`; no ORM, no persistence context, no generated repositories.** It closes unresolved question 12, open since `P0-TSK-011` and brought forward from Phase 3 by the transition because Phase 1 creates nine tables and deciding after the first repository is written means writing the second one twice. No new dependency — `spring-jdbc` has been on the runtime classpath since `P0-TSK-027` added a `DataSource` for readiness. **The decisive argument is the privilege model rather than taste**: `INV-HIST-03`, `INV-HIST-01` and `INV-LED-03` are enforced at `DB-PRIVILEGE` by `finapp_app` holding **no `UPDATE` and no `DELETE`**, and that is worth exactly as much as the guarantee that nothing emits a statement nobody wrote — Hibernate's dirty checking emits `UPDATE` on its own initiative, at a flush point decided by code far from the write, so whether the forbidden statement is issued depends on whether an entity happened to be dirty. That is the shape of defect that passes every test and fails in production. `DB-PRIVILEGE` ranks second to `DB-CONSTRAINT` in the catalogue, but it is the strongest mechanism available for **forbidding an operation**: a `CHECK` constraint cannot express "this role may not `UPDATE`". **Spring Data JDBC came far closer and was rejected on two concrete behaviours rather than general unease**: `save()` deletes and re-inserts child collections, and against superseded credentials those children *are* the history; and application-minted UUIDv7 identifiers arrive non-null, so it defaults to `UPDATE` on a new aggregate — the `Persistable.isNew()` trap, sitting precisely on the registration path. Both are workaroundable, and needing a workaround on the **first** aggregate is the signal. **The seam it closes was explicit in the code**: four kernel ports are generic over the unit of work and three said *"a JDBC `Connection` today, whatever the Phase 3 decision produces later"*. `T` is now `Connection` permanently; the type parameter **stays**, because removing it is a refactor of proven Phase 0 code with no correctness benefit (`EXECUTION_PROTOCOL.md` rule 4), and all five javadocs were corrected because they described a decision that had moved phases and then been taken. **Transactions are begun explicitly** (`TransactionTemplate`), since `@Transactional` fails *silently* on self-invocation and registration must write two modules plus audit plus outbox in one commit. **Enforced rather than recorded**, which `DOD-ARCH` requires: `NoObjectRelationalMapperTest` fails the build if a JPA, Hibernate or Spring Data artefact reaches the application's **runtime** classpath — catching one that arrives transitively behind a starter, the way it would actually arrive — and writing it **found a real gap**, since `MODULE_ARCHITECTURE.md` §6 had forbidden JPA in `sharedkernel` **only**, which is not where anyone would add an ORM. **A false positive was caught before commit and is the finding worth keeping**: the first forbidden list matched `hibernate-` and **failed on the real classpath**, because `hibernate-validator` is Bean Validation, arrives with `spring-boot-starter-validation` from `P0-TSK-025`, and has nothing to do with persistence. A rule that forbids a correct dependency is a rule somebody turns off — ADR-0019's own reasoning for keeping `key` out of the `secretsAreWrapped` vocabulary — so the list now names the ORM's own artefacts, and a test keeps the carve-out honest by asserting it is still needed, the way `P0-TSK-041` proved its two exemptions load-bearing. The classpath property moved from `databaseTest` alone to **every** test task, because a second guard in a second tier is exactly the `:platform:databaseTest` shape the `P0-TSK-027` review found: a list of one that goes stale the moment there are two. **No new shared state** — `DISTRIBUTED_EXECUTION.md` §3 gains no row, and that absence is the argument: claim-by-insert, bounded `lock_timeout`, conditional `UPDATE … WHERE`, transaction-scoped advisory locks, savepoints and writing on the caller's connection all stay expressible unchanged. Four mutations, all caught - the fourth added by the completion gate, which found that §6 named a class and nothing checked it existed. 610 hermetic tests, 173 database tests. |
| 2026-09-04 | **`P0-TSK-042` complete. Exit criterion 7 closes; Phase 0 is `COMPLETE` — all twelve criteria — and Phase 1 is `READY`.** A remote was added and the CI workflow executed for the first time: run [33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202), commit `04f4a53`, **all four jobs green** — 32 actionable tasks executed, 606 hermetic tests, migrations applied to an empty database, `flywayValidate`, re-applied idempotently, 173 database tests, 119 commits scanned, SBOM clean. **It took three runs, and that is the finding rather than an inconvenience.** Two defects, neither in what Phase 0 designed and neither reachable from this machine. **First: `gradlew` was committed mode `100644`**, so four jobs died on `Permission denied`, exit 126; `core.filemode` is false on Windows so nothing here could observe it, and the sharp detail is that `P0-TSK-001` had explicitly enforced **LF line endings** on that same file so *"Linux CI is not broken by a Windows checkout"* — it reasoned about the file's bytes and not about its mode. Fixed with `git update-index --chmod=+x`; `gradlew.bat` stays 644, and `infra/scanner-pins.sh` stays 644 because it is **sourced, never executed**. **Second: `gradle/verification-metadata.xml` was complete for a warm dependency cache only.** Gradle does not re-read metadata descriptors it has already parsed, so `--write-verification-metadata` over a warm `GRADLE_USER_HOME` records fewer artefacts than a cold resolution needs; the file had only ever been generated on this machine, over months of warm builds, and was therefore complete here and incomplete for CI and for any new developer alike. Regenerating against an **empty** home added **10 components and 23 artefacts, every single one a parent POM or a BOM `.module`** — not one jar, which is what identifies the mechanism rather than guessing at it — and removed nothing, so ADR-0025's merge claim held and was checked rather than assumed. Proven on a **second, separate** empty home with verification enforced and no write flag. `README.md` §7a gains the cold-regeneration procedure and ADR-0025 the consequence; §7's "this repository has no git remote, so the pipeline has never executed" was true since the first week and is now corrected. **A third finding belongs to the scan rather than the build.** gitleaks met this repository's own 119 commits for the first time — `P0-TSK-031` proved its teeth against a *throwaway clone*, deliberately, because a dummy secret committed here would make the scan red for ever — and produced exactly one finding, a **false positive**: `generic-api-key` fired on `A_KEY`, a UUID fixture idempotency key. An idempotency key is not a secret, and that is not a convenience invented to clear a build: **ADR-0019 settled it** when choosing the vocabulary `secretsAreWrapped` matches on, and left `key` out of it because a rule with false positives is a rule somebody turns off. `.gitleaks.toml` allowlists **the exact literal and nothing else** — not the rule, which stays enabled everywhere; not the file, so a real credential added to that same test still fails; and not "UUIDs in test sources", which would mask a genuine UUID-shaped API key committed into a test. Proven narrow rather than asserted narrow: a random high-entropy credential planted in the same file, same line, same rule, in a throwaway clone, still fails with exit 1 — **after the first attempt at that demonstration reported a pass and was wrong**, having planted `AKIAIOSFODNN7EXAMPLE`, AWS's own documentation key, which gitleaks allowlists by default; the mutation had never landed, which is the fourth time this project has met that defect class. **Every one of the three sat in the machinery that checks the work rather than in the work**, which is the recurring finding this phase recorded across its last twenty tasks, arriving once more at the gate built to catch exactly that. **The transition's judgement was tested and held**: it had refused to record Phase 1 `READY` while criterion 7 failed, on the argument that the blocking criterion is the one whose purpose is to prove the gates execute at all — and the first run failed. Had the gate been waived, Phase 1 would have been entered on a build that could not run anywhere but one Windows machine. 606 hermetic tests, 173 database tests. |
| 2026-09-04 | **Phase 0 → Phase 1 transition conducted.** The gate audit is the deliverable and its result is that **Phase 0 remains `IN_PROGRESS`**: 12 of 13 audit areas `PASS`, one `PARTIAL`, and 11 of 12 universal exit criteria hold — criterion 7 fails because the suite has never run in CI, there being no git remote. `PHASE_GATES.md` §4 prescribes the consequence and §1 is explicit that *"shipping through a failed gate"* is the failure; remediation is `P0-TSK-042`, the sole item of the new `P0-EPIC-13`, and it cannot be performed from inside the repository. **The transition found something a formality would have missed.** Phase 1's seven identity properties — credential irreversibility, per-credential derivation parameters, immediate session revocation, authentication-is-not-authorization, MFA bypass paths, recovery escalation, account enumeration — existed **only as exit-criteria prose** in `PHASE_GATES.md`: no stable ID, no enforcement mechanism ranked by strength, no named verification method, no mutation-demonstration row. Every other property on this platform gets all four, and the asymmetry was backwards, because Phase 1 is the phase whose *product* is security and the alternative was writing credential-handling code against prose. Catalogued as `INV-IDN-01`…`07`, taking the platform to **71 invariants** — and the Phase 0 mutation register was verified unaffected, since it is scoped to Phase 0 and a Phase 1 invariant with no test yet is correct rather than a gap. **Phase 1 is recorded `PLANNED`, not `READY`, and that is a deliberate deviation from the instruction that produced this work**: eleven of twelve entry criteria are met and criterion 1 — the previous phase is `COMPLETE` — is not, so §1 forbids `READY`; the criterion blocking it is precisely the one that proves the gates execute at all, which makes forcing it the worst available place to make an exception. It flips on the first green CI run with no further planning. **Four irreversible decisions taken rather than deferred into implementation.** ADR-0029: Party, Customer and Identity are three aggregates in two modules — the pressure to collapse them into one `users` table comes from the simplest first story, and the cost lands on a person who is not a customer, a customer who is not a person, a retired login, and staff; unpicking it later means migrating identity out of a table financial records already reference, where `INV-HIST-01` forbids rewriting the history pointing at it. ADR-0030: sessions are server-side and authoritative in PostgreSQL, because a self-contained JWT makes validity a property of a signature rather than of current state, so *"log out everywhere"* becomes a promise the architecture cannot keep (`INV-IDN-03`) — and **assurance is a level rather than an MFA boolean**, since every real bypass is a route producing a session a boolean calls fine. ADR-0031: permission at the boundary **and** ownership in the domain, always both — collapsing them is the most common authorization defect in financial software, where a customer with a legitimate `transfer:create` permission uses it against someone else's account and every check passes; `BOUNDED_CONTEXTS.md` context 2 renamed to *Identity, Authentication & Authorization*, since the list had been omitting a concept `CLAUDE.md` forbids collapsing. ADR-0032: a credential stores the derivation **and** the algorithm and parameters that produced it, because the decision usually missed is not which algorithm but where the parameters live — a global work factor cannot be raised, as raising it changes only new credentials and nothing records what the old ones used. Phase 1 planned in full: 3 bounded contexts, 6 aggregates, 16 commands, 10 events, 5 state machines, 9 tables, 14 endpoints, 12 failure scenarios, 7 milestones, and **25 backlog items** elaborated to task granularity with acceptance criteria and DoD profiles. The must-not-implement list is explicit and three minimal foundations are justified individually. No application code written. 606 hermetic tests, 173 database tests. |
| 2026-09-03 | `P0-TSK-017` complete. **The Phase 0 backlog is 62 of 62.** `@RequiresIdempotencyKey` declares the requirement on a handler or its controller, and an **interceptor** enforces it. **An interceptor rather than a filter, and that is load-bearing twice**: a filter runs before the dispatcher has chosen a handler, so it could not know whether *this* endpoint declares the requirement without a second, drifting copy of the routing table; and a filter runs outside `@ExceptionHandler`, so its rejection would be the container's default page rather than the error contract — the problem `P0-TSK-025` had to work around by rendering the contract by hand inside its filters. Rejection happens **before the handler is entered**, asserted by counting handler entries rather than reading the response, because for a money-moving command the half of the work done before a late rejection is the half that matters. **The requirement is declared, not defaulted**: requiring the header everywhere would force it onto reads, where it means nothing and would train clients to send a value nobody uses, and the annotation is the greppable list of endpoints claiming to move money. New error code `api.IdempotencyKeyRequired` (422), distinct from `api.ValidationFailed` on purpose — a client can automate "generate a key and retry" but not "your request was invalid"; the published contract gained six lines, every difference `COMPATIBLE`, and no probe fixture leaked into it. **A real gap was found by following `DATA_CLASSIFICATION.md` §5**, which classifies this column as a caller-supplied identifier: `IdempotencyKey` bounds length and blankness because those are the table's `CHECK` constraints and carries **no charset**, so a caller could have put CR/LF into a value the platform logs, stores durably and will put on an audit record — a forged log line. Closed with the same default-deny charset the correlation identifier uses, and unit-tested rather than driven over HTTP because the JDK's own `HttpClient` refuses to **send** CR/LF, while a hostile client writing raw bytes to a socket is not bound by that politeness. **The audit clause was corrected rather than approximated**: the registry now exists, but `AuditRecord` has no field for a key and nothing in Phase 0 writes an audit record in an HTTP flow, so there is nothing to record it on. Adding a column would be a schema change nothing populates, and unlike actor attribution **no history is lost by waiting** — the test ADR-0010 applies. Transferred to Phase 4; the third Phase 0 criterion to need this correction after `P0-TSK-014` and `P0-TSK-028`. Five mutations, all caught. 606 hermetic tests, 173 database tests. |
| 2026-09-03 | **Exit criterion 11 closed: the three Tomcat advisories are fixed and the dependency scan reports zero vulnerabilities.** `tomcat-embed-core` pinned to **11.0.25** in the version catalog and applied as a dependency **constraint** in `app`. **There was no patch release to move to** — Spring Boot 4.1.1 is the latest stable 4.1.x and `4.2.0-M1` is a milestone, so overriding the BOM was the only route, and it is the only deliberate deviation from it. A **constraint** rather than `force`, because `force` wins against a *higher* version too: a future Boot managing 11.0.26 would have been silently held back at 11.0.25, which is exactly the pin-rot ADR-0026 exists to prevent. All three embed artefacts are constrained together — only `-core` is affected, but they ship as one release and share internals. **Verified rather than assumed at four points**: the scan goes 3 CRITICAL → **0**; the lockfiles record 11.0.25 across every configuration; the 68 slice tests boot a real Tomcat 11.0.25 on a random port, so compatibility is demonstrated; and the verification metadata plus all six lockfiles were regenerated in **one invocation**, per the `P0-TSK-035` finding that neither order works alone. **The exposure is recorded accurately rather than dramatised**: all three are authentication and authorization bypasses — security-constraint bypass, DIGEST replay, FORM bypass — and Phase 0 has *no authentication at all*, so they were practically unexploitable here; the gate does not grade on exploitability and Phase 1 brings precisely what they attack. **One claim was corrected by probing**, which is the finding worth keeping: the build comment first said the lockfile would reject removing the constraint, and it does not — with the block deleted, resolution still yields 11.0.25 because the lock applies its own `{strictly 11.0.25}`. The lock *keeps* the version; it does not object to the loss. A regression needs the deletion **and** a lock regeneration, and the `dependency-scan` job is the control. No test was added to assert the version: it would duplicate the scan and need editing on every legitimate bump, which is the stale-list defect this repository has met four times. **Eleven of twelve exit criteria now hold**; criterion 7 — the suite has never run in CI — is the only one left and cannot be closed from inside the repository. 578 hermetic tests, 173 database tests. |
| 2026-09-03 | `P0-DOC-012` complete; **`P0-EPIC-12` closes, and with it the Phase 0 backlog**. The phase review, all eight `PHASE_GATES.md` §4 areas in order, and ADR-0001 through ADR-0028 moved to `Accepted`. **The review finds the exit gate does not pass, which is what conducting one is for.** Ten of twelve universal criteria hold; criterion 11 fails on three HIGH/CRITICAL Tomcat CVEs and criterion 7 on a suite that has never run in CI. §4's closing rule prescribes the consequence — the phase **remains `IN_PROGRESS`** — and §1 is explicit that moving backwards from review is normal while *"shipping through a failed gate"* is the failure. Neither failure is architectural: Phase 0's design work is done. **Two areas could not be conducted as written and say so rather than reporting a pass.** Area 2 asks for one real posting walked end to end and Phase 0 creates none, so it verifies the kernel a posting will be built from instead and names what it cannot check. Area 5 enumerates the three registered privileged actions and finds **none of them is emitted** — an abandoned event, meaning consumers permanently not receiving a fact that happened, is recorded only in logs, which ADR-0010 is explicit do not count as an audit trail. **The ADRs were accepted despite the open failures, and the reasoning is recorded rather than assumed**: criterion 10 is a *precondition* of the gate rather than a reward for passing it, so accepting them is work toward it — holding ADR-0003 at `Proposed` because Tomcat has a CVE would be theatre, since the decisions were taken, implemented and tested and none is contingent on either failure. **Two documentation drifts found by hand-diffing what no guard covers**: the pinned-version table in `SYSTEM_ARCHITECTURE.md` omitted Prometheus, Grafana and WireMock — the first two being `compose.yaml` images that `verifyInfrastructureVersions` guards, so the table under-reported the coverage of the very check described beneath it. Both closed. The review also records the phase's recurring finding in one place: across the last twenty tasks the defect was almost never in production code but in the thing doing the checking — a rule that could not fail, a sweep reading a stale class file, a regex that read past its section, a coverage list checked in one direction, a register asserting a demonstration nobody had performed. 578 hermetic tests, 173 database tests. |
| 2026-09-03 | Task completion review of `P0-DOC-011`. **One important finding, and it is the one a glossary is most dangerous for: a factual contradiction with the document that owns the decision.** `Risk Score` was attributed to `risk`, while `MODULE_ARCHITECTURE.md` §4 lists it under `credit` beside `Credit Score`. Found by auditing all 62 owner attributions against every `Owns:` line rather than by reading — 15 are this document's judgement because the register does not name them, and exactly one of the remaining 47 disagreed. The register is followed, because ADR-0012 makes it the authority on ownership and `P0-TSK-006` verified single ownership by script; **a glossary must not settle an ownership question by quietly disagreeing with the document that owns it.** The underlying ambiguity is real and is recorded rather than resolved: if a risk score measures fraud and abuse — which is how `CLAUDE.md` contrasts it with a credit score — then `risk` is where it belongs, and that is a Phase 10 or 13 decision. Two guards added, both proven by mutation: an owner contradicting the register now fails the build, and every `INV-*` the glossary cites must exist. The second was written after auditing all **23** citations by hand and finding them sound — a check worth having anyway, since nothing else would notice one going stale. Nine mutations, all caught. 578 hermetic tests, 173 database tests. |
| 2026-09-03 | `P0-DOC-011` complete. The domain glossary: 62 terms, each with what it **is**, what it is **not**, and the module that will own it — plus all eight `CLAUDE.md` §Domain Distinctions groups contrasted under headings that repeat the group wording exactly. **The `Not:` line is the deliverable rather than decoration**: a definition alone does not stop a collapse, because two definitions can each be correct and still be applied to the same thing by two people; naming the concept a term is confused with is what turns "these are different" into something a reviewer can point at. **Comparing the two lists mechanically found they disagree** — seven terms are forbidden from being collapsed that the canonical list never names (`Authentication`, `Transaction`, `Operational Account`, `Underwriting`, `Customer Payment`, `Merchant Settlement`, and `KYC`, which is the *process* and distinct from the canonical `KYC Case`), so a glossary covering only the canonical list would have left undefined exactly the terms the rule is about. It is therefore the **union**, and `DomainGlossaryTest` enforces that in both directions: the reverse check stops the glossary becoming a second, unguarded home for vocabulary its owning document should define, which is the same rule that keeps `ERROR_CONTRACT.md` the only list of error codes. **`external` is an owner, not a blank** — a PSP is a company we contract with, and modelling one as our own state is the first step towards a domain that belongs to a vendor (ADR-0008). **Nothing in the glossary is implemented and it says so**, checked rather than assumed: no production class is named for any of the 62 terms, which is what `DOD-DOC`'s ban on aspirational statements demands be stated. **A spelling was settled**: the canonical list had `Installment` once against twenty uses of `Instalment` elsewhere, including the module register that assigns its ownership. Seven mutations, all caught — after the guard found **two defects in itself**: `^` without `Pattern.MULTILINE`, which is the defect the `P0-TSK-033` review found in a register parser reproduced here and caught only because this check asserts *presence*; and a `### Term` inside a fenced code block being read as a definition. Two new build inputs, bringing that list to fifteen. 576 hermetic tests, 173 database tests. |
| 2026-09-03 | Task completion review of `P0-TSK-038`. **No critical findings; two gaps in the guard and one wrong claim in the register, all closed — and the register's own guard is what found the wrong claim.** First gap: the register was checked in **one direction only**. `containsAll` says every Phase 0 invariant has a row and nothing about rows the catalogue does not know, so a planted `INV-ZZZ-99` row — an invariant that appears nowhere — passed cleanly. That is the "register describing something that does not exist" defect `ColumnClassificationTest` checks in both directions and `AuditableActionRegistryTest` in three, and it matters for the same reason: an entry that has quietly stopped applying to anything is indistinguishable from one that still does. Closed, deliberately **not** restricted to Phase 0, since a later-phase invariant demonstrated early is welcome and one that does not exist is a typo. Second gap: `everyInSuiteDemonstrationNamesAnExistingMethod` skipped a row whose class it could not resolve, deferring to a sibling test — so on its own it would have passed over an empty sweep having checked nothing. It now asserts it actually resolved every method-naming row. **The wrong claim is the more interesting finding.** Auditing the register against the recorded evidence showed one row where the observed result had been **inferred rather than recorded**: `INV-IDEM-03` cited `P0-TSK-016`'s mutation sweep, which never states that the fingerprint guard itself was mutated. Closed by performing it — removing `fingerprint.matches` from `resolveExistingClaim` fails **two** tests, both named for the property — and in writing that row I named a method that does not exist, which **the task's own guard caught**. That is the best available evidence the guard works, and it also showed the check applies to *every* row naming a method rather than only in-suite ones, so the test was renamed to say what it does. `DOD-DOC` forbids aspirational statements presented as current fact, and a register of demonstrations is exactly where that rule bites hardest. Nine mutations, all caught. 569 hermetic tests, 173 database tests. |
| 2026-09-03 | `P0-TSK-038` complete; **`P0-EPIC-11` closes with it**. The convention requiring every invariant test to be **demonstrated to fail**, made repeatable and — more to the point — enforced. **The task's own criterion is the narrower of two obligations**: it asks for the nine `P0-TST-*` items, while `PHASE_GATES.md` criterion 3 asks for **every in-scope `INV-*`** to have a test that fails when the invariant is broken. The register covers both, all 17 Phase 0 invariants and all 9 items, and `MutationDemonstrationTest` holds it to the invariant catalogue, the backlog and the compiled test classes on every build — which converts criterion 3 from something a human verifies once at the gate into something the build verifies continuously. **A demonstration has two admissible forms and the distinction is the whole point**: an *in-suite* proof — a fixture that violates the rule, asserted to be rejected — runs on every build and cannot rot, while a *recorded* procedure only proves the test had teeth **on the day it was written**, which is what a mutation must be when it drops a constraint, widens a grant or edits production code, because a build must not do those things to itself. The register labels each row, and the guard verifies that an in-suite row names a **method that still exists**, so a claim of continuous proof cannot point at something renamed away. **The audit found a real gap, which is what an audit is for**: `INV-MON-05` (precision preserved in persistence) had a test and **no recorded demonstration** — its identifier appeared nowhere in the project's records. Closed by performing the demonstration rather than asserting it: re-deriving the scale from the currency in `MoneyColumns.read` fails **exactly one** test, and notably **not** `roundTripsEveryCurrencyScale`, which writes amounts whose scale already matches the currency's current minor units so re-derivation gives the same answer — a general test is not automatically the protecting one, and that is why the register names a **method** rather than only a class. **Two limits recorded rather than glossed**: `INV-AUD-01` is demonstrated only in half, since the registry proves a *recorded* action is catalogued and cannot detect a privileged action that writes no record at all (Phase 15 owns the other half); and nothing checks that a recorded procedure still reproduces, which is exactly the residual risk the form column exists to make visible. Seven mutations, all caught, each by the intended assertion. Three new build inputs declared — the register, the invariant catalogue and the backlog — bringing that list to thirteen. 568 hermetic tests, 173 database tests. |
| 2026-09-03 | Task completion review of `P0-TSK-037`. **One important finding, and it was found by asking what the first real user would do rather than by reading the code.** Every stub was bound to a single HTTP method — most to `GET`, two to `POST` — so an adapter POSTing to create a payment, which is what every payment adapter does, got a **404 from a stub that claimed the provider succeeds**. Proven by probe before it was believed. That is the worst possible shape for the failure: a 404 reads as "the adapter called the wrong path", so the author would debug their own code against a harness that was quietly answering a different question. Fixed by making every mode verb-agnostic — a provider that is unavailable is unavailable for every verb, because the failure belongs to the provider and not to the request method — and locked with a regression test that fails when any single mode is bound back to one verb. **A second finding, and it is the same defect class as the two the implementation already hit**: the ADR check searched the whole of ADR-0008, and "timeout" appears there three times and "unknown state" twice, so both were satisfied by unrelated sentences — deleting the contract-test requirement entirely would have left the check green. Now bounded to that one sentence, and proven by deleting the requirement while leaving "timeout" elsewhere. **Third occurrence in one task of matching a whole document instead of bounding the region**, which is worth naming as a pattern rather than a coincidence. Also closed a silent overflow: WireMock's delay is an `int` and a plain cast turns `Duration.ofDays(30)` into **−1702967296**, so a long delay became a negative one; now `Math.toIntExact`, which is `INV-MON-06`'s reasoning applied outside money. Five consecutive runs green, so the timing assertions are not flaky. 562 hermetic tests, 173 database tests. |
| 2026-09-03 | `P0-TSK-037` complete. `SimulatedProvider`, and the shape of it is the finding: **a provider is unreliable in both directions**, so the harness has two halves and a simulator with only the first cannot reach the modes that cost the most. Outbound is the provider's API - a real HTTP server we call - covering timeout, unavailable, 5xx, delayed, malformed body, garbage, unknown state, the retry sequence, and the request that is **received** before the response is lost. Inbound is the provider calling **us**: a duplicated webhook (`INV-IDEM-04`) and a late settlement (`INV-SET-03`) are the provider acting on its own schedule, and no amount of stubbing its API reproduces them. **The single most useful thing the harness offers is `requestCount`**, because it separates two failures that are identical from the caller's side - a request that never arrived, and one that arrived and was acted on before the answer was lost - which is exactly why `INV-LIFE-03` requires an explicit indeterminate state rather than a guess in either direction. **The acceptance criterion is a checkable claim, so it is checked**: `ProviderFailureCoverageTest` holds three links that must all hold at once - every bullet in `CLAUDE.md` §Failure Engineering is classified as a provider concern or explicitly not one *with the reason and where it is covered*; every provider concern names a harness method that **exists**; and every such method is **actually called** by the suite that proves the harness. The third link is what stops a mode being covered on paper, and it is the one a coverage list normally lacks. Six mutations, all caught, including a bullet added to `CLAUDE.md` - which also proved the new build-input declaration, since `CLAUDE.md` is the last file anyone would think to declare as a Gradle input. **Two defects, both found by the guard's own assertions**: the section regex read straight past `## Failure Engineering` into `## Definition of Done` and returned "auditability" as a failure mode, because `DOTALL` lets `.` match newlines so a single `- .*` swallows the rest of the file - replaced by line-walking with an explicit stop, which **cannot** over-read; and a literal match reported that ADR-0008 had stopped requiring "malformed response" when the ADR simply **wraps mid-phrase**. **WireMock standalone, measured rather than assumed**: it relocates Jetty and Jackson under `wiremock/` - zero classes at `org/eclipse/jetty` - so the harness cannot change which servlet container Spring Boot picks for every `@SpringBootTest` in `app`, and it resolves to exactly **one** lockfile entry. Version 3.13.2, the current stable, noting that Maven Central's `<latest>` *and* `<release>` markers both point at `4.0.0-beta.38`. `unit` tier, decided on the measured 1.3s. **No new ADR** - ADR-0008 already decided the harness exists and lists the modes, so this is its recorded follow-up, and that section now says so. 561 hermetic tests, 173 database tests. |
| 2026-09-03 | Task completion review of `P0-TSK-036`. **No critical findings; two gaps in the guard, both closed, and both found by asking what CI actually executes.** `./gradlew build --dry-run` shows `build` runs `:test` and **never the three hermetic tier tasks** - they are selection conveniences over the same tests, which is fine for coverage and not fine for the tasks themselves: `:sharedkernel:sliceTest` was run and **reports BUILD SUCCESSFUL in one second having selected nothing and written no result file**. A tier that quietly became empty would therefore be discovered by a developer wondering why their command was fast, and by nobody else. `noTierIsEmpty` closes it repository-wide, since per module an empty tier is legitimate. The second: **an unrecognised `@Tag` is ignored rather than rejected**, so `@Tag("databse")` reads as a tier and schedules nothing. Probing found it *was* caught - but by luck: the class was also a `@SpringBootTest`, so detection floored it at SLICE and the misspelling surfaced as "needs SLICE but is in UNIT". A class detection cannot see would have had no floor. The vocabulary is now closed, with an empty non-tier list, on the argument that makes `AuditableAction` closed. Both proven by mutation, bringing the task to **nine of nine**. Also confirmed: `TestTaxonomyTest` really does run inside `./gradlew build` (12 tests in `:app:test`'s results), so the taxonomy is enforced by the job CI runs rather than only by a task it does not. **Process note, third occurrence of the same trap:** `git checkout --` on a path reverted `MetricConventionTest` to HEAD while reverting a mutation, silently destroying this task's own change to it; caught by counting the slice tags afterwards rather than trusting the revert. 542 hermetic tests, 173 database tests. |
| 2026-09-03 | `P0-TSK-036` complete. Four test tiers - unit, architecture, slice, database - defined by **what a test needs in order to run**, and by nothing else. That is the only axis on which membership can be decided mechanically, and it is the one that matters for scheduling: a tier mixing requirements produces a task costing what its heaviest member costs and failing wherever that member's infrastructure is absent. Grouping by intent reads better in a document and cannot be checked. **The default tier selects by EXCLUDING the others' tags** rather than including one of its own, so a test can never belong to no tier at all - the failure an includeTags-only set of tasks creates, and a silent one, since the test compiles, is never selected, reports nothing and is believed to be running. **Two of the five names the task asks for are deliberately not tiers, with the reason recorded rather than dropped**: `contract` is a *kind* whose members have different requirements - `OpenApiContractTest` needs a Spring context, `ColumnClassificationTest` needs a database - and `integration` is replaced by `database`, which says what is integrated with. The tiers **partition** the hermetic suite exactly (418 + 54 + 68 = 540 = `test`), so `build` still runs all three hermetic ones; `unitTest` is ~14s against `build`'s minute. **The split ships with its guard**, because splitting one task into four multiplies the ways to make the `:platform:databaseTest` mistake the `P0-TSK-027` review found: `TestTaxonomyTest` holds the Gradle declaration, `TestTier`, every class's tag, `TESTING.md` and CI to each other. **Seven mutations, all caught - after the first one survived.** Removing a tier tag from a platform test left the guard green, because it reads sibling modules' compiled test classes from disk and nothing had told Gradle that, so it read a **stale class file**; closed with `dependsOn` and a declared input derived from the subprojects. **Two more skips found by its own guards**, both whole classes: `ModuleBoundaryRulesTest` - the oldest rule suite here - because ArchUnit executes `@ArchTest` **fields** and it declares no `@Test` method, and `MoneyTest` because every test method lives in a `@Nested` class. And **one false positive that improved the rule**: `NoDirectBrokerPublicationRulesTest`'s fixture declares `OutboxWriter<java.sql.Connection>` and opens nothing, so detection now keys on **acquisition** rather than mention - a rule that pushes a hermetic suite into the database tier is a rule somebody turns off. Twelve Spring-context tests and nine ArchUnit suites turned out to be sitting in the default tier. The recorded duplication is paid down: thirteen test classes migrated off private connection helpers onto `DatabaseRoles`, and what they had been copying was a connection as the **superuser**. ADR-0028 recorded. 540 hermetic tests, 173 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-035`. **No critical or important findings; four properties measured rather than assumed, and two small corrections.** The tests really do reach a container, not a leftover compose stack - the published JDBC URL is `localhost:<random mapped port>`, printed by probe. The **documented escape hatch works**: with `FINAPP_DB_URL` set, zero containers are created and the suite runs against compose, which is the path for inspecting what a test left behind. **Nothing leaks**: the count of postgres containers is identical before and after a run, and the reaper is what makes that true. And the **cost did not survive measurement** - 14 seconds wall clock for 160 platform tests including container start, the role script and every migration, so the objection that a container per JVM would be slow was wrong. Two corrections: the "system property not set" message still told the reader the Gradle task supplies the URL, when since this task the task supplies the *image* and the harness supplies the URL - which is precisely the failure an IDE run produces, so the message now says so. And **Testcontainers mounts the Docker socket** into its reaper, which is control of the daemon granted to test-time code; not a new capability, since compose already required Docker, but named in ADR-0027 rather than left implicit. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | `P0-TSK-035` complete. Database tests now bring their own database: a `LauncherSessionListener` starts one PostgreSQL container per test JVM, applies the same `00-roles.sql` the compose stack runs and the real migrations through Flyway, and publishes the coordinates as the system properties every test already read - so **no test changed** and **all 173 pass with compose stopped**. That constraint was deliberate: a harness requiring 173 assertions to be edited would have been a change nobody could review. A `LauncherSessionListener` rather than an extension, because it runs before any test class loads and several suites open their connection in `@BeforeAll`. **PostgreSQL only** - the task named Kafka and Redis, there is no client for either, and a container nothing connects to tests nothing. **Three things it ran into.** Putting the harness in `testFixtures` so `app` could reach it exposed it to the ArchUnit sweep and two rules fired - a static container field and a constant named `LOCAL_PASSWORD` - and **both were fixed at source rather than exempted**, since the field did not need to be static and the constant is the published marker rather than a credential. `HealthReadinessDatabaseTest` asserted Flyway was unreachable by loading the class, and **its own comment had predicted the failure** - *"a false positive if Flyway were ever added as a test dependency of this module"* - so it now checks the module's runtime classpath, which is what ADR-0011's claim always meant. And `P0-TSK-039`'s controls exposed a defect in `P0-TSK-039`'s own procedure: locks block metadata generation and metadata blocks lock generation, so **neither order works** and both flags must be passed in one invocation. Testcontainers 2.x also moved `PostgreSQLContainer` and deprecated the old package, which `-Werror` turned into a build failure. ADR-0027 recorded. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-040`. **One important finding, and it is a comment that described behaviour the workflow does not have.** The `schedule:` block claimed the weekly cron was "for the scanner-pin freshness job only - every other job is gated to exclude scheduled runs". Only `pinned-images` carries an `if:`; the other four run too. `DEFINITION_OF_DONE.md` §3 forbids documentation describing behaviour that does not exist, and the correction is the more useful statement anyway: **two of the gates find things that change without the code changing** - `dependency-scan` fails on a CVE published against an artefact nobody touched, and `pinned-images` reports a scanner release - so neither is discoverable from a diff, and a weekly full run is the point rather than an accident. **Two properties verified rather than assumed.** Version ordering: `sort -V` puts `v8.9.0` before `v8.30.1` and `0.74.0` before `0.100.0`, which is the classic trap and the one that would have made the check silently report a release that does not exist, or miss one that does; the four comparisons the script actually performs were exercised directly. And the unreachable-registry path returns **exit 2, not 0** - proven by pointing the resolver at an invalid host - because a check that cannot run must not look like a check that passed. The `sort -V` dependency on GNU coreutils is now noted in the script rather than assumed. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | `P0-TSK-040` complete; **`P0-EPIC-10` and milestone M0.4 close with it**. Three mechanisms, because none reaches all of it: Dependabot for the four SHA-pinned actions and both version catalogues, and a weekly registry check for the two scanner digests it cannot read. **The scanner pins stay where Dependabot cannot see them, deliberately** - moving them into the workflow so a bot could read them would put the same digest in two places and undo the single definition `P0-TSK-031` established, which is what lets a developer and CI run the identical image. **Each pin is now three facts rather than one**: repository, version and digest. The version had been a comment beside the digest, and a comment cannot be checked - which is exactly what made "is this digest still v8.30.1?" unanswerable. With it as data the check distinguishes two findings that deserve different reactions: the tag **moved**, which is the attack digest-pinning defends against and means the pin held and somebody should find out why it had to, and a newer release exists, which is ordinary rot. Both proven against the real registries. Trivy's digest moved out of a workflow `env:` value - a place nothing reads - and both scanners are now invoked through `infra/scripts/`. **A PR-opening bot for the images was rejected in favour of a check that could be proven**: a workflow could open one with the built-in token and would be untestable here, since this repository has no remote and no workflow has ever run; the freshness check is the part that could be demonstrated, and it was, before the decision was written. **Making the dependency scan a script had an immediate consequence: it was run, and it fails** - three HIGH/CRITICAL CVEs in the Tomcat that Spring Boot 4.1.1 brings, fixed in Tomcat 11.0.25. That gate had never been executed by anyone. Recorded as a blocker rather than fixed, since a dependency upgrade belongs to its own change (`EXECUTION_PROTOCOL.md` rule 4). ADR-0026 recorded. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-039`. **No critical findings; one real coverage gap, closed.** The question the review had to answer is whether verification holds for the tasks **CI** runs, since CI has never executed and the metadata was generated against `build databaseTest` only. Both of CI's other Gradle invocations - `:platform:flywayValidate` and `:app:cyclonedxBom` - were run against the generated file and pass, so the SBOM job and the migration job will not fail on a missing checksum. **The gap was `build-logic`**: it is an included build with its own settings, so it never applied the convention plugin that enables locking and a root `--write-locks` does not reach it - verified. Its artefacts were already checksum-verified, because dependency verification is Gradle-wide and reaches included builds (confirmed by finding `gradle-kotlin-dsl-plugins` in the metadata), so only the version record was missing - and it matters as much as any, since a precompiled script plugin runs in every build with full privileges. Locked, generated from within the included build, and proven by mutating a real entry. **A process note worth keeping**: the first attempt at that mutation edited a version string that does not appear in that lockfile, so the `sed` matched nothing and the test "passed" - the third time in two tasks that a mutation was reported as caught when it had never been applied. Checking that the mutation actually landed is now part of applying one. A stray `verification-metadata.dryrun.xml` from an early probe was removed. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | `P0-TSK-039` complete. Every artefact the build resolves is now checksum-verified (456 components) and version-locked (three lockfiles), closing the largest remaining supply-chain hole - a build-time dependency executes with full build privileges, so it can read the source, the environment and any credential the build holds. Both controls proven by mutation: an altered checksum fails naming the artefact and the repository it came from, and a changed locked version fails with *"Did not resolve ... which is part of the dependency lock state"*. **Locking looked redundant and measuring the file proved otherwise.** Verification already refuses any artefact it does not know - confirmed by bumping a pinned version - so the first analysis was that a lockfile would be a second copy of the same fact. Then the generated file was read: on its **first** generation it recorded **69 of 342 modules at more than one version**, `jackson-bom` at five, because the buildscript, plugin, compile and test classpaths legitimately resolve different versions of the same module. Verification therefore cannot tell a deliberate resolution from drift *between versions it already trusts*; a lockfile can, and it earns its place twice over because **thirteen dependencies take their version from the Spring Boot BOM and that version is written down nowhere else** - a BOM bump moves them silently today. **Trust on first use is the limit and it is stated rather than glossed**: the checksums record what this machine downloaded on the day they were written, so they catch a later substitution and not a first download that was already compromised. PGP signatures are the answer and were **measured** rather than argued about - one narrow slice produced 11 signed artefacts and 49 trusted keys - then deferred to Phase 15, since a keyring is a trust decision of its own and every unsigned artefact still needs a checksum. The acceptance criterion's "not regenerate everything and hope" is met by verifying that regeneration **merges**: a narrow run preserved all 456 entries. Gradle never prunes, so removing a dependency leaves its entries trusted - recorded in the procedure. ADR-0025 recorded. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | Task completion review of `P0-TST-009`. **No critical or important findings; the audit's own claims were verified rather than trusted, and two limits recorded.** The seven-row conformance table asserts every multi-instance test gives each instance its own connection - three of those I had checked directly, so the other four were traced: `OutboxCrashRecoveryTest` turned out to pass a connection *source* rather than a connection, and that source calls `DriverManager.getConnection` afresh each time, so eight relays really do contend; `IdempotencyRecordSchemaTest` opens `own` per racer inside the loop. The table holds. The corrected skew test was re-proven after the line-ending normalisation: reverting the V004 fix still makes it fail, which it did not do before this task. **The harness's own limit is now stated rather than implied**: nothing mechanically prevents `SimulatedInstance.serverNow()` being changed to read the JVM clock instead of the database's, and on a machine where the two agree - nearly true here, where the container drifts about half a second - every skew test would keep passing while measuring the wrong thing again. The precondition does not catch that either, and a guard would have to assume a drift that may not exist, so the defence is that the anchor is named in the convention and in the harness rather than enforced. **The duplication the audit exposed is recorded as debt**: thirteen test classes still open connections through their own private helper, which is not a defect - all seven multi-instance tests were confirmed correct - but it means the shared harness is one a future test is as likely to miss as to find. Owned by `P0-TSK-036`. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | `P0-TST-009` complete. **The audit found the criterion's own subject broken.** `clockSkewCannotStealALiveClaim` was written alongside `P0-TSK-016`'s fix precisely to prove that an instance with a fast clock could not steal a live claim - and it built that clock as `Clock.fixed(FIXED.plus(1 hour))` from a hard-coded `2026-09-01T12:00:00Z`. Measured against the running container, whose `SELECT now()` returned `2026-09-03 05:20`, that clock was about **forty hours behind** the server rather than an hour ahead. The test exercised a **slow** instance, and a slow instance never believes anything has expired, so it passed for a reason unrelated to the property it named - proven by reverting the V004 fix so the lease is judged by the client's clock again, which left that test green while two unrelated tests failed. Corrected by anchoring the skew on `SELECT now()` through a new `SimulatedInstance` harness; it now **fails** under the same reintroduction, which is what "a clock-skew failure is detectable" means. A precondition asserts the skew is real and in the dangerous direction, so the fixture cannot silently invert again. **The audit's other result is that everything else conforms, for a reason worth recording**: every multi-instance test already gives each instance its own connection, and a shared clock is *correct* because eligibility, abandonment, leases and retention are all decided by the server's clock - the one place a client clock decided anything was the idempotency lease, which is the defect ADR-0014 exists for and is now server-side. **The declared dependency was a backlog defect**, the second of the class `P0-TSK-004` found: `P0-TSK-035` (Testcontainers) changes where the database comes from, not whether a test can give each instance its own connection, component and clock. `DatabaseRoles` moved to a shared `com.finapp.platform.testing` package - it had been package-private in `audit`, which is why two earlier tasks put schema-wide tests in the audit package to reach it. 528 hermetic tests, 173 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-041`. **No critical findings; one important one and two closed gaps, all found by probing shapes the rules were not designed against.** The important one: **a static final ARRAY was not flagged** - `private static final String[] CACHE = {...}` went straight through, and a `final` reference to an array protects nothing, so it is per-instance shared state exactly as a `HashMap` would be. Closed, with enum `$VALUES` excluded as **synthetic** - which is the only reason arrays can be flagged at all, since every enum the compiler writes has one. Re-proven in both directions, and the whole build still passes with four enums present. **A second gap is recorded rather than closed**: a mutable collection built by a *factory method* and assigned to an interface-typed static field escapes both halves of the rule - the construction is not in `<clinit>` and the field's type is an interface. Widening to "any mutable construction in the class" would flag the common and correct pattern of building a local collection and returning an immutable copy, so it is written down in ADR-0024 and `MODULE_ARCHITECTURE.md` §6 instead. **Three shapes verified to work that were never designed for**: a `@Scheduled` annotation - which is how a Spring developer would actually introduce ambient scheduling, and an annotation is not a field or a call - a `ReentrantLock` used only as a local variable, and a non-final static primitive. All caught, because the condition asks for direct dependencies rather than inspecting fields. Also confirmed the bytecode sweep works through **both** classpath shapes, jar and directory, by planting a block in `platform` and in `app` separately. Two code-quality fixes: a dead `noClasses` import left by the inversion repair, and a `DescribedPredicate` wrapper whose description was never used. 528 hermetic tests, 172 database tests. |
| 2026-09-02 | `P0-TSK-041` complete. Four rules make the mechanically detectable half of ADR-0014 a build failure: `synchronized` (method **and** block), process-local locks, ambient scheduling, and static mutable state. Each means something **only within one process**, so its presence is a claim about coordination that is false the moment a second instance starts - and worse than no lock at all, because the code reads as though the race was handled. The exemption set is `DISTRIBUTED_EXECUTION.md` §3 rather than a list the rule keeps for itself, named individually because a type-wide `ThreadLocal` exemption would admit the third one without anyone deciding; both current entries are **proven load-bearing**, since the same rule with an empty exemption set fires on each. **The block check is not an ArchUnit rule**: ArchUnit models accesses, a block is a `MONITORENTER` instruction with no access flag - verified by probe, where the block method reported `modifiers=[]` - so it reads bytecode with ASM at test scope. **Three defects, every one caught by the task's own tests rather than by review.** `noClasses().should(customCondition)` **inverts events**, so two rules were incapable of failing - the *identical* defect `P0-TST-008` found in `secretsAreWrapped` and wrote up at length, reproduced one task later by the person who wrote it up. `haveModifier(SYNCHRONIZED)` on `classes()` checks the **class's** modifiers and a class cannot be synchronized, so that rule could not fire either. And the bytecode sweep walked only **directories** - a consumed module reaches a dependent as a **jar**, so `platform` and `sharedkernel` were never scanned and the planted block was invisible, while a count-based vacuity guard saw nothing wrong because `app`'s classes are plenty; coverage is now asserted per module from the same classpath helper the ArchUnit guard uses. **The limit is recorded rather than glossed**: the `IdempotentExecutor` defect that motivated ADR-0014 used no lock, no static state and no scheduler - it was a clock comparison - so these rules narrow the ways to be wrong rather than closing them, and the design question stays a review question. ADR-0024 recorded. 528 hermetic tests, 172 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-034`. **No critical findings; one important one, and it is the stale-list defect again.** The five modes the guard treats as insufficient - `disable`, `allow`, `prefer`, `require`, `verify-ca` - happened to be **exactly** the driver's other five, and nothing checked that. A driver upgrade adding a mode would have left it silently unclassified and untested, which is the same failure this repository has met in CI's job list, in an ArchUnit coverage guard and in a privilege check. The set is now derived from the driver's own `SslMode` enum at test time, by reflection because the driver is deliberately runtime-only, with a vacuity assertion so an unresolvable class fails loudly rather than comparing two empty sets. Proven by dropping a mode from the classified set. **Two bypass questions answered by disassembling the driver rather than reasoning**: it reads `sslmode` from **no environment variable** - `PGProperty` consults only the passed `Properties` - so there is no silent override; but a libpq **service file** (`?service=name` with `pg_service.conf`) is a source the application cannot see. That one fails in the safe direction only: a service file setting a weak mode is still refused, and one setting `verify-full` produces a false refusal. Documented rather than closed, because the alternative is trusting a file the application cannot read. Also confirmed `verify-full` is a real driver mode rather than a plausible-looking string, and added the two untested edges - a loopback database stays exempt with a weak mode configured, and both `sslmode` sources are load-bearing. Kafka and Redis remaining unguarded is recorded as debt with the reason that a guard for a connection with no client guards nothing. 515 hermetic tests, 172 database tests. |
| 2026-09-02 | `P0-TSK-034` complete. The acceptance criterion's second clause - *local setup must not normalise insecure defaults into later environments* - turned out to name a real default, and it belongs to the **driver** rather than to this repository. Measured against the local container: `sslmode` unset and `prefer` both **connect unencrypted and report nothing**, while `require` and `verify-full` are refused. The platform sets no `sslmode`, which is correct locally and, in a deployment, a plaintext connection to a remote database carrying every credential, amount and account identifier in the clear - with the pool connected, readiness UP and the logs quiet. `prefer` is the worst available default precisely because it looks like it is trying. `TransportSecurityGuard` now refuses to start when a non-loopback database would be reached without **`verify-full`** - not `require`, which encrypts and verifies nothing and so stops passive eavesdropping but not an active attacker presenting their own certificate. Loopback is exempt deliberately: a connection that does not leave the host would otherwise cost every developer a certificate for a container, and `DOD-BUILD` requires a clean clone to build with no machine-specific setup. **One mutation survived and led somewhere better**: reading `sslmode` only from the Hikari property and ignoring the JDBC URL passed every test, because the two sources were combined in a private method no test reached. That raised the question of which source the driver honours - measured, **the URL wins in both directions** - and rather than encode a driver implementation detail the guard now requires **every configured source to agree**, which cannot be wrong about a precedence that may change. `DatabaseEndpoint` extracted so "is this database on this machine?" has one definition rather than two that drift. Kafka, Redis and inbound HTTP are documented rather than guarded - there is no client for the first two and the application is never the TLS endpoint - and a guard for a connection that does not exist would be guarding nothing. Nothing is encrypted at rest and nothing holds data that needs it; expectations recorded per concern with owning phases. **Process note:** `git checkout --` failed to revert a mutation in a NEW file, because the file was untracked and the command is a no-op there - caught by re-reading the file rather than trusting the command. Copy-based backup is the only reliable revert for untracked work. ADR-0023 recorded. 513 hermetic tests, 172 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-033`. **One critical finding, and the scheme itself is what produced it.** `correlation_id` was classified `INTERNAL` in four tables on the reasoning that it is operational metadata. Applying the ceiling rule to it exposed that it is **caller-supplied**: the permitted charset is `[A-Za-z0-9._:@/+=-]`, a well-formed inbound `X-Correlation-Id` is accepted verbatim, and `jane.doe@example.com`, `acct:GB29NWBK60161331926819`, `customer-1990-05-14` and `+447700900123` were all confirmed accepted by probe. That value is then written to **every log line** as a top-level ECS field, stamped on **every span**, stored in four tables, and echoed in the response header and every problem-detail body - so a caller can place personal or financial data into a telemetry backend with different access control and months of retention, which is exactly what `INV-AUD-02` forbids and what ADR-0017 and ADR-0018 keep SQL text and request-derived tags off spans and metrics to prevent. **The level stays `INTERNAL` and the value must change**: raising the classification would forbid correlation from appearing in logs, which defeats correlation. Recorded as debt for Phase 1 rather than fixed, since it is `P0-TSK-025`'s ingress behaviour (`EXECUTION_PROTOCOL.md` rule 4). §5 was rewritten around the distinction it had missed - free text whose ceiling is a *handling* rule, versus caller-supplied identifiers whose level is a *requirement on the value*. **One important defect in the guard**: the register parser matched `[a-z_]+`, so a column name containing a digit - `address_line_2`, `iso_4217_code` - could not be classified at all; the row would sit unparsed and the failure would read "this column has no entry" while the entry was right there. It fails safe and diagnoses the wrong thing, and Phase 3 would have met it. Found by planting such a column, fixed, and re-proven in both directions. 489 hermetic tests, 172 database tests. |
| 2026-09-02 | `P0-TSK-033` complete. A data classification scheme written in the phase that holds **no customer data, no money and no credentials** - which is the point rather than an irony. A column's classification cannot be added later: by the time it holds data the handling it was given for its whole life is already settled, and may be in a log aggregator, an event stream or a backup that cannot be recalled. Reclassifying is not a schema change, it is an admission the previous handling was wrong. So every column is classified at its **ceiling** rather than its current content - `audit_record.actor_id` is `RESTRICTED-PII` although it contains the literal `system` today, because from Phase 1 it is a person's identity-provider subject and there is no later moment at which changing the answer is safe. Same argument ADR-0010 made for actor attribution. **Per column, not per table**, because a table mixes levels and treating them alike either over-restricts the operational fields - which makes people work around the scheme - or under-protects the free-text one. "Referenced by later data-model tasks" is **enforced rather than hoped**: `ColumnClassificationTest` reconciles the register against the live schema in both directions, so Phase 3 cannot land ledger tables unclassified; proven with a planted `customer_email`, a removed register row and an invalid level. Handling rules are **referenced, never restated** - every one is already enforced by redaction, metric cardinality, span content, configuration or audit immutability rules - because a second copy drifts while looking authoritative. **The weak point is named rather than glossed**: five columns hold whatever a caller writes, and no build rule checks that; the nearest control is the Phase 1 output scrubber already recorded as debt. **One defect, caught by its own vacuity guard**: the register parser used `^` without `Pattern.MULTILINE`, so it anchored to the start of the document and matched nothing - every assertion would have passed over an empty register and reported a scheme classifying nothing. ADR-0022 recorded. 489 hermetic tests, 172 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-032`. **Two important findings, both the same shape: this suite deviated from a pattern its four siblings already had.** First, `SystemActorRulesTest` was written **without a coverage guard** - every other rule suite has `everyModuleWithProductionCodeIsAnalysed`, because a rule that sees nothing passes and reports safety it never checked. That is precisely the deviation `P0-TST-008` identified as the mechanism by which a security rule sits behind a green test protecting nothing. Added, and proven by narrowing the sweep to one module. Second, **`SecurityContext` was missing from `DISTRIBUTED_EXECUTION.md` §3**, whose opening line is "every component with state" - it is the platform's second `ThreadLocal`, and `P0-TSK-041` is scheduled to build an ArchUnit rule whose exemption set is *that register*, so an unlisted one would have arrived as either a build failure or an unjustified exemption. Registered as non-authoritative, with the distinction that matters: it carries the acting party, it is never the source of one, and losing it costs the operation rather than correctness because `require()` refuses. **One accuracy fix**: nothing in production establishes a scope, and both the architecture document and the debt table now say so - it is a seam under `EXECUTION_PROTOCOL.md` rule 3, not an unfinished wiring job, and `DEFINITION_OF_DONE.md` §3 forbids documentation describing behaviour that does not exist. Verified rather than assumed: the field rule was probed against five bypass shapes **individually** - a constant captured in a static initialiser, a static import, a lambda, a chained call and a nested class - because one catch in an aggregate probe can mask four misses; `getField` is a bytecode-level GETSTATIC and caught all five. 489 hermetic tests, 168 database tests. |
| 2026-09-02 | `P0-TSK-032` complete. The first acceptance clause was **already met** by `P0-TSK-022` - `AuditRecord` cannot be built without an actor - so the task was the mechanism and the decision behind it. `Actor` and `ActorType` existed and nothing supplied them, which meant every call site would decide independently what happens when nobody established who was acting. **`SecurityContext.require()` throws rather than defaulting to `Actor.SYSTEM`**, and that is the decision: a default is convenient and *correct today*, and silently wrong the moment Phase 1 lands - an authenticated request whose scope was never established would record the platform as having done what a customer did, with nothing failing, the record complete and plausible, about the wrong party, and permanent under `INV-HIST-03`. Phase 0 claims the system actor **out loud** through `enterSystem()`, greppable on purpose as the list Phase 1 must revisit, and reading `Actor.SYSTEM` anywhere else now fails the build - every audit record needs an actor, so every call site has a parameter to satisfy and the constant is the shortest way to satisfy it (ADR-0019's argument, unchanged). `Actor`/`ActorType` moved to `platform.security`, because audit *records* an actor and does not own the concept; the actor is deliberately **not** merged into `Correlation`, since an identifier naming one execution and one naming a party are different things and merging them would put a customer identifier into every log line and span (`INV-AUD-02`). **The un-testable clause was made falsifiable**: "Phase 1 needs no schema change" is a claim about a phase that does not exist, but the property behind it is not - a record written as every non-`SYSTEM` type, carrying an OIDC `sub`, a directory DN and a service credential, persists today with no DDL, proven to fail by narrowing the live `CHECK` and by widening `Actor`'s bound past the column's. **Two findings, both in the tests.** The pooled-thread leak test proved nothing - `propagate` restores on the way out, so the worker was already clean and the assertion passed whatever `apply(null)` did; confirmed by a surviving mutation, and it now dirties the worker the way a real leak happens, with a precondition. And the new database test **committed audit rows permanently** - they are append-only to the application role by design - so cleanup is the migrator's job, scoped by a probe marker rather than a wholesale DELETE, because the sibling suites share that table. The correlation sink guard fired for the **seventh** time, on `security`, and the answer was recorded as deliberately not a sink. ADR-0021 recorded. 488 hermetic tests, 168 database tests. **Unrelated defect found and recorded rather than fixed** (`EXECUTION_PROTOCOL.md` rule 4): `RequestValidationTest.aMalformedIdentifierIsReplaced` asserts the issued correlation identifier `doesNotContain("bad")`, and a UUIDv7 hex string contains `bad` about 0.7% of the time - one run in 137, observed once here. The intent is right and the method is wrong: it should assert the value is a well-formed generated identifier, not that it avoids three substrings which are also valid hex. It belongs to `P0-TSK-025` and is noted in the backlog. |
| 2026-09-02 | Task completion review of `P0-TSK-031`. **Two important findings, and both were controls that did not do what they claimed.** First: the configuration rule matched only the **first** `key value` pair on a line, so `LOGIN PASSWORD 'x'` was read as the key `LOGIN` and skipped - **the SQL role script, one of the four files the test names, was not being checked at all**, and a real password planted there passed the build cleanly. It had looked covered because the original mutation changed *both* occurrences and tripped the single-sourcing count instead - a different assertion catching it, which is the second time in this task a mutation passed for the wrong reason. **The lesson is that a mutation must be isolated to the assertion under test**; re-run against one occurrence, it slipped straight through. Second: `DatabaseCredentialGuard` read `spring.datasource.url` while `spring.datasource.hikari.jdbc-url` is bound afterwards and **wins** - proven by starting the application against `db.internal` with the generic URL left on loopback and unwrapping the pool to confirm it really had the remote address. A documented Spring property is exactly the "bypassable by a documented path" `DOD-SEC` forbids; the guard now reads what the pool actually connects with, via `Binder` so relaxed spellings cannot dodge it. Three further gaps, all found by probing bypass shapes rather than by reading: **`.sh` was not scanned** although this task itself added `infra/scripts/`, and shell is where `PGPASSWORD=` lives; **`authorization` and `bearer` had been dropped** from the vocabulary as "authentication data" when they are how an API credential is written in configuration; and **`PGPASSWORD` has no word boundary to split on**, closed with `endsWith` rather than `contains` so `passwordless` and `tokenizer` stay clean. The ad-hoc probes are now a **22-row shape table** asserted on every build - `P0-TST-008`'s lesson that a probe living only in a shell does not survive the person who ran it - plus a guard that the extension list in the test and the Gradle input filter cannot drift, which is the fourth occurrence of that defect class here. 470 hermetic tests, 165 database tests. |
| 2026-09-02 | `P0-TSK-031` complete; **`P0-EPIC-10` opened**. Secret management, and the probe changed the shape of the answer. The obvious reading of the acceptance criterion is that clause 2 delivers clause 1 - run a scanner, keep it green, no secret in the repository. **It does not.** Four plausible secrets committed to a throwaway repository and scanned with the pinned image: gitleaks caught a private-key block, a high-entropy token and a real-shaped AWS pair, and **missed `password: hunter2` and `POSTGRES_PASSWORD: correcthorse`** - there is nothing about a memorable password to detect, a memorable password is what a human commits, and those two shapes are exactly the shape this repository's own configuration has. So the scanner is a **net, not the control**. The control is `CommittedConfigurationHoldsNoSecretTest`: default-deny over configuration files it **discovers** rather than lists, so a new `application-prod.yaml` is covered without anyone remembering. It does a second job for free - the marked local default is written in six files across YAML, Kotlin and SQL that cannot share a constant, and rejecting any *other* local default is what actually single-sources it; the comment claiming "three places" was already wrong. **A name is not a control either**: externalised configuration fails silently when nobody sets the variable, so `DatabaseCredentialGuard` refuses to start with the marked default aimed off loopback, fails closed on an unreadable host, and checks every host in a failover list. Three findings. **`secretsAreWrapped` rejected this task's own field** - `APP_PASSWORD_VARIABLE` holds the *name* of a variable, a real false positive - and it was **renamed rather than exempted**, because the obvious exemption would admit `PASSWORD_PROPERTY = "hunter2"` for ever and that rule has no exemption set at all. **The documentation tripped the scan it documents**: the first drafts quoted realistic example keys and the scan caught both files, which is the scanner working - it cannot tell an example from a disclosure - so the examples now describe shapes and the proof generates its dummy value at run time, rather than allowlisting prose about the scanner. And **a startup test that tested nothing**: `SpringApplicationBuilder.properties(...)` populates Boot's *default* source, which ranks below `application.yaml`, so both cases silently kept the committed URL - the positive control had been green while asserting nothing about its own argument. Clause 3 is demonstrated against a throwaway clone and never against this repository, since a dummy secret committed here would make the scan red for ever. ADR-0020 recorded. 446 hermetic tests, 165 database tests. |
| 2026-09-02 | Task completion review of `P0-TST-008`. The first question was whether the inversion defect it found was **systemic**, since other rules protect `INV-MON-01` and `INV-EVT-01`. It is not: all four sibling suites use the positive `classes().that()...should()` form and prove their teeth with `rule.check(violating)` - the **rule**, not the condition. Which makes the finding sharper rather than softer: the correct pattern already existed, this suite deviated from it, and the deviation is exactly what hid a security control that could not fail. The teeth tests now use the sibling idiom - `assertThatThrownBy(() -> rule.check(...))` and `assertThatCode(...).doesNotThrowAnyException()` - so the suite reads like its four neighbours and cannot drift back. Two further fixes. The **MDC rule was proven only by a manual probe**, which is not a method that survives the person who used it; it now has a fixture and is rejected on every build alongside the others. And the appender test wrote its log to the **system temp directory**, where Logback appends for ever - a test whose negative control deliberately writes an unredacted sentinel was leaving that plaintext in a directory nothing cleans, which is the wrong shape for a test about not writing secrets to files. Moved inside `build/`, and the stray removed. 419 hermetic tests, 165 database tests. |
| 2026-09-02 | `P0-TST-008` complete; **`P0-EPIC-09` closed**. The task's own note said to check whether its criterion was already met rather than assume, and it was not - the reason being the most serious defect this phase has produced. **`secretsAreWrapped` was structurally incapable of failing.** `noClasses().should(condition)` *inverts* the condition's events, reporting as violations the things it marks satisfied; the condition only ever emitted `violated(...)`, so the inversion left it nothing to report and a production record holding a plaintext `String password` passed cleanly. The rule's own fixture test had "proved" it worked by invoking the **condition** directly, bypassing the inversion and testing something the build never runs. A security control that cannot fail, with a green test beside it, is worse than absent: it is believed. Now `classes().should(not ...)`, with the fixture evaluating the rule, and both production shapes proven to fail. **A second path was found by probing**: the MDC takes a `String`, so the wrapper cannot protect it, and the ECS encoder lifts every entry to a top-level field - `MDC.put("apiToken", token)` publishes it verbatim. Closed by `onlyCorrelationContextWritesTheMdc`, proven by planting a production MDC write, with the exemption shown to be real rather than vacuous. Redaction is now asserted across console **and** file appenders, each with a precondition and a negative control, because value-level and encoder-level redaction look identical to a one-appender test. **Process finding:** the first probe reported "already met" because `:app:test` was UP-TO-DATE and the assertion read stale XML - a mutation probe that does not force a re-run can report whatever the last run said. 418 hermetic tests, 165 database tests. |
| 2026-09-02 | `P0-TSK-030` complete - the only Phase 0 task the backlog marks **High risk**. `INV-AUD-02` is the one invariant that specifies its own enforcement, *default-deny redaction*, and the decision that follows is that **default-deny is a property of the rule, not of the wrapper**: a wrapper people must remember is opt-in with extra steps. `secretsAreWrapped` fails the build on any field or no-argument accessor whose name says it holds a secret unless it is `Sensitive<?>`. The accident being closed is Java's own - a record generates a `toString()` printing every component, so `log.info("authenticating {}", credentials)` prints the password with no getter, no concatenation, and nothing a reviewer stops at. **Accessors as well as fields**, because a serialiser reads accessors and a private `pw` behind a `getPassword()` escaped the first version of the rule entirely. The vocabulary is narrow on purpose and `key` is not in it: an idempotency key is not a secret, and a rule with false positives is a rule somebody turns off. `equals` is identity-based, because value equality would let the wrapper answer whether a guess is right. **Review found the invariant half-covered**: it names logs, event payloads *and API responses*, and only logs were tested. Jackson turned out to decline revealing a `Sensitive` by **accident** - no properties, so `{}` - the same shape as the `"correlationId":{}` defect already hit here, and one that ends silently when somebody adds a getter; masking is now stated by a serialiser in `app` and asserted against a type that *has* an accessor. ECS JSON everywhere including locally, since an encoder nobody runs locally is an encoder whose defects nobody sees. Verified on a running instance: JSON on real stdout, a client-supplied correlation id as a queryable field, and the database password absent even from a logged authentication failure. ADR-0019 recorded. 415 hermetic tests, 165 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-029`. One important finding, and it is the gap the task's own worst defect should have suggested: **nothing checked that the dashboard's queries name series the application actually publishes**. A dashboard querying a series that does not exist does not fail - it renders "No data" on every panel and looks exactly like a quiet system, which during an incident is the worst way to be wrong. That is not hypothetical: `baseUnit("events")` had already made the published name diverge from the queried one, and it took looking at a browser to notice. `DashboardQueriesResolveTest` now resolves every `expr` against a live registry, and the dashboard is a declared build input - the sixth such line. **Writing it found two more things, both by failing.** `hikaricp_*` exists only once the pool has actually initialised: with the datasource at a closed port there is no pool and only the generic `jdbc_connections_*`, so the guard has to run against a real database - which means it checks what the dashboard will really face. And `http_server_requests_*` is registered when the first request is served rather than at startup, so the test serves one first; asserting before that would have reported a dashboard error that does not exist. The PromQL parser errs deliberately towards treating an unknown token AS a series, because a false failure is visible and fixable while the other direction silently stops checking whichever query it misparsed. Three mutations, all caught: a dashboard querying a missing series, a renamed metric with the dashboard untouched, and a drifted Grafana pin. 407 hermetic tests, 165 database tests. |
| 2026-09-02 | `P0-TSK-029` complete. Prometheus metrics, a naming convention enforced against the **live registry** rather than a written list, and a Grafana dashboard **verified rendering live data in a browser** - `finapp_outbox_pending` reading 1 against exactly one unpublished row. A metric name is a contract that outlives the code: every alert rule and runbook written against it lives outside this repository, so it is enforced by the build before there are twenty-four modules to reconcile. **No tag value may come from a request**, which is a security rule as much as an operational one - an identifier in a tag multiplies one series into thousands and puts it in a system with months of retention (`INV-AUD-02`) - and **correlation is deliberately kept off metrics**, the one concern where it must be kept out. **Outbox depth and age are gauges over the database, not counters from the relay**: a relay-side counter reports nothing when the relay is down, which is exactly the incident worth seeing. An unreadable backlog reports NaN rather than zero, because a zero silences the alert that should fire. **Two defects no test caught**, both found by scraping a running instance: the gauges were structurally always NaN, because the cache compared `nanoTime()` against a `Long.MIN_VALUE` sentinel and the subtraction overflows - two tests were green over it, one asserting NaN when the database is *absent*, which an always-NaN gauge satisfies perfectly; and `baseUnit("events")` renamed the published series to one no dashboard queried, hidden by a substring assertion. **Two architecture rules fired and both improved the design**: `INV-MON-01` caught floating point twice - `OutboxBacklog` was fixed by casting to `bigint`, while `OutboxMetrics` took the first two entries in an exemption set empty since `P0-TSK-008`, because Micrometer's `Gauge` is a `ToDoubleFunction` and a row count cannot reach a monetary path; and the ambient-time rule rejected `nanoTime()`, so the cache uses the injected `Clock`. ADR-0018 recorded. 407 hermetic tests, 163 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-028`. One important finding, and it is a property that was **working by coincidence and asserted nowhere**: a log line emitted inside a request carries `traceId`, `spanId` and `correlationId` together - which is the join that makes any of this usable, and it holds only because two independent mechanisms happen to agree, Boot's log correlation and `CorrelationContext`. Disable either and every log line quietly stops being joinable, with nothing failing. Found by probing the logging context from inside a request rather than from the test thread, where it is empty and says nothing. Now asserted. Three further findings, all mine. **A fabricated exception**: the database span recorded connection failures as `span.error(new IllegalStateException(type))`, attaching a stack trace pointing at the recording line rather than at anything that failed - worse than no stack trace, because it looks like one; replaced with an `error.type` tag. **A bean lookup on every connection acquisition**, in front of every database connection the platform will ever make; memoised. And **the hermetic "every span carries correlation" assertion ran over a set of one**, because liveness produces a single span - a claim that cannot fail for the right reason; it now uses readiness, which reaches for a connection and produces two. Also added the branch nobody had covered: a span started outside any flow must carry **no** correlation, since inventing one would fill a dashboard with identifiers matching nothing in any table. Verified rather than assumed: the recorded spans really do include a real HTTP SERVER span, so the HTTP leg is genuine and not the database span in disguise. 400 hermetic tests, 159 database tests. |
| 2026-09-02 | `P0-TSK-028` complete; `P0-EPIC-09` opened. **The acceptance criterion could not be met as written and was corrected rather than approximated** - it named HTTP, DB, outbox and consumer, and two of those have no subject: there is no broker adapter and no consumer wiring, so no request can reach either. Same correction `P0-TSK-014` needed, and the legs transfer to the Phase 3 broker adapter. What was delivered: **correlation on every span**, stamped once by a span processor rather than by each component - because "every span" is not a property discipline delivers, and forgetting is silent: the span is recorded, the trace looks complete, and it cannot be found. **A trace id is explicitly not a substitute for a correlation id**: it is subject to sampling, so a sampled-out flow would be unfindable from the only value a customer holds, and it is absent from every table. HTTP and database proven in one connected trace against a live PostgreSQL, with the database span a **child** of the request - a flat list sharing a trace id cannot answer what a connection was acquired for. **No JDBC tracing library and no statement text**: SQL on a span would carry amounts and account identifiers into a backend with different retention and access control (`INV-AUD-02`), arriving silently the first time somebody writes a query. Three defects found by running it, each producing **no traces and no error**: Boot 4 gates the OTel SDK behind `management.opentelemetry.enabled`, off by default; the auto-configuration module is `@ConditionalOnClass` on the bridge so both are required; and a duplicate `management:` key in YAML stopped every context. Plus one of mine - a javadoc claiming the tracer was resolved lazily while the code resolved it eagerly in a bean post-processor. **The correlation sink guard fired for the sixth time**, on the sink it was built for, closing `P0-TSK-014`'s last clause four tasks and one milestone after it was recorded. ADR-0017 recorded. 398 hermetic tests, 159 database tests. |
| 2026-09-02 | Task completion review of `P0-DOC-003`. No critical findings; three gaps in the guard, all closed, and the pattern is that a document guard is only as good as the set of claims it thought to check. **The document named error codes and nothing verified they exist**: `ErrorCodeRegistryTest` reconciles the CATALOGUE with the taxonomy, but these were mentions in prose, so renaming a code would have left the conventions telling a client to handle something that can never arrive - the same defect as documenting one that was never added, from the other direction. **The charset check ran one way only**: it caught a character dropped from the document but not the pattern being widened without the document following, which is the direction that rots quietly; now derived from `CorrelationId` by probing every printable character rather than restated. **And the deprecation windows were a second unguarded copy of ADR-0015** - the exact duplication this class refuses to allow for error codes, written by me two sections later; now compared numerically and wording-independently. Nine of nine mutations caught across the two rounds, in both directions. Two claims verified rather than assumed: every response really does carry `X-Correlation-Id`, actuator responses included, which is what the document promises; and the document is genuinely a declared build input - a green run, an edit to the document alone, and the task re-ran and failed. 394 hermetic tests, 156 database tests. |
| 2026-09-02 | `P0-DOC-003` complete; **`P0-EPIC-08` closed**. The API conventions document - versioning, errors, correlation, request limits, idempotency, pagination, deprecation - and the interesting part was not writing it. Two of the six conventions the backlog asks for describe behaviour that **does not exist**: there is no money-moving endpoint and no collection endpoint, while `DOD-DOC` forbids aspirational statements presented as current fact. Omitting them would have defeated the task's own reason for existing, since a convention decided after five modules have each invented their own is not a convention. Resolved by making the distinction **structural**: every section is labelled `Implemented`, naming the class and test that prove it, or `Decided, not yet implemented`, naming the owning task - and **an unlabelled section fails the build**, so the distinction survives the next person in a hurry. The acceptance criterion is enforced rather than asserted: `ApiConventionsAreAccurateTest` pins the prefix, the handler package, the correlation header with its charset and 128-character bound, the size limit and its property, the problem-detail members and the media type - and fails if the error-code catalogue is ever pasted in, because `ERROR_CONTRACT.md` owns it and a second unguarded copy would drift while looking authoritative. **Pagination decided on a correctness argument**: offset re-reads a moving set, so a row inserted between pages is silently skipped or repeated - on a transaction history that is a payment missing from a statement with nothing reporting an error; that offset is also O(offset) on a ledger is the lesser objection. Six of six mutations caught in both directions. Also corrected a stale record found while checking: the `P0-TSK-014` DoD row still said no HTTP surface existed, which `P0-TSK-025` had built the day before. 391 hermetic tests, 156 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-027`. Two important findings, both about checks rather than code. **`:app:databaseTest` would never have run in CI**: the workflow step named `:platform:databaseTest` explicitly - a list of one that went stale the moment a second module gained database tests, which is what this task did. The positive control for readiness, the half of the acceptance criterion that says it reports UP when the database is actually there, would have run on one machine and nowhere else. Now `./gradlew databaseTest` unqualified, so a third module is covered without anyone remembering. And **the leak test was a deny-list**: turning details on and reading the body it would really publish showed the disk-space indicator reporting an absolute filesystem path - a username and the host's directory layout - which no list of forbidden substrings had anticipated, while three of its seven entries never fired at all. Replaced with an exact match, which immediately found something else the deny-list had never questioned: the aggregate publishes its group names even with details off. Same argument as `ProblemDetailBody` - specify what is published, because anything else publishes itself. Two minor fixes: the allow-list test was **partly vacuous**, since three of its twelve endpoints return 404 for reasons other than the allow-list - probing with exposure widened to `*` showed nine genuinely gated, and `heapdump` reachable behind one further property, returning 55 MB of process memory; the twelve are now split so no assertion overstates its protection. The migration check also asserted against the test classpath while describing the runtime one, and now checks the running context directly as well. Verified rather than assumed: **readiness recovers on its own** - 200, then 503 with the container stopped, then 200 within one poll of it returning, no restart. Two deferrals recorded as debt: the endpoints are unauthenticated until `P0-EPIC-10`, and connection-pool sizing across N instances is arithmetic owed before Phase 3. 384 hermetic tests, 156 database tests. |
| 2026-09-02 | `P0-TSK-027` complete. Liveness, readiness and build info - and the decision that matters is which questions they answer. **Liveness depends on nothing external**: a liveness probe consulting PostgreSQL restarts every instance at once during a thirty-second failover, leaving the fleet reconnecting in a herd to a database already in trouble, with the diagnostic state destroyed - a degradation turned into an outage by the check meant to prevent one. **Readiness includes PostgreSQL and excludes Kafka and Redis**, because the outbox holds events durably and a broker outage delays publication rather than invalidating the instance (`INV-EVT-02`). The trap closed here is Spring's own default: the readiness group is `readinessState` alone, so adding the actuator and a `DataSource` gives a readiness endpoint that returns **UP while PostgreSQL is unreachable** - correct-looking and worthless. The application now has a `DataSource` at all because readiness must be answered **through the pool the application uses**; one that opens its own connection reports healthy while the pool is exhausted, which is exactly when traffic must be diverted. `spring-boot-starter-jdbc`, never `-data-jpa`: unresolved question 12 stays open. **The application starts when its database is down**, deliberately - one that refuses to boot leaves an orchestrator with a crash-loop instead of an instance able to say what is broken. Security: allow-list exposure, no detail, no components, twelve other actuator endpoints asserted 404, and no URL, host, driver or exception in any health body. One defect found by running it, the third of its kind this session: **`properties { time = null }` compiles and does nothing** - Boot 4 excludes via an `excludes` set and otherwise falls back to the build instant, which would have defeated reproducible archives; found by reading the generated file, not the build script. ADR-0015's claim that operational endpoints escape `/v1` is now verified rather than asserted. Five of five mutations caught, and `./gradlew build` re-run with PostgreSQL stopped to prove the hermetic guarantee survived a new `DataSource`. ADR-0016 recorded. 383 hermetic tests, 156 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-026`. One important finding, and it is the defect class this project keeps meeting: **a fix that compiled, read correctly, and did nothing**. Declaring the contract baseline as a Gradle input made the test's own bootstrap message unreachable - a missing baseline aborts the task before any test runs, reporting an internal property name instead of "a first document has been generated, here it is". The first repair, `optional(true)`, was wrong for a reason worth remembering: it permits a null *value*, and the value is present - it is the file behind it that is missing. `inputs.files` rather than `inputs.file` holds both properties, and both were then re-proven: deleting the baseline reaches the test's message, editing it still re-runs the task and fails. Three further findings. **A `synchronized` that guarded nothing**: an instance method locking a different instance per test method, protecting a static memo whose own comment argued the caching was unnecessary - shared mutable state removed rather than fixed. **Two literals for one component name**, so renaming the problem-detail schema would leave every response pointing at nothing; single-sourced, and a `$ref` resolution test added because a document that does not resolve still parses, still diffs, and still looks complete. **Nothing asserted that the published contract contains no test fixture** - several suites register probe controllers by `@Import`, and their separation from this one is a property of Spring's context cache key rather than something anyone declared; a probe baked into a baseline would look exactly as authoritative. Both new guards proven by mutation. Also tightened the handler predicate to `com.finapp.`, since `HandlerTypePredicate` matches by `startsWith` and would have claimed a sibling namespace - confirmed by disassembling Spring rather than by assuming. 375 hermetic tests, 152 database tests. |
| 2026-09-02 | `P0-TSK-026` complete. The API is versioned in the path - `/v1`, applied **once** in the composition root rather than written on each controller, because a prefix repeated in every mapping is a prefix somebody eventually omits, and an unversioned route can never be changed: there is no second version to move its clients to. The version lives in the path because that is the only place it survives an access log, an audit record, a proxy cache key and a `curl` pasted into a ticket. The OpenAPI document is **generated from the running application** on every build and compared byte for byte against the committed copy: any difference fails the build, and each is labelled BREAKING or COMPATIBLE. **The gate and the classifier are deliberately separate** - a classifier clever enough to gate would have to be right about every possible edit, and its one dangerous mistake fails at the customer's end. springdoc is test-scope, so the deployed application serves no `/v3/api-docs` and ships no documentation library. Two defects found by mutation, both in what was published: **the status existed only inside an English description**, so changing `api.Conflict` from 409 to 422 read as a harmless rewording - status, code and type are now pinned as data, which was a real gap in the contract and not only in the classifier; and **springdoc synthesises a `servers` entry from the request**, which in a test is the random port and on a deployment is an internal address published to every client. A third in the classifier: exempting a removed `description` as prose made deleting a component look like a rewording. Six of six mutations now behave correctly. Also fixed a literal NUL byte committed in `RequestValidationTest`, which made git treat the file as binary and its diffs unreviewable. ADR-0015 recorded. 373 hermetic tests, 152 database tests. |
| 2026-09-02 | Task completion review of `P0-TSK-025`. One important finding, the same defect class this epic's previous task closed one layer in: **a constraint declared on a method parameter was not a 422**. Probing found one class of failure reported three different ways depending only on where the constraint sat - a request body gave 422, a method parameter gave 400, and a method parameter under `@Validated` gave **500**. A client cannot write error handling against that, and the 500 is the worst: it says our side failed for something only the caller can fix. Spring validates these on three separate mechanisms and only the first was mapped. **The first fix was dead code**: overriding `handleHandlerMethodValidationException` compiled, read correctly, and never ran - that exception extends `ResponseStatusException`, so the base class dispatches it through a more general branch and it reaches `handleExceptionInternal` with 400 already chosen. The mapping now sits in that funnel, which every framework error provably passes through, plus a handler for the proxy path Spring does not cover at all; removing either fails the test. It was caught only because **the isolated run and the full suite disagreed** - which mechanism Spring picks depends on whether any bean in the context is `@Validated`, so a single-class run and a whole-suite run genuinely exercise different code. Correlation assertion also strengthened: 5 of 5 mutations caught after \"does not contain the original\" was found to pass for a *sanitised* value. **Process failure**: a fix was reported complete while not present in the commit. Verified now by grepping `git show HEAD:` rather than the working tree, and `git checkout` on a path is no longer used to revert a probe - a scratch copy is. 352 hermetic tests, 152 database tests. |
| 2026-09-01 | `P0-TSK-025` complete. Declarative validation rejecting before any domain invocation - asserted by **counting handler entries**, because a 422 returned after the handler ran and did half the work looks identical from outside. Rendered 422 rather than the 400 Spring defaults to, keeping the distinction the error contract makes between a wrong serialiser and wrong data. **`api.PayloadTooLarge` made real**: a JSON body is streamed with no default bound, so an unbounded request body was a denial-of-service vector costing an attacker one connection - and a limit that only reads `Content-Length` is one a caller opts out of by sending chunked, so the body is bounded by a counting stream as well. Two findings while building it: **a filter cannot throw its way to the error contract**, since `@ExceptionHandler` is a dispatcher mechanism and a filter runs outside it, so the filters render the contract themselves; and the test context declared its own `@SpringBootApplication`, which scanned only `com.finapp.app.api` and missed the composition root - it now uses the real application. Also closes the ingress correlation filter recorded as debt: every response carries an identifier in the body and in `X-Correlation-Id`, the scope wraps error handling, and an untrusted inbound header is **replaced rather than sanitised** - a silently rewritten identifier breaks the client's own correlation without telling anyone. Five of five mutations caught after one round exposed a weak assertion: \"does not contain the original\" is satisfied by a sanitised value. 350 hermetic tests, 152 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-024`. One important finding, from probing error paths the tests had not: **a missing query parameter and a wrong-typed path variable both returned `500 api.InternalError`**. Unambiguous client mistakes reported as platform failures - a client may retry a 500 forever on a request that can never succeed, and a spike of malformed requests is indistinguishable from an outage on every error-rate dashboard. The catch-all was swallowing a whole family of Spring's web exceptions. Two fixes failed before the third worked, and the sequence is the lesson: enumerating exception types fixed the ones I had thought of; testing for Spring's `ErrorResponse` interface fixed the missing parameter and still missed the type mismatch, which does not implement it. **The set of framework exceptions is Spring's to define, so the mapping from exception to status has to be Spring's too** - extending `ResponseEntityExceptionHandler` routes every one through a single override with the status already decided, and a future Spring version's new exception routes there as well. Removing that base class now fails five tests. Added `api.NotAcceptable` (406) to complete the mapping. **Process failure, fifth occurrence**: `git checkout --` destroyed the uncommitted rewrite while reverting a probe. Rewritten and committed before probing again. 339 hermetic tests, 152 database tests. |
| 2026-09-01 | `P0-TSK-024` complete; **`P0-EPIC-08` and milestone M0.4 opened**, and the platform has its first outward-facing surface. RFC 9457 problem details on every error path - and the paths worth the work are the four the framework raises **before our code runs**: an unknown route, an unsupported method, an unparseable body, an unread media type. Left alone, each answers in Spring's own shape, so a client sees two error formats depending on how far into the request it got, and nothing notices because each looks reasonable alone. Tested over **real HTTP** rather than MockMvc, because MockMvc does not run the container's error dispatch and would have reported a clean contract for paths that return the framework's `/error` body in production. Two defects found by running it: **the wire format was an accident of the serialiser** - the platform record serialised directly produced `\"correlationId\":{}`, the identifier a client is meant to quote silently absent while its member was present, and `\"detail\":null` for absent members; fixed with an explicit wire record so a field added to the contract can no longer publish itself to every client. And **a correlation scope entered in a controller closes before the error handler runs** - the same shape as the relay defect, and now a recorded requirement on the ingress filter. `ApiException` keeps the log message and the client detail in separate fields so the unsafe default is unreachable rather than discouraged. 339 hermetic tests, 152 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-006`. No critical or important findings. The ordering guard was probed and is load-bearing - removing the `applied_sequence < EXCLUDED.applied_sequence` clause fails the two tests that depend on it, so neither is vacuous. One code-quality fix: the probe handlers read ambient state - a `ThreadLocal` sequence and a static mutable transfer id - which is fragile and, more to the point, models something no consumer does. `InboxConsumer.Handler` receives only the unit of work precisely because the caller has already deserialised the message, so the handlers now close over their message as a real consumer's would, and the test reads as the usage pattern it is meant to document. **Process note**: `git checkout --` destroyed the uncommitted refactor while reverting a probe, for the fourth time in this project. The remedy that works is the one already known - commit before probing - and it is recorded here rather than resolved to be remembered. 316 hermetic tests, 152 database tests. |
| 2026-09-01 | `P0-TST-006` complete; **`P0-EPIC-06` and milestone M0.3 closed**. Duplicate delivery was already covered; **ordering was covered nowhere**, and `EVENT_ARCHITECTURE.md` made three claims about it that existed only as prose. The sharpest is now executable: an order-dependent handler is **still wrong under the inbox**. The handler everybody writes first receives `TransferCompleted` then `TransferInitiated` and ends up believing a finished transfer is still in flight - nothing failed, nothing retried, no duplicate occurred, the inbox did its job perfectly, and the projection is wrong anyway. An ordering key fixes it on the identical deliveries, with a positive control because a handler that ignored every second message would otherwise pass. Retention was made executable too: deleting a dedupe record - what a sweep running earlier than the producer's redelivery window does - makes the same message run twice with nothing reporting it, which is what `DATA_MIGRATIONS.md` §9 means by a correctness bound. The acceptance criterion was demonstrated against the live database: dropping the inbox primary key fails **nine tests across three classes**, then restored. Run through the application role, so the inbox's narrow grant is proven sufficient for real consumer use. 316 hermetic tests, 152 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-005`. One important finding: **the killed-instance test did not prove what it claimed**. Its comment said terminating a backend showed the relay's advisory lock had to be transaction-scoped - but killing a backend releases session-scoped locks just as thoroughly, and switching the relay to `pg_try_advisory_lock` passed all 145 database tests. The claim in the relay's own javadoc was therefore unverified. Closed by a test that models a **connection pool** rather than a crash: a source handing out one physical connection whose `close()` does nothing, which is what a pool does and the only case where a session actually survives the cycle. A session-scoped lock now fails exactly that test, and the comment on the killed-instance test says what it does prove. Two minor fixes: `pg_terminate_backend` returns whether it worked, and counting rows rather than successes would have asserted recovery from a crash that never happened; and a second event for one aggregate collided with the probe table's primary key. **A flake class removed**: three tests looped a fixed number of relay cycles assuming each would land an attempt, which holds only while the row is due when the poll runs - and the local clock steps backwards. They failed about one run in twenty, never the same test twice. All now loop on the state they are waiting for. 30 consecutive green runs. 316 hermetic tests, 146 database tests. |
| 2026-09-01 | `P0-TST-005` complete. `OutboxCrashRecoveryTest` joins the whole chain - business fact, outbox row, relay, publisher - which no existing test did: the writer's tests end at the row and the relay's crash test covers dying *after* publishing. The scenario in between is the one the outbox exists for, and the one where a mistake is invisible. Two properties are new. **A killed instance does not strand its aggregate**: the backend is terminated with `pg_terminate_backend` while it holds the advisory lock and an open transaction, and a surviving instance publishes the event - which is what makes the transaction-scoped lock load-bearing rather than stylistic, since a session-scoped lock on a pooled connection would outlive the code meant to release it and stop that aggregate forever. And **the criterion is asserted at the relay**, not the writer: a missing row is a fact about storage, an announcement nobody can retract is the consequence. Run through the application role, so `V008`'s outbox grants are exercised rather than assumed. The criterion was demonstrated by moving the write onto its own connection - three tests fail. One test initially caught that mutation only sometimes, because it asserted what the publisher saw and so depended on the surviving row being *due*, which the local clock's backwards steps decide; restated as \"the row must not exist\", it catches it every run. 316 hermetic tests, 145 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-007`. One important finding, and it is the same shape as the defect the task itself closed: **the new column-privilege check was vacuous when its query saw nothing**. `isSubsetOf` over an empty set is trivially true, so a renamed table, a typo or a changed catalogue view would have left it green while checking nothing - proven by making the query return empty and watching it pass. A guard written to catch a blind spot had one of its own. Closed by asserting the query sees something, which is reliable because PostgreSQL expands every table-level grant into `column_privileges`, so non-empty is the normal state. Also verified rather than asserted: **the self-maintaining claim**. A column added as a future migration would add it, with `UPDATE` granted on it, fails both tests with no edit to any test - which is what \"covered without anyone remembering\" has to mean to be worth writing down. The `hasSizeGreaterThan(5)` vacuity guard was replaced with named columns, since a count is satisfied by a query returning the wrong table. Recorded a limit rather than a gap: immutability is enforced against the **application** role; the migrator owns the table and can alter it, which is a privileged-access concern for Phase 15 and is what ADR-0010 claims - that the application cannot alter its own audit trail. 316 hermetic tests, 141 database tests. |
| 2026-09-01 | `P0-TST-007` complete; `P0-EPIC-07` closed. The task looked already satisfied - `P0-TSK-022` had shipped an immutability test covering `UPDATE`, `DELETE`, `TRUNCATE`, `DROP`, `ALTER` and self-granting - and checking rather than assuming found its acceptance criterion **false for one kind of widening**. PostgreSQL can grant a privilege on a *column*, and a column grant does not appear in `information_schema.table_privileges` at all: `GRANT UPDATE (reason)` let the application role rewrite a committed audit record's justification - `'original reason'` became `'rewritten after the fact'` - **while the entire audit suite passed green**. `INV-HIST-03` violated, undetected, because the existing update test happened to set `outcome` and the grants test read a view column grants do not reach. `reason` is the worst column to lose: it is the justification for a privileged action. Closed in two places - `UPDATE` attempted on every column with the list read from the catalogue so a future column is covered automatically, and column-versus-table privilege comparison for **every** platform table, since the inbox's deliberate lack of `UPDATE` had the identical hole and a blind spot found in one place is a blind spot everywhere. Both widenings now fail the suite; before this task the column-level one failed nothing. 316 hermetic tests, 141 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-023`. One important finding, and it is the same defect this project already fixed once: **the catalogue was not a declared Gradle input**, so editing it left `:app:test` `UP-TO-DATE` and the build went green over a document the guard never opened. Proven by breaking the catalogue and watching the build pass. `P0-DOC-002`'s review found exactly this for `MODULE_ARCHITECTURE.md` and its build-file comment even names the failure - *\"a check that reports success for work it did not do\"* - which did not generalise on its own to a second document-backed guard. Declared, and the comment now says a third guard needs a third line. A minor finding alongside it: the catalogue was located by a path relative to an assumed working directory, which works under Gradle and breaks in an IDE with a failure reading as a missing document rather than a misconfigured test; it now walks upward like its sibling. Also confirmed a missing catalogue fails loudly rather than passing vacuously. 316 hermetic tests, 138 database tests. |
| 2026-09-01 | `P0-TSK-023` complete. The auditable-action registry, which `INV-AUD-01` names as half of its enforcement and `V009` already assumed existed. The shape that cannot be built is the obvious one - a single enum in the platform listing every action - because actions belong to the modules that perform them and the platform sits below every business module; an enum here naming KYC's actions would invert the dependency. So `AuditableAction` is an interface, each module declares an enum, and only `app` sees the whole set. **The registry is enforced by the type system, not by review**: `AuditRecord.operation` is an `AuditableAction`, so an action outside the registry cannot be recorded at all. The catalogue and the code are reconciled in three directions and each was proven by planting the fault - an action declared but not catalogued, one catalogued but not declared, and a `requiresReason` flag that disagrees. That flag closes the question `V009` deferred: it made `reason` nullable saying \"the domain decides which those are\", and the registry is where the domain decides. The registry's **limit** is documented rather than glossed - it cannot detect a privileged action that writes no record at all, and a registry that looked complete while the calls were missing would be worse than none because it would be believed. Recorded as debt: the three declared platform actions are not yet emitted. 316 hermetic tests, 138 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-022`. One important finding, in the file that is hardest to test because it runs once: **the role-provisioning script hardcoded the database name** while `compose.yaml` parameterises it as `${FINAPP_DB_NAME:-finapp}` and the README documents it as overridable. The failure is not graceful - a `GRANT` naming a database that was never created is an error, `ON_ERROR_STOP` aborts initialisation, and the container exits 3 complaining about a name nobody typed. Reproduced by starting PostgreSQL with `POSTGRES_DB=altdb`, fixed with `current_database()` and `format()`, and verified against both the default and an overridden name. Also added: an escalation test (the application role cannot `SET ROLE` to the migrator, create roles or databases, `COPY TO PROGRAM`, or read `pg_authid` - if it could assume the owning role every grant below it would be decorative), and the concurrency test `DOD-KERNEL` requires, whose subject is an **absence**: audit writes must not serialise, because an audit write is on the critical path of every privileged action and anything making two contend would put a lock in front of the whole platform and present as latency rather than failure. Two deferrals recorded as debt - audit retention/archival and the four-eyes approver column. 307 hermetic tests, 138 database tests. |
| 2026-09-01 | `P0-TSK-022` complete. The audit trail, and with it **the database role split that had been documented and deferred since `P0-TSK-005`**. Roles are cluster objects, so they are provisioned by infrastructure and only their grants live in migrations - a migration creating a role would claim an object outside its schema, break against the scratch databases CI creates, and need the migrator to hold `CREATEROLE`. Both roles are `NOSUPERUSER`, which is the load-bearing part: a superuser ignores every permission check, so running the application as one does not weaken these invariants but makes them **untestable**, and pointing the app credentials at the superuser now fails all seven immutability tests including the precondition that detects it. `TRUNCATE` is asserted separately from `DELETE`, being a distinct privilege that \"we denied DELETE\" reasoning misses. `V008` pays the grants `V002`, `V005` and `V007` each promised, and the granted set is read from the catalogue per table so a too-wide grant fails as loudly as a too-narrow one. **One test was asserting the wrong thing**: a self-`GRANT` does not raise - PostgreSQL warns \"no privileges were granted\" and returns success - so the assertion checked the database's error-reporting choice while the boundary held; it now checks the outcome instead. Teeth proven by granting `UPDATE, DELETE` on the live table: four tests fail. The correlation guard fired for the fourth time and closed the audit sink `P0-TST-003` named. 307 hermetic tests, 136 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-021`. No critical or important findings; three minor ones, all fixed. A **duplicate arriving inside one transaction** - an entirely ordinary poll batch - was untested, and it takes a different database path from a redelivery: the unique violation is raised immediately rather than after blocking, so the savepoint rather than the lock timeout is what keeps the caller's transaction usable. Probed, found correct, and made permanent. `messageType` was validated only in the store, so a caller got the error from three layers down; it is now checked in the wrapper too, **before** the ambient-correlation lookup, so a caller that got both wrong is told about the argument it passed rather than the context it did not establish. Two deferrals recorded as debt with owning phases - inbox retention sweep and inbox metrics - noting that for retention the risk runs only one way: a record never swept deduplicates forever, and it is early expiry that admits a duplicate. 295 hermetic tests, 109 database tests. |
| 2026-09-01 | `P0-TSK-021` complete; `P0-EPIC-06` closed. The inbox: a dedupe record written in the same transaction as the side effect, so it exists if and only if the effect happened. Keyed on **(consumer, dedupe_key)** - scoping to the consumer is the decision that matters, because keying on the message alone lets the first consumer silently suppress every other one, and that defect looks like success until somebody notices months later that a notification never arrived. No state machine, deliberately: an idempotency record needs `IN_PROGRESS` because a caller is waiting to be told something, and nobody waits on a redelivered message. Contention is reported after a 500ms bound rather than waited on, because losing the race costs one redelivery that the broker was going to perform anyway - a trade available to a consumer and not to a command. **An API defect found by using it**: handed an auto-commit connection the store failed with \"could not create a savepoint\", an error about a mechanism rather than about the mistake, when what would actually happen is the dedupe record committing alone and the message being lost; it now refuses auto-commit and says why. Six of six mutations caught after one round exposed a weak assertion - \"the handler did not run\" was checked by counting effects after a rollback, which cannot tell that apart from \"ran and was undone\"; invocations are now counted in memory. The correlation guard fired for the third time and forced the inbox row to be asserted as the fourth sink. 295 hermetic tests, 107 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-020`. One important finding, and it was in the half of the code nobody reads until something is wrong: **every failure log line lacked its correlation identifier**. The correlation scope wrapped only the publish, and a `catch` attached to a try-with-resources runs *after* the resource closes — so the publication-failure warning, the blocked-aggregate warning and the abandonment error, the three lines an operator actually reads, could not be joined to the transfer or payment whose event they concerned. Proven by reading a real Logback appender (three of four new assertions failed), then fixed by scoping the whole per-event handling. Also closed: `P0-TSK-020` was never marked complete in `BACKLOG.md`; ADR-0005 requires a documented poison-message procedure and none existed, though abandonment stalls an aggregate until a person acts — now written, including that an abandoned row is never resolved by deleting it, since the row is the only evidence the gap exists; the `last_error` bound is duplicated between Java and SQL with no test that a maximal error is storable, so a tightened constraint would have made *recording* a broker failure fail; and the advisory-lock namespace had no register. Four deferrals recorded as architectural debt with owning phases — broker adapter, outbox retention, relay metrics, dead-letter tooling — none of them financial-correctness debt. Migrations verified against a from-scratch empty database. 291 hermetic tests, 88 database tests. |
| 2026-09-01 | `P0-TSK-020` complete. The outbox relay: every instance polls, and a **transaction-scoped advisory lock per aggregate** is what makes ordering survive more than one of them. The usual pattern - `SELECT ... FOR UPDATE SKIP LOCKED` - locks rows, so two instances can take events 1 and 2 of the same aggregate and publish them in whichever order finishes first; ordering would hold only while the relay happened to be running singly, which is the assumption ADR-0014 exists to remove. Delivery is at-least-once and is said so: a publisher that delivers and then dies leaves the event unmarked, and the restart delivers it a second time. Ordering under failure is asserted separately from ordering on the happy path, because the two are different properties and only the second is easy. An abandoned row **blocks** its aggregate rather than being skipped - a stall is loud, an undetectable gap in a financial event stream is not. `V006` puts eligibility and abandonment on the server's clock, applying the V004 lesson before it could bite again. Two implementation traps recorded: JDBC **commits** when auto-commit is restored, so a tidy `finally` would turn every error path into a commit; and `RetryPolicy`'s default claimed forty minutes of retrying where the ceiling made it eight, so the test now pins the window rather than the attempt count. No Kafka adapter, deliberately - the relay publishes through a port, and `nothingPublishesToABrokerDirectly` still exempts nothing at all. 291 hermetic tests, 83 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-019`. Two findings, both from probing. **The byte-exactness assertion could not detect its own loss**: re-encoding the payload through `new String(bytes).trim()` survived every test, because every payload chosen — `{}`, a short JSON object, `{1,2,3}` — happens to be unchanged by a trim-and-re-encode. The payload is now deliberately hostile to it: leading and trailing whitespace, a NUL, and a byte that is not valid UTF-8. A relay must publish what the producer wrote, not a round-trip of it. **All ten of `V005`'s constraints were unexercised** — the same gap the `V002` review closed for the idempotency table. Most cannot be reached through the writer at all, since the envelope validates bounds and versions before SQL sees them and nothing writes `attempts` or `published_at` until the relay, which is exactly the argument for testing them at the schema: a constraint application code cannot reach is one only the database will ever enforce, against an operator or a writer nobody has written yet. `OutboxEventSchemaTest` added; both fixes proven by mutation. 283 hermetic tests, 63 database tests. |
| 2026-09-01 | `P0-TSK-019` complete. `platform.outbox_event` carries the full envelope as columns, all ten NOT NULL, and the writer never opens a transaction of its own — so `INV-EVT-01` holds by construction rather than by intent. Proven both ways: a rolled-back fact loses its outbox row and a committed one keeps it, because a rollback-only test would pass against a writer that never wrote anything. A failed write raises rather than logs, since a fact committed without its publication record is a lost event nobody can detect afterwards. `nothingPublishesToABrokerDirectly` enforces the second acceptance criterion, matched by package name so the rule exists before the dependency does and covering method references; proven by planting a direct publish in production code. `V005` records why the relay must select unpublished rows rather than a sequence watermark — allocation happens at insert and visibility at commit, so a watermark relay skips rows permanently. Both self-maintaining guards fired as designed, and the sink guard forced correlation propagation into the outbox row to be asserted, closing one of `P0-TSK-014`'s deferred clauses. 283 hermetic tests, 56 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-018`. All eight mutations of the envelope were caught, including reordering two fields of the canonical form and having an emitted event inherit its parent's cause rather than being caused by the event emitting it — so the two reflection-derived tests are load-bearing rather than merely clever. Three minor findings, all fixed: `correlationForEmittedEvent` used a fully-qualified type name twice where an import sat two lines above; a name at exactly `MAX_NAME_LENGTH` was untested, so an off-by-one to `>=` would have silently rejected a legal name (proven, then closed); and `EventId.of(String)` — the path a received message header takes — had no test that a v4 or a malformed value is refused. 279 hermetic tests, 47 database tests. |
| 2026-09-01 | `P0-TSK-018` complete. `EventEnvelope` enforces `INV-EVT-03` at construction — all ten fields mandatory, so an event that could not be traced cannot be built — and carries metadata only, so relays and consumers can handle events they cannot deserialise and no log line can spill event contents. A boundary conflict had to be resolved first: the architecture places the envelope in `sharedkernel` in three places, but the correlation identifiers it carries lived in `platform` and the shared kernel may not depend upward. Resolved by the general rule rather than a workaround — value types sit below the mechanisms that move them, so the identifiers moved down and `CorrelationContext` with its MDC dependency stayed. `EVENT_ARCHITECTURE.md` listed `eventVersion` and `schemaVersion` without defining either; both are now defined and distinguished, and `schemaVersion` leads the canonical form because a consumer that cannot parse an envelope cannot read the field telling it which layout to expect unless that field never moves. No wire format: that would commit the shared kernel to a serialisation library, and it belongs to the outbox. 277 hermetic tests, 47 database tests. |
| 2026-09-01 | Task completion review of `P0-TST-004`. One important finding, in the test whose headline claim is that it relies on no timing luck: the losers used the default three-second claim wait while the winner held its claim until the lock-wait poll finished, so on a slow machine the losers would time out first and the test would fail for a reason unrelated to what it asserts. The losers now wait far longer than they can need — bounding the wait is a different test's subject and must not be this one's constraint. The lock-wait observation itself was verified real by pointing the losers at a different key and watching the test time out rather than pass. Two mutations survive this suite — the lease condition and the fingerprint check — and both were confirmed caught by the full database suite rather than assumed to be; neither is this suite's subject. Five consecutive full runs green. |
| 2026-09-01 | `P0-TST-004` complete. Five failure modes from `CLAUDE.md` §Failure Engineering driven directly at the idempotency kernel: observed contention, contention outlasting the bounded wait, a response lost after commit, expiry on both sides of the retention sweep, and an instance crashing mid-command. The acceptance criterion "no test relies on timing luck" is met by waiting until PostgreSQL reports the losing sessions waiting on a lock rather than by sleeping — a latch makes threads *begin* together but the winner may finish first, so the test would pass without exercising contention at all. "Test fails if the unique constraint is dropped" demonstrated against the live database: 17 failures, then restored. Recorded that V003 freezes a terminal claim entirely, so retention cannot be extended after completion. `P0-TSK-017` recorded as `BLOCKED` on `P0-TSK-023` and on the HTTP surface `P0-EPIC-08` brings in M0.4. |
| 2026-09-01 | **Multi-instance execution made an explicit architectural requirement** — ADR-0014, [`DISTRIBUTED_EXECUTION.md`](../architecture/DISTRIBUTED_EXECUTION.md), and a new §Multi-Instance Execution in `SYSTEM_ARCHITECTURE.md`. It had been implicit: ADR-0004 and ADR-0005 both depend on it without naming it, and nothing said how many copies of the monolith run. An audit of all production code found it mechanically clean — no locks, schedulers, caches or static mutable business state — and **one real defect**: `P0-TSK-016`'s claim reclaim compared `created_at` written by one instance's clock against a staleness bound computed from another's. An instance running six minutes fast with a five-minute lease would consider every neighbour's fresh claim abandoned, take the key, and run the command while the neighbour was still running it — two financial effects for one request. It passed every test because they all ran in one JVM with one clock. Corrected by `V004`: the lease is set and judged by the database's clock, the client-side staleness predicate is removed rather than kept as a fast path, and a test gives the second instance a clock an hour ahead. The no-floating-point rule then caught a `double` on the new lease path, which was the right call. Remediation recorded as `P0-TSK-041` (architecture rule) and `P0-TST-009` (multi-instance test convention). ADR-0001 is unchanged: one deployable is not one instance. 268 hermetic tests, 41 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-016`. One important finding, from checking a claim rather than reading it: the wrapper's javadoc said the blocking wait was "bounded: see `lock_timeout`" and `lock_timeout` existed nowhere. The claim was false — a duplicate blocked for as long as the first command took, which on a hot key with a retrying client is connection-pool exhaustion, the exact failure the class argues against two paragraphs earlier. ADR-0004 requires a bounded wait then conflict. Closed: the store now bounds the claim with a `lock_timeout` confined to that statement, and `claim()` returns `CLAIMED`/`ALREADY_CLAIMED`/`CONTENDED` because a contended claim has nothing to read — the holder may still commit or roll back — so it is honestly reported as unknown. The test asserts both ends of the bound, since an upper bound alone would pass if the claim failed instantly for an unrelated reason. A mutation sweep then left two survivors, both closed: the store's `state = 'IN_PROGRESS'` guard on recording an outcome, and `isStaleAt`, which survived only because the reclaim statement re-checks staleness in SQL — defence in depth working, and precisely why the Java predicate needed its own test, since together the two mutations would re-run a live command. 272 hermetic tests, 40 database tests. |
| 2026-09-01 | `P0-TSK-016` complete. The execute-once wrapper: claim, run, record, replay — all inside the caller's transaction, so no crash can leave a financial effect that no idempotency record describes. All three acceptance clauses proven against a real PostgreSQL, with "exactly one effect" counted in a side-effect table rather than inferred from the wrapper's own return value. A live `IN_PROGRESS` claim is reported rather than waited on or assumed failed; a stale one is taken over with the staleness test in the database, so two racing reclaims cannot both win. The fingerprint is compared before staleness, so a different request never inherits a key. The store is a port, leaving unresolved question 12 open. Two fixture findings: a `TEMPORARY TABLE` is session-local and so invisible to the racing connections, and `IdempotencyKey` had to become `Serializable` or the exceptions lose their diagnostic state — the `P0-TSK-009` defect again. 268 hermetic tests, 38 database tests. |
| 2026-09-01 | `P0-TST-003` complete; `P0-EPIC-04` and milestone M0.2 closed. The task had been recorded as blocked until M0.4, which was too pessimistic: a second sink already existed, since `platform.idempotency_record` carries `correlation_id NOT NULL`. One request's identifier is now proven identical in the log and in a committed row across a thread handoff, for both an accepted and a generated identifier, with a negative control showing an unwrapped handoff loses it. The three sinks that genuinely do not exist are handled by `CorrelationSinkCoverageTest`, which derives platform concerns from the build output and fails when one appears unclassified — proven by adding an `outbox` package. The four-sink criterion is enforced as the sinks arrive rather than left to memory. 261 hermetic tests, 28 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-015`. All nine constraint mutations were caught, and so was the enum/migration drift guard. One real gap the sweep could not reveal: a `CHECK` constraint sees only the row being written, so V002 constrained row *shape* and said nothing about *transitions*. Probing the developer database with the statement an operator or a defective wrapper would run — `UPDATE ... SET state='IN_PROGRESS', completed_at=NULL` — turned a finished command back into an unfinished one, which a wrapper would then re-execute: a second financial effect from an UPDATE no application code performed. Closed by `V003`, a `BEFORE UPDATE` trigger freezing terminal claims entirely and making identity and fingerprint immutable in any state; both halves proven by isolated mutation. A second finding was a test artefact worth keeping: mixing a client-generated `created_at` with PostgreSQL's `now()` for `completed_at` produced a backwards row, because the container's clock runs behind the host's — which is why these timestamps are application-supplied from one injected clock and the schema declares no `DEFAULT now()`. 259 hermetic tests, 25 database tests. |
| 2026-09-01 | `P0-TSK-015` complete — the platform's first table. `INV-IDEM-01` enforced by a unique key on (scope, idempotency_key) and proven under 16-way contention against a real PostgreSQL: exactly one winner, every loser a unique violation. The state machine is checked in the schema as well as in code, the fingerprint's algorithm is recorded on the record (`INV-HIST-04`'s rule applied to the thing that decides whether two requests are the same), and the response is stored as bytes so a retry receives what the first caller received. Established that this table is legitimately mutable and so not gated on the `P0-TSK-022` privilege split. `flywayValidate` caught a checksum mismatch when the migration was edited after being applied locally — the rule working; repaired, then verified against an empty scratch database. Expiry policy documented in `DATA_MIGRATIONS.md` §8, including why too-short expiry costs money and too-long costs storage. 259 hermetic tests, 20 database tests. |
| 2026-09-01 | Task completion review of `P0-TSK-014`. All eight mutations of the correlation kernel were caught, including `InheritableThreadLocal`, which fails through the unwrapped-task path — so the claim the design rests on is genuinely tested rather than merely argued. Two gaps the sweep could not reveal, both fixed: the token charset used `String.matches`, recompiling the expression on the ingress path of every request; and the MDC key names are documented as a published contract that log queries and dashboards are written against, yet every test used the constants, so renaming one would have been a compile-safe refactor that silently broke every dashboard. Literals now pinned. Also recorded that `propagate` returns an `Executor` rather than an `ExecutorService`, so a caller needing `submit` wraps the task — no speculative decorator written. 255 tests. |
| 2026-09-01 | `P0-TSK-014` complete for what can be verified now. Correlation and causation modelled as distinct types, validated as untrusted input against log injection, with a context that survives an async handoff and provably does not leak between tasks on a pooled thread — the failure `InheritableThreadLocal` would have introduced. Log lines proven to carry the identifier by reading a real Logback appender. **The acceptance criterion could not be met as written**: it names a trace, an emitted event and an ingress filter, and the exporter, outbox, audit store and HTTP surface all arrive in later milestones. Backlog corrected and the clauses transferred to `P0-TST-003`, which is recorded as blocked until M0.4. `slf4j-api` added to `platform` (facade only). 254 tests. |
| 2026-09-01 | Task completion review of `P0-TSK-013`. Three findings in the rule itself, all from probing rather than reading. **`Instant::now` as a method reference bypassed the rule entirely** — a method reference is an `invokedynamic`, not a call, so `getMethodCallsFromSelf()` never sees it; closed with `getMethodReferencesFromSelf()`, and a rule one syntax away from being bypassed is not enforcement. **`TemporalAdjusters.firstDayOfNextMonth` was forbidden and should not have been** — it is applied to a date the caller already holds and reads nothing, so the rule was pushing people off a correct API, exactly the failure its own javadoc warns about. **Ambient *zone* was not forbidden**, though `instant.atZone(ZoneId.systemDefault())` makes which date an instant falls on depend on server configuration — a dating defect no amount of clock injection prevents. Also removed a dead `java.sql.Timestamp` entry that could never match. All four re-probed, including two positive controls proving the allowed APIs stay allowed. 219 tests. |
| 2026-09-01 | `P0-TSK-013` complete. Two ArchUnit rules make ambient time a build failure, with `Clock.systemUTC()` permitted in the composition root alone. The rules immediately caught a violation written in the previous task — `IdGenerator.systemDefault()` — which was removed rather than exempted. `Instant.now(clock)` is deliberately allowed, since forbidding the clock-taking overloads would push people off the correct API. `TestClock` replaces an inline test clock and makes `rewind` a named operation, because NTP correction moves real clocks backwards. `DOMAIN_MODEL.md` §Time records the posting-date/value-date/system-time distinction, which no rule can enforce. The `P0-DOC-002` documentation-equivalence check failed the build until the new rules were documented, one task after it was written. 219 tests. |
| 2026-09-01 | Task completion review of `P0-TSK-012`. A mutation sweep over `IdGenerator` and `EntityId` found one real gap: deleting the RFC-variant check from `EntityId` survived every test, because every rejection case in the suite already failed the *version* check first, so the variant branch was never reached. A version-7-but-wrong-variant value claims to be time-ordered while not being an RFC 9562 UUID, and its high bits would be read as a timestamp on the strength of a version field nothing corroborates. Case added; the mutation now fails. Two other mutations survived and are correct to: `hashCode` dropping the class component violates no contract (`equals` still distinguishes), and making `EntityId` final is caught at compile time rather than by a test — my probe harness reported it as surviving because it parsed stale results without checking the exit code, which is the same defect shape these reviews keep finding, this time in the probe rather than the code. 213 tests — the case was added to an existing rejection test rather than as a new one. |
| 2026-09-01 | `P0-TSK-012` complete; ADR-0013 recorded. Typed aggregate identifiers over UUIDv7. The task named `CustomerId` and `AccountId`, but those are business nouns owned by `party` and `accounts` and the shared kernel forbids them, so the kernel holds the mechanism and the compile-error criterion is proven with probe types — by invoking `javac` on the substitution, since a compile error cannot be asserted at run time. Monotonicity is engineered rather than inherited from the clock: a counter rather than randomness in the 12-bit field, borrowing the next millisecond on exhaustion, and no regression when the clock jumps backwards. The restart test makes explicit that cross-instance uniqueness rests on the 62 random bits, not the counter. 213 tests. |
| 2026-09-01 | `P0-TST-002` complete, closing `P0-EPIC-03`. The criterion's naive-division clause was already satisfied — that mutation is caught by eight existing tests — so the work was the untested range: 1..100 parts rather than 1..40, and amounts from the whole representable range rather than a band around zero. Both sweeps assert they encountered indivisible remainders, because zero residual is trivially true on divisible amounts and a sweep of those would pass over a broken allocator. Evenness asserted separately: an allocator dumping the whole remainder on the first part satisfies totality and is caught only by that. |
| 2026-09-01 | `P0-DOC-001` complete. `README.md` covering prerequisites, build, test, infrastructure lifecycle, migrations and CI gates. Verified by cloning the repository into a temporary directory and running every documented command in order, with infrastructure stopped first so the hermetic-build claim was tested rather than asserted. One inaccuracy found and corrected: `toolchainInfo` reports the launcher JVM, not the compile toolchain. Closes `P0-EPIC-01` and completes every item in milestone M0.1; only "green in CI" remains, blocked on the absent git remote. Milestone pointer corrected from M0.1 to M0.2, which the last five tasks had already been working in. |
| 2026-09-01 | Task completion review of `P0-TST-001`. A mutation sweep over `Money` found three surviving mutants, two of them real gaps. Reversing `compareTo` survived every ordering property — antisymmetry, transitivity and consistency with `equals` are all satisfied by a comparator running backwards, so the properties described its shape but never its orientation; closed by stating the orientation against `BigDecimal`'s own ordering. Deleting the scale comparison from `equals` also survived, because the generator built every amount with `ofMinorUnits` and so never varied scale at all — an entire dimension of `Money`'s state was invisible. Generation is now scale-aware, with a new property asserting same-currency/different-scale operations are rejected and that such amounts are not equal. The third mutant, `hashCode` ignoring currency, survives correctly: `hashCode` may collide. 190 tests. |
| 2026-09-01 | `P0-TST-001` complete. `MoneyPropertiesTest` asserts Money's algebraic laws over generated values, where the existing coverage was example-based — commutativity had rested on a single triple of small positive amounts. Each law asserts its own coverage so it cannot pass by rejecting everything. The rounding properties initially failed the acceptance criterion: making every policy round `CEILING` passed, because the `FLOOR`/`CEILING` bracket was computed through `Money` itself and the break moved the bounds with the value. Replaced with per-policy defining properties; all three deliberate breaks named in the criterion now fail. 188 tests. |
| 2026-09-01 | Task completion review of `P0-DOC-002`. One important finding: rule-suite discovery listed a single directory, so a suite placed in a subpackage ran its rules on every build while escaping the documentation check — enforced but undocumentable, with the equivalence test still green. Proven with a probe suite, then closed by walking the whole test-classes tree (loading classes with `initialize=false`, since deciding whether something is a rule suite must not run its static initialiser). Also confirmed a missing document fails rather than passing vacuously, and that the ten `*(ArchUnit: ...)*` markers all sit in §6 and yield exactly the twelve enforced rule names with no false positives. |
| 2026-09-01 | `P0-DOC-002` complete. Audit of `MODULE_ARCHITECTURE.md` against the enforced rules found six drifts, including a rule enforced on every build that the §6 list did not mention, and two sections still calling `INV-MON-01` unenforced two tasks after it was enforced. Prose fixed, then the equivalence made mechanical: every enforcement claim names its rule, and `ArchitectureRulesAreDocumentedTest` fails the build in either direction. Probing also found `:app:test` staying `UP-TO-DATE` after the document was broken — the document is now a declared task input. Closes `P0-EPIC-02`. 172 tests. |
| 2026-09-01 | Task completion review of `P0-TSK-008`. One critical finding, found by probing rather than reading: the new coverage guard asserted only that `Money` was analysed, so it could not see a whole module falling out of the sweep. Proven by narrowing the sweep to `sharedkernel` — all four floating-point rules reported PASSED and the build exited 0 with a `double` planted in `platform`'s `MoneyColumns`. This is the same weakness the `P0-TSK-007` review found and that the new rule's own javadoc cites as its rationale. Both suites now derive expected coverage from the classpath through a shared `ProductionModules` helper, and the identical probe now fails the build. Also verified end to end that a `float` in a signature and a `Double.parseDouble` call in production code each fail the build — the latter also catching `BigDecimal.valueOf(double)`, the trap beside the safe `valueOf(long, int)`. |
| 2026-09-01 | `P0-TSK-008` complete. `INV-MON-01` enforced statically over all production code — fields, signatures, call targets and field accesses, including generic arguments. Default-deny rather than a list of financial packages, with deliberate-violation fixtures asserting the teeth on every build. Proven end to end by planting a `double` in `Money`. Closes `P0-EPIC-02` and Phase 0 exit criterion 4. 170 tests. |
| 2026-08-31 | Task completion review of `P0-TSK-011`. Probing PostgreSQL showed `CHAR(3)` accepts `'US '` — the column type was not the guarantee the design implied, leaving `INV-MON-02` enforced only by application code against `DEFINITION_OF_DONE.md` §1.3. Added check constraints on the currency pattern and scale range, generated from `Money.MAX_SUPPORTED_SCALE`, and proved them by weakening them. Also switched the not-null assertion from message text to SQLState (messages are localisable), tightened `trim()` to `stripTrailing()` to match its own stated rationale, and made the write path use `MonetaryColumnException` like the read path. |
| 2026-08-31 | `P0-TSK-011` complete. `MoneyColumns` fixes the three-column storage shape from ADR-0003 and supplies the DDL migrations use. Round-trip verified against a real PostgreSQL across 0-, 2-, 3- and 4-decimal currencies and the `BIGINT` extremes. Written mechanism-agnostic: the task asked for a JPA embeddable, but no ADR has chosen a data-access mechanism — recorded as unresolved question 12. |
| 2026-08-31 | Task completion review of `P0-TSK-010`. One important finding: `allocate(int)` and `allocate(long...)` resolved silently by literal width — `allocate(3)` split three ways, `allocate(3L)` returned the whole amount as one part. Proven, then removed by renaming to `allocateEvenly` / `allocateByWeights`, with a test guarding against reintroduction. |
| 2026-08-31 | `P0-TSK-010` complete. `RoundingPolicy` with six named policies, explicit-policy rounding, and allocation that distributes the indivisible remainder rather than absorbing it. Zero-residual proven by sweeping ~160,000 even splits and 2,000 weighted ones, and demonstrated to fail when the remainder is discarded. 127 tests. |
| 2026-08-31 | Task completion review of `P0-TSK-009`. One important finding: the diagnostic state on the monetary exceptions was `transient`, so `left()` and `right()` returned `null` after serialization — proven by round-tripping one, and fixed by making `CurrencyCode` serializable. Also corrected an operand-order inversion in the mismatch message, and added the three tests whose absence let those through: scale mismatch on `minus`/`compareTo`, `absoluteValue` overflow, and serialization of diagnostics. 74 tests. |
| 2026-08-31 | `P0-TSK-009` complete. `Money` and `CurrencyCode` — the platform's first financial code. Integer minor units, explicit currency, stored scale; exact arithmetic only, with cross-currency, cross-scale, inexact-amount and overflow failures all distinct and all under one `MonetaryException` supertype. 70 tests. |
| 2026-08-31 | Task completion review of `P0-TSK-007`. Probing showed ArchUnit was importing exactly one class — benign (it skips `package-info`, which is all `platform` and `sharedkernel` contain), but it exposed that the coverage guard asserted only that `app` was seen and would have passed if a module were dropped from the analysis. Guard replaced with one that derives expected coverage from the classpath, proven by excluding a module that had production code. Also added a rule that production classes must belong to a module package: a class directly in `com.finapp` was silently exempt from every rule. |
| 2026-08-31 | `P0-TSK-007` complete. Six ArchUnit boundary rules enforced on every build, each proven by a deliberate violation; plus a guard test so the suite cannot become silently vacuous. `MODULE_ARCHITECTURE.md` §2 and §8 corrected — they claimed the rules were "not yet in place". |
| 2026-08-31 | Task completion review of `P0-TSK-006`. Four defects found and fixed, all by mechanical checks rather than re-reading: `Instalment` owned by both `lending` and `bnpl` (the headline acceptance criterion, violated); the `app` module absent from the register entirely; `paymentmethods` and `crossborder` missing from the layering diagram; the §5 ownership table maintained as a second enumeration that could drift from §4. |
| 2026-08-31 | `P0-TSK-006` complete. Context-to-module map: 28 contexts to 24 modules, all nine boundary attributes per module, authoritative-state ownership table. Found two missing bounded contexts and one state at risk of two owners. ADR-0012 records the mapping decision. |
| 2026-08-31 | `P0-TSK-004` complete. CI with four gates: build/tests, migrations against real PostgreSQL, secret scan over full history, SBOM dependency scan. Actions SHA-pinned, scanners digest-pinned. Not yet executed on a runner — no git remote exists. |
| 2026-08-31 | Task completion review of `P0-TSK-001`, `-002`, `-003`, `-005`. No critical or financial findings — no money-handling code exists yet. Six important findings fixed: an unsatisfiable `DOD-BUILD` "CI green" requirement caused by over-specified `P0-TSK-004` dependencies; a one-directional infrastructure drift check that let an unpinned image pass (proven, then closed); dead Spring Boot configuration in `build-logic` (proven unnecessary); an unnecessary Spring test stack in `platform` contradicting its own comment; a name-substring scope test replaced with a structural one; ADR-0011 missing from `DECISIONS.md`. Java toolchain version moved into the version catalog, removing four duplicated copies of "21". |
| 2026-08-31 | `P0-TSK-005` complete. Flyway 12.4.0, forward-only, module-owned schema history; ADR-0011 and `DATA_MIGRATIONS.md` written. |
| 2026-08-31 | `P0-TSK-003` complete. Local infrastructure (PostgreSQL 18.6, Kafka 4.3.1 KRaft, Redis 8.10.1), pinned and health-checked, with a build-enforced version-drift check against the catalog. |
| 2026-08-31 | `P0-TSK-002` complete. `sharedkernel`, `platform`, `app` with enforced dependency direction; `sharedkernel` proven Spring-free; `java-library` adopted for `api`/`implementation` boundary control. |
| 2026-08-31 | `P0-TSK-001` complete. Gradle 9.7.1 multi-module build, Java 21 toolchain, Spring Boot 4.1.1 BOM, `build-logic` conventions, checksum-pinned wrapper. Phase 0 `IN_PROGRESS`. Repository placed under Git. |
| 2026-08-31 | Project initiation. Delivery plan, phase gates, backlog, architecture baseline, invariant catalog, Definition of Done, execution protocol and ADR-0001..0010 created. Phase 0 entry gate passed; status `READY`. |
