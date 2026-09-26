package com.finapp.payments;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The wallet withdrawal machine (`P7-TSK-008`, ADR-0057 §1's four states adopted by
 * ADR-0062 §6 — the payout's discipline on the customer's wallet):
 *
 * <pre>
 * DISPATCHED ──► COMPLETED
 *     │    └───► FAILED
 *     └───────► UNKNOWN ──(inquiry)──► COMPLETED | FAILED
 * </pre>
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate
 * every transition; `V016`'s status {@code CHECK}s and its trigger's edges are generated from
 * here, and {@code PaymentsMigrationTest} fails the build if they disagree.
 *
 * <p><strong>Four states, no {@code REQUESTED}</strong>: the dispatch transaction judges the
 * wallet's bound under its lock, places the hold, pins the routing decision and commits
 * {@code DISPATCHED} atomically, so a request state is one no committed row could ever hold
 * (ADR-0044's every-state-has-a-producer rule — ADR-0057 §1 verbatim). <strong>And no
 * reversal edge from {@code COMPLETED}</strong>, which is {@code INV-REV-03}'s domain rank on
 * a rail whose declaration is final-on-acceptance: the machine simply has nowhere to go.
 *
 * <p><strong>Every state has a producer</strong>: the dispatch produces {@code DISPATCHED};
 * the scheme's answer — synchronous, re-driven by a lease takeover, or asked for by the
 * inquiry sweep — produces the rest, always through a conditional transition on the locked
 * row.
 */
public enum WithdrawalStatus {

    /** Judged under the wallet account's lock, held, our reference committed; the wire
     * follows the commit. */
    DISPATCHED,

    /**
     * The scheme accepted — irrevocably (ADR-0062 §3): the hold released and DEBIT the
     * wallet / CREDIT {@code INSTANT_CLEARING} posted in one transaction. Instructed and
     * accepted, not yet settled ({@code INV-SET-01}).
     */
    COMPLETED,

    /** The scheme refused, or provably never received it: the hold released, nothing
     * posted — the wallet is whole again. */
    FAILED,

    /** The scheme's answer is missing or ambiguous: the hold STANDS until the inquiry
     * resolves it ({@code INV-LIFE-03}, the standing-hold doctrine). */
    UNKNOWN;

    /** The states reachable from this one. */
    public Set<WithdrawalStatus> permittedTransitions() {
        return switch (this) {
            case DISPATCHED -> EnumSet.of(COMPLETED, FAILED, UNKNOWN);
            case UNKNOWN -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(WithdrawalStatus.class);
        };
    }

    public boolean canTransitionTo(WithdrawalStatus target) {
        return permittedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    /** Still awaiting the scheme's word — the only states a resolver may move or a send
     * permit may be renewed in. */
    public boolean isResolvable() {
        return !isTerminal();
    }

    /** The values as a SQL literal list — `V016`'s status {@code CHECK} is generated from
     * this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The resolvable values as a SQL literal list — the permit renewal's conditional. */
    public static String resolvableSqlValueList() {
        return Arrays.stream(values())
                .filter(WithdrawalStatus::isResolvable)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The terminal values as a SQL literal list — `V016`'s live-row predicates. */
    public static String sqlTerminalValueList() {
        return Arrays.stream(values())
                .filter(WithdrawalStatus::isTerminal)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
