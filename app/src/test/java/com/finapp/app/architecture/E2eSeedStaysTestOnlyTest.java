package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.KycPolicyVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Operator Console's end-to-end seed (`P17-TSK-106`) is a test-only path, and stays one: it lives under {@code infra/scripts/}, no
 * image carries it, nothing deployed runs it, and it refuses any stack but a test one.
 *
 * <p>The images are built from nothing else by construction: the application image's build context is the boot jar alone
 * ({@code .dockerignore}, held by {@link ApplicationImageTest}), and the migrator image's is every module's
 * {@code src/main/resources/db/migration} ({@code migrator/build.gradle.kts}). So "ships nowhere" is "named by no {@code src/main}
 * file", which this holds by the seed's own markers. What the plants do is held on a real PostgreSQL - the dead letters by
 * {@code E2eSeedPlantsDatabaseTest}, the held role (`P17-TSK-108`) by {@code E2eSeedReviewsDatabaseTest}, the alert and the sealed
 * assessment (`P17-TSK-110`) by {@code E2eSeedRiskDatabaseTest}, the finance plants (`P17-TSK-112`) by {@code E2eSeedFinanceDatabaseTest},
 * the merchant plants and the dispute's statement (`P17-TSK-115`) by {@code E2eSeedMerchantDatabaseTest}, the credit desks' doors and
 * the review case (`P17-TSK-117`) by {@code E2eSeedCreditDatabaseTest}, the FX desks' doors and the booked trade (`P17-TSK-119`) by
 * {@code E2eSeedFxDatabaseTest}, the KYC review states (`P17-TSK-113`) by {@code E2eSeedKycDatabaseTest}; the script's run against a
 * running stack is the console's end-to-end run.
 */
@Tag("architecture")
@DisplayName("the end-to-end seed is test-only and stays so (P17-TSK-106)")
class E2eSeedStaysTestOnlyTest {

    private static final String SCRIPT = "infra/scripts/e2e-seed.sh";
    private static final String SEED = "infra/scripts/e2e-seed/";

    /** What only the seed says: its script's name, its event type and aggregate type, its consumer and group, its plant's failure. */
    private static final Pattern MARKERS =
            Pattern.compile("e2e-seed|e2e\\.SeededEvent|e2e\\.Seed\\b|e2e\\.seed-consumer|finapp\\.e2e-seed|E2eSeedPlantedFailure");

    private static Path root;
    private static String script;

    @BeforeAll
    static void readTheSeed() {
        root = EvidenceCitations.repositoryRoot();
        script = EvidenceCitations.read(root.resolve(SCRIPT));
    }

    @Test
    @DisplayName("no module's main source set names the seed, so neither the application's jar nor the migrator's migrations carry it")
    void noImageCarriesTheSeed() throws IOException {
        List<Path> main;
        try (Stream<Path> modules = Files.list(root)) {
            main = modules.map(module -> module.resolve("src/main")).filter(Files::isDirectory).toList();
        }
        assertThat(main).as("the modules' main source sets were found").hasSizeGreaterThan(10);
        List<String> naming = main.stream().flatMap(E2eSeedStaysTestOnlyTest::files).filter(E2eSeedStaysTestOnlyTest::namesTheSeed)
                .map(file -> root.relativize(file).toString()).toList();
        assertThat(naming).as("the seed and its rows belong to tests: a main source set that names them ships them").isEmpty();
    }

    @Test
    @DisplayName("nothing deployed names the seed: no manifest, no delivery root, no Terraform")
    void nothingDeployedRunsTheSeed() {
        List<String> naming = Stream.of("deploy", "infra/terraform").map(root::resolve).filter(Files::isDirectory)
                .flatMap(E2eSeedStaysTestOnlyTest::files).filter(E2eSeedStaysTestOnlyTest::namesTheSeed)
                .map(file -> root.relativize(file).toString()).toList();
        assertThat(naming).isEmpty();
    }

    @Test
    @DisplayName("every plant is marked test-only and is one statement, so psql and the database test run the same text")
    void everyPlantIsOneMarkedStatement() {
        List<Path> plants = files(root.resolve(SEED)).filter(file -> file.toString().endsWith(".sql")).toList();
        assertThat(plants).extracting(file -> file.getFileName().toString())
                .containsExactlyInAnyOrder("abandoned-event.sql", "parked-record.sql", "held-role.sql", "risk-alert.sql",
                        "sealed-assessment.sql", "fx-rates.sql", "accounting-break.sql", "merchant-organisation.sql",
                        "payout-destination.sql", "customer-verdict.sql", "kyc-review.sql", "counterparty-screening.sql");
        for (Path plant : plants) {
            String text = EvidenceCitations.read(plant);
            assertThat(text).as("%s says what it is", plant.getFileName()).containsPattern("\\A-- TEST-ONLY \\(P17-TSK-1(06|08|10|12|13|15)\\)");
            String statements = text.replaceAll("(?m)--.*$", "").replaceAll("'[^']*'", "''").strip();
            assertThat(statements.chars().filter(c -> c == ';').count()).as("%s is one statement", plant.getFileName()).isEqualTo(1);
            assertThat(statements).as("%s ends in that statement", plant.getFileName()).endsWith(";");
            assertThat(statements).as("%s takes nothing away and changes no schema", plant.getFileName())
                    .doesNotContainPattern("(?i)\\b(DELETE|TRUNCATE|ALTER|DROP|GRANT|CREATE)\\b");
            if (plant.getFileName().toString().equals(VERDICT)) {
                continue;
            }
            assertThat(statements).as("%s inserts its own module's rows and changes none", plant.getFileName())
                    .doesNotContainPattern("(?i)\\bUPDATE\\b");
        }
    }

    /** The one plant that changes a row (`P17-TSK-115`): a test identity's KYC verdict, which no door reaches on a test stack. */
    private static final String VERDICT = "customer-verdict.sql";

    @Test
    @DisplayName("P17-TSK-115: the one plant that changes a row changes one status, of one PENDING customer, to ACTIVE - party's own machine"
            + " edge, the verdict an approved case's decision writes - and nothing else")
    void theVerdictChangesOneStatusAlongItsEdge() {
        String verdict = EvidenceCitations.read(root.resolve(SEED + VERDICT)).replaceAll("(?m)--.*$", "").strip();
        assertThat(verdict).as("one UPDATE, of party.customer").containsPattern("\\AUPDATE party\\.customer c\\s")
                .containsOnlyOnce("UPDATE");
        assertThat(verdict).as("its status and the instant it changed, nothing else")
                .contains("SET status = 'ACTIVE', status_changed_at = GREATEST(clock_timestamp(), c.opened_at)")
                .doesNotContain("SET party_id", ", party_id =", ", opened_at =", "kind", "display_name");
        assertThat(verdict).as("only a PENDING customer of the identity named, so a re-run or another party changes nothing")
                .contains("i.id = :'identity'::uuid AND c.party_id = i.party_id AND c.status = 'PENDING'");
        int checked = script.indexOf("uuid_like \"$payer\" ||");
        assertThat(checked).as("the identity is checked").isPositive();
        assertThat(script.indexOf("plant customer-verdict.sql -v identity=\"$payer\"")).as("and only then planted").isGreaterThan(checked);
        // P17-TSK-117: the borrower's verdict, the same way.
        int borrowerChecked = script.indexOf("uuid_like \"$borrower\" ||");
        assertThat(borrowerChecked).as("the borrower is checked").isPositive();
        assertThat(script.indexOf("plant customer-verdict.sql -v identity=\"$borrower\"")).as("and only then planted")
                .isGreaterThan(borrowerChecked);
        // P17-TSK-119: the trader's verdict, the same way.
        int traderChecked = script.indexOf("uuid_like \"$trader\" ||");
        assertThat(traderChecked).as("the trader is checked").isPositive();
        assertThat(script.indexOf("plant customer-verdict.sql -v identity=\"$trader\"")).as("and only then planted")
                .isGreaterThan(traderChecked);
    }

    @Test
    @DisplayName("a plant's values are the database client's quoted variables, each checked by the shell first, never spliced into it"
            + " (P17-TSK-108)")
    void aPlantsValuesAreQuotedVariables() {
        String held = EvidenceCitations.read(root.resolve(SEED + "held-role.sql")).replaceAll("(?m)--.*$", "");
        assertThat(held).as("only the quoted form, which psql makes a literal")
                .contains(":'identity'").contains(":'role'").contains(":'granter'").doesNotContainPattern(":[a-z]");
        int checked = script.indexOf("uuid_like \"$1\" && uuid_like \"$ADMIN_ID\" ||");
        int planted = script.indexOf("plant held-role.sql -v identity=\"$1\" -v role=\"$2\" -v granter=\"$ADMIN_ID\"");
        assertThat(checked).as("the ids are checked").isPositive();
        assertThat(planted).as("and only then planted, as variables").isGreaterThan(checked);
        assertThat(script).as("the roles a plant is handed are the script's own words, not a run's input")
                .contains("held_role \"$holder\" LEDGER_OPERATOR").contains("held_role \"$holder\" FINANCIAL_CONTROLLER")
                .doesNotContainPattern("held_role \"\\$holder\" \"\\$");

        // P17-TSK-110: the risk plants take the seed's version the same way, checked first.
        for (String risk : List.of("risk-alert.sql", "sealed-assessment.sql")) {
            int versionChecked = script.lastIndexOf("uuid_like \"$TEST_POLICY_ID\"", script.indexOf("plant " + risk));
            assertThat(versionChecked).as("the version %s is handed is checked first", risk).isPositive();
            assertThat(script).contains("plant " + risk + " -v version=\"$TEST_POLICY_ID\"");
        }
        // P17-TSK-112: the finance plants' values - an instant, a month's first day, a subject - each checked by its shape first.
        int instantChecked = script.indexOf("grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T12:00:00Z$'");
        assertThat(instantChecked).as("the rates' instant is checked").isPositive();
        assertThat(script.indexOf("plant fx-rates.sql -v observed=\"$1\"")).as("and only then planted").isGreaterThan(instantChecked);
        int subjectChecked = script.indexOf("grep -Eq '^e2e-seed:[0-9a-f]{12}$'");
        assertThat(subjectChecked).as("the break's subject is checked").isPositive();
        assertThat(script.indexOf("plant accounting-break.sql -v month=\"$CURRENT_MONTH-01\" -v subject=\"$subject\""))
                .as("and only then planted, its month one the books printed and month_like checked").isGreaterThan(subjectChecked);
        assertThat(script.indexOf("month_like \"$CURRENT_MONTH\"")).isPositive().isLessThan(subjectChecked);
        // P17-TSK-115: the destination's merchant, proposer and reference, each checked by its shape first.
        int destinationChecked = script.indexOf("uuid_like \"$owner\" && uuid_like \"$ONBOARDER_ID\" ||");
        int referenceChecked = script.indexOf("grep -Eq '^pdr_[0-9a-f]{12}$'");
        int destinationPlanted = script.indexOf(
                "plant payout-destination.sql -v merchant=\"$owner\" -v proposer=\"$ONBOARDER_ID\" -v reference=\"$reference\"");
        assertThat(destinationChecked).as("the destination's ids are checked").isPositive();
        assertThat(referenceChecked).as("its reference is checked").isPositive();
        assertThat(destinationPlanted).as("and only then planted").isGreaterThan(destinationChecked).isGreaterThan(referenceChecked);
        for (Path plant : files(root.resolve(SEED)).filter(file -> file.toString().endsWith(".sql")).toList()) {
            String statement = EvidenceCitations.read(plant).replaceAll("(?m)--.*$", "").replaceAll("'[^']*'", "''");
            assertThat(statement).as("%s takes its values only as quoted variables - no bare :name", plant.getFileName())
                    .doesNotContainPattern("(?<!:):(?![:'])");
        }
    }

    @Test
    @DisplayName("P17-TSK-112: the seed's GL chart is the kind rehearsal's, byte for byte - held to the reference mapping there - so the"
            + " books a seed closes are mapped as a rehearsal's are")
    void theSeedsChartIsTheRehearsals() {
        assertThat(EvidenceCitations.read(root.resolve(SEED + "gl-mapping.json")))
                .isEqualTo(EvidenceCitations.read(root.resolve("infra/scripts/kind-rehearsal/gl-mapping.json")));
    }

    @Test
    @DisplayName("the script refuses any stack but a test one before it touches anything")
    void theScriptRefusesAnythingButATestStack() {
        assertThat(script).startsWith("#!/bin/sh").contains("set -eu");
        assertThat(script).as("the compose target answers only on this machine, anchored so no lookalike host passes")
                .contains("grep -Eq '^http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?/?$'")
                .contains("|| refuse \"FINAPP_BACKEND_URL is");
        assertThat(script).as("the kind target runs only against a kind node, read from the node's own label")
                .contains("{{index .Config.Labels \"io.x-k8s.kind.cluster\"}}")
                .contains("refuse \"E2E_SEED_NODE '$E2E_SEED_NODE' is not a kind node");
        assertThat(script).as("an unknown target is refused, never defaulted to something live")
                .containsPattern("\\*\\)\\s+refuse \"E2E_SEED_TARGET is");
        int refusals = script.indexOf("refuse \"E2E_SEED_TARGET is");
        assertThat(script.indexOf("psql")).as("the refusals come before the first database statement").isGreaterThan(refusals);
    }

    @Test
    @DisplayName("the HTTP half has no route to the database, and what the shell puts into SQL is checked first")
    void theHttpHalfHasNoDatabaseRoute() {
        String seed = EvidenceCitations.read(root.resolve(SEED + "Seed.java"));
        assertThat(seed).as("Seed.java reaches the platform through its doors only")
                .doesNotContain("java.sql").doesNotContainIgnoringCase("jdbc").doesNotContain("psql");
        assertThat(seed).as("a test identity's secrets stay in the run's state, never on standard output")
                .doesNotContainPattern("System\\.out\\.println\\([^)]*(password|totp|secret)");
        int checked = script.indexOf("grep -Eq '^e2e-seed\\.[a-z]+\\.[0-9a-f]{12}$'");
        assertThat(checked).as("a login's shape is checked before it reaches SQL").isPositive();
        assertThat(script.indexOf("WHERE login_identifier = '$login'")).isGreaterThan(checked);
        assertThat(script).as("the run's state, with its test identities' secrets, goes with the run").contains("trap cleanup EXIT");
        // P17-TSK-115: the top-up's id and the dispute's reference, each checked before the database is asked about it.
        int intentChecked = script.indexOf("uuid_like \"$intent\" ||");
        assertThat(intentChecked).isPositive();
        assertThat(script.indexOf("WHERE intent_id = '$intent'")).isGreaterThan(intentChecked);
        int disputeChecked = script.indexOf("grep -Eq '^dp_e2e_[0-9a-f]{12}$'");
        assertThat(disputeChecked).isPositive();
        assertThat(script.indexOf("WHERE provider_dispute_reference = '$dispute'")).isGreaterThan(disputeChecked);
        assertThat(seed).as("the provider's signing key is read from the run's state and printed nowhere")
                .contains("state.resolve(\"provider-key\")").doesNotContainPattern("System\\.out\\.println\\([^)]*(signing|provider-key)");
        // P17-TSK-117: the credit request's id, checked before the database is asked for its case.
        int requestChecked = script.indexOf("uuid_like \"$request\" ||");
        assertThat(requestChecked).isPositive();
        assertThat(script.indexOf("WHERE decision_request_id = '$request'")).isGreaterThan(requestChecked);
    }

    @Test
    @DisplayName("P17-TSK-117: the credit desks act through their doors - the seed's policy reads no source and refers, a pending version"
            + " is withdrawn only by the rejection door, the customer applies stepped up - and nothing writes a credit, lending or BNPL row")
    void theCreditDesksActThroughTheirDoors() {
        String seed = EvidenceCitations.read(root.resolve(SEED + "Seed.java"));
        assertThat(seed).as("the seed's policy reads no source, refers on the bureau's absence, and keeps the exposure rule every"
                        + " policy must have")
                .contains("\\\"sources\\\":[]").contains("\\\"operator\\\":\\\"IS_ABSENT\\\",\\\"effect\\\":\\\"REFER\\\"")
                .contains("\\\"subject\\\":\\\"EXPOSURE_HEADROOM\\\"");
        assertThat(seed).as("the customer applies on a stepped-up public session, as applying for credit always is")
                .contains("elevatedOn(publicBase, who)").contains("\"/v1/me/credit/decision-requests\"");
        assertThat(script).as("a version left pending is withdrawn through the rejection door")
                .contains("seed_http decide-version \"$1\" \"$2\" \"$left\" rejection");
        assertThat(script.lines().filter(line -> line.strip().startsWith("withdraw ")).toList())
                .as("every withdrawal names one of the three version tables and a product, as this script's own literals - pending()"
                        + " splices them")
                .hasSize(3)
                .allMatch(line -> line.strip().matches(
                        "withdraw [a-z]+ [a-z-]+ (credit\\.credit_policy_version|lending\\.loan_terms_version|bnpl\\.bnpl_terms_version)"
                                + " [A-Z][A-Z_0-9]*"));
        assertThat(script).as("nothing writes a credit, lending or BNPL row: the doors make every version, request and case")
                .doesNotContainPattern("(?i)INSERT INTO (credit|lending|bnpl)\\.")
                .doesNotContainPattern("(?i)UPDATE (credit|lending|bnpl)\\.");
        assertThat(script).as("the states no running finapp can reach are named, not seeded").contains(
                "NOT SEEDED: A LOAN, A BNPL AGREEMENT, AND WHAT HANGS ON THEM");
    }

    @Test
    @DisplayName("P17-TSK-113: the review states are new kyc rows, as the platform leaves them - the case IN_REVIEW on a HIT with its open"
            + " task, for a PENDING customer of the identity named; the screening IN_REVIEW on a HIT, never a beneficiary's - under the"
            + " platform's policy version")
    void theReviewStatesArePlantedAsNewKycRows() {
        String review = EvidenceCitations.read(root.resolve(SEED + "kyc-review.sql")).replaceAll("(?m)--.*$", "");
        assertThat(inserted(review)).as("the case, its check and its task, and nothing else")
                .containsExactly("kyc.kyc_case", "kyc.verification_check", "kyc.review_task");
        assertThat(review).as("for a PENDING customer of the identity named, so a decision has a customer to make ACTIVE or REJECTED")
                .contains("WHERE i.id = :'identity'::uuid AND c.status = 'PENDING'")
                .contains("'IN_REVIEW', '" + KycPolicyVersion.CURRENT.value() + "'")
                .contains("'SANCTIONS', 'HIT'").contains("'OPEN', now()");
        String screening = EvidenceCitations.read(root.resolve(SEED + "counterparty-screening.sql")).replaceAll("(?m)--.*$", "");
        assertThat(inserted(screening)).as("the screening and its one attempt, and nothing else")
                .containsExactly("kyc.counterparty_screening", "kyc.counterparty_screening_attempt");
        assertThat(screening).as("IN_REVIEW on a HIT under the platform's policy version, requested by the person the shell names")
                .contains("'IN_REVIEW', 'HIT', 'AUTOMATIC', '" + KycPolicyVersion.CURRENT.value() + "'")
                .contains(":'requester'");
        assertThat(screening).as("its reference the seed's own, so no beneficiary listener acts on its decision")
                .contains("'e2e-seed-' || seed.id").doesNotContain("xb-beneficiary-");
    }

    @Test
    @DisplayName("P17-TSK-113: what the KYC scenarios hand a plant or splice into a query is checked first - the applicant, after its"
            + " consent went through the door; the plant's task; the task's case and check; the remitter - and the customer history is"
            + " named as owed")
    void theKycValuesAreCheckedBeforeSql() {
        int applicantChecked = script.indexOf("uuid_like \"$applicant\" ||");
        int consented = script.indexOf("[ \"$consented\" = KYC_PROCESSING ] ||");
        int reviewPlanted = script.indexOf("plant kyc-review.sql -v identity=\"$applicant\"");
        assertThat(applicantChecked).as("the applicant is checked").isPositive();
        assertThat(consented).as("its consent granted through the door, and checked").isGreaterThan(applicantChecked);
        assertThat(reviewPlanted).as("and only then its case planted").isGreaterThan(consented);
        int taskChecked = script.indexOf("uuid_like \"$task\" ||");
        assertThat(taskChecked).as("the plant's task is checked").isGreaterThan(reviewPlanted);
        assertThat(script.indexOf("WHERE id = '$task'")).as("before it reaches SQL").isGreaterThan(taskChecked);
        int caseChecked = script.indexOf("uuid_like \"$reviewed\" && uuid_like \"$hit\" ||");
        assertThat(caseChecked).as("the task's case is checked").isGreaterThan(taskChecked);
        assertThat(script.indexOf("WHERE id = '$reviewed'")).as("before it reaches SQL").isGreaterThan(caseChecked);
        int remitterChecked = script.indexOf("uuid_like \"$remitter\" ||");
        assertThat(remitterChecked).as("the remitter is checked").isPositive();
        assertThat(script.indexOf("plant counterparty-screening.sql -v requester=\"$remitter\"")).as("and only then planted")
                .isGreaterThan(remitterChecked);
        assertThat(script).as("the customer's account, transfers and consent history are named as owed, not planted")
                .contains("NOT SEEDED YET: THE KYC CUSTOMER'S ACCOUNT, TRANSFERS AND CONSENT HISTORY (P17-TSK-121)");
    }

    /** The tables a plant's statement inserts into, in order. */
    private static List<String> inserted(String statement) {
        Matcher insert = Pattern.compile("(?i)INSERT INTO ([a-z_]+\\.[a-z_]+)").matcher(statement);
        List<String> tables = new java.util.ArrayList<>();
        while (insert.find()) {
            tables.add(insert.group(1));
        }
        return tables;
    }

    @Test
    @DisplayName("P17-TSK-119: the FX desks act through their doors - every version and enabling proposed by the seed's controller, a"
            + " pending one withdrawn only by the rejection door, the conversion priced by the platform; booked-trade judged on kind's"
            + " Secret before anything is touched - and nothing writes an FX or cross-border row")
    void theFxDesksActThroughTheirDoors() {
        String seed = EvidenceCitations.read(root.resolve(SEED + "Seed.java"));
        String trade = seed.substring(seed.indexOf("private void fxTrade("), seed.indexOf("System.out.println(\"FX_TRADE_ID=\""));
        assertThat(trade).as("the conversion is the customer's quote accepted, and neither body it sends names a rate")
                .contains("\"/v1/me/fx/quotes\"").contains("\"/v1/me/fx/conversions\"")
                .doesNotContainPattern("[rR]ate\\\\\":");
        assertThat(seed).as("the policies are the runbook's version 1, on its provider and its rail (equal, by E2eSeedFxDatabaseTest)")
                .contains("\\\"providers\\\":[\\\"fx-sim-a\\\"]").contains("\\\"rails\\\":[\\\"corridor-sim-a\\\"]");
        assertThat(script).as("a version or an enabling left pending is withdrawn through the rejection door, by the seed's controller")
                .contains("seed_http decide-version fxcontroller \"$1\" \"$left\" rejection")
                .contains("seed_http decide-enable fxcontroller \"$1\" \"$3\" rejection");
        assertThat(script.lines().map(String::strip).filter(line -> line.startsWith("withdraw_version ") || line.startsWith("in_force "))
                .toList())
                .as("every withdrawal and every version put in force names one of the two policy tables, as this script's own literals"
                        + " - proposed_one() and in_force() splice them - the one pass-through in_force's own")
                .containsExactlyInAnyOrder(
                        "in_force fx-pricing fx.pricing_policy_version",
                        "in_force corridor-policy crossborder.corridor_policy_version",
                        "withdraw_version \"$1\" \"$2\"",
                        "withdraw_version fx-pricing fx.pricing_policy_version",
                        "withdraw_version corridor-policy crossborder.corridor_policy_version");
        assertThat(script.lines().map(String::strip).filter(line -> line.matches("ENABLE_(PAIR|CORRIDOR)=.*")).toList())
                .as("the enable requests' subjects, spliced into SQL, are the script's own literals, set once")
                .containsExactly("ENABLE_PAIR=BHD-JPY", "ENABLE_CORRIDOR=GBP-USD-US");
        int leftChecked = script.indexOf("[ -z \"$3\" ] || uuid_like \"$3\" ||");
        assertThat(leftChecked).as("a pending request's id is checked").isPositive();
        assertThat(script.indexOf("seed_http decide-enable fxcontroller \"$1\" \"$3\" rejection")).as("before it is withdrawn")
                .isGreaterThan(leftChecked);
        assertThat(script).as("nothing writes an FX or cross-border row: the doors make every version, request and trade")
                .doesNotContainPattern("(?i)INSERT INTO (fx|crossborder)\\.").doesNotContainPattern("(?i)UPDATE (fx|crossborder)\\.");

        int judged = script.indexOf("booked-trade)\n            HTTP=yes\n            [ \"$TARGET\" = kind ] || refuse");
        assertThat(judged).as("booked-trade refused off kind, in the scenarios' judgement").isPositive();
        assertThat(script.indexOf("psql")).as("before anything is touched").isGreaterThan(judged);
        int feeds = script.indexOf("[ \"$scenario\" != booked-trade ] || fx_feeds");
        assertThat(feeds).as("and on kind, its Secret judged").isPositive();
        assertThat(script.indexOf("        booked-trade) seed_booked_trade ;;")).as("before any scenario runs").isGreaterThan(feeds);
        assertThat(script).as("for the FX provider and the reference feed, their values never printed")
                .contains("for name in FINAPP_FX_PROVIDER_URL FINAPP_FX_REFERENCE_URL; do")
                .doesNotContainPattern("(say|printf|echo)[^\n]*\\$delivered\\b");
        assertThat(script).as("the trace no test stack can reach is named, not seeded")
                .contains("NOT SEEDED: A CROSS-BORDER PAYMENT, AND SO ITS TRACE");
    }

    @Test
    @DisplayName("P17-TSK-115: the dispute is the card network's word - refused on compose, which has no card PSP; on kind the seed only"
            + " states it to the rehearsal's simulator and hints that it exists, and the platform's own inquiry opens it")
    void theDisputeIsTheNetworksWord() {
        int judged = script.indexOf("open-dispute)\n            HTTP=yes\n            [ \"$TARGET\" = kind ] || refuse");
        assertThat(judged).as("refused off kind, in the scenarios' judgement").isPositive();
        assertThat(script.indexOf("psql")).as("before anything is touched").isGreaterThan(judged);
        String simulator = EvidenceCitations.read(root.resolve("infra/scripts/kind-rehearsal/Simulator.java"));
        assertThat(simulator).as("the simulator answers the adapter's dispute inquiry from what the seed stated, and its answers")
                .contains("server.createContext(DISPUTES, Simulator::disputeInquiry)")
                .contains("private static final String DISPUTES = \"/disputes/\"")
                .contains("server.createContext(DISPUTE_RESPONSES, exchange -> answer(exchange, DISPUTE_RESPONSES,")
                .contains("private static final Path DISPUTE_STATEMENTS = Path.of(\"/tmp/disputes\")");
        assertThat(script).as("the seed states the dispute there, then hints").contains("cat > \"/tmp/disputes/$1\"' statement \"$dispute\"")
                .contains("seed_http dispute-hint \"$operation\" \"$dispute\"");
        String seed = EvidenceCitations.read(root.resolve(SEED + "Seed.java"));
        assertThat(seed).as("in the adapter's own words: the statement's paths are the adapter's constants'")
                .contains("\"/v1/providers/payments/webhooks\"").contains("\"stage\\\":\\\"needs_response\\\"");
        assertThat(script).as("and nothing writes a dispute or a posting: the shell only reads the dispute the platform opened")
                .doesNotContainPattern("(?i)INSERT INTO payments\\.").doesNotContainPattern("(?i)INSERT INTO ledger\\.");
    }

    @Test
    @DisplayName("the script is committed executable, as every script under infra/scripts")
    void theScriptIsExecutable() throws Exception {
        Process git = new ProcessBuilder("git", "ls-files", "-s", SCRIPT).directory(root.toFile()).redirectErrorStream(true).start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the script is tracked").isNotBlank().startsWith("100755");
    }

    private static Stream<Path> files(Path directory) {
        try {
            return Files.walk(directory).filter(Files::isRegularFile).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + directory, e);
        }
    }

    private static boolean namesTheSeed(Path file) {
        try {
            return MARKERS.matcher(Files.readString(file, StandardCharsets.ISO_8859_1)).find();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
