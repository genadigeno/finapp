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
 * Settlement `V006`, read as text (`P8-TSK-017`): the scheme's line types and references are the
 * enums' whole lists, appended to the bank statement's vocabulary, and nothing else moves — the
 * cycle is the batch's identity, never a line reference.
 */
@DisplayName("settlement V006 - the scheme cycle report's vocabulary (P8-TSK-017)")
class SettlementV006MigrationTest {

    private static final String MIGRATION =
            "db/migration/settlement/V006__the_scheme_cycle_report.sql";

    @Test
    @DisplayName("the line-type and reference CHECKs are the scheme vocabulary's lists - exactly the"
            + " scheme's members added to V005's")
    void theSchemeVocabularyIsTheEnums() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT batch_total_line_type CHECK (line_type IN ("
                        + SettlementLineType.sqlValueList(SettlementLineType.schemeVocabulary())
                        + "))"))
                .contains(normalized("ADD CONSTRAINT line_type CHECK (line_type IN ("
                        + SettlementLineType.sqlValueList(SettlementLineType.schemeVocabulary())
                        + "))"))
                .contains(normalized("ADD CONSTRAINT line_reference_kind CHECK (kind IN ("
                        + LineReferenceKind.sqlValueList(LineReferenceKind.schemeVocabulary())
                        + "))"));

        Set<SettlementLineType> added = EnumSet.copyOf(SettlementLineType.schemeVocabulary());
        added.removeAll(SettlementLineType.bankVocabulary());
        assertThat(added)
                .containsExactly(
                        SettlementLineType.CREDIT_IN,
                        SettlementLineType.DEBIT_OUT,
                        SettlementLineType.SCHEME_FEE);
        Set<LineReferenceKind> references =
                EnumSet.copyOf(LineReferenceKind.schemeVocabulary());
        references.removeAll(LineReferenceKind.bankVocabulary());
        assertThat(references)
                .as("no SETTLEMENT_CYCLE reference: the cycle is the batch's identity")
                .containsExactly(LineReferenceKind.SCHEME_REF, LineReferenceKind.END_TO_END_REF);
        assertThat(sql)
                .as("V006 touches only the three CHECKs and grants nothing")
                .doesNotContain("GRANT")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("ADD COLUMN");
    }

    private static String migration() {
        try (InputStream in =
                SettlementV006MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
