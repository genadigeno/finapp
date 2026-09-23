package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002` and the code cannot drift (`P6-TSK-003`, the {@code P0-TSK-022} pattern): the status
 * {@code CHECK}s on all three columns from {@link MerchantStatus#sqlValueList()}, the name
 * bounds from {@link Merchant#MAX_NAME_LENGTH}, and — the sharp one — every transition
 * trigger edge from {@code permittedTransitions()}: an edge added to the machine without its
 * trigger half is a transition the aggregate permits and every other writer is refused, and
 * an edge the machine lost is a move raw SQL can make that the domain cannot.
 */
@DisplayName("merchant migration reconciliation (P6-TSK-003)")
class MerchantMigrationTest {

    private static final String MIGRATION =
            "db/migration/merchant/V002__create_merchant_and_history.sql";

    @Test
    @DisplayName("the status CHECKs are generated from the machine, on all three columns")
    void statusChecksMatchTheEnum() {
        String list = MerchantStatus.sqlValueList();
        assertThat(migration())
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("every transition trigger edge is generated from the machine")
    void triggerEdgesMatchTheMachine() {
        String migration = migration();
        for (MerchantStatus from : MerchantStatus.values()) {
            if (from.isTerminal()) {
                assertThat(migration)
                        .as("a terminal state has no trigger edge (%s)", from)
                        .doesNotContain("OLD.status = '" + from.name() + "' AND NEW.status");
                continue;
            }
            String targets =
                    from.permittedTransitions().stream()
                            .map(to -> "'" + to.name() + "'")
                            .collect(Collectors.joining(", "));
            assertThat(migration)
                    .as("the trigger's %s edge set is the machine's", from)
                    .contains("(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                            + targets + "))");
        }
    }

    @Test
    @DisplayName("the name bounds are the aggregate's own constant")
    void nameBoundsMatchTheAggregate() {
        assertThat(migration())
                .contains("CHECK (length(legal_name) BETWEEN 1 AND " + Merchant.MAX_NAME_LENGTH + ")")
                .contains("CHECK (length(display_name) BETWEEN 1 AND "
                        + Merchant.MAX_NAME_LENGTH + ")");
    }

    @Test
    @DisplayName("no balance column exists in this schema, and never will (INV-MER-02)")
    void noBalanceColumnExists() {
        // The register-level half; the live-schema sweep in the app database suite holds the
        // same claim against information_schema. Both directions of the phase's named risk.
        // Comments and COMMENT ON prose stripped first: the claim is about COLUMNS - the
        // migration's own text says "never a balance" and must be allowed to.
        String definitionsOnly =
                migration()
                        .lines()
                        .map(line -> line.replaceFirst("--.*$", ""))
                        .filter(line -> !line.trim().startsWith("'"))
                        .collect(Collectors.joining("\n"))
                        .toLowerCase();
        assertThat(definitionsOnly)
                .doesNotContain("balance")
                .doesNotContain("payable")
                .doesNotContain("amount_minor");
    }

    @Test
    @DisplayName("no cross-schema foreign key, and the grants are narrowed")
    void theBoundaryAndTheGrantsHold() {
        assertThat(migration())
                .as("party_ref is a value; an FK into party's schema is coupling neither"
                        + " Gradle nor ArchUnit can see (ADR-0029)")
                .doesNotContain("REFERENCES party.");
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON merchant.merchant TO finapp_app")
                .contains("GRANT UPDATE (status, status_changed_at) ON merchant.merchant"
                        + " TO finapp_app")
                .contains("GRANT SELECT, INSERT ON merchant.merchant_event TO finapp_app")
                .doesNotContain("DELETE ON merchant.merchant");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE merchant.merchant");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                MerchantMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
