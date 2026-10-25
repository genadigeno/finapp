package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.InboxConsumer;
import com.finapp.platform.inbox.InboxKey;
import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.risk.AlertObserver;
import com.finapp.risk.Alerts;
import com.finapp.risk.CaseObserver;
import com.finapp.risk.Cases;
import com.finapp.risk.CollectionsCases;
import com.finapp.risk.JdbcRiskAlertStore;
import com.finapp.risk.JdbcRiskCaseStore;
import com.finapp.risk.JdbcRiskSignalStore;
import com.finapp.risk.MonitoringHints;
import com.finapp.risk.ProfileHints;
import com.finapp.risk.RiskActingParty;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.RiskSubjectParties;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A collections case opened on default, on PostgreSQL across instances (`P13-TSK-025`; owner decision C3;
 * {@code PHASE_13_PLAN.md} sections 3.1, 7.4, 12.8; {@code INV-RSK-06}, {@code INV-RSK-02}, {@code INV-IDEM-04}): the
 * real handler ({@link RiskSignalIntake}, the composition's sources {@code LOAN_DEFAULTED} and {@code BNPL_DEFAULTED})
 * driven through the platform's own {@link InboxConsumer}, so the dedupe row, the signal, the alert and the case share
 * the transaction they share in production, over the real stores, T-sig ({@link RiskSignals} with
 * {@link CollectionsCases}), {@link Alerts} and {@link Cases} - wired as {@code RiskBeans}, {@code RiskAlertBeans} and
 * {@code RiskCaseBeans} wire them. The agreement's party is the accountable join's answer, planted here for the folding
 * cases: the join's own statements are {@code JdbcCreditAgreementPartiesTest}'s and their live read over the published
 * views {@link #theJoinReadsThePublishedViews}'s, and a loan or an obligation born through lending's and bnpl's doors is
 * no part of what this suite proves.
 *
 * <p>The entry's three - {@link #aDefaultOpensOneCase}, {@link #aSecondDefaultOfOnePartyAttaches},
 * {@link #theSameEventTwiceOpensOnce} - and the failure and the money's census beside them. <strong>Written and owed a
 * first run</strong> under the standing criterion-7 deviation (C5). The entry's probe: drop
 * {@code risk_alert_born_once} by in-test DDL - {@code #theSameEventTwiceOpensOnce} red (the delivery past the dedupe
 * raises a second alert, which attaches to the one case).
 */
@Tag("database")
@DisplayName("a collections case opened on default, across instances (P13-TSK-025)")
@SuppressWarnings("try") // correlation Scopes are used for their close side effect
class CollectionsCaseDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Duration RETENTION = Duration.ofDays(14);

    /** The accountable join's answers: agreement -> party. */
    private static final Map<String, UUID> PARTY_OF_AGREEMENT = new ConcurrentHashMap<>();
    private static final RiskSubjectParties SUBJECTS = new RiskSubjectParties() {
        @Override
        public Optional<UUID> partyOf(Connection unitOfWork, SignalSubject subject) {
            return subject.kind() == SubjectKind.PARTY ? Optional.of(UUID.fromString(subject.ref())) : Optional.empty();
        }

        @Override
        public Optional<UUID> accountablePartyOf(Connection unitOfWork, SignalSubject subject) {
            return subject.kind() == SubjectKind.CREDIT_AGREEMENT
                    ? Optional.ofNullable(PARTY_OF_AGREEMENT.get(subject.ref())) : partyOf(unitOfWork, subject);
        }
    };
    private static final RiskActingParty NO_PERSON = (unitOfWork, actor) -> Optional.empty();
    private static final JdbcRiskAlertStore ALERT_STORE = new JdbcRiskAlertStore();
    private static final Cases CASES = new Cases(new JdbcRiskCaseStore(), ALERT_STORE, NO_PERSON, SUBJECTS,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, CaseObserver.NONE);
    private static final Alerts ALERTS = new Alerts(ALERT_STORE, CASES::fold, NO_PERSON, SUBJECTS,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, Spans.NONE, AlertObserver.NONE);
    private static final RiskSignals SIGNALS = new RiskSignals(new JdbcRiskSignalStore(), new JdbcOutboxWriter(), IDS,
            CLOCK, ProfileHints.NONE, new CollectionsCases(ALERTS::raiseAll));

    private final InboxConsumer<Connection> inbox = new InboxConsumer<>(new JdbcInboxRecordStore(), CLOCK, RETENTION);
    private final RiskSignalIntake loans = intake(RiskSignalIntake.Source.LOAN_DEFAULTED);
    private final RiskSignalIntake obligations = intake(RiskSignalIntake.Source.BNPL_DEFAULTED);

    // ------------------------------------------------------------------ the entry's three

    @Test
    @DisplayName("a default opens one case: one signal, one OBLIGATION_DEFAULTED alert ATTACHED to one COLLECTIONS case"
            + " of the borrower's party - opened from the alert, first line, 168 h - announced once each and audited"
            + " risk.CaseOpened under SYSTEM")
    void aDefaultOpensOneCase() throws SQLException {
        UUID party = UUID.randomUUID();
        UUID loan = agreementOf(party);
        ReceivedEvent defaulted = loanDefaulted(EventId.next(IDS), loan, "2026-10-24");

        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(deliver(a, loans, defaulted)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        }

        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ?", defaulted.eventId().value()))
                .isEqualTo(1);
        assertThat(column("SELECT kind || ':' || status || ':' || rule_code || ':' || coalesce(policy_version_id::text,"
                + " 'none') || ':' || window_kind FROM risk.risk_alert WHERE subject_ref = ?", loan.toString()))
                .containsExactly("OBLIGATION_DEFAULTED:ATTACHED:OBLIGATION_DEFAULTED:none:D1");
        assertThat(column("SELECT c.kind || ':' || c.subject_kind || ':' || c.status || ':' || c.queue || ':'"
                + " || c.sla_hours FROM risk.risk_case c WHERE c.subject_ref = ?", party.toString()))
                .containsExactly("COLLECTIONS:PARTY:OPEN:FIRST_LINE:168");
        assertThat(count("SELECT count(*) FROM risk.risk_case c JOIN risk.risk_alert a ON a.id = c.opening_alert_id"
                + " AND a.attached_case_id = c.id WHERE a.subject_ref = ?", loan.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.AlertRaised'"
                + " AND aggregate_id IN (SELECT id FROM risk.risk_alert WHERE subject_ref = ?)", loan.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.CaseOpened'"
                + " AND aggregate_id IN (SELECT id FROM risk.risk_case WHERE subject_ref = ?)", party.toString()))
                .isEqualTo(1);
        assertThat(column("SELECT actor_type FROM platform.audit_record WHERE operation = 'risk.CaseOpened'"
                + " AND target_id IN (SELECT id::text FROM risk.risk_case WHERE subject_ref = ?)", party.toString()))
                .containsExactly("SYSTEM");
    }

    @Test
    @DisplayName("a second default of one party attaches: a loan's and a BNPL obligation's defaults of one borrower,"
            + " delivered at once by two instances - two signals, two alerts, ONE open COLLECTIONS case holding both")
    void aSecondDefaultOfOnePartyAttaches() throws Exception {
        UUID party = UUID.randomUUID();
        UUID loan = agreementOf(party);
        UUID obligation = agreementOf(party);
        ReceivedEvent loanDefault = loanDefaulted(EventId.next(IDS), loan, "2026-10-24");
        ReceivedEvent bnplDefault = bnplDefaulted(EventId.next(IDS), obligation, "2026-10-24");

        List<InboxConsumer.Outcome> outcomes = race(List.of(
                () -> deliverOnAnInstance(loans, loanDefault),
                () -> deliverOnAnInstance(obligations, bnplDefault)));

        assertThat(outcomes).containsOnly(InboxConsumer.Outcome.PROCESSED);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'COLLECTIONS'",
                party.toString())).as("one open per (party, COLLECTIONS)").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert a JOIN risk.risk_case c ON c.id = a.attached_case_id"
                + " WHERE c.subject_ref = ? AND a.kind = 'OBLIGATION_DEFAULTED'", party.toString())).isEqualTo(2);
        assertThat(column("SELECT revision::text FROM risk.risk_case WHERE subject_ref = ?", party.toString()))
                .containsExactly("1");
        assertThat(column("SELECT e.kind FROM risk.risk_case_event e JOIN risk.risk_case c ON c.id = e.case_id"
                + " WHERE c.subject_ref = ? ORDER BY e.revision", party.toString()))
                .containsExactly("OPENED", "ALERT_ATTACHED");
    }

    @Test
    @DisplayName("the same event twice opens once: a redelivery the inbox absorbs, a delivery past the dedupe the"
            + " signal's and the alert's uniques absorb, and ten instances at once - one signal, one alert, one case,"
            + " one AlertRaised, one CaseOpened")
    void theSameEventTwiceOpensOnce() throws Exception {
        UUID party = UUID.randomUUID();
        UUID loan = agreementOf(party);
        ReceivedEvent defaulted = loanDefaulted(EventId.next(IDS), loan, "2026-10-24");

        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer();
                SimulatedInstance b = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(deliver(a, loans, defaulted)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
            assertThat(deliver(b, loans, defaulted)).isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        }
        // Past the dedupe (its retention lapsed, or the record lost): the handler runs again, finds its signal
        // standing, raises over it and meets risk_alert_born_once - nothing new.
        forgetDedupe(defaulted);
        try (SimulatedInstance c = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(deliver(c, loans, defaulted)).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        }
        forgetDedupe(defaulted);
        List<Callable<InboxConsumer.Outcome>> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(() -> deliverOnAnInstance(loans, defaulted));
        }
        assertThat(race(ten)).containsOnlyOnce(InboxConsumer.Outcome.PROCESSED);

        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ?", defaulted.eventId().value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ?", loan.toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ?", party.toString())).isEqualTo(1);
        assertThat(column("SELECT revision::text FROM risk.risk_case WHERE subject_ref = ?", party.toString()))
                .as("nothing attached twice").containsExactly("0");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.AlertRaised'"
                + " AND aggregate_id IN (SELECT id FROM risk.risk_alert WHERE subject_ref = ?)", loan.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.CaseOpened'"
                + " AND aggregate_id IN (SELECT id FROM risk.risk_case WHERE subject_ref = ?)", party.toString()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ the failure and the money

    @Test
    @DisplayName("fail closed: a default whose party cannot be placed is refused whole - no signal, no alert, no case and"
            + " no dedupe record, so the redelivery retries")
    void anUnplaceableBorrowerStallsTheDelivery() throws SQLException {
        UUID orphan = UUID.randomUUID();
        ReceivedEvent defaulted = loanDefaulted(EventId.next(IDS), orphan, "2026-10-24");
        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer()) {
            assertThatThrownBy(() -> deliverWithoutCommit(a, loans, defaulted)).isInstanceOf(IllegalStateException.class);
            a.rollback();
        }
        assertThat(count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ?", defaulted.eventId().value()))
                .isZero();
        assertThat(count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ?", orphan.toString())).isZero();
        assertThat(count("SELECT count(*) FROM platform.inbox_message WHERE consumer = 'risk.signals'"
                + " AND dedupe_key = ?", defaulted.eventId().value().toString())).isZero();
    }

    @Test
    @DisplayName("INV-RSK-02: a default's case moves no money and touches no obligation - every lending, bnpl and ledger"
            + " table holds exactly the rows it held before the delivery")
    void aDefaultsCaseChangesNothingOfTheObligationOrTheLedger() throws SQLException {
        UUID party = UUID.randomUUID();
        UUID loan = agreementOf(party);
        Map<String, Long> before = rowsOfTheMoneyDomains();
        try (SimulatedInstance a = SimulatedInstance.inAgreementWithTheServer()) {
            assertThat(deliver(a, loans, loanDefaulted(EventId.next(IDS), loan, "2026-10-24")))
                    .isEqualTo(InboxConsumer.Outcome.PROCESSED);
        }
        assertThat(count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ?", party.toString())).isEqualTo(1);
        assertThat(rowsOfTheMoneyDomains()).as("not one row of lending, bnpl or the ledger").isEqualTo(before);
    }

    @Test
    @DisplayName("the party join's live read: both statements run as the application role over lending's and bnpl's"
            + " published views - the view, its columns and the grant live - an unknown agreement answering empty; an"
            + " agreement the database holds answering its own party")
    void theJoinReadsThePublishedViews() throws SQLException {
        JdbcCreditAgreementParties join = new JdbcCreditAgreementParties();
        try (Connection application = DatabaseRoles.application()) {
            assertThat(join.partyOfLoan(application, com.finapp.lending.LoanId.of(IDS.next()))).isEmpty();
            assertThat(join.partyOfObligation(application, com.finapp.bnpl.BnplObligationId.of(IDS.next())))
                    .isEmpty();
            for (String view : List.of("lending.party_principal_exposure|loan_id", "bnpl.party_principal_exposure"
                    + "|obligation_id")) {
                String[] named = view.split("\\|");
                try (Statement any = application.createStatement();
                        ResultSet row = any.executeQuery("SELECT " + named[1] + ", party_id FROM " + named[0]
                                + " LIMIT 1")) {
                    if (row.next()) {
                        UUID agreement = row.getObject(1, UUID.class);
                        UUID party = row.getObject(2, UUID.class);
                        assertThat(named[1].equals("loan_id")
                                ? join.partyOfLoan(application, com.finapp.lending.LoanId.of(agreement))
                                : join.partyOfObligation(application, com.finapp.bnpl.BnplObligationId.of(agreement)))
                                .as("an agreement the database holds answers its own party").contains(party);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------

    private static RiskSignalIntake intake(RiskSignalIntake.Source source) {
        return new RiskSignalIntake(source, SIGNALS, MonitoringHints.NONE, IDS, RiskFlowScope.NONE);
    }

    private static UUID agreementOf(UUID party) {
        UUID agreement = IDS.next();
        PARTY_OF_AGREEMENT.put(agreement.toString(), party);
        return agreement;
    }

    private InboxConsumer.Outcome deliverOnAnInstance(RiskSignalIntake intake, ReceivedEvent event) throws SQLException {
        try (SimulatedInstance instance = SimulatedInstance.inAgreementWithTheServer()) {
            return deliver(instance, intake, event);
        }
    }

    /** One delivery, exactly as the shell drives it: the dedupe record and the handler in one transaction, committed. */
    private InboxConsumer.Outcome deliver(SimulatedInstance instance, RiskSignalIntake intake, ReceivedEvent event)
            throws SQLException {
        InboxConsumer.Outcome outcome = deliverWithoutCommit(instance, intake, event);
        if (outcome == InboxConsumer.Outcome.CONTENDED) {
            instance.rollback();
        } else {
            instance.commit();
        }
        return outcome;
    }

    private InboxConsumer.Outcome deliverWithoutCommit(SimulatedInstance instance, RiskSignalIntake intake,
            ReceivedEvent event) {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(
                new Correlation(event.correlationId(), CausationId.of(event.eventId().value().toString())))) {
            return inbox.consume(instance.connection(), new InboxKey(intake.consumerName(),
                    event.eventId().value().toString()), event.eventType(),
                    connection -> intake.handle(connection, event));
        }
    }

    private static List<InboxConsumer.Outcome> race(List<Callable<InboxConsumer.Outcome>> deliveries)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(deliveries.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<InboxConsumer.Outcome>> futures = new ArrayList<>();
        try {
            for (Callable<InboxConsumer.Outcome> delivery : deliveries) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return delivery.call();
                }));
            }
            go.countDown();
            List<InboxConsumer.Outcome> outcomes = new ArrayList<>();
            for (Future<InboxConsumer.Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ReceivedEvent loanDefaulted(EventId eventId, UUID loan, String businessDate) {
        return new ReceivedEvent(eventId, "lending.LoanDefaulted", 1, 1, loan, "loan", Instant.now(CLOCK), "lending",
                CorrelationId.of(UUID.randomUUID().toString()), CausationId.of("p13-tsk-025"), "application/json",
                condition("loanId", loan, businessDate).toBytes());
    }

    private static ReceivedEvent bnplDefaulted(EventId eventId, UUID obligation, String businessDate) {
        return new ReceivedEvent(eventId, "bnpl.BnplDefaulted", 1, 1, obligation, "bnpl_obligation", Instant.now(CLOCK),
                "bnpl", CorrelationId.of(UUID.randomUUID().toString()), CausationId.of("p13-tsk-025"),
                "application/json", condition("obligationId", obligation, businessDate).toBytes());
    }

    private static EventPayload condition(String idField, UUID id, String businessDate) {
        return EventPayload.of()
                .with(idField, id.toString())
                .with("product", "PERSONAL_LOAN")
                .with("businessDate", businessDate)
                .with("daysPastDue", "91")
                .with("bucket", "B4_90_PLUS")
                .with("defaulted", "true")
                .with("agreementVersion", "1");
    }

    private static void forgetDedupe(ReceivedEvent event) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement forget = migrator.prepareStatement(
                        "DELETE FROM platform.inbox_message WHERE consumer = ? AND dedupe_key = ?")) {
            forget.setString(1, RiskSignalIntake.CONSUMER);
            forget.setString(2, event.eventId().value().toString());
            assertThat(forget.executeUpdate()).as("the dedupe record existed").isEqualTo(1);
        }
    }

    /** Every base table of lending, bnpl and the ledger, and its row count. */
    private static Map<String, Long> rowsOfTheMoneyDomains() throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Connection migrator = DatabaseRoles.migrator(); Statement list = migrator.createStatement();
                ResultSet tables = list.executeQuery("SELECT table_schema || '.' || table_name FROM"
                        + " information_schema.tables WHERE table_schema IN ('lending', 'bnpl', 'ledger')"
                        + " AND table_type = 'BASE TABLE' ORDER BY 1")) {
            List<String> names = new ArrayList<>();
            while (tables.next()) {
                names.add(tables.getString(1));
            }
            assertThat(names).as("the census reads every money domain").isNotEmpty();
            for (String table : names) {
                try (Statement count = migrator.createStatement();
                        ResultSet counted = count.executeQuery("SELECT count(*) FROM " + table)) {
                    counted.next();
                    rows.put(table, counted.getLong(1));
                }
            }
        }
        return rows;
    }

    private static long count(String sql, Object argument) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement count = migrator.prepareStatement(sql)) {
            count.setObject(1, argument);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static List<String> column(String sql, Object argument) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setObject(1, argument);
            try (ResultSet rows = select.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }
}
