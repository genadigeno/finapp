package com.finapp.app.accounts;

import com.finapp.accounts.ProductType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The body of {@code POST /v1/me/accounts} (`P3-TSK-013`).
 *
 * <p>{@code productType} publishes the closed {@link ProductType} enum directly — the
 * {@code RoleAssignmentRequest} precedent: a request enum's growth cannot break an existing
 * client, and an unknown value is refused by deserialisation as the caller's {@code 400}.
 * {@code currency} arrives as text and is bounded to the ISO shape here, so a garbage value is
 * a {@code 422} naming the field rather than a refusal three layers down
 * ({@code ERROR_CONTRACT.md} §3a); whether the platform <em>operates</em> in it is the domain's
 * question ({@code accounts.UnsupportedCurrency}), deliberately not restated as a pattern.
 *
 * <p>Carries no secret and no PII: a product type and a currency code say nothing about
 * anybody.
 */
public record AccountOpenRequest(
        @NotNull ProductType productType,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
                String currency) {}
