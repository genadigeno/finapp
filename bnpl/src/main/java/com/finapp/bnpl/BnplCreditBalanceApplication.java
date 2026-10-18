package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Money already held applied - F3a's fact (`P12-TSK-015`; {@code bnpl V008}'s {@code bnpl_credit_balance_application} and
 * its allocation rows; {@code PHASE_12_PLAN.md} section 12.11 F3a; the Phase 11 gate's R2 from the first table;
 * {@code INV-LND-04} by analogy). What of the obligation's {@code BNPL_CREDIT_BALANCE} the pinned allocation engine applied
 * over its billed items still due, oldest first - at a billing's birth ({@link #billing()}, key
 * {@code bnpl.credit-application:<billing>}) or as the obligation's next catch-up ({@link #catchUp()}, key
 * {@code bnpl.credit-application:<obligation>:catch-up:<n>}) - exactly one of the two. The lines sum to the amount; the
 * value date is {@code max(today, floor)} read under B4. INSERT-only. RESTRICTED-FINANCIAL.
 */
public record BnplCreditBalanceApplication(
        UUID id,
        BnplObligationId obligation,
        Optional<UUID> billing,
        Optional<Integer> catchUp,
        Money amount,
        BnplAgreementId agreement,
        int agreementVersion,
        String allocationEngine,
        LocalDate valueDate,
        List<Line> lines,
        JournalEntryId entry,
        CorrelationId correlation) {

    public BnplCreditBalanceApplication {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(billing, "billing must not be null");
        Objects.requireNonNull(catchUp, "catchUp must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        if (billing.isPresent() == catchUp.isPresent() || catchUp.filter(n -> n < 1).isPresent()) {
            throw new IllegalArgumentException("an application is at a billing or a catch-up numbered from 1, never both");
        }
        if (!amount.isPositive() || lines.isEmpty()) {
            throw new IllegalArgumentException("an application applies something");
        }
        Money allocated = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (Line line : lines) {
            allocated = allocated.plus(line.amount());
        }
        if (!allocated.equals(amount)) {
            throw new IllegalArgumentException("an application's lines sum to it exactly (INV-LND-04)");
        }
    }

    /** One line: what it paid of one billing's component. */
    public record Line(UUID id, UUID billing, AllocationComponent component, Money amount) {

        public Line {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(billing, "billing must not be null");
            Objects.requireNonNull(component, "component must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("a line pays something");
            }
        }

        @Override
        public String toString() {
            return "Line[" + billing + ", " + component + "]";
        }
    }

    @Override
    public String toString() {
        return "BnplCreditBalanceApplication[" + id + ", " + billing.map(b -> "at " + b).orElse("catch-up " + catchUp.orElseThrow())
                + "]";
    }
}
