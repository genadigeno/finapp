import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
 * TEST-ONLY. The HTTP half of the Operator Console's end-to-end seed (P17-TSK-106, P17-TSK-108, P17-TSK-110, P17-TSK-112,
 * P17-TSK-115): every
 * effect the platform has a door for, through that door - registration, a second factor, a step-up, a role grant, a risk policy
 * proposed and activated, a case opened and taken; and the books' finance acts - a GL mapping version, a manual adjustment, a month's
 * nil reports, a month locked and closed by four eyes, a prior-period adjustment, a regulatory obligation and its validated return. And
 * the merchant desks' - a merchant onboarded and keyed, a card top-up, and the card PSP's own word that it is disputed (P17-TSK-115). A single source file run by {@code infra/scripts/e2e-seed.sh}
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
            case "books" -> seed.books(one(rest));
            case "propose-mapping" -> seed.proposeMapping(rest[0], rest[1], rest[2]);
            case "activate-mapping" -> seed.activateMapping(rest[0], rest[1]);
            case "adjust" -> seed.adjust(rest[0], rest[1], rest[2]);
            case "nil-reports" -> seed.nilReports(rest[0], rest[1], rest[2], rest[3], rest[4]);
            case "close-month" -> seed.closeMonth(rest[0], rest[1], rest[2]);
            case "lock-and-capture" -> seed.lockAndCaptureCommand(rest[0], rest[1]);
            case "propose-ppa" -> seed.proposePriorPeriodAdjustment(rest[0], rest[1], rest[2]);
            case "obligation" -> seed.obligation(rest[0], rest[1]);
            case "draft-submission" -> seed.draftSubmission(rest[0], rest[1], rest[2]);
            case "onboard-merchant" -> seed.onboardMerchant(rest[0], rest[1]);
            case "issue-key" -> seed.issueKey(rest[0], rest[1]);
            case "top-up" -> seed.topUp(one(rest));
            case "dispute-statement" -> seed.disputeStatement(rest[0], rest[1], rest[2]);
            case "dispute-hint" -> seed.disputeHint(rest[0], rest[1]);
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

    /** Finds the open FEE_MISMATCH this file raised, by each candidate's stored trace back to the raw file. */
    private void awaitBreak(String who, String fileId) throws Exception {
        String session = signIn(operatorBase, who);
        Pattern ids = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        while (Instant.now().isBefore(deadline)) {
            String list = expect("breaks", get(operatorBase, "/v1/operator/reconciliation/breaks?type=FEE_MISMATCH&status=OPEN", session), 200).body();
            Matcher candidate = ids.matcher(list);
            while (candidate.find()) {
                String trace = expect("trace", get(operatorBase, "/v1/operator/reconciliation/breaks/" + candidate.group(1) + "/trace", session), 200).body();
                if (trace.contains(fileId)) {
                    System.out.println("BREAK_ID=" + candidate.group(1));
                    return;
                }
            }
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

    // ---------------------------------------------------------------------------------------------- finance, through its doors (P17-TSK-112)

    /** The platform's own EUR fee-revenue account (ledger V003) - a purpose a manual adjustment may move. */
    private static final String FEE_REVENUE_EUR = "01a09b66-b58f-79be-b9db-835d27b3eb17";
    /** The platform's own EUR dispute-costs account (ledger V014) - the other side of the seed's adjustments. */
    private static final String DISPUTE_COSTS_EUR = "01a0e029-5400-7000-8000-000000000011";
    /** A period as the list serves it: the view's first members, in its record's order. */
    private static final Pattern PERIOD = Pattern.compile("\\{\"id\":\"([0-9a-f-]{36})\",\"period\":\"([0-9]{4}-[0-9]{2})\","
            + "\"startDate\":\"[^\"]*\",\"endDate\":\"[^\"]*\",\"status\":\"([A-Z_]+)\",\"revision\":([0-9]+)");
    /** A check of a close run as its read serves it: its code, its latest status and the latest attempt's number. */
    private static final Pattern CHECK = Pattern.compile("\\{\"check\":\"([A-Z_]+)\",\"attempts\":[0-9]+,\"status\":\"([A-Z_]+)\","
            + "\"history\":\\[\\{\"attempt\":([0-9]+)");

    /**
     * The books' months as the finance scenarios use them, read from the period list: the closing month - two before today's, which
     * the seed closes - and the closable one before today's, which it locks for the console. Each printed with its id, status and
     * revision when the books hold it; a month they do not hold prints nothing, and the shell refuses (ADR-0152: the books open
     * months back only on a fresh database).
     */
    private void books(String who) throws Exception {
        java.time.YearMonth today = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        String session = signIn(operatorBase, who);
        String list = expect("the period list", get(operatorBase, "/v1/operator/accounting/periods?limit=24", session), 200).body();
        Map<String, String[]> held = new HashMap<>();
        Matcher period = PERIOD.matcher(list);
        while (period.find()) {
            held.put(period.group(2), new String[] {period.group(1), period.group(3), period.group(4)});
        }
        String[][] months = {{"CLOSING", today.minusMonths(2).toString()}, {"CLOSABLE", today.minusMonths(1).toString()}};
        for (String[] month : months) {
            System.out.println(month[0] + "_MONTH=" + month[1]);
            // The day before the month's end at noon: inside a valuation set's 96 hours before the month's last instant.
            System.out.println(month[0] + "_RATES_AT=" + java.time.YearMonth.parse(month[1]).atEndOfMonth().minusDays(1) + "T12:00:00Z");
            String[] row = held.get(month[1]);
            if (row != null) {
                System.out.println(month[0] + "_ID=" + row[0]);
                System.out.println(month[0] + "_STATUS=" + row[1]);
                System.out.println(month[0] + "_REVISION=" + row[2]);
            }
        }
        System.out.println("CURRENT_MONTH=" + today);
        System.out.println("NEXT_MONTH=" + today.plusMonths(1));
    }

    /**
     * A GL mapping version proposed by {@code who} (an ACCOUNTANT): the chart at {@code chart} - the kind rehearsal's reference
     * mapping, copied beside this file - in force from {@code month}. Prints its MAPPING_VERSION_ID.
     */
    private void proposeMapping(String who, String month, String chart) throws Exception {
        String content = Files.readString(Path.of(chart)).strip();
        if (!content.startsWith("{")) {
            throw new IllegalStateException("the chart at " + chart + " is not a JSON object");
        }
        String body = "{\"effectiveFromPeriod\":\"" + month + "\",\"reason\":\"e2e seed: the reference chart in force from " + month
                + "\"," + content.substring(1);
        String session = elevated(who);
        Answer proposed = expect("mapping proposal by " + who, post(operatorBase, "/v1/operator/accounting/mapping-versions", body,
                session, key()), 201);
        String id = field(proposed.body(), "id");
        if (id == null || !"PROPOSED".equals(field(proposed.body(), "status"))) {
            throw new IllegalStateException("the proposal did not answer a PROPOSED version: " + proposed.body());
        }
        System.out.println("MAPPING_VERSION_ID=" + id);
    }

    /** {@code versionId} activated by {@code who} - a FINANCIAL_CONTROLLER, never its proposer (four eyes). */
    private void activateMapping(String who, String versionId) throws Exception {
        String session = elevated(who);
        Answer activated = expect("mapping activation by " + who, post(operatorBase, "/v1/operator/accounting/mapping-versions/"
                + versionId + "/activation", "{\"reason\":\"e2e seed: the books' chart, reviewed by a second person\"}", session,
                key()), 200);
        if (!"ACTIVE".equals(field(activated.body(), "status"))) {
            throw new IllegalStateException("the activation did not answer ACTIVE: " + activated.body());
        }
    }

    /**
     * A manual adjustment of 1.00 EUR dated {@code postingDate}, from the platform's dispute costs to its fee revenue: proposed by
     * {@code proposer}, approved by {@code approver} (two LEDGER_OPERATORs) - so the month holds a journal line, without which its
     * trial balance has no currency row and no return can be drawn from it. Prints its ENTRY_ID.
     */
    private void adjust(String proposer, String approver, String postingDate) throws Exception {
        String body = "{\"postingDate\":\"" + postingDate + "\",\"valueDate\":\"" + postingDate + "\",\"reference\":\"e2e-seed:"
                + letters(12).toLowerCase(Locale.ROOT) + "\",\"reason\":\"e2e seed: a line in the month its return is drawn from\","
                + "\"lines\":[{\"accountId\":\"" + DISPUTE_COSTS_EUR + "\",\"direction\":\"DEBIT\",\"amount\":\"1.00\",\"currency\":\"EUR\"},"
                + "{\"accountId\":\"" + FEE_REVENUE_EUR + "\",\"direction\":\"CREDIT\",\"amount\":\"1.00\",\"currency\":\"EUR\"}]}";
        Answer proposed = expect("adjustment proposal by " + proposer, post(operatorBase, "/v1/ledger/adjustments", body,
                elevated(proposer), key()), 201);
        String proposal = field(proposed.body(), "proposalId");
        Answer approved = expect("adjustment approval by " + approver, post(operatorBase, "/v1/ledger/adjustments/" + proposal
                + "/approval", null, elevated(approver), null), 201);
        System.out.println("ENTRY_ID=" + field(approved.body(), "entryId"));
    }

    /**
     * A month's nil reports from the four sources whose runs a close requires (reconciliation V002): uploaded by {@code submitter},
     * attested by {@code attester} (two RECONCILIATION_OPERATORs, INV-SET-07), each awaited to ACCEPTED - the acceptance opens the
     * run the matcher completes. Each in its source's own format, as the quiet month's proof writes them; the bank statement continues
     * the chain at {@code sequence} from {@code opening}, a nil one closing where it opened. Prints NIL_FILE_IDS.
     */
    private void nilReports(String submitter, String attester, String businessDate, String sequence, String opening) throws Exception {
        java.time.LocalDate day = java.time.LocalDate.parse(businessDate);
        String compact = day.toString().replace("-", "");
        String sign = opening.startsWith("-") ? "D" : "C";
        String balance = opening.startsWith("-") ? opening.substring(1) : opening;
        String[][] reports = {
            {"simulated-psp.settlement", "H,SIM_PSP_CSV,1,PSPB-" + day + "-01,EUR," + day + "\nT,0,0.00,PSP-REM-" + digits(8) + "\n"},
            {"simulated-payout.settlement", "H,SIM_PAYOUT_CSV,1,PAYDAY-" + day + "-EUR,EUR," + day + "\nT,0,0.00,PAY-REM-" + digits(8) + "\n"},
            {"simulated-scheme.cycle-report", "{\"format\":\"SIM_SCHEME_JSON\",\"version\":1,\"cycle\":\"" + day + "-C1\",\"currency\":\"EUR\","
                    + "\"businessDate\":\"" + day + "\",\"remittanceReference\":\"SCH-REM-" + digits(8) + "\",\"entries\":[],\"net\":\"0.00\","
                    + "\"entryCount\":0}"},
            {"simulated-bank.statement", ":20:SB-STMT-" + compact + "-EUR\n:25:SIMBANK-EUR-01\n:28C:" + sequence + "\n:60F:" + sign + ","
                    + day.minusDays(1) + ",EUR," + balance + "\n:62F:" + sign + "," + day + ",EUR," + balance + "\n"}};
        String uploading = elevated(submitter);
        java.util.List<String> files = new java.util.ArrayList<>();
        for (String[] report : reports) {
            String content = java.util.Base64.getEncoder().encodeToString(report[1].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            Answer received = expect("nil report of " + report[0], post(operatorBase, "/v1/operator/settlement/files",
                    "{\"sourceCode\":\"" + report[0] + "\",\"businessDate\":\"" + day + "\",\"content\":\"" + content + "\"}", uploading,
                    key()), 202);
            String fileId = field(received.body(), "fileId");
            if (fileId == null || field(received.body(), "duplicateOf") != null) {
                throw new IllegalStateException("the nil report of " + report[0] + " did not receive a fresh file: " + received.body());
            }
            files.add(fileId);
        }
        String attesting = elevated(attester);
        for (String file : files) {
            expect("attestation of " + file, post(operatorBase, "/v1/operator/settlement/files/" + file + "/attestation", null, attesting,
                    null), 200);
        }
        for (String file : files) {
            awaitFile(submitter, file, "ACCEPTED");
        }
        System.out.println("NIL_FILE_IDS=" + String.join(",", files));
    }

    /**
     * Closes {@code periodId} end to end, as the console would by four eyes: locked and its valuation rate set captured by
     * {@code accountant}, the set approved by {@code controller}; the close run - the schedule's - awaited to complete; the close
     * proposed by the accountant, every DISCLOSED check disclosed, and approved by the controller. Prints CLOSED_PERIOD_ID.
     */
    private void closeMonth(String accountant, String controller, String periodId) throws Exception {
        String set = lockAndCapture(accountant, periodId);
        expect("rate set approval by " + controller, post(operatorBase, "/v1/operator/accounting/valuation-rate-sets/" + set + "/approval",
                "{\"reason\":\"e2e seed: the month's reference rates, reviewed\"}", elevated(controller), key()), 200);
        String run = awaitCloseRun(accountant, periodId);
        String runId = field(run, "runId");
        java.util.List<String> disclosures = new java.util.ArrayList<>();
        Matcher check = CHECK.matcher(run);
        while (check.find()) {
            if ("DISCLOSED".equals(check.group(2))) {
                disclosures.add("{\"check\":\"" + check.group(1) + "\",\"attempt\":" + check.group(3)
                        + ",\"reason\":\"e2e seed: disclosed with the seeded month\"}");
            }
        }
        Answer proposed = expect("close proposal by " + accountant, post(operatorBase, "/v1/operator/accounting/periods/" + periodId
                + "/close-proposal", "{\"closeRunId\":\"" + runId + "\",\"reason\":\"e2e seed: the books' first month, closed\","
                + "\"disclosures\":[" + String.join(",", disclosures) + "]}", elevated(accountant), key()), 200, 201);
        String proposal = field(proposed.body(), "proposalId");
        Answer approved = expect("close approval by " + controller, post(operatorBase, "/v1/operator/accounting/periods/" + periodId
                + "/close-approval", "{\"proposalId\":\"" + proposal + "\",\"reason\":\"e2e seed: the close, reviewed\"}",
                elevated(controller), key()), 200);
        if (!"CLOSED".equals(field(approved.body(), "status"))) {
            throw new IllegalStateException("the close approval did not answer CLOSED: " + approved.body());
        }
        System.out.println("CLOSED_PERIOD_ID=" + periodId);
    }

    /** {@code periodId} locked and its valuation rate set captured by {@code accountant}, the set left PROPOSED. Prints RATE_SET_ID. */
    private void lockAndCaptureCommand(String accountant, String periodId) throws Exception {
        System.out.println("RATE_SET_ID=" + lockAndCapture(accountant, periodId));
    }

    private String lockAndCapture(String accountant, String periodId) throws Exception {
        String session = elevated(accountant);
        Answer locked = expect("lock by " + accountant, post(operatorBase, "/v1/operator/accounting/periods/" + periodId + "/lock",
                "{\"reason\":\"e2e seed: the month's books stop\"}", session, key()), 200, 201);
        if (!"LOCKED".equals(field(locked.body(), "status"))) {
            throw new IllegalStateException("the lock did not answer LOCKED: " + locked.body());
        }
        Answer captured = expect("rate set capture by " + accountant, post(operatorBase, "/v1/operator/accounting/valuation-rate-sets",
                "{\"periodId\":\"" + periodId + "\",\"reason\":\"e2e seed: the month-end reference rates\"}", session, key()), 201);
        String set = field(captured.body(), "id");
        if (set == null || !"PROPOSED".equals(field(captured.body(), "status"))) {
            throw new IllegalStateException("the capture did not answer a PROPOSED set: " + captured.body());
        }
        return set;
    }

    /** Waits for the schedule's close run of {@code periodId} to complete; a FAILED check stops the seed with the run's own read. */
    private String awaitCloseRun(String who, String periodId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
        String body = null;
        while (Instant.now().isBefore(deadline)) {
            Answer read = get(operatorBase, "/v1/operator/accounting/periods/" + periodId + "/close-run", signIn(operatorBase, who));
            if (read.status() == 200) {
                body = read.body();
                if (body.contains("\"complete\":true")) {
                    return body;
                }
                Matcher check = CHECK.matcher(body);
                while (check.find()) {
                    if ("FAILED".equals(check.group(2))) {
                        throw new IllegalStateException("the close run of " + periodId + " has " + check.group(1) + " FAILED: "
                                + body.substring(0, Math.min(body.length(), 1200)));
                    }
                }
            } else if (read.status() != 404) {
                expect("the close run of " + periodId, read, 200);
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("the close run of " + periodId + " did not complete in ten minutes: " + body);
    }

    /**
     * A prior-period adjustment of the CLOSED {@code periodId} at {@code revision}, proposed by {@code accountant}: 0.50 EUR from fee
     * revenue back to dispute costs, dated today in the OPEN month. Prints PPA_ID.
     */
    private void proposePriorPeriodAdjustment(String accountant, String periodId, String revision) throws Exception {
        String body = "{\"correctedPeriodId\":\"" + periodId + "\",\"correctedRevision\":" + Integer.parseInt(revision)
                + ",\"reason\":\"e2e seed: a fee booked in a closed month, given back\",\"lines\":["
                + "{\"ledgerAccountId\":\"" + FEE_REVENUE_EUR + "\",\"direction\":\"DEBIT\",\"amount\":\"0.50\",\"currency\":\"EUR\"},"
                + "{\"ledgerAccountId\":\"" + DISPUTE_COSTS_EUR + "\",\"direction\":\"CREDIT\",\"amount\":\"0.50\",\"currency\":\"EUR\"}]}";
        Answer proposed = expect("prior-period adjustment by " + accountant, post(operatorBase,
                "/v1/operator/accounting/prior-period-adjustments", body, elevated(accountant), key()), 200, 201);
        String id = field(proposed.body(), "id");
        if (id == null || !"PROPOSED".equals(field(proposed.body(), "status"))) {
            throw new IllegalStateException("the proposal did not answer a PROPOSED adjustment: " + proposed.body());
        }
        System.out.println("PPA_ID=" + id);
    }

    /**
     * A reference regulatory obligation on the trial balance, proposed by {@code reporter} (a REGULATORY_REPORTER) and activated by
     * {@code controller} (four eyes): its form code fresh on every run, so no two runs' forms meet. Prints OBLIGATION_ID.
     */
    private void obligation(String reporter, String controller) throws Exception {
        String form = "REF-E2E-" + letters(10);
        String body = "{\"jurisdictionCode\":\"REF\",\"formCode\":\"" + form + "\",\"formVersion\":1,\"adapterName\":\"simulated-reference\","
                + "\"sources\":[{\"definition\":\"TRIAL_BALANCE\",\"version\":1}],"
                + "\"lines\":[{\"code\":\"DEBITS\",\"label\":\"Closing debit balances\",\"source\":\"TRIAL_BALANCE\","
                + "\"figure\":\"body.currencies[currency=*].closingDebitBalances\"},"
                + "{\"code\":\"CREDITS\",\"label\":\"Closing credit balances\",\"source\":\"TRIAL_BALANCE\","
                + "\"figure\":\"body.currencies[currency=*].closingCreditBalances\"}],"
                + "\"rules\":[{\"code\":\"BALANCED\",\"type\":\"EQUALS\",\"left\":[\"DEBITS\"],\"right\":[\"CREDITS\"]}],"
                + "\"reason\":\"e2e seed: a reference return for the console's approval\"}";
        Answer proposed = expect("obligation proposal by " + reporter, post(operatorBase, "/v1/operator/regulatory/obligations", body,
                elevated(reporter), key()), 201);
        String id = field(proposed.body(), "obligationId");
        String hash = field(proposed.body(), "configurationSha256");
        expect("obligation activation by " + controller, post(operatorBase, "/v1/operator/regulatory/obligations/" + id + "/activation",
                "{\"configurationSha256\":\"" + hash + "\",\"reason\":\"e2e seed: the reference form, reviewed\"}", elevated(controller),
                key()), 200);
        System.out.println("OBLIGATION_ID=" + id);
    }

    /** A return of {@code obligationId} for the CLOSED {@code periodId}, drafted and validated by {@code reporter}. Prints SUBMISSION_ID. */
    private void draftSubmission(String reporter, String obligationId, String periodId) throws Exception {
        String session = elevated(reporter);
        Answer drafted = expect("draft by " + reporter, post(operatorBase, "/v1/operator/regulatory/submissions", "{\"obligationId\":\""
                + obligationId + "\",\"periodId\":\"" + periodId + "\",\"correctsSubmissionId\":null,\"reason\":\"e2e seed: the month's"
                + " return, for the console's approval\"}", session, key()), 201);
        String id = field(drafted.body(), "submissionId");
        Answer validated = expect("validation by " + reporter, post(operatorBase, "/v1/operator/regulatory/submissions/" + id
                + "/validation", null, session, key()), 200);
        if (!"VALIDATED".equals(field(validated.body(), "status"))) {
            throw new IllegalStateException("the validation did not answer VALIDATED: " + validated.body());
        }
        System.out.println("SUBMISSION_ID=" + id);
    }

    // ---------------------------------------------------------------------------------------------- merchants and disputes (P17-TSK-115)

    /** Onboards the organisation {@code partyId} names as a EUR merchant, as {@code who}, a MERCHANT_ADMINISTRATOR. Prints its id. */
    private void onboardMerchant(String who, String partyId) throws Exception {
        String name = "E2E Seed " + letters(8);
        Answer onboarded = expect("onboarding of " + partyId, post(operatorBase, "/v1/operator/merchants",
                "{\"partyId\":\"" + partyId + "\",\"legalName\":\"" + name + " GmbH\",\"displayName\":\"" + name
                        + "\",\"settlementCurrency\":\"EUR\"}", elevated(who), key()), 201);
        System.out.println("MERCHANT_ID=" + field(onboarded.body(), "merchantId"));
    }

    /**
     * Issues the merchant an API key, as {@code who}. Prints the key's id only: the secret is answered once, and nothing here
     * needs it - the console sees the key in the merchant's list, and revokes it there - so it is dropped unread.
     */
    private void issueKey(String who, String merchantId) throws Exception {
        Answer issued = expect("API key of " + merchantId, post(operatorBase, "/v1/operator/merchants/" + merchantId + "/api-keys",
                null, elevated(who), key()), 201);
        System.out.println("MERCHANT_KEY_ID=" + field(issued.body(), "keyId"));
    }

    /** The top-up's amount: 12.50 EUR, the dispute statement's 1250 at scale 2. */
    private static final String TOP_UP = "12.50";

    /**
     * A card top-up of 12.50 EUR into {@code who}'s own wallet, authorized and captured by the card PSP - a payment with no
     * merchant, the kind an operator answers a dispute on (a merchant answers its own). {@code who} is a customer the shell made
     * ACTIVE: the wallet opens through the accounts door, the card attaches through tokenisation. Prints the payment's id.
     */
    private void topUp(String who) throws Exception {
        String session = signIn(publicBase, who);
        expect("wallet of " + who, post(publicBase, "/v1/me/accounts", "{\"productType\":\"WALLET\",\"currency\":\"EUR\"}", session,
                key()), 201);
        String card = field(expect("card of " + who, post(publicBase, "/v1/me/payment-methods",
                "{\"clientToken\":\"ctok_" + letters(12) + "\"}", session, null), 201).body(), "id");
        String payment = field(expect("top-up of " + who, post(publicBase, "/v1/payments",
                "{\"amount\":\"" + TOP_UP + "\",\"currency\":\"EUR\",\"paymentMethodId\":\"" + card + "\"}", session, key()), 200, 201)
                .body(), "id");
        expect("top-up confirmation", post(publicBase, "/v1/payments/" + payment + "/confirmation", null, session, null), 200, 409);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        String status = field(expect("top-up read", get(publicBase, "/v1/payments/" + payment, session), 200).body(), "status");
        while (!"SUCCEEDED".equals(status) && !"FAILED".equals(status) && Instant.now().isBefore(deadline)) {
            Thread.sleep(1000);
            status = field(expect("top-up read", get(publicBase, "/v1/payments/" + payment, session), 200).body(), "status");
        }
        if (!"SUCCEEDED".equals(status)) {
            throw new IllegalStateException("the top-up " + payment + " ended " + status);
        }
        System.out.println("PAYMENT_ID=" + payment);
    }

    /**
     * The card PSP's statement of a chargeback on the capture {@code operation} names, written to the run's state as
     * {@code dispute-<dispute>.json} for the shell to hand the provider (kind's simulator answers the platform's inquiry with it):
     * the network's stage {@code needs_response} (the platform's CHARGED_BACK, the stage that takes evidence and a representment),
     * the whole {@code amountMinor} at EUR's scale, and the network's deadline a fortnight out - the card adapter's own words
     * (CardDisputeStatement). Prints nothing.
     */
    private void disputeStatement(String operation, String dispute, String amountMinor) throws IOException {
        String respondBy = Instant.now().plus(Duration.ofDays(14)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        Files.writeString(state.resolve("dispute-" + dispute + ".json"), "{\"status\":\"disputed\",\"operation\":\"" + operation
                + "\",\"dispute\":\"" + dispute + "\",\"stage\":\"needs_response\",\"reasonCode\":\"fraudulent\",\"amountMinor\":\""
                + amountMinor + "\",\"currency\":\"EUR\",\"scale\":2,\"respondBy\":\"" + respondBy + "\"}");
    }

    /**
     * The card PSP's webhook that a dispute exists, signed as the provider signs: HMAC-SHA256 over {@code timestamp + "." + body}
     * under the payment webhook key, which the shell read from the stack into the run's state ({@code provider-key}, the setting's
     * base64 text, as the kind rehearsal's load client reads it). The hint names our capture and the network's dispute; the platform
     * then asks the PSP for its statement, and that answer alone opens the dispute and posts the chargeback. Prints nothing.
     */
    private void disputeHint(String operation, String dispute) throws Exception {
        byte[] signing = java.util.Base64.getDecoder().decode(Files.readString(state.resolve("provider-key")).strip());
        String body = "{\"eventId\":\"evt_e2e_" + letters(12) + "\",\"operation\":\"" + operation + "\",\"status\":\"disputed\","
                + "\"dispute\":\"" + dispute + "\"}";
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signing, "HmacSHA256"));
        String signature = java.util.HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        expect("dispute hint " + dispute, call("POST", publicBase + "/v1/providers/payments/webhooks", body,
                Map.of("X-Provider-Timestamp", timestamp, "X-Provider-Signature", signature)), 204);
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
