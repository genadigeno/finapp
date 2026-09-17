package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hold aggregate: its machine, its bounds, and what its renderings never say
 * (`P3-TSK-015`, {@code INV-LIFE-02}, {@code INV-LIFE-04}, {@code INV-AUD-02}).
 */
@DisplayName("a hold is a guarded reservation (P3-TSK-015)")
class HoldTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LedgerAccountId ACCOUNT = LedgerAccountId.of(IDS.next());

    @Test
    @DisplayName("a placed hold is ACTIVE from birth, and release is its one edge")
    void thePlacedHoldReleasesOnce() {
        Hold placed = Hold.place(HoldId.next(IDS), ACCOUNT, Money.ofMinorUnits(500, USD), CLOCK);
        assertThat(placed.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(placed.releasedAt()).isEmpty();

        Hold released = placed.release(CLOCK);
        assertThat(released.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(released.releasedAt()).isPresent();
        assertThat(released.amount()).isEqualTo(placed.amount());
    }

    @Test
    @DisplayName("a released hold refuses a second release - the aggregate, not only the store")
    void aReleasedHoldIsTerminal() {
        // INV-LIFE-04 at the domain: the store's conditional UPDATE converges a retried
        // release, but a caller holding the aggregate and asking it to move must be refused
        // by the aggregate itself (INV-LIFE-02) - every aggregate meets a second caller.
        Hold released =
                Hold.place(HoldId.next(IDS), ACCOUNT, Money.ofMinorUnits(500, USD), CLOCK)
                        .release(CLOCK);
        assertThat(HoldStatus.RELEASED.isTerminal()).isTrue();
        assertThatThrownBy(() -> released.release(CLOCK))
                .isInstanceOf(IllegalHoldTransitionException.class)
                .hasMessageNotContaining("500");
    }

    @Test
    @DisplayName("the amount is strictly positive: zero and negative holds are unconstructible")
    void theAmountIsStrictlyPositive() {
        assertThatThrownBy(
                        () ->
                                Hold.place(
                                        HoldId.next(IDS),
                                        ACCOUNT,
                                        Money.zero(USD),
                                        CLOCK))
                .as("a zero hold reserves nothing")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                Hold.place(
                                        HoldId.next(IDS),
                                        ACCOUNT,
                                        Money.ofMinorUnits(-100, USD),
                                        CLOCK))
                .as("a negative hold is a release wearing a placement's clothes")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("status and release instant are one fact - the mismatched pairs cannot exist")
    void theReleaseInstantMatchesTheStatus() {
        Instant now = Instant.now(CLOCK);
        assertThatThrownBy(
                        () ->
                                new Hold(
                                        HoldId.next(IDS),
                                        ACCOUNT,
                                        Money.ofMinorUnits(100, USD),
                                        HoldStatus.RELEASED,
                                        now,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Hold(
                                        HoldId.next(IDS),
                                        ACCOUNT,
                                        Money.ofMinorUnits(100, USD),
                                        HoldStatus.ACTIVE,
                                        now,
                                        Optional.of(now)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no rendering carries the amount (INV-AUD-02)")
    void theRenderingsAreAmountFree() {
        // A record's generated toString prints every component; Hold overrides it, and the
        // needle - a distinctive minor-unit value - must appear nowhere.
        Hold hold =
                Hold.place(HoldId.next(IDS), ACCOUNT, Money.ofMinorUnits(987654, USD), CLOCK);
        assertThat(hold.toString()).doesNotContain("987654").doesNotContain("9876.54");
    }
}
