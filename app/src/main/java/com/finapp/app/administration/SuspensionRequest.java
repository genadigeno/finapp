package com.finapp.app.administration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/identities/&#123;id&#125;/suspension}.
 *
 * <h2>The reason is required, and the type system already said so</h2>
 *
 * <p>{@code IdentityAuditAction.IDENTITY_SUSPENDED.requiresReason()} is {@code true}, and
 * {@code AuditRecord} <strong>refuses construction</strong> without one — so an absent reason
 * cannot reach the table by any route. What this annotation adds is <em>where the caller is told</em>:
 * a {@code 422} naming the field, rather than an exception three layers down rendered
 * {@code api.InternalError} ({@code ERROR_CONTRACT.md} §3).
 *
 * <p>It is required because this is an action taken against <strong>somebody else's account</strong>.
 * The audit trail's answer to <em>why was this person locked out?</em> is this field, and an
 * administrative action nobody has to justify is the shape an insider's abuse takes.
 *
 * <h2>The charset, and why it is the display-name one rather than the login one</h2>
 *
 * <p>A reason is prose written by a person: it will contain apostrophes, accents, case numbers and
 * possibly a name, so an allow-list narrow enough to feel like a control would reject legitimate
 * justifications. What is excluded is what is in nobody's explanation — control, format, surrogate,
 * private-use and unassigned code points — the same rule and the same reasoning as
 * {@code PartyName}, because a CR in a value the platform stores and later renders is a forged log
 * line ({@code DATA_CLASSIFICATION.md} §5).
 *
 * @param reason why this identity is being suspended. Recorded permanently ({@code INV-HIST-03})
 */
public record SuspensionRequest(
        @NotBlank
                @Size(min = REASON_MIN, max = REASON_MAX)
                @Pattern(regexp = REASON_CHARSET)
                String reason) {

    /**
     * Long enough to be an explanation rather than a word.
     *
     * <p>Not zero, and not one: {@code @NotBlank} already refuses whitespace, but springdoc renders
     * an absent minimum as {@code minLength: 0}, which tells a client generator that an empty
     * string is acceptable when it is not ({@code RegistrationRequest}'s recorded finding).
     */
    static final int REASON_MIN = 1;

    /**
     * Mirrors {@code AuditRecord.MAX_REASON_LENGTH}, which mirrors the {@code CHECK} on
     * {@code platform.audit_record}. A literal because an annotation needs a compile-time constant
     * and {@code app} must not compile against a platform internal to get one; a test asserts they
     * still agree, because a boundary that admitted more than the record accepts would turn a
     * caller's over-long reason into a 500 at the last write.
     */
    static final int REASON_MAX = 1000;

    /** Everything except control, format, surrogate, private-use and unassigned code points. */
    static final String REASON_CHARSET = "[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]+";
}
