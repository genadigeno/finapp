package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hold schema and its one-definition sources agree (`P3-TSK-015` — the
 * {@code JournalEntryMigrationTest} discipline): enum value lists, the {@code MoneyColumns}
 * monetary shape, and the layers that carry {@code INV-LIFE-04} and {@code INV-BAL-04}'s
 * representable halves at the schema.
 */
@DisplayName("the hold schema and its definitions agree (P3-TSK-015)")
class HoldMigrationTest {

    private static final String MIGRATION = "db/migration/ledger/V008__create_hold.sql";

    @Test
    @DisplayName("the status CHECK lists exactly the values the enum declares")
    void theStatusListMatchesItsEnum() {
        assertThat(migration())
                .contains("CHECK (status IN (" + HoldStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the monetary columns are MoneyColumns' generated shape, verbatim")
    void theMonetaryShapeIsGenerated() {
        assertThat(migration())
                .contains(
                        new MoneyColumns.ColumnNames("amount_minor", "currency", "scale")
                                .ddl());
    }

    @Test
    @DisplayName("the representable halves are CHECKs: positivity, coherence, ordering")
    void theRepresentableHalvesAreChecks() {
        assertThat(migration())
                .as("a hold's amount is strictly positive - the domain's rule restated where"
                        + " the domain cannot reach")
                .contains("CHECK (amount_minor > 0)")
                .as("the status and the release instant are one fact for every writer")
                .contains("CHECK ((status = 'ACTIVE') = (released_at IS NULL))")
                .contains("CHECK (released_at IS NULL OR released_at >= placed_at)");
    }

    @Test
    @DisplayName("the currency is bound to the account's by composite FK - the V005 mechanism")
    void theCurrencyIsBoundToTheAccount() {
        assertThat(migration())
                .contains("FOREIGN KEY (ledger_account_id, currency)")
                .contains("REFERENCES ledger.ledger_account (id, currency)");
    }

    @Test
    @DisplayName("RELEASED is terminal for every writer - the freeze trigger exists")
    void theFreezeTriggerBindsEveryWriter() {
        assertThat(migration())
                .contains("CREATE TRIGGER hold_permits_only_release")
                .contains("BEFORE UPDATE ON ledger.hold");
    }

    @Test
    @DisplayName("the grants are the plan's own: SELECT, INSERT, UPDATE (status, released_at)")
    void theGrantsAreThePlans() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON ledger.hold TO finapp_app")
                .contains("GRANT UPDATE (status, released_at) ON ledger.hold TO finapp_app")
                .doesNotContain("GRANT DELETE")
                .doesNotContain("GRANT ALL");
    }

    private static String migration() {
        try (InputStream migration =
                HoldMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
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
