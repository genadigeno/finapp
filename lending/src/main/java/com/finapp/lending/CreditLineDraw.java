package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit line draw as recorded (`P11-TSK-021`, {@code lending V013}; ADR-0100 point 3): born once its posting exists -
 * posting rule 1, {@code DR LOAN_PRINCIPAL / CR CUSTOMER_WALLET} under {@code lending.draw:<id>} - and never changed. Its
 * figures are facts of the act; the line's balances stay ledger balances ({@code INV-LND-01}).
 *
 * @param drawKey the customer's idempotency key, unique per line - the arbiter a retried key converges on after its claim
 *     has lapsed
 * @param agreementVersion the agreement version whose limit the draw was judged against
 * @param journalEntry the posting's entry, by value (ADR-0029)
 * @param valueDate the business date of the database clock in the pinned servicing zone - the entry's value date, from
 *     which the draw accrues (`P11-TSK-016`'s line accrual on drawn principal)
 * @param drawnAt the database's instant of the draw
 */
public record CreditLineDraw(
        UUID id,
        LoanId loan,
        String drawKey,
        Money amount,
        LoanAgreementId agreement,
        int agreementVersion,
        JournalEntryId journalEntry,
        LocalDate valueDate,
        Instant drawnAt) {

    public CreditLineDraw {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(drawKey, "drawKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(drawnAt, "drawnAt must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a draw is a positive amount");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
    }

    /** Identifiers only - never the amount or the key ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "CreditLineDraw[" + id + ", loan " + loan.value() + ", redacted]";
    }
}
