package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.telemetry.Spans;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The accept leg's series (`P8-TSK-024`, `PHASE_8_PLAN.md` §15): {@code
 * finapp.settlement.batch.accepted} and the {@code accept} stage of {@code
 * finapp.settlement.ingestion.latency}, eager per declared source beside the parse stage; an
 * acceptance counts both, and only once its transaction commits (the {@code
 * CommittedIntakeOutcomes} seam).
 */
@DisplayName("the settlement acceptance meters (P8-TSK-024)")
class SettlementAcceptanceMetersTest {

    private static final String PSP = "simulated-psp.settlement";
    private static final String BANK = "simulated-bank.statement";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static SettlementSources register() {
        return SettlementSources.of(
                List.of(
                        new SettlementSourceDescriptor(
                                PSP,
                                SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                1,
                                Set.of(DeliveryChannel.UPLOAD),
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

    private Counter accepted(String source) {
        return registry.find(SettlementMeters.BATCH_ACCEPTED).tag("source", source).counter();
    }

    private Timer latency(String source, String stage) {
        return registry.find(SettlementMeters.INGESTION_LATENCY)
                .tag("source", source)
                .tag("stage", stage)
                .timer();
    }

    @Test
    @DisplayName("batch.accepted and both latency stages exist at zero for every declared source")
    void theAcceptSeriesAreEager() {
        new SettlementMeters(registry, register());

        for (String source : List.of(PSP, BANK)) {
            assertThat(accepted(source)).as("batch.accepted{%s} exists eagerly", source)
                    .isNotNull();
            assertThat(accepted(source).count()).isZero();
            for (String stage :
                    List.of(SettlementMeters.PARSE_STAGE, SettlementMeters.ACCEPT_STAGE)) {
                assertThat(latency(source, stage))
                        .as("ingestion.latency{%s,%s} exists eagerly", source, stage)
                        .isNotNull();
                assertThat(latency(source, stage).count()).isZero();
            }
        }
        assertThat(registry.find(SettlementMeters.BATCH_ACCEPTED).counters())
                .as("bounded by the register")
                .hasSize(2);
        assertThat(registry.find(SettlementMeters.INGESTION_LATENCY).timers())
                .as("two sources x two stages")
                .hasSize(4);
    }

    @Test
    @DisplayName("an acceptance counts the batch and records its door-to-accepted age on the"
            + " accept stage alone")
    void countAcceptedMovesBoth() {
        SettlementMeters meters = new SettlementMeters(registry, register());

        meters.countAccepted(PSP, Duration.ofMinutes(4));

        assertThat(accepted(PSP).count()).isEqualTo(1.0d);
        assertThat(latency(PSP, SettlementMeters.ACCEPT_STAGE).count()).isEqualTo(1L);
        assertThat(latency(PSP, SettlementMeters.ACCEPT_STAGE).totalTime(TimeUnit.MINUTES))
                .isEqualTo(4.0d);
        assertThat(latency(PSP, SettlementMeters.PARSE_STAGE).count())
                .as("the parse stage is the parse leg's")
                .isZero();
        assertThat(accepted(BANK).count()).isZero();
    }

    @Test
    @DisplayName("the parse leg's latency lands on the parse stage, never the accept stage")
    void parseLatencyStaysOnTheParseStage() {
        SettlementMeters meters = new SettlementMeters(registry, register());

        meters.recordParseLatency(BANK, Duration.ofSeconds(30));

        assertThat(latency(BANK, SettlementMeters.PARSE_STAGE).count()).isEqualTo(1L);
        assertThat(latency(BANK, SettlementMeters.ACCEPT_STAGE).count()).isZero();
        assertThat(accepted(BANK).count()).isZero();
    }

    @Test
    @DisplayName("the observer's acceptance counts only once its transaction commits")
    void theObserverCountsAfterCommit() {
        CommittedIntakeOutcomes observer =
                new CommittedIntakeOutcomes(new SettlementMeters(registry, register()));
        TransactionSynchronizationManager.initSynchronization();

        observer.accepted(PSP, Duration.ofSeconds(42));

        assertThat(accepted(PSP).count())
                .as("the acceptance has not committed: nothing happened yet")
                .isZero();
        assertThat(latency(PSP, SettlementMeters.ACCEPT_STAGE).count()).isZero();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        assertThat(accepted(PSP).count()).isEqualTo(1.0d);
        assertThat(latency(PSP, SettlementMeters.ACCEPT_STAGE).count()).isEqualTo(1L);
        assertThat(latency(PSP, SettlementMeters.ACCEPT_STAGE).totalTime(TimeUnit.SECONDS))
                .isEqualTo(42.0d);
    }

    @Test
    @DisplayName("a rolled-back acceptance is never counted")
    void aRolledBackAcceptanceCountsNothing() {
        CommittedIntakeOutcomes observer =
                new CommittedIntakeOutcomes(new SettlementMeters(registry, register()));
        TransactionSynchronizationManager.initSynchronization();

        observer.accepted(PSP, Duration.ofSeconds(42));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();

        assertThat(accepted(PSP).count()).isZero();
        assertThat(latency(PSP, SettlementMeters.ACCEPT_STAGE).count()).isZero();
    }

    @Test
    @DisplayName("with no transaction the acceptance counts at once")
    void withoutATransactionItCountsAtOnce() {
        new CommittedIntakeOutcomes(new SettlementMeters(registry, register()))
                .accepted(BANK, Duration.ofSeconds(1));

        assertThat(accepted(BANK).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the observers hand the legs the spans they were given; the one-argument"
            + " constructors trace nothing")
    void theObserversCarryTheirSpans() {
        SettlementMeters meters = new SettlementMeters(registry, register());
        Spans spans = new Spans() {
            @Override
            public <T> T within(String name, Map<String, String> identifiers,
                    java.util.function.Supplier<T> work) {
                return work.get();
            }
        };

        assertThat(new CommittedIntakeOutcomes(meters, spans).spans()).isSameAs(spans);
        assertThat(new CommittedReceptionOutcomes(meters, spans).spans()).isSameAs(spans);
        assertThat(new CommittedIntakeOutcomes(meters).spans()).isSameAs(Spans.NONE);
        assertThat(new CommittedReceptionOutcomes(meters).spans()).isSameAs(Spans.NONE);
    }
}
