package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.credit.CreditReplayProof;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDecisions;
import com.finapp.credit.JdbcCreditPolicyStore;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcScorecardStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replay gauge when the proof cannot be read (`P10-TSK-019`; DOD-OBS): absent, never clean - an unreadable database
 * reports NaN for every verdict rather than a reassuring 0 diverged.
 */
@DisplayName("finapp.credit.replay reports absent, never clean, when unreadable (P10-TSK-019)")
class CreditReplayMetricsTest {

    @Test
    @DisplayName("an unreadable reading is NaN for both verdicts, and an operator's read-now refuses rather than reassures")
    void unreadableIsAbsentNeverClean() {
        DecisionReplayer replayer = new DecisionReplayer(new JdbcCreditDecisions(), new JdbcDecisionSnapshotStore(),
                new JdbcCreditPolicyStore(), new JdbcScorecardStore(), new JdbcCreditAssessmentStore(),
                new JdbcPolicyEvaluationStore(), new JdbcUnderwritingCaseStore(), EngineVersions.STANDARD);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditReplayMetrics metrics = new CreditReplayMetrics(new CreditReplayProof(replayer),
                new CreditReadingSnapshot(() -> {
                    throw new SQLException("the database is down", "08001");
                }),
                Clock.systemUTC(), registry);
        for (DecisionReplayer.Verdict verdict : DecisionReplayer.Verdict.values()) {
            assertThat(registry.get(CreditReplayMetrics.REPLAY).tag("verdict", verdict.name()).gauge().value())
                    .as(verdict.name()).isNaN();
        }
        assertThatIllegalStateException().isThrownBy(metrics::readNow);
    }
}
