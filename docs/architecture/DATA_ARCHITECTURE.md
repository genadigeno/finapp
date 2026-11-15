# Data Architecture

## Source-of-Truth Rules

Each authoritative state has one owner.

Preferred roles:
- PostgreSQL: transactional source of truth
- Ledger tables: authoritative financial postings
- Kafka: durable integration/event transport
- Redis: **not used by the application** (ADR-0030); `compose.yaml` runs one for local development and none is deployed
- Object storage: **not used by the application** (ADR-0036: documents and settlement files are held in PostgreSQL behind a store port); it serves infrastructure only
- Search index: derived query model
- Analytics warehouse/database: derived analytical model

## Schema Evolution

Schema change is governed by [ADR-0011](../adr/ADR-0011-forward-only-migrations.md) and the
conventions in [`DATA_MIGRATIONS.md`](DATA_MIGRATIONS.md). Migrations are forward-only, each
module owns its schema and its migration history, and a change to a financial table is
treated as a financial operation: history is not edited, corrections are new forward steps,
and evidence is preserved.

## Data Access

Authoritative writes and aggregate loads use **explicit SQL through `JdbcClient`** — no
object-relational mapper, no persistence context (ADR-0033). The reason is the privilege model
above rather than a preference: `INV-HIST-03`, `INV-HIST-01` and `INV-LED-03` are enforced by the
application role holding no `UPDATE` and no `DELETE`, which is only meaningful while nothing emits
a statement nobody wrote.

The unit of work is a JDBC `Connection`, transactions are begun explicitly, and
`NoObjectRelationalMapperTest` fails the build if an ORM artefact reaches the application's runtime
classpath. This decision governs **authoritative state only**; derived read models and reporting
projections are Phase 14's decision.

## Growth, Partitioning and Read Replicas

Decided by [ADR-0142](../adr/ADR-0142-no-table-partitioned-and-no-read-replica-routed-at-the-target.md) (`Proposed`, 2026-11-13,
`P16-TSK-012`). The figures, derived and read, are [`CAPACITY_MODEL.md`](CAPACITY_MODEL.md) §3.6; none is measured until E65 runs
(`POSTPONED_ISSUES.md` PI-025).

- **No table is partitioned.** A partitioned table's unique constraints must contain the partition key. Here those uniques are the
  once-ness arbiters (the idempotency claim, the inbox's dedupe row, the outbox's event identity, the posting scope) and the journal's
  integrity keys, and a time key would make each hold within a partition only. A partition can also be detached or dropped, which no
  history guard refuses. The journal is never deleted, so a partition buys it no retention. Its hot reads are by account over the whole
  history, which no time key prunes: ADR-0133's checkpoint and item 64's decision bound those reads instead (both built, 2026-11-15:
  ledger `V038`, accounting `V017`).
- **Retention is a batched, leaderless `DELETE`** (`OPERATIONS_RUNBOOK.md` §11.15). At the target the outbox's sweep has 2.7 times the
  capacity its writes need at the three-replica minimum, by derivation.
- **One primary serves every door.** Every decision, balance and once-ness check reads the primary under its lock, through the writer's
  pool. Report generation's own pool (`finapp_reporting`) is built on the same address. No read replica is routed, and the driver may
  take only a primary (`PrimaryOnlyGuard`). `OnePrimaryServesEveryDoorTest`, `ReportingPoolEndpointTest` and `PrimaryOnlyGuardTest` hold
  `PHASE_GATES.md` §5 Phase 16 criterion 6.
- **The only read a replica could ever serve is report generation**, by a later ADR. The reporting role alone would reach the replica,
  every run would state the replica's replay position, a FINAL run would be refused unless the replica had replayed past its period's lock,
  and a replica down would make reporting refuse while money moved on.
- **What would overturn either decision** is ADR-0142's list, read by E65 from the soak's samples. Any such reading becomes a new ADR
  before anything is built.

## Data Rules

- Use constraints to enforce important invariants.
- Use unique constraints for idempotency keys and external identifiers where appropriate.
- Version state transitions and externally material changes when reproducibility matters.
- Do not let projections become writable authorities.
- Sensitive data must be classified and protected appropriately. The scheme is
  [`DATA_CLASSIFICATION.md`](DATA_CLASSIFICATION.md) (ADR-0022): five levels, classified **per
  column at its ceiling** rather than at what the column holds today, because a column cannot be
  reclassified once it has data. `ColumnClassificationTest` fails the build if a migration adds a
  column the register does not classify.
