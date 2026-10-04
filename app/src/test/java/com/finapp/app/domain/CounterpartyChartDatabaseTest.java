package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.settlement.CounterpartyChartGuard;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.CounterpartyClearing;
import com.finapp.ledger.CounterpartyKind;
import com.finapp.ledger.JdbcCounterpartyStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.OwnerKind;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The counterparty registry and its accounts against a live PostgreSQL (`P9-TSK-010`, ADR-0078
 * sections 2-5; {@code INV-RAIL-04}, {@code INV-LED-04}): the reference trigger refuses an
 * unregistered counterparty for every writer and admits a registered one, the registry is
 * append-only at both ranks, the generated rules refuse each incoherent account alone, and the
 * keyed resolution and the startup guard read the chart they find.
 *
 * <p>The per-JVM container is shared and the registry is append-only, so every counterparty
 * planted here carries a unique {@code cp-...} code - never {@code fx-sim-a}, which ledger `V022`
 * (`P9-TSK-011`) seeds - and its accounts sit on {@code FX_PROVIDER_CLEARING}, which no proof
 * reads until that purpose joins the reconciled positions with its source.
 */
@Tag("database")
@DisplayName("the counterparty chart holds at the database (P9-TSK-010, ADR-0078)")
class CounterpartyChartDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private final ChartOfAccounts<Connection> chart = new ChartOfAccounts<>(new JdbcLedgerAccountStore());

    @Test
    @DisplayName("the trigger refuses a COUNTERPARTY account naming no registered counterparty, by raw SQL")
    void anUnregisteredCounterpartyIsRefused() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            UUID stranger = IDS.next();
            assertThatThrownBy(() -> insertAccount(app, "COUNTERPARTY", stranger, "FX_PROVIDER_CLEARING", "EUR"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("names a registered counterparty")
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23503"));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> insertAccount(migrator, "COUNTERPARTY", IDS.next(), "FX_PROVIDER_CLEARING", "EUR"))
                    .as("for every writer, the schema owner included")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("names a registered counterparty");
        }
    }

    @Test
    @DisplayName("a registered counterparty's account is admitted, resolved by its code and found by the guard;"
            + " every gap is refused by name")
    void aRegisteredCounterpartyResolves() throws Exception {
        String code = uniqueCode();
        UUID counterparty = IDS.next();
        UUID account = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            registerCounterparty(app, counterparty, code, "FX_PROVIDER");
            insertAccount(app, account, "COUNTERPARTY", counterparty, "FX_PROVIDER_CLEARING", "EUR");

            assertThat(new JdbcCounterpartyStore().findByCode(app, code).orElseThrow().id()).isEqualTo(counterparty);
            LedgerAccount resolved = chart.resolve(app, AccountPurpose.FX_PROVIDER_CLEARING, code, EUR);
            assertThat(resolved.id().value()).isEqualTo(account);
            assertThat(resolved.ownerKind()).isEqualTo(OwnerKind.COUNTERPARTY);
            assertThat(resolved.ownerRef()).contains(counterparty);
            assertThat(new JdbcLedgerAccountStore().findAllOfPurpose(app, AccountPurpose.FX_PROVIDER_CLEARING))
                    .extracting(found -> found.id().value())
                    .contains(account);
            assertThatThrownBy(() -> chart.resolve(app, AccountPurpose.FX_PROVIDER_CLEARING, code, USD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(code);
            assertThatThrownBy(() -> chart.resolve(app, AccountPurpose.FX_PROVIDER_CLEARING, uniqueCode(), EUR))
                    .as("another counterparty's position is not this one's")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(new JdbcLedgerAccountStore().findOperational(app, AccountPurpose.FX_PROVIDER_CLEARING, EUR))
                    .as("there is no shared FX provider clearing")
                    .isEmpty();
        }

        CounterpartyChartGuard.verify(
                List.of(clearing(code, CounterpartyKind.FX_PROVIDER, Set.of(EUR))), DatabaseRoles::application);
        assertThatThrownBy(() -> CounterpartyChartGuard.verify(
                        List.of(clearing(code, CounterpartyKind.FX_PROVIDER, Set.of(EUR, USD))),
                        DatabaseRoles::application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("settles USD");
        assertThatThrownBy(() -> CounterpartyChartGuard.verify(
                        List.of(clearing(code, CounterpartyKind.CORRIDOR_PROVIDER, Set.of(EUR))),
                        DatabaseRoles::application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registered as FX_PROVIDER");
        String undeclared = uniqueCode();
        assertThatThrownBy(() -> CounterpartyChartGuard.verify(
                        List.of(clearing(undeclared, CounterpartyKind.FX_PROVIDER, Set.of(EUR))),
                        DatabaseRoles::application))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'" + undeclared + "' is declared but has no registry row");
    }

    @Test
    @DisplayName("an unreachable database is not a finding: the guard defers to the per-call refusal")
    void anUnreachableDatabaseDoesNotRefuse() {
        assertThatCode(() -> CounterpartyChartGuard.verify(
                        List.of(clearing("cp-unreachable", CounterpartyKind.FX_PROVIDER, Set.of(EUR))),
                        () -> {
                            throw new SQLException("down", "08001");
                        }))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the registry is append-only at both ranks: the application holds no UPDATE or DELETE,"
            + " and the trigger refuses the schema owner")
    void theRegistryIsAppendOnly() throws Exception {
        String code = uniqueCode();
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            registerCounterparty(app, id, code, "FX_PROVIDER");
            assertThatThrownBy(() -> execute(app, "UPDATE ledger.counterparty SET code = 'cp-renamed' WHERE id = '" + id + "'"))
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> execute(app, "DELETE FROM ledger.counterparty WHERE id = '" + id + "'"))
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
        }
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> execute(migrator, "UPDATE ledger.counterparty SET kind = 'CORRIDOR_PROVIDER' WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("registered once and never changed");
            assertThatThrownBy(() -> execute(migrator, "DELETE FROM ledger.counterparty WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("registered once and never changed");
        }
    }

    @Test
    @DisplayName("the registry's shape and the chart's rules each refuse alone: a malformed code, an unknown"
            + " kind, a second row of one code; a COUNTERPARTY account with no owner, the counterparty purpose"
            + " as a shared account")
    void eachRuleRefusesAlone() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(() -> registerCounterparty(app, IDS.next(), "FX SIM", "FX_PROVIDER"))
                    .satisfies(failure -> assertThat(failure.getMessage()).contains("counterparty_code_is_shaped"));
            assertThatThrownBy(() -> registerCounterparty(app, IDS.next(), uniqueCode(), "BANK"))
                    .satisfies(failure -> assertThat(failure.getMessage()).contains("counterparty_kind_is_known"));
            String code = uniqueCode();
            registerCounterparty(app, IDS.next(), code, "FX_PROVIDER");
            assertThatThrownBy(() -> registerCounterparty(app, IDS.next(), code, "FX_PROVIDER"))
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23505"));

            assertThatThrownBy(() -> insertAccount(app, "COUNTERPARTY", null, "FX_PROVIDER_CLEARING", "EUR"))
                    .satisfies(failure -> assertThat(failure.getMessage()).contains("ledger_account_owner_ref_matches_kind"));
            assertThatThrownBy(() -> insertAccount(app, "OPERATIONAL", null, "FX_PROVIDER_CLEARING", "EUR"))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("ledger_account_owner_kind_matches_purpose"));
        }
    }

    // -----------------------------------------------------------------

    private static CounterpartyClearing clearing(String code, CounterpartyKind kind, Set<CurrencyCode> currencies) {
        return new CounterpartyClearing(code, kind, AccountPurpose.FX_PROVIDER_CLEARING, currencies);
    }

    private static String uniqueCode() {
        return "cp-" + IDS.next().toString().substring(24);
    }

    private static void registerCounterparty(Connection connection, UUID id, String code, String kind)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ledger.counterparty (id, code, kind) VALUES (?, ?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, code);
            insert.setString(3, kind);
            insert.executeUpdate();
        }
    }

    private static void insertAccount(Connection connection, String ownerKind, UUID ownerRef, String purpose,
            String currency) throws SQLException {
        insertAccount(connection, IDS.next(), ownerKind, ownerRef, purpose, currency);
    }

    private static void insertAccount(Connection connection, UUID id, String ownerKind, UUID ownerRef,
            String purpose, String currency) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ledger.ledger_account (id, account_type, normal_balance, currency, owner_kind,"
                        + " owner_ref, purpose, gl_code, status, created_at, status_changed_at)"
                        + " VALUES (?, 'ASSET', 'DEBIT', ?, ?, ?, ?, NULL, 'ACTIVE', now(), now())")) {
            insert.setObject(1, id);
            insert.setString(2, currency);
            insert.setString(3, ownerKind);
            insert.setObject(4, ownerRef);
            insert.setString(5, purpose);
            insert.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
