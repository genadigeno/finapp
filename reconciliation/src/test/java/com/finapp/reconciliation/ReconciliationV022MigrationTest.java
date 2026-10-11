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
 * Reconciliation `V022`, read as text (`P11-TSK-004`, ADR-0096 section 4): the capital contribution's kind appended to
 * every list that names a kind, the posting rule and the never-aged rule generated from {@link ExpectationKind}, the
 * decision's two new values in their whole lists, and the recognition's every-writer ranks.
 */
@DisplayName("reconciliation V022 - lending capital recognition (P11-TSK-004)")
class ReconciliationV022MigrationTest {

    private static final String V022 = "db/migration/reconciliation/V022__lending_capital_recognition.sql";

    @Test
    @DisplayName("each kind CHECK is the enum's whole list, the capital contribution appended last")
    void everyKindListIsTheEnum() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT expectation_kind CHECK (kind IN (" + ExpectationKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT rule_expectation_kind CHECK (expectation_kind IS NULL OR expectation_kind IN ("
                        + ExpectationKind.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT rule_set_lag_kind CHECK (expectation_kind IN ("
                        + ExpectationKind.sqlValueList() + "))"));
        Set<ExpectationKind> appended = EnumSet.allOf(ExpectationKind.class);
        appended.removeAll(EnumSet.range(ExpectationKind.CARD_CAPTURE, ExpectationKind.CROSSBORDER_RETURN));
        assertThat(appended).containsExactly(ExpectationKind.LENDING_CAPITAL_CONTRIBUTION);
    }

    @Test
    @DisplayName("the posting rule and the never-aged rule are the enum's: the two statement kinds record no posting,"
            + " only the capital contribution is never aged")
    void thePostingAndAgeingRulesAreTheEnums() {
        String sql = normalized(migration());
        String noPosting = ExpectationKind.sqlValueList(
                EnumSet.copyOf(java.util.Arrays.stream(ExpectationKind.values())
                        .filter(kind -> !kind.recordsAPosting()).toList()));
        assertThat(noPosting).isEqualTo("'REMITTANCE', 'LENDING_CAPITAL_CONTRIBUTION'");
        assertThat(sql)
                .contains(normalized("DROP CONSTRAINT expectation_remittance_has_no_entry"))
                .contains(normalized("ADD CONSTRAINT expectation_records_its_posting CHECK ((kind IN (" + noPosting
                        + ")) = (journal_entry_id IS NULL))"));
        assertThat(ExpectationKind.sqlNotAgedList()).isEqualTo("'LENDING_CAPITAL_CONTRIBUTION'");
        assertThat(sql).contains(normalized("ADD CONSTRAINT expectation_capital_is_never_aged CHECK ("
                + "kind <> 'LENDING_CAPITAL_CONTRIBUTION' OR overdue_since IS NULL)"));
    }

    @Test
    @DisplayName("the decision's origin and verdict lists are the enums' whole lists, and a recognition is a capital"
            + " recognition")
    void theDecisionVocabularyIsTheEnums() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT match_decision_origin CHECK (origin IN ("
                        + DecisionOrigin.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT match_decision_verdict CHECK (verdict IS NULL OR verdict IN ("
                        + DecisionVerdict.sqlValueList() + "))"))
                .contains(normalized("ADD CONSTRAINT match_decision_recognition_is_capital CHECK ("
                        + "(origin = 'RECOGNITION') = (verdict = 'CAPITAL_RECOGNISED'))"));
        assertThat(DecisionVerdict.CAPITAL_RECOGNISED.engine()).isEqualTo(DecisionVerdict.Engine.RECOGNITION);
    }

    @Test
    @DisplayName("the recognitions are born once per contribution and per line, append-only, granted SELECT and"
            + " INSERT only; a contribution is allocated by a recognition alone")
    void theRecognitionRanks() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("CONSTRAINT capital_recognition_once_per_contribution UNIQUE (expectation_id)")
                .contains("CONSTRAINT capital_recognition_once_per_line UNIQUE (external_item_id)")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.capital_recognition")
                .contains("GRANT SELECT, INSERT ON reconciliation.capital_recognition TO finapp_app;")
                .contains("BEFORE INSERT ON reconciliation.allocation")
                .contains("d.origin = 'RECOGNITION'");
        assertThat(sql).doesNotContain("GRANT UPDATE").doesNotContain("GRANT DELETE").doesNotContain("INSERT INTO");
    }

    private static String migration() {
        try (InputStream in = ReconciliationV022MigrationTest.class.getClassLoader().getResourceAsStream(V022)) {
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
