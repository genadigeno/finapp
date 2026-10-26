package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The monitoring, born-once, one-open, filing and rescreening censuses over fakes (`P13-TSK-023`; {@code INV-RSK-09},
 * {@code INV-RSK-06}; ADR-0111 decision 6; F8; {@code DESIGN-023} D3, D5): monitored = journal by set, so a lost entry
 * and a phantom row cannot cancel out; the watermark bounds what is judged (pending behind it, ahead of a reprocessing);
 * a second alert of one key is found whatever day it was born, `-020`'s AML alerts per window among the rows; a second
 * open case of one subject and kind is found; a filing left undriven past one inquiry interval is overdue; a rescreening
 * permit unaccounted for under its list version is found (`P13-TSK-024`). Each census
 * reads one read-only snapshot and writes nothing. The database's half is {@code RiskProofsDatabaseTest}'s (owed, C5).
 */
@DisplayName("the monitoring, born-once, one-open and filing censuses: each re-proven by query, a difference named"
        + " (P13-TSK-023)")
class RiskCensusesTest {

    private static final RiskDay DAY = RiskDay.of(LocalDate.of(2026, 10, 23));
    private static final com.finapp.sharedkernel.id.IdGenerator IDS = new com.finapp.sharedkernel.id.IdGenerator(
            java.time.Clock.systemUTC(), new java.security.SecureRandom());

    private final FakeRiskProofReads reads = new FakeRiskProofReads();
    private final List<UUID> journal = new ArrayList<>();
    private Optional<MonitoringWatermark> watermark = Optional.empty();

    private final MonitoringCensus monitoring = new MonitoringCensus(ledger(), watermarks(), reads);

    @Test
    @DisplayName("monitored = journal by set: equal through the watermark holds; an entry the intake passed is missing;"
            + " a monitored row naming no entry is phantom - the two never cancel out in a count")
    void monitoredEqualsTheJournal() {
        UUID first = entry("2026-10-23T08:00:00Z", true);
        entry("2026-10-23T09:00:00Z", true);
        UUID lost = entry("2026-10-23T10:00:00Z", false);
        UUID last = entry("2026-10-23T11:00:00Z", true);
        watermarkAt(last);
        assertThat(monitoring.run(StandInConnections.snapshot(), DAY).verdict()).isEqualTo(ProofVerdict.DIVERGED);

        UUID phantom = FakeRiskProofReads.mintedAt(Instant.parse("2026-10-23T10:30:00Z"));
        reads.monitored.add(phantom);
        MonitoringCensus.Result result = monitoring.run(StandInConnections.snapshot(), DAY);
        assertThat(result.entries()).isEqualTo(4);
        assertThat(result.monitored()).as("the counts agree - a phantom and a loss cancel out").isEqualTo(4);
        assertThat(result.missing()).containsExactly(lost);
        assertThat(result.phantom()).containsExactly(phantom);
        assertThat(result.verdict()).isEqualTo(ProofVerdict.DIVERGED);
        assertThat(result.watermarkThrough()).isEqualTo(Instant.ofEpochMilli(MonitoringWatermark.sequenceOf(last)));
        assertThat(first).isNotNull();

        reads.monitored.remove(phantom);
        reads.monitored.add(lost);
        assertThat(monitoring.run(StandInConnections.snapshot(), DAY).verdict()).isEqualTo(ProofVerdict.HELD);
    }

    @Test
    @DisplayName("the watermark bounds the census: an entry past it is pending, not missing; a row past it after a"
            + " reprocessing is ahead, not a difference; no watermark yet - everything pending")
    void theWatermarkBoundsWhatIsJudged() {
        UUID through = entry("2026-10-23T08:00:00Z", true);
        UUID behind = entry("2026-10-23T09:00:00Z", true);
        entry("2026-10-23T10:00:00Z", false);
        watermarkAt(through);

        MonitoringCensus.Result result = monitoring.run(StandInConnections.snapshot(), DAY);
        assertThat(result.entries()).isEqualTo(1);
        assertThat(result.pending()).isEqualTo(2);
        assertThat(result.ahead()).as("born before a reprocessing moved the watermark back").isEqualTo(1);
        assertThat(result.missingCount()).isZero();
        assertThat(result.verdict()).isEqualTo(ProofVerdict.HELD);
        assertThat(behind).isNotNull();

        watermark = Optional.empty();
        MonitoringCensus.Result unwalked = monitoring.run(StandInConnections.snapshot(), DAY);
        assertThat(unwalked.pending()).isEqualTo(3);
        assertThat(unwalked.entries()).isZero();
    }

    @Test
    @DisplayName("born once: a second alert of one key is found whatever day it was born - AML per window among the rows;"
            + " equal keys and rows hold; nothing raised is vacuous")
    void alertsAreBornOnce() {
        AlertBornOnceCensus census = new AlertBornOnceCensus(reads);
        assertThat(census.run(StandInConnections.snapshot(), DAY).verdict()).isEqualTo(ProofVerdict.VACUOUS);
        reads.bornOnce.put(DAY, List.of(
                new RiskProofReads.BornOnceRow(AlertKind.AML, WindowKind.D7, 3, 3, 3),
                new RiskProofReads.BornOnceRow(AlertKind.AML, WindowKind.D30, 2, 2, 3),
                new RiskProofReads.BornOnceRow(AlertKind.FRAUD, WindowKind.D1, 4, 4, 4)));

        AlertBornOnceCensus.Result result = census.run(StandInConnections.snapshot(), DAY);
        assertThat(result.raised()).isEqualTo(9);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.rows()).filteredOn(row -> row.kind() == AlertKind.AML)
                .extracting(AlertBornOnceCensus.Row::window, AlertBornOnceCensus.Row::duplicates)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(WindowKind.D7, 0L),
                        org.assertj.core.groups.Tuple.tuple(WindowKind.D30, 1L));
        assertThat(result.verdict()).isEqualTo(ProofVerdict.DIVERGED);
    }

    @Test
    @DisplayName("every kind is judged: P13-TSK-025's fact alert (OBLIGATION_DEFAULTED - its own code, no policy"
            + " version, a CREDIT_AGREEMENT's D1 window) is born-once-counted and the COLLECTIONS case one-open-counted"
            + " like any other - the statements filter on no kind, no policy and no subject kind")
    void theFactAlertAndTheCollectionsCaseAreCounted() {
        for (String statement : List.of(JdbcRiskProofReads.BORN_ONCE, JdbcRiskProofReads.OPEN_CASES,
                JdbcRiskProofReads.ALERTS, JdbcRiskProofReads.ALERT_RULES)) {
            assertThat(statement).doesNotContain("policy_version").doesNotContain("kind IN")
                    .doesNotContain("kind =").doesNotContain("subject_kind");
        }
        reads.bornOnce.put(DAY, List.of(
                new RiskProofReads.BornOnceRow(AlertKind.OBLIGATION_DEFAULTED, WindowKind.D1, 2, 1, 2)));
        AlertBornOnceCensus.Result alerts = new AlertBornOnceCensus(reads).run(StandInConnections.snapshot(), DAY);
        assertThat(alerts.duplicates()).as("one default raised twice").isEqualTo(1);
        assertThat(alerts.verdict()).isEqualTo(ProofVerdict.DIVERGED);
        reads.open.add(new RiskProofReads.OpenRow(CaseKind.COLLECTIONS, 2, 2, 0));
        assertThat(new CaseOneOpenCensus(reads).run(StandInConnections.snapshot()).verdict())
                .isEqualTo(ProofVerdict.HELD);
    }

    @Test
    @DisplayName("one open case per subject and kind: a subject holding two open cases diverges; none open is vacuous")
    void oneOpenCasePerSubjectAndKind() {
        CaseOneOpenCensus census = new CaseOneOpenCensus(reads);
        assertThat(census.run(StandInConnections.snapshot()).verdict()).isEqualTo(ProofVerdict.VACUOUS);
        reads.open.add(new RiskProofReads.OpenRow(CaseKind.FRAUD, 3, 3, 0));
        assertThat(census.run(StandInConnections.snapshot()).verdict()).isEqualTo(ProofVerdict.HELD);
        reads.open.add(new RiskProofReads.OpenRow(CaseKind.AML, 3, 2, 1));
        CaseOneOpenCensus.Result result = census.run(StandInConnections.snapshot());
        assertThat(result.open()).isEqualTo(6);
        assertThat(result.subjectsOverOne()).isEqualTo(1);
        assertThat(result.verdict()).isEqualTo(ProofVerdict.DIVERGED);
        assertThat(result.asOf()).isEqualTo(reads.now);
    }

    @Test
    @DisplayName("the filing census over regulatory_report_due: filed and due hold; due past one inquiry interval is"
            + " overdue - the drive is not driving it")
    void noFilingIsLeftUndriven() {
        FilingCensus census = new FilingCensus(reads);
        reads.filing = new RiskProofReads.FilingRow(4, 2, 0);
        assertThat(census.run(StandInConnections.snapshot()).verdict()).isEqualTo(ProofVerdict.HELD);
        assertThat(reads.filingAsked).isEqualTo(Duration.ofMinutes(15)).isEqualTo(FilingCensus.OVERDUE_AFTER);
        reads.filing = new RiskProofReads.FilingRow(4, 2, 1);
        assertThat(census.run(StandInConnections.snapshot()).verdict()).isEqualTo(ProofVerdict.DIVERGED);
        reads.filing = new RiskProofReads.FilingRow(0, 0, 0);
        assertThat(census.run(StandInConnections.snapshot()).verdict()).isEqualTo(ProofVerdict.VACUOUS);
    }

    @Test
    @DisplayName("the rescreening census (P13-TSK-024, -022's row): per list version, permits = the enqueue's count,"
            + " DONE(CLEARED) = clearance signals, DONE(HIT) <= hit signals (a late hit adds one) - each broken alone is"
            + " a difference; the versions in force or retired since yesterday's start, UTC")
    void everyRescreeningPermitIsAccountedFor() {
        RescreeningCensus census = new RescreeningCensus(reads);
        assertThat(census.run(StandInConnections.snapshot()).verdict()).as("no version in scope").isEqualTo(
                ProofVerdict.VACUOUS);
        assertThat(reads.rescreeningAsked).as("yesterday's start on the database's clock")
                .isEqualTo(Instant.parse("2026-10-22T00:00:00Z"));

        // 10 born and counted; 6 cleared, each a signal; 3 hits, a fourth hit signal told late; 1 still PENDING.
        reads.rescreening.add(version(ScreeningList.SANCTIONS, 7, 10, 10, 6, 3, 6, 4));
        // A version just activated - nothing enqueued yet - holds trivially.
        reads.rescreening.add(version(ScreeningList.PEP, 3, 0, 0, 0, 0, 0, 0));
        RescreeningCensus.Result held = census.run(StandInConnections.snapshot());
        assertThat(held.verdict()).isEqualTo(ProofVerdict.HELD);
        assertThat(held.rows()).hasSize(2).allMatch(RescreeningCensus.Row::holds);

        for (RiskProofReads.RescreeningRow broken : List.of(
                version(ScreeningList.SANCTIONS, 8, 10, 11, 0, 0, 0, 0), // a permit born and never counted
                version(ScreeningList.SANCTIONS, 8, 10, 9, 0, 0, 0, 0), // a counted permit missing
                version(ScreeningList.SANCTIONS, 8, 10, 10, 5, 0, 4, 0), // a clearance with no signal
                version(ScreeningList.SANCTIONS, 8, 10, 10, 5, 0, 6, 0), // a clearance signal with no permit
                version(ScreeningList.SANCTIONS, 8, 10, 10, 0, 2, 0, 1))) { // a hit with no signal
            reads.rescreening.clear();
            reads.rescreening.add(version(ScreeningList.PEP, 3, 4, 4, 4, 0, 4, 0));
            reads.rescreening.add(broken);
            RescreeningCensus.Result result = census.run(StandInConnections.snapshot());
            assertThat(result.verdict()).as("%s", broken).isEqualTo(ProofVerdict.DIVERGED);
            assertThat(result.diverged()).isEqualTo(1);
        }
    }

    private static RiskProofReads.RescreeningRow version(ScreeningList list, int ordinal, long enqueued, long permits,
            long cleared, long hit, long clearedSignals, long hitSignals) {
        return new RiskProofReads.RescreeningRow(ScreeningListVersionId.next(IDS), list, ordinal, enqueued,
                permits, cleared, hit, clearedSignals, hitSignals);
    }

    @Test
    @DisplayName("every census reads one read-only REPEATABLE READ snapshot - refused a connection that could write")
    void oneSnapshotEach() {
        Connection writable = StandInConnections.declaring(false, false, Connection.TRANSACTION_REPEATABLE_READ);
        assertThatThrownBy(() -> monitoring.run(writable, DAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AlertBornOnceCensus(reads).run(writable, DAY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CaseOneOpenCensus(reads).run(writable)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FilingCensus(reads).run(writable)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RescreeningCensus(reads).run(writable)).isInstanceOf(IllegalStateException.class);
        assertThat(reads.calls).isEmpty();
        assertThat(ProofVerdict.of(0, 0)).isEqualTo(ProofVerdict.VACUOUS);
        assertThat(ProofVerdict.HELD.and(ProofVerdict.VACUOUS)).isEqualTo(ProofVerdict.HELD);
        assertThat(ProofVerdict.VACUOUS.and(ProofVerdict.DIVERGED)).isEqualTo(ProofVerdict.DIVERGED);
    }

    @Test
    @DisplayName("a proof's snapshot is bounded: statement_timeout armed SET LOCAL by set_config(..., true) in"
            + " milliseconds; refused on a connection that could write and for a timeout PostgreSQL reads as none")
    void aSnapshotIsBounded() {
        List<String> said = new ArrayList<>();
        Connection snapshot = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isReadOnly" -> true;
                    case "getAutoCommit" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_REPEATABLE_READ;
                    case "prepareStatement" -> {
                        said.add((String) arguments[0]);
                        yield Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {java.sql.PreparedStatement.class}, (statement, call, values) -> {
                                    switch (call.getName()) {
                                        case "setString" -> said.add(values[0] + "=" + values[1]);
                                        case "executeQuery" -> {
                                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                                    new Class<?>[] {java.sql.ResultSet.class},
                                                    (rows, rowCall, rowValues) ->
                                                            rowCall.getName().equals("next") ? Boolean.TRUE : null);
                                        }
                                        case "close" -> said.add("closed");
                                        default -> throw new UnsupportedOperationException(call.getName());
                                    }
                                    return null;
                                });
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RiskProofs.bound(snapshot, Duration.ofSeconds(5));
        assertThat(said).containsExactly("SELECT set_config('statement_timeout', ?, true)", "1=5000ms", "closed");
        assertThat(RiskProofs.STATEMENT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> RiskProofs.bound(snapshot, Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskProofs.bound(StandInConnections.declaring(false, false,
                Connection.TRANSACTION_REPEATABLE_READ), Duration.ofSeconds(5))).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------

    private UUID entry(String at, boolean monitored) {
        UUID id = FakeRiskProofReads.mintedAt(Instant.parse(at));
        journal.add(id);
        if (monitored) {
            reads.monitored.add(id);
        }
        return id;
    }

    private void watermarkAt(UUID last) {
        watermark = Optional.of(new MonitoringWatermark(MonitoringWatermark.LEDGER_JOURNAL, last,
                MonitoringWatermark.sequenceOf(last), Instant.parse("2026-10-23T12:00:00Z")));
    }

    private MonitoredLedger ledger() {
        return new MonitoredLedger() {
            @Override
            public List<MonitoredEntry> entriesAfter(Connection unitOfWork, UUID afterEntryId, Duration settleHorizon,
                    int limit) {
                throw new UnsupportedOperationException("a census never walks the journal's lines");
            }

            @Override
            public long entriesBeyond(Connection unitOfWork, UUID afterEntryId, long cap) {
                throw new UnsupportedOperationException("a census never counts the lag");
            }

            @Override
            public List<UUID> entryIds(Connection snapshot, UUID fromInclusive, UUID toExclusive) {
                return journal.stream().filter(id -> RiskProofs.order(id, fromInclusive) >= 0
                        && RiskProofs.order(id, toExclusive) < 0).sorted(RiskProofs::order).toList();
            }
        };
    }

    private MonitoringWatermarkStore watermarks() {
        return (MonitoringWatermarkStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MonitoringWatermarkStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("read")) {
                        return watermark;
                    }
                    throw new UnsupportedOperationException("a census only reads the watermark: " + method.getName());
                });
    }
}
