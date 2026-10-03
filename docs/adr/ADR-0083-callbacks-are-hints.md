# ADR-0083 — Callbacks are hints: an outbound money flow adopts its outcome only from an authenticated inquiry

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: Payments · FX · Security
Supersedes: nothing. Amends ADR-0047's pipeline for outbound money flows: its first three
steps stand unchanged, and a fourth replaces "apply the mapped edge" with "inquire, then
apply what the inquiry says". The doctrine going forward; `X-TSK-012` aligns the Phase 5 and
Phase 7 providers. Settles D25 and owner decision O5.

## Context

ADR-0047 made webhooks safe against duplication, reordering, staleness and replay:
authenticate over the raw bytes with a freshness window, retain evidence first, dedupe
through the inbox, then apply the mapped edge as a conditional transition. One threat
remained outside its model: **the webhook signing key itself being stolen**. A correctly
signed, fresh, novel, well-formed callback passes every one of ADR-0047's gates — and for
Phase 5's inbound flows that was an acceptable residual, because a forged "captured" is
caught by the money never arriving in settlement.

Phase 9 raises the stakes in both directions:

1. **Outbound flows credit nothing on a callback, but conclude on one.** A forged
   `Accepted{providerRef}` on an outbound credit would release a hold and post a completion
   entry — customer money moving on an attacker's say-so. A forged `Rejected` would fail a
   payment the provider executed, the double-spend window ADR-0057 closed for sweeps.
2. **The platform already owns the stronger channel.** Every outbound flow has an
   authenticated inquiry (`inquire(E)`, `inquire(T)`) over the outbound provider credential,
   which a webhook-key thief does not hold. The resolution sweeps already adopt outcomes from
   it exclusively (`INV-LIFE-03`); the callback path was the one door that adopted a pushed
   outcome directly.
3. **Two doctrines would otherwise coexist** — Phase 9 flows trusting inquiries only, Phase
   5/7 flows adopting verified callback outcomes — and a reader of one pipeline would draw
   wrong conclusions about the other (the judges' finding against a Phase-9-only rule).

## Decision

1. **For outbound money flows, a callback is a hint.** The pipeline is ADR-0047's, amended
   in its last step: **authenticate** (signature over the raw bytes, per-provider key,
   freshness window), **retain evidence** verbatim, **dedupe** through the inbox — and then,
   instead of adopting the callback's own outcome, **schedule an immediate authenticated
   inquiry** (`inquire(E)` for an outbound credit, `inquire(T)` for a cover) and adopt only
   what the inquiry answers, through the same `OutboundCreditOutcomes` / `FxCoverOutcomes`
   conditional transitions the resolution sweeps use. One applier, one arbitration, whatever
   the channel.

2. **The callback's content decides nothing; its arrival decides when.** A callback is a
   scheduling event: it collapses the sweep's polling interval to now. Its claimed outcome is
   retained as evidence (Phase 8 reconciles what was said) and may be counted, but no state
   edge, posting, hold release or permit renewal reads it.

3. **This is the doctrine going forward.** Every new provider integration — the FX provider's
   and corridor rail's webhooks in Phase 9, and any later one — is built as hint-then-inquire
   from birth. The contract batteries carry the planted test: a forged-but-signed callback
   moves nothing.

4. **`X-TSK-012` aligns the Phase 5 and Phase 7 providers** (the PSP and instant-rail
   pipelines), so one doctrine holds platform-wide. It is scheduled with Phase 15 (production
   hardening: security), with an explicit earlier trigger: a suspected webhook-key
   compromise, or a provider offering no inquiry. It is not done inside Phase 9 because it
   changes live Phase 5/7 money paths (one inquiry per callback) with their own negative
   tests and provider contract changes — and because, pending it, those flows keep ADR-0047's
   full protections; only the stolen-key residual remains, as it has since Phase 5.

5. **The cost is stated, and paid knowingly.** One provider call per callback, and outcome
   latency moves from "callback arrival" to "callback arrival + one inquiry round-trip". What
   it buys: a stolen webhook key can accelerate the platform's learning and nothing else —
   it cannot move money, conclude a payment, or create the `MISSING_EXTERNAL` /
   `UNKNOWN_EXTERNAL` wreckage of a forged conclusion. The security architecture
   (`PHASE_9_PLAN.md` §11) rests on this: "a forged-but-signed callback moves nothing unless the authenticated
   inquiry over the outbound credential confirms it".

## Alternatives Considered

### Keep ADR-0047 as is: adopt the verified callback's outcome
Pros:
- One fewer provider call; outcomes land at callback speed.
- Sufficient so far: no incident, and inbound flows are settlement-checked anyway.

Cons:
- For outbound flows the webhook key becomes a money-moving credential held by a third
  party's infrastructure. Key theft converts to hold releases, completion entries or forged
  failures.
- The sweep and the callback path would arbitrate the same edges from two authorities of
  different strength.

Refused for outbound money; the callback keeps every other role (evidence, dedupe, metering,
scheduling).

### A Phase-9-only rule, leaving Phase 5/7 untouched and unrecorded
Pros:
- No cross-cutting task; Phase 9 ships the same code either way.

Cons:
- Two doctrines with no recorded reconciliation: a reader of the PSP pipeline would wrongly
  assume the corridor's, and vice versa (the judges' defect).
- The stolen-key residual on Phase 5/7 would be an unrecorded accepted risk instead of a
  scheduled alignment with a trigger.

The doctrine is platform-wide; the alignment is `X-TSK-012`, owned and triggered (point 4).

### No webhooks at all for the new providers: sweep only
Pros:
- The attack surface disappears; no webhook keys to confine.

Cons:
- Outcome latency becomes the sweep interval even when the provider is eager to tell us; the
  customer watches `PROCESSING` for no reason.
- The evidence of what the provider pushed, when, is lost — Phase 8 wants what was said.

Hints keep the latency win and the evidence at near-zero risk.

### Inquire synchronously inside the webhook request
Pros:
- One round trip fewer than evidence-commit-then-inquire.

Cons:
- A transaction (or a held connection) would span a provider call, which ADR-0046 forbids;
  the webhook door must acknowledge on evidence + dedupe commit (ADR-0047 §5), not on a
  second provider's latency.

The inquiry runs after the ack, on the applier's own transaction discipline.

## Consequences

Positive:
- A stolen webhook key moves no money on any Phase 9 flow, by construction, with a planted
  test per provider battery.
- One applier per aggregate arbitrates every channel (callback-hinted inquiry, scheduled
  sweep inquiry), so ordering, duplication and race defects have one home.
- ADR-0047's evidence and dedupe discipline is reused byte for byte; nothing already built
  weakens.

Negative:
- One provider inquiry per callback: the corridor and FX providers see roughly double the
  read traffic on busy days (bounded by the inbox dedupe — a redelivered callback does not
  re-inquire past its dedupe record).
- Outcome latency gains one inquiry round-trip over Phase 5/7's callback adoption.
- Until `X-TSK-012` lands, the platform runs two recorded doctrines; the PSP and
  instant-rail pipelines keep the stolen-key residual they have carried since Phase 5, now
  explicit.

Operational impact: callback volume and the hinted-inquiry outcome land on the existing
provider latency and outcome meters; a provider whose inquiry lags its callbacks shows as
`received.age` / `unknown.age` growth, alerting.
Security impact: webhook keys (`FINAPP_FX_WEBHOOK_KEY`, `FINAPP_CORRIDOR_WEBHOOK_KEY`) remain
confined and rotated under the existing regime, but are no longer money-moving credentials;
the outbound provider credentials are the sole outcome authority; the planted
forged-callback test is part of every contract battery.
Financial impact: none directly — no posting path changes; the authority feeding the acting
conditionals narrows.

## Invariants / Constraints

`INV-LIFE-03` (an unknown outcome resolves only by knowledge — now the only adoption path
for pushed outcomes too), `INV-PAY-01` (webhook authentication and freshness, unchanged),
`INV-PAY-03` (provider vocabulary confined; the total mapping's default is indeterminate),
`INV-IDEM-04` (duplicate callbacks are harmless: inbox dedupe, and the inquiry's conditional
edges), `INV-HIST-02` (callback evidence retained verbatim, adopted or not), `INV-AUD-01`
(outcomes audited acting-only in the applier's transaction).

## Follow-up

- `P9-TSK-012` (cover outcomes), `-020` (outbound credit resolution) and `-023` (returns)
  build the hinted-inquiry path for the FX and corridor providers, each with the planted
  forged-callback test; the contract batteries carry "dedupe on our reference before
  validity" (FX) and "on `E`" (corridor).
- `X-TSK-012` (owner Phase 15; trigger: suspected key compromise, or a provider with no
  inquiry) aligns the Phase 5 and Phase 7 providers and retires the two-doctrine state.
- A real provider offering no inquiry endpoint would make the hint model unworkable for it:
  that discovery is `X-TSK-012`'s trigger and would need its own ADR.
- The Phase 9 review (`P9-DOC-001`) reads this ADR against the code before accepting it.
