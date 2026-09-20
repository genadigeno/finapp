package com.finapp.app.payments;

import com.finapp.payments.Refund;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/payments/'{id}'/refund} (`P5-TSK-015`).
 *
 * <p>{@code amount} is a decimal string parsed exactly (the {@code PaymentCreateRequest}
 * idiom: {@code INV-MON-01} past the boundary, inbound; a non-representable amount is the
 * caller's 422, never a rounding). The {@code reason} is <strong>required</strong>
 * ({@code INV-AUD-03}, the reversal precedent) — free prose by the operator, bound for the
 * audit record's reason field, its bound cited from {@link Refund#MAX_REASON_LENGTH} so the
 * DTO, `V004`'s {@code CHECK} and the aggregate reconcile on one number.
 */
public record RefundRequest(
        @NotBlank String amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
                String currency,
        @NotBlank @Size(max = Refund.MAX_REASON_LENGTH) String reason) {}
