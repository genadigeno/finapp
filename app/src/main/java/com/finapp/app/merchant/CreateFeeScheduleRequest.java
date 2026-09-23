package com.finapp.app.merchant;

import com.finapp.merchant.FeeSchedule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body creating a named pricing identity (`P6-TSK-004`).
 *
 * <p>The name bound is {@link FeeSchedule#MAX_NAME_LENGTH}'s boundary copy — annotation,
 * aggregate and `V004`'s {@code CHECK} are one bound in three reconciled places (the
 * {@code P1-TSK-028} finding: a boundary wider than the last write fails as our {@code 500}
 * after validation already passed).
 *
 * <p>Carries no secret and no PII: a schedule name is the platform's own vocabulary.
 */
public record CreateFeeScheduleRequest(
        @NotBlank @Size(max = FeeSchedule.MAX_NAME_LENGTH) String name,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
