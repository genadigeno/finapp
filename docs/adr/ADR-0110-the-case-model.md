# ADR-0110 — The case model shared by fraud, ATO, merchant abuse, AML and collections: born-once alerts, one open case per subject and kind, concurrent updates by revision, four-eyes dispositions

Status: Proposed
Date: 2026-10-22
Phase: 13
Context: Risk (Case Management) · Fraud · AML · Lending and BNPL (collections, C3) · Identity · Audit
Supersedes: nothing. Applies ADR-0107 (case management is `risk`'s third capability and never decides money),
ADR-0108 (a `REVIEW` or `HOLD` opens a case in T-eval's transaction) and ADR-0112 (every effect of a case is a keyed
action through the owning domain). Rests on `PHASE_13_PLAN.md` §2.2 (C3, C6), §3.1 (Alert, Case, Investigator
Action, Disposition, Escalation), §4, §5, §7.1 (T-case), §7.2 (R4, R5), §7.4, §9, §11, §12.6, §12.8, §13.2
(scenarios 1, 2, 5, 6, 12, 15), §24 (OQ-6, OQ-10) and `INV-RSK-06`, `-07`, `-08`, `-10`.

## Context

1. **A judgement a person must make needs a place to be made.** A `REVIEW` or `HOLD` decision, a fraud rule's hit,
   a monitoring rule's hit, a sanctions rescreening hit and a loan or BNPL default each demand an investigator's act
   with a reason and an outcome — the same act, whatever raised it. `MODULE_ARCHITECTURE.md` M6 places case
   management in `risk` for that reason.
2. **Alert floods are the known failure of monitoring** (plan §21). A rule that fires on every movement of a
   subject in a window, or on every replay of a history, produces a queue nobody can work; the same subject's
   alerts must fold into one investigation.
3. **Investigators work concurrently on N instances.** Two analysts opening one case from two alerts, two updating
   one case at once, an event arriving after the case is disposed — each must keep every act and lose nothing.
4. **Collections is deferred, and the owner wants its door** (C3, OQ-10; `DECISIONS.md` §Deferred). Contact
   strategy, promises to pay and placement are a later collections context's; a case opened on a default, with
   investigator actions as facts, is a thin slice this model gives for free.
5. **Tipping-off.** An AML investigation's existence must never reach the subject (`INV-RSK-08`); the case model
   therefore has no customer-facing surface by construction.

## Decision

1. **The alert is born once and never deleted.** `risk_alert` (`risk V008`, `P13-TSK-015`): one row per
   (subject, rule code, window start), held by `UNIQUE (subject_ref, rule_code, window_start)` with
   `ON CONFLICT DO NOTHING`, carrying kind, severity, evidence references and the policy version that raised it.
   `OPEN → ATTACHED` (to a case) `| SUPPRESSED` — with a reason: deduplicated into an open case's window, or an
   operator's reasoned suppression under four eyes, audited `risk.AlertSuppressed`. A replayed event, a reprocessed
   history or two instances evaluating one window produce one row (`INV-RSK-06`).

2. **The case is the unit of a person's judgement.** `risk_case` (`risk V009`, `P13-TSK-016`): kinds `FRAUD`,
   `ATO`, `MERCHANT_ABUSE`, `AML`, `COLLECTIONS` (C3); `OPEN → IN_INVESTIGATION` (taken by one investigator)
   `→ ESCALATED` (optional; raises level and queue, reasoned) `→ DISPOSED`; **one open case per (subject, kind)** by
   a partial unique index; an SLA deadline stamped on the database clock; a `revision` counter. Opened by T-eval
   (`REVIEW`, `HOLD`, a policy-flagged `DENY` — the case row is `risk`'s and takes no money lock, OQ-2), by the sweep
   (an alert with no open case), by hand (`POST /v1/operator/risk/cases`), or by `-025` on `LoanDefaulted` /
   `BnplDefaulted`. **A case never re-opens** (OQ-6): a later alert for a disposed subject opens a new case that
   references the old one; the disposed case is untouched.

3. **Alerts fold into the subject's open case.** Raising an alert and attaching it are one transaction where the
   sweep or T-eval holds nothing else of `risk`: the case row `FOR UPDATE` (R4), then the alert rows (R5). An alert
   for a subject with an open case of the matching kind attaches to it; without one, it opens one — the partial
   unique arbitrates when ten alerts race (`CaseDatabaseTest#tenAlertsOneOpenCase`, scenario 12: a structuring
   sequence is alerted once per window and the case folds every alert).

4. **Investigator actions are append-only, reasoned and audited.** `risk_case_action`: note, evidence attached,
   taken, escalation, action proposed / approved / rejected (ADR-0112), disposition proposed / approved / rejected,
   report drafted (ADR-0111) — each with actor, time, reason where required, correlation and outcome; never edited,
   never deleted. Evidence is held as references (signal, assessment, journal entry, alert ids) resolved through
   audited reads, never copied; a note is bounded and screened by `InstrumentShapes`, `CONFIDENTIAL`.

5. **Concurrent updates: the row lock and the `revision` compare-and-set.** Every case act (T-case, its own
   transaction) takes the case row `FOR UPDATE` and carries the `revision` the investigator's screen read; the
   `UPDATE` is conditional on it and increments it. The loser answers `409 risk.CaseRevisionStale` with nothing
   written and re-reads; the winner's action is kept, so two investigators updating one case keep both actions and
   lose no update (`CaseDatabaseTest#concurrentUpdatesKeepEveryAction`, scenario 5). The lock alone would serialise;
   the revision is what stops a stale screen overwriting a newer act.

6. **Dispositions are a closed vocabulary, and the ones that matter are two persons' acts.** `Disposition` ∈
   {`CLOSED_FALSE_POSITIVE`, `CLOSED_NO_ACTION`, `CONFIRMED_FRAUD`, `CONFIRMED_SUSPICIOUS`, `REFERRED`}. A
   `RISK_INVESTIGATE` holder proposes; every `CONFIRMED_*`, and any disposition that issues an action, is approved by
   a second holder with `RISK_CASE_APPROVE` and a `MULTI_FACTOR` session (`POST …/dispositions`, `…/approval`,
   `…/rejection`). The proposer never approves: refused `403 risk.SelfApprovalRefused`, held by a four-eyes `CHECK`,
   recorded `FAILED` (scenario 15; `INV-RSK-07`). A `CONFIRMED_*` disposition may set the customer risk profile's
   rating — `PROHIBITED` only this way — as a new profile version, never an edit (`INV-RSK-10`). `REFERRED` is the
   case recommending an act it cannot make: a reversal is the owning domain's four-eyes act (ADR-0112).

7. **Self-dealing fails closed** (the gate's A7-6 applied forward). No holder reads or works a case, approves a
   disposition or an action, or files a report whose subject is their own party; an operator with no placeable party
   is refused too; each refusal `403 risk.SelfDealingRefused`, recorded `FAILED` in a transaction of its own.

8. **Queues and reads are operators' only** (plan §9, §11; `INV-RSK-08`). `GET /v1/operator/risk/alerts` and
   `…/cases` are oldest-first, keyset-paged, filtered by status and kind, never the operator's own party's, every
   page audited; a case's read is audited. No customer or merchant route reads any `risk` table
   (`NoCustomerSurfaceReadsRiskTest`); a customer's answer is the operation's generic outcome (C6); logs carry ids
   and counts; events carry ids, codes and statuses (`CaseOpened`, `CaseEscalated`, `CaseDisposed`, `AlertRaised`);
   metric tags are bounded vocabularies (`case{kind,status}`, `alert{kind,severity}`).

9. **Collections: the case only** (C3, `P13-TSK-025`, cut candidate). A `COLLECTIONS` case opens on `LoanDefaulted`
   / `BnplDefaulted` through the inbox (born once per event), with investigator actions as facts; contact strategy,
   promises to pay, placement and write-off stay out — Phase 14 and a later collections context. The model gains
   nothing for it beyond the kind.

10. **What a case never does.** Decide money; write another module's row; edit or delete an alert, an action or
    an assessment; re-open; carry a customer-readable status; copy evidence.

11. **Would this remain correct with ten instances?** Two instances raising one alert write one row
    (`AlertDatabaseTest#twoInstancesRaiseOnce`, scenario 2); the same event three times, delayed and reordered, gives
    one signal, one alert, one case (`SignalIntakeDatabaseTest#theSameEventTwiceRecordsOnce`, scenario 1: inbox dedupe
    per consumer + `UNIQUE (source_event_id)` + born-once alerts); ten alerts open one case
    (`#tenAlertsOneOpenCase`); two investigators keep both actions with one `409` (`#concurrentUpdatesKeepEveryAction`);
    a delayed event after disposition records a signal and opens a new case referencing the old
    (`#aLateEventAfterDispositionOpensANewCase`, scenario 6); the profile recomputed by two sweepers writes one version
    under the head row lock (`ProfileDatabaseTest#tenRecomputesOneVersion`). The SLA is judged on the database clock
    and surfaced as a gauge (`case.age{kind}`), never by an instance's timer. T-case takes no money row, so it can
    wait on nothing a money transaction holds.

## Alternatives Considered

### One case per alert
Pros: nothing to fold; a trivially simple queue.
Cons: an alert flood is a case flood; a subject's structuring sequence becomes a dozen investigations of one fact,
each disposed separately. Refused: the unit of judgement is the subject and the kind.

### Re-open a disposed case
Pros: one thread per subject for ever.
Cons: a disposition is a person's recorded conclusion; re-opening it rewrites what was concluded, and a report filed
from it would reference a case whose state changed. Refused (OQ-6): a new case references the old.

### Optimistic concurrency without the row lock, or the row lock without `revision`
Cons: without the lock, two conditional updates interleave their reads of the action list; without the revision, a
stale screen's act is applied over a newer one it never saw — a lost update that looks like a success. Refused: both,
and the loser answers `409` with nothing written.

### A case model per capability — fraud, AML and collections each their own
Cons: three queues, three four-eyes shapes, three tipping-off controls, and a subject under both fraud and AML
investigated by two models that cannot see each other. Refused by M6: one model, the kind a field.

### A full collections workflow in Phase 13
Refused (C3): contact strategy, promises to pay and placement need their own context and consent; the case is the
door, the operations are later. Cut first if the phase needs cutting (plan §16).

### Single-person `CONFIRMED_*` dispositions
Cons: one person confirms fraud, sets a `PROHIBITED` rating and triggers a suspension of a customer who cannot see
why. Refused: every `CONFIRMED_*` and every action-issuing disposition is two persons' act (`INV-RSK-07`).

### A customer-facing "risk status" or case notice
Refused by `INV-RSK-08` and plan §20: tipping-off; the customer sees the operation's generic outcome only.

## Consequences

Positive:
- A subject under investigation is one open case per kind, however many alerts, instances or replays produced it.
- Every act on a case is attributable, reasoned and immutable; a disposition that affects a person is two persons'.
- Fraud, ATO, merchant abuse, AML and a default's collections door share one queue, one four-eyes shape and one
  tipping-off control.

Negative:
- A stale screen costs the investigator a `409` and a re-read — the price of never losing an act.
- A disposed case cannot be amended; a correction is a new case that references it, and a filed report stands.
- The collections slice gives an operator a case and no tooling to work it; that is the deferral, recorded.

Operational impact: `finapp.risk.case{kind,status}`, `case.age{kind}` and `case.backlog{kind}` (gauges),
`case.resolution{kind}` (timer), `alert{kind,severity}`, `alert.suppressed{reason_code}`; alerts on a case older than
its SLA and on the backlog; the case and alert reports, audited.
Security impact: `RISK_INVESTIGATE` and `RISK_CASE_APPROVE` split; four eyes with step-up on every confirming or
acting disposition; self-dealing fails closed; evidence by reference through audited reads; no customer surface.
Financial impact: none — a case moves no money; its only effects are keyed actions through owning domains
(ADR-0112) and a `REFERRED` recommendation.

## Invariants / Constraints

`INV-RSK-06` (born-once alerts; one open case per subject and kind; replay creates nothing), `INV-RSK-07`
(dispositions that confirm or act are two persons' acts; self-approval recorded `FAILED`), `INV-RSK-08`
(tipping-off), `INV-RSK-10` (the profile append-only; `PROHIBITED` only by a four-eyes disposition), `INV-AUD-03`,
`INV-AUD-04`, `INV-EVT-04`, `INV-IDEM-04`, ADR-0107, ADR-0108, ADR-0112.

## Follow-up

- `P13-TSK-015` — alerts born once (`risk V008`); `P13-TSK-016` — the case machine,
  investigator actions, `revision`, escalation, four-eyes dispositions (`risk V009`); `P13-TSK-018` — the
  queues, reads and the tipping-off control; `P13-TSK-025` — the collections case (C3).
- `P13-TSK-013` and `-014` — the account-takeover and abuse rules that raise fraud alerts into this model;
  `P13-TSK-020` and `-021` — the AML alerts and case.
- `P13-TST-001` runs scenarios 1, 2, 5, 6, 12 and 15 with counts; `RISK_LIFECYCLES.md` §Alert and §Case are the
  machines' record; `AUDITABLE_ACTIONS.md` and `ERROR_CONTRACT.md` gain the planned entries.
- The SLA per kind is a policy figure the owner sets at activation; until then the default is the task's and is
  recorded here when built.
- **Built by `P13-TSK-015` (2026-10-25) - decision 1, and decision 3's contract.** `risk V010` (the next free
  number; planned `V008`): `risk_alert` born once by `UNIQUE (subject_ref, rule_code, window_start)` through one
  `INSERT … ON CONFLICT ON CONSTRAINT risk_alert_born_once DO NOTHING`; the window a bucket of its kind aligned by
  `CHECK` from a database instant that is the evidence's own; evidence by reference (signal, assessment, journal entry
  ids, at most 100 each); never deleted. **Decision 1 corrected (dated note):** "`SUPPRESSED` — with a reason:
  deduplicated into an open case's window, or an operator's reasoned suppression" no longer stands - the plan's
  reconciliation is built: **the system's dedupe is `ATTACHED`** (decision 3's folding), and `SUPPRESSED` is only the
  operator's four-eyes act (`Alerts.proposeSuppression` / `approveSuppression` / `rejectSuppression`; two `EMPLOYEE`s,
  the approver never the proposer, by domain and by `CHECK`; audited `risk.AlertSuppressionProposed`,
  `risk.AlertSuppressed`, `risk.AlertSuppressionRejected`). **Decision 3** is declared as the port `AlertFolding` for
  `P13-TSK-016` (`NONE` wired: every alert stays `OPEN`, queued); the order is a birth at (R3)'s position, then the
  case (R4), then the alert's attach (R5), and a transaction raises through one `raiseAll` - its alerts in key order,
  every birth before any fold. The door and the queue stay `P13-TSK-018`'s.
- **Acceptance.** `Proposed` at the Phase 12 → 13 transition (2026-10-22); the Phase 13 review (`P13-DOC-001`) reads
  this ADR against the code, corrects it in place with dated notes, and accepts it.
