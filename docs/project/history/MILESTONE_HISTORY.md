# Milestone History

Closed milestone records for Phases 0-4.

**Archive.** These records were moved verbatim out of
[`CURRENT_STATE.md`](../CURRENT_STATE.md) on 2026-09-20 so that the canonical description of
where the project *is* stops carrying the project's entire narrative of where it *has been*.
Nothing was edited, summarised or dropped in the move. Section headings below read as they did
when they were written.

Current state: [`CURRENT_STATE.md`](../CURRENT_STATE.md) ·
Authoritative backlog: [`BACKLOG.md`](../BACKLOG.md)

---

## Current Milestone

**M4.8 — The gate.** `P4-DOC-001`; **CLOSED 2026-09-19, 1 of 1 — and with it the
phase closes at 14 of 14.** The exit review conducted with **the review's own verdict
flipping the status** rather than the task count reaching the end of the list
([`reviews/PHASE_4_REVIEW.md`](reviews/PHASE_4_REVIEW.md)): 8 areas `PASS`, 12
universal criteria `PASS`, F1–F8 all `Met`, 16 phase-specific criteria `PASS`, and the
ten-instances question `PASS` over six contended decisions. **The post-flip battery is
green — 1157 hermetic tests, 0 failures** — and the flip surfaced nothing, because
`P4-TST-002` and `P4-TSK-011` had each pre-paid their half. Two area-7 findings, both
in the record rather than the code, both corrected in the review.

### M4.7 — Observability and demonstration — CLOSED

**M4.7 — Observability and demonstration.** `P4-TSK-011`, `P4-TST-001`,
`P4-TST-002`; **CLOSED 2026-09-19, 3 of 3** — the four planned meters and the
dashboard row (`P4-TSK-011`), conservation under sustained concurrent movement
(`P4-TST-001`, which found and fixed a real deadlock on the way), and the
`Phase: 4` register rows (`P4-TST-002`, which found the caller had no
concurrent-duplicate test and performed it). The milestone's stated acceptance
holds by demonstration on all three.

### M4.6 — The seams — CLOSED

**M4.6 — The seams.** `P4-TSK-010`; **CLOSED 2026-09-19, 1 of 1** — the limit and risk
seams hardened into contracts Phase 13 can honour: generic over the unit of work,
verdict-returning (`SeamVerdict`), with `LIMIT_REFUSED`/`RISK_REFUSED` reserved and
produced by the execution's mapping arms, `V004` widening the reason `CHECK`, and the
in-lock contract **observed** by a decorator probe's `FOR UPDATE NOWAIT` rather than
stated. The milestone's stated acceptance holds by demonstration: removing either
parameter fails compilation (performed at the composition root and restored), the
defaults are exercised on every transfer test (the refusing-default mutation fails the
suite), the seams' size is the no-Phase-13-logic assertion, and a seam refusal is a
committed replayable `FAILED` with nothing posted.

### M4.5 — Reversal — CLOSED

**M4.5 — Reversal.** `P4-TSK-009`; **CLOSED 2026-09-19, 1 of 1** — the privileged,
reasoned correction: `TRANSFER_REVERSE` on `LEDGER_OPERATOR` (no migration — a
permission is never a column, ADR-0031; the scope's `V015` was a drift, corrected
on being met), `POST /v1/transfers/{id}/reversal` with the reason required, one
transaction moving `COMPLETED → REVERSED` under the transfer row's `FOR UPDATE`
and posting the referencing entry through `ReversalService`. The milestone's
stated acceptance **holds by demonstration**: the original entry byte-identical
as PostgreSQL's own renderings, both balances restored exactly, ten concurrent
reversals producing one entry and one move counted in the tables, the loser of
the deterministic interleaving observed Lock-waiting and refused with nothing
posted, and the permissionless session refused with nothing written.

### M4.4 — Over HTTP — CLOSED

**M4.4 — Over HTTP.** `P4-TSK-008`; **CLOSED 2026-09-18, 1 of 1** — the transfer
surface: `POST /v1/transfers` answering `201` with the judgement in the body,
status and list under ownership, and the chain transfer → entry id → statement
line walked by identifier from **both** parties' sides. The milestone's stated
acceptance holds by demonstration — with the accept's *"a verified customer with
two accounts"* corrected on the record: `ProductType` has one value and one
agreement per customer per product type is live (`P3-TSK-012`), so a second open
**converges onto the first** and the demonstration is two verified customers,
which exercises the same three surfaces and more. **M4.3's fourth clause is paid**:
a removed beneficiary refuses new transfers — one byte-identical
`transfers.UnknownDestination` across unknown, a stranger's, malformed and
removed, with nothing written, counted in the table.

### M4.3 — Beneficiaries — CLOSED

**M4.3 — Beneficiaries.** `P4-TSK-006`, `P4-TSK-007`; **CLOSED 2026-09-18, 2 of 2** —
the saved destination with its one-live (party, destination) slot and every-writer
terminal freeze, carried over HTTP under the conditional `MULTI_FACTOR` step-up (the
transition's structural ruling). The milestone's stated acceptance — *a saved
destination is created under the second factor, listed, removed — and a removed one
refuses new transfers* — **holds by demonstration** on its first three clauses: the
enrolled identity refused at `PASSWORD` with nothing written and creating at
`MULTI_FACTOR` after proving the factor over the real challenge endpoint; listed (live
own rows only); removed (converging 204, the row surviving as evidence). The fourth
clause — *a removed one refuses new transfers* — was `P4-TSK-008`'s to demonstrate,
because "a transfer names a beneficiary" is that surface's resolution step (the body's
`beneficiaryId` arm); recorded here rather than glossed, **and paid on schedule
(2026-09-18)**: the removed entry is one byte-identical `transfers.UnknownDestination`
with nothing written, counted in the table.

### M4.2 — The movement exists — CLOSED

**M4.2 — The movement exists.** `P4-TSK-003` … `P4-TSK-005`; **CLOSED 2026-09-17, 3 of 3**
— the machine pinned in code, generated into `V002`'s `CHECK`s and every-writer trigger,
and driven by the execution command in **one local transaction** under the source-account
lock. The milestone's stated acceptance — *ten instances draining one account: exactly the
affordable transfers succeed, total value conserved, counted in the table* — **holds by
demonstration**: ten instances draining 1000-affordable in 300s accept exactly **3
`COMPLETED` with 7 `FAILED(INSUFFICIENT_FUNDS)`**, the source settled at 100 and never
negative, the pair summing to the funded 1000 to the minor unit (`INV-CON-02`), counted in
the tables and never inferred from return values.

### M4.1 — Foundations — CLOSED

**M4.1 — Foundations.** `P4-TSK-001`, `P4-TSK-002`; **CLOSED 2026-09-17, 2 of 2** — the
`transfers` module and its privilege floor (the fourth performance of the established
shape, with the build-graph edges that make the phase's top risk — the transfer module
writing postings — structurally unreachable before any transfer code exists), and the ADR
governance registers build-reconciled: the twice-carried second-copy item paid as work, so
the decay `P2-DOC-001` and `P3-DOC-001` each found by hand is now a build failure that
names its ADR. The milestone's stated acceptance — *the module and privilege floor exist;
the ADR governance registers are build-reconciled* — holds by demonstration on both halves.

### Phase 3 milestones — all closed

**M3.8 — Observability and the gate.** `P3-TST-003`, `P3-TSK-020`,
`P3-TSK-021`, `P3-DOC-001`; **CLOSED 2026-09-17, 4 of 4 — and with it the
phase closes at 25 of 25.** The financial supplement F1–F8 assessed with
named tests and re-checked at the gate, the register carrying a row for
**every** `Phase: 3` invariant the catalogue names — nineteen of nineteen,
`INV-AUD-04`'s landed by `P3-TSK-021` with the four-eyes mechanism it could
not honestly precede — the six planned meters published by a freshly
started instance with the dashboard row resolving, and the exit review
conducted with **the review's own verdict flipping the status** rather than
the task count reaching the end of the list. The flip surfaced nothing,
because its one failure was predicted and pre-paid — the posture this
milestone's items existed to buy.

**M3.7 — Statements and the trial balance.** `P3-TSK-018` plus `P3-TSK-019`;
**CLOSED 2026-09-17, 2 of 2** — every figure a customer is shown traces to
journal lines (`INV-ACC-02`'s drill-down shape three phases early: opening +
lines = closing **by construction**, the closing held to an independent
recomputation), and `INV-ACC-01` is a continuously published fact:
`finapp.ledger.trial.balance` per currency — 0 verified balanced, 1 out of
balance, NaN unverifiable — with no `IN_FLIGHT` verdict because a snapshot
never contains half an entry, and **no repair path, structurally**. The
milestone's stated acceptance — *trial balance is zero per currency,
asserted by an automated job* — holds by demonstration: sweeps racing four
live posters read zero every time, and three injected imbalance shapes
(the scales probe, the cross-currency subsidy) are each flagged per
currency.

**M3.6 — Correction without mutation.** `P3-TSK-016` plus `P3-TSK-017`;
**CLOSED 2026-09-17, 2 of 2** — a mistake is corrected by a new entry, never
an edit (`INV-HIST-01`). The reversal references the original, swaps
directions, is bounded per `(account, direction)` pair by `V009`'s trigger
under the advisory lock (`INV-REV-01/02`), and leaves the original
byte-identical — asserted as PostgreSQL's own renderings. The adjustment is
the ledger's one public write: behind `LEDGER_ADJUST` at its first real
check site, reason required and bounded in three reconciled places
(`INV-REV-04`), audited as `ledger.AdjustmentPosted` naming the person and
carrying the justification — with four-eyes recorded as the debt it is,
never implied by a threshold check. The milestone's stated acceptance — *a
reversal creates a new entry referencing the original, which is
byte-identical afterwards* — holds by demonstration.

**M3.5 — Holds and available balance.** `P3-TSK-015` plus `P3-TST-002`;
**CLOSED 2026-09-17, 2 of 2** — the phase's sharpest contention point,
decided by the account row's `FOR UPDATE`: a hold cannot make available
balance negative (`INV-BAL-04`), judged from postings and standing hold rows
inside the lock, never from the projection (`INV-BAL-05`, behaviourally
proven). The milestone's stated acceptance — *a hold cannot exceed available
balance under a ten-way race; release restores availability exactly* — holds
by demonstration: ten instances placing 1000 against 3000 accept exactly
three, counted in the table, the loser observed Lock-waiting in
`pg_stat_activity`; and the register rows recording that the tests fail when
the lock is removed, when the check moves outside it, and when the decision
reads the projection are landed where the exit review will look
(`P3-TST-002`).

**M3.4 — The product exists.** `P3-TSK-011` … `P3-TSK-014`;
**CLOSED 2026-09-17, 4 of 4** — the module and its privilege floor, the gated
Customer Account, the HTTP surface whose balance says which number it is, and
the close that ends the agreement without touching a single journal row. The
milestone's stated acceptance — *a verified customer opens an account, sees
its balance, and closes it — end to end over HTTP* — holds by demonstration
in one suite: open 201, balance `"0.00"`, a real posting moving it to
`"12.50"`, emptied, `DELETE` 204, the list showing `CLOSED`, the closed
account's balance still readable, and a repeated `DELETE` converging.
*(This block read `P3-TSK-011 … P3-TSK-013; 0 of 3` until `P3-TSK-011`'s gate:
the epic holds four tasks and the acceptance's own "closes it" is
`P3-TSK-014` — the M2.3-style counting drift, corrected rather than left.)*

**M3.3 — A balance is explainable.** `P3-TSK-008` … `P3-TSK-010` plus
`P3-TST-001`; **CLOSED 2026-09-16, 4 of 4** — the derivation that defines the
balance, the transactional projection that can never be behind, the verification
whose comparison is the evidence, and the sustained demonstration. The
milestone's stated acceptance — *a balance recomputed from zero equals the
projection, under sustained concurrent posting* — holds by demonstration rather
than composition: ten instances posting continuously while the verification job
runs, every mid-storm sweep drift-free, the final row equal to the posters' own
committed tally, and a lock-then-look rebuild mid-storm losing nothing.

**M3.2 — A posting is possible and cannot be wrong.** `P3-TSK-004` … `P3-TSK-007`;
**CLOSED 2026-09-14, 4 of 4** — the entry and line aggregates whose unbalanced shapes
cannot be constructed, persistence balanced-by-constraint and immutable-by-privilege,
the idempotent posting command, and the posting permissions. The milestone's stated
acceptance holds by demonstration: an unbalanced entry is impossible at the domain
(`P3-TSK-004`'s unconstructible shapes) **and** the database (`P3-TSK-005`'s deferred
constraint triggers against raw SQL); `UPDATE`/`DELETE` denied on every column of both
journal tables, with the append-only trigger binding even the migrator; and ten
identical keys produce one effect, counted in the table (`P3-TSK-006`'s race).

**M3.1 — The chart exists.** `P3-TSK-001` … `P3-TSK-003`; **CLOSED 2026-09-13, 3 of 3** —
the module and privilege floor, the `LedgerAccount` whose classification cannot drift, the
operational chart seeded by migration. The milestone's stated acceptance — *a ledger account
is created with a type, normal balance and currency, and its classification cannot be changed
once posted to* — holds by demonstration: the freeze proven in two layers with a positive
control between them, and the chart resolving every purpose in every supported currency
before anything can post.

### Phase 2 milestones — all closed

**M2.6 — Observability and the gate.** `P2-TSK-020`, `P2-DOC-001`; **CLOSED 2026-09-13,
2 of 2** — the six planned meters, every one published by a freshly started instance with
nothing configured, and then the exit review that ruled the gate. **With it the phase closes at
23 of 23**, and the review's own verdict is what flipped the status rather than the task count
reaching the end of the list.

**M2.5 — Consent.** `P2-TSK-017` … `P2-TSK-019` plus `P2-TST-002`; **CLOSED
2026-09-13, 4 of 4.** The milestone's stated acceptance — *withdrawal demonstrably blocks the
dependent capability, across instances* — **holds at the capability, demonstrated rather than
composed**: the real case-opening consumer, wired as the application wires it, opens nothing
after a withdrawal committed on another instance. The store landed (`P2-TSK-017`), spoke HTTP
(`P2-TSK-018`), became a gate reading authoritative state per decision (`P2-TSK-019`), and
`P2-TST-002` recorded the four `INV-CNS` rows and **performed** the cached-read
demonstration — which is what found that the test had been proving the gate rather than the
capability, and had been resting on autocommit. **Next: M2.2's last member**, `P2-TSK-006`,
unblocked by the gate it waited for.

**M2.4 — KYB and beneficial ownership.** `P2-TSK-015` … `P2-TSK-016`; **CLOSED
2026-09-12, 2 of 2.** The milestone's stated acceptance — *an organisation decides only on a
fully verified ownership graph* — now holds **end to end over HTTP**: the acting person
registers the organisation, declares its owners, watches the gate refuse readiness until
every owner's verification answers, and reads the reviewer's decision — with a stranger's
own chain resolving to a 404, the shaped view keeping tipping-off closed, and the reviewer
seeing the graph whole. `P2-TSK-015` built the gate, the lock and the re-route;
`P2-TSK-016` carried them to the surface and answered the acting-person question by
building its missing precondition. **Next: M2.5, consent**, opening with `P2-TSK-017`;
`P2-TSK-006` stays blocked on the consent gate it delivers.

**M2.3 — Decisions and review.** `P2-TSK-012` … `P2-TSK-014`; **CLOSED 2026-09-10, 3 of
3.** The milestone's stated acceptance — *a hit case cannot terminate without a reviewer,
and a decision moves `customer.status` in one transaction* — is met and mutation-proven:
the reviewer surface resolves tasks exactly once; the case gets its one immutable,
attributable, policy-pinned decision through either door; and the customer projection
moves with the decision atomically, `PENDING → ACTIVE`/`REJECTED`, with the reconciliation
sweep holding the pair together in both directions. **The demonstration obligation is met**:
`P2-TST-001` recorded the five `INV-KYC-01`…`05` register rows and the item's §4 row in
`MUTATION_TESTING.md`, so the milestone closes with its gate criteria held by demonstration
rather than owed to the exit review. **Next: M2.4, KYB and beneficial ownership**, opening with
`P2-TSK-015`.

**M2.2 — A case exists and checks run.** `P2-TSK-005` … `P2-TSK-011` plus `P2-TSK-006`;
**CLOSED 2026-09-13, 7 of 7** — the case aggregate, the first production consumer, the
document store, the check machine, screening, the callback door, and finally the customer's
own door: `P2-TSK-006` waited on the consent gate from the day the milestone opened and
landed the day after the gate did, while M2.3–M2.5 proceeded rather than wait. Acceptance: a
case opened over HTTP reaches `READY_FOR_DECISION` on clean simulated checks, with evidence
retained verbatim — real for all five check types as of `P2-TSK-010`, a non-clean case routes
to a person, a check a crash stranded mid-call completes by the provider's own callback, and
the person's own `POST /v1/me/kyc` now opens (or converges on) the case, consent-gated.

**M2.1 — Foundations settle.** `P2-TSK-001` … `P2-TSK-004` plus the inherited `P1-TSK-033`;
**CLOSED 2026-09-09, 5 of 5.** The stated acceptance — an outbox event reaches a real consumer
through Kafka with exactly one effect per fact, and a person can change their password — held
from `P2-TSK-002` on, and the milestone stayed open until its remaining scheduled members
landed: acceptance is what a milestone *means* (the M1.2 lesson), never a licence to strand the
tasks still inside it. What settled: the broker adapter and wire format, the consumer shell and
its offset-after-effect discipline, the credential change, two guarded module skeletons, and
the reviewer population with the first real least-privilege split. **Next: M2.2, the KYC case
lifecycle.** Acceptance: an outbox event reaches a real consumer through Kafka with
exactly one effect per fact, and a person can change their password. (The milestone's own
wording inherited the backlog's exactly-once promise; corrected the same way — the *effect* is
exactly-once, the delivery is at-least-once.)

### Phase 1 milestones — all closed

**M1.2 — That person can authenticate.** **CLOSED 2026-09-08**, two days after its last numbered
task, by `P1-TSK-027`. Its stated acceptance is *"an identity authenticates and **receives a
session**"*, and until now it did not — the scope line named session issuance while the session tasks
were numbered into M1.3. **The acceptance is what a milestone means**, so it stayed open rather than
being declared met by task count.

**M1.7 — Phase review.** `P1-TSK-024`, `P1-DOC-001`; **2 of 2 complete** (2026-09-08). The review is
conducted and its verdict is that the gate does not pass — which is the milestone succeeding, not
failing: a review that could only return `COMPLETE` would not be one. The
mutation register now covers every phase the project has reached, and the current phase is derived
rather than written down — so Phase 2 needs no change to the guard.

**M1.6 — Recovery that is not the way in.** `P1-TSK-023`; **1 of 1 complete** (2026-09-08). The
milestone's stated acceptance — *every abuse case refused, each with its own test* — is met with
nine, and the invariant's own precondition had to be built before any of them could be true.

**M1.5 — Authorization, and audit that names the actor.** `P1-TSK-020` … `P1-TSK-022`; **3 of 3
complete** (2026-09-08). The milestone's stated acceptance — *every privileged action is permitted,
scoped to its owner, and recorded against the person who performed it* — is met, and each of the
three is now held against the code rather than against anybody's memory of a review. A rule's absence is never a grant: every endpoint declares one, and
an endpoint that declares nothing is refused — enforced at run time *and* at build time, because a
deployment defect found by a customer receiving a 403 has been found too late.

**M1.4 — A second factor that cannot be bypassed.** `P1-TSK-017` … `P1-TSK-019`; **3 of 3
complete** (2026-09-07). The milestone's stated acceptance — *"INV-IDN-05 demonstrated: every
enumerated path either requires the factor or cannot produce a MULTI_FACTOR session"* — is met, and
the enumeration is held against the code rather than maintained by hand.

**M1.3 — Sessions are real, and revocation is immediate.** `P1-TSK-013` … `P1-TSK-016`;
**4 of 4 complete** (2026-09-07). The milestone's stated acceptance — *"INV-IDN-03 demonstrated to
fail when the session lookup is cached"* — is met and caught twice, behaviourally and by
`NoProcessLocalSessionStateTest`.

**M1.2 — That person can authenticate.** `P1-TSK-007` … `P1-TSK-012`; **6 of 6 complete** — but the milestone does **not** close: its stated acceptance requires a session, which is `P1-TSK-027`. The session now exists; wiring it into authentication is what remains.
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

