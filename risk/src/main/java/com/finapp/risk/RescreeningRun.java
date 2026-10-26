package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The rescreening leg's steps (`P13-TSK-022`; {@code PHASE_13_PLAN.md} sections 7.1 T-list, 7.5, 12.10;
 * {@code RISK_LIFECYCLES.md} section 4.10; {@code INV-RSK-06}, {@code INV-RSK-02}, {@code INV-RSK-08}) - the enqueue of an
 * activated list version's permits, and each permit's three steps; {@code app}'s {@code RescreeningDriver} runs them on
 * {@code RiskMonitoringSchedule}, every instance, no leader.
 *
 * <h2>The enqueue - one batch, one transaction</h2>
 *
 * <p>{@link #enqueueNextBatch}: the oldest {@code ACTIVE} version whose enqueue permit has come, {@code FOR UPDATE SKIP
 * LOCKED} (R9), then a page of {@code ACTIVE} parties after its cursor ({@link ActiveParties}, on the same unit of work),
 * one permit each {@code ON CONFLICT DO NOTHING} ({@code rescreening_permit_once}), then the cursor advanced - or the
 * census completed when the page came back short. The page, its permits and the advance commit together or not at all:
 * a crash re-runs the batch from the committed cursor into the same keys, and ten enqueuers take a version one batch at a
 * time - <strong>every {@code ACTIVE} party once</strong>. Never in the activation's transaction: an activation writes
 * one row whatever the population.
 *
 * <h2>A permit - three steps, the kyc call in none of risk's transactions</h2>
 *
 * <ol>
 *   <li>{@link #claimNextDue} - the oldest due permit {@code FOR UPDATE SKIP LOCKED}, its permit a lease ahead, one
 *       attempt counted, <strong>committed</strong>; a claim past the bound with no conclusion behind it fails the permit
 *       {@code UNCONCLUDED} instead - so a conclusion that always throws is bounded too;</li>
 *   <li>{@link #ask} - kyc asked through {@link PartyRescreening} under the permit's key, holding no risk row; a throw is
 *       {@link PartyRescreening.Unavailable};</li>
 *   <li>{@link #conclude} - in one transaction: the permit {@code FOR UPDATE} (R9), acted on only while {@code PENDING}
 *       at the claimed attempt (a slower conclusion of an earlier claim records nothing), then judged:
 *       <ul>
 *         <li>a screening kyc was <strong>asked after the version's activation</strong> (by {@link #REQUEST_SKEW}) and
 *             has decided is the party's rescreening under it: the {@code MONITORING} pin for a hit (R1), the signal
 *             {@code SCREENING_CLEARED} or
 *             {@code SCREENING_HIT} born once - its source the permit (R3) - the permit {@code DONE}, and for a hit the
 *             {@code SCREENING_HIT} alert born once and folded into the party's {@code AML} case (R5 → R4 → R5);</li>
 *         <li>an older screening ({@code STALE} - kyc has not screened the party under this version), one in flight, none,
 *             or kyc unavailable: the permit re-permitted, backed off - {@code FAILED} with the cause at the bound;</li>
 *         <li>no {@code ACTIVE} customer relationship any more: {@code DONE(SKIPPED)}.</li>
 *       </ul></li>
 * </ol>
 *
 * <p><strong>Risk asks, kyc screens</strong> ({@code INV-RSK-02}): nothing here screens, and a stale, pending or
 * unavailable answer is never recorded as a clearance - the census of a version counts only what kyc decided under it.
 *
 * <p><strong>The hit's alert</strong> ({@code DESIGN-022} D9): kind {@code SCREENING_HIT}, rule {@code SCREENING_HIT} of the
 * {@code MONITORING} version in force - raised only when that version carries the rule (an alert names the policy rule that
 * raised it, ADR-0109); its window the {@code H1} bucket holding the version's activation (a database stamp on a born-once
 * fact, so every raiser of the version computes the same key); severity {@code CRITICAL}; evidence the hit's signal. With
 * no such rule the hit is recorded and counted {@code hit_not_alerted} - never silently.
 */
public final class RescreeningRun {

    /** A hit's alert severity - constant, so two raisers of one key agree. */
    public static final AlertSeverity HIT_SEVERITY = AlertSeverity.CRITICAL;

    /** The bucket a hit's alert speaks for: the hour its list version came into force. */
    public static final WindowKind HIT_WINDOW = WindowKind.H1;

    /**
     * How far after the activation kyc's ask must be to count under it - the instances' clock skew the plan admits
     * ({@code PHASE_13_PLAN.md} section 7.4, {@link VelocityProof#SKEW}), because kyc stamps its ask on an instance's
     * clock and the activation is the database's.
     */
    public static final Duration REQUEST_SKEW = VelocityProof.SKEW;

    /** The leg's bounds: how many claims a permit takes before it fails, and the backoff between them. */
    public record Bounds(int maxAttempts, Duration firstBackoff, Duration maxBackoff) {

        /** Twelve attempts, five minutes doubling to six hours - about a day and a quarter before a permit fails. */
        public static final Bounds DEFAULT = new Bounds(12, Duration.ofMinutes(5), Duration.ofHours(6));

        public Bounds {
            Objects.requireNonNull(firstBackoff, "firstBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("a permit takes at least one attempt");
            }
            if (firstBackoff.isNegative() || firstBackoff.isZero() || maxBackoff.compareTo(firstBackoff) < 0) {
                throw new IllegalArgumentException("the backoff is positive and its cap no shorter than its first wait");
            }
        }

        /** The wait after claim {@code attempt}: the first, doubling, capped. */
        public Duration backoff(int attempt) {
            int doublings = Math.min(Math.max(attempt - 1, 0), 20);
            Duration wait = firstBackoff.multipliedBy(1L << doublings);
            return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
        }
    }

    /** One enqueue batch: whether a version was claimed, how many permits it bore, whether its census is complete. */
    public record Enqueued(boolean claimed, int born, boolean complete) {

        /** No version was due. */
        public static final Enqueued NOTHING = new Enqueued(false, 0, false);
    }

    /** One permit's conclusion: the permit as claimed, what became of it, the signal it recorded. */
    public record Concluded(RescreeningPermit permit, RescreeningObserver.Conclusion conclusion,
            Optional<RiskSignalId> signal) {
        public Concluded {
            Objects.requireNonNull(permit, "permit");
            Objects.requireNonNull(conclusion, "conclusion");
            Objects.requireNonNull(signal, "signal");
        }
    }

    private final ScreeningListStore lists;
    private final RescreeningPermitStore permits;
    private final ActiveParties activeParties;
    private final PartyRescreening kyc;
    private final RiskSignals signals;
    private final RiskPolicies policies;
    private final AlertRaiser alerts;
    private final IdGenerator ids;
    private final RescreeningObserver observer;
    private final Bounds bounds;

    public RescreeningRun(ScreeningListStore lists, RescreeningPermitStore permits, ActiveParties activeParties,
            PartyRescreening kyc, RiskSignals signals, RiskPolicies policies, AlertRaiser alerts, IdGenerator ids,
            RescreeningObserver observer, Bounds bounds) {
        this.lists = Objects.requireNonNull(lists, "lists");
        this.permits = Objects.requireNonNull(permits, "permits");
        this.activeParties = Objects.requireNonNull(activeParties, "activeParties");
        this.kyc = Objects.requireNonNull(kyc, "kyc");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
    }

    // ------------------------------------------------------------------ the enqueue

    /**
     * One enqueue batch on the caller's unit of work - see the class.
     *
     * @param batch the most parties one batch enqueues, {@code 1..ActiveParties.BOUND}
     */
    public Enqueued enqueueNextBatch(Connection unitOfWork, int batch) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        if (batch < 1 || batch > ActiveParties.BOUND) {
            throw new IllegalArgumentException("an enqueue batch is 1.." + ActiveParties.BOUND + " parties: " + batch);
        }
        Optional<ScreeningListVersion> claimed = lists.claimEnqueueDue(unitOfWork);
        if (claimed.isEmpty()) {
            return Enqueued.NOTHING;
        }
        ScreeningListVersion version = claimed.get();
        List<UUID> page = activeParties.after(unitOfWork, version.enqueueCursor(), batch);
        if (page.size() > batch) {
            throw new IllegalStateException("the active parties' page is longer than asked: " + page.size());
        }
        Optional<UUID> cursor = version.enqueueCursor();
        int born = 0;
        for (UUID party : page) {
            if (cursor.isPresent() && databaseOrder(party, cursor.get()) <= 0) {
                throw new IllegalStateException("the active parties' page is not ascending after its cursor");
            }
            cursor = Optional.of(party);
            if (permits.enqueue(unitOfWork, new RescreeningPermitStore.NewPermit(RescreeningPermitId.next(ids), party,
                    version.id(), version.correlationId()))) {
                born++;
            }
        }
        boolean complete = page.size() < batch;
        lists.advanceEnqueue(unitOfWork, version.id(), cursor, born, complete);
        observer.enqueued(born);
        return new Enqueued(true, born, complete);
    }

    // ------------------------------------------------------------------ a permit's three steps

    /**
     * Step 1, on its own transaction: the oldest due permit claimed a lease ahead ({@link Claim.Claimed}), nothing due
     * ({@link Claim#NOTHING}) - or a permit whose claims have passed the bound with none concluded, failed here
     * {@code UNCONCLUDED} ({@link Claim.Exhausted}).
     *
     * <p>The bound is judged at the claim as well as at the conclusion (`P13-TSK-022`'s review): {@link #waits} fails a
     * permit at its last attempt only when a conclusion runs, so a conclusion that throws every time - a defect, a row
     * the store refuses - would be re-claimed every lease forever. A claim past the bound means every earlier conclusion
     * failed or was overtaken; it is concluded without asking kyc again.
     */
    public Claim claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("a claim's lease is positive");
        }
        Optional<RescreeningPermit> claimed = permits.claimNextDue(unitOfWork, lease);
        if (claimed.isEmpty()) {
            return Claim.NOTHING;
        }
        RescreeningPermit permit = claimed.get();
        if (permit.attempts() > bounds.maxAttempts()) {
            recorded(permits.fail(unitOfWork, permit.id(), permit.attempts(), RescreeningPermit.Cause.UNCONCLUDED));
            observer.concluded(RescreeningObserver.Conclusion.FAILED);
            return new Claim.Exhausted(permit);
        }
        return new Claim.Claimed(permit);
    }

    /** What a claim found. Closed, so the driver handles every case. */
    public sealed interface Claim permits Claim.Nothing, Claim.Claimed, Claim.Exhausted {

        /** No permit was due. */
        Claim NOTHING = new Nothing();

        /** No permit was due. */
        record Nothing() implements Claim {}

        /** A permit claimed for its ask and its conclusion - at an attempt within the bound. */
        record Claimed(RescreeningPermit permit) implements Claim {
            public Claimed {
                Objects.requireNonNull(permit, "permit");
            }
        }

        /** A permit claimed past the bound with no conclusion behind it - failed {@code UNCONCLUDED} by the claim. */
        record Exhausted(RescreeningPermit permit) implements Claim {
            public Exhausted {
                Objects.requireNonNull(permit, "permit");
            }
        }
    }

    /** Step 2, on no risk transaction: kyc asked under the permit's key; a throw is {@code Unavailable}. */
    public PartyRescreening.Answer ask(RescreeningPermit claimed) {
        Objects.requireNonNull(claimed, "claimed");
        try {
            return Objects.requireNonNull(kyc.ask(claimed.partyId(), claimed.list(), claimed.requestKey()),
                    "kyc's answer");
        } catch (RuntimeException unavailable) {
            // The class only - never the message, which a storage failure may fill with a row (INV-RSK-08).
            observer.askFailed(unavailable.getClass());
            return new PartyRescreening.Unavailable();
        }
    }

    /**
     * Step 3, on its own transaction: {@code answer} recorded for {@code claimed} - see the class. Empty when the permit is
     * no longer at the claimed attempt (another claim or conclusion came first): nothing is written.
     */
    public Optional<Concluded> conclude(Connection unitOfWork, RescreeningPermit claimed, PartyRescreening.Answer answer) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(answer, "answer");
        RescreeningPermit locked = permits.lock(unitOfWork, claimed.id()).orElseThrow(() -> new IllegalStateException(
                "a claimed rescreening permit has its row"));
        if (locked.status() != RescreeningPermit.Status.PENDING || locked.attempts() != claimed.attempts()) {
            return Optional.empty();
        }
        Concluded concluded = switch (answer) {
            case PartyRescreening.Inactive inactive -> skipped(unitOfWork, locked);
            case PartyRescreening.Screened screened when current(screened, locked) -> screened(unitOfWork, locked, screened);
            case PartyRescreening.Screened stale -> waits(unitOfWork, locked, RescreeningPermit.Cause.STALE);
            case PartyRescreening.Pending pending -> waits(unitOfWork, locked,
                    pending.reason() == PartyRescreening.Wait.IN_FLIGHT ? RescreeningPermit.Cause.IN_FLIGHT
                            : RescreeningPermit.Cause.NOT_SCREENED);
            case PartyRescreening.Unavailable unavailable -> waits(unitOfWork, locked, RescreeningPermit.Cause.UNAVAILABLE);
        };
        observer.concluded(concluded.conclusion());
        return Optional.of(concluded);
    }

    /** The leg's bounds. */
    public Bounds bounds() {
        return bounds;
    }

    // ------------------------------------------------------------------ the judgements

    /**
     * True when kyc was <strong>asked</strong> {@code screened} after the version came into force - a rescreening under
     * it (`P13-TSK-022`'s review). Judged by the ask, never the answer: a check dispatched before the activation and
     * decided after it screened the list before this one, and counting it would record a clearance the version never
     * gave - a false clearance. The two instants are two clocks (kyc stamps {@code requested_at} on the requesting
     * instance's, the activation is the database's), so the ask must clear the activation by {@link #REQUEST_SKEW}: a
     * check asked within it is judged stale - the conservative side, costing at most a re-ask.
     */
    static boolean current(PartyRescreening.Screened screened, RescreeningPermit permit) {
        return !screened.requestedAt().isBefore(permit.listActivatedAt().plus(REQUEST_SKEW));
    }

    /**
     * Ascending as PostgreSQL orders {@code uuid} - bytewise, so each half unsigned; {@link UUID#compareTo} compares the
     * halves signed and would call a page the database ordered descending wherever the top bit of a half differs.
     */
    public static int databaseOrder(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private Concluded skipped(Connection unitOfWork, RescreeningPermit locked) {
        recorded(permits.conclude(unitOfWork, locked.id(), locked.attempts(), RescreeningPermit.Outcome.SKIPPED,
                Optional.empty()));
        return new Concluded(locked, RescreeningObserver.Conclusion.SKIPPED, Optional.empty());
    }

    private Concluded screened(Connection unitOfWork, RescreeningPermit locked, PartyRescreening.Screened screened) {
        SignalKind kind = screened.hit() ? SignalKind.SCREENING_HIT : SignalKind.SCREENING_CLEARED;
        CorrelationId correlation = CorrelationId.of(locked.correlationId());
        CausationId cause = CausationId.of(locked.id().value().toString());
        // (R1) before (R3): the hit's alert names the MONITORING version's rule, pinned FOR SHARE to this commit.
        Optional<RiskPolicyVersion> pinned = screened.hit()
                ? policies.active(unitOfWork, RiskOperation.MONITORING) : Optional.empty();
        RiskSignal signal = new RiskSignal(
                RiskSignalId.next(ids),
                kind,
                SignalSubject.of(SubjectKind.PARTY, locked.partyId()),
                new RiskSignal.SourceEvent(EventId.of(locked.id().value()), kind.sourceEventType(), 1,
                        RiskSignals.PRODUCER, correlation, cause),
                screened.decidedAt(),
                SignalAttributes.of(kind, Map.of(
                        "list_version_ref", locked.listVersionId().value().toString(),
                        "screening_ref", screened.screeningRef().toString())));
        if (signals.record(unitOfWork, signal) != RiskSignals.Outcome.BORN) {
            throw new IllegalStateException(
                    "a permit's clearance was recorded before its conclusion: the permit's lock protocol was bypassed");
        }
        RescreeningPermit.Outcome outcome = screened.hit() ? RescreeningPermit.Outcome.HIT
                : RescreeningPermit.Outcome.CLEARED;
        recorded(permits.conclude(unitOfWork, locked.id(), locked.attempts(), outcome, Optional.of(signal.id())));
        if (!screened.hit()) {
            return new Concluded(locked, RescreeningObserver.Conclusion.CLEARED, Optional.of(signal.id()));
        }
        Optional<RiskPolicyVersion> naming = pinned.filter(version -> version.policy().rules().stream()
                .anyMatch(rule -> rule.kind() == RuleKind.SCREENING_HIT));
        if (naming.isEmpty()) {
            return new Concluded(locked, RescreeningObserver.Conclusion.HIT_NOT_ALERTED, Optional.of(signal.id()));
        }
        alerts.raiseAll(unitOfWork, List.of(hitAlert(locked, naming.get().id(), signal.id(), correlation, cause)));
        return new Concluded(locked, RescreeningObserver.Conclusion.HIT, Optional.of(signal.id()));
    }

    /**
     * The hit's alert - born once per (party, {@code SCREENING_HIT}, the hour the version came into force), whoever raises
     * it and whenever ({@code INV-RSK-06}).
     */
    static NewAlert hitAlert(RescreeningPermit permit, RiskPolicyVersionId version, RiskSignalId signal,
            CorrelationId correlation, CausationId cause) {
        return NewAlert.ofRule(AlertKind.SCREENING_HIT, HIT_SEVERITY, SignalSubject.of(SubjectKind.PARTY,
                        permit.partyId()), RuleKind.SCREENING_HIT, AlertWindow.containing(HIT_WINDOW,
                        permit.listActivatedAt()), version, AlertEvidence.signals(List.of(signal)), correlation, cause);
    }

    private Concluded waits(Connection unitOfWork, RescreeningPermit locked, RescreeningPermit.Cause cause) {
        if (locked.attempts() >= bounds.maxAttempts()) {
            recorded(permits.fail(unitOfWork, locked.id(), locked.attempts(), cause));
            return new Concluded(locked, RescreeningObserver.Conclusion.FAILED, Optional.empty());
        }
        recorded(permits.defer(unitOfWork, locked.id(), locked.attempts(), cause, bounds.backoff(locked.attempts())));
        return new Concluded(locked, RescreeningObserver.Conclusion.DEFERRED, Optional.empty());
    }

    private static void recorded(boolean written) {
        if (!written) {
            throw new IllegalStateException(
                    "the locked permit moved under its own row lock: the FOR UPDATE protocol was bypassed");
        }
    }
}
