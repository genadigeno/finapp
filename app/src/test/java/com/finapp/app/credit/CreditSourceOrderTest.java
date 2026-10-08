package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.credit.CreditDataCollection;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider order read from configuration (`P10-TSK-021`; ADR-0085 section 10): empty, the kind falls to its
 * fail-safe - production's order until unresolved questions #13 and #14 are answered; a provider named is refused at
 * startup, loudly, rather than ignored; a malformed, repeated or dangling code is refused.
 */
@DisplayName("the credit source order from configuration - fail-safe until party facts exist (P10-TSK-021)")
class CreditSourceOrderTest {

    private static final CreditDataCollection.Timing TIMING =
            new CreditDataCollection.Timing(Duration.ofMinutes(1), Duration.ofMinutes(30));
    private static final String BLOCKED = "a bureau pull needs facts the platform does not hold (#13)";

    @Test
    @DisplayName("no provider configured: the order is empty and every birth names the fail-safe")
    void anEmptyOrderSelectsTheFailSafe() {
        UnconfiguredBureau failSafe = new UnconfiguredBureau();
        CreditDataCollection.Configured configured =
                CreditSourceOrder.configured("finapp.credit.bureau", " ", "", failSafe, TIMING, BLOCKED);
        assertThat(configured.order()).isEmpty();
        assertThat(configured.select()).isSameAs(failSafe);
    }

    @Test
    @DisplayName("a provider named is refused at startup with the reason - never silently ignored")
    void aNamedProviderIsRefusedUntilItsFactsExist() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CreditSourceOrder.configured("finapp.credit.bureau", "bureau-sim-b, bureau-sim-a",
                        "bureau-sim-a", new UnconfiguredBureau(), TIMING, BLOCKED))
                .withMessageContaining("finapp.credit.bureau.providers names bureau-sim-b,bureau-sim-a")
                .withMessageContaining("#13");
    }

    @Test
    @DisplayName("a disabled code naming no provider, a malformed code and a repeated code are each refused")
    void aDanglingMalformedOrRepeatedCodeIsRefused() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CreditSourceOrder.configured("finapp.credit.findata", "", "findata-sim-a",
                        new UnconfiguredFinancialData(), TIMING, BLOCKED))
                .withMessageContaining("finapp.credit.findata.disabled");
        assertThatIllegalStateException()
                .isThrownBy(() -> CreditSourceOrder.codes("finapp.credit.bureau.providers", "Bureau_A"));
        assertThatIllegalStateException()
                .isThrownBy(() -> CreditSourceOrder.codes("finapp.credit.bureau.providers", "bureau-a,,bureau-b"));
        assertThatIllegalStateException()
                .isThrownBy(() -> CreditSourceOrder.codes("finapp.credit.bureau.providers", "bureau-a,bureau-a"))
                .withMessageContaining("twice");
        assertThat(CreditSourceOrder.codes("p", " bureau-b , bureau-a ")).containsExactly("bureau-b", "bureau-a");
    }
}
