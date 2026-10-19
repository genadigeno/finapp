package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V012}'s text held to the code (`P12-TSK-021`; {@code BnplRepaymentMigrationTest}'s idiom - hermetic, so it
 * runs on every build while the live ranks are {@code BnplRefundDatabaseTest}'s): the refund's uniques and conservation,
 * its trigger's B5 then B4 and the two bounds (INV-BNPL-07, B7), the adjustment's ranks and the deferred Σ, the
 * replaced {@code billing_component_paid} counting cancellations, the billing's and the closure's due-date ranks, the
 * schedule's and agreement's version n+1, the purchase's terminal edge, the grants, and every stamp after the locks.
 */
@DisplayName("bnpl V012 holds the refund's, the adjustment's and the versions' ranks as the code declares them (P12-TSK-021)")
class BnplRefundMigrationTest {

    private static final String V012 = "db/migration/bnpl/V012__merchant_refunds_latest_instalments_first.sql";

    @Test
    @DisplayName("the refund: one per requester's key at a merchant, one entry, R = r_f + debit = cancelled + excess, the"
            + " excess somewhere exactly when positive, the reason screened")
    void theRefundsRow() {
        assertThat(statements())
                .contains("CONSTRAINT bnpl_refund_request_unique UNIQUE (merchant_id, requested_by, refund_key)")
                .contains("CONSTRAINT bnpl_refund_journal_entry_unique UNIQUE (journal_entry_id)")
                .contains("AND amount_minor = fee_returned_minor + payable_debit_minor")
                .contains("AND amount_minor = cancelled_unbilled_minor + cancelled_billed_minor + excess_minor)")
                .contains("(excess_minor = 0 AND excess_to = 'NONE')")
                .contains("CHECK (NOT bnpl.holds_instrument_shape(reason))")
                .contains("CHECK (allocation_engine = 'REFUND_ALLOCATION_ENGINE_V1')");
    }

    @Test
    @DisplayName("the refund's trigger takes B5 then B4 for every writer and holds Σ refunds <= G and Σ payable debits <="
            + " the net, the versions in force and the floor - and a CLOSED obligation's refund is all excess to the wallet")
    void theRefundsTrigger() {
        String trigger = body("bnpl.bnpl_refund_is_born_once()");
        assertThat(trigger.indexOf("FROM bnpl.purchase p WHERE p.id = NEW.purchase_id FOR UPDATE;"))
                .as("B5 before B4").isLessThan(trigger.indexOf(
                        "FROM bnpl.bnpl_obligation o WHERE o.purchase_id = NEW.purchase_id FOR UPDATE;"));
        assertThat(trigger).contains("refunded + NEW.amount_minor > financing.gross_minor")
                .contains("debited + NEW.payable_debit_minor > financing.net_minor")
                .contains("returned + NEW.fee_returned_minor > financing.fee_minor")
                .contains("value_floor := bnpl.principal_value_floor(obligation.id);")
                .contains("OR NEW.excess_to <> 'WALLET'")
                .contains("NEW.created_at := clock_timestamp();");
    }

    @Test
    @DisplayName("the adjustments sum to their refund at commit - Σ adjustments + excess = R - no billed component settled"
            + " beyond what it billed, a version beside an unbilled cancellation, FULLY_REFUNDED exactly at the gross")
    void theDeferredConservation() {
        String sql = statements();
        assertThat(sql).contains("    AFTER INSERT ON bnpl.bnpl_refund\n    DEFERRABLE INITIALLY DEFERRED\n"
                        + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.bnpl_refund_conserves();")
                .contains("    AFTER INSERT ON bnpl.bnpl_refund_adjustment\n    DEFERRABLE INITIALLY DEFERRED\n"
                        + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.bnpl_refund_conserves();");
        assertThat(body("bnpl.bnpl_refund_conserves()"))
                .contains("IF unbilled <> refund.cancelled_unbilled_minor OR billed <> refund.cancelled_billed_minor")
                .contains("> bnpl.billing_component_billed(b.id, c.component)) THEN")
                .contains("SELECT 1 FROM bnpl.bnpl_schedule s WHERE s.refund_id = refund.id")
                .contains("p.status = 'FULLY_REFUNDED'");
    }

    @Test
    @DisplayName("what a billing has had settled counts every refund's cancellation of its principal and release of its"
            + " interest - so a cancelled billed item reads settled, and closes")
    void theOneDefinitionOfPaidCountsCancellations() {
        String paid = statements().substring(statements().indexOf(
                "CREATE OR REPLACE FUNCTION bnpl.billing_component_paid(billing uuid, paid_component text)"));
        assertThat(paid.substring(0, paid.indexOf("$$;", paid.indexOf("$$") + 2)))
                .contains("FROM bnpl.bnpl_credit_balance_allocation a")
                .contains("FROM bnpl.bnpl_repayment_allocation r")
                .contains("FROM bnpl.bnpl_refund_adjustment x")
                .contains("WHEN 'PRINCIPAL' THEN x.principal_cancelled_minor")
                .contains("WHEN 'INTEREST' THEN x.interest_released_minor");
    }

    @Test
    @DisplayName("the billing's oldest-first rank and the closure's every-instalment-billed are judged by due date - a"
            + " refund's version carries billed instalments as new rows")
    void theDueDateRanks() {
        String billing = body("bnpl.bnpl_billing_is_born_once()");
        assertThat(billing).contains("WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date))")
                .doesNotContain("WHERE b.instalment_id = i.id))")
                .contains("remaining := schedule.principal_minor - (SELECT coalesce(sum(b.principal_minor), 0)");
        String closure = body("bnpl.bnpl_obligation_closure_is_born_once()");
        assertThat(closure).contains("WHERE b.obligation_id = NEW.obligation_id AND b.value_date = i.due_date)) THEN")
                .contains("(NEW.reason = 'REFUNDED' AND purchase_status = 'FULLY_REFUNDED')")
                .contains("(NEW.reason = 'REFUNDED_PARTIALLY_REPAID' AND purchase_status = 'FINANCED'")
                .contains("(NEW.reason = 'REPAID' AND purchase_status <> 'FULLY_REFUNDED')")
                .doesNotContain("'PAID_OFF_EARLY'");
    }

    @Test
    @DisplayName("schedule and agreement version n+1: the next, beside its refund, from the purchase date, its principal the"
            + " predecessor's less exactly the unbilled cancellation, every billing kept as billed, the terms copied")
    void theVersions() {
        String sql = statements();
        assertThat(sql).contains("schedule_version >= 1 AND (schedule_version = 1) = (refund_id IS NULL));")
                .contains("FOREIGN KEY (refund_id) REFERENCES bnpl.bnpl_refund (id) DEFERRABLE INITIALLY DEFERRED;");
        assertThat(body("bnpl.bnpl_schedule_is_born_once()"))
                .contains("NEW.start_date <> obligation.purchase_date")
                .contains("NEW.schedule_version <> (SELECT max(x.schedule_version) + 1 FROM bnpl.bnpl_schedule x");
        assertThat(body("bnpl.bnpl_schedule_conserves()"))
                .contains("schedule.principal_minor IS DISTINCT FROM previous.principal_minor")
                .contains("- (SELECT r.cancelled_unbilled_minor FROM bnpl.bnpl_refund r WHERE r.id = schedule.refund_id)")
                .contains("AND i.principal_minor = b.principal_minor");
        assertThat(body("bnpl.bnpl_agreement_is_born_once()"))
                .contains("OR NEW.canonical_terms <> previous.canonical_terms")
                .contains("OR NEW.schedule_version <> previous.schedule_version + 1");
    }

    @Test
    @DisplayName("the purchase moves FINANCED -> FULLY_REFUNDED only when its refunds total the gross, and nothing leaves"
            + " FULLY_REFUNDED")
    void thePurchasesTerminalEdge() {
        String edge = body("bnpl.purchase_permits_only_machine_edges()");
        assertThat(edge).contains("IF OLD.status = 'FINANCED' AND NEW.status = 'FULLY_REFUNDED' THEN")
                .contains("<> (SELECT f.gross_minor FROM bnpl.merchant_financing f WHERE f.purchase_id = OLD.id) THEN")
                .contains("IF NOT (OLD.status = 'AUTHORISED' AND NEW.status = 'FINANCED') THEN");
    }

    @Test
    @DisplayName("the grants: the refund and its adjustments SELECT and INSERT only; V012 names no schema but bnpl's")
    void theGrantsAndTheSchema() {
        String sql = statements();
        assertThat(sql).contains("GRANT SELECT, INSERT ON bnpl.bnpl_refund TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_refund_adjustment TO finapp_app;")
                .doesNotContainPattern("GRANT[^;]*(DELETE|UPDATE)");
        assertThat(sql).doesNotContain("ledger.").doesNotContain("merchant.merchant").doesNotContain("credit.");
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
        try (InputStream in = BnplRefundMigrationTest.class.getClassLoader().getResourceAsStream(V012)) {
            assertThat(in).as(V012 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
