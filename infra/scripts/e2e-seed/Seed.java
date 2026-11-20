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
 * P17-TSK-113, P17-TSK-115, P17-TSK-117, P17-TSK-119): every
 * effect the platform has a door for, through that door - registration, a second factor, a step-up, a role grant, a risk policy
 * proposed and activated, a case opened and taken; and the books' finance acts - a GL mapping version, a manual adjustment, a month's
 * nil reports, a month locked and closed by four eyes, a prior-period adjustment, a regulatory obligation and its validated return. And
 * the merchant desks' - a merchant onboarded and keyed, a card top-up, and the card PSP's own word that it is disputed (P17-TSK-115).
 * And the credit desks' - the reference scorecard approved, a credit policy, lending terms and BNPL terms proposed and decided by four
 * eyes, and a customer's stepped-up credit request (P17-TSK-117). And the FX controller's - a pricing policy and a corridor policy
 * proposed and decided by four eyes, a pair and a corridor disabled and their enabling proposed, and a customer's conversion
 * (P17-TSK-119). And a customer's consent to KYC processing, which every case's customer has given (P17-TSK-113). A single source
 * file run by {@code infra/scripts/e2e-seed.sh}
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
            case "approve-scorecard" -> seed.approveScorecard(rest[0], rest[1]);
            case "propose-version" -> seed.proposeVersion(rest[0], rest[1], rest[2]);
            case "decide-version" -> seed.decideVersion(rest[0], rest[1], rest[2], rest[3]);
            case "credit-request" -> seed.creditRequest(one(rest));
            case "disable" -> seed.disable(rest[0], rest[1], rest[2]);
            case "propose-enable" -> seed.proposeEnable(rest[0], rest[1], rest[2]);
            case "decide-enable" -> seed.decideEnable(rest[0], rest[1], rest[2], rest[3]);
            case "fx-trade" -> seed.fxTrade(one(rest));
            case "kyc-consent" -> seed.kycConsent(one(rest));
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

    // ---------------------------------------------------------------------------------------------- credit, through its doors (P17-TSK-117)

    /** The amount a seeded customer asks for: inside PERSONAL_LOAN's bounds, below its four-eyes threshold. */
    static final String CREDIT_AMOUNT = "5000.00";

    /**
     * The seed's PERSONAL_LOAN policy (P17-TSK-117). It reads no source: no test stack has a bureau or a financial-data provider -
     * CreditBeans refuses either's URL until the platform holds what a pull needs (unresolved questions #13 and #14) - so every
     * bureau attribute is ABSENT, and its first rule REFERs every request to an underwriter with the code of absent data,
     * CRD-SOURCE-UNAVAILABLE. Its exposure rule, which every policy must have (credit.PolicyIncomplete otherwise), reads the bureau's
     * balance and so is never assessed, which leaves the REFER standing. Under it an underwriter can only decline: a person's
     * approval needs the exposure too (credit.ExposureUnassessable). The decline is the console's act on the seeded case.
     */
    static String referringPolicy(String reason) {
        return "{\"product\":\"PERSONAL_LOAN\",\"currency\":\"EUR\",\"assessmentRateBps\":950,\"minimumDisposableMinor\":10000,"
                + "\"minimumPaymentRatioBps\":300,\"maximumExposureMinor\":4000000,\"sources\":[],\"unavailableFallback\":\"REFER\","
                + "\"autoApprovalCeilingMinor\":1000000,\"rules\":["
                + "{\"ruleCode\":\"NO_BUREAU_REFER\",\"subjectKind\":\"ATTRIBUTE\",\"subject\":\"BUREAU_EXTERNAL_SCORE\","
                + "\"operator\":\"IS_ABSENT\",\"effect\":\"REFER\",\"reasonCode\":\"CRD-SOURCE-UNAVAILABLE\"},"
                + "{\"ruleCode\":\"EXPOSURE_LIMIT\",\"subjectKind\":\"FIGURE\",\"subject\":\"EXPOSURE_HEADROOM\",\"operator\":\"LT\","
                + "\"moneyOperandMinor\":0,\"effect\":\"DECLINE\",\"reasonCode\":\"CRD-EXPOSURE-LIMIT\"}],"
                + "\"reason\":\"" + reason + "\"}";
    }

    /** The seed's PERSONAL_LOAN terms: a fixed 9.90% plan, its late fee and its servicing - lending's own test terms' figures. */
    static String lendingTerms(String reason) {
        return "{\"product\":\"PERSONAL_LOAN\",\"currency\":\"EUR\",\"nominalAnnualRate\":\"0.0990\",\"rateType\":\"FIXED\","
                + "\"originationFeeMinor\":0,\"originationFeeTreatment\":\"DEDUCTED\",\"lateFeeMinor\":1500,\"lateFeeDay\":5,"
                + "\"lateFeeCapMinor\":6000,\"graceDays\":3,\"delinquencyBounds\":[1,30,60,90],\"defaultThresholdDays\":90,"
                + "\"allocationSequence\":\"OLDEST_DUE_FIRST\",\"allocationOrder\":[\"FEES\",\"INTEREST\",\"PRINCIPAL\"],"
                + "\"autoCollection\":true,\"partialCollection\":true,\"instalmentRounding\":\"UP\",\"interestRounding\":\"HALF_EVEN\","
                + "\"dayCount\":\"ACT_365F\",\"servicingZone\":\"UTC\",\"allowedDays\":[1,15,28],\"offerValidityDays\":14,"
                + "\"payoffHorizonDays\":30,\"accrualEngineVersion\":1,\"allocationEngineVersion\":1,"
                + "\"overpaymentTreatment\":\"HOLD_AS_CREDIT\",\"minFirstPeriodDays\":15,\"disbursementDeadlineHours\":24,"
                + "\"scheduleEngineVersion\":1,\"agreementTemplateId\":\"PERSONAL_LOAN_AGREEMENT\",\"agreementTemplateVersion\":1,"
                + "\"reason\":\"" + reason + "\"}";
    }

    /** The seed's BNPL_PAY_IN_4 terms: four interest-free fortnightly instalments - BNPL's own test terms' figures. */
    static String bnplTerms(String reason) {
        return "{\"product\":\"BNPL_PAY_IN_4\",\"currency\":\"EUR\",\"lateFeeMinor\":500,\"lateFeeDay\":5,\"lateFeeCapMinor\":1500,"
                + "\"graceDays\":3,\"delinquencyBounds\":[1,30,60,90],\"defaultThresholdDays\":90,"
                + "\"allocationSequence\":\"OLDEST_DUE_FIRST\",\"allocationOrder\":[\"FEES\",\"INTEREST\",\"PRINCIPAL\"],"
                + "\"overpaymentTreatment\":\"HOLD_AS_CREDIT\",\"autoCollection\":true,\"partialCollection\":true,"
                + "\"collectionRetryDays\":1,\"servicingZone\":\"UTC\",\"offerValidityMinutes\":30,\"disputeResponseDays\":14,"
                + "\"disputeFourEyesThresholdMinor\":25000,\"payoffHorizonDays\":30,\"allocationEngine\":\"ALLOCATION_ENGINE_V1\","
                + "\"refundAllocationEngine\":\"REFUND_ALLOCATION_ENGINE_V1\",\"nominalAnnualRate\":\"0\",\"frequency\":\"BIWEEKLY\","
                + "\"instalmentRounding\":\"DOWN\",\"instalmentCount\":4,\"intervalDays\":14,"
                + "\"scheduleEngine\":\"BNPL_SCHEDULE_ENGINE_V1\",\"agreementTemplateId\":\"BNPL_PAY_IN_4_AGREEMENT\","
                + "\"agreementTemplateVersion\":1,\"reason\":\"" + reason + "\"}";
    }

    /** The five postable currencies, as the ledger supports them (SupportedCurrencies). */
    private static final java.util.List<String> CURRENCIES = java.util.List.of("EUR", "GBP", "USD", "JPY", "BHD");

    /**
     * The FX pricing policy's version 1 as OPERATIONS_RUNBOOK section 2 has two FX controllers propose and approve it (P9-TSK-007,
     * the phase plan's O7; no migration seeds it, D26), so a stack's pricing never narrows when the console approves the seed's
     * proposal: every directional pair of the five currencies under both purposes, 40 rows, on fx-sim-a; spread 0.003500 and markup
     * 0.001500; rate scale 10 from JPY and 6 otherwise, the rate towards zero and amounts and margin half-even; a 30 s window for a
     * conversion and 60 s across a border, a 10 s cover margin; a 150 bps band among EUR, GBP and USD and 300 bps with JPY or BHD; the
     * reference at most 120 s old; five open quotes; notional bounds EUR, GBP and USD 1.00-50,000.00, JPY 100-7,500,000 and BHD
     * 0.500-20,000.000. Amounts and fractions are decimal strings, never JSON numbers.
     */
    static String fxPricingPolicy(String reason) {
        java.util.List<String> pairs = new java.util.ArrayList<>();
        for (String purpose : java.util.List.of("CONVERSION", "CROSS_BORDER")) {
            for (String source : CURRENCIES) {
                for (String destination : CURRENCIES) {
                    if (source.equals(destination)) {
                        continue;
                    }
                    boolean tight = !source.equals("JPY") && !source.equals("BHD") && !destination.equals("JPY")
                            && !destination.equals("BHD");
                    pairs.add("{\"source\":\"" + source + "\",\"destination\":\"" + destination + "\",\"purpose\":\"" + purpose
                            + "\",\"providers\":[\"fx-sim-a\"],\"spread\":\"0.003500\",\"markup\":\"0.001500\",\"rateScale\":"
                            + (source.equals("JPY") ? 10 : 6) + ",\"rateRounding\":\"TOWARDS_ZERO\",\"amountRounding\":\"HALF_EVEN\","
                            + "\"marginRounding\":\"HALF_EVEN\",\"windowSeconds\":" + (purpose.equals("CONVERSION") ? 30 : 60)
                            + ",\"coverMarginSeconds\":10,\"band\":\"" + (tight ? "0.015" : "0.03") + "\",\"referenceMaxAgeSeconds\":120,"
                            + "\"sourceMinimum\":\"" + minimum(source) + "\",\"sourceMaximum\":\"" + maximum(source)
                            + "\",\"destinationMinimum\":\"" + minimum(destination) + "\",\"destinationMaximum\":\""
                            + maximum(destination) + "\"}");
                }
            }
        }
        return "{\"pairs\":[" + String.join(",", pairs) + "],\"openQuoteCap\":5,\"reason\":\"" + reason + "\"}";
    }

    private static String minimum(String currency) {
        return switch (currency) {
            case "JPY" -> "100";
            case "BHD" -> "0.500";
            default -> "1.00";
        };
    }

    private static String maximum(String currency) {
        return switch (currency) {
            case "JPY" -> "7500000";
            case "BHD" -> "20000.000";
            default -> "50000.00";
        };
    }

    /**
     * The corridor policy's version 1 as the runbook has two controllers propose and activate it (P9-TSK-015, ADR-0080 section 4, O7;
     * never a seed, D26): EUR to USD in the US, EUR to JPY in Japan, USD to BHD in Bahrain and GBP to USD in the US, on corridor-sim-a;
     * fees EUR 2.50, GBP 2.00 and USD 3.00 and no margin, half-even; maxima USD 10,000.00, JPY 1,500,000 and BHD 4,000.000; screening
     * valid 168 hours; delivery estimated at 24; the beneficiary's name and entity type required.
     */
    static String corridorPolicy(String reason) {
        String[][] rows = {
            {"EUR", "USD", "US", "2.50", "10000.00"},
            {"EUR", "JPY", "JP", "2.50", "1500000"},
            {"USD", "BHD", "BH", "3.00", "4000.000"},
            {"GBP", "USD", "US", "2.00", "10000.00"}};
        java.util.List<String> corridors = new java.util.ArrayList<>();
        for (String[] row : rows) {
            corridors.add("{\"source\":\"" + row[0] + "\",\"destination\":\"" + row[1] + "\",\"country\":\"" + row[2]
                    + "\",\"rails\":[\"corridor-sim-a\"],\"feeFixed\":\"" + row[3] + "\",\"feeMargin\":\"0\","
                    + "\"feeRounding\":\"HALF_EVEN\",\"maximum\":\"" + row[4] + "\",\"screeningValidityHours\":168,"
                    + "\"deliveryEstimateHours\":24,\"requiredData\":[\"BENEFICIARY_NAME\",\"ENTITY_TYPE\"]}");
        }
        return "{\"corridors\":[" + String.join(",", corridors) + "],\"reason\":\"" + reason + "\"}";
    }

    /** Where a kind of availability is set: an FX pair ({@code fx-pair}, BASE-QUOTE) or a corridor ({@code corridor}, S-D-CC). */
    static String availabilityPath(String kind, String subject) {
        return switch (kind) {
            case "fx-pair" -> "/v1/operator/fx/pairs/" + subject + "/availability";
            case "corridor" -> "/v1/operator/cross-border/corridors/" + subject + "/availability";
            default -> throw new IllegalArgumentException("an availability is fx-pair or corridor, not " + kind);
        };
    }

    /** Where a kind's enable requests are decided under their id. */
    static String enableRequestsPath(String kind) {
        return switch (kind) {
            case "fx-pair" -> "/v1/operator/fx/enable-requests";
            case "corridor" -> "/v1/operator/cross-border/corridor-enable-requests";
            default -> throw new IllegalArgumentException("an availability is fx-pair or corridor, not " + kind);
        };
    }

    /** Where a kind of version is proposed, and decided under its id. */
    static String versionsPath(String kind) {
        return switch (kind) {
            case "credit-policy" -> "/v1/operator/credit/policies";
            case "lending-terms" -> "/v1/operator/lending/terms";
            case "bnpl-terms" -> "/v1/operator/bnpl/terms";
            case "fx-pricing" -> "/v1/operator/fx/pricing-policies";
            case "corridor-policy" -> "/v1/operator/cross-border/corridor-policies";
            default -> throw new IllegalArgumentException(
                    "a version is credit-policy, lending-terms, bnpl-terms, fx-pricing or corridor-policy, not " + kind);
        };
    }

    /**
     * The reference scorecard {@code versionId} approved by {@code who} (a CREDIT_POLICY_OFFICER): the platform's own RETAIL_SCORECARD
     * v1, proposed by its migration, so the approver is never its proposer. A request is scored only under an ACTIVE scorecard.
     */
    private void approveScorecard(String who, String versionId) throws Exception {
        String session = elevated(who);
        Answer approved = expect("scorecard approval by " + who, post(operatorBase, "/v1/operator/credit/scorecards/" + versionId
                + "/approval", "{\"reason\":\"e2e seed: the platform's reference scorecard, so a request can be scored\"}", session,
                key()), 200);
        if (!"ACTIVE".equals(field(approved.body(), "status"))) {
            throw new IllegalStateException("the scorecard's approval did not answer ACTIVE: " + approved.body());
        }
    }

    /**
     * A {@code kind} version - the seed's own content - proposed by {@code who}, {@code PROPOSED}: the seed's to activate
     * ({@code seed}) or the console's ({@code proposal}). Prints its VERSION_ID.
     */
    private void proposeVersion(String who, String kind, String purpose) throws Exception {
        String reason = switch (purpose) {
            case "seed" -> switch (kind) {
                case "fx-pricing" -> "e2e seed: the version in force, under which the seeded conversion is priced";
                case "corridor-policy" -> "e2e seed: the version in force, which the console's approval of the proposal retires";
                default -> "e2e seed: the version the seeded review case is judged under";
            };
            case "proposal" -> "e2e seed: a proposal the console's second officer approves";
            default -> throw new IllegalArgumentException("propose-version seed or proposal, not " + purpose);
        };
        String body = switch (kind) {
            case "credit-policy" -> referringPolicy(reason);
            case "lending-terms" -> lendingTerms(reason);
            case "bnpl-terms" -> bnplTerms(reason);
            case "fx-pricing" -> fxPricingPolicy(reason);
            case "corridor-policy" -> corridorPolicy(reason);
            default -> throw new IllegalArgumentException(
                    "a version is credit-policy, lending-terms, bnpl-terms, fx-pricing or corridor-policy, not " + kind);
        };
        String session = elevated(who);
        Answer proposed = expect(kind + " proposal by " + who, post(operatorBase, versionsPath(kind), body, session, key()), 201);
        String id = field(proposed.body(), "id");
        if (id == null || !"PROPOSED".equals(field(proposed.body(), "status"))) {
            throw new IllegalStateException("the " + kind + " proposal did not answer a PROPOSED version: " + proposed.body());
        }
        System.out.println("VERSION_ID=" + id);
    }

    /**
     * {@code versionId} decided by {@code who}: {@code approval} - a second officer, never its proposer (four eyes) - or
     * {@code rejection}, which anyone holding the desk's permission may make, the seed's withdrawal of a version left pending.
     */
    private void decideVersion(String who, String kind, String versionId, String decision) throws Exception {
        String expected = switch (decision) {
            case "approval" -> "ACTIVE";
            case "rejection" -> "REJECTED";
            default -> throw new IllegalArgumentException("decide-version approval or rejection, not " + decision);
        };
        String reason = "approval".equals(decision) ? "e2e seed: the seed's version, reviewed by a second officer"
                : "e2e seed: a pending version withdrawn, so the run's own may be proposed (one PROPOSED at a time)";
        String session = elevated(who);
        Answer decided = expect(kind + " " + decision + " by " + who, post(operatorBase, versionsPath(kind) + "/" + versionId + "/"
                + decision, "{\"reason\":\"" + reason + "\"}", session, key()), 200);
        if (!expected.equals(field(decided.body(), "status"))) {
            throw new IllegalStateException("the " + decision + " did not answer " + expected + ": " + decided.body());
        }
    }

    /**
     * A credit request for PERSONAL_LOAN by {@code who}, a customer the shell made ACTIVE: submitted on a stepped-up session on the
     * public host, as applying for credit always is (a MULTI_FACTOR act), and keyed. The platform's own progress sweep evaluates it
     * under the seed's policy and opens its review case. Prints its CREDIT_REQUEST_ID.
     */
    private void creditRequest(String who) throws Exception {
        String session = elevatedOn(publicBase, who);
        Answer submitted = expect("credit request of " + who, post(publicBase, "/v1/me/credit/decision-requests",
                "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + CREDIT_AMOUNT + "\",\"currency\":\"EUR\",\"termMonths\":24}", session,
                key()), 202);
        String id = field(submitted.body(), "requestId");
        if (id == null) {
            throw new IllegalStateException("the credit request answered no requestId: " + submitted.body());
        }
        System.out.println("CREDIT_REQUEST_ID=" + id);
    }

    /**
     * {@code subject} disabled by {@code who} (an FX_CONTROLLER) - one person's act, at once, and keyed. A subject with no fact is
     * available, and only an unavailable one can have its enabling proposed. Prints its OUTCOME: DISABLED, or UNCHANGED when it
     * already was (a run before left it so).
     */
    private void disable(String who, String kind, String subject) throws Exception {
        String session = elevated(who);
        Answer answered = expect(kind + " " + subject + " disabled by " + who, post(operatorBase, availabilityPath(kind, subject),
                "{\"available\":false,\"reason\":\"e2e seed: disabled, so its enabling can be proposed for the console to approve\"}",
                session, key()), 200);
        String outcome = field(answered.body(), "outcome");
        if (!"DISABLED".equals(outcome) && !"UNCHANGED".equals(outcome)) {
            throw new IllegalStateException("the disabling did not answer DISABLED or UNCHANGED: " + answered.body());
        }
        System.out.println("OUTCOME=" + outcome);
    }

    /**
     * {@code subject}'s enabling proposed by {@code who} - nothing is enabled until a second controller approves (four eyes). Keyed.
     * Prints its REQUEST_ID.
     */
    private void proposeEnable(String who, String kind, String subject) throws Exception {
        String session = elevated(who);
        Answer answered = expect(kind + " " + subject + " enabling proposed by " + who, post(operatorBase,
                availabilityPath(kind, subject), "{\"available\":true,\"reason\":\"e2e seed: enabling proposed, the console's second"
                        + " controller's to approve\"}", session, key()), 200);
        String request = field(answered.body(), "requestId");
        if (!"ENABLE_PROPOSED".equals(field(answered.body(), "outcome")) || request == null) {
            throw new IllegalStateException("the enabling was not proposed: " + answered.body());
        }
        System.out.println("REQUEST_ID=" + request);
    }

    /**
     * The enable request {@code requestId} decided by {@code who}: {@code approval} - a second controller, never its proposer - or
     * {@code rejection}, which any controller may make: the seed's withdrawal of a request a run before left pending (one per subject).
     */
    private void decideEnable(String who, String kind, String requestId, String decision) throws Exception {
        String expected = switch (decision) {
            case "approval" -> "APPROVED";
            case "rejection" -> "REJECTED";
            default -> throw new IllegalArgumentException("decide-enable approval or rejection, not " + decision);
        };
        String reason = "approval".equals(decision) ? "e2e seed: the enabling, reviewed by a second controller"
                : "e2e seed: a pending enable request withdrawn, so the run's own may be proposed (one per subject)";
        String session = elevated(who);
        Answer decided = expect(kind + " enable request " + decision + " by " + who, post(operatorBase, enableRequestsPath(kind) + "/"
                + requestId + "/" + decision, "{\"reason\":\"" + reason + "\"}", session, null), 200);
        if (!expected.equals(field(decided.body(), "status"))) {
            throw new IllegalStateException("the " + decision + " did not answer " + expected + ": " + decided.body());
        }
    }

    /** The seeded conversion: 10.00 EUR, inside the 12.50 EUR top-up and above version 1's 1.00 EUR minimum. */
    static final String FX_AMOUNT = "10.00";

    /**
     * A conversion of 10.00 EUR to USD by {@code who}, a customer the shell made ACTIVE and funded: a quote fixed on its source side,
     * priced under the ACTIVE pricing policy from the stack's own reference rate and FX provider, then accepted - synchronous and
     * final, the trade BOOKED with its entry. Both keyed. The rate is the provider's, never the seed's: neither body has a rate
     * field. Prints its FX_TRADE_ID.
     */
    private void fxTrade(String who) throws Exception {
        String session = signIn(publicBase, who);
        Answer quoted = expect("FX quote for " + who, post(publicBase, "/v1/me/fx/quotes", "{\"sourceCurrency\":\"EUR\","
                + "\"destinationCurrency\":\"USD\",\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"" + FX_AMOUNT + "\"}", session,
                key()), 201);
        String quote = field(quoted.body(), "id");
        if (quote == null) {
            throw new IllegalStateException("the FX quote answered no id: " + quoted.body());
        }
        Answer converted = expect("FX conversion for " + who, post(publicBase, "/v1/me/fx/conversions",
                "{\"quoteId\":\"" + quote + "\"}", session, key()), 201);
        String trade = field(converted.body(), "id");
        if (trade == null || !"BOOKED".equals(field(converted.body(), "status"))) {
            throw new IllegalStateException("the conversion did not answer a BOOKED trade: " + converted.body());
        }
        System.out.println("FX_TRADE_ID=" + trade);
    }

    /**
     * The customer's consent to KYC processing, through the consent door, against the text's first version: the grant a case's
     * opening requires (KycCaseService), so the case the shell plants for this customer has the consent a real one has.
     */
    private void kycConsent(String who) throws Exception {
        String session = signIn(publicBase, who);
        Answer granted = expect("KYC consent of " + who, post(publicBase, "/v1/me/consents",
                "{\"purpose\":\"KYC_PROCESSING\",\"textVersion\":1}", session, null), 201);
        if (!"KYC_PROCESSING".equals(field(granted.body(), "purpose"))) {
            throw new IllegalStateException("the consent door answered another purpose: " + granted.body());
        }
        System.out.println("CONSENTED=KYC_PROCESSING");
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
        return elevatedOn(operatorBase, who);
    }

    /** A fresh stepped-up session on {@code base}: the operator host's, or the public host's for a customer's act (P17-TSK-117). */
    private String elevatedOn(String base, String who) throws Exception {
        String session = signIn(base, who);
        long last = Long.parseLong(people.getProperty(who + ".step", "0"));
        long step = currentStep();
        while (step <= last) {
            Thread.sleep(1000);
            step = currentStep();
        }
        Answer stepped = expect("step-up of " + who, post(base, "/v1/authentications/mfa",
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
