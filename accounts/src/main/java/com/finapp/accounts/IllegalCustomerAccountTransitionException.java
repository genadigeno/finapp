package com.finapp.accounts;

import java.io.Serial;

/**
 * A customer-account status transition the machine does not permit ({@code INV-LIFE-02}).
 *
 * <p>Carries the states and not the identifier — an exception is serializable, {@code EntityId}
 * is not ({@code IllegalLedgerAccountTransitionException}'s recorded reasoning); the message
 * names the identifier's value instead.
 */
public final class IllegalCustomerAccountTransitionException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final CustomerAccountStatus from;
    private final CustomerAccountStatus to;

    public IllegalCustomerAccountTransitionException(
            CustomerAccountId account, CustomerAccountStatus from, CustomerAccountStatus to) {
        super("customer account " + account + " cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public CustomerAccountStatus from() {
        return from;
    }

    public CustomerAccountStatus to() {
        return to;
    }
}
