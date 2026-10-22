package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reprocessing order over fakes (`P13-TSK-019`; ADR-0111 decision 4; {@code INV-RSK-09}, {@code INV-RSK-08}): the
 * reason screened before the lock, the point judged at or before the watermark under it, the position moved back to the
 * millisecond's floor with the permit due now, and the act recorded with a summary that names the positions and never
 * the reason.
 */
@DisplayName("the monitoring reprocessing order (P13-TSK-019)")
class MonitoringReprocessingTest {

    private static final IdGenerator IDS = new IdGenerator(
            Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC), new Random(19));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);
    private static final Actor OFFICER = new Actor("officer-1", ActorType.EMPLOYEE);
    private static final CorrelationId CORRELATION = CorrelationId.generate(IDS);
    private static final Connection NO_CONNECTION = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes hold their state in memory");
            });
    private static final String SOURCE = MonitoringWatermark.LEDGER_JOURNAL;
    private static final long STANDING = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli();

    private FakeWatermarks watermarks;
    private List<AuditRecord> audited;
    private MonitoringReprocessing reprocessing;

    @BeforeEach
    void fakes() {
        watermarks = new FakeWatermarks();
        audited = new ArrayList<>();
        AuditWriter<Connection> audit = (unitOfWork, record) -> audited.add(record);
        reprocessing = new MonitoringReprocessing(watermarks, audit, IDS, CLOCK);
    }

    @Test
    @DisplayName("ordered: the watermark locked, moved back to the millisecond's floor with its permit due now, the act"
            + " recorded SUCCEEDED with the reason and a summary of the two positions - never the reason")
    void ordered() {
        long from = STANDING - Duration.ofHours(2).toMillis();
        MonitoringReprocessing.Ordered ordered = reprocessing.order(NO_CONNECTION, SOURCE, from, OFFICER,
                "policy v2 applied to history", CORRELATION);

        assertThat(ordered).isEqualTo(new MonitoringReprocessing.Ordered(SOURCE, from, STANDING));
        assertThat(watermarks.locks).isEqualTo(1);
        assertThat(watermarks.row.lastEntryId()).isEqualTo(MonitoringWatermark.positionAt(from));
        assertThat(watermarks.row.lastSequence()).isEqualTo(from);
        assertThat(watermarks.lastPermitDelay).isEqualTo(Duration.ZERO);

        assertThat(audited).hasSize(1);
        AuditRecord record = audited.getFirst();
        assertThat(record.operation()).isEqualTo(RiskAuditAction.REPROCESSING_ORDERED);
        assertThat(record.actor()).isEqualTo(OFFICER);
        assertThat(record.targetType()).isEqualTo("monitoring_watermark");
        assertThat(record.targetId()).isEqualTo(SOURCE);
        assertThat(record.reason()).contains("policy v2 applied to history");
        assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(record.correlationId()).isEqualTo(CORRELATION);
        assertThat(record.changeSummary()).contains(
                "monitoring reprocessing ordered from sequence " + from + "; the watermark stood at " + STANDING);
        assertThat(record.changeSummary().orElseThrow()).doesNotContain("policy v2");
    }

    @Test
    @DisplayName("the summary names the positions only - a count's shape, no reason, no amount, no party")
    void theSummaryCarriesPositionsOnly() {
        assertThat(MonitoringReprocessing.summary(10, 20))
                .isEqualTo("monitoring reprocessing ordered from sequence 10; the watermark stood at 20");
    }

    @Test
    @DisplayName("at the watermark's own sequence is allowed (the millisecond's floor lies at or before its id)")
    void atTheWatermarkIsAllowed() {
        reprocessing.order(NO_CONNECTION, SOURCE, STANDING, OFFICER, "re-run the last millisecond", CORRELATION);
        assertThat(watermarks.row.lastEntryId()).isEqualTo(MonitoringWatermark.positionAt(STANDING));
    }

    @Test
    @DisplayName("a point ahead of the watermark, or below zero, is refused under the lock and nothing is written")
    void aheadIsRefused() {
        assertThatExceptionOfType(MonitoringReprocessing.NotBehind.class)
                .isThrownBy(() -> reprocessing.order(NO_CONNECTION, SOURCE, STANDING + 1, OFFICER, "skip", CORRELATION));
        assertThatExceptionOfType(MonitoringReprocessing.NotBehind.class)
                .isThrownBy(() -> reprocessing.order(NO_CONNECTION, SOURCE, -1, OFFICER, "below", CORRELATION));
        assertThat(watermarks.moves).isZero();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("the reason is required, bounded and screened for instrument shapes - judged before anything is locked")
    void theReasonIsScreened() {
        for (String reason : new String[] {null, "", "   ", "x".repeat(1001), "card 4111 1111 1111 1111 seen"}) {
            assertThatExceptionOfType(MonitoringReprocessing.ReasonRequired.class)
                    .as(String.valueOf(reason))
                    .isThrownBy(() -> reprocessing.order(NO_CONNECTION, SOURCE, 0, OFFICER, reason, CORRELATION));
        }
        assertThat(watermarks.locks).isZero();
        assertThat(watermarks.moves).isZero();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("an unknown source is not found; nothing is written")
    void anUnknownSource() {
        assertThatExceptionOfType(MonitoringReprocessing.SourceNotFound.class)
                .isThrownBy(() -> reprocessing.order(NO_CONNECTION, "ledger.other", 0, OFFICER, "why", CORRELATION));
        assertThat(watermarks.moves).isZero();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("the act requires a reason in the catalogue, and the code is the plan's")
    void theActAndItsCode() {
        assertThat(RiskAuditAction.REPROCESSING_ORDERED.code()).isEqualTo("risk.ReprocessingOrdered");
        assertThat(RiskAuditAction.REPROCESSING_ORDERED.requiresReason()).isTrue();
        assertThat(RiskErrorCode.REPROCESSING_NOT_BEHIND.status()).isEqualTo(422);
        assertThat(RiskErrorCode.REASON_REQUIRED.status()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeWatermarks implements MonitoringWatermarkStore {
        MonitoringWatermark row = new MonitoringWatermark(SOURCE, MonitoringWatermark.positionAt(STANDING), STANDING,
                Instant.EPOCH);
        int locks;
        int moves;
        Duration lastPermitDelay;

        @Override
        public Optional<MonitoringWatermark> claimDue(Connection unitOfWork, String source) {
            throw new UnsupportedOperationException("the door never claims");
        }

        @Override
        public Optional<MonitoringWatermark> lock(Connection unitOfWork, String source) {
            if (!source.equals(row.source())) {
                return Optional.empty();
            }
            locks++;
            return Optional.of(row);
        }

        @Override
        public Optional<MonitoringWatermark> read(Connection unitOfWork, String source) {
            return Optional.of(row);
        }

        @Override
        public void move(Connection unitOfWork, String source, UUID lastEntryId, Duration permitDelay) {
            moves++;
            lastPermitDelay = permitDelay;
            row = new MonitoringWatermark(source, lastEntryId, MonitoringWatermark.sequenceOf(lastEntryId),
                    Instant.EPOCH.plus(permitDelay));
        }

        @Override
        public boolean hint(Connection unitOfWork, String source) {
            return false;
        }
    }
}
