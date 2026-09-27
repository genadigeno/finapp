package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispute command's capability gate (`P7-TSK-012`, ADR-0061 §8, {@code INV-RAIL-01}):
 * a rail declaring no chargebacks has no dispute to record, judged from the DECLARATION before
 * anything is read or written — every collaborator here fails the test if it is touched.
 *
 * <p>Hermetic because the card door cannot reach it: the door attributes by OUR card operation
 * references, which no push or book attempt carries. The gate is defence in depth for the day a
 * second disputable-looking rail arrives, and this is its only rank.
 */
@DisplayName("dispute notifications: the capability gate (P7-TSK-012)")
class DisputeNotificationsTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("a book-rail attempt is NOT_DISPUTABLE from its declaration alone, and nothing is"
            + " read, written, audited or announced")
    @SuppressWarnings("unchecked") // The untouchable fakes are raw proxies by construction.
    void aRailWithoutChargebacksHasNothingToRecord() {
        DisputeNotifications disputes =
                new DisputeNotifications(
                        untouchable(DisputeStore.class),
                        untouchable(PaymentIntentStore.class),
                        PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL, BookRail.RAIL)),
                        untouchable(AuditWriter.class),
                        untouchable(OutboxWriter.class),
                        IDS,
                        CLOCK);
        PaymentAttempt book =
                PaymentAttempt.createBook(
                        IDS, CLOCK, PaymentIntentId.of(IDS.next()), BookRail.RAIL.id());

        DisputeNotifications.Outcome outcome =
                disputes.apply(
                        untouchable(Connection.class),
                        book,
                        new DisputeNotice(
                                "simulated-card",
                                new ProviderReference("dp_book_1"),
                                DisputeStage.CHARGED_BACK,
                                DisputeReason.FRAUD,
                                Money.ofMinorUnits(1000, CurrencyCode.of("EUR"))),
                        Correlation.startingWith(CorrelationId.generate(IDS)));

        assertThat(outcome).isEqualTo(DisputeNotifications.Outcome.NOT_DISPUTABLE);
        assertThat(outcome.isContradiction())
                .as("loud: a rail without chargebacks being disputed is an integration break")
                .isTrue();
    }

    @Test
    @DisplayName("the outcomes the operator must see are exactly the three contradictions")
    void theContradictionsAreExactlyThree() {
        for (DisputeNotifications.Outcome outcome : DisputeNotifications.Outcome.values()) {
            assertThat(outcome.isContradiction())
                    .as("%s", outcome)
                    .isEqualTo(
                            outcome == DisputeNotifications.Outcome.STAGE_CONTRADICTED
                                    || outcome == DisputeNotifications.Outcome.FACTS_CONTRADICTED
                                    || outcome == DisputeNotifications.Outcome.NOT_DISPUTABLE);
        }
    }

    /** A collaborator that fails the test the moment anything calls it. */
    @SuppressWarnings("unchecked")
    private static <T> T untouchable(Class<T> type) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("toString")) {
                                return "untouchable " + type.getSimpleName();
                            }
                            throw new AssertionError(
                                    type.getSimpleName() + "." + method.getName()
                                            + " was reached: the gate must refuse first");
                        });
    }
}
