package com.finapp.bnpl;

import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;

/**
 * The purchase amount and plan bounds a BNPL product may be decided within - credit's, read through a port
 * (`P12-TSK-004`; A16, ADR-0102 decision 1, lending's {@code LoanProductBounds} precedent).
 *
 * <p><strong>One truth, credit's.</strong> A purchase's amount is the checkout session's gross, and the bounds a decision
 * may be requested within are credit's {@code CreditProduct} - bnpl holds no second copy. bnpl has no build edge to
 * {@code credit}, so {@code app} implements this ({@code CreditBnplProductBounds}); its adapter test holds the two to
 * one value. Read by {@link BnplTermsAdministration#propose} to judge, before anything is written, whether a terms
 * version can schedule every purchase within those bounds ({@code 422 bnpl.TermsNotAmortising}). Code on every instance,
 * so the judgement is the same everywhere.
 */
public interface BnplProductBounds {

    /** The product's bounds - every BNPL product has them. */
    Bounds of(BnplProduct product);

    /**
     * The smallest and largest purchase a decision may name, both inclusive, and the terms in months a request may name.
     *
     * @param minimumAmount the smallest purchase, positive
     * @param maximumAmount the largest, in the same currency, at least the smallest
     * @param admittedTermMonths the terms credit admits for a months-shaped product, ascending; empty for a fixed plan,
     *     which sends no term
     */
    record Bounds(Money minimumAmount, Money maximumAmount, List<Integer> admittedTermMonths) {

        public Bounds {
            Objects.requireNonNull(minimumAmount, "minimumAmount");
            Objects.requireNonNull(maximumAmount, "maximumAmount");
            Objects.requireNonNull(admittedTermMonths, "admittedTermMonths");
            admittedTermMonths = List.copyOf(admittedTermMonths);
            if (!minimumAmount.isPositive() || minimumAmount.compareTo(maximumAmount) > 0) {
                throw new IllegalArgumentException("amount bounds are positive and ordered, in one currency");
            }
        }

        /** The bounds only by their terms - never an amount. */
        @Override
        public String toString() {
            return "Bounds[" + admittedTermMonths + " months]";
        }
    }
}
