# ADR-0147 — Security under scale and failure: a probe in every experiment, every check fails closed, four eyes and the audit trail read across each fault

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Platform · Identity · the API surface (`app`) · the `kind` rehearsal's experiments scope
Rests on: ADR-0030 and ADR-0031 (authentication and authorisation are the application's), ADR-0010 (the audit trail append-only by
grant), ADR-0127 (the failure-injection method), ADR-0137 (load shedding), ADR-0139 (the failover drill), ADR-0140 (the post-recovery
hold); `PHASE_16_PLAN.md` §18; `THREAT_MODEL.md` T1, T3, T10, T14, T18, T20.

## Context

`PHASE_16_PLAN.md` §18 lists eleven ways a security control could weaken while the platform is loaded, failing over, partly away or
recovered. Each control is the application's and reads PostgreSQL: the session and an operator's roles (one transaction), a merchant's
key, the per-source count, the four-eyes rules (a domain refusal and a `CHECK`), the refused act's record. The edge authenticates nothing
(`THREAT_MODEL.md` §7). So "no fail-open under degradation" is a claim about one thing: what each of those reads does when it cannot read.

Reading the code: the session interceptor and the merchant key's door let a storage failure propagate (a failure, never a pass and never
`401`), and the per-source door answers `503` when it cannot count (`SourceLimitInterceptorTest`); the shedding door only refuses
(ADR-0137); a refusal whose record cannot be written is answered as a failure (`RefusedActsTest`). Only `SessionEndpointDatabaseTest`
showed the first of these, in the database tier, which runs per task nowhere (`POSTPONED_ISSUES.md` PI-023). Nothing ran a protected
request against the platform while a fault was on. And one gap was found that no control covers: **a restore undoes the security record
after its point**, while the post-recovery gate (ADR-0140) reconciles only provider files.

## Decision

1. **Every check fails closed when its store is away, and is held so without a database.** `DegradedStorageFailsClosedTest` drives the
   real session interceptor and the real merchant key's door over a data source that has no connection to give and over a role read that
   fails mid-request: the store's failure is thrown as itself (answered `5xx` by the error contract), never as an `ApiException`; the unit
   of work rolls back; no refusal is recorded (none was decided); no session, merchant or actor is left; no handler is reached. A request
   presenting nothing is refused `401` without the database.
2. **Every experiment carries a security probe**, through `experiment_run`, around the experiment's own steps and never inside them:
   - held credentials (a customer's session, an elevated operator's, the merchant's key) taken fresh at the steady state;
   - the probe's own **control**: on every ready replica each held credential served `200` and each of four protected requests (no
     credential, or a forged one, at a customer's, an operator's and a merchant's route) refused `401`, or the run cannot go on;
   - from just before the fault to the recovery's end, every 5 s, **every application pod asked directly** from the edge's probe pod (a
     replica failing open alone behind the Service would be one answer in N);
   - judged after the recovery: a protected request answered `2xx` or `3xx` is **a fail-open observation** and fails the experiment, as
     does a held credential refused (a session lost), a fault in which no protected request was answered (nothing observed), or any ready
     replica after the recovery not serving the held credentials or not refusing the protected requests. The register's Stop field says
     so (`RESILIENCE_EXPERIMENTS.md` §1.1).
3. **Four eyes is tried across every fault.** Once a run, one identity holding both accounting roles (the second given on the run's own
   server, as the rehearsal's seed grants roles) proposes a GL mapping version, never activated. Its proposer's own activation is tried just
   after each fault is on and again after the recovery: never a `2xx`, `403 accounting.SelfApprovalRefused` after the recovery, every `403`
   recorded (counted in the trail), the plant still `PROPOSED`. In E05 the first try meets no primary and the second the promoted one.
4. **The audit trail is compared by digest across every fault**: every audit record read just before the fault present and unchanged after
   the recovery.
5. **The unlogged counter's cost across a failover is measured, not judged.** E05 reads the sign-in door's counts in one window before the
   fault and after the promotion: platform `V014` decided the counter is unlogged and accepted one fresh window per crash or failover.
6. **Two experiments written for a count, registered at ten**: E81, one source's burst at the sign-in door over every replica, the door at
   `application.yaml`'s own limit, exactly the limit admitted; E82, a rolling restart of the whole fleet, every new replica serving the
   sessions held from before it. E83, reserved, is unused.
7. **A restore's undoing of the security record is put to the owner** (`THREAT_MODEL.md` §6 item 69), recommended to be closed by a
   security window in the post-recovery gate; until decided, the incident record lists the acts after the restore's point and each is
   repeated through its door at the release (`OPERATIONS_RUNBOOK.md` §11.55).
8. **What §18 leaves to others is named**: key availability, backup confidentiality and the restore's row counts are the restore drills'
   (E59-E61, ADR-0140); secrets regenerated in a disaster recovery are `P16-TSK-025`'s; a WAF and DDoS service are designed-only
   (`THREAT_MODEL.md` §6 item 32 (b)), sized by `P16-TSK-015`.

## Alternatives Considered

### Probe through the edge only
It is the path a client takes, and it would show the edge's own refusals. It cannot tell a replica that failed open alone from one that did
not: the Service picks one of N per request. The edge carries no authentication of its own, so every check worth probing is the replica's.

### A separate security experiment rather than a probe in every experiment
One run to read. But the plan's row is "under any experiment", and a fault the security experiment did not include (a broker, a disk, a
black-holed hop) would never be probed.

### Plant the four-eyes act once, in E05 only
§18 names E05. A promoted standby is the case most likely to lose a rule, but a shed fleet, a slow database and a lost replica each answer
the same request differently; the plant costs two client calls an experiment.

### Fail an experiment on a held session answered `5xx`
A held session's lookup while the database is away is rightly a failure, and a failure is not a lost session. Only a refusal is.

## Consequences

Positive: §18's claims are carried by every experiment's verdict, not by one run's; a fail-open on one replica is seen; the restore's gap
is named and owned. Negative: each experiment takes two more client calls and a look every 5 s (seven requests a pod: seventy at ten);
an experiment whose fault takes the probe pod's own node away cannot observe and fails as unproven rather than passing; E81 rolls the fleet
twice. Operational impact: `OPERATIONS_RUNBOOK.md` §11.62, and security notes in §11.30, §11.40, §11.52 and §11.55. Security impact: the
subject; no control changed. Financial impact: none - the plant is a mapping proposal never activated, and every experiment still ends in
the census.

## Invariants / Constraints

`INV-IDN-03` (a revocation honoured on the next request, whatever replica), `INV-AUD-01` and `INV-AUD-04` (a refused self-approval
recorded; approver never the proposer), `INV-IDEM-*` across a shed (ADR-0137).

## Follow-up

- The runs, owed under `POSTPONED_ISSUES.md` PI-025: the probe in every experiment it rides (E01 and E05 at three first), E81 and E82 at ten.
- `THREAT_MODEL.md` §6 item 69: the owner's decision on the restore's security window.
