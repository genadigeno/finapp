package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.AlertObserver;
import com.finapp.risk.Alerts;
import com.finapp.risk.CaseObserver;
import com.finapp.risk.Cases;
import com.finapp.risk.JdbcCustomerRiskProfileStore;
import com.finapp.risk.JdbcMonitoredTransactionStore;
import com.finapp.risk.JdbcMonitoringWatermarkStore;
import com.finapp.risk.JdbcRiskAlertStore;
import com.finapp.risk.JdbcRiskCaseStore;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.JdbcRiskSignalStore;
import com.finapp.risk.JdbcVelocityWindowStore;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoredMovements;
import com.finapp.risk.MonitoringIntake;
import com.finapp.risk.MonitoringObserver;
import com.finapp.risk.MonitoringReprocessing;
import com.finapp.risk.MonitoringRun;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.ProfileInputs;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPartyIdentities;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.SignalReadings;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The monitoring rules over real postings (`P13-TSK-020`; {@code PHASE_13_PLAN.md} section 7.4, scenarios 8 and 12;
 * ADR-0111 decision 3; {@code INV-RSK-06}, {@code INV-RSK-09}, {@code INV-RSK-04}; the intake suite's idiom - no Spring
 * context, the stores composed by hand on the application role): parties planted with a customer and a wallet
 * agreement, their wallets' money moved through {@code PostingService}, the journal read by {@link LedgerMonitoredLedger},
 * ingested by {@link MonitoringIntake} with {@link MonitoringRun} as its rule leg in the claim's own transaction, each
 * account placed behind its party by the production join {@link AccountMonitoredParties}; the alerts, the cases and the
 * profiles real rows.
 *
 * <p>Written and owed under C5 (the standing criterion-7 deviation): the database tier is not run per task. The
 * {@code MONITORING} version is the v1 fixture, activated once for the suite (its predecessor, if any, retired beside
 * it); every case uses parties of its own.
 *
 * <p><strong>Its own container</strong> ({@code own-container}, P13-DOC-001's database pass): the {@code MONITORING}
 * version it activates judges every profile recompute in the database, and its drains run the rule leg over the WHOLE
 * journal from the one global watermark - so in a shared container it judges every other suite's movements, and meets
 * their rows: the pass's re-run failed four of its cases on another suite's customer account whose ledger
 * {@code owner_ref} is not a UUIDv7, which the rule leg's account-to-party join refuses (ADR-0013) - green alone.
 */
@Tag("database")
@Tag("own-container")
@DisplayName("the monitoring rules over real postings (P13-TSK-020)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class MonitoringRulesDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final String SOURCE = MonitoringWatermark.LEDGER_JOURNAL;
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();

    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final JournalEntryStore<Connection> journal = new JdbcJournalEntryStore(IDS);
    private final MonitoredLedger ledger = new LedgerMonitoredLedger(journal, ledgerAccounts);
    private final JdbcMonitoringWatermarkStore watermarks = new JdbcMonitoringWatermarkStore();
    private final JdbcMonitoredTransactionStore monitored = new JdbcMonitoredTransactionStore();
    private final JdbcCustomerAccountStore agreements = new JdbcCustomerAccountStore();
    private final AccountMonitoredParties parties = new AccountMonitoredParties(new JdbcPartyStore(), agreements);
    private final MonitoringReprocessing reprocessing =
            new MonitoringReprocessing(watermarks, new JdbcAuditWriter(), IDS, CLOCK);

    private static LocalDate today;

    @BeforeAll
    static void aMonitoringVersionIsActive() throws SQLException {
        RiskPolicyAdministration administration = new RiskPolicyAdministration(POLICY_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK);
        RiskPolicyAdministration.Proposed proposed = inTransaction(uow -> administration.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.MONITORING), "the monitoring rules suite's version", employee(),
                correlation()));
        inTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated", correlation()));
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(
                "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date"); ResultSet row = select.executeQuery()) {
            row.next();
            today = row.getObject(1, LocalDate.class);
        }
    }

    @Test
    @Order(1)
    @DisplayName("scenario 12: a structuring sequence posted over three claims of one value date is ONE alert -"
            + " risk_alert_born_once - named by its three entries, and a fourth movement of the day changes nothing")
    void aStructuringSequenceAlertsOncePerWindow() throws Exception {
        Holder holder = holder();
        Holder sink = holder();
        LocalDate day = today.minusDays(3);
        UUID first = transfer(holder, sink, 900_00, day);
        UUID second = transfer(holder, sink, 950_00, day);
        drain(run(active()));
        assertThat(alerts(holder.party(), "STRUCTURING")).as("two movements: no sequence yet").isZero();

        UUID third = transfer(holder, sink, 800_00, day);
        drain(run(active()));
        transfer(holder, sink, 700_00, day);
        drain(run(active()));
        assertThat(alerts(holder.party(), "STRUCTURING")).as("once per window").isEqualTo(1);
        assertThat(column("SELECT kind || ':' || severity || ':' || subject_kind || ':' || window_kind || ':'"
                + " || (window_start = (CAST(? AS date))::timestamp AT TIME ZONE 'UTC') FROM risk.risk_alert"
                + " WHERE subject_ref = ? AND rule_code = 'STRUCTURING'", day.toString(), holder.party().toString()))
                .containsExactly("AML:HIGH:PARTY:D1:true");
        assertThat(column("SELECT unnest(evidence_journal_entry_ids)::text FROM risk.risk_alert WHERE subject_ref = ?"
                + " AND rule_code = 'STRUCTURING'", holder.party().toString()))
                .containsExactly(first.toString(), second.toString(), third.toString());
    }

    @Test
    @Order(2)
    @DisplayName("every alert of a party folds into ONE open AML case: structuring, rapid in-out and a round amount of"
            + " one day - the case's one-open unique and the fold (R4, then R5)")
    void everyAlertFoldsIntoOneCase() throws Exception {
        Holder holder = holder();
        Holder source = holder();
        Holder sink = holder();
        LocalDate day = today.minusDays(2);
        transfer(source, holder, 1_000_00, day);
        transfer(holder, sink, 900_00, day);
        transfer(holder, sink, 950_00, day);
        transfer(holder, sink, 800_00, day);
        drain(run(active()));
        assertThat(column("SELECT rule_code FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'AML'"
                + " ORDER BY rule_code", holder.party().toString()))
                .containsExactly("RAPID_IN_OUT", "ROUND_AMOUNT", "STRUCTURING");
        assertThat(count("SELECT count(DISTINCT attached_case_id) FROM risk.risk_alert WHERE subject_ref = ?"
                + " AND kind = 'AML' AND status = 'ATTACHED'", holder.party().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'",
                holder.party().toString())).as("one open AML case").isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("no ACTIVE MONITORING version: the movements are ingested, nothing is raised and no head enrolled")
    void noActiveMonitoringPolicyRaisesNothing() throws Exception {
        Holder holder = holder();
        Holder sink = holder();
        LocalDate day = today.minusDays(1);
        transfer(holder, sink, 900_00, day);
        transfer(holder, sink, 950_00, day);
        transfer(holder, sink, 800_00, day);
        drain(run(new RiskPolicies(noActive())));
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction WHERE subject_ref = ?",
                holder.account())).as("the record is the journal's still").isEqualTo(3);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ?", holder.party().toString()))
                .isZero();
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile WHERE party_id = ?",
                holder.party())).as("no head enrolled").isZero();
    }

    @Test
    @Order(4)
    @DisplayName("scenario 8: reprocessing from before the suite's first entry re-reads and re-judges it - no new"
            + " monitored transaction, alert, case or profile version")
    void reprocessingRaisesNoSecondAlert() throws Exception {
        Holder holder = holder();
        Holder sink = holder();
        LocalDate day = today.minusDays(4);
        UUID first = transfer(holder, sink, 900_00, day);
        transfer(holder, sink, 950_00, day);
        transfer(holder, sink, 800_00, day);
        drain(run(active()));
        long rows = count("SELECT count(*) FROM risk.monitored_transaction", null);
        long alertCount = count("SELECT count(*) FROM risk.risk_alert", null);
        long cases = count("SELECT count(*) FROM risk.risk_case", null);
        long versions = count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = ?",
                holder.party());
        assertThat(alerts(holder.party(), "STRUCTURING")).isEqualTo(1);

        CorrelationId correlation = CorrelationId.generate(IDS);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation))) {
            inTransaction(uow -> reprocessing.order(uow, SOURCE, MonitoringWatermark.sequenceOf(first) - 1,
                    new Actor("risk-officer-020", ActorType.EMPLOYEE), "P13-TSK-020: scenario 8 - the rules re-run",
                    correlation));
        }
        drain(run(active()));
        assertThat(count("SELECT count(*) FROM risk.monitored_transaction", null)).isEqualTo(rows);
        assertThat(count("SELECT count(*) FROM risk.risk_alert", null)).as("no second alert").isEqualTo(alertCount);
        assertThat(count("SELECT count(*) FROM risk.risk_case", null)).isEqualTo(cases);
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = ?",
                holder.party())).isEqualTo(versions);
    }

    @Test
    @Order(5)
    @DisplayName("two sweepers over one party - the rule leg re-judging its rows and the profile permit's recompute -"
            + " serialise on its head (R8) and raise ONE alert and ONE case")
    void twoSweepersOneSubjectRaiseOnce() throws Exception {
        Holder holder = holder();
        Holder sink = holder();
        transfer(holder, sink, 900_00, today);
        transfer(holder, sink, 950_00, today);
        transfer(holder, sink, 800_00, today);
        MonitoringIntake intake = intake(50);
        // Ingested with no rule leg - the two sweepers below judge the party.
        drainWith(intake, MonitoringIntake.BatchLeg.NONE);
        List<com.finapp.risk.MonitoredTransaction> rows = new ArrayList<>();
        // The party's rows as the intake bore them, re-classified exactly as the claim would hand them over.
        for (UUID entry : holder.entries()) {
            rows.add(classified(entry));
        }
        MonitoringRun run = run(active());
        ProfileRecompute recompute = recompute(active());
        List<Callable<?>> sweepers = new ArrayList<>();
        sweepers.add(() -> asSystem(() -> inTransaction(uow -> run.judge(uow, rows))));
        sweepers.add(() -> asSystem(() -> inTransaction(uow -> recompute.recompute(uow, holder.party(),
                CorrelationId.generate(IDS)))));
        List<Object> outcomes = atOnce(sweepers);
        assertThat(outcomes).as("both sweepers completed - the second waited on the head, never failed").hasSize(2);
        assertThat(alerts(holder.party(), "STRUCTURING")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'",
                holder.party().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = ?",
                holder.party())).as("one rating, one version: the second judge re-read the head after the first")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ the composition

    private RiskPolicies active() {
        return new RiskPolicies(POLICY_STORE);
    }

    private ProfileRecompute recompute(RiskPolicies policies) {
        JdbcRiskAlertStore alertStore = new JdbcRiskAlertStore();
        RiskSubjectParties placed = (unitOfWork, subject) -> subject.kind() == SubjectKind.PARTY
                ? Optional.of(UUID.fromString(subject.ref())) : Optional.empty();
        Cases cases = new Cases(new JdbcRiskCaseStore(), alertStore, (unitOfWork, actor) -> Optional.empty(), placed,
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, CaseObserver.NONE);
        Alerts alerts = new Alerts(alertStore, cases::fold, (unitOfWork, actor) -> Optional.empty(), placed,
                new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
        VelocityWindows windows = new VelocityWindows(new JdbcVelocityWindowStore());
        ProfileInputs inputs = new ProfileInputs((unitOfWork, party) -> Optional.empty(),
                new SignalReadings(new JdbcRiskSignalStore()), RiskPartyIdentities.NONE, windows,
                new MonitoredMovements(monitored, parties, windows));
        return new ProfileRecompute(policies, new JdbcCustomerRiskProfileStore(), inputs, alerts::raiseAll,
                new JdbcOutboxWriter(), IDS, CLOCK, Duration.ofDays(1), MonitoringObserver.NONE);
    }

    private MonitoringRun run(RiskPolicies policies) {
        return new MonitoringRun(policies, parties, recompute(policies), IDS, CLOCK, Spans.NONE,
                MonitoringObserver.NONE);
    }

    private MonitoringIntake intake(int batch) {
        return new MonitoringIntake(ledger, watermarks, monitored, IDS, Duration.ZERO, batch, Duration.ofMillis(1));
    }

    private void drain(MonitoringRun run) {
        drainWith(intake(50), run);
    }

    /** Claims until one reads nothing - the cap bounds a shared journal other suites filled. */
    private void drainWith(MonitoringIntake intake, MonitoringIntake.BatchLeg leg) {
        // A zero horizon reads an entry once the DATABASE's clock is past its minting millisecond (the instance's): wait
        // for that before the first empty read can end the drain (P13-DOC-001's database pass - the JVM ran ahead).
        DatabaseClock.awaitPastNow();
        try (SecurityContext.Scope actor = SecurityContext.enter(SYSTEM);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            for (int i = 0; i < 2_000; i++) {
                MonitoringIntake.Batch batch = inTransaction(uow -> intake.sweepBatch(uow, SOURCE, leg));
                if (!batch.claimed()) {
                    sleep();
                    continue;
                }
                if (batch.read() == 0) {
                    return;
                }
            }
        }
    }

    /** The monitored row of {@code entry}, read back from the journal and classified as the intake does. */
    private com.finapp.risk.MonitoredTransaction classified(UUID entry) {
        DatabaseClock.awaitPastNow();
        return inTransaction(uow -> {
            List<com.finapp.risk.MonitoredEntry> read = ledger.entriesAfter(uow, previous(entry), Duration.ZERO, 1);
            return com.finapp.risk.MonitoringClassification.classify(
                    com.finapp.risk.MonitoredTransactionId.next(IDS), read.getFirst());
        });
    }

    /** The UUID immediately before {@code id} in PostgreSQL's order - the keyset position just behind it. */
    private static UUID previous(UUID id) {
        long least = id.getLeastSignificantBits();
        long most = id.getMostSignificantBits();
        return least == 0 ? new UUID(most - 1, -1L) : new UUID(most, least - 1);
    }

    // ------------------------------------------------------------------ parties and postings

    /** A party, its customer and its wallet agreement - the agreement's id the wallet's owner_ref. */
    private record Holder(UUID party, UUID account, LedgerAccount wallet, List<UUID> entries) {}

    private Holder holder() {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        return inTransaction(app -> {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at)"
                    + " VALUES (?, 'PERSON', 'Monitored Holder', now() - interval '400 day')", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'ACTIVE', now() - interval '400 day', now() - interval '400 day')",
                    customer, party);
            CustomerAccount agreement = agreements.openOrConverge(app,
                    CustomerAccount.open(IDS, CLOCK, customer, ProductType.WALLET)).account();
            LedgerAccount wallet = ledgerAccounts.createOrConverge(app, LedgerAccount.owned(IDS, CLOCK,
                    AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR, agreement.id().value())).account();
            return new Holder(party, agreement.id().value(), wallet, new ArrayList<>());
        });
    }

    /** A transfer-shaped entry from {@code from}'s wallet to {@code to}'s on {@code valueDate}; its entry id. */
    private UUID transfer(Holder from, Holder to, long minor, LocalDate valueDate) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(SYSTEM)) {
            UUID id = inTransaction(uow -> service().post(uow, new PostingCommand("transfer:" + IDS.next(),
                    LocalDate.now(CLOCK), valueDate, "monitoring-rules-probe", List.of(
                            new JournalLine(from.wallet().id(), Direction.DEBIT, Money.ofMinorUnits(minor, EUR)),
                            new JournalLine(to.wallet().id(), Direction.CREDIT, Money.ofMinorUnits(minor, EUR)))))
                    .entryId().value());
            from.entries().add(id);
            return id;
        }
    }

    private PostingService service() {
        return new PostingService(
                new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                journal, new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new com.finapp.ledger.JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    // ------------------------------------------------------------------ plumbing

    private static RiskPolicyStore noActive() {
        return (RiskPolicyStore) Proxy.newProxyInstance(MonitoringRulesDatabaseTest.class.getClassLoader(),
                new Class<?>[] {RiskPolicyStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "shareActiveOnce" -> Optional.empty();
                    case "lockOperationShared" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private long alerts(UUID party, String rule) throws SQLException {
        return count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND rule_code = '" + rule + "'",
                party.toString());
    }

    private static <T> T asSystem(Callable<T> work) throws Exception {
        try (SecurityContext.Scope actor = SecurityContext.enter(SYSTEM);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.call();
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static void sleep() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <R> R inTransaction(Function<Connection, R> work) {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                R result = work.apply(app);
                app.commit();
                return result;
            } catch (RuntimeException failure) {
                app.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code parameter} bound as given: a {@code String} for risk's text references, a {@code UUID} for a uuid column. */
    private static long count(String sql, Object parameter) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            if (parameter != null) {
                select.setObject(1, parameter);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    /** Every parameter bound as text - risk's subject references are text, and the date is cast in the SQL. */
    private static List<String> column(String sql, String... parameters) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setString(i + 1, parameters[i]);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    values.add(row.getString(1));
                }
            }
            return values;
        }
    }

    private static List<Object> atOnce(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<?> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
