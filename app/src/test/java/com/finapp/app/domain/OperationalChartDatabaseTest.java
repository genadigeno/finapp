package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The seeded operational chart against a live PostgreSQL (`P3-TSK-003`): every combination
 * resolves through the application role, the gaps are loud, and the seams stay seams.
 *
 * <p>The per-JVM container ({@code P0-TSK-035}) applies {@code V001}–{@code V003} from scratch,
 * so a missing or malformed seed fails this suite — which is what "a missing seed fails the
 * build" means mechanically.
 */
@Tag("database")
@DisplayName("the seeded operational chart resolves (P3-TSK-003)")
class OperationalChartDatabaseTest {

    private final ChartOfAccounts<Connection> chart =
            new ChartOfAccounts<>(new JdbcLedgerAccountStore());

    @Test
    @DisplayName("every operational purpose resolves in every supported currency")
    void everyCombinationResolves() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            for (AccountPurpose purpose : AccountPurpose.values()) {
                if (purpose.ownerKind() == OwnerKind.CUSTOMER) {
                    continue;
                }
                for (CurrencyCode currency : SupportedCurrencies.ALL) {
                    LedgerAccount account = chart.resolve(app, purpose, currency);
                    assertThat(account.purpose()).isEqualTo(purpose);
                    assertThat(account.currency()).isEqualTo(currency);
                    assertThat(account.ownerKind()).isEqualTo(purpose.ownerKind());
                    assertThat(account.ownerRef()).isEmpty();
                    assertThat(account.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
                    // The stored pair survives the round trip coherently - rehydrate accepted
                    // it, and the normal balance is the type's own derivation.
                    assertThat(account.normalBalance())
                            .isEqualTo(account.accountType().normalBalance());
                }
            }
        }
    }

    @Test
    @DisplayName("a gap is loud: an unseeded currency names itself, never an empty answer")
    void aGapIsLoud() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    chart.resolve(
                                            app,
                                            AccountPurpose.ROUNDING_RESIDUAL,
                                            CurrencyCode.of("JPY")))
                    .as("a supported-looking ask outside the seed is a deployment defect, and"
                            + " a silent empty answer would route a posting nowhere")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ROUNDING_RESIDUAL")
                    .hasMessageContaining("JPY");
        }
    }

    @Test
    @DisplayName("an owned purpose is refused by the operational chart outright")
    void anOwnedPurposeIsRefused() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    chart.resolve(
                                            app,
                                            AccountPurpose.CUSTOMER_WALLET,
                                            CurrencyCode.of("GBP")))
                    .as("a wallet account is resolved by owner; asking the operational chart"
                            + " is a programming error, not a lookup miss")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the seams stay seams: nothing has posted to FX_POSITION or SUSPENSE_UNMATCHED")
    void theSeamsStaySeams() throws Exception {
        // Structurally true today - ledger.journal_line does not exist, so nothing CAN have
        // posted - and SELF-ARMING: the to_regclass guard makes this query live the day
        // P3-TSK-005 creates the table, from which point it asserts that the Phase 8 and
        // Phase 9 seams really are seams (nothing in Phase 3 posts to either; the plan says
        // so in §17, and this is where saying it grows teeth).
        try (Connection app = DatabaseRoles.application()) {
            for (AccountPurpose seam :
                    java.util.List.of(
                            AccountPurpose.FX_POSITION, AccountPurpose.SUSPENSE_UNMATCHED)) {
                for (CurrencyCode currency : SupportedCurrencies.ALL) {
                    UUID accountId = chart.resolve(app, seam, currency).id().value();
                    assertThat(linesReferencing(app, accountId))
                            .as("no journal line may reference the %s seam in Phase 3", seam)
                            .isZero();
                }
            }
        }
    }

    /**
     * Zero when the posting table does not exist yet, by the trigger's own reasoning — and in
     * two statements for the trigger's own reason: PostgreSQL parses a whole statement at
     * prepare time, so a single query naming an absent table fails on the name however the
     * CASE around it would have evaluated. Probe first, count only when the table is real.
     */
    private static long linesReferencing(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement probe =
                        connection.prepareStatement(
                                "SELECT to_regclass('ledger.journal_line')");
                ResultSet exists = probe.executeQuery()) {
            exists.next();
            if (exists.getObject(1) == null) {
                return 0;
            }
        }
        try (PreparedStatement count =
                connection.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_line"
                                + " WHERE ledger_account_id = ?")) {
            count.setObject(1, accountId);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
