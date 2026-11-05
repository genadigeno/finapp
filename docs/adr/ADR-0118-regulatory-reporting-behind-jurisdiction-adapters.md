# ADR-0118 — Regulatory reporting behind jurisdiction adapters: obligations as configuration, submissions from FINAL runs, a simulated reference adapter

Status: Proposed
Date: 2026-10-29
Phase: 14
Context: Reporting · Accounting / General Ledger · Lending (the principal terms)
Supersedes: nothing. Applies Phase 13's C4 / ADR-0111 precedent (a simulated filing port, the jurisdiction a field) and
ADR-0096 §6 as the owner corrected it on 2026-11-05 (the safeguarding proof subtracting principal outstanding and written off). Rests on `PHASE_14_PLAN.md` §13.7 and `INV-ACC-13`.

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
   cash and clearing receivables, with `lending_funded` (ADR-0096 §6) subtracted. *(Corrected by the owner, 2026-11-05: with the principal outstanding and written off subtracted, `committed` a line beside it, and the figure with nothing subtracted the one that warns. As built below.)*

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

`P14-TSK-019`, 2026-11-05, no migration, and `P14-TSK-023` the same day, moving the subtraction in place at definition and form version 1 by the owner's ruling, because no FINAL of this definition had been issued and no obligation activated. `SAFEGUARDING_POSITION` version 1, `SafeguardingPositionDefinition`.

**`LendingCapitalProof` could not be read.** The entry said to, through a port. The proof's `committed` is the loans `PENDING_DISBURSEMENT` now and its `outstanding` a present balance, so a FINAL reading it would move after its month closed, against `INV-ACC-04`, and `ReportingNeverWritesTest#noReportPortAdapterWrites` refuses a report port's adapter any edge to `com.finapp.lending` for that reason. The guard stands. The return states its own term, `LendingFundedAsOfCutoff.principalUnrepaid`, in `accounting`, as a call is forbidden, and `SafeguardingFormulaIsTheHeadroomsTest`, in `app`, pins it to the headroom's from both sides, the term + committed = `Headroom.lendingFunded()`, through the real definition too, with both purpose sets. The port keeps its name: it reads as of a cutoff the terms of capital's figure.

**Each term as of the cutoff, in one `REPEATABLE READ READ ONLY` snapshot, each statement naming one module's schema.** *Outstanding:* the trial balance's own statements, by posting date. *Written off:* CR-DR on the four principal purposes of the entries holding a line on a write-off expense, by posting date. *Committed:* no journal line exists, because an accepted loan posts nothing, and a status moves, so none is read. Immutable history can say it: a loan's `opened_at`, its agreement version 1's principal, and its disbursement's `value_date`, `decided_at` and `failure_reason`, NULL until decided and frozen once decided. A loan stood committed when its own servicing zone's business date of its opening is on or before the cutoff, and neither its `value_date` nor its cancellation's date is.

**Why the `value_date`, and not the `ACTIVE` edge's instant.** `LoanDisbursements` posts `posting_date = value_date`, as stated and never floored, so in a FINAL run a loan crosses from committed to outstanding on one date: never counted twice and never in neither. The edge's instant, read in UTC, double-counts a loan in a zone west of UTC disbursed after midnight UTC and drops one in a zone east of it; read in the zone, it double-counts a transaction straddling midnight between its clock read and its event. No two instants are ordered here, so a clock stepped back (ADR-0063) changes nothing either. **That is a FINAL's, not a PROVISIONAL's.** A PROVISIONAL run cuts by instant, and `LoanDisbursements` writes the two facts a moment apart: the entry first, its `created_at` from the application's clock, then `decided_at`, stamped by the database at the disbursement's update. A cut between them reads the loan in both terms, and clock skew could read it in neither (`SafeguardingPositionDefinitionTest#aProvisionalCutInsideADisbursementCountsItTwice`). A PROVISIONAL run is labelled and never issued, and its statement says so.

**So:** regenerating an earlier month is byte-identical whatever came after, because a later disbursement has a later `value_date`, a later cancellation a later date, a later acceptance a later `opened_at`, a later write-off a later posting date. **That rests on two things, and only one is the ledger's.** The ledger's lock refuses a journal line dated in a locked month, which holds `outstanding`, `writtenOff` and a `value_date`, being that line's date. It refuses no `opened_at` or `decided_at`: an acceptance and a failed disbursement post nothing, and each stamp is the database's clock read through the loan's own zone, so an act just after a month ended, in a zone west of UTC, dates inside that month. What keeps it out of a month already locked is `LockGrace.MIN_BUSINESS_DAYS`, 2: the first lock falls after every zone's last day of the month, and zones reach only to -14:00. Lowering that minimum would let a later act change a FINAL, so `LockGrace` names this dependency. A reopen's new revision holds every line now dated in the month, and committed does not move, because no fact behind it did.

**The mapping is by ledger purpose, under the definition's version**, never a GL mapping's sections, so a new mapping version moves no figure; a pinned version decides only a row's `glAccount`, the drill-down key. Total over the ledger's forty, and a purpose added later fails `SafeguardingPurposeCensusTest` until disposed of. In: `CASH_AT_BANK`; clearing receivables `SETTLEMENT_CLEARING`, `INSTANT_CLEARING`; customer wallets; merchant payables, a BNPL financing's among them; both credit balances; clearing payables `PAYOUT_CLEARING`, because a payable was debited and the cash has not left. Each counterparty on `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` on its own side, its debit balance a receivable and its credit balance a payable, never netted (`INV-RAIL-04`). `SUSPENSE_UNMATCHED` at its net credit only: money received and not attributed may be a customer's, so out it would read as surplus, and a net debit is unexplained money gone, never a negative liability hiding a shortfall. Out, served beside the position: `CHARGEBACK_RECOVERABLE`, `FX_POSITION`. Out: interest and fee receivables, capital, every revenue and expense. `body.mapping` states each with its reason.

**A holder in debit is no cover for anyone else** (ADR-0061 section 5). The trial balance's reads group by purpose, owner kind, currency, scale and side, so a purpose is one row and its accounts net: wallets at +100 and -30 read 70, and against cash of 70 a position of 0, where the platform owes 100. A negative wallet is a receivable from that customer and a negative payable is merchant debt, the same kind of claim as a chargeback, which stays out for being neither cash nor a clearing receivable. So `CUSTOMER_WALLET`, `MERCHANT_PAYABLE`, `LOAN_CREDIT_BALANCE` and `BNPL_CREDIT_BALANCE` are read a second time per account (`LendingFundedAsOfCutoff.heldPerAccount`), a `ledger` statement on the same snapshot, by posting date and frozen by the trial balance's own argument. Each liability line is its accounts in credit, gross; its accounts in debit, their sum and how many, stand beside the position and out of it; and each writes the trial balance's net, so the form's `CUSTOMER_WALLETS_FOOT`, `MERCHANT_PAYABLES_FOOT` and `CREDIT_BALANCES_FOOT` hold gross less debt to it, and two statements drifting apart leave the draft unable to validate. **Every other purpose in the position hides nothing this way.** `CASH_AT_BANK`, `SETTLEMENT_CLEARING`, `INSTANT_CLEARING`, `PAYOUT_CLEARING` and `SUSPENSE_UNMATCHED` hold one account per currency (`ledger_account_one_operational_per_purpose_currency`), so they net no holder; a figure against its normal side reads negative and is never moved. `FX_PROVIDER_CLEARING` and `CORRIDOR_CLEARING` hold one per counterparty and currency, each row on its own side. Principal is summed as the headroom sums it, and a credit on one loan can only lower the adjustment. **The one netting left** is inside suspense's one account, a debit item against a credit item: no account per holder exists to read, and `SUSPENSE` shows the register gross. `SafeguardingPurposeCensusTest#noPurposeInThePositionNetsOneHolderAgainstAnother` holds the reasons to the ledger's owner kinds, and `body.mapping` says which is which.

**A shortfall is a figure, and each figure says its own.** The form's nine rules are `EQUALS` footings, arithmetic that must hold in any month: a rule on a position would make the return impossible to validate in exactly the month it matters, and the way on would be to change the configuration. Each position reads negative, unfloored; a person sees them on the form's `POSITION_BEFORE` and `POSITION` lines at validation and attestation. **No flag is called shortfall:** keyed on `position` it would say none in the month loan money that has left masks one. `positionNegative` and `positionBeforePrincipalUnrepaidNegative` each state one figure's sign; `POSITION`'s label and `drillDown.position` say a positive figure proves no cover and to read `POSITION_BEFORE`; and `customerFundedLiabilities` says it can read negative, because `principalUnrepaid` does not fall when loan money leaves wallets. No meter or alert: `PI-011`, keyed on `positionBeforePrincipalUnrepaid`.

**A zero is a read that succeeded.** Each currency element holds every term, so a currency with wallets and no lending validates on true zeros, and a read that fails throws, so no FINAL exists and the draft is refused: the two cannot look alike. A currency nothing touched has no element, because no scale exists without an amount and no JDK table decides one.

**What the subtraction is, by the owner's ruling (2026-11-05, `P14-TSK-019`'s items (a) and (b); `P14-TSK-023`).** `committed` is out of it and stays a line: an accepted loan awaiting disbursement has created no wallet money, so no liability stands behind it to subtract it from, and subtracting it lifted the position with nothing behind it. It was built as ADR-0096 section 6 first said until this ruling, and read capital 30, deposits 100, a loss of 50 and 30 accepted as 10 over; that month now reads short by 20 on both positions. Capital's identity keeps the term: capital = headroom + outstanding + committed + written off, so the return's `principalUnrepaid` + `principalCommitted` = `Headroom.lendingFunded()`, which does not change. The principal written off stays in the subtraction (`P14-TSK-021`'s owner item (h)).

**`positionBeforePrincipalUnrepaid` is the figure that warns, and the adjustment a disclosure.** `principalUnrepaid` is an upper bound on the money capital created still in wallets and payables, since fungible wallets cannot say how much is left. By double entry, per currency, with what the return leaves out being receivables, chargebacks, the FX position, a holder's debt and suspense at a net debit:

```
position                        = capital + results + written off           - left out
positionBeforePrincipalUnrepaid = capital + results - principal outstanding - left out
```

**So this ruling did not end every masking.** `position` = `positionBeforePrincipalUnrepaid` + `principalUnrepaid`, so it reads zero or above in a month short on the figure that warns whenever `principalUnrepaid` is at least that shortfall: capital 30, deposits 100, 30 lent and withdrawn and a loss of 20 reads `position` +10 and the figure that warns -20. An acceptance moves neither. A disbursement, or a BNPL purchase, lowers the figure that warns by what it lands as a credit balance, wallet or payable money created and no cash entering, and raises `position` only by what it clears of a holder's debt and by any fee it earns, since a credit needs no funds check and lands on a holder in debit as readily as on one in credit: on a wallet in credit with no fee the worked month then reads -20 and -50; onto a wallet at -10, a disbursement of 30 clears 10 and lands 20, `position` +10 and the figure that warns -20 (`SafeguardingPositionDefinitionTest#aDisbursementOntoAHolderInDebt`, the fix round's M-3). A withdrawal of loan money leaves `position` where it was and lowers cash and wallets alike, so it moves the figure that warns not at all. `SafeguardingPositionDefinitionTest` pins all of it. No real safeguarding regime's rules are claimed; a real one would define its own form.

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
