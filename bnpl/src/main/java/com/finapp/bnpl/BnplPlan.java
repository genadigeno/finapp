package com.finapp.bnpl;

import java.util.OptionalInt;

/**
 * The instalment plans a BNPL purchase can be financed on (`P12-TSK-002`; owner decision B1, ADR-0102 decision 1,
 * {@code PHASE_12_PLAN.md} section 12.1) - what "plan" means in this module, and only that: the BNPL Schedule's rows are
 * BNPL Instalments, never a "plan" ({@code GLOSSARY.md}).
 *
 * <p>Pay-in-4's one plan, four instalments with no term sent to credit (credit's {@code FIXED_PLAN} shape); pay-in-N's
 * three, the customer's choice of 3, 6 or 12 monthly instalments - the term credit's request names (its {@code MONTHS}
 * shape over the admitted set {3, 6, 12}). Each plan belongs to exactly one {@link BnplProduct}, which lists it; the
 * correspondence with credit's products is held in {@code app} ({@code BnplVocabularyTest}), the only place both are
 * visible.
 */
public enum BnplPlan {

    /** Pay-in-4: four instalments, the first at purchase, then every 14 days (A4, A5) - a fixed plan, no term. */
    PAY_IN_4(4, false),

    /** Pay-in-N over 3 monthly instalments. */
    PAY_IN_3_MONTHS(3, true),

    /** Pay-in-N over 6 monthly instalments. */
    PAY_IN_6_MONTHS(6, true),

    /** Pay-in-N over 12 monthly instalments. */
    PAY_IN_12_MONTHS(12, true);

    private final int instalments;
    private final boolean termInMonths;

    BnplPlan(int instalments, boolean termInMonths) {
        if (instalments < 2) {
            throw new IllegalStateException(name() + ": a plan has at least two instalments");
        }
        this.instalments = instalments;
        this.termInMonths = termInMonths;
    }

    /** How many instalments the plan's BNPL Schedule has. */
    public int instalments() {
        return instalments;
    }

    /**
     * The term in months the plan's credit decision request names - present for a pay-in-N plan (its instalments, one a
     * month), empty for pay-in-4's fixed plan, which sends no term.
     */
    public OptionalInt termMonths() {
        return termInMonths ? OptionalInt.of(instalments) : OptionalInt.empty();
    }

    /** The product that lists this plan - exactly one, checked as {@link BnplProduct} loads. */
    public BnplProduct product() {
        for (BnplProduct product : BnplProduct.values()) {
            if (product.plans().contains(this)) {
                return product;
            }
        }
        throw new IllegalStateException(name() + ": every plan belongs to a product");
    }
}
