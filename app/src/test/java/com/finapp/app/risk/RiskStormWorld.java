package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.risk.RiskPolicy;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The risk storm's world (`P13-TST-001`; the BNPL storm's {@code BnplStormWorld} shape): the doors of both instances as a
 * client reaches them, every answer classified by the {@link Tally} - a response outside its act's closed set, a 5xx or
 * a transport failure no same-key retry resolved is an unexplained failure; the storm's actors (customers with a wallet,
 * a bank account, a card and a password and a {@code MULTI_FACTOR} session each; merchants with an API key; operators
 * holding exactly their role, each stepped up); the money they move funded only through the platform's own doors - card
 * top-ups confirmed at the scheme's simulator, a treasury customer's transfers - never a fixture posting, so every
 * journal entry carries a namespace monitoring's register knows; the policy doors
 * driven under four eyes across instances; and the database read as the schema owner, outside any snapshot (the census
 * reads its own snapshot - {@link RiskStormCensus}).
 *
 * <p><strong>Every subject answer is kept</strong> ({@link Door#subjectAnswers}): the answers the customers and the
 * merchants received - the needle's input at the end (the tipping-off census's second half, {@code INV-RSK-08}): no risk
 * word in a judged movement's answer, no risk record's identifier in any of them.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class RiskStormWorld {

    static final String PASSWORD = "a-perfectly-fine-pw-7";
    static final String NEW_PASSWORD = "a-perfectly-fine-pw-8";
    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final Clock UTC = Clock.systemUTC();
    static final IdGenerator IDS = new IdGenerator(UTC, new SecureRandom());
    static final ObjectMapper JSON = new ObjectMapper();

    static final String TRANSFERS = "/v1/transfers";
    static final String WITHDRAWALS = "/v1/me/withdrawals";
    static final String SESSIONS = "/v1/checkout/sessions";
    static final String CONFIRMATION = "/v1/checkout/sessions/confirmation";
    static final String POLICIES = "/v1/operator/risk/policies";
    static final String CASES = "/v1/operator/risk/cases";
    static final String ASSESSMENTS = "/v1/operator/risk/assessments";
    static final String REPROCESSING = "/v1/operator/risk/monitoring/reprocessing";

    /**
     * Words no judged movement's answer to a customer may carry (`INV-RSK-08`; the seam suites' lists, merged): the
     * decisions' codes, the reasons, the machinery.
     */
    static final List<String> RISK_WORDS = List.of("risk_unavailable", "amount_band", "velocity", "score",
            "assessment", "challenge", "screening", "reason1", "no_policy", "hold_expired", "held_until", "risk.");

    /**
     * The storm's seed: {@code FINAPP_STORM_SEED}, else the clock - printed at the start so a red run's choices replay as
     * far as two instances' scheduling allows.
     */
    static final long SEED = seed();
    static final Random RANDOMNESS = new Random(SEED);
    private static final Pattern CODE = Pattern.compile("\"code\":\"([^\"]*)\"");

    // ================================================================== the tally

    /**
     * Every response the storm received, by act and outcome, and the unexplained failures. Never an identifier or an
     * amount in what it keeps: the act's name, the status, the code.
     */
    static final class Tally {

        final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        final List<String> unexplained = new CopyOnWriteArrayList<>();

        void count(String outcome) {
            outcomes.computeIfAbsent(outcome, ignored -> new AtomicInteger()).incrementAndGet();
        }

        int of(String outcome) {
            AtomicInteger found = outcomes.get(outcome);
            return found == null ? 0 : found.get();
        }

        /** The sum of every outcome whose name starts with {@code prefix}. */
        int startingWith(String prefix) {
            return outcomes.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                    .mapToInt(e -> e.getValue().get()).sum();
        }

        void unexplained(String what) {
            unexplained.add(what);
        }

        Map<String, Integer> snapshot() {
            Map<String, Integer> copy = new TreeMap<>();
            outcomes.forEach((name, value) -> copy.put(name, value.get()));
            return copy;
        }
    }

    /** One response, as the storm judges it. */
    record Answer(int status, String body, String code) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        boolean is(int expected, String expectedCode) {
            return status == expected && expectedCode.equals(code);
        }

        String field(String name) {
            return RiskStormWorld.field(body, name);
        }

        Optional<String> optionalField(String name) {
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }

        long number(String name) {
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":(-?\\d+)").matcher(body);
            assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
            return Long.parseLong(matcher.group(1));
        }
    }

    /** Who an answer was given to: a subject of risk's judgement (a customer, a merchant) or an operator. */
    enum Audience {
        SUBJECT,
        OPERATOR
    }

    /** A subject's answer, kept for the needle: the route template, whether it answered a judged movement, the body. */
    record SubjectAnswer(String route, boolean judged, String body) {}

    /**
     * One instance's doors, as a client reaches them. A client timeout under a key is resolved by the same key on the
     * other instance; while the instance is being killed its doors route to the other.
     */
    static final class Door {

        final String name;
        final int port;
        private final Tally tally;
        volatile Door other;
        volatile boolean down;
        /** Every answer a customer or a merchant received, on either instance (shared between the two doors). */
        final List<SubjectAnswer> subjectAnswers;
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        Door(String name, int port, Tally tally, List<SubjectAnswer> subjectAnswers) {
            this.name = name;
            this.port = port;
            this.tally = tally;
            this.subjectAnswers = subjectAnswers;
        }

        Answer get(String path, String bearer, Audience audience) throws Exception {
            if (down && other != null) {
                return other.get(path, bearer, audience);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(90)).GET();
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            try {
                return keep(send(request.build()), path, audience);
            } catch (java.io.IOException refused) {
                if (down && other != null) {
                    tally.count("a read cut by the restart, read on the other instance");
                    return other.get(path, bearer, audience);
                }
                tally.unexplained(template(path) + " failed at the transport: " + refused.getClass().getSimpleName());
                throw refused;
            }
        }

        Answer post(String path, String body, String bearer, String key, Audience audience) throws Exception {
            return send("POST", path, body, bearer, key, audience);
        }

        Answer put(String path, String body, String bearer, Audience audience) throws Exception {
            return send("PUT", path, body, bearer, null, audience);
        }

        private Answer send(String method, String path, String body, String bearer, String key, Audience audience)
                throws Exception {
            if (down && other != null) {
                tally.count("routed to the other instance while one restarts");
                return other.send(method, path, body, bearer, key, audience);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (key != null) {
                request.header(IdempotencyKeyHeader.NAME, key);
            }
            try {
                Answer answer = send(request.build());
                if (answer.status() >= 500 && down && other != null) {
                    tally.count("a request cut by the restart, retried on the other instance");
                    return other.send(method, path, body, bearer, key, audience);
                }
                return keep(answer, path, audience);
            } catch (java.io.IOException refused) {
                if (refused instanceof HttpTimeoutException && key != null && other != null) {
                    tally.count("timeout resolved by a same-key retry");
                    return other.send(method, path, body, bearer, key, audience);
                }
                if (down && other != null) {
                    tally.count("a request cut by the restart, retried on the other instance");
                    return other.send(method, path, body, bearer, key, audience);
                }
                tally.unexplained(template(path) + " failed at the transport: " + refused.getClass().getSimpleName());
                throw refused;
            }
        }

        private Answer keep(Answer answer, String path, Audience audience) {
            if (audience == Audience.SUBJECT) {
                String route = template(path);
                boolean judged = route.equals(TRANSFERS) || route.startsWith(WITHDRAWALS) || route.equals(CONFIRMATION)
                        || route.startsWith("/v1/me/cross-border/payments") || route.startsWith("/v1/payments");
                subjectAnswers.add(new SubjectAnswer(route, judged, answer.body()));
            }
            return answer;
        }

        private static Answer send(HttpRequest request) throws Exception {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            Matcher code = CODE.matcher(body);
            return new Answer(response.statusCode(), body, code.find() ? code.group(1) : "");
        }
    }

    /**
     * Judges {@code answer} for {@code act} against its closed set: a 2xx, or one of {@code expected} -
     * {@code "409 risk.CaseRevisionStale"} or a bare {@code "409"} - and counts it. Anything else is unexplained.
     */
    static boolean judged(Tally tally, String act, Answer answer, String... expected) {
        String seen = answer.status() + (answer.code().isEmpty() ? "" : " " + answer.code());
        if (answer.ok()) {
            tally.count(act + " " + answer.status());
            return true;
        }
        for (String allowed : expected) {
            if (allowed.equals(seen) || allowed.equals(Integer.toString(answer.status()))) {
                tally.count(act + " " + seen);
                return true;
            }
        }
        tally.count(act + " UNEXPLAINED " + seen);
        tally.unexplained(act + " answered " + seen + ": " + abbreviated(answer.body()));
        return false;
    }

    /** An act that must be REFUSED (a self-approval, a stale revision): a 2xx is the breach itself. */
    static boolean refused(Tally tally, String act, Answer answer, String... expected) {
        if (answer.ok()) {
            tally.count(act + " UNEXPLAINED " + answer.status());
            tally.unexplained(act + " answered " + answer.status() + " - a refusal was due");
            return false;
        }
        return judged(tally, act, answer, expected);
    }

    static String abbreviated(String body) {
        return body == null ? "" : body.substring(0, Math.min(300, body.length()));
    }

    /** The route template of {@code path} - its identifiers replaced, so a tally names nothing. */
    static String template(String path) {
        return path.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}")
                .replaceAll("\\?.*", "");
    }

    static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, abbreviated(body)).isTrue();
        return matcher.group(1);
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static long seed() {
        String given = System.getProperty("finapp.storm.seed", System.getenv("FINAPP_STORM_SEED"));
        return given == null || given.isBlank() ? System.currentTimeMillis() : Long.parseLong(given.trim());
    }

    // ================================================================== the actors

    /**
     * A customer: the login, the identity and the party, the party's customer row, the EUR wallet product and its ledger
     * account, a card and a bank account registered, a password-only session and a MULTI_FACTOR one (mutable: a
     * password change rotates the session).
     */
    static final class Customer {
        final String login;
        final UUID identity;
        final UUID party;
        final UUID customerId;
        final UUID wallet;
        final UUID walletLedger;
        volatile String card;
        volatile String bank;
        volatile String passwordToken;
        volatile String mfaToken;
        volatile String password = PASSWORD;

        Customer(String login, UUID identity, UUID party, UUID customerId, UUID wallet, UUID walletLedger) {
            this.login = login;
            this.identity = identity;
            this.party = party;
            this.customerId = customerId;
            this.wallet = wallet;
            this.walletLedger = walletLedger;
        }
    }

    /** A merchant: its id, its party and its API key. */
    record Merchant(String id, UUID party, String apiKey) {}

    /** An operator holding exactly one role: its MULTI_FACTOR session, its party, identity and login. */
    record Operator(RoleName role, String token, String passwordToken, UUID party, UUID identity, String login) {}

    private final Door door;
    private final Authorization authorization;
    private final PostingService postings;
    private final SimulatedProvider provider;
    private final Tally tally;

    RiskStormWorld(Door door, Authorization authorization, PostingService postings, SimulatedProvider provider,
            Tally tally) {
        this.door = door;
        this.authorization = authorization;
        this.postings = postings;
        this.provider = provider;
        this.tally = tally;
    }

    /** The provider answers every exchange, tokenisation, card and scheme call with fresh references. */
    void stubTheProviders() {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-{{randomValue length=12 type='ALPHANUMERIC'}}\","
                        + "\"suffix\":\"6819\",\"payee\":\"match\"}");
        schemeAccepts();
        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"tokenised\","
                + "\"token\":\"tok_{{randomValue length=12 type='ALPHANUMERIC'}}\",\"brand\":\"Visa\",\"last4\":\"4242\","
                + "\"expiryMonth\":12,\"expiryYear\":2030}");
        provider.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_auth-{{randomValue type='UUID'}}\"}");
        provider.succeedsWith(SimulatedCardPspAdapter.CAPTURES_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_cap-{{randomValue type='UUID'}}\"}");
        provider.succeedsWith(SimulatedCardPspAdapter.VOIDS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_void-{{randomValue type='UUID'}}\"}");
        provider.succeedsWith(SimulatedCardPspAdapter.REFUNDS_PATH, 200,
                "{\"status\":\"approved\",\"reference\":\"psp_ref-{{randomValue type='UUID'}}\"}");
        provider.succeedsWith(com.finapp.kyc.ScreeningAdapter.COUNTERPARTY_PATH, 200, "{\"status\":\"clear\"}");
    }

    /** The instant scheme accepts every transfer, its reference minted from our idempotency header. */
    void schemeAccepts() {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"rs-{{request.headers.Idempotency-Key}}\",\"cycle\":\"C1\"}");
    }

    /** The instant scheme refuses every transfer - scenario 7's window, quiesced. */
    void schemeRejects() {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, "{\"status\":\"rejected\"}");
    }

    int schemeSends() {
        return provider.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
    }

    /**
     * A customer: registered over the door, promoted ACTIVE (the seam suites' seeded promotion), signed in twice -
     * password only, then stepped up through a factor confirmed now - a EUR wallet opened and funded with {@code minor}
     * through a real posting, a bank account and a card registered through the real exchanges.
     */
    Customer customer(long minor) throws Exception {
        String login = "rs." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Answer registered = door.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":"
                + "\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, key(), Audience.SUBJECT);
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
        UUID identity;
        UUID party;
        try (Connection app = DatabaseRoles.application()) {
            try (PreparedStatement promote = app.prepareStatement("UPDATE party.customer SET status = 'ACTIVE',"
                    + " status_changed_at = GREATEST(now(), opened_at) WHERE party_id = (SELECT party_id FROM"
                    + " identity.identity WHERE login_identifier = ?)")) {
                promote.setString(1, login);
                promote.executeUpdate();
            }
            String[] ids = row(app, "SELECT id::text, party_id::text FROM identity.identity WHERE login_identifier = ?",
                    login);
            identity = UUID.fromString(ids[0]);
            party = UUID.fromString(ids[1]);
        }
        String password = signIn(login, PASSWORD);
        String mfa = stepUp(signIn(login, PASSWORD));
        Answer opened = door.post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}", password, key(),
                Audience.SUBJECT);
        assertThat(opened.status()).as(opened.body()).isEqualTo(201);
        UUID wallet = UUID.fromString(opened.field("id"));
        UUID customerId = UUID.fromString(scalar("SELECT id::text FROM party.customer WHERE party_id = ?", party));
        UUID ledger = UUID.fromString(scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref = ? AND"
                + " purpose = 'CUSTOMER_WALLET' AND currency = 'EUR'", wallet));
        Customer customer = new Customer(login, identity, party, customerId, wallet, ledger);
        customer.passwordToken = password;
        customer.mfaToken = mfa;
        customer.card = card(customer);
        topUp(customer, minor);
        customer.bank = bankAccount(customer);
        return customer;
    }

    /**
     * {@code minor} paid into the customer's wallet by its card - payments' real pay-in (created, confirmed, authorised
     * and captured through the simulated PSP; a top-up is no judged operation), in chunks of at most EUR 50,000: the
     * storm's money comes only from real flows, so every journal entry the monitoring intake reads is one it classifies
     * (a fixture's raw posting is an unclassified transaction, and its alert would fire at every rest).
     */
    void topUp(Customer customer, long minor) throws Exception {
        long left = minor;
        while (left > 0) {
            long chunk = Math.min(left, 50_000_00L);
            Answer created = door.post("/v1/payments", "{\"paymentMethodId\":\"" + customer.card + "\",\"amount\":\""
                    + decimal(chunk) + "\",\"currency\":\"EUR\"}", customer.mfaToken, key(), Audience.SUBJECT);
            assertThat(created.status()).as(created.body()).isEqualTo(201);
            Answer confirmed = door.post("/v1/payments/" + created.field("id") + "/confirmation", null,
                    customer.mfaToken, null, Audience.SUBJECT);
            assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
            assertThat(confirmed.field("status")).as(confirmed.body()).isEqualTo("SUCCEEDED");
            left -= chunk;
        }
    }

    /** The storm's treasury: a customer whose wallet funds the scenes' extra wallets by real transfers. */
    private volatile Customer treasury;

    /** A second EUR wallet of {@code customer}'s, funded with {@code minor} by a transfer from the treasury - its id. */
    UUID anotherWallet(Customer customer, long minor) throws Exception {
        Answer opened = door.post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                customer.mfaToken, key(), Audience.SUBJECT);
        assertThat(opened.status()).as(opened.body()).isEqualTo(201);
        UUID wallet = UUID.fromString(opened.field("id"));
        synchronized (this) {
            if (treasury == null) {
                treasury = customer(200_000_00L);
            }
        }
        Answer funded = door.post(TRANSFERS, "{\"sourceAccountId\":\"" + treasury.wallet + "\",\"destinationAccountId\":\""
                + wallet + "\",\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\",\"reference\":\"funding\"}",
                treasury.mfaToken, key(), Audience.SUBJECT);
        assertThat(funded.status()).as(funded.body()).isEqualTo(201);
        assertThat(funded.field("status")).as(funded.body()).isEqualTo("COMPLETED");
        return wallet;
    }

    /** The treasury's party, once made - a scene reserves it. */
    Optional<UUID> treasuryParty() {
        return Optional.ofNullable(treasury).map(t -> t.party);
    }

    /** A bank account registered through the instant scheme's exchange - the new payee of an ATO sequence. */
    String bankAccount(Customer customer) throws Exception {
        Answer registered = door.post("/v1/me/payment-methods/bank-accounts", "{\"grant\":\"blg-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "\",\"acknowledgeNoMatch\":false}",
                customer.mfaToken, key(), Audience.SUBJECT);
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
        return registered.field("id");
    }

    private String card(Customer customer) throws Exception {
        Answer attached = door.post("/v1/me/payment-methods", "{\"clientToken\":\"ctok_"
                + UUID.randomUUID().toString().substring(0, 8) + "\"}", customer.mfaToken, null, Audience.SUBJECT);
        assertThat(attached.status()).as(attached.body()).isEqualTo(201);
        return attached.field("id");
    }

    /** An operator holding exactly {@code role}: registered, assigned, signed in and stepped up. */
    Operator operator(RoleName role) throws Exception {
        String login = "rso." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Answer registered = door.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":"
                + "\"Grace Hopper\",\"password\":\"" + PASSWORD + "\"}", null, key(), Audience.OPERATOR);
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
        String[] ids = row("SELECT id::text, party_id::text FROM identity.identity WHERE login_identifier = ?", login);
        UUID identity = UUID.fromString(ids[0]);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "the risk storm");
            app.commit();
        }
        String password = signIn(login, PASSWORD);
        String mfa = stepUp(signIn(login, PASSWORD));
        return new Operator(role, mfa, password, UUID.fromString(ids[1]), identity, login);
    }

    /**
     * A trading merchant (the payment seam suite's shape): its party and customer rows seeded, created by a merchant
     * administrator, a fee schedule assigned, an API key issued.
     */
    Merchant merchant(Operator administrator) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'ORGANISATION',"
                    + " 'Storm Shop Holdings', now())", party);
            execute(app, "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES"
                    + " (?, ?, 'ACTIVE', now() - interval '1 hour', now())", IDS.next(), party);
        }
        Answer created = door.post("/v1/operator/merchants", "{\"partyId\":\"" + party + "\",\"legalName\":\"Storm Shop"
                + " GmbH\",\"displayName\":\"Storm Shop\",\"settlementCurrency\":\"EUR\"}", administrator.token(), key(),
                Audience.OPERATOR);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String merchantId = created.field("merchantId");
        String schedule = door.post("/v1/operator/fee-schedules", "{\"name\":\"Std " + UUID.randomUUID()
                + "\",\"currency\":\"EUR\"}", administrator.token(), null, Audience.OPERATOR).field("feeScheduleId");
        Answer priced = door.post("/v1/operator/fee-schedules/" + schedule + "/versions", "{\"rate\":0.029,"
                + "\"fixedAmountMinor\":30,\"roundingPolicy\":\"HALF_EVEN\",\"refundFeePolicy\":\"RETAINED\","
                + "\"reason\":\"initial pricing\"}", administrator.token(), null, Audience.OPERATOR);
        assertThat(priced.status()).as(priced.body()).isEqualTo(201);
        Answer assigned = door.put("/v1/operator/merchants/" + merchantId + "/fee-schedule", "{\"feeScheduleId\":\""
                + schedule + "\",\"reason\":\"standard terms\"}", administrator.token(), Audience.OPERATOR);
        assertThat(assigned.status()).as(assigned.body()).isEqualTo(200);
        Answer issued = door.post("/v1/operator/merchants/" + merchantId + "/api-keys", null, administrator.token(),
                key(), Audience.OPERATOR);
        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        return new Merchant(merchantId, party, issued.field("keyId") + "." + issued.field("secret"));
    }

    String signIn(String login, String password) throws Exception {
        Answer session = door.post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\""
                + password + "\"}", null, null, Audience.OPERATOR);
        assertThat(session.status()).as(session.body()).isIn(200, 201);
        return session.field("sessionToken");
    }

    /** A factor enrolled and confirmed now, and the session elevated to MULTI_FACTOR - its new token. */
    String stepUp(String token) throws Exception {
        Matcher secret = Pattern.compile("secret=([A-Z2-7]+)").matcher(door.post("/v1/me/mfa", null, token, null,
                Audience.OPERATOR).body());
        assertThat(secret.find()).as("the enrolment must carry a base32 secret").isTrue();
        Sensitive<String> factor = Sensitive.of(secret.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        long confirmed = -1;
        for (long candidate : new long[] {step - 1, step, step + 1}) {
            if (door.post("/v1/me/mfa/confirmation", "{\"code\":\"" + codeAt(factor, candidate) + "\"}", token, null,
                    Audience.OPERATOR).status() == 204) {
                confirmed = candidate;
                break;
            }
        }
        assertThat(confirmed).as("some step around this JVM's confirms the factor").isNotNegative();
        Answer elevated = door.post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 1) + "\"}",
                token, null, Audience.OPERATOR);
        if (elevated.status() == 401) {
            elevated = door.post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 2) + "\"}",
                    token, null, Audience.OPERATOR);
        }
        assertThat(elevated.status()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        assertThat(elevated.field("assurance")).isEqualTo("MULTI_FACTOR");
        return elevated.field("sessionToken");
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    // ================================================================== the policies

    /** {@code policy} as the policy door's proposal body - the desk suite's own conversion, never a copy. */
    static String proposal(RiskPolicy policy, String reason) throws Exception {
        return JSON.writeValueAsString(RiskPolicyDeskTest.request(policy, Map.of("reason", reason)));
    }

    // ================================================================== the database, as the owner

    private static final ConcurrentLinkedDeque<Connection> IDLE = new ConcurrentLinkedDeque<>();

    @FunctionalInterface
    interface OwnerWork<T> {
        T apply(Connection owner) throws SQLException;
    }

    /**
     * The schema owner's connections, reused across reads (the lending storm's `P11-DOC-001` lesson: a fresh connection
     * per read exhausts the host's ephemeral ports) - around ONE autocommit statement, never a shared snapshot.
     */
    static <T> T asOwner(OwnerWork<T> work) throws SQLException {
        try {
            return borrowed(work);
        } catch (SQLException failure) {
            if (!String.valueOf(failure.getSQLState()).startsWith("08")
                    && !String.valueOf(failure.getSQLState()).startsWith("57P")) {
                throw failure;
            }
            return borrowed(work);
        }
    }

    private static <T> T borrowed(OwnerWork<T> work) throws SQLException {
        Connection owner = IDLE.pollFirst();
        if (owner == null || owner.isClosed() || !owner.getAutoCommit()) {
            closeQuietly(owner);
            owner = DatabaseRoles.migrator();
        }
        boolean healthy = false;
        try {
            T result = work.apply(owner);
            healthy = true;
            return result;
        } finally {
            if (healthy && IDLE.size() < 16) {
                IDLE.offerFirst(owner);
            } else {
                closeQuietly(owner);
            }
        }
    }

    static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already gone
            }
        }
    }

    static String scalar(String sql, Object... parameters) throws SQLException {
        String[] found = row(sql, parameters);
        return found == null ? null : found[0];
    }

    static long count(String sql, Object... parameters) throws SQLException {
        String value = scalar(sql, parameters);
        return value == null ? 0 : Long.parseLong(value);
    }

    static String[] row(String sql, Object... parameters) throws SQLException {
        List<String[]> found = rows(sql, parameters);
        return found.isEmpty() ? null : found.get(0);
    }

    static String[] row(Connection connection, String sql, Object... parameters) throws SQLException {
        List<String[]> found = rows(connection, sql, parameters);
        return found.isEmpty() ? null : found.get(0);
    }

    static List<String[]> rows(String sql, Object... parameters) throws SQLException {
        return asOwner(owner -> rows(owner, sql, parameters));
    }

    static List<String> strings(String sql, Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        for (String[] row : rows(sql, parameters)) {
            values.add(row[0]);
        }
        return values;
    }

    static List<String[]> rows(Connection connection, String sql, Object... parameters) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement read = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                read.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                int columns = row.getMetaData().getColumnCount();
                while (row.next()) {
                    String[] values = new String[columns];
                    for (int i = 0; i < columns; i++) {
                        values[i] = row.getString(i + 1);
                    }
                    rows.add(values);
                }
            }
        }
        return rows;
    }

    static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
        }
    }

    /** The bootstrap owner's statement with every user trigger suspended - a scene's lever, never a path. */
    static void asBootstrap(String sql, Object... parameters) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
            }
            execute(owner, sql, parameters);
            owner.commit();
        }
    }

    static Instant databaseNow() throws SQLException {
        return Instant.parse(scalar("SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"));
    }

    /** Waits until {@code predicate} (a boolean query) is true on the database. */
    static void awaitDatabase(Duration patience, String what, String predicate, Object... parameters) throws Exception {
        long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            String value = scalar(predicate, parameters);
            if ("t".equals(value) || "true".equals(value)) {
                return;
            }
            assertThat(System.nanoTime()).as("the database never said: %s", what).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
