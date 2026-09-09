package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one-open-case rule under real contention, and the conditional transition (`P2-TSK-005`).
 *
 * <p>The whole argument for putting the rule in the database is that only the database can
 * arbitrate between two concurrent transactions ({@code P1-TSK-005}), so the headline test
 * races <strong>ten instances, each with its own connection</strong> ({@code P0-TST-009}), and
 * counts rows rather than trusting outcomes.
 *
 * <p>Run as the <strong>application role</strong> throughout, so {@code V002}'s grants are
 * proven sufficient for real use rather than assumed.
 */
@Tag("database")
@DisplayName("the KYC case table under contention (P2-TSK-005)")
class KycCaseDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final KycCaseStore<Connection> store = new JdbcKycCaseStore();

    @Test
    @DisplayName("ten instances opening for one customer produce exactly one case")
    void tenConcurrentOpensProduceOneCase() throws Exception {
        UUID customerId = IDS.next();
        int instances = 10;
        CyclicBarrier start = new CyclicBarrier(instances);
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        List<KycCaseStore.Opening> openings = new ArrayList<>();
        try {
            List<Callable<KycCaseStore.Opening>> racers = new ArrayList<>();
            for (int i = 0; i < instances; i++) {
                racers.add(
                        () -> {
                            // Own connection per instance (P0-TST-009): the contention is real,
                            // and the losers BLOCK on the index until the winner commits -
                            // which is the database arbitrating, not this test arranging.
                            try (Connection app = DatabaseRoles.application()) {
                                app.setAutoCommit(false);
                                start.await();
                                KycCaseStore.Opening opening =
                                        store.openOrConverge(
                                                app, KycCase.open(IDS, CLOCK, customerId));
                                app.commit();
                                return opening;
                            }
                        });
            }
            for (Future<KycCaseStore.Opening> outcome : pool.invokeAll(racers)) {
                openings.add(outcome.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(openings).hasSize(instances);
        assertThat(openings.stream().filter(KycCaseStore.Opening::created))
                .as("exactly one instance created the case")
                .hasSize(1);
        assertThat(openings.stream().map(opening -> opening.kycCase().id()).distinct())
                .as("and all ten were handed the same case - convergence, not an error")
                .hasSize(1);
        assertThat(rowsFor(customerId))
                .as("counted in the table, never inferred from the outcomes")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a transition is a conditional UPDATE whose row count is the outcome")
    void aTransitionIsWonExactlyOnce() throws Exception {
        UUID customerId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            KycCase kycCase = store.openOrConverge(app, KycCase.open(IDS, CLOCK, customerId)).kycCase();
            // Instants derived from the aggregate, not from clock reads: two reads of a real
            // clock are not ordered (the P1-TSK-031 class), and the constraint compares stored
            // values.
            Instant later = kycCase.openedAt().plusSeconds(1);

            assertThat(
                            store.moveStatus(
                                    app,
                                    kycCase.id(),
                                    KycCaseStatus.OPEN,
                                    KycCaseStatus.CHECKS_IN_PROGRESS,
                                    later))
                    .as("the first mover wins")
                    .isTrue();
            assertThat(
                            store.moveStatus(
                                    app,
                                    kycCase.id(),
                                    KycCaseStatus.OPEN,
                                    KycCaseStatus.CHECKS_IN_PROGRESS,
                                    later))
                    .as("a second attempt from the same from-state is told it lost - the row"
                            + " count is the outcome, not an error and not a success")
                    .isFalse();
            app.commit();

            assertThat(store.findOpenFor(app, customerId))
                    .hasValueSatisfying(
                            open ->
                                    assertThat(open.status())
                                            .isEqualTo(KycCaseStatus.CHECKS_IN_PROGRESS));
        }
    }

    @Test
    @DisplayName("a decided case frees the slot: changed circumstances open a NEW case")
    void aDecidedCaseFreesTheSlot() throws Exception {
        UUID customerId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            KycCase first = store.openOrConverge(app, KycCase.open(IDS, CLOCK, customerId)).kycCase();

            // While it is open, a second ask converges rather than duplicating.
            assertThat(store.openOrConverge(app, KycCase.open(IDS, CLOCK, customerId)).created())
                    .isFalse();

            // Walk it to APPROVED through the machine's own legal path.
            Instant later = first.openedAt().plusSeconds(1);
            assertThat(store.moveStatus(app, first.id(), KycCaseStatus.OPEN, KycCaseStatus.CHECKS_IN_PROGRESS, later)).isTrue();
            assertThat(store.moveStatus(app, first.id(), KycCaseStatus.CHECKS_IN_PROGRESS, KycCaseStatus.READY_FOR_DECISION, later)).isTrue();
            assertThat(store.moveStatus(app, first.id(), KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.APPROVED, later)).isTrue();
            app.commit();

            // INV-LIFE-04's other half: the terminal set IS the index predicate, so a decided
            // case must admit a successor - a periodic re-verification is a new case, and the
            // decided one stays decided and stays true.
            KycCaseStore.Opening successor =
                    store.openOrConverge(app, KycCase.open(IDS, CLOCK, customerId));
            app.commit();
            assertThat(successor.created()).as("the slot is free").isTrue();
            assertThat(successor.kycCase().id()).isNotEqualTo(first.id());
            assertThat(store.findOpenFor(app, customerId))
                    .hasValueSatisfying(open -> assertThat(open.id()).isEqualTo(successor.kycCase().id()));
            assertThat(rowsFor(customerId)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a status outside the machine is physically unwritable")
    void anUnknownStatusIsUnwritable() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try (PreparedStatement insert =
                    app.prepareStatement(
                            "INSERT INTO kyc.kyc_case (id, customer_id, status, policy_version,"
                                    + " opened_at, status_changed_at)"
                                    + " VALUES (?, ?, 'DECIDED', 'kyc-1', now(), now())")) {
                insert.setObject(1, IDS.next());
                insert.setObject(2, IDS.next());
                assertThatThrownBy(insert::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .extracting(failure -> ((SQLException) failure).getSQLState())
                        .as("the CHECK generated from the enum refuses it (23514)")
                        .isEqualTo("23514");
            } finally {
                app.rollback();
            }
        }
    }

    // -----------------------------------------------------------------

    private static int rowsFor(UUID customerId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM kyc.kyc_case WHERE customer_id = ?")) {
            count.setObject(1, customerId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }
}
