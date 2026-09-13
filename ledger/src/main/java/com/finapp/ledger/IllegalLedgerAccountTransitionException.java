package com.finapp.ledger;

import java.io.Serial;

/**
 * A ledger-account status transition the machine does not permit ({@code INV-LIFE-02}).
 *
 * <p>Carries the states and not the identifier — an exception is serializable, {@code EntityId}
 * is not, and making it so is a change to proven kernel code no module task gets to smuggle
 * ({@code IllegalKycCaseTransitionException}'s recorded reasoning). The message names the
 * identifier's value instead.
 */
public final class IllegalLedgerAccountTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final LedgerAccountStatus from;
    private final LedgerAccountStatus to;

    public IllegalLedgerAccountTransitionException(
            LedgerAccountId account, LedgerAccountStatus from, LedgerAccountStatus to) {
        super("ledger account " + account + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public LedgerAccountStatus from() {
        return from;
    }

    public LedgerAccountStatus to() {
        return to;
    }
}
