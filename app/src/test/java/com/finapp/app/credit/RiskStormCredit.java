package com.finapp.app.credit;

import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditRiskSignal;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDecisions;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;

/**
 * The risk storm's credit decision (`P13-TST-001`, scenario 13) - a public bridge over {@link CreditWorld} (the lending
 * storm's {@code LendingStormCredit} precedent): a credit line decided by credit's real freezer with the composition's own
 * {@code CreditRiskSignal} (risk's seam at version 2, an instance's bean) sealing the party's rating, and the decision
 * replayed by credit's real replayer - from its snapshot, never the seam.
 */
public final class RiskStormCredit {

    private static volatile boolean inForce;

    private RiskStormCredit() {}

    /** The credit line's seed and version in force (once). */
    public static synchronized void inForce() {
        if (!inForce) {
            CreditWorld.seedsInForce();
            CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
            inForce = true;
        }
    }

    /**
     * {@code party}'s credit line request evaluated and decided through {@code seam} (the instance's risk signal) - the
     * request's id. Credit's world is a test fixture's: the seam is set for this decision and put back after it.
     */
    public static synchronized UUID decided(UUID party, CreditRiskSignal<Connection> seam) {
        CreditWorld.risk = seam;
        try {
            UUID request = CreditWorld.evaluated(party, CreditProduct.CREDIT_LINE, CreditWorld.eur(200_000),
                    Duration.ofDays(7));
            Decider.Decided decided = CreditWorld.decide(CreditWorld.deciding(), request);
            if (decided != Decider.Decided.DECIDED) {
                throw new IllegalStateException("the credit line was not decided: " + decided);
            }
            return request;
        } finally {
            CreditWorld.risk = new com.finapp.app.credit.NotAssessedUntilPhase13();
        }
    }

    /** The decision's sealed canonical snapshot - its bytes. */
    public static String sealed(UUID request) {
        return CreditWorld.scalar("SELECT canonical FROM credit.decision_snapshot WHERE decision_request_id = ?"
                + " ORDER BY sequence DESC LIMIT 1", request);
    }

    /** The decision replayed from its snapshot: IDENTICAL, or what it diverged as. */
    public static String replay(UUID request) {
        CreditDecisionId decision = CreditDecisionId.of(UUID.fromString(CreditWorld.scalar(
                "SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", request)));
        DecisionReplayer replayer = new DecisionReplayer(new JdbcCreditDecisions(), new JdbcDecisionSnapshotStore(),
                CreditWorld.POLICIES, CreditWorld.SCORECARDS, new JdbcCreditAssessmentStore(),
                new JdbcPolicyEvaluationStore(), new JdbcUnderwritingCaseStore(), EngineVersions.STANDARD);
        return new CreditReadingSnapshot(DatabaseRoles::application).read(uow -> replayer.replay(uow, decision))
                .orElseThrow().verdict().name();
    }
}
