package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * The one pure pricing function (`P9-TSK-002`; ADR-0074 §§2-7, 11; {@code PHASE_9_PLAN.md}
 * §12.2): a fixed leg, a provider's firm quote and a pricing pair in, a frozen posting plan or a
 * typed refusal out.
 *
 * <p><strong>Why the residual exists, and why it is bounded.</strong> A plan holds three
 * independently rounded amounts - the provider's stated counter, the customer leg at the customer
 * rate, and the margin - and three roundings never sum to the exact product. What is left is the
 * residual {@code r}: source-fixed {@code r = Dp − Dc − M}, destination-fixed
 * {@code r = Sc − Sp − M}. From the exact identities {@code S·rp = S·rc + S·(rp − rc)} and
 * {@code D/rc = D/rp + D(rp − rc)/(rc·rp)}, with coherence holding the provider term strictly below
 * one minor unit, every half policy contributes at most ½ per rounding, so {@code |r| < 2} and,
 * being integral, {@code |r| <= 1}; any named policy contributes below 1, so {@code |r| <= 2}. The
 * residual is posted on its own line ({@code INV-FX-07}, {@code INV-BAL-03}) - never folded into
 * the margin, the customer amount or the position - and a residual past its policy's proven bound
 * is {@link Refusal#PLAN_INVARIANT_VIOLATED}: a defect, never a shipped price.
 *
 * <p><strong>Three rules keep the arithmetic honest.</strong>
 * <ul>
 *   <li>The margin is computed from the ROUNDED customer rate, so the rate's rounding cannot hide
 *       margin ({@code INV-FX-03}).
 *   <li>No rate is ever inverted. The destination-fixed customer leg is one exactly-rounded
 *       division, and the provider's coherence is judged by cross-multiplication, so no division
 *       enters it at all.
 *   <li>Every amount ends in exactly one named rounding; the two derived figures too.
 * </ul>
 *
 * <p>Deterministic and total over its typed refusals: the same inputs always give the same
 * plan - the basis of replay ({@code INV-FX-05}). An argument that is not a pricing outcome but a
 * caller's defect - a quote for another pair, an amount in the wrong currency or at the wrong
 * scale - is an {@link IllegalArgumentException}.
 */
public final class ConversionPlan {

    private ConversionPlan() {}

    /** Why a plan was not issued. Each is a typed outcome; none is ever rounded away. */
    public enum Refusal {
        /** The provider's stated counter disagrees with its own rate; the next provider is tried. */
        PROVIDER_QUOTE_INCOHERENT,
        /** The margin computed from the rounded customer rate is negative: a defect. */
        MARGIN_NEGATIVE,
        /** {@code spread + markup = 0}: the margin could not be attributed. */
        MARGIN_UNATTRIBUTABLE,
        /** The fixed leg is outside the pair's bounds, or the computed leg is not positive. */
        AMOUNT_OUT_OF_RANGE,
        /** The residual exceeds the policy's proven bound: CRITICAL, the quote is not issued. */
        PLAN_INVARIANT_VIOLATED
    }

    /** The plan's outcome: priced, or refused with a reason. */
    public sealed interface Result permits Priced, Refused {}

    /** A plan was computed. */
    public record Priced(Plan plan) implements Result {
        public Priced {
            Objects.requireNonNull(plan, "plan must not be null");
        }
    }

    /** No plan: the typed reason and a human-readable detail (never shown to a customer). */
    public record Refused(Refusal refusal, String detail) implements Result {
        public Refused {
            Objects.requireNonNull(refusal, "refusal must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /**
     * The frozen posting plan: every amount a later posting copies.
     *
     * @param fixedSide which leg the customer fixed
     * @param providerRate {@code rp}
     * @param customerRate {@code rc = round(rp × (1 − spread − markup), rateScale, rateRounding)}
     * @param internalRate {@code round(rp × (1 − spread), MAX_SCALE, rateRounding)} - the dealing
     *     rate, stored for Phase 14's split, never posted
     * @param customerPays the source amount the customer gives: {@code S}, or {@code Sc}
     * @param customerReceives the destination amount delivered: {@code Dc}, or {@code D}
     * @param positionSource the source the position sells (CR {@code FX_POSITION(S)}): {@code S},
     *     or the provider's stated {@code Sp}
     * @param positionDestination the destination the position buys (DR {@code FX_POSITION(D)}):
     *     the provider's stated {@code Dp}, or {@code D}
     * @param margin {@code M}, in the computed leg's currency, never negative
     * @param spreadMargin the spread's part of {@code M}
     * @param markupMargin the markup's part of {@code M}
     * @param residual {@code r}, in the computed leg's currency
     */
    public record Plan(
            FixedSide fixedSide,
            ExchangeRate providerRate,
            ExchangeRate customerRate,
            ExchangeRate internalRate,
            Money customerPays,
            Money customerReceives,
            Money positionSource,
            Money positionDestination,
            Money margin,
            Money spreadMargin,
            Money markupMargin,
            Money residual) {

        public Plan {
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            Objects.requireNonNull(providerRate, "providerRate must not be null");
            Objects.requireNonNull(customerRate, "customerRate must not be null");
            Objects.requireNonNull(internalRate, "internalRate must not be null");
            Objects.requireNonNull(customerPays, "customerPays must not be null");
            Objects.requireNonNull(customerReceives, "customerReceives must not be null");
            Objects.requireNonNull(positionSource, "positionSource must not be null");
            Objects.requireNonNull(positionDestination, "positionDestination must not be null");
            Objects.requireNonNull(margin, "margin must not be null");
            Objects.requireNonNull(spreadMargin, "spreadMargin must not be null");
            Objects.requireNonNull(markupMargin, "markupMargin must not be null");
            Objects.requireNonNull(residual, "residual must not be null");
        }

        /** The computed leg's currency: where the margin and the residual arise. */
        public CurrencyCode computedCurrency() {
            return margin.currency();
        }
    }

    /**
     * Prices one conversion.
     *
     * @param fixedSide which leg {@code fixedAmount} fixes
     * @param fixedAmount the fixed leg, exact at its currency's minor units
     * @param quote the provider's firm quote for this pair's direction
     * @param pair the pricing terms, pinned by the caller
     * @return the plan, or the typed refusal
     * @throws IllegalArgumentException for a caller's defect, never for a pricing outcome
     */
    public static Result compute(
            FixedSide fixedSide, Money fixedAmount, ProviderQuote quote, PricingPair pair) {
        Objects.requireNonNull(fixedSide, "fixedSide must not be null");
        Objects.requireNonNull(fixedAmount, "fixedAmount must not be null");
        Objects.requireNonNull(quote, "quote must not be null");
        Objects.requireNonNull(pair, "pair must not be null");
        ExchangeRate rp = quote.rate();
        if (!rp.source().equals(pair.source()) || !rp.destination().equals(pair.destination())) {
            throw new IllegalArgumentException(
                    "The quote prices " + rp.source() + "→" + rp.destination() + ", the pair "
                            + pair.source() + "→" + pair.destination());
        }
        boolean sourceFixed = fixedSide == FixedSide.FIXED_SOURCE;
        CurrencyCode fixedCurrency = sourceFixed ? pair.source() : pair.destination();
        CurrencyCode computedCurrency = sourceFixed ? pair.destination() : pair.source();
        requireExact(fixedAmount, fixedCurrency, "the fixed amount");
        requireExact(quote.statedCounter(), computedCurrency, "the provider's stated counter");

        if (pair.totalMargin().signum() == 0) {
            return refused(Refusal.MARGIN_UNATTRIBUTABLE,
                    "spread + markup = 0: a margin left by rounding could not be attributed");
        }
        NotionalBounds bounds = sourceFixed ? pair.sourceBounds() : pair.destinationBounds();
        if (!bounds.admits(fixedAmount)) {
            return refused(Refusal.AMOUNT_OUT_OF_RANGE,
                    "the fixed leg " + fixedAmount + " is outside [" + bounds.minimum() + ", "
                            + bounds.maximum() + "]");
        }
        if (!coherent(sourceFixed, fixedAmount, quote.statedCounter(), rp)) {
            return refused(Refusal.PROVIDER_QUOTE_INCOHERENT,
                    "the stated counter " + quote.statedCounter() + " is not within one minor unit"
                            + " of " + rp + " applied to " + fixedAmount);
        }

        BigDecimal rcValue =
                rp.value()
                        .multiply(BigDecimal.ONE.subtract(pair.totalMargin()))
                        .setScale(pair.rateScale(), pair.rateRounding().mode());
        if (rcValue.signum() <= 0) {
            return refused(Refusal.AMOUNT_OUT_OF_RANGE,
                    "the customer rate rounds to " + rcValue.toPlainString() + " at scale "
                            + pair.rateScale());
        }
        ExchangeRate rc = ExchangeRate.of(pair.source(), pair.destination(), rcValue);
        ExchangeRate internal = internalRate(rp, pair.spread(), pair.rateRounding());

        Money customerPays;
        Money customerReceives;
        Money positionSource;
        Money positionDestination;
        Money margin;
        if (sourceFixed) {
            customerPays = fixedAmount;
            positionSource = fixedAmount;
            positionDestination = quote.statedCounter();
            customerReceives = rc.convert(fixedAmount, pair.amountRounding());
            margin = Money.of(
                    fixedAmount.toBigDecimal().multiply(rp.marginAgainst(rc)),
                    computedCurrency,
                    pair.marginRounding());
        } else {
            customerReceives = fixedAmount;
            positionDestination = fixedAmount;
            positionSource = quote.statedCounter();
            customerPays = rc.sourceFor(fixedAmount, pair.amountRounding());
            // An exact numerator and an exact denominator, then ONE rounded division.
            BigDecimal numerator = fixedAmount.toBigDecimal().multiply(rp.marginAgainst(rc));
            BigDecimal denominator = rc.value().multiply(rp.value());
            margin = Money.of(
                    numerator.divide(
                            denominator,
                            computedCurrency.minorUnits(),
                            pair.marginRounding().mode()),
                    computedCurrency);
        }
        Money computedLeg = sourceFixed ? customerReceives : customerPays;
        if (!computedLeg.isPositive()) {
            return refused(Refusal.AMOUNT_OUT_OF_RANGE,
                    "the computed leg is " + computedLeg + ", which is not positive");
        }
        if (margin.isNegative()) {
            return refused(Refusal.MARGIN_NEGATIVE,
                    "the margin computed from the rounded customer rate is " + margin);
        }

        Money residual = sourceFixed
                ? positionDestination.minus(customerReceives).minus(margin)
                : customerPays.minus(positionSource).minus(margin);
        long bound = residualBound(pair.amountRounding(), pair.marginRounding());
        if (Math.abs(residual.minorUnits()) > bound) {
            return refused(Refusal.PLAN_INVARIANT_VIOLATED,
                    "the residual " + residual + " exceeds the policy's proven bound of " + bound
                            + " minor unit(s)");
        }

        List<Money> parts =
                margin.allocateByWeights(
                        pair.spread().attributionWeight(), pair.markup().attributionWeight());
        return new Priced(new Plan(
                fixedSide, rp, rc, internal, customerPays, customerReceives, positionSource,
                positionDestination, margin, parts.get(0), parts.get(1), residual));
    }

    /**
     * The disclosed margin over mid - a fraction at scale six, {@code HALF_EVEN}, the
     * {@code NUMERIC(7,6)} a quote stores (ADR-0074 §7). With the reference in the customer
     * rate's direction: {@code round((ref − rc) ÷ ref, 6)}, one exactly-rounded division. In the
     * inverse direction: {@code round(1 − rc × ref, 6)}, an exact product. No inversion is taken.
     *
     * @throws IllegalArgumentException if {@code reference} is neither direction of the pair, or
     *     the figure does not fit {@code NUMERIC(7,6)}
     */
    public static BigDecimal disclosedMarginOverMid(ExchangeRate customer, ExchangeRate reference) {
        Objects.requireNonNull(customer, "customer must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        BigDecimal figure;
        if (reference.source().equals(customer.source())
                && reference.destination().equals(customer.destination())) {
            figure = reference.marginAgainst(customer)
                    .divide(reference.value(), Margin.SCALE, RoundingMode.HALF_EVEN);
        } else if (reference.source().equals(customer.destination())
                && reference.destination().equals(customer.source())) {
            figure = BigDecimal.ONE
                    .subtract(customer.value().multiply(reference.value()))
                    .setScale(Margin.SCALE, RoundingMode.HALF_EVEN);
        } else {
            throw new IllegalArgumentException(
                    "The reference " + reference + " is neither direction of " + customer);
        }
        if (figure.precision() - figure.scale() > 1) {
            throw new IllegalArgumentException(
                    "A disclosed margin of " + figure.toPlainString() + " does not fit NUMERIC(7,6)");
        }
        return figure;
    }

    /** The internal (dealing) rate: {@code round(rp × (1 − spread), MAX_SCALE, rateRounding)}. */
    static ExchangeRate internalRate(ExchangeRate rp, Margin spread, RoundingPolicy rateRounding) {
        BigDecimal value =
                rp.value()
                        .multiply(BigDecimal.ONE.subtract(spread.value()))
                        .setScale(ExchangeRate.MAX_SCALE, rateRounding.mode());
        return ExchangeRate.of(rp.source(), rp.destination(), value);
    }

    /**
     * The proven residual bound: 1 minor unit when both the amount and the margin round to
     * nearest (each term at most ½), 2 under any other named policy (each term below 1).
     */
    static long residualBound(RoundingPolicy amountRounding, RoundingPolicy marginRounding) {
        return isHalf(amountRounding) && isHalf(marginRounding) ? 1L : 2L;
    }

    private static boolean isHalf(RoundingPolicy policy) {
        return policy == RoundingPolicy.HALF_EVEN || policy == RoundingPolicy.HALF_UP;
    }

    /**
     * Strictly within one minor unit of the computed leg - source-fixed
     * {@code |Dp − S·rp| < 10^−m(D)}; destination-fixed {@code |Sp·rp − D| < rp × 10^−m(S)}, by
     * cross-multiplication, so no division is taken. A non-positive stated counter is incoherent. Package-private:
     * a cover's executed rate is judged against its executed amounts by the same rule ({@link CoverLines#rateCoherent}).
     */
    static boolean coherent(
            boolean sourceFixed, Money fixedAmount, Money statedCounter, ExchangeRate rp) {
        if (!statedCounter.isPositive()) {
            return false;
        }
        if (sourceFixed) {
            BigDecimal gap = statedCounter.toBigDecimal().subtract(rp.exactProduct(fixedAmount)).abs();
            return gap.compareTo(BigDecimal.ONE.movePointLeft(statedCounter.scale())) < 0;
        }
        BigDecimal gap = rp.exactProduct(statedCounter).subtract(fixedAmount.toBigDecimal()).abs();
        return gap.compareTo(rp.value().movePointLeft(statedCounter.scale())) < 0;
    }

    private static void requireExact(Money amount, CurrencyCode currency, String what) {
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(
                    what + " must be in " + currency + ", but was " + amount);
        }
        if (amount.scale() != currency.minorUnits()) {
            throw new IllegalArgumentException(
                    what + " must be at " + currency + "'s " + currency.minorUnits()
                            + " minor units, but was at scale " + amount.scale());
        }
    }

    private static Refused refused(Refusal refusal, String detail) {
        return new Refused(refusal, detail);
    }
}
