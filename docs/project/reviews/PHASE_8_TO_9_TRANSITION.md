# Phase 8 → Phase 9 Transition

**Conducted:** 2026-10-02 … 2026-10-03
**Parts:** integrated Phase 8 audit · financial correctness per flow · multi-instance audit ·
the settlement/reconciliation boundary · idempotency · consistency and atomicity · security ·
reconciliation correctness end to end · testing · architecture drift · blocking issues and
repair · Phase 8 completion · Phase 9 initialisation
**Constraint:** this transition ran under the rule that **CRITICAL and IMPORTANT Phase 8 defects
are resolved before the transition, never deferred as debt** — and under the owner's further rule
that financial, concurrency, security and distributed-system defects are not hidden as future
debt. So, like the two transitions before it, it wrote application code: the repairs in §11, each
tested, each broken on purpose to prove its test, each restored byte-identical and recorded. It
wrote no Phase 9 code — Phase 9 exists below only as documents.

---

## Verdict

| Part | Outcome |
|---|---|
| 1. Integrated Phase 8 audit | **`PASS` after repair.** Nine parallel read-only audits over the whole phase and an adversarial verification of every finding — each verifier told to refute and to default to refutation: **eleven CRITICAL** findings (four of them filed IMPORTANT and upgraded by their verifiers), **eighteen IMPORTANT**, thirty-six MINOR. Every CRITICAL and IMPORTANT repaired; the MINOR correctness, concurrency and security ones repaired; the rest recorded with owners |
| 2. Financial correctness | **`FAIL` as found** (a payout return credited twice; a duplicate line allocating by the late legs; a reopened bank item that could never re-park, jamming the time legs; a repudiation leaving reopened overdue value unowned, a mid-chain statement hole unowned, later-corrected value unexplained; rule-less lines never owned). **`PASS` after repair** |
| 3. *"Would Phase 8 remain financially correct if 10 instances executed relevant operations concurrently?"* | **`PARTIAL` as found** (the escalation worklist starving behind not-yet-due breaks; genuine bytes unreadmittable after a repudiation; the first gate found no CRITICAL multi-instance defect — the Phase 8 arbiters held). **`PASS` after repair**, re-audited over every repaired arbiter |
| 4. The settlement/reconciliation boundary | `PASS` — no build edge appeared between the siblings (ADR-0064 holds); every new need crossed as a reconciliation-declared port composed in `app` (`ReturnedPayouts`, `PayoutReturnFallbacks`, `Investigators`, `SettlementBatchRepudiations.lockChainAndReadSuccessor`); the one composition-ownership deviation is recorded with its owner (ARCH-P8-02 → `P9-TSK-014`) |
| 5. Idempotency | **`FAIL` as found** (one payout return, two credits — the person's fallback and the worker's application never met; a transfer not bound to its in-flight operation). **`PASS` after repair**, both orders and the true race proven on the payout row |
| 6. Consistency and atomicity | **`FAIL` as found** (an item's savepoint rollback restored rows but not the chunk's Java state; a correction blind to its own chunk's queued park; conditional-exit results discarded). **`PASS` after repair**; no atomicity is claimed across a boundary that lacks it |
| 7. Security | **`FAIL` as found** (an approver who could not see where a transfer sends money; card numbers passing the PSP reference classes and the spaced/dashed forms passing the note screens; seven reason doors unscreened; a four-eyes repudiation reversible by one controller, found by the re-gate itself). **`PASS` after repair** |
| 8. Reconciliation correctness | **`FAIL` as found** (§2's matching and repudiation defects live in the matcher's four legs and the repudiation's compensation). **`PASS` after repair** — the position, suspense and completeness proofs, the storm, the battery and the cash suites all green over the repaired legs |
| 9. Testing | **The fleet-wide hermetic tier green at 2255 tests across 357 suites and 16 modules, 0 failures**, beside every module tier (hermetic and database), the architecture tier, every own-container suite, the twelve proof-group suites, the classification guard and the document guards, each from a fresh run; the database and kafka tiers ran module-wide and per-suite, never fleet-wide, on the owner's standing instruction - the recorded deviation, as at both prior gates |
| 10. Architecture drift | Found and corrected: the expectation events carried a payload shape their own opener rejects (version 2 now carries no operation reference); reconciliation's self-loop causation corrected to the flow's root; LEDGER_MODEL's one-door adjustment claim corrected to both doors; the component register gained its missing expectation-opener, acceptance-intake and backfill rows; AUDITABLE_ACTIONS' readmission claim corrected; ten ADRs annotated with dated provenance for every behavioural correction |
| **Phase 8** | **`COMPLETE`**, confirmed after repair |
| **Phase 9** | **Entry gate: all twelve criteria hold → `READY`**; first task `P9-TSK-001` `READY`, not started |

Phase 8 was ruled `COMPLETE` by its exit review two days earlier
([`PHASE_8_REVIEW.md`](PHASE_8_REVIEW.md), `P8-DOC-001`). This is the second, independent pass the
gate model requires — and again **the review had ruled the phase complete while the phase was not
correct**: all eleven CRITICAL defects sat on paths every Phase 8 test passed over, five of them
in code the exit review had read.

---

## 1. Integrated Phase 8 audit

Phase 8 read as one capability — settlement evidence taken in whole and authenticated, recognised
once, matched deterministically against expectations the platform's own operations open, every
disagreement a classified, aged, owned break, resolved only by evidence or by controlled
four-eyes adjustment, operated without editing history — and against Phases 5–7, whose money it
explains. Nine read-only audits ran in parallel (reconciliation, settlement, multi-instance,
idempotency, atomicity, architecture, security, testing, and the debt register itself), and every
finding was verified adversarially. The audits returned seventy findings; thirty-four survived
their verifiers as real CRITICAL or IMPORTANT defects (several MINOR filings were upgraded, and
several IMPORTANT filings downgraded or refuted, by the verification).

| Area | Verdict | Re-checked after repair |
|---|---|---|
| Matching — the run, grace, rematch and reprocess legs | `PASS` after repair | The reach model judged on rows (`match_reach`, `V016`); fingerprint truth in every leg's basis; claimant order per leg; the drain rule; re-audited line by line by the re-gate |
| Suspense and parking | `PASS` after repair | A reopened item parks again (`V017`'s live-item uniques); an always-failing park is contained to its item, its grace stopped |
| Breaks and resolution | `PASS` after repair | No stranded break by reclassification; remainder siblings closed by every settling path; the approver sees what they approve |
| Batch and statement repudiation | `PASS` after repair | A repudiation re-raises what it reopens, owns the hole it cuts, frees the key it strands, refuses the four shapes it cannot compensate, and waits for its run to complete |
| The payout return | `PASS` after repair | One return, one credit — the worker and the person serialised on the payout row; the fallback waits for `COMPLETED` |
| Settlement intake | `PASS` after repair | Parse and accept failures back off per file and never follow another instance's edge; a retired source's three doors write nothing; the pull is bounded |
| Screening | `PASS` after repair | One shared screen at every reason door with its database twin; the PSP reference classes refuse instrument shapes in every grouping |
| Observability and audit | `PASS` after repair | The two long acts record their start atomically with their first effect; refused four-eyes attempts stay a recorded gap (Phase 15) |
| Documentation and registers | `PASS` after repair | §10 |

**The separation held throughout.** Settlement owns evidence and recognition; reconciliation owns
expectations, matching, breaks and resolution; the ledger owns the money; `app` composes. None of
the repairs moved these lines, and the one place composition owns what it should only call is
recorded, not preserved silently.

---

## 2. Financial correctness, per flow

Currency explicit, money in minor units with scale, no floating point, history immutable, every
correction a new entry — unchanged. **As found, the following flows could credit twice, strand or
misattribute value**, and each is repaired:

| Flow | Defect as found | Repair |
|---|---|---|
| Payout return | **IDEM-1 (CRITICAL)**: a person's fallback transfer left nothing the return worker checked, so a later report's repeat of the line — another fingerprint — credited the payable a second time | Both paths serialise on the payout row `FOR UPDATE`; the worker writes nothing while a person's transfer stands (`RETURNED_BY_PERSON`); the transfer is refused once the return stands — and, the repair's own find, it is admitted only for a `COMPLETED` payout: in flight it waits, `FAILED` it is refused outright (the released hold already returned the value) |
| Break resolution | **IDEM-2 (CRITICAL by verification)**: a transfer out of a `MISSING_INTERNAL` break was not bound to its in-flight operation, whose own completion credits the party unconditionally | `TRANSFER_TO_ACCOUNT` and `RECOGNISE_GAIN` re-ask the platform's records over the item's frozen keys, at proposal and at approval under the locks, refused while the operation is `IN_FLIGHT` or `COMPLETED`; the card capture whose answer was lost is now reachable by our own minted reference, so it waits too |
| Late matching | **REC-1 (CRITICAL)**: the rematch and reprocess legs judged a parked fingerprint duplicate with the fingerprint forced false, so a duplicate could allocate — including taking a payout return ahead of the genuine line | Every late leg judges with the item's true fingerprint, stored in its basis; a `REPEATED_FINGERPRINT` park and any item with a `DUPLICATE` decision are off the late worklists |
| Run matching | **REC-2 (CRITICAL)**: a rule-less allocating line waited `UNMATCHED` for ever — never graced, never broken, owned by nothing | Parked at run time (`UNKNOWN_EXTERNAL`/`GRACE_EXPIRED`), owned at once; the storm now passes on the production path, its hand-written fixture removed |
| Repudiation | **REC-3 (CRITICAL)**: a bank item a repudiation reopened could never re-park — the once-ever suspense uniques refused it and the failed park jammed the time legs for every later item | `V017`: one LIVE suspense item per external item, one `RECON_PARK` row per (item, park); a failing park is contained to its own item, grace stopped, the leg draining on |
| Repudiation | **REC-4 / SET-1 (CRITICAL)**: a repudiation reopened an already-overdue expectation with no break — ageing's one-way `overdue_since` meant nothing would ever own it | The approval raises a fresh `MISSING_EXTERNAL` for each reopened overdue expectation, following the closed break, proven against ageing re-run |
| Repudiation | **REC-7 (CRITICAL by verification)**: repudiating a batch whose parked line a later batch's correction had offset left the released value unexplained | The fourth refused shape: `RepudiationNotSupported` at proposal and at approval, re-judged under the locks, nothing written |
| Statement chain | **SET-2 (CRITICAL by verification)**: repudiating a mid-chain statement opened a cash-chain hole with no `STATEMENT_GAP` | The approval locks settlement's source row in the acceptance's own order and raises the gap on the successor's run; the genuine statement closes it `EVIDENCED` |
| Fee checking | The latent `FeeCheck` cross-currency throw (the Phase 9 design's §27.2.1): a fee line whose original is in another currency crashed into an untyped `ITEM_ERRORED` | The currency pre-filter: a gross the fee's own currency cannot price prices nothing (F1), the whole fee at issue as `FEE_MISMATCH` — deliberately not `CURRENCY_MISMATCH`, whose every kind disposes of parked value a fee line does not hold |

**One bound is recorded, not silent**: an instant-rail line carrying only the scheme's reference,
its optional end-to-end reference omitted, answers `UNKNOWN` while its execution is in flight (the
claim is written at completion), so a four-eyes transfer can pre-empt the completion — which then
surfaces, by construction, as an overdue `MISSING_EXTERNAL` for a person's claw-back (the
re-gate's NEW-IDEM-1; ADR-0071 §2 states the bound, the debt row names the completion-side
detection that closes it).

---

## 3. Multi-instance audit

Ten instances of everything, asked again over every repaired arbiter. The first gate found the
Phase 8 arbiters themselves held — no CRITICAL multi-instance defect — and two IMPORTANT gaps,
both repaired; the repairs were then re-audited as new contention surface.

| Concern | As found | After repair |
|---|---|---|
| Ten chunkers, try-lock bypassed | **Wrong in the seam the register's own claim invites**: the run leg ignored its conditional exits, so a second chunker re-decided items and the loser left a `DUPLICATE_EXTERNAL` break no kind could close (REC-10/MI-4/IDEM-3) | `lockItems` re-reads status under the item locks and returns only the ids still `PENDING`; a conditional exit that updates no row throws and the item's savepoint contains it — ten bypassed chunkers across chunk boundaries leave one decision per item and zero orphan breaks |
| Escalation | **Wrong**: the worklist took the oldest breaks first, so older not-yet-due breaks starved a due one for ever (MI-1) | Due-band selection in SQL on the database clock, paged by `(raised_at, id)` |
| Readmission after repudiation | **Wrong**: a mis-normalised batch's genuine bytes could never be re-accepted (MI-2) — and the repair's first form was itself reopened by the re-gate (NEW-SEC-1, §7) | An `ACCEPTED` original whose batch is `REPUDIATED` is readmissible while no live batch holds its identity — and the re-gate then closed the repair's own hole (NEW-SEC-1): the readmission of a repudiated batch's file inherits NOTHING (settlement `V014` restates `V009`'s walk with the repudiated bar), so acceptance waits for an attester distinct from every submitter along the chain - reinstating a repudiated batch takes two people again |
| Intake failure writes | **Wrong**: a parse failure rolled back on one instance was recorded after another instance's `PARSED` edge — a false history row (MI-5); the accept leg had no backoff, so poisoned files starved every source (MI-7) | The failure transaction locks the file row and writes only while the status still stands; `V013` gives the accept leg the parse leg's twins (`accept_failures`, `next_accept_at`), proven with ten always-failing files and one behind them |
| Repudiation vs its run | **Wrong**: a repudiation was admitted while the batch's run was `BLOCKED`, so a later requeue completed a run over `REPUDIATED` items (MI-8) | `BatchNotDisposed` at proposal, re-read under the advisories at approval |
| The payout row | the IDEM-1 race (§2) | Both orders proven with held connections and the true race counted: exactly one credit |
| Lock orders | — | Every new lock sits in the one order its row's other takers already use, restated in `DISTRIBUTED_EXECUTION.md` §3 with its arbiter; the acceptance's source-row breadth is a recorded row (MI-6), correctness unaffected |

**Answer: *"Would Phase 8 remain financially correct if 10 instances executed relevant operations
concurrently?"* — `PASS`**, re-audited after repair over the repaired arbiters. Every contended
decision's arbiter is PostgreSQL's — the conditional transitions, the uniques, the row locks, the
deferred sums — and every repaired one is raced in a counted test.

---

## 4. The settlement/reconciliation boundary

ADR-0064 holds: no build edge appeared between the siblings. Every repair that needed the other
side crossed as a port declared by reconciliation and composed in `app` — the payout lock
(`ReturnedPayouts` over merchant's own applier row), the person-transfer read
(`PayoutReturnFallbacks`), the assignee judgement (`Investigators` over identity's public read),
the statement chain's successor (`lockChainAndReadSuccessor` on the existing
`SettlementBatchRepudiations` port). Provider specifics stayed in the adapters; the screen lives
in `sharedkernel` because settlement and reconciliation share no edge. The one place `app` owns
intake rules it should only call is ARCH-P8-02's recorded row, owned by the first second caller
(`P9-TSK-014`).

---

## 5. Idempotency

| Operation | Mechanism | As found |
|---|---|---|
| Payout return application | The payout row `FOR UPDATE`, the `payout_return` row, the person-transfer read | **Wrong** (IDEM-1, §2); now one credit under both orders and the race |
| Fallback transfer | The same row; `ReturnAlreadyAttributed`; `OperationNotTerminal` for an in-flight payout | **Wrong**; repaired with the repair's own residual |
| Transfer / gain on grace-typed breaks | The lookup re-asked at proposal AND approval under the locks | **Wrong** (IDEM-2); the card case closed by resolving our own minted references; the scheme-claim bound recorded (§2) |
| The matcher's exits | Conditional transitions whose results are now load-bearing (`exitOrThrow`) | **Wrong** (IDEM-3): every exit's boolean was discarded; now a lost exit rolls the item back into containment |
| Duplicate lines, files, webhooks | Fingerprints judged truthfully in every leg; the claim keys; the readmission rule | **Wrong** for the late legs (REC-1); repaired |
| Replays | Every decision's basis stored whole; the battery's replays `IDENTICAL` over the repaired legs | Held — and the bases widened (the true fingerprint, the fee's gross) so a replay cannot bless a wrong verdict |

---

## 6. Consistency and atomicity

| Workflow | Boundary | As found → repaired |
|---|---|---|
| An item's attempt in any leg | The item's savepoint | **ATOM-02**: rollback restored rows and counts but not the chunk's Java state — queued parks and releases survived, claimant ranks counted; the savepoint now carries and restores all of it, and the run leg re-reads its remainders from the restored rows |
| A correction in its original's own chunk | One transaction | **ATOM-01/ATOM-04**: a top-up never lowered the in-memory remainder; an exact opposite correction could not see its own chunk's queued park — both now first-class, the offset identical byte for byte to the two-run record |
| A time leg's posting phase | `postPhaseContained` | **REC-3's atomicity half**: a failed phase re-posts piecewise, offsets together, each park alone under its own savepoint; a park failing again strands only its item |
| The repudiation approval | One transaction, the §3 lock order | Held as built; extended with the source-row lock (SET-2), the key release (REC-8), the re-raise (REC-4) and the run-status re-read (MI-8), each inside the same transaction |
| Start-of-act audit | The act's own first transaction | **SEC-08**: the opening position and the fetch recorded nothing until their end; the start now commits with the first effect, proven by crash injection |

No atomicity is claimed across the PSP, the scheme, the payout provider or the bank; what holds
them together is unchanged — dispatch-before-call, conditional transitions, claims, sweeps — plus
the repaired containments above.

---

## 7. Security

- **The approver could not see where a `TRANSFER_TO_ACCOUNT` sends money** (SEC-01, CRITICAL):
  `GET /v1/operator/reconciliation/resolutions/{id}` now renders the operands — the target
  account with purpose and owner, the offset partner, the chosen expectation, the frozen lines —
  and an approval may echo them; a mismatch is `409 ResolutionStale` with nothing written.
- **Card numbers passed the PSP reference classes** (SEC-02) and **the spaced/dashed forms passed
  the person-text screens** (SEC-03): one shared screen (`InstrumentShapes`) — Luhn-valid card
  numbers contiguous or grouped, account identifiers contiguous or printed under mod-97, the
  platform's own UUIDs masked — at the formats, the notes, the narratives and every reason door
  (SEC-04), with a PL/pgSQL twin as CHECKs (`V012`, `V019`) proven against a 4,000-text corpus.
  The re-gate's own NEW-SEC-2 widened the rule to the machine groupings: a card run grouped by `:` or `_` is no reference shape (`ReferenceShape`), no escape from the domain screen (`InstrumentShapes`' card scan, strictly wider than the database twins - the parity bound stated in the class and the register), refused by the PSP format's translated second walk, and `MALFORMED` - stored nowhere - in the payout, scheme and statement formats, whose inline screens carry the same widening.
- **A four-eyes repudiation was reversible by one controller** (the re-gate's NEW-SEC-1):
  a readmission whose original is an `ACCEPTED` file of a `REPUDIATED` batch inherited the original's attestation, so one `RECONCILIATION_ADMINISTER` holder could re-post a four-eyes-repudiated recognition. Settlement `V014` gives the authentication walk the repudiated bar - exactly the `DECLINED` rule - so the readmitted file waits, acceptance passes it over, the readmitter's self-attestation is refused by the existing distinct-person rank, and a distinct attester completes the pair; proven for every writer and over HTTP, the `REJECTED` inheritance unchanged, the probe caught.
- **The break assignee was never checked** (SEC-06): an assignee must be an ACTIVE identity
  holding `RECONCILIATION_INVESTIGATE`, judged through a port before any lock; a card number
  pasted as one is refused with nothing written, published or echoed.
- **The pull collector buffered an unbounded provider body** (SEC-07): bounded at the door's own
  limit plus one byte; a runaway answer is cancelled at the first byte past it.
- **Two privileged acts audited only after their effect** (SEC-08): §6.
- Recorded with owners: refused four-eyes attempts still leave no audit outcome (the Phase 7 row,
  widened); the actuator rows; the operator-as-`CUSTOMER` row — none a Phase 8 regression.

---

## 8. Reconciliation correctness, end to end

For every relevant line the platform still identifies everything by stored identifiers alone, and
the repairs closed the gaps between identification and ownership: every allocating line is owned
at run time or parked owned at grace; every duplicate is judged on its true fingerprint; every
reopened value re-parks; every reopened overdue expectation is re-owned; every repudiation hole is
cut visibly; every freed key is takeable by the genuine evidence; every remainder break closes
with the allocation that settles it; and the whole of it replays `IDENTICAL` from stored bases.
The storm, the battery, the twelve proof-group suites and the cash suites all run green over the
repaired legs — the storm now entirely on production paths.

---

## 9. Testing — and the second gate

**The battery, over the merged repairs** (every verdict from a fresh run's test-results XML):

| Tier / suite | Tests | Failures |
|---|---|---|
| **Fleet-wide hermetic `test`** | **2255 across 357 suites, 16 modules** | **0** |
| reconciliation hermetic / database (module-wide) | 172 / 230 | 0 |
| settlement hermetic / database (module-wide) | 186 / 88 | 0 |
| sharedkernel · merchant · platform hermetic | 213 · 137 · 181 | 0 |
| app hermetic · architecture | 645 · 143 | 0 |
| the storm · the battery · repudiation · resolution | 1 · 7 · 20 · 5 | 0 |
| payout return · late evidence · bank / payout / scheme cash | 18 · 4 · 5 / 5 / 4 | 0 |
| E2E · pull · readmission · administration · investigation · routes | 9 · 12 · 10 · 7 · 5 · 8 | 0 |
| the screening suite · the twelve proof-group suites in one container | 2 · 64 | 0 |
| the column-classification guard · the document guards | 5 · 128 | 0 |

**After the re-gate's two security repairs** (`NEW-SEC-1`, `NEW-SEC-2`), re-run fresh: settlement
hermetic 192 and database 88, sharedkernel 218, reconciliation hermetic 174 and database 230 - 0
failures; each repairing agent ran its own touched tiers green in its worktree (the app hermetic
645, architecture 143, repudiation 20 and readmission 10 among them); the register, ADR-index and
glossary guards re-ran 44 across 10, 0 failures, over the final tree with Phase 9 landed. **One
recorded deviation**: the app hermetic and architecture tiers' last pass over the final tree was
stopped on the owner's instruction, the owner ruling them passed; its one failure before the stop
was a register citation (a nested test case cited by method), corrected and re-proven by the
guard above. The fleet-wide database and kafka tiers ran module-wide and per-suite, never
fleet-wide, on the owner's standing instruction - the deviation both prior gates recorded.

**Probes.** Every repair was broken on purpose and caught by its own test from a fresh run —
seventy-two probe applications across the two repair rounds, the residuals, the FeeCheck pre-filter
and the re-gate's repairs, every restore verified byte-identical by sha256. The probe evidence is
recorded in `MUTATION_TESTING.md` §2 (thirty transition rows, the guard parsing every named
test).

**The re-gate.** The gate was then repeated adversarially over the repaired tree: nine dimension
auditors re-judged all seventy original findings against the code — not the repair reports — and
hunted regressions on the changed surface; every reopened or new claim went to a refutation
verifier. **All nine dimensions `PASS`.** Every CRITICAL and IMPORTANT finding is confirmed
`FIXED` with file-and-line evidence; the re-gate surfaced five real new MINOR findings — two
security ones repaired at once (§7), the rest verified bounded and recorded with owners (§11) —
and one refuted claim died as filed.

---

## 10. Architecture drift

Corrected with dated provenance in place: ADR-0065 §10 (the repudiation's continuity, the run
rule), ADR-0066 (the screen's points, the backoff, the readmission), ADR-0067 §6/§8, ADR-0068
(the reach on rows, the claimant order, the arbiter table, the correction bullet), ADR-0069 (the
case file, the assignment, the screen), ADR-0070 §§2/10/11, ADR-0071 (the operand read, the
payout bind, the recorded `UNKNOWN` bound), ADR-0073 §5 (the completed-payout rule);
`FINANCIAL_INVARIANTS.md` (INV-REC-04's per-leg order, INV-SET-06/07 as built);
`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`; `RECONCILIATION_MODEL.md`;
`DISTRIBUTED_EXECUTION.md` §3 (the late legs' row rewritten for the reach model, the repudiation
and intake rows, the three missing register rows added, the lock-order row extended);
`LEDGER_MODEL.md` (both adjustment doors); `AUDITABLE_ACTIONS.md` (the readmission truth, the two
start actions); `ERROR_CONTRACT.md` (+`BatchNotDisposed`, `OperationNotTerminal`,
`ReturnAlreadyAttributed` and the door notes); `DATA_CLASSIFICATION.md` (+`match_reach`, the
`V013` columns, the key-release column); `MODULE_ARCHITECTURE.md` (the ports); the expectation
events at version 2 with the flow-root causation; `EVENT_ARCHITECTURE.md`'s rule now held by the
code it describes.

---

## 11. Blocking issues and repair

The full finding-by-finding record — thirty-four verified CRITICAL/IMPORTANT findings, each with
its repair, its test and its probe — is carried by `MUTATION_TESTING.md` §2's transition rows and
the per-row dated notes in the documents above; the shape of each repair is in §§2–8. Summary:

| Class | Count | Disposition |
|---|---|---|
| CRITICAL (verified) | 11 | All repaired: REC-1…4, REC-7, SET-1, SET-2, IDEM-1, IDEM-2, ARCH-P8-01 (the event shape that jammed ageing), SEC-01 |
| IMPORTANT (verified) | 18 | All repaired: REC-5, REC-6, REC-8/ATOM-03, SET-3, MI-1, MI-2, ATOM-01, ATOM-02, SEC-02, SEC-03, T-3, and the debt-dimension findings (the two clock clauses, the reselection, the claimant order, the duplicate-takes-return, the reclassification strand, the unscreened reasons) |
| MINOR, repaired | 15+ | REC-9, REC-10/MI-4/IDEM-3, ATOM-04, MI-5, MI-7, MI-8, SEC-04…SEC-09, T-1, T-5, T-6, T-7, ARCH-P8-03/04/05, the FeeCheck pre-filter, and the re-gate's NEW-SEC-1 and NEW-SEC-2 |
| MINOR, recorded with owners | 9 | REC-11 (investigation routes), NEW-REC-1 (the duplicate park after a repudiation), NEW-IDEM-1 (the scheme-claim bound), NEW-ATOM-1 (the faulting residual's record), MI-6 (the acceptance lock's breadth), ARCH-P8-02 (`P9-TSK-014`), SEC-05 (the widened refused-acts row), T-2's schema backstop, T-4's enumeration — each a `CURRENT_STATE.md` row with risk, trigger and owner |

**The gate was repeated after repair** (§9): every area re-read against the repaired code, the
ten-instances question answered again, the battery re-run whole. **Phase 8 passes.**

---

## 12. Phase 8 completion

**Phase 8 is `COMPLETE`** — ruled by `P8-DOC-001` and confirmed here after repair. **Delivered**:
the platform proves, continuously and per counterparty, position, currency and item, that its
internal financial state matches what the card PSP, the instant scheme, the payout provider and
the platform's bank say happened — evidence taken whole and authenticated, recognised once,
matched deterministically under versioned rules, every disagreement a classified, aged, owned
break resolved only by evidence or four-eyes adjustment, every operation conducted without
editing history, and now correct under the duplicates, reopenings, repudiations, races, crashes
and hostile inputs this transition threw at it. **Decisions**: ADR-0064…0073 `Accepted`, amended
with this transition's provenance where it changed what they describe. **Remaining non-blocking
debt** is recorded with owners; none is financial-correctness debt — the one financial bound
(NEW-IDEM-1) is surfaced by construction and named in ADR-0071 with the design that closes it.

---

## 13. Phase 9 initialisation

**Objective.** Move money between currencies and across borders with the same provability: a
quote that is a frozen posting plan, conversion booked at acceptance in one local transaction
with a decoupled back-to-back cover, cross-border payments as holds and corridor acceptances with
beneficiary screening before pricing, returns credited in the currency received, and FX trade and
corridor reconciliation on the Phase 8 machinery — no new break types, no reconciliation that
converts currency. Planned in [`PHASE_9_PLAN.md`](../PHASE_9_PLAN.md); decided in ADR-0074…0083
(`Proposed`); the machines in
[`FX_AND_CROSS_BORDER_LIFECYCLES.md`](../../domain/FX_AND_CROSS_BORDER_LIFECYCLES.md). The design
was reached by a judge panel of three independent designs, synthesised, checked by a completeness
critic whose twenty-nine gaps were resolved under recorded decisions, drafted into the repository
artefacts by six writers, re-checked by a consistency critic, and integrated against the document
guards before landing.

**Entry gate** (`PHASE_GATES.md` §2):

| # | Criterion | Holds |
|---|---|---|
| 1 | Hard dependency phases `COMPLETE` | Yes — Phase 8 (confirmed here, §12), and every phase before it |
| 2 | `DELIVERY_PLAN.md` §Phase 9 current and specific | Yes — the addendum, which also resolves §18's FX-deferral wording: trade and corridor reconciliation are Phase 9's, FX P&L and revaluation Phase 14's |
| 3 | Bounded contexts and aggregates identified | Yes — `PHASE_9_PLAN.md` §3–§4: `fx` and `crossborder`, two new modules, and the changed ones |
| 4 | Invariants identified by ID | Yes — ten new and thirteen restated, read from the catalogue (**120 invariants**) |
| 5 | Lifecycles drafted | Yes — every machine in the lifecycles document, the born-once facts included |
| 6 | Transaction and consistency boundaries stated | Yes — `PHASE_9_PLAN.md` §3 (T-a…T-g with the lock order) and each task |
| 7 | Idempotency stated for every money-moving command | Yes — §4 and each task |
| 8 | External dependencies and failure modes listed | Yes — §14's sixty scenarios |
| 9 | Security, audit and reconciliation implications stated | Yes — §11, §12 |
| 10 | Backlog at task granularity with acceptance criteria | Yes — thirty items plus `X-TSK-013`…`-015`, the twenty-three fields each |
| 11 | Required decisions have ADRs at least `Proposed` | Yes — ADR-0074…0083 |
| 12 | `CURRENT_STATE.md` names the phase active | Yes — Phase 9 `READY`, `P9-TSK-001` the current task |

**What Phase 8 handed over, disposed** (`PHASE_9_PLAN.md` §2): the fleet battery — run here, §9;
the `FeeCheck` cross-currency throw — repaired here, §2; the fee-batch 0/3-minor deferral — a
debt row owned by `P9-TSK-003`; the instance-stamped send permits — `X-TSK-013`'s premise,
recorded, scheduled in M9.9; the four v1 rule sets' missing JPY/BHD pricing — owner decision O6's
v2 successors.

**Exit criteria**: `PHASE_GATES.md` §5 Phase 9 — the five original criteria extended by the
transition's measurable ones, each naming its owning tasks.

**First task: `P9-TSK-001`** — `READY`, not started.
