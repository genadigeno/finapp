import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TEST-ONLY. The HTTP half of the Operator Console's end-to-end seed (P17-TSK-106, P17-TSK-108, P17-TSK-110): every effect the
 * platform has a door for, through that door - registration, a second factor, a step-up, a role grant, a risk policy proposed and
 * activated, a case opened and taken. A single source file run by {@code infra/scripts/e2e-seed.sh}
 * ({@code java --source 21}), never compiled into anything and named by no module's main source set (E2eSeedStaysTestOnlyTest).
 *
 * <p>It has no route to the database and must not have one. What only the database can answer - a test identity's id, which no API
 * exposes before it holds a role (the console's gap G-10), and the first administrator, which no API can make (README section 5e) -
 * the shell asks and hands in, as the kind rehearsal's client and shell divide the same work.
 *
 * <p>Usage: {@code Seed.java <public base> <operator base> <state dir> <command> [arguments]}; the bases are the same URL on the compose
 * stack's single port. The state directory is the run's own temporary directory, removed by the shell when the run ends: the test
 * identities' passwords and factor secrets live there and nowhere else, and never reach standard output, which carries only
 * {@code KEY=value} lines for the shell.
 */
public final class Seed {

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");

    private final String publicBase;
    private final String operatorBase;
    private final Path state;
    private final Properties people = new Properties();
    private final HttpClient http;

    private Seed(String publicBase, String operatorBase, Path state) throws Exception {
        this.publicBase = publicBase;
        this.operatorBase = operatorBase;
        this.state = state;
        Path saved = state.resolve("people.properties");
        if (Files.exists(saved)) {
            try (var in = Files.newInputStream(saved)) {
                people.load(in);
            }
        }
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10));
        Path ca = state.resolve("edge-ca.crt");
        if (Files.exists(ca)) {
            builder.sslContext(trusting(ca));
        }
        this.http = builder.build();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException("usage: Seed.java <public base> <operator base> <state dir> <command> [arguments]");
        }
        Seed seed = new Seed(args[0], args[1], Path.of(args[2]));
        String[] rest = java.util.Arrays.copyOfRange(args, 4, args.length);
        switch (args[3]) {
            case "register" -> seed.register(one(rest));
            case "register-bare" -> seed.registerBare(one(rest));
            case "grant" -> seed.grant(rest[0], rest[1], java.util.Arrays.copyOfRange(rest, 2, rest.length));
            case "upload" -> seed.upload(rest[0], rest[1]);
            case "await-file" -> seed.awaitFile(rest[0], rest[1], rest[2]);
            case "attest" -> seed.attest(rest[0], rest[1]);
            case "await-break" -> seed.awaitBreak(rest[0], rest[1]);
            case "propose-policy" -> seed.proposePolicy(rest[0], rest[1]);
            case "activate-policy" -> seed.activatePolicy(rest[0], rest[1]);
            case "open-case" -> seed.openCase(rest[0], rest[1]);
            case "take-case" -> seed.takeCase(rest[0], rest[1]);
            default -> throw new IllegalArgumentException("unknown command " + args[3]);
        }
    }

    private static String one(String[] rest) {
        if (rest.length != 1) {
            throw new IllegalArgumentException("one argument expected");
        }
        return rest[0];
    }

    // ---------------------------------------------------------------------------------------------- identities, through their doors

    /**
     * Registers a fresh test identity on the public host and enrols its second factor there, before it holds any role: every operator
     * act asks for a stepped-up session, and a role that can act is granted only to a holder with a factor. Prints its login for the
     * shell to look its id up.
     */
    private void register(String who) throws Exception {
        String login = registered(who);
        String session = signIn(publicBase, who);
        String secret = secretFrom(expect("factor enrolment of " + who, post(publicBase, "/v1/me/mfa", null, session, null), 201).body());
        // The previous step's code, inside the verifier's window, as the kind rehearsal confirms: the first step-up may then use the
        // current step without waiting out a thirty-second step.
        long step = currentStep() - 1;
        expect("factor confirmation of " + who, post(publicBase, "/v1/me/mfa/confirmation",
                "{\"code\":\"" + totp(secret, step) + "\"}", session, null), 204);
        people.setProperty(who + ".totp", secret);
        people.setProperty(who + ".step", Long.toString(step));
        save();
        System.out.println("LOGIN=" + login);
    }

    /**
     * Registers a fresh test identity on the public host and nothing more (P17-TSK-108): no session, no second factor. The holder the
     * factorless review exists for - the shell plants its role, since the grant door refuses a factor-requiring role to it. Prints its
     * login for the shell to look its id up.
     */
    private void registerBare(String who) throws Exception {
        String login = registered(who);
        save();
        System.out.println("LOGIN=" + login);
    }

    /** The registration door, for a fresh login and password kept in the run's state. Answers the login. */
    private String registered(String who) throws Exception {
        String login = "e2e-seed." + who + "." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "E2e-seed-" + UUID.randomUUID();
        expect("registration of " + who, post(publicBase, "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + password + "\",\"displayName\":\"E2E seed " + who + "\"}", null, key()),
                201);
        people.setProperty(who + ".login", login);
        people.setProperty(who + ".password", password);
        return login;
    }

    /** The administrator's grant of one role to each identity named, on one stepped-up session, through the identity door. */
    private void grant(String admin, String role, String... identityIds) throws Exception {
        String session = elevated(admin);
        for (String identityId : identityIds) {
            expect("grant of " + role + " to " + identityId, post(operatorBase, "/v1/identities/" + identityId + "/roles",
                    "{\"role\":\"" + role + "\",\"reason\":\"e2e seed: a test operator for the console's end-to-end tests\"}", session, null),
                    200, 201, 204);
        }
    }

    // ---------------------------------------------------------------------------------------------- settlement, through its doors

    /** The PSP source every environment's migrations declare (settlement V002): the simulated card PSP's daily report. */
    private static final String PSP_SOURCE = "simulated-psp.settlement";

    /**
     * Uploads a one-sale report of the simulated PSP as {@code who}. Every reference is fresh, so no two runs' bytes or batches meet:
     * the same bytes converge on the earlier file ({@code duplicateOf}) and a live batch's (source, batch, currency) is unique.
     * References are letters, and the remittance's eight digits are far below a card number's length, so the door's screen passes them.
     *
     * <ul>
     *   <li>{@code parsed}: one sale, no fee - the file the console attests or declines;
     *   <li>{@code fee}: one sale carrying a 1.75 fee. No capture expectation reaches the sale's reference (locally no card rail
     *       completes captures: no provider is configured), so the fee check prices the fee at zero and raises a FEE_MISMATCH of
     *       1.75 in the run itself (Matching.applyFeeCheck) - a break two persons can ACKNOWLEDGE, moving no money.
     * </ul>
     */
    private void upload(String who, String kind) throws Exception {
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String fee = switch (kind) {
            case "parsed" -> "";
            case "fee" -> "1.75";
            default -> throw new IllegalArgumentException("upload parsed or fee, not " + kind);
        };
        String net = fee.isEmpty() ? "10.00" : "8.25";
        String report = "H,SIM_PSP_CSV,1,PSPB-E2E-" + letters(12) + ",EUR," + day + "\n"
                + "D,1,SALE,10.00," + fee + ",EUR," + day + ",,,PSP-CAP-E2E" + letters(12) + ",,,ORD-E2E" + letters(8) + ",E2E seed sale\n"
                + "T,1," + net + ",PSP-REM-" + digits(8) + "\n";
        String content = java.util.Base64.getEncoder().encodeToString(report.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String session = elevated(who);
        Answer received = expect("upload by " + who, post(operatorBase, "/v1/operator/settlement/files",
                "{\"sourceCode\":\"" + PSP_SOURCE + "\",\"businessDate\":\"" + day + "\",\"content\":\"" + content + "\"}", session, key()), 202);
        String fileId = field(received.body(), "fileId");
        if (fileId == null || field(received.body(), "duplicateOf") != null) {
            throw new IllegalStateException("the upload did not receive a fresh file: " + received.body());
        }
        System.out.println("FILE_ID=" + fileId);
    }

    /** Waits for the intake's sweeps (every instance's, every fifteen seconds by default) to bring the file to a status. */
    private void awaitFile(String who, String fileId, String wanted) throws Exception {
        String session = signIn(operatorBase, who);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            status = field(expect("file " + fileId, get(operatorBase, "/v1/operator/settlement/files/" + fileId, session), 200).body(), "status");
            if (wanted.equals(status)) {
                return;
            }
            if ("REJECTED".equals(status)) {
                throw new IllegalStateException("file " + fileId + " was rejected while waiting for " + wanted);
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("file " + fileId + " stood " + status + " after three minutes, not " + wanted);
    }

    /** The second person's attestation (INV-SET-07): {@code who} is never the submitter, or the door refuses. */
    private void attest(String who, String fileId) throws Exception {
        String session = elevated(who);
        expect("attestation by " + who, post(operatorBase, "/v1/operator/settlement/files/" + fileId + "/attestation", null, session, null), 200);
    }

    /**
     * Finds the open FEE_MISMATCH this file raised, by each candidate's stored trace back to the raw file. The break list is a
     * section 7 collection since P17-TSK-042: every page is walked, its opaque {@code nextCursor} followed, never read.
     */
    private void awaitBreak(String who, String fileId) throws Exception {
        String session = signIn(operatorBase, who);
        Pattern ids = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");
        Pattern next = Pattern.compile("\"nextCursor\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(deadline)) {
            String cursor = null;
            do {
                String list = expect("breaks", get(operatorBase, "/v1/operator/reconciliation/breaks?type=FEE_MISMATCH&status=OPEN&limit=200"
                        + (cursor == null ? "" : "&cursor=" + cursor), session), 200).body();
                Matcher candidate = ids.matcher(list);
                while (candidate.find()) {
                    String trace = expect("trace", get(operatorBase, "/v1/operator/reconciliation/breaks/" + candidate.group(1) + "/trace", session), 200).body();
                    if (trace.contains(fileId)) {
                        System.out.println("BREAK_ID=" + candidate.group(1));
                        return;
                    }
                }
                Matcher following = next.matcher(list);
                cursor = following.find() ? following.group(1) : null;
            } while (cursor != null);
            Thread.sleep(3000);
        }
        throw new IllegalStateException("no open FEE_MISMATCH traced to file " + fileId + " after three minutes");
    }

    // ---------------------------------------------------------------------------------------------- risk, through its doors (P17-TSK-110)

    /**
     * The seed's TRANSFER policy: one AMOUNT_BAND rule of weight 600 above 10,000,000.00 EUR, the score bands ALLOW to 499 and
     * REVIEW from 500, the fail-safe ALLOW, an override threshold of 1,000.00 EUR. Every movement a test stack makes is ALLOWed
     * under it, as under no policy; only an amount above the threshold - the plant's - is a REVIEW, which a second officer must
     * approve to override. The plant (sealed-assessment.sql) carries this version's verdict for that amount, so the rule, the
     * bands and the threshold change only with it (app's E2eSeedRiskDatabaseTest holds the two together).
     */
    static String testPolicy(String reason) {
        return "{\"operation\":\"TRANSFER\",\"engine\":\"RISK_ENGINE_V1\",\"currency\":\"EUR\","
                + "\"rules\":[{\"kind\":\"AMOUNT_BAND\",\"parameters\":{\"threshold_minor\":1000000000},\"weight\":600}],"
                + "\"scoreBands\":[{\"from\":0,\"to\":499,\"decision\":\"ALLOW\"},{\"from\":500,\"to\":1000,\"decision\":\"REVIEW\"}],"
                + "\"failSafeBands\":[{\"fromMinor\":0,\"decision\":\"ALLOW\"}],"
                + "\"challengeAssurance\":\"MULTI_FACTOR\",\"overrideThresholdMinor\":100000,\"reason\":\"" + reason + "\"}";
    }

    /** The seed's TRANSFER policy proposed by {@code who} (a RISK_OFFICER), {@code PROPOSED}. Prints its VERSION_ID. */
    private void proposePolicy(String who, String purpose) throws Exception {
        String reason = switch (purpose) {
            case "seed" -> "e2e seed: the version the seeded alerts and assessment are judged under";
            case "proposal" -> "e2e seed: a proposal the console's second officer activates";
            default -> throw new IllegalArgumentException("propose-policy seed or proposal, not " + purpose);
        };
        String session = elevated(who);
        Answer proposed = expect("policy proposal by " + who, post(operatorBase, "/v1/operator/risk/policies", testPolicy(reason),
                session, key()), 201);
        String id = field(proposed.body(), "id");
        if (id == null || !"PROPOSED".equals(field(proposed.body(), "status"))) {
            throw new IllegalStateException("the proposal did not answer a PROPOSED version: " + proposed.body());
        }
        System.out.println("VERSION_ID=" + id);
    }

    /** {@code versionId} activated by {@code who} - a second officer, never its proposer (four eyes). */
    private void activatePolicy(String who, String versionId) throws Exception {
        String session = elevated(who);
        Answer activated = expect("policy activation by " + who, post(operatorBase, "/v1/operator/risk/policies/" + versionId
                + "/activation", "{\"reason\":\"e2e seed: the seeded risk desks' version, reviewed by a second officer\"}", session,
                key()), 200);
        if (!"ACTIVE".equals(field(activated.body(), "status"))) {
            throw new IllegalStateException("the activation did not answer ACTIVE: " + activated.body());
        }
    }

    /** A case opened by hand on {@code alertId} by {@code who} (a RISK_ANALYST). Prints its CASE_ID. */
    private void openCase(String who, String alertId) throws Exception {
        String session = elevated(who);
        Answer opened = expect("case opening by " + who, post(operatorBase, "/v1/operator/risk/cases", "{\"alertId\":\"" + alertId
                + "\",\"reason\":\"e2e seed: a case for the console's four-eyes disposition\"}", session, key()), 200, 201);
        String caseId = field(opened.body(), "caseId");
        if (caseId == null) {
            throw new IllegalStateException("the opening answered no case: " + opened.body());
        }
        System.out.println("CASE_ID=" + caseId);
    }

    /** {@code caseId} taken by {@code who}: {@code IN_INVESTIGATION}, which the console's analyst takes over. */
    private void takeCase(String who, String caseId) throws Exception {
        String session = elevated(who);
        Answer taken = expect("the take of " + caseId + " by " + who, post(operatorBase, "/v1/operator/risk/cases/" + caseId
                + "/actions", "{\"act\":\"TAKE\",\"revision\":0}", session, key()), 200);
        if (!"IN_INVESTIGATION".equals(field(taken.body(), "status"))) {
            throw new IllegalStateException("the take did not answer IN_INVESTIGATION: " + taken.body());
        }
    }

    private static String letters(int count) {
        StringBuilder out = new StringBuilder(count);
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < count; i++) {
            out.append((char) ('A' + random.nextInt(26)));
        }
        return out.toString();
    }

    private static String digits(int count) {
        StringBuilder out = new StringBuilder(count);
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < count; i++) {
            out.append((char) ('0' + random.nextInt(10)));
        }
        return out.toString();
    }

    private String signIn(String base, String who) throws Exception {
        Answer answer = expect("sign-in of " + who, post(base, "/v1/authentications",
                "{\"loginIdentifier\":\"" + people.getProperty(who + ".login") + "\",\"password\":\"" + people.getProperty(who + ".password") + "\"}",
                null, null), 201);
        return field(answer.body(), "sessionToken");
    }

    /** A fresh stepped-up operator session. A code is consumed once per step, so a new step-up waits for a step the last did not use. */
    private String elevated(String who) throws Exception {
        String session = signIn(operatorBase, who);
        long last = Long.parseLong(people.getProperty(who + ".step", "0"));
        long step = currentStep();
        while (step <= last) {
            Thread.sleep(1000);
            step = currentStep();
        }
        Answer stepped = expect("step-up of " + who, post(operatorBase, "/v1/authentications/mfa",
                "{\"code\":\"" + totp(people.getProperty(who + ".totp"), step) + "\"}", session, null), 200);
        people.setProperty(who + ".step", Long.toString(step));
        save();
        return field(stepped.body(), "sessionToken");
    }

    // ---------------------------------------------------------------------------------------------- the factor

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
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 0xf;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
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

    // ---------------------------------------------------------------------------------------------- HTTP

    private record Answer(int status, String body) {}

    private Answer call(String method, String url, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/problem+json");
        headers.forEach(request::header);
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.body());
    }

    private Answer post(String base, String path, String body, String bearer, String key) throws IOException, InterruptedException {
        Map<String, String> headers = new HashMap<>();
        if (bearer != null) {
            headers.put("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            headers.put("Idempotency-Key", key);
        }
        return call("POST", base + path, body, headers);
    }

    private Answer get(String base, String path, String bearer) throws IOException, InterruptedException {
        return call("GET", base + path, null, Map.of("Authorization", "Bearer " + bearer));
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

    /** A refused step stops the seed with the platform's own answer: its status and problem code, never a guess. */
    private static Answer expect(String what, Answer answer, int... wanted) {
        for (int status : wanted) {
            if (answer.status() == status) {
                return answer;
            }
        }
        String body = answer.body() == null ? "" : answer.body();
        throw new IllegalStateException(what + " answered " + answer.status() + ": " + body.substring(0, Math.min(body.length(), 400)));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private void save() throws IOException {
        try (var out = Files.newOutputStream(state.resolve("people.properties"))) {
            people.store(out, "this run's test identities; removed with the run");
        }
    }

    private static SSLContext trusting(Path certificate) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("edge-ca", CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certificate))));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
