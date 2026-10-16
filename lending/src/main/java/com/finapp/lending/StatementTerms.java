package com.finapp.lending;

import com.finapp.creditmath.AccrualTerms;
import com.finapp.creditmath.EngineVersion;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What a statement engine reads of a credit line's agreement (`P11-TSK-009`; ADR-0100 points 1 and 6, A23, A24,
 * {@code PHASE_11_PLAN.md} section 12.6): the currency, the statement day the customer chose, the minimum payment's
 * floor and ratio, the days from a statement to its payment's due date, the minimum payment's rounding mode and the
 * statement engine version - exactly as the terms (and, from `P11-TSK-012`, the agreement that pins them) name them.
 * Shape only, as {@link AccrualTerms} is: whether an engine version implements them is that engine's judgement
 * ({@link StatementRefused}), so a later version can accept what version 1 refuses.
 *
 * <p>{@code toString} names the engine version only - never the floor or the ratio ({@code INV-AUD-02}).
 *
 * @param currency the line's one currency
 * @param statementDay the day of the month every cycle ends on - the customer's choice within the terms' allowed days
 * @param minimumPaymentFloor the least the whole minimum payment is, unless the balance is less (A24: EUR 25.00)
 * @param minimumPaymentRatioBps the principal part's ratio of drawn principal, in basis points (A24: 300)
 * @param paymentDueDays the days from the statement date to the minimum payment's due date (A23: 25)
 * @param minimumPaymentRounding the rounding of the ratio's figure to the minor unit, as declared
 * @param statementEngine the statement engine version the figures must come from
 */
public record StatementTerms(
        CurrencyCode currency,
        int statementDay,
        Money minimumPaymentFloor,
        int minimumPaymentRatioBps,
        int paymentDueDays,
        RoundingMode minimumPaymentRounding,
        EngineVersion statementEngine) {

    public StatementTerms {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(minimumPaymentFloor, "minimumPaymentFloor");
        Objects.requireNonNull(minimumPaymentRounding, "minimumPaymentRounding");
        Objects.requireNonNull(statementEngine, "statementEngine");
    }

    /**
     * The statement conventions of a credit line's {@code terms} with the agreement's {@code statementDay}, as
     * declared - the minimum payment's rounding being the terms' instalment rounding ({@link LoanTerms}).
     *
     * @throws StatementRefused when {@code terms} are not a credit line's
     */
    public static StatementTerms of(LoanTerms terms, int statementDay) {
        Objects.requireNonNull(terms, "terms");
        LoanTerms.RevolvingTerms revolving = terms.revolving().orElseThrow(() -> new StatementRefused(
                StatementRefused.Reason.UNIMPLEMENTED_CONVENTION, "a statement is computed for a credit line's terms"));
        return new StatementTerms(terms.currency(), statementDay, revolving.minimumPaymentFloor(),
                revolving.minimumPaymentRatioBps(), revolving.paymentDueDays(), terms.instalmentRounding(),
                revolving.statementEngine());
    }

    /** The engine version only - never the floor or the ratio. */
    @Override
    public String toString() {
        return "StatementTerms[" + statementEngine + "]";
    }
}
