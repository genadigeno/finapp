package com.finapp.merchant;

import com.finapp.sharedkernel.money.Money;
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
}
