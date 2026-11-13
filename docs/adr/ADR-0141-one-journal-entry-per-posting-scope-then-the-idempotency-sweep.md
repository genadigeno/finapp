# ADR-0141 — One journal entry per posting scope, for ever, then the idempotency records swept

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Ledger · Platform
Rests on: ADR-0004 (the idempotency record); ADR-0014 (multi-instance execution, clocks); ADR-0122 decision 5 (expand-only);
`THREAT_MODEL.md` §6 item 18 (decided 2026-11-09) and item 63; `CURRENT_STATE.md` debt row #61 (ruled by `P15-TSK-002`, C15);
`DATA_MIGRATIONS.md` §4.1 and §8; `DATA_RETENTION.md`.

## Context

The idempotency record (`platform.idempotency_record`, ADR-0004) is the first arbiter of every keyed command: a retry under its key
replays the stored outcome. For a posting it was also the only arbiter: `journal_entry.idempotency_scope`, the scope-and-key that ties
an entry to its command (INV-LED-04), was not unique. So a swept record would let a re-presented posting key claim afresh and post a
second entry, and the records have never been swept (debt row #61, ruled not settled by `P15-TSK-002`). The table grows without bound.
The owner decided (item 18) to land a uniqueness on the posting scope, then sweep, then revoke the application's unused `DELETE`.

Three facts shape how. The journal holds rows already, and no journal row can be deleted or edited (INV-HIST-01), so a uniqueness
cannot be bought by removing a duplicate. A unique index has no `NOT VALID` form, so the expand-only lint's tightening rule
(`DATA_MIGRATIONS.md` §4.1) admits it only over a named data fix of the same release. And the record's expiry is written from an
instance's clock, while the sweep must judge it on the database's.

## Decision

1. **A census first, kept as a row.** Ledger `V036` counts, on every database the release reaches, how many scopes more than one
   entry shares, and writes the counts (never a scope, key, entry or amount) to `ledger.posting_scope_census`, append-only for every
   role. The table has no constraint, so the census always records what it found.
2. **The uniqueness stands on the census.** Ledger `V037` first adds a `CHECK` to the census table that every row says zero (an
   unknown count fails it), then builds `journal_entry_one_per_idempotency_scope`, a unique, non-deferrable index on
   `idempotency_scope`. At any duplicate the release stops at the `CHECK`, before the index, in its own transaction: nothing is
   deleted, the census row is the finding, and the environment stays on the release before, which never sweeps
   (`OPERATIONS_RUNBOOK.md` §11.56). Its contract marker names `V036` as its data fix and waives `UNIQUE` and `CHECK`: no release has
   ever written two entries under one scope.
3. **The journal answers a posting it already holds.** `JdbcJournalEntryStore.append` inserts `ON CONFLICT (idempotency_scope) DO
   NOTHING`; a refused entry writes no line and raises `PostingScopeTakenException`, leaving the transaction usable. Inside a fresh
   claim, `PostingService` looks the scope up before anything else, so a retry whose record has gone is answered with the recorded
   entry's identifier (the bytes the first execution stored) before any cutoff is read; `ReversalService` does the same before its
   bound, which would count that very reversal. The entry is the request's witness: compared field by field with what the fingerprint
   covers, a different request is `INV-IDEM-03`'s conflict. A writer that claimed nothing and committed the scope meanwhile meets the
   index and is answered the same way. Nothing is written on either path: no entry, audit, event or projection.
4. **Then the sweep, which waits for the uniqueness.** Platform `V016`'s `platform.sweep_expired_idempotency_records` deletes one
   batch of `COMPLETED` or `FAILED` records whose own `expires_at` is more than the grace behind the database's clock, under `FOR
   UPDATE SKIP LOCKED` in a materialised CTE, the predicate repeated on the locked row. It refuses every batch while a valid,
   non-partial unique index on exactly `ledger.journal_entry (idempotency_scope)` is absent, read from the catalogue by shape, and at
   any column of the table it was not reviewed against. A fourth `RetentionSweepSchedule` bean runs it on every instance, leaderless.
5. **The grace: 30 days by default, never less than a day.** `finapp.idempotency.sweep.grace`, refused at start and by the function
   below a day, the margin over a written expiry that skewed instance clocks and stragglers need. Until it is swept a record answers
   every retry, so a key is answered for its expiry plus the grace. The default keeps 31 days of keyed commands (about 25 million rows
   at W1's day, **derived**: 434,000 journal entries and 377,000 keyed money movements a day, `CAPACITY_MODEL.md` §3.1, times 31, the other keyed commands besides), against an
   unbounded table today.
6. **The application's `DELETE` is revoked.** The function is the only path out of the table.

## Consequences

- A posting is once for ever, whatever becomes of its record, under ten instances racing one scope: the index arbitrates.
- **What the sweep leaves to other commands** is `THREAT_MODEL.md` §6 item 63: a command whose only arbiter is its record (a
  transfer and a card checkout, as read: each posts under a key derived from a row it creates) is a new command when retried after its
  record is swept. That is `DATA_MIGRATIONS.md` §8's contract made real; the grace's default puts it a month past the 24-hour retry
  window the records are written with.
- The posting hot path gains one index probe (the lookup) and one index entry per entry; the claim's completion gains one partial
  index entry. Both builds hold `SHARE` on their tables for their length. E62 measures the first under load against E38's run on the
  tree before this decision, and E64 the builds' write stop on a soak's journal; both are owed (`POSTPONED_ISSUES.md` PI-025). E63
  forces a sweep under load and replays posting keys against the swept records.
- The platform's sweep reads the ledger's catalogue entry. It reads no ledger row and binds no ledger object, and it is the one place a
  platform function names a ledger table: the cost of a sweep that cannot outrun the uniqueness it rests on.
- A floored posting retried as stated after its record is swept is refused as a conflict, where its record would have replayed it:
  the entry cannot say whether its date was floored. A refusal, never a second entry.

## Alternatives rejected

- **A partial or grandfathered index** that tolerates existing duplicates: it would make the uniqueness depend on data no one has
  reviewed, and no duplicate is expected; the census decides instead, and a duplicate stops the release for a person.
- **Mapping the unique violation (`23505`) in the store**: the error aborts the caller's transaction, so the stored outcome could not be
  read; `ON CONFLICT DO NOTHING` keeps it usable.
- **Relying on the insert's conflict alone, without the lookup**: the cutoff's and the reversal bound's triggers fire before the index
  is consulted, so a retry of an old posting would read as a closed period, and a full reversal's as an over-reversal.
- **Sweeping at the record's expiry, with no grace**: the expiry is an instance's clock; and it would turn the 24-hour window into
  the whole of every non-posting command's once-ness on the first day.
- **A per-scope retention**: a change to what a key means, excluded by the task.
