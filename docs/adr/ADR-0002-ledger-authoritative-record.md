# ADR-0002 — Immutable double-entry journal postings are the authoritative financial record

Status: Proposed

Date: 2026-08-31

## Context

Every system that moves money needs one answer to "how much does this account hold, and
why?". The common shortcut is a balance column updated in place, with a transaction table
kept alongside for display purposes.

That shortcut fails in specific, expensive ways: the balance and the history diverge, there
is no way to prove which is correct, reconciliation has nothing authoritative to compare
against, and corrections are made by editing the balance — which is indistinguishable from
fraud in an audit.

`CLAUDE.md` already forbids `balance = balance + amount` as the only financial truth. This
ADR makes the positive statement.

## Decision

**Immutable, balanced double-entry journal entries are the authoritative financial record.**

- Every economic event that changes financial position produces a journal entry.
- Every journal entry has at least two lines and balances per currency.
- Posted entries and lines are never updated or deleted, enforced at the database privilege
  level, not by application convention.
- Balances are derived from postings (ADR-0009).
- Corrections are new balanced entries referencing the original — reversals or adjustments,
  never edits.
- The `ledger` module is the only writer of postings.

Every monetary change must be explainable as:
economic event → domain operation → financial transaction → journal entry →
debit/credit lines → resulting balances → settlement → reconciliation.

## Alternatives Considered

### Option A — Mutable balance with a transaction log
Pros: Simple; fast reads; familiar.
Cons: Balance and log can disagree with no authority to resolve it. Corrections mutate
state. No natural reconciliation anchor. Cannot produce a trial balance. Explicitly
forbidden by `CLAUDE.md` and `.claude/rules/ledger-domain.md`.

### Option B — Single-entry transaction ledger
Pros: Simpler than double entry; sufficient for a closed wallet system.
Cons: No counterparty account, so there is no systemic correctness check. Cannot detect a
one-sided posting. Cannot produce financial statements. Does not extend to fees, FX,
lending or accounting — all of which are in scope.

### Option C — Immutable double-entry ledger (chosen)
Pros: Total debits equal total credits gives a continuous, system-wide correctness signal.
Every balance is reproducible. Corrections are visible. Reconciliation has an authority.
Extends naturally to Phase 14 accounting. It is what the industry actually uses, for these
reasons.
Cons: More postings per operation. Requires explicit accounting rules per economic event.
Balance reads need a projection to be fast. Higher initial design cost.

## Consequences

Positive:
- Trial balance summing to zero per currency becomes a continuously monitorable invariant —
  the single most valuable correctness signal the platform has.
- Any balance can be reproduced from zero, satisfying "a balance must be explainable".
- Phase 8 reconciliation and Phase 14 accounting have a real foundation.

Negative:
- Every money-moving feature must define its accounting treatment before implementation.
  This is deliberate friction.
- Posting volume grows faster than transaction volume; partitioning is a Phase 16 concern.
- Balance reads require a projection with a documented staleness bound.

Operational impact: A continuous trial-balance verification job with alerting becomes a core
operational control.

Security impact: Immutability is enforced by database privileges — the application role
holds `INSERT` and `SELECT` only. This protects history from both bugs and insiders.

Financial impact: This decision is the foundation of financial correctness for the entire
platform. Every later phase inherits it.

## Invariants / Constraints

`INV-LED-01` through `INV-LED-06`, `INV-BAL-01`, `INV-BAL-02`, `INV-BAL-03`,
`INV-HIST-01`, `INV-REV-01`, `INV-ACC-01`.

## Follow-up

- Phase 3: ADR on chart-of-accounts structure.
- Phase 3: ADR on isolation level and concurrency control for postings.
- Phase 9: multi-currency posting and FX position treatment.
- Phase 14: mapping from the operational ledger to the general ledger.
