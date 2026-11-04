package com.finapp.app.accounting;

import static com.finapp.accounting.testing.RegulatoryWorld.APPROVER;
import static com.finapp.accounting.testing.RegulatoryWorld.PREPARER;
import static com.finapp.accounting.testing.RegulatoryWorld.REASON;
import static com.finapp.accounting.testing.RegulatoryWorld.SOURCE;
import static com.finapp.accounting.testing.RegulatoryWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.app.api.ClosedBody;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.RequiresIdempotencyKey;
import com.finapp.platform.audit.AuditOutcome;
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
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The regulatory obligation's doors (`P14-TSK-018`; ADR-0118 decision 1; {@code INV-AUD-04}): configuration as data under four eyes: every door's happy path, its refusal on the contract, its permission and assurance declared; a row's only way in, nothing seeded. {@code RegulatorySubmissionDatabaseTest} proves the
 * interceptor's refusal of a lesser holder (owed, C5).
 */
@DisplayName("the regulatory obligation's doors (P14-TSK-018)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RegulatoryObligationDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2096-03-10T09:00:00Z"), ZoneOffset.UTC);

    private final RegulatoryWorld world = new RegulatoryWorld(CLOCK);
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            Map<IdempotencyKey, IdempotencyRecord> held = new HashMap<>(claims);
            int records = world.audit.size();
            try {
                return work.apply(UOW);
            } catch (RuntimeException failed) {
                claims.clear();
                claims.putAll(held);
                world.audit.subList(records, world.audit.size()).clear();
                throw failed;
            }
        }
    };
    private final RegulatoryObligationDesk desk = new RegulatoryObligationDesk(world.obligations, new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), transactions, AccountingFlowScope.NONE);

    private <R> R as(Actor actor, Supplier<R> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor); CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(world.ids)))) {
            return work.get();
        }
    }

    private static RegulatoryObligationDesk.Stated stated(int version, String reason) {
        return new RegulatoryObligationDesk.Stated(ScriptedRegulatoryProvider.JURISDICTION, RegulatoryWorld.FORM, version, ScriptedRegulatoryProvider.NAME,
                List.of(new RegulatoryObligationDesk.RegulatoryObligationSourceItem(SOURCE, 1)),
                List.of(new RegulatoryObligationDesk.RegulatoryObligationLineItem("ASSETS", "Total assets", SOURCE, "body.equation[currency=*].assets")),
                List.of(new RegulatoryObligationDesk.RegulatoryObligationRuleItem("POSITIVE", "NON_NEGATIVE", List.of("ASSETS"), List.of())), reason);
    }

    private RegulatoryObligationDesk.RegulatoryObligationReceipt proposed(String key) {
        return as(PREPARER, () -> desk.propose(key, stated(1, REASON)));
    }

    private static void refused(Runnable door, AccountingErrorCode code) {
        assertThatThrownBy(door::run).isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.errorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("the routes: a preparer proposes and reads; a controller decides and reads their own door, the activation MULTI_FACTOR; every POST keyed; no route asks ACCOUNTING_READ; the bodies closed")
    void theRoutes() {
        Map<String, PermissionName> expected = Map.of(
                "proposeRegulatoryObligation", PermissionName.REGULATORY_REPORT_PREPARE,
                "listRegulatoryObligations", PermissionName.REGULATORY_REPORT_PREPARE,
                "readRegulatoryObligation", PermissionName.REGULATORY_REPORT_PREPARE,
                "listRegulatoryObligationsAwaitingActivation", PermissionName.REGULATORY_REPORT_APPROVE,
                "readRegulatoryObligationAttestation", PermissionName.REGULATORY_REPORT_APPROVE,
                "activateRegulatoryObligation", PermissionName.REGULATORY_REPORT_APPROVE,
                "rejectRegulatoryObligation", PermissionName.REGULATORY_REPORT_APPROVE);
        int seen = 0;
        for (Method method : RegulatoryObligationController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(RequiresPermission.class)) {
                seen++;
                assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName()).isEqualTo(expected.get(method.getName()));
                assertThat(method.isAnnotationPresent(RequiresIdempotencyKey.class)).as(method.getName()).isEqualTo(!(method.getName().startsWith("read") || method.getName().startsWith("list")));
                assertThat(method.isAnnotationPresent(RequiresAssurance.class)).as(method.getName()).isEqualTo(method.getName().equals("activateRegulatoryObligation"));
                if (method.isAnnotationPresent(RequiresAssurance.class)) {
                    assertThat(method.getAnnotation(RequiresAssurance.class).value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
                }
            }
        }
        assertThat(seen).isEqualTo(expected.size());
        for (Class<?> body : List.of(RegulatoryObligationController.RegulatoryObligationRequest.class, RegulatoryObligationController.RegulatoryObligationSourceRequest.class, RegulatoryObligationController.RegulatoryObligationLineRequest.class,
                RegulatoryObligationController.RegulatoryObligationRuleRequest.class, RegulatoryObligationController.RegulatoryObligationActivationRequest.class, RegulatoryObligationController.RegulatoryObligationRejectionRequest.class)) {
            assertThat(body.isAnnotationPresent(ClosedBody.class)).as(body.getSimpleName()).isTrue();
        }
    }

    @Test
    @DisplayName("a list's null is refused at the boundary, never an NPE in a lambda; a toString carries no reason")
    void aNullInAListIsRefusedAtTheBoundary() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<RegulatoryObligationController.RegulatoryObligationRequest>> violations = validator.validate(new RegulatoryObligationController.RegulatoryObligationRequest("REF", "F", 1, "a",
                    Arrays.asList((RegulatoryObligationController.RegulatoryObligationSourceRequest) null), List.of(), List.of(), "r"));
            assertThat(violations).isNotEmpty();
            assertThat(validator.validate(new RegulatoryObligationController.RegulatoryObligationRequest("REF", "F", 1, "a", List.of(), Arrays.asList((RegulatoryObligationController.RegulatoryObligationLineRequest) null), List.of(), "r"))).isNotEmpty();
            assertThat(validator.validate(new RegulatoryObligationController.RegulatoryObligationRequest("REF", "F", 1, "a", List.of(), List.of(), Arrays.asList((RegulatoryObligationController.RegulatoryObligationRuleRequest) null), "r"))).isNotEmpty();
            assertThat(validator.validate(new RegulatoryObligationController.RegulatoryObligationRuleRequest("R", "EQUALS", Arrays.asList((String) null), List.of()))).isNotEmpty();
        }
        assertThat(new RegulatoryObligationController.RegulatoryObligationRequest("REF", "F", 1, "a", List.of(), List.of(), List.of(), REASON).toString()).doesNotContain(REASON);
        assertThat(new RegulatoryObligationController.RegulatoryObligationActivationRequest("h", REASON).toString()).doesNotContain(REASON);
        assertThat(new RegulatoryObligationController.RegulatoryObligationRejectionRequest(REASON).toString()).doesNotContain(REASON);
        assertThat(stated(1, REASON).toString()).doesNotContain(REASON);
    }

    @Test
    @DisplayName("the proposal door: REFERENCE, hashed; the same key replays, another body conflicts; incoherent and unserved configurations refused on the contract")
    void theProposalDoor() {
        RegulatoryObligationDesk.RegulatoryObligationReceipt first = proposed("p-1");
        assertThat(first.status()).isEqualTo("PROPOSED");
        assertThat(first.standing()).isEqualTo("REFERENCE");
        assertThat(proposed("p-1").obligationId()).isEqualTo(first.obligationId());
        assertThat(world.obligationStore.all()).hasSize(1);
        assertThatThrownBy(() -> as(PREPARER, () -> desk.propose("p-1", stated(1, "another reason")))).isInstanceOf(IdempotencyConflictException.class);

        refused(() -> proposed("p-2"), AccountingErrorCode.REGULATORY_OBLIGATION_STALE);
        refused(() -> as(PREPARER, () -> desk.propose("p-3", stated(2, " "))), AccountingErrorCode.REASON_REQUIRED);
        refused(() -> as(PREPARER, () -> desk.propose("p-4", new RegulatoryObligationDesk.Stated("REF", RegulatoryWorld.FORM, 2, ScriptedRegulatoryProvider.NAME, null, null, null, REASON))), AccountingErrorCode.REGULATORY_OBLIGATION_INVALID);
        refused(() -> as(PREPARER, () -> desk.propose("p-5", new RegulatoryObligationDesk.Stated("REF", RegulatoryWorld.FORM, 2, ScriptedRegulatoryProvider.NAME, stated(2, REASON).sources(), stated(2, REASON).lines(),
                List.of(new RegulatoryObligationDesk.RegulatoryObligationRuleItem("R", "DROP TABLE", List.of("ASSETS"), List.of())), REASON))), AccountingErrorCode.REGULATORY_OBLIGATION_INVALID);
        refused(() -> as(PREPARER, () -> desk.propose("p-6", new RegulatoryObligationDesk.Stated("DE-BAFIN", RegulatoryWorld.FORM, 2, ScriptedRegulatoryProvider.NAME, stated(2, REASON).sources(), stated(2, REASON).lines(), stated(2, REASON).rules(), REASON))),
                AccountingErrorCode.REGULATORY_OBLIGATION_INVALID);
    }

    @Test
    @DisplayName("the activation door: a second person naming the hash; the proposer refused 403, recorded FAILED in its own transaction; a differing hash Stale; an unknown id not found")
    void theActivationDoor() {
        RegulatoryObligationDesk.RegulatoryObligationReceipt proposed = proposed("p-1");

        refused(() -> as(PREPARER, () -> desk.activate("a-1", proposed.obligationId(), proposed.configurationSha256(), REASON)), AccountingErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(world.audit.get(world.audit.size() - 1).outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(world.audit.get(world.audit.size() - 1).operation().code()).isEqualTo("accounting.SelfApprovalRefused");
        refused(() -> as(APPROVER, () -> desk.activate("a-2", proposed.obligationId(), "0".repeat(64), REASON)), AccountingErrorCode.REGULATORY_OBLIGATION_STALE);
        refused(() -> as(APPROVER, () -> desk.activate("a-3", "not-a-uuid", proposed.configurationSha256(), REASON)), AccountingErrorCode.NOT_FOUND);
        refused(() -> as(APPROVER, () -> desk.activate("a-4", RegulatoryObligationId.next(world.ids).value().toString(), proposed.configurationSha256(), REASON)), AccountingErrorCode.NOT_FOUND);

        assertThat(as(APPROVER, () -> desk.activate("a-5", proposed.obligationId(), proposed.configurationSha256(), REASON)).status()).isEqualTo("ACTIVE");
        assertThat(world.audit.stream().map(record -> record.operation().code())).contains("regulatory.ObligationActivated");
    }

    @Test
    @DisplayName("the rejection door: final; a reason required; what is decided refuses")
    void theRejectionDoor() {
        RegulatoryObligationDesk.RegulatoryObligationReceipt proposed = proposed("p-1");
        refused(() -> as(APPROVER, () -> desk.reject("r-0", proposed.obligationId(), " ")), AccountingErrorCode.REASON_REQUIRED);
        assertThat(as(APPROVER, () -> desk.reject("r-1", proposed.obligationId(), REASON)).status()).isEqualTo("REJECTED");
        refused(() -> as(APPROVER, () -> desk.activate("a-1", proposed.obligationId(), proposed.configurationSha256(), REASON)), AccountingErrorCode.REGULATORY_OBLIGATION_STALE);
    }

    @Test
    @DisplayName("the reads: a preparer's and a controller's their own doors, the proposer's reason to the controller alone; a pending list never their own; each recorded before it is served")
    void theReads() {
        RegulatoryObligationDesk.RegulatoryObligationReceipt proposed = proposed("p-1");
        world.audit.clear();

        RegulatoryObligationDesk.RegulatoryObligationView preparer = as(PREPARER, () -> desk.read(proposed.obligationId()));
        assertThat(preparer.proposalReason()).isNull();
        assertThat(preparer.standing()).isEqualTo("REFERENCE");
        assertThat(preparer.notice()).contains("reference");
        assertThat(preparer.adapterHeld()).isTrue();
        assertThat(preparer.lines()).extracting(RegulatoryObligationDesk.RegulatoryObligationLineItem::code).containsExactly("ASSETS");
        assertThat(world.audit.get(0).operation().code()).isEqualTo("regulatory.ObligationRead");

        RegulatoryObligationDesk.RegulatoryObligationView controller = as(APPROVER, () -> desk.attestation(proposed.obligationId()));
        assertThat(controller.proposalReason()).isEqualTo(REASON);
        assertThat(controller.toString()).doesNotContain(REASON);
        assertThat(world.audit.get(1).changeSummary().orElseThrow()).doesNotContain(REASON);

        assertThat(as(APPROVER, desk::awaitingActivation)).extracting(RegulatoryObligationDesk.RegulatoryObligationSummaryView::obligationId).containsExactly(proposed.obligationId());
        assertThat(as(PREPARER, desk::awaitingActivation)).isEmpty();
        assertThat(as(PREPARER, desk::recent)).hasSize(1);
        refused(() -> as(PREPARER, () -> desk.read("not-a-uuid")), AccountingErrorCode.NOT_FOUND);
        refused(() -> as(PREPARER, () -> desk.read(RegulatoryObligationId.next(world.ids).value().toString())), AccountingErrorCode.NOT_FOUND);
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
