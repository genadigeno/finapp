package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The combined bound's arithmetic (`P7-TSK-013`, ADR-0061 §3, {@code INV-DSP-01}): the
 * counterparty bears {@code min(amount, headroom)}, never more, the rest is excess; a parked share
 * is the counterparty's by attribution; only the excess ever moves back.
 */
@DisplayName("ChargebackSplit (P7-TSK-013)")
class ChargebackSplitTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    @Test
    @DisplayName("INV-DSP-01 swept: for every headroom around the amount, the counterparty bears"
            + " exactly min(amount, headroom) and the parts sum to what the network took")
    void theCounterpartyBearsAtMostTheHeadroom() {
        Money taken = eur(1000);
        for (long headroom = -300; headroom <= 1300; headroom += 50) {
            ChargebackSplit split = ChargebackSplit.of(taken, eur(headroom), true);
            long expected = Math.max(0, Math.min(1000, headroom));
            assertThat(split.counterpartyShare()).as("headroom %d", headroom)
                    .isEqualTo(eur(expected));
            assertThat(split.parkedShare()).isEqualTo(eur(0));
            assertThat(split.excess()).isEqualTo(eur(1000 - expected));
            assertThat(split.counterpartyShare().plus(split.parkedShare()).plus(split.excess()))
                    .as("value neither created nor destroyed (INV-BAL-03)")
                    .isEqualTo(taken);
            assertThat(split.recoverable()).isEqualTo(eur(1000 - expected));
        }
    }

    @Test
    @DisplayName("a counterparty that takes no postings has its share PARKED, never refused -"
            + " and a parked share counts against the bound like a posted one")
    void aNonPostableCounterpartyIsParked() {
        ChargebackSplit split = ChargebackSplit.of(eur(1000), eur(700), false);
        assertThat(split.counterpartyShare()).isEqualTo(eur(0));
        assertThat(split.parkedShare()).isEqualTo(eur(700));
        assertThat(split.excess()).isEqualTo(eur(300));
        assertThat(split.attributed()).as("the bound's term").isEqualTo(eur(700));
        assertThat(split.recoverable())
                .as("the parked share and the excess both rest in the recoverable")
                .isEqualTo(eur(1000));
    }

    @Test
    @DisplayName("a re-attribution moves only the excess, never more, to the counterparty or its"
            + " parking")
    void onlyTheExcessMovesBack() {
        ChargebackSplit split = ChargebackSplit.of(eur(1000), eur(400), true);
        ChargebackSplit posted = split.reattributed(eur(200), true);
        assertThat(posted.counterpartyShare()).isEqualTo(eur(600));
        assertThat(posted.excess()).isEqualTo(eur(400));
        ChargebackSplit parked = split.reattributed(eur(600), false);
        assertThat(parked.counterpartyShare()).isEqualTo(eur(400));
        assertThat(parked.parkedShare()).isEqualTo(eur(600));
        assertThat(parked.excess()).isEqualTo(eur(0));
        assertThatThrownBy(() -> split.reattributed(eur(601), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> split.reattributed(eur(0), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an incoherent split is refused: shares beyond the amount, negative, in another"
            + " currency, or a non-positive amount")
    void anIncoherentSplitIsRefused() {
        assertThatThrownBy(() -> new ChargebackSplit(eur(1000), eur(700), eur(301)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never exceed");
        assertThatThrownBy(() -> new ChargebackSplit(eur(1000), eur(-1), eur(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never negative");
        assertThatThrownBy(
                        () -> new ChargebackSplit(
                                eur(1000), Money.ofMinorUnits(10, CurrencyCode.of("GBP")),
                                eur(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency and scale");
        assertThatThrownBy(() -> new ChargebackSplit(eur(0), eur(0), eur(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("INV-AUD-02: a split prints its currency, never an amount")
    void itPrintsNoAmount() {
        assertThat(ChargebackSplit.of(eur(123456), eur(98765), true).toString())
                .doesNotContain("1234.56")
                .doesNotContain("987.65")
                .contains("EUR");
    }
}
