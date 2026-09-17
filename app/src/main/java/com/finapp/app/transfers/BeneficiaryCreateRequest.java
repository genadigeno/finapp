package com.finapp.app.transfers;

import com.finapp.transfers.Beneficiary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/beneficiaries} (`P4-TSK-007`).
 *
 * <p>The display-name bound references {@link Beneficiary#MAX_DISPLAY_NAME_LENGTH}
 * <strong>directly</strong> — the {@code SuspensionRequest} idiom, so the boundary and the
 * domain cannot drift and no reconciliation test is owed. The domain's sharper rule (no
 * control, format, surrogate, private-use or unassigned characters) is the aggregate's own,
 * mapped to a 422 naming the field by the service; a bound is expressible here and a Unicode
 * category set is not.
 *
 * <p>{@code destinationAccountId} is a string, parsed by the service: a malformed value folds
 * into the same {@code transfers.UnknownDestination} refusal as an unknown one
 * (malformed-equals-absent for a third party's identifier — see {@code TransfersErrorCode}).
 */
public record BeneficiaryCreateRequest(
        @NotBlank @Size(max = Beneficiary.MAX_DISPLAY_NAME_LENGTH) String displayName,
        @NotBlank String destinationAccountId) {}
