package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The counterparty chart's completeness rule and the declaration's shape (`P9-TSK-010`, ADR-0078
 * sections 2-4): every declared counterparty registered, of its declared kind, with one seeded
 * account per settled currency - each gap planted alone and named, all of them listed at once.
 */
@DisplayName("the counterparty chart refuses every gap between the declarations and the seed (ADR-0078)")
class CounterpartyChartTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static final CounterpartyClearing FX_SIM_A =
            new CounterpartyClearing(
                    "fx-sim-a", CounterpartyKind.FX_PROVIDER, AccountPurpose.FX_PROVIDER_CLEARING, Set.of(EUR, USD));

    /** Planted readings: a registry and the seeded (code, currency) pairs. */
    private static CounterpartyChart.Readings readings(
            Map<String, CounterpartyKind> registry, Set<String> seeded) {
        return new CounterpartyChart.Readings() {
            @Override
            public Optional<Counterparty> counterparty(String code) {
                return Optional.ofNullable(registry.get(code))
                        .map(kind -> new Counterparty(UUID.randomUUID(), code, kind));
            }

            @Override
            public boolean hasAccount(AccountPurpose purpose, String code, CurrencyCode currency) {
                return seeded.contains(purpose + "|" + code + "|" + currency.code());
            }
        };
    }

    private static final Set<String> BOTH = Set.of("FX_PROVIDER_CLEARING|fx-sim-a|EUR", "FX_PROVIDER_CLEARING|fx-sim-a|USD");

    @Test
    @DisplayName("a complete chart passes, and no declaration needs nothing")
    void aCompleteChartPasses() {
        CounterpartyChart.verify(List.of(FX_SIM_A), readings(Map.of("fx-sim-a", CounterpartyKind.FX_PROVIDER), BOTH));
        CounterpartyChart.verify(List.of(), readings(Map.of(), Set.of()));
    }

    @Test
    @DisplayName("each planted gap is named: no registry row, the wrong kind, a currency unseeded")
    void eachGapIsNamed() {
        assertThat(CounterpartyChart.gaps(List.of(FX_SIM_A), readings(Map.of(), BOTH)))
                .singleElement().asString().contains("'fx-sim-a' is declared but has no registry row");
        assertThat(CounterpartyChart.gaps(
                        List.of(FX_SIM_A), readings(Map.of("fx-sim-a", CounterpartyKind.CORRIDOR_PROVIDER), BOTH)))
                .singleElement().asString().contains("registered as CORRIDOR_PROVIDER but declared as FX_PROVIDER");
        assertThat(CounterpartyChart.gaps(
                        List.of(FX_SIM_A),
                        readings(Map.of("fx-sim-a", CounterpartyKind.FX_PROVIDER), Set.of("FX_PROVIDER_CLEARING|fx-sim-a|EUR"))))
                .singleElement().asString().contains("settles USD on FX_PROVIDER_CLEARING but has no seeded account");
    }

    @Test
    @DisplayName("verify refuses startup listing every gap at once")
    void verifyListsEveryGap() {
        CounterpartyClearing second =
                new CounterpartyClearing("fx-sim-b", CounterpartyKind.FX_PROVIDER, AccountPurpose.FX_PROVIDER_CLEARING, Set.of(EUR));
        assertThatThrownBy(() -> CounterpartyChart.verify(
                        List.of(FX_SIM_A, second),
                        readings(Map.of("fx-sim-a", CounterpartyKind.FX_PROVIDER), Set.of("FX_PROVIDER_CLEARING|fx-sim-a|EUR"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("settles USD")
                .hasMessageContaining("'fx-sim-b' is declared but has no registry row");
    }

    @Test
    @DisplayName("a declaration settles only on a counterparty-owned purpose, in a shaped code, in some currency")
    void theDeclarationIsCoherent() {
        assertThatThrownBy(() -> new CounterpartyClearing(
                        "fx-sim-a", CounterpartyKind.FX_PROVIDER, AccountPurpose.SETTLEMENT_CLEARING, Set.of(EUR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-RAIL-04");
        assertThatThrownBy(() -> new CounterpartyClearing(
                        "FX_SIM", CounterpartyKind.FX_PROVIDER, AccountPurpose.FX_PROVIDER_CLEARING, Set.of(EUR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'FX_SIM'");
        assertThatThrownBy(() -> new CounterpartyClearing(
                        "fx-sim-a", CounterpartyKind.FX_PROVIDER, AccountPurpose.FX_PROVIDER_CLEARING, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no settled currency");
        assertThatThrownBy(() -> new Counterparty(UUID.randomUUID(), "a".repeat(33), CounterpartyKind.FX_PROVIDER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
