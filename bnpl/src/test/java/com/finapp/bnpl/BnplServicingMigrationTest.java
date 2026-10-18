package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V008}'s text held to the code (`P12-TSK-015`; {@code BnplAcceptanceMigrationTest}'s idiom - hermetic, so it
 * runs on every build while the live ranks are {@code BnplBillingDatabaseTest}'s): the billing's uniques and its trigger's
 * rules (dated its due date, never early, oldest first, interest = the period's posted accruals, the principal rule), the
 * floor's accrual arm, the application's two occasions, its uniques, its next number, its floor ranks and its deferred
 * conservation, the one paid definition, the permit-only edge, the grants, every stamp after the locks.
 */
@DisplayName("bnpl V008 holds the billing's, the application's and the permit's ranks as the code declares them"
        + " (P12-TSK-015)")
class BnplServicingMigrationTest {

    private static final String V008 =
            "db/migration/bnpl/V008__instalment_billing_the_catch_up_application_and_the_servicing_permit.sql";

    @Test
    @DisplayName("one billing per instalment and per due date; one application per billing; one catch-up per number;"
            + " one accrual per obligation and date")
    void theArbitersArePresent() {
        assertThat(statements())
                .contains("ADD CONSTRAINT bnpl_billing_due_date_unique UNIQUE (obligation_id, value_date)")
                .contains("CONSTRAINT bnpl_credit_balance_application_billing_unique UNIQUE (billing_id)")
                .contains("CONSTRAINT bnpl_credit_balance_application_catch_up_unique UNIQUE (obligation_id,"
                        + " catch_up_sequence)")
                .contains("CONSTRAINT bnpl_credit_balance_application_entry_unique UNIQUE (journal_entry_id)")
                .contains("CONSTRAINT bnpl_interest_accrual_date_unique UNIQUE (obligation_id, accrual_date)")
                .contains("(billing_id IS NULL) = (catch_up_sequence IS NOT NULL) AND (catch_up_sequence IS NULL OR"
                        + " catch_up_sequence >= 1)");
    }

    @Test
    @DisplayName("the billing is dated its due date once it has come, oldest first, its interest the period's posted"
            + " accruals, its principal least(remaining, amount - interest), the last the remainder")
    void theBillingsRulesAreHeldForEveryWriter() {
        String trigger = body("bnpl.bnpl_billing_is_born_once()");
        assertThat(trigger)
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("IF NEW.value_date IS DISTINCT FROM instalment.due_date")
                .contains("OR instalment.due_date > bnpl.business_date_latest(obligation.servicing_zone) THEN")
                .contains("AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.instalment_id = i.id))")
                .contains("SELECT coalesce(sum(a.posted_minor), 0) INTO accrued FROM bnpl.bnpl_interest_accrual a")
                .contains("IF accrued_through IS NULL OR accrued_through < instalment.due_date - 1 THEN")
                .contains("IF NEW.interest_minor <> accrued THEN")
                .contains("expected := remaining;")
                .contains("expected := LEAST(remaining, GREATEST(0, instalment.amount_minor - NEW.interest_minor));")
                .contains("NEW.billed_at := clock_timestamp();");
        assertThat(statements()).contains("(principal_minor + interest_minor > 0) = (journal_entry_id IS NOT NULL)");
    }

    @Test
    @DisplayName("the floor: the later of the day after the latest accrual and the latest billing, never before the"
            + " purchase - and every application ranked against it")
    void theFloorAndItsRanks() {
        String floor = body("bnpl.principal_value_floor(obligation uuid)");
        assertThat(floor).contains("(SELECT o.purchase_date FROM bnpl.bnpl_obligation o WHERE o.id = obligation)")
                .contains("(SELECT max(b.value_date) FROM bnpl.bnpl_billing b WHERE b.obligation_id = obligation)")
                .contains("(SELECT max(a.accrual_date) + 1 FROM bnpl.bnpl_interest_accrual a WHERE a.obligation_id ="
                        + " obligation)");
        String application = body("bnpl.bnpl_credit_balance_application_is_born_once()");
        assertThat(application).contains("value_floor := bnpl.principal_value_floor(NEW.obligation_id);")
                .contains("IF NEW.value_date < value_floor")
                .contains("OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone),"
                        + " value_floor) THEN")
                .contains("ELSIF NEW.catch_up_sequence IS DISTINCT FROM 1 + coalesce(")
                .contains("FOR UPDATE;")
                .contains("NEW.created_at := clock_timestamp();");
    }

    @Test
    @DisplayName("an application conserves at commit - its rows sum to it, no billed component paid beyond what it"
            + " billed, every payer counted by one definition")
    void theApplicationConserves() {
        String sql = statements();
        for (String table : List.of("bnpl_credit_balance_application", "bnpl_credit_balance_allocation")) {
            assertThat(sql).contains("    AFTER INSERT ON bnpl." + table + "\n    DEFERRABLE INITIALLY DEFERRED\n"
                    + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.bnpl_credit_balance_application_conserves();");
        }
        assertThat(body("bnpl.bnpl_credit_balance_application_conserves()"))
                .contains("AND bnpl.billing_component_paid(a.billing_id, a.component)")
                .contains("> bnpl.billing_component_billed(a.billing_id, a.component)) THEN");
        assertThat(body("bnpl.billing_component_paid(billing uuid, paid_component text)"))
                .contains("FROM bnpl.bnpl_credit_balance_allocation a")
                .contains("FROM bnpl.bnpl_repayment_allocation r")
                .contains("r.target = 'DUE_ITEM'");
    }

    @Test
    @DisplayName("the obligation moves its permit alone while ACTIVE; the application role may move only that column, may"
            + " only read the accrual record, and never updates or deletes a fact")
    void thePermitAndTheGrants() {
        assertThat(body("bnpl.bnpl_obligation_permits_only_machine_edges()"))
                .contains("IF NEW.status = OLD.status AND OLD.status = 'ACTIVE'");
        String sql = statements();
        assertThat(sql).contains("GRANT UPDATE (next_servicing_at) ON bnpl.bnpl_obligation TO finapp_app;")
                .contains("GRANT SELECT ON bnpl.bnpl_interest_accrual TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_credit_balance_application TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_credit_balance_allocation TO finapp_app;")
                .doesNotContain("GRANT INSERT ON bnpl.bnpl_interest_accrual")
                .doesNotContainPattern("GRANT[^;]*(DELETE|UPDATE ON|UPDATE \\((?!next_servicing_at))");
    }

    @Test
    @DisplayName("V008 names no schema but bnpl's - no ledger table read by a bnpl trigger")
    void noOtherSchemaIsNamed() {
        assertThat(statements()).doesNotContain("ledger.").doesNotContain("lending.").doesNotContain("credit.");
    }

    private static String body(String signature) {
        String sql = statements();
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION " + signature);
        assertThat(start).as(signature).isNotNegative();
        int end = sql.indexOf("$$;", sql.indexOf("$$", start) + 2);
        return sql.substring(start, end);
    }

    private static String statements() {
        return migration().lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        try (InputStream in = BnplServicingMigrationTest.class.getClassLoader().getResourceAsStream(V008)) {
            assertThat(in).as(V008 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
