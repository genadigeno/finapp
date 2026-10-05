package com.finapp.fx;

import static com.finapp.fx.FxPolicyFixtures.application;
import static com.finapp.fx.FxPolicyFixtures.correlation;
import static com.finapp.fx.FxPolicyFixtures.scalar;
import static com.finapp.fx.FxQuoteFixtures.A;
import static com.finapp.fx.FxQuoteFixtures.B;
import static com.finapp.fx.FxQuoteFixtures.activate;
import static com.finapp.fx.FxQuoteFixtures.awaitDatabaseClock;
import static com.finapp.fx.FxQuoteFixtures.claim;
import static com.finapp.fx.FxQuoteFixtures.customer;
import static com.finapp.fx.FxQuoteFixtures.eurUsdBothWays;
import static com.finapp.fx.FxQuoteFixtures.issuance;
import static com.finapp.fx.FxQuoteFixtures.issue;
import static com.finapp.fx.FxQuoteFixtures.lifecycle;
import static com.finapp.fx.FxQuoteFixtures.providers;
import static com.finapp.fx.FxQuoteFixtures.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An issued quote's life (`P9-TSK-008`; ADR-0075 section 5; INV-FX-04): its owner's read and
 * cancellation, expiry as an event written exactly once by ten sweepers, and cancellation racing
 * expiry at the boundary - exactly one terminal path per quote, its event once (counted).
 */
@Tag("database")
@DisplayName("the quote's life: read, cancel, expire exactly once (P9-TSK-008)")
class QuoteLifecycleDatabaseTest {

    private final FakeFxProvider a = new FakeFxProvider(A).rate("EUR", "USD", "1.085024");
    private final FakeFxProvider b = new FakeFxProvider(B).rate("EUR", "USD", "1.085024");

    @BeforeEach
    void freshReference() throws SQLException {
        reference("EUR", "USD", "1.0850000000");
    }

    @Test
    @DisplayName("the owner cancels a live quote once - history, audit and event; another owner's id is"
            + " absent; a second cancellation is refused")
    void theOwnerCancels() throws SQLException {
        activate(eurUsdBothWays(Duration.ofSeconds(30), Duration.ofSeconds(10)), 5);
        UUID owner = UUID.randomUUID();
        QuoteStore.QuoteRow quote = issue(issuance(providers(a, b), Clock.systemUTC()), owner, claim(), "EUR", "USD",
                FixedSide.FIXED_SOURCE, "100.00").quote();
        try (Connection app = application()) {
            assertThatThrownBy(() -> lifecycle().cancel(app, quote.id(), UUID.randomUUID(), customer(owner),
                            Instant.now(), correlation()))
                    .isInstanceOf(QuoteLifecycle.QuoteNotFound.class);
            app.rollback();
            assertThat(lifecycle().cancel(app, quote.id(), owner, customer(owner), Instant.now(), correlation()).status())
                    .isEqualTo(QuoteStatus.CANCELLED);
            app.commit();
            assertThatThrownBy(() -> lifecycle().cancel(app, quote.id(), owner, customer(owner), Instant.now(), correlation()))
                    .isInstanceOf(QuoteLifecycle.QuoteNotCancellable.class);
            app.rollback();
            String id = quote.id().value().toString();
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote_event WHERE quote_id = '" + id + "' AND to_status = 'CANCELLED'"))
                    .isEqualTo("1");
            assertThat(scalar(app, "SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.QuoteCancelled'"
                            + " AND target_id = '" + id + "'"))
                    .isEqualTo("1");
            assertThat(scalar(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxQuoteCancelled'"
                            + " AND aggregate_id = '" + id + "'"))
                    .isEqualTo("1");
            app.rollback();
        }
    }

    @Test
    @DisplayName("a lapsed quote reads EXPIRED before any sweep, cannot be cancelled, and the sweep then"
            + " writes its expiry once - caused by its issue, under its own correlation")
    void expiryIsLazyThenSwept() throws Exception {
        activate(eurUsdBothWays(FxQuoteFixtures.MINIMUM_WINDOW, Duration.ZERO), 5);
        UUID owner = UUID.randomUUID();
        QuoteStore.QuoteRow quote = issue(issuance(providers(a, b), Clock.systemUTC()), owner, claim(), "EUR", "USD",
                FixedSide.FIXED_SOURCE, "100.00").quote();
        awaitDatabaseClock(quote.expiresAt().plusMillis(500));
        String id = quote.id().value().toString();
        try (Connection app = application()) {
            assertThat(lifecycle().read(app, quote.id(), owner).orElseThrow().status()).isEqualTo(QuoteStatus.EXPIRED);
            assertThat(scalar(app, "SELECT status FROM fx.quote WHERE id = '" + id + "'")).isEqualTo("ISSUED");
            assertThatThrownBy(() -> lifecycle().cancel(app, quote.id(), owner, customer(owner), Instant.now(), correlation()))
                    .isInstanceOf(QuoteLifecycle.QuoteNotCancellable.class);
            app.rollback();
            sweepUntilDry();
            assertThat(scalar(app, "SELECT status FROM fx.quote WHERE id = '" + id + "'")).isEqualTo("EXPIRED");
            assertThat(scalar(app, "SELECT detected_by FROM fx.quote_event WHERE quote_id = '" + id + "' AND to_status = 'EXPIRED'"))
                    .isEqualTo("SWEEP");
            assertThat(scalar(app, "SELECT e.causation_id = q.issued_event_id::text AND e.correlation_id = q.correlation_id"
                            + " FROM platform.outbox_event e JOIN fx.quote q ON q.id::text = e.aggregate_id::text"
                            + " WHERE e.event_type = 'fx.FxQuoteExpired' AND q.id = '" + id + "'"))
                    .isEqualTo("t");
            app.rollback();
        }
    }

    @Test
    @DisplayName("ten sweepers over thirty lapsed quotes: each expired once, thirty events (counted)")
    void tenSweepersExpireEachOnce() throws Exception {
        activate(eurUsdBothWays(FxQuoteFixtures.MINIMUM_WINDOW, Duration.ZERO), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        List<QuoteStore.QuoteRow> quotes = new ArrayList<>();
        for (int owner = 0; owner < 6; owner++) {
            UUID party = UUID.randomUUID();
            for (int i = 0; i < 5; i++) {
                quotes.add(issue(issuance, party, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00").quote());
            }
        }
        Instant last = quotes.stream().map(QuoteStore.QuoteRow::expiresAt).max(Instant::compareTo).orElseThrow();
        awaitDatabaseClock(last.plusMillis(500));
        AtomicInteger expired = new AtomicInteger();
        race(10, () -> {
            while (true) {
                int page;
                try (Connection own = application()) {
                    page = FxQuoteFixtures.expirePage(own, 4).size();
                    own.commit();
                }
                expired.addAndGet(page);
                if (page == 0) {
                    return null;
                }
            }
        });
        String ids = String.join(",", quotes.stream().map(q -> "'" + q.id().value() + "'").toList());
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote WHERE id IN (" + ids + ") AND status = 'EXPIRED'")).isEqualTo("30");
            assertThat(scalar(app, "SELECT count(*) FROM fx.quote_event WHERE quote_id IN (" + ids + ") AND to_status = 'EXPIRED'"))
                    .isEqualTo("30");
            assertThat(scalar(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxQuoteExpired'"
                            + " AND aggregate_id IN (" + ids + ")"))
                    .isEqualTo("30");
            app.rollback();
        }
        assertThat(expired.get()).as("the sweepers' own counts sum to thirty").isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("cancellers racing sweepers across the expiry boundary: every quote takes exactly one"
            + " terminal path, its event once")
    void cancelRacesExpiry() throws Exception {
        activate(eurUsdBothWays(FxQuoteFixtures.MINIMUM_WINDOW, Duration.ZERO), 5);
        QuoteIssuance issuance = issuance(providers(a, b), Clock.systemUTC());
        List<UUID> owners = new ArrayList<>();
        List<QuoteStore.QuoteRow> quotes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID owner = UUID.randomUUID();
            owners.add(owner);
            quotes.add(issue(issuance, owner, claim(), "EUR", "USD", FixedSide.FIXED_SOURCE, "100.00").quote());
        }
        Instant first = quotes.stream().map(QuoteStore.QuoteRow::expiresAt).min(Instant::compareTo).orElseThrow();
        awaitDatabaseClock(first.minusMillis(150));
        Instant until = Instant.now().plusMillis(1500);
        race(20, index -> {
            while (Instant.now().isBefore(until)) {
                try (Connection own = application()) {
                    if (index % 2 == 0) {
                        int which = (index / 2) % quotes.size();
                        try {
                            lifecycle().cancel(own, quotes.get(which).id(), owners.get(which), customer(owners.get(which)),
                                    Instant.now(), correlation());
                            own.commit();
                        } catch (QuoteLifecycle.QuoteNotCancellable closed) {
                            own.rollback();
                        }
                    } else {
                        FxQuoteFixtures.expirePage(own, 3);
                        own.commit();
                    }
                }
            }
            return null;
        });
        sweepUntilDry();
        try (Connection app = application()) {
            for (QuoteStore.QuoteRow quote : quotes) {
                String id = quote.id().value().toString();
                assertThat(scalar(app, "SELECT count(*) FROM fx.quote_event WHERE quote_id = '" + id
                                + "' AND to_status IN ('CANCELLED', 'EXPIRED')"))
                        .as("one terminal path for %s", id)
                        .isEqualTo("1");
                assertThat(scalar(app, "SELECT count(*) FROM fx.quote_event e JOIN fx.quote q ON q.id = e.quote_id"
                                + " WHERE q.id = '" + id + "' AND e.to_status = q.status"))
                        .isEqualTo("1");
            }
            app.rollback();
        }
    }

    // -----------------------------------------------------------------

    private static void sweepUntilDry() throws SQLException {
        while (true) {
            try (Connection own = application()) {
                int page = FxQuoteFixtures.expirePage(own, 50).size();
                own.commit();
                if (page == 0) {
                    return;
                }
            }
        }
    }

    @FunctionalInterface
    private interface Racer {
        Object run(int index) throws Exception;
    }

    @FunctionalInterface
    private interface Simple {
        Object run() throws Exception;
    }

    private static void race(int racers, Simple work) throws Exception {
        race(racers, index -> work.run());
    }

    private static void race(int racers, Racer work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int index = i;
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.run(index);
                }));
            }
            start.countDown();
            for (Future<Object> outcome : pending) {
                outcome.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
