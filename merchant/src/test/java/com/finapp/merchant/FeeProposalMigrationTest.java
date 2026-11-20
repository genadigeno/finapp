package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("fee schedule version and fee assignment proposal migration reconciliation (P17-TSK-123)")
class FeeProposalMigrationTest {

    private static final String MIGRATION =
            "db/migration/merchant/V022__fee_schedule_versions_and_fee_assignment_by_four_eyes.sql";

    @Test
    @DisplayName("the status CHECKs are the proposals' machine; four eyes and one open proposal per subject are the schema's")
    void theMachineAndFourEyesAreTheSchemas() {
        String migration = migration();
        assertThat(count(migration, "CHECK (status IN (" + FeeProposalStatus.sqlValueList() + "))")).isEqualTo(2);
        assertThat(count(migration, "CHECK (decided_by IS NULL OR decided_by <> proposed_by)")).isEqualTo(2);
        assertThat(migration)
                .contains("ON merchant.fee_schedule_version_proposal (fee_schedule_id)\n    WHERE status = 'PROPOSED'")
                .contains("ON merchant.fee_assignment_proposal (merchant_id)\n    WHERE status = 'PROPOSED'")
                .contains("CHECK (\n        (status = 'APPROVED') = (fee_schedule_version_id IS NOT NULL))");
    }

    @Test
    @DisplayName("the proposed terms are held as V004 holds a version's: the policies are the enums'")
    void theTermsAreTheVersionsShape() {
        StringBuilder refund = new StringBuilder();
        for (RefundFeePolicy policy : RefundFeePolicy.values()) {
            refund.append(refund.isEmpty() ? "" : ", ").append("'").append(policy.name()).append("'");
        }
        assertThat(migration())
                .contains("CHECK (refund_fee_policy IN (" + refund + "))")
                .contains("rate                    numeric(7, 6) NOT NULL")
                .contains("FOREIGN KEY (fee_schedule_id, fixed_currency) REFERENCES merchant.fee_schedule (id, currency)");
    }

    @Test
    @DisplayName("each proposal is SELECT and INSERT with its decision's columns, never DELETE")
    void theGrants() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON merchant.fee_schedule_version_proposal TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON merchant.fee_assignment_proposal TO finapp_app;")
                .contains("GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason,"
                        + " decision_correlation_id,\n    fee_schedule_version_id) ON merchant.fee_schedule_version_proposal")
                .contains("GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason,"
                        + " decision_correlation_id)\n    ON merchant.fee_assignment_proposal")
                .doesNotContain("DELETE ON merchant.");
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String migration() {
        try (InputStream migration = FeeProposalMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
