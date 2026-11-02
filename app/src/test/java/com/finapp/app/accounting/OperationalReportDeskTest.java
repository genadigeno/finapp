package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.OperationalReports;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.ValuationRateSetId;
import com.finapp.accounting.reporting.CloseStatusDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The operational report door (`P14-TSK-017`; ADR-0115 As built; {@code PHASE_14_PLAN.md} sections 10, 12): {@code CLOSE_STATUS} read
 * live under {@code ACCOUNTING_READ} - the role that asks for a translated run finds the approved set here (`P14-TSK-015`'s review,
 * M-4), in a read of the very state it would otherwise be refused; recorded before it is served, with a failed record failing the read;
 * the refusals on the contract; nothing stored. The route's 401/403 over HTTP are the interceptor's, held by {@code
 * RoutePermissionRegisterTest}. The real reads run on a stand-in snapshot.
 */
@DisplayName("the operational report door (P14-TSK-017)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class OperationalReportDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final Instant DATABASE_NOW = Instant.parse("2097-04-05T09:30:00Z");
    private static final UUID APPROVED = IDS.next();

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore store = new InMemoryCloseRunStore(periods);
    private final AccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private final CloseRuns closeRuns = new CloseRuns(store, periods, breaks, CloseRuns.Leases.STANDARD, (uow, record) -> { }, IDS, CLOCK);
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<ValuationRateSetStore.SetRow> sets = new ArrayList<>();
    private boolean auditFails;

    /** The set store's reads, scripted: anything else - a write above all - fails the test. */
    private final ValuationRateSetStore rateSets = (ValuationRateSetStore) Proxy.newProxyInstance(
            ValuationRateSetStore.class.getClassLoader(), new Class<?>[] {ValuationRateSetStore.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "ofPeriod" -> sets;
                case "approvedFor" -> sets.stream().filter(set -> set.status() == ValuationRateSetStatus.APPROVED).findFirst();
                default -> throw new AssertionError("close status only reads: " + method.getName());
            });

    private final ReportSnapshots snapshots = new ReportSnapshots() {
        @Override
        public <R> R read(Function<Connection, R> work) {
            return work.apply(databaseClock());
        }
    };
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(databaseClock());
        }
    };
    private final OperationalReports reports = new OperationalReports(ReportDefinitions.of(
            new CloseStatusDefinition(new AccountingCloseStatusReads(periods, closeRuns, breaks, rateSets),
                    new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS)),
            TestReportDefinition.echo()), periods,
            new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, Duration.ofMinutes(4), () -> 0L),
            (uow, record) -> {
                if (auditFails) {
                    throw new IllegalStateException("the audit table is away");
                }
                audited.add(record);
            }, IDS, CLOCK);
    private final OperationalReportDesk desk = new OperationalReportDesk(reports, snapshots, transactions, AccountingFlowScope.NONE);
    private AccountingPeriodId march;

    @BeforeEach
    void marchIsLocked() {
        march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(databaseClock(), new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        periods.markLocked(databaseClock(), march, 1, ACCOUNTANT, Optional.empty());
    }

    @Test
    @DisplayName("CLOSE_STATUS: labelled OPERATIONAL, as of the database's instant, its content what the hash hashes - recorded before it is"
            + " served; a lock with no run reads as such, and the approved set is named for the accountant (M-4)")
    void theReadIsLabelledAndRecorded() {
        sets.add(set(ValuationRateSetStatus.REJECTED, IDS.next()));
        sets.add(set(ValuationRateSetStatus.APPROVED, APPROVED));

        OperationalReportDesk.OperationalReportView view = as(ACCOUNTANT, () -> desk.read(march.value().toString(),
                CloseStatusDefinition.CODE));

        assertThat(view.definition()).isEqualTo("CLOSE_STATUS");
        assertThat(view.definitionVersion()).isEqualTo(1);
        assertThat(view.basis()).isEqualTo("OPERATIONAL");
        assertThat(view.period()).isEqualTo("2097-03");
        assertThat(view.asOf()).isEqualTo(DATABASE_NOW.toString());
        assertThat(view.contentSha256()).isEqualTo(com.finapp.accounting.reporting.CanonicalJson.sha256(view.content()));
        assertThat(view.content()).contains("\"basis\":\"OPERATIONAL\"")
                .contains("\"approvedValuationRateSet\":{")
                .contains("\"id\":\"" + APPROVED + "\"")
                .contains("\"APPROVED\":\"1\"")
                .contains("\"REJECTED\":\"1\"")
                .doesNotContain(ACCOUNTANT.id())
                .doesNotContain("reason");
        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.OperationalReportRead");
            assertThat(record.targetId()).isEqualTo(march.value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("definition=CLOSE_STATUS", "period=2097-03")
                    .doesNotContain("approvedValuationRateSet");
        });
    }

    @Test
    @DisplayName("the real run: once the leg has born it, the checks are in the answer; a second read is a new answer, and nothing is stored")
    void aMovingAnswer() {
        OperationalReportDesk.OperationalReportView before = as(ACCOUNTANT, () -> desk.read(march.value().toString(),
                CloseStatusDefinition.CODE));
        assertThat(before.content()).contains("\"run\":null");
        closeRuns.startMissing(databaseClock(), Actor.SYSTEM);
        OperationalReportDesk.OperationalReportView after = as(ACCOUNTANT, () -> desk.read(march.value().toString(),
                CloseStatusDefinition.CODE));
        assertThat(after.content()).contains("\"check\":\"GL_EQUALS_LEDGER\"").contains("\"runState\":\"RUNNING\"");
        assertThat(after.contentSha256()).isNotEqualTo(before.contentSha256());
        assertThat(audited).hasSize(2);
    }

    @Test
    @DisplayName("a failed record fails the read: nothing is served unrecorded")
    void aFailedRecordFailsTheRead() {
        auditFails = true;
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.read(march.value().toString(), CloseStatusDefinition.CODE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the refusals on the contract, nothing recorded: a run's definition 409, an unknown one 422, an unknown or malformed period 404")
    void theRefusals() {
        refused(() -> as(ACCOUNTANT, () -> desk.read(march.value().toString(), TestReportDefinition.CODE)),
                AccountingErrorCode.REPORT_DOOR_MISMATCH, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.read(march.value().toString(), "NO_SUCH_REPORT")),
                AccountingErrorCode.REPORT_DEFINITION_UNKNOWN, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.read(UUID.randomUUID().toString(), CloseStatusDefinition.CODE)),
                AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.read("not-a-uuid", CloseStatusDefinition.CODE)), AccountingErrorCode.NOT_FOUND, 404);
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("a time budget spent is 503 and serves nothing, recording nothing")
    void aTimedOutReadIsNoAnswer() {
        long[] ticks = new long[1];
        OperationalReports spent = new OperationalReports(ReportDefinitions.of(new CloseStatusDefinition(
                (snapshot, id) -> {
                    try (var read = snapshot.prepareStatement("SELECT 1"); var rows = read.executeQuery()) {
                        throw new AssertionError("the budget was spent before this statement");
                    } catch (java.sql.SQLException impossible) {
                        throw new AssertionError(impossible);
                    }
                }, new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS))), periods,
                new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, Duration.ofMillis(1), () -> ticks[0]++ * 2_000_000L),
                (uow, record) -> audited.add(record), IDS, CLOCK);
        OperationalReportDesk spending = new OperationalReportDesk(spent, snapshots, transactions, AccountingFlowScope.NONE);
        refused(() -> as(ACCOUNTANT, () -> spending.read(march.value().toString(), CloseStatusDefinition.CODE)),
                AccountingErrorCode.READ_TIMED_OUT, 503);
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("the route: ACCOUNTING_READ, a GET with no key - a read changes nothing, and each answer is its own instant's")
    void theRoute() {
        Map<String, PermissionName> expected = Map.of("readOperationalReport", PermissionName.ACCOUNTING_READ);
        int doors = 0;
        for (Method method : OperationalReportController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                doors++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).isFalse();
            }
        }
        assertThat(doors).isEqualTo(expected.size());
    }

    // ------------------------------------------------------------------ plumbing

    /** A stand-in on which only the database's instant is ever read: the stores in memory never touch it. */
    private Connection databaseClock() {
        return new ScriptedStatements(sql -> List.of(ScriptedStatements.row("1", Timestamp.from(DATABASE_NOW)))).connection();
    }

    private static ValuationRateSetStore.SetRow set(ValuationRateSetStatus status, UUID id) {
        return new ValuationRateSetStore.SetRow(ValuationRateSetId.of(id), AccountingPeriodId.next(IDS), MARCH,
                Instant.parse("2097-04-01T00:00:00Z"), 96, 3, status, "accountant-2", Instant.parse("2097-04-05T08:00:00Z"),
                Optional.of("controller-1"), Optional.of(Instant.parse("2097-04-05T08:30:00Z")));
    }

    private static void refused(Runnable act, AccountingErrorCode code, int status) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.errorCode().status()).isEqualTo(status);
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }
}
