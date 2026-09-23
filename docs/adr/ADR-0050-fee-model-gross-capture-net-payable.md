# ADR-0050 — The fee model: gross capture to the payable, fee assessed in the same entry, net payout

Status: Proposed
Date: 2026-09-21
Phase: 6
Context: Merchant · Checkout · Payments · Ledger
Supersedes: nothing. Closes unresolved architectural question 8 (High), open since
initiation: *"Fee model: who pays, when recognised, gross vs net settlement."*

## Context

Phase 6 introduces the second commercial party. A customer's checkout payment is no longer
money arriving into the customer's own wallet (Phase 5's flow): it is money owed **to a
merchant**, of which the platform keeps a fee. Question 8 has carried High risk for one
stated reason: **changing revenue recognition after postings exist is a restatement.** The
decision must land before the first merchant-bound capture posts.

Three questions, each with real alternatives:

1. **Who pays the fee?** The merchant (deducted from what they are owed), the customer
   (surcharged on top), or the platform (absorbed).
2. **When is fee revenue recognised?** At capture (when the platform's claim on the money
   becomes real), at settlement (when cash arrives — Phase 8), or at payout.
3. **Gross or net?** Does the merchant's payable record the full captured amount with the
   fee as its own visible deduction (gross), or only the net figure (net), with the fee
   implicit?

A fourth question hides inside the third: **how is the split computed so that no minor
unit is created or destroyed?** Two independent roundings (fee rounded, net rounded) can
sum to a cent more or less than the capture — the exact "fee rounding creating cent-level
ledger imbalance" risk the delivery plan names.

And a boundary question: the `payments` module posts the capture (ADR-0048) but must not
know merchants (`MODULE_ARCHITECTURE.md`'s ownership rules). Whoever computes the fee, the
capture's atomicity — posting commits with the `CAPTURED` transition, or neither — is
non-negotiable.

## Decision

1. **The merchant pays the fee.** The customer pays the order amount and nothing else; the
   platform's revenue is a deduction from what the merchant is owed. Surcharging is a
   jurisdiction-specific product decision and is out of scope for the core model.

2. **Fee revenue is recognised at capture.** The economic event that earns the fee is the
   platform capturing the payment on the merchant's behalf — the moment the platform's
   claim against the PSP becomes real (ADR-0048's boundary). Settlement risk is real but it
   is *settlement's* risk, tracked by Phase 8 against `SETTLEMENT_CLEARING` exactly as
   `INV-SET-01` already frames it; deferring recognition to settlement would make revenue a
   reconciliation outcome, and deferring it to payout would tie the platform's earnings to
   the merchant's withdrawal behaviour, which states nothing true.

3. **Gross to the books, net to the merchant — in one journal entry.** A merchant-bound
   capture posts, atomically with the attempt's `CAPTURED` transition, one entry:

   | Line | Direction | Account |
   |---|---|---|
   | Captured amount | DEBIT | `SETTLEMENT_CLEARING` |
   | Captured amount | CREDIT | `MERCHANT_PAYABLE` (the merchant's own liability account) |
   | Fee | DEBIT | `MERCHANT_PAYABLE` (same account) |
   | Fee | CREDIT | `FEE_REVENUE` |

   The books show the gross flow and the fee explicitly (the merchant's statement can
   render both); the payable's **position** is net. Payouts (ADR-0051) pay the net
   position. One entry, so `INV-LED-01` holds trivially and a crash cannot separate the
   capture from its fee — there is no state in which the platform captured but forgot to
   charge, or charged without capturing.

4. **The fee is computed once; the net is derived by subtraction.** `fee =
   round(gross × rate) + fixed` (rounding mode named per schedule, `INV-MON-03`), and the
   merchant's net is `gross − fee` — never independently computed, never independently
   rounded. The two lines above sum to the capture by construction; no rounding residual
   exists to strand (`INV-MER-04`). Fee currency is the capture currency; cross-currency
   fees are Phase 9's.

5. **The schedule version is pinned per assessment** (`INV-MER-03`, `INV-HIST-04`). Every
   assessment records the fee schedule version that produced it; recomputing under that
   version reproduces the amount to the minor unit; changing a schedule creates a **new
   version** effective forward and reprices nothing. A capture mid-flight when the version
   changes uses the version pinned at dispatch.

6. **The boundary: `payments` posts lines it is handed, and knows no merchant.** The
   capture's posting lines come from the flow that created the intent: Phase 5's wallet
   top-up supplies its two lines (ADR-0048, unchanged); a checkout-created intent supplies
   the four lines above through the same composition seam (`app` resolves the merchant's
   payable account and the pinned fee schedule when the intent is created, so the capture
   transaction computes nothing it cannot see). Provider adapters remain fee-blind; fee
   vocabulary never enters `payments`' domain model — the `INV-PAY-03` discipline applied
   to a second vocabulary.

## Consequences

- The merchant payable is **derived from postings** and nothing else (`INV-MER-02`): the
  account's position *is* captured − fees − payouts, with no stored payable field anywhere.
  The three-way reconciliation Phase 8 needs (payments ↔ payable ↔ payouts) exists by
  construction.
- A refund of a merchant-bound capture reverses the same shape — gross out of the payable,
  fee treatment per the schedule's refund policy (fee retained or returned — a **schedule
  attribute**, versioned with it, so the answer is data, not code).
- The fee computation is pure arithmetic on `Money` with a named rounding mode — property-
  testable, and testable in bulk (the high-volume batch in the phase gate).
- `FEE_REVENUE` already exists (seeded `P3-TSK-003`; first posted to by `P5-TST-003`'s
  storm spender). `MERCHANT_PAYABLE` accounts are created per merchant at onboarding, the
  customer-wallet precedent.
