package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.BeneficialOwner;
import com.finapp.kyc.BeneficialOwnerStore;
import com.finapp.kyc.CallbackSignature;
import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckPurpose;
import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.ChecksAssessment;
import com.finapp.kyc.EvidenceId;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycDecision;
import com.finapp.kyc.KycPolicyVersion;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskId;
import com.finapp.kyc.ReviewTaskStatus;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.kyc.VerificationProvider;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * kyc's wiring of the fail-closed rule (`P13-TSK-022`'s reviews), hermetic over in-memory stores and the real
 * {@link CaseAssessment}, {@link DecisionRecording}, {@link VerificationRunService} and {@link ProviderCallbackService}:
 * an undecided case's assessment reads every check, so a list update's {@code HIT} routes it to review instead of the
 * automatic approval; the door's hit path and the callback's rescreen branch both assess it after the commit, and tell
 * the permit that asked ({@link RescreenHits}); a decided case is assessed for nothing. The database's behaviour is
 * {@code RescreeningDatabaseTest}'s (written, owed - C5); these are the three sites the last review found unguarded.
 */
@DisplayName("kyc's wiring of the fail-closed rule: an undecided case's rescreen hit reaches a person (P13-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RescreenRoutingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final byte[] KEY = "a-32-byte-signing-key-for-tests!".getBytes(StandardCharsets.UTF_8);
    private static final List<CheckType> REQUIRED = List.of(CheckType.IDENTITY, CheckType.DOCUMENT,
            CheckType.SANCTIONS, CheckType.PEP, CheckType.ADVERSE_MEDIA);

    private final Cases cases = new Cases();
    private final Tasks tasks = new Tasks();
    private final Checks checks = new Checks();
    private final List<KycDecision> decisions = new ArrayList<>();
    private final List<CheckId> told = new ArrayList<>();
    private final Map<CheckType, CheckOutcome> answers = new EnumMap<>(CheckType.class);
    private final List<CheckType> providerCalls = new ArrayList<>();
    private final UUID customer = IDS.next();

    private final HermeticKycServices.Services services = HermeticKycServices.over(cases, checks, tasks,
            new NoOwners(), (uow, decision) -> decisions.add(decision), new Parties(),
            REQUIRED.stream().map(this::provider).toList(), told::add, KEY, IDS, CLOCK);
    private final CaseAssessment assessment = services.assessment();
    private final VerificationRunService runs = services.runs();
    private final ProviderCallbackService callbacks = services.callbacks();

    @Test
    @DisplayName("CaseAssessment: an undecided case is assessed over every check - a rescreen HIT beside an all-clear"
            + " onboarding blocks the automatic approval and opens its task; a decided case is assessed for nothing")
    void anUndecidedCaseReadsEveryCheck() {
        KycCaseId undecided = allClearCase(KycCaseStatus.CHECKS_IN_PROGRESS);
        VerificationCheck hit = checks.rescreen(undecided, CheckType.SANCTIONS, CheckStatus.HIT, "risk.rescreen:a#0");

        Optional<CaseAssessment.Result> assessed = inFlow(() -> assessment.assessRescreened(undecided));
        assertThat(assessed.orElseThrow().assessment()).isEqualTo(ChecksAssessment.BLOCKED);
        assertThat(cases.status(undecided)).isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(tasks.openFor(undecided)).containsExactly(hit.id());
        assertThat(decisions).as("never an automatic approval past a list update's hit").isEmpty();

        KycCaseId decided = allClearCase(KycCaseStatus.APPROVED);
        checks.rescreen(decided, CheckType.PEP, CheckStatus.HIT, "risk.rescreen:b#0");
        assertThat(inFlow(() -> assessment.assessRescreened(decided))).as("evidence beside the decision").isEmpty();
        assertThat(cases.status(decided)).isEqualTo(KycCaseStatus.APPROVED);
        assertThat(tasks.openFor(decided)).isEmpty();
    }

    @Test
    @DisplayName("VerificationRunService.rescreen: the door's HIT is assessed after its commit - the undecided case to"
            + " review, the permit told; asked again under its key, kyc answers from the row and routes it again (no"
            + " provider call) - the crash's healing")
    void theDoorsHitIsAssessedAndTold() {
        KycCaseId undecided = allClearCase(KycCaseStatus.CHECKS_IN_PROGRESS);
        answers.put(CheckType.SANCTIONS, CheckOutcome.HIT);
        VerificationCheck hit = inFlow(() -> runs.rescreen(undecided, customer, CheckType.SANCTIONS,
                "risk.rescreen:c#0"));
        assertThat(hit.status()).isEqualTo(CheckStatus.HIT);
        assertThat(cases.status(undecided)).as("routed to review").isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(tasks.openFor(undecided)).containsExactly(hit.id());
        assertThat(decisions).isEmpty();
        assertThat(told).containsExactly(hit.id());
        assertThat(providerCalls).containsExactly(CheckType.SANCTIONS);

        tasks.forget(undecided);
        cases.set(undecided, KycCaseStatus.CHECKS_IN_PROGRESS);
        inFlow(() -> runs.rescreen(undecided, customer, CheckType.SANCTIONS, "risk.rescreen:c#0"));
        assertThat(providerCalls).as("answered from the row").containsExactly(CheckType.SANCTIONS);
        assertThat(cases.status(undecided)).as("the routing re-run").isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(told).containsExactly(hit.id(), hit.id());
    }

    @Test
    @DisplayName("ProviderCallbackService: a late callback's HIT for a RESCREEN check is assessed - the undecided case to"
            + " review - and the permit told; a late CLEAR tells nothing")
    void aLateCallbacksHitIsAssessedAndTold() {
        KycCaseId undecided = allClearCase(KycCaseStatus.CHECKS_IN_PROGRESS);
        VerificationCheck stranded = checks.rescreen(undecided, CheckType.PEP, CheckStatus.DISPATCHED,
                "risk.rescreen:d#0");
        deliver(stranded.id(), "hit");
        assertThat(checks.status(stranded.id())).isEqualTo(CheckStatus.HIT);
        assertThat(cases.status(undecided)).isEqualTo(KycCaseStatus.IN_REVIEW);
        assertThat(tasks.openFor(undecided)).containsExactly(stranded.id());
        assertThat(told).containsExactly(stranded.id());

        KycCaseId other = allClearCase(KycCaseStatus.APPROVED);
        VerificationCheck cleared = checks.rescreen(other, CheckType.SANCTIONS, CheckStatus.DISPATCHED,
                "risk.rescreen:e#0");
        deliver(cleared.id(), "clear");
        assertThat(told).as("a clear tells nothing").containsExactly(stranded.id());
        assertThat(cases.status(other)).isEqualTo(KycCaseStatus.APPROVED);
    }

    // ----------------------------------------------------------------- the world

    private void deliver(CheckId checkId, String status) {
        byte[] body = ("{\"deliveryId\":\"rescreen-" + UUID.randomUUID() + "\",\"checkId\":\"" + checkId.value()
                + "\",\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
        inFlow(() -> {
            callbacks.deliver(body, new CallbackSignature(KEY).sign(body));
            return null;
        });
    }

    /** A case in {@code status} with every required type's onboarding check CLEAR. */
    private KycCaseId allClearCase(KycCaseStatus status) {
        KycCaseId id = KycCaseId.next(IDS);
        cases.set(id, status);
        for (CheckType type : REQUIRED) {
            checks.put(VerificationCheck.rehydrate(CheckId.next(IDS), id, type, CheckStatus.CLEAR,
                    CLOCK.instant().minusSeconds(3600), CLOCK.instant().minusSeconds(3600)));
        }
        return id;
    }

    private VerificationProvider provider(CheckType type) {
        return new VerificationProvider() {
            @Override
            public CheckType checkType() {
                return type;
            }

            @Override
            public ProviderResult verify(VerificationSubject subject) {
                providerCalls.add(type);
                return new ProviderResult(answers.getOrDefault(type, CheckOutcome.CLEAR), Optional.empty());
            }
        };
    }

    private static <T> T inFlow(Supplier<T> work) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    /** kyc's cases: the conditional moves, the decision's never past an OPEN task (the store's rule). */
    private final class Cases implements KycCaseStore<Connection> {
        private final Map<KycCaseId, KycCaseStatus> statuses = new LinkedHashMap<>();

        void set(KycCaseId id, KycCaseStatus status) {
            statuses.put(id, status);
        }

        KycCaseStatus status(KycCaseId id) {
            return statuses.get(id);
        }

        @Override
        public boolean moveStatus(Connection unitOfWork, KycCaseId id, KycCaseStatus from, KycCaseStatus to,
                Instant at) {
            if (statuses.get(id) != from || (to.isTerminal() && !tasks.openFor(id).isEmpty())) {
                return false;
            }
            statuses.put(id, to);
            return true;
        }

        @Override
        public boolean moveToReadyForDecision(Connection unitOfWork, KycCaseId id, KycCaseStatus from, Instant at) {
            if (statuses.get(id) != from || !tasks.openFor(id).isEmpty()) {
                return false;
            }
            statuses.put(id, KycCaseStatus.READY_FOR_DECISION);
            return true;
        }

        @Override
        public Optional<KycCase> findById(Connection unitOfWork, KycCaseId id) {
            return Optional.ofNullable(statuses.get(id)).map(status -> KycCase.rehydrate(id, customer, KycCaseKind.KYC,
                    status, KycPolicyVersion.CURRENT, CLOCK.instant().minusSeconds(7200), CLOCK.instant()));
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
    }

    /** kyc's checks: both purposes, the door's keyed insert and its conditional moves. */
    private static final class Checks implements CheckStore<Connection> {
        private final Map<CheckId, VerificationCheck> rows = new LinkedHashMap<>();
        private final Map<String, CheckId> byKey = new LinkedHashMap<>();
        private final Map<CheckId, String> keys = new LinkedHashMap<>();

        void put(VerificationCheck check) {
            rows.put(check.id(), check);
        }

        VerificationCheck rescreen(KycCaseId caseId, CheckType type, CheckStatus status, String key) {
            VerificationCheck check = VerificationCheck.rehydrate(CheckId.next(IDS), caseId, type, status,
                    CLOCK.instant(), CLOCK.instant(), CheckPurpose.RESCREEN);
            put(check);
            byKey.put(key, check.id());
            keys.put(check.id(), key);
            return check;
        }

        CheckStatus status(CheckId id) {
            return rows.get(id).status();
        }

        private void move(CheckId id, CheckStatus status) {
            VerificationCheck was = rows.get(id);
            rows.put(id, VerificationCheck.rehydrate(was.id(), was.caseId(), was.type(), status, was.requestedAt(),
                    CLOCK.instant(), was.purpose()));
        }

        @Override
        public List<VerificationCheck> forCase(Connection unitOfWork, KycCaseId caseId) {
            return rows.values().stream().filter(check -> check.caseId().equals(caseId))
                    .filter(VerificationCheck::onboarding).toList();
        }

        @Override
        public List<VerificationCheck> allForCase(Connection unitOfWork, KycCaseId caseId) {
            return rows.values().stream().filter(check -> check.caseId().equals(caseId)).toList();
        }

        @Override
        public Requested requestRescreen(Connection unitOfWork, KycCaseId caseId, CheckType type, CheckId fresh,
                String rescreenKey) {
            if (byKey.containsKey(rescreenKey)) {
                return new Requested(rows.get(byKey.get(rescreenKey)), false);
            }
            return new Requested(rescreen(caseId, type, CheckStatus.REQUESTED, rescreenKey), true);
        }

        @Override
        public Optional<VerificationCheck> findRescreen(Connection unitOfWork, String rescreenKey) {
            return Optional.ofNullable(byKey.get(rescreenKey)).map(rows::get);
        }

        @Override
        public Optional<String> rescreenKeyOf(Connection unitOfWork, CheckId checkId) {
            return Optional.ofNullable(keys.get(checkId));
        }

        @Override
        public boolean dispatch(Connection unitOfWork, CheckId checkId, Instant at) {
            if (rows.get(checkId).status() != CheckStatus.REQUESTED) {
                return false;
            }
            move(checkId, CheckStatus.DISPATCHED);
            return true;
        }

        @Override
        public boolean complete(Connection unitOfWork, CheckId checkId, CheckOutcome outcome, Instant at) {
            if (rows.get(checkId).status() != CheckStatus.DISPATCHED) {
                return false;
            }
            move(checkId, outcome.toStatus());
            return true;
        }

        @Override
        public Optional<VerificationCheck> findById(Connection unitOfWork, CheckId checkId) {
            return Optional.ofNullable(rows.get(checkId));
        }

        @Override
        public void appendEvidence(Connection unitOfWork, EvidenceId evidenceId, CheckId checkId, byte[] payload,
                Instant receivedAt) {
            // Evidence is the database suite's.
        }

        @Override
        public List<VerificationCheck> latestDecidedScreenings(Connection unitOfWork, KycCaseId caseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requested requestOrConverge(Connection unitOfWork, VerificationCheck fresh) {
            throw new UnsupportedOperationException("the run's onboarding requests are the database suite's");
        }
    }

    /** kyc's review tasks: one per check, ever. */
    private static final class Tasks implements ReviewTaskStore<Connection> {
        private final Map<CheckId, ReviewTask> byCheck = new LinkedHashMap<>();

        List<CheckId> openFor(KycCaseId caseId) {
            return byCheck.values().stream().filter(task -> task.caseId().equals(caseId))
                    .filter(task -> task.status() == ReviewTaskStatus.OPEN).map(ReviewTask::checkId).toList();
        }

        void forget(KycCaseId caseId) {
            byCheck.values().removeIf(task -> task.caseId().equals(caseId));
        }

        @Override
        public boolean openForCheck(Connection unitOfWork, ReviewTask fresh) {
            return byCheck.putIfAbsent(fresh.checkId(), fresh) == null;
        }

        @Override
        public long countOpen(Connection unitOfWork) {
            return byCheck.values().stream().filter(task -> task.status() == ReviewTaskStatus.OPEN).count();
        }

        @Override
        public boolean resolve(Connection unitOfWork, ReviewTaskId taskId, KycCaseId caseId, UUID resolvedBy,
                String reason, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReviewTask> findByIdForCase(Connection unitOfWork, ReviewTaskId taskId, KycCaseId caseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReviewTask> forCase(Connection unitOfWork, KycCaseId caseId) {
            throw new UnsupportedOperationException();
        }
    }

    /** No ownership graph: a KYC case is no KYB case's owner here. */
    private static final class NoOwners implements BeneficialOwnerStore<Connection> {
        @Override
        public Declared declare(Connection unitOfWork, BeneficialOwner owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KycCaseId> parentCasesOf(Connection unitOfWork, KycCaseId verificationCaseId) {
            return List.of();
        }

        @Override
        public List<DeclaredOwner> ownersOf(Connection unitOfWork, KycCaseId caseId) {
            throw new UnsupportedOperationException();
        }
    }

    /** The customer projection a decision moves - always PENDING here. */
    private static final class Parties implements PartyStore<Connection> {
        @Override
        public boolean moveCustomerStatus(Connection unitOfWork, CustomerId customerId, CustomerStatus from,
                CustomerStatus to, Instant at) {
            return true;
        }

        @Override
        public Optional<Customer> findLiveCustomerFor(Connection unitOfWork, PartyId partyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.Party> findById(Connection unitOfWork, PartyId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Customer> organisationRegisteredBy(Connection unitOfWork, PartyId registrantPartyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyKind> kindOf(Connection unitOfWork, PartyId partyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyKind> kindOfCustomer(Connection unitOfWork, CustomerId customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PartyId> partyOfCustomer(Connection unitOfWork, CustomerId customerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PartyId> customerPartiesAfter(Connection unitOfWork, Set<CustomerStatus> statuses,
                Optional<PartyId> after, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.finapp.party.PartyName> rename(Connection unitOfWork, PartyId id,
                com.finapp.party.PartyName newName) {
            throw new UnsupportedOperationException();
        }
    }
}
