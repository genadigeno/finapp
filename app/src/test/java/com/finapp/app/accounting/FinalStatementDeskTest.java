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
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunFailure;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryReportRunStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A controller's read of a FINAL statement (`P14-TSK-022`, the owner's ruling (v); ADR-0115): the door, its one {@code 404}, what it withholds, the real status of the month, and a read recorded before it is served. Real {@code ReportRuns} over the in-memory store and period machine, so a run reaches each status by its
 * own edges. That the two real definitions' real bytes name no door once projected is {@code StatementNotesAreFrozenTest#aControllersViewNamesNoDoor}; the interceptor's refusal of a caller without the permission is {@code DenyByDefaultDatabaseTest}'s, the declaration pinned here and by {@code RoutePermissionRegisterTest}.
 */
@DisplayName("a controller's read of a FINAL statement (P14-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class FinalStatementDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2096-12-03T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final YearMonth AUGUST = YearMonth.of(2096, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2096, 9);
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("the fakes never touch the connection");
    });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.EMPLOYEE);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.EMPLOYEE);
    private static final String FIGURE = "123456";

    /** A statement's body in the issued form: a figure, a door at the top, one in a note, and a note's reasons. */
    private static CanonicalJson.Obj issued() {
        return CanonicalJson.object()
                .put("sections", CanonicalJson.array().add(CanonicalJson.object().put("closingMinor", FIGURE).put("route", "GET /v1/operator/somewhere (ACCOUNTING_READ)")))
                .put("drillDown", CanonicalJson.object().put("movement", "GET /v1/operator/accounting/gl/accounts/{glAccount}/activity (ACCOUNTING_READ)"))
                .put("notes", CanonicalJson.object()
                        .put("suspense", CanonicalJson.object().put("items", CanonicalJson.array()).put("route", "GET /v1/operator/reconciliation/breaks/{id} (RECONCILIATION_INVESTIGATE)"))
                        .put("closeRecord", CanonicalJson.object().put("revision", 1L).put("reasons", "read at GET .../close-run (ACCOUNTING_READ)"))
                        .put("priorPeriodCorrections", CanonicalJson.object().put("untraced", 0L)));
    }

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final InMemoryReportRunStore store = new InMemoryReportRunStore(CLOCK, periods);
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private final ReportRuns runs = new ReportRuns(store, periods, mappings, ReportDefinitions.of(
            new TestReportDefinition(BalanceSheetDefinition.CODE, 1, Set.of(), false, (snapshot, inputs) -> issued()),
            new TestReportDefinition(IncomeStatementDefinition.CODE, 1, Set.of(), false, (snapshot, inputs) -> issued()),
            new TestReportDefinition(TrialBalanceDefinition.CODE, 1, Set.of(), false, (snapshot, inputs) -> issued())),
            ReportRuns.Leases.STANDARD, (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(UOW);
        }
    };
    private final ReportSnapshots snapshots = new ReportSnapshots() {
        @Override
        public <R> R read(Function<Connection, R> work) {
            order.add("snapshot");
            return work.apply(UOW);
        }
    };
    /** The period's status as {@code JdbcPeriodPins} reads it from the row. */
    private final GlPins.PeriodPins pins = (snapshot, month) -> periods.recent(UOW, 100).stream().filter(row -> row.month().equals(month)).findFirst().map(row -> new GlPins.PeriodPin(row.status(), Optional.empty()));
    private final FinalStatementDesk desk = new FinalStatementDesk(runs, pins, snapshots, transactions, (uow, record) -> {
        order.add("recorded");
        audited.add(record);
    }, AccountingFlowScope.NONE, IDS, CLOCK);
    private final GlMappingVersionId version = aVersion();

    private GlMappingVersionId aVersion() {
        GlMappingVersionId id = GlMappingVersionId.next(IDS);
        mappings.insertProposal(UOW, new GlMappingStore.NewProposal(id, 1, ReferenceGlMapping.version(AUGUST), ACCOUNTANT, "the reference", CorrelationId.of("flow-propose"), UUID.randomUUID()));
        mappings.decide(UOW, id, GlMappingVersionStatus.ACTIVE, CONTROLLER, "ok");
        return id;
    }

    private AccountingPeriodId august() {
        AccountingPeriodId id = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(id, AUGUST, CorrelationId.of("flow-open")));
        return id;
    }

    private void lockAndClose(AccountingPeriodId id) {
        int revision = periods.find(UOW, id).orElseThrow().revision();
        periods.markLocked(UOW, id, revision, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        periods.markCloseProposed(UOW, id, revision);
        periods.markClosed(UOW, id, revision);
    }

    private ReportRuns.Claimed claimed(String code, AccountingPeriodId id) {
        return runs.claim(UOW, code, id, RunParameters.none(), ACCOUNTANT, CorrelationId.of("flow-claim"));
    }

    private ReportRunStore.RunRow completed(String code, AccountingPeriodId id) {
        ReportRuns.Claimed claim = claimed(code, id);
        String text = CanonicalJson.text(issued());
        return runs.complete(UOW, claim.run().id(), claim.run().attempt(), new ReportGeneration.Generated(text, CanonicalJson.sha256(text)), ACCOUNTANT).run();
    }

    private FinalStatementDesk.FinalStatementView read(ReportRunId id) {
        return as(CONTROLLER, () -> desk.read(id.value().toString()));
    }

    @Test
    @DisplayName("a controller reads either FINAL statement: the figures as issued, every door withheld and each path named, the hash the issued bytes', what opens said once; recorded accounting.FinalStatementRead before it is served, naming no figure")
    void aControllerReadsAFinalStatement() {
        AccountingPeriodId august = august();
        lockAndClose(august);
        for (String code : List.of(BalanceSheetDefinition.CODE, IncomeStatementDefinition.CODE)) {
            ReportRunStore.RunRow run = completed(code, august);
            order.clear();
            int recorded = audited.size();

            FinalStatementDesk.FinalStatementView view = read(run.id());

            assertThat(view.definition()).isEqualTo(code);
            assertThat(view.status()).isEqualTo("FINAL");
            assertThat(view.period()).isEqualTo(AUGUST.toString());
            assertThat(view.periodStatus()).isEqualTo("CLOSED");
            assertThat(view.issuedSha256()).isEqualTo(CanonicalJson.sha256(CanonicalJson.text(issued())));
            assertThat(view.statement()).contains("\"closingMinor\":\"" + FIGURE + "\"").contains("\"untraced\":\"0\"").contains("\"revision\":\"1\"");
            assertThat(view.statement()).doesNotContain("drillDown").doesNotContain("\"route\"").doesNotContain("\"reasons\"").doesNotContain("/v1/").doesNotContain("ACCOUNTING_READ").doesNotContain("RECONCILIATION_INVESTIGATE");
            assertThat(view.withheld()).containsExactly("drillDown", "notes.closeRecord.reasons", "notes.suspense.route", "sections.route");
            assertThat(view.doors()).isEqualTo(FinalStatementDesk.DOORS).contains("none of them opens under ACCOUNTING_CLOSE_APPROVE").contains("GET /v1/operator/accounting/periods/{periodId}/close-proposal").contains("hand over this run's id").contains("untraced");
            assertThat(order).as("read, recorded, then served").containsExactly("snapshot", "recorded");
            assertThat(audited).hasSize(recorded + 1);
            AuditRecord record = audited.get(audited.size() - 1);
            assertThat(record.operation()).isEqualTo(AccountingAuditAction.FINAL_STATEMENT_READ);
            assertThat(record.actor()).isEqualTo(CONTROLLER);
            assertThat(record.targetType()).isEqualTo(FinalStatementDesk.TARGET);
            assertThat(record.targetId()).isEqualTo(run.id().value().toString());
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.reason()).isEmpty();
            assertThat(record.changeSummary().orElseThrow()).contains("definition=" + code).contains("periodStatus=CLOSED").doesNotContain(FIGURE);
            assertThat(view.toString()).doesNotContain(FIGURE);
        }
    }

    @Test
    @DisplayName("everything else is one 404, indistinguishable: another definition FINAL, the statement PROVISIONAL, GENERATING, FAILED and SUPERSEDED, an unknown id, a malformed one and none at all - the same code, title and detail, and nothing recorded")
    void everythingElseIsOneNotFound() {
        AccountingPeriodId august = august();
        lockAndClose(august);
        ReportRunStore.RunRow trialBalance = completed(TrialBalanceDefinition.CODE, august);
        ReportRunStore.RunRow generating = claimed(BalanceSheetDefinition.CODE, august).run();
        ReportRuns.Claimed failing = claimed(IncomeStatementDefinition.CODE, august);
        runs.fail(UOW, failing.run().id(), failing.run().attempt(), RunFailure.DEFINITION_FAILED, ACCOUNTANT, CorrelationId.of("flow-fail"));
        AccountingPeriodId september = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(september, SEPTEMBER, CorrelationId.of("flow-open")));
        ReportRunStore.RunRow provisional = completed(BalanceSheetDefinition.CODE, september);
        assertThat(provisional.status().name()).isEqualTo("PROVISIONAL");

        List<String> asked = new ArrayList<>(List.of(trialBalance.id().value().toString(), generating.id().value().toString(), failing.run().id().value().toString(), provisional.id().value().toString(),
                ReportRunId.next(IDS).value().toString(), "not-an-id"));
        asked.add(null);
        List<List<String>> answers = new ArrayList<>();
        for (String id : asked) {
            assertThatThrownBy(() -> as(CONTROLLER, () -> desk.read(id))).as(String.valueOf(id)).isInstanceOfSatisfying(ApiException.class, refused -> {
                assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.NOT_FOUND);
                assertThat(refused.errorCode().status()).isEqualTo(404);
                answers.add(List.of(refused.errorCode().code(), refused.getMessage(), refused.clientDetail().orElseThrow()));
            });
        }
        assertThat(answers).hasSize(asked.size());
        assertThat(Set.copyOf(answers)).as("one answer, whatever the run is").hasSize(1);
        assertThat(audited).as("a refusal served nothing, so it records nothing").isEmpty();
    }

    @Test
    @DisplayName("a month after a reopen: its FINAL statement is SUPERSEDED in the reopen's transaction, so the id answers the 404 at once and history is the accountant's door; the month's next FINAL, once closed again, is a new run, read at its own revision")
    void aMonthAfterAReopen() {
        AccountingPeriodId august = august();
        lockAndClose(august);
        ReportRunStore.RunRow first = completed(BalanceSheetDefinition.CODE, august);
        assertThat(read(first.id()).periodRevision()).isEqualTo(1);

        assertThat(store.supersedeFinal(UOW, august, CorrelationId.of("flow-reopen"))).isPositive();
        periods.markReopened(UOW, august, 1);
        assertThat(runs.find(UOW, first.id()).orElseThrow().status().name()).isEqualTo("SUPERSEDED");
        assertThatThrownBy(() -> read(first.id())).as("superseded: the ruling says FINAL").isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.NOT_FOUND));

        lockAndClose(august);
        ReportRunStore.RunRow second = completed(BalanceSheetDefinition.CODE, august);
        assertThat(second.id()).isNotEqualTo(first.id());
        FinalStatementDesk.FinalStatementView view = read(second.id());
        assertThat(view.periodRevision()).isEqualTo(2);
        assertThat(view.periodStatus()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("the month's real status, read from its own row in the run's snapshot, never inferred from the run: what the row says is what is served, and NONE when no row holds it")
    void theMonthsRealStatus() {
        AccountingPeriodId august = august();
        lockAndClose(august);
        ReportRunStore.RunRow run = completed(IncomeStatementDefinition.CODE, august);
        FinalStatementDesk reading = new FinalStatementDesk(runs, (snapshot, month) -> Optional.of(new GlPins.PeriodPin(PeriodStatus.LOCKED, Optional.empty())), snapshots, transactions, (uow, record) -> { }, AccountingFlowScope.NONE, IDS, CLOCK);
        assertThat(as(CONTROLLER, () -> reading.read(run.id().value().toString())).periodStatus()).as("the row's word, whatever the run is").isEqualTo("LOCKED");
        FinalStatementDesk none = new FinalStatementDesk(runs, (snapshot, month) -> Optional.empty(), snapshots, transactions, (uow, record) -> { }, AccountingFlowScope.NONE, IDS, CLOCK);
        assertThat(as(CONTROLLER, () -> none.read(run.id().value().toString())).periodStatus()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("a record that cannot be written fails the read: nothing is served unrecorded")
    void nothingServedUnrecorded() {
        AccountingPeriodId august = august();
        lockAndClose(august);
        ReportRunStore.RunRow run = completed(BalanceSheetDefinition.CODE, august);
        FinalStatementDesk unrecording = new FinalStatementDesk(runs, pins, snapshots, transactions, (uow, record) -> {
            throw new IllegalStateException("the audit table is away");
        }, AccountingFlowScope.NONE, IDS, CLOCK);
        assertThatThrownBy(() -> as(CONTROLLER, () -> unrecording.read(run.id().value().toString()))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the projection removes those members at any depth, in an array too, and nothing else: the rest of the tree is the issued tree, order included; and it names no path it did not remove")
    void theProjectionRemovesOnlyTheDoors() {
        String issued = CanonicalJson.text(CanonicalJson.object().put("a", "1").put("list", CanonicalJson.array().add(CanonicalJson.object().put("route", "x").put("b", "2")).add(CanonicalJson.object().put("c", "3"))).put("n", CanonicalJson.Null.INSTANCE).put("t", true));

        FinalStatementDesk.Projection projection = FinalStatementDesk.project(issued);

        assertThat(projection.statement()).isEqualTo("{\"a\":\"1\",\"list\":[{\"b\":\"2\"},{\"c\":\"3\"}],\"n\":null,\"t\":true}");
        assertThat(projection.withheld()).containsExactly("list.route");
        assertThat(FinalStatementDesk.project("{\"a\":\"1\"}").withheld()).isEmpty();
    }

    @Test
    @DisplayName("the door: GET under report-runs, ACCOUNTING_CLOSE_APPROVE, not keyed; the controller holds it and no ACCOUNTING_READ, which stays the accountant's alone, so a figure's lines and the run's whole bytes stay out of its reach")
    void theDoorIsTheControllers() {
        assertThat(FinalStatementController.class.getAnnotation(RequestMapping.class).path()).containsExactly("/operator/accounting/report-runs");
        Method handler = Arrays.stream(FinalStatementController.class.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(GetMapping.class)).findFirst().orElseThrow();
        assertThat(handler.getAnnotation(GetMapping.class).value()).containsExactly("/{runId}/final-statement");
        assertThat(handler.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ACCOUNTING_CLOSE_APPROVE);
        assertThat(handler.isAnnotationPresent(RequiresIdempotencyKey.class)).isFalse();
        assertThat(RoleName.FINANCIAL_CONTROLLER.permissions()).contains(PermissionName.ACCOUNTING_CLOSE_APPROVE).doesNotContain(PermissionName.ACCOUNTING_READ);
        assertThat(Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(PermissionName.ACCOUNTING_READ))).containsExactly(RoleName.ACCOUNTANT);
        assertThat(RoleName.ACCOUNTANT.permissions()).doesNotContain(PermissionName.ACCOUNTING_CLOSE_APPROVE);
        assertThat(Map.of("lines", Arrays.stream(ReportRunLinesController.class.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(GetMapping.class)).findFirst().orElseThrow().getAnnotation(RequiresPermission.class).value()))
                .as("no journal line through this permission").containsEntry("lines", PermissionName.ACCOUNTING_READ);
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }
}
