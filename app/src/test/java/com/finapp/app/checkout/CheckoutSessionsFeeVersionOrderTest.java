package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.telemetry.CheckoutMeters;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.OrderStore;
import com.finapp.merchant.FeeScheduleStore;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PI-002's order (`P15-TSK-038`): the card fee version in force is resolved <strong>after</strong> the merchant's share lock,
 * by a statement that takes no instant, as {@code BnplPricing} resolves the BNPL version. A resolution before the lock, or
 * handed this instance's clock, prices a session by a version a suspension or a clock step had already moved past.
 *
 * <p>The order is the behaviour itself, read through the real {@link CheckoutSessions#open} with its stores answering: what
 * the statement does on PostgreSQL is {@code StoredBoundsClockSkewDatabaseTest}'s, owed (C14).
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the card fee version is resolved after the merchant's share lock (P15-TSK-038, PI-002)")
class CheckoutSessionsFeeVersionOrderTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final List<String> events = new ArrayList<>();
    private final Merchant trading = Merchant.onboard(IDS, CLOCK, IDS.next(), "Shop GmbH", "Shop", EUR);

    @Test
    @DisplayName("the merchant's row is share-locked first, then the version in force is read, and no instant crosses")
    void theVersionIsResolvedAfterTheShareLock() {
        CheckoutSessions.OpenSessionCommand command =
                new CheckoutSessions.OpenSessionCommand(
                        "a-key", trading.id(), MerchantApiKeyId.of(IDS.next()), Money.ofMinorUnits(10_00L, EUR), "One lamp");

        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
                SecurityContext.Scope actor =
                        SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER))) {
            assertThatThrownBy(() -> sessions().open(connection(), command))
                    .as("no version in force stops the open before anything is written, which ends this test's interest")
                    .isInstanceOf(MerchantNotPriceableException.class);
        }

        assertThat(events).as("a resolution before the lock could price by a version a suspension has passed")
                .containsExactly("share-lock", "resolve");
    }

    // -----------------------------------------------------------------

    private CheckoutSessions sessions() {
        return new CheckoutSessions(
                proxy(CheckoutSessionStore.class, (method, arguments) -> {
                    throw new AssertionError("nothing is stored before the version is resolved: " + method);
                }),
                proxy(OrderStore.class, (method, arguments) -> {
                    throw new AssertionError("no order here: " + method);
                }),
                merchants(),
                new FeeSchedules(feeStore(), merchants(), audit(), IDS, CLOCK),
                new IdempotentExecutor(proxy(IdempotencyRecordStore.class, (method, arguments) -> {
                    throw new AssertionError("no claim before the version is resolved: " + method);
                }), CLOCK, Duration.ofDays(1), Duration.ofMinutes(1)),
                audit(),
                proxy(OutboxWriter.class, (method, arguments) -> null),
                IDS,
                CLOCK,
                new SecureRandom(),
                new CheckoutMeters(new SimpleMeterRegistry()));
    }

    private MerchantStore<Connection> merchants() {
        return proxy(MerchantStore.class, (method, arguments) -> {
            if (!method.equals("findByIdForShare")) {
                throw new AssertionError("the door shares the merchant's row and nothing else: " + method);
            }
            events.add("share-lock");
            assertThat(arguments[1]).isEqualTo(trading.id());
            return Optional.of(trading);
        });
    }

    private FeeScheduleStore<Connection> feeStore() {
        return proxy(FeeScheduleStore.class, (method, arguments) -> {
            if (!method.equals("findEffectiveVersionFor")) {
                throw new AssertionError("the open resolves the version in force and nothing else: " + method);
            }
            events.add("resolve");
            return Optional.empty();
        });
    }

    private AuditWriter<Connection> audit() {
        return proxy(AuditWriter.class, (method, arguments) -> null);
    }

    private static Connection connection() {
        return proxy(Connection.class, (method, arguments) -> null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?> type, BiFunction<String, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (self, method, arguments) -> answer.apply(method.getName(), arguments));
    }
}
