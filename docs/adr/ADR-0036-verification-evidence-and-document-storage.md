# ADR-0036 — Verification evidence verbatim in PostgreSQL; object storage deferred

Status: Accepted

Date: 2026-09-09

## Context

`INV-HIST-02` requires raw external payloads — provider responses, screening results — to be
retained unmodified, with a checksum where the source is a file. Phase 2 is the first phase with
such payloads, and it also captures **documents**: images of identity papers, the most sensitive
bytes the platform will hold before card data. `DELIVERY_PLAN.md` §Phase 2.6 sketched "Document
reference (object storage, encrypted)" — but no object store exists: `compose.yaml` runs
PostgreSQL, Kafka and Redis, and adding a fourth infrastructure component for a phase with
simulated providers and zero volume is a topology decision taken for a load that is not there.

## Decision

**Provider evidence and document content are stored in PostgreSQL, in the `kyc` schema, encrypted
where classified `RESTRICTED-PII`, with a SHA-256 checksum recorded beside every stored payload.**
Object storage is deferred with a named trigger, and the seam is preserved: documents are reached
only through a `DocumentStore` port, so relocating the bytes later is an adapter change plus a
data migration, not a domain change.

Concretely:

- Evidence rows are **append-only at `DB-PRIVILEGE`** (`INV-HIST-02`, the audit-table mechanism
  from `P0-TSK-022`): the application role holds `INSERT` and `SELECT` on evidence and document
  tables, never `UPDATE` or `DELETE`.
- Document content is encrypted with AES-256-GCM under a key held outside the database — the
  `INV-IDN-08` mechanism (`SecretCipher`), reused rather than reinvented, with its own key
  (`FINAPP_DOC_KEY`), because one key per concern is what makes later rotation per concern
  possible.
- Every read of document content is audited (`INV-KYC-06`); the audited unit is the access, not
  the session.
- Documents are size- and type-constrained at the boundary; the checksum is computed on the bytes
  received and verified on read, so silent corruption is a detected failure rather than a
  mystery.

## Alternatives Considered

### Object storage (MinIO locally, S3 in deployment) now
Pros: the shape production would use; keeps large bytes out of the relational store.
Cons: a fourth pinned infrastructure component, pre-signed-URL machinery, and a second
access-control system to audit — all for simulated documents measured in kilobytes. The
platform's own precedent (Redis with no client, ADR-0030's rejection of it as an authority) is
that infrastructure arrives with its first real requirement.

### References only, content nowhere
Pros: no sensitive bytes held.
Cons: fails `INV-HIST-02` — a decision must be defensible from retained evidence, and a
reference to bytes nobody holds is not evidence.

### Chosen: PostgreSQL with the port as the seam
Pros: one store, one privilege model, one backup story; append-only enforceable today at
`DB-PRIVILEGE`; encryption mechanism already exists and is tested.
Cons: bulk bytes in the relational store — bounded by size limits and by Phase 2's volume being
test traffic. Trigger to revisit: document volume becoming operationally material, or Phase 5's
evidence retention arriving (its provider payloads are higher-volume).

## Consequences

Positive: `INV-HIST-02` enforceable at the strongest available mechanism on day one.
Negative: a future migration moves bytes out of PostgreSQL; accepted, and the port bounds it.
Security impact: a new externalised key, guarded like the MFA key (published local default
confined to loopback).
Operational impact: table growth is a debt-register concern with the existing retention rows.

## Invariants / Constraints

`INV-HIST-02`, `INV-KYC-01`, `INV-KYC-06`, ADR-0022 (classification at the ceiling), ADR-0023.

## Follow-up

The object-storage trigger above; Phase 15 owns retention and the regulatory deletion question
for documents (deletion of PII vs immutability of evidence — a tension recorded, not resolved
here).
