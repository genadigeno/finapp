package com.finapp.payments;

import java.util.Arrays;
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
 * <p>The machine lived on this enum while there was one machine. Since `P7-TSK-002` the edges
 * are each {@link InteractionModel}'s own — a status's legal exits are a property of the model
 * a row lives in, not of the status — and this enum keeps the vocabulary, the terminal set and
 * the SQL fragments the {@code P5-TSK-008} reconciliation generates from. The push states sit
 * below the two-step seven; the book machine adds no state of its own (born {@code EXECUTED}
 * or {@code FAILED}).
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
     * The try failed, at whatever stage its model has, with the mapped
     * {@link PaymentFailureReason} on this row ({@code INV-PAY-03}). Terminal in every model:
     * no automatic retry — a failed attempt fails its intent, and a customer who still wants
     * to pay creates a new intent (ADR-0045 §4). The one state every model shares, legal
     * because a terminal has no edges to blur.
     */
    FAILED,

    // ------------------------------------------------------------------ the PUSH machine
    // (`P7-TSK-002`, ADR-0059 §2). Appended after the two-step states so every generated
    // list only extends. Their operations arrive with their rails (`P7-TSK-006`, `-009`).

    /**
     * A pay-in waiting for the payer's authorization at the payer's own PSP — decided by that
     * PSP, never by our clock, which is why no sweep bound and no stuck gauge reads it
     * (`P7-TSK-009` owns its ageing).
     */
    AWAITING_PAYER,

    /** The push's execution dispatch is committed (ADR-0046's discipline on the second model). */
    EXECUTION_DISPATCHED,

    /** The execution's outcome is unknown ({@code INV-LIFE-03}, the push model's own state). */
    EXECUTION_UNKNOWN,

    /**
     * The push executed — the payee's credit is final on the rails that carry this model
     * (ADR-0059 §1), and <strong>{@code EXECUTED} is not {@code CAPTURED}</strong>: no query,
     * report or reconciliation can mistake one rail's completion for another's. Shared with
     * the book machine, whose rows are born here. Terminal by structure, in the one-live
     * index's predicate with the other two.
     */
    EXECUTED;

    /**
     * Whether no model's machine has an edge out of this state (`P7-TSK-002`: the machines
     * live on {@link InteractionModel}, so "terminal" is the structural union —
     * {@code InteractionModelMachinesTest} pins this switch equal to that derivation).
     */
    public boolean isTerminal() {
        return switch (this) {
            case CAPTURED, FAILED, EXECUTED -> true;
            default -> false;
        };
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
