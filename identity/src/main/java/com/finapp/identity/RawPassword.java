package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;

/**
 * A plaintext password, in flight and never at rest.
 *
 * <p>It exists at exactly two moments: when a credential is set, and when one is verified. ADR-0032
 * calls the second <em>"the only moment the platform legitimately holds the plaintext"</em>, and
 * this type is what makes that moment explicit rather than a {@code String} passed between methods
 * that nobody can distinguish from a username.
 *
 * <h2>Why the component is named {@code secret}</h2>
 *
 * <p>{@code secretsAreWrapped} matches field-name <em>words</em> against a fixed vocabulary and
 * fails the build on an unwrapped one. Naming the component accurately therefore makes the
 * <strong>existing</strong> rule enforce the wrapping, rather than adding a rule or relying on
 * whoever reads this next. The name is the control.
 *
 * <h2>Why {@code String} and not {@code char[]}</h2>
 *
 * <p>The received wisdom is {@code char[]}, so that the value can be zeroed after use. That is
 * sound where the caller controls the buffer. Here the plaintext arrives as a {@code String}
 * deserialised by Jackson from a request body, so a {@code char[]} copied from it would leave the
 * original on the heap and zero a duplicate - security theatre with a cost. What actually protects
 * it is {@link Sensitive}, which masks every rendering path, and the fact that it is never stored.
 *
 * <h2>The bounds are a denial-of-service control, not a policy</h2>
 *
 * <p>Argon2id is <em>deliberately</em> expensive, which makes any endpoint that derives one the
 * most CPU-costly thing the platform does (ADR-0032). An unbounded password is therefore an
 * amplification vector: the cost is paid before anything else can refuse it. The upper bound is
 * generous enough that no passphrase anybody writes is affected.
 *
 * <p>The lower bound is <strong>not</strong> a password-strength policy. Composition rules belong
 * to the task that has a policy to state and a place to tell a user about it; this is the length
 * below which a value is not plausibly a password at all.
 */
public record RawPassword(Sensitive<String> secret) {

    /** Below this, the value is not a password. Not a strength policy - see the class javadoc. */
    public static final int MIN_LENGTH = 8;

    /**
     * Above this, a caller is buying CPU time rather than authenticating.
     *
     * <p>Long enough for any real passphrase; short enough that a flood of maximal ones is not
     * meaningfully worse than a flood of ordinary ones, since Argon2id's cost is dominated by the
     * memory and time factors rather than by input length.
     */
    public static final int MAX_LENGTH = 256;

    public RawPassword {
        Objects.requireNonNull(secret, "secret must not be null");
        String value = secret.expose();
        Objects.requireNonNull(value, "a password must not be null");
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            // The message names the bounds and never the value or its actual length: a length is a
            // small disclosure and there is no reason to make it (INV-AUD-02).
            throw new IllegalArgumentException(
                    "a password must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                            + " characters");
        }
    }

    /** Wraps a plaintext. The only way to build one, so the wrapping cannot be forgotten. */
    public static RawPassword of(String plaintext) {
        return new RawPassword(Sensitive.of(plaintext));
    }

    /**
     * The plaintext, for the deriver and for nothing else.
     *
     * <p>Named to be conspicuous at a call site. {@code Sensitive} deliberately makes exposure an
     * explicit act rather than something that happens by interpolation, and this preserves that.
     */
    public String expose() {
        return secret.expose();
    }

    /** {@code Sensitive} masks itself, so the generated {@code toString} is already safe - asserted. */
}
