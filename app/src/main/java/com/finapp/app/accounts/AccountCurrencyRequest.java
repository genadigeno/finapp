package com.finapp.app.accounts;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The body of {@code POST /v1/me/accounts/{id}/currencies} (`P9-TSK-004`).
 *
 * <p>{@code currency} arrives as text bounded to the ISO shape, so a garbage value is a
 * {@code 422} naming the field ({@code ERROR_CONTRACT.md} §3a); whether the platform
 * <em>operates</em> in it is the domain's question ({@code accounts.UnsupportedCurrency}) — the
 * {@link AccountOpenRequest} precedent. Carries no secret and no PII.
 */
public record AccountCurrencyRequest(
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
                String currency) {}
