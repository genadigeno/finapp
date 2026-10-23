package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The completions' hint over the watermark (`P13-TSK-019` closing `P13-TSK-005`'s seam): every completed movement
 * pulls the ledger journal's permit on the handler's own connection - one store call, the source fixed, nothing of the
 * movement read; a store that pulled nothing (the row held by a sweep, or the permit already due) is fine; a store
 * that fails fails the delivery as any statement would.
 */
@DisplayName("the completions' hint over the monitoring watermark (P13-TSK-019)")
class WatermarkMonitoringHintsTest {

    private static final Connection HANDLERS_CONNECTION = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fake holds its state in memory");
            });

    @Test
    @DisplayName("a completed movement hints the ledger journal's watermark on the handler's connection, whatever the"
            + " movement was")
    void aCompletionHintsTheJournalsWatermark() {
        FakeWatermarks watermarks = new FakeWatermarks();
        MonitoringHints hints = new WatermarkMonitoringHints(watermarks);

        hints.movementCompleted(HANDLERS_CONNECTION, new MonitoringHints.CompletedMovement("transfers",
                "transfers.TransferCompleted", UUID.randomUUID(), Instant.parse("2026-10-23T10:00:00Z")));
        hints.movementCompleted(HANDLERS_CONNECTION, new MonitoringHints.CompletedMovement("payments",
                "payments.WithdrawalCompleted", UUID.randomUUID(), Instant.parse("2026-10-23T10:00:01Z")));

        assertThat(watermarks.hinted).containsExactly(MonitoringWatermark.LEDGER_JOURNAL,
                MonitoringWatermark.LEDGER_JOURNAL);
        assertThat(watermarks.connections).allMatch(connection -> connection == HANDLERS_CONNECTION);
        assertThat(watermarks.moves).as("a hint never moves the position").isZero();
    }

    @Test
    @DisplayName("a hint that pulled nothing - the row held by a sweep, or the permit already due - is not a failure")
    void aHintThatPulledNothing() {
        FakeWatermarks watermarks = new FakeWatermarks();
        watermarks.pulls = false;
        new WatermarkMonitoringHints(watermarks).movementCompleted(HANDLERS_CONNECTION,
                new MonitoringHints.CompletedMovement("crossborder", "crossborder.PaymentCompleted", UUID.randomUUID(),
                        Instant.parse("2026-10-23T10:00:00Z")));
        assertThat(watermarks.hinted).hasSize(1);
    }

    @Test
    @DisplayName("a store that fails fails the delivery - the inbox retries it - and a null is refused before anything")
    void failuresAndNulls() {
        FakeWatermarks watermarks = new FakeWatermarks();
        watermarks.failing = true;
        WatermarkMonitoringHints hints = new WatermarkMonitoringHints(watermarks);
        MonitoringHints.CompletedMovement movement = new MonitoringHints.CompletedMovement("transfers",
                "transfers.TransferCompleted", UUID.randomUUID(), Instant.parse("2026-10-23T10:00:00Z"));
        assertThatExceptionOfType(RiskStorageException.class)
                .isThrownBy(() -> hints.movementCompleted(HANDLERS_CONNECTION, movement));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> hints.movementCompleted(null, movement));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> hints.movementCompleted(HANDLERS_CONNECTION, null));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> new WatermarkMonitoringHints(null));
    }

    private static final class FakeWatermarks implements MonitoringWatermarkStore {
        final List<String> hinted = new ArrayList<>();
        final List<Connection> connections = new ArrayList<>();
        boolean pulls = true;
        boolean failing;
        int moves;

        @Override
        public Optional<MonitoringWatermark> claimDue(Connection unitOfWork, String source) {
            throw new UnsupportedOperationException("a hint never claims");
        }

        @Override
        public Optional<MonitoringWatermark> lock(Connection unitOfWork, String source) {
            throw new UnsupportedOperationException("a hint never locks");
        }

        @Override
        public Optional<MonitoringWatermark> read(Connection unitOfWork, String source) {
            throw new UnsupportedOperationException("a hint never reads");
        }

        @Override
        public void move(Connection unitOfWork, String source, UUID lastEntryId, Duration permitDelay) {
            moves++;
        }

        @Override
        public boolean hint(Connection unitOfWork, String source) {
            if (failing) {
                throw new RiskStorageException("risk storage failed hinting the monitoring watermark: SQLSTATE 08006");
            }
            hinted.add(source);
            connections.add(unitOfWork);
            return pulls;
        }
    }
}
