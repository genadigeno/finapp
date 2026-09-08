# Phase 1 Review Record

**Phase 1 — Identity and Customer Foundation**
Conducted: 2026-09-08, at `IN_REVIEW`, per [`PHASE_GATES.md`](../PHASE_GATES.md) §4.
Task: `P1-DOC-001`.

---

## The verdict in one table

| | Outcome |
|---|---|
| Review areas (8) | **7 `PASS`, 1 `NOT APPLICABLE`** — area 2 has no subject and says so |
| Universal exit criteria (12) | **10 `PASS`, 2 `FAIL`** |
| Phase 1-specific criteria (6) | **5 `PASS`, 1 `PARTIAL`** |
| **Phase 1 verdict** | **remains `IN_PROGRESS`** |

`PHASE_GATES.md` §4: *"A review that finds a gate failure returns the phase to `IN_PROGRESS`."*
§1 is explicit that moving backwards from review is normal, while **shipping through a failed gate**
is the failure. Phase 0's review reached the same conclusion and was vindicated within days, when
the first CI run it had refused to waive failed twice for defects no local run could reach.

**Neither failure is architectural.** Phase 1's design work is done; what is missing is a connection
between two things it built, and four meters.

---

## The two gate failures

### Criterion 1 — Required functionality exists: **FAIL**

**No production path issues a first session.** Traced through the code rather than inferred:

| Step | Finding |
|---|---|
| The only writer of session rows | `SessionRotation`, via `sessions.insert` |
| Its only production caller | `MfaChallenge.elevate` |
| What `elevate` requires | an existing `current` session |
| The endpoint that reaches it | `MfaChallengeController`, which is `@RequiresSession` |
| `POST /v1/authentications` on success | **`204 NO_CONTENT`, no body, no session** |

So a real client cannot obtain a session by any route, and the **eight endpoints
`PHASE_1_PLAN.md` §7 marks `Auth: session` are unreachable**. Every test that exercises them
inserts a session row directly.

`MfaBypassPathsAreEnumeratedTest` has recorded this since `P1-TSK-019`, in as many words:
*"nothing in production calls `Session.issue` … that is an accident of sequencing rather than a
design goal — `P1-TSK-027` will add the second path."* The guard was right, the task exists, and it
is `TODO`.

**Why this is criterion 1 and not a nicety.** The criterion demands deliverables *"exercisable end to
end"*, and the phase objective this review is assessing reads *"a Party can exist, become a Customer,
hold an Identity, **prove it, hold a session with a recorded assurance level**, and have every
privileged action authorised and audited."* Proving an identity and holding a session are both built;
nothing joins them.

**Owner:** `P1-TSK-027`. It is a small change — issue the session inside the authentication
transaction — and it is the difference between a phase that works and a phase whose parts do.

### Criterion 6 — Observability exists: **FAIL**

`PHASE_1_PLAN.md` §Observability names six meters. **Two exist.**

| Meter | Exists |
|---|---|
| `finapp.identity.authentication` | ✅ |
| `finapp.identity.lockout` | ✅ |
| `finapp.identity.mfa_challenge` | ❌ |
| `finapp.identity.session_lifetime` | ❌ |
| `finapp.identity.recovery` | ❌ |
| `finapp.identity.active_sessions` | ❌ |

The criterion asks for *"metrics, traces and structured logs for the phase's **critical flows**"*, and
the four missing ones are exactly the phase's critical flows: the second factor, session lifetime,
recovery and the live session population.

**`finapp.identity.recovery` is the one that matters most**, and the plan says why in its own
annotation: *"recovery is the ATO vector; its rate is a security signal."* A takeover campaign is a
rise in recovery initiations, and today that is visible only by querying the audit trail — which is
evidence, not monitoring. `INV-AUD-01` is satisfied and criterion 6 is not, and the distinction is the
whole point of having both.

Recorded as a note at `P1-TSK-023`'s completion gate. This review is where it becomes an assessment.

**Owner:** new backlog item `P1-TSK-029`, created by this review.

---

## 1. Domain correctness — `PASS`

Party, Customer and Identity are three aggregates in two modules with three lifecycles, exactly as
`DOMAIN_MODEL.md` and ADR-0029 require. `ThreeAggregatesAreSeparateTest` asserts it as the four
shapes a merged model **cannot represent** — a person who is not a customer, a customer who is not a
person, one Party holding a retired login and its replacement, and lifecycles that move independently
— which is a stronger statement than an abstract claim of separation.

`DELIVERY_PLAN.md` §17 named collapsing them as Phase 1's top risk. It did not happen, and a status
field added to `Party` fails the build.

**Two modelling decisions read as omissions and are the design**, both checked against the glossary:

- **`Party` has no lifecycle.** Existence has no states; every state one reaches for — inactive,
  closed, archived — is a statement about a *relationship* or a *login*, each of which has its own
  table. A status on `Party` would be one fact in two places, free to disagree.
- **`LoginIdentifier` is deliberately not an email address**, and its charset excludes `@`
  specifically so the confusion cannot arrive through the first person who types one. `P1-TSK-023`
  finally built the separate, separately-verified channel that decision always implied.

---

## 2. Financial correctness — `NOT APPLICABLE`

The area asks to *"walk one real posting end to end."* **Phase 1 creates no posting**, no account, no
ledger and no money. There is nothing to walk, and reporting a pass would be reporting on a
subject that does not exist.

This is the same answer Phase 0's review gave, and it is stated rather than skipped because a
reader comparing review records must be able to tell *"assessed and clean"* from *"had no subject."*

**What can be said**: no monetary type appears anywhere in this phase, `INV-MON-01`'s static rules
sweep both new modules — proven by `P1-TSK-003`, where a `double` planted in `PartyAuditAction` failed
two floating-point rules — and the phase introduces no path by which money could be affected.

The first posting is Phase 3. F1–F8 do not apply to this phase.

---

## 3. Boundary integrity — `PASS`

No module reached into another's state.

- `PartyModuleIsolationTest` and `IdentityModuleIsolationTest` assert neither module sees the other
  nor `app`, each with a **non-vacuity half** asserting it *does* see `platform` and `sharedkernel`.
- `identity.identity.party_id` carries **no `REFERENCES` clause**, deliberately (ADR-0029), and a test
  fails if one is added. The cost is stated rather than hidden: the database will accept an identity
  for a party that does not exist, and what prevents it is the registration transaction writing both
  in one commit — a property a test asserts, not the schema.
- Registration spans two contexts and belongs wholly to neither, so **`app` contributes two calls and
  a transaction** and each module writes its own rows, events and audit record.

**One boundary decision was made by a build rule and the rule was right.** `P1-TSK-023`'s controllers
unwrapped a token and an email address; `SecretsAreUnwrappedInOnePlaceTest` refused it, because a
plaintext in `app` is a plaintext outside the module that owns secrets. The boundary now passes
`Sensitive<String>` through untouched.

---

## 4. Failure behaviour — `PASS`

`PHASE_1_PLAN.md` §8 lists twelve scenarios. All twelve have tests or a stated rationale; two are
traced here.

### Concurrent login and revocation

*"Revocation wins. A session must never survive a concurrent revoke."*

`P1-TSK-014` found the **hard reading broken and verified it broken before writing any machinery**: a
session *issued* concurrently with a revoke-all was still live afterwards, because the insert lands
after the revoke has selected its rows. An attacker holding the old password kept a live session
across the password change, and **every revoke-then-look-up test passed**.

The fix is one explicit lock, not two. An explicit lock was written on the issuing side as well, on
the reasoning that both sides of a race must take one — and **a mutation removing it survived**,
because PostgreSQL already takes `FOR KEY SHARE` on the referenced row for every insert with a
foreign key. It was removed rather than left in: a redundant lock reads as *the* mechanism and hides
the real one.

The race test waits for PostgreSQL to report the issuer **blocked**, rather than asserting the end
state — because with the lock the insert serialises after the revoke and the new session is live, and
without it the insert races and the new session is *also* live. Same rows, opposite mechanisms.

### Database unavailable

*"Authentication fails closed. Never a session issued without a durable record."*

`P1-TSK-012` established that failing closed is **two claims**: no success reported, **and** no durable
trace claiming otherwise. A platform returning a failure while having committed the audit record of a
success would be worse than one that crashed, because the trail would say a person logged in,
permanently, under `INV-HIST-03`.

The kill is deterministic — the flow asks the connection for its own backend identifier and
terminates it from inside — after a timed version raced a one-millisecond derivation and disrupted
nothing. And the assertion had to be tightened: a mutation swallowing the storage failure and
reporting `401` **survived**, which is a real defect rather than a cosmetic one, because reporting
`401` logs every user out during a database blip and says *"this session is not live"* when the truth
is *"we cannot tell"*.

---

## 5. Security — `PASS`

**Every privileged action is enumerated, authorised and audited**, and each of those three is now
checked by the build rather than by reading.

| Question | Mechanism |
|---|---|
| Is every action catalogued? | `AuditableActionRegistryTest`, three directions |
| Is every catalogued action **emitted**? | `AuditCompletenessTest` (`P1-TSK-022`) — this closed the limit `P0-TSK-023` recorded against itself |
| Does every endpoint declare a rule? | `EveryEndpointDeclaresARuleTest` **and** the interceptor, at build time and run time |
| Is ownership checked against authoritative state? | `OwnershipIsScopedTest` (`P1-TSK-021`) |
| Does every record name the person? | `AuditNamesTheActorDatabaseTest` |
| Where may the platform claim to be the actor? | `SystemActorCallSitesAreEnumeratedTest` — **three** sites, all unauthenticated, each justified |

**Nineteen auditable actions** (17 `identity`, 2 `party`). Five are declared **not yet emitted** with
the task that will emit them — `identity.IdentitySuspended`, `party.ProfileChanged` and the three
`outbox.*` actions carried as Phase 15 debt.

**The phase found three real security defects in its own earlier work**, each by probing rather than
reading, and that is the strongest evidence this area can offer:

1. **`SessionRevocation.revoke` took an owner and never checked it** (`P1-TSK-016`) — any caller could
   end any session by identifier, while the audit record asserted an owner nobody had verified. Worse
   than an absent parameter, because the signature read as though ownership were enforced.
2. **A `PASSWORD` session could begin a replacement MFA enrolment** with an attacker-controlled secret
   (`P1-TSK-019`), refused only by a partial unique index and surfacing as a 500. The property held by
   accident of a constraint rather than by a decision.
3. **A handler declaring both `@Unauthenticated` and `@RequiresPermission` was served `200`**
   (`P1-TSK-020` gate) — and **both** guards passed it. The harm was not that it was public but that
   it *read* as protected.

**One recorded weakness stands**: no per-source rate limiting. Building it now would be harmful rather
than premature — behind the load balancer this architecture commits to, `getRemoteAddr()` is the
balancer, and the threshold would take authentication down for everyone. The missing input is a
deployment topology. Owned by Phase 15.

---

## 6. Test quality — `PASS`

**847 hermetic tests, 404 database tests.** Every Phase 1 invariant has a demonstration recorded in
`MUTATION_TESTING.md`, and `MutationDemonstrationTest` now enforces that continuously for **every
phase reached** rather than Phase 0 only (`P1-TSK-024`).

**What that task found is the honest measure of this area.** Nine register rows **did not parse** —
eight written during this phase — so the guard had not been checking that the tests they name exist.
A register whose rows the guard cannot read reports coverage it does not have. It was proven
precisely rather than argued: a second-position reference naming a missing test **survives** the old
parser and is **caught** by the new one.

**What the guard still cannot check** is that a *recorded* procedure still reproduces — re-running one
means mutating production code or the schema, which a build must not do to itself. That residual is
why the register labels each row's form.

**The phase's recurring finding, stated plainly.** Across twenty-four tasks the defect was almost
never in production code. It was in the thing doing the checking: a rule whose vocabulary was one
third structurally unreachable; a coverage guard asserting `contains` where its four siblings assert
equality; an exemption citing a test that did not exist; a `contains` over source text matching a
comment rather than SQL; a test passing for the wrong reason because it looked up the latest row; and
a register parser silently dropping what it could not read. **Six mutations survived their first run
this phase, and every one of them found something real.**

---

## 7. Documentation drift — `PARTIAL`, and three drifts found

Hand-diffed where no guard reaches — the method that found two drifts in Phase 0's review.

### `PHASE_1_PLAN.md` §7 declares fifteen endpoints; twelve exist

| Planned, absent | Owner |
|---|---|
| `POST /v1/me/credential` | `P1-TSK-026` (`TODO`) |
| `POST /v1/identities/{id}/suspension` | `P1-TSK-028` (`TODO`) |
| `POST /v1/identities/{id}/roles` | `P1-TSK-028` (`TODO`) |
| **`GET /v1/me`** | **nobody** |
| **`PATCH /v1/me`** | **nobody** |

**`GET /v1/me` and `PATCH /v1/me` are owned by no backlog task at all** — the **eighth** backlog defect
of this class in Phase 1, and the first found by a review rather than by the task that tripped over
it. Recorded as `P1-TSK-030`.

Two endpoints exist that the plan never declared — `POST /v1/me/channels` and
`/v1/me/channels/verification` — added by `P1-TSK-023` because `INV-IDN-06` had **no subject**, which
was recorded at the time as that phase's most consequential backlog defect.

### An exemption in `AuditCompletenessTest` rests on a false statement

Its entry for `party.ProfileChanged` reads *"No Phase 1 endpoint changes a party profile.
`PHASE_1_PLAN.md` does not list one."*

**It does list one: `PATCH /v1/me`.** I wrote that entry during `P1-TSK-022` and it is untrue.

This is the sharpest kind of drift, and the same shape `P1-TSK-018`'s gate found: **an exemption is a
claim that something is safe by other means, so a false claim is a hole with a paragraph in front of
it.** The entry stays — the action genuinely is unemitted — and its reason is corrected to say the
endpoint is *planned and unbuilt* rather than *not planned*.

### `CURRENT_STATE.md` §Next Task carried an imprecision of mine

It said *"the phase-specific exit criteria name seven identity properties and there are eight."*
`PHASE_GATES.md` §5 lists **six bullets**, not seven properties; the claim conflated the exit criteria
with the transition's seven `INV-IDN` properties. Corrected by this review, which is why a review
checks claims rather than inheriting them.

**Everything a guard covers is clean**: the architecture-rule register, the auditable-action
catalogue, the API conventions, the glossary, the column classification, the published contract and
the mutation register all reconcile in the build.

---

## 8. Architectural debt — `PASS`

Every item is recorded with what was deferred, why, the risk carried, the trigger and the owning
phase. Phase 1 **closed** four debt rows — the correlation-identifier disclosure, connection-pool
sizing, the output scrubber and the absent security scope — and opened these:

| Deferred | Trigger | Owner |
|---|---|---|
| No per-source rate limiting | A deployment topology and a trusted-proxy declaration | Phase 15 |
| `POST /v1/registrations` unauthenticated and unthrottled | `P1-TSK-011` landing gave a mechanism to extend | Phase 1 |
| Broker adapter — **the trigger has been reached** | Three domain events are written and nothing publishes them | Phase 1 |
| `@ArchTest` rules do not run in the `architectureTest` tier | Found by `P1-TSK-003`'s acceptance probe | `P1-TSK-025` |
| Four-eyes approver not modelled (`INV-AUD-04`) | The first action requiring a second approver | Phase 3 |

**None is financial-correctness debt**, which `EXECUTION_PROTOCOL.md` never permits.

**The broker adapter deserves emphasis**: its recorded trigger — *"the first producer"* — was reached
at `P1-TSK-006`, and the phase has since added six more event types. The events are durable and
unread rather than lost, so `INV-EVT-01` holds and the risk is bounded; it becomes real with the first
consumer. **No backlog task owns it**, which `PHASE_1_PLAN.md` §12 already flagged as a gap.

---

## The twelve universal exit criteria

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Required functionality exists | **FAIL** | No production path issues a first session; eight endpoints unreachable. `P1-TSK-027` |
| 2 | Architectural boundaries respected | `PASS` | Isolation tests both directions; no cross-module FK; no undeclared dependency |
| 3 | Required invariants tested | `PASS` | Every Phase 1 invariant has a demonstration, enforced by `MutationDemonstrationTest` |
| 4 | Failure cases handled | `PASS` | All twelve §8 scenarios; two traced in area 4 |
| 5 | Security implemented | `PASS` | Deny-by-default, ownership, audit — each checked at build time and run time |
| 6 | Observability exists | **FAIL** | Four of six meters missing, including the recovery rate |
| 7 | Integration tests pass | `PASS` | 404 database tests green against real PostgreSQL via Testcontainers |
| 8 | Documentation reflects reality | `PASS` after this review's three corrections |
| 9 | `CURRENT_STATE.md` updated | `PASS` | Updated by this review with the verdict |
| 10 | Relevant ADRs `Accepted` | `PASS` | ADR-0029…0034 moved by this review — see below |
| 11 | No unresolved critical issues | `PASS` | Blockers: none. No debt row is financial-correctness debt |
| 12 | Formal review conducted | `PASS` | This record |

## The six Phase 1-specific criteria

| Criterion | Verdict | Evidence |
|---|---|---|
| Every protected endpoint has a passing negative authorization test | `PASS` | One per declaration class, plus a positive control so refusal is not blanket (`P1-TSK-020`) |
| Credentials verifiably never appear in logs, events or API responses | `PASS` | `P1-TST-001`; the unwrap whitelist pins `expose()` to named classes and fails the build on a new one |
| MFA cannot be bypassed by any tested path; session revocation is immediate and tested | `PASS` | `P1-TST-003`, five paths each named; revocation proven across instances and caught twice |
| Account recovery cannot be used to take over an account under the tested abuse cases | `PASS` | Nine abuse-case tests (`P1-TSK-023`) |
| Every privileged action produces an audit record with all seven fields | **PARTIAL** | True of every action that is **emitted**; `identity.IdentitySuspended` has no endpoint (`P1-TSK-028`), so the criterion is met for what exists and cannot yet be met for what does not |
| Party, Customer and Identity separately persisted with distinct lifecycles | `PASS` | `ThreeAggregatesAreSeparateTest` |

---

## Decisions taken by this review

### ADR-0029 … ADR-0034 moved to `Accepted`, despite the open failures

The same reasoning Phase 0's review recorded, and it holds unchanged: **criterion 10 is a
precondition of the gate rather than a reward for passing it.** The gate requires the ADRs to be
accepted, so accepting them is work toward it. Holding ADR-0030 at `Proposed` because four meters are
missing would be theatre — the decisions were taken, implemented and tested, and neither failure is
contingent on any of them.

### Two backlog items created

- **`P1-TSK-029`** — the four missing meters, which is criterion 6's remediation.
- **`P1-TSK-030`** — `GET /v1/me` and `PATCH /v1/me`, which the plan declares and no task owned.

### One test comment corrected

`AuditCompletenessTest`'s `party.ProfileChanged` entry, whose stated reason was false.

---

## What the phase actually produced

**Counted, not quoted** — see the note below this table.

| | |
|---|---|
| Modules | 2 new (`party`, `identity`), both schema-owning |
| Tables | **10** — 8 in `identity`, 2 in `party` |
| Endpoints | 12 published, contract compared byte for byte on every build |
| Aggregates and entities | **8** across 3 bounded contexts |
| Auditable actions | 19 (17 `identity`, 2 `party`), reconciled in three directions |
| Invariants | 8 new (`INV-IDN-01`…`08`), taking the platform to **72** |
| ADRs | 6 (0029–0034) |
| Tests | 847 hermetic, 404 database |
| Backlog | **25 of 31** complete; 5 `TODO` and `P1-TSK-027` open |

### The completion gate found three of these numbers wrong, and the reason matters

The first version read *"12 tables in `identity`"* (that is the **migration** count — four of the
twelve alter rather than create), *"6 aggregates"* (the **plan's** number, before `P1-TSK-023` added
`ContactChannel` and `RecoveryRequest`) and *"24 of 29 backlog items"* (stale the moment this review
completed one item and created two).

**All three were quoted from a plan rather than counted from the repository** — which is precisely the
drift this review found in area 7 and criticised. A review record asserting numbers it did not check
has the same defect as the document it is auditing, and `DEFINITION_OF_DONE.md` §1.13 forbids
aspirational statements presented as current fact without exempting the review that enforces it.

**What it deliberately did not build**: money, accounts, a ledger, WebAuthn, a notification adapter, a
broker adapter, or per-source rate limiting — each recorded with its owning phase rather than left
implicit.

---

## What happens next

Phase 1 is `IN_PROGRESS`. To reach `COMPLETE`:

1. **`P1-TSK-027`** — authentication issues a session. Closes criterion 1 and lets M1.2 close.
2. **`P1-TSK-029`** — the four meters. Closes criterion 6.
3. Re-run this review against those two criteria.

`P1-TSK-025`, `-026`, `-028` and `-030` are open and **do not block the gate**: none is named by a
universal or phase-specific criterion, and each is recorded with its owner. That distinction is the
gate doing its job — it blocks on the criteria, not on the backlog being empty.

### One criterion deserves a sharper answer than it got

**Criterion 11** — *"Known-issues list contains no `critical` or `high` severity item"* — is recorded
`PASS` on the grounds that §Blockers is empty and no debt row is financial-correctness debt. The
completion gate asked whether criterion 1's failure is itself a high-severity known issue, and the
answer is that it is **not a known issue at all**: it is a *criterion failure*, which the gate model
handles directly by returning the phase to `IN_PROGRESS`. Recording it twice — once as a failed
criterion and again as a blocker — would double-count one fact and make the known-issues list a
mirror of the criteria table rather than an independent signal.

The distinction is worth stating because it is the one that would let a future review quietly launder
a criterion failure into "a known issue we accepted".
