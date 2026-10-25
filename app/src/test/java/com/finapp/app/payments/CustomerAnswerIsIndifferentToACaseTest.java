package com.finapp.app.payments;

import static com.finapp.app.payments.HeldWithdrawalFixtures.EUR;
import static com.finapp.app.payments.HeldWithdrawalFixtures.IDS;
import static com.finapp.app.payments.HeldWithdrawalFixtures.someKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.payments.HeldWithdrawalFixtures.Customer;
import com.finapp.ledger.HoldService;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderEvidenceStore;
import com.finapp.payments.PushRail;
import com.finapp.payments.RailId;
import com.finapp.payments.RailOperations;
import com.finapp.payments.RoutingStore;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.WithdrawalOutcomes;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.WithdrawalStore;
import com.finapp.payments.WithdrawalVerdict;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.CaseKind;
import com.finapp.risk.Cases;
import com.finapp.risk.RiskCase;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The tipping-off control at the customer's answer (`P13-TSK-018`; {@code INV-RSK-08}; {@code PHASE_13_PLAN.md} section
 * 11: "whether an AML case exists is never derivable from a customer response"; C6): <strong>for each customer read and
 * command of a judged movement, the response's status and body are equal with and without an open case on the
 * party</strong> - a timing difference being outside the claim. The reads - the held withdrawal, the accounts, the
 * wallet's balances - are read, a {@code FRAUD} case is opened on the party through the case machine, and they are read
 * again, byte-equal; the command - a withdrawal - is made by two customers alike but for the open case on one, and the
 * two answers are equal but for their identifiers and instants. Every answer names no risk word
 * ({@link HeldWithdrawalFixtures#RISK_WORDS}). The merchant's surface reaches no {@code risk} type at all
 * ({@code NoCustomerSurfaceReadsRiskTest}'s whole-reach rule), so its answers cannot turn on a case.
 * <strong>Written and owed</strong> (C5).
 *
 * <p>The held withdrawal is born {@code HELD} by the composition's engine with a scripted seam
 * ({@code WithdrawalHoldDatabaseTest}'s), so no risk version is activated in the shared container.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("no customer answer differs with a case open on the party (P13-TSK-018, INV-RSK-08)")
class CustomerAnswerIsIndifferentToACaseTest {

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private WithdrawalStore<Connection> withdrawalStore;
    @Autowired private WithdrawalOutcomes outcomes;
    @Autowired private HoldService holdService;
    @Autowired private RoutingStore<Connection> routingStore;
    @Autowired private PaymentRails rails;
    @Autowired private PushRail instantRail;
    @Autowired private ProviderEvidenceStore<Connection> evidence;
    @Autowired private IdempotentExecutor executor;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private TransactionRunner transactions;
    @Autowired private Cases cases;

    private HeldWithdrawalFixtures fixtures;

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
        fixtures = new HeldWithdrawalFixtures(port, provider);
        fixtures.schemeAccepts();
    }

    @Test
    @DisplayName("the reads: the held withdrawal, the accounts and the wallet's balances answer byte-equal before and"
            + " after a case opens on the party - and name no risk word")
    void theReadsAreIndifferent() throws Exception {
        Customer customer = fixtures.funded(1_000_00);
        UUID held = held(customer, 10_00);
        List<String> reads = List.of("/v1/me/withdrawals/" + held, "/v1/me/accounts",
                "/v1/me/accounts/" + customer.product() + "/balances");
        List<HttpResponse<String>> before = read(reads, customer);

        RiskCase opened = openCaseOn(customer.party());
        assertThat(opened.open()).isTrue();

        List<HttpResponse<String>> after = read(reads, customer);
        for (int i = 0; i < reads.size(); i++) {
            assertThat(after.get(i).statusCode()).as(reads.get(i)).isEqualTo(before.get(i).statusCode());
            assertThat(after.get(i).body()).as(reads.get(i)).isEqualTo(before.get(i).body());
            HeldWithdrawalFixtures.assertNoRiskWord(after.get(i).body());
        }
        assertThat(before.get(0).body()).contains("\"status\":\"HELD\"");
    }

    @Test
    @DisplayName("the command: two customers alike withdraw alike, one with a case open on its party - the same status"
            + " and the same answer but for identifiers and instants, neither naming a risk word")
    void theCommandIsIndifferent() throws Exception {
        Customer plain = fixtures.funded(1_000_00);
        Customer underCase = fixtures.funded(1_000_00);
        String plainMethod = fixtures.registerBankAccount(plain);
        String caseMethod = fixtures.registerBankAccount(underCase);
        openCaseOn(underCase.party());

        HttpResponse<String> without = fixtures.withdraw(plain.token(), plainMethod, "5.00", someKey());
        HttpResponse<String> with = fixtures.withdraw(underCase.token(), caseMethod, "5.00", someKey());
        assertThat(with.statusCode()).isEqualTo(without.statusCode());
        assertThat(masked(with.body())).isEqualTo(masked(without.body()));
        HeldWithdrawalFixtures.assertNoRiskWord(with.body());
        HeldWithdrawalFixtures.assertNoRiskWord(without.body());
    }

    // ------------------------------------------------------------------ fixtures

    private List<HttpResponse<String>> read(List<String> paths, Customer customer) throws Exception {
        List<HttpResponse<String>> answers = new java.util.ArrayList<>();
        for (String path : paths) {
            answers.add(fixtures.get(path, customer.token()));
        }
        return answers;
    }

    /** A FRAUD case opened on {@code party} by an analyst - another registered identity, placed in its own party. */
    private RiskCase openCaseOn(UUID party) throws Exception {
        Customer analyst = fixtures.customer();
        Actor actor = new Actor(analyst.identity().toString(), ActorType.CUSTOMER);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)));
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            RiskCase opened = cases.open(app, Cases.OpenRequest.of(CaseKind.FRAUD, SignalSubject.of(SubjectKind.PARTY,
                    party)), "the indifference suite's case", actor, CorrelationId.generate(IDS)).riskCase();
            app.commit();
            return opened;
        }
    }

    /** A withdrawal born HELD for an hour - the composition's engine, its seam answering HOLD. */
    private UUID held(Customer customer, long minor) throws Exception {
        Withdrawals.Resolved resolved = HeldWithdrawalFixtures.resolved(customer, EUR);
        Withdrawals holding = new Withdrawals(withdrawalStore, outcomes, holdService, routingStore, rails,
                RailOperations.ofPush(RailId.of("instant"), instantRail), evidence, executor, auditWriter, IDS,
                HeldWithdrawalFixtures.CLOCK, transactions,
                (unitOfWork, judged) -> WithdrawalVerdict.hold(Duration.ofHours(1)));
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(customer.identity().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            Withdrawals.Initiated initiated = holding.withdraw(someKey(), Money.ofMinorUnits(minor, EUR),
                    uow -> resolved);
            assertThat(initiated.status()).isEqualTo(WithdrawalStatus.HELD);
            return initiated.id().value();
        }
    }

    /** The answer with its identifiers and instants masked - what two alike commands share. */
    private static String masked(String body) {
        return body.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z", "<instant>")
                .replaceAll("\"(reference|providerReference|destination)\":\"[^\"]*\"", "\"$1\":\"<ref>\"");
    }
}
