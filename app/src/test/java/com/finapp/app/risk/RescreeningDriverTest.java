package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActiveParties;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.PartyRescreening;
import com.finapp.risk.RescreeningRun;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSignalStore;
import com.finapp.risk.RiskSignals;
import com.finapp.risk.ScreeningList;
import com.finapp.risk.ScreeningListVersion;
import com.finapp.risk.ScreeningListVersions;
import com.finapp.risk.SignalKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.testing.InMemoryRescreeningPermitStore;
import com.finapp.risk.testing.InMemoryScreeningListStore;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rescreening leg's driver, hermetic (`P13-TSK-022`; {@code DESIGN-022} D3): one tick enqueues an activated
 * version's permits in bounded batches and then concludes the due permits, each step its own transaction, kyc asked on no
 * transaction under the activation's correlation and <strong>as the platform</strong>; the bounds per tick hold; a failing
 * step is counted {@code claim.failed{kind=rescreening}} and never thrown into the monitoring sweep; the leg rides
 * {@code RiskMonitoringSchedule}, last in its tick. The counts under ten instances are {@code RescreeningDatabaseTest}'s
 * (written, owed - C5); the domain's are {@code RescreeningRunTest}'s.
 */
@DisplayName("the rescreening driver: enqueue then conclude, as the platform, bounded, never throwing (P13-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RescreeningDriverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(RescreeningDriverTest.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    private final InMemoryScreeningListStore lists = new InMemoryScreeningListStore();
    private final InMemoryRescreeningPermitStore permits = new InMemoryRescreeningPermitStore(lists);
    private final TreeSet<UUID> active = new TreeSet<>();
    private final List<String> asked = new ArrayList<>();
    private final List<RiskSignal> signals = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RescreeningMetrics metrics = new RescreeningMetrics(permits, () -> {
        throw new SQLException("no database in this suite");
    }, CLOCK, registry);
    private final Transactions transactions = new Transactions();
    private boolean failClaims;

    private final PartyRescreening kyc = (party, list, key) -> {
        Actor actor = SecurityContext.require();
        asked.add(actor.type() + "|" + CorrelationContext.current().orElseThrow().correlationId().value() + "|" + key);
        return new PartyRescreening.Screened(UUID.randomUUID(), false, lists.now);
    };

    private final RescreeningRun run = new RescreeningRun(lists, permits, this::page, kyc,
            new RiskSignals(new Signals(), (OutboxWriter<Connection>) (uow, envelope, payload, mediaType) -> { },
                    IDS, CLOCK),
            new RiskPolicies(unusedPolicies()), AlertRaiser.NONE, IDS, metrics, RescreeningRun.Bounds.DEFAULT);

    @Test
    @DisplayName("one tick: the enqueue's batches, then the due permits - each concluded once, kyc asked as the platform"
            + " under the activation's correlation and the permit's key")
    void oneTickEnqueuesThenConcludes() {
        for (int i = 0; i < 5; i++) {
            active.add(UUID.randomUUID());
        }
        ScreeningListVersion version = activated();
        RescreeningDriver driver = driver(2, 10, 50);
        RescreeningDriver.Tick tick = driver.driveDue();
        assertThat(tick).isEqualTo(new RescreeningDriver.Tick(5, 5));
        assertThat(lists.get(version.id()).enqueueCompletedAt()).isPresent();
        assertThat(asked).hasSize(5).allMatch(line -> line.startsWith("SYSTEM|" + version.correlationId() + "|"
                + PartyRescreening.KEY_PREFIX));
        assertThat(signals).hasSize(5).allMatch(signal -> signal.kind() == SignalKind.SCREENING_CLEARED);
        assertThat(registry.get(RescreeningMetrics.RESCREENING).tag("outcome", "cleared").counter().count())
                .isEqualTo(5.0);
        assertThat(driver.driveDue()).as("nothing due").isEqualTo(RescreeningDriver.Tick.NOTHING);
    }

    @Test
    @DisplayName("the bounds per tick: at most enqueue-batches-per-tick batches and permits-per-tick permits; the rest the"
            + " next tick's")
    void theBoundsPerTick() {
        for (int i = 0; i < 7; i++) {
            active.add(UUID.randomUUID());
        }
        activated();
        RescreeningDriver driver = driver(3, 2, 4);
        assertThat(driver.driveDue()).isEqualTo(new RescreeningDriver.Tick(6, 4));
        assertThat(driver.driveDue()).isEqualTo(new RescreeningDriver.Tick(1, 3));
        assertThatThrownBy(() -> driver(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RescreeningDriver(run, transactions, metrics, RiskFlowScope.NONE, Duration.ZERO, 1,
                1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a failing step is counted claim.failed{kind=rescreening} and ends its loop - the leg never throws into"
            + " the sweep; the permits stand for a later tick")
    void aFailingStepIsCountedNeverThrown() {
        active.add(UUID.randomUUID());
        activated();
        failClaims = true;
        RescreeningDriver driver = driver(10, 1, 10);
        assertThat(driver.driveDue()).isEqualTo(RescreeningDriver.Tick.NOTHING);
        assertThat(registry.get(RescreeningMetrics.CLAIM_FAILED).tag("kind", RescreeningMetrics.KIND).counter().count())
                .isEqualTo(2.0);
        failClaims = false;
        assertThat(driver.driveDue()).isEqualTo(new RescreeningDriver.Tick(1, 1));
    }

    @Test
    @DisplayName("the leg rides RiskMonitoringSchedule: RescreeningLeg.NONE for the earlier suites, the driver in"
            + " production - a leg, not a scheduler")
    void theLegRidesTheMonitoringSchedule() {
        assertThat(RiskMonitoringSchedule.RescreeningLeg.NONE.driveDue()).isZero();
        assertThat(registry.find(RescreeningMetrics.BACKLOG).gauge()).isNotNull();
        assertThat(registry.get(RescreeningMetrics.BACKLOG).gauge().value()).as("unreadable is NaN, never zero").isNaN();
    }

    // ------------------------------------------------------------------ the world

    private RescreeningDriver driver(int enqueueBatch, int enqueueBatchesPerTick, int permitsPerTick) {
        return new RescreeningDriver(run, transactions, metrics, RiskFlowScope.NONE, Duration.ofMinutes(2), enqueueBatch,
                enqueueBatchesPerTick, permitsPerTick);
    }

    private ScreeningListVersion activated() {
        ScreeningListVersions versions = new ScreeningListVersions(lists, (AuditWriter<Connection>) (uow, record) -> { },
                IDS, CLOCK);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("rec")))) {
            ScreeningListVersion recorded = versions.record(UOW, ScreeningList.SANCTIONS, "v1", "a new edition",
                    new Actor("officer-1", ActorType.CUSTOMER), CorrelationId.of("rec")).version();
            return versions.activate(UOW, recorded.id(), "in force", new Actor("officer-2", ActorType.CUSTOMER),
                    CorrelationId.of("activation-p13t22")).version();
        }
    }

    private List<UUID> page(Connection unitOfWork, Optional<UUID> after, int limit) {
        return active.stream().filter(party -> after.isEmpty() || party.compareTo(after.get()) > 0).limit(limit).toList();
    }

    private static RiskPolicyStore unusedPolicies() {
        return (RiskPolicyStore) Proxy.newProxyInstance(RiskPolicyStore.class.getClassLoader(),
                new Class<?>[] {RiskPolicyStore.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException("a clear pins no policy");
                });
    }

    /** One transaction per step: the claims' row locks released at its end; a claim fails while asked to. */
    private final class Transactions implements RiskTransactions {

        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try {
                if (failClaims) {
                    // The statement fails before anything is written - the batch or the claim rolled back whole.
                    throw new IllegalStateException("the database refused the statement");
                }
                return work.apply(UOW);
            } finally {
                lists.release();
                permits.release();
            }
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            return work.apply(UOW);
        }
    }

    /** risk_signal in memory: born once per source event. */
    private final class Signals implements RiskSignalStore {

        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            if (signals.stream().anyMatch(born -> born.source().id().equals(signal.source().id()))) {
                return false;
            }
            signals.add(signal);
            return true;
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            throw new UnsupportedOperationException();
        }
    }
}
