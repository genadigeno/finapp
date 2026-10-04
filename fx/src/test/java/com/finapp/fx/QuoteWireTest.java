package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire and the judgements, without a database (`P9-TSK-008`; ADR-0075 sections 1, 4): the
 * plausibility band in both directions, exactly at its edge; the canonical reference pair; failover
 * in the pinned order with each answer recorded; and the total budget measured on the instance's
 * clock, past which no candidate is asked.
 */
@DisplayName("the quote's wire and judgements (P9-TSK-008)")
class QuoteWireTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    @DisplayName("the band is exact and division-free in both directions, inclusive at its edge")
    void theBand() {
        ExchangeRate reference = ExchangeRate.of(EUR, USD, new BigDecimal("1.0000"));
        BigDecimal band = new BigDecimal("0.015");
        assertThat(QuoteIssuance.plausible(ExchangeRate.of(EUR, USD, new BigDecimal("1.015")), reference, band)).isTrue();
        assertThat(QuoteIssuance.plausible(ExchangeRate.of(EUR, USD, new BigDecimal("1.0150000001")), reference, band)).isFalse();
        assertThat(QuoteIssuance.plausible(ExchangeRate.of(EUR, USD, new BigDecimal("0.985")), reference, band)).isTrue();
        assertThat(QuoteIssuance.plausible(ExchangeRate.of(USD, EUR, new BigDecimal("1.015")), reference, band))
                .as("the inverse direction: |rp x ref - 1| <= band")
                .isTrue();
        assertThat(QuoteIssuance.plausible(ExchangeRate.of(USD, EUR, new BigDecimal("1.016")), reference, band)).isFalse();
        assertThat(QuoteIssuance.plausible(ExchangeRate.of(EUR, CurrencyCode.of("GBP"), new BigDecimal("1")), reference, band))
                .as("another pair is never plausible")
                .isFalse();
    }

    @Test
    @DisplayName("either direction of a conversion finds the reference's one canonical pair")
    void theCanonicalPair() {
        assertThat(QuoteIssuance.referencePair(EUR, USD)).isEqualTo(ReferencePair.of("EUR", "USD"));
        assertThat(QuoteIssuance.referencePair(USD, EUR)).isEqualTo(ReferencePair.of("EUR", "USD"));
        assertThat(QuoteIssuance.referencePair(CurrencyCode.of("JPY"), CurrencyCode.of("BHD")))
                .isEqualTo(ReferencePair.of("BHD", "JPY"));
    }

    @Test
    @DisplayName("failover in the pinned order: declined, nothing sent, incoherent, then the chosen quote -"
            + " every answer a step, the later candidate never asked")
    void failoverInOrder() {
        FakeFxProvider declining = new FakeFxProvider("p1").mode(FakeFxProvider.Mode.DECLINE);
        FakeFxProvider silent = new FakeFxProvider("p2").mode(FakeFxProvider.Mode.NOTHING_SENT);
        FakeFxProvider incoherent = new FakeFxProvider("p3").rate("EUR", "USD", "1.085024").mode(FakeFxProvider.Mode.INCOHERENT);
        FakeFxProvider good = new FakeFxProvider("p4").rate("EUR", "USD", "1.085024");
        FakeFxProvider unasked = new FakeFxProvider("p5").rate("EUR", "USD", "1.085024");
        QuoteIssuance.Sourced sourced = issuance(Clock.systemUTC()).source(claimed(declining, silent, incoherent, good, unasked));
        assertThat(sourced.steps()).extracting(QuoteStore.Step::outcome).containsExactly(
                QuoteStore.StepOutcome.DECLINED, QuoteStore.StepOutcome.NOTHING_SENT, QuoteStore.StepOutcome.INCOHERENT,
                QuoteStore.StepOutcome.QUOTED, QuoteStore.StepOutcome.CHOSEN);
        assertThat(sourced.steps().get(0).detail()).contains("MARKET_CLOSED");
        assertThat(sourced.chosen()).hasValueSatisfying(chosen -> assertThat(chosen.providerCode()).isEqualTo("p4"));
        assertThat(unasked.requests()).isZero();
        assertThat(sourced.retained()).hasSize(3);
    }

    @Test
    @DisplayName("past the five-second total budget no further candidate is asked - each recorded"
            + " UNAVAILABLE - and with none usable the failure is named")
    void theTotalBudget() {
        FakeFxProvider slow = new FakeFxProvider("p1").mode(FakeFxProvider.Mode.INDETERMINATE);
        FakeFxProvider late = new FakeFxProvider("p2").rate("EUR", "USD", "1.085024");
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));
        Clock stepping = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.getAndUpdate(t -> t.plus(Duration.ofSeconds(3)));
            }
        };
        QuoteIssuance.Sourced sourced = issuance(stepping).source(claimed(slow, late));
        assertThat(sourced.steps()).extracting(QuoteStore.Step::outcome)
                .containsExactly(QuoteStore.StepOutcome.INDETERMINATE, QuoteStore.StepOutcome.UNAVAILABLE);
        assertThat(sourced.steps().get(1).detail()).contains("BUDGET_EXHAUSTED");
        assertThat(late.requests()).isZero();
        assertThat(sourced.chosen()).isEmpty();
        assertThat(sourced.failure()).isEqualTo(QuoteRefusal.RATE_UNAVAILABLE);
    }

    @Test
    @DisplayName("an incoherent-only outcome is named INCOHERENT; an implausible one IMPLAUSIBLE")
    void theFailureIsNamed() {
        FakeFxProvider incoherent = new FakeFxProvider("p1").rate("EUR", "USD", "1.085024").mode(FakeFxProvider.Mode.INCOHERENT);
        assertThat(issuance(Clock.systemUTC()).source(claimed(incoherent)).failure()).isEqualTo(QuoteRefusal.INCOHERENT);
        FakeFxProvider implausible = new FakeFxProvider("p1").rate("EUR", "USD", "1.300000");
        assertThat(issuance(Clock.systemUTC()).source(claimed(implausible)).failure()).isEqualTo(QuoteRefusal.IMPLAUSIBLE);
    }

    // -----------------------------------------------------------------

    private static QuoteIssuance.Claimed claimed(FakeFxProvider... candidates) {
        String[] codes = java.util.Arrays.stream(candidates).map(FakeFxProvider::code).toArray(String[]::new);
        PolicyPair terms = FxQuoteFixtures.pair("EUR", "USD", Duration.ofSeconds(30), Duration.ofSeconds(10), codes);
        QuoteStore.RequestRow request = new QuoteStore.RequestRow(UUID.randomUUID(), "QR-" + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID(), PricingPurpose.CONVERSION, EUR, USD, FixedSide.FIXED_SOURCE,
                FxQuoteFixtures.money("1000.00", "EUR"), PricingPolicyId.next(IDS), Instant.now());
        List<QuoteIssuance.Candidate> list = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.length; i++) {
            list.add(new QuoteIssuance.Candidate(i + 1,
                    new FxProviders.Composed(FakeFxProvider.declaration(candidates[i].code()), candidates[i])));
        }
        RateSnapshot reference = new RateSnapshot(UUID.randomUUID(), ReferenceSourceDeclaration.SOURCE,
                ExchangeRate.of(EUR, USD, new BigDecimal("1.0850000000")), Instant.now(), Instant.now());
        return new QuoteIssuance.Claimed(request, terms, list, List.of(), reference);
    }

    @SuppressWarnings("unchecked")
    private static QuoteIssuance issuance(Clock clock) {
        return new QuoteIssuance(
                stub(PricingPolicyStore.class),
                new FxAvailability(stub(AvailabilityStore.class), stub(com.finapp.platform.audit.AuditWriter.class),
                        stub(OutboxWriter.class), IDS),
                stub(QuoteStore.class),
                stub(RateSnapshotStore.class), new FxProviders(List.of()), stub(FxProviderEvidenceStore.class),
                (unitOfWork, party) -> java.util.Optional.of(party), (OutboxWriter<Connection>) stub(OutboxWriter.class),
                IDS, clock);
    }

    /** The wire touches no store: every method of these stubs fails the test. */
    private static <T> T stub(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new AssertionError("the wire reached " + type.getSimpleName() + "." + method.getName());
        }));
    }
}
