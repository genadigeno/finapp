package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The regulatory report against a real database (`P13-TSK-021`; {@code risk V016}; {@code PHASE_13_PLAN.md} sections 7.4,
 * 14; {@code INV-RSK-06}, {@code INV-RSK-07}, {@code INV-RSK-08}): the entry's six cases - the approver is not the drafter
 * (the domain, and {@code regulatory_report_four_eyes} alone); {@code FILED} is immutable (the grant refuses the
 * application, the trigger every writer); a lost filing response inquired, never re-filed; one open draft per case under
 * ten drafters; ten filers file once; ten inquirers acknowledge once - and the persons {@code CUSTOMER}-typed throughout,
 * the platform refused as a person by the {@code CHECK}. The authority is a synchronised fake keyed by the filing reference
 * as a real FIU is (the simulated adapter is {@code app}'s {@code SimulatedFilingProviderTest}). Every case keys on fresh
 * ids. <strong>Written and owed</strong> (owner decision C5).
 */
@Tag("database")
@DisplayName("the regulatory report against the database: four eyes by CHECK, immutable, filed once, inquired never"
        + " re-filed (P13-TSK-021)")
class RegulatoryReportDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcRiskCaseStore CASE_STORE = new JdbcRiskCaseStore();
    private static final Map<String, UUID> PARTY_OF = new ConcurrentHashMap<>();
    private static final Cases CASES = new Cases(CASE_STORE, new JdbcRiskAlertStore(),
            (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id())), RiskSubjectParties.NONE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, CaseObserver.NONE);
    private static final Duration LEASE = Duration.ofMillis(300);
    private static final Duration INTERVAL = Duration.ofMillis(300);
    private static final String CHECK_VIOLATION = "23514";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final Authority authority = new Authority();
    private final RegulatoryReports reports = new RegulatoryReports(new JdbcRegulatoryReportStore(), CASES, CASE_STORE,
            new JdbcRiskAlertStore(), MonitoredParties.NONE,
            (uow, actor) -> Optional.ofNullable(PARTY_OF.get(actor.id())), authority, new JdbcAuditWriter(),
            new JdbcOutboxWriter(), IDS, CLOCK, ReportObserver.NONE);

    @Test
    @DisplayName("the approver is not the drafter: the domain refuses the drafter, and regulatory_report_four_eyes alone"
            + " refuses the migrator's approval naming the drafter")
    void theApproverIsNotTheDrafter() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        Actor drafter = officer();
        RegulatoryReport drafted = draft(caseId, drafter);
        assertThatThrownBy(() -> inOneTransaction(uow -> reports.approve(uow, caseId, drafted.id(), "mine", drafter,
                correlation()))).isInstanceOf(RegulatoryReports.SelfApprovalRefused.class);
        // In a transaction of its own (P13-DOC-001, the review's A8-3): on an autocommit connection the implicit commit
        // fires V016's deferred history trigger, which raises 23514 too - a probe that would pass with the CHECK gone.
        // The statement itself is refused, by the CHECK by name, before any commit.
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            refusedBy(migrator, "UPDATE risk.regulatory_report SET status = 'APPROVED', approved_by = drafted_by,"
                    + " approved_by_type = drafted_by_type WHERE id = '" + drafted.id().value() + "'", CHECK_VIOLATION,
                    "regulatory_report_four_eyes");
            migrator.rollback();
        }
        assertThat(inOneTransaction(uow -> reports.approve(uow, caseId, drafted.id(), "accurate", officer(),
                correlation())).report().status()).isEqualTo(RegulatoryReportStatus.APPROVED);
    }

    @Test
    @DisplayName("FILED is immutable: the application cannot UPDATE a content column (not granted), and the trigger refuses"
            + " the migrator's edit of the narrative, the jurisdiction or the transactions")
    void filedIsImmutable() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        RegulatoryReport filed = file(caseId, approve(caseId, draft(caseId, officer())));
        String where = " WHERE id = '" + filed.id().value() + "'";
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE risk.regulatory_report SET narrative = 'rewritten'" + where,
                    INSUFFICIENT_PRIVILEGE);
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            for (String edit : List.of("narrative = 'rewritten'", "jurisdiction = 'EU-OTHER'",
                    "transaction_ids = ARRAY[gen_random_uuid()]", "ground_codes = ARRAY['STRUCTURING']")) {
                refused(migrator, "UPDATE risk.regulatory_report SET " + edit + where, CHECK_VIOLATION);
            }
            refused(migrator, "DELETE FROM risk.regulatory_report" + where, "P0001");
        }
        assertThat(column("SELECT narrative FROM risk.regulatory_report" + where))
                .containsExactly("Funds were split below the threshold and moved out within a day.");
    }

    @Test
    @DisplayName("a lost filing response is inquired, never re-filed: the authority received it and the answer was lost;"
            + " FILED stays with its permit; past the lease the drive inquires by the reference - one submission")
    void aLostFilingResponseIsInquiredNeverReFiled() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        RegulatoryReport filed = file(caseId, approve(caseId, draft(caseId, officer())));
        authority.loseNextAnswer = true;
        assertThatThrownBy(() -> drive(filed.id())).hasMessageContaining("lost");
        assertThat(column("SELECT status || ',' || attempts FROM risk.regulatory_report WHERE id = '"
                + filed.id().value() + "'")).containsExactly("FILED,1");
        Thread.sleep(LEASE.toMillis() + 100);
        assertThat(drive(filed.id()).orElseThrow().status()).isEqualTo(RegulatoryReportStatus.ACKNOWLEDGED);
        assertThat(authority.files()).as("one submission").hasSize(1);
        assertThat(authority.inquiries()).containsExactly(filed.filingReference());
    }

    @Test
    @DisplayName("one open draft per case: ten drafters of one case - one DRAFT (the case's lock, the one-live unique),"
            + " nine refused; a REJECTED filing then admits a new draft")
    void oneOpenDraftPerCase() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        List<Object> answers = race(10, () -> {
            try {
                return draft(caseId, officer());
            } catch (RegulatoryReports.ReportNotFileable refused) {
                return refused;
            }
        });
        assertThat(answers).filteredOn(RegulatoryReport.class::isInstance).hasSize(1);
        assertThat(count("SELECT count(*) FROM risk.regulatory_report WHERE case_id = '" + caseId.value() + "'"))
                .isEqualTo(1);
        RegulatoryReport first = (RegulatoryReport) answers.stream().filter(RegulatoryReport.class::isInstance)
                .findFirst().orElseThrow();
        authority.rejectNext = true;
        file(caseId, approve(caseId, first));
        assertThat(drive(first.id()).orElseThrow().status()).isEqualTo(RegulatoryReportStatus.REJECTED);
        assertThat(draft(caseId, officer()).status()).isEqualTo(RegulatoryReportStatus.DRAFT);
        assertThat(count("SELECT count(*) FROM risk.risk_case_action WHERE case_id = '" + caseId.value()
                + "' AND kind = 'REPORT_DRAFTED'")).isEqualTo(2);
    }

    @Test
    @DisplayName("ten approvers of one draft, each a second person: one DRAFT -> APPROVED (the row lock, the conditional"
            + " edge), nine refused; one history row, one risk.ReportApproved")
    void tenApproversApproveOnce() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        RegulatoryReport drafted = draft(caseId, officer());
        List<Object> answers = race(10, () -> {
            try {
                return inOneTransaction(uow -> reports.approve(uow, caseId, drafted.id(), "accurate", officer(),
                        correlation()));
            } catch (RegulatoryReports.ReportNotFileable refused) {
                return refused;
            }
        });
        assertThat(answers).filteredOn(RegulatoryReports.Decided.class::isInstance).hasSize(1);
        assertThat(count("SELECT count(*) FROM risk.regulatory_report_event WHERE report_id = '" + drafted.id().value()
                + "' AND status = 'APPROVED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ReportApproved'"
                + " AND target_id = '" + drafted.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten filers of one approved report: one FILED (one history row, one risk.RegulatoryReportFiled), each"
            + " door's drive after its commit - one claim, one submission")
    void tenFilersFileOnce() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        RegulatoryReport approved = approve(caseId, draft(caseId, officer()));
        List<Object> answers = race(10, () -> {
            Actor filer = officer();
            try {
                RegulatoryReports.Decided decided = inOneTransaction(uow -> reports.file(uow, caseId, approved.id(),
                        "file", filer, correlation()));
                drive(approved.id());
                return decided;
            } catch (RegulatoryReports.ReportNotFileable refused) {
                drive(approved.id());
                return refused;
            }
        });
        assertThat(answers).filteredOn(RegulatoryReports.Decided.class::isInstance).hasSize(1);
        assertThat(authority.files()).hasSize(1);
        assertThat(count("SELECT count(*) FROM risk.regulatory_report_event WHERE report_id = '" + approved.id().value()
                + "' AND status = 'FILED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'risk.RegulatoryReportFiled'"
                + " AND aggregate_id = '" + approved.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten inquirers of one FILED report whose first answer was UNKNOWN: one claim per permit, one"
            + " acknowledgement, one risk.ReportAcknowledged as the platform")
    void tenInquirersAcknowledgeOnce() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        RegulatoryReport filed = file(caseId, approve(caseId, draft(caseId, officer())));
        authority.unknownNext = true;
        assertThat(drive(filed.id()).orElseThrow().status()).isEqualTo(RegulatoryReportStatus.FILED);
        Thread.sleep(INTERVAL.toMillis() + 100);
        // Ten inquiry legs at once over this report (claimed by its id: another suite's due report is not this race's).
        List<Optional<RegulatoryReport>> drives = race(10, () -> asSystem(() -> inOneTransaction(uow ->
                reports.claim(uow, filed.id(), Duration.ofMinutes(2)))).flatMap(claimed -> {
                    FilingOutcome outcome = reports.submit(claimed);
                    return asSystem(() -> inOneTransaction(uow -> reports.conclude(uow, claimed, outcome, INTERVAL,
                            correlation())));
                }));
        assertThat(drives).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ReportAcknowledged'"
                + " AND target_id = '" + filed.id().value() + "' AND actor_type = 'SYSTEM'")).isEqualTo(1);
        assertThat(column("SELECT status FROM risk.regulatory_report WHERE id = '" + filed.id().value() + "'"))
                .containsExactly("ACKNOWLEDGED");
    }

    @Test
    @DisplayName("the widened persons: CUSTOMER-typed drafter, approver and filer admitted; the platform as an approver"
            + " refused by regulatory_report_by_persons alone; the authority's answer recorded as SYSTEM")
    void theWidenedPersons() throws Exception {
        RiskCaseId caseId = confirmedSuspicious();
        RegulatoryReport drafted = draft(caseId, officer());
        try (Connection migrator = DatabaseRoles.migrator()) {
            refused(migrator, "UPDATE risk.regulatory_report SET status = 'APPROVED', approved_by = 'system',"
                    + " approved_by_type = 'SYSTEM' WHERE id = '" + drafted.id().value() + "'", CHECK_VIOLATION);
        }
        RegulatoryReport filed = file(caseId, approve(caseId, drafted));
        drive(filed.id());
        assertThat(column("SELECT drafted_by_type || ',' || approved_by_type || ',' || filed_by_type FROM"
                + " risk.regulatory_report WHERE id = '" + filed.id().value() + "'"))
                .containsExactly("CUSTOMER,CUSTOMER,CUSTOMER");
        assertThat(column("SELECT status || ',' || actor_type FROM risk.regulatory_report_event WHERE report_id = '"
                + filed.id().value() + "' ORDER BY recorded_at")).containsExactly("DRAFT,CUSTOMER",
                "APPROVED,CUSTOMER", "FILED,CUSTOMER", "ACKNOWLEDGED,SYSTEM");
    }

    // ------------------------------------------------------------------ fixtures

    /** An AML case of a fresh party opened by hand, taken, and disposed CONFIRMED_SUSPICIOUS by two analysts. */
    private RiskCaseId confirmedSuspicious() {
        Actor alice = officer();
        Actor bob = officer();
        RiskCase opened = inOneTransaction(uow -> CASES.open(uow, Cases.OpenRequest.of(CaseKind.AML,
                SignalSubject.of(SubjectKind.PARTY, IDS.next())), "a pattern", alice, correlation()).riskCase());
        RiskCase taken = inOneTransaction(uow -> CASES.take(uow, opened.id(), opened.revision(), alice, correlation())
                .riskCase());
        Cases.Decided proposed = inOneTransaction(uow -> CASES.proposeDisposition(uow, taken.id(), taken.revision(),
                "CONFIRMED_SUSPICIOUS", false, "confirmed", alice, correlation()));
        inOneTransaction(uow -> CASES.approveDisposition(uow, taken.id(), proposed.dispositionId(),
                proposed.riskCase().revision(), "agreed", bob, correlation()));
        return taken.id();
    }

    private RegulatoryReport draft(RiskCaseId caseId, Actor drafter) {
        return inOneTransaction(uow -> reports.draft(uow, caseId, new RegulatoryReports.Draft("EU-REF",
                "Funds were split below the threshold and moved out within a day.", List.of(), "confirmed"), drafter,
                correlation()));
    }

    private RegulatoryReport approve(RiskCaseId caseId, RegulatoryReport drafted) {
        return inOneTransaction(uow -> reports.approve(uow, caseId, drafted.id(), "accurate", officer(), correlation()))
                .report();
    }

    private RegulatoryReport file(RiskCaseId caseId, RegulatoryReport approved) {
        return inOneTransaction(uow -> reports.file(uow, caseId, approved.id(), "file", officer(), correlation()))
                .report();
    }

    /** The drive as the platform: the claim committed, the call on no connection, the record committed. */
    private Optional<RegulatoryReport> drive(RegulatoryReportId id) {
        return asSystem(() -> inOneTransaction(uow -> reports.claim(uow, id, LEASE))).flatMap(claimed -> {
            FilingOutcome outcome = reports.submit(claimed);
            return asSystem(() -> inOneTransaction(uow -> reports.conclude(uow, claimed, outcome, INTERVAL,
                    correlation())));
        });
    }

    /** An officer as the session establishes every operator - CUSTOMER-typed - of a fresh party of their own. */
    private static Actor officer() {
        Actor actor = new Actor(IDS.next().toString(), ActorType.CUSTOMER);
        PARTY_OF.put(actor.id(), IDS.next());
        return actor;
    }

    private static CorrelationId correlation() {
        return CorrelationId.of("report-db-" + IDS.next());
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static <T> T asSystem(Supplier<T> work) {
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return work.get();
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

    private static List<String> column(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            while (row.next()) {
                values.add(row.getString(1));
            }
        }
        return values;
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

    /** An authority keyed by the filing reference, synchronised - received once, answered as scripted. */
    private static final class Authority implements RegulatoryFilingProvider {
        private final List<String> files = new ArrayList<>();
        private final List<String> inquiries = new ArrayList<>();
        private final Map<String, Outcome> received = new java.util.HashMap<>();
        volatile boolean loseNextAnswer;
        volatile boolean rejectNext;
        volatile boolean unknownNext;

        synchronized List<String> files() {
            return List.copyOf(files);
        }

        synchronized List<String> inquiries() {
            return List.copyOf(inquiries);
        }

        @Override
        public Optional<Declaration> declared(String jurisdiction) {
            return "EU-REF".equals(jurisdiction) ? Optional.of(new Declaration("EU-REF", 1, "FINAPP-DB-ENTITY"))
                    : Optional.empty();
        }

        @Override
        public synchronized Receipt file(Request request) {
            files.add(request.filingReference());
            Outcome standing = received.computeIfAbsent(request.filingReference(), reference -> {
                if (rejectNext) {
                    rejectNext = false;
                    return Outcome.REJECTED;
                }
                if (unknownNext) {
                    unknownNext = false;
                    return Outcome.UNKNOWN;
                }
                return Outcome.ACKNOWLEDGED;
            });
            if (loseNextAnswer) {
                loseNextAnswer = false;
                throw new IllegalStateException("the answer was lost");
            }
            return receipt(request.filingReference(), standing);
        }

        @Override
        public synchronized Receipt inquire(String filingReference) {
            inquiries.add(filingReference);
            Outcome standing = received.get(filingReference);
            if (standing == null) {
                return Receipt.notReceived();
            }
            if (standing == Outcome.UNKNOWN) {
                standing = Outcome.ACKNOWLEDGED;
                received.put(filingReference, standing);
            }
            return receipt(filingReference, standing);
        }

        private static Receipt receipt(String reference, Outcome standing) {
            String authority = "FIU-" + reference.substring(0, 8);
            return switch (standing) {
                case ACKNOWLEDGED -> Receipt.acknowledged(authority);
                case REJECTED -> Receipt.rejected(Optional.of(authority), "FORMAT_INVALID");
                case UNKNOWN -> Receipt.unknown(Optional.of(authority));
                case NOT_RECEIVED -> Receipt.notReceived();
            };
        }
    }
}
