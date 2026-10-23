package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The velocity windows against a real database (`P13-TSK-006`; {@code risk V005}; {@code PHASE_13_PLAN.md} section 7.4,
 * scenario 14; {@code INV-RSK-03}, {@code INV-CON-03}): ten concurrent movements of one subject count ten - on one
 * account, and on ten accounts of one party, where only R2a serialises them - each reading its predecessor's committed
 * figure; a refused or rolled-back movement counts nothing; the window is cut by the database clock and the database
 * refuses one cut by any other; overlapping windows upserted in sorted order never deadlock; the counters only move
 * forward, for every role. <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task;
 * the owner-authorised database pass before the exit review runs it).
 *
 * <p>Windows are never deleted, so every case keys its movements on fresh subjects and never assumes what an earlier
 * case left. Counts are asserted over the {@code D30} window, or summed over a subject's windows of a kind, so a case
 * that happens to straddle an hour's or a day's boundary still counts what it counted.
 */
@Tag("database")
@DisplayName("velocity windows: ten movements count ten, a refusal counts nothing, the database's clock cuts"
        + " (P13-TSK-006)")
class VelocityWindowDatabaseTest {

    private static final VelocityWindows WINDOWS = new VelocityWindows(new JdbcVelocityWindowStore());
    private static final VelocityKey PARTY_D30 = new VelocityKey(VelocityDimension.PARTY, WindowKind.D30);
    private static final VelocityKey SOURCE_D30 = new VelocityKey(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D30);

    @Test
    @DisplayName("ten concurrent movements from one account on ten connections count ten, and each read its"
            + " predecessor's committed figure - 0 to 9, no two alike (scenario 14)")
    void tenMovementsCountTen() throws Exception {
        UUID party = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        List<VelocityReading> seen = race(10, () -> inOneTransaction(uow -> {
            WindowCut cut = WINDOWS.cut(uow, VelocityMovement.of(eur(10_00)).party(party).sourceAccount(source).build());
            VelocityReading before = WINDOWS.read(uow, cut, Set.of(SOURCE_D30)).get(SOURCE_D30);
            WINDOWS.increment(uow, cut, RiskDecision.ALLOW, false);
            return before;
        }));
        assertThat(seen.stream().map(VelocityReading::count).sorted().toList())
                .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(total("SOURCE_ACCOUNT", source, "D1")).isEqualTo(new long[] {10, 100_00});
        assertThat(total("SOURCE_ACCOUNT", source, "H1")).isEqualTo(new long[] {10, 100_00});
        assertThat(total("PARTY", party, "D30")).isEqualTo(new long[] {10, 100_00});
    }

    @Test
    @DisplayName("ten concurrent movements of one party from ten accounts - ten different money locks - still count ten,"
            + " each reading its predecessor's figure: R2a, not the caller's lock, serialises the party (INV-CON-03)")
    void tenMovementsOfOnePartyFromTenAccountsCountTen() throws Exception {
        UUID party = UUID.randomUUID();
        List<VelocityReading> seen = race(10, () -> inOneTransaction(uow -> {
            WindowCut cut = WINDOWS.cut(uow,
                    VelocityMovement.of(eur(500_00)).party(party).sourceAccount(UUID.randomUUID()).build());
            VelocityReading before = WINDOWS.read(uow, cut, Set.of(PARTY_D30)).get(PARTY_D30);
            WINDOWS.increment(uow, cut, RiskDecision.ALLOW, false);
            return before;
        }));
        assertThat(seen.stream().map(VelocityReading::count).sorted().toList())
                .as("no two movements read the same figure: none could pass a threshold the other was about to cross")
                .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(total("PARTY", party, "D30")).isEqualTo(new long[] {10, 5_000_00});
    }

    @Test
    @DisplayName("a refused movement counts nothing, and a permitted one whose money transaction rolls back counts nothing"
            + " either")
    void aRefusedMovementCountsNothing() throws Exception {
        UUID party = UUID.randomUUID();
        for (RiskDecision refusal : List.of(RiskDecision.DENY, RiskDecision.HOLD, RiskDecision.REVIEW,
                RiskDecision.CHALLENGE)) {
            inOneTransaction(uow -> WINDOWS.increment(uow,
                    WINDOWS.cut(uow, VelocityMovement.of(eur(1_00)).party(party).build()), refusal, false));
        }
        try (Connection uow = DatabaseRoles.application()) {
            uow.setAutoCommit(false);
            WINDOWS.increment(uow, WINDOWS.cut(uow, VelocityMovement.of(eur(1_00)).party(party).build()),
                    RiskDecision.ALLOW, false);
            uow.rollback();
        }
        assertThat(count("SELECT count(*) FROM risk.velocity_window WHERE subject_ref = '" + party + "'")).isZero();

        inOneTransaction(uow -> WINDOWS.increment(uow,
                WINDOWS.cut(uow, VelocityMovement.of(eur(1_00)).party(party).build()), RiskDecision.ALLOW, false));
        assertThat(total("PARTY", party, "D30")).isEqualTo(new long[] {1, 1_00});
    }

    @Test
    @DisplayName("two instances cut one movement at the database's clock: the cut is clock_timestamp() on the caller's"
            + " connection after its locks, every start is WindowStarts' of it, and the stored row is that window")
    void aWindowIsCutByTheDatabaseClock() throws Exception {
        UUID party = UUID.randomUUID();
        for (int instance = 0; instance < 2; instance++) {
            inOneTransaction(uow -> {
                Instant before = databaseNow(uow);
                WindowCut cut = WINDOWS.cut(uow, VelocityMovement.of(eur(1_00)).party(party).build());
                Instant after = databaseNow(uow);
                assertThat(cut.at()).isBetween(before, after);
                List<VelocityWindow> counted = WINDOWS.increment(uow, cut, RiskDecision.ALLOW, false);
                assertThat(counted).allSatisfy(window -> {
                    assertThat(window.key().windowStart())
                            .isEqualTo(WindowStarts.start(window.key().windowKind(), cut.at()));
                    assertThat(window.key().contains(window.lastCountedAt())).isTrue();
                });
                return counted;
            });
        }
        assertThat(total("PARTY", party, "D30")).isEqualTo(new long[] {2, 2_00});
    }

    @Test
    @DisplayName("the database refuses a window cut by any other clock: a start ahead of its own clock (an instance"
            + " running fast), or a window ended more than a minute ago (an instance running slow)")
    void theDatabaseRefusesAForeignClock() throws Exception {
        UUID party = UUID.randomUUID();
        JdbcVelocityWindowStore store = new JdbcVelocityWindowStore();
        Instant now = inOneTransaction(VelocityWindowDatabaseTest::databaseNow);
        for (Instant foreign : List.of(WindowStarts.start(WindowKind.H1, now).plusSeconds(3600),
                WindowStarts.start(WindowKind.H1, now).minusSeconds(2 * 3600))) {
            VelocityWindowKey key = new VelocityWindowKey(VelocityDimension.PARTY, party.toString(), WindowKind.H1,
                    foreign);
            assertThatThrownBy(() -> inOneTransaction(uow -> store.upsert(uow, key, eur(1_00))))
                    .isInstanceOf(RiskStorageException.class)
                    .hasMessageContaining("SQLSTATE 23514");
        }
        assertThat(count("SELECT count(*) FROM risk.velocity_window WHERE subject_ref = '" + party + "'")).isZero();
    }

    @Test
    @DisplayName("WindowStarts and the database's date_bin agree on every kind's start for any instant")
    void windowStartsAgreeWithSql() throws Exception {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement select = migrator.prepareStatement("SELECT date_bin(risk.velocity_window_length(?),"
                        + " ?::timestamptz, TIMESTAMPTZ '1970-01-05 00:00:00+00')")) {
            for (String at : List.of("2026-10-23T14:37:12.345678Z", "2026-10-24T00:00:00Z", "2026-10-23T23:59:59.999999Z",
                    "2031-03-09T12:00:00Z", "2027-02-28T23:00:00Z", "1999-12-31T23:59:59Z")) {
                for (WindowKind kind : WindowKind.values()) {
                    select.setString(1, kind.name());
                    select.setString(2, at);
                    try (ResultSet row = select.executeQuery()) {
                        row.next();
                        assertThat(row.getObject(1, OffsetDateTime.class).toInstant()).as(kind + " at " + at)
                                .isEqualTo(WindowStarts.start(kind, Instant.parse(at)));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("overlapping windows of many subjects upserted by ten concurrent movements in sorted order never"
            + " deadlock, and every one is counted")
    void overlappingWindowsNeverDeadlock() throws Exception {
        UUID party = UUID.randomUUID();
        List<UUID> merchants = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<DeviceHash> devices = List.of(DeviceHash.of("device one " + party), DeviceHash.of("device two " + party));
        AtomicInteger next = new AtomicInteger();
        List<Integer> done = race(10, () -> {
            int i = next.getAndIncrement();
            return inOneTransaction(uow -> {
                VelocityMovement movement = VelocityMovement.of(eur(1_00)).party(party)
                        .merchant(merchants.get(i % 2)).device(devices.get((i / 2) % 2))
                        .destinationAccount(merchants.get((i + 1) % 2)).build();
                WINDOWS.increment(uow, WINDOWS.cut(uow, movement), RiskDecision.ALLOW, false);
                return i;
            });
        });
        assertThat(done).hasSize(10);
        assertThat(total("PARTY", party, "D30")).isEqualTo(new long[] {10, 10_00});
        assertThat(total("MERCHANT", merchants.get(0), "D30")[0] + total("MERCHANT", merchants.get(1), "D30")[0])
                .isEqualTo(10);
    }

    @Test
    @DisplayName("the counters only move forward, for every role: the owner's decrement, double count, sum decrease,"
            + " re-keying, DELETE and TRUNCATE refused by the trigger; the application's every UPDATE but the two"
            + " counters, and its DELETE, refused by privilege")
    void theSumNeverDecreases() throws Exception {
        UUID party = UUID.randomUUID();
        inOneTransaction(uow -> WINDOWS.increment(uow,
                WINDOWS.cut(uow, VelocityMovement.of(eur(5_00)).party(party).build()), RiskDecision.ALLOW, false));
        String row = " FROM risk.velocity_window WHERE subject_ref = '" + party + "' AND window_kind = 'D30'";
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            for (String edit : List.of(
                    "UPDATE risk.velocity_window SET count = count - 1 WHERE subject_ref = '" + party + "'",
                    "UPDATE risk.velocity_window SET count = count + 2 WHERE subject_ref = '" + party + "'",
                    "UPDATE risk.velocity_window SET count = count + 1, sum_minor = sum_minor - 1 WHERE subject_ref = '"
                            + party + "'",
                    "UPDATE risk.velocity_window SET count = count + 1, currency = 'USD' WHERE subject_ref = '" + party + "'",
                    "UPDATE risk.velocity_window SET count = count + 1, window_start = window_start - interval '30 days'"
                            + " WHERE subject_ref = '" + party + "' AND window_kind = 'D30'",
                    "DELETE" + row,
                    "TRUNCATE risk.velocity_window")) {
                refused(owner, edit, "23514");
                owner.rollback();
            }
        }
        try (Connection application = DatabaseRoles.application()) {
            application.setAutoCommit(false);
            for (String edit : List.of(
                    "UPDATE risk.velocity_window SET currency = 'USD' WHERE subject_ref = '" + party + "'",
                    "UPDATE risk.velocity_window SET last_counted_at = clock_timestamp() WHERE subject_ref = '" + party + "'",
                    "DELETE" + row)) {
                refused(application, edit, "42501");
                application.rollback();
            }
            refused(application, "INSERT INTO risk.velocity_window (dimension, subject_ref, window_kind, window_start,"
                    + " count, sum_minor, currency, scale) VALUES ('PARTY', '" + UUID.randomUUID() + "', 'D30',"
                    + " date_bin(interval '30 days', clock_timestamp(), TIMESTAMPTZ '1970-01-05 00:00:00+00'), 5, 1,"
                    + " 'EUR', 2)", "23514");
            application.rollback();
        }
        assertThat(total("PARTY", party, "D30")).isEqualTo(new long[] {1, 5_00});
    }

    @Test
    @DisplayName("the parameter table holds the velocity dimension: value_subject_kind renamed, a dimension outside"
            + " VelocityDimension refused by the re-made CHECK")
    void theParameterTableHoldsTheDimension() throws Exception {
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'risk'"
                + " AND table_name = 'risk_rule_parameter' AND column_name = 'value_velocity_dimension'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'risk'"
                + " AND table_name = 'risk_rule_parameter' AND column_name = 'value_subject_kind'")).isZero();
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = 'risk_rule_parameter_value_typed'"
                + " AND pg_get_constraintdef(oid) LIKE '%SOURCE_ACCOUNT%' AND pg_get_constraintdef(oid) NOT LIKE"
                + " '%''ACCOUNT''%'")).isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, RiskPolicyFixtures.EUR);
    }

    /** The count and the sum of every window of one subject's kind - across any boundary the case straddled. */
    private static long[] total(String dimension, UUID subject, String kind) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery("SELECT COALESCE(sum(count), 0), COALESCE(sum(sum_minor), 0)"
                        + " FROM risk.velocity_window WHERE dimension = '" + dimension + "' AND subject_ref = '"
                        + subject + "' AND window_kind = '" + kind + "'")) {
            row.next();
            return new long[] {row.getLong(1), row.getLong(2)};
        }
    }

    private static Instant databaseNow(Connection uow) {
        try (Statement statement = uow.createStatement();
                ResultSet row = statement.executeQuery("SELECT clock_timestamp()")) {
            row.next();
            return row.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
