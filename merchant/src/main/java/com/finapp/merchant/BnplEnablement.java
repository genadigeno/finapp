package com.finapp.merchant;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One merchant BNPL enablement as it stands (`P12-TSK-006`; OQ-8): who proposed it and why, who decided it and why, and
 * who withdrew it and why - each decision whole and frozen on the row once written (`V010`'s edge trigger), so the row is
 * its own history and every instant on it is the database's, written after the lock the act took.
 *
 * <p>Reasons are {@code RESTRICTED-PII} (free text by a person): carried here for the store and the audit record, never
 * rendered by a door and never in {@link #toString()}.
 */
public record BnplEnablement(
        BnplEnablementId id,
        MerchantId merchantId,
        BnplEnablementStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<String> withdrawnBy,
        Optional<Instant> withdrawnAt,
        Optional<String> withdrawalReason) {

    /** Bound on every reason - the audit record's own ({@code AuditRecord.MAX_REASON_LENGTH}) and `V010`'s CHECK. */
    public static final int MAX_REASON_LENGTH = 1000;

    public BnplEnablement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(withdrawnBy, "withdrawnBy must not be null");
        Objects.requireNonNull(withdrawnAt, "withdrawnAt must not be null");
        Objects.requireNonNull(withdrawalReason, "withdrawalReason must not be null");
        if ((status == BnplEnablementStatus.PROPOSED) != decidedBy.isEmpty()) {
            throw new IllegalArgumentException("only a PROPOSED enablement is undecided");
        }
        if ((status == BnplEnablementStatus.WITHDRAWN) != withdrawnBy.isPresent()) {
            throw new IllegalArgumentException("only a WITHDRAWN enablement carries a withdrawal");
        }
    }

    /**
     * A reason as the domain admits it: present, not blank, within {@link #MAX_REASON_LENGTH} and holding no card-number or
     * bank-account shape ({@link com.finapp.sharedkernel.security.InstrumentShapes}, the platform's one screen - bnpl's
     * {@code BnplReasons} precedent; the Phase 12 -> 13 gate's A5-F5) - judged before anything is locked or written.
     * `V010`'s CHECK holds the bound for every writer, `V012`'s {@code merchant.holds_instrument_shape} twin the screen.
     *
     * @throws ReasonRefused (an {@link IllegalArgumentException}) for a missing, blank, unbounded or instrument-bearing
     *     reason
     */
    public static String reasoned(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ReasonRefused("a BNPL enablement act is reasoned");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new ReasonRefused("a reason is at most " + MAX_REASON_LENGTH + " characters");
        }
        if (com.finapp.sharedkernel.security.InstrumentShapes.holdsAny(reason)) {
            throw new ReasonRefused("a reason must not hold a card-number or bank-account shape");
        }
        return reason;
    }

    /**
     * A reason the domain does not admit - missing, blank, past the bound or holding an instrument shape. An
     * {@link IllegalArgumentException}, as every refusal of {@link #reasoned} always was; the door answers it
     * {@code 422 api.ValidationFailed}. Its message never repeats the reason ({@code INV-AUD-02}).
     */
    public static final class ReasonRefused extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRefused(String detail) {
            super(detail);
        }
    }

    /** Identifiers and the state only - never a reason or a person ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplEnablement[" + id + ", merchant=" + merchantId + ", " + status + "]";
    }
}
