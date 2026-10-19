package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V014}'s text held to the code (`P12-TSK-022`; {@code BnplRefundMigrationTest}'s idiom - hermetic, so it runs
 * on every build while the live ranks are {@code BnplPayoffDatabaseTest}'s): the quote's uniques and its amount {@code CHECK},
 * the execution's two uniques, its date and value-date ranks and its deferred tie to the closure, the billing's early arm
 * beside the execution (and the coherence {@code CHECK} it needs), the floor's payoff arm, the {@code PAYOFF} repayment and
 * the {@code PAID_OFF_EARLY} closure only beside the execution, the grants, and every stamp after the locks.
 */
@DisplayName("bnpl V014 holds the payoff's quote, execution and admitted facts as the code declares them (P12-TSK-022)")
class BnplPayoffMigrationTest {

    private static final String V014 = "db/migration/bnpl/V014__early_payoff_quote_execution_and_closure.sql";

    @Test
    @DisplayName("the quote: one per obligation and key, the amount exactly its parts, interest-free without an engine,"
            + " made on the database clock's date and good through at most the pinned horizon, stamped after the lock")
    void theQuote() {
        assertThat(statements())
                .contains("CONSTRAINT bnpl_payoff_quote_key_unique UNIQUE (obligation_id, request_key)")
                .contains("amount_minor = principal_minor + principal_due_minor + interest_accrued_minor + interest_due_minor\n"
                        + "                       + fees_due_minor - credit_balance_minor + projected_interest_minor),")
                .contains("good_through >= business_date AND projected_days BETWEEN 0 AND good_through - business_date")
                .contains("accrual_engine IS NOT NULL\n        OR (interest_accrued_minor = 0 AND interest_due_minor = 0");
        assertThat(body("bnpl.bnpl_payoff_quote_is_born_once()"))
                .contains("WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("obligation.status <> 'ACTIVE'")
                .contains("OR NEW.good_through > NEW.business_date + horizon THEN")
                .contains("NEW.created_at := clock_timestamp();");
    }

    @Test
    @DisplayName("the execution: once per quote and per obligation, on its quote's good-through date which is the database"
            + " clock's, valued max(G, floor) read at its birth, and at commit its obligation CLOSED PAID_OFF_EARLY")
    void theExecution() {
        assertThat(statements())
                .contains("CONSTRAINT bnpl_payoff_execution_quote_unique UNIQUE (quote_id)")
                .contains("CONSTRAINT bnpl_payoff_execution_obligation_unique UNIQUE (obligation_id)")
                .contains("FOREIGN KEY (quote_id, obligation_id)\n        REFERENCES bnpl.bnpl_payoff_quote (id, obligation_id)")
                .contains("    AFTER INSERT ON bnpl.bnpl_payoff_execution\n    DEFERRABLE INITIALLY DEFERRED\n"
                        + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.bnpl_payoff_execution_closes_its_obligation();");
        assertThat(body("bnpl.bnpl_payoff_execution_is_born_once()"))
                .contains("IF NEW.business_date <> quote.good_through")
                .contains("OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN")
                .contains("IF NEW.value_date <> GREATEST(NEW.business_date, bnpl.principal_value_floor(NEW.obligation_id)) THEN")
                .contains("NEW.executed_at := clock_timestamp();");
        assertThat(body("bnpl.bnpl_payoff_execution_closes_its_obligation()"))
                .contains("o.status = 'CLOSED' AND o.closure_reason = 'PAID_OFF_EARLY'");
    }

    @Test
    @DisplayName("the billing is early only beside the obligation's execution, waits for the accruals through G - 1 only,"
            + " is made on the payoff's date and names the execution - and the coherence CHECK admits exactly that")
    void theEarlyBilling() {
        String trigger = body("bnpl.bnpl_billing_is_born_once()");
        assertThat(trigger)
                .contains("SELECT * INTO payoff FROM bnpl.bnpl_payoff_execution e WHERE e.obligation_id = NEW.obligation_id;")
                .contains("OR (instalment.due_date > bnpl.business_date_latest(obligation.servicing_zone) AND payoff.id IS NULL) THEN")
                .contains("ELSE LEAST(instalment.due_date, payoff.business_date) END;")
                .contains("IF reach > period_from AND (accrued_through IS NULL OR accrued_through < reach - 1) THEN")
                .contains("NEW.payoff_execution_id := payoff.id;")
                .contains("NEW.billed_at := clock_timestamp();");
        assertThat(statements()).contains("AND (business_date >= value_date OR payoff_execution_id IS NOT NULL)");
    }

    @Test
    @DisplayName("the floor counts a payoff's billing at most at the payoff's value date - an early billing never raises it"
            + " to a future due date")
    void theFloor() {
        String sql = statements();
        String floor = sql.substring(sql.indexOf("CREATE OR REPLACE FUNCTION bnpl.principal_value_floor(obligation uuid)"));
        assertThat(floor.substring(0, floor.indexOf("$$;", floor.indexOf("$$") + 2)))
                .contains("max(CASE WHEN e.id IS NULL THEN b.value_date ELSE LEAST(b.value_date, e.value_date) END)")
                .contains("LEFT JOIN bnpl.bnpl_payoff_execution e ON e.obligation_id = b.obligation_id");
    }

    @Test
    @DisplayName("a PAYOFF repayment only beside its execution, keyed by the quote and dated its value date; a PAID_OFF_EARLY"
            + " closure only beside one, and no other reason beside one")
    void thePayoffsFactsOnlyBesideItsExecution() {
        assertThat(body("bnpl.bnpl_repayment_is_born_once()"))
                .contains("IF NEW.kind = 'PAYOFF' AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_payoff_execution e")
                .contains("AND e.quote_id::text = NEW.request_key")
                .contains("AND e.value_date = NEW.value_date) THEN")
                .contains("value_floor := bnpl.principal_value_floor(NEW.obligation_id);");
        assertThat(body("bnpl.bnpl_obligation_closure_is_born_once()"))
                .contains("OR NOT ((paid_off AND NEW.reason = 'PAID_OFF_EARLY' AND NEW.refund_id IS NULL)")
                .contains("OR (NOT paid_off")
                .contains("AND (NEW.reason <> 'PAID_OFF_EARLY' OR r.kind = 'PAYOFF')) THEN");
    }

    @Test
    @DisplayName("the quote and the execution are SELECT and INSERT only for the application role, never truncated")
    void theGrants() {
        assertThat(statements())
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_payoff_quote TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_payoff_execution TO finapp_app;")
                .contains("BEFORE TRUNCATE ON bnpl.bnpl_payoff_quote")
                .contains("BEFORE TRUNCATE ON bnpl.bnpl_payoff_execution")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE");
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
        try (InputStream in = BnplPayoffMigrationTest.class.getClassLoader().getResourceAsStream(V014)) {
            assertThat(in).as(V014 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
