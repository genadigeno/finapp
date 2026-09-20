package com.finapp.paymentmethods;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The tokenised instrument reference this module exists to hold (`P5-TSK-004`,
 * {@code INV-PAY-02}).
 *
 * <p>The {@code InstrumentToken} mechanism restated, <strong>not imported</strong> — this
 * module sees no business sibling in either direction ({@code payments} included, by the PCI
 * build-graph decision), so the wrapped-token discipline is restated the way
 * {@code DocumentCipher} restates {@code SecretCipher}'s: the mechanism is the platform's, the
 * class is the boundary's own, and the duplication is the recorded cost of the isolation.
 *
 * <h2>Why the component is named {@code secret}</h2>
 *
 * <p>The {@code RawPassword} idiom: {@code secretsAreWrapped} matches field-name words against
 * its vocabulary, so naming the component accurately makes the <strong>existing</strong> rule
 * enforce the wrapping for ever. A token in a log line is a precise identifier of one
 * customer's instrument (the {@code Session.tokenHash} precedent).
 *
 * <h2>One rule the in-flight twin does not need: a PAN-shaped value is refused</h2>
 *
 * <p>The charset ({@code [A-Za-z0-9_-]}, ≤ 128) admits a bare card number, so this type adds
 * the refusal that makes {@code INV-PAY-02} a property of the type rather than of everyone's
 * care: <strong>a value consisting only of digits is not a token</strong> — no tokenisation
 * provider mints one, and a 13–19 digit value is indistinguishable from the card number this
 * module exists to never hold. {@code V002} carries the same rule at {@code DB-CONSTRAINT}
 * rank, so a PAN cannot physically be stored by any writer. The refusal names the rule and
 * never the value ({@code INV-AUD-02}).
 *
 * <p>{@link #expose()} is the one way the value comes off, registered as an unwrapping method
 * in {@code SecretsAreUnwrappedInOnePlaceTest}, so every caller is a named, justified entry —
 * the store writing its column being the one production caller this task ships.
 */
public record TokenReference(Sensitive<String> secret) {

    /** Matches {@code V002}'s bound, so a value that constructs here always stores. */
    public static final int MAX_LENGTH = 128;

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_LENGTH + "}");

    /**
     * Digits alone, or digits with hyphen separators — the shapes a card number is written in.
     * The charset admits hyphens (real token formats use them), so the refusal must cover the
     * separated form too: {@code 4111-1111-1111-1111} is a formatted card number, not a token.
     * (Named for what it matches: {@code secretsAreWrapped} rightly refuses a card-number word
     * on an unwrapped field, and a {@code Pattern} holding a shape rule is not a secret — the
     * accurate-rename answer, the {@code REQUIRED_ENVIRONMENT_VARIABLE} precedent.)
     */
    private static final Pattern DIGITS_AND_SEPARATORS = Pattern.compile("[0-9-]+");

    public TokenReference {
        Objects.requireNonNull(secret, "the token reference must not be null");
        String value = secret.expose();
        Objects.requireNonNull(value, "the token reference must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a token reference must be 1-" + MAX_LENGTH + " characters of [A-Za-z0-9_-]");
        }
        if (DIGITS_AND_SEPARATORS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a token reference consisting only of digits and separators is refused: it"
                            + " is indistinguishable from a card number, which this module"
                            + " exists to never hold (INV-PAY-02)");
        }
    }

    public static TokenReference of(String value) {
        return new TokenReference(Sensitive.of(value));
    }

    /** The bare token, for the store's column and nowhere else. */
    public String expose() {
        return secret.expose();
    }
}
