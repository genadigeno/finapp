package com.finapp.app.transfers;

import com.finapp.app.administration.SuspensionRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/transfers/&#123;id&#125;/reversal} (`P4-TSK-009`).
 *
 * <h2>The reason is required, and the type system already said so</h2>
 *
 * <p>{@code TransfersAuditAction.TRANSFER_REVERSED.requiresReason()} is {@code true}, and
 * {@code AuditRecord} <strong>refuses construction</strong> without one — so an absent reason
 * cannot reach the table by any route. What this annotation adds is <em>where the caller is
 * told</em>: a {@code 422} naming the field, rather than an exception three layers down rendered
 * {@code api.InternalError} ({@code ERROR_CONTRACT.md} §3). It is required because a reversal is
 * a privileged act over <strong>somebody else's money</strong> ({@code PHASE_4_PLAN.md} §11):
 * the trail's answer to <em>why was this transfer undone?</em> is this field, and a quiet
 * reversal is how an accomplice undoes a customer's transfer.
 *
 * <p>The bounds and charset are cited from {@link SuspensionRequest} — the `P2-TSK-012` idiom:
 * one mirrored copy of {@code AuditRecord.MAX_REASON_LENGTH}, held to the record by
 * {@code AdministrativeRequestBoundsTest}, rather than a third literal free to drift. The
 * reason travels in a {@code POST} body, never a query parameter: free prose may name a person
 * or an incident, and a query string reaches access logs, proxies and browser history
 * ({@code INV-AUD-02}, the `P1-TSK-032` DELETE-body reasoning).
 *
 * @param reason why this transfer is being reversed. Recorded permanently ({@code INV-HIST-03})
 */
public record TransferReversalRequest(
        @NotBlank
                @Size(min = SuspensionRequest.REASON_MIN, max = SuspensionRequest.REASON_MAX)
                @Pattern(regexp = SuspensionRequest.REASON_CHARSET)
                String reason) {}
