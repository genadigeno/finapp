package com.finapp.risk;

import java.util.UUID;

/**
 * Payments' doors on a held card payment, as a risk action reaches them (`P13-TSK-010`, slice 2; {@code PHASE_13_PLAN.md}
 * sections 5, 12.3, 12.8; ADR-0108, ADR-0112) - <strong>declared by this task: no port existed</strong>, on
 * {@link HeldWithdrawalActions}' shape as `-009` left it. {@code app} implements it over payments' keyed
 * {@code captureHeld} and {@code voidHeld} ({@code PaymentsHeldPaymentActions}): each resolves the payment's capture
 * hold under its row lock in payments' own transaction, keyed by {@code idempotencyKey} ({@code risk.action:<id>}) and
 * audited by payments, then hands the money to payments' existing capture or void command - the provider called
 * holding no connection (ADR-0046). The actions {@link ActionKind#CAPTURE_HELD_PAYMENT} and
 * {@link ActionKind#VOID_HELD_PAYMENT} are executed through it by the action machine ({@link ActionDispatch}, T-act,
 * `P13-TSK-017`) - an approved override's of a card {@code HOLD} (`P13-TSK-012`'s, folded in) or a case's - under the
 * system actor.
 *
 * <p><strong>The shape.</strong> No unit of work - the door sends, so it runs in payments' own transactions, never inside
 * a risk one (T-act's shape); the subject is the payment intent's id; the reason is a code
 * ({@code [A-Z][A-Z0-9_]{0,63}}), never a note ({@code INV-RSK-08}).
 *
 * <p><strong>The outcomes.</strong> {@link ActionOutcome#DONE}: this call (or this key's earlier call) resolved the hold
 * and the capture or the void followed. {@link ActionOutcome#ALREADY}: the hold was already resolved by another hand -
 * captured, voided, or expired by payments' deadline leg - or the authorisation no longer stands (declined, or the
 * customer's own cancellation released it); a capture racing the deadline leaves exactly one resolution and the loser
 * reads {@code ALREADY}. {@link ActionOutcome#REFUSED}: no held payment answers to the id, or - a capture - its
 * {@code held_until} has passed on the database clock: the expiry is the policy's outcome. An authorisation still in
 * flight, a key reused for another payment or a storage failure is thrown - the action machine's {@code FAILED},
 * re-driven by the same key.
 */
public interface HeldPaymentActions {

    ActionOutcome capture(UUID intentId, String idempotencyKey, String reason);

    ActionOutcome voidHold(UUID intentId, String idempotencyKey, String reason);
}
