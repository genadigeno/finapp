package com.finapp.app.administration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code DELETE /v1/identities/&#123;id&#125;/suspension} (`P1-TSK-032`).
 *
 * <h2>A DELETE with a body, and the alternative is worse</h2>
 *
 * <p>The reason is required — lifting a suspension is an action taken against somebody else's
 * account, and a quiet reinstatement is how an accomplice undoes an incident response — and it is
 * free prose that may name a person or an incident. A query parameter would put that prose into
 * access logs, proxies and browser history ({@code INV-AUD-02}), so it travels in the body, which
 * every other administrative reason already does.
 *
 * <h2>The bounds are {@code SuspensionRequest}'s, cited rather than repeated</h2>
 *
 * <p>The {@code RoleAssignmentRequest} pattern: referencing the constants is what makes
 * {@code AdministrativeRequestBoundsTest}'s parity assertion a fact rather than a coincidence, and
 * it keeps the one real bound — {@code AuditRecord.MAX_REASON_LENGTH}, mirrored once — from
 * gaining a third copy that drifts alone.
 *
 * @param reason why this identity is being let back in. Recorded permanently ({@code INV-HIST-03})
 */
public record ReinstatementRequest(
        @NotBlank
                @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                String reason) {}
