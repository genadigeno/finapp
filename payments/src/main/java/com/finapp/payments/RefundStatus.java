package com.finapp.payments;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The refund machine (ADR-0045 §3, {@code PAYMENT_LIFECYCLES.md} §4, {@code INV-LIFE-01}):
 *
 * <pre>DISPATCHED ──&gt; COMPLETED
 *     │  └─────&gt; FAILED
 *     └──&gt; UNKNOWN ──&gt; {COMPLETED, FAILED}</pre>
 *
 * <p>Four states, five edges. Born {@code DISPATCHED} — the same ADR-0046 shape as the attempt:
 * the dispatch (and the Phase 3 hold it places on the customer wallet) commits before the
 * provider is asked, so a crash mid-call leaves a visible fact. <strong>One unknown state
 * suffices</strong> where the attempt needed two: a refund is one provider operation, so "what
 * is unknown" has only one answer ({@code INV-LIFE-03}).
 *
 * <p>Deliberately absent: a {@code REQUESTED}/approval state (refund creation <em>is</em> the
 * privileged act, with its required reason — a maker-checker flow has no producer yet), and
 * {@code PARTIALLY_REFUNDED}-style aggregate states (derived by views over refund rows,
 * ADR-0045 — stored nowhere).
 *
 * <p>The schema {@code CHECK} and transition trigger are generated from
 * {@link #sqlValueList()}/{@link #permittedTransitions()} by {@code P5-TSK-008}'s
 * reconciliation; until it lands the fragments are pinned by literal in {@code RefundTest}.
 */
public enum RefundStatus {

    /**
     * The refund dispatch is committed, the hold placed, the idempotency reference minted
     * ({@code P5-TSK-015}). Nothing transitions <em>to</em> it: birth is the only door.
     */
    DISPATCHED,

    /**
     * The refund's outcome is unknown ({@code INV-LIFE-03}); resolved by query, webhook or
     * nothing ({@code PAYMENT_LIFECYCLES.md} §7) — the hold stays until an outcome does.
     */
    UNKNOWN,

    /**
     * The provider refunded; the posting (DR wallet / CR {@code PSP_CLEARING}, key
     * {@code payment-refund:&lt;refundId&gt;}) and the hold release commit atomically beside
     * this transition (ADR-0048, {@code P5-TSK-015}). Terminal.
     */
    COMPLETED,

    /** The provider refused; the hold releases with nothing posted. Terminal. */
    FAILED;

    /** The states reachable from this one. */
    public Set<RefundStatus> permittedTransitions() {
        return switch (this) {
            case DISPATCHED -> EnumSet.of(COMPLETED, FAILED, UNKNOWN);
            case UNKNOWN -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(RefundStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(RefundStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for {@code P5-TSK-008}'s {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The terminal states as a SQL literal list ({@code P5-TSK-008}'s trigger reconciliation). */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(RefundStatus::isTerminal)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
