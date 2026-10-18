package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL Instalment billed - F2's fact (`P12-TSK-013` for pay-in-4's instalment 1 at purchase, `P12-TSK-015` for every
 * instalment at its due date; {@code bnpl V006}, extended by {@code V008}; {@code PHASE_12_PLAN.md} section 12.11 F2,
 * {@code INV-BNPL-05}, {@code INV-LND-02} by analogy). The same claim reclassified as due: its principal (and pay-in-N's
 * interest - exactly the period's posted accruals, never the projection) moved by the entry {@code bnpl.billing:<instalment>}
 * ({@code DR BNPL_PRINCIPAL_DUE / CR BNPL_PRINCIPAL}, {@code DR BNPL_INTEREST_DUE / CR BNPL_INTEREST_ACCRUED}), value-dated
 * its due date, under the schedule and agreement versions and the engines it names. Born once per instalment, INSERT-only;
 * {@link #entry()} empty for a billing of nothing (the row and no entry). RESTRICTED-FINANCIAL.
 *
 * @param periodStart the start of the period it closes - the previous instalment's due date, the first the schedule's
 *     start; its end is {@link #dueDate()}
 * @param dueDate the instalment's due date - the billing's value date
 */
public record BnplBilling(
        UUID id,
        UUID instalment,
        int sequence,
        BnplObligationId obligation,
        UUID scheduleId,
        int scheduleVersion,
        String scheduleEngine,
        BnplAgreementId agreement,
        int agreementVersion,
        Optional<String> accrualEngine,
        LocalDate periodStart,
        LocalDate dueDate,
        Money principal,
        Money interest,
        Optional<JournalEntryId> entry,
        CorrelationId correlation) {

    public BnplBilling {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(instalment, "instalment must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
        Objects.requireNonNull(periodStart, "periodStart must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(interest, "interest must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (sequence < 1 || scheduleVersion < 1 || agreementVersion < 1) {
            throw new IllegalArgumentException("a billing's sequence and versions are numbered from 1");
        }
        if (principal.isNegative() || interest.isNegative()) {
            throw new IllegalArgumentException("a billing bills zero or more of each component");
        }
        if (periodStart.isAfter(dueDate)) {
            throw new IllegalArgumentException("a billing's period starts on or before its due date");
        }
        if (entry.isPresent() != principal.plus(interest).isPositive()) {
            throw new IllegalArgumentException("a billing has an entry exactly when it bills something");
        }
    }

    /** What it made due - principal and interest. */
    public Money amount() {
        return principal.plus(interest);
    }

    @Override
    public String toString() {
        return "BnplBilling[" + instalment + ", " + sequence + ", due " + dueDate + "]";
    }
}
