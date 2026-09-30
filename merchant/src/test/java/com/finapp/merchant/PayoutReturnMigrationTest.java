package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V008` read as text (`P8-TSK-019`, ADR-0073 §1): the return's money bound to the payout's by
 * a composite foreign key onto a declared unique, one return per payout and per entry, only a
 * {@code COMPLETED} payout returning, append-only for every writer, and {@code SELECT, INSERT}
 * the only grant. The behaviour of each rank is proven against the real schema by
 * {@code PayoutReturnDatabaseTest}; this pins that the ranks are there.
 */
@DisplayName("merchant V008 - the payout return (P8-TSK-019)")
class PayoutReturnMigrationTest {

    private static final String MIGRATION = "db/migration/merchant/V008__create_payout_return.sql";

    @Test
    @DisplayName("the money is the payout's: a composite foreign key onto a declared unique")
    void theMoneyIsBoundToThePayouts() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("ALTER TABLE merchant.merchant_payout ADD CONSTRAINT"
                        + " merchant_payout_money_is_unique UNIQUE (id, amount_minor, currency,"
                        + " scale);")
                .contains("CONSTRAINT payout_return_is_the_payouts_money FOREIGN KEY (payout_id,"
                        + " amount_minor, currency, scale) REFERENCES merchant.merchant_payout (id,"
                        + " amount_minor, currency, scale)");
    }

    @Test
    @DisplayName("one return per payout and per entry, only a COMPLETED payout, append-only, and"
            + " SELECT, INSERT the only grant")
    void theFactIsBornOnce() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("payout_id uuid NOT NULL UNIQUE")
                .contains("journal_entry_id uuid NOT NULL UNIQUE")
                .contains("AND payout.status = '" + MerchantPayoutStatus.COMPLETED.name() + "'")
                .contains("BEFORE INSERT ON merchant.payout_return")
                .contains("BEFORE UPDATE OR DELETE ON merchant.payout_return")
                .contains("GRANT SELECT, INSERT ON merchant.payout_return TO finapp_app;")
                .doesNotContain("GRANT UPDATE")
                .doesNotContain("GRANT DELETE")
                .as("no status column: RECORDED is the fact's only state")
                .doesNotContain(" status text");
        assertThat(MerchantPayoutStatus.COMPLETED.isTerminal())
                .as("the trigger's COMPLETED check never goes stale: the state is terminal")
                .isTrue();
    }

    private static String migration() {
        try (InputStream in =
                PayoutReturnMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }
}
