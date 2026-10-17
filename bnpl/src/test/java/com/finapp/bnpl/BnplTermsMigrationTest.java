package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.EngineVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V002}'s text held to the code (`P12-TSK-004`; the migration-test idiom - {@code LedgerAccountMigrationTest},
 * {@code BnplMigrationTest}'s statement reading - hermetic, so it runs on every build while the live ranks are
 * {@code BnplTermsDatabaseTest}'s): the machine's value lists are the enums', the four-eyes {@code CHECK} and the two
 * partial uniques are present, pay-in-4's rate is held to zero, the term set and the history are INSERT-only and the
 * machine row updates only its decision columns, nothing is seeded, every decision stamp is the instant after the lock
 * ({@code clock_timestamp()}, lending {@code V030}'s repair from the first row), the engines named are this build's, no
 * other schema is named, and advisory namespace {@code 12} is the administration's, pinned.
 */
@DisplayName("bnpl V002 holds the terms machine's ranks as the code declares them (P12-TSK-004)")
class BnplTermsMigrationTest {

    private static final String RESOURCE = "db/migration/bnpl/V002__bnpl_terms_as_versioned_data.sql";

    @Test
    @DisplayName("the status and product CHECKs list exactly the enums' members")
    void theValueListsAreTheEnums() {
        String sql = statements();
        assertThat(sql).contains("CHECK (status IN (" + BnplTermsVersionStatus.sqlValueList() + "))");
        String products = Arrays.stream(BnplProduct.values()).map(p -> "'" + p.name() + "'")
                .collect(Collectors.joining(", "));
        assertThat(sql).contains("CHECK (product IN (" + products + "))");
    }

    @Test
    @DisplayName("four eyes by CHECK for every writer, and one PROPOSED and one ACTIVE per product by partial uniques")
    void fourEyesAndOnePerProduct() {
        String sql = statements();
        assertThat(sql).contains("CONSTRAINT bnpl_terms_version_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR"
                + " decided_by <> proposed_by)");
        assertThat(sql).contains("CREATE UNIQUE INDEX bnpl_terms_one_active ON bnpl.bnpl_terms_version (product) WHERE"
                + " status = 'ACTIVE'");
        assertThat(sql).contains("CREATE UNIQUE INDEX bnpl_terms_one_proposed ON bnpl.bnpl_terms_version (product) WHERE"
                + " status = 'PROPOSED'");
        assertThat(sql).contains("DEFERRABLE INITIALLY DEFERRED").contains("retired with no later ACTIVE successor");
    }

    @Test
    @DisplayName("a pay-in-4 version holds no rate: the kind CHECK pins its rate to '0', its plan to 4 every 14 days on its"
            + " own engine, and gives it no day count or accrual")
    void aPayIn4VersionHoldsNoRate() {
        String kind = constraint("bnpl_terms_kind_coherent");
        String payIn4 = kind.substring(kind.indexOf("product = 'BNPL_PAY_IN_4'"), kind.indexOf("OR (product = 'BNPL_PAY_IN_N'"));
        assertThat(payIn4).contains("nominal_annual_rate = '0'", "instalment_count = 4", "interval_days = 14",
                "instalment_rounding = 'DOWN'", "day_count IS NULL", "accrual_engine IS NULL",
                "schedule_engine = '" + BnplEngine.BNPL_SCHEDULE_ENGINE_V1.name() + "'");
        String payInN = kind.substring(kind.indexOf("OR (product = 'BNPL_PAY_IN_N'"));
        assertThat(payInN).contains("allowed_terms <@ ARRAY[3, 6, 12]", "min_first_period_days BETWEEN 1 AND 28",
                "day_count = 'ACT_365F'", "schedule_engine = '" + EngineVersion.SCHEDULE_ENGINE_V1.name() + "'",
                "accrual_engine = '" + EngineVersion.ACCRUAL_ENGINE_V1.name() + "'");
        assertThat(constraint("bnpl_terms_shared_engines_implemented")).contains(EngineVersion.ALLOCATION_ENGINE_V1.name(),
                BnplEngine.REFUND_ALLOCATION_ENGINE_V1.name());
        assertThat(constraint("bnpl_terms_windows_bounded")).contains("offer_validity_minutes BETWEEN 1 AND "
                + BnplTerms.MAXIMUM_OFFER_VALIDITY_MINUTES);
    }

    @Test
    @DisplayName("the term set and the history are INSERT-only; the machine row updates its decision columns alone;"
            + " nothing is seeded; no default privilege")
    void theTermSetIsNeverUpdatedOrDeletedByAnyRole() {
        String sql = statements();
        assertThat(grants(sql)).containsExactlyInAnyOrder(
                "GRANT SELECT, INSERT ON bnpl.bnpl_terms_version TO finapp_app",
                "GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, effective_from,"
                        + " effective_to) ON bnpl.bnpl_terms_version TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_terms TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_terms_version_event TO finapp_app");
        assertThat(sql).doesNotContain("INSERT INTO bnpl.").doesNotContain("ALTER DEFAULT PRIVILEGES");
        assertThat(sql).contains("immutable from insert, for every role")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_terms\n");
        assertThat(sql).contains("a BNPL terms version is born PROPOSED and undecided");
    }

    @Test
    @DisplayName("every decision stamp is clock_timestamp() - the instant after the lock - never the transaction's start")
    void theDecisionIsStampedAfterTheLock() {
        String trigger = statements();
        assertThat(trigger).contains("NEW.decided_at := clock_timestamp();")
                .contains("NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(),")
                .doesNotContain("NEW.decided_at := transaction_timestamp()");
    }

    @Test
    @DisplayName("the migration names no other schema - a precondition elsewhere is the domain's, through app's port")
    void namesNoOtherSchema() {
        Matcher schemas = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant|identity|accounts"
                + "|payments|public)\\.").matcher(statements());
        assertThat(schemas.results().map(java.util.regex.MatchResult::group).toList()).isEmpty();
        assertThat(statements()).contains("bnpl.holds_instrument_shape(proposal_reason)");
    }

    @Test
    @DisplayName("advisory namespace 12 is the administration's, in its one statement (DISTRIBUTED_EXECUTION.md section 3)")
    void namespaceTwelveIsPinned() {
        assertThat(JdbcBnplTermsStore.NAMESPACE).isEqualTo(12);
        assertThat(JdbcBnplTermsStore.LOCK_SQL).isEqualTo("SELECT pg_advisory_xact_lock(12, hashtext(?))");
    }

    // -----------------------------------------------------------------

    private static String constraint(String name) {
        String sql = statements();
        int start = sql.indexOf("CONSTRAINT " + name + " CHECK");
        assertThat(start).as(name).isNotNegative();
        int next = sql.indexOf("CONSTRAINT ", start + 1);
        return sql.substring(start, next < 0 ? sql.length() : next);
    }

    private static java.util.List<String> grants(String sql) {
        return Arrays.stream(sql.split(";"))
                .map(statement -> statement.strip().replaceAll("\\s+", " "))
                .filter(statement -> statement.startsWith("GRANT") || statement.startsWith("REVOKE"))
                .toList();
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements() {
        return migration().lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        try (InputStream in = BnplTermsMigrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
