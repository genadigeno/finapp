package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A BNPL obligation's closure as recorded (`P12-TSK-018`, {@code bnpl V010}'s {@code bnpl_obligation_closure};
 * {@code INV-LND-08} by analogy, {@code BNPL_LIFECYCLES.md} section 4.7; lending's {@code LoanClosure}): born in the
 * transaction that brings the obligation's last receivable to zero, with the credit balance it refunded to the wallet - so
 * nothing is owed either way after it. Closure is a consequence, never a command. RESTRICTED-FINANCIAL.
 *
 * @param refund what the credit balance held, refunded to the wallet - zero or positive
 * @param refundEntry the refund's journal entry ({@code DR BNPL_CREDIT_BALANCE / CR CUSTOMER_WALLET}, key
 *     {@code bnpl.closure-refund:<obligation>}) - present exactly when the refund is positive
 * @param repayment the repayment whose transaction closed it - empty when the servicing step did, after a credit-balance
 *     application
 * @param closingRefund the BNPL refund whose transaction closed it (`P12-TSK-021`) - never beside a repayment
 */
public record BnplClosure(
        UUID id,
        BnplObligationId obligation,
        BnplClosureReason reason,
        Money refund,
        Optional<JournalEntryId> refundEntry,
        Optional<UUID> repayment,
        Optional<UUID> closingRefund,
        CorrelationId correlation) {

    /** A closure by no refund - the repayment's or the servicing step's (`P12-TSK-018`'s shape). */
    public BnplClosure(UUID id, BnplObligationId obligation, BnplClosureReason reason, Money refund,
            Optional<JournalEntryId> refundEntry, Optional<UUID> repayment, CorrelationId correlation) {
        this(id, obligation, reason, refund, refundEntry, repayment, Optional.empty(), correlation);
    }

    public BnplClosure {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(refund, "refund must not be null");
        Objects.requireNonNull(refundEntry, "refundEntry must not be null");
        Objects.requireNonNull(repayment, "repayment must not be null");
        Objects.requireNonNull(closingRefund, "closingRefund must not be null");
        if (repayment.isPresent() && closingRefund.isPresent()) {
            throw new IllegalArgumentException("one closing act: a repayment or a refund");
        }
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (refund.isNegative() || refund.isPositive() != refundEntry.isPresent()) {
            throw new IllegalArgumentException("a refund's entry exists exactly when a credit balance was refunded");
        }
    }

    /** Identifiers only. */
    @Override
    public String toString() {
        return "BnplClosure[" + id + ", obligation " + obligation.value() + ", " + reason + "]";
    }
}
