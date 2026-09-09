# Phase 1 → Phase 2 Transition Record

Conducted: 2026-09-09, per [`PHASE_GATES.md`](../PHASE_GATES.md) §2/§4 and
`EXECUTION_PROTOCOL.md` rules 1–2. The Phase 0 → 1 precedent applies throughout: a transition is
a separate governance act, its numbers are counted rather than quoted, and its job is to find the
gap.

**Standing on `P1-DOC-002` rather than repeating it.** The exit review was re-run **yesterday**
([`PHASE_1_REVIEW.md`](PHASE_1_REVIEW.md), addendum 2026-09-09): all twelve universal and all six
phase-specific criteria assessed with recounted evidence, verdict `COMPLETE`. This record does
not re-litigate what a day-old review established; it re-verifies the mechanical evidence fresh,
audits the axes that review did not cover, and records what **this** gate found — which was not
nothing.

---

# PART 1 — Phase 1 completion audit

Evidence: the re-run's recount (17 endpoints, 10 tables, 8 aggregates, 20 auditable actions, 72 →
now 82 catalogued invariants, 6 ADRs), plus a fresh full-suite run for this gate (864 hermetic,
465 database tests, green — §Part 5).

| # | Category | Verdict | Evidence, in one line |
|---|---|---|---|
| 1 | Functional completeness | **PASS** | The phase objective walkable end to end over published endpoints alone; 17 endpoints each driven over HTTP; `P1-TSK-033` open by ruling, scheduled M2.1 |
| 2 | Domain correctness | **PASS** | Party/Customer/Identity provably separate (`ThreeAggregatesAreSeparateTest`); no lifecycle on Party by design; glossary reconciled by build |
| 3 | Architecture | **PASS** | `app → modules → platform → sharedkernel` enforced by Gradle + ArchUnit, both engines tagged (`P1-TSK-025`); no cross-module FK, asserted |
| 4 | Data ownership | **PASS** | Each schema owned by its module; `party_id` by value; no shared mutable authority (ADR-0029) |
| 5 | Transaction boundaries | **PASS** | Explicit `TransactionTemplate` everywhere (ADR-0033); registration and administration each one transaction, proven by injected failure |
| 6 | Consistency | **PASS** | Authoritative state in PostgreSQL; per-request session/permission reads; no projection anywhere yet |
| 7 | Idempotency | **PASS** | Registration under `@RequiresIdempotencyKey` at `DB-CONSTRAINT`; authentication deliberately not idempotent with the reasoning recorded (`P1-TSK-010`) |
| 8 | Concurrency | **PASS** | Every contended operation raced by ten instances on own connections — registration, credential upgrade, lockout, revoke-vs-issue, rotation, MFA step, role grant, suspension, reinstatement |
| 9 | Failure handling | **PASS** | All twelve plan §8 scenarios tested; fails closed proven as *two* claims (no success, no durable trace); no request shape produces a 500, probed per endpoint |
| 10 | Security | **PASS** | §Part 3 |
| 11 | Authentication | **PASS** | Enumeration-safe in body **and** work (derivations counted); lockout self-healing; sessions server-side, hashed at rest |
| 12 | Authorization | **PASS** | Deny-by-default enforced twice (runtime + build); ownership in statements; inverted rule for administration; every endpoint declares or the build fails |
| 13 | Auditability | **PASS** | 20 actions catalogued and reconciled three ways; every action emitted or declared with owner; refusals audited; actor never defaulted |
| 14 | Observability | **PASS** | Six of six planned meters, eager, build-enforced; dashboard queries resolve against the live registry |
| 15 | Testing | **PASS** | §Part 5 — with the qualification that passing is the floor, not the evidence |
| 16 | Documentation | **PASS** | After this gate's own corrections (§Part 4) — the same procedure every review here has applied |
| 17 | Operational readiness | **PASS**, scoped | Health/readiness/build-info proven both directions; structured logs, traces, correlation; *deployment* readiness is explicitly Phase 15's, and nothing is deployed |

# PART 2 — Distributed-system audit

Method: the ADR-0014 register (`DISTRIBUTED_EXECUTION.md` §3) re-walked against the Phase 1
additions; the four ADR-0024 build rules confirmed live (they run in two tiers since
`P1-TSK-025`); `NoProcessLocalSessionStateTest` and its exemption staleness guard confirmed; the
concurrency suites re-run fresh.

**1. Correct with 10 instances?** **Yes.** Every authoritative decision is arbitrated by
PostgreSQL: uniqueness by constraint, transitions by conditional `UPDATE` whose row count is the
outcome, counters by `INSERT … ON CONFLICT … RETURNING`, relay ordering by transaction-scoped
advisory lock, leases by the server's clock. Nothing consults process memory for a security or
correctness decision.

**2. Operations requiring concurrency control, and their mechanism** — registration identifier
claim (unique index + savepoint); idempotent execution (claim-by-insert, server-clock lease);
credential supersede and upgrade-on-use (conditional supersede + partial unique index, savepoint
isolation); lockout (single-statement counter); session issue racing revoke-all (`FOR UPDATE`
meeting the FK's `FOR KEY SHARE`); rotation (revoke-first, issue-if-won); MFA confirm/challenge
(consumed time step, conditional); role assignment (`ON CONFLICT DO NOTHING` + partial index);
suspension/reinstatement (conditional status moves). Each has a ten-instance test on its own
connections.

**3. Invariants enforced by the database** — login-identifier-once-ever; one live customer
relationship; one active credential per identity/type; one open idempotency claim per key; inbox
dedupe; credential derivation physically must be encoded form (`INV-IDN-01` at `DB-CONSTRAINT`);
status-change timestamp ordering; append-only audit, terminal-claim freezing and
supersede-not-update (privileges + triggers); role assignment uniqueness.

**4. Enforced by application logic** — lifecycle legality (aggregates, `INV-LIFE-02`); ownership
predicates inside statements; deny-by-default authorization; assurance as a level; fingerprint
comparison (`INV-IDEM-03`); enumeration-safe response and work shaping; recovery bound to the
credential it was raised against. Each is `DOMAIN`-ranked in the catalogue and mutation-demonstrated.

**5. Eventual consistency** — exactly one place: the outbox event stream, which is durable and
**unread** (no consumer until Phase 2). Nothing authoritative is derived from it (`INV-EVT-02`).

**6. Stronger consistency required** — all identity/party/security state, and it has it: one
PostgreSQL, authoritative reads per decision, revocation and role changes effective on the next
request by construction.

**7. Single-instance assumptions** — **none found.** The four static rules sweep every module in
two tiers; the session-cache detector and its exemption guard are live; the register's
non-authoritative process-locals (metrics memoisation, correlation/security `ThreadLocal`s with
scope discipline) are each recorded with why correctness does not depend on them. The one
*historically* real defect of this class (the `P0-TSK-016` clock lease) was fixed by ADR-0014's
audit and is regression-guarded.

# PART 3 — Security audit

Everything below was verified against tests that exist and were re-run, not against descriptions.

- **Authentication**: enumeration-safe across body, status and *work*; brute-force bounded per
  identity with a self-healing, oracle-free lock; fails closed as itself.
- **Sessions**: server-side, authoritative, hashed (bearer!); revocation immediate on every
  instance; rotation on privilege change preserving the absolute bound; no `EXPIRED` status to
  go stale.
- **Credentials**: irreversible at `DB-CONSTRAINT`; per-credential parameters; upgrade-on-use;
  plaintext unwrap sites pinned to named classes.
- **MFA**: TOTP with consumed time steps; enumerated bypass paths held against the code;
  replacing a confirmed factor requires that factor. **Passkeys/WebAuthn: deliberately absent**,
  the `STRONG` rung reserved so it lands additively — a scope decision, not a gap.
- **Privilege boundaries**: two roles' worth of vocabulary but one role until Phase 2
  (`KYC_REVIEWER` is `P2-TSK-004`, which finally makes the mapping mutation-testable);
  administration's inverted ownership rule; first-administrator bootstrap out of band with its
  actor-less grant recorded.
- **Sensitive data**: classification at the ceiling on all 46+ columns; default-deny redaction;
  `information_schema`-derived leak sweeps; correlation identifiers platform-minted.
- **Secrets**: no literal can reach committed configuration; two externalised credentials, each
  with loopback confinement; scanners pinned and fresh.
- **Audit**: append-only at `DB-PRIVILEGE`; every privileged action and refusal recorded against
  the proven person.

**Weaknesses, stated rather than glossed** (all recorded with owners; none blocks — the
reasoning is in the debt register and `P1-DOC-002`):

1. No per-source rate limiting; registration and authentication are deliberate CPU/memory
   amplifiers (~46 ms / ~19 MiB per attempt). Bounded today by nothing being deployed. Phase 15.
2. `POST /v1/me/credential` unbuilt (`P1-TSK-033`, scheduled M2.1) — a person with a stolen
   password and no verified channel cannot self-serve a credential replacement.
3. Four-eyes exists as a reason requirement, not a second approver (ADR-0010 debt).
4. Operational endpoints unauthenticated (recorded debt; bounded bodies).
5. **The Kafka-plaintext debt trigger fires in Phase 2**: `P2-TSK-001` brings the first broker
   client. The broker stays loopback-only locally; the transport-security expectation transfers
   from "documented" to "owned by the task that adds the client," and its gate must say so.

# PART 4 — Architecture consistency audit

The implementation was compared against `CLAUDE.md`, `SYSTEM_ARCHITECTURE.md`,
`BOUNDED_CONTEXTS.md`, `DOMAIN_MODEL.md`, `FINANCIAL_INVARIANTS.md` and ADR-0001…0034. **No
architectural drift, no accidental coupling, no ownership violation** — the boundary suites and
the module register agree with the code, and `P1-DOC-002` resolved the one contested deliverable
(the broker adapter) a day ago with the plan corrected.

**What this gate found was documentation decay around the governance record itself, all fixed
here:**

| Drift | Fix |
|---|---|
| `docs/adr/README.md` index listed ADR-0029…0034 as `Proposed`; the files have been `Accepted` since `P1-DOC-001` | Six rows corrected |
| `DECISIONS.md` §Invariant governance said "seventy-one" invariants — stale since `INV-IDN-08` | Corrected (82, with the new groups) |
| `ROADMAP.md` §Current position was frozen at 2026-09-04 ("Phase 1 is READY and not started") | Rewritten to the actual position |
| `BACKLOG.md`'s Phase 1 header said `IN_PROGRESS … first task complete` | Corrected to `COMPLETE` |

**And one genuine defect pair in the guard machinery, found by probing (see Part 7):** the two
phase-derived guards enforce **exit** criteria but keyed on the highest phase *named*, so naming
Phase 2 as `READY` — this transition's own required act — would have (a) demanded Phase 2's
meters and invariant demonstrations before any Phase 2 code exists, and (b) made
`PlannedMetersExistTest` **stop checking Phase 1's plan entirely**, silently. And both guards
read documents that were **not declared build inputs** (`CURRENT_STATE.md`, the phase plans), so
the probe that found this initially reported green against a build that had not run — the
`P0-TSK-023` defect class, caught by asking why the probe passed.

# PART 5 — Testing and quality audit

Fresh run for this gate (after its repairs): `./gradlew build databaseTest` — **BUILD
SUCCESSFUL; 864 hermetic tests, 465 database tests, zero failures.**

Passing is the floor. What makes the suite evidence rather than reassurance, verified by
construction across the phase's record: 100+ mutation-style demonstrations registered and
guard-enforced (`MUTATION_TESTING.md`, every Phase 0–1 invariant); concurrency tests race real
instances on real connections and assert the **coordination**, not the end state; failure tests
kill real backends deterministically; negative controls accompany positive assertions
system-wide; the tag taxonomy is closed and both test engines are tagged; and the recurring
finding of the whole phase — *the defect is almost never in production code but in the thing
doing the checking* — was confirmed once more by this very gate (Part 4's guard finding).

# PART 6 — Technical debt

**CRITICAL: none.** **IMPORTANT (was): the guard/input pair above — repaired by this gate rather
than carried** (Part 7), because a transition that knowingly hands the next phase a broken gate
mechanism is hiding a correctness issue as debt.

Non-blocking, carried with owners (the full register is `CURRENT_STATE.md` §Known Architectural
Debt; deltas from this gate):

| Item | Class | Blocks Phase 2? | Owner |
|---|---|---|---|
| Broker adapter unbuilt | IMPORTANT (aging) | No — it **is** Phase 2 work (`P2-TSK-001`, M2.1) | Phase 2 |
| `P1-TSK-033` credential change | IMPORTANT (security capability) | No — scheduled M2.1 | Phase 2 |
| Kafka transport plaintext | MINOR until a non-loopback broker exists | No; trigger noted on `P2-TSK-001` | Phase 2/15 |
| Per-source rate limiting; registration amplifier | IMPORTANT at deployment, MINOR until then | No | Phase 15 |
| Four-eyes second approver | MINOR (mechanism debt, recorded) | No | Phase 3 |
| Relay/inbox metrics, retention sweeps, dead-letter tooling | MINOR | No | Phases 3/15 |

# PART 7 — Repairs performed by this gate

1. **Both phase-derived guards re-anchored to *completed* phases.**
   `MutationDemonstrationTest` and `PlannedMetersExistTest` now derive their enforcement
   boundary from the phases `CURRENT_STATE.md` records `COMPLETE` (the backticked closed-status
   vocabulary), instead of the highest phase named. Consequences, both proven by probe:
   naming Phase 2 `READY` changes nothing (green); recording Phase 2 `COMPLETE` immediately
   demands its planned meters and invariant demonstrations (red until they exist) — **the status
   flip is now the guarded act**, which is criterion 3 and criterion 6 enforced at exactly the
   moment they apply. And the meters guard now checks **every** completed phase's plan, closing
   the silent regression where Phase 1's meters would have left the checked set the day Phase 2
   was named.
1b. **And a third instance, found by the act of elaborating the backlog**: the register guard's
   §4 check demanded a `MUTATION_TESTING.md` entry for every `P{n}-TST-*` item in the backlog,
   unfiltered by phase — so writing `P2-TST-001`/`002` into the *planned* backlog failed the
   build, demanding demonstrations for code that does not exist. Bounded to completed phases,
   same boundary, same reasoning, with a vacuity assertion that the boundary did not empty the
   obligation.
2. **`CURRENT_STATE.md` and `docs/project/PHASE_*_PLAN.md` declared as `:app:test` inputs.**
   Found because the first probe of (1) reported green while the build sat `UP-TO-DATE` — the
   guards' own documents could change without the guards re-running. The `P0-TSK-023` class, in
   the build wiring of the two newest document-backed guards.
3. The four documentation decays in Part 4's table.

Both probes re-run after the repairs: `READY` green, `COMPLETE` red with both guards naming their
demands. Full suite green afterwards.

# PART 8 — Phase 1 completion

**Phase 1 — Identity and Customer Foundation: `COMPLETE`** (recorded 2026-09-09 by
`P1-DOC-002`; confirmed by this gate). Delivered capabilities, decisions and non-blocking debt
are recorded in `CURRENT_STATE.md` §Current Phase, §Completed Capabilities and §Known
Architectural Debt, and in `PHASE_1_REVIEW.md`. Headline: a Party can exist, become a Customer,
hold an Identity, prove it over HTTP, hold a session with a recorded assurance level, and have
every privileged action authorised and audited — 17 endpoints, 20 auditable actions, 8 new
invariants, 6 ADRs (`Accepted`), 864 + 465 tests, no money anywhere, by design.

# PART 3 of the gate — Phase 2 entry

Assessed against `PHASE_GATES.md` §2, criterion by criterion:

| # | Criterion | Holds? | Evidence |
|---|---|---|---|
| 1 | Hard dependencies `COMPLETE` | ✅ | Phases 0 and 1 |
| 2 | Delivery-plan section current and specific | ✅ | §Phase 2 read against `PHASE_2_PLAN.md`; the one aspirational line (object storage) corrected by ADR-0036 |
| 3 | Contexts and aggregates identified | ✅ | `PHASE_2_PLAN.md` §2/§4; module register rows existed since `P0-TSK-006` |
| 4 | Invariants identified by ID | ✅ | `INV-KYC-01`…`06`, `INV-CNS-01`…`04` — **catalogued by this transition**, the Phase 0 → 1 precedent: the gate's six prose bullets were the weaker regime the `INV-IDN` group escaped |
| 5 | Lifecycles drafted | ✅ | Plan §5 — case, check, review; consent deliberately has none (facts, not states) |
| 6 | Transaction/consistency boundaries stated | ✅ | Plan §4/§8; ADR-0035's one cross-module transaction |
| 7 | Idempotency stated per money-moving command | ✅ vacuously and said so | Phase 2 moves no money; the non-money idempotency surfaces (callbacks, case-open, consent facts) are stated per task |
| 8 | External dependencies and failure modes | ✅ | Plan §8; `SimulatedProvider` is the counterparty (ADR-0008) |
| 9 | Security, audit, reconciliation implications | ✅ | Plan §6; evidence-retention is the phase's reconciliation obligation (`INV-HIST-02`) |
| 10 | Backlog at task granularity with acceptance | ✅ | 21 tasks + 2 test items + 1 review across six milestones, `BACKLOG.md` §Phase 2 |
| 11 | Required ADRs at least `Proposed` | ✅ | ADR-0035…0038 |
| 12 | `CURRENT_STATE.md` names the phase | ✅ | Updated by this transition |

**Phase 2 — KYC/KYB and Consent: `READY`.** First task: `P2-TSK-001` (the broker adapter),
followed by `P1-TSK-033`. Not started; starting it is the next instruction, not this one.

## Risks carried into Phase 2, named

1. **Treating a provider verdict as the decision** — the plan's own first risk; `INV-KYC-01` and
   ADR-0038 exist to make the safe shape the only shape.
2. **The first real broker integration** — new failure surface (rebalance, offset discipline);
   `P2-TSK-002`'s acceptance is exactly-once-effect under restart, not happy-path delivery.
3. **Document PII** — the highest-classification data yet; ADR-0036 puts it behind one audited
   port with the strongest mechanisms already built.
4. **Tipping-off via status surfaces** — a screening hit must be invisible in customer-facing
   responses; asserted per endpoint, `INV-IDN-07`'s reasoning.
5. **Scope pressure toward Phase 13** (rescreening, monitoring) — the must-not list and rule 3
   hold the line; the seam (re-runnable check) is recorded.
