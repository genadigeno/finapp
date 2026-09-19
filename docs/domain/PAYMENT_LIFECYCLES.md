# Payment Lifecycles

Rewritten from a 28-line stub by the Phase 4 → 5 transition (2026-09-20), the
`LEDGER_MODEL.md` precedent: the document that names a phase's model is written before the
phase's first task, from the decisions in ADR-0045…ADR-0049, and corrected by the tasks that
implement it. Until Phase 5's first task lands, **nothing in this document is implemented**,
and every statement below is the decided design rather than a description of code.

Related: [ADR-0045](../adr/ADR-0045-payment-intent-and-attempt.md) (the two aggregates and
their machines) · [ADR-0046](../adr/ADR-0046-no-transaction-spans-a-provider-call.md) (the
dispatch discipline and `UNKNOWN`) · [ADR-0047](../adr/ADR-0047-webhook-ingestion.md)
(webhooks) · [ADR-0048](../adr/ADR-0048-authorization-is-not-a-posting.md) (the accounting
treatment) · [ADR-0049](../adr/ADR-0049-first-provider-simulated-card-psp.md) (the first
provider and its finality).

---

## 1. The concepts, kept apart

The `CLAUDE.md` §Domain Distinctions groups, made concrete for this platform. Every term is
also in [`GLOSSARY.md`](GLOSSARY.md); this section states how Phase 5 composes them.

| Concept | Is | Is not |
|---|---|---|
| **Payment Intent** | The customer-facing objective: *"fund my wallet with 25.00 GBP from instrument X."* One aggregate, one lifecycle, the thing a customer queries | An attempt, a provider interaction, or a transaction |
| **Payment Attempt** | One try at realising an intent against one provider. Owns every provider interaction and every provider reference | The customer's view; retriable state on the intent |
| **Payment Method** | A **tokenised** reference to an external instrument, owned by `paymentmethods` — the PCI boundary (`INV-PAY-02`) | A PAN, a stored card, or anything reconstructable into one |
| **Authorization** | The issuer's promise, held provider-side against the customer's external instrument. A payment-domain fact with **no ledger effect** (ADR-0048) | A posting, a hold on the wallet, or money received |
| **Capture** | The act that makes the money ours to credit. **The ledger's first touch**: one posting, debit PSP clearing, credit the customer wallet | Settlement (`INV-SET-01`) |
| **Refund** | A new, bounded, referencing operation returning captured value to the instrument (`INV-PAY-05`, `INV-REV-02`) | An edit, an unwind, or a reversal of history |
| **Provider Reference** | The identifier a provider knows an operation by — the join Phase 8 reconciles on | Our identifier; a substitute for our own idempotency reference |
| **PSP / Processor / Acquirer / Issuer / Network** | Parties in the external chain, behind the adapter. Phase 5 simulates one card-style PSP (ADR-0049) and speaks to it alone | Domain concepts; none of their vocabulary crosses the adapter (`INV-PAY-03`) |
| **Clearing / Settlement** | Later facts about the money's arrival, owned by Phase 8. The `PSP_CLEARING` account's balance **is** the captured-but-unsettled position | Anything Phase 5 records as complete |
| **Dispute / Chargeback** | Phase 7's lifecycle. Phase 5 preserves the evidence and identifiers they will need — the seam, nothing more | Phase 5 states |

## 2. The intent lifecycle (ADR-0045)

```
REQUIRES_CONFIRMATION ──> PROCESSING ──> SUCCEEDED
          │                    └───────> FAILED
          └──────────────────> CANCELLED
```

Five states, four edges, every state earned by a producer and durably observable —
ADR-0044's doctrine, applied to the machine it was written to precede:

- **`REQUIRES_CONFIRMATION`** — creation commits it. Durable because confirmation is a
  separate act, which is what gives `CANCELLED` a producer: the window between acceptance
  and execution that ADR-0043 closed for transfers genuinely exists here.
- **`PROCESSING`** — confirmation commits it, in the same transaction that dispatches the
  attempt. The state ADR-0044 refused for transfers is **earned** here: the outcome now
  belongs to a third party.
- **`SUCCEEDED`** — the attempt captured; the posting committed in the same transaction as
  this transition. **Stable with no outgoing edge**: refunds are their own bounded
  aggregate referencing the capture, so the intent never transitions again — refund state
  stored on the intent would be one fact in two places (the ADR-0044 argument; a view joins).
- **`FAILED`** — the attempt failed, with the mapped reason on the attempt. Terminal; a
  customer who still wants to pay creates a new intent.
- **`CANCELLED`** — the customer's own withdrawal, only from `REQUIRES_CONFIRMATION`.
  Terminal, nothing dispatched, nothing posted.

Deliberately absent, each with its reason (the ADR-0044 discipline): `REQUIRES_ACTION`
(3-D-Secure-style challenges — no simulated provider issues one yet; arrives with its
producer), `PARTIALLY_REFUNDED`/`REFUNDED` (derived from the refund rows, never stored
twice), `DISPUTED` (Phase 7's lifecycle, not an intent state).

## 3. The attempt lifecycle (ADR-0045, ADR-0046)

```
AUTH_DISPATCHED ──> AUTHORIZED ──> CAPTURE_DISPATCHED ──> CAPTURED
     │    │                             │      │
     │    └──> AUTH_UNKNOWN ──┐         │      └──> CAPTURE_UNKNOWN ──> CAPTURED
     │              │         │         │                   │
     └──> FAILED <──┘         └──> AUTHORIZED               └──> FAILED
```

Seven states: `AUTH_DISPATCHED`, `AUTH_UNKNOWN`, `AUTHORIZED`, `CAPTURE_DISPATCHED`,
`CAPTURE_UNKNOWN`, `CAPTURED`, `FAILED`.

- **The `*_DISPATCHED` states are durable on purpose** — the exact inversion of ADR-0044's
  refusal of `PROCESSING`. Each is committed *before* the provider is asked (ADR-0046), so a
  crash mid-call leaves a visible fact to resolve rather than an unknown nobody recorded.
  The stranded-`DISPATCHED` case Phase 2 left visible-by-design gets its sweeper here,
  because now there is money on it.
- **The `*_UNKNOWN` states are the honest answer** (`INV-LIFE-03`): a timeout, a 5xx, a
  malformed body, an unrecognised state or a transport failure after send. Two states
  rather than one generic `UNKNOWN`, so *what* is unknown is a property of the machine
  itself — the resolution query for "did the authorization happen?" and "did the capture
  happen?" are different questions with different edges out.
- **`AUTHORIZED`** records the issuer's promise: amount, provider references, provider-side
  expiry metadata. No ledger effect (ADR-0048).
- **`CAPTURED`** is stable with no outgoing edge — refunds reference it and bound
  themselves by it. Captured is **not settled** (`INV-SET-01`).
- **`FAILED`** carries the **mapped, enumerated** reason (`INV-PAY-03`: never the provider's
  own code — that lives in the retained evidence).
- A connection **refused before anything was sent** is knowledge, not ambiguity: the
  dispatch fails into `FAILED(PROVIDER_UNAVAILABLE)`. Anything after send is `*_UNKNOWN`.

Absent with reasons: `VOIDED` (abandoning an authorization has no producer in the top-up
flow — it arrives with checkout expiry, Phase 6), multi-attempt retry (the schema admits N
attempts per intent with a one-live partial index as the arbiter, but Phase 5 ships exactly
one attempt per intent: an automatic retry policy is a versioned artefact with nothing to
calibrate it, and routing is Phase 7's).

## 4. The refund lifecycle (ADR-0045, ADR-0048)

```
DISPATCHED ──> COMPLETED
    │  └─────> FAILED
    └──> UNKNOWN ──> {COMPLETED, FAILED}
```

A refund is its own aggregate, referencing the captured attempt, bounded by it
(`INV-PAY-05`) — the sum of non-`FAILED` refunds never exceeds the captured amount, under
concurrency, for every writer. **Dispatch places a Phase 3 hold on the customer wallet**
(availability judged inside the account lock — the funds being returned must not be spent
mid-flight), and the outcome releases it: completion releases-and-posts atomically, failure
releases with nothing posted. This is the hold-then-capture composition `P3-TSK-015`
recorded as owed to "the capturing flow — Phase 4/5", arriving.

## 5. The financial flows (ADR-0048)

For each operation: business operation → payment transition → ledger effect → provider
interaction → final state → reconciliation posture.

| Operation | Payment transition | Ledger effect | When |
|---|---|---|---|
| Intent create / confirm / cancel | §2 edges | **None** | — |
| Authorization | `AUTH_DISPATCHED → AUTHORIZED` | **None** — the issuer holds the customer's funds, not ours (ADR-0048) | — |
| Capture | `CAPTURE_DISPATCHED → CAPTURED`, intent → `SUCCEEDED` | **DR `PSP_CLEARING` (asset) / CR customer wallet (liability)** — one posting, in the outcome transaction, key `payment-capture:<attemptId>` | Outcome commit |
| Refund completion | Refund → `COMPLETED` | **DR customer wallet / CR `PSP_CLEARING`** + hold release, one transaction, key `payment-refund:<refundId>` | Outcome commit |
| Settlement | — (Phase 8) | Clearing → bank when settlement files arrive | Phase 8 |

The `PSP_CLEARING` operational account was seeded per currency by `P3-TSK-003`, before
anything could post to it. Its balance is continuously the **captured-but-unsettled
position** — the number Phase 8 reconciles against provider settlement, and the reason
capture and settlement must never be one state.

## 6. Provider state mapping (`INV-PAY-03`)

Every provider answer — synchronous response, query result, webhook — passes through a
**total** mapping into the machine's vocabulary. The default branch is indeterminate
(`*_UNKNOWN`), never success; the raw bytes are retained verbatim as evidence
(`INV-HIST-02`) whatever the mapping said. Provider vocabulary appears in exactly one
place: the retained evidence and the adapter that parsed it.

## 7. What resolves an `UNKNOWN` (ADR-0046, ADR-0047)

Three resolvers, all conditional, all idempotent, racing harmlessly:

1. **The reconciliation-by-query sweeper** — every instance polls for aged `*_DISPATCHED`
   and `*_UNKNOWN` rows, queries the provider by **our** idempotency reference
   (`INV-PAY-04`), and applies the outcome through the conditional transition. No lease and
   no leader: the query is read-only and the conditional transition's row count arbitrates,
   so concurrent sweepers are the normal case, not a hazard.
2. **A webhook** — authenticated (`INV-PAY-01`), deduplicated, applied through the same
   conditional edges. A webhook for an already-terminal attempt is evidence, never a
   transition.
3. **Nothing** — an `UNKNOWN` that stays unresolved is a published, aging, alertable fact
   (the unknown-state age meter), never silently expired into failure.
