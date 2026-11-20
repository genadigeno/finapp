package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreeningStatus;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycInquiries;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reviewers' queues at the desk (`P17-TSK-029`): §7's request rules - a limit refused rather than reduced, a cursor naming its
 * collection, a status inside its vocabulary - each refused before anything is read or recorded; a next page named exactly
 * while a row remains, resuming after the last row served; every answer recorded once, in its read's unit of work; a miss
 * recording nothing; and a screening row that is three fields and no more.
 */
@DisplayName("the KYC reviewers' queues at the desk (P17-TSK-029)")
@SuppressWarnings("try") // The correlation and actor scopes are used for their close side effect (the established idiom).
class KycQueueDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant T = Instant.parse("2026-11-01T08:00:00.123456Z");

    private final List<AuditRecord> recorded = new ArrayList<>();
    private final FakeInquiries inquiries = new FakeInquiries();
    private final List<KycCase> latest = new ArrayList<>();
    private final KycQueueDesk desk = HermeticKycServices.queueDesk(inquiries, cases(),
            new RecordedReads((unitOfWork, record) -> recorded.add(record), IDS, CLOCK));

    private CorrelationContext.Scope flow;
    private SecurityContext.Scope actor;

    @BeforeEach
    void enter() {
        flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
        actor = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE));
    }

    @AfterEach
    void leave() {
        actor.close();
        flow.close();
    }

    // ------------------------------------------------------------------ the review queue

    @Test
    @DisplayName("a page serves its limit and names the next page exactly while a row remains, resuming after the last served")
    void theQueuePagesAfterItsLastRow() {
        List<KycCase> three = List.of(aCase(KycCaseStatus.OPEN), aCase(KycCaseStatus.IN_REVIEW), aCase(KycCaseStatus.OPEN));
        inquiries.cases = three;

        KycQueueDesk.ReviewQueuePage first = desk.queue(null, null, "2");

        assertThat(first.items()).extracting(KycQueueDesk.KycCaseHead::caseId)
                .containsExactly(id(three.get(0)), id(three.get(1)));
        assertThat(inquiries.limits).as("one row past the page, to know whether another follows").containsExactly(3);
        assertThat(inquiries.statuses).as("absent, the status is every open one")
                .containsExactly(KycQueueDesk.OPEN);
        assertThat(inquiries.queueAfter).containsExactly(Optional.empty());
        assertThat(first.nextCursor()).isNotNull();

        inquiries.cases = List.of(three.get(2));
        KycQueueDesk.ReviewQueuePage second = desk.queue(null, first.nextCursor(), "2");

        assertThat(inquiries.queueAfter.getLast()).as("strictly after the last case served, by its order's key")
                .contains(new KycInquiries.QueuePosition(three.get(1).openedAt(), three.get(1).id().value()));
        assertThat(second.items()).extracting(KycQueueDesk.KycCaseHead::caseId).containsExactly(id(three.get(2)));
        assertThat(second.nextCursor()).as("the last page names none").isNull();

        inquiries.cases = List.of(three.get(0), three.get(1));
        assertThat(desk.queue(null, null, "2").nextCursor())
                .as("a last page exactly full names none: the row read past it is what says so").isNull();
    }

    @Test
    @DisplayName("every page is recorded once, in its read's unit of work: the filter, whether it followed a cursor, the count")
    void everyPageIsRecordedOnce() {
        inquiries.cases = List.of(aCase(KycCaseStatus.IN_REVIEW), aCase(KycCaseStatus.IN_REVIEW), aCase(KycCaseStatus.IN_REVIEW));

        KycQueueDesk.ReviewQueuePage page = desk.queue("IN_REVIEW", null, "2");
        desk.queue("IN_REVIEW", page.nextCursor(), "2");

        assertThat(inquiries.statuses.getFirst()).containsExactly(KycCaseStatus.IN_REVIEW);
        assertThat(recorded).hasSize(2).allSatisfy(record -> {
            assertThat(record.operation()).isEqualTo(KycAuditAction.REVIEW_QUEUE_READ);
            assertThat(record.targetType()).isEqualTo("kyc_review_queue");
            assertThat(record.targetId()).isEqualTo("IN_REVIEW");
        });
        assertThat(recorded.get(0).changeSummary()).hasValue("status=IN_REVIEW, continued=false, served=2");
        assertThat(recorded.get(1).changeSummary()).hasValue("status=IN_REVIEW, continued=true, served=2");
    }

    @Test
    @DisplayName("the queue filters by AWAITING_CUSTOMER: the cases a reviewer asked the customer about (P17-TSK-126)")
    void theQueueFiltersTheCasesAwaitingTheirCustomer() {
        inquiries.cases = List.of(aCase(KycCaseStatus.AWAITING_CUSTOMER));

        KycQueueDesk.ReviewQueuePage page = desk.queue("AWAITING_CUSTOMER", null, null);

        assertThat(inquiries.statuses.getLast()).containsExactly(KycCaseStatus.AWAITING_CUSTOMER);
        assertThat(page.items()).extracting(KycQueueDesk.KycCaseHead::status).containsExactly("AWAITING_CUSTOMER");
        assertThat(recorded.getLast().changeSummary()).hasValue("status=AWAITING_CUSTOMER, continued=false, served=1");
    }

    @Test
    @DisplayName("a limit outside 1..200, a decided or unknown status, and another collection's or an unreadable cursor are"
            + " refused 422 - nothing read, nothing recorded")
    void theRequestRulesRefuseBeforeReading() {
        String backlogCursor = OperatorCollection.cursor(KycQueueDesk.BACKLOG, "1", IDS.next().toString());
        for (ThrowingCallable refused : List.<ThrowingCallable>of(
                () -> desk.queue(null, null, "201"),
                () -> desk.queue(null, null, "0"),
                () -> desk.queue(null, null, "fifty"),
                () -> desk.queue("APPROVED", null, null),
                () -> desk.queue("REJECTED", null, null),
                () -> desk.queue("in_review", null, null),
                () -> desk.queue(null, backlogCursor, null),
                () -> desk.queue(null, OperatorCollection.cursor(KycQueueDesk.QUEUE, "not-micros", "x"), null),
                () -> desk.queue(null, "garbage", null),
                () -> desk.backlog(null, null, "201"),
                () -> desk.backlog("RELEASED", null, null),
                () -> desk.backlog(null, OperatorCollection.cursor(KycQueueDesk.QUEUE, "1", IDS.next().toString()), null))) {
            assertThatThrownBy(refused).isInstanceOfSatisfying(ApiException.class,
                    refusal -> assertThat(refusal.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
        assertThat(inquiries.limits).as("nothing read").isEmpty();
        assertThat(recorded).as("nothing recorded").isEmpty();
    }

    // ------------------------------------------------------------------ a customer's latest case

    @Test
    @DisplayName("a customer's latest case is served as its head and recorded kyc.CaseRead; a miss and a malformed id are one 404,"
            + " recording nothing")
    void theLatestCaseIsRecordedAndAMissIsNot() {
        KycCase found = aCase(KycCaseStatus.APPROVED);
        latest.add(found);

        KycQueueDesk.KycCaseHead head = desk.latestOf(found.customerId().toString());

        assertThat(head.caseId()).isEqualTo(id(found));
        assertThat(head.status()).as("open or decided: the latest").isEqualTo("APPROVED");
        assertThat(recorded).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(KycAuditAction.KYC_CASE_READ);
            assertThat(record.targetType()).isEqualTo("KycCase");
            assertThat(record.targetId()).isEqualTo(id(found));
            assertThat(record.changeSummary()).hasValue("customer=" + found.customerId() + ", latest-of-customer");
        });

        latest.clear();
        recorded.clear();
        for (String missing : List.of(IDS.next().toString(), "not-a-uuid")) {
            assertThatThrownBy(() -> desk.latestOf(missing)).isInstanceOfSatisfying(ApiException.class,
                    refusal -> assertThat(refusal.errorCode()).isEqualTo(PlatformErrorCode.NOT_FOUND));
        }
        assertThat(recorded).isEmpty();
    }

    // ------------------------------------------------------------------ the screening backlog

    @Test
    @DisplayName("the backlog pages as the queue does, records each page kyc.ScreeningBacklogRead, and a row is its id, status"
            + " and raised instant - nothing else")
    void theBacklogIsThreeFieldsAPage() {
        List<KycInquiries.AwaitingReview> three = List.of(awaiting(), awaiting(), awaiting());
        inquiries.screenings = three;

        KycQueueDesk.ScreeningBacklogPage first = desk.backlog("IN_REVIEW", null, "2");
        inquiries.screenings = List.of(three.get(2));
        KycQueueDesk.ScreeningBacklogPage second = desk.backlog(null, first.nextCursor(), "2");

        assertThat(first.items()).extracting(KycQueueDesk.ScreeningAwaitingReview::screeningId)
                .containsExactly(three.get(0).id().value().toString(), three.get(1).id().value().toString());
        assertThat(inquiries.backlogAfter.getLast())
                .contains(new KycInquiries.BacklogPosition(three.get(1).raisedAt(), three.get(1).id().value()));
        assertThat(second.items()).extracting(KycQueueDesk.ScreeningAwaitingReview::screeningId)
                .containsExactly(three.get(2).id().value().toString());
        assertThat(second.nextCursor()).isNull();
        assertThat(recorded).hasSize(2).allSatisfy(record -> {
            assertThat(record.operation()).isEqualTo(KycAuditAction.SCREENING_BACKLOG_READ);
            assertThat(record.targetId()).isEqualTo("IN_REVIEW");
        });
        assertThat(recorded.get(1).changeSummary()).hasValue("continued=true, served=1");
        assertThat(Arrays.stream(KycQueueDesk.ScreeningAwaitingReview.class.getRecordComponents()).map(RecordComponent::getName))
                .as("tipping-off: a reviewer's list carries no subject, verdict, evidence or reason")
                .containsExactly("screeningId", "status", "raisedAt");
    }

    // ------------------------------------------------------------------ plumbing

    private static KycCase aCase(KycCaseStatus status) {
        return KycCase.rehydrate(KycCaseId.of(IDS.next()), IDS.next(), KycCaseKind.KYC, status,
                new KycPolicyVersion("kyc-2026-09"), T, T.plusSeconds(60));
    }

    private static KycInquiries.AwaitingReview awaiting() {
        return new KycInquiries.AwaitingReview(CounterpartyScreeningId.of(IDS.next()), CounterpartyScreeningStatus.IN_REVIEW, T);
    }

    private static String id(KycCase kycCase) {
        return kycCase.id().value().toString();
    }

    /** kyc's case store, answering only the latest-case read; any other call is a defect here. */
    @SuppressWarnings("unchecked")
    private KycCaseStore<Connection> cases() {
        return (KycCaseStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {KycCaseStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findLatestFor")) {
                        return latest.stream().filter(found -> found.customerId().equals(args[1])).findFirst();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The read port, answering what the case hands it and remembering what it was asked. */
    private static final class FakeInquiries implements KycInquiries {
        List<KycCase> cases = List.of();
        List<AwaitingReview> screenings = List.of();
        final List<Set<KycCaseStatus>> statuses = new ArrayList<>();
        final List<Optional<QueuePosition>> queueAfter = new ArrayList<>();
        final List<Optional<BacklogPosition>> backlogAfter = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();

        @Override
        public List<KycCase> openCases(
                Connection unitOfWork, Set<KycCaseStatus> statuses, Optional<QueuePosition> after, int limit) {
            this.statuses.add(statuses);
            queueAfter.add(after);
            limits.add(limit);
            return cases.subList(0, Math.min(limit, cases.size()));
        }

        @Override
        public List<AwaitingReview> screeningsAwaitingReview(Connection unitOfWork, Optional<BacklogPosition> after, int limit) {
            backlogAfter.add(after);
            limits.add(limit);
            return screenings.subList(0, Math.min(limit, screenings.size()));
        }
    }
}
