# ADR-0010 — The audit trail is a first-class append-only store, distinct from logs

Status: Accepted

Date: 2026-08-31

## Context

`CLAUDE.md` states plainly: application logs are not automatically a regulatory-grade audit
trail. It also requires that important financial and administrative actions be auditable with
actor, time, operation, target, reason where applicable, correlation and outcome.

Application logs fail as an audit trail for structural reasons, not incidental ones: they are
retained for weeks rather than years, they are sampled and dropped under load, their format
changes freely, they are not queryable as records, and — decisively — they are mutable and
usually writable by the process that produces them.

The decision must be made in Phase 0, because audit records cannot be created retroactively.
An action that occurred before the audit trail existed is permanently unauditable.

## Decision

**A dedicated, append-only audit store, separate from application logging.**

- Audit records are rows in a dedicated table, not log lines.
- Every record carries: actor, actor type, occurred-at, operation, target type and id, reason
  (where applicable), correlation id, outcome, and a before/after summary where materially
  useful.
- **Append-only at the database privilege level**: the application role holds `INSERT` and
  `SELECT` only. `UPDATE` and `DELETE` are denied by grant, and this is proven by test.
- The audit write commits in the **same transaction** as the action it records. An action
  that succeeds without its audit record is not permitted.
- An **auditable-action registry** enumerates the action types that must be audited; Phase 15
  verifies completeness against it.
- Audit records contain no credential material, no PAN and no unnecessary PII
  (`INV-AUD-02`) — they record *that* an action occurred and by whom, not sensitive payloads.
- An `ActorId`/`ActorType` abstraction is introduced in Phase 0 and populated by a system
  actor until Phase 1 supplies real identity. The schema does not change when identity
  arrives.
- Application logging continues to exist for debugging and operations. It is not the audit
  trail and is never relied upon as one.

## Alternatives Considered

### Option A — Structured application logs as the audit trail
Pros: Already exists; no schema; centralised tooling.
Cons: Mutable, sampled, dropped under load, short-retention, schema-unstable, not
transactional with the action. Fails every property an audit trail needs. Explicitly rejected
by `CLAUDE.md`.

### Option B — Audit via domain events on Kafka
Pros: Already flowing; decoupled; naturally append-only within retention.
Cons: Kafka retention is finite and configured for transport, not archival. Delivery is at
least once, so audit records can duplicate. Not transactional with the action, so a crash
loses the record. `CLAUDE.md` rule 12 — Kafka is not financial truth, and audit is closer to
financial truth than to transport.

### Option C — Database triggers capturing row changes
Pros: Automatic; hard to bypass; captures everything.
Cons: Records *data* changes, not *business actions* — it cannot express intent, actor
context or reason codes. "Row updated" is not "reviewer approved KYC case with reason X".
Triggers are also invisible to application-level reasoning and hard to test.

### Option D — Dedicated append-only audit store (chosen)
Pros: Transactional with the action, so it cannot be lost. Immutable by database privilege,
not by convention. Records business intent, not row diffs. Queryable as records with
long-term retention. Directly satisfies the `CLAUDE.md` requirement.
Cons: Requires explicit instrumentation at each auditable action — omission is possible,
which is why the registry and Phase 15 verification exist. Table growth needs a retention and
archival strategy that preserves auditability.

## Consequences

Positive:
- Every privileged and financial action is attributable and defensible.
- Audit completeness is verifiable against an explicit registry rather than assumed.
- Immutability is enforced by the database, protecting history from both bugs and insiders.

Negative:
- Instrumentation is explicit and can be forgotten; mitigated by the registry, review, and
  the Phase 15 gate check.
- Audit volume grows with transaction volume; archival must preserve auditability, so
  deletion is not an option.
- Audit writes add cost to the action's transaction. This is accepted deliberately.

Operational impact: Audit table growth is monitored; archival is a Phase 15 deliverable that
must retain queryability.

Security impact: This is a security control in its own right. The application cannot alter
its own audit trail. Privileged-action review depends on it.

Financial impact: `INV-LED-05` requires every posting to be attributable. The audit trail is
how a posting, an adjustment or a break resolution is defended months later.

## Invariants / Constraints

`INV-AUD-01`, `INV-AUD-02`, `INV-AUD-03`, `INV-AUD-04`, `INV-HIST-03`, `INV-LED-05`.

## Follow-up

- Phase 0: schema, writer, privilege enforcement, registry, immutability test.
- Phase 1: real actor populated; authentication and authorization actions audited.
- Phase 3/8/14: four-eyes actions record both initiator and approver. *(Phase 3 kept the
  promise by dissolving its anticipated mechanism rather than paying it — `P3-TSK-021`: a
  four-eyes action is TWO acts, each with one actor — `ledger.AdjustmentProposed` names the
  initiator with the justification, `ledger.AdjustmentPosted` names the approver — so no
  record needs a second actor column, and the schema anticipated here was never added. The
  pairing lives on the proposal row, one join away, which is where later phases' four-eyes
  actions should look before adding columns.)*
- Phase 15: completeness verification against the registry; retention and archival that
  preserves auditability.

## Read-note: the carried items' ruling (2026-11-08)

C15 (`PHASE_15_PLAN.md` §25), applied by `P15-TSK-002`, places this record's Phase 15 follow-ups. Completeness verification against the registry belongs to Phase 15's audit-completeness criterion, owned by `P15-TSK-021` and `P15-TSK-028`. The retention policy and the archival design to write-once storage belong to `P15-TSK-029`, and the archive bucket's write-once lock to `P15-TSK-015`. **Building** the archival is re-dated to Phase 16's partitioning and archival of high-volume tables, so read "archival is a Phase 15 deliverable" as its design only. No audit record is ever deleted (`CURRENT_STATE.md` §Known Architectural Debt, "Audit retention and archival"). The rows carry the disposition, marked **C15** (`P15-TSK-002`, 2026-11-08); this record's decision and status are unchanged.

## Read-note: the archival design (2026-11-08)

`P15-TSK-029` wrote the retention policy and the archival design this record's follow-up asked for, in [`DATA_RETENTION.md`](../architecture/DATA_RETENTION.md) §3: archival is a verified **copy** to write-once storage, never a deletion, so the operational table keeps every row and queryability survives. Nothing of it is built, and the archive's retention stays open until a real jurisdiction is chosen. This record's decision and status are unchanged.
