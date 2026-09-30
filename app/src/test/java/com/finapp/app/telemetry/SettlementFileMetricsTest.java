package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.security.Actor;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.RefusedDelivery;
import com.finapp.settlement.SettlementFile;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import com.finapp.sharedkernel.correlation.CorrelationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The file gauges' honesty rules (`P8-TSK-003`): every declared source's two series exist
 * eagerly; a source with nothing waiting reads zero; the oldest's age is whole seconds from
 * reception; and an unreadable database reads NaN, never zero.
 */
@DisplayName("the settlement file gauges (P8-TSK-003)")
class SettlementFileMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static SettlementSources register() {
        return SettlementSources.of(
                List.of(
                        new SettlementSourceDescriptor(
                                "simulated-psp.settlement",
                                SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                Optional.of("PSP-REM-[0-9]{4,12}")),
                        new SettlementSourceDescriptor(
                                "simulated-bank.statement",
                                SourceKind.BANK_STATEMENT,
                                SettlementFormatId.SIM_STATEMENT_TAGGED,
                                1,
                                Set.of(DeliveryChannel.UPLOAD),
                                Optional.empty(),
                                Optional.empty())));
    }

    /** The store seam, answering only the gauges' one query — everything else is a defect. */
    private static final class PendingOnlyStore implements SettlementFileStore<Connection> {
        private final List<PendingReading> readings;

        PendingOnlyStore(List<PendingReading> readings) {
            this.readings = readings;
        }

        @Override
        public List<PendingReading> pendingBySource(Connection unitOfWork) {
            return readings;
        }

        @Override
        public Optional<SourceRow> sourceByCode(Connection unitOfWork, String code) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public List<SourceRow> sources(Connection unitOfWork) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public Optional<FileRow> fileById(Connection unitOfWork, UUID fileId) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public Optional<FileRow> lockFileById(Connection unitOfWork, UUID fileId) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public boolean recordAttestation(
                Connection unitOfWork, UUID fileId, String attestedBy, Instant attestedAt) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public List<FileRow> newestFiles(Connection unitOfWork, int limit) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public List<RefusalRow> newestRefusals(Connection unitOfWork, int limit) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public Stored insert(Connection unitOfWork, SettlementFile file, byte[] content) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public void appendReceipt(
                Connection unitOfWork,
                UUID receiptId,
                UUID fileId,
                ReceiptOutcome outcome,
                DeliveryChannel channel,
                Actor deliveredBy,
                Instant receivedAt,
                CorrelationId correlation) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public void appendBirthEvent(
                Connection unitOfWork,
                UUID fileId,
                Actor actor,
                Instant occurredAt,
                CorrelationId correlation) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public void recordRefusal(Connection unitOfWork, RefusedDelivery refusal) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public byte[] readContent(Connection unitOfWork, UUID fileId) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        // The parse leg's surface (P8-TSK-008) - not the gauges', same as everything above.

        @Override
        public List<UUID> dueForParse(Connection unitOfWork, Instant now, int limit) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public Optional<FileRow> lockDueById(Connection unitOfWork, UUID fileId, Instant now) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public boolean markParsed(Connection unitOfWork, UUID fileId, Instant at) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public boolean markRejected(
                Connection unitOfWork,
                UUID fileId,
                com.finapp.settlement.FileStatus from,
                com.finapp.settlement.RejectionCode code,
                Optional<String> detail,
                Instant at) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public int bumpParseFailures(Connection unitOfWork, UUID fileId) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public void scheduleNextParse(Connection unitOfWork, UUID fileId, Instant nextParseAt) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public void appendFileEvent(
                Connection unitOfWork,
                UUID fileId,
                com.finapp.settlement.FileStatus from,
                com.finapp.settlement.FileStatus to,
                Actor actor,
                Optional<String> reason,
                Instant occurredAt,
                CorrelationId correlation) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public void recordIngestionErrors(
                Connection unitOfWork,
                UUID fileId,
                List<com.finapp.settlement.format.FormatDefect> defects) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        // The accept leg's surface (P8-TSK-009) - not the gauges', same as everything above.

        @Override
        public List<UUID> dueForAccept(Connection unitOfWork, int limit) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public Optional<FileRow> lockEligibleById(Connection unitOfWork, UUID fileId) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public boolean markFileAccepted(Connection unitOfWork, UUID fileId, Instant at) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public Optional<SourceRow> sourceByIdForUpdate(Connection unitOfWork, UUID sourceId) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }

        @Override
        public long claimNextSequence(Connection unitOfWork, UUID sourceId) {
            throw new UnsupportedOperationException("the gauges read pendingBySource alone");
        }
    }

    /** A connection the fake never touches — the gauges only open and close it. */
    private static Connection unusedConnection() {
        return (Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            if ("close".equals(method.getName())) {
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    @Test
    @DisplayName("a waiting file is counted with its age; a quiet source reads zero on both")
    void countsAndAges() {
        SettlementFileStore<Connection> store =
                new PendingOnlyStore(
                        List.of(
                                new SettlementFileStore.PendingReading(
                                        "simulated-psp.settlement",
                                        3,
                                        Optional.of(NOW.minusSeconds(90)))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SettlementFileMetrics(
                store, register(), SettlementFileMetricsTest::unusedConnection, CLOCK, registry);

        assertThat(gaugeValue(registry, SettlementFileMetrics.PENDING,
                        "simulated-psp.settlement"))
                .isEqualTo(3.0);
        assertThat(gaugeValue(registry, SettlementFileMetrics.AGE,
                        "simulated-psp.settlement"))
                .as("whole seconds since the oldest reception")
                .isEqualTo(90.0);
        assertThat(gaugeValue(registry, SettlementFileMetrics.PENDING,
                        "simulated-bank.statement"))
                .as("a quiet source is an honest zero, not an absent series")
                .isEqualTo(0.0);
        assertThat(gaugeValue(registry, SettlementFileMetrics.AGE,
                        "simulated-bank.statement"))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("an unreadable database reads NaN on every series - absent is alertable, a"
            + " comforting zero is not")
    void unreadableReadsNaN() {
        SettlementFileStore<Connection> store = new PendingOnlyStore(List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SettlementFileMetrics(
                store,
                register(),
                () -> {
                    throw new SQLException("nope");
                },
                CLOCK,
                registry);

        assertThat(gaugeValue(registry, SettlementFileMetrics.PENDING,
                        "simulated-psp.settlement"))
                .isNaN();
        assertThat(gaugeValue(registry, SettlementFileMetrics.AGE,
                        "simulated-bank.statement"))
                .isNaN();
    }

    private static double gaugeValue(SimpleMeterRegistry registry, String name, String source) {
        return registry.find(name).tag("source", source).gauge().value();
    }
}
