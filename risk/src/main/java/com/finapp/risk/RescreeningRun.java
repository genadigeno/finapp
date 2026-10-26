package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The rescreening leg's steps (`P13-TSK-022`; {@code PHASE_13_PLAN.md} sections 7.1 T-list, 7.5, 12.10;
 * {@code RISK_LIFECYCLES.md} section 4.10; {@code INV-RSK-06}, {@code INV-RSK-02}, {@code INV-RSK-08}) - the enqueue of an
 * activated list version's permits, and each permit's three steps; {@code app}'s {@code RescreeningDriver} runs them on
 * {@code RiskActionSchedule} (its third leg since kyc's door made it a provider caller), every instance, no leader.
 *
 * <h2>The enqueue - one batch, one transaction</h2>
 *
 * <p>{@link #enqueueNextBatch}: the oldest {@code ACTIVE} version whose enqueue permit has come, {@code FOR UPDATE SKIP
 * LOCKED} (R9), then a page of {@code ACTIVE} or {@code PENDING} parties after its cursor ({@link ActiveParties}, on the same unit of work),
 * one permit each {@code ON CONFLICT DO NOTHING} ({@code rescreening_permit_once}), then the cursor advanced - or the
 * census completed when the page came back short. The page, its permits and the advance commit together or not at all:
 * a crash re-runs the batch from the committed cursor into the same keys, and ten enqueuers take a version one batch at a
 * time - <strong>every such party once</strong>. Never in the activation's transaction: an activation writes
 * one row whatever the population.
 *
 * <h2>A permit - three steps, the kyc call in none of risk's transactions</h2>
 *
 * <ol>
 *   <li>{@link #claimNextDue} - the oldest due permit {@code FOR UPDATE SKIP LOCKED}, its permit a lease ahead, one
 *       attempt counted, <strong>committed</strong>; a claim past the bound with no conclusion behind it fails the permit
 *       {@code UNCONCLUDED} instead - so a conclusion that always throws is bounded too;</li>
 *   <li>{@link #ask} - kyc asked through {@link PartyRescreening} under the permit's key, holding no risk row; a throw is
 *       {@link PartyRescreening.Unavailable}. The question carries the claim's attempt and its abandonment line (the
 *       review's IMPORTANT 2): one new key ordinal per claim while the last answered "we do not know" or was abandoned
 *       mid-call, at most {@code maxAttempts} - so a provider outage or a restart mid-call is retried at the leg's own
 *       backoff and bound, never failed after three questions;</li>
 *   <li>{@link #conclude} - in one transaction: the permit {@code FOR UPDATE} (R9), acted on only while {@code PENDING}
 *       at the claimed attempt (a slower conclusion of an earlier claim records nothing), then judged:
 *       <ul>
 *         <li>a screening kyc was <strong>asked at or after the version's activation</strong> - the permit's own keyed
 *             check, both instants the database's - and has decided is the party's rescreening under it: the signal
 *             {@code SCREENING_CLEARED} or {@code SCREENING_HIT} born once - its source the permit (R3) - the permit
 *             {@code DONE}, and for a hit the {@code SCREENING_HIT} fact alert born once and folded into the party's
 *             {@code AML} case (R5 → R4 → R5);</li>
 *         <li>an older screening ({@code STALE} - kyc has not screened the party under this version), one in flight, none,
 *             or kyc unavailable: the permit re-permitted, backed off - {@code FAILED} with the cause at the bound;</li>
 *         <li>no {@code ACTIVE} or {@code PENDING} customer relationship any more: {@code DONE(SKIPPED)};</li>
 *         <li>a {@code PENDING} relationship with no KYC case - never screened, nothing to rescreen (the review's
 *             IMPORTANT 4): {@code DONE(NOT_ONBOARDED)}, counted on its own, never an exposure.</li>
 *       </ul></li>
 * </ol>
 *
 * <p><strong>Risk asks, kyc screens</strong> ({@code INV-RSK-02}): nothing here screens, and a stale, pending or
 * unavailable answer is never recorded as a clearance - the census of a version counts only what kyc decided under it.
 *
 * <p><strong>The hit's alert</strong> (the review's IMPORTANT 2, replacing {@code DESIGN-022} D9): a <em>fact</em>
 * ({@link AlertKind#SCREENING_HIT}, {@code risk V018}) - its own code its rule, no policy naming it, so no policy in
 * force (or one without a screening rule) can leave a sanctions hit unalerted; about the {@code PARTY}, its window the
 * {@code D1} bucket holding the version's activation (a database stamp on a born-once fact); severity
 * {@code CRITICAL}; evidence exactly the hit's signal. <strong>One alert per (party, list version)</strong> (the
 * review's IMPORTANT 3): a {@code SCREENING_HIT} alert's identity carries its one signal ({@link
 * AlertKind#oneAlertPerSignal}, {@code risk V018}'s {@code fact_signal_id}), and that signal is born once of the permit,
 * which is one per (party, version) - so two lists' or versions' hits of one party on one day are two alerts, each born,
 * each folded into the party's open {@code AML} case or a new one, and a second hit is never lost to a case the first
 * one's investigator already closed.
 *
 * <p><strong>The answer is kyc's door's</strong> (the owner's decision O1): kyc re-asks a decided party under the
 * permit's key, so "asked at or after the activation" is true by construction for a permit's own check and judged here
 * on one clock - kept as the rule, never assumed.
 */
public final class RescreeningRun {

    /** A hit's alert severity - constant, so two raisers of one key agree. */
    public static final AlertSeverity HIT_SEVERITY = AlertSeverity.CRITICAL;

    /** The bucket a hit's alert speaks for: the day its list version came into force - a fact's window ({@code D1}). */
    public static final WindowKind HIT_WINDOW = WindowKind.D1;

    /** The leg's bounds: how many claims a permit takes before it fails, and the backoff between them. */
    public record Bounds(int maxAttempts, Duration firstBackoff, Duration maxBackoff) {

        /**
         * Twelve attempts, five minutes doubling to six hours: eleven waits of 5, 10, 20, 40, 80, 160 and 320 minutes and
         * then four of six hours - about 34.6 hours from the first claim to the last before a permit fails.
         */
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
    private final AlertRaiser alerts;
    private final IdGenerator ids;
    private final RescreeningObserver observer;
    private final Bounds bounds;

    public RescreeningRun(ScreeningListStore lists, RescreeningPermitStore permits, ActiveParties activeParties,
            PartyRescreening kyc, RiskSignals signals, AlertRaiser alerts, IdGenerator ids, RescreeningObserver observer,
            Bounds bounds) {
        this.lists = Objects.requireNonNull(lists, "lists");
        this.permits = Objects.requireNonNull(permits, "permits");
        this.activeParties = Objects.requireNonNull(activeParties, "activeParties");
        this.kyc = Objects.requireNonNull(kyc, "kyc");
        this.signals = Objects.requireNonNull(signals, "signals");
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

    /**
     * Step 2, on no risk transaction: kyc asked under the permit's key; a throw is {@code Unavailable}. {@code lease} is
     * the one {@code claimed} was taken with: the claim's instant is its permit less the lease, and a question still
     * unanswered from before that instant less one more lease was abandoned ({@link PartyRescreening.Question}) - all
     * on the database's clock.
     */
    public PartyRescreening.Answer ask(RescreeningPermit claimed, Duration lease) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(lease, "lease");
        Instant claimedAt = claimed.nextAttemptAt().orElseThrow(() -> new IllegalArgumentException(
                "a claimed permit holds its permit")).minus(lease);
        PartyRescreening.Question question = new PartyRescreening.Question(claimed.partyId(), claimed.list(),
                claimed.requestKey(), claimed.attempts(), claimedAt.minus(lease));
        try {
            return Objects.requireNonNull(kyc.ask(question), "kyc's answer");
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
            case PartyRescreening.Inactive inactive -> unrecorded(unitOfWork, locked, RescreeningPermit.Outcome.SKIPPED,
                    RescreeningObserver.Conclusion.SKIPPED);
            case PartyRescreening.NotOnboarded notOnboarded -> unrecorded(unitOfWork, locked,
                    RescreeningPermit.Outcome.NOT_ONBOARDED, RescreeningObserver.Conclusion.NOT_ONBOARDED);
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
     * True when kyc was <strong>asked</strong> {@code screened} at or after the version came into force - a rescreening
     * under it (`P13-TSK-022`'s review, IMPORTANT 3). Judged by the ask, never the answer: a check dispatched before the
     * activation and decided after it screened the list before this one, and counting it would record a clearance the
     * version never gave - a false clearance. <strong>One clock</strong>: kyc's door stamps a rescreen's ask with the
     * database's {@code clock_timestamp()} ({@code kyc V011}), the clock the activation is stamped on - so the review's
     * interim ±5 s allowance is gone. A permit's own keyed check is asked after its activation by construction (the
     * permit exists only once the version is in force); the rule is kept, never assumed.
     */
    static boolean current(PartyRescreening.Screened screened, RescreeningPermit permit) {
        return !screened.requestedAt().isBefore(permit.listActivatedAt());
    }

    /**
     * Ascending as PostgreSQL orders {@code uuid} - bytewise, so each half unsigned; {@link UUID#compareTo} compares the
     * halves signed and would call a page the database ordered descending wherever the top bit of a half differs.
     */
    public static int databaseOrder(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    /** Done with nothing recorded: the party gone ({@code SKIPPED}), or never onboarded ({@code NOT_ONBOARDED}). */
    private Concluded unrecorded(Connection unitOfWork, RescreeningPermit locked, RescreeningPermit.Outcome outcome,
            RescreeningObserver.Conclusion conclusion) {
        recorded(permits.conclude(unitOfWork, locked.id(), locked.attempts(), outcome, Optional.empty()));
        return new Concluded(locked, conclusion, Optional.empty());
    }

    /**
     * A hit kyc recorded on one of {@code permitId}'s questions, told by kyc's side after its commit (`P13-TSK-022`'s last
     * review, item 3: "a HIT on any of the permit's ordinals wins - and a person is told"), on the caller's unit of work.
     * The permit {@code FOR UPDATE} (R9), then by where it stands:
     *
     * <ul>
     *   <li>{@code PENDING} - a claim asked, and its conclusion may be computing from an answer read before the hit
     *       committed: concluded {@code HIT} here, under the lock - the signal born of the permit, the fact alert, the
     *       fold, exactly as {@link #conclude} would - so the slower conclusion finds it {@code DONE} and records
     *       nothing;</li>
     *   <li>{@code DONE(HIT)} - told already: nothing;</li>
     *   <li>concluded otherwise - {@code CLEARED} from a newer question, {@code SKIPPED}, {@code FAILED} - a <em>late</em>
     *       {@code SCREENING_HIT} signal born of kyc's check (its source the check: the permit already names its one
     *       signal, {@code rescreening_permit_signal_is_its_own}) and its own fact alert (its key carries that signal),
     *       folded into the party's {@code AML} case; the permit stays as it concluded - its history is what it
     *       knew.</li>
     * </ul>
     *
     * <p>Born once whoever tells it and however often: the permit's signal or the check's is unique on its source, and a
     * replay meets {@code ALREADY} and records nothing more. A screening asked before the version's activation is no
     * rescreening under it - never for a permit's own key, kept as the rule. Empty when nothing was recorded.
     */
    public Optional<Concluded> hit(Connection unitOfWork, RescreeningPermitId permitId,
            PartyRescreening.Screened screened) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(permitId, "permitId");
        Objects.requireNonNull(screened, "screened");
        if (!screened.hit()) {
            throw new IllegalArgumentException("only a hit is told after the fact");
        }
        Optional<RescreeningPermit> found = permits.lock(unitOfWork, permitId);
        if (found.isEmpty() || !current(screened, found.get())) {
            return Optional.empty();
        }
        RescreeningPermit locked = found.get();
        Concluded concluded;
        if (locked.status() == RescreeningPermit.Status.PENDING) {
            if (locked.attempts() < 1) {
                // No claim has asked a question yet: the first claim reads the hit.
                return Optional.empty();
            }
            concluded = screened(unitOfWork, locked, screened);
        } else if (locked.outcome().filter(outcome -> outcome == RescreeningPermit.Outcome.HIT).isPresent()) {
            return Optional.empty();
        } else {
            CorrelationId correlation = CorrelationId.of(locked.correlationId());
            CausationId cause = CausationId.of(screened.screeningRef().toString());
            RiskSignal signal = signal(SignalKind.SCREENING_HIT, locked, screened, EventId.of(screened.screeningRef()),
                    correlation, cause);
            if (signals.record(unitOfWork, signal) != RiskSignals.Outcome.BORN) {
                // Told already - the late signal and its alert commit together.
                return Optional.empty();
            }
            alerts.raiseAll(unitOfWork, List.of(hitAlert(locked, signal.id(), correlation, cause)));
            concluded = new Concluded(locked, RescreeningObserver.Conclusion.LATE_HIT, Optional.of(signal.id()));
        }
        observer.concluded(concluded.conclusion());
        return Optional.of(concluded);
    }

    /** A clearance signal about the permit's party under its version, naming kyc's screening; its source {@code source}. */
    private RiskSignal signal(SignalKind kind, RescreeningPermit locked, PartyRescreening.Screened screened,
            EventId source, CorrelationId correlation, CausationId cause) {
        return new RiskSignal(
                RiskSignalId.next(ids),
                kind,
                SignalSubject.of(SubjectKind.PARTY, locked.partyId()),
                new RiskSignal.SourceEvent(source, kind.sourceEventType(), 1, RiskSignals.PRODUCER, correlation, cause),
                screened.decidedAt(),
                SignalAttributes.of(kind, Map.of(
                        "list_version_ref", locked.listVersionId().value().toString(),
                        "screening_ref", screened.screeningRef().toString())));
    }

    private Concluded screened(Connection unitOfWork, RescreeningPermit locked, PartyRescreening.Screened screened) {
        SignalKind kind = screened.hit() ? SignalKind.SCREENING_HIT : SignalKind.SCREENING_CLEARED;
        CorrelationId correlation = CorrelationId.of(locked.correlationId());
        CausationId cause = CausationId.of(locked.id().value().toString());
        RiskSignal signal = signal(kind, locked, screened, EventId.of(locked.id().value()), correlation, cause);
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
        // A fact's alert (risk V018): no policy is read, so none - in force or missing - can leave a hit unalerted.
        alerts.raiseAll(unitOfWork, List.of(hitAlert(locked, signal.id(), correlation, cause)));
        return new Concluded(locked, RescreeningObserver.Conclusion.HIT, Optional.of(signal.id()));
    }

    /**
     * The hit's fact alert - born once per (party, {@code SCREENING_HIT}, the day the version came into force, its one
     * signal), whoever raises it and whenever ({@code INV-RSK-06}); its evidence exactly the signal born of the permit, so
     * one alert per (party, list version) - the review's IMPORTANT 3.
     */
    static NewAlert hitAlert(RescreeningPermit permit, RiskSignalId signal, CorrelationId correlation,
            CausationId cause) {
        return NewAlert.ofFact(AlertKind.SCREENING_HIT, HIT_SEVERITY, SignalSubject.of(SubjectKind.PARTY,
                permit.partyId()), AlertWindow.containing(HIT_WINDOW, permit.listActivatedAt()), signal, correlation,
                cause);
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
