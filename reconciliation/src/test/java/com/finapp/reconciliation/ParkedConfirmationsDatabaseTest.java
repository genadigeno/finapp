package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A parking's owner against the real schema (`P8-TSK-020`, ADR-0070 §2's
 * {@code UNMATCHED_CONFIRMATION} row, reconciliation `V011`): the CREDIT suspense item and the
 * break standing on it are born together in one transaction through the one deferred key; a
 * second opener of the same parking converges; ten racing openers leave ONE item, ONE break and
 * ONE announcement, the losers' breaks discarded with their savepoints; a parking whose scheme
 * execution a credit already explains is owned as the duplicate it is; and the deferral is the
 * opener's alone — every other writer still meets the subject key at its own statement.
 *
 * <p>The shared connection rolls back at the end; the race commits deliberately — the module's
 * container is this invocation's own.
 */
@Tag("database")
@DisplayName("a parking's owner: born together, converged, raced (P8-TSK-020)")
class ParkedConfirmationsDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final LocalDate PARKED_ON = LocalDate.parse("2026-06-15");
    private static final int RACERS = 10;

    private static Connection application;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    private static ParkedConfirmations opener() {
        return new ParkedConfirmations(
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS), IDS);
    }

    private static ParkedConfirmations.Opening parking(
            UUID parkingId, UUID entryId, BreakCause cause,
            InternalClassification classification, Optional<String> operationRef) {
        return new ParkedConfirmations.Opening(
                parkingId, SOURCE, RULE_SET, SuspenseSide.CREDIT, Money.ofPersisted(42_50, EUR, 2),
                PARKED_ON, entryId, cause, classification, operationRef, "UNATTRIBUTED",
                PLATFORM, CLOCK.instant(), CorrelationId.of("p8t20-" + parkingId));
    }

    @Test
    @DisplayName("born together: the CREDIT item carries the parking's value, date and entry,"
            + " and its owner is an UNKNOWN_EXTERNAL break standing on the item itself - raised,"
            + " audited and announced once - and a second opener converges on both")
    void theItemAndItsOwnerAreBornTogether() throws SQLException {
        UUID parkingId = IDS.next();
        UUID entryId = IDS.next();
        ParkedConfirmations.Opened opened =
                opener().open(
                        application,
                        parking(parkingId, entryId, BreakCause.PARKED_ON_RECEIPT,
                                InternalClassification.UNKNOWN, Optional.empty()));

        assertThat(opened.created()).isTrue();
        assertThat(text(application, "SELECT origin || '|' || origin_ref || '|' || side || '|'"
                        + " || amount_minor || '|' || currency || '|' || scale || '|'"
                        + " || released_minor || '|' || status || '|' || opened_on || '|'"
                        + " || entry_id || '|' || break_id FROM reconciliation.suspense_item"
                        + " WHERE id = ?", opened.suspenseItemId()))
                .isEqualTo("UNMATCHED_CONFIRMATION|" + parkingId + "|CREDIT|4250|EUR|2|0|OPEN|"
                        + PARKED_ON + "|" + entryId + "|" + opened.breakId());
        assertThat(count(application, "SELECT count(*) FROM reconciliation.suspense_item WHERE"
                        + " id = ? AND external_item_id IS NULL AND park_id IS NULL AND"
                        + " position_account_id IS NULL", opened.suspenseItemId()))
                .as("no external item, park or position: payments' entry holds the value")
                .isEqualTo(1);
        assertThat(text(application, "SELECT type || '|' || cause || '|' || status || '|'"
                        + " || suspense_item_id || '|' || value_at_issue_minor || '|'"
                        + " || internal_classification || '|' || internal_state || '|'"
                        + " || source_id || '|' || rule_set_id"
                        + " FROM reconciliation.break WHERE id = ?", opened.breakId()))
                .isEqualTo("UNKNOWN_EXTERNAL|PARKED_ON_RECEIPT|OPEN|" + opened.suspenseItemId()
                        + "|4250|UNKNOWN|UNATTRIBUTED|" + SOURCE + "|" + RULE_SET);
        assertThat(count(application, "SELECT count(*) FROM reconciliation.break WHERE id = ?"
                        + " AND expectation_id IS NULL AND external_item_id IS NULL"
                        + " AND run_id IS NULL AND decision_id IS NULL", opened.breakId()))
                .as("the item is the owner's one subject")
                .isEqualTo(1);
        assertThat(announcements(application, opened.breakId())).isEqualTo(1);

        ParkedConfirmations.Opened again =
                opener().open(
                        application,
                        parking(parkingId, IDS.next(), BreakCause.PARKED_ON_RECEIPT,
                                InternalClassification.UNKNOWN, Optional.empty()));
        assertThat(again)
                .as("the origin converges: the same item and owner, nothing written")
                .isEqualTo(new ParkedConfirmations.Opened(
                        false, opened.suspenseItemId(), opened.breakId()));
        assertThat(count(application, "SELECT count(*) FROM reconciliation.suspense_item WHERE"
                        + " origin_ref = ?", parkingId.toString()))
                .isEqualTo(1);
        assertThat(count(application, "SELECT count(*) FROM reconciliation.break WHERE"
                        + " suspense_item_id = ?", opened.suspenseItemId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a parking whose execution a credit already explains is owned as a"
            + " DUPLICATE_EXTERNAL under EXECUTION_ALREADY_EXPLAINED, naming what explains it")
    void anExplainedParkingIsOwnedAsADuplicate() throws SQLException {
        UUID payIn = IDS.next();
        ParkedConfirmations.Opened opened =
                opener().open(
                        application,
                        parking(IDS.next(), IDS.next(), BreakCause.EXECUTION_ALREADY_EXPLAINED,
                                InternalClassification.COMPLETED,
                                Optional.of("PAY_IN:" + payIn)));

        assertThat(opened.created()).isTrue();
        assertThat(text(application, "SELECT type || '|' || cause || '|' || suspense_item_id"
                        + " || '|' || internal_classification || '|' || internal_operation_ref"
                        + " FROM reconciliation.break WHERE id = ?", opened.breakId()))
                .isEqualTo("DUPLICATE_EXTERNAL|EXECUTION_ALREADY_EXPLAINED|"
                        + opened.suspenseItemId() + "|COMPLETED|PAY_IN:" + payIn);
    }

    @Test
    @DisplayName("ten openers of one parking at once: one item, one break, one announcement -"
            + " every loser's break rolled back with its savepoint, every loser converged")
    void tenOpenersOwnOneParkingOnce() throws Exception {
        UUID parkingId = IDS.next();
        UUID entryId = IDS.next();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        List<Future<ParkedConfirmations.Opened>> racers = new ArrayList<>();
        try {
            for (int i = 0; i < RACERS; i++) {
                racers.add(pool.submit(() -> {
                    try (Connection racer = DatabaseRoles.application()) {
                        racer.setAutoCommit(false);
                        start.await();
                        ParkedConfirmations.Opened opened =
                                opener().open(
                                        racer,
                                        parking(parkingId, entryId, BreakCause.PARKED_ON_RECEIPT,
                                                InternalClassification.UNKNOWN,
                                                Optional.empty()));
                        racer.commit();
                        return opened;
                    }
                }));
            }
            start.countDown();
            List<ParkedConfirmations.Opened> outcomes = new ArrayList<>();
            for (Future<ParkedConfirmations.Opened> racer : racers) {
                outcomes.add(racer.get(2, TimeUnit.MINUTES));
            }
            assertThat(outcomes.stream().filter(ParkedConfirmations.Opened::created))
                    .as("exactly one opener created")
                    .hasSize(1);
            assertThat(outcomes.stream().map(ParkedConfirmations.Opened::suspenseItemId)
                            .distinct())
                    .as("every racer answers the one item")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        try (Connection reader = DatabaseRoles.application()) {
            UUID item =
                    (UUID) one(reader, "SELECT id FROM reconciliation.suspense_item WHERE"
                            + " origin_ref = ?", parkingId.toString());
            assertThat(count(reader, "SELECT count(*) FROM reconciliation.suspense_item WHERE"
                            + " origin_ref = ?", parkingId.toString()))
                    .isEqualTo(1);
            assertThat(count(reader, "SELECT count(*) FROM reconciliation.break WHERE"
                            + " cause = 'PARKED_ON_RECEIPT' AND correlation_id = ?",
                            "p8t20-" + parkingId))
                    .as("no loser's break survived its savepoint")
                    .isEqualTo(1);
            UUID owner =
                    (UUID) one(reader, "SELECT break_id FROM reconciliation.suspense_item"
                            + " WHERE id = ?", item);
            assertThat(one(reader, "SELECT suspense_item_id FROM reconciliation.break WHERE"
                            + " id = ?", owner))
                    .isEqualTo(item);
            assertThat(announcements(reader, owner)).isEqualTo(1);
            assertThat(count(reader, "SELECT count(*) FROM platform.audit_record WHERE"
                            + " correlation_id = ?", "p8t20-" + parkingId))
                    .as("one BreakRaised record: the losers' were rolled back with them")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ten openers of one parking at once count ONE raise: each loser's raise is"
            + " dropped with its savepoint, never counted at its commit (P8-TSK-024's gate)")
    void tenOpenersCountOneRaise() throws Exception {
        UUID parkingId = IDS.next();
        UUID entryId = IDS.next();
        AtomicLong committedRaises = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        List<Future<?>> racers = new ArrayList<>();
        try {
            for (int i = 0; i < RACERS; i++) {
                racers.add(pool.submit(() -> {
                    // One transaction's deferred counts, as the app's meters keep them: a raise
                    // the register created is deferred, the savepoint's mark drops what its
                    // rollback undid, and only a commit counts what is left.
                    List<String> deferred = new ArrayList<>();
                    ReconciliationTelemetry telemetry =
                            new ReconciliationTelemetry() {
                                @Override
                                public int countMark() {
                                    return deferred.size();
                                }

                                @Override
                                public void discardCountsAfter(int mark) {
                                    deferred.subList(mark, deferred.size()).clear();
                                }
                            };
                    BreakRegister register =
                            new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
                    BreakRegister counting = (unitOfWork, newBreak) -> {
                        BreakRegister.Raised raised = register.raise(unitOfWork, newBreak);
                        if (raised.created()) {
                            deferred.add(newBreak.type().name());
                        }
                        return raised;
                    };
                    try (Connection racer = DatabaseRoles.application()) {
                        racer.setAutoCommit(false);
                        start.await();
                        new ParkedConfirmations(counting, IDS, telemetry)
                                .open(racer,
                                        parking(parkingId, entryId, BreakCause.PARKED_ON_RECEIPT,
                                                InternalClassification.UNKNOWN,
                                                Optional.empty()));
                        racer.commit();
                        committedRaises.addAndGet(deferred.size());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> racer : racers) {
                racer.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(committedRaises.get())
                .as("one break stands, so one raise is counted - never one per racer")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the deferral is the opener's alone: a raw break naming a suspense item that"
            + " does not exist is refused at its own statement, and deferring it only moves the"
            + " refusal to SET CONSTRAINTS IMMEDIATE - never past it")
    void everyOtherWriterStillMeetsTheSubjectKey() throws SQLException {
        String rawBreak = "INSERT INTO reconciliation.break (id, type, cause, severity,"
                + " source_id, rule_set_id, suspense_item_id, value_at_issue_minor, currency,"
                + " scale, raised_at, status_changed_at, correlation_id) VALUES (?,"
                + " 'UNKNOWN_EXTERNAL', 'PARKED_ON_RECEIPT', 'HIGH', ?, ?, ?, 100, 'EUR', 2,"
                + " now(), now(), 'p8t20-raw')";

        Savepoint before = application.setSavepoint();
        Throwable immediate =
                catchThrowable(() -> execute(application, rawBreak, IDS.next(), SOURCE, RULE_SET,
                        IDS.next()));
        application.rollback(before);
        assertThat(sqlState(immediate))
                .as("INITIALLY IMMEDIATE: the missing subject refused at the insert itself")
                .isEqualTo("23503");

        Savepoint deferred = application.setSavepoint();
        execute(application, "SET CONSTRAINTS reconciliation.break_suspense_item_fk DEFERRED");
        execute(application, rawBreak, IDS.next(), SOURCE, RULE_SET, IDS.next());
        Throwable atImmediate =
                catchThrowable(() -> execute(application,
                        "SET CONSTRAINTS reconciliation.break_suspense_item_fk IMMEDIATE"));
        application.rollback(deferred);
        assertThat(sqlState(atImmediate))
                .as("a deferred subject that never came is refused when the check returns")
                .isEqualTo("23503");
    }

    // ----------------------------------------------------------------- SQL

    private static long announcements(Connection connection, UUID breakId) throws SQLException {
        return count(connection, "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.ReconciliationBreakRaised' AND aggregate_id = ?", breakId);
    }

    private static String sqlState(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        return cause == null ? null : ((SQLException) cause).getSQLState();
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        if (args.length == 0) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Object one(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static String text(Connection connection, String sql, Object... args)
            throws SQLException {
        Object value = one(connection, sql, args);
        return value == null ? null : value.toString();
    }

    private static long count(Connection connection, String sql, Object... args)
            throws SQLException {
        return ((Number) one(connection, sql, args)).longValue();
    }
}
