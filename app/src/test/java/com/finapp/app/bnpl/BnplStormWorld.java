package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.LendingStormCredit;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContext;

/**
 * The BNPL storm's world (`P12-TST-001`; the lending storm's {@code LendingStormWorld} shape, `P11-TST-001`): the doors as
 * each instance serves them, every response classified and searched as it arrives, the actors by role, the terms over the
 * doors under four eyes, capital through the bank's statement chain, the merchants and the customers, the card pay-ins
 * that are the only way money enters a wallet, the "born in the past" ageing of a reserved obligation, and the database
 * reads every scene needs.
 *
 * <p>Money enters only by the platform's own paths: capital by the bank statement and recognition, wallets by payments'
 * card pay-in and lending's disbursements. Nothing here posts to the ledger.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class BnplStormWorld {

    static final String EUR = "EUR";
    /** A marker every simulated credit answer carries in a field no adapter reads: it may rest only in evidence. */
    static final String NEEDLE = "Quorvalith Bnplneedle";
    static final String PASSWORD = "a-perfectly-fine-pw-7";
    static final String BANK_SOURCE = "simulated-bank.statement";
    static final String APPLICATIONS = "/v1/me/bnpl-applications";
    static final String OFFERS = "/v1/me/bnpl-offers";
    static final String AGREEMENTS = "/v1/me/bnpl-agreements";
    static final String OPERATOR_AGREEMENTS = "/v1/operator/bnpl-agreements";
    static final String PURCHASES = "/v1/merchant/bnpl-purchases";
    static final String MERCHANT_DISPUTES = "/v1/merchant/bnpl-disputes";
    static final String OPERATOR_DISPUTES = "/v1/operator/bnpl-disputes";
    static final String BNPL_TERMS = "/v1/operator/bnpl/terms";
    static final String LOAN_APPLICATIONS = "/v1/me/loan-applications";
    static final String LOAN_OFFERS = "/v1/me/loan-offers";
    static final String LOANS = "/v1/me/loans";
    static final String LENDING_TERMS = "/v1/operator/lending/terms";
    static final String CONTRIBUTIONS = "/v1/operator/lending/capital-contributions";
    static final String PAYOUTS = "/v1/merchant/payouts";
    static final String SESSIONS = "/v1/checkout/sessions";
    static final String CONFIRMATION = "/v1/checkout/sessions/confirmation";
    /** The payout provider's path (merchant's adapter) - every payout's send arrives here. */
    static final String PAYOUT_PATH = com.finapp.merchant.SimulatedPayoutProvider.PAYOUTS_PATH;

    /**
     * The storm's seed: {@code -Dfinapp.storm.seed} or {@code FINAPP_STORM_SEED}, else the clock - printed at the start so
     * a red run's choices replay (as far as two instances' scheduling allows).
     */
    static final long SEED = seed();
    static final Random RANDOMNESS = new Random(SEED);
    /** Every amount the storm rendered into a request body or a statement (minor units) - the needle's catalogue. */
    static final Set<Long> RENDERED_AMOUNTS = ConcurrentHashMap.newKeySet();
    private static final Pattern CODE = Pattern.compile("\"code\":\"([^\"]*)\"");

    // ================================================================== the tally - every act, classified

    /**
     * Every response the storm received, by act and outcome; the needle's sightings; and the unexplained failures - a
     * response outside its act's closed set, a 5xx, a timeout no same-key retry resolved, an exception no armed fault
     * explains. Never an identifier or an amount in what it keeps: the act's name, the status, the code.
     */
    static final class Tally {

        final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        final List<String> unexplained = new CopyOnWriteArrayList<>();
        final List<String> needleSightings = new CopyOnWriteArrayList<>();
        /** Successful audited reads, by audit operation. */
        final Map<String, AtomicLong> auditedReads = new ConcurrentHashMap<>();

        void count(String outcome) {
            outcomes.computeIfAbsent(outcome, ignored -> new AtomicInteger()).incrementAndGet();
        }

        int of(String outcome) {
            AtomicInteger found = outcomes.get(outcome);
            return found == null ? 0 : found.get();
        }

        void audited(String operation) {
            auditedReads.computeIfAbsent(operation, ignored -> new AtomicLong()).incrementAndGet();
        }

        long auditedReads(String operation) {
            AtomicLong found = auditedReads.get(operation);
            return found == null ? 0 : found.get();
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

        boolean is(int expected) {
            return status == expected;
        }

        boolean is(int expected, String expectedCode) {
            return status == expected && expectedCode.equals(code);
        }

        String field(String name) {
            return BnplStormWorld.field(body, name);
        }

        Optional<String> optionalField(String name) {
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }
    }

    /**
     * One instance's doors, as a client reaches them: every response searched for the needle as it arrives. A client
     * timeout is resolved by the same key on the other instance - or is an unexplained failure. While the instance is
     * being killed its doors route to the other.
     */
    static final class Door {

        final String name;
        final int port;
        private final Tally tally;
        volatile Door other;
        volatile boolean down;
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        Door(String name, int port, Tally tally) {
            this.name = name;
            this.port = port;
            this.tally = tally;
        }

        Answer get(String path, String bearer) throws Exception {
            if (down && other != null) {
                return other.get(path, bearer);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60)).GET();
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            try {
                Answer answer = send(request.build(), path);
                if (answer.status() >= 500 && down && other != null) {
                    tally.count("a read cut by the restart, read on the other instance");
                    return other.get(path, bearer);
                }
                return answer;
            } catch (java.io.IOException refused) {
                if (down && other != null) {
                    tally.count("a read cut by the restart, read on the other instance");
                    return other.get(path, bearer);
                }
                tally.unexplained(template(path) + " failed at the transport: " + refused.getClass().getSimpleName());
                throw refused;
            }
        }

        Answer post(String path, String body, String bearer, String key) throws Exception {
            if (down && other != null) {
                tally.count("routed to the other instance while one restarts");
                return other.post(path, body, bearer, key);
            }
            try {
                Answer answer = send(postRequest(port, path, body, bearer, key), path);
                if (answer.status() >= 500 && down && other != null) {
                    tally.count("a request cut by the restart, retried on the other instance");
                    return other.post(path, body, bearer, key);
                }
                return answer;
            } catch (java.io.IOException refused) {
                if (!down || other == null || refused instanceof HttpTimeoutException) {
                    if (refused instanceof HttpTimeoutException && key != null && other != null) {
                        tally.count("timeout resolved by a same-key retry");
                        return other.post(path, body, bearer, key);
                    }
                    tally.unexplained(template(path) + " failed at the transport: " + refused.getClass().getSimpleName());
                    throw refused;
                }
                tally.count("a request cut by the restart, retried on the other instance");
                return other.post(path, body, bearer, key);
            }
        }

        private Answer send(HttpRequest request, String path) throws Exception {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            if (body.contains(NEEDLE)) {
                tally.needleSightings.add(template(path));
            }
            Matcher code = CODE.matcher(body);
            return new Answer(response.statusCode(), body, code.find() ? code.group(1) : "");
        }

        private static HttpRequest postRequest(int port, String path, String body, String bearer, String key) {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (key != null) {
                request.header(IdempotencyKeyHeader.NAME, key);
            }
            return request.build();
        }
    }

    /**
     * Judges {@code answer} for {@code act} against its closed set: a 2xx, or one of {@code expected} -
     * {@code "409 bnpl.ObligationClosed"} or a bare {@code "409"} - and counts it. Anything else is an unexplained failure,
     * and answered false.
     */
    static boolean judged(Tally tally, String act, Answer answer, String... expected) {
        String seen = answer.status() + (answer.code().isEmpty() ? "" : " " + answer.code());
        if (answer.status() >= 200 && answer.status() < 300) {
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
        tally.unexplained(act + " answered " + seen);
        return false;
    }

    /**
     * Judges an act that must be REFUSED - a self-approval, self-dealing, a key without the refund scope, a password-only
     * acceptance: as {@link #judged}, except that a 2xx is the breach itself and an unexplained failure, never an answer.
     */
    static boolean refused(Tally tally, String act, Answer answer, String... expected) {
        if (answer.status() >= 200 && answer.status() < 300) {
            tally.count(act + " UNEXPLAINED " + answer.status());
            tally.unexplained(act + " answered " + answer.status() + " - a refusal was due");
            return false;
        }
        return judged(tally, act, answer, expected);
    }

    /** The route template of {@code path} - its identifiers replaced, so a tally names nothing. */
    static String template(String path) {
        return path.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}")
                .replaceAll("\\?.*", "");
    }

    static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s", name).isTrue();
        return matcher.group(1);
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    /** A storm amount as every request body renders it (major units, two places) - registered for the needle's scan. */
    static String decimal(long minor) {
        RENDERED_AMOUNTS.add(minor);
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    static String money(long minor) {
        return "{\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\"}";
    }

    static long minor(String decimal) {
        return new BigDecimal(decimal).movePointRight(2).longValueExact();
    }

    private static long seed() {
        String given = System.getProperty("finapp.storm.seed", System.getenv("FINAPP_STORM_SEED"));
        return given == null || given.isBlank() ? System.currentTimeMillis() : Long.parseLong(given.trim());
    }

    static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p12tst1-" + UUID.randomUUID()))
                .causing(CausationId.of("p12tst1-storm"));
    }

    // ================================================================== the actors

    private final ApplicationContext beans;
    private final Authorization authorization;
    private final LendingStormCredit credit;
    private final SimulatedProvider provider;
    private final Door door;
    /** The EUR statement chain's place: the next sequence and the last closing (minor units). */
    private long nextSequence = 1;
    private long closingMinor = 0;

    BnplStormWorld(ApplicationContext beans, LendingStormCredit credit, SimulatedProvider provider, Door door) {
        this.beans = beans;
        this.authorization = beans.getBean(Authorization.class);
        this.credit = credit;
        this.provider = provider;
        this.door = door;
    }

    /** A signed-in operator session holding {@code role} - and only it. */
    String sessionWith(RoleName role) throws Exception {
        String login = "bs." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(door.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Grace"
                + " Hopper\",\"password\":\"" + PASSWORD + "\"}", null, key()).status()).isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        assign(identity, role);
        return door.post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD
                + "\"}", null, key()).field("sessionToken");
    }

    /** {@code role} assigned to {@code identity} by the system - the fixture's grant. */
    void assign(UUID identity, RoleName role) throws Exception {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "the BNPL storm's actor");
            app.commit();
        }
    }

    /** A fresh session of a customer's login, after {@code role} is assigned to it - the self-dealing actor. */
    String customerWithRole(CreditFixtures.Customer customer, RoleName role) throws Exception {
        assign(UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?",
                customer.login())), role);
        return door.post("/v1/authentications", "{\"loginIdentifier\":\"" + customer.login() + "\",\"password\":\""
                + PASSWORD + "\"}", null, key()).field("sessionToken");
    }

    /**
     * A customer: verified, consenting, on a {@code MULTI_FACTOR} session, given facts the credit simulators approve; their
     * wallet opened through accounts' own door and a card attached through payment methods' (the provider tokenising).
     */
    Customer customer() throws Exception {
        CreditFixtures.Customer customer = new CreditFixtures(door.port).consentingCustomer();
        credit.register(customer.party(), LendingStormCredit.Profile.GOOD);
        Answer opened = door.post("/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}",
                customer.token(), key());
        assertThat(opened.status()).as("the wallet opened: %s", opened.code()).isEqualTo(201);
        String card = "ctok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Answer attached = door.post("/v1/me/payment-methods", "{\"clientToken\":\"" + card + "\"}", customer.token(),
                null);
        assertThat(attached.status()).as("the card attached: %s", attached.code()).isEqualTo(201);
        return new Customer(customer, attached.field("id"), walletOf(customer.party()));
    }

    /** The party's wallet ledger account, through accounts' own read (lending's `LoanWallets`, as the BNPL suites read it). */
    String walletOf(UUID party) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return beans.getBean(BnplTransactions.class).inTransaction(uow -> beans.getBean(
                    com.finapp.lending.LoanWallets.class).walletFor(uow, party, com.finapp.sharedkernel.money.CurrencyCode
                    .of(EUR)).orElseThrow()).value().toString();
        }
    }

    /** A password-only session of a verified customer - the step-up refusals' actor. */
    CreditFixtures.Customer passwordOnly() throws Exception {
        CreditFixtures.Customer customer = new CreditFixtures(door.port).passwordOnlyCustomer(true);
        credit.register(customer.party(), LendingStormCredit.Profile.GOOD);
        return customer;
    }

    /** A storm customer: their credit fixture, their card's payment method and their wallet's ledger account. */
    record Customer(CreditFixtures.Customer fixture, String card, String wallet) {

        String token() {
            return fixture.token();
        }

        UUID party() {
            return fixture.party();
        }
    }

    // ================================================================== the provider's ordinary answers

    /**
     * The provider's ordinary stubs: tokenisation minting a token per client token; the card PSP approving with a minted
     * reference each authorisation, capture, refund and void; merchant payouts paid.
     */
    void stubTheProvider() {
        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"tokenised\","
                + "\"token\":\"tok_{{jsonPath request.body '$.clientToken'}}\",\"brand\":\"Visa\",\"last4\":\"4242\","
                + "\"expiryMonth\":12,\"expiryYear\":2030}");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "psp_a");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.REFUNDS_PATH, "psp_r");
        provider.succeedsWithMintedReference(SimulatedCardPspAdapter.VOIDS_PATH, "psp_v");
        payoutsPaid();
    }

    /**
     * The payout provider mints its own reference per payout - letters only (`P8-TSK-018`'s shape): the provider's report
     * screens its references for a digit run of card length, dashes collapsed, which our {@code pyo-<uuid>} hex holds now
     * and then - echoed inside the provider's reference, the report would be refused (`PRIMARY_ACCOUNT_NUMBER`).
     */
    void payoutsPaid() {
        provider.succeedsWith(PAYOUT_PATH, 200, "{\"status\":\"paid\",\"reference\":\"po_{{randomValue length=12"
                + " type='ALPHABETIC'}}\"}");
    }

    /** The provider's reference an inquiry answer carries for {@code ourReference}: letters only, never its hex digits. */
    static String providerReferenceOf(String ourReference) {
        StringBuilder letters = new StringBuilder("po_q_");
        for (char c : ourReference.toCharArray()) {
            if (c >= '0' && c <= '9') {
                letters.append((char) ('g' + (c - '0')));
            } else if (Character.isLetter(c)) {
                letters.append(c);
            }
        }
        return letters.substring(0, Math.min(letters.length(), 40));
    }

    // ================================================================== money into a wallet: the card pay-in

    /**
     * A card pay-in of {@code minor} into the customer's wallet: payments' own door - created on {@code on} under a key,
     * confirmed on {@code confirmOn} - the PSP authorising and capturing. The intent's id; its status in {@code status[0]}.
     */
    static String payIn(Door on, Door confirmOn, Customer customer, long minor, String payKey, Tally tally,
            String[] status) throws Exception {
        String body = "{\"paymentMethodId\":\"" + customer.card() + "\",\"amount\":\"" + decimal(minor)
                + "\",\"currency\":\"EUR\"}";
        Answer created = on.post("/v1/payments", body, customer.token(), payKey);
        judged(tally, "card pay-in", created);
        assertThat(created.status()).as("a card pay-in created: %s", created.code()).isEqualTo(201);
        String intent = created.field("id");
        Answer confirmed = confirmOn.post("/v1/payments/" + intent + "/confirmation", null, customer.token(), null);
        judged(tally, "card pay-in confirmation", confirmed);
        if (status != null) {
            status[0] = confirmed.optionalField("status").orElse("?");
        }
        return intent;
    }

    // ================================================================== the terms

    /** A BNPL product's terms over the wire, {@code changes} overriding (raw JSON values). */
    static String bnplTerms(String product, Map<String, String> changes) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("product", "\"" + product + "\"");
        fields.put("currency", "\"EUR\"");
        fields.put("lateFeeMinor", "500");
        fields.put("lateFeeDay", "5");
        fields.put("lateFeeCapMinor", "1500");
        fields.put("graceDays", "3");
        fields.put("delinquencyBounds", "[1,30,60,90]");
        fields.put("defaultThresholdDays", "90");
        fields.put("allocationSequence", "\"OLDEST_DUE_FIRST\"");
        fields.put("allocationOrder", "[\"FEES\",\"INTEREST\",\"PRINCIPAL\"]");
        fields.put("overpaymentTreatment", "\"HOLD_AS_CREDIT\"");
        fields.put("autoCollection", "true");
        fields.put("partialCollection", "true");
        fields.put("collectionRetryDays", "1");
        fields.put("servicingZone", "\"UTC\"");
        fields.put("offerValidityMinutes", "30");
        fields.put("disputeResponseDays", "14");
        fields.put("disputeFourEyesThresholdMinor", "25000");
        fields.put("payoffHorizonDays", "30");
        fields.put("allocationEngine", "\"ALLOCATION_ENGINE_V1\"");
        fields.put("refundAllocationEngine", "\"REFUND_ALLOCATION_ENGINE_V1\"");
        if ("BNPL_PAY_IN_4".equals(product)) {
            fields.put("nominalAnnualRate", "\"0\"");
            fields.put("frequency", "\"BIWEEKLY\"");
            fields.put("instalmentRounding", "\"DOWN\"");
            fields.put("instalmentCount", "4");
            fields.put("intervalDays", "14");
            fields.put("scheduleEngine", "\"BNPL_SCHEDULE_ENGINE_V1\"");
            fields.put("agreementTemplateId", "\"BNPL_PAY_IN_4_AGREEMENT\"");
        } else {
            fields.put("nominalAnnualRate", "\"0.1999\"");
            fields.put("frequency", "\"MONTHLY\"");
            fields.put("instalmentRounding", "\"UP\"");
            fields.put("interestRounding", "\"HALF_EVEN\"");
            fields.put("dayCount", "\"ACT_365F\"");
            fields.put("allowedTerms", "[3,6,12]");
            fields.put("minFirstPeriodDays", "15");
            fields.put("scheduleEngine", "\"SCHEDULE_ENGINE_V1\"");
            fields.put("accrualEngine", "\"ACCRUAL_ENGINE_V1\"");
            fields.put("agreementTemplateId", "\"BNPL_PAY_IN_N_AGREEMENT\"");
        }
        fields.put("agreementTemplateVersion", "1");
        fields.put("reason", "\"the storm's terms\"");
        fields.putAll(changes);
        StringBuilder json = new StringBuilder("{");
        fields.forEach((name, value) -> json.append(json.length() == 1 ? "" : ",").append('"').append(name).append("\":")
                .append(value));
        return json.append('}').toString();
    }

    /** A lending product's plan terms over the wire (the lending storm's), its servicing zone {@code zone}. */
    static String lendingTerms(String product, String zone, String rate, String reason) {
        StringBuilder json = new StringBuilder("{\"product\":\"").append(product).append("\",\"currency\":\"EUR\"")
                .append(",\"nominalAnnualRate\":\"").append(rate).append("\",\"rateType\":\"FIXED\"")
                .append(",\"originationFeeMinor\":0,\"originationFeeTreatment\":\"DEDUCTED\"")
                .append(",\"lateFeeMinor\":1500,\"lateFeeDay\":5,\"lateFeeCapMinor\":6000,\"graceDays\":3")
                .append(",\"delinquencyBounds\":[1,30,60,90],\"defaultThresholdDays\":90")
                .append(",\"allocationSequence\":\"OLDEST_DUE_FIRST\",\"allocationOrder\":[\"FEES\",\"INTEREST\",\"PRINCIPAL\"]")
                .append(",\"autoCollection\":false,\"partialCollection\":true")
                .append(",\"instalmentRounding\":\"UP\",\"interestRounding\":\"HALF_EVEN\",\"dayCount\":\"ACT_365F\"")
                .append(",\"servicingZone\":\"").append(zone).append('"')
                .append(",\"allowedDays\":[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28]")
                .append(",\"offerValidityDays\":14,\"payoffHorizonDays\":30")
                .append(",\"accrualEngineVersion\":1,\"allocationEngineVersion\":1");
        if ("CREDIT_LINE".equals(product)) {
            json.append(",\"overpaymentTreatment\":\"PAY_DOWN_PRINCIPAL\",\"minimumPaymentFloorMinor\":2500")
                    .append(",\"minimumPaymentRatioBps\":300,\"paymentDueDays\":25,\"statementEngineVersion\":1")
                    .append(",\"agreementTemplateId\":\"CREDIT_LINE_AGREEMENT\"");
        } else {
            json.append(",\"overpaymentTreatment\":\"HOLD_AS_CREDIT\",\"minFirstPeriodDays\":15")
                    .append(",\"disbursementDeadlineHours\":24,\"scheduleEngineVersion\":1")
                    .append(",\"agreementTemplateId\":\"PERSONAL_LOAN_AGREEMENT\"");
        }
        return json.append(",\"agreementTemplateVersion\":1,\"reason\":\"").append(reason).append("\"}").toString();
    }

    /**
     * Terms activated under four eyes across instances: proposed on {@code proposing}, the proposer's own approval refused
     * on {@code approving} (a 2xx the breach), approved there by the other officer. The version's id.
     */
    String activateTerms(String path, Door proposing, Door approving, String proposer, String approver, String json,
            String selfRefusal, Tally tally) throws Exception {
        Answer proposed = proposing.post(path, json, proposer, key());
        assertThat(proposed.status()).as("terms proposed: %s %s", proposed.code(), proposed.body()).isEqualTo(201);
        String version = proposed.field("id");
        Answer self = approving.post(path + "/" + version + "/approval", "{\"reason\":\"my own\"}", proposer, key());
        refused(tally, "terms self-approval", self, "403 " + selfRefusal);
        Answer approved = approving.post(path + "/" + version + "/approval", "{\"reason\":\"reviewed\"}", approver, key());
        assertThat(approved.status()).as("terms approved: %s", approved.code()).isEqualTo(200);
        assertThat(approved.field("status")).isEqualTo("ACTIVE");
        tally.count("terms activated");
        return version;
    }

    // ================================================================== capital through the bank's statement

    /**
     * A tranche of capital through the real path - `BnplRecognisedCapital`'s, under four eyes across instances: proposed on
     * {@code proposing}, its proposer's approval refused, approved on {@code approving} (an expectation, no capital); then,
     * unless {@code arrives} is false, the bank's statement carrying the transfer pulled, parsed and accepted - bank
     * recognition posting the capital. The contribution's id.
     */
    synchronized String tranche(Door proposing, Door approving, String proposer, String approver, long minor,
            boolean arrives, Tally tally) throws Exception {
        String reference = "LCAP-" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
        Answer proposed = proposing.post(CONTRIBUTIONS, "{\"currency\":\"EUR\",\"amountMinor\":" + minor
                + ",\"reference\":\"" + reference + "\",\"reason\":\"a storm tranche\"}", proposer, key());
        assertThat(proposed.status()).as("capital proposed: %s", proposed.code()).isEqualTo(201);
        String contribution = proposed.field("id");
        Answer self = approving.post(CONTRIBUTIONS + "/" + contribution + "/approval", "{\"reason\":\"mine\"}", proposer,
                key());
        refused(tally, "capital self-approval", self, "403");
        Answer approved = approving.post(CONTRIBUTIONS + "/" + contribution + "/approval",
                "{\"reason\":\"board minute\"}", approver, key());
        assertThat(approved.status()).as("capital approved: %s", approved.code()).isEqualTo(200);
        tally.count(arrives ? "capital tranche" : "capital tranche that never arrives");
        if (arrives) {
            acceptedStatement(minor, reference);
        }
        return contribution;
    }

    /** The next EUR statement with one capital line, pulled, parsed and accepted - dated on the DATABASE's clock. */
    private void acceptedStatement(long minor, String reference) throws Exception {
        String today = scalar("SELECT (now() AT TIME ZONE 'UTC')::date::text");
        String text = ":20:BSTORM-STMT-" + nextSequence + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                + "\n:25:SIMBANK-EUR-01\n:28C:" + nextSequence + "\n:60F:C," + today + ",EUR," + bankDecimal(closingMinor)
                + "\n:61:" + today + ",C," + bankDecimal(minor) + "," + reference + "\n:62F:C," + today + ",EUR,"
                + bankDecimal(closingMinor + minor) + "\n";
        UUID file;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            @SuppressWarnings("unchecked")
            FileReception<Connection> reception = beans.getBean(FileReception.class);
            FileReception.Result result = beans.getBean("settlementTransactionRunner", TransactionRunner.class)
                    .inTransaction(uow -> reception.receive(uow,
                            new FileReception.Delivery(BANK_SOURCE, DeliveryChannel.PULL,
                                    text.getBytes(StandardCharsets.UTF_8), Optional.empty(), Actor.SYSTEM,
                                    SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                                    CorrelationContext.current().orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            file = ((FileReception.Result.New) result).fileId();
        }
        beans.getBean(FileParsing.class).sweep();
        beans.getBean(BatchAcceptance.class).sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", file))
                .isEqualTo("ACCEPTED:-");
        nextSequence++;
        closingMinor += minor;
    }

    // ================================================================== payouts against the provider's report

    static final String PAYOUT_SOURCE = "simulated-payout.settlement";

    /**
     * Phase 8's evidence for the storm's payouts (the entry's reconciliation implication): the payout provider's daily
     * report (`SIM_PAYOUT_CSV` v1, `P8-TSK-018`'s fixture) naming every COMPLETED payout by the provider's reference and
     * ours, each at the pinned 0.25 fee, received, parsed and accepted through settlement's own sweeps, then matched by
     * reconciliation's. Each payout is {provider reference, our reference, amount minor}. The accepted batch's id.
     */
    UUID payoutReport(List<String[]> payouts) throws Exception {
        String today = scalar("SELECT (now() AT TIME ZONE 'UTC')::date::text");
        com.finapp.app.settlement.SimulatedPayoutReports report = new com.finapp.app.settlement.SimulatedPayoutReports(
                "BSTORM-PAYDAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(), EUR,
                today, "PAY-REM-" + (10_000_000 + RANDOMNESS.nextInt(89_999_999)));
        for (String[] payout : payouts) {
            long minor = Long.parseLong(payout[2]);
            RENDERED_AMOUNTS.add(minor);
            report.with(com.finapp.app.settlement.SimulatedPayoutReports.Entry.settled(
                    BigDecimal.valueOf(minor, 2).toPlainString(), "0.25", payout[0], payout[1]));
        }
        UUID file;
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            @SuppressWarnings("unchecked")
            FileReception<Connection> reception = beans.getBean(FileReception.class);
            FileReception.Result result = beans.getBean("settlementTransactionRunner", TransactionRunner.class)
                    .inTransaction(uow -> reception.receive(uow,
                            new FileReception.Delivery(PAYOUT_SOURCE, DeliveryChannel.PULL, report.render(),
                                    Optional.empty(), Actor.SYSTEM, SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                                    CorrelationContext.current().orElseThrow())));
            assertThat(result).isInstanceOf(FileReception.Result.New.class);
            file = ((FileReception.Result.New) result).fileId();
        }
        beans.getBean(FileParsing.class).sweep();
        beans.getBean(BatchAcceptance.class).sweep();
        assertThat(scalar("SELECT status || ':' || coalesce(rejection_code, '-') FROM settlement.file WHERE id = ?", file))
                .as("the payout provider's report accepted").isEqualTo("ACCEPTED:-");
        com.finapp.reconciliation.Matching matching = beans.getBean(com.finapp.reconciliation.Matching.class);
        for (int sweep = 0; sweep < 4; sweep++) {
            matching.sweep();
        }
        return UUID.fromString(scalar("SELECT id::text FROM settlement.batch WHERE file_id = ?", file));
    }

    private static String bankDecimal(long minor) {
        RENDERED_AMOUNTS.add(minor);
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    // ================================================================== merchants

    /** A storm merchant: its id, its plain key, its refund-scoped key, its payable account and its destination's reference. */
    record Merchant(String id, String apiKey, String refundKey, String payable, String destination) {}

    /**
     * A trading merchant through checkout's and merchant's own doors (`BnplApplicationClient`'s), BNPL-enabled by two
     * persons and priced at {@code rate} plus {@code fixedMinor}; a key with the {@code BNPL_REFUND} scope; an effective
     * payout destination (the `-016` suite's fixture - cooling-off long past); its payable account.
     */
    Merchant merchant(String rate, long fixedMinor) throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(door.port);
        BnplApplicationClient.Merchant merchant = client.tradingMerchant(authorization);
        BnplApplicationClient.bnplEnabled(merchant, beans.getBean(BnplEnablements.class), beans.getBean(BnplPricing.class),
                beans.getBean(FeeSchedules.class), rate, fixedMinor);
        // The pricing is stamped by A's clock (5 s ahead) and judged by the acceptance on the database's: priced
        // "immediately" on A, it is in force for a BNPL purchase only once the database's clock reaches the stamps
        // (recorded for P12-DOC-001 - a 422 inside the skew, nothing written).
        awaitDatabase(Duration.ofSeconds(30), "the merchant's BNPL pricing in force on the database's clock",
                "SELECT bool_and(v.effective_from <= clock_timestamp() AND s.assigned_at <= clock_timestamp()) FROM"
                        + " merchant.merchant_bnpl_fee_schedule s JOIN merchant.fee_schedule_version v ON"
                        + " v.fee_schedule_id = s.fee_schedule_id WHERE s.merchant_id = ?::uuid",
                UUID.fromString(merchant.id()));
        String administrator = client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> issued = client.post("/v1/operator/merchants/" + merchant.id()
                + "/api-keys?scope=BNPL_REFUND", null, administrator, key());
        assertThat(issued.statusCode()).as("the refund-scoped key issued").isEqualTo(201);
        String refundKey = BnplApplicationClient.field(issued.body(), "keyId") + "."
                + BnplApplicationClient.field(issued.body(), "secret");
        String destination = "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection owner = DatabaseRoles.migrator()) {
            BnplApplicationClient.execute(owner,
                    "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference, display_suffix,"
                            + " status, proposed_by, proposed_at, proposal_reason, approved_by, approved_at,"
                            + " cooling_off_until, effective_at) VALUES (?, ?::uuid, ?, '0016', 'EFFECTIVE', 'fixture-a',"
                            + " now() - interval '4 days', 'fixture', 'fixture-b', now() - interval '4 days',"
                            + " now() - interval '1 day', now() - interval '1 hour')",
                    uuidV7(), merchant.id(), destination);
        }
        String payable = scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref = ?::uuid AND purpose ="
                + " 'MERCHANT_PAYABLE' AND currency = 'EUR'", UUID.fromString(merchant.id()));
        return new Merchant(merchant.id(), merchant.apiKey(), refundKey, payable, destination);
    }

    /** A checkout session of {@code minor} opened by the merchant's key: its id and the token its customer holds. */
    static String[] openSession(Door on, Merchant merchant, long minor, Tally tally) throws Exception {
        RENDERED_AMOUNTS.add(minor);
        Answer created = on.post(SESSIONS, "{\"amountMinor\":" + minor + ",\"currency\":\"EUR\",\"lineSummary\":\"A"
                + " bicycle\"}", merchant.apiKey(), key());
        judged(tally, "open session", created);
        assertThat(created.status()).as("a session opened: %s", created.code()).isEqualTo(201);
        return new String[] {created.field("checkoutId"), created.field("sessionToken")};
    }

    // ================================================================== BNPL origination

    static String payIn4(String sessionToken) {
        return "{\"sessionToken\":\"" + sessionToken + "\",\"product\":\"BNPL_PAY_IN_4\",\"plan\":\"PAY_IN_4\"}";
    }

    static String payInN(String sessionToken, int months) {
        return "{\"sessionToken\":\"" + sessionToken + "\",\"product\":\"BNPL_PAY_IN_N\",\"plan\":\"PAY_IN_" + months
                + "_MONTHS\"}";
    }

    /** The application's offer, once the sweeps have made it - empty when credit declined or it closed undecided. */
    static Optional<String> awaitOffer(String application, Duration patience) throws Exception {
        long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            String[] row = row("SELECT a.status, (SELECT o.id::text FROM bnpl.bnpl_offer o WHERE o.application_id = a.id)"
                    + " FROM bnpl.bnpl_application a WHERE a.id = ?::uuid", UUID.fromString(application));
            if (row != null && row[1] != null) {
                return Optional.of(row[1]);
            }
            if (row != null && Set.of("DECLINED", "CLOSED_UNDECIDED", "WITHDRAWN").contains(row[0])) {
                return Optional.empty();
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }

    /** The offer's terms hash as its owner reads it. */
    static String termsHash(Door on, String token, String offer) throws Exception {
        return on.get(OFFERS + "/" + offer, token).field("termsSha256");
    }

    static String acceptance(String hash) {
        return "{\"termsSha256\":\"" + hash + "\"}";
    }

    // ================================================================== lending, on the shared parties

    static String loanApplication(long minor, int term, int day) {
        return "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\",\"termMonths\":"
                + term + ",\"repaymentDay\":" + day + ",\"repaymentMethod\":\"WALLET\",\"payoutPreference\":\"WALLET\","
                + "\"declaredMonthlyIncome\":\"6000.00\",\"declaredMonthlyExpenditure\":\"1000.00\"}";
    }

    static String lineApplication(long minor, int day) {
        return "{\"product\":\"CREDIT_LINE\",\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\",\"repaymentDay\":"
                + day + ",\"repaymentMethod\":\"WALLET\"}";
    }

    static Optional<String> awaitLoanOffer(String application, Duration patience) throws Exception {
        long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            String[] row = row("SELECT a.status, (SELECT o.id::text FROM lending.loan_offer o WHERE o.application_id ="
                    + " a.id) FROM lending.loan_application a WHERE a.id = ?::uuid", UUID.fromString(application));
            if (row != null && row[1] != null) {
                return Optional.of(row[1]);
            }
            if (row != null && Set.of("DECLINED", "CLOSED_UNDECIDED", "WITHDRAWN").contains(row[0])) {
                return Optional.empty();
            }
            Thread.sleep(100);
        }
        return Optional.empty();
    }

    /**
     * The fixed offset whose local midnight falls at {@code midnight} (a whole minute): local = UTC + offset, so the
     * offset is minus the instant's time of day, folded into (-12 h, +12 h] (the lending storm's).
     */
    static ZoneOffset zoneWhoseMidnightIs(Instant midnight) {
        long secondsOfDay = Math.floorMod(midnight.getEpochSecond(), 86_400L);
        long offset = -secondsOfDay;
        if (offset <= -12 * 3600) {
            offset += 86_400;
        }
        ZoneOffset zone = ZoneOffset.ofTotalSeconds((int) offset);
        assertThat(midnight.atOffset(zone).toLocalTime().toSecondOfDay()).as("local midnight").isZero();
        return zone;
    }

    // ================================================================== born in the past

    /**
     * "Born in the past" (the BNPL suites' owner-rank date shift - `BnplCollectionLedgerDatabaseTest#toAge`, made the
     * storm's): an obligation no servicing step has written anything for since its purchase is moved {@code days} back - the
     * purchase's business date, the obligation's, the schedule's start, every instalment's due date, the billings' and
     * repayments' dates and the obligation's journal entries together, so the record reads as if bought that many days ago -
     * and its permit set to {@code permit}. Under the obligation's own row lock (B4), as the bootstrap owner with the
     * triggers suspended, in one transaction, on an obligation the storm reserved from every mover. Whole days before the
     * database's own business date - never a day of the month (A6-06).
     */
    static boolean bornInThePast(String obligation, long days, String permit) throws Exception {
        String o = "'" + obligation + "'";
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("SELECT 1 FROM bnpl.bnpl_obligation WHERE id = " + o + " FOR UPDATE");
                String serviced = scalarOn(owner, "SELECT concat_ws(',', 'accruals ' || (SELECT count(*) FROM"
                        + " bnpl.bnpl_interest_accrual WHERE obligation_id = " + o + "), 'collections ' || (SELECT count(*)"
                        + " FROM bnpl.bnpl_collection_attempt WHERE obligation_id = " + o + "), 'conditions ' || (SELECT"
                        + " count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id = " + o + "), 'refunds ' || (SELECT"
                        + " count(*) FROM bnpl.bnpl_refund WHERE obligation_id = " + o + "), 'repayments ' || (SELECT"
                        + " count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = " + o + " AND kind <> 'AT_PURCHASE'),"
                        + " 'billings ' || (SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = " + o + " AND"
                        + " value_date > (SELECT purchase_date FROM bnpl.bnpl_obligation WHERE id = " + o + ")))");
                // Born with its permit at its own birth, an obligation may meet a sweep before the owner's lock: then
                // it cannot be aged - nothing is changed and the caller buys another.
                if (!"accruals 0,collections 0,conditions 0,refunds 0,repayments 0,billings 0".equals(serviced)) {
                    owner.rollback();
                    return false;
                }
                statement.execute("UPDATE bnpl.purchase SET business_date = business_date - " + days + " WHERE id ="
                        + " (SELECT purchase_id FROM bnpl.bnpl_obligation WHERE id = " + o + ");"
                        + " UPDATE bnpl.bnpl_obligation SET purchase_date = purchase_date - " + days
                        + ", next_servicing_at = " + permit + " WHERE id = " + o + ";"
                        + " UPDATE bnpl.bnpl_schedule SET start_date = start_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_instalment SET due_date = due_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_billing SET value_date = value_date - " + days + ", period_start ="
                        + " period_start - " + days + ", business_date = business_date - " + days
                        + " WHERE obligation_id = " + o + "; UPDATE bnpl.bnpl_repayment SET value_date = value_date - "
                        + days + " WHERE obligation_id = " + o + "; UPDATE ledger.journal_entry SET posting_date ="
                        + " posting_date - " + days + ", value_date = value_date - " + days + " WHERE id IN (SELECT"
                        + " l.entry_id FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                        + " l.ledger_account_id WHERE a.owner_ref = " + o + "::uuid)");
                reprojected(owner, obligation);
            }
            owner.commit();
        }
        return true;
    }

    /**
     * Pay-in-N's schedule re-projected from its shifted start: its instalments are months apart and their interest counts
     * the days between them, so a shift by days leaves rows no projection from the new start makes - the replay's
     * SCHEDULE finding. The pinned engine's own projection is written in their place (pay-in-4's 14-day grid shifts whole).
     */
    private static void reprojected(Connection owner, String obligation) throws SQLException {
        String[] row;
        try (PreparedStatement select = owner.prepareStatement("SELECT o.product, o.plan, o.purchase_date::text,"
                + " a.terms_version_id::text, f.gross_minor, f.scale FROM bnpl.bnpl_obligation o JOIN bnpl.bnpl_agreement a"
                + " ON a.obligation_id = o.id AND a.version = 1 JOIN bnpl.merchant_financing f ON f.purchase_id ="
                + " o.purchase_id WHERE o.id = ?::uuid")) {
            select.setObject(1, UUID.fromString(obligation));
            try (ResultSet found = select.executeQuery()) {
                found.next();
                row = new String[] {found.getString(1), found.getString(2), found.getString(3), found.getString(4),
                        found.getString(5), found.getString(6)};
            }
        }
        if (!"BNPL_PAY_IN_N".equals(row[0])) {
            return;
        }
        com.finapp.bnpl.BnplTerms terms = new com.finapp.bnpl.JdbcBnplTermsStore()
                .version(owner, com.finapp.bnpl.BnplTermsVersionId.of(UUID.fromString(row[3]))).orElseThrow().terms();
        com.finapp.bnpl.BnplIllustration projected = com.finapp.bnpl.BnplIllustration.of(terms,
                com.finapp.bnpl.BnplPlan.valueOf(row[1]), com.finapp.sharedkernel.money.Money.ofPersisted(
                        Long.parseLong(row[4]), com.finapp.sharedkernel.money.CurrencyCode.of("EUR"),
                        Integer.parseInt(row[5])), java.time.LocalDate.parse(row[2]));
        try (PreparedStatement update = owner.prepareStatement("UPDATE bnpl.bnpl_instalment SET due_date = ?,"
                + " principal_minor = ?, interest_minor = ?, amount_minor = ? WHERE obligation_id = ?::uuid AND"
                + " sequence = ?")) {
            for (com.finapp.bnpl.BnplIllustration.Line line : projected.lines()) {
                update.setObject(1, line.dueDate());
                update.setLong(2, line.principal().minorUnits());
                update.setLong(3, line.interest().minorUnits());
                update.setLong(4, line.amount().minorUnits());
                update.setObject(5, UUID.fromString(obligation));
                update.setInt(6, line.sequence());
                assertThat(update.executeUpdate()).as("instalment %d re-projected", line.sequence()).isOne();
            }
        }
    }

    /** The obligation's servicing permit moved (the owner's fixture: a permit is no fact). */
    static void permit(String obligation, String when) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE bnpl.bnpl_obligation SET next_servicing_at = " + when + " WHERE id = '"
                        + obligation + "'::uuid");
            }
            owner.commit();
        }
    }

    /** A UUIDv7 - the shape every bnpl id takes (ADR-0013), so an unknown id is a well-formed one (the `-028` lesson). */
    static UUID uuidV7() {
        long millis = System.currentTimeMillis();
        long msb = (millis << 16) | 0x7000L | (RANDOMNESS.nextInt() & 0x0FFFL);
        long lsb = (RANDOMNESS.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    // ================================================================== the database

    static String scalar(String sql, Object... parameters) throws SQLException {
        String[] found = row(sql, parameters);
        return found == null ? null : found[0];
    }

    static long count(String sql, Object... parameters) throws SQLException {
        String value = scalar(sql, parameters);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String scalarOn(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    static long count(Connection connection, String sql) throws SQLException {
        try (Statement read = connection.createStatement(); ResultSet row = read.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    static String[] row(String sql, Object... parameters) throws SQLException {
        List<String[]> found = rows(sql, parameters);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The schema owner's connections, reused across reads (the lending storm's `P11-DOC-001` fix: a fresh connection per
     * read exhausts the host's ephemeral ports): borrowed and returned around ONE autocommit statement - no snapshot is ever
     * shared; a connection the database closed is dropped and the read retried once on a fresh one.
     */
    private static final java.util.concurrent.ConcurrentLinkedDeque<Connection> IDLE =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    private static final int MOST_IDLE = 16;

    @FunctionalInterface
    private interface OwnerWork<T> {
        T apply(Connection owner) throws SQLException;
    }

    private static <T> T asOwner(OwnerWork<T> work) throws SQLException {
        try {
            return borrowed(work);
        } catch (SQLException failure) {
            if (!connectionLost(failure)) {
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
        } catch (SQLException failure) {
            healthy = !connectionLost(failure);
            throw failure;
        } finally {
            if (healthy && IDLE.size() < MOST_IDLE) {
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

    private static boolean connectionLost(SQLException failure) {
        String state = String.valueOf(failure.getSQLState());
        return state.startsWith("08") || state.startsWith("57P");
    }

    /** Rows read as the schema owner - every table, every column - outside any snapshot. */
    static List<String[]> rows(String sql, Object... parameters) throws SQLException {
        return asOwner(owner -> rows(owner, sql, parameters));
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

    static void execute(String sql, Object... parameters) throws SQLException {
        borrowed(owner -> {
            try (PreparedStatement statement = owner.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                statement.execute();
            }
            return null;
        });
    }

    /** The bootstrap owner's statement with every user trigger suspended - a fixture's move, never a path. */
    static void asBootstrap(String sql) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute(sql);
            }
            owner.commit();
        }
    }

    static Instant databaseNow() throws SQLException {
        return Instant.parse(scalar("SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"));
    }

    static List<String> strings(String sql, Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        for (String[] row : rows(sql, parameters)) {
            values.add(row[0]);
        }
        return values;
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
            Thread.sleep(50);
        }
    }

    static String uuids(Collection<String> ids) {
        return ids.isEmpty() ? "'00000000-0000-0000-0000-000000000000'::uuid"
                : String.join(",", ids.stream().map(id -> "'" + id + "'::uuid").toList());
    }
}
