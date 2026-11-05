# ADR-0118 — Regulatory reporting behind jurisdiction adapters: obligations as configuration, submissions from FINAL runs, a simulated reference adapter

Status: Proposed
Date: 2026-10-29
Phase: 14
Context: Reporting · Accounting / General Ledger · Lending (the `lending_funded` term)
Supersedes: nothing. Applies Phase 13's C4 / ADR-0111 precedent (a simulated filing port, the jurisdiction a field) and
ADR-0096 §6 (the safeguarding proof subtracting `lending_funded`). Rests on `PHASE_14_PLAN.md` §13.7 and `INV-ACC-13`.

## Context

The platform has no real jurisdiction (`DECISIONS.md` §Deferred: the neutral EUR reference). A regulatory return is
nonetheless a distinct artefact: drawn from issued figures, approved, submitted, acknowledged or rejected, corrected by
resubmission. Inventing a legal obligation would be worse than having none.

## Decision

1. **A regulatory obligation is configuration data**: jurisdiction code, form code and version, period type, source
   report definitions, the line mapping (form line ← report figure), validation rules, the adapter name.
2. **A submission** is drafted only from FINAL report runs of `CLOSED` periods, recording each run id and hash (lineage);
   `DRAFT → VALIDATED → APPROVED → SUBMITTED → ACKNOWLEDGED | REJECTED`; approval four-eyes; `SUBMITTED` committed before
   the provider call, keyed by the submission id at the provider; an unknown answer an inquiry, never a re-submit; a
   correction is a new submission referencing the original (`INV-ACC-13`).
3. **`RegulatoryReportingProvider`** is a port; `app` implements a **simulated reference adapter** (owner decision C4).
   No real channel, no statutory form, no compliance claim.
4. **The first obligation** is a reference **safeguarding position return** (C13): customer and merchant liabilities vs
   cash and clearing receivables, with `lending_funded` (ADR-0096 §6) subtracted.

## As built

`P14-TSK-018`, 2026-11-04, accounting `V010`. Status stays `Proposed` until the owner ratifies the phase's ADRs.

**`SubmissionStatus` is widened.** Decision 2's line has no door out of a state a person refuses to attest, a preparer takes back, or a provider says it never received, and a state no door leaves is a return nobody can end. Appended: `REFUSED` (a controller's: nothing sent; distinct from `REJECTED`, the provider's answer to what was sent, in name, event, audit and reader's text), `WITHDRAWN` (a preparer's: nothing sent), `UNDELIVERED` (a person's conclusion, only on the provider's definite "not received" asked at that act, with the resends spent, the horizon past or the month moved). The machine: `DRAFT → VALIDATED | WITHDRAWN`; `VALIDATED → APPROVED | REFUSED | WITHDRAWN`; `APPROVED → SUBMITTED | REFUSED | WITHDRAWN`; `SUBMITTED → ACKNOWLEDGED | REJECTED | UNDELIVERED`. A failed validation stays `DRAFT`, recorded once; its way on is a withdrawal and a new draft. A correction references only `ACKNOWLEDGED`, `REJECTED` or `UNDELIVERED`; after `REFUSED` or `WITHDRAWN` a plain new draft is free.

**Status never identifies the books.** `CLOSED` round-trips, so every act going forward compares the period's revision and each run's id and hash, its bytes re-proved, under the period row: in the domain, and again in `regulatory_submission_judge_stands` for a raw writer. A reopen's approval holds that row `FOR UPDATE`, so it and a submit serialise and each sees what the other committed. There is no third order.

**A return sent and then overtaken stands.** It is frozen, and the month stood when its send began. It is flagged on every read and counted until a return of its form is acknowledged at the month's present revision: a draft or a rejected correction clears nothing, since the regulator still holds the figure. It is not edited, withdrawn or re-sent; a correction is a new submission naming it, one per original.

**A month that moved before the send: never sent.** `SUBMITTED` commits before any call, so a row can stand `SUBMITTED` with nothing delivered for as long as the provider is down or a worker is dead. The first attempt reads the month's revision in its claim's own transaction, unlocked, after the claim's `UPDATE`, and if it moved it only inquires. A resend's permit takes the period `FOR SHARE` and refuses, `MONTH_MOVED`, in the domain, in `PERMIT_RESEND_SQL` and in the trigger. A definite "not received" then opens the person's door whatever the resend count, so the return reaches `UNDELIVERED` and a correction goes at the new revision. `regulatory_submission_judge_stands` is not on that edge: it is how a return its month overtook is ended.

**The residual window.** A send that began just before a reopen committed cannot be closed without a transaction around the call, and no call is ever made inside one. From the read at the claim or the permit until the provider has the bytes, a reopen can commit unseen, and the regulator can then hold figures the books stopped saying during that send. **So the rule is that the month stood when the send began, not when it arrived.** The return reads `overtaken`, as any other.

**A person concludes on what the provider says now.** `last_answer` can be an interval old, so the non-delivery door asks the provider once at the act, on no transaction, keeps what it says whatever then happens, and concludes only on a definite "not received" said then. If the call fails or the answer cannot be kept, nothing is concluded: `503 accounting.RegulatoryProviderUnavailable`, the row unmoved, the key unspent.

**"An unknown answer an inquiry, never a re-submit" is held, and one more case added:** a resend, only on the provider's *definite* "not received", under the same id, at most five, each counted and committed before its call with `last_answer = RESENDING`, so no person can conclude non-delivery over a send in flight. The provider deduplicates on the id, so this is the first send, not a second return. `UNKNOWN` forever has no person's door: nobody here may assert what a regulator holds; the row reads overdue, the gauge counts it, and the inquiry goes on (owner item).

**An answer disagreeing with a concluded return is kept, flagged, audited and metered, and is never an edge.** A provider's answer is untrusted bytes: at most 64 KiB kept, and an answer over the bound, of a malformed or instrument-shaped reference or code, or contradicting its own call decides nothing. Every answer stays kept verbatim (`INV-HIST-02`), but a read serves the newest twenty and the count, and past the horizon an answer that decides nothing backs the inquiry off to at most one a day, so an unending `UNKNOWN` is no unbounded load. A provider that throws keeps no answer and is asked again each lease.

**Decision 1 as built:** an obligation enters by four-eyes doors, not a migration: proposed under `REGULATORY_REPORT_PREPARE`, activated by a second person under `REGULATORY_REPORT_APPROVE` from a `MULTI_FACTOR` session; a closed rule vocabulary and a closed figure path grammar; `standing` `REFERENCE` by `CHECK`; nothing seeded. **Decision 4 is `P14-TSK-019`'s.**

**Beyond the Consequences' sentence on roles:** one identity can hold two roles until Phase 15's separation at assignment, so the preparer never approves and the approver never sends, each held at the act and by `CHECK`: the first on the row and its history, the second on the row alone, a `SUBMITTED` event naming no approver. A preparer may send. The approver reads what they attest under `REGULATORY_REPORT_APPROVE`, its figures under their own permission; `ACCOUNTING_READ` is granted to no one new, and a view says what a role cannot open.

**What a real adapter changes:** its own bean in `RegulatoryReportingBeans`; a call timeout below the two-minute lease; a format verified against the regulator's specification; a jurisdiction; a migration widening `standing`; a review of `regulatory_provider_answer.raw`'s classification; and an agreed reference format, because the instrument screen discards a definite acknowledgement whose reference merely holds an instrument's shape (`TX2026110500001` always does, and a 16-digit reference often holds a Luhn-valid window), leaving the row `SUBMITTED` with no door. The machine, the driver and the doors do not change.

## Alternatives Considered

### Model a named real regime now
Pros: realism. Cons: fabricated obligations; unverifiable fields. Rejected until the owner chooses a jurisdiction.

### Regulatory reports as plain report runs
Pros: less machinery. Cons: no approval, submission or correction lifecycle. Rejected.

## Consequences

Positive: lineage from a submitted line to journal lines. Negative: a reference format only. Operational impact: the
inquiry leg of `AccountingCloseSchedule`. Security impact: `REGULATORY_REPORTER` prepares and submits,
`FINANCIAL_CONTROLLER` approves. Financial impact: none.

## Invariants / Constraints

`INV-ACC-04`, `-13`; `INV-AUD-01`, `-04`; `INV-HIST-02` (the provider's answers retained verbatim).

*(As built, `P14-TSK-020`, 2026-11-05: the alerts `-018` left. On an outcome's `increase`: `rejected`, `contrary`, `run_bytes_differ`, `send_withheld_month_moved`, `not_resent_month_moved`, `answer_anomaly`. On a level a person's door clears: `resends_exhausted`, `stale_unsent`. On the growth of a level that can hold for good: `overtaken`, the owner's ruling, `PI-010`, and `overdue`, this task's reading of the same reason, since a return `UNKNOWN` for good stays overdue for good with no door (`PI-008`). `RegulatoryAttentionMetrics` reads through `AccountingGaugeRead` and has a statement timeout. Nothing is claimed of a real channel: every alert here has only ever been evaluated by `RuleEvaluator`.)*


## Follow-up

A real jurisdiction's adapter and forms, verified against the regulator's specification when chosen.
