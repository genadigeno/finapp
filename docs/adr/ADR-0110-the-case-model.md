# ADR-0110 — The case model shared by fraud, ATO, merchant abuse, AML and collections: born-once alerts, one open case per subject and kind, concurrent updates by revision, four-eyes dispositions

Status: Accepted (2026-10-27, `P13-DOC-001` — read against the code and corrected first)
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
   *(P13-DOC-001, 2026-10-27, the openers as built: T-eval opens (or folds into) the party's case for `REVIEW` and
   `HOLD` alone - `ATO` when the sealed hits show the account-takeover anchors, else `FRAUD` - and **a policy-flagged
   `DENY` opens none**: no version carries such a flag (`CaseOpener`) - for the owner's ratification
   (`PHASE_13_PLAN.md` §1.2 item 94). Every other opening is an alert's fold (decision 3): T-eval's account-takeover
   and merchant-abuse alerts, the profile leg's fraud alerts, the monitoring rules' `AML` alerts, a rescreening's
   `SCREENING_HIT` fact alert (into the party's `AML` case) and T-sig's `OBLIGATION_DEFAULTED` fact alert (into its
   party's `COLLECTIONS` case); by hand under `RISK_INVESTIGATE`.)*

3. **Alerts fold into the subject's open case.** Raising an alert and attaching it are one transaction where the
   sweep or T-eval holds nothing else of `risk`: the case row `FOR UPDATE` (R4), then the alert rows (R5). An alert
   for a subject with an open case of the matching kind attaches to it; without one, it opens one — the partial
   unique arbitrates when ten alerts race (`CaseDatabaseTest#tenAlertsOneOpenCase`, scenario 12: a structuring
   sequence is alerted once per window and the case folds every alert).
   *(P13-DOC-001, 2026-10-27: "where the sweep or T-eval holds nothing else of `risk`" is not so as built: a raising
   transaction holds the risk rows it took before - T-eval its pin, velocity locks and sealed assessment (R1)–(R3),
   inside the caller's money transaction (ADR-0108 decision 6's note); the profile leg the party's profile head (R8 →
   R4 → R5); a rescreening's conclusion its permit (R9 → R3 → R8 → R5 → R4 → R5). The order that keeps it cycle-free is
   the follow-up's: every birth (at (R3)'s position, in key order) before any fold, then the case (R4), then the attach
   (R5); no transaction holding (R4) waits on a money row.)*

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
   *(P13-DOC-001, 2026-10-27, the four-eyes set as built (`DispositionRules`; `risk V012`'s
   `risk_case_disposition_four_eyes`): every `CONFIRMED_*`, every `REFERRED` and every disposition that sets
   `PROHIBITED` - two persons, the approval stepped up, proposed only on a taken case. "Any disposition that issues an
   action" is moot: **no disposition issues an action** (ADR-0112's dated amendment (a)) - a case's risk action is
   proposed on the case and approved on its own row under four eyes, a disposition waiting while one stands `PROPOSED`.
   `CLOSED_FALSE_POSITIVE` and `CLOSED_NO_ACTION` that prohibit nothing are one person's act, decided by its own
   proposal, from `OPEN` too - so one `RISK_INVESTIGATE` holder may open a case by hand on an open alert and close it
   alone, reaching what decision 1's four-eyes suppression controls, `AML` included (`PHASE_13_PLAN.md` §1.2 item 78).
   A `PROHIBITED` rating refuses a movement only while the active version carries a `PROFILE_RATING → DENY` rule (item
   83); no door lifts it (item 21); `CaseProhibitionDriver`, which writes it after the case commits, has no per-item
   failure handling (item 84).)*

7. **Self-dealing fails closed** (the gate's A7-6 applied forward). No holder reads or works a case, approves a
   disposition or an action, or files a report whose subject is their own party; an operator with no placeable party
   is refused too; each refusal `403 risk.SelfDealingRefused`, recorded `FAILED` in a transaction of its own.
   *(P13-DOC-001, 2026-10-27: the case file's evidence door (`P13-TSK-023`) judges the **case's** subject, not each
   served reference - a reference of the reader's own party attached to another party's case is served - for the
   owner (`PHASE_13_PLAN.md` §1.2 item 79); a merchant's case is judged against its organisation party (item 22).)*

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
    *(P13-DOC-001, 2026-10-27: the named tests exist and ran green in the owner-authorised database pass (2026-10-27,
    after test-only repairs); the storm's counted run of the scenarios is postponed (`POSTPONED_ISSUES.md` PI-004), and
    `AlertBornOnceCensus` and `CaseOneOpenCensus` watch the two arbiters in operation. The converse holds less: a money
    transaction's T-eval does take the case row (R4) - decision 2's note, ADR-0108 decision 6's note, item 95.)*

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
  machines' record; `AUDITABLE_ACTIONS.md` and `ERROR_CONTRACT.md` gain the planned entries. *(P13-DOC-001,
  2026-10-27: `P13-TST-001` is postponed by the owner until after the project's phases (PI-004) - decision 11's
  note.)*
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
- **Built by `P13-TSK-016` (2026-10-25) - decisions 2-7.** `risk V012` (the next free number; planned `V009`):
  `risk_case` one open per (subject, kind) by the partial unique `risk_case_one_open`, its machine judged by the domain
  and the trigger, never re-opened (a new case names its predecessor); `risk_case_action` append-only; `risk_case_event`
  one history row per revision. **Decision 3** implemented as `Cases::fold` (the case R4, then the alert's attach R5,
  through the alert store) with the backlog born before it drained by the case leg on `RiskMonitoringSchedule`; `-004`'s
  `CaseOpener` as `Cases::openFor` (`ATO` when the sealed hits show the account-takeover anchors, else `FRAUD`, so an
  anchor rule's hold and its alert are one case). **Decision 5** as written: the row lock and the revision, compared in
  the domain, the `UPDATE` conditional on it and the trigger refusing any step but one. **Decision 6, corrected (dated
  note):** "a `CONFIRMED_*` disposition may set the customer risk profile's rating" is built as an explicit `prohibit` on
  the proposal - admitted for a confirming disposition of a party's case alone - and written by the profile in a
  transaction of its own after the case commits (never R8 under R4, `P13-TSK-013`'s lock-order finding), one version per
  disposition; "any disposition that issues an action" joins the four-eyes set with `-017`; the approval is stepped up
  for every four-eyes disposition (`REFERRED` and a prohibiting one too). **Decision 7** as written, with the merchant's
  organisation party placed for a `MERCHANT_ABUSE` case (`RiskSubjectParties.accountablePartyOf`). **The SLA per kind**
  (the follow-up's "default is the task's"): `ATO` 4 h, `FRAUD` 24 h, `MERCHANT_ABUSE` 72 h, `AML` 720 h, `COLLECTIONS`
  168 h (`SlaDeadlines`), stamped by the trigger on the database clock - owner item to set per kind. The queues and reads
  (decision 8) stay `P13-TSK-018`'s - built, below.
- **Built by `P13-TSK-018` (2026-10-25) - decision 8, and decision 1's persons corrected.** `risk V014` (the next free
  number): **decision 1, corrected (dated note):** the suppression's "two `EMPLOYEE`s" above read so until this task -
  the platform's session establishes every operator `CUSTOMER`-typed, so the door would have refused every analyst;
  `risk_alert_suppression_by_persons` is re-made by name over `CUSTOMER` and `EMPLOYEE` (an authenticated human, the
  meaning `-016` and `-017` adopted; never `SYSTEM`, `SERVICE`, `MERCHANT`), the domain's check `Cases.person`, and the
  door built - `POST /v1/operator/risk/alerts/{alertId}/suppression` (`RISK_INVESTIGATE`), `…/approval`
  (`RISK_CASE_APPROVE`, `MULTI_FACTOR`), `…/rejection`, keyed `risk.alert:<type>:<id>`; a merchant's alert placed through
  the accountable join. **Decision 8** as written, with three readings made precise: "never the operator's own party's"
  is **withheld** from a page (never refused - a page is what the reader may see; an unplaceable subject withheld too,
  fail closed; the reader unplaced refused), the cursor always the last row served, the scan bounded at 1,000 rows a
  page; "every page audited" is `risk.AlertRead` / `risk.CaseRead` committed before the answer, the page one read-only
  snapshot; "a case's read" is the case file - the case, its alerts, investigator actions, history and risk actions, its
  evidence by reference only - `403 risk.SelfDealingRefused` for the reader's own party's. The keyset is the immutable
  `(raised_at | opened_at, id)`; the indexes `risk_alert_queue`, `risk_alert_of_case`, `risk_case_queue`,
  `risk_case_open_queue`. The tipping-off control made whole by four guards (`NoCustomerSurfaceReadsRiskTest`'s whole
  reach and risk-table rule, `RiskEventsCarryOnlyCodesTest`, `RiskLogSitesAreEnumeratedTest`,
  `RiskMetricTagsAreBoundedTest`) and the owed `CustomerAnswerIsIndifferentToACaseTest`. The gauges
  `finapp.risk.case.backlog{kind}` and `.case.age{kind}` read the queue. A signal's and a monitored transaction's read
  door - the resolution of those evidence references - is in no route table yet (owner item).
- **Built by `P13-TSK-025` (2026-10-26) - decision 9, corrected (dated note).** "The model gains nothing for it beyond
  the kind" no longer stands: a default is neither a policy rule's finding nor an operational signal, and `V010`'s CHECKs
  coupled "own code, no policy" with "opens no case". **The alert gains a third origin, the fact**: `AlertKind.
  OBLIGATION_DEFAULTED` (folding into `COLLECTIONS`) - its own code its rule, no policy (it alerts with no `MONITORING`
  version active), about the `CREDIT_AGREEMENT`, its evidence exactly the one default signal, keyed on the `D1` bucket of
  the default's business date - raised in T-sig by `CollectionsCases` on every delivery that reaches the handler (born or
  standing), under the platform's actor (`RiskSignalIntake.asThePlatform`, enumerated). `risk V017` (persistence was
  planned "none") re-makes the four CHECKs by name and adds the fact's shape and the kind's three CHECKs. **The case's
  subject is the agreement's party** (`Cases`' kind rule over the accountable join - `app`'s `JdbcCreditAgreementParties`
  over lending's and bnpl's published views, `SYSTEM_JOIN`), so one open per (party, `COLLECTIONS`) holds and a second
  default attaches. **Facts only**: disposed `CLOSED_NO_ACTION` or `REFERRED` (`DispositionRules.COLLECTIONS`), never
  `CLOSED_FALSE_POSITIVE`, `CONFIRMED_*` or `PROHIBITED`; no `ActionKind` (as `-017` built); never by hand. The SLA stays
  168 h - owner item. Contact strategy, promises to pay, placement, agencies, write-off and provisioning stay out (Phase 14
  and a later collections context); lending and bnpl unchanged.
- *(P13-DOC-001, 2026-10-27 - the fact origin, as built: `OBLIGATION_DEFAULTED` is not the only fact kind.
  `AlertKind.SCREENING_HIT` (`P13-TSK-022`'s review, `risk V018`) is a second - raised from the rescreening permit's
  born-once `SCREENING_HIT` signal, its own code its rule, no policy naming it, about the `PARTY` on the `D1` window of
  the list version's activation, one alert per (party, list version) by its `fact_signal_id`, folded into the party's
  `AML` case. And the `-016` bullet's "'any disposition that issues an action' joins the four-eyes set with `-017`" did
  not happen: `-017` built no disposition that issues an action (decision 6's note).)*
- **Acceptance.** **As built** (`P13-DOC-001`, 2026-10-27): `Proposed` at the Phase 12 → 13 transition (2026-10-22);
  the Phase 13 review read this ADR against the code, corrected it in place above with dated notes, and accepted it.
  The as-built choices awaiting the owner carry their `PHASE_13_PLAN.md` §1.2 item numbers (the SLA per kind, item
  23; the collections fact, items 60-65).
