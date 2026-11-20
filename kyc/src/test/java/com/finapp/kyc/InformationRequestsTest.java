package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * The three acts on a case awaiting its customer (`P17-TSK-126`, ADR-0155), over in-memory stores that hold the database's own
 * rules: the case's conditional moves refused off the table, one open request per case, the answer and the expiry judged on a
 * "database clock" the test sets apart from the application's. The statements that carry those rules on PostgreSQL are
 * {@code InformationRequestStatementsTest}'s; their behaviour under concurrent transactions is owed to the database tier.
 */
@DisplayName("a reviewer's request, the customer's answer and the expiry move the case together with the request (P17-TSK-126)")
class InformationRequestsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor REVIEWER = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final Actor CUSTOMER = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    private static final CorrelationId CORRELATION = CorrelationId.of("corr-request");
    private static final String TEXT =
            "Please upload a clearer photo of your passport: all four corners must be visible. Marker-91c2.";

    private final Cases cases = new Cases();
    private final Requests requests = new Requests();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final List<byte[]> payloads = new ArrayList<>();
    private final InformationRequests acts = new InformationRequests(cases, requests,
            (AuditWriter<Connection>) (unitOfWork, record) -> audit.add(record),
            (OutboxWriter<Connection>) (unitOfWork, envelope, payload, mediaType) -> {
                events.add(envelope);
                payloads.add(payload);
            },
            IDS, CLOCK);

    @Test
    @DisplayName("a request moves the case to AWAITING_CUSTOMER and writes one open request, its record and its event")
    void aRequestParksTheCaseWithTheCustomer() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);

        assertThat(request(kycCase.id())).isEqualTo(InformationRequests.Requested.REQUESTED);

        assertThat(cases.status(kycCase.id())).isEqualTo(KycCaseStatus.AWAITING_CUSTOMER);
        InformationRequest open = requests.findOpenFor(null, kycCase.id()).orElseThrow();
        assertThat(open.reason()).isEqualTo(InformationRequestReason.DOCUMENT_UNREADABLE);
        assertThat(open.text().value()).isEqualTo(TEXT);
        assertThat(open.dueAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(open.correlationId()).isEqualTo(CORRELATION.value());

        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(KycAuditAction.INFORMATION_REQUESTED);
            assertThat(record.actor()).isEqualTo(REVIEWER);
            assertThat(record.targetType()).isEqualTo("KycCase");
            assertThat(record.targetId()).isEqualTo(kycCase.id().value().toString());
            assertThat(record.reason()).contains("DOCUMENT_UNREADABLE");
            assertThat(record.changeSummary().orElseThrow()).doesNotContain("Marker-91c2");
        });
    }

    @Test
    @DisplayName("the event tells the notifier who and by when, never what: no text, no reason")
    void theEventCarriesNoWords() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(kycCase.id());

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("kyc.CustomerInformationRequested");
            assertThat(event.aggregateType()).isEqualTo("KycCase");
            assertThat(event.aggregateId()).isEqualTo(kycCase.id());
            assertThat(event.correlationId()).isEqualTo(CORRELATION);
        });
        String payload = new String(payloads.get(0), StandardCharsets.UTF_8);
        assertThat(payload)
                .contains("\"caseId\"", "\"customerId\"", "\"requestId\"", "\"dueAtEpochMilli\"")
                .doesNotContain("Marker-91c2", "passport", "DOCUMENT_UNREADABLE", "reason");
    }

    @Test
    @DisplayName("refusals write nothing: no case, a KYB case, a case not in review, a case already awaiting")
    void refusalsWriteNothing() {
        assertThat(request(KycCaseId.next(IDS))).isEqualTo(InformationRequests.Requested.NOT_FOUND);
        KycCase kyb = cases.add(KycCaseKind.KYB, KycCaseStatus.IN_REVIEW);
        assertThat(request(kyb.id())).isEqualTo(InformationRequests.Requested.NOT_OFFERED);
        assertThat(cases.status(kyb.id())).isEqualTo(KycCaseStatus.IN_REVIEW);
        for (KycCaseStatus status : List.of(KycCaseStatus.OPEN, KycCaseStatus.CHECKS_IN_PROGRESS,
                KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.APPROVED, KycCaseStatus.REJECTED)) {
            KycCase other = cases.add(KycCaseKind.KYC, status);
            assertThat(request(other.id())).as("%s", status).isEqualTo(InformationRequests.Requested.NOT_IN_REVIEW);
            assertThat(cases.status(other.id())).isEqualTo(status);
        }
        assertThat(requests.rows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(events).isEmpty();

        KycCase inReview = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(inReview.id());
        audit.clear();
        events.clear();
        assertThat(request(inReview.id())).isEqualTo(InformationRequests.Requested.ALREADY_AWAITING);
        assertThat(requests.rows).hasSize(1);
        assertThat(audit).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("of two reviewers asking at once, the case's conditional move admits one; the loser finds it awaiting")
    void aLostMoveIsAlreadyAwaiting() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        // The second reviewer read IN_REVIEW, then the first one's move committed before the second's.
        cases.beforeNextMove = () -> cases.put(kycCase.id(), KycCaseStatus.AWAITING_CUSTOMER);
        assertThat(request(kycCase.id())).isEqualTo(InformationRequests.Requested.ALREADY_AWAITING);
        assertThat(requests.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("the customer's answer closes the request ANSWERED and returns the case to review, once")
    void theAnswerReturnsTheCase() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(kycCase.id());
        audit.clear();
        UUID customer = IDS.next();

        Optional<InformationRequest> answered = acts.answer(null, cases.find(kycCase.id()),
                new CustomerAnswer("I uploaded a new photo of my passport."), CUSTOMER, customer, CorrelationId.of("corr-answer"));

        assertThat(answered).hasValueSatisfying(request -> {
            assertThat(request.status()).isEqualTo(InformationRequestStatus.ANSWERED);
            assertThat(request.answeredBy()).contains(customer);
        });
        assertThat(cases.status(kycCase.id())).isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(KycAuditAction.INFORMATION_REQUEST_ANSWERED);
            assertThat(record.actor()).isEqualTo(CUSTOMER);
            assertThat(record.changeSummary().orElseThrow()).doesNotContain("passport");
        });

        assertThat(acts.answer(null, cases.find(kycCase.id()), new CustomerAnswer("again"), CUSTOMER, customer,
                CorrelationId.of("corr-again")))
                .as("nothing is left to answer")
                .isEmpty();
        assertThat(audit).hasSize(1);
    }

    @Test
    @DisplayName("an answer past the deadline on the database clock is refused, whatever the sweep has done: the case still waits")
    void aLapsedRequestCannotBeAnswered() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(kycCase.id());
        requests.databaseNow = NOW.plus(Duration.ofDays(14));

        assertThat(acts.answer(null, cases.find(kycCase.id()), new CustomerAnswer("late"), CUSTOMER, IDS.next(),
                CorrelationId.of("corr-late"))).isEmpty();
        assertThat(cases.status(kycCase.id())).isEqualTo(KycCaseStatus.AWAITING_CUSTOMER);
        assertThat(requests.findOpenFor(null, kycCase.id())).isPresent();
    }

    @Test
    @DisplayName("the expiry closes each lapsed request EXPIRED and returns its case, as the platform, under the asking flow")
    void theExpiryReturnsLapsedCases() {
        KycCase lapsed = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(lapsed.id());
        requests.databaseNow = NOW.plus(Duration.ofDays(7));
        KycCase fresh = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(fresh.id(), Clock.fixed(NOW.plus(Duration.ofDays(7)), ZoneOffset.UTC));
        audit.clear();
        requests.databaseNow = NOW.plus(Duration.ofDays(14));

        InformationRequests.ExpiredPage page = acts.expirePage(null, 10, Actor.SYSTEM);

        assertThat(page.expired()).isEqualTo(1);
        assertThat(page.returned()).containsExactly(lapsed.id());
        assertThat(cases.status(lapsed.id())).isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(cases.status(fresh.id())).as("not yet due").isEqualTo(KycCaseStatus.AWAITING_CUSTOMER);
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(KycAuditAction.INFORMATION_REQUEST_EXPIRED);
            assertThat(record.actor()).isEqualTo(Actor.SYSTEM);
            assertThat(record.correlationId()).isEqualTo(CORRELATION);
            assertThat(record.changeSummary().orElseThrow()).contains("caseReturned=true");
        });
        assertThat(acts.expirePage(null, 10, Actor.SYSTEM).expired()).as("expired once").isZero();
    }

    @Test
    @DisplayName("a lapsed request whose case is not awaiting is still expired, its record saying the case did not move")
    void aLapseOnACaseNotAwaitingIsRecordedNotRefused() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(kycCase.id());
        cases.put(kycCase.id(), KycCaseStatus.IN_REVIEW);
        audit.clear();
        requests.databaseNow = NOW.plus(Duration.ofDays(15));

        InformationRequests.ExpiredPage page = acts.expirePage(null, 10, Actor.SYSTEM);

        assertThat(page.expired()).isEqualTo(1);
        assertThat(page.returned()).isEmpty();
        assertThat(audit).singleElement()
                .satisfies(record -> assertThat(record.changeSummary().orElseThrow()).contains("caseReturned=false"));
    }

    @Test
    @DisplayName("an answer whose case is not awaiting fails loudly: the shared transaction's invariant was broken")
    void anAnswerOnACaseNotAwaitingFails() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        request(kycCase.id());
        cases.put(kycCase.id(), KycCaseStatus.IN_REVIEW);
        assertThatThrownBy(() -> acts.answer(null, cases.find(kycCase.id()), new CustomerAnswer("an answer"), CUSTOMER,
                IDS.next(), CorrelationId.of("corr")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ plumbing

    private InformationRequests.Requested request(KycCaseId caseId) {
        return request(caseId, CLOCK);
    }

    private InformationRequests.Requested request(KycCaseId caseId, Clock clock) {
        InformationRequests at = new InformationRequests(cases, requests,
                (AuditWriter<Connection>) (unitOfWork, record) -> audit.add(record),
                (OutboxWriter<Connection>) (unitOfWork, envelope, payload, mediaType) -> {
                    events.add(envelope);
                    payloads.add(payload);
                },
                IDS, clock);
        return at.request(null, caseId, InformationRequestReason.DOCUMENT_UNREADABLE, CustomerRequestText.of(TEXT), REVIEWER,
                IDS.next(), CORRELATION);
    }

    /** Cases as the database holds them: a move conditional on its source, and refused off the table as the store refuses it. */
    private static final class Cases implements KycCaseStore<Connection> {
        final Map<KycCaseId, KycCase> rows = new LinkedHashMap<>();
        Runnable beforeNextMove = () -> { };

        KycCase add(KycCaseKind kind, KycCaseStatus status) {
            KycCase kycCase = KycCase.rehydrate(KycCaseId.next(IDS), IDS.next(), kind, status, KycPolicyVersion.CURRENT,
                    NOW.minusSeconds(3600), NOW.minusSeconds(60));
            rows.put(kycCase.id(), kycCase);
            return kycCase;
        }

        void put(KycCaseId id, KycCaseStatus status) {
            KycCase was = rows.get(id);
            rows.put(id, KycCase.rehydrate(id, was.customerId(), was.kind(), status, was.policyVersion(), was.openedAt(),
                    was.statusChangedAt()));
        }

        KycCase find(KycCaseId id) {
            return rows.get(id);
        }

        KycCaseStatus status(KycCaseId id) {
            return rows.get(id).status();
        }

        @Override
        public boolean moveStatus(Connection unitOfWork, KycCaseId id, KycCaseStatus from, KycCaseStatus to, Instant at) {
            if (!from.canTransitionTo(to)) {
                throw new IllegalKycCaseTransitionException(id, from, to);
            }
            Runnable before = beforeNextMove;
            beforeNextMove = () -> { };
            before.run();
            if (!rows.containsKey(id) || rows.get(id).status() != from) {
                return false;
            }
            put(id, to);
            return true;
        }

        @Override
        public Optional<KycCase> findById(Connection unitOfWork, KycCaseId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean moveToReadyForDecision(Connection unitOfWork, KycCaseId id, KycCaseStatus from, Instant at) {
            throw new UnsupportedOperationException();
        }
    }

    /** Requests as V016 holds them: one open per case, the answer and the expiry judged on {@link #databaseNow}. */
    private static final class Requests implements InformationRequestStore<Connection> {
        final Map<InformationRequestId, InformationRequest> rows = new LinkedHashMap<>();
        Instant databaseNow = NOW;

        @Override
        public void open(Connection unitOfWork, InformationRequest request) {
            if (findOpenFor(unitOfWork, request.caseId()).isPresent()) {
                throw new IllegalStateException("information_request_one_open_per_case");
            }
            rows.put(request.id(), request);
        }

        @Override
        public Optional<InformationRequest> findOpenFor(Connection unitOfWork, KycCaseId caseId) {
            return rows.values().stream().filter(r -> r.caseId().equals(caseId) && r.isOpen()).findFirst();
        }

        @Override
        public List<InformationRequest> forCase(Connection unitOfWork, KycCaseId caseId) {
            return rows.values().stream().filter(r -> r.caseId().equals(caseId))
                    .sorted(Comparator.comparing(InformationRequest::requestedAt)).toList();
        }

        @Override
        public Optional<InformationRequest> answer(
                Connection unitOfWork, KycCaseId caseId, CustomerAnswer answer, UUID answeredBy) {
            return findOpenFor(unitOfWork, caseId).filter(open -> !open.lapsedAt(databaseNow)).map(open -> {
                InformationRequest closed = open.answer(answer, answeredBy, databaseNow);
                rows.put(closed.id(), closed);
                return closed;
            });
        }

        @Override
        public List<Lapsed> expirePage(Connection unitOfWork, int limit) {
            List<Lapsed> page = rows.values().stream().filter(r -> r.isOpen() && r.lapsedAt(databaseNow))
                    .sorted(Comparator.comparing(InformationRequest::dueAt)).limit(limit)
                    .map(r -> new Lapsed(r.id(), r.caseId(), r.dueAt(), r.correlationId())).toList();
            for (Lapsed lapsed : page) {
                rows.put(lapsed.id(), rows.get(lapsed.id()).expire(databaseNow));
            }
            return page;
        }
    }
}
