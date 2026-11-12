# ADR-0128 — Recovery objectives and the post-recovery financial gate: RTO and RPO by tier, and no money moves until the books are proven

Status: Proposed
Date: 2026-11-12
Phase: 16
Context: Platform · Ledger · Reconciliation · every module (recovered)
Rests on: ADR-0005, ADR-0010, ADR-0120, ADR-0121, ADR-0123, ADR-0124; `OPERATIONS_RUNBOOK.md` §11.35 and §11.40;
`THREAT_MODEL.md` §6 items 21 (a), 38 and 44 (a); owner decisions C4 and C9 of `PHASE_16_PLAN.md` §2.2 (proposed, awaiting the
owner); `DELIVERY_PLAN.md` §Phase 16 §13; `PHASE_GATES.md` §5 Phase 16's third and fourth criteria.

## Context

The Terraform for the reference cloud declares a Multi-AZ database with a synchronous standby, automated backups kept for 7 to
35 days, and a daily copy to a vault in another account or region (`infra/terraform/modules/postgres/`, never applied). The
dedicated backup account was decided on 2026-11-09 (item 21 (a)). Locally, a logical backup and restore exists, together with
the platform's own verification (`infra/scripts/restore-rehearsal.sh`, `RestoreVerification`). Its first rule is that no money
moves on a restored database until the verification passes, and that rule is enforced by keeping the roles' logins locked
until then. There is no point-in-time restore anywhere local, no failover anywhere, and the lending and BNPL proofs have only
ever judged empty data. No recovery objective is written. The owner's brief forbids arbitrary values.

## Decision

1. **Recovery objectives by tier, each from its basis.** For each tier the basis weighs criticality (the journal is the
   financial record), financial risk (a lost acknowledged commit is money a customer was told moved), cost (a second region
   doubles the environment) and what the decided architecture can do. Tier 1, a database instance or zone lost: RPO 0, RTO 15
   minutes to money movement (synchronous standby). Tier 2, logical corruption or deletion: RPO at most 5 minutes before the
   bad write, RTO 4 hours (point-in-time restore, the verification, the reconciliation window). Tier 3, the production account
   lost: RPO 24 hours, RTO 24 hours (the backup account's daily copy, infrastructure rebuilt from code). Tier 4, a region lost:
   the same as tier 3 until cross-region replication is bought (ADR-0129). Kafka has no RPO of financial truth: it is rebuilt
   and refilled from the outbox inside the inbox's dedupe window.
2. **A failover is not a restore.** With a synchronous standby nothing acknowledged is lost, so readiness returning on every
   replica and the continuous correctness signals staying silent are the check (§11.40). A restore loses everything after its
   point by construction, and so is gated.
3. **The post-recovery gate.** After any restore, the roles stay locked until `RestoreVerification` passes: row counts, the
   catalogue, the schema, sequences, the trial balance per currency, GL equal to the ledger for every locked period, the
   projection check, every product proof, and the source-join census. Then every settlement file accepted after the restore
   point is re-ingested and reconciliation is re-run, with money movement still held. Only a person's recorded release, after
   both steps, lets money move. The verification runs over a source that holds every product, a loan and a BNPL obligation
   among them (item 44 (a)).
4. **Restoration is tested, not merely backup creation.** The local drills restore and verify: a logical restore, a
   point-in-time restore from archived WAL to a named instant before a planted bad write, and a cluster rebuilt from the
   repository and a backup. Each records its durations and its restore rate per GiB, so the RTO's restore term is a measured
   rate at the local size. The managed restores and failovers are designed only, until an account exists (C4).
5. **Keys are on the recovery's critical path.** A restored database is useful only with the key versions that encrypted its
   evidence. The drill proves that a restore with the same key ring reads the evidence, and that a restore with a version
   missing refuses it.

## Alternatives Considered

### One RTO and RPO for everything
Rejected: a zone's loss and a region's loss differ by orders of magnitude in what they cost to recover from.

### Tighter regional objectives now (cross-region replication)
A real option, priced at a second environment. It is the owner's to buy with a real environment (C10 (B)).

### Resume money movement as soon as the database is up
Rejected: a restore can silently drop the window after its point, and only the verification and the reconciliation find what
that window held.

## Consequences

Positive: every objective has a stated basis and an executable local analogue. Money never moves on books nobody has proven.
Negative: the local numbers describe the procedure at the local size, not a managed service.
Operational impact: a recovery is a declared incident with a release step taken by a person.
Security impact: backups and the WAL archive stay encrypted. The key-ring check makes key loss visible before it matters.
Financial impact: no money moves on a restored database until the trial balance, GL equal to the ledger, the proofs and the
reconciliation of the window pass.

## Invariants / Constraints

`INV-ACC-01`, `INV-ACC-06`, `INV-ACC-12`, `INV-BAL-02`, `INV-HIST-01` and `INV-REC-*` are judged before money moves after any
restore.

## Follow-up

`P16-TSK-022` runs the failover drill. `P16-TSK-024` runs the restore and point-in-time drills. `P16-TSK-025` writes the DR plan
and runs the DR exercise.
