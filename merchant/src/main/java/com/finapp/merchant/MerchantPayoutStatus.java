package com.finapp.merchant;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The merchant payout machine (`P6-TSK-012`, ADR-0051 §5 as refined by ADR-0057 §1,
 * {@code CHECKOUT_MERCHANT_LIFECYCLES.md} §4):
 *
 * <pre>
 * DISPATCHED ──► COMPLETED
 *     │    └───► FAILED
 *     └───────► UNKNOWN ──(query)──► COMPLETED | FAILED
 * </pre>
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate
 * every transition rather than the ones somebody remembered. `V007`'s status {@code CHECK}s and
 * its trigger's edges are generated from here; {@code MerchantPayoutMigrationTest} fails the
 * build if they disagree.
 *
 * <p><strong>Four states, not the five first planned.</strong> ADR-0051 §2's dispatch
 * transaction judges the bound, places the hold and commits {@code DISPATCHED} atomically, so a
 * {@code REQUESTED} state would be one no committed row could ever hold — ADR-0044's
 * every-state-has-a-producer rule refuses it, and the refund, the same shape, has four too.
 *
 * <p><strong>Every state has a producer</strong>: the dispatch transaction produces
 * {@code DISPATCHED}; the provider's answer — synchronous, re-driven by a takeover, or queried by
 * the resolution sweep — produces the rest, always through a conditional transition on the
 * locked row.
 */
public enum MerchantPayoutStatus {

    /** Judged under the payable's lock, held, our reference committed; the wire follows. */
    DISPATCHED,

    /**
     * The rail accepted irrevocably: the hold released and DEBIT payable / CREDIT
     * {@code PAYOUT_CLEARING} posted in one transaction. Instructed, not settled
     * ({@code INV-SET-01}).
     */
    COMPLETED,

    /** The rail refused, or never received it: the hold released, nothing posted. */
    FAILED,

    /** The rail's answer is missing or ambiguous: the hold STANDS until a query resolves it. */
    UNKNOWN;

    /** The states reachable from this one. */
    public Set<MerchantPayoutStatus> permittedTransitions() {
        return switch (this) {
            case DISPATCHED -> EnumSet.of(COMPLETED, FAILED, UNKNOWN);
            case UNKNOWN -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(MerchantPayoutStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(MerchantPayoutStatus target) {
        return permittedTransitions().contains(target);
    }

    /**
     * Still awaiting the rail's word — the states an answer may resolve, a takeover may re-send
     * and the sweep may query. Exactly the non-terminal ones.
     */
    public boolean isResolvable() {
        return !isTerminal();
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraints. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The resolvable states as a SQL literal list, for the sweep's index and the permit. */
    public static String resolvableSqlValueList() {
        return Arrays.stream(values())
                .filter(MerchantPayoutStatus::isResolvable)
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
