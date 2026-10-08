package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source selection as a pure function of configuration (`P10-TSK-021`; ADR-0085 section 10): the first provider in
 * order not disabled, else the kind's fail-safe; any configured provider found by its code, disabled or not, for the
 * request born naming it; and a configuration that could select nothing, or would silently disable nothing, refused.
 */
@DisplayName("source selection - first enabled in order, else the fail-safe (P10-TSK-021)")
class SourceSelectionTest {

    private static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofMinutes(1), Duration.ofMinutes(30));
    private static final CreditDataSource A = bureau("bureau-a");
    private static final CreditDataSource B = bureau("bureau-b");
    private static final CreditDataSource NONE = bureau("bureau-none");

    @Test
    @DisplayName("the first provider in order is selected; a disabled one is skipped; all disabled falls to the fail-safe")
    void theFirstEnabledInOrderElseTheFailSafe() {
        assertThat(configured(Set.of(), A, B).select()).isSameAs(A);
        assertThat(configured(Set.of(), B, A).select()).as("the order is the configuration's").isSameAs(B);
        assertThat(configured(Set.of("bureau-a"), A, B).select()).isSameAs(B);
        assertThat(configured(Set.of("bureau-a", "bureau-b"), A, B).select()).isSameAs(NONE);
        assertThat(new CreditDataCollection.Configured(List.of(), Set.of(), Optional.of(NONE), TIMING).select())
                .as("no provider at all: the fail-safe - production's order until #13").isSameAs(NONE);
        assertThat(new CreditDataCollection.Configured(A, TIMING).select()).isSameAs(A);
    }

    @Test
    @DisplayName("the same configuration selects the same provider every time - no state, no rotation")
    void selectionIsAPureFunctionOfConfiguration() {
        CreditDataCollection.Configured configured = configured(Set.of("bureau-a"), A, B);
        for (int i = 0; i < 100; i++) {
            assertThat(configured.select()).isSameAs(B);
        }
    }

    @Test
    @DisplayName("a born request's provider is found by its code, disabled or not; a code configured out is not found")
    void aProviderIsFoundByItsCodeDisabledOrNot() {
        CreditDataCollection.Configured configured = configured(Set.of("bureau-a"), A, B);
        assertThat(configured.provider("bureau-a")).containsSame(A);
        assertThat(configured.provider("bureau-b")).containsSame(B);
        assertThat(configured.provider("bureau-none")).containsSame(NONE);
        assertThat(configured.provider("bureau-gone")).isEmpty();
    }

    @Test
    @DisplayName("a configuration that selects nothing, repeats a code or disables an unknown one is refused")
    void anUnusableConfigurationIsRefused() {
        assertThatIllegalArgumentException().as("every provider disabled and no fail-safe")
                .isThrownBy(() -> new CreditDataCollection.Configured(List.of(A), Set.of("bureau-a"), Optional.empty(),
                        TIMING));
        assertThatIllegalArgumentException().as("nothing at all")
                .isThrownBy(() -> new CreditDataCollection.Configured(List.of(), Set.of(), Optional.empty(), TIMING));
        assertThatIllegalArgumentException().as("a code twice")
                .isThrownBy(() -> configured(Set.of(), A, A));
        assertThatIllegalArgumentException().as("the fail-safe is also a provider")
                .isThrownBy(() -> new CreditDataCollection.Configured(List.of(NONE), Set.of(), Optional.of(NONE), TIMING));
        assertThatIllegalArgumentException().as("a disabled code naming no provider is a typo, not a disable")
                .isThrownBy(() -> configured(Set.of("bureau-c"), A, B));
        assertThatIllegalArgumentException().as("a financial-data provider in the bureau's order")
                .isThrownBy(() -> new CreditDataCollection.Sources(Map.of(CreditSourceKind.BUREAU,
                        new CreditDataCollection.Configured(List.of(A, financialData("findata-a")), Set.of(),
                                Optional.of(NONE), TIMING))));
    }

    // -----------------------------------------------------------------

    private static CreditDataCollection.Configured configured(Set<String> disabled, CreditDataSource... order) {
        return new CreditDataCollection.Configured(List.of(order), disabled, Optional.of(NONE), TIMING);
    }

    private static CreditBureau bureau(String code) {
        return new CreditBureau() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public CreditDataAnswer pull(CreditDataPull request) {
                throw new UnsupportedOperationException("selection never pulls");
            }
        };
    }

    private static FinancialDataProvider financialData(String code) {
        return new FinancialDataProvider() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public CreditDataAnswer pull(CreditDataPull request) {
                throw new UnsupportedOperationException("selection never pulls");
            }
        };
    }
}
