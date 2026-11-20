package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A reviewer's request for information and its machine (`P17-TSK-126`, ADR-0155 decision 2): {@code OPEN → ANSWERED | EXPIRED},
 * both terminal, refused by the entity off its edges; the deadline fourteen days after the asking.
 */
@DisplayName("an information request closes once, answered or expired (P17-TSK-126)")
class InformationRequestTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CustomerRequestText TEXT =
            CustomerRequestText.of("Please upload a clearer photo of your passport, all four corners visible.");

    @Test
    @DisplayName("a request opens OPEN, asked now and due fourteen days later, with nothing closed")
    void opensOpenWithItsDeadline() {
        InformationRequest request = open();
        assertThat(request.status()).isEqualTo(InformationRequestStatus.OPEN);
        assertThat(request.requestedAt()).isEqualTo(CLOCK.instant());
        assertThat(request.dueAt()).isEqualTo(CLOCK.instant().plus(Duration.ofDays(14)));
        assertThat(InformationRequest.ANSWER_WINDOW).isEqualTo(Duration.ofDays(14));
        assertThat(request.closedAt()).isEmpty();
        assertThat(request.answer()).isEmpty();
        assertThat(request.lapsedAt(request.dueAt().minusMillis(1))).isFalse();
        assertThat(request.lapsedAt(request.dueAt())).isTrue();
    }

    @Test
    @DisplayName("an answer closes it ANSWERED with the answer and its author; an expiry closes it EXPIRED with neither")
    void bothClosesAreRecorded() {
        UUID customer = IDS.next();
        InformationRequest answered = open().answer(new CustomerAnswer("Uploaded a new photo."), customer,
                CLOCK.instant().plusSeconds(60));
        assertThat(answered.status()).isEqualTo(InformationRequestStatus.ANSWERED);
        assertThat(answered.answeredBy()).contains(customer);
        assertThat(answered.closedAt()).contains(CLOCK.instant().plusSeconds(60));

        InformationRequest expired = open().expire(CLOCK.instant().plus(Duration.ofDays(15)));
        assertThat(expired.status()).isEqualTo(InformationRequestStatus.EXPIRED);
        assertThat(expired.answer()).isEmpty();
        assertThat(expired.answeredBy()).isEmpty();
    }

    @Test
    @DisplayName("a close on a clock behind the asking is stamped at the asking, never before it")
    void aCloseIsNeverBeforeTheAsking() {
        assertThat(open().expire(CLOCK.instant().minusSeconds(5)).closedAt()).contains(CLOCK.instant());
    }

    @Test
    @DisplayName("every pair of statuses behaves as the machine declares: a closed request moves nowhere, OPEN to itself neither")
    void everyTransitionIsEnforced() {
        for (InformationRequestStatus from : InformationRequestStatus.values()) {
            for (InformationRequestStatus to : InformationRequestStatus.values()) {
                InformationRequest request = at(from);
                boolean permitted = from.canTransitionTo(to);
                if (permitted) {
                    assertThat(move(request, to).status()).as("%s -> %s", from, to).isEqualTo(to);
                } else if (to == InformationRequestStatus.OPEN) {
                    assertThat(from.permittedTransitions()).as("nothing reopens a request").doesNotContain(to);
                } else {
                    assertThatThrownBy(() -> move(request, to))
                            .as("%s -> %s must be refused", from, to)
                            .isInstanceOf(IllegalInformationRequestTransitionException.class);
                }
            }
        }
        assertThat(InformationRequestStatus.OPEN.permittedTransitions())
                .isEqualTo(EnumSet.of(InformationRequestStatus.ANSWERED, InformationRequestStatus.EXPIRED));
        assertThat(InformationRequestStatus.ANSWERED.isTerminal()).isTrue();
        assertThat(InformationRequestStatus.EXPIRED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a row that contradicts itself cannot be rehydrated: closed exactly when not OPEN, an answer exactly when ANSWERED")
    void anIncoherentRowIsRefused() {
        InformationRequest open = open();
        assertThatThrownBy(() -> InformationRequest.rehydrate(open.id(), open.caseId(), open.reason(), open.text(),
                open.requestedBy(), open.requestedAt(), open.dueAt(), open.correlationId(), InformationRequestStatus.EXPIRED,
                Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InformationRequest.rehydrate(open.id(), open.caseId(), open.reason(), open.text(),
                open.requestedBy(), open.requestedAt(), open.dueAt(), open.correlationId(), InformationRequestStatus.EXPIRED,
                Optional.of(CLOCK.instant()), Optional.of(new CustomerAnswer("an answer")), Optional.of(IDS.next())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InformationRequest.rehydrate(open.id(), open.caseId(), open.reason(), open.text(),
                open.requestedBy(), open.requestedAt(), open.requestedAt(), open.correlationId(),
                InformationRequestStatus.OPEN, Optional.empty(), Optional.empty(), Optional.empty()))
                .as("the deadline follows the asking")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an answer is 1 to 2,000 characters, not blank, and neither it nor the request prints its words")
    void theAnswerIsBoundedAndQuiet() {
        assertThatThrownBy(() -> new CustomerAnswer("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerAnswer("x".repeat(CustomerAnswer.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerAnswer("bell\u0007")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CustomerAnswer("x".repeat(CustomerAnswer.MAX_LENGTH)).value()).hasSize(2000);
        assertThat(new CustomerAnswer("my secret words").toString()).doesNotContain("secret");
        assertThat(open().toString()).doesNotContain("passport");
    }

    private static InformationRequest open() {
        return InformationRequest.open(IDS, CLOCK, KycCaseId.next(IDS), InformationRequestReason.DOCUMENT_UNREADABLE, TEXT,
                IDS.next(), "corr-1");
    }

    private static InformationRequest at(InformationRequestStatus status) {
        InformationRequest open = open();
        return switch (status) {
            case OPEN -> open;
            case ANSWERED -> open.answer(new CustomerAnswer("an answer"), IDS.next(), CLOCK.instant());
            case EXPIRED -> open.expire(CLOCK.instant());
        };
    }

    private static InformationRequest move(InformationRequest request, InformationRequestStatus to) {
        return switch (to) {
            case OPEN -> throw new IllegalStateException("no method reopens a request");
            case ANSWERED -> request.answer(new CustomerAnswer("an answer"), IDS.next(), CLOCK.instant());
            case EXPIRED -> request.expire(CLOCK.instant());
        };
    }
}
