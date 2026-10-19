package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V016}'s text held to the code (`P12-TSK-024`; {@code BnplPayoffMigrationTest}'s idiom - hermetic, so it runs
 * on every build while the live ranks are {@code BnplRepaymentReversalDatabaseTest}'s and {@code BnplWaiverDatabaseTest}'s):
 * the four-eyes {@code CHECK}s, the one-live-proposal partial unique, the reversal's edges and its value-date rank, the
 * negating rows mirrored and whole at commit, the waiver's composite keys and its decision born once, both arms of the
 * bound for every writer, the two shared sums replaced, the grants, and every stamp after the locks.
 */
@DisplayName("bnpl V016 holds the repayment reversal and the fee waiver as the code declares them (P12-TSK-024)")
class BnplCorrectionMigrationTest {

    private static final String V016 = "db/migration/bnpl/V016__repayment_reversal_and_fee_waiver.sql";

    @Test
    @DisplayName("four eyes by CHECK on both: whoever approved never proposed; an approval posts exactly its entry")
    void fourEyesByCheck() {
        assertThat(statements())
                .contains("CONSTRAINT bnpl_repayment_reversal_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)")
                .contains("CONSTRAINT bnpl_waiver_decision_four_eyes CHECK (outcome <> 'APPROVED' OR decided_by <> proposed_by)")
                .contains("CONSTRAINT bnpl_waiver_decision_waiver_fk FOREIGN KEY (waiver_id, proposed_by)\n"
                        + "        REFERENCES bnpl.bnpl_waiver (id, proposed_by)")
                .contains("(status = 'APPROVED') = (journal_entry_id IS NOT NULL)\n"
                        + "        AND (status = 'APPROVED') = (value_date IS NOT NULL)")
                .contains("CONSTRAINT bnpl_waiver_decision_approval_posts CHECK ((outcome = 'APPROVED') = (journal_entry_id IS NOT NULL))");
    }

    @Test
    @DisplayName("one live reversal per repayment; one decision per waiver; one negation per allocation")
    void theArbiters() {
        assertThat(statements())
                .contains("CREATE UNIQUE INDEX bnpl_repayment_reversal_one_live\n"
                        + "    ON bnpl.bnpl_repayment_reversal (repayment_id) WHERE status IN ('PROPOSED', 'APPROVED');")
                .contains("CONSTRAINT bnpl_waiver_decision_once UNIQUE (waiver_id)")
                .contains("CONSTRAINT bnpl_repayment_allocation_reversal_allocation_unique UNIQUE (allocation_id)")
                .contains("CONSTRAINT bnpl_repayment_reversal_entry_unique UNIQUE (journal_entry_id)");
    }

    @Test
    @DisplayName("the reversal's machine: born PROPOSED under B4 on an ACTIVE obligation for its own repayment, never a"
            + " payoff's; PROPOSED -> APPROVED | REJECTED; approved only while ACTIVE and valued between the floor and today;"
            + " every stamp clock_timestamp()")
    void theReversalsMachine() {
        String trigger = body("bnpl.bnpl_repayment_reversal_permits_only_machine_edges()");
        assertThat(trigger)
                .contains("WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("IF repayment.kind = 'PAYOFF' THEN")
                .contains("IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN")
                .contains("value_floor := bnpl.principal_value_floor(NEW.obligation_id);")
                .contains("OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN")
                .contains("NEW.proposed_at := clock_timestamp();")
                .contains("NEW.decided_at := clock_timestamp();")
                .doesNotContain("transaction_timestamp()");
    }

    @Test
    @DisplayName("the negating rows mirror their allocation exactly, only for an APPROVED reversal, whole at commit")
    void theNegatingRows() {
        assertThat(body("bnpl.bnpl_repayment_allocation_reversal_is_born_once()"))
                .contains("IF reversal.id IS NULL OR reversal.status <> 'APPROVED' THEN")
                .contains("OR NEW.amount_minor <> allocation.amount_minor THEN");
        assertThat(statements())
                .contains("CREATE CONSTRAINT TRIGGER bnpl_repayment_reversal_is_whole\n    AFTER UPDATE ON bnpl.bnpl_repayment_reversal\n"
                        + "    DEFERRABLE INITIALLY DEFERRED")
                .contains("CREATE CONSTRAINT TRIGGER bnpl_repayment_allocation_reversal_is_whole\n"
                        + "    AFTER INSERT ON bnpl.bnpl_repayment_allocation_reversal\n    DEFERRABLE INITIALLY DEFERRED");
    }

    @Test
    @DisplayName("the waiver's bound, both arms (the Phase 11 gate's R3), for every writer under B4 - at the proposal and at"
            + " the approval")
    void theWaiversBound() {
        String room = body("bnpl.fee_waiver_room(assessment uuid)");
        assertThat(room)
                .contains("bnpl.billing_component_billed(b.id, 'FEES') - bnpl.billing_component_paid(b.id, 'FEES'),")
                .contains("f.amount_minor\n            - (SELECT coalesce(sum(w.amount_minor), 0) FROM bnpl.bnpl_waiver w")
                .contains("- (SELECT coalesce(sum(x.late_fee_released_minor), 0) FROM bnpl.bnpl_refund_adjustment x");
        assertThat(body("bnpl.bnpl_waiver_is_born_once()"))
                .contains("WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("IF NEW.amount_minor > coalesce(bnpl.fee_waiver_room(NEW.fee_assessment_id), 0) THEN")
                .contains("AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_waiver_decision d WHERE d.waiver_id = w.id)) THEN")
                .contains("NEW.proposed_at := clock_timestamp();");
        assertThat(body("bnpl.bnpl_waiver_decision_is_born_once()"))
                .contains("WHERE o.id = proposal.obligation_id FOR UPDATE;")
                .contains("IF proposal.amount_minor > coalesce(bnpl.fee_waiver_room(proposal.fee_assessment_id), 0) THEN")
                .contains("NEW.decided_at := clock_timestamp();");
    }

    @Test
    @DisplayName("the two shared sums: billed FEES less approved waivers; settled less the negating rows - V013's every"
            + " other term kept (the release counted as settled)")
    void theSharedSums() {
        assertThat(body("bnpl.billing_component_billed(billing uuid, billed_component text)"))
                .contains("- (SELECT coalesce(sum(w.amount_minor), 0) FROM bnpl.bnpl_waiver w\n"
                        + "                        JOIN bnpl.bnpl_waiver_decision d ON d.waiver_id = w.id AND d.outcome = 'APPROVED'");
        assertThat(body("bnpl.billing_component_paid(billing uuid, paid_component text)"))
                .contains("- (SELECT coalesce(sum(n.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation_reversal n")
                .contains("WHEN 'FEES' THEN x.late_fee_released_minor")
                .contains("FROM bnpl.bnpl_credit_balance_allocation a");
    }

    @Test
    @DisplayName("the grants: the reversal's decision columns only (never its instant); the rest SELECT and INSERT only")
    void theGrants() {
        assertThat(statements())
                .contains("GRANT UPDATE (status, decided_by, decided_by_type, decision_reason, journal_entry_id, value_date)\n"
                        + "    ON bnpl.bnpl_repayment_reversal TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_repayment_allocation_reversal TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_waiver TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_waiver_decision TO finapp_app;")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("UPDATE ON bnpl.bnpl_waiver");
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
        try (InputStream in = BnplCorrectionMigrationTest.class.getClassLoader().getResourceAsStream(V016)) {
            assertThat(in).as(V016 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
