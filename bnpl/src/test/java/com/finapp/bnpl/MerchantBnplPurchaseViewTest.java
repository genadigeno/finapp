package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The merchant's view of a BNPL purchase is closed (`P12-TSK-014`; {@code INV-CRD-04}, {@code INV-BNPL-02},
 * {@code INV-BNPL-04}): its components are exactly the merchant's facts - no customer credit datum has a field to travel
 * in - the financing conserves the purchase as read, and a purchase is never seen {@code AUTHORISED}.
 */
@DisplayName("the merchant's view of a BNPL purchase (P12-TSK-014)")
class MerchantBnplPurchaseViewTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("the field set is closed: the purchase, the session and order, the product, the status, gross, fee, net,"
            + " the pinned version, F1 and its instant - nothing of the customer's credit")
    void theFieldSetIsClosed() {
        assertThat(Arrays.stream(MerchantBnplPurchaseView.class.getRecordComponents()).map(RecordComponent::getName))
                .as("a component added here is a datum sent to a merchant: INV-CRD-04 forbids a party, a plan, a"
                        + " decision, a reason, a score, an approved amount, a schedule, a repayment or a delinquency")
                .containsExactly("purchase", "checkoutSession", "order", "product", "status", "gross", "fee", "net",
                        "feeScheduleVersion", "financingEntry", "financedAt");
    }

    @Test
    @DisplayName("INV-BNPL-04 as read: a financing whose fee and net do not sum to the gross is refused")
    void theFinancingConservesThePurchase() {
        assertThatIllegalStateException().isThrownBy(() -> view(BnplPurchaseStatus.FINANCED, 600_00, 24_00, 575_00))
                .withMessageContaining("gross = fee + net");
        assertThat(view(BnplPurchaseStatus.FINANCED, 600_00, 24_00, 576_00).net()).isEqualTo(Money.ofMinorUnits(576_00,
                EUR));
    }

    @Test
    @DisplayName("INV-BNPL-02 as read: a purchase is never seen AUTHORISED - FINANCED or FULLY_REFUNDED only")
    void neverAuthorised() {
        assertThatIllegalStateException().isThrownBy(() -> view(BnplPurchaseStatus.AUTHORISED, 600_00, 24_00, 576_00));
        assertThat(view(BnplPurchaseStatus.FULLY_REFUNDED, 600_00, 24_00, 576_00).status())
                .isEqualTo(BnplPurchaseStatus.FULLY_REFUNDED);
    }

    @Test
    @DisplayName("its text is the purchase and its status - never a figure, the order or the version (INV-AUD-02)")
    void itsTextCarriesNoFigure() {
        MerchantBnplPurchaseView view = view(BnplPurchaseStatus.FINANCED, 600_00, 24_00, 576_00);
        assertThat(view.toString()).contains(view.purchase().value().toString()).doesNotContain("600.00", "576.00", "24.00",
                view.order().toString(), view.feeScheduleVersion().toString(), view.checkoutSession().toString());
    }

    static MerchantBnplPurchaseView view(BnplPurchaseStatus status, long gross, long fee, long net) {
        return new MerchantBnplPurchaseView(BnplPurchaseId.next(IDS), IDS.next(), IDS.next(),
                BnplProduct.BNPL_PAY_IN_4, status, Money.ofMinorUnits(gross, EUR), Money.ofMinorUnits(fee, EUR),
                Money.ofMinorUnits(net, EUR), IDS.next(), JournalEntryId.of(IDS.next()),
                Instant.parse("2026-10-18T09:00:00Z"));
    }
}
