package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The affordability assessment (`P10-TSK-009`; PHASE_10_PLAN.md section 12.3, ADR-0088 section 1; {@code INV-CRD-12},
 * {@code INV-MON-01}, {@code INV-MON-02}, {@code INV-CRD-07}) - engine version 1's arithmetic, read from the snapshot
 * alone, exact in the product's one currency.
 *
 * <pre>
 * income      = min(verified, declared)        -- verified = financial data, where present
 * expenditure = max(verified committed, declared)
 * obligations = the bureau's monthly obligations
 * repayment   = annuity(requested amount, term, assessment rate)    -- a term in MONTHS (PERSONAL_LOAN, BNPL_PAY_IN_N):
 *                                                                      a stress rate, not a price
 *             = limit x minimum payment ratio                       -- NONE (CREDIT_LINE)
 *             = largest instalment x plan stress factor             -- FIXED_PLAN (BNPL_PAY_IN_4, P12-TSK-001)
 * largest instalment = amount - (n - 1) x floor(amount / n)          -- n the plan's instalments, in minor units
 * disposable  = income - expenditure - obligations - repayment
 * affordable  &lt;=&gt; disposable &gt;= minimum disposable
 * </pre>
 *
 * <p><strong>Rounded once, at a declared point.</strong> The repayment is computed in {@code BigDecimal} at scale
 * {@value #SCALE} with {@code HALF_EVEN} through every step, and rounded to minor units {@code HALF_UP} exactly once, at
 * the end; a zero rate is the amount divided by the term. Everything else is exact {@link Money} arithmetic. The
 * formula, the scale and the rounding are the engine's - a change is a new engine version, the old kept for replay;
 * the rate, the ratio, the plan's stress factor and the minimum are the pinned policy's.
 *
 * <p><strong>The fixed plan's arm</strong> (`P12-TSK-001`, ADR-0102 point 6). A fixed plan splits the amount into its
 * {@code n} instalments with the remainder on the first (A4: {@code A = floor(P / n)} for instalments 2 to n, the first
 * {@code P - (n - 1)A}), so its largest instalment is the first - computed here, exactly, in minor units, by credit's own
 * arithmetic over the requested amount (credit never calls a BNPL engine). Pay-in-4's instalments fall every 14 days, so
 * up to three fall in one month; the policy's stress factor says how many largest instalments a month must carry. The
 * arm was added to engine version 1 for a product no decision under it ever named, so every recorded decision replays as
 * before ({@code INV-CRD-01}); the arm and each existing one answer only for their own term shape.
 *
 * <p><strong>Absence is never a zero.</strong> An income or an expenditure with neither figure present, or the
 * bureau's obligations absent, makes the figure {@link Assessment.Unassessable} naming what was absent - the policy
 * reasons about it as {@code ABSENT} or the evaluation fails; nothing is silently zero. A money input in another
 * currency is refused, never converted: the snapshot holds none ({@link SnapshotContent} refuses it), and the
 * parameters' minimum must be in the product's currency.
 */
public final class AffordabilityAssessment {

    /** The engine version whose arithmetic this is. */
    public static final int ENGINE_VERSION = 1;

    /** The working scale of the repayment's arithmetic. */
    public static final int SCALE = 10;

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private AffordabilityAssessment() {}

    /**
     * The pinned policy's affordability parameters.
     *
     * @param annualAssessmentRate the stress rate, a fraction per year ({@code 0.0899} is 8.99%) - never negative
     * @param minimumPaymentRatio a revolving line's minimum monthly payment as a fraction of its limit
     * @param minimumDisposable the disposable income an affordable request leaves at least
     * @param planStressFactor a fixed plan's: how many of its largest instalments a month carries - at least one
     */
    public record Parameters(BigDecimal annualAssessmentRate, BigDecimal minimumPaymentRatio, Money minimumDisposable,
            Optional<BigDecimal> planStressFactor) {
        public Parameters {
            Objects.requireNonNull(annualAssessmentRate, "annualAssessmentRate");
            Objects.requireNonNull(minimumPaymentRatio, "minimumPaymentRatio");
            Objects.requireNonNull(minimumDisposable, "minimumDisposable");
            Objects.requireNonNull(planStressFactor, "planStressFactor");
            if (annualAssessmentRate.signum() < 0 || minimumPaymentRatio.signum() <= 0
                    || minimumPaymentRatio.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("a non-negative rate and a payment ratio in (0, 1]");
            }
            if (planStressFactor.isPresent() && planStressFactor.get().compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("a plan's stress factor carries at least one instalment");
            }
        }

        /** Parameters with no plan stress factor - every product's but a fixed plan's. */
        public Parameters(BigDecimal annualAssessmentRate, BigDecimal minimumPaymentRatio, Money minimumDisposable) {
            this(annualAssessmentRate, minimumPaymentRatio, minimumDisposable, Optional.empty());
        }
    }

    /** What the assessment found. */
    public sealed interface Assessment permits Assessment.Assessed, Assessment.Unassessable {

        /** Every figure, in the product's currency. */
        record Assessed(
                Money income, Money expenditure, Money obligations, Money repayment, Money disposable, boolean affordable)
                implements Assessment {
            /** No figure renders. */
            @Override
            public String toString() {
                return "Assessed[affordable=" + affordable + "]";
            }
        }

        /** A required input was absent - named, so the policy can reason about it; never a zero. */
        record Unassessable(Set<CreditAttributeCode> absent) implements Assessment {
            public Unassessable {
                absent = Set.copyOf(absent);
            }
        }
    }

    /** Assesses the snapshot's request under the pinned parameters. */
    public static Assessment assess(SnapshotContent snapshot, Parameters parameters) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(parameters, "parameters");
        CurrencyCode currency = snapshot.product().currency();
        if (!parameters.minimumDisposable().currency().equals(currency)) {
            throw new IllegalArgumentException("the minimum disposable is in the product's currency (INV-CRD-12)");
        }
        Set<CreditAttributeCode> absent = EnumSet.noneOf(CreditAttributeCode.class);
        Optional<Money> verifiedIncome = money(snapshot, CreditAttributeCode.FINDATA_MONTHLY_INCOME);
        Optional<Money> declaredIncome = money(snapshot, CreditAttributeCode.DECLARED_MONTHLY_INCOME);
        Optional<Money> verifiedSpend = money(snapshot, CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE);
        Optional<Money> declaredSpend = money(snapshot, CreditAttributeCode.DECLARED_MONTHLY_EXPENDITURE);
        Optional<Money> obligations = money(snapshot, CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS);

        Optional<Money> income = lesser(verifiedIncome, declaredIncome);
        Optional<Money> expenditure = greater(verifiedSpend, declaredSpend);
        if (income.isEmpty()) {
            absent.add(CreditAttributeCode.DECLARED_MONTHLY_INCOME);
        }
        if (expenditure.isEmpty()) {
            absent.add(CreditAttributeCode.DECLARED_MONTHLY_EXPENDITURE);
        }
        if (obligations.isEmpty()) {
            absent.add(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS);
        }
        if (!absent.isEmpty()) {
            return new Assessment.Unassessable(absent);
        }
        Money repayment = repayment(snapshot, parameters);
        Money disposable = income.get().minus(expenditure.get()).minus(obligations.get()).minus(repayment);
        return new Assessment.Assessed(income.get(), expenditure.get(), obligations.get(), repayment, disposable,
                disposable.compareTo(parameters.minimumDisposable()) >= 0);
    }

    /** The monthly repayment the request implies - rounded once, to minor units, {@code HALF_UP}. */
    static Money repayment(SnapshotContent snapshot, Parameters parameters) {
        CurrencyCode currency = snapshot.product().currency();
        BigDecimal amount = snapshot.requestedAmount().toBigDecimal();
        CreditProduct product = snapshot.product();
        BigDecimal exact = switch (product.termShape()) {
            case NONE -> amount.multiply(parameters.minimumPaymentRatio()).setScale(SCALE, RoundingMode.HALF_EVEN);
            case MONTHS -> annuity(amount, snapshot.termMonths()
                    .orElseThrow(() -> new IllegalArgumentException("an instalment product's request names its term")),
                    parameters.annualAssessmentRate());
            case FIXED_PLAN -> largestInstalment(snapshot.requestedAmount(), product.planInstalments().orElseThrow())
                    .toBigDecimal()
                    .multiply(parameters.planStressFactor().orElseThrow(() -> new IllegalArgumentException(
                            "a fixed plan's policy states its stress factor")))
                    .setScale(SCALE, RoundingMode.HALF_EVEN);
        };
        return Money.of(exact, currency, RoundingPolicy.HALF_UP);
    }

    /**
     * A fixed plan's largest instalment: its first, {@code amount - (instalments - 1) x floor(amount / instalments)} in
     * minor units - exact, the A4 split, so the instalments sum to the amount.
     */
    static Money largestInstalment(Money amount, int instalments) {
        if (instalments < 1 || !amount.isPositive()) {
            throw new IllegalArgumentException("a plan splits a positive amount into at least one instalment");
        }
        long minor = amount.minorUnits();
        long later = Math.floorDiv(minor, instalments);
        return Money.ofMinorUnits(minor - (instalments - 1) * later, amount.currency());
    }

    /**
     * The level monthly payment repaying {@code amount} over {@code term} months at {@code annualRate}:
     * {@code amount x r / (1 - (1 + r)^-term)}, {@code r = annualRate / 12} - at scale {@value #SCALE},
     * {@code HALF_EVEN}, unrounded to money; a zero rate is {@code amount / term}.
     */
    static BigDecimal annuity(BigDecimal amount, int term, BigDecimal annualRate) {
        if (term < 1) {
            throw new IllegalArgumentException("a term is at least one month");
        }
        if (annualRate.signum() == 0) {
            return amount.divide(BigDecimal.valueOf(term), SCALE, RoundingMode.HALF_EVEN);
        }
        BigDecimal monthly = annualRate.divide(MONTHS_PER_YEAR, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal growth = BigDecimal.ONE.add(monthly).pow(term).setScale(SCALE, RoundingMode.HALF_EVEN);
        BigDecimal discount = BigDecimal.ONE.divide(growth, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal denominator = BigDecimal.ONE.subtract(discount);
        return amount.multiply(monthly).divide(denominator, SCALE, RoundingMode.HALF_EVEN);
    }

    /** A money attribute's value - in the product's currency, which {@link SnapshotContent} already guarantees. */
    private static Optional<Money> money(SnapshotContent snapshot, CreditAttributeCode code) {
        return snapshot.attribute(code).value() instanceof AttributeValue.MoneyValue money
                ? Optional.of(money.value())
                : Optional.empty();
    }

    private static Optional<Money> lesser(Optional<Money> verified, Optional<Money> declared) {
        if (verified.isPresent() && declared.isPresent()) {
            return Optional.of(verified.get().compareTo(declared.get()) <= 0 ? verified.get() : declared.get());
        }
        return declared.isPresent() ? declared : verified;
    }

    private static Optional<Money> greater(Optional<Money> verified, Optional<Money> declared) {
        if (verified.isPresent() && declared.isPresent()) {
            return Optional.of(verified.get().compareTo(declared.get()) >= 0 ? verified.get() : declared.get());
        }
        return declared.isPresent() ? declared : verified;
    }
}
