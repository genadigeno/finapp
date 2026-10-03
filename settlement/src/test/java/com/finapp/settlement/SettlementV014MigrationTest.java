package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V014`, read as text (the Phase 8 -> 9 transition's re-gate, NEW-SEC-1): a
 * repudiated batch's file passes NOTHING on - `V009`'s walk re-stated whole with the one bar an
 * approved repudiation is, exactly the `DECLINED` rule - so reinstating a repudiated batch takes
 * two people again, the readmitter and an attester distinct from every submitter. The behaviour
 * of each rank is proven against the real schema by {@code BatchRepudiationDatabaseTest} and
 * {@code ReadmissionDatabaseTest}.
 */
@DisplayName("settlement V014 - a repudiated batch's file passes nothing on (NEW-SEC-1)")
class SettlementV014MigrationTest {

    private static final String MIGRATION =
            "db/migration/settlement/V014__a_repudiated_batch_passes_nothing_on.sql";

    @Test
    @DisplayName("the walk is re-stated whole: a DECLINED file or one whose batch is REPUDIATED"
            + " bars the chain, and the rest of V009's rule stands as it was")
    void theBarredWalk() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("CREATE OR REPLACE FUNCTION settlement.file_authenticates_readmission("
                        + "original_id UUID) RETURNS boolean LANGUAGE sql STABLE")
                .as("a DECLINED file still stops the walk and passes nothing on")
                .contains("f.rejection_code IS NOT DISTINCT FROM 'DECLINED'")
                .as("a file whose batch is REPUDIATED passes nothing on (NEW-SEC-1)")
                .contains("OR EXISTS (SELECT 1 FROM settlement.batch b"
                        + " WHERE b.file_id = f.id AND b.status = 'REPUDIATED')")
                .contains("AND NOT c.barred")
                .contains("SELECT NOT c.barred AND (c.received_via = 'PULL' OR c.attested)")
                .as("only a readmission's own original is walked through")
                .contains("WHERE c.received_via = 'READMISSION'")
                .as("a hand-made cycle fails closed, never loops")
                .contains("AND c.depth < 1000");
    }

    @Test
    @DisplayName("nothing else moves: no grant widened, no trigger replaced, no second function"
            + " re-stated - V009's trigger and ranks keep reading the one walk")
    void onlyTheWalkIsReStated() {
        String sql = normalized(migration());
        assertThat(sql)
                .doesNotContain("GRANT ")
                .doesNotContain("SECURITY DEFINER")
                .doesNotContain("CREATE TRIGGER")
                .doesNotContain("CREATE OR REPLACE FUNCTION settlement.file_submitters")
                .doesNotContain(
                        "CREATE OR REPLACE FUNCTION settlement.file_readmission_is_authenticated");
    }

    private static String migration() {
        try (InputStream in =
                SettlementV014MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Statements only, whitespace collapsed - so a fragment matches however the SQL wraps. */
    private static String normalized(String sql) {
        return sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .replaceAll("\\s+", " ")
                .replace("( ", "(")
                .replace(" )", ")");
    }
}
