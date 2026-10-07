# Phase 9 Exit Review — FX and Cross-Border Payments

**Conducted:** 2026-10-07 (`P9-DOC-001`)
**Prescribed by:** [`PHASE_GATES.md`](../PHASE_GATES.md) §4 (review areas), §3 (universal exit
criteria and the financial supplement), §5 (Phase 9-specific criteria, read from the gate at
review time)
**Phase objective under review:** the platform converts money between five currencies at three
scales without creating or destroying a minor unit — a quote is a frozen posting plan priced by one
pure function, booked at acceptance in one local transaction against the FX position, and covered
back to back by a provider that may be slow, duplicated or silent — and pays across borders exactly
once: to a screened, payable beneficiary, held at acceptance, instructed on a corridor rail,
resolved only from knowledge, recalled, returned or unwound exactly, each counterparty on its own
clearing position and every leg reconciled to cash without the reconciliation ever converting.

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`**, five of them only after this review's corrections — see *What the review found* |
| Universal criteria (12) | **12 `PASS`**, one (7) with a recorded deviation, one (10) **met by this review's own act** |
| Financial supplement (F1–F8) | **8 `Met`** — re-assessed at the gate, never inherited; F6 after the windows this review moved onto the database clock, F8 after the review ruled the one Phase-9-owned debt row |
| Phase 9-specific criteria | **27 `PASS`** — 5 original + 22 added by the Phase 8 → 9 transition, counted from the gate; the Testing bullet's full-battery clause as a recorded deviation |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — every contended decision in `PHASE_9_PLAN.md` §7 with its PostgreSQL arbiter and its counted test; the storm's two instances ten seconds apart; the two instance-clock windows the review found moved onto the database's |
| **Verdict** | **Phase 9 `COMPLETE` (2026-10-07)** |

Conducted in the `P2-DOC-001` order: assess → land the corrections → **flip the status, which
is the guarded act** → re-run the battery → finalise with counted numbers.

**The assessment was five read-only audits, then five document passes, and it found what no
task's gate had.** The audits read ADR-0074…0083 against the code, walked every `Phase: 9`
invariant against the register, enumerated the Phase 9 routes with their permissions and
negatives and every audited act with its positive assertion, mapped the gate's criteria to the
tests that prove them, and diffed the plan, the lifecycle document and the architecture documents
against the implementation. They found **two windows still judged on an instance's clock — the
outbound credit's `NEVER_RECEIVED` and recall bound, and a beneficiary's screening validity — after
`X-TSK-013` had closed that class everywhere else; a two-entry approval posting without the
multi-entry pre-lock; a Phase 9 invariant with no register row that would have failed the flip;
the second corridor rail unpinned; eighteen audited acts never asserted; four refusals never
negatively tested; the machines' and arbiters' every-writer claims untested for the payment, the
outbound credit and the cancellation request; and drift in all ten ADRs and every Phase 9
document**. None was waived. Each was corrected here, in code or in text, and every correction in
code was broken on purpose: **seven probes, seven caught - the instance-clock bound, the
instance-clock lapse, a provider port planted in the conversion, rail B's ceiling edited under its
version, an audit dropped, the pre-lock removed, a shared table name registered bare - every restore
byte-identical (sha256-verified)**. One probe's
anchor first failed to apply, the review's own edits having left the file with CRLF endings; it was
recorded as no verdict, re-run after the working tree was normalised to LF, and caught.

---

## What the review found, and what it did about it

| Severity | Finding | Done |
|---|---|---|
| **IMPORTANT** | **The outbound credit's `NEVER_RECEIVED` and recall bound was the instance's clock** (`OutboundCreditResolution.neverReceivedBound` read `Instant.now(clock)`), so a resolver a minute ahead concluded a credit "never received" a minute early — releasing a hold while a re-send could still land. Every other permit and window had moved onto the database clock with `X-TSK-013` | `DatabaseTime.now(unitOfWork)` inside both locked transactions (the inquiry's and the recall's). `OutboundCreditResolutionDatabaseTest#aSkewedResolverConcludesNothingEarly` — a resolver one minute ahead concludes nothing twenty seconds before the deadline and concludes once past it; `SendPermitsAreTheDatabasesTest` extended to the Phase 9 stores (`JdbcOutboundCreditStore`, `JdbcCoverStore`). Probed |
| **IMPORTANT** | **A clearance's lapse was judged on the instance's clock** (`OfferIssuance`, `PaymentAuthorization` compared `decided_at + validity` with the caller's `now`): an instance ahead lapsed a valid clearance, one behind honoured a lapsed one (`INV-XB-02`) | Both judge against `DatabaseTime.now` on the unit of work. `CrossBorderPaymentDatabaseTest#aSkewedInstanceDoesNotLapseAClearance` — an instance an hour ahead authorizes a clearance with half an hour left. Probed |
| **IMPORTANT** | **The four-eyes approval of a parked cross-border return posted two entries — the fee refund, then the transfer — without the multi-entry pre-lock** the standing rule (ADR-0076 §9) requires over shared hot rows (`FEE_REVENUE`, the suspense) | `ParkedReturn` carries the transfer's accounts; `CorridorReturnResolutions` locks the union of both entries' projection rows in order before its first posting. `CrossBorderReturnDatabaseTest#theApprovalPreLocksBothEntriesInOrder` — held at the source wallet, the approval already holds the USD suspense, which posting the fee alone never touches. Probed |
| **IMPORTANT** | **`INV-FX-09` — `Phase: 9` by its catalogue line — had no register row**; the flip would have turned the guard red | An In-suite row (`NoProviderPortInConversionTest#thePlantedReachIsRefused`) and a Recorded probe (a provider port planted in `FxConversion`) |
| **IMPORTANT** | **The second corridor rail (`corridor-sim-b`, `P9-TSK-026`) was not pinned** in `RailMoneySemanticsArePinnedTest` — its money semantics could change under its id | Pinned: semantics, version 1, USD 10,000.00. Probed |
| **IMPORTANT** | **Eighteen audited acts were never asserted positively** — the pricing policy's and the corridor policy's proposal, activation and rejection; the FX and corridor availability's disable, enable proposal, enabling and rejection; the reversal's proposal and rejection; the beneficiary's registration and revocation | One count each, by operation and target, in the suites that drive them. One probed (the FX disable's audit dropped) |
| **IMPORTANT** | **Four refusals had no negative test**: the FX revenue, position and corridor reports and the trace (403 to an `FX_CONTROLLER`, 401 without a session); the reversal's approval and rejection | Each negatively tested in `Phase9ReportsDatabaseTest` and `FxTradeReversalRaceDatabaseTest` |
| **IMPORTANT** | **The every-writer claims for the payment's and the outbound credit's machines, three born-once arbiters and the cancellation request were never exercised by raw SQL** | `CrossBorderReturnDatabaseTest#theMachinesAndArbitersHoldForEveryWriter` (illegal edges refused; `payment_one_per_quote`, the `scheme_execution_claim` PK and `outbound_credit_return_once` refusing a copy with every trigger off); `CrossBorderCancellationDatabaseTest` (no `UPDATE` or `DELETE` of a cancellation request, as the application or the owner) |
| **IMPORTANT** | **The gate's provenance criterion was half-tested**: no assertion that the provenance columns are `NOT NULL`, and the plan perturbation reached the customer rate only | `FxProofDatabaseTest#everyProvenanceColumnIsNotNull`; the perturbation extended to the internal rate and the disclosed margin, each flipping the verdict alone |
| **IMPORTANT** | **The column-classification guard had been red since `P9-TSK-017`**, unseen because the database tier was skipped all phase: `crossborder.beneficiary` shares its name with `transfers.beneficiary`, and a register keyed on `table.column` merged the two; seventy Phase 9 columns were also unclassified | The register keys a shared name on `schema.table.column` (twenty-three rows qualified), as the guard's own message prescribed; `ColumnClassificationTest#tableNamesSharedBySchemasAreQualified` replaces the uniqueness assertion and the schema side qualifies shared names; the seventy columns classified. Probed (a qualified row made bare: caught twice) |
| **IMPORTANT** | **ARCH-P8-02 (the app composition owns reconciliation intake rules) stood owned by Phase 9** (F8) | Ruled: the second callers arrived without diverging — the FX and both corridor sources pass the one `ReconciliationIntake` — so the risk did not materialise; the boundary refactor re-owned to Phase 15 |
| MINOR | **Four Phase 9 posters date their entries from the instance's clock** (the outbound credit's completion, the return from evidence, the resolution's fee refund, the reversal), where `LEDGER_MODEL.md` §2 says a posting date is an input | Ruled the Phase 5–7 practice `X-TSK-011` already records, not a new class: each posts inside one conditional transition that admits a single application, so no later-day replay can meet a conflicting fingerprint (ADR-0065 §6's argument). The four sites added to `X-TSK-011`'s scope; `LEDGER_MODEL.md` §9 and the plan's posting catalogue say so |
| MINOR | `corridor-sim-b`'s source declares `PULL` but no pull collector is composed for it | Recorded in ADR-0080; upload serves it, as the plan's cut scope allowed |
| MINOR | Drift in all ten ADRs, `PHASE_9_PLAN.md`, `FX_AND_CROSS_BORDER_LIFECYCLES.md`, `DISTRIBUTED_EXECUTION.md` §3 (a duplicate *planned* row, the lock order unbuilt), `MODULE_ARCHITECTURE.md` (ten Phase 9 entries still "nothing built yet"), the glossary (six terms missing) and domain model, `CAPABILITY_MAP.md`, `ROADMAP.md`, the gate's other named documents, three code comments, the reversal's posting key (as built `fx-trade:` in the `ledger.reverse` scope), the Phase 8 backlog header still `IN_PROGRESS`, and 70 Phase 9 columns missing from `DATA_CLASSIFICATION.md` (the database-tier `ColumnClassificationTest` skipped all phase) | Every statement made true — area 7 |

## The flip

Recording Phase 9 `COMPLETE` is the guarded act: the register guard begins demanding a §2 row for
every `Phase: 9` invariant and a §4 row for every `P9-TST` item, and `PlannedMetersExistTest` reads
`PHASE_9_PLAN.md` §15 through the generic union. Against the real status the architecture tier
(`MutationDemonstrationTest` among its 162 tests across 28 suites), `PlannedMetersExistTest` (11) and the
glossary and document guards (44 across 18) passed. **And it was proven non-vacuous against the real
status**: with `INV-FX-09`'s two rows removed, the guard failed reporting `(currently 9)` and naming
exactly `["INV-FX-09"]` — the invariant the audit found unrowed, which the flip would otherwise have
caught first. The register was restored byte-identical (sha256-verified).

---

## Area 1 — Scope: what the phase set out to build, and what it built

Thirty items across nine milestones (M9.1–M9.9), every one `COMPLETE`, plus `X-TSK-013`, which the
gate made a precondition. Both cut candidates (`P9-TSK-026`, the second providers, and `-025`, the
operator trade reversal) were built, so transition decision O8 was never exercised and the gate's
conditional clauses read as met by the tasks. Migrations: fx `V001`–`V009`, crossborder
`V001`–`V006`, ledger `V019`–`V025`, payments `V024`–`V028`, settlement `V015`–`V017`,
reconciliation `V020`–`V021`, kyc `V009`, merchant `V009`, identity `V016` and `V019` — thirty-six.
**`PASS`.**

## Area 2 — Walk the money end to end

A wallet conversion, a cross-border payment and its return, and a recall that wins, each walked hop
by hop: the quote's frozen plan and its pinned provenance; acceptance, the trade and the
conversion's entry through `FX_POSITION` in one transaction; the cover sent later, its execution
fact and the realised result; the FX provider's settlement file, the leg matched to cash; the
cross-border hold and outbound credit in Tx1, the instruction after the commit, the completion's
entry and its expectation, the corridor report and the bank; the return applied once in the
currency received with the fee refunded; the recall's unwind. All of it driven together in the
storm (`FxCrossBorderStormDatabaseTest`), which settles every leg to cash from the database's own
truth. **Found:** the approval of a parked return posted two entries without the pre-lock — fixed
above. **`PASS`.**

## Area 3 — Multi-instance correctness

Every §7 contention matched to its PostgreSQL arbiter and its counted ten-way race; the six born-once
arbiters each refused a copy with the locks and triggers bypassed (three for the first time here);
the storm's two application contexts, ten seconds apart on the clock, raced the same quote, cover,
payment, recall and return with one effect each; the six schedules registered
(`NoSingleInstanceAssumptionRulesTest`, fourteen → twenty); advisory namespace `5` registered and
pinned. **Found:** two windows still judged on an instance's clock (above) — the review answers the
multi-instance question only after moving them onto the database's, each proven by a skewed
instance. **`PASS`.**

## Area 4 — Failure behaviour

Each of the sixty failure scenarios in `PHASE_9_PLAN.md` §14 has a test or a stated rationale (the
plan's §13 and §14 tables, each row naming it); the storm seeds the catalogue fault by fault — a
lost FX response, duplicated and forged callbacks, an expired lock and a requote, an off-plan
execution, a crash mid-cover, a declined send, `RECEIVED` then rejected, never received, a recall
that wins and one too late, returns before and after delivery, a partial return parked and resolved
four-eyes, crashes after Tx1 and mid-completion — and every local and cross-module transaction
(T-a…T-g) is proven all-or-nothing by failure injection in its own suite. **`PASS`.**

## Area 5 — Security and audit

Forty-four Phase 9 routes, each in `RoutePermissionRegisterTest` with a negative of its own — four of
them only since this review; five permissions under the `FX_CONTROLLER` role and the screening
reviewer, pinned by `RoleNameTest`; four-eyes refused at the domain and at the `CHECK`, each alone;
the provider credentials confined and every provider URL through the transport guard; the
`INV-RAIL-03` needle walked end to end in the storm and absent from every table and log but kyc's
ciphertext; signed-but-forged callbacks move nothing. Every privileged act catalogued and now
asserted positively — eighteen only since this review. **`PASS`** after the audit and negative
assertions.

## Area 6 — Invariants, the register, and the demonstrations

One hundred and eleven invariants reached by Phase 9, read from the catalogue with the guard's own
token regex — thirteen new to the phase (`INV-FX-01`…`09`, `INV-XB-01`…`04`) — every one now with
rows; §4 rows for `P9-TST-001` and `P9-TST-002`; this review's new §2 rows. **`PASS`.**

## Area 7 — Documentation accuracy

All ten ADRs read against the code, corrected with dated notes where a decision changed, and
accepted. `PHASE_9_PLAN.md` (inline corrections and a new §20 *Errata — as built*),
`FX_AND_CROSS_BORDER_LIFECYCLES.md`, `DISTRIBUTED_EXECUTION.md` §3 (the duplicate *planned* row
deleted, the lock order rewritten as built), `MODULE_ARCHITECTURE.md` (every Phase 9 entry rewritten
from the code, a port that never existed removed), the glossary (six terms added: Posting Plan,
Unwind, Cancellation Request, Recall, Cross-Border Return, Payee Check) and the domain model,
`CAPABILITY_MAP.md`, `ROADMAP.md`, `RECONCILIATION_MODEL.md`, `LEDGER_MODEL.md`,
`BOUNDED_CONTEXTS.md`, `DATA_CLASSIFICATION.md`, `AUDITABLE_ACTIONS.md`, `ERROR_CONTRACT.md`, the
`DELIVERY_PLAN.md` addendum and three code comments made true. The most consequential: availability
was documented as a `FOR SHARE` lock in the global order (it is an append-only fact read unlocked;
only its writers serialise); a `CROSSBORDER_RETURN` scheme-execution claim the code never built;
a `CrossBorderParticipants` port that never existed; and a `clear_until` column that does not
exist. **`PASS`.**

## Area 8 — Debt, and what is deliberately deferred

The one Phase-9-owned row (ARCH-P8-02) ruled and re-owned to Phase 15 with its reason. Carried with
owners: `X-TSK-014` and `X-TSK-015` (cross-cutting, `PLANNED`); the rail B pull collector (above);
Phase 16's sub-account path for the hot `FX_POSITION` rows. **`PASS`.**

## The twelve universal exit criteria

| # | Criterion | Verdict |
|---|---|---|
| 1 | Required functionality exists | `PASS` — quotes, conversions and covers in five currencies; corridor payments, recalls, returns and unwinds; counterparty screening and beneficiaries; FX and corridor reconciliation to cash; reports and the trace |
| 2 | Architectural boundaries respected | `PASS` — no build edge between `fx` and `crossborder`, nor from `crossborder` to `payments` or `kyc`; every join a port `app` implements (`PaymentsModuleIsolationTest`, `CrossborderModuleIsolationTest`) |
| 3 | Required invariants tested | `PASS` — thirteen `Phase: 9` invariants, every one with register rows, demanded by the guard after the flip |
| 4 | Failure cases handled | `PASS` — all sixty of §14 |
| 5 | Security requirements implemented | `PASS` — area 5 |
| 6 | Observability exists | `PASS` — the §15 series from a freshly started instance (`PlannedMetersExistTest`, armed by the flip), thirteen alert rules and the twelve-panel dashboard row resolved against a live scrape |
| 7 | Integration tests pass | `PASS` **with deviation recorded** — below |
| 8 | Documentation reflects reality | `PASS` — area 7 |
| 9 | `CURRENT_STATE.md` updated | `PASS` — this review's finalisation |
| 10 | Relevant ADRs exist and are `Accepted` | `PASS` — **by this review's act**: ADR-0074…0083, each read against the code and corrected first |
| 11 | No unresolved critical issues | `PASS` — none critical; every important finding corrected and probed |
| 12 | Formal phase review conducted | `PASS` — this document |

**Criterion 7 — full suite against real infrastructure.** The owner's standing instruction skips
`build databaseTest kafkaTest`, and every Phase 9 item was verified by targeted tiers and recorded in
those words. This review does not pretend otherwise:

- The **hermetic** tier was run **fleet-wide after the flip** — **2549 tests across 419 suites and 18
  modules, 0 failures** — with the **architecture** tier, **162 across 28, 0 failures**.
- The **database** tier was run over every suite the review's corrections reach — the app suites
  carrying its new tests (the outbound credit resolution, cross-border payment, return, cancellation
  and beneficiary, the FX reversal race, the Phase 9 reports, the FX proofs), the payout return suite
  in its own container, reconciliation's two resolution suites, the fx and crossborder policy and
  availability suites and the column-classification guard — **153 tests across 16 suites, 0
  failures**. **No fleet-wide database or kafka count is claimed for Phase 9.**

Its cost is stated from this very phase: the column-classification guard, database-tier, stood red
from `P9-TSK-017` to this review without any task seeing it (above).

## The financial supplement F1–F8 — re-assessed at the gate

| # | Verdict |
|---|---|
| F1 | `Met` — the trial balance zero per currency in all five currencies, in the battery's ≥ 10,000 conversions, every storm round inside its snapshot, and at rest |
| F2 | `Met` — every balance derived from journal lines; the FX books proof and the position proof every round; projections replayed and verified at rest |
| F3 | `Met` — every money-moving command has a domain arbiter independent of `platform.idempotency_record`: `UNIQUE (fx.trade.quote_id)`, `UNIQUE (crossborder.payment.quote_id)`, the `fx.cover_execution` PK, `UNIQUE (fx.cover.quote_id, kind)`, the `scheme_execution_claim` PK, `outbound_credit_return_once` |
| F4 | `Met` — conversions are final except the four-eyes reversal, which posts the exact inverse; unwinds, recalls and returns compensate, never edit; the FX and cross-border histories append-only for every writer |
| F5 | `Met` — duplicated and forged callbacks at both doors, duplicated provider answers, ten deliveries one record |
| F6 | `Met` — every contention raced, the two instance-clock windows moved onto the database clock by this review |
| F7 | `Met` — `NoFloatingPointMoneyRulesTest`; rates `NUMERIC(20,10)`, margins at six decimals, refused never rounded |
| F8 | `Met` — FX and corridor reconciliation built, never converting; the one Phase-9-owned debt row ruled |

## The Phase 9-specific criteria — twenty-seven

Five original and twenty-two added by the Phase 8 → 9 transition, counted from the gate. All
**`PASS`**, read against the code: *Trial balance per currency*; *The rounding residual*; *Quote
expiry*; *Server-authoritative rates*; *Minor units*; *Spread is explicit*; *The FX position is
explained*; *A quote is a frozen plan with provenance* (the provenance and perturbation halves since
this review); *Provider ambiguity ends only in knowledge*; *Cross-border happens exactly once*; *The
lifecycles hold* (the payment, outbound credit and cancellation request's every-writer halves since
this review); *Unwind, cancellation and return are exact*; *Counterparty positions* (M9.8 built);
*Reconciliation never converts*; *Compliance* (the lapse on the database clock since this review);
*Security* (four negatives since this review); *Audit* (eighteen positive assertions since this
review); *Observability*; *Multi-instance and concurrency*; *Atomicity*; *Testing* (the full-battery
clause as criterion 7's deviation); *Documentation and invariants*.

## ADR-0074 … ADR-0083: accepted

Each read against the code, every drifted passage corrected — dated notes where a decision changed —
and only then accepted: `Accepted (2026-10-07, P9-DOC-001 — read against the code and corrected
first)`. Where the code had drifted from an ADR that was right, the code was corrected instead
(ADR-0076 §9's pre-lock; ADR-0079's and ADR-0081's windows on the database clock).

## The inputs routed to this review, decided

Transition decisions O1–O10: **confirmed** against what was built (O8 moot — nothing was cut).
`X-TSK-013`: complete before the storm, as the gate required. The fleet-wide battery: the recorded
deviation above.

## What the phase produced

Two modules (`fx`, `crossborder`), thirty-six migrations across nine schemas, two FX providers and
two corridor rails, forty-four routes, the §15 series, thirteen alert rules, a twelve-panel
dashboard row, four audited reports and the trace, and the storm and the battery that prove no
minor unit is created or destroyed across five currencies.

## What happens next

The Phase 9 → 10 transition is `READY`. Phase 10 (Credit Decisioning) inherits a platform that
converts and pays across borders, and the debt rows owned by Phase 15 and Phase 16.
