# ADR-0037 — Consent is an append-only history; the current basis is derived

Status: Accepted

Date: 2026-09-09

## Context

Consent is the lawful basis for processing, and `CLAUDE.md` §Domain Distinctions forbids
collapsing it with authentication or authorization — `INV-IDN-04` already states that neither is
ever treated as a lawful basis. Phase 2 makes consent real: KYC processing, screening and (in
Phase 10) bureau access each need a recorded, current, purpose-scoped basis.

The obvious model is a row per (party, purpose) with a `granted` boolean flipped on grant and
withdrawal. That model cannot answer the questions consent exists to answer: *was there a basis
on the day the processing happened*, *which version of the text was agreed to*, and *when was it
withdrawn* — because a flipped boolean has no history and an updated row has destroyed the
evidence.

## Decision

**A consent record is an immutable fact: a grant or a withdrawal, purpose-scoped, bound to the
version of the consent text it was given against, timestamped, and never edited or deleted.** The
current basis for a purpose is **derived** — the latest record for (party, purpose) decides — the
`P1-TSK-013` shape: there is no stored `status` free to disagree with the history that produced
it, and no sweep needed to keep one true.

Concretely:

- `consent.consent_record` is append-only at `DB-PRIVILEGE` (`INV-CNS-02`), the audit-table
  mechanism.
- Every record carries the **consent text version** (`INV-CNS-04`, which is `INV-HIST-04`'s rule
  applied to the artefact a person agreed to): a grant against v3 is not a grant against v4, and
  which versions require re-consent is a property of the version, not a guess.
- **Absence is refusal** (`INV-CNS-01`): the query answers "no current basis" for a purpose with
  no records exactly as for one whose latest record is a withdrawal — a missing row must never be
  distinguishable from a refusal by the caller, because a default-permit consent check is not a
  consent check.
- **Withdrawal is immediate on every instance** (`INV-CNS-03`): the check reads the table per
  decision, and no process-local cache of consent exists — `INV-IDN-03`'s reasoning, because an
  eventually-withdrawn consent is an unwithdrawn consent.
- Purposes are a closed enumeration in code (the `AuditableAction` shape): a free-string purpose
  is a vocabulary nobody controls and a check nobody can enumerate.

## Alternatives Considered

### Mutable (party, purpose) row with a status
Pros: one row, one obvious query.
Cons: destroys the evidence on every change; cannot date a basis retroactively; re-consent on a
text change becomes an untracked migration.

### Event-sourced consent through the outbox
Pros: history for free.
Cons: `INV-EVT-02` — events are transport, not truth; the authoritative basis would live in a
stream nothing may treat as authoritative.

### Chosen: append-only table, derived current state
Pros: history *is* the store; immutability at the strongest mechanism; the derivation is one
`ORDER BY ... LIMIT 1` per (party, purpose).
Cons: reads cost a scan of the pair's history — bounded by an index and by consent-check
frequency being per decision, not per request.

## Consequences

Positive: every consent question a regulator asks is a query over facts.
Negative: none material at Phase 2 volume.
Security impact: consent history is evidence — classified, immutable, audited on privileged read.
Financial impact: Phase 10's `INV-CRD-03` (bureau access requires recorded consent) inherits a
working mechanism instead of building one beside a credit bureau adapter.

## Invariants / Constraints

`INV-CNS-01`…`04`, `INV-IDN-04`, `INV-HIST-04`, `INV-EVT-02`.

## Follow-up

Consent expiry (a basis with a lifetime) is not modelled in Phase 2 — no purpose needs one yet;
the record's shape (a dated fact) admits it additively when one does.
