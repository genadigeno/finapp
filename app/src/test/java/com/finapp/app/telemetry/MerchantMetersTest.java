package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.MerchantPayoutStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The merchant surface's counters (`P6-TSK-013`), hermetically: every series eager at zero,
 * the payout outcomes the machine's own judgements with its birth state counting nothing, and
 * the fee assessment a count with nothing on it. That only ACTING judgements reach these methods
 * is the doors' discipline, proven where the doors run — the payout and checkout suites.
 */
@DisplayName("the merchant surface's counters (P6-TSK-013)")
class MerchantMetersTest {

    @Test
    @DisplayName("every series exists at zero from construction: one fee counter, one payout"
            + " counter per judgement")
    void everySeriesIsEager() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MerchantMeters(registry);

        assertThat(registry.get(MerchantMeters.FEE_ASSESSED).counter().count()).isZero();
        assertThat(registry.find(MerchantMeters.PAYOUT).counters())
                .as("an alert on failed payouts must be writable before one has ever failed")
                .extracting(counter -> counter.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("completed", "failed", "unknown");
    }

    @Test
    @DisplayName("each judgement counts its own outcome, and DISPATCHED - mid-question - counts"
            + " nothing and has no series")
    void judgementsCountAndDispatchedDoesNot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantMeters meters = new MerchantMeters(registry);

        meters.payoutJudged(MerchantPayoutStatus.UNKNOWN);
        meters.payoutJudged(MerchantPayoutStatus.COMPLETED);
        meters.payoutJudged(MerchantPayoutStatus.DISPATCHED);

        assertThat(payouts(registry, "unknown")).isEqualTo(1.0d);
        assertThat(payouts(registry, "completed"))
                .as("a payout that went unknown and then completed is two judgements")
                .isEqualTo(1.0d);
        assertThat(payouts(registry, "failed")).isZero();
        assertThat(registry.find(MerchantMeters.PAYOUT).tag("outcome", "dispatched").counter())
                .as("a dispatch is no decision, so it has no series to be counted in")
                .isNull();
    }

    @Test
    @DisplayName("a fee assessment is a count: one tagless series, never an amount (INV-AUD-02)")
    void aFeeAssessmentIsACount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MerchantMeters meters = new MerchantMeters(registry);

        meters.feeAssessed();
        meters.feeAssessed();

        Counter assessed = registry.get(MerchantMeters.FEE_ASSESSED).counter();
        assertThat(assessed.count()).isEqualTo(2.0d);
        assertThat(assessed.getId().getTags())
                .as("no merchant, no currency, no amount: a tag is where a figure would leak")
                .isEmpty();
    }

    private static double payouts(SimpleMeterRegistry registry, String outcome) {
        return registry.get(MerchantMeters.PAYOUT).tag("outcome", outcome).counter().count();
    }
}
