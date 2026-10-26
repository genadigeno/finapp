package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code kyc V011}'s text held to the code (`P13-TSK-022`, the owner's decision O1; the migration-test idiom, hermetic -
 * the live constraints and grants are {@code RescreeningDatabaseTest#theRanksBeneathTheDomain}'s, written and owed
 * under C5): the purpose is {@link CheckPurpose}'s vocabulary, a rescreen keyed and only a rescreen, a screening, one
 * question per key; onboarding's one-in-flight index over onboarding checks alone; the purpose and the key frozen by
 * the grant; no other schema.
 */
@DisplayName("kyc V011: a list update rescreens beside the decision, held to the code (P13-TSK-022)")
class KycRescreenMigrationTest {

    private static final String MIGRATION =
            "db/migration/kyc/V011__a_list_update_rescreens_beside_the_decision.sql";

    private static final Pattern ANOTHER_SCHEMA =
            Pattern.compile(
                    "\\b(risk|lending|credit|ledger|platform|checkout|merchant|identity|accounts|payments"
                            + "|bnpl|party|transfers|crossborder|settlement|reconciliation|fx|public)\\.[a-z_]+");

    private final String sql = collapsed(statements());

    @Test
    @DisplayName("the purpose is CheckPurpose's vocabulary, ONBOARDING by default - every earlier row onboarding")
    void thePurposeIsTheEnums() {
        assertThat(sql)
                .contains(
                        "ALTER TABLE kyc.verification_check ADD COLUMN purpose text NOT NULL DEFAULT"
                                + " 'ONBOARDING';")
                .contains(
                        "ADD CONSTRAINT verification_check_purpose_is_known CHECK (purpose IN ("
                                + CheckPurpose.sqlValueList()
                                + "))");
    }

    @Test
    @DisplayName("a rescreen is keyed and only a rescreen is, a screening, bounded, ONE QUESTION PER KEY - the arbiter"
            + " the store's ON CONFLICT names")
    void aRescreenIsKeyedOnce() {
        assertThat(sql)
                .contains(
                        "ADD CONSTRAINT verification_check_rescreen_is_keyed CHECK ((purpose = 'RESCREEN') ="
                                + " (rescreen_key IS NOT NULL))")
                .contains(
                        "ADD CONSTRAINT verification_check_rescreen_key_is_bounded CHECK (rescreen_key IS NULL"
                                + " OR char_length(rescreen_key) BETWEEN 1 AND "
                                + JdbcCheckStore.RESCREEN_KEY_MAX
                                + ")")
                .contains(
                        "ADD CONSTRAINT verification_check_rescreen_is_a_screening CHECK (purpose ="
                                + " 'ONBOARDING' OR check_type IN ('SANCTIONS', 'PEP'))")
                .contains("ADD CONSTRAINT verification_check_rescreen_once UNIQUE (rescreen_key);");
    }

    @Test
    @DisplayName("onboarding's one-in-flight index is re-made over onboarding checks alone - a rescreen in flight neither"
            + " refuses a run's request of its type nor is converged on by it")
    void theInFlightIndexHoldsOnboardingAlone() {
        assertThat(sql)
                .contains("DROP INDEX kyc.verification_check_one_in_flight_per_type;")
                .contains(
                        "CREATE UNIQUE INDEX verification_check_one_in_flight_per_type ON"
                                + " kyc.verification_check (case_id, check_type) WHERE status NOT IN ('CLEAR',"
                                + " 'HIT', 'INDETERMINATE') AND purpose = 'ONBOARDING';");
        assertThat(sql.indexOf("DROP INDEX kyc.verification_check_one_in_flight_per_type;"))
                .isLessThan(sql.indexOf("CREATE UNIQUE INDEX verification_check_one_in_flight_per_type"));
    }

    @Test
    @DisplayName("risk's screening-hit read is served in its own order (P13-TSK-022's review): per (case, type) the"
            + " decided checks newest first, both purposes - one probe per type, however many rescreens accumulate")
    void theLatestDecidedReadIsIndexed() {
        assertThat(sql)
                .contains(
                        "CREATE INDEX verification_check_decided_latest ON kyc.verification_check (case_id,"
                                + " check_type, requested_at DESC, status_changed_at DESC, id DESC) WHERE status IN"
                                + " ('CLEAR', 'HIT');");
    }

    @Test
    @DisplayName("the purpose and the key are frozen: V004's table-wide UPDATE narrowed to the machine's two columns;"
            + " no DELETE; no other schema named; no row written")
    void thePurposeAndTheKeyAreFrozen() {
        assertThat(sql)
                .contains("REVOKE UPDATE ON kyc.verification_check FROM finapp_app;")
                .contains("GRANT UPDATE (status, status_changed_at) ON kyc.verification_check TO finapp_app;")
                .doesNotContain("DELETE")
                .doesNotContain("INSERT INTO")
                .doesNotContain("UPDATE kyc.");
        assertThat(sql.indexOf("REVOKE UPDATE")).isLessThan(sql.indexOf("GRANT UPDATE"));
        String unquoted = statements().replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).as("no other schema named").isFalse();
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements() {
        try (InputStream in =
                KycRescreenMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
