package com.finapp.risk;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * T-eval and T-advise - the synchronous risk evaluation (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 7.1, 7.2,
 * 7.6, 12.3, 12.4; ADR-0108; {@code RISK_LIFECYCLES.md} section 4.2; {@code INV-RSK-01}, {@code INV-RSK-03},
 * {@code INV-RSK-04}, {@code INV-RSK-05}, {@code INV-CON-03}). Channel-neutral: the seam adapters (`-008`…`-011`, in
 * {@code app}) call it with a {@link RiskEvaluationRequest} on their own connection inside their own transaction after
 * their own locks, and map its answer to their two-value verdict. Stateless - every figure, instant and arbiter is the
 * database's - so any number of instances evaluate one command, one subject, one window as one.
 *
 * <h2>T-eval: {@link #evaluate}, the order</h2>
 *
 * <pre>
 *   find (operation, command_ref)                    a retry is answered with the sealed decision - nothing below runs
 *   [savepoint, no timeout]   (R1) the pin            RiskPolicies.active: FOR SHARE, the A3-I1 shared re-read
 *   [savepoint, 250 ms]       (R2a) cut               the subjects' advisory locks, sorted; then clock_timestamp()
 *                             read                    the windows the policy reads, the profile, the signals, RiskInputs
 *                             evaluate                RiskEngineV1 over the policy and the inputs
 *                             (R2) count              VelocityWindows.increment - ONLY when the decision permits
 *   a failure in a step       its savepoint undone    the fail-safe recorded: the band for the amount, or the compiled
 *                                                     ALLOW; a permitted fail-safe counted behind a savepoint of its own
 *   (R3) seal                 the assessment born     UNIQUE (operation, command_ref); a lost race aborts the caller
 *   announce                  the outbox              risk.RiskDecisionMade; risk.LimitBreached per limit rule breached
 *   [savepoint, 250 ms]       the alerts              the sealed hits' account-takeover (-013) and merchant-abuse
 *                                                     (-014) alerts, one raiseAll
 *   case                      CaseOpener              REVIEW, HOLD - the case of the kind the hits call for (-016)
 * </pre>
 *
 * <p><strong>The alerts</strong> (`P13-TSK-013`; {@link AccountTakeover}, {@link AtoSequence}): when the sealed hits show
 * the account-takeover sequence, its alerts are raised after the seal - births at (R3)'s position, then the folds -
 * through one {@link AlertRaiser#raiseAll}, behind a savepoint of their own under the read budget: a failed or
 * timed-out raise is undone alone and reported ({@link RiskEvaluationObserver#alertsUnraised}), the decision and the
 * caller's money transaction stand. A replay is answered from its seal and raises nothing; a fail-safe has no hits and
 * raises nothing. <em>`P13-TSK-014`</em>: a payment whose merchant's {@code REFUND_RATIO} or {@code CHARGEBACK_RATIO}
 * fired raises its {@code MERCHANT_ABUSE} alerts in the same list ({@link PaymentAbuse}) - keyed (merchant, rule, the
 * window's bucket containing the cut), so every payment to that merchant in the bucket meets one row; one merchant's
 * payments are already serialised on (R2a)'s merchant lock, so the raise never waits on another's uncommitted birth.
 *
 * <p><strong>The pin runs outside the statement timeout</strong>, behind its own savepoint: an evaluation that meets an
 * activation in flight <em>waits</em> for it and pins the successor (ADR-0109 decision 6, the A3-I1 rule); a timeout
 * there would turn every activation into a burst of unpinned fail-safes - the "neither" the pin exists to exclude. The
 * activation holds no money row and commits in milliseconds, so the wait is its own. The reads run under 250 ms per
 * statement in a <em>second</em> savepoint, so undoing them never releases the pin's {@code FOR SHARE}: the fail-safe
 * band is read from a version that cannot retire before the caller commits.
 *
 * <p><strong>Never an aborted caller for risk's trouble</strong>: a failed or timed-out read, a refusing engine and an
 * absent policy are each a recorded decision ({@code INV-RSK-04}). Three failures do abort the caller, deliberately: the
 * idempotency lookup (without it a retry could count twice), the seal (a judged command is never committed without
 * its assessment, {@code INV-RSK-01}) and - `P13-TSK-016` - the case a {@code REVIEW} or {@code HOLD} opens
 * ({@link CaseOpener}: a held or refused movement is never committed without its investigation; fail closed).
 *
 * <p><strong>T-advise ({@link #advise})</strong> is the same evaluation with {@link VelocityWindows#peek} - no lock on a
 * subject, no count, no row, no event - for a door's step-up answer before the command exists. Never authoritative:
 * T-eval judges again under its locks.
 */
public final class RiskEvaluation {

    public static final String DECISION_MADE_EVENT = "risk.RiskDecisionMade";
    public static final String LIMIT_BREACHED_EVENT = "risk.LimitBreached";
    public static final String AGGREGATE_TYPE = "risk_assessment";
    public static final String PRODUCER = "risk";
    public static final int EVENT_VERSION = 1;

    /** The rules whose hard {@code DENY} or {@code HOLD} is a limit breached - an amount or a velocity. */
    static final Set<RuleKind> LIMIT_KINDS = EnumSet.of(RuleKind.AMOUNT_BAND, RuleKind.VELOCITY_COUNT,
            RuleKind.VELOCITY_SUM);

    /** The longest latency a row records - the {@code CHECK}'s hour. */
    static final long MAX_LATENCY_MICROS = 3_600_000_000L;

    private final RiskPolicies policies;
    private final VelocityWindows windows;
    private final CustomerRiskProfiles profiles;
    private final SignalReadings signals;
    private final RiskInputs inputs;
    private final RiskAssessmentStore assessments;
    private final EvaluationBudget budget;
    private final CaseOpener cases;
    private final AlertRaiser alerts;
    private final OutboxWriter<Connection> outbox;
    private final RiskEvaluationObserver observer;
    private final Spans spans;
    private final IdGenerator ids;
    private final Clock clock;

    public RiskEvaluation(RiskPolicies policies, VelocityWindows windows, CustomerRiskProfiles profiles,
            SignalReadings signals, RiskInputs inputs, RiskAssessmentStore assessments, EvaluationBudget budget,
            CaseOpener cases, AlertRaiser alerts, OutboxWriter<Connection> outbox, RiskEvaluationObserver observer,
            Spans spans, IdGenerator ids, Clock clock) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.spans = Objects.requireNonNull(spans, "spans");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ the answers

    /**
     * T-eval's answer - the sealed assessment's decision, as the seam maps it: {@link #permits} → {@code PERMIT}; else
     * {@code REFUSE}, a {@code CHALLENGE} unsatisfied committing {@link ReasonCode#CHALLENGE_REQUIRED}.
     * {@code replayed} is true when the command was already sealed and nothing was evaluated now.
     *
     * <p>{@code limitDecided} (`P13-TSK-008`) answers a consumer with a limit seam beside its risk seam (transfers'
     * {@code TransferLimitCheck}, cross-border's {@code CrossBorderLimitCheck}): true when the decision refuses and is
     * not a {@code CHALLENGE}, and some fired limit rule - {@code AMOUNT_BAND}, {@code VELOCITY_COUNT},
     * {@code VELOCITY_SUM} - carries that very decision as its own hard decision. The decision is the strictest of the
     * band's and every fired hard decision, so this is "a limit rule decided the refusal": a screening {@code DENY}
     * beside a velocity {@code REVIEW} is not a limit refusal; a score band a weighted limit-kind rule pushed is not one
     * (bands are risk's judgement); a fail-safe has no hit and is never one; an unsatisfied {@code CHALLENGE} is always
     * {@code CHALLENGE_REQUIRED}, whichever rule raised it. Derived from the sealed hits, so a replayed answer says the
     * same as the fresh one.
     *
     * <p>{@code holdValidity} (`P13-TSK-009`) answers the one consumer with a native hold - the withdrawal: present
     * exactly for a {@code HOLD}, the pinned version's hold validity (ADR-0109 - policy data, so the consumer's
     * {@code held_until} is the version's, never a constant of its own). A {@code HOLD} always names its version (the
     * fail-safe without one is the compiled {@code ALLOW}), so it is read from the pin, fresh or replayed alike.
     */
    public record Assessed(RiskAssessmentId assessmentId, RiskOperation operation, CommandRef commandRef,
            RiskDecision decision, List<ReasonCode> reasons, Optional<Boolean> challengeSatisfied,
            Optional<ReasonCode> failSafe, VelocityOutcome velocity, boolean replayed, boolean limitDecided,
            Optional<Duration> holdValidity) {

        public Assessed {
            Objects.requireNonNull(assessmentId, "assessmentId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(commandRef, "commandRef");
            Objects.requireNonNull(decision, "decision");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            Objects.requireNonNull(challengeSatisfied, "challengeSatisfied");
            Objects.requireNonNull(failSafe, "failSafe");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(holdValidity, "holdValidity");
            if (limitDecided && (decision == RiskDecision.ALLOW || decision == RiskDecision.CHALLENGE)) {
                throw new IllegalArgumentException("only a refusing decision other than a challenge is decided by a limit");
            }
            if ((decision == RiskDecision.HOLD) != holdValidity.isPresent()) {
                throw new IllegalArgumentException("a hold validity is answered exactly for a HOLD");
            }
        }

        static Assessed of(RiskAssessment sealed, boolean replayed, Optional<Duration> holdValidity) {
            return new Assessed(sealed.id(), sealed.operation(), sealed.commandRef(), sealed.decision(),
                    sealed.reasons(), sealed.challengeSatisfied(), sealed.failSafeReason(), sealed.velocity(), replayed,
                    limitDecided(sealed.decision(), sealed.hits()), holdValidity);
        }

        /** Whether a fired limit rule's own hard decision is the refusing {@code decision} (the record's javadoc). */
        static boolean limitDecided(RiskDecision decision, List<RuleHit> hits) {
            if (decision == RiskDecision.ALLOW || decision == RiskDecision.CHALLENGE) {
                return false;
            }
            return hits.stream().anyMatch(hit -> hit.fired() && LIMIT_KINDS.contains(hit.kind())
                    && hit.hardDecision().filter(decision::equals).isPresent());
        }

        /** True when the movement may proceed: {@code ALLOW}, or a {@code CHALLENGE} the sealed session satisfied. */
        public boolean permits() {
            return VelocityWindows.permits(decision, challengeSatisfied.orElse(false));
        }

        /** True for a {@code CHALLENGE} the session did not satisfy - the seam's {@code CHALLENGE_REQUIRED}. */
        public boolean challengeRequired() {
            return decision == RiskDecision.CHALLENGE && !challengeSatisfied.orElse(false);
        }

        /** The code a refusal carries: {@code CHALLENGE_REQUIRED} for an unsatisfied challenge, else the first reason. */
        public Optional<ReasonCode> refusalReason() {
            if (permits()) {
                return Optional.empty();
            }
            return challengeRequired() ? Optional.of(ReasonCode.CHALLENGE_REQUIRED) : reasons.stream().findFirst();
        }

        @Override
        public String toString() {
            return "Assessed[" + assessmentId.value() + ", " + operation + ", " + decision + (replayed ? ", replayed" : "")
                    + "]";
        }
    }

    /** T-advise's answer - never authoritative, never recorded. */
    public record Advice(RiskOperation operation, RiskDecision decision, List<ReasonCode> reasons,
            Optional<Boolean> challengeSatisfied, Optional<ReasonCode> failSafe) {

        public Advice {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(decision, "decision");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            Objects.requireNonNull(challengeSatisfied, "challengeSatisfied");
            Objects.requireNonNull(failSafe, "failSafe");
        }

        public boolean permits() {
            return VelocityWindows.permits(decision, challengeSatisfied.orElse(false));
        }

        /** True when the door should answer {@code 403 identity.AssuranceRequired} before anything exists. */
        public boolean stepUpRequired() {
            return decision == RiskDecision.CHALLENGE && !challengeSatisfied.orElse(false);
        }
    }

    /**
     * Another transaction sealed this command while this one evaluated it - possible only when the caller did not
     * serialise its own command, and this transaction has already counted its windows: it must not commit. The caller
     * rolls back (its increments with it); its retry finds the sealed assessment.
     */
    public static final class RaceLost extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RaceLost(RiskEvaluationRequest request) {
            super("another transaction sealed " + request.operation() + " command " + request.commandRef()
                    + " first: roll back and retry");
        }
    }

    // ------------------------------------------------------------------ T-eval

    /** T-eval on the caller's {@code unitOfWork}, inside its transaction, after its locks. */
    public Assessed evaluate(Connection unitOfWork, RiskEvaluationRequest request) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(request, "request");
        return spans.within(RiskSpans.EVALUATE, Map.of(), () -> evaluated(unitOfWork, request));
    }

    private Assessed evaluated(Connection unitOfWork, RiskEvaluationRequest request) {
        Optional<RiskAssessment> sealed = assessments.find(unitOfWork, request.operation(), request.commandRef());
        if (sealed.isPresent()) {
            return Assessed.of(sealed.get(), true, holdValidity(unitOfWork, sealed.get()));
        }
        Instant started = clock.instant();
        Judgement judgement = judgement(unitOfWork, request, true);
        RiskAssessment assessment = seal(request, judgement, started);
        if (!assessments.insert(unitOfWork, assessment)) {
            throw new RaceLost(request);
        }
        announce(unitOfWork, request, assessment);
        raiseAlerts(unitOfWork, request, assessment, judgement);
        if (assessment.decision() == RiskDecision.REVIEW || assessment.decision() == RiskDecision.HOLD) {
            // P13-TSK-016: the case of the kind the sealed hits call for - ATO when they show the account-takeover
            // anchors (the alerts above folded first, so the anchor's alert and this decision are one case), else FRAUD.
            // Not behind a savepoint: a REVIEW or HOLD never commits without its case (fail closed).
            cases.open(unitOfWork, new CaseOpener.Decided(assessment.id(), assessment.operation(),
                    assessment.commandRef(), request.party(), assessment.decision(),
                    CaseOpener.Decided.kindFor(assessment.operation(), assessment.hits()), request.correlation()));
        }
        observer.evaluated(new RiskEvaluationObserver.Report(assessment.id(), assessment.operation(),
                assessment.decision(), assessment.unavailable(),
                judgement.verdict().fired().stream().map(RuleHit::reason).toList(), assessment.challengeSatisfied(),
                assessment.velocity(), Duration.ofNanos(assessment.latencyMicros() * 1_000L)));
        return Assessed.of(assessment, false, assessment.decision() == RiskDecision.HOLD
                ? judgement.version().flatMap(version -> version.policy().holdValidity())
                : Optional.empty());
    }

    /**
     * The sealed assessment's alerts - the account-takeover sequence's ({@link AccountTakeover}, `P13-TSK-013`) and a
     * payment's merchant-abuse ratios' ({@link PaymentAbuse}, `P13-TSK-014`), in one list, so the transaction raises
     * through one {@code raiseAll}. Behind a savepoint of its own
     * under the read budget: a raise that fails or waits past it on another transaction's uncommitted birth of the same
     * key is undone alone and reported - the seal, the event and the caller's money transaction stand
     * ({@code -015}'s note on {@link Alerts}: a failed birth would otherwise abort the caller).
     */
    private void raiseAlerts(Connection unitOfWork, RiskEvaluationRequest request, RiskAssessment assessment,
            Judgement judgement) {
        if (judgement.version().isEmpty() || judgement.cause().isPresent()) {
            return;
        }
        boolean takeover = !AtoSequence.anchorsShown(assessment.operation(), assessment.hits()).isEmpty();
        boolean abuse = request.merchant().isPresent() && judgement.cutAt().isPresent()
                && PaymentAbuse.merchantFired(assessment.operation(), assessment.hits());
        if (!takeover && !abuse) {
            return;
        }
        RiskPolicyVersion version = judgement.version().get();
        // The alerts are built inside the savepoint too: nothing of the raise may abort the money transaction. One
        // raiseAll for the transaction - the account-takeover alerts and the merchant's together (-015's contract).
        Attempt<List<Alerts.Raised>> attempt = attempt(unitOfWork, Optional.of(EvaluationBudget.READ_TIMEOUT), () -> {
            List<NewAlert> raised = new ArrayList<>(AccountTakeover.alerts(judgement.verdict(), judgement.anchors(),
                    request.party(), version.id(), Optional.of(assessment.id()), request.correlation(),
                    Optional.of(request.causation())));
            if (abuse) {
                raised.addAll(PaymentAbuse.merchant(judgement.verdict(), version.policy(), request.merchant().get(),
                        version.id(), assessment.id(), judgement.cutAt().get(), request.correlation(),
                        request.causation()));
            }
            return alerts.raiseAll(unitOfWork, List.copyOf(raised));
        });
        if (attempt.failed()) {
            observer.alertsUnraised(assessment.id(), assessment.operation(), attempt.cause().name());
        }
    }

    /**
     * A sealed {@code HOLD}'s hold validity - its pinned version's (`P13-TSK-009`); empty for every other decision. A
     * replay reads the version by its id, unlocked: a decided version never changes ({@code INV-RSK-05}).
     */
    private Optional<Duration> holdValidity(Connection unitOfWork, RiskAssessment sealed) {
        if (sealed.decision() != RiskDecision.HOLD) {
            return Optional.empty();
        }
        RiskAssessment.Pin pin = sealed.pin().orElseThrow(() -> new IllegalStateException(
                "a sealed HOLD names its policy version: only the compiled ALLOW pins none"));
        return policies.version(unitOfWork, pin.id()).policy().holdValidity();
    }

    // ------------------------------------------------------------------ T-advise

    /** T-advise on {@code unitOfWork} - the door's own transaction: the same judgement, nothing counted or written. */
    public Advice advise(Connection unitOfWork, RiskEvaluationRequest request) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(request, "request");
        return spans.within(RiskSpans.ADVISE, Map.of(), () -> {
            Judgement judgement = judgement(unitOfWork, request, false);
            Verdict verdict = judgement.verdict();
            return new Advice(request.operation(), verdict.decision().orElseThrow(), verdict.reasons(),
                    judgement.challengeSatisfied(), judgement.cause().map(UnavailableCause::reason));
        });
    }

    // ------------------------------------------------------------------ the judgement

    /** What one evaluation came to, before it is sealed. */
    private record Judgement(Verdict verdict, EvaluationInputs inputs, Optional<RiskPolicyVersion> version,
            Optional<UnavailableCause> cause, Optional<Boolean> challengeSatisfied, VelocityOutcome velocity,
            Optional<Instant> cutAt, AccountTakeover.Anchors anchors) {}

    /** What the reads came to: the sealed inputs, and the account-takeover anchors they were taken from. */
    private record Read(EvaluationInputs inputs, AccountTakeover.Anchors anchors) {}

    /** The pin, then the judgement - counted when {@code count} (T-eval), peeked when not (T-advise). */
    private Judgement judgement(Connection unitOfWork, RiskEvaluationRequest request, boolean count) {
        Attempt<Optional<RiskPolicyVersion>> pin = attempt(unitOfWork, Optional.empty(),
                () -> policies.active(unitOfWork, request.operation()));
        if (pin.failed()) {
            return unavailable(unitOfWork, request, Optional.empty(), UnavailableCause.PIN_UNREADABLE,
                    RiskEngineV1.failSafeUnpinned(request.operation()), count);
        }
        Optional<RiskPolicyVersion> pinned = pin.value();
        if (pinned.isEmpty()) {
            return unavailable(unitOfWork, request, Optional.empty(), UnavailableCause.NO_POLICY,
                    RiskEngineV1.failSafe(request.operation()), count);
        }
        RiskPolicyVersion version = pinned.get();
        Attempt<Judgement> judged = attempt(unitOfWork, Optional.of(EvaluationBudget.READ_TIMEOUT),
                () -> count ? judged(unitOfWork, request, version) : peeked(unitOfWork, request, version));
        if (judged.failed()) {
            return unavailable(unitOfWork, request, pinned, judged.cause(), band(version.policy(), request.amount()),
                    count);
        }
        return judged.value();
    }

    /** T-eval's step behind the timed savepoint: cut (R2a), read, evaluate, count (R2) when permitted. */
    private Judgement judged(Connection unitOfWork, RiskEvaluationRequest request, RiskPolicyVersion version) {
        RiskPolicy policy = version.policy();
        WindowCut cut = windows.cut(unitOfWork, request.movement());
        Map<VelocityKey, VelocityReading> figures = windows.read(unitOfWork, cut, VelocityWindows.keysRead(policy));
        Read read = read(unitOfWork, request, policy, figures, cut.at());
        EvaluationInputs sealed = read.inputs();
        Verdict verdict = RiskEngineV1.evaluate(policy, sealed);
        RiskDecision decision = verdict.decision().orElseThrow();
        Optional<Boolean> satisfied = challenge(decision, sealed, policy);
        VelocityOutcome velocity = VelocityOutcome.NOT_PERMITTED;
        if (VelocityWindows.permits(decision, satisfied.orElse(false))) {
            windows.increment(unitOfWork, cut, decision, satisfied.orElse(false));
            velocity = VelocityOutcome.COUNTED;
        }
        return new Judgement(verdict, sealed, Optional.of(version), Optional.empty(), satisfied, velocity,
                Optional.of(cut.at()), read.anchors());
    }

    /** T-advise's step: the figures peeked, nothing locked or counted. */
    private Judgement peeked(Connection unitOfWork, RiskEvaluationRequest request, RiskPolicyVersion version) {
        RiskPolicy policy = version.policy();
        Instant now = windows.now(unitOfWork);
        Map<VelocityKey, VelocityReading> figures = windows.peek(unitOfWork, request.movement(),
                VelocityWindows.keysRead(policy));
        Read read = read(unitOfWork, request, policy, figures, now);
        Verdict verdict = RiskEngineV1.evaluate(policy, read.inputs());
        return new Judgement(verdict, read.inputs(), Optional.of(version), Optional.empty(),
                challenge(verdict.decision().orElseThrow(), read.inputs(), policy), VelocityOutcome.NOT_PERMITTED,
                Optional.empty(), read.anchors());
    }

    /**
     * The recorded fail-safe: {@code verdict} over the request's own inputs; a permitting one counted (T-eval) behind a
     * timed savepoint of its own - the money moves, so its windows count it (`-006`'s contract) - and recorded
     * {@link VelocityOutcome#UNCOUNTED} when the count itself fails.
     */
    private Judgement unavailable(Connection unitOfWork, RiskEvaluationRequest request,
            Optional<RiskPolicyVersion> version, UnavailableCause cause, Verdict verdict, boolean count) {
        EvaluationInputs sealed = request.judged().build();
        RiskDecision decision = verdict.decision().orElseThrow();
        if (!count || !VelocityWindows.permits(decision, false)) {
            return new Judgement(verdict, sealed, version, Optional.of(cause), Optional.empty(),
                    VelocityOutcome.NOT_PERMITTED, Optional.empty(), AccountTakeover.Anchors.none());
        }
        Attempt<Instant> counted = attempt(unitOfWork, Optional.of(EvaluationBudget.READ_TIMEOUT), () -> {
            WindowCut cut = windows.cut(unitOfWork, request.movement());
            windows.increment(unitOfWork, cut, decision, false);
            return cut.at();
        });
        return counted.failed()
                ? new Judgement(verdict, sealed, version, Optional.of(cause), Optional.empty(),
                        VelocityOutcome.UNCOUNTED, Optional.empty(), AccountTakeover.Anchors.none())
                : new Judgement(verdict, sealed, version, Optional.of(cause), Optional.empty(),
                        VelocityOutcome.COUNTED, Optional.of(counted.value()), AccountTakeover.Anchors.none());
    }

    /** The policy's fail-safe band for {@code amount} - the open-ended last one when its currency is not the policy's. */
    static Verdict band(RiskPolicy policy, Money amount) {
        return amount.currency().equals(policy.currency())
                ? RiskEngineV1.failSafe(policy, amount)
                : RiskEngineV1.failSafeUnplaced(policy);
    }

    /** A {@code CHALLENGE}'s answer: the sealed session's assurance satisfies the policy's; absent is unsatisfied. */
    static Optional<Boolean> challenge(RiskDecision decision, EvaluationInputs sealed, RiskPolicy policy) {
        if (decision != RiskDecision.CHALLENGE) {
            return Optional.empty();
        }
        RiskAssurance required = policy.challengeAssurance().orElse(RiskAssurance.MULTI_FACTOR);
        return Optional.of(sealed.sessionAssurance().value().map(held -> held.satisfies(required)).orElse(false));
    }

    // ------------------------------------------------------------------ the reads

    /**
     * The inputs {@code policy} judges, read at {@code now} - the cut's database instant: the request's own, the
     * window figures, and - only for a kind the policy carries - the profile's rating, the acting identity's credential
     * change and recovery (the account-takeover anchors, kept for the alert), the source account's age, the payee's and
     * the device's first sighting; then {@link RiskInputs} for the rest.
     *
     * <p><strong>The novelty readings</strong> (`P13-TSK-013`): a beneficiary payee from its registration signal; an
     * account payee and a device from their earliest counted window ({@link VelocityWindows#firstCounted} - the first
     * permitted movement that carried them), read after (R2a), so a subject's own concurrent movements are serialised
     * before it. "Never counted" is {@code FirstSeen.never()}, read and found none - new. A request that carries no
     * device leaves {@code NEW_DEVICE} {@code ABSENT} (no seam carries one today, `-008`), never "new".
     */
    private Read read(Connection unitOfWork, RiskEvaluationRequest request, RiskPolicy policy,
            Map<VelocityKey, VelocityReading> figures, Instant now) {
        Set<RuleKind> judged = kinds(policy);
        EvaluationInputs.Builder in = request.judged();
        figures.forEach(in::velocity);
        if (judged.contains(RuleKind.PROFILE_RATING)) {
            profiles.current(unitOfWork, request.party()).ifPresent(current -> in.profileRating(current.rating()));
        }
        AccountTakeover.Anchors anchors = AccountTakeover.Anchors.read(unitOfWork, signals,
                request.identity().map(List::of).orElse(List.of()), judged);
        anchors.applyTo(in, now);
        if (judged.contains(RuleKind.ACCOUNT_AGE_BELOW)) {
            request.sourceAccount().ifPresent(account -> signals.age(unitOfWork,
                    SignalSubject.of(SubjectKind.ACCOUNT, account), SignalKind.ACCOUNT_OPENED, now)
                    .ifPresent(age -> in.accountAgeDays(days(age))));
        }
        // P13-TSK-014: a payment's merchant ratios - the numerators after (R2a), which holds the merchant's lock; the
        // denominators the MERCHANT figures read with the policy's keys, before this movement.
        if (PaymentAbuse.PAYMENTS.contains(request.operation()) && request.merchant().isPresent()
                && judged.stream().anyMatch(PaymentAbuse.RATIOS::contains)) {
            in.monitoring(PaymentAbuse.merchant(unitOfWork, signals, policy, request.merchant().get(), figures, now));
        }
        RiskInputs.Readings external = inputs.read(unitOfWork, request, judged, now);
        external.sessionAssurance().value().ifPresent(in::sessionAssurance);
        external.screeningHit().value().ifPresent(in::screeningHit);
        external.kycApproved().value().ifPresent(in::kycApproved);
        external.merchantSuspended().value().ifPresent(in::merchantSuspended);
        external.authFailures().forEach(in::authFailures);
        if (judged.contains(RuleKind.NEW_PAYEE)) {
            request.destination().ifPresent(to -> {
                if (to.kind() == RiskEvaluationRequest.DestinationKind.BENEFICIARY) {
                    signals.age(unitOfWork, SignalSubject.of(SubjectKind.BENEFICIARY, to.id()),
                                    SignalKind.BENEFICIARY_REGISTERED, now)
                            .ifPresent(age -> in.payeeFirstSeen(FirstSeen.daysAgo(days(age))));
                } else {
                    in.payeeFirstSeen(firstSeen(unitOfWork, VelocityDimension.DESTINATION, to.id().toString(), now));
                }
            });
        }
        if (judged.contains(RuleKind.NEW_DEVICE)) {
            request.device().ifPresent(device -> in.deviceFirstSeen(
                    firstSeen(unitOfWork, VelocityDimension.DEVICE, device.value(), now)));
        }
        return new Read(in.build(), anchors);
    }

    /** A subject's first sighting in {@code dimension}, judged at {@code now}: whole days since, or never. */
    private FirstSeen firstSeen(Connection unitOfWork, VelocityDimension dimension, String subject, Instant now) {
        return windows.firstCounted(unitOfWork, dimension, subject)
                .map(first -> FirstSeen.daysAgo(days(first.isAfter(now) ? Duration.ZERO : Duration.between(first, now))))
                .orElse(FirstSeen.never());
    }

    private static Set<RuleKind> kinds(RiskPolicy policy) {
        Set<RuleKind> kinds = EnumSet.noneOf(RuleKind.class);
        for (RiskRule rule : policy.rules()) {
            kinds.add(rule.kind());
        }
        return kinds;
    }

    private static int days(Duration age) {
        return (int) Math.min(Math.max(0, age.toDays()), EvaluationInputs.MAX_DAYS);
    }

    // ------------------------------------------------------------------ the seal and the announcement

    private RiskAssessment seal(RiskEvaluationRequest request, Judgement judgement, Instant started) {
        Verdict verdict = judgement.verdict();
        String inputsText = CanonicalEvaluation.render(judgement.inputs());
        String verdictText = CanonicalEvaluation.render(verdict);
        long latency = Math.max(0, Duration.between(started, clock.instant()).toNanos() / 1_000L);
        return new RiskAssessment(
                RiskAssessmentId.next(ids),
                request.operation(),
                request.commandRef(),
                RiskAssessment.Subjects.of(request),
                request.amount(),
                judgement.version().map(version -> new RiskAssessment.Pin(version.id(), version.version())),
                verdict.engine(),
                verdict.score(),
                verdict.decision().orElseThrow(),
                judgement.cause(),
                judgement.challengeSatisfied(),
                judgement.velocity(),
                judgement.cutAt(),
                inputsText,
                CanonicalEvaluation.sha256(inputsText),
                CanonicalEvaluation.sha256(verdictText),
                verdict.hits(),
                verdict.reasons(),
                Math.min(latency, MAX_LATENCY_MICROS),
                request.correlation().value(),
                request.causation().value(),
                Optional.empty());
    }

    /**
     * {@code risk.RiskDecisionMade} - identifiers and codes only, never a score, an amount, a rule's parameter or a text
     * ({@code INV-RSK-08}) - and {@code risk.LimitBreached} for each fired amount or velocity rule whose own decision is
     * {@code DENY} or {@code HOLD}. In the caller's transaction: announced exactly when sealed.
     */
    private void announce(Connection unitOfWork, RiskEvaluationRequest request, RiskAssessment assessment) {
        EventPayload decided = EventPayload.of()
                .with("assessmentId", assessment.id().value().toString())
                .with("operation", assessment.operation().name())
                .with("commandRef", assessment.commandRef().value())
                .with("decision", assessment.decision().name())
                .with("reasonCount", Integer.toString(assessment.reasons().size()));
        for (int i = 0; i < assessment.reasons().size(); i++) {
            decided = decided.with("reason" + (i + 1), assessment.reasons().get(i).name());
        }
        write(unitOfWork, request, assessment, DECISION_MADE_EVENT, decided);
        for (RuleHit hit : assessment.hits()) {
            boolean breached = hit.fired() && LIMIT_KINDS.contains(hit.kind())
                    && hit.hardDecision().filter(d -> d == RiskDecision.DENY || d == RiskDecision.HOLD).isPresent();
            if (breached) {
                write(unitOfWork, request, assessment, LIMIT_BREACHED_EVENT, EventPayload.of()
                        .with("assessmentId", assessment.id().value().toString())
                        .with("operation", assessment.operation().name())
                        .with("ruleCode", hit.reason().name())
                        .with("ruleOrdinal", Integer.toString(hit.ordinal())));
            }
        }
    }

    private void write(Connection unitOfWork, RiskEvaluationRequest request, RiskAssessment assessment, String type,
            EventPayload payload) {
        outbox.write(unitOfWork,
                new EventEnvelope(EventId.next(ids), type, EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION,
                        assessment.id(), AGGREGATE_TYPE, clock.instant(), PRODUCER, request.correlation(),
                        request.causation()),
                payload.toBytes(), EventPayload.MEDIA_TYPE);
    }

    // ------------------------------------------------------------------ the savepoints

    /** One step behind a savepoint: its value, or the cause it failed with - its work undone. */
    private record Attempt<T>(T value, UnavailableCause cause) {
        boolean failed() {
            return cause != null;
        }
    }

    private <T> Attempt<T> attempt(Connection unitOfWork, Optional<Duration> timeout, Supplier<T> step) {
        EvaluationBudget.Mark mark = budget.open(unitOfWork, timeout);
        T value;
        try {
            value = step.get();
            budget.keep(unitOfWork, mark);
        } catch (RuntimeException failed) {
            // The step's rows, locks and timeout are gone; the caller's transaction and everything before the savepoint
            // stand. The failure is recorded as the fail-safe's cause - never rethrown, never silent.
            budget.undo(unitOfWork, mark);
            return new Attempt<>(null, causeOf(failed));
        }
        return new Attempt<>(value, null);
    }

    /** What a failed step's failure was: the engine's refusal, the statement timeout, or any other failed read. */
    static UnavailableCause causeOf(RuntimeException failure) {
        if (failure instanceof EvaluationFailed) {
            return UnavailableCause.ENGINE_REFUSED;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RiskStorageException storage
                    && storage.sqlState().filter(RiskStorageException.QUERY_CANCELED::equals).isPresent()) {
                return UnavailableCause.TIMEOUT;
            }
            if (cause instanceof SQLException sql && RiskStorageException.QUERY_CANCELED.equals(sql.getSQLState())) {
                return UnavailableCause.TIMEOUT;
            }
            // Another module's store behind RiskInputs names its SQLSTATE the platform's way, in its message only.
            if (cause.getMessage() != null && cause.getMessage().endsWith("SQLSTATE " + RiskStorageException.QUERY_CANCELED)) {
                return UnavailableCause.TIMEOUT;
            }
        }
        return UnavailableCause.READ_FAILED;
    }
}
