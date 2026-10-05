package com.finapp.reconciliation;

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
 * Reconciliation `V020`, read as text (`P9-TSK-011`): every vocabulary the FX provider touches is
 * its enum's WHOLE list, appended at the end; {@code FX_FEE} is a priced, non-allocating fee line
 * naming its original by {@code COVER_REF}; the two new causes raise exactly their types; and the
 * pairing trigger and V014's timing list are regenerated. No fifteenth break type.
 */
@DisplayName("reconciliation V020 - the FX provider's vocabulary (P9-TSK-011)")
class ReconciliationV020MigrationTest {

    private static final String V020 = "db/migration/reconciliation/V020__the_fx_provider_vocabulary.sql";

    @Test
    @DisplayName("each CHECK is its enum's whole list")
    void everyListIsTheEnum() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT expectation_kind CHECK (kind IN (" + ExpectationKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT rule_expectation_kind CHECK (expectation_kind IS NULL OR expectation_kind IN ("
                        + ExpectationKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT rule_set_lag_kind CHECK (expectation_kind IN ("
                        + ExpectationKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT expectation_key_kind CHECK (key_kind IN (" + KeyKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT reference_alias_kind CHECK (key_kind IN (" + KeyKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT reference_alias_anchor_kind CHECK (anchor_kind IN ("
                        + KeyKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT match_candidate_key_kind CHECK (key_kind IN (" + KeyKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT match_decision_key_kind CHECK (matched_key_kind IS NULL OR"
                        + " matched_key_kind IN (" + KeyKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT rule_key_kind CHECK (key_kind IS NULL OR key_kind IN ("
                        + KeyKind.sqlValueList() + ", 'ORIGINAL_REF'))"))
                .contains(normalized("ADD CONSTRAINT external_item_line_type CHECK (line_type IN ("
                        + ExternalLineType.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN ("
                        + ItemKeyKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT provider_fee_line_type CHECK (line_type IN ("
                        + "'PROCESSING_FEE', 'SCHEME_FEE', 'BANK_FEE', 'PAYOUT_FEE', 'FX_FEE'))"))
                .contains(normalized("ADD CONSTRAINT break_cause CHECK (cause IN (" + BreakCause.sqlValueList() + "))"))
                .contains(BreakCause.sqlRaisePairingRule());
        assertThat(sql).contains("'PAYOUT_FEE', 'BANK_CREDIT', 'BANK_DEBIT', 'BANK_FEE', 'FX_SOLD', 'FX_BOUGHT', 'FX_FEE'))")
                .as("rule_line_type: the FX lines appended to V010's list");
        assertThat(sql).contains("AND break_cause IN ('LATE_MATCH', 'CYCLE_MISMATCH', 'VALUE_DATE_DIFFERS'), false);");
    }

    @Test
    @DisplayName("the members are appended at the end, the fee priced and non-allocating, the causes raising exactly their types")
    void theMembers() {
        Set<ExpectationKind> kinds = EnumSet.allOf(ExpectationKind.class);
        kinds.removeAll(EnumSet.range(ExpectationKind.CARD_CAPTURE, ExpectationKind.REMITTANCE));
        assertThat(kinds).containsExactly(ExpectationKind.FX_SELL_LEG, ExpectationKind.FX_BUY_LEG);
        assertThat(ExternalLineType.fxVocabulary())
                .containsExactly(ExternalLineType.FX_SOLD, ExternalLineType.FX_BOUGHT, ExternalLineType.FX_FEE);
        assertThat(ExternalLineType.FX_FEE.allocating()).isFalse();
        assertThat(ExternalLineType.FX_SOLD.allocating()).isTrue();
        assertThat(ExternalLineType.FX_BOUGHT.allocating()).isTrue();
        assertThat(ExternalLineType.FX_FEE.originalKeyKind()).contains(KeyKind.COVER_REF);
        assertThat(BreakCause.FX_LEG_DIFFERS.raisesAs()).containsExactly(BreakType.AMOUNT_MISMATCH);
        assertThat(BreakCause.VALUE_DATE_DIFFERS.raisesAs()).containsExactly(BreakType.TIMING_DIFFERENCE);
        assertThat(BreakType.values()).as("no fifteenth break type").hasSize(14);
    }

    private static String migration() {
        try (InputStream in = ReconciliationV020MigrationTest.class.getClassLoader().getResourceAsStream(V020)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }
}
