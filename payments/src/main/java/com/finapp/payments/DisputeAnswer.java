package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * The PSP's own statement of the dispute its reference names (`P15-TSK-036`, ADR-0083): what a dispute notice triggers,
 * asked over the provider credential. {@link Verdict#FOUND} carries the statement already in our vocabulary
 * ({@code INV-PAY-03}: the stage word and reason code stop at the adapter) and <strong>the operation the PSP says the
 * dispute contests</strong>, so attribution comes from the authenticated channel too: a forged notice naming a genuine
 * dispute on another payment moves nothing.
 */
public record DisputeAnswer(
        Verdict verdict,
        Optional<ProviderIdempotencyReference> operation,
        Optional<DisputeNotice> notice,
        Optional<byte[]> evidence) {

    /** The PSP's view of the dispute. Default branch: {@link #INDETERMINATE}. */
    public enum Verdict {
        /** The dispute exists; the statement and its operation are carried. */
        FOUND,
        /** The PSP explicitly answered that it never saw this reference. */
        UNRECOGNISED,
        /** A 200 body we cannot read: a new stage word, a changed amount or deadline format. Counted as a break. */
        UNREADABLE,
        /** No answer at all: a transport failure, a 404, a 5xx, a truncated body. Ask again. */
        INDETERMINATE
    }

    public DisputeAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(notice, "notice must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (verdict == Verdict.FOUND && (operation.isEmpty() || notice.isEmpty())) {
            throw new IllegalArgumentException("a FOUND answer carries its operation and its statement");
        }
        if (verdict != Verdict.FOUND && (operation.isPresent() || notice.isPresent())) {
            throw new IllegalArgumentException("only a FOUND answer carries a statement");
        }
        evidence = evidence.map(byte[]::clone);
    }

    public static DisputeAnswer found(ProviderIdempotencyReference operation, DisputeNotice notice, byte[] evidence) {
        return new DisputeAnswer(Verdict.FOUND, Optional.of(operation), Optional.of(notice), Optional.of(evidence));
    }

    public static DisputeAnswer unrecognised(byte[] evidence) {
        return new DisputeAnswer(Verdict.UNRECOGNISED, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    public static DisputeAnswer unreadable(byte[] evidence) {
        return new DisputeAnswer(Verdict.UNREADABLE, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    public static DisputeAnswer indeterminate() {
        return new DisputeAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static DisputeAnswer indeterminate(byte[] evidence) {
        return new DisputeAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty(), Optional.of(evidence));
    }

    @Override
    public Optional<byte[]> evidence() {
        return evidence.map(byte[]::clone);
    }

    /** The verdict and whether bytes arrived - never the amount or the bytes ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "DisputeAnswer[" + verdict + ", evidence=" + evidence.isPresent() + "]";
    }
}
