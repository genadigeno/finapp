# ADR-0047 — Webhooks: authenticated, freshness-bounded, evidence-first, and idempotent by conditional transition

Status: Proposed
Date: 2026-09-20
Phase: 5
Context: Payments
Supersedes: nothing. The "webhook ingestion, signature verification and deduplication"
decision anticipated for Phase 5.

## Context

A webhook is a provider telling the platform, on the provider's own schedule, that money
moved. It is the most consequential machine-facing input the platform accepts — the message
that credits a wallet — and it arrives with every property `CLAUDE.md` §Failure Engineering
warns about: duplicated, late, out of order, before the synchronous response it reports on,
or never. `P2-TSK-011` built the platform's first signed callback door for KYC; this ADR
decides what carries over unchanged and what payments must add.

## Decision

1. **Authenticate before parsing, with a freshness window** (`INV-PAY-01`). Signature over
   the raw bytes, per-provider key, constant-time comparison, verified before any read —
   the `P2-TSK-011` discipline — **plus a signed timestamp with a bounded window**, which
   that task deliberately omitted and payments require: the simulated provider signs
   `timestamp + body` (the scheme real PSPs use), and a message outside the window is
   refused. The window is not the dedupe — it bounds how long a captured-and-replayed
   message stays *processable at all*, which matters once evidence rows and meters react
   even to duplicates. Unauthenticated and stale messages write **nothing**.

2. **Evidence first, always** (`INV-HIST-02`). An authenticated webhook's raw bytes are
   persisted verbatim — with the provider's event identifier, receipt instant and
   verification outcome — **before** any state is touched, in the same transaction as the
   dedupe record. A webhook that changes no state (a duplicate, a late report on a terminal
   attempt) still leaves its evidence: both are genuine provider statements Phase 8 will
   want.

3. **Dedupe on `(provider, provider event id)` through the platform inbox** (`INV-IDEM-04`,
   the `P0-TSK-021` mechanism): the dedupe record and the effect commit together. A
   contended record answers 409 unacknowledged so the provider redelivers — the inbox's own
   contract.

4. **Every state effect is a conditional transition — ordering is nobody's promise.** A
   webhook maps (through the total provider-state mapping, `INV-PAY-03`) to an edge of the
   attempt or refund machine and is applied only if that edge is currently legal. Out of
   order, duplicated-with-a-fresh-id, racing the synchronous response or the sweeper — all
   land on the same arbitration, and the losers are evidence rather than errors. A webhook
   reporting on an already-terminal operation is **evidence, never a transition**
   (`INV-LIFE-04`; the `P2-TSK-011` late-callback rule).

5. **Acknowledgment discipline**: 2xx only after evidence + dedupe commit. A webhook that is
   authentic but unparseable or unmappable to any known operation is **acknowledged with
   its evidence retained and a meter incremented** — redelivery adds nothing when the bytes
   are already held, and an alert, not a stalled provider queue, is the correct escalation.
   This is deliberately the opposite of the inbox's block-don't-skip rule for *internal*
   events, where a gap is undetectable; here the evidence row **is** the detection.

## Why

Each half answers a specific forgery or loss. Authentication answers *who says so* — a
wallet-crediting endpoint with a computable signature is an open door (`P2-TSK-011`'s
argument, with money). Evidence-first answers *what was actually said*, independently of
what the platform did about it — the raw material of every future dispute and
reconciliation. Inbox dedupe answers *how many times*, and conditional transitions answer
*in what order* by refusing to care: at-least-once delivery with no ordering guarantee is
the only honest model of a provider's webhook queue, so correctness must not depend on
either. The result is that webhook processing needs no state of its own beyond the evidence
and dedupe rows — every question is answered by the machines it drives.

## Consequences

- Per-provider webhook keys join the externalised-secret regime; the per-credential
  loopback-confinement generalisation the debt register has owed since `P2-TSK-011` fires
  here and is scheduled as its own task (`P5-TSK-002`) rather than a fifth hand-written
  copy.
- The webhook endpoint is the platform's second machine-facing surface; the `SIGNED_CALLBACK`
  ownership class covers its reads.
- Duplicate and unmappable webhook rates are meters (plan §15) — a rise in either is a
  provider integration defect or a probe, and both are invisible without counting.
- The replay-window bullet in `PHASE_GATES.md` §Phase 5 gets its subject.

## Alternatives rejected

- **Dedupe without evidence for duplicates** — discards a genuine provider statement;
  Phase 8 reconciles what was *said*, not what was novel.
- **Ordering by provider sequence/timestamps** — a promise providers do not make; encoding
  it makes correctness depend on another party's clock discipline.
- **Block-don't-skip for unparseable webhooks** — stalls the provider's queue to force
  redelivery of bytes already retained; alerting on retained evidence dominates.
- **Signature without a freshness window** — sufficient for Phase 2's idempotent
  case-completion, insufficient where replayed traffic moves meters, evidence and
  operational attention.

## Follow-ups

- `P5-TSK-012` implements ingestion; `P5-TSK-013` the conditional transitions and the
  out-of-order/duplicate/before-sync demonstrations; `P5-TSK-002` the credential
  confinement generalisation this consumes.
