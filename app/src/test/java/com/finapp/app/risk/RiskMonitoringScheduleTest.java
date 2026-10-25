package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.risk.MonitoredEntry;
import com.finapp.risk.MonitoredLedger;
import com.finapp.risk.MonitoredTransaction;
import com.finapp.risk.MonitoredTransactionStore;
import com.finapp.risk.MonitoringIntake;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.MonitoringWatermarkStore;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskRating;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The monitoring schedule over fakes (`P13-TSK-019`; bnpl's {@code BnplOriginationScheduleTest} shape): the
 * configuration judged first; one tick takes batches while they come back full, up to its bound, and stops at a short
 * one or an unclaimed watermark; a failing batch is counted {@code finapp.risk.sweeper.claim.failed{kind=monitoring}}
 * and ends the tick. The transactions are a fake unit of work: the intake's state lives in the fakes. The profile leg
 * (`P13-TSK-007`): after the intake, due profiles one per transaction up to its bound, stopping when none is due; a
 * failing recompute counted the same way, ending the leg and leaving the intake's work standing.
 */
@DisplayName("the risk monitoring schedule (P13-TSK-019, P13-TSK-007)")
class RiskMonitoringScheduleTest {

    private static final IdGenerator IDS = new IdGenerator(
            Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC), new Random(19));

    @Test
    @DisplayName("the configuration is judged first: a non-positive poll or an empty tick refuse to build")
    void configurationJudged() {
        World world = new World(0);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new RiskMonitoringSchedule(
                world.intake(2), world.transactions.real, world.metrics, RiskFlowScope.NONE, Duration.ZERO, 10));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new RiskMonitoringSchedule(
                world.intake(2), world.transactions.real, world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 0));
    }

    @Test
    @DisplayName("one tick drains full batches up to its bound and stops at the short one; the next tick finishes")
    void aTickDrainsBoundedBatches() {
        World world = new World(7);
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), world.transactions.real, world.metrics,
                RiskFlowScope.NONE, Duration.ofSeconds(5), 3);
        assertThat(schedule.sweepOnce()).as("three full batches of two, the bound").isEqualTo(6);
        assertThat(world.transactions.began).isEqualTo(3);
        assertThat(schedule.sweepOnce()).as("the short batch of one, then stop").isEqualTo(1);
        assertThat(world.transactions.began).isEqualTo(4);
        assertThat(schedule.sweepOnce()).as("nothing left: one empty batch").isZero();
        assertThat(world.born).hasSize(7);
    }

    @Test
    @DisplayName("an unclaimed watermark ends the tick at once - another sweeper holds it")
    void anUnclaimedWatermarkEndsTheTick() {
        World world = new World(3);
        world.watermarks.claimable = false;
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), world.transactions.real, world.metrics,
                RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        assertThat(schedule.sweepOnce()).isZero();
        assertThat(world.transactions.began).isEqualTo(1);
    }

    @Test
    @DisplayName("a failing batch is counted claim.failed{kind=monitoring}, ends the tick and bears nothing")
    void aFailingBatchIsCounted() {
        World world = new World(3);
        world.ledger.failing = true;
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), world.transactions.real, world.metrics,
                RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        assertThat(schedule.sweepOnce()).isZero();
        assertThat(world.registry.get(RiskMonitoringMetrics.CLAIM_FAILED).tag("kind", "monitoring").counter().count())
                .isEqualTo(1.0);
        assertThat(world.born).isEmpty();
        assertThat(world.transactions.began).isEqualTo(1);
        assertThat(schedule.isRunning()).isFalse();
    }

    @Test
    @DisplayName("the profile leg: after the intake, one due profile per transaction up to its bound; it stops when none"
            + " is due; a leg with no bound refuses to build")
    void theProfileLegRecomputesDueProfiles() {
        World world = new World(1);
        ScriptedLeg leg = new ScriptedLeg(5);
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), leg, 3, world.transactions.real,
                world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        assertThat(schedule.sweepOnce()).as("the intake's count, as before").isEqualTo(1);
        assertThat(leg.recomputed).as("three of the five due - the bound").isEqualTo(3);
        assertThat(world.transactions.began).as("one intake batch, then one transaction per profile").isEqualTo(4);
        schedule.sweepOnce();
        assertThat(leg.recomputed).as("the last two, then none due").isEqualTo(5);
        assertThat(world.transactions.began).isEqualTo(4 + 1 + 3);
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new RiskMonitoringSchedule(
                world.intake(2), leg, 0, world.transactions.real, world.metrics, RiskFlowScope.NONE,
                Duration.ofSeconds(5), 10));
    }

    @Test
    @DisplayName("a party whose recompute fails is counted, its permit deferred in a transaction of its own, and the leg"
            + " goes on to the next due party - one failing party never stalls the leg")
    void aFailingPartyIsDeferredAndTheLegGoesOn() {
        World world = new World(0);
        ScriptedLeg leg = new ScriptedLeg(4);
        leg.failingParties = 1;
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), leg, 10, world.transactions.real,
                world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        schedule.sweepOnce();
        assertThat(leg.deferred).as("the failing party's permit deferred").hasSize(1);
        assertThat(leg.recomputed).as("the three others recomputed after it").isEqualTo(3);
        assertThat(world.registry.get(RiskMonitoringMetrics.CLAIM_FAILED).tag("kind", "monitoring").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a failing claim is counted claim.failed{kind=monitoring} and ends the leg; the intake's batch stands")
    void aFailingRecomputeIsCounted() {
        World world = new World(1);
        ScriptedLeg leg = new ScriptedLeg(5);
        leg.failing = true;
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), leg, 3, world.transactions.real,
                world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        assertThat(schedule.sweepOnce()).isEqualTo(1);
        assertThat(world.born).hasSize(1);
        assertThat(leg.recomputed).isZero();
        assertThat(world.registry.get(RiskMonitoringMetrics.CLAIM_FAILED).tag("kind", "monitoring").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the override action leg (P13-TSK-012) runs last, as the platform, after the intake and the profiles;"
            + " a leg that throws is counted claim.failed{kind=monitoring} and the tick's earlier work stands")
    void theOverrideActionLegRunsLastAsThePlatform() {
        World world = new World(1);
        ScriptedLeg profiles = new ScriptedLeg(1);
        List<String> driven = new ArrayList<>();
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), profiles, 3, () -> {
            driven.add(com.finapp.platform.security.SecurityContext.require().type().name() + " after "
                    + profiles.recomputed);
            return 1;
        }, world.transactions.real, world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        assertThat(schedule.sweepOnce()).isEqualTo(1);
        assertThat(driven).containsExactly("SYSTEM after 1");

        RiskMonitoringSchedule failing = new RiskMonitoringSchedule(world.intake(2), RiskMonitoringSchedule.ProfileLeg.NONE,
                0, () -> {
                    throw new IllegalStateException("the claim failed");
                }, world.transactions.real, world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        failing.sweepOnce();
        assertThat(world.registry.get(RiskMonitoringMetrics.CLAIM_FAILED).tag("kind", "monitoring").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the case leg (P13-TSK-016) runs as the platform after the profiles and before the override actions;"
            + " a case leg that throws is counted claim.failed{kind=monitoring} and the action leg still runs")
    void theCaseLegRunsBetweenTheProfilesAndTheActions() {
        World world = new World(1);
        ScriptedLeg profiles = new ScriptedLeg(1);
        List<String> driven = new ArrayList<>();
        RiskMonitoringSchedule schedule = new RiskMonitoringSchedule(world.intake(2), profiles, 3, () -> {
            driven.add("cases as " + com.finapp.platform.security.SecurityContext.require().type().name() + " after "
                    + profiles.recomputed);
            return 2;
        }, () -> {
            driven.add("actions");
            return 0;
        }, world.transactions.real, world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        schedule.sweepOnce();
        assertThat(driven).containsExactly("cases as SYSTEM after 1", "actions");

        List<String> after = new ArrayList<>();
        RiskMonitoringSchedule failing = new RiskMonitoringSchedule(world.intake(2),
                RiskMonitoringSchedule.ProfileLeg.NONE, 0, () -> {
                    throw new IllegalStateException("the queue read failed");
                }, () -> {
                    after.add("actions");
                    return 0;
                }, world.transactions.real, world.metrics, RiskFlowScope.NONE, Duration.ofSeconds(5), 10);
        failing.sweepOnce();
        assertThat(after).containsExactly("actions");
        assertThat(world.registry.get(RiskMonitoringMetrics.CLAIM_FAILED).tag("kind", "monitoring").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the meters are registered eagerly, with bounded tags and no id")
    void metersAreEager() {
        World world = new World(0);
        assertThat(world.registry.find(RiskMonitoringMetrics.LAG).tag("source", "ledger.journal").gauge()).isNotNull();
        assertThat(world.registry.find(RiskMonitoringMetrics.UNCLASSIFIED).gauge()).isNotNull();
        assertThat(world.registry.find(RiskMonitoringMetrics.CLAIM_FAILED).tag("kind", "monitoring").counter())
                .isNotNull();
    }

    // ------------------------------------------------------------------ the fakes

    /**
     * The profile leg as a fake: {@code due} profiles waiting, each recompute taking one; the claim itself failing; or
     * the first {@code failingParties} recomputes failing for their party, which a deferral moves out of the way.
     */
    private static final class ScriptedLeg implements RiskMonitoringSchedule.ProfileLeg {
        private int due;
        int recomputed;
        boolean failing;
        int failingParties;
        final List<UUID> deferred = new ArrayList<>();

        ScriptedLeg(int due) {
            this.due = due;
        }

        @Override
        public Optional<ProfileRecompute.Outcome> recomputeNextDue(Connection unitOfWork) {
            if (failing) {
                throw new IllegalStateException("the claim failed");
            }
            if (due == 0) {
                return Optional.empty();
            }
            UUID party = IDS.next();
            if (failingParties > 0) {
                failingParties--;
                due--;
                throw new ProfileRecompute.RecomputeFailed(party, new IllegalStateException("the engine refused"));
            }
            due--;
            recomputed++;
            return Optional.of(new ProfileRecompute.Outcome(party, ProfileRecompute.Kind.CHANGED, 1,
                    Optional.of(RiskRating.LOW)));
        }

        @Override
        public void defer(Connection unitOfWork, UUID partyId) {
            deferred.add(partyId);
        }
    }

    private static final class World {
        final FakeLedger ledger = new FakeLedger();
        final FakeWatermarks watermarks = new FakeWatermarks();
        final Set<UUID> born = new HashSet<>();
        final MonitoredTransactionStore store = new MonitoredTransactionStore() {
            @Override
            public int insertAbsent(Connection unitOfWork, List<MonitoredTransaction> rows) {
                int count = 0;
                for (MonitoredTransaction row : rows) {
                    if (born.add(row.journalEntryId())) {
                        count++;
                    }
                }
                return count;
            }

            @Override
            public long countUnclassified(Connection unitOfWork) {
                return 0;
            }

            @Override
            public long count(Connection unitOfWork) {
                return born.size();
            }
        };
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final RiskMonitoringMetrics metrics = new RiskMonitoringMetrics(watermarks, store, ledger,
                () -> {
                    throw new java.sql.SQLException("no database in this test");
                }, Clock.systemUTC(), registry);
        final FakeTransactions transactions = new FakeTransactions();

        World(int entries) {
            for (int i = 0; i < entries; i++) {
                ledger.plant();
            }
        }

        MonitoringIntake intake(int batch) {
            return new MonitoringIntake(ledger, watermarks, store, IDS, Duration.ofSeconds(60), batch,
                    Duration.ofSeconds(5));
        }
    }

    /**
     * The unit of work as a fake of the port: each call runs the work at once on a connection that answers nothing -
     * the fakes hold the state - and {@code began} counts the transactions the schedule opened. No data source is
     * named, so the suite stays in the hermetic tier it belongs to (the taxonomy keys on acquisition).
     */
    private static final class FakeTransactions implements RiskTransactions {
        private static final Connection NO_CONNECTION = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("the fakes hold their state in memory");
                });
        int began;
        final RiskTransactions real = this;

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            began++;
            return work.apply(NO_CONNECTION);
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            return work.apply(NO_CONNECTION);
        }
    }

    private static final class FakeLedger implements MonitoredLedger {
        final List<MonitoredEntry> journal = new ArrayList<>();
        boolean failing;

        void plant() {
            UUID id = IDS.next();
            journal.add(new MonitoredEntry(id, MonitoringWatermark.sequenceOf(id), MonitoredEntry.EntryType.POSTING,
                    "transfer:" + UUID.randomUUID(), Optional.empty(), Instant.parse("2026-10-23T09:00:00Z"),
                    LocalDate.of(2026, 10, 23), List.of(
                            new MonitoredEntry.Line(UUID.randomUUID(), "CUSTOMER", Optional.of(UUID.randomUUID()),
                                    "CUSTOMER_WALLET", MonitoredEntry.Side.DEBIT, 10_00, 2, CurrencyCode.of("EUR")),
                            new MonitoredEntry.Line(UUID.randomUUID(), "OPERATIONAL", Optional.empty(), "CASH_AT_BANK",
                                    MonitoredEntry.Side.CREDIT, 10_00, 2, CurrencyCode.of("EUR")))));
        }

        @Override
        public List<MonitoredEntry> entriesAfter(Connection unitOfWork, UUID afterEntryId, Duration settleHorizon,
                int limit) {
            if (failing) {
                throw new IllegalStateException("the journal is unreadable");
            }
            return journal.stream()
                    .filter(entry -> after(entry.journalEntryId(), afterEntryId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long entriesBeyond(Connection unitOfWork, UUID afterEntryId, long cap) {
            return journal.stream().filter(entry -> after(entry.journalEntryId(), afterEntryId)).count();
        }

        private static boolean after(UUID id, UUID position) {
            int most = Long.compareUnsigned(id.getMostSignificantBits(), position.getMostSignificantBits());
            return most != 0 ? most > 0
                    : Long.compareUnsigned(id.getLeastSignificantBits(), position.getLeastSignificantBits()) > 0;
        }
    }

    private static final class FakeWatermarks implements MonitoringWatermarkStore {
        MonitoringWatermark row = new MonitoringWatermark(MonitoringWatermark.LEDGER_JOURNAL, MonitoringWatermark.FLOOR,
                0, Instant.EPOCH);
        boolean claimable = true;

        @Override
        public Optional<MonitoringWatermark> claimDue(Connection unitOfWork, String source) {
            return claimable ? Optional.of(row) : Optional.empty();
        }

        @Override
        public Optional<MonitoringWatermark> lock(Connection unitOfWork, String source) {
            return Optional.of(row);
        }

        @Override
        public Optional<MonitoringWatermark> read(Connection unitOfWork, String source) {
            return Optional.of(row);
        }

        @Override
        public void move(Connection unitOfWork, String source, UUID lastEntryId, Duration permitDelay) {
            row = new MonitoringWatermark(source, lastEntryId, MonitoringWatermark.sequenceOf(lastEntryId),
                    Instant.EPOCH.plus(permitDelay));
        }

        @Override
        public boolean hint(Connection unitOfWork, String source) {
            return false;
        }
    }
}
