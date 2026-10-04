package com.finapp.app.fx;

import com.finapp.fx.Margin;
import com.finapp.fx.NotionalBounds;
import com.finapp.fx.PolicyPair;
import com.finapp.fx.PricingPair;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyProposal;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.PricingPurpose;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The pricing version an FX suite needs, made ACTIVE through the four-eyes administration -
 * idempotent, so suites sharing one database each find their own version active whatever order
 * they run in (`P9-TSK-009`): v1 per O7, or the race suites' short-window variant.
 */
final class FxTestPolicy {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    /** v1 (O7): forty rows, a 30 s conversion window, five open quotes. */
    static final Wanted V1 = new Wanted(PricingPolicyV1.pairs().stream().map(FxTestPolicy::pair).toList(), 5);

    /** The race suites': EUR-USD both ways, a 5 s window, no cover margin, a hundred open quotes. */
    static final Wanted RACE = new Wanted(
            PricingPolicyV1.pairs().stream()
                    .filter(p -> p.purpose().equals("CONVERSION")
                            && (p.source() + p.destination()).matches("EURUSD|USDEUR"))
                    .map(FxTestPolicy::pair)
                    .map(p -> new PolicyPair(p.purpose(), p.providers(), p.pricing(), Duration.ofSeconds(5),
                            Duration.ZERO, p.band(), p.referenceMaxAge()))
                    .toList(),
            100);

    /** A version's shape: its rows and its open-quote cap. */
    record Wanted(List<PolicyPair> pairs, int openQuoteCap) {

        boolean isActive(PricingPolicyStore.VersionView active) {
            return active.row().openQuoteCap() == openQuoteCap
                    && active.pairs().size() == pairs.size()
                    && active.pairs().stream().allMatch(stored -> pairs.stream().anyMatch(wanted ->
                            wanted.purpose() == stored.purpose()
                                    && wanted.pricing().source().equals(stored.pricing().source())
                                    && wanted.pricing().destination().equals(stored.pricing().destination())
                                    && wanted.window().equals(stored.window())
                                    && wanted.coverMargin().equals(stored.coverMargin())));
        }
    }

    private FxTestPolicy() {}

    /** Makes {@code wanted} the ACTIVE version unless it already is - two controllers, four eyes. */
    static void ensure(PricingPolicyAdministration administration, PricingPolicyStore store, Wanted wanted)
            throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            Optional<PricingPolicyStore.VersionView> active = store.active(app);
            if (active.isPresent() && wanted.isActive(active.get())) {
                app.rollback();
                return;
            }
            try (PreparedStatement select = app.prepareStatement(
                            "SELECT id FROM fx.pricing_policy_version WHERE status = 'PROPOSED'");
                    ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    administration.reject(app, com.finapp.fx.PricingPolicyId.of(row.getObject(1, UUID.class)),
                            controller(), "cleared by an FX suite", Instant.now(), CorrelationId.generate(IDS));
                }
            }
            PricingPolicyAdministration.Proposed proposed = administration.propose(
                    app, new PricingPolicyProposal(wanted.pairs(), wanted.openQuoteCap(), "an FX suite's version"),
                    controller(), Instant.now(), CorrelationId.generate(IDS));
            administration.approve(app, proposed.id(), controller(), "an FX suite's approval", Instant.now(),
                    CorrelationId.generate(IDS));
            app.commit();
        }
    }

    private static Actor controller() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    /** A v1 request row in the domain's words. */
    static PolicyPair pair(FxAdministrationController.PairRequest r) {
        CurrencyCode source = CurrencyCode.of(r.source());
        CurrencyCode destination = CurrencyCode.of(r.destination());
        return new PolicyPair(
                PricingPurpose.valueOf(r.purpose()),
                r.providers(),
                new PricingPair(source, destination, Margin.of(r.spread()), Margin.of(r.markup()), r.rateScale(),
                        RoundingPolicy.valueOf(r.rateRounding()), RoundingPolicy.valueOf(r.amountRounding()),
                        RoundingPolicy.valueOf(r.marginRounding()),
                        new NotionalBounds(Money.of(new BigDecimal(r.sourceMinimum()), source),
                                Money.of(new BigDecimal(r.sourceMaximum()), source)),
                        new NotionalBounds(Money.of(new BigDecimal(r.destinationMinimum()), destination),
                                Money.of(new BigDecimal(r.destinationMaximum()), destination))),
                Duration.ofSeconds(r.windowSeconds()),
                Duration.ofSeconds(r.coverMarginSeconds()),
                new BigDecimal(r.band()),
                Duration.ofSeconds(r.referenceMaxAgeSeconds()));
    }
}
