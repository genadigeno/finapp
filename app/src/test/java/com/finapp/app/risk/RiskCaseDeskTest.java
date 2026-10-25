package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
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
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertRaiser;
import com.finapp.risk.CaseObserver;
import com.finapp.risk.Cases;
import com.finapp.risk.CustomerRiskProfileVersion;
import com.finapp.risk.ProfileCause;
import com.finapp.risk.ProfileInputs;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskPartyIdentities;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskRating;
import com.finapp.risk.SignalReadings;
import com.finapp.risk.VelocityWindows;
import com.finapp.risk.testing.InMemoryCustomerRiskProfileStore;
import com.finapp.risk.testing.InMemoryRiskAlertStore;
import com.finapp.risk.testing.InMemoryRiskCaseStore;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The case doors, hermetic (`P13-TSK-016`; {@code PHASE_13_PLAN.md} sections 9, 11; ADR-0110 decisions 5-7): the acts
 * {@code RISK_INVESTIGATE}, the decisions {@code RISK_CASE_APPROVE}, the approval {@code MULTI_FACTOR}, every act keyed;
 * a replayed key answering the case as it stands; a stale revision {@code 409 risk.CaseRevisionStale}; the proposer's
 * approval {@code 403 risk.SelfApprovalRefused} and the analyst's own party {@code 403 risk.SelfDealingRefused}, each
 * recorded {@code FAILED} in a transaction of its own; a malformed request {@code 422 risk.CaseRequestInvalid}; an
 * unknown or malformed id the uniform {@code 404 risk.NotFound}; and <strong>the lock order</strong>: an approved
 * prohibition is applied to the profile after the approval's transaction, never inside it (R8 never under R4). Over
 * HTTP the doors are {@code RiskCaseApiTest}'s (written, owed - C5).
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the case doors: RISK_INVESTIGATE / RISK_CASE_APPROVE, keyed acts, the revision, the prohibition after"
        + " the commit (P13-TSK-016)")
class RiskCaseDeskTest {

    private final InMemoryRiskCaseStore caseStore = new InMemoryRiskCaseStore();
    private final InMemoryRiskAlertStore alertStore = new InMemoryRiskAlertStore();
    private final InMemoryCustomerRiskProfileStore profileStore = new InMemoryCustomerRiskProfileStore();
    private final RiskEvaluationWorld world = new RiskEvaluationWorld();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<String, UUID> partyOf = new HashMap<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new LinkedHashMap<>();
    /** Every outermost transaction in which a case row was locked and a profile head was locked too. */
    private final List<String> r8UnderR4 = new ArrayList<>();
    private int depth;

    private final RiskTransactions transactions = new RiskTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            if (depth > 0) {
                // A nested call joins the transaction it runs in (REQUIRED propagation, as Spring's).
                return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
            }
            Map<IdempotencyKey, IdempotencyRecord> keys = new LinkedHashMap<>(claims);
            int audited = audit.size();
            int caseLog = caseStore.log.size();
            int profileLog = profileStore.log.size();
            depth++;
            try {
                return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
            } catch (RuntimeException rolledBack) {
                claims.clear();
                claims.putAll(keys);
                audit.subList(audited, audit.size()).clear();
                throw rolledBack;
            } finally {
                depth--;
                boolean caseLocked = caseStore.log.subList(caseLog, caseStore.log.size()).stream()
                        .anyMatch(entry -> entry.equals("lock case") || entry.startsWith("lockOpen"));
                boolean headLocked = profileStore.log.subList(profileLog, profileStore.log.size()).stream()
                        .anyMatch(entry -> entry.startsWith("lock "));
                if (caseLocked && headLocked) {
                    r8UnderR4.add("a transaction took the profile head (R8) beside a case row (R4)");
                }
            }
        }

        @Override
        public <R> R inSnapshot(Function<Connection, R> work) {
            return inTransaction(work);
        }
    };

    private final OutboxWriter<Connection> outbox =
            (unitOfWork, envelope, payload, mediaType) -> events.add(envelope.eventType());
    private final Cases cases = new Cases(caseStore, alertStore,
            (unitOfWork, actor) -> Optional.ofNullable(partyOf.get(actor.id())), (unitOfWork, subject) ->
                    Optional.empty(), (unitOfWork, record) -> audit.add(record), outbox, RiskEvaluationWorld.IDS,
            RiskEvaluationWorld.CLOCK, CaseObserver.NONE);
    private final ProfileRecompute recompute = new ProfileRecompute(new RiskPolicies(world.policyStore), profileStore,
            new ProfileInputs((unitOfWork, party) -> Optional.empty(), new SignalReadings(world.signalStore),
                    RiskPartyIdentities.NONE, new VelocityWindows(world.windowStore)),
            AlertRaiser.NONE, outbox, RiskEvaluationWorld.IDS, RiskEvaluationWorld.CLOCK, Duration.ofDays(1));
    private final CaseProhibitionDriver prohibitions = new CaseProhibitionDriver(cases, recompute, transactions,
            RiskEvaluationWorld.IDS, 5);
    private final RiskCaseDesk desk = new RiskCaseDesk(cases, prohibitions,
            new IdempotentExecutor(new Claims(), RiskEvaluationWorld.CLOCK, Duration.ofDays(1), Duration.ofMinutes(1)),
            transactions, RiskFlowScope.NONE);

    @Test
    @DisplayName("the acts carry RISK_INVESTIGATE, the decisions RISK_CASE_APPROVE, the approval MULTI_FACTOR; every act"
            + " is keyed")
    void everyRouteCarriesItsPermission() throws NoSuchMethodException {
        Map<Method, PermissionName> routes = Map.of(
                RiskCaseController.class.getMethod("openRiskCase", String.class,
                        RiskCaseController.RiskCaseOpenRequest.class), PermissionName.RISK_INVESTIGATE,
                RiskCaseController.class.getMethod("actOnRiskCase", String.class, String.class,
                        RiskCaseController.RiskCaseActionRequest.class), PermissionName.RISK_INVESTIGATE,
                RiskCaseController.class.getMethod("proposeRiskCaseDisposition", String.class, String.class,
                        RiskCaseController.RiskCaseDispositionRequest.class), PermissionName.RISK_INVESTIGATE,
                RiskCaseController.class.getMethod("approveRiskCaseDisposition", String.class, String.class,
                        String.class, RiskCaseController.RiskCaseDecisionRequest.class), PermissionName.RISK_CASE_APPROVE,
                RiskCaseController.class.getMethod("rejectRiskCaseDisposition", String.class, String.class,
                        String.class, RiskCaseController.RiskCaseDecisionRequest.class), PermissionName.RISK_CASE_APPROVE);
        routes.forEach((method, permission) -> {
            assertThat(method.getAnnotation(RequiresPermission.class).value()).as(method.getName())
                    .isEqualTo(permission);
            assertThat(method.getAnnotation(RequiresIdempotencyKey.class)).as(method.getName()).isNotNull();
            boolean approval = method.getName().equals("approveRiskCaseDisposition");
            assertThat(method.getAnnotation(RequiresAssurance.class) != null).as(method.getName()).isEqualTo(approval);
        });
        assertThat(RiskCaseController.class.getMethod("approveRiskCaseDisposition", String.class, String.class,
                String.class, RiskCaseController.RiskCaseDecisionRequest.class).getAnnotation(RequiresAssurance.class)
                .value()).isEqualTo(AssuranceLevel.MULTI_FACTOR);
    }

    @Test
    @DisplayName("an opening is keyed: its replay answers the case as it stands, opened as the first answer said; a second"
            + " opening of the subject and kind answers the standing case, opened false")
    void anOpeningIsKeyedAndReplays() {
        Actor alice = analyst();
        UUID party = RiskEvaluationWorld.IDS.next();
        RiskCaseDesk.RiskCaseView first = as(alice, () -> desk.open("k-open", openOn(party)));
        assertThat(first.opened()).isTrue();
        assertThat(first.status()).isEqualTo("OPEN");
        assertThat(first.revision()).isZero();
        assertThat(first.subjectRef()).isEqualTo(party.toString());
        RiskCaseDesk.RiskCaseView replay = as(alice, () -> desk.open("k-open", openOn(party)));
        assertThat(replay.caseId()).isEqualTo(first.caseId());
        assertThat(replay.opened()).isTrue();
        RiskCaseDesk.RiskCaseView again = as(alice, () -> desk.open("k-other", openOn(party)));
        assertThat(again.caseId()).isEqualTo(first.caseId());
        assertThat(again.opened()).isFalse();
        assertThat(audited("risk.CaseOpened")).hasSize(1);
    }

    @Test
    @DisplayName("the acts carry the revision: a take answers revision 1; the same revision again is 409"
            + " risk.CaseRevisionStale; a disposed case is 409 risk.CaseNotOpen")
    void actsCarryTheRevision() {
        Actor alice = analyst();
        RiskCaseDesk.RiskCaseView opened = as(alice, () -> desk.open("k-1", openOn(RiskEvaluationWorld.IDS.next())));
        RiskCaseDesk.RiskCaseView taken = as(alice, () -> desk.act("k-2", opened.caseId(), act("TAKE", 0, null)));
        assertThat(taken.status()).isEqualTo("IN_INVESTIGATION");
        assertThat(taken.revision()).isEqualTo(1);
        assertThat(taken.taken()).isTrue();
        assertThat(refusal(analyst(), () -> desk.act("k-3", opened.caseId(), act("NOTE", 0, "a stale note")))
                .errorCode()).isEqualTo(RiskErrorCode.CASE_REVISION_STALE);
        RiskCaseDesk.RiskCaseView closed = as(alice, () -> desk.propose("k-4", opened.caseId(),
                new RiskCaseController.RiskCaseDispositionRequest(1, "CLOSED_NO_ACTION", null, "nothing here")));
        assertThat(closed.status()).isEqualTo("DISPOSED");
        assertThat(closed.disposition().status()).isEqualTo("DECIDED");
        assertThat(closed.disposition().requiresSecondPerson()).isFalse();
        assertThat(refusal(alice, () -> desk.act("k-5", opened.caseId(), act("NOTE", 2, "too late"))).errorCode())
                .isEqualTo(RiskErrorCode.CASE_NOT_OPEN);
    }

    @Test
    @DisplayName("INV-RSK-07: the proposer's approval is 403 risk.SelfApprovalRefused, recorded FAILED in a transaction"
            + " of its own; a second holder's approval disposes - and its PROHIBITED is written after the approval's"
            + " transaction, never inside it (R8 never under R4), once under the key's replay")
    void theProhibitionIsAppliedAfterTheApprovalCommits() {
        Actor alice = analyst();
        Actor bob = analyst();
        UUID party = RiskEvaluationWorld.IDS.next();
        RiskCaseDesk.RiskCaseView opened = as(alice, () -> desk.open("p-1", openOn(party)));
        RiskCaseDesk.RiskCaseView taken = as(alice, () -> desk.act("p-2", opened.caseId(), act("TAKE", 0, null)));
        RiskCaseDesk.RiskCaseView proposed = as(alice, () -> desk.propose("p-3", taken.caseId(),
                new RiskCaseController.RiskCaseDispositionRequest(taken.revision(), "CONFIRMED_FRAUD", true,
                        "a mule account")));
        assertThat(proposed.disposition().status()).isEqualTo("PROPOSED");
        assertThat(proposed.disposition().prohibits()).isTrue();
        String dispositionId = proposed.disposition().dispositionId();

        ApiException self = refusal(alice, () -> desk.approve("p-4", taken.caseId(), dispositionId,
                new RiskCaseController.RiskCaseDecisionRequest(proposed.revision(), "agreed")));
        assertThat(self.errorCode()).isEqualTo(RiskErrorCode.SELF_APPROVAL_REFUSED);
        assertThat(audited("risk.SelfApprovalRefused")).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED));
        assertThat(profileStore.head(party)).isEmpty();

        RiskCaseDesk.RiskCaseView approved = as(bob, () -> desk.approve("p-5", taken.caseId(), dispositionId,
                new RiskCaseController.RiskCaseDecisionRequest(proposed.revision(), "confirmed")));
        assertThat(approved.status()).isEqualTo("DISPOSED");
        assertThat(profileStore.versions(RiskEvaluationWorld.UNTOUCHABLE, party)).singleElement().satisfies(version -> {
            assertThat(version.rating()).isEqualTo(RiskRating.PROHIBITED);
            assertThat(version.cause()).isEqualTo(ProfileCause.DISPOSITION);
            assertThat(version.dispositionId()).contains(UUID.fromString(dispositionId));
        });
        assertThat(r8UnderR4).as("the profile head is never taken beside the case row").isEmpty();

        as(bob, () -> desk.approve("p-5", taken.caseId(), dispositionId,
                new RiskCaseController.RiskCaseDecisionRequest(proposed.revision(), "confirmed")));
        assertThat(profileStore.versions(RiskEvaluationWorld.UNTOUCHABLE, party))
                .extracting(CustomerRiskProfileVersion::version).containsExactly(1);
        assertThat(audited("risk.DispositionApproved")).hasSize(1);
        assertThat(events).contains(Cases.DISPOSED_EVENT, ProfileRecompute.EVENT_TYPE);
    }

    @Test
    @DisplayName("self-dealing is 403 risk.SelfDealingRefused, recorded FAILED; an analyst placed in no party is refused"
            + " the same way")
    void selfDealingIsRefusedAndRecorded() {
        UUID party = RiskEvaluationWorld.IDS.next();
        RiskCaseDesk.RiskCaseView opened = as(analyst(), () -> desk.open("s-1", openOn(party)));
        Actor own = new Actor("analyst-" + UUID.randomUUID(), ActorType.CUSTOMER);
        partyOf.put(own.id(), party);
        assertThat(refusal(own, () -> desk.act("s-2", opened.caseId(), act("NOTE", 0, "my own"))).errorCode())
                .isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        Actor nobody = new Actor("analyst-nobody", ActorType.CUSTOMER);
        assertThat(refusal(nobody, () -> desk.open("s-3", openOn(RiskEvaluationWorld.IDS.next()))).errorCode())
                .isEqualTo(RiskErrorCode.SELF_DEALING_REFUSED);
        assertThat(audited("risk.SelfDealingRefused")).hasSize(2).allSatisfy(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.changeSummary().orElseThrow()).doesNotContain(party.toString());
        });
    }

    @Test
    @DisplayName("an unknown or malformed case or disposition id is the uniform 404 risk.NotFound; a malformed request"
            + " is 422 risk.CaseRequestInvalid; a blank reason 422 risk.ReasonRequired")
    void theUniformRefusals() {
        Actor alice = analyst();
        assertThat(refusal(alice, () -> desk.act("u-1", RiskEvaluationWorld.IDS.next().toString(),
                act("TAKE", 0, null))).errorCode()).isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(refusal(alice, () -> desk.act("u-2", "not-a-case", act("TAKE", 0, null))).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        RiskCaseDesk.RiskCaseView opened = as(alice, () -> desk.open("u-3", openOn(RiskEvaluationWorld.IDS.next())));
        assertThat(refusal(alice, () -> desk.approve("u-4", opened.caseId(), "not-a-disposition",
                new RiskCaseController.RiskCaseDecisionRequest(0, "agreed"))).errorCode())
                .isEqualTo(RiskErrorCode.NOT_FOUND);
        assertThat(refusal(alice, () -> desk.open("u-5", new RiskCaseController.RiskCaseOpenRequest(
                RiskEvaluationWorld.IDS.next().toString(), "FRAUD", null, null, "both"))).errorCode())
                .isEqualTo(RiskErrorCode.CASE_REQUEST_INVALID);
        assertThat(refusal(alice, () -> desk.act("u-6", opened.caseId(), new RiskCaseController.RiskCaseActionRequest(
                "ATTACH_EVIDENCE", 0, "why", null, List.of("not-a-uuid"), null, null))).errorCode())
                .isEqualTo(RiskErrorCode.CASE_REQUEST_INVALID);
        assertThat(refusal(alice, () -> desk.act("u-7", opened.caseId(), act("NOTE", 0, " "))).errorCode())
                .isEqualTo(RiskErrorCode.REASON_REQUIRED);
        assertThat(refusal(alice, () -> desk.act("u-8", opened.caseId(), act("ESCALATE", 0, "beyond me")))
                .errorCode()).isEqualTo(RiskErrorCode.CASE_REQUEST_INVALID);
    }

    // -----------------------------------------------------------------

    private static RiskCaseController.RiskCaseOpenRequest openOn(UUID party) {
        return new RiskCaseController.RiskCaseOpenRequest(null, "FRAUD", "PARTY", party.toString(), "worth a look");
    }

    private static RiskCaseController.RiskCaseActionRequest act(String act, int revision, String text) {
        return new RiskCaseController.RiskCaseActionRequest(act, revision, text, null, null, null, null);
    }

    /** An analyst as the session interceptor establishes one: the identity, CUSTOMER-typed (the permission the door's). */
    private Actor analyst() {
        Actor actor = new Actor("analyst-" + UUID.randomUUID(), ActorType.CUSTOMER);
        partyOf.put(actor.id(), RiskEvaluationWorld.IDS.next());
        return actor;
    }

    private List<AuditRecord> audited(String code) {
        return audit.stream().filter(record -> record.operation().code().equals(code)).toList();
    }

    private static ApiException refusal(Actor actor, Supplier<?> work) {
        ApiException refused = catchThrowableOfType(ApiException.class, () -> as(actor, work));
        assertThat(refused).as("refused on the error contract").isNotNull();
        return refused;
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
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                    StoredResponse.empty(), correlationId, now, expiresAt));
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
