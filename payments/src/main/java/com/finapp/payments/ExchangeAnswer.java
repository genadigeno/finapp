package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * The grant exchange's total answer (`P7-TSK-006`, ADR-0062 §2): on success, exactly the
 * three values the platform is allowed to keep — the opaque destination reference, a
 * four-character display suffix, and the confirmation-of-payee result. Bank details and
 * aliases never cross this boundary ({@code INV-RAIL-03}); the destination reference is
 * opaque by construction, shaped only for storage.
 */
public record ExchangeAnswer(
        Outcome outcome,
        Optional<ProviderReference> destination,
        Optional<String> displaySuffix,
        Optional<ConfirmationOfPayee> payee,
        Optional<byte[]> evidence) {

    public enum Outcome {
        /** The grant was live and the three stored values came back. */
        EXCHANGED,
        /** The rail refused the grant — spent, expired or revoked. Knowledge. */
        REFUSED,
        /** No usable answer; the grant's state is unknown ({@code INV-LIFE-03}). */
        INDETERMINATE,
        /** The connection was refused before anything left: the grant is unspent. */
        NOTHING_SENT
    }

    /** The scheme directory's name check, in the rail's own four words (ADR-0062 §1). */
    public enum ConfirmationOfPayee {
        MATCH,
        CLOSE_MATCH,
        /** Recording it needs the customer's explicit acknowledgement (`P7-TSK-007`). */
        NO_MATCH,
        UNAVAILABLE
    }

    public static final int DISPLAY_SUFFIX_LENGTH = 4;

    public ExchangeAnswer {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(displaySuffix, "displaySuffix must not be null");
        Objects.requireNonNull(payee, "payee must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        boolean exchanged = outcome == Outcome.EXCHANGED;
        if (exchanged
                != (destination.isPresent()
                        && displaySuffix.isPresent()
                        && payee.isPresent())) {
            throw new IllegalArgumentException(
                    "EXCHANGED carries exactly the three stored values, and no other outcome"
                            + " carries any (ADR-0062 §2)");
        }
        if (displaySuffix.isPresent()
                && displaySuffix.get().length() != DISPLAY_SUFFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "the display suffix is exactly " + DISPLAY_SUFFIX_LENGTH + " characters");
        }
    }

    public static ExchangeAnswer exchanged(
            ProviderReference destination,
            String displaySuffix,
            ConfirmationOfPayee payee,
            byte[] evidence) {
        return new ExchangeAnswer(
                Outcome.EXCHANGED,
                Optional.of(destination),
                Optional.of(displaySuffix),
                Optional.of(payee),
                Optional.of(evidence));
    }

    public static ExchangeAnswer refused(byte[] evidence) {
        return new ExchangeAnswer(
                Outcome.REFUSED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(evidence));
    }

    public static ExchangeAnswer indeterminate() {
        return new ExchangeAnswer(
                Outcome.INDETERMINATE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static ExchangeAnswer indeterminate(byte[] evidence) {
        return new ExchangeAnswer(
                Outcome.INDETERMINATE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(evidence));
    }

    public static ExchangeAnswer nothingSent() {
        return new ExchangeAnswer(
                Outcome.NOTHING_SENT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * The outcome and the name check, never the destination reference or the suffix
     * (`P7-DOC-001`): the generated form printed both, so a log line naming the answer would
     * have carried the customer's bank destination ({@code INV-RAIL-03}'s sinks include logs).
     */
    @Override
    public String toString() {
        return "ExchangeAnswer[outcome=" + outcome
                + ", destination=" + (destination.isPresent() ? "<redacted>" : "none")
                + ", displaySuffix=" + (displaySuffix.isPresent() ? "<redacted>" : "none")
                + ", payee=" + payee.map(Enum::name).orElse("none")
                + ", evidence=" + evidence.map(bytes -> bytes.length + " bytes").orElse("none")
                + "]";
    }
}
