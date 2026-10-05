package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V010` reconciled against the vocabulary that generates it (`P8-TSK-018`, ADR-0068 §§7-8): the
 * item's line-type and key-kind {@code CHECK}s are the enums' whole lists, the payout members
 * exactly what `V010` adds to `V009`'s scheme vocabulary; the payout fee joins the rule and fee
 * schedule vocabularies; and the payout rule set v1's missing terms are completed in place ONLY
 * behind the guard that refuses once any run or decision has named that rule set - the recorded
 * deviation from "a change is a NEW version", safe exactly because no stored decision can be
 * explained differently.
 */
@DisplayName("reconciliation V010 reconciliation (P8-TSK-018)")
class ReconciliationV010MigrationTest {

    private static final String V002 =
            "db/migration/reconciliation/V002__expectation_register_and_rule_set_v1.sql";
    private static final String V010 =
            "db/migration/reconciliation/V010__payout_items_and_the_payout_fee_terms.sql";
    private static final String PAYOUT_RULE_SET = "01a0e2bd-8300-7003-8000-000000000003";

    /** The vocabularies as V010 wrote them - `V020` (`P9-TSK-011`) appended the FX provider's. */
    private static final Set<ExternalLineType> LINES_AS_OF_V010 =
            EnumSet.range(ExternalLineType.CAPTURE, ExternalLineType.PAYOUT_FEE);

    private static final Set<ItemKeyKind> KEYS_AS_OF_V010 =
            EnumSet.range(ItemKeyKind.PSP_CAPTURE_REF, ItemKeyKind.PAYOUT_PROVIDER_REF);

    @Test
    @DisplayName("the item's line-type and key-kind CHECKs are the enums' whole lists - the payout"
            + " members and reference exactly what V010 adds to V009's scheme vocabulary")
    void thePayoutVocabularyIsTheEnums() {
        String sql = normalized(migration(V010));
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT external_item_line_type CHECK (line_type IN ("
                                + ExternalLineType.sqlValueList(LINES_AS_OF_V010) + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN ("
                                + ItemKeyKind.sqlValueList(KEYS_AS_OF_V010) + "))"));
        Set<ExternalLineType> payoutMembers = EnumSet.copyOf(LINES_AS_OF_V010);
        payoutMembers.removeAll(ExternalLineType.schemeVocabulary());
        assertThat(payoutMembers)
                .containsExactly(
                        ExternalLineType.PAYOUT_EXECUTED,
                        ExternalLineType.PAYOUT_RETURNED,
                        ExternalLineType.PAYOUT_FEE);
        Set<ItemKeyKind> payoutKeys = EnumSet.copyOf(KEYS_AS_OF_V010);
        payoutKeys.removeAll(ItemKeyKind.schemeVocabulary());
        assertThat(payoutKeys).containsExactly(ItemKeyKind.PAYOUT_PROVIDER_REF);

        assertThat(ExternalLineType.PAYOUT_FEE.allocating())
                .as("the provider's fee is judged, never allocated - its effect is the recognition")
                .isFalse();
        assertThat(ExternalLineType.PAYOUT_EXECUTED.allocating()).isTrue();
        assertThat(ExternalLineType.PAYOUT_RETURNED.allocating()).isTrue();
        assertThat(ExternalLineType.PAYOUT_FEE.originalKeyKind())
                .as("a payout fee's original is a payout, reached by the provider's reference")
                .contains(KeyKind.PAYOUT_PROVIDER_REF);
        assertThat(ExternalLineType.PAYOUT_EXECUTED.originalKeyKind()).isEmpty();
        assertThat(ExternalLineType.PAYOUT_RETURNED.originalKeyKind()).isEmpty();
    }

    @Test
    @DisplayName("the payout fee joins the rule and fee schedule vocabularies beside the PSP's,"
            + " the scheme's and the bank's")
    void thePayoutFeeJoinsTheRuleSetVocabularies() {
        String sql = normalized(migration(V010));
        assertThat(sql)
                .contains("ADD CONSTRAINT rule_line_type CHECK (line_type IN (")
                .contains("'PAYOUT_EXECUTED', 'PAYOUT_RETURNED', 'PAYOUT_FEE',")
                .contains(normalized(
                        "ADD CONSTRAINT provider_fee_line_type CHECK (line_type IN ("
                                + "'PROCESSING_FEE', 'SCHEME_FEE', 'BANK_FEE', 'PAYOUT_FEE'))"));
    }

    @Test
    @DisplayName("the payout rule set's terms are completed in place only behind the guard - no"
            + " run and no decision may ever have named it - and exactly the fee rule and the"
            + " flat schedule are added: no tolerance, no other rule")
    void theSeedIsCompletedOnlyBehindTheGuard() {
        String sql = normalized(migration(V010));
        int guard = sql.indexOf("DO $$");
        int ruleInsert = sql.indexOf("INSERT INTO reconciliation.rule (");
        int scheduleInsert = sql.indexOf("INSERT INTO reconciliation.provider_fee_schedule");
        assertThat(guard).as("the guard exists").isNotNegative();
        assertThat(ruleInsert).as("the guard precedes the rule").isGreaterThan(guard);
        assertThat(scheduleInsert).as("the guard precedes the schedule").isGreaterThan(guard);
        String block = sql.substring(guard, sql.indexOf("$$;", guard));
        assertThat(block)
                .contains("FROM reconciliation.reconciliation_batch WHERE rule_set_id = '"
                        + PAYOUT_RULE_SET + "'")
                .contains("FROM reconciliation.match_decision WHERE rule_set_id = '"
                        + PAYOUT_RULE_SET + "'")
                .contains("RAISE EXCEPTION");
        assertThat(sql)
                .contains("('" + PAYOUT_RULE_SET
                        + "', 5, 'PAYOUT_FEE', NULL, NULL, 'CHECK', false, 48);")
                .contains("('" + PAYOUT_RULE_SET
                        + "', 'PAYOUT_FEE', 'EUR', 0.000000, 25, 2, 'HALF_UP')")
                .contains("('" + PAYOUT_RULE_SET
                        + "', 'PAYOUT_FEE', 'GBP', 0.000000, 25, 2, 'HALF_UP')")
                .contains("('" + PAYOUT_RULE_SET
                        + "', 'PAYOUT_FEE', 'USD', 0.000000, 25, 2, 'HALF_UP');")
                .as("an absent tolerance reads zero - none is seeded")
                .doesNotContain("INSERT INTO reconciliation.tolerance")
                .doesNotContain("GRANT");
        String v002 = normalized(migration(V002));
        assertThat(v002)
                .as("priority 5 was free in the payout rule set v1, and its return rules stay"
                        + " operation-anchored")
                .doesNotContain("('" + PAYOUT_RULE_SET + "', 5,")
                .contains("('" + PAYOUT_RULE_SET
                        + "', 3, 'PAYOUT_RETURNED', 'PAYOUT_PROVIDER_REF', 'PAYOUT_RETURN',"
                        + " 'ONE_TO_ONE', true, 72)")
                .contains("('" + PAYOUT_RULE_SET
                        + "', 4, 'PAYOUT_RETURNED', 'OUR_REF', 'PAYOUT_RETURN', 'ONE_TO_ONE',"
                        + " true, 72)");
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration(String path) {
        try (InputStream migration =
                ReconciliationV010MigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + path);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
