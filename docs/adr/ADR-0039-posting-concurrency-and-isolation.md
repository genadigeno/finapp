# ADR-0039 — Posting concurrency: `READ COMMITTED` with the balance derived, never locked

Status: Proposed
Date: 2026-09-13
Phase: 3
Context: Ledger
Supersedes: nothing. Closes unresolved question 1, open since `P0-TSK-011`.

## Context

`INV-CON-01` states that concurrent operations on the same financial resource never produce a
lost update, a double spend, or a balance inconsistent with its postings. It is the invariant
the catalogue marks `Phase: 3`, and the one whose violation *"produces losses that are only
found at reconciliation, if at all."*

Phase 3 is where money arrives, so the question is no longer abstract: when ten instances post
to the same ledger account simultaneously, what stops them producing a wrong answer?

**The question has two halves, and conflating them is the mistake.** One is *how do postings
serialise?* The other is *how does a decision that depends on a balance — a hold, an overdraft
check — stay correct while postings continue?* A design that answers only the first produces a
ledger that is internally consistent and still permits a double spend.

## Decision

**Postings are inserts under `READ COMMITTED`, and they take no lock on any account.** A
journal entry and its lines are appended; nothing is updated; there is no row whose previous
value must be read. Under append-only semantics `READ COMMITTED` has no lost update to have,
because there is no update.

**A balance is never read-then-written.** It is derived by aggregating postings, and where a
projection exists it is a cache anchored to them (ADR-0009, ADR-0041) — never a counter that a
posting increments. `balance = balance + amount` is forbidden by `INV-BAL-01` and by
`CLAUDE.md` rule 5, and the reason bites hardest exactly here: a counter is precisely the row
whose concurrent update is lost.

**Any decision that must be atomic with respect to a balance takes an explicit lock on the
account row, not on the postings.** The pattern is `SELECT … FROM ledger.ledger_account WHERE
id = ? FOR UPDATE`, then evaluate, then post — the **lock-then-look** protocol `P2-TSK-015`
established for the ownership gate, applied to money. This covers:

- **placing a hold** (`INV-BAL-04`: a hold may not make available balance negative);
- **any future debit that must not overdraw**;
- and nothing else. An ordinary balanced posting between two accounts, authorised elsewhere,
  takes no lock.

**`SERIALIZABLE` is not used**, and that is a deliberate rejection rather than an omission —
see below.

**Every money-moving command is idempotent at the financial boundary** through the Phase 0
kernel (`INV-IDEM-01`, ADR-0004): a unique constraint on (scope, idempotency key), claimed by
insert, in the posting's own transaction. Concurrency control and idempotency are different
mechanisms answering different questions, and neither substitutes for the other.

## Why

**Why `READ COMMITTED` rather than `SERIALIZABLE`.** `SERIALIZABLE` in PostgreSQL is
Serializable Snapshot Isolation: it detects dangerous read-write patterns and aborts one
transaction with a serialisation failure. That is correct and it is the wrong trade here, for
three reasons.

1. **It moves the failure to run time and spreads it everywhere.** Every posting path would
   need a retry loop, and a retry loop around a money-moving command is the place where "the
   database committed but the response was lost" becomes two effects. The idempotency kernel
   makes retries safe, but only for retries the *client* drives with a key — an internal
   retry-on-serialisation-failure is a second effect waiting for the first author who forgets.
2. **It is a global setting solving a local problem.** The only operations here that genuinely
   need mutual exclusion are the ones that read a balance and act on it. Those are a small,
   enumerable set, and an explicit `FOR UPDATE` on the account row states the requirement *at
   the site that has it*, where a reader can see it.
3. **Append-only writes do not need it.** SSI protects against anomalies arising from
   read-write dependencies. A posting reads nothing it then writes.

**Why lock the account row rather than the balance projection.** The projection is derived and
may be rebuilt, so a lock on it protects nothing durable. The account row exists for the life
of the account and is the natural mutual-exclusion point for "decisions about this account".

**Why not an advisory lock.** The relay uses one because its subject — an aggregate's event
stream — has no row of its own. An account has a row. A lock on the real row cannot drift out
of correspondence with the thing it protects, and it is visible in `pg_locks` next to the
account an operator is looking at.

**Why the entry-level balance check is a database constraint and not only a domain rule.**
`INV-LED-01` is enforced at `DOMAIN` **and** `DB-CONSTRAINT` because the domain is not the only
writer a schema will ever have — a migration, an operator, a future batch job. The check is
per-entry and per-currency, which is what makes it expressible at all.

## Consequences

- Concurrency tests must race **real connections**, one per simulated instance, against a real
  PostgreSQL (`P0-TST-009`). A single-JVM test with one connection proves nothing about
  isolation.
- The hold path has a contention point by design. That is the correct place for one: a customer's
  own account, serialising that customer's own balance-affecting decisions.
- `DISTRIBUTED_EXECUTION.md` §3 gains rows for `ledger.journal_entry`, `ledger.journal_line`,
  `ledger.ledger_account` and `ledger.hold` when Phase 3 builds them.
- If a later phase introduces an operation that reads *many* balances and acts on the set — a
  batch sweep, a netting run — this ADR does not cover it, and that operation needs its own
  decision rather than a lock loop.

## Alternatives rejected

| Option | Why not |
|---|---|
| `SERIALIZABLE` everywhere | Retry loops around money-moving commands; a global answer to a local question; unnecessary for append-only writes |
| `REPEATABLE READ` | Same retry burden at the point of a write conflict, without SSI's benefit for the read-write patterns that motivated it |
| Optimistic version column on the account | A version column implies the account row carries the balance, which is exactly the `balance = balance + amount` model `INV-BAL-01` forbids |
| Advisory lock per account | An account has a real row; a lock keyed on a number can drift from the thing it protects and is invisible beside it |
| Lock the account on **every** posting | Serialises the whole ledger on its busiest accounts — an operational account receives every transaction — for a guarantee append-only writes do not need |

## Follow-ups

- The **balance-read staleness bound** for decisions that do *not* take the lock is
  `INV-BAL-05`'s subject and is ADR-0041's to state.
- Batch/netting operations: an open decision, owned by whichever phase introduces one.
