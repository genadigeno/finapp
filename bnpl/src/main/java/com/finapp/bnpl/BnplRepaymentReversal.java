package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL repayment reversal (`P12-TSK-024`, {@code bnpl V016}'s {@code bnpl_repayment_reversal}; lending's
 * {@code RepaymentReversal}): the four-eyes proposal to undo one repayment exactly - money-free (the amount is the
 * repayment's), naming the repayment's own agreement version (nothing is recomputed); once {@code APPROVED}, F8's entry
 * and the date it is valued. The repayment's {@code REVERSED} status is derived from this row, never written onto it.
 * The reasons are a person's prose - held on the row and in the audit trail, never in an event or a view.
 */
public record BnplRepaymentReversal(
        UUID id,
        BnplObligationId obligation,
        UUID repayment,
        BnplAgreementId agreement,
        int agreementVersion,
        BnplCorrectionStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<JournalEntryId> entry,
        Optional<LocalDate> valueDate) {

    public BnplRepaymentReversal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(repayment, "repayment must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("agreement versions are numbered from 1");
        }
        if ((status == BnplCorrectionStatus.PROPOSED) != decidedBy.isEmpty()
                || (status == BnplCorrectionStatus.APPROVED) != entry.isPresent()
                || (status == BnplCorrectionStatus.APPROVED) != valueDate.isPresent()) {
            throw new IllegalArgumentException("a reversal is decided exactly when it is not PROPOSED, and posts exactly"
                    + " when APPROVED");
        }
    }

    /** Identifiers and the status - never a reason ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplRepaymentReversal[" + id + ", " + status + "]";
    }
}
