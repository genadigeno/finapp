package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplDispute;
import com.finapp.bnpl.BnplDisputeId;
import com.finapp.bnpl.BnplDisputeProposal;
import com.finapp.bnpl.BnplDisputeReason;
import com.finapp.bnpl.BnplDisputeStatus;
import com.finapp.bnpl.BnplDisputes;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplTermsVersionId;
import com.finapp.bnpl.RefundAllocation;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispute doors' boundary, hermetic (`P12-TSK-023`): the receipt a retried key replays - no free text, never the key,
 * the reason or the statement ({@code INV-IDEM-01}, {@code INV-AUD-02}); the merchant's answer closed against the
 * customer's facts (plan section 11); the body's vocabularies and amount refused before anything; and the dispute meter's
 * closed, eager series. The doors over a real database are {@code BnplDisputeApiTest}'s.
 */
@DisplayName("the BNPL dispute doors' boundary (P12-TSK-023)")
class BnplDisputeDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("the receipt replays the view exactly and carries no reason, statement or key; with and without a proposal")
    void theReceiptReplays() {
        BnplDispute dispute = dispute(BnplDisputeStatus.MERCHANT_RESPONDED);
        BnplDisputeProposal proposal = new BnplDisputeProposal(IDS.next(), dispute.id(), BnplDisputeStatus.RESOLVED_REFUND,
                BnplDisputeProposal.Status.PROPOSED, "OPERATOR:a", "proposal reason text", Optional.of(Instant.EPOCH),
                Optional.empty(), Optional.empty(), Optional.empty(), CorrelationId.of("c"));
        for (BnplDisputeDesk.BnplDisputeView view : List.of(BnplDisputeDesk.view(dispute, null),
                BnplDisputeDesk.view(dispute, proposal))) {
            byte[] stored = BnplDisputeDesk.stored(view).body();
            assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("it never came")
                    .doesNotContain("we shipped it").doesNotContain("proposal reason text");
            assertThat(BnplDisputeDesk.replayed(stored)).isEqualTo(view);
        }
        BnplDisputeDesk.BnplDisputeView view = BnplDisputeDesk.view(dispute, proposal);
        assertThat(view.amount()).isEqualTo("300.00");
        assertThat(view.heldInstalments()).isEqualTo(1);
        assertThat(view.proposalStatus()).isEqualTo("PROPOSED");
        assertThat(view.proposalOutcome()).isEqualTo("RESOLVED_REFUND");
    }

    @Test
    @DisplayName("the merchant's answer is closed: no member names the reason, a held instalment, the customer or the"
            + " schedule")
    void theMerchantsAnswerIsClosed() {
        assertThat(Arrays.stream(BnplDisputeDesk.BnplMerchantDisputeView.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("disputeId", "purchaseId", "status", "category", "amount", "currency",
                        "responseDeadline", "refundId");
        BnplDisputeDesk.BnplMerchantDisputeView merchant = BnplDisputeDesk.merchantView(
                dispute(BnplDisputeStatus.OPEN));
        assertThat(merchant.toString()).doesNotContain("it never came");
    }

    @Test
    @DisplayName("the body's vocabularies are closed and its amount exact - anything else is 422 before anything")
    void theBodyIsJudgedFirst() {
        assertThat(BnplDisputeDesk.category("not_received")).isEqualTo(BnplDisputeReason.NOT_RECEIVED);
        assertThat(BnplDisputeDesk.decision("accept")).isEqualTo(BnplDisputes.Decision.ACCEPT);
        assertThat(BnplDisputeDesk.outcome("REFUND")).isEqualTo(BnplDisputeStatus.RESOLVED_REFUND);
        assertThat(BnplDisputeDesk.outcome("uphold")).isEqualTo(BnplDisputeStatus.RESOLVED_UPHELD);
        assertThat(BnplDisputeDesk.amount("40.00", "EUR")).isEqualTo(Money.ofMinorUnits(40_00, EUR));
        for (Runnable invalid : List.<Runnable>of(() -> BnplDisputeDesk.category("LOST"),
                () -> BnplDisputeDesk.decision("MAYBE"), () -> BnplDisputeDesk.outcome("WITHDRAWN"),
                () -> BnplDisputeDesk.amount("1e3", "EUR"), () -> BnplDisputeDesk.amount("1.001", "EUR"),
                () -> BnplDisputeDesk.amount("1.00", "XXQ"))) {
            assertThatThrownBy(invalid::run).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> BnplDisputeDesk.disputeId("not-a-uuid")).isInstanceOf(ApiException.class)
                .hasMessageContaining("No BNPL dispute");
    }

    @Test
    @DisplayName("finapp.bnpl.dispute{outcome} is registered at zero for every outcome; an act counts its own once; every"
            + " BnplDisputes outcome has its series")
    void theMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplDisputeMetrics metrics = new BnplDisputeMetrics(registry);
        assertThat(registry.find(BnplDisputeMetrics.DISPUTE).counters())
                .hasSize(BnplDisputeMetrics.Outcome.values().length)
                .allSatisfy(counter -> assertThat(counter.count()).isZero())
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey).containsExactly("outcome"));
        for (BnplDisputes.Outcome acted : BnplDisputes.Outcome.values()) {
            metrics.record(BnplDisputeMetrics.Outcome.of(acted));
        }
        metrics.record(BnplDisputeMetrics.Outcome.SELF_APPROVAL_REFUSED);
        assertThat(registry.get(BnplDisputeMetrics.DISPUTE).tags("outcome", "resolved_refund").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(BnplDisputeMetrics.DISPUTE).tags("outcome", "self_approval_refused").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(BnplDisputeMetrics.DISPUTE).tags("outcome", "sweep_failed").counter().count()).isZero();
    }

    private static BnplDispute dispute(BnplDisputeStatus status) {
        Money amount = Money.ofMinorUnits(300_00, EUR);
        BnplDispute.Held held = new BnplDispute.Held(IDS.next(), 4, IDS.next(), LocalDate.of(2026, 11, 28),
                RefundAllocation.Cancellation.Kind.UNBILLED, Money.ofMinorUnits(150_00, EUR));
        boolean answered = status != BnplDisputeStatus.OPEN;
        return new BnplDispute(BnplDisputeId.next(IDS), BnplPurchaseId.next(IDS), BnplObligationId.of(IDS.next()),
                IDS.next(), IDS.next(), BnplAgreementId.of(IDS.next()), BnplTermsVersionId.of(IDS.next()), status,
                BnplDisputeReason.NOT_RECEIVED, "it never came", "key-1", amount, Money.ofMinorUnits(0, EUR),
                Optional.of(Instant.parse("2026-10-20T12:00:00Z")), Optional.of(LocalDate.of(2026, 10, 20)),
                Optional.of(Instant.parse("2026-11-03T12:00:00Z")),
                answered ? Optional.of("we shipped it") : Optional.empty(),
                answered ? Optional.of(Instant.parse("2026-10-21T12:00:00Z")) : Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                CorrelationId.of("c"), List.of(held));
    }
}
