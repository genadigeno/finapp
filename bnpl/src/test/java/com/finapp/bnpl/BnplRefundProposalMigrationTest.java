package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("bnpl refund_proposal migration reconciliation (P17-TSK-116)")
class BnplRefundProposalMigrationTest {

    private static final String MIGRATION = "db/migration/bnpl/V023__operator_bnpl_refund_four_eyes_above_a_threshold.sql";

    @Test
    @DisplayName("the status CHECK is the proposal's machine; four eyes and one open proposal are the schema's")
    void theMachineAndFourEyesAreTheSchemas() {
        assertThat(migration())
                .contains("CHECK (status IN (" + BnplRefundProposal.Status.sqlValueList() + "))")
                .contains("CHECK (decided_by IS NULL OR decided_by <> proposed_by)")
                .contains("WHERE status = 'PROPOSED'");
    }

    @Test
    @DisplayName("an approval and only an approval names the refund it applied; one open proposal per purchase")
    void theApprovalNamesItsRefund() {
        assertThat(migration())
                .contains("CHECK ((status = 'APPROVED') = (refund_id IS NOT NULL))")
                .contains("ON bnpl.refund_proposal (purchase_id)")
                .contains("REFERENCES bnpl.purchase (id)");
    }

    @Test
    @DisplayName("the threshold is seeded for every supported currency at version 1, the owner's values")
    void theThresholdIsSeededPerCurrency() {
        assertThat(migration())
                .contains("(1, 'EUR', 2, 100000,")
                .contains("(1, 'GBP', 2, 100000,")
                .contains("(1, 'USD', 2, 100000,")
                .contains("(1, 'JPY', 0, 150000,")
                .contains("(1, 'BHD', 3, 400000,");
    }

    @Test
    @DisplayName("the threshold is read only; the proposal is SELECT and INSERT with its decision's columns, never DELETE")
    void theGrants() {
        assertThat(migration())
                .contains("GRANT SELECT ON bnpl.refund_four_eyes_threshold TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.refund_proposal TO finapp_app;")
                .doesNotContain("DELETE ON bnpl.");
    }

    private static String migration() {
        try (InputStream migration = BnplRefundProposalMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
