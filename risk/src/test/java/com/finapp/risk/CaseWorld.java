package com.finapp.risk;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.risk.testing.InMemoryRiskCaseStore;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The case over fakes for the hermetic suites (`P13-TSK-016`): the real {@link Cases} over an
 * {@link InMemoryRiskCaseStore} and an {@link InMemoryRiskAlertStore}; the real {@link Alerts} whose folding is
 * {@code cases::fold} (the composition's wiring); the real {@link ProfileRecompute} over an
 * {@link InMemoryCustomerRiskProfileStore} for the prohibition; the acting-party and subject-party ports maps; the audit
 * trail, the outbox and the meters recorded. The system's acts run inside {@link #asSystem}.
 */
final class CaseWorld {

    static final Connection UOW = EvaluationWorld.UOW;

    final InMemoryRiskCaseStore caseStore = new InMemoryRiskCaseStore();
    final InMemoryRiskAlertStore alertStore = new InMemoryRiskAlertStore();
    final InMemoryCustomerRiskProfileStore profileStore = new InMemoryCustomerRiskProfileStore();
    final CustomerRiskProfileTest.PolicyStore policyStore = new CustomerRiskProfileTest.PolicyStore();
    final List<AuditRecord> audit = new ArrayList<>();
    final List<EventEnvelope> events = new ArrayList<>();
    final List<Map<String, String>> payloads = new ArrayList<>();
    final Map<String, UUID> partyOfActor = new HashMap<>();
    final Map<SignalSubject, UUID> partyOfSubject = new HashMap<>();
    final Map<CaseKind, Map<CaseStatus, Integer>> moved = new EnumMap<>(CaseKind.class);
    final List<Duration> resolutions = new ArrayList<>();

    final Cases cases;
    final Alerts alerts;
    final ProfileRecompute recompute;

    CaseWorld() {
        caseStore.applied = disposition -> profileStore.versionOfDisposition(UOW, disposition).isPresent();
        OutboxWriter<Connection> outbox = (unitOfWork, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(EventPayload.fields(payload));
        };
        CaseObserver observer = new CaseObserver() {
            @Override
            public void moved(CaseKind kind, CaseStatus status) {
                CaseWorld.this.moved.computeIfAbsent(kind, k -> new EnumMap<>(CaseStatus.class))
                        .merge(status, 1, Integer::sum);
            }

            @Override
            public void resolved(CaseKind kind, Duration openToDisposed) {
                resolutions.add(openToDisposed);
            }
        };
        RiskActingParty acting = (unitOfWork, actor) -> Optional.ofNullable(partyOfActor.get(actor.id()));
        RiskSubjectParties subjects = (unitOfWork, subject) -> Optional.ofNullable(partyOfSubject.get(subject));
        cases = new Cases(caseStore, alertStore, acting, subjects, (unitOfWork, record) -> audit.add(record), outbox,
                EvaluationWorld.IDS, EvaluationWorld.CLOCK, observer);
        alerts = new Alerts(alertStore, cases::fold, acting, subjects, (unitOfWork, record) -> audit.add(record),
                outbox, EvaluationWorld.IDS, EvaluationWorld.CLOCK, Spans.NONE, AlertObserver.NONE);
        recompute = new ProfileRecompute(new RiskPolicies(policyStore), profileStore,
                CustomerRiskProfileTest.inputs(new CustomerRiskProfileTest.Screening()), alerts::raiseAll, outbox,
                EvaluationWorld.IDS, EvaluationWorld.CLOCK, Duration.ofDays(1));
    }

    // ------------------------------------------------------------------ people

    /** A risk analyst of a fresh party of their own. */
    Actor analyst() {
        Actor actor = new Actor("analyst-" + EvaluationWorld.IDS.next(), ActorType.EMPLOYEE);
        partyOfActor.put(actor.id(), EvaluationWorld.IDS.next());
        return actor;
    }

    /** A risk analyst whose party is {@code party}. */
    Actor analystOf(UUID party) {
        Actor actor = new Actor("analyst-" + EvaluationWorld.IDS.next(), ActorType.EMPLOYEE);
        partyOfActor.put(actor.id(), party);
        return actor;
    }

    static CorrelationId correlation() {
        return CorrelationId.of("case-flow-" + EvaluationWorld.IDS.next());
    }

    /** {@code work} as the platform - a leg's scope, the system's openings and folds. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    static <T> T asSystem(Supplier<T> work) {
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return work.get();
        }
    }

    // ------------------------------------------------------------------ cases and alerts

    static SignalSubject party(UUID party) {
        return SignalSubject.of(SubjectKind.PARTY, party);
    }

    /** A party case opened by hand on a fresh party, by a fresh analyst. */
    RiskCase openFraudCase() {
        return cases.open(UOW, Cases.OpenRequest.of(CaseKind.FRAUD, party(EvaluationWorld.IDS.next())),
                "a pattern worth a look", analyst(), correlation()).riskCase();
    }

    /** {@code riskCase} taken by {@code investigator}. */
    RiskCase taken(RiskCase riskCase, Actor investigator) {
        return cases.take(UOW, riskCase.id(), riskCase.revision(), investigator, correlation()).riskCase();
    }

    /** An ATO alert of {@code subject} on the day of {@link AlertWorld#EVIDENCE_STAMP}, raised by the system. */
    Alerts.Raised raiseAto(SignalSubject subject, RuleKind anchor) {
        NewAlert alert = NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH, subject, anchor,
                AlertWorld.day(), AlertWorld.policy(),
                AlertEvidence.assessment(RiskAssessmentId.of(EvaluationWorld.IDS.next())), correlation(),
                CausationId.of("case-suite"));
        return asSystem(() -> alerts.raise(UOW, alert));
    }

    /** A planted OPEN alert - one born before the folding was wired (the drain's backlog). */
    RiskAlert plantedOpen(NewAlert alert) {
        RiskAlert planted = alertStore.insert(UOW, RiskAlertId.next(EvaluationWorld.IDS), alert).orElseThrow();
        alertStore.log.clear();
        return planted;
    }

    /** The MONITORING version active - so a recompute evaluates (`-007`'s fixture). */
    void monitoringActive() {
        policyStore.activate(RiskPolicyFixtures.v1(RiskOperation.MONITORING));
    }

    /** The audit records of {@code code}. */
    List<AuditRecord> audited(String code) {
        return audit.stream().filter(record -> record.operation().code().equals(code)).toList();
    }

    /** The events of {@code type}. */
    List<Map<String, String>> announced(String type) {
        List<Map<String, String>> found = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).eventType().equals(type)) {
                found.add(payloads.get(i));
            }
        }
        return found;
    }

    /** How many times a case of {@code kind} moved into {@code status}. */
    int movedInto(CaseKind kind, CaseStatus status) {
        return moved.getOrDefault(kind, Map.of()).getOrDefault(status, 0);
    }
}
