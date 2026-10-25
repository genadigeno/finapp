package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Payment, merchant and BNPL abuse - the readings and the alerts both paths share (`P13-TSK-014`;
 * {@code PHASE_13_PLAN.md} sections 12.2, 12.6; {@code RISK_LIFECYCLES.md} section 4.5; ADR-0110; {@code INV-RSK-06},
 * {@code INV-RSK-08}, {@code INV-MON-01}) - {@link AccountTakeover}'s shape.
 *
 * <h2>Two subjects, two paths - each read where it is judged</h2>
 *
 * <p><strong>The merchant</strong> has no profile: its {@link RuleKind#REFUND_RATIO} and {@link RuleKind#CHARGEBACK_RATIO}
 * are judged where its payments are - T-eval of a {@code CARD_PAYMENT} or {@code WALLET_PAYMENT}
 * ({@link #merchant(Connection, SignalReadings, RiskPolicy, UUID, Map, Instant)}): the numerator its
 * {@code REFUND_COMPLETED} / {@code CHARGEBACK_RECEIVED} signals (by {@code merchant_subject_ref}) observed in the rule window's
 * bucket, read after (R2a) - the cut holds the merchant's lock -; the denominator its {@code MERCHANT} window's count for
 * the same bucket, the figure before this movement, read with the policy's keys ({@link VelocityWindows#keysRead}). A
 * fired ratio raises {@link AlertKind#MERCHANT_ABUSE} on the {@code MERCHANT}. <strong>The party</strong> - the
 * customer - is judged by its profile: the profile leg ({@link #party}) reads the party's ratios the same way (its
 * signals, its {@code PARTY} window) and its {@link RuleKind#BNPL_MULTI_APPLICATION} count - the
 * {@code BNPL_APPLICATION_SUBMITTED} signals within the window's length before the recompute's database instant -, and a
 * fired rule raises {@link AlertKind#FRAUD} on the {@code PARTY}; the rules' weights move the rating, and so credit's
 * signal (BNPL is judged through credit, never a seam - plan section 12.1). No key is raised by both paths.
 *
 * <h2>The alert's identity</h2>
 *
 * <p>(subject, rule kind, the rule window's bucket): for a ratio the bucket its figures were read over - the bucket
 * containing T-eval's cut, or the recompute's database instant - so every judgement of one subject in one bucket meets
 * one row whichever instance, retry or sweep raises it; a later bucket over the ratio is a new alert that folds into the
 * same open case (`-016`). For the BNPL count, the bucket containing the latest application's own {@code observed_at} - a
 * stored fact of a born-once row. Severity constant per rule ({@link #severity}), so two raisers of one key agree.
 * Evidence by reference ({@link AlertEvidence}): T-eval's sealed assessment (whose inputs carry the two figures), the
 * profile leg's latest evidence signal. Nothing of a figure, a score or a parameter is copied ({@code INV-RSK-08}).
 */
public final class PaymentAbuse {

    /** The ratio kinds - the merchant's in a payment version, the party's in the monitoring version. */
    public static final Set<RuleKind> RATIOS = Collections.unmodifiableSet(
            EnumSet.of(RuleKind.REFUND_RATIO, RuleKind.CHARGEBACK_RATIO));

    /** Every abuse kind this class reads and raises for. */
    public static final Set<RuleKind> KINDS = Collections.unmodifiableSet(
            EnumSet.of(RuleKind.REFUND_RATIO, RuleKind.CHARGEBACK_RATIO, RuleKind.BNPL_MULTI_APPLICATION));

    /** The operations whose ratios are the merchant's. */
    static final Set<RiskOperation> PAYMENTS = Collections.unmodifiableSet(
            EnumSet.of(RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT));

    private PaymentAbuse() {}

    /** The signal kind an abuse kind counts - its numerator's, and its alert's evidence. */
    public static SignalKind evidenceOf(RuleKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case REFUND_RATIO -> SignalKind.REFUND_COMPLETED;
            case CHARGEBACK_RATIO -> SignalKind.CHARGEBACK_RECEIVED;
            case BNPL_MULTI_APPLICATION -> SignalKind.BNPL_APPLICATION_SUBMITTED;
            default -> throw new IllegalArgumentException(kind + " is not an abuse rule");
        };
    }

    /** Every abuse alert's severity, per rule - constant, so two raisers of one key agree. */
    public static AlertSeverity severity(RuleKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case CHARGEBACK_RATIO -> AlertSeverity.HIGH;
            case REFUND_RATIO, BNPL_MULTI_APPLICATION -> AlertSeverity.MEDIUM;
            default -> throw new IllegalArgumentException(kind + " is not an abuse rule");
        };
    }

    // ------------------------------------------------------------------ T-eval: the merchant

    /**
     * The merchant's ratios a payment {@code policy} judges, read at {@code now} - T-eval's cut (or T-advise's
     * database instant): per ratio rule's window, the merchant's numerator in the bucket containing {@code now} over the
     * {@code MERCHANT} window figure {@code figures} holds for it. A window whose figure is absent (the movement carries
     * no merchant) or zero seals nothing - {@code ABSENT}. Only the ratio maps are filled.
     */
    public static MonitoringReadings merchant(Connection unitOfWork, SignalReadings signals, RiskPolicy policy,
            UUID merchant, Map<VelocityKey, VelocityReading> figures, Instant now) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(signals, "signals");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(figures, "figures");
        Objects.requireNonNull(now, "now");
        Map<WindowKind, Ratio> refunds = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Ratio> chargebacks = new EnumMap<>(WindowKind.class);
        for (RiskRule rule : policy.rules()) {
            if (!RATIOS.contains(rule.kind())) {
                continue;
            }
            WindowKind window = rule.parameters().window("window");
            Map<WindowKind, Ratio> ratios = rule.kind() == RuleKind.REFUND_RATIO ? refunds : chargebacks;
            VelocityReading denominator = figures.get(new VelocityKey(VelocityDimension.MERCHANT, window));
            if (ratios.containsKey(window) || denominator == null) {
                continue;
            }
            int numerator = signals.countByMerchantSince(unitOfWork, merchant, evidenceOf(rule.kind()),
                    WindowStarts.start(window, now));
            AbuseRatios.of(numerator, denominator.count()).ifPresent(ratio -> ratios.put(window, ratio));
        }
        return new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), refunds, chargebacks, Map.of());
    }

    /** The {@code MERCHANT} window keys a payment {@code policy}'s ratio rules divide by - {@link VelocityWindows#keysRead}'s. */
    static Set<VelocityKey> denominatorKeys(RiskPolicy policy) {
        Set<VelocityKey> keys = new java.util.TreeSet<>();
        if (!PAYMENTS.contains(policy.operation())) {
            return keys;
        }
        for (RiskRule rule : policy.rules()) {
            if (RATIOS.contains(rule.kind())) {
                keys.add(new VelocityKey(VelocityDimension.MERCHANT, rule.parameters().window("window")));
            }
        }
        return keys;
    }

    /** True when a payment's sealed hits show a fired ratio - the merchant's alert to raise. */
    static boolean merchantFired(RiskOperation operation, List<RuleHit> hits) {
        return PAYMENTS.contains(operation) && hits.stream().anyMatch(hit -> hit.fired() && RATIOS.contains(hit.kind()));
    }

    /**
     * The {@code MERCHANT_ABUSE} alerts a payment's {@code verdict} raises for {@code merchant}: one per fired ratio
     * rule, keyed (merchant, rule kind, the rule window's bucket containing {@code cutAt}), naming {@code version} and
     * the sealed {@code assessment}. Empty for any other operation or when no ratio fired.
     */
    public static List<NewAlert> merchant(Verdict verdict, RiskPolicy policy, UUID merchant, RiskPolicyVersionId version,
            RiskAssessmentId assessment, Instant cutAt, CorrelationId correlation, CausationId causation) {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(cutAt, "cutAt");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(causation, "causation");
        if (!merchantFired(verdict.operation(), verdict.hits())) {
            return List.of();
        }
        Map<AlertKey, NewAlert> alerts = new LinkedHashMap<>();
        for (RuleHit hit : verdict.hits()) {
            if (!hit.fired() || !RATIOS.contains(hit.kind())) {
                continue;
            }
            WindowKind window = rule(policy, hit).parameters().window("window");
            NewAlert alert = NewAlert.ofRule(AlertKind.MERCHANT_ABUSE, severity(hit.kind()),
                    SignalSubject.of(SubjectKind.MERCHANT, merchant), hit.kind(), AlertWindow.containing(window, cutAt),
                    version, AlertEvidence.assessment(assessment), correlation, causation);
            alerts.putIfAbsent(alert.key(), alert);
        }
        return List.copyOf(alerts.values());
    }

    // ------------------------------------------------------------------ the profile leg: the party

    /**
     * What the profile leg read of a party's abuse: the sealed readings, the database instant they were read at, and per
     * evidence kind the latest signal found - the alert's reference - read only where its count was positive.
     */
    public static final class PartyRead {

        private static final PartyRead NONE = new PartyRead(MonitoringReadings.none(), Optional.empty(), Map.of());

        private final MonitoringReadings readings;
        private final Optional<Instant> at;
        private final Map<SignalKind, RiskSignal> latest;

        private PartyRead(MonitoringReadings readings, Optional<Instant> at, Map<SignalKind, RiskSignal> latest) {
            this.readings = readings;
            this.at = at;
            this.latest = latest;
        }

        /** Nothing read - the version judges no abuse kind. */
        public static PartyRead none() {
            return NONE;
        }

        /** The readings to seal - the ratio and BNPL maps filled, every other monitoring reading {@code ABSENT}. */
        public MonitoringReadings readings() {
            return readings;
        }

        /** The latest signal of {@code kind} the reading found, when its count was positive. */
        public Optional<RiskSignal> latest(SignalKind kind) {
            return Optional.ofNullable(latest.get(kind));
        }

        @Override
        public String toString() {
            // Kinds only: the signals are CONFIDENTIAL evidence, the figures the party's.
            return "PartyRead" + latest.keySet();
        }
    }

    /**
     * The party's abuse readings the {@code MONITORING} {@code policy} judges, read at {@code now} - the recompute's
     * database instant: per ratio rule's window the party's numerator in the bucket containing {@code now} over its
     * {@code PARTY} window's committed count for that bucket ({@link VelocityWindows#counted}, no lock - the recompute
     * holds the head, R8, and counts nothing); per {@code BNPL_MULTI_APPLICATION} window the party's applications
     * observed within the window's length before {@code now}. Nothing for another operation.
     */
    public static PartyRead party(Connection unitOfWork, SignalReadings signals, VelocityWindows windows,
            RiskPolicy policy, UUID party, Instant now) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(signals, "signals");
        Objects.requireNonNull(windows, "windows");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(now, "now");
        if (policy.operation() != RiskOperation.MONITORING
                || policy.rules().stream().noneMatch(rule -> KINDS.contains(rule.kind()))) {
            return PartyRead.none();
        }
        SignalSubject subject = SignalSubject.of(SubjectKind.PARTY, party);
        Map<WindowKind, Ratio> refunds = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Ratio> chargebacks = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Long> applications = new EnumMap<>(WindowKind.class);
        Map<SignalKind, RiskSignal> latest = new EnumMap<>(SignalKind.class);
        for (RiskRule rule : policy.rules()) {
            if (!KINDS.contains(rule.kind())) {
                continue;
            }
            WindowKind window = rule.parameters().window("window");
            SignalKind evidence = evidenceOf(rule.kind());
            long counted;
            if (rule.kind() == RuleKind.BNPL_MULTI_APPLICATION) {
                if (applications.containsKey(window)) {
                    continue;
                }
                counted = signals.countWithin(unitOfWork, subject, evidence, now, window.length());
                applications.put(window, counted);
            } else {
                Map<WindowKind, Ratio> ratios = rule.kind() == RuleKind.REFUND_RATIO ? refunds : chargebacks;
                if (ratios.containsKey(window)) {
                    continue;
                }
                counted = signals.countSince(unitOfWork, subject, evidence, WindowStarts.start(window, now));
                long denominator = windows.counted(unitOfWork, VelocityDimension.PARTY, party.toString(), window, now);
                AbuseRatios.of(counted, denominator).ifPresent(ratio -> ratios.put(window, ratio));
            }
            if (counted > 0 && !latest.containsKey(evidence)) {
                signals.latest(unitOfWork, subject, evidence).ifPresent(signal -> latest.put(evidence, signal));
            }
        }
        return new PartyRead(new MonitoringReadings(Reading.absent(), Map.of(), Map.of(), refunds, chargebacks,
                applications), Optional.of(now), Collections.unmodifiableMap(latest));
    }

    /**
     * The {@code FRAUD} alerts a {@code MONITORING} {@code verdict} raises for {@code party}: one per fired abuse rule,
     * keyed (party, rule kind, the window's bucket - a ratio's the bucket {@code read} was taken over, the BNPL count's the
     * bucket of the latest application), naming {@code version} and the latest evidence signal, caused by that signal's
     * source event. Empty for any other operation or when nothing fired.
     *
     * @throws IllegalStateException for a rule fired over no signal - impossible past the door ({@code threshold_count}
     *     at least one), asserted so it can never raise an alert that names nothing
     */
    public static List<NewAlert> party(Verdict verdict, RiskPolicy policy, PartyRead read, UUID party,
            RiskPolicyVersionId version, CorrelationId correlation) {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(correlation, "correlation");
        if (verdict.operation() != RiskOperation.MONITORING) {
            return List.of();
        }
        Map<AlertKey, NewAlert> alerts = new LinkedHashMap<>();
        for (RuleHit hit : verdict.hits()) {
            if (!hit.fired() || !KINDS.contains(hit.kind())) {
                continue;
            }
            WindowKind window = rule(policy, hit).parameters().window("window");
            RiskSignal evidence = read.latest(evidenceOf(hit.kind())).orElseThrow(() -> new IllegalStateException(
                    hit.kind() + " fired over no signal: an abuse rule fires only over the evidence it counted"));
            Instant at = hit.kind() == RuleKind.BNPL_MULTI_APPLICATION ? evidence.observedAt()
                    : read.at.orElseThrow(() -> new IllegalStateException("a ratio is read at an instant"));
            NewAlert alert = NewAlert.ofRule(AlertKind.FRAUD, severity(hit.kind()),
                    SignalSubject.of(SubjectKind.PARTY, party), hit.kind(), AlertWindow.containing(window, at), version,
                    AlertEvidence.signals(List.of(evidence.id())), correlation,
                    CausationId.of(evidence.source().id().value().toString()));
            alerts.putIfAbsent(alert.key(), alert);
        }
        return List.copyOf(new ArrayList<>(alerts.values()));
    }

    private static RiskRule rule(RiskPolicy policy, RuleHit hit) {
        return policy.rules().stream().filter(rule -> rule.ordinal() == hit.ordinal() && rule.kind() == hit.kind())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "hit " + hit.ordinal() + " (" + hit.kind() + ") names no rule of its version"));
    }
}
