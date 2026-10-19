package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplClosure;
import com.finapp.bnpl.BnplClosureReason;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The repayment doors' boundary, hermetic (`P12-TSK-018`): the receipt a retried key replays - the same repayment, its
 * allocation and any closure, never the key or a party ({@code INV-IDEM-01}); the views in exact decimal strings; the
 * body's amount exact at the currency's minor units; and the repayment meter's closed, eager series. The doors over a real
 * database are {@code BnplRepaymentApiTest}'s.
 */
@DisplayName("the BNPL repayment doors' boundary (P12-TSK-018)")
class BnplRepaymentDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("a repayment's receipt replays the repayment, its allocation and its closure - and never the key")
    void theReceiptReplays() {
        BnplAgreementId agreement = BnplAgreementId.next(IDS);
        BnplObligationId obligation = BnplObligationId.next(IDS);
        UUID instalment = IDS.next();
        BnplPurchaseStore.Repayment repayment = new BnplPurchaseStore.Repayment(IDS.next(), obligation,
                BnplPurchaseStore.Repayment.CUSTOMER, "the-customers-secret-key", eur(480_00), agreement, 1,
                "ALLOCATION_ENGINE_V1", JournalEntryId.of(IDS.next()), LocalDate.of(2026, 11, 28), List.of(
                        new BnplPurchaseStore.Allocation(IDS.next(), 1, "DUE_ITEM", Optional.of(instalment),
                                Optional.of("PRINCIPAL"), eur(450_00)),
                        new BnplPurchaseStore.Allocation(IDS.next(), 2, "CREDIT_BALANCE", Optional.empty(),
                                Optional.empty(), eur(30_00))));
        BnplClosure closure = new BnplClosure(IDS.next(), obligation, BnplClosureReason.REPAID, eur(30_00),
                Optional.of(JournalEntryId.of(IDS.next())), Optional.of(repayment.id()), CorrelationId.of("desk"));
        BnplRepaymentDesk.BnplRepaymentView view = BnplRepaymentDesk.view(agreement, repayment, Optional.of(closure),
                false);
        byte[] stored = BnplRepaymentDesk.stored(view).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("the-customers-secret-key");
        assertThat(BnplRepaymentDesk.replayed(stored)).isEqualTo(view);
        assertThat(view.amount()).isEqualTo("480.00");
        assertThat(view.status()).isEqualTo("ALLOCATED");
        assertThat(view.allocations()).extracting(BnplRepaymentDesk.BnplRepaymentAllocationView::instalmentId)
                .containsExactly(instalment.toString(), null);
        assertThat(view.closure()).isEqualTo(new BnplRepaymentDesk.BnplRepaymentClosureView("REPAID", "30.00"));

        BnplRepaymentDesk.BnplRepaymentView open = BnplRepaymentDesk.view(agreement, repayment, Optional.empty(), false);
        assertThat(BnplRepaymentDesk.replayed(BnplRepaymentDesk.stored(open).body())).isEqualTo(open);
        assertThat(open.closure()).isNull();
    }

    @Test
    @DisplayName("the body's amount is exact at the currency's minor units; an exponent, an unknown currency or a sub-cent"
            + " amount is 422 before anything")
    void theAmountIsExact() {
        assertThat(BnplRepaymentDesk.amount(new BnplRepaymentController.BnplRepaymentBody("40.00", "EUR")))
                .isEqualTo(eur(40_00));
        for (BnplRepaymentController.BnplRepaymentBody invalid : List.of(
                new BnplRepaymentController.BnplRepaymentBody("1e3", "EUR"),
                new BnplRepaymentController.BnplRepaymentBody("1.001", "EUR"),
                new BnplRepaymentController.BnplRepaymentBody("1.00", "XXQ"))) {
            assertThatThrownBy(() -> BnplRepaymentDesk.amount(invalid)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("a conservation refused at commit is recognised by its invariant - and only it")
    void aRefusedConservationIsRecognised() {
        assertThat(BnplRepaymentDesk.refusedConservation(new IllegalStateException("wrapped",
                new IllegalStateException("no billed component is ever paid beyond what it billed (P12-TSK-018,"
                        + " INV-LND-04)")))).isTrue();
        assertThat(BnplRepaymentDesk.refusedConservation(new IllegalStateException("a deadlock"))).isFalse();
    }

    @Test
    @DisplayName("the repayment meter registers every kind and outcome eagerly, and the anomaly counter")
    void theMeterIsEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplRepaymentMetrics metrics = new BnplRepaymentMetrics(registry);
        assertThat(registry.find(BnplRepaymentMetrics.REPAYMENT).counters())
                .hasSize(BnplRepaymentMetrics.Kind.values().length * BnplRepaymentMetrics.Outcome.values().length);
        metrics.record(BnplRepaymentMetrics.Kind.CUSTOMER, BnplRepaymentMetrics.Outcome.CLOSED);
        assertThat(registry.get(BnplRepaymentMetrics.REPAYMENT).tags("kind", "customer", "outcome", "closed").counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get(BnplRepaymentMetrics.ANOMALY).counter().count()).isZero();
        metrics.anomaly();
        assertThat(registry.get(BnplRepaymentMetrics.ANOMALY).counter().count()).isEqualTo(1.0);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
