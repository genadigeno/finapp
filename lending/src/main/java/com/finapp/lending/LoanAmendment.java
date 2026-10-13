package com.finapp.lending;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A restructuring as recorded (`P11-TSK-027`, {@code lending V023}; ADR-0099 point 5, {@code INV-LND-11}): the proposal to
 * amend one agreement version of an instalment loan - a rate never above it, a remaining term never shorter than its
 * schedule has left, whether the arrears are re-scheduled - its proposer and reason, the SHA-256 of the document the
 * customer is shown; once decided, the decider, their reason and - an approval - the offer's expiry; once accepted, the
 * agreement version it made. Names no amount: an amendment never raises principal outstanding.
 *
 * @param amendsVersion the agreement version it amends - its acceptance is conditional on that version being current
 * @param amendsRate the rate of the agreement version it amends - the document's "from"
 * @param nominalAnnualRate the amended rate, canonical
 * @param remainingInstalments the new schedule version's instalments
 * @param rescheduleArrears whether billed principal past due is re-classified as not due (posting rule 13)
 * @param approved whether its decision is an approval - the customer sees only an amendment that was offered to them
 * @param expiresAt the offer's expiry, exactly when approved
 * @param acceptedVersion the agreement version its acceptance made, exactly when {@code ACCEPTED}
 */
public record LoanAmendment(
        UUID id,
        LoanId loan,
        LoanAgreementId amendsAgreement,
        int amendsVersion,
        BigDecimal amendsRate,
        BigDecimal nominalAnnualRate,
        int remainingInstalments,
        boolean rescheduleArrears,
        byte[] documentSha256,
        LoanAmendmentStatus status,
        String proposedBy,
        Instant proposedAt,
        String proposalReason,
        boolean approved,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason,
        Optional<Instant> expiresAt,
        Optional<Integer> acceptedVersion) {

    public LoanAmendment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(amendsAgreement, "amendsAgreement must not be null");
        Objects.requireNonNull(amendsRate, "amendsRate must not be null");
        Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate must not be null");
        documentSha256 = Objects.requireNonNull(documentSha256, "documentSha256 must not be null").clone();
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        Objects.requireNonNull(proposalReason, "proposalReason must not be null");
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        Objects.requireNonNull(decisionReason, "decisionReason must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(acceptedVersion, "acceptedVersion must not be null");
        if (amendsVersion < 1 || remainingInstalments < 1 || documentSha256.length != 32) {
            throw new IllegalArgumentException("an amendment amends a numbered version over at least one instalment");
        }
        if (approved != expiresAt.isPresent() || (approved && decidedBy.isEmpty())) {
            throw new IllegalArgumentException("an approved amendment, and only one, carries its offer's expiry");
        }
        if ((status == LoanAmendmentStatus.ACCEPTED) != acceptedVersion.isPresent()) {
            throw new IllegalArgumentException("an accepted amendment, and only one, names the version it made");
        }
    }

    @Override
    public byte[] documentSha256() {
        return documentSha256.clone();
    }

    /** The document hash in lowercase hexadecimal - what the customer echoes when accepting. */
    public String documentSha256Hex() {
        return HexFormat.of().formatHex(documentSha256);
    }

    /** Identifiers and the status - never the rate or a reason ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanAmendment[" + id + ", amends v" + amendsVersion + ", " + status + "]";
    }
}
