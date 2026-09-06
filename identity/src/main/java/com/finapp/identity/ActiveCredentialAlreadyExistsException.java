package com.finapp.identity;

import java.io.Serial;

/**
 * The identity already holds an active credential of this type (`P1-TSK-007`).
 *
 * <h2>Raised from the index, not from a check</h2>
 *
 * <p>The partial unique index on {@code (identity_id, type) WHERE status = 'ACTIVE'} is what
 * refuses this, so it is the database arbitrating between two concurrent writers rather than an
 * application check that raced. A pre-flight {@code SELECT} would not be a substitute and must not
 * be added: two instances would both see no active credential, both insert, and one would get
 * {@code 23505} anyway - a pre-check makes the defect rarer rather than absent, which is worse.
 *
 * <h2>The transaction is aborted when this is thrown</h2>
 *
 * <p>PostgreSQL aborts a transaction when it raises a unique violation, so a caller that intends to
 * carry on must roll back to a savepoint first. This is a typed exception rather than a boolean for
 * exactly that reason: a boolean would let a caller continue unaware, and every subsequent
 * statement would fail with {@code 25P02} - an error about the transaction rather than about the
 * credential, three layers from the cause.
 *
 * <h2>It carries nothing</h2>
 *
 * <p>Not the identity, not the type, and certainly not the derivation. Which account already has a
 * credential is an existence fact, and this type's whole neighbourhood is built around not
 * disclosing those ({@code INV-IDN-07}).
 */
public final class ActiveCredentialAlreadyExistsException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    ActiveCredentialAlreadyExistsException(Throwable cause) {
        super("The identity already holds an active credential of this type", cause);
    }
}
