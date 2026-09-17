# Phase 3 Exit Review — Accounts and Financial Ledger

**Conducted:** 2026-09-17 (`P3-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 3-specific criteria)
**Phase objective under review:** the authoritative financial record — a chart of accounts,
balanced immutable postings, balances derived and reproducible from zero, holds against
available balance, reversal without mutation, and a trial balance asserted continuously.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`** — area 2 has a subject for the first time in the programme, and the posting is walked |
| Universal criteria (12) | **12 `PASS`** |
| Financial supplement (F1–F8) | **8 `Met`** — binding for the first time; re-assessed at the gate, not inherited from [`PHASE_3_FINANCIAL_SUPPLEMENT.md`](PHASE_3_FINANCIAL_SUPPLEMENT.md) |
| Phase 3-specific criteria | **16 `PASS`** — the gate lists sixteen (9 original + 7 transition-extension); the backlog's `P3-DOC-001` scope says "nine", a drift recorded in area 7 |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — every contended decision arbitrated by PostgreSQL and raced in a test; the table is in area 3 |
| **Verdict** | **Phase 3 `COMPLETE` (2026-09-17)** |

The review was conducted in the order the `P2-DOC-001` precedent established: assess, land the
corrections, **flip the status — the flip is the guarded act, because recording a phase
`COMPLETE` changes what the build demands** — then re-run the full battery and finalize with
counted numbers. The battery after the flip is green: **1121 hermetic, 683 database, 14 kafka
tests**, with `MutationDemonstrationTest` deriving its demanded set from the catalogue for a
third `COMPLETE`-recorded phase and `PlannedMetersExistTest`'s derived guard taking over
Phase 3's §15 table from the pinned one. Unlike Phase 2's flip, this one surfaced nothing —
because `P3-TST-003` predicted the one failure the flip would have produced (`INV-AUD-04`'s
missing register row), created `P3-TSK-021` to land it, and `P3-TSK-021`'s own battery already
survived a simulated flip. The gate machinery found its defect **before** the gate instead of
at it, which is the posture the `P2-TST-001`/`P3-TST-003` items exist to buy.

---

## Area 1 — Scope: what the phase set out to build, and what it built

[`PHASE_3_PLAN.md`](../PHASE_3_PLAN.md) §1 lists eight deliverables. Each is checked against
the implementation, not against the change log's memory of it:

| Plan §1 deliverable | Delivered by | Verified |
|---|---|---|
| The `ledger` module, schema and privilege floor | `P3-TSK-001` | Owner `finapp_migrator`; the application role holds `INSERT`/`SELECT` only on the journal tables — `INV-LED-03`/`INV-HIST-01` at `DB-PRIVILEGE`, swept per column |
| A flat typed chart with the operational accounts seeded | `P3-TSK-002`, `P3-TSK-003` | `INV-LED-06` frozen in two layers; fifteen seeded rows (5 purposes × 3 currencies) resolving before anything can post |
| Balanced immutable postings | `P3-TSK-004`…`-006` | Unbalanced unconstructible at the domain **and** uncommittable by raw SQL (deferred constraint triggers); one idempotent command path (`INV-LED-04`) |
| Balances derived, projected transactionally, verified | `P3-TSK-008`…`-010`, `P3-TST-001` | Replay-from-zero equals the projection under sustained ten-instance posting; drift is detected, reported, never repaired |
| The Customer Account product over HTTP | `P3-TSK-011`…`-014` | Open (gated on `ACTIVE` verification), list, balance that says which number it is, close that leaves history readable |
| Holds against available balance | `P3-TSK-015`, `P3-TST-002` | Decided inside the account lock from authoritative rows; ten-way race admits exactly what was available |
| Correction without mutation | `P3-TSK-016`, `P3-TSK-017`, `P3-TSK-021` | Reversal bounded per `(account, direction)` with the original byte-identical; adjustment under four-eyes as two authenticated acts |
| Statements, the trial balance, the six meters, the gate | `P3-TSK-018`…`-020`, `P3-TST-003`, this review | Statement reconciles by construction; `INV-ACC-01` continuously published; every §15 meter live from a fresh instance |

**The backlog closes at 25 of 25** — 21 `P3-TSK-*`, 3 `P3-TST-*`, and this item — across
eight milestones, all `CLOSED`. One item (`P3-TSK-021`) was created mid-phase by `P3-TST-003`,
which is the register doctrine working: the row that could not honestly be written became a
task rather than a claim.

**Area 1: `PASS`.**

---

## Area 2 — Walk one real posting end to end

This area had no subject in the Phase 0, 1 and 2 reviews, and each said so rather than
reporting a pass. **It has one now — the first in the programme** — and the subject chosen is
the richest write the phase built: a manual adjustment under four-eyes, because it exercises
the entire chain the `CLAUDE.md` §Ledger mandate names — *economic event → domain operation →
financial transaction → journal entry → debit/credit lines → resulting balances* — plus the
controls (`INV-REV-04`, `INV-AUD-04`) that make a manual posting defensible. Every step below
names the code and the test that proves it.

### 1. Economic event

An operator decides the books must be corrected — a real-world fact outside the system. It
enters as `POST /v1/ledger/adjustments` behind `@RequiresPermission(LEDGER_ADJUST)`
(`P3-TSK-007`'s permission at its designed check site) and `@RequiresIdempotencyKey`, with a
**required reason** — the justification enters the record at the moment the initiator writes
it (`INV-REV-04`), bounded identically in the DTO, `V004`'s `CHECK` and `AuditRecord`.

### 2. Domain operation

`AdjustmentService.propose` (`ledger/AdjustmentService.java`): amounts arrive as decimal
strings parsed **exactly** (`INV-MON-01`/`-03` at the boundary — an inexact amount is the
caller's 422, never a rounding), the would-be entry is validated balanced through
`JournalEntry.balanced` and discarded, the idempotency claim is taken (scope `ledger.adjust`,
fingerprint binding **actor + reason + lines** — `INV-IDEM-03`'s sharpest case: the same key
with a different justification conflicts), and the proposal is written:
`ledger.adjustment_proposal` + lines (`V010`), payload **frozen by trigger for every writer**,
plus the `ledger.AdjustmentProposed` audit record naming the initiator. **Nothing posts.**

A second `LEDGER_ADJUST` holder reads `GET /v1/ledger/adjustments/{id}` — and because the
payload is schema-frozen, what they read is structurally what will post (TOCTOU closed at the
schema, proven by `AdjustmentProposalSchemaDatabaseTest` *"the proposal a person read is the
proposal that decides"*). They approve via `POST …/{id}/approval`:
`AdjustmentService.approve → doApprove` locks the proposal row (`lockById`, `FOR UPDATE` —
lock-then-look), converges a same-approver retry onto the recorded entry, refuses
self-approval (`requireApprovableBy` — `INV-AUD-04`, backed at `DB-CONSTRAINT` by `V010`'s
`CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)`), and rebuilds
`JournalEntry.balanced` from the stored rows.

### 3. Financial transaction

The approval's transaction is the financial transaction: one unit of work in which the
proposal moves `PROPOSED → APPROVED`, the entry posts, and every record of the act commits —
together or not at all. Its idempotency is the one-way lifecycle itself (scope
`ledger.adjust.approve:<proposalId>`, `APPROVAL_SCOPE_PREFIX` in `AdjustmentService`): ten
concurrent approvals produce **exactly one entry, counted in the table**
(`AdjustmentEndpointDatabaseTest` *"ten concurrent approvals produce exactly one entry"*),
every response converging on it.

### 4. Journal entry

`PostingEffect.record` — the one write set every journal entry commits with (`INV-LED-04`'s
single write path, shared with postings and reversals): `journal.append` writes the
`ledger.journal_entry` row, `entry_type = 'ADJUSTMENT'`, **actor = the approver** (ADR-0021's
honesty rule: the posting is the approver's act; the initiator is one join away through the
proposal row, reachable from the entry's `idempotency_scope`), attribution `NOT NULL`
(`INV-LED-05`). At COMMIT, `V004`'s deferred constraint triggers judge the whole entry
balanced per currency (`INV-LED-01`) with at least two lines (`INV-LED-02`), and `V010`'s
deferred trigger refuses any `ADJUSTMENT` entry without an `APPROVED` proposal referencing
it — for **every** writer, raw SQL included. Once committed, the entry is immutable at
`DB-PRIVILEGE` (no `UPDATE`/`DELETE` grant) and by the unconditional append-only trigger
(`INV-LED-03`, `INV-HIST-01`).

### 5. Debit/credit lines

`ledger.journal_line`: direction carries the sign and amounts are strictly positive, so
"balanced" is two per-currency sums that must be equal — the property the schema inherits
from `P3-TSK-004`'s domain design. Each line's currency is bound to its account's by `V005`'s
composite FK (a USD line on a JPY account is unstorable for every writer), the scale is
stored (`INV-MON-05`), and one scale per currency per entry is a COMMIT-time constraint.

### 6. Resulting balances

The last write in `PostingEffect` is the projection: `BalanceProjection.apply` increments
`ledger.account_balance` under the row's own lock in the posting's transaction (ADR-0041 —
current or absent along with the fact, never behind), watermark `last_entry_seq` advancing by
one. The authoritative number is derivable from zero (`BalanceDerivation` — `INV-BAL-01`/
`-02`, proven under sustained concurrent posting by `SustainedConcurrentPostingDatabaseTest`
*"replay from zero equals the projection throughout ten instances posting"*); the customer
sees it as decimal strings under `kind: "PROJECTION"` (`GET /v1/me/accounts/{id}/balance`);
the statement reconciles opening + lines = closing **by construction** under
`kind: "DERIVED"` (`INV-ACC-02`'s drill-down shape); and the trial balance sweeps the entry
into a per-currency zero (`INV-ACC-01`, `finapp.ledger.trial.balance`). The write is observed
by `finapp.ledger.posting{outcome=posted}` and timed — proven through real HTTP by
`P3-TSK-020`.

**Settlement and reconciliation** — the chain's last two links — have no Phase 3 subject:
settlement is Phase 5/8's and reconciliation Phase 8's, and the seam is already seeded
(`SUSPENSE_UNMATCHED` exists per currency with the Phase-3 nothing-posts-to-it assertion
armed). Stated rather than glossed.

The walk is driven end to end in one suite:
`AdjustmentEndpointDatabaseTest` *"an operator proposes and a SECOND operator approves:
nothing posts before the approval"* — proposal visible and postless, approval by a second
person, entry posted with the approver as actor, projection moved, both audit records
present, event announced.

**Area 2: `PASS` — assessed, with a subject, for the first time.**

---

## Area 3 — Multi-instance correctness

The mandatory question: **would this remain correct if 10 instances executed it
concurrently?** Answered per contended decision, each with its PostgreSQL arbiter and the
test that races it — never an adjective:

| Contended decision | Arbiter | Raced by |
|---|---|---|
| Ten identical posting commands | Unique `(scope, idempotency_key)` claim | `PostingServiceDatabaseTest` — "ten instances, one key: one effect, counted in the database" |
| Ten concurrent postings to one account | Projection row's exclusive lock; entries are inserts (ADR-0039 — no lost update exists to have) | `BalanceProjectionDatabaseTest` — 100+…+1000 = 5500 counted in the table, first-row `ON CONFLICT` convergence included |
| Sustained posting racing the verification job | Seq-bracketed read; watermark serialised by the row lock | `SustainedConcurrentPostingDatabaseTest` — every mid-storm sweep drift-free; a lock-then-look rebuild mid-storm loses nothing |
| Ten opens of one customer's account | Partial unique one-live index + savepoint convergence | `CustomerAccountDatabaseTest` — one agreement, one ledger account, one record, one event, nine converged |
| A close racing an in-flight posting | `SELECT … FOR UPDATE` vs the posting's `FOR KEY SHARE` — the mode analysis, both interleavings proven with the loser observed Lock-waiting | `AccountClosingDatabaseTest` |
| Ten holds against finite available balance | Account-row `FOR UPDATE`, derivation inside the lock | `HoldDatabaseTest` — 1000×10 against 3000: exactly three accepted, counted; the moved-outside-the-lock mutation caught by the outcome half (`P3-TST-002`) |
| Ten concurrent partial reversals | `V009`'s advisory-lock trigger (namespace 2) summing prior reversals per `(account, direction)` | `ReversalDatabaseTest` — 400×10 against 1000: exactly two accepted, reversed total 800 counted |
| Ten concurrent approvals of one proposal | Lock-then-look on the proposal row; the one-way machine is the idempotency | `AdjustmentEndpointDatabaseTest` — exactly one entry, counted in the table |
| Trial-balance sweeps racing live posters | One statement, one snapshot; deferred constraints mean a snapshot never holds half an entry | `TrialBalanceDatabaseTest` — "sweeps racing live posters read zero every time" |

No coordination primitive was invented: every arbiter is one of the four Phase 0-proven
protocols (unique index, conditional `UPDATE` row count, privilege, lock-then-look) plus the
one registered advisory-lock namespace (`V009`, namespace 2, in the register). No
process-local state joined `DISTRIBUTED_EXECUTION.md` §3 — verified against the register.

**Area 3: `PASS`. The ten-instances answer is `PASS`.**

---

## Area 4 — Failure engineering

[`PHASE_3_PLAN.md`](../PHASE_3_PLAN.md) §14 names twelve failure scenarios. Each is mapped to
its evidence:

| §14 scenario | Evidence |
|---|---|
| 1. Request times out after commit | The stored response replays byte-for-byte (`INV-IDEM-01`); `PostingServiceDatabaseTest` retry tests |
| 2. Client retries a posting | Same — one effect, counted; a different fingerprint on a known key is a 409 (`INV-IDEM-03`) |
| 3. Crash between commit and event publication | The outbox row commits with the entry; redelivery-with-same-`eventId` is `P0-TST-005`'s proven property, cited in the assertion |
| 4. Injected failure at the last write | "an injected failure at the last write leaves nothing at all" — no entry, no claim, no projection change |
| 5. Rolled-back posting | "a rolled-back posting leaves no outbox row"; the projection unchanged |
| 6. Verification racing in-flight postings | `IN_FLIGHT`, never false drift — the deterministic mid-comparison commit (`P3-TSK-010`) |
| 7. Projection corrupted by an unknown writer | Detected (`DRIFTING`), reported, **never repaired** — both corruption shapes planted through the app role's own grant |
| 8. Concurrent hold placement at the boundary | Exactly-available accepted, one minor unit more refused, ten-way counted (`P3-TSK-015`) |
| 9. Over-reversal, concurrently | Two of ten partial reversals accepted, the bound judged under the advisory lock for every writer (`P3-TSK-016`) |
| 10. Raw-SQL writer bypassing the domain | Refused at the schema: deferred balance triggers, append-only trigger, four-eyes trigger, kind-binding FKs — each proven against raw SQL from scratch |
| 11. Backend killed mid-posting | **Covered by argument, not by a literal kill test, and recorded honestly**: the posting write set is one transaction on one connection with no second connection anywhere in the flow, so a killed backend is a rollback — the shape scenario 4 and 5's probes demonstrate (nothing at all remains). The literal `pg_terminate_backend` idiom exists in the suite (`AuthenticationFailsClosedDatabaseTest`) and was not duplicated here because the single-transaction design leaves it nothing distinct to prove |
| 12. Trial balance detects an injected imbalance | Three raw-SQL shapes flagged per currency, riding the constraint deferral so nothing commits (`P3-TSK-019`) |

**Area 4: `PASS`**, with scenario 11's covered-by-argument status stated rather than implied.

---

## Area 5 — Security and audit

**Privileged actions.** Phase 3 added 8 auditable actions and all 8 are emitted —
`AuditCompletenessTest`'s `NOT_YET_EMITTED` holds exactly the three Phase-15 `outbox.*`
actions, verified against the code rather than the catalogue's memory:
`ledger.JournalEntryPosted`, `ledger.AdjustmentProposed`, `ledger.AdjustmentPosted`,
`ledger.AdjustmentRejected`, `ledger.HoldPlaced`, `ledger.HoldReleased`,
`accounts.AccountOpened`, `accounts.AccountClosed`. The reason-required pair
(`AdjustmentProposed`, `AdjustmentPosted`) is the invariant speaking (`INV-REV-04`).

**Authorization.** Two permissions (`LEDGER_POST`, `LEDGER_ADJUST`), one role
(`LEDGER_OPERATOR`, holding exactly both — `RoleName.java`), pairwise-disjoint from the other
two populations and proven from every direction over HTTP (`DenyByDefaultDatabaseTest`).
Every new endpoint has its negative test: a session without the role refused on every surface
with nothing written (`INV-AUD-03`), unauthenticated refused, ownership one uniform 404
across not-yours/unknown/malformed. The in-process posting command deliberately carries no
permission — ADR-0031 puts permission at the boundary, and a Phase 4 transfer runs as the
customer.

**Four-eyes** (`INV-AUD-04`): approver ≠ initiator at three ranks — the aggregate
(`INV-LIFE-02`), `V010`'s plain `CHECK`, and the deferred journal trigger binding raw SQL.
Self-approval's negative test refuses with **nothing written** and a second person approving
the same proposal as the positive control.

**Disclosure.** No amount reaches an exception message, a log, an event payload or a metric
tag (`INV-AUD-02`) — the refusals name accounts, currencies and facts, never sums; the
statement never discloses the counterparty account or the adjustment `reason`; balances leave
only through the two declared surfaces that say which number they are.

**Area 5: `PASS`.**

---

## Area 6 — Invariants, the mutation register, and the demonstrations

**The in-scope set is what the catalogue says, not what the plan remembers** — the lesson
both prior reviews paid for, applied here by parsing the `**Phase:**` field of every
invariant token-exactly (a naive substring match falsely reads "13" as containing "3"; the
count was verified with a standalone-token parse). The result: **nineteen `Phase: 3`
invariants** — `INV-LED-01`…`06`, `INV-BAL-01`…`05`, `INV-HIST-01`, `INV-CON-01`,
`INV-REV-01`, `INV-REV-02`, `INV-REV-04`, `INV-REC-05`, `INV-ACC-01`, `INV-AUD-04` — where
the plan's §6 table lists seventeen (`INV-REC-05` and `INV-AUD-04` sit outside it, exactly
the drift the plan's own closing paragraph predicted and ruled on: the catalogue wins).

**All nineteen have `MUTATION_TESTING.md` §2 rows** — verified by counting distinct
identifiers in the register against the catalogue's list, and held to the code on every
build by `MutationDemonstrationTest`'s nine checks, which since the status flip derive
Phase 3's demanded set from the catalogue. The last row to land (`INV-AUD-04`) is the phase's
governance headline: `P3-TST-003` found the row could not honestly be written because the
mechanism was deliberately unbuilt, refused to write a false row, predicted the exact battery
failure the flip would produce, and created `P3-TSK-021` — which built the mechanism, landed
the row, and proved the battery survives the flip before this review needed it.

**Mutations, counted from the change log rather than quoted**: **138 mutations, probes and
demonstrations across the 22 items that performed them** (`P3-TSK-002`…`-021` = 20 sweeps
totalling 136, plus one performed demonstration each by `P3-TST-001` and `P3-TST-002`), plus
**three §5 guard-teeth re-proofs** (`P3-TST-001`, `P3-TST-002`, `P3-TST-003` — one method
reference corrupted, the guard failing naming exactly it, restore byte-identical each time).
`P3-TSK-001`'s four planned probes were **not performed** (owner's direction, recorded in the
change log at the time — coverage evidenced instead through the derived module sweep) and
`P3-TST-003` performed none by design (its deliverable was the record; every recordable
demonstration was found already performed by its owning task). This review performs none,
and that is the point: with nineteen of nineteen rows landed ahead of the gate, there was no
scramble left for the review to do — the posture the `P3-TST-*` items exist to buy.

**Survivors: exactly one across the phase, and it survived correctly** — `P3-TSK-003`'s
`findOperational` losing its `owner_ref IS NULL` predicate changes nothing today because the
purpose→owner-kind CHECK chain makes the excluded rows unstorable; recorded as defence in
depth. Zero mutations survived wrongly. (Phase 2 had three wrong-survivals, each producing a
finding; Phase 3's sweeps were designed against those findings and produced none.)

**No new invariant group and no new invariants**: the platform stays at **82**, because
Phase 3's properties were catalogued at project initiation — Phase 3 is what the catalogue
was written for.

**Area 6: `PASS`.**

---

## Area 7 — Documentation accuracy

Assessed by hand-diffing the plan and the domain document against the implementation — the
method that found the real defects in all three prior reviews. Six findings; five corrected
by this review, one recorded with an owner.

**Finding 1 — the plan declares two endpoints and a permission that were never built and no
task owns.** `PHASE_3_PLAN.md` §9 lists `GET /v1/ledger/accounts/{id}` and
`GET /v1/ledger/trial-balance` behind a `LEDGER_READ` permission. Neither endpoint exists,
`LEDGER_READ` is not in `PermissionName`, and no backlog item owns any of the three —
the recurring unowned-declaration class (ninth occurrence in Phase 1, met again here).
**Ruled non-blocking**: no exit criterion names them; §1's deliverables are all delivered —
the trial-balance *capability* exists as the continuous job and gauge (`INV-ACC-01`'s Verify
clause asks for a job with alerting, not an endpoint), and the operational read surface has
no consumer until an operator tool exists. Recorded with owner = **the Phase 3 → 4
transition**, which must either schedule them or correct the plan's declaration; the plan's
§9 rows are annotated with that provenance rather than silently deleted.

**Finding 2 — `LEDGER_MODEL.md` was stale on the adjustment, and its own front matter
assigns this review the correction.** Two spots still described `P3-TSK-017`'s one-person
adjustment: §1's table row ("a correction anybody may make") and §6's bullet recording
four-eyes as unbuilt debt with a threshold. Both superseded by `P3-TSK-021` — every
adjustment is proposed by one person and approved by a second, and there is no approver
column because there are two acts. Corrected, with the front-matter provenance note updated.

**Finding 3 — `BACKLOG.md` carries a second copy of each phase's status, and three of four
were stale.** The Phase 0 header read `IN_PROGRESS` (COMPLETE since 2026-09-04), the Phase 2
header `READY` (COMPLETE since 2026-09-13), and the Phase 3 header `READY` (IN_PROGRESS
since 2026-09-13). The same mechanism as the ADR index's status column — a fact written in
two places with nothing reconciling them — and the same decay `P2-DOC-001` found there. All
three corrected; deriving phase status from one source remains with the transition alongside
the ADR-index item it already carries.

**Finding 4 — `CURRENT_STATE.md`'s §Next Task was stale at `P3-TSK-011`** — unmaintained
across eleven tasks while §Current Task stayed correct: two sections of one document
disagreeing, the exact shape §Active Work exhibited across the whole of Phase 2. Replaced by
this review with the Phase 3 → 4 transition it now points at.

**Finding 5 — the backlog's `P3-DOC-001` scope says "nine Phase 3-specific criteria"; the
gate lists sixteen.** `PHASE_GATES.md` §5 carries 9 original criteria plus 7 added by the
Phase 2 → 3 transition's extension. The review assessed all sixteen (below); the backlog
sentence was written before the extension and never updated — recorded here, with the gate
as the authority.

**Finding 6 — the ADR files and the ADR index both said `Proposed`** for ADR-0039…0042 —
the known second-copy decay, met exactly as `P2-DOC-001` predicted it would recur. Both
flipped to `Accepted` by this review (see the ADR section); `DECISIONS.md` already carries
all four Phase 3 entries (added by the transition), so the third copy needed no change —
an improvement over Phase 2, where it had silently omitted the whole phase.

**Area 7: `PASS`** — the findings are the area working, and none blocks a criterion.

---

## Area 8 — Debt

**No financial-correctness debt** — `EXECUTION_PROTOCOL.md` §Architectural Debt forbids it,
and none was accepted: every in-scope invariant is enforced and demonstrated.

**One debt row dissolved**: ADR-0010's four-eyes "second actor column" — dissolved rather
than paid, because a four-eyes action is two acts, each with one actor, and the pairing
lives on the proposal row (`P3-TSK-021`).

**No new rows.** The phase's recorded remainders each have a named owner and none is debt in
the register's sense: hold expiry (Phase 5, a rail/product rule), capture (Phase 4/5), the
de-minimis adjustment threshold (a future policy artefact whose seam is the proposal row),
full ledger-read operational tooling (finding 1's owner, the transition), and the raw-SQL
projection bypass — which is not debt but the detection design: the verification job exists
to catch exactly that writer, proven with planted entries.

**Standing platform debt** (outbox/inbox/audit retention, dead-letter tooling, per-source
rate limiting, the loopback-confinement generalisation) is unchanged by Phase 3 and remains
owned by Phase 5/15 as recorded in `CURRENT_STATE.md`.

**Area 8: `PASS`.**

---

## The twelve universal exit criteria

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Every deliverable exercisable end to end | `PASS` | The area-2 walk over real HTTP; open→post→balance→statement→close in `AccountEndpointDatabaseTest`; every write surface driven through the boundary, not only the service |
| 2 | All invariants in scope enforced at their catalogued rank | `PASS` | 19 of 19; the `DB-CONSTRAINT`/`DB-PRIVILEGE` claims proven against raw SQL and per-column grant sweeps, not application behaviour alone |
| 3 | Each in-scope invariant has a test demonstrated to fail when broken | `PASS` | 19 register rows, every named mutation performed by its owning task; `MutationDemonstrationTest` holds the rows to the code on every build since the flip |
| 4 | Failure scenarios enumerated and tested | `PASS` | Area 4 — twelve of twelve, scenario 11's covered-by-argument status stated |
| 5 | Concurrency correctness proven with real contention | `PASS` | Area 3 — nine contended decisions, each raced with one connection per simulated instance, outcomes counted in tables |
| 6 | Observability: planned meters exist and are verified on a running instance | `PASS` | All six §15 meters eager from a plain context (pinned guard until the flip, derived guard after it); dashboard row resolved against a live scrape (`P3-TSK-020`) |
| 7 | CI green | `PASS` | The battery after the status flip: 1121 hermetic / 683 database / 14 kafka; CI runs the identical gates on every push (per the standing no-watch rule, the post-commit run is not polled — the gates themselves are the ones this battery just executed) |
| 8 | Documentation matches the implementation | `PASS` | Area 7 — six drifts found by hand-diff, five corrected in this review, one recorded with its owner |
| 9 | `CURRENT_STATE.md` reflects reality | `PASS` | Updated by this review: phase status, M3.8 closed, the stale §Next Task replaced, change-log row added |
| 10 | Phase ADRs `Accepted` | `PASS` | ADR-0039…0042 — see below |
| 11 | No HIGH/CRITICAL known vulnerability | `PASS` | The dependency set is unchanged since the last green `dependency-scan`; no new runtime dependency was added in Phase 3 (verified: every lockfile delta this phase is module-internal) |
| 12 | Backlog complete or explicitly carried | `PASS` | 25 of 25; the carried remainders each name an owner (area 8) |

---

## The financial supplement F1–F8 — binding for the first time

Phases 0–2 recorded the supplement **not applicable** because no money moved. Phase 3 is the
phase it was written for, and it binds. The assessment below **re-checks** each criterion at
the gate rather than inheriting [`PHASE_3_FINANCIAL_SUPPLEMENT.md`](PHASE_3_FINANCIAL_SUPPLEMENT.md)
(`P3-TST-003`): each named test was verified to exist and to be named by a `MUTATION_TESTING.md`
§2 row, so the register guard holds every reference below to the code on every build.

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| F1 | Trial balance zero per currency, continuously asserted | **Met** | `TrialBalanceDatabaseTest#aCommittedJournalSweepsBalancedAndInjectedImbalancesAreDetected` — three injected shapes flagged per currency with a committed positive control; `finapp.ledger.trial.balance` published eagerly, NaN when unverifiable |
| F2 | Every balance reproducible from zero | **Met** | `BalanceDerivationDatabaseTest` against independent `BigDecimal` recomputation; `SustainedConcurrentPostingDatabaseTest#replayFromZeroEqualsTheProjectionThroughout` under ten instances |
| F3 | Financial history immutable for every writer | **Met** | Per-column privilege sweep + the unconditional append-only trigger binding the migrator; `ReversalDatabaseTest` proves the original byte-identical (PostgreSQL's own renderings) after correction |
| F4 | Money-moving commands idempotent under real concurrency | **Met** | `PostingServiceDatabaseTest#tenInstancesOneKey` — one effect counted; the approval's state-machine idempotency ten-way raced; `INV-IDEM-03` conflicts proven at HTTP |
| F5 | Duplicate external events produce no second financial effect | **Met — with the Phase 3 vacuity stated** | No external event produces a financial effect in this phase; the binding mechanism is the Phase 0/2-proven inbox (`P2-TSK-002`, `P0-TST-006`), and the first external financial consumer (Phase 5) inherits it. Stated rather than glossed, exactly as the supplement records |
| F6 | Contended financial decisions arbitrated by the database | **Met** | The area-3 table — nine decisions, nine arbiters, nine races with counted outcomes |
| F7 | Corrections are new effects, never edits | **Met** | `ReversalDatabaseTest` (referencing, bounded, original untouched); `AdjustmentEndpointDatabaseTest` (proposed, approved by a second person, posted as a new entry) |
| F8 | Every posting attributable and auditable | **Met** | `INV-LED-05` at `NOT NULL`; every write-path action emitted with the acting person (area 5); the adjustment's initiator and approver each named by their own record |

**The supplement has no open item.**

---

## The Phase 3-specific criteria — sixteen

§5's nine original criteria plus the seven the Phase 2 → 3 transition's extension added
(the backlog's "nine" is area 7's finding 5).

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | An unbalanced entry impossible at domain **and** database | `PASS` | `JournalEntryTest` unconstructible shapes + property sweep; `V004` deferred triggers refusing raw SQL at COMMIT |
| 2 | Posted entries and lines immutable at `DB-PRIVILEGE` | `PASS` | Per-column sweep over both tables; append-only trigger binding every role |
| 3 | The ledger is the sole writer of postings | `PASS` | `INV-LED-04`: one `PostingEffect` write path; module isolation both directions; `ProjectionVerification` as the raw-writer net |
| 4 | Balances derived from postings; projection never authoritative for a decision | `PASS` | `INV-BAL-01`/`-05`: no read existed until the declared readers arrived, each stating what it returns; hold/close decisions derive inside the lock — the projection-read mutation caught behaviourally |
| 5 | Replay-from-zero equals the projection under sustained concurrent posting | `PASS` | `P3-TST-001`'s storm: ≥25 sweeps over ≥200 commits, drift-free throughout, rebuild mid-storm losing nothing |
| 6 | Holds respect available balance under contention | `PASS` | `INV-BAL-04`: exactly-available accepted, one minor unit more refused, ten-way counted |
| 7 | Reversal references the original, is bounded, leaves it byte-identical | `PASS` | `INV-REV-01`/`-02`: `V009`'s trigger under the advisory lock for every writer; partials summing correctly under the ten-way race |
| 8 | One idempotent posting command; retries one effect | `PASS` | F4's evidence |
| 9 | The operational chart resolves every purpose in every supported currency | `PASS` | `OperationalChartDatabaseTest`: fifteen rows, every combination, before anything can post |
| 10 | `INV-LED-06` at the database: classification frozen once posted to | `PASS` | The two-layer freeze with the positive control between them; the trigger re-proven against the real `journal_line` |
| 11 | Account opening gated on `ACTIVE` verification; close leaves postings intact | `PASS` | `CustomerAccountDatabaseTest` (gate, per-decision authoritative read); `AccountClosingDatabaseTest` (history byte-identical and readable after close) |
| 12 | Posting authority a named permission; adjustments carry reason and audit | `PASS` | `LEDGER_POST`/`LEDGER_ADJUST` at their check sites; `INV-REV-04`'s reason in three reconciled places; both adjustment acts audited naming their own actor |
| 13 | Balance-dependent decisions derive inside the account lock | `PASS` | Holds and close both lock-then-look; the moved-outside-the-lock mutation performed and caught (`P3-TST-002`) |
| 14 | Drift and trial-balance metrics eager, absent-not-zero | `PASS` | `finapp.ledger.projection.drift` and `finapp.ledger.trial.balance`: NaN when unreadable, never zero — the mutation making NaN zero caught for each |
| 15 | A statement reconciles: opening + lines = closing | `PASS` | By construction (disjoint predicates), held to an independent recomputation (`StatementEndpointDatabaseTest`) |
| 16 | `LEDGER_MODEL.md` describes the implemented model; every catalogue `Phase: 3` invariant has a register row | `PASS` | The model document corrected where it was stale (area 7, finding 2) and now matches; nineteen of nineteen rows (area 6) |

---

## ADR-0039 … ADR-0042: accepted

All four are implemented, tested and load-bearing, and are moved to `Accepted` on the
standing precedent that **criterion 10 is a precondition of the gate, not a reward for
passing it**:

- **ADR-0039** (`READ COMMITTED`; postings as inserts; balance-dependent decisions take the
  account lock) — is the arbiter in six of area 3's nine races, and the rejected
  `SERIALIZABLE` retry loop never had to exist around a money-moving command.
- **ADR-0040** (flat typed chart, roll-up by attribute) — carried the seed, the derivations
  and `INV-LED-06`'s freeze with no recursive query anywhere on a reporting path.
- **ADR-0041** (transactional projection no decision may read) — its two rules held
  structurally: the projection was write-only until the declared readers arrived, and every
  decision derives inside the lock; the accepted contention cost is measured by the phase's
  races and the hot-row mitigation stays recorded.
- **ADR-0042** (four account concepts) — the boundary did its work: the product carries no
  balance, the accounting survived the product's close, and the `accounts → ledger` edge is
  a build-graph fact whose reverse is a Gradle cycle, demonstrated.

The status flip is recorded in each ADR file **and** in the index's status column
([`docs/adr/README.md`](../../adr/README.md)) — the second copy `P2-DOC-001` found stale,
checked deliberately this time. `DECISIONS.md` already carries all four entries.

---

## What the phase produced

Counted from the artefacts, not quoted from drafts:

| | Count |
|---|---|
| New modules | 2 (`ledger`, `accounts`) — platform total 7 with production code |
| Migrations | 13 (ledger `V001`–`V010`, accounts `V001`–`V002`, identity `V014`) |
| New tables | 8 (`ledger_account`, `journal_entry`, `journal_line`, `account_balance`, `hold`, `adjustment_proposal`, `adjustment_proposal_line`, `customer_account`) |
| New operations / paths | 9 operations across 7 new paths (platform total: 40 operations, 34 paths in `openapi.json`) |
| Auditable actions | 8, all emitted (`NOT_YET_EMITTED` = exactly the three Phase-15 outbox actions) |
| Permissions / roles | 2 permissions + 1 role (`LEDGER_OPERATOR` holds exactly both) |
| Event types | 5 (`JournalEntryPosted`, `HoldPlaced`, `HoldReleased`, `AccountOpened`, `AccountClosed`) |
| Aggregates | 5 (`LedgerAccount`, `JournalEntry` (+`JournalLine`), `Hold`, `AdjustmentProposal`, `CustomerAccount`) |
| ADRs | 4 (`Accepted`; platform total 42) |
| Invariants | 0 new (platform stays 82); **19 in scope, 19 register rows** |
| Backlog | **25 of 25** across 8 milestones |
| Mutations, probes and demonstrations | **138 across 22 items**, + 3 guard-teeth re-proofs; **1 survivor, correctly; 0 wrongly** |
| Tests after the flip | **1121 hermetic, 683 database, 14 kafka** |

And the number that distinguishes this phase from every one before it: **money exists** — a
verified customer can open an account, receive a posting, see the balance three ways that
each say which number they are, hold funds against it, have a mistake corrected without one
committed byte changing, and close the product with the accounting intact.

---

## What happens next

**The Phase 3 → Phase 4 transition** — its own act, performed with no application code
written. It must produce what the three prior transitions produced: an independent
completion audit of Phase 3, the distributed-systems and security audits, `PHASE_4_PLAN.md`
(transfers — the first flow where two customer positions move together, ADR-0039's
`FOR UPDATE` meeting its second caller and `INV-CON-02` going live), the elaborated backlog,
and the decisions Phase 4 cannot start without (the transfer/ledger transaction boundary and
compensation strategy — unresolved question 5, High risk). It also inherits this review's
finding 1 (the plan's unbuilt `LEDGER_READ` surface: schedule it or correct the
declaration) and the standing derive-the-ADR-index item.
