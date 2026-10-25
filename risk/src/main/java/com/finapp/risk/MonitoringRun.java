package com.finapp.risk;

import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * T-mon's rule leg over one claim (`P13-TSK-020`; {@code PHASE_13_PLAN.md} sections 7.1 T-mon, 7.2, 12.7; ADR-0111
 * decision 3; {@code DESIGN-020} D1, D2, D9, D10; {@code INV-RSK-06}, {@code INV-RSK-09}) - the
 * {@link MonitoringIntake.BatchLeg} the schedule hands the intake, run on the claim's own unit of work after the batch's
 * rows are inserted and before the watermark advances.
 *
 * <h2>One claim, one transaction</h2>
 *
 * <p>The claim's commit is the only durable record that its movements were seen; a rule leg committed apart would lose
 * a batch's windows on a crash between the two (no row records which parties were judged, and the profile's permit
 * re-judges only today's). So the rows, the alerts, the cases, the profile versions and the advance commit together or
 * not at all, and a crash, a rollback or a reprocessing re-runs the leg over the same rows into the same keys.
 *
 * <h2>The order of the locks</h2>
 *
 * <p>The claim holds the watermark (R7). The leg pins the {@code MONITORING} version {@code FOR SHARE} (R1 -
 * {@link RiskPolicies#active}); with none {@code ACTIVE} it judges nothing, enrols no head and counts each party
 * {@code NOT_JUDGED}. Then the parties the rows touch - each row's customer account subject and customer counterparty,
 * placed through {@link MonitoredParties} (plain reads) - in ascending order, each under a JDBC savepoint of its own:
 * {@link ProfileRecompute#monitor} takes its head {@code FOR UPDATE} (R8), raises its alerts (R5 births, then the folds
 * R4 → R5) and writes its version. Nobody holding a head, a case or an alert ever waits on the watermark (T-sig's hint
 * is {@code SKIP LOCKED}; the reprocessing door holds no risk row), and every other head taker holds one head at most -
 * so the claim's ascending heads close no cycle.
 *
 * <p><strong>A party that throws</strong> is rolled back to its savepoint alone - nothing of it written - counted
 * {@code FAILED} with its cause's class, and the run goes on: one party's poison never stalls the journal. Its today's
 * windows are re-judged by its profile permit; an earlier window by reprocessing - the recorded bound. An account no
 * party can be placed behind is counted and unjudged.
 */
public final class MonitoringRun implements MonitoringIntake.BatchLeg {

    /** A party's leg, isolated: the work, and its undoing alone when it throws. */
    @FunctionalInterface
    public interface Isolation {

        /** JDBC savepoints on the claim's connection. */
        Isolation SAVEPOINTS = MonitoringRun::inSavepoint;

        /** Runs {@code work} so that its throwing undoes its writes alone, then rethrows. */
        <T> T alone(Connection unitOfWork, Supplier<T> work);
    }

    /**
     * A party's writes could not be isolated or undone alone - its savepoint would not open, roll back or release - so
     * the claim itself must fail whole: never counted as one party's failure, which would commit what it could not undo.
     */
    public static final class IsolationBroken extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        IsolationBroken(RiskStorageException cause) {
            super("a party's leg could not be isolated: the claim fails whole", cause);
        }
    }

    /** What one run did. */
    public record Judged(int judged, int failed, int notJudged, int unplaced) {

        /** Nothing touched. */
        public static final Judged NOTHING = new Judged(0, 0, 0, 0);
    }

    private final RiskPolicies policies;
    private final MonitoredParties parties;
    private final ProfileRecompute recompute;
    private final IdGenerator ids;
    private final Clock clock;
    private final Spans spans;
    private final MonitoringObserver observer;
    private final Isolation isolation;

    public MonitoringRun(RiskPolicies policies, MonitoredParties parties, ProfileRecompute recompute, IdGenerator ids,
            Clock clock, Spans spans, MonitoringObserver observer) {
        this(policies, parties, recompute, ids, clock, spans, observer, Isolation.SAVEPOINTS);
    }

    /** @param isolation the party's isolation - {@link Isolation#SAVEPOINTS} in production; a suite's own otherwise */
    public MonitoringRun(RiskPolicies policies, MonitoredParties parties, ProfileRecompute recompute, IdGenerator ids,
            Clock clock, Spans spans, MonitoringObserver observer, Isolation isolation) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.recompute = Objects.requireNonNull(recompute, "recompute");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.spans = Objects.requireNonNull(spans, "spans");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.isolation = Objects.requireNonNull(isolation, "isolation");
    }

    @Override
    public void onBatch(Connection unitOfWork, List<MonitoredTransaction> rows) {
        judge(unitOfWork, rows);
    }

    /** The rule leg over {@code rows} on the claim's unit of work - see the class. */
    public Judged judge(Connection unitOfWork, List<MonitoredTransaction> rows) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            return Judged.NOTHING;
        }
        Optional<RiskPolicyVersion> pinned = policies.active(unitOfWork, RiskOperation.MONITORING);

        // The touched parties, each with its movements as it sees them - placed once per account.
        Map<UUID, Optional<UUID>> placed = new HashMap<>();
        Map<UUID, Set<UUID>> accountsOf = new TreeMap<>();
        Set<UUID> unplaced = new LinkedHashSet<>();
        for (MonitoredTransaction row : rows) {
            if (!MonitoredMovement.COUNTED.contains(row.kind())) {
                continue;
            }
            for (Optional<UUID> account : List.of(MonitoredMovement.subjectAccount(row),
                    MonitoredMovement.counterpartyAccount(row))) {
                account.ifPresent(id -> {
                    Optional<UUID> party = placed.computeIfAbsent(id, a -> parties.partyOfAccount(unitOfWork, a));
                    if (party.isPresent()) {
                        accountsOf.computeIfAbsent(party.get(), p -> new LinkedHashSet<>()).add(id);
                    } else {
                        unplaced.add(id);
                    }
                });
            }
        }
        if (!unplaced.isEmpty()) {
            observer.unplaced(unplaced.size());
        }
        if (pinned.isEmpty()) {
            accountsOf.keySet().forEach(party -> observer.subject(MonitoringObserver.SubjectOutcome.NOT_JUDGED));
            return new Judged(0, 0, accountsOf.size(), unplaced.size());
        }

        int judged = 0;
        int failed = 0;
        for (Map.Entry<UUID, Set<UUID>> party : accountsOf.entrySet()) {
            List<MonitoredMovement> touched = new ArrayList<>();
            for (MonitoredTransaction row : rows) {
                MonitoredMovement.seenBy(row, party.getValue()).ifPresent(touched::add);
            }
            Instant started = clock.instant();
            try {
                isolation.alone(unitOfWork, () -> spans.within(RiskSpans.MONITORING_EVALUATE, Map.of(),
                        () -> recompute.monitor(unitOfWork, party.getKey(), touched, pinned.get(),
                                CorrelationId.generate(ids))));
                judged++;
                observer.subject(MonitoringObserver.SubjectOutcome.JUDGED);
                observer.judged(Duration.between(started, clock.instant()));
            } catch (IsolationBroken broken) {
                throw broken;
            } catch (RuntimeException failure) {
                // That party alone rolled back - the run goes on (DESIGN-020 D10). The cause's class only.
                failed++;
                observer.subject(MonitoringObserver.SubjectOutcome.FAILED);
                Throwable cause = failure instanceof ProfileRecompute.RecomputeFailed && failure.getCause() != null
                        ? failure.getCause() : failure;
                observer.subjectFailed(cause.getClass());
            }
        }
        return new Judged(judged, failed, 0, unplaced.size());
    }

    private static <T> T inSavepoint(Connection unitOfWork, Supplier<T> work) {
        Savepoint savepoint;
        try {
            savepoint = unitOfWork.setSavepoint();
        } catch (SQLException failure) {
            throw new IsolationBroken(JdbcMonitoredTransactionStore.failed("opening a party's savepoint", failure));
        }
        T done;
        try {
            done = work.get();
        } catch (RuntimeException failure) {
            try {
                unitOfWork.rollback(savepoint);
            } catch (SQLException undone) {
                // The party's writes could not be undone alone: the claim itself must fail whole.
                throw new IsolationBroken(JdbcMonitoredTransactionStore.failed("rolling a party's savepoint back",
                        undone));
            }
            throw failure;
        }
        try {
            unitOfWork.releaseSavepoint(savepoint);
        } catch (SQLException failure) {
            throw new IsolationBroken(JdbcMonitoredTransactionStore.failed("releasing a party's savepoint", failure));
        }
        return done;
    }
}
