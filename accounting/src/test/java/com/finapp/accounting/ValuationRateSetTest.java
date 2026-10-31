package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A valuation rate set's content and its vocabulary (`P14-TSK-014`; ADR-0116 decision 2; {@code INV-ACC-11},
 * {@code INV-MON-01}): the as-of instant, the staleness bound's edges, the lifecycle's edges, a rate that is the
 * reference's EUR pair exactly as {@code NUMERIC(20,10)} holds it, and the judgement's refusal of a port that breaks its
 * contract.
 */
@DisplayName("a valuation rate set's content: as of, staleness, lifecycle, the rate (P14-TSK-014)")
class ValuationRateSetTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final Instant AS_OF = Instant.parse("2026-10-01T00:00:00Z");
    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());

    @Test
    @DisplayName("a set is as of the first instant after its period, UTC - December's in the next year")
    void asOfIsTheFirstInstantAfterThePeriod() {
        assertThat(ValuationRateSet.asOf(YearMonth.of(2026, 9))).isEqualTo(AS_OF);
        assertThat(ValuationRateSet.asOf(YearMonth.of(2026, 12))).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(ValuationRateSet.asOf(YearMonth.of(2028, 2))).isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
    }

    @Test
    @DisplayName("the staleness bound: 96 hours by default, 1 to 336; an observation exactly the bound before the end is"
            + " admitted, one a nanosecond older is not; the end itself is not, a nanosecond before it is")
    void theStalenessBound() {
        ValuationStaleness standard = ValuationStaleness.standard();
        assertThat(standard.hours()).isEqualTo(96);
        assertThatThrownBy(() -> new ValuationStaleness(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValuationStaleness(337)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ValuationStaleness(336).hours()).isEqualTo(336);

        Instant oldest = AS_OF.minus(Duration.ofHours(96));
        assertThat(standard.oldestAdmitted(AS_OF)).isEqualTo(oldest);
        assertThat(standard.admits(oldest, AS_OF)).isTrue();
        assertThat(standard.admits(oldest.minusNanos(1), AS_OF)).isFalse();
        assertThat(standard.admits(AS_OF, AS_OF)).as("at the end is after the period's last instant").isFalse();
        assertThat(standard.admits(AS_OF.minusNanos(1), AS_OF)).isTrue();
    }

    @Test
    @DisplayName("PROPOSED moves once, to APPROVED (four eyes) or REJECTED; both terminal")
    void theLifecycle() {
        assertThat(ValuationRateSetStatus.PROPOSED.next())
                .containsExactlyInAnyOrder(ValuationRateSetStatus.APPROVED, ValuationRateSetStatus.REJECTED);
        assertThat(ValuationRateSetStatus.APPROVED.next()).isEmpty();
        assertThat(ValuationRateSetStatus.REJECTED.next()).isEmpty();
        assertThat(ValuationRateSetStatus.PROPOSED.fourEyesTo(ValuationRateSetStatus.APPROVED)).isTrue();
        assertThat(ValuationRateSetStatus.PROPOSED.fourEyesTo(ValuationRateSetStatus.REJECTED)).isFalse();
        assertThat(ValuationRateSetStatus.APPROVED.canMoveTo(ValuationRateSetStatus.REJECTED)).isFalse();
    }

    @Test
    @DisplayName("a rate is EUR-based, of another currency, positive and exactly NUMERIC(20,10), from a source's code")
    void aRateIsTheReferencesEurPair() {
        assertThat(rate(EUR, GBP, "9999999999.9999999999", "simulated-reference").pair()).isEqualTo("EUR/GBP");
        assertThat(rate(EUR, GBP, "0.0000000001", "simulated-reference").rate()).isEqualByComparingTo("1E-10");
        assertThatThrownBy(() -> rate(GBP, EUR, "1.16", "simulated-reference")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rate(EUR, EUR, "1", "simulated-reference")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rate(EUR, GBP, "0", "simulated-reference")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rate(EUR, GBP, "-0.86", "simulated-reference"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rate(EUR, GBP, "0.86120000001", "simulated-reference"))
                .as("eleven fractional digits").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rate(EUR, GBP, "10000000000", "simulated-reference"))
                .as("eleven integer digits").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rate(EUR, GBP, "0.86", "Simulated Reference"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the judgement refuses a port that breaks its contract - nothing valued, EUR valued, a currency twice -"
            + " before anything is judged")
    void theValuedCurrenciesAreThePortsContract() {
        AccountingPeriodId period = AccountingPeriodId.next(IDS);
        YearMonth month = YearMonth.of(2026, 9);
        ValuationStaleness standard = ValuationStaleness.standard();
        assertThatThrownBy(() -> ValuationRateSet.judge(period, month, standard, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ValuationRateSet.judge(period, month, standard, List.of(EUR, GBP), List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ValuationRateSet.judge(period, month, standard, List.of(GBP, GBP), List.of()))
                .isInstanceOf(IllegalStateException.class);
        ValuationRateSet judged = ValuationRateSet.judge(period, month, standard, List.of(GBP), List.of(
                new ValuationRates.Observed(GBP, Optional.of(new ValuationRates.Observation(UUID.randomUUID(),
                        "simulated-reference", EUR, GBP, new BigDecimal("0.8612000000"), AS_OF.minusSeconds(60),
                        AS_OF.minusSeconds(58))))));
        assertThat(judged.rates()).singleElement().extracting(ValuationRate::rate).isEqualTo(new BigDecimal("0.8612000000"));
    }

    @Test
    @DisplayName("a set holds itself to the same rules: as of its period's end, each currency once, every observation"
            + " admissible")
    void aSetHoldsItsOwnRanks() {
        AccountingPeriodId period = AccountingPeriodId.next(IDS);
        YearMonth month = YearMonth.of(2026, 9);
        ValuationRate fresh = new ValuationRate(EUR, GBP, new BigDecimal("0.8612"), "simulated-reference",
                UUID.randomUUID(), AS_OF.minusSeconds(60), AS_OF.minusSeconds(59));
        ValuationRate stale = new ValuationRate(EUR, GBP, new BigDecimal("0.8612"), "simulated-reference",
                UUID.randomUUID(), AS_OF.minus(Duration.ofDays(5)), AS_OF.minus(Duration.ofDays(5)));
        ValuationStaleness standard = ValuationStaleness.standard();
        assertThat(new ValuationRateSet(period, month, AS_OF, standard, List.of(fresh)).rates()).containsExactly(fresh);
        assertThatThrownBy(() -> new ValuationRateSet(period, month, AS_OF.minusSeconds(1), standard, List.of(fresh)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValuationRateSet(period, month, AS_OF, standard, List.of(fresh, fresh)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValuationRateSet(period, month, AS_OF, standard, List.of(stale)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ValuationRateSet(period, month, AS_OF, standard, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ValuationRate rate(CurrencyCode base, CurrencyCode quote, String rate, String source) {
        return new ValuationRate(base, quote, new BigDecimal(rate), source, UUID.randomUUID(), AS_OF.minusSeconds(60),
                AS_OF.minusSeconds(59));
    }
}
