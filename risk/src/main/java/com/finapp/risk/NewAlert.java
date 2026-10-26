package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a raiser asks {@link Alerts} to raise (`P13-TSK-015`; {@code risk V010}) - judged whole here, the
 * {@code CHECK}s' twin: the alert's identity ({@link #key}: the subject, the rule, the window start - and a {@code SCREENING_HIT}'s one
 * signal, `P13-TSK-022`'s review), its kind and
 * severity, the policy version whose rule raised it and its evidence by reference, and the raiser's flow (correlation,
 * causation). Three shapes, and only three:
 *
 * <ul>
 *   <li>{@link #ofRule} - a policy's rule fired: a {@link RuleKind} as the rule, a rule's {@link AlertKind}, the pinned
 *       policy version, at least one reference;</li>
 *   <li>{@link #operational} - an operational kind ({@code UNCLASSIFIED_TRANSACTION}, {@code VELOCITY_DIVERGENCE}): its
 *       own code as the rule, no policy; it opens no case and is never folded;</li>
 *   <li>{@link #ofFact} (`P13-TSK-025`) - a fact kind ({@code OBLIGATION_DEFAULTED}): its own code as the rule, no
 *       policy, about its kind's {@link AlertKind#factSubject()}, its evidence exactly the one signal that recorded the
 *       fact; it folds into a case like a rule's alert ({@code risk V017}'s
 *       {@code risk_alert_default_names_its_agreement} holds the same for every writer).</li>
 * </ul>
 */
public record NewAlert(
        AlertKind kind,
        AlertSeverity severity,
        SignalSubject subject,
        AlertRule rule,
        AlertWindow window,
        Optional<RiskPolicyVersionId> policyVersion,
        AlertEvidence evidence,
        CorrelationId correlation,
        CausationId causation) {

    public NewAlert {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(causation, "causation");
        if (kind.ownCode() != rule.ownCode() || (kind.ownCode() && !rule.code().equals(kind.name()))) {
            throw new IllegalArgumentException("an operational or a fact's alert names its own kind as its rule; a"
                    + " rule's alert a rule kind");
        }
        if (kind.ownCode() != policyVersion.isEmpty()) {
            throw new IllegalArgumentException("a rule's alert names the policy version that raised it; an operational"
                    + " or a fact's alert names none");
        }
        if (!kind.operational() && evidence.isEmpty()) {
            throw new IllegalArgumentException("a rule's alert names at least one reference of its evidence");
        }
        if (kind.fact() && (subject.kind() != kind.factSubject().orElseThrow() || evidence.signals().size() != 1
                || !evidence.assessments().isEmpty() || !evidence.journalEntries().isEmpty()
                || window.kind() != WindowKind.D1)) {
            throw new IllegalArgumentException("a fact's alert is about its kind's subject, on the day of the fact, and"
                    + " names exactly the one signal that recorded it");
        }
    }

    /**
     * A fact recorded (`P13-TSK-025`): {@code kind}'s alert about {@code subject}, on the {@code D1} window of
     * {@code day}'s start, its evidence {@code signal} alone - no policy, its own code as the rule.
     */
    public static NewAlert ofFact(AlertKind kind, AlertSeverity severity, SignalSubject subject, AlertWindow day,
            RiskSignalId signal, CorrelationId correlation, CausationId causation) {
        return new NewAlert(kind, severity, subject, AlertRule.fact(kind), day, Optional.empty(),
                AlertEvidence.signals(List.of(Objects.requireNonNull(signal, "signal"))), correlation, causation);
    }

    /** A policy's rule fired: {@code rule} of {@code policyVersion} over {@code evidence}. */
    public static NewAlert ofRule(AlertKind kind, AlertSeverity severity, SignalSubject subject, RuleKind rule,
            AlertWindow window, RiskPolicyVersionId policyVersion, AlertEvidence evidence, CorrelationId correlation,
            CausationId causation) {
        return new NewAlert(kind, severity, subject, AlertRule.of(rule), window,
                Optional.of(Objects.requireNonNull(policyVersion, "policyVersion")), evidence, correlation, causation);
    }

    /** An operational alert of {@code kind} - no policy, its own code as the rule. */
    public static NewAlert operational(AlertKind kind, AlertSeverity severity, SignalSubject subject, AlertWindow window,
            AlertEvidence evidence, CorrelationId correlation, CausationId causation) {
        return new NewAlert(kind, severity, subject, AlertRule.operational(kind), window, Optional.empty(), evidence,
                correlation, causation);
    }

    /** The alert's identity - what the unique holds: its one signal too for a kind keyed by it (risk V018). */
    public AlertKey key() {
        return AlertKey.of(kind, subject.ref(), rule.code(), window.start(), evidence);
    }

    @Override
    public String toString() {
        // Codes only: the subject is CONFIDENTIAL.
        return "NewAlert[" + kind + ", " + severity + ", " + rule + ", " + window + "]";
    }
}
