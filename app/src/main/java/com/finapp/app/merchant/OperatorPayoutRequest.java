package com.finapp.app.merchant;

import com.finapp.merchant.MerchantPayout;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An operator's payout request on a merchant's behalf, over
 * {@code POST /v1/operator/merchants/{merchantId}/payouts} (`P6-TSK-012`, ADR-0057 §6). The
 * merchant's own request plus a reason, which is required: money leaves the platform because a
 * person asked, and the audit record is what explains it. No destination field, for the
 * merchant request's reason.
 */
public record OperatorPayoutRequest(
        @NotBlank String amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = MerchantPayout.MAX_REASON_LENGTH) String reason) {}
