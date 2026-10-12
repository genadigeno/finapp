package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Money already held applied at a billing - a {@code credit_balance_application} row and its
 * {@code credit_balance_allocation} rows (`P11-TSK-017`, {@code lending V012}; ADR-0095 point 5, posting rule 6,
 * {@code INV-LND-04}): what {@link AllocationEngineV1#allocate} applied of the loan's credit balance over its billed
 * items, oldest first - only its lines on billed items ({@link AllocationLine.Target#DUE_ITEM}); its
 * {@link AllocationLine.Target#CREDIT_BALANCE} line is what stays held and is no line here. The lines sum to the amount
 * (asserted here, and held by the database at commit). Born at most once per billing; never changed.
 *
 * @param billing the billing at which it was applied
 * @param lines the billed items' components it paid, in the engine's order - each a {@link AllocationLine.Target#DUE_ITEM}
 *     with its row identity
 * @param entry posting rule 6's journal entry
 */
public record CreditBalanceApplication(
        UUID id,
        UUID billing,
        LoanId loan,
        Money amount,
        LoanAgreementId agreement,
        int agreementVersion,
        EngineVersion allocationEngine,
        List<Line> lines,
        JournalEntryId entry) {

    public CreditBalanceApplication {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(billing, "billing must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        if (!amount.isPositive() || lines.isEmpty()) {
            throw new IllegalArgumentException("an application applies something");
        }
        Money total = Money.ofPersisted(0, amount.currency(), amount.scale());
        for (Line line : lines) {
            total = total.plus(line.line().amount());
        }
        if (!total.equals(amount)) {
            throw new IllegalArgumentException("an application's lines sum to it");
        }
    }

    /** One allocation row: its identity and the engine's line, which pays a billed item's component. */
    public record Line(UUID id, AllocationLine line) {

        public Line {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(line, "line must not be null");
            if (line.target() != AllocationLine.Target.DUE_ITEM) {
                throw new IllegalArgumentException("an application's lines pay billed items");
            }
        }
    }

    /** Identifiers only. */
    @Override
    public String toString() {
        return "CreditBalanceApplication[" + loan.value() + ", billing " + billing + ", " + lines.size() + " lines]";
    }
}
