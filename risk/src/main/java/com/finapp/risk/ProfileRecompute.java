package com.finapp.risk;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
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
import java.util.TreeMap;
import java.util.UUID;

/**
 * The customer risk profile's recompute (`P13-TSK-007`; {@code PHASE_13_PLAN.md} sections 7.1 T-mon, 7.2 R8, 12.5;
 * {@code RISK_LIFECYCLES.md} section 4.9; {@code INV-RSK-10}, {@code INV-RSK-07}, {@code INV-HIST-04}) - T-mon's
 * profile leg, one party per transaction, holding no money row.
 *
 * <h2>The order of the locks</h2>
 *
 * <p>The pin first (R1: the {@code MONITORING} version {@code FOR SHARE}, or the shared advisory re-read when none -
 * {@link RiskPolicies#active}, never the door's {@code inForce}), then the head (R8: claimed
 * {@code FOR UPDATE SKIP LOCKED} by the sweep, or locked {@code FOR UPDATE} - waiting - by a direct recompute). Under
 * the head's lock the party's inputs are read ({@link ProfileInputs}), the pinned version is evaluated by
 * {@link RiskEngineV1} - its rating bands map the signal score to {@code LOW}, {@code MEDIUM} or {@code HIGH} - and:
 *
 * <ul>
 *   <li><strong>the rating changed</strong> (or the party had none): version {@code n + 1} born with its whole
 *       evaluation (the pinned version, the engine, the score, the reasons, the sealed inputs and their digest), the
 *       head advanced beside it, {@code risk.CustomerRiskRatingChanged} written - one transaction;</li>
 *   <li><strong>the rating is unchanged</strong>: nothing is written but the permit (`DESIGN-007` D3 - a change of
 *       reasons alone is not a change of rating);</li>
 *   <li><strong>the party is {@code PROHIBITED}</strong>: nothing is evaluated - only a four-eyes disposition (`-016`)
 *       lifts it, and the version trigger refuses a recompute's version after one;</li>
 *   <li><strong>no {@code MONITORING} version is {@code ACTIVE}</strong>: nothing is written but the permit (D2) - the
 *       platform has not chosen to judge, and a rating nobody judged would reach credit's snapshots as an
 *       assessment.</li>
 * </ul>
 *
 * <p><strong>The retrospective account-takeover alerts</strong> (`P13-TSK-013`; {@code PHASE_13_PLAN.md} section 7.1
 * T-mon: "evaluate the monitoring policy …, raise alerts, … recompute the profile"): when the pinned version carries an
 * anchor kind ({@code RECENT_CREDENTIAL_CHANGE}, {@code RECENT_RECOVERY}) and it fired over the party's identities'
 * signals, its {@code ACCOUNT_TAKEOVER} alert is raised in this transaction, under the head's lock, before the rating's
 * branch - whether or not the rating moves ({@link AccountTakeover}: keyed by the anchor's own day, so every later
 * recompute, any other sweeper and T-eval's raise of the same sequence meet it as {@code ALREADY}). <em>The abuse
 * alerts</em> (`P13-TSK-014`, {@link PaymentAbuse#party}): a fired {@code REFUND_RATIO}, {@code CHARGEBACK_RATIO} or
 * {@code BNPL_MULTI_APPLICATION} over the party's signals raises its {@code FRAUD} alert in the same list - keyed by the
 * window's bucket - and its weight moves the rating, which credit's signal answers.
 *
 * <p>So ten sweepers on one party write one version ({@code ProfileDatabaseTest#tenRecomputesOneVersion}): the claim
 * skips a held head; a waiting recompute re-reads the head after the winner's commit, evaluates the same committed
 * inputs, finds the rating unchanged and writes nothing. Every stamp is the database's ({@code clock_timestamp()}); the
 * permit is re-stamped {@code cadence} after it.
 *
 * <p><strong>A recompute that fails</strong> - the engine refusing ({@link EvaluationFailed}), a reading that throws - is
 * {@link RecomputeFailed}, naming the party and never a partial version: the caller's transaction rolls back whole, and
 * the sweep then {@link #defer defers} that party's permit by {@link #FAILURE_BACKOFF} in a transaction of its own, so a
 * party whose recompute keeps failing never stands first in every sweeper's claim and stalls the leg for everyone else.
 * The failure is metered by the sweep; the party is retried after the backoff.
 */
public final class ProfileRecompute {

    public static final String EVENT_TYPE = "risk.CustomerRiskRatingChanged";
    public static final String PRODUCER = "risk";
    public static final int EVENT_VERSION = 1;
    public static final String AGGREGATE_TYPE = "customer_risk_profile";

    /** How long a party whose recompute failed waits before it is claimed again. */
    public static final Duration FAILURE_BACKOFF = Duration.ofMinutes(15);

    /** A recompute that failed for {@link #partyId()} - nothing was written; its transaction is the caller's to roll back. */
    public static final class RecomputeFailed extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient UUID partyId;

        public RecomputeFailed(UUID partyId, RuntimeException cause) {
            // The cause's class only - its message may carry a value from a reading (INV-RSK-08).
            super("a customer risk profile recompute failed: " + cause.getClass().getSimpleName(), cause);
            this.partyId = partyId;
        }

        /** The party whose recompute failed - the one the sweep defers. */
        public UUID partyId() {
            return partyId;
        }
    }

    /** What one recompute did. */
    public enum Kind {
        /** A new version was born: the rating moved, or the party had none. */
        CHANGED,
        /** Evaluated, the rating unchanged: only the permit moved. */
        UNCHANGED,
        /** The party is {@code PROHIBITED}: nothing evaluated, only the permit moved. */
        PROHIBITED_STANDS,
        /** No {@code MONITORING} version is {@code ACTIVE}: nothing judged, only the permit moved. */
        NOT_JUDGED,
        /** An approved disposition's {@code PROHIBITED} version was born (`P13-TSK-016`). */
        PROHIBITED_SET,
        /** The approved disposition's version already stood: nothing written (`P13-TSK-016`). */
        ALREADY_APPLIED
    }

    /**
     * One recompute's outcome - the party, what happened and the head's version and rating after it.
     *
     * @param version the head's version after the recompute (0 when the party is still unassessed)
     */
    public record Outcome(UUID partyId, Kind kind, int version, Optional<RiskRating> rating) {
        public Outcome {
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(rating, "rating");
        }

        /** Identifiers and codes only - never the rating ({@code INV-RSK-08}). */
        @Override
        public String toString() {
            return "Outcome[" + partyId + ", " + kind + ", v" + version + "]";
        }
    }

    private final RiskPolicies policies;
    private final CustomerRiskProfileStore store;
    private final ProfileInputs inputs;
    private final AlertRaiser alerts;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration cadence;
    private final MonitoringObserver observer;

    /**
     * @param alerts the retrospective alerts' raise (`P13-TSK-013`) - {@code alerts::raiseAll} in production
     * @param cadence how long after a recompute the party is due again - the re-assessment interval; a signal's hint
     *     pulls it forward
     */
    public ProfileRecompute(RiskPolicies policies, CustomerRiskProfileStore store, ProfileInputs inputs,
            AlertRaiser alerts, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock, Duration cadence) {
        this(policies, store, inputs, alerts, outbox, ids, clock, cadence, MonitoringObserver.NONE);
    }

    /**
     * @param observer the rule hits' meter (`P13-TSK-020`: {@code finapp.risk.rule.hit{operation=MONITORING,rule}})
     */
    public ProfileRecompute(RiskPolicies policies, CustomerRiskProfileStore store, ProfileInputs inputs,
            AlertRaiser alerts, OutboxWriter<Connection> outbox, IdGenerator ids, Clock clock, Duration cadence,
            MonitoringObserver observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.store = Objects.requireNonNull(store, "store");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cadence = Objects.requireNonNull(cadence, "cadence");
        if (cadence.isNegative() || cadence.isZero()) {
            throw new IllegalArgumentException("the recompute cadence must be positive");
        }
    }

    /**
     * The sweep's leg: the pin, then one due head claimed {@code FOR UPDATE SKIP LOCKED} and recomputed, on the
     * caller's unit of work, under a correlation minted for it.
     *
     * @return the outcome; empty when no head is due (or every due head is held by another sweeper)
     */
    public Optional<Outcome> recomputeNextDue(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Optional<RiskPolicyVersion> pinned = policies.active(unitOfWork, RiskOperation.MONITORING);
        Optional<CustomerRiskProfile> claimed = store.claimDue(unitOfWork);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(recomputeLocked(unitOfWork, claimed.get(), pinned, CorrelationId.generate(ids)));
    }

    /**
     * {@code partyId}'s profile recomputed now - enrolled if missing, its head locked {@code FOR UPDATE} (waiting on a
     * holder): the shape `-016`'s case outcome and `-020`'s monitoring leg call, under their own flow.
     */
    public Outcome recompute(Connection unitOfWork, UUID partyId, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(correlation, "correlation");
        Optional<RiskPolicyVersion> pinned = policies.active(unitOfWork, RiskOperation.MONITORING);
        return recomputeLocked(unitOfWork, store.lockOrCreate(unitOfWork, partyId), pinned, correlation);
    }

    private Outcome recomputeLocked(Connection unitOfWork, CustomerRiskProfile head, Optional<RiskPolicyVersion> pinned,
            CorrelationId correlation) {
        UUID party = head.partyId();
        if (head.prohibited()) {
            store.restamp(unitOfWork, party, cadence, false);
            return new Outcome(party, Kind.PROHIBITED_STANDS, head.currentVersion(), head.rating());
        }
        if (pinned.isEmpty()) {
            store.restamp(unitOfWork, party, cadence, false);
            return new Outcome(party, Kind.NOT_JUDGED, head.currentVersion(), head.rating());
        }
        try {
            return evaluated(unitOfWork, head, pinned.get(), correlation, List.of(), EnumSet.noneOf(RuleKind.class));
        } catch (RuntimeException failure) {
            throw new RecomputeFailed(party, failure);
        }
    }

    /**
     * T-mon's rule leg for one party (`P13-TSK-020`; {@code PHASE_13_PLAN.md} sections 7.1 T-mon, 7.2 R8, 12.7;
     * ADR-0111 decision 3; {@code DESIGN-020} D2, D5, D8, D13, D14) - on the claim's unit of work, under
     * {@code pinned} (the run's R1): the head locked {@code FOR UPDATE} - waiting, born unassessed if missing (R8); then
     * each of {@code touched} - the party's movements the claim's batch read - judged by {@link RiskEngineV1} over the
     * windows of its value date ({@link MonitoredMovements#readDay}, read once per day) with its own amount sealed, each
     * fired AML rule an alert ({@link MonitoringRules}); then the profile's own evaluation as of the database's instant
     * (today's windows among its readings) and its rating's branch - and every alert of the party, the movements' and the
     * profile's, raised in ONE {@link AlertRaiser#raiseAll} (`-015`'s contract: births in key order, then the folds into
     * the party's open case, R4 then R5). A {@code PROHIBITED} party is still judged and alerted - monitoring never goes
     * blind on a prohibited customer - and its rating stands.
     *
     * @throws RecomputeFailed for anything that throws - nothing of the party written; the run rolls its savepoint back
     */
    public Outcome monitor(Connection unitOfWork, UUID partyId, List<MonitoredMovement> touched,
            RiskPolicyVersion pinned, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(touched, "touched");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(correlation, "correlation");
        if (pinned.operation() != RiskOperation.MONITORING) {
            throw new IllegalArgumentException("the rule leg judges under the MONITORING version");
        }
        CustomerRiskProfile head = store.lockOrCreate(unitOfWork, partyId);
        try {
            Set<RuleKind> fired = EnumSet.noneOf(RuleKind.class);
            List<NewAlert> movementAlerts = movementAlerts(unitOfWork, partyId, touched, pinned, correlation, fired);
            if (head.prohibited()) {
                if (!movementAlerts.isEmpty()) {
                    alerts.raiseAll(unitOfWork, movementAlerts);
                }
                observer.fired(fired);
                store.restamp(unitOfWork, partyId, cadence, false);
                return new Outcome(partyId, Kind.PROHIBITED_STANDS, head.currentVersion(), head.rating());
            }
            return evaluated(unitOfWork, head, pinned, correlation, movementAlerts, fired);
        } catch (RuntimeException failure) {
            throw new RecomputeFailed(partyId, failure);
        }
    }

    /** Each touched movement judged over its day's windows; the AML alerts its fired rules raise (unraised yet). */
    private List<NewAlert> movementAlerts(Connection unitOfWork, UUID party, List<MonitoredMovement> touched,
            RiskPolicyVersion pinned, CorrelationId correlation, Set<RuleKind> fired) {
        if (touched.isEmpty() || !MonitoredMovements.judgesTheJournal(pinned.policy())) {
            return List.of();
        }
        MonitoredMovements journal = inputs.journal();
        MonitoredParties.Accounts accounts = journal.accountsOf(unitOfWork, party);
        Map<java.time.LocalDate, MonitoredMovements.Read> days = new TreeMap<>();
        List<NewAlert> raised = new ArrayList<>();
        List<MonitoredMovement> ordered = new ArrayList<>(touched);
        ordered.sort(MonitoredMovement.ORDER);
        for (MonitoredMovement movement : ordered) {
            MonitoredMovements.Read day = days.get(movement.valueDate());
            if (day == null) {
                day = journal.readDay(unitOfWork, party, accounts, pinned.policy(), movement.valueDate());
                days.put(movement.valueDate(), day);
            }
            Verdict verdict = RiskEngineV1.evaluate(pinned.policy(),
                    day.inputs(party, pinned.policy(), Optional.of(movement)));
            verdict.fired().forEach(hit -> fired.add(hit.kind()));
            raised.addAll(MonitoringRules.alerts(verdict, pinned.policy(), day, Optional.of(movement), party,
                    pinned.id(), correlation));
        }
        return List.copyOf(raised);
    }

    /**
     * An approved disposition's {@code PROHIBITED} applied to {@code partyId}'s profile (`P13-TSK-016`;
     * {@code INV-RSK-10}, {@code INV-RSK-07}; {@code DESIGN-016} D3) - <strong>in a transaction of the caller's own,
     * after the case's commits</strong>: the head (R8) locked waiting, born unassessed if missing - and <em>no case row
     * held</em> (the profile leg takes R8 before its folds' R4, so a case act holding R4 must never wait on R8) - then,
     * unless a version already names {@code dispositionId} (the door's retry, the case leg's re-drive, a second instance:
     * nothing written), version {@code n + 1} born {@link ProfileCause#DISPOSITION} {@link RiskRating#PROHIBITED} naming
     * the disposition, the head advanced beside it, and {@code risk.CustomerRiskRatingChanged} when the rating moved (a
     * party already prohibited by another disposition gains the version, not an announcement). One version per
     * disposition: the head's lock and {@code customer_risk_profile_version_one_per_disposition}. The four eyes are the
     * case's ({@code risk_case_disposition_four_eyes}); this act only applies what two persons decided.
     */
    public Outcome applyDisposition(Connection unitOfWork, UUID partyId, UUID dispositionId, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(dispositionId, "dispositionId");
        Objects.requireNonNull(correlation, "correlation");
        CustomerRiskProfile head = store.lockOrCreate(unitOfWork, partyId);
        if (store.versionOfDisposition(unitOfWork, dispositionId).isPresent()) {
            return new Outcome(partyId, Kind.ALREADY_APPLIED, head.currentVersion(), head.rating());
        }
        int next = head.currentVersion() + 1;
        store.insertVersion(unitOfWork, new CustomerRiskProfileVersion(partyId, next, RiskRating.PROHIBITED,
                ProfileCause.DISPOSITION, List.of(), Optional.empty(), Optional.of(dispositionId), correlation.value(),
                Optional.empty()));
        store.advance(unitOfWork, partyId, head.currentVersion(), next, RiskRating.PROHIBITED, cadence);
        if (!head.prohibited()) {
            announce(unitOfWork, partyId, RiskRating.PROHIBITED, next, correlation);
        }
        return new Outcome(partyId, Kind.PROHIBITED_SET, next, Optional.of(RiskRating.PROHIBITED));
    }

    /**
     * The sweep's answer to a {@link RecomputeFailed}: {@code partyId}'s permit re-stamped {@link #FAILURE_BACKOFF}
     * after the database's clock, nothing else moved - in a transaction of the caller's own, after the failed one rolled
     * back. The head's row lock is taken by the {@code UPDATE} (waiting on a holder).
     */
    public void defer(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        store.restamp(unitOfWork, partyId, FAILURE_BACKOFF, false);
    }

    private Outcome evaluated(Connection unitOfWork, CustomerRiskProfile head, RiskPolicyVersion version,
            CorrelationId correlation, List<NewAlert> movementAlerts, Set<RuleKind> fired) {
        UUID party = head.partyId();
        ProfileInputs.Read read = inputs.read(unitOfWork, party, version.policy());
        EvaluationInputs sealed = read.inputs();
        Verdict verdict = RiskEngineV1.evaluate(version.policy(), sealed);
        verdict.fired().forEach(hit -> fired.add(hit.kind()));
        observer.fired(fired);
        RiskRating rating = verdict.rating()
                .orElseThrow(() -> new IllegalStateException("the monitoring verdict is a rating"));
        if (rating.onlyByDisposition()) {
            // INV-RSK-07: the bands cannot produce it (RatingBand, Verdict refuse it); asserted where it would matter.
            throw new IllegalStateException("a recompute never sets " + rating + ": only a disposition does");
        }
        // The retrospective account-takeover alerts (P13-TSK-013): born under the head's lock (R8) in this transaction,
        // keyed by the anchor's own day - a later recompute, a second sweeper or T-eval's raise of the same sequence
        // answers ALREADY. A failed raise fails the recompute whole (RecomputeFailed): the rating and its alert commit
        // together or not at all, and the deferred party is retried.
        // P13-TSK-014: the party's abuse alerts (FRAUD - its refund and chargeback ratios, its BNPL applications) in the
        // same list: one raiseAll for the transaction (-015's contract).
        List<NewAlert> retrospective = new ArrayList<>(AccountTakeover.alerts(verdict, read.anchors(), party,
                version.id(), Optional.empty(), correlation, Optional.empty()));
        retrospective.addAll(PaymentAbuse.party(verdict, version.policy(), read.abuse(), party, version.id(),
                correlation));
        // P13-TSK-020: the AML alerts - today's windows over the profile's own readings, and the rule leg's touched
        // movements' - in the same list: one raiseAll for the transaction. A key raised by both is one alert.
        retrospective.addAll(MonitoringRules.alerts(verdict, version.policy(), read.journal(), Optional.empty(), party,
                version.id(), correlation));
        retrospective.addAll(movementAlerts);
        if (!retrospective.isEmpty()) {
            alerts.raiseAll(unitOfWork, List.copyOf(retrospective));
        }
        if (head.rating().equals(Optional.of(rating))) {
            store.restamp(unitOfWork, party, cadence, true);
            return new Outcome(party, Kind.UNCHANGED, head.currentVersion(), head.rating());
        }
        int next = head.currentVersion() + 1;
        String canonical = CanonicalEvaluation.render(sealed);
        store.insertVersion(unitOfWork, new CustomerRiskProfileVersion(party, next, rating, ProfileCause.RECOMPUTE,
                verdict.reasons(), Optional.of(new CustomerRiskProfileVersion.Evaluation(version.id(), version.version(),
                        verdict.engine(), verdict.score().value(), canonical, CanonicalEvaluation.sha256(canonical))),
                Optional.empty(), correlation.value(), Optional.empty()));
        store.advance(unitOfWork, party, head.currentVersion(), next, rating, cadence);
        announce(unitOfWork, party, rating, next, correlation);
        return new Outcome(party, Kind.CHANGED, next, Optional.of(rating));
    }

    private void announce(Connection unitOfWork, UUID party, RiskRating rating, int version, CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        CustomerRiskProfileId.of(party),
                        AGGREGATE_TYPE,
                        Instant.now(clock),
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                // The party, the rating's code and the version - never a score, a reason or a rule (INV-RSK-08).
                EventPayload.of()
                        .with("party", party.toString())
                        .with("rating", rating.name())
                        .with("version", Integer.toString(version))
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
