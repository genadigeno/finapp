# ADR-0048 — Authorization is a payment-domain fact; the ledger's first touch is capture

Status: Proposed
Date: 2026-09-20
Phase: 5
Context: Payments · Ledger
Supersedes: nothing. Closes unresolved architectural question 6 (High), open since
initiation: *"Accounting treatment of authorization (memo/hold) vs capture (posting)."*

## Context

The question has carried High risk because getting it wrong **misstates available funds**:
record too early and the platform reports money it may never receive; record too late and
captured money is invisible to the books. Phase 5's flow makes the question concrete for
the first time: a wallet top-up, where the customer's **external** instrument funds their
platform wallet through a PSP. Authorization is the issuer's promise against the customer's
external card; capture is the act that makes the money ours to credit; settlement — Phase
8's — is when it actually arrives.

The candidate treatments the question named: a **memo posting** (a journal entry recording
the authorization), a **Phase 3 hold**, or **nothing at the ledger** until capture.

## Decision

1. **Authorization has no ledger effect.** It is recorded entirely in the payment domain —
   the attempt's `AUTHORIZED` state, amount, provider references and provider-side expiry
   metadata. Nothing about the platform's own financial position has changed: the funds
   being promised are the customer's, at their issuer, outside our books. There is nothing
   true for a balanced entry to say.

2. **Capture is the ledger's first touch**: one posting, **debit `PSP_CLEARING` (asset:
   due from the provider), credit the customer's wallet (liability)** — committed in the
   same transaction as the attempt's `CAPTURE_DISPATCHED → CAPTURED` transition and the
   intent's move to `SUCCEEDED`, through `PostingService` on the outcome transaction's
   connection, idempotency key `payment-capture:<attemptId>` so a replayed or
   webhook-raced outcome can never post twice.

3. **Captured is not settled** (`INV-SET-01`). The `PSP_CLEARING` balance is continuously
   the captured-but-unsettled position — the number Phase 8's settlement files will
   reconcile against. Phase 5 posts nothing to move it onward.

4. **A refund holds, then posts.** Refund dispatch places a **Phase 3 hold** on the
   customer wallet for the refund amount, availability judged inside the account lock
   (`INV-BAL-04/-05`) — the money being returned must not be spendable mid-flight — and
   the outcome resolves it atomically: completion releases the hold and posts **debit
   wallet / credit `PSP_CLEARING`** (key `payment-refund:<refundId>`); failure releases
   with nothing posted. This is the hold-then-capture composition `P3-TSK-015` recorded as
   owed to "the capturing flow", arriving with its owner.

## Why

**A memo posting records a maybe as an entry.** Most authorizations in the world expire or
are voided without capture; entries for them would put never-money into the journal, force
a contra-entry convention to expire it, and make the trial balance carry noise whose only
reader is the code that filters it out. A journal entry is the platform's strongest claim
of fact; an authorization is precisely not yet one.

**A Phase 3 hold has the wrong subject.** A hold constrains *our* customer's wallet — the
platform's liability. A top-up authorization constrains the customer's **external** funds
at their issuer; the wallet's availability is genuinely unchanged until capture. Using a
hold here would misstate available funds in the conservative-looking direction, which is
still misstating them. (The refund direction is the mirror image — there the funds at risk
*are* wallet funds, which is exactly why the refund **does** hold.)

**Nothing-until-capture keeps `INV-BAL` honest end to end**: every wallet balance remains
explainable purely from postings that describe money the platform actually took custody
of, and the clearing account's balance acquires a precise operational meaning Phase 8
depends on.

## Consequences

- The authorization's amount and expiry live only in the payment domain; an operator asking
  "how much is authorized but uncaptured?" queries payments, not the ledger — a payment
  metric, deliberately not a financial position.
- Capture failure after authorization leaves no ledger trace to clean up — the `FAILED`
  attempt records the mapped reason and the issuer's promise simply expires provider-side.
- The refund's hold makes wallet availability contend with in-flight refunds under the
  established account lock — the same contention Phase 3 priced.
- F1/F2 (trial balance, replay-from-zero) extend over Phase 5's postings with no new
  mechanism: captures and refunds are ordinary balanced entries through the one write path.

## Alternatives rejected

- **Memo/authorization postings** — entries for never-money (above).
- **Hold on the wallet at authorization** — wrong subject; misstates availability (above).
- **Posting at settlement rather than capture** — leaves captured custody invisible to the
  books for days and collapses `INV-SET-01`'s distinction from the other side; the wallet
  credit would lag the customer-visible "success" (the ADR-0041 argument against a
  customer-visible state ahead of the money — here, behind it).

## Follow-ups

- `P5-TSK-010` implements capture's posting; `P5-TSK-015` the refund's hold-then-post;
  `P5-TST-003` storms both against the trial-balance and projection sweeps.
- Phase 6 revisits the treatment for merchant-present flows (fees, payables) under its own
  ADR — question 8's territory, deliberately not decided here.
