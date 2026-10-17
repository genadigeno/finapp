package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V003}'s text held to the code (`P12-TSK-010`; {@code BnplTermsMigrationTest}'s idiom - hermetic, so it runs
 * on every build while the live ranks are {@code BnplApplicationStoreDatabaseTest}'s): the machine's value lists are the
 * enums', the session's and the party-and-product's partial uniques cover exactly the open states the store's
 * {@code ON CONFLICT DO NOTHING} relies on, {@code UNIQUE (decision_request_id)} is there, no column carries money, the
 * machine row updates only its status, closure reason and permit, the history is INSERT-only, every stamp is the instant
 * after the act's locks ({@code clock_timestamp()}), the deferred never-rests-{@code SUBMITTED} trigger exists, and no
 * other schema is named.
 */
@DisplayName("bnpl V003 holds the application machine's ranks as the code declares them (P12-TSK-010)")
class BnplApplicationMigrationTest {

    private static final String RESOURCE = "db/migration/bnpl/V003__the_bnpl_application.sql";

    @Test
    @DisplayName("the status, product, plan and closure-reason CHECKs list exactly the enums' members")
    void theValueListsAreTheEnums() {
        assertThat(constraint("bnpl_application_status_is_known")).contains(list(BnplApplicationStatus.values()));
        assertThat(constraint("bnpl_application_product_is_offered")).contains(list(BnplProduct.values()));
        assertThat(constraint("bnpl_application_closure_reason_is_known"))
                .contains(list(BnplApplicationClosureReason.values()));
        String plans = constraint("bnpl_application_plan_of_its_product");
        for (BnplProduct product : BnplProduct.values()) {
            String own = "(product = '" + product.name() + "' AND plan "
                    + (product.plans().size() == 1 ? "= '" + product.plans().get(0).name() + "'"
                            : "IN (" + list(product.plans().toArray(BnplPlan[]::new)) + ")");
            assertThat(plans).as(product.name()).contains(own);
        }
        assertThat(constraint("bnpl_application_event_edge")).contains("to_status IN ('OFFERED', 'DECLINED',"
                + " 'CLOSED_UNDECIDED', 'WITHDRAWN')");
    }

    @Test
    @DisplayName("one open application per session (SUBMITTED, AWAITING_DECISION, OFFERED) and per party and product"
            + " (SUBMITTED, AWAITING_DECISION), one per credit request - the arbiters the store's insert meets")
    void theArbitersArePresent() {
        String sql = statements();
        assertThat(sql).contains("CREATE UNIQUE INDEX bnpl_application_one_open_per_session ON bnpl.bnpl_application"
                + " (checkout_session_id)\n    WHERE status IN ('SUBMITTED', 'AWAITING_DECISION', 'OFFERED');");
        assertThat(sql).contains("CREATE UNIQUE INDEX bnpl_application_one_open_per_party_product ON"
                + " bnpl.bnpl_application (party_id, product)\n    WHERE status IN ('SUBMITTED', 'AWAITING_DECISION');");
        assertThat(sql).contains("CONSTRAINT bnpl_application_decision_request_unique UNIQUE (decision_request_id)");
        assertThat(sql).contains("DEFERRABLE INITIALLY DEFERRED").contains("committed SUBMITTED");
    }

    @Test
    @DisplayName("no money column - the amount is the session's gross and credit's request's - and the machine row moves"
            + " only its status, closure reason and permit; the history is INSERT-only; never DELETE")
    void noMoneyAndTheGrantsAreColumnScoped() {
        assertThat(statements()).doesNotContain("_minor").doesNotContain("currency");
        assertThat(grants(statements())).containsExactly(
                "GRANT SELECT, INSERT ON bnpl.bnpl_application TO finapp_app",
                "GRANT UPDATE (status, closure_reason, next_step_at) ON bnpl.bnpl_application TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_application_event TO finapp_app");
    }

    @Test
    @DisplayName("every stamp is clock_timestamp() - the instant after the act's locks - never the transaction's start,"
            + " and the closure is clamped forward of the submission")
    void everyStampIsAfterTheLocks() {
        String sql = statements();
        assertThat(sql).contains("NEW.submitted_at := clock_timestamp();")
                .contains("ELSE GREATEST(clock_timestamp(), OLD.submitted_at) END;")
                .contains("NEW.occurred_at := clock_timestamp();")
                .doesNotContain("transaction_timestamp()")
                .doesNotContain("now()");
    }

    @Test
    @DisplayName("the identity is frozen, only the machine's edges move it, and a permit moves only while awaiting")
    void theEdgeTriggerHoldsTheMachine() {
        String sql = statements();
        assertThat(sql).contains("(OLD.status = 'SUBMITTED' AND NEW.status = 'AWAITING_DECISION')")
                .contains("AND NEW.status IN ('OFFERED', 'DECLINED', 'CLOSED_UNDECIDED', 'WITHDRAWN')")
                .contains("identity and choices are frozen from birth")
                .contains("only its permit, and only while AWAITING_DECISION")
                .contains("records its product''s ACTIVE terms version")
                .contains("plan is one its terms version offers");
    }

    @Test
    @DisplayName("the migration names no other schema - the session, the merchant and credit's request are the domain's,"
            + " through app's ports")
    void namesNoOtherSchema() {
        Matcher schemas = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant|identity|accounts"
                + "|payments|public)\\.").matcher(statements());
        assertThat(schemas.results().map(MatchResult::group).toList()).isEmpty();
    }

    // -----------------------------------------------------------------

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

    private static String constraint(String name) {
        String sql = statements();
        int start = sql.indexOf("CONSTRAINT " + name + " CHECK");
        assertThat(start).as(name).isNotNegative();
        int next = sql.indexOf("CONSTRAINT ", start + 1);
        int end = sql.indexOf(");\n", start);
        int stop = next < 0 ? end : Math.min(next, end < 0 ? sql.length() : end);
        return sql.substring(start, stop < 0 ? sql.length() : stop).replaceAll("\\s+", " ");
    }

    private static List<String> grants(String sql) {
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
        try (InputStream in = BnplApplicationMigrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
