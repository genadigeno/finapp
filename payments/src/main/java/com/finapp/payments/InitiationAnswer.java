package com.finapp.payments;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;
import java.util.Optional;

/**
 * A pay-by-bank initiation's total answer (`P7-TSK-006`, ADR-0062 §1): on success, the
 * authorization handle the payer's client follows to the payer's own PSP — where consent is
 * given with strong customer authentication, never here. The handle is opaque, short-lived,
 * and {@link Sensitive} by construction — it is a capability URL, and a log line holding
 * it is a log line that can complete or observe the payer's flow ({@code INV-AUD-02}'s
 * default-deny redaction); the surface that renders it to the payer's client (`P7-TSK-010`'s)
 * will be its one greppable {@code expose()} site.
 */
public record InitiationAnswer(
        Outcome outcome,
        Optional<Sensitive<String>> authorizationHandle,
        Optional<byte[]> evidence) {

    public enum Outcome {
        /** The scheme opened the initiation; the payer must now authorize at their PSP. */
        INITIATED,
        /** The scheme refused to open it. Knowledge. */
        REFUSED,
        /** No usable answer ({@code INV-LIFE-03}); the inquiry resolves it. */
        INDETERMINATE,
        /** The connection was refused before anything left. */
        NOTHING_SENT
    }

    public static final int MAX_HANDLE_LENGTH = 512;

    public InitiationAnswer {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(authorizationHandle, "authorizationHandle must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if ((outcome == Outcome.INITIATED) != authorizationHandle.isPresent()) {
            throw new IllegalArgumentException(
                    "INITIATED carries the authorization handle, and no other outcome does -"
                            + " an initiation the payer cannot follow is not one");
        }
    }

    /** The handle's shape is judged here, BEFORE it becomes unreadable by design. */
    public static InitiationAnswer initiated(String authorizationHandle, byte[] evidence) {
        Objects.requireNonNull(authorizationHandle, "authorizationHandle must not be null");
        if (authorizationHandle.isBlank()
                || authorizationHandle.length() > MAX_HANDLE_LENGTH) {
            throw new IllegalArgumentException(
                    "an authorization handle is 1.." + MAX_HANDLE_LENGTH + " characters");
        }
        return new InitiationAnswer(
                Outcome.INITIATED,
                Optional.of(Sensitive.of(authorizationHandle)),
                Optional.of(evidence));
    }

    public static InitiationAnswer refused(byte[] evidence) {
        return new InitiationAnswer(
                Outcome.REFUSED, Optional.empty(), Optional.of(evidence));
    }

    public static InitiationAnswer indeterminate() {
        return new InitiationAnswer(Outcome.INDETERMINATE, Optional.empty(), Optional.empty());
    }

    public static InitiationAnswer indeterminate(byte[] evidence) {
        return new InitiationAnswer(
                Outcome.INDETERMINATE, Optional.empty(), Optional.of(evidence));
    }

    public static InitiationAnswer nothingSent() {
        return new InitiationAnswer(Outcome.NOTHING_SENT, Optional.empty(), Optional.empty());
    }
}
