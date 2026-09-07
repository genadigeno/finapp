package com.finapp.app.recovery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/recoveries}.
 *
 * <p>Bounds mirror {@code LoginIdentifier}, as every other boundary DTO here does and for the same
 * reason: an annotation needs a compile-time constant. {@code RecoveryRequestBoundsTest} sweeps
 * every code point the boundary admits and asserts the domain admits it too - written because
 * {@code P1-TSK-010} completion gate found exactly this sentence claiming a test nobody had written.
 *
 * <p>Without the bounds, a malformed identifier would reach the domain constructor inside the
 * service, throw, and surface as {@code api.InternalError} - our fault reported for their input,
 * which {@code ERROR_CONTRACT.md} section 3 forbids and which a client may retry for ever.
 *
 * @param loginIdentifier whose account to recover. A 422 here says the request was the wrong shape
 *     and says nothing about whether anybody holds that identifier
 */
public record RecoveryInitiationRequest(
        @NotBlank
                @Size(min = LOGIN_MIN, max = LOGIN_MAX)
                @Pattern(regexp = LOGIN_CHARSET)
                String loginIdentifier) {

    static final int LOGIN_MIN = 3;

    static final int LOGIN_MAX = 64;

    static final String LOGIN_CHARSET = "[A-Za-z0-9._-]+";
}
