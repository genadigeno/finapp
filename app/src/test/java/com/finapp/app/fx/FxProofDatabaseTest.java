package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxBooksProof;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxPlanVerification;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The FX proofs (`P9-TSK-013`; PHASE_9_PLAN.md section 12.9.4, INV-FX-05, INV-FX-06): over every
 * conversion and cover the shared container holds - a plan executed, a cover slipped to a realised
 * loss, a destination-fixed plan, a cover still open - the books proof holds in every book and
 * currency and every trade replays exactly; a raw line planted on each FX book flips that book's
 * proof, and a perturbed stored rate flips the plan verdict - each plant rolled back, detection
 * never repair. The gauges read 0 failing currencies and verdict 1.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("the FX proofs: the books explained, every plan replayed, each plant caught (P9-TSK-013)")
@SuppressWarnings("try")
class FxProofDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private FxBooksProof books;
    @Autowired private FxPlanVerification plans;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
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
    @DisplayName("over executed, slipped, destination-fixed and open covers the books proof holds in every book and"
            + " currency, every trade replays exactly, and the gauges read 0 failing and verdict 1")
    void theProofsAreClean() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("5000.00", "EUR"));
        dispatch.dispatchNow(coverOf(convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "1000.00")), Actor.SYSTEM);
        UUID slipping = coverOf(convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "500.00"));
        engine.advance(Duration.ofMinutes(10));
        dispatch.dispatchNow(slipping, Actor.SYSTEM);
        engine.rate("EUR/USD", "1.0841000000");
        dispatch.dispatchNow(slipping, Actor.SYSTEM);
        assertThat(FxCoverFixtures.status(slipping)).isEqualTo("EXECUTED");
        engine.rate("EUR/USD", "1.0850240000");
        dispatch.dispatchNow(coverOf(convertedTrade(client, customer, "EUR", "USD", "FIXED_DESTINATION", "400.00")),
                Actor.SYSTEM);
        convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "100.00"); // its cover stays open

        FxProofMetrics.Result result = inSnapshot();
        assertThat(result.books().clean())
                .as("every FX book explained: %s", result.books().lines().stream().filter(line -> !line.holds()).toList())
                .isTrue();
        assertThat(result.plans().clean()).as("every plan replays: %s", result.plans().divergences()).isTrue();
        assertThat(result.plans().verified()).isGreaterThanOrEqualTo(4);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new FxProofMetrics(books, plans, dataSource::getConnection, Clock.systemUTC(), registry);
        for (AccountPurpose book : FxBooksProof.BOOKS) {
            assertThat(registry.get(FxProofMetrics.PROOF).tag("purpose", book.name()).gauge().value())
                    .as(book.name()).isZero();
        }
        assertThat(registry.get(FxProofMetrics.PLAN_VERDICT).gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a raw line planted on each FX book flips that book's proof, in that currency alone - detection,"
            + " never repair (each plant rolled back)")
    void aPlantedRawLineFlipsTheProof() throws Exception {
        for (AccountPurpose book : FxBooksProof.BOOKS) {
            try (CorrelationContext.Scope flow =
                            CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection uow = dataSource.getConnection()) {
                uow.setAutoCommit(false);
                try {
                    ChartOfAccounts<Connection> chart = new ChartOfAccounts<>(ledgerAccountStore);
                    LocalDate today = LocalDate.now(ZoneOffset.UTC);
                    Money one = Money.ofPersisted(1, EUR, 2);
                    postings.post(uow, new PostingCommand("fx-proof-plant:" + ids.next(), today, today, "a raw plant",
                            List.of(new JournalLine(chart.resolve(uow, book, EUR).id(), Direction.DEBIT, one),
                                    new JournalLine(chart.resolve(uow, AccountPurpose.FEE_REVENUE, EUR).id(),
                                            Direction.CREDIT, one))));
                    FxBooksProof.Report report = books.prove(uow);
                    assertThat(report.failing(book)).as("%s flipped", book).isEqualTo(1);
                    assertThat(report.lines()).filteredOn(line -> !line.holds())
                            .as("only %s in EUR", book)
                            .singleElement()
                            .satisfies(line -> assertThat(line.currency()).isEqualTo(EUR));
                } finally {
                    uow.rollback();
                }
            }
        }
    }

    @Test
    @DisplayName("a perturbed stored customer rate, internal rate or disclosed margin each flips the plan verdict alone,"
            + " naming what differs (each plant rolled back; the internal two added by P9-DOC-001)")
    void aPerturbedRateFlipsThePlanVerdict() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("1000.00", "EUR"));
        String trade = convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", "250.00");
        Map<String, String> perturbations = Map.of(
                "customer rate", "customer_rate = customer_rate - power(10::numeric, -rate_scale)",
                "internal rate", "internal_rate = internal_rate + 0.0000000001",
                "disclosed margin", "disclosed_margin = disclosed_margin + 0.000001");
        for (Map.Entry<String, String> perturbation : perturbations.entrySet()) {
            try (Connection migrator = DatabaseRoles.migrator()) {
                migrator.setAutoCommit(false);
                try (Statement statement = migrator.createStatement()) {
                    statement.execute("ALTER TABLE fx.quote DISABLE TRIGGER USER");
                    statement.execute("UPDATE fx.quote SET " + perturbation.getValue()
                            + " WHERE id = (SELECT quote_id FROM fx.trade WHERE id = '" + trade + "')");
                    FxPlanVerification.Report report = plans.verify(migrator);
                    assertThat(report.divergences())
                            .as("the %s perturbed", perturbation.getKey())
                            .filteredOn(divergence -> divergence.trade().value().toString().equals(trade))
                            .singleElement()
                            .satisfies(divergence -> assertThat(divergence.what()).contains(perturbation.getKey()));
                } finally {
                    migrator.rollback();
                }
            }
        }
        assertThat(inSnapshot().plans().clean()).as("the rollback restored the replay").isTrue();
    }

    @Test
    @DisplayName("every provenance column a trade's read returns is NOT NULL for every writer - the rate chain, the"
            + " provider's quote, the policy and the plan (P9-DOC-001, the gate's provenance criterion)")
    void everyProvenanceColumnIsNotNull() throws Exception {
        List<String> provenance = List.of("pricing_policy_version_id", "reference_snapshot_id", "reference_rate",
                "provider_code", "provider_quote_reference", "provider_rate", "provider_value_date", "customer_rate",
                "internal_rate", "disclosed_margin", "spread", "markup", "rate_scale", "rate_rounding",
                "amount_rounding", "margin_rounding", "residual_minor", "margin_minor", "spread_margin_minor",
                "markup_margin_minor");
        for (String column : provenance) {
            assertThat(FxTestClient.scalar("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'fx'"
                    + " AND table_name = 'quote' AND column_name = ?", column))
                    .as("fx.quote.%s", column).isEqualTo("NO");
        }
        for (String column : List.of("quote_id", "customer_rate", "executed_rate", "residual_minor", "margin_minor")) {
            assertThat(FxTestClient.scalar("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'fx'"
                    + " AND table_name = 'trade' AND column_name = ?", column))
                    .as("fx.trade.%s", column).isEqualTo("NO");
        }
    }

    private FxProofMetrics.Result inSnapshot() {
        return new FxProofMetrics(books, plans, dataSource::getConnection, Clock.systemUTC(), new SimpleMeterRegistry())
                .readNow();
    }
}
