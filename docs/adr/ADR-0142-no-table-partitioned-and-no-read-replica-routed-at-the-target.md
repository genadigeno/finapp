# ADR-0142 — No table partitioned and no read replica routed at the target: each decision derived, its overturning measurement built

Status: Accepted (2026-11-16, `P16-DOC-001` — read against the code and corrected first)
Date: 2026-11-13
Phase: 16
Context: Platform · Ledger · Accounting (report generation) · every module (the data tier)
Rests on: ADR-0001, ADR-0009, ADR-0010, ADR-0011, ADR-0039, ADR-0041, ADR-0115, ADR-0126, ADR-0129, ADR-0133, ADR-0135; ledger `V004`, `V029`,
`V034`, `V035`; platform `V002`, `V005`, `V007`, `V009`, `V012`; `PHASE_16_PLAN.md` §6.5 and §6.6; `PHASE_GATES.md` §5 Phase 16
criteria 5 and 6; `CAPACITY_MODEL.md` §2, §3.1–§3.3 and §3.6; `THREAT_MODEL.md` §6 items 18, 55 and 64.

## Context

`PHASE_16_PLAN.md` §6.5 leaves partitioning (journal lines, outbox, inbox, audit, idempotency records) to be decided from the soak's
growth rates extrapolated to the production target, by an ADR, whether or not anything is built. §6.6 leaves a read replica to be
decided by whether report generation or operator reads load the primary, and holds the criterion either way by a guard. The owner
postponed every `kind` run of Phase 16 on 2026-11-13 (`POSTPONED_ISSUES.md` PI-025), so no growth rate and no report's cost has been
measured. Both decisions are taken here on what can be derived today: W1–W8's arithmetic, the schema and the statements in the code.
Each names the measurement that would confirm or overturn it, and that measurement is built and owed (E65). No figure below is
measured unless it names its run; the derived ones are `CAPACITY_MODEL.md` §3.6's.

**What partitioning costs here, read from the schema.** In PostgreSQL every unique constraint of a partitioned table must contain the
partition key. Several of this platform's uniques are its once-ness arbiters: the idempotency claim's `(scope, idempotency_key)`
(`INV-IDEM-01`), the inbox's `(consumer, dedupe_key)` (`INV-IDEM-04`), the outbox's `event_id` that every consumer deduplicates on,
the journal entry's primary key that lines, reversals and adjustment proposals reference, the line's `(entry_id, seq)`, and the
posting scope's uniqueness `P16-TSK-011` is landing. Partitioned by a time, each would be unique only within a partition: a retry, a
redelivery or a replay that falls on another day would pass its arbiter and take a second effect. And a partition can be detached or
dropped. No row trigger fires on either, nor do `V035`'s `BEFORE TRUNCATE` triggers, so a partitioned journal or audit trail gains a
bulk erasure path that `INV-HIST-01`'s guards do not see.

**What partitioning would buy, read from the statements** (`CAPACITY_MODEL.md` §3.6 lists them by table). The journal's hot reads are
by account over its whole history: the available balance under its lock, the merchant payable's breakdown, the purpose balances, the
projection's verification and rebuild, and the credit exposure. A time key prunes none of them; each would probe every partition.
Only the period-bounded reads (the GL's month, a statement's date range) would prune, and `posting_date` lives on the entry, not the
line. The from-inception reads (a trial balance's opening, the balance sheet, the BNPL and lending reports to a date, the
reconciliation disclosure) read every partition before their date. The proofs scan the table whole either way. For the swept tables
(outbox, inbox) a time key would turn the retention sweep's batched `DELETE` into a partition drop. But the outbox's sweep keeps
dead-lettered and discarded rows and rows a standing parked record names as evidence, and a drop cannot keep them.

**Growth at the target, derived** (`CAPACITY_MODEL.md` §3.6). The journal takes about 434,000 entries and 1.4 million lines a day,
0.5 billion lines a year and 1.5 billion in three years, none ever deleted. The outbox takes about 3.2 million events a day and keeps
each 30 days after its publication, about 96 million rows standing. Its sweep deletes at most 20 batches of 500 rows a five-minute
tick on each instance: 2.88 million a day an instance, 8.64 million at the three-replica minimum. So it keeps pace with 2.7 times
the target's writes; one instance alone would not. The inbox takes at most about 2.3 million rows a day and keeps them 14 days. The
idempotency records, at least 377,000 a day, are unswept until `P16-TSK-011`. The audit trail takes roughly 0.6 to 1.4 million a day,
kept and archived (`P16-TSK-010`). Bytes a row are not derived: E65 measures them.

**What a replica would serve, read from the code.** Both of the application's pools connect to one address (`DatabaseEndpoint.url`):
the writer's, which every door, schedule, consumer, proof and census uses, and report generation's (`ReportingPool`, as
`finapp_reporting`), built on the same address. Report generation is operator-initiated (no workload assumption states how many runs a
day), bounded to one connection an instance by its pool and to four minutes a statement (`finapp.accounting.report.generation-timeout`).
Its trial-balance-based definitions fold the journal **from inception**: the trial balance's opening and its ledger windows, the balance
sheet, the GL activity's opening and the purpose reports' openings. At the target that is about half a billion lines after a year,
so a report's cost grows with the journal's age, as the merchant payable's read grows with a merchant's (ADR-0133). The driver can
also choose a server: pgjdbc takes a list of hosts and, by its default `targetServerType=any`, the first that answers, primary or
standby. So a deployment's URL alone could put either pool on a copy.

## Decision

1. **No table is partitioned, at the target or now.**
   - The journal (`ledger.journal_entry`, `ledger.journal_line`) is never partitioned for capacity. It is never deleted, so a partition
     buys no retention. Its uniques are integrity and once-ness arbiters, a drop or detach would be an erasure no guard sees, and its
     hot reads are by account over the whole history, where a time key prunes nothing. Their cost is bounded by ADR-0133's
     commit-order checkpoint for the account folds (item 55) and by item 64's decision for the report folds, never by a partition. *(Corrected by `P16-DOC-001`, the Phase 16 exit review, 2026-11-16: as built, the checkpoint bounds one account fold: the merchant API's payable read (`P16-REM-001`, ledger `V038`, `CheckpointedPositionBreakdown`, its only reader held by `PositionCheckpointsAreTheApisAloneTest`); the report folds are bounded by `P16-REM-003` (accounting `V017`). The other account folds still read the whole history - `AvailableBalance` under the account's lock among them, on the money-moving path - and their bound waits on E38's measurement, owed under PI-025. The decision stands: a time key prunes none of them.)*
   - The idempotency claims and the inbox are never partitioned by a time: their primary keys are the once-ness arbiters.
   - The outbox, the audit trail and the payment attempts and their history are not partitioned. The outbox's sweep keeps pace at the
     minimum fleet and keeps evidence a drop would lose; the audit trail and the attempts are kept, so a partition buys no retention
     until a jurisdiction's period is decided (`THREAT_MODEL.md` §6 item 23 (b)). The audit trail's archive is built by segment, not by
     partition (ADR-0135), and its queue (`platform.audit_archive_pending`) is one of the tables E65 reads.
   - The balance projection, the window counter and the other tables bounded by their population, not by time, need nothing.
   - **What would overturn it**, each read by E65 from the soak's own samples (E39's run, PI-025):
     - for a swept table, its rows written a day at the target (inserted a money movement × the target's 377,000 movements a day) above
       its sweep's derived capacity at three replicas, or its dead rows above its autovacuum trigger and rising through the soak. The
       sweep's cadence and batch are changed first, by configuration. A partition by an immutable time column is considered only for a
       table whose arbiter survives it, which today is none;
     - for an append-only table, an autovacuum run on it, read from the soak's log, whose duration grows with the table's whole size
       rather than with the rows written since the last run. An index build's write stop on the journal is item 42 (b)'s, decided by the
       migrator's mode, not by partitioning.

     Any such reading becomes a new ADR before anything is built. That ADR first answers the uniques, the erasure path and the
     queries this one names.
2. **No read replica is routed.** One primary serves every door, every decision, every balance and once-ness check, the proofs, the
   census and report generation. That is `PHASE_GATES.md` §5 Phase 16 criterion 6's first branch, held by a guard (decision 3).
   - **Never eligible for a replica**: every money-moving door, every balance, hold, limit, four-eyes or once-ness check, every schedule
     and consumer, the close's checks (they gate a period's close), the proofs and the census, reconciliation, and a customer's or a
     merchant's balance and payable reads (what they act on must agree with the decision their next command meets).
   - **The only read ever eligible** is report generation, on its own pool. It may be routed only by a later ADR, and only with all of
     these built and tested first:
     - the reporting pool alone reaches the replica, as `finapp_reporting` alone;
     - every run states the replica's replay position and its replay timestamp;
     - a FINAL run, and a FINAL run's regeneration, refused unless the replica has replayed past the commit that locked its period,
       which the lock must then record;
     - the replica down or behind its bound means report generation refuses, never a silent return to the primary, and money moves on
       untouched;
     - the guard turned to the second branch: the reporting pool reaches the replica alone, and no money-moving door reaches that pool.
   - **What would overturn it**: E65's runs fit report generation's slowest statement against the journal's lines. At the target's
     journal (about half a billion lines a year), a statement past the four-minute bound is item 64's: the fold is bounded first, and
     a replica moves that cost without bounding it. A replica is reconsidered only if, after that, report generation's cost at the
     target, times the runs a day the owner expects (no assumption states it), takes more of the primary's CPU at the busiest hour than
     the target's peak leaves free (E29 and E30 at seven replicas, `P16-TSK-007`'s, owed under `POSTPONED_ISSUES.md` PI-025; *corrected by `P16-DOC-001`, 2026-11-16*).
3. **The guard, criterion 6's first branch.** One primary data source serves every door, held in layers:
   - no production class but `DatabaseEndpoint` names a database's address, in any module's bytes;
   - no production class but `ReportingPool` builds a pool or a connection (the driver, `DriverManager`, a `javax.sql` source other
     than `DataSource`, Spring's builders and driver-backed sources, and HikariCP's constructors, its configuration, a connection
     borrowed from it, its driver source unwrapped, its setters and its close). A class may hold the writer pool's own type to read
     its statistics and fixed settings, as the load-shedding door reads its queue (ADR-0137), and nothing else of it;
   - no production `@Bean` declares a `DataSource`, so the writer is Spring Boot's one auto-configured pool;
   - only report generation's wiring reaches the reporting pool (its own package, and `AccountingBeans`' two methods that hand it to the
     run generator), with the entry's probe planted: a money-moving door wired to that pool goes red (`OnePrimaryServesEveryDoorTest`);
   - the reporting pool connects to the writer's address, under any name a replica route might be given (`ReportingPoolEndpointTest`);
   - the driver may take only a primary: a `targetServerType` other than `primary`, or a URL naming other than one readable host
     without `targetServerType=primary`, refuses to start (`PrimaryOnlyGuard`). A single host that is itself a standby fails at the
     first write, and every money-moving door writes its claim first.

## Alternatives Considered

### Partition the journal by posting month
Pros: the GL's month and a statement's range would prune; an index could be built per partition.
Cons: every unique, the posting scope's among them, would hold within a month only. The account folds would probe every partition.
Detach and drop would be an erasure path, and the date would have to move onto the line. Rejected.

### Partition the journal by a hash of the account
Pros: the account folds would read one partition.
Cons: no retention, the same uniques split, the entry's balance check reaching across partitions for its lines; the folds' cost is their
history, which ADR-0133's checkpoint bounds without it. Rejected.

### Partition the outbox and the inbox by day, and drop old partitions
Pros: retention by `DROP` instead of a batched `DELETE`, with no bloat.
Cons: the inbox's arbiter and the outbox's identity split across days, and a drop cannot keep the outbox's evidence rows. The sweeps keep
pace by derivation, and E65 measures their tables. Rejected unless E65 says otherwise.

### Route report generation to a replica now
Pros: the from-inception folds' cost moved off the primary.
Cons: it moves the cost without bounding it, the four-minute bound is still passed, and a FINAL report then needs replay past its lock to
be right. No measurement asks for it. Deferred to the conditions above.

### Hold the criterion by documentation alone
Rejected: `PHASE_GATES.md` asks for a guard, and the driver's own host choice is a path no document closes.

## Consequences

Positive: every once-ness arbiter and the journal's immutability stay whole. No stale read can reach a decision, by code or by
configuration. Each decision says what would overturn it, and the reading is built.
Negative: the outbox's and the inbox's retention stays a batched `DELETE` with its vacuum cost. Report generation's whole-history folds
stay on the primary and grow with the journal (item 64).
Operational impact: a deployment whose URL lists several hosts must say `targetServerType=primary`. A single-endpoint deployment
(the managed database's, `kind`'s Service) changes nothing.
Security impact: the reporting role remains the only route a replica could ever be given. The driver's host choice is closed as a side
door.
Financial impact: none. No posting, balance or record changes.

## Invariants / Constraints

`INV-HIST-01` (no erasure path), `INV-LED-01`…`05`, `INV-IDEM-01`, `INV-IDEM-04`, `INV-ACC-14`, ADR-0129 decision 7. A replica never
answers a decision.

## Follow-up

- Run E65 with E39 (`REHEARSAL_SCOPE=load REHEARSAL_LOAD_PROFILE=soak REHEARSAL_LOAD_DECISIONS=1`, PI-025), and record its readings in
  `OPERATIONS_RUNBOOK.md` §11.57 against this ADR's thresholds.
- The owner decides item 64, the report folds' bound. *(Decided 2026-11-13 at (i): an opening checkpoint for each locked month,
  built by `P16-REM-003`, its database cases run before it merges.)* *(Built 2026-11-15 by `P16-REM-003`, accounting `V017`: every
  report's opening - `LedgerTrialBalanceReads.before` and `ledger`, `LedgerCounterpartyPositionReads.before` - starts from the latest
  locked month's checkpoint still under the lock it was folded under and folds only the lines dated after that month; the proofs and
  the close's GL = ledger check stay on the whole fold. Two departures from item 64 (i)'s wording, neither changing what a read means.
  **The checkpoint is written after the lock commits**, by `AccountingCloseSchedule`'s openings leg (`OpeningCheckpoints`), not in the
  lock's transaction: that transaction holds the posting cutoff's key exclusively from its advance to its commit (ADR-0114), and a
  month's fold inside it would hold every posting in the platform behind it. What "written by the lock" was for still holds: a
  checkpoint is valid only while its month's row is not `OPEN` and its `locked_at` is the one it was folded under, and its insert reads
  that lock again. **It holds the journal's own classification** (purpose, owner kind, currency, scale, direction), not a pinned
  version's GL accounts: the mapping is applied on the read, so no version change can stale it, and the failure scenario "a mapping
  version change" cannot arise. Its database cases ran green on PostgreSQL 18.6 before any merge; E65 on the final tree stays owed under
  PI-025.)*
- Revisit decision 1 for the audit trail when a jurisdiction's retention period is decided (item 23 (b)), and for the idempotency records
  once their sweep exists. *(Corrected by `P16-DOC-001`, the Phase 16 exit review, 2026-11-16: the sweep exists since `P16-TSK-011` (platform `V016`); the revisit is E65's reading of `platform.idempotency_record`, owed under PI-025.)*
