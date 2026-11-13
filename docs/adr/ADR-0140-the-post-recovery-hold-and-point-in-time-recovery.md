# ADR-0140 — The post-recovery hold and point-in-time recovery: the role lock held through the window, the window acting as the application's role, a person's recorded release

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Platform · Settlement · Reconciliation · every module (recovered)
Rests on: ADR-0128 (decisions 3, 4 and 5); ADR-0123 (key rings); `OPERATIONS_RUNBOOK.md` §11.35 and §11.55; `THREAT_MODEL.md` §6
items 38, 44 (a) and 62; `PHASE_16_PLAN.md` §11.1.

## Context

ADR-0128 decided that after any restore the roles stay locked until the verification passes, that every file a provider delivered
after the restore's point is then delivered again and reconciliation re-run "with money movement still held", and that only a
person's recorded release lets money move. It left open how money is held while the window runs, given that the window writes (a
file received, parsed and accepted; matching decided; breaks raised), and what records the release. `PHASE_16_PLAN.md` §11.1 named
two candidates for the hold: a hold built on the `SchemaVersionGuard` pattern, or the role lock. It also decided that the local drill
recovers to an instant from archived WAL, which no local server did.

## Decision

1. **The hold is the role lock, held through the window.** The platform's three roles stay `NOLOGIN` from before the restore until
   the release: no replica can open a connection, so no door, schedule, relay or consumer moves anything, however many replicas are
   pointed at the database. Nothing new is built into the application for it, and it does not depend on the fleet being scaled to zero
   or on any replica reading a flag.
2. **The window runs as the administrator acting as the application's role.** One command, `RestoreReconciliation`, connects as the
   restore's administrator with `-c role=finapp_app` for its whole session, so every statement runs under the application's own grants,
   never the administrator's, while the role itself still cannot log in. It refuses to run unless it is acting as `finapp_app` and none
   of the three roles can log in. It delivers each provider file again through the operators' upload service (one person uploads,
   another attests) and the intake's legs, runs matching until idle and the time sweep once, and reports the breaks raised since the
   window's start, which are the recovery's findings for a person.
3. **The books are verified again after the window**, by the same verification, before any release.
4. **The release is a person's, recorded.** The roles are released in one transaction with the database's instant read. The record
   names the person, the reason, the instant and the SHA-256 of each report the release rests on. In a real recovery it belongs in the
   incident's record (§11.12); the drill writes it beside its artefacts. A record in the restored database's own audit trail is item
   62 (a)'s, for the owner.
5. **Point-in-time recovery, locally.** The drill's source archives every WAL segment, encrypted with its ciphertext's digest beside
   it, into a store of the run's own, from a base backup written into `openssl`. A recovery lays the base backup down before its server
   first starts, with the platform's roles refused in `pg_hba.conf`, recovers to an instant, is promoted, makes the roles `NOLOGIN`
   before any check, and is then compared, verified and gated as a logical restore is. A segment is read back only when its digest
   holds, and PostgreSQL's own refusal to end a recovery short of its target is what a gap or a damaged segment must produce.
6. **The evidence's keys are checked on every restore** (ADR-0128 decision 5): every column a key version is stamped in, the startup
   guard's own list, read against the rings the verification was started with.

## Alternatives Considered

### A hold flag read by the application (the `SchemaVersionGuard` pattern)
Pros: the roles could be released before the window, and the window could run through the application's own deployment.
Cons: a new row, a new guard on every money-moving path, schedule and consumer, and a migration, for a state that exists only after a
disaster; it holds only what reads it, so a path that forgot the flag would move money, while the role lock holds everything that
connects. Rejected for now: the role lock is already the first rule's enforcement.

### Release the roles and keep the fleet at zero replicas during the window
Pros: the window could run as `finapp_app` itself.
Cons: the hold would be an operator's scaling, not the database's; any process with the credential could move money. Rejected.

### Run the window as the administrator without acting as the application's role
Pros: simplest.
Cons: every statement would bypass the application's grants, so a defect the grants would refuse could write. Rejected.

## Consequences

Positive: one enforcement point, the database, from the restore to the release; the window exercises the application's real intake and
reconciliation code under the application's real grants; the release is a person's act with its evidence named.
Negative: the window needs a session that may act as `finapp_app` while that role is locked: on the local server the administrator, a
superuser; on a managed instance the master user's membership is unverified (item 62 (b)).
Operational impact: a recovery is a declared incident with three steps after the verification (the window, the books again, the
release), each written in §11.55.
Security impact: no new standing credential; the archive and the base backup are ciphertext at rest; a restore whose key store lost a
version is refused before anyone reads evidence it cannot decrypt.
Financial impact: no money moves on a restored database until the verification, the window and the second verification pass and a
person releases it.

## Invariants / Constraints

`INV-ACC-01`, `INV-ACC-06`, `INV-ACC-12`, `INV-BAL-02`, `INV-HIST-01` and `INV-REC-*` judged before and after the window; the first rule
of §11.35 never relaxed between the restore and the release.

## Follow-up

The drills are owed (`POSTPONED_ISSUES.md` PI-025: E59, E60, E61). Item 62 holds the release's record in the database's trail, the
window on a managed instance and the archive's integrity for the owner. The managed restore itself is item 38 (a)'s.
