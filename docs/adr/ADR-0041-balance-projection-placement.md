# ADR-0041 — The balance projection lives in the ledger schema, is rebuildable, and is never the authority

Status: Accepted (2026-09-17, P3-DOC-001)
Date: 2026-09-13
Phase: 3
Context: Ledger
Supersedes: nothing. Closes unresolved question 3, open since project initiation.
Refines: ADR-0009 (balance is a projection), which decided *that* it is derived and not *where*
it lives or what may read it.

## Context

ADR-0009 settled that a balance is derived from postings and is never an independent authority
(`INV-BAL-01`). What it left open is placement, and placement decides three things this phase
cannot avoid: what a balance query costs, how stale an answer may be, and — the one that
matters — **which decisions may be made from the projection at all**.

`INV-BAL-05` is the constraint with teeth: *"a financial decision is never made from a
projection whose staleness is unbounded."* A projection that everything reads, including the
overdraft check, is a balance-as-truth model wearing a different word.

## Decision

**The projection is a table in the `ledger` schema, owned and written only by the ledger
module, updated in the same transaction as the posting that changes it.**

```
ledger.account_balance (ledger_account_id PK, currency, posted_minor, holds_minor,
                        last_entry_seq, updated_at)
```

Four rules, and the third is the one that makes the rest safe:

1. **Same transaction as the posting.** No asynchronous projector, no event-driven catch-up, no
   lag. The projection and the postings it summarises commit together or neither commits, so
   the projection is never *behind* — it is either current or absent along with the fact.
2. **Rebuildable from zero, and the rebuild is a first-class operation** (`INV-BAL-02`). A
   continuous verification job recomputes from postings and compares, and the comparison — not
   anybody's confidence — is the evidence the projection is right.
3. **A decision that must be exact reads the postings, under the account lock** (ADR-0039). The
   projection serves *display*: statements, dashboards, an API balance query. A hold, an
   overdraft check, any refusal-or-permit on funds, derives its number inside the locked
   transaction. **The projection is an optimisation for reads that may be approximate, and
   nothing else is permitted to rest on it.**
4. **`posted_minor` and `holds_minor` are separate columns**, because available balance is
   `posted − holds` (`INV-BAL-04`) and collapsing them into one number destroys the ability to
   explain either.

**Not a materialised view**: it cannot be updated transactionally with the posting, and
`REFRESH` is exactly the unbounded staleness `INV-BAL-05` forbids.

**Not a separate read store** (Redis, a read replica, a separate service). `CLAUDE.md` rule 12
is explicit that caches and projections are not financial truth; putting the projection where a
crash can lose it, or where it can be read without the postings being reachable, turns a
performance decision into a correctness one.

## Why

**Why same-transaction rather than event-driven.** An asynchronous projector is the standard
answer and it is wrong for the balance specifically, because its whole benefit — decoupling
write latency from projection latency — buys a lag that `INV-BAL-05` then requires us to bound,
monitor and alert on, and to *exclude from every decision path*. That is three mechanisms and a
metric to avoid one `UPDATE` in a transaction that is already open. The projection is a
denormalisation, not an integration.

The cost is honest and accepted: **every posting to an account contends on that account's
projection row.** For a customer account that is one row touched by that customer's own
traffic. For a high-volume operational account it is a genuine hot row, and the mitigation is
recorded rather than pretended away — operational accounts may be **partitioned into
sub-accounts by purpose** (ADR-0040 makes `purpose` a first-class attribute precisely so this is
expressible), and if that proves insufficient the projection for those accounts becomes an
explicit, separately-decided exception rather than a silent change to this rule.

**Why `last_entry_seq`.** It makes "is this projection current?" answerable in one comparison
rather than by recomputing, which is what lets the verification job be cheap enough to run
continuously (`INV-ACC-01`'s trial balance is the system-level version of the same idea).

**Why the projection may not back a hold.** This is the decision this ADR exists for. A hold
that reads the projection is correct exactly as long as nothing else is posting, which is the
condition under which concurrency bugs are invisible. Reading the postings under the account
lock costs an aggregate over that account's rows — bounded, indexable, and paid only by
decisions that must not be wrong.

## Consequences

- The ledger module owns the projection table; nothing else may write it (`INV-LED-04`'s shape
  applied to the derived data), enforced by a boundary test and by grants.
- A balance API response must state **what kind of number it is**. A query for display returns
  the projection; there is no public API that returns a locked, exact balance, because no
  external caller has a decision to make with one.
- The verification job is a Phase 3 deliverable, not a Phase 15 nicety: `INV-BAL-01`'s stated
  verification method is *"continuous recomputation comparison with alerting."*
- A projection rebuild must be safe **while postings continue**, which is a named Phase 3
  failure scenario rather than an assumption.

## Alternatives rejected

| Option | Why not |
|---|---|
| Asynchronous projector from the outbox | Buys lag that `INV-BAL-05` then forces us to bound, monitor and exclude from decisions — three mechanisms to avoid one in-transaction `UPDATE` |
| Materialised view | Cannot refresh transactionally with the posting; `REFRESH` *is* unbounded staleness |
| Redis / read replica / separate read service | A projection whose durability is weaker than the record it summarises; `CLAUDE.md` rule 12 |
| No projection — always aggregate postings | Correct and unusable: a statement screen for a five-year-old account aggregates its entire history on every load |
| One `balance_minor` column, holds netted in | Destroys the ability to explain available versus settled, which `INV-BAL-04` requires be separable |
| Projection may back a hold if "fresh enough" | "Fresh enough" is a staleness bound on a decision path, which is what `INV-BAL-05` forbids; and the window where it is wrong is exactly the window of concurrent posting |
