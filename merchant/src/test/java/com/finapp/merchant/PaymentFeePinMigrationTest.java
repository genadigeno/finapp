package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V005` and the code cannot drift (`P6-TSK-005`, the {@code P0-TSK-022} pattern) — and the
 * three claims the pin's whole purpose rests on: one per payment, immutable, and no balance.
 */
@DisplayName("payment fee pin migration reconciliation (P6-TSK-005)")
class PaymentFeePinMigrationTest {

    private static final String MIGRATION = "db/migration/merchant/V005__create_payment_fee_pin.sql";

    @Test
    @DisplayName("ONE PIN PER PAYMENT: the intent reference is the primary key")
    void onePinPerPayment() {
        // Not a unique index beside a surrogate key: the intent IS the identity here. A second
        // pin would be a second price for one payment, refused by the key rather than by a
        // read-then-write - so ten instances cannot both win.
        assertThat(migration()).contains("payment_intent_ref      uuid        PRIMARY KEY");
    }

    @Test
    @DisplayName("immutability reuses V004's function rather than declaring a second 'never'")
    void immutabilityIsTheSameFunction() {
        assertThat(migration())
                .as("two functions saying \"never\" would eventually say it differently")
                .contains("BEFORE UPDATE OR DELETE ON merchant.payment_fee_pin")
                .contains("EXECUTE FUNCTION merchant.fee_definitions_are_immutable()")
                .doesNotContain("CREATE FUNCTION");
    }

    @Test
    @DisplayName("no grant confers UPDATE or DELETE on a pin")
    void theGrantsAreInsertAndReadOnly() {
        assertThat(
                        migration()
                                .lines()
                                .map(String::trim)
                                .filter(line -> line.startsWith("GRANT "))
                                .toList())
                .isNotEmpty()
                .allSatisfy(
                        grant ->
                                assertThat(grant)
                                        .doesNotContain("UPDATE")
                                        .doesNotContain("DELETE"));
    }

    @Test
    @DisplayName("the port has no method that could express a repricing - the third rank")
    void thePortCannotExpressAChange() {
        assertThat(
                        Arrays.stream(PaymentFeePinStore.class.getDeclaredMethods())
                                .map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.startsWith("update") || name.startsWith("delete"));
    }

    @Test
    @DisplayName("the gross is MoneyColumns' generated three-column shape, and positive")
    void grossMatchesMoneyColumns() {
        assertThat(migration())
                .contains(MoneyColumns.columnsFor("gross").ddl())
                .contains("CHECK (gross_amount_minor > 0)");
    }

    @Test
    @DisplayName("the version reference is a real foreign key - a pin nobody can reprice is"
            + " the one thing this table exists to prevent")
    void theVersionIsAForeignKey() {
        assertThat(migration())
                .contains("REFERENCES merchant.fee_schedule_version (id)")
                .contains("REFERENCES merchant.merchant (id)")
                .as("no cross-schema FK on the intent reference (ADR-0029)")
                .doesNotContain("REFERENCES payments.");
    }

    @Test
    @DisplayName("this schema still holds no balance (INV-MER-02)")
    void noBalanceColumnAppears() {
        // A pinned gross is what was AGREED, not what is OWED - read over the COLUMN
        // DECLARATIONS, because this file's own prose makes exactly that distinction.
        assertThat(columnDeclarations())
                .doesNotContain("balance")
                .doesNotContain("payable")
                .doesNotContain("owed");
    }

    /**
     * Only the {@code CREATE TABLE} bodies, with {@code --} comments stripped — the column and
     * constraint declarations and nothing else.
     *
     * <p>The first form of the no-balance sweep filtered out lines <em>containing</em>
     * {@code COMMENT ON}, and a {@code COMMENT ON COLUMN} whose text wraps onto a second line
     * defeated it — the sweep read prose that says "not what is owed" as a column that holds
     * what is owed. A guard whose subject is column declarations should read column
     * declarations.
     */
    private static String columnDeclarations() {
        StringBuilder declarations = new StringBuilder();
        boolean inside = false;
        for (String raw : migration().lines().toList()) {
            String line = raw.replaceFirst("--.*$", "");
            if (line.stripLeading().startsWith("CREATE TABLE")) {
                inside = true;
                continue;
            }
            if (inside) {
                if (line.startsWith(");")) {
                    inside = false;
                    continue;
                }
                declarations.append(line).append('\n');
            }
        }
        return declarations.toString().toLowerCase();
    }


    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE merchant.payment_fee_pin");
    }

    private static String migration() {
        try (InputStream migration =
                PaymentFeePinMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
