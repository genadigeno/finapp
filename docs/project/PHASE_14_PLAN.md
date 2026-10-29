# Phase 14 — Accounting and Financial Reporting

*Written by the Phase 13 → 14 transition (2026-10-29), as documents only: nothing in this plan is implemented until
`P14-TSK-001` lands. Decided in ADR-0113…ADR-0118 (`Proposed`); the invariants `INV-ACC-06`…`-14` beside the catalogue's
existing `INV-ACC-01`…`-05`; the exit criteria in `PHASE_GATES.md` §5 Phase 14, made measurable by this transition; the
backlog in [`BACKLOG.md`](BACKLOG.md) §Phase 14. Where this plan and an earlier document disagree, this plan is right and
the earlier document carries a dated note saying so. Where this plan and the code the tasks build disagree, the task's
record is right and this plan gains an erratum.*

**Status of this document:** the decided design, drafted from `DELIVERY_PLAN.md` §Phase 14, `MODULE_ARCHITECTURE.md`
§`accounting`, `ROADMAP.md` Refinement 2 (the GL mapping seam ADR-0040 left in Phase 3), the Phase 14 rows of
`DECISIONS.md` §Deliberately Deferred and `CURRENT_STATE.md` §Known Architectural Debt, and the code as it stands at
master `f94f331a`. Owner decisions C1–C13 (§2.2) are recorded as **recommended defaults awaiting the owner's
ratification**; the first task depends on none of them. Phase 13's formal completion gate runs in parallel; this plan
edits no Phase 13 section and moves no current-phase pointer.

---

## 1. Objective

Turn the one authoritative ledger into **reportable, closable, reproducible financial information** — without a second
ledger. Phase 14 adds a General Ledger that is a **versioned mapping over the existing journal**, accounting periods
whose close is **enforced by the ledger itself** at the moment of posting, a close that runs from recorded readiness
checks to a four-eyes approval, financial statements and operational reports that are **immutable, reproducible report
runs** with drill-down to journal lines, reporting-currency translation that never touches an original amount, and a
regulatory-reporting framework behind a jurisdiction adapter.

**Phase 14 moves no money and creates no competing record.** Every economic event already posts once, in its source
domain's transaction, through `PostingService` / `ReversalService` / `AdjustmentService` (`INV-LED-04`). The GL holds no
balance of its own: every GL figure is a function of journal lines × a pinned mapping version (`INV-ACC-06`). The only
journal entries Phase 14 causes are **prior-period adjustments** (a ledger adjustment of a new origin, `ACCOUNTING`,
four-eyes, through the ledger's own door — `P14-TSK-013`) and, only if the owner chooses C6 (A), **write-offs** posted
by lending and bnpl under rules already designed (`P14-TSK-021`). Reporting never writes (`INV-ACC-14`).

### 1.1 The production limitation, recorded up front

The general-ledger chart, the statement layout and every regulatory form are **reference artefacts, not a statutory
chart or a filed return**: the platform's reference jurisdiction remains the neutral EUR one (`DECISIONS.md`
§Deferred; Phase 13's C4 precedent), the regulatory adapter is simulated, and nothing in Phase 14 claims compliance with
any accounting framework or regulator. Where a treatment is a policy choice (write-off, provisioning, effective-interest
amortisation, revaluation, year-end closing entries), this plan records the assumption, defers or bounds it, and lists it
for qualified review (§2.2). Production activates no mapping version by migration — the first is proposed and approved
under four eyes after deployment (the BNPL terms precedent); until then reports run and answer
`accounting.NoActiveMapping`, and the ledger's trial balance (`INV-ACC-01`) stays the continuous signal it already is.

---

## 2. Why this phase is shaped by decisions already taken

### 2.1 Inherited, binding

| Decision | Where | Consequence here |
|---|---|---|
| The journal is the authoritative record; the ledger is its sole writer | ADR-0002, `INV-LED-04`, `INV-EVT-02` | The GL is a read model over the journal; no accounting table holds a balance; events are hints, never the GL's input |
| Posting date decides the accounting period; it is a domain input, never a clock read | `LEDGER_MODEL.md` §2, ledger `V004` | The period is a range of `journal_entry.posting_date`; the close enforces on that column |
| A flat ledger account with a typed classification; the GL mapping is Phase 14's | ADR-0040; `ledger_account.gl_code` nullable, unwritten | The mapping is accounting's versioned data keyed by purpose; `gl_code` stays unwritten (ADR-0113 §3) |
| Corrections are new entries: reversal bounded by the original, adjustments four-eyes, each origin its own door | `INV-REV-01`…`-04`, ADR-0071 §6 (`AdjustmentOrigin`), ledger `V009`, `V010`, `V015` | Post-close corrections reuse these doors; a new origin `ACCOUNTING` for prior-period adjustments |
| The trial balance is zero per currency and never self-corrects | `INV-ACC-01`, `TrialBalance.sweep`, `finapp.ledger.trial.balance{currency}` | Kept as the continuous signal; Phase 14 adds the per-period, per-GL-account view, never a second checker that repairs |
| One currency per ledger account; a conversion is two single-currency legs meeting on `FX_POSITION`; no revaluation while covered positions are zero at rest | ADR-0040, ADR-0076, D30 | Translation is a reporting-layer act at a pinned valuation rate set; nothing in the journal is re-valued (C7) |
| The journal is read by keyset over its UUIDv7 id within a settle horizon; never by events | ADR-0111 decision 1, `MonitoredLedger`, `LedgerMonitoredLedger` | The accounting reads reuse the shape; a FINAL report is cut by posting date of a locked period, which is immutable |
| Shared/exclusive advisory locks arbitrate a version pin against an activation | `JdbcRiskPolicyStore` `LOCK_SQL` / `SHARED_LOCK_SQL` (namespace 13); ledger `V009` (namespace 2 inside a trigger) | The posting cutoff takes namespace `14` shared at every journal insert and exclusive at the cutoff edge (ADR-0114) |
| Every module reaches a foreign domain through ports implemented in `app`; `risk` has no ledger edge | ADR-0107, `RiskModuleIsolationTest` | `accounting` depends on `platform` and `sharedkernel` only (ADR-0113) |
| The standing criterion-7 deviation; storms after the project's phases | `PHASE_13_PLAN.md` §13.1 (C5), `POSTPONED_ISSUES.md` PI-004 | Per task hermetic + architecture; database suites written and owed; one database pass at the exit; `P14-TST-001` planned and `POSTPONED` |

### 2.2 Owner decisions — C1–C13, recommended defaults awaiting ratification

| # | Decision | Options | Recommended default |
|---|---|---|---|
| C1 | Functional and reporting currency | (A) EUR functional = reporting; (B) per-currency books only, no translation | **(A)**: EUR (the reference jurisdiction's), every report showing transaction-currency columns beside the EUR translation |
| C2 | The GL chart and mapping key | (A) a reference chart as versioned data, mapped by ledger purpose (+ owner kind; counterparty a dimension); (B) per-ledger-account codes in `gl_code` | **(A)**: purpose-level mapping is total over a closed enum and survives per-loan account creation; `gl_code` stays unwritten. Codes illustrative, not a statutory chart (qualified review) |
| C3 | A posting dated into a locked or closed period | (A) the ledger refuses (`ledger.PeriodClosed`); the one back-dating poster (lending's accrual) floors its posting date at the first open date, value date kept; close readiness requires accruals caught up; (B) the ledger re-dates silently | **(A)**: `INV-ACC-03` as written; re-dating is the producer's explicit rule, never the ledger's silence |
| C4 | Regulatory reporting jurisdiction | (A) a simulated reference format, jurisdiction a field, adapter data; (B) a named real jurisdiction | **(A)** (Phase 13's C4 precedent): no legal obligation claimed; every real form is adapter data verified when a jurisdiction is chosen |
| C5 | Criterion 7 in Phase 14 | — | The standing deviation continues: database suites written and owed per task; one owner-authorised database pass before `P14-DOC-001`; `P14-TST-001` planned and `POSTPONED` (PI-004) |
| C6 | Write-off, provisioning, effective-interest amortisation | (A) write-off as a four-eyes operator command posting lending rule 14 / bnpl F11 with the capital-headroom fix in the same commit, a cut candidate; provisioning (ECL / IFRS 9 staging) and EIR deferred and disclosed; (B) defer all three | **(A)** — the rules and expense accounts already exist; no automatic charge-off policy is invented; provisioning and EIR need qualified review and are disclosed as reporting differences in the statements' notes |
| C7 | Revaluation / unrealised FX | (A) report-time translation at a pinned valuation rate set, the translation difference a disclosed line, nothing posted; (B) period-end revaluation entries to new `FX_UNREALISED_*` purposes, auto-reversed | **(A)**: realised results are already posted (`FX_REALISED_*`, `FX_SPREAD_REVENUE`, `ROUNDING_RESIDUAL`); posting unrealised P&L is policy (IAS 21-shaped) needing qualified review |
| C8 | Year-end and P&L closing entries | (A) none posted; the current-period result and retained earnings computed in the statements; (B) closing entries to a new `RETAINED_EARNINGS` purpose | **(A)**: no new posting treatment; the journal stays the economic record |
| C9 | Period granularity and calendar | (A) calendar months of `posting_date`, closed in order, a period lockable no earlier than its end + a configured grace (default 2 business days); (B) daily periods | **(A)** |
| C10 | How "reporting never writes" is enforced | (A) report and drill-down transactions `REPEATABLE READ READ ONLY` (database-enforced) + the module's isolation (no ledger edge) + a static guard; a separate read-only database role deferred to Phase 15's privileged-access review; (B) a `finapp_reporting` role now | **(A)** — the single application role (`DESIGN-019` §17) stays; the gate's "privilege review" is met by the read-only transaction and the guard, recorded |
| C11 | Where GL-side breaks live | (A) accounting-owned `accounting_break`, reconciliation's shape (born once, case file, four-eyes acknowledgement, ageing) without its tables or suspense; (B) new types in reconciliation's break table | **(A)**: a GL break has no external evidence and parks no value; reconciliation's own breaks and suspense are read and disclosed, never copied |
| C12 | Roles and four-eyes | (A) `ACCOUNTANT` (propose), `FINANCIAL_CONTROLLER` (approve), `REGULATORY_REPORTER` (prepare, submit) — eleven permissions, no role both proposes and approves; approver ≠ proposer by `CHECK` | **(A)** |
| C13 | The safeguarding position return (subtracting `lending_funded`, ADR-0096 §6) | (A) in Phase 14 as a reference regulatory return, cut candidate; (B) Phase 15 | **(A)** |

C3, C6, C7 and C8 are the accounting-policy choices a qualified reviewer should confirm before production; the plan is
built so each can change by a later task without rewriting history (§12).

---

## 3. What exists, what is reused, what is missing (the review of the implementation)

Surveyed at master `f94f331a` by grep and reading the key classes and migrations.

| Area | Exists (code) | Phase 14 |
|---|---|---|
| Journal | `ledger.journal_entry` / `journal_line` (`V004`): three dates, `entry_type` `POSTING`/`REVERSAL`/`ADJUSTMENT`, `reference`, `idempotency_scope` = `ledger.post:<key>` (the posting key's namespace classifies the entry — `LedgerMonitoredLedger`), `INSERT`-only by grant, balanced per currency by trigger | **Reuse** as the only source of every GL figure |
| Ledger accounts | `ledger_account` (`V002`): type, normal balance, one currency, owner kind (`CUSTOMER`, `MERCHANT`, `OPERATIONAL`, `SUSPENSE`, `COUNTERPARTY`, `LOAN`, `BNPL_OBLIGATION`), `purpose` (40 values, `AccountPurpose`), nullable unwritten `gl_code` | **Reuse**; map by purpose; `gl_code` stays unwritten |
| Chart | `ChartOfAccounts` resolves operational accounts by purpose + currency; counterparty clearings by counterparty (`V021`) | **Reuse** as the ledger's chart; the GL chart is new, in `accounting` |
| Posting | `PostingService` (`PostingCommand`: key, posting date, value date, reference, lines), keyed idempotency, `PostingObserver` | **Reuse**; nothing new posts through it except C6 (A) |
| Reversal | `ReversalService` + `V009` bound under namespace 2 | **Reuse** for post-close corrections the owning domains already offer |
| Adjustment | `AdjustmentService` proposals (`V010`), origins `MANUAL` / `RECONCILIATION` (`V015`), `proposeOwned` / `approveOwned`, four-eyes `CHECK` | **Reuse**; add origin `ACCOUNTING` + reason `PRIOR_PERIOD_ADJUSTMENT` (`-013`) |
| Balances | `BalanceDerivation`, `AsOf` (`Latest`, `PostingDate`, `ThroughEntry`), `PurposeBalances`, `PositionBreakdown` (namespaces), `StatementDerivation`, `ProjectionVerification` | **Reuse** for drill-down and source checks |
| Trial balance | `TrialBalance.sweep` — one `GROUP BY currency, scale, direction` over the whole journal; gauge `finapp.ledger.trial.balance{currency}` (`LedgerMetrics`) | **Reuse** as the continuous check; **missing**: per period, per GL account, snapshotted |
| Posting catalogues | Documented, not registered: `LEDGER_MODEL.md` §9 (FX), §10 (lending, ADR-0096 §2 rules 0–13), §11 (BNPL F1–F11, ADR-0103); `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §3.3; `PAYMENT_LIFECYCLES.md` §capture/refund; `RAIL_AND_DISPUTE_LIFECYCLES.md` (disputes, ADR-0061); `*BooksHaveOnePosterTest` guards | **Missing**: one register of every namespace with its rule, version and line shape, and a runtime conformance check (`-002`, `-010`) |
| Product subledger proofs | `LoanSubledgerProof`, `LoanReplayProof`, `LendingCapitalProof` (lending); `BnplSubledgerProof`, `BnplThreeWayProof`, `BnplReplayProof` (bnpl); `FxBooksProof` (fx); `PositionProof` (reconciliation); merchant payable drill-down (`P12-TSK-016`); `VelocityProof` (risk) | **Reuse** through a port as close checks; never re-implemented |
| Accruals | Lending daily accrual `lending.accrual:<loan>:<date>` posts **posting date = value date = the accrual date** (`LoanServicing.java:432-433`); BNPL `bnpl.accrual:<obligation>:<date>` posts **posting date today, value date the accrual date** (`BnplAccruals.java:344`) | **Reuse**; lending's back-dating made cutoff-safe (`-006`, C3) |
| Reconciliation | Breaks (ADR-0069), suspense ageing (ADR-0070), resolutions four-eyes (ADR-0071), reports `/positions`, `/suspense`, `/unmatched`, `/summary`, `/provider-costs` | **Reuse**: read and disclosed at close (`INV-ACC-05`); not copied |
| FX | `fx.rate_snapshot` reference rates (`V002`: source, pair, rate `NUMERIC(20,10)`, `observed_at`, append-only), executed trades with frozen provenance (`INV-FX-05`), realised results posted (`V023`) | **Reuse**: valuation rate sets captured from reference snapshots (`-014`); never from customer quotes |
| Reports | Operator reports for lending (`/portfolio`, `/delinquency`, `/accrual`, `/capital`), bnpl (`/portfolio`, `/merchant-financing`, `/refunds`, `/delinquency`), reconciliation (above), merchant transactions; risk reports | **Reuse** as operational (live) reports; **missing**: immutable, reproducible report runs, GL, statements |
| Periods, close, GL mapping, valuation, statements, regulatory reporting | Nothing: no period, no cutoff, no mapping, no statement, no submission | **New** — this phase |
| Write-off, provisioning, EIR | `LOAN_WRITE_OFF_EXPENSE`, `BNPL_WRITE_OFF_EXPENSE` seeded, posted by nothing; rule 14 / F11 designed | C6 |

---

## 4. Bounded contexts and modules

### 4.1 Ownership (owner brief 7.1)

| Layer | Owner | Authoritative state | Phase 14 |
|---|---|---|---|
| Operational account / wallet state | `accounts` (Customer Account, Wallet), `merchant` | account lifecycle, ownership; **no balance** | unchanged; read through ledger owner refs only |
| Product subledgers | `lending`, `bnpl`, `fx`, `payments`, `merchant`, `reconciliation` | the domain's records (loans, obligations, trades, attempts, payouts, breaks); every money figure a ledger balance proven equal to their rows | unchanged; their proofs become close checks |
| The authoritative ledger | `ledger` | journal entries and lines, ledger accounts, holds, adjustments, **and from `-004` the posting cutoff** | gains the cutoff (`V028`), a `posting_date` index (`-007`), the `ACCOUNTING` origin (`-013`) |
| General Ledger / accounting representation | `accounting` (new) | GL mapping versions (GL accounts + rules), accounting periods and their close records, valuation rate sets, accounting breaks, prior-period adjustment records | new; **no balance** |
| Accounting rules and mappings | source rules: each poster (code); register and conformance: `accounting`; GL mapping: `accounting` | — | `-002`, `-003`, `-010` |
| Financial reporting | `accounting` (Reporting, 27) | report definitions (code), report runs (immutable output) | `-008`, `-009`, `-015`…`-017` |
| Regulatory reporting | `accounting`, behind `RegulatoryReportingProvider` (simulated adapter in `app`) | obligation configurations, submissions | `-018`, `-019` |

### 4.2 The module decision: one `accounting` module, no ledger edge (ADR-0113)

Contexts Accounting / General Ledger (25) and Reporting (27) in **one module, `accounting`**, schema `accounting`,
depending on `platform` and `sharedkernel` only. Every other domain behind ports `accounting` declares and `app`
implements: `JournalReads` (GL aggregates and drill-down lines, read-only), `LedgerChart` (the purposes, their types,
owner kinds and counterparties), `PostingCutoff` (lock/unlock through the ledger's own API), `PriorPeriodPostings`
(`AdjustmentService.proposeOwned/approveOwned` with origin `ACCOUNTING`), `SourceProofs` (the existing proofs' verdicts),
`AccrualCompleteness` (lending's and bnpl's accrued-through dates), `ReconciliationDisclosure` (open breaks, suspense
and ageing), `ValuationRates` (fx reference snapshots), `RegulatoryReportingProvider`. Split trigger recorded: regulatory
reporting acquiring retention or segregation obligations the GL does not share. Not a service: the GL's consistency
boundary is the ledger's database.

---

## 5. Aggregates and commands

| Aggregate | Commands (permission) | Transaction |
|---|---|---|
| GL Mapping Version (+ GL Accounts, Mapping Rules) | propose (`ACCOUNTING_MAPPING_PROPOSE`), approve/activate, reject (`ACCOUNTING_MAPPING_APPROVE`) | own; activation under namespace `14` key `mapping` |
| Accounting Period | open next (system), lock, propose unlock / approve unlock, propose close / approve close / reject, propose reopen / approve reopen | own + the ledger's cutoff edge in the same transaction |
| Close Run (+ Close Checks) | start (on lock), claim/run check (system, leaderless), re-run check | own, one per check claim |
| Report Run | generate (`ACCOUNTING_REPORT_GENERATE`, keyed), complete (system), supersede (on reopen), regenerate-and-compare | claim txn + content txn, `REPEATABLE READ READ ONLY` for the reads |
| Valuation Rate Set | capture (propose), approve, reject | own |
| Accounting Break | raise (system, born once), acknowledge with disclosure (four-eyes), resolve by reference to a correcting entry | own |
| Prior-Period Adjustment | propose, approve (posts the ledger adjustment in the approval's transaction), reject | own + the ledger adjustment |
| Regulatory Submission | draft, validate, approve, submit, record acknowledgement / rejection, resubmit (correction) | own; the provider call outside any transaction, `SUBMITTED` committed first |

---

## 6. Lifecycles

- **Accounting Period:** `OPEN → LOCKED → CLOSE_PROPOSED → CLOSED`; `CLOSE_PROPOSED → LOCKED` (rejected);
  `LOCKED → OPEN` (unlock, four-eyes); `CLOSED → OPEN` (reopen, four-eyes, only the latest `CLOSED` period, only while
  the next period is not `LOCKED`; `revision + 1`). Periods lock and close in order; at most one period is
  `LOCKED`/`CLOSE_PROPOSED` at a time. Every edge is a conditional `UPDATE … WHERE status = :from AND revision = :rev`
  with an append-only `period_event` row.
- **GL Mapping Version:** `PROPOSED → ACTIVE → RETIRED`; `PROPOSED → REJECTED`; immutable content from birth;
  `effective_from_period` an `OPEN` period; a period pins the version in force when it is `LOCKED`.
- **Close Check:** `PENDING → PASSED | FAILED | DISCLOSED` (a failure the approver discloses explicitly); re-run creates a
  new attempt row, never edits.
- **Report Run:** `GENERATING → FINAL | PROVISIONAL`, `FAILED`; `FINAL → SUPERSEDED` (on reopen); content and hash frozen
  at completion.
- **Valuation Rate Set:** `PROPOSED → APPROVED | REJECTED`; immutable.
- **Accounting Break:** `OPEN → ACKNOWLEDGED (disclosed) → RESOLVED`; `OPEN → RESOLVED`; never deleted.
- **Regulatory Submission:** `DRAFT → VALIDATED → APPROVED → SUBMITTED → ACKNOWLEDGED | REJECTED`; a rejection or a
  correction is a new submission referencing the original; `SUBMITTED` immutable; an unknown provider answer is an
  inquiry permit on the `SUBMITTED` row, never a re-submit.

---

## 7. Financial invariants

The catalogue's `INV-ACC-01`…`-05` (trial balance zero per currency; drill-down; closed periods reject postings;
reproducible reports; unreconciled positions disclosed) are Phase 14's to demonstrate, and this transition adds nine,
`Phase: 14`: **`INV-ACC-06`** the GL is a function of the journal — every line maps to exactly one GL account under a
version, classification-compatible, and the GL holds no balance; **`-07`** a mapping version is immutable, four-eyes
activated, never retroactive, and every GL figure names its version; **`-08`** the posting cutoff and the close never
race; **`-09`** a period closes only from recorded, complete readiness under four eyes; **`-10`** corrections after
close are prior-period adjustments in an open period, and a reopen supersedes, never deletes; **`-11`** every translated
figure keeps its original amount, currency, valuation rate set and rounding; valuation never alters a line or an
executed rate; **`-12`** every entry conforms to its registered posting rule and every source record expected to post
has exactly one entry — breaks born once, never silently corrected; **`-13`** a regulatory submission comes only from a
FINAL report run, four-eyes, with lineage, corrected only by resubmission; **`-14`** reporting never writes. Protected
beside them: `INV-LED-01`…`-06`, `INV-BAL-02`, `INV-HIST-01`, `INV-HIST-04`, `INV-REV-01`…`-04`, `INV-REC-02`, `-05`,
`INV-FX-05`, `INV-IDEM-01`, `INV-AUD-01`…`-04`, `INV-EVT-02`. The catalogue count goes 162 → 171.

---

## 8. Multi-instance architecture (owner brief 7.9)

### 8.1 Transactions

| Name | Contents |
|---|---|
| T-map | mapping proposal insert; activation: namespace `14` key `mapping` exclusive, the version `FOR UPDATE`, totality and compatibility checked through `LedgerChart`, conditional edge, history row |
| T-lock | (A1) the period row `FOR UPDATE` (and the previous period `FOR SHARE`: must be `CLOSED`); (A2) namespace `14` key `cutoff` **exclusive**; (A3) the ledger's `posting_cutoff` row: conditional advance of `closed_through` to the period end, its event row; the period edge `OPEN → LOCKED`, the pinned mapping version, the close run row; outbox `AccountingPeriodLocked` |
| T-post (every existing posting) | unchanged, plus ledger `V028`'s `BEFORE INSERT` trigger on `journal_entry`: namespace `14` key `cutoff` **shared**, then refuse `posting_date <= closed_through` (SQLSTATE mapped to `ledger.PeriodClosed`) |
| T-check | claim one `close_check` `FOR UPDATE SKIP LOCKED` with a lease; run it in `REPEATABLE READ READ ONLY` reads; record the attempt and outcome by conditional edge |
| T-close / T-unlock / T-reopen | the period row `FOR UPDATE`; for unlock/reopen namespace `14` exclusive and the cutoff moved back by exactly one period; approver ≠ proposer `CHECK`; the report runs `SUPERSEDED` on reopen |
| T-report | claim: insert-or-read the run row by its unique key; generate: `REPEATABLE READ READ ONLY` on a separate connection; complete: conditional `GENERATING → FINAL` with content and sha256 |
| T-ppa | the target `OPEN` period `FOR SHARE`; the proposal; at approval `PriorPeriodPostings.approveOwned` posts the `ACCOUNTING` adjustment (posting date in the open period) and the PPA row commits with it |

### 8.2 The Phase 14 lock order (planned — `DISTRIBUTED_EXECUTION.md` §3's row, built by `-004`, `-005`, `-012`, `-013`)

(A1) accounting period rows, oldest first → (A2) namespace `14` (`cutoff`: shared in every journal insert, exclusive
only at the lock/unlock/reopen edges; `mapping`: exclusive at activation) → (A3) the ledger's `posting_cutoff` row →
(A4) accounting child rows (close run, check, report run, break, PPA). Posting transactions take only (A2) shared, after
all their money rows and at their entry insert — they never take an accounting row — and the cutoff transactions take no
money row, so no cycle exists. The exclusive request queues new shared requests behind it: the pause is bounded by the
longest in-flight posting transaction, and T-lock runs under a `lock_timeout` and retries.

### 8.3 Contention table (each row a counted test)

| # | Race | Arbiter | Test |
|---|---|---|---|
| 1 | Ten posting transactions dated in period P vs the lock of P | namespace `14` shared/exclusive + the trigger's re-read | `PostingCutoffDatabaseTest#tenPostersAndALockLeaveNoEntryAfterTheCut` |
| 2 | Two instances lock the same period | conditional edge `OPEN → LOCKED` | `AccountingPeriodDatabaseTest#twoLocksOneEdge` |
| 3 | Two approvers approve one close | conditional edge, revision | `PeriodCloseDatabaseTest#twoApprovalsOneClose` |
| 4 | Proposer approves own close / mapping / PPA / submission | `CHECK (approved_by <> proposed_by)` | `*#theProposerCannotApprove` |
| 5 | Ten instances claim one close check | `FOR UPDATE SKIP LOCKED` + lease | `CloseRunDatabaseTest#tenWorkersOneAttempt` |
| 6 | A worker dies mid-check | lease expiry, attempt row | `CloseRunDatabaseTest#aDeadWorkersCheckIsReclaimed` |
| 7 | Two instances generate the same report | unique run key, insert-or-read | `ReportRunDatabaseTest#twoGeneratorsOneRun` |
| 8 | Mapping activation vs a lock pinning a version | namespace `14` `mapping` exclusive vs the lock's read of the active version under the period row | `GlMappingDatabaseTest#aLockBehindAnActivationPinsOneVersion` |
| 9 | Reopen vs a posting into the next period | reopen moves the cutoff back one period only; postings dated after are unaffected | `PeriodReopenDatabaseTest#aReopenRefusesNothingOutsideItsPeriod` |
| 10 | The same break raised by two checks / instances | `UNIQUE (period_id, check_code, subject_ref)` | `AccountingBreakDatabaseTest#twoRaisesOneBreak` |
| 11 | A lending accrual catch-up racing the lock | the floor read under the shared lock (C3) | `LoanAccrualCutoffDatabaseTest#aCatchUpAcrossALockPostsInTheOpenPeriod` |
| 12 | Duplicate PPA approval | the ledger's adjustment key + conditional edge | `PriorPeriodAdjustmentDatabaseTest#aReplayedApprovalPostsOnce` |
| 13 | Duplicate submission | the provider idempotency key = submission id; `SUBMITTED` committed before the call | `RegulatorySubmissionDatabaseTest#aRetriedSubmitSubmitsOnce` |

### 8.4 Schedules

One leaderless schedule, **`AccountingCloseSchedule`** (`LEASE_PROTECTED_SCHEDULERS` 31 → 32, re-verified at `-011`):
legs (a) open the next period when the current month begins (insert-if-absent, unique by month); (b) claim and run close
checks; (c) complete claimed report runs whose generator died (lease expiry); (d) regulatory inquiry for `SUBMITTED`
rows past their answer horizon (`-018`). Every leg meters its claim failures from its first task; each is a
`SmartLifecycle`, off in test contexts, with its `finapp.accounting.sweeper.enabled` gauge. Nothing else schedules.

### 8.5 The ten-instance answer, per concern

Duplicate accounting events: none consumed for correctness (the journal is read). Concurrent posting vs close: §8.2.
Concurrent close: rows 2–4. Duplicate accruals: lending `UNIQUE (loan_id, accrual_date)` and the key, unchanged.
Concurrent adjustments: the ledger's proposal row and four-eyes, unchanged; PPAs keyed. Replayed or delayed events: a
FINAL run reads a locked period's posting-date range, immutable after the cutoff; a replay inserts nothing. Concurrent
report generation and reconciliation workers: rows 5–7. Restart and partial batches: every check and run is resumable
from its rows; a run's content commits whole or not at all. Races: every edge conditional, every birth unique.

---

## 9. Data architecture

Schema `accounting` (`REVOKE ALL FROM PUBLIC`; the application role `SELECT, INSERT` on every table, `UPDATE` only on the
status columns of the machine rows through conditional edges, no `DELETE`): `gl_mapping_version`, `gl_account`,
`gl_mapping_rule` (`UNIQUE (version_id, ledger_purpose, owner_kind)`), `accounting_period` (`UNIQUE (period_start)`,
`CHECK` months contiguous), `period_event`, `close_run`, `close_check` (`UNIQUE (close_run_id, check_code, attempt)`),
`report_run` (`UNIQUE (definition, definition_version, period_id, revision, parameters_hash)`, content `jsonb` frozen by
trigger, `content_sha256`), `valuation_rate_set`, `valuation_rate` (snapshot ids and provenance), `accounting_break`,
`prior_period_adjustment`, `regulatory_obligation`, `regulatory_submission`. Ledger: `V028` `posting_cutoff` (one row,
`closed_through date`) + `posting_cutoff_event` (append-only) + the trigger; `V029` index `journal_entry (posting_date,
id)`; `V030` origin `ACCOUNTING` + reason `PRIOR_PERIOD_ADJUSTMENT`. Identity `V024`: the permissions and roles (C12).
Lending: no migration for `-006` (code only); `-021` (if C6 A) takes lending and bnpl migrations then numbered.
Migration numbers are assigned in landing order (Flyway out-of-order off); next free re-verified at the transition:
accounting `V001`, ledger `V028`, identity `V024`, lending `V032`, bnpl `V020`. Amounts: `BIGINT` minor units + `CHAR(3)` +
scale everywhere (`MoneyColumns`); rates `NUMERIC(20,10)` as fx stores them; no floating point (`INV-MON-01`).

## 10. API architecture

All operator surfaces under `/v1/operator/accounting/…` and `/v1/operator/regulatory/…`, `Idempotency-Key` on every
`POST`, `409` on a stale revision, errors in `ERROR_CONTRACT.md` (`accounting.*`): periods (list, lock, unlock and
reopen proposals and approvals, close proposal/approval/rejection); mapping versions (propose, activate, reject, read,
diff); GL (`/gl/balances?period&currency`, `/gl/accounts/{code}/activity?period` paged to journal lines, `/gl/entries/{id}`
the GL view of one entry); report runs (generate, read, `…/regeneration` answering `IDENTICAL | DIVERGED`); valuation
rate sets; breaks; prior-period adjustments; regulatory submissions. No customer or merchant surface reads `accounting`
(`NoCustomerSurfaceReadsAccountingTest`). Every read of a report or GL drill-down is an audited operator read.

## 11. Event architecture

Outbox events (full envelope, `INV-EVT-03`), ids and codes only, never amounts: `accounting.AccountingPeriodOpened`,
`AccountingPeriodLocked`, `AccountingPeriodCloseProposed`, `AccountingPeriodClosed`, `AccountingPeriodReopened`,
`GlMappingVersionActivated`, `ReportRunCompleted` (the `TrialBalanceGenerated` of `DELIVERY_PLAN.md` is its kind
`TRIAL_BALANCE`), `AccountingBreakRaised`, `PriorPeriodAdjustmentPosted`, `RegulatorySubmissionSubmitted`,
`…Acknowledged`, `…Rejected`. Accounting **consumes no event for correctness**: `ledger.JournalEntryPosted` may pull a
provisional report forward, never feed a figure (`INV-EVT-02`).

## 12. Security, audit and privacy (owner brief 7.11)

Roles (C12): `ACCOUNTANT` — `ACCOUNTING_READ`, `ACCOUNTING_MAPPING_PROPOSE`, `ACCOUNTING_CLOSE_PROPOSE`,
`ACCOUNTING_ADJUST_PROPOSE`, `ACCOUNTING_REPORT_GENERATE`; `FINANCIAL_CONTROLLER` — `ACCOUNTING_READ`,
`ACCOUNTING_MAPPING_APPROVE`, `ACCOUNTING_CLOSE_APPROVE`, `ACCOUNTING_ADJUST_APPROVE`, `REGULATORY_REPORT_APPROVE`;
`REGULATORY_REPORTER` — `ACCOUNTING_READ`, `REGULATORY_REPORT_PREPARE`, `REGULATORY_REPORT_SUBMIT`. No role proposes and
approves the same act; approver ≠ proposer by `CHECK` on every four-eyes row (`INV-AUD-04`). Every act in
`AUDITABLE_ACTIONS.md`'s new `accounting.*` / `regulatory.*` set with actor, time, target, reason, correlation and outcome;
report content `CONFIDENTIAL` (aggregates), drill-down lines at the ledger's classification, regulatory content
`CONFIDENTIAL`; no customer name or instrument in any accounting row (owner refs only); report generation and drill-down
in read-only transactions (C10, `INV-ACC-14`); the ledger's grants unchanged (the cutoff row is written only through the
ledger's own `PostingCutoffs` API, whose two statements are its own).

---

## 13. The accounting model

### 13.1 The model (owner brief 7.2)

| Concept | Status | Where |
|---|---|---|
| Chart of accounts | exists (the ledger's, purposes) | `ledger`; the GL chart (GL Account) **new** in `accounting` (`-003`) |
| Account classification | exists (`AccountType`, `NormalBalance`); GL classification + statement section **new** | `accounting.gl_account` |
| Accounting period | **new** | `accounting.accounting_period` (`-005`); the cutoff in `ledger` (`-004`) |
| Accounting entry | = the journal entry viewed through a mapping version — **not stored** | derived (`-007`) |
| Posting rule | exists in code at each poster; **register new** | posters; `accounting.PostingRuleRegister` (`-002`) |
| Mapping | **new**, versioned | `gl_mapping_version`/`gl_mapping_rule` (`-003`) |
| Accounting event | = the economic event's journal entry, classified by its key namespace | derived; register (`-002`) |
| Accrual | exists (lending, bnpl daily interest) | lending, bnpl; completeness a close check (`-011`) |
| Fee / interest revenue | exists (`FEE_REVENUE`, `LOAN_*_INCOME`, `BNPL_*_INCOME`, `FX_SPREAD_REVENUE`) | ledger purposes; reported (`-017`) |
| Receivable / payable | exist as purposes (loan/BNPL assets, `CHARGEBACK_RECOVERABLE`, clearings; `CUSTOMER_WALLET`, `MERCHANT_PAYABLE`, `CORRIDOR_CLEARING`, credit balances) | GL sections (`-003`), reports (`-017`) |
| Cash / settlement | exist (`CASH_AT_BANK`, `SETTLEMENT_CLEARING`, `INSTANT_CLEARING`, `PAYOUT_CLEARING`, provider clearings) | reported (`-017`) |
| Suspense | exists (`SUSPENSE_UNMATCHED`, items owned by breaks) | disclosed (`-011`, `-016`) |
| Trial balance | exists (ledger, continuous); per period **new** | `-009` |
| GL report | **new** | `-007`, `-017` |
| Financial statement | **new** | `-016` |
| Period close | **new** | `-011`, `-012` |
| Adjustment / reversal | exist | ledger; PPA origin **new** (`-013`) |
| Accounting reconciliation | proofs exist; register conformance and breaks **new** | `-010` |

### 13.2 Posting rules and source mappings (owner brief 7.3)

Every rule below **already posts** in its source domain; Phase 14 registers it (rule id, version, namespace, line
shape, dating class) and checks conformance — it invents no treatment. "Lines" are as the cited catalogue and code
record them; `-002` reads each against its code site and corrects this table by erratum.

| Source event | Source / authoritative ref | Namespace (rule) | Lines (DR / CR) | Currency, dates | Duplicates · correction · evidence |
|---|---|---|---|---|---|
| Internal transfer | `transfers` transfer id (`TransferExecution`) | `transfer:` | DR sender wallet / CR receiver wallet (+ fee lines where the transfer carries one) | one currency; posting = value = today | keyed; correction by reversal; internal — no external evidence |
| Card / wallet capture (top-up, checkout) | `payments` attempt (`PaymentOutcomes`) | `payment-capture:` | DR `SETTLEMENT_CLEARING` / CR wallet, or `MERCHANT_PAYABLE` gross with the fee split (ADR-0050) | today | keyed; refunded by `payment-refund:`; PSP report → settlement batch → bank (ADR-0065) |
| Authorisation | `payments` attempt | — | **no entry** (a hold only, `INV-BAL-04`) | — | no accounting effect until capture |
| Refund | `payments` refund | `payment-refund:` | DR wallet or payable / CR `SETTLEMENT_CLEARING` | today | keyed; PSP evidence |
| Withdrawal / payout | `payments` withdrawal; `merchant` payout | `wallet-withdrawal:`, `merchant-payout:` | DR wallet / CR `PAYOUT_CLEARING`; DR `MERCHANT_PAYABLE` / CR `PAYOUT_CLEARING` | today | keyed; returns by evidence (ADR-0073) |
| Disputes | `payments` dispute (`ChargebackAccounting`) | `dispute-chargeback:`, `-attribution:`, `-won:`, `-restoration:`, `-loss:`, `-fee:`, `-reattribution:` | per `RAIL_AND_DISPUTE_LIFECYCLES.md` stage table (ADR-0061) | today | keyed per stage; bounded |
| Settlement recognition | `settlement` batch (`BatchAcceptance`) | `settlement-batch:` | `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md` §3.3 (fees to `PROCESSING_COSTS`, bank lines to `CASH_AT_BANK`, unattributed to `SUSPENSE_UNMATCHED`) | posting = `accepted_on` (stored), value = the batch date | keyed; repudiation reverses; the batch's own evidence |
| Reconciliation resolution | `reconciliation` resolution | `RECONCILIATION`-origin adjustments | write-off to `RECONCILIATION_LOSSES`, gain to `RECONCILIATION_GAINS`, transfers out of suspense (ADR-0071) | today | four-eyes; the break's case file |
| FX conversion, cover, unwind, reversal | `fx` trade / cover (`FxConversion`, `FxCoverOutcomes`) | `fx-trade:`, `fx-cover:` | `LEDGER_MODEL.md` §9 (legs on `FX_POSITION`, `FX_SPREAD_REVENUE`, `ROUNDING_RESIDUAL`, `FX_REALISED_GAINS`/`LOSSES`) | per-currency legs; executed amounts; posting dates from stored rows | keyed; trade reversal mirrors; provider evidence (`INV-FX-08`) |
| Cross-border credit and return | `payments`/`crossborder` credit | `outbound-credit:`, `crossborder-return:`, `crossborder-return-fee:` | `LEDGER_MODEL.md` §9 | today (`X-TSK-011`) | keyed; corridor evidence |
| Lending capital | `reconciliation` recognition | rule 0 | DR `CASH_AT_BANK` / CR `LENDING_CAPITAL` | bank date | bank statement |
| Loan disbursement, billing, accrual, repayment, fees, waivers, reversals, payoff, credit lines | `lending` (ADR-0096 §2 rules 1–13) | `lending.*` | `LEDGER_MODEL.md` §10; accrual DR `LOAN_INTEREST_ACCRUED` / CR `LOAN_INTEREST_INCOME` | accrual: **posting date = accrual date** (back-dating; C3) | keyed per act/date; reversal and waiver four-eyes; `LoanSubledgerProof` |
| BNPL financing, billing, accrual, repayment, refund, fee, waiver, reversal, payoff | `bnpl` (ADR-0103 F1–F10) | `bnpl.*` | `LEDGER_MODEL.md` §11 (merchant settlement F1 net of discount; customer repayment F3) | accrual posting today, value the date | keyed; `BnplThreeWayProof` |
| Approved manual adjustments | `ledger` proposals | `MANUAL` origin | as approved | today | four-eyes (`INV-REV-04`) |
| Write-off (C6 A) | `lending` / `bnpl` | rule 14 / F11 | per-loan assets → `LOAN_WRITE_OFF_EXPENSE` / `BNPL_WRITE_OFF_EXPENSE` | today | four-eyes; headroom fix (ADR-0104) |
| Prior-period adjustment | `accounting` | `ACCOUNTING` origin | as approved, operational purposes not closed to free adjustments | today, open period | four-eyes; references the corrected closed period |

**Rule versioning.** The register is code (`accounting`, pure data, purposes as strings), versioned by a catalogue
version constant; a changed rule is a new version entry with an `effective_from` posting date, and an entry is judged
by the version in force at its posting date. `app`'s `PostingRuleRegisterTest` fails the build when a posting key prefix
in any source module is unregistered or a registered purpose is not an `AccountPurpose` (the `*HasOnePosterTest`
precedent).

### 13.3 GL ↔ ledger ↔ source reconciliation (owner brief 7.4)

Three levels, each a close check producing born-once `accounting_break` rows, never a correction: (1) **GL = ledger** —
per period and currency, Σ GL debits/credits under the pinned version = Σ journal; every line mapped exactly once
(`MAPPING_GAP`, `DOUBLE_MAPPING`); (2) **rule conformance** — every entry in the period has a registered namespace
(`UNREGISTERED_POSTING`) and a conforming line shape (`SHAPE_MISMATCH`), its entries balanced per currency (an
`UNBALANCED_ENTRY` break is impossible by trigger and still checked); (3) **source completeness** — the existing proofs'
verdicts read through `SourceProofs` (lending subledger and replay, bnpl three-way and subledger, fx books, position
proof, merchant payable, projection verification): a missing or duplicate entry, an amount or currency discrepancy, a
reversed source with its entry unreversed, are each that proof's finding raised as `SOURCE_DISCREPANCY` with the proof's
evidence. Delays: an entry expected but not yet posted (accrual lag, a batch accepted after the cutoff) is
`SOURCE_DELAYED` — blocking for accruals, disclosed for evidence in transit. Resolution is a correcting entry through the
owning domain's existing door (reversal, waiver, reconciliation resolution, PPA), referenced by the break; reconciliation's
own breaks and suspense are disclosed, not copied (C11, `INV-ACC-05`).

### 13.4 Multi-currency (owner brief 7.5)

Transaction currency = the line's (ledger, one per account); functional = reporting = **EUR** (C1); settlement currency
= the clearing account's. Original amounts are never converted in the journal. A **valuation rate set** is captured at
lock from `fx.rate_snapshot` reference rates (source, pair, rate, `observed_at`, snapshot id — the last observation at or
before the period end, a staleness bound refusing older), approved four-eyes, immutable, pinned by every FINAL run; it
is never a customer quote or an executed rate, and a customer's executed rate is never re-read for valuation
(`INV-FX-05` kept). Translation: per line group, `HALF_EVEN` to the reporting currency's minor unit, the sum's
difference from the translated total shown as an explicit **translation difference** line (C7) — never absorbed.
Realised FX results are reported from their posted purposes; unrealised results are not posted (C7 A).

### 13.5 Period close (owner brief 7.6)

**Before lock:** the period is `OPEN`; postings dated in it commit. **Readiness to lock:** the calendar grace has passed
(C9); the previous period is `CLOSED`. **At lock (T-lock):** the ledger cutoff advances atomically — from its commit no
entry dated in the period commits (`INV-ACC-08`); the mapping version is pinned. **During close (`LOCKED`):** the close
run's checks — `LEDGER_TRIAL_BALANCE` (per currency, cumulative and period), `GL_EQUALS_LEDGER`, `RULE_CONFORMANCE`,
`SOURCE_COMPLETENESS`, `ACCRUALS_COMPLETE` (lending and bnpl accrued through the period end), `PROJECTION_VERIFIED`,
`RECONCILIATION_DISCLOSURE` (open breaks, suspense balance and ageing as of the cutoff), `VALUATION_SET_APPROVED`,
`OPEN_ACCOUNTING_BREAKS`; then the FINAL trial balance run. Corrections found during close post in the **next open
period** (dated today), never into the locked one; an unlock (four-eyes) is the only way back. **Proposal and approval:**
an `ACCOUNTANT` proposes with every check `PASSED` or `DISCLOSED` (a disclosure a reasoned record); a
`FINANCIAL_CONTROLLER` approves; `CLOSED`. **After close:** the period's journal cut and its FINAL runs are immutable;
corrections are prior-period adjustments (`-013`); a reopen (four-eyes, latest only) moves the cutoff back one period,
supersedes (never deletes) its FINAL runs, and re-runs the close with `revision + 1` (`INV-ACC-10`). No silent alteration
is possible: the ledger refuses the posting, the run's content is frozen by trigger, and every edge is evented and audited.

### 13.6 Financial reporting (owner brief 7.7)

Every report is a **report definition** (code, versioned) producing a **report run**: FINAL for a `LOCKED`/`CLOSED`
period (reproducible — its posting-date range is immutable after the cutoff, its mapping and valuation set pinned:
`INV-ACC-04`), PROVISIONAL for an open period (cut at the journal's settle horizon, labelled, never issued). Reports:
**trial balance** (per GL account × currency: opening, debits, credits, closing; zero per currency; equal to the
ledger), **GL activity** (per account, drill-down to lines), **balances**, **fee and interest revenue**, **receivables
and payables**, **settlement and clearing positions**, **suspense** (with ageing from reconciliation), **FX gains and
losses** (realised, spread, residual), **loan portfolio** and **BNPL exposure** (the existing lending/bnpl report reads
through ports, re-stated as of the period end), **reconciliation status** and **close status**, and the **balance sheet
and income statement** (statement sections from the mapping; current-period result and retained earnings computed, C8;
notes disclosing suspense, open breaks, PPAs and the deferred treatments of C6/C7). Operational dashboards (the existing
live operator reports and gauges) stay operational and are labelled so; no cache, projection or gauge is a financial
figure's source (`INV-BAL-05`).

### 13.7 Regulatory reporting (owner brief 7.8)

A **regulatory obligation** is configuration data: jurisdiction code, form code and version, period type, the report
definition(s) it draws from, the line mapping (form line ← report figure), validation rules, and the adapter. A
**submission** is drafted only from FINAL runs of `CLOSED` periods (lineage: run ids and hashes), validated, approved
four-eyes, and submitted through `RegulatoryReportingProvider` — **simulated** (C4), its export format a reference
document; acknowledgement and rejection recorded by an inquiry leg; correction and resubmission a new submission
referencing the original (`INV-ACC-13`). The first obligation is the reference **safeguarding position return** (C13):
customer and merchant liabilities vs `CASH_AT_BANK` and clearing receivables, `lending_funded` subtracted (ADR-0096 §6).
No legal obligation is asserted and nothing claims compliance.

---

## 14. Testing strategy

### 14.1 Tiers (C5 — the standing criterion-7 deviation)

Per task: the hermetic tier of the touched modules, the task's `app` tests by filter, and the guard suites by name
(`AccountingModuleIsolationTest`, `NoCustomerSurfaceReadsAccountingTest`, `ReportingNeverWritesTest`,
`PostingRuleRegisterTest`, `NoSingleInstanceAssumptionRulesTest`, `ColumnClassificationTest`, `RoutePermissionRegisterTest`,
`NoFloatingPointMoneyRulesTest`, `OpenApiContractTest`); the task's database suites written and owed a first run; no
fleet `databaseTest`/`kafkaTest` — a criterion-7 deviation in every entry's gate. One owner-authorised database pass
before `P14-DOC-001`. Every migration test asserts constraint-name uniqueness per table.

### 14.2 Mandatory scenarios (each a named test in its task)

(1) rule correctness — every registered namespace's line shape matches the code's postings (`-002`, `-010`); (2) balance —
the period trial balance zero per currency and equal to the ledger (`-009`); (3) duplicates — a replayed posting key,
report generation, break raise, PPA approval and submission each one effect (`-008`, `-010`, `-013`, `-018`);
(4) source-to-GL — a planted unmapped purpose, double mapping, unregistered namespace and source discrepancy each raise
exactly one break (`-010`); (5) conversion and rounding — translation of every currency at three scales, the
difference line explicit (`-015`); (6) accruals — a lending catch-up across a lock posts in the open period with its
value date kept (`-006`); (7) cutoff — ten posters vs a lock, none after the cut (`-004`); (8) concurrent posting vs
close — scenario 7 under a close run (`-011`); (9) replay and recovery — a killed check worker and a killed generator
resumed, nothing doubled (`-011`, `-008`); (10) report consistency — regeneration `IDENTICAL`, a reopen superseding
(`-008`, `-012`); (11) access and approval — every proposer refused as approver, every route permission-registered
(`-001`, `-003`, `-012`, `-013`, `-018`); (12) lineage — every FINAL figure drills to journal lines and every submission
line to a run figure (`-007`, `-018`).

### 14.3 The storm and the battery

`P14-TST-002` (the battery): ≥ 10,000 generated entries over five currencies and twelve periods, an independent oracle
for the GL, trial balance, statements and translation, regenerated in two JVMs byte-identical; a perturbed mapping rule
or rate flipping exactly the named rows. `P14-TST-001` (the storm, ten instances posting while periods lock and close,
reports and checks racing): **planned and `POSTPONED`** by the owner until after the project's phases (PI-004); its
evidence is deferred, named and not claimed.

## 15. Failure scenarios

A lock times out behind a long posting transaction (retried; nothing half-done). A check worker dies (lease, re-claim).
A generator dies (lease; the run's content commits whole). A posting arrives during close (refused if dated in the
locked period — the producer posts today; lending's accrual floors, C3). A mapping is changed after postings exist (a new
version, effective from an open period; closed periods keep theirs). A report generated from a stale projection
(impossible: runs read the journal, never a projection). The provider is down or answers unknown (`SUBMITTED` committed
first; inquiry). A source proof is unavailable at close (`SOURCE_COMPLETENESS` `FAILED`, never assumed). A reopen while
the next period is open (allowed; its postings unaffected). The database commits a lock but the response is lost (the
retry reads `LOCKED` and answers it).

## 16. Observability

Every series is `finapp.accounting.*`; tags from `MetricNames.ALLOWED_TAG_KEYS` plus any bounded key the building task
adds deliberately (`check`, `definition`); no tag carries an id, an amount or a name. `PlannedMetersExistTest` reads this
table once Phase 14 is `COMPLETE`; `-020` registers what earlier tasks did not and an `AccountingMetersTest` counts the
rows both ways.

| Meter | Type | Tags | Alert |
|---|---|---|---|
| `finapp.accounting.period.open.age` | gauge (days the oldest unclosed past period has been past its grace) | — | `AccountingCloseOverdue` |
| `finapp.accounting.close.check` | counter | check, outcome | `AccountingCloseCheckFailed` |
| `finapp.accounting.close.duration` | timer | — | — |
| `finapp.accounting.cutoff.refused` | counter (postings refused `ledger.PeriodClosed`) | module | `AccountingPostingIntoClosedPeriod` |
| `finapp.accounting.gl.unmapped` | gauge (lines unmapped in the open period under the active version) | — | `AccountingMappingGap` |
| `finapp.accounting.break.open` | gauge | type | `AccountingBreakAged` |
| `finapp.accounting.report.run` | counter | definition, outcome | — |
| `finapp.accounting.report.diverged` | counter | definition | `AccountingReportDiverged` |
| `finapp.accounting.regulatory.submission` | counter | outcome | `RegulatorySubmissionRejected` |
| `finapp.accounting.sweeper.enabled` | gauge | — | — |

## 17. Milestones

| Milestone | Acceptance | Tasks |
|---|---|---|
| **M14.1 Foundations** | the module, floors, roles, vocabularies; the posting rule register; the GL chart and mapping under four eyes | `-001`…`-003` |
| **M14.2 Periods and cutoff** | the ledger cutoff; periods, lock and unlock; lending's accrual cutoff-safe | `-004`…`-006` |
| **M14.3 GL and trial balance** | GL read model with drill-down; immutable report runs; the period trial balance | `-007`…`-009` |
| **M14.4 Reconciliation and close** | conformance and breaks; the close run; four-eyes close and reopen; prior-period adjustments | `-010`…`-013` |
| **M14.5 Multi-currency** | valuation rate sets; translation in reports | `-014`, `-015` |
| **M14.6 Financial reporting** | statements; operational financial reports | `-016`, `-017` |
| **M14.7 Regulatory reporting** | the framework and simulated adapter; the safeguarding return (C13) | `-018`, `-019` |
| **M14.8 Operations** | meters, alerts, runbook §10 | `-020` |
| **M14.9 Write-off (optional, C6)** | write-off under four eyes with the headroom fix | `-021` |
| **M14.10 Exit** | the battery, the review (the storm postponed) | `P14-TST-002`, `P14-TST-001` (`POSTPONED`), `P14-DOC-001` |

**Cut order:** `-021`, then `-019`, then `-017`'s portfolio/exposure re-statements. **Never cut:** the cutoff (`-004`),
four-eyes close (`-012`), report reproducibility (`-008`), conformance (`-010`), the battery.

## 18. Backlog (small vertical slices — elaborated in [`BACKLOG.md`](BACKLOG.md) §Phase 14)

| ID | Title | Milestone | Depends on | Migrations | DoD profiles |
|---|---|---|---|---|---|
| `P14-TSK-001` | The `accounting` module boundary, floors, vocabularies and roles | M14.1 | Phase 13 `COMPLETE` | accounting `V001`, identity `V024` | `DOD-ARCH`, `DOD-SEC` |
| `P14-TSK-002` | The posting rule register, as built | M14.1 | `-001` | none | `DOD-FIN`, `DOD-ARCH` |
| `P14-TSK-003` | The GL chart and mapping as versioned data, four-eyes | M14.1 | `-001` | accounting `V002` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P14-TSK-004` | The ledger posting cutoff | M14.2 | `-001` | ledger `V028` | `DOD-FIN`, `DOD-DOMAIN` |
| `P14-TSK-005` | Accounting periods: open, lock, unlock | M14.2 | `-003`, `-004` | accounting `V003` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P14-TSK-006` | Back-dating posters made cutoff-safe (lending accrual) | M14.2 | `-002`, `-004` | none | `DOD-FIN` |
| `P14-TSK-007` | The GL read model and drill-down | M14.3 | `-003`, `-005` | ledger `V029` | `DOD-FIN`, `DOD-API` |
| `P14-TSK-008` | Report runs: immutable, reproducible | M14.3 | `-005` | accounting `V004` | `DOD-DOMAIN`, `DOD-API` |
| `P14-TSK-009` | The period trial balance | M14.3 | `-007`, `-008` | none | `DOD-FIN`, `DOD-TEST` |
| `P14-TSK-010` | Rule conformance, source completeness and accounting breaks | M14.4 | `-002`, `-007` | accounting `V006` | `DOD-FIN`, `DOD-DOMAIN` |
| `P14-TSK-011` | The close run and its readiness checks | M14.4 | `-006`, `-009`, `-010` | accounting `V007` | `DOD-FIN`, `DOD-DOMAIN` |
| `P14-TSK-012` | Four-eyes close, rejection and reopen | M14.4 | `-011` | accounting `V008` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P14-TSK-013` | Prior-period adjustments | M14.4 | `-012` | ledger `V030`, accounting `V009` | `DOD-FIN`, `DOD-SEC` |
| `P14-TSK-014` | Valuation rate sets | M14.5 | `-005` | accounting `V005` | `DOD-FIN`, `DOD-SEC` |
| `P14-TSK-015` | Reporting-currency translation | M14.5 | `-009`, `-014` | none | `DOD-FIN`, `DOD-TEST` |
| `P14-TSK-016` | Financial statements | M14.6 | `-013`, `-015` | none | `DOD-FIN`, `DOD-API` |
| `P14-TSK-017` | Operational financial reports | M14.6 | `-008`, `-015` | none | `DOD-API` |
| `P14-TSK-018` | The regulatory reporting framework | M14.7 | `-016` | accounting `V010` | `DOD-DOMAIN`, `DOD-API`, `DOD-SEC` |
| `P14-TSK-019` | The safeguarding position return (C13) | M14.7 | `-018` | none | `DOD-FIN` |
| `P14-TSK-020` | Operating accounting: meters, alerts, runbook | M14.8 | `-012`, `-017`, `-018` | none | `DOD-OBS` |
| `P14-TSK-021` | Loan and BNPL write-off under four eyes (C6, cut candidate) | M14.9 | `-002`, `-007` | lending, bnpl (then numbered) | `DOD-FIN`, `DOD-SEC` |
| `P14-TST-002` | The accounting battery | M14.10 | `-009`, `-015`, `-016` | none | `DOD-TEST` |
| `P14-TST-001` | The accounting storm — `POSTPONED` (PI-004) | M14.10 | all | none | `DOD-TEST` |
| `P14-DOC-001` | The Phase 14 exit review | M14.10 | all but `TST-001` | none | `DOD-DOC` |

Migration numbers here are the planned landing order (waves: **A** `-001`; **B** `-002`, `-003`, `-004`; **C** `-005`,
`-006`; **D** `-007`, `-008`, `-014`; **E** `-009`, `-010`; **F** `-011`, `-015`; **G** `-012`, `-017`; **H** `-013`,
`-021`; **I** `-016`; **J** `-018`; **K** `-019`, `-020`; **L** `P14-TST-002`, then `P14-DOC-001`); each wave's board
re-verifies them and renumbers rather than land a lower number after a higher. Critical path: `-001` → `-003` → `-005` →
`-007` → `-009` → `-011` → `-012` → `-013` → `-016` → `-018` → `-020` → `TST-002` → `DOC-001`.

## 19. Exit criteria (summary — the gate is `PHASE_GATES.md` §5 Phase 14)

Rule correctness and traceability; GL = ledger; ledger reconciliation by break, never correction; multi-currency with
originals kept; period close enforced by the ledger and approved four-eyes; reproducible reporting; reporting never
writes; ten-instance races counted; the battery green; documentation and ADRs read against the code.

## 20. ADRs (next free number ADR-0113; all `Proposed` at the transition)

ADR-0113 the `accounting` bounded context and the GL as a versioned mapping over the one journal · ADR-0114 accounting
periods, the posting cutoff in the ledger and the close · ADR-0115 reporting as immutable, reproducible report runs ·
ADR-0116 multi-currency valuation and translation · ADR-0117 the posting rule register, GL reconciliation and accounting
breaks · ADR-0118 regulatory reporting behind jurisdiction adapters. The next free number after the transition is
ADR-0119.

## 21. What Phase 14 must NOT implement

A second ledger or any accounting table holding a balance; a write to a ledger table from `accounting`; a posting
treatment the owner has not ratified (revaluation postings, provisioning/ECL, EIR amortisation, closing entries — C6–C8);
an automatic write-off policy; a real regulator, filing channel or statutory chart; a customer- or merchant-facing
accounting surface; a cache or projection as a reported figure's source; Phase 15's separate reporting role (C10) or
data purge; a service outside the monolith.

## 22. Risks

Reporting drifting from the ledger (answered: no stored balance; GL = ledger checked every close). A close permitting
silent back-dated postings (answered: the ledger refuses at insert, `INV-ACC-08`). Mapping applied retroactively
(answered: versions pinned per period). The cutoff's exclusive lock pausing posting (bounded; measured by the storm when
run). An accounting treatment mistaken for a qualified one (answered: §1.1, C3/C6–C8 listed for review).

## 23. The first task

**`P14-TSK-001` — The `accounting` module boundary, floors, vocabularies and roles** — `READY`, marked by this
transition. Mirrors `P13-TSK-001`: the module and its `V001` floor (no table), `AccountingModuleIsolationTest` (edges
to `platform` and `sharedkernel` only), the closed vocabularies, the ports declared empty, `AccountingAuditAction` and
`AccountingErrorCode` with their register rows, identity `V024` admitting the three roles and eleven permissions (C12),
`NoCustomerSurfaceReadsAccountingTest` and `ReportingNeverWritesTest` armed. First because every later task writes into
this schema through these ports, and the role split is the trust decision the close rests on. It depends on no owner
decision.

## 24. The transition's carried items (Phase 14 rows of `CURRENT_STATE.md` debt and `DECISIONS.md` §Deferred)

| Item | Disposition (recommended) |
|---|---|
| Write-off, charge-off, provisioning; EIR of the origination fee and the merchant discount | C6: write-off `-021` (cut candidate); provisioning and EIR disclosed, deferred for qualified review |
| The safeguarding proof subtracting `lending_funded` | C13: `-019` |
| Revaluation, functional/reporting currency, FX P&L reporting | C1, C7: `-014`, `-015`, `-017` (FX gains and losses report) |
| An equity account for a non-zero first bank opening | Deferred to go-live (Phase 15): no real bank in the reference platform; ratify |
| The lending proofs' cost at scale | Re-owned to Phase 16 (scale), where measurement belongs; ratify |
| The BNPL replay re-deriving refunds through production functions | Stays with the next proof change; not accounting's |
| A report dating a posting across a backward clock step | FINAL runs read locked posting-date ranges and are immune; the live BNPL report's debt stays |
| Collections write-off | With C6's write-off; collections operations stay a later context |
