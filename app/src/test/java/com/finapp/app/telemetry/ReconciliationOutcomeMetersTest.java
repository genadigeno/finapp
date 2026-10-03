package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.telemetry.Spans;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.DecisionOutcome;
import com.finapp.reconciliation.ResolutionKind;
import com.finapp.reconciliation.ResolutionOutcome;
import com.finapp.reconciliation.Severity;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reconciliation's counters and timers (`P8-TSK-024`, `PHASE_8_PLAN.md` §15) - the
 * {@code CommittedRailOutcomes} shape: every series eager at zero from the first scrape; each
 * fact counted only once the transaction that made it true commits (a rolled-back chunk,
 * rematch or decision is never published); a stale approval's refusal counted at once, because
 * nothing commits; and a source the register does not name never invents a tag value.
 */
@DisplayName("the reconciliation outcome meters (P8-TSK-024)")
class ReconciliationOutcomeMetersTest {

    private static final String PSP = "simulated-psp.settlement";
    private static final String BANK = "simulated-bank.statement";
    private static final UUID PSP_ID = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final UUID BANK_ID = UUID.fromString("01a0e2bc-8200-7004-8000-000000000004");
    private static final Function<UUID, Optional<String>> SEEDED =
            id -> Optional.ofNullable(Map.of(PSP_ID, PSP, BANK_ID, BANK).get(id));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Completed, as a transaction manager completes them: the deferred counts' list is
            // a transaction-bound resource, released at completion, never left to the next test.
            TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                    sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
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

    private ReconciliationOutcomeMeters meters() {
        return meters(SEEDED);
    }

    private ReconciliationOutcomeMeters meters(Function<UUID, Optional<String>> codes) {
        return new ReconciliationOutcomeMeters(registry, register(), codes, Spans.NONE);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private Counter item(String source, DecisionOutcome outcome) {
        return registry.find(ReconciliationOutcomeMeters.ITEM)
                .tag("source", source)
                .tag("outcome", lower(outcome))
                .counter();
    }

    private Counter rematch(String source, DecisionOutcome outcome) {
        return registry.find(ReconciliationOutcomeMeters.REMATCH)
                .tag("source", source)
                .tag("outcome", lower(outcome))
                .counter();
    }

    private Timer runLatency(String source) {
        return registry.find(ReconciliationOutcomeMeters.RUN_LATENCY)
                .tag("source", source)
                .timer();
    }

    private Counter raised(BreakType type, Severity severity) {
        return registry.find(ReconciliationOutcomeMeters.BREAK_RAISED)
                .tag("type", lower(type))
                .tag("severity", lower(severity))
                .counter();
    }

    private Counter resolution(ResolutionKind kind, ResolutionOutcome outcome) {
        return registry.find(ReconciliationOutcomeMeters.RESOLUTION)
                .tag("type", lower(kind))
                .tag("outcome", lower(outcome))
                .counter();
    }

    private Timer resolutionLatency(ResolutionKind kind) {
        return registry.find(ReconciliationOutcomeMeters.RESOLUTION_LATENCY)
                .tag("type", lower(kind))
                .timer();
    }

    private Counter adjustment(ResolutionKind kind) {
        return registry.find(ReconciliationOutcomeMeters.ADJUSTMENT)
                .tag("type", lower(kind))
                .counter();
    }

    /** Every count this registry holds, summed - the "nothing at all was counted" reading. */
    private double everyCount() {
        double counted = 0;
        for (Counter counter : registry.find(ReconciliationOutcomeMeters.ITEM).counters()) {
            counted += counter.count();
        }
        for (String name :
                List.of(
                        ReconciliationOutcomeMeters.REMATCH,
                        ReconciliationOutcomeMeters.BREAK_RAISED,
                        ReconciliationOutcomeMeters.RESOLUTION,
                        ReconciliationOutcomeMeters.ADJUSTMENT)) {
            for (Counter counter : registry.find(name).counters()) {
                counted += counter.count();
            }
        }
        for (String name :
                List.of(
                        ReconciliationOutcomeMeters.RUN_LATENCY,
                        ReconciliationOutcomeMeters.RESOLUTION_LATENCY)) {
            for (Timer timer : registry.find(name).timers()) {
                counted += timer.count();
            }
        }
        return counted;
    }

    /** One of each fact - what a busy transaction would report before it commits. */
    private static void reportOneOfEach(ReconciliationOutcomeMeters meters) {
        meters.runCompleted(
                PSP_ID, Map.of(DecisionOutcome.MATCHED, 2L), Optional.of(Duration.ofSeconds(5)));
        meters.rematched(PSP_ID, DecisionOutcome.MATCHED);
        meters.resolved(
                ResolutionKind.WRITE_OFF,
                ResolutionOutcome.APPROVED,
                Optional.of(Duration.ofMinutes(3)));
        meters.adjusted(ResolutionKind.WRITE_OFF);
        meters.countRaised(BreakType.AMOUNT_MISMATCH, Severity.HIGH);
    }

    // ------------------------------------------------------------------ eager series

    @Test
    @DisplayName("every series exists at zero from construction - per declared source and"
            + " outcome, per type and severity, per resolution kind and outcome")
    void everySeriesIsEager() {
        meters();

        for (String source : List.of(PSP, BANK)) {
            for (DecisionOutcome outcome : DecisionOutcome.values()) {
                assertThat(item(source, outcome))
                        .as("item{%s,%s} exists eagerly", source, outcome)
                        .isNotNull();
                assertThat(item(source, outcome).count()).isZero();
                assertThat(rematch(source, outcome))
                        .as("rematch{%s,%s} exists eagerly", source, outcome)
                        .isNotNull();
                assertThat(rematch(source, outcome).count()).isZero();
            }
            assertThat(runLatency(source)).as("run.latency{%s}", source).isNotNull();
            assertThat(runLatency(source).count()).isZero();
        }
        assertThat(registry.find(ReconciliationOutcomeMeters.ITEM).counters())
                .as("bounded by the register: 2 sources x 6 outcomes, no invented source")
                .hasSize(2 * DecisionOutcome.values().length);

        for (BreakType type : BreakType.values()) {
            for (Severity severity : Severity.values()) {
                assertThat(raised(type, severity))
                        .as("break.raised{%s,%s}", type, severity)
                        .isNotNull();
                assertThat(raised(type, severity).count()).isZero();
            }
        }
        assertThat(registry.find(ReconciliationOutcomeMeters.BREAK_RAISED).counters())
                .hasSize(BreakType.values().length * Severity.values().length);

        for (ResolutionKind kind : ResolutionKind.values()) {
            for (ResolutionOutcome outcome : ResolutionOutcome.values()) {
                assertThat(resolution(kind, outcome))
                        .as("resolution{%s,%s}", kind, outcome)
                        .isNotNull();
                assertThat(resolution(kind, outcome).count()).isZero();
            }
            assertThat(resolutionLatency(kind)).as("resolution.latency{%s}", kind).isNotNull();
            if (kind.postsAdjustment()) {
                assertThat(adjustment(kind)).as("adjustment{%s}", kind).isNotNull();
                assertThat(adjustment(kind).count()).isZero();
            } else {
                assertThat(adjustment(kind))
                        .as("a kind that posts nothing has no adjustment series: %s", kind)
                        .isNull();
            }
        }
        assertThat(registry.find(ReconciliationOutcomeMeters.ADJUSTMENT).counters())
                .as("WRITE_OFF, TRANSFER_TO_ACCOUNT and RECOGNISE_GAIN post adjustments")
                .hasSize(3);
    }

    @Test
    @DisplayName("the tag values are lower case - outcome, type and severity alike")
    void tagValuesAreLowerCase() {
        meters();

        assertThat(registry.getMeters())
                .allSatisfy(meter -> meter.getId().getTags().forEach(tag -> {
                    if (!tag.getKey().equals("source")) {
                        assertThat(tag.getValue())
                                .as("%s's %s", meter.getId().getName(), tag.getKey())
                                .isEqualTo(tag.getValue().toLowerCase(Locale.ROOT));
                    }
                }));
    }

    @Test
    @DisplayName("the port hands out the spans it was given")
    void spansAreTheGivenOnes() {
        Spans spans = new Spans() {
            @Override
            public <T> T within(String name, Map<String, String> identifiers,
                    java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
        assertThat(new ReconciliationOutcomeMeters(registry, register(), SEEDED, spans).spans())
                .isSameAs(spans);
    }

    // ------------------------------------------------------------------ with no transaction

    @Test
    @DisplayName("a completed run counts its items by source and outcome and records its age")
    void runCompletedCountsItemsAndLatency() {
        ReconciliationOutcomeMeters meters = meters();
        Map<DecisionOutcome, Long> outcomes = new EnumMap<>(DecisionOutcome.class);
        outcomes.put(DecisionOutcome.MATCHED, 3L);
        outcomes.put(DecisionOutcome.PARKED, 1L);

        meters.runCompleted(PSP_ID, outcomes, Optional.of(Duration.ofSeconds(90)));

        assertThat(item(PSP, DecisionOutcome.MATCHED).count()).isEqualTo(3.0d);
        assertThat(item(PSP, DecisionOutcome.PARKED).count()).isEqualTo(1.0d);
        assertThat(item(PSP, DecisionOutcome.UNMATCHED).count()).isZero();
        assertThat(item(BANK, DecisionOutcome.MATCHED).count())
                .as("another source's series is untouched")
                .isZero();
        assertThat(runLatency(PSP).count()).isEqualTo(1L);
        assertThat(runLatency(PSP).totalTime(TimeUnit.SECONDS)).isEqualTo(90.0d);
        assertThat(runLatency(BANK).count()).isZero();
    }

    @Test
    @DisplayName("a run whose birth is unknown counts its items and records no latency")
    void runCompletedWithoutBirthRecordsNoLatency() {
        ReconciliationOutcomeMeters meters = meters();

        meters.runCompleted(BANK_ID, Map.of(DecisionOutcome.UNMATCHED, 2L), Optional.empty());

        assertThat(item(BANK, DecisionOutcome.UNMATCHED).count()).isEqualTo(2.0d);
        assertThat(runLatency(BANK).count()).isZero();
    }

    @Test
    @DisplayName("a late leg's decision counts on the rematch series, never the item series")
    void rematchedCounts() {
        ReconciliationOutcomeMeters meters = meters();

        meters.rematched(PSP_ID, DecisionOutcome.MATCHED);
        meters.rematched(PSP_ID, DecisionOutcome.MATCHED);
        meters.rematched(BANK_ID, DecisionOutcome.PARKED);

        assertThat(rematch(PSP, DecisionOutcome.MATCHED).count()).isEqualTo(2.0d);
        assertThat(rematch(BANK, DecisionOutcome.PARKED).count()).isEqualTo(1.0d);
        assertThat(item(PSP, DecisionOutcome.MATCHED).count()).isZero();
    }

    @Test
    @DisplayName("a resolution counts by kind and outcome and records raised-to-resolved when"
            + " known")
    void resolvedCountsAndRecordsLatency() {
        ReconciliationOutcomeMeters meters = meters();

        meters.resolved(
                ResolutionKind.WRITE_OFF,
                ResolutionOutcome.APPROVED,
                Optional.of(Duration.ofHours(2)));
        meters.resolved(ResolutionKind.ACKNOWLEDGE, ResolutionOutcome.REJECTED, Optional.empty());

        assertThat(resolution(ResolutionKind.WRITE_OFF, ResolutionOutcome.APPROVED).count())
                .isEqualTo(1.0d);
        assertThat(resolutionLatency(ResolutionKind.WRITE_OFF).count()).isEqualTo(1L);
        assertThat(resolutionLatency(ResolutionKind.WRITE_OFF).totalTime(TimeUnit.HOURS))
                .isEqualTo(2.0d);
        assertThat(resolution(ResolutionKind.ACKNOWLEDGE, ResolutionOutcome.REJECTED).count())
                .isEqualTo(1.0d);
        assertThat(resolutionLatency(ResolutionKind.ACKNOWLEDGE).count())
                .as("no latency without a raise to measure from")
                .isZero();
    }

    @Test
    @DisplayName("an approval's adjustment counts by kind - a count, never an amount")
    void adjustedCounts() {
        ReconciliationOutcomeMeters meters = meters();

        meters.adjusted(ResolutionKind.TRANSFER_TO_ACCOUNT);

        assertThat(adjustment(ResolutionKind.TRANSFER_TO_ACCOUNT).count()).isEqualTo(1.0d);
        assertThat(adjustment(ResolutionKind.WRITE_OFF).count()).isZero();
    }

    @Test
    @DisplayName("a raised break counts by type and severity")
    void countRaisedCounts() {
        ReconciliationOutcomeMeters meters = meters();

        meters.countRaised(BreakType.MISSING_EXTERNAL, Severity.CRITICAL);

        assertThat(raised(BreakType.MISSING_EXTERNAL, Severity.CRITICAL).count())
                .isEqualTo(1.0d);
        assertThat(raised(BreakType.MISSING_EXTERNAL, Severity.HIGH).count()).isZero();
    }

    @Test
    @DisplayName("a source id the register does not name counts nothing and invents no series")
    void anUnknownSourceCountsNothing() {
        ReconciliationOutcomeMeters meters = meters();
        int seriesBefore = registry.getMeters().size();
        UUID stranger = UUID.randomUUID();

        meters.runCompleted(
                stranger, Map.of(DecisionOutcome.MATCHED, 5L), Optional.of(Duration.ofSeconds(1)));
        meters.rematched(stranger, DecisionOutcome.MATCHED);

        assertThat(everyCount()).isZero();
        assertThat(registry.getMeters())
                .as("no tag value is ever invented")
                .hasSize(seriesBefore);
    }

    @Test
    @DisplayName("a source lookup that fails never fails the reporting operation")
    void aFailingLookupNeverFailsTheOperation() {
        ReconciliationOutcomeMeters meters =
                meters(id -> {
                    throw new IllegalStateException("the register is unreadable");
                });

        assertThatCode(() -> {
                    meters.runCompleted(
                            PSP_ID, Map.of(DecisionOutcome.MATCHED, 1L), Optional.empty());
                    meters.rematched(PSP_ID, DecisionOutcome.MATCHED);
                })
                .doesNotThrowAnyException();
        assertThat(everyCount()).isZero();
    }

    // ------------------------------------------------------------------ after commit

    @Test
    @DisplayName("inside a transaction nothing is counted until it commits - then everything is")
    void countedOnlyAfterCommit() {
        ReconciliationOutcomeMeters meters = meters();
        TransactionSynchronizationManager.initSynchronization();

        reportOneOfEach(meters);

        assertThat(everyCount())
                .as("a fact inside an uncommitted transaction is not yet a fact")
                .isZero();
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered)
                .as("one synchronization holding the transaction's deferred counts")
                .hasSize(1);

        registered.forEach(TransactionSynchronization::afterCommit);

        assertThat(item(PSP, DecisionOutcome.MATCHED).count()).isEqualTo(2.0d);
        assertThat(runLatency(PSP).count()).isEqualTo(1L);
        assertThat(rematch(PSP, DecisionOutcome.MATCHED).count()).isEqualTo(1.0d);
        assertThat(resolution(ResolutionKind.WRITE_OFF, ResolutionOutcome.APPROVED).count())
                .isEqualTo(1.0d);
        assertThat(resolutionLatency(ResolutionKind.WRITE_OFF).count()).isEqualTo(1L);
        assertThat(adjustment(ResolutionKind.WRITE_OFF).count()).isEqualTo(1.0d);
        assertThat(raised(BreakType.AMOUNT_MISMATCH, Severity.HIGH).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a savepoint's rollback drops the counts deferred after its mark, and the"
            + " commit counts the rest - Spring never sees a rollback(savepoint)")
    void aSavepointRollbackDropsItsCounts() {
        ReconciliationOutcomeMeters meters = meters();
        TransactionSynchronizationManager.initSynchronization();

        meters.rematched(PSP_ID, DecisionOutcome.MATCHED);
        int mark = meters.countMark();
        assertThat(mark).as("one count deferred before the savepoint").isEqualTo(1);
        // The undone attempt: a raise and a rematch the savepoint's rollback took back.
        meters.countRaised(BreakType.AMOUNT_MISMATCH, Severity.HIGH);
        meters.rematched(PSP_ID, DecisionOutcome.PARKED);
        meters.discardCountsAfter(mark);
        // The containment that replaced it.
        meters.countRaised(BreakType.PROCESSING_ERROR, Severity.HIGH);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        assertThat(rematch(PSP, DecisionOutcome.MATCHED).count()).isEqualTo(1.0d);
        assertThat(rematch(PSP, DecisionOutcome.PARKED).count())
                .as("the undone attempt's decision").isZero();
        assertThat(raised(BreakType.AMOUNT_MISMATCH, Severity.HIGH).count())
                .as("the undone attempt's raise").isZero();
        assertThat(raised(BreakType.PROCESSING_ERROR, Severity.HIGH).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("outside a transaction the mark is zero and a discard drops nothing")
    void outsideATransactionTheMarkIsInert() {
        ReconciliationOutcomeMeters meters = meters();
        meters.rematched(PSP_ID, DecisionOutcome.MATCHED);
        assertThat(meters.countMark()).isZero();
        meters.discardCountsAfter(0);
        assertThat(rematch(PSP, DecisionOutcome.MATCHED).count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a rolled-back transaction's facts are never counted")
    void aRollbackCountsNothing() {
        ReconciliationOutcomeMeters meters = meters();
        TransactionSynchronizationManager.initSynchronization();

        reportOneOfEach(meters);
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        registered.forEach(sync -> {
            sync.beforeCompletion();
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        });
        TransactionSynchronizationManager.clearSynchronization();

        assertThat(everyCount())
                .as("a rolled-back chunk, rematch or decision is an alert about nothing")
                .isZero();
    }

    @Test
    @DisplayName("a stale approval's refusal is counted at once - nothing commits to count after")
    void staleRefusedIsImmediate() {
        ReconciliationOutcomeMeters meters = meters();
        TransactionSynchronizationManager.initSynchronization();

        meters.staleRefused(ResolutionKind.MANUAL_MATCH);

        assertThat(resolution(ResolutionKind.MANUAL_MATCH, ResolutionOutcome.STALE).count())
                .isEqualTo(1.0d);
        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .as("nothing deferred: the refusal's transaction rolls back")
                .isEmpty();
    }
}
