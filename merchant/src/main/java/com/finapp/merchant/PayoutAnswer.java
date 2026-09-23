package com.finapp.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * What a send of our reference got back (`P6-TSK-012`): an enumerated verdict, the provider's
 * reference when it accepted, and the raw bytes that arrived, if any, as evidence.
 *
 * @param evidence the answer's bytes exactly as they arrived; empty when nothing did
 */
public record PayoutAnswer(
        Verdict verdict, Optional<PayoutProviderReference> providerReference, Optional<byte[]> evidence) {

    /** The four things a send can tell the platform. */
    public enum Verdict {
        /** The rail accepted irrevocably. */
        ACCEPTED,
        /** The rail refused. */
        DECLINED,
        /**
         * The connection was refused before anything was sent — knowledge, not ambiguity
         * (ADR-0046 §2). Carries no evidence: nothing arrived.
         */
        NOTHING_SENT,
        /** Anything else — a timeout, a 5xx, garbage, an unmapped word. Never success. */
        INDETERMINATE
    }

    public PayoutAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if ((verdict == Verdict.ACCEPTED) != providerReference.isPresent()) {
            throw new IllegalArgumentException(
                    "an acceptance carries the provider's reference, and nothing else does");
        }
        if (verdict == Verdict.NOTHING_SENT && evidence.isPresent()) {
            throw new IllegalArgumentException("nothing sent means nothing arrived");
        }
        evidence = evidence.map(byte[]::clone);
    }

    public static PayoutAnswer accepted(PayoutProviderReference theirs, byte[] received) {
        return new PayoutAnswer(Verdict.ACCEPTED, Optional.of(theirs), Optional.of(received));
    }

    public static PayoutAnswer declined(byte[] received) {
        return new PayoutAnswer(Verdict.DECLINED, Optional.empty(), Optional.of(received));
    }

    public static PayoutAnswer nothingSent() {
        return new PayoutAnswer(Verdict.NOTHING_SENT, Optional.empty(), Optional.empty());
    }

    public static PayoutAnswer indeterminate() {
        return new PayoutAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty());
    }

    public static PayoutAnswer indeterminate(byte[] received) {
        return new PayoutAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.of(received));
    }

    @Override
    public Optional<byte[]> evidence() {
        return evidence.map(byte[]::clone);
    }

    /** The verdict and whether bytes arrived — never the bytes. */
    @Override
    public String toString() {
        return "PayoutAnswer[" + verdict + ", evidence=" + evidence.isPresent() + "]";
    }
}
