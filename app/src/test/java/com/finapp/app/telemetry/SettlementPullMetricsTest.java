package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pull's two series and their honesty rules (`P8-TSK-021`): every declared source's silence
 * gauge and failure counters exist eagerly; silence is whole seconds since the source's last
 * ACCEPTED batch; a source never accepted, and an unreadable database, read NaN - never zero,
 * which would read as "just heard from"; a failure counts once under its own outcome.
 */
@DisplayName("the settlement pull meters (P8-TSK-021)")
class SettlementPullMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PSP = "simulated-psp.settlement";
    private static final String BANK = "simulated-bank.statement";
    private static final UUID PSP_ID = UUID.randomUUID();
    private static final UUID BANK_ID = UUID.randomUUID();

    private static SettlementSources register() {
        return SettlementSources.of(
                List.of(
                        new SettlementSourceDescriptor(
                                PSP,
                                SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                Optional.of("PSP-REM-[0-9]{4,12}")),
                        new SettlementSourceDescriptor(
                                BANK,
                                SourceKind.BANK_STATEMENT,
                                SettlementFormatId.SIM_STATEMENT_TAGGED,
                                1,
                                Set.of(DeliveryChannel.UPLOAD),
                                Optional.empty(),
                                Optional.empty())));
    }

    /** A store seam answering only {@code method}; any other call is a defect. */
    @SuppressWarnings("unchecked")
    private static <S> S answering(Class<?> type, String method, Object answer) {
        return (S)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, called, args) -> {
                            if (called.getName().equals(method)) {
                                return answer;
                            }
                            throw new UnsupportedOperationException(
                                    "the silence gauge reads " + method + " alone, not "
                                            + called.getName());
                        });
    }

    private static Connection connection() {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, called, args) -> null);
    }

    private static SettlementFileStore<Connection> sources() {
        return answering(
                SettlementFileStore.class,
                "sources",
                List.of(
                        new SettlementFileStore.SourceRow(
                                PSP_ID, PSP, SourceKind.PSP_SETTLEMENT_REPORT, true),
                        new SettlementFileStore.SourceRow(
                                BANK_ID, BANK, SourceKind.BANK_STATEMENT, true)));
    }

    private static SettlementPullMetrics meters(
            SimpleMeterRegistry registry,
            Map<UUID, Instant> lastAccepted,
            SettlementPullMetrics.Connections connections) {
        SettlementBatchStore<Connection> batches =
                answering(SettlementBatchStore.class, "lastAcceptedAt", lastAccepted);
        return new SettlementPullMetrics(
                sources(), batches, register(), connections, CLOCK, registry);
    }

    private static double silence(SimpleMeterRegistry registry, String source) {
        return registry.get(SettlementPullMetrics.SILENCE).tag("source", source).gauge().value();
    }

    @Test
    @DisplayName("every declared source has its silence gauge and a failure counter per outcome"
            + " from the first scrape")
    void everySeriesExistsEagerly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        meters(registry, Map.of(), SettlementPullMetricsTest::connection);
        for (String source : List.of(PSP, BANK)) {
            assertThat(registry.find(SettlementPullMetrics.SILENCE).tag("source", source).gauge())
                    .isNotNull();
            for (String outcome :
                    List.of("not_yet", "unavailable", "timeout", "transport", "refused_answer")) {
                assertThat(registry.get(SettlementPullMetrics.PULL_FAILURE)
                                .tags("source", source, "outcome", outcome)
                                .counter()
                                .count())
                        .as(source + " " + outcome)
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("silence is whole seconds since the last accepted batch; a source never"
            + " accepted reads NaN, never zero")
    void silenceCountsFromTheLastAcceptance() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        meters(
                registry, Map.of(PSP_ID, NOW.minusSeconds(90)), SettlementPullMetricsTest::connection);
        assertThat(silence(registry, PSP)).isEqualTo(90.0);
        assertThat(silence(registry, BANK))
                .as("never accepted: 'since when' has no answer yet")
                .isNaN();
    }

    @Test
    @DisplayName("an unreadable database reads NaN for every source, never zero")
    void anUnreadableDatabaseReadsNaN() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        meters(
                registry,
                Map.of(PSP_ID, NOW.minusSeconds(90)),
                () -> {
                    throw new SQLTransientConnectionException("the pool is exhausted");
                });
        assertThat(silence(registry, PSP)).isNaN();
        assertThat(silence(registry, BANK)).isNaN();
    }

    @Test
    @DisplayName("a fetch that brought nothing counts once, under its own source and outcome")
    void aFailureCountsOnce() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SettlementPullMetrics meters =
                meters(registry, Map.of(), SettlementPullMetricsTest::connection);
        meters.notReceived(PSP, "timeout");
        assertThat(registry.get(SettlementPullMetrics.PULL_FAILURE)
                        .tags("source", PSP, "outcome", "timeout")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get(SettlementPullMetrics.PULL_FAILURE)
                        .tags("source", BANK, "outcome", "timeout")
                        .counter()
                        .count())
                .isZero();
    }
}
