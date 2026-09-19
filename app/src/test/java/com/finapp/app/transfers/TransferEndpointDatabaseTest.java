package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The transfer surface over real HTTP (`P4-TSK-008`): the first customer-visible money movement,
 * end to end — and the composition root's copy of the execution command, where
 * {@code TransferExecutionDatabaseTest} deliberately composes its own.
 *
 * <p>The concurrency proofs are the command's and are <strong>cited, not repeated</strong>
 * (the `P1-TSK-012` rule): the claim's unique constraint, the source row's {@code FOR UPDATE}
 * and the ten-way drain live in {@code TransferExecutionDatabaseTest} and
 * {@code TransferConservationDatabaseTest}. What only this suite can prove is the
 * surface: the contract shape, the disclosure folds, the byte-for-byte replay, and M4.3's
 * fourth clause — a removed beneficiary refuses new transfers.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the transfer endpoints (P4-TSK-008)")
class TransferEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @LocalServerPort private int port;

    // -----------------------------------------------------------------

    @Test
    @DisplayName("a verified customer moves money to another's account: both balances move,"
            + " both statements show the entry's lines, and the chain is walked by identifier")
    void theAcceptanceChainHolds() throws Exception {
        // The backlog's "a verified customer with two accounts" is unsatisfiable by design:
        // ProductType has one value and one agreement per customer per product type is live
        // (P3-TSK-012's partial unique index), so a second open CONVERGES onto the first and
        // source equals destination - the correct FAILED(SELF_TRANSFER). The demonstration is
        // therefore two verified customers, which exercises the same three surfaces and more:
        // both parties see the SAME entry identifier from their own side.
        String sender = verifiedCustomer(someLogin());
        String receiver = verifiedCustomer(someLogin());
        String source = openAccount(sender);
        String destination = openAccount(receiver);
        fund(source, 10_00);

        HttpResponse<String> created =
                transfer(sender, body(source, destination, null, "3.00", "USD", "rent"),
                        someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(field(created.body(), "status")).isEqualTo("COMPLETED");
        String transferId = field(created.body(), "id");
        String entryId = field(created.body(), "journalEntryId");

        // Both balance endpoints move - the projection is transactional, nothing is polled -
        // and each side reads its own (the ownership chain, not a shared fixture's).
        assertThat(get("/v1/me/accounts/" + source + "/balance", sender).body())
                .contains("\"settled\":\"7.00\"");
        assertThat(get("/v1/me/accounts/" + destination + "/balance", receiver).body())
                .contains("\"settled\":\"3.00\"");

        // Both statements show the entry's lines, and the chain joins BY IDENTIFIER in both
        // directions (plan section 12): the view's journalEntryId is the statement line's
        // entryId, and that line's reference is the TRANSFER id - movement and evidence
        // pointing at each other.
        String period = "?from=" + LocalDate.now(CLOCK).minusDays(1)
                + "&to=" + LocalDate.now(CLOCK).plusDays(1);
        String sourceStatement =
                get("/v1/me/accounts/" + source + "/statement" + period, sender).body();
        String destinationStatement =
                get("/v1/me/accounts/" + destination + "/statement" + period, receiver).body();
        assertThat(sourceStatement).contains(entryId).contains(transferId);
        assertThat(destinationStatement).contains(entryId).contains(transferId);
        assertThat(lineFor(destinationStatement, entryId))
                .contains("\"direction\":\"CREDIT\"")
                .contains("\"amount\":\"3.00\"");

        // GET answers the judgement by identifier.
        String read = get("/v1/transfers/" + transferId, sender).body();
        assertThat(field(read, "status")).isEqualTo("COMPLETED");
        assertThat(field(read, "journalEntryId")).isEqualTo(entryId);

        // A FAILED judgement is a 201 whose body says so, never an HTTP error: the command was
        // accepted and its domain outcome recorded (the asynchronous-outcome contract shape).
        HttpResponse<String> refused =
                transfer(
                        sender, body(source, destination, null, "100.00", "USD", null),
                        someKey());
        assertThat(refused.statusCode()).isEqualTo(201);
        assertThat(field(refused.body(), "status")).isEqualTo("FAILED");
        assertThat(field(refused.body(), "failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(refused.body()).contains("\"journalEntryId\":null");

        // An inexact amount on an OTHERWISE-VALID request is refused, never rounded
        // (INV-MON-03). On a valid pair deliberately: the no-500 sweep's inexact shape names
        // an unknown destination, whose refusal would mask a silent rounding - the mutation
        // survived against the sweep and is caught only here (the P2-TSK-016 lesson).
        HttpResponse<String> inexact =
                transfer(sender, body(source, destination, null, "1.234", "USD", null),
                        someKey());
        assertThat(inexact.statusCode()).isEqualTo(422);
        assertThat(inexact.body()).contains("api.ValidationFailed").contains("amount");
    }

    @Test
    @DisplayName("a retried key replays the original body byte for byte; a changed request is"
            + " the distinct 409; a keyless request is the interceptor's 422")
    void theIdempotencyContractHolds() throws Exception {
        String token = verifiedCustomer(someLogin());
        String source = openAccount(token);
        String destination = destinationProduct().toString();
        fund(source, 10_00);
        String key = someKey();
        String request = body(source, destination, null, "2.50", "USD", "byte for byte");

        HttpResponse<String> first = transfer(token, request, key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(field(first.body(), "status")).isEqualTo("COMPLETED");

        // Byte for byte (INV-IDEM-01): the view renders the REPLAYED judgement plus columns
        // V002's trigger freezes for every writer, so the retry learns exactly what its
        // request did - not what the world looks like now.
        HttpResponse<String> retried = transfer(token, request, key);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(retried.body()).isEqualTo(first.body());

        // The same key with a different amount is a materially different request (INV-IDEM-03).
        HttpResponse<String> changed =
                transfer(token, body(source, destination, null, "2.51", "USD", "byte for byte"),
                        key);
        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("api.Conflict");

        HttpResponse<String> keyless = transfer(token, request, null);
        assertThat(keyless.statusCode()).isEqualTo(422);
        assertThat(keyless.body()).contains("api.IdempotencyKeyRequired");
    }

    @Test
    @DisplayName("a transfer names a beneficiary - and a removed one refuses new transfers with"
            + " nothing written (M4.3's fourth clause)")
    void aRemovedBeneficiaryRefusesNewTransfers() throws Exception {
        String token = verifiedCustomer(someLogin());
        String source = openAccount(token);
        fund(source, 10_00);
        UUID destination = destinationProduct();
        String beneficiaryId =
                field(createBeneficiary(token, destination).body(), "id");

        // The live entry resolves: the transfer executes through the saved destination.
        HttpResponse<String> viaBeneficiary =
                transfer(token, body(source, null, beneficiaryId, "1.00", "USD", null), someKey());
        assertThat(viaBeneficiary.statusCode()).isEqualTo(201);
        assertThat(field(viaBeneficiary.body(), "status")).isEqualTo("COMPLETED");

        assertThat(delete(token, "/v1/beneficiaries/" + beneficiaryId).statusCode())
                .isEqualTo(204);

        // The removed entry refuses NEW transfers: 422, and nothing written - no row, no
        // claim, counted in the table rather than inferred from the answer.
        long before = transferCount();
        HttpResponse<String> refused =
                transfer(token, body(source, null, beneficiaryId, "1.00", "USD", null), someKey());
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("transfers.UnknownDestination");
        assertThat(transferCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("every destination refusal is one byte-identical answer: raw unknown and"
            + " malformed, and a beneficiary unknown, a stranger's, malformed and removed")
    void destinationRefusalsAreOneAnswer() throws Exception {
        String token = verifiedCustomer(someLogin());
        String stranger = verifiedCustomer(someLogin());
        String source = openAccount(token);
        fund(source, 10_00);
        // The stranger's own live beneficiary, and the caller's own removed one.
        String strangersBeneficiary =
                field(createBeneficiary(stranger, destinationProduct()).body(), "id");
        String removedBeneficiary =
                field(createBeneficiary(token, destinationProduct()).body(), "id");
        assertThat(delete(token, "/v1/beneficiaries/" + removedBeneficiary).statusCode())
                .isEqualTo(204);

        long before = transferCount();
        List<String> bodies =
                List.of(
                        body(source, IDS.next().toString(), null, "1.00", "USD", null),
                        body(source, "not-a-uuid", null, "1.00", "USD", null),
                        body(source, null, IDS.next().toString(), "1.00", "USD", null),
                        body(source, null, strangersBeneficiary, "1.00", "USD", null),
                        body(source, null, "not-a-uuid", "1.00", "USD", null),
                        body(source, null, removedBeneficiary, "1.00", "USD", null));
        String reference = null;
        for (String request : bodies) {
            HttpResponse<String> refusal = transfer(token, request, someKey());
            assertThat(refusal.statusCode()).isEqualTo(422);
            assertThat(refusal.body()).contains("transfers.UnknownDestination");
            // The equality between the causes (the P1-TSK-016 idiom): none is readable from
            // the response, so the endpoint is an oracle over nobody's products or address
            // book - a stranger's beneficiary id indistinguishable from one that never existed.
            if (reference == null) {
                reference = normalized(refusal.body());
            } else {
                assertThat(normalized(refusal.body())).isEqualTo(reference);
            }
        }
        assertThat(transferCount()).as("a boundary refusal writes nothing").isEqualTo(before);
    }

    @Test
    @DisplayName("every source refusal is one byte-identical answer: unknown, malformed and"
            + " somebody else's product")
    void sourceRefusalsAreOneAnswer() throws Exception {
        String token = verifiedCustomer(someLogin());
        String destination = openAccount(token);
        UUID somebodyElses = destinationProduct();

        long before = transferCount();
        List<String> bodies =
                List.of(
                        body(IDS.next().toString(), destination, null, "1.00", "USD", null),
                        body("not-a-uuid", destination, null, "1.00", "USD", null),
                        body(somebodyElses.toString(), destination, null, "1.00", "USD", null));
        String reference = null;
        for (String request : bodies) {
            HttpResponse<String> refusal = transfer(token, request, someKey());
            assertThat(refusal.statusCode()).isEqualTo(422);
            assertThat(refusal.body()).contains("transfers.UnknownSource");
            if (reference == null) {
                reference = normalized(refusal.body());
            } else {
                assertThat(normalized(refusal.body())).isEqualTo(reference);
            }
        }
        assertThat(transferCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("exactly one destination arm: both and neither are the caller's 422")
    void exactlyOneDestinationArmIsRequired() throws Exception {
        String token = verifiedCustomer(someLogin());
        String source = openAccount(token);

        HttpResponse<String> both =
                transfer(
                        token,
                        body(
                                source,
                                IDS.next().toString(),
                                IDS.next().toString(),
                                "1.00",
                                "USD",
                                null),
                        someKey());
        HttpResponse<String> neither =
                transfer(token, body(source, null, null, "1.00", "USD", null), someKey());
        assertThat(both.statusCode()).isEqualTo(422);
        assertThat(both.body()).contains("api.ValidationFailed");
        assertThat(neither.statusCode()).isEqualTo(422);
        assertThat(neither.body()).contains("exactly one");
    }

    @Test
    @DisplayName("a stranger's transfer id, an unknown one and a malformed one are one 404")
    void aStrangersTransferIdIsOne404() throws Exception {
        String owner = verifiedCustomer(someLogin());
        String stranger = verifiedCustomer(someLogin());
        String source = openAccount(owner);
        String destination = destinationProduct().toString();
        fund(source, 10_00);
        String transferId =
                field(
                        transfer(owner, body(source, destination, null, "1.00", "USD", null),
                                        someKey())
                                .body(),
                        "id");

        HttpResponse<String> notYours = get("/v1/transfers/" + transferId, stranger);
        HttpResponse<String> unknown = get("/v1/transfers/" + IDS.next(), stranger);
        HttpResponse<String> malformed = get("/v1/transfers/not-a-uuid", stranger);
        assertThat(notYours.statusCode()).isEqualTo(404);
        // The equality between the causes: customer_id = ? in the statement is the ownership
        // check, and none of the three causes is readable from the response.
        assertThat(normalized(notYours.body()))
                .isEqualTo(normalized(unknown.body()))
                .isEqualTo(normalized(malformed.body()));

        assertThat(get("/v1/transfers/" + transferId, owner).statusCode())
                .as("the positive control: the owner reads their own judgement")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("the list shows only the caller's transfers, newest first")
    void theListShowsOnlyTheCallersTransfersNewestFirst() throws Exception {
        String a = verifiedCustomer(someLogin());
        String b = verifiedCustomer(someLogin());
        String aSource = openAccount(a);
        String bSource = openAccount(b);
        String destination = destinationProduct().toString();
        fund(aSource, 10_00);
        fund(bSource, 10_00);

        String first =
                field(
                        transfer(a, body(aSource, destination, null, "1.00", "USD", null),
                                        someKey())
                                .body(),
                        "id");
        String second =
                field(
                        transfer(a, body(aSource, destination, null, "2.00", "USD", null),
                                        someKey())
                                .body(),
                        "id");
        String theirs =
                field(
                        transfer(b, body(bSource, destination, null, "1.00", "USD", null),
                                        someKey())
                                .body(),
                        "id");

        String list = get("/v1/transfers", a).body();
        assertThat(list).contains(first).contains(second).doesNotContain(theirs);
        assertThat(list.indexOf(second))
                .as("newest first (plan section 9)")
                .isLessThan(list.indexOf(first));
    }

    @Test
    @DisplayName("no body shape is a 500")
    void noBodyShapeIsA500() throws Exception {
        String token = verifiedCustomer(someLogin());
        String source = openAccount(token);
        String destination = IDS.next().toString();

        List<String> shapes =
                List.of(
                        "{}",
                        body(source, destination, null, "abc", "USD", null),
                        // Inexact at the currency's scale: refused, never rounded (INV-MON-03).
                        body(source, destination, null, "1.234", "USD", null),
                        body(source, destination, null, "-5.00", "USD", null),
                        body(source, destination, null, "0", "USD", null),
                        body(source, destination, null, "1.00", "usd", null),
                        // ISO but no minor unit: an amount cannot be expressed in XXX.
                        body(source, destination, null, "1.00", "XXX", null),
                        body(source, destination, null, "1.00", "USD", "x".repeat(201)),
                        "{\"sourceAccountId\":12345,\"destinationAccountId\":\"" + destination
                                + "\",\"amount\":\"1.00\",\"currency\":\"USD\"}",
                        "{\"sourceAccountId\":{\"a\":1},\"amount\":\"1.00\","
                                + "\"currency\":\"USD\"}",
                        "[1,2,3]",
                        "not json at all");
        for (String shape : shapes) {
            HttpResponse<String> response = transfer(token, shape, someKey());
            assertThat(response.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isGreaterThanOrEqualTo(400)
                    .isLessThan(500);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    /**
     * Registers over HTTP, promotes the customer to {@code ACTIVE} (the verification gate's
     * answer, seeded - this suite's subject is the transfer surface, not onboarding), and
     * returns a session token.
     */
    private String verifiedCustomer(String login) throws Exception {
        assertThat(register(login).statusCode()).isEqualTo(201);
        try (Connection app = DatabaseRoles.application()) {
            // GREATEST(now(), opened_at): the container's clock runs behind the JVM's that
            // wrote opened_at, and the ordering constraint is right to refuse a backwards
            // status change (P1-TSK-031, the P2-TSK-006 idiom).
            execute(
                    app,
                    "UPDATE party.customer SET status = 'ACTIVE',"
                            + " status_changed_at = GREATEST(now(), opened_at)"
                            + " WHERE party_id ="
                            + " (SELECT party_id FROM identity.identity"
                            + "   WHERE login_identifier = ?)",
                    login);
        }
        return tokenFrom(authenticate(login).body());
    }

    /** Opens a USD wallet over the real endpoint; returns the product identifier. */
    private String openAccount(String token) throws Exception {
        HttpResponse<String> opened =
                post(
                        "/v1/me/accounts",
                        "{\"productType\":\"WALLET\",\"currency\":\"USD\"}",
                        token,
                        true);
        assertThat(opened.statusCode()).isEqualTo(201);
        return field(opened.body(), "id");
    }

    /** Funds the product's wallet with a real posting (the execution suite's fixture). */
    private void fund(String product, long minorUnits) throws Exception {
        JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope =
                        SecurityContext.enter(
                                new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            LedgerAccountId wallet =
                    ledgerAccounts.findAllOwned(app, UUID.fromString(product)).stream()
                            .filter(a -> a.purpose() == AccountPurpose.CUSTOMER_WALLET)
                            .findFirst()
                            .orElseThrow()
                            .id();
            LedgerAccount clearing =
                    ledgerAccounts
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                            .orElseThrow();
            LocalDate today = LocalDate.now(CLOCK);
            new PostingService(
                            new IdempotentExecutor(
                                    new JdbcIdempotencyRecordStore(),
                                    CLOCK,
                                    Duration.ofDays(1),
                                    Duration.ofMinutes(5)),
                            new JdbcJournalEntryStore(IDS),
                            new JdbcAuditWriter(),
                            new JdbcOutboxWriter(),
                            new JdbcBalanceProjection(),
                            IDS,
                            CLOCK,
                            PostingObserver.NONE)
                    .post(
                            app,
                            new PostingCommand(
                                    "fund-" + IDS.next(),
                                    today,
                                    today,
                                    "funding",
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(),
                                                    Direction.DEBIT,
                                                    Money.ofMinorUnits(minorUnits, USD)),
                                            new JournalLine(
                                                    wallet,
                                                    Direction.CREDIT,
                                                    Money.ofMinorUnits(minorUnits, USD)))));
            app.commit();
        }
    }

    /**
     * A destination product: somebody else's party, ACTIVE customer and wallet-bearing product
     * (the {@code BeneficiaryEndpointDatabaseTest} fixture, over the surfaces it exercises).
     */
    private UUID destinationProduct() throws Exception {
        return UUID.fromString(openAccount(verifiedCustomer(someLogin())));
    }

    private HttpResponse<String> createBeneficiary(String token, UUID destination)
            throws Exception {
        HttpResponse<String> created =
                post(
                        "/v1/beneficiaries",
                        "{\"displayName\":\"Aunt Vera\",\"destinationAccountId\":\""
                                + destination + "\"}",
                        token,
                        false);
        assertThat(created.statusCode()).isEqualTo(201);
        return created;
    }

    // -----------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------

    private static String body(
            String source,
            String destinationAccount,
            String beneficiary,
            String amount,
            String currency,
            String reference) {
        StringBuilder json = new StringBuilder("{\"sourceAccountId\":\"").append(source)
                .append("\"");
        if (destinationAccount != null) {
            json.append(",\"destinationAccountId\":\"").append(destinationAccount).append("\"");
        }
        if (beneficiary != null) {
            json.append(",\"beneficiaryId\":\"").append(beneficiary).append("\"");
        }
        json.append(",\"amount\":\"").append(amount).append("\"");
        json.append(",\"currency\":\"").append(currency).append("\"");
        if (reference != null) {
            json.append(",\"reference\":\"").append(reference).append("\"");
        }
        return json.append("}").toString();
    }

    private HttpResponse<String> transfer(String token, String body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/transfers"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header(IdempotencyKeyHeader.NAME, idempotencyKey);
        }
        return send(request.build());
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

    private HttpResponse<String> delete(String token, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Authorization", "Bearer " + token)
                        .DELETE()
                        .build();
        return send(request);
    }

    private HttpResponse<String> post(
            String path, String body, String token, boolean idempotencyKey) throws Exception {
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
        if (idempotencyKey) {
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

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }

    /** The statement line object carrying {@code entryId} — bounded by its braces. */
    private static String lineFor(String statementBody, String entryId) {
        int at = statementBody.indexOf(entryId);
        assertThat(at).as("the statement must carry entry %s", entryId).isNotNegative();
        int start = statementBody.lastIndexOf('{', at);
        int end = statementBody.indexOf('}', at);
        return statementBody.substring(start, end + 1);
    }

    /**
     * The correlation identifier differs per request by design, and {@code instance} is the
     * caller's own request path echoed back. Everything else must be byte-identical.
     */
    private static String normalized(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "\"correlationId\":\"normalized\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"normalized\"");
    }

    private static long transferCount() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement("SELECT count(*) FROM transfers.transfer")) {
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String someLogin() {
        return "mover." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
