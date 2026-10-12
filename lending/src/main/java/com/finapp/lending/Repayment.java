package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A repayment as recorded (`P11-TSK-018`, {@code lending V014}; ADR-0095 point 3, posting rule 5): money from the
 * borrower's wallet, born only once it moved, with the allocation that split it - and never changed. Its figures are facts
 * of the act; the loan's balances stay ledger balances ({@code INV-LND-01}). {@code REVERSED} is never written here: an
 * approved reversal ({@code P11-TSK-020}) derives it.
 *
 * @param requestKey the requester's idempotency key, unique per loan and kind - the arbiter a retried key converges on
 *     after its claim has lapsed
 * @param agreementVersion the agreement version it was allocated under
 * @param allocationEngine the allocation engine version that split it (the agreement's pinned one)
 * @param allocations its lines in the engine's order, summing to the amount (asserted here and held at commit)
 * @param journalEntry posting rule 5's entry, by value (ADR-0029)
 * @param valueDate the business date of the database clock in the pinned zone - the entry's value date
 * @param receivedAt the database's instant of the repayment
 */
public record Repayment(
        UUID id,
        LoanId loan,
        RepaymentKind kind,
        String requestKey,
        Money amount,
        LoanAgreementId agreement,
        int agreementVersion,
        EngineVersion allocationEngine,
        List<RepaymentAllocation> allocations,
        JournalEntryId journalEntry,
        LocalDate valueDate,
        Instant receivedAt) {

    public Repayment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(journalEntry, "journalEntry must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations must not be null"));
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a repayment is a positive amount");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        Money total = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (RepaymentAllocation allocation : allocations) {
            total = total.plus(allocation.line().amount());
        }
        if (!total.equals(amount)) {
            throw new IllegalArgumentException("a repayment's allocations sum to it (INV-LND-04)");
        }
    }

    /** What its allocation paid of {@code component} across billed items. */
    public Money paid(AllocationComponent component) {
        Money paid = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (RepaymentAllocation allocation : allocations) {
            AllocationLine line = allocation.line();
            if (line.target() == AllocationLine.Target.DUE_ITEM && line.component().orElseThrow() == component) {
                paid = paid.plus(line.amount());
            }
        }
        return paid;
    }

    /** What its allocation sent to {@code target}, summed. */
    public Money to(AllocationLine.Target target) {
        Money sum = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (RepaymentAllocation allocation : allocations) {
            if (allocation.line().target() == target) {
                sum = sum.plus(allocation.line().amount());
            }
        }
        return sum;
    }

    /** Identifiers only - never the amount or the key ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "Repayment[" + id + ", loan " + loan.value() + ", " + kind + ", redacted]";
    }
}
