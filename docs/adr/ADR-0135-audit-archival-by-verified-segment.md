# ADR-0135 — Audit archival by verified segment, and the session sweep

Status: Proposed

Date: 2026-11-13

## Context

ADR-0010 makes the audit trail append-only and says archival must preserve auditability, so deletion is not an option.
`DATA_RETENTION.md` §3 (`P15-TSK-029`) designed the archival as a verified copy to write-once storage, starting from monthly
partitions; `THREAT_MODEL.md` §6 item 18 (decided 2026-11-09) scheduled the build, with the session sweep, into Phase 16.
Three facts shape the build:

- **Partitioning is not decided.** It is `P16-TSK-012`'s, by measurement and an ADR (`PHASE_16_PLAN.md` §6.5), and the plan
  says the archival is built regardless. A design that starts from closed partitions cannot be built now.
- **A position over the audit table is a position a clock or a writer can choose.** `audit_id` is a UUIDv7 minted on an
  instance's clock and `occurred_at` is that instance's business time; a transaction can commit long after it began. A cursor
  over either can pass a row that commits behind it, for good and with no signal.
- **The application uses no object store** (ADR-0036), no real bucket exists (`infra/terraform/modules/object-storage` is
  never applied), and Phase 16 runs nothing on a cluster until after the phases (`POSTPONED_ISSUES.md` PI-025).

## Decision

1. **The unit is a segment, not a partition.** A committed audit row never changes, so it is closed when it commits. A segment
   is up to `finapp.audit.archive.batch-size` committed rows, fixed when it is cut. No period has to end first.
2. **A queue, written by the database, instead of a cursor.** An `AFTER INSERT` trigger on `platform.audit_record` (SECURITY
   DEFINER) adds each row's id to `platform.audit_archive_pending` in the transaction that writes the row; the migration queues
   the rows written before it while the trigger's lock holds new writes back. A segment is cut by moving ids from the queue to
   `platform.audit_archive_member`, whose primary key puts each audit row in one segment, ever.
3. **One claimer, one segment in flight, a chain.** Every instance ticks; the claim takes one head row `FOR UPDATE SKIP
   LOCKED`. Under it the one `CLAIMED` segment (a partial unique index allows one) is left while its lease lives, on the
   database's clock, and taken over with a new holder token once it lapses; with none claimed, the next is cut. Segments are
   archived in order, each manifest naming its predecessor's digest.
4. **The copy is the database's own text.** Format 1: one line per row, in id order, a JSON array of every column in the
   table's order, timestamps in UTC to the microsecond (`platform.audit_archive_content`), and a JSON manifest
   (`platform.audit_archive_manifest`). Both are pure functions of rows that never change and of a digest fixed before the
   segment was cut, so a taken-over segment renders the same bytes. `platform.audit_archive_record` reads a line back into a
   row. A column added to the table, or a changed type or order, stops the archive until a migration reviews it.
5. **Write once, verify, then mark.** Each object goes to a key fixed by the segment's number with a write that succeeds only
   where no object is (S3 `PUT` with `If-None-Match: *`); a `412` is read back like any other. Both objects are read back and
   compared byte for byte; only then is the segment marked `ARCHIVED`, with the digests of what was read back, which
   `complete_audit_archive_segment` recomputes from the rows, refusing otherwise (`AADGS`), and refusing a caller that no longer
   holds the segment (`AAHLD`).
6. **Fail closed.** A store that is down leaves the segment claimed and nothing lost. A copy that is missing or differs is never
   marked; every later attempt meets the same object, so the archive stops there and pages, because a chain built over a broken
   link is worse than a paused one.
7. **The store is a port; one adapter, S3's object API, unsigned for now.** `HttpAuditArchiveStore` speaks the subset the archive
   needs, https off loopback, every exchange bounded in time and in size. The archive runs where `finapp.audit.archive.endpoint`
   names a store; no committed overlay names one, and on `kind` the experiments scope names a test-only write-once store
   (`ArchiveStore.java`). Request signing and the pod role's credential for a real bucket are not built (`THREAT_MODEL.md` §6
   item 57 (a)).
8. **Encryption at rest is the bucket's.** SSE-KMS under the archive's own key, a key separate from the database's (Terraform,
   `THREAT_MODEL.md` T10). The application holds no archive key, so no rotation of an application key can strand an archived copy.
9. **The session sweep is identity's own function run as a retention sweep.** `identity.sweep_expired_sessions` (SECURITY DEFINER,
   identity `V027`) deletes `ACTIVE` or `REVOKED` rows whose idle bound passed more than `finapp.identity.session.sweep.grace`
   (default 30 days, refused under a minute) ago, on the database's clock, `FOR UPDATE SKIP LOCKED`, and stops at a column it was
   not reviewed against. A live session's idle bound is ahead of `now()`, so it is never selected. `RetentionSweep.ofModuleFunction`
   lets a module name its own schema's `sweep_` function without `platform` knowing its schema.

## Alternatives Considered

### Partition first, then archive closed partitions (`DATA_RETENTION.md` §3 as designed)
Pros: an archive unit per period; old partitions could later be detached. Cons: needs the partitioning decision, which is not taken
and must not be guessed; a partition is "closed" only by a period and a clock.

### A cursor over `audit_id` or `occurred_at` with a safety margin
Pros: no trigger; no queue table. Cons: the position is chosen by instances' clocks and by when transactions commit; any row behind
the margin is skipped for good with no signal. An index on the cursor's column costs the hot path what the queue costs.

### A marker column on `platform.audit_record`
Pros: one table. Cons: marking means `UPDATE` on the audit table, which the application must never hold (`INV-HIST-03`); even a
definer function writing one column alters every audit row.

### Several segments in flight, chained at the mark
Pros: parallel exports. Cons: the chain then needs its own ordering step; no volume the plan names needs it (one tick archives up to
twenty segments).

### The AWS SDK, or SigV4 written now
Pros: a real bucket reachable. Cons: the SDK brings a dependency tree into the verified build for a path no environment can use; a
hand-written signer with static keys would be dead code everywhere (no environment has a static archive key, and a production pod
would use its role). Both wait for the first real bucket (item 57).

## Consequences

Positive: the audit trail gains a copy outside the database's failure and insider domain, provably equal to its rows, with nothing
deleted; a row cannot escape the archive by committing late; ten instances archive each row once, by the database.
Negative: one insert more in every audited transaction (the queue); one segment in flight at a time; a divergent object stops the
archive until a person acts; until a store is named the queue only grows.
Operational impact: `OPERATIONS_RUNBOOK.md` §11.15 (the session sweep) and §11.50 (the archive: its signals, its failures, the read
path); alerts on the backlog's age and on a copy that differs; experiments E44–E46 built and owed (PI-025).
Security impact: no new grant of a write on any audit table; the application role executes six functions and reads the archive's
tables; the store is reached over TLS with bounded calls, and its credential is not built.
Financial impact: none; no money moves. The trail that defends postings (`INV-LED-05` through ADR-0010) is kept whole.

## Invariants / Constraints

`INV-HIST-03`, `INV-AUD-01`, `INV-AUD-02`, `INV-IDN-03` (the session sweep never touches a live session), ADR-0010, ADR-0014,
ADR-0030, ADR-0036 (re-read: the archive is the application's first object store use, for the audit copy alone).

## Follow-up

The real bucket's signing and credential, and the read path's door and its own audit record (`THREAT_MODEL.md` §6 item 57); the
archive's lock period and mode (item 23 (b)); partitioning and whether archived partitions may ever be detached (`P16-TSK-012`, and
item 23 (b)'s period before any deletion); the runs on `kind` (E44–E46, PI-025).
