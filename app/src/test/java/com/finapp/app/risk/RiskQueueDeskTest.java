package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.AlertKind;
import com.finapp.risk.AlertQueue;
import com.finapp.risk.AlertSeverity;
import com.finapp.risk.AlertWindow;
import com.finapp.risk.CaseKind;
import com.finapp.risk.CaseWorklist;
import com.finapp.risk.Cases;
import com.finapp.risk.NewAlert;
import com.finapp.risk.RiskAlert;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskPolicyVersionId;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.RuleKind;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.risk.WindowKind;
import com.finapp.sharedkernel.correlation.CausationId;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The investigator's reads, hermetic (`P13-TSK-018`; {@code PHASE_13_PLAN.md} sections 9, 11; {@code INV-RSK-08};
 * {@code DESIGN-018} D3-D8): the alert queue, the case queue and the case file behind {@code RISK_INVESTIGATE}, keyless
 * reads; each page read on one snapshot and recorded in a transaction of its own <strong>before</strong> the answer; the
 * page's cursor, its bound and the queue's refusals {@code 422 risk.QueueRequestInvalid}; the reader's own party's rows
 * withheld from a page and a file of one {@code 403 risk.SelfDealingRefused}, recorded {@code FAILED}; an unknown or
 * malformed case the uniform {@code 404}; the file's evidence by reference and its downstream actions; no person served.
 * Over HTTP the doors are {@code QueueApiTest}'s (written, owed - C5).
 */
@DisplayName("the queues and the case file: snapshot, then the record, then the answer; self-dealing withheld or refused"
        + " (P13-TSK-018)")
class RiskQueueDeskTest {

    private final RiskActionWorld world = new RiskActionWorld();
    /** Every unit of work asked of the desk, in order: snapshot or transaction. */
    private final List<String> units = new ArrayList<>();
    private final RiskTransactions observed = new RiskTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            units.add("transaction");
            return world.transactions.inTransaction(work);
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            units.add("snapshot");
            return world.transactions.inSnapshot(work);
        }
    };
    private final AlertQueue alertQueue = new AlertQueue(world.alertStore,
            (unitOfWork, actor) -> Optional.ofNullable(world.partyOf.get(actor.id())),
            (unitOfWork, subject) -> Optional.ofNullable(world.partyOfSubject.get(subject)),
            (unitOfWork, record) -> world.audit.add(record), RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK);
    private final CaseWorklist worklist = new CaseWorklist(world.caseStore, world.alertStore, world.actions,
            (unitOfWork, actor) -> Optional.ofNullable(world.partyOf.get(actor.id())),
            (unitOfWork, subject) -> Optional.ofNullable(world.partyOfSubject.get(subject)),
            (unitOfWork, record) -> world.audit.add(record), RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK);
    private final RiskQueueDesk desk = new RiskQueueDesk(alertQueue, worklist, observed, RiskFlowScope.NONE);

    @Test
    @DisplayName("the three reads carry RISK_INVESTIGATE and no idempotency key")
    void theReadsCarryRiskInvestigate() throws NoSuchMethodException {
        for (Method read : List.of(
                RiskAlertController.class.getMethod("readRiskAlertQueue", String.class, String.class, String.class,
                        Integer.class),
                RiskCaseController.class.getMethod("readRiskCaseQueue", String.class, String.class, String.class,
                        Integer.class),
                RiskCaseController.class.getMethod("readRiskCase", String.class))) {
            assertThat(read.getAnnotation(GetMapping.class)).as(read.getName()).isNotNull();
            assertThat(read.getAnnotation(RequiresPermission.class).value()).as(read.getName())
                    .isEqualTo(PermissionName.RISK_INVESTIGATE);
            assertThat(read.getAnnotation(RequiresIdempotencyKey.class)).as(read.getName()).isNull();
        }
    }

    @Test
    @DisplayName("a page of alerts: oldest first, the cursor the last served, read on a snapshot and recorded"
            + " risk.AlertRead in a transaction of its own before the answer; no person served")
    void aPageOfAlerts() {
        List<RiskAlert> born = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            world.alertStore.now = world.alertStore.now.plusSeconds(1);
            born.add(born(SignalSubject.of(SubjectKind.PARTY, RiskEvaluationWorld.IDS.next())));
        }
        Actor reader = world.analyst();
        RiskQueueDesk.RiskAlertPageView first = RiskActionWorld.as(reader, () -> desk.alerts(null, null, null, 2));
        assertThat(first.alerts()).extracting(RiskQueueDesk.RiskAlertView::alertId)
                .containsExactly(id(born.get(0)), id(born.get(1)));
        assertThat(first.next()).isEqualTo(id(born.get(1)));
        assertThat(units).containsExactly("snapshot", "transaction");
        assertThat(world.audited("risk.AlertRead")).singleElement().satisfies(record -> {
            assertThat(record.actor()).isEqualTo(reader);
            assertThat(record.changeSummary().orElseThrow()).contains(id(born.get(0))).contains(id(born.get(1)))
                    .doesNotContain(born.get(0).subject().ref());
        });
        RiskQueueDesk.RiskAlertView view = first.alerts().get(0);
        assertThat(view.kind()).isEqualTo("ACCOUNT_TAKEOVER");
        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.ruleCode()).isEqualTo("RECENT_CREDENTIAL_CHANGE");
        assertThat(view.assessmentIds()).hasSize(1);
        assertThat(view.suppression()).isNull();

        RiskQueueDesk.RiskAlertPageView rest = RiskActionWorld.as(reader, () -> desk.alerts("OPEN", null,
                first.next(), null));
        assertThat(rest.alerts()).extracting(RiskQueueDesk.RiskAlertView::alertId).containsExactly(id(born.get(2)));
        assertThat(rest.next()).isNull();
    }

    @Test
    @DisplayName("the queue's refusals: a status or kind outside the vocabulary, a page below one, a cursor that is no"
            + " identifier, a v4 cursor and one naming no alert are 422 risk.QueueRequestInvalid - nothing recorded;"
            + " a page above the bound is the bound")
    void theQueuesRefusals() {
        Actor reader = world.analyst();
        for (Supplier<?> refused : List.<Supplier<?>>of(
                () -> desk.alerts("open", null, null, null),
                () -> desk.alerts(null, "COLLECTIONS", null, null),
                () -> desk.alerts(null, null, null, 0),
                () -> desk.alerts(null, null, "not-an-id", null),
                () -> desk.alerts(null, null, UUID.randomUUID().toString(), null),
                () -> desk.alerts(null, null, RiskEvaluationWorld.IDS.next().toString(), null),
                () -> desk.cases("CLOSED", null, null, null),
                () -> desk.cases(null, null, RiskEvaluationWorld.IDS.next().toString(), null))) {
            assertThat(refusal(reader, refused).errorCode()).isEqualTo(RiskErrorCode.QUEUE_REQUEST_INVALID);
        }
        assertThat(world.audited("risk.AlertRead")).isEmpty();
        assertThat(world.audited("risk.CaseRead")).isEmpty();
        for (int i = 0; i < 55; i++) {
            world.alertStore.now = world.alertStore.now.plusMillis(1);
            born(SignalSubject.of(SubjectKind.PARTY, RiskEvaluationWorld.IDS.next()));
        }
        assertThat(RiskActionWorld.as(reader, () -> desk.alerts(null, null, null, 500)).alerts()).hasSize(50);
    }

    @Test
    @DisplayName("self-dealing: the reader's own party's alert and case are withheld from the pages; the file of the own"
            + " party's case is 403 risk.SelfDealingRefused, recorded FAILED in a transaction of its own; an unplaced"
            + " reader is refused the page, recorded against the queue")
    void selfDealing() {
        UUID party = RiskEvaluationWorld.IDS.next();
        Actor reader = world.analyst();
        world.partyOf.put(reader.id(), party);
        RiskAlert own = born(SignalSubject.of(SubjectKind.PARTY, party));
        Actor opener = world.analyst();
        RiskCase ownCase = RiskActionWorld.as(opener, () -> world.transactions.inTransaction(uow ->
                world.cases.open(uow, Cases.OpenRequest.of(CaseKind.FRAUD, SignalSubject.of(SubjectKind.PARTY,
                        party)), "a pattern", opener, RiskActionWorld.correlation()).riskCase()));
        assertThat(RiskActionWorld.as(reader, () -> desk.alerts(null, null, null, null)).alerts())
                .extracting(RiskQueueDesk.RiskAlertView::alertId).doesNotContain(id(own));
        assertThat(RiskActionWorld.as(reader, () -> desk.cases(null, null, null, null)).cases())
                .extracting(RiskCaseDesk.RiskCaseView::caseId).doesNotContain(ownCase.id().value().toString());

        units.clear();
        assertThat(refusal(reader, () -> desk.file(ownCase.id().value().toString())).errorCode())
                .isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(units).containsExactly("snapshot", "transaction");
        assertThat(world.audited("risk.SelfDealingRefused")).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(ownCase.id().value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("act=case-read, cause=OWN_PARTY");
        });
        assertThat(world.audited("risk.CaseRead")).as("nothing refused was served").hasSize(1);

        Actor unplaced = new Actor(RiskEvaluationWorld.IDS.next().toString(),
                com.finapp.platform.security.ActorType.CUSTOMER);
        assertThat(refusal(unplaced, () -> desk.alerts(null, null, null, null)).errorCode())
                .isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(world.audited("risk.SelfDealingRefused")).hasSize(2).last().satisfies(record ->
                assertThat(record.targetType()).isEqualTo(AlertQueue.TARGET_TYPE));
    }

    @Test
    @DisplayName("the file: the case's head, its alert, its investigator actions and history, its downstream action and"
            + " its evidence by reference; recorded risk.CaseRead; an unknown or malformed case the uniform 404")
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    void theFile() {
        RiskActionWorld.Taken taken = world.partyCase(CaseKind.FRAUD);
        RiskAssessmentId assessment = RiskAssessmentId.of(RiskEvaluationWorld.IDS.next());
        RiskAlert alert = world.alertStore.insert(RiskEvaluationWorld.UNTOUCHABLE,
                RiskAlertId.next(RiskEvaluationWorld.IDS), NewAlert.ofRule(AlertKind.FRAUD, AlertSeverity.MEDIUM,
                        taken.riskCase().subject(), RuleKind.VELOCITY_COUNT, window(),
                        RiskPolicyVersionId.of(RiskEvaluationWorld.IDS.next()), AlertEvidence.assessment(assessment),
                        RiskActionWorld.correlation(), CausationId.of("file-suite"))).orElseThrow();
        try (com.finapp.platform.security.SecurityContext.Scope platform =
                com.finapp.platform.security.SecurityContext.enterSystem()) {
            world.transactions.inTransaction(uow -> world.cases.fold(uow, alert));
        }
        RiskSignalId signal = RiskSignalId.of(RiskEvaluationWorld.IDS.next());
        RiskActionWorld.as(taken.investigator(), () -> world.transactions.inTransaction(uow -> world.cases
                .attachEvidence(uow, taken.riskCase().id(), world.caseOf(taken.id()).revision(),
                        new AlertEvidence(List.of(signal), List.of(), List.of()), "the device",
                        taken.investigator(), RiskActionWorld.correlation())));
        RiskActionDesk.RiskActionView action = world.propose(taken, "REVOKE_SESSIONS", null);

        Actor reader = world.analyst();
        units.clear();
        RiskQueueDesk.RiskCaseFileView file = RiskActionWorld.as(reader, () -> desk.file(taken.id()));
        assertThat(units).containsExactly("snapshot", "transaction");
        assertThat(file.riskCase().caseId()).isEqualTo(taken.id());
        assertThat(file.riskCase().opened()).isFalse();
        assertThat(file.alerts()).extracting(RiskQueueDesk.RiskAlertView::alertId).containsExactly(id(alert));
        assertThat(file.actions()).extracting(RiskQueueDesk.RiskCaseActionView::kind)
                .contains("TAKEN", "EVIDENCE_ATTACHED", "ACTION_PROPOSED");
        assertThat(file.history()).isNotEmpty();
        assertThat(file.riskActions()).extracting(RiskActionDesk.RiskActionView::actionId)
                .containsExactly(action.actionId());
        assertThat(file.evidence()).containsExactly(
                new RiskQueueDesk.RiskEvidenceView("ASSESSMENT", assessment.value().toString()),
                new RiskQueueDesk.RiskEvidenceView("SIGNAL", signal.value().toString()));
        assertThat(file.toString()).doesNotContain(taken.investigator().id());
        assertThat(world.audited("risk.CaseRead")).singleElement().satisfies(record ->
                assertThat(record.targetId()).isEqualTo(taken.id()));

        assertThat(refusal(reader, () -> desk.file(RiskEvaluationWorld.IDS.next().toString())).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(refusal(reader, () -> desk.file("not-an-id")).errorCode()).isEqualTo(RiskErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a page of cases: the open ones oldest first unless a status is named, recorded risk.CaseRead")
    void aPageOfCases() {
        RiskActionWorld.Taken first = world.partyCase(CaseKind.FRAUD);
        world.caseStore.now = world.caseStore.now.plus(Duration.ofSeconds(1));
        RiskActionWorld.Taken second = world.partyCase(CaseKind.ATO);
        Actor reader = world.analyst();
        RiskQueueDesk.RiskCasePageView page = RiskActionWorld.as(reader, () -> desk.cases(null, null, null, null));
        assertThat(page.cases()).extracting(RiskCaseDesk.RiskCaseView::caseId).containsExactly(first.id(), second.id());
        assertThat(page.next()).isNull();
        assertThat(RiskActionWorld.as(reader, () -> desk.cases("IN_INVESTIGATION", "ATO", null, 1)).cases())
                .extracting(RiskCaseDesk.RiskCaseView::caseId).containsExactly(second.id());
        assertThat(world.audited("risk.CaseRead")).hasSize(2).allSatisfy(record ->
                assertThat(record.targetType()).isEqualTo(CaseWorklist.QUEUE_TARGET_TYPE));
    }

    // -----------------------------------------------------------------

    private RiskAlert born(SignalSubject subject) {
        return world.alertStore.insert(RiskEvaluationWorld.UNTOUCHABLE, RiskAlertId.next(RiskEvaluationWorld.IDS),
                NewAlert.ofRule(AlertKind.ACCOUNT_TAKEOVER, AlertSeverity.HIGH, subject,
                        RuleKind.RECENT_CREDENTIAL_CHANGE, window(),
                        RiskPolicyVersionId.of(RiskEvaluationWorld.IDS.next()),
                        AlertEvidence.assessment(RiskAssessmentId.of(RiskEvaluationWorld.IDS.next())),
                        RiskActionWorld.correlation(), CausationId.of("queue-desk-suite"))).orElseThrow();
    }

    private static AlertWindow window() {
        return AlertWindow.of(new com.finapp.risk.VelocityWindowKey(com.finapp.risk.VelocityDimension.PARTY, RiskEvaluationWorld.IDS.next().toString(), WindowKind.D1, Instant.parse("2026-10-24T00:00:00Z")));
    }

    private static String id(RiskAlert alert) {
        return alert.id().value().toString();
    }

    private static ApiException refusal(Actor actor, Supplier<?> work) {
        ApiException refused = catchThrowableOfType(ApiException.class, () -> RiskActionWorld.as(actor, work));
        assertThat(refused).as("refused on the error contract").isNotNull();
        return refused;
    }
}
