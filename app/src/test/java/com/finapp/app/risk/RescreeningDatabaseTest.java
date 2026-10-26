package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.kyc.DocumentVerificationAdapter;
import com.finapp.kyc.IdentityVerificationAdapter;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.ScreeningAdapter;
import com.finapp.app.kyc.VerificationRunService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.RescreeningRun;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.ScreeningList;
import com.finapp.risk.ScreeningListVersion;
import com.finapp.risk.ScreeningListVersions;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The rescreening over the database, through kyc's real ports and its simulated provider (`P13-TSK-022`;
 * {@code PHASE_13_PLAN.md} sections 7.4, 12.10; {@code INV-RSK-06}, {@code INV-RSK-02}, {@code INV-RSK-07}): parties
 * planted {@code ACTIVE}, a list version recorded and activated by two {@code CUSTOMER}-typed persons, kyc's case-bound
 * screening run after the activation by {@link VerificationRunService} against the simulated provider, and the composed
 * application's {@link RescreeningRun} and {@link RescreeningDriver} driven by ten threads at once:
 *
 * <ul>
 *   <li>{@link #anActivationEnqueuesEveryActivePartyOnce} - ten enqueuers, an injected crash mid-batch: every
 *       {@code ACTIVE} party one permit, no {@code PENDING} one any;</li>
 *   <li>{@link #tenSweepersRescreenOnce} - ten sweepers: every permit concluded once, one clearance signal each;</li>
 *   <li>{@link #aHitAlertsOnceAndCases} - a hit: one signal, one {@code SCREENING_HIT} alert attached to one {@code AML}
 *       case, a re-drive and a second list's hit the same hour creating no second alert or case;</li>
 *   <li>{@link #aSecondActivationOfOneVersionIsRefused} - two activators racing: one activation;</li>
 *   <li>{@link #theRanksBeneathTheDomain} - the persons, four eyes and the machine refused for a raw writer.</li>
 * </ul>
 *
 * <p><strong>Written and owed under C5</strong> (the standing criterion-7 deviation): the database tier is not run per
 * task. The database is shared with every other suite, so each case asserts over its own parties and its own versions.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("the rescreening over the database: every active party once, each permit once, a hit alerted once"
        + " (P13-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RescreeningDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String CHECK_VIOLATION = "23514";
    private static final String[] ALL_PATHS = {
        IdentityVerificationAdapter.PATH, DocumentVerificationAdapter.PATH, ScreeningAdapter.SANCTIONS_PATH,
        ScreeningAdapter.PEP_PATH, ScreeningAdapter.ADVERSE_MEDIA_PATH
    };

    private static SimulatedProvider provider;

    @Autowired private ScreeningListVersions lists;
    @Autowired private RescreeningRun run;
    @Autowired private RescreeningDriver driver;
    @Autowired private RiskTransactions transactions;
    @Autowired private VerificationRunService runs;
    @Autowired private RiskPolicyAdministration policies;

    private final JdbcKycCaseStore cases = new JdbcKycCaseStore();

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.kyc.provider.url", () -> provider.baseUrl());
        registry.add("finapp.kyc.provider.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
        for (String path : ALL_PATHS) {
            provider.succeedsWith(path, 200, "{\"status\":\"clear\"}");
        }
    }

    @Test
    @DisplayName("an activation enqueues every ACTIVE party once under ten enqueuers and an injected crash mid-batch - and"
            + " no PENDING party; the census complete")
    void anActivationEnqueuesEveryActivePartyOnce() throws Exception {
        List<UUID> active = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            active.add(plantedCustomer("ACTIVE"));
        }
        List<UUID> pending = List.of(plantedCustomer("PENDING"), plantedCustomer("PENDING"));
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        AtomicInteger crashes = new AtomicInteger();
        concurrently(10, () -> {
            while (true) {
                if (crashes.getAndIncrement() < 2) {
                    // The injected crash: a batch enqueued and then rolled back whole before its commit.
                    try {
                        transactions.inTransaction(uow -> {
                            run.enqueueNextBatch(uow, 7);
                            throw new IllegalStateException("crash before the commit");
                        });
                    } catch (IllegalStateException crashed) {
                        // Rolled back: its permits and its advance are gone together.
                    }
                    continue;
                }
                if (!transactions.inTransaction(uow -> run.enqueueNextBatch(uow, 7)).claimed()) {
                    return null;
                }
            }
        });
        for (UUID party : active) {
            assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?",
                    party, version.id().value())).as("one permit per ACTIVE party").isEqualTo(1);
        }
        for (UUID party : pending) {
            assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ?", party))
                    .as("no PENDING party").isZero();
        }
        assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE list_version_id = ?", version.id().value()))
                .as("the census is the version's own count")
                .isEqualTo(count("SELECT enqueued FROM risk.screening_list_version WHERE id = ?", version.id().value()));
        assertThat(count("SELECT count(*) FROM risk.screening_list_version WHERE id = ? AND enqueue_next_at IS NULL"
                + " AND enqueue_completed_at IS NOT NULL", version.id().value())).isEqualTo(1);
    }

    @Test
    @DisplayName("ten sweepers rescreen each permit once: kyc's screening run after the activation, every permit DONE"
            + " CLEARED with exactly one SCREENING_CLEARED signal born of it and one RiskSignalRecorded")
    void tenSweepersRescreenOnce() throws Exception {
        List<UUID> parties = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            parties.add(plantedCustomer("ACTIVE"));
        }
        ScreeningListVersion version = activated(ScreeningList.SANCTIONS);
        enqueueAll();
        for (UUID party : parties) {
            screenedNow(party);
        }
        concurrently(10, () -> {
            Instant deadline = Instant.now().plusSeconds(180);
            while (Instant.now().isBefore(deadline) && concludedOf(version, parties) < parties.size()) {
                driver.driveDue();
            }
            return null;
        });
        for (UUID party : parties) {
            UUID permit = scalarUuid("SELECT id FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ?"
                    + " AND status = 'DONE' AND outcome = 'CLEARED'", party, version.id().value());
            assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ? AND kind ="
                    + " 'SCREENING_CLEARED' AND list_version_ref = ?", permit, version.id().value())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RiskSignalRecorded'"
                    + " AND causation_id = ?", permit.toString())).as("announced once").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a hit alerts once and cases: SCREENING_HIT born of the permit, one CRITICAL SCREENING_HIT alert of the"
            + " MONITORING version's rule attached to one AML case; a re-drive and a PEP hit that hour create no second")
    void aHitAlertsOnceAndCases() throws Exception {
        aMonitoringVersionIsActive();
        UUID party = plantedCustomer("ACTIVE");
        ScreeningListVersion sanctions = activated(ScreeningList.SANCTIONS);
        enqueueAll();
        provider.succeedsWith(ScreeningAdapter.SANCTIONS_PATH, 200, "{\"status\":\"hit\"}");
        screenedNow(party);
        driveUntil(() -> concludedOf(sanctions, List.of(party)) == 1);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE subject_ref = ? AND kind = 'SCREENING_HIT'",
                party.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'SCREENING_HIT'"
                + " AND rule_code = 'SCREENING_HIT' AND severity = 'CRITICAL' AND status = 'ATTACHED'", party.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'", party.toString()))
                .isEqualTo(1);
        driver.driveDue();
        driver.driveDue();

        provider.succeedsWith(ScreeningAdapter.PEP_PATH, 200, "{\"status\":\"hit\"}");
        ScreeningListVersion pep = activated(ScreeningList.PEP);
        enqueueAll();
        screenedNow(party);
        driveUntil(() -> concludedOf(pep, List.of(party)) == 1);
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE subject_ref = ? AND kind = 'SCREENING_HIT'",
                party.toString())).as("one signal per (party, version)").isEqualTo(2);
        boolean sameHour = sanctions.activatedAt().orElseThrow().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .equals(scalarInstant("SELECT activated_at FROM risk.screening_list_version WHERE id = ?",
                        pep.id().value()).truncatedTo(java.time.temporal.ChronoUnit.HOURS));
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'SCREENING_HIT'",
                party.toString())).as("born once per (party, rule, the activation's hour)").isEqualTo(sameHour ? 1 : 2);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'", party.toString()))
                .as("one open AML case per party").isEqualTo(1);
    }

    @Test
    @DisplayName("a second activation of one version is refused: two activators racing leave one activation, the other"
            + " ListVersionStale; one ACTIVE per list")
    void aSecondActivationOfOneVersionIsRefused() throws Exception {
        ScreeningListVersion recorded = recorded(ScreeningList.PEP);
        AtomicInteger stale = new AtomicInteger();
        concurrently(2, () -> {
            try {
                inFlow(() -> transactions.inTransaction(uow -> lists.activate(uow, recorded.id(), "in force",
                        person(), correlation())));
            } catch (ScreeningListVersions.ListVersionStale refused) {
                stale.incrementAndGet();
            }
            return null;
        });
        assertThat(stale).hasValue(1);
        assertThatThrownBy(() -> inFlow(() -> transactions.inTransaction(uow -> lists.activate(uow, recorded.id(),
                "again", person(), correlation())))).isInstanceOf(ScreeningListVersions.ListVersionStale.class);
        assertThat(count("SELECT count(*) FROM risk.screening_list_version WHERE list = 'PEP' AND status = 'ACTIVE'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the ranks beneath the domain, for a raw writer: the persons (a SYSTEM recorder refused), four eyes (the"
            + " recorder as activator refused), a permit for a version not in force refused, nothing deleted")
    void theRanksBeneathTheDomain() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            assertThat(sqlState(app, "INSERT INTO risk.screening_list_version (id, list, version, ordinal, status,"
                    + " recorded_by, recorded_by_type, recorded_at, enqueued, correlation_id) SELECT '" + IDS.next()
                    + "', 'SANCTIONS', 'raw-" + UUID.randomUUID().toString().substring(0, 8) + "', coalesce(max(ordinal), 0)"
                    + " + 1, 'RECORDED', 'platform', 'SYSTEM', now(), 0, 'c' FROM risk.screening_list_version WHERE list"
                    + " = 'SANCTIONS'")).as("a SYSTEM recorder").isEqualTo(CHECK_VIOLATION);
        }
        ScreeningListVersion recorded = recorded(ScreeningList.SANCTIONS);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(sqlState(app, "UPDATE risk.screening_list_version SET status = 'ACTIVE', activated_by = '"
                    + recorded.recordedBy().id() + "', activated_by_type = 'CUSTOMER' WHERE id = '" + recorded.id().value()
                    + "'")).as("four eyes for every writer").isEqualTo(CHECK_VIOLATION);
            assertThat(sqlState(app, "INSERT INTO risk.rescreening_permit (id, party_id, list_version_id, status,"
                    + " attempts, next_attempt_at, enqueued_at, correlation_id) VALUES ('" + IDS.next() + "', '"
                    + IDS.next() + "', '" + recorded.id().value() + "', 'PENDING', 0, now(), now(), 'c')"))
                    .as("a permit for a version not in force").isEqualTo(CHECK_VIOLATION);
            assertThat(sqlState(app, "DELETE FROM risk.screening_list_version WHERE id = '" + recorded.id().value() + "'"))
                    .as("no DELETE for any role").isEqualTo("42501");
        }
        assertThat(scalarText("SELECT recorded_by_type FROM risk.screening_list_version WHERE id = ?",
                recorded.id().value())).as("the operator the session types CUSTOMER").isEqualTo("CUSTOMER");
    }

    // ------------------------------------------------------------------ fixtures

    /** A recorded version of {@code list} by one CUSTOMER-typed person. */
    private ScreeningListVersion recorded(ScreeningList list) {
        return inFlow(() -> transactions.inTransaction(uow -> lists.record(uow, list,
                "v-" + UUID.randomUUID().toString().substring(0, 12), "the provider published a new edition", person(),
                correlation()).version()));
    }

    /** A version recorded by one person and activated by another. */
    private ScreeningListVersion activated(ScreeningList list) {
        ScreeningListVersion recorded = recorded(list);
        return inFlow(() -> transactions.inTransaction(uow -> lists.activate(uow, recorded.id(), "in force", person(),
                correlation()).version()));
    }

    private void aMonitoringVersionIsActive() {
        Actor proposer = new Actor("officer-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        RiskPolicyAdministration.Proposed proposed = inFlow(() -> transactions.inTransaction(uow -> policies.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.MONITORING), "the rescreening suite's version", proposer,
                correlation())));
        inFlow(() -> transactions.inTransaction(uow -> policies.activate(uow, proposed.id(), person(), "activated",
                correlation())));
    }

    private void enqueueAll() {
        while (transactions.inTransaction(uow -> run.enqueueNextBatch(uow, 500)).claimed()) {
            // The next batch.
        }
    }

    private void driveUntil(Callable<Boolean> done) throws Exception {
        Instant deadline = Instant.now().plusSeconds(180);
        while (!done.call()) {
            assertThat(Instant.now()).as("the leg concluded the permit in time").isBefore(deadline);
            driver.driveDue();
        }
    }

    /** kyc's case-bound screening of {@code party}, run now - after the activation - against the simulated provider. */
    private void screenedNow(UUID party) throws SQLException {
        UUID customer = scalarUuid("SELECT id FROM party.customer WHERE party_id = ? AND status = 'ACTIVE'", party);
        KycCase opened;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            opened = cases.openOrConverge(app, KycCase.open(IDS, CLOCK, customer, KycCaseKind.KYC)).kycCase();
            app.commit();
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            runs.runChecks(opened.id(), customer);
        }
    }

    private long concludedOf(ScreeningListVersion version, List<UUID> parties) throws SQLException {
        long done = 0;
        for (UUID party : parties) {
            done += count("SELECT count(*) FROM risk.rescreening_permit WHERE party_id = ? AND list_version_id = ? AND"
                    + " status = 'DONE'", party, version.id().value());
        }
        return done;
    }

    /** A party with a customer relationship in {@code status}; the party. */
    private static UUID plantedCustomer(String status) throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at)"
                    + " VALUES (?, 'PERSON', 'Rescreened Holder', now() - interval '400 day')", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, ?, now() - interval '400 day', now() - interval '400 day')", IDS.next(), party,
                    status);
        }
        return party;
    }

    private static Actor person() {
        return new Actor("officer-" + UUID.randomUUID(), ActorType.CUSTOMER);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static <T> T inFlow(java.util.function.Supplier<T> work) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    private static void concurrently(int threads, Callable<Void> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> running = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                running.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            for (Future<Void> future : running) {
                future.get(300, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String sqlState(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
            return "succeeded";
        } catch (SQLException refused) {
            return refused.getSQLState();
        }
    }

    private static long count(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static UUID scalarUuid(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).as(sql).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static Instant scalarInstant(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        }
    }

    private static String scalarText(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }
}
