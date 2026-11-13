# Data Retention

Written by `P15-TSK-029` (2026-11-08) under C12 (`PHASE_15_PLAN.md` §2.2). How long each class of data is kept, what decides
that, and what mechanism ends it. `RetentionPolicyIsCompleteTest` holds this document's shape.

**What this document is not.** It claims no compliance with any standard or law (`PHASE_15_PLAN.md` §1.1). No real jurisdiction
has been chosen, so every period below that a jurisdiction would set is **`OPEN`**, and nothing purges against an assumed one.
A purge built against an invented period is a defect waiting for one (C12).

**Three different questions**, and folding them together would answer each one wrongly:

- A **correctness bound**: too short admits a second effect. The inbox's dedupe record is the case.
- An **operational bound**: too short costs diagnosis or a replay. The published outbox row is the case.
- A **duty**: a jurisdiction's rule about how long a record must be kept and how it ends. Financial history, evidence,
  agreements, audit and regulatory reports are the cases.

---

## 1. The classes

| Class | Data | Period | What decides it | How it ends | State |
|---|---|---|---|---|---|
| **Operational** | Published outbox rows, `platform.outbox_event` | `finapp.outbox.retention`, default 30 days from publication, refused at start below the inbox's dedupe window. Longer for a row a standing parked record names | Diagnosis, an audited replay reading across that window, and a release republishing a parked record's event | The sweep, past the bound, published rows only | Built |
| **Operational** | Dedupe records, `platform.inbox_message` | `finapp.inbox.retention`, default 14 days from processing, and never less than the record's own written expiry | Every redelivery window (`DATA_MIGRATIONS.md` §9); it must exceed every topic's retention | The sweep, past both bounds | Built |
| **Operational** | Idempotency records, `platform.idempotency_record` | Kept: not swept | Debt row #61, ruled not settled: `journal_entry.idempotency_scope` is not `UNIQUE`, so the record is posting once-ness's second rank | A later sweep lands that uniqueness first; both Phase 16 | Not swept |
| **Operational** | Session rows, `identity.session` | A dead session's row: `finapp.identity.session.sweep.grace`, default 30 days past its idle bound, refused under a minute; a live session is never selected | Diagnosis of a reported takeover (which device, how strongly a person signed in), as the outbox's 30 days serve a replay; the logins' audit records are never swept. ADR-0030's caution, an early expiry logging a user out, cannot arise: the bound is the row's own idle bound on the database's clock, which a live session has not passed. Sized from the target's 240,000 sign-ins a day (`CAPACITY_MODEL.md` §3.1; carried at 2.81 a second in `P16-TSK-007`'s E30 step 3): a row lives from its sign-in to its idle bound (30 minutes to 12 hours later) and the grace, so about 7.3 million rows at the default, and one more for each step-up's rotation; a tick's twenty batches of 500 every five minutes deleting up to 2.9 million a day per instance | The session sweep (`RetentionSweepSchedule`, identity `V027`), `P16-TSK-010` | Built *(read "Kept: not swept … A sweep, Phase 16" until 2026-11-13)* |
| **Operational** | Parked records and discard decisions, `platform.inbox_parked_record` and `platform.dead_letter_discard` | Kept: never swept | A parked record is a message nobody has handled, and a discard is a recorded four-eyes decision | Nothing ends them | Kept for ever |
| **Operational** | Callback hints, `payments.callback_hint` | Kept: not swept | Operational state, never financial truth. A concluded row is safe to delete, and no volume yet asks for it | A sweep of concluded rows, when volume does | Not swept |
| **Operational** | The source-join census's records, `accounting.source_census_head`, `accounting.source_census_finding` | Kept: not swept | A finding is the record of an entry the census could not explain, `CONFIDENTIAL` by `DATA_CLASSIFICATION.md`, and the investigation may need it long after the page ends | Nothing ends it: a reviewed change would give it a period | Built |
| **Operational** | Abuse counts, `platform.window_counter` | A window's length, then `finapp.abuse.sweep.grace`; selected on the row's own window end, so no setting can delete a live window | Telling a door how many it has admitted, and nothing after the window ends. **Unlogged**: a crash or failover empties it, at the cost of one extra window's budget | The window-counter retention sweep (`RetentionSweepSchedule`, platform `V014`) | Built |
| **Operational** | Kafka topics | The catalogue's `retention.ms` (`infra/kafka/topics.yaml`) | Bounds diagnosis and replay, never truth; every topic stays below the inbox's dedupe window | The broker | Built |
| **Operational** | Logs, traces, metrics | By signal, set per environment by its backend. The reference on `kind` and in compose (`P15-TSK-023`): traces 7 days (Tempo's `block_retention`), logs 31 days (Loki's `retention_period`), metrics with Prometheus. A real environment's: Tempo and Loki in its own cluster on its own buckets, at 7 and 31 days unless a jurisdiction says otherwise (`THREAT_MODEL.md` section 6 item 37 (a), decided 2026-11-11, with the first real environment). **The logs' period is also the erasure's bound** for anything a log printed of a person: no log store is searched or purged for one person's erasure, which would need the person's identifiers in the logs to find them by, and a store per retention class follows only if a regulator asks for less (item 37 (e), decided 2026-11-12). *(Read "A real environment's backends, their readers and their periods are not chosen" until 2026-11-11.)* | Operations; a log or trace is never evidence (ADR-0019, `INV-AUD-02`: restricted data never reaches one) | The backend's own compactor | Per environment |
| **Financial** | Journal entries and lines, financial transactions, every domain record a balance is explained from (payments, transfers, settlement, loans, BNPL, FX) | Never deleted | `INV-HIST-01`: a correction is a reversal, an adjustment or a compensating entry | Nothing ends it. Any duty beyond this is `OPEN` | Kept for ever |
| **Evidence** | Provider statements and files: `payments.provider_evidence`, `payments.dispute_evidence`, `merchant.payout_evidence`, `fx.fx_provider_evidence`, `kyc.verification_evidence`, `settlement.file` and its chunks | `OPEN` | A real jurisdiction and each provider's contract | Purge not built; re-dated to that choice | Kept for ever |
| **Evidence** | Credit evidence: `credit.credit_evidence`, `retain_until` stored per row from the product's retention | `OPEN` beyond the stored date, and the scope is open too: whether a purge reaches the normalised attributes (`credit_record_attribute`) and each snapshot's canonical text, and how a purged decision then explains | A real jurisdiction | Purge not built; re-dated to that choice. Nothing deletes past `retain_until` today, and a trigger refuses `DELETE` for every role | Kept for ever |
| **Evidence** | Agreement and acceptance evidence: `lending.loan_agreement`, `lending.loan_acceptance`, `bnpl.bnpl_agreement`, `bnpl.bnpl_acceptance`, and a dispute's merchant statement | `OPEN` | A real jurisdiction | Purge not built; re-dated to that choice | Kept for ever |
| **Audit** | `platform.audit_record`, and its archive's own record: `platform.audit_archive_segment`, `platform.audit_archive_member`, `platform.audit_archive_pending`, `platform.audit_archive_head` | Never deleted. How long a copy must stay in the archive is `OPEN` | ADR-0010: archival must preserve auditability, so deletion is not an option. `INV-HIST-03` | Nothing ends it. Archived by copy (§3) | Archival built (`P16-TSK-010`, 2026-11-13), running where a store is named, which no committed environment does yet; deletion never *(read "Designed; the build is Phase 16's" until then)* |
| **Regulatory** | Regulatory reports: `risk.regulatory_report`, `risk.regulatory_report_event` | `OPEN` | A real reporting jurisdiction's record-keeping rule | Destruction schedule and legal hold re-dated to that choice | Kept for ever |

**`OPEN`** means: no value is set, nothing depends on one, and no code path deletes against one. To close a period, name the
jurisdiction, the rule and the owner in this table in the change that builds the purge.

**A table not named above is kept.** It takes its class from `DATA_CLASSIFICATION.md`, and nothing is deleted from it until a
reviewed change gives it a row here. Working rows that a process deletes as part of its own logic, such as the authentication
throttle's counters, are that process's state, and retention is not what bounds them.

---

## 2. The rules every sweep obeys

- **An allow-list of terminal states**, never "anything not in these states". The outbox selects `published_at IS NOT NULL`, so
  a pending row, an abandoned row and any state a later task adds stay. So does a published row that a parked record still names,
  unless that record has reached a state the sweep lists as finished. The inbox has no state column: every row is a handling
  that committed, and its sweep lists the columns it was reviewed against, so a new column stops it until a migration says
  which of its values are terminal. **An unknown state is never selected.**
- **A dedupe record is kept at least as long as a redelivery could match it.** The inbox's bound is the retention the consumer
  writes with, read from the consumer, and it has no setting of its own. An instance refuses to start with that retention at or
  below the oldest record the broker can redeliver (8 days and 5 minutes today), and the sweep's function refuses such a bound
  too. The sweep also waits for the row's `expires_at`, which a replay order extends, so a row a replay depends on stays.
- **Deletion has one path.** The application role holds no `DELETE` on either table. Two `SECURITY DEFINER` functions (platform
  `V012`) are the only way, and each deletes only what its predicate selects. *(2026-11-13: so for the window counter, platform
  `V014`, and the session table, identity `V027`, whose function selects only `ACTIVE` or `REVOKED` rows past their idle bound by the
  grace and stops at a column it was not reviewed against.)* A holder of the application's credential still holds
  `UPDATE` on the outbox, so it can back-date `published_at` and reach a published row. It cannot change an inbox record's
  `processed_at`, so a dedupe record younger than the floor stays out of its reach.
- **Batched and leaderless.** `FOR UPDATE SKIP LOCKED` in one short transaction per batch, so N instances delete disjoint
  batches and an interrupted sweep simply resumes (`DISTRIBUTED_EXECUTION.md` §3).
- **Nothing financial is ever selected** (`INV-HIST-01`). A sweep of any further class is a reviewed change to this document and
  to the migration that grants it.

---

## 3. Audit archival — as built

The archive bucket's write-once lock is configured (`infra/terraform/modules/object-storage`, never applied), with Object Lock on
from creation and its mode and period required inputs with no default, because the period below is `OPEN`. The archival itself
was built in Phase 16 (`P16-TSK-010`, 2026-11-13; ADR-0135; platform `V015`). It runs on an instance whose
`finapp.audit.archive.endpoint` names a store, and no committed environment names one yet: no real bucket exists, and the
test-only store is the `kind` experiments scope's alone (`OPERATIONS_RUNBOOK.md` §11.50). Until a store is named the archive's
queue only grows, and its gauges say how far. *(Until 2026-11-13 this section was the design, "Nothing here is built", and its
first step was "Partition first": partitioning is a separate decision, by measurement and an ADR, and the archival is built
without it, as the steps below say.)*

**No audit record is ever deleted.** Archival is a **copy** to write-once storage, verified before anything relies on it. The
operational table keeps every row; relieving its size is the partitioning's question and never a `DELETE`.

1. **Queue every row, by the database.** A trigger on `platform.audit_record` puts each new row's id on the archive's queue,
   `platform.audit_archive_pending`, in the transaction that writes the row; the rows written before it were queued by the
   migration. No cursor over ids or times: an id minted on an instance's clock, or a transaction that commits late, would let
   a cursor pass a row for good with no signal.
2. **Cut a segment.** A segment is up to `finapp.audit.archive.batch-size` queued rows, moved from the queue into
   `platform.audit_archive_member` in one transaction. A committed audit row never changes, so it is closed the moment it
   commits: no period has to end first. The member table's key puts each row in one segment, ever.
3. **Claim leaderlessly.** Every instance ticks; the claim takes the archive's head row with `SKIP LOCKED`, so one claims at a
   time and a contender does nothing. One segment is in flight at a time, under a lease on the database's clock: a crash lets
   the lease lapse, and any instance takes the segment over with a new holder token. Its objects' names are deterministic, so
   the second holder writes the same bytes again or finds them there.
4. **Export and seal, by the database.** The segment's file is the database's own text, one line per row in id order, a JSON
   array of every column in the table's order (the actor, target, reason, correlation and outcome among them, `INV-AUD-*`),
   timestamps in UTC to the microsecond, so the copy needs nothing from the live schema to be read. Its manifest records the
   segment, its row count, its id and time range and the file's digest, chained to the previous manifest's digest. An altered
   or missing archive is then detectable, not merely forbidden. A column added to the table stops the archive until a
   migration reviews it.
5. **Write once.** The file and the manifest go to the store with a write that succeeds only where no object is, and on the
   real bucket under its Object Lock, a separate key from the database's and no application delete permission. The lock's period
   is the `OPEN` period above. The application deletes nothing from the store, and holds no key of the archive's: encryption at
   rest is the bucket's.
6. **Verify, then record.** Read both objects back and compare them byte for byte with the database's text; only then mark the
   segment archived, with the digests of what was read back, which the database recomputes from the rows and refuses otherwise.
   A store that is down leaves the segment claimed and nothing lost; a copy that differs is never marked and stops the chain.
7. **Keep it queryable** (ADR-0010). The segment table indexes the files by id and time range, and the read path restores a
   segment to a scratch schema for a question: its objects checked against the recorded digests and the chain, each line read
   back into a row by `platform.audit_archive_record` (`OPERATIONS_RUNBOOK.md` §11.50). Queries remain the operational table's
   while it holds every row.

**What this leaves open:** the archive's retention (`OPEN`, `THREAT_MODEL.md` §6 item 23 (b)); the real bucket's request signing
and credential, and the read path's access control and its own audit record (item 57); the partition interval, by measurement
(`P16-TSK-012`). The archival's runs on `kind` are owed (`POSTPONED_ISSUES.md` PI-025).

---

## 4. Personal data and the end of evidence

Personal data inside evidence, agreement and regulatory records ends by **crypto-shredding by key version** (C12): a field
encrypted under a retired key version becomes unreadable when that version is destroyed, without touching an `INSERT`-only row
or its history. This is a design only. The versioned key rings exist since `P15-TSK-019`, and startup refuses a retired version
with rows under it (`KeyRingRetirementGuard`), so a shredding would have to change that rule deliberately, which nothing here does
(ADR-0123 decision 4, `Proposed`). *(Read "The versioned key rings are `P15-TSK-019`'s, not built yet" until 2026-11-09.)*
Destroying a version would be an audited act that waits on a period above, on a legal hold that does not yet exist, and on an
answer to what a decision that cited the evidence then explains.

---

## 5. Changing this document

A change to a period, a new class or a new sweep changes this document, the migration that grants the deletion, and
`DATA_MIGRATIONS.md` §8–9 in the same change, and it answers the ten-instance question. Closing an `OPEN` period names the
jurisdiction and its rule.
