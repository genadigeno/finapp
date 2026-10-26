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
 * The monitoring, born-once, one-open and filing censuses over fakes (`P13-TSK-023`; {@code INV-RSK-09},
 * {@code INV-RSK-06}; ADR-0111 decision 6; F8; {@code DESIGN-023} D3, D5): monitored = journal by set, so a lost entry
 * and a phantom row cannot cancel out; the watermark bounds what is judged (pending behind it, ahead of a reprocessing);
 * a second alert of one key is found whatever day it was born, `-020`'s AML alerts per window among the rows; a second
 * open case of one subject and kind is found; a filing left undriven past one inquiry interval is overdue. Each census
 * reads one read-only snapshot and writes nothing. The database's half is {@code RiskProofsDatabaseTest}'s (owed, C5).
 */
@DisplayName("the monitoring, born-once, one-open and filing censuses: each re-proven by query, a difference named"
        + " (P13-TSK-023)")
class RiskCensusesTest {

    private static final RiskDay DAY = RiskDay.of(LocalDate.of(2026, 10, 23));

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
    @DisplayName("every census reads one read-only REPEATABLE READ snapshot - refused a connection that could write")
    void oneSnapshotEach() {
        Connection writable = StandInConnections.declaring(false, false, Connection.TRANSACTION_REPEATABLE_READ);
        assertThatThrownBy(() -> monitoring.run(writable, DAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AlertBornOnceCensus(reads).run(writable, DAY))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CaseOneOpenCensus(reads).run(writable)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FilingCensus(reads).run(writable)).isInstanceOf(IllegalStateException.class);
        assertThat(reads.calls).isEmpty();
        assertThat(ProofVerdict.of(0, 0)).isEqualTo(ProofVerdict.VACUOUS);
        assertThat(ProofVerdict.HELD.and(ProofVerdict.VACUOUS)).isEqualTo(ProofVerdict.HELD);
        assertThat(ProofVerdict.VACUOUS.and(ProofVerdict.DIVERGED)).isEqualTo(ProofVerdict.DIVERGED);
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
