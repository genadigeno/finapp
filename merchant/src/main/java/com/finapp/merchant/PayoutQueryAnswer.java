package com.finapp.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * What a query by our reference got back (`P6-TSK-012`) — {@link PayoutAnswer}'s shape, with
 * the one verdict only a query can give: the provider has no record of the reference at all.
 *
 * @param evidence the answer's bytes exactly as they arrived; empty when nothing did
 */
public record PayoutQueryAnswer(
        Verdict verdict, Optional<PayoutProviderReference> providerReference, Optional<byte[]> evidence) {

    /** The four things a query can tell the platform. */
    public enum Verdict {
        /** The rail accepted the payout irrevocably. */
        ACCEPTED,
        /** The rail refused it. */
        DECLINED,
        /**
         * The provider has no record of our reference. Concluded {@code NEVER_RECEIVED} only
         * once the payout's latest send permit is older than the sweep's bound (ADR-0057 §4).
         */
        UNRECOGNISED,
        /** Anything else — pending, a timeout, a 5xx, garbage, an unmapped word. */
        INDETERMINATE
    }

    public PayoutQueryAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if ((verdict == Verdict.ACCEPTED) != providerReference.isPresent()) {
            throw new IllegalArgumentException(
                    "an acceptance carries the provider's reference, and nothing else does");
        }
        evidence = evidence.map(byte[]::clone);
    }

    public static PayoutQueryAnswer accepted(PayoutProviderReference theirs, byte[] received) {
        return new PayoutQueryAnswer(Verdict.ACCEPTED, Optional.of(theirs), Optional.of(received));
    }

    public static PayoutQueryAnswer declined(byte[] received) {
        return new PayoutQueryAnswer(Verdict.DECLINED, Optional.empty(), Optional.of(received));
    }

    public static PayoutQueryAnswer unrecognised(byte[] received) {
        return new PayoutQueryAnswer(Verdict.UNRECOGNISED, Optional.empty(), Optional.of(received));
    }

    public static PayoutQueryAnswer indeterminate() {
        return new PayoutQueryAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty());
    }

    public static PayoutQueryAnswer indeterminate(byte[] received) {
        return new PayoutQueryAnswer(
                Verdict.INDETERMINATE, Optional.empty(), Optional.of(received));
    }

    @Override
    public Optional<byte[]> evidence() {
        return evidence.map(byte[]::clone);
    }

    /** The verdict and whether bytes arrived — never the bytes. */
    @Override
    public String toString() {
        return "PayoutQueryAnswer[" + verdict + ", evidence=" + evidence.isPresent() + "]";
    }
}
