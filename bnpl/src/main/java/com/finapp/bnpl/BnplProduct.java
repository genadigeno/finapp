package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The BNPL products bnpl offers (`P12-TSK-002`; owner decision B1, ADR-0102 decision 1, {@code PHASE_12_PLAN.md}
 * section 12.1).
 *
 * <p><strong>Closed, and named for credit's products.</strong> Each member is the priced, serviced counterpart of the
 * {@code CreditProduct} of the same name - {@code BNPL_PAY_IN_4} and {@code BNPL_PAY_IN_N}, credit's BNPL family since
 * `P12-TSK-001` - whose decision a BNPL application requests and a BNPL agreement consumes. bnpl has no build edge to
 * {@code credit} (ADR-0101 decision 6), so the correspondence is by name and is checked where both types are visible:
 * {@code app}'s {@code BnplVocabularyTest} holds the BNPL products to exactly credit's BNPL family, the same currency, and
 * plan shapes that agree with credit's term shapes ({@link PlanShape#FIXED} with {@code FIXED_PLAN} and its declared
 * instalments, {@link PlanShape#MONTHS} with {@code MONTHS} and its admitted terms).
 *
 * <p><strong>No amount bounds here, deliberately</strong> (A16, the {@code LoanProduct} precedent): the purchase amount
 * is the checkout session's gross, and the bounds it must lie within are credit's - one truth. What a product declares is
 * only what bnpl itself decides: its currency (EUR, A2), its instalment frequency, the shape of its plans and the plans.
 * Checked as the class loads - a fixed-shape product lists exactly one plan with no term, a months-shaped product only
 * plans with a term of one month per instalment, and every {@link BnplPlan} belongs to exactly one product - so a member
 * that disagreed with itself fails every use of the type.
 */
public enum BnplProduct {

    /** Pay-in-4 (P4): EUR, every two weeks, its one fixed plan of four instalments. */
    BNPL_PAY_IN_4(euro(), InstalmentFrequency.BIWEEKLY, PlanShape.FIXED, List.of(BnplPlan.PAY_IN_4)),

    /** Pay-in-N (PN): EUR, monthly, the customer's choice of 3, 6 or 12 instalments. */
    BNPL_PAY_IN_N(euro(), InstalmentFrequency.MONTHLY, PlanShape.MONTHS,
            List.of(BnplPlan.PAY_IN_3_MONTHS, BnplPlan.PAY_IN_6_MONTHS, BnplPlan.PAY_IN_12_MONTHS));

    /**
     * The shape of a product's plans - bnpl's reading of credit's term shape: whether the product's one plan is fixed, so
     * the decision request names no term, or the customer chooses a term in months among the product's plans.
     */
    public enum PlanShape {

        /** One plan, fixed by the product; no term is sent (credit's {@code FIXED_PLAN}). */
        FIXED,

        /** The customer's choice of a term in months, one instalment a month (credit's {@code MONTHS}). */
        MONTHS
    }

    // The declaration checked as the class loads, by iteration over values() - no static collection, no state.
    static {
        Set<BnplPlan> listed = EnumSet.noneOf(BnplPlan.class);
        for (BnplProduct product : values()) {
            for (BnplPlan plan : product.plans) {
                if (!listed.add(plan)) {
                    throw new IllegalStateException(plan + ": a plan belongs to exactly one product");
                }
            }
        }
        if (!listed.equals(EnumSet.allOf(BnplPlan.class))) {
            throw new IllegalStateException("every plan belongs to a product");
        }
    }

    private final CurrencyCode currency;
    private final InstalmentFrequency frequency;
    private final PlanShape shape;
    private final List<BnplPlan> plans;

    BnplProduct(CurrencyCode currency, InstalmentFrequency frequency, PlanShape shape, List<BnplPlan> plans) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.frequency = Objects.requireNonNull(frequency, "frequency");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
        if (this.plans.isEmpty()) {
            throw new IllegalStateException(name() + ": a product has at least one plan");
        }
        for (BnplPlan plan : this.plans) {
            boolean agrees = shape == PlanShape.FIXED
                    ? this.plans.size() == 1 && plan.termMonths().isEmpty()
                    : plan.termMonths().isPresent() && plan.termMonths().getAsInt() == plan.instalments()
                            && frequency == InstalmentFrequency.MONTHLY;
            if (!agrees) {
                throw new IllegalStateException(name() + ": " + plan + " does not have the product's plan shape");
            }
        }
    }

    /** The product's one currency - every amount of its purchases, agreements, postings and facts is in it. */
    public CurrencyCode currency() {
        return currency;
    }

    /** How often its instalments fall due. */
    public InstalmentFrequency frequency() {
        return frequency;
    }

    /** Whether its one plan is fixed or the customer chooses a term in months. */
    public PlanShape shape() {
        return shape;
    }

    /** The plans a purchase of this product can be financed on, in declaration order. */
    public List<BnplPlan> plans() {
        return plans;
    }

    private static CurrencyCode euro() {
        return CurrencyCode.of("EUR");
    }
}
