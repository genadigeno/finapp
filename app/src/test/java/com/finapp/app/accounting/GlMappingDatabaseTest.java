package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.StatementSection;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.YearMonth;
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
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The GL mapping as versioned data against a real database (`P14-TSK-003`; {@code accounting V002}, ADR-0113 decision 3;
 * {@code INV-ACC-06}, {@code INV-ACC-07}, {@code INV-AUD-04}): the backlog entry's four named cases - the proposer cannot
 * approve (the domain and the {@code CHECK} for every writer), ten activators leave one {@code ACTIVE}, a non-total and an
 * incompatible version refused against the LIVE ledger chart through {@link LedgerPurposeChart} - with the content
 * immutable, one {@code ACTIVE} and nothing born active held for the owner itself by raw SQL. risk's
 * {@code RiskPolicyDatabaseTest}, applied. <strong>Written and owed</strong> (owner decision C5: the database tiers are
 * skipped per task; the owner-authorised database pass before the exit review runs it).
 *
 * <p>The versions are shared rows in the suite's database and one version is {@code ACTIVE} at a time, so every case
 * proposes from the month already in force ({@link #month()}) and never assumes what an earlier case left. The chart is
 * the live one: a suite elsewhere that opened a runtime account with a type its owning module would never give it would
 * make the reference incompatible here - which is the refusal working, and would be investigated as such.
 */
@Tag("database")
@DisplayName("the GL mapping as versioned data, four-eyes (P14-TSK-003)")
class GlMappingDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcGlMappingStore STORE = new JdbcGlMappingStore();
    private static final GlMappingAdministration ADMINISTRATION = new GlMappingAdministration(STORE,
            new LedgerPurposeChart(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);

    @Test
    @DisplayName("the proposer cannot approve: SelfApprovalRefused and nothing moves; a raw activation by the proposer is"
            + " refused by gl_mapping_version_four_eyes for the owner itself, and a non-person decider by"
            + " gl_mapping_version_persons")
    void theProposerCannotApprove() throws Exception {
        Actor proposer = person();
        GlMappingAdministration.Proposed proposed = propose(ReferenceGlMapping.version(month()), proposer);
        assertThatThrownBy(() -> inOneTransaction(uow -> ADMINISTRATION.activate(uow, proposed.id(), proposer, "mine",
                correlation()))).isInstanceOf(GlMappingAdministration.SelfApprovalRefused.class);
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "UPDATE accounting.gl_mapping_version SET status = 'ACTIVE', decided_by = proposed_by,"
                    + " decided_by_type = 'CUSTOMER', decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'",
                    "23514", "gl_mapping_version_four_eyes");
            refusedBy(owner, "UPDATE accounting.gl_mapping_version SET status = 'REJECTED', decided_by = 'platform',"
                    + " decided_by_type = 'SYSTEM', decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'",
                    "23514", "gl_mapping_version_persons");
        }
        inOneTransaction(uow -> ADMINISTRATION.reject(uow, proposed.id(), person(), "done", correlation()));
    }

    @Test
    @DisplayName("ten activators on ten connections activate once: one ACTIVE, nine MappingStale, one event, one"
            + " activation record; the predecessor retired in the same transaction, ending where the successor begins")
    void tenActivationsOneActive() throws Exception {
        GlMappingAdministration.Proposed proposed = propose(ReferenceGlMapping.version(month()), person());
        List<Object> outcomes = race(10, () -> decideOrStale(
                uow -> ADMINISTRATION.activate(uow, proposed.id(), person(), "racing", correlation())));
        assertThat(outcomes.stream().filter(GlMappingAdministration.Decided.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(GlMappingAdministration.MappingStale.class::isInstance)).hasSize(9);
        assertThat(status(proposed.id())).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM accounting.gl_mapping_version WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '"
                + GlMappingAdministration.ACTIVATED_EVENT + "' AND aggregate_id = '" + proposed.id().value() + "'"))
                .as("one event").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.MappingActivated'"
                + " AND target_id = '" + proposed.id().value() + "'")).as("losers record nothing").isEqualTo(1);
        GlMappingAdministration.Decided decided = (GlMappingAdministration.Decided) outcomes.stream()
                .filter(GlMappingAdministration.Decided.class::isInstance).findFirst().orElseThrow();
        if (decided.retired().isPresent()) {
            assertThat(count("SELECT count(*) FROM accounting.gl_mapping_version WHERE version = "
                    + decided.retired().get() + " AND status = 'RETIRED' AND effective_to_period = '"
                    + decided.effectiveFrom().atDay(1) + "'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a non-total version is refused against the live chart - a purpose unmapped - and stays PROPOSED")
    void aNonTotalVersionIsRefused() throws Exception {
        GlMappingAdministration.Proposed proposed =
                propose(ReferenceGlMapping.without(month(), Set.of("SUSPENSE_UNMATCHED")), person());
        assertThatThrownBy(() -> inOneTransaction(uow -> ADMINISTRATION.activate(uow, proposed.id(), person(), "why",
                correlation()))).isInstanceOf(GlMappingAdministration.MappingNotTotal.class)
                .hasMessageContaining("SUSPENSE_UNMATCHED/SUSPENSE");
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
        inOneTransaction(uow -> ADMINISTRATION.reject(uow, proposed.id(), person(), "not total", correlation()));
    }

    @Test
    @DisplayName("an incompatible classification is refused against the live chart - the seeded ASSET CASH_AT_BANK mapped"
            + " to a LIABILITY account - and stays PROPOSED")
    void anIncompatibleClassificationIsRefused() throws Exception {
        GlMappingAdministration.Proposed proposed = propose(ReferenceGlMapping.remapped(month(), "CASH_AT_BANK",
                new GlAccount("2999", "Cash shown as a liability", GlClassification.LIABILITY,
                        StatementSection.OTHER_LIABILITIES)), person());
        assertThatThrownBy(() -> inOneTransaction(uow -> ADMINISTRATION.activate(uow, proposed.id(), person(), "why",
                correlation()))).isInstanceOf(GlMappingAdministration.MappingIncompatible.class)
                .hasMessageContaining("CASH_AT_BANK/OPERATIONAL is ASSET");
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
        inOneTransaction(uow -> ADMINISTRATION.reject(uow, proposed.id(), person(), "incompatible", correlation()));
    }

    @Test
    @DisplayName("for the owner itself: the content is immutable, no version is born ACTIVE, a second ACTIVE is refused"
            + " by the partial unique, a retroactive activation by the edge trigger, a version without content at COMMIT")
    void theRanksHoldForEveryWriter() throws Exception {
        GlMappingAdministration.Proposed active = propose(ReferenceGlMapping.version(month()), person());
        inOneTransaction(uow -> ADMINISTRATION.activate(uow, active.id(), person(), "in force", correlation()));
        GlMappingAdministration.Proposed second = propose(ReferenceGlMapping.version(month()), person());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE accounting.gl_account SET name = 'renamed' WHERE version_id = '" + active.id().value()
                    + "'", "P0001");
            refused(owner, "DELETE FROM accounting.gl_mapping_rule WHERE version_id = '" + active.id().value() + "'",
                    "P0001");
            refused(owner, "INSERT INTO accounting.gl_mapping_version (id, version, status, effective_from_period,"
                    + " proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES ('"
                    + UUID.randomUUID() + "', 999999, 'ACTIVE', '2030-01-01', 'raw', 'CUSTOMER', clock_timestamp(),"
                    + " 'raw', 'raw')", "P0001");
            refusedBy(owner, "UPDATE accounting.gl_mapping_version SET status = 'ACTIVE', decided_by = 'someone-else',"
                    + " decided_by_type = 'CUSTOMER', decision_reason = 'raw' WHERE id = '" + second.id().value() + "'",
                    "23505", "gl_mapping_one_active");
        }
        GlMappingAdministration.Proposed backDated =
                propose(ReferenceGlMapping.version(month().minusMonths(1)), person());
        assertThatThrownBy(() -> inOneTransaction(uow -> ADMINISTRATION.activate(uow, backDated.id(), person(), "why",
                correlation()))).isInstanceOf(GlMappingAdministration.MappingRetroactive.class);
        for (GlMappingVersionId pending : List.of(second.id(), backDated.id())) {
            inOneTransaction(uow -> ADMINISTRATION.reject(uow, pending, person(), "done", correlation()));
        }
        try (Connection writer = DatabaseRoles.application()) {
            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.execute("INSERT INTO accounting.gl_mapping_version (id, version, status, effective_from_period,"
                        + " proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES ('"
                        + UUID.randomUUID() + "', 999998, 'PROPOSED', '2030-01-01', 'raw', 'CUSTOMER', clock_timestamp(),"
                        + " 'no content', 'raw')");
            }
            assertThatThrownBy(writer::commit).as("a version without its content never commits")
                    .isInstanceOf(SQLException.class)
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
            writer.rollback();
        }
    }

    // -----------------------------------------------------------------

    /** The month a new proposal may take effect from: the latest already in force, or the reference's. */
    private static YearMonth month() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery("SELECT max(effective_from_period)::text FROM"
                        + " accounting.gl_mapping_version WHERE status IN ('ACTIVE', 'RETIRED')")) {
            row.next();
            String latest = row.getString(1);
            return latest == null ? ReferenceGlMapping.EFFECTIVE_FROM : YearMonth.parse(latest.substring(0, 7));
        }
    }

    private static GlMappingAdministration.Proposed propose(GlMappingVersion version, Actor proposer) {
        return inOneTransaction(uow -> ADMINISTRATION.propose(uow, version, "the reference chart", proposer,
                correlation()));
    }

    private static Object decideOrStale(Function<Connection, GlMappingAdministration.Decided> act) {
        try {
            return inOneTransaction(act);
        } catch (GlMappingAdministration.MappingStale stale) {
            return stale;
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

    private static Actor person() {
        return new Actor("operator-" + UUID.randomUUID(), ActorType.CUSTOMER);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String status(GlMappingVersionId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT status FROM accounting.gl_mapping_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
    }

    /** Refused with {@code sqlState} by {@code constraint} by name - not by another rank raising the same state. */
    private static void refusedBy(Connection connection, String sql, String sqlState, String constraint) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState);
                    assertThat(failure.getMessage()).as("refused by %s itself", constraint).contains(constraint);
                });
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
