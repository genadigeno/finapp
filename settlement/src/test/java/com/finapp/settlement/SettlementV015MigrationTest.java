package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V015`, read as text (`P9-TSK-011`): every vocabulary the FX provider's report
 * touches is its enum's WHOLE list - the source kinds and format ids reconciled for the first time
 * - the readmission rule refuses the new verdict, and the source row is seeded once.
 */
@DisplayName("settlement V015 - the FX provider report's vocabulary (P9-TSK-011)")
class SettlementV015MigrationTest {

    private static final String MIGRATION = "db/migration/settlement/V015__the_fx_provider_report.sql";

    @Test
    @DisplayName("each CHECK is its enum's whole list, appended at the end")
    void everyListIsTheEnum() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT source_kind CHECK (kind IN (" + SourceKind.sqlValueList() + "))"))
                // The format lists as V015 wrote them - applied history: V016 (P9-TSK-014) appended
                // SIM_CORRIDOR_CSV, and SettlementV016MigrationTest holds the current list.
                .contains(normalized("ADD CONSTRAINT file_format CHECK (format_id IN ("
                        + "'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED', 'SIM_FX_CSV'))"))
                .contains(normalized("ADD CONSTRAINT batch_format CHECK (format_id IN ("
                        + "'SIM_PSP_CSV', 'SIM_SCHEME_JSON', 'SIM_PAYOUT_CSV', 'SIM_STATEMENT_TAGGED', 'SIM_FX_CSV'))"))
                .contains(normalized("rejection_code IS NULL OR rejection_code IN (" + RejectionCode.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT batch_total_line_type CHECK (line_type IN ("
                        + SettlementLineType.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT line_type CHECK (line_type IN ("
                        + SettlementLineType.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT line_reference_kind CHECK (kind IN ("
                        + LineReferenceKind.sqlValueList() + "))"));
        Set<SettlementLineType> added = EnumSet.allOf(SettlementLineType.class);
        added.removeAll(EnumSet.range(SettlementLineType.CAPTURE, SettlementLineType.PAYOUT_FEE));
        assertThat(added).as("appended, contiguous").containsExactlyElementsOf(SettlementLineType.fxVocabulary());
        assertThat(SettlementLineType.FX_FEE.isReportFee()).as("hop 1 recognises the provider's fee").isTrue();
        assertThat(SettlementLineType.FX_SOLD.isReportFee()).isFalse();
        assertThat(SettlementLineType.FX_BOUGHT.isReportFee()).isFalse();
        assertThat(SettlementFormatId.SIM_FX_CSV.kind()).isEqualTo(SourceKind.FX_PROVIDER_REPORT);
        assertThat(SourceKind.FX_PROVIDER_REPORT.settlesAPosition()).isTrue();
        assertThat(RejectionCode.CURRENCY_NOT_SETTLED.leavesErrorRows())
                .as("a whole-batch verdict, like SOURCE_RETIRED: no per-line error rows")
                .isFalse();
    }

    @Test
    @DisplayName("the readmission rule refuses a CURRENCY_NOT_SETTLED original, and the source row is seeded once")
    void theRuleAndTheSeed() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("AND o.rejection_code NOT IN ('SOURCE_RETIRED', 'CURRENCY_NOT_SETTLED'))")
                .contains("'fx-sim-a.trade-report', 'FX_PROVIDER_REPORT', 'ACTIVE', 1)")
                .doesNotContain("GRANT")
                .doesNotContain("CREATE TABLE");
    }

    private static String migration() {
        try (InputStream in = SettlementV015MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
