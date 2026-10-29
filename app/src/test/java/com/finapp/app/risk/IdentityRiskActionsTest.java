package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Authorization;
import com.finapp.identity.Identity;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.SessionRevocation;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SuspensionCause;
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
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.ActionOutcome;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.lang.reflect.Proxy;
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
 * {@link IdentityRiskActions} over identity's real {@link IdentityAdministration} and {@link SessionRevocation} on fake
 * stores (`P13-TSK-017`; ADR-0112 decision 2; C10; {@code INV-RSK-02}, {@code INV-IDEM-01}): the party's identities
 * suspended, reinstated or their sessions revoked <strong>through identity's own commands</strong> - identity's machine
 * moves the row and identity audits its act as the platform with the action's reason code; keyed
 * {@code risk.action:<id>}, a re-drive replays the first answer (a {@code DONE} stays {@code DONE}, nothing moves twice);
 * {@code ALREADY} for a state already held, {@code REFUSED} for a closed identity, a party with none, or a decider who is
 * no identity; identity's not-self rule given the approver, never the subject.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("identity's risk-action doors: through identity's own commands, keyed, audited as the platform"
        + " (P13-TSK-017)")
class IdentityRiskActionsTest {

    private final Map<IdentityId, Identity> rows = new LinkedHashMap<>();
    private final Map<IdentityId, Integer> liveSessions = new HashMap<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new LinkedHashMap<>();
    private final List<String> moves = new ArrayList<>();
    private final Map<IdentityId, SuspensionCause> causes = new HashMap<>();

    private final IdentityStore<Connection> identities = new IdentityStore<>() {
        @Override
        public Optional<Identity> findByLoginIdentifier(Connection unitOfWork, LoginIdentifier loginIdentifier) {
            return Optional.empty();
        }

        @Override
        public Optional<Identity> findById(Connection unitOfWork, IdentityId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<IdentityId> findIdsByParty(Connection unitOfWork, UUID partyId, int limit) {
            return rows.values().stream().filter(row -> row.partyId().equals(partyId)).map(Identity::id).limit(limit)
                    .toList();
        }

        @Override
        public boolean moveStatus(Connection unitOfWork, IdentityId id, IdentityStatus from, Identity to,
                Optional<SuspensionCause> cause) {
            Identity row = rows.get(id);
            if (row == null || row.status() != from) {
                return false;
            }
            boolean suspending = to.status() == IdentityStatus.SUSPENDED;
            if (!suspending && from == IdentityStatus.SUSPENDED && cause.isPresent()
                    && !cause.equals(Optional.ofNullable(causes.get(id)))) {
                return false; // JdbcIdentityStore's "AND suspension_cause = ?" (identity V024)
            }
            rows.put(id, to);
            if (suspending) {
                causes.put(id, cause.orElseThrow());
            } else {
                causes.remove(id);
            }
            moves.add(id + ":" + to.status());
            return true;
        }
    };

    @SuppressWarnings("unchecked")
    private final SessionStore<Connection> sessions = (SessionStore<Connection>) Proxy.newProxyInstance(
            SessionStore.class.getClassLoader(), new Class<?>[] {SessionStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("revokeAllFor")) {
                    IdentityId id = (IdentityId) args[1];
                    Integer live = liveSessions.getOrDefault(id, 0);
                    liveSessions.put(id, 0);
                    return live;
                }
                throw new AssertionError("identity's risk-action doors do not call " + method.getName());
            });

    @SuppressWarnings("unchecked")
    private final RoleAssignmentStore<Connection> roles = (RoleAssignmentStore<Connection>) Proxy.newProxyInstance(
            RoleAssignmentStore.class.getClassLoader(), new Class<?>[] {RoleAssignmentStore.class},
            (proxy, method, args) -> {
                throw new AssertionError("no role is read or assigned");
            });

    private final OutboxWriter<Connection> outbox =
            (unitOfWork, envelope, payload, mediaType) -> events.add(envelope.eventType());
    private final SessionRevocation revocation = new SessionRevocation(sessions, RiskEvaluationWorld.IDS,
            RiskEvaluationWorld.CLOCK, (unitOfWork, record) -> audit.add(record));
    private final IdentityAdministration administration = new IdentityAdministration(RiskEvaluationWorld.IDS,
            RiskEvaluationWorld.CLOCK, identities, new Authorization(roles, RiskEvaluationWorld.IDS,
                    RiskEvaluationWorld.CLOCK, (unitOfWork, record) -> audit.add(record)), revocation,
            (unitOfWork, record) -> audit.add(record), outbox);
    private final IdentityRiskActions doors = new IdentityRiskActions(administration, revocation, identities,
            new IdempotentExecutor(new Claims(), RiskEvaluationWorld.CLOCK, Duration.ofDays(1), Duration.ofMinutes(1)),
            new IdentityRiskActions.Transactions() {
                @Override
                public <T> T inOne(Function<Connection, T> work) {
                    return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
                }
            });

    @Test
    @DisplayName("SUSPEND_IDENTITY suspends every identity of the party through identity's administration - its row"
            + " moved by identity, audited identity.IdentitySuspended as the platform with the action's code; a re-drive"
            + " under the same key replays DONE and moves nothing; another key reads ALREADY")
    void aSuspensionGoesThroughIdentitysCommand() {
        UUID party = UUID.randomUUID();
        Identity first = identity(party, IdentityStatus.ACTIVE);
        Identity second = identity(party, IdentityStatus.ACTIVE);
        liveSessions.put(first.id(), 2);
        String decider = RiskEvaluationWorld.IDS.next().toString();

        assertThat(asThePlatform(() -> doors.suspend(party, decider, "risk.action:a", "RISK_CASE_ATO")))
                .isEqualTo(ActionOutcome.DONE);
        assertThat(rows.get(first.id()).status()).isEqualTo(IdentityStatus.SUSPENDED);
        assertThat(rows.get(second.id()).status()).isEqualTo(IdentityStatus.SUSPENDED);
        assertThat(causes).as("identity records risk as the cause (item 25)").containsEntry(first.id(),
                SuspensionCause.RISK).containsEntry(second.id(), SuspensionCause.RISK);
        assertThat(audit).filteredOn(record -> record.operation().code().equals("identity.IdentitySuspended"))
                .hasSize(2).allSatisfy(record -> {
                    assertThat(record.actor()).as("the platform executes").isEqualTo(Actor.SYSTEM);
                    assertThat(record.reason()).contains("RISK_CASE_ATO");
                });
        assertThat(events).containsOnly("identity.IdentitySuspended");
        assertThat(liveSessions.get(first.id())).as("identity's suspension ends the sessions").isZero();

        int moved = moves.size();
        assertThat(asThePlatform(() -> doors.suspend(party, decider, "risk.action:a", "RISK_CASE_ATO")))
                .as("INV-IDEM-01: the key's answer replayed").isEqualTo(ActionOutcome.DONE);
        assertThat(moves).hasSize(moved);
        assertThat(asThePlatform(() -> doors.suspend(party, decider, "risk.action:b", "RISK_CASE_ATO")))
                .as("another key finds the state held").isEqualTo(ActionOutcome.ALREADY);
        assertThat(claims.keySet()).extracting(IdempotencyKey::scope).containsOnly(IdentityRiskActions.SCOPE);
    }

    @Test
    @DisplayName("REINSTATE_IDENTITY reinstates a suspended identity; REVOKE_SESSIONS ends the live sessions - DONE,"
            + " none live ALREADY; a closed identity, a party with none and a decider who is no identity REFUSED")
    void theOtherKindsAndTheRefusals() {
        UUID party = UUID.randomUUID();
        Identity suspended = identity(party, IdentityStatus.SUSPENDED);
        causes.put(suspended.id(), SuspensionCause.RISK);
        String decider = RiskEvaluationWorld.IDS.next().toString();
        assertThat(asThePlatform(() -> doors.reinstate(party, decider, "risk.action:r", "RISK_CASE_FRAUD")))
                .isEqualTo(ActionOutcome.DONE);
        assertThat(rows.get(suspended.id()).status()).isEqualTo(IdentityStatus.ACTIVE);

        liveSessions.put(suspended.id(), 3);
        assertThat(asThePlatform(() -> doors.revokeSessions(party, "risk.action:s1", "RISK_CASE_ATO")))
                .isEqualTo(ActionOutcome.DONE);
        assertThat(audit).filteredOn(record -> record.operation().code().equals("identity.SessionRevoked"))
                .singleElement().satisfies(record -> assertThat(record.actor()).isEqualTo(Actor.SYSTEM));
        assertThat(asThePlatform(() -> doors.revokeSessions(party, "risk.action:s2", "RISK_CASE_ATO")))
                .isEqualTo(ActionOutcome.ALREADY);

        UUID closedParty = UUID.randomUUID();
        identity(closedParty, IdentityStatus.CLOSED);
        assertThat(asThePlatform(() -> doors.suspend(closedParty, decider, "risk.action:c", "RISK_CASE_ATO")))
                .isEqualTo(ActionOutcome.REFUSED);
        assertThat(asThePlatform(() -> doors.suspend(UUID.randomUUID(), decider, "risk.action:n", "RISK_CASE_ATO")))
                .isEqualTo(ActionOutcome.REFUSED);
        UUID other = UUID.randomUUID();
        identity(other, IdentityStatus.ACTIVE);
        assertThat(asThePlatform(() -> doors.suspend(other, "officer-not-an-identity", "risk.action:d",
                "RISK_CASE_ATO"))).as("identity names two parties").isEqualTo(ActionOutcome.REFUSED);
        assertThat(rows.values()).filteredOn(row -> row.partyId().equals(other))
                .allSatisfy(row -> assertThat(row.status()).isEqualTo(IdentityStatus.ACTIVE));
    }

    @Test
    @DisplayName("item 25 (the Phase 13 gate): REINSTATE_IDENTITY lifts risk's own suspension only - an"
            + " administrator's and one recorded with no cause stand, that identity REFUSED; a party whose only"
            + " suspension is an administrator's is REFUSED and stays suspended")
    void riskLiftsOnlyItsOwnSuspension() {
        UUID party = UUID.randomUUID();
        Identity byRisk = identity(party, IdentityStatus.SUSPENDED);
        causes.put(byRisk.id(), SuspensionCause.RISK);
        Identity byAdministrator = identity(party, IdentityStatus.SUSPENDED);
        causes.put(byAdministrator.id(), SuspensionCause.ADMINISTRATOR);
        Identity unrecorded = identity(party, IdentityStatus.SUSPENDED);
        String decider = RiskEvaluationWorld.IDS.next().toString();

        assertThat(asThePlatform(() -> doors.reinstate(party, decider, "risk.action:own", "RISK_CASE_FRAUD")))
                .isEqualTo(ActionOutcome.DONE);
        assertThat(rows.get(byRisk.id()).status()).isEqualTo(IdentityStatus.ACTIVE);
        assertThat(rows.get(byAdministrator.id()).status()).as("identity's decision stands")
                .isEqualTo(IdentityStatus.SUSPENDED);
        assertThat(rows.get(unrecorded.id()).status()).as("fail closed").isEqualTo(IdentityStatus.SUSPENDED);
        assertThat(audit).filteredOn(record -> record.operation().code().equals("identity.IdentityReinstated"))
                .singleElement().satisfies(record -> assertThat(record.changeSummary().orElseThrow())
                        .contains("cause=RISK"));

        UUID administered = UUID.randomUUID();
        Identity held = identity(administered, IdentityStatus.SUSPENDED);
        causes.put(held.id(), SuspensionCause.ADMINISTRATOR);
        assertThat(asThePlatform(() -> doors.reinstate(administered, decider, "risk.action:admin", "RISK_CASE_FRAUD")))
                .isEqualTo(ActionOutcome.REFUSED);
        assertThat(rows.get(held.id()).status()).isEqualTo(IdentityStatus.SUSPENDED);
        assertThat(causes).containsEntry(held.id(), SuspensionCause.ADMINISTRATOR);
    }

    @Test
    @DisplayName("identity's not-self rule is given the approver: an approver who is the subject's own identity is"
            + " refused by identity itself")
    void identitysNotSelfRuleHolds() {
        UUID party = UUID.randomUUID();
        Identity self = identity(party, IdentityStatus.ACTIVE);
        assertThat(asThePlatform(() -> doors.suspend(party, self.id().value().toString(), "risk.action:self",
                "RISK_CASE_ATO"))).isEqualTo(ActionOutcome.REFUSED);
        assertThat(rows.get(self.id()).status()).isEqualTo(IdentityStatus.ACTIVE);
        assertThatThrownBy(() -> doors.suspend(party, null, "k", "RISK_CASE_ATO"))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------

    private Identity identity(UUID party, IdentityStatus status) {
        Instant now = RiskEvaluationWorld.CLOCK.instant();
        Identity identity = Identity.rehydrate(IdentityId.next(RiskEvaluationWorld.IDS), party,
                new LoginIdentifier("user." + rows.size()), status, now, now);
        rows.put(identity.id(), identity);
        return identity;
    }

    private static <T> T asThePlatform(Supplier<T> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }

    /** The platform's claim store, in memory. */
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
