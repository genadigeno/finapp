package com.finapp.fx;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
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

/** Shared builders for the fx policy suites (`P9-TSK-007`). */
final class FxPolicyFixtures {

    static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    static final String PROVIDER = "fx-sim-a";
    static final Set<String> DECLARED = Set.of(PROVIDER);

    private FxPolicyFixtures() {}

    static PricingPolicyAdministration administration() {
        return new PricingPolicyAdministration(
                new JdbcPricingPolicyStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, DECLARED);
    }

    static FxAvailability availability() {
        return new FxAvailability(new JdbcAvailabilityStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS);
    }

    static Actor controller() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** One EUR-USD conversion pair at O7's terms. */
    static PolicyPair eurUsd() {
        return pair("EUR", "USD", "0.003500", "0.001500", 6);
    }

    static PolicyPair pair(String source, String destination, String spread, String markup, int scale) {
        CurrencyCode s = CurrencyCode.of(source);
        CurrencyCode d = CurrencyCode.of(destination);
        return new PolicyPair(
                PricingPurpose.CONVERSION,
                List.of(PROVIDER),
                new PricingPair(
                        s, d, Margin.of(spread), Margin.of(markup), scale,
                        RoundingPolicy.TOWARDS_ZERO, RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_EVEN,
                        bounds(s), bounds(d)),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                new BigDecimal("0.015"),
                Duration.ofSeconds(120));
    }

    static NotionalBounds bounds(CurrencyCode currency) {
        return switch (currency.code()) {
            case "JPY" -> new NotionalBounds(money("100", currency), money("7500000", currency));
            case "BHD" -> new NotionalBounds(money("0.500", currency), money("20000.000", currency));
            default -> new NotionalBounds(money("1.00", currency), money("50000.00", currency));
        };
    }

    static Money money(String amount, CurrencyCode currency) {
        return Money.of(new BigDecimal(amount), currency);
    }

    static PricingPolicyProposal proposal(String reason) {
        return new PricingPolicyProposal(List.of(eurUsd()), 5, reason);
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
                            app.prepareStatement("SELECT id FROM fx.pricing_policy_version WHERE status = 'PROPOSED'");
                    ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    administration()
                            .reject(app, PricingPolicyId.of(row.getObject(1, UUID.class)), controller(),
                                    "cleared by the next test case", Instant.now(), correlation());
                }
            }
            app.commit();
        }
    }

    /** Proposes and activates a version through two controllers; returns its id. */
    static PricingPolicyId activated() throws SQLException {
        withdrawPending();
        try (Connection app = application()) {
            PricingPolicyAdministration.Proposed proposed =
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
