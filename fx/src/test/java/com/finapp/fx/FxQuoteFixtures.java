package com.finapp.fx;

import static com.finapp.fx.FxPolicyFixtures.IDS;
import static com.finapp.fx.FxPolicyFixtures.application;
import static com.finapp.fx.FxPolicyFixtures.bounds;
import static com.finapp.fx.FxPolicyFixtures.controller;
import static com.finapp.fx.FxPolicyFixtures.correlation;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Shared builders for the quote suites (`P9-TSK-008`). */
final class FxQuoteFixtures {

    static final String A = "test-a";
    static final String B = "test-b";
    static final Set<String> DECLARED = Set.of(A, B, FxPolicyFixtures.PROVIDER);
    static final Duration MINIMUM_WINDOW = Duration.ofSeconds(5);
    private static final Duration CLOCK_WAIT_BOUND = Duration.ofSeconds(60);

    private FxQuoteFixtures() {}

    /** A conversion pair at O7's margins, priced by {@code providers} in order. */
    static PolicyPair pair(String source, String destination, Duration window, Duration coverMargin, String... providers) {
        CurrencyCode s = CurrencyCode.of(source);
        CurrencyCode d = CurrencyCode.of(destination);
        boolean tight = !source.matches("JPY|BHD") && !destination.matches("JPY|BHD");
        return new PolicyPair(
                PricingPurpose.CONVERSION,
                List.of(providers),
                new PricingPair(
                        s, d, Margin.of("0.003500"), Margin.of("0.001500"), source.equals("JPY") ? 10 : 6,
                        RoundingPolicy.TOWARDS_ZERO, RoundingPolicy.HALF_EVEN, RoundingPolicy.HALF_EVEN,
                        bounds(s), bounds(d)),
                window,
                coverMargin,
                new BigDecimal(tight ? "0.015" : "0.03"),
                Duration.ofSeconds(120));
    }

    /** EUR-USD and USD-EUR, providers A then B. */
    static List<PolicyPair> eurUsdBothWays(Duration window, Duration coverMargin) {
        return List.of(pair("EUR", "USD", window, coverMargin, A, B), pair("USD", "EUR", window, coverMargin, A, B));
    }

    static PricingPolicyAdministration administration() {
        return new PricingPolicyAdministration(
                new JdbcPricingPolicyStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, DECLARED);
    }

    /** Proposes and activates a version of {@code pairs} through two controllers; returns its id. */
    static PricingPolicyId activate(List<PolicyPair> pairs, int openQuoteCap) throws SQLException {
        FxPolicyFixtures.withdrawPending();
        try (Connection app = application()) {
            PricingPolicyAdministration.Proposed proposed = administration().propose(
                    app, new PricingPolicyProposal(pairs, openQuoteCap, "quote suite version"), controller(),
                    Instant.now(), correlation());
            administration().approve(app, proposed.id(), controller(), "quote suite approval", Instant.now(), correlation());
            app.commit();
            return proposed.id();
        }
    }

    /** Records a fresh reference observation for a canonical pair. */
    static void reference(String base, String quote, String rate) throws SQLException {
        try (Connection app = application()) {
            new JdbcRateSnapshotStore().record(
                    app, ReferenceSourceDeclaration.SOURCE,
                    new RateObservation(ExchangeRate.of(CurrencyCode.of(base), CurrencyCode.of(quote), new BigDecimal(rate)),
                            Instant.now()),
                    UUID.randomUUID());
            app.commit();
        }
    }

    static FxProviders providers(FakeFxProvider... fakes) {
        return new FxProviders(Arrays.stream(fakes)
                .map(fake -> new FxProviders.Composed(FakeFxProvider.declaration(fake.code()), fake))
                .toList());
    }

    static FxAvailability availability() {
        return new FxAvailability(new JdbcAvailabilityStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS);
    }

    static QuoteIssuance issuance(FxProviders providers, Clock clock) {
        return issuance(providers, clock, new JdbcOutboxWriter());
    }

    /** The issuance with its own outbox writer - the failure-injection seam. */
    static QuoteIssuance issuance(FxProviders providers, Clock clock,
            com.finapp.platform.outbox.OutboxWriter<Connection> outbox) {
        return new QuoteIssuance(
                new JdbcPricingPolicyStore(IDS), availability(), new JdbcQuoteStore(IDS), new JdbcRateSnapshotStore(),
                providers, new JdbcFxProviderEvidenceStore(new FxEvidenceCipher(new byte[32], 1, new java.security.SecureRandom()), IDS),
                everyoneActive(), outbox, IDS, clock);
    }

    /** Every party an active customer of itself; the conversion's wallet methods are not asked here. */
    static ConversionParticipants everyoneActive() {
        return new ConversionParticipants() {
            @Override
            public Optional<UUID> activeCustomer(Connection unitOfWork, UUID partyId) {
                return Optional.of(partyId);
            }

            @Override
            public Optional<com.finapp.ledger.LedgerAccountId> wallet(Connection unitOfWork, UUID customerId,
                    CurrencyCode currency) {
                throw new UnsupportedOperationException("the quote suites convert nothing");
            }

            @Override
            public Optional<com.finapp.ledger.LedgerAccountId> openIfAbsent(Connection unitOfWork, UUID customerId,
                    CurrencyCode currency) {
                throw new UnsupportedOperationException("the quote suites convert nothing");
            }
        };
    }

    static QuoteLifecycle lifecycle() {
        return new QuoteLifecycle(new JdbcQuoteStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS);
    }

    static Actor customer(UUID party) {
        return new Actor(party.toString(), ActorType.CUSTOMER);
    }

    static Money money(String amount, String currency) {
        return Money.of(new BigDecimal(amount), CurrencyCode.of(currency));
    }

    /**
     * The desk's three steps without the idempotency record: claim (committed), the wire, issue
     * (committed - a refusal commits the steps and evidence, then is rethrown).
     */
    static QuoteIssuance.Issued issue(QuoteIssuance issuance, UUID owner, String claimKey, String source,
            String destination, FixedSide side, String amount) throws SQLException {
        QuoteIssuance.Claimed claimed;
        Money fixed = money(amount, side == FixedSide.FIXED_SOURCE ? source : destination);
        try (Connection app = application()) {
            try {
                claimed = issuance.claim(app, claimKey, new QuoteIssuance.QuoteRequest(owner, CurrencyCode.of(source),
                        CurrencyCode.of(destination), side, fixed), correlation());
                app.commit();
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
        QuoteIssuance.Sourced sourced = issuance.source(claimed);
        try (Connection app = application()) {
            try {
                QuoteIssuance.Issued issued = issuance.issue(app, claimed, sourced, customer(owner), correlation());
                app.commit();
                return issued;
            } catch (QuoteRefusal.Refused refused) {
                app.commit();
                throw refused;
            } catch (RuntimeException failed) {
                app.rollback();
                throw failed;
            }
        }
    }

    /** One expiry page in the platform's own scope, as the schedule runs it. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    static List<QuoteStore.ExpiredRow> expirePage(Connection unitOfWork, int limit) {
        try (com.finapp.platform.security.SecurityContext.Scope system =
                com.finapp.platform.security.SecurityContext.enterSystem()) {
            return lifecycle().expirePage(unitOfWork, limit, Instant.now(),
                    com.finapp.platform.security.SecurityContext.require());
        }
    }

    static String claim() {
        return "fx.quote:CUSTOMER:test|" + UUID.randomUUID();
    }

    /**
     * Waits until the database's own clock has reached {@code instant} - the clock every lapse is
     * judged on ({@code expires_at <= statement_timestamp()}) and {@code expires_at} is stamped from.
     *
     * <p>The JVM's clock is not that clock: the container runs in a VM whose clock drifts from the
     * host's (measured 565 ms behind on 2026-10-05), so a sleep timed on {@link Instant#now()} can
     * wake before the quote has lapsed where it is judged. Bounded: past {@link #CLOCK_WAIT_BOUND}
     * the case fails rather than hangs.
     */
    static void awaitDatabaseClock(Instant instant) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + CLOCK_WAIT_BOUND.toNanos();
        try (Connection probe = application();
                PreparedStatement remaining = probe.prepareStatement(
                        "SELECT statement_timestamp() >= ?,"
                                + " CAST(CEIL(EXTRACT(EPOCH FROM (? - statement_timestamp())) * 1000) AS bigint)")) {
            remaining.setObject(1, instant.atOffset(ZoneOffset.UTC));
            remaining.setObject(2, instant.atOffset(ZoneOffset.UTC));
            while (true) {
                boolean reached;
                long millis;
                try (ResultSet row = remaining.executeQuery()) {
                    row.next();
                    reached = row.getBoolean(1);
                    millis = row.getLong(2);
                }
                probe.rollback();
                if (reached) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the database clock did not reach " + instant + " within "
                            + CLOCK_WAIT_BOUND + " (" + millis + " ms short)");
                }
                Thread.sleep(Math.max(1, millis));
            }
        }
    }
}
