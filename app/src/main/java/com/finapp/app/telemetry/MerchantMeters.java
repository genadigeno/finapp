package com.finapp.app.telemetry;

import com.finapp.merchant.MerchantPayoutStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The merchant surface's counters (`P6-TSK-013`, {@code PHASE_6_PLAN.md} §15):
 * {@code finapp.merchant.fee.assessed} and {@code finapp.merchant.payout} by outcome.
 *
 * <h2>Eager, and derived from the machine</h2>
 *
 * <p>Every series is registered at construction (`P1-TSK-029`): a freshly started instance
 * publishes healthy zeros, never absences an alert cannot evaluate. The payout outcomes are
 * the payout machine's own judgements — every {@link MerchantPayoutStatus} but the one it is
 * born in — so a judgement the machine gains registers itself.
 *
 * <h2>Only the ACTING judgement counts</h2>
 *
 * <p>The `P5-TSK-017` rule, at a new vocabulary. A payout's answer arrives through several
 * doors — the synchronous send, a takeover's re-send, the resolution sweep — and ten sweeps
 * racing one payout converge on a judgement only one of them made. The acting bit is the
 * conditional transition's own row count, carried out of {@code MerchantPayoutOutcomes.Applied}
 * on {@code MerchantPayouts.Initiated.acting} and {@code MerchantPayoutResolution.SweepResult
 * .actingJudgements}, and the doors count it <strong>after</strong> their transactions
 * committed. {@code DISPATCHED} is the platform mid-question and is never a judgement. A payout
 * that went {@code UNKNOWN} and later {@code COMPLETED} counts twice — once per judgement,
 * exactly as refunds do — because each is a decision somebody made.
 *
 * <h2>Fee assessments: counts, never amounts</h2>
 *
 * <p>{@code INV-AUD-02}: a fee is a merchant's commercial term, and no amount reaches a meter.
 * An assessment is counted where the capture composes its merchant-bound entry — inside the
 * capture's transaction, behind its conditional transition, so ten resolvers racing one
 * capture count one assessment. That is {@code CheckoutMeters}' compromise for completions,
 * for the same reason: the capture's transaction belongs to {@code payments}, which must not
 * learn what a fee is ({@code INV-PAY-03}). A transaction that rolls back after composing
 * overcounts by one, the cheaper error.
 *
 * <h2>Per instance</h2>
 *
 * <p>Counters live in this JVM's registry; {@code rate()} and {@code sum()} aggregate at the
 * scrape. Nothing reads them back ({@code CLAUDE.md} rule 12): the count of record is always the
 * table.
 */
public final class MerchantMeters {

    /** {@code finapp.merchant.fee.assessed} — merchant-bound captures that assessed a fee. */
    static final String FEE_ASSESSED = "finapp.merchant.fee.assessed";

    /** {@code finapp.merchant.payout} — acting payout judgements, by outcome. */
    static final String PAYOUT = "finapp.merchant.payout";

    private final Counter feeAssessments;
    private final Map<MerchantPayoutStatus, Counter> payouts =
            new EnumMap<>(MerchantPayoutStatus.class);

    /**
     * Public for the {@code PaymentMeters} reason: the merchant and checkout suites compose the
     * real doors over a {@code SimpleMeterRegistry} of their own, so they exercise the wired
     * counting path rather than a double.
     */
    public MerchantMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        feeAssessments =
                Counter.builder(FEE_ASSESSED)
                        .description(
                                "Merchant-bound captures that assessed the platform's fee,"
                                        + " counted once per capture on the transition that"
                                        + " committed it: racing resolvers are one assessment."
                                        + " A count, never an amount (INV-AUD-02). Per"
                                        + " instance; rate() and sum() aggregate")
                        .register(registry);
        for (MerchantPayoutStatus status : MerchantPayoutStatus.values()) {
            if (isJudgement(status)) {
                payouts.put(
                        status,
                        Counter.builder(PAYOUT)
                                .tag("outcome", status.name().toLowerCase(Locale.ROOT))
                                .description(
                                        "Acting payout judgements by outcome, counted"
                                                + " post-commit at the door that applied them:"
                                                + " completed released the hold and posted,"
                                                + " failed released it with nothing posted,"
                                                + " unknown left the merchant's money visibly"
                                                + " reserved (INV-LIFE-03). A replay, a"
                                                + " converged takeover and the losers of a"
                                                + " racing resolution are never throughput."
                                                + " Per instance; rate() and sum() aggregate")
                                .register(registry));
            }
        }
    }

    /** One merchant-bound capture's fee assessment, on the transition that committed it. */
    public void feeAssessed() {
        feeAssessments.increment();
    }

    /**
     * One acting payout judgement, post-commit. {@code DISPATCHED} counts nothing: the platform
     * is mid-question, and a meter counting it would report decisions nobody made.
     */
    public void payoutJudged(MerchantPayoutStatus judged) {
        Objects.requireNonNull(judged, "judged must not be null");
        if (isJudgement(judged)) {
            payouts.get(judged).increment();
        }
    }

    /** Every state but birth is a decision somebody made about the payout. */
    private static boolean isJudgement(MerchantPayoutStatus status) {
        return status != MerchantPayoutStatus.DISPATCHED;
    }
}
