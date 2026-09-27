package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispute response, its machine and the evidence value objects (`P7-TSK-014`, ADR-0061 §7) —
 * the domain rank of every rule `V022` holds for every writer.
 */
@DisplayName("dispute responses and evidence (P7-TSK-014)")
class DisputeResponseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor MERCHANT = new Actor("01920000-0000-7000-8000-000000000001", ActorType.MERCHANT);

    @Test
    @DisplayName("the machine is exactly DISPATCHED -> {SUBMITTED, FAILED, UNKNOWN},"
            + " UNKNOWN -> {SUBMITTED, FAILED}; SUBMITTED and FAILED are terminal, and every"
            + " status but FAILED is live")
    void exactlyTheEdges() {
        assertThat(DisputeResponseStatus.DISPATCHED.permittedTransitions())
                .isEqualTo(EnumSet.of(DisputeResponseStatus.SUBMITTED,
                        DisputeResponseStatus.FAILED, DisputeResponseStatus.UNKNOWN));
        assertThat(DisputeResponseStatus.UNKNOWN.permittedTransitions())
                .isEqualTo(EnumSet.of(DisputeResponseStatus.SUBMITTED,
                        DisputeResponseStatus.FAILED));
        assertThat(EnumSet.allOf(DisputeResponseStatus.class))
                .filteredOn(DisputeResponseStatus::isTerminal)
                .containsExactlyInAnyOrder(DisputeResponseStatus.SUBMITTED,
                        DisputeResponseStatus.FAILED);
        assertThat(EnumSet.allOf(DisputeResponseStatus.class))
                .filteredOn(status -> !status.isLive())
                .containsExactly(DisputeResponseStatus.FAILED);
        assertThat(DisputeResponseStatus.values()).hasSize(4);
    }

    @Test
    @DisplayName("born DISPATCHED with our reference minted, the evidence and requester frozen")
    void bornDispatchedWithItsReference() {
        DisputeEvidenceId first = DisputeEvidenceId.next(IDS);
        DisputeResponse response = representment(List.of(first));

        assertThat(response.status()).isEqualTo(DisputeResponseStatus.DISPATCHED);
        assertThat(response.reference().value()).startsWith("dsr-");
        assertThat(response.evidence()).containsExactly(first);
        assertThat(response.requestedById()).isEqualTo(MERCHANT.id());
        assertThat(response.requestedByType()).isEqualTo("MERCHANT");
        assertThat(response.providerReference()).isEmpty();
        assertThat(response.failure()).isEmpty();
        assertThat(response.createdAt()).isEqualTo(response.createdAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(response.toString()).doesNotContain(response.reference().value());
    }

    @Test
    @DisplayName("a representment carries evidence and an acceptance none; at most five distinct"
            + " documents; a stated reason is never blank")
    void theEvidenceMatchesTheKind() {
        assertThatThrownBy(() -> representment(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                DisputeResponse.dispatch(
                                        IDS, Instant.now(CLOCK), DisputeId.of(IDS.next()),
                                        DisputeResponseKind.ACCEPTANCE,
                                        List.of(DisputeEvidenceId.next(IDS)), MERCHANT,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        DisputeEvidenceId same = DisputeEvidenceId.next(IDS);
        assertThatThrownBy(() -> representment(List.of(same, same)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                representment(
                                        List.of(DisputeEvidenceId.next(IDS),
                                                DisputeEvidenceId.next(IDS),
                                                DisputeEvidenceId.next(IDS),
                                                DisputeEvidenceId.next(IDS),
                                                DisputeEvidenceId.next(IDS),
                                                DisputeEvidenceId.next(IDS))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                DisputeResponse.dispatch(
                                        IDS, Instant.now(CLOCK), DisputeId.of(IDS.next()),
                                        DisputeResponseKind.ACCEPTANCE, List.of(), MERCHANT,
                                        Optional.of("  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        DisputeResponse.dispatch(
                                        IDS, Instant.now(CLOCK), DisputeId.of(IDS.next()),
                                        DisputeResponseKind.ACCEPTANCE, List.of(), MERCHANT,
                                        Optional.empty())
                                .evidence())
                .isEmpty();
    }

    @Test
    @DisplayName("each outcome is its own edge: SUBMITTED carries the PSP's reference, FAILED its"
            + " reason, UNKNOWN nothing - and nothing leaves a terminal status")
    void everyEdgeAndEveryTerminal() {
        DisputeResponse dispatched = representment(List.of(DisputeEvidenceId.next(IDS)));

        DisputeResponse submitted = dispatched.submitted(new ProviderReference("psp_dr_1"));
        assertThat(submitted.status()).isEqualTo(DisputeResponseStatus.SUBMITTED);
        assertThat(submitted.providerReference()).contains(new ProviderReference("psp_dr_1"));

        DisputeResponse failed = dispatched.failed(DisputeResponseFailure.DECLINED);
        assertThat(failed.failure()).contains(DisputeResponseFailure.DECLINED);

        DisputeResponse unknown = dispatched.unknown();
        assertThat(unknown.status()).isEqualTo(DisputeResponseStatus.UNKNOWN);
        assertThat(unknown.failure()).isEmpty();
        assertThat(unknown.providerReference()).isEmpty();
    }

    @Test
    @DisplayName("UNKNOWN -> UNKNOWN is no edge, and SUBMITTED / FAILED refuse every move")
    void terminalIsTerminal() {
        DisputeResponse unknown = representment(List.of(DisputeEvidenceId.next(IDS))).unknown();
        assertThatThrownBy(unknown::unknown)
                .isInstanceOf(IllegalDisputeResponseTransitionException.class);
        for (DisputeResponse terminal :
                List.of(
                        unknown.submitted(new ProviderReference("psp_dr_2")),
                        unknown.failed(DisputeResponseFailure.PROVIDER_UNAVAILABLE))) {
            assertThatThrownBy(terminal::unknown)
                    .isInstanceOf(IllegalDisputeResponseTransitionException.class);
            assertThatThrownBy(() -> terminal.submitted(new ProviderReference("psp_dr_3")))
                    .isInstanceOf(IllegalDisputeResponseTransitionException.class);
            assertThatThrownBy(() -> terminal.failed(DisputeResponseFailure.DECLINED))
                    .isInstanceOf(IllegalDisputeResponseTransitionException.class);
        }
    }

    @Test
    @DisplayName("a corrupt row is refused at read: a reference without SUBMITTED, a failure"
            + " without FAILED")
    void aCorruptRowIsRefused() {
        assertThatThrownBy(
                        () ->
                                DisputeResponse.rehydrate(
                                        DisputeResponseId.next(IDS), DisputeId.of(IDS.next()),
                                        DisputeResponseKind.ACCEPTANCE,
                                        DisputeResponseStatus.DISPATCHED, Optional.empty(),
                                        new ProviderIdempotencyReference("dsr-x"),
                                        Optional.of(new ProviderReference("psp_dr_4")),
                                        List.of(), MERCHANT.id(), "MERCHANT", Optional.empty(),
                                        Instant.now(CLOCK)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                DisputeResponse.rehydrate(
                                        DisputeResponseId.next(IDS), DisputeId.of(IDS.next()),
                                        DisputeResponseKind.ACCEPTANCE,
                                        DisputeResponseStatus.UNKNOWN,
                                        Optional.of(DisputeResponseFailure.DECLINED),
                                        new ProviderIdempotencyReference("dsr-y"),
                                        Optional.empty(), List.of(), MERCHANT.id(), "MERCHANT",
                                        Optional.empty(), Instant.now(CLOCK)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("evidence content is 1 byte to 512 KiB, cloned in and out, and never printed")
    void evidenceContentIsBoundedAndPrivate() {
        assertThatThrownBy(() -> DisputeEvidenceContent.of(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DisputeEvidenceContent.of(new byte[DisputeEvidenceContent.MAX_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] bytes = "receipt #42 for card ending 4242".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        DisputeEvidenceContent content = DisputeEvidenceContent.of(bytes);
        bytes[0] = 'X';
        assertThat(content.value()[0]).isEqualTo((byte) 'r');
        content.value()[0] = 'Y';
        assertThat(content.value()[0]).isEqualTo((byte) 'r');
        assertThat(content.toString()).doesNotContain("receipt").contains("bytes");
        assertThat(DisputeEvidenceContent.of(new byte[DisputeEvidenceContent.MAX_BYTES]).length())
                .isEqualTo(DisputeEvidenceContent.MAX_BYTES);
        assertThat(DisputeEvidenceContentType.values())
                .extracting(DisputeEvidenceContentType::mediaType)
                .containsExactly("image/jpeg", "image/png", "application/pdf");
    }

    @Test
    @DisplayName("the respond-by deadline rides the chargeback: never on an inquiry's notice or"
            + " dispute, recorded once, truncated to the column's microseconds")
    void theDeadlineRidesTheChargeback() {
        Money amount = Money.ofMinorUnits(1000, CurrencyCode.of("EUR"));
        Instant deadline = Instant.parse("2026-10-11T23:59:59.123456789Z");
        assertThatThrownBy(
                        () ->
                                new DisputeNotice(
                                        "simulated-card", new ProviderReference("dp_9"),
                                        DisputeStage.INQUIRY, DisputeReason.FRAUD, amount,
                                        Optional.empty(), Optional.of(deadline)))
                .isInstanceOf(IllegalArgumentException.class);
        DisputeNotice charged =
                new DisputeNotice(
                        "simulated-card", new ProviderReference("dp_9"),
                        DisputeStage.CHARGED_BACK, DisputeReason.FRAUD, amount, Optional.empty(),
                        Optional.of(deadline));
        assertThat(charged.respondBy()).contains(deadline.truncatedTo(ChronoUnit.MICROS));

        Dispute inquiry =
                Dispute.open(IDS, Instant.now(CLOCK), "simulated-card",
                        new ProviderReference("dp_10"), PaymentAttemptId.of(IDS.next()),
                        DisputeReason.FRAUD, DisputeStage.INQUIRY, Optional.empty(),
                        Optional.empty());
        assertThatThrownBy(() -> inquiry.withRespondBy(deadline))
                .isInstanceOf(IllegalArgumentException.class);
        Dispute chargedBack =
                Dispute.open(IDS, Instant.now(CLOCK), "simulated-card",
                        new ProviderReference("dp_11"), PaymentAttemptId.of(IDS.next()),
                        DisputeReason.FRAUD, DisputeStage.CHARGED_BACK,
                        Optional.of(ChargebackSplit.of(amount, amount, true)), Optional.empty());
        Dispute dated = chargedBack.withRespondBy(deadline);
        assertThat(dated.respondBy()).contains(deadline.truncatedTo(ChronoUnit.MICROS));
        assertThatThrownBy(() -> dated.withRespondBy(deadline.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dated.advanceTo(DisputeStage.REPRESENTED, Optional.empty()).respondBy())
                .as("the deadline survives every edge")
                .isEqualTo(dated.respondBy());
    }

    @Test
    @DisplayName("the operator's reach is a set of account purposes and an act is reasoned")
    void theOperatorsReach() {
        DisputeActor.Operator operator =
                new DisputeActor.Operator(
                        Optional.of("customer disputes a top-up they made"),
                        Set.of(com.finapp.ledger.AccountPurpose.CUSTOMER_WALLET));
        assertThat(operator.actsFor())
                .containsExactly(com.finapp.ledger.AccountPurpose.CUSTOMER_WALLET);
        assertThatThrownBy(
                        () ->
                                new DisputeActor.Operator(
                                        Optional.of(" "),
                                        Set.of(com.finapp.ledger.AccountPurpose.CUSTOMER_WALLET)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DisputeResponse representment(List<DisputeEvidenceId> evidence) {
        return DisputeResponse.dispatch(
                IDS,
                Instant.now(CLOCK),
                DisputeId.of(IDS.next()),
                DisputeResponseKind.REPRESENTMENT,
                evidence,
                MERCHANT,
                Optional.empty());
    }
}
