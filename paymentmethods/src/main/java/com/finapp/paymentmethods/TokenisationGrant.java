package com.finapp.paymentmethods;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one-time client token an attach exchanges for a stored instrument (`P5-TSK-005`).
 *
 * <p>The client tokenises the card <em>outside</em> the platform (the standard client-side
 * model — {@code INV-PAY-02}'s whole point) and hands us only this grant; the exchange turns
 * it into the permanent {@link TokenReference} plus provider-sourced display metadata. It
 * travels wrapped end to end — the {@code Sensitive} deserialiser at the boundary, this type
 * in the domain, one unwrap at the wire — because a grant in a log is a chargeable-instrument
 * reference for its validity window.
 *
 * <h2>The digit-shape refusal is the surface's own {@code INV-PAY-02} control</h2>
 *
 * <p>The attach request is the one place a careless client could send a <em>card number</em>
 * where a token belongs, and this constructor is where that is refused: a value of digits and
 * separators is not a grant, and refusing it here means raw instrument data is turned away at
 * the boundary — before any exchange, any log line, any store. The refusal names the rule and
 * never the value ({@code INV-AUD-02}).
 */
public record TokenisationGrant(Sensitive<String> secret) {

    public static final int MAX_LENGTH = 128;

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_LENGTH + "}");

    /** Digits alone or with hyphen separators — the shapes a card number is written in. */
    private static final Pattern DIGITS_AND_SEPARATORS = Pattern.compile("[0-9-]+");

    public TokenisationGrant {
        Objects.requireNonNull(secret, "the tokenisation grant must not be null");
        String value = secret.expose();
        Objects.requireNonNull(value, "the tokenisation grant must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a tokenisation grant must be 1-"
                            + MAX_LENGTH
                            + " characters of [A-Za-z0-9_-]");
        }
        if (DIGITS_AND_SEPARATORS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a tokenisation grant consisting only of digits and separators is refused:"
                            + " it is indistinguishable from a card number, which never enters"
                            + " the platform (INV-PAY-02)");
        }
    }

    /** The bare grant, for the exchange wire and nowhere else. */
    public String expose() {
        return secret.expose();
    }
}
