package com.finapp.risk;

import com.finapp.platform.security.ActorType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One alert as stored (`P13-TSK-015`; {@code risk V010}; {@code PHASE_13_PLAN.md} section 3.1; {@code RISK_LIFECYCLES.md}
 * section 4.5; ADR-0110 decision 1): born once per (subject, rule, window start) with its kind, severity, the policy
 * version whose rule raised it and its evidence by reference - all frozen - and its machine: {@link AlertStatus}, the
 * case it attached to, and the four-eyes suppression's proposal and decision. The instants are the database's. A value:
 * the store reads it whole; the edges are {@link Alerts}' and the store's, conditional on the state they move from.
 */
public record RiskAlert(
        RiskAlertId id,
        AlertKind kind,
        AlertSeverity severity,
        SignalSubject subject,
        AlertRule rule,
        AlertWindow window,
        Optional<RiskPolicyVersionId> policyVersion,
        AlertEvidence evidence,
        AlertStatus status,
        Optional<RiskCaseId> attachedCase,
        Optional<Instant> attachedAt,
        Optional<Suppression> suppression,
        Optional<Decision> decision,
        Instant raisedAt,
        String correlationId,
        String causationId) {

    /** A person's proposal to suppress the alert: a closed code, a screened text, the proposer and the stamp. */
    public record Suppression(SuppressionReason reasonCode, String reason, String proposedBy, ActorType proposedByType,
            Instant proposedAt) {
        public Suppression {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(proposedByType, "proposedByType");
            Objects.requireNonNull(proposedAt, "proposedAt");
        }

        @Override
        public String toString() {
            // The text is RESTRICTED-PII and the person CONFIDENTIAL.
            return "Suppression[" + reasonCode + "]";
        }
    }

    /** A second person's approval of the proposal: the decider, the stamp and the decision's reason. */
    public record Decision(String decidedBy, ActorType decidedByType, Instant decidedAt, String reason) {
        public Decision {
            Objects.requireNonNull(decidedBy, "decidedBy");
            Objects.requireNonNull(decidedByType, "decidedByType");
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "Decision[" + decidedByType + "]";
        }
    }

    public RiskAlert {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(attachedCase, "attachedCase");
        Objects.requireNonNull(attachedAt, "attachedAt");
        Objects.requireNonNull(suppression, "suppression");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(raisedAt, "raisedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        if ((status == AlertStatus.ATTACHED) != attachedCase.isPresent() || attachedCase.isPresent() != attachedAt
                .isPresent()) {
            throw new IllegalArgumentException("an alert is ATTACHED exactly when it names its case");
        }
        if (status == AlertStatus.ATTACHED && kind.operational()) {
            throw new IllegalArgumentException("an operational alert opens no case and attaches to none");
        }
        if ((status == AlertStatus.SUPPRESSED) != decision.isPresent()) {
            throw new IllegalArgumentException("an alert is SUPPRESSED exactly when a suppression was approved");
        }
        if (decision.isPresent() && suppression.isEmpty()) {
            throw new IllegalArgumentException("a suppression is approved over a proposal");
        }
        if (decision.isPresent() && decision.get().decidedBy().equals(suppression.get().proposedBy())) {
            throw new IllegalArgumentException("a suppression is two persons' act (INV-RSK-07)");
        }
    }

    /** The alert's identity. */
    public AlertKey key() {
        return new AlertKey(subject.ref(), rule.code(), window.start());
    }

    /** True while the alert awaits a case or a person - the queue's. */
    public boolean open() {
        return status == AlertStatus.OPEN;
    }

    /** True while a proposal to suppress the open alert awaits a second person. */
    public boolean suppressionAwaitsDecision() {
        return open() && suppression.isPresent();
    }

    /** The case kind this alert folds into - empty for an operational alert. */
    public Optional<CaseKind> foldsInto() {
        return kind.caseKind();
    }

    @Override
    public String toString() {
        // Identifiers and codes only: the subject and the evidence are CONFIDENTIAL, the texts RESTRICTED-PII.
        return "RiskAlert[" + id.value() + ", " + kind + ", " + severity + ", " + rule + ", " + window + ", " + status
                + "]";
    }
}
