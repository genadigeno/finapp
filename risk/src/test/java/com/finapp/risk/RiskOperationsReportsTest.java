package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four operations reports and the meters' one reading (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 15;
 * {@code DESIGN-023} D8, D10): each read on one snapshot with the census it carries; a date the platform has not had
 * answers {@code known=false} and reads nothing; the reading takes every census over today and yesterday and the
 * velocity proof made whole; {@code risk.ReportRead}'s summary carries the report, the date, the rows and the verdict -
 * never an amount, a party or a subject. Over HTTP the reports are {@code RiskReportsApiTest}'s (written and owed, C5).
 */
@DisplayName("the risk operations reports: one snapshot each with its census, unknown days answered empty, recorded"
        + " risk.ReportRead (P13-TSK-023)")
class RiskOperationsReportsTest {

    private static final RiskDay DAY = RiskDay.of(LocalDate.of(2026, 10, 23));
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final FakeRiskProofReads reads = new FakeRiskProofReads();
    private final AssessmentCensusTest.FakeOwners owners = new AssessmentCensusTest.FakeOwners();
    private final FakeVelocityWindowStore windows = new FakeVelocityWindowStore(Instant.parse("2026-10-23T12:00:00Z"));
    private final List<AuditRecord> audit = new ArrayList<>();
    private final RiskOperationsReports reports = new RiskOperationsReports(reads, new AssessmentCensus(reads, owners),
            new MonitoringCensus(emptyLedger(), noWatermark(), reads), new AlertBornOnceCensus(reads),
            new CaseOneOpenCensus(reads), new FilingCensus(reads),
            new VelocityProof(windows, evidence(EnumSet.allOf(VelocityDimension.class)),
                    evidence(EnumSet.of(VelocityDimension.SOURCE_ACCOUNT, VelocityDimension.DESTINATION))),
            (unitOfWork, record) -> audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);

    @Test
    @DisplayName("the decisions: the day's groups, reasons and versions with the assessment census; a sum in exact minor"
            + " units by currency; recorded with counts and the verdict only")
    void theDecisions() {
        reads.decisions.put(DAY, List.of(new RiskProofReads.DecisionRow(RiskOperation.TRANSFER, RiskDecision.ALLOW,
                Optional.empty(), Optional.empty(), VelocityOutcome.COUNTED, EUR, 2, 3, 360_00)));
        reads.reasons.put(DAY, List.of(new RiskProofReads.ReasonRow(RiskOperation.TRANSFER, ReasonCode.NEW_PAYEE, 1)));
        reads.versions.put(DAY, List.of(new RiskProofReads.VersionRow(RiskOperation.TRANSFER, OptionalInt.of(1), 3)));

        RiskOperationsReports.DecisionsReport report = reports.decisions(StandInConnections.snapshot(), DAY);
        assertThat(report.known()).isTrue();
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.judgedMinor()).isEqualTo(360_00L);
            assertThat(row.currency()).isEqualTo(EUR);
        });
        assertThat(report.census()).hasValueSatisfying(census ->
                assertThat(census.verdict()).isEqualTo(ProofVerdict.VACUOUS));

        reports.record(EvaluationWorld.UOW, report.served(), analyst(), CorrelationId.of("flow-1"));
        AuditRecord recorded = audit.get(0);
        assertThat(recorded.operation()).isEqualTo(RiskAuditAction.REPORT_READ);
        assertThat(recorded.operation().requiresReason()).isFalse();
        assertThat(recorded.targetType()).isEqualTo(RiskOperationsReports.TARGET_TYPE);
        assertThat(recorded.targetId()).isEqualTo("decisions");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(recorded.changeSummary().orElseThrow())
                .isEqualTo("report=decisions, date=2026-10-23, known=true, rows=1, verdict=VACUOUS");
    }

    @Test
    @DisplayName("the alerts carry the born-once census, the cases the one-open and filing censuses at the snapshot, the"
            + " velocity the proof made whole and the day's monitoring census")
    void eachReportCarriesItsCensus() {
        reads.bornOnce.put(DAY, List.of(new RiskProofReads.BornOnceRow(AlertKind.AML, WindowKind.D7, 2, 1, 2)));
        assertThat(reports.alerts(StandInConnections.snapshot(), DAY).served().verdict())
                .isEqualTo(ProofVerdict.DIVERGED);
        reads.open.add(new RiskProofReads.OpenRow(CaseKind.FRAUD, 2, 2, 0));
        reads.filing = new RiskProofReads.FilingRow(1, 1, 0);
        RiskOperationsReports.CasesReport cases = reports.cases(StandInConnections.snapshot(), DAY);
        assertThat(cases.open()).hasValueSatisfying(open -> assertThat(open.verdict()).isEqualTo(ProofVerdict.HELD));
        assertThat(cases.filings()).hasValueSatisfying(filing -> assertThat(filing.due()).isEqualTo(1));
        assertThat(cases.served().verdict()).isEqualTo(ProofVerdict.HELD);
        RiskOperationsReports.VelocityReport velocity = reports.velocity(StandInConnections.snapshot(), DAY);
        assertThat(velocity.proof()).hasValueSatisfying(whole -> assertThat(whole.journal()).isPresent());
        assertThat(velocity.monitoring()).isPresent();
        assertThat(windows.upserted).as("no report ever writes").isEmpty();
    }

    @Test
    @DisplayName("a date after the database's today, or before the platform's first, is known=false: nothing read beyond"
            + " the clock, still answerable and recordable")
    void anUnknownDayIsEmpty() {
        RiskDay tomorrow = RiskDay.of(LocalDate.of(2026, 10, 24));
        reads.decisions.put(tomorrow, List.of(new RiskProofReads.DecisionRow(RiskOperation.TRANSFER, RiskDecision.ALLOW,
                Optional.empty(), Optional.empty(), VelocityOutcome.COUNTED, EUR, 2, 1, 1_00)));
        RiskOperationsReports.DecisionsReport report = reports.decisions(StandInConnections.snapshot(), tomorrow);
        assertThat(report.known()).isFalse();
        assertThat(report.rows()).isEmpty();
        assertThat(reads.calls).containsExactly("now");
        assertThat(reports.velocity(StandInConnections.snapshot(), RiskDay.of(LocalDate.of(2025, 12, 31))).known())
                .isFalse();
        assertThatThrownBy(() -> reports.alerts(StandInConnections.declaring(false, false,
                Connection.TRANSACTION_REPEATABLE_READ), DAY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the meters' reading: the day-based censuses over today and yesterday, the worse of the two; the snapshot"
            + " ones once; the velocity proof made whole")
    void theReading() {
        reads.bornOnce.put(DAY.previous(), List.of(new RiskProofReads.BornOnceRow(AlertKind.FRAUD, WindowKind.D1, 2, 1,
                2)));
        RiskOperationsReports.Reading reading = reports.reading(StandInConnections.snapshot());
        assertThat(reading.censuses()).containsOnlyKeys(CensusKind.values());
        assertThat(reading.censuses().get(CensusKind.ALERT_BORN_ONCE)).as("yesterday's second alert")
                .isEqualTo(ProofVerdict.DIVERGED);
        assertThat(reading.censuses().get(CensusKind.ASSESSMENT)).isEqualTo(ProofVerdict.VACUOUS);
        assertThat(reading.velocity()).isEqualTo(VelocityProof.Verdict.VACUOUS);
        assertThat(reads.calls).contains("bornOnce 2026-10-22", "bornOnce 2026-10-23");
    }

    // ------------------------------------------------------------------

    private static Actor analyst() {
        return new Actor("analyst-1", ActorType.CUSTOMER);
    }

    private static VelocityEvidence evidence(Set<VelocityDimension> provable) {
        return new VelocityEvidence() {
            @Override
            public Set<VelocityDimension> provable() {
                return provable;
            }

            @Override
            public Record read(Connection snapshot, Instant from) {
                return new Record(List.of(), Instant.parse("2026-10-23T12:00:00Z"));
            }
        };
    }

    private static MonitoredLedger emptyLedger() {
        return new MonitoredLedger() {
            @Override
            public List<MonitoredEntry> entriesAfter(Connection unitOfWork, UUID afterEntryId, Duration settleHorizon,
                    int limit) {
                return List.of();
            }

            @Override
            public long entriesBeyond(Connection unitOfWork, UUID afterEntryId, long cap) {
                return 0;
            }

            @Override
            public List<UUID> entryIds(Connection snapshot, UUID fromInclusive, UUID toExclusive) {
                return List.of();
            }
        };
    }

    private static MonitoringWatermarkStore noWatermark() {
        return (MonitoringWatermarkStore) Proxy.newProxyInstance(RiskOperationsReportsTest.class.getClassLoader(),
                new Class<?>[] {MonitoringWatermarkStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("read")) {
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
