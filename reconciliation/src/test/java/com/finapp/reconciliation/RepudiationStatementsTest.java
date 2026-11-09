package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, as text, the repudiation's read of the break its new {@code MISSING_EXTERNAL} break continues (`P15-TSK-037`; {@code THREAT_MODEL.md} T13): the chain's tail by its
 * own column, no stamp. Hermetic: its database case is owed to {@code P15-TST-002}.
 */
@DisplayName("a repudiation continues the break chain's tail, ranked by the rows, never by a stamp (P15-TSK-037)")
class RepudiationStatementsTest {

    @Test
    @DisplayName("the last resolved break on an expectation prefers the chain's tail, the resolved break no later break continues, and orders by no stamp (a trace link: DISPLAYS in the census)")
    void theLastResolvedBreakIsTheTail() {
        assertThat(JdbcRepudiationStore.LAST_RESOLVED_BREAK_ON)
                .contains("AND NOT EXISTS (SELECT 1 FROM reconciliation.break f WHERE f.expectation_id = b.expectation_id AND f.follows_break_id = b.id)")
                .contains("ORDER BY b.id DESC LIMIT 1")
                .doesNotContain("resolved_at");
    }
}
