package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.decide;
import static com.finapp.app.credit.CreditWorld.deciding;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.evaluated;
import static com.finapp.app.credit.CreditWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.risk.RiskCreditRiskSignal;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDecisions;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.CustomerRiskProfiles;
import com.finapp.risk.JdbcCustomerRiskProfileStore;
import com.finapp.risk.JdbcRiskPolicyStore;
import com.finapp.risk.ProfileInputs;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicies;
import com.finapp.risk.RiskPolicyAdministration;
import com.finapp.risk.RiskRating;
import com.finapp.risk.ScreeningClearances;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Credit's risk seam at version 2, through credit's real freezer and replayer (`P13-TSK-007`; {@code PHASE_13_PLAN.md}
 * section 13.2 scenario 13; {@code INV-RSK-10}, {@code INV-CRD-01}): a decision sealed with the rating risk gave it -
 * the code, seam version 2 and the profile version, in canonical format 2 - replays {@code IDENTICAL} after the profile
 * moves on, and its sealed text is the same bytes; a decision sealed through Phase 10's seam replays {@code NOT_ASSESSED}
 * at version 1, as before. <strong>Written and owed</strong> (owner decision C5).
 */
@Tag("database")
@DisplayName("credit's risk seam, version 2, sealed and replayed (P13-TSK-007)")
class CreditRiskSeamDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Duration WEEK = Duration.ofDays(7);
    private static final JdbcRiskPolicyStore POLICY_STORE = new JdbcRiskPolicyStore();
    private static final JdbcCustomerRiskProfileStore PROFILES = new JdbcCustomerRiskProfileStore();
    private static final Map<UUID, Boolean> SCREENED = new ConcurrentHashMap<>();
    private static final ProfileRecompute RECOMPUTE = new ProfileRecompute(new RiskPolicies(POLICY_STORE), PROFILES,
            new ProfileInputs((unitOfWork, party) -> Optional.ofNullable(SCREENED.get(party))
                    .map(hit -> new ScreeningClearances.Clearance(UUID.randomUUID(), hit, Instant.now()))),
            new JdbcOutboxWriter(), IDS, CLOCK, Duration.ofDays(1));
    private static final RiskCreditRiskSignal SEAM = new RiskCreditRiskSignal(new CustomerRiskProfiles(PROFILES,
            (unitOfWork, actor) -> Optional.empty(), new JdbcAuditWriter(), IDS, CLOCK));

    @BeforeAll
    static void world() {
        CreditWorld.seedsInForce();
        CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
        RiskPolicyAdministration administration = new RiskPolicyAdministration(POLICY_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK);
        RiskPolicyAdministration.Proposed proposed = inOneTransaction(uow -> administration.propose(uow,
                RiskPolicyFixtures.v1(RiskOperation.MONITORING), "the seam suite's monitoring version", employee(),
                CorrelationId.generate(IDS)));
        inOneTransaction(uow -> administration.activate(uow, proposed.id(), employee(), "activated",
                CorrelationId.generate(IDS)));
    }

    @Test
    @DisplayName("a rating change never rewrites a sealed decision: sealed LOW at profile version 1, the profile moves to"
            + " MEDIUM at version 2, the decision replays IDENTICAL from the same bytes (scenario 13)")
    void aRatingChangeNeverRewritesASealedDecision() {
        UUID party = IDS.next();
        assertThat(inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, CorrelationId.generate(IDS))).rating())
                .contains(RiskRating.LOW);
        UUID request;
        CreditWorld.risk = SEAM;
        try {
            request = evaluated(party, CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
            assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.DECIDED);
        } finally {
            CreditWorld.risk = new NotAssessedUntilPhase13();
        }
        String sealed = scalar("SELECT canonical FROM credit.decision_snapshot WHERE decision_request_id = ?"
                + " ORDER BY sequence DESC LIMIT 1", request);
        assertThat(sealed).startsWith("{\"format\":\"2\"")
                .contains("{\"code\":\"RISK_SIGNAL\",\"value\":{\"code\":\"LOW\"},\"provenance\":{\"kind\":\"port\","
                        + "\"port\":\"risk-signal\",\"version\":\"2\",\"recordVersion\":\"1\"}}");

        SCREENED.put(party, true);
        ProfileRecompute.Outcome moved = inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, CorrelationId.generate(IDS)));
        assertThat(moved.version()).isEqualTo(2);
        assertThat(moved.rating()).contains(RiskRating.MEDIUM);

        assertThat(replay(request).verdict()).as("the decision replays from its snapshot, never the seam")
                .isEqualTo(DecisionReplayer.Verdict.IDENTICAL);
        assertThat(scalar("SELECT canonical FROM credit.decision_snapshot WHERE decision_request_id = ?"
                + " ORDER BY sequence DESC LIMIT 1", request)).as("the same bytes").isEqualTo(sealed);
    }

    @Test
    @DisplayName("a decision sealed through Phase 10's seam replays NOT_ASSESSED at seam version 1 - whatever risk now holds"
            + " about the party (INV-CRD-01)")
    void aVersionOneDecisionReplaysNotAssessed() {
        UUID party = IDS.next();
        UUID request = evaluated(party, CreditProduct.CREDIT_LINE, eur(200_000), WEEK);
        assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.DECIDED);
        SCREENED.put(party, true);
        inOneTransaction(uow -> RECOMPUTE.recompute(uow, party, CorrelationId.generate(IDS)));
        String sealed = scalar("SELECT canonical FROM credit.decision_snapshot WHERE decision_request_id = ?"
                + " ORDER BY sequence DESC LIMIT 1", request);
        assertThat(sealed).contains("{\"code\":\"RISK_SIGNAL\",\"value\":{\"code\":\"NOT_ASSESSED\"},\"provenance\":"
                + "{\"kind\":\"port\",\"port\":\"risk-signal\",\"version\":\"1\"}}");
        assertThat(replay(request).verdict()).isEqualTo(DecisionReplayer.Verdict.IDENTICAL);
    }

    // -----------------------------------------------------------------

    private static DecisionReplayer.Replay replay(UUID request) {
        CreditDecisionId decision = CreditDecisionId.of(UUID.fromString(
                scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", request)));
        DecisionReplayer replayer = new DecisionReplayer(new JdbcCreditDecisions(), new JdbcDecisionSnapshotStore(),
                CreditWorld.POLICIES, CreditWorld.SCORECARDS, new JdbcCreditAssessmentStore(),
                new JdbcPolicyEvaluationStore(), new JdbcUnderwritingCaseStore(), EngineVersions.STANDARD);
        return new CreditReadingSnapshot(DatabaseRoles::application).read(uow -> replayer.replay(uow, decision))
                .orElseThrow();
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }
}
