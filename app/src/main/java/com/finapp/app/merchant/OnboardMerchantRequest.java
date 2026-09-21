package com.finapp.app.merchant;

import com.finapp.merchant.Merchant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The onboarding request body (`P6-TSK-003`).
 *
 * <p>The name bounds are {@link Merchant#MAX_NAME_LENGTH}'s boundary copy — the annotation,
 * the aggregate and `V002`'s {@code CHECK} are one bound in three reconciled places (the
 * {@code P1-TSK-028} finding: a boundary wider than the last write fails as our {@code 500}
 * after validation already passed). The currency is shape-checked here and chart-checked in
 * the command ({@code merchant.UnsupportedCurrency}).
 *
 * <p>Carries no secret and no PII beyond business names bound for {@code CONFIDENTIAL}
 * columns; the party identifier is the operator's assertion, verified against the KYB
 * projection before anything is written — never trusted (ADR-0031's rule).
 */
public record OnboardMerchantRequest(
        @NotNull UUID partyId,
        @NotBlank @Size(max = Merchant.MAX_NAME_LENGTH) String legalName,
        @NotBlank @Size(max = Merchant.MAX_NAME_LENGTH) String displayName,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$") String settlementCurrency) {}
