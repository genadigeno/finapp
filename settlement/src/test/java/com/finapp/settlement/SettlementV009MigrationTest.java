package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Settlement `V009`, read as text (`P8-TSK-022`): the readmission rule is ONE pair of functions
 * - what a readmission inherits, and who submitted its bytes - read by a trigger that binds
 * every writer on birth and on every move, and by the accept leg's claim
 * ({@code JdbcSettlementFileStore.ELIGIBLE}). The behaviour of each rank is proven against the
 * real schema by {@code ReadmissionDatabaseTest}.
 */
@DisplayName("settlement V009 - the readmission rule (P8-TSK-022)")
class SettlementV009MigrationTest {

    private static final String MIGRATION =
            "db/migration/settlement/V009__the_readmission_rule.sql";

    @Test
    @DisplayName("a readmission inherits from a pulled or attested original, recursively along"
            + " the chain, and a DECLINED original passes nothing on")
    void theInheritanceRule() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("CREATE OR REPLACE FUNCTION settlement.file_authenticates_readmission("
                        + "original_id UUID) RETURNS boolean LANGUAGE sql STABLE")
                .contains("CREATE OR REPLACE FUNCTION settlement.file_inherits_authentication("
                        + "readmission_id UUID) RETURNS boolean LANGUAGE sql STABLE")
                .as("a DECLINED file stops the walk and passes nothing on")
                .contains("f.rejection_code IS NOT DISTINCT FROM 'DECLINED'")
                .contains("AND NOT c.declined")
                .contains("SELECT NOT c.declined AND (c.received_via = 'PULL' OR c.attested)")
                .as("only a readmission's own original is walked through")
                .contains("WHERE c.received_via = 'READMISSION'")
                .contains("AND r.received_via = 'READMISSION'")
                .as("a hand-made cycle fails closed, never loops")
                .contains("AND c.depth < 1000");
    }

    @Test
    @DisplayName("every submitter along the chain - readmitters and the original's uploader -"
            + " is named, a pull's absent one excluded")
    void theSubmitters() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("CREATE OR REPLACE FUNCTION settlement.file_submitters(file_id UUID)"
                        + " RETURNS SETOF text LANGUAGE sql STABLE")
                .contains("SELECT DISTINCT c.received_by FROM chain c"
                        + " WHERE c.received_by IS NOT NULL;");
    }

    @Test
    @DisplayName("the trigger binds every writer on insert and update: an attester is none of"
            + " the submitters, and an uninheriting readmission is ACCEPTED only attested")
    void theTriggerBindsEveryWriter() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("BEFORE INSERT OR UPDATE ON settlement.file FOR EACH ROW"
                        + " EXECUTE FUNCTION settlement.file_readmission_is_authenticated();")
                .as("judged by the ORIGINAL's id, so a row being inserted is judged too")
                .contains("submitters := array_prepend(NEW.received_by,"
                        + " ARRAY(SELECT settlement.file_submitters(NEW.readmits_file_id)));")
                .contains("IF NEW.attested_by = ANY (submitters) THEN")
                .contains("IF NEW.status = 'ACCEPTED' AND NEW.attested_by IS NULL AND NOT"
                        + " settlement.file_authenticates_readmission(NEW.readmits_file_id)"
                        + " THEN")
                .as("nothing here widens a grant")
                .doesNotContain("GRANT ")
                .doesNotContain("SECURITY DEFINER");
    }

    private static String migration() {
        try (InputStream in =
                SettlementV009MigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
