package com.finapp.paymentmethods;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An instrument attached to a Party (`P5-TSK-004`, extended per kind by `P7-TSK-007`) — what
 * the platform holds <em>instead of</em> the instrument, plus exactly what a person needs to
 * recognise it in a list. Two kinds, one aggregate, one lifecycle:
 *
 * <ul>
 *   <li>{@link PaymentMethodKind#CARD_TOKEN} — the PCI boundary's subject ({@code INV-PAY-02}):
 *       token, brand, expiry.
 *   <li>{@link PaymentMethodKind#BANK_ACCOUNT} — {@code INV-RAIL-03}'s subject (ADR-0062 §2):
 *       the opaque destination reference, the confirmation-of-payee word, and — for
 *       {@code NO_MATCH} only — the instant of the customer's recorded acknowledgement.
 * </ul>
 *
 * <p><strong>The kind is a frozen birth fact and every per-kind rule keys on it</strong> (the
 * payments attempt's per-model discipline at the instrument rank): the one constructor refuses
 * a foreign kind's facts in both directions, so no writer can dress a bank row in card facts
 * or vice versa; {@code V003} carries the same coherence at {@code DB-CONSTRAINT} rank.
 *
 * <p><strong>Owned by a Party, not a Customer, and by a raw {@code UUID}, deliberately.</strong>
 * A saved instrument is a person's and outlives any one commercial relationship — the
 * {@code Beneficiary}/consent-record ownership argument verbatim — and {@code party} owns the
 * typed identifier this module cannot see. No cross-schema FK (ADR-0029's rule). No
 * verification gate, also deliberately: an instrument reference is a Party's convenience like a
 * saved destination; money-gating happens at the intent, where the wallet and the verified
 * customer are resolved (`P5-TSK-009`).
 *
 * <p><strong>Every display field is validated into a shape that cannot carry an
 * identifier</strong>: the brand's charset holds no digits at all; the display suffix is
 * exactly four characters — digits for a card (last4, displayable by PCI's own definition),
 * alphanumerics for a bank account (the exchange's own bound) — and four characters is the
 * most instrument this aggregate can ever disclose; the expiry is two bounded integers; the
 * token refuses PAN-shaped values ({@link TokenReference}); the destination refuses
 * account-number-, international-identifier- and phone-shaped values
 * ({@link DestinationReference}).
 *
 * <p><strong>Expiry is month AND year</strong> — the plan's §8 shorthand ("expiry month") was
 * corrected on being met (`P5-TSK-004`); recorded with provenance rather than silently
 * widened.
 */
public record PaymentMethod(
        PaymentMethodId id,
        UUID partyId,
        PaymentMethodKind kind,
        Optional<TokenReference> token,
        Optional<String> brand,
        String displaySuffix,
        Optional<Integer> expiryMonth,
        Optional<Integer> expiryYear,
        Optional<DestinationReference> destination,
        Optional<PayeeCheck> payeeCheck,
        Optional<Instant> noMatchAcknowledgedAt,
        PaymentMethodStatus status,
        Instant createdAt,
        Optional<Instant> detachedAt) {

    /** Matches {@code V002}'s length CHECK, so a value that constructs here always stores. */
    public static final int MAX_BRAND_LENGTH = 30;

    /** Letters and spaces only — a charset that structurally cannot carry a digit of a PAN. */
    private static final Pattern BRAND_SHAPE = Pattern.compile("[A-Za-z][A-Za-z ]{0,29}");

    /** Exactly four digits: last4 is the most card this schema ever discloses. */
    private static final Pattern CARD_SUFFIX_SHAPE = Pattern.compile("[0-9]{4}");

    /** Exactly four alphanumerics — the exchange's own bound (ADR-0062 §2), and still
     * structurally unable to carry an account number. */
    private static final Pattern BANK_SUFFIX_SHAPE = Pattern.compile("[A-Za-z0-9]{4}");

    /** The year bounds are sanity, not policy: outside them is corrupt data, not an old card. */
    public static final int MIN_EXPIRY_YEAR = 2000;

    public static final int MAX_EXPIRY_YEAR = 2100;

    public PaymentMethod {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(brand, "brand must not be null");
        Objects.requireNonNull(displaySuffix, "displaySuffix must not be null");
        Objects.requireNonNull(expiryMonth, "expiryMonth must not be null");
        Objects.requireNonNull(expiryYear, "expiryYear must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(payeeCheck, "payeeCheck must not be null");
        Objects.requireNonNull(
                noMatchAcknowledgedAt, "noMatchAcknowledgedAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(detachedAt, "detachedAt must not be null");

        // The kind's coherence, both directions for both families (V003's CHECKs at domain
        // rank): a card is exactly its card facts, a bank account exactly its bank facts.
        boolean card = kind == PaymentMethodKind.CARD_TOKEN;
        if (card != token.isPresent()
                || card != brand.isPresent()
                || card != expiryMonth.isPresent()
                || card != expiryYear.isPresent()) {
            throw new IllegalArgumentException(
                    "a " + kind + " payment method carries the card facts exactly when it is a"
                            + " card: token, brand and expiry are CARD_TOKEN's, and only its");
        }
        boolean bank = kind == PaymentMethodKind.BANK_ACCOUNT;
        if (bank != destination.isPresent() || bank != payeeCheck.isPresent()) {
            throw new IllegalArgumentException(
                    "a " + kind + " payment method carries the bank facts exactly when it is a"
                            + " bank account: destination and payee check are BANK_ACCOUNT's,"
                            + " and only its");
        }
        // The consent fact is one fact with the check's word (ADR-0062 §2): an unacknowledged
        // NO_MATCH instrument cannot exist, and an acknowledgement of anything else is noise.
        boolean noMatch = payeeCheck.filter(check -> check == PayeeCheck.NO_MATCH).isPresent();
        if (noMatch != noMatchAcknowledgedAt.isPresent()) {
            throw new IllegalArgumentException(
                    "a NO_MATCH payee check is recorded exactly with the customer's"
                            + " acknowledgement instant, and no other result carries one"
                            + " (INV-RAIL-03's consent rule)");
        }

        if (brand.isPresent() && !BRAND_SHAPE.matcher(brand.get()).matches()) {
            throw new IllegalArgumentException(
                    "a brand must be 1-"
                            + MAX_BRAND_LENGTH
                            + " letters and spaces, starting with a letter");
        }
        Pattern suffixShape = card ? CARD_SUFFIX_SHAPE : BANK_SUFFIX_SHAPE;
        if (!suffixShape.matcher(displaySuffix).matches()) {
            throw new IllegalArgumentException(
                    card
                            ? "a card display suffix is exactly four digits - last4, and nothing"
                                    + " more of the instrument is ever disclosed (INV-PAY-02)"
                            : "a bank display suffix is exactly four alphanumerics - the"
                                    + " exchange's own bound, and nothing more of the account is"
                                    + " ever disclosed (INV-RAIL-03)");
        }
        if (expiryMonth.isPresent() && (expiryMonth.get() < 1 || expiryMonth.get() > 12)) {
            throw new IllegalArgumentException("an expiry month must be 1-12");
        }
        if (expiryYear.isPresent()
                && (expiryYear.get() < MIN_EXPIRY_YEAR || expiryYear.get() > MAX_EXPIRY_YEAR)) {
            throw new IllegalArgumentException(
                    "an expiry year must be " + MIN_EXPIRY_YEAR + "-" + MAX_EXPIRY_YEAR);
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

    /** A newly attached card: {@code ACTIVE} from birth — attached is the only way in. */
    public static PaymentMethod attachCard(
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
                PaymentMethodKind.CARD_TOKEN,
                Optional.of(token),
                Optional.of(brand),
                displaySuffix,
                Optional.of(expiryMonth),
                Optional.of(expiryYear),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                PaymentMethodStatus.ACTIVE,
                Instant.now(clock),
                Optional.empty());
    }

    /**
     * A newly registered bank account (`P7-TSK-007`): exactly the grant exchange's three
     * storable values, {@code ACTIVE} from birth.
     *
     * <p>{@code noMatchAcknowledged} is the customer's "register even though the name did not
     * match": required exactly when the check said {@link PayeeCheck#NO_MATCH} — this factory
     * refuses an unacknowledged {@code NO_MATCH} as defence in depth behind the surface's 409 —
     * and vacuous otherwise (nothing is recorded; consent to a mismatch that did not happen is
     * not a fact).
     *
     * @throws IllegalArgumentException when the check is {@code NO_MATCH} and the customer has
     *     not acknowledged it (ADR-0062 §2's consent rule)
     */
    public static PaymentMethod registerBankAccount(
            PaymentMethodId id,
            UUID partyId,
            DestinationReference destination,
            String displaySuffix,
            PayeeCheck payeeCheck,
            boolean noMatchAcknowledged,
            Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(payeeCheck, "payeeCheck must not be null");
        if (payeeCheck == PayeeCheck.NO_MATCH && !noMatchAcknowledged) {
            throw new IllegalArgumentException(
                    "a NO_MATCH payee check is registered only with the customer's explicit"
                            + " acknowledgement (ADR-0062 section 2)");
        }
        Instant now = Instant.now(clock);
        return new PaymentMethod(
                id,
                partyId,
                PaymentMethodKind.BANK_ACCOUNT,
                Optional.empty(),
                Optional.empty(),
                displaySuffix,
                Optional.empty(),
                Optional.empty(),
                Optional.of(destination),
                Optional.of(payeeCheck),
                payeeCheck == PayeeCheck.NO_MATCH ? Optional.of(now) : Optional.empty(),
                PaymentMethodStatus.ACTIVE,
                now,
                Optional.empty());
    }

    /** A stored row, already validated by the schema; the constructor re-judges coherence. */
    public static PaymentMethod rehydrate(
            PaymentMethodId id,
            UUID partyId,
            PaymentMethodKind kind,
            TokenReference token,
            String brand,
            String displaySuffix,
            Integer expiryMonth,
            Integer expiryYear,
            DestinationReference destination,
            PayeeCheck payeeCheck,
            Instant noMatchAcknowledgedAt,
            PaymentMethodStatus status,
            Instant createdAt,
            Instant detachedAt) {
        return new PaymentMethod(
                id,
                partyId,
                kind,
                Optional.ofNullable(token),
                Optional.ofNullable(brand),
                displaySuffix,
                Optional.ofNullable(expiryMonth),
                Optional.ofNullable(expiryYear),
                Optional.ofNullable(destination),
                Optional.ofNullable(payeeCheck),
                Optional.ofNullable(noMatchAcknowledgedAt),
                status,
                createdAt,
                Optional.ofNullable(detachedAt));
    }

    /**
     * The one transition: {@code ACTIVE → DETACHED}, kind-agnostic.
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
        // GREATEST(now(), created_at) - the P1-TSK-031 drift, met in domain code rather than a
        // fixture: the clock that wrote createdAt may read ahead of this one (another
        // instance's, or this one stepped back by time sync; ADR-0014: skew is bounded, never
        // zero), and a legal detach moments after attaching must not die on the ordering the
        // constructor and `V002`'s CHECK are right to refuse. The store's conditional clamps
        // the same way, in its statement.
        Instant now = Instant.now(clock);
        return new PaymentMethod(
                id,
                partyId,
                kind,
                token,
                brand,
                displaySuffix,
                expiryMonth,
                expiryYear,
                destination,
                payeeCheck,
                noMatchAcknowledgedAt,
                PaymentMethodStatus.DETACHED,
                createdAt,
                Optional.of(now.isBefore(createdAt) ? createdAt : now));
    }

    /**
     * Names the method, its kind and its status, never a reference ({@code INV-AUD-02}): a
     * record's generated {@code toString} prints every component — the wrapped values would
     * mask themselves, but overriding is the stated rule rather than an inherited accident
     * (the {@code P0-TSK-030} accidental-safety lesson).
     */
    @Override
    public String toString() {
        return "PaymentMethod[id=" + id + ", kind=" + kind + ", status=" + status + "]";
    }
}
