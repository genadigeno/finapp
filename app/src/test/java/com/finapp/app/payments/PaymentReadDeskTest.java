package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.payments.DisputeStage;
import com.finapp.payments.PaymentCollections;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment reads' requests, without a database (`P17-TSK-032`): each filter's vocabulary the domain's own, each instant and
 * date strict, each collection's cursor its own - the dispute queue's carrying an unstated deadline as a word, never an instant -
 * and the routing versions' cursor a positive number. The reads, their order, the permissions, the records and the masking are
 * {@code PaymentReadsApiDatabaseTest}'s and {@code RoutingPolicyDatabaseTest}'s.
 */
@DisplayName("the payment reads' requests (P17-TSK-032)")
class PaymentReadDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("a status and a stage are the machine's own words; an instant, a date and an identifier are strict")
    void theFiltersAreTheDomainsWords() {
        assertThat(PaymentReadDesk.intentStatus("SUCCEEDED")).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(PaymentReadDesk.disputeStage("CHARGED_BACK")).isEqualTo(DisputeStage.CHARGED_BACK);
        assertThat(PaymentReadDesk.instant("from", "2026-11-20T00:00:00Z")).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
        assertThat(PaymentReadDesk.date("date", "2026-11-20")).isEqualTo(LocalDate.parse("2026-11-20"));

        assertRefused(() -> PaymentReadDesk.intentStatus("succeeded"));
        assertRefused(() -> PaymentReadDesk.intentStatus("CAPTURED"));
        assertRefused(() -> PaymentReadDesk.disputeStage("OPEN"));
        assertRefused(() -> PaymentReadDesk.instant("from", null));
        assertRefused(() -> PaymentReadDesk.instant("from", "2026-11-20"));
        assertRefused(() -> PaymentReadDesk.date("date", null));
        assertRefused(() -> PaymentReadDesk.date("date", "2026-11-2"));
        assertRefused(() -> PaymentReadDesk.date("date", "2026-02-30"));
        assertRefused(() -> PaymentReadDesk.identifier("partyId", "someone"));
    }

    @Test
    @DisplayName("each collection's cursor is its own, exact to the microsecond, and refused anywhere else")
    void eachCursorIsItsOwn() {
        Instant at = Instant.parse("2026-11-20T10:15:30.123456Z");
        UUID id = IDS.next();
        String payments = OperatorCollection.cursor(PaymentReadDesk.PAYMENTS, at.toString(), id.toString());
        assertThat(PaymentReadDesk.paymentCursor(payments)).contains(new PaymentCollections.PaymentCursor(at, id));
        assertThat(PaymentReadDesk.paymentCursor(null)).isEmpty();
        String disputes = OperatorCollection.cursor(PaymentReadDesk.DISPUTES, at.toString(), id.toString());

        assertRefused(() -> PaymentReadDesk.paymentCursor(disputes));
        assertRefused(() -> PaymentReadDesk.disputeCursor(payments));
        assertRefused(() -> PaymentReadDesk.paymentCursor("not-a-cursor"));
        assertRefused(() -> PaymentReadDesk.paymentCursor(
                OperatorCollection.cursor(PaymentReadDesk.PAYMENTS, "yesterday", id.toString())));
    }

    @Test
    @DisplayName("the dispute queue's cursor carries a stated deadline as an instant and an unstated one as a word")
    void theQueueCursorKnowsAnUnstatedDeadline() {
        Instant due = Instant.parse("2026-11-25T00:00:00Z");
        UUID id = IDS.next();
        assertThat(PaymentReadDesk.disputeCursor(
                        OperatorCollection.cursor(PaymentReadDesk.DISPUTES, due.toString(), id.toString())))
                .contains(new PaymentCollections.DisputeCursor(Optional.of(due), id));
        assertThat(PaymentReadDesk.disputeCursor(
                        OperatorCollection.cursor(PaymentReadDesk.DISPUTES, PaymentReadDesk.UNSTATED, id.toString())))
                .contains(new PaymentCollections.DisputeCursor(Optional.empty(), id));
        assertRefused(() -> PaymentReadDesk.disputeCursor(
                OperatorCollection.cursor(PaymentReadDesk.DISPUTES, "soon", id.toString())));
    }

    @Test
    @DisplayName("the routing versions' cursor is a positive version number, and nothing else")
    void theVersionCursorIsANumber() {
        assertThat(RoutingPolicyOperations.versionCursor(OperatorCollection.cursor(RoutingPolicyOperations.VERSIONS, "7")))
                .contains(7);
        assertThat(RoutingPolicyOperations.versionCursor(null)).isEmpty();
        assertRefused(() -> RoutingPolicyOperations.versionCursor(
                OperatorCollection.cursor(RoutingPolicyOperations.VERSIONS, "seven")));
        assertRefused(() -> RoutingPolicyOperations.versionCursor(
                OperatorCollection.cursor(RoutingPolicyOperations.VERSIONS, "0")));
        assertRefused(() -> RoutingPolicyOperations.versionCursor(
                OperatorCollection.cursor(PaymentReadDesk.PAYMENTS, "7")));
    }

    private static void assertRefused(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }
}
