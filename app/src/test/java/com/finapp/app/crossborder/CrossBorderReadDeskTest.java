package com.finapp.app.crossborder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CorridorTerms;
import com.finapp.crossborder.CrossBorderCollections;
import com.finapp.crossborder.PaymentStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cross-border operator reads' requests and views, without a database (`P17-TSK-037`): a status the machine's own word, a
 * corridor {@code S-D-CC}, each instant strict, each collection's cursor its own; a payment's offer amounts at their stored
 * scales and a failure's reason only when it failed. The reads, their order, the permissions and the records are
 * {@code FxCrossBorderReadsDatabaseTest}'s.
 */
@DisplayName("the cross-border operator reads' requests (P17-TSK-037)")
class CrossBorderReadDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("a status and a corridor are the machine's own words; an instant is strict")
    void theFiltersAreTheDomainsWords() {
        assertThat(CrossBorderReadDesk.requestStatus("REJECTED"))
                .isEqualTo(CorridorAvailabilityStore.EnableRequestStatus.REJECTED);
        assertThat(CrossBorderReadDesk.paymentStatus("IN_TRANSIT")).isEqualTo(PaymentStore.Status.IN_TRANSIT);
        assertThat(CrossBorderReadDesk.corridor("EUR-USD-US")).isEqualTo(CorridorKey.parse("EUR-USD-US"));

        assertRefused(() -> CrossBorderReadDesk.requestStatus("PENDING"));
        assertRefused(() -> CrossBorderReadDesk.paymentStatus("SETTLED"));
        assertRefused(() -> CrossBorderReadDesk.corridor("EUR-USD"));
        assertRefused(() -> CrossBorderReadDesk.corridor("eur-usd-us"));
        assertRefused(() -> CrossBorderReadDesk.instant("to", null));
        assertRefused(() -> CrossBorderReadDesk.instant("to", "tomorrow"));
    }

    @Test
    @DisplayName("each collection's cursor is its own, exact to the microsecond, and refused anywhere else")
    void eachCursorIsItsOwn() {
        Instant at = Instant.parse("2026-11-20T10:15:30.123456Z");
        UUID id = IDS.next();
        String requests = OperatorCollection.cursor(CrossBorderReadDesk.CORRIDOR_ENABLE_REQUESTS, at.toString(), id.toString());
        String payments = OperatorCollection.cursor(CrossBorderReadDesk.PAYMENTS, at.toString(), id.toString());
        assertThat(CrossBorderReadDesk.requestCursor(requests)).contains(new CrossBorderCollections.CorridorRequestCursor(at, id));
        assertThat(CrossBorderReadDesk.paymentCursor(payments))
                .contains(new CrossBorderCollections.CrossBorderPaymentCursor(at, id));
        assertThat(CrossBorderReadDesk.paymentCursor(null)).isEmpty();

        assertRefused(() -> CrossBorderReadDesk.requestCursor(payments));
        assertRefused(() -> CrossBorderReadDesk.paymentCursor(requests));
        assertRefused(() -> CrossBorderReadDesk.paymentCursor(
                OperatorCollection.cursor(CrossBorderReadDesk.PAYMENTS, "now", id.toString())));
    }

    @Test
    @DisplayName("a payment reads its offer's amounts at their stored scales, and a failure's reason only when it failed")
    void theViewsAreTheRows() {
        CurrencyCode eur = CurrencyCode.of("EUR");
        CrossBorderReadDesk.CrossBorderPaymentItem paid = CrossBorderReadDesk.paymentItem(
                new CrossBorderCollections.CrossBorderPaymentHead(IDS.next(), IDS.next(), IDS.next(),
                        CorridorKey.parse("EUR-USD-US"), PaymentStore.Status.IN_TRANSIT, Optional.empty(),
                        Money.ofPersisted(100000, eur, 2), Money.ofPersisted(250, eur, 2), Money.ofPersisted(100250, eur, 2),
                        Money.ofPersisted(108502, CurrencyCode.of("USD"), 2), Instant.parse("2026-11-20T10:00:00Z")));
        assertThat(paid.sentAmount()).isEqualTo("1000.00");
        assertThat(paid.feeAmount()).isEqualTo("2.50");
        assertThat(paid.totalDebitAmount()).isEqualTo("1002.50");
        assertThat(paid.deliveredAmount()).isEqualTo("1085.02");
        assertThat(paid.sourceCurrency()).isEqualTo("EUR");
        assertThat(paid.destinationCurrency()).isEqualTo("USD");
        assertThat(paid.failureReason()).isNull();

        CrossBorderReadDesk.CorridorEnableRequestItem decided = CrossBorderReadDesk.requestItem(
                new CrossBorderCollections.CorridorRequestEntry(IDS.next(), CorridorKey.parse("GBP-USD-US"),
                        CorridorAvailabilityStore.EnableRequestStatus.APPROVED, "op-a", Instant.parse("2026-11-20T09:00:00Z"),
                        "recovered", Optional.of("op-b"), Optional.of(Instant.parse("2026-11-20T09:05:00Z")),
                        Optional.of("verified")));
        assertThat(decided.corridor()).isEqualTo("GBP-USD-US");
        assertThat(decided.decidedBy()).isEqualTo("op-b");
        assertThat(decided.decisionReason()).isEqualTo("verified");
    }

    @Test
    @DisplayName("a corridor never switched reads available with no controller, as the gate reads it; a switched one its latest"
            + " fact; its terms' rails, maximum and estimate")
    void aCorridorNeverSwitchedIsAvailable() {
        CurrencyCode eur = CurrencyCode.of("EUR");
        CorridorKey key = CorridorKey.parse("EUR-USD-US");
        CorridorTerms terms = new CorridorTerms(key, List.of("corridor-sim-a"), Money.ofPersisted(250, eur, 2),
                new BigDecimal("0.005"), RoundingPolicy.HALF_EVEN, Money.ofPersisted(1000000, CurrencyCode.of("USD"), 2),
                Duration.ofHours(24),
                Duration.ofHours(48), Set.of());
        CrossBorderReadDesk.CorridorAvailabilityItem never = CrossBorderReadDesk.corridorItem(terms, Optional.empty());
        assertThat(never.corridor()).isEqualTo("EUR-USD-US");
        assertThat(never.rails()).containsExactly("corridor-sim-a");
        assertThat(never.maximumAmount()).isEqualTo("10000.00");
        assertThat(never.maximumCurrency()).as("the maximum is the destination's").isEqualTo("USD");
        assertThat(never.deliveryEstimateHours()).isEqualTo(48);
        assertThat(never.available()).isTrue();
        assertThat(never.changedBy()).isNull();

        Instant at = Instant.parse("2026-11-20T08:00:00Z");
        CrossBorderReadDesk.CorridorAvailabilityItem stopped = CrossBorderReadDesk.corridorItem(terms,
                Optional.of(new CrossBorderCollections.CorridorFact(key, false, "op-a", "provider outage", at)));
        assertThat(stopped.available()).isFalse();
        assertThat(stopped.changedBy()).isEqualTo("op-a");
        assertThat(stopped.changedAt()).isEqualTo(at.toString());
        assertThat(stopped.reason()).isEqualTo("provider outage");
    }

    private static void assertRefused(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }
}
