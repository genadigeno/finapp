package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The count behind {@code finapp.credit.scorecard.awaited} (`P15-TSK-025`); its run over real rows is owed to the database tier (C14). */
@DisplayName("requests awaiting a scorecard: only those at SUBMITTED, and none while a version of the family is ACTIVE (P15-TSK-025)")
class ScorecardAwaitedStatementTest {

    @Test
    @DisplayName("the statement counts SUBMITTED requests, gated on no ACTIVE version of the asked family")
    void theStatementIsGatedOnTheFamily() {
        assertThat(JdbcScorecardStore.AWAITING_AN_ACTIVE_VERSION)
                .contains("FROM credit.decision_request WHERE status = 'SUBMITTED'")
                .contains("NOT EXISTS (SELECT 1 FROM credit.scorecard_model_version WHERE family = ? AND status = 'ACTIVE')");
    }
}
