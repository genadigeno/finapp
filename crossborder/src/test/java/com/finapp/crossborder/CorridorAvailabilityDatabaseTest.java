package com.finapp.crossborder;

import static com.finapp.crossborder.CorridorPolicyFixtures.application;
import static com.finapp.crossborder.CorridorPolicyFixtures.availability;
import static com.finapp.crossborder.CorridorPolicyFixtures.controller;
import static com.finapp.crossborder.CorridorPolicyFixtures.correlation;
import static com.finapp.crossborder.CorridorPolicyFixtures.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The corridor kill switch against a live PostgreSQL (`P9-TSK-015`, ADR-0080 section 4, the lifecycle
 * document §3.10; {@code INV-AUD-04}) - the FX switch's suite on crossborder's tables: one person stops
 * a corridor at once, two restart it, each rank of the enabling's four eyes proven alone, the facts
 * append-only for every writer, and ten racers converging (counted).
 */
@Tag("database")
@DisplayName("the corridor kill switch: one person to stop, two to restart (P9-TSK-015)")
class CorridorAvailabilityDatabaseTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RAISED = "P0001";

    @Test
    @DisplayName("one person disables at once; a repeat writes nothing; enabling is proposed, refused"
            + " to its proposer, approved by a second person - every step recorded")
    void stopAtOnceRestartUnderFourEyes() throws SQLException {
        CorridorKey subject = corridor();
        Actor first = controller();
        Actor second = controller();
        try (Connection app = application()) {
            assertThat(availability().isAvailable(app, subject)).as("no fact: available").isTrue();
            assertThat(availability().disable(app, subject, first, "corridor outage", Instant.now(), correlation()).changed())
                    .isTrue();
            app.commit();
            assertThat(availability().isAvailable(app, subject)).isFalse();
            assertThat(availability().disable(app, subject, second, "again", Instant.now(), correlation()).changed())
                    .as("already unavailable: nothing written")
                    .isFalse();
            app.commit();

            CorridorAvailability.EnableProposed proposed =
                    availability().proposeEnable(app, subject, first, "corridor recovered", Instant.now(), correlation());
            app.commit();
            assertThat(availability().isAvailable(app, subject)).as("a proposal enables nothing").isFalse();
            assertThatThrownBy(() -> availability().approveEnable(app, proposed.requestId(), first, "mine",
                            Instant.now(), correlation()))
                    .isInstanceOf(CorridorAvailability.EnableSelfApprovalRefused.class);
            app.rollback();
            availability().approveEnable(app, proposed.requestId(), second, "verified", Instant.now(), correlation());
            app.commit();
            assertThat(availability().isAvailable(app, subject)).isTrue();
            assertThat(facts(app, subject)).as("disabled once, enabled once").isEqualTo(2);
            assertThat(scalar(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                            + " 'crossborder.CorridorAvailabilityChanged' AND convert_from(payload, 'UTF8') LIKE '%"
                            + subject.code() + "%'"))
                    .isEqualTo("2");
            assertThatThrownBy(() -> availability().proposeEnable(app, subject, first, "again", Instant.now(), correlation()))
                    .isInstanceOf(CorridorAvailability.AlreadyAvailable.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("each rank of the enabling's four eyes alone: the CHECK refuses a self-approval by raw"
            + " SQL, and an enabling fact needs an APPROVED request for its own subject")
    void theEnablingRanksHoldAlone() throws SQLException {
        CorridorKey subject = corridor();
        try (Connection app = application()) {
            availability().disable(app, subject, controller(), "outage", Instant.now(), correlation());
            CorridorAvailability.EnableProposed proposed =
                    availability().proposeEnable(app, subject, controller(), "recovered", Instant.now(), correlation());
            app.commit();
            assertThatThrownBy(() -> execute(app, "UPDATE crossborder.corridor_enable_request SET status = 'APPROVED',"
                            + " decided_by = proposed_by, decided_at = now(), decision_reason = 'myself'"
                            + " WHERE id = '" + proposed.requestId() + "'"))
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, "INSERT INTO crossborder.corridor_availability (corridor, id,"
                            + " available, actor_id, reason, recorded_at, enable_request_id) VALUES ('"
                            + subject.code() + "', '" + UUID.randomUUID() + "', true, 'x', 'self-enabled',"
                            + " now(), '" + proposed.requestId() + "')"))
                    .as("an enabling fact naming a request still PROPOSED")
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, "INSERT INTO crossborder.corridor_availability (corridor, id,"
                            + " available, actor_id, reason, recorded_at) VALUES ('" + subject.code() + "', '"
                            + UUID.randomUUID() + "', true, 'x', 'no request', now())"))
                    .as("an enabling fact naming no request")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
        }
    }

    @Test
    @DisplayName("the request machine and the facts hold for every writer: no second live proposal, no"
            + " edge out of a decision, no edited or deleted fact")
    void theMachineAndFactsHoldForEveryWriter() throws SQLException {
        CorridorKey subject = corridor();
        try (Connection app = application(); Connection migrator = DatabaseRoles.migrator()) {
            availability().disable(app, subject, controller(), "outage", Instant.now(), correlation());
            CorridorAvailability.EnableProposed proposed =
                    availability().proposeEnable(app, subject, controller(), "recovered", Instant.now(), correlation());
            app.commit();
            assertThatThrownBy(() -> availability().proposeEnable(app, subject, controller(), "twice", Instant.now(), correlation()))
                    .isInstanceOf(CorridorAvailability.EnablePending.class);
            app.rollback();
            Actor rejecter = controller();
            availability().rejectEnable(app, proposed.requestId(), rejecter, "not yet", Instant.now(), correlation());
            app.commit();
            assertThat(availability().rejectEnable(app, proposed.requestId(), rejecter, "not yet", Instant.now(),
                            correlation()).replayed())
                    .as("the rejecter's retry converges")
                    .isTrue();
            assertThatThrownBy(() -> availability().approveEnable(app, proposed.requestId(), controller(), "late",
                            Instant.now(), correlation()))
                    .as("the domain refuses REJECTED -> APPROVED")
                    .isInstanceOf(CorridorAvailability.EnableRequestNotPending.class);
            assertThatThrownBy(() -> availability().rejectEnable(app, proposed.requestId(), controller(), "again",
                            Instant.now(), correlation()))
                    .as("and a second person's REJECTED -> REJECTED")
                    .isInstanceOf(CorridorAvailability.EnableRequestNotPending.class);
            app.rollback();
            assertThatThrownBy(() -> execute(migrator, "UPDATE crossborder.corridor_enable_request SET status = 'APPROVED'"
                            + " WHERE id = '" + proposed.requestId() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThatThrownBy(() -> execute(migrator, "DELETE FROM crossborder.corridor_enable_request WHERE id = '"
                            + proposed.requestId() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThatThrownBy(() -> execute(migrator, "UPDATE crossborder.corridor_availability SET reason = 'edited'"
                            + " WHERE corridor = '" + subject.code() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            assertThatThrownBy(() -> execute(app, "DELETE FROM crossborder.corridor_availability WHERE corridor = '"
                            + subject.code() + "'"))
                    .matches(e -> INSUFFICIENT_PRIVILEGE.equals(((SQLException) e).getSQLState()));
            app.rollback();
        }
    }

    @Test
    @DisplayName("ten approvers of one enable request: one approval, one enabling fact (counted)")
    void tenApproversOneEnabling() throws Exception {
        CorridorKey subject = corridor();
        UUID requestId;
        try (Connection app = application()) {
            availability().disable(app, subject, controller(), "outage", Instant.now(), correlation());
            requestId = availability().proposeEnable(app, subject, controller(), "recovered", Instant.now(), correlation())
                    .requestId();
            app.commit();
        }
        List<Boolean> outcomes =
                race(10, () -> {
                    try (Connection own = application()) {
                        try {
                            availability().approveEnable(own, requestId, controller(), "raced", Instant.now(), correlation());
                            own.commit();
                            return true;
                        } catch (CorridorAvailability.EnableRequestNotPending lost) {
                            own.rollback();
                            return false;
                        }
                    }
                });
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT count(*) FROM crossborder.corridor_availability WHERE corridor = '"
                            + subject.code() + "' AND available"))
                    .as("counted in the table")
                    .isEqualTo("1");
            app.rollback();
        }
    }

    @Test
    @DisplayName("ten controllers disabling one subject at once: one fact, one event (counted) -"
            + " namespace 8 orders each read before its append")
    void tenDisablersOneFact() throws Exception {
        CorridorKey subject = corridor();
        List<Boolean> changed =
                race(10, () -> {
                    try (Connection own = application()) {
                        boolean wrote = availability()
                                .disable(own, subject, controller(), "raced outage", Instant.now(), correlation())
                                .changed();
                        own.commit();
                        return wrote;
                    }
                });
        assertThat(changed.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        try (Connection app = application()) {
            assertThat(facts(app, subject)).as("counted in the table").isEqualTo(1);
            assertThat(scalar(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                            + " 'crossborder.CorridorAvailabilityChanged' AND convert_from(payload, 'UTF8') LIKE '%"
                            + subject.code() + "%'"))
                    .isEqualTo("1");
            app.rollback();
        }
    }

    // -----------------------------------------------------------------

    /** A corridor no other case touches: a random (pair, country) - availability needs no policy row. */
    private static CorridorKey corridor() {
        List<String> currencies = List.of("EUR", "GBP", "USD", "JPY", "BHD");
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        String[] countries = java.util.Locale.getISOCountries();
        while (true) {
            String source = currencies.get(random.nextInt(currencies.size()));
            String destination = currencies.get(random.nextInt(currencies.size()));
            if (!source.equals(destination)) {
                return new CorridorKey(
                        com.finapp.sharedkernel.money.CurrencyCode.of(source),
                        com.finapp.sharedkernel.money.CurrencyCode.of(destination),
                        com.finapp.sharedkernel.money.CountryCode.of(countries[random.nextInt(countries.length)]));
            }
        }
    }

    private static long facts(Connection app, CorridorKey subject) throws SQLException {
        return Long.parseLong(scalar(app, "SELECT count(*) FROM crossborder.corridor_availability WHERE corridor = '"
                + subject.code() + "'"));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
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
