# ADR-0129 — Availability and the multi-region stance: one writer, multi-AZ active-passive, no active-active writes, financial-operation availability stated apart

Status: Proposed
Date: 2026-11-12
Phase: 16
Context: Platform · Ledger · every module (availability)
Rests on: ADR-0001, ADR-0005, ADR-0009, ADR-0014, ADR-0016, ADR-0041, ADR-0120, ADR-0121, ADR-0124, ADR-0128; owner decision
C10 of `PHASE_16_PLAN.md` §2.2 (proposed, awaiting the owner); `PHASE_GATES.md` §5 Phase 16's sixth criterion.

## Context

Every arbiter of the platform is a row or a constraint in one PostgreSQL. The Terraform declares that database in one region,
Multi-AZ, with a synchronous standby. Kafka is not truth. The owner's brief asks for an assessment of active-passive against
active-active, of split-brain, and of which services, stores and workflows suit each model, and it says active-active writes
must not be introduced casually. It also asks that service availability be kept apart from the availability of a money-moving
operation.

## Decision

1. **One authoritative writer.** The journal and every arbiter live in one primary. Write capacity scales vertically and by
   shortening hot-row hold times (ADR-0041's sub-accounts after measurement). It never scales by a second writer.
2. **One region, multiple zones, active-passive.** Within the region a synchronous standby takes over (ADR-0128 tier 1). Another
   region is a recovery site, restored from backups. A warm asynchronous replica there is an option the owner may buy.
3. **No active-active writes.** Two regions accepting postings against the same accounts would need conflict resolution on
   money, which no invariant admits.
4. **Split-brain refused by construction.** A recovery region is promoted only by a person's recorded act after the first
   region is fenced: its database stopped or its credentials revoked. A health check never promotes it.
5. **Per store and per workflow** (`PHASE_16_PLAN.md` §12's table):
   - PostgreSQL has a single writer and a recovery site.
   - `finapp-app` and the migrator follow the writer's region.
   - Kafka exists per region and is never mirrored as truth.
   - Backups and key material are made available in the recovery region before they are needed.
   - Customer reads come from the active region only.
   - Settlement and reconciliation run in the active region, and the window is re-ingested after a recovery.
6. **Financial-operation availability is stated apart from service availability.** A matrix per operation and per failure says
   which money-moving operations must be refused while a dependency is degraded, so as to keep correctness, and which stay
   available. A refusal is open (`503`, `UNKNOWN`) and never a guess.
7. **Read replicas never serve an authoritative financial read.** A decision reads the primary under its lock. If a replica is
   introduced, it serves the reporting pool alone (`finapp_reporting`, `SELECT` only). A static guard holds that no
   money-moving door reaches that pool, and a report from a replica carries the replica's replay position.

## Alternatives Considered

### Active-active with conflict resolution
Rejected: it makes money's correctness depend on a merge.

### Active-passive with a replicated warm standby region now
A credible option. It tightens ADR-0128's tier 4 to the replica's lag, at the price of a second environment and of key and
secret replication. It is the owner's to buy (C10 (B)).

### Partitioning customers by region (each region a writer for its own customers)
Rejected for now: cross-region movements would become distributed transactions between two ledgers, a programme of its own.

## Consequences

Positive: one source of financial truth, and no merge on money.
Negative: a region's loss is a recovery measured in hours, and write capacity is one machine's.
Operational impact: regional promotion is a person's act with fencing first.
Security impact: key material and secrets must be present in the recovery region, which is a designed procedure.
Financial impact: none on the books. Availability gives way to correctness wherever the two conflict.

## Invariants / Constraints

`INV-LED-*` and `INV-BAL-*`: one writer of the journal. No path reads a replica to decide about money.

## Follow-up

`P16-TSK-012` decides the replica question and its guard. `P16-TSK-023` writes the availability matrix. `P16-TSK-026` writes the
assessment and the Terraform variants (never applied).
