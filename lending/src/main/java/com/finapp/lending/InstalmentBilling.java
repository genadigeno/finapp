package com.finapp.lending;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.DueState;
import com.finapp.creditmath.EngineVersion;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One billed item of an instalment loan - an {@code instalment_billing} row (`P11-TSK-017`, {@code lending V012};
 * ADR-0094 point 6, {@code PHASE_11_PLAN.md} sections 12.2 step 5 and 12.3, {@code INV-LND-01}): what became due on
 * {@code period.endExclusive()} - the interest actually accrued over the period (never the projection) and the principal
 * portion - with the schedule, agreement and engine versions it was computed under and posting rule 3's journal entry by
 * value (empty for a billing of nothing, which writes the row and no entry). Born once per instalment, once per due date;
 * never changed. What is still due on it is billed less allocated - the allocation engine's {@link DueState.DueItem}.
 *
 * <p>{@code toString} names identifiers and dates only - billed amounts are {@code RESTRICTED-FINANCIAL}.
 *
 * @param instalment the instalment billed - present exactly for {@link BillingKind#INSTALMENT}
 * @param sequence the instalment's sequence; a post-maturity period's continues the schedule's count
 * @param period {@code [D(j-1), Dj)} - its end the due date
 */
public record InstalmentBilling(
        UUID id,
        LoanId loan,
        BillingKind kind,
        UUID schedule,
        int scheduleVersion,
        Optional<UUID> instalment,
        int sequence,
        AccrualPeriod period,
        Money interestDue,
        Money principalDue,
        LoanAgreementId agreement,
        int agreementVersion,
        EngineVersion scheduleEngine,
        EngineVersion accrualEngine,
        Optional<JournalEntryId> entry) {

    public InstalmentBilling {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(schedule, "schedule must not be null");
        Objects.requireNonNull(instalment, "instalment must not be null");
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(interestDue, "interestDue must not be null");
        Objects.requireNonNull(principalDue, "principalDue must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
        Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (scheduleVersion < 1 || agreementVersion < 1 || sequence < 1) {
            throw new IllegalArgumentException("a billing's versions and sequence are numbered from 1");
        }
        if ((kind == BillingKind.INSTALMENT) != instalment.isPresent()) {
            throw new IllegalArgumentException("a billing names its instalment exactly when it bills one");
        }
        if (!interestDue.currency().equals(principalDue.currency()) || interestDue.scale() != principalDue.scale()) {
            throw new IllegalArgumentException("a billing's figures are in one currency and scale");
        }
        if (interestDue.isNegative() || principalDue.isNegative()) {
            throw new IllegalArgumentException("what a billing makes due is zero or positive");
        }
        if (kind == BillingKind.POST_MATURITY && !principalDue.isZero()) {
            throw new IllegalArgumentException("a post-maturity period bills interest alone");
        }
        if (interestDue.plus(principalDue).isPositive() != entry.isPresent()) {
            throw new IllegalArgumentException("a billing names an entry exactly when it bills something");
        }
    }

    /** The due date - the period's end. */
    public java.time.LocalDate dueDate() {
        return period.endExclusive();
    }

    /** Everything it made due. */
    public Money billed() {
        return interestDue.plus(principalDue);
    }

    /** Identifiers and dates only. */
    @Override
    public String toString() {
        return "InstalmentBilling[" + loan.value() + ", " + kind + " #" + sequence + ", due " + dueDate() + "]";
    }
}
