package com.finapp.payments;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The payment-attempt machine (ADR-0045 §2, {@code PAYMENT_LIFECYCLES.md} §3,
 * {@code INV-LIFE-01}):
 *
 * <pre>AUTH_DISPATCHED ──&gt; AUTHORIZED ──&gt; CAPTURE_DISPATCHED ──&gt; CAPTURED
 *      │    │                                │      │
 *      │    └──&gt; AUTH_UNKNOWN ──┐            │      └──&gt; CAPTURE_UNKNOWN ──&gt; CAPTURED
 *      │              │         │            │                  │
 *      └──&gt; FAILED &lt;──┘         └──&gt; AUTHORIZED                 └──&gt; FAILED</pre>
 *
 * <p>Seven states, eleven edges. <strong>The {@code *_DISPATCHED} states are durable on
 * purpose</strong> — each is committed <em>before</em> the provider is asked (ADR-0046), so a
 * crash mid-call leaves a visible fact to resolve rather than an unknown nobody recorded — the
 * exact inversion of ADR-0044's refusal of {@code PROCESSING} for transfers. <strong>Two unknown
 * states rather than one</strong>, so <em>what</em> is unknown is a property of the machine
 * itself ({@code INV-LIFE-03}): "did the authorization happen?" and "did the capture happen?"
 * are different questions with different resolution queries and different legal exits, and a
 * single {@code UNKNOWN} would need a second column the machine cannot see.
 *
 * <p><strong>{@code CAPTURED} is the attempt's stable state — no outgoing edge — and it is in
 * the terminal set</strong>, on exactly the argument {@link PaymentIntentStatus}'s
 * {@code SUCCEEDED} recorded: {@link #isTerminal()} stays the structural derivation, refunds are
 * new operations <em>referencing</em> the captured attempt ({@code INV-LIFE-04}'s own example),
 * never an edge out of it, and derived refund facts are stored nowhere. Captured is <strong>not
 * settled</strong> ({@code INV-SET-01}): settlement is Phase 8's fact, attached without touching
 * this machine.
 *
 * <p><strong>{@code AUTHORIZED → FAILED} is deliberately not an edge.</strong> Abandoning an
 * authorization is {@code VOIDED}'s job, and {@code VOIDED} has no producer in a flow that
 * always captures — it arrives with checkout expiry (Phase 6, ADR-0045 §5). Also absent with
 * their producers-to-come: {@code REQUIRES_ACTION} (no simulated provider issues a challenge),
 * {@code CLEARING}/{@code SETTLED} (Phase 8), multi-attempt retry states (one attempt per
 * intent in Phase 5, ADR-0045 §4 — the schema's one-live index keeps the day N arrives).
 *
 * <p>Declared on the enum so the machine is readable in one place and the sweep can be derived
 * rather than remembered. The schema {@code CHECK} and transition trigger are generated from
 * {@link #sqlValueList()}/{@link #permittedTransitions()} by {@code P5-TSK-008}'s reconciliation;
 * until it lands the fragments are pinned by literal in {@code PaymentAttemptTest}.
 */
public enum PaymentAttemptStatus {

    /**
     * The authorization dispatch is committed; the provider may or may not have been asked yet
     * ({@code P5-TSK-009}, born inside confirm's transaction). Nothing transitions <em>to</em>
     * it: birth is the only door.
     */
    AUTH_DISPATCHED,

    /**
     * The authorization's outcome is unknown — a timeout, a 5xx, a malformed body, a transport
     * failure after send ({@code INV-LIFE-03}). Resolved by query, webhook or nothing
     * ({@code PAYMENT_LIFECYCLES.md} §7); never expired into failure by assumption.
     */
    AUTH_UNKNOWN,

    /**
     * The issuer's promise: the provider's reference and the authorized amount, held on this
     * row. No ledger effect (ADR-0048 — the issuer holds the customer's funds, not us).
     * Provider-side expiry metadata stays in retained evidence: no column and no consumer yet
     * ({@code PHASE_5_PLAN.md} §8's deliberate omission).
     */
    AUTHORIZED,

    /** The capture dispatch is committed, its idempotency reference minted ({@code P5-TSK-010}). */
    CAPTURE_DISPATCHED,

    /** The capture's outcome is unknown — the second {@code INV-LIFE-03} state, own exits. */
    CAPTURE_UNKNOWN,

    /**
     * The provider captured; the posting commits beside this transition ({@code P5-TSK-010},
     * key {@code payment-capture:&lt;attemptId&gt;}). Stable with no outgoing edge, terminal by
     * structure (above); refunds reference this row and are bounded by its captured amount
     * ({@code INV-PAY-05}). Not settled ({@code INV-SET-01}).
     */
    CAPTURED,

    /**
     * The try failed, at either stage, with the mapped {@link PaymentFailureReason} on this row
     * ({@code INV-PAY-03}). Terminal: no automatic retry in Phase 5 — a failed attempt fails
     * its intent, and a customer who still wants to pay creates a new intent (ADR-0045 §4).
     */
    FAILED;

    /** The states reachable from this one. */
    public Set<PaymentAttemptStatus> permittedTransitions() {
        return switch (this) {
            case AUTH_DISPATCHED -> EnumSet.of(AUTHORIZED, FAILED, AUTH_UNKNOWN);
            case AUTH_UNKNOWN -> EnumSet.of(AUTHORIZED, FAILED);
            case AUTHORIZED -> EnumSet.of(CAPTURE_DISPATCHED);
            case CAPTURE_DISPATCHED -> EnumSet.of(CAPTURED, FAILED, CAPTURE_UNKNOWN);
            case CAPTURE_UNKNOWN -> EnumSet.of(CAPTURED, FAILED);
            case CAPTURED, FAILED -> EnumSet.noneOf(PaymentAttemptStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(PaymentAttemptStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for {@code P5-TSK-008}'s {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * The terminal states as a SQL literal list ({@code P5-TSK-008}'s trigger reconciliation).
     * Generated so "terminal" has one definition — and it deliberately <em>includes</em>
     * {@code CAPTURED}, whose stable-with-no-exit reading is argued at the class javadoc.
     */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(PaymentAttemptStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
