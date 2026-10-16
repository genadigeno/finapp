package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Money already held applied at a billing - a {@code credit_balance_application} row and its
 * {@code credit_balance_allocation} rows (`P11-TSK-017`, {@code lending V012}; ADR-0095 point 5, posting rule 6,
 * {@code INV-LND-04}): what {@link AllocationEngineV1#allocate} applied of the loan's credit balance over its billed
 * items, oldest first - only its lines on billed items ({@link AllocationLine.Target#DUE_ITEM}); its
 * {@link AllocationLine.Target#CREDIT_BALANCE} line is what stays held and is no line here. The lines sum to the amount
 * (asserted here, and held by the database at commit). Born at most once per billed item - or, credit held while
 * something is already due, as the loan's next <em>catch-up</em> application (the Phase 11 gate's R2, {@code lending V029}:
 * at a servicing step or a payoff, at no billed item's birth); never changed.
 *
 * @param billing the billed item at whose birth it was applied - empty for a catch-up application
 * @param catchUpSequence a catch-up application's place among its loan's (1, 2, ...) - empty at a billed item
 * @param lines the billed items' components it paid, in the engine's order - each a {@link AllocationLine.Target#DUE_ITEM}
 *     with its row identity
 * @param entry posting rule 6's journal entry
 */
public record CreditBalanceApplication(
        UUID id,
        Optional<UUID> billing,
        Optional<Integer> catchUpSequence,
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
        Objects.requireNonNull(catchUpSequence, "catchUpSequence must not be null");
        if (billing.isPresent() == catchUpSequence.isPresent()
                || catchUpSequence.filter(sequence -> sequence < 1).isPresent()) {
            throw new IllegalArgumentException("an application is born at a billed item or as a numbered catch-up, not both");
        }
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
        return "CreditBalanceApplication[" + loan.value() + ", "
                + billing.map(item -> "billing " + item).orElseGet(() -> "catch-up " + catchUpSequence.orElseThrow())
                + ", " + lines.size() + " lines]";
    }
}
