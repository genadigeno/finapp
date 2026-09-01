package com.finapp.platform.idempotency;

import java.sql.Connection;

/**
 * The work being made idempotent.
 *
 * <p>Receives the caller's transaction, so its financial effect commits with the idempotency
 * record and not separately (ADR-0004). A command that opened its own connection would break
 * that guarantee silently — everything would still appear to work until the first crash between
 * the two commits.
 */
@FunctionalInterface
public interface IdempotentCommand {

    /**
     * @param unitOfWork the open transaction to write the command's effect through
     * @return the outcome to store and to replay on retry; never null
     */
    CommandResult run(Connection unitOfWork);
}
