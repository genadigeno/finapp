package com.finapp.payments;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A tokenised instrument reference, in flight to the provider (`P5-TSK-003`, `INV-PAY-02`).
 *
 * <p>The token is what the platform holds <em>instead of</em> raw card data, and it is
 * instrument-linked data that must never reach a log — which is why it travels wrapped
 * ({@link Sensitive}) rather than as a {@code String} nobody can distinguish from a display
 * suffix. {@code secretsAreWrapped} fired on the unwrapped first version of this type, and the
 * rule had the better argument (the {@code Session.tokenHash} precedent): a token in a log line
 * is a precise identifier of one customer's instrument.
 *
 * <h2>Why the component is named {@code secret}</h2>
 *
 * <p>The {@code RawPassword} idiom, for the same reason: {@code secretsAreWrapped} matches
 * field-name words against its vocabulary, so naming the component accurately makes the
 * <strong>existing</strong> rule enforce the wrapping for ever. The name is the control.
 *
 * <h2>The charset is the wire's safety</h2>
 *
 * <p>{@code [A-Za-z0-9_-]} covers tokenisation-provider shapes ({@code tok_...}) and makes the
 * adapter's JSON need no escaping machinery — a value outside it is refused at construction,
 * with a message that names the rule and never the value ({@code INV-AUD-02}).
 *
 * <p>{@link #expose()} is the one way the value comes off, registered as an unwrapping method in
 * {@code SecretsAreUnwrappedInOnePlaceTest}, so every caller is a named, justified entry — and
 * the only production caller is the adapter putting the token on the provider wire, which is the
 * one place it legitimately goes.
 */
public record InstrumentToken(Sensitive<String> secret) {

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    public InstrumentToken {
        Objects.requireNonNull(secret, "the instrument token must not be null");
        String value = secret.expose();
        Objects.requireNonNull(value, "the instrument token must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "an instrument token must be 1-128 characters of [A-Za-z0-9_-]");
        }
    }

    public static InstrumentToken of(String value) {
        return new InstrumentToken(Sensitive.of(value));
    }

    /** The bare token, for the provider wire and nowhere else. */
    public String expose() {
        return secret.expose();
    }
}
