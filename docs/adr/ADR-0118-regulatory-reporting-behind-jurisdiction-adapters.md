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
