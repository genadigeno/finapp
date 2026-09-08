package com.finapp.app.registration;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/registrations}.
 *
 * <h2>Why the bounds are duplicated from the domain types</h2>
 *
 * <p>{@code LoginIdentifier} and {@code PartyName} already enforce these, and enforcing them again
 * here is not redundancy. Boundary validation is rejected <strong>before the handler is
 * entered</strong> and renders {@code api.ValidationFailed} naming the field; the domain type
 * throws {@code IllegalArgumentException}, which would surface as {@code api.InternalError} - our
 * fault, for the caller's mistake. The constants are the domain types' own, so the two cannot
 * drift, and a test asserts they still agree.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><strong>No party kind.</strong> Organisations are on {@code PHASE_1_PLAN.md} §12's must-not
 * list, so the endpoint registers a person. A field a caller may set to a value the platform
 * refuses to serve is surface for nothing.
 *
 * <p><strong>No email address.</strong> A login identifier that is also a contact channel cannot be
 * changed without changing how somebody logs in, nor verified without blocking login
 * ({@code PHASE_1_PLAN.md} §4). {@code LoginIdentifier}'s charset excludes {@code @} precisely so
 * the confusion cannot arrive silently through the first person who types an address.
 *
 * <h2>The password, added by {@code P1-TSK-026}</h2>
 *
 * <p>It is <strong>required</strong>, and that removes a shape rather than adding one: an optional
 * password would let a caller create an Identity that can never authenticate, which is exactly the
 * state this task exists to close. Making it required is a {@code BREAKING} change to a published
 * {@code /v1} contract on the platform's first endpoint, accepted with the reasoning recorded on
 * {@code RegistrationController}.
 *
 * <p>It is <strong>wrapped</strong>, for the reason {@code AuthenticationRequest} sets out at
 * length: a record's generated {@code toString} prints every component, so an unwrapped one turns
 * {@code log.info("{}", request)} into a password disclosure with no getter call and nothing a
 * reviewer stops at. {@code secretsAreWrapped} enforces it either way.
 *
 * <p><strong>Its bounds are not declared here, and that is the one asymmetry worth explaining.</strong>
 * Bean Validation cannot see inside {@link Sensitive}, and a constraint that unwrapped it would put
 * a plaintext in {@code app} - which {@code SecretsAreUnwrappedInOnePlaceTest} pins to {@code
 * identity} and would fail the build over, correctly. So {@code RawPassword} is the only thing that
 * bounds it, and {@code RegistrationService} maps its refusal to the {@code 422} this annotation
 * would have produced.
 *
 * <p>Registration answers a short password <em>differently</em> from authentication, deliberately.
 * {@code AuthenticationRequest} treats one as an ordinary authentication failure, because a second
 * response shape there is an enumeration risk. Here the password is a value the caller
 * <strong>chose</strong> and must be able to correct, the refusal is decided before any lookup, and
 * it discloses nothing about any account - so telling them is both safe and necessary.
 *
 * @param loginIdentifier what the person will type to log in
 * @param displayName their name, as a human would recognise it. {@code RESTRICTED-PII}
 * @param password their secret. Never logged, never stored, never echoed
 */
public record RegistrationRequest(
        @NotBlank
                @Size(min = LOGIN_MIN, max = LOGIN_MAX)
                @Pattern(regexp = LOGIN_CHARSET)
                String loginIdentifier,
        @NotBlank
                @Size(min = NAME_MIN, max = NAME_MAX)
                @Pattern(regexp = NAME_CHARSET)
                String displayName,
        @NotNull Sensitive<String> password) {

    /**
     * Mirrors {@code LoginIdentifier}. Literals because an annotation needs a compile-time
     * constant and {@code app} must not compile against a module's internals to get one; a test
     * asserts they still match.
     */
    static final int LOGIN_MIN = 3;

    static final int LOGIN_MAX = 64;

    /**
     * Case-insensitive here, lower-cased by {@code LoginIdentifier}.
     *
     * <p>The boundary accepts what a person would type; normalisation is the domain type's job and
     * happens in exactly one place, which is what makes the unique index mean what it appears to
     * mean.
     */
    static final String LOGIN_CHARSET = "[A-Za-z0-9._-]+";

    /**
     * One, not zero.
     *
     * <p>{@code @NotBlank} already refuses an empty name, but it is invisible to the published
     * contract: springdoc renders {@code @Size(max = 200)} as {@code minLength: 0}, which tells a
     * client generator that an empty string is acceptable when it is not. The annotation is not
     * redundant - it still catches a name that is only whitespace, which no length can express.
     */
    static final int NAME_MIN = 1;

    static final int NAME_MAX = 200;

    /**
     * Everything except control, format, surrogate, private-use and unassigned code points.
     *
     * <p><strong>Not an allow-list, deliberately.</strong> {@code PartyName} refuses a charset
     * restriction on names and is right to: names contain apostrophes, hyphens, accents and
     * non-Latin scripts, and a rule narrow enough to feel like a control would reject legitimate
     * customers. What is excluded here is not in anybody's name at all.
     *
     * <p>It is here <em>as well as</em> in the domain type because the two do different jobs. The
     * domain type protects every future writer; this one decides what a caller is <strong>told</strong>
     * - {@code api.ValidationFailed} naming the field, rather than an {@code IllegalArgumentException}
     * three layers down rendered as {@code api.InternalError}. A NUL cannot be stored in a
     * PostgreSQL {@code text} column at all, so without this a caller could turn its own mistake
     * into a 500 - and a client may retry a 500 for ever on a request that can never succeed.
     */
    static final String NAME_CHARSET = "[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]+";
}
