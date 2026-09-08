package com.finapp.app.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PATCH /v1/me}.
 *
 * <h2>The bounds are a second boundary onto the same column, so they are the same bounds</h2>
 *
 * <p>{@code P1-TSK-006}'s completion gate found that a NUL byte in a display name produced
 * {@code 500 api.InternalError} — a caller's mistake reported as a platform failure — and that CR,
 * LF, tab and a bidirectional override were being accepted into a {@code RESTRICTED-PII} column,
 * which is a forged log line waiting for the first component that prints a name. It closed that in
 * <strong>three</strong> places: {@code PartyName}, the request boundary, and a {@code CHECK} in
 * {@code party} {@code V003}.
 *
 * <p>{@code PATCH /v1/me} is a <em>new</em> boundary onto that same column. Skipping the constraint
 * here would reopen one of the three, and the constraint is what decides whether the caller is
 * <strong>told</strong> — {@code api.ValidationFailed} naming the field, rather than an
 * {@code IllegalArgumentException} from the domain type rendered {@code api.InternalError}.
 *
 * <h2>Absence and null are the same thing here, and the limit is recorded rather than discovered</h2>
 *
 * <p>A record cannot distinguish <em>field absent</em> from {@code "displayName": null}: both
 * deserialise to {@code null}. Here that costs nothing, because {@code display_name} is
 * {@code NOT NULL} and can never be cleared — so both are refused, {@code @NotBlank}, one
 * {@code 422}. A {@code PATCH} with an empty body is a {@code 422} too, which is correct: it asks
 * for nothing.
 *
 * <p><strong>When a genuinely nullable field arrives this shape cannot express it</strong> — "clear
 * it" and "leave it alone" would become indistinguishable, and the answer is a wrapper type or JSON
 * Merge Patch. That is a decision for the task that has such a field, not machinery built now for
 * one that does not exist.
 *
 * @param displayName the person's name, as a human would recognise it. {@code RESTRICTED-PII}
 */
public record ProfileUpdateRequest(
        @NotBlank
                @Size(min = NAME_MIN, max = NAME_MAX)
                @Pattern(regexp = NAME_CHARSET)
                String displayName) {

    /**
     * Mirrors {@code PartyName}, as {@code RegistrationRequest} does and for the same reason: an
     * annotation needs a compile-time constant and {@code app} must not compile against a module's
     * internals to get one.
     *
     * <p>A test sweeps every code point this boundary admits and asserts the domain type admits it
     * too — because a boundary wider than the domain turns a caller's input into <em>our</em> 500,
     * and one narrower is a limit nobody chose.
     */
    static final int NAME_MIN = 1;

    static final int NAME_MAX = 200;

    /** Everything except control, format, surrogate, private-use and unassigned code points. */
    static final String NAME_CHARSET = "[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]+";
}
