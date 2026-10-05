package com.finapp.fx;

import static com.finapp.fx.FxPolicyFixtures.application;
import static com.finapp.fx.FxPolicyFixtures.scalar;
import static com.finapp.fx.FxQuoteFixtures.A;
import static com.finapp.fx.FxQuoteFixtures.B;
import static com.finapp.fx.FxQuoteFixtures.activate;
import static com.finapp.fx.FxQuoteFixtures.awaitDatabaseClock;
import static com.finapp.fx.FxQuoteFixtures.claim;
import static com.finapp.fx.FxQuoteFixtures.eurUsdBothWays;
import static com.finapp.fx.FxQuoteFixtures.issuance;
import static com.finapp.fx.FxQuoteFixtures.issue;
import static com.finapp.fx.FxQuoteFixtures.pair;
import static com.finapp.fx.FxQuoteFixtures.providers;
import static com.finapp.fx.FxQuoteFixtures.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Quote issuance against a live PostgreSQL (`P9-TSK-008`; ADR-0075 sections 3-6; INV-FX-02,
 * INV-FX-04, INV-FX-05, INV-FX-07): the frozen plan under the pinned version, the database's
 * window, the cap and its pre-check, failover with every step recorded, fail-closed staleness and
 * the policy activated between the transactions - with an in-memory provider that counts RFQs.
 */
@Tag("database")
@DisplayName("FX quote issuance: claim, wire, insert (P9-TSK-008)")
class QuoteIssuanceDatabaseTest {

    private static final Duration WINDOW = Duration.ofSeconds(30);
    private static final Duration COVER = Duration.ofSeconds(10);

    private final FakeFxProvider a = new FakeFxProvider(A).rate("EUR", "USD", "1.085024").rate("USD", "EUR", "0.921600");
    private final FakeFxProvider b = new FakeFxProvider(B).rate("EUR", "USD", "1.085024").rate("USD", "EUR", "0.921600");

    @BeforeEach
    void freshReference() throws SQLException {
        reference("EUR", "USD", "1.0850000000");
    }

    @Test
    @DisplayName("both sides issue under the pinned terms: O7's worked figures, the plan identity, a 30 s"
            + " database window, the steps, the history and the event")
    void bothSidesIssue() throws SQLException {
        PricingPolicyId version = activate(eurUsdBothWays(WINDOW, COVER), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();

        QuoteStore.QuoteRow sold = issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "1000.00").quote();
        assertThat(sold.status()).isEqualTo(QuoteStatus.ISSUED);
        assertThat(sold.customerSource().toBigDecimal()).isEqualByComparingTo("1000.00");
        assertThat(sold.customerDestination().toBigDecimal()).isEqualByComparingTo("1079.60");
        assertThat(sold.customerRate().value()).isEqualByComparingTo("1.079598");
        assertThat(sold.version()).isEqualTo(version);
        assertThat(Duration.between(sold.issuedAt(), sold.expiresAt())).isEqualTo(WINDOW);

        QuoteStore.QuoteRow bought = issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_DESTINATION, "1000.00").quote();
        assertThat(bought.customerDestination().toBigDecimal()).isEqualByComparingTo("1000.00");
        assertThat(bought.customerSource().toBigDecimal()).isEqualByComparingTo("926.27");

        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT position_destination_minor - customer_destination_minor - margin_minor"
                            + " - residual_minor FROM fx.quote WHERE id = '" + sold.id().value() + "'"))
                    .as("the plan identity, stored")
                    .isEqualTo("0");
            assertThat(scalar(app, "SELECT string_agg(outcome, ',' ORDER BY position, outcome) FROM fx.quote_sourcing_step"
                            + " s JOIN fx.quote q ON q.quote_request_id = s.quote_request_id WHERE q.id = '"
                            + sold.id().value() + "'"))
                    .isEqualTo("CHOSEN,QUOTED");
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote_event WHERE quote_id = '" + sold.id().value()
                            + "' AND to_status = 'ISSUED'"))
                    .isEqualTo("1");
            assertThat(scalar(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxQuoteIssued'"
                            + " AND aggregate_id = '" + sold.id().value() + "'"))
                    .isEqualTo("1");
            assertThat(scalar(app, "SELECT convert_from(payload, 'UTF8') FROM platform.outbox_event WHERE"
                            + " aggregate_id = '" + sold.id().value() + "'"))
                    .as("amounts as minor units with their scale; no rate, no provider reference")
                    .contains("\"destinationMinor\":\"107960\"", "\"destinationScale\":\"2\"")
                    .doesNotContain("1.08", "PQ-");
            app.rollback();
        }
    }

    @Test
    @DisplayName("an owner at the cap is refused before any provider call; ten racers at four live quotes"
            + " issue exactly one (the trigger arbitrates, counted)")
    void theCapHolds() throws Exception {
        activate(eurUsdBothWays(WINDOW, COVER), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00");
        }
        List<QuoteIssuance.Claimed> claims = new ArrayList<>();
        List<QuoteIssuance.Sourced> sourced = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            try (Connection app = application()) {
                claims.add(issuance.claim(app, claim(), new QuoteIssuance.QuoteRequest(owner, CurrencyCode.of("EUR"),
                        CurrencyCode.of("USD"), FixedSide.FIXED_SOURCE, FxQuoteFixtures.money("100.00", "EUR")),
                        FxPolicyFixtures.correlation()));
                app.commit();
            }
            sourced.add(issuance.source(claims.get(i)));
        }
        List<String> outcomes = race(10, index -> {
            try (Connection own = application()) {
                try {
                    issuance.issue(own, claims.get(index), sourced.get(index), FxQuoteFixtures.customer(owner),
                            FxPolicyFixtures.correlation());
                    own.commit();
                    return "issued";
                } catch (QuoteRefusal.Refused refused) {
                    own.commit();
                    return refused.refusal().name();
                }
            }
        });
        assertThat(outcomes.stream().filter("issued"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("TOO_MANY_OPEN_QUOTES"::equals).count()).isEqualTo(9);
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote WHERE owner_party_id = '" + owner + "' AND status = 'ISSUED'"))
                    .as("counted in the table")
                    .isEqualTo("5");
            app.rollback();
        }
        int before = a.requests() + b.requests();
        assertThatThrownBy(() -> issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00"))
                .isInstanceOf(QuoteRefusal.Refused.class)
                .extracting(e -> ((QuoteRefusal.Refused) e).refusal())
                .isEqualTo(QuoteRefusal.TOO_MANY_OPEN_QUOTES);
        assertThat(a.requests() + b.requests()).as("no provider was asked").isEqualTo(before);
    }

    @Test
    @DisplayName("the cap counts only live quotes: a lapsed quote the sweeper has not written does not count")
    void aLapsedQuoteDoesNotCount() throws Exception {
        activate(eurUsdBothWays(FxQuoteFixtures.MINIMUM_WINDOW, Duration.ZERO), 1);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        QuoteStore.QuoteRow first = issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00").quote();
        assertThatThrownBy(() -> issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00"))
                .isInstanceOf(QuoteRefusal.Refused.class);
        awaitDatabaseClock(first.expiresAt().plusMillis(500));
        reference("EUR", "USD", "1.0850000000");
        assertThat(issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00").quote().status())
                .isEqualTo(QuoteStatus.ISSUED);
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT status FROM fx.quote WHERE id = '" + first.id().value() + "'"))
                    .as("still ISSUED in the table - unswept, and not counted")
                    .isEqualTo("ISSUED");
            app.rollback();
        }
    }

    @Test
    @DisplayName("a version activated between the claim and the insert is PolicyStale, nothing issued; an issued"
            + " quote keeps the version it was priced under")
    void thePinnedVersionPrices() throws Exception {
        PricingPolicyId first = activate(eurUsdBothWays(WINDOW, COVER), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        QuoteStore.QuoteRow priced = issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "500.00").quote();

        QuoteIssuance.Claimed claimed;
        try (Connection app = application()) {
            claimed = issuance.claim(app, claim(), new QuoteIssuance.QuoteRequest(owner, CurrencyCode.of("EUR"),
                    CurrencyCode.of("USD"), FixedSide.FIXED_SOURCE, FxQuoteFixtures.money("500.00", "EUR")),
                    FxPolicyFixtures.correlation());
            app.commit();
        }
        QuoteIssuance.Sourced sourced = issuance.source(claimed);
        PricingPolicyId second = activate(eurUsdBothWays(WINDOW, COVER), 5);
        try (Connection app = application()) {
            assertThatThrownBy(() -> issuance.issue(app, claimed, sourced, FxQuoteFixtures.customer(owner),
                            FxPolicyFixtures.correlation()))
                    .isInstanceOf(QuoteRefusal.Refused.class)
                    .extracting(e -> ((QuoteRefusal.Refused) e).refusal())
                    .isEqualTo(QuoteRefusal.POLICY_STALE);
            app.commit();
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote WHERE quote_request_id = '" + claimed.request().id() + "'"))
                    .isEqualTo("0");
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote_sourcing_step WHERE quote_request_id = '"
                            + claimed.request().id() + "'"))
                    .as("the steps of a refused attempt are kept")
                    .isEqualTo("2");
            assertThat(scalar(app, "SELECT pricing_policy_version_id FROM fx.quote WHERE id = '" + priced.id().value() + "'"))
                    .isEqualTo(first.value().toString())
                    .isNotEqualTo(second.value().toString());
            app.rollback();
        }
    }

    @Test
    @DisplayName("failover: an indeterminate first provider, the second chosen - every step recorded and the"
            + " choice recomputable from them; a re-claim of the same key converges on the same quote")
    void failoverIsRecorded() throws SQLException {
        activate(eurUsdBothWays(WINDOW, COVER), 5);
        a.mode(FakeFxProvider.Mode.INDETERMINATE);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        String key = claim();
        QuoteStore.QuoteRow quote = issue(issuance, owner, key, "EUR", "USD", FixedSide.FIXED_SOURCE, "250.00").quote();
        try (Connection app = application()) {
            UUID request = UUID.fromString(scalar(app, "SELECT quote_request_id FROM fx.quote WHERE id = '" + quote.id().value() + "'"));
            List<QuoteStore.Step> steps = new JdbcQuoteStore(FxPolicyFixtures.IDS).steps(app, request, 1);
            assertThat(steps).extracting(QuoteStore.Step::outcome).containsExactly(
                    QuoteStore.StepOutcome.INDETERMINATE, QuoteStore.StepOutcome.QUOTED, QuoteStore.StepOutcome.CHOSEN);
            assertThat(steps.get(0).detail()).contains("TIMEOUT");
            Optional<String> recomputed = steps.stream()
                    .filter(s -> s.outcome() == QuoteStore.StepOutcome.QUOTED && s.detail().isEmpty())
                    .map(QuoteStore.Step::providerCode)
                    .findFirst();
            assertThat(recomputed).as("the first coherent, plausible quote in order").contains(B);
            assertThat(scalar(app, "SELECT provider_code FROM fx.quote WHERE id = '" + quote.id().value() + "'")).isEqualTo(B);
            assertThat(scalar(app, "SELECT count(*) FROM fx.fx_provider_evidence WHERE provider_code = '" + B
                            + "' AND client_reference = (SELECT reference FROM fx.quote_request WHERE id = '" + request + "')"))
                    .as("the chosen answer retained as evidence under QR")
                    .isEqualTo("1");
            app.rollback();
        }
        QuoteIssuance.Issued again = issue(issuance, owner, key, "EUR", "USD", FixedSide.FIXED_SOURCE, "250.00");
        assertThat(again.converged()).isTrue();
        assertThat(again.quote().id()).isEqualTo(quote.id());
    }

    @Test
    @DisplayName("an incoherent and an implausible provider both fail over; with none usable the request is"
            + " refused with every step kept and nothing issued")
    void unusableAnswersFailOver() throws SQLException {
        activate(eurUsdBothWays(WINDOW, COVER), 5);
        a.mode(FakeFxProvider.Mode.INCOHERENT);
        b.rate("EUR", "USD", "1.300000");
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "250.00"))
                .isInstanceOf(QuoteRefusal.Refused.class)
                .extracting(e -> ((QuoteRefusal.Refused) e).refusal())
                .isEqualTo(QuoteRefusal.IMPLAUSIBLE);
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT string_agg(s.outcome, ',' ORDER BY s.position) FROM fx.quote_sourcing_step s"
                            + " JOIN fx.quote_request r ON r.id = s.quote_request_id WHERE r.owner_party_id = '" + owner + "'"))
                    .isEqualTo("INCOHERENT,IMPLAUSIBLE");
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote WHERE owner_party_id = '" + owner + "'")).isEqualTo("0");
            app.rollback();
        }
    }

    @Test
    @DisplayName("refused before any provider call: a stale reference (fail closed), a fixed leg outside its"
            + " bounds, a suspended pair, a pair not offered")
    void refusedBeforeTheWire() throws Exception {
        PolicyPair staleEurUsd = new PolicyPair(PricingPurpose.CONVERSION, List.of(A, B),
                pair("EUR", "USD", WINDOW, COVER, A, B).pricing(), WINDOW, COVER, new java.math.BigDecimal("0.015"),
                Duration.ofSeconds(1));
        activate(List.of(staleEurUsd, pair("USD", "EUR", WINDOW, COVER, A, B), pair("EUR", "GBP", WINDOW, COVER, A, B)), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        Thread.sleep(1500);
        assertRefused(issuance, owner, "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00", QuoteRefusal.REFERENCE_STALE);
        reference("EUR", "USD", "1.0850000000");
        assertRefused(issuance, owner, "USD", "EUR", FixedSide.FIXED_SOURCE, "0.99", QuoteRefusal.AMOUNT_OUT_OF_RANGE);
        assertRefused(issuance, owner, "USD", "EUR", FixedSide.FIXED_DESTINATION, "50000.01", QuoteRefusal.AMOUNT_OUT_OF_RANGE);
        assertRefused(issuance, owner, "GBP", "USD", FixedSide.FIXED_SOURCE, "100.00", QuoteRefusal.PAIR_NOT_OFFERED);
        try (Connection app = application()) {
            FxQuoteFixtures.availability().disable(app, AvailabilitySubject.pair("EUR-GBP"), FxPolicyFixtures.controller(),
                    "suspended for the suite", Instant.now(), FxPolicyFixtures.correlation());
            app.commit();
        }
        assertRefused(issuance, owner, "EUR", "GBP", FixedSide.FIXED_SOURCE, "100.00", QuoteRefusal.PAIR_SUSPENDED);
        assertThat(a.requests() + b.requests()).as("no provider was asked").isZero();
    }

    @Test
    @DisplayName("a crash between the transactions: the takeover's claim converges on the same QR and its"
            + " pinned version - stale once a successor activated, issued under it while it is not")
    void aTakeoverConvergesOnTheClaim() throws SQLException {
        activate(eurUsdBothWays(WINDOW, COVER), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        String key = claim();
        QuoteIssuance.Claimed first = claimOnly(issuance, owner, key);
        // The crashed flight never reached Tx2; the takeover re-runs the claim under the same key.
        QuoteIssuance.Claimed takeover = claimOnly(issuance, owner, key);
        assertThat(takeover.request().id()).isEqualTo(first.request().id());
        assertThat(takeover.request().reference()).isEqualTo(first.request().reference());
        assertThat(takeover.request().requestedAt()).as("the original anchor - only ever a shorter window")
                .isEqualTo(first.request().requestedAt());
        try (Connection app = application()) {
            assertThat(issuance.issue(app, takeover, issuance.source(takeover), FxQuoteFixtures.customer(owner),
                            FxPolicyFixtures.correlation()).quote().version())
                    .isEqualTo(first.request().version());
            app.commit();
        }

        String staleKey = claim();
        QuoteIssuance.Claimed beforeActivation = claimOnly(issuance, owner, staleKey);
        PricingPolicyId successor = activate(eurUsdBothWays(WINDOW, COVER), 5);
        QuoteIssuance.Claimed afterActivation = claimOnly(issuance, owner, staleKey);
        assertThat(afterActivation.request().version())
                .as("the claim's pinned version, not the successor")
                .isEqualTo(beforeActivation.request().version())
                .isNotEqualTo(successor);
        try (Connection app = application()) {
            assertThatThrownBy(() -> issuance.issue(app, afterActivation, issuance.source(afterActivation),
                            FxQuoteFixtures.customer(owner), FxPolicyFixtures.correlation()))
                    .isInstanceOf(QuoteRefusal.Refused.class)
                    .extracting(e -> ((QuoteRefusal.Refused) e).refusal())
                    .isEqualTo(QuoteRefusal.POLICY_STALE);
            app.rollback();
        }
    }

    @Test
    @DisplayName("Tx2 is atomic: a failure after the insert leaves no quote, no step, no history and no event")
    void theInsertIsAtomic() throws SQLException {
        activate(eurUsdBothWays(WINDOW, COVER), 5);
        com.finapp.platform.outbox.OutboxWriter<Connection> failing = (unitOfWork, envelope, payload, mediaType) -> {
            throw new IllegalStateException("injected: the outbox write fails after the quote insert");
        };
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC(), failing);
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected");
        try (Connection app = application()) {
            String request = "(SELECT id FROM fx.quote_request WHERE owner_party_id = '" + owner + "')";
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote_request WHERE owner_party_id = '" + owner + "'"))
                    .as("the claim's request, committed in Tx1, remains for the takeover")
                    .isEqualTo("1");
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote WHERE quote_request_id = " + request)).isEqualTo("0");
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote_sourcing_step WHERE quote_request_id = " + request))
                    .isEqualTo("0");
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote_event e JOIN fx.quote q ON q.id = e.quote_id"
                            + " WHERE q.owner_party_id = '" + owner + "'"))
                    .isEqualTo("0");
            app.rollback();
        }
    }

    private static QuoteIssuance.Claimed claimOnly(QuoteIssuance issuance, UUID owner, String key) throws SQLException {
        try (Connection app = application()) {
            QuoteIssuance.Claimed claimed = issuance.claim(app, key, new QuoteIssuance.QuoteRequest(owner,
                    CurrencyCode.of("EUR"), CurrencyCode.of("USD"), FixedSide.FIXED_SOURCE,
                    FxQuoteFixtures.money("100.00", "EUR")), FxPolicyFixtures.correlation());
            app.commit();
            return claimed;
        }
    }

    @Test
    @DisplayName("an instance whose clock runs an hour fast issues the same database window - its clock decides"
            + " nothing about validity")
    void aSkewedInstance() throws SQLException {
        activate(eurUsdBothWays(WINDOW, COVER), 5);
        Clock fast = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));
        QuoteStore.QuoteRow quote = issue(issuance(providers(a, b), fast), UUID.randomUUID(), claim(), "EUR", "USD",
                FixedSide.FIXED_SOURCE, "100.00").quote();
        assertThat(Duration.between(quote.issuedAt(), quote.expiresAt())).isEqualTo(WINDOW);
        assertThat(Duration.between(Instant.now(), quote.issuedAt()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    // -----------------------------------------------------------------

    private void assertRefused(QuoteIssuance issuance, UUID owner, String source, String destination, FixedSide side,
            String amount, QuoteRefusal expected) {
        assertThatThrownBy(() -> issue(issuance, owner, claim(), source, destination, side, amount))
                .isInstanceOf(QuoteRefusal.Refused.class)
                .extracting(e -> ((QuoteRefusal.Refused) e).refusal())
                .isEqualTo(expected);
    }

    @FunctionalInterface
    private interface Racer<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> race(int racers, Racer<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return work.run(index);
                };
                pending.add(pool.submit(task));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
