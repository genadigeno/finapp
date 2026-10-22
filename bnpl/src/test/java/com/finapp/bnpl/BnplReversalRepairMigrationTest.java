package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V020}'s text held to the code (the Phase 12 -> 13 transition gate's repair R3; {@code BnplCorrectionMigrationTest}'s
 * idiom - hermetic, so it runs on every build, while the live ranks are {@code BnplRepaymentReversalDatabaseTest}'s): A2-1's
 * rank - a reversal never valued before the repayment it reverses, its ceiling raised by exactly that date - and A7-3's
 * release - INSERT-only, under B4, beside an APPROVED reversal, only on an instalment a refund cancelled whole, exactly
 * the fee due - counted by the two shared sums and judged at the reversal's commit for every writer.
 */
@DisplayName("bnpl V020 holds the reversal after its original and its released fee as the code declares them (the Phase 12"
        + " transition gate's R3)")
class BnplReversalRepairMigrationTest {

    private static final String V020 = "db/migration/bnpl/V020__the_reversal_after_its_original_and_its_released_fee.sql";

    @Test
    @DisplayName("A2-1: the approval valued never before the repayment it reverses, the ceiling raised by that date - and"
            + " V016's every other rank kept")
    void aReversalIsNeverValuedBeforeItsRepayment() {
        String trigger = body("bnpl.bnpl_repayment_reversal_permits_only_machine_edges()");
        assertThat(trigger)
                .contains("IF NEW.value_date < repayment.value_date THEN")
                .contains("OR NEW.value_date > GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor,\n"
                        + "                                             repayment.value_date) THEN")
                .contains("IF NEW.value_date < value_floor")
                .contains("IF repayment.kind = 'PAYOFF' THEN")
                .contains("IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED')) THEN")
                .contains("NEW.proposed_at := clock_timestamp();")
                .contains("NEW.decided_at := clock_timestamp();");
    }

    @Test
    @DisplayName("A7-3's release: born once under B4, beside an APPROVED reversal and under an entry of its own, only on an"
            + " instalment a refund cancelled whole, exactly the fee due; never updated, deleted or truncated")
    void theReleaseIsBornOnce() {
        assertThat(statements())
                .contains("CONSTRAINT bnpl_reversal_fee_release_once UNIQUE (reversal_id, instalment_id)")
                .contains("CONSTRAINT bnpl_reversal_fee_release_amount_positive CHECK (amount_minor > 0)")
                .contains("CONSTRAINT bnpl_reversal_fee_release_instalment_fk FOREIGN KEY (instalment_id, obligation_id)\n"
                        + "        REFERENCES bnpl.bnpl_instalment (id, obligation_id)")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_reversal_fee_release")
                .contains("BEFORE TRUNCATE ON bnpl.bnpl_reversal_fee_release");
        assertThat(body("bnpl.bnpl_reversal_fee_release_is_born_once()"))
                .contains("IF TG_OP <> 'INSERT' THEN")
                .contains("WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("OR reversal.status <> 'APPROVED'")
                .contains("OR NEW.journal_entry_id = reversal.journal_entry_id")
                .contains("AND a.kind = 'BILLED_UNPAID' AND a.whole")
                .contains("NEW.amount_minor <> bnpl.billing_component_billed(billing, 'FEES')");
    }

    @Test
    @DisplayName("the two shared sums count the release: settled FEES, and never forgiven again - V016's every other term"
            + " kept")
    void theSharedSumsCountTheRelease() {
        assertThat(body("bnpl.billing_component_paid(billing uuid, paid_component text)"))
                .contains("FROM bnpl.bnpl_reversal_fee_release f")
                .contains("WHERE b.id = billing AND paid_component = 'FEES'")
                .contains("- (SELECT coalesce(sum(n.amount_minor), 0) FROM bnpl.bnpl_repayment_allocation_reversal n")
                .contains("WHEN 'FEES' THEN x.late_fee_released_minor")
                .contains("FROM bnpl.bnpl_credit_balance_allocation a");
        assertThat(body("bnpl.fee_waiver_room(assessment uuid)"))
                .contains("- (SELECT coalesce(sum(r.amount_minor), 0) FROM bnpl.bnpl_reversal_fee_release r")
                .contains("- (SELECT coalesce(sum(x.late_fee_released_minor), 0) FROM bnpl.bnpl_refund_adjustment x");
    }

    @Test
    @DisplayName("at the reversal's commit, for every writer: no fee due on an instalment a refund cancelled whole; the"
            + " release SELECT and INSERT only")
    void theCommitRankAndTheGrants() {
        assertThat(statements())
                .contains("CREATE CONSTRAINT TRIGGER bnpl_repayment_reversal_releases_refunded_fees\n"
                        + "    AFTER UPDATE ON bnpl.bnpl_repayment_reversal\n"
                        + "    DEFERRABLE INITIALLY DEFERRED")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_reversal_fee_release TO finapp_app;")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE");
        assertThat(body("bnpl.bnpl_repayment_reversal_releases_refunded_fees()"))
                .contains("IF NEW.status = 'APPROVED' AND EXISTS (")
                .contains("AND bnpl.billing_component_billed(b.id, 'FEES') > bnpl.billing_component_paid(b.id, 'FEES')");
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
        try (InputStream in = BnplReversalRepairMigrationTest.class.getClassLoader().getResourceAsStream(V020)) {
            assertThat(in).as(V020 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
