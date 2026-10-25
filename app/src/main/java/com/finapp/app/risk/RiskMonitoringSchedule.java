package com.finapp.app.risk;

import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.MonitoringIntake;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskSpans;
import java.sql.Connection;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * The risk monitoring sweep (`P13-TSK-019`; {@code PHASE_13_PLAN.md} sections 7.1 T-mon, 7.5, 12.7;
 * {@code RISK_LIFECYCLES.md} sections 4.9, 4.11; bnpl's {@code BnplOriginationSchedule}) - the intake leg, created here;
 * the profile leg (`P13-TSK-007`, below); `-022` adds the rescreening leg. Every instance runs it with no lease and
 * no leader: each tick claims the ledger journal's watermark row {@code FOR UPDATE SKIP LOCKED} when its permit has come (the Phase 13
 * lock order's R7), reads the entries after it through the port within the settle horizon, inserts their monitored
 * transactions born once and advances the watermark - one batch, one transaction, holding no money row - and goes on
 * while a batch comes back full, up to a bounded number of batches per tick; so ten sweepers on one watermark ingest
 * every entry once ({@code MonitoringIntakeDatabaseTest#tenSweepersIngestOnce}). A batch that fails rolls back whole,
 * is counted ({@code finapp.risk.sweeper.claim.failed{kind=monitoring}}) and leaves the permit for a later tick.
 * Completion events only pull the permit forward (`-005`'s hint). Off in test contexts
 * ({@code finapp.risk.monitoring.sweeper.enabled}); the suites drive {@link #sweepOnce()}.
 *
 * <p><strong>The profile leg</strong> (`P13-TSK-007`; {@code INV-RSK-10}): after the intake, each tick claims customer
 * risk profiles whose permit has come - one head per transaction, {@code FOR UPDATE SKIP LOCKED} after the
 * {@code MONITORING} pin (R1, then R8), recomputed by {@link ProfileRecompute}, at most {@code profileClaimsPerTick} -
 * and stops at the first claim with none due. A recompute that fails ({@link ProfileRecompute.RecomputeFailed}) rolls
 * back alone, is counted {@code claim.failed{kind=monitoring}}, and its party's permit is deferred by
 * {@link ProfileRecompute#FAILURE_BACKOFF} in a transaction of its own - so a party that keeps failing never stalls the
 * leg - and the leg goes on; any other failure (the claim itself, the pin) is counted and ends the leg for the tick. Ten
 * instances sweeping together write one version per rating change ({@code ProfileDatabaseTest#tenRecomputesOneVersion}).
 *
 * <p><strong>The case leg</strong> (`P13-TSK-016`; {@code DESIGN-016} D7; {@link CaseLegDriver}): after the profiles, the
 * drain of the alerts born {@code OPEN} before the folding was wired - each folded in a transaction of its own, the case
 * (R4) before the alert (R5), bounded by {@code finapp.risk.case.folds-per-tick} - and the re-drive of approved
 * prohibitions whose profile version a door's crash left unwritten ({@code finapp.risk.case.prohibitions-per-tick}). A
 * failed fold is counted {@code claim.failed{kind=monitoring}} and the leg goes on; a leg that throws is counted and ends
 * for the tick.
 *
 * <p><strong>The override action leg moved</strong> (`P13-TSK-017`): `-012`'s at-least-once re-drive of an approved
 * override's issued action rode this schedule until the action machine landed; the action is a {@code risk_action} row
 * since {@code risk V013} (under the override's own id, its key unchanged) and {@code RiskActionSchedule} - its own
 * leaderless schedule - drives it, so a slow owner's door no longer holds a tick of the monitoring sweep.
 */
@Slf4j
public final class RiskMonitoringSchedule implements SmartLifecycle {

    /** The profile leg's steps - {@link ProfileRecompute}'s, in production. */
    public interface ProfileLeg {

        /** No profile leg - the intake alone (`P13-TSK-019`'s shape, kept for its suites). */
        ProfileLeg NONE = new ProfileLeg() {
            @Override
            public Optional<ProfileRecompute.Outcome> recomputeNextDue(Connection unitOfWork) {
                return Optional.empty();
            }

            @Override
            public void defer(Connection unitOfWork, UUID partyId) {
                // Nothing is ever claimed, so nothing ever fails.
            }
        };

        /** The next due profile recomputed on the unit of work, or empty when none is due. */
        Optional<ProfileRecompute.Outcome> recomputeNextDue(Connection unitOfWork);

        /** {@code partyId}'s permit deferred after its recompute failed and rolled back. */
        void defer(Connection unitOfWork, UUID partyId);

        /** The leg over {@code recompute}. */
        static ProfileLeg over(ProfileRecompute recompute) {
            Objects.requireNonNull(recompute, "recompute");
            return new ProfileLeg() {
                @Override
                public Optional<ProfileRecompute.Outcome> recomputeNextDue(Connection unitOfWork) {
                    return recompute.recomputeNextDue(unitOfWork);
                }

                @Override
                public void defer(Connection unitOfWork, UUID partyId) {
                    recompute.defer(unitOfWork, partyId);
                }
            };
        }
    }

    /** The case leg's step - {@link CaseLegDriver#driveDue}, in production (`P13-TSK-016`). */
    @FunctionalInterface
    public interface CaseLeg {

        /** No case leg - the earlier tasks' shape, kept for their suites. */
        CaseLeg NONE = () -> 0;

        /** The queued alerts folded and the approved prohibitions applied, bounded per tick; how many were done. */
        int driveDue();
    }

    private final MonitoringIntake intake;
    private final ProfileLeg profiles;
    private final CaseLeg cases;
    private final int profileClaimsPerTick;
    private final RiskTransactions transactions;
    private final RiskMonitoringMetrics metrics;
    private final RiskFlowScope flow;
    private final Duration pollInterval;
    private final int batchesPerTick;
    private ScheduledExecutorService executor;

    /**
     * @param pollInterval the tick
     * @param batchesPerTick the most batches one tick takes while they come back full - the drain's bound, so a tick
     *     behind a long outage never holds the watermark for the whole backlog
     */
    public RiskMonitoringSchedule(MonitoringIntake intake, RiskTransactions transactions, RiskMonitoringMetrics metrics,
            RiskFlowScope flow, Duration pollInterval, int batchesPerTick) {
        this(intake, ProfileLeg.NONE, 0, transactions, metrics, flow, pollInterval, batchesPerTick);
    }

    /**
     * @param profiles the profile leg - {@code ProfileLeg.over(profileRecompute)} in production
     * @param profileClaimsPerTick the most profiles one tick recomputes - the leg's bound; 0 only with no leg
     */
    public RiskMonitoringSchedule(MonitoringIntake intake, ProfileLeg profiles, int profileClaimsPerTick,
            RiskTransactions transactions, RiskMonitoringMetrics metrics, RiskFlowScope flow, Duration pollInterval,
            int batchesPerTick) {
        this(intake, profiles, profileClaimsPerTick, CaseLeg.NONE, transactions, metrics, flow, pollInterval,
                batchesPerTick);
    }

    /**
     * @param cases the case leg - {@code caseLegDriver::driveDue} in production (`P13-TSK-016`): the drain of the
     *     queued alerts and the approved prohibitions' re-drive, after the profiles
     */
    public RiskMonitoringSchedule(MonitoringIntake intake, ProfileLeg profiles, int profileClaimsPerTick,
            CaseLeg cases, RiskTransactions transactions, RiskMonitoringMetrics metrics,
            RiskFlowScope flow, Duration pollInterval, int batchesPerTick) {
        // The configuration judged first - a misconfigured instance refuses to start whatever it was handed.
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("the poll interval must be positive");
        }
        if (batchesPerTick < 1) {
            throw new IllegalArgumentException("batchesPerTick must be positive: " + batchesPerTick);
        }
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        if (profileClaimsPerTick < 0 || (profileClaimsPerTick == 0 && profiles != ProfileLeg.NONE)) {
            throw new IllegalArgumentException("profileClaimsPerTick must be positive: " + profileClaimsPerTick);
        }
        this.profileClaimsPerTick = profileClaimsPerTick;
        this.cases = Objects.requireNonNull(cases, "cases");
        this.batchesPerTick = batchesPerTick;
        this.intake = Objects.requireNonNull(intake, "intake");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /**
     * One sweep under the system actor: batches of the intake leg while they come back full, each in a transaction of
     * its own, a failed batch counted and the intake ended for this tick; then the profile leg, one due profile per
     * transaction up to its bound, a failed recompute counted and its party deferred, a failed claim ending the leg; then
     * the case leg.
     *
     * @return how many monitored transactions this call bore
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public int sweepOnce() {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            int born = 0;
            for (int taken = 0; taken < batchesPerTick; taken++) {
                MonitoringIntake.Batch batch;
                try {
                    batch = flow.sweep(RiskSpans.MONITORING_INTAKE, () -> transactions.inTransaction(
                            uow -> intake.sweepBatch(uow, MonitoringWatermark.LEDGER_JOURNAL)));
                } catch (RuntimeException failure) {
                    // The batch rolled back whole; the permit is untouched and a later tick claims again. The class
                    // name only - never a position, an entry or a subject.
                    metrics.claimFailed();
                    log.warn("Risk monitoring intake batch failed: {}", failure.getClass().getSimpleName());
                    break;
                }
                if (!batch.claimed()) {
                    break;
                }
                born += batch.born();
                if (!batch.full()) {
                    break;
                }
            }
            recomputeDueProfiles();
            driveCaseLeg();
            return born;
        }
    }

    /** The case leg, inside {@link #sweepOnce}'s system scope: the drain and the prohibitions, each act its own tx. */
    private void driveCaseLeg() {
        try {
            int done = cases.driveDue();
            if (done > 0) {
                log.debug("Risk case leg acts: {}", done);
            }
        } catch (RuntimeException failure) {
            metrics.claimFailed();
            log.warn("Risk case leg failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** The profile leg, inside {@link #sweepOnce}'s system scope: due profiles recomputed, one per transaction. */
    private void recomputeDueProfiles() {
        for (int taken = 0; taken < profileClaimsPerTick; taken++) {
            Optional<ProfileRecompute.Outcome> outcome;
            try {
                outcome = flow.sweep(RiskSpans.PROFILE_RECOMPUTE,
                        () -> transactions.inTransaction(profiles::recomputeNextDue));
            } catch (ProfileRecompute.RecomputeFailed failed) {
                // That party's recompute rolled back whole - no version, no event. Its permit is deferred in a
                // transaction of its own so it does not stand first in every claim; the leg goes on. The cause's class
                // only - never a party, a rating or a score (INV-RSK-08).
                metrics.claimFailed();
                log.warn("Risk profile recompute failed: {}", failed.getCause().getClass().getSimpleName());
                try {
                    transactions.inTransaction(unitOfWork -> {
                        profiles.defer(unitOfWork, failed.partyId());
                        return null;
                    });
                } catch (RuntimeException deferral) {
                    metrics.claimFailed();
                    log.warn("Risk profile recompute deferral failed: {}", deferral.getClass().getSimpleName());
                    return;
                }
                continue;
            } catch (RuntimeException failure) {
                // The claim or the pin failed: nothing was claimed for certain; counted, and the leg ends for the tick.
                metrics.claimFailed();
                log.warn("Risk profile recompute failed: {}", failure.getClass().getSimpleName());
                return;
            }
            if (outcome.isEmpty()) {
                return;
            }
            log.debug("Risk profile recompute: {}", outcome.get().kind());
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "risk-monitoring");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void sweepQuietly() {
        try {
            sweepOnce();
        } catch (RuntimeException failure) {
            log.warn("Risk monitoring sweep failed: {}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }
}
