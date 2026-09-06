package com.finapp.identity;

import java.io.Serial;

/**
 * The login identifier is in use, so this identity cannot be created (`P1-TSK-006`).
 *
 * <h2>Why this is a typed exception and not a return value</h2>
 *
 * <p>It is raised from a unique-index violation, which means PostgreSQL has already <strong>put
 * the transaction into an aborted state</strong>. Nothing further can be done on that connection
 * until the caller rolls back to a savepoint. A boolean return would let a caller carry on
 * unaware, and every subsequent statement would fail with {@code 25P02} - an error about the
 * transaction rather than about the identifier, three layers from the cause.
 *
 * <h2>What the caller must not do with it</h2>
 *
 * <p><strong>It must never reach a client as a distinct outcome.</strong> Telling a caller that a
 * login identifier is taken turns registration into an account-existence oracle, which is exactly
 * {@code INV-IDN-07}. The boundary answers with the single generic refusal
 * {@code party.RegistrationRefused}, identical for every reason a registration can fail.
 *
 * <p>It carries no identifier for the same reason: an exception message reaches a log line, and
 * this type's whole confidentiality concern is that knowing a login exists tells an attacker an
 * account exists ({@code DATA_CLASSIFICATION.md} §4). The attempted identifier is recorded in
 * exactly one place - the audit record - which is access-controlled and is the regulatory
 * artefact.
 */
public final class LoginIdentifierAlreadyTakenException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    LoginIdentifierAlreadyTakenException(Throwable cause) {
        super("The login identifier is already in use", cause);
    }
}
