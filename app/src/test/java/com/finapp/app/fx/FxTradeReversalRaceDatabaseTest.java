package com.finapp.app.fx;

import static com.finapp.app.fx.FxCoverFixtures.convertedTrade;
import static com.finapp.app.fx.FxCoverFixtures.coverOf;
import static com.finapp.app.fx.FxTestClient.count;
import static com.finapp.app.fx.FxTestClient.field;
import static com.finapp.app.fx.FxTestClient.fund;
import static com.finapp.app.fx.FxTestClient.money;
import static com.finapp.app.fx.FxTestClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.fx.FxBooksProof;
import com.finapp.fx.FxCoverDispatch;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.TradeReversals;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingService;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSets;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * The operator's FX trade reversal (`P9-TSK-025`, the lifecycle document section 3.3; PHASE_9_PLAN.md section 14
 * scenario 8, the reversal half; {@code INV-REV-01}, {@code INV-REV-02}, {@code INV-AUD-04}): a booked wallet
 * conversion corrected by its exact mirror, four-eyes - ten approvers make one reversal entry and one unwind, whose
 * execution posts one realised line; the customer's wallets back where they began; the FX books proving at rest.
 * Every invalid edge of the machine refused by the domain and by raw SQL; self-approval refused by each rank alone;
 * a short destination wallet, a cross-border trade and a missing permission each refused with nothing written; the
 * approval atomic under an injected fault.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Order(Integer.MAX_VALUE - 1)
@DisplayName("the operator's FX trade reversal (P9-TSK-025)")
class FxTradeReversalRaceDatabaseTest {

    private static final String TRADES = "/v1/operator/fx/trades/";
    private static SimulatedFxEngine engine;

    @LocalServerPort private int port;
    @Autowired private PricingPolicyAdministration administration;
    @Autowired private PricingPolicyStore policyStore;
    @Autowired private PostingService postings;
    @Autowired private RuleSetAdministration ruleSetAdministration;
    @Autowired private RuleSets ruleSets;
    @Autowired private DataSource dataSource;
    @Autowired private FxCoverDispatch dispatch;
    @Autowired private FxBooksProof booksProof;
    @Autowired private Authorization authorization;
    @Autowired private TradeReversals reversals;
    @Autowired private HoldService holds;

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

    private FxTestClient client() {
        return new FxTestClient(port);
    }

    // ------------------------------------------------------------------ scenario 8, the reversal half

    @Test
    @DisplayName("scenario 8 (reversal): ten approvers on one proposal - one approves, nine are 409 ProposalNotPending;"
            + " one mirror entry, the trade REVERSED, one unwind whose execution posts one realised line; the wallets"
            + " back where they began; the FX books prove at rest")
    void tenApproversMakeOneReversal() throws Exception {
        Booked booked = booked("1000.00");
        UUID cover = coverOf(booked.trade());
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        assertThat(FxCoverFixtures.status(cover)).isEqualTo("EXECUTED");
        long eur = walletBalance(booked, "EUR");
        assertThat(eur).isEqualTo(200_000);

        String proposer = sessionWith(RoleName.LEDGER_OPERATOR);
        HttpResponse<String> proposed = propose(booked.trade(), proposer, FxTestClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String reversal = field(proposed.body(), "reversalId");

        List<String> approvers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            approvers.add(sessionWith(RoleName.LEDGER_OPERATOR));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<HttpResponse<String>>> racers = new ArrayList<>();
            for (String approver : approvers) {
                racers.add(pool.submit(() -> {
                    start.await();
                    return decide(booked.trade(), reversal, "approval", approver);
                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> racer : racers) {
                statuses.add(racer.get(2, TimeUnit.MINUTES).statusCode());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(9);

        assertThat(scalar("SELECT status FROM fx.trade WHERE id = ?::uuid", booked.trade())).isEqualTo("REVERSED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.reverse:fx-trade:" + booked.trade())).as("one mirror entry").isEqualTo(1);
        assertThat(scalar("SELECT status || ':' || (reversal_entry_id IS NOT NULL) FROM fx.trade_reversal WHERE id = ?::uuid",
                reversal)).isEqualTo("APPROVED:true");
        assertThat(walletBalance(booked, "EUR")).as("the source wallet back where it began").isEqualTo(300_000);
        assertThat(walletBalance(booked, "USD")).as("the destination wallet back where it began").isZero();
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = (SELECT quote_id FROM fx.trade WHERE id = ?::uuid)"
                + " AND kind = 'UNWIND'", booked.trade())).as("one unwind").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'fx.FxTradeReversed' AND aggregate_id = ?::uuid",
                booked.trade())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'fx.FxTradeReversalApproved'"
                + " AND target_id = ?", reversal)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.trade_reversal_event WHERE reversal_id = ?::uuid", reversal)).isEqualTo(2);

        UUID unwind = UUID.fromString(scalar("SELECT id::text FROM fx.cover WHERE quote_id = (SELECT quote_id FROM fx.trade"
                + " WHERE id = ?::uuid) AND kind = 'UNWIND'", booked.trade()));
        dispatch.dispatchNow(unwind, Actor.SYSTEM);
        assertThat(FxCoverFixtures.status(unwind)).isEqualTo("EXECUTED");
        assertThat(FxCoverFixtures.coverLines(unwind).stream()
                .filter(line -> line.startsWith("FX_REALISED_GAINS") || line.startsWith("FX_REALISED_LOSSES")))
                .as("one realised line").hasSize(1);
        assertBooksProve();
    }

    // ------------------------------------------------------------------ the machine's edges

    @Test
    @DisplayName("every invalid edge is refused: by the domain - self-approval and self-rejection 409 SelfApprovalRefused,"
            + " a second proposal 409 ProposalPending, a decided proposal 409 ProposalNotPending, a reversed trade 409"
            + " TradeNotReversible; and by raw SQL - each out of APPROVED or REJECTED, a frozen proposal, a delete, a"
            + " birth on a reversed trade, and four-eyes by the CHECK alone")
    void everyInvalidEdgeIsRefused() throws Exception {
        Booked booked = booked("100.00");
        String proposer = sessionWith(RoleName.LEDGER_OPERATOR);
        String other = sessionWith(RoleName.LEDGER_OPERATOR);
        String reversal = field(propose(booked.trade(), proposer, FxTestClient.key()).body(), "reversalId");

        assertThat(decide(booked.trade(), reversal, "approval", proposer).body()).contains("fx.SelfApprovalRefused");
        assertThat(decide(booked.trade(), reversal, "rejection", proposer).body()).contains("fx.SelfApprovalRefused");
        assertThat(propose(booked.trade(), other, FxTestClient.key()).body()).contains("fx.ProposalPending");

        // Four-eyes by the CHECK alone: the domain bypassed, a decision by the proposer refused by the schema.
        String proposedBy = scalar("SELECT proposed_by FROM fx.trade_reversal WHERE id = ?::uuid", reversal);
        assertRefused("UPDATE fx.trade_reversal SET status = 'REJECTED', decided_by = ?, decided_reason = 'self'"
                + " WHERE id = ?::uuid", proposedBy, reversal);
        assertRefused("UPDATE fx.trade_reversal SET proposed_reason = 'rewritten' WHERE id = ?::uuid", reversal);
        assertRefused("DELETE FROM fx.trade_reversal WHERE id = ?::uuid", reversal);
        assertRefused("UPDATE fx.trade_reversal SET status = 'APPROVED', decided_by = 'someone-else', decided_reason = 'no'"
                + " WHERE id = ?::uuid", reversal);

        HttpResponse<String> rejected = decide(booked.trade(), reversal, "rejection", other);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(decide(booked.trade(), reversal, "approval", sessionWith(RoleName.LEDGER_OPERATOR)).body())
                .contains("fx.ProposalNotPending");
        assertRefused("UPDATE fx.trade_reversal SET status = 'PROPOSED', decided_by = NULL, decided_reason = NULL,"
                + " decided_at = NULL WHERE id = ?::uuid", reversal);
        assertRefused("UPDATE fx.trade_reversal_event SET reason = 'rewritten' WHERE reversal_id = ?::uuid", reversal);
        assertThat(scalar("SELECT status FROM fx.trade WHERE id = ?::uuid", booked.trade())).isEqualTo("BOOKED");

        // A second proposal, approved: the trade REVERSED; then nothing more can be proposed, by the domain or by SQL.
        String second = field(propose(booked.trade(), proposer, FxTestClient.key()).body(), "reversalId");
        assertThat(decide(booked.trade(), second, "approval", other).statusCode()).isEqualTo(200);
        assertThat(propose(booked.trade(), other, FxTestClient.key()).body()).contains("fx.TradeNotReversible");
        assertRefused("INSERT INTO fx.trade_reversal (id, trade_id, status, proposed_by, proposed_reason, proposed_at,"
                + " correlation_id) VALUES (gen_random_uuid(), ?::uuid, 'PROPOSED', 'x', 'again', now(), 'c')", booked.trade());
        assertRefused("UPDATE fx.trade_reversal SET status = 'REJECTED' WHERE id = ?::uuid", second);
        assertThatThrownBy(() -> {
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                reversals.approve(app, UUID.fromString(second), new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                        "again", CorrelationId.generate(FxTestClient.IDS));
            }
        }).isInstanceOf(TradeReversals.ProposalNotPending.class);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("refusals write nothing: a destination wallet short of the mirror is 409 TradeNotReversible; a"
            + " cross-border trade is never reversed; a customer or an FX controller without FX_TRADE_REVERSE is 403")
    void refusalsWriteNothing() throws Exception {
        Booked short_ = booked("100.00");
        String proposer = sessionWith(RoleName.LEDGER_OPERATOR);
        String reversal = field(propose(short_.trade(), proposer, FxTestClient.key()).body(), "reversalId");
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope platform = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            holds.place(app, LedgerAccountId.of(UUID.fromString(walletAccount(short_, "USD"))), money("0.01", "USD"));
            app.commit();
        }
        HttpResponse<String> refused = decide(short_.trade(), reversal, "approval", sessionWith(RoleName.LEDGER_OPERATOR));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("fx.TradeNotReversible");
        assertThat(scalar("SELECT status FROM fx.trade WHERE id = ?::uuid", short_.trade())).isEqualTo("BOOKED");
        assertThat(scalar("SELECT status FROM fx.trade_reversal WHERE id = ?::uuid", reversal)).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.reverse:fx-trade:" + short_.trade())).isZero();

        // A cross-border trade is never reversed (INV-REV-03) - judged by the birth trigger whenever the shared database
        // holds one (the corridor suites book them); the domain's own refusal is the same purpose check.
        if (count("SELECT count(*) FROM fx.trade WHERE purpose = 'CROSS_BORDER'") > 0) {
            assertRefused("INSERT INTO fx.trade_reversal (id, trade_id, status, proposed_by, proposed_reason, proposed_at,"
                    + " correlation_id) SELECT gen_random_uuid(), id, 'PROPOSED', 'x', 'a cross-border trade', now(), 'c'"
                    + " FROM fx.trade WHERE purpose = 'CROSS_BORDER' LIMIT 1");
        }

        Booked guarded = booked("100.00");
        HttpResponse<String> customer = client().post(TRADES + guarded.trade() + "/reversal", "{\"reason\":\"mine\"}",
                guarded.customer().token(), FxTestClient.key());
        assertThat(customer.statusCode()).as(customer.body()).isIn(401, 403);
        HttpResponse<String> controller = propose(guarded.trade(), sessionWith(RoleName.FX_CONTROLLER), FxTestClient.key());
        assertThat(controller.statusCode()).as(controller.body()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM fx.trade_reversal WHERE trade_id = ?::uuid", guarded.trade())).isZero();
    }

    @Test
    @DisplayName("atomicity: a fault injected beneath the trade's REVERSED edge rolls the approval back whole - no mirror,"
            + " the proposal still PROPOSED, no unwind; lifted, the same approval completes once")
    void theApprovalIsAtomic() throws Exception {
        Booked booked = booked("100.00");
        UUID cover = coverOf(booked.trade());
        dispatch.dispatchNow(cover, Actor.SYSTEM);
        String reversal = field(propose(booked.trade(), sessionWith(RoleName.LEDGER_OPERATOR), FxTestClient.key()).body(),
                "reversalId");
        String approver = sessionWith(RoleName.LEDGER_OPERATOR);
        String name = "probe_refuse_reversed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection owner = DatabaseRoles.migrator(); java.sql.Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE FUNCTION fx." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id = '"
                    + booked.trade() + "'::uuid AND NEW.status = 'REVERSED' THEN RAISE EXCEPTION 'injected fault'; END IF;"
                    + " RETURN NEW; END $$");
            ddl.execute("CREATE TRIGGER " + name + " BEFORE UPDATE ON fx.trade FOR EACH ROW EXECUTE FUNCTION fx." + name + "()");
        }
        try {
            HttpResponse<String> failed = decide(booked.trade(), reversal, "approval", approver);
            assertThat(failed.statusCode()).as(failed.body()).isGreaterThanOrEqualTo(500);
            assertThat(scalar("SELECT status FROM fx.trade_reversal WHERE id = ?::uuid", reversal)).isEqualTo("PROPOSED");
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    "ledger.reverse:fx-trade:" + booked.trade())).isZero();
            assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = (SELECT quote_id FROM fx.trade WHERE id = ?::uuid)"
                    + " AND kind = 'UNWIND'", booked.trade())).isZero();
        } finally {
            try (Connection owner = DatabaseRoles.migrator(); java.sql.Statement ddl = owner.createStatement()) {
                ddl.execute("DROP TRIGGER " + name + " ON fx.trade");
                ddl.execute("DROP FUNCTION fx." + name + "()");
            }
        }
        assertThat(decide(booked.trade(), reversal, "approval", approver).statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.reverse:fx-trade:" + booked.trade())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fx.cover WHERE quote_id = (SELECT quote_id FROM fx.trade WHERE id = ?::uuid)"
                + " AND kind = 'UNWIND'", booked.trade())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ plumbing

    record Booked(FxTestClient.Customer customer, UUID product, String trade) {}

    /** A customer funded 3,000.00 EUR converting {@code amount} EUR to USD (fixed source). */
    private Booked booked(String amount) throws Exception {
        FxTestClient.Customer customer = client().verifiedCustomer();
        UUID product = client().openWallet(customer, "EUR");
        fund(postings, product, money("3000.00", "EUR"));
        String trade = convertedTrade(client(), customer, "EUR", "USD", "FIXED_SOURCE", amount);
        return new Booked(customer, product, trade);
    }

    private HttpResponse<String> propose(String trade, String session, String key) throws Exception {
        return client().post(TRADES + trade + "/reversal", "{\"reason\":\"the customer converted the wrong amount\"}",
                session, key);
    }

    private HttpResponse<String> decide(String trade, String reversal, String act, String session) throws Exception {
        return client().post(TRADES + trade + "/reversal/" + reversal + "/" + act,
                "{\"reason\":\"checked against the customer's instruction\"}", session, null);
    }

    private static long walletBalance(Booked booked, String currency) throws Exception {
        return count("SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)"
                + " FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " WHERE a.owner_ref::text = ? AND a.purpose = 'CUSTOMER_WALLET' AND a.currency = ?",
                booked.product().toString(), currency);
    }

    private static String walletAccount(Booked booked, String currency) throws Exception {
        return scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref::text = ? AND purpose = 'CUSTOMER_WALLET'"
                + " AND currency = ?", booked.product().toString(), currency);
    }

    private void assertBooksProve() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            app.setAutoCommit(false);
            FxBooksProof.Report report = booksProof.prove(app);
            app.rollback();
            assertThat(report.clean()).as("the FX books prove: %s", report.lines()).isTrue();
        }
    }

    /** A raw statement the schema must refuse, run as the application role with every domain check bypassed. */
    private static void assertRefused(String sql, Object... parameters) {
        assertThatThrownBy(() -> {
            try (Connection app = DatabaseRoles.application(); PreparedStatement statement = app.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                statement.executeUpdate();
            }
        }).as(sql).isInstanceOf(SQLException.class);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "rv." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client().post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key()).statusCode())
                .isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(FxTestClient.IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client().post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + FxTestClient.PASSWORD + "\"}", null, FxTestClient.key());
        return field(session.body(), "sessionToken");
    }
}
