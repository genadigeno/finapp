package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pin, hermetic (`P13-TSK-002`; ADR-0109 decision 6; {@code INV-RSK-05}; the Phase 12 → 13 gate's A3-I1 rule
 * applied forward): the {@code ACTIVE} row read once {@code FOR SHARE} on the common path, taking nothing of the
 * administration's; when that first read is empty, advisory namespace 13 taken SHARED and exactly one more read - the
 * successor pinned when one committed, a genuine none otherwise. The live race is
 * {@code RiskPolicyDatabaseTest#anEvaluationBehindAnActivationPinsTheSuccessor} (written, owed - C5); this holds the
 * composition's shape so that a pin which reads once and answers "none" is red here on every build.
 */
@DisplayName("the pin never answers \"neither\": one FOR SHARE read, then the shared namespace and one more (P13-TSK-002)")
class RiskPoliciesPinTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(
            RiskPoliciesPinTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the fakes never touch the connection");
            });

    @Test
    @DisplayName("an ACTIVE row on the first read is pinned with no lock taken - the common path costs one row read")
    void theCommonPathTakesNothingOfTheAdministrations() {
        ScriptedStore store = new ScriptedStore();
        RiskPolicyVersionId active = store.activeFirst(RiskOperation.TRANSFER);
        Optional<RiskPolicyVersion> pinned = new RiskPolicies(store).active(UOW, RiskOperation.TRANSFER);
        assertThat(pinned).isPresent();
        assertThat(pinned.get().id()).isEqualTo(active);
        assertThat(store.log).containsExactly("share TRANSFER", "version " + active.value());
    }

    @Test
    @DisplayName("an empty first read takes namespace 13 SHARED and reads once more: the successor an activation committed"
            + " is pinned, never \"neither\"")
    void anEmptyFirstReadReadsAgainUnderTheSharedNamespace() {
        ScriptedStore store = new ScriptedStore();
        RiskPolicyVersionId successor = store.activeOnSecondRead(RiskOperation.WITHDRAWAL);
        Optional<RiskPolicyVersion> pinned = new RiskPolicies(store).active(UOW, RiskOperation.WITHDRAWAL);
        assertThat(pinned).as("the successor, committed by the activation the first read raced").isPresent();
        assertThat(pinned.get().id()).isEqualTo(successor);
        assertThat(store.log).containsExactly("share WITHDRAWAL", "A0-shared WITHDRAWAL", "share WITHDRAWAL",
                "version " + successor.value());
    }

    @Test
    @DisplayName("an empty second read is a genuine none - exactly two reads, one shared lock, no third attempt - and the"
            + " unlocked in-force read answers PolicyNotActive")
    void anEmptySecondReadIsGenuine() {
        ScriptedStore store = new ScriptedStore();
        RiskPolicies policies = new RiskPolicies(store);
        assertThat(policies.active(UOW, RiskOperation.CROSS_BORDER)).isEmpty();
        assertThat(store.log).containsExactly("share CROSS_BORDER", "A0-shared CROSS_BORDER", "share CROSS_BORDER");
        assertThatThrownBy(() -> policies.inForce(UOW, RiskOperation.CROSS_BORDER))
                .isInstanceOf(RiskPolicies.PolicyNotActive.class);
        assertThatThrownBy(() -> policies.version(UOW, RiskPolicyVersionId.next(IDS)))
                .isInstanceOf(RiskPolicyAdministration.PolicyNotFound.class);
    }

    @Test
    @DisplayName("the lock statements are namespace 13's: exclusive for T-pol, shared for the pin's re-read - the same key")
    void theStatementsAreNamespaceThirteens() {
        assertThat(JdbcRiskPolicyStore.NAMESPACE).isEqualTo(13);
        assertThat(JdbcRiskPolicyStore.LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock(13, hashtext(?))");
        assertThat(JdbcRiskPolicyStore.SHARED_LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock_shared(13, hashtext(?))");
        assertThat(JdbcRiskPolicyStore.SHARE_ACTIVE_SQL).endsWith("status = 'ACTIVE' FOR SHARE");
    }

    // -----------------------------------------------------------------

    /** A store whose reads are scripted per operation: present at once, present only after the shared lock, or never. */
    static final class ScriptedStore extends RiskPolicyAdministrationTest.FakeRiskPolicyStore {
        private final java.util.Map<RiskOperation, RiskPolicyVersionId> first = new java.util.EnumMap<>(RiskOperation.class);
        private final java.util.Map<RiskOperation, RiskPolicyVersionId> afterLock = new java.util.EnumMap<>(RiskOperation.class);
        private final java.util.Map<RiskOperation, Boolean> locked = new java.util.EnumMap<>(RiskOperation.class);

        RiskPolicyVersionId activeFirst(RiskOperation operation) {
            RiskPolicyVersionId id = born(operation);
            first.put(operation, id);
            return id;
        }

        RiskPolicyVersionId activeOnSecondRead(RiskOperation operation) {
            RiskPolicyVersionId id = born(operation);
            afterLock.put(operation, id);
            return id;
        }

        private RiskPolicyVersionId born(RiskOperation operation) {
            RiskPolicyVersionId id = RiskPolicyVersionId.next(IDS);
            insertProposal(UOW, new NewProposal(id, 1, RiskPolicyFixtures.v1(operation),
                    new Actor("alice", ActorType.EMPLOYEE), "scripted", CorrelationId.of("pin"), IDS.next()));
            decide(UOW, id, RiskPolicyVersionStatus.ACTIVE, new Actor("bob", ActorType.EMPLOYEE), "scripted");
            log.clear();
            return id;
        }

        @Override
        public void lockOperationShared(Connection unitOfWork, RiskOperation operation) {
            super.lockOperationShared(unitOfWork, operation);
            locked.put(operation, true);
        }

        @Override
        public Optional<RiskPolicyVersionId> shareActiveOnce(Connection unitOfWork, RiskOperation operation) {
            log.add("share " + operation);
            if (first.containsKey(operation)) {
                return Optional.of(first.get(operation));
            }
            if (locked.getOrDefault(operation, false) && afterLock.containsKey(operation)) {
                return Optional.of(afterLock.get(operation));
            }
            return Optional.empty();
        }
    }
}
