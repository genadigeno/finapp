package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V010}'s text held to the code (`P12-TSK-018`; {@code BnplServicingMigrationTest}'s idiom - hermetic, so it
 * runs on every build while the live ranks are {@code BnplRepaymentDatabaseTest}'s): the repayment's kinds, its B4 for
 * every writer and its floor; the allocation's HOLD_AS_CREDIT target, its billed-only rank, its per-component uniques and
 * its deferred conservation; the closure's uniques, its rows' side, its deferred tie to the obligation; the edge
 * {@code ACTIVE -> CLOSED} only beside the closure; the grants; every stamp after the locks.
 */
@DisplayName("bnpl V010 holds the repayment's, the allocation's and the closure's ranks as the code declares them"
        + " (P12-TSK-018)")
class BnplRepaymentMigrationTest {

    private static final String V010 = "db/migration/bnpl/V010__customer_repayment_and_closure.sql";

    @Test
    @DisplayName("the repayment knows its kinds, takes B4 for every writer, names the agreement version in force, refuses"
            + " the later tasks' kinds and is floored")
    void theRepaymentsRanks() {
        assertThat(statements()).contains("CHECK (kind IN ('AT_PURCHASE', 'CUSTOMER', 'AUTO_COLLECTION', 'PAYOFF'))");
        String trigger = body("bnpl.bnpl_repayment_is_born_once()");
        assertThat(trigger)
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("OR NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x")
                .contains("IF NEW.kind IN ('AUTO_COLLECTION', 'PAYOFF') THEN")
                .contains("value_floor := bnpl.principal_value_floor(NEW.obligation_id);")
                .contains("IF NEW.value_date < value_floor")
                .contains("NEW.received_at := clock_timestamp();");
    }

    @Test
    @DisplayName("an allocation holds the remainder - no not-due principal - pays only a billed instalment, one line per"
            + " component, and conserves at commit over every payer")
    void theAllocationsRanks() {
        String sql = statements();
        assertThat(sql).contains("OR (target = 'CREDIT_BALANCE' AND instalment_id IS NULL AND component IS NULL));")
                .contains("ON bnpl.bnpl_repayment_allocation (repayment_id, instalment_id, component) WHERE target ="
                        + " 'DUE_ITEM';")
                .contains("ON bnpl.bnpl_repayment_allocation (repayment_id) WHERE target = 'CREDIT_BALANCE';")
                .contains("    AFTER INSERT ON bnpl.bnpl_repayment_allocation\n    DEFERRABLE INITIALLY DEFERRED\n"
                        + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.bnpl_repayment_allocation_conserves();");
        assertThat(sql.substring(sql.indexOf("ADD CONSTRAINT bnpl_repayment_allocation_target")))
                .startsWith("ADD CONSTRAINT bnpl_repayment_allocation_target CHECK (")
                .doesNotContain("NOT_DUE_PRINCIPAL')");
        assertThat(body("bnpl.bnpl_repayment_allocation_is_born_once()"))
                .contains("AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.instalment_id = NEW.instalment_id)");
        assertThat(body("bnpl.bnpl_repayment_allocation_conserves()"))
                .contains("AND bnpl.billing_component_paid(b.id, l.component)")
                .contains("> bnpl.billing_component_billed(b.id, l.component)) THEN");
    }

    @Test
    @DisplayName("the closure: one per obligation, its refund's entry exactly when positive, REPAID on an ACTIVE obligation"
            + " under B4, every instalment billed and paid, and CLOSED beside it at commit")
    void theClosuresRanks() {
        String sql = statements();
        assertThat(sql).contains("CONSTRAINT bnpl_obligation_closure_obligation_unique UNIQUE (obligation_id)")
                .contains("refund_minor >= 0 AND (refund_minor > 0) = (refund_journal_entry_id IS NOT NULL)),")
                .contains("    AFTER INSERT ON bnpl.bnpl_obligation_closure\n    DEFERRABLE INITIALLY DEFERRED\n"
                        + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.bnpl_obligation_closure_closes_its_obligation();");
        assertThat(body("bnpl.bnpl_obligation_closure_is_born_once()"))
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("IF NOT FOUND OR obligation.status <> 'ACTIVE' OR NEW.reason <> 'REPAID' THEN")
                .contains("AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_billing b WHERE b.instalment_id = i.id)) THEN")
                .contains("<> bnpl.billing_component_billed(b.id, c.component)) THEN")
                .contains("NEW.created_at := clock_timestamp();");
        assertThat(body("bnpl.bnpl_obligation_closure_closes_its_obligation()"))
                .contains("WHERE o.id = NEW.obligation_id AND o.status = 'CLOSED' AND o.closure_reason = NEW.reason)");
    }

    @Test
    @DisplayName("the obligation closes only beside its closure, its permit unmoved, the instant the database's - and out of"
            + " CLOSED nothing; the application role may move only the status and the reason besides the permit")
    void theEdgeAndTheGrants() {
        String edge = body("bnpl.bnpl_obligation_permits_only_machine_edges()");
        assertThat(edge).contains("IF NEW.status = OLD.status AND OLD.status = 'ACTIVE'")
                .contains("IF OLD.status = 'ACTIVE' AND NEW.status = 'CLOSED' THEN")
                .contains("OR NOT EXISTS (SELECT 1 FROM bnpl.bnpl_obligation_closure c")
                .contains("NEW.closed_at := clock_timestamp();");
        assertThat(statements()).contains("GRANT SELECT, INSERT ON bnpl.bnpl_obligation_closure TO finapp_app;")
                .contains("GRANT UPDATE (status, closure_reason) ON bnpl.bnpl_obligation TO finapp_app;")
                .doesNotContainPattern("GRANT[^;]*(DELETE|UPDATE ON|UPDATE \\((?!status, closure_reason))");
    }

    @Test
    @DisplayName("V010 names no schema but bnpl's - no ledger table read by a bnpl trigger")
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
        try (InputStream in = BnplRepaymentMigrationTest.class.getClassLoader().getResourceAsStream(V010)) {
            assertThat(in).as(V010 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
