package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.PostingCutoff;
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
    private static final String DISCLOSURE = "the secret disclosure, two suspense items";

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore runStore = new InMemoryCloseRunStore(periods);
    private final InMemoryAccountingBreakStore breaks = new InMemoryAccountingBreakStore();
    private final InMemoryValuationRateSetStore setStore = new InMemoryValuationRateSetStore().watching(periods);
    private final InMemoryPeriodCloseStore closes = new InMemoryPeriodCloseStore(periods);
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
    private final PeriodCloseDesk desk = new PeriodCloseDesk(machine, priorPeriodAdjustments, new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions, AccountingFlowScope.NONE);
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
        closes.attemptEvidenceIs(CloseCheckCode.RECONCILIATION_DISCLOSURE, "{\"suspenseUnowned\":\"1\"}");

        PeriodCloseDesk.CloseProposalView view = as(CONTROLLER, () -> desk.proposal(period()));
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.periodStatus()).isEqualTo("CLOSE_PROPOSED");
        assertThat(view.stillStands()).isTrue();
        assertThat(view.closeRunId()).isEqualTo(run.value().toString());
        assertThat(view.checks()).hasSize(CloseCheckCode.values().length);
        assertThat(view.checks()).filteredOn(check -> check.check().equals("RECONCILIATION_DISCLOSURE")).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo("DISCLOSED");
            assertThat(check.disclosureReason()).isEqualTo(DISCLOSURE);
            assertThat(check.evidence()).isEqualTo("{\"suspenseUnowned\":\"1\"}");
        });
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
        refused(() -> as(ACCOUNTANT, () -> desk.approveReopen("k-s", period(), List.of(), "mine")), AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited.get(audited.size() - 1).outcome()).isEqualTo(AuditOutcome.FAILED);

        cutoffBusy = true;
        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-o", period(), List.of(), "agreed")), AccountingErrorCode.CUTOFF_BUSY, 503);
        cutoffBusy = false;

        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-o2", period(), List.of(), "agreed"));
        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN.name());
        assertThat(reopened.revision()).isEqualTo(2);
        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-o2", period(), List.of(), "agreed"))).isEqualTo(reopened);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-q", period(), List.of(), "again")), AccountingErrorCode.PERIOD_STALE, 409);
    }

    @Test
    @DisplayName("the routes are the partition: every act keyed; the accountant proposes, the controller decides and reads what it decides")
    void theRoutesAreThePartition() throws Exception {
        Map<String, PermissionName> expected = Map.of("proposeAccountingPeriodClose", PermissionName.ACCOUNTING_CLOSE_PROPOSE, "readAccountingPeriodCloseProposal", PermissionName.ACCOUNTING_CLOSE_APPROVE,
                "approveAccountingPeriodClose", PermissionName.ACCOUNTING_CLOSE_APPROVE, "rejectAccountingPeriodClose", PermissionName.ACCOUNTING_CLOSE_APPROVE, "proposeAccountingPeriodReopen",
                PermissionName.ACCOUNTING_CLOSE_PROPOSE, "approveAccountingPeriodReopen", PermissionName.ACCOUNTING_CLOSE_APPROVE);
        int seen = 0;
        for (Method method : PeriodCloseController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                seen++;
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

        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-t4", period(), named, "agreed")).priorPeriodAdjustments()).as("the approver is told").containsExactly(adjustment.value().toString());
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

        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-n6", period(), List.of(), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        assertThat(periods.find(UOW, march).orElseThrow().status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-n6", period(), List.of(adjustment), "agreed")).status()).isEqualTo("OPEN");
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

        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-r3", period(), List.of(first), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-r3", period(), read.priorPeriodAdjustments(), "agreed"));
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.priorPeriodAdjustments()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("P14-TSK-022: a stored reopen receipt stays readable: a row from before P14-TSK-013 has six fields and replays with an empty list; and a body naming none fingerprints exactly as it did, so a retry across the deploy replays and is no conflict")
    void aStoredReceiptStaysReadable() {
        as(ACCOUNTANT, () -> {
            String key = "k-old";
            String fingerprinted = PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, java.util.Set.of(), "from before");
            assertThat(fingerprinted).isEqualTo("reopen-proposal|" + march.value() + "|from before");
            IdempotencyKey stored = new IdempotencyKey(AccountingPeriodDesk.SCOPE + ACCOUNTANT.type().name() + ":" + ACCOUNTANT.id(), key);
            String sixFields = march.value() + "|" + MARCH + "|CLOSED|1||";
            claims.put(stored, new IdempotencyRecord(stored, RequestFingerprint.sha256(fingerprinted.getBytes(java.nio.charset.StandardCharsets.UTF_8)), IdempotencyState.COMPLETED,
                    StoredResponse.of(sixFields.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain"), CorrelationId.generate(IDS), CLOCK.instant(), CLOCK.instant().plus(Duration.ofDays(1))));

            PeriodCloseDesk.PeriodCloseReceipt replayed = desk.proposeReopen(key, period(), null, "from before");

            assertThat(replayed.status()).isEqualTo("CLOSED");
            assertThat(replayed.priorPeriodAdjustments()).isEmpty();
            return null;
        });
        UUID some = UUID.randomUUID();
        assertThat(PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, java.util.Set.of(some), "r")).as("another form, whatever a reason holds")
                .isNotEqualTo(PeriodCloseDesk.reopenFingerprint("reopen-proposal", march, java.util.Set.of(), "[" + some + "]|r"));
    }

    @Test
    @DisplayName("P14-TSK-022: a reopen's body: a null among the names is the platform's validation failure at the door, never a NullPointerException and a 500; its absence names none; the list is bounded")
    void aReopensBodyIsBounded() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new PeriodCloseController.PeriodReopenRequest(REASON, java.util.Arrays.asList((String) null))))
                    .singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).startsWith("postedAdjustments[0]"));
            assertThat(validator.validate(new PeriodCloseController.PeriodReopenRequest(REASON, null))).isEmpty();
            assertThat(validator.validate(new PeriodCloseController.PeriodReopenRequest(REASON, java.util.Collections.nCopies(201, UUID.randomUUID().toString())))).isNotEmpty();
        }
    }

    @Test
    @DisplayName("the independent review, M-3: no body and no view prints a reason or evidence, whichever a log line were handed")
    void aBodysTextHoldsNoReason() {
        String secret = "a-persons-reason";
        for (Object record : List.of(new PeriodCloseController.CloseProposalRequest("run", secret, List.of(new PeriodCloseController.CloseDisclosureItem("CHECK", 1, secret))),
                new PeriodCloseController.CloseDisclosureItem("CHECK", 1, secret), new PeriodCloseController.CloseDecisionRequest("proposal", secret), new PeriodCloseController.PeriodReopenRequest(secret, List.of(UUID.randomUUID().toString())),
                new PeriodCloseDesk.StatedDisclosure("CHECK", 1, secret), new PeriodCloseDesk.CloseProposalCheckView("CHECK", 1, "DISCLOSED", "judge:v1", secret, secret))) {
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

    // ------------------------------------------------------------------ plumbing

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
