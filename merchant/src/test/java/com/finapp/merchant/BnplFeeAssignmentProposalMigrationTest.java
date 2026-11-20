package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("bnpl_fee_assignment_proposal migration reconciliation (P17-TSK-116)")
class BnplFeeAssignmentProposalMigrationTest {

    private static final String MIGRATION = "db/migration/merchant/V021__bnpl_fee_assignment_by_four_eyes.sql";

    @Test
    @DisplayName("the status CHECK is the proposal's machine; four eyes and one open proposal are the schema's")
    void theMachineAndFourEyesAreTheSchemas() {
        assertThat(migration())
                .contains("CHECK (status IN (" + BnplFeeAssignmentProposal.Status.sqlValueList() + "))")
                .contains("CHECK (decided_by IS NULL OR decided_by <> proposed_by)")
                .contains("WHERE status = 'PROPOSED'");
    }

    @Test
    @DisplayName("the product CHECK is MerchantBnplProduct's; one open proposal per merchant and product")
    void theProductsAreTheEnums() {
        StringBuilder products = new StringBuilder();
        for (MerchantBnplProduct product : MerchantBnplProduct.values()) {
            products.append(products.isEmpty() ? "" : ", ").append("'").append(product.name()).append("'");
        }
        assertThat(migration())
                .contains("CHECK (product IN (" + products + "))")
                .contains("ON merchant.bnpl_fee_assignment_proposal (merchant_id, product)");
    }

    @Test
    @DisplayName("the proposal is SELECT and INSERT with its decision's columns, never DELETE")
    void theGrants() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON merchant.bnpl_fee_assignment_proposal TO finapp_app;")
                .contains("GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason,"
                        + " decision_correlation_id)")
                .doesNotContain("DELETE ON merchant.");
    }

    private static String migration() {
        try (InputStream migration = BnplFeeAssignmentProposalMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
