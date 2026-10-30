package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappings;
import com.finapp.accounting.LedgerChart;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GL mapping doors' boundary (`P14-TSK-003`; {@code PHASE_14_PLAN.md} sections 10, 12): the proposal parsed into the
 * domain's words, every act keyed per principal and replayed from its stored receipt, the domain's refusals on the error
 * contract - the self-approval {@code 403} recorded {@code FAILED} in a transaction of its own - and the reads, a version
 * whole and against the version in force with its judgement. The routes' permissions are the partition of identity
 * {@code V025} (propose the accountant's, decide and diff the controller's, read the accountant's), pinned by
 * {@code RoutePermissionRegisterTest}. Hermetic: the store, the claims and the chart are in memory.
 */
@DisplayName("the GL mapping doors (P14-TSK-003)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class GlMappingDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(GlMappingDeskTest.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);

    private final InMemoryGlMappingStore store = new InMemoryGlMappingStore();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private LedgerChart.Chart chart = ReferenceGlMapping.agreeingChart(purpose -> true);
    private int transactions;
    private int rolledBack;
    private final AccountingTransactions unitOfWork = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            transactions++;
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = audited.size();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                claims.clear();
                claims.putAll(held);
                audited.subList(records, audited.size()).clear();
                rolledBack++;
                throw failed;
            }
        }
    };
    private final GlMappingDesk desk = new GlMappingDesk(
            new GlMappingAdministration(store, uow -> chart, (uow, record) -> audited.add(record),
                    (uow, envelope, payload, mediaType) -> events.add(envelope.eventType()), IDS, CLOCK),
            new GlMappings(store, uow -> chart),
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), unitOfWork,
            AccountingFlowScope.NONE);

    @Test
    @DisplayName("an accountant proposes (201 PROPOSED), a controller activates (ACTIVE): each keyed per principal, a"
            + " replayed key answers the stored receipt and writes nothing")
    void proposeThenActivateKeyed() {
        GlMappingDesk.GlMappingReceipt proposed = as(ACCOUNTANT, () -> desk.propose("key-1", request("2026-11")));
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(proposed.version()).isEqualTo(1);
        assertThat(proposed.effectiveFromPeriod()).isEqualTo("2026-11");
        assertThat(as(ACCOUNTANT, () -> desk.propose("key-1", request("2026-11")))).isEqualTo(proposed);
        assertThat(store.maxVersion(UOW)).as("the replay proposed nothing").isEqualTo(1);

        GlMappingDesk.GlMappingReceipt activated =
                as(CONTROLLER, () -> desk.activate("key-1", proposed.id(), "reviewed against the chart"));
        assertThat(activated.status()).isEqualTo("ACTIVE");
        assertThat(activated.retiredVersion()).isNull();
        assertThat(as(CONTROLLER, () -> desk.activate("key-1", proposed.id(), "reviewed against the chart")))
                .isEqualTo(activated);
        assertThat(events).containsExactly(GlMappingAdministration.ACTIVATED_EVENT);
        assertThat(claims.keySet()).extracting(IdempotencyKey::scope)
                .containsExactlyInAnyOrder("accounting.mapping:CUSTOMER:accountant-1",
                        "accounting.mapping:CUSTOMER:controller-1");
    }

    @Test
    @DisplayName("the proposer's own activation is 403 accounting.SelfApprovalRefused, recorded FAILED in a transaction"
            + " of its own after the act rolled back; the proposal stays PROPOSED")
    void selfApprovalIsRefusedAndRecorded() {
        GlMappingDesk.GlMappingReceipt proposed = as(ACCOUNTANT, () -> desk.propose("key-1", request("2026-11")));
        int before = transactions;
        assertThatThrownBy(() -> as(ACCOUNTANT, () -> desk.activate("key-2", proposed.id(), "my own")))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(AccountingErrorCode.SELF_APPROVAL_REFUSED);
                    assertThat(refused.errorCode().status()).isEqualTo(403);
                });
        assertThat(transactions - before).as("the act's, then the record's").isEqualTo(2);
        assertThat(rolledBack).isEqualTo(1);
        AuditRecord refusal = audited.get(audited.size() - 1);
        assertThat(refusal.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
        assertThat(refusal.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(refusal.targetId()).isEqualTo(proposed.id());
        assertThat(as(ACCOUNTANT, () -> desk.version(proposed.id())).status()).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("the domain's refusals on the contract: not total and incompatible 422, stale and retroactive 409, a"
            + " missing reason 422, a malformed month or an unknown section 422 before anything is keyed, an unknown or"
            + " malformed id 404")
    void refusalsOnTheContract() {
        GlMappingDesk.GlMappingReceipt partial = as(ACCOUNTANT, () -> desk.propose("p-1", request("2026-11",
                ReferenceGlMapping.without(YearMonth.of(2026, 11), Set.of("CASH_AT_BANK")))));
        refused(() -> as(CONTROLLER, () -> desk.activate("a-1", partial.id(), "why")),
                AccountingErrorCode.MAPPING_NOT_TOTAL, 422);
        GlMappingDesk.GlMappingReceipt wrong = as(ACCOUNTANT, () -> desk.propose("p-2", request("2026-11",
                ReferenceGlMapping.remapped(YearMonth.of(2026, 11), "FEE_REVENUE", new GlAccount("5999",
                        "Fees shown as an expense", com.finapp.accounting.GlClassification.EXPENSE,
                        com.finapp.accounting.StatementSection.OTHER_EXPENSES)))));
        refused(() -> as(CONTROLLER, () -> desk.activate("a-2", wrong.id(), "why")),
                AccountingErrorCode.MAPPING_INCOMPATIBLE, 422);
        refused(() -> as(CONTROLLER, () -> desk.activate("a-3", wrong.id(), " ")), AccountingErrorCode.REASON_REQUIRED, 422);

        GlMappingDesk.GlMappingReceipt later = as(ACCOUNTANT, () -> desk.propose("p-3", request("2027-01")));
        as(CONTROLLER, () -> desk.activate("a-4", later.id(), "in force from January"));
        refused(() -> as(new Actor("controller-2", ActorType.CUSTOMER), () -> desk.activate("a-5", later.id(),
                "a second controller, too late")), AccountingErrorCode.MAPPING_STALE, 409);
        GlMappingDesk.GlMappingReceipt backDated = as(ACCOUNTANT, () -> desk.propose("p-4", request("2026-12")));
        refused(() -> as(CONTROLLER, () -> desk.activate("a-6", backDated.id(), "why")),
                AccountingErrorCode.MAPPING_RETROACTIVE, 409);

        refused(() -> as(ACCOUNTANT, () -> desk.propose("p-5", request("2026-13"))), AccountingErrorCode.MAPPING_INVALID, 422);
        GlMappingController.GlMappingProposalRequest unknownSection = new GlMappingController.GlMappingProposalRequest(
                "2027-02", List.of(new GlMappingController.GlMappingAccountRequest("1000", "Cash", "ASSET", "NO_SUCH")),
                List.of(new GlMappingController.GlMappingRuleRequest("CASH_AT_BANK", "OPERATIONAL", "1000")), "why");
        refused(() -> as(ACCOUNTANT, () -> desk.propose("p-6", unknownSection)), AccountingErrorCode.MAPPING_INVALID, 422);
        refused(() -> as(CONTROLLER, () -> desk.activate("a-7", "not-a-uuid", "why")), AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(CONTROLLER, () -> desk.reject("a-8", IDS.next().toString(), "why")),
                AccountingErrorCode.NOT_FOUND, 404);
    }

    @Test
    @DisplayName("the reads: a version whole (no person, no reason), and against the version in force with its"
            + " judgement and the counterparties as the dimension")
    void theReads() {
        GlMappingDesk.GlMappingReceipt first = as(ACCOUNTANT, () -> desk.propose("p-1", request("2026-11")));
        as(CONTROLLER, () -> desk.activate("a-1", first.id(), "first"));
        GlMappingDesk.GlMappingReceipt second = as(ACCOUNTANT, () -> desk.propose("p-2", request("2026-12",
                ReferenceGlMapping.without(YearMonth.of(2026, 12), Set.of("FX_POSITION")))));

        GlMappingDesk.GlMappingVersionView view = as(ACCOUNTANT, () -> desk.version(first.id()));
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.accounts()).hasSize(35);
        assertThat(view.rules()).hasSize(40);
        assertThat(view.accounts().get(0).normalBalance()).isEqualTo("DEBIT");

        GlMappingDesk.GlMappingDiffView diff = as(CONTROLLER, () -> desk.diff(second.id(), null));
        assertThat(diff.againstId()).isEqualTo(first.id());
        assertThat(diff.againstVersion()).isEqualTo(1);
        assertThat(diff.rulesRemoved()).extracting(GlMappingDesk.GlMappingRuleView::purpose).containsExactly("FX_POSITION");
        assertThat(diff.unmapped()).containsExactly("FX_POSITION/OPERATIONAL");
        assertThat(diff.activatable()).isFalse();
        assertThat(diff.counterparties()).extracting(GlMappingDesk.GlMappingCounterpartyView::code).containsExactly("fx-sim-a");
        GlMappingDesk.GlMappingDiffView againstNothing = as(CONTROLLER, () -> desk.diff(first.id(), second.id()));
        assertThat(againstNothing.rulesAdded()).extracting(GlMappingDesk.GlMappingRuleView::purpose)
                .containsExactly("FX_POSITION");
        assertThat(againstNothing.activatable()).isTrue();
    }

    @Test
    @DisplayName("the routes declare the partition: propose ACCOUNTING_MAPPING_PROPOSE, activate, reject and diff"
            + " ACCOUNTING_MAPPING_APPROVE, read ACCOUNTING_READ; every POST keyed")
    void theRoutesDeclareThePartition() throws NoSuchMethodException {
        Map<String, PermissionName> expected = Map.of(
                "proposeGlMappingVersion", PermissionName.ACCOUNTING_MAPPING_PROPOSE,
                "activateGlMappingVersion", PermissionName.ACCOUNTING_MAPPING_APPROVE,
                "rejectGlMappingVersion", PermissionName.ACCOUNTING_MAPPING_APPROVE,
                "readGlMappingVersion", PermissionName.ACCOUNTING_READ,
                "readGlMappingVersionDiff", PermissionName.ACCOUNTING_MAPPING_APPROVE);
        for (Method method : GlMappingController.class.getDeclaredMethods()) {
            if (expected.containsKey(method.getName())) {
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                        .isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName())
                        .isEqualTo(method.getName().startsWith("propose") || method.getName().startsWith("activate")
                                || method.getName().startsWith("reject"));
            }
        }
    }

    // -----------------------------------------------------------------

    private static GlMappingController.GlMappingProposalRequest request(String month) {
        return request(month, ReferenceGlMapping.version(YearMonth.of(2026, 11)));
    }

    private static GlMappingController.GlMappingProposalRequest request(String month, GlMappingVersion content) {
        return new GlMappingController.GlMappingProposalRequest(month,
                content.accounts().stream().map(a -> new GlMappingController.GlMappingAccountRequest(a.code(), a.name(),
                        a.classification().name(), a.section().name())).toList(),
                content.rules().stream().map((MappingRule r) -> new GlMappingController.GlMappingRuleRequest(r.purpose(),
                        r.ownerKind(), r.glAccountCode())).toList(),
                "the reference chart, for qualified review");
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
