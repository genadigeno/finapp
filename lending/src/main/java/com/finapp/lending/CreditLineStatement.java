package com.finapp.lending;

import com.finapp.creditmath.DueState;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.LendingEngine;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One credit line statement - a {@code credit_line_statement} row (`P11-TSK-022`, {@code lending V017}; ADR-0100 point 6,
 * A23, A24, {@code PHASE_11_PLAN.md} section 12.6, {@code INV-LND-01}): the line's billed item. What
 * {@link StatementEngineV1} stated for one cycle - the interest the cycle's accruals posted, the fees it carries, the
 * minimum payment's principal part, the minimum payment and its due date - with the agreement and engine versions it was
 * computed under and posting rule 3's journal entry by value (empty for a statement of nothing, which writes the row and
 * no entry). Born once per line and cycle; never changed. <strong>No balance</strong>: opening and closing balances are
 * derived from the ledger by {@link CreditLineStatements#read}, never stored. What is still due on it is billed less
 * allocated - the allocation engine's {@link DueState.DueItem}, due on {@link #dueDate()}.
 *
 * <p>{@code toString} names identifiers and dates only - statement amounts are {@code RESTRICTED-FINANCIAL}.
 *
 * @param statement the engine's figures, its cycle and due date
 * @param agreementVersion the governing agreement version it was stated under
 * @param accrualEngine the accrual engine whose rows it bills
 * @param entry posting rule 3's journal entry - present exactly when it billed interest or principal
 */
public record CreditLineStatement(
        UUID id,
        LoanId loan,
        Statement statement,
        LoanAgreementId agreement,
        int agreementVersion,
        EngineVersion accrualEngine,
        Optional<JournalEntryId> entry) {

    public CreditLineStatement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(statement, "statement must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        if (accrualEngine.engine() != LendingEngine.ACCRUAL) {
            throw new IllegalArgumentException("a statement bills an accrual engine's rows");
        }
        // The parameters, not the accessors: a compact constructor's fields are assigned after its body.
        if (statement.interestBilled().plus(statement.principalBilled()).isPositive() != entry.isPresent()) {
            throw new IllegalArgumentException("a statement names an entry exactly when it bills something");
        }
    }

    /** The statement date - the cycle's end, the next cycle's first day. */
    public LocalDate statementDate() {
        return statement.statementDate();
    }

    /** The minimum payment's due date - the date its billed item falls due. */
    public LocalDate dueDate() {
        return statement.dueDate();
    }

    /** What rule 3 made due: the interest billed and the principal billed (carried fees are due on their own item). */
    public Money billed() {
        return statement.interestBilled().plus(statement.principalBilled());
    }

    /** Identifiers and dates only. */
    @Override
    public String toString() {
        return "CreditLineStatement[" + loan.value() + ", " + statementDate() + ", due " + dueDate() + "]";
    }
}
