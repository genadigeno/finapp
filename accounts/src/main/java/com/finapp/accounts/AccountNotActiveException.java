package com.finapp.accounts;

import java.io.Serial;

/**
 * The caller's own agreement is not {@code ACTIVE}, so no wallet currency can be added to it
 * (`P9-TSK-004`).
 *
 * <p>Thrown only after ownership is proven in the statement, so the refusal discloses nothing
 * about anybody else's accounts — unlike an unknown or not-yours identifier, which stays the
 * one {@code 404}. A wallet opened under a closed agreement would be a ledger account no
 * product owns any more, which is why the opener reads the agreement {@code FOR SHARE} and
 * refuses here rather than letting the close and the open interleave.
 */
public final class AccountNotActiveException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public AccountNotActiveException(CustomerAccountId account, CustomerAccountStatus status) {
        super("account " + account + " is " + status + "; a wallet currency cannot be added");
    }
}
