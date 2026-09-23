package com.finapp.app.merchant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A merchant's own payout request, over {@code POST /v1/merchant/payouts} (`P6-TSK-012`). No
 * destination field, deliberately: a payout goes only to the merchant's effective destination,
 * which the four-eyes flow chose (ADR-0056) — a request that could name one would be the
 * redirection that flow exists to prevent. No reason either: the merchant's key asked.
 *
 * @param amount a decimal string such as {@code "25.00"}, exact at the currency's scale
 * @param currency ISO 4217, the merchant's settlement currency
 */
public record MerchantPayoutRequest(
        @NotBlank String amount, @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}
