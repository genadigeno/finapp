package com.finapp.bnpl;

import com.finapp.creditmath.Act365Fixed;
import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationSequence;
import com.finapp.creditmath.DayCountConvention;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.LendingEngine;
import com.finapp.creditmath.OverpaymentTreatment;
import com.finapp.creditmath.ScheduleTerms;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import lombok.Builder;

/**
 * One BNPL product's term set - every plan shape, rate, fee and servicing convention the engines and sweeps will read,
 * nothing they infer (`P12-TSK-004`; ADR-0102 decisions 1-4, {@code PHASE_12_PLAN.md} sections 2.3 and 12.1-12.3;
 * {@code INV-LND-05} and {@code INV-LND-09} by analogy, {@code INV-HIST-04}). Lending's {@code LoanTerms} shape, bnpl's
 * own fields.
 *
 * <p><strong>Judged whole, before anything is written.</strong> A value of this type exists only if it is complete and
 * within bounds: the constructor refuses ({@link TermsInvalid}) a convention no engine version of this build implements,
 * an allocation order omitting or repeating a component, another product's plan terms, a plan the product does not list,
 * and every value outside the bounds below. {@code bnpl V002} holds the conventions and shapes again for every writer.
 * Whether a pay-in-N set amortises across credit's amount bounds is the schedule engine's judgement at the door
 * ({@code BnplTermsAdministration}, {@code 422 bnpl.TermsNotAmortising}) - no amount bound is a terms field (A16).
 *
 * <p><strong>The two products' own parts</strong> - exactly one present, the product's: pay-in-4's
 * {@link PayIn4Terms} (four instalments every 14 days, the first at purchase, {@code BNPL_SCHEDULE_ENGINE_V1}, no interest
 * - the rate held at zero, the instalment rounded {@code DOWN} with the remainder on instalment 1, A4/OQ-3) and pay-in-N's
 * {@link PayInNTerms} (its plans among 3, 6 and 12 months, the first-period floor of A6, ACT/365F,
 * {@code SCHEDULE_ENGINE_V1} and {@code ACCRUAL_ENGINE_V1}, instalment {@code UP} and interest {@code HALF_EVEN}).
 *
 * <p><strong>No excluded feature has a field</strong> (A1, L8, ADR-0102 decision 4): no penalty interest, prepayment
 * fee, APR, cooling-off or withdrawal period, statutory cap, customer fee beside the late fee (OQ-17), rate type (the
 * rate is fixed for the version's life, OQ-4) or merchant fee (merchant's schedules, `P12-TSK-006`). The jurisdiction is
 * the agreement template's (A1).
 *
 * <p><strong>Re-judged on every read</strong> ({@code JdbcBnplTermsStore} rebuilds a stored version through this
 * constructor), so a bound is only ever WIDENED by a code change; narrowing one needs a migration proving no stored
 * version violates it. {@code toString} names the product only - never a rate or an amount ({@code INV-AUD-02}).
 *
 * @param product the product these terms price - every amount is in its currency
 * @param frequency the product's own instalment frequency ({@code BIWEEKLY} pay-in-4, {@code MONTHLY} pay-in-N)
 * @param nominalAnnualRate the fixed nominal annual rate as a canonical decimal fraction, {@code 0} for pay-in-4
 * @param lateFee the late fee assessed once per past-due instalment (A13), zero for none
 * @param lateFeeDay the days past due at which it is assessed - beyond grace, at or before the default threshold
 * @param lateFeeCap the most late fee one agreement may be charged, at least one fee
 * @param graceDays how long the late fee waits after a due date (grace delays only the fee, never DPD)
 * @param delinquencyBounds the buckets' lower bounds in days past due, the first 1, the last the default threshold -
 *     exactly {@link #DELINQUENCY_BOUNDS} while the buckets' labels name them (the Phase 12 transition gate's A7-5)
 * @param defaultThresholdDays the days past due at which an obligation defaults (A14: 90 - the only value the bounds admit)
 * @param allocationSequence {@code OLDEST_DUE_FIRST}
 * @param allocationOrder every {@link AllocationComponent} exactly once, in the order a repayment pays them
 * @param overpaymentTreatment {@code HOLD_AS_CREDIT} - both products (applied at the next billing, refunded at closure)
 * @param autoCollection whether due instalments are collected from the wallet automatically (A7)
 * @param partialCollection whether an automatic collection may take less than the whole amount due (A7)
 * @param collectionRetryDays the days between collection attempts while an instalment stays due (A7: daily, 1)
 * @param instalmentRounding {@code DOWN} for pay-in-4 (the remainder first), {@code UP} for pay-in-N's level instalment
 * @param servicingZone what "a day" is (A22) - {@code UTC}
 * @param offerValidityMinutes the cap on an offer's life, at most 30 minutes (A17, OQ-18)
 * @param disputeResponseDays the merchant's window to answer a dispute (OQ-10: 14 days)
 * @param disputeFourEyesThreshold the disputed amount above which a resolution needs a second person (ADR-0106 decision 5)
 * @param payoffHorizonDays how far ahead a payoff quote may be good through (`P12-TSK-022`, Phase 11's quote shape)
 * @param allocationEngine the allocation engine version ({@code ALLOCATION_ENGINE_V1})
 * @param refundAllocationEngine the refund's latest-first allocator ({@code REFUND_ALLOCATION_ENGINE_V1})
 * @param payIn4 pay-in-4's own terms - present exactly for {@link BnplProduct#BNPL_PAY_IN_4}
 * @param payInN pay-in-N's own terms - present exactly for {@link BnplProduct#BNPL_PAY_IN_N}
 * @param agreementTemplate the product's registered agreement template
 */
@Builder(toBuilder = true)
public record BnplTerms(
        BnplProduct product,
        InstalmentFrequency frequency,
        BigDecimal nominalAnnualRate,
        Money lateFee,
        int lateFeeDay,
        Money lateFeeCap,
        int graceDays,
        List<Integer> delinquencyBounds,
        int defaultThresholdDays,
        AllocationSequence allocationSequence,
        List<AllocationComponent> allocationOrder,
        OverpaymentTreatment overpaymentTreatment,
        boolean autoCollection,
        boolean partialCollection,
        int collectionRetryDays,
        RoundingMode instalmentRounding,
        String servicingZone,
        int offerValidityMinutes,
        int disputeResponseDays,
        Money disputeFourEyesThreshold,
        int payoffHorizonDays,
        EngineVersion allocationEngine,
        BnplEngine refundAllocationEngine,
        Optional<PayIn4Terms> payIn4,
        Optional<PayInNTerms> payInN,
        BnplAgreementTemplate agreementTemplate) {

    // ------------------------------------------------------------------ the bounds bnpl declares

    /** Grace, in days after a due date. */
    public static final int MAXIMUM_GRACE_DAYS = 30;

    /** The default threshold's range, in days past due (A14: 90). */
    public static final int MINIMUM_DEFAULT_THRESHOLD_DAYS = 30;

    public static final int MAXIMUM_DEFAULT_THRESHOLD_DAYS = 180;

    /** The delinquency buckets' count - lending's four (ADR-0098), named by `P12-TSK-020`. */
    public static final int DELINQUENCY_BUCKETS = 4;

    /**
     * The one set of delinquency bounds this build admits (the Phase 12 transition gate's A7-5): the buckets' recorded
     * labels ({@link BnplDelinquencyBucket} - {@code B1_1_29} ... {@code B4_90_PLUS}) carry these bounds in their names, so
     * a version declaring others would record a band under a label that misnames it. Until the labels derive from the
     * bounds (an owner's choice, ADR-0102), the terms door refuses any other - and with it any default threshold but 90.
     */
    public static final List<Integer> DELINQUENCY_BOUNDS = List.of(1, 30, 60, 90);

    /** The longest wait between two collection attempts of a due instalment. */
    public static final int MAXIMUM_COLLECTION_RETRY_DAYS = 7;

    /** The offer validity cap (A17, OQ-18): an offer lives at most 30 minutes, and never beyond its session. */
    public static final int MAXIMUM_OFFER_VALIDITY_MINUTES = 30;

    /** The merchant's dispute response window's widest setting. */
    public static final int MAXIMUM_DISPUTE_RESPONSE_DAYS = 60;

    /** The longest payoff horizon (lending's). */
    public static final int MAXIMUM_PAYOFF_HORIZON_DAYS = 90;

    /** Pay-in-4's days between instalments (A5) - its engine's, so the shape is stated once (`P12-TSK-008`). */
    public static final int PAY_IN_4_INTERVAL_DAYS = BnplScheduleEngineV1.INTERVAL_DAYS;

    /** Pay-in-N's first-period floor's range (A6): above 28 a purchase on the 29th-31st would skip a month. */
    public static final int MAXIMUM_PAY_IN_N_FIRST_PERIOD_DAYS = 28;

    /** The one servicing zone this build implements (A22). */
    public static final String SERVICING_ZONE = "UTC";

    // ------------------------------------------------------------------ what this build's engines implement

    /** Pay-in-4: {@code floor(P / 4)} for instalments 2-4, the remainder on instalment 1 - its engine's (`P12-TSK-008`). */
    static final RoundingMode PAY_IN_4_INSTALMENT_ROUNDING = BnplScheduleEngineV1.INSTALMENT_ROUNDING;

    /** Pay-in-N: {@code SCHEDULE_ENGINE_V1}'s level instalment. */
    static final RoundingMode PAY_IN_N_INSTALMENT_ROUNDING = RoundingMode.UP;

    /** Pay-in-N: {@code ACCRUAL_ENGINE_V1}'s cumulative rounding. */
    static final RoundingMode PAY_IN_N_INTEREST_ROUNDING = RoundingMode.HALF_EVEN;

    public BnplTerms {
        require(product, "a product");
        require(frequency, "an instalment frequency");
        require(nominalAnnualRate, "a nominal annual rate");
        require(lateFee, "a late fee");
        require(lateFeeCap, "a late fee cap");
        require(delinquencyBounds, "delinquency bounds");
        require(allocationSequence, "an allocation sequence");
        require(allocationOrder, "an allocation order");
        require(overpaymentTreatment, "an overpayment treatment");
        require(instalmentRounding, "an instalment rounding mode");
        require(servicingZone, "a servicing zone");
        require(disputeFourEyesThreshold, "a dispute four-eyes threshold");
        require(allocationEngine, "an allocation engine version");
        require(refundAllocationEngine, "a refund allocation engine version");
        require(payIn4, "the pay-in-4 terms slot");
        require(payInN, "the pay-in-N terms slot");
        require(agreementTemplate, "an agreement template");
        CurrencyCode currency = product.currency();

        if (frequency != product.frequency()) {
            throw new TermsInvalid("the instalment frequency is the product's own");
        }
        nominalAnnualRate = canonicalRate(nominalAnnualRate);
        inCurrency(lateFee, currency, "the late fee");
        inCurrency(lateFeeCap, currency, "the late fee cap");
        if (lateFee.isNegative()) {
            throw new TermsInvalid("the late fee is zero or positive");
        }
        if (lateFeeCap.compareTo(lateFee) < 0) {
            throw new TermsInvalid("the late fee cap admits at least one late fee");
        }
        if (graceDays < 0 || graceDays > MAXIMUM_GRACE_DAYS) {
            throw new TermsInvalid("grace is 0 to " + MAXIMUM_GRACE_DAYS + " days");
        }
        if (defaultThresholdDays < MINIMUM_DEFAULT_THRESHOLD_DAYS || defaultThresholdDays > MAXIMUM_DEFAULT_THRESHOLD_DAYS) {
            throw new TermsInvalid("the default threshold is " + MINIMUM_DEFAULT_THRESHOLD_DAYS + " to "
                    + MAXIMUM_DEFAULT_THRESHOLD_DAYS + " days past due");
        }
        if (lateFeeDay <= graceDays || lateFeeDay > defaultThresholdDays) {
            throw new TermsInvalid("the late fee is assessed beyond grace and at or before the default threshold");
        }
        delinquencyBounds = copy(delinquencyBounds, "delinquency bounds");
        if (delinquencyBounds.size() != DELINQUENCY_BUCKETS || delinquencyBounds.get(0) != 1
                || !strictlyAscending(delinquencyBounds)
                || delinquencyBounds.get(delinquencyBounds.size() - 1) != defaultThresholdDays) {
            throw new TermsInvalid("the delinquency bounds are one lower bound per bucket, rising from 1 to the default"
                    + " threshold");
        }
        if (!delinquencyBounds.equals(DELINQUENCY_BOUNDS)) {
            throw new TermsInvalid("the delinquency bounds are " + DELINQUENCY_BOUNDS + " and the default threshold "
                    + DELINQUENCY_BOUNDS.get(DELINQUENCY_BOUNDS.size() - 1) + " days - the buckets' labels name them");
        }
        if (allocationSequence != AllocationSequence.OLDEST_DUE_FIRST) {
            throw new TermsInvalid("the allocation sequence is oldest due first");
        }
        allocationOrder = copy(allocationOrder, "an allocation order");
        if (allocationOrder.size() != AllocationComponent.values().length
                || !EnumSet.copyOf(allocationOrder).equals(EnumSet.allOf(AllocationComponent.class))) {
            throw new TermsInvalid("the allocation order names every component exactly once");
        }
        if (overpaymentTreatment != OverpaymentTreatment.HOLD_AS_CREDIT) {
            throw new TermsInvalid("an overpayment is held as a credit balance");
        }
        if (collectionRetryDays < 1 || collectionRetryDays > MAXIMUM_COLLECTION_RETRY_DAYS) {
            throw new TermsInvalid("a due instalment is retried every 1 to " + MAXIMUM_COLLECTION_RETRY_DAYS + " days");
        }
        if (!SERVICING_ZONE.equals(servicingZone)) {
            throw new TermsInvalid("the servicing zone is UTC - the one zone this build services in");
        }
        if (offerValidityMinutes < 1 || offerValidityMinutes > MAXIMUM_OFFER_VALIDITY_MINUTES) {
            throw new TermsInvalid("an offer's validity is 1 to " + MAXIMUM_OFFER_VALIDITY_MINUTES + " minutes");
        }
        if (disputeResponseDays < 1 || disputeResponseDays > MAXIMUM_DISPUTE_RESPONSE_DAYS) {
            throw new TermsInvalid("the dispute response window is 1 to " + MAXIMUM_DISPUTE_RESPONSE_DAYS + " days");
        }
        inCurrency(disputeFourEyesThreshold, currency, "the dispute four-eyes threshold");
        if (disputeFourEyesThreshold.isNegative()) {
            throw new TermsInvalid("the dispute four-eyes threshold is zero or positive");
        }
        if (payoffHorizonDays < 1 || payoffHorizonDays > MAXIMUM_PAYOFF_HORIZON_DAYS) {
            throw new TermsInvalid("the payoff horizon is 1 to " + MAXIMUM_PAYOFF_HORIZON_DAYS + " days");
        }
        if (allocationEngine.engine() != LendingEngine.ALLOCATION) {
            throw new TermsInvalid("the allocation engine version is a version of that engine");
        }
        if (refundAllocationEngine.kind() != BnplEngine.Kind.REFUND_ALLOCATION) {
            throw new TermsInvalid("the refund allocation engine version is a version of that engine");
        }
        boolean fixed = product.shape() == BnplProduct.PlanShape.FIXED;
        if (payIn4.isPresent() != fixed || payInN.isPresent() == fixed) {
            throw new TermsInvalid("the terms carry their product's own plan terms and no other's");
        }
        if (fixed) {
            payIn4.get().judge(product);
            if (nominalAnnualRate.signum() != 0) {
                throw new TermsInvalid("pay-in-4 charges no interest: its rate is zero");
            }
            if (instalmentRounding != PAY_IN_4_INSTALMENT_ROUNDING) {
                throw new TermsInvalid("the terms name a rounding mode no engine version implements");
            }
        } else {
            payInN.get().judge(product);
            if (instalmentRounding != PAY_IN_N_INSTALMENT_ROUNDING) {
                throw new TermsInvalid("the terms name a rounding mode no engine version implements");
            }
        }
        if (agreementTemplate.product() != product) {
            throw new TermsInvalid("the agreement template is the product's own");
        }
    }

    /** The currency every amount of these terms is in - the product's. */
    public CurrencyCode currency() {
        return product.currency();
    }

    /**
     * Pay-in-N's schedule terms ({@code SCHEDULE_ENGINE_V1}'s input, ADR-0102 decision 3): the currency, the fixed rate,
     * the first-period floor, the day count, both roundings and the pinned schedule engine, exactly as declared; empty for
     * pay-in-4, whose schedule is bnpl's own engine's.
     */
    public Optional<ScheduleTerms> scheduleTerms() {
        return payInN.map(own -> new ScheduleTerms(currency(), nominalAnnualRate, own.minFirstPeriodDays(),
                own.dayCount(), instalmentRounding, own.interestRounding(), own.scheduleEngine()));
    }

    /** The product only - never a rate or an amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplTerms[" + product + "]";
    }

    // ------------------------------------------------------------------ the parts

    /**
     * Pay-in-4's own terms (A4, A5, ADR-0102 decision 2): four instalments, 14 days apart, the first at purchase - the
     * plan {@link BnplPlan#PAY_IN_4} - on {@code BNPL_SCHEDULE_ENGINE_V1}.
     */
    public record PayIn4Terms(int instalments, int intervalDays, BnplEngine scheduleEngine) {

        public PayIn4Terms {
            require(scheduleEngine, "a schedule engine version");
        }

        void judge(BnplProduct product) {
            if (instalments != product.plans().get(0).instalments()) {
                throw new TermsInvalid("pay-in-4 is " + product.plans().get(0).instalments() + " instalments");
            }
            if (intervalDays != PAY_IN_4_INTERVAL_DAYS) {
                throw new TermsInvalid("pay-in-4's instalments fall due every " + PAY_IN_4_INTERVAL_DAYS + " days");
            }
            if (scheduleEngine.kind() != BnplEngine.Kind.BNPL_SCHEDULE) {
                throw new TermsInvalid("the schedule engine version is a version of pay-in-4's schedule engine");
            }
        }

        @Override
        public String toString() {
            return "PayIn4Terms[" + scheduleEngine + "]";
        }
    }

    /**
     * Pay-in-N's own terms (A6, ADR-0102 decision 3): the plans offered - a non-empty ascending choice among the product's
     * 3, 6 and 12 months - the first-period floor (1 to 28 days: one month after the purchase, clamped), ACT/365F,
     * interest {@code HALF_EVEN}, and the schedule and accrual engine versions.
     */
    public record PayInNTerms(
            List<Integer> allowedTerms,
            int minFirstPeriodDays,
            DayCountConvention dayCount,
            RoundingMode interestRounding,
            EngineVersion scheduleEngine,
            EngineVersion accrualEngine) {

        public PayInNTerms {
            require(allowedTerms, "the plans offered");
            require(dayCount, "a day count");
            require(interestRounding, "an interest rounding mode");
            require(scheduleEngine, "a schedule engine version");
            require(accrualEngine, "an accrual engine version");
            allowedTerms = copy(allowedTerms, "the plans offered");
        }

        void judge(BnplProduct product) {
            List<Integer> listed = product.plans().stream().map(plan -> plan.termMonths().orElseThrow()).toList();
            if (allowedTerms.isEmpty() || !strictlyAscending(allowedTerms) || !listed.containsAll(allowedTerms)) {
                throw new TermsInvalid("the plans offered are an ascending choice among the product's " + listed
                        + " months, at least one");
            }
            if (minFirstPeriodDays < 1 || minFirstPeriodDays > MAXIMUM_PAY_IN_N_FIRST_PERIOD_DAYS) {
                throw new TermsInvalid("the first-period floor is 1 to " + MAXIMUM_PAY_IN_N_FIRST_PERIOD_DAYS + " days");
            }
            if (interestRounding != PAY_IN_N_INTEREST_ROUNDING) {
                throw new TermsInvalid("the terms name a rounding mode no engine version implements");
            }
            if (scheduleEngine.engine() != LendingEngine.SCHEDULE) {
                throw new TermsInvalid("the schedule engine version is a version of that engine");
            }
            if (accrualEngine.engine() != LendingEngine.ACCRUAL) {
                throw new TermsInvalid("the accrual engine version is a version of that engine");
            }
            if (dayCount.implementedBy().number() > accrualEngine.number()) {
                throw new TermsInvalid("the terms name a day count their accrual engine version does not implement");
            }
        }

        /** The longest plan offered - the term the amortisation judgement reads. */
        public int longestTerm() {
            return allowedTerms.get(allowedTerms.size() - 1);
        }

        @Override
        public String toString() {
            return "PayInNTerms[" + scheduleEngine + ", " + accrualEngine + "]";
        }
    }

    /** A terms proposal refused whole; the detail names the defect, never a value. */
    public static final class TermsInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public TermsInvalid(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The rate in its one canonical spelling: no trailing zero, no exponent, {@code 0..1}, at most
     * {@link Act365Fixed#RATE_PLACES} places - the accrual arithmetic's own bounds, so no admitted rate is one the engines
     * refuse.
     */
    static BigDecimal canonicalRate(BigDecimal rate) {
        if (rate.signum() < 0 || rate.compareTo(Act365Fixed.MAXIMUM_RATE) > 0) {
            throw new TermsInvalid("the nominal annual rate is 0 to 1 - a decimal fraction, 0.05 for 5 %");
        }
        BigDecimal canonical = rate.signum() == 0 ? BigDecimal.ZERO : rate.stripTrailingZeros();
        if (canonical.scale() < 0) {
            canonical = canonical.setScale(0);
        }
        if (canonical.scale() > Act365Fixed.RATE_PLACES) {
            throw new TermsInvalid("the nominal annual rate has at most " + Act365Fixed.RATE_PLACES + " decimal places");
        }
        return canonical;
    }

    private static void inCurrency(Money amount, CurrencyCode currency, String what) {
        if (!amount.currency().equals(currency) || amount.scale() != currency.minorUnits()) {
            throw new TermsInvalid(what + " is in the product's currency, at its scale");
        }
    }

    private static boolean strictlyAscending(List<Integer> values) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null || (i > 0 && values.get(i) <= values.get(i - 1))) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> copy(List<T> values, String what) {
        for (T value : values) {
            require(value, what + " without a gap");
        }
        return List.copyOf(values);
    }

    private static <T> T require(T value, String what) {
        if (value == null) {
            throw new TermsInvalid("the terms name " + what);
        }
        return value;
    }
}
