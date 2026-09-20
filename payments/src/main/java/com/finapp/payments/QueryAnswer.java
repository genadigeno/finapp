package com.finapp.payments;

import java.util.Objects;
import java.util.Optional;

/**
 * The provider's answer to a resolution query keyed on <strong>our</strong> idempotency
 * reference (`P5-TSK-003`, ADR-0046).
 *
 * <p>A query is how an {@code *_UNKNOWN} or stranded {@code *_DISPATCHED} state resolves
 * ({@code PAYMENT_LIFECYCLES.md} §7): read-only, idempotent, safe for every instance to race.
 * Its vocabulary differs from {@link ProviderAnswer}'s in exactly two places, each deliberate:
 *
 * <ul>
 *   <li>{@link Verdict#UNRECOGNISED} exists — the provider <em>explicitly answering</em> that it
 *       never saw this reference, which is the sweeper's licence to resolve a stranded dispatch
 *       to {@code FAILED}. <strong>Only an explicit parsed answer earns it</strong>: a 404 maps
 *       to {@link Verdict#INDETERMINATE}, because a misrouted load balancer's 404 reading as
 *       "the operation never happened" is the expensive direction — resolving to {@code FAILED}
 *       an operation the provider in fact performed.
 *   <li>{@code NOTHING_SENT} does not — a query that never reached the provider changes nothing
 *       and is asked again; the distinction only pays where a caller acts on it.
 * </ul>
 *
 * <p>{@code APPROVED} requires the provider's reference for {@link ProviderAnswer}'s reason: a
 * resolution to {@code AUTHORIZED} that cannot name the authorization leaves the capture
 * undispatchable. Same coherence, same rule.
 */
public record QueryAnswer(
        Verdict verdict, Optional<ProviderReference> providerReference, Optional<byte[]> evidence) {

    /** The provider's view of the operation our reference names. Default branch: {@link #INDETERMINATE}. */
    public enum Verdict {
        /** The operation happened. Carries the provider's reference. */
        APPROVED,
        /** The operation was refused. */
        DECLINED,
        /** The provider explicitly answered that it never saw this reference. */
        UNRECOGNISED,
        /** Everything else — including a 404, which is a status code and not an answer. */
        INDETERMINATE
    }

    public QueryAnswer {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (verdict == Verdict.APPROVED && providerReference.isEmpty()) {
            throw new IllegalArgumentException(
                    "an APPROVED query answer must carry the provider's reference - a resolution"
                            + " the next operation cannot act on is INDETERMINATE, not APPROVED");
        }
        if (verdict != Verdict.APPROVED && providerReference.isPresent()) {
            throw new IllegalArgumentException(
                    "only an APPROVED query answer carries a provider reference");
        }
        evidence = evidence.map(byte[]::clone);
    }

    public static QueryAnswer approved(ProviderReference reference, byte[] evidence) {
        return new QueryAnswer(Verdict.APPROVED, Optional.of(reference), Optional.of(evidence));
    }

    public static QueryAnswer declined(byte[] evidence) {
        return new QueryAnswer(Verdict.DECLINED, Optional.empty(), Optional.of(evidence));
    }

    public static QueryAnswer unrecognised(byte[] evidence) {
        return new QueryAnswer(Verdict.UNRECOGNISED, Optional.empty(), Optional.of(evidence));
    }

    public static QueryAnswer indeterminate() {
        return new QueryAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.empty());
    }

    public static QueryAnswer indeterminate(byte[] evidence) {
        return new QueryAnswer(Verdict.INDETERMINATE, Optional.empty(), Optional.of(evidence));
    }

    @Override
    public Optional<byte[]> evidence() {
        return evidence.map(byte[]::clone);
    }

    /** The verdict and whether bytes arrived — never the bytes ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "QueryAnswer["
                + verdict
                + providerReference.map(reference -> ", " + reference.value()).orElse("")
                + ", evidence="
                + evidence.isPresent()
                + "]";
    }
}
