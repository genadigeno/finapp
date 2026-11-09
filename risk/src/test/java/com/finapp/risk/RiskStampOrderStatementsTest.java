package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, as text, the three single-row reads of `P15-TSK-037` in risk that a stamp no longer ranks ({@code THREAT_MODEL.md} T13). Hermetic: that each answers
 * correctly with the clock stepped is {@code RiskStampOrderDatabaseTest}, owed to {@code P15-TST-002}.
 */
@DisplayName("risk's single-row reads are ranked by the rows, never a stamp a stepped clock wrote - pinned as text (P15-TSK-037)")
class RiskStampOrderStatementsTest {

    @Test
    @DisplayName("a new case names its predecessor by the chain's tail, the disposed case no later case names: no stamp in its rank")
    void aCasesPredecessorIsTheTail() {
        assertThat(JdbcRiskCaseStore.LATEST_DISPOSED)
                .contains("AND NOT EXISTS (SELECT 1 FROM risk.risk_case s WHERE s.subject_ref = c.subject_ref AND s.kind = c.kind AND s.predecessor_case_id = c.id)")
                .contains("ORDER BY c.id DESC LIMIT 1")
                .doesNotContain("disposed_at");
    }

    @Test
    @DisplayName("a judged payment is ranked by its operation, not the sealed assessment's stamp: both rows name one party and merchant")
    void aJudgedPaymentIsNotRankedByAStamp() {
        assertThat(JdbcRiskAssessmentStore.JUDGED_PAYMENT).endsWith("ORDER BY operation LIMIT 1").doesNotContain("assessed_at");
    }

    @Test
    @DisplayName("the latest signal is by the source event's own instant (INV-EVT-04), its tie-break the id, no longer the database's recorded_at")
    void theLatestSignalIsTheEventsOwnTime() {
        assertThat(JdbcRiskSignalStore.LATEST).endsWith("ORDER BY observed_at DESC, id DESC LIMIT 1").doesNotContain("recorded_at");
    }
}
