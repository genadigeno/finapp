package com.finapp.paymentmethods;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A tokenised instrument attached to a Party (`P5-TSK-004`, {@code PHASE_5_PLAN.md} §8) — the
 * PCI boundary's subject ({@code INV-PAY-02}): what the platform holds <em>instead of</em> card
 * data, plus exactly what a person needs to recognise it in a list.
 *
 * <p><strong>Owned by a Party, not a Customer, and by a raw {@code UUID}, deliberately.</strong>
 * A saved instrument is a person's and outlives any one commercial relationship — the
 * {@code Beneficiary}/consent-record ownership argument verbatim — and {@code party} owns the
 * typed identifier this module cannot see. No cross-schema FK (ADR-0029's rule). No
 * verification gate, also deliberately: an instrument reference is a Party's convenience like a
 * saved destination; money-gating happens at the intent, where the wallet and the verified
 * customer are resolved (`P5-TSK-009`).
 *
 * <p><strong>Every display field is validated into a shape that cannot carry a PAN</strong>,
 * which is what makes {@code INV-PAY-02} a property of the types rather than of everyone's
 * care: the brand's charset holds no digits at all; the display suffix is <em>exactly four</em>
 * digits — last4 is displayable by PCI's own definition, and four digits is the most instrument
 * this aggregate can ever disclose; the expiry is two bounded integers; and the token refuses
 * PAN-shaped values ({@link TokenReference}). {@code V002} carries each rule at
 * {@code DB-CONSTRAINT} rank for every writer.
 *
 * <p><strong>Expiry is month AND year</strong> — the plan's §8 shorthand ("expiry month") is
 * corrected on being met, because a month without a year is not display metadata anyone can
 * render; recorded with provenance rather than silently widened.
 */
public record PaymentMethod(
        PaymentMethodId id,
        UUID partyId,
        TokenReference token,
        String brand,
        String displaySuffix,
        int expiryMonth,
        int expiryYear,
        PaymentMethodStatus status,
        Instant createdAt,
        Optional<Instant> detachedAt) {

    /** Matches {@code V002}'s length CHECK, so a value that constructs here always stores. */
    public static final int MAX_BRAND_LENGTH = 30;

    /** Letters and spaces only — a charset that structurally cannot carry a digit of a PAN. */
    private static final Pattern BRAND_SHAPE = Pattern.compile("[A-Za-z][A-Za-z ]{0,29}");

    /** Exactly four digits: last4 is the most instrument this schema ever discloses. */
    private static final Pattern SUFFIX_SHAPE = Pattern.compile("[0-9]{4}");

    /** The year bounds are sanity, not policy: outside them is corrupt data, not an old card. */
    public static final int MIN_EXPIRY_YEAR = 2000;

    public static final int MAX_EXPIRY_YEAR = 2100;

    public PaymentMethod {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(brand, "brand must not be null");
        Objects.requireNonNull(displaySuffix, "displaySuffix must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(detachedAt, "detachedAt must not be null");
        if (!BRAND_SHAPE.matcher(brand).matches()) {
            throw new IllegalArgumentException(
                    "a brand must be 1-"
                            + MAX_BRAND_LENGTH
                            + " letters and spaces, starting with a letter");
        }
        if (!SUFFIX_SHAPE.matcher(displaySuffix).matches()) {
            throw new IllegalArgumentException(
                    "a display suffix is exactly four digits - last4, and nothing more of the"
                            + " instrument is ever disclosed (INV-PAY-02)");
        }
        if (expiryMonth < 1 || expiryMonth > 12) {
            throw new IllegalArgumentException("an expiry month must be 1-12");
        }
        if (expiryYear < MIN_EXPIRY_YEAR || expiryYear > MAX_EXPIRY_YEAR) {
            throw new IllegalArgumentException(
                    "an expiry year must be "
                            + MIN_EXPIRY_YEAR
                            + "-"
                            + MAX_EXPIRY_YEAR);
        }
        // Coherence both directions (INV-LIFE-02's shape): the status and the detachment
        // instant are one fact, refused on read-back too - defence in depth ahead of V002's
        // CHECK.
        if (status == PaymentMethodStatus.DETACHED && detachedAt.isEmpty()) {
            throw new IllegalArgumentException(
                    "a detached payment method records when it was detached");
        }
        if (status == PaymentMethodStatus.ACTIVE && detachedAt.isPresent()) {
            throw new IllegalArgumentException("a live payment method has no detachment instant");
        }
        if (detachedAt.isPresent() && detachedAt.get().isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "a payment method cannot be detached before it exists");
        }
    }

    /** A newly attached instrument: {@code ACTIVE} from birth — attached is the only way in. */
    public static PaymentMethod attach(
            PaymentMethodId id,
            UUID partyId,
            TokenReference token,
            String brand,
            String displaySuffix,
            int expiryMonth,
            int expiryYear,
            Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new PaymentMethod(
                id,
                partyId,
                token,
                brand,
                displaySuffix,
                expiryMonth,
                expiryYear,
                PaymentMethodStatus.ACTIVE,
                Instant.now(clock),
                Optional.empty());
    }

    /** A stored row, already validated by the schema; the constructor re-judges coherence. */
    public static PaymentMethod rehydrate(
            PaymentMethodId id,
            UUID partyId,
            TokenReference token,
            String brand,
            String displaySuffix,
            int expiryMonth,
            int expiryYear,
            PaymentMethodStatus status,
            Instant createdAt,
            Instant detachedAt) {
        return new PaymentMethod(
                id,
                partyId,
                token,
                brand,
                displaySuffix,
                expiryMonth,
                expiryYear,
                status,
                createdAt,
                Optional.ofNullable(detachedAt));
    }

    /**
     * The one transition: {@code ACTIVE → DETACHED}.
     *
     * @throws IllegalPaymentMethodTransitionException from any other state
     *     ({@code INV-LIFE-02}, {@code INV-LIFE-04}) — the aggregate refuses, not merely the
     *     store's conditional
     */
    public PaymentMethod detach(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(PaymentMethodStatus.DETACHED)) {
            throw new IllegalPaymentMethodTransitionException(
                    id, status, PaymentMethodStatus.DETACHED);
        }
        return new PaymentMethod(
                id,
                partyId,
                token,
                brand,
                displaySuffix,
                expiryMonth,
                expiryYear,
                PaymentMethodStatus.DETACHED,
                createdAt,
                Optional.of(Instant.now(clock)));
    }

    /**
     * Names the method and its status, never the token ({@code INV-AUD-02}): a record's
     * generated {@code toString} prints every component — the wrapped token would mask itself,
     * but overriding is the stated rule rather than an inherited accident (the
     * {@code P0-TSK-030} accidental-safety lesson).
     */
    @Override
    public String toString() {
        return "PaymentMethod[id=" + id + ", status=" + status + "]";
    }
}
