package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.LendingStormCredit;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.lending.LoanTerms;
import com.finapp.lending.Schedule;
import com.finapp.lending.ScheduleEngineV1;
import com.finapp.lending.ScheduleOutcome;
import com.finapp.lending.SchedulePeriod;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
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
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * The lending storm's world (`P11-TST-001`): the doors as each instance serves them, every response classified and
 * searched as it arrives, the actors by role, the terms cohorts and their real midnights, origination through the real
 * doors and sweeps, the "born in the past" ageing of an account no step has serviced, capital through the bank's
 * statement chain, the borrowers' bank accounts and the scheme's stubs, and the database reads every scene needs.
 *
 * <p>Money enters only by the platform's own paths: capital by the bank statement and recognition (rule 0), wallets by
 * disbursements, draws and payments' pay-ins. Nothing here posts to the ledger.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class LendingStormWorld {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    /** A marker every simulated credit answer carries in a field no adapter reads: it may rest only in evidence. */
    static final String NEEDLE = "Quorvalith Lendneedle";
    static final String PASSWORD = "a-perfectly-fine-pw-7";
    static final String BANK_SOURCE = "simulated-bank.statement";
    static final String APPLICATIONS = "/v1/me/loan-applications";
    static final String OFFERS = "/v1/me/loan-offers";
    static final String LOANS = "/v1/me/loans";
    static final String OPERATOR_LOANS = "/v1/operator/loans";
    static final String TERMS = "/v1/operator/lending/terms";
    static final String CONTRIBUTIONS = "/v1/operator/lending/capital-contributions";
    static final String REPORTS = "/v1/operator/reports/lending";
    static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Pattern CODE = Pattern.compile("\"code\":\"([^\"]*)\"");

    // ================================================================== the tally - every act, classified

    /**
     * Every response the storm received, by act and outcome; the needle's sightings; and the unexplained failures - a
     * response outside its act's closed expected set, a 5xx, a timeout no same-key retry resolved, an exception no armed
     * fault explains (C15). Never an identifier or an amount in what it keeps: the act's name, the status, the code.
     */
    static final class Tally {

        final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        final List<String> unexplained = new CopyOnWriteArrayList<>();
        final List<String> needleSightings = new CopyOnWriteArrayList<>();
        /** Successful audited reads, by audit operation - C13's "exactly the storm's reads". */
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
            return LendingStormWorld.field(body, name);
        }

        Optional<String> optionalField(String name) {
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }
    }

    /**
     * One instance's doors, as a client reaches them: every response searched for the needle as it arrives and judged
     * against the act's closed set. A client timeout is resolved by the same key on the other instance (the replay of the
     * committed answer) - or is an unexplained failure.
     */
    static final class Door {

        final String name;
        final int port;
        private final Tally tally;
        volatile Door other;
        /** The instance is being killed: its doors answer nothing, and a client goes to the other instance. */
        volatile boolean down;

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
                    // Cut by the instance's kill: the client retries on the other instance under the same key.
                    tally.count("a request cut by the restart, retried on the other instance");
                    return other.post(path, body, bearer, key);
                }
                return answer;
            } catch (java.io.IOException refused) {
                if (!down || other == null || refused instanceof HttpTimeoutException) {
                    if (refused instanceof HttpTimeoutException && key != null && other != null) {
                        // The client's own retry after a timeout: the same key on the other instance answers the
                        // committed act.
                        tally.count("timeout resolved by a same-key retry");
                        return other.post(path, body, bearer, key);
                    }
                    tally.unexplained(template(path) + " failed at the transport: " + refused.getClass().getSimpleName());
                    throw refused;
                }
                // Killed under the request: the client retries on the other instance - under the same key, so a
                // committed act replays and an uncommitted one runs once.
                tally.count("a request cut by the restart, retried on the other instance");
                return other.post(path, body, bearer, key);
            }
        }

        private Answer send(HttpRequest request, String path) throws Exception {
            HttpResponse<String> response;
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            }
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
     * {@code "409 lending.LoanNotRepayable"} or a bare {@code "409"} - and counts it. Anything else is an unexplained
     * failure (C15), and answered false.
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
     * Judges an act that must be REFUSED - a self-approval, self-dealing: as {@link #judged}, except that a 2xx is the
     * breach itself (four eyes, INV-LND-11) and an unexplained failure (C15), never a counted answer.
     */
    static boolean refused(Tally tally, String act, Answer answer, String... expected) {
        if (answer.status() >= 200 && answer.status() < 300) {
            tally.count(act + " UNEXPLAINED " + answer.status());
            tally.unexplained(act + " answered " + answer.status() + " - a refusal was due");
            return false;
        }
        return judged(tally, act, answer, expected);
    }

    /** The route template of {@code path} - its identifiers replaced, so a tally names no loan and no party. */
    static String template(String path) {
        return path.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}")
                .replaceAll("\\?.*", "");
    }

    static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    static long minor(String decimal) {
        return new BigDecimal(decimal).movePointRight(2).longValueExact();
    }

    // ================================================================== the actors

    private final ApplicationContext beans;
    private final Authorization authorization;
    private final LendingStormCredit credit;
    private final SimulatedProvider scheme;
    private final Door door;
    /** Each operator session's identity - the actor its acts are recorded under. */
    final Map<String, String> identities = new ConcurrentHashMap<>();

    /** The EUR statement chain's place: the next sequence and the last closing (minor units). */
    private long nextSequence = 1;
    private long closingMinor = 0;

    LendingStormWorld(ApplicationContext beans, LendingStormCredit credit, SimulatedProvider scheme, Door door) {
        this.beans = beans;
        this.authorization = beans.getBean(Authorization.class);
        this.credit = credit;
        this.scheme = scheme;
        this.door = door;
    }

    /** A signed-in operator session holding {@code role} - and only it. */
    String sessionWith(RoleName role) throws Exception {
        String login = "ls." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(door.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Grace"
                + " Hopper\",\"password\":\"" + PASSWORD + "\"}", null, key()).status()).isEqualTo(201);
        UUID identity = UUID.fromString(scalar("SELECT id::text FROM identity.identity WHERE login_identifier = ?", login));
        assign(identity, role);
        String token = door.post("/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\""
                + PASSWORD + "\"}", null, key()).field("sessionToken");
        identities.put(token, identity.toString());
        return token;
    }

    /** {@code role} assigned to {@code identity} by the system - the fixture's grant. */
    void assign(UUID identity, RoleName role) throws Exception {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "the lending storm's actor");
            app.commit();
        }
    }

    /** A verified, consenting customer on a MULTI_FACTOR session, given facts the simulators make {@code profile}. */
    CreditFixtures.Customer borrower(LendingStormCredit.Profile profile) throws Exception {
        CreditFixtures.Customer customer = new CreditFixtures(door.port).consentingCustomer();
        credit.register(customer.party(), profile);
        return customer;
    }

    /** A password-only session of a verified customer - the step-up refusals' actor. */
    CreditFixtures.Customer passwordOnly() throws Exception {
        CreditFixtures.Customer customer = new CreditFixtures(door.port).passwordOnlyCustomer(true);
        credit.register(customer.party(), LendingStormCredit.Profile.GOOD);
        return customer;
    }

    static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p11tst1-" + UUID.randomUUID()))
                .causing(CausationId.of("p11tst1-storm"));
    }

    // ================================================================== the terms cohorts and their midnights

    /**
     * The fixed offset whose local midnight falls at {@code midnight} (a whole minute): local = UTC + offset, so the
     * offset is minus the instant's time of day, folded into (-12 h, +12 h].
     */
    static ZoneOffset zoneWhoseMidnightIs(Instant midnight) {
        long secondsOfDay = Math.floorMod(midnight.getEpochSecond(), 86_400L);
        long offset = -secondsOfDay;
        if (offset <= -12 * 3600) {
            offset += 86_400;
        }
        ZoneOffset zone = ZoneOffset.ofTotalSeconds((int) offset);
        assertThat(midnight.atOffset(zone).toLocalTime().toSecondOfDay()).as("local midnight at %s", midnight).isZero();
        return zone;
    }

    /** {@code product}'s plan terms over the wire, its servicing zone {@code zone}, collecting automatically or not. */
    static String terms(String product, String zone, boolean autoCollection, String rate, String reason) {
        StringBuilder json = new StringBuilder("{\"product\":\"").append(product).append("\",\"currency\":\"EUR\"")
                .append(",\"nominalAnnualRate\":\"").append(rate).append("\",\"rateType\":\"FIXED\"")
                .append(",\"originationFeeMinor\":0,\"originationFeeTreatment\":\"DEDUCTED\"")
                .append(",\"lateFeeMinor\":1500,\"lateFeeDay\":5,\"lateFeeCapMinor\":6000,\"graceDays\":3")
                .append(",\"delinquencyBounds\":[1,30,60,90],\"defaultThresholdDays\":90")
                .append(",\"allocationSequence\":\"OLDEST_DUE_FIRST\",\"allocationOrder\":[\"FEES\",\"INTEREST\",\"PRINCIPAL\"]")
                .append(",\"autoCollection\":").append(autoCollection).append(",\"partialCollection\":true")
                .append(",\"instalmentRounding\":\"UP\",\"interestRounding\":\"HALF_EVEN\",\"dayCount\":\"ACT_365F\"")
                .append(",\"servicingZone\":\"").append(zone).append('"')
                .append(",\"allowedDays\":").append(allDays())
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

    /** Every day 1 to 28 - so whatever day a cohort's boundary falls on, an account can be due on it. */
    private static String allDays() {
        StringBuilder days = new StringBuilder("[");
        for (int day = 1; day <= 28; day++) {
            days.append(day == 1 ? "" : ",").append(day);
        }
        return days.append(']').toString();
    }

    /** {@code product}'s terms activated under four eyes: proposed on {@code proposing}, approved on {@code approving}. */
    String activateTerms(Door proposing, Door approving, String proposer, String approver, String json, Tally tally)
            throws Exception {
        Answer proposed = proposing.post(TERMS, json, proposer, key());
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        String version = proposed.field("id");
        Answer self = approving.post(TERMS + "/" + version + "/approval", "{\"reason\":\"my own\"}", proposer, key());
        assertThat(self.status()).as("self-approval: %s", self.body()).isEqualTo(403);
        assertThat(self.code()).isEqualTo("lending.SelfApprovalRefused");
        tally.count("terms self-approval 403");
        Answer approved = approving.post(TERMS + "/" + version + "/approval", "{\"reason\":\"reviewed\"}", approver,
                key());
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.field("status")).isEqualTo("ACTIVE");
        tally.count("terms activated");
        return version;
    }

    // ================================================================== origination

    /** A personal loan application body. */
    static String loanApplication(long minor, int term, int day, boolean external) {
        return "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\",\"termMonths\":"
                + term + ",\"repaymentDay\":" + day + ",\"repaymentMethod\":\"WALLET\",\"payoutPreference\":\""
                + (external ? "EXTERNAL_ACCOUNT" : "WALLET") + "\",\"declaredMonthlyIncome\":\"6000.00\","
                + "\"declaredMonthlyExpenditure\":\"1000.00\"}";
    }

    /** A credit line application body. */
    static String lineApplication(long minor, int day) {
        return "{\"product\":\"CREDIT_LINE\",\"amount\":\"" + decimal(minor) + "\",\"currency\":\"EUR\",\"repaymentDay\":"
                + day + ",\"repaymentMethod\":\"WALLET\"}";
    }

    /** The application's offer, once the sweeps have made it - empty when credit declined or it closed undecided. */
    static Optional<String> awaitOffer(String application, Duration patience) throws Exception {
        long deadline = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < deadline) {
            String[] row = row("SELECT a.status, (SELECT o.id::text FROM lending.loan_offer o WHERE o.application_id = a.id)"
                    + " FROM lending.loan_application a WHERE a.id = ?::uuid", application);
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

    static String acceptance(String hash, String payoutMethod) {
        return "{\"termsSha256\":\"" + hash + "\"" + (payoutMethod == null ? "" : ",\"payoutMethodId\":\"" + payoutMethod
                + "\"") + "}";
    }

    /** Waits until {@code predicate} (a boolean query) is true on the database. */
    static void awaitDatabase(Duration patience, String what, String predicate, Object... parameters) throws Exception {
        long deadline = System.nanoTime() + patience.toNanos();
        while (!"t".equals(scalar(predicate, parameters)) && !"true".equals(scalar(predicate, parameters))) {
            assertThat(System.nanoTime()).as("the database never said: %s", what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    // ================================================================== the borrower's bank account and the scheme

    /**
     * The scheme's ordinary stubs: the grant exchange answers a destination DERIVED from the grant (so a payout's wire
     * body names its borrower's account, and a fault can be armed on exactly it); transfers accepted with a minted
     * reference; pay-in initiations initiated.
     */
    void stubTheScheme() {
        scheme.succeedsWith(SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200, "{\"status\":\"exchanged\","
                + "\"destination\":\"dest-{{jsonPath request.body '$.grant'}}\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        schemeAccepts();
        scheme.succeedsWith(SimulatedInstantSchemeAdapter.INITIATIONS_PATH, 200, "{\"status\":\"initiated\","
                + "\"handle\":\"https://payer-psp.example/authorize/{{request.headers.Idempotency-Key}}\"}");
    }

    void schemeAccepts() {
        scheme.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, "{\"status\":\"accepted\","
                + "\"reference\":\"sch-loan-{{request.headers.Idempotency-Key}}\",\"cycle\":\"C1\"}");
    }

    /** The borrower's own bank account through the real grant exchange: its payment method and the grant it names. */
    String[] bankAccount(CreditFixtures.Customer borrower) throws Exception {
        for (int attempt = 1; ; attempt++) {
            String grant = "blg-" + UUID.randomUUID().toString().substring(0, 13);
            Answer registered = door.post("/v1/me/payment-methods/bank-accounts", "{\"grant\":\"" + grant
                    + "\",\"acknowledgeNoMatch\":false}", borrower.token(), key());
            if (registered.is(503, "paymentmethods.GrantExchangeUnavailable") && attempt < 5) {
                // The rail provider slower than the client's timeout: the borrower tries again, with a new grant.
                continue;
            }
            assertThat(registered.status()).as(registered.body()).isEqualTo(201);
            return new String[] {registered.field("id"), grant};
        }
    }

    // ================================================================== capital through the bank's statement

    /**
     * A tranche of capital through the real path: proposed on {@code proposing}, refused its proposer's approval, approved
     * on {@code approving} (an expectation, no capital), then - unless {@code arrives} is false - the bank's statement
     * carrying the transfer pulled, parsed and accepted, bank recognition posting rule 0. The contribution's id.
     */
    synchronized String tranche(Door proposing, Door approving, String proposer, String approver, long minor,
            boolean arrives, Tally tally) throws Exception {
        String reference = "LCAP-" + (10_000_000 + RANDOMNESS.nextInt(89_999_999));
        Answer proposed = proposing.post(CONTRIBUTIONS, "{\"currency\":\"EUR\",\"amountMinor\":" + minor
                + ",\"reference\":\"" + reference + "\",\"reason\":\"a storm tranche\"}", proposer, key());
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        String contribution = proposed.field("id");
        Answer self = approving.post(CONTRIBUTIONS + "/" + contribution + "/approval", "{\"reason\":\"mine\"}", proposer,
                key());
        assertThat(self.status()).as(self.body()).isEqualTo(403);
        tally.count("capital self-approval 403");
        Answer approved = approving.post(CONTRIBUTIONS + "/" + contribution + "/approval",
                "{\"reason\":\"board minute\"}", approver, key());
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        tally.count(arrives ? "capital tranche" : "capital tranche that never arrives");
        if (arrives) {
            acceptedStatement(minor, reference);
        }
        return contribution;
    }

    /** The next EUR statement with one capital line, pulled, parsed and accepted - dated on the DATABASE's clock. */
    private void acceptedStatement(long minor, String reference) throws Exception {
        String today = scalar("SELECT (now() AT TIME ZONE 'UTC')::date::text");
        String text = ":20:LSTORM-STMT-" + nextSequence + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
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

    private static String bankDecimal(long minor) {
        return (minor / 100) + "." + String.format("%02d", minor % 100);
    }

    // ================================================================== born in the past

    /**
     * "Born in the past" (the fixture's clock; never a path the platform has - `LoanAccountDatabaseTest#toFirstDueDate`'s
     * technique, made replayable): an instalment loan disbursed to its wallet that NO servicing step has written anything
     * for is moved back so its first instalment falls due on {@code firstDue} - its accrual start, the disbursement's
     * entry and value date, and schedule version 1 RE-PROJECTED from the new start by the pinned schedule engine (so the
     * replay re-derives it), the instalments rewritten in place; its servicing permit set to {@code permit}. Under the
     * loan's own row lock (L3), as the bootstrap owner with the triggers suspended - in one transaction, on an account the
     * storm reserved from every mover.
     */
    static void bornInThePast(String loanId, LoanTerms terms, LocalDate firstDue, Instant permit) throws Exception {
        String[] agreement = row("SELECT g.amount_minor::text, g.term_months::text, g.repayment_day::text,"
                + " s.start_date::text FROM lending.loan_agreement g JOIN lending.accrual_start s ON s.loan_id = g.loan_id"
                + " WHERE g.loan_id = ?::uuid AND g.version = 1", loanId);
        Money principal = Money.ofMinorUnits(Long.parseLong(agreement[0]), EUR);
        int term = Integer.parseInt(agreement[1]);
        int day = Integer.parseInt(agreement[2]);
        LocalDate start = LocalDate.parse(agreement[3]);
        Schedule schedule = null;
        LocalDate newStart = null;
        for (int back = 1; back <= 200 && schedule == null; back++) {
            LocalDate candidate = firstDue.minusDays(back);
            if (candidate.isAfter(start)) {
                continue; // an accrual start is never after the day the loan was disbursed - never in the future
            }
            ScheduleOutcome outcome = ScheduleEngineV1.project(terms, principal, term, day, candidate);
            if (outcome instanceof ScheduleOutcome.Projected projected
                    && projected.schedule().periods().get(0).dueDate().equals(firstDue)) {
                schedule = projected.schedule();
                newStart = candidate;
            }
        }
        assertThat(schedule).as("a start whose first instalment falls due on %s", firstDue).isNotNull();
        long days = ChronoUnit.DAYS.between(newStart, start);
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("SELECT 1 FROM lending.loan WHERE id = '" + loanId + "' FOR UPDATE");
                assertThat(count(owner, "SELECT (SELECT count(*) FROM lending.interest_accrual WHERE loan_id = '" + loanId
                        + "') + (SELECT count(*) FROM lending.instalment_billing WHERE loan_id = '" + loanId + "')"
                        + " + (SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = '" + loanId + "')"
                        + " + (SELECT count(*) FROM lending.repayment WHERE loan_id = '" + loanId + "')"
                        + " + (SELECT count(*) FROM lending.loan_payout WHERE loan_id = '" + loanId + "')"))
                        .as("born in the past only before anything was serviced").isZero();
                statement.execute("UPDATE ledger.journal_entry SET posting_date = posting_date - " + days
                        + ", value_date = value_date - " + days + " WHERE id = (SELECT journal_entry_id FROM"
                        + " lending.loan_disbursement WHERE loan_id = '" + loanId + "');"
                        + " UPDATE lending.loan_disbursement SET value_date = value_date - " + days + " WHERE loan_id = '"
                        + loanId + "'; UPDATE lending.accrual_start SET start_date = DATE '" + newStart + "' WHERE loan_id"
                        + " = '" + loanId + "'; UPDATE lending.loan SET next_servicing_at = '" + permit + "'::timestamptz"
                        + " WHERE id = '" + loanId + "'");
                long totalInterest = schedule.periods().stream().mapToLong(period -> period.interest().minorUnits()).sum();
                SchedulePeriod last = schedule.periods().get(schedule.periods().size() - 1);
                try (PreparedStatement update = owner.prepareStatement("UPDATE lending.repayment_schedule SET start_date"
                        + " = ?, instalment_minor = ?, final_instalment_minor = ?, total_interest_minor = ?, intended_day"
                        + " = ? WHERE loan_id = ?::uuid AND schedule_version = 1")) {
                    update.setObject(1, newStart);
                    update.setLong(2, schedule.instalment().minorUnits());
                    update.setLong(3, last.amount().minorUnits());
                    update.setLong(4, totalInterest);
                    update.setInt(5, schedule.intendedDay());
                    update.setString(6, loanId);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                assertThat(count(owner, "SELECT count(*) FROM lending.loan_instalment WHERE loan_id = '" + loanId + "'"))
                        .as("the re-projection keeps the instalment count").isEqualTo(schedule.periods().size());
                try (PreparedStatement update = owner.prepareStatement("UPDATE lending.loan_instalment SET period_start"
                        + " = ?, due_date = ?, days = ?, opening_principal_minor = ?, interest_minor = ?, principal_minor"
                        + " = ?, amount_minor = ?, closing_principal_minor = ? WHERE loan_id = ?::uuid AND sequence = ?")) {
                    for (SchedulePeriod period : schedule.periods()) {
                        update.setObject(1, period.periodStart());
                        update.setObject(2, period.dueDate());
                        update.setInt(3, (int) period.days());
                        update.setLong(4, period.openingBalance().minorUnits());
                        update.setLong(5, period.interest().minorUnits());
                        update.setLong(6, period.principal().minorUnits());
                        update.setLong(7, period.amount().minorUnits());
                        update.setLong(8, period.closingBalance().minorUnits());
                        update.setString(9, loanId);
                        update.setInt(10, period.sequence());
                        assertThat(update.executeUpdate()).isEqualTo(1);
                    }
                }
            }
            owner.commit();
        }
    }

    /**
     * A credit line "born in the past" (the fixture's clock - lending's `CreditLineStatementDatabaseTest.openOn` /
     * `dateDraw` shape): a line no servicing step has written anything for, opened on {@code openedOn} in its pinned zone
     * and every draw - its row and its entry - valued that day; its permit set to {@code permit}. Under the line's own
     * row lock, as the bootstrap owner with the triggers suspended.
     */
    static void lineBornInThePast(String line, LocalDate openedOn, ZoneOffset zone, Instant permit) throws Exception {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("SELECT 1 FROM lending.loan WHERE id = '" + line + "' FOR UPDATE");
                assertThat(count(owner, "SELECT (SELECT count(*) FROM lending.interest_accrual WHERE loan_id = '" + line
                        + "') + (SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = '" + line + "')"
                        + " + (SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = '" + line + "')"
                        + " + (SELECT count(*) FROM lending.repayment WHERE loan_id = '" + line + "')"))
                        .as("a line born in the past only before anything was serviced").isZero();
                statement.execute("UPDATE lending.loan SET opened_at = '" + openedOn.atTime(12, 0).atOffset(zone)
                        .toInstant() + "'::timestamptz, next_servicing_at = '" + permit + "'::timestamptz WHERE id = '"
                        + line + "'; UPDATE ledger.journal_entry SET posting_date = DATE '" + openedOn + "', value_date ="
                        + " DATE '" + openedOn + "' WHERE id IN (SELECT journal_entry_id FROM lending.credit_line_draw"
                        + " WHERE loan_id = '" + line + "'); UPDATE lending.credit_line_draw SET value_date = DATE '"
                        + openedOn + "' WHERE loan_id = '" + line + "'");
            }
            owner.commit();
        }
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

    /** Rows read as the schema owner - every table, every column - outside any snapshot. */
    static List<String[]> rows(String sql, Object... parameters) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            return rows(owner, sql, parameters);
        }
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
        try (Connection owner = DatabaseRoles.migrator(); PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.execute();
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

    static String uuids(Collection<String> ids) {
        return ids.isEmpty() ? "'00000000-0000-0000-0000-000000000000'::uuid"
                : String.join(",", ids.stream().map(id -> "'" + id + "'::uuid").toList());
    }
}
