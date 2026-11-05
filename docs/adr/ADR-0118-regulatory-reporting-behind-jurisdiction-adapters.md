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

**Decision 1 as built:** an obligation enters by four-eyes doors, not a migration: proposed under `REGULATORY_REPORT_PREPARE`, activated by a second person under `REGULATORY_REPORT_APPROVE` from a `MULTI_FACTOR` session; a closed rule vocabulary and a closed figure path grammar; `standing` `REFERENCE` by `CHECK`; nothing seeded. **Decision 4 is `P14-TSK-019`'s:** below.

**Beyond the Consequences' sentence on roles:** one identity can hold two roles until Phase 15's separation at assignment, so the preparer never approves and the approver never sends, each held at the act and by `CHECK`: the first on the row and its history, the second on the row alone, a `SUBMITTED` event naming no approver. A preparer may send. The approver reads what they attest under `REGULATORY_REPORT_APPROVE`, its figures under their own permission; `ACCOUNTING_READ` is granted to no one new, and a view says what a role cannot open.

**What a real adapter changes:** its own bean in `RegulatoryReportingBeans`; a call timeout below the two-minute lease; a format verified against the regulator's specification; a jurisdiction; a migration widening `standing`; a review of `regulatory_provider_answer.raw`'s classification; and an agreed reference format, because the instrument screen discards a definite acknowledgement whose reference merely holds an instrument's shape (`TX2026110500001` always does, and a 16-digit reference often holds a Luhn-valid window), leaving the row `SUBMITTED` with no door. The machine, the driver and the doors do not change.

### Decision 4 as built

`P14-TSK-019`, 2026-11-05, no migration. `SAFEGUARDING_POSITION` version 1, `SafeguardingPositionDefinition`.

**`LendingCapitalProof` could not be read.** The entry said to, through a port. The proof's `committed` is the loans `PENDING_DISBURSEMENT` now and its `outstanding` a present balance, so a FINAL reading it would move after its month closed, against `INV-ACC-04`, and `ReportingNeverWritesTest#noReportPortAdapterWrites` refuses a report port's adapter any edge to `com.finapp.lending` for that reason. The guard stands. The formula is stated a second time, `LendingFundedAsOfCutoff.lendingFunded`, in `accounting`, as a call is forbidden, and `SafeguardingFormulaIsTheHeadroomsTest`, in `app`, pins it and both purpose sets to the headroom's.

**Each term as of the cutoff, in one `REPEATABLE READ READ ONLY` snapshot, each statement naming one module's schema.** *Outstanding:* the trial balance's own statements, by posting date. *Written off:* CR-DR on the four principal purposes of the entries holding a line on a write-off expense, by posting date. *Committed:* no journal line exists, because an accepted loan posts nothing, and a status moves, so none is read. Immutable history can say it: a loan's `opened_at`, its agreement version 1's principal, and its disbursement's `value_date`, `decided_at` and `failure_reason`, NULL until decided and frozen once decided. A loan stood committed when its own servicing zone's business date of its opening is on or before the cutoff, and neither its `value_date` nor its cancellation's date is.

**Why the `value_date`, and not the `ACTIVE` edge's instant.** `LoanDisbursements` posts `posting_date = value_date`, as stated and never floored, so a loan crosses from committed to outstanding on one date: never counted twice and never in neither. The edge's instant, read in UTC, double-counts a loan in a zone west of UTC disbursed after midnight UTC and drops one in a zone east of it; read in the zone, it double-counts a transaction straddling midnight between its clock read and its event. No two instants are ordered here, so a clock stepped back (ADR-0063) changes nothing either.

**So:** regenerating an earlier month is byte-identical whatever came after, because a later disbursement has a later `value_date`, a later cancellation a later date, a later acceptance a later `opened_at`, a later write-off a later posting date. A reopen's new revision holds every line now dated in the month, and committed does not move, because no fact behind it did.

**The mapping is by ledger purpose, under the definition's version**, never a GL mapping's sections, so a new mapping version moves no figure; a pinned version decides only a row's `glAccount`, the drill-down key. Total over the ledger's forty, and a purpose added later fails `SafeguardingPurposeCensusTest` until disposed of. In: `CASH_AT_BANK`; clearing receivables `SETTLEMENT_CLEARING`, `INSTANT_CLEARING`; customer wallets; merchant payables, a BNPL financing's among them; both credit balances; clearing payables `PAYOUT_CLEARING`, because a payable was debited and the cash has not left. Each counterparty on `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` on its own side, its debit balance a receivable and its credit balance a payable, never netted (`INV-RAIL-04`). `SUSPENSE_UNMATCHED` at its net credit only: money received and not attributed may be a customer's, so out it would read as surplus, and a net debit is unexplained money gone, never a negative liability hiding a shortfall. Out, served beside the position: `CHARGEBACK_RECOVERABLE`, `FX_POSITION`. Out: interest and fee receivables, capital, every revenue and expense. `body.mapping` states each with its reason.

**A shortfall is a figure.** The form's six rules are `EQUALS` footings, arithmetic that must hold in any month: a rule on the position would make the return impossible to validate in exactly the month it matters, and the way on would be to change the configuration. It reads negative, unfloored; a person sees it on the form's `POSITION` line at validation and attestation. No meter or alert: an owner item.

**A zero is a read that succeeded.** Each currency element holds every term, so a currency with wallets and no lending validates on true zeros, and a read that fails throws, so no FINAL exists and the draft is refused: the two cannot look alike. A currency nothing touched has no element, because no scale exists without an amount and no JDK table decides one.

**What this figure is not.** Built as ADR-0096 section 6 ruled, and two things the owner should read: `committed` lifts the position by the principal with no cash and no liability behind it until the disbursement, and can read a real shortfall as a surplus; and `outstanding` is an upper bound on loan-funded money still in wallets, so the position does not fall when a borrower withdraws, and is, by double entry, capital plus results plus committed, less the assets the return leaves out. `positionBeforeLendingFunded` is served beside it for that reason. `SafeguardingPositionDefinitionTest` pins all of it. No real safeguarding regime's rules are claimed; a real one would define its own form.

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

## Follow-up

A real jurisdiction's adapter and forms, verified against the regulator's specification when chosen.
