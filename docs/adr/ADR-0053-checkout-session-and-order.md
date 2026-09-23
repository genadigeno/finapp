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

## Amendment — `P6-TSK-007`: what a retry of the confirmation answers

Implementing §3 and §5 made a gap in them visible, and the correction belongs here rather than
only in code.

**Every state that can be converged on, is.** This ADR said duplicate completion "converges on
the machine", and the implementation first read that as: a session mid-payment continues on the
intent the first call opened. That is right, and it is not enough. The capture is chained
synchronously, so the first confirmation normally returns `COMPLETED` — which left every retry
of a *successful purchase* answering `409 checkout.NotConfirmable`, to a customer who has no
other way to learn the outcome: the checkout read surface is the merchant's, key-authenticated,
and the customer holds only a token.

A lost response is the ordinary failure of a payment flow, not an exceptional one. So a
confirmation of a session that is already **paid** (`COMPLETED` or `COMPLETED_LATE`) renders the
session and writes nothing — it confirms no payment and chains no capture, because the order was
created in the capture's own transaction and a paid session means the money has landed.

Two constraints on that convergence, both load-bearing:

- **The payer is re-established first**, against the intent the session names, so a *second*
  holder of the token learns neither the payment intent nor the order — it gets the same one
  `404` an unknown session gets. The `PAYMENT_PENDING` branch does not repeat this check because
  `payments` performs it at the confirmation; the paid branch must, precisely because it skips
  that surface.
- **It does not extend to a session that left the flow unpaid.** Abandoned, or expired with
  nothing captured, still refuses: there is no outcome to converge on.

**And one price per payment means converging on a duplicate, refusing a difference.** Because
the confirmation's payment creation is keyed on the session, concurrent confirmations converge
on one intent and then all arrive at the fee pin with the same decision. A pin that treated its
own primary key as a storage failure failed nine of ten purchases that had worked. The pin now
converges when the merchant, the version and the gross all match, and throws when any of them
does not — the two halves of `INV-MER-03` at this level: converging is what keeps a retry from
being an error, and refusing is what keeps it from being a silent repricing.
