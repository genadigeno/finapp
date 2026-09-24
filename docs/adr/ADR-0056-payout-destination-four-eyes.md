# ADR-0056 — A payout destination changes by two operators, a conditional step-up and a cancellable cooling-off; bank details never enter

Status: Accepted (2026-09-24, `P6-DOC-001` — read against the implementation at the phase review; two passages corrected to it first)
Date: 2026-09-23
Phase: 6 (`P6-TSK-011`)
Context: Merchant (bounded context 12) · Identity · Payments boundary
Supersedes: nothing. Implements `INV-AUD-04` for its second subject; refines `PHASE_6_PLAN.md`
§4, §9 and §11 and `CHECKOUT_MERCHANT_LIFECYCLES.md` §6.

## Context

A payout destination is where the platform sends a merchant's money. Changing it is the most
profitable single act available to an attacker, so it is the high-consequence action
`INV-AUD-04` names. Three different threats need three different controls:

- a **single insider** — one operator redirecting money alone;
- a **stolen session** — an operator's login used by someone else;
- a **change that gets through both** — which must not pay out before anyone can notice.

The Phase 6 plan fixed the shape (propose → approve by a distinct actor → effective after a
cooling-off) and left one decision to this task's design: whether the two actors are two
operators or merchant-then-operator (§11). It also required destination details "tokenised/
masked at the ceiling" without saying how.

The platform already has one four-eyes implementation: `P3-TSK-021`'s manual adjustment
proposals (ledger `V010`) — approver ≠ initiator as a `CHECK`, a frozen payload, lock-then-look
approval. The catalogue's note calling the payout destination "the first implemented subject"
was inaccurate, and is corrected with this ADR.

## Decision

1. **Two distinct operators propose and approve — not merchant-then-operator.** In Phase 6 a
   merchant has only machine API keys (ADR-0052): no human user, no factor, and merchant
   self-service users are out of the phase (`PHASE_6_PLAN.md` §17). If a key could propose, a
   leaked server credential would be one operator's click away from redirecting money, and
   "step-up on both sides" would be impossible on the merchant's. So a merchant key reaches no
   destination route at all; the merchant's instruction to change arrives out of band, and the
   platform's controls act on the operators who carry it out.

2. **Permissions: precise vocabulary, coarse bundling.** Propose and withdraw are
   `MERCHANT_ADMINISTER`; approve and reject are a new `PAYOUT_DESTINATION_APPROVE` (the plan's
   `PAYOUT_APPROVE`, named for what it approves — payouts themselves have no approval step).
   `MERCHANT_ADMINISTRATOR` holds both today. **Four-eyes is distinct identities, not distinct
   permissions** — `P3-TSK-021`'s shape — so the approval statement refuses the proposer whatever
   roles they hold.

3. **The step-up is `P4-TSK-007`'s conditional, on both sides.** The proposer and the approver
   each need a `MULTI_FACTOR` session exactly when they have an active factor, decided by an
   authoritative read in the writing transaction. Rejecting and withdrawing need none: stopping a
   change is the fail-safe direction.

4. **The cooling-off is pinned, enforced by the schema, and cancellable.**
   - `finapp.merchant.payout-destination.cooling-off`, default `PT72H`, must be positive — a
     zero is the control switched off, so a misconfiguration fails the context.
   - Each approval stores its own deadline (`cooling_off_until = approved_at + cooling-off`), so
     a later configuration change never alters an approval already given.
   - `V006`'s `CHECK (effective_at >= cooling_off_until)` makes an early effect unrepresentable
     for every writer.
   - **`APPROVED → WITHDRAWN` exists, and is the point**: a cooling-off nothing can act on is a
     delay, not a control.

5. **`EFFECTIVE` is a state with a producer, not a query filter.** A leaderless effectuation
   sweep (ADR-0024's idempotent-per-period half, the checkout expiry sweeper's shape) makes a due
   approval effective and supersedes the previous destination **in the same transaction** —
   supersession first, because the one-effective index is checked per statement. Under MVCC a
   plain reader sees either the old destination or the new one, never neither. **The
   dispatch's locking read can see neither, once**: if the sweep has locked the old row, the
   dispatch waits, re-checks that row - now `SUPERSEDED` - and cannot see the new one, which
   its statement's snapshot still holds `APPROVED`. The payout is refused as
   `merchant.NoEffectiveDestination`, everything rolls back and the key is unspent, so a retry
   a moment later pays to the new destination. Fail-safe, and found by reading the code against
   PostgreSQL's locking rules at the phase review (`P6-DOC-001`) rather than by a test.

6. **One open change and one `EFFECTIVE` destination per merchant**, each a partial unique
   index. One open change keeps the cooling-off's meaning single: there is never a question of
   which pending change would win.

7. **Bank details never enter the platform.** The operator's client obtains a grant from the
   payout provider for the merchant's account; the platform exchanges it — holding no database
   connection — for an opaque reference and a four-character display suffix, and stores only
   those. The grant and reference are `Sensitive`-wrapped (`secretsAreWrapped`), their
   `expose()` sites registered, and values shaped like a domestic account number or an
   international account are refused at the surface, in the domain types and by `V006`'s
   `CHECK`s. `iban` and `accountnumber` join the wrapping rule's vocabulary. The `payment_method`
   precedent (`INV-PAY-02`'s mechanism), restated for bank data.

8. **A refused self-approval is a committed, audited act.** The approval transaction commits
   with only the `DENIED` record and the proposal untouched; the `409` is raised after the
   commit, so the refusal keeps its evidence (`INV-AUD-03`).

9. **The dispatch reads the effective destination in its own transaction**, `FOR SHARE`
   (`PayoutDestinationStore#findEffectiveForShare`, ADR-0057 §7), so a supersession cannot
   commit under it. *(This named `PayoutDestinations.effectiveFor` until the phase review,
   `P6-DOC-001`: that is the operator list's plain read.)* Every change is its own immutable row, so the id a payout
   records is the destination version it was sent to (`P6-TSK-012`'s inherited acceptance).

## Alternatives Considered

### Merchant proposes, operator approves
Pros: the merchant knows its own account, and the operator never types bank details.
Cons: in Phase 6 the merchant is a machine key — the credential most likely to leak, with no
factor to step up. It becomes the right design when merchant users with MFA exist; this ADR
records that as the trigger for revisiting.

### "Effective" as a computed read (the latest approval whose deadline has passed)
Pros: no sweep, no lag between the deadline and the effect.
Cons: no state a reviewer can see, no partial index to make "one effective" structural, and it
is the Phase 6 plan's own named risk ("expiry treated as a query filter"). The sweep's lag is
one poll interval against a cooling-off measured in days.

### Raw bank details encrypted at rest
Pros: the approver could see the full account.
Cons: puts the platform in scope for holding bank details at all; the provider already holds
them, and the reference is what a payout needs.

### A separate approver role
Pros: collusion would need one person from each population.
Cons: a trust decision nobody has taken; the permission split makes it a one-line change when
someone does (`P4-TSK-009`'s recorded sentence).

## Consequences

Positive:
- A single insider, a stolen operator session without the factor, and a leaked merchant key
  each cannot redirect a merchant's money alone.
- Every step is on the audit trail with actor, reason and correlation — refusals included.
- The destination a payout used is an immutable, identified row.

Negative:
- Two colluding operators can still redirect money after the cooling-off, as with adjustments.
  The pending gauge and the trail are the detection; the residual is recorded.
- Operators without a factor act at `PASSWORD` — the conditional step-up's recorded limitation.
  Mandatory operator MFA is a policy decision for the owner.
- The approver sees a masked suffix, not the account: verification against the merchant's
  documentation happens outside the platform.
- The conditional step-up is now a six-line method in three services; extraction is recorded
  as a follow-up rather than done under two unrelated services.

Operational impact: one more leaderless sweep (register row in `DISTRIBUTED_EXECUTION.md` §3);
one gauge, `finapp.merchant.destination.pending`; an unconfigured provider answers `503` on
proposals only.
Security impact: the plan's four-eyes + step-up + cooling-off, with bank details kept at the
provider.
Financial impact: none directly — nothing is posted. It decides where `P6-TSK-012`'s payouts go.

## Invariants / Constraints

- `INV-AUD-04` at domain, statement (`proposed_by <> ?`) and constraint rank.
- The machine's three-layer enforcement follows `INV-LIFE-01/-02/-04`'s discipline without
  claiming them: a destination is not a money-moving operation (`MUTATION_TESTING.md` §3,
  the beneficiary precedent). *(This line claimed them until the task's completion gate.)*
- `INV-AUD-01…03`: every act and every refusal recorded; the grant and the reference never
  printed or echoed; each route's permission explicit, with its negative test.
- `INV-MER-01`: the destination/merchant pairing judged in the statement; one 404.

## Follow-up

- When merchant self-service users with MFA arrive, revisit decision 1.
- Extract the conditional step-up once a fourth caller needs it.
- ~~`P6-TSK-012`: the dispatch reads `effectiveFor`, refuses when none is effective, and records
  the destination id.~~ Done by `P6-TSK-012`, through `findEffectiveForShare` (§9).
