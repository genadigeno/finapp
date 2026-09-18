package com.finapp.app.transfers;

import com.finapp.transfers.Transfer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/transfers} (`P4-TSK-008`).
 *
 * <p><strong>Exactly one of {@code destinationAccountId} and {@code beneficiaryId}</strong> —
 * a Bean Validation constraint cannot say "one of these two", so the rule is decided by
 * {@link TransferService} at the boundary, before any transaction, as a 422 naming the
 * constraint. Both are strings parsed by the service: a malformed destination folds into the
 * same {@code transfers.UnknownDestination} refusal as an unknown one, and a malformed
 * beneficiary into the same — malformed-equals-absent, so neither is readable from the
 * response ({@code TransfersErrorCode}'s reasoning). The source likewise folds into
 * {@code transfers.UnknownSource}.
 *
 * <p><strong>{@code amount} is a decimal string parsed exactly</strong> — the
 * {@code AdjustmentRequest} precedent: a JSON number is a {@code double} in every careless
 * client ({@code INV-MON-01} past the boundary, inbound), and an amount not representable at
 * the currency's scale is the caller's 422, never a rounding ({@code INV-MON-03}).
 *
 * <p>The reference bound references {@link Transfer#MAX_REFERENCE_LENGTH} <strong>directly</strong>
 * (the {@code BeneficiaryCreateRequest} idiom), and the migration reconciliation holds that
 * constant to `V002`'s {@code CHECK}. Carries no secret; the reference is free text a person
 * writes ({@code RESTRICTED-PII} at the register) — named in
 * {@code CredentialReachesNoEmittedSinkTest}'s pinned set.
 */
public record TransferCreateRequest(
        @NotBlank String sourceAccountId,
        String destinationAccountId,
        String beneficiaryId,
        @NotBlank String amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
                String currency,
        @Size(max = Transfer.MAX_REFERENCE_LENGTH) String reference) {}
