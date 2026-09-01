package com.finapp.platform.idempotency;

import java.util.Objects;

/**
 * A command's outcome, and whether it was a success.
 *
 * <p><strong>Why a failure is a result and not an exception.</strong> A command that failed
 * definitively — a rejected transfer, a declined authorisation — has a real outcome that a
 * retry must be told about rather than have re-attempted. Recording it as {@code FAILED} is what
 * makes the retry deterministic. An exception, by contrast, leaves the transaction to roll back,
 * taking the claim with it, so the command is genuinely retried — which is right for a
 * transient failure and wrong for a definitive one. The distinction is the command's to make,
 * and this type is how it says which it meant.
 */
public record CommandResult(boolean succeeded, StoredResponse response) {

    public CommandResult {
        Objects.requireNonNull(response, "response must not be null; use StoredResponse.empty()");
    }

    public static CommandResult succeeded(StoredResponse response) {
        return new CommandResult(true, response);
    }

    /** A definitive failure: recorded, and replayed to a retry rather than re-attempted. */
    public static CommandResult failed(StoredResponse response) {
        return new CommandResult(false, response);
    }
}
