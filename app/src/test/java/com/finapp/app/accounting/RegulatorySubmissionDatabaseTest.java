package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.JdbcRegulatoryObligationStore;
import com.finapp.accounting.JdbcRegulatorySubmissionStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RegulatoryAdapters;
import com.finapp.accounting.RegulatoryForm;
import com.finapp.accounting.RegulatoryFormLine;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryObligationStatus;
import com.finapp.accounting.RegulatoryObligations;
import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunContentParser;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The regulatory framework against a live PostgreSQL (`P14-TSK-018`; ADR-0118; {@code accounting V010}; {@code INV-ACC-13}, {@code INV-ACC-04}, {@code INV-HIST-02}, {@code INV-AUD-04}; {@code PHASE_14_PLAN.md} section 8.3 row 13). OWED (C5): written and walked by hand, clause by clause against V010, not yet run.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it closes months of 1999 for real, reopens them, and activates a GL mapping from January; in the shared container that would fail other suites' postings.
 *
 * <p><strong>No state crosses a case, in any order.</strong> A reopen moves a revision for good, and periods close in order: so <em>every case takes its own month</em> from {@link #closedMonth}, which closes the earliest month not closed - whatever a case before it left {@code OPEN} - or opens and closes the next.
 * Every case's month is {@code CLOSED} when it starts, at whatever revision, and each case reads that revision from the row. Every case takes its own form code, so no obligation or one-live index crosses cases. No case leaves a period {@code LOCKED} or {@code CLOSE_PROPOSED}. The suite therefore holds under any JUnit order, and takes no
 * {@code @TestMethodOrder}. A leftover {@code SUBMITTED} row of another case may be driven by a case's {@code inquireDue}, so a case asserts on its own row's id only.
 *
 * <p><strong>The source's content is the suite's</strong>: a {@code TRIAL_BALANCE} run claimed and completed through {@code ReportRuns}, with a body of the suite's writing through the machine's own completion. What is proven here is V010 and the machine; that a figure path finds what the real definitions write is {@code
 * RegulatoryFiguresOnRealBodiesTest}'s, hermetic. Refusals are asserted raw, on the application role's own connection, or on the SQLSTATE: a store's message drops the driver's text.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("the regulatory framework against the database (P14-TSK-018)")
class RegulatorySubmissionDatabaseTest {

    private static final int INSTANCES = 10;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration INTERVAL = Duration.ofMinutes(15);
    private static final String REASON = "read and agreed";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String CHECK_VIOLATION = "23514";
    private static final AtomicInteger FORMS = new AtomicInteger();
    private static final JdbcRegulatoryObligationStore OBLIGATIONS = new JdbcRegulatoryObligationStore();
    private static final JdbcRegulatorySubmissionStore STORE = new JdbcRegulatorySubmissionStore();
    private static final JdbcReportRunStore RUNS = new JdbcReportRunStore();
    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(new TestReportDefinition(RegulatoryWorld.SOURCE, 1, Set.of(), false, (snapshot, inputs) -> CanonicalJson.object()));
    private static final ReportRuns REPORT_RUNS = new ReportRuns(RUNS, AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), DEFINITIONS, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);

    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
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
    };

    private final ScriptedRegulatoryProvider provider = new ScriptedRegulatoryProvider();
    private final RegulatoryAdapters adapters = new RegulatoryAdapters(List.of(provider));
    private final RegulatorySubmissions machine = new RegulatorySubmissions(AccountingPeriodFixture.STORE, STORE, OBLIGATIONS, RUNS, adapters, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW, Duration.ofDays(1));
    private final RegulatoryObligations obligations = new RegulatoryObligations(OBLIGATIONS, adapters, DEFINITIONS, new JdbcAuditWriter(), IDS, NOW);
    private final RegulatorySubmissionMetrics metrics = new RegulatorySubmissionMetrics(new SimpleMeterRegistry());
    private final RegulatorySubmissionDriver driver = new RegulatorySubmissionDriver(machine, TRANSACTIONS, metrics, new CloseRunMetrics(new SimpleMeterRegistry()), AccountingFlowScope.NONE, LEASE, INTERVAL, 100);

    @BeforeAll
    static void aChartInForce() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
    }

    // ------------------------------------------------------------------ the ladder

    /** A month of the case's own, {@code CLOSED}: the earliest not closed, whatever left it so, or the next one opened and closed. */
    private static YearMonth closedMonth() {
        Optional<AccountingPeriodStore.PeriodRow> unclosed = inScope(Actor.SYSTEM, uow -> AccountingPeriodFixture.STORE.oldestUnclosed(uow));
        YearMonth month;
        if (unclosed.isPresent()) {
            assertThat(unclosed.get().status()).as("no case leaves a period past OPEN and not closed").isEqualTo(PeriodStatus.OPEN);
            month = unclosed.get().month();
        } else {
            YearMonth latest = inScope(Actor.SYSTEM, uow -> AccountingPeriodFixture.STORE.latest(uow)).orElseThrow();
            month = latest.plusMonths(1);
            inScope(Actor.SYSTEM, uow -> periods(on(month.atDay(15).toString())).openNext(uow, Actor.SYSTEM, correlation()));
        }
        PeriodCloseFixture.closed(month);
        return month;
    }

    private static void reopen(YearMonth month) {
        proposeReopen(month);
        approveReopen(month);
    }

    private static void proposeReopen(YearMonth month) {
        AccountingPeriodId period = id(month);
        Actor proposer = person();
        inScope(proposer, uow -> PeriodCloseFixture.MACHINE.proposeReopen(uow, period, proposer, "the suite reopens it", correlation()));
    }

    private static void approveReopen(YearMonth month) {
        AccountingPeriodId period = id(month);
        Actor approver = person();
        inScope(approver, uow -> PeriodCloseFixture.MACHINE.approveReopen(uow, period, approver, "the suite reopens it", correlation()));
    }

    /** A FINAL run of the source for {@code month}, its content the suite's, through the machine's own claim and completion. */
    private static ReportRunId issued(YearMonth month, CanonicalJson.Obj body) {
        AccountingPeriodId period = id(month);
        Actor accountant = person();
        return inScope(accountant, uow -> {
            ReportRuns.Claimed claimed = REPORT_RUNS.claim(uow, RegulatoryWorld.SOURCE, period, RunParameters.none(), accountant, correlation());
            ReportRunStore.RunRow run = claimed.run();
            assertThat(claimed.mine()).as("the case's own month holds no such run").isTrue();
            String content = CanonicalJson.text(CanonicalJson.object().put("definition", RegulatoryWorld.SOURCE).put("period", month.toString()).put("body", body));
            REPORT_RUNS.complete(uow, run.id(), run.attempt(), new ReportGeneration.Generated(content, CanonicalJson.sha256(content), Optional.empty()), accountant);
            return run.id();
        });
    }

    private static ReportRunId issued(YearMonth month) {
        return issued(month, RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00));
    }

    /** A form of the case's own, version 1 in force: proposed by one person and activated by another. */
    private Form activeForm() {
        String form = "REF-TEST-" + FORMS.incrementAndGet();
        Actor proposer = person();
        RegulatoryObligations.Acted proposed = inScope(proposer, uow -> obligations.propose(uow, new RegulatoryObligations.Proposal(ScriptedRegulatoryProvider.JURISDICTION, form, 1, ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration()), REASON,
                proposer, correlation()));
        Actor activator = person();
        inScope(activator, uow -> obligations.activate(uow, proposed.id(), proposed.configurationSha256(), REASON, activator, correlation()));
        return new Form(form, proposed.id());
    }

    private record Form(String code, RegulatoryObligationId obligation) {}

    /** A case's return, and the two persons who made it. */
    private record Case(YearMonth month, Form form, RegulatorySubmissions.Acted acted, Actor preparer, Actor approver) {

        RegulatorySubmissionId id() {
            return acted.id();
        }

        RegulatorySubmissions.Attested read() {
            return new RegulatorySubmissions.Attested(acted.contentSha256(), acted.periodRevision());
        }
    }

    private Case drafted() {
        YearMonth month = closedMonth();
        issued(month);
        return draftedIn(month, activeForm());
    }

    private Case draftedIn(YearMonth month, Form form) {
        Actor preparer = person();
        AccountingPeriodId period = id(month);
        RegulatorySubmissions.Acted acted = inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(form.obligation(), period, Optional.empty(), REASON), preparer, correlation()));
        return new Case(month, form, acted, preparer, person());
    }

    private Case validated(Case drafted) {
        RegulatorySubmissions.Acted acted = inScope(drafted.preparer(), uow -> machine.validate(uow, drafted.id(), drafted.preparer(), correlation()));
        assertThat(acted.status()).isEqualTo(SubmissionStatus.VALIDATED);
        return drafted;
    }

    private Case approved(Case validated) {
        inScope(validated.approver(), uow -> machine.approve(uow, validated.id(), validated.read(), REASON, validated.approver(), correlation()));
        return validated;
    }

    private Case approved() {
        return approved(validated(drafted()));
    }

    private Case submitted() {
        Case approved = approved();
        Actor sender = person();
        inScope(sender, uow -> machine.submit(uow, approved.id(), approved.read(), REASON, sender, correlation()));
        return approved;
    }

    private static RegulatorySubmissionStore.Row rowOf(RegulatorySubmissionId id) {
        return inScope(Actor.SYSTEM, uow -> STORE.find(uow, id)).orElseThrow();
    }

    // ------------------------------------------------------------------ raw, on the application role's own connection

    private static long count(String sql) throws SQLException {
        return PeriodCloseFixture.count(sql);
    }

    private static void asApplication(String sql, Object... parameters) throws SQLException {
        try (Connection application = DatabaseRoles.application(); PreparedStatement statement = application.prepareStatement(sql)) {
            for (int at = 0; at < parameters.length; at++) {
                statement.setObject(at + 1, parameters[at]);
            }
            statement.executeUpdate();
        }
    }

    private static void asMigrator(String sql, Object... parameters) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement statement = migrator.prepareStatement(sql)) {
            for (int at = 0; at < parameters.length; at++) {
                statement.setObject(at + 1, parameters[at]);
            }
            statement.executeUpdate();
        }
    }

    private static final String RAW_INSERT = "INSERT INTO accounting.regulatory_submission (id, obligation_id, jurisdiction_code, form_code, form_version, period_id, period_revision, corrects_submission_id, content, content_sha256, status, prepared_by,"
            + " prepared_by_type, prepared_at, preparation_reason, attempts, resends, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, 'CUSTOMER', clock_timestamp(), 'a reason', 0, 0, ?)";

    /** The content a draft of {@code form} for {@code month} would state, with the run {@code run}. */
    private static String contentFor(Form form, YearMonth month, int revision, ReportRunId run, String runSha256, CanonicalJson.Obj runContent, Optional<RegulatorySubmissionId> corrects) {
        RegulatoryForm.SourceRun source = new RegulatoryForm.SourceRun(RegulatoryWorld.SOURCE, 1, run, runSha256, runContent);
        return RegulatoryForm.content(new RegulatoryForm.Header(form.obligation(), ScriptedRegulatoryProvider.JURISDICTION, form.code(), 1, CanonicalJson.sha256(RegulatoryWorld.configuration().canonical()), month, revision, corrects), List.of(source),
                RegulatoryForm.draw(RegulatoryWorld.configuration(), Map.of(RegulatoryWorld.SOURCE, source)));
    }

    private static UUID rawDraft(Connection connection, Form form, AccountingPeriodId period, int revision, String content, String sha256) throws SQLException {
        UUID id = IDS.next();
        try (PreparedStatement insert = connection.prepareStatement(RAW_INSERT)) {
            insert.setObject(1, id);
            insert.setObject(2, form.obligation().value());
            insert.setString(3, ScriptedRegulatoryProvider.JURISDICTION);
            insert.setString(4, form.code());
            insert.setInt(5, 1);
            insert.setObject(6, period.value());
            insert.setInt(7, revision);
            insert.setNull(8, java.sql.Types.OTHER);
            insert.setString(9, content);
            insert.setString(10, sha256);
            insert.setString(11, "raw-writer");
            insert.setString(12, "raw-correlation");
            insert.executeUpdate();
        }
        return id;
    }

    private static List<Object> race(List<? extends Callable<?>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch ready = new CountDownLatch(racers.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<?> racer : racers) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return racer.call();
                } catch (RuntimeException refused) {
                    return refused;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> future : futures) {
            outcomes.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return outcomes;
    }

    // ================================================================== the entry's four

    @Test
    @DisplayName("a retried submit submits once: ten instances of the sender race the edge, one event; ten drives race the claim under real locks, one call; the provider holds one return")
    void aRetriedSubmitSubmitsOnce() throws Exception {
        Case approved = approved();
        Actor sender = person();
        List<Callable<RegulatorySubmissions.Acted>> senders = new ArrayList<>();
        for (int at = 0; at < INSTANCES; at++) {
            senders.add(() -> inScope(sender, uow -> machine.submit(uow, approved.id(), approved.read(), REASON, sender, correlation())));
        }
        assertThat(race(senders)).allSatisfy(outcome -> assertThat(((RegulatorySubmissions.Acted) outcome).status()).isEqualTo(SubmissionStatus.SUBMITTED));
        assertThat(count("SELECT count(*) FROM accounting.regulatory_submission_event WHERE submission_id = '" + approved.id().value() + "' AND act = 'SUBMITTED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + RegulatorySubmissions.SUBMITTED_EVENT + "' AND aggregate_id = '" + approved.id().value() + "'")).isEqualTo(1);
        assertThat(rowOf(approved.id()).attempts()).as("SUBMITTED commits before any call").isZero();

        List<Callable<Boolean>> drives = new ArrayList<>();
        for (int at = 0; at < INSTANCES; at++) {
            drives.add(() -> driver.submitIfDue(approved.id()));
        }
        assertThat(race(drives)).filteredOn(Boolean.TRUE::equals).hasSize(1);

        assertThat(provider.sendsReceived(approved.id())).isEqualTo(1);
        RegulatorySubmissionStore.Row concluded = rowOf(approved.id());
        assertThat(concluded.status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(concluded.attempts()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM accounting.regulatory_provider_answer WHERE submission_id = '" + approved.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + RegulatorySubmissions.ACKNOWLEDGED_EVENT + "' AND aggregate_id = '" + approved.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'regulatory.SubmissionAcknowledged' AND target_id = '" + approved.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("only FINAL runs feed a draft: the machine refuses a CLOSED month with no FINAL run and an OPEN month holding a PROVISIONAL one; a raw writer is refused a draft of an OPEN month and a source naming a PROVISIONAL run")
    void onlyFinalRunsFeedADraft() throws Exception {
        YearMonth closed = closedMonth();
        Form form = activeForm();
        AccountingPeriodId closedPeriod = id(closed);
        Actor preparer = person();
        assertThatThrownBy(() -> inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(form.obligation(), closedPeriod, Optional.empty(), REASON), preparer, correlation())))
                .as("CLOSED with no FINAL run").isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);

        // The next month, OPEN, holding a PROVISIONAL run of the same question.
        YearMonth open = closed.plusMonths(1);
        inScope(Actor.SYSTEM, uow -> periods(on(open.atDay(15).toString())).openNext(uow, Actor.SYSTEM, correlation()));
        ReportRunId provisional = issued(open, RegulatoryWorld.body(1, 1, 1, 1));
        assertThat(inScope(Actor.SYSTEM, uow -> RUNS.find(uow, provisional)).orElseThrow().status().name()).isEqualTo("PROVISIONAL");
        AccountingPeriodId openPeriod = id(open);
        assertThatThrownBy(() -> inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(form.obligation(), openPeriod, Optional.empty(), REASON), preparer, correlation())))
                .as("an OPEN month").isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);

        CanonicalJson.Obj content = CanonicalJson.object().put("body", RegulatoryWorld.body(1, 1, 1, 1));
        String stated = contentFor(form, open, row(open).revision(), provisional, "0".repeat(64), content, Optional.empty());
        try (Connection application = DatabaseRoles.application()) {
            assertThatThrownBy(() -> rawDraft(application, form, openPeriod, row(open).revision(), stated, CanonicalJson.sha256(stated)))
                    .as("the trigger, for a raw writer").isInstanceOf(SQLException.class).hasMessageContaining("CLOSED period");
        }

        // A draft of the CLOSED month, its FINAL run in place; a raw source naming the OPEN month's PROVISIONAL run is refused.
        issued(closed);
        Case draft = draftedIn(closed, form);
        String sha = inScope(Actor.SYSTEM, uow -> RUNS.content(uow, provisional)).orElseThrow().sha256();
        assertThatThrownBy(() -> asApplication("INSERT INTO accounting.regulatory_submission_source (submission_id, run_id, run_sha256, definition, definition_version) VALUES (?, ?, ?, ?, 1)", draft.id().value(), provisional.value(), sha, RegulatoryWorld.SOURCE))
                .isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_source_is_a_final_run");
    }

    @Test
    @DisplayName("a correction references the original: only a concluded return; a plain draft after one sent refused raw under the unique's SQLSTATE; ten racing corrections of one original, one stands")
    void aCorrectionReferencesTheOriginal() throws Exception {
        Case approved = approved();
        Actor sender = person();
        inScope(sender, uow -> machine.submit(uow, approved.id(), approved.read(), REASON, sender, correlation()));
        AccountingPeriodId period = id(approved.month());
        Actor preparer = person();

        assertThatThrownBy(() -> inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(approved.form().obligation(), period, Optional.of(approved.id()), REASON), preparer, correlation())))
                .as("not yet concluded").isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);

        provider.nextSubmit(() -> Answer.rejected(Optional.of("PROVIDER-1"), "FORM_REFUSED", "{}".getBytes(StandardCharsets.UTF_8)));
        driver.submitIfDue(approved.id());
        assertThat(rowOf(approved.id()).status()).isEqualTo(SubmissionStatus.REJECTED);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + RegulatorySubmissions.REJECTED_EVENT + "' AND aggregate_id = '" + approved.id().value() + "'")).isEqualTo(1);

        // A plain draft: the machine refuses, and the trigger for a raw writer, under the unique's own SQLSTATE.
        assertThatThrownBy(() -> inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(approved.form().obligation(), period, Optional.empty(), REASON), preparer, correlation())))
                .isInstanceOf(RegulatorySubmissions.SubmissionLive.class);
        String content = inScope(Actor.SYSTEM, uow -> STORE.content(uow, approved.id())).orElseThrow();
        int revision = approved.acted().periodRevision();
        try (Connection application = DatabaseRoles.application()) {
            assertThatThrownBy(() -> rawDraft(application, approved.form(), period, revision, content, CanonicalJson.sha256(content)))
                    .isInstanceOfSatisfying(SQLException.class, refused -> {
                        assertThat(refused.getSQLState()).isEqualTo("23505");
                        assertThat(refused.getMessage()).contains("regulatory_submission_names_what_was_sent");
                    });
        }

        // Ten preparers race to correct it: the original's row serialises them, and one stands.
        List<Callable<RegulatorySubmissions.Acted>> racers = new ArrayList<>();
        for (int at = 0; at < INSTANCES; at++) {
            Actor racer = person();
            racers.add(() -> inScope(racer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(approved.form().obligation(), period, Optional.of(approved.id()), REASON), racer, correlation())));
        }
        List<Object> outcomes = race(racers);
        assertThat(outcomes).filteredOn(RegulatorySubmissions.Acted.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(RegulatorySubmissions.SubmissionLive.class::isInstance).hasSize(INSTANCES - 1);
        RegulatorySubmissions.Acted correction = (RegulatorySubmissions.Acted) outcomes.stream().filter(RegulatorySubmissions.Acted.class::isInstance).findFirst().orElseThrow();
        assertThat(rowOf(correction.id()).corrects()).contains(approved.id());
        assertThat(rowOf(approved.id()).correctedBy()).contains(correction.id());
        assertThat(rowOf(approved.id()).status()).as("the original is not edited").isEqualTo(SubmissionStatus.REJECTED);
    }

    @Test
    @DisplayName("the proposer cannot approve: the machine refuses and the row's and the history's CHECKs refuse a raw writer, on the SQLSTATE; the approver cannot send: the machine refuses and the row's CHECK refuses a raw writer")
    void theProposerCannotApprove() throws Exception {
        Case validated = validated(drafted());

        assertThatThrownBy(() -> inScope(validated.preparer(), uow -> machine.approve(uow, validated.id(), validated.read(), REASON, validated.preparer(), correlation()))).isInstanceOf(RegulatorySubmissions.SelfApprovalRefused.class);
        String hash = validated.acted().contentSha256();
        int revision = validated.acted().periodRevision();
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET status = 'APPROVED', approved_by = prepared_by, approved_by_type = 'CUSTOMER', approved_at = clock_timestamp(), approval_reason = 'mine',"
                + " approved_content_sha256 = ?, approved_period_revision = ? WHERE id = ?", hash, revision, validated.id().value())).isInstanceOfSatisfying(SQLException.class, refused -> {
                    assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
                    assertThat(refused.getMessage()).contains("regulatory_submission_four_eyes");
                });
        assertThatThrownBy(() -> asApplication("INSERT INTO accounting.regulatory_submission_event (id, submission_id, act, from_status, to_status, actor, actor_type, prepared_by, reason, occurred_at, correlation_id)"
                + " VALUES (?, ?, 'APPROVED', 'VALIDATED', 'APPROVED', 'same', 'CUSTOMER', 'same', 'mine', clock_timestamp(), 'c')", IDS.next(), validated.id().value())).isInstanceOfSatisfying(SQLException.class, refused -> {
                    assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
                    assertThat(refused.getMessage()).contains("regulatory_submission_event_four_eyes");
                });
        assertThat(rowOf(validated.id()).status()).isEqualTo(SubmissionStatus.VALIDATED);

        Case approved = approved(validated);
        assertThatThrownBy(() -> inScope(approved.approver(), uow -> machine.submit(uow, approved.id(), approved.read(), REASON, approved.approver(), correlation()))).isInstanceOf(RegulatorySubmissions.SelfApprovalRefused.class);
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET status = 'SUBMITTED', submitted_by = approved_by, submitted_by_type = 'CUSTOMER', submitted_at = clock_timestamp(), submission_reason = 'mine',"
                + " next_inquiry_at = clock_timestamp(), answer_horizon_at = clock_timestamp() WHERE id = ?", approved.id().value())).isInstanceOfSatisfying(SQLException.class, refused -> {
                    assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
                    assertThat(refused.getMessage()).contains("regulatory_submission_approver_does_not_send");
                });
    }

    // ================================================================== a status that round-trips

    @Test
    @DisplayName("closed, reopened and re-closed, the month reads CLOSED and is not the month read: the machine refuses, and the trigger refuses a raw writer's edge from a status that looks right")
    void aRoundTrippedMonthIsNotTheMonthItWas() throws Exception {
        Case draft = drafted();
        YearMonth month = draft.month();
        roundTrip(month);
        assertThat(row(month).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(row(month).revision()).isGreaterThan(draft.acted().periodRevision());

        assertThatThrownBy(() -> inScope(draft.preparer(), uow -> machine.validate(uow, draft.id(), draft.preparer(), correlation()))).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET status = 'VALIDATED', validation_outcome = '{}', validation_passed = TRUE, validated_by = 'raw', validated_by_type = 'CUSTOMER', validated_at = clock_timestamp() WHERE id = ?",
                draft.id().value())).isInstanceOf(SQLException.class).hasMessageContaining("revision it read");
        assertThat(rowOf(draft.id()).monthMoved()).isTrue();

        // The door out asks nothing of the month.
        assertThat(inScope(draft.preparer(), uow -> machine.withdraw(uow, draft.id(), REASON, draft.preparer(), correlation())).status()).isEqualTo(SubmissionStatus.WITHDRAWN);

        // VALIDATED and APPROVED overtaken the same way, in the same month at its new revision.
        Case validated = validated(draftedIn(month, draft.form()));
        roundTrip(month);
        assertThatThrownBy(() -> inScope(validated.approver(), uow -> machine.approve(uow, validated.id(), validated.read(), REASON, validated.approver(), correlation()))).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(inScope(validated.approver(), uow -> machine.refuse(uow, validated.id(), REASON, validated.approver(), correlation())).status()).isEqualTo(SubmissionStatus.REFUSED);

        Case approved = approved(validated(draftedIn(month, draft.form())));
        roundTrip(month);
        Actor sender = person();
        assertThatThrownBy(() -> inScope(sender, uow -> machine.submit(uow, approved.id(), approved.read(), REASON, sender, correlation()))).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(rowOf(approved.id()).status()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(provider.submits()).isZero();
    }

    /** Reopened, closed again, its source issued again under the new revision: the month reads as it did, its revision moved. */
    private static void roundTrip(YearMonth month) {
        reopen(month);
        PeriodCloseFixture.closed(month);
        issued(month);
    }

    @Test
    @DisplayName("a reopen's approval against a submit, submit first: the reopen waits on the period row, the return commits SUBMITTED against the old revision, and reads monthMoved from then on, frozen")
    void aSubmitFirstHoldsTheReopenOff() throws Exception {
        Case approved = approved();
        proposeReopen(approved.month());
        Actor sender = person();
        CountDownLatch edgeMade = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<RegulatorySubmissions.Acted> submit = pool.submit(() -> inScope(sender, uow -> {
            RegulatorySubmissions.Acted acted = machine.submit(uow, approved.id(), approved.read(), REASON, sender, correlation());
            edgeMade.countDown();
            await(release);
            return acted;
        }));
        assertThat(edgeMade.await(60, TimeUnit.SECONDS)).isTrue();
        Future<?> reopen = pool.submit(() -> approveReopen(approved.month()));
        assertThatThrownBy(() -> reopen.get(1, TimeUnit.SECONDS)).as("the reopen waits on the row the submit holds FOR SHARE").isInstanceOf(TimeoutException.class);
        release.countDown();
        assertThat(submit.get(60, TimeUnit.SECONDS).status()).isEqualTo(SubmissionStatus.SUBMITTED);
        reopen.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        RegulatorySubmissionStore.Row frozen = rowOf(approved.id());
        assertThat(frozen.status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(frozen.periodRevision()).isEqualTo(approved.acted().periodRevision());
        assertThat(frozen.monthMoved()).isTrue();
        assertThat(frozen.periodStatusNow()).isEqualTo(PeriodStatus.OPEN);
        assertThat(count("SELECT count(*) FROM accounting.report_run r JOIN accounting.regulatory_submission_source s ON s.run_id = r.id WHERE s.submission_id = '" + approved.id().value() + "' AND r.status = 'SUPERSEDED'")).isEqualTo(1);
        assertThat(frozen.contentSha256()).as("frozen").isEqualTo(approved.acted().contentSha256());
    }

    @Test
    @DisplayName("a reopen's approval against a submit, reopen first: the submit waits, then reads the new revision: Stale, nothing written, nothing sent")
    void aReopenFirstRefusesTheSubmit() throws Exception {
        Case approved = approved();
        proposeReopen(approved.month());
        AccountingPeriodId period = id(approved.month());
        Actor approver = person();
        Actor sender = person();
        CountDownLatch edgeMade = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> reopen = pool.submit(() -> inScope(approver, uow -> {
            PeriodCloseFixture.MACHINE.approveReopen(uow, period, approver, "the suite reopens it", correlation());
            edgeMade.countDown();
            await(release);
            return null;
        }));
        assertThat(edgeMade.await(60, TimeUnit.SECONDS)).isTrue();
        Future<Object> submit = pool.submit(() -> {
            try {
                return inScope(sender, uow -> machine.submit(uow, approved.id(), approved.read(), REASON, sender, correlation()));
            } catch (RuntimeException refused) {
                return refused;
            }
        });
        assertThatThrownBy(() -> submit.get(1, TimeUnit.SECONDS)).as("the submit waits on the row the reopen holds FOR UPDATE").isInstanceOf(TimeoutException.class);
        release.countDown();
        reopen.get(60, TimeUnit.SECONDS);
        assertThat(submit.get(60, TimeUnit.SECONDS)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        pool.shutdownNow();

        assertThat(rowOf(approved.id()).status()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(count("SELECT count(*) FROM accounting.regulatory_submission_event WHERE submission_id = '" + approved.id().value() + "' AND act = 'SUBMITTED'")).isZero();
        assertThat(provider.submits()).as("nothing sent").isZero();
        assertThat(provider.inquiries()).isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + RegulatorySubmissions.SUBMITTED_EVENT + "' AND aggregate_id = '" + approved.id().value() + "'")).isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'regulatory.SubmissionSubmitted' AND target_id = '" + approved.id().value() + "'")).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    // ================================================================== one live

    @Test
    @DisplayName("ten drafts of one form and month, racing: one live row, nine refused; a withdrawal frees it")
    void tenDraftsOneLiveRow() throws Exception {
        YearMonth month = closedMonth();
        issued(month);
        Form form = activeForm();
        AccountingPeriodId period = id(month);
        List<Callable<RegulatorySubmissions.Acted>> racers = new ArrayList<>();
        for (int at = 0; at < INSTANCES; at++) {
            Actor racer = person();
            racers.add(() -> inScope(racer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(form.obligation(), period, Optional.empty(), REASON), racer, correlation())));
        }
        List<Object> outcomes = race(racers);

        assertThat(outcomes).filteredOn(RegulatorySubmissions.Acted.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(RegulatorySubmissions.SubmissionLive.class::isInstance).hasSize(INSTANCES - 1);
        assertThat(count("SELECT count(*) FROM accounting.regulatory_submission WHERE form_code = '" + form.code() + "' AND period_id = '" + period.value() + "'")).isEqualTo(1);
        RegulatorySubmissions.Acted born = (RegulatorySubmissions.Acted) outcomes.stream().filter(RegulatorySubmissions.Acted.class::isInstance).findFirst().orElseThrow();
        assertThat(count("SELECT count(*) FROM accounting.regulatory_submission_line WHERE submission_id = '" + born.id().value() + "' AND run_id IS NOT NULL AND run_sha256 IS NOT NULL AND figure_path IS NOT NULL")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM accounting.regulatory_submission_source WHERE submission_id = '" + born.id().value() + "'")).isEqualTo(1);

        Actor preparer = person();
        inScope(preparer, uow -> machine.withdraw(uow, born.id(), REASON, preparer, correlation()));
        assertThat(inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(form.obligation(), period, Optional.empty(), REASON), preparer, correlation())).status()).isEqualTo(SubmissionStatus.DRAFT);
    }

    // ================================================================== what V010 holds for every writer

    @Test
    @DisplayName("born whole, at its commit: rows the content does not state are refused, and a content with no rows; a line past its bound refused; the hash is the text's own; REFERENCE, the month and the revision in the header are the row's")
    void bornWhole() throws Exception {
        YearMonth month = closedMonth();
        ReportRunId run = issued(month);
        Form form = activeForm();
        AccountingPeriodId period = id(month);
        int revision = row(month).revision();
        ReportRunStore.Content issued = inScope(Actor.SYSTEM, uow -> RUNS.content(uow, run)).orElseThrow();
        String stated = contentFor(form, month, revision, run, issued.sha256(), RunContentParser.object(issued.text()), Optional.empty());
        String sha = CanonicalJson.sha256(stated);

        // No rows at all: refused at the commit.
        try (Connection application = DatabaseRoles.application()) {
            application.setAutoCommit(false);
            rawDraft(application, form, period, revision, stated, sha);
            assertThatThrownBy(application::commit).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_lines_agree");
            application.rollback();
        }

        // A line differing from what the content states: refused at the commit.
        try (Connection application = DatabaseRoles.application()) {
            application.setAutoCommit(false);
            UUID id = rawDraft(application, form, period, revision, stated, sha);
            try (PreparedStatement source = application.prepareStatement("INSERT INTO accounting.regulatory_submission_source (submission_id, run_id, run_sha256, definition, definition_version) VALUES (?, ?, ?, ?, 1)")) {
                source.setObject(1, id);
                source.setObject(2, run.value());
                source.setString(3, issued.sha256());
                source.setString(4, RegulatoryWorld.SOURCE);
                source.executeUpdate();
            }
            int ordinal = 1;
            for (String code : List.of("ASSETS", "ASSETS", "LIABILITIES", "LIABILITIES")) {
                String currency = ordinal % 2 == 1 ? "EUR" : "USD";
                try (PreparedStatement line = application.prepareStatement("INSERT INTO accounting.regulatory_submission_line (submission_id, ordinal, line_code, label, currency, minor_units, scale, run_id, run_sha256, source_definition, figure_path)"
                        + " VALUES (?, ?, ?, 'Forged', ?, '1', 2, ?, ?, ?, 'body.forged')")) {
                    line.setObject(1, id);
                    line.setInt(2, ordinal++);
                    line.setString(3, code);
                    line.setString(4, currency);
                    line.setObject(5, run.value());
                    line.setString(6, issued.sha256());
                    line.setString(7, RegulatoryWorld.SOURCE);
                    line.executeUpdate();
                }
            }
            assertThatThrownBy(application::commit).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_lines_agree");
            application.rollback();
        }

        // A line past FigurePath.MAX_LENGTH, for a raw writer.
        try (Connection application = DatabaseRoles.application()) {
            application.setAutoCommit(false);
            UUID id = rawDraft(application, form, period, revision, stated, sha);
            try (PreparedStatement source = application.prepareStatement("INSERT INTO accounting.regulatory_submission_source (submission_id, run_id, run_sha256, definition, definition_version) VALUES (?, ?, ?, ?, 1)")) {
                source.setObject(1, id);
                source.setObject(2, run.value());
                source.setString(3, issued.sha256());
                source.setString(4, RegulatoryWorld.SOURCE);
                source.executeUpdate();
            }
            try (PreparedStatement line = application.prepareStatement("INSERT INTO accounting.regulatory_submission_line (submission_id, ordinal, line_code, label, currency, minor_units, scale, run_id, run_sha256, source_definition, figure_path)"
                    + " VALUES (?, 1, 'ASSETS', 'Forged', 'EUR', '1', 2, ?, ?, ?, ?)")) {
                line.setObject(1, id);
                line.setObject(2, run.value());
                line.setString(3, issued.sha256());
                line.setString(4, RegulatoryWorld.SOURCE);
                line.setString(5, "body." + "x".repeat(252));
                assertThatThrownBy(line::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_line_bounded");
            }
            application.rollback();
        }

        try (Connection application = DatabaseRoles.application()) {
            assertThatThrownBy(() -> rawDraft(application, form, period, revision, stated, "0".repeat(64))).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_content_hashed");
            String notReference = stated.replace("\"standing\":\"REFERENCE\"", "\"standing\":\"STATUTORY\"");
            assertThatThrownBy(() -> rawDraft(application, form, period, revision, notReference, CanonicalJson.sha256(notReference))).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_content_is_this_rows");
            String otherMonth = stated.replace("\"period\":\"" + month + "\"", "\"period\":\"1901-01\"");
            assertThat(otherMonth).isNotEqualTo(stated);
            assertThatThrownBy(() -> rawDraft(application, form, period, revision, otherMonth, CanonicalJson.sha256(otherMonth))).as("attested content stating another month").isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_content_is_this_rows");
            String otherRevision = stated.replace("\"periodRevision\":\"" + revision + "\"", "\"periodRevision\":\"99\"");
            assertThatThrownBy(() -> rawDraft(application, form, period, revision, otherRevision, CanonicalJson.sha256(otherRevision))).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_content_is_this_rows");
        }
    }

    @Test
    @DisplayName("frozen, for every writer: the application role holds no UPDATE of what was drawn and no DELETE; even the migrator meets the trigger; nothing is truncated")
    void frozenForEveryWriter() throws Exception {
        Case sent = submitted();
        UUID id = sent.id().value();
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET content = '{}' WHERE id = ?", id)).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        assertThatThrownBy(() -> asApplication("DELETE FROM accounting.regulatory_submission WHERE id = ?", id)).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission_line SET minor_units = '1' WHERE submission_id = ?", id)).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        assertThatThrownBy(() -> asMigrator("UPDATE accounting.regulatory_submission SET content = '{}' WHERE id = ?", id)).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_submission_is_frozen");
        assertThatThrownBy(() -> asMigrator("UPDATE accounting.regulatory_submission_line SET minor_units = '1' WHERE submission_id = ?", id)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> asMigrator("DELETE FROM accounting.regulatory_submission_source WHERE submission_id = ?", id)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> asMigrator("DELETE FROM accounting.regulatory_submission WHERE id = ?", id)).isInstanceOf(SQLException.class).hasMessageContaining("never deleted");
        // CASCADE, because PostgreSQL refuses a table another references before any trigger: this reaches each table's own trigger, and each raises, so nothing is emptied.
        for (String table : List.of("regulatory_obligation", "regulatory_submission", "regulatory_submission_source", "regulatory_submission_line", "regulatory_submission_event", "regulatory_provider_answer")) {
            assertThatThrownBy(() -> asMigrator("TRUNCATE accounting." + table + " CASCADE")).as(table).isInstanceOf(SQLException.class).hasMessageContaining("never truncated");
        }
        assertThatThrownBy(() -> asMigrator("UPDATE accounting.regulatory_submission SET status = 'DRAFT' WHERE id = ?", id)).isInstanceOf(SQLException.class).hasMessageContaining("not an edge");
    }

    @Test
    @DisplayName("ack then rejection, for the application role: an edge needs its recorded answer; the first definite answer concludes; a contrary one is kept verbatim, flagged, no edge, and lists the return; a concluded row takes no edge")
    void ackAgainstRejectionIsOneWay() throws Exception {
        Case sent = submitted();
        UUID id = sent.id().value();
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET status = 'ACKNOWLEDGED', provider_reference = 'R-1', concluded_at = clock_timestamp() WHERE id = ?", id))
                .as("no answer on the record").isInstanceOf(SQLException.class).hasMessageContaining("recorded answer");

        driver.submitIfDue(sent.id());
        assertThat(rowOf(sent.id()).status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET status = 'REJECTED', rejection_code = 'LATE' WHERE id = ?", id)).isInstanceOf(SQLException.class).hasMessageContaining("not an edge");

        // A slower worker's contrary answer.
        byte[] contrary = "not UTF-8: é".getBytes(StandardCharsets.ISO_8859_1);
        RegulatorySubmissionStore.Row claimedRow = rowOf(sent.id());
        RegulatorySubmissions.Claimed claimed = new RegulatorySubmissions.Claimed(claimedRow, inScope(Actor.SYSTEM, uow -> STORE.content(uow, sent.id())).orElseThrow());
        RegulatorySubmissions.Recorded recorded = inScope(Actor.SYSTEM, uow -> machine.record(uow, claimed, new RegulatorySubmissions.Answered(com.finapp.accounting.RegulatoryReportingProvider.CallKind.INQUIRE, Answer.rejected(Optional.of("PROVIDER-2"), "FORM_REFUSED", contrary)),
                INTERVAL, correlation()));
        assertThat(recorded.disposition()).isEqualTo(RegulatorySubmissions.Disposition.CONTRARY);
        RegulatorySubmissionStore.Row after = rowOf(sent.id());
        assertThat(after.status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(after.rejectionCode()).isEmpty();
        assertThat(after.contraryAnswers()).isEqualTo(1);
        List<RegulatorySubmissionStore.AnswerRow> kept = inScope(Actor.SYSTEM, uow -> STORE.answers(uow, sent.id(), RegulatorySubmissions.ANSWERS_SERVED));
        assertThat(kept).hasSize(2);
        List<RegulatorySubmissionStore.Row> standing = inScope(Actor.SYSTEM, uow -> STORE.standing(uow, Optional.empty(), 100));
        assertThat(standing).as("a contrary answer: a person can find which return").extracting(RegulatorySubmissionStore.Row::id).contains(sent.id());
        assertThat(kept.get(1).raw()).as("the bytes, verbatim: not text").isEqualTo(contrary);
        assertThat(kept.get(1).flag()).contains(RegulatorySubmissionStore.Flag.CONTRARY);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'regulatory.SubmissionContraryAnswer' AND target_id = '" + id + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a provider's answer, append-only: the application role holds no UPDATE and the migrator meets the trigger; an oversized one its prefix, its length and its hash, and it decides nothing; the bound and its rule by CHECK")
    void aProvidersAnswerIsKept() throws Exception {
        Case sent = submitted();
        byte[] huge = new byte[com.finapp.accounting.RegulatoryAnswerReading.MAX_RAW_BYTES + 100];
        Arrays.fill(huge, (byte) 'x');
        provider.nextSubmit(() -> Answer.acknowledged("REF-1", huge));
        driver.submitIfDue(sent.id());

        RegulatorySubmissionStore.Row row = rowOf(sent.id());
        assertThat(row.status()).as("an oversized answer decides nothing").isEqualTo(SubmissionStatus.SUBMITTED);
        RegulatorySubmissionStore.AnswerRow kept = inScope(Actor.SYSTEM, uow -> STORE.answers(uow, sent.id(), RegulatorySubmissions.ANSWERS_SERVED)).get(0);
        assertThat(kept.truncated()).isTrue();
        assertThat(kept.raw()).hasSize(com.finapp.accounting.RegulatoryAnswerReading.MAX_RAW_BYTES);
        assertThat(kept.raw()).isEqualTo(Arrays.copyOf(huge, com.finapp.accounting.RegulatoryAnswerReading.MAX_RAW_BYTES));
        assertThat(kept.rawSha256()).isEqualTo(CanonicalJson.sha256(new String(huge, StandardCharsets.UTF_8)));
        assertThat(kept.receivedBytes()).isEqualTo(huge.length);
        assertThat(kept.anomaly()).contains(com.finapp.accounting.RegulatoryAnswerReading.Anomaly.OVERSIZED);

        UUID answer = kept.id();
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_provider_answer SET flag = 'LATE' WHERE id = ?", answer)).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
        assertThatThrownBy(() -> asMigrator("UPDATE accounting.regulatory_provider_answer SET flag = 'LATE' WHERE id = ?", answer)).isInstanceOf(SQLException.class).hasMessageContaining("verbatim");
        assertThatThrownBy(() -> asMigrator("DELETE FROM accounting.regulatory_provider_answer WHERE id = ?", answer)).isInstanceOf(SQLException.class).hasMessageContaining("verbatim");

        // The bound and its rule, by CHECK: a raw writer on the application role's own connection.
        String insert = "INSERT INTO accounting.regulatory_provider_answer (id, submission_id, attempt, call_kind, stated_outcome, read_outcome, raw, raw_sha256, received_bytes, truncated, received_at, correlation_id)"
                + " VALUES (?, ?, 1, 'INQUIRE', 'UNKNOWN', 'UNKNOWN', ?, ?, ?, ?, clock_timestamp(), 'c')";
        byte[] past = new byte[com.finapp.accounting.RegulatoryAnswerReading.MAX_RAW_BYTES + 1];
        Arrays.fill(past, (byte) 'x');
        String pastSha = CanonicalJson.sha256(new String(past, StandardCharsets.UTF_8));
        assertThatThrownBy(() -> asApplication(insert, IDS.next(), sent.id().value(), past, pastSha, (long) past.length, false)).isInstanceOfSatisfying(SQLException.class, refused -> {
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("regulatory_provider_answer_raw_bounded");
        });
        byte[] small = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> asApplication(insert, IDS.next(), sent.id().value(), small, CanonicalJson.sha256("x"), 2L, true)).as("truncated, and no anomaly says so").isInstanceOfSatisfying(SQLException.class, refused -> {
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("regulatory_provider_answer_truncation_coherent");
        });
        assertThatThrownBy(() -> asApplication(insert, IDS.next(), sent.id().value(), small, "0".repeat(64), 1L, false)).as("a hash that is not the bytes'").isInstanceOfSatisfying(SQLException.class, refused -> {
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("regulatory_provider_answer_hash_is_the_answers");
        });
    }

    @Test
    @DisplayName("UNDELIVERED on a definite not received: refused over an unknown answer and to a raw writer; a counted resend must carry RESENDING; the driver's resend goes after its inquiry; the person's door opens once the resends are spent, on what the provider says at the act")
    void undeliveredOnADefiniteNotReceived() throws Exception {
        Case sent = submitted();
        UUID id = sent.id().value();
        provider.nextSubmit(() -> Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8)));
        driver.submitIfDue(sent.id());
        assertThat(rowOf(sent.id()).lastAnswer()).contains("UNKNOWN");
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET status = 'UNDELIVERED', closed_by = 'raw', closed_by_type = 'CUSTOMER', closed_at = clock_timestamp(), closing_reason = 'r' WHERE id = ?", id))
                .isInstanceOf(SQLException.class).hasMessageContaining("definite not received");

        // A resend that does not carry RESENDING: the trigger refuses it.
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET resends = resends + 1 WHERE id = ?", id)).isInstanceOf(SQLException.class).hasMessageContaining("recorded before its call");

        Actor sender = person();
        assertThatThrownBy(() -> inScope(sender, uow -> machine.declareUndelivered(uow, sent.id(), Optional.empty(), REASON, sender, correlation()))).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);

        // The permit made due by the migrator, standing in for time; then a definite answer, and the driver's own resend.
        asMigrator("UPDATE accounting.regulatory_submission SET next_inquiry_at = clock_timestamp() - interval '1 minute', last_answer = 'UNKNOWN' WHERE id = ?", id);
        provider.nextInquiry(() -> Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8)));
        provider.nextSubmit(() -> Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8)));
        driver.submitIfDue(sent.id());
        RegulatorySubmissionStore.Row resent = rowOf(sent.id());
        assertThat(resent.resends()).isEqualTo(1);
        assertThat(resent.lastAnswer()).contains("UNKNOWN");
        assertThat(count("SELECT count(*) FROM accounting.regulatory_provider_answer WHERE submission_id = '" + id + "' AND call_kind = 'RESEND'")).isEqualTo(1);

        for (int resend = 0; resend < RegulatorySubmissions.MAX_RESENDS; resend++) {
            provider.nextSubmit(() -> Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8)));
        }
        assertThatThrownBy(() -> asMigrator("UPDATE accounting.regulatory_submission SET answer_horizon_at = clock_timestamp() - interval '1 minute' WHERE id = ?", id)).as("a sending is recorded once").isInstanceOf(SQLException.class).hasMessageContaining("recorded once");
        for (int ask = 0; ask < RegulatorySubmissions.MAX_RESENDS; ask++) {
            asMigrator("UPDATE accounting.regulatory_submission SET next_inquiry_at = clock_timestamp() - interval '1 minute' WHERE id = ?", id);
            provider.nextInquiry(() -> Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8)));
            driver.submitIfDue(sent.id());
        }
        RegulatorySubmissionStore.Row exhausted = rowOf(sent.id());
        assertThat(exhausted.resends()).isEqualTo(RegulatorySubmissions.MAX_RESENDS);
        assertThat(exhausted.lastAnswer()).contains("NOT_RECEIVED");
        Optional<RegulatorySubmissions.Recorded> asked = driver.askBeforeNonDelivery(rowOf(sent.id()), correlation());
        assertThat(asked.map(RegulatorySubmissions.Recorded::disposition)).as("what the provider says now, kept").contains(RegulatorySubmissions.Disposition.NOT_RECEIVED);
        assertThat(inScope(sender, uow -> machine.declareUndelivered(uow, sent.id(), asked, REASON, sender, correlation())).status()).isEqualTo(SubmissionStatus.UNDELIVERED);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + RegulatorySubmissions.UNDELIVERED_EVENT + "' AND aggregate_id = '" + id + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the drive's permit: a claim takes only a permit that has come, one at a time, ten racing; SKIP LOCKED leaves an instance free of a locked row")
    void aClaimTakesOnlyAPermitThatHasCome() throws Exception {
        Case sent = submitted();
        UUID id = sent.id().value();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM accounting.regulatory_submission WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, id);
                lock.executeQuery();
            }
            Optional<RegulatorySubmissions.Claimed> skipped = inScope(Actor.SYSTEM, uow -> machine.claim(uow, sent.id(), LEASE));
            assertThat(skipped).as("a held row is skipped, never waited on").isEmpty();
            holder.rollback();
        }
        List<Callable<Optional<RegulatorySubmissions.Claimed>>> claims = new ArrayList<>();
        for (int at = 0; at < INSTANCES; at++) {
            claims.add(() -> inScope(Actor.SYSTEM, uow -> machine.claim(uow, sent.id(), LEASE)));
        }
        List<Object> outcomes = race(claims);
        assertThat(outcomes).filteredOn(outcome -> outcome instanceof Optional<?> claimed && claimed.isPresent()).hasSize(1);
        assertThat(rowOf(sent.id()).attempts()).isEqualTo(1);
        assertThat(rowOf(sent.id()).nextInquiryAt().orElseThrow()).isAfter(NOW.instant().plusSeconds(60));
        Optional<RegulatorySubmissions.Claimed> notYet = inScope(Actor.SYSTEM, uow -> machine.claim(uow, sent.id(), LEASE));
        assertThat(notYet).as("the permit is a lease ahead, and no lock is left to skip").isEmpty();
    }

    @Test
    @DisplayName("what stands, on the database's clock: overtaken until a return of the form is acknowledged at the month's present revision, a draft clearing nothing; stale_unsent; overdue; the wait backing off past the horizon; resends_exhausted; a page and its next; a pending list excluding its reader's own")
    void whatStands() throws Exception {
        RegulatorySubmissionStore.Attention before = attention();

        // Overtaken, and what clears it.
        Case sent = submitted();
        driver.submitIfDue(sent.id());
        assertThat(rowOf(sent.id()).status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        reopen(sent.month());
        assertThat(attention().overtaken()).isEqualTo(before.overtaken() + 1);
        assertThat(rowOf(sent.id()).overtaken()).isTrue();
        List<RegulatorySubmissionStore.Row> standing = inScope(Actor.SYSTEM, uow -> STORE.standing(uow, Optional.empty(), 100));
        assertThat(standing).extracting(RegulatorySubmissionStore.Row::id).contains(sent.id());

        PeriodCloseFixture.closed(sent.month());
        issued(sent.month());
        Actor preparer = person();
        AccountingPeriodId period = id(sent.month());
        RegulatorySubmissions.Acted drafted = inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(sent.form().obligation(), period, Optional.of(sent.id()), REASON), preparer, correlation()));
        assertThat(attention().overtaken()).as("a draft of a correction clears nothing").isEqualTo(before.overtaken() + 1);
        Case correction = approved(validated(new Case(sent.month(), sent.form(), drafted, preparer, person())));
        Actor sender = person();
        inScope(sender, uow -> machine.submit(uow, correction.id(), correction.read(), REASON, sender, correlation()));
        driver.submitIfDue(correction.id());
        assertThat(rowOf(correction.id()).status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(attention().overtaken()).isEqualTo(before.overtaken());
        assertThat(rowOf(sent.id()).overtaken()).isFalse();
        assertThat(rowOf(sent.id()).monthMoved()).isTrue();

        // Not yet sent, its month moved. This leaves that month OPEN; the next case in the ladder closes it.
        Case unsent = validated(drafted());
        reopen(unsent.month());
        assertThat(attention().staleUnsent()).isEqualTo(before.staleUnsent() + 1);

        // Overdue: a machine whose horizon is already behind it, so no case waits on a clock.
        RegulatorySubmissions pastItsHorizon = new RegulatorySubmissions(AccountingPeriodFixture.STORE, STORE, OBLIGATIONS, RUNS, adapters, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW, Duration.ofHours(-3));
        Case approved = approved();
        Actor late = person();
        inScope(late, uow -> pastItsHorizon.submit(uow, approved.id(), approved.read(), REASON, late, correlation()));
        assertThat(attention().overdue()).isEqualTo(before.overdue() + 1);

        // The wait backs off on the database's clock: three hours past the horizon, three hours; under a ceiling of one, one.
        inScope(Actor.SYSTEM, uow -> STORE.claim(uow, approved.id(), LEASE));
        inScope(Actor.SYSTEM, uow -> STORE.await(uow, approved.id(), 1, INTERVAL, RegulatorySubmissions.INQUIRY_CEILING, "UNKNOWN", Optional.empty()));
        RegulatorySubmissionStore.Row waiting = rowOf(approved.id());
        assertThat(Duration.between(waiting.answerHorizonAt().orElseThrow(), waiting.nextInquiryAt().orElseThrow())).isBetween(Duration.ofMinutes(359), Duration.ofMinutes(361));
        inScope(Actor.SYSTEM, uow -> STORE.await(uow, approved.id(), 1, INTERVAL, Duration.ofHours(1), "UNKNOWN", Optional.empty()));
        RegulatorySubmissionStore.Row ceiled = rowOf(approved.id());
        assertThat(Duration.between(ceiled.answerHorizonAt().orElseThrow(), ceiled.nextInquiryAt().orElseThrow())).isBetween(Duration.ofMinutes(239), Duration.ofMinutes(241));

        // Resends spent: the migrator standing in for five rounds, RESENDING first as the trigger asks.
        asMigrator("UPDATE accounting.regulatory_submission SET resends = ?, last_answer = 'RESENDING' WHERE id = ?", RegulatorySubmissions.MAX_RESENDS, approved.id().value());
        asMigrator("UPDATE accounting.regulatory_submission SET last_answer = 'NOT_RECEIVED' WHERE id = ?", approved.id().value());
        assertThat(attention().resendsExhausted()).isEqualTo(before.resendsExhausted() + 1);
        assertThat(RegulatorySubmissions.nonDeliveryOpen(rowOf(approved.id()))).isTrue();

        // A page and the next, on what is frozen: no row twice.
        List<RegulatorySubmissionStore.Row> first = inScope(Actor.SYSTEM, uow -> STORE.standing(uow, Optional.empty(), 2));
        assertThat(first).hasSize(2);
        List<RegulatorySubmissionStore.Row> next = inScope(Actor.SYSTEM, uow -> STORE.standing(uow, Optional.of(first.get(1).id()), 2));
        assertThat(next).extracting(RegulatorySubmissionStore.Row::id).doesNotContain(first.get(0).id(), first.get(1).id());

        Case validated = validated(drafted());
        List<RegulatorySubmissionStore.Row> own = inScope(validated.preparer(), uow -> machine.awaitingApproval(uow, validated.preparer()));
        assertThat(own).extracting(RegulatorySubmissionStore.Row::id).doesNotContain(validated.id());
        List<RegulatorySubmissionStore.Row> decidable = inScope(validated.approver(), uow -> machine.awaitingApproval(uow, validated.approver()));
        assertThat(decidable).extracting(RegulatorySubmissionStore.Row::id).contains(validated.id());
    }

    private static RegulatorySubmissionStore.Attention attention() {
        return inScope(Actor.SYSTEM, uow -> STORE.attention(uow, RegulatorySubmissions.MAX_RESENDS));
    }

    @Test
    @DisplayName("SUBMITTED with nothing sent, the month really reopened and re-closed before the claim: the first attempt only asks, nothing is sent, a resend is refused by the statement and for a raw writer by the trigger; a person concludes it and a correction goes at the new revision")
    void aMonthMovedBeforeTheClaimIsNeverSent() throws Exception {
        Case sent = submitted();
        roundTrip(sent.month());
        assertThat(rowOf(sent.id()).monthMoved()).isTrue();

        assertThat(driver.submitIfDue(sent.id())).isTrue();
        assertThat(provider.submits()).as("the regulator is handed nothing").isZero();
        assertThat(provider.inquiries()).isEqualTo(1);
        assertThat(provider.sendsReceived(sent.id())).isZero();
        RegulatorySubmissionStore.Row asked = rowOf(sent.id());
        assertThat(asked.status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(asked.attempts()).isEqualTo(1);
        assertThat(asked.resends()).isZero();
        assertThat(asked.lastAnswer()).contains("NOT_RECEIVED");
        assertThat(asked.overtaken()).isTrue();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'regulatory.SubmissionResent' AND target_id = '" + sent.id().value() + "'")).isZero();

        boolean permitted = inScope(Actor.SYSTEM, uow -> STORE.permitResend(uow, sent.id(), asked.attempts(), RegulatorySubmissions.MAX_RESENDS));
        assertThat(permitted).as("the statement's own condition").isFalse();
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET resends = resends + 1, last_answer = 'RESENDING' WHERE id = ?", sent.id().value())).as("for a raw writer").isInstanceOf(SQLException.class)
                .hasMessageContaining("a resend goes only while the month stands");

        // The door: open whatever the resend count, asked at the act. judge_stands is not on this edge: it is how a return its month overtook is ended.
        assertThat(RegulatorySubmissions.nonDeliveryOpen(asked)).isTrue();
        Optional<RegulatorySubmissions.Recorded> answered = driver.askBeforeNonDelivery(asked, correlation());
        assertThat(answered.map(RegulatorySubmissions.Recorded::disposition)).contains(RegulatorySubmissions.Disposition.NOT_RECEIVED);
        Actor sender = person();
        assertThat(inScope(sender, uow -> machine.declareUndelivered(uow, sent.id(), answered, REASON, sender, correlation())).status()).isEqualTo(SubmissionStatus.UNDELIVERED);

        Actor preparer = person();
        AccountingPeriodId period = id(sent.month());
        RegulatorySubmissions.Acted correction = inScope(preparer, uow -> machine.draft(uow, new RegulatorySubmissions.Draft(sent.form().obligation(), period, Optional.of(sent.id()), REASON), preparer, correlation()));
        assertThat(correction.periodRevision()).isEqualTo(row(sent.month()).revision()).isGreaterThan(sent.acted().periodRevision());
        assertThat(rowOf(correction.id()).corrects()).contains(sent.id());
    }

    @Test
    @DisplayName("a resend goes only while the month stands: permitted at the revision read, audited, one row; a reopen committed first refuses it, MONTH_MOVED")
    void aResendIsPermittedUnderThePeriod() throws Exception {
        Case sent = submitted();
        RegulatorySubmissions.Claimed claimed = inScope(Actor.SYSTEM, uow -> machine.claim(uow, sent.id(), LEASE)).orElseThrow();
        inScope(Actor.SYSTEM, uow -> STORE.await(uow, sent.id(), claimed.row().attempts(), INTERVAL, RegulatorySubmissions.INQUIRY_CEILING, "NOT_RECEIVED", Optional.empty()));
        RegulatorySubmissions.ResendPermit permitted = inScope(Actor.SYSTEM, uow -> machine.permitResend(uow, claimed, correlation()));
        assertThat(permitted).isEqualTo(RegulatorySubmissions.ResendPermit.PERMITTED);
        assertThat(rowOf(sent.id()).lastAnswer()).contains("RESENDING");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'regulatory.SubmissionResent' AND target_id = '" + sent.id().value() + "'")).isEqualTo(1);

        inScope(Actor.SYSTEM, uow -> STORE.await(uow, sent.id(), claimed.row().attempts(), INTERVAL, RegulatorySubmissions.INQUIRY_CEILING, "NOT_RECEIVED", Optional.empty()));
        reopen(sent.month());
        RegulatorySubmissions.ResendPermit refused = inScope(Actor.SYSTEM, uow -> machine.permitResend(uow, claimed, correlation()));
        assertThat(refused).isEqualTo(RegulatorySubmissions.ResendPermit.MONTH_MOVED);
        assertThat(rowOf(sent.id()).resends()).isEqualTo(1);
    }

    @Test
    @DisplayName("a read proves what it serves: each line agrees with the run's figure read back from the database's bytes")
    void aReadProvesWhatItServes() {
        Case draft = drafted();
        RegulatorySubmissions.View view = inScope(Actor.SYSTEM, uow -> machine.view(uow, draft.id()));
        assertThat(view.lines()).hasSize(4).allSatisfy(line -> assertThat(line.agrees()).isTrue());
        List<RegulatoryFormLine> lines = inScope(Actor.SYSTEM, uow -> STORE.lines(uow, draft.id()));
        assertThat(lines.get(0).minor().longValueExact()).isEqualTo(100_00);
        assertThat(view.stillStands()).isTrue();
    }

    // ================================================================== the obligation

    @Test
    @DisplayName("an obligation, for the application role and the migrator where each is tried: REFERENCE by CHECK; four eyes by CHECK; two activations of one form race and one version stands; a rule outside the vocabulary refused; frozen but for its decision")
    void anObligationIsHeldForEveryWriter() throws Exception {
        String form = "REF-TEST-" + FORMS.incrementAndGet();
        String configuration = RegulatoryWorld.configuration().canonical();
        String sha = CanonicalJson.sha256(configuration);
        String insert = "INSERT INTO accounting.regulatory_obligation (id, jurisdiction_code, form_code, form_version, period_type, adapter_name, standing, configuration, configuration_sha256, status, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id)"
                + " VALUES (?, 'REF', ?, ?, 'MONTH', 'scripted-reference', ?, ?, ?, 'PROPOSED', 'raw-writer', 'CUSTOMER', clock_timestamp(), 'a reason', 'c')";
        assertThatThrownBy(() -> asApplication(insert, IDS.next(), form, 1, "STATUTORY", configuration, sha)).isInstanceOfSatisfying(SQLException.class, refused -> {
            assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(refused.getMessage()).contains("regulatory_obligation_is_reference");
        });
        assertThatThrownBy(() -> asApplication(insert, IDS.next(), form, 1, "REFERENCE", configuration, "0".repeat(64))).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_obligation_configuration_hashed");
        String script = configuration.replace("\"type\":\"EQUALS\"", "\"type\":\"RUN_THIS_SQL\"");
        assertThatThrownBy(() -> asApplication(insert, IDS.next(), form, 1, "REFERENCE", script, CanonicalJson.sha256(script))).isInstanceOf(SQLException.class).hasMessageContaining("regulatory_obligation_rules_are_of_the_vocabulary");

        Actor proposer = person();
        RegulatoryObligations.Acted second = inScope(proposer, uow -> obligations.propose(uow, new RegulatoryObligations.Proposal("REF", form, 2, ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration()), REASON, proposer, correlation()));
        RegulatoryObligations.Acted third = inScope(proposer, uow -> obligations.propose(uow, new RegulatoryObligations.Proposal("REF", form, 3, ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration()), REASON, proposer, correlation()));
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_obligation SET status = 'ACTIVE', decided_by = proposed_by, decided_by_type = 'CUSTOMER', decided_at = clock_timestamp(), decision_reason = 'mine' WHERE id = ?", second.id().value()))
                .isInstanceOfSatisfying(SQLException.class, refused -> {
                    assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION);
                    assertThat(refused.getMessage()).contains("regulatory_obligation_four_eyes");
                });
        assertThatThrownBy(() -> inScope(proposer, uow -> obligations.activate(uow, second.id(), second.configurationSha256(), REASON, proposer, correlation()))).isInstanceOf(RegulatoryObligations.SelfApprovalRefused.class);
        assertThatThrownBy(() -> asMigrator("UPDATE accounting.regulatory_obligation SET configuration = '{}' WHERE id = ?", second.id().value())).isInstanceOf(SQLException.class).hasMessageContaining("frozen");

        Actor first = person();
        Actor other = person();
        List<Callable<RegulatoryObligations.Acted>> activations = new ArrayList<>();
        activations.add(() -> inScope(first, uow -> obligations.activate(uow, second.id(), second.configurationSha256(), REASON, first, correlation())));
        activations.add(() -> inScope(other, uow -> obligations.activate(uow, third.id(), third.configurationSha256(), REASON, other, correlation())));
        List<Object> outcomes = race(activations);
        assertThat(count("SELECT count(*) FROM accounting.regulatory_obligation WHERE form_code = '" + form + "' AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(outcomes).filteredOn(RegulatoryObligations.Acted.class::isInstance).isNotEmpty();
        assertThat(inScope(Actor.SYSTEM, uow -> OBLIGATIONS.find(uow, third.id())).orElseThrow().status()).as("the later version wins, whichever came first").isEqualTo(RegulatoryObligationStatus.ACTIVE);
    }

    @Test
    @DisplayName("an obligation retired under a return: an act going forward refuses, in the machine and for a raw writer")
    void aRetiredVersionRefuses() throws Exception {
        Case draft = drafted();
        Actor proposer = person();
        RegulatoryObligations.Acted later = inScope(proposer, uow -> obligations.propose(uow, new RegulatoryObligations.Proposal("REF", draft.form().code(), 2, ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration()), REASON, proposer, correlation()));
        Actor activator = person();
        inScope(activator, uow -> obligations.activate(uow, later.id(), later.configurationSha256(), REASON, activator, correlation()));

        assertThatThrownBy(() -> inScope(draft.preparer(), uow -> machine.validate(uow, draft.id(), draft.preparer(), correlation()))).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThatThrownBy(() -> asApplication("UPDATE accounting.regulatory_submission SET status = 'VALIDATED', validation_outcome = '{}', validation_passed = TRUE, validated_by = 'raw', validated_by_type = 'CUSTOMER', validated_at = clock_timestamp() WHERE id = ?", draft.id().value()))
                .isInstanceOf(SQLException.class).hasMessageContaining("obligation version is in force");
    }

    @Test
    @DisplayName("a row's reason is not in a store's failure")
    void aStoresFailureCarriesNoRow() {
        Case draft = drafted();
        assertThatThrownBy(() -> inScope(Actor.SYSTEM, uow -> {
            STORE.appendEvent(uow, new RegulatorySubmissionStore.EventRow(IDS.next(), draft.id(), RegulatorySubmissionStore.Act.APPROVED, Optional.of(SubmissionStatus.VALIDATED), SubmissionStatus.APPROVED, Actor.SYSTEM, Optional.empty(), Optional.of("THE-SECRET-REASON"), correlation()));
            return null;
        })).isInstanceOf(com.finapp.accounting.AccountingStorageException.class).hasMessageNotContaining("THE-SECRET-REASON");
    }
}
