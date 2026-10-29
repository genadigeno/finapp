# ADR-0115 — Financial reporting as immutable, reproducible report runs over a pinned journal cut

Status: Proposed
Date: 2026-10-29
Phase: 14
Context: Reporting · Accounting / General Ledger
Supersedes: nothing. Writes `DELIVERY_PLAN.md` §Phase 14's anticipated "ADR on reporting read-model architecture".
Rests on `PHASE_14_PLAN.md` §13.6 and `INV-ACC-02`, `-04`, `-05`, `-14`, `INV-BAL-05`.

## Context

The platform's reports today are live operator reads (lending, bnpl, reconciliation, merchant, risk) — correct as of the
moment, never issued, never reproducible. Financial reporting needs issued outputs that answer the same question the
same way forever, and a trail from every figure to its journal lines.

## Decision

1. A **report definition** is code: a kind (`TRIAL_BALANCE`, `GL_ACTIVITY`, `BALANCE_SHEET`, `INCOME_STATEMENT`,
   `REVENUE`, `RECEIVABLES_PAYABLES`, `SETTLEMENT_POSITIONS`, `SUSPENSE`, `FX_RESULTS`, `LOAN_PORTFOLIO`, `BNPL_EXPOSURE`,
   `RECONCILIATION_STATUS`, `CLOSE_STATUS`, regulatory forms) with a version constant.
2. A **report run** is a row born once per `(definition, definition_version, period, revision, parameters_hash)`: claimed
   (insert-or-read), generated in a `REPEATABLE READ READ ONLY` transaction, completed by a conditional edge with its
   content (`jsonb`) and `content_sha256`, frozen by trigger. It records its inputs: the period and its revision, the
   mapping version, the valuation rate set, the journal cut, the definition version.
3. **FINAL** runs exist only for `LOCKED`/`CLOSED` periods — whose posting-date range is immutable after the cutoff
   (ADR-0114) — so regeneration reproduces the hash (`INV-ACC-04`); `…/regeneration` answers `IDENTICAL | DIVERGED`, a
   divergence metered and alerted, never repaired. **PROVISIONAL** runs (open periods) are cut at the journal's settle
   horizon (the ADR-0111 keyset), labelled, never issued. A reopen marks FINAL runs `SUPERSEDED`; never deleted.
4. **Drill-down**: every figure carries its (GL account, currency, period) key; the GL activity door lists the journal
   lines composing it, paged (`INV-ACC-02`).
5. **Authoritative vs operational**: the existing live reports and gauges stay operational and say so; no cache,
   projection or gauge is the source of a FINAL figure (`INV-BAL-05`).

## Alternatives Considered

### Materialised GL balance tables refreshed by a job
Pros: fast reads. Cons: a projection that can drift, a second set of balances. Rejected; aggregates computed per run.

### Regenerate on demand without storing output
Pros: no storage. Cons: no proof of what was issued. Rejected.

## Consequences

Positive: issued outputs provable byte-for-byte. Negative: generation cost per run (bounded by the posting-date index).
Operational impact: lease-based completion by `AccountingCloseSchedule`. Security impact: content `CONFIDENTIAL`, reads
audited. Financial impact: none.

## Invariants / Constraints

`INV-ACC-02`, `-04`, `-05`, `-14`; `INV-HIST-04`; `INV-BAL-05`.

## Follow-up

Retention of runs with Phase 15's data-retention work; export formats beyond JSON.
