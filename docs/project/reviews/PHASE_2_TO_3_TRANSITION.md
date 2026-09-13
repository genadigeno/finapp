# Phase 2 → Phase 3 Transition

**Conducted:** 2026-09-13
**Parts:** Phase 2 completion audit · multi-instance audit · architecture audit · security and
privacy audit · testing audit · Phase 3 initialisation
**Constraint:** a transition writes **no application code**. Everything below is audit,
decision and plan.

---

## Verdict

| Part | Outcome |
|---|---|
| Phase 2 completion audit (16 categories) | **16 `PASS`** |
| Multi-instance audit | **`PASS`** — *"would Phase 2 remain correct with 10 concurrent instances?"* |
| Architecture audit | No drift in the architecture; **one decay in the governance record**, repaired |
| Security and privacy audit | `PASS`, with five limits stated and owned |
| Testing audit | **1025 hermetic · 584 database · 14 kafka · 147 architecture**, green on a fresh run |
| **Phase 2** | **`COMPLETE`** — confirming `P2-DOC-001` |
| **Phase 3** | **Entry gate: all twelve criteria hold → `READY`** |

Phase 2 was already ruled `COMPLETE` by its exit review hours earlier. This audit is a
**second, independent pass** — the Phase 1 → 2 precedent, which re-audited Phase 1 across
seventeen categories after `P1-DOC-002` had already ruled it complete. A gate that is only ever
assessed by the person who just finished the work is not two checks.

---

## 1. Phase 2 completion audit

| # | Category | Verdict | Evidence |
|---|---|---|---|
| 1 | Functional completeness | **`PASS`** | Every `PHASE_2_PLAN.md` §1 bullet delivered; 13 operations across 11 paths, each driven over real HTTP by a database test |
| 2 | KYC correctness | **`PASS`** | One case machine, every invalid transition refused **by the aggregate** and enumerated from the machine; `INDETERMINATE` terminal with resolution as a *new* check; the case moves only by our own assessment |
| 3 | KYB correctness | **`PASS`** | The ownership graph gates the decision; readiness demands every owner **answered**, not approved; `case_kind` unwritable at `DB-PRIVILEGE`; KYB never auto-decides |
| 4 | Consent correctness | **`PASS`** | History *is* the store, order server-assigned, basis derived per read; absence and withdrawal indistinguishable; both doors of the gated capability ask |
| 5 | Domain boundaries | **`PASS`** | Four isolation tests, each forbidding every sibling; three cross-context questions answered by **ports**, not dependencies |
| 6 | Data ownership | **`PASS`** | `kyc` owns the decision, `party` projects it (`INV-KYC-05`); nothing outside `kyc` writes a decision — verified by grep across all `src/main` |
| 7 | Persistence | **`PASS`** | 11 tables, 13 migrations, every column classified at its ceiling before the migration landed |
| 8 | Transaction boundaries | **`PASS`** | Explicit `TransactionTemplate` per module; the decision and its projection in one transaction; the gate on the caller's unit of work |
| 9 | Consistency | **`PASS`** | No cross-service transaction exists; the one cross-module write (decision → customer status) is one database transaction, with kill-mid-flight tests |
| 10 | Idempotency | **`PASS`** | Convergence at every door: case opening, document upload (content-addressed), callbacks (two dedupe layers), organisation registration |
| 11 | Security | **`PASS`** | §4 below |
| 12 | Authorization | **`PASS`** | Every endpoint declares a rule or fails the build; reviewer surfaces behind `KYC_REVIEW` with negative tests; the callback behind an HMAC |
| 13 | Auditability | **`PASS`** | 9 actions catalogued and **all emitted**; `NOT_YET_EMITTED` holds exactly the three Phase-15 `outbox.*` actions |
| 14 | Observability | **`PASS`** | Six planned meters, eager and **unconditional**; dashboard queries resolve against a live scrape |
| 15 | Testing | **`PASS`** | §5 below |
| 16 | Documentation | **`PASS`** | After the exit review's four corrections and this transition's one repair |

### Against the Phase 2-specific exit criteria

All six hold; `P2-DOC-001` assessed each with named evidence and this audit re-checked them
rather than inheriting the verdicts. The two worth restating:

- *"Consent withdrawal demonstrably blocks the dependent capability"* — proven at the
  **capability**, driving the real consumer wired as the application wires it, with one
  `ConsentGate` **per simulated instance**, straddling the commit boundary.
- *"Documents are access-controlled, encrypted and every access is audited"* — the register row
  landed at the exit review with its demonstration **performed**, not inferred.

## 2. Multi-instance audit

**Would Phase 2 remain correct if 10 instances executed the relevant operations concurrently?**

# `PASS`

Every contended decision is arbitrated by PostgreSQL, and each is raced in a test with **one
connection per simulated instance** (`P0-TST-009`):

| Hazard named in the request | Mechanism | Proven by |
|---|---|---|
| Concurrent KYC submissions | Partial unique index over non-terminal states | `KycCaseDatabaseTest#tenConcurrentOpensProduceOneCase` — nine losers **converged** |
| Duplicate submissions | The same index; the endpoint answers `201` either way | `KycCaseEndpointDatabaseTest` |
| Concurrent document processing | `UNIQUE (case_id, checksum)` + savepoint | `DocumentUploadDatabaseTest#tenConcurrentIdenticalUploadsProduceOneDocument` |
| Duplicate provider callbacks | **Two layers, blind in different directions**: inbox key for identical deliveries, conditional completion for distinct ones | `ProviderCallbackDatabaseTest#aDuplicatedDeliveryHasOneEffect` |
| Delayed callbacks | Terminal checks stay terminal; the answer is retained as evidence | `#aLateCallbackIsEvidenceOnly` |
| Concurrent review actions | Conditional `UPDATE`, row count is the outcome, then the row freezes by trigger | `ReviewDatabaseTest#tenConcurrentResolutionsProduceOne` |
| Concurrent consent changes | Append-only with a **server-assigned** `seq`; no locks, no losers | `ConsentHistoryDatabaseTest` |
| Duplicate consent requests | Duplicates are honest history; the derivation absorbs them | `#repeatedGrantsAreHonestHistory` |
| Retries / timeout-then-retry | Convergence everywhere; `CONTENDED` reported honestly rather than acknowledged | `ProviderCallbackDatabaseTest` |
| Workflow recovery | The callback heals a crash-stranded `DISPATCHED` check; a duplicate delivery heals a crash between commit and assessment | `#aClearCallbackCompletesTheCheck` |
| Service restart | No in-memory workflow state exists to lose | Register, §3 |
| Duplicate events | Inbox dedupe by `eventId` | `RegistrationOpensCaseKafkaTest#aDuplicateDeliveryIsOneCase` |
| Event ordering | Per-aggregate via the relay lock and partition key; handlers order-independent | `#aSecondEventForTheSameCustomerConvergesSilently` |
| Race conditions | **Lock-then-look** where a predicate's snapshot cannot be trusted | `KybCaseDatabaseTest#aDeclarationRacingReadinessIsSeen` — the mover observed **blocked** in `pg_stat_activity` |
| Lost updates | No read-then-write on any authoritative row | Conditional updates throughout |
| Database isolation | `READ COMMITTED`, with its one real limitation found and closed | The write skew below |
| Uniqueness violations | Expected; caught behind savepoints so the caller's other writes survive | `openOrConverge` |
| Distributed scheduling | Only the relay schedules, and it takes a per-aggregate lease | `DISTRIBUTED_EXECUTION.md` §3 exemption |
| Stale reads | The gate reads authoritative state per decision, no cache | `ConsentWithdrawalBlocksTheCapabilityDatabaseTest` |
| Partial failures | One database transaction per effect; kill-mid-flight leaves nothing | `CustomerProjectionDatabaseTest#aKilledBackendLeavesNothing` |

**The finding that makes this a `PASS` rather than a formality** is `P2-TSK-015`'s: the backlog
required the readiness check as *"a predicate in the transition statement, not a read-then-act"*
— necessary and **not sufficient**. Under `READ COMMITTED`, a blocked `UPDATE` re-evaluates its
subqueries against the statement's *original* snapshot, so a just-committed owner is invisible
and an organisation could be decided over a graph it had just grown. That is write skew, it is
not visible to a predicate-only reading, and it was closed by locking the case row on **both**
sides before acting. Phase 2 met the one hazard `READ COMMITTED` genuinely has, and closed it.

**Nothing in Phase 2 coordinates in process memory** — verified by grep for `synchronized`,
process-local locks and static mutable state across all Phase 2 production code: zero
occurrences, and the ADR-0024 build rules still name exactly two permitted `ThreadLocal`s.

## 3. Architecture audit

**No drift in the architecture.** The implemented boundaries match `MODULE_ARCHITECTURE.md` §3,
the context map, ADR-0035 and ADR-0042's predecessors in `GLOSSARY.md`. Ownership is correct in
the direction that matters: `party` cannot see `kyc`, so nothing outside the orchestration can
compute a verification outcome.

### One decay in the governance record, repaired

**`DISTRIBUTED_EXECUTION.md` §3's component register had no Phase 2 entries at all.** It ended
at `SessionRevocation` — Phase 1 — while Phase 2 introduced eleven pieces of shared state: the
one-open-case index, the in-flight check index, append-only evidence, the total unique on review
tasks and decisions, the ownership graph and its lock protocol, the append-only consent history
with its server-assigned order, the unwritable consent texts, the stateless gate, and the
organisation-registrant index.

This matters more than an incomplete table, because **§3 is an enforced exemption set**: ADR-0024's
build rules permit process-local state only where this register names it. An absent row is a
component whose next author finds no precedent to extend and no recorded reason why none is
needed.

Repaired: eleven rows added, plus a note recording what the audit actually established —
**Phase 2 introduced no coordination primitive of its own.** Every row is one of four protocols
Phase 0 already proved: a unique index as arbiter, a conditional `UPDATE` whose row count is the
outcome, a privilege that makes a write impossible, and lock-then-look where a snapshot cannot be
trusted.

This is the third occurrence of the same class — `P0-TSK-032`'s review found `SecurityContext`
missing; the Phase 1 → 2 transition found four more governance decays. **The pattern is now
explicit enough to name: a register maintained by discipline decays at exactly the boundaries
where nobody is looking, which is why the ones with build guards behind them have not.**

### No new invariant group, and that is the difference from the last two transitions

Phase 0 → 1 created `INV-IDN` and Phase 1 → 2 created `INV-KYC`/`INV-CNS`, both because those
phases' properties existed only as gate prose — no stable ID, no ranked enforcement, no named
verification. **Phase 3 needs none**: `INV-LED`, `INV-BAL`, `INV-REV`, `INV-CON-01`, `INV-ACC-01`
and `INV-HIST-01` were catalogued at project initiation, because Phase 3 is what the catalogue
was written for. The platform stays at **82 invariants**.

What Phase 3 inherits instead is the exit review's finding, and the Phase 3 plan states it
where it will be read: **the in-scope set is whatever `FINANCIAL_INVARIANTS.md` marks
`Phase: 3`**, not what the plan remembers creating.

### Four decisions taken, because Phase 3 cannot start without them

`PHASE_GATES.md` §2 criterion 11 requires an ADR in at least `Proposed` for any decision needed
to start, and `CURRENT_STATE.md` §Unresolved Architectural Questions listed three at **High**
risk with "Phase 3" against them. All are irreversible once postings exist.

| ADR | Decision | Closes |
|---|---|---|
| **ADR-0039** | `READ COMMITTED`; postings are inserts and take no lock; balance-dependent decisions take `SELECT … FOR UPDATE` on the account row | Question 1 |
| **ADR-0040** | A flat, typed chart — no hierarchy; roll-up by attribute; one currency per account; nullable `gl_code` as the Phase 14 seam | Question 2 |
| **ADR-0041** | The projection lives in the ledger schema, updates in the posting's transaction, and **no decision reads it** | Question 3 |
| **ADR-0042** | Customer Account, Ledger Account, Wallet and Operational Account are four things; `accounts` and `wallet` stay one module with a recorded split trigger | Question 4 |

Two are worth the reader's attention because the obvious answer is the wrong one:

**`SERIALIZABLE` was rejected**, which reads as the less safe choice and is not. It would put a
retry loop around every money-moving command, and a retry loop around a money-moving command is
exactly where *"the database committed but the response was lost"* becomes two effects. The
operations that genuinely need mutual exclusion are a small enumerable set — the ones that read a
balance and act on it — and an explicit lock states that requirement at the site that has it.

**The balance projection may not back a hold.** An asynchronous projector is the standard answer
and it buys a lag that `INV-BAL-05` then requires us to bound, monitor and exclude from every
decision path: three mechanisms and a metric to avoid one `UPDATE` in an already-open
transaction. The cost is stated rather than hidden — postings contend on their accounts'
projection rows, and the operational-account hot row has a recorded mitigation.

## 4. Security and privacy audit

`PASS`. Eight privileged actions, each authorised, audited and negatively tested (the exit
review's table). The controls worth restating:

- **Document content**: AES-256-GCM under an externalised key confined to loopback, checksummed
  at capture and re-verified on read, readable only through one audited path, plaintext in **no
  column** (swept from `information_schema`).
- **Tipping-off**: a case in review and a case in checks answer **byte-identically**, with an
  exhaustive `switch` making a new status a compile failure until somebody decides which side of
  the disclosure line it sits on.
- **Consent refusal**: one error code for three causes, deliberately — proven as an *equality
  between the causes* rather than three assertions against remembered expectations.
- **The callback HMAC**: verified before parsing, in constant time, against RFC 4231 vectors —
  because this is the input that clears sanctions screenings.

**Five limits stated and owned**, unchanged from the exit review: no key rotation and no
per-provider callback keys (Phase 5); the per-credential loopback confinement written four times
rather than generalised (trigger fired, owed as its own work); no four-eyes; the operational
endpoints unauthenticated; no per-source rate limiting (blocked on a deployment topology).

**None is a Phase 2 correctness issue, and none blocks Phase 3.**

## 5. Testing audit

Run fresh for this transition: **1025 hermetic · 584 database · 14 kafka · 147 architecture, all
green.**

Adequacy is argued from the register and from what the tests *assert*, not from green:

- **Every `Phase: 2` invariant carries a mutation-register row** — eleven of them, two landed by
  the exit review with their demonstrations **performed**.
- **135 mutations** across the phase's twenty-one tasks that ran sweeps, counted from the change
  log. Three survived, each producing a finding; a fourth survived **correctly**.
- **Coordination is asserted, not just outcomes.** The KYB race observes the mover *blocked* in
  `pg_stat_activity`; the consent demonstration straddles a commit boundary; the run asserts
  `requestCount == checkCount`. An outcome-only test passes against both the right and the wrong
  mechanism, and this phase repeatedly refused to accept one.

**What the tests do not cover, stated**: no load or performance testing exists anywhere in the
platform (Phase 16), and provider behaviour is simulated by design and for ever (ADR-0008).

## 6. Findings

| Severity | Finding | Blocks Phase 3? | Remediation |
|---|---|---|---|
| **IMPORTANT** | `DISTRIBUTED_EXECUTION.md` §3 had no Phase 2 entries, and it is an **enforced exemption set** rather than a record | No | **Repaired in this transition**: eleven rows plus a note |
| MINOR | The ADR index and `DECISIONS.md` each keep a second copy of ADR status/coverage, with nothing reconciling them | No | Corrected at the exit review; **deriving the index from the ADR files is carried to a future transition** as the durable fix |
| MINOR | `CURRENT_STATE.md` and `BACKLOG.md` count M2.3 differently (3 numbered tasks vs 4 epic members) | No | Convention stated where used; totals reconcile at 23 |

**No CRITICAL findings. No IMPORTANT correctness findings.** The one IMPORTANT finding is a
governance-record decay, repaired here, and it is recorded as IMPORTANT rather than MINOR
precisely because the register has enforcement behind it.

## 7. Phase 2 completion

**Phase 2 is `COMPLETE` (2026-09-13)**, confirming `P2-DOC-001`.

**Delivered**: 2 modules, 11 tables, 13 migrations, 13 endpoints, 9 auditable actions, 10 new
invariants (11 in scope), 4 ADRs, 23 of 23 backlog items, and no money — by design.

**The capability in one sentence**: a person registers and their case opens — eagerly if they
already hold a basis, or by their own `POST` when they act; documents are captured encrypted and
every read of content is on the record; five checks run against simulated providers whose
answers are evidence and never decisions; a hit becomes work for a person and cannot terminate
without one; an organisation's case decides only over a fully answered ownership graph; the
decision is immutable, attributable and policy-pinned, and it moves `customer.status` in its own
transaction; and none of it proceeds for a person who has not granted a current, purpose-scoped
basis — on either door.

**Non-blocking debt carried forward**: twelve rows in `CURRENT_STATE.md` §Known Architectural
Debt, none financial-correctness, none `critical` or `high`. Two have fired triggers and are
owed as their own work: the loopback-credential generalisation, and deriving the ADR index's
status column from the ADR files.

## 8. Phase 3 initialisation

**Phase 3 — Accounts and Financial Ledger — is `READY`.**

All twelve entry-gate criteria hold:

| # | Criterion | Evidence |
|---|---|---|
| 1 | Hard dependencies `COMPLETE` | Phases 0, 1, 2 all `COMPLETE` |
| 2 | Delivery-plan section current and specific | `DELIVERY_PLAN.md` §Phase 3, unchanged and accurate |
| 3 | Bounded contexts and aggregates identified | `PHASE_3_PLAN.md` §3, §4, §5 |
| 4 | Invariants identified by ID | §6 — and the set is read from the catalogue, per the exit review's finding |
| 5 | Lifecycles drafted | Customer Account, Ledger Account, Hold, Journal Entry |
| 6 | Transaction and consistency boundaries stated | §7, ADR-0039, ADR-0041 |
| 7 | Idempotency stated for every money-moving command | §7; `INV-IDEM-01` at the financial boundary |
| 8 | External dependencies and failure modes listed | §14 — twelve scenarios; **no external dependency exists in Phase 3**, which is stated rather than left blank |
| 9 | Security, audit, reconciliation implications stated | §11, §12 |
| 10 | Backlog at task granularity with acceptance criteria | 24 items, 8 epics, 8 milestones |
| 11 | Required ADRs at least `Proposed` | ADR-0039…0042 |
| 12 | `CURRENT_STATE.md` names the active phase | Updated by this transition |

**Phase 3 is `READY` rather than `PLANNED`**, and the distinction matters because the Phase 0 → 1
transition had to withhold it: there, criterion 1 failed because Phase 0's CI criterion had never
run. Here every dependency is genuinely `COMPLETE`, verified by a full battery in this session.

The first task is **`P3-TSK-001` — the `ledger` module, its schema and the privilege floor**, and
it is first for a reason worth stating: every `DB-PRIVILEGE` claim the phase will make —
immutable postings (`INV-LED-03`), unedited history (`INV-HIST-01`), the ledger as sole writer
(`INV-LED-04`) — is only *available* at that rank because the migrator owns the objects and each
table's grants arrive with the migration that creates it. A schema created casually later, under
the wrong owner, forecloses the strongest enforcement the catalogue knows, in the phase whose
product is financial correctness.
