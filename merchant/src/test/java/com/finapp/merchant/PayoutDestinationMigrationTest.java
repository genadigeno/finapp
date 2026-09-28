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
 * `V006` and the code cannot drift (`P6-TSK-011`, the {@code P0-TSK-022} pattern): the status
 * {@code CHECK}s, the one-open index and every trigger edge from {@link PayoutDestinationStatus};
 * the reference and suffix shapes from {@link BankDetailShapes} and {@link PayoutDestination};
 * the bounds from their constants — and the four-eyes and cooling-off constraints, whose absence
 * would leave only the application between a proposer and their own approval.
 */
@DisplayName("payout destination migration reconciliation (P6-TSK-011)")
class PayoutDestinationMigrationTest {

    private static final String MIGRATION =
            "db/migration/merchant/V006__create_payout_destination.sql";

    @Test
    @DisplayName("the status CHECKs are generated from the machine, on all three columns")
    void statusChecksMatchTheEnum() {
        String list = PayoutDestinationStatus.sqlValueList();
        assertThat(migration())
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("every transition trigger edge is generated from the machine")
    void triggerEdgesMatchTheMachine() {
        String migration = migration();
        for (PayoutDestinationStatus from : PayoutDestinationStatus.values()) {
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
    @DisplayName("one open change and one EFFECTIVE destination per merchant, from the machine")
    void theTwoIndexesMatchTheMachine() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX payout_destination_one_open_per_merchant")
                .contains("WHERE status IN (" + PayoutDestinationStatus.openSqlValueList() + ")")
                .contains("CREATE UNIQUE INDEX payout_destination_one_effective_per_merchant")
                .contains("WHERE status = 'EFFECTIVE'");
    }

    @Test
    @DisplayName("four-eyes and the cooling-off are constraints, not only code (INV-AUD-04)")
    void fourEyesAndTheCoolingOffAreConstraints() {
        assertThat(migration())
                .contains("CHECK (approved_by IS NULL OR approved_by <> proposed_by)")
                .contains("CHECK (effective_at IS NULL OR effective_at >= cooling_off_until)")
                .contains("CHECK (cooling_off_until IS NULL OR cooling_off_until > approved_at)");
    }

    @Test
    @DisplayName("the reference and suffix shapes are the domain types' own")
    void theShapesAreTheDomainTypesOwn() {
        assertThat(migration())
                .contains(
                        "CHECK (destination_reference ~ '^" + BankDetailShapes.REFERENCE_CHARSET_REGEX
                                + "$')")
                .contains(
                        "CHECK (destination_reference !~ '^"
                                + BankDetailShapes.DIGITS_AND_SEPARATORS_REGEX + "$')")
                .contains(
                        "CHECK (destination_reference !~ '^"
                                + BankDetailShapes.INTERNATIONAL_ACCOUNT_REGEX + "$')")
                .contains(
                        "CHECK (display_suffix ~ '^" + PayoutDestination.DISPLAY_SUFFIX_REGEX
                                + "$')");
    }

    @Test
    @DisplayName("the bounds are the aggregate's own constants")
    void theBoundsMatchTheAggregate() {
        assertThat(migration())
                .contains("CHECK (length(proposed_by) BETWEEN 1 AND "
                        + PayoutDestination.MAX_ACTOR_LENGTH + ")")
                .contains("CHECK (length(proposal_reason) BETWEEN 1 AND "
                        + PayoutDestination.MAX_REASON_LENGTH + ")")
                .contains("[A-Za-z0-9_-]{1," + PayoutDestinationReference.MAX_LENGTH + "}");
    }

    @Test
    @DisplayName("no balance or amount column exists here (INV-MER-02)")
    void noBalanceColumnExists() {
        // Comments and COMMENT ON prose stripped first: the claim is about COLUMNS.
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
    @DisplayName("the grants are narrowed: no DELETE, UPDATE on the lifecycle columns only")
    void theGrantsAreNarrowed() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON merchant.payout_destination TO finapp_app")
                .contains(
                        "GRANT UPDATE (status, approved_by, approved_at, cooling_off_until,"
                                + " effective_at, superseded_at, ended_by, ended_at) ON"
                                + " merchant.payout_destination TO finapp_app")
                .contains("GRANT SELECT, INSERT ON merchant.payout_destination_event TO finapp_app")
                // A statement granting DELETE (or everything), not the prose that says why not.
                .doesNotContainPattern("GRANT[^;]*(DELETE|ALL)")
                .doesNotContain("REFERENCES party.")
                .doesNotContain("REFERENCES ledger.");
    }

    private static String migration() {
        try (InputStream in =
                PayoutDestinationMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
