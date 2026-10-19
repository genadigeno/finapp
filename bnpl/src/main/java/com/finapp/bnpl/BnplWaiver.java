package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL fee waiver (`P12-TSK-024`, {@code bnpl V016}'s {@code bnpl_waiver} and {@code bnpl_waiver_decision}; lending's
 * {@code Waiver}): the four-eyes proposal to forgo an amount of one late fee's unpaid remainder - the assessment and its
 * instalment, the amount, the agreement version in force when proposed - and its decision, born once: {@code APPROVED}
 * naming F7's entry, or {@code REJECTED}. RESTRICTED-FINANCIAL (the amount); the reasons a person's prose.
 */
public record BnplWaiver(
        UUID id,
        BnplObligationId obligation,
        BnplWaiverKind kind,
        UUID assessment,
        UUID instalment,
        Money amount,
        BnplAgreementId agreement,
        int agreementVersion,
        BnplCorrectionStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<JournalEntryId> entry) {

    public BnplWaiver {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(instalment, "instalment must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a waiver of nothing is no waiver");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("agreement versions are numbered from 1");
        }
        if ((status == BnplCorrectionStatus.PROPOSED) != decidedBy.isEmpty()
                || (status == BnplCorrectionStatus.APPROVED) != entry.isPresent()) {
            throw new IllegalArgumentException("a waiver is decided exactly when it is not PROPOSED, and posts exactly"
                    + " when APPROVED");
        }
    }

    /** Identifiers and the status - never the amount or a reason ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplWaiver[" + id + ", " + kind + ", " + status + "]";
    }
}
