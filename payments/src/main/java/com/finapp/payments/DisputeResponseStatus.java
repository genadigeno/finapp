package com.finapp.payments;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The dispute-response machine (`P7-TSK-014`) — the dispatch-before-call lifecycle every provider
 * operation shares (ADR-0046), for an operation that moves no money:
 *
 * <pre>
 * DISPATCHED ──► SUBMITTED
 *     │   └────► FAILED
 *     └──► UNKNOWN ──► SUBMITTED
 *              └─────► FAILED
 * </pre>
 *
 * <p>{@code SUBMITTED} means the PSP took the response — not that the network decided anything:
 * the dispute's stage stays the network's word. {@code UNKNOWN} is honest ambiguity
 * ({@code INV-LIFE-03}), resolved by query on OUR reference, never by a clock. Declared on the
 * enum so the machine reads in one place; `V022`'s status {@code CHECK}, birth rule and every-writer
 * edge trigger are generated from here, and {@code PaymentsMigrationTest} fails the build if they
 * disagree.
 */
public enum DisputeResponseStatus {

    /** Committed with its reference before the call — the reconcilable state. */
    DISPATCHED,

    /** The PSP took the response; its submission reference is stored. Terminal. */
    SUBMITTED,

    /** The PSP refused it, or the first send transmitted nothing. Terminal — a new response
     * (a new key) may follow. */
    FAILED,

    /** Sent, or possibly sent, and no answer — resolved by query. */
    UNKNOWN;

    /** The statuses reachable from this one along one edge. */
    public Set<DisputeResponseStatus> permittedTransitions() {
        return switch (this) {
            case DISPATCHED -> EnumSet.of(SUBMITTED, FAILED, UNKNOWN);
            case UNKNOWN -> EnumSet.of(SUBMITTED, FAILED);
            case SUBMITTED, FAILED -> EnumSet.noneOf(DisputeResponseStatus.class);
        };
    }

    public boolean canTransitionTo(DisputeResponseStatus target) {
        return permittedTransitions().contains(target);
    }

    /** Terminal is terminal ({@code INV-LIFE-04}). */
    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** Whether a resolver may still act on it — the two non-terminal statuses. */
    public boolean isResolvable() {
        return !isTerminal();
    }

    /**
     * Whether it still answers the dispute: every status but {@code FAILED}. One LIVE response per
     * dispute — `V022`'s partial unique index is generated from this.
     */
    public boolean isLive() {
        return this != FAILED;
    }

    /** The quoted, comma-separated value list `V022`'s {@code CHECK} uses. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
