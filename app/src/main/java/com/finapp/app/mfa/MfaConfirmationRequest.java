package com.finapp.app.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The code a customer read from their authenticator (`P1-TSK-017`).
 *
 * <h2>Not wrapped in Sensitive, and that is a judgement rather than an omission</h2>
 *
 * <p>A TOTP code is valid for about ninety seconds and only against one pending enrolment, so it is
 * not a secret in the sense `secretsAreWrapped` protects - and the field is deliberately named
 * `code` rather than `otp`, which IS in that vocabulary. Calling it `otp` would be equally
 * defensible and would require wrapping; what would not be defensible is naming it `otp` and
 * exempting the rule.
 *
 * <p>What still must not happen is a code reaching a log, and nothing here logs the request. The
 * residual is bounded by the code expiring on its own.
 *
 * <h2>The bounds are the boundary's, and they are narrow on purpose</h2>
 *
 * <p>Six to eight digits, matching what `TotpParameters` permits. A caller sending a megabyte of
 * digits should be refused at the boundary rather than inside a constant-time comparison, which is
 * the `P0-TSK-025` argument: reject before any domain work, and count the work to prove it.
 */
public record MfaConfirmationRequest(
        @NotBlank @Size(min = 6, max = 8) @Pattern(regexp = "[0-9]+") String code) {}
