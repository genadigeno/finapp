package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.finapp.app.telemetry.MerchantMeters;
import com.finapp.app.telemetry.SweepMeters;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.merchant.JdbcMerchantPayoutStore;
import com.finapp.merchant.JdbcPayoutEvidenceStore;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantTransactionRunner;
import com.finapp.merchant.PayoutAnswer;
import com.finapp.merchant.PayoutEvidenceCipher;
import com.finapp.merchant.PayoutProvider;
import com.finapp.merchant.PayoutQueryAnswer;
import com.finapp.merchant.PayoutReference;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout resolution schedule's own mechanics (`P6-TSK-012`, counting added by
 * `P6-TSK-013`): ticks invoke the sweep, a throwing tick is logged and the schedule continues,
 * stop stops, and the meter reads only the tick's own tally. The sweep's correctness is the
 * database suite's; what only this test can see is the lifecycle.
 *
 * <p>Hermetic: the resolution is a <strong>real</strong> {@link MerchantPayoutResolution} whose
 * candidate read is intercepted by a {@link MerchantTransactionRunner} that counts and answers
 * with an empty list — the {@code PaymentSweeperScheduleTest} shape — so the schedule drives the
 * real type's entry point, and nothing behind that read is ever reached.
 */
@DisplayName("the merchant payout resolution schedule (P6-TSK-012, P6-TSK-013)")
class MerchantPayoutResolutionScheduleTest {

    @Test
    @DisplayName("ticks invoke the sweep on the configured delay, and stop stops")
    void ticksInvokeTheSweep() {
        AtomicInteger ticks = new AtomicInteger();
        MerchantPayoutResolutionSchedule schedule =
                new MerchantPayoutResolutionSchedule(
                        ticking(ticks, false), meters(), Duration.ofMillis(50), SweepMeters.Sweep.NONE);
        schedule.start();
        try {
            assertThat(schedule.isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() >= 2);
        } finally {
            schedule.stop();
        }
        assertThat(schedule.isRunning()).isFalse();
        int after = ticks.get();
        await().during(Duration.ofMillis(200)).until(() -> ticks.get() == after);
    }

    @Test
    @DisplayName("a throwing tick is logged and the schedule continues - one poisoned tick must"
            + " not end the resolution of every ambiguous payout")
    void aThrowingTickDoesNotEndResolution() {
        AtomicInteger ticks = new AtomicInteger();
        MerchantPayoutResolutionSchedule schedule =
                new MerchantPayoutResolutionSchedule(
                        ticking(ticks, true), meters(), Duration.ofMillis(50), SweepMeters.Sweep.NONE);
        schedule.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() >= 3);
        } finally {
            schedule.stop();
        }
    }

    @Test
    @DisplayName("the meter reads the tick's OWN tally: ticks that judged nothing count nothing,"
            + " and every judgement's series exists at zero")
    void theMeterCountsTheTicksOwnJudgementsOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger ticks = new AtomicInteger();
        MerchantPayoutResolutionSchedule schedule =
                new MerchantPayoutResolutionSchedule(
                        ticking(ticks, false), new MerchantMeters(registry), Duration.ofMillis(20), SweepMeters.Sweep.NONE);
        schedule.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() >= 3);
        } finally {
            schedule.stop();
        }
        // A meter fed from `candidates` or from the tick count would report throughput for
        // payouts other instances had already resolved (P5-TSK-017's rule).
        assertThat(registry.find("finapp.merchant.payout").counters())
                .hasSize(3)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("a non-positive interval is refused at construction")
    void aNonPositiveIntervalIsRefused() {
        AtomicInteger ticks = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                new MerchantPayoutResolutionSchedule(
                                        ticking(ticks, false), meters(), Duration.ZERO, SweepMeters.Sweep.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new MerchantPayoutResolutionSchedule(
                                        ticking(ticks, false), meters(), Duration.ofMillis(-1), SweepMeters.Sweep.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a dispatched bound of zero is refused: it would let the sweep conclude"
            + " NEVER_RECEIVED of a send still in flight (ADR-0057 section 4)")
    void aZeroDispatchedBoundIsRefused() {
        AtomicInteger ticks = new AtomicInteger();
        // P6-DOC-001: only a negative bound was refused, and ADR-0057 names the bound as the
        // safety of the never-received conclusion.
        assertThatThrownBy(() -> ticking(ticks, false, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dispatchedAge");
        assertThatThrownBy(() -> ticking(ticks, false, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    /**
     * `P16-TSK-017` (item 42 (i); runbook §11.39): the real sweep over a scripted connection. A payout whose id no domain writer
     * could mint, at the head of the listing, is skipped, recorded under the payout leg and counted, never judged; the tick ends
     * normally, so nothing pages as a failed tick; and a tick that does fail whole is counted where its WARN line is written.
     */
    @Test
    @DisplayName("a corrupt payout id at the head is skipped, recorded and counted, never judged, and the tick ends; a tick that"
            + " fails whole is counted (P16-TSK-017)")
    void aPoisonedHeadIsSkippedRecordedAndCounted() {
        java.util.UUID corrupt = com.finapp.platform.testing.ScriptedListingConnection.versionFour();
        com.finapp.platform.testing.ScriptedListingConnection jdbc =
                new com.finapp.platform.testing.ScriptedListingConnection(java.time.Instant.parse("2026-11-13T10:00:00Z"))
                        .answering("f.leg = 'PAYOUT'", List.of(com.finapp.platform.testing.ScriptedListingConnection.row("id", corrupt)));
        MerchantTransactionRunner runner = new MerchantTransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return work.apply(jdbc.connection());
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SweepMeters sweepMeters = new SweepMeters(registry);
        MerchantPayoutResolutionSchedule schedule = new MerchantPayoutResolutionSchedule(
                resolution(runner, Duration.ofMinutes(10)), meters(), Duration.ofSeconds(30), sweepMeters.payouts());
        schedule.sweepQuietly();

        List<com.finapp.platform.testing.ScriptedListingConnection.Sent> recorded =
                jdbc.sentContaining("INSERT INTO merchant.sweep_row_failure");
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).parameter(1)).isEqualTo(corrupt);
        assertThat(recorded.get(0).parameter(3)).isEqualTo("UnreadableRow");
        assertThat(jdbc.sentContaining("UPDATE merchant.merchant_payout")).as("never judged").isEmpty();
        assertThat(registry.get("finapp.merchant.payout.sweep.row.unreadable").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finapp.merchant.payout.sweep.tick.failed").counter().count()).isZero();

        AtomicInteger ticks = new AtomicInteger();
        new MerchantPayoutResolutionSchedule(ticking(ticks, true), meters(), Duration.ofSeconds(30), sweepMeters.payouts())
                .sweepQuietly();
        assertThat(registry.get("finapp.merchant.payout.sweep.tick.failed").counter().count()).isEqualTo(1.0);
    }

    private static MerchantMeters meters() {
        return new MerchantMeters(new SimpleMeterRegistry());
    }

    /**
     * A real resolution whose {@code sweep()} counts and optionally throws: the counting rides
     * in the runner that serves the candidate read — the first thing every sweep does — and the
     * collaborators behind it are real types that are never reached.
     */
    private static MerchantPayoutResolution ticking(AtomicInteger ticks, boolean throwing) {
        return ticking(ticks, throwing, Duration.ofMinutes(10));
    }

    private static MerchantPayoutResolution ticking(
            AtomicInteger ticks, boolean throwing, Duration dispatchedAge) {
        MerchantTransactionRunner runner =
                new MerchantTransactionRunner() {
                    @Override
                    public <R> R inTransaction(Function<Connection, R> work) {
                        ticks.incrementAndGet();
                        if (throwing) {
                            throw new IllegalStateException("tick made to fail");
                        }
                        @SuppressWarnings("unchecked")
                        R empty = (R) com.finapp.platform.persistence.SweepListing.of(List.of());
                        return empty;
                    }
                };
        return resolution(runner, dispatchedAge);
    }

    /** The production construction over {@code runner}: the collaborators behind the candidate read are real types. */
    private static MerchantPayoutResolution resolution(MerchantTransactionRunner runner, Duration dispatchedAge) {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        Clock clock = Clock.systemUTC();
        JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
        MerchantPayoutOutcomes outcomes =
                new MerchantPayoutOutcomes(
                        new JdbcMerchantPayoutStore(),
                        new HoldService(
                                accounts,
                                new JdbcBalanceDerivation(),
                                new JdbcHoldStore(),
                                new JdbcBalanceProjection(),
                                (uow, record) -> {},
                                (uow, envelope, payload, mediaType) -> {},
                                ids,
                                clock),
                        new PostingService(
                                new IdempotentExecutor(
                                        new JdbcIdempotencyRecordStore(),
                                        clock,
                                        Duration.ofDays(1),
                                        Duration.ofMinutes(5)),
                                new JdbcJournalEntryStore(ids),
                                (uow, record) -> {},
                                new JdbcOutboxWriter(),
                                new JdbcBalanceProjection(),
                                ids,
                                clock,
                                PostingObserver.NONE),
                        new ChartOfAccounts<>(accounts),
                        accounts,
                        (uow, record) -> {},
                        (uow, envelope, payload, mediaType) -> {},
                        ids,
                        clock,
                        // No payout resolves in a schedule test (the runner answers empty):
                        // an opening reaching here is a harness defect (P8-TSK-005).
                        (uow, opening) -> {
                            throw new AssertionError("no payout completes in this suite");
                        });
        return new MerchantPayoutResolution(
                runner,
                new JdbcMerchantPayoutStore(),
                new NoProvider(),
                outcomes,
                new JdbcPayoutEvidenceStore(
                        new PayoutEvidenceCipher(new byte[32], 1, new SecureRandom()), ids),
                ids,
                clock,
                dispatchedAge,
                Duration.ofMinutes(1),
                50);
    }

    /** Never reached: the runner intercepts before any payout can be sent or queried. */
    private static final class NoProvider implements PayoutProvider {
        @Override
        public PayoutAnswer dispatch(PayoutRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PayoutQueryAnswer query(PayoutReference ours) {
            throw new UnsupportedOperationException();
        }
    }
}
