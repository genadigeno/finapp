package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * The amount and term bounds a loan product may be decided within - credit's, read through a port (`P11-TSK-006`;
 * ADR-0092 section 3 and its Follow-up, ADR-0093 section 5, A16).
 *
 * <p><strong>One truth, credit's.</strong> The principal and term of an offer are exactly the approved decision's (A20),
 * and the bounds a decision may be requested within are credit's {@code CreditProduct} - lending holds no second copy
 * (`P11-TSK-002`'s refinement). Lending has no build edge to {@code credit}, so {@code app} implements this over credit's
 * published product (the {@code crossborder} precedent); its adapter test holds the two to one value.
 *
 * <p>Read by {@link LoanTermsAdministration#propose} to judge, before anything is written, whether a terms version can
 * amortise across those bounds ({@link ScheduleEngineV1#judgeAmortisation}, {@code 422 lending.TermsNotAmortising}).
 * The bounds are code on every instance - a build's products are its code - so the judgement is the same everywhere.
 */
public interface LoanProductBounds {

    /**
     * The amortisation bounds of an instalment product, or empty for a revolving one, which has no term and no schedule.
     */
    Optional<Bounds> amortisationBounds(LoanProduct product);

    /**
     * The smallest and largest principal and term a decision may name, both inclusive.
     *
     * @param minimumPrincipal the smallest principal, positive
     * @param maximumPrincipal the largest principal, in the same currency, at least the smallest
     * @param minimumTermMonths the shortest term, at least one month
     * @param maximumTermMonths the longest term, at least the shortest
     */
    record Bounds(Money minimumPrincipal, Money maximumPrincipal, int minimumTermMonths, int maximumTermMonths) {

        public Bounds {
            Objects.requireNonNull(minimumPrincipal, "minimumPrincipal");
            Objects.requireNonNull(maximumPrincipal, "maximumPrincipal");
            if (!minimumPrincipal.isPositive() || minimumPrincipal.compareTo(maximumPrincipal) > 0) {
                throw new IllegalArgumentException("principal bounds are positive and ordered, in one currency");
            }
            if (minimumTermMonths < 1 || maximumTermMonths < minimumTermMonths) {
                throw new IllegalArgumentException("term bounds are at least a month and ordered");
            }
        }

        /** The bounds only by their terms - never an amount. */
        @Override
        public String toString() {
            return "Bounds[" + minimumTermMonths + ".." + maximumTermMonths + " months]";
        }
    }
}
