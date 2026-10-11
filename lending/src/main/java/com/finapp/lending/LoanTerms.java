package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.Builder;

/**
 * One product's terms - every rate, fee and servicing convention the engines read, nothing they infer (`P11-TSK-005`;
 * ADR-0092 sections 2-3, {@code PHASE_11_PLAN.md} section 12.1; {@code INV-LND-05}, {@code INV-LND-09}).
 *
 * <p><strong>Judged whole, before anything is written.</strong> A value of this type exists only if it is complete and
 * within bounds: the constructor refuses ({@link TermsInvalid}) a convention no engine version of this build implements
 * (a rounding mode, a day count its accrual engine does not compute, a variable zone), an allocation order omitting or
 * repeating a component, another product's terms, and every value outside the bounds lending declares - the rate
 * {@code 0..1}, the fee rules, delinquency bounds rising from 1 to the default threshold, repayment days within every
 * month, and the offer validity and disbursement deadline within the product's own declared ceilings
 * ({@link LoanProduct#offerValidity()}, {@link LoanProduct#disbursementDeadline()}). {@code lending V003} holds the
 * conventions and the shapes again for every writer.
 *
 * <p><strong>No amount or term bounds here</strong> (the `P11-TSK-002` refinement): the principal or limit and the term
 * of an offer are the approved decision's (A20), bounded by credit's product - one truth. Whether terms amortise across
 * those bounds is the schedule engine's judgement ({@code 422 lending.TermsNotAmortising}, wired by `P11-TSK-006`).
 *
 * <p><strong>No excluded feature has a field</strong> (L4, L8): no penalty or default rate, no prepayment fee, no APR,
 * no withdrawal period, no statutory cap. The jurisdiction is the agreement template's (A1).
 *
 * <p><strong>Re-judged on every read.</strong> {@link JdbcLoanTermsStore} rebuilds a stored version through this
 * constructor, so a stored version is always a valid value of this type - and therefore a bound is only ever WIDENED
 * by a code change; narrowing one needs a migration proving no stored version violates it (and the matching
 * {@code lending V003} {@code CHECK} narrowed with it), or the history of the versions in force becomes unreadable.
 *
 * <p>The record's {@code toString} names the product only - never a rate or an amount ({@code INV-AUD-02}).
 *
 * @param product the product these terms price - every amount is in its currency
 * @param nominalAnnualRate the nominal annual rate as a decimal fraction ({@code 0.0899} is 8.99 %), canonical: no
 *     trailing zero, at most 8 places, {@code 0..1}
 * @param rateType {@code FIXED}
 * @param originationFee an amount or basis points of the principal (A17), {@code 0} by default
 * @param originationFeeTreatment {@code DEDUCTED}
 * @param lateFee the late fee assessed on a past-due item (A18), zero for none
 * @param lateFeeDay the days past due at which it is assessed - beyond grace, at or before the default threshold
 * @param lateFeeCap the most late fee one account may be charged, at least one fee
 * @param graceDays how long the late fee waits after a due date (grace delays only the fee, never DPD - ADR-0098)
 * @param delinquencyBounds the four buckets' lower bounds in days past due, the first 1, the last the default threshold
 * @param defaultThresholdDays the days past due at which a loan defaults (L7: 90)
 * @param allocationSequence {@code OLDEST_DUE_FIRST}
 * @param allocationOrder every {@link AllocationComponent} exactly once, in the order a repayment pays them (L6)
 * @param overpaymentTreatment the product kind's own: {@code HOLD_AS_CREDIT} (loan), {@code PAY_DOWN_PRINCIPAL} (line)
 * @param autoCollection whether due amounts are collected from the wallet automatically (A21)
 * @param partialCollection whether an automatic collection may take less than the whole amount due (A21)
 * @param instalmentRounding {@code UP} - the schedule's level instalment and the line's minimum payment
 * @param interestRounding {@code HALF_EVEN} - the accrual engine's cumulative rounding
 * @param dayCount a convention the named accrual engine implements ({@code ACT_365F})
 * @param servicingZone what "a day" is (A2) - {@code UTC} or a fixed offset {@code +HH:MM} in whole minutes, canonical
 * @param allowedDays the repayment (loan) or statement (line) days a customer may choose, ascending, within 1-28
 * @param offerValidityDays how long an offer stays acceptable, at most the product's ceiling
 * @param payoffHorizonDays how far ahead a payoff quote may be good through
 * @param accrualEngine the accrual engine version
 * @param allocationEngine the allocation engine version
 * @param instalment an instalment loan's own terms - present exactly for an {@link LoanKind#INSTALMENT} product
 * @param revolving a credit line's own terms - present exactly for a {@link LoanKind#REVOLVING} product
 * @param agreementTemplate the registered agreement template of the product
 */
@Builder(toBuilder = true)
public record LoanTerms(
        LoanProduct product,
        BigDecimal nominalAnnualRate,
        RateType rateType,
        OriginationFee originationFee,
        OriginationFeeTreatment originationFeeTreatment,
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
        RoundingMode instalmentRounding,
        RoundingMode interestRounding,
        DayCountConvention dayCount,
        String servicingZone,
        List<Integer> allowedDays,
        int offerValidityDays,
        int payoffHorizonDays,
        EngineVersion accrualEngine,
        EngineVersion allocationEngine,
        Optional<InstalmentTerms> instalment,
        Optional<RevolvingTerms> revolving,
        AgreementTemplate agreementTemplate) {

    // ------------------------------------------------------------------ the bounds lending declares

    /** The highest nominal annual rate a terms version may name: 100 % a year. */
    public static final BigDecimal MAXIMUM_RATE = BigDecimal.ONE;

    /** A rate's most decimal places once canonical. */
    public static final int RATE_PLACES = 8;

    /** The origination fee in basis points, below the whole principal. */
    public static final int MAXIMUM_ORIGINATION_FEE_BPS = 9_999;

    /** Grace, in days after a due date. */
    public static final int MAXIMUM_GRACE_DAYS = 30;

    /** The default threshold's range, in days past due (L7: 90). */
    public static final int MINIMUM_DEFAULT_THRESHOLD_DAYS = 30;

    public static final int MAXIMUM_DEFAULT_THRESHOLD_DAYS = 180;

    /** The last repayment or statement day every month has. */
    public static final int LAST_ALLOWED_DAY = 28;

    /** The longest payoff horizon. */
    public static final int MAXIMUM_PAYOFF_HORIZON_DAYS = 90;

    /** A fixed servicing zone's widest offset. */
    public static final int MAXIMUM_ZONE_OFFSET_HOURS = 14;

    // ------------------------------------------------------------------ what this build's engines implement

    /** Instalment rounding: {@code SCHEDULE_ENGINE_V1}'s level instalment and {@code STATEMENT_ENGINE_V1}'s minimum. */
    static final RoundingMode IMPLEMENTED_INSTALMENT_ROUNDING = RoundingMode.UP;

    /** Interest rounding: {@code ACCRUAL_ENGINE_V1}'s cumulative rounding. */
    static final RoundingMode IMPLEMENTED_INTEREST_ROUNDING = RoundingMode.HALF_EVEN;

    public LoanTerms {
        require(product, "a product");
        require(nominalAnnualRate, "a nominal annual rate");
        require(rateType, "a rate type");
        require(originationFee, "an origination fee");
        require(originationFeeTreatment, "an origination fee treatment");
        require(lateFee, "a late fee");
        require(lateFeeCap, "a late fee cap");
        require(delinquencyBounds, "delinquency bounds");
        require(allocationSequence, "an allocation sequence");
        require(allocationOrder, "an allocation order");
        require(overpaymentTreatment, "an overpayment treatment");
        require(instalmentRounding, "an instalment rounding mode");
        require(interestRounding, "an interest rounding mode");
        require(dayCount, "a day count");
        require(servicingZone, "a servicing zone");
        require(allowedDays, "allowed days");
        require(accrualEngine, "an accrual engine version");
        require(allocationEngine, "an allocation engine version");
        require(instalment, "the instalment terms slot");
        require(revolving, "the revolving terms slot");
        require(agreementTemplate, "an agreement template");
        CurrencyCode currency = product.currency();

        nominalAnnualRate = canonicalRate(nominalAnnualRate);
        originationFee.judge(currency);
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
        if (delinquencyBounds.size() != DelinquencyBucket.values().length || delinquencyBounds.get(0) != 1
                || !strictlyAscending(delinquencyBounds)
                || delinquencyBounds.get(delinquencyBounds.size() - 1) != defaultThresholdDays) {
            throw new TermsInvalid("the delinquency bounds are one lower bound per bucket, rising from 1 to the default"
                    + " threshold");
        }
        allocationOrder = copy(allocationOrder, "an allocation order");
        if (allocationOrder.size() != AllocationComponent.values().length
                || !EnumSet.copyOf(allocationOrder).equals(EnumSet.allOf(AllocationComponent.class))) {
            throw new TermsInvalid("the allocation order names every component exactly once");
        }
        if (overpaymentTreatment != overpaymentOf(product.kind())) {
            throw new TermsInvalid("the overpayment treatment is the product kind's own");
        }
        if (instalmentRounding != IMPLEMENTED_INSTALMENT_ROUNDING || interestRounding != IMPLEMENTED_INTEREST_ROUNDING) {
            throw new TermsInvalid("the terms name a rounding mode no engine version implements");
        }
        engine(accrualEngine, LendingEngine.ACCRUAL, "the accrual engine");
        engine(allocationEngine, LendingEngine.ALLOCATION, "the allocation engine");
        if (dayCount.implementedBy().number() > accrualEngine.number()) {
            throw new TermsInvalid("the terms name a day count their accrual engine version does not implement");
        }
        servicingZone = canonicalZone(servicingZone);
        allowedDays = copy(allowedDays, "allowed days");
        if (allowedDays.isEmpty() || !strictlyAscending(allowedDays) || allowedDays.get(0) < 1
                || allowedDays.get(allowedDays.size() - 1) > LAST_ALLOWED_DAY) {
            throw new TermsInvalid("the allowed days are ascending, within 1 to " + LAST_ALLOWED_DAY + ", at least one");
        }
        if (offerValidityDays < 1 || offerValidityDays > product.offerValidity().toDays()) {
            throw new TermsInvalid("an offer's validity is at least a day and at most the product's");
        }
        if (payoffHorizonDays < 1 || payoffHorizonDays > MAXIMUM_PAYOFF_HORIZON_DAYS) {
            throw new TermsInvalid("the payoff horizon is 1 to " + MAXIMUM_PAYOFF_HORIZON_DAYS + " days");
        }
        if (instalment.isPresent() != (product.kind() == LoanKind.INSTALMENT)
                || revolving.isPresent() != (product.kind() == LoanKind.REVOLVING)) {
            throw new TermsInvalid("the terms carry their product kind's own terms and no other's");
        }
        instalment.ifPresent(own -> own.judge(product));
        revolving.ifPresent(own -> own.judge(product));
        if (agreementTemplate.product() != product) {
            throw new TermsInvalid("the agreement template is the product's own");
        }
    }

    /** The currency every amount of these terms is in - the product's. */
    public CurrencyCode currency() {
        return product.currency();
    }

    /** The product only - never a rate or an amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanTerms[" + product + "]";
    }

    // ------------------------------------------------------------------ the parts

    /** How an origination fee is stated (A17): an amount, or basis points of the principal - never both. */
    public enum FeeBasis {
        /** A fixed amount in the product's currency. */
        AMOUNT,
        /** Basis points of the principal (or limit). */
        BASIS_POINTS
    }

    /**
     * The origination fee: an amount ({@code amount} present) or basis points ({@code basisPoints} present), exactly
     * one. Zero is an amount of zero. Never printed.
     */
    public record OriginationFee(Optional<Money> amount, Optional<Integer> basisPoints) {

        public OriginationFee {
            require(amount, "the origination fee's amount slot");
            require(basisPoints, "the origination fee's basis-point slot");
            if (amount.isPresent() == basisPoints.isPresent()) {
                throw new TermsInvalid("the origination fee is an amount or basis points, exactly one");
            }
        }

        /** No origination fee: an amount of zero (A17's default). */
        public static OriginationFee none(CurrencyCode currency) {
            return new OriginationFee(Optional.of(Money.zero(currency)), Optional.empty());
        }

        public static OriginationFee ofAmount(Money amount) {
            return new OriginationFee(Optional.of(amount), Optional.empty());
        }

        public static OriginationFee ofBasisPoints(int basisPoints) {
            return new OriginationFee(Optional.empty(), Optional.of(basisPoints));
        }

        /** How it is stated. */
        public FeeBasis basis() {
            return amount.isPresent() ? FeeBasis.AMOUNT : FeeBasis.BASIS_POINTS;
        }

        void judge(CurrencyCode currency) {
            amount.ifPresent(fee -> {
                inCurrency(fee, currency, "the origination fee");
                if (fee.isNegative()) {
                    throw new TermsInvalid("the origination fee is zero or positive");
                }
            });
            basisPoints.ifPresent(bps -> {
                if (bps < 0 || bps > MAXIMUM_ORIGINATION_FEE_BPS) {
                    throw new TermsInvalid("the origination fee is 0 to " + MAXIMUM_ORIGINATION_FEE_BPS + " basis points");
                }
            });
        }

        @Override
        public String toString() {
            return "OriginationFee[" + basis() + "]";
        }
    }

    /**
     * An instalment loan's own terms (ADR-0093, ADR-0097): the shortest first period (15 days), the disbursement
     * deadline - at most the product's - and the schedule engine version.
     */
    public record InstalmentTerms(int minFirstPeriodDays, int disbursementDeadlineHours, EngineVersion scheduleEngine) {

        public InstalmentTerms {
            require(scheduleEngine, "a schedule engine version");
        }

        void judge(LoanProduct product) {
            if (minFirstPeriodDays < 1 || minFirstPeriodDays > LAST_ALLOWED_DAY) {
                throw new TermsInvalid("the shortest first period is 1 to " + LAST_ALLOWED_DAY + " days");
            }
            if (disbursementDeadlineHours < 1 || disbursementDeadlineHours > product.disbursementDeadline().toHours()) {
                throw new TermsInvalid("the disbursement deadline is at least an hour and at most the product's");
            }
            engine(scheduleEngine, LendingEngine.SCHEDULE, "the schedule engine");
        }

        @Override
        public String toString() {
            return "InstalmentTerms[" + scheduleEngine + "]";
        }
    }

    /**
     * A credit line's own terms (ADR-0100, A23, A24): the minimum payment's floor and ratio, the days from a statement
     * to its payment's due date, and the statement engine version.
     */
    public record RevolvingTerms(
            Money minimumPaymentFloor, int minimumPaymentRatioBps, int paymentDueDays, EngineVersion statementEngine) {

        public RevolvingTerms {
            require(minimumPaymentFloor, "a minimum payment floor");
            require(statementEngine, "a statement engine version");
        }

        void judge(LoanProduct product) {
            inCurrency(minimumPaymentFloor, product.currency(), "the minimum payment floor");
            if (!minimumPaymentFloor.isPositive()) {
                throw new TermsInvalid("the minimum payment floor is positive");
            }
            if (minimumPaymentRatioBps < 1 || minimumPaymentRatioBps > 10_000) {
                throw new TermsInvalid("the minimum payment ratio is 1 to 10000 basis points");
            }
            if (paymentDueDays < 1 || paymentDueDays >= LAST_ALLOWED_DAY) {
                throw new TermsInvalid("a statement's payment falls due 1 to " + (LAST_ALLOWED_DAY - 1)
                        + " days after it - before the next statement");
            }
            engine(statementEngine, LendingEngine.STATEMENT, "the statement engine");
        }

        @Override
        public String toString() {
            return "RevolvingTerms[" + statementEngine + "]";
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

    /** The overpayment treatment each kind's engines implement (L6, A26). */
    public static OverpaymentTreatment overpaymentOf(LoanKind kind) {
        return switch (kind) {
            case INSTALMENT -> OverpaymentTreatment.HOLD_AS_CREDIT;
            case REVOLVING -> OverpaymentTreatment.PAY_DOWN_PRINCIPAL;
        };
    }

    /** The rate in its one canonical spelling: no trailing zero, no exponent, 0..1, at most 8 places. */
    static BigDecimal canonicalRate(BigDecimal rate) {
        if (rate.signum() < 0 || rate.compareTo(MAXIMUM_RATE) > 0) {
            throw new TermsInvalid("the nominal annual rate is 0 to 1 - a decimal fraction, 0.05 for 5 %");
        }
        BigDecimal canonical = rate.signum() == 0 ? BigDecimal.ZERO : rate.stripTrailingZeros();
        if (canonical.scale() < 0) {
            canonical = canonical.setScale(0);
        }
        if (canonical.scale() > RATE_PLACES) {
            throw new TermsInvalid("the nominal annual rate has at most " + RATE_PLACES + " decimal places");
        }
        return canonical;
    }

    /** {@code UTC}, or a fixed offset in whole minutes spelled {@code +HH:MM} - never a region with daylight saving. */
    static String canonicalZone(String zone) {
        ZoneOffset offset;
        try {
            ZoneId id = ZoneId.of(zone);
            if (!id.getRules().isFixedOffset()) {
                throw new TermsInvalid("the servicing zone is a fixed offset: a day is never 23 or 25 hours");
            }
            offset = id.getRules().getOffset(java.time.Instant.EPOCH);
        } catch (DateTimeException malformed) {
            throw new TermsInvalid("the servicing zone is UTC or a fixed offset +HH:MM");
        }
        int seconds = offset.getTotalSeconds();
        if (seconds % 60 != 0 || Math.abs(seconds) > MAXIMUM_ZONE_OFFSET_HOURS * 3600) {
            throw new TermsInvalid("the servicing zone is UTC or a fixed offset +HH:MM in whole minutes, at most "
                    + MAXIMUM_ZONE_OFFSET_HOURS + " hours");
        }
        return seconds == 0 ? "UTC" : offset.getId();
    }

    private static void engine(EngineVersion version, LendingEngine engine, String what) {
        if (version.engine() != engine) {
            throw new TermsInvalid(what + " version is a version of that engine");
        }
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
