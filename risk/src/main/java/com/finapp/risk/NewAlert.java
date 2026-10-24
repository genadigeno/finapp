package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Objects;
import java.util.Optional;

/**
 * What a raiser asks {@link Alerts} to raise (`P13-TSK-015`; {@code risk V010}) - judged whole here, the
 * {@code CHECK}s' twin: the alert's identity ({@link #key}: the subject, the rule, the window start), its kind and
 * severity, the policy version whose rule raised it and its evidence by reference, and the raiser's flow (correlation,
 * causation). Two shapes, and only two:
 *
 * <ul>
 *   <li>{@link #ofRule} - a policy's rule fired: a {@link RuleKind} as the rule, a non-operational {@link AlertKind},
 *       the pinned policy version, at least one reference;</li>
 *   <li>{@link #operational} - an operational kind ({@code UNCLASSIFIED_TRANSACTION}, {@code VELOCITY_DIVERGENCE}): its
 *       own code as the rule, no policy; it opens no case and is never folded.</li>
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
        if (kind.operational() != rule.operational() || (kind.operational() && !rule.code().equals(kind.name()))) {
            throw new IllegalArgumentException("an operational alert names its own kind as its rule; a rule's alert a"
                    + " rule kind");
        }
        if (kind.operational() != policyVersion.isEmpty()) {
            throw new IllegalArgumentException("a rule's alert names the policy version that raised it; an operational"
                    + " alert names none");
        }
        if (!kind.operational() && evidence.isEmpty()) {
            throw new IllegalArgumentException("a rule's alert names at least one reference of its evidence");
        }
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

    /** The alert's identity - what the unique holds. */
    public AlertKey key() {
        return new AlertKey(subject.ref(), rule.code(), window.start());
    }

    @Override
    public String toString() {
        // Codes only: the subject is CONFIDENTIAL.
        return "NewAlert[" + kind + ", " + severity + ", " + rule + ", " + window + "]";
    }
}
