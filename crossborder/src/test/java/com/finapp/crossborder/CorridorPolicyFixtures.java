package com.finapp.crossborder;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared builders for the crossborder corridor suites (`P9-TSK-015`). */
final class CorridorPolicyFixtures {

    static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    static final String RAIL = "corridor-sim-a";

    /** The build's corridor rails as the suites see them: corridor-sim-a's three destinations. */
    static final CorridorDirectory DIRECTORY = () -> Set.of(new CorridorDirectory.DeclaredRail(RAIL, Set.of(
            new CorridorDirectory.Coverage(CountryCode.of("US"), CurrencyCode.of("USD")),
            new CorridorDirectory.Coverage(CountryCode.of("JP"), CurrencyCode.of("JPY")),
            new CorridorDirectory.Coverage(CountryCode.of("BH"), CurrencyCode.of("BHD")))));

    private CorridorPolicyFixtures() {}

    static CorridorPolicyAdministration administration() {
        return administration(DIRECTORY);
    }

    static CorridorPolicyAdministration administration(CorridorDirectory directory) {
        return new CorridorPolicyAdministration(
                new JdbcCorridorPolicyStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, directory);
    }

    static CorridorAvailability availability() {
        return new CorridorAvailability(new JdbcCorridorAvailabilityStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS);
    }

    static Actor controller() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** O7's EUR -> USD/US corridor: EUR 2.50 + 0 bps, at most USD 10,000.00, screening valid 7 days. */
    static CorridorTerms eurUsdUs() {
        return terms("EUR", "USD", "US", "2.50", "10000.00");
    }

    static CorridorTerms terms(String source, String destination, String country, String fee, String maximum) {
        CurrencyCode s = CurrencyCode.of(source);
        CurrencyCode d = CurrencyCode.of(destination);
        return new CorridorTerms(
                new CorridorKey(s, d, CountryCode.of(country)),
                List.of(RAIL),
                Money.of(new BigDecimal(fee), s),
                BigDecimal.ZERO,
                RoundingPolicy.HALF_EVEN,
                Money.of(new BigDecimal(maximum), d),
                Duration.ofDays(7),
                Duration.ofDays(1),
                Set.of(RequiredData.BENEFICIARY_NAME, RequiredData.ENTITY_TYPE));
    }

    static CorridorPolicyProposal proposal(String reason) {
        return new CorridorPolicyProposal(List.of(eurUsdUs()), reason);
    }

    static Connection application() throws SQLException {
        Connection app = DatabaseRoles.application();
        app.setAutoCommit(false);
        return app;
    }

    /** Withdraws any proposal another case left pending, so every case starts free to propose. */
    static void withdrawPending() throws SQLException {
        try (Connection app = application()) {
            try (PreparedStatement select =
                            app.prepareStatement("SELECT id FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'");
                    ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    administration().reject(app, CorridorPolicyId.of(row.getObject(1, UUID.class)), controller(),
                            "cleared by the next test case", Instant.now(), correlation());
                }
            }
            app.commit();
        }
    }

    /** Proposes and activates a version through two controllers; returns its id. */
    static CorridorPolicyId activated() throws SQLException {
        withdrawPending();
        try (Connection app = application()) {
            CorridorPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("fixture version"), controller(), Instant.now(), correlation());
            administration().approve(app, proposed.id(), controller(), "fixture approval", Instant.now(), correlation());
            app.commit();
            return proposed.id();
        }
    }

    static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getString(1);
        }
    }
}
