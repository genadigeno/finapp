# ADR-0053 — Checkout session and order: two aggregates, expiry gates dispatch, landed money always wins

Status: Proposed
Date: 2026-09-21
Phase: 6
Context: Checkout · Merchant · Payments
Supersedes: nothing. Confirms and closes unresolved architectural question 7 (its recorded
working position: `checkout` is its own module, `MODULE_ARCHITECTURE.md` §3 M2).

## Context

The checkout session is the customer-facing purchase experience: short-lived, expiring,
abandonable. The order is the commercial fact a completed checkout produces: long-lived,
the unit merchants reconcile against. The payment is Phase 5's machinery, already built.
Three relationships need fixing before code exists, because each has a tempting collapse:
session-as-order (an "order" that expires is not a fact), order-as-payment (ADR-0045
separated intent from attempt for exactly this reason one level down), and — the sharpest —
**what happens when the payment's outcome and the session's expiry race**, since the
provider's answer arrives on its own schedule (ADR-0046) and the session's clock stops for
nobody.

## Decision

1. **Two aggregates.** `CheckoutSession` (the offer to pay: merchant, amount, currency,
   line summary, expiry, the payment intent it opened) and `Order` (the commercial fact:
   created when a session's payment succeeds, never before). A session that expires
   unpaid produces **no order row at all** — an abandoned checkout is not a cancelled
   order, and the platform does not manufacture commercial facts out of silence.
2. **Question 7 closed as the working position stood**: `checkout` is its own module. The
   session and order have their own lifecycles, state and events; M2's merge trigger
   ("owns no state that outlives a session") is demonstrably not met — the order outlives
   everything. The trigger stays recorded for re-evaluation if Phase 6 proves otherwise.
3. **One payment intent per session**, created by the session's own confirmation flow and
   referenced by id — the one-live discipline (partial unique index) with the same shape
   ADR-0045 pinned for attempts-per-intent. The session holds the intent id; `payments`
   does not know sessions (references point **from** checkout **into** payments, never
   back). Retry-after-failure within a session's lifetime re-uses the session's intent
   exactly as ADR-0045 §4 already allows for attempts.
4. **The session lifecycle**: `OPEN → PAYMENT_PENDING → COMPLETED | EXPIRED | ABANDONED`.
   Expiry is a **modelled transition produced by a sweeper** (the `PaymentSweeperSchedule`
   pattern: leaderless, conditional, idempotent per ADR-0024) — never a `WHERE expires_at <
   now()` filter pretending to be a state. All three terminal states are earned by
   producers (the ADR-0044 doctrine).
5. **Expiry gates dispatch; landed money always wins.** The race has one rule with two
   halves:
   - An **expired session refuses to *start* anything**: no new confirmation, no new
     dispatch. The gate is the session's conditional transition — an expiry and a
     confirmation racing on one row have exactly one winner (`INV-CON-02`).
   - **Money that landed is never orphaned by a clock.** If the capture completes after
     the session expired (dispatched before expiry; the provider answered late — scenario
     the plan's failure list owns), the payment's success **still produces the order**:
     the session moves `EXPIRED → COMPLETED_LATE` (a modelled, audited, metered edge), the
     merchant is credited per ADR-0050, and the late completion is visible to operators.
     The alternative — auto-refunding landed money — would initiate a *second* money
     movement with its own failure modes to undo a first one that succeeded, turning a
     timing artefact into financial risk. A merchant who does not want the late order
     refunds it through the existing, human-decided refund path.
6. **The order lifecycle** is deliberately minimal in Phase 6: `PAID → REFUNDED
   (partially/fully, derived from the payment's refund rows, not stored)`. Fulfilment
   states are the merchant's business, not the platform's books.

## Consequences

- `checkout → payments` is **refused** in the build graph: checkout commands payments
  through a port `app` implements (the `InstrumentResolution` precedent), so the module
  that owns the customer's purchase experience cannot reach provider machinery, and the
  posting composition seam (ADR-0050 §6) has one home.
- The session token (customer-facing) is single-purpose and unguessable (ADR-0052 §5);
  possession grants access to that session only.
- Duplicate session creation is keyed (`INV-IDEM-01`); duplicate completion converges on
  the machine; the expiry sweeper races the webhook exactly as Phase 5's sweeper races it,
  arbitrated by conditional transitions — no new concurrency primitive exists in this
  phase.
- `COMPLETED_LATE` is a distinct state so the honest condition is countable (a meter and
  an operator view), rather than laundered into `COMPLETED`.
