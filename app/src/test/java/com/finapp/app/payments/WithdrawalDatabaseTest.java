package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldService;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.PushAnswer;
import com.finapp.payments.PushRail;
import com.finapp.payments.RailId;
import com.finapp.payments.ReversalNotSupportedException;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalFailureReason;
import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalOutcomes;
import com.finapp.payments.WithdrawalResolution;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.WithdrawalStore;
import com.finapp.payments.Withdrawals;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * The wallet withdrawal, end to end and at every resolver (`P7-TSK-008`, ADR-0062 §§3, 6):
 * the whole deployed chain over HTTP — fund by a real card payment, register the bank
 * account through the real grant exchange, withdraw over the REAL
 * {@link SimulatedInstantSchemeAdapter} against the stubbed scheme — plus the seeded-row
 * batteries only the database can prove: the send-permit rule re-judged on the locked row,
 * {@code NEVER_RECEIVED} only past the DECLARED deadline, the sweep completing a lost
 * answer to exactly one entry, and the refused-first-send rule driven through a second
 * engine pointed at a dead port.
 *
 * <p>The phase plan's four gate scenarios land here for this flow: the lost response
 * ({@code UNKNOWN}, resolved by inquiry, one entry), the ten-way same-key race (one
 * effect), the affordability race (available never negative), and the keyed replay.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the wallet withdrawal over the instant rail (P7-TSK-008)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class WithdrawalDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    private static SimulatedProvider provider;

    @LocalServerPort private int port;

    @Autowired private Withdrawals engine;
    @Autowired private WithdrawalOutcomes outcomes;
    @Autowired private WithdrawalResolution resolution;
    @Autowired private WithdrawalStore<Connection> withdrawalStore;
    @Autowired private HoldService holdService;
    @Autowired private TransactionRunner transactions;
    @Autowired private IdGenerator ids;

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
        // One stub server, every provider: the card PSP funds the wallet, the tokeniser
        // mints the card method, the instant scheme takes the withdrawal.
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
        // The sweep's candidacy bounds, tiny so a seeded row is swept NOW; the
        // NEVER_RECEIVED bound stays honest - it rides the rail's DECLARED 90s deadline
        // plus this margin, which is exactly what the aged-permit fixtures exercise.
        registry.add("finapp.payments.withdrawal.sweeper.dispatched-age", () -> "PT0.05S");
        registry.add("finapp.payments.withdrawal.sweeper.unknown-age", () -> "PT0.05S");
        registry.add("finapp.payments.withdrawal.sweeper.margin", () -> "PT1S");
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // -----------------------------------------------------------------
    // The acceptance chain and the keyed door
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the acceptance chain holds: funded, registered, withdrawn - COMPLETED with"
            + " the scheme's reference and cycle, the hold released, EXACTLY ONE entry"
            + " DR wallet / CR INSTANT_CLEARING, and the balance explained")
    void theAcceptanceChainHolds() throws Exception {
        Fixture f = fundedFixture("20.00");
        schemeAccepts("sch-chain-" + suffix(), "CYCLE-7");

        HttpResponse<String> withdrawn =
                withdraw(f.token(), body(f.methodId(), "5.00", "USD"), someKey());
        assertThat(withdrawn.statusCode()).isEqualTo(201);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("COMPLETED");
        assertThat(withdrawn.body()).contains("\"failureReason\":null");
        String id = field(withdrawn.body(), "id");

        // The row carries the scheme's word (Phase 8's keys) and the routed rail.
        assertThat(oneString("SELECT status || '|' || scheme_reference || '|' ||"
                        + " settlement_cycle || '|' || rail FROM payments.withdrawal"
                        + " WHERE id = ?", UUID.fromString(id)))
                .startsWith("COMPLETED|sch-chain-")
                .endsWith("|CYCLE-7|instant");

        // EXACTLY ONE posting, keyed by the operation (INV-RAIL-04: the instant rail's own
        // clearing position, never the card PSP's).
        assertThat(count("SELECT count(*) FROM ledger.journal_entry"
                        + " WHERE idempotency_scope = 'ledger.post:wallet-withdrawal:" + id + "'"))
                .isEqualTo(1);
        assertThat(oneString(
                        "SELECT a.purpose FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE e.idempotency_scope = 'ledger.post:wallet-withdrawal:" + id + "'"
                                + " AND l.direction = 'CREDIT'"))
                .isEqualTo("INSTANT_CLEARING");

        // The hold is RELEASED and the money explained: 20 funded - 5 withdrawn = 15.
        assertThat(oneString("SELECT h.status FROM ledger.hold h JOIN payments.withdrawal w"
                        + " ON w.hold_reference = h.id WHERE w.id = ?", UUID.fromString(id)))
                .isEqualTo("RELEASED");
        assertThat(get("/v1/me/accounts/" + f.product() + "/balance", f.token()).body())
                .contains("\"settled\":\"15.00\"");

        // The pinned decision: the withdrawal subject, the bank pay-out rule - version 3
        // since P7-TSK-009 (V017's whole-version seed carries the rule forward verbatim).
        assertThat(oneString("SELECT chosen_rail || '|' || v.version::text"
                        + " FROM payments.routing_decision d"
                        + " JOIN payments.routing_policy_version v ON v.id = d.policy_version_id"
                        + " WHERE d.withdrawal_id = ?", UUID.fromString(id)))
                .isEqualTo("instant|3");

        // One initiated fact, one completed fact; the dispatch audited as the PERSON, the
        // outcome as the platform.
        assertThat(outboxCount("payments.WithdrawalInitiated", id)).isEqualTo(1);
        assertThat(outboxCount("payments.WithdrawalCompleted", id)).isEqualTo(1);
        assertThat(oneString("SELECT actor_type FROM platform.audit_record WHERE operation ="
                        + " 'payments.WithdrawalDispatched' AND target_id = ?", id))
                .isEqualTo("CUSTOMER");
        assertThat(oneString("SELECT actor_type FROM platform.audit_record WHERE operation ="
                        + " 'payments.WithdrawalOutcomeApplied' AND target_id = ?", id))
                .isEqualTo("SYSTEM");

        // The read surface answers the caller; the destination is in NO response.
        String read = get("/v1/me/withdrawals/" + id, f.token()).body();
        assertThat(field(read, "status")).isEqualTo("COMPLETED");
        assertThat(read).doesNotContain(f.destination());
    }

    @Test
    @DisplayName("the keyed door: a retried key replays without a second send, a changed"
            + " request is the distinct 409, and ten same-key racers produce one withdrawal")
    void theIdempotencyContractHolds() throws Exception {
        Fixture f = fundedFixture("20.00");
        schemeAccepts("sch-idem-" + suffix(), "C1");
        String key = someKey();
        String body = body(f.methodId(), "3.00", "USD");

        HttpResponse<String> first = withdraw(f.token(), body, key);
        assertThat(first.statusCode()).isEqualTo(201);
        String id = field(first.body(), "id");
        long sent = provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);

        HttpResponse<String> replayed = withdraw(f.token(), body, key);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(field(replayed.body(), "id")).isEqualTo(id);
        assertThat(provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH))
                .as("the replay answers the claim; nothing is sent twice (INV-PAY-04)")
                .isEqualTo(sent);

        assertThat(withdraw(f.token(), body(f.methodId(), "4.00", "USD"), key).statusCode())
                .as("a reused key for a different request is the distinct refusal"
                        + " (INV-IDEM-03)")
                .isEqualTo(409);

        // Ten racers, one key: one row, one entry, one completed fact - counted in the
        // tables, never inferred (gate scenario 2).
        String raceKey = someKey();
        String raceBody = body(f.methodId(), "2.00", "USD");
        List<Callable<Integer>> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            racers.add(() -> withdraw(f.token(), raceBody, raceKey).statusCode());
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            for (Future<Integer> status : pool.invokeAll(racers)) {
                assertThat(status.get()).isIn(201, 409, 425);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE customer_id ="
                        + " (SELECT customer_id FROM payments.withdrawal WHERE id = '" + id
                        + "'::uuid) AND dispatch_key = '" + raceKey + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten different-key withdrawals racing one wallet admit exactly the"
            + " affordable set - available never negative, every posting explained")
    void theAffordabilityRaceHolds() throws Exception {
        Fixture f = fundedFixture("10.00");
        schemeAccepts("sch-afford-" + suffix(), "C1");

        List<Callable<Integer>> racers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            racers.add(
                    () ->
                            withdraw(f.token(), body(f.methodId(), "3.00", "USD"), someKey())
                                    .statusCode());
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> status : pool.invokeAll(racers)) {
                statuses.add(status.get());
            }
        } finally {
            pool.shutdown();
        }
        // 10.00 affords exactly three 3.00 withdrawals (INV-BAL-04 under the lock);
        // the seven losers are the recorded 422, writing nothing.
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(7);
        assertThat(get("/v1/me/accounts/" + f.product() + "/balance", f.token()).body())
                .contains("\"settled\":\"1.00\"");
        // And the same key that was refused unfunded succeeds after a top-up: the claim
        // rolled back with the refusal, so the key is unburned.
        String retryKey = someKey();
        assertThat(withdraw(f.token(), body(f.methodId(), "3.00", "USD"), retryKey)
                        .statusCode())
                .isEqualTo(422);
        fund(f.token(), "5.00");
        assertThat(withdraw(f.token(), body(f.methodId(), "3.00", "USD"), retryKey)
                        .statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("a lost answer is honestly UNKNOWN with the hold STANDING and nothing"
            + " posted; the inquiry sweep resolves it to COMPLETED with the SAME reference"
            + " and exactly one entry")
    void aLostAnswerResolvesByInquiryToOneEntry() throws Exception {
        Fixture f = fundedFixture("20.00");
        provider.receivesTheRequestThenLosesTheResponse(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH);

        HttpResponse<String> withdrawn =
                withdraw(f.token(), body(f.methodId(), "5.00", "USD"), someKey());
        assertThat(withdrawn.statusCode()).isEqualTo(201);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("UNKNOWN");
        String id = field(withdrawn.body(), "id");
        String reference =
                oneString("SELECT end_to_end_reference FROM payments.withdrawal WHERE id = ?",
                        UUID.fromString(id));

        // INV-LIFE-03: the hold STANDS, nothing posted, nothing announced terminal.
        assertThat(oneString("SELECT h.status FROM ledger.hold h JOIN payments.withdrawal w"
                        + " ON w.hold_reference = h.id WHERE w.id = ?", UUID.fromString(id)))
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry"
                        + " WHERE idempotency_scope = 'ledger.post:wallet-withdrawal:" + id + "'"))
                .isZero();
        assertThat(outboxCount("payments.WithdrawalCompleted", id)).isZero();

        // The scheme answers the inquiry BY OUR REFERENCE with the accepted word.
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + reference,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-lost-" + suffix()
                        + "\",\"cycle\":\"C9\"}");
        Thread.sleep(80); // past the tiny unknown-age candidacy bound
        WithdrawalResolution.SweepResult swept = resolution.sweep();
        assertThat(swept.resolved()).isGreaterThanOrEqualTo(1);

        assertThat(oneString("SELECT status FROM payments.withdrawal WHERE id = ?",
                        UUID.fromString(id)))
                .isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry"
                        + " WHERE idempotency_scope = 'ledger.post:wallet-withdrawal:" + id + "'"))
                .as("resolved by inquiry to EXACTLY ONE entry (gate scenario 1)")
                .isEqualTo(1);
        // The scheme's bytes are retained against the withdrawal - the evidence's third
        // subject (INV-HIST-02), decrypt-verified by the store's own read path elsewhere.
        assertThat(count("SELECT count(*) FROM payments.provider_evidence"
                        + " WHERE withdrawal_id = '" + id + "'::uuid"
                        + " AND kind = 'QUERY_RESULT'"))
                .isGreaterThanOrEqualTo(1);

        // A second sweep converges quietly: the answer is already applied.
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + reference,
                200,
                "{\"status\":\"accepted\",\"reference\":\"sch-lost-again\",\"cycle\":\"C9\"}");
        resolution.sweep();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry"
                        + " WHERE idempotency_scope = 'ledger.post:wallet-withdrawal:" + id + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a rejection is FAILED(DECLINED) with the hold released and nothing posted;"
            + " unfunded and mismatched currencies are refusals that write nothing")
    void refusalsAndDeclinesAreHonest() throws Exception {
        Fixture f = fundedFixture("20.00");
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                200,
                "{\"status\":\"rejected\"}");
        HttpResponse<String> declined =
                withdraw(f.token(), body(f.methodId(), "5.00", "USD"), someKey());
        assertThat(declined.statusCode()).isEqualTo(201);
        assertThat(field(declined.body(), "status")).isEqualTo("FAILED");
        assertThat(field(declined.body(), "failureReason")).isEqualTo("DECLINED");
        String id = field(declined.body(), "id");
        assertThat(oneString("SELECT h.status FROM ledger.hold h JOIN payments.withdrawal w"
                        + " ON w.hold_reference = h.id WHERE w.id = ?", UUID.fromString(id)))
                .isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry"
                        + " WHERE idempotency_scope = 'ledger.post:wallet-withdrawal:" + id + "'"))
                .isZero();
        assertThat(get("/v1/me/accounts/" + f.product() + "/balance", f.token()).body())
                .as("a declined withdrawal leaves the wallet whole")
                .contains("\"settled\":\"20.00\"");

        HttpResponse<String> unfunded =
                withdraw(f.token(), body(f.methodId(), "999.00", "USD"), someKey());
        assertThat(unfunded.statusCode()).isEqualTo(422);
        assertThat(unfunded.body()).contains("payments.WithdrawalUnfunded");

        HttpResponse<String> mismatched =
                withdraw(f.token(), body(f.methodId(), "5.00", "EUR"), someKey());
        assertThat(mismatched.statusCode()).isEqualTo(422);
        assertThat(mismatched.body()).contains("payments.WithdrawalCurrencyMismatched");
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE customer_id ="
                        + " (SELECT customer_id FROM payments.withdrawal WHERE id = '" + id
                        + "'::uuid) AND status <> 'FAILED'"))
                .isZero();
    }

    @Test
    @DisplayName("no eligible rail is a RECORDED refusal with the wallet untouched - and the"
            + " repaired retry succeeds under a new key")
    void anUnroutableWithdrawalIsARecordedRefusal() throws Exception {
        Fixture f = fundedFixture("20.00");
        schemeAccepts("sch-avail-" + suffix(), "C1");
        execute("INSERT INTO payments.rail_availability"
                + " (rail, available, reason, changed_by, changed_at)"
                + " VALUES ('instant', false, 'suite: rail out', 'WithdrawalDatabaseTest',"
                + " now()) ON CONFLICT (rail) DO UPDATE SET available = false,"
                + " reason = 'suite: rail out', changed_at = now()");
        try {
            HttpResponse<String> refused =
                    withdraw(f.token(), body(f.methodId(), "5.00", "USD"), someKey());
            assertThat(refused.statusCode()).isEqualTo(422);
            assertThat(refused.body()).contains("payments.NoEligibleRail");
            // The refusal IS a decision row - the withdrawal subject with no chosen rail -
            // and no withdrawal row, no hold, no entry accompanies it.
            assertThat(count("SELECT count(*) FROM payments.routing_decision"
                            + " WHERE withdrawal_id IS NOT NULL AND chosen_rail IS NULL"))
                    .isGreaterThanOrEqualTo(1);
            assertThat(get("/v1/me/accounts/" + f.product() + "/balance", f.token()).body())
                    .contains("\"settled\":\"20.00\"");
        } finally {
            execute("UPDATE payments.rail_availability SET available = true,"
                    + " reason = 'suite: rail back', changed_at = now()"
                    + " WHERE rail = 'instant'");
        }
        assertThat(withdraw(f.token(), body(f.methodId(), "5.00", "USD"), someKey())
                        .statusCode())
                .as("the SAME withdrawal succeeds once the operator repairs availability")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("security: an enrolled identity is refused at PASSWORD with the key unburned"
            + " and nothing sent; ownership folds card-kind, stranger's and unknown methods"
            + " into one refusal; a stranger's read is one 404")
    void theSecurityNegativesHold() throws Exception {
        // The card method resolves to NO push destination: one refusal with unknown.
        Fixture f = fundedFixture("20.00");
        String cardMethod = attachInstrument(f.token());
        HttpResponse<String> cardKind =
                withdraw(f.token(), body(cardMethod, "5.00", "USD"), someKey());
        assertThat(cardKind.statusCode()).isEqualTo(422);
        assertThat(cardKind.body()).contains("payments.UnknownInstrument");
        HttpResponse<String> unknown =
                withdraw(f.token(), body(UUID.randomUUID().toString(), "5.00", "USD"),
                        someKey());
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(normalized(cardKind.body())).isEqualTo(normalized(unknown.body()));

        // A stranger cannot read the owner's withdrawal, and cannot spend their method.
        schemeAccepts("sch-own-" + suffix(), "C1");
        String id = field(
                withdraw(f.token(), body(f.methodId(), "2.00", "USD"), someKey()).body(),
                "id");
        String stranger = verifiedCustomer(someLogin());
        openAccount(stranger);
        assertThat(get("/v1/me/withdrawals/" + id, stranger).statusCode()).isEqualTo(404);
        assertThat(withdraw(stranger, body(f.methodId(), "2.00", "USD"), someKey())
                        .statusCode())
                .isEqualTo(422);

        // The step-up: enrolled at PASSWORD is refused, the key stays unburned, nothing is
        // sent; the SAME key succeeds at MULTI_FACTOR (the P7-TSK-007 proof at this door).
        String login = someLogin();
        String password = verifiedCustomer(login);
        openAccount(password);
        String method = registerBankAccount(password);
        fundVia(password);
        com.finapp.sharedkernel.security.Sensitive<String> secret = enrolAndConfirm(password);
        long sent = provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
        String key = someKey();
        HttpResponse<String> refused = withdraw(password, body(method, "1.00", "USD"), key);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH))
                .isEqualTo(sent);
        String elevated =
                tokenFrom(
                        post("/v1/authentications/mfa",
                                        "{\"code\":\"" + codeNow(secret) + "\"}", password)
                                .body());
        schemeAccepts("sch-stepup-" + suffix(), "C1");
        assertThat(withdraw(elevated, body(method, "1.00", "USD"), key).statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("INV-REV-03: the reversal is refused from the DECLARATION with zero"
            + " transactions, zero wire calls and nothing written - and no reversal route"
            + " exists on the surface")
    void theReversalIsRefusedByTheDomain() throws Exception {
        Fixture f = fundedFixture("20.00");
        schemeAccepts("sch-rev-" + suffix(), "C1");
        String id = field(
                withdraw(f.token(), body(f.methodId(), "5.00", "USD"), someKey()).body(),
                "id");

        long audits = count("SELECT count(*) FROM platform.audit_record");
        long events = count("SELECT count(*) FROM platform.outbox_event");
        long entries = count("SELECT count(*) FROM ledger.journal_entry");
        long wire = provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);

        assertThatThrownBy(() -> engine.reverse(WithdrawalId.of(UUID.fromString(id))))
                .isInstanceOf(ReversalNotSupportedException.class);

        assertThat(count("SELECT count(*) FROM platform.audit_record")).isEqualTo(audits);
        assertThat(count("SELECT count(*) FROM platform.outbox_event")).isEqualTo(events);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry")).isEqualTo(entries);
        assertThat(provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH))
                .isEqualTo(wire);
        assertThat(oneString("SELECT status FROM payments.withdrawal WHERE id = ?",
                        UUID.fromString(id)))
                .isEqualTo("COMPLETED");

        // No surface exists either: the void door does not know withdrawals, and the
        // collection accepts no DELETE.
        assertThat(post("/v1/payments/" + id + "/void", "{\"reason\":\"try\"}", f.token())
                        .statusCode())
                .isIn(403, 404);
        HttpRequest delete =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/me/withdrawals/" + id))
                        .header("Authorization", "Bearer " + f.token())
                        .DELETE()
                        .build();
        assertThat(send(delete).statusCode()).isEqualTo(405);
    }

    // -----------------------------------------------------------------
    // The permit rule and the declared deadline, on seeded rows
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a refused connection fails a withdrawal only on its FIRST send while the"
            + " row's permit is still that send's - a renewed permit makes it move nothing"
            + " (ADR-0057 §3/§4 on the locked row)")
    void theFirstSendRuleIsReJudgedOnTheRow() throws Exception {
        Fixture f = fundedFixture("20.00");
        // A second engine at a DEAD PORT: the refused connection is real, end to end.
        Withdrawals offline = offlineEngine();
        Actor person = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope actor = SecurityContext.enter(person);
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(ids)))) {
            Withdrawals.Initiated refused =
                    offline.withdraw(
                            someKey(),
                            Money.of(new BigDecimal("4.00"), CurrencyCode.of("USD")),
                            uow -> resolvedOf(f));
            assertThat(refused.status()).isEqualTo(WithdrawalStatus.FAILED);
            assertThat(refused.failureReason())
                    .contains(WithdrawalFailureReason.PROVIDER_UNAVAILABLE);
            assertThat(oneString("SELECT h.status FROM ledger.hold h JOIN payments.withdrawal w"
                            + " ON w.hold_reference = h.id WHERE w.id = ?",
                            refused.id().value()))
                    .isEqualTo("RELEASED");
        }

        // The rule's other half, on a seeded row: a NOTHING_SENT whose permit is NOT the
        // row's moves nothing - the takeover may have sent, and been paid.
        Seeded seeded = seedDispatched(f, "3.00", Instant.now(CLOCK));
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(ids)))) {
            WithdrawalOutcomes.Applied applied =
                    transactions.inTransaction(
                            uow -> {
                                Withdrawal locked =
                                        withdrawalStore
                                                .findForUpdate(uow, seeded.id())
                                                .orElseThrow();
                                return outcomes.applySendAnswer(
                                        uow,
                                        locked,
                                        PushAnswer.nothingSent(),
                                        true,
                                        locked.lastDispatchedAt().minusSeconds(30),
                                        CorrelationContext.current().orElseThrow());
                            });
            assertThat(applied.acting()).isFalse();
            assertThat(oneString("SELECT status FROM payments.withdrawal WHERE id = ?",
                            seeded.id().value()))
                    .as("a stale permit's refused connection concludes NOTHING")
                    .isEqualTo("DISPATCHED");
        }
    }

    @Test
    @DisplayName("UNRECOGNISED concludes NEVER_RECEIVED only past the DECLARED deadline plus"
            + " margin, judged against the row's latest permit - never a clock alone")
    void neverReceivedRespectsTheDeclaredDeadline() throws Exception {
        Fixture f = fundedFixture("20.00");
        // Older than 90s (the RAIL's declared deadline) + the 1s margin: conclusive.
        Seeded old = seedDispatched(f, "2.00", Instant.now(CLOCK).minus(Duration.ofHours(2)));
        // A thirty-second-old permit: past the sweep's tiny candidacy bound but well
        // inside the DECLARED 90s deadline - absence proves nothing yet. Thirty seconds
        // rather than zero so a bound bent to "margin alone" is CAUGHT rather than skated
        // past (the falsifiable form).
        Seeded young = seedDispatched(f, "2.00", Instant.now(CLOCK).minusSeconds(30));
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + old.reference(),
                200,
                "{\"status\":\"unrecognised\"}");
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + young.reference(),
                200,
                "{\"status\":\"unrecognised\"}");
        Thread.sleep(80);

        resolution.sweep();

        assertThat(oneString("SELECT status || '|' || failure_reason"
                        + " FROM payments.withdrawal WHERE id = ?", old.id().value()))
                .isEqualTo("FAILED|NEVER_RECEIVED");
        assertThat(oneString("SELECT h.status FROM ledger.hold h JOIN payments.withdrawal w"
                        + " ON w.hold_reference = h.id WHERE w.id = ?", old.id().value()))
                .isEqualTo("RELEASED");
        assertThat(oneString("SELECT status FROM payments.withdrawal WHERE id = ?",
                        young.id().value()))
                .as("within the declared deadline the hold STANDS (INV-LIFE-03)")
                .isEqualTo("DISPATCHED");
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private record Fixture(
            String token,
            String product,
            String methodId,
            String destination,
            UUID partyId,
            UUID customerId,
            UUID walletAccountId) {}

    private record Seeded(WithdrawalId id, String reference) {}

    /** A verified customer with an open USD wallet funded over the REAL card chain, and a
     * registered bank account through the REAL grant exchange. */
    private Fixture fundedFixture(String amount) throws Exception {
        String login = someLogin();
        String token = verifiedCustomer(login);
        String product = openAccount(token);
        String methodId = registerBankAccount(token);
        fund(token, amount);
        UUID partyId =
                oneUuid("SELECT party_id FROM identity.identity WHERE login_identifier = ?",
                        login);
        UUID customerId =
                oneUuid("SELECT id FROM party.customer WHERE party_id = ?", partyId);
        UUID walletAccountId =
                oneUuid("SELECT id FROM ledger.ledger_account WHERE owner_ref = ?"
                        + " AND purpose = 'CUSTOMER_WALLET'",
                        UUID.fromString(product));
        String destination =
                oneString("SELECT destination_reference FROM paymentmethods.payment_method"
                        + " WHERE id = ?", UUID.fromString(methodId));
        return new Fixture(
                token, product, methodId, destination, partyId, customerId, walletAccountId);
    }

    private Withdrawals.Resolved resolvedOf(Fixture f) {
        return new Withdrawals.Resolved(
                f.partyId(),
                f.customerId(),
                com.finapp.ledger.LedgerAccountId.of(f.walletAccountId()),
                CurrencyCode.of("USD"),
                UUID.fromString(f.methodId()),
                new ProviderReference(f.destination()));
    }

    /** A DISPATCHED row seeded directly (the sweeper-fixture pattern): a REAL hold placed
     * on the wallet, the permit at {@code permit} - inserts are not edits, so the
     * forward-only trigger does not bind the seed's age. */
    private Seeded seedDispatched(Fixture f, String amount, Instant permit) {
        Money money = Money.of(new BigDecimal(amount), CurrencyCode.of("USD"));
        String reference = ids.next().toString().replace("-", "");
        WithdrawalId id = WithdrawalId.of(ids.next());
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope correlation =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(ids)))) {
            transactions.inTransaction(
                    uow -> {
                        Hold hold =
                                holdService.place(
                                        uow,
                                        com.finapp.ledger.LedgerAccountId.of(
                                                f.walletAccountId()),
                                        money);
                        try (PreparedStatement insert =
                                uow.prepareStatement(
                                        "INSERT INTO payments.withdrawal (id, party_id,"
                                            + " customer_id, wallet_account_id,"
                                            + " payment_method_id, destination_reference,"
                                            + " amount_minor, currency, scale,"
                                            + " end_to_end_reference, rail, status,"
                                            + " dispatch_key, hold_reference, created_at,"
                                            + " last_dispatched_at) VALUES"
                                            + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'instant',"
                                            + " 'DISPATCHED', ?, ?, ?, ?)")) {
                            insert.setObject(1, id.value());
                            insert.setObject(2, f.partyId());
                            insert.setObject(3, f.customerId());
                            insert.setObject(4, f.walletAccountId());
                            insert.setObject(5, UUID.fromString(f.methodId()));
                            insert.setString(6, f.destination());
                            insert.setLong(7, money.minorUnits());
                            insert.setString(8, "USD");
                            insert.setShort(9, (short) money.scale());
                            insert.setString(10, reference);
                            insert.setString(11, "seed-" + reference);
                            insert.setObject(12, hold.id().value());
                            insert.setTimestamp(13, Timestamp.from(permit));
                            insert.setTimestamp(14, Timestamp.from(permit));
                            insert.executeUpdate();
                        } catch (SQLException failure) {
                            throw new IllegalStateException("seeding a withdrawal", failure);
                        }
                        return null;
                    });
        }
        return new Seeded(id, reference);
    }

    /** The engine against a port nothing listens on: the refused connection, real. */
    private Withdrawals offlineEngine() {
        PushRail dead =
                new SimulatedInstantSchemeAdapter(
                        URI.create("http://127.0.0.1:1"),
                        Duration.ofMillis(300),
                        "0123456789abcdef0123456789abcdef".getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII));
        return new Withdrawals(
                withdrawalStore,
                outcomes,
                holdService,
                routingStoreBean,
                railsBean,
                dead,
                RailId.of("instant"),
                evidenceBean,
                executorBean,
                auditWriterBean,
                ids,
                CLOCK,
                transactions);
    }

    @Autowired private com.finapp.payments.RoutingStore<Connection> routingStoreBean;
    @Autowired private com.finapp.payments.PaymentRails railsBean;
    @Autowired private com.finapp.payments.ProviderEvidenceStore<Connection> evidenceBean;
    @Autowired private com.finapp.platform.idempotency.IdempotentExecutor executorBean;
    @Autowired private com.finapp.platform.audit.AuditWriter<Connection> auditWriterBean;

    /** Accepts every transfer, minting the scheme reference FROM OUR idempotency header -
     * unique per withdrawal, stable across a re-send of the same one (the dedupe premise),
     * and never colliding on the platform-wide UNIQUE (the shared-database lesson). */
    private void schemeAccepts(String prefix, String cycle) {
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                200,
                "{\"status\":\"accepted\",\"reference\":\"" + prefix
                        + "-{{request.headers.Idempotency-Key}}\",\"cycle\":\"" + cycle
                        + "\"}");
    }

    /** Registers a bank account through the real exchange; returns the method id. */
    private String registerBankAccount(String token) throws Exception {
        String dest = "dest-wdr-" + suffix();
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"" + dest
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered =
                post(
                        "/v1/me/payment-methods/bank-accounts",
                        "{\"grant\":\"blg-" + suffix() + "\",\"acknowledgeNoMatch\":false}",
                        token,
                        true);
        assertThat(registered.statusCode()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    /** Funds the wallet by a REAL card payment through the stubbed PSP. */
    private void fundVia(String token) throws Exception {
        fund(token, "20.00");
    }

    private void fund(String token, String amount) throws Exception {
        String suffix = suffix();
        providerAuthorises("psp_auth-" + suffix);
        providerCaptures("psp_cap-" + suffix);
        String cardMethod = attachInstrument(token);
        String paymentId =
                field(payment(token, body(cardMethod, amount, "USD"), someKey()).body(),
                        "id");
        assertThat(field(confirm(token, paymentId).body(), "status"))
                .isEqualTo("SUCCEEDED");
    }

    // -----------------------------------------------------------------
    // MFA (the established idiom)
    // -----------------------------------------------------------------

    private com.finapp.sharedkernel.security.Sensitive<String> enrolAndConfirm(
            String sessionToken) throws Exception {
        com.finapp.sharedkernel.security.Sensitive<String> secret =
                com.finapp.sharedkernel.security.Sensitive.of(
                        secretFrom(post("/v1/me/mfa", null, sessionToken).body()));
        String confirming =
                com.finapp.identity.Authenticator.codeAt(
                        secret,
                        com.finapp.identity.TotpParameters.current(),
                        Instant.ofEpochSecond(
                                (currentStep() - 1)
                                        * com.finapp.identity.TotpParameters.current()
                                                .periodSeconds()));
        assertThat(post("/v1/me/mfa/confirmation", "{\"code\":\"" + confirming + "\"}",
                                sessionToken)
                        .statusCode())
                .isEqualTo(204);
        return secret;
    }

    private static String codeNow(com.finapp.sharedkernel.security.Sensitive<String> secret) {
        return com.finapp.identity.Authenticator.codeNow(
                secret, com.finapp.identity.TotpParameters.current(), CLOCK);
    }

    private static long currentStep() {
        return Instant.now(CLOCK).getEpochSecond()
                / com.finapp.identity.TotpParameters.current().periodSeconds();
    }

    // -----------------------------------------------------------------
    // HTTP and fixtures below the line (the PaymentEndpointDatabaseTest idiom)
    // -----------------------------------------------------------------

    private static void providerAuthorises(String pspReference) {
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
    }

    private static void providerCaptures(String pspReference) {
        provider.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"" + pspReference + "\"}");
    }

    private String verifiedCustomer(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        execute(
                "UPDATE party.customer SET status = 'ACTIVE',"
                        + " status_changed_at = GREATEST(now(), opened_at)"
                        + " WHERE party_id = (SELECT party_id FROM identity.identity"
                        + " WHERE login_identifier = '" + login + "')");
        return tokenFrom(authenticate(login).body());
    }

    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened =
                post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"USD\"}",
                        token, true);
        assertThat(opened.statusCode()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    private String attachInstrument(String token) throws Exception {
        String suffix = suffix();
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"tok_" + suffix
                        + "\",\"brand\":\"Visa\",\"last4\":\"4242\",\"expiryMonth\":12,"
                        + "\"expiryYear\":2030}");
        HttpResponse<String> attached =
                post("/v1/me/payment-methods", "{\"clientToken\":\"ctok_" + suffix + "\"}",
                        token, false);
        assertThat(attached.statusCode()).isEqualTo(201);
        return field(attached.body(), "id");
    }

    private static String body(String methodId, String amount, String currency) {
        return "{\"paymentMethodId\":\"" + methodId + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"" + currency + "\"}";
    }

    private HttpResponse<String> withdraw(String token, String body, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/me/withdrawals"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private HttpResponse<String> payment(String token, String body, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/payments"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private HttpResponse<String> confirm(String token, String paymentId) throws Exception {
        return post("/v1/payments/" + paymentId + "/confirmation", null, token, false);
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                true);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                false);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return send(request);
    }

    private HttpResponse<String> post(String path, String body, String token)
            throws Exception {
        return post(path, body, token, false);
    }

    private HttpResponse<String> post(String path, String body, String token, boolean keyed)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (keyed) {
            request.header(IdempotencyKeyHeader.NAME, someKey());
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    // -----------------------------------------------------------------
    // Parsing and counters
    // -----------------------------------------------------------------

    private static String field(String body, String name) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                                "\"" + java.util.regex.Pattern.quote(name) + "\":\"([^\"]+)\"")
                        .matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    private static String secretFrom(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("secret=([A-Z2-7]+)").matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"n\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"n\"");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String someLogin() {
        return "wdr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static String oneString(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).as("expected one row: %s", sql).isTrue();
            return row.getString(1);
        }
    }

    private static String oneString(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("expected one row: %s", sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static UUID oneUuid(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("expected one row: %s", sql).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static long outboxCount(String eventType, String aggregateId) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement =
                        app.prepareStatement(
                                "SELECT count(*) FROM platform.outbox_event"
                                        + " WHERE event_type = ? AND aggregate_id = ?")) {
            statement.setString(1, eventType);
            statement.setObject(2, UUID.fromString(aggregateId));
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }
}
