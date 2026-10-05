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
 * `V011` reconciled against the vocabulary that generates it (`P8-TSK-020`, ADR-0070 §§2 and 8):
 * the owner's suspense-item subject made deferrable - INITIALLY IMMEDIATE, so every other writer
 * keeps immediate checking - and the break's cause {@code CHECK} and raise-time pairing regenerated
 * whole, the one member `V011` adds exactly {@link BreakCause#EXECUTION_ALREADY_EXPLAINED}, raising
 * {@code DUPLICATE_EXTERNAL} alone.
 */
@DisplayName("reconciliation V011 reconciliation (P8-TSK-020)")
class ReconciliationV011MigrationTest {

    private static final String V011 =
            "db/migration/reconciliation/V011__unmatched_confirmations_join_suspense.sql";

    @Test
    @DisplayName("the owner's suspense-item subject is deferrable, initially immediate - never"
            + " initially deferred, which would move every writer's check to commit")
    void theSubjectKeyDefersOnlyWhenAsked() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("ALTER TABLE reconciliation.break ALTER CONSTRAINT"
                        + " break_suspense_item_fk DEFERRABLE INITIALLY IMMEDIATE;")
                .doesNotContain("INITIALLY DEFERRED")
                .as("no other constraint is loosened")
                .doesNotContain("suspense_item_break_fk");
    }

    /** The causes as V011 wrote them - `V020` (`P9-TSK-011`) appended the FX legs' two. */
    private static final Set<BreakCause> AS_OF_V011 =
            EnumSet.range(BreakCause.EXPECTATION_OVERDUE, BreakCause.EXECUTION_ALREADY_EXPLAINED);

    @Test
    @DisplayName("the cause CHECK and the raise-time pairing are the enum's whole lists, and V011"
            + " adds exactly EXECUTION_ALREADY_EXPLAINED, raising DUPLICATE_EXTERNAL alone")
    void theCauseVocabularyIsGenerated() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT break_cause CHECK (cause IN ("
                                + BreakCause.sqlValueList(AS_OF_V011) + "))"))
                .contains(BreakCause.sqlRaisePairingRule(AS_OF_V011));
        Set<BreakCause> added = EnumSet.copyOf(AS_OF_V011);
        added.removeAll(BreakCause.v004Vocabulary());
        assertThat(added).containsExactly(BreakCause.EXECUTION_ALREADY_EXPLAINED);
        assertThat(BreakCause.EXECUTION_ALREADY_EXPLAINED.raisesAs())
                .containsExactly(BreakType.DUPLICATE_EXTERNAL);
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration() {
        try (InputStream migration =
                ReconciliationV011MigrationTest.class.getClassLoader().getResourceAsStream(V011)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V011);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + V011, e);
        }
    }
}
