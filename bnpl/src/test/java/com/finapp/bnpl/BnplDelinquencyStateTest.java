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
 * The derived condition, pure (`P12-TSK-020`; {@code PHASE_12_PLAN.md} section 12.8, A14; lending's
 * {@code DelinquencyStateTest}): DPD the date difference to the oldest instalment unpaid at the end of its due date, the
 * bucket under the pinned bounds, the flag at the threshold and held to cure; the value's shape and its redaction.
 */
@DisplayName("the BNPL delinquency state - derived, never stored (P12-TSK-020)")
class BnplDelinquencyStateTest {

    private static final BnplTerms TERMS = BnplTermsFixtures.payIn4().build();
    private static final LocalDate DUE = LocalDate.of(2026, 10, 31);

    @Test
    @DisplayName("past due only after the end of the due date, by the oldest unpaid instalment; a paid one counts not")
    void dpdIsTheOldestUnpaidInstalmentsAge() {
        List<BnplPurchaseStore.BilledItem> items = List.of(item(DUE, 0), item(DUE.plusDays(14), 1), item(DUE.plusDays(28), 150_00));
        assertThat(BnplDelinquencyState.derive(items, DUE.plusDays(14), TERMS, false)).isEqualTo(BnplDelinquencyState.CURRENT);
        BnplDelinquencyState state = BnplDelinquencyState.derive(items, DUE.plusDays(15), TERMS, false);
        assertThat(state.daysPastDue()).isEqualTo(1);
        assertThat(state.bucket()).contains(BnplDelinquencyBucket.B1_1_29);
        assertThat(BnplDelinquencyState.derive(items, DUE.plusDays(29), TERMS, false).daysPastDue()).isEqualTo(15);
    }

    @Test
    @DisplayName("the buckets at their bounds (1, 30, 60, 90) and the default at 90, held while past due, cleared at 0")
    void bucketsAndDefault() {
        List<BnplPurchaseStore.BilledItem> owing = List.of(item(DUE, 10));
        assertThat(BnplDelinquencyState.derive(owing, DUE.plusDays(29), TERMS, false).bucketName()).isEqualTo("B1_1_29");
        assertThat(BnplDelinquencyState.derive(owing, DUE.plusDays(30), TERMS, false).bucketName()).isEqualTo("B2_30_59");
        assertThat(BnplDelinquencyState.derive(owing, DUE.plusDays(60), TERMS, false).bucketName()).isEqualTo("B3_60_89");
        assertThat(BnplDelinquencyState.derive(owing, DUE.plusDays(89), TERMS, false).defaulted()).isFalse();
        BnplDelinquencyState ninety = BnplDelinquencyState.derive(owing, DUE.plusDays(90), TERMS, false);
        assertThat(ninety.bucketName()).isEqualTo("B4_90_PLUS");
        assertThat(ninety.defaulted()).isTrue();
        assertThat(BnplDelinquencyState.derive(List.of(item(DUE.plusDays(80), 10)), DUE.plusDays(90), TERMS, true)
                .defaulted()).as("held while anything is past due").isTrue();
        assertThat(BnplDelinquencyState.derive(List.of(item(DUE, 0)), DUE.plusDays(91), TERMS, true))
                .as("cleared at cure, no probation").isEqualTo(BnplDelinquencyState.CURRENT);
        assertThat(BnplDelinquencyBucket.of(5, List.of(1, 10, 20, 30))).contains(BnplDelinquencyBucket.B1_1_29);
        assertThat(BnplDelinquencyBucket.of(20, List.of(1, 10, 20, 30))).contains(BnplDelinquencyBucket.B3_60_89);
        assertThat(BnplDelinquencyBucket.sqlValueList()).isEqualTo("'CURRENT', 'B1_1_29', 'B2_30_59', 'B3_60_89', 'B4_90_PLUS'");
        assertThat(BnplConditionKind.sqlValueList()).isEqualTo("'DELINQUENCY', 'DEFAULT'");
    }

    @Test
    @DisplayName("the shape: CURRENT exactly at DPD 0, a defaulted state past due, nothing negative; toString redacted")
    void theShape() {
        assertThatThrownBy(() -> new BnplDelinquencyState(0, Optional.of(BnplDelinquencyBucket.B1_1_29), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplDelinquencyState(3, Optional.empty(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BnplDelinquencyState(0, Optional.empty(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BnplDelinquencyBucket.of(-1, List.of(1, 30, 60, 90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new BnplDelinquencyState(47, Optional.of(BnplDelinquencyBucket.B2_30_59), false).toString())
                .doesNotContain("47").doesNotContain("B2");
    }

    private static BnplPurchaseStore.BilledItem item(LocalDate due, long dueMinor) {
        Money zero = Money.ofMinorUnits(0, BnplTermsFixtures.EUR);
        return new BnplPurchaseStore.BilledItem(UUID.randomUUID(), UUID.randomUUID(), due, zero, zero,
                Money.ofMinorUnits(150_00, BnplTermsFixtures.EUR), zero, zero,
                Money.ofMinorUnits(150_00 - dueMinor, BnplTermsFixtures.EUR));
    }
}
