package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V016`, read as text (`P9-TSK-014`): the format ids are their enum's WHOLE list, the
 * corridor's format is a payout provider's report by kind, and the source row is seeded once - and
 * nothing else changes.
 */
@DisplayName("settlement V016 - the corridor provider report (P9-TSK-014)")
class SettlementV016MigrationTest {

    private static final String MIGRATION = "db/migration/settlement/V016__the_corridor_provider_report.sql";

    @Test
    @DisplayName("each format CHECK is the enum's whole list, appended at the end, on a payout report's kind")
    void theFormatListIsTheEnum() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT file_format CHECK (format_id IN ("
                        + SettlementFormatId.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT batch_format CHECK (format_id IN ("
                        + SettlementFormatId.sqlValueList() + "))"));
        SettlementFormatId[] formats = SettlementFormatId.values();
        assertThat(formats[formats.length - 1]).as("appended last").isEqualTo(SettlementFormatId.SIM_CORRIDOR_CSV);
        assertThat(SettlementFormatId.SIM_CORRIDOR_CSV.kind()).isEqualTo(SourceKind.PAYOUT_PROVIDER_REPORT);
    }

    @Test
    @DisplayName("the source row is seeded once, and no other vocabulary, grant or table changes")
    void theSeedAndNothingElse() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("'corridor-sim-a.settlement', 'PAYOUT_PROVIDER_REPORT', 'ACTIVE', 1)")
                .doesNotContain("source_kind")
                .doesNotContain("line_type")
                .doesNotContain("line_reference_kind")
                .doesNotContain("rejection_code")
                .doesNotContain("GRANT")
                .doesNotContain("CREATE TABLE");
    }

    private static String migration() {
        try (InputStream in = SettlementV016MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }
}
