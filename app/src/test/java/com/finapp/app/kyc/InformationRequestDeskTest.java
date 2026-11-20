package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.session.RequiresSession;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.PermissionName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionId;
import com.finapp.identity.SessionStatus;
import com.finapp.kyc.CustomerAnswer;
import com.finapp.kyc.InformationRequest;
import com.finapp.kyc.InformationRequestId;
import com.finapp.kyc.InformationRequestReason;
import com.finapp.kyc.InformationRequestStore;
import com.finapp.kyc.InformationRequests;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycErrorCode;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.kyc.TransactionRunner;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * A case awaiting its customer at its doors (`P17-TSK-126`, ADR-0155), hermetic: the tipping-off text refused before anything
 * opens; each door's act keyed - replayed, a refusal spending no key, another request under the key a conflict; the customer's
 * answer resolved from the session alone; a return re-asking readiness; the leaderless expiry's page loop; and the doors'
 * controls. The stores are in memory and hold the database's rules (the conditional moves, one open request, the database
 * clock); a unit of work's rollback restores the idempotency claims, as the transaction's does. The SQL under ten instances is
 * owed to the database tier (the owner's ruling of 2026-11-20).
 */
@DisplayName("a case awaiting its customer, at its doors and its expiry (P17-TSK-126)")
class InformationRequestDeskTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor REVIEWER = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final String TEXT = "Please upload a clearer photo of your passport: all four corners must be visible.";

    private final Cases cases = new Cases();
    private final Requests requests = new Requests();
    private final List<com.finapp.platform.audit.AuditRecord> audit = new ArrayList<>();
    private Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final IdempotentExecutor executor =
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30));
    private final InformationRequests acts = new InformationRequests(cases, requests,
            (AuditWriter<Connection>) (unitOfWork, record) -> audit.add(record),
            (OutboxWriter<Connection>) (unitOfWork, envelope, payload, mediaType) -> { }, IDS, CLOCK);

    private final UUID party = IDS.next();
    private final IdentityId customerIdentity = IdentityId.next(IDS);
    private final UUID customerId = IDS.next();
    private final InformationRequestDesk desk = new InformationRequestDesk(acts, cases, identities(), parties(), CLOCK,
            new TransactionTemplate(new ClaimTransactions()), dataSource());

    private CorrelationContext.Scope flow;
    private SecurityContext.Scope actor;

    @BeforeEach
    void enter() {
        flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
        actor = SecurityContext.enter(REVIEWER);
    }

    @AfterEach
    void leave() {
        actor.close();
        flow.close();
    }

    // ------------------------------------------------------------------ the reviewer's door

    @Test
    @DisplayName("a text that could tip the customer off is refused 422 kyc.RequestTextRefused before any transaction: no key, nothing written")
    void aTippingOffTextIsRefusedBeforeAnythingOpens() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        String tipping = "Your name matched a sanctions list entry; please upload your passport again.";

        assertThatThrownBy(() -> desk.request(kycCase.id(), InformationRequestReason.OTHER, tipping, IdentityId.next(IDS),
                KeyedReviewAct.informationRequest(executor, "k-1", REVIEWER, kycCase.id(), InformationRequestReason.OTHER,
                        tipping)))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(KycErrorCode.REQUEST_TEXT_REFUSED);
                    assertThat(refused.getMessage()).doesNotContain("sanctions list entry");
                });
        assertThat(claims).isEmpty();
        assertThat(requests.rows).isEmpty();
        assertThat(cases.status(kycCase.id())).isEqualTo(KycCaseStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("a request is made once: a retry under its key replays it, the key under another request is a conflict")
    void theRequestIsKeyed() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);

        assertThat(request(kycCase.id(), "k-2", TEXT)).isEqualTo(InformationRequests.Requested.REQUESTED);
        assertThat(request(kycCase.id(), "k-2", TEXT))
                .as("the first outcome, not the ALREADY_AWAITING the act would meet now")
                .isEqualTo(InformationRequests.Requested.REQUESTED);
        assertThat(requests.rows).hasSize(1);
        assertThat(audit).hasSize(1);
        assertThatThrownBy(() -> request(kycCase.id(), "k-2", TEXT + " Thank you."))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(cases.status(kycCase.id())).isEqualTo(KycCaseStatus.AWAITING_CUSTOMER);
    }

    @Test
    @DisplayName("a refusal spends no key: a case not yet in review is asked under the same key once it is")
    void aRefusalSpendsNoKey() {
        KycCase kycCase = cases.add(KycCaseKind.KYC, KycCaseStatus.CHECKS_IN_PROGRESS);

        assertThat(request(kycCase.id(), "k-3", TEXT)).isEqualTo(InformationRequests.Requested.NOT_IN_REVIEW);
        assertThat(claims).as("the claim rolled back with the refusal").isEmpty();

        cases.put(kycCase.id(), KycCaseStatus.IN_REVIEW);
        assertThat(request(kycCase.id(), "k-3", TEXT)).isEqualTo(InformationRequests.Requested.REQUESTED);
    }

    // ------------------------------------------------------------------ the customer's door

    @Test
    @DisplayName("the customer's answer is found from the session alone, returns the case, and its readiness is re-asked: no task open, ready")
    void theAnswerReturnsTheCaseAndReasksReadiness() {
        KycCase kycCase = customersCase();
        request(kycCase.id(), "k-4", TEXT);

        assertThat(answer("a-1", "I uploaded a new photo of my passport.")).isEqualTo(InformationRequestDesk.Answered.ANSWERED);

        assertThat(cases.status(kycCase.id())).as("every task resolved while the customer held the case")
                .isEqualTo(KycCaseStatus.READY_FOR_DECISION);
        assertThat(requests.forCase(null, kycCase.id())).singleElement()
                .satisfies(request -> assertThat(request.answeredBy()).contains(customerIdentity.value()));
    }

    @Test
    @DisplayName("with a review task still open, the returned case stays in review for the reviewer")
    void anOpenTaskKeepsTheCaseInReview() {
        KycCase kycCase = customersCase();
        cases.openTasks.add(kycCase.id());
        request(kycCase.id(), "k-5", TEXT);

        answer("a-2", "Uploaded.");

        assertThat(cases.status(kycCase.id())).isEqualTo(KycCaseStatus.IN_REVIEW);
    }

    @Test
    @DisplayName("an answer is keyed: its replay answers ANSWERED again; with nothing to answer it is refused and spends no key")
    void theAnswerIsKeyed() {
        KycCase kycCase = customersCase();
        cases.openTasks.add(kycCase.id());
        assertThat(answer("a-3", "Uploaded.")).isEqualTo(InformationRequestDesk.Answered.NOTHING_TO_ANSWER);
        assertThat(claims).isEmpty();

        request(kycCase.id(), "k-6", TEXT);
        assertThat(answer("a-3", "Uploaded.")).isEqualTo(InformationRequestDesk.Answered.ANSWERED);
        assertThat(answer("a-3", "Uploaded.")).as("a retry after a lost answer").isEqualTo(InformationRequestDesk.Answered.ANSWERED);
        assertThat(audit).filteredOn(record -> record.operation().code().equals("kyc.InformationRequestAnswered")).hasSize(1);
        assertThat(answer("a-4", "Again.")).isEqualTo(InformationRequestDesk.Answered.NOTHING_TO_ANSWER);
    }

    @Test
    @DisplayName("past its deadline on the database clock the request cannot be answered; a customer with no open case is told so")
    void lapsedAndCaselessAnswersAreRefused() {
        KycCase kycCase = customersCase();
        request(kycCase.id(), "k-7", TEXT);
        requests.databaseNow = NOW.plus(InformationRequest.ANSWER_WINDOW);
        assertThat(answer("a-5", "late")).isEqualTo(InformationRequestDesk.Answered.NOTHING_TO_ANSWER);
        assertThat(cases.status(kycCase.id())).isEqualTo(KycCaseStatus.AWAITING_CUSTOMER);

        cases.put(kycCase.id(), KycCaseStatus.APPROVED);
        assertThat(answer("a-6", "no case")).isEqualTo(InformationRequestDesk.Answered.NO_OPEN_CASE);
    }

    // ------------------------------------------------------------------ the expiry

    @Test
    @DisplayName("the sweep expires what the database clock says lapsed, returns each case and re-asks its readiness")
    void theSweepReturnsLapsedCases() {
        KycCase ready = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        KycCase tasked = cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW);
        cases.openTasks.add(tasked.id());
        request(ready.id(), "k-8", TEXT);
        request(tasked.id(), "k-9", TEXT);
        requests.databaseNow = NOW.plus(InformationRequest.ANSWER_WINDOW).plusSeconds(1);

        assertThat(schedule(10).sweepOnce()).isEqualTo(2);

        assertThat(cases.status(ready.id())).isEqualTo(KycCaseStatus.READY_FOR_DECISION);
        assertThat(cases.status(tasked.id())).isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(audit).filteredOn(record -> record.operation().code().equals("kyc.InformationRequestExpired"))
                .hasSize(2)
                .allSatisfy(record -> assertThat(record.actor().type()).isEqualTo(ActorType.SYSTEM));
        assertThat(schedule(10).sweepOnce()).as("each lapse expires once").isZero();
    }

    @Test
    @DisplayName("a tick of full pages stops at its bound; a stop begun during a page ends the tick after it")
    void theTickIsBounded() {
        for (int i = 0; i < InformationRequestExpirySchedule.MAX_PAGES + 3; i++) {
            request(cases.add(KycCaseKind.KYC, KycCaseStatus.IN_REVIEW).id(), "k-many-" + i, TEXT);
        }
        requests.databaseNow = NOW.plus(InformationRequest.ANSWER_WINDOW);

        assertThat(schedule(1).sweepOnce()).isEqualTo(InformationRequestExpirySchedule.MAX_PAGES);

        InformationRequestExpirySchedule stopping = schedule(1);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        stopping.drain().started(executorService);
        requests.onNextPage = executorService::shutdown;
        assertThat(stopping.sweepOnce()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the doors and the customer's vocabulary

    @Test
    @DisplayName("the reviewer's door takes KYC_REVIEW, a factor, a key, and records its refusals; the customer's a session and a key")
    void theDoorsCarryTheirControls() throws Exception {
        Method ask = InformationRequestController.class.getMethod("requestCustomerInformation", String.class, String.class,
                RequestForInformation.class, jakarta.servlet.http.HttpServletRequest.class);
        assertThat(ask.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/information-request");
        assertThat(ask.getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.KYC_REVIEW);
        assertThat(ask.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
        assertThat(ask.isAnnotationPresent(RequiresIdempotencyKey.class)).isTrue();
        assertThat(ask.isAnnotationPresent(RecordsRefusals.class)).isTrue();

        Method answer = InformationResponseController.class.getMethod("answerInformationRequest", String.class,
                InformationResponse.class, jakarta.servlet.http.HttpServletRequest.class);
        assertThat(answer.isAnnotationPresent(RequiresIdempotencyKey.class)).isTrue();
        assertThat(InformationResponseController.class.isAnnotationPresent(RequiresSession.class)).isTrue();
        assertThat(KeyedReviewAct.informationResponse(executor, "x", new Actor("c", ActorType.CUSTOMER), "a").key().scope())
                .as("the customer's key names the customer").isEqualTo("kyc.information-response:CUSTOMER:c");
        assertThat(KeyedReviewAct.informationRequest(executor, "x", REVIEWER, KycCaseId.of(IDS.next()),
                InformationRequestReason.OTHER, TEXT).key().scope())
                .isEqualTo("kyc.information-request:" + REVIEWER.keyedType().name() + ":" + REVIEWER.id());
    }

    @Test
    @DisplayName("the customer sees ACTION_REQUIRED for a case awaiting them, and no other status changes its word")
    void theCustomerFacingWordIsActionRequired() {
        assertThat(CustomerFacingCaseStatus.of(KycCaseStatus.AWAITING_CUSTOMER)).isEqualTo("ACTION_REQUIRED");
        assertThat(CustomerFacingCaseStatus.of(KycCaseStatus.IN_REVIEW)).isEqualTo("IN_PROGRESS");
        assertThat(CustomerFacingCaseStatus.of(KycCaseStatus.CHECKS_IN_PROGRESS)).isEqualTo("IN_PROGRESS");
        assertThat(KycQueueDesk.OPEN).contains(KycCaseStatus.AWAITING_CUSTOMER)
                .isEqualTo(EnumSet.complementOf(EnumSet.of(KycCaseStatus.APPROVED, KycCaseStatus.REJECTED)));
    }

    // ------------------------------------------------------------------ plumbing

    private InformationRequests.Requested request(KycCaseId caseId, String key, String text) {
        return desk.request(caseId, InformationRequestReason.DOCUMENT_UNREADABLE, text, IdentityId.next(IDS),
                KeyedReviewAct.informationRequest(executor, key, REVIEWER, caseId, InformationRequestReason.DOCUMENT_UNREADABLE,
                        text));
    }

    @SuppressWarnings("try")
    private InformationRequestDesk.Answered answer(String key, String words) {
        Actor customer = new Actor(customerIdentity.value().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope asCustomer = SecurityContext.enter(customer)) {
            return desk.answer(session(), words, KeyedReviewAct.informationResponse(executor, key, customer, words));
        }
    }

    private KycCase customersCase() {
        KycCase kycCase = KycCase.rehydrate(KycCaseId.next(IDS), customerId, KycCaseKind.KYC, KycCaseStatus.IN_REVIEW,
                KycPolicyVersion.CURRENT, NOW.minusSeconds(3600), NOW.minusSeconds(60));
        cases.rows.put(kycCase.id(), kycCase);
        return kycCase;
    }

    private Session session() {
        return Session.rehydrate(SessionId.next(IDS), customerIdentity, Sensitive.of("hash"), AssuranceLevel.PASSWORD,
                SessionStatus.ACTIVE, NOW, NOW.plusSeconds(3600), NOW.plusSeconds(86400), null, null);
    }

    private InformationRequestExpirySchedule schedule(int pageSize) {
        TransactionRunner runner = new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return work.apply(null);
            }
        };
        return new InformationRequestExpirySchedule(acts, desk, runner, Duration.ofSeconds(1), pageSize);
    }

    @SuppressWarnings("unchecked")
    private IdentityStore<Connection> identities() {
        return (IdentityStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById") && customerIdentity.equals(args[1])) {
                        return Optional.of(Identity.rehydrate(customerIdentity, party, new LoginIdentifier("rfi-customer"),
                                IdentityStatus.ACTIVE, NOW, NOW));
                    }
                    if (method.getName().equals("findById")) {
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private PartyStore<Connection> parties() {
        return (PartyStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PartyStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findLiveCustomerFor") && PartyId.of(party).equals(args[1])) {
                        return Optional.of(Customer.rehydrate(CustomerId.of(customerId), PartyId.of(party),
                                CustomerStatus.PENDING, NOW, NOW));
                    }
                    if (method.getName().equals("findLiveCustomerFor")) {
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DataSource dataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(InformationRequestDeskTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed", "getAutoCommit" -> false;
                    default -> null;
                });
        return (DataSource) Proxy.newProxyInstance(InformationRequestDeskTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A transaction that restores the idempotency claims on rollback, as the database's rollback restores the claim row. */
    private final class ClaimTransactions implements PlatformTransactionManager {
        private Map<IdempotencyKey, IdempotencyRecord> snapshot = Map.of();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            snapshot = new HashMap<>(claims);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // The stores are in memory: committed as written.
        }

        @Override
        public void rollback(TransactionStatus status) {
            claims = new HashMap<>(snapshot);
        }
    }

    /** The case store as the database holds it: conditional moves, and readiness refused while a task is open. */
    private final class Cases implements KycCaseStore<Connection> {
        final Map<KycCaseId, KycCase> rows = new LinkedHashMap<>();
        final Set<KycCaseId> openTasks = new HashSet<>();

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

        KycCaseStatus status(KycCaseId id) {
            return rows.get(id).status();
        }

        @Override
        public boolean moveStatus(Connection unitOfWork, KycCaseId id, KycCaseStatus from, KycCaseStatus to, Instant at) {
            assertThat(from.canTransitionTo(to)).as("%s -> %s is an edge of the table", from, to).isTrue();
            if (!rows.containsKey(id) || rows.get(id).status() != from) {
                return false;
            }
            put(id, to);
            return true;
        }

        @Override
        public boolean moveToReadyForDecision(Connection unitOfWork, KycCaseId id, KycCaseStatus from, Instant at) {
            if (!rows.containsKey(id) || rows.get(id).status() != from || openTasks.contains(id)) {
                return false;
            }
            put(id, KycCaseStatus.READY_FOR_DECISION);
            return true;
        }

        @Override
        public Optional<KycCase> findById(Connection unitOfWork, KycCaseId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customer) {
            return rows.values().stream()
                    .filter(kycCase -> kycCase.customerId().equals(customer) && !kycCase.status().isTerminal())
                    .findFirst();
        }

        @Override
        public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customer) {
            throw new UnsupportedOperationException();
        }
    }

    /** V016 in memory: one open request per case, the answer and the expiry judged on {@link #databaseNow}. */
    private static final class Requests implements InformationRequestStore<Connection> {
        final Map<InformationRequestId, InformationRequest> rows = new LinkedHashMap<>();
        Instant databaseNow = NOW;
        Runnable onNextPage = () -> { };

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
            Runnable before = onNextPage;
            onNextPage = () -> { };
            before.run();
            List<Lapsed> page = rows.values().stream().filter(r -> r.isOpen() && r.lapsedAt(databaseNow))
                    .sorted(Comparator.comparing(InformationRequest::dueAt)).limit(limit)
                    .map(r -> new Lapsed(r.id(), r.caseId(), r.dueAt(), r.correlationId())).toList();
            for (Lapsed lapsed : page) {
                rows.put(lapsed.id(), rows.get(lapsed.id()).expire(databaseNow));
            }
            return page;
        }
    }

    /** The idempotency records, in memory - {@code KeyedReviewActTest}'s. */
    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(),
                    correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
                    held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
