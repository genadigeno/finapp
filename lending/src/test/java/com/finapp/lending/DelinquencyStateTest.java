package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.creditmath.DueItemId;
import com.finapp.creditmath.DueState;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The delinquency derivation, hermetically (`P11-TSK-024`; ADR-0098 point 1, {@code INV-LND-12}): DPD is a difference of
 * dates from the oldest item unpaid at the end of its due date, the bucket follows from the pinned bounds, the default
 * flag is set at the threshold and cleared only at cure (A8). The database's own rule ({@code lending V020}) is the same,
 * proven against the rows by {@code DelinquencyDatabaseTest}.
 */
@DisplayName("the delinquency derivation (P11-TSK-024)")
class DelinquencyStateTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final LoanTerms TERMS = LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Test
    @DisplayName("nothing billed, an item due today, an item paid: CURRENT - due is not past due, and paid is not unpaid")
    void currentWhenNothingIsPastDue() {
        assertThat(derive(List.of(), false)).isEqualTo(DelinquencyState.CURRENT);
        assertThat(derive(List.of(item(TODAY, 100_00)), false)).isEqualTo(DelinquencyState.CURRENT);
        assertThat(derive(List.of(item(TODAY.minusDays(40), 0), item(TODAY.plusDays(5), 100_00)), false))
                .isEqualTo(DelinquencyState.CURRENT);
    }

    @Test
    @DisplayName("DPD is the business date less the oldest unpaid due date - one minor unit unpaid is unpaid; the younger"
            + " items do not move it")
    void dpdIsTheOldestUnpaidItemsAge() {
        DelinquencyState state = derive(List.of(item(TODAY.minusDays(61), 1), item(TODAY.minusDays(31), 500_00),
                item(TODAY.minusDays(1), 500_00)), false);
        assertThat(state.daysPastDue()).isEqualTo(61);
        assertThat(state.bucket()).contains(DelinquencyBucket.B3_60_89);
        assertThat(state.defaulted()).isFalse();
        assertThat(derive(List.of(item(TODAY.minusDays(61), 0), item(TODAY.minusDays(31), 500_00)), false).daysPastDue())
                .as("the oldest paid in full: the next oldest's age").isEqualTo(31);
    }

    @Test
    @DisplayName("the buckets follow the pinned bounds at their edges: 1 and 29 B1, 30 B2, 59 B2, 60 B3, 89 B3, 90 B4 - and"
            + " other bounds move them")
    void theBucketsFollowThePinnedBounds() {
        List<Integer> bounds = TERMS.delinquencyBounds();
        assertThat(DelinquencyBucket.of(0, bounds)).isEmpty();
        assertThat(DelinquencyBucket.of(1, bounds)).contains(DelinquencyBucket.B1_1_29);
        assertThat(DelinquencyBucket.of(29, bounds)).contains(DelinquencyBucket.B1_1_29);
        assertThat(DelinquencyBucket.of(30, bounds)).contains(DelinquencyBucket.B2_30_59);
        assertThat(DelinquencyBucket.of(59, bounds)).contains(DelinquencyBucket.B2_30_59);
        assertThat(DelinquencyBucket.of(60, bounds)).contains(DelinquencyBucket.B3_60_89);
        assertThat(DelinquencyBucket.of(89, bounds)).contains(DelinquencyBucket.B3_60_89);
        assertThat(DelinquencyBucket.of(90, bounds)).contains(DelinquencyBucket.B4_90_PLUS);
        assertThat(DelinquencyBucket.of(400, bounds)).contains(DelinquencyBucket.B4_90_PLUS);
        assertThat(DelinquencyBucket.of(45, List.of(1, 15, 45, 60))).contains(DelinquencyBucket.B3_60_89);
        assertThatIllegalArgumentException().isThrownBy(() -> DelinquencyBucket.of(-1, bounds));
        assertThatIllegalArgumentException().isThrownBy(() -> DelinquencyBucket.of(1, List.of(1, 30)));
    }

    @Test
    @DisplayName("default: set at the threshold; held below it while anything is past due; cleared only at cure (A8) - and a"
            + " flag never recorded is not invented below the threshold")
    void defaultIsSetAtTheThresholdAndClearedAtCure() {
        assertThat(derive(List.of(item(TODAY.minusDays(89), 100_00)), false).defaulted()).isFalse();
        assertThat(derive(List.of(item(TODAY.minusDays(90), 100_00)), false).defaulted()).isTrue();
        assertThat(derive(List.of(item(TODAY.minusDays(10), 100_00)), true).defaulted())
                .as("held while past due").isTrue();
        assertThat(derive(List.of(item(TODAY.minusDays(10), 100_00)), false).defaulted()).isFalse();
        assertThat(derive(List.of(item(TODAY.minusDays(95), 0)), true)).as("cured: cleared")
                .isEqualTo(DelinquencyState.CURRENT);
    }

    @Test
    @DisplayName("a draw is suspended exactly while anything is past due or the line is defaulted; the state refuses a"
            + " defaulted CURRENT or a bucket without DPD")
    void theSuspensionAndTheShape() {
        assertThat(DelinquencyState.CURRENT.suspendsDraws()).isFalse();
        assertThat(derive(List.of(item(TODAY.minusDays(1), 1)), false).suspendsDraws()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> new DelinquencyState(0, Optional.empty(), true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DelinquencyState(0, Optional.of(DelinquencyBucket.B1_1_29), false));
        assertThatIllegalArgumentException().isThrownBy(() -> new DelinquencyState(3, Optional.empty(), false));
        assertThat(DelinquencyState.CURRENT.toString()).doesNotContain("0");
    }

    private static DelinquencyState derive(List<DueState.DueItem> items, boolean previouslyDefaulted) {
        return DelinquencyState.derive(items, TODAY, TERMS, previouslyDefaulted);
    }

    private static DueState.DueItem item(LocalDate due, long unpaidMinor) {
        Money zero = Money.ofPersisted(0, EUR, 2);
        return new DueState.DueItem(DueItemId.of(IDS.next()), due, zero, zero, Money.ofPersisted(unpaidMinor, EUR, 2));
    }
}
