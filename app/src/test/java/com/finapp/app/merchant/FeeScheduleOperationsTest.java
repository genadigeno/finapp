package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.merchant.FeeAssignmentProposal;
import com.finapp.merchant.FeeProposalRefused;
import com.finapp.merchant.FeeProposalStatus;
import com.finapp.merchant.FeeProposalStore;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.FeeScheduleProposals;
import com.finapp.merchant.FeeScheduleStore;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.FeeScheduleVersionId;
import com.finapp.merchant.FeeScheduleVersionProposal;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.RecordedReads;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The fee schedule's four eyes at the door, hermetically (`P17-TSK-123`; ADR-0154's second amendment): the desk over the
 * real {@link FeeScheduleProposals}, {@link FeeSchedules} and {@link IdempotentExecutor}, every store in memory and every
 * act in a transaction that rolls back whole. A version and an assignment are proposed keyed - a retry replays the first
 * answer, another request under the key is refused, nothing is priced - and only a second person's approval mints the
 * version or moves the pointer, once; the proposer's own decision is refused and leaves nothing behind. The database's half
 * (V022 for every writer, the races) is {@code FeeScheduleFourEyesDatabaseTest}'s.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the fee schedule's four eyes at the door, keyed (P17-TSK-123)")
class FeeScheduleOperationsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(FeeScheduleOperationsTest.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                case "close" -> null;
                case "isClosed" -> false;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "the in-memory unit of work";
                default -> throw new UnsupportedOperationException("the fakes never touch the connection: " + method);
            });
    private static final Actor ADMINISTRATOR = new Actor("administrator-1", ActorType.EMPLOYEE);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.EMPLOYEE);
    private static final Actor BOTH = new Actor("holds-both-1", ActorType.EMPLOYEE);

    /** Everything the stores hold, copied whole to roll a failed transaction back. */
    private static final class State {
        final Map<FeeScheduleId, FeeSchedule> schedules = new LinkedHashMap<>();
        final List<FeeScheduleVersion> versions = new ArrayList<>();
        final Map<MerchantId, FeeScheduleId> pointers = new HashMap<>();
        final Map<UUID, FeeScheduleVersionProposal> versionProposals = new LinkedHashMap<>();
        final Map<UUID, FeeAssignmentProposal> assignmentProposals = new LinkedHashMap<>();
        final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
        final List<AuditRecord> audited = new ArrayList<>();

        State copy() {
            State copy = new State();
            copy.schedules.putAll(schedules);
            copy.versions.addAll(versions);
            copy.pointers.putAll(pointers);
            copy.versionProposals.putAll(versionProposals);
            copy.assignmentProposals.putAll(assignmentProposals);
            copy.claims.putAll(claims);
            copy.audited.addAll(audited);
            return copy;
        }
    }

    private State state = new State();
    private final Map<MerchantId, Merchant> merchants = new HashMap<>();

    private final FeeSchedules feeSchedules = new FeeSchedules(new Schedules(), new Merchants(),
            (uow, record) -> state.audited.add(record), IDS, CLOCK);
    private final FeeScheduleOperations desk = new FeeScheduleOperations(
            feeSchedules,
            new TransactionTemplate() {
                @Override
                public <T> T execute(TransactionCallback<T> action) {
                    State held = state.copy();
                    try {
                        TransactionStatus status = new SimpleTransactionStatus();
                        return action.doInTransaction(status);
                    } catch (RuntimeException failed) {
                        state = held;
                        throw failed;
                    }
                }
            },
            (DataSource) Proxy.newProxyInstance(FeeScheduleOperationsTest.class.getClassLoader(),
                    new Class<?>[] {DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getConnection" -> UOW;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    }),
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)),
            new FeeScheduleProposals(new Proposals(), feeSchedules, (uow, record) -> state.audited.add(record), IDS, CLOCK),
            new RecordedReads((uow, record) -> state.audited.add(record), IDS, CLOCK));

    @Test
    @DisplayName("a version: proposed keyed - a retry replays it, another request under the key is refused, nothing"
            + " minted; one open per schedule; a second person's approval mints it once, its retry replays, and it is"
            + " decided once")
    void aVersionIsKeyedAndMintedOnlyByASecondPerson() {
        String schedule = as(ADMINISTRATOR, () -> desk.create("k-schedule",
                new CreateFeeScheduleRequest("Standard", "EUR"))).feeScheduleId();
        assertThat(as(ADMINISTRATOR, () -> desk.create("k-schedule", new CreateFeeScheduleRequest("Standard", "EUR")))
                .feeScheduleId()).as("a retried creation answers the schedule created first").isEqualTo(schedule);
        assertThat(state.schedules).hasSize(1);

        FeeScheduleOperations.FeeScheduleVersionProposalReceipt proposed =
                as(ADMINISTRATOR, () -> desk.proposeVersion("k-1", schedule, terms("0.029")));
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(proposed.feeScheduleVersionId()).isNull();
        assertThat(state.versions).as("a proposal mints nothing").isEmpty();
        assertThat(as(ADMINISTRATOR, () -> desk.proposeVersion("k-1", schedule, terms("0.029"))))
                .as("the same key replays the proposal (INV-IDEM-01)").isEqualTo(proposed);
        assertThat(state.versionProposals).hasSize(1);
        assertThatThrownBy(() -> as(ADMINISTRATOR, () -> desk.proposeVersion("k-1", schedule, terms("0.031"))))
                .as("another request under the key (INV-IDEM-03)").isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> as(ADMINISTRATOR, () -> desk.proposeVersion("k-2", schedule, terms("0.031"))))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(MerchantErrorCode.FEE_PROPOSAL_ALREADY_OPEN));

        FeeScheduleOperations.FeeScheduleVersionProposalReceipt approved =
                as(CONTROLLER, () -> desk.approveVersion("a-1", proposed.proposalId(), "reviewed"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(state.versions).as("minted once").hasSize(1);
        assertThat(state.versions.get(0).id().value().toString()).isEqualTo(approved.feeScheduleVersionId());
        assertThat(state.versions.get(0).createdBy()).as("under the approver").isEqualTo(CONTROLLER.id());
        assertThat(as(CONTROLLER, () -> desk.approveVersion("a-1", proposed.proposalId(), "reviewed")))
                .as("the retried approval replays").isEqualTo(approved);
        assertThat(state.versions).hasSize(1);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.approveVersion("a-2", proposed.proposalId(), "again")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(MerchantErrorCode.FEE_PROPOSAL_ALREADY_DECIDED));
        assertThat(state.versions).hasSize(1);
        // Per principal, as the actor keys (its keyedType: an operator's keys stay where they were before EMPLOYEE).
        String administrator = ADMINISTRATOR.keyedType().name() + ":" + ADMINISTRATOR.id();
        String controller = CONTROLLER.keyedType().name() + ":" + CONTROLLER.id();
        assertThat(state.claims.keySet()).extracting(IdempotencyKey::scope).contains(
                "merchant.fee-schedule:" + administrator, "merchant.fee-schedule-version:" + administrator,
                "merchant.fee-schedule-version-approval:" + controller);
    }

    @Test
    @DisplayName("the proposer's own decision - holding both permissions - is refused 409 and leaves nothing: no version,"
            + " no pointer, no stored key, the proposal open (INV-AUD-04)")
    void theProposerNeverDecides() {
        String schedule = as(BOTH, () -> desk.create("k-schedule", new CreateFeeScheduleRequest("Own", "EUR")))
                .feeScheduleId();
        String version = as(BOTH, () -> desk.proposeVersion("k-1", schedule, terms("0.029"))).proposalId();
        int claims = state.claims.size();
        assertThatThrownBy(() -> as(BOTH, () -> desk.approveVersion("a-1", version, "mine")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(MerchantErrorCode.FEE_PROPOSAL_SELF_DECISION));
        assertThatThrownBy(() -> as(BOTH, () -> desk.rejectVersion("r-1", version, "mine")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(MerchantErrorCode.FEE_PROPOSAL_SELF_DECISION));
        assertThat(state.versions).isEmpty();
        assertThat(state.claims).as("a refusal stores no answer under its key").hasSize(claims);
        assertThat(state.versionProposals.values()).singleElement()
                .satisfies(proposal -> assertThat(proposal.status()).isEqualTo(FeeProposalStatus.PROPOSED));

        String merchant = merchant();
        String assignment = as(BOTH, () -> desk.proposeAssignment("k-2", merchant,
                new AssignFeeScheduleRequest(UUID.fromString(schedule), "mine"))).proposalId();
        assertThatThrownBy(() -> as(BOTH, () -> desk.approveAssignment("a-2", assignment, "mine")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(MerchantErrorCode.FEE_PROPOSAL_SELF_DECISION));
        assertThat(state.pointers).isEmpty();
    }

    @Test
    @DisplayName("an assignment: proposed keyed, the pointer unmoved; a second person's approval moves it once with the"
            + " proposer's reason; a PUT naming the schedule in place proposes nothing; a rejection moves nothing")
    void anAssignmentMovesOnlyByASecondPerson() {
        String schedule = as(ADMINISTRATOR, () -> desk.create("k-schedule", new CreateFeeScheduleRequest("Std", "EUR")))
                .feeScheduleId();
        String other = as(ADMINISTRATOR, () -> desk.create("k-other", new CreateFeeScheduleRequest("Other", "EUR")))
                .feeScheduleId();
        String merchant = merchant();
        AssignFeeScheduleRequest request = new AssignFeeScheduleRequest(UUID.fromString(schedule), "negotiated tier");

        FeeScheduleOperations.MerchantFeeScheduleView proposed =
                as(ADMINISTRATOR, () -> desk.proposeAssignment("k-1", merchant, request));
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(as(ADMINISTRATOR, () -> desk.proposeAssignment("k-1", merchant, request))).isEqualTo(proposed);
        assertThat(state.pointers).as("a proposal moves nothing").isEmpty();

        FeeScheduleOperations.MerchantFeeScheduleView approved =
                as(CONTROLLER, () -> desk.approveAssignment("a-1", proposed.proposalId(), "reviewed"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(state.pointers).containsEntry(MerchantId.of(UUID.fromString(merchant)),
                FeeScheduleId.of(UUID.fromString(schedule)));
        assertThat(state.audited).filteredOn(record -> record.operation().code().equals("merchant.MerchantFeeScheduleAssigned"))
                .singleElement().satisfies(record -> {
                    assertThat(record.actor()).isEqualTo(CONTROLLER);
                    assertThat(record.reason()).contains("negotiated tier");
                });
        assertThat(as(CONTROLLER, () -> desk.assignmentOf(merchant)).status()).isEqualTo("ASSIGNED");

        FeeScheduleOperations.MerchantFeeScheduleView converged =
                as(ADMINISTRATOR, () -> desk.proposeAssignment("k-2", merchant, request));
        assertThat(converged.status()).isEqualTo("ASSIGNED");
        assertThat(converged.proposalId()).isNull();
        assertThat(state.assignmentProposals).hasSize(1);

        String rejected = as(ADMINISTRATOR, () -> desk.proposeAssignment("k-3", merchant,
                new AssignFeeScheduleRequest(UUID.fromString(other), "cheaper"))).proposalId();
        assertThat(as(CONTROLLER, () -> desk.rejectAssignment("r-1", rejected, "not agreed")).status())
                .isEqualTo("REJECTED");
        assertThat(state.pointers.get(MerchantId.of(UUID.fromString(merchant))).value().toString()).isEqualTo(schedule);
    }

    @Test
    @DisplayName("each kind's queue is P17-TSK-040's pending-decision shape: its open proposals oldest first, the decided"
            + " gone, PROPOSED or no status, a cursor walking the rest")
    void eachKindsQueueIsThePendingShape() {
        String first = as(ADMINISTRATOR, () -> desk.create("k-1", new CreateFeeScheduleRequest("One", "EUR")))
                .feeScheduleId();
        String second = as(ADMINISTRATOR, () -> desk.create("k-2", new CreateFeeScheduleRequest("Two", "EUR")))
                .feeScheduleId();
        String one = as(ADMINISTRATOR, () -> desk.proposeVersion("k-3", first, terms("0.029"))).proposalId();
        String two = as(ADMINISTRATOR, () -> desk.proposeVersion("k-4", second, terms("0.031"))).proposalId();
        String merchant = merchant();
        String assignment = as(ADMINISTRATOR, () -> desk.proposeAssignment("k-5", merchant,
                new AssignFeeScheduleRequest(UUID.fromString(first), "standard terms"))).proposalId();

        // Both stamped at the one fixed instant: the id breaks the tie, so the walk is checked as a walk.
        PendingDecisions.PendingDecisionPage page = as(CONTROLLER, () -> desk.pendingVersions("PROPOSED", null, "1"));
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotNull();
        PendingDecisions.PendingDecisionPage rest = as(CONTROLLER, () -> desk.pendingVersions(null, page.nextCursor(), "1"));
        assertThat(rest.items()).hasSize(1);
        assertThat(rest.nextCursor()).isNull();
        assertThat(List.of(page.items().get(0).id(), rest.items().get(0).id())).containsExactlyInAnyOrder(one, two);
        assertThat(UUID.fromString(page.items().get(0).id())).isLessThan(UUID.fromString(rest.items().get(0).id()));
        PendingDecisions.PendingDecision oneItem = page.items().get(0).id().equals(one) ? page.items().get(0)
                : rest.items().get(0);
        assertThat(oneItem).isEqualTo(new PendingDecisions.PendingDecision(one, "merchant.FeeScheduleVersion",
                ADMINISTRATOR.id(), CLOCK.instant().toString(), "annual repricing", first));
        assertThat(as(CONTROLLER, () -> desk.pendingAssignments(null, null, null)).items()).singleElement()
                .isEqualTo(new PendingDecisions.PendingDecision(assignment, "merchant.FeeAssignment", ADMINISTRATOR.id(),
                        CLOCK.instant().toString(), "standard terms", merchant));
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.pendingVersions("APPROVED", null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.pendingAssignments(null, page.nextCursor(), null)))
                .as("one queue's cursor is refused by another").isInstanceOf(ApiException.class);

        as(CONTROLLER, () -> desk.rejectVersion("r-1", one, "not this month"));
        assertThat(as(CONTROLLER, () -> desk.pendingVersions(null, null, null)).items())
                .extracting(PendingDecisions.PendingDecision::id).as("a decided proposal leaves its queue")
                .containsExactly(two);
    }

    @Test
    @DisplayName("one proposal read by its approver with the schedule it names, every serving recorded"
            + " merchant.FeeProposalRead (P17-TSK-124's pattern); an unknown proposal is the 404 and records nothing")
    void theApproversReadIsRecorded() {
        String schedule = as(ADMINISTRATOR, () -> desk.create("k-1", new CreateFeeScheduleRequest("Read", "EUR")))
                .feeScheduleId();
        String proposal = as(ADMINISTRATOR, () -> desk.proposeVersion("k-2", schedule, terms("0.029"))).proposalId();
        int records = state.audited.size();

        FeeScheduleOperations.FeeProposalView read = as(CONTROLLER, () -> desk.proposal(proposal));
        assertThat(read.kind()).isEqualTo("merchant.FeeScheduleVersion");
        assertThat(read.proposedTerms().rate()).isEqualTo("0.029");
        assertThat(read.feeSchedule().feeScheduleId()).isEqualTo(schedule);
        assertThat(state.audited.subList(records, state.audited.size())).singleElement().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("merchant.FeeProposalRead");
            assertThat(record.actor()).isEqualTo(CONTROLLER);
            assertThat(record.targetId()).isEqualTo(proposal);
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary)
                    .contains("kind=merchant.FeeScheduleVersion").doesNotContain("annual repricing"));
        });

        int before = state.audited.size();
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.proposal(IDS.next().toString())))
                .isInstanceOf(ApiException.class);
        assertThat(state.audited).hasSize(before);
    }

    // ------------------------------------------------------------------ fixtures

    private static CreateFeeScheduleVersionRequest terms(String rate) {
        return new CreateFeeScheduleVersionRequest(new BigDecimal(rate), 30L, "HALF_EVEN", "RETAINED", null,
                "annual repricing");
    }

    private String merchant() {
        Merchant merchant = Merchant.rehydrate(MerchantId.of(IDS.next()), UUID.randomUUID(), "Acme GmbH", "Acme", EUR,
                MerchantStatus.ACTIVE, CLOCK.instant(), CLOCK.instant());
        merchants.put(merchant.id(), merchant);
        return merchant.id().value().toString();
    }

    private static <R> R as(Actor actor, Supplier<R> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    /** The schedules, versions and pointers, in memory: V004's rules that the domain does not already hold. */
    private final class Schedules implements FeeScheduleStore<Connection> {
        @Override
        public void insertSchedule(Connection unitOfWork, FeeSchedule schedule) {
            state.schedules.put(schedule.id(), schedule);
        }

        @Override
        public Optional<FeeSchedule> findSchedule(Connection unitOfWork, FeeScheduleId id) {
            return Optional.ofNullable(state.schedules.get(id));
        }

        @Override
        public List<FeeSchedule> listSchedules(Connection unitOfWork) {
            return List.copyOf(state.schedules.values());
        }

        @Override
        public Optional<FeeScheduleVersion> insertVersionIfNumberIsFree(Connection unitOfWork, FeeScheduleVersion version) {
            boolean taken = state.versions.stream().anyMatch(stored -> stored.scheduleId().equals(version.scheduleId())
                    && stored.version() == version.version());
            if (taken) {
                return Optional.empty();
            }
            state.versions.add(version);
            return Optional.of(version);
        }

        @Override
        public int nextVersionNumber(Connection unitOfWork, FeeScheduleId scheduleId) {
            return 1 + (int) state.versions.stream().filter(stored -> stored.scheduleId().equals(scheduleId)).count();
        }

        @Override
        public Optional<FeeScheduleVersion> findVersion(Connection unitOfWork, FeeScheduleVersionId id) {
            return state.versions.stream().filter(stored -> stored.id().equals(id)).findFirst();
        }

        @Override
        public List<FeeScheduleVersion> listVersions(Connection unitOfWork, FeeScheduleId scheduleId) {
            return state.versions.stream().filter(stored -> stored.scheduleId().equals(scheduleId))
                    .sorted(FeeScheduleVersion.EFFECTIVE_ORDER).toList();
        }

        @Override
        public Optional<FeeScheduleVersion> findEffectiveVersion(Connection unitOfWork, FeeScheduleId scheduleId,
                Instant instant) {
            return listVersions(unitOfWork, scheduleId).stream().filter(stored -> !stored.effectiveFrom().isAfter(instant))
                    .findFirst();
        }

        @Override
        public Optional<FeeScheduleId> findAssignment(Connection unitOfWork, MerchantId merchantId) {
            return Optional.ofNullable(state.pointers.get(merchantId));
        }

        @Override
        public void assign(Connection unitOfWork, MerchantId merchantId, Optional<FeeScheduleId> previous,
                FeeScheduleId scheduleId, String reason, Instant at) {
            state.pointers.put(merchantId, scheduleId);
        }

        @Override
        public Optional<FeeScheduleVersion> findEffectiveVersionFor(Connection unitOfWork, MerchantId merchantId) {
            return findAssignment(unitOfWork, merchantId).flatMap(id -> findEffectiveVersion(unitOfWork, id, CLOCK.instant()));
        }
    }

    /** The merchants, in memory: a lock is a read here, one thread at a time. */
    private final class Merchants implements MerchantStore<Connection> {
        @Override
        public void insert(Connection unitOfWork, Merchant merchant) {
            merchants.put(merchant.id(), merchant);
        }

        @Override
        public Optional<Merchant> findById(Connection unitOfWork, MerchantId id) {
            return Optional.ofNullable(merchants.get(id));
        }

        @Override
        public Optional<Merchant> findByIdForUpdate(Connection unitOfWork, MerchantId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public Optional<Merchant> findByIdForShare(Connection unitOfWork, MerchantId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public boolean transition(Connection unitOfWork, Merchant before, Merchant transitioned) {
            throw new UnsupportedOperationException("no standing moves here");
        }
    }

    /** V022's rows, in memory: one open proposal per subject, a decision only of a PROPOSED row. */
    private final class Proposals implements FeeProposalStore<Connection> {
        @Override
        public void insertVersion(Connection unitOfWork, FeeScheduleVersionProposal proposed) {
            if (state.versionProposals.values().stream().anyMatch(open -> open.status() == FeeProposalStatus.PROPOSED
                    && open.feeScheduleId().equals(proposed.feeScheduleId()))) {
                throw new FeeProposalRefused(FeeProposalRefused.Reason.ALREADY_PROPOSED, "one open per schedule");
            }
            state.versionProposals.put(proposed.id().value(), proposed);
        }

        @Override
        public Optional<FeeScheduleVersionProposal> lockVersion(Connection unitOfWork, FeeScheduleVersionProposal.Id id) {
            return Optional.ofNullable(state.versionProposals.get(id.value()));
        }

        @Override
        public boolean decideVersion(Connection unitOfWork, FeeScheduleVersionProposal decided) {
            FeeScheduleVersionProposal held = state.versionProposals.get(decided.id().value());
            if (held == null || held.status() != FeeProposalStatus.PROPOSED) {
                return false;
            }
            state.versionProposals.put(decided.id().value(), decided);
            return true;
        }

        @Override
        public void insertAssignment(Connection unitOfWork, FeeAssignmentProposal proposed) {
            if (state.assignmentProposals.values().stream().anyMatch(open -> open.status() == FeeProposalStatus.PROPOSED
                    && open.merchantId().equals(proposed.merchantId()))) {
                throw new FeeProposalRefused(FeeProposalRefused.Reason.ALREADY_PROPOSED, "one open per merchant");
            }
            state.assignmentProposals.put(proposed.id().value(), proposed);
        }

        @Override
        public Optional<FeeAssignmentProposal> lockAssignment(Connection unitOfWork, FeeAssignmentProposal.Id id) {
            return Optional.ofNullable(state.assignmentProposals.get(id.value()));
        }

        @Override
        public boolean decideAssignment(Connection unitOfWork, FeeAssignmentProposal decided) {
            FeeAssignmentProposal held = state.assignmentProposals.get(decided.id().value());
            if (held == null || held.status() != FeeProposalStatus.PROPOSED) {
                return false;
            }
            state.assignmentProposals.put(decided.id().value(), decided);
            return true;
        }

        @Override
        public List<FeeProposalStore.Pending> pending(Connection unitOfWork, FeeProposalStore.Kind kind,
                Optional<FeeProposalStore.PendingCursor> after, int limit) {
            List<FeeProposalStore.Pending> pending = new ArrayList<>();
            state.versionProposals.values().stream().filter(open -> open.status() == FeeProposalStatus.PROPOSED)
                    .forEach(open -> pending.add(new FeeProposalStore.Pending(open.id().value(),
                            FeeProposalStore.Kind.FEE_SCHEDULE_VERSION, open.proposedBy(), open.proposedAt(),
                            open.proposalReason(), open.feeScheduleId().value())));
            state.assignmentProposals.values().stream().filter(open -> open.status() == FeeProposalStatus.PROPOSED)
                    .forEach(open -> pending.add(new FeeProposalStore.Pending(open.id().value(),
                            FeeProposalStore.Kind.FEE_ASSIGNMENT, open.proposedBy(), open.proposedAt(),
                            open.proposalReason(), open.merchantId().value())));
            return pending.stream().filter(open -> open.kind() == kind)
                    .filter(open -> after.isEmpty() || open.proposedAt().isAfter(after.get().proposedAt())
                            || (open.proposedAt().equals(after.get().proposedAt()) && open.id().compareTo(after.get().id()) > 0))
                    .sorted(Comparator.comparing(FeeProposalStore.Pending::proposedAt)
                            .thenComparing(FeeProposalStore.Pending::id)).limit(limit).toList();
        }

        @Override
        public Optional<FeeProposalStore.Found> find(Connection unitOfWork, UUID id) {
            if (state.versionProposals.containsKey(id)) {
                return Optional.of(new FeeProposalStore.FoundVersion(state.versionProposals.get(id)));
            }
            return Optional.ofNullable(state.assignmentProposals.get(id)).map(FeeProposalStore.FoundAssignment::new);
        }
    }

    /** The platform's idempotency records, in memory. */
    private final class Claims implements IdempotencyRecordStore<Connection> {
        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (state.claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            state.claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                    StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(state.claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = state.claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            state.claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
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
