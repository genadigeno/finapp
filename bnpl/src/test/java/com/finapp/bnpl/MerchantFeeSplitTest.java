package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** bnpl's own boundary on the merchant fee split (`P12-TSK-006`; {@code INV-BNPL-04}, ADR-0058). */
@DisplayName("a merchant fee split conserves the gross and covers its fee (P12-TSK-006)")
class MerchantFeeSplitTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID VERSION = UUID.randomUUID();

    @Test
    @DisplayName("gross = fee + net is admitted, a zero fee included (pay-in-N's optional fee, OQ-17)")
    void aConservingSplitIsAdmitted() {
        assertThat(split(10_000, 299, 9_701).net()).isEqualTo(eur(9_701));
        assertThat(split(10_000, 0, 10_000).fee().isZero()).isTrue();
    }

    @Test
    @DisplayName("a split that does not conserve the gross is refused - one minor unit either way")
    void aNonConservingSplitIsRefused() {
        assertThatThrownBy(() -> split(10_000, 299, 9_700)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> split(10_000, 299, 9_702)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a sale that does not cover its fee, a negative fee and a non-positive gross are refused")
    void theBoundsAreHeld() {
        assertThatThrownBy(() -> split(100, 100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> split(100, -1, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> split(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantFeeSplit(eur(100), Money.ofMinorUnits(1, CurrencyCode.of("USD")),
                eur(99), VERSION)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("its string form names the version and no amount")
    void noAmountInItsStringForm() {
        assertThat(split(10_000, 299, 9_701).toString()).doesNotContain("299").doesNotContain("9701")
                .contains(VERSION.toString());
    }

    @Test
    @DisplayName("the port's returned fee binds as the refund allocator's MerchantFeeReturn, the purchase fixed (P12-TSK-009)")
    void thePortBindsAsTheAllocatorsFeeReturn() {
        BnplPurchaseId purchase = BnplPurchaseId.of(java.util.UUID.fromString("01900000-0000-7000-8000-000000000006"));
        BnplMerchants port = new BnplMerchants() {
            @Override
            public Standing standing(java.sql.Connection unitOfWork, UUID merchantRef, BnplProduct product) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean coversFee(java.sql.Connection unitOfWork, UUID merchantRef, BnplProduct product, Money gross) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<com.finapp.ledger.LedgerAccountId> payableAccount(
                    java.sql.Connection unitOfWork, UUID merchantRef, CurrencyCode currency) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MerchantFeeSplit pinFee(java.sql.Connection unitOfWork, UUID merchantRef, BnplProduct product,
                    BnplPurchaseId purchaseId, Money gross) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Money returnedFee(java.sql.Connection unitOfWork, BnplPurchaseId purchaseId, Money refundedBefore,
                    Money refunded) {
                assertThat(purchaseId).isEqualTo(purchase);
                return refundedBefore.plus(refunded).minus(refundedBefore);
            }
        };
        MerchantFeeReturn bound = (before, refunded) -> port.returnedFee(null, purchase, before, refunded);
        assertThat(bound.returnedFee(eur(100), eur(50))).isEqualTo(eur(50));
    }

    private static MerchantFeeSplit split(long gross, long fee, long net) {
        return new MerchantFeeSplit(eur(gross), eur(fee), eur(net), VERSION);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
