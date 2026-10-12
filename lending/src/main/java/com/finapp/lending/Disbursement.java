package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An instalment loan's disbursement as recorded (`P11-TSK-014`, {@code lending V006} and {@code V008};
 * {@code LENDING_LIFECYCLES.md} section 4.6): its state, its path, its deadline and - once {@code POSTED} - the journal
 * entry of posting rule 1 and that entry's value date. No amount: the principal is the agreement's, the money the
 * ledger's ({@code INV-LND-01}).
 *
 * @param payoutMethod path X's payment method by opaque reference - empty for a wallet disbursement (path W)
 * @param deadlineAt when a wallet that cannot be posted stops being retried and the loan is cancelled
 * @param journalEntry the entry of posting rule 1, once {@code POSTED}
 * @param valueDate that entry's value date, once {@code POSTED}
 */
public record Disbursement(
        UUID id,
        LoanId loan,
        DisbursementStatus status,
        Optional<UUID> payoutMethod,
        Instant createdAt,
        Instant deadlineAt,
        Optional<JournalEntryId> journalEntry,
        Optional<LocalDate> valueDate) {

    public Disbursement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(payoutMethod, "payoutMethod must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
    }

    /** The path the borrower chose at the application (A25, ADR-0097): the wallet, or an external account. */
    public PayoutPreference path() {
        return payoutMethod.isPresent() ? PayoutPreference.EXTERNAL_ACCOUNT : PayoutPreference.WALLET;
    }

    /** Never the payout method ({@code INV-RAIL-03}). */
    @Override
    public String toString() {
        return "Disbursement[" + loan.value() + ", " + status + ", " + path() + "]";
    }
}
