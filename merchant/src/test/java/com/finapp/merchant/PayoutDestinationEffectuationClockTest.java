package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
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
 * The effectuation sweep judges and stamps a cooling-off on the database's clock (`P15-TSK-038`, a security control:
 * {@code X-TSK-008}'s payout-destination store).
 *
 * <p>The sweep holds no {@code Clock}, so a skewed instance can neither shorten nor extend a cooling-off. The database half,
 * the deadline crossed on a moved database clock, is {@code PayoutDestinationDatabaseTest}'s and owed (C14).
 */
@DisplayName("payout destination effectuation is on the database's clock (P15-TSK-038)")
class PayoutDestinationEffectuationClockTest {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");
    private static final Duration COOLING_OFF = Duration.ofHours(72);
    private static final Instant DEADLINE = T0.plus(COOLING_OFF);
    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(T0, ZoneOffset.UTC), new SecureRandom());
    private static final MerchantId MERCHANT = MerchantId.of(UUID.randomUUID());

    private final List<String> events = new ArrayList<>();
    private final List<PayoutDestination> moves = new ArrayList<>();
    private final List<AuditRecord> audited = new ArrayList<>();
    private Instant databaseClock;
    private PayoutDestination approved;
    private Optional<PayoutDestination> effective = Optional.empty();

    @Test
    @DisplayName("the list binds no instant, and the reading the row is judged on is taken after the row's lock")
    void theReadingFollowsTheLock() {
        approved = approved();
        databaseClock = DEADLINE;

        PayoutDestinationEffectuation.SweepResult result = sweep();

        assertThat(result.effected()).isEqualTo(1);
        assertThat(events).as("a waiting lock cannot stale the reading")
                .containsExactly("list", "lock", "clock", "lockEffective", "move");
    }

    @Test
    @DisplayName("a cooling-off the database says is running is left running, whatever the list once said")
    void aRunningCoolingOffIsLeftRunning() {
        approved = approved();
        databaseClock = DEADLINE.minusNanos(1_000);

        PayoutDestinationEffectuation.SweepResult result = sweep();

        assertThat(result.effected()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(events).doesNotContain("move");
    }

    @Test
    @DisplayName("the effect, the supersession and the audit record carry the one reading the deadline was judged on")
    void theAnswerAgrees() {
        approved = approved();
        effective = Optional.of(earlierEffective());
        databaseClock = DEADLINE.plusSeconds(5);

        sweep();

        assertThat(moves).extracting(PayoutDestination::status)
                .containsExactly(PayoutDestinationStatus.SUPERSEDED, PayoutDestinationStatus.EFFECTIVE);
        assertThat(moves.get(0).supersededAt()).as("superseded at the reading").contains(databaseClock);
        assertThat(moves.get(1).effectiveAt()).as("effective at the reading").contains(databaseClock);
        assertThat(audited).singleElement().satisfies(
                record -> assertThat(record.occurredAt()).as("no second clock in one operation").isEqualTo(databaseClock));
    }

    // -----------------------------------------------------------------

    private PayoutDestinationEffectuation.SweepResult sweep() {
        return new PayoutDestinationEffectuation(
                        new MerchantTransactionRunner() {
                            @Override
                            public <R> R inTransaction(Function<Connection, R> work) {
                                return work.apply(proxy(Connection.class, (method, arguments) -> null));
                            }
                        },
                        destinations(),
                        audit(),
                        IDS,
                        10)
                .sweep();
    }

    private static PayoutDestination approved() {
        return proposed("the merchant's new account").approve("anna", T0, COOLING_OFF);
    }

    private static PayoutDestination earlierEffective() {
        return proposed("the merchant's account").approve("anna", T0.minus(Duration.ofDays(10)), COOLING_OFF)
                .effect(T0.minus(Duration.ofDays(1)));
    }

    private static PayoutDestination proposed(String reason) {
        return PayoutDestination.propose(
                IDS, Clock.fixed(T0.minus(Duration.ofDays(20)), ZoneOffset.UTC), MERCHANT,
                new PayoutDestinationReference(com.finapp.sharedkernel.security.Sensitive.of("pdr_clock")), "3000", "boris",
                reason);
    }

    @SuppressWarnings("unchecked")
    private PayoutDestinationStore<Connection> destinations() {
        return proxy(PayoutDestinationStore.class, (method, arguments) -> switch (method) {
            case "findDue" -> {
                events.add("list");
                yield List.of(approved);
            }
            case "findForUpdate" -> {
                events.add("lock");
                yield Optional.of(approved);
            }
            case "databaseNow" -> {
                events.add("clock");
                yield databaseClock;
            }
            case "findEffectiveForUpdate" -> {
                events.add("lockEffective");
                yield effective;
            }
            case "transition" -> {
                events.add("move");
                moves.add((PayoutDestination) arguments[2]);
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
    private static <T> T proxy(Class<?> type, BiFunction<String, Object[], Object> answer) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (self, method, arguments) -> answer.apply(method.getName(), arguments));
    }
}
