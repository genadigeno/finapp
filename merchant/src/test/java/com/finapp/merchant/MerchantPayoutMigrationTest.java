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
 * `V007` and the code cannot drift (`P6-TSK-012`, the {@code P0-TSK-022} pattern): the status
 * and failure-reason {@code CHECK}s, every trigger edge and the resolvable set from the enums;
 * the reference shapes from the domain types; the permit rules; and the grants that leave the
 * lifecycle columns as the only movable ones.
 */
@DisplayName("merchant payout migration reconciliation (P6-TSK-012)")
class MerchantPayoutMigrationTest {

    private static final String MIGRATION =
            "db/migration/merchant/V007__create_merchant_payout.sql";

    @Test
    @DisplayName("the status CHECKs are generated from the machine, on all three columns")
    void statusChecksMatchTheEnum() {
        String list = MerchantPayoutStatus.sqlValueList();
        assertThat(migration())
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))")
                .contains("failure_reason IN (" + PayoutFailureReason.sqlValueList() + ")");
    }

    @Test
    @DisplayName("every transition trigger edge is generated from the machine")
    void triggerEdgesMatchTheMachine() {
        String migration = migration();
        for (MerchantPayoutStatus from : MerchantPayoutStatus.values()) {
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
    @DisplayName("the resolvable set drives the sweep's index and the permit's guard")
    void theResolvableSetIsTheEnums() {
        String resolvable = MerchantPayoutStatus.resolvableSqlValueList();
        assertThat(migration())
                .contains("WHERE status IN (" + resolvable + ")")
                .contains("OLD.status NOT IN (" + resolvable + ")");
    }

    @Test
    @DisplayName("the outcome fields follow the state, and the reason follows the requester")
    void theCoherenceIsConstrained() {
        assertThat(migration())
                .contains("CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))")
                .contains("CHECK ((status = 'COMPLETED') = (provider_reference IS NOT NULL))")
                .contains("CHECK ((requested_by_type = 'MERCHANT') = (reason IS NULL))")
                .contains("CHECK (last_dispatched_at >= created_at)")
                .contains("NEW.last_dispatched_at < OLD.last_dispatched_at");
    }

    @Test
    @DisplayName("the reference shapes are the domain types' own")
    void theShapesAreTheDomainTypesOwn() {
        assertThat(migration())
                .contains("provider_idempotency_reference ~ '^" + PayoutReference.REGEX + "$'")
                .contains("provider_reference ~ '^" + PayoutProviderReference.REGEX + "$'");
    }

    @Test
    @DisplayName("a payout is born DISPATCHED, to its own merchant's EFFECTIVE destination")
    void theDestinationIsBoundForEveryWriter() {
        assertThat(migration())
                .contains("REFERENCES merchant.payout_destination (id, merchant_id)")
                .contains("BEFORE INSERT ON merchant.merchant_payout")
                .contains("destination.status = 'EFFECTIVE'")
                .contains("IF NEW.status <> 'DISPATCHED' THEN");
    }

    @Test
    @DisplayName("no stored balance: the payout is a movement, never a payable")
    void noBalanceColumnExists() {
        assertThat(migration().toLowerCase(java.util.Locale.ROOT))
                .doesNotContainPattern("\\b(balance|payable|available)[a-z_]*\\s+(bigint|numeric)");
    }

    @Test
    @DisplayName("the grants leave only the lifecycle columns movable, and nothing deletable")
    void theGrantsAreNarrowed() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON merchant.merchant_payout TO finapp_app")
                .contains(
                        "GRANT UPDATE (status, failure_reason, provider_reference,"
                                + " last_dispatched_at) ON merchant.merchant_payout TO finapp_app")
                .contains("GRANT SELECT, INSERT ON merchant.merchant_payout_event TO finapp_app")
                .contains("GRANT SELECT, INSERT ON merchant.payout_evidence TO finapp_app")
                .doesNotContainPattern("GRANT[^;]*(DELETE|ALL)");
    }

    private static String migration() {
        try (InputStream migration =
                MerchantPayoutMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
