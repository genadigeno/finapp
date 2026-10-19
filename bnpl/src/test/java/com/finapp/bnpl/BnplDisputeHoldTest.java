package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The servicing hold's two derivations, pure (`P12-TSK-023`; ADR-0106 decision 3) - {@code bnpl V015}'s
 * {@code bnpl.dispute_holds} and {@code bnpl.dispute_clock_start} in Java - and the delinquency derivation read through
 * them: held while opened and not resolved by the date, the clock from the latest lift, and no hold exactly
 * {@code P12-TSK-020}'s rule.
 */
@DisplayName("the dispute hold's derivations (P12-TSK-023)")
class BnplDisputeHoldTest {

    private static final com.finapp.sharedkernel.id.IdGenerator IDS = new com.finapp.sharedkernel.id.IdGenerator(
            java.time.Clock.systemUTC(), new java.security.SecureRandom());
    private static final LocalDate DUE = LocalDate.of(2026, 10, 31);
    private static final LocalDate OPENED = LocalDate.of(2026, 10, 20);
    private static final LocalDate LIFTED = LocalDate.of(2026, 11, 20);

    @Test
    @DisplayName("held from the opening's business date up to - not including - the lift's; another due date never")
    void heldOn() {
        BnplDisputeHold hold = new BnplDisputeHold(List.of(window(DUE, OPENED, Optional.of(LIFTED))));
        assertThat(hold.heldOn(DUE, OPENED.minusDays(1))).isFalse();
        assertThat(hold.heldOn(DUE, OPENED)).isTrue();
        assertThat(hold.heldOn(DUE, LIFTED.minusDays(1))).isTrue();
        assertThat(hold.heldOn(DUE, LIFTED)).isFalse();
        assertThat(hold.heldOn(DUE.plusDays(14), OPENED)).isFalse();
        assertThat(new BnplDisputeHold(List.of(window(DUE, OPENED, Optional.empty()))).heldOn(DUE, LIFTED.plusYears(1)))
                .as("an open dispute holds until it resolves").isTrue();
    }

    @Test
    @DisplayName("the clock starts at the later of the due date and the latest lift by the date - the due date otherwise")
    void clockFrom() {
        BnplDisputeHold hold = new BnplDisputeHold(List.of(window(DUE, OPENED, Optional.of(LIFTED)),
                window(DUE, OPENED, Optional.of(LIFTED.plusDays(5)))));
        assertThat(hold.clockFrom(DUE, LIFTED.minusDays(1))).isEqualTo(DUE);
        assertThat(hold.clockFrom(DUE, LIFTED)).isEqualTo(LIFTED);
        assertThat(hold.clockFrom(DUE, LIFTED.plusDays(9))).isEqualTo(LIFTED.plusDays(5));
        assertThat(BnplDisputeHold.NONE.clockFrom(DUE, LIFTED)).isEqualTo(DUE);
        LocalDate early = DUE.minusDays(20);
        assertThat(new BnplDisputeHold(List.of(window(DUE, early.minusDays(1), Optional.of(early)))).clockFrom(DUE, LIFTED))
                .as("a lift before the due date never moves the clock earlier").isEqualTo(DUE);
    }

    @Test
    @DisplayName("a lift before its opening is refused")
    void aLiftIsNeverBeforeItsOpening() {
        assertThatThrownBy(() -> window(DUE, OPENED, Optional.of(OPENED.minusDays(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DPD through the hold: a held instalment is not past due; after the lift it counts from the lift; with no"
            + " hold it is P12-TSK-020's rule")
    void theConditionReadsTheHold() {
        BnplTerms terms = BnplTermsFixtures.payIn4().build();
        BnplPurchaseStore.BilledItem unpaid = new BnplPurchaseStore.BilledItem(UUID.randomUUID(), UUID.randomUUID(), DUE,
                eur(0), eur(0), eur(150_00), eur(0), eur(0), eur(0));
        BnplDisputeHold open = new BnplDisputeHold(List.of(window(DUE, OPENED, Optional.empty())));
        BnplDisputeHold lifted = new BnplDisputeHold(List.of(window(DUE, OPENED, Optional.of(LIFTED))));
        assertThat(BnplDelinquencyState.derive(List.of(unpaid), LIFTED, terms, false).daysPastDue()).isEqualTo(20);
        assertThat(BnplDelinquencyState.derive(List.of(unpaid), LIFTED, terms, false, open).daysPastDue()).isZero();
        assertThat(BnplDelinquencyState.derive(List.of(unpaid), LIFTED, terms, false, lifted).daysPastDue()).isZero();
        assertThat(BnplDelinquencyState.derive(List.of(unpaid), LIFTED.plusDays(7), terms, false, lifted).daysPastDue())
                .isEqualTo(7);
        assertThat(BnplDelinquencyState.derive(List.of(unpaid), LIFTED, terms, false, BnplDisputeHold.NONE).daysPastDue())
                .isEqualTo(20);
    }

    private static BnplDisputeHold.Window window(LocalDate due, LocalDate opened, Optional<LocalDate> lifted) {
        return new BnplDisputeHold.Window(BnplDisputeId.next(IDS), due, opened, lifted);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, BnplTermsFixtures.EUR);
    }
}
