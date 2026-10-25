package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.party.JdbcPartyStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.MonitoredParties;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Risk's account → party join against a real database (`P13-TSK-020`; {@link AccountMonitoredParties}): accounts'
 * {@code customerOf} - the system's read, the owner and nothing else - and party's {@code partyOfCustomer} place an
 * agreement's party; the reverse reads the live customer's agreements with their opening. Written and owed under C5.
 */
@Tag("database")
@DisplayName("risk's account -> party join over the real tables (P13-TSK-020)")
class AccountMonitoredPartiesDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final JdbcCustomerAccountStore agreements = new JdbcCustomerAccountStore();
    private final AccountMonitoredParties join = new AccountMonitoredParties(new JdbcPartyStore(), agreements);

    @Test
    @DisplayName("an agreement is placed behind its customer's party, and a party reads back its agreement; an unknown"
            + " agreement places nothing")
    void theJoinBothWays() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at)"
                    + " VALUES (?, 'PERSON', 'Joined Holder', now() - interval '2 hour')", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                    + " VALUES (?, ?, 'ACTIVE', now() - interval '1 hour', now() - interval '1 hour')", customer, party);
            CustomerAccount agreement = agreements.openOrConverge(app,
                    CustomerAccount.open(IDS, CLOCK, customer, ProductType.WALLET)).account();

            assertThat(agreements.customerOf(app, agreement.id())).contains(customer);
            assertThat(join.partyOfAccount(app, agreement.id().value())).contains(party);
            assertThat(join.partyOfAccount(app, IDS.next())).isEmpty();
            assertThat(agreements.customerOf(app, CustomerAccountId.of(IDS.next()))).isEmpty();

            MonitoredParties.Accounts accounts = join.accountsOf(app, party);
            assertThat(accounts.ids()).containsExactly(agreement.id().value());
            assertThat(accounts.firstOpenedAt()).isPresent();
            assertThat(join.accountsOf(app, IDS.next())).isEqualTo(MonitoredParties.Accounts.NONE);
        }
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }
}
