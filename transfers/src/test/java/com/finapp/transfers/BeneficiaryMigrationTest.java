package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V003` and the code cannot drift (`P4-TSK-006`, the {@code P0-TSK-022} pattern).
 *
 * <p>Three generated artefacts, each with one definition: the status {@code CHECK} from
 * {@link BeneficiaryStatus#sqlValueList()}, the <strong>one-live index predicate</strong> from
 * {@link BeneficiaryStatus#sqlTerminalValueList()} — a state added without deciding whether it
 * frees the (party, destination) slot either lets a party hold two live rows for one
 * destination or blocks the successor forever, both silent without this reconciliation (the
 * {@code kyc_case} argument) — and the trigger's edge conditions from
 * {@link BeneficiaryStatus#permittedTransitions()}.
 */
@DisplayName("beneficiary migration reconciliation (P4-TSK-006)")
class BeneficiaryMigrationTest {

    private static final String MIGRATION =
            "db/migration/transfers/V003__create_beneficiary.sql";

    @Test
    @DisplayName("the status CHECK is generated from the machine")
    void statusCheckMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (status IN (" + BeneficiaryStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the one-live index predicate is generated from the terminal set")
    void oneLiveIndexPredicateMatchesTheTerminalSet() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX beneficiary_one_live_per_party_destination")
                .contains(
                        "WHERE status NOT IN ("
                                + BeneficiaryStatus.sqlTerminalValueList()
                                + ")");
    }

    @Test
    @DisplayName("the trigger's edge conditions are generated from permittedTransitions()")
    void triggerEdgesMatchTheMachine() {
        for (BeneficiaryStatus from : BeneficiaryStatus.values()) {
            if (from.isTerminal()) {
                // A terminal state must appear in NO edge condition as a source (INV-LIFE-04).
                assertThat(migration()).doesNotContain("OLD.status = '" + from.name() + "'");
                continue;
            }
            assertThat(migration())
                    .as("the trigger must carry %s's exact edge set", from)
                    .contains(
                            "(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                                    + from.permittedTransitions().stream()
                                            .map(to -> "'" + to.name() + "'")
                                            .collect(Collectors.joining(", "))
                                    + "))");
        }
    }

    @Test
    @DisplayName("the coherence pair and the name bound match the aggregate")
    void coherenceAndBoundsMatchTheAggregate() {
        assertThat(migration())
                .contains("CHECK ((status = 'ACTIVE') = (removed_at IS NULL))")
                .contains(
                        "CHECK (length(display_name) <= "
                                + Beneficiary.MAX_DISPLAY_NAME_LENGTH
                                + ")");
    }

    @Test
    @DisplayName("the grants are the planned set: the removal columns and nothing more")
    void grantsAreTheColumnNarrowedSet() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON transfers.beneficiary TO finapp_app;")
                .contains("GRANT UPDATE (status, removed_at) ON transfers.beneficiary"
                        + " TO finapp_app;")
                .doesNotContain("GRANT UPDATE ON transfers")
                .doesNotContain("GRANT DELETE");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE transfers.beneficiary (");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                BeneficiaryMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
