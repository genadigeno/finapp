package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxCoverFixtures.reference;
import static com.finapp.app.fx.FxCoverFixtures.status;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxCoverOutcomes;
import com.finapp.fx.FxProvider;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The cover under ten instances (`P9-TSK-012`; PHASE_9_PLAN.md section 8's arbiters, ADR-0077
 * sections 3-5; INV-FX-08, INV-IDEM-02): ten sweepers send one cover ONCE (the database-stamped,
 * forward-only permit, rows another sweeper holds skipped); a lost response plus ten sweepers plus ten
 * hinted inquirers leave one provider execution, one execution fact and one entry; ten appliers of one
 * rejection leave one successor attempt; ten appliers of one execution answer leave one fact - every
 * count read from the rows and the simulator, never argued. Ordered with the cover suite.
 */
@Tag("database")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"finapp.fx.cover.resend-after=PT1S", "finapp.fx.cover.requote-base=PT1S"})
@Order(Integer.MAX_VALUE - 1)
@DisplayName("the FX cover under ten instances: one send, one execution, one successor (P9-TSK-012)")
@SuppressWarnings("try")
class FxCoverRaceDatabaseTest {

    private static final int RACERS = 10;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private FxCoverOutcomes outcomes;
    @Autowired private IdGenerator ids;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws Exception {
        if (engine == null) {
            engine = SimulatedFxEngine.start(new byte[32]);
            FxTestClient.rates(engine, Map.of("EUR/USD", "1.0850240000"));
        }
        registry.add("finapp.fx.provider.url", () -> engine.baseUrl().toString());
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    @BeforeEach
    void v1AndFreshReferences() throws Exception {
        FxTestPolicy.ensure(administration, policyStore, FxTestPolicy.V1);
        FxTestClient.freshReferences();
        FxCoverFixtures.ensureFxSourceActive(ruleSetAdministration, ruleSets, dataSource);
        FxTestClient.rates(engine, Map.of("EUR/USD", "1.0850240000"));
    }

    @Test
    @DisplayName("ten sweepers over one due cover: T1 is sent ONCE, executed once - one fact, one entry (counted)")
    void tenSweepersSendOnce() throws Exception {
        UUID cover = coverOf(booked());
        String t1 = reference(cover, 1);
        Thread.sleep(1_200); // the permit is older than resend-after: due for every sweeper at once

        race(() -> dispatch.sweepOnce(Actor.SYSTEM));

        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(engine.idempotencyKeys()).filteredOn(t1::equals).as("sent once").hasSize(1);
        assertThat(engine.executionsOf(t1)).isEqualTo(1);
        assertOneFactOneEntry(cover);
    }

    @Test
    @DisplayName("a lost response, then ten sweepers and ten hinted inquirers at once: one provider execution, one"
            + " execution fact, one entry - the inquiry finds it, nobody re-sends (counted)")
    void aLostResponseAndTwentyResolvers() throws Exception {
        UUID cover = coverOf(booked());
        String t1 = reference(cover, 1);
        engine.loseNextExecutionResponse();
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("UNKNOWN");
        // The permit paces the instances: a sweep inside resend-after leaves the just-sent cover alone.
        String permit = FxTestClient.scalar("SELECT last_dispatched_at::text FROM fx.cover WHERE id = ?", cover);
        dispatch.sweepOnce(Actor.SYSTEM);
        assertThat(status(cover)).as("not yet due: no inquiry, no re-send").isEqualTo("UNKNOWN");
        assertThat(FxTestClient.scalar("SELECT last_dispatched_at::text FROM fx.cover WHERE id = ?", cover))
                .as("the permit was not renewed by a sweep it did not license").isEqualTo(permit);
        Thread.sleep(1_200);

        List<Callable<Object>> resolvers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            resolvers.add(() -> dispatch.sweepOnce(Actor.SYSTEM));
            resolvers.add(() -> {
                dispatch.hint(t1, Actor.SYSTEM);
                return null;
            });
        }
        raceAll(resolvers);

        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(engine.executionsOf(t1)).isEqualTo(1);
        assertThat(engine.idempotencyKeys()).filteredOn(t1::equals).as("the one original send").hasSize(1);
        assertOneFactOneEntry(cover);
    }

    @Test
    @DisplayName("ten appliers of one rejection: one fresh-quote successor - attempt 2 once, one requote event and"
            + " audit, T2 executed once; the losers record nothing (counted)")
    void tenAppliersOfOneRejection() throws Exception {
        UUID cover = coverOf(booked());
        engine.advance(Duration.ofMinutes(10));
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("REJECTED");
        engine.rate("EUR/USD", "1.0841000000");

        race(() -> {
            dispatch.dispatchNow(cover, Actor.SYSTEM);
            return null;
        });

        assertThat(count("SELECT attempts FROM fx.cover WHERE id = ?", cover)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM fx.cover_attempt WHERE cover_id = ?", cover)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxCoverRequoted'"
                + " AND aggregate_id = ?", cover)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.CoverRequoted'"
                + " AND target_id = ?", cover.toString())).isEqualTo(1);
        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(engine.executionsOf(reference(cover, 2))).isEqualTo(1);
        assertOneFactOneEntry(cover);
    }

    @Test
    @DisplayName("ten appliers of one execution answer: one acting applier, one execution fact, one entry, one"
            + " pair of legs - the fact's PK the arbiter beneath the row lock (counted)")
    void tenAppliersOfOneExecution() throws Exception {
        UUID cover = coverOf(booked());
        FxProvider.ExecutionAnswer.Executed answer = new FxProvider.ExecutionAnswer.Executed("FT-race-" + UUID.randomUUID(),
                Money.ofPersisted(100_000, EUR, 2), Money.ofPersisted(108_502, USD, 2),
                ExchangeRate.of(EUR, USD, new BigDecimal("1.0850240000")), LocalDate.now().plusDays(2),
                new FxProvider.Evidence("{\"status\":\"executed\"}".getBytes(StandardCharsets.UTF_8)));
        AtomicInteger actors = new AtomicInteger();

        race(() -> {
            try (CorrelationContext.Scope flow =
                    CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)));
                com.finapp.platform.security.SecurityContext.Scope acting =
                        com.finapp.platform.security.SecurityContext.enterSystem()) {
                FxCoverOutcomes.Applied applied = FxCoverFixtures.inTransaction(dataSource,
                        uow -> outcomes.apply(uow, cover, 1, answer, FxCoverOutcomes.Channel.HINTED_INQUIRY, Actor.SYSTEM));
                if (applied.acting()) {
                    actors.incrementAndGet();
                }
            }
            return null;
        });

        assertThat(actors).hasValue(1);
        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertOneFactOneEntry(cover);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", cover.toString()))
                .isEqualTo(2);
    }

    // -----------------------------------------------------------------

    private String booked() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        return convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "1000.00");
    }

    private static void assertOneFactOneEntry(UUID cover) throws Exception {
        assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ?", cover)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", "fx-cover:" + cover)).isEqualTo(1);
    }

    private static void race(Callable<Object> each) throws Exception {
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            racers.add(each);
        }
        raceAll(racers);
    }

    /** Every racer released at once by one latch; each racer's own failure fails the race. */
    private static void raceAll(List<Callable<Object>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> racer : racers) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return racer.call();
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
