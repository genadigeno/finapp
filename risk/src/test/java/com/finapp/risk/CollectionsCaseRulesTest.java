package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A collections case opened on default, hermetically (`P13-TSK-025`; owner decision C3; {@code PHASE_13_PLAN.md} sections
 * 3.1, 12.8; ADR-0110 decision 9; {@code INV-RSK-06}, {@code INV-RSK-02}): the real T-sig ({@link RiskSignals}) with the
 * real {@link CollectionsCases}, over the real {@link Alerts} and {@link Cases} of {@link CaseWorld} - in-memory stores
 * holding V010's born-once and V012's one-open arbiters as the statements apply them, and a signal store born once by
 * source event. Lending's and bnpl's default events are recorded as -005's handlers record them (their signal, built
 * here as the mapping builds it); the agreement's party is the accountable join's answer. The database's half - the
 * real handler and inbox on PostgreSQL, ten instances, the rollback of a stall - is {@code CollectionsCaseDatabaseTest},
 * written and owed (C5).
 */
@DisplayName("a collections case opened on default - facts only (P13-TSK-025)")
class CollectionsCaseRulesTest {

    private static final LocalDate DEFAULTED_ON = LocalDate.parse("2026-10-24");

    private final CaseWorld world = new CaseWorld();
    private final BornOnceSignals signalStore = new BornOnceSignals();
    /** The accountable join, as the world plants it. */
    private final RiskSubjectParties subjects = (unitOfWork, subject) -> Optional.ofNullable(
            world.partyOfSubject.get(subject));
    /** Every alert the meter counted ({@code finapp.risk.alert{kind,severity}}), and every refused default. */
    private final List<AlertKind> alertsCounted = new ArrayList<>();
    private final List<String> unplacedCounted = new ArrayList<>();
    /** The world's alert machine, metered: the case's folding, the world's stores. */
    private final Alerts metered = new Alerts(world.alertStore, world.cases::fold,
            (unitOfWork, actor) -> Optional.ofNullable(world.partyOfActor.get(actor.id())), subjects,
            (unitOfWork, record) -> world.audit.add(record), recordingOutbox(), EvaluationWorld.IDS,
            EvaluationWorld.CLOCK, com.finapp.platform.telemetry.Spans.NONE, new AlertObserver() {
                @Override
                public void raised(AlertKind kind, AlertSeverity severity) {
                    alertsCounted.add(kind);
                }

                @Override
                public void suppressed(SuppressionReason reason) {
                }
            });
    private final RiskSignals signals = new RiskSignals(signalStore, recordingOutbox(), EvaluationWorld.IDS,
            EvaluationWorld.CLOCK, ProfileHints.NONE, new CollectionsCases(metered::raiseAll, subjects,
                    () -> unplacedCounted.add("unplaced")));

    // ------------------------------------------------------------------ the entry's three cases

    @Test
    @DisplayName("a default opens one case: one signal, one OBLIGATION_DEFAULTED alert about the loan - its own code its"
            + " rule, no policy, the signal its one reference, on the default's day - ATTACHED to one COLLECTIONS case"
            + " of the borrower's PARTY, born OPEN on the first line with the kind's SLA; announced and audited as the"
            + " platform; the case meter moved")
    void aDefaultOpensOneCase() {
        UUID party = EvaluationWorld.IDS.next();
        UUID loan = borrowedBy(party);
        RiskSignal loanDefaulted = defaulted(SignalKind.LOAN_DEFAULTED, loan, DEFAULTED_ON);

        assertThat(record(loanDefaulted)).isEqualTo(RiskSignals.Outcome.BORN);

        assertThat(signalStore.rows).hasSize(1);
        RiskAlert alert = world.alertStore.all().get(0);
        assertThat(world.alertStore.all()).hasSize(1);
        assertThat(alert.kind()).isEqualTo(AlertKind.OBLIGATION_DEFAULTED);
        assertThat(alert.subject()).isEqualTo(SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan));
        assertThat(alert.rule().code()).isEqualTo("OBLIGATION_DEFAULTED");
        assertThat(alert.rule().ruleKind()).isEmpty();
        assertThat(alert.policyVersion()).as("a fact, never a policy's finding").isEmpty();
        assertThat(alert.evidence().signals()).containsExactly(loanDefaulted.id());
        assertThat(alert.evidence().assessments()).isEmpty();
        assertThat(alert.evidence().journalEntries()).isEmpty();
        assertThat(alert.window().kind()).isEqualTo(WindowKind.D1);
        assertThat(alert.window().start()).isEqualTo(Instant.parse("2026-10-24T00:00:00Z"));
        assertThat(alert.severity()).isEqualTo(CollectionsCases.SEVERITY);
        assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED);
        assertThat(alert.causationId()).isEqualTo(loanDefaulted.source().id().value().toString());
        assertThat(alert.correlationId()).isEqualTo(loanDefaulted.source().correlation().value());

        RiskCase opened = world.caseStore.all().get(0);
        assertThat(world.caseStore.all()).hasSize(1);
        assertThat(opened.kind()).isEqualTo(CaseKind.COLLECTIONS);
        assertThat(opened.subject()).as("the borrower's party, never the agreement")
                .isEqualTo(CaseWorld.party(party));
        assertThat(opened.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(opened.queue()).isEqualTo(CaseQueue.FIRST_LINE);
        assertThat(opened.level()).isEqualTo(CaseLevel.L1);
        assertThat(opened.slaHours()).isEqualTo(SlaDeadlines.hours(CaseKind.COLLECTIONS)).isEqualTo(168);
        assertThat(opened.openingAlert()).contains(alert.id());
        assertThat(opened.openingAssessment()).isEmpty();
        assertThat(opened.revision()).isZero();
        assertThat(alert.attachedCase()).contains(opened.id());

        assertThat(world.announced(Alerts.EVENT_TYPE)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("kind", "OBLIGATION_DEFAULTED").containsEntry("subjectKind",
                    "CREDIT_AGREEMENT").containsEntry("subjectRef", loan.toString());
        });
        assertThat(world.announced(Cases.OPENED_EVENT)).singleElement().satisfies(event ->
                assertThat(event).containsEntry("kind", "COLLECTIONS").containsEntry("status", "OPEN"));
        assertThat(world.announced(RiskSignals.EVENT_TYPE)).hasSize(1);
        assertThat(world.audited("risk.CaseOpened")).singleElement().satisfies(record -> {
            assertThat(record.actor().type()).isEqualTo(ActorType.SYSTEM);
            assertThat(record.changeSummary().orElseThrow()).contains("kind=COLLECTIONS").contains("from=alert")
                    .doesNotContain(party.toString()).doesNotContain(loan.toString());
        });
        assertThat(world.movedInto(CaseKind.COLLECTIONS, CaseStatus.OPEN)).as("case{kind=COLLECTIONS,status=OPEN}")
                .isEqualTo(1);
        assertThat(alertsCounted).as("alert{kind=OBLIGATION_DEFAULTED}").containsExactly(AlertKind.OBLIGATION_DEFAULTED);
        assertThat(unplacedCounted).isEmpty();
    }

    @Test
    @DisplayName("a second default of one party attaches: a BNPL obligation of the same borrower defaults - its own"
            + " signal and alert, attached to the party's open COLLECTIONS case as a step of its own; no second case")
    void aSecondDefaultOfOnePartyAttaches() {
        UUID party = EvaluationWorld.IDS.next();
        UUID loan = borrowedBy(party);
        UUID obligation = borrowedBy(party);
        record(defaulted(SignalKind.LOAN_DEFAULTED, loan, DEFAULTED_ON));
        RiskSignals.Outcome second = record(defaulted(SignalKind.BNPL_DEFAULTED, obligation, DEFAULTED_ON));

        assertThat(second).isEqualTo(RiskSignals.Outcome.BORN);
        assertThat(signalStore.rows).hasSize(2);
        assertThat(world.alertStore.all()).hasSize(2).allSatisfy(alert -> {
            assertThat(alert.kind()).isEqualTo(AlertKind.OBLIGATION_DEFAULTED);
            assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED);
        });
        assertThat(world.alertStore.all()).extracting(alert -> alert.subject().ref())
                .containsExactlyInAnyOrder(loan.toString(), obligation.toString());
        RiskCase theCase = world.caseStore.all().get(0);
        assertThat(world.caseStore.all()).as("one open per (party, COLLECTIONS)").hasSize(1);
        assertThat(world.alertStore.all()).extracting(alert -> alert.attachedCase().orElseThrow())
                .containsOnly(theCase.id());
        assertThat(theCase.revision()).as("the attach is a step of its own").isEqualTo(1);
        assertThat(world.caseStore.allEvents()).extracting(CaseEvent::kind)
                .containsExactly(CaseEventKind.OPENED, CaseEventKind.ALERT_ATTACHED);
        assertThat(world.announced(Cases.OPENED_EVENT)).hasSize(1);
        assertThat(world.announced(Alerts.EVENT_TYPE)).hasSize(2);
        assertThat(world.audited("risk.CaseOpened")).as("an attach is its history row, never an audited act")
                .hasSize(1);
    }

    @Test
    @DisplayName("the same event twice opens once: the redelivery past the dedupe finds its signal standing, raises over"
            + " the standing row and meets the alert's unique - one signal, one alert, one case, one AlertRaised, one"
            + " CaseOpened; a third delivery the same")
    void theSameEventTwiceOpensOnce() {
        UUID party = EvaluationWorld.IDS.next();
        UUID loan = borrowedBy(party);
        RiskSignal first = defaulted(SignalKind.LOAN_DEFAULTED, loan, DEFAULTED_ON);
        // The redelivery is the same event, so the mapping mints a fresh signal id for it - never the stored one.
        RiskSignal again = sameEvent(first);
        RiskSignal third = sameEvent(first);

        assertThat(record(first)).isEqualTo(RiskSignals.Outcome.BORN);
        assertThat(record(again)).isEqualTo(RiskSignals.Outcome.ALREADY);
        assertThat(record(third)).isEqualTo(RiskSignals.Outcome.ALREADY);

        assertThat(signalStore.rows).hasSize(1);
        assertThat(world.alertStore.all()).as("born once").singleElement().satisfies(alert ->
                assertThat(alert.evidence().signals()).as("the standing row's id, never the redelivery's")
                        .containsExactly(first.id()));
        assertThat(world.caseStore.all()).singleElement().satisfies(riskCase ->
                assertThat(riskCase.revision()).as("nothing attached twice").isZero());
        assertThat(world.announced(Alerts.EVENT_TYPE)).hasSize(1);
        assertThat(world.announced(Cases.OPENED_EVENT)).hasSize(1);
        assertThat(world.announced(RiskSignals.EVENT_TYPE)).hasSize(1);
        assertThat(signalStore.readsBySource).as("the standing row read for each redelivery").isEqualTo(2);
        assertThat(alertsCounted).as("the alert meter counts the one birth").hasSize(1);
    }

    // ------------------------------------------------------------------ replay and re-default

    @Test
    @DisplayName("a default recorded before this build converges on its replay: the signal stands with no alert, the"
            + " event is delivered again - its alert and case are born over the standing signal")
    void aDefaultRecordedBeforeTheAlertExistedOpensItsCaseOnReplay() {
        UUID party = EvaluationWorld.IDS.next();
        UUID obligation = borrowedBy(party);
        RiskSignal recordedEarlier = defaulted(SignalKind.BNPL_DEFAULTED, obligation, DEFAULTED_ON);
        signalStore.rows.add(recordedEarlier);

        assertThat(record(sameEvent(recordedEarlier))).isEqualTo(RiskSignals.Outcome.ALREADY);

        assertThat(world.alertStore.all()).singleElement().satisfies(alert -> {
            assertThat(alert.evidence().signals()).containsExactly(recordedEarlier.id());
            assertThat(alert.status()).isEqualTo(AlertStatus.ATTACHED);
        });
        assertThat(world.caseStore.all()).singleElement().satisfies(riskCase -> {
            assertThat(riskCase.kind()).isEqualTo(CaseKind.COLLECTIONS);
            assertThat(riskCase.subject()).isEqualTo(CaseWorld.party(party));
        });
        assertThat(world.announced(RiskSignals.EVENT_TYPE)).as("the signal was not born again").isEmpty();
    }

    @Test
    @DisplayName("a re-default after a cure is a second default: once the case is closed, the same loan's later default"
            + " raises its own alert (its own day) and opens a NEW case naming the disposed one - never re-opened")
    void aReDefaultAfterTheCaseIsDisposedOpensANewCase() {
        UUID party = EvaluationWorld.IDS.next();
        UUID loan = borrowedBy(party);
        record(defaulted(SignalKind.LOAN_DEFAULTED, loan, DEFAULTED_ON));
        RiskCase first = world.caseStore.all().get(0);
        world.cases.proposeDisposition(CaseWorld.UOW, first.id(), first.revision(), "CLOSED_NO_ACTION", false,
                "cured within the month", world.analyst(), CaseWorld.correlation());

        record(defaulted(SignalKind.LOAN_DEFAULTED, loan, DEFAULTED_ON.plusDays(40)));

        assertThat(world.alertStore.all()).hasSize(2);
        assertThat(world.caseStore.all()).hasSize(2);
        RiskCase second = world.caseStore.all().stream().filter(riskCase -> riskCase.status() == CaseStatus.OPEN)
                .findFirst().orElseThrow();
        assertThat(second.predecessor()).contains(first.id());
        assertThat(world.cases.current(CaseWorld.UOW, first.id()).orElseThrow().status())
                .isEqualTo(CaseStatus.DISPOSED);
    }

    // ------------------------------------------------------------------ the kind's rules (Cases)

    @Test
    @DisplayName("worked by facts only: note, evidence and escalation admitted; CLOSED_NO_ACTION one person's, from OPEN"
            + " too; REFERRED two persons' on a taken case; CLOSED_FALSE_POSITIVE, CONFIRMED_FRAUD, CONFIRMED_SUSPICIOUS"
            + " and PROHIBITED refused 422 risk.CaseRequestInvalid with nothing written")
    void theKindIsWorkedByFactsOnly() {
        RiskCase opened = defaultCase();
        Actor alice = world.analyst();
        Actor bob = world.analyst();
        for (String finding : List.of("CLOSED_FALSE_POSITIVE", "CONFIRMED_FRAUD", "CONFIRMED_SUSPICIOUS")) {
            RiskCase now = world.cases.current(CaseWorld.UOW, opened.id()).orElseThrow();
            assertThatThrownBy(() -> world.cases.proposeDisposition(CaseWorld.UOW, now.id(), now.revision(), finding,
                    false, "a finding", alice, CaseWorld.correlation()))
                    .as(finding).isInstanceOf(Cases.CaseRequestInvalid.class);
        }
        RiskCase taken = world.taken(opened, alice);
        assertThatThrownBy(() -> world.cases.proposeDisposition(CaseWorld.UOW, taken.id(), taken.revision(),
                "CONFIRMED_FRAUD", true, "prohibit them", alice, CaseWorld.correlation()))
                .isInstanceOf(Cases.CaseRequestInvalid.class);
        assertThat(world.cases.current(CaseWorld.UOW, opened.id()).orElseThrow().revision())
                .as("nothing written by a refusal").isEqualTo(taken.revision());

        RiskCase noted = world.cases.note(CaseWorld.UOW, taken.id(), taken.revision(), "borrower contacted lending",
                alice, CaseWorld.correlation()).riskCase();
        RiskCase evidenced = world.cases.attachEvidence(CaseWorld.UOW, noted.id(), noted.revision(),
                AlertEvidence.signals(List.of(RiskSignalId.next(EvaluationWorld.IDS))), "the delinquency history",
                alice, CaseWorld.correlation()).riskCase();
        RiskCase escalated = world.cases.escalate(CaseWorld.UOW, evidenced.id(), evidenced.revision(), "SECOND_LINE",
                "a restructuring candidate", alice, CaseWorld.correlation()).riskCase();
        Cases.Decided referred = world.cases.proposeDisposition(CaseWorld.UOW, escalated.id(), escalated.revision(),
                "REFERRED", false, "to lending's restructuring path", alice, CaseWorld.correlation());
        assertThat(referred.riskCase().status()).as("two persons' act").isEqualTo(CaseStatus.ESCALATED);
        assertThatThrownBy(() -> world.cases.approveDisposition(CaseWorld.UOW, escalated.id(),
                referred.dispositionId(), referred.riskCase().revision(), "agreed", alice, CaseWorld.correlation()))
                .isInstanceOf(Cases.SelfApprovalRefused.class);
        Cases.Decided approved = world.cases.approveDisposition(CaseWorld.UOW, escalated.id(),
                referred.dispositionId(), referred.riskCase().revision(), "agreed", bob, CaseWorld.correlation());
        assertThat(approved.riskCase().status()).isEqualTo(CaseStatus.DISPOSED);
        assertThat(approved.riskCase().disposition().orElseThrow().disposition()).isEqualTo(Disposition.REFERRED);
        assertThat(approved.prohibits()).isFalse();

        RiskCase another = defaultCase();
        Actor platform = new Actor("system", ActorType.SYSTEM);
        world.partyOfActor.put(platform.id(), EvaluationWorld.IDS.next());
        assertThatThrownBy(() -> world.cases.proposeDisposition(CaseWorld.UOW, another.id(), another.revision(),
                "CLOSED_NO_ACTION", false, "the platform closes it", platform, CaseWorld.correlation()))
                .as("a person's act - never the platform's").isInstanceOf(IllegalArgumentException.class);
        // Every operator session is CUSTOMER-typed (the session interceptor's): a person, admitted.
        Actor operator = new Actor("operator-" + EvaluationWorld.IDS.next(), ActorType.CUSTOMER);
        world.partyOfActor.put(operator.id(), EvaluationWorld.IDS.next());
        Cases.Decided closed = world.cases.proposeDisposition(CaseWorld.UOW, another.id(), another.revision(),
                "CLOSED_NO_ACTION", false, "cured", operator, CaseWorld.correlation());
        assertThat(closed.riskCase().status()).as("one person, straight from OPEN").isEqualTo(CaseStatus.DISPOSED);

        assertThat(DispositionRules.COLLECTIONS).containsExactlyInAnyOrder(Disposition.CLOSED_NO_ACTION,
                Disposition.REFERRED);
        for (CaseKind kind : CaseKind.values()) {
            for (Disposition disposition : Disposition.values()) {
                assertThat(DispositionRules.admitted(kind, disposition)).as(kind + " " + disposition)
                        .isEqualTo(kind != CaseKind.COLLECTIONS || DispositionRules.COLLECTIONS.contains(disposition));
            }
        }
    }

    @Test
    @DisplayName("INV-RSK-02: the kind proposes no action - no ActionKind on any subject - and is never opened by hand,"
            + " on a kind and a subject or from an open default alert")
    void theKindProposesNoActionAndIsNeverOpenedByHand() {
        for (ActionKind action : ActionKind.values()) {
            for (SubjectKind subject : SubjectKind.values()) {
                assertThat(ActionRules.admits(CaseKind.COLLECTIONS, subject, action)).as(action + " on " + subject)
                        .isFalse();
            }
        }
        Actor alice = world.analyst();
        assertThatThrownBy(() -> world.cases.open(CaseWorld.UOW, Cases.OpenRequest.of(CaseKind.COLLECTIONS,
                CaseWorld.party(EvaluationWorld.IDS.next())), "a default", alice, CaseWorld.correlation()))
                .isInstanceOf(Cases.CaseRequestInvalid.class);
        UUID loan = borrowedBy(EvaluationWorld.IDS.next());
        RiskSignal signal = defaulted(SignalKind.LOAN_DEFAULTED, loan, DEFAULTED_ON);
        RiskAlert planted = world.plantedOpen(CollectionsCases.alertFor(signal));
        assertThatThrownBy(() -> world.cases.open(CaseWorld.UOW, Cases.OpenRequest.fromAlert(planted.id()),
                "a default", alice, CaseWorld.correlation()))
                .isInstanceOf(Cases.CaseRequestInvalid.class).hasMessageContaining("never by hand");
        assertThat(world.caseStore.all()).isEmpty();
        assertThat(CaseWorld.asSystem(() -> world.cases.drain(CaseWorld.UOW, planted.id())).outcome())
                .as("an open default alert is folded by the drain, as the system")
                .isEqualTo(AlertFolding.Folding.ATTACHED);
        assertThat(world.caseStore.all()).singleElement().satisfies(riskCase ->
                assertThat(riskCase.kind()).isEqualTo(CaseKind.COLLECTIONS));
    }

    @Test
    @DisplayName("fail closed: a defaulted agreement whose party cannot be placed opens no case - the raise throws before"
            + " any alert is born, announced or counted, so T-sig's transaction (the signal and the dedupe with it) is"
            + " refused and the delivery retried; each refusal counted collections.unplaced alone, every retry of a"
            + " stalled partition the same")
    void anUnplaceableBorrowerOpensNothing() {
        UUID orphan = EvaluationWorld.IDS.next();
        RiskSignal stalled = defaulted(SignalKind.LOAN_DEFAULTED, orphan, DEFAULTED_ON);
        for (int retry = 0; retry < 3; retry++) {
            RiskSignal delivery = retry == 0 ? stalled : sameEvent(stalled);
            // The in-memory store does not roll back as the database does: the retries meet the first's row ALREADY.
            assertThatThrownBy(() -> record(delivery))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be placed");
        }
        assertThat(world.alertStore.all()).as("no alert born").isEmpty();
        assertThat(world.caseStore.all()).isEmpty();
        assertThat(alertsCounted).as("finapp.risk.alert never climbs for an alert that does not exist").isEmpty();
        assertThat(world.announced(Alerts.EVENT_TYPE)).isEmpty();
        assertThat(unplacedCounted).as("finapp.risk.collections.unplaced, once per refused delivery").hasSize(3);
    }

    @Test
    @DisplayName("only a default raises: a delinquency change, an account opening or a recovery records its signal and"
            + " raises nothing; and the platform is entered only for a raising kind")
    void onlyADefaultRaises() {
        UUID loan = borrowedBy(EvaluationWorld.IDS.next());
        Map<String, String> condition = new LinkedHashMap<>(condition(DEFAULTED_ON, true));
        condition.put("previous_bucket", "DPD_1_30");
        condition.put("defaulted", "false");
        RiskSignal delinquent = signal(SignalKind.LOAN_DELINQUENCY_CHANGED,
                SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan), "lending.LoanDelinquencyChanged", "lending",
                condition);
        RiskSignal opened = signal(SignalKind.ACCOUNT_OPENED,
                SignalSubject.of(SubjectKind.ACCOUNT, EvaluationWorld.IDS.next()), "accounts.AccountOpened",
                "accounts", Map.of("product", "WALLET"));
        assertThat(signals.record(CaseWorld.UOW, delinquent)).isEqualTo(RiskSignals.Outcome.BORN);
        assertThat(signals.record(CaseWorld.UOW, opened)).isEqualTo(RiskSignals.Outcome.BORN);
        assertThat(world.alertStore.all()).isEmpty();
        assertThat(world.caseStore.all()).isEmpty();
        for (SignalKind kind : SignalKind.values()) {
            assertThat(signals.raises(kind)).as(kind.name()).isEqualTo(kind == SignalKind.LOAN_DEFAULTED
                    || kind == SignalKind.BNPL_DEFAULTED);
        }
        assertThat(new RiskSignals(signalStore, recordingOutbox(), EvaluationWorld.IDS, EvaluationWorld.CLOCK)
                .raises(SignalKind.LOAN_DEFAULTED)).as("the suites' T-sig raises nothing").isFalse();
    }

    @Test
    @DisplayName("the fact's alert, judged whole: its own code its rule, no policy, about the agreement, on a D1 day, its"
            + " evidence exactly one signal - and a signal that records no default (or a flag unset) refused")
    void theFactsAlertIsJudgedWhole() {
        SignalSubject agreement = SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, EvaluationWorld.IDS.next());
        AlertWindow day = AlertWindow.containing(WindowKind.D1, Instant.parse("2026-10-24T00:00:00Z"));
        RiskSignalId signal = RiskSignalId.next(EvaluationWorld.IDS);
        CorrelationId flow = CorrelationId.of("flow");
        CausationId cause = CausationId.of("cause");
        assertThat(NewAlert.ofFact(AlertKind.OBLIGATION_DEFAULTED, AlertSeverity.MEDIUM, agreement, day, signal, flow,
                cause).key()).isEqualTo(new AlertKey(agreement.ref(), "OBLIGATION_DEFAULTED", day.start()));
        assertThatThrownBy(() -> NewAlert.ofFact(AlertKind.OBLIGATION_DEFAULTED, AlertSeverity.MEDIUM,
                CaseWorld.party(EvaluationWorld.IDS.next()), day, signal, flow, cause))
                .as("about the agreement").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewAlert.ofFact(AlertKind.OBLIGATION_DEFAULTED, AlertSeverity.MEDIUM, agreement,
                AlertWindow.containing(WindowKind.H1, Instant.parse("2026-10-24T05:00:00Z")), signal, flow, cause))
                .as("on the day").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewAlert(AlertKind.OBLIGATION_DEFAULTED, AlertSeverity.MEDIUM, agreement,
                AlertRule.fact(AlertKind.OBLIGATION_DEFAULTED), day, Optional.of(AlertWorld.policy()),
                AlertEvidence.signals(List.of(signal)), flow, cause)).as("no policy")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewAlert(AlertKind.OBLIGATION_DEFAULTED, AlertSeverity.MEDIUM, agreement,
                AlertRule.of(RuleKind.AMOUNT_BAND), day, Optional.empty(), AlertEvidence.signals(List.of(signal)),
                flow, cause)).as("its own code").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewAlert(AlertKind.OBLIGATION_DEFAULTED, AlertSeverity.MEDIUM, agreement,
                AlertRule.fact(AlertKind.OBLIGATION_DEFAULTED), day, Optional.empty(),
                AlertEvidence.signals(List.of(signal, RiskSignalId.next(EvaluationWorld.IDS))), flow, cause))
                .as("one signal").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlertRule.fact(AlertKind.AML)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AlertRule.codes()).endsWith("UNCLASSIFIED_TRANSACTION", "VELOCITY_DIVERGENCE",
                "OBLIGATION_DEFAULTED");
        assertThat(AlertKind.OBLIGATION_DEFAULTED.caseKind()).contains(CaseKind.COLLECTIONS);
        assertThat(AlertKind.OBLIGATION_DEFAULTED.operational()).isFalse();
        assertThat(AlertKind.firstFor(CaseKind.COLLECTIONS)).isEqualTo(AlertKind.OBLIGATION_DEFAULTED);

        UUID loan = EvaluationWorld.IDS.next();
        assertThatThrownBy(() -> CollectionsCases.alertFor(signal(SignalKind.LOAN_DEFAULTED,
                SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, loan), "lending.LoanDefaulted", "lending",
                condition(DEFAULTED_ON, false)))).as("a default with its flag unset")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionsCases.alertFor(signal(SignalKind.ACCOUNT_OPENED,
                SignalSubject.of(SubjectKind.ACCOUNT, loan), "accounts.AccountOpened", "accounts",
                Map.of("product", "WALLET")))).as("no default").isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ fixtures

    /** A fresh agreement whose party the accountable join places as {@code party}. */
    private UUID borrowedBy(UUID party) {
        UUID agreement = EvaluationWorld.IDS.next();
        world.partyOfSubject.put(SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, agreement), party);
        return agreement;
    }

    /** A fresh borrower's loan defaulted - its COLLECTIONS case, as opened. */
    private RiskCase defaultCase() {
        UUID loan = borrowedBy(EvaluationWorld.IDS.next());
        record(defaulted(SignalKind.LOAN_DEFAULTED, loan, DEFAULTED_ON));
        return world.caseStore.all().stream().filter(riskCase -> riskCase.openingAlert().flatMap(alert ->
                world.alertStore.all().stream().filter(row -> row.id().equals(alert)).findFirst())
                .filter(row -> row.subject().ref().equals(loan.toString())).isPresent()).findFirst().orElseThrow();
    }

    /** Records {@code signal} as the handler does for a raising kind: under the platform's actor. */
    private RiskSignals.Outcome record(RiskSignal signal) {
        return CaseWorld.asSystem(() -> signals.record(CaseWorld.UOW, signal));
    }

    /** The default event of {@code agreement} on {@code day}, as -005's mapping records it. */
    private static RiskSignal defaulted(SignalKind kind, UUID agreement, LocalDate day) {
        String type = kind == SignalKind.LOAN_DEFAULTED ? "lending.LoanDefaulted" : "bnpl.BnplDefaulted";
        String producer = kind == SignalKind.LOAN_DEFAULTED ? "lending" : "bnpl";
        return signal(kind, SignalSubject.of(SubjectKind.CREDIT_AGREEMENT, agreement), type, producer,
                condition(day, true));
    }

    private static Map<String, String> condition(LocalDate day, boolean defaulted) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("product", "INSTALMENT");
        attributes.put("business_date", day.toString());
        attributes.put("days_past_due", "91");
        attributes.put("bucket", "DPD_90_PLUS");
        attributes.put("defaulted", Boolean.toString(defaulted));
        attributes.put("agreement_version", "1");
        return attributes;
    }

    private static RiskSignal signal(SignalKind kind, SignalSubject subject, String type, String producer,
            Map<String, String> attributes) {
        return new RiskSignal(RiskSignalId.next(EvaluationWorld.IDS), kind, subject,
                new RiskSignal.SourceEvent(EventId.next(EvaluationWorld.IDS), type, 1, producer,
                        CorrelationId.of("servicing-" + EvaluationWorld.IDS.next()), CausationId.of("servicing-step")),
                Instant.parse("2026-10-24T21:00:00Z"), SignalAttributes.of(kind, attributes));
    }

    /** The same event delivered again: the mapping mints a fresh id; everything else is the event's. */
    private static RiskSignal sameEvent(RiskSignal recorded) {
        return new RiskSignal(RiskSignalId.next(EvaluationWorld.IDS), recorded.kind(), recorded.subject(),
                recorded.source(), recorded.observedAt(), recorded.attributes());
    }

    private OutboxWriter<Connection> recordingOutbox() {
        return (unitOfWork, envelope, payload, mediaType) -> {
            world.events.add(envelope);
            world.payloads.add(EventPayload.fields(payload));
        };
    }

    /** risk V003's arbiter as the statement applies it: born once by source event; the standing row read back by it. */
    static final class BornOnceSignals implements RiskSignalStore {
        final List<RiskSignal> rows = new ArrayList<>();
        int readsBySource;

        @Override
        public boolean insert(Connection unitOfWork, RiskSignal signal) {
            if (rows.stream().anyMatch(row -> row.source().id().equals(signal.source().id()))) {
                return false;
            }
            rows.add(signal);
            return true;
        }

        @Override
        public Optional<RiskSignal> recordedFrom(Connection unitOfWork, EventId source) {
            readsBySource++;
            return rows.stream().filter(row -> row.source().id().equals(source)).findFirst();
        }

        @Override
        public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            return rows.stream().filter(row -> row.subject().equals(subject) && row.kind() == kind)
                    .max(Comparator.comparing(RiskSignal::observedAt));
        }

        @Override
        public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean seen(Connection unitOfWork, SignalSubject subject) {
            throw new UnsupportedOperationException();
        }
    }
}
