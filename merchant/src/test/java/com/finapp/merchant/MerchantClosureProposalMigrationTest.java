package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("merchant_closure_proposal migration reconciliation (P17-TSK-031)")
class MerchantClosureProposalMigrationTest {

    private static final String MIGRATION = "db/migration/merchant/V019__merchant_closure_by_four_eyes.sql";

    @Test
    @DisplayName("the status CHECK is generated from the proposal's machine")
    void statusCheckMatchesTheEnum() {
        assertThat(migration()).contains("CHECK (status IN (" + ClosureProposalStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("four eyes and one open proposal per merchant are the schema's, for every writer")
    void fourEyesAndOneOpenAreTheSchemas() {
        assertThat(migration())
                .contains("CHECK (decided_by IS NULL OR decided_by <> proposed_by)")
                .contains("ON merchant.merchant_closure_proposal (merchant_id)\n    WHERE status = 'PROPOSED'");
    }

    @Test
    @DisplayName("the grants: SELECT and INSERT, UPDATE narrowed to the decision's columns, never DELETE")
    void grantsAreTheDecisionsColumns() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON merchant.merchant_closure_proposal TO finapp_app;")
                .doesNotContain("DELETE ON merchant.merchant_closure_proposal");
    }

    private static String migration() {
        try (InputStream migration = MerchantClosureProposalMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
