package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertBornOnceCensus;
import com.finapp.risk.AssessmentCensus;
import com.finapp.risk.CaseOneOpenCensus;
import com.finapp.risk.DailyReplay;
import com.finapp.risk.FilingCensus;
import com.finapp.risk.JudgedCommands;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoringCensus;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.RiskAuditAction;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskOperationsReports;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskProofReads;
import com.finapp.risk.VelocityEvidence;
import com.finapp.risk.VelocityProof;
import com.finapp.risk.VelocityWindowStore;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proofs' doors, hermetic (`P13-TSK-023`; {@code DESIGN-023} D7-D9): a report's date judged first ({@code 422
 * api.ValidationFailed}, nothing read, nothing recorded), the report read on one snapshot, then {@code risk.ReportRead}
 * committed in a transaction of its own before the answer; the day's replay reasoned first ({@code 422
 * risk.ReasonRequired}), an unplaced reader {@code 403 risk.SelfDealingRefused} recorded {@code FAILED}, the day
 * recorded {@code risk.Replayed} before the answer; the evidence door's unknown kind and malformed ids the uniform
 * {@code 404}; every route {@code RISK_INVESTIGATE}. Over HTTP the doors are {@code RiskReportsApiTest}'s (owed, C5).
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the proofs' doors: the date and the reason judged first, one snapshot, recorded before the answer"
        + " (P13-TSK-023)")
class RiskProofDesksTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final List<String> units = new ArrayList<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final Map<String, UUID> partyOfActor = new HashMap<>();
    private final RiskProofReads reads = empty(RiskProofReads.class);
    private final RiskTransactions transactions = new RiskTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            units.add("transaction");
            return work.apply(snapshot());
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            units.add("snapshot");
            return work.apply(snapshot());
        }
    };
    private final RiskOperationsReports reports = new RiskOperationsReports(reads,
            new AssessmentCensus(reads, empty(JudgedCommands.class)),
            new MonitoringCensus(empty(MonitoredLedger.class), empty(MonitoringWatermarkStore.class), reads),
            new AlertBornOnceCensus(reads), new CaseOneOpenCensus(reads), new FilingCensus(reads),
            new VelocityProof(empty(VelocityWindowStore.class), evidence(), evidence()),
            (unitOfWork, record) -> audit.add(record), IDS, CLOCK);
    private final RiskOperationsReportDesk desk = new RiskOperationsReportDesk(reports, transactions,
            RiskFlowScope.NONE);
    private final DailyReplay daily = new DailyReplay(reads, new RiskPolicies(empty(RiskPolicyStore.class)),
            (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())),
            (unitOfWork, record) -> audit.add(record), IDS, CLOCK);
    private final Actor analyst = new Actor("analyst-1", ActorType.CUSTOMER);

    @Test
    @DisplayName("a report: a date that is no calendar date is 422 api.ValidationFailed with nothing read or recorded;"
            + " a day is read on one snapshot, then risk.ReportRead committed in a transaction of its own")
    void aReportIsJudgedReadAndRecorded() {
        for (String bad : new String[] {null, "", "2026-02-30", "26-10-23", "2026-10-23T00:00:00Z"}) {
            ApiException refused = catchThrowableOfType(ApiException.class, () -> as(analyst, () -> desk.decisions(bad)));
            assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
        }
        assertThat(units).isEmpty();
        assertThat(audit).isEmpty();

        RiskOperationsReportDesk.RiskDecisionsReportView view = as(analyst, () -> desk.decisions("2026-10-23"));
        assertThat(view.known()).isTrue();
        assertThat(view.census().verdict()).isEqualTo("VACUOUS");
        assertThat(view.census().operations()).hasSize(5);
        assertThat(units).containsExactly("snapshot", "transaction");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(RiskAuditAction.REPORT_READ);
            assertThat(record.actor()).isEqualTo(analyst);
            assertThat(record.changeSummary().orElseThrow()).startsWith("report=decisions, date=2026-10-23");
        });

        units.clear();
        assertThat(as(analyst, () -> desk.velocity("2026-10-24")).known()).as("tomorrow").isFalse();
        assertThat(as(analyst, () -> desk.alerts("2026-10-23")).census().verdict()).isEqualTo("VACUOUS");
        assertThat(as(analyst, () -> desk.cases("2026-10-23")).filings().verdict()).isEqualTo("VACUOUS");
        assertThat(units).containsExactly("snapshot", "transaction", "snapshot", "transaction", "snapshot",
                "transaction");
    }

    @Test
    @DisplayName("the day's replay: a blank reason 422 risk.ReasonRequired before anything is read; an unplaced reader 403"
            + " risk.SelfDealingRefused, recorded FAILED; a placed reader's day recorded risk.Replayed, then answered")
    void theDayReplay() {
        RiskAssessmentDesk assessments = new RiskAssessmentDesk(new com.finapp.risk.RiskAssessments(
                empty(com.finapp.risk.RiskAssessmentStore.class), new RiskPolicies(empty(RiskPolicyStore.class)),
                (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())),
                (unitOfWork, record) -> audit.add(record), IDS, CLOCK), daily, transactions, RiskFlowScope.NONE);

        ApiException blank = catchThrowableOfType(ApiException.class,
                () -> as(analyst, () -> assessments.replayDay("2026-10-23", " ")));
        assertThat(blank.errorCode()).isEqualTo(RiskErrorCode.REASON_REQUIRED);
        ApiException date = catchThrowableOfType(ApiException.class,
                () -> as(analyst, () -> assessments.replayDay("yesterday", "the audit")));
        assertThat(date.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
        assertThat(units).isEmpty();

        ApiException unplaced = catchThrowableOfType(ApiException.class,
                () -> as(analyst, () -> assessments.replayDay("2026-10-23", "the audit")));
        assertThat(unplaced.errorCode()).isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(RiskAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetType()).isEqualTo(DailyReplay.TARGET_TYPE);
        });

        audit.clear();
        units.clear();
        partyOfActor.put(analyst.id(), UUID.randomUUID());
        RiskAssessmentDesk.RiskDayReplayView view = as(analyst, () -> assessments.replayDay("2026-10-23", "the audit"));
        assertThat(view.known()).isTrue();
        assertThat(view.verdict()).isEqualTo("IDENTICAL");
        assertThat(view.replayed()).isZero();
        assertThat(view.firstDiverged()).isNull();
        assertThat(units).containsExactly("snapshot", "transaction");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(RiskAuditAction.REPLAYED);
            assertThat(record.reason()).contains("the audit");
        });
    }

    @Test
    @DisplayName("the evidence door: a kind not served here and a malformed id are the uniform 404 before anything is"
            + " read")
    void theEvidenceDoorsRefusals() {
        assertThat(RiskEvidenceDesk.kind("signals")).isEqualTo(com.finapp.risk.CaseWorklist.EvidenceKind.SIGNAL);
        assertThat(RiskEvidenceDesk.kind("journal-entries"))
                .isEqualTo(com.finapp.risk.CaseWorklist.EvidenceKind.JOURNAL_ENTRY);
        for (String kind : new String[] {"assessments", "signal", "SIGNALS", "", null}) {
            ApiException refused = catchThrowableOfType(ApiException.class, () -> RiskEvidenceDesk.kind(kind));
            assertThat(refused.errorCode()).as(String.valueOf(kind)).isEqualTo(RiskErrorCode.NOT_FOUND);
        }
        assertThat(catchThrowableOfType(ApiException.class, () -> RiskEvidenceDesk.caseId("not-a-case")).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(catchThrowableOfType(ApiException.class, () -> RiskEvidenceDesk.uuid("not-an-entry")).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(units).isEmpty();
    }

    @Test
    @DisplayName("every proof route holds RISK_INVESTIGATE - the analyst's, never another desk's")
    void everyRouteIsTheAnalysts() throws NoSuchMethodException {
        for (Method method : RiskOperationsReportController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)) {
                assertThat(method.getAnnotation(RequiresPermission.class).value())
                        .isEqualTo(PermissionName.RISK_INVESTIGATE);
            }
        }
        assertThat(RiskAssessmentController.class.getMethod("replayRiskAssessmentsOfDay", String.class,
                RiskAssessmentController.RiskReplayRequest.class).getAnnotation(RequiresPermission.class).value())
                .isEqualTo(PermissionName.RISK_INVESTIGATE);
        assertThat(RiskCaseController.class.getMethod("readRiskCaseEvidence", String.class, String.class, String.class)
                .getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.RISK_INVESTIGATE);
    }

    // ------------------------------------------------------------------

    private static <T> T as(Actor actor, java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

    /** A stand-in for the proofs' snapshot: read-only, in a transaction, REPEATABLE READ; it answers nothing else. */
    private static Connection snapshot() {
        return (Connection) Proxy.newProxyInstance(RiskProofDesksTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isReadOnly" -> true;
                    case "getAutoCommit" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_REPEATABLE_READ;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A port answering every read empty - the clock at the fixed instant, an empty list, set or optional. */
    @SuppressWarnings("unchecked")
    private static <T> T empty(Class<T> port) {
        return (T) Proxy.newProxyInstance(RiskProofDesksTest.class.getClassLoader(), new Class<?>[] {port},
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == Instant.class) {
                        return CLOCK.instant();
                    }
                    if (type == List.class) {
                        return List.of();
                    }
                    if (type == Set.class) {
                        return Set.of();
                    }
                    if (type == Optional.class) {
                        return Optional.empty();
                    }
                    if (type == RiskProofReads.FilingRow.class) {
                        return new RiskProofReads.FilingRow(0, 0, 0);
                    }
                    if (method.getName().equals("toString")) {
                        return port.getSimpleName();
                    }
                    throw new UnsupportedOperationException(port.getSimpleName() + "." + method.getName());
                });
    }

    private static VelocityEvidence evidence() {
        return new VelocityEvidence() {
            @Override
            public Set<com.finapp.risk.VelocityDimension> provable() {
                return Set.of(com.finapp.risk.VelocityDimension.SOURCE_ACCOUNT);
            }

            @Override
            public Record read(Connection snapshot, Instant from) {
                return new Record(List.of(), CLOCK.instant());
            }
        };
    }
}
