package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Account-takeover detection's two halves that both paths share (`P13-TSK-013`; {@code PHASE_13_PLAN.md} sections 7.1,
 * 12.5, 12.6; {@code RISK_LIFECYCLES.md} section 4.5; ADR-0110; {@code INV-RSK-06}, {@code INV-RSK-08}): reading the
 * anchors, and building the alert. T-eval reads the acting identity's anchors and raises after the seal; the profile
 * leg reads the party's identities' anchors and raises after its evaluation - and because both build the alert from the
 * same born-once signal, <strong>one sequence is one alert whichever path sees it first</strong>.
 *
 * <h2>The alert's identity: (party, anchor rule, the anchor's day)</h2>
 *
 * <p>The subject is the {@link SubjectKind#PARTY} - whose money it is, and what the profile leg judges. The rule is the
 * anchor's kind ({@code RECENT_CREDENTIAL_CHANGE} or {@code RECENT_RECOVERY}). The window is the {@link #ALERT_WINDOW}
 * bucket containing <strong>the anchor signal's {@code observed_at}</strong> - the instant the born-once signal row holds
 * as its own fact, stored once and read identically by every instance, every replay and both paths - never the moment
 * a movement was judged or a sweep ran: ten withdrawals over two days after one credential change are one seizure of
 * control and one alert, and a later credential change is a new alert that folds into the same open {@code ATO} case
 * (`-016`). The severity is {@link #SEVERITY}, constant, so the two raisers of one key never disagree.
 *
 * <p><strong>Evidence by reference</strong> ({@link AlertEvidence}): the anchor signal, and in T-eval the sealed
 * assessment that judged the movement. Nothing of a reading, a score or a parameter is copied ({@code INV-RSK-08}).
 */
public final class AccountTakeover {

    /** The bucket an account-takeover alert speaks for: the anchor's day. */
    public static final WindowKind ALERT_WINDOW = WindowKind.D1;

    /** Every account-takeover alert's severity - constant, so T-eval and the sweep raising one key agree. */
    public static final AlertSeverity SEVERITY = AlertSeverity.HIGH;

    private AccountTakeover() {}

    /**
     * The anchors one judgement read: per anchor signal kind, the latest signal over the identities read - or nothing
     * read at all ({@link #none()}: no identity to read, or the version judges no anchor), which leaves the readings
     * {@code ABSENT}.
     */
    public static final class Anchors {

        private static final Anchors NONE = new Anchors(Map.of(), Set.of());

        private final Map<SignalKind, RiskSignal> latest;
        private final Set<RuleKind> read;

        private Anchors(Map<SignalKind, RiskSignal> latest, Set<RuleKind> read) {
            this.latest = latest;
            this.read = read;
        }

        /** Nothing read - every anchor reading {@code ABSENT}. */
        public static Anchors none() {
            return NONE;
        }

        /**
         * The anchors {@code judged} carries, read over {@code identities} on {@code unitOfWork}: the latest
         * {@code CREDENTIAL_CHANGED} and {@code RECOVERY_COMPLETED} by {@code observed_at} (ties by the signal's id, so
         * every reader picks one). No identity, or no anchor judged, reads nothing.
         */
        public static Anchors read(Connection unitOfWork, SignalReadings signals, Collection<UUID> identities,
                Set<RuleKind> judged) {
            Objects.requireNonNull(unitOfWork, "unitOfWork");
            Objects.requireNonNull(signals, "signals");
            Objects.requireNonNull(identities, "identities");
            Objects.requireNonNull(judged, "judged");
            Set<RuleKind> anchors = judged.stream().filter(AtoSequence.ANCHORS::contains)
                    .collect(java.util.stream.Collectors.toCollection(() -> java.util.EnumSet.noneOf(RuleKind.class)));
            if (identities.isEmpty() || anchors.isEmpty()) {
                return NONE;
            }
            Map<SignalKind, RiskSignal> latest = new EnumMap<>(SignalKind.class);
            for (RuleKind anchor : anchors) {
                SignalKind kind = AtoSequence.anchorSignal(anchor);
                for (UUID identity : identities) {
                    signals.latest(unitOfWork, SignalSubject.of(SubjectKind.IDENTITY, identity), kind)
                            .ifPresent(signal -> latest.merge(kind, signal, Anchors::later));
                }
            }
            return new Anchors(Collections.unmodifiableMap(latest), Collections.unmodifiableSet(anchors));
        }

        private static RiskSignal later(RiskSignal one, RiskSignal other) {
            Comparator<RiskSignal> order = Comparator.comparing(RiskSignal::observedAt)
                    .thenComparing(signal -> signal.id().value());
            return order.compare(one, other) >= 0 ? one : other;
        }

        /**
         * Sets every anchor reading this read on {@code inputs}, judged at {@code now} (the database instant the caller
         * read after its locks): {@code LastSeen} in whole hours, or {@code LastSeen.never()} - read and found none.
         */
        public void applyTo(EvaluationInputs.Builder inputs, Instant now) {
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(now, "now");
            if (read.contains(RuleKind.RECENT_CREDENTIAL_CHANGE)) {
                inputs.credentialChange(lastSeen(latest.get(SignalKind.CREDENTIAL_CHANGED), now));
            }
            if (read.contains(RuleKind.RECENT_RECOVERY)) {
                inputs.recovery(lastSeen(latest.get(SignalKind.RECOVERY_COMPLETED), now));
            }
        }

        /** The signal {@code anchor} was read from, when one was found. */
        public Optional<RiskSignal> of(RuleKind anchor) {
            return Optional.ofNullable(latest.get(AtoSequence.anchorSignal(anchor)));
        }

        private static LastSeen lastSeen(RiskSignal latest, Instant now) {
            if (latest == null) {
                return LastSeen.never();
            }
            long hours = latest.observedAt().isAfter(now) ? 0 : Duration.between(latest.observedAt(), now).toHours();
            return LastSeen.hoursAgo(Math.min(hours, EvaluationInputs.MAX_HOURS));
        }

        @Override
        public String toString() {
            // Kinds only: the signals are CONFIDENTIAL evidence.
            return "Anchors" + latest.keySet();
        }
    }

    /**
     * The alerts {@code verdict} raises for {@code party}: one per anchor whose sequence the hits show
     * ({@link AtoSequence#anchorsShown}), keyed (party, anchor kind, the anchor's day), naming {@code version}, the anchor
     * signal and - in T-eval - {@code assessment}. Empty when the hits show no sequence.
     *
     * @param causation the flow's cause - T-eval's command; the sweep's is each anchor's own source event
     */
    public static List<NewAlert> alerts(Verdict verdict, Anchors anchors, UUID party, RiskPolicyVersionId version,
            Optional<RiskAssessmentId> assessment, CorrelationId correlation, Optional<CausationId> causation) {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(anchors, "anchors");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(causation, "causation");
        List<NewAlert> alerts = new ArrayList<>();
        for (RuleKind anchor : AtoSequence.anchorsShown(verdict.operation(), verdict.hits())) {
            RiskSignal signal = anchors.of(anchor).orElseThrow(() -> new IllegalStateException(
                    anchor + " fired over no signal: an anchor fires only over a reading it found"));
            AlertEvidence evidence = new AlertEvidence(List.of(signal.id()), assessment.map(List::of).orElse(List.of()),
                    List.of());
            alerts.add(NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, SEVERITY, SignalSubject.of(SubjectKind.PARTY, party),
                    anchor, AlertWindow.containing(ALERT_WINDOW, signal.observedAt()), version, evidence, correlation,
                    causation.orElseGet(() -> CausationId.of(signal.source().id().value().toString()))));
        }
        return List.copyOf(alerts);
    }
}
