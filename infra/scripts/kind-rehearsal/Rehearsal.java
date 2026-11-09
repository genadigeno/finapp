import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
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
        Rehearsal rehearsal = new Rehearsal(Path.of(args[0]));
        switch (args[1]) {
            case "prepare" -> rehearsal.prepare();
            case "onboard" -> rehearsal.onboard();
            case "smoke" -> rehearsal.smoke(args[2]);
            case "workload" -> rehearsal.workload(args[2]);
            case "replay" -> rehearsal.replay(args[2], args[3], args[4], args[5]);
            case "edge" -> rehearsal.edge();
            case "map-gl" -> rehearsal.mapGl();
            case "gl-check" -> rehearsal.glCheck();
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

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String someLogin(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ---------------------------------------------------------------------------------------------- the fixtures

    private static final int CUSTOMERS = 4;

    private Answer register(String login) throws IOException, InterruptedException {
        return post(publicBase, "/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null, key());
    }

    private String authenticate(String login) throws IOException, InterruptedException {
        Answer answer = expect("authenticate " + login, post(publicBase, "/v1/authentications",
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
        String party = UUID.randomUUID().toString();
        state.setProperty("merchant.party", party);
        save();
        String seed = "\\set ON_ERROR_STOP on\n"
                + "UPDATE party.customer SET status = 'ACTIVE', status_changed_at = GREATEST(now(), opened_at)\n"
                + " WHERE party_id IN (SELECT party_id FROM identity.identity WHERE login_identifier IN ("
                + String.join(",", customers) + "));\n"
                + "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)\n"
                + " SELECT gen_random_uuid(), i.id, r, i.id, now()\n"
                + "   FROM identity.identity i, unnest(ARRAY['MERCHANT_ADMINISTRATOR','EVENT_OPERATOR','ACCOUNTANT']) r\n"
                + "  WHERE i.login_identifier = '" + operator + "';\n"
                + "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at)\n"
                + " SELECT gen_random_uuid(), i.id, 'FINANCIAL_CONTROLLER', i.id, now()\n"
                + "   FROM identity.identity i WHERE i.login_identifier = '" + controller + "';\n"
                + "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES\n"
                + " ('" + party + "', 'ORGANISATION', 'Rehearsal Holdings', now());\n"
                + "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at) VALUES\n"
                + " (gen_random_uuid(), '" + party + "', 'ACTIVE', now() - interval '1 hour', now());\n";
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
                + " (gen_random_uuid(), '" + merchantId + "', '" + reference + "', '3000', 'EFFECTIVE', 'rehearsal-a', now() - interval '4 days',\n"
                + "  'the rehearsal fixture', 'rehearsal-b', now() - interval '4 days', now() - interval '1 day', now() - interval '1 hour');\n");
        System.out.println("onboarded merchant, " + CUSTOMERS + " customers with wallets and cards");
    }

    /** Enrols a TOTP factor for {@code who} and answers an elevated session; the factor's secret stays in this run's state. */
    private String enrol(String who) throws Exception {
        String session = authenticate(state.getProperty(who + ".login"));
        String secret = secretFrom(expect("mfa enrolment of " + who, post(publicBase, "/v1/me/mfa", null, session, null), 201).body());
        long step = currentStep();
        expect("mfa confirmation of " + who, post(publicBase, "/v1/me/mfa/confirmation", "{\"code\":\"" + totp(secret, step - 1) + "\"}", session, null), 204);
        Answer elevated = expect("elevation of " + who, post(publicBase, "/v1/authentications/mfa", "{\"code\":\"" + totp(secret, step) + "\"}", session, null), 200);
        state.setProperty(who + ".totp", secret);
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
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, null), 201).body(), "sessionToken");
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

    private String fresh(String who) throws Exception {
        String session = authenticate(state.getProperty(who + ".login"));
        String secret = state.getProperty(who + ".totp");
        Answer elevated = expect("elevation", post(publicBase, "/v1/authentications/mfa",
                "{\"code\":\"" + totp(secret, currentStep()) + "\"}", session, null), 200);
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
        String marker = UUID.randomUUID().toString();
        call("GET", publicBase + "/v1/me?forged=" + marker, null, Map.of("X-Forwarded-For", "198.51.100.77"));
        System.out.println("edge forged marker=" + marker + " client=" + java.net.InetAddress.getLocalHost().getHostAddress());
    }
}
