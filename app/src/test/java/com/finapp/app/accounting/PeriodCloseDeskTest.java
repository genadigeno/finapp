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
    private final PeriodCloses machine = new PeriodCloses(periods, closes, runs, breaks, setStore, com.finapp.accounting.testing.MonthsThatRan.EVERY_MONTH, cutoff, (uow, period, actor, correlation) -> 2, (uow, record) -> audited.add(record),
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
    private final PeriodCloseDesk desk = new PeriodCloseDesk(machine, new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions, AccountingFlowScope.NONE);
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

        PeriodCloseDesk.PeriodCloseReceipt proposed = as(ACCOUNTANT, () -> desk.proposeReopen("k-p", period(), "a correction"));
        assertThat(proposed.status()).isEqualTo("CLOSED");
        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("k-p", period(), "a correction"))).isEqualTo(proposed);
        refused(() -> as(ACCOUNTANT, () -> desk.approveReopen("k-s", period(), "mine")), AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited.get(audited.size() - 1).outcome()).isEqualTo(AuditOutcome.FAILED);

        cutoffBusy = true;
        refused(() -> as(CONTROLLER, () -> desk.approveReopen("k-o", period(), "agreed")), AccountingErrorCode.CUTOFF_BUSY, 503);
        cutoffBusy = false;

        PeriodCloseDesk.PeriodCloseReceipt reopened = as(CONTROLLER, () -> desk.approveReopen("k-o2", period(), "agreed"));
        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN.name());
        assertThat(reopened.revision()).isEqualTo(2);
        assertThat(as(CONTROLLER, () -> desk.approveReopen("k-o2", period(), "agreed"))).isEqualTo(reopened);
        refused(() -> as(ACCOUNTANT, () -> desk.proposeReopen("k-q", period(), "again")), AccountingErrorCode.PERIOD_STALE, 409);
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
    @DisplayName("the independent review, M-3: no body and no view prints a reason or evidence, whichever a log line were handed")
    void aBodysTextHoldsNoReason() {
        String secret = "a-persons-reason";
        for (Object record : List.of(new PeriodCloseController.CloseProposalRequest("run", secret, List.of(new PeriodCloseController.CloseDisclosureItem("CHECK", 1, secret))),
                new PeriodCloseController.CloseDisclosureItem("CHECK", 1, secret), new PeriodCloseController.CloseDecisionRequest("proposal", secret), new PeriodCloseController.PeriodReopenRequest(secret),
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
