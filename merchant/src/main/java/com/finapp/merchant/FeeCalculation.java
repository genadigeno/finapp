package com.finapp.merchant;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The fee arithmetic (`P6-TSK-004`, ADR-0050 §4) — the whole of it, in one method.
 *
 * <h2>Pure, and that is a security property as much as a testing one</h2>
 *
 * <p>No clock, no store, no context, no state. Every capture on this platform will trust this
 * computation, so a defect here is systematic and silent — the kind found at reconciliation
 * months later, one minor unit at a time. Purity is what makes it provable by property test
 * rather than by the examples somebody happened to think of.
 *
 * <h2>One rounding, and the net is never one of them</h2>
 *
 * <pre>
 *   fee = round(gross × rate, version's policy) + fixed
 *   net = gross − fee
 * </pre>
 *
 * <p><strong>The variable part is rounded once; the fixed part is already exact money; the net
 * is subtraction.</strong> So {@code fee + net == gross} algebraically, for every amount,
 * every rate, every rounding policy and every currency — there is no residual to strand,
 * because there is no second rounding to produce one ({@code INV-MER-04}).
 *
 * <p>The alternative — computing the net as {@code round(gross × (1 − rate)) − fixed} — is the
 * defect this shape exists to prevent. Two independent roundings sum to a minor unit more or
 * less than the capture, invisibly, per transaction, at volume: {@code INV-BAL-03} violated by
 * arithmetic rather than by a bug.
 *
 * <h2>Why nothing is clamped</h2>
 *
 * <p>A large fixed part on a small capture produces a fee greater than the gross and a
 * negative net. {@link FeeAssessment#exceedsGross()} says so; nothing here caps it. Capping
 * would make the recorded fee disagree with what the pinned version produces on recomputation
 * — {@code INV-MER-03} broken for cosmetics — unless the cap were itself part of the versioned
 * definition, which ADR-0050 §4 does not make it.
 */
public final class FeeCalculation {

    private FeeCalculation() {
        // Pure arithmetic; not instantiable.
    }

    /**
     * Prices {@code gross} under {@code version}.
     *
     * <p>Deterministic: the same arguments produce the same answer forever, which is exactly
     * what {@code INV-MER-03}'s recomputation clause promises a merchant and an auditor.
     *
     * @throws FeeCurrencyMismatchException if {@code gross} is not in the version's currency
     * @throws com.finapp.sharedkernel.money.ScaleMismatchException if the gross was written
     *     under a different minor-unit count than the version's fixed part. Loud rather than
     *     coerced, deliberately: a currency redenomination changes what a fixed part quoted
     *     before it <em>means</em>, and silently pricing across that boundary would produce a
     *     plausible number nobody could defend ({@code INV-MON-05}).
     * @throws com.finapp.sharedkernel.money.MonetaryOverflowException if the fee or the net is
     *     not representable ({@code INV-MON-06} — never wrapped)
     */
    public static FeeAssessment assess(Money gross, FeeScheduleVersion version) {
        Objects.requireNonNull(gross, "gross must not be null");
        Objects.requireNonNull(version, "version must not be null");
        if (!gross.currency().equals(version.currency())) {
            throw new FeeCurrencyMismatchException(version.currency(), gross.currency());
        }

        // Exact: BigDecimal multiplication of an exact amount by an exact ratio loses nothing.
        // The product carries the sum of the two scales and is rounded exactly once, below.
        BigDecimal variableExact = gross.toBigDecimal().multiply(version.rate().toBigDecimal());

        // THE ONE ROUNDING IN THE WHOLE COMPUTATION, under the policy the version pinned
        // (INV-MON-03: named, required, never defaulted). Money.of is the sanctioned factory
        // for turning a computed decimal into money and its javadoc names a fee as the case.
        Money variable = Money.of(variableExact, gross.currency(), version.roundingPolicy());

        // Exact from here down. plus() and minus() refuse a currency or scale mismatch rather
        // than coercing, and both use Math.addExact / subtractExact.
        Money fee = variable.plus(version.fixed());
        Money net = gross.minus(fee);

        return new FeeAssessment(gross, fee, net, version.id());
    }

    /**
     * The fee returned by a refund of {@code refunded}, given that {@code refundedBefore} had
     * already been returned — the {@code RETURNED} policy's arithmetic (`P6-TSK-014`,
     * {@code INV-MER-04} across the refund boundary).
     *
     * <h2>Cumulative allocation, differenced — and the naive formula is the probe</h2>
     *
     * <pre>
     *   cum(x)  = round(assessedFee × x / gross, the version's policy)
     *   returned = cum(refundedBefore + refunded) − cum(refundedBefore)
     * </pre>
     *
     * <p>Rounding each refund's own share independently — {@code round(fee × r / gross)} per
     * refund — is the defect this shape exists to prevent, and it is not a rare one: an odd
     * fee refunded in two halves rounds up twice and returns a minor unit MORE THAN WAS EVER
     * ASSESSED. The platform would be paying a merchant fee it never charged, one cent at a
     * time, invisibly, on exactly the commonest partial-refund shape there is.
     *
     * <p>Differencing a cumulative allocation fixes it by construction rather than by a clamp:
     * <strong>the sum over any sequence of refunds telescopes</strong> to
     * {@code cum(total refunded)}, and a fully refunded capture gives {@code cum(gross)}, which
     * is {@code round(fee × 1)} — EXACTLY the assessed fee. "Two partial refunds summing to
     * the capture return exactly the assessed fee and no more" is therefore an identity here,
     * not a bound that has to be checked.
     *
     * <p>It also cannot over-return in any interleaving: {@code x ≤ gross} always, so the
     * cumulative figure is bounded by the assessed fee itself. Nothing is clamped, which is
     * what keeps {@code INV-MER-03}'s recomputation clause honest — a clamp would make the
     * returned figure disagree with what the pinned version produces.
     *
     * @param assessment what the ORIGINAL capture was priced at, recomputed under the pinned
     *     version — never today's schedule ({@code INV-MER-03})
     * @param version the pinned version itself, whose rounding policy decides the share. Passed
     *     beside the assessment rather than derived from it, and the two are CHECKED to agree:
     *     an assessment carries its version's identifier, so "this refund is priced by what the
     *     payment was priced by" becomes a thing the code verifies instead of a thing a comment
     *     claims
     * @param refundedBefore what had already been refunded and completed; zero for the first
     * @param refunded this refund's amount
     * @throws IllegalArgumentException if the refunds exceed the capture, which the payments
     *     module's own budget bound already refuses — checked again here because this is the
     *     arithmetic that would silently produce a wrong number if it ever stopped being true
     */
    public static Money returnedFee(
            FeeAssessment assessment,
            FeeScheduleVersion version,
            Money refundedBefore,
            Money refunded) {
        Money after = refundedAfter(assessment, version, refundedBefore, refunded);
        return cumulativeFee(assessment, version, after)
                .minus(cumulativeFee(assessment, version, refundedBefore));
    }

    /**
     * The LEAST fee a refund of {@code refunded} can return, whatever completes before it —
     * what a refund's dispatch may count on coming back (`P6-TSK-015`, ADR-0054).
     *
     * <h2>Why the share is not known when the refund is dispatched</h2>
     *
     * <p>{@link #returnedFee} is evaluated at COMPLETION, against what had completed before —
     * the only order that telescopes when a sibling refund fails. At dispatch, siblings may
     * still be in flight and refunds not yet dispatched may complete first, so the
     * {@code before} the completion will see can be anything from {@code refundedBefore} up to
     * {@code gross − refunded}, and across that range the share moves by a minor unit: a
     * one-cent fee refunded in halves returns the cent to whichever half completes FIRST. A
     * reservation that assumed the larger share would take a cent more than it held the
     * moment the smaller one happened — and if something else had drained the account in
     * between, the account would end a cent below anything anybody decided.
     *
     * <h2>Exact where it can be, one minor unit conservative where it cannot</h2>
     *
     * <ul>
     *   <li><strong>The refund completes the capture's remainder</strong>
     *       ({@code refundedBefore + refunded == gross}, every full refund among them): the
     *       range is a point, and the answer is {@link #returnedFee} itself — nothing can
     *       complete before this refund that has not already.
     *   <li><strong>Otherwise</strong>: {@code k = floor(fee × refunded / gross)}, less one
     *       under {@code HALF_EVEN} when {@code k} is odd.
     * </ul>
     *
     * <p>Why that is a floor for every completion order, which is the whole claim: the
     * cumulative figure rounds a value held on a fixed grid of {@link #SHARE_SCALE} decimal
     * places, so the difference of two cumulative values before the policy rounds them is a
     * whole number of grid steps no smaller than the exact share less one step — and so no
     * smaller than {@code k}, because a whole minor unit is itself on the grid (the working
     * scale is finer than any currency's). Every policy but one then commutes with a shift by
     * whole minor units, so the rounded difference is at least {@code k}; {@code HALF_EVEN}'s
     * tie-to-even can lose one on an odd shift, which is the one exception. When the exact
     * share is a whole number of minor units the answer is exact (under {@code HALF_EVEN}, when
     * that number is even); otherwise it is at most one minor unit below what any particular
     * order returns — two under {@code HALF_EVEN}, which gives one up in advance for a tie that
     * may never be reached — and the completion releases the difference.
     *
     * <p>Never negative: a fee is never negative ({@link FeeScheduleVersion} refuses a
     * negative rate or fixed part), so {@code k ≥ 0}, and the {@code HALF_EVEN} step only
     * applies to an odd {@code k}, which is at least one.
     *
     * @param refundedBefore what had already been refunded and COMPLETED when this refund was
     *     dispatched — never the non-failed sum, because a sibling in flight can still fail and
     *     leave the completion's {@code before} lower than that
     * @throws IllegalArgumentException for a foreign assessment or refunds exceeding the
     *     capture, exactly as {@link #returnedFee} refuses them
     */
    public static Money leastReturnedFee(
            FeeAssessment assessment,
            FeeScheduleVersion version,
            Money refundedBefore,
            Money refunded) {
        Money after = refundedAfter(assessment, version, refundedBefore, refunded);
        if (after.toBigDecimal().compareTo(assessment.gross().toBigDecimal()) == 0) {
            // A POINT: this refund completes the capture's remainder, so the completion will
            // see exactly refundedBefore. Exact, and by the very function it will evaluate.
            return returnedFee(assessment, version, refundedBefore, refunded);
        }
        CurrencyCode currency = assessment.fee().currency();
        // Exact: the floor of a rational at the currency's own scale - BigDecimal rounds the
        // true quotient once, in the direction named, and nothing else is rounded here.
        Money floor =
                Money.of(
                        assessment
                                .fee()
                                .toBigDecimal()
                                .multiply(refunded.toBigDecimal())
                                .divide(
                                        assessment.gross().toBigDecimal(),
                                        currency.minorUnits(),
                                        java.math.RoundingMode.FLOOR),
                        currency);
        if (version.roundingPolicy() == RoundingPolicy.HALF_EVEN
                && (floor.minorUnits() & 1L) == 1L) {
            // Tie-to-even does not commute with an ODD shift: x.5 and (x + k).5 round in
            // opposite directions when k is odd, so the difference can be k − 1.
            return floor.minus(Money.ofMinorUnits(1L, currency));
        }
        return floor;
    }

    /**
     * The total refunded once {@code refunded} completes after {@code refundedBefore} —
     * checked, because both the completion's arithmetic and the dispatch's reservation would
     * silently produce a wrong number if either assumption below stopped being true.
     */
    private static Money refundedAfter(
            FeeAssessment assessment,
            FeeScheduleVersion version,
            Money refundedBefore,
            Money refunded) {
        Objects.requireNonNull(assessment, "assessment must not be null");
        Objects.requireNonNull(version, "version must not be null");
        if (!assessment.version().equals(version.id())) {
            // The assumption INV-MER-03 rests on, CHECKED rather than trusted: the assessment
            // must have been produced BY this version. A refund priced under any other one is
            // a repricing, and the only thing worse than refusing it is doing it quietly.
            throw new IllegalArgumentException(
                    "This assessment was priced under version " + assessment.version()
                            + " and the refund would be priced under " + version.id()
                            + "; a refund is priced by what the payment was priced by"
                            + " (INV-MER-03)");
        }
        Objects.requireNonNull(refundedBefore, "refundedBefore must not be null");
        Objects.requireNonNull(refunded, "refunded must not be null");
        Money after = refundedBefore.plus(refunded);
        if (after.toBigDecimal().compareTo(assessment.gross().toBigDecimal()) > 0) {
            throw new IllegalArgumentException(
                    "Refunds of " + after + " exceed the captured " + assessment.gross()
                            + "; a refund cannot return more than was taken");
        }
        return after;
    }

    /** The fee owed back once {@code refunded} of the capture has been returned, in total. */
    private static Money cumulativeFee(
            FeeAssessment assessment, FeeScheduleVersion version, Money refunded) {
        if (refunded.isZero()) {
            // Not merely an optimisation: gross is never zero here (a pinned gross is
            // positive), but saying so costs nothing and makes the first refund's arithmetic
            // obviously exact rather than obviously rounded.
            return Money.ofMinorUnits(0L, assessment.fee().currency());
        }
        BigDecimal share =
                assessment
                        .fee()
                        .toBigDecimal()
                        .multiply(refunded.toBigDecimal())
                        .divide(
                                assessment.gross().toBigDecimal(),
                                SHARE_SCALE,
                                java.math.RoundingMode.HALF_UP);
        // THE ONE ROUNDING, under the policy the version pinned - the same discipline assess()
        // holds, at the reversal.
        return Money.of(share, assessment.fee().currency(), version.roundingPolicy());
    }

    /**
     * Working scale for the proportional share before it is rounded to money.
     *
     * <p>Wide enough that the intermediate division is not itself a rounding anybody can see:
     * the ratio is bounded by one and the fee by the capture, so twelve digits leaves the
     * money-scale rounding as the only one that decides a minor unit. HALF_UP here is a
     * TRUNCATION GUARD rather than a policy choice - the policy choice is the version's, and
     * it is applied by Money.of below.
     *
     * <p>{@link #leastReturnedFee}'s floor rests on this grid being FINER than any currency's
     * minor unit, so that a whole minor unit is a whole number of grid steps. Twelve is well
     * above the three-decimal currencies this platform prices; a coarser working scale would
     * need that proof redone before it could ship.
     */
    private static final int SHARE_SCALE = 12;
}
