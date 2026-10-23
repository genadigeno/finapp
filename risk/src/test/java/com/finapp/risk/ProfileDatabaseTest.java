package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The customer risk profile against a real database (`P13-TSK-007`; {@code risk V006}; {@code PHASE_13_PLAN.md} section
 * 7.4's row "the profile recomputed by two sweepers"; {@code INV-RSK-10}, {@code INV-RSK-07}): ten recomputes of one
 * party write one version, by the waiting lock and by the sweep's {@code SKIP LOCKED} claim; an unchanged rating writes
 * nothing; {@code PROHIBITED} is never cleared by a recompute - the domain and the trigger each refuse it; a signal pulls
 * the permit forward, and a hint arriving while a recompute holds the head waits and pulls it after; and the table's
 * rules for every writer - the versions {@code INSERT}-only, in sequence, {@code PROHIBITED} only by a disposition, the
 * inputs' digest their own, the pinned version a monitoring one, the head moving only beside its versions, nothing ever
 * deleted. <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task; the
 * owner-authorised database pass before the exit review runs it).
 *
 * <p>The {@code MONITORING} policy is the shared database's: the suite activates the v1 fixture once (its predecessor,
 * if any, retired beside it) and every case uses parties of its own.
 */
@Tag("database")
@DisplayName("the customer risk profile (P13-TSK-007)")
class ProfileDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final JdbcCustomerRiskProfileStore STORE = new JdbcCustomerRiskProfileStore();
    /** Each party's scripted screening clearance - present only for the parties a case screens. */
    private static final Map<UUID, Boolean> SCREENED = new ConcurrentHashMap<>();
    private static final ProfileRecompute RECOMPUTE = new ProfileRecompute(new RiskPolicies(POLICY_STORE), STORE,
            new ProfileInputs((unitOfWork, party) -> Optional.ofNullable(SCREENED.get(party))
                    .map(hit -> new ScreeningClearances.Clearance(UUID.randomUUID(), hit, Instant.now()))),
            new JdbcOutboxWriter(), IDS, CLOCK, Duration.ofDays(1));

    private static RiskPolicyVersionId monitoring;

    @BeforeAll
    static void aMonitoringVersionIsActive() {
        RiskPolicyAdministration administration = new RiskPolicyAdministration(POLICY_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK);
        RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.MONITORING), "the profile suite's monitoring version", employee(),
                correlation()));
        inOneTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated", correlation()));
        monitoring = proposed.id();
    }

    @Test
    @DisplayName("ten recomputes of one party on ten connections write ONE version - the head's lock (R8) serialises"
            + " them, UNIQUE (party_id, version) the backstop: one CHANGED, nine UNCHANGED, one event")
    void tenRecomputesOneVersion() throws Exception {
        UUID party = IDS.next();
        List<ProfileRecompute.Outcome> outcomes = race(10,
                () -> inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, correlation())));
        assertThat(outcomes).filteredOn(o -> o.kind() == ProfileRecompute.Kind.CHANGED).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.kind() == ProfileRecompute.Kind.UNCHANGED).hasSize(9);
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = '" + party + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT current_version FROM risk.customer_risk_profile WHERE party_id = '" + party + "'"))
                .isEqualTo(1);
        assertThat(count(events(party))).as("one announcement").isEqualTo(1);

        // The sweep's shape: ten sweepers over one due head - first in the claim's order, its permit long past - and
        // SKIP LOCKED, so it is claimed once; whatever else the shared database has due is drained after.
        UUID due = IDS.next();
        inOneTransaction(uow -> {
            STORE.pullForward(uow, due);
            return null;
        });
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.execute("UPDATE risk.customer_risk_profile SET next_recompute_at = timestamptz '2000-01-01'"
                    + " WHERE party_id = '" + due + "'");
        }
        List<Optional<ProfileRecompute.Outcome>> claims = race(10, () -> inOneTransaction(RECOMPUTE::recomputeNextDue));
        assertThat(claims).filteredOn(claim -> claim.map(o -> o.partyId().equals(due)).orElse(false))
                .as("one sweeper claimed it").hasSize(1);
        for (int drained = 0; drained < 10_000 && inOneTransaction(RECOMPUTE::recomputeNextDue).isPresent(); drained++) {
            // Drained: nothing due remains.
        }
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = '" + due + "'"))
                .isEqualTo(1);
        assertThat(count(events(due))).isEqualTo(1);
    }

    @Test
    @DisplayName("an unchanged rating writes no version and no event - the permit moves a cadence ahead on the database's"
            + " clock; a changed one writes the next")
    void anUnchangedRatingWritesNoVersion() throws Exception {
        UUID party = IDS.next();
        inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, correlation()));
        ProfileRecompute.Outcome again = inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, correlation()));
        assertThat(again.kind()).isEqualTo(ProfileRecompute.Kind.UNCHANGED);
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = '" + party + "'"))
                .isEqualTo(1);
        assertThat(count(events(party))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile WHERE party_id = '" + party + "'"
                + " AND next_recompute_at > clock_timestamp() + interval '23 hours' AND recomputed_at IS NOT NULL"))
                .as("the permit a cadence ahead").isEqualTo(1);

        SCREENED.put(party, true);
        ProfileRecompute.Outcome moved = inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, correlation()));
        assertThat(moved.kind()).isEqualTo(ProfileRecompute.Kind.CHANGED);
        assertThat(moved.rating()).contains(RiskRating.MEDIUM);
        List<CustomerRiskProfileVersion> versions = inOneTransaction(uow -> STORE.versions(uow, party));
        assertThat(versions).extracting(CustomerRiskProfileVersion::version).containsExactly(1, 2);
        assertThat(versions.get(1).evaluation().orElseThrow().policyVersionId()).isEqualTo(monitoring);
        assertThat(versions.get(1).reasons()).containsExactly(ReasonCode.SCREENING_HIT);
        assertThat(versions.get(1).computedAt()).isPresent();
    }

    @Test
    @DisplayName("PROHIBITED is never cleared by a recompute: the sweep leaves it standing, and a recompute's version after"
            + " it is refused by the trigger for every writer (INV-RSK-07)")
    void prohibitedIsNeverClearedByARecompute() throws Exception {
        UUID party = IDS.next();
        inOneTransaction(uow -> {
            STORE.lockOrCreate(uow, party);
            STORE.insertVersion(uow, new CustomerRiskProfileVersion(party, 1, RiskRating.PROHIBITED,
                    ProfileCause.DISPOSITION, List.of(), Optional.empty(), Optional.of(UUID.randomUUID()), "disposition",
                    Optional.empty()));
            STORE.advance(uow, party, 0, 1, RiskRating.PROHIBITED, Duration.ZERO);
            return null;
        });
        ProfileRecompute.Outcome outcome = inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, correlation()));
        assertThat(outcome.kind()).isEqualTo(ProfileRecompute.Kind.PROHIBITED_STANDS);
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = '" + party + "'"))
                .isEqualTo(1);
        assertThat(count(events(party))).isZero();

        for (Connection writer : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (writer) {
                writer.setAutoCommit(false);
                refused(writer, recomputeVersion(party, 2, "LOW", digestOf("{}")), "23514");
                writer.rollback();
            }
        }
    }

    @Test
    @DisplayName("PROHIBITED only by a disposition: a recompute's PROHIBITED and a disposition's without its disposition are"
            + " refused by the CHECK alone; the digest is the inputs' own; the pinned version a MONITORING one")
    void theVersionsRulesHoldForEveryWriter() throws Exception {
        UUID party = IDS.next();
        inOneTransaction(uow -> STORE.lockOrCreate(uow, party));
        for (Connection writer : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (writer) {
                writer.setAutoCommit(false);
                refused(writer, recomputeVersion(party, 1, "PROHIBITED", digestOf("{}")), "23514");
                writer.rollback();
                refused(writer, "INSERT INTO risk.customer_risk_profile_version (party_id, version, rating, cause,"
                        + " reason_codes, correlation_id, computed_at) VALUES ('" + party + "', 1, 'PROHIBITED',"
                        + " 'DISPOSITION', '', 'raw', clock_timestamp())", "23514");
                writer.rollback();
                refused(writer, recomputeVersion(party, 1, "LOW", "0".repeat(64)), "23514");
                writer.rollback();
                // The pinned version must be a MONITORING one: another operation's version - or none at all - is no
                // (id, 'MONITORING') pair, refused by the composite foreign key.
                refused(writer, recomputeVersion(party, 1, "LOW", digestOf("{}")).replace(
                        monitoring.value().toString(), otherVersion()), "23503");
                writer.rollback();
                refused(writer, recomputeVersion(party, 2, "LOW", digestOf("{}")), "23514");
                writer.rollback();
            }
        }
    }

    @Test
    @DisplayName("the versions are INSERT-only and nothing is ever removed: the application holds no UPDATE or DELETE, the"
            + " owner meets the triggers")
    void theVersionsAreInsertOnlyForEveryWriter() throws Exception {
        UUID party = IDS.next();
        inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, correlation()));
        String version = " FROM risk.customer_risk_profile_version WHERE party_id = '" + party + "'";
        String head = " FROM risk.customer_risk_profile WHERE party_id = '" + party + "'";
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            refused(app, "UPDATE risk.customer_risk_profile_version SET rating = 'HIGH' WHERE party_id = '" + party + "'",
                    "42501");
            app.rollback();
            refused(app, "DELETE" + version, "42501");
            app.rollback();
            refused(app, "DELETE" + head, "42501");
            app.rollback();
            refused(app, "UPDATE risk.customer_risk_profile SET created_at = now() WHERE party_id = '" + party + "'",
                    "42501");
            app.rollback();
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE risk.customer_risk_profile_version SET rating = 'HIGH' WHERE party_id = '" + party + "'",
                    "P0001");
            owner.rollback();
            refused(owner, "DELETE" + version, "P0001");
            owner.rollback();
            refused(owner, "DELETE" + head, "P0001");
            owner.rollback();
            refused(owner, "TRUNCATE risk.customer_risk_profile_version", "P0001");
            owner.rollback();
            refused(owner, "TRUNCATE risk.customer_risk_profile CASCADE", "P0001");
            owner.rollback();
        }
    }

    @Test
    @DisplayName("the head moves only beside its versions: born unassessed, one version at a time, to the rating its version"
            + " carries - a raw move otherwise is refused for every writer")
    void theHeadMovesOnlyWithItsVersions() throws Exception {
        UUID party = IDS.next();
        inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, correlation()));
        for (Connection writer : List.of(DatabaseRoles.application(), DatabaseRoles.migrator())) {
            try (writer) {
                writer.setAutoCommit(false);
                refused(writer, "UPDATE risk.customer_risk_profile SET rating = 'HIGH' WHERE party_id = '" + party + "'",
                        "23514");
                writer.rollback();
                refused(writer, "UPDATE risk.customer_risk_profile SET current_version = 3, rating = 'LOW'"
                        + " WHERE party_id = '" + party + "'", "23514");
                writer.rollback();
                refused(writer, "INSERT INTO risk.customer_risk_profile (party_id, current_version, rating,"
                        + " next_recompute_at, created_at) VALUES ('" + IDS.next() + "', 1, 'LOW', now(), now())", "23514");
                writer.rollback();
                refused(writer, "UPDATE risk.customer_risk_profile SET current_version = 2, rating = 'MEDIUM'"
                        + " WHERE party_id = '" + party + "'", "23514");
                writer.rollback();
            }
        }
    }

    @Test
    @DisplayName("a signal pulls its party's permit forward - enrolling the party due now; and a hint arriving while a"
            + " recompute holds the head WAITS for it and pulls the permit back to now after its commit")
    void aSignalPullsThePermitForward() throws Exception {
        UUID identity = IDS.next();
        UUID party = IDS.next();
        RiskSignals signals = new RiskSignals(new JdbcRiskSignalStore(), new JdbcOutboxWriter(), IDS, CLOCK,
                ProfileHints.over((uow, subject) -> subject.equals(SignalSubject.of(SubjectKind.IDENTITY, identity))
                        ? Optional.of(party) : Optional.empty(), STORE));
        inOneTransaction(uow -> signals.record(uow, credentialChanged(identity)));
        String due = "SELECT count(*) FROM risk.customer_risk_profile WHERE party_id = '" + party + "'"
                + " AND current_version = 0 AND next_recompute_at <= clock_timestamp()";
        assertThat(count(due)).as("enrolled, due now").isEqualTo(1);

        // A recompute holds the head and re-stamps the permit a day ahead; the hint waits on it, then pulls it back.
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            STORE.lockOrCreate(holder, party);
            STORE.restamp(holder, party, Duration.ofDays(1), true);
            ExecutorService hinter = Executors.newSingleThreadExecutor();
            try {
                Future<RiskSignals.Outcome> hint = hinter.submit(
                        () -> inOneTransaction(uow -> signals.record(uow, credentialChanged(identity))));
                Thread.sleep(500);
                assertThat(hint.isDone()).as("the hint waits on the recompute's lock - it never skips").isFalse();
                holder.commit();
                assertThat(hint.get(1, TimeUnit.MINUTES)).isEqualTo(RiskSignals.Outcome.BORN);
            } finally {
                hinter.shutdownNow();
            }
        }
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile WHERE party_id = '" + party + "'"
                + " AND next_recompute_at <= clock_timestamp()")).as("due again after the recompute").isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private static RiskSignal credentialChanged(UUID identity) {
        return new RiskSignal(RiskSignalId.next(IDS), SignalKind.CREDENTIAL_CHANGED,
                SignalSubject.of(SubjectKind.IDENTITY, identity),
                new RiskSignal.SourceEvent(EventId.next(IDS), "identity.CredentialChanged", 1, "identity",
                        CorrelationId.of("profile-suite"), CausationId.of("profile-suite")),
                Instant.now(), SignalAttributes.of(SignalKind.CREDENTIAL_CHANGED, Map.of()));
    }

    /** A raw RECOMPUTE version of the suite's monitoring version over the canonical text "{}" with {@code sha256}. */
    private static String recomputeVersion(UUID party, int version, String rating, String sha256) {
        return "INSERT INTO risk.customer_risk_profile_version (party_id, version, rating, cause, signal_score,"
                + " reason_codes, policy_version_id, policy_operation, policy_version, engine, inputs_canonical,"
                + " inputs_sha256, correlation_id, computed_at) VALUES ('" + party + "', " + version + ", '" + rating
                + "', 'RECOMPUTE', 0, '', '" + monitoring.value() + "', 'MONITORING', 1, 'RISK_ENGINE_V1', '{}', '"
                + sha256 + "', 'raw', clock_timestamp())";
    }

    private static String digestOf(String text) {
        return CanonicalEvaluation.sha256(text);
    }

    /** A policy version of another operation if the shared database holds one, else an id no version has. */
    private static String otherVersion() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT id::text FROM risk.risk_policy_version WHERE operation <> 'MONITORING' LIMIT 1")) {
            return row.next() ? row.getString(1) : IDS.next().toString();
        }
    }

    private static String events(UUID party) {
        return "SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + ProfileRecompute.EVENT_TYPE
                + "' AND aggregate_id = '" + party + "'";
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

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
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
