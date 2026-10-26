package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four operations reports and the meters' one reading (`P13-TSK-023`; {@code PHASE_13_PLAN.md} sections 9, 15;
 * {@code DESIGN-023} D8, D10): each read on one snapshot with the census it carries, by a reader the platform places in a
 * party (fail closed); a date the platform has not had answers {@code known=false} and reads nothing; a velocity
 * divergence located by its window's subject unless the subject is the reader's own or unplaceable; the reading takes
 * every census over today and yesterday and the velocity proof made whole; {@code risk.ReportRead}'s summary carries the
 * report, the date, the rows and the verdict - never an amount, a party or a subject. Over HTTP the reports are
 * {@code RiskReportsApiTest}'s (written and owed, C5).
 */
@DisplayName("the risk operations reports: one snapshot each with its census, a placed reader, unknown days answered"
        + " empty, recorded risk.ReportRead (P13-TSK-023)")
class RiskOperationsReportsTest {

    private static final RiskDay DAY = RiskDay.of(LocalDate.of(2026, 10, 23));
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID OWN = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");

    private final FakeRiskProofReads reads = new FakeRiskProofReads();
    private final AssessmentCensusTest.FakeOwners owners = new AssessmentCensusTest.FakeOwners();
    private final FakeVelocityWindowStore windows = new FakeVelocityWindowStore(Instant.parse("2026-10-23T12:00:00Z"));
    private final List<AuditRecord> audit = new ArrayList<>();
    private final Map<String, UUID> partyOfActor = new HashMap<>(Map.of("analyst-1", OWN));
    private final Map<UUID, UUID> partyOfAccount = new HashMap<>();
    private final RiskOperationsReports reports = new RiskOperationsReports(reads, new AssessmentCensus(reads, owners),
            new MonitoringCensus(emptyLedger(), noWatermark(), reads), new AlertBornOnceCensus(reads),
            new CaseOneOpenCensus(reads), new FilingCensus(reads),
            new VelocityProof(windows, evidence(EnumSet.allOf(VelocityDimension.class)),
                    evidence(EnumSet.of(VelocityDimension.SOURCE_ACCOUNT, VelocityDimension.DESTINATION))),
            (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())), RiskSubjectParties.NONE,
            accounts(), (unitOfWork, record) -> audit.add(record), EvaluationWorld.IDS, EvaluationWorld.CLOCK);

    @Test
    @DisplayName("the decisions: the day's groups, reasons and versions with the assessment census; a sum in exact minor"
            + " units by currency; recorded with counts and the verdict only")
    void theDecisions() {
        reads.decisions.put(DAY, List.of(new RiskProofReads.DecisionRow(RiskOperation.TRANSFER, RiskDecision.ALLOW,
                Optional.empty(), Optional.empty(), VelocityOutcome.COUNTED, EUR, 2, 3, 360_00)));
        reads.reasons.put(DAY, List.of(new RiskProofReads.ReasonRow(RiskOperation.TRANSFER, ReasonCode.NEW_PAYEE, 1)));
        reads.versions.put(DAY, List.of(new RiskProofReads.VersionRow(RiskOperation.TRANSFER, OptionalInt.of(1), 3)));

        RiskOperationsReports.DecisionsReport report = reports.decisions(StandInConnections.snapshot(), DAY, analyst());
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
        assertThat(reports.alerts(StandInConnections.snapshot(), DAY, analyst()).served().verdict())
                .isEqualTo(ProofVerdict.DIVERGED);
        reads.open.add(new RiskProofReads.OpenRow(CaseKind.FRAUD, 2, 2, 0));
        reads.filing = new RiskProofReads.FilingRow(1, 1, 0);
        RiskOperationsReports.CasesReport cases = reports.cases(StandInConnections.snapshot(), DAY, analyst());
        assertThat(cases.open()).hasValueSatisfying(open -> assertThat(open.verdict()).isEqualTo(ProofVerdict.HELD));
        assertThat(cases.filings()).hasValueSatisfying(filing -> assertThat(filing.due()).isEqualTo(1));
        assertThat(cases.served().verdict()).isEqualTo(ProofVerdict.HELD);
        RiskOperationsReports.VelocityReport velocity = reports.velocity(StandInConnections.snapshot(), DAY, analyst());
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
        RiskOperationsReports.DecisionsReport report = reports.decisions(StandInConnections.snapshot(), tomorrow,
                analyst());
        assertThat(report.known()).isFalse();
        assertThat(report.rows()).isEmpty();
        assertThat(reads.calls).containsExactly("now");
        assertThat(reports.velocity(StandInConnections.snapshot(), RiskDay.of(LocalDate.of(2025, 12, 31)), analyst())
                .known()).isFalse();
        assertThatThrownBy(() -> reports.alerts(StandInConnections.declaring(false, false,
                Connection.TRANSACTION_REPEATABLE_READ), DAY, analyst())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the reader is placed - an unplaceable reader refused, fail closed, recorded FAILED against the report;"
            + " an orphan of the reader's own party withheld and counted, another's named")
    void theReaderIsPlaced() {
        Actor unplaced = new Actor("analyst-2", ActorType.CUSTOMER);
        RiskAssessments.SelfDealingRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                RiskAssessments.SelfDealingRefused.class,
                () -> reports.cases(StandInConnections.snapshot(), DAY, unplaced));
        assertThat(refused.refusal()).isEqualTo(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE);
        assertThat(refused.act()).isEqualTo("report-read");
        assertThat(reads.calls).as("nothing of the day read for an unplaced reader").containsExactly("now");
        reports.recordRefusal(EvaluationWorld.UOW, RiskOperationsReports.Report.CASES, refused, unplaced,
                CorrelationId.of("flow-2"));
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(RiskAuditAction.SELF_DEALING_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetType()).isEqualTo(RiskOperationsReports.TARGET_TYPE);
            assertThat(record.targetId()).isEqualTo("cases");
            assertThat(record.changeSummary().orElseThrow()).isEqualTo("act=report-read, cause=UNPLACEABLE refused");
        });
        assertThatThrownBy(() -> reports.velocity(StandInConnections.snapshot(), DAY,
                new Actor("risk-platform", ActorType.SYSTEM))).as("a report is read by a person")
                .isInstanceOf(IllegalArgumentException.class);

        CommandRef own = CommandRef.of(FakeRiskProofReads.mintedAt(Instant.parse("2026-10-23T09:00:00Z")));
        CommandRef other = CommandRef.of(FakeRiskProofReads.mintedAt(Instant.parse("2026-10-23T09:01:00Z")));
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(
                Instant.parse("2026-10-23T09:00:00Z")), RiskOperation.WITHDRAWAL, own, true, OWN));
        reads.assessed.add(new FakeRiskProofReads.Planted(FakeRiskProofReads.mintedAt(
                Instant.parse("2026-10-23T09:01:00Z")), RiskOperation.WITHDRAWAL, other, true, UUID.randomUUID()));
        RiskOperationsReports.DecisionsReport report = reports.decisions(StandInConnections.snapshot(), DAY, analyst());
        AssessmentCensus.Operation withdrawals = report.census().orElseThrow().operations().stream()
                .filter(operation -> operation.operation() == RiskOperation.WITHDRAWAL).findFirst().orElseThrow();
        assertThat(withdrawals.orphanedCount()).as("the count stays whole").isEqualTo(2);
        assertThat(withdrawals.orphaned()).extracting(RiskProofReads.Assessed::commandRef).containsExactly(other);
        assertThat(report.withheld()).isEqualTo(1);
        assertThat(report.served().verdict()).as("the verdict is the whole census's").isEqualTo(ProofVerdict.DIVERGED);
    }

    @Test
    @DisplayName("a velocity divergence is located by its window's subject - unless the subject is the reader's own"
            + " party's or cannot be placed: withheld and counted; the proof carries its own as-of, the snapshot's")
    void aDivergenceIsLocated() {
        UUID party = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
        UUID account = UUID.fromString("018f0000-0000-7000-8000-0000000000cc");
        VelocityWindows counting = new VelocityWindows(windows);
        windows.now = Instant.parse("2026-10-23T09:00:00Z");
        // Counted with no evidence of record holding either movement: every one of their live windows diverges.
        counting.increment(StandInConnections.unused(), counting.cut(StandInConnections.unused(),
                VelocityMovement.of(Money.ofMinorUnits(10_00, EUR)).party(party).sourceAccount(account).build()),
                RiskDecision.ALLOW, false);
        counting.increment(StandInConnections.unused(), counting.cut(StandInConnections.unused(),
                VelocityMovement.of(Money.ofMinorUnits(10_00, EUR)).party(OWN).build()), RiskDecision.ALLOW, false);
        windows.now = Instant.parse("2026-10-23T12:00:00Z");

        RiskOperationsReports.VelocityReport report = reports.velocity(StandInConnections.snapshot(), DAY, analyst());
        VelocityProof.Whole whole = report.proof().orElseThrow();
        assertThat(whole.verdict()).isEqualTo(VelocityProof.Verdict.DIVERGED);
        assertThat(whole.record().asOf()).as("the windows live at the snapshot, whatever the date asked")
                .isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
        assertThat(report.record()).as("another party's windows located, by their subject").isNotEmpty()
                .allSatisfy(located -> assertThat(located.subjectRef()).isEqualTo(party.toString()));
        long ownWindows = whole.record().divergences().stream()
                .filter(divergence -> divergence.key().subjectRef().equals(OWN.toString())).count();
        long accountWindows = whole.record().divergences().stream()
                .filter(divergence -> divergence.key().subjectRef().equals(account.toString())).count();
        assertThat(ownWindows).isPositive();
        assertThat(accountWindows).isPositive();
        assertThat(report.journal()).as("the account's journal windows are unplaced too").isEmpty();
        assertThat(report.withheld()).as("the reader's own party's windows and the unplaced account's, on both links")
                .isEqualTo(ownWindows + accountWindows + whole.journal().orElseThrow().divergences().size());

        partyOfAccount.put(account, party);
        RiskOperationsReports.VelocityReport placed = reports.velocity(StandInConnections.snapshot(), DAY, analyst());
        assertThat(placed.record()).extracting(RiskOperationsReports.Located::subjectRef)
                .as("an account placed in another party is located").contains(account.toString());
        assertThat(placed.withheld()).isEqualTo(ownWindows);
        partyOfAccount.put(account, OWN);
        assertThat(reports.velocity(StandInConnections.snapshot(), DAY, analyst()).record())
                .extracting(RiskOperationsReports.Located::subjectRef).as("an account of the reader's own party is not")
                .doesNotContain(account.toString());
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

    private MonitoredParties accounts() {
        return new MonitoredParties() {
            @Override
            public Optional<UUID> partyOfAccount(Connection unitOfWork, UUID customerAccountId) {
                return Optional.ofNullable(partyOfAccount.get(customerAccountId));
            }

            @Override
            public Accounts accountsOf(Connection unitOfWork, UUID partyId) {
                return Accounts.NONE;
            }
        };
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
