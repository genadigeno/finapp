package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
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
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting break doors (`P14-TSK-010`; ADR-0117 decision 3; {@code INV-ACC-12}, {@code INV-ACC-05},
 * {@code INV-AUD-04}): the routes' permissions as the partition gives them - the list the accountant's, the proposal
 * {@code ACCOUNTING_ADJUST_PROPOSE}, the controller's read, approval and resolution {@code ACCOUNTING_ADJUST_APPROVE},
 * every POST keyed; the proposer's approval 403 and recorded {@code FAILED} in its own transaction; a replayed key the
 * stored receipt; the refusals on the contract; a view carrying the disclosure and never a person or a reason. Hermetic:
 * the store and the journal in memory; the routes' 401/403 over HTTP are the interceptor's, held by
 * {@code RoutePermissionRegisterTest}.
 */
@DisplayName("the accounting break doors (P14-TSK-010)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountingBreakDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2097-04-02T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            AccountingBreakDeskTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);

    private final InMemoryAccountingBreakStore store = new InMemoryAccountingBreakStore();
    private final InMemoryJournal journal = new InMemoryJournal();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final AccountingTransactions unitOfWork = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = audited.size();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                claims.clear();
                claims.putAll(held);
                audited.subList(records, audited.size()).clear();
                throw failed;
            }
        }
    };
    private final AccountingBreaks breaks = new AccountingBreaks(store, journal, (uow, record) -> audited.add(record),
            (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
    private final AccountingBreakDesk desk = new AccountingBreakDesk(breaks,
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), unitOfWork,
            AccountingFlowScope.NONE, (uow, record) -> audited.add(record), IDS, CLOCK);

    @Test
    @DisplayName("acknowledgement is four-eyes over the doors: the proposer's approval is 403 and recorded FAILED in its"
            + " own transaction; a second person's approval acknowledges; a replayed key answers the stored receipt")
    void theProposerCannotAcknowledge() {
        String id = raised();
        AccountingBreakDesk.AccountingBreakReceipt proposed = as(ACCOUNTANT,
                () -> desk.proposeAcknowledgement("propose", id, "a fee line the transfer poster wrote; corrected"));
        assertThat(proposed.status()).isEqualTo("OPEN");
        assertThat(proposed.acknowledgementPending()).isTrue();
        assertThat(as(ACCOUNTANT, () -> desk.proposeAcknowledgement("propose", id,
                "a fee line the transfer poster wrote; corrected"))).isEqualTo(proposed);

        refused(() -> as(ACCOUNTANT, () -> desk.approveAcknowledgement("approve-self", id, "mine")),
                AccountingErrorCode.SELF_APPROVAL_REFUSED, 403);
        assertThat(audited).filteredOn(record -> record.outcome() == AuditOutcome.FAILED).singleElement()
                .satisfies(record -> {
                    assertThat(record.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
                    assertThat(record.targetId()).isEqualTo(id);
                });

        AccountingBreakDesk.AccountingBreakReceipt acknowledged =
                as(CONTROLLER, () -> desk.approveAcknowledgement("approve", id, "disclosed at close"));
        assertThat(acknowledged.status()).isEqualTo("ACKNOWLEDGED");
        AccountingBreakDesk.AccountingBreakView view = as(CONTROLLER, () -> desk.one(id));
        assertThat(view.disclosure()).isEqualTo("a fee line the transfer poster wrote; corrected");
        assertThat(view.judgedBy()).isEqualTo("posting-rules:v2");
        assertThat(view.toString()).doesNotContain("accountant-1").doesNotContain("controller-1")
                .doesNotContain("disclosed at close");
    }

    @Test
    @DisplayName("resolution names an existing correcting entry: an unknown one 422, a malformed id 422, then RESOLVED"
            + " with the entry on the view; a second resolution 409")
    void resolutionIsByReference() {
        String id = raised();
        JournalReads.Entry correcting = journal.post(LocalDate.of(2097, 4, 1), "EUR", 2, 100, "CUSTOMER_WALLET",
                "CUSTOMER", "FEE_REVENUE", "OPERATIONAL");
        refused(() -> as(CONTROLLER, () -> desk.resolve("r-1", id, UUID.randomUUID().toString(), "fixed")),
                AccountingErrorCode.CORRECTING_ENTRY_INVALID, 422);
        // The review's I-2: the refused resolution recorded FAILED in a transaction of its own, nothing changed.
        assertThat(audited).filteredOn(record -> record.outcome() == AuditOutcome.FAILED).singleElement()
                .satisfies(record -> {
                    assertThat(record.operation().code()).isEqualTo("accounting.BreakResolved");
                    assertThat(record.targetId()).isEqualTo(id);
                    assertThat(record.changeSummary()).hasValueSatisfying(summary ->
                            assertThat(summary).contains("refused=UNKNOWN"));
                });
        assertThat(store.history()).hasSize(1);
        assertThatThrownBy(() -> as(CONTROLLER, () -> desk.resolve("r-2", id, "not-an-entry", "fixed")))
                .isInstanceOfSatisfying(ApiException.class, refused ->
                        assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        AccountingBreakDesk.AccountingBreakReceipt resolved = as(CONTROLLER,
                () -> desk.resolve("r-3", id, correcting.id().toString(), "reversed through its own door"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(as(CONTROLLER, () -> desk.one(id)).correctingEntryId()).isEqualTo(correcting.id().toString());
        refused(() -> as(ACCOUNTANT, () -> desk.resolve("r-4", id, correcting.id().toString(), "again")),
                AccountingErrorCode.BREAK_STALE, 409);
    }

    @Test
    @DisplayName("the refusals and the reads: unknown 404, an unreasoned act 422, a malformed period or status 422; the"
            + " list narrowed by status")
    void theRefusalsAndTheReads() {
        refused(() -> as(ACCOUNTANT, () -> desk.proposeAcknowledgement("k", "nope", "x")), AccountingErrorCode.NOT_FOUND,
                404);
        refused(() -> as(CONTROLLER, () -> desk.one(UUID.randomUUID().toString())), AccountingErrorCode.NOT_FOUND, 404);
        String id = raised();
        refused(() -> as(ACCOUNTANT, () -> desk.proposeAcknowledgement("k-r", id, " ")),
                AccountingErrorCode.REASON_REQUIRED, 422);
        refused(() -> as(CONTROLLER, () -> desk.approveAcknowledgement("k-a", id, "none proposed")),
                AccountingErrorCode.BREAK_STALE, 409);
        for (Supplier<?> malformed : List.<Supplier<?>>of(() -> desk.list("2097-3", null, null, null),
                () -> desk.list("2097-03", "CLOSED", null, null), () -> desk.list("2097-03", null, 0, null),
                () -> desk.list("2097-03", null, null, "not-a-cursor"))) {
            assertThatThrownBy(() -> as(ACCOUNTANT, malformed)).isInstanceOfSatisfying(ApiException.class, refused ->
                    assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
        AccountingBreakDesk.AccountingBreakPage page = as(ACCOUNTANT, () -> desk.list("2097-03", null, null, null));
        assertThat(page.next()).isNull();
        assertThat(page.breaks()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(id);
            assertThat(view.type()).isEqualTo("SHAPE_MISMATCH");
            assertThat(view.check()).isEqualTo("RULE_CONFORMANCE");
            assertThat(view.status()).isEqualTo("OPEN");
        });
        assertThat(as(ACCOUNTANT, () -> desk.list("2097-03", "RESOLVED", 10, null)).breaks()).isEmpty();
        assertThat(as(ACCOUNTANT, () -> desk.list("2097-04", null, null, null)).breaks()).isEmpty();
    }

    @Test
    @DisplayName("the list is a keyset page (the review's M-4): a full page names its next cursor and the pages together"
            + " are every break once; every served read - a page or one break - is recorded accounting.BreakRead first,"
            + " an unknown break records nothing (M-5)")
    void theListPagesAndEveryReadIsRecorded() {
        java.util.Set<String> raised = new java.util.TreeSet<>();
        for (int i = 0; i < 5; i++) {
            raised.add(raised());
        }
        java.util.Set<String> served = new java.util.TreeSet<>();
        String after = null;
        int pages = 0;
        do {
            String cursor = after;
            AccountingBreakDesk.AccountingBreakPage page = as(ACCOUNTANT, () -> desk.list("2097-03", null, 2, cursor));
            page.breaks().forEach(view -> assertThat(served.add(view.id())).as("no break served twice").isTrue());
            after = page.next();
            pages++;
        } while (after != null);
        assertThat(served).isEqualTo(raised);
        assertThat(pages).isEqualTo(3);
        List<AuditRecord> reads = audited.stream()
                .filter(record -> record.operation().code().equals("accounting.BreakRead")).toList();
        assertThat(reads).hasSize(3).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetId()).isEqualTo("2097-03");
            assertThat(record.changeSummary()).hasValueSatisfying(summary ->
                    assertThat(summary).contains("door=list").doesNotContain("shape:"));
        });
        assertThat(reads.get(1).changeSummary()).hasValueSatisfying(summary -> assertThat(summary).contains("page=next"));

        String one = raised.iterator().next();
        as(CONTROLLER, () -> desk.one(one));
        assertThat(audited).last().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.BreakRead");
            assertThat(record.targetId()).isEqualTo(one);
            assertThat(record.actor()).isEqualTo(CONTROLLER);
        });
        int recorded = audited.size();
        refused(() -> as(CONTROLLER, () -> desk.one(IDS.next().toString())), AccountingErrorCode.NOT_FOUND, 404);
        assertThat(audited).as("an unknown break records nothing").hasSize(recorded);
    }

    @Test
    @DisplayName("the routes: the list ACCOUNTING_READ, the proposal ACCOUNTING_ADJUST_PROPOSE, the read, approval and"
            + " resolution ACCOUNTING_ADJUST_APPROVE; every POST keyed")
    void theRoutesAreThePartition() {
        Map<String, PermissionName> expected = Map.of(
                "listAccountingBreaks", PermissionName.ACCOUNTING_READ,
                "readAccountingBreak", PermissionName.ACCOUNTING_ADJUST_APPROVE,
                "proposeAccountingBreakAcknowledgement", PermissionName.ACCOUNTING_ADJUST_PROPOSE,
                "approveAccountingBreakAcknowledgement", PermissionName.ACCOUNTING_ADJUST_APPROVE,
                "resolveAccountingBreak", PermissionName.ACCOUNTING_ADJUST_APPROVE);
        int doors = 0;
        for (Method method : AccountingBreakController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                doors++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                        .isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName())
                        .isEqualTo(!method.getName().startsWith("list") && !method.getName().startsWith("read"));
            }
        }
        assertThat(doors).isEqualTo(expected.size());
    }

    // -----------------------------------------------------------------

    /** A shape mismatch raised in March 2097 by the system - its id. */
    private String raised() {
        UUID entry = UUID.randomUUID();
        breaks.raise(UOW, MARCH, new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH,
                "shape:" + entry, "entry=" + entry, "posting-rules:v2"), Actor.SYSTEM, CorrelationId.generate(IDS));
        return store.rows().get(store.rows().size() - 1).id().value().toString();
    }

    private static void refused(Runnable act, AccountingErrorCode code, int status) {
        assertThatThrownBy(act::run).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(code);
            assertThat(refused.errorCode().status()).isEqualTo(status);
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

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
