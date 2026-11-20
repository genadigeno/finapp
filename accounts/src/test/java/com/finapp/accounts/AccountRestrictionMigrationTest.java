package com.finapp.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("account_restriction migration reconciliation (P17-TSK-028)")
class AccountRestrictionMigrationTest {

    private static final String MIGRATION = "db/migration/accounts/V003__account_restriction_by_four_eyes.sql";

    @Test
    @DisplayName("the kind, status and reason-per-kind CHECKs are generated from the enums")
    void checksMatchTheEnums() {
        assertThat(migration())
                .contains("CHECK (kind IN (" + RestrictionKind.sqlValueList() + "))")
                .contains("CHECK (status IN (" + RestrictionStatus.sqlValueList() + "))")
                .contains(RestrictionReason.sqlPairs());
    }

    @Test
    @DisplayName("four eyes and one open proposal per agreement are the schema's, for every writer")
    void fourEyesAndOneOpenAreTheSchemas() {
        assertThat(migration())
                .contains("CHECK (decided_by IS NULL OR decided_by <> proposed_by)")
                .contains("ON accounts.account_restriction (account_id) WHERE status = 'PROPOSED'");
    }

    @Test
    @DisplayName("the grants: SELECT and INSERT, UPDATE narrowed to the decision's columns, never DELETE")
    void grantsAreTheDecisionsColumns() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON accounts.account_restriction TO finapp_app;")
                .contains("GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason,"
                        + " decision_correlation_id)")
                .doesNotContain("DELETE ON accounts.account_restriction");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE accounts.account_restriction");
    }

    private static String migration() {
        try (InputStream migration = AccountRestrictionMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
