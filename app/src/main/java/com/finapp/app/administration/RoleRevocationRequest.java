package com.finapp.app.administration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code DELETE /v1/identities/&#123;id&#125;/roles/&#123;role&#125;} (`P15-TSK-032`).
 *
 * <p>A {@code DELETE} with a body, for {@code ReinstatementRequest}'s reason: the reason is required - taking a role away
 * changes what someone may do, and it is the act that closes an incident's break-glass grant - and it is free prose that may
 * name a person or an incident, so it never travels in a URL. The role is the path's, an enum, so an unknown one is refused
 * before the handler is entered.
 *
 * <p>The bounds are {@code SuspensionRequest}'s, cited rather than repeated, so {@code AdministrativeRequestBoundsTest}'s
 * parity is a fact.
 *
 * @param reason why the role is taken. Recorded permanently on {@code identity.RoleAssigned} ({@code INV-HIST-03})
 */
public record RoleRevocationRequest(
        @NotBlank
                @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                String reason) {}
