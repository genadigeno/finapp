package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A merchant fee schedule version's birth floored by version number (`P15-TSK-037`; {@code THREAT_MODEL.md} T13), pinned as text: the immediate version's start can never
 * read before the latest immediately-effective lower-numbered version's, so a stepped or skewed instance clock cannot shadow the newest rate. Hermetic: its database case
 * is owed to {@code P15-TST-002}.
 */
@DisplayName("a fee schedule version's start is floored at its immediate predecessors', chosen by version number (P15-TSK-037)")
class FeeScheduleVersionFloorStatementsTest {

    @Test
    @DisplayName("the insert floors effective_from at the latest immediate lower-numbered version of the schedule")
    void theStartIsFlooredByVersionNumber() {
        assertThat(JdbcFeeScheduleStore.INSERT_VERSION)
                .contains("GREATEST(CAST(? AS timestamptz), (SELECT max(v.effective_from) FROM merchant.fee_schedule_version v"
                        + " WHERE v.fee_schedule_id = ? AND v.version < ? AND v.effective_from = v.created_at))");
    }
}
