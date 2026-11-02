package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two reads accounting's reports took from here (`P14-TSK-017`; {@code INV-ACC-04}), by their statements; their behaviour against
 * PostgreSQL is {@code OperationalReportsDatabaseTest}'s in {@code app} (owed, C5). The born-as-of read differs from the as-of read by
 * exactly the owner's frozen raise, so an adoption after the instant cannot join it; the run lines select no identity, rule set, requester
 * or reason, in a total order.
 */
@DisplayName("the report readings' statements (P14-TSK-017)")
class ReportReadingsStatementsTest {

    @Test
    @DisplayName("born as of: the as-of read plus the owner's frozen raise before the instant - nothing else differs")
    void bornAsOfIsTheAsOfReadAndTheOwnersRaise() {
        String raise = " JOIN reconciliation.break b ON b.id = s.break_id AND b.raised_at < ?";
        assertThat(JdbcReportReadings.SUSPENSE_BORN_AS_OF).contains(raise);
        assertThat(JdbcReportReadings.SUSPENSE_BORN_AS_OF.replace(raise, "")).isEqualTo(JdbcReportReadings.SUSPENSE_AS_OF);
    }

    @Test
    @DisplayName("the run lines: grouped by source, kind, status and day and ordered by the same; a bound on the business date; no other column")
    void theRunLines() {
        assertThat(JdbcReportReadings.RUNS_OF_BUSINESS_DATES)
                .startsWith("SELECT source_id, kind, status, business_date, count(*) AS runs FROM reconciliation.reconciliation_batch")
                .contains("WHERE business_date >= ? AND business_date <= ?")
                .contains("GROUP BY source_id, kind, status, business_date")
                .endsWith("ORDER BY source_id, kind, status, business_date");
    }

    @Test
    @DisplayName("both only read, name no other schema, and hold nothing a report's restricted view refuses")
    void bothOnlyRead() {
        for (String sql : List.of(JdbcReportReadings.SUSPENSE_BORN_AS_OF, JdbcReportReadings.RUNS_OF_BUSINESS_DATES)) {
            String lower = sql.toLowerCase(Locale.ROOT);
            assertThat(lower).startsWith("select ");
            assertThat(lower).doesNotContain(";").doesNotContain("for share").doesNotContain("for update");
            // ReadOnlySnapshot's refused functions, matched as it matches them: anywhere in the text.
            for (String refused : List.of("set_config", "pg_advisory", "pg_notify", "pg_sleep", "dblink", "lo_")) {
                assertThat(lower).as(refused).doesNotContain(refused);
            }
            assertThat(lower).doesNotContain("accounting.").doesNotContain("ledger.").doesNotContain("reason")
                    .doesNotContain("requested_by");
        }
    }
}
