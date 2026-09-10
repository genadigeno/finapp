package com.finapp.app.kyc;

import com.finapp.app.administration.SuspensionRequest;
import com.finapp.kyc.DecisionOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/kyc/cases/&#123;id&#125;/decision} (`P2-TSK-013`).
 *
 * <h2>The outcome is the domain's own enum, published as a request enum</h2>
 *
 * <p>{@code DecisionOutcome} appears in the contract as a two-value request enum — the
 * {@code RoleAssignmentRequest} shape, with the same review discipline: the classifier labels
 * request-enum additions {@code BREAKING} by blanket rule, and an existing client that never
 * sends a new value cannot be broken by its existence (`P2-TSK-004`'s recorded reasoning).
 *
 * <h2>The reason is required, and the bounds are cited rather than repeated</h2>
 *
 * <p>{@code KycAuditAction.KYC_DECISION_RECORDED.requiresReason()} is {@code true} and
 * {@code kyc_decision.reason} is {@code NOT NULL} — {@code INV-KYC-02}'s own words. This
 * annotation adds <em>where the caller is told</em>: a {@code 422} naming the field, rather
 * than a refusal three layers down rendered {@code api.InternalError}. The bounds are
 * {@code SuspensionRequest}'s constants — one mirrored copy of
 * {@code AuditRecord.MAX_REASON_LENGTH} for the whole boundary
 * ({@code AdministrativeRequestBoundsTest} holds this record to it), with {@code V007}'s
 * {@code CHECK} carrying the same number, reconciled by {@code DecisionMigrationTest}.
 *
 * @param outcome what the reviewer concluded: {@code APPROVED} or {@code REJECTED}
 * @param reason the reviewer's justification. Recorded permanently on the decision and in the
 *     audit trail ({@code INV-HIST-03})
 */
public record DecisionRequest(
        @NotNull DecisionOutcome outcome,
        @NotBlank
                @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                String reason) {}
