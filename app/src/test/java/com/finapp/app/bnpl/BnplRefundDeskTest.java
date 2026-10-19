package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplClosure;
import com.finapp.bnpl.BnplClosureReason;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplRefund;
import com.finapp.bnpl.BnplRefunds;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The refund doors' boundary, hermetic (`P12-TSK-021`): the receipts a retried key replays - the merchant's purchase-level
 * figures and the operator's split, never the key or the reason ({@code INV-IDEM-01}, {@code INV-AUD-02}); the merchant's
 * answer closed against the customer's facts (the `-014` closed view); the body's amount exact at the currency's minor
 * units; the refused conservation recognised; and the refund meter's closed, eager series. The doors over a real database
 * are {@code BnplRefundApiTest}'s.
 */
@DisplayName("the BNPL refund doors' boundary (P12-TSK-021)")
class BnplRefundDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("the merchant's receipt replays its purchase-level figures and never the key, the reason, an instalment or"
            + " the customer's excess; the operator's replays the split and the closure")
    void theReceiptsReplay() {
        BnplRefunds.Refunded refunded = refunded(BnplRefunds.Kind.FULL);
        BnplRefundDesk.BnplMerchantRefundView merchant = BnplRefundDesk.merchantView(refunded);
        byte[] stored = BnplRefundDesk.stored(merchant).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("the-merchants-secret-key")
                .doesNotContain("goods came back");
        assertThat(BnplRefundDesk.replayedMerchant(stored)).isEqualTo(merchant);
        assertThat(merchant.amount()).isEqualTo("600.00");
        assertThat(merchant.payableDebit()).isEqualTo("576.00");
        assertThat(merchant.purchaseStatus()).isEqualTo("FULLY_REFUNDED");

        BnplRefundDesk.BnplOperatorRefundView operator = BnplRefundDesk.operatorView(refunded);
        assertThat(BnplRefundDesk.replayedOperator(BnplRefundDesk.stored(operator).body())).isEqualTo(operator);
        assertThat(operator.excess()).isEqualTo("150.00");
        assertThat(operator.excessTo()).isEqualTo("WALLET");
        assertThat(operator.closure()).isEqualTo("REFUNDED");
        BnplRefundDesk.BnplOperatorRefundView open = BnplRefundDesk.operatorView(new BnplRefunds.Refunded(
                refunded.refund(), Optional.empty(), false, BnplRefunds.Kind.PARTIAL));
        assertThat(BnplRefundDesk.replayedOperator(BnplRefundDesk.stored(open).body())).isEqualTo(open);
        assertThat(open.purchaseStatus()).isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    @DisplayName("the merchant's answer is closed: no member names an instalment, the excess, the customer, the wallet or"
            + " the agreement")
    void theMerchantsAnswerIsClosed() {
        assertThat(Arrays.stream(BnplRefundDesk.BnplMerchantRefundView.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("refundId", "purchaseId", "amount", "currency", "feeReturned", "payableDebit",
                        "purchaseStatus", "valueDate");
    }

    @Test
    @DisplayName("the body's amount is exact at the currency's minor units; an exponent, an unknown currency or a sub-cent"
            + " amount is 422 before anything")
    void theAmountIsExact() {
        assertThat(BnplRefundDesk.amount(new BnplRefundController.BnplRefundBody("40.00", "EUR", "r")))
                .isEqualTo(Money.ofMinorUnits(40_00, EUR));
        for (BnplRefundController.BnplRefundBody invalid : List.of(
                new BnplRefundController.BnplRefundBody("1e3", "EUR", "r"),
                new BnplRefundController.BnplRefundBody("1.001", "EUR", "r"),
                new BnplRefundController.BnplRefundBody("1.00", "XXQ", "r"))) {
            assertThatThrownBy(() -> BnplRefundDesk.amount(invalid)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("a conservation refused at commit is recognised by its invariant - INV-BNPL-06 or -07 - and only it")
    void aRefusedConservationIsRecognised() {
        assertThat(BnplRefundDesk.refusedConservation(new IllegalStateException("wrapped", new IllegalStateException(
                "a BNPL refund's adjustments sum to its cancellations (P12-TSK-021, INV-BNPL-06)")))).isTrue();
        assertThat(BnplRefundDesk.refusedConservation(new IllegalStateException("a deadlock"))).isFalse();
    }

    @Test
    @DisplayName("the refund meter registers every kind and outcome eagerly, and the shared anomaly counter")
    void theMeterIsEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplRefundMetrics metrics = new BnplRefundMetrics(registry);
        assertThat(registry.find(BnplRefundMetrics.REFUND).counters())
                .hasSize(BnplRefunds.Kind.values().length * BnplRefundMetrics.Outcome.values().length);
        metrics.record(BnplRefunds.Kind.FULL, BnplRefundMetrics.Outcome.CLOSED);
        assertThat(registry.get(BnplRefundMetrics.REFUND).tags("kind", "full", "outcome", "closed").counter().count())
                .isEqualTo(1.0);
        metrics.anomaly();
        assertThat(registry.get(BnplRepaymentMetrics.ANOMALY).counter().count()).isEqualTo(1.0);
        new BnplRepaymentMetrics(registry).anomaly();
        assertThat(registry.get(BnplRepaymentMetrics.ANOMALY).counter().count()).as("one counter, two meters")
                .isEqualTo(2.0);
    }

    private static BnplRefunds.Refunded refunded(BnplRefunds.Kind kind) {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        UUID refund = IDS.next();
        BnplRefund row = new BnplRefund(refund, BnplPurchaseId.next(IDS), obligation, IDS.next(), BnplRefund.Door.MERCHANT,
                "MERCHANT:x", "the-merchants-secret-key", eur(600_00), eur(0), eur(24_00), eur(576_00), eur(450_00),
                eur(0), eur(150_00), BnplRefund.ExcessTo.WALLET, eur(0), eur(0), "REFUND_ALLOCATION_ENGINE_V1",
                BnplAgreementId.next(IDS), 2, 2, "the goods came back", JournalEntryId.of(IDS.next()),
                LocalDate.of(2026, 10, 20), Optional.of(Instant.parse("2026-10-20T10:00:00Z")), CorrelationId.of("desk"),
                List.of(new BnplRefund.Adjustment(IDS.next(), 4, IDS.next(),
                        com.finapp.bnpl.RefundAllocation.Cancellation.Kind.UNBILLED, eur(450_00), eur(0), true)));
        BnplClosure closure = new BnplClosure(IDS.next(), obligation, BnplClosureReason.REFUNDED, eur(0), Optional.empty(),
                Optional.empty(), Optional.of(refund), CorrelationId.of("desk"));
        return new BnplRefunds.Refunded(row, Optional.of(closure), false, kind);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
