import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The HTTP half of the kind rehearsal (P15-TSK-012): the smoke flows, the workload that runs during a rollout, the operator's replay
 * and the edge's assertions, all through the public and operator hosts of the real edge. A single source file run by the JDK image the
 * application is built with, on the cluster's Docker network, so that the client is a real outside address and the certificate it
 * sees is the one the local CA issued.
 *
 * <p>Nothing here decides what is correct about money. It sends requests, counts what came back and writes the keys it used; the census
 * (census.sql) reads the database and compares. A request answered with anything but the status the flow expects is counted as failed.
 *
 * <p>What it cannot do by HTTP, the rehearsal does in SQL as the test-only server's superuser: a customer's ACTIVE status (KYC is not a
 * smoke flow), the operator's roles (the first administrator has to come from somewhere) and a merchant's owning party and effective
 * payout destination (a four-eyes proposal with a cooling-off period). This program writes those statements to a file and exits; the
 * shell applies them and calls the next step, because this program has no route to the database and must not have one.
 *
 * <p>Per-run state, sessions included, lives in the run's own directory and goes with it. It is never under the repository.
 */
public final class Rehearsal {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final String PASSWORD = "a-rehearsal-password-" + UUID.randomUUID();

    private final Path work;
    private final Properties state = new Properties();
    private final HttpClient http;
    private final String publicBase;
    private final String operatorBase;
    private final AtomicLong unresolved = new AtomicLong();

    private Rehearsal(Path work) throws Exception {
        this.work = work;
        this.publicBase = "https://public.finapp.test:" + System.getenv().getOrDefault("PUBLIC_PORT", "30443");
        this.operatorBase = "https://operator.finapp.test:" + System.getenv().getOrDefault("OPERATOR_PORT", "30444");
        Path saved = work.resolve("state.properties");
        if (Files.exists(saved)) {
            try (var in = Files.newInputStream(saved)) {
                state.load(in);
            }
        }
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(trusting(work.resolve("edge-ca.crt")))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static void main(String[] args) throws Exception {
        if ("scram-verifier".equals(args[1])) {
            // Needs no run directory and no edge: a computation over standard input alone (P15-TSK-020).
            System.out.println(scramVerifier(new String(System.in.readAllBytes(), StandardCharsets.US_ASCII).strip()));
            return;
        }
        Rehearsal rehearsal = new Rehearsal(Path.of(args[0]));
        switch (args[1]) {
            case "prepare" -> rehearsal.prepare();
            case "onboard" -> rehearsal.onboard();
            case "smoke" -> rehearsal.smoke(args[2]);
            case "workload" -> rehearsal.workload(args[2]);
            case "replay" -> rehearsal.replay(args[2], args[3], args[4], args[5]);
            case "edge" -> rehearsal.edge();
            case "surface" -> rehearsal.surface();
            case "forged" -> rehearsal.forged(args[2], Integer.parseInt(args[3]));
            case "report-token" -> System.out.println("token " + rehearsal.fresh("operator"));
            case "map-gl" -> rehearsal.mapGl();
            case "gl-check" -> rehearsal.glCheck();
            // P15-TSK-020: the rotations.
            case "register-person" -> rehearsal.registerPerson(args[2]);
            case "enrol-person" -> rehearsal.enrolPerson(args[2]);
            case "elevate" -> rehearsal.elevate(args[2], Integer.parseInt(args[3]));
            case "callbacks" -> rehearsal.callbacks(args[2]);
            case "callbacks-burst" -> rehearsal.callbacksBurst(args[2], Integer.parseInt(args[3]));
            case "served-serial" -> rehearsal.servedSerial();
            // The telemetry pipeline's steps (P15-TSK-023): infra/scripts/kind-rehearsal/telemetry.sh calls these.
            case "trace-payment" -> rehearsal.tracePayment(args[2]);
            case "settle-file" -> rehearsal.settleFile(args[2], args[3]);
            case "latency" -> rehearsal.latency(args[2], Integer.parseInt(args[3]));
            // The platform's procedures through the edge (P15-TSK-032, stream S5): each prints "platform <command> ..." lines only.
            case "platform-prepare" -> rehearsal.platformPrepare();
            case "platform-staff" -> rehearsal.platformStaff();
            case "activate-recon-terms" -> rehearsal.activateReconTerms();
            case "activate-pricing" -> rehearsal.activatePricing();
            case "activate-fx-source" -> rehearsal.activateFxSource();
            case "activate-corridor" -> rehearsal.activateCorridor();
            case "fx-conversion" -> rehearsal.fxConversion(args[2]);
            case "fx-cover" -> rehearsal.fxCover(args[2]);
            case "activate-credit" -> rehearsal.activateCredit();
            case "activate-lending" -> rehearsal.activateLending();
            case "activate-bnpl" -> rehearsal.activateBnpl();
            case "activate-risk" -> rehearsal.activateRisk();
            case "access-review" -> rehearsal.accessReview(args[2]);
            case "access-review-enrol" -> rehearsal.accessReviewEnrol();
            case "first-role" -> rehearsal.firstRole();
            case "compromise" -> rehearsal.compromise(args[2], args[3]);
            case "public-sign-in" -> rehearsal.publicSignIn(args[2]);
            case "key-conflict" -> rehearsal.keyConflict(args[2]);
            case "callback-unmappable" -> rehearsal.callbackUnmappable(args[2]);
            case "settlement-refused" -> rehearsal.settlementRefused(args[2]);
            case "merchant-key-revoke" -> rehearsal.merchantKeyRevoke();
            case "dead-letters" -> rehearsal.deadLetters(args[2]);
            case "release-parked" -> rehearsal.releaseParked(args[2]);
            case "discard" -> rehearsal.discard(args[2], args[3]);
            case "retry-abandoned" -> rehearsal.retryAbandoned(args[2]);
            case "rebuild-projection" -> rehearsal.rebuildProjection(args[2]);
            case "trial-balance-report" -> rehearsal.trialBalanceReport();
            case "report-run" -> rehearsal.reportRun(args[2]);
            case "sign-in-flood" -> rehearsal.signInFlood(args[2], Integer.parseInt(args[3]));
            case "surface-unmarked" -> rehearsal.surfaceUnmarked(args[2]);
            case "payout-held" -> rehearsal.payoutHeld(args[2]);
            default -> throw new IllegalArgumentException("unknown step " + args[1]);
        }
    }

    // ---------------------------------------------------------------------------------------------- transport

    private static SSLContext trusting(Path pem) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        int index = 0;
        for (var certificate : certificates.generateCertificates(new ByteArrayInputStream(Files.readAllBytes(pem)))) {
            store.setCertificateEntry("ca-" + index++, certificate);
        }
        if (index == 0) {
            throw new IllegalStateException("no certificate in " + pem);
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private record Answer(int status, String body, HttpResponse<String> raw) {}

    private Answer call(String method, String url, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        headers.forEach(request::header);
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body(), response);
    }

    private Answer post(String base, String path, String body, String bearer, String key) throws IOException, InterruptedException {
        Map<String, String> headers = new java.util.HashMap<>();
        if (bearer != null) {
            headers.put("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            headers.put("Idempotency-Key", key);
        }
        return call("POST", base + path, body, headers);
    }

    private Answer put(String base, String path, String body, String bearer) throws IOException, InterruptedException {
        return call("PUT", base + path, body, Map.of("Authorization", "Bearer " + bearer));
    }

    private Answer get(String base, String path, String bearer) throws IOException, InterruptedException {
        return call("GET", base + path, null, bearer == null ? Map.of() : Map.of("Authorization", "Bearer " + bearer));
    }

    private static String field(String body, String name) {
        Matcher matcher = FIELD.matcher(body);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        return null;
    }

    private static Answer expect(String what, Answer answer, int... wanted) {
        for (int status : wanted) {
            if (answer.status() == status) {
                return answer;
            }
        }
        throw new IllegalStateException(what + " answered " + answer.status() + ": " + answer.body());
    }

    private void save() throws IOException {
        try (var out = Files.newOutputStream(work.resolve("state.properties"))) {
            state.store(out, "this run's participants; generated, and removed with the run");
        }
    }

    /** A UUIDv7, the shape the platform's identifiers take: a party row with any other identifier is, to the application, no party at all. */
    private static String uuid7() {
        long millis = Instant.now().toEpochMilli();
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        long high = (millis << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
        long low = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low).toString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String someLogin(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ---------------------------------------------------------------------------------------------- the fixtures

    private static final int CUSTOMERS = 4;

    /** Registers with the run's password: the one the fixtures were registered with, kept in the state, so a person registered by a later invocation signs in with it too. */
    private Answer register(String login) throws IOException, InterruptedException {
        return post(publicBase, "/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + password() + "\"}", null, key());
    }

    private String authenticate(String login) throws IOException, InterruptedException {
        Answer answer = expect("authenticate " + login, post(publicBase, "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + password() + "\"}", null, null), 201);
        return field(answer.body(), "sessionToken");
    }

    /** A role-holder's sign-in, on the operator host: the public one refuses staff (P15-TSK-042). */
    private String authenticateStaff(String login) throws IOException, InterruptedException {
        Answer answer = expect("authenticate staff " + login, post(operatorBase, "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + password() + "\"}", null, null), 201);
        return field(answer.body(), "sessionToken");
    }

    private String password() {
        return state.getProperty("password", PASSWORD);
    }

    /** Registers the participants and writes the SQL only the database can answer for. */
    private void prepare() throws Exception {
        state.setProperty("password", PASSWORD);
        List<String> customers = new ArrayList<>();
        for (int i = 0; i < CUSTOMERS; i++) {
            String login = someLogin("c" + i);
            expect("register " + login, register(login), 201);
            state.setProperty("customer." + i + ".login", login);
            customers.add("'" + login + "'");
        }
        String operator = someLogin("op");
        expect("register operator", register(operator), 201);
        state.setProperty("operator.login", operator);
        // The second person of four-eyes: the GL mapping's activation is refused to whoever proposed it.
        String controller = someLogin("ctl");
        expect("register controller", register(controller), 201);
        state.setProperty("controller.login", controller);
        String party = uuid7();
        state.setProperty("merchant.party", party);
        save();
        String seed = "\\set ON_ERROR_STOP on\n"
                + "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)\n"
                + " WHERE party_id IN (SELECT party_id FROM identity.identity WHERE login_identifier IN ("
                + String.join(",", customers) + "));\n"
                + "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)\n"
                + " SELECT uuidv7(), i.id, r, i.id, now()\n"
                + "   FROM identity.identity i, unnest(ARRAY['MERCHANT_ADMINISTRATOR','EVENT_OPERATOR','ACCOUNTANT']) r\n"
                + "  WHERE i.login_identifier = '" + operator + "';\n"
                + "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)\n"
                + " SELECT uuidv7(), i.id, 'FINANCIAL_CONTROLLER', i.id, now()\n"
                + "   FROM identity.identity i WHERE i.login_identifier = '" + controller + "';\n"
                + "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES\n"
                + " ('" + party + "', 'ORGANISATION', 'Rehearsal Holdings', now());\n"
                + "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES\n"
                + " (uuidv7(), '" + party + "', 'ACTIVE', now() - interval '1 hour', now());\n";
        Files.writeString(work.resolve("seed-1.sql"), seed);
        System.out.println("prepared " + CUSTOMERS + " customers and one operator");
    }

    /** The operator's factor, the merchant, its pricing, its key; then the SQL for its destination. */
    private void onboard() throws Exception {
        String operator = enrol("operator");
        enrol("controller");

        String party = state.getProperty("merchant.party");
        Answer merchant = expect("onboard merchant", post(operatorBase, "/v1/operator/merchants",
                "{\"partyId\":\"" + party + "\",\"legalName\":\"Rehearsal GmbH\",\"displayName\":\"Rehearsal\",\"settlementCurrency\":\"EUR\"}",
                operator, key()), 201);
        String merchantId = field(merchant.body(), "merchantId");
        String schedule = field(expect("fee schedule", post(operatorBase, "/v1/operator/fee-schedules",
                "{\"name\":\"Rehearsal " + UUID.randomUUID() + "\",\"currency\":\"EUR\"}", operator, null), 201).body(), "feeScheduleId");
        expect("fee version", post(operatorBase, "/v1/operator/fee-schedules/" + schedule + "/versions",
                "{\"rate\":0.029,\"fixedAmountMinor\":30,\"roundingPolicy\":\"HALF_EVEN\",\"refundFeePolicy\":\"RETAINED\",\"reason\":\"the rehearsal's pricing\"}",
                operator, null), 201);
        expect("assign fee schedule", put(operatorBase, "/v1/operator/merchants/" + merchantId + "/fee-schedule",
                "{\"feeScheduleId\":\"" + schedule + "\",\"reason\":\"the rehearsal's pricing\"}", operator), 200);
        Answer issued = expect("merchant key", post(operatorBase, "/v1/operator/merchants/" + merchantId + "/api-keys", null, operator, key()), 201);
        state.setProperty("merchant.id", merchantId);
        state.setProperty("merchant.key", field(issued.body(), "keyId") + "." + field(issued.body(), "secret"));

        // The customers: a session, a EUR wallet and a card each.
        for (int i = 0; i < CUSTOMERS; i++) {
            String login = state.getProperty("customer." + i + ".login");
            String token = authenticate(login);
            state.setProperty("customer." + i + ".token", token);
            Answer account = expect("open wallet", post(publicBase, "/v1/me/accounts",
                    "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}", token, key()), 201);
            state.setProperty("customer." + i + ".account", field(account.body(), "id"));
            Answer card = expect("attach card", post(publicBase, "/v1/me/payment-methods",
                    "{\"clientToken\":\"ctok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "\"}", token, null), 201);
            state.setProperty("customer." + i + ".card", field(card.body(), "id"));
        }
        save();

        String reference = "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Files.writeString(work.resolve("seed-2.sql"), "\\set ON_ERROR_STOP on\n"
                + "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference, display_suffix, status, proposed_by,\n"
                + "  proposed_at, proposal_reason, approved_by, approved_at, cooling_off_until, effective_at) VALUES\n"
                + " (uuidv7(), '" + merchantId + "', '" + reference + "', '3000', 'EFFECTIVE', 'rehearsal-a', now() - interval '4 days',\n"
                + "  'the rehearsal fixture', 'rehearsal-b', now() - interval '4 days', now() - interval '1 day', now() - interval '1 hour');\n");
        System.out.println("onboarded merchant, " + CUSTOMERS + " customers with wallets and cards");
    }

    /**
     * Enrols a TOTP factor for {@code who} and answers an elevated session; the factor's secret stays in this run's state. Staff, so on the
     * operator host: since P15-TSK-042 a role-holder signs in, enrols and steps up there alone.
     */
    private String enrol(String who) throws Exception {
        String session = authenticateStaff(state.getProperty(who + ".login"));
        String secret = secretFrom(expect("mfa enrolment of " + who, post(operatorBase, "/v1/me/mfa", null, session, null), 201).body());
        long step = currentStep();
        expect("mfa confirmation of " + who, post(operatorBase, "/v1/me/mfa/confirmation", "{\"code\":\"" + totp(secret, step - 1) + "\"}", session, null), 204);
        Answer elevated = expect("elevation of " + who, post(operatorBase, "/v1/authentications/mfa", "{\"code\":\"" + totp(secret, step) + "\"}", session, null), 200);
        state.setProperty(who + ".totp", secret);
        state.setProperty(who + ".step", Long.toString(step));
        return field(elevated.body(), "sessionToken");
    }

    private static long currentStep() {
        return Instant.now().getEpochSecond() / 30;
    }

    private static String secretFrom(String enrolment) {
        Matcher matcher = Pattern.compile("[?&]secret=([A-Z2-7]+)").matcher(enrolment);
        if (!matcher.find()) {
            throw new IllegalStateException("no secret in the enrolment answer");
        }
        return matcher.group(1);
    }

    private static String totp(String base32, long step) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(decodeBase32(base32), "HmacSHA1"));
        byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 0xf;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private static byte[] decodeBase32(String text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toCharArray()) {
            int value = c >= 'A' && c <= 'Z' ? c - 'A' : c - '2' + 26;
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------- the flows

    private String customerToken(int i) {
        return state.getProperty("customer." + i + ".token");
    }

    private String account(int i) {
        return state.getProperty("customer." + i + ".account");
    }

    /** A card top-up: the intent, then the confirmation that authorizes and captures it. */
    private String topUp(int customer, String amount, String key) throws IOException, InterruptedException {
        String token = customerToken(customer);
        Answer created = expect("top-up create", post(publicBase, "/v1/payments",
                "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"paymentMethodId\":\"" + state.getProperty("customer." + customer + ".card") + "\"}",
                token, key), 200, 201);
        String id = field(created.body(), "id");
        Answer confirmed = expect("top-up confirm", post(publicBase, "/v1/payments/" + id + "/confirmation", null, token, null), 200, 409);
        String status = confirmed.status() == 200 ? field(confirmed.body(), "status") : null;
        // A retried request can find the payment already confirmed, or find it still resolving: what counts is where it ends.
        for (int polls = 0; polls < 15 && !"SUCCEEDED".equals(status) && !"FAILED".equals(status); polls++) {
            pause(1000);
            status = field(expect("top-up read", get(publicBase, "/v1/payments/" + id, token), 200).body(), "status");
        }
        if (!"SUCCEEDED".equals(status)) {
            throw new IllegalStateException("the top-up ended " + status);
        }
        return id;
    }

    private Answer transfer(int from, int to, String amount, String key, String reference) throws IOException, InterruptedException {
        return post(publicBase, "/v1/transfers", "{\"sourceAccountId\":\"" + account(from) + "\",\"destinationAccountId\":\"" + account(to)
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"reference\":\"" + reference + "\"}", customerToken(from), key);
    }

    /** A customer pays the merchant by card through a checkout session: authorize and capture, chained. */
    private void checkout(int customer, long amountMinor) throws IOException, InterruptedException {
        checkout(customer, amountMinor, new String[1]);
    }

    /** {@code held[0]} keeps the session token across a retry: a replayed create shows no token, and a repeated confirmation must name the same session. */
    private void checkout(int customer, long amountMinor, String[] held) throws IOException, InterruptedException {
        String merchantKey = state.getProperty("merchant.key");
        if (held[0] == null) {
            Answer session = expect("checkout create", post(publicBase, "/v1/checkout/sessions",
                    "{\"amountMinor\":" + amountMinor + ",\"currency\":\"EUR\",\"lineSummary\":\"The rehearsal's order\"}", merchantKey, key()), 201);
            held[0] = field(session.body(), "sessionToken");
        }
        Answer confirmed = expect("checkout confirm", post(publicBase, "/v1/checkout/sessions/confirmation",
                "{\"sessionToken\":\"" + held[0] + "\",\"paymentMethodId\":\""
                        + state.getProperty("customer." + customer + ".card") + "\"}", customerToken(customer), null), 200);
        String status = field(confirmed.body(), "status");
        if (!"COMPLETED".equals(status)) {
            throw new IllegalStateException("the checkout ended " + status + ": " + confirmed.body());
        }
    }

    private String payout(String amount, String key) throws IOException, InterruptedException {
        Answer created = expect("merchant payout", post(publicBase, "/v1/merchant/payouts",
                "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}", state.getProperty("merchant.key"), key), 200, 201);
        return field(created.body(), "status");
    }

    /** Every flow once, each asserted; any refusal fails the step. */
    private void smoke(String label) throws Exception {
        String login = someLogin("smoke");
        expect("register", register(login), 201);
        System.out.println("smoke " + label + " register ok");
        String freshPassword = field(expect("authenticate", post(publicBase, "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + password() + "\"}", null, null), 201).body(), "sessionToken");
        if (freshPassword == null) {
            throw new IllegalStateException("no session token");
        }
        System.out.println("smoke " + label + " authenticate ok");
        for (int customer = 0; customer < CUSTOMERS; customer++) {
            topUp(customer, "5.00", key());
        }
        System.out.println("smoke " + label + " card top-up (authorize, capture) ok, " + CUSTOMERS + " customers funded");
        String reference = "smoke-" + label + "-" + UUID.randomUUID();
        Answer sent = expect("transfer", transfer(0, 1, "1.00", key(), reference), 201);
        if (!"COMPLETED".equals(field(sent.body(), "status"))) {
            throw new IllegalStateException("the transfer ended " + sent.body());
        }
        System.out.println("smoke " + label + " transfer ok");
        checkout(1, 10_00L);
        System.out.println("smoke " + label + " checkout (authorize, capture to the merchant) ok");
        String status = payout("1.00", key());
        System.out.println("smoke " + label + " merchant payout " + status);
    }

    /**
     * The scripted workload that runs while a rollout runs. Every request counts: a request answered with anything but what its flow
     * expects, or not answered, is a failure and is written with the reason. It stops when {@code stop-<label>} appears in the work dir.
     */
    private void workload(String label) throws Exception {
        Path results = work.resolve("workload-" + label + ".tsv");
        Files.writeString(results, "flow\tkey\toutcome\n");
        AtomicLong failed = new AtomicLong();
        AtomicLong succeeded = new AtomicLong();
        Map<String, AtomicLong> byFlow = new ConcurrentHashMap<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread watcher = Thread.ofVirtual().start(() -> {
            Path file = work.resolve("stop-" + label);
            while (!Files.exists(file)) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            stop.set(true);
        });
        Object lock = new Object();
        java.util.function.BiConsumer<String, String> note = (flow, line) -> {
            synchronized (lock) {
                try {
                    Files.writeString(results, flow + "\t" + line + "\n", StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        List<Thread> threads = new ArrayList<>();
        // One thread of each kind: a rate a laptop can carry, and a replay window under its 1000-event bound.
        threads.add(Thread.ofVirtual().start(() -> transfers(label, stop, note, failed, succeeded, byFlow)));
        threads.add(Thread.ofVirtual().start(() -> topUps(label, stop, note, failed, succeeded, byFlow)));
        threads.add(Thread.ofVirtual().start(() -> commerce(label, stop, note, failed, succeeded, byFlow)));
        for (Thread thread : threads) {
            thread.join();
        }
        watcher.join(2000);
        String line = "workload " + label + " ok=" + succeeded.get() + " failed=" + failed.get() + " unresolved=" + unresolved.get() + " " + byFlow;
        System.out.println(line);
        Files.writeString(work.resolve("workload-" + label + ".summary"), line + "\n");
    }

    private interface Flow {
        void run() throws Exception;
    }

    /** Every failed attempt counts, even one a retry then heals. Under {@code WORKLOAD_RETRY=retry} (the pod kill) a logical request is retried with the same key until it resolves or the bound runs out. */
    private void counted(String flow, String key, java.util.function.BiConsumer<String, String> note, AtomicLong failed, AtomicLong ok,
            Map<String, AtomicLong> byFlow, Flow body) {
        boolean retry = "retry".equals(System.getenv("WORKLOAD_RETRY"));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(240));
        while (true) {
            try {
                body.run();
                ok.incrementAndGet();
                byFlow.computeIfAbsent(flow + ".ok", ignored -> new AtomicLong()).incrementAndGet();
                note.accept(flow, key + "\tok");
                return;
            } catch (Exception thrown) {
                failed.incrementAndGet();
                byFlow.computeIfAbsent(flow + ".failed", ignored -> new AtomicLong()).incrementAndGet();
                note.accept(flow, key + "\tATTEMPT-FAILED " + String.valueOf(thrown.getMessage()).replaceAll("\\s+", " "));
                if (!retry || Instant.now().isAfter(deadline)) {
                    unresolved.incrementAndGet();
                    note.accept(flow, key + "\tUNRESOLVED");
                    return;
                }
                pause(3000);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void transfers(String label, AtomicBoolean stop, java.util.function.BiConsumer<String, String> note, AtomicLong failed,
            AtomicLong ok, Map<String, AtomicLong> byFlow) {
        int i = 0;
        while (!stop.get()) {
            int from = i % CUSTOMERS;
            int to = (i + 1) % CUSTOMERS;
            String key = "wl-" + label + "-" + i + "-" + key();
            String reference = "wl-" + label + "-" + UUID.randomUUID();
            String flow = "transfer";
            counted(flow, key + "\t" + reference, note, failed, ok, byFlow, () -> {
                Answer first = expect("transfer", transfer(from, to, "0.01", key, reference), 200, 201);
                if (!"COMPLETED".equals(field(first.body(), "status"))) {
                    throw new IllegalStateException("the transfer ended " + first.body());
                }
                // Every fourth is sent again with the same key: the retry a client makes after a lost answer. Its answer must name the same transfer.
                if (from == 0) {
                    Answer again = expect("transfer retry", transfer(from, to, "0.01", key, reference), 200, 201);
                    if (!field(first.body(), "id").equals(field(again.body(), "id"))) {
                        throw new IllegalStateException("the retry made another transfer: " + again.body());
                    }
                }
            });
            i++;
            pause(1500);
        }
    }

    private void topUps(String label, AtomicBoolean stop, java.util.function.BiConsumer<String, String> note, AtomicLong failed,
            AtomicLong ok, Map<String, AtomicLong> byFlow) {
        int i = 0;
        while (!stop.get()) {
            int customer = i % CUSTOMERS;
            String key = "wl-" + label + "-t" + i + "-" + key();
            counted("top-up", key, note, failed, ok, byFlow, () -> topUp(customer, "0.05", key));
            i++;
            pause(1500);
        }
    }

    private void commerce(String label, AtomicBoolean stop, java.util.function.BiConsumer<String, String> note, AtomicLong failed,
            AtomicLong ok, Map<String, AtomicLong> byFlow) {
        int i = 0;
        while (!stop.get()) {
            int customer = i % CUSTOMERS;
            counted("register", "fresh", note, failed, ok, byFlow, () -> expect("register", register(someLogin("wl")), 201));
            counted("authenticate", "existing", note, failed, ok, byFlow, () -> authenticate(state.getProperty("customer." + customer + ".login")));
            String[] held = new String[1];
            counted("checkout", "fresh", note, failed, ok, byFlow, () -> checkout(customer, 2_00L, held));
            String payoutKey = "wl-" + label + "-p" + i + "-" + key();
            counted("payout", payoutKey, note, failed, ok, byFlow, () -> payout("0.10", payoutKey));
            i++;
            pause(4000);
        }
    }

    // ---------------------------------------------------------------------------------------------- the operator's replay

    private void replay(String eventType, String from, String to, String reason) throws Exception {
        String token = freshOperator();
        Answer answer = expect("replay", post(operatorBase, "/v1/operator/events/replays",
                "{\"eventType\":\"" + eventType + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"reason\":\"" + reason + "\"}",
                token, key()), 200);
        System.out.println("replay " + eventType + " requeued=" + field(answer.body(), "requeued") + " " + answer.body());
    }

    /** A new elevated session: the rehearsal runs for longer than a session is meant to stay open. */
    private String freshOperator() throws Exception {
        return fresh("operator");
    }

    /** A code is consumed once per step (P1-TSK-018): a new elevation waits for a step the last one did not use. Staff, so on the operator host. */
    private String fresh(String who) throws Exception {
        String session = authenticateStaff(state.getProperty(who + ".login"));
        String secret = state.getProperty(who + ".totp");
        long last = Long.parseLong(state.getProperty(who + ".step", "0"));
        long step = currentStep();
        while (step <= last) {
            pause(1000);
            step = currentStep();
        }
        Answer elevated = expect("elevation", post(operatorBase, "/v1/authentications/mfa",
                "{\"code\":\"" + totp(secret, step) + "\"}", session, null), 200);
        state.setProperty(who + ".step", Long.toString(step));
        save();
        return field(elevated.body(), "sessionToken");
    }

    // ---------------------------------------------------------------------------------------------- GL = ledger

    private static String period() {
        return java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
    }

    /** The first GL mapping of a deployment (runbook 10.12): one person proposes the reference chart, another activates it. */
    private void mapGl() throws Exception {
        String chart = Files.readString(Path.of("/src/gl-mapping.json"));
        String body = "{\"effectiveFromPeriod\":\"" + period() + "\",\"reason\":\"the rehearsal's reference chart\","
                + chart.strip().substring(1);
        Answer proposed = expect("mapping proposal", post(operatorBase, "/v1/operator/accounting/mapping-versions", body, fresh("operator"), key()), 201);
        String id = field(proposed.body(), "id");
        expect("mapping activation", post(operatorBase, "/v1/operator/accounting/mapping-versions/" + id + "/activation",
                "{\"reason\":\"the second person's review\"}", fresh("controller"), key()), 200);
        System.out.println("gl mapping activated: " + proposed.body());
    }

    /** The General Ledger's own judgement of itself against the journal, for this month in EUR: the application's arithmetic, not a copy of it. */
    private void glCheck() throws Exception {
        Answer answer = expect("gl balances", get(operatorBase, "/v1/operator/accounting/gl/balances?period=" + period() + "&currency=EUR", fresh("operator")), 200);
        boolean agrees = answer.body().contains("\"agrees\":true");
        System.out.println((agrees ? "PASS" : "FAIL") + "|GL equals ledger|" + period() + " EUR agrees=" + agrees + " " + answer.body().replaceAll("\\s+", " "));
        if (!agrees) {
            throw new IllegalStateException("the General Ledger does not agree with the journal");
        }
    }

    // ---------------------------------------------------------------------------------------------- the edge

    private void edge() throws Exception {
        Answer customer = get(publicBase, "/v1/me", null);
        System.out.println("edge public-route status=" + customer.status()
                + " hsts=" + customer.raw().headers().firstValue("strict-transport-security").orElse("absent"));
        Answer publicOperator = get(publicBase, "/v1/operator/events/dead-letters", null);
        System.out.println("edge operator-route-on-public status=" + publicOperator.status() + " body=" + publicOperator.body().replaceAll("\\s+", " "));
        Answer operator = get(operatorBase, "/v1/operator/events/dead-letters", null);
        System.out.println("edge operator-route-on-operator status=" + operator.status() + " body=" + operator.body().replaceAll("\\s+", " "));
        Answer actuator = get(publicBase, "/actuator/health", null);
        System.out.println("edge actuator-on-public status=" + actuator.status());
    }

    // ---------------------------------------------------------------------------------------------- the telemetry pipeline (P15-TSK-023)

    /**
     * One card top-up of a planted amount, created and then confirmed. The platform mints every request's correlation (ADR-0034: a caller's
     * own value is only echoed as a client reference), so each request's is read from its answer and printed with the payment's id: the
     * shell follows each request's legs by its own correlation, and the asynchronous ones by the correlation the platform stored with them.
     */
    private void tracePayment(String amount) throws Exception {
        String token = customerToken(0);
        // The client sends its own W3C traceparent on both requests; the platform joins it rather than replacing it.
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] traceBytes = new byte[16];
        random.nextBytes(traceBytes);
        String trace = java.util.HexFormat.of().formatHex(traceBytes);
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Idempotency-Key", key());
        headers.put("traceparent", "00-" + trace + "-" + java.util.HexFormat.of().formatHex(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}) + "-01");
        Answer created = expect("traced top-up create", call("POST", publicBase + "/v1/payments",
                "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"paymentMethodId\":\"" + state.getProperty("customer.0.card") + "\"}",
                headers), 200, 201);
        String id = field(created.body(), "id");
        headers.remove("Idempotency-Key");
        headers.put("traceparent", "00-" + trace + "-" + java.util.HexFormat.of().formatHex(new byte[] {8, 7, 6, 5, 4, 3, 2, 1}) + "-01");
        Answer confirmed = expect("traced top-up confirm", call("POST", publicBase + "/v1/payments/" + id + "/confirmation", null, headers), 200, 409);
        String status = confirmed.status() == 200 ? field(confirmed.body(), "status") : null;
        for (int polls = 0; polls < 30 && !"SUCCEEDED".equals(status); polls++) {
            pause(1000);
            status = field(expect("traced top-up read", get(publicBase, "/v1/payments/" + id, token), 200).body(), "status");
        }
        System.out.println("traced payment=" + id + " status=" + status + " create=" + correlationOf(created) + " confirm=" + correlationOf(confirmed)
                + " trace=" + trace);
    }

    /**
     * A PSP settlement report delivered as a person would: uploaded by the operator, then attested by the controller, the second person the
     * upload door requires. The file's id and each request's minted correlation are printed for the shell to follow.
     */
    private void settleFile(String businessDate, String reportFile) throws Exception {
        String content = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(work.resolve(reportFile)));
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Authorization", "Bearer " + fresh("operator"));
        headers.put("Idempotency-Key", key());
        Answer landed = expect("settlement upload", call("POST", operatorBase + "/v1/operator/settlement/files",
                "{\"sourceCode\":\"simulated-psp.settlement\",\"businessDate\":\"" + businessDate + "\",\"content\":\"" + content + "\"}",
                headers), 202);
        String file = field(landed.body(), "fileId");
        Map<String, String> attest = new java.util.HashMap<>();
        attest.put("Authorization", "Bearer " + fresh("controller"));
        Answer attested = expect("settlement attestation", call("POST", operatorBase + "/v1/operator/settlement/files/" + file + "/attestation", null, attest), 200);
        System.out.println("settlement file=" + file + " upload=" + correlationOf(landed) + " attestation=" + correlationOf(attested));
    }

    /** The correlation the platform minted for a request, from its answer's header; "-" when absent. */
    private static String correlationOf(Answer answer) {
        return answer.raw().headers().firstValue("X-Correlation-Id").orElse("-");
    }

    /**
     * Transfers of a cent between two customers, one after another, for {@code seconds}: each request's first status and milliseconds in
     * latency-&lt;label&gt;.tsv, so the shell can compare the collector up, stopped and hung. A write, so its trace is one the tail policy keeps.
     *
     * <p>A transfer whose answer was lost, or a 5xx, may have committed: it is asked again with the same key and reference until the
     * platform answers it (the stored answer for one that committed), so the census counts each transfer that moved money exactly once.
     * One still unanswered after a minute is written {@code UNRESOLVED}, and the shell fails the step on it.
     */
    private void latency(String label, int seconds) throws Exception {
        Path results = work.resolve("latency-" + label + ".tsv");
        Files.writeString(results, "");
        long until = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        int i = 0;
        int unresolvedHere = 0;
        StringBuilder lines = new StringBuilder();
        // Every transfer that succeeded, in the workload's own record, so the census counts it like any other.
        StringBuilder counted = new StringBuilder();
        while (System.nanoTime() < until) {
            int from = i % 2;
            String key = "tl-" + label + "-" + i + "-" + key();
            // A workload's reference, which the census counts by its prefix (census.sql's one transfer per key).
            String reference = "wl-telemetry-" + label + "-" + UUID.randomUUID();
            long started = System.nanoTime();
            int status = transferStatus(from, key, reference);
            lines.append(status).append('\t').append(Duration.ofNanos(System.nanoTime() - started).toMillis()).append('\n');
            int settled = status;
            Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
            while ((settled == -1 || settled >= 500) && Instant.now().isBefore(deadline)) {
                pause(1000);
                settled = transferStatus(from, key, reference);
            }
            if (settled == 200 || settled == 201) {
                counted.append("transfer\t").append(key).append('\t').append(reference).append("\tok\n");
            } else if (settled == -1 || settled >= 500) {
                unresolvedHere++;
                counted.append("transfer\t").append(key).append('\t').append(reference).append("\tUNRESOLVED\n");
            }
            i++;
        }
        Files.writeString(results, lines.toString());
        Files.writeString(work.resolve("workload-telemetry-" + label + ".tsv"), "flow\tkey\toutcome\n" + counted);
        System.out.println("latency " + label + " requests=" + i + " unresolved=" + unresolvedHere);
    }

    /** One transfer of a cent from customer {@code from} to the other; -1 when no answer came. */
    private int transferStatus(int from, String key, String reference) throws InterruptedException {
        try {
            return transfer(from, 1 - from, "0.01", key, reference).status();
        } catch (IOException failure) {
            return -1;
        }
    }

    /**
     * The operator's own password, on each host (P15-TSK-042): refused on the public one as a wrong password is, and counted nowhere;
     * served on the operator host. Run on release N only, after the rollout: the edge's acceptance runs on N - 1, which can predate the
     * refusal. Prints the status alone, never the body: a body that was served would carry a session token into the artefacts.
     */
    private void surface() throws Exception {
        String staff = "{\"loginIdentifier\":\"" + state.getProperty("operator.login") + "\",\"password\":\"" + password() + "\"}";
        Answer staffOnPublic = post(publicBase, "/v1/authentications", staff, null, null);
        System.out.println("surface operator-sign-in-on-public status=" + staffOnPublic.status());
        Answer staffOnOperator = post(operatorBase, "/v1/authentications", staff, null, null);
        System.out.println("surface operator-sign-in-on-operator status=" + staffOnOperator.status());
    }

    /**
     * Requests at the recovery door, each forging a different address: the door counts per source before it reads the body, so an empty one is enough and changes
     * nothing. The first address after the valve is what the count is keyed on.
     */
    private void forged(String label, int requests) throws Exception {
        for (int i = 1; i <= requests; i++) {
            Answer answer = call("POST", publicBase + "/v1/recoveries", "{}", Map.of("X-Forwarded-For", "198.51.100." + i));
            System.out.println("forged " + label + " status=" + answer.status() + " client=" + java.net.InetAddress.getLocalHost().getHostAddress());
        }
    }

    // ---------------------------------------------------------------------------------------------- the rotations (P15-TSK-020)

    /**
     * A PostgreSQL SCRAM-SHA-256 verifier for the password on standard input, computed here so that the password never reaches the
     * server, a statement or a log: the server stores exactly this, and a rotation writes it with {@code ALTER ROLE ... PASSWORD}.
     */
    private static String scramVerifier(String password) throws Exception {
        if (password.isEmpty()) {
            throw new IllegalArgumentException("no password on standard input");
        }
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        int iterations = 4096;
        byte[] salted = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        byte[] storedKey = java.security.MessageDigest.getInstance("SHA-256").digest(hmac(salted, "Client Key"));
        byte[] serverKey = hmac(salted, "Server Key");
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        return "SCRAM-SHA-256$" + iterations + ":" + b64.encodeToString(salt) + "$" + b64.encodeToString(storedKey) + ":" + b64.encodeToString(serverKey);
    }

    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A new person, registered through the edge with the run's password, after the fixtures. */
    private void registerPerson(String who) throws Exception {
        String login = someLogin(who);
        expect("register " + who, register(login), 201);
        state.setProperty(who + ".login", login);
        save();
        System.out.println("registered " + who + " login=" + login);
    }

    /** A TOTP factor for a registered person: its secret is encrypted under whichever key version the replica that took the enrolment had active. */
    private void enrolPerson(String who) throws Exception {
        enrol(who);
        save();
        System.out.println("enrolled " + who);
    }

    /** Elevations of one person, each a fresh TOTP step: every one decrypts that person's factor on whichever replica takes it. */
    private void elevate(String who, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            fresh(who);
            System.out.println("elevated " + who);
        }
    }

    /**
     * The provider of the payment webhook door, signing as a provider does: HMAC-SHA256 over {@code timestamp + "." + body} under the key in
     * {@code provider-key}, read again before every delivery so that the shell switches the provider's secret by replacing the file. The body
     * carries no event id: an authenticated delivery of that shape is kept as evidence and acknowledged ({@code 204}) and moves nothing; an
     * unauthenticated one is the door's uniform {@code 401}. Every answer is counted per key name; nothing here judges, the shell does.
     */
    private void callbacks(String label) throws Exception {
        Path results = work.resolve("callbacks-" + label + ".tsv");
        Files.writeString(results, "");
        Map<String, AtomicLong> counts = new java.util.TreeMap<>();
        long n = 0;
        while (!Files.exists(work.resolve("stop-callbacks-" + label))) {
            String name = Files.readString(work.resolve("provider-key-name")).strip();
            byte[] key = java.util.Base64.getDecoder().decode(Files.readString(work.resolve("provider-key")).strip());
            int status = deliver(key, "{\"rehearsal\":\"" + label + "\",\"n\":" + (n++) + "}");
            counts.computeIfAbsent(name + "." + status, ignored -> new AtomicLong()).incrementAndGet();
            Files.writeString(results, Instant.now() + "\t" + name + "\t" + status + "\n", StandardOpenOption.APPEND);
            pause(700);
        }
        String line = "callbacks " + label + " " + counts;
        System.out.println(line);
        Files.writeString(work.resolve("callbacks-" + label + ".summary"), line + "\n");
    }

    /** {@code requests} deliveries signed with the key in {@code burst-key}: what each replica the edge reaches answers to that key now. */
    private void callbacksBurst(String name, int requests) throws Exception {
        byte[] key = java.util.Base64.getDecoder().decode(Files.readString(work.resolve("burst-key")).strip());
        Map<Integer, AtomicLong> counts = new java.util.TreeMap<>();
        for (int i = 0; i < requests; i++) {
            counts.computeIfAbsent(deliver(key, "{\"rehearsal\":\"burst-" + name + "\",\"n\":" + i + "}"), ignored -> new AtomicLong()).incrementAndGet();
            pause(300);
        }
        System.out.println("burst " + name + " accepted=" + counts.getOrDefault(204, new AtomicLong()).get() + " refused="
                + counts.getOrDefault(401, new AtomicLong()).get() + " all=" + counts);
    }

    private int deliver(byte[] key, String body) {
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String signature = java.util.HexFormat.of().formatHex(hmac(key, timestamp + "." + body));
            return call("POST", publicBase + "/v1/providers/payments/webhooks", body,
                    Map.of("X-Provider-Timestamp", timestamp, "X-Provider-Signature", signature)).status();
        } catch (Exception failed) {
            return -1;
        }
    }

    /** The certificate the public host serves now, on a connection of its own: its serial and its end, never the key. */
    private void servedSerial() throws Exception {
        URI base = URI.create(publicBase);
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) trusting(work.resolve("edge-ca.crt")).getSocketFactory()
                .createSocket(base.getHost(), base.getPort())) {
            socket.startHandshake();
            java.security.cert.X509Certificate served = (java.security.cert.X509Certificate) socket.getSession().getPeerCertificates()[0];
            System.out.println("served serial=" + served.getSerialNumber().toString(16).toUpperCase(Locale.ROOT) + " notAfter=" + served.getNotAfter().toInstant());
        }
    }

    // ---------------------------------------------------------------------------------------------- the platform's procedures (P15-TSK-032)

    /*
     * Section 11's procedures and the domain activations, through the edge, as the people the runbook names. Every command prints
     * "platform <command> <what> key=value ..." lines and nothing else on stdout: statuses, error codes, identifiers and counts, never a
     * session token, a password, a factor's secret, a key or a response body. A request answered otherwise than the procedure expects
     * still prints its line, so the shell can say what happened; a command exits non-zero only when it cannot go on at all.
     *
     * Who does what: "operator" proposes and "controller" approves (two people, so four eyes holds); "admin" holds ADMINISTRATOR and
     * grants every other role through the grant door; "holder" is a customer given a first role; "stranger" holds MERCHANT_ADMINISTRATOR
     * from SQL, around the door, with no factor. Identity identifiers are answered by no HTTP door (registration answers 201 with no body
     * and /v1/me a party id), so platform-prepare writes ids.sql, which the shell runs into ids.properties in the work dir.
     */

    private static final List<String> PLATFORM_PERSONS = List.of("admin", "holder", "stranger");

    /** Every role a domain activation, the event doors, the rebuild and the settlement doors need, granted to both persons of four eyes. */
    private static final List<String> STAFF_ROLES = List.of("RECONCILIATION_CONTROLLER", "RECONCILIATION_OPERATOR", "FX_CONTROLLER",
            "CREDIT_POLICY_OFFICER", "LENDING_TREASURY_OFFICER", "LENDING_OFFICER", "BNPL_OFFICER", "RISK_OFFICER", "LEDGER_OPERATOR",
            "EVENT_OPERATOR");

    /** The four sources settlement V002 seeds with an active rule set (runbook section 1). */
    private static final List<String> SETTLEMENT_SOURCES = List.of("simulated-psp.settlement", "simulated-scheme.cycle-report",
            "simulated-payout.settlement", "simulated-bank.statement");

    /** Credit V006's and V008's proposals (runbook section 6.1's table). */
    private static final String SCORECARD_SEED = "0190a1b2-5c0e-7000-8000-00000000c001";
    private static final Map<String, String> POLICY_SEEDS = new LinkedHashMap<>(Map.of(
            "PERSONAL_LOAN", "0190a1b2-5c0e-7000-8000-00000000d001", "CREDIT_LINE", "0190a1b2-5c0e-7000-8000-00000000d002"));

    private static final String WHY = " (the kind rehearsal, P15-TSK-032)";

    private void say(String line) {
        System.out.println("platform " + line);
    }

    private static String codeOf(Answer answer) {
        String code = answer.body() == null ? null : field(answer.body(), "code");
        return code == null ? "-" : code;
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static boolean ok(Answer answer) {
        return answer.status() / 100 == 2;
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":" + jsonString(reason) + "}";
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String today() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    private String credentials(String login) {
        return "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + password() + "\"}";
    }

    /** A sign-in that answers its status: the procedures read a refusal as an outcome, never as a failure of the program. */
    private Answer signIn(String base, String login) throws IOException, InterruptedException {
        return post(base, "/v1/authentications", credentials(login), null, null);
    }

    /** The identity id of {@code who}: kept in the state once read from ids.properties, which the shell writes from ids.sql. */
    private String identity(String who) throws IOException {
        String known = state.getProperty(who + ".identity");
        if (known != null) {
            return known;
        }
        Path file = work.resolve("ids.properties");
        if (!Files.exists(file)) {
            return null;
        }
        Properties ids = new Properties();
        try (var in = Files.newInputStream(file)) {
            ids.load(in);
        }
        String found = ids.getProperty(who + ".identity");
        if (found == null || found.isBlank()) {
            return null;
        }
        state.setProperty(who + ".identity", found.strip());
        save();
        return found.strip();
    }

    private String requireIdentity(String command, String who) throws IOException {
        String id = identity(who);
        if (id == null) {
            say(command + " ids missing=" + who);
            throw new IllegalStateException("no identity id for " + who + ": run ids.sql into ids.properties in the work dir first");
        }
        return id;
    }

    private Answer grant(String admin, String identityId, String role, String reason) throws IOException, InterruptedException {
        return post(operatorBase, "/v1/identities/" + segment(identityId) + "/roles",
                "{\"role\":\"" + role + "\",\"reason\":" + jsonString(reason) + "}", admin, null);
    }

    /** Three more persons with the run's password, and the SQL only the database can answer: the first administrator, a pre-rule holding, the ids. */
    private void platformPrepare() throws Exception {
        for (String who : PLATFORM_PERSONS) {
            String login = someLogin(who);
            expect("register " + who, register(login), 201);
            state.setProperty(who + ".login", login);
        }
        save();
        // ADMINISTRATOR the way prepare grants the operator's roles: the grant door needs an administrator to exist. MERCHANT_ADMINISTRATOR
        // around the door, with no factor: a holding from before the factor rule (P15-TSK-039), for the access review to find.
        Files.writeString(work.resolve("seed-platform.sql"), "\\set ON_ERROR_STOP on\n"
                + "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)\n"
                + " SELECT uuidv7(), i.id, 'ADMINISTRATOR', i.id, now()\n"
                + "   FROM identity.identity i WHERE i.login_identifier = '" + state.getProperty("admin.login") + "';\n"
                + "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)\n"
                + " SELECT uuidv7(), i.id, 'MERCHANT_ADMINISTRATOR', i.id, now()\n"
                + "   FROM identity.identity i WHERE i.login_identifier = '" + state.getProperty("stranger.login") + "';\n");
        // One "<who>.identity=<uuid>" line per person under psql -A -t: the shell writes them to ids.properties.
        StringBuilder ids = new StringBuilder("\\set ON_ERROR_STOP on\n");
        boolean first = true;
        for (String who : List.of("operator", "controller", "admin", "holder", "stranger")) {
            String login = state.getProperty(who + ".login");
            if (login == null) {
                continue;
            }
            ids.append(first ? "" : "UNION ALL\n").append("SELECT '").append(who).append(".identity=' || i.id::text FROM identity.identity i")
                    .append(" WHERE i.login_identifier = '").append(login).append("'\n");
            first = false;
        }
        Files.writeString(work.resolve("ids.sql"), ids.append(";\n").toString());
        say("prepare persons=" + PLATFORM_PERSONS.size());
    }

    /** The administrator's factor, then every role the activations need, granted through the door to both persons of four eyes. */
    private void platformStaff() throws Exception {
        // A second run finds the factor enrolled and steps up with it: enrolling again is refused.
        boolean enrolled = state.getProperty("admin.totp") != null;
        String admin = enrolled ? fresh("admin") : enrol("admin");
        save();
        say("staff enrol who=admin status=" + (enrolled ? "already" : "200"));
        for (String who : List.of("operator", "controller")) {
            String id = requireIdentity("staff", who);
            for (String role : STAFF_ROLES) {
                Answer granted = grant(admin, id, role, "the desks the activations need" + WHY);
                say("staff grant role=" + role + " who=" + who + " status=" + granted.status() + (ok(granted) ? "" : " code=" + codeOf(granted)));
            }
        }
    }

    private record FourEyes(String id, Answer proposed, Answer self, Answer approved) {}

    /**
     * One person proposes, the same person's decision is tried (the self-approval refusal), the other person decides. Prints the three
     * lines; {@code selfWord} is the word the shell reads the refusal by, {@code suffix} what names the act when a command does several.
     */
    private FourEyes fourEyes(String command, String selfWord, String suffix, String path, String body, String idField, String decision,
            String decisionBody, boolean keyedDecision, String proposer, String approver) throws IOException, InterruptedException {
        Answer proposed = post(operatorBase, path, body, proposer, key());
        String id = ok(proposed) ? field(proposed.body(), idField) : null;
        say(command + " proposed status=" + proposed.status() + " code=" + codeOf(proposed) + suffix);
        if (id == null) {
            return new FourEyes(null, proposed, null, null);
        }
        String decided = path + "/" + segment(id) + decision;
        Answer self = post(operatorBase, decided, decisionBody, proposer, keyedDecision ? key() : null);
        say(command + " " + selfWord + " status=" + self.status() + " code=" + codeOf(self) + suffix);
        Answer approved = post(operatorBase, decided, decisionBody, approver, keyedDecision ? key() : null);
        say(command + " approved status=" + approved.status() + " state=" + orDash(field(approved.body(), "status"))
                + (ok(approved) ? "" : " code=" + codeOf(approved)) + suffix);
        return new FourEyes(id, proposed, self, approved);
    }

    private FourEyes activation(String command, String suffix, String path, String body, String idField, boolean keyedDecision,
            String proposer, String approver) throws IOException, InterruptedException {
        return fourEyes(command, "self-approval", suffix, path, body, idField, "/approval", reasonBody("reviewed by a second person" + WHY),
                keyedDecision, proposer, approver);
    }

    // ------------------------------------------------------------------ runbook section 1: the four sources' successors, JPY and BHD

    private void activateReconTerms() throws Exception {
        String operator = fresh("operator");
        String controller = fresh("controller");
        for (String source : SETTLEMENT_SOURCES) {
            Answer listed = get(operatorBase, "/v1/operator/reconciliation/rule-sets?source=" + segment(source), operator);
            Map<String, Object> active = versionWithStatus(listed, "ACTIVE");
            if (active == null) {
                say("activate-recon-terms read status=" + listed.status() + " code=" + codeOf(listed) + " active=none source=" + source);
                continue;
            }
            activation("activate-recon-terms", " source=" + source, "/v1/operator/reconciliation/rule-sets",
                    toJson(successor(source, active)), "ruleSetId", false, operator, controller);
            Answer reread = get(operatorBase, "/v1/operator/reconciliation/rule-sets?source=" + segment(source), operator);
            Map<String, Object> now = versionWithStatus(reread, "ACTIVE");
            Map<String, Object> thresholds = now == null ? Map.of() : obj(now.get("severityThresholds"));
            say("activate-recon-terms read status=" + reread.status() + " version=" + (now == null ? "-" : text(now.get("version")))
                    + " jpy=" + (thresholds.containsKey("JPY") ? "yes" : "no") + " bhd=" + (thresholds.containsKey("BHD") ? "yes" : "no")
                    + " source=" + source);
        }
    }

    /** The first version of a rule-set list in {@code status}, newest first as the door lists them; null when none or unreadable. */
    private static Map<String, Object> versionWithStatus(Answer listed, String status) {
        if (listed.status() != 200) {
            return null;
        }
        for (Object version : arr(obj(tryJson(listed.body())).get("versions"))) {
            if (status.equals(text(obj(version).get("status")))) {
                return obj(version);
            }
        }
        return null;
    }

    /**
     * The active version restated whole - every lag, rule, tolerance, fee schedule and threshold, a proposal being a full version and
     * never a delta - plus section 1's JPY and BHD rows, each added only where the version does not already carry it.
     */
    private static Map<String, Object> successor(String source, Map<String, Object> active) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceCode", source);
        body.put("reason", "OPERATIONS_RUNBOOK section 1: the active version restated whole, with the JPY and BHD rows" + WHY);
        body.put("fundingLagDays", active.get("fundingLagDays"));
        body.put("gainMinAgeDays", active.get("gainMinAgeDays"));
        body.put("lagDays", new LinkedHashMap<>(obj(active.get("lagDays"))));
        List<Object> rules = new ArrayList<>();
        for (Object rule : arr(active.get("rules"))) {
            rules.add(picked(obj(rule), "priority", "lineType", "keyKind", "expectationKind", "cardinality", "operationAnchored", "graceHours"));
        }
        body.put("rules", rules);
        List<Object> tolerances = new ArrayList<>();
        for (Object tolerance : arr(active.get("tolerances"))) {
            tolerances.add(picked(obj(tolerance), "comparison", "currency", "absoluteMinor", "days"));
        }
        List<Object> fees = new ArrayList<>();
        for (Object fee : arr(active.get("feeSchedules"))) {
            fees.add(picked(obj(fee), "lineType", "currency", "rate", "fixedMinor", "scale", "roundingPolicy"));
        }
        Map<String, Object> thresholds = new LinkedHashMap<>(obj(active.get("severityThresholds")));
        thresholds.putIfAbsent("JPY", new BigDecimal(150000));
        thresholds.putIfAbsent("BHD", new BigDecimal(400000));
        // The fee schedule of the source's own fee line: fixed per the table, the rate and rounding as EUR's, the scale the currency's minor units.
        String feeLine = switch (source) {
            case "simulated-psp.settlement" -> "PROCESSING_FEE";
            case "simulated-scheme.cycle-report" -> "SCHEME_FEE";
            case "simulated-payout.settlement" -> "PAYOUT_FEE";
            default -> "BANK_FEE";
        };
        long[] fixed = switch (source) {
            case "simulated-psp.settlement", "simulated-payout.settlement" -> new long[] {40, 100};
            case "simulated-scheme.cycle-report" -> new long[] {15, 40};
            default -> new long[] {75, 200};
        };
        Map<String, Object> euro = null;
        for (Object fee : fees) {
            if (feeLine.equals(obj(fee).get("lineType")) && "EUR".equals(obj(fee).get("currency"))) {
                euro = obj(fee);
            }
        }
        String rate = euro == null ? "0.000000" : text(euro.get("rate"));
        String rounding = euro == null ? "HALF_UP" : text(euro.get("roundingPolicy"));
        addFee(fees, feeLine, "JPY", rate, fixed[0], 0, rounding);
        addFee(fees, feeLine, "BHD", rate, fixed[1], 3, rounding);
        if (source.equals("simulated-psp.settlement")) {
            addTolerance(tolerances, "PROCESSING_FEE_PER_LINE", "JPY", 3);
            addTolerance(tolerances, "PROCESSING_FEE_PER_LINE", "BHD", 10);
            addTolerance(tolerances, "PROCESSING_FEE_PER_BATCH", "JPY", 75);
            addTolerance(tolerances, "PROCESSING_FEE_PER_BATCH", "BHD", 200);
        }
        body.put("tolerances", tolerances);
        body.put("feeSchedules", fees);
        body.put("severityThresholds", thresholds);
        return body;
    }

    private static void addFee(List<Object> fees, String lineType, String currency, String rate, long fixedMinor, int scale, String rounding) {
        for (Object fee : fees) {
            if (lineType.equals(obj(fee).get("lineType")) && currency.equals(obj(fee).get("currency"))) {
                return;
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lineType", lineType);
        row.put("currency", currency);
        row.put("rate", rate);
        row.put("fixedMinor", fixedMinor);
        row.put("scale", scale);
        row.put("roundingPolicy", rounding);
        fees.add(row);
    }

    private static void addTolerance(List<Object> tolerances, String comparison, String currency, long absoluteMinor) {
        for (Object tolerance : tolerances) {
            if (comparison.equals(obj(tolerance).get("comparison")) && currency.equals(obj(tolerance).get("currency"))) {
                return;
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("comparison", comparison);
        row.put("currency", currency);
        row.put("absoluteMinor", absoluteMinor);
        tolerances.add(row);
    }

    // ------------------------------------------------------------------ runbook sections 2, 3, 3b and 4: FX and the corridors

    private Answer fxQuote(String token, String amount) throws IOException, InterruptedException {
        return post(publicBase, "/v1/me/fx/quotes", "{\"sourceCurrency\":\"EUR\",\"destinationCurrency\":\"USD\",\"fixedSide\":\"FIXED_SOURCE\","
                + "\"amount\":\"" + amount + "\"}", token, key());
    }

    /** A customer's quote before and after: closed, never priced on a default, until two people activate v1. */
    private void activatePricing() throws Exception {
        String customer = authenticate(state.getProperty("customer.3.login"));
        Answer before = fxQuote(customer, "10.00");
        say("activate-pricing quote-before status=" + before.status() + " code=" + codeOf(before));
        String operator = fresh("operator");
        String controller = fresh("controller");
        FourEyes decided = activation("activate-pricing", "", "/v1/operator/fx/pricing-policies",
                pricingPolicyV1("OPERATIONS_RUNBOOK section 2: pricing policy v1, O7's values" + WHY), "id", false, operator, controller);
        if (decided.id() != null) {
            Answer listed = get(operatorBase, "/v1/operator/fx/pricing-policies", controller);
            say("activate-pricing read status=" + listed.status() + " state=" + orDash(stateOf(listed, decided.id())));
        }
        Answer after = fxQuote(customer, "10.00");
        say("activate-pricing quote-after status=" + after.status() + " code=" + codeOf(after));
    }

    /** The state of version {@code id} in a "versions" list answer. */
    private static String stateOf(Answer listed, String id) {
        for (Object version : arr(obj(tryJson(listed.body())).get("versions"))) {
            if (id.equals(text(obj(version).get("id")))) {
                return text(obj(version).get("status"));
            }
        }
        return null;
    }

    private void activateFxSource() throws Exception {
        String operator = fresh("operator");
        String controller = fresh("controller");
        activation("activate-fx-source", "", "/v1/operator/reconciliation/rule-sets",
                fxRuleSetV1("OPERATIONS_RUNBOOK section 3: fx-sim-a.trade-report's rule set v1" + WHY), "ruleSetId", false, operator, controller);
        Answer reread = get(operatorBase, "/v1/operator/reconciliation/rule-sets?source=fx-sim-a.trade-report", operator);
        Map<String, Object> active = versionWithStatus(reread, "ACTIVE");
        say("activate-fx-source read status=" + reread.status() + " state=" + (active == null ? "-" : "ACTIVE")
                + " version=" + (active == null ? "-" : text(active.get("version"))));
    }

    private Answer corridors(String token) throws IOException, InterruptedException {
        return get(publicBase, "/v1/me/cross-border/corridors", token);
    }

    private static int corridorCount(Answer offered) {
        return offered.status() == 200 ? arr(obj(tryJson(offered.body())).get("corridors")).size() : 0;
    }

    /** What a customer can observe: discovery offers nothing until two people activate v1, then its four corridors. */
    private void activateCorridor() throws Exception {
        String customer = authenticate(state.getProperty("customer.3.login"));
        Answer before = corridors(customer);
        say("activate-corridor offered-before status=" + before.status() + " corridors=" + corridorCount(before));
        String operator = fresh("operator");
        String controller = fresh("controller");
        FourEyes decided = activation("activate-corridor", "", "/v1/operator/cross-border/corridor-policies",
                corridorPolicyV1("OPERATIONS_RUNBOOK section 3b: the corridor policy v1, O7's values" + WHY), "id", false, operator, controller);
        if (decided.id() != null) {
            Answer listed = get(operatorBase, "/v1/operator/cross-border/corridor-policies", controller);
            say("activate-corridor read status=" + listed.status() + " state=" + orDash(stateOf(listed, decided.id())));
        }
        Answer after = corridors(customer);
        say("activate-corridor offered-after status=" + after.status() + " corridors=" + corridorCount(after));
    }

    /**
     * One customer conversion of EUR 1.00 to USD: a quote, then its conversion. The customer's EUR wallet is the one onboard opened; the
     * conversion opens the USD account of the same agreement itself (FxConversionDatabaseTest), so nothing else is opened first.
     */
    private void fxConversion(String label) throws Exception {
        String token = authenticate(state.getProperty("customer.2.login"));
        Answer quoted = fxQuote(token, "1.00");
        String quote = quoted.status() == 201 ? field(quoted.body(), "id") : null;
        if (quote == null) {
            say("fx-conversion " + label + " status=" + quoted.status() + " trade=- code=" + codeOf(quoted) + " step=quote");
            return;
        }
        Answer converted = post(publicBase, "/v1/me/fx/conversions", "{\"quoteId\":\"" + quote + "\"}", token, key());
        String trade = ok(converted) ? field(converted.body(), "id") : null;
        say("fx-conversion " + label + " status=" + converted.status() + " trade=" + orDash(trade) + " code=" + codeOf(converted));
        if (trade != null) {
            state.setProperty("fx.trade." + label, trade);
            save();
        }
    }

    /** The trade's cover as the investigator reads it: its state, and whether its execution booked (a journal entry). */
    private void fxCover(String trade) throws Exception {
        Answer read = get(operatorBase, "/v1/operator/fx/trades/" + segment(trade) + "/provenance", fresh("operator"));
        Map<String, Object> cover = obj(obj(tryJson(read.body())).get("cover"));
        Map<String, Object> execution = obj(cover.get("execution"));
        say("fx-cover state=" + (cover.isEmpty() ? "none" : orDash(text(cover.get("status"))))
                + " booked=" + (execution.get("journalEntryId") != null ? "yes" : "no") + " status=" + read.status() + " code=" + codeOf(read));
    }

    // ------------------------------------------------------------------ runbook section 6.1: credit policy and scorecard

    /**
     * Section 6.1 as written: each v1 is a migration's proposal, so one person ("controller") activates it, the four-eyes CHECK comparing
     * them with the migration. The self-approval refusal needs a person's proposal, so each is then restated whole as version 2 by
     * "operator", refused to "operator", activated by "controller": the policies in force are v1's content under two persons' names.
     */
    private void activateCredit() throws Exception {
        String operator = fresh("operator");
        String controller = fresh("controller");
        String seedReason = reasonBody("OPERATIONS_RUNBOOK section 6.1: the seeded version 1, reviewed" + WHY);
        Answer scorecard = post(operatorBase, "/v1/operator/credit/scorecards/" + SCORECARD_SEED + "/approval", seedReason, controller, key());
        say("activate-credit seed status=" + scorecard.status() + " state=" + orDash(field(scorecard.body(), "status")) + " code="
                + codeOf(scorecard) + " what=RETAIL_SCORECARD");
        for (Map.Entry<String, String> seed : POLICY_SEEDS.entrySet()) {
            Answer policy = post(operatorBase, "/v1/operator/credit/policies/" + seed.getValue() + "/approval", seedReason, controller, key());
            say("activate-credit seed status=" + policy.status() + " state=" + orDash(field(policy.body(), "status")) + " code="
                    + codeOf(policy) + " what=" + seed.getKey());
        }
        activation("activate-credit", " what=RETAIL_SCORECARD", "/v1/operator/credit/scorecards",
                scorecardV1("OPERATIONS_RUNBOOK section 6.1: RETAIL_SCORECARD v1 (credit V006) restated whole" + WHY), "id", true,
                operator, controller);
        for (String product : POLICY_SEEDS.keySet()) {
            Answer inForce = get(operatorBase, "/v1/operator/credit/policies?product=" + product, operator);
            if (inForce.status() != 200) {
                say("activate-credit read status=" + inForce.status() + " code=" + codeOf(inForce) + " what=" + product);
                continue;
            }
            activation("activate-credit", " what=" + product, "/v1/operator/credit/policies",
                    toJson(policyRestated(obj(tryJson(inForce.body())),
                            "OPERATIONS_RUNBOOK section 6.1: the policy in force restated whole" + WHY)), "id", true, operator, controller);
            Answer reread = get(operatorBase, "/v1/operator/credit/policies?product=" + product, operator);
            Map<String, Object> view = obj(tryJson(reread.body()));
            say("activate-credit read status=" + reread.status() + " state=" + orDash(text(view.get("status"))) + " version="
                    + orDash(text(view.get("version"))) + " what=" + product);
        }
    }

    /** The policy door's answer as its proposal body: the request's own fields, a rule's ordinal and the version's identity left out. */
    private static Map<String, Object> policyRestated(Map<String, Object> view, String reason) {
        Map<String, Object> body = picked(view, "product", "currency", "assessmentRateBps", "minimumDisposableMinor", "minimumPaymentRatioBps",
                "maximumExposureMinor");
        List<Object> sources = new ArrayList<>();
        for (Object source : arr(view.get("sources"))) {
            sources.add(picked(obj(source), "kind", "maximumDataAgeSeconds"));
        }
        body.put("sources", sources);
        body.put("unavailableFallback", view.get("unavailableFallback"));
        body.put("autoApprovalCeilingMinor", view.get("autoApprovalCeilingMinor"));
        List<Object> rules = new ArrayList<>();
        for (Object rule : arr(view.get("rules"))) {
            rules.add(picked(obj(rule), "ruleCode", "subjectKind", "subject", "operator", "integerOperand", "moneyOperandMinor", "booleanOperand",
                    "codesOperand", "effect", "capMinor", "reasonCode"));
        }
        body.put("rules", rules);
        body.put("reason", reason);
        if (view.get("planStressFactorBps") != null) {
            body.put("planStressFactorBps", view.get("planStressFactorBps"));
        }
        return body;
    }

    // ------------------------------------------------------------------ runbook sections 7.1, 7.3, 7.4 and 8.1: lending and BNPL

    /**
     * Section 7.1: a capital contribution under four eyes, then the bank's statement carrying it (uploaded by "operator", attested by
     * "controller", the upload door's two persons) - capital is recognised from the statement and never typed. Section 7.3: both loan
     * products' terms under four eyes. Section 7.4 step 5: the recognised capital read back for today.
     */
    private void activateLending() throws Exception {
        String operator = fresh("operator");
        String controller = fresh("controller");
        long minor = 100_000_000L;
        String reference = "LCAP-" + (10_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(89_999_999));
        String day = today();
        long recognisedBefore = recognised(operator, day)[1];
        FourEyes capital = activation("activate-lending", " what=capital", "/v1/operator/lending/capital-contributions",
                "{\"currency\":\"EUR\",\"amountMinor\":" + minor + ",\"reference\":\"" + reference + "\",\"reason\":"
                        + jsonString("OPERATIONS_RUNBOOK section 7.1: the rehearsal's lending capital" + WHY) + "}",
                "id", true, operator, controller);
        say("activate-lending capital status=" + (capital.approved() == null ? capital.proposed().status() : capital.approved().status()));
        boolean delivered = false;
        if (capital.approved() != null && ok(capital.approved())) {
            delivered = capitalStatement(operator, controller, day, reference, minor);
        }
        for (String product : List.of("PERSONAL_LOAN", "CREDIT_LINE")) {
            activation("activate-lending", " what=" + product, "/v1/operator/lending/terms", loanTerms(product,
                    product.equals("CREDIT_LINE") ? "0.1299" : "0.0899", "OPERATIONS_RUNBOOK section 7.3: the product's terms" + WHY),
                    "id", true, operator, controller);
            Answer read = get(operatorBase, "/v1/operator/lending/terms?product=" + product, operator);
            say("activate-lending read status=" + read.status() + " state=" + orDash(field(read.body(), "status")) + " what=" + product);
        }
        long[] report = recognised(operator, day);
        // Parse and acceptance are sweeps: the statement is recognised within a few of their ticks, or it is not.
        for (int polls = 0; delivered && polls < 60 && report[0] == 200 && report[1] < recognisedBefore + minor; polls++) {
            pause(3000);
            report = recognised(operator, day);
        }
        say("activate-lending capital-report status=" + report[0] + " recognised=" + report[1]);
    }

    /** The capital report's EUR recognised capital for {@code day}: {status, recognisedMinor}. */
    private long[] recognised(String token, String day) throws IOException, InterruptedException {
        Answer report = get(operatorBase, "/v1/operator/reports/lending/capital?date=" + day, token);
        long recognised = 0;
        for (Object row : arr(obj(tryJson(report.body())).get("rows"))) {
            if ("EUR".equals(text(obj(row).get("currency")))) {
                recognised = number(obj(row).get("recognisedMinor"));
            }
        }
        return new long[] {report.status(), recognised};
    }

    /**
     * The settlement bank's statement carrying the contribution, in the simulated bank's tagged format (SimStatementTaggedFormat, the
     * lending storm's statement): its own sequence and opening balance kept in the state, so a second statement continues the chain.
     */
    private boolean capitalStatement(String operator, String controller, String day, String reference, long minor) throws Exception {
        long sequence = Long.parseLong(state.getProperty("bank.eur.sequence", "0")) + 1;
        long opening = Long.parseLong(state.getProperty("bank.eur.closing", "0"));
        String statement = ":20:LCAP-STMT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) + "\n"
                + ":25:SIMBANK-EUR-01\n"
                + ":28C:" + sequence + "\n"
                + ":60F:C," + day + ",EUR," + decimal(opening) + "\n"
                + ":61:" + day + ",C," + decimal(minor) + "," + reference + "\n"
                + ":62F:C," + day + ",EUR," + decimal(opening + minor) + "\n";
        Answer landed = post(operatorBase, "/v1/operator/settlement/files", "{\"sourceCode\":\"simulated-bank.statement\",\"businessDate\":\""
                + day + "\",\"content\":\"" + java.util.Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8)) + "\"}",
                operator, key());
        String file = landed.status() == 202 ? field(landed.body(), "fileId") : null;
        Answer attested = file == null ? null : post(operatorBase, "/v1/operator/settlement/files/" + file + "/attestation", null, controller, null);
        say("activate-lending statement status=" + landed.status() + " code=" + codeOf(landed) + " attestation="
                + (attested == null ? "-" : Integer.toString(attested.status())));
        if (file != null) {
            state.setProperty("bank.eur.sequence", Long.toString(sequence));
            state.setProperty("bank.eur.closing", Long.toString(opening + minor));
            save();
        }
        return attested != null && ok(attested);
    }

    private static String decimal(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    /** Section 8.1 step 5: both BNPL products' terms under four eyes, read back. */
    private void activateBnpl() throws Exception {
        String operator = fresh("operator");
        String controller = fresh("controller");
        for (String product : List.of("BNPL_PAY_IN_4", "BNPL_PAY_IN_N")) {
            activation("activate-bnpl", " what=" + product, "/v1/operator/bnpl/terms",
                    bnplTerms(product, "OPERATIONS_RUNBOOK section 8.1 step 5: the product's terms" + WHY), "id", true, operator, controller);
            Answer read = get(operatorBase, "/v1/operator/bnpl/terms?product=" + product, operator);
            say("activate-bnpl read status=" + read.status() + " state=" + orDash(field(read.body(), "status")) + " what=" + product);
        }
    }

    /**
     * Section 9.1 step 9 on one operation, TRANSFER: a version that judges permissively - one weighted rule, every score band and the one
     * fail-safe band ALLOW - so nothing later is held or refused, only recorded under a version instead of NO_POLICY_ACTIVE.
     */
    private void activateRisk() throws Exception {
        String operator = fresh("operator");
        String controller = fresh("controller");
        FourEyes decided = fourEyes("activate-risk", "self-approval", " what=TRANSFER", "/v1/operator/risk/policies",
                permissiveRiskPolicy("TRANSFER", "OPERATIONS_RUNBOOK section 9.1 step 9: a permissive rehearsal version" + WHY), "id",
                "/activation", reasonBody("reviewed by a second person" + WHY), true, operator, controller);
        Answer read = get(operatorBase, "/v1/operator/risk/policies?operation=TRANSFER", operator);
        say("activate-risk read status=" + read.status() + " state=" + orDash(field(read.body(), "status")) + " current="
                + (decided.id() != null && decided.id().equals(field(read.body(), "id")) ? "yes" : "no") + " what=TRANSFER");
    }

    // ------------------------------------------------------------------ the access review, a first role, a compromise (11.37, 11.12)

    /** The administrator's read of the money-role holdings with no factor: how many, and whether "stranger" is among them. */
    private void accessReview(String label) throws Exception {
        String stranger = identity("stranger");
        Answer read = get(operatorBase, "/v1/operator/access-review/factorless-holdings", fresh("admin"));
        List<Object> holdings = arr(obj(tryJson(read.body())).get("holdings"));
        boolean named = false;
        for (Object holding : holdings) {
            named |= stranger != null && stranger.equals(text(obj(holding).get("identityId")));
        }
        say("access-review " + label + " status=" + read.status() + " holdings=" + holdings.size() + " stranger=" + (named ? "yes" : "no")
                + (read.status() == 200 ? "" : " code=" + codeOf(read)));
    }

    /** "stranger" enrols and confirms a factor on the operator host: the review's resolution that revokes nothing. */
    private void accessReviewEnrol() throws Exception {
        Answer signedIn = signIn(operatorBase, state.getProperty("stranger.login"));
        String session = signedIn.status() == 201 ? field(signedIn.body(), "sessionToken") : null;
        if (session == null) {
            say("access-review-enrol status=" + signedIn.status() + " code=" + codeOf(signedIn) + " step=sign-in");
            return;
        }
        Answer enrolment = post(operatorBase, "/v1/me/mfa", null, session, null);
        if (enrolment.status() != 201) {
            say("access-review-enrol status=" + enrolment.status() + " code=" + codeOf(enrolment) + " step=enrolment");
            return;
        }
        String secret = secretFrom(enrolment.body());
        long step = currentStep();
        Answer confirmed = post(operatorBase, "/v1/me/mfa/confirmation", "{\"code\":\"" + totp(secret, step - 1) + "\"}", session, null);
        if (ok(confirmed)) {
            state.setProperty("stranger.totp", secret);
            state.setProperty("stranger.step", Long.toString(step - 1));
            save();
        }
        say("access-review-enrol status=" + confirmed.status() + " code=" + codeOf(confirmed));
    }

    /**
     * Runbook 11.37's "a person given their first role signs in again": "holder" as a customer, on the public host, enrolled and stepped up
     * there; a first role (KYC_REVIEWER, which needs no factor) ends that session; the operator host signs "holder" in; a second role
     * (RISK_ANALYST) ends nothing, so the operator session still steps up.
     */
    private void firstRole() throws Exception {
        String id = requireIdentity("first-role", "holder");
        String login = state.getProperty("holder.login");
        Answer customer = signIn(publicBase, login);
        String session = customer.status() == 201 ? field(customer.body(), "sessionToken") : null;
        say("first-role customer-sign-in status=" + customer.status());
        if (session == null) {
            throw new IllegalStateException("holder could not sign in on the public host: " + customer.status());
        }
        Answer enrolment = post(publicBase, "/v1/me/mfa", null, session, null);
        String secret = enrolment.status() == 201 ? secretFrom(enrolment.body()) : null;
        if (secret != null) {
            long step = currentStep();
            Answer confirmed = post(publicBase, "/v1/me/mfa/confirmation", "{\"code\":\"" + totp(secret, step - 1) + "\"}", session, null);
            Answer elevated = post(publicBase, "/v1/authentications/mfa", "{\"code\":\"" + totp(secret, step) + "\"}", session, null);
            say("first-role customer-enrol status=" + confirmed.status() + " step-up=" + elevated.status());
            if (elevated.status() == 200 && field(elevated.body(), "sessionToken") != null) {
                session = field(elevated.body(), "sessionToken");
            }
            state.setProperty("holder.totp", secret);
            state.setProperty("holder.step", Long.toString(step));
            save();
        } else {
            say("first-role customer-enrol status=" + enrolment.status() + " code=" + codeOf(enrolment));
        }
        say("first-role customer-session status=" + get(publicBase, "/v1/me", session).status());
        String admin = fresh("admin");
        Answer first = grant(admin, id, "KYC_REVIEWER", "a first role, needing no factor" + WHY);
        say("first-role grant status=" + first.status() + (ok(first) ? "" : " code=" + codeOf(first)));
        say("first-role old-session status=" + get(publicBase, "/v1/me", session).status());
        Answer staff = signIn(operatorBase, login);
        say("first-role staff-sign-in status=" + staff.status());
        Answer second = grant(admin, id, "RISK_ANALYST", "a second role" + WHY);
        say("first-role second-grant status=" + second.status() + (ok(second) ? "" : " code=" + codeOf(second)));
        String operatorSession = staff.status() == 201 ? field(staff.body(), "sessionToken") : null;
        if (operatorSession == null) {
            say("first-role after-second status=- code=no-operator-session");
            return;
        }
        if (secret == null) {
            // No factor to step up with: a reviewer's read of a case that does not exist serves 404 to a live session, 401 to an ended one.
            say("first-role after-second status=" + get(operatorBase, "/v1/kyc/cases/" + uuid7(), operatorSession).status() + " via=kyc-case");
            return;
        }
        long last = Long.parseLong(state.getProperty("holder.step", "0"));
        long step = currentStep();
        while (step <= last) {
            pause(1000);
            step = currentStep();
        }
        Answer stepped = post(operatorBase, "/v1/authentications/mfa", "{\"code\":\"" + totp(secret, step) + "\"}", operatorSession, null);
        state.setProperty("holder.step", Long.toString(step));
        save();
        say("first-role after-second status=" + stepped.status() + " via=step-up");
    }

    /**
     * Runbook 11.37's compromised credential: the administrator suspends {@code who} with a reason naming the incident, which ends every
     * session it holds; a sign-in is refused while suspended; reinstatement with a reason restores no session, and a new sign-in is served.
     */
    /**
     * Section 11.12's compromise order (P15-TSK-032, as section 11.12 states it): suspend; the actor's acts read (the shell, from the audit
     * trail); every role revoked through the revocation door with the incident as reason; the channels and factor confirmed with the holder
     * (a person's act, recorded by the shell); reinstate, sessions not restored; the holder changes the password on the operator host at
     * once (recovery delivers no token until a channel adapter exists); every role granted again with a reason. The roles are read by the
     * shell into roles-{who}.txt before this runs. The new password is kept as {who}.password for this person alone.
     */
    private void compromise(String who, String incident) throws Exception {
        String id = requireIdentity("compromise", who);
        say("compromise identity=" + id);
        String login = state.getProperty(who + ".login");
        List<String> roles = Files.readAllLines(work.resolve("roles-" + who + ".txt")).stream().map(String::strip).filter(r -> !r.isEmpty()).toList();
        say("compromise roles=" + roles.size());
        Answer before = signIn(operatorBase, login);
        String previous = before.status() == 201 ? field(before.body(), "sessionToken") : null;
        if (previous == null) {
            say("compromise sign-in-before status=" + before.status());
            throw new IllegalStateException(who + " could not sign in before the suspension: " + before.status());
        }
        String admin = fresh("admin");
        String why = "incident " + incident + ": " + who + "'s password proven where staff are refused" + WHY;
        Answer suspended = post(operatorBase, "/v1/identities/" + segment(id) + "/suspension", reasonBody(why), admin, null);
        say("compromise suspend status=" + suspended.status() + (ok(suspended) ? "" : " code=" + codeOf(suspended)));
        say("compromise old-session status=" + get(operatorBase, "/v1/operator/events/dead-letters", previous).status());
        say("compromise sign-in status=" + signIn(operatorBase, login).status());
        for (String role : roles) {
            Answer revoked = call("DELETE", operatorBase + "/v1/identities/" + segment(id) + "/roles/" + segment(role), reasonBody(why),
                    Map.of("Authorization", "Bearer " + admin));
            say("compromise revoke role=" + role + " status=" + revoked.status() + (ok(revoked) ? "" : " code=" + codeOf(revoked)));
        }
        Answer reinstated = call("DELETE", operatorBase + "/v1/identities/" + segment(id) + "/suspension",
                reasonBody("incident " + incident + ": the channels and the factor confirmed with the holder on a channel already held" + WHY),
                Map.of("Authorization", "Bearer " + admin));
        say("compromise reinstate status=" + reinstated.status() + (ok(reinstated) ? "" : " code=" + codeOf(reinstated)));
        Answer again = signIn(operatorBase, login);
        say("compromise sign-in-after status=" + again.status());
        String replaced = "a-replaced-password-" + UUID.randomUUID();
        if (again.status() == 201) {
            // A holder of a factor changes the password on a stepped-up session alone (403 identity.AssuranceRequired otherwise): the factor
            // the holder confirmed is theirs is what proves them here. A factor the holder did not recognise means a new identity instead.
            String stepped = fresh(who);
            Answer changed = post(operatorBase, "/v1/me/credential", "{\"currentPassword\":" + jsonString(password()) + ",\"newPassword\":"
                    + jsonString(replaced) + "}", stepped, null);
            say("compromise password-change status=" + changed.status() + (ok(changed) ? "" : " code=" + codeOf(changed)));
            if (ok(changed)) {
                state.setProperty(who + ".password", replaced);
                save();
            }
        }
        for (String role : roles) {
            Answer regranted = grant(admin, id, role, "incident " + incident + ": " + who + "'s roles restored once the credential was replaced" + WHY);
            say("compromise regrant role=" + role + " status=" + regranted.status() + (ok(regranted) ? "" : " code=" + codeOf(regranted)));
        }
        Answer old = post(operatorBase, "/v1/authentications", "{\"loginIdentifier\":" + jsonString(login) + ",\"password\":"
                + jsonString(password()) + "}", null, null);
        say("compromise old-password status=" + old.status());
        Answer replacedIn = post(operatorBase, "/v1/authentications", "{\"loginIdentifier\":" + jsonString(login) + ",\"password\":"
                + jsonString(state.getProperty(who + ".password", password())) + "}", null, null);
        say("compromise new-password status=" + replacedIn.status());
    }

    /** A person's own password at the public sign-in: for a role-holder, refused and counted credential_proven (section 11.37's page). */
    private void publicSignIn(String who) throws Exception {
        Answer answer = signIn(publicBase, state.getProperty(who + ".login"));
        say("public-sign-in who=" + who + " status=" + answer.status());
    }

    // ------------------------------------------------------------------ the critical flows' signals (11.20, 11.22, 11.23, 11.30)

    private static String transferBody(String from, String to, String amount, String reference) {
        return "{\"sourceAccountId\":\"" + from + "\",\"destinationAccountId\":\"" + to + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\",\"reference\":\"" + reference + "\"}";
    }

    /** One key, two bodies: the second is INV-IDEM-03's conflict, refused and counted (TransferKeyConflict). */
    private void keyConflict(String label) throws Exception {
        String token = authenticate(state.getProperty("customer.0.login"));
        String reused = key();
        String reference = "kc-" + UUID.randomUUID();
        Answer first = post(publicBase, "/v1/transfers", transferBody(account(0), account(1), "0.01", reference), token, reused);
        say("key-conflict first status=" + first.status() + (ok(first) ? "" : " code=" + codeOf(first)) + " label=" + label);
        Answer second = post(publicBase, "/v1/transfers", transferBody(account(0), account(1), "0.02", reference), token, reused);
        say("key-conflict second status=" + second.status() + " code=" + codeOf(second) + " label=" + label);
    }

    /** One callback signed as "callbacks" signs, naming an operation reference of our shape that was never minted: PaymentWebhookUnmappable. */
    private void callbackUnmappable(String label) throws Exception {
        byte[] key = java.util.Base64.getDecoder().decode(Files.readString(work.resolve("provider-key")).strip());
        String body = "{\"eventId\":\"evt_" + UUID.randomUUID() + "\",\"operation\":\"never-minted-" + UUID.randomUUID()
                + "\",\"status\":\"captured\"}";
        say("callback-unmappable status=" + deliver(key, body) + " label=" + label);
    }

    /**
     * A PSP report whose one free-text field carries a card number (the well-known test number), through settle-file's door: the
     * screen refuses it at the door, keeping metadata only (422 settlement.DeliveryRefused, SettlementDeliveryRefused).
     */
    private void settlementRefused(String label) throws Exception {
        String day = today();
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        String report = "H,SIM_PSP_CSV,1,PSPB-REFUSED-" + tag + ",EUR," + day + "\n"
                + "D,1,SALE,1.00,0.03,EUR," + day + ",,,psp_cap-" + UUID.randomUUID() + ",,,,Card 4111111111111111\n"
                + "T,1,0.97,PSP-REM-81" + (10_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(89_999_999)) + "\n";
        Answer refused = post(operatorBase, "/v1/operator/settlement/files", "{\"sourceCode\":\"simulated-psp.settlement\",\"businessDate\":\""
                + day + "\",\"content\":\"" + java.util.Base64.getEncoder().encodeToString(report.getBytes(StandardCharsets.UTF_8)) + "\"}",
                fresh("operator"), key());
        say("settlement-refused status=" + refused.status() + " code=" + codeOf(refused) + " label=" + label);
    }

    /** The merchant's key revoked through its door with a reason; the old key refused; a new key issued and kept for later flows. */
    private void merchantKeyRevoke() throws Exception {
        String merchant = state.getProperty("merchant.id");
        String old = state.getProperty("merchant.key");
        String keyId = old.substring(0, old.indexOf('.'));
        String operator = fresh("operator");
        Answer revoked = call("DELETE", operatorBase + "/v1/operator/merchants/" + segment(merchant) + "/api-keys/" + segment(keyId),
                reasonBody("the merchant's key revoked: exposure suspected" + WHY), Map.of("Authorization", "Bearer " + operator));
        say("merchant-key-revoke revoke status=" + revoked.status() + (ok(revoked) ? "" : " code=" + codeOf(revoked)));
        say("merchant-key-revoke old-key status=" + get(publicBase, "/v1/merchant/me", old).status());
        Answer issued = post(operatorBase, "/v1/operator/merchants/" + segment(merchant) + "/api-keys", null, operator, key());
        String renewed = issued.status() == 201 && field(issued.body(), "secret") != null
                ? field(issued.body(), "keyId") + "." + field(issued.body(), "secret") : null;
        if (renewed != null) {
            state.setProperty("merchant.key", renewed);
            save();
        }
        say("merchant-key-revoke new-key status=" + issued.status() + " serves=" + (renewed == null ? "-" : Integer.toString(get(publicBase,
                "/v1/merchant/me", renewed).status())));
    }

    // ------------------------------------------------------------------ dead letters and replay (11.6)

    /**
     * The queues as the door serves them, one line per entry: kind is the discard door's subject kind (OUTBOX_EVENT for an abandoned
     * event, whose subject is its id; INBOX_RECORD for a parked record, whose subject is its park id), then the standing discards.
     */
    private void deadLetters(String label) throws Exception {
        Answer read = get(operatorBase, "/v1/operator/events/dead-letters", fresh("operator"));
        Map<String, Object> queues = obj(tryJson(read.body()));
        List<Object> abandoned = arr(queues.get("abandoned"));
        List<Object> parked = arr(queues.get("parked"));
        List<Object> standing = arr(queues.get("standingDiscards"));
        say("dead-letters " + label + " status=" + read.status() + " abandoned=" + abandoned.size() + " parked=" + parked.size() + " standing="
                + standing.size() + (read.status() == 200 ? "" : " code=" + codeOf(read)));
        for (Object entry : abandoned) {
            Map<String, Object> event = obj(entry);
            say("dead-letters " + label + " kind=OUTBOX_EVENT id=" + orDash(text(event.get("eventId"))) + " class="
                    + orDash(text(event.get("failureClass"))) + " park=-");
        }
        for (Object entry : parked) {
            Map<String, Object> record = obj(entry);
            say("dead-letters " + label + " kind=INBOX_RECORD id=" + orDash(text(record.get("dedupeKey"))) + " class="
                    + orDash(text(record.get("failureClass"))) + " park=" + orDash(text(record.get("parkId"))));
        }
        for (Object entry : standing) {
            Map<String, Object> discard = obj(entry);
            say("dead-letters " + label + " kind=STANDING_DISCARD id=" + orDash(text(discard.get("discardId"))) + " class=- park=- subject="
                    + orDash(text(discard.get("subjectKind"))) + ":" + orDash(text(discard.get("subjectId"))));
        }
    }

    private void releaseParked(String parkId) throws Exception {
        Answer released = post(operatorBase, "/v1/operator/events/parked/" + segment(parkId) + "/release",
                reasonBody("the cause fixed, the record released" + WHY), fresh("operator"), key());
        say("release-parked status=" + released.status() + " code=" + codeOf(released));
    }

    /** A discard is two people: proposed by "operator", refused to "operator", approved by "controller" (EVENT_OPERATOR from platform-staff). */
    private void discard(String subjectKind, String subjectId) throws Exception {
        fourEyes("discard", "self", "", "/v1/operator/events/discards", "{\"subjectKind\":" + jsonString(subjectKind) + ",\"subjectId\":"
                        + jsonString(subjectId) + ",\"reason\":" + jsonString("the fact can never be delivered; recovered from its owner" + WHY) + "}",
                "subject", "/approval", reasonBody("checked by a second person" + WHY), true, fresh("operator"), fresh("controller"));
    }

    private void retryAbandoned(String eventId) throws Exception {
        Answer retried = post(operatorBase, "/v1/operator/events/abandoned/" + segment(eventId) + "/retry",
                reasonBody("the environmental cause fixed" + WHY), fresh("operator"), key());
        say("retry-abandoned status=" + retried.status() + " code=" + codeOf(retried));
    }

    // ------------------------------------------------------------------ the ledger and the books (10.17, 11.20, 11.37)

    /** Runbook 10.17: a drifting projection's rebuild, proposed by one ledger operator and approved by another; the approval takes no body. */
    private void rebuildProjection(String accountId) throws Exception {
        fourEyes("rebuild-projection", "self", "", "/v1/ledger/projection-rebuilds", "{\"ledgerAccountId\":" + jsonString(accountId)
                        + ",\"reason\":" + jsonString("the close's PROJECTION_VERIFIED check named this account" + WHY) + "}",
                "rebuildId", "/approval", null, true, fresh("operator"), fresh("controller"));
    }

    /** The accounting period of this month, from the periods the door lists; null when none. */
    private String currentPeriod(String token, String command) throws IOException, InterruptedException {
        Answer periods = get(operatorBase, "/v1/operator/accounting/periods", token);
        for (Object period : arr(tryJson(periods.body()))) {
            if (period().equals(text(obj(period).get("period")))) {
                return text(obj(period).get("id"));
            }
        }
        say(command + " status=" + periods.status() + " run=- lines=0 code=" + (periods.status() == 200 ? "no-period-" + period() : codeOf(periods)));
        return null;
    }

    private Answer generate(String token, String periodId) throws IOException, InterruptedException {
        return post(operatorBase, "/v1/operator/accounting/report-runs", "{\"definition\":\"TRIAL_BALANCE\",\"periodId\":\"" + periodId + "\"}",
                token, key());
    }

    /**
     * Section 11.20's step: this month's TRIAL_BALANCE generated, waited for, and its lines read. The lines door drills one figure - one GL
     * account, one month, one currency - so the first row of the run's content that has lines is the one drilled, and the page counted.
     */
    private void trialBalanceReport() throws Exception {
        String operator = fresh("operator");
        String periodId = currentPeriod(operator, "trial-balance-report");
        if (periodId == null) {
            throw new IllegalStateException("no accounting period for " + period());
        }
        Answer generated = generate(operator, periodId);
        String run = ok(generated) ? field(generated.body(), "id") : null;
        if (run == null) {
            say("trial-balance-report status=" + generated.status() + " run=- lines=0 code=" + codeOf(generated));
            return;
        }
        Map<String, Object> content = Map.of();
        String runState = field(generated.body(), "status");
        for (int polls = 0; polls < 30; polls++) {
            Answer read = get(operatorBase, "/v1/operator/accounting/report-runs/" + run, operator);
            Map<String, Object> view = obj(tryJson(read.body()));
            runState = text(obj(view.get("run")).get("status"));
            if (runState != null && !"GENERATING".equals(runState)) {
                content = obj(tryJson(text(view.get("content"))));
                break;
            }
            pause(2000);
        }
        Map<String, Object> row = rowWithLines(content);
        if (row == null) {
            say("trial-balance-report status=" + generated.status() + " run=" + run + " lines=0 state=" + orDash(runState) + " lines-status=-");
            return;
        }
        Answer lines = get(operatorBase, "/v1/operator/accounting/report-runs/" + run + "/lines?account=" + segment(text(row.get("account")))
                + "&month=" + period() + "&currency=" + segment(text(row.get("currency"))), operator);
        int count = arr(obj(obj(tryJson(lines.body())).get("activity")).get("lines")).size();
        say("trial-balance-report status=" + generated.status() + " run=" + run + " lines=" + count + " state=" + orDash(runState) + " lines-status="
                + lines.status() + (lines.status() == 200 ? "" : " code=" + codeOf(lines)));
    }

    /** The first account row of a report's content with lines in its movement, else the first account row, anywhere in the content. */
    private static Map<String, Object> rowWithLines(Object content) {
        List<Map<String, Object>> rows = new ArrayList<>();
        collectRows(content, rows);
        for (Map<String, Object> row : rows) {
            if (number(row.get("lines")) > 0) {
                return row;
            }
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void collectRows(Object node, List<Map<String, Object>> rows) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> object = obj(map);
            if (object.get("account") instanceof String && object.get("currency") instanceof String && object.get("lines") instanceof BigDecimal) {
                rows.add(object);
            }
            object.values().forEach(value -> collectRows(value, rows));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> collectRows(value, rows));
        }
    }

    /** A report run through report generation's own pool: the door a refused reporting role answers 500 at (11.37). */
    private void reportRun(String label) throws Exception {
        String operator = fresh("operator");
        String periodId = currentPeriod(operator, "report-run " + label);
        if (periodId == null) {
            throw new IllegalStateException("no accounting period for " + period());
        }
        Answer generated = generate(operator, periodId);
        say("report-run " + label + " status=" + generated.status() + " code=" + codeOf(generated) + " state="
                + orDash(ok(generated) ? field(generated.body(), "status") : null));
    }

    // ------------------------------------------------------------------ the doors and the surfaces (11.30, 11.37), and a held payout

    /** {@code n} sign-ins with a wrong password for one customer login of its own, registered here so that no fixture is locked out. */
    private void signInFlood(String label, int n) throws Exception {
        String login = someLogin("flood");
        register(login);
        int unauthorised = 0;
        int limited = 0;
        int other = 0;
        for (int i = 0; i < n; i++) {
            int status;
            try {
                status = post(publicBase, "/v1/authentications", "{\"loginIdentifier\":\"" + login + "\",\"password\":\"not-the-password-" + i
                        + "\"}", null, null).status();
            } catch (IOException unanswered) {
                status = -1;
            }
            if (status == 401) {
                unauthorised++;
            } else if (status == 429) {
                limited++;
            } else {
                other++;
            }
        }
        say("sign-in-flood " + label + " 401=" + unauthorised + " 429=" + limited + " other=" + other);
    }

    /** A staff sign-in on the operator host and a customer's on the public host: with the edge's marker gone, staff are refused there. */
    private void surfaceUnmarked(String label) throws Exception {
        say("surface-unmarked " + label + " staff status=" + signIn(operatorBase, state.getProperty("operator.login")).status());
        say("surface-unmarked " + label + " customer status=" + signIn(publicBase, state.getProperty("customer.0.login")).status());
    }

    /** One merchant payout while the simulator holds inquiries (/tmp/hold-inquiries): its creation unanswered, so the payout is UNKNOWN. */
    private void payoutHeld(String label) throws Exception {
        Answer created;
        try {
            created = post(publicBase, "/v1/merchant/payouts", "{\"amount\":\"0.10\",\"currency\":\"EUR\"}", state.getProperty("merchant.key"), key());
        } catch (IOException unanswered) {
            say("payout-held " + label + " status=-1 payout=- state=-");
            return;
        }
        String payout = ok(created) ? field(created.body(), "id") : null;
        say("payout-held " + label + " status=" + created.status() + " payout=" + orDash(payout) + " state="
                + orDash(ok(created) ? field(created.body(), "status") : null) + (ok(created) ? "" : " code=" + codeOf(created)));
        if (payout != null) {
            state.setProperty("payout.held." + label, payout);
            save();
        }
    }

    // ------------------------------------------------------------------ the bodies, each from the application's own fixtures

    /** PricingPolicyV1 (app tests, held to O7 by PricingPolicyV1Test): 20 directional pairs of the five currencies under both purposes. */
    private static String pricingPolicyV1(String reason) {
        List<String> currencies = List.of("EUR", "GBP", "USD", "JPY", "BHD");
        List<String> tight = List.of("EUR", "GBP", "USD");
        StringBuilder pairs = new StringBuilder();
        for (String purpose : List.of("CONVERSION", "CROSS_BORDER")) {
            for (String source : currencies) {
                for (String destination : currencies) {
                    if (source.equals(destination)) {
                        continue;
                    }
                    boolean narrow = tight.contains(source) && tight.contains(destination);
                    pairs.append(pairs.length() == 0 ? "" : ",")
                            .append("{\"source\":\"").append(source).append("\",\"destination\":\"").append(destination)
                            .append("\",\"purpose\":\"").append(purpose).append("\",\"providers\":[\"fx-sim-a\"]")
                            .append(",\"spread\":\"0.003500\",\"markup\":\"0.001500\",\"rateScale\":").append(source.equals("JPY") ? 10 : 6)
                            .append(",\"rateRounding\":\"TOWARDS_ZERO\",\"amountRounding\":\"HALF_EVEN\",\"marginRounding\":\"HALF_EVEN\"")
                            .append(",\"windowSeconds\":").append(purpose.equals("CONVERSION") ? 30 : 60)
                            .append(",\"coverMarginSeconds\":10,\"band\":\"").append(narrow ? "0.015" : "0.03")
                            .append("\",\"referenceMaxAgeSeconds\":120")
                            .append(",\"sourceMinimum\":\"").append(notionalMinimum(source))
                            .append("\",\"sourceMaximum\":\"").append(notionalMaximum(source))
                            .append("\",\"destinationMinimum\":\"").append(notionalMinimum(destination))
                            .append("\",\"destinationMaximum\":\"").append(notionalMaximum(destination)).append("\"}");
                }
            }
        }
        return "{\"pairs\":[" + pairs + "],\"openQuoteCap\":5,\"reason\":" + jsonString(reason) + "}";
    }

    private static String notionalMinimum(String currency) {
        return switch (currency) {
            case "JPY" -> "100";
            case "BHD" -> "0.500";
            default -> "1.00";
        };
    }

    private static String notionalMaximum(String currency) {
        return switch (currency) {
            case "JPY" -> "7500000";
            case "BHD" -> "20000.000";
            default -> "50000.00";
        };
    }

    /** FxRuleSetV1 (app tests, held by FxRuleSetV1Test) in the rule-set door's words. */
    private static String fxRuleSetV1(String reason) {
        String[][] currencies = {{"EUR", "2", "100000"}, {"GBP", "2", "100000"}, {"USD", "2", "100000"}, {"JPY", "0", "150000"},
            {"BHD", "3", "400000"}};
        StringBuilder fees = new StringBuilder();
        StringBuilder thresholds = new StringBuilder();
        for (String[] currency : currencies) {
            fees.append(fees.length() == 0 ? "" : ",").append("{\"lineType\":\"FX_FEE\",\"currency\":\"").append(currency[0])
                    .append("\",\"rate\":\"0.000000\",\"fixedMinor\":0,\"scale\":").append(currency[1]).append(",\"roundingPolicy\":\"HALF_UP\"}");
            thresholds.append(thresholds.length() == 0 ? "" : ",").append('"').append(currency[0]).append("\":").append(currency[2]);
        }
        return "{\"sourceCode\":\"fx-sim-a.trade-report\",\"reason\":" + jsonString(reason) + ",\"fundingLagDays\":2,\"gainMinAgeDays\":90,"
                + "\"lagDays\":{\"FX_SELL_LEG\":2,\"FX_BUY_LEG\":2},"
                + "\"rules\":[{\"priority\":1,\"lineType\":\"FX_SOLD\",\"keyKind\":\"COVER_REF\",\"expectationKind\":\"FX_SELL_LEG\","
                + "\"cardinality\":\"ONE_TO_ONE\",\"operationAnchored\":false,\"graceHours\":24},"
                + "{\"priority\":2,\"lineType\":\"FX_BOUGHT\",\"keyKind\":\"COVER_REF\",\"expectationKind\":\"FX_BUY_LEG\","
                + "\"cardinality\":\"ONE_TO_ONE\",\"operationAnchored\":false,\"graceHours\":24},"
                + "{\"priority\":3,\"lineType\":\"FX_FEE\",\"keyKind\":\"ORIGINAL_REF\",\"cardinality\":\"CHECK\",\"operationAnchored\":false,"
                + "\"graceHours\":24}],"
                + "\"tolerances\":[{\"comparison\":\"SETTLEMENT_DATE_DAYS\",\"days\":2}],"
                + "\"feeSchedules\":[" + fees + "],\"severityThresholds\":{" + thresholds + "}}";
    }

    /** CorridorPolicyV1.json (app tests): O7's four corridors on corridor-sim-a. */
    private static String corridorPolicyV1(String reason) {
        String[][] rows = {{"EUR", "USD", "US", "2.50", "10000.00"}, {"EUR", "JPY", "JP", "2.50", "1500000"}, {"USD", "BHD", "BH", "3.00", "4000.000"},
            {"GBP", "USD", "US", "2.00", "10000.00"}};
        StringBuilder corridors = new StringBuilder();
        for (String[] row : rows) {
            corridors.append(corridors.length() == 0 ? "" : ",").append("{\"source\":\"").append(row[0]).append("\",\"destination\":\"")
                    .append(row[1]).append("\",\"country\":\"").append(row[2]).append("\",\"rails\":[\"corridor-sim-a\"],\"feeFixed\":\"")
                    .append(row[3]).append("\",\"feeMargin\":\"0\",\"feeRounding\":\"HALF_EVEN\",\"maximum\":\"").append(row[4])
                    .append("\",\"screeningValidityHours\":168,\"deliveryEstimateHours\":24,\"requiredData\":[\"BENEFICIARY_NAME\",\"ENTITY_TYPE\"]}");
        }
        return "{\"corridors\":[" + corridors + "],\"reason\":" + jsonString(reason) + "}";
    }

    /** RETAIL_SCORECARD v1 as credit V006 seeds it, in the scorecard door's words (ScorecardAdministrationEndpointDatabaseTest's shape). */
    private static String scorecardV1(String reason) {
        return "{\"family\":\"RETAIL_SCORECARD\",\"basePoints\":500,\"attributes\":["
                + "{\"code\":\"BUREAU_EXTERNAL_SCORE\",\"absentPoints\":-40,\"bands\":[{\"upper\":550,\"points\":-60},"
                + "{\"lower\":550,\"upper\":650,\"points\":0},{\"lower\":650,\"upper\":750,\"points\":40},{\"lower\":750,\"points\":80}]},"
                + "{\"code\":\"BUREAU_DELINQUENCIES_24M\",\"absentPoints\":-20,\"bands\":[{\"upper\":1,\"points\":30},"
                + "{\"lower\":1,\"upper\":3,\"points\":-30},{\"lower\":3,\"points\":-90}]},"
                + "{\"code\":\"BUREAU_DEFAULTS_72M\",\"absentPoints\":-20,\"bands\":[{\"upper\":1,\"points\":20},{\"lower\":1,\"points\":-120}]},"
                + "{\"code\":\"BUREAU_ACTIVE_ACCOUNTS\",\"absentPoints\":0,\"bands\":[{\"upper\":1,\"points\":-10},"
                + "{\"lower\":1,\"upper\":6,\"points\":10},{\"lower\":6,\"points\":-20}]},"
                + "{\"code\":\"BUREAU_INSOLVENCY_FLAG\",\"absentPoints\":-30,\"bands\":[{\"codes\":[\"false\"],\"points\":10},"
                + "{\"codes\":[\"true\"],\"points\":-200}]}],\"reason\":" + jsonString(reason) + "}";
    }

    /** LendingStormWorld.terms(product, "UTC", true, rate, reason): a whole terms version of one loan product. */
    private static String loanTerms(String product, String rate, String reason) {
        StringBuilder days = new StringBuilder("[");
        for (int day = 1; day <= 28; day++) {
            days.append(day == 1 ? "" : ",").append(day);
        }
        StringBuilder json = new StringBuilder("{\"product\":\"").append(product).append("\",\"currency\":\"EUR\"")
                .append(",\"nominalAnnualRate\":\"").append(rate).append("\",\"rateType\":\"FIXED\"")
                .append(",\"originationFeeMinor\":0,\"originationFeeTreatment\":\"DEDUCTED\"")
                .append(",\"lateFeeMinor\":1500,\"lateFeeDay\":5,\"lateFeeCapMinor\":6000,\"graceDays\":3")
                .append(",\"delinquencyBounds\":[1,30,60,90],\"defaultThresholdDays\":90")
                .append(",\"allocationSequence\":\"OLDEST_DUE_FIRST\",\"allocationOrder\":[\"FEES\",\"INTEREST\",\"PRINCIPAL\"]")
                .append(",\"autoCollection\":true,\"partialCollection\":true")
                .append(",\"instalmentRounding\":\"UP\",\"interestRounding\":\"HALF_EVEN\",\"dayCount\":\"ACT_365F\"")
                .append(",\"servicingZone\":\"UTC\"")
                .append(",\"allowedDays\":").append(days.append(']'))
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
        return json.append(",\"agreementTemplateVersion\":1,\"reason\":").append(jsonString(reason)).append('}').toString();
    }

    /** BnplStormWorld.bnplTerms(product, {reason}): a whole terms version of one BNPL product. */
    private static String bnplTerms(String product, String reason) {
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
        fields.put("reason", jsonString(reason));
        StringBuilder json = new StringBuilder("{");
        fields.forEach((name, value) -> json.append(json.length() == 1 ? "" : ",").append('"').append(name).append("\":").append(value));
        return json.append('}').toString();
    }

    /**
     * A risk policy version that judges permissively, in RiskPolicyEndpointDatabaseTest's words: one AMOUNT_BAND rule weighted 1 (a rule
     * must carry a weight or a hard decision), every score band ALLOW from 0 to 1000, one open-ended fail-safe band ALLOW, the challenge at
     * MULTI_FACTOR and an override threshold as the version requires; no HOLD anywhere, so no hold validity.
     */
    private static String permissiveRiskPolicy(String operation, String reason) {
        return "{\"operation\":\"" + operation + "\",\"engine\":\"RISK_ENGINE_V1\",\"currency\":\"EUR\","
                + "\"rules\":[{\"kind\":\"AMOUNT_BAND\",\"parameters\":{\"threshold_minor\":1000000000},\"weight\":1}],"
                + "\"scoreBands\":[{\"from\":0,\"to\":1000,\"decision\":\"ALLOW\"}],"
                + "\"failSafeBands\":[{\"fromMinor\":0,\"decision\":\"ALLOW\"}],"
                + "\"challengeAssurance\":\"MULTI_FACTOR\",\"overrideThresholdMinor\":100000,\"reason\":" + jsonString(reason) + "}";
    }

    // ------------------------------------------------------------------ a small JSON reader and writer, for the bodies restated whole

    /** Parses a JSON text into maps, lists, strings, BigDecimals, Booleans and nulls; null when the text is not JSON. */
    private static Object tryJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonCursor cursor = new JsonCursor(text);
            Object value = cursor.value();
            cursor.space();
            return cursor.at == text.length() ? value : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal number ? number.toPlainString() : value.toString();
    }

    private static long number(Object value) {
        return value instanceof BigDecimal number ? number.longValue() : 0L;
    }

    /** The named members of {@code from} that are present and not null, in that order. */
    private static Map<String, Object> picked(Map<String, Object> from, String... names) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : names) {
            if (from.get(name) != null) {
                out.put(name, from.get(name));
            }
        }
        return out;
    }

    private static final class JsonCursor {

        private final String text;
        private int at;

        JsonCursor(String text) {
            this.text = text;
        }

        void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        Object value() {
            space();
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Object word(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw new IllegalStateException("not JSON at " + at);
            }
            at += word.length();
            return value;
        }

        private Map<String, Object> object() {
            Map<String, Object> members = new LinkedHashMap<>();
            at++;
            space();
            if (text.charAt(at) == '}') {
                at++;
                return members;
            }
            while (true) {
                space();
                String name = string();
                space();
                if (text.charAt(at++) != ':') {
                    throw new IllegalStateException("not JSON at " + at);
                }
                members.put(name, value());
                space();
                char next = text.charAt(at++);
                if (next == '}') {
                    return members;
                }
                if (next != ',') {
                    throw new IllegalStateException("not JSON at " + at);
                }
            }
        }

        private List<Object> array() {
            List<Object> items = new ArrayList<>();
            at++;
            space();
            if (text.charAt(at) == ']') {
                at++;
                return items;
            }
            while (true) {
                items.add(value());
                space();
                char next = text.charAt(at++);
                if (next == ']') {
                    return items;
                }
                if (next != ',') {
                    throw new IllegalStateException("not JSON at " + at);
                }
            }
        }

        private String string() {
            if (text.charAt(at++) != '"') {
                throw new IllegalStateException("not JSON at " + at);
            }
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(escaped);
                }
            }
        }

        private BigDecimal number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            return new BigDecimal(text.substring(start, at));
        }
    }

    private static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        writeJson(out, value);
        return out.toString();
    }

    private static void writeJson(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            out.append(jsonString(string));
        } else if (value instanceof BigDecimal number) {
            out.append(number.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> member : map.entrySet()) {
                out.append(first ? "" : ",").append(jsonString(String.valueOf(member.getKey()))).append(':');
                writeJson(out, member.getValue());
                first = false;
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "" : ",");
                writeJson(out, list.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("no JSON for a " + value.getClass().getSimpleName());
        }
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
