package com.finapp.app.payments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The body of {@code POST /v1/me/withdrawals} (`P7-TSK-008`).
 *
 * <p>The {@code PaymentCreateRequest} shape at the outbound door: the caller names their own
 * instrument and prices the withdrawal as an exact decimal string ({@code INV-MON-01} past
 * the boundary, inbound; not representable at the currency's scale is the caller's 422,
 * never a rounding). No secret rides here: a method id is not the destination — the opaque
 * reference stays behind the boundary ({@code INV-RAIL-03}) — and the amount is the
 * caller's own instruction, bound for the wallet only through the hold it must afford.
 */
public record WithdrawalRequest(
        @NotBlank String paymentMethodId,
        @NotBlank String amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
                String currency) {}
