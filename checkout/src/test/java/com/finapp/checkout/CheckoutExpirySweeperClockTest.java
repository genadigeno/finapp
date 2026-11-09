package com.finapp.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweep judges and stamps on the database's clock (`P15-TSK-038`, {@code X-TSK-008}'s checkout store).
 *
 * <p>The sweeper holds no {@code Clock}; every instant here is what its store answered. The database cases, the deadline
 * crossed on a moved database clock, are {@code CheckoutExpiryDatabaseTest}'s and owed (C14).
 */
@DisplayName("the checkout expiry sweep is on the database's clock (P15-TSK-038)")
class CheckoutExpirySweeperClockTest {

    private static final Instant OPENED = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant DEADLINE = OPENED.plus(Duration.ofMinutes(30));
    private static final Duration GRACE = Duration.ofMinutes(10);
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(OPENED, ZoneOffset.UTC), new SecureRandom());
    private static final Money AMOUNT = Money.ofMinorUnits(100_00L, CurrencyCode.of("EUR"));

    private final List<String> events = new ArrayList<>();
    private final List<AuditRecord> audited = new ArrayList<>();
    private Instant databaseClock;
    private CheckoutSession stored;
    private CheckoutSession moved;

    @Test
    @DisplayName("the candidates are the statement's, and the reading the row is judged on is taken after its lock")
    void theReadingFollowsTheLock() {
        stored = open();
        databaseClock = DEADLINE.plusSeconds(1);

        CheckoutExpirySweeper.SweepResult result = sweeper().sweep();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(events)
                .as("no instant crosses to the list, and a waiting lock cannot stale the reading")
                .containsExactly("list", "lock", "clock", "move");
    }

    @Test
    @DisplayName("the row, the audit record and the event carry the one reading the deadline was judged on")
    void theAnswerAgrees() {
        stored = open();
        databaseClock = DEADLINE.plusSeconds(1);

        sweeper().sweep();

        assertThat(moved.statusChangedAt()).as("stamped from the reading").isEqualTo(databaseClock);
        assertThat(audited).singleElement().satisfies(
                record -> assertThat(record.occurredAt()).as("the record is the reading, no second clock").isEqualTo(databaseClock));
    }

    @Test
    @DisplayName("a row the database says is live is left alone, whatever the list once said")
    void aRowTheDatabaseSaysIsLiveIsLeftAlone() {
        stored = open();
        databaseClock = DEADLINE.minusNanos(1_000);

        CheckoutExpirySweeper.SweepResult result = sweeper().sweep();

        assertThat(result.expired()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(events).doesNotContain("move");
    }

    @Test
    @DisplayName("the payment's grace is judged on the same reading: exclusive of its end, and not a moment before it")
    void theGraceIsJudgedOnTheDatabasesClock() {
        stored = open().confirm(OPENED.plusSeconds(1), UUID.randomUUID());

        databaseClock = DEADLINE.plus(GRACE).minusNanos(1_000);
        assertThat(sweeper().sweep().expired()).as("inside the grace").isZero();

        events.clear();
        databaseClock = DEADLINE.plus(GRACE);
        assertThat(sweeper().sweep().expired()).as("at the end of the grace").isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private CheckoutExpirySweeper sweeper() {
        return new CheckoutExpirySweeper(
                new CheckoutTransactionRunner() {
                    @Override
                    public <R> R inTransaction(Function<Connection, R> work) {
                        return work.apply(proxy(Connection.class, (method, arguments) -> null));
                    }
                },
                sessions(),
                audit(),
                outbox(),
                IDS,
                GRACE,
                50,
                UndispatchedPayments.none());
    }

    private static CheckoutSession open() {
        return CheckoutSession.open(
                IDS, OPENED, UUID.randomUUID(), AMOUNT, "One coffee", UUID.randomUUID(),
                CheckoutSessionToken.issue(new SecureRandom()), DEADLINE);
    }

    @SuppressWarnings("unchecked")
    private CheckoutSessionStore<Connection> sessions() {
        return proxy(CheckoutSessionStore.class, (method, arguments) -> switch (method) {
            case "findExpirable" -> {
                events.add("list");
                yield List.of(stored);
            }
            case "findByIdForUpdate" -> {
                events.add("lock");
                yield Optional.of(stored);
            }
            case "databaseNow" -> {
                events.add("clock");
                yield databaseClock;
            }
            case "transition" -> {
                events.add("move");
                moved = (CheckoutSession) arguments[2];
                yield true;
            }
            default -> throw new AssertionError("the sweep has no business with " + method);
        });
    }

    @SuppressWarnings("unchecked")
    private AuditWriter<Connection> audit() {
        return proxy(AuditWriter.class, (method, arguments) -> {
            audited.add((AuditRecord) arguments[1]);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private OutboxWriter<Connection> outbox() {
        return proxy(OutboxWriter.class, (method, arguments) -> null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?> type, BiFunction<String, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (self, method, arguments) -> answer.apply(method.getName(), arguments));
    }
}
