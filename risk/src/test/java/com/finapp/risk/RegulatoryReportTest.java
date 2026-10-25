package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RegulatoryReports} (`P13-TSK-021`; {@code PHASE_13_PLAN.md} sections 5, 7.1 T-rep, 12.9, 14;
 * {@code RISK_LIFECYCLES.md} section 4.8; ADR-0111 decisions 5-6; {@code DESIGN-021}): the machine over fakes - drafted
 * only from an AML case of a party disposed {@code CONFIRMED_SUSPICIOUS}, its content drawn from the case, the case's
 * {@code REPORT_DRAFTED} act beside it; one live or accepted report per case; approved by a second person (the drafter
 * refused, recorded {@code FAILED}); filed before anything is sent; the drive's first attempt submitting and every later
 * one inquiring - a lost answer asked for, never filed again; the answer recorded once; the narrative in no record and
 * the subject in no event; every operator {@code CUSTOMER}-typed. The database's own arbiters are
 * {@code RegulatoryReportDatabaseTest}'s (written and owed, C5).
 */
@DisplayName("the regulatory report: drafted from a confirmed suspicion, two persons, filed before the call, inquired"
        + " never re-filed (P13-TSK-021)")
class RegulatoryReportTest {

    private static final String NARRATIVE = "Funds were split below the threshold and moved out within a day.";

    @Test
    @DisplayName("a report is drafted from an AML case disposed CONFIRMED_SUSPICIOUS: its content drawn from the case -"
            + " the alerts' rule codes, the monitored transactions of the evidence that touch the party's accounts, the"
            + " party's accounts, the adapter's jurisdiction, format and reference - its REPORT_DRAFTED act past the"
            + " revision, the case unmoved")
    void aReportIsDraftedFromTheCase() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        Actor drafter = world.officer();

        RegulatoryReport drafted = world.drafted(confirmed, drafter);

        assertThat(drafted.status()).isEqualTo(RegulatoryReportStatus.DRAFT);
        ReportContent content = drafted.content();
        assertThat(content.jurisdiction()).isEqualTo("EU-REF");
        assertThat(content.formatVersion()).isEqualTo(1);
        assertThat(content.reportingEntityReference()).isEqualTo("FINAPP-TEST-ENTITY");
        assertThat(content.subject()).isEqualTo(confirmed.party());
        assertThat(content.accounts()).containsExactlyElementsOf(confirmed.accounts());
        assertThat(content.grounds()).as("the attached alerts' rule codes, sorted, each once")
                .containsExactly("RAPID_IN_OUT", "STRUCTURING");
        assertThat(content.transactions()).as("the evidence's monitored transactions of the party's accounts only")
                .containsExactlyElementsOf(confirmed.transactions())
                .doesNotContainAnyElementsOf(confirmed.foreignTransactions());
        assertThat(content.narrative()).isEqualTo(NARRATIVE);
        assertThat(drafted.draftedBy().type()).isEqualTo(ActorType.CUSTOMER);

        RiskCase after = world.world.caseStore.find(ReportWorld.UOW, confirmed.id()).orElseThrow();
        assertThat(after).as("a disposed case never steps").isEqualTo(confirmed.riskCase());
        CaseAction act = world.world.caseStore.actions(ReportWorld.UOW, confirmed.id()).stream()
                .filter(action -> action.kind() == CaseActionKind.REPORT_DRAFTED).findFirst().orElseThrow();
        assertThat(act.id()).as("the report's id is its act's").isEqualTo(drafted.id().value());
        assertThat(act.ordinal()).as("past the case's last revision").isEqualTo(after.revision() + 1);
        assertThat(act.text()).as("the narrative is never the case's").isEmpty();
        assertThat(act.actor()).isEqualTo(RiskCase.Person.of(drafter));

        AuditRecord audited = only(world, "risk.ReportDrafted");
        assertThat(audited.reason()).contains("the case is confirmed suspicious");
        assertThat(audited.targetId()).isEqualTo(drafted.id().value().toString());
        assertThat(audited.changeSummary().orElseThrow()).contains("jurisdiction=EU-REF").contains("transactions=3")
                .contains("unreferenced=0").contains("grounds=2").contains("accounts=2");
        assertThat(world.moved).containsExactly(RegulatoryReportStatus.DRAFT);
        assertThat(world.events).as("a draft announces nothing").isEmpty();
        assertThat(world.reportStore.events()).singleElement()
                .satisfies(event -> assertThat(event.status()).isEqualTo(RegulatoryReportStatus.DRAFT));
    }

    @Test
    @DisplayName("only an AML case disposed CONFIRMED_SUSPICIOUS is reported: an open case, a false positive, a FRAUD"
            + " case confirmed suspicious and an unknown case are refused, nothing written")
    void onlyAConfirmedSuspiciousAmlCaseIsReported() {
        ReportWorld world = new ReportWorld();
        Actor drafter = world.officer();
        RiskCase open = world.world.cases.open(ReportWorld.UOW, Cases.OpenRequest.of(CaseKind.AML,
                CaseWorld.party(EvaluationWorld.IDS.next())), "a look", world.world.analyst(),
                CaseWorld.correlation()).riskCase();
        assertThatThrownBy(() -> world.reports.draft(ReportWorld.UOW, open.id(), ReportWorld.draft(NARRATIVE), drafter,
                ReportWorld.correlation())).isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        ReportWorld.Confirmed falsePositive = world.disposed(CaseKind.AML, Disposition.CLOSED_FALSE_POSITIVE);
        assertThatThrownBy(() -> world.drafted(falsePositive, drafter))
                .isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        ReportWorld.Confirmed fraud = world.disposed(CaseKind.FRAUD, Disposition.CONFIRMED_SUSPICIOUS);
        assertThatThrownBy(() -> world.drafted(fraud, drafter)).as("only an AML case's suspicion is reported")
                .isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        assertThatThrownBy(() -> world.reports.draft(ReportWorld.UOW, RiskCaseId.of(EvaluationWorld.IDS.next()),
                ReportWorld.draft(NARRATIVE), drafter, ReportWorld.correlation()))
                .isInstanceOf(Cases.CaseNotFound.class);
        assertThat(world.reportStore.all()).isEmpty();
        assertThat(world.world.caseStore.allActions()).noneMatch(action -> action.kind() == CaseActionKind.REPORT_DRAFTED);
    }

    @Test
    @DisplayName("one live or accepted report per case: a second draft is refused while one is DRAFT, APPROVED, FILED or"
            + " ACKNOWLEDGED; a REJECTED filing frees the case for a new draft")
    void oneOpenDraftPerCase() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        RegulatoryReport first = world.drafted(confirmed, world.officer());
        assertThatThrownBy(() -> world.drafted(confirmed, world.officer()))
                .isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        world.reports.approve(ReportWorld.UOW, confirmed.id(), first.id(), "ok", world.officer(),
                ReportWorld.correlation());
        assertThatThrownBy(() -> world.drafted(confirmed, world.officer())).as("APPROVED")
                .isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        world.reports.file(ReportWorld.UOW, confirmed.id(), first.id(), "file", world.officer(),
                ReportWorld.correlation());
        assertThatThrownBy(() -> world.drafted(confirmed, world.officer())).as("FILED - the entry's predicate would admit"
                + " a second filing here (DESIGN-021 D3)").isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        world.authority.submissions.push(ReportWorld.Submission.REJECT);
        assertThat(world.drive(first.id()).orElseThrow().status()).isEqualTo(RegulatoryReportStatus.REJECTED);

        RegulatoryReport second = world.drafted(confirmed, world.officer());
        assertThat(second.id()).isNotEqualTo(first.id());
        List<CaseAction> drafts = world.world.caseStore.actions(ReportWorld.UOW, confirmed.id()).stream()
                .filter(action -> action.kind() == CaseActionKind.REPORT_DRAFTED).toList();
        assertThat(drafts).extracting(CaseAction::ordinal).as("each past the last, the case never stepping")
                .containsExactly(confirmed.riskCase().revision() + 1, confirmed.riskCase().revision() + 2);
    }

    @Test
    @DisplayName("four eyes: the drafter's approval is refused SelfApprovalRefused and recorded FAILED; a second person"
            + " approves; the same approver's retry converges; another person after it is refused")
    void theApproverIsNotTheDrafter() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        Actor drafter = world.officer();
        RegulatoryReport drafted = world.drafted(confirmed, drafter);

        RegulatoryReports.SelfApprovalRefused refused = org.assertj.core.api.Assertions.catchThrowableOfType(
                RegulatoryReports.SelfApprovalRefused.class, () -> world.reports.approve(ReportWorld.UOW,
                        confirmed.id(), drafted.id(), "mine", drafter, ReportWorld.correlation()));
        assertThat(world.current(drafted.id()).status()).isEqualTo(RegulatoryReportStatus.DRAFT);
        world.reports.recordRefusal(ReportWorld.UOW, refused, drafter, ReportWorld.correlation());
        AuditRecord failed = only(world, "risk.SelfApprovalRefused");
        assertThat(failed.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(failed.targetId()).isEqualTo(drafted.id().value().toString());
        assertThat(failed.changeSummary().orElseThrow()).contains("act=report:approve").doesNotContain(
                confirmed.party().toString());

        Actor approver = world.officer();
        RegulatoryReports.Decided approved = world.reports.approve(ReportWorld.UOW, confirmed.id(), drafted.id(),
                "accurate", approver, ReportWorld.correlation());
        assertThat(approved.replayed()).isFalse();
        assertThat(approved.report().status()).isEqualTo(RegulatoryReportStatus.APPROVED);
        assertThat(approved.report().approvedBy()).contains(RiskCase.Person.of(approver));
        assertThat(world.reports.approve(ReportWorld.UOW, confirmed.id(), drafted.id(), "accurate", approver,
                ReportWorld.correlation()).replayed()).as("the same approver's retry").isTrue();
        assertThatThrownBy(() -> world.reports.approve(ReportWorld.UOW, confirmed.id(), drafted.id(), "me too",
                world.officer(), ReportWorld.correlation())).isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        assertThat(world.world.audited("risk.ReportApproved")).hasSize(1);
        assertThatThrownBy(() -> new RegulatoryReport(drafted.id(), drafted.caseId(), drafted.content(),
                RegulatoryReportStatus.APPROVED, drafted.draftedBy(), drafted.draftedAt(), Optional.of(drafted.draftedBy()),
                drafted.draftedAt(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, "c")).as("the value refuses it too")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("persons are authenticated humans: CUSTOMER-typed operators draft, approve and file (the routed"
            + " defect); the platform, a service or a merchant is no drafter")
    void customerTypedOperatorsDraftApproveAndFile() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        RegulatoryReport filed = world.filed(confirmed);
        assertThat(filed.status()).isEqualTo(RegulatoryReportStatus.FILED);
        assertThat(List.of(filed.draftedBy(), filed.approvedBy().orElseThrow(), filed.filedBy().orElseThrow()))
                .extracting(RiskCase.Person::type).containsOnly(ActorType.CUSTOMER);

        ReportWorld other = new ReportWorld();
        ReportWorld.Confirmed again = other.confirmedSuspicious();
        for (ActorType type : List.of(ActorType.SYSTEM, ActorType.SERVICE, ActorType.MERCHANT)) {
            Actor notAPerson = new Actor("not-a-person-" + type, type);
            other.world.partyOfActor.put(notAPerson.id(), EvaluationWorld.IDS.next());
            assertThatThrownBy(() -> other.reports.draft(ReportWorld.UOW, again.id(), ReportWorld.draft(NARRATIVE),
                    notAPerson, ReportWorld.correlation())).as("%s", type).isInstanceOf(IllegalArgumentException.class);
        }
        Actor employee = new Actor("employee-" + EvaluationWorld.IDS.next(), ActorType.EMPLOYEE);
        other.world.partyOfActor.put(employee.id(), EvaluationWorld.IDS.next());
        assertThat(other.drafted(again, employee).draftedBy().type()).isEqualTo(ActorType.EMPLOYEE);
    }

    @Test
    @DisplayName("self-dealing fails closed on every act and read: the subject's own party's drafter, approver, filer and"
            + " reader are refused, and so is an officer placed in no party")
    void selfDealingFailsClosed() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        Actor own = world.officerOf(confirmed.party());
        assertThatThrownBy(() -> world.drafted(confirmed, own)).isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .satisfies(thrown -> assertThat(((RiskAssessments.SelfDealingRefused) thrown).refusal())
                        .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.OWN_PARTY));
        Actor nobody = new Actor(EvaluationWorld.IDS.next().toString(), ActorType.CUSTOMER);
        assertThatThrownBy(() -> world.drafted(confirmed, nobody)).isInstanceOf(RiskAssessments.SelfDealingRefused.class)
                .satisfies(thrown -> assertThat(((RiskAssessments.SelfDealingRefused) thrown).refusal())
                        .isEqualTo(RiskAssessments.SelfDealingRefused.Cause.UNPLACEABLE));

        RegulatoryReport drafted = world.drafted(confirmed, world.officer());
        assertThatThrownBy(() -> world.reports.approve(ReportWorld.UOW, confirmed.id(), drafted.id(), "ok", own,
                ReportWorld.correlation())).isInstanceOf(RiskAssessments.SelfDealingRefused.class);
        world.reports.approve(ReportWorld.UOW, confirmed.id(), drafted.id(), "ok", world.officer(),
                ReportWorld.correlation());
        assertThatThrownBy(() -> world.reports.file(ReportWorld.UOW, confirmed.id(), drafted.id(), "file", own,
                ReportWorld.correlation())).isInstanceOf(RiskAssessments.SelfDealingRefused.class);
        assertThatThrownBy(() -> world.reports.read(ReportWorld.UOW, confirmed.id(), drafted.id(), own))
                .isInstanceOf(RiskAssessments.SelfDealingRefused.class);
        assertThatThrownBy(() -> world.reports.read(ReportWorld.UOW, RiskCaseId.of(EvaluationWorld.IDS.next()),
                drafted.id(), world.officer())).as("a report is read under its own case only")
                .isInstanceOf(RegulatoryReports.ReportNotFound.class);
        assertThat(world.current(drafted.id()).status()).isEqualTo(RegulatoryReportStatus.APPROVED);
    }

    @Test
    @DisplayName("filed before the call: FILED committed with the permit due at once and no attempt, nothing sent;"
            + " RegulatoryReportFiled carries the report id and the jurisdiction only; audited risk.ReportFiled")
    void filedBeforeTheCall() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        RegulatoryReport filed = world.filed(confirmed);

        assertThat(filed.status()).isEqualTo(RegulatoryReportStatus.FILED);
        assertThat(filed.nextInquiryAt()).contains(world.reportStore.now);
        assertThat(filed.attempts()).isZero();
        assertThat(world.authority.files).as("nothing is sent by the act").isEmpty();
        assertThat(world.announced(RegulatoryReports.FILED_EVENT)).singleElement().isEqualTo(Map.of(
                "reportId", filed.id().value().toString(), "jurisdiction", "EU-REF"));
        assertThat(world.world.audited("risk.ReportFiled")).singleElement()
                .satisfies(record -> assertThat(record.reason()).contains("file it"));
        assertThat(world.moved).containsExactly(RegulatoryReportStatus.DRAFT, RegulatoryReportStatus.APPROVED,
                RegulatoryReportStatus.FILED);
    }

    @Test
    @DisplayName("the drive: the first claim submits under the filing reference (the report's id), the acknowledgement"
            + " recorded once as the platform; nothing is claimed again")
    void theDriveSubmitsOnceAndRecordsTheAnswer() {
        ReportWorld world = new ReportWorld();
        RegulatoryReport filed = world.filed(world.confirmedSuspicious());

        RegulatoryReport acknowledged = world.drive(filed.id()).orElseThrow();

        assertThat(acknowledged.status()).isEqualTo(RegulatoryReportStatus.ACKNOWLEDGED);
        assertThat(acknowledged.attempts()).isEqualTo(1);
        assertThat(acknowledged.providerReference()).isPresent();
        assertThat(acknowledged.nextInquiryAt()).isEmpty();
        assertThat(world.authority.files).containsExactly(filed.id().value().toString());
        assertThat(world.authority.inquiries).isEmpty();
        assertThat(world.authority.contents).singleElement().isEqualTo(filed.content());
        AuditRecord audited = only(world, "risk.ReportAcknowledged");
        assertThat(audited.actor().type()).as("the platform records the authority's answer").isEqualTo(ActorType.SYSTEM);
        assertThat(audited.changeSummary().orElseThrow()).contains("attempts=1").contains("providerReference=FIU-");
        assertThat(world.drive(filed.id())).as("concluded: nothing due").isEmpty();
        assertThat(world.answered).containsExactly(RegulatoryFilingProvider.Outcome.ACKNOWLEDGED);
        assertThat(world.reportStore.events()).extracting(InMemoryEvents::status).containsExactly(
                RegulatoryReportStatus.DRAFT, RegulatoryReportStatus.APPROVED, RegulatoryReportStatus.FILED,
                RegulatoryReportStatus.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("a lost filing response is inquired, never re-filed: the authority received it and the answer was lost;"
            + " within the lease nothing is claimed; after it the second attempt inquires by the reference and records"
            + " the acknowledgement - one submission")
    void aLostFilingResponseIsInquiredNeverReFiled() {
        ReportWorld world = new ReportWorld();
        RegulatoryReport filed = world.filed(world.confirmedSuspicious());
        world.authority.submissions.push(ReportWorld.Submission.LOSE_ANSWER);

        assertThatThrownBy(() -> world.drive(filed.id())).hasMessageContaining("lost");
        // The claim committed before the call; the record wrote nothing.
        assertThat(world.current(filed.id()).status()).isEqualTo(RegulatoryReportStatus.FILED);
        assertThat(world.current(filed.id()).attempts()).isEqualTo(1);
        assertThat(world.drive(filed.id())).as("within the lease, nothing is due").isEmpty();

        world.later(ReportWorld.LEASE);
        RegulatoryReport answered = world.drive(filed.id()).orElseThrow();

        assertThat(answered.status()).isEqualTo(RegulatoryReportStatus.ACKNOWLEDGED);
        assertThat(answered.attempts()).isEqualTo(2);
        assertThat(world.authority.files).as("one submission - never a second").hasSize(1);
        assertThat(world.authority.inquiries).containsExactly(filed.filingReference());
        assertThat(world.world.audited("risk.ReportAcknowledged")).hasSize(1);
    }

    @Test
    @DisplayName("nothing received is still owed: the authority unreachable at the first attempt; the second inquires,"
            + " hears NOT_RECEIVED and submits under the same reference - one filing at the authority")
    void nothingReceivedIsSubmittedUnderTheSameReference() {
        ReportWorld world = new ReportWorld();
        RegulatoryReport filed = world.filed(world.confirmedSuspicious());
        world.authority.submissions.push(ReportWorld.Submission.UNAVAILABLE);

        assertThatThrownBy(() -> world.drive(filed.id())).hasMessageContaining("unreachable");
        world.later(ReportWorld.LEASE);
        RegulatoryReport answered = world.drive(filed.id()).orElseThrow();

        assertThat(answered.status()).isEqualTo(RegulatoryReportStatus.ACKNOWLEDGED);
        assertThat(world.authority.inquiries).containsExactly(filed.filingReference());
        assertThat(world.authority.files).as("the owed first filing, under the same reference")
                .containsExactly(filed.filingReference(), filed.filingReference());
        assertThat(world.authority.received).as("one filing at the authority").hasSize(1);
        assertThat(world.answered).containsExactly(RegulatoryFilingProvider.Outcome.NOT_RECEIVED,
                RegulatoryFilingProvider.Outcome.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("an UNKNOWN answer is a permit on the FILED row, never a re-filing: the provider's reference recorded,"
            + " the permit an inquiry interval ahead; the inquiry then records the acknowledgement")
    void anUnknownAnswerIsAPermit() {
        ReportWorld world = new ReportWorld();
        RegulatoryReport filed = world.filed(world.confirmedSuspicious());
        world.authority.submissions.push(ReportWorld.Submission.UNKNOWN);

        RegulatoryReport pending = world.drive(filed.id()).orElseThrow();

        assertThat(pending.status()).isEqualTo(RegulatoryReportStatus.FILED);
        assertThat(pending.providerReference()).isPresent();
        assertThat(pending.nextInquiryAt()).contains(world.reportStore.now.plus(ReportWorld.INTERVAL));
        assertThat(world.world.audited("risk.ReportAcknowledged")).as("an unknown standing is no audited act").isEmpty();
        world.later(ReportWorld.LEASE);
        assertThat(world.drive(filed.id())).as("the interval, not the lease, is the permit").isEmpty();
        world.later(ReportWorld.INTERVAL);
        RegulatoryReport answered = world.drive(filed.id()).orElseThrow();
        assertThat(answered.status()).isEqualTo(RegulatoryReportStatus.ACKNOWLEDGED);
        assertThat(answered.providerReference()).isEqualTo(pending.providerReference());
        assertThat(world.authority.files).hasSize(1);
    }

    @Test
    @DisplayName("a rejection is final, audited with its code as the platform; the record is once - a stale attempt's"
            + " record writes nothing")
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    void aRejectionIsFinalAndTheRecordIsOnce() {
        ReportWorld world = new ReportWorld();
        RegulatoryReport filed = world.filed(world.confirmedSuspicious());
        world.authority.submissions.push(ReportWorld.Submission.REJECT);
        RegulatoryReport claimed = CaseWorld.asSystem(() -> world.reports.claim(ReportWorld.UOW, filed.id(),
                ReportWorld.LEASE).orElseThrow());
        FilingOutcome outcome = world.reports.submit(claimed);
        Actor person = world.officer();
        assertThatThrownBy(() -> {
            try (com.finapp.platform.security.SecurityContext.Scope scope =
                    com.finapp.platform.security.SecurityContext.enter(person)) {
                world.reports.conclude(ReportWorld.UOW, claimed, outcome, ReportWorld.INTERVAL, ReportWorld.correlation());
            }
        }).as("the authority's answer is the platform's record, never a person's")
                .isInstanceOf(IllegalStateException.class);

        RegulatoryReport rejected = CaseWorld.asSystem(() -> world.reports.conclude(ReportWorld.UOW, claimed, outcome,
                ReportWorld.INTERVAL, ReportWorld.correlation()).orElseThrow());
        assertThat(rejected.status()).isEqualTo(RegulatoryReportStatus.REJECTED);
        assertThat(rejected.rejectionCode()).contains("FORMAT_INVALID");
        assertThat(only(world, "risk.ReportRejected").changeSummary().orElseThrow()).contains("rejection=FORMAT_INVALID");
        assertThat(CaseWorld.asSystem(() -> world.reports.conclude(ReportWorld.UOW, claimed, outcome,
                ReportWorld.INTERVAL, ReportWorld.correlation()))).as("recorded once").isEmpty();
        assertThatThrownBy(() -> world.reports.file(ReportWorld.UOW, filed.caseId(), filed.id(), "again",
                world.officer(), ReportWorld.correlation())).as("never re-filed")
                .isInstanceOf(RegulatoryReports.ReportNotFileable.class);
    }

    @Test
    @DisplayName("ten filers of one approved report: one FILED, the others refused or converged; ten drives: one claim,"
            + " one submission, one acknowledgement")
    void tenFilersFileOnce() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        RegulatoryReport approved = world.approved(confirmed);
        Actor first = world.officer();
        List<Actor> filers = new ArrayList<>(List.of(first));
        IntStream.range(0, 9).forEach(i -> filers.add(world.officer()));
        int filed = 0;
        int refused = 0;
        for (Actor filer : filers) {
            try {
                if (!world.reports.file(ReportWorld.UOW, confirmed.id(), approved.id(), "file", filer,
                        ReportWorld.correlation()).replayed()) {
                    filed++;
                }
            } catch (RegulatoryReports.ReportNotFileable notFileable) {
                refused++;
            }
        }
        assertThat(filed).isEqualTo(1);
        assertThat(refused).isEqualTo(9);
        assertThat(world.reports.file(ReportWorld.UOW, confirmed.id(), approved.id(), "file", first,
                ReportWorld.correlation()).replayed()).as("the filer's own retry").isTrue();
        assertThat(world.announced(RegulatoryReports.FILED_EVENT)).hasSize(1);

        List<Optional<RegulatoryReport>> drives = IntStream.range(0, 10).mapToObj(i -> world.drive(approved.id()))
                .toList();
        assertThat(drives.stream().filter(Optional::isPresent)).hasSize(1);
        assertThat(world.authority.files).hasSize(1);
        assertThat(world.world.audited("risk.ReportAcknowledged")).hasSize(1);
    }

    @Test
    @DisplayName("the narrative is in no record and the subject in no event: every audit reason and summary, the event,"
            + " the case's act and every toString carry ids, codes and counts only (INV-RSK-08, INV-AUD-02)")
    void theNarrativeIsNeverInARecord() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        RegulatoryReport filed = world.filed(confirmed);
        world.drive(filed.id());
        world.reports.recordRead(ReportWorld.UOW, world.current(filed.id()), world.officer(), ReportWorld.correlation());

        List<String> recorded = new ArrayList<>();
        world.world.audit.stream().filter(record -> record.operation().code().contains("Report"))
                .forEach(record -> {
                    record.reason().ifPresent(recorded::add);
                    record.changeSummary().ifPresent(recorded::add);
                });
        assertThat(recorded).isNotEmpty().allSatisfy(text -> assertThat(text).doesNotContain("threshold")
                .doesNotContain(confirmed.party().toString()));
        world.payloads.forEach(payload -> assertThat(payload.values()).doesNotContain(confirmed.party().toString())
                .noneMatch(value -> value.contains("threshold")));
        assertThat(world.current(filed.id()).toString()).doesNotContain("threshold")
                .doesNotContain(confirmed.party().toString());
        assertThat(world.current(filed.id()).content().toString()).doesNotContain("threshold");
        assertThat(world.world.audited("risk.RegulatoryReportRead")).hasSize(1);
    }

    @Test
    @DisplayName("a draft not taken as asked is refused, nothing written: no reason, no narrative or one holding a card"
            + " number, an undeclared jurisdiction, too many attachments or one named twice")
    void invalidDraftsAreRefused() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        Actor drafter = world.officer();
        List<RegulatoryReports.Draft> reasonless = List.of(
                new RegulatoryReports.Draft("EU-REF", NARRATIVE, List.of(), " "),
                new RegulatoryReports.Draft("EU-REF", "", List.of(), "ok"),
                new RegulatoryReports.Draft("EU-REF", "paid from 4111 1111 1111 1111", List.of(), "ok"),
                new RegulatoryReports.Draft("EU-REF", "x".repeat(ReportContent.NARRATIVE_BOUND + 1), List.of(), "ok"));
        for (RegulatoryReports.Draft draft : reasonless) {
            assertThatThrownBy(() -> world.reports.draft(ReportWorld.UOW, confirmed.id(), draft, drafter,
                    ReportWorld.correlation())).isInstanceOf(RiskReasons.ReasonRequired.class);
        }
        UUID attachment = EvaluationWorld.IDS.next();
        List<RegulatoryReports.Draft> invalid = List.of(
                new RegulatoryReports.Draft("US-FIN", NARRATIVE, List.of(), "ok"),
                new RegulatoryReports.Draft(null, NARRATIVE, List.of(), "ok"),
                new RegulatoryReports.Draft("EU-REF", NARRATIVE, List.of(attachment, attachment), "ok"),
                new RegulatoryReports.Draft("EU-REF", NARRATIVE, IntStream.range(0, ReportContent.ATTACHMENTS_BOUND + 1)
                        .mapToObj(i -> EvaluationWorld.IDS.next()).toList(), "ok"));
        for (RegulatoryReports.Draft draft : invalid) {
            assertThatThrownBy(() -> world.reports.draft(ReportWorld.UOW, confirmed.id(), draft, drafter,
                    ReportWorld.correlation())).isInstanceOf(RegulatoryReports.ReportRequestInvalid.class);
        }
        assertThat(world.reportStore.all()).isEmpty();
        RegulatoryReport attached = world.reports.draft(ReportWorld.UOW, confirmed.id(), new RegulatoryReports.Draft(
                "EU-REF", NARRATIVE, List.of(attachment), "ok"), drafter, ReportWorld.correlation());
        assertThat(attached.content().attachments()).containsExactly(attachment);
    }

    @Test
    @DisplayName("the machine refuses every other edge: a DRAFT is not filed, a FILED report is not approved, an"
            + " answered one never moves; a claim takes only a due FILED permit")
    void theMachineRefusesEveryOtherEdge() {
        ReportWorld world = new ReportWorld();
        ReportWorld.Confirmed confirmed = world.confirmedSuspicious();
        RegulatoryReport drafted = world.drafted(confirmed, world.officer());
        assertThatThrownBy(() -> world.reports.file(ReportWorld.UOW, confirmed.id(), drafted.id(), "skip",
                world.officer(), ReportWorld.correlation())).isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        assertThat(CaseWorld.asSystem(() -> world.reports.claim(ReportWorld.UOW, drafted.id(), ReportWorld.LEASE)))
                .as("a draft has no permit").isEmpty();
        world.reports.approve(ReportWorld.UOW, confirmed.id(), drafted.id(), "ok", world.officer(),
                ReportWorld.correlation());
        world.reports.file(ReportWorld.UOW, confirmed.id(), drafted.id(), "file", world.officer(),
                ReportWorld.correlation());
        assertThatThrownBy(() -> world.reports.approve(ReportWorld.UOW, confirmed.id(), drafted.id(), "late",
                world.officer(), ReportWorld.correlation())).isInstanceOf(RegulatoryReports.ReportNotFileable.class);
        world.drive(drafted.id());
        world.later(Duration.ofDays(1));
        assertThat(world.drive(drafted.id())).as("answered: never claimed again").isEmpty();
        assertThatThrownBy(() -> world.reports.claim(ReportWorld.UOW, drafted.id(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static AuditRecord only(ReportWorld world, String code) {
        List<AuditRecord> found = world.world.audited(code);
        assertThat(found).as(code).hasSize(1);
        return found.get(0);
    }

    /** The history rows' status - a method reference target. */
    private static final class InMemoryEvents {
        static RegulatoryReportStatus status(com.finapp.risk.testing.InMemoryRegulatoryReportStore.Event event) {
            return event.status();
        }
    }
}
