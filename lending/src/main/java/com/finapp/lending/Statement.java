package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One credit-line cycle's statement, as a statement engine computed it (`P11-TSK-009`; ADR-0100 point 6, A23, A24) -
 * the figures `P11-TSK-022`'s {@code credit_line_statement} row records: what the cycle billed and nothing else. The
 * cycle, the interest it bills ({@code ACCRUED -> DUE}), the fees it carries, the principal part it bills
 * ({@code DR LOAN_PRINCIPAL_DUE / CR LOAN_PRINCIPAL}), the minimum payment, its due date, and the engine version that
 * computed them.
 *
 * <p><strong>No balance.</strong> Opening and closing balances are derived by the caller from the ledger, never
 * produced here as figures that could drift (ADR-0100 point 6) - and so is the line's drawn principal, which this
 * value deliberately does not carry.
 *
 * <p><strong>Asserted at construction:</strong> every figure in one currency at its scale, none negative; the minimum
 * payment exactly the interest billed plus the fees carried plus the principal billed (A24); the statement date the
 * cycle's end, and the due date after it. A value of this type that broke one cannot exist.
 *
 * <p>{@code toString} names the statement date and the engine only - statement amounts are
 * {@code RESTRICTED-FINANCIAL}, never in a log line ({@code INV-AUD-02}).
 *
 * @param cycle the statement cycle - its end is the statement date, the next cycle's first day
 * @param interestBilled the cycle's posted accruals, summed - the interest the statement makes due
 * @param feesCarried the fees assessed in the cycle that the statement carries into its minimum payment
 * @param principalBilled the minimum payment's principal part, made due by the statement
 * @param minimumPayment interest billed + fees carried + principal billed
 * @param dueDate the statement date plus the agreement's payment-due days
 * @param engine the engine version that computed it
 */
public record Statement(
        AccrualPeriod cycle,
        Money interestBilled,
        Money feesCarried,
        Money principalBilled,
        Money minimumPayment,
        LocalDate dueDate,
        EngineVersion engine) {

    public Statement {
        Objects.requireNonNull(cycle, "cycle");
        Objects.requireNonNull(interestBilled, "interestBilled");
        Objects.requireNonNull(feesCarried, "feesCarried");
        Objects.requireNonNull(principalBilled, "principalBilled");
        Objects.requireNonNull(minimumPayment, "minimumPayment");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(engine, "engine");
        if (engine.engine() != LendingEngine.STATEMENT) {
            throw new IllegalArgumentException("a statement is computed by a statement engine");
        }
        for (Money figure : List.of(interestBilled, feesCarried, principalBilled, minimumPayment)) {
            if (figure.isNegative() || !figure.currency().equals(interestBilled.currency())
                    || figure.scale() != interestBilled.currency().minorUnits()) {
                throw new IllegalArgumentException("a statement's figures are not negative, in one currency at its scale");
            }
        }
        if (!minimumPayment.equals(interestBilled.plus(feesCarried).plus(principalBilled))) {
            throw new IllegalArgumentException("the minimum payment is the interest billed, the fees carried and the"
                    + " principal billed");
        }
        if (!dueDate.isAfter(cycle.endExclusive())) {
            throw new IllegalArgumentException("a statement's payment falls due after its statement date");
        }
    }

    /** The statement date: the cycle's end, the day after its last accrual date. */
    public LocalDate statementDate() {
        return cycle.endExclusive();
    }

    /** The statement date and the engine only. */
    @Override
    public String toString() {
        return "Statement[" + statementDate() + ", " + engine + "]";
    }
}
