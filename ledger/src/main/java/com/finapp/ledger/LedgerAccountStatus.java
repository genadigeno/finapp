package com.finapp.ledger;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lifecycle of a ledger account — <strong>the one mutable thing on the row</strong>.
 *
 * <pre>
 * ACTIVE ⇄ POSTING_SUSPENDED, and either → CLOSED (terminal)
 * </pre>
 *
 * <p>Deliberately minimal. The classification is frozen (`INV-LED-06`), the identity fields are
 * facts, and what legitimately changes about a chart row is only whether it accepts postings.
 * {@code CLOSED} ends the account's <em>acceptance of new postings</em> and nothing else: the
 * rows it accumulated are history, and history survives the account ({@code INV-HIST-01},
 * `P3-TSK-014`'s objective stated at the type that carries it).
 *
 * <p>Persisted values, in a generated {@code CHECK} ({@code LedgerAccountMigrationTest}
 * reconciles).
 */
public enum LedgerAccountStatus {

    /** Accepting postings. */
    ACTIVE,

    /**
     * Temporarily refusing postings — an operational freeze. Reversible, because a freeze is a
     * pause and not an ending; the check happens under the account lock (ADR-0039), which is
     * `P3-TSK-006`'s to enforce.
     */
    POSTING_SUSPENDED,

    /**
     * Terminal ({@code INV-LIFE-04}). No postings ever again; every posted row stays. A closed
     * account is never reopened — a successor product opens successor accounts.
     */
    CLOSED;

    /** The states reachable from this one — the {@code KycCaseStatus} idiom. */
    public Set<LedgerAccountStatus> permittedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(POSTING_SUSPENDED, CLOSED);
            case POSTING_SUSPENDED -> EnumSet.of(ACTIVE, CLOSED);
            case CLOSED -> EnumSet.noneOf(LedgerAccountStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(LedgerAccountStatus target) {
        return permittedTransitions().contains(target);
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
