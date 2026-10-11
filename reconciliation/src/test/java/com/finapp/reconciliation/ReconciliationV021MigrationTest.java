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
 * Reconciliation `V021`, read as text (`P9-TSK-014`): the corridor's two expectation kinds, appended
 * at the end, in every list that names a kind - and nothing else changes, no rule set is seeded.
 */
@DisplayName("reconciliation V021 - the corridor's expectation kinds (P9-TSK-014)")
class ReconciliationV021MigrationTest {

    private static final String V021 = "db/migration/reconciliation/V021__the_corridor_expectation_kinds.sql";

    /** The kinds as V021 left them - before V022 (`P11-TSK-004`) appended the capital contribution. */
    private static final Set<ExpectationKind> AS_OF_V021 =
            EnumSet.range(ExpectationKind.CARD_CAPTURE, ExpectationKind.CROSSBORDER_RETURN);

    @Test
    @DisplayName("each kind CHECK is the enum's whole list as of V021, the corridor's kinds appended last")
    void everyListIsTheEnum() {
        String sql = normalized(migration());
        String asOfV021 = ExpectationKind.sqlValueList(AS_OF_V021);
        assertThat(sql)
                .contains(normalized("ADD CONSTRAINT expectation_kind CHECK (kind IN (" + asOfV021 + "))"))
                .contains(normalized("ADD CONSTRAINT rule_expectation_kind CHECK (expectation_kind IS NULL OR expectation_kind IN ("
                        + asOfV021 + "))"))
                .contains(normalized("ADD CONSTRAINT rule_set_lag_kind CHECK (expectation_kind IN ("
                        + asOfV021 + "))"));
        Set<ExpectationKind> appended = EnumSet.copyOf(AS_OF_V021);
        appended.removeAll(EnumSet.range(ExpectationKind.CARD_CAPTURE, ExpectationKind.FX_BUY_LEG));
        assertThat(appended).containsExactly(ExpectationKind.CROSSBORDER_PAYOUT, ExpectationKind.CROSSBORDER_RETURN);
    }

    @Test
    @DisplayName("no other vocabulary, no seeded rule set, no grant and no table")
    void nothingElse() {
        String sql = normalized(migration());
        assertThat(sql)
                .doesNotContain("key_kind")
                .doesNotContain("line_type")
                .doesNotContain("break_cause")
                .doesNotContain("INSERT INTO")
                .doesNotContain("GRANT")
                .doesNotContain("CREATE TABLE");
    }

    private static String migration() {
        try (InputStream in = ReconciliationV021MigrationTest.class.getClassLoader().getResourceAsStream(V021)) {
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
