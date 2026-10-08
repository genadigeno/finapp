package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The scorecard suites' shared machinery (`P10-TSK-011`). The family is one shared row set in the suite's database, so
 * every case first brings it to the state it needs through the domain itself - rejecting a pending proposal, proposing
 * and activating its own version - and never assumes what an earlier case left.
 */
final class ScorecardFixtures {

    static final Clock CLOCK = Clock.systemUTC();
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final JdbcScorecardStore STORE = new JdbcScorecardStore();
    static final ScorecardAdministration ADMINISTRATION =
            new ScorecardAdministration(STORE, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);

    private ScorecardFixtures() {}

    static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** v1's table with {@code base} for its base - so each case's version is told apart by its score. */
    static Scorecard table(int base) {
        Scorecard v1 = RetailScorecardV1.scorecard();
        return new Scorecard(base, v1.attributes());
    }

    /** Rejects the family's pending proposal, if there is one, so a case can propose. */
    static void clearPending() {
        inOneTransaction(uow -> {
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT id FROM credit.scorecard_model_version WHERE family = 'RETAIL_SCORECARD' AND status = 'PROPOSED'");
                    ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    ADMINISTRATION.reject(uow, ScorecardModelVersionId.of(row.getObject(1, UUID.class)), employee(),
                            "cleared for the next case", correlation());
                }
                return null;
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    /** A fresh proposal by a new person. */
    static ScorecardAdministration.Proposed propose(int base, Actor proposer) {
        clearPending();
        return inOneTransaction(uow -> ADMINISTRATION.propose(uow, ScorecardFamily.RETAIL_SCORECARD, table(base),
                "a case's proposal", proposer, correlation()));
    }

    /** A fresh version, proposed by one person and activated by another. */
    static ScorecardModelVersionId activate(int base) {
        ScorecardAdministration.Proposed proposed = propose(base, employee());
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "a case's activation", correlation()));
        return proposed.id();
    }

    /** A frozen snapshot of the golden attributes - a fresh decision request - pinning {@code model}. */
    static DecisionSnapshot snapshot(ScorecardModelVersionId model) {
        return snapshot(model, CanonicalSnapshotTest.attributes());
    }

    static DecisionSnapshot snapshot(ScorecardModelVersionId model, List<CreditAttribute> attributes) {
        UUID party = IDS.next();
        UUID decision = inOneTransaction(uow -> DecisionRequestRows.submitted(uow, party, CreditProduct.PERSONAL_LOAN));
        SnapshotContent content = new SnapshotContent(decision, party, CreditProduct.PERSONAL_LOAN,
                com.finapp.sharedkernel.money.Money.ofMinorUnits(1_000_000, com.finapp.sharedkernel.money.CurrencyCode.of("EUR")),
                java.util.Optional.of(36), new PinnedVersions(CreditPolicyV1.PERSONAL_LOAN_ID.value(), model.value(), 1), attributes);
        String canonical = CanonicalSnapshot.render(content);
        JdbcDecisionSnapshotStore snapshots = new JdbcDecisionSnapshotStore();
        return inOneTransaction(uow -> {
            snapshots.insertSnapshot(uow, DecisionSnapshotId.next(IDS), decision, 1, CanonicalSnapshot.FORMAT, canonical,
                    CanonicalSnapshot.sha256(canonical), content.versions());
            DecisionSnapshotStore.StoredSnapshot stored = snapshots.snapshotOf(uow, decision, 1).orElseThrow();
            return new DecisionSnapshot(stored.id(), stored.sequence(), stored.format(), stored.canonical(),
                    stored.sha256(), stored.frozenAt(), CanonicalSnapshot.parse(stored.canonical()));
        });
    }

    static <R> R inOneTransaction(Function<Connection, R> work) {
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

    static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    static String status(ScorecardModelVersionId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT status FROM credit.scorecard_model_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState}. */
    static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    static <T> List<T> race(int racers, Callable<T> work) throws Exception {
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
