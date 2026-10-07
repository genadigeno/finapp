package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One fetch round's outcomes, hermetically (`P9-TSK-005`): the permit gates the wire, a failed
 * source is a value, and every row is counted - stored, not newer, or rejected by the adapter,
 * the declaration or the database.
 */
@DisplayName("one reference fetch round: paced, failed, and every row counted (P9-TSK-005)")
class ReferenceRateFetchTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Instant T = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    @DisplayName("without the permit nothing is fetched: the round is paced")
    void withoutThePermitNothingIsFetched() {
        AtomicInteger fetches = new AtomicInteger();
        RateSource counting =
                source(
                        () -> {
                            fetches.incrementAndGet();
                            return new RateSource.Fetched.Rates(List.of(), 0);
                        });
        ReferenceRateFetch.Result result =
                fetch(counting, new RecordingStore(), (uow, source, window) -> false).fetchOnce();
        assertThat(result.paced()).isTrue();
        assertThat(fetches).hasValue(0);
    }

    @Test
    @DisplayName("a failed source is a value, and stores nothing")
    void aFailedSourceIsAValue() {
        RecordingStore store = new RecordingStore();
        ReferenceRateFetch.Result result =
                fetch(
                                source(
                                        () ->
                                                new RateSource.Fetched.Failed(
                                                        RateSource.FetchFailure.TIMEOUT)),
                                store,
                                (uow, source, window) -> true)
                        .fetchOnce();
        assertThat(result.failure()).contains(RateSource.FetchFailure.TIMEOUT);
        assertThat(store.recorded).isEmpty();
    }

    @Test
    @DisplayName("every row is counted: stored, not newer, refused by the database, an undeclared"
            + " pair, and the adapter's own rejections")
    void everyRowIsCounted() {
        RecordingStore store = new RecordingStore();
        store.answers.add(RateSnapshotStore.Recorded.STORED);
        store.answers.add(RateSnapshotStore.Recorded.NOT_NEWER);
        store.refuseThird = true;
        List<RateObservation> rows =
                List.of(
                        observation("EUR", "USD"),
                        observation("EUR", "GBP"),
                        observation("USD", "JPY"),
                        // The inverse direction: never declared, never offered to the store.
                        observation("USD", "EUR"));
        ReferenceRateFetch.Result result =
                fetch(source(() -> new RateSource.Fetched.Rates(rows, 2)), store,
                                (uow, source, window) -> true)
                        .fetchOnce();
        assertThat(result.paced()).isFalse();
        assertThat(result.failure()).isEmpty();
        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.notNewer()).isEqualTo(1);
        assertThat(result.rejected())
                .as("two from the adapter, one refused by the database, one undeclared")
                .isEqualTo(4);
        assertThat(store.recorded)
                .extracting(RateObservation::pair)
                .doesNotContain(ReferencePair.of("USD", "EUR"));
    }

    @Test
    @DisplayName("a skewed instance has nothing to skew: the freshness read, the permit and the"
            + " round take no Clock - the database's statement_timestamp() judges both windows")
    void noInstanceClockTakesPart() {
        // The backlog's "staleness on the database clock with a skewed instance", held
        // structurally (P9-TSK-005's gate): an instance's clock can only enter a decision through
        // a Clock (or an Instant it computed) handed to one of these, and none accepts one. The
        // database tier proves the SQL judges on statement_timestamp() (RateSnapshotDatabaseTest,
        // RateFetchPermitDatabaseTest); this proves no Java path could override it.
        for (Class<?> decides :
                List.of(
                        JdbcRateSnapshotStore.class,
                        JdbcRateFetchPermitStore.class,
                        ReferenceRateFetch.class)) {
            List<Class<?>> fieldTypes = new ArrayList<>();
            for (java.lang.reflect.Field field : decides.getDeclaredFields()) {
                fieldTypes.add(field.getType());
            }
            List<Class<?>> parameterTypes = new ArrayList<>();
            for (java.lang.reflect.Constructor<?> constructor : decides.getDeclaredConstructors()) {
                parameterTypes.addAll(List.of(constructor.getParameterTypes()));
            }
            assertThat(fieldTypes)
                    .as("%s's fields", decides.getSimpleName())
                    .doesNotContain(Clock.class, Instant.class);
            assertThat(parameterTypes)
                    .as("%s's constructors", decides.getSimpleName())
                    .doesNotContain(Clock.class, Instant.class);
        }
        for (java.lang.reflect.Method method : RateSnapshotStore.class.getMethods()) {
            if (method.getName().equals("freshLatest") || method.getName().equals("age")) {
                assertThat(method.getParameterTypes())
                        .as("%s takes the age to judge, never the instant to judge it at", method)
                        .doesNotContain(Clock.class, Instant.class);
            }
        }
        for (java.lang.reflect.Method method : RateFetchPermitStore.class.getMethods()) {
            assertThat(method.getParameterTypes()).doesNotContain(Clock.class, Instant.class);
        }
    }

    @Test
    @DisplayName("a negative window is refused at construction")
    void aNegativeWindowIsRefused() {
        assertThatThrownBy(
                        () ->
                                new ReferenceRateFetch(
                                        source(() -> new RateSource.Fetched.Rates(List.of(), 0)),
                                        new RecordingStore(),
                                        (uow, source, window) -> true,
                                        ReferenceRateFetchTest::noTransaction,
                                        IDS,
                                        Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static ReferenceRateFetch fetch(
            RateSource source, RateSnapshotStore<Connection> store, RateFetchPermitStore<Connection> permits) {
        return new ReferenceRateFetch(
                source, store, permits, ReferenceRateFetchTest::noTransaction, IDS, Duration.ZERO);
    }

    private static <R> R noTransaction(java.util.function.Function<Connection, R> work) {
        return work.apply(null);
    }

    private static RateSource source(java.util.function.Supplier<RateSource.Fetched> answer) {
        return new RateSource() {
            @Override
            public String sourceCode() {
                return ReferenceSourceDeclaration.SOURCE;
            }

            @Override
            public Fetched fetch() {
                return answer.get();
            }
        };
    }

    private static RateObservation observation(String base, String quote) {
        return new RateObservation(
                ExchangeRate.of(CurrencyCode.of(base), CurrencyCode.of(quote), new BigDecimal("1.1")),
                T);
    }

    /** Answers in order; the third call is refused as the database would refuse it. */
    private static final class RecordingStore implements RateSnapshotStore<Connection> {
        final List<RateObservation> recorded = new ArrayList<>();
        final List<Recorded> answers = new ArrayList<>();
        boolean refuseThird;

        @Override
        public Recorded record(
                Connection unitOfWork, String source, RateObservation observation, UUID id) {
            recorded.add(observation);
            if (refuseThird && recorded.size() == 3) {
                throw new ObservationRefusedException("refused");
            }
            return answers.isEmpty() ? Recorded.STORED : answers.remove(0);
        }

        @Override
        public Optional<RateSnapshot> latest(Connection unitOfWork, String source, ReferencePair pair) {
            return Optional.empty();
        }

        @Override
        public Optional<RateSnapshot> freshLatest(
                Connection unitOfWork, String source, ReferencePair pair, Duration maxAge) {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> age(Connection unitOfWork, String source, ReferencePair pair) {
            return Optional.empty();
        }
    }
}
