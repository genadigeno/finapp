/**
 * The customer-facing purchase experience, and the commercial fact it produces.
 *
 * <p><strong>What belongs here.</strong> The CheckoutSession aggregate ({@code P6-TSK-006}): a
 * merchant's short-lived, expiring <em>offer</em> to a customer to pay, with its six-state
 * machine — expiry a sweeper-earned transition, never a {@code WHERE expires_at < now()} filter
 * pretending to be a state (ADR-0053 §4) — and the Order: the permanent fact a <em>paid</em>
 * session produces, one per session, append-only. A session that dies unpaid produces no order
 * at all; the platform does not manufacture commercial facts out of silence.
 *
 * <p><strong>The race rule this module is defined by</strong> (ADR-0053 §5, {@code INV-MER-06}):
 * <em>expiry gates dispatch; landed money always wins.</em> An expired session refuses to start
 * anything new — but a capture that lands after expiry still completes the order, through the
 * modelled, counted {@code EXPIRED -> COMPLETED_LATE} edge, because the provider answers on its
 * own schedule (ADR-0046) and money whose commercial fact is decided by a race between a webhook
 * and a sweeper is money the books cannot explain.
 *
 * <p><strong>No business-sibling edge, in either direction — deliberately.</strong> The
 * {@code paymentmethods} posture for a different reason: this module is isolated so the purchase
 * experience cannot grow into a god-orchestrator. Payment execution ({@code payments}), merchant
 * resolution ({@code merchant}) and the ADR-0050 posting composition all reach it through ports
 * {@code app} implements; references travel by identifier. None of the three refusals has a
 * Gradle cycle behind it — {@code CheckoutModuleIsolationTest} is the only control.
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema
 * ({@code P6-TSK-001}). The aggregates, machines and tables are {@code P6-TSK-006}; the
 * session's payment flow {@code P6-TSK-007}; the expiry sweep and the late-completion race
 * {@code P6-TSK-008}.
 */
package com.finapp.checkout;
