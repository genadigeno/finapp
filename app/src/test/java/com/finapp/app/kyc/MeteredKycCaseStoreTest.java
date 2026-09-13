package com.finapp.app.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The case-throughput counter's exact semantics (`P2-TSK-020`): the meter's increments are the
 * arbiters' own verdicts and nothing else — {@code created} for {@code opened}, a <em>won</em>
 * terminal move for {@code approved}/{@code rejected}.
 *
 * <p>Hermetic against a scripted delegate, deliberately: the decorator is door-agnostic, so the
 * property to prove is what it does with each answer the store can give, not which door
 * produced the call. That the real bean is actually decorated is
 * {@code PlannedMetersExistTest}'s subject (the series would not exist otherwise), and one real
 * door's increment is asserted end to end in {@code KycCaseEndpointDatabaseTest}.
 */
@DisplayName("the case-throughput meter (P2-TSK-020)")
class MeteredKycCaseStoreTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-13T12:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("every outcome's series exists at zero before anything has happened")
    void everySeriesExistsEagerly() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new MeteredKycCaseStore(new ScriptedStore(), registry);

        // P1-TSK-029's rule: an alert on a rate needs a series sitting at zero, not one that
        // starts existing when the first case opens. The tag values are the machine's own -
        // "opened" plus each terminal status - so a new terminal state registers itself.
        for (String outcome : new String[] {"opened", "approved", "rejected"}) {
            assertThat(
                            registry.get("finapp.kyc.case")
                                    .tag("outcome", outcome)
                                    .counter()
                                    .count())
                    .as("finapp.kyc.case{outcome=%s} exists at zero", outcome)
                    .isZero();
        }
    }

    @Test
    @DisplayName("a created open counts once; a converged one counts nothing")
    void convergedOpensAreNotThroughput() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ScriptedStore delegate = new ScriptedStore();
        MeteredKycCaseStore store = new MeteredKycCaseStore(delegate, registry);
        KycCase fresh = someCase();

        delegate.created = true;
        store.openOrConverge(null, fresh);
        delegate.created = false;
        store.openOrConverge(null, fresh);
        store.openOrConverge(null, fresh);

        // A duplicate delivery, a retry and the losers of a race are handed the winner's case,
        // not new throughput - the same discipline that keeps the converged path unrecorded and
        // unannounced (INV-KYC-03), applied at the meter.
        assertThat(counter(registry, "opened")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a won terminal move counts under its own status; a lost one counts nothing")
    void onlyTheWonTerminalMoveIsADecision() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ScriptedStore delegate = new ScriptedStore();
        MeteredKycCaseStore store = new MeteredKycCaseStore(delegate, registry);
        KycCaseId caseId = someCase().id();
        Instant at = Instant.now(CLOCK);

        delegate.moveWins = true;
        store.moveStatus(null, caseId, KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.APPROVED, at);
        store.moveStatus(null, caseId, KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.REJECTED, at);
        delegate.moveWins = false;
        // The N-1 losers of a racing decision: the conditional already made them not-a-decision,
        // and the meter must agree or one decision reads as several.
        store.moveStatus(null, caseId, KycCaseStatus.READY_FOR_DECISION, KycCaseStatus.APPROVED, at);

        assertThat(counter(registry, "approved")).isEqualTo(1.0d);
        assertThat(counter(registry, "rejected")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a non-terminal move is not a decision, however won")
    void nonTerminalMovesAreNotDecisions() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ScriptedStore delegate = new ScriptedStore();
        MeteredKycCaseStore store = new MeteredKycCaseStore(delegate, registry);
        delegate.moveWins = true;

        store.moveStatus(
                null,
                someCase().id(),
                KycCaseStatus.OPEN,
                KycCaseStatus.CHECKS_IN_PROGRESS,
                Instant.now(CLOCK));

        assertThat(counter(registry, "approved")).isZero();
        assertThat(counter(registry, "rejected")).isZero();
        assertThat(counter(registry, "opened")).isZero();
    }

    // -----------------------------------------------------------------

    private static double counter(MeterRegistry registry, String outcome) {
        return registry.get("finapp.kyc.case").tag("outcome", outcome).counter().count();
    }

    private static KycCase someCase() {
        return KycCase.open(
                new com.finapp.sharedkernel.id.IdGenerator(CLOCK, new SecureRandom()),
                CLOCK,
                UUID.randomUUID(),
                KycCaseKind.KYC);
    }

    /** Answers whatever the test scripts; the decorator must add nothing of its own. */
    private static final class ScriptedStore implements KycCaseStore<Connection> {

        boolean created;
        boolean moveWins;

        @Override
        public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
            return new Opening(fresh, created);
        }

        @Override
        public boolean moveStatus(
                Connection unitOfWork,
                KycCaseId caseId,
                KycCaseStatus from,
                KycCaseStatus to,
                Instant at) {
            return moveWins;
        }

        @Override
        public Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customerId) {
            return Optional.empty();
        }

        @Override
        public Optional<KycCase> findById(Connection unitOfWork, KycCaseId caseId) {
            return Optional.empty();
        }

        @Override
        public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customerId) {
            return Optional.empty();
        }

        @Override
        public boolean moveToReadyForDecision(
                Connection unitOfWork, KycCaseId caseId, KycCaseStatus from, Instant at) {
            return moveWins;
        }
    }
}
