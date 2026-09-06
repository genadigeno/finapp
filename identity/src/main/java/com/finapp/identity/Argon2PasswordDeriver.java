package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id, through a vetted library (ADR-0032).
 *
 * <h2>What is deliberately not written here</h2>
 *
 * <p>No primitive, no salting scheme, no comparison. {@link Argon2PasswordEncoder} generates a
 * per-credential salt from a {@code SecureRandom}, produces the PHC-encoded form, and compares in
 * constant time. {@code DELIVERY_PLAN.md} §17 names <em>"rolling bespoke cryptography"</em> as a
 * risk, and each of those three is a place where doing it by hand goes wrong quietly: a reused
 * salt, a salt that is not random, or a comparison that returns early and leaks the stored value
 * one byte at a time.
 *
 * <h2>The one thing this class does parse</h2>
 *
 * <p>{@link #parametersOf} reads {@code m=…,t=…,p=…} back out of the encoded string, because
 * Spring's own {@code Argon2EncodingUtils} is package-private. That is parsing a documented text
 * format, not implementing cryptography, and it earns its place: the parameters are stored twice on
 * purpose (ADR-0032 Option D - once inside the encoded form for verification, once as columns for
 * reporting) and <strong>duplication that nothing reconciles is drift waiting to happen</strong>.
 * This is what lets a test assert the two agree.
 *
 * <h2>Stateless, and therefore safe for N instances</h2>
 *
 * <p>{@link Argon2PasswordEncoder} holds only its configuration and is immutable; the salt comes
 * from a {@code SecureRandom} per call. Ten instances deriving concurrently share nothing, and
 * there is no cache to go stale ({@code DISTRIBUTED_EXECUTION.md} §3 gains no row).
 *
 * <h2>Cost</h2>
 *
 * <p>Memory is per <em>concurrent</em> derivation: at the shipped 19456 KiB, ten simultaneous
 * derivations on one instance is roughly 190 MiB of transient heap-external allocation. That is a
 * capacity fact worth knowing before it is discovered, and it is the other half of ADR-0032's
 * recorded negative consequence.
 */
public final class Argon2PasswordDeriver implements PasswordDeriver {

    /**
     * Salt length in bytes. 16 is the PHC-recommended minimum and Spring's own default.
     *
     * <p>Not a stored parameter, and that is correct: the salt travels <em>inside</em> the encoded
     * derivation, so a credential produced with a different salt length still verifies without
     * anything recording the length separately. {@link DerivationParameters} holds the three
     * factors that affect <em>cost</em>, which are the ones an upgrade campaign queries on.
     */
    private static final int SALT_LENGTH = 16;

    /** Output length in bytes. 32 matches the salt's security level; also Spring's default. */
    private static final int HASH_LENGTH = 32;

    /** The parameter segment of a PHC string: {@code $m=19456,t=2,p=1$}. */
    private static final Pattern ENCODED_PARAMETERS =
            Pattern.compile("\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$");

    private final DerivationParameters parameters;
    private final Argon2PasswordEncoder encoder;

    public Argon2PasswordDeriver(DerivationParameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters must not be null");
        // (saltLength, hashLength, parallelism, memory, iterations) - the argument order is the
        // library's and is easy to transpose, which is why the shipped values are pinned by a test
        // that reads them back out of a real derivation rather than out of this constructor.
        this.encoder =
                new Argon2PasswordEncoder(
                        SALT_LENGTH,
                        HASH_LENGTH,
                        parameters.parallelism(),
                        parameters.memoryKib(),
                        parameters.iterations());
    }

    @Override
    public CredentialAlgorithm algorithm() {
        return CredentialAlgorithm.ARGON2ID;
    }

    @Override
    public DerivationParameters currentParameters() {
        return parameters;
    }

    @Override
    public Sensitive<String> derive(RawPassword password) {
        Objects.requireNonNull(password, "password must not be null");
        return Sensitive.of(encoder.encode(password.expose()));
    }

    @Override
    public boolean matches(RawPassword password, Sensitive<String> credentialDerivation) {
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(credentialDerivation, "credentialDerivation must not be null");
        return encoder.matches(password.expose(), credentialDerivation.expose());
    }

    @Override
    public DerivationParameters parametersOf(Sensitive<String> credentialDerivation) {
        Objects.requireNonNull(credentialDerivation, "credentialDerivation must not be null");
        Matcher encoded = ENCODED_PARAMETERS.matcher(credentialDerivation.expose());
        if (!encoded.find()) {
            // The message never repeats the value. It is offline-crackable material, and an
            // exception message reaches a log line (INV-AUD-02).
            throw new IllegalArgumentException(
                    "the derivation does not carry Argon2 parameters in its encoded form");
        }
        return new DerivationParameters(
                Integer.parseInt(encoded.group(1)),
                Integer.parseInt(encoded.group(2)),
                Integer.parseInt(encoded.group(3)));
    }
}
