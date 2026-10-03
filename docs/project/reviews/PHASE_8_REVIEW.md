# Phase 8 Exit Review — Settlement and Reconciliation

**Conducted:** 2026-10-01 (`P8-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 8-specific criteria, read from the gate at
review time)
**Phase objective under review:** the platform knows, for every money movement it records, whether
the outside world agrees — every settling completion opens its expectation in the transaction that
posted it; four counterparties' evidence (a PSP report, an instant scheme's cycle report, a payout
provider's report and the bank's statement) is received screened and authenticated, parsed whole and
recognised once; matching is deterministic, versioned and explainable; every discrepancy is a
classified break, every unexplained value an owned and aged suspense item, every correction a
four-eyes resolution through the ledger; and cash moves only on the bank's own statement.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`**, all eight only after this review's corrections — see *What the review found* |
| Universal criteria (12) | **12 `PASS`**, one (7) with a recorded deviation, one (10) **met by this review's own act** |
| Financial supplement (F1–F8) | **8 `Met`** — re-assessed at the gate, never inherited; F2 after the projection verification this review added, F8 after the review ruled every Phase-8-owned debt row |
| Phase 8-specific criteria | **28 `PASS`** — 7 original + 21 added by the Phase 7 → 8 transition, counted from the gate; the Testing bullet's full-battery clause as a recorded deviation |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — every contended decision in `PHASE_8_PLAN.md` §7 with its PostgreSQL arbiter and its counted test, six of them raced for the first time here |
| **Verdict** | **Phase 8 `COMPLETE` (2026-10-01)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → **flip the status, which
is the guarded act** → re-run the battery → finalise with counted numbers.

**The assessment was six read-only audits, widened to sixteen readers, and it found more than any
task's gate had.** The audits read ADR-0064…0073 against the code (one reader per pair, and the
first pair read twice), mapped all 136 gate claims to the tests that prove them, enumerated every
Phase 8 route with its permission and negative, diffed nine documents and the javadoc against the
implementation, walked every invariant, register row and debt row, and walked the money end to end
on three rails. They found **two CRITICAL defects - a guarantee ADR-0069 claimed for every writer
that no database rule held, and a gain admitted under a rule set its value was never parked under -
a Phase 8 invariant with no register row that would have failed the flip, four audited acts never
asserted, twenty-nine gate claims with a partial test, drift in all ten ADRs and in some 150
statements across the documents, and eleven Phase-8-owned debt rows standing unruled**. None was
waived. Each was corrected here, in code or in text, and every correction and every new test was
broken on purpose: **thirty-eight demonstrations, thirty-six caught at the first run, and the two
that were not were probes aimed at the wrong site - recorded as no verdict, re-aimed, caught**.

---

## What the review found, and what it did about it

| Severity | Finding | Done |
|---|---|---|
| **CRITICAL** | **"A `RESOLVED` break has exactly one `APPROVED` resolution naming it — for every writer, by a deferred trigger" (ADR-0069 §6) was held by no database rule.** `finapp_app` holds `UPDATE (status, resolved_at)` on `reconciliation.break`, and the machine trigger admits `OPEN → RESOLVED`: a raw update closed a break with no record behind it | **Built: reconciliation `V015`.** Every new `RESOLVED` `break_event` must carry `resolution_id` (a deferred key to the resolution; history untouched), and a deferred constraint trigger refuses, at commit, a break reaching `RESOLVED` — by update or born so — unless such an event names an `APPROVED` resolution. The first design (a resolution naming the break, or a repudiation closure) would have refused two legitimate paths the documentation audit found — a remainder sibling and an offset partner closed by one approval — so the link is the closing event's own. **Stated limit, recorded debt (Phase 15):** the trigger proves an approved resolution stands behind every closure, not that it is that break's own. Probed six ways, each rule alone |
| **CRITICAL** | **A gain was judged against the source's ACTIVE rule set, not the one its owning break pins** (ADR-0070 §4 promised the pinned version). A newly activated rule set with a shorter `gain_min_age_days` reached value already parked under the old one | `JdbcResolutionStore.gainEligible` reads the owning break's `rule_set_id`, at proposal and at approval; proven both ways (a 90-day pin refuses beside a 5-day successor; a 5-day pin admits beside a 90-day one). Probed |
| **IMPORTANT** | **`INV-IDEM-02` - Phase 8 by its catalogue line - had no register row**; the flip would have turned the guard red. Its demonstration had been performed by `P8-TSK-008` and filed under `INV-SET-04` alone | A row filed citing that probe; nothing re-performed. And the §4 rows for `P8-TST-001` and `P8-TST-002`, which the flip also demands, written |
| **IMPORTANT** | **Four audited acts were never asserted positively**: `settlement.SettlementFileUploaded`, `reconciliation.BreakNoteAdded`, `.BreakEvidenceLinked`, `.RunReplayed` — catalogued and emitted, but the tests checked only that bodies stayed out | One count each (1, 1, 1 and ten racing replays → 10). Probed |
| **IMPORTANT** | **Twenty-nine gate claims had a partial test** (the audit's map: 136 claims, 101 full, 0 missing): no failure injection for seven local transactions; five contentions never raced (a different file for one batch, attest against decline, manual match against the matcher, allocation against ageing, ten repudiation proposers); parking with the try-lock bypassed and ten distinct approvals over shared projection rows never raced; a line repeated across files, deterministic claimant order and a replay over the battery untested; a chargeback ahead of its webhook; a scheme line typed through the real lookup; a second presentment; the 100-defect bound in three formats; no projection verification at rest | Twenty-seven closed by new tests (`AtomicityByFailureInjectionDatabaseTest`, `MatcherRacesDatabaseTest`, `ClaimantOrderAndReplayDatabaseTest`, `IntakeAtomicityAndRacesDatabaseTest`, `FormatDefectBoundTest`, `LateEvidenceAndBreakTypingDatabaseTest`, a ten-proposer case, the storm's at-rest `ProjectionVerification`); two answered by construction and stated, not tested — `RecordAlreadyMatched` is unreachable through any race (the row locks serialise before the allocation insert), and the real detector cannot raise `AMBIGUOUS_MATCH` (three uniques give every path at most one candidate), so that type is defence in depth today. Probed (22, two after re-aiming) |
| **IMPORTANT** | **`INV-SET-01`'s Phase 8 subject had no demonstration** — no test showed an operation reading `REPORTED` before cash | A hop-1 check in `BankStatementCashDatabaseTest`. Probed twice — the wiring caught only by the new check |
| **IMPORTANT** | **Eleven Phase-8-owned debt rows stood unruled** (F8) — one whose owning task closed unpaid, one paid but never struck, three "for the exit review to schedule" | Each ruled: one paid here (the PSP format's crash past its defect cap, probed), one struck as paid, nine re-owned to Phase 15 (or Phases 13 and 15) with reasons, one merged; three stale rows from earlier phases struck or re-owned; five new rows recorded |
| MINOR | `settlement.file_event.reason` classified `INTERNAL` while it carries operator prose | `CONFIDENTIAL` |
| MINOR | Drift in all ten ADRs, in `RECONCILIATION_MODEL.md` (15), `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` (15 + 9), `PHASE_8_PLAN.md` (29 + 12), the `DELIVERY_PLAN.md` addendum, the glossary (nine terms missing) and domain model, `MODULE_ARCHITECTURE.md`, `DISTRIBUTED_EXECUTION.md` §3 and 22 javadoc sites | Every statement made true — area 7 |

## The flip

Recording Phase 8 `COMPLETE` is the guarded act: the register guard begins demanding a §2 row for
every `Phase: 8` invariant and a §4 row for every `P8-TST` item, and `PlannedMetersExistTest` reads
`PHASE_8_PLAN.md` §15 through the generic union. **It surfaced one defect, the review's own**: a row
this review had just written named a nested test class the parser cannot read (`$` in the
reference) — corrected, and the guard's twin check then refused the method on the top-level class;
the row now names the class. Against the real status `MutationDemonstrationTest` and
`PlannedMetersExistTest` passed (41 tests across 9 suites under the run's filters). **And it was
proven non-vacuous against the real status**: with `INV-REC-10`'s two rows removed, the guard
failed reporting `(currently 8)` and naming exactly `["INV-REC-10"]` — an invariant it demands
only once Phase 8 is complete. The register was restored byte-identical (sha256-verified). Without
`INV-IDEM-02`'s row, filed before the flip, the same guard would have failed naming it.

---

## Area 1 — Scope: what the phase set out to build, and what it built

Twenty-seven items across eight milestones (M8.1–M8.8), every one `COMPLETE`; the three deferral
candidates (`P8-TSK-021` pull, `-019` payout returns, `-023` batch repudiation) were all built, so
transition decision O6 was never exercised and the gate's *Ingestion*, *Replay and reprocessing*
and *Security* conditionals read as met by the tasks. Migrations: settlement `V001`–`V010`,
reconciliation `V001`–`V015` (`V014` `P8-TST-002`'s correction, `V015` this review's), ledger
`V015`–`V018`, merchant `V008`. **`PASS`.**

## Area 2 — Walk the money end to end

A card capture, an instant pay-in and withdrawal, and a merchant payout and its return, each walked
hop by hop through the code — completion posting and expectation in one transaction, the report
received, parsed and recognised once, the allocation, the remittance, the bank statement's credit,
the cash proof — with the class, the transaction boundary, the arbiter, the entry and the test of
every hop. All three rails are driven end to end in one test, the storm. **Findings, recorded:**
the hop suites seed the completion for card and instant (the storm joins them); the applied return
reaches cash only in the storm; no ten-way payout completion asserts one expectation (the
conditional complete arbitrates and `ON CONFLICT` backstops). **`PASS`.**

## Area 3 — Multi-instance correctness

Every §7 contention matched to its PostgreSQL arbiter and its counted test; the six never raced
before are raced here (above). **Measured, not assumed:** with the per-source try-lock bypassed
across a source's runs no value is over-allocated, but the break census moves (`P8-TST-001`) — the
census is the lock's, the money the schema's (`DISTRIBUTED_EXECUTION.md` register 4). **Recorded as
debt:** the keyed and value-date rematch clauses compare two instances' clocks; claimant order
differs by leg; a duplicate line can take a payout return first — value never wrong in any of
them. **`PASS`.**

## Area 4 — Failure behaviour

All forty-four of `PHASE_8_PLAN.md` §14 have a test (§14.15, a chargeback in the file before its
webhook, only since this review); the crash points — mid-parse, between parse and accept,
mid-acceptance after the posting, between acceptance and the first chunk, mid-chunk — are the
storm's, each resumed elsewhere with nothing partial; every local transaction of the atomicity
table is now proven all-or-nothing by failure injection. **`PASS`.**

## Area 5 — Security and audit

Forty-four Phase 8 routes, every one with its register row, an OpenAPI entry and a negative of its
own; four permissions granted by two roles whose grants are pairwise disjoint; sixteen confined
credentials; the column guard over both schemas; no amount in any tag, span, log or audit detail.
**Ruled:** approvals (`ResolutionApproved`, `SettlementFileAttested`) carry no required reason —
a decision, stated; the operator actor-type debt **carried and widened** — four-eyes is provable
from the rows today (every distinctness rule compares identity ids), but Phase 8's frozen
`*_by_type` columns make the eventual fix forward-only. **Recorded as debt:** six person-written
reasons are unscreened for PAN/IBAN shapes and `reconciliation` has no every-column needle sweep.
**`PASS`** after the four audit assertions.

## Area 6 — Invariants, the register, and the demonstrations

Twenty-two `Phase: 8` invariants read from the catalogue with the guard's own token regex (thirteen
existing, nine new), every one now with rows; §4 rows for both `P8-TST` items; this review's
fifteen new §2 rows. The register's three "survived" verdicts are honest and none is a Phase 8
invariant's only row. **`PASS`.**

## Area 7 — Documentation accuracy

All ten ADRs corrected against the code and accepted; `RECONCILIATION_MODEL.md`,
`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`, `PHASE_8_PLAN.md`, the `DELIVERY_PLAN.md` addendum,
the glossary (nine terms added), the domain model, `MODULE_ARCHITECTURE.md` §4/§5,
`DISTRIBUTED_EXECUTION.md` §3 (three new rows) and 22 javadoc sites made true, each changed decision
carrying a dated `P8-DOC-001` note. The most consequential: every document said `settlement` writes
a repudiation's reversal (reconciliation does, through `ReversalService`); the readmission rules
were stated as designed, not as `V009` decided them; the four-eyes rule as before `V014`. One
migration comment (`V011`'s) cannot change under Flyway's checksum and is recorded known-stale.
**`PASS`.**

## Area 8 — Debt, and what is deliberately deferred

Every Phase-8-owned row ruled (above). New owners named: an equity account for a non-zero first
opening (Phase 14), return-to-sender and a payout return to a closed merchant (Phase 15), recovering
a DEBIT suspense item (Phase 13). **`PASS`.**

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` — ingestion by upload and pull, parsing, recognition, matching with grace and rematch, breaks, suspense, resolutions, repudiation, readmission, replay, the proofs, the meters and the five reports, all exercisable end to end over real HTTP |
| 2 | Architectural boundaries respected | `PASS` — no build edge between `settlement` and `reconciliation`; every join a port `app` implements |
| 3 | Required invariants tested | `PASS` — twenty-two `Phase: 8` invariants, every one with register rows, demanded by the guard after the flip |
| 4 | Failure cases handled | `PASS` — all forty-four of §14 |
| 5 | Security requirements implemented | `PASS` — area 5 |
| 6 | Observability exists | `PASS` — thirty-two §15 series from a freshly started instance, seventeen alert rules and the dashboard row resolved against a live scrape |
| 7 | Integration tests pass | `PASS` **with deviation recorded** — below |
| 8 | Documentation reflects reality | `PASS` — area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` — this review's finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` — **by this review's act**: ADR-0064…0073, each read against the code and corrected first |
| 11 | No unresolved critical issues | `PASS` — both critical findings corrected and probed |
| 12 | Formal phase review conducted | `PASS` — this document |

**Criterion 7 — full suite against real infrastructure.** The owner's standing instruction skips
`build databaseTest kafkaTest`, and every Phase 8 item was verified by targeted tiers and recorded in
those words. This review does not pretend otherwise:

- The **hermetic** tier was run **fleet-wide before the flip** — **2194 tests across 16 modules,
  0 failures** — and again **after the flip and the records** (counted in `CURRENT_STATE.md` §Just
  completed).
- The **database** tier was run over every suite the review's corrections reach, each in the
  container convention its suite keeps — the settlement and reconciliation module tiers whole
  (77 and 189 tests), the twelve proof-group suites in one shared container, and nineteen suites
  apart — **0 failures**. **No fleet-wide database or kafka count is claimed for Phase 8.**

Its cost is stated from this very phase: two suites (`ResolutionBatteryDatabaseTest`,
`BankStatementCashDatabaseTest`) fail when co-located with others — absolute counts and a fixed
statement sequence — which the debt row for suites run apart now names.

## The financial supplement F1–F8 — re-assessed at the gate

| # | Verdict |
|---|---|
| F1 | `Met` — the trial balance every storm round, inside its snapshot, and at rest |
| F2 | `Met` — every balance derived from journal lines (`BalanceDerivation`) and, since this review, every projection row Phase 8 posts verified at rest (`ProjectionVerification`) |
| F3 | `Met` — every money-moving command has a domain arbiter in its own transaction, independent of `platform.idempotency_record` (recognition, bank recognition, park and unpark, resolution posting, repudiation, payout return, expectation opening, upload and pull); a retention probe recommended, not blocking |
| F4 | `Met` — unpark the park's exact inverse; repudiation by reversal and counter-allocation; nothing deletable across 31 tables; every closure now bound to an approved resolution (`V015`) |
| F5 | `Met` — files ten ways, racing pulls, duplicate lines, duplicate returns, duplicate callbacks |
| F6 | `Met` — every contention raced, six for the first time here |
| F7 | `Met` — `NoFloatingPointMoneyRulesTest`, five rules, `noExemptClassDependsOnMoney` among them |
| F8 | `Met` — every reconciliation implication met or deferred with a named owning phase, after the review's rulings |

## The Phase 8-specific criteria — twenty-eight

Seven original and twenty-one added by the Phase 7 → 8 transition, counted from the gate. All
**`PASS`**, read against the code: *Break types* (fourteen, `AMBIGUOUS_MATCH` defence in depth);
*Duplicate ingestion*; *Immutability*; *Rule version and tolerance*; *Controlled resolution*
(`V014`, `V015`); *Suspense*; *Crash recovery*; *Ingestion* (pull built); *Duplicates*;
*Normalization*; *Deterministic matching*; *Investigation*; *Recognition*; *Expectations*; *Late
settlement*; *Replay and reprocessing* (repudiation built); *Multi-instance*; *Atomicity*;
*Security* (sixteen credentials); *Audit*; *Observability*; *Testing* (the full-battery clause as
criterion 7's deviation); *Documentation*; and the rest of the transition's set as the gate words
them.

## ADR-0064 … ADR-0073: accepted

Each read against the code, every drifted passage corrected — dated notes where a decision changed —
and only then accepted: `Accepted (2026-10-01, P8-DOC-001 — read against the code and corrected
first)`. The predecessor annotations (ADR-0036, ADR-0040, ADR-0057, ADR-0060 §6, ADR-0062) were
found present and accurate.

## The inputs routed to this review, decided

Transition decisions O1–O7: **all confirmed** against what was built (O6 moot — nothing was cut).
The deferral candidates: none deferred. The operator actor-type debt: carried and widened. The
fleet-wide battery: the recorded deviation above.

## What the phase produced

Two modules (`settlement`, `reconciliation`), twenty-five migrations, four counterparties' formats,
forty-four routes, thirty-two meters, seventeen alert rules, five audited reports, and the storm and
the battery that reconcile the reconciliation.

## What happens next

The Phase 8 → 9 transition is `READY`. Phase 9 (FX) inherits a reconciliation that never converts
currency — a `CURRENCY_MISMATCH` stays a break — and the debt rows owned by Phase 15.
