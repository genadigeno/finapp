# ADR-0057 — The payout dispatches behind a send permit, fails only on what it knows, and resolves by query

Status: Accepted (2026-09-24, `P6-DOC-001` — read against the implementation at the phase review; its key variables renamed to the ones the configuration binds, and its bound given a floor, first)
Date: 2026-09-23
Phase: 6 (`P6-TSK-012`)
Context: Merchant (bounded context 12) · Ledger · Identity
Supersedes: nothing. Refines ADR-0051 §2, §3 and §5, `PHASE_6_PLAN.md` §4, §5, §8, §9 and §11,
and `CHECKOUT_MERCHANT_LIFECYCLES.md` §4.

## Context

ADR-0051 decided the payout's accounting: the payable is the budget, a hold makes in-flight
payouts count against it, completion releases-and-posts DEBIT `MERCHANT_PAYABLE` / CREDIT
`PAYOUT_CLEARING`, and ambiguity leaves the hold standing. It reused `P5-TSK-016`'s
two-transaction keyed command for the dispatch. Building it exposed questions ADR-0051 left
open, and one hazard no earlier flow had:

- **The payout is the first flow with BOTH a re-sending takeover and a sweeper that can
  conclude "never received".** The refund has a takeover but no sweep; the payment attempt has
  a sweep but no takeover. Together, without a guard, a sweep can query a crashed payout, find
  the provider has no record of it, fail it and release its hold — while a client's retry is
  re-sending the same reference, which the provider then pays. Money leaves; the books say it
  did not; the released hold lets the payable pay it out again.
- **A refused connection on a re-send is not a refused payout.** The first send may already
  have been paid.
- ADR-0051 §5's `REQUESTED` state, the operator's route and permission, the claim's tenancy,
  `PAYOUT_CLEARING`'s type, the evidence's protection, the webhook and the destination binding
  were each undecided.

## Decision

1. **Four states, not five.** `DISPATCHED → COMPLETED | FAILED | UNKNOWN`, `UNKNOWN → COMPLETED
   | FAILED`. ADR-0051 §2's own dispatch transaction judges the bound, places the hold and
   commits `DISPATCHED` atomically, so `REQUESTED` would be a state no committed row could ever
   hold — ADR-0044's every-state-has-a-producer rule refuses it. The refund, the same shape, has
   four too.
2. **`FAILED` records why**: `DECLINED` (the rail refused), `PROVIDER_UNAVAILABLE` (nothing was
   sent) or `NEVER_RECEIVED` (the provider has no record, past the bound in 4) — the payment
   attempt's vocabulary, on the row, so a failed payout is reconcilable without the audit trail.
3. **A refused connection fails a payout only on its FIRST send.** On a takeover's re-send it
   moves nothing: the first send may have paid, and releasing the hold then is an over-payout.
4. **The send permit.** `last_dispatched_at` is committed before every send of our reference —
   by the dispatch itself, and by a takeover's conditional renewal (only while the payout is
   `DISPATCHED` or `UNKNOWN`, forward only, refused by `V007` otherwise). The resolution sweep
   concludes `NEVER_RECEIVED` only when the latest permit is older than its dispatched bound,
   **re-judged on the row it locked**. Either the sweep's lock comes first — the payout fails,
   and the takeover's conditional renewal then matches no row, so nothing is sent — or the
   renewal comes first, and the sweep finds the permit too young to conclude anything. "Never
   received" becomes a claim about the future as well as the past. The premise, stated: the
   dispatched bound (`finapp.merchant.payout.sweeper.dispatched-age`, default `PT10M`) must
   exceed any plausible gap between a committed permit and its request reaching the provider,
   **plus the clock skew between instances** — the permit is stamped by the sending instance's
   clock and judged against the sweeping instance's, so a sweeper running ahead sees every
   permit older than it is. Ten minutes is orders of magnitude beyond NTP-disciplined skew; an
   unsynchronised host is an operational fault this bound does not survive. **A bound of zero
   is refused at construction** (`MerchantPayoutResolution`, the cooling-off's rule in ADR-0056
   §4): it would let the sweep hear "unknown reference" for a request still in flight. Only a
   negative bound was refused until the phase review, `P6-DOC-001`.
5. **The claim is per merchant.** Scope `merchant.payout:<merchantId>`, the key the client's —
   the owning principal in the scope, as ADR-0004 intends — so two merchants' identical keys are
   two claims. The payout row keeps the key as its `dispatch_key`, unique per merchant: the
   takeover's convergence target.
6. **An operator pays out over its own route and permission.** A handler cannot take a
   merchant's key and an operator's session both (ADR-0052's disjoint populations), so the
   plan's "merchant key (or operator)" is two routes over one command:
   `POST /v1/merchant/payouts` (the key) and `POST /v1/operator/merchants/{merchantId}/payouts`
   behind the new `MERCHANT_PAYOUT`, held by `LEDGER_OPERATOR` — money leaving the platform is
   the money-operating population's, beside `PAYMENT_REFUND`, and `MERCHANT_ADMINISTRATOR`
   still moves nothing. The operator's reason is required, audited and kept on the row. No
   step-up, `PAYMENT_REFUND`'s parity: what either route cannot choose is where the money goes.
7. **The destination is bound at dispatch, at every rank.** The dispatch reads the merchant's
   effective destination `FOR SHARE`, so a supersession's `FOR UPDATE` waits for the payout's
   commit; the row records the destination id (its version); a composite foreign key makes a
   payout to another merchant's destination unstorable, and an insert trigger one to a
   destination that is not `EFFECTIVE`. A re-send goes to the RECORDED destination, even if
   superseded since — it is the same payout.
8. **`PAYOUT_CLEARING` is a credit-normal `LIABILITY`**, one per supported currency, seeded by
   ledger `V012`: instructed and not yet settled is an obligation the platform still owes, and
   Phase 8's settlement will debit it against cash.
9. **The provider's answers are kept verbatim and encrypted** (AES-256-GCM, the plaintext's
   SHA-256, append-only) under their own key, `FINAPP_MERCHANT_PAYOUT_EVIDENCE_KEY`: untrusted
   bytes the platform does not control, which a real provider could enrich with an account
   holder's details. The provider's API key is its own too,
   `FINAPP_MERCHANT_PAYOUT_PROVIDER_KEY` — the credential regime the destination tokenisation
   deferred to the provider that moves money. *(These read `FINAPP_PAYOUT_*` until the phase
   review, `P6-DOC-001`, found that nothing binds them: the configuration reads
   `finapp.merchant.payout.*`, so setting the named variable changed nothing and a deployment off
   loopback refused to start while pointing at the wrong fix. The refusals and this text now
   name the variables that bind, and `ConfinedCredentialVariablesTest` keeps the pair together.)*
10. **The query sweep is the resolver; the webhook is deferred.** ADR-0051 §5 named "query and
    webhook through the established doors". No accept clause and no Phase 6 item needs the
    webhook, the query path is complete on its own (ADR-0046 §4), and a second provider door
    carries its own authentication, freshness and evidence surface. Recorded, not built.
11. **Suspension gates a payout on the merchant row's lock**: the dispatch reads the merchant
    `FOR UPDATE`, so a suspension and a payout serialise rather than race.
12. **Every resolver locks the payout first** — the synchronous answer, a takeover's re-send and
    the sweep alike — applies the answer through one shared `MerchantPayoutOutcomes`, audits and
    publishes only an acting transition, and requires the hold's release to have acted. The
    refund's weaknesses are not inherited: a losing resolver reporting the verdict's status
    instead of the row's, losers writing audit records, and a release whose result is ignored.

*(Adopted beyond the payout by `P7-TSK-008`: the wallet withdrawal (ADR-0062 §6) carries
§§1–5 and §12 onto the push rail — four states, the failure vocabulary, the first-send rule,
the send permit judged on the locked row, the per-principal claim scope and dispatch key, and
one shared outcomes component — with the withdrawal's own bound source: the rail's DECLARED
outcome deadline plus a configured margin in place of a flat dispatched age.)*

## Alternatives Considered

- **Let the sweep re-send instead of concluding `NEVER_RECEIVED`.** Removes the race by never
  deciding from absence, but turns a read-only resolver into a money-mover and sends payouts
  hours late. The permit keeps the sweep read-only and the vocabulary Phase 5's.
- **Never re-send on takeover; let the sweep resolve.** Simplest, but ADR-0051 chose the
  `P5-TSK-016` contract, which re-drives the wire with the stored reference.
- **A merchant-and-operator route.** Refused by the interceptor and the build (ADR-0052).
- **`MERCHANT_PAYOUT` in `MERCHANT_ADMINISTRATOR`.** Would make the counterparty
  administrators a money-moving population, which `RoleName` recorded they are not.
- **Plaintext evidence.** Cheaper, and one enriched answer from a real provider away from bank
  data at rest.

## Consequences

- The payable view gains `paidOut` — a payable debit in an entry that credits
  `PAYOUT_CLEARING` — through a ledger read that classifies several counterparty purposes in
  ONE statement, so the terms still sum to the position by construction. A payout in flight is
  a hold, not a posting, and appears in no term.
- Meters are `P6-TSK-013`'s (payout outcomes, the stuck-payout gauges): the rows they read —
  status, `last_dispatched_at` and the history table — exist now.
- `P6-TST-002`'s readings are reachable: `PAYOUT_CLEARING`'s delta against completed payouts,
  and no payout left `DISPATCHED`.

## Invariants / Constraints

**Protected here.** Each claim below has a probe that breaks it and a test that fails, recorded
in `MUTATION_TESTING.md` §2 (`P6-TSK-012`):

- `INV-MER-05` — the bound, judged under the payable's lock with every in-flight hold counted,
  and a completed payout posted, so the bound stays cumulative.
- `INV-MER-02` — no payable column; `paidOut` derived from the lines.
- `INV-MER-01` — the merchant in every statement a merchant can reach, the claim's scope
  included; one 404; a payout to another merchant's destination unstorable.
- `INV-LIFE-01…04` — the payout IS a money-moving operation, so its machine is claimed: born
  `DISPATCHED` for every writer, its edges refused at the aggregate and the trigger, terminal
  states terminal, and `UNKNOWN` never concluded from a re-send's refused connection or a young
  permit.
- `INV-PAY-04` — our reference committed before the wire and presented on every send and query.
- `INV-CON-01/-02` — the release belt (§12), and both halves of the send permit (§4): the
  takeover's conditional renewal and the resolver's row lock.
- `INV-BAL-04` — a completion releases its hold.
- `INV-SET-01` — `PAYOUT_CLEARING` a credit-normal liability: instructed is not settled.
- `INV-IDEM-01/-03` — the takeover converges; a replay answers its request's judgement; a key
  reused for a different amount is the distinct 409.
- `INV-AUD-01…03` — the operator's act recorded as the operator's; identifiers only in facts and
  records, and the evidence ciphertext at rest; the operator route behind its own permission.

**Relied on, not re-demonstrated here.** The payout uses these mechanisms unchanged, and their
owning tasks' rows stand: `INV-LED-01` (`PostingService`, and `V004`'s deferred balance trigger),
`INV-BAL-05` (the bound reads the ledger through `HoldService`, never the payable view), and
`INV-EVT-01` (every fact written to the outbox in the acting transaction).

## Follow-up

- The payout webhook, when a real provider supplies one.
- Payout returns (a bank refusing an instructed payout after the fact): Phase 8, a compensating
  DEBIT `PAYOUT_CLEARING` / CREDIT payable. *(Paid, as a decision, at the Phase 7 → 8 transition
  by ADR-0073, `Proposed`, built by `P8-TSK-019`. A return is a merchant fact of its own, born
  once from the payout provider's settlement evidence and applied by a leaderless worker. That
  worker posts exactly this compensating entry, with a four-eyes `TRANSFER_TO_ACCOUNT` as the
  fallback when it cannot apply. The payout itself stays `COMPLETED`: a return is a new
  operation, never an edge back (`INV-LIFE-04`).)*
- A real adapter must honour the idempotency-by-reference contract for at least the sweep's
  resolution horizon.
- *The Phase 6 → 7 transition*: §4's send permit brought to the refund (payments `V009`), the
  platform's other re-sending flow. And the payout's own `firstSend` is now judged against the
  **locked row's** permit — the one the flight stored — rather than the request's belief, which
  a takeover's renewal could have made stale: a first send's refused connection failed a payout
  a later permit had already re-sent
  (`MerchantPayoutDatabaseTest#aFirstSendsRefusedConnectionAfterARenewalMovesNothing`).
- *The Phase 8 → 9 transition*: the dispatch discipline — permits, `UNKNOWN`,
  knowledge-only conclusions, resolution by query, evidence — is reused by ADR-0077 (the
  FX cover) and ADR-0079 (the Outbound Credit: §4's states plus `RECEIVED`), both
  `Proposed`, each born under a **database-stamped** send permit; `X-TSK-013`, scheduled
  inside Phase 9, aligns this ADR's and the refund's permits to the same rule.
- *`X-TSK-013` (2026-10-07)*: **§4's skew premise is removed.** Every Phase 5–7 permit - the
  payout's, the withdrawal's, the refund's, the dispute response's and the pay-in initiation's -
  is stamped by the database: born `GREATEST(created_at, statement_timestamp())`, renewed
  `GREATEST(permit + 1 µs, statement_timestamp())`, and held by a trigger per table (payments
  `V028`, merchant `V009`) that re-stamps any forward write after the machine trigger refused a
  backward one. The sweeps' candidacy bounds and the `NEVER_RECEIVED` bound are read from the
  same clock (`DatabaseTime.now`, in the transaction that locks the row). The dispatched bound
  must still exceed a committed permit's flight to the provider; it no longer has to exceed any
  instance's skew. The withdrawal and payout renewals' conditional moved from "not after my
  clock" to "the permit I read". Proven by one ±5 s skew race per flow and the build rule
  `SendPermitsAreTheDatabasesTest`.
