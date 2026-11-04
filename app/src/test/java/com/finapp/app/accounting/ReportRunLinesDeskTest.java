package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A report run's figure drilled to its lines (`P14-TSK-016`; {@code INV-ACC-02}): under the <em>run's</em> version, so an opening has a route even where a month's own version maps a purpose elsewhere, or no version was in force at all; in one read-only snapshot, recorded {@code accounting.GlRead} before it is served; {@code exact}
 * only of a FINAL run; a month after the run's period refused. The route {@code ACCOUNTING_READ}, the accountant's alone (the interceptor's refusal of a caller without it is {@code DenyByDefaultDatabaseTest}'s, the declaration pinned by {@code RoutePermissionRegisterTest}). Hermetic: the journal is {@code InMemoryJournal}, the session a stand-in.
 */
@DisplayName("a report run's figure drilled to its lines (P14-TSK-016)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ReportRunLinesDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-03T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final Connection UOW = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("the fakes never touch the connection");
    });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.EMPLOYEE);

    private final InMemoryJournal journal = new InMemoryJournal();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(CLOCK, periods);
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<SnapshotSession> sessions = new ArrayList<>();
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(UOW);
        }
    };
    private final TestReportDefinition echo = TestReportDefinition.echo();
    private final ReportRuns runs = new ReportRuns(store, periods, mappings, ReportDefinitions.of(echo), ReportRuns.Leases.STANDARD, (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
    private GlMappingVersionId version;

    /** The month's period as the store holds it, as {@code JdbcPeriodPins} reads it from the row. */
    private GlPins.PeriodPins pins() {
        return (snapshot, month) -> periods.recent(UOW, 100).stream().filter(row -> row.month().equals(month)).findFirst().map(row -> new GlPins.PeriodPin(row.status(), Optional.empty()));
    }

    private ReportRunLinesDesk desk() {
        return new ReportRunLinesDesk(runs, mappings, pins(), new GeneralLedger(journal), new GlSnapshots(() -> {
            SnapshotSession session = new SnapshotSession();
            sessions.add(session);
            return session.connection();
        }, GlSnapshots.STATEMENT_TIMEOUT), transactions, (unitOfWork, record) -> audit.add(record), AccountingFlowScope.NONE, IDS, CLOCK);
    }

    /** A version active, September locked under it: a FINAL run of the probe definition. */
    private ReportRunStore.RunRow aFinalRun() {
        version = GlMappingVersionId.next(IDS);
        mappings.insertProposal(UOW, new GlMappingStore.NewProposal(version, 1, ReferenceGlMapping.version(SEPTEMBER), ACCOUNTANT, "the reference", CorrelationId.of("flow-propose"), UUID.randomUUID()));
        mappings.decide(UOW, version, GlMappingVersionStatus.ACTIVE, new Actor("controller-1", ActorType.EMPLOYEE), "ok");
        AccountingPeriodId september = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(september, SEPTEMBER, CorrelationId.of("flow-open")));
        periods.markLocked(UOW, september, 1, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        ReportRuns.Claimed claimed = runs.claim(UOW, TestReportDefinition.CODE, september, RunParameters.of(Map.of("probe", "a"), Optional.empty()), ACCOUNTANT, CorrelationId.of("flow-claim"));
        return runs.complete(UOW, claimed.run().id(), claimed.run().attempt(), new ReportGeneration.Generated("{}", com.finapp.accounting.reporting.CanonicalJson.sha256("{}")), ACCOUNTANT).run();
    }

    @Test
    @DisplayName("a figure's lines, month by month, by page: under the run's version, read in one snapshot, recorded once before it is answered: never an amount in the summary")
    void aFiguresLinesAreServedAndRecorded() {
        ReportRunStore.RunRow run = aFinalRun();
        journal.post(AUGUST.atDay(3), "EUR", 2, 10_000, "CASH_AT_BANK", "OPERATIONAL", "CUSTOMER_WALLET", "CUSTOMER");
        journal.post(SEPTEMBER.atDay(3), "EUR", 2, 250, "CUSTOMER_WALLET", "CUSTOMER", "CASH_AT_BANK", "OPERATIONAL");
        journal.post(SEPTEMBER.atDay(4), "EUR", 2, 40, "CUSTOMER_WALLET", "CUSTOMER", "CASH_AT_BANK", "OPERATIONAL");

        ReportRunLinesDesk.ReportRunLinesView first = as(ACCOUNTANT, () -> desk().lines(run.id().value().toString(), "1000", "2026-09", "EUR", null, 1));
        assertThat(first.run()).isEqualTo(run.id().value().toString());
        assertThat(first.status()).isEqualTo("FINAL");
        assertThat(first.exact()).as("a FINAL run's pages sum to its figure").isTrue();
        assertThat(first.activity().lines()).hasSize(1);
        assertThat(first.activity().figure().credits()).isEqualTo("2.90");
        assertThat(first.activity().next()).isNotNull();
        ReportRunLinesDesk.ReportRunLinesView second = as(ACCOUNTANT, () -> desk().lines(run.id().value().toString(), "1000", "2026-09", "EUR", first.activity().next(), 1));
        assertThat(second.activity().lines()).hasSize(1);
        assertThat(second.activity().next()).as("a full page says there may be more").isNotNull();
        ReportRunLinesDesk.ReportRunLinesView last = as(ACCOUNTANT, () -> desk().lines(run.id().value().toString(), "1000", "2026-09", "EUR", second.activity().next(), 1));
        assertThat(last.activity().lines()).isEmpty();
        assertThat(last.activity().next()).isNull();
        BigDecimal credits = new BigDecimal(first.activity().lines().get(0).amount()).add(new BigDecimal(second.activity().lines().get(0).amount()));
        assertThat(credits).as("the pages sum to the figure").isEqualByComparingTo(first.activity().figure().credits());

        ReportRunLinesDesk.ReportRunLinesView earlier = as(ACCOUNTANT, () -> desk().lines(run.id().value().toString(), "1000", "2026-08", "EUR", null, null));
        assertThat(earlier.activity().lines()).as("an opening walked back, a month the run read and did not report").singleElement().satisfies(line -> assertThat(line.amount()).isEqualTo("100.00"));

        assertThat(sessions).as("one snapshot each").hasSize(4);
        assertThat(sessions).allSatisfy(session -> assertThat(session.calls).contains("readOnly=true", "verify").endsWith("rollback", "close"));
        assertThat(audit).hasSize(4).allSatisfy(record -> {
            assertThat(record.operation()).isEqualTo(AccountingAuditAction.GL_READ);
            assertThat(record.actor()).isEqualTo(ACCOUNTANT);
            assertThat(record.targetType()).isEqualTo("report_run");
            assertThat(record.targetId()).isEqualTo(run.id().value().toString());
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("door=report-run-lines", "account=1000", "currency=EUR", "version=1").doesNotContain("2.90", "100.00", "290"));
        });
    }

    @Test
    @DisplayName("why it exists: a month before the run's version took effect has a route here, under the run's version, and the GL's own door cannot read it at all; and a purpose a later version moved reads under the run's account")
    void itReachesWhatTheGlsDoorCannot() {
        ReportRunStore.RunRow run = aFinalRun();
        journal.post(YearMonth.of(2020, 1).atDay(2), "EUR", 2, 777, "CASH_AT_BANK", "OPERATIONAL", "CUSTOMER_WALLET", "CUSTOMER");
        GeneralLedgerDesk theGlsDoor = new GeneralLedgerDesk(new GeneralLedger(journal), new com.finapp.accounting.GlPins(mappings, (snapshot, month) -> Optional.empty()), new GlSnapshots(() -> new SnapshotSession().connection(), GlSnapshots.STATEMENT_TIMEOUT),
                transactions, (unitOfWork, record) -> { }, AccountingFlowScope.NONE, IDS, CLOCK);
        refused(() -> as(ACCOUNTANT, () -> theGlsDoor.activity("1000", "2020-01", "EUR", null, null)), AccountingErrorCode.NO_ACTIVE_MAPPING, 409);

        ReportRunLinesDesk.ReportRunLinesView read = as(ACCOUNTANT, () -> desk().lines(run.id().value().toString(), "1000", "2020-01", "EUR", null, null));
        assertThat(read.activity().lines()).singleElement().satisfies(line -> assertThat(line.amount()).isEqualTo("7.77"));
        assertThat(read.activity().mappingVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("the month's own status, as the GL's door serves it: the run's locked month reads LOCKED, whatever the run is; a month no period row holds reads NONE, which means exactly that")
    void theMonthsOwnStatus() {
        String run = aFinalRun().id().value().toString();
        assertThat(as(ACCOUNTANT, () -> desk().lines(run, "1000", "2026-09", "EUR", null, null)).activity().periodStatus()).isEqualTo("LOCKED");
        assertThat(as(ACCOUNTANT, () -> desk().lines(run, "1000", "2026-08", "EUR", null, null)).activity().periodStatus()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("exact only of a FINAL run: SUPERSEDED, its period having moved, the lines are the journal's as they stand, and it says so")
    void exactOnlyOfAFinalRun() {
        ReportRunStore.RunRow run = aFinalRun();
        store.supersedeFinal(UOW, run.periodId(), CorrelationId.of("flow-unlock"));
        assertThat(store.find(UOW, run.id()).orElseThrow().status()).isEqualTo(RunStatus.SUPERSEDED);
        ReportRunLinesDesk.ReportRunLinesView read = as(ACCOUNTANT, () -> desk().lines(run.id().value().toString(), "1000", "2026-09", "EUR", null, null));
        assertThat(read.status()).isEqualTo("SUPERSEDED");
        assertThat(read.exact()).isFalse();
    }

    @Test
    @DisplayName("refused, nothing recorded: an unknown or malformed run and an account the version does not hold 404; a run with no version 409; a month after the run's period, a malformed month, currency, size or cursor 422; no currency 422")
    void theRefusals() {
        ReportRunStore.RunRow run = aFinalRun();
        String id = run.id().value().toString();
        refused(() -> as(ACCOUNTANT, () -> desk().lines(UUID.randomUUID().toString(), "1000", "2026-09", "EUR", null, null)), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk().lines("not-a-uuid", "1000", "2026-09", "EUR", null, null)), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "9999", "2026-09", "EUR", null, null)), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "cash", "2026-09", "EUR", null, null)), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "1000", "2026-10", "EUR", null, null)), PlatformErrorCode.VALIDATION_FAILED, 422);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "1000", "September", "EUR", null, null)), PlatformErrorCode.VALIDATION_FAILED, 422);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "1000", "2026-09", "euro", null, null)), PlatformErrorCode.VALIDATION_FAILED, 422);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "1000", "2026-09", null, null, null)), PlatformErrorCode.VALIDATION_FAILED, 422);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "1000", "2026-09", "EUR", "nonsense", null)), PlatformErrorCode.VALIDATION_FAILED, 422);
        refused(() -> as(ACCOUNTANT, () -> desk().lines(id, "1000", "2026-09", "EUR", null, 501)), PlatformErrorCode.VALIDATION_FAILED, 422);
        assertThat(audit).isEmpty();

        YearMonth october = SEPTEMBER.plusMonths(1);
        AccountingPeriodId next = AccountingPeriodId.next(IDS);
        periods.closeForTest(SEPTEMBER);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(next, october, CorrelationId.of("flow-open")));
        periods.markLocked(UOW, next, 1, ACCOUNTANT, Optional.empty());
        ReportRuns.Claimed unpinned = runs.claim(UOW, TestReportDefinition.CODE, next, RunParameters.of(Map.of("probe", "a"), Optional.empty()), ACCOUNTANT, CorrelationId.of("flow-claim"));
        refused(() -> as(ACCOUNTANT, () -> desk().lines(unpinned.run().id().value().toString(), "1000", october.toString(), "EUR", null, null)), AccountingErrorCode.NO_ACTIVE_MAPPING, 409);
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("an actor-less call reads and records nothing; a record that cannot be written fails the read: nothing is served unrecorded")
    void nothingServedUnrecorded() {
        ReportRunStore.RunRow run = aFinalRun();
        assertThatThrownBy(() -> desk().lines(run.id().value().toString(), "1000", "2026-09", "EUR", null, null)).isNotInstanceOf(ApiException.class);
        assertThat(sessions).isEmpty();
        assertThat(audit).isEmpty();

        ReportRunLinesDesk unrecording = new ReportRunLinesDesk(runs, mappings, pins(), new GeneralLedger(journal), new GlSnapshots(() -> new SnapshotSession().connection(), GlSnapshots.STATEMENT_TIMEOUT), transactions, (unitOfWork, record) -> {
            throw new IllegalStateException("the audit table is away");
        }, AccountingFlowScope.NONE, IDS, CLOCK);
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> unrecording.lines(run.id().value().toString(), "1000", "2026-09", "EUR", null, null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the route: GET under report-runs, ACCOUNTING_READ, which the accountant's role alone holds - a controller or a regulatory reporter is refused it by the interceptor")
    void theRouteIsTheAccountants() throws NoSuchMethodException {
        assertThat(ReportRunLinesController.class.getAnnotation(RequestMapping.class).path()).containsExactly("/operator/accounting/report-runs");
        Method handler = Arrays.stream(ReportRunLinesController.class.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(GetMapping.class)).findFirst().orElseThrow();
        assertThat(handler.getAnnotation(GetMapping.class).value()).containsExactly("/{runId}/lines");
        assertThat(handler.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ACCOUNTING_READ);
        assertThat(Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(PermissionName.ACCOUNTING_READ))).containsExactly(RoleName.ACCOUNTANT);
        assertThat(RoleName.FINANCIAL_CONTROLLER.permissions()).doesNotContain(PermissionName.ACCOUNTING_READ);
        assertThat(RoleName.REGULATORY_REPORTER.permissions()).doesNotContain(PermissionName.ACCOUNTING_READ);
    }

    // ------------------------------------------------------------------

    private static void refused(Runnable call, ErrorCode code, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(code.status()).isEqualTo(status);
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }
}
