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
 * Settlement `V007`, read as text (`P8-TSK-018`): the payout provider's line types and reference
 * are the enums' whole lists, appended to the scheme's vocabulary, and nothing else moves.
 */
@DisplayName("settlement V007 - the payout provider report's vocabulary (P8-TSK-018)")
class SettlementV007MigrationTest {

    private static final String MIGRATION =
            "db/migration/settlement/V007__the_payout_provider_report.sql";

    @Test
    @DisplayName("the line-type and reference CHECKs are the enums' whole lists - exactly the"
            + " payout provider's members added to V006's")
    void thePayoutVocabularyIsTheEnums() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT batch_total_line_type CHECK (line_type IN ("
                        + SettlementLineType.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT line_type CHECK (line_type IN ("
                        + SettlementLineType.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT line_reference_kind CHECK (kind IN ("
                        + LineReferenceKind.sqlValueList() + "))"));

        Set<SettlementLineType> added = EnumSet.allOf(SettlementLineType.class);
        added.removeAll(SettlementLineType.schemeVocabulary());
        assertThat(added)
                .containsExactly(
                        SettlementLineType.PAYOUT_EXECUTED,
                        SettlementLineType.PAYOUT_RETURNED,
                        SettlementLineType.PAYOUT_FEE);
        assertThat(SettlementLineType.PAYOUT_FEE.isReportFee())
                .as("hop 1 posts the provider's fee")
                .isTrue();
        assertThat(SettlementLineType.PAYOUT_EXECUTED.isReportFee()).isFalse();
        assertThat(SettlementLineType.PAYOUT_RETURNED.isReportFee()).isFalse();
        Set<LineReferenceKind> references = EnumSet.allOf(LineReferenceKind.class);
        references.removeAll(LineReferenceKind.schemeVocabulary());
        assertThat(references).containsExactly(LineReferenceKind.PAYOUT_PROVIDER_REF);
        assertThat(sql)
                .as("V007 touches only the three CHECKs and grants nothing")
                .doesNotContain("GRANT")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("ADD COLUMN");
    }

    private static String migration() {
        try (InputStream in =
                SettlementV007MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
