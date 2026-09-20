package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * The provider's answer to a money-moving dispatch, normalised — plus whatever bytes actually
 * arrived, verbatim (`P5-TSK-003`, `INV-PAY-03`, `INV-HIST-02`).
 *
 * <h2>The verdict IS the total mapping</h2>
 *
 * <p>Four values, and each is exactly one edge of the attempt machine
 * ({@code PAYMENT_LIFECYCLES.md} §3): {@link Verdict#APPROVED} and {@link Verdict#DECLINED} are
 * knowledge from a parsed answer; {@link Verdict#NOTHING_SENT} is knowledge of the other kind —
 * the connection was refused before anything was transmitted, so the operation cannot have
 * happened and the dispatch fails as {@code FAILED(PROVIDER_UNAVAILABLE)}; and
 * {@link Verdict#INDETERMINATE} is everything else — <em>we do not know</em> is an answer
 * ({@code INV-LIFE-03}), and the caller commits an {@code *_UNKNOWN} state, never a guess. A
 * finer decline taxonomy is deliberately absent: it would be reason vocabulary with no consumer
 * (ADR-0044's doctrine applied to reasons), and the provider's own code lives where
 * {@code INV-PAY-03} puts it — in the retained evidence.
 *
 * <h2>Coherence the constructor enforces</h2>
 *
 * <p>{@code APPROVED} requires the provider's reference — an "approved" the next operation
 * cannot act on (capture needs the authorization's id; refund needs the capture's) is an answer
 * we cannot use, and the adapter maps it to {@code INDETERMINATE} instead. The reference is
 * present <em>only</em> with {@code APPROVED}: on a decline the provider's id is evidence, not
 * something the caller presents onward. {@code NOTHING_SENT} carries nothing, structurally —
 * nothing arrived.
 *
 * <p>Evidence is absent only when nothing arrived (a refused connection, a timeout, bytes that
 * were not HTTP). <strong>Anything received is retained</strong>, malformed and 5xx included:
 * the unparseable answer is precisely what an investigation of the provider wants
 * ({@code INV-HIST-02}); the caller persists it (`P5-TSK-008`'s evidence table).
 */
public record ProviderAnswer(
        Verdict verdict, Optional<ProviderReference> providerReference, Optional<byte[]> evidence) {

    /** The normalised outcome of a dispatch. The default branch of every mapping is {@link #INDETERMINATE}. */
    public enum Verdict {
        /** A parsed answer: the provider performed the operation. Carries its reference. */
        APPROVED,
        /** A parsed answer: the provider refused the operation. Maps to {@code FAILED(DECLINED)}. */
        DECLINED,
        /**
         * The connection was refused before anything was transmitted — knowledge, not ambiguity
         * ({@code PAYMENT_LIFECYCLES.md} §3): maps to {@code FAILED(PROVIDER_UNAVAILABLE)}.
         */
        NOTHING_SENT,
        /** Everything else. The caller commits {@code *_UNKNOWN}, never a guess ({@code INV-LIFE-03}). */
        INDETERMINATE
    }

    public ProviderAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (verdict == Verdict.APPROVED && providerReference.isEmpty()) {
            throw new IllegalArgumentException(
                    "an APPROVED answer must carry the provider's reference - an approval the"
                            + " next operation cannot act on is INDETERMINATE, not APPROVED");
        }
        if (verdict != Verdict.APPROVED && providerReference.isPresent()) {
            throw new IllegalArgumentException(
                    "only an APPROVED answer carries a provider reference - anywhere else the"
                            + " provider's id is evidence, not something to act on");
        }
        if (verdict == Verdict.NOTHING_SENT && evidence.isPresent()) {
            throw new IllegalArgumentException(
                    "a NOTHING_SENT answer cannot carry evidence - nothing arrived");
        }
        evidence = evidence.map(byte[]::clone);
    }

    public static ProviderAnswer approved(ProviderReference reference, byte[] evidence) {
        return new ProviderAnswer(Verdict.APPROVED, Optional.of(reference), Optional.of(evidence));
    }

    public static ProviderAnswer declined(byte[] evidence) {
        return new ProviderAnswer(Verdict.DECLINED, Optional.empty(), Optional.of(evidence));
    }

    public static ProviderAnswer nothingSent() {
        return new ProviderAnswer(Verdict.NOTHING_SENT, Optional.empty(), Optional.empty());
    }

    public static ProviderAnswer indeterminate() {
        return new ProviderAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty());
    }

    public static ProviderAnswer indeterminate(byte[] evidence) {
        return new ProviderAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.of(evidence));
    }

    @Override
    public Optional<byte[]> evidence() {
        return evidence.map(byte[]::clone);
    }

    /** The verdict and whether bytes arrived — never the bytes ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "ProviderAnswer["
                + verdict
                + providerReference.map(reference -> ", " + reference.value()).orElse("")
                + ", evidence="
                + evidence.isPresent()
                + "]";
    }
}
