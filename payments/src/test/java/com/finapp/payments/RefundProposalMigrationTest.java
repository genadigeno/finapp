package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("refund_proposal migration reconciliation (P17-TSK-031)")
class RefundProposalMigrationTest {

    private static final String MIGRATION = "db/migration/payments/V043__refund_four_eyes_above_a_threshold.sql";

    @Test
    @DisplayName("the status CHECK is the proposal's machine; four eyes and one open proposal are the schema's")
    void theMachineAndFourEyesAreTheSchemas() {
        assertThat(migration())
                .contains("CHECK (status IN (" + RefundProposal.Status.sqlValueList() + "))")
                .contains("CHECK (decided_by IS NULL OR decided_by <> proposed_by)")
                .contains("WHERE status = 'PROPOSED'");
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
                .contains("GRANT SELECT ON payments.refund_four_eyes_threshold TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON payments.refund_proposal TO finapp_app;")
                .doesNotContain("DELETE ON payments.");
    }

    private static String migration() {
        try (InputStream migration = RefundProposalMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
