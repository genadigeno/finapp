package com.finapp.app.payments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The body of {@code POST /v1/payments} (`P5-TSK-011`).
 *
 * <p><strong>{@code amount} is a decimal string parsed exactly</strong> — the
 * {@code TransferCreateRequest} precedent: a JSON number is a {@code double} in every careless
 * client ({@code INV-MON-01} past the boundary, inbound), and an amount not representable at
 * the currency's scale is the caller's 422, never a rounding ({@code INV-MON-03}).
 *
 * <p>{@code paymentMethodId} is a string parsed by the service: a malformed identifier folds
 * into the same {@code payments.UnknownInstrument} refusal as an unknown or a stranger's
 * (malformed-equals-absent), so the shape of the refusal reads nothing back to a probe.
 *
 * <p><strong>Deliberately no account identifier</strong>: the money's destination is the
 * caller's own wallet, resolved through their live customer inside the command
 * ({@code PaymentParticipants.walletOwnedBy}) — the POST takes no identifier a stranger could
 * point at a victim (the {@code TransferController} ownership stance, sharpened: here not even
 * the caller chooses the destination).
 */
public record PaymentCreateRequest(
        @NotBlank String paymentMethodId,
        @NotBlank String amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
                String currency) {}
