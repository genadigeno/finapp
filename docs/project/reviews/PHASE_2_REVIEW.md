# Phase 2 Review — KYC/KYB and Consent

**Conducted:** 2026-09-13 (`P2-DOC-001`)
**Against:** [`PHASE_GATES.md`](../PHASE_GATES.md) §3 (universal exit gate), §4 (review areas),
§5 Phase 2, and [`DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md)
**Phase status at review:** `IN_PROGRESS` → `IN_REVIEW`

---

## The verdict in one table

| | Outcome |
|---|---|
| Review areas (8) | **7 `PASS`, 1 `NOT APPLICABLE`** — area 2 has no subject and says so |
| Universal exit criteria (12) | **12 `PASS`** |
| Financial supplement (F1–F8) | **Not applicable** — Phase 2 is not a money-affecting phase (`PHASE_GATES.md` §3) |
| Phase 2-specific criteria (6) | **6 `PASS`** |
| *"Would this remain correct with 10 concurrent instances?"* | **`PASS`** — §Area 3a |
| **Verdict** | **Phase 2 `COMPLETE` (2026-09-13)** |

**Three criteria were not passing when the review opened**, and all three were closed by the
review rather than waived. **One of them was not visible to anybody until the status was
flipped** — see §The flip found an invariant nobody had counted, which is the most
important thing this review produced.

- **Criterion 3** required a mutation-register row for every in-scope invariant, and **two were
  missing**. `INV-KYC-06`'s was deferred *in writing* by `P2-TST-001` — *"its demonstrations
  exist from `P2-TSK-008`; the row lands with the exit review"* — so landing it was this
  review's declared scope. **`INV-HIST-02`'s was missed by everyone**, and only the flip
  revealed it. Both were **performed rather than inferred**, which is the `P0-TSK-038` review's
  own finding applied to the last rows of the set.
- **Criterion 8** found **four** drifts: two in `PHASE_2_PLAN.md` §11, one of them a promise
  the platform deliberately refuses to make; a third produced **by this review's own ADR
  acceptance**, because the ADR index keeps a second copy of every status; and a fourth in
  `DECISIONS.md`, which indexed ADR-0001…0034 and **none of Phase 2's four**. All corrected.

Everything else held on first assessment.

---

## The flip found an invariant nobody had counted

**Phase 2 has eleven invariants, not ten.** Every document that counted them —
`PHASE_2_PLAN.md`, the Phase 1 → 2 transition, `CURRENT_STATE.md`, and this review's own
first draft — counted the two groups the transition *created* (`INV-KYC-01`…`06`,
`INV-CNS-01`…`04`) and stopped. **`INV-HIST-02` — external evidence is retained verbatim
— is marked `Phase: 2 (screening), 5 (providers), 8 (files)` in the catalogue**, so it has
belonged to Phase 2 since the transition wrote it, and it sits in neither group.

Nothing found it for four days. The plan did not, the transition did not, the eighteen tasks
that ran mutation sweeps did not, and this review's area-6 assessment did not — it read the
two groups, counted nine rows against ten invariants, and concluded the set was complete but
for `INV-KYC-06`.

**What found it was the act of recording the phase `COMPLETE`.** `MutationDemonstrationTest`
derives its demanded set from the *catalogue* rather than from any phase document, and it keys
on phases recorded `COMPLETE`. The moment the status flipped, the battery failed naming exactly
one missing element:

```
but could not find the following element(s):
  ["INV-HIST-02"]
```

This is **`P1-TSK-024`'s finding, repeating**. That task extended the register guard to Phase 1
and recorded: *"there are nine Phase 1 invariants, because `INV-AUD-03` is `Phase: 1 onward` and
is not in the `INV-IDN` group at all — a guard extended only to `INV-IDN-*` would have
missed it."* The same shape, one phase later: **a phase's invariants are what the catalogue says
they are, not what the phase's own plan remembers creating.** A group is a convenience for
readers; it is not the set.

Three things are worth separating out.

- **The property was never unprotected.** Evidence has been retained verbatim since
  `P2-TSK-009`, the append-only grant has been at `DB-PRIVILEGE` since the same migration, and
  both owning tasks caught the evidence-dropped mutation in their own sweeps. What was missing
  was the *record* that the test has teeth — which is precisely the gap the register exists
  to close, because an invariant with a test and no demonstration is indistinguishable from one
  whose test cannot fail.
- **The row was landed and its demonstration performed**, not inferred: dropping the evidence
  append from the run path is caught by *"a clean run takes the case to APPROVED with retained
  evidence"*.
- **The guard is the only control that could have caught this, and it did.** No human count
  would have: every prose summary of the phase, including two written in the last hour of it,
  says "ten".

### The order of operations is what made this visible, and it was deliberate

Had the status been flipped after the final battery instead of before it, the phase would have
been recorded `COMPLETE` on a build that was about to fail, and the failure would have surfaced
at the *next* task — in Phase 3, attributed to whatever touched the tree first. The design
for this review specified **land the row → flip → re-run**, on the reasoning that
recording a phase `COMPLETE` changes what the build demands. That reasoning was correct for a
reason it did not anticipate.

---

## 1. Domain correctness — `PASS`

The implemented concepts match [`GLOSSARY.md`](../../domain/GLOSSARY.md) and
[`PHASE_2_PLAN.md`](../PHASE_2_PLAN.md) §4, and the two places the domain is easiest to collapse
are held apart mechanically:

- **KYB is not "a KYC case with a flag set"** — the glossary's own words. What makes it a
  different thing is the `beneficial_owner` graph, and `kyc_case.case_kind` is **unwritable at
  `DB-PRIVILEGE`**: `V008` revokes the table-wide `UPDATE` and re-grants exactly
  `(status, status_changed_at)`, because a `KYB → KYC` flip is the one write that would disarm
  the ownership gate silently.
- **A provider verdict is not a decision** (`INV-KYC-01`). `VerificationCheck` normalises a
  provider answer into *our* vocabulary; `ChecksAssessment` reads the whole case; `KycDecision`
  is a separate recorded act. No branch anywhere maps a verdict onto a case status.
- **Consent is neither authentication nor authorization** (`INV-IDN-04`). The gate is a
  precondition queried per decision, and it lives in its own module and its own table.
- **`customer.status` is a projection, never an authority** (`INV-KYC-05`, ADR-0035). The
  mapping lives in `app` because `kyc` cannot see `party`, and `PartyModuleIsolationTest` keeps
  it that way.

One modelling decision is worth recording as correct rather than merely present:
`INDETERMINATE` is **terminal for a check**, with resolution being a *new* check (ADR-0038). A
check therefore never flaps, and the evidence of a failed attempt stays true.

## 2. Financial correctness — `NOT APPLICABLE`

The area asks for one real posting walked end to end: economic event → domain operation →
financial transaction → journal entry → lines → balances. **Phase 2 creates no posting.** There
is no ledger, no account, no money, and the plan says so in its own scope section: *"Still no
money, no account, no ledger."*

Reporting a pass here would be reporting on something that does not exist. Phase 0's and Phase
1's reviews recorded the same absence for the same reason, and the reason is that a reader
comparing review records must be able to tell *assessed and clean* from *had no subject*.

What can be checked, and is: `INV-MON-01` still holds statically across the two new modules —
`P2-TSK-003` proved it by planting a `double` in each, and the derived-coverage sweep picks up
a module the day it appears rather than when somebody remembers.

## 3. Boundary integrity — `PASS`

No module reached into another's state. Four isolation tests now exist —
`KycModuleIsolationTest`, `ConsentModuleIsolationTest`, `PartyModuleIsolationTest`,
`IdentityModuleIsolationTest` — and each forbids **every** sibling plus `app`.

That last part is a repair this phase made rather than inherited: `P2-TSK-003` found the Phase 1
tests forbidding only each other, so from the moment a third business module existed, `party`
could have grown a compile-time dependency on `kyc` with nothing failing. **That edge matters
most in exactly that direction**, because `INV-KYC-05` makes customer status a projection of the
KYC decision and a `party → kyc` dependency is the first step toward computing it locally.

Three cross-context questions were answered with **ports rather than dependencies**, which is
the pattern this phase established and is worth naming:

| Question | Port | Implemented in |
|---|---|---|
| Is this customer a person or an organisation? | `CaseKindResolver` | `app`, over `PartyStore` |
| May this party's case be opened? | `CaseOpeningConsent` | `app`, over `PartyStore` + `ConsentGate` |
| What does this decision do to the customer? | (no port — `app` owns the mapping) | `DecisionRecording.project` |

### 3a. The distributed-systems question — `PASS`

`CLAUDE.md`'s mandatory rule and the completion loop both require an explicit answer, where
`FAIL` or `UNKNOWN` means the gate does not pass.

**Answer: `PASS`.** Every contended decision in the phase is arbitrated by PostgreSQL, and each
has a test that races real connections rather than mocking the contention:

| Contended thing | Arbiter | Proven by |
|---|---|---|
| One open case per customer | Partial unique index over non-terminal states | `KycCaseDatabaseTest#tenConcurrentOpensProduceOneCase` — nine losers **converged**, not errored |
| One question per check type | Partial in-flight index + savepoint | `VerificationRunDatabaseTest#tenInstancesProduceOneRunsWorthOfEffects` (`requestCount == checkCount`) |
| One effect per callback delivery | Inbox primary key **and** conditional completion | `ProviderCallbackDatabaseTest#tenConcurrentDeliveriesProduceOneEffect` |
| One resolution per review task | Conditional `UPDATE`, row count is the outcome | `ReviewDatabaseTest#tenConcurrentResolutionsProduceOne` — one 204, nine 409s, one audit record |
| One decision per case | Conditional `READY_FOR_DECISION → terminal` | `DecisionDatabaseTest#tenConcurrentDecisionsProduceOne` |
| One owner row per declaration | Unique index + case-row lock | `KybCaseDatabaseTest#tenInstancesDeclareOneOwnerOnce` |
| Readiness vs. a racing declaration | `SELECT … FOR UPDATE` then act | `KybCaseDatabaseTest#aDeclarationRacingReadinessIsSeen` — the mover **observed blocked** in `pg_stat_activity`, both interleavings |
| Consent basis across instances | Authoritative read per decision | `ConsentWithdrawalBlocksTheCapabilityDatabaseTest` — one gate **per simulated instance**, straddling the commit |

**Nothing in the phase coordinates in process memory.** `NoProcessLocalConsentStateTest` is the
detector for the newest such surface, and its own limit is recorded rather than implied: a bare
`Map<String, Boolean>` names no consent type and is invisible to it — which is why the
behavioural cross-instance race is the load-bearing control there and the field detector is a
second one, blind in a different direction.

The one property that needed more than a predicate is recorded in area 4.

## 4. Failure behaviour — `PASS`

All twelve scenarios in `PHASE_2_PLAN.md` §8 have a test or a documented accepted rationale
(criterion 4). Two are traced through the code here, as the area requires.

| § | Scenario | Covered by |
|---|---|---|
| 1 | Provider timeout → no decision by assumption | `VerificationRunDatabaseTest#aTimeoutDoesNotDecide` |
| 2 | Provider answers after our timeout | `ProviderCallbackDatabaseTest#aLateCallbackIsEvidenceOnly` |
| 3 | Duplicate provider callback → one advance | `#aDuplicatedDeliveryHasOneEffect` |
| 4 | Upload succeeds, case update fails | `DocumentUploadDatabaseTest` (one transaction; the atomicity probes in `CustomerProjectionDatabaseTest#anInjectedFailureLeavesNothing`) |
| 5 | Two instances process one callback | `#tenConcurrentDeliveriesProduceOneEffect` |
| 6 | Concurrent reviewer resolutions | `ReviewDatabaseTest#tenConcurrentResolutionsProduceOne` |
| 7 | Concurrent decision and new evidence | `DecisionDatabaseTest#aSecondDecisionLoses`, `#anUnreviewedCaseIsNotReady`; evidence references are a join table, so evidence appended after the decision is **mechanically** outside what it rested on |
| 8 | Withdrawal racing a gated operation | `ConsentWithdrawalBlocksTheCapabilityDatabaseTest` — traced below |
| 9 | Screening list updated after a decision | **Accepted rationale**: the decision is immutable and stands; rescreening is Phase 13's, and the seam (a re-runnable check on a case) exists |
| 10 | Crash between decision and projection | **Impossible by construction** (ADR-0035, same transaction), asserted by `#aKilledBackendLeavesNothing` |
| 11 | Duplicate case-open submissions | `KycCaseDatabaseTest#tenConcurrentOpensProduceOneCase`; `KycCaseEndpointDatabaseTest` over HTTP |
| 12 | Broker adapter down | Outbox holds; `finapp.outbox.pending` is live and `OutboxMetricsDatabaseTest` covers it |

### A crash mid-call, traced

Dispatch commits **before** the provider is asked. That ordering is the whole design: a crash
during the HTTP call leaves a visible `DISPATCHED` row rather than an unknown (`INV-LIFE-03`),
and the provider *received* that request. Before `P2-TSK-011` this was a recorded remainder
needing a sweeper. It is now healed by the provider's own callback: the delivery completes the
stranded check, and — because the delivery commits before the case is assessed — a **duplicate**
delivery re-assesses too, healing a crash that landed between a delivery's commit and its
assessment.

What is deliberately *not* claimed: the two commits (database effect, broker offset) are not
atomic, and the design says so. Every failure between them resolves as a redelivery into the
dedupe, which is the safe direction.

### A withdrawal racing a gated open, traced

`ConsentGate.permits` reads the history on the caller's unit of work, per decision, with no
cache anywhere. The demonstration straddles the commit boundary, which is the invariant's own
wording — *"from the transaction that records a withdrawal"*: while B's withdrawal is
uncommitted, A still permits (an instance refusing there would be reading dirty); on A's very
next decision after the commit, A refuses. No sleep, no polling, no timing luck.

**And the eager door asks too.** The registration consumer opens a case for a person who cannot
yet hold a grant — a grant needs a session, a session needs the registration the event
announces — so a gate with an ungated second door would not be a gate. A refusal there is a
**skip, never a stall**: acknowledged, logged with correlation, nothing written, because the
platform's own correct decision must not get the poison-record treatment.

## 5. Security — `PASS`

Every privileged action in the phase is authorised and audited. Enumerated:

| Action | Authorisation | Audit record | Negative test |
|---|---|---|---|
| Read a case file | `@RequiresPermission(KYC_REVIEW)` | `kyc.CaseRead` | `ReviewDatabaseTest#bothEndpointsRefuseASessionWithNoRole` |
| Resolve a review task | `@RequiresPermission(KYC_REVIEW)` + reason required | `kyc.ReviewResolved` | same, plus `#aMissingReasonIsRefusedAtTheBoundary` |
| Record a decision | `@RequiresPermission(KYC_REVIEW)` + reason required | `kyc.DecisionRecorded` | `DecisionDatabaseTest#aSessionWithNoRoleIsRefused` |
| Read document content | One audited path, no HTTP caller for customers by design | `kyc.DocumentContentRead` | `DocumentUploadDatabaseTest#theReadPathDecryptsVerifiesAndAudits` |
| Open / read own case | Session; **ownership by absence of a parameter** | `kyc.CaseOpened` | `KycCaseEndpointDatabaseTest#bothVerbsRefuseWithoutASession` |
| Declare a beneficial owner | Session + the registrant chain | `kyc.OwnerDeclared` | `KybEndpointDatabaseTest` (stranger → 404, nothing written) |
| Grant / withdraw consent | Session | `consent.ConsentGranted` / `ConsentWithdrawn` | `ConsentEndpointDatabaseTest#allThreeRefuseWithoutASession` |
| Provider callback | HMAC-SHA256 over the raw bytes, constant-time | (the check outcome's own record) | `ProviderCallbackDatabaseTest#anUnsignedDeliveryWritesNothing` |

**`AuditCompletenessTest`'s `NOT_YET_EMITTED` set holds exactly the three `outbox.*` actions**,
which are Phase 15 debt. Every one of the phase's nine `kyc.*`/`consent.*` actions is emitted —
counted, not quoted.

Three security decisions deserve recording because the safe-looking alternative was worse:

- **The callback signature is the control, and a checksum was rejected by name.** This is the
  input that clears sanctions screenings, so a tag anyone can compute would be an open door to
  check-outcome forgery. Verified before parsing, before any read, in constant time, against
  RFC 4231's own vectors. Its limits are written down rather than implied: one static key, no
  rotation, no per-provider keys, and **no replay window at the signature layer, deliberately** —
  a replayed callback is byte-identical, so it is exactly the duplicate the inbox absorbs.
- **Tipping-off is a shaping control with one definition.** A case in `IN_REVIEW` and one in
  `CHECKS_IN_PROGRESS` answer **byte-identically** to the customer, and the exhaustive `switch`
  in `CustomerFacingCaseStatus` makes a new case status a compile failure until somebody decides
  which side of the disclosure line it sits on.
- **One error code for three consent causes, deliberately.** No history, a withdrawal, and a
  grant lapsed by a re-consent-demanding version are indistinguishable to every caller
  (`INV-CNS-01`), proven at the surface as an **equality between the causes** rather than as
  three assertions against remembered expectations.

## 6. Test quality — `PASS`

The area's question is whether the tests fail when the invariant is deliberately broken. For
Phase 2 the answer is recorded in [`MUTATION_TESTING.md`](../MUTATION_TESTING.md) §2, which now
carries **all eleven** of the phase's rows — `INV-KYC-01`…`06`, `INV-CNS-01`…`04`
and `INV-HIST-02` — each naming its tests by `Class#method` and checked on every build.
Two of the eleven were landed by this review, and **both were performed rather than
inferred**.

**`INV-KYC-06`.** Two mutations, both caught:

| Mutation | Result |
|---|---|
| The audit write dropped from `DocumentAccess.read` — content delivered with no trail of who looked | **Caught by the intended assertion**: *"the read path decrypts, verifies the checksum, and audits who looked"* |
| Content persisted in the clear — the ciphertext column holds the plaintext | **Caught** by the `information_schema` column sweep, among four others (content stored in the clear also fails to decrypt on the way back) |

**`INV-HIST-02`.** One mutation — the evidence append dropped from the run path, so a
provider's answer is normalised into an outcome and the bytes that produced it are gone —
**caught by the intended assertion**: *"a clean run takes the case to APPROVED with retained
evidence"*. The row is the review's own finding; see §The flip found an invariant nobody had
counted.

Performing both rather than reasoning to them is the `P0-TSK-038` review's finding applied to
the last rows of the set: it found one row whose *observed* column had been inferred, and closed
it by performing the demonstration. A register of demonstrations is exactly where `DOD-DOC`'s
ban on aspirational statements bites hardest.

The phase's mutation totals, **counted from the change log rather than estimated**: **135
mutations, probes and demonstrations** across the twenty-one Phase 2 tasks that performed them,
plus the two this review performed. (`P2-TST-001` states none because its own description was to
*record* demonstrations its owning tasks had already performed — the audit found none missing.)

**Three survived, and each produced a finding rather than a shrug**, which is the more useful
outcome and is why the count of survivors is worth stating at all:

- **`P2-TSK-007`** — removing the converged-guard survived the wire-duplicate test, because an
  exact duplicate never reaches the handler at all: the **inbox** absorbs it by `eventId`, and
  the guard's real subject is a *distinct* event converging on an existing case. That path is
  now driven end to end and the mutation is caught by it.
- **`P2-TSK-015`** — the reviewer-door re-route survived, because both re-route tests drove the
  *assess* door. The fix was a **move rather than a new test for the controller**: a consequence
  living only on the HTTP boundary is one a second caller silently loses.
- **`P2-TSK-016`** — the at-least-one-owner check survived, because the no-500 sweep's shape
  named an *unknown* party, which eligibility refused before the aggregate was ever constructed.
  Re-aimed at an eligible owner, it is caught.

A fourth is recorded as **correctly surviving** rather than as a miss: `P2-TST-002`'s
refusal-only cache survives the cross-instance race, and should — withdrawal still takes effect,
and `INV-CNS-03` says nothing about a stale *refusal*. The symmetric defect is real and is
caught at the other door.

## 7. Documentation drift — `PARTIAL` → corrected, four findings

Hand-diffing the plan against the implementation — the method that found the real defects in
both prior reviews — produced two findings in `PHASE_2_PLAN.md` §11. Two more surfaced
from the review's own acceptance decision, and **both are the same mechanism**: a fact about an
ADR written down in more than one place, with nothing reconciling the copies.

### The milestone table promises exactly-once delivery, which the platform refuses to promise

M2.1's acceptance reads *"an outbox event reaches a real consumer through Kafka **exactly once
per fact**"*. `P2-TSK-001`'s design corrected that promise at the time — the `EventPublisher`
port's own javadoc refuses it, because **an adapter claiming exactly-once invites consumers to
skip their inbox** — and the crash test *demonstrates* the duplicate, same `finapp.eventId` on
both copies, which is precisely what makes the inbox able to absorb it (`INV-IDEM-04`).

The correction landed in `BACKLOG.md` and in `CURRENT_STATE.md`, which records it explicitly:
*"the effect is exactly-once, the delivery is at-least-once."* **The plan was not corrected**,
so the phase's own planning document carried a delivery guarantee the architecture deliberately
does not make. Corrected here, with the provenance stated.

### The milestone table never scheduled its own observability section

§11 names M2.6 *"Phase review"* with contents `P2-DOC-001` — one item. M2.6 in fact holds
`P2-TSK-020` as well, the six meters that §10 of the same document specifies. The plan
specified the observability and then omitted it from the only milestone that could deliver it.
Corrected to *"Observability and the gate"*, matching `BACKLOG.md`'s epic and `CURRENT_STATE.md`.

### The ADR index carried its own copy of four statuses, and it went stale on the spot

Moving ADR-0035…0038 to `Accepted` in their own files left
[`docs/adr/README.md`](../../adr/README.md) still listing all four as `Proposed`, because the
index keeps a status column of its own. **This is the exact governance decay the Phase 1 → 2
transition had to repair** — its findings included *"ADR index rows stale at `Proposed`"* — and
it recurred within one phase, in the same file, under the same mechanism: a fact written down
twice with nothing reconciling the copies.

Corrected. Recorded here rather than fixed quietly, because the fix is the smaller half: **no
guard covers this**, and the next phase's acceptance decision will reintroduce it unless
something does. The repository closes this class by derivation everywhere it has met it before
(CI's task list, a coverage guard, a privilege check), and an index deriving its status column
from the ADR files is the same shape of answer. Carried to the Phase 2 → 3 transition rather
than built here, on `EXECUTION_PROTOCOL.md` rule 4: a review corrects the documents it audits;
it does not grow machinery mid-gate.

### `DECISIONS.md` indexes every phase's decisions except this one's

[`DECISIONS.md`](../DECISIONS.md) is the human-readable index of architectural decisions, and it
carries an entry for ADR-0001 through ADR-0034 — every decision of Phases 0 and 1, including
all six the Phase 0 → 1 transition took. **It carries none of Phase 2's four.** A reader
going to the document whose stated job is to answer *"what has this platform decided?"* would
have found the ledger, the modular monolith, sessions, authorization and credential storage, and
no mention that the platform had decided who owns a verification decision, how evidence is
stored, or that consent is an append-only history.

Nothing catches this either — same gap as the index above, and the same shape as the stale
status column: the decision is written in the ADR, again in the ADR index, and again here, with
no reconciliation between the three. Added, with the four entries written in the document's own
voice rather than as links.

Both of these belong to the same recommendation, which is carried rather than built: **the ADR
index's status column and `DECISIONS.md`'s coverage should be derived from the ADR files**, the
way this repository has closed every other stale-list defect it has met. A review corrects the
documents it audits; it does not grow machinery mid-gate (`EXECUTION_PROTOCOL.md` rule 4).

### A counting discrepancy, recorded rather than silently resolved

`CURRENT_STATE.md`'s M2.3 block says **"3 of 3"**, counting the milestone's numbered tasks;
`BACKLOG.md` groups **four** items under the M2.3 epic, `P2-TST-001` among them. Neither is
wrong about anything real — the totals reconcile at 23 either way — but two documents counting
one milestone differently is the drift class `P1-DOC-001` found in its own tables. The
milestone block now states which convention it uses.

**Everything else checked clean**, and was checked rather than assumed: §7's API table matches
the published contract exactly (13 operations, 11 paths, counted from `openapi.json`), §4's
domain model matches the aggregates as built, §6's security model matches the enforcement, §8's
twelve scenarios are the twelve assessed above, and §10's six meters are the six registered.

## 8. Architectural debt — `PASS`

Twelve rows are open in `CURRENT_STATE.md` §Known Architectural Debt. **None is
financial-correctness debt**, and none carries `critical` or `high` severity — criterion 11,
checked against the table rather than remembered.

Phase 2 moved four of them and added none:

| Row | Movement |
|---|---|
| Broker adapter behind `EventPublisher` | **Closed** by `P2-TSK-001` |
| Relay metrics | **Paid in full** — `finapp.outbox.publication` by outcome, eager |
| Inbox metrics | **Paid in full** — its trigger was *"the first live consumer"*, which `P2-TSK-002` was |
| Kafka plaintext | **Narrowed** — the first client arrived *with* its guard; the row is now Redis plus the deployed TLS posture |

One row's **trigger fired** and is recorded as owed rather than quietly re-deferred: the
per-credential loopback confinement is now written four times (`DatabaseCredentialGuard`,
`MfaKey`, `DocumentKey`, `CallbackKey`), which is exactly the count at which copies start to
drift. The generalisation is owed as its own piece of work; folding it into a callback task
would have been `EXECUTION_PROTOCOL.md` rule 4's smuggled refactor.

---

## The twelve universal exit criteria

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Required functionality exists | **`PASS`** | Every `PHASE_2_PLAN.md` §1 bullet delivered and exercisable end to end over real HTTP: 13 operations, each with a database test driving it |
| 2 | Architectural boundaries respected | **`PASS`** | Four isolation tests, each forbidding every sibling; three cross-context questions answered by ports; no undeclared dependency (verification metadata + lockfiles enforced) |
| 3 | Required invariants tested | **`PASS`** | All **eleven** Phase 2 invariants carry a register row — the eleventh, `INV-HIST-02`, found by the flip itself; both missing rows landed and **performed** |
| 4 | Failure cases handled | **`PASS`** | All twelve §8 scenarios mapped above; two are accepted rationales with owners, ten have tests |
| 5 | Security requirements implemented | **`PASS`** | Area 5's table: eight privileged actions, each authorised, audited and negatively tested; 11 new columns classified at their ceiling |
| 6 | Observability exists | **`PASS`** | Six planned meters registered **eagerly and unconditionally** (`P2-TSK-020`); dashboard row whose every query resolves against a live scrape |
| 7 | Integration tests pass | **`PASS`** | `build databaseTest kafkaTest` green: **1025 hermetic, 584 database, 14 kafka**, counted from the result XML |
| 8 | Documentation reflects reality | **`PASS`**, after the four corrections in area 7 |
| 9 | `CURRENT_STATE.md` updated | **`PASS`** | Updated by this review with the phase's outcome and the next phase's position |
| 10 | Relevant ADRs exist and are `Accepted` | **`PASS`** | ADR-0035…0038 moved to `Accepted` by this review (below) |
| 11 | No unresolved critical issues | **`PASS`** | No `critical` or `high` item in the debt table; Blockers: none |
| 12 | Formal phase review conducted | **`PASS`** | This record |

**The financial supplement (F1–F8) does not apply.** `PHASE_GATES.md` §3 names the phases it
binds — 3, 4, 5, 6, 7, 8, 9, 11, 12, 14 — and Phase 2 is not among them, because it moves no
money. Stated rather than skipped, so a reader can tell an inapplicable criterion from an
unassessed one.

## The six Phase 2-specific criteria

| Criterion | Verdict | Evidence |
|---|---|---|
| KYC state machine rejects every invalid transition (exhaustively tested) | **`PASS`** | `KycCaseLifecycleTest#everyTransitionIsEnforced` — the cross-product **derived from the machine**, not a list somebody wrote; both terminals swept separately |
| Provider verdict stored as evidence, never itself the decision | **`PASS`** | `INV-KYC-01`'s row; evidence retained verbatim (`INV-HIST-02`), the mapping's default branch `INDETERMINATE` and never success |
| Duplicate provider callback produces no duplicate decision | **`PASS`** | `#aDuplicatedDeliveryHasOneEffect`, `#tenConcurrentDeliveriesProduceOneEffect`; two dedupe layers blind in different directions, designed in rather than found |
| Consent withdrawal demonstrably blocks the dependent capability | **`PASS`** | `ConsentWithdrawalBlocksTheCapabilityDatabaseTest` — the **real consumer**, wired as the application wires it, opening nothing; with a positive control |
| Documents access-controlled, encrypted, every access audited | **`PASS`** | `INV-KYC-06`'s row, performed by this review; AES-256-GCM under an externalised key; one audited read path |
| Reviewer decisions require elevated authorization, a reason code, and are audited | **`PASS`** | `@RequiresPermission(KYC_REVIEW)` on every reviewer surface; reason bounded in three reconciled places; `kyc.ReviewResolved` and `kyc.DecisionRecorded` |

---

## Decisions taken by this review

### ADR-0035 … ADR-0038 moved to `Accepted`

Criterion 10 requires every architectural decision taken during the phase to be recorded **and
`Accepted`**. The four are implemented, tested and load-bearing:

- **ADR-0035** — KYC owns the verification decision; Party projects it. Enforced by module
  isolation and the same-transaction projection.
- **ADR-0036** — evidence and document content verbatim in PostgreSQL behind a port, encrypted;
  object storage deferred with a named trigger.
- **ADR-0037** — consent is an append-only history, current basis derived. Enforced at
  `DB-PRIVILEGE`.
- **ADR-0038** — a provider verdict is evidence; a hit is resolved by a person, never by
  silence. Enforced by the assessment's `HIT`-first ordering and the absence of any edge from
  `IN_REVIEW` to a terminal.

This follows Phase 0's and Phase 1's recorded reasoning: **criterion 10 is a precondition of the
gate rather than a reward for passing it.**

### `INV-KYC-06`'s register row landed

With its demonstrations performed, not inferred — see area 6.

### Three document corrections

`PHASE_2_PLAN.md` §11's exactly-once promise and its M2.6 contents, and the ADR index's
stale status column — see area 7.

### One backlog item created

**The per-credential loopback confinement's generalisation** is owed as its own work: its
trigger fired at the fourth credential during `P2-TSK-011`, and the row has been carrying
*"due as its own piece of work"* since. It is not created as a Phase 2 item, because Phase 2 is
closing; it belongs to whichever phase schedules it, and Phase 5 is its recorded owner.

---

## What the phase actually produced

Counted for this review, never quoted — `P1-DOC-001`'s own finding was that three of its
numbers were wrong for having been inherited, and its recount found a backlog defect inside its
own table.

| | |
|---|---|
| Modules | **2 new** (`kyc`, `consent`), each owning a schema |
| Tables | **11** (`kyc` 8, `consent` 2, `party` 1) |
| Migrations | **13** during the phase (`kyc` 8, `consent` 2, `party` 2, `identity` 1) |
| Endpoints | **13 operations across 11 paths**; 31 operations platform-wide |
| Auditable actions | **9** (`kyc` 7, `consent` 2), all emitted |
| Invariants | **10 new** (`INV-KYC-01…06`, `INV-CNS-01…04`), taking the platform to **82**; **11 in scope** — `INV-HIST-02` was already catalogued and belongs to the phase |
| ADRs | **4** (ADR-0035…0038), taking the platform to **38** |
| Tests | **1025 hermetic, 584 database, 14 kafka** |
| Backlog | **23 of 23** items |
| Money | **None.** By design |

**The capability, stated as a sentence:** a person registers and their case opens — eagerly if
they already hold a basis, or by their own `POST` when they act; documents are captured
encrypted and every read of content is on the record; five checks run against simulated
providers whose answers are evidence and never decisions; a hit becomes work for a person and
cannot terminate without one; an organisation's case decides only over a fully answered
ownership graph; the decision is immutable, attributable and policy-pinned, and it moves
`customer.status` in its own transaction; and none of it proceeds for a person who has not
granted a current, purpose-scoped basis — on either door.

---

## What happens next

**Phase 2 is `COMPLETE` (2026-09-13).** The next act is the **Phase 2 → Phase 3 transition**,
which is a separate piece of work and deliberately not this review's: it audits the completed
phase against the entry gate for Phase 3, establishes the ledger's plan, and takes the
irreversible decisions Phase 3 cannot start without — isolation level and locking strategy for
concurrent postings, the chart-of-accounts structure, and the balance-projection placement, all
three of which sit at the top of `CURRENT_STATE.md` §Unresolved Architectural Questions with
**High** risk attached.

Phase 3 is where money arrives. The financial supplement F1–F8 binds it, `INV-LED-*`,
`INV-BAL-*` and `INV-CON-01` become live, and `DB-PRIVILEGE` finally carries `INV-LED-03` and
`INV-HIST-01` — the mechanism for which has existed since `P0-TSK-022` and has had nothing to
protect until now.

### The flip is itself the guarded act, and that was verified rather than assumed

Since the Phase 1 → 2 transition's guard redesign, recording a phase `COMPLETE` **changes what
the build demands**: `MutationDemonstrationTest` begins requiring a register row for every one
of the phase's invariants, and `PlannedMetersExistTest` begins deriving the phase's planned
meter table. So the order here was **land the row → flip the status → run the full battery**,
and the battery was re-run after the flip rather than before it.

A review that flipped the status without re-running the build would have been asserting a
property the build was about to refuse — which is the `P0-TSK-023` class, arriving at the gate
built to catch it. `P2-TSK-020` was scheduled immediately before this review precisely so the
meter half would be a non-event; this review confirmed that rather than trusting it.
