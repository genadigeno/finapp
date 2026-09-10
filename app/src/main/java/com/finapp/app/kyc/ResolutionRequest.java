package com.finapp.app.kyc;

import com.finapp.app.administration.SuspensionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/kyc/cases/&#123;id&#125;/reviews/&#123;taskId&#125;/resolution}
 * (`P2-TSK-012`).
 *
 * <h2>The reason is required, and the type system already said so</h2>
 *
 * <p>{@code KycAuditAction.REVIEW_RESOLVED.requiresReason()} is {@code true} and
 * {@code AuditRecord} refuses construction without one — {@code INV-KYC-04}'s own words: a hit's
 * resolution carries a recorded reason, because a name match is a probability and a judgement
 * nobody has to justify is indistinguishable from silence. What this annotation adds is
 * <em>where the caller is told</em>: a {@code 422} naming the field, rather than an exception
 * three layers down rendered {@code api.InternalError} ({@code ERROR_CONTRACT.md} §3).
 *
 * <h2>The bounds are {@code SuspensionRequest}'s, cited rather than repeated</h2>
 *
 * <p>The {@code ReinstatementRequest} pattern, across a package this time (which is why those
 * constants became {@code public}): one mirrored copy of {@code AuditRecord.MAX_REASON_LENGTH}
 * for the whole boundary, held to the record's own bound by
 * {@code AdministrativeRequestBoundsTest} — and {@code V006}'s
 * {@code review_task_reason_is_bounded} carries the same number, reconciled by
 * {@code ReviewTaskMigrationTest}, because a boundary wider than either sink turns a caller's
 * over-long reason into a 500 at the last write.
 *
 * @param reason the reviewer's judgement about what the check raised. Recorded permanently on
 *     the task and in the audit trail ({@code INV-HIST-03})
 */
public record ResolutionRequest(
        @NotBlank
                @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                String reason) {}
