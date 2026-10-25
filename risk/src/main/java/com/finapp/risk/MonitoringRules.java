package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The AML monitoring rules' alerts (`P13-TSK-020`; {@code PHASE_13_PLAN.md} sections 12.2, 12.7; ADR-0111 decision 3;
 * ADR-0110; {@code DESIGN-020} D8; {@code INV-RSK-06}, {@code INV-RSK-09}, {@code INV-RSK-04}, {@code INV-RSK-08}) -
 * the five kinds and the {@code VELOCITY_*} reuse, judged by {@link RiskEngineV1} over {@link MonitoredMovements}'
 * readings, each fired one raising its {@link AlertKind#AML} alert ({@link AccountTakeover}'s and {@link PaymentAbuse}'s
 * shape).
 *
 * <h2>The alert's identity: (party, rule kind, the window's bucket)</h2>
 *
 * <p>The subject is the {@link SubjectKind#PARTY} - placed by the platform's party join, so the alert reaches the
 * investigator's queue (`-018` withholds what it cannot place). The rule is the kind's code. The window is the bucket
 * of the rule's own {@code window} parameter containing the reading's instant - the touched movement's <em>value
 * date</em>, or the profile recompute's database instant - and for the two movement kinds ({@code ROUND_AMOUNT},
 * {@code CORRIDOR_HIGH_RISK}, which name no window) the {@link #MOVEMENT_WINDOW} of the movement's value date. Never a
 * run's clock: a reprocessing, a late batch, a second sweeper and the profile's own recompute re-derive the same key
 * and meet the standing alert as {@code ALREADY} - one alert per party, rule and window, folded into the party's open
 * {@code AML} case (`-016`). The severity is constant per kind ({@link #severity}), so two raisers of one key agree.
 *
 * <h2>Every hit recorded on the alert's evidence</h2>
 *
 * <p>The evidence is the journal entries of the movements the rule judged, by reference ({@link AlertEvidence}; the
 * journal is the financial truth and a monitored transaction its one-to-one record): {@code STRUCTURING} the window's
 * movements in the policy's money below its {@code below_minor}; {@code RAPID_IN_OUT} the window's movements in the
 * policy's money; {@code DORMANT_THEN_ACTIVE} and {@code VELOCITY_*} the window's movements; the movement kinds the
 * movement - each the first {@link AlertEvidence#BOUND} in the journal's order. A rule that fired over no journal entry
 * yet - a velocity window counted by T-eval whose movements the intake has not read - raises nothing: the run that
 * ingests the window's movement raises it, named. Nothing of a figure, a score or a parameter is copied.
 */
public final class MonitoringRules {

    /** The kinds whose firing is an AML alert - the five and the {@code VELOCITY_*} reuse. */
    public static final Set<RuleKind> KINDS = Collections.unmodifiableSet(EnumSet.of(RuleKind.STRUCTURING,
            RuleKind.RAPID_IN_OUT, RuleKind.DORMANT_THEN_ACTIVE, RuleKind.ROUND_AMOUNT, RuleKind.CORRIDOR_HIGH_RISK,
            RuleKind.VELOCITY_COUNT, RuleKind.VELOCITY_SUM));

    /** The kinds judged over one movement - they name no window, so their alert speaks for the movement's day. */
    public static final Set<RuleKind> MOVEMENT_KINDS = Collections.unmodifiableSet(
            EnumSet.of(RuleKind.ROUND_AMOUNT, RuleKind.CORRIDOR_HIGH_RISK));

    /** The bucket a movement kind's alert speaks for: the movement's value date. */
    public static final WindowKind MOVEMENT_WINDOW = WindowKind.D1;

    private MonitoringRules() {}

    /** True when {@code kind} is an AML kind judged over a window - every one but the movement kinds. */
    static boolean windowed(RuleKind kind) {
        return KINDS.contains(kind) && !MOVEMENT_KINDS.contains(kind);
    }

    /** Every AML alert's severity, per rule - constant, so two raisers of one key agree. */
    public static AlertSeverity severity(RuleKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case STRUCTURING, RAPID_IN_OUT, CORRIDOR_HIGH_RISK -> AlertSeverity.HIGH;
            case DORMANT_THEN_ACTIVE, VELOCITY_COUNT, VELOCITY_SUM -> AlertSeverity.MEDIUM;
            case ROUND_AMOUNT -> AlertSeverity.LOW;
            default -> throw new IllegalArgumentException(kind + " is not a monitoring rule");
        };
    }

    /**
     * The AML alerts a {@code MONITORING} {@code verdict} raises for {@code party} over {@code read} - one per fired AML
     * rule, keyed (party, rule kind, the window's bucket), naming {@code version} and the journal entries the rule judged,
     * caused by {@code movement}'s entry (the rule leg's) or the first evidence entry (the profile's own recompute).
     * Empty for any other operation, when nothing fired, or for a rule fired over no journal entry yet.
     */
    public static List<NewAlert> alerts(Verdict verdict, RiskPolicy policy, MonitoredMovements.Read read,
            Optional<MonitoredMovement> movement, UUID party, RiskPolicyVersionId version, CorrelationId correlation) {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(movement, "movement");
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
            RiskRule rule = rule(policy, hit);
            WindowKind window = MOVEMENT_KINDS.contains(hit.kind()) ? MOVEMENT_WINDOW
                    : rule.parameters().window("window");
            List<UUID> evidence = evidence(hit.kind(), rule, policy, read, window, movement);
            if (evidence.isEmpty()) {
                continue;
            }
            UUID cause = movement.map(MonitoredMovement::journalEntryId).orElse(evidence.getFirst());
            NewAlert alert = NewAlert.ofRule(AlertKind.AML, severity(hit.kind()),
                    SignalSubject.of(SubjectKind.PARTY, party), hit.kind(), AlertWindow.containing(window, read.at()),
                    version, AlertEvidence.journalEntries(evidence), correlation, CausationId.of(cause.toString()));
            alerts.putIfAbsent(alert.key(), alert);
        }
        return List.copyOf(new ArrayList<>(alerts.values()));
    }

    /** The journal entries {@code kind} judged, the first {@link AlertEvidence#BOUND}, distinct, in the journal's order. */
    private static List<UUID> evidence(RuleKind kind, RiskRule rule, RiskPolicy policy, MonitoredMovements.Read read,
            WindowKind window, Optional<MonitoredMovement> movement) {
        if (MOVEMENT_KINDS.contains(kind)) {
            return movement.map(moved -> List.of(moved.journalEntryId())).orElse(List.of());
        }
        Set<UUID> entries = new LinkedHashSet<>();
        for (MonitoredMovement judged : read.window(window)) {
            boolean counted = switch (kind) {
                case STRUCTURING -> judged.inMoneyOf(policy.currency())
                        && judged.amountMinor() < rule.parameters().minorAmount("below_minor");
                case RAPID_IN_OUT -> judged.inMoneyOf(policy.currency());
                default -> true;
            };
            if (counted) {
                entries.add(judged.journalEntryId());
            }
            if (entries.size() == AlertEvidence.BOUND) {
                break;
            }
        }
        return List.copyOf(entries);
    }

    private static RiskRule rule(RiskPolicy policy, RuleHit hit) {
        return policy.rules().stream().filter(rule -> rule.ordinal() == hit.ordinal() && rule.kind() == hit.kind())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "hit " + hit.ordinal() + " (" + hit.kind() + ") names no rule of its version"));
    }
}
