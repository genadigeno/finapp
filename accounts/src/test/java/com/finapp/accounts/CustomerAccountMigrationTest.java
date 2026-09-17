package com.finapp.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002` and the enums cannot drift (`P3-TSK-012`, the {@code P0-TSK-022} pattern).
 *
 * <p>Three generated artefacts, each with one definition in code: the status {@code CHECK} from
 * {@link CustomerAccountStatus#sqlValueList()}, the product {@code CHECK} from
 * {@link ProductType#sqlValueList()}, and — the sharp one — the one-live-account index
 * predicate from {@link CustomerAccountStatus#sqlTerminalValueList()}: a state added to the
 * machine without a decision about whether it frees the slot either lets a customer hold two
 * live agreements or blocks their successor forever, and both are silent without this
 * reconciliation (the {@code KycCaseMigrationTest} reasoning, verbatim).
 *
 * <p>`V002` is pinned directly rather than by latest-definition derivation because it is the
 * table's creating migration; when a later migration replaces a constraint, the
 * {@code RoleAssignmentMigrationTest} applied-history lesson applies and this test must learn
 * it.
 */
@DisplayName("customer_account migration reconciliation (P3-TSK-012)")
class CustomerAccountMigrationTest {

    private static final String MIGRATION =
            "db/migration/accounts/V002__create_customer_account.sql";

    @Test
    @DisplayName("the status CHECK is generated from the machine")
    void statusCheckMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (status IN (" + CustomerAccountStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the product CHECK is generated from the product enum")
    void productCheckMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (product_type IN (" + ProductType.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the one-live-account predicate is generated from the terminal states")
    void indexPredicateMatchesTheTerminalStates() {
        assertThat(migration())
                .contains(
                        "WHERE status NOT IN ("
                                + CustomerAccountStatus.sqlTerminalValueList()
                                + ")");
    }

    @Test
    @DisplayName("the grants are the planned set: SELECT+INSERT, UPDATE narrowed to two columns")
    void grantsAreTheColumnNarrowedSet() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON accounts.customer_account TO finapp_app;")
                .contains(
                        "GRANT UPDATE (status, status_changed_at) ON accounts.customer_account"
                                + " TO finapp_app;");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Without this, a moved or renamed file makes every assertion above pass over an
        // empty string.
        assertThat(migration()).contains("CREATE TABLE accounts.customer_account");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                CustomerAccountMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
