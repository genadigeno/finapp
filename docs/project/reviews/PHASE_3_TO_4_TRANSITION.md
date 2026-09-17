# Phase 3 → Phase 4 Transition

**Conducted:** 2026-09-17
**Parts:** Phase 3 completion audit · financial correctness audit · multi-instance audit ·
atomicity and consistency audit · idempotency audit · persistence audit · architecture audit ·
security and audit audit · reconciliation-readiness audit · testing audit · Phase 4
initialisation
**Constraint:** a transition writes **no application code**. Everything below is audit,
decision and plan.

---

## Verdict

| Part | Outcome |
|---|---|
| Phase 3 completion audit (16 categories) | **16 `PASS`** |
| Financial correctness audit (14 properties) | **14 `PASS`** |
| Multi-instance audit | **`PASS`** — *"would Phase 3 remain correct with 10 concurrent instances?"* |
| Atomicity and consistency audit | `PASS` — no atomicity is assumed across any boundary that does not have it |
| Idempotency audit | `PASS` — nothing rests on JVM-local memory |
| Database and persistence audit | `PASS` |
| Architecture audit | No drift in the architecture; **one decay in the governance record — the fourth of its class — repaired** |
| Security and audit audit | `PASS`, limits stated and owned |
| Reconciliation readiness | `PASS` — every link in the chain is a stored identifier |
| Testing audit | **1121 hermetic · 683 database · 14 kafka**, green on a fresh post-transition run |
| **Phase 3** | **`COMPLETE`** — confirming `P3-DOC-001` |
| **Phase 4** | **Entry gate: all twelve criteria hold → `READY`** |

Phase 3 was ruled `COMPLETE` by its exit review earlier today
([`PHASE_3_REVIEW.md`](PHASE_3_REVIEW.md)). This audit is the **second, independent pass** —
the standing precedent: a gate assessed only by whoever just finished the work is not two
checks. Where the exit review's evidence is hours old and already counted, this audit
re-checks the *claims* and probes the places the review did not: the governance registers,
the seam register, the single-instance sweep, and the planning inputs Phase 4 depends on.

---

## 1. Phase 3 completion audit

| # | Category | Verdict | Evidence |
|---|---|---|---|
| 1 | Functional completeness | **`PASS`** | Every `PHASE_3_PLAN.md` §1 deliverable delivered; 9 operations on 7 new paths, each driven over real HTTP; 25 of 25 backlog items |
| 2 | Account model | **`PASS`** | ADR-0042's four concepts held: the product carries no balance, the accounting survives the product's close, `accounts → ledger` is a build-graph fact whose reverse is a Gradle cycle |
| 3 | Ledger model | **`PASS`** | Flat typed chart (ADR-0040) with both derivations `CHECK`-held; `LEDGER_MODEL.md` matches the implementation after the exit review's two corrections |
| 4 | Journal entries and lines | **`PASS`** | Balance per currency judged at COMMIT by deferred triggers for every writer; ≥2 lines; direction carries the sign; amounts strictly positive; attribution `NOT NULL` |
| 5 | Debit/credit behaviour | **`PASS`** | Normal balance derived from type; settled = normal-side − opposite-side, stated once (`BalanceDerivation.settle`); negative is a legal state, not an error |
| 6 | Posting | **`PASS`** | One write path (`PostingEffect`: entry → audit → outbox → projection last), joining the caller's transaction — the seam Phase 4 exists to use |
| 7 | Balances | **`PASS`** | Derived (`INV-BAL-01/-02`), projected transactionally (ADR-0041), verified continuously, displayed under a `kind` that says which number it is |
| 8 | Holds | **`PASS`** | Placed and released under the account lock, derived from authoritative rows, exactly-available accepted, release converging |
| 9 | Reversals | **`PASS`** | New referencing entries, bounded per `(account, direction)` under the in-trigger advisory lock, the original byte-identical |
| 10 | Adjustments | **`PASS`** | Four-eyes as two authenticated acts; approver ≠ initiator at three ranks; payload frozen by schema |
| 11 | Currency handling | **`PASS`** | Explicit everywhere; line currency bound to the account's by composite FK; one scale per currency per entry; mixed-scale histories refuse loudly |
| 12 | Persistence and transaction boundaries | **`PASS`** | Part 6 below |
| 13 | Idempotency and concurrency | **`PASS`** | Parts 3 and 5 below |
| 14 | Security and auditability | **`PASS`** | Part 8 below |
| 15 | Observability | **`PASS`** | All six §15 meters eager from a plain context (derived guard since the flip); dashboard row resolves live; absent-not-zero throughout |
| 16 | Testing and documentation | **`PASS`** | Parts 10 and 7 below |

**Against the sixteen Phase 3-specific gate criteria**: all hold; `P3-DOC-001` assessed each
with named evidence and this audit re-checked the claims rather than inheriting the verdicts.

## 2. Financial correctness audit

The fourteen properties, each against the enforcing mechanism rather than application logic:

| # | Property | Verdict | Mechanism (database-rank where it exists) |
|---|---|---|---|
| 1 | Every journal entry balances | **`PASS`** | Domain (`JournalEntry.balanced`) **and** `V004`'s deferred COMMIT triggers — raw SQL proven refused |
| 2 | Total debits = total credits, always | **`PASS`** | Per entry (above) aggregated by the continuous trial-balance sweep, per currency, injected imbalances flagged |
| 3 | No floating point on monetary paths | **`PASS`** | `STATIC`, every module, exemption set named and same-case-only (Micrometer gauges) |
| 4 | Currency explicit | **`PASS`** | `NOT NULL` + pattern `CHECK` on every monetary column; composite FK binds lines to accounts |
| 5 | Rounding deterministic and documented | **`PASS`** | Named policies only; **no rounding exists on any Phase 3 path** — amounts enter exactly or are the caller's 422 |
| 6 | Posted history immutable | **`PASS`** | `DB-PRIVILEGE` (no `UPDATE`/`DELETE` grant) **plus** the unconditional append-only trigger binding the migrator |
| 7 | Corrections are reversals/adjustments | **`PASS`** | `INV-REV-01/-02/-04`, `INV-AUD-04` — and the original proven byte-identical as PostgreSQL's own renderings |
| 8 | No flow silently creates/destroys money | **`PASS`** | Balance-at-COMMIT for every writer + the residual-designated-account rule + the trial balance as the continuous net |
| 9 | Every balance explainable from records | **`PASS`** | Replay-from-zero equals the projection under sustained ten-instance posting, held to independent `BigDecimal` recomputation |
| 10 | Holds cause no untracked movement | **`PASS`** | A hold moves no money (no posting); it constrains availability, derived inside the lock; `holds_minor` reconciled against the rows by the verification job |
| 11 | A hold cannot release twice | **`PASS`** | Conditional `UPDATE` row count gates decrement, record and event; `RELEASED` terminal by trigger for every writer |
| 12 | A reversal cannot execute twice | **`PASS`** | The bound sums prior reversals under the advisory-lock trigger — a second full reversal exceeds the original and refuses, for every writer; reversal-of-reversal refused at domain and schema |
| 13 | Duplicate commands, one effect | **`PASS`** | Unique claim at the financial boundary; ten-way races counted in tables across postings, opens, holds, approvals |
| 14 | State reconstructable / independently verified | **`PASS`** | `BalanceDerivation` from zero; `ProjectionVerification` continuously; the trial balance; the statement's independent recomputation |

## 3. Multi-instance audit

**Would the Phase 3 financial system remain correct if 10 instances executed the relevant
operations concurrently?**

# `PASS`

The exit review's area-3 table stands (nine contended decisions, each with its PostgreSQL
arbiter and its counted race); this audit adds what the review did not run:

- **The single-instance sweep, fresh**: `synchronized`, process-local locks, `ThreadLocal`,
  `@Scheduled`, static mutable collections — **zero occurrences** across `ledger` and
  `accounts` production code (the only statics are immutable values and private static
  helpers). The ADR-0024 build rules enforce this every build; the grep is the independent
  second look.
- **Stale reads**: no decision reads the projection (write-only port until the declared
  readers; hold/close/adjustment decisions derive inside locks); the verifiers tolerate
  in-flight postings by seq bracket, never by time window.
- **Lost updates**: postings are inserts (none to lose); every mutable row moves by
  conditional `UPDATE` or under `FOR UPDATE`.
- **Distributed scheduling**: nothing new schedules; the scrape-is-the-schedule pattern for
  both sweeps means no leader, no lease, no §3 exemption question.
- **Service restart / partial failure**: no in-memory state; every write set one transaction.
- **Event publication failure**: outbox with the entry's own transaction (`INV-EVT-01`),
  at-least-once with `eventId` dedupe downstream.

**One genuinely new coordination twist this phase introduced, verified registered**: `V009`'s
advisory lock is taken *inside a trigger* (namespace 2, in the lock-namespace register), so it
binds raw SQL — where every earlier lock bound only code that chose to take it.

## 4. Atomicity and consistency audit

Per critical operation — authoritative state, boundary, arbiter, recovery:

| Operation | One transaction contains | Arbiter | Recovery |
|---|---|---|---|
| Account open | Agreement + ledger account + audit + event | Partial unique one-live index, savepoint convergence | Rollback leaves none of the four; retry converges |
| Posting | Claim + entry + lines + audit + outbox + projection | Unique claim; deferred COMMIT judgment | Nothing-at-all on failure; retry replays or executes |
| Balance update | The projection write is the posting's **last write, same transaction** | The row's own lock | Cannot diverge from the fact — current or absent together |
| Hold place/release | Decision + row + projection `holds_minor` + audit + event | Account-row `FOR UPDATE`; conditional release | Rollback total; converge on retry |
| Reversal | Bound check + entry + state effects | In-trigger advisory lock, namespace 2 | Same nothing-at-all shape |
| Adjustment | Propose: proposal + audit. Approve: decision + entry + audit + outbox + projection | Proposal-row `FOR UPDATE`; the one-way machine | Each act atomic; the machine replays |
| Account close | Zero-check + both status moves + audit + event | `FOR UPDATE` vs the posting's `FOR KEY SHARE` — both interleavings proven | Rollback total |

**No atomicity is assumed across a boundary that does not have it**: every one of these is
one local transaction on one connection (ADR-0001's monolith over one database), and the only
eventually-consistent path in the phase — event delivery — carries no financial authority
(`INV-EVT-02`) and no invariant depends on its timing.

## 5. Idempotency audit

Repeated commands, cross-instance keys, retry-after-timeout, duplicate deliveries, restarts —
each inspected against its mechanism, **none resting on JVM memory**: the claim is a unique
database constraint (ADR-0004), the stored response replays byte-for-byte, `IN_PROGRESS` is
reported rather than assumed failed, and a stale claim's takeover is judged by the server's
clock (the ADR-0014 defect class, closed in Phase 0 and untouched since). Phase 3's
non-keyed idempotency is state-machine convergence, proven ten-way in each case: account
opens converge, hold releases converge, adjustment approvals converge on the recorded entry,
account closes converge. Duplicate *event* delivery has no Phase 3 financial consumer (F5's
stated vacuity); the inbox that will own it is the Phase 0/2-proven mechanism.

## 6. Database and persistence audit

- **Keys and constraints**: every table UUIDv7-keyed (application-minted, ADR-0013); the
  phase's unique constraints each named an arbiter in part 4; FKs bind lines→entries,
  lines→accounts (+currency composite), proposals→entries, holds→accounts; `CHECK`s
  generated from enums by the established ceremony so code and schema cannot drift
  (reconciliation tests per migration).
- **Monetary precision**: the `MoneyColumns` three-column shape pinned verbatim in every
  monetary table; `INV-MON-05` proven at `BIGINT` extremes and off-default stored scales.
- **Locking**: pessimistic and enumerated (ADR-0039's set); no optimistic versioning anywhere
  a lost update could hide — inserts and conditional updates need none.
- **Isolation**: `READ COMMITTED` with its one genuine hazard (write skew against a blocked
  statement's original snapshot) met by lock-then-look at every site that has it.
- **Indexes**: the derivation's read indexed by `V004` at creation;
  `journal_line_by_account` serves statements; partial indexes are the arbiters above.
- **Migrations**: 13 this phase, forward-only, applied/validated/re-applied from scratch in
  CI and in every database-tier JVM.
- **Where an invariant could be database-enforced, it is** — the review's criterion-2 table;
  the one deliberate exception (`INV-BAL-05`, unrepresentable in SQL) is held by structure
  (a write-only port) and behavioural mutation instead.

## 7. Architecture audit

**No drift in the architecture.** Boundaries match `MODULE_ARCHITECTURE.md` §3 and
ADR-0042's asymmetry; `BOUNDED_CONTEXTS.md` context 7/5 ownership holds; `DOMAIN_MODEL.md`'s
three dates are honoured (posting/value dates are command inputs everywhere);
`EVENT_ARCHITECTURE.md`'s envelope discipline carries the five new event types;
`DATA_ARCHITECTURE.md` read against the implementation is accurate, including its
ledger-tables-as-truth role table. The two seams the roadmap's register owes from Phase 3 are
**real, verified in the schema**: `gl_code` (nullable, unused, `V002`) and the suspense
accounts (seeded per currency, the nothing-posts-to-them assertion armed).

### The finding: the component register decayed again — the fourth of its class

`DISTRIBUTED_EXECUTION.md` §3 had **no Phase 3 rows at all.** The register ended at Phase 2's
entries while Phase 3 introduced the platform's most contended shared state — the journal,
the projection row, holds, the adjustment proposal — and the transition **one phase ago**
repaired the identical decay for Phase 2 and named the pattern (*"a register maintained by
discipline decays at exactly the boundaries where nobody is looking"*). It decayed again
anyway, which sharpens the conclusion rather than embarrassing it: the registers with build
guards have not decayed once; this one's guard (ADR-0024) checks only the process-local
direction, so the durable-state rows rest on discipline, and the discipline is now stated as
a named transition-audit step.

**Repaired in this transition**: ten rows added (ledger account, journal, projection,
derivation, verifiers, holds, reversal, adjustment proposal, customer account, metric
caches) plus the "Why Phase 3 added ten rows and one registered lock" note. Phase 3
introduced **one** new coordination mechanism (the in-trigger advisory lock, registered) and
two deliberate absences that are themselves the design: postings take no lock, and the
verifiers take no lock.

### Rulings this transition owed

- **The `LEDGER_READ` declaration is struck, not scheduled.** `P3-DOC-001` found plan §9
  declaring two operational read endpoints and a permission nobody built or owned, with the
  owner set to this transition. Ruling: the declaration is corrected in the Phase 3 plan
  with provenance rather than scheduled — the trial-balance *capability* exists as the
  continuous job and gauge (`INV-ACC-01`'s own Verify clause), an operational read surface
  with no consumer is dead contract, and the surface arrives with the operator tooling that
  consumes it (Phase 15, or the first phase that needs it), as its own decision.
- **The ADR-index second-copy decay gets a build guard, as work**: `P4-TSK-002`, scheduled
  first in M4.1 — the item has been carried by two transitions, and carrying it a third time
  after the decay recurred verbatim would be the register lesson unlearned.
- **Two delivery-plan corrections with provenance** (`DELIVERY_PLAN.md` §Phase 4, module
  register): `TransferInitiated` leaves the event list (ADR-0044 — under ADR-0043 it would
  commit beside its own outcome; one fact, named once), and the stuck-transfer detector is
  recorded subjectless (no durable intermediate state exists to be stuck). The step-up
  trigger moves from "high-value or new-beneficiary transfers" to **beneficiary creation**
  (a value threshold is a versioned policy artefact with nothing to calibrate it — the
  `P3-TSK-021` argument; the value trigger is a recorded seam on the risk port).

### No new invariant group — the second transition in a row

Phase 4's properties were catalogued at initiation (`INV-CON-02`, `INV-LIFE-01/-02/-04`,
`INV-IDEM-01`'s transfers element — five, token-parsed from the catalogue). The platform
stays at **82 invariants**, and the plan states the standing rule where it will be read: the
in-scope set is what the catalogue marks `Phase: 4`.

### Two decisions taken, because Phase 4 cannot start without them

| ADR | Decision | Closes |
|---|---|---|
| **ADR-0043** | The transfer and its posting commit in **one local transaction**; a failed transfer is a committed domain outcome; "compensation" is the business reversal and nothing else; **no internal saga**, with the boundary at which that answer changes named (Phase 5's payment lifecycle) | Unresolved question 5 (High) — open since initiation |
| **ADR-0044** | The transfer lifecycle is four states with every state earned by a producer: `INITIATED → {COMPLETED, FAILED}`, `COMPLETED → REVERSED`; no `VALIDATED`/`AUTHORIZED`/`PROCESSING`/`CANCELLED`, each refused with its reason; `COMPLETED` stable-not-terminal as a recorded reading of `INV-LIFE-04` | The state-machine design the delivery plan's §14 demands documented |

The one worth reading twice: **the rich conventional machine was rejected because ADR-0043
makes most of its states unobservable** — a state that begins and ends inside one
uncommitted transaction is a comment wearing a status's clothes, and a state no command can
produce is a branch somebody eventually writes code for.

## 8. Security and audit audit

`PASS`. Eight Phase 3 auditable actions, all emitted, each with actor/correlation/outcome and
the reason-required pair enforced at construction; two permissions and one role,
pairwise-disjoint across the three privileged populations and negatively tested from every
direction; four-eyes structural at three ranks on the one action `INV-AUD-04` names; no
amount in any exception, log, event payload or metric tag; ownership one-404 across every
customer surface; the in-process posting command deliberately permission-free with the
reasoning recorded. Secrets: no new credential this phase; the committed-configuration rule
and scanner sweep unchanged and green.

**Limits stated and owned, unchanged**: no four-eyes threshold policy (a versioned artefact,
deliberately); operational endpoints unauthenticated (Phase 15); per-source rate limiting
blocked on deployment topology (Phase 15); the loopback-credential generalisation (Phase 5).

## 9. Reconciliation readiness

`PASS`. Phase 3 is self-contained (nothing external to reconcile against — F8's recorded
posture), and what it preserves is what Phase 8 will need: every entry carries its
`idempotency_scope` (the join back to the commanding flow), attribution
(actor/correlation/causation, `NOT NULL`), both dates as domain inputs, exact
amount+currency+scale per line, the reversal's `reverses_entry_id`, the adjustment's
proposal linkage, and the suspense accounts standing empty per currency with their
nothing-posts assertion armed. The chain economic event → operation → transaction → entry →
lines → balances is walkable by stored identifier in both directions — demonstrated by the
exit review's area-2 walk. **Nothing found that would make future reconciliation difficult**;
the one deliberate absence (no external evidence store entries) is the absence of external
evidence, not of the machinery (Phase 2's verbatim-evidence pattern is the template).

## 10. Testing audit

Run fresh for this transition, after its own document changes (`CURRENT_STATE.md` and the
plans are declared build inputs): **1121 hermetic · 683 database · 14 kafka, green.**

Adequacy argued from what the tests assert, not from green: every `Phase: 3` invariant
carries a register row with its demonstration **performed** (19 of 19, token-verified); 138
mutations across 22 items with **one survivor, correctly, zero wrongly**; coordination is
asserted, not just outcomes (losers observed Lock-waiting in `pg_stat_activity`; the
moved-outside-the-lock mutation caught by the outcome half; storms ended by verifier floors,
never by time); constraints proven against raw SQL from scratch, not through the domain.
The standing not-covered statement is unchanged: no load/performance testing before
Phase 16; providers simulated by design.

## 11. Findings

| Severity | Finding | Blocks Phase 4? | Remediation |
|---|---|---|---|
| **IMPORTANT** | `DISTRIBUTED_EXECUTION.md` §3 had **no Phase 3 rows** — the fourth occurrence of the register-decay class, one transition after the pattern was named | No | **Repaired here**: ten rows + the Phase 3 note; the check is now a named transition-audit step |
| MINOR | `ROADMAP.md` §Current position frozen at 2026-09-13 ("Phase 3 `IN_PROGRESS`") | No | Corrected by this transition |
| MINOR | `DELIVERY_PLAN.md` §Phase 4 / module register carry three pre-decision statements (the `TransferInitiated` event, the stuck-transfer detector, the step-up trigger) | No | Corrected with provenance per the §7 rulings |
| MINOR | Plan §9's struck `LEDGER_READ` declaration (inherited from `P3-DOC-001`) | No | Ruled and corrected per §7 |
| MINOR | `CURRENT_STATE.md` §Unresolved Architectural Questions still listed questions 1–4 as open, a full phase after ADR-0039…0042 closed them — the stale-second-copy class in the very table this transition came to update for question 5 | No | Moved to *Resolved since* with provenance |

**No CRITICAL findings. No correctness findings of any severity.** The one IMPORTANT finding
is the governance-record decay, repaired here.

## 12. Phase 3 completion

**Phase 3 is `COMPLETE` (2026-09-17)**, confirming `P3-DOC-001`.

**Delivered**: 2 modules, 8 tables, 13 migrations, 9 operations on 7 paths, 8 auditable
actions all emitted, 2 permissions + 1 role, 5 event types, 5 aggregates, 4 ADRs
(`Accepted`), 19 invariants in scope with 19 register rows, 25 of 25 backlog items, 138
mutations with one correct survivor, 1121/683/14 tests — and **money**: the authoritative
financial record, with every balance explainable, every correction a new entry, and the trial
balance continuously zero per currency.

**Non-blocking debt carried**: the standing platform rows (retention, dead-letter, rate
limiting, loopback generalisation — Phases 5/15), none financial, none critical/high. The
twice-carried ADR-index item stops being carried: it is `P4-TSK-002`.

## 13. Phase 4 initialisation

**Phase 4 — Internal Transfers — is `READY`.**

All twelve entry-gate criteria hold:

| # | Criterion | Evidence |
|---|---|---|
| 1 | Hard dependencies `COMPLETE` | Phases 3 (ledger) and 1 (actor) `COMPLETE`; the Phase 2 verified-party gate is inherited structurally — the ownership chain reaches only a live customer's products |
| 2 | Delivery-plan section current and specific | §Phase 4, with this transition's three provenance corrections |
| 3 | Bounded contexts and aggregates identified | `PHASE_4_PLAN.md` §3, §4 |
| 4 | Invariants identified by ID, from the catalogue | §6 — five, token-parsed |
| 5 | Lifecycles drafted | ADR-0044; Beneficiary's two-state machine in §8 |
| 6 | Transaction and consistency boundaries stated | ADR-0043; plan §7 |
| 7 | Idempotency stated for every money-moving command | Plan §4/§7: execute (keyed, fingerprint enumerated) and reverse (state-machine convergence) |
| 8 | External dependencies and failure modes listed | §14 — twelve scenarios; **no external dependency exists in Phase 4**, stated rather than left blank |
| 9 | Security, audit, reconciliation implications stated | §11, §12 |
| 10 | Backlog at task granularity with acceptance criteria | 14 items, 8 milestones |
| 11 | Required ADRs at least `Proposed` | ADR-0043, ADR-0044 |
| 12 | `CURRENT_STATE.md` names the active phase | Updated by this transition |

The first task is **`P4-TSK-001` — the `transfers` module, its schema and the privilege
floor**, first for the standing reason: the floor is what every later grant claim rests on —
and for a Phase 4-specific one: the module's build-graph edges (`transfers → ledger`
declared, `ledger → transfers` a demonstrated cycle) are the boundary that keeps the phase's
one named top risk — the transfer module writing postings directly — structurally
unreachable before any transfer code exists.
