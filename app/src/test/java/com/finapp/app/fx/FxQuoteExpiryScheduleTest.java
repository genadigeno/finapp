package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxQuoteId;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Quote expiry's page loop and a stopping instance (`P15-TSK-009`): a tick takes no page after its schedule's stop began, and
 * without a stop runs to its page bound. The expiry itself is {@code QuoteLifecycleDatabaseTest}'s.
 */
@DisplayName("FX quote expiry: a stopping schedule takes no further page (P15-TSK-009)")
class FxQuoteExpiryScheduleTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Every page comes back full, so only the bound or a stop ends the loop. */
    private static final class Pages {
        final AtomicInteger taken = new AtomicInteger();
        volatile Runnable onFirstPage = () -> { };

        QuoteStore store() {
            return (QuoteStore) Proxy.newProxyInstance(QuoteStore.class.getClassLoader(), new Class<?>[] {QuoteStore.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("expirePage") && args.length == 2) {
                            if (taken.incrementAndGet() == 1) {
                                onFirstPage.run();
                            }
                            return List.of(new QuoteStore.ExpiredRow(FxQuoteId.next(IDS), CurrencyCode.of("USD"),
                                    CurrencyCode.of("EUR"), IDS.next().toString(), IDS.next()));
                        }
                        return null;
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static FxQuoteExpirySchedule schedule(Pages pages) {
        OutboxWriter<Connection> outbox = (OutboxWriter<Connection>) Proxy.newProxyInstance(OutboxWriter.class.getClassLoader(),
                new Class<?>[] {OutboxWriter.class}, (proxy, method, args) -> null);
        AuditWriter<Connection> audit = (AuditWriter<Connection>) Proxy.newProxyInstance(AuditWriter.class.getClassLoader(),
                new Class<?>[] {AuditWriter.class}, (proxy, method, args) -> null);
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
        QuoteStore store = pages.store();
        QuoteLifecycle lifecycle = new QuoteLifecycle(store, audit, outbox, IDS);
        return new FxQuoteExpirySchedule(lifecycle, new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return work.apply(connection);
            }
        }, new FxQuoteMetrics(store, () -> connection, CLOCK, new SimpleMeterRegistry()), CLOCK, Duration.ofSeconds(1), 1);
    }

    @Test
    @DisplayName("running, a tick of full pages stops at its bound")
    void aRunningTickTakesItsBound() {
        Pages pages = new Pages();
        assertThat(schedule(pages).sweepOnce()).isEqualTo(FxQuoteExpirySchedule.MAX_PAGES);
        assertThat(pages.taken).hasValue(FxQuoteExpirySchedule.MAX_PAGES);
    }

    @Test
    @DisplayName("a stop begun during the first page ends the tick there: the page in flight, and no other")
    void aStopEndsTheTickBetweenPages() {
        Pages pages = new Pages();
        FxQuoteExpirySchedule schedule = schedule(pages);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        schedule.drain().started(executor);
        pages.onFirstPage = executor::shutdown;

        assertThat(schedule.sweepOnce()).isEqualTo(1);
        assertThat(pages.taken).hasValue(1);
    }
}
