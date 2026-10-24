package com.finapp.risk;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The alert machine over fakes for the hermetic suites (`P13-TSK-015`): the real {@link Alerts} over an
 * {@link InMemoryRiskAlertStore}; the folding a seam the suite sets ({@link AlertFolding#NONE} unless it says
 * otherwise); the acting-party and subject-party ports maps; the audit trail, the outbox and the meters recorded.
 */
final class AlertWorld {

    static final Connection UOW = EvaluationWorld.UOW;
    /** A database instant - a born-once row's stamp the evidence carries (a monitored transaction's recorded_at). */
    static final Instant EVIDENCE_STAMP = Instant.parse("2026-10-25T09:41:17.123456Z");

    final InMemoryRiskAlertStore store = new InMemoryRiskAlertStore();
    final List<AuditRecord> audit = new ArrayList<>();
    final List<EventEnvelope> events = new ArrayList<>();
    final List<Map<String, String>> payloads = new ArrayList<>();
    final Map<String, UUID> partyOfActor = new HashMap<>();
    final Map<SignalSubject, UUID> partyOfSubject = new HashMap<>();
    final Map<AlertKind, Integer> raisedCount = new EnumMap<>(AlertKind.class);
    final Map<SuppressionReason, Integer> suppressedCount = new EnumMap<>(SuppressionReason.class);
    final List<String> spans = new ArrayList<>();

    /** The folding the machine calls - the suite replaces it. */
    AlertFolding folding = AlertFolding.NONE;

    final Alerts alerts;

    AlertWorld() {
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        Spans recorded = new Spans() {
            @Override
            public <T> T within(String name, Map<String, String> identifiers, java.util.function.Supplier<T> work) {
                spans.add(name + identifiers);
                return work.get();
            }
        };
        AlertObserver observer = new AlertObserver() {
            @Override
            public void raised(AlertKind kind, AlertSeverity severity) {
                raisedCount.merge(kind, 1, Integer::sum);
            }

            @Override
            public void suppressed(SuppressionReason reason) {
                suppressedCount.merge(reason, 1, Integer::sum);
            }
        };
        alerts = new Alerts(store, (unitOfWork, born) -> folding.fold(unitOfWork, born),
                (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id())),
                (unitOfWork, subject) -> Optional.ofNullable(partyOfSubject.get(subject)),
                (unitOfWork, record) -> audit.add(record), outbox, EvaluationWorld.IDS, EvaluationWorld.CLOCK,
                recorded, observer);
    }

    // ------------------------------------------------------------------ people

    /** A risk analyst of a fresh party of their own. */
    Actor analyst() {
        Actor actor = new Actor("analyst-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        partyOfActor.put(actor.id(), UUID.randomUUID());
        return actor;
    }

    /** A risk analyst whose party is {@code party}. */
    Actor analystOf(UUID party) {
        Actor actor = new Actor("analyst-" + UUID.randomUUID(), ActorType.EMPLOYEE);
        partyOfActor.put(actor.id(), party);
        return actor;
    }

    static CorrelationId correlation() {
        return CorrelationId.of("flow-" + UUID.randomUUID());
    }

    // ------------------------------------------------------------------ alerts

    static SignalSubject party(UUID party) {
        return SignalSubject.of(SubjectKind.PARTY, party);
    }

    static SignalSubject freshParty() {
        return party(UUID.randomUUID());
    }

    static AlertWindow day() {
        return AlertWindow.containing(WindowKind.D1, EVIDENCE_STAMP);
    }

    static RiskPolicyVersionId policy() {
        return RiskPolicyVersionId.of(UUID.fromString("0192e1a0-0000-7000-8000-000000000015"));
    }

    /** An ATO rule's alert for {@code subject} in {@code window}, evidenced by one assessment. */
    static NewAlert ato(SignalSubject subject, AlertWindow window) {
        return NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH, subject,
                RuleKind.RECENT_CREDENTIAL_CHANGE, window, policy(),
                AlertEvidence.assessment(RiskAssessmentId.of(EvaluationWorld.IDS.next())), correlation(),
                CausationId.of("assessment-suite"));
    }

    /** A monitoring rule's AML alert for {@code subject}, evidenced by journal entries. */
    static NewAlert structuring(SignalSubject subject, AlertWindow window, List<UUID> journalEntries) {
        return NewAlert.ofRule(AlertKind.AML, AlertSeverity.MEDIUM, subject, RuleKind.STRUCTURING, window, policy(),
                AlertEvidence.journalEntries(journalEntries), correlation(), CausationId.of("monitoring-suite"));
    }

    /** An operational alert: a journal entry of an unknown namespace. */
    static NewAlert unclassified(SignalSubject subject, UUID journalEntry) {
        return NewAlert.operational(AlertKind.UNCLASSIFIED_TRANSACTION, AlertSeverity.LOW, subject, day(),
                AlertEvidence.journalEntries(List.of(journalEntry)), correlation(), CausationId.of("intake-suite"));
    }

    /** {@code alert} raised by the machine, born. */
    RiskAlert born(NewAlert alert) {
        Alerts.Raised raised = alerts.raise(UOW, alert);
        if (raised.outcome() != Alerts.Outcome.BORN) {
            throw new IllegalStateException("the world's alert was already raised");
        }
        return raised.alert();
    }

    /** An open ATO alert of a fresh party, whose party is placed. */
    RiskAlert openAlert() {
        SignalSubject subject = freshParty();
        return born(ato(subject, day()));
    }

    /** {@code alert}'s suppression proposed by {@code proposer} with a screened text. */
    RiskAlert proposed(RiskAlert alert, Actor proposer) {
        return alerts.proposeSuppression(UOW, alert.id(), "FALSE_POSITIVE", "the pattern is the customer's payroll",
                proposer, correlation());
    }

    /** The audit records of {@code code}. */
    List<AuditRecord> audited(String code) {
        return audit.stream().filter(record -> record.operation().code().equals(code)).toList();
    }
}
