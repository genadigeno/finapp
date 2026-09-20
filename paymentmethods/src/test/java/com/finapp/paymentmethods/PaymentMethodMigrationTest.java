package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002` and the code cannot drift (`P5-TSK-004`, the {@code P0-TSK-022} pattern — the
 * {@code BeneficiaryMigrationTest} shape verbatim).
 *
 * <p>The generated artefacts, each with one definition: the status {@code CHECK} from
 * {@link PaymentMethodStatus#sqlValueList()}, the <strong>one-live index predicate</strong>
 * from {@link PaymentMethodStatus#sqlTerminalValueList()} — a state added without deciding
 * whether it frees the (party, token) slot either lets a party hold two live rows for one
 * instrument or blocks the successor forever, both silent without this reconciliation — the
 * trigger's edge conditions from {@link PaymentMethodStatus#permittedTransitions()}, and the
 * bounds that mirror the aggregate's own (the token length, the PAN-shape refusal).
 */
@DisplayName("payment method migration reconciliation (P5-TSK-004)")
class PaymentMethodMigrationTest {

    private static final String MIGRATION =
            "db/migration/paymentmethods/V002__create_payment_method.sql";

    @Test
    @DisplayName("the status CHECK is generated from the machine")
    void statusCheckMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (status IN (" + PaymentMethodStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the one-live index predicate is generated from the terminal set")
    void oneLiveIndexPredicateMatchesTheTerminalSet() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX payment_method_one_live_per_party_token")
                .contains(
                        "WHERE status NOT IN ("
                                + PaymentMethodStatus.sqlTerminalValueList()
                                + ")");
    }

    @Test
    @DisplayName("the trigger's edge conditions are generated from permittedTransitions()")
    void triggerEdgesMatchTheMachine() {
        for (PaymentMethodStatus from : PaymentMethodStatus.values()) {
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
    @DisplayName("the coherence pair and the PAN-refusing bounds match the aggregate")
    void coherenceAndBoundsMatchTheAggregate() {
        assertThat(migration())
                .contains("CHECK ((status = 'ACTIVE') = (detached_at IS NULL))")
                .contains("CHECK (length(token_reference) <= " + TokenReference.MAX_LENGTH + ")")
                // INV-PAY-02's DB-CONSTRAINT half: the not-a-PAN rule the domain type carries,
                // restated for every writer - the file's defining property.
                .contains("CHECK (token_reference !~ '^[0-9-]+$')")
                .contains("CHECK (display_suffix ~ '^[0-9]{4}$')")
                .contains(
                        "CHECK (expiry_year BETWEEN "
                                + PaymentMethod.MIN_EXPIRY_YEAR
                                + " AND "
                                + PaymentMethod.MAX_EXPIRY_YEAR
                                + ")");
    }

    @Test
    @DisplayName("the grants are the planned set: the detachment columns and nothing more")
    void grantsAreTheColumnNarrowedSet() {
        assertThat(migration())
                .contains(
                        "GRANT SELECT, INSERT ON paymentmethods.payment_method TO finapp_app;")
                .contains(
                        "GRANT UPDATE (status, detached_at) ON paymentmethods.payment_method"
                                + " TO finapp_app;")
                .doesNotContain("GRANT UPDATE ON paymentmethods")
                .doesNotContain("GRANT DELETE");
    }

    private static String migration() {
        // The classloader, not a path: a module's tests see its resources inside the jar
        // java-library packs (the P2-TSK-004 finding).
        try (InputStream file =
                PaymentMethodMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(file).as("the migration %s must exist", MIGRATION).isNotNull();
            return new String(file.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + MIGRATION, e);
        }
    }
}
