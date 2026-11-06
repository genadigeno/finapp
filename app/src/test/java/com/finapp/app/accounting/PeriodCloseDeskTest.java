package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.ReturnsAReopenOvertakes;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.InMemoryPeriodCloseStore;
import com.finapp.accounting.testing.InMemoryValuationRateSetStore;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
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
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The close's and the reopen's doors (`P14-TSK-012`; {@code PHASE_14_PLAN.md} sections 10, 12; {@code INV-ACC-09}, {@code -10}, {@code INV-AUD-04}): every act keyed and reasoned; the same key replayed as the first answer, the same key with another
 * body refused; the refusals on the contract; a refused self-approval recorded {@code FAILED} in its own transaction; the controller's read under its own permission, audited, carrying the reason per attempt and no person. The routes' 401/403 over HTTP are the interceptor's,
 * held by {@code RoutePermissionRegisterTest}; the permission on each is declared here.
 */
@DisplayName("the close's and the reopen's doors (P14-TSK-012)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PeriodCloseDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("the fakes never touch the connection");
    });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);
    private static final String REASON = "read and agreed";
    /** An id no reopen has, a UUIDv7 as every id is. */
    private static final String NONE_READ = com.finapp.accounting.PeriodReopenId.next(IDS).value().toString();
    private static final String DISCLOSURE = "the secret disclosure, two suspense items";

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore runStore = new InMemoryCloseRunStore(periods);
    private final InMemoryAccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private final InMemoryValuationRateSetStore setStore = new InMemoryValuationRateSetStore().watching(periods);
    private final InMemoryPeriodCloseStore closes = new InMemoryPeriodCloseStore(periods, runStore);
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final CloseRuns runs = new CloseRuns(runStore, periods, breaks, CloseRuns.Leases.STANDARD, (uow, record) -> audited.add(record), IDS, CLOCK);
    private final PostingCutoff cutoff = new PostingCutoff() {
        @Override
        public Optional<LocalDate> closedThrough(Connection unitOfWork) {
            return Optional.empty();
        }

        @Override
        public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
            return true;
        }

        @Override
        public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
            if (cutoffBusy) {
                throw new PostingCutoff.CutoffBusy("the ledger's key was held", new RuntimeException());
            }
            return true;
        }
    };
    private boolean cutoffBusy;
    private final com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore priorPeriod = new com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore(periods);
    private final PeriodCloses machine = new PeriodCloses(periods, closes, runs, breaks, setStore, com.finapp.accounting.testing.MonthsThatRan.EVERY_MONTH, cutoff, (uow, period, actor, correlation) -> 2, priorPeriod, (uow, record) -> audited.add(record),
            (uow, envelope, payload, mediaType) -> events.add(envelope.eventType()), IDS, CLOCK);
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = audited.size();
            Runnable rollback = closes.savepoint();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                rollback.run();
                claims.clear();
                claims.putAll(held);
                audited.subList(records, audited.size()).clear();
                throw failed;
            }
        }
    };
    private final com.finapp.accounting.PriorPeriodAdjustments priorPeriodAdjustments = new com.finapp.accounting.PriorPeriodAdjustments(periods, priorPeriod,
            new com.finapp.accounting.testing.InMemoryPriorPeriodPostings(), new com.finapp.accounting.testing.InMemoryGlMappingStore(), (uow, record) -> audited.add(record), (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
    /** What the read would hand back: its own statement is {@code JdbcReturnsAReopenOvertakesTest}'s, and PostgreSQL's answer {@code PeriodReopenDatabaseTest}'s, owed. */
    private final List<ReturnsAReopenOvertakes.Item> sent = new ArrayList<>();
    private long sentInAll = -1;
    private boolean sentUnreadable;
    private final ReturnsAReopenOvertakes overtaken = (uow, period) -> {
        if (sentUnreadable) {
            throw new AccountingStorageException("reading the returns a reopen overtakes: 53300");
        }
        return new ReturnsAReopenOvertakes.Overtaken(sent, sentInAll < 0 ? sent.size() : sentInAll);
    };
    private final PeriodCloseDesk desk = new PeriodCloseDesk(machine, priorPeriodAdjustments, overtaken, new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions, AccountingFlowScope.NONE);
    private AccountingPeriodId march;
    private CloseRunId run;

    @BeforeEach
    void marchIsLockedAndItsRunReady() {
        march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        periods.markLocked(UOW, march, 1, ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(GlMappingVersionId.next(IDS), 1)));
        run = runs.startMissing(UOW, Actor.SYSTEM).get(0);
        ValuationRateSets sets = new ValuationRateSets(setStore, periods, new OneRate(), ValuationStaleness.standard(), (uow, record) -> { }, IDS, CLOCK);
        sets.approve(UOW, sets.capture(UOW, march, ACCOUNTANT, null, CorrelationId.generate(IDS)).id(), CONTROLLER, "agreed", CorrelationId.generate(IDS));
        Optional<CloseRuns.ClaimedCheck> next;
        while ((next = runs.claim(UOW, Actor.SYSTEM)).isPresent()) {
            CloseRuns.ClaimedCheck claimed = next.get();
            if (claimed.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
                runs.concludeOpenBreaks(UOW, claimed);
            } else if (claimed.check() == CloseCheckCode.RECONCILIATION_DISCLOSURE) {
                runs.conclude(UOW, claimed, CheckVerdict.disclosed(claimed.check(), "reconciliation-disclosure:v2", CanonicalJson.object().put("suspenseUnowned", 1L)));
            } else {
                runs.conclude(UOW, claimed, CheckVerdict.passed(claimed.check(), "judge:v1", CanonicalJson.object().put("clean", true)));
            }
        }
    }

    private List<PeriodCloseDesk.StatedDisclosure> reasons() {
        return List.of(new PeriodCloseDesk.StatedDisclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE.name(), 1, DISCLOSURE));
    }

    private String period() {
        return march.value().toString();
    }

    private PeriodCloseDesk.PeriodCloseReceipt propose(String key) {
        return as(ACCOUNTANT, () -> desk.propose(key, period(), run.value().toString(), REASON, reasons()));
    }

    @Test
    @DisplayName("the proposal: keyed, a timeout then a retry replays the first answer; the same key with another reason, or another disclosure, is a conflict, never answered as done")
    void theProposalIsKeyed() {
        PeriodCloseDesk.PeriodCloseReceipt first = propose("k-1");
        assertThat(first.status()).isEqualTo("CLOSE_PROPOSED");
        assertThat(first.proposalId()).isNotNull();
        assertThat(first.closeRunId()).isEqualTo(run.value().toString());
        int published = events.size();

        assertThat(propose("k-1")).as("the same key, the same body").isEqualTo(first);
        assertThat(events).as("written once").hasSize(published);

        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.propose("k-1", period(), run.value().toString(), "another reason", reasons()))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.propose("k-1", period(), run.value().toString(), REASON,
                List.of(new PeriodCloseDesk.StatedDisclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE.name(), 1, "another disclosure"))))).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("the proposal's refusals on the contract: not ready 409, no reason 422, stale 409, a malformed run or check stale - never a reason in a detail")
    void theProposalsRefusals() {
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-2", period(), run.value().toString(), REASON, List.of())), AccountingErrorCode.REASON_REQUIRED, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-3", period(), CloseRunId.next(IDS).value().toString(), REASON, reasons())), AccountingErrorCode.CLOSE_PROPOSAL_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-4", period(), "not-a-uuid", REASON, reasons())), AccountingErrorCode.CLOSE_PROPOSAL_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-5", period(), run.value().toString(), REASON,
                List.of(new PeriodCloseDesk.StatedDisclosure("NOT_A_CHECK", 1, DISCLOSURE)))), AccountingErrorCode.CLOSE_PROPOSAL_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-6", UUID.randomUUID().toString(), run.value().toString(), REASON, reasons())), AccountingErrorCode.NOT_FOUND, 404);

        runs.rerun(UOW, march, CloseCheckCode.RULE_CONFORMANCE, ACCOUNTANT, "again", CorrelationId.generate(IDS));
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.propose("k-7", period(), run.value().toString(), REASON, reasons()))).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.errorCode()).isEqualTo(AccountingErrorCode.CLOSE_NOT_READY);
            assertThat(api.errorCode().status()).isEqualTo(409);
            assertThat(api.getMessage()).doesNotContain(DISCLOSURE).doesNotContain(REASON);
        });
    }

    @Test
    @DisplayName("the approval: a repeat of the key replays; a proposer's own is refused 403 and recorded FAILED in its own transaction; another's, CLOSED once; the rejected one's stale")
    void theApproval() {
        String proposal = propose("k-1").proposalId();
        refused(() -> as(ACCOUNTANT, () -> desk.approve("k-a", period(), proposal, REASON)), AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited.get(audited.size() - 1).outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(audited.get(audited.size() - 1).changeSummary().orElseThrow()).contains("act=period:close");

        PeriodCloseDesk.PeriodCloseReceipt closed = as(CONTROLLER, () -> desk.approve("k-b", period(), proposal, REASON));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(as(CONTROLLER, () -> desk.approve("k-b", period(), proposal, REASON))).isEqualTo(closed);
        assertThat(events).filteredOn(PeriodCloses.CLOSED_EVENT::equals).hasSize(1);
        refused(() -> as(new Actor("controller-2", ActorType.CUSTOMER), () -> desk.approve("k-c", period(), proposal, REASON)), AccountingErrorCode.PERIOD_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.approve("k-d", period(), "not-a-uuid", REASON)), AccountingErrorCode.CLOSE_PROPOSAL_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.reject("k-e", period(), proposal, REASON)), AccountingErrorCode.PERIOD_STALE, 409);
    }

    @Test
    @DisplayName("the rejection: LOCKED again; a repeat replays; a next proposal under a new key is a new one")
    void theRejection() {
        String proposal = propose("k-1").proposalId();
        PeriodCloseDesk.PeriodCloseReceipt rejected = as(CONTROLLER, () -> desk.reject("k-r", period(), proposal, "not yet"));
        assertThat(rejected.status()).isEqualTo("LOCKED");
        assertThat(as(CONTROLLER, () -> desk.reject("k-r", period(), proposal, "not yet"))).isEqualTo(rejected);
        assertThat(propose("k-2").proposalId()).isNotEqualTo(proposal);
    }

    @Test
    @DisplayName("the controller's read: recorded before it is served; each attempt, the evidence, the reason per DISCLOSED attempt, whether it still stands; no person, no proposal's reason; none is not found")
    void theControllersRead() {
        refused(() -> as(CONTROLLER, () -> desk.proposal(period())), AccountingErrorCode.NOT_FOUND, 404);
        propose("k-1");

        PeriodCloseDesk.CloseProposalView view = as(CONTROLLER, () -> desk.proposal(period()));
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.periodStatus()).isEqualTo("CLOSE_PROPOSED");
        assertThat(view.stillStands()).isTrue();
        assertThat(view.closeRunId()).isEqualTo(run.value().toString());
        assertThat(view.checks()).hasSize(CloseCheckCode.values().length);
        assertThat(view.checks()).filteredOn(check -> check.check().equals("RECONCILIATION_DISCLOSURE")).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("DISCLOSED");
            assertThat(check.disclosureReason()).isEqualTo(DISCLOSURE);
            assertThat(check.evidence()).as("the attempt's own evidence, read from its row").isEqualTo("{\"suspenseUnowned\":\"1\"}");
        });
        assertThat(view.reopenPending()).isFalse();
        assertThat(view.overtakenReturns()).as("no reopen pending, no list read: none served, never an empty one").isNull();
        assertThat(view.toString()).doesNotContain(ACCOUNTANT.id()).doesNotContain(REASON);
        AuditRecord read = audited.get(audited.size() - 1);
        assertThat(read.operation().code()).isEqualTo("accounting.CloseProposalRead");
        assertThat(read.changeSummary().orElseThrow()).doesNotContain(DISCLOSURE);
    }

    @Test
    @DisplayName("the reopen: keyed; proposed by one, approved by another; refused to its proposer, recorded; a busy cutoff 503, nothing kept, and the retry goes through")
    void theReopen() {
        String proposal = propose("k-1").proposalId();
        as(CONTROLLER, () -> desk.approve("k-b", period(), proposal, REASON));

        PeriodCloseDesk.PeriodCloseReceipt proposed = as(ACCOUNTANT, () -> desk.proposeReopen("k-p", period(), List.of(), "a correction"));
        assertThat(proposed.status()).isEqualTo("CLOSED");
        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-p", period(), List.of(), "a correction"))).isEqualTo(proposed);
        refused(() -> as(ACCOUNTANT, () -> desk.approveReopen("k-s", period(), reopen(), List.of(), "mine")), AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited.get(audited.size() - 1).outcome()).isEqualTo(AuditOutcome.FAILED);

        cutoffBusy = true;
        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-o", period(), reopen(), List.of(), "agreed")), AccountingErrorCode.CUTOFF_BUSY, 503);
        cutoffBusy = false;

        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-o2", period(), reopen(), List.of(), "agreed"));
        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN.name());
        assertThat(reopened.revision()).isEqualTo(2);
        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-o2", period(), reopen(), List.of(), "agreed"))).isEqualTo(reopened);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-q", period(), List.of(), "again")), AccountingErrorCode.PERIOD_STALE, 409);
    }

    @Test
    @DisplayName("the routes are the partition: every act keyed; the accountant proposes, the controller decides and reads what it decides")
    void theRoutesAreThePartition() throws Exception {
        Map<String, PermissionName> expected = Map.of("proposeAccountingPeriodClose", PermissionName.ACCOUNTING_CLOSE_PROPOSE, "readAccountingPeriodCloseProposal", PermissionName.ACCOUNTING_CLOSE_APPROVE,
                "approveAccountingPeriodClose", PermissionName.ACCOUNTING_CLOSE_APPROVE, "rejectAccountingPeriodClose", PermissionName.ACCOUNTING_CLOSE_APPROVE, "proposeAccountingPeriodReopen",
                PermissionName.ACCOUNTING_CLOSE_PROPOSE, "approveAccountingPeriodReopen", PermissionName.ACCOUNTING_CLOSE_APPROVE, "rejectAccountingPeriodReopen", PermissionName.ACCOUNTING_CLOSE_APPROVE,
                "withdrawAccountingPeriodReopen", PermissionName.ACCOUNTING_CLOSE_PROPOSE, "readAccountingPeriodRequests", PermissionName.ACCOUNTING_CLOSE_APPROVE);
        int seen = 0;
        for (Method method : PeriodCloseController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                seen++;
                // P14-TSK-024, the owner's acceptance: the close's and the reopen's approvals step up; an end, a proposal, a rejection of a close and a read do not.
                boolean stepsUp = method.getName().equals("approveAccountingPeriodClose") || method.getName().equals("approveAccountingPeriodReopen");
                assertThat(Optional.ofNullable(method.getAnnotation(com.finapp.app.session.RequiresAssurance.class)).map(com.finapp.app.session.RequiresAssurance::value)).as(method.getName())
                        .isEqualTo(stepsUp ? Optional.of(com.finapp.identity.AssuranceLevel.MULTI_FACTOR) : Optional.empty());
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName()).isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName()).isEqualTo(!method.getName().startsWith("read"));
            }
        }
        assertThat(seen).isEqualTo(expected.size());
    }

    @Test
    @DisplayName("P14-TSK-013: a reopen's two people and a re-close's approver are told what has posted against the month - its figures at the next revision still show what those corrected; a close's own acts name nothing; the replay answers the same")
    void aReopenIsToldWhatHasPosted() {
        PeriodCloseDesk.PeriodCloseReceipt proposal = propose("k-t1");
        assertThat(proposal.priorPeriodAdjustments()).isEmpty();
        assertThat(as(CONTROLLER, () -> desk.approve("k-t2", period(), proposal.proposalId(), "agreed")).priorPeriodAdjustments()).isEmpty();
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, MARCH.plusMonths(1), CorrelationId.generate(IDS)));
        com.finapp.accounting.PriorPeriodAdjustmentId adjustment = com.finapp.accounting.PriorPeriodAdjustmentId.next(IDS);
        priorPeriod.insert(UOW, new com.finapp.accounting.PriorPeriodAdjustmentStore.NewAdjustment(adjustment, march, 1, april, 1, MARCH.plusMonths(1).atDay(5), UUID.randomUUID(), ACCOUNTANT, "a correction", CorrelationId.generate(IDS)));
        assertThat(priorPeriod.markPosted(UOW, adjustment, UUID.randomUUID(), CONTROLLER, "agreed")).isTrue();
        com.finapp.accounting.PriorPeriodAdjustmentId rejected = com.finapp.accounting.PriorPeriodAdjustmentId.next(IDS);
        priorPeriod.insert(UOW, new com.finapp.accounting.PriorPeriodAdjustmentStore.NewAdjustment(rejected, march, 1, april, 1, MARCH.plusMonths(1).atDay(5), UUID.randomUUID(), ACCOUNTANT, "a correction", CorrelationId.generate(IDS)));
        assertThat(priorPeriod.markRejected(UOW, rejected, CONTROLLER, "no")).isTrue();

        List<String> named = List.of(adjustment.value().toString());
        PeriodCloseDesk.PeriodCloseReceipt reopening = as(ACCOUNTANT, () -> desk.proposeReopen("k-t3", period(), named, "found something"));
        assertThat(reopening.priorPeriodAdjustments()).as("the proposer is told, the rejected one corrected nothing").containsExactly(adjustment.value().toString());
        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-t3", period(), named, "found something"))).as("the stored receipt").isEqualTo(reopening);

        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-t4", period(), reopen(), named, "agreed")).priorPeriodAdjustments()).as("the approver is told").containsExactly(adjustment.value().toString());
    }

    /** {@code march} closed, with one adjustment posted against it. */
    private String closedWithOnePosted(String key) {
        PeriodCloseDesk.PeriodCloseReceipt proposal = propose(key + "-p");
        as(CONTROLLER, () -> desk.approve(key + "-a", period(), proposal.proposalId(), "agreed"));
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, MARCH.plusMonths(1), CorrelationId.generate(IDS)));
        return posted(april);
    }

    private String posted(AccountingPeriodId april) {
        com.finapp.accounting.PriorPeriodAdjustmentId adjustment = com.finapp.accounting.PriorPeriodAdjustmentId.next(IDS);
        priorPeriod.insert(UOW, new com.finapp.accounting.PriorPeriodAdjustmentStore.NewAdjustment(adjustment, march, 1, april, 1, MARCH.plusMonths(1).atDay(5), UUID.randomUUID(), ACCOUNTANT, "a correction", CorrelationId.generate(IDS)));
        assertThat(priorPeriod.markPosted(UOW, adjustment, UUID.randomUUID(), CONTROLLER, "agreed")).isTrue();
        return adjustment.value().toString();
    }

    /** {@code march} closed, nothing posted against it, by these keys. */
    private void closed(String key) {
        PeriodCloseDesk.PeriodCloseReceipt proposal = propose(key + "-p");
        assertThat(proposal.overtakenReturns()).as("M-6: a close's acts read no list, so serve none, never an empty one").isNull();
        assertThat(proposal.overtakenReturnsTotal()).isNull();
        assertThat(proposal.overtakenReturnsNote()).as("and say nothing of one").isNull();
        PeriodCloseDesk.PeriodCloseReceipt closed = as(CONTROLLER, () -> desk.approve(key + "-a", period(), proposal.proposalId(), "agreed"));
        assertThat(closed.overtakenReturns()).isNull();
        assertThat(closed.overtakenReturnsTotal()).isNull();
        assertThat(closed.overtakenReturnsNote()).isNull();
    }

    private ReturnsAReopenOvertakes.Item aReturn(SubmissionStatus status) {
        return new ReturnsAReopenOvertakes.Item(RegulatorySubmissionId.next(IDS), "REF-SAFEGUARDING_POSITION-2", status);
    }

    private static PeriodCloseDesk.OvertakenReturn listed(ReturnsAReopenOvertakes.Item item) {
        return new PeriodCloseDesk.OvertakenReturn(item.id().value().toString(), item.form(), item.status().name());
    }

    @Test
    @DisplayName("P14-TSK-023 (a): each of a reopen's two receipts lists the returns it overtakes, read at that act: id, form and status, the approval's holding what was sent since the proposal, and neither stops anything or is named back")
    void aReopensReceiptsListTheReturnsItOvertakes() throws Exception {
        closed("k-l1");
        ReturnsAReopenOvertakes.Item acknowledged = aReturn(SubmissionStatus.ACKNOWLEDGED);
        sent.add(acknowledged);

        PeriodCloseDesk.PeriodCloseReceipt proposed = as(ACCOUNTANT, () -> desk.proposeReopen("k-l2", period(), List.of(), "a correction"));

        assertThat(proposed.status()).as("it stopped nothing").isEqualTo("CLOSED");
        assertThat(proposed.overtakenReturns()).containsExactly(listed(acknowledged));
        assertThat(proposed.overtakenReturnsTotal()).isEqualTo(1);
        assertThat(proposed.overtakenReturnsNote()).isEqualTo(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE);

        ReturnsAReopenOvertakes.Item since = aReturn(SubmissionStatus.SUBMITTED);
        sent.add(0, since);
        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-l3", period(), reopen(), List.of(), "agreed"));

        assertThat(reopened.status()).as("it stopped nothing").isEqualTo("OPEN");
        assertThat(reopened.overtakenReturns()).as("read at this act, not the proposal's").containsExactly(listed(since), listed(acknowledged));
        assertThat(reopened.overtakenReturnsTotal()).isEqualTo(2);
        assertThat(reopened.overtakenReturnsNote()).isEqualTo(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE);
        assertThat(PeriodCloseController.PeriodReopenRequest.class.getRecordComponents()).as("nothing to name back").extracting(java.lang.reflect.RecordComponent::getName).containsExactly("reason", "postedAdjustments");
    }

    @Test
    @DisplayName("P14-TSK-023 (a): a month with no returns reads an empty list and a total of none, never an absence, on both receipts and on a replay, and the words say an empty list is not nothing in flight")
    void aMonthWithNoReturnsReadsAnEmptyList() throws Exception {
        closed("k-e1");

        PeriodCloseDesk.PeriodCloseReceipt proposed = as(ACCOUNTANT, () -> desk.proposeReopen("k-e2", period(), List.of(), "a correction"));
        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-e3", period(), reopen(), List.of(), "agreed"));

        for (PeriodCloseDesk.PeriodCloseReceipt receipt : List.of(proposed, reopened)) {
            assertThat(receipt.overtakenReturns()).isNotNull().isEmpty();
            assertThat(receipt.overtakenReturnsTotal()).isZero();
            assertThat(receipt.overtakenReturnsNote()).contains("does not mean nothing is in flight");
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(receipt)).contains("\"overtakenReturns\":[]").contains("\"overtakenReturnsTotal\":0");
            assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-e2", period(), List.of(), "a correction"))).as("read, and none, survives its replay as read, and none").isEqualTo(proposed);
        }
    }

    @Test
    @DisplayName("P14-TSK-023 (a): the words say what the list is and is not: information, read at the act, it decides nothing and a status may have moved; what it leaves out, APPROVED and UNDELIVERED; and the one door that lists what stands is real, asks what they say, and neither of a reopen's two people holds it")
    void theWordsSayWhatTheListIsNot() throws Exception {
        assertThat(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE).contains("Information only").contains("it stopped nothing and decides nothing").contains("a status may have moved since").contains("SUBMITTED or ACKNOWLEDGED")
                .contains("at most 20 of overtakenReturnsTotal").contains("a return not yet sent").contains("One APPROVED can still be sent").contains("UNDELIVERED").contains("does not mean nothing is in flight");
        assertThat(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE).contains("GET /v1/operator/regulatory/submissions under REGULATORY_REPORT_PREPARE");
        assertThat("/v1" + RegulatorySubmissionController.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).path()[0]).isEqualTo("/v1/operator/regulatory/submissions");
        assertThat(RegulatorySubmissionController.class.getMethod("listStandingRegulatorySubmissions", String.class, Integer.class).getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.REGULATORY_REPORT_PREPARE);
        assertThat(java.util.Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(PermissionName.REGULATORY_REPORT_PREPARE))
                .filter(role -> role.permissions().contains(PermissionName.ACCOUNTING_CLOSE_PROPOSE) || role.permissions().contains(PermissionName.ACCOUNTING_CLOSE_APPROVE)).toList()).as("as the words say").isEmpty();
    }

    @Test
    @DisplayName("P14-TSK-023 (a): a key's replay returns the stored list, whatever the returns have since done; a repeat under a new key converges and reads what stands now; the receipt is no part of the fingerprint")
    void aReplayReturnsTheStoredList() {
        closed("k-s1");
        ReturnsAReopenOvertakes.Item first = aReturn(SubmissionStatus.SUBMITTED);
        sent.add(first);
        PeriodCloseDesk.PeriodCloseReceipt proposed = as(ACCOUNTANT, () -> desk.proposeReopen("k-s2", period(), List.of(), "a correction"));

        ReturnsAReopenOvertakes.Item moved = new ReturnsAReopenOvertakes.Item(first.id(), first.form(), SubmissionStatus.ACKNOWLEDGED);
        sent.set(0, moved);
        sent.add(aReturn(SubmissionStatus.ACKNOWLEDGED));

        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-s2", period(), List.of(), "a correction"))).as("the stored receipt, not what stands").isEqualTo(proposed);
        PeriodCloseDesk.PeriodCloseReceipt again = as(ACCOUNTANT, () -> desk.proposeReopen("k-s3", period(), List.of(), "a correction"));
        assertThat(again.overtakenReturns()).as("the same person's repeat converges, and reads what stands").hasSize(2).contains(listed(moved));

        UUID some = UUID.randomUUID();
        assertThat(PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, Optional.empty(), java.util.Set.of(), "r")).as("a proposal naming none, exactly as before").isEqualTo("reopen-proposal|" + march.value() + "|r");
        assertThat(PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, Optional.empty(), java.util.Set.of(some), "r")).as("one naming some, exactly as before").isEqualTo("reopen-proposal-named|" + march.value() + "|[" + some + "]|r");
        com.finapp.accounting.PeriodReopenId named = com.finapp.accounting.PeriodReopenId.next(IDS);
        assertThat(PeriodCloseDesk.reopenFingerprint("reopen-approval", march, Optional.of(named), java.util.Set.of(), "r")).as("an approval names the reopen it decides (P14-TSK-024)")
                .isEqualTo("reopen-approval|" + march.value() + "@" + named.value() + "|r");
        assertThat(PeriodCloseDesk.reopenFingerprint("reopen-approval", march, Optional.of(named), java.util.Set.of(some), "r")).isEqualTo("reopen-approval-named|" + march.value() + "@" + named.value() + "|[" + some + "]|r");
    }

    @Test
    @DisplayName("P14-TSK-023 (a): the bound: a month holding more than a receipt lists serves the newest twenty and how many there are, and the stored receipt holds no more than that, a form's every character surviving it")
    void aLongMonthIsBounded() {
        closed("k-b1");
        for (int held = 0; held < ReturnsAReopenOvertakes.NEWEST; held++) {
            sent.add(aReturn(SubmissionStatus.ACKNOWLEDGED));
        }
        sentInAll = 47;

        PeriodCloseDesk.PeriodCloseReceipt proposed = as(ACCOUNTANT, () -> desk.proposeReopen("k-b2", period(), List.of(), "a correction"));

        assertThat(proposed.overtakenReturns()).hasSize(20).allSatisfy(item -> assertThat(item.form()).isEqualTo("REF-SAFEGUARDING_POSITION-2"));
        assertThat(proposed.overtakenReturnsTotal()).as("more than it lists").isEqualTo(47);
        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-b2", period(), List.of(), "a correction"))).isEqualTo(proposed);
        IdempotencyKey key = new IdempotencyKey(AccountingPeriodDesk.SCOPE + ACCOUNTANT.type().name() + ":" + ACCOUNTANT.id(), "k-b2");
        assertThat(claims.get(key).response().body()).as("what is stored is bounded: twenty of about a hundred bytes, and no word").hasSizeLessThan(3_000);
        assertThat(new String(claims.get(key).response().body(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Information only");
    }

    @Test
    @DisplayName("P14-TSK-023 (a): the read failing fails the act, nothing kept, never an empty list for what could not be read; the same key then goes through")
    void aReadThatFailsIsNoEmptyList() {
        closed("k-f0");
        sentUnreadable = true;

        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-f1", period(), List.of(), "a correction"))).isInstanceOf(AccountingStorageException.class);
        assertThat(closes.reopens()).as("nothing kept").isEmpty();

        sentUnreadable = false;
        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-f1", period(), List.of(), "a correction")).overtakenReturnsNote()).isNotNull();
    }

    @Test
    @DisplayName("P14-TSK-022 (m): a reopen's two acts name what has posted: naming none or another is the adjustment's own 409 accounting.PriorPeriodAdjustmentStale with nothing kept, and the same key then goes through; a malformed id is that refusal, never a 500; the detail names a count, never an id")
    void aReopenNamesWhatHasPosted() {
        String adjustment = closedWithOnePosted("k-n1");

        refused(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-n2", period(), List.of(), "found something")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-n3", period(), null, "found something")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-n4", period(), List.of(UUID.randomUUID().toString()), "found something")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-n5", period(), List.of("not-a-uuid"), "found something")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-n2", period(), List.of(), "found something")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.clientDetail().orElseThrow()).contains("1 stand").doesNotContain(adjustment));
        assertThat(closes.reopens()).isEmpty();

        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-n2", period(), List.of(adjustment), "found something")).status()).as("a refusal stored nothing under the key").isEqualTo("CLOSED");
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-n2", period(), List.of(), "found something"))).as("the same key naming another set").isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-n2", period(), List.of(UUID.randomUUID().toString()), "found something")))
                .as("the same key naming another set, neither empty: the fingerprint covers the set itself, not only whether one is named").isInstanceOf(IdempotencyConflictException.class);

        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-n6", period(), reopen(), List.of(), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        assertThat(periods.find(UOW, march).orElseThrow().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-n6", period(), reopen(), List.of(adjustment), "agreed")).status()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("P14-TSK-022 (n): an adjustment posted after the proposal: the approver reads the list at GET close-proposal, under ACCOUNTING_CLOSE_APPROVE on a CLOSED month, names it, and lands; naming the proposer's list is refused")
    void theApproverReadsTheListItNames() throws Exception {
        String first = closedWithOnePosted("k-r1");
        as(ACCOUNTANT, () -> desk.proposeReopen("k-r2", period(), List.of(first), "found something"));
        String second = posted(periods.lockShared(UOW, MARCH.plusMonths(1)).orElseThrow().id());

        assertThat(PeriodCloseController.class.getMethod("readAccountingPeriodCloseProposal", String.class).getAnnotation(RequiresPermission.class).value()).isEqualTo(PermissionName.ACCOUNTING_CLOSE_APPROVE);
        PeriodCloseDesk.CloseProposalView read = as(CONTROLLER, () -> desk.proposal(period()));
        assertThat(read.periodStatus()).isEqualTo("CLOSED");
        assertThat(read.priorPeriodAdjustments()).containsExactlyInAnyOrder(first, second);

        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-r3", period(), reopen(), List.of(first), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-r3", period(), reopen(), read.priorPeriodAdjustments(), "agreed"));
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.priorPeriodAdjustments()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("P14-TSK-022, P14-TSK-023: a stored reopen receipt stays readable: a row from before P14-TSK-013 has six fields, one from before P14-TSK-023 seven; each replays with its list, total and note null, since it read no list (M-6); and a body naming none fingerprints exactly as it did, so a retry across the deploy replays and is no conflict")
    void aStoredReceiptStaysReadable() throws Exception {
        List<PeriodCloseDesk.PeriodCloseReceipt> served = new java.util.ArrayList<>();
        as(ACCOUNTANT, () -> {
            String adjustment = UUID.randomUUID().toString();
            Map<String, java.util.function.Consumer<PeriodCloseDesk.PeriodCloseReceipt>> rows = new java.util.LinkedHashMap<>();
            rows.put(march.value() + "|" + MARCH + "|CLOSED|1||", replayed -> assertThat(replayed.priorPeriodAdjustments()).isEmpty());
            rows.put(march.value() + "|" + MARCH + "|CLOSED|1|||", replayed -> assertThat(replayed.priorPeriodAdjustments()).isEmpty());
            rows.put(march.value() + "|" + MARCH + "|CLOSED|1|||" + adjustment, replayed -> assertThat(replayed.priorPeriodAdjustments()).containsExactly(adjustment));
            int row = 0;
            for (Map.Entry<String, java.util.function.Consumer<PeriodCloseDesk.PeriodCloseReceipt>> old : rows.entrySet()) {
                String key = "k-old-" + row++;
                String fingerprinted = PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, Optional.empty(), java.util.Set.of(), "from before");
                assertThat(fingerprinted).isEqualTo("reopen-proposal|" + march.value() + "|from before");
                IdempotencyKey stored = new IdempotencyKey(AccountingPeriodDesk.SCOPE + ACCOUNTANT.type().name() + ":" + ACCOUNTANT.id(), key);
                claims.put(stored, new IdempotencyRecord(stored, RequestFingerprint.sha256(fingerprinted.getBytes(java.nio.charset.StandardCharsets.UTF_8)), IdempotencyState.COMPLETED,
                        StoredResponse.of(old.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain"), CorrelationId.generate(IDS), CLOCK.instant(), CLOCK.instant().plus(Duration.ofDays(1))));

                PeriodCloseDesk.PeriodCloseReceipt replayed = desk.proposeReopen(key, period(), null, "from before");

                assertThat(replayed.status()).isEqualTo("CLOSED");
                assertThat(replayed.revision()).isEqualTo(1);
                assertThat(replayed.proposalId()).isNull();
                assertThat(replayed.closeRunId()).isNull();
                old.getValue().accept(replayed);
                assertThat(replayed.overtakenReturns()).as("M-6: it read no list, so it serves none, never an empty one").isNull();
                assertThat(replayed.overtakenReturnsTotal()).isNull();
                assertThat(replayed.overtakenReturnsNote()).as("and says nothing of one").isNull();
                served.add(replayed);
            }
            return null;
        });
        assertThat(served).hasSize(3);
        for (PeriodCloseDesk.PeriodCloseReceipt replayed : served) {
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(replayed)).as("served as null").contains("\"overtakenReturns\":null").contains("\"overtakenReturnsTotal\":null");
        }
        UUID some = UUID.randomUUID();
        assertThat(PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, Optional.empty(), java.util.Set.of(some), "r")).as("another form, whatever a reason holds")
                .isNotEqualTo(PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, Optional.empty(), java.util.Set.of(), "[" + some + "]|r"));
    }

    @Test
    @DisplayName("P14-TSK-022: a reopen's body: a null among the names is the platform's validation failure at the door, never a NullPointerException and a 500; its absence names none; its bound is the most a month can hold, one above what an adjustment's approval may name, so a full month can still reopen")
    void aReopensBodyIsBounded() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new PeriodCloseController.PeriodReopenRequest(REASON, java.util.Arrays.asList((String) null))))
                    .singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).startsWith("postedAdjustments[0]"));
            assertThat(validator.validate(new PeriodCloseController.PeriodReopenRequest(REASON, null))).isEmpty();
            // P14-TSK-024: a decision on a pending request names it - absent, the platform's validation failure at the door.
            assertThat(validator.validate(new PeriodCloseController.ReopenApprovalRequest(null, REASON, null))).singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("reopenId"));
            assertThat(validator.validate(new PeriodCloseController.ReopenEndRequest(null, REASON))).singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("reopenId"));
            assertThat(validator.validate(new AccountingPeriodController.UnlockDecisionRequest(null, REASON))).singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("unlockProposedAt"));
            assertThat(validator.validate(new PeriodCloseController.ReopenApprovalRequest(UUID.randomUUID().toString(), REASON, java.util.Collections.nCopies(com.finapp.accounting.PriorPeriodAdjustments.MAX_POSTED_CORRECTIONS + 1, UUID.randomUUID().toString()))))
                    .as("an approval's names bounded as the proposal's").isNotEmpty();
            int named = com.finapp.accounting.PriorPeriodAdjustments.MAX_NAMED_CORRECTIONS;
            int most = com.finapp.accounting.PriorPeriodAdjustments.MAX_POSTED_CORRECTIONS;
            assertThat(named).isEqualTo(200);
            assertThat(most).as("an approval names those posted and adds itself").isEqualTo(named + 1);
            String id = UUID.randomUUID().toString();
            assertThat(validator.validate(new PeriodCloseController.PeriodReopenRequest(REASON, java.util.Collections.nCopies(most, id)))).as("a reopen names all a month can hold").isEmpty();
            assertThat(validator.validate(new PeriodCloseController.PeriodReopenRequest(REASON, java.util.Collections.nCopies(most + 1, id)))).isNotEmpty();
            assertThat(validator.validate(new PriorPeriodAdjustmentController.PriorPeriodApprovalRequest("2097-04-05", 1, java.util.Collections.nCopies(named, id), REASON))).as("an approval names the 200 before it").isEmpty();
            assertThat(validator.validate(new PriorPeriodAdjustmentController.PriorPeriodApprovalRequest("2097-04-05", 1, java.util.Collections.nCopies(named + 1, id), REASON))).as("so the 202nd can never post").isNotEmpty();
        }
    }

    @Test
    @DisplayName("P14-TSK-022's review, M-1: a month at the most it can hold still reopens: 201 posted, named in the bodies a door admits, proposed and approved, the receipt listing them all")
    void aFullMonthStillReopens() {
        String first = closedWithOnePosted("k-f1");
        AccountingPeriodId april = periods.lockShared(UOW, MARCH.plusMonths(1)).orElseThrow().id();
        List<String> all = new ArrayList<>(List.of(first));
        while (all.size() < com.finapp.accounting.PriorPeriodAdjustments.MAX_POSTED_CORRECTIONS) {
            all.add(posted(april));
        }
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new PeriodCloseController.PeriodReopenRequest("found something", all))).as("the door admits the body").isEmpty();
        }

        as(ACCOUNTANT, () -> desk.proposeReopen("k-f2", period(), all, "found something"));
        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-f3", period(), reopen(), all, "agreed"));

        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.priorPeriodAdjustments()).hasSize(201);
        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-f3", period(), reopen(), all, "agreed"))).as("and its long receipt replays").isEqualTo(reopened);
    }

    // ------------------------------------------------------------------ P14-TSK-024: a reopen's end, and what the approver reads before deciding

    @Test
    @DisplayName("P14-TSK-024 (d): a reopen's rejection - a second person's: keyed, the key's replay the stored receipt, another body a conflict; CLOSED at the same revision, nothing published, no list read; the proposer's own 403 and recorded FAILED; the approval after it told by name; the month free")
    void aReopensRejection() {
        closed("k-x1");
        as(ACCOUNTANT, () -> desk.proposeReopen("k-x2", period(), List.of(), "a correction"));
        int published = events.size();
        refused(() -> as(ACCOUNTANT, () -> desk.rejectReopen("k-x3", period(), reopen(), "mine")), AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited.get(audited.size() - 1).outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(audited.get(audited.size() - 1).changeSummary().orElseThrow()).contains("act=period:reopen-rejection");

        PeriodCloseDesk.PeriodCloseReceipt rejected = as(CONTROLLER, () -> desk.rejectReopen("k-x4", period(), reopen(), "make it a prior-period adjustment"));

        assertThat(rejected.status()).isEqualTo("CLOSED");
        assertThat(rejected.revision()).isEqualTo(1);
        assertThat(rejected.overtakenReturns()).as("an end overtakes nothing, so no list is read or served").isNull();
        assertThat(rejected.overtakenReturnsNote()).isNull();
        assertThat(rejected.priorPeriodAdjustments()).isEmpty();
        assertThat(events).hasSize(published);
        assertThat(as(CONTROLLER, () -> desk.rejectReopen("k-x4", period(), reopen(), "make it a prior-period adjustment"))).as("the key's replay").isEqualTo(rejected);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.rejectReopen("k-x4", period(), reopen(), "another reason"))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.approveReopen("k-x5", period(), reopen(), List.of(), "late")))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.PERIOD_STALE);
                    assertThat(refused.clientDetail().orElseThrow()).contains("was REJECTED").doesNotContain("prior-period");
                });
        assertThat(closes.reopens()).extracting(row -> row.status().name()).containsExactly("REJECTED");
        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-x6", period(), List.of(), "another correction")).status()).as("free for a new one").isEqualTo("CLOSED");
        assertThat(audited).extracting(record -> record.operation().code()).contains("accounting.PeriodReopenRejected");
    }

    @Test
    @DisplayName("P14-TSK-024 (d): a reopen's withdrawal - its proposer's alone: anyone else 403 accounting.NotTheProposer, recorded FAILED with nothing kept under its key; the proposer's keyed, CLOSED; none pending then 409 by name")
    void aReopensWithdrawal() {
        closed("k-w1");
        as(ACCOUNTANT, () -> desk.proposeReopen("k-w2", period(), List.of(), "a correction"));
        refused(() -> as(CONTROLLER, () -> desk.withdrawReopen("k-w3", period(), reopen(), "theirs")), AccountingErrorCode.NOT_THE_PROPOSER, 403);
        AuditRecord kept = audited.get(audited.size() - 1);
        assertThat(kept.operation().code()).as("recorded FAILED in its own transaction (the fix round's M-2)").isEqualTo("accounting.NotTheProposer");
        assertThat(kept.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(kept.actor()).isEqualTo(CONTROLLER);
        assertThat(kept.changeSummary().orElseThrow()).isEqualTo("period=" + MARCH + ", act=period:reopen-withdrawal");
        assertThat(claims.keySet()).noneMatch(key -> key.key().equals("k-w3"));
        refused(() -> as(ACCOUNTANT, () -> desk.withdrawReopen("k-w4", period(), reopen(), "")), AccountingErrorCode.REASON_REQUIRED, 422);
        assertThat(closes.reopens()).extracting(row -> row.status().name()).containsExactly("PROPOSED");

        assertThat(as(ACCOUNTANT, () -> desk.withdrawReopen("k-w4", period(), reopen(), "proposed in error")).status()).isEqualTo("CLOSED");

        assertThat(closes.reopens()).extracting(row -> row.status().name()).containsExactly("WITHDRAWN");
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.rejectReopen("k-w5", period(), reopen(), "late"))).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.clientDetail().orElseThrow()).contains("was WITHDRAWN"));
        refused(() -> as(ACCOUNTANT, () -> desk.withdrawReopen("k-w6", UUID.randomUUID().toString(), reopen(), "none")), AccountingErrorCode.NOT_FOUND, 404);
    }

    @Test
    @DisplayName("P14-TSK-024 (-023's a): on a pending reopen the approver reads, before deciding, the returns it would overtake - the receipts' statement on the read's own transaction, newest first with the total - and a note saying it decides nothing and that the approval's receipt may differ; recorded with how many; no reopen pending, none read and none served")
    void theApproverIsToldBeforeDeciding() {
        closed("k-o1");
        PeriodCloseDesk.CloseProposalView closedRead = as(CONTROLLER, () -> desk.proposal(period()));
        assertThat(closedRead.reopenPending()).isFalse();
        assertThat(closedRead.reopenProposedAt()).isNull();
        assertThat(closedRead.reopenId()).isNull();
        assertThat(closedRead.overtakenReturns()).as("no reopen pending, no list read: none served").isNull();
        assertThat(closedRead.overtakenReturnsTotal()).isNull();
        assertThat(closedRead.overtakenReturnsNote()).isNull();

        ReturnsAReopenOvertakes.Item acknowledged = aReturn(SubmissionStatus.ACKNOWLEDGED);
        ReturnsAReopenOvertakes.Item submitted = aReturn(SubmissionStatus.SUBMITTED);
        sent.add(submitted);
        sent.add(acknowledged);
        sentInAll = 23;
        as(ACCOUNTANT, () -> desk.proposeReopen("k-o2", period(), List.of(), "a correction"));

        PeriodCloseDesk.CloseProposalView read = as(CONTROLLER, () -> desk.proposal(period()));

        assertThat(read.reopenPending()).isTrue();
        assertThat(read.reopenProposedAt()).isEqualTo(closes.reopens().get(0).proposedAt().toString());
        assertThat(read.reopenId()).as("what the approval names").isEqualTo(closes.reopens().get(0).id().value().toString());
        assertThat(read.overtakenReturns()).containsExactly(listed(submitted), listed(acknowledged));
        assertThat(read.overtakenReturnsTotal()).isEqualTo(23);
        assertThat(read.overtakenReturnsNote()).isEqualTo(PeriodCloseDesk.PENDING_REOPEN_NOTE);
        AuditRecord recorded = audited.get(audited.size() - 1);
        assertThat(recorded.operation().code()).isEqualTo("accounting.CloseProposalRead");
        assertThat(recorded.changeSummary().orElseThrow()).contains("reopenPending=true").contains("overtakenReturns=23").doesNotContain(submitted.id().value().toString());

        sent.clear();
        sentInAll = -1;
        PeriodCloseDesk.CloseProposalView none = as(CONTROLLER, () -> desk.proposal(period()));
        assertThat(none.overtakenReturns()).as("read, and none: an empty list, never an absence").isNotNull().isEmpty();
        assertThat(none.overtakenReturnsTotal()).isZero();

        sentUnreadable = true;
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.proposal(period()))).as("a list that cannot be read fails the read, never an empty one").isInstanceOf(AccountingStorageException.class);
        sentUnreadable = false;

        as(ACCOUNTANT, () -> desk.withdrawReopen("k-o3", period(), reopen(), "proposed in error"));
        assertThat(as(CONTROLLER, () -> desk.proposal(period())).overtakenReturns()).as("ended: none pending, none read").isNull();
    }

    @Test
    @DisplayName("P14-TSK-024: the read's note says what the receipts' says of the list, and that it was read holding nothing and decides nothing, and that the approval's receipt lists what stands at the approval, which can differ; the receipts' note is unchanged")
    void theReadsNoteSaysItMayDiffer() {
        assertThat(PeriodCloseDesk.PENDING_REOPEN_NOTE).contains("Information only").contains("holding nothing").contains("it decides nothing").contains("a status may have moved since")
                .contains("The approval's receipt lists what stands when the approval holds the month, so it differs from this list when a return was sent or concluded in between")
                .contains("SUBMITTED or ACKNOWLEDGED").contains("at most 20 of overtakenReturnsTotal").contains("does not mean nothing is in flight").contains("GET /v1/operator/regulatory/submissions under REGULATORY_REPORT_PREPARE");
        assertThat(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE).startsWith("Information only, read while this act held the month: it stopped nothing and decides nothing, and a status may have moved since. Each return SUBMITTED or ACKNOWLEDGED");
        assertThat(PeriodCloseDesk.PENDING_REOPEN_NOTE.substring(PeriodCloseDesk.PENDING_REOPEN_NOTE.indexOf(" Each return"))).as("the same words of what is listed")
                .isEqualTo(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE.substring(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE.indexOf(" Each return")));
    }

    @Test
    @DisplayName("P14-TSK-024 (-012's j): every check the approver reads carries when its attempt concluded - the attempt's own concludedAt, never another stamp - so the person deciding sees as of when")
    void eachCheckSaysWhenItConcluded() {
        propose("k-c1");
        PeriodCloseDesk.CloseProposalView view = as(CONTROLLER, () -> desk.proposal(period()));
        Map<String, String> concluded = new HashMap<>();
        runs.view(UOW, march).orElseThrow().checks().forEach(check -> concluded.put(check.check().name(), check.latest().concludedAt().orElseThrow().toString()));
        assertThat(view.checks()).hasSize(CloseCheckCode.values().length).allSatisfy(check -> assertThat(check.concludedAt()).as(check.check()).isNotNull().isEqualTo(concluded.get(check.check())));
        assertThat(view.checks()).allSatisfy(check -> assertThat(check.concludedAt()).isNotEqualTo(view.proposedAt()).isNotEqualTo(view.lockedAt()));
    }

    @Test
    @DisplayName("the independent review, M-3: no body and no view prints a reason or evidence, whichever a log line were handed")
    void aBodysTextHoldsNoReason() {
        String secret = "a-persons-reason";
        for (Object record : List.of(new PeriodCloseController.CloseProposalRequest("run", secret, List.of(new PeriodCloseController.CloseDisclosureItem("CHECK", 1, secret))),
                new PeriodCloseController.CloseDisclosureItem("CHECK", 1, secret), new PeriodCloseController.CloseDecisionRequest("proposal", secret), new PeriodCloseController.PeriodReopenRequest(secret, List.of(UUID.randomUUID().toString())),
                new PeriodCloseDesk.StatedDisclosure("CHECK", 1, secret), new PeriodCloseDesk.CloseProposalCheckView("CHECK", 1, "DISCLOSED", "judge:v1", "2097-04-05T09:00:00Z", secret, secret),
                new PeriodCloseController.ReopenEndRequest(UUID.randomUUID().toString(), secret), new PeriodCloseController.ReopenApprovalRequest(UUID.randomUUID().toString(), secret, List.of()),
                new AccountingPeriodController.UnlockDecisionRequest("2097-04-05T09:00:00Z", secret))) {
            assertThat(record.toString()).as(record.getClass().getSimpleName()).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("the independent review, M-4: a null among the disclosures is the platform's validation failure at the door, never a NullPointerException and a 500; its absence is no disclosure")
    void aNullDisclosureIsAValidationFailure() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new PeriodCloseController.CloseProposalRequest("run", REASON, java.util.Arrays.asList((PeriodCloseController.CloseDisclosureItem) null))))
                    .singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).startsWith("disclosures[0]"));
            assertThat(validator.validate(new PeriodCloseController.CloseProposalRequest("run", REASON, null))).isEmpty();
            assertThat(validator.validate(new PeriodCloseController.CloseProposalRequest("run", REASON, List.of(new PeriodCloseController.CloseDisclosureItem("CHECK", 1, "x".repeat(1001))))))
                    .as("the cascade still reaches each item").singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("disclosures[0].reason"));
        }
    }

    @Test
    @DisplayName("P14-TSK-024's fix round (M-1): each act on a pending reopen names it - withdrawn and another proposed, the approval naming the first is 409 accounting.RequestStale, recorded FAILED and nothing kept under its key, the rejection too; a value that is no reopen's id names no request; the same key naming another reopen is a conflict; naming the one pending, it is approved")
    void anActNamesTheReopenItRead() {
        closed("k-m1");
        as(ACCOUNTANT, () -> desk.proposeReopen("k-m2", period(), List.of(), "a correction"));
        String first = reopen();
        as(ACCOUNTANT, () -> desk.withdrawReopen("k-m3", period(), first, "proposed in error"));
        as(new Actor("accountant-2", ActorType.CUSTOMER), () -> desk.proposeReopen("k-m4", period(), List.of(), "another correction"));
        String second = reopen();
        assertThat(second).isNotEqualTo(first);
        int published = events.size();

        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.approveReopen("k-m5", period(), first, List.of(), "the one I read"))).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.REQUEST_STALE);
            assertThat(refused.errorCode().status()).isEqualTo(409);
            assertThat(refused.clientDetail().orElseThrow()).contains("is not the one pending").doesNotContain("the one I read");
        });
        AuditRecord kept = audited.get(audited.size() - 1);
        assertThat(kept.operation().code()).isEqualTo("accounting.RequestStale");
        assertThat(kept.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(kept.changeSummary().orElseThrow()).isEqualTo("period=" + MARCH + ", act=period:reopen-approval");
        assertThat(claims.keySet()).noneMatch(key -> key.key().equals("k-m5"));
        assertThat(periods.find(UOW, march).orElseThrow().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(events).as("nothing published").hasSize(published);
        refused(() -> as(CONTROLLER, () -> desk.rejectReopen("k-m6", period(), first, "the one I read")), AccountingErrorCode.REQUEST_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-m7", period(), "not-a-reopen", List.of(), "the one I read")), AccountingErrorCode.REQUEST_STALE, 409);
        assertThat(reopen()).as("the second still pending").isEqualTo(second);

        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-m8", period(), second, List.of(), "the one I read"));
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-m8", period(), second, List.of(), "the one I read"))).as("the key's replay").isEqualTo(reopened);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.approveReopen("k-m8", period(), first, List.of(), "the one I read"))).as("the fingerprint covers the reopen named")
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("P14-TSK-024's fix round (M-1): the controller's read of a period's pending requests - an unlock by when it was proposed, a reopen by its id and when it was proposed, what each act names; null where none is pending; no person, no reason; an unknown period 404")
    void theControllerReadsWhatItWillName() {
        propose("k-q1");
        PeriodCloseDesk.PeriodRequestsView proposed = as(CONTROLLER, () -> desk.requests(period()));
        assertThat(proposed.status()).isEqualTo("CLOSE_PROPOSED");
        assertThat(proposed.unlockPending()).isFalse();
        assertThat(proposed.unlockProposedAt()).isNull();
        assertThat(proposed.reopenPending()).isFalse();
        assertThat(proposed.reopenId()).isNull();
        assertThat(proposed.reopenProposedAt()).isNull();

        as(CONTROLLER, () -> desk.approve("k-q2", period(), closes.latest(UOW, march).orElseThrow().id().value().toString(), REASON));
        as(ACCOUNTANT, () -> desk.proposeReopen("k-q3", period(), List.of(), "a correction"));
        PeriodCloseDesk.PeriodRequestsView reopening = as(CONTROLLER, () -> desk.requests(period()));
        assertThat(reopening.status()).isEqualTo("CLOSED");
        assertThat(reopening.reopenPending()).isTrue();
        assertThat(reopening.reopenId()).isEqualTo(closes.reopens().get(0).id().value().toString());
        assertThat(reopening.reopenProposedAt()).isEqualTo(closes.reopens().get(0).proposedAt().toString());
        assertThat(reopening.toString()).doesNotContain(ACCOUNTANT.id()).doesNotContain("a correction");
        refused(() -> as(CONTROLLER, () -> desk.requests(UUID.randomUUID().toString())), AccountingErrorCode.NOT_FOUND, 404);

        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(april, MARCH.plusMonths(1), CorrelationId.generate(IDS)));
        periods.markLocked(UOW, april, 1, ACCOUNTANT, Optional.empty());
        periods.proposeUnlock(UOW, april, 1, ACCOUNTANT, "a missed accrual");
        PeriodCloseDesk.PeriodRequestsView unlocking = as(CONTROLLER, () -> desk.requests(april.value().toString()));
        assertThat(unlocking.unlockPending()).isTrue();
        assertThat(unlocking.unlockProposedAt()).isEqualTo(periods.find(UOW, april).orElseThrow().unlockProposedAt().orElseThrow().toString());
        assertThat(unlocking.reopenPending()).isFalse();
    }

    // ------------------------------------------------------------------ plumbing

    /** The reopen a person read before acting (`P14-TSK-024`): the one pending, else the latest - what a repeat names - else an id no reopen has. */
    private String reopen() {
        return machine.pendingOf(UOW, march).reopen().map(row -> row.id().value().toString())
                .orElseGet(() -> closes.reopens().isEmpty() ? NONE_READ : closes.reopens().get(closes.reopens().size() - 1).id().value().toString());
    }

    private static void refused(Runnable act, AccountingErrorCode code, int status) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.errorCode().status()).isEqualTo(status);
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

    /** The reference: one valued pair, observed the hour before the cutoff. */
    private static final class OneRate implements ValuationRates {

        private static final CurrencyCode USD = CurrencyCode.of("USD");

        @Override
        public List<CurrencyCode> valued() {
            return List.of(USD);
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            return List.of(new Observed(USD, Optional.of(new Observation(UUID.randomUUID(), "simulated-reference", CurrencyCode.of("EUR"), USD, new BigDecimal("1.1734000000"),
                    before.minusSeconds(3_600), before.minusSeconds(3_598)))));
        }
    }

    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint, CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState, StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response, held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
