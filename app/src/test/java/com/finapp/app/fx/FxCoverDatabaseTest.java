package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxCoverFixtures.coverLines;
import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxCoverFixtures.positionNet;
import static com.finapp.app.fx.FxCoverFixtures.reference;
import static com.finapp.app.fx.FxCoverFixtures.status;
import static com.finapp.app.fx.FxCoverFixtures.tradeLines;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.fx.CoverStore;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.FxCoverOutcomes;
import com.finapp.fx.FxProvider;
import com.finapp.fx.FxProviderEvidenceStore;
import com.finapp.fx.FxProviders;
import com.finapp.fx.FxSettlementExpectations;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.QuoteStore;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * The FX cover over the real composition and the simulated provider (`P9-TSK-012`; ADR-0077,
 * PHASE_9_PLAN.md sections 12.4(b)/(f) and 12.5; INV-FX-06, INV-FX-08, INV-FX-09, INV-PAY-04,
 * INV-LIFE-03): the cover executes once and closes the plan exactly, onto fx-sim-a's own clearing,
 * opening both legs; scenario 3 (the market moves; the provider refuses its own lock - one
 * rejection, one fresh firm quote, one requote, one realised-result line); scenario 4 (the response
 * lost - UNKNOWN, one inquiry, one transition, one posting); the unrecognised re-send of the SAME T;
 * an implausible requote held with its backoff; an off-plan fixed leg booked and flagged; an
 * unwanted rejected cover voided; the outcome transaction atomic under injected failure; every
 * edge of the applier and of the database's machine. The customer's lines never move.
 *
 * <p>Ordered after every default suite (and before the residue judge): its FX source v1 is the
 * runbook's act, which {@code FxProviderSourceDatabaseTest} performs itself after proving its absence.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("the FX cover: once, exactly the plan, however the provider answers (P9-TSK-012)")
@SuppressWarnings("try")
class FxCoverDatabaseTest {

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
    @Autowired private CoverStore covers;
    @Autowired private QuoteStore quotes;
    @Autowired private FxProviders providers;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private FxSettlementExpectations expectations;
    @Autowired private FxProviderEvidenceStore<Connection> evidence;
    @Autowired private OutboxWriter<Connection> outbox;
    @Autowired private AuditWriter<Connection> audit;
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
    @DisplayName("(b) the cover executes at the plan: one execution, the entry closing FX_POSITION exactly onto"
            + " fx-sim-a's OWN clearing, both legs opened, its event and audit - the customer's lines untouched")
    void theCoverExecutesAtThePlan() throws Exception {
        String trade = booked("1000.00");
        UUID cover = coverOf(trade);
        List<String> customerLines = tradeLines(trade);
        String t1 = reference(cover, 1);

        dispatch.dispatchNow(cover, Actor.SYSTEM);

        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(engine.executionsOf(t1)).isEqualTo(1);
        assertThat(coverLines(cover)).containsExactly(
                "FX_POSITION EUR DEBIT 100000", "FX_PROVIDER_CLEARING EUR CREDIT 100000",
                "FX_PROVIDER_CLEARING USD DEBIT 108502", "FX_POSITION USD CREDIT 108502");
        assertThat(positionNet(trade, "EUR")).as("FX_POSITION is 0 at rest").isZero();
        assertThat(positionNet(trade, "USD")).isZero();
        assertThat(scalar("SELECT owner_ref::text FROM ledger.ledger_account a JOIN ledger.journal_line l"
                + " ON l.ledger_account_id = a.id JOIN fx.cover_execution e ON e.journal_entry_id = l.entry_id"
                + " WHERE e.cover_id = ? AND a.purpose = 'FX_PROVIDER_CLEARING' LIMIT 1", cover))
                .as("the counterparty's own account (INV-RAIL-04)")
                .isEqualTo("01a0e2bc-8200-7022-8000-000000000001");
        assertThat(scalar("SELECT provider_trade_ref || ':' || realised_sold_minor || ':' || realised_bought_minor"
                + " || ':' || executed_off_plan FROM fx.cover_execution WHERE cover_id = ?", cover))
                .matches("FT-[0-9a-f]{8}-\\d+:0:0:false");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", cover.toString()))
                .as("both legs").isEqualTo(2);
        // Each leg IS its clearing line's copy, dated and pinned by the FX source's v1 (ADR-0067).
        com.finapp.app.reconciliation.ClearingLineCopies.assertOpensItsClearingLinesCopy(
                com.finapp.reconciliation.ExpectationKind.FX_SELL_LEG, cover.toString(), "fx-cover:" + cover,
                com.finapp.reconciliation.ExpectationDirection.OUTBOUND);
        com.finapp.app.reconciliation.ClearingLineCopies.assertOpensItsClearingLinesCopy(
                com.finapp.reconciliation.ExpectationKind.FX_BUY_LEG, cover.toString(), "fx-cover:" + cover,
                com.finapp.reconciliation.ExpectationDirection.INBOUND);
        assertThat(scalar("SELECT direction || ' ' || amount_minor || ' ' || currency FROM reconciliation.expectation"
                + " WHERE operation_ref = ? AND kind = 'FX_SELL_LEG'", cover.toString())).isEqualTo("OUTBOUND 100000 EUR");
        assertThat(scalar("SELECT direction || ' ' || amount_minor || ' ' || currency FROM reconciliation.expectation"
                + " WHERE operation_ref = ? AND kind = 'FX_BUY_LEG'", cover.toString())).isEqualTo("INBOUND 108502 USD");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE key_kind = 'COVER_REF'"
                + " AND key_value IN (?, ?)", t1 + ":EUR", t1 + ":USD")).as("each leg its own key").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxCoverExecuted'"
                + " AND aggregate_id = ?", cover)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.CoverExecuted'"
                + " AND target_id = ?", cover.toString())).isEqualTo(1);
        assertThat(tradeLines(trade)).as("INV-FX-09: no cover outcome reaches a customer line").isEqualTo(customerLines);

        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(engine.executionsOf(t1)).as("a terminal cover is never sent again").isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 3: the market moving after issue leaves the cover at the firm rate (zero result); the"
            + " provider refusing its own lock yields one rejection, one fresh firm quote, one requote T2 and"
            + " exactly one realised-result line - (f) posted exactly, the customer byte-identical, FX_POSITION 0")
    void theProviderRateMoves() throws Exception {
        String steady = booked("1000.00");
        UUID steadyCover = coverOf(steady);
        engine.rate("EUR/USD", "1.0900000000");
        dispatch.dispatchNow(steadyCover, Actor.SYSTEM);
        assertThat(scalar("SELECT realised_bought_minor FROM fx.cover_execution WHERE cover_id = ?", steadyCover))
                .as("the provider honours its own firm quote: the market move costs nothing").isEqualTo("0");

        engine.rate("EUR/USD", "1.0850240000");
        String trade = booked("1000.00");
        UUID cover = coverOf(trade);
        List<String> customerLines = tradeLines(trade);
        String t1 = reference(cover, 1);
        engine.advance(Duration.ofMinutes(10));
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxCoverRejected'"
                + " AND aggregate_id = ?", cover)).isEqualTo(1);

        engine.rate("EUR/USD", "1.0841000000");
        int quotesBefore = engine.quoteRequests();
        dispatch.dispatchNow(cover, Actor.SYSTEM);

        assertThat(engine.quoteRequests() - quotesBefore).as("one fresh firm quote").isEqualTo(1);
        assertThat(status(cover)).isEqualTo("EXECUTED");
        String t2 = reference(cover, 2);
        assertThat(t2).isNotEqualTo(t1).matches("T-[0-9a-f]{32}");
        assertThat(engine.executionsOf(t1)).isZero();
        assertThat(engine.executionsOf(t2)).isEqualTo(1);
        assertThat(coverLines(cover)).containsExactly(
                "FX_POSITION EUR DEBIT 100000", "FX_PROVIDER_CLEARING EUR CREDIT 100000",
                "FX_PROVIDER_CLEARING USD DEBIT 108410", "FX_REALISED_LOSSES USD DEBIT 92", "FX_POSITION USD CREDIT 108502");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxCoverRequoted'"
                + " AND aggregate_id = ?", cover)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.CoverRequoted'"
                + " AND target_id = ?", cover.toString())).isEqualTo(1);
        assertThat(positionNet(trade, "EUR")).isZero();
        assertThat(positionNet(trade, "USD")).isZero();
        assertThat(tradeLines(trade)).isEqualTo(customerLines);

        dispatch.hint(t1, Actor.SYSTEM);
        assertThat(count("SELECT count(*) FROM fx.cover_attempt WHERE cover_id = ?", cover))
                .as("a hint naming the superseded T1 moves nothing").isEqualTo(2);
    }

    @Test
    @DisplayName("scenario 4: the provider executes but the response is lost - UNKNOWN, never failed; the inquiry"
            + " finds the execution: one transition, one posting, the provider's reference stored; execution count 1")
    void theResponseIsLost() throws Exception {
        UUID cover = coverOf(booked("1000.00"));
        String t1 = reference(cover, 1);
        engine.loseNextExecutionResponse();
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("UNKNOWN");
        assertThat(engine.executionsOf(t1)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ?", cover)).isZero();

        dispatch.dispatchNow(cover, Actor.SYSTEM);

        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(engine.executionsOf(t1)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ? AND provider_trade_ref LIKE 'FT-%'",
                cover)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", "fx-cover:" + cover))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover_attempt WHERE cover_id = ?", cover))
                .as("no new reference without a definitive rejection").isEqualTo(1);
    }

    @Test
    @DisplayName("a send whose answer is unusable makes the cover UNKNOWN; an inquiry the provider has never seen"
            + " re-sends the SAME T - executed once, no T2 minted (INV-FX-08)")
    void anUnrecognisedReferenceIsResentUnderItself() throws Exception {
        UUID cover = coverOf(booked("1000.00"));
        String t1 = reference(cover, 1);
        engine.serverErrorNext();
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("UNKNOWN");
        assertThat(engine.executionsOf(t1)).isZero();

        dispatch.dispatchNow(cover, Actor.SYSTEM);

        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(engine.executionsOf(t1)).isEqualTo(1);
        assertThat(engine.idempotencyKeys()).filteredOn(t1::equals).as("the same T, sent twice").hasSize(2);
        assertThat(count("SELECT count(*) FROM fx.cover_attempt WHERE cover_id = ?", cover)).isEqualTo(1);
    }

    @Test
    @DisplayName("an implausible requote is held: the cover stays REJECTED, its backoff advanced, no new attempt -"
            + " a new reference only after a fresh firm quote passed the band")
    void anImplausibleRequoteIsHeld() throws Exception {
        UUID cover = coverOf(booked("1000.00"));
        engine.advance(Duration.ofMinutes(10));
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("REJECTED");
        engine.rate("EUR/USD", "1.3000000000");

        dispatch.dispatchNow(cover, Actor.SYSTEM);

        assertThat(status(cover)).isEqualTo("REJECTED");
        assertThat(scalar("SELECT requote_failures FROM fx.cover WHERE id = ?", cover)).isEqualTo("1");
        assertThat(count("SELECT count(*) FROM fx.cover_attempt WHERE cover_id = ?", cover)).isEqualTo(1);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertThat(covers.claimDue(app, Duration.ofHours(1), Duration.ofSeconds(30), 1000))
                    .as("the backoff holds the refused cover off the next sweep")
                    .noneMatch(row -> row.id().equals(cover));
            app.rollback();
        }
    }

    @Test
    @DisplayName("a provider deviating on the FIXED leg is booked exactly and flagged executed_off_plan - the"
            + " difference realised in that leg's currency, FX_POSITION still closed")
    void anOffPlanExecutionIsFlagged() throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        String trade = convertedTrade(client, customer, "EUR", "USD", "FIXED_DESTINATION", "1000.00");
        UUID cover = coverOf(trade);
        engine.deviateNextExecution(1);

        dispatch.dispatchNow(cover, Actor.SYSTEM);

        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(scalar("SELECT executed_off_plan || ':' || realised_bought_minor FROM fx.cover_execution"
                + " WHERE cover_id = ?", cover)).isEqualTo("true:-1");
        assertThat(coverLines(cover)).contains("FX_REALISED_LOSSES USD DEBIT 1", "FX_POSITION USD CREDIT 100000");
        assertThat(positionNet(trade, "EUR")).isZero();
        assertThat(positionNet(trade, "USD")).isZero();
    }

    @Test
    @DisplayName("a REJECTED cover its quote no longer wants (the trade REVERSED) is VOIDED and audited - no firm"
            + " quote asked for, nothing sent")
    void anUnwantedRejectedCoverIsVoided() throws Exception {
        String trade = booked("1000.00");
        UUID cover = coverOf(trade);
        engine.advance(Duration.ofMinutes(10));
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(status(cover)).isEqualTo("REJECTED");
        try (Connection app = DatabaseRoles.application();
                PreparedStatement reverse = app.prepareStatement("UPDATE fx.trade SET status = 'REVERSED' WHERE id = ?::uuid")) {
            reverse.setString(1, trade);
            reverse.executeUpdate();
        }
        int quotesBefore = engine.quoteRequests();

        dispatch.dispatchNow(cover, Actor.SYSTEM);

        assertThat(status(cover)).isEqualTo("VOIDED");
        assertThat(engine.quoteRequests()).isEqualTo(quotesBefore);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.CoverVoided' AND target_id = ?",
                cover.toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("the outcome transaction is atomic: a failure injected after the posting (the second leg's"
            + " expectation, or the audit) leaves no execution fact, no entry and the cover where it was; the real"
            + " applier then books it once")
    void theOutcomeIsAtomicUnderFailure() throws Exception {
        UUID cover = coverOf(booked("1000.00"));
        FxProvider.ExecutionAnswer.Executed answer = executedAtThePlan();
        FxSettlementExpectations failingSecondLeg = new FxSettlementExpectations() {
            private int opened;

            @Override
            public void open(Connection unitOfWork, Opening opening) {
                if (++opened == 2) {
                    throw new IllegalStateException("injected: the second leg");
                }
                expectations.open(unitOfWork, opening);
            }
        };
        AuditWriter<Connection> failingAudit = (unitOfWork, record) -> {
            throw new IllegalStateException("injected: the audit");
        };
        for (FxCoverOutcomes broken : List.of(
                outcomes(failingSecondLeg, audit), outcomes(expectations, failingAudit))) {
            assertThatThrownBy(() -> applyInFlow(broken, cover, answer)).isInstanceOf(IllegalStateException.class);
            assertThat(status(cover)).isEqualTo("DISPATCHED");
            assertThat(count("SELECT count(*) FROM fx.cover_execution WHERE cover_id = ?", cover)).isZero();
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", "fx-cover:" + cover)).isZero();
            assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE operation_ref = ?", cover.toString())).isZero();
        }

        applyInFlow(outcomes, cover, answer);
        assertThat(status(cover)).isEqualTo("EXECUTED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", "fx-cover:" + cover)).isEqualTo(1);
    }

    @Test
    @DisplayName("every edge of the applier: each answer on each state, both channels - only Executed and Rejected"
            + " conclude, only a send's unusable answer makes a cover UNKNOWN, contradictions move nothing")
    void everyEdgeOfTheApplier() throws Exception {
        // From DISPATCHED.
        assertThat(applied("DISPATCHED", executedAtThePlan(), FxCoverOutcomes.Channel.ANSWER)).isEqualTo("EXECUTED");
        assertThat(applied("DISPATCHED", rejected(), FxCoverOutcomes.Channel.ANSWER)).isEqualTo("REJECTED");
        assertThat(applied("DISPATCHED", indeterminate(), FxCoverOutcomes.Channel.ANSWER)).isEqualTo("UNKNOWN");
        assertThat(applied("DISPATCHED", indeterminate(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("DISPATCHED");
        assertThat(applied("DISPATCHED", unrecognised(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("DISPATCHED");
        assertThat(applied("DISPATCHED", new FxProvider.ExecutionAnswer.NothingSent(), FxCoverOutcomes.Channel.ANSWER))
                .isEqualTo("DISPATCHED");
        assertThat(applied("DISPATCHED", executedIn(USD, EUR), FxCoverOutcomes.Channel.ANSWER))
                .as("an execution in other currencies is not knowledge").isEqualTo("UNKNOWN");
        // From UNKNOWN.
        assertThat(applied("UNKNOWN", executedAtThePlan(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("EXECUTED");
        assertThat(applied("UNKNOWN", rejected(), FxCoverOutcomes.Channel.HINTED_INQUIRY)).isEqualTo("REJECTED");
        assertThat(applied("UNKNOWN", indeterminate(), FxCoverOutcomes.Channel.ANSWER)).isEqualTo("UNKNOWN");
        assertThat(applied("UNKNOWN", unrecognised(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("UNKNOWN");
        // From REJECTED and VOIDED: a late execution contradicts - evidence only.
        assertThat(applied("REJECTED", executedAtThePlan(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("REJECTED");
        assertThat(applied("REJECTED", rejected(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("REJECTED");
        assertThat(applied("VOIDED", executedAtThePlan(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("VOIDED");
        // From EXECUTED: a duplicate converges, a rejection contradicts.
        assertThat(applied("EXECUTED", executedAtThePlan(), FxCoverOutcomes.Channel.HINTED_INQUIRY)).isEqualTo("EXECUTED");
        assertThat(applied("EXECUTED", rejected(), FxCoverOutcomes.Channel.INQUIRY)).isEqualTo("EXECUTED");
    }

    @Test
    @DisplayName("the database's machine, for every writer: DISPATCHED|UNKNOWN -> VOIDED refused, EXECUTED refused"
            + " without its fact, a requote refused without its new reference, the failures counted only while"
            + " REJECTED, the exposure frozen, nothing deleted")
    void theDatabasesMachine() throws Exception {
        UUID dispatched = coverOf(booked("1000.00"));
        assertRefused("UPDATE fx.cover SET status = 'VOIDED' WHERE id = ?", dispatched);
        assertRefused("UPDATE fx.cover SET status = 'EXECUTED' WHERE id = ?", dispatched);
        assertRefused("UPDATE fx.cover SET requote_failures = 1 WHERE id = ?", dispatched);
        assertRefused("UPDATE fx.cover SET fixed_amount_minor = fixed_amount_minor + 1 WHERE id = ?", dispatched);
        assertRefused("DELETE FROM fx.cover WHERE id = ?", dispatched);
        migrator("UPDATE fx.cover SET status = 'UNKNOWN' WHERE id = ?", dispatched);
        assertRefused("UPDATE fx.cover SET status = 'VOIDED' WHERE id = ?", dispatched);
        assertRefused("UPDATE fx.cover SET status = 'DISPATCHED' WHERE id = ?", dispatched);
        migrator("UPDATE fx.cover SET status = 'REJECTED' WHERE id = ?", dispatched);
        assertRefused("UPDATE fx.cover SET status = 'DISPATCHED', attempts = attempts + 1 WHERE id = ?", dispatched);
        migrator("UPDATE fx.cover SET requote_failures = requote_failures + 1 WHERE id = ?", dispatched);
        assertThat(scalar("SELECT requote_failures FROM fx.cover WHERE id = ?", dispatched)).isEqualTo("1");
        migrator("UPDATE fx.cover SET status = 'VOIDED' WHERE id = ?", dispatched);
        assertRefused("UPDATE fx.cover SET status = 'REJECTED' WHERE id = ?", dispatched);

        UUID executed = coverOf(booked("1000.00"));
        dispatch.dispatchNow(executed, Actor.SYSTEM);
        assertRefused("UPDATE fx.cover SET status = 'REJECTED' WHERE id = ?", executed);
        assertRefused("UPDATE fx.cover_execution SET provider_trade_ref = 'FT-forged' WHERE cover_id = ?", executed);
        assertRefused("UPDATE fx.cover_execution SET journal_entry_id = gen_random_uuid() WHERE cover_id = ?", executed);
        assertRefused("DELETE FROM fx.cover_execution WHERE cover_id = ?", executed);
        assertRefused("INSERT INTO fx.cover_execution SELECT * FROM fx.cover_execution WHERE cover_id = ?", executed);

        // The born-once arbiters beneath every check (the lock-bypass shape): with every trigger of the table
        // off, a second fact for the cover is the PK's to refuse, and another cover's fact naming the same
        // provider trade is the unique's.
        UUID unsent = coverOf(booked("1000.00"));
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            try (java.sql.Statement statement = migrator.createStatement()) {
                statement.execute("ALTER TABLE fx.cover_execution DISABLE TRIGGER USER");
                String rest = " fixed_side, sold_currency, sold_minor, sold_scale, bought_currency, bought_minor,"
                        + " bought_scale, executed_rate, value_date, plan_sold_minor, plan_bought_minor, realised_sold_minor,"
                        + " realised_bought_minor, executed_off_plan, NULL, recorded_at, recorded_on, correlation_id"
                        + " FROM fx.cover_execution WHERE cover_id = '" + executed + "'";
                statement.execute("SAVEPOINT pk");
                assertThatThrownBy(() -> statement.execute("INSERT INTO fx.cover_execution SELECT cover_id, attempt,"
                        + " client_reference, provider_code, provider_trade_ref || '-twin'," + rest))
                        .as("a second fact for one cover").hasMessageContaining("cover_execution_pk");
                statement.execute("ROLLBACK TO SAVEPOINT pk");
                assertThatThrownBy(() -> statement.execute("INSERT INTO fx.cover_execution SELECT '" + unsent + "'::uuid, 1,"
                        + " (SELECT client_reference FROM fx.cover_attempt WHERE cover_id = '" + unsent + "' AND attempt = 1),"
                        + " provider_code, provider_trade_ref," + rest))
                        .as("one provider trade, another cover").hasMessageContaining("cover_execution_provider_trade_once");
            } finally {
                migrator.rollback();
            }
        }
    }

    // -----------------------------------------------------------------

    private String booked(String amount) throws Exception {
        FxTestClient client = new FxTestClient(port);
        FxTestClient.Customer customer = client.verifiedCustomer();
        UUID product = client.openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        return convertedTrade(client, customer, "EUR", "USD", "FIXED_SOURCE", amount);
    }

    /** A fresh cover put in {@code state} along legal edges, then {@code answer} applied once; its status after. */
    private String applied(String state, FxProvider.ExecutionAnswer answer, FxCoverOutcomes.Channel channel)
            throws Exception {
        UUID cover = coverOf(booked("1000.00"));
        switch (state) {
            case "UNKNOWN" -> migrator("UPDATE fx.cover SET status = 'UNKNOWN' WHERE id = ?", cover);
            case "REJECTED" -> migrator("UPDATE fx.cover SET status = 'REJECTED' WHERE id = ?", cover);
            case "VOIDED" -> {
                migrator("UPDATE fx.cover SET status = 'REJECTED' WHERE id = ?", cover);
                migrator("UPDATE fx.cover SET status = 'VOIDED' WHERE id = ?", cover);
            }
            case "EXECUTED" -> applyInFlow(outcomes, cover, executedAtThePlan());
            default -> { }
        }
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)));
                com.finapp.platform.security.SecurityContext.Scope acting =
                        com.finapp.platform.security.SecurityContext.enterSystem()) {
            FxCoverFixtures.inTransaction(dataSource, uow -> outcomes.apply(uow, cover, 1, answer, channel, Actor.SYSTEM));
        }
        if (answer instanceof FxProvider.ExecutionAnswer.Executed && !"EXECUTED".equals(state)
                && "EXECUTED".equals(status(cover))) {
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?", "fx-cover:" + cover))
                    .isEqualTo(1);
        }
        return status(cover);
    }

    private void applyInFlow(FxCoverOutcomes applier, UUID cover, FxProvider.ExecutionAnswer answer) throws SQLException {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)));
                com.finapp.platform.security.SecurityContext.Scope acting =
                        com.finapp.platform.security.SecurityContext.enterSystem()) {
            FxCoverFixtures.inTransaction(dataSource,
                    uow -> applier.apply(uow, cover, 1, answer, FxCoverOutcomes.Channel.ANSWER, Actor.SYSTEM));
        }
    }

    private FxCoverOutcomes outcomes(FxSettlementExpectations legs, AuditWriter<Connection> auditWriter) {
        return new FxCoverOutcomes(covers, quotes, providers, new ChartOfAccounts<>(ledgerAccountStore), postings, legs,
                evidence, outbox, auditWriter, ids, Clock.systemUTC(),
                new com.finapp.fx.CoverUnwinds(covers, auditWriter, ids, Clock.systemUTC()));
    }

    private static FxProvider.ExecutionAnswer.Executed executedAtThePlan() {
        return executedIn(EUR, USD);
    }

    private static FxProvider.ExecutionAnswer.Executed executedIn(CurrencyCode sold, CurrencyCode bought) {
        return new FxProvider.ExecutionAnswer.Executed("FT-edge-" + UUID.randomUUID().toString().substring(0, 8),
                Money.ofPersisted(sold.equals(EUR) ? 100_000 : 108_502, sold, 2),
                Money.ofPersisted(bought.equals(USD) ? 108_502 : 100_000, bought, 2),
                ExchangeRate.of(sold, bought, new BigDecimal("1.0850240000")), LocalDate.now().plusDays(2),
                evidenceOf("{\"status\":\"executed\"}"));
    }

    private static FxProvider.ExecutionAnswer rejected() {
        return new FxProvider.ExecutionAnswer.Rejected(FxProvider.RejectReason.QUOTE_EXPIRED,
                evidenceOf("{\"status\":\"rejected\"}"));
    }

    private static FxProvider.ExecutionAnswer indeterminate() {
        return new FxProvider.ExecutionAnswer.Indeterminate(FxProvider.Indeterminacy.TIMEOUT, Optional.empty());
    }

    private static FxProvider.ExecutionAnswer unrecognised() {
        return new FxProvider.ExecutionAnswer.Unrecognised(evidenceOf("{\"status\":\"unrecognised\"}"));
    }

    private static FxProvider.Evidence evidenceOf(String body) {
        return new FxProvider.Evidence(body.getBytes(StandardCharsets.UTF_8));
    }

    private static void migrator(String sql, UUID id) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement statement = migrator.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    /** Refused for the schema owner itself - the database's rank, every writer. */
    private static void assertRefused(String sql, UUID id) {
        assertThatThrownBy(() -> migrator(sql, id)).as(sql).isInstanceOf(SQLException.class);
    }
}
