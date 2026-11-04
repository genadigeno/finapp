package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore;
import com.finapp.accounting.testing.InMemoryPriorPeriodPostings;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.app.api.ClosedBody;
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
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
 * The prior-period adjustment's doors (`P14-TSK-013`; {@code PHASE_14_PLAN.md} sections 10, 12; {@code INV-ACC-10}, {@code INV-REV-04}, {@code INV-AUD-04}): every act keyed and reasoned; the same key replaying the first answer, another body refused; the refusals on the contract; a refused self-approval recorded
 * {@code FAILED} in its own transaction; the reads audited before they are served; decimal strings exact or refused, never naming a value. The routes' permissions are held by {@code RoutePermissionRegisterTest}; the permission on each is declared here.
 */
@DisplayName("the prior-period adjustment's doors (P14-TSK-013)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PriorPeriodAdjustmentDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("the fakes never touch the connection");
    });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final YearMonth JANUARY = YearMonth.of(2026, 1);
    private static final YearMonth FEBRUARY = YearMonth.of(2026, 2);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static final String TODAY = "2026-03-10";
    private static final String REASON = "the secret reason: a fee booked twice";

    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryPriorPeriodAdjustmentStore store = new InMemoryPriorPeriodAdjustmentStore(periods);
    private final InMemoryPriorPeriodPostings ledger = new InMemoryPriorPeriodPostings();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final PriorPeriodAdjustments machine = new PriorPeriodAdjustments(periods, store, ledger, mappings, (uow, record) -> audited.add(record),
            (uow, envelope, payload, mediaType) -> events.add(envelope.eventType()), IDS, CLOCK);
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = audited.size();
            Runnable rollback = store.savepoint();
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
    private final PriorPeriodAdjustmentDesk desk = new PriorPeriodAdjustmentDesk(machine, new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions, AccountingFlowScope.NONE);
    private AccountingPeriodId january;
    private String wallet;
    private String fees;

    @BeforeEach
    void januaryClosed() {
        for (YearMonth month : List.of(JANUARY, FEBRUARY, MARCH)) {
            periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(AccountingPeriodId.next(IDS), month, CorrelationId.generate(IDS)));
        }
        periods.closeForTest(JANUARY);
        january = periods.month(JANUARY).orElseThrow().id();
        GlMappingVersionId version = GlMappingVersionId.next(IDS);
        mappings.insertProposal(null, new GlMappingStore.NewProposal(version, 1, ReferenceGlMapping.version(JANUARY), ACCOUNTANT, "the reference", CorrelationId.generate(IDS), UUID.randomUUID()));
        assertThat(mappings.decide(null, version, GlMappingVersionStatus.ACTIVE, CONTROLLER, "approved")).isTrue();
        wallet = ledger.account("CUSTOMER_WALLET", "CUSTOMER").toString();
        fees = ledger.account("FEE_REVENUE", "OPERATIONAL").toString();
    }

    private List<PriorPeriodAdjustmentDesk.StatedLine> lines() {
        return List.of(new PriorPeriodAdjustmentDesk.StatedLine(fees, "DEBIT", "15.00", "EUR"), new PriorPeriodAdjustmentDesk.StatedLine(wallet, "CREDIT", "15.00", "EUR"));
    }

    private PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt propose(String key) {
        return as(ACCOUNTANT, () -> desk.propose(key, january.value().toString(), 1, REASON, lines()));
    }

    private PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt approve(String key, String id) {
        return as(CONTROLLER, () -> desk.approve(key, id, TODAY, 1, List.of(), "read and agreed"));
    }

    @Test
    @DisplayName("the routes are the partition: a proposal the accountant's, each decision and the approver's reads the controller's, a month's list ACCOUNTING_READ; every POST keyed")
    void theRoutesAreThePartition() {
        Map<String, PermissionName> expected = Map.of("proposePriorPeriodAdjustment", PermissionName.ACCOUNTING_ADJUST_PROPOSE,
                "listPendingPriorPeriodAdjustments", PermissionName.ACCOUNTING_ADJUST_APPROVE,
                "readPriorPeriodAdjustment", PermissionName.ACCOUNTING_ADJUST_APPROVE,
                "approvePriorPeriodAdjustment", PermissionName.ACCOUNTING_ADJUST_APPROVE,
                "rejectPriorPeriodAdjustment", PermissionName.ACCOUNTING_ADJUST_APPROVE,
                "readPriorPeriodAdjustmentsOfPeriod", PermissionName.ACCOUNTING_READ);
        int seen = 0;
        for (Method method : PriorPeriodAdjustmentController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                seen++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName()).isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName())
                        .isEqualTo(!(method.getName().startsWith("read") || method.getName().startsWith("list")));
            }
        }
        assertThat(seen).isEqualTo(expected.size());
    }

    @Test
    @DisplayName("the proposal has no date to state: its body is closed and holds no such component - the server dates it, and the receipt says what")
    void theProposalCannotNameADate() {
        assertThat(PriorPeriodAdjustmentController.PriorPeriodAdjustmentRequest.class.isAnnotationPresent(ClosedBody.class)).isTrue();
        assertThat(Arrays.stream(PriorPeriodAdjustmentController.PriorPeriodAdjustmentRequest.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("correctedPeriodId", "correctedRevision", "reason", "lines");

        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt receipt = propose("k-1");

        assertThat(receipt.status()).isEqualTo("PROPOSED");
        assertThat(receipt.postingDate()).isEqualTo(TODAY);
        assertThat(receipt.correctedPeriod()).isEqualTo("2026-01");
        assertThat(receipt.targetPeriod()).isEqualTo("2026-03");
        assertThat(receipt.earlierCorrections()).isEmpty();
    }

    @Test
    @DisplayName("keyed acts: a timeout then a retry replays the first answer and writes nothing; the same key with another reason or other lines is a conflict; a new key is a new proposal, which the approver is shown")
    void aProposalIsKeyed() {
        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt first = propose("k-1");
        int records = audited.size();

        assertThat(propose("k-1")).as("the same key, the same body").isEqualTo(first);
        assertThat(store.rows()).hasSize(1);
        assertThat(ledger.proposals()).hasSize(1);
        assertThat(audited).hasSize(records);

        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.propose("k-1", january.value().toString(), 1, "another reason", lines()))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.propose("k-1", january.value().toString(), 1, REASON,
                List.of(new PriorPeriodAdjustmentDesk.StatedLine(fees, "DEBIT", "16.00", "EUR"), new PriorPeriodAdjustmentDesk.StatedLine(wallet, "CREDIT", "16.00", "EUR"))))).isInstanceOf(IdempotencyConflictException.class);

        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt second = propose("k-2");
        assertThat(second.earlierCorrections()).singleElement().satisfies(correction -> {
            assertThat(correction.id()).isEqualTo(first.id());
            assertThat(correction.status()).isEqualTo("PROPOSED");
        });
        assertThat(propose("k-2")).as("the stored receipt holds what the act found").isEqualTo(second);
    }

    @Test
    @DisplayName("an approval: the response lost after the commit - the same key replays it; a new key from the same person converges, writing nothing; another body under the key is a conflict; ten approvers' losers find it POSTED, stale")
    void anApprovalIsKeyed() {
        String id = propose("k-1").id();
        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt posted = approve("a-1", id);
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.journalEntryId()).isNotNull();
        assertThat(events).containsExactly(PriorPeriodAdjustments.POSTED_EVENT);
        int records = audited.size();

        assertThat(approve("a-1", id)).isEqualTo(posted);
        assertThat(approve("a-2", id)).as("converged by the row").isEqualTo(posted);
        assertThat(events).hasSize(1);
        assertThat(audited).hasSize(records);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.approve("a-1", id, TODAY, 1, List.of(), "another reason"))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.approve("a-1", id, "2026-03-11", 1, List.of(), "read and agreed"))).isInstanceOf(IdempotencyConflictException.class);
        refused(() -> as(new Actor("controller-2", ActorType.CUSTOMER), () -> desk.approve("a-3", id, TODAY, 1, List.of(), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
    }

    @Test
    @DisplayName("a refused self-approval is 403 and recorded FAILED in its own transaction, and the key is not consumed; a rejection by the proposer is a withdrawal")
    void theProposerCannotApprove() {
        String id = propose("k-1").id();

        refused(() -> as(ACCOUNTANT, () -> desk.approve("a-1", id, TODAY, 1, List.of(), "agreed")), AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);

        assertThat(audited).last().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(id);
        });
        assertThat(ledger.calls()).doesNotContain("approve");
        assertThat(approve("a-1", id).status()).as("the key was not consumed; a second person goes through under it").isEqualTo("POSTED");

        String other = propose("k-2").id();
        assertThat(as(ACCOUNTANT, () -> desk.reject("r-1", other, "withdrawn")).status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("the refusals on the contract: the month or target in the wrong state 409, the revision or what was read 409, a purpose closed 422 with its door named, a purpose unmapped 422; a malformed id is not found")
    void theRefusals() {
        String id = propose("k-1").id();
        refused(() -> as(CONTROLLER, () -> desk.approve("a-1", id, "2026-03-11", 1, List.of(), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.approve("a-2", id, TODAY, 2, List.of(), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.approve("a-3", id, TODAY, 1, List.of(UUID.randomUUID().toString()), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.approve("a-4", id, "not-a-date", 1, List.of(), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.approve("a-5", id, TODAY, 1, List.of("not-an-id"), "agreed")), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(CONTROLLER, () -> desk.approve("a-6", id, TODAY, 1, null, " ")), AccountingErrorCode.REASON_REQUIRED, 422);
        refused(() -> as(CONTROLLER, () -> desk.approve("a-7", "not-an-id", TODAY, 1, null, "agreed")), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(CONTROLLER, () -> desk.reject("r-1", UUID.randomUUID().toString(), "no")), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-3", "not-an-id", 1, REASON, lines())), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-4", january.value().toString(), 9, REASON, lines())), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_STALE, 409);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-5", periods.month(FEBRUARY).orElseThrow().id().value().toString(), 1, REASON, lines())), AccountingErrorCode.PERIOD_STALE, 409);

        String loan = ledger.account("LOAN_PRINCIPAL", "LOAN").toString();
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.propose("k-6", january.value().toString(), 1, REASON,
                List.of(new PriorPeriodAdjustmentDesk.StatedLine(loan, "DEBIT", "15.00", "EUR"), new PriorPeriodAdjustmentDesk.StatedLine(wallet, "CREDIT", "15.00", "EUR")))))
                .isInstanceOfSatisfying(ApiException.class, closed -> {
                    assertThat(closed.errorCode()).isEqualTo(AccountingErrorCode.PRIOR_PERIOD_PURPOSE_CLOSED);
                    assertThat(closed.errorCode().status()).isEqualTo(422);
                    assertThat(closed.clientDetail().orElseThrow()).contains("lending");
                });
    }

    @Test
    @DisplayName("the boundary's decimal strings are exact or refused, each refusal naming the line and the field and never the value; more precision than the currency has is refused, never rounded; unbalanced is refused before the key is spent")
    void theLinesAreParsedExactly() {
        record Bad(String account, String direction, String amount, String currency, String names) {}
        for (Bad bad : List.of(new Bad("not-an-account", "DEBIT", "15.00", "EUR", "ledger account"), new Bad(fees, "EITHER", "15.00", "EUR", "direction"), new Bad(fees, "DEBIT", "15.00", "XX", "currency"),
                new Bad(fees, "DEBIT", "fifteen", "EUR", "amount"), new Bad(fees, "DEBIT", "15.001", "EUR", "amount"), new Bad(fees, "DEBIT", null, "EUR", "amount"))) {
            assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.propose("k-p", january.value().toString(), 1, REASON,
                    List.of(new PriorPeriodAdjustmentDesk.StatedLine(bad.account(), bad.direction(), bad.amount(), bad.currency()), new PriorPeriodAdjustmentDesk.StatedLine(wallet, "CREDIT", "15.00", "EUR")))))
                    .isInstanceOfSatisfying(ApiException.class, refused -> {
                        assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_INVALID);
                        assertThat(refused.clientDetail().orElseThrow()).contains("line 0").contains(bad.names()).doesNotContain("15.001").doesNotContain("fifteen").doesNotContain("not-an-account");
                    });
        }
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-u", january.value().toString(), 1, REASON,
                List.of(new PriorPeriodAdjustmentDesk.StatedLine(fees, "DEBIT", "15.00", "EUR"), new PriorPeriodAdjustmentDesk.StatedLine(wallet, "CREDIT", "14.00", "EUR")))), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_INVALID, 422);
        refused(() -> as(ACCOUNTANT, () -> desk.propose("k-n", january.value().toString(), 1, REASON, null)), AccountingErrorCode.PRIOR_PERIOD_ADJUSTMENT_INVALID, 422);
        assertThat(store.rows()).isEmpty();
        assertThat(claims).as("a refused request spends no key").isEmpty();
    }

    @Test
    @DisplayName("the approver's read: audited before it is served; the date, revision, lines as exact decimals, the reason, the month's other corrections, and whether it still stands - no person; the pending and the month's list audited too")
    void theReads() {
        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt first = propose("k-1");
        approve("a-1", first.id());
        String second = propose("k-2").id();
        int records = audited.size();

        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentView view = as(CONTROLLER, () -> desk.read(second));

        assertThat(view.postingDate()).isEqualTo(TODAY);
        assertThat(view.correctedRevision()).isEqualTo(1);
        assertThat(view.stillStands()).isTrue();
        assertThat(view.blockedBy()).isNull();
        assertThat(view.reason()).isEqualTo(REASON);
        assertThat(view.lines()).extracting(PriorPeriodAdjustmentDesk.PriorPeriodLineView::amount).containsOnly("15.00");
        assertThat(view.earlierCorrections()).singleElement().satisfies(correction -> assertThat(correction.id()).isEqualTo(first.id()));
        assertThat(audited).hasSize(records + 1);
        assertThat(audited).last().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.PriorPeriodAdjustmentRead");
            assertThat(record.changeSummary().orElseThrow()).doesNotContain("secret").doesNotContain("15.00");
        });
        assertThat(Arrays.stream(PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentView.class.getRecordComponents()).map(RecordComponent::getName)).as("no person leaves").doesNotContain("proposedBy", "decidedBy");

        assertThat(as(CONTROLLER, desk::pending)).singleElement().satisfies(pending -> assertThat(pending.id()).isEqualTo(second));
        assertThat(as(ACCOUNTANT, () -> desk.ofPeriod(january.value().toString()))).hasSize(2);
        assertThat(audited).hasSize(records + 3);
        refused(() -> as(CONTROLLER, () -> desk.read("not-an-id")), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(CONTROLLER, () -> desk.read(UUID.randomUUID().toString())), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(ACCOUNTANT, () -> desk.ofPeriod(UUID.randomUUID().toString())), AccountingErrorCode.NOT_FOUND, 404);
    }

    @Test
    @DisplayName("a stored receipt round-trips: ids, months, dates and codes, an earlier correction with and without its entry")
    void aStoredReceiptRoundTrips() {
        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt receipt = new PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt("id", "2026-01", 2, "2026-03", TODAY, "POSTED", "proposal", "entry",
                List.of(new PriorPeriodAdjustmentDesk.PriorPeriodCorrectionView("a", 1, "POSTED", TODAY, "e"), new PriorPeriodAdjustmentDesk.PriorPeriodCorrectionView("b", 2, "PROPOSED", LocalDate.parse(TODAY).toString(), null)));
        assertThat(PriorPeriodAdjustmentDesk.parsedReceipt(PriorPeriodAdjustmentDesk.stored(receipt))).isEqualTo(receipt);
        PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt bare = new PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentReceipt("id", "2026-01", 1, "2026-03", TODAY, "PROPOSED", "proposal", null, List.of());
        assertThat(PriorPeriodAdjustmentDesk.parsedReceipt(PriorPeriodAdjustmentDesk.stored(bare))).isEqualTo(bare);
    }

    @Test
    @DisplayName("no body, line or view prints a reason or an amount, whichever a log line were handed")
    void aBodysTextHoldsNoReason() {
        String secret = "a-persons-reason";
        String amount = "987.65";
        for (Object record : List.of(new PriorPeriodAdjustmentController.PriorPeriodAdjustmentRequest("period", 1, secret, List.of(new PriorPeriodAdjustmentController.PriorPeriodLineItem("a", "DEBIT", amount, "EUR"))),
                new PriorPeriodAdjustmentController.PriorPeriodLineItem("a", "DEBIT", amount, "EUR"), new PriorPeriodAdjustmentController.PriorPeriodApprovalRequest(TODAY, 1, List.of(), secret),
                new PriorPeriodAdjustmentController.PriorPeriodRejectionRequest(secret), new PriorPeriodAdjustmentDesk.StatedLine("a", "DEBIT", amount, "EUR"), new PriorPeriodAdjustmentDesk.PriorPeriodLineView("a", "DEBIT", amount, "EUR"),
                new PriorPeriodAdjustmentDesk.PriorPeriodAdjustmentView("id", "2026-01", 1, "2026-03", TODAY, "PROPOSED", "p", null, "t", null, secret, true, null, List.of(new PriorPeriodAdjustmentDesk.PriorPeriodLineView("a", "DEBIT", amount, "EUR")), List.of()))) {
            assertThat(record.toString()).as(record.getClass().getSimpleName()).doesNotContain(secret).doesNotContain(amount);
        }
    }

    @Test
    @DisplayName("a null among the lines or the named corrections is the platform's validation failure at the door, never a NullPointerException and a 500; each line's bounds cascade")
    void aNullLineIsAValidationFailure() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new PriorPeriodAdjustmentController.PriorPeriodAdjustmentRequest("period", 1, REASON, Arrays.asList((PriorPeriodAdjustmentController.PriorPeriodLineItem) null))))
                    .singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).startsWith("lines[0]"));
            assertThat(validator.validate(new PriorPeriodAdjustmentController.PriorPeriodApprovalRequest(TODAY, 1, Arrays.asList((String) null), REASON)))
                    .singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).startsWith("postedCorrections[0]"));
            assertThat(validator.validate(new PriorPeriodAdjustmentController.PriorPeriodAdjustmentRequest("period", 1, REASON, List.of(new PriorPeriodAdjustmentController.PriorPeriodLineItem("a", "DEBIT", "1".repeat(41), "EUR")))))
                    .singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("lines[0].amount"));
            assertThat(validator.validate(new PriorPeriodAdjustmentController.PriorPeriodApprovalRequest(TODAY, 1, null, REASON))).as("nothing posted yet names nothing").isEmpty();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void refused(Runnable act, AccountingErrorCode code, int status) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.errorCode().status()).isEqualTo(status);
            assertThat(refused.clientDetail().orElse("")).doesNotContain("secret");
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
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
