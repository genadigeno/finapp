package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * The PSP's own word on whether the capture our reference names has cleared (`P15-TSK-036`, ADR-0083): the inquiry a
 * clearing notice triggers, asked over the provider credential. Only {@link Verdict#CLEARED} carries the network's
 * references, and only it records anything; the callback's own references are never read.
 *
 * <p>Total, like {@link QueryAnswer}: a 404, a 5xx and a truncated body are {@link Verdict#INDETERMINATE}, and a 200 nobody
 * can read is {@link Verdict#UNREADABLE}.
 *
 * <p><strong>The contract, and its limit:</strong> the PSP answers the operation's latest presentment. A PSP that answered only
 * the first would hide a second presentment from this inquiry; the settlement file's line is then what shows it.
 */
public record ClearingAnswer(
        Verdict verdict,
        Optional<ProviderReference> acquirerReference,
        Optional<ProviderReference> networkTransactionId,
        Optional<byte[]> evidence) {

    /** The PSP's view of the capture's clearing. Default branch: {@link #INDETERMINATE}. */
    public enum Verdict {
        /** The network cleared it. Carries both references. */
        CLEARED,
        /** The PSP knows the operation and it has not cleared. */
        NOT_CLEARED,
        /** The PSP explicitly answered that it never saw this reference. */
        UNRECOGNISED,
        /** A 200 body we cannot read: an unknown word, or a clearing missing a reference. Counted as a break. */
        UNREADABLE,
        /** No answer at all: a transport failure, a 404, a 5xx, a truncated body. Ask again. */
        INDETERMINATE
    }

    public ClearingAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(acquirerReference, "acquirerReference must not be null");
        Objects.requireNonNull(networkTransactionId, "networkTransactionId must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        boolean carriesBoth = acquirerReference.isPresent() && networkTransactionId.isPresent();
        if (verdict == Verdict.CLEARED && !carriesBoth) {
            throw new IllegalArgumentException("a CLEARED answer carries both network references");
        }
        if (verdict != Verdict.CLEARED && (acquirerReference.isPresent() || networkTransactionId.isPresent())) {
            throw new IllegalArgumentException("only a CLEARED answer carries network references");
        }
        evidence = evidence.map(byte[]::clone);
    }

    public static ClearingAnswer cleared(ProviderReference acquirer, ProviderReference network, byte[] evidence) {
        return new ClearingAnswer(Verdict.CLEARED, Optional.of(acquirer), Optional.of(network), Optional.of(evidence));
    }

    public static ClearingAnswer notCleared(byte[] evidence) {
        return new ClearingAnswer(Verdict.NOT_CLEARED, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    public static ClearingAnswer unrecognised(byte[] evidence) {
        return new ClearingAnswer(Verdict.UNRECOGNISED, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    public static ClearingAnswer unreadable(byte[] evidence) {
        return new ClearingAnswer(Verdict.UNREADABLE, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    public static ClearingAnswer indeterminate() {
        return new ClearingAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ClearingAnswer indeterminate(byte[] evidence) {
        return new ClearingAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    @Override
    public Optional<byte[]> evidence() {
        return evidence.map(byte[]::clone);
    }

    /** The verdict and whether bytes arrived - never the bytes ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "ClearingAnswer[" + verdict + ", evidence=" + evidence.isPresent() + "]";
    }
}
