package com.finapp.app.merchant;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/operator/merchants/'{id}'/payout-destinations} (`P6-TSK-011`).
 *
 * <p>{@code destinationToken} is the grant the operator's client obtained from the payout
 * provider for the merchant's bank account — never the account itself (ADR-0056 §5). It is
 * {@link Sensitive} so every rendering of the request masks it (the
 * {@code AttachPaymentMethodRequest} precedent); its shape rule, including the refusal of
 * account-number-shaped values, is {@code PayoutDestinationGrant}'s, the one definition, mapped
 * to a {@code 422} naming the field and never the value.
 *
 * <p>{@code reason} is the operator's, required and bounded by the audit trail's own limit
 * ({@code INV-AUD-03}): a destination change redirects a counterparty's money, and the record
 * refuses to exist without the why.
 */
public record ProposePayoutDestinationRequest(
        @NotNull Sensitive<String> destinationToken,
        @NotBlank @Size(max = AuditRecord.MAX_REASON_LENGTH) String reason) {}
