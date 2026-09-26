package com.finapp.paymentmethods;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The rail provider's opaque reference to a customer's external bank account
 * (`P7-TSK-007`, ADR-0062 §2) — what the platform holds <em>instead of</em> bank details, the
 * {@link TokenReference} doctrine applied to {@code INV-RAIL-03}'s subject.
 *
 * <p>The wrapped-reference mechanism is restated, not imported (the PCI build-graph decision
 * {@link TokenReference} records: this module sees no business sibling in either direction),
 * and the component is named {@code secret} for the same reason as the token's: the
 * {@code secretsAreWrapped} vocabulary makes the wrapping permanent, and a destination
 * reference in a log line is a precise identifier of one customer's bank account.
 *
 * <h2>The three {@code INV-RAIL-03} refusals, and their recorded limits</h2>
 *
 * <p>The charset is {@code ProviderReference}'s own ({@code [A-Za-z0-9_.:-]}, ≤ 128), so every
 * answer a rail adapter may lawfully hand the platform is storable. Within it:
 *
 * <ul>
 *   <li><strong>A value with no letter is refused</strong> — an account number, a sort-coded
 *       account string and a phone number are all digits and separators however they are
 *       punctuated, and no provider mints an opaque reference containing no letter at all.
 *   <li><strong>The international-identifier shape is refused</strong> — two letters, two
 *       digits, then alphanumerics, at most 34 characters in all (an IBAN's own bound; longer
 *       values cannot be one and stay legal). Recorded limit: a 32-hex reference that happens
 *       to start letter-letter-digit-digit collides with this shape, so <em>an adapter whose
 *       scheme mints colliding references must prefix them</em> (for example {@code dest:}) —
 *       the adapter-contract cost of making {@code INV-RAIL-03} structural, judged worth it
 *       exactly as {@code TokenReference}'s digits refusal was.
 * </ul>
 *
 * <p>{@code V003} carries all three rules at {@code DB-CONSTRAINT} rank for every writer.
 * The refusals name the rule and never the value ({@code INV-AUD-02}).
 *
 * <p>{@link #expose()} is the one way the value comes off, registered in
 * {@code SecretsAreUnwrappedInOnePlaceTest}; the store writing its column and binding its
 * converge read are the production callers this task ships.
 */
public record DestinationReference(Sensitive<String> secret) {

    /** Matches {@code V003}'s bound — {@code ProviderReference}'s, so every lawful exchange
     * answer constructs here and every value that constructs here stores. */
    public static final int MAX_LENGTH = 128;

    private static final Pattern SHAPE =
            Pattern.compile("[A-Za-z0-9_.:-]{1," + MAX_LENGTH + "}");

    /** No letter anywhere: the shape of an account number, sort code or phone number. */
    private static final Pattern NO_LETTERS = Pattern.compile("[0-9_.:-]+");

    /** An IBAN is at most 34 characters; beyond that, nothing is one. */
    public static final int MAX_INTERNATIONAL_IDENTIFIER_LENGTH = 34;

    private static final Pattern INTERNATIONAL_IDENTIFIER_SHAPE =
            Pattern.compile("[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{1,30}");

    public DestinationReference {
        Objects.requireNonNull(secret, "the destination reference must not be null");
        String value = secret.expose();
        Objects.requireNonNull(value, "the destination reference must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a destination reference must be 1-"
                            + MAX_LENGTH
                            + " characters of [A-Za-z0-9_.:-]");
        }
        if (NO_LETTERS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a destination reference containing no letter is refused: digits and"
                            + " separators are the shapes account numbers and phone numbers are"
                            + " written in, which this module exists to never hold"
                            + " (INV-RAIL-03)");
        }
        if (value.length() <= MAX_INTERNATIONAL_IDENTIFIER_LENGTH
                && INTERNATIONAL_IDENTIFIER_SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a destination reference in the international account identifier shape is"
                            + " refused (INV-RAIL-03); a scheme whose opaque references collide"
                            + " with it must prefix them at the adapter");
        }
    }

    public static DestinationReference of(String value) {
        return new DestinationReference(Sensitive.of(value));
    }

    /** The bare reference, for the store's column and nowhere else. */
    public String expose() {
        return secret.expose();
    }
}
