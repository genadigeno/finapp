package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The image scan and the static analysis are wired as the threat register says, and nothing has narrowed them
 * ({@code P15-TSK-006}; {@code THREAT_MODEL.md} T5, T6, T43, T45).
 *
 * <p>What only a run can show - that Trivy and Semgrep find what is planted - is shown by {@code image-scan.sh} and
 * {@code sast.sh}, in CI, where every plant must be refused by name and a plant accepted fails the job. This test
 * fails a pull request before any of that runs, and holds what a run cannot see: that the bar was not lowered, that
 * a plant was not deleted, that a finding carried has its reason, its expiry and its line in the register.
 *
 * <p>What it does not hold: whether a CVE is present today, which is what the scan is for, and the claim that a
 * rule finds its shape, which is {@code semgrep --test}'s, run by {@code sast.sh}.
 */
@Tag("architecture")
@DisplayName("the image scan and the static analysis are wired as the threat register says (P15-TSK-006)")
class SecurityScansAreWiredTest {

    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final String IMAGE_SCAN = "infra/scripts/image-scan.sh";
    private static final String SAST = "infra/scripts/sast.sh";
    private static final String DEPENDENCY_SCAN = "infra/scripts/dependency-scan.sh";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String PIN_CHECK = "infra/scripts/check-pinned-images.sh";
    private static final String ACCEPTED = "infra/trivy/accepted-findings.yaml";
    private static final String BASELINE = "infra/semgrep/baseline.tsv";
    private static final String RULES = "infra/semgrep/rules";
    private static final String THREAT_MODEL = "docs/architecture/THREAT_MODEL.md";

    /** The bar: nothing below it passes because a value in a script changed. */
    private static final String BAR = "HIGH,CRITICAL";

    /** Flags that narrow what a scan sees or what fails it. None of them belongs in a security scan here. */
    private static final List<String> NARROWING_TRIVY_FLAGS = List.of(
            "--ignore-unfixed", "--skip-dirs", "--skip-files", "--skip-version-check=false", "--ignore-policy",
            "--include-non-failures", "--severity CRITICAL", "--severity=CRITICAL", "--exit-code 0", "--exit-code=0",
            "--list-all-pkgs=false");

    /** The plants and what each must be refused for, as the scan reports it. */
    private static final Map<String, String> IMAGE_PLANTS = Map.of(
            "vulnerable-base", "CVE-",
            "high-only", "CVE-2021-23337",
            "leak-in-layer", "github-pat",
            "leak-in-env", "aws-access-key-id",
            "root-user", "DS-0002");

    private static final Map<String, String> SAST_PLANTS = Map.of(
            "sql-concatenation", "finapp-sql-text-not-constant",
            "sql-helper", "finapp-sql-helper-given-non-constant",
            "sql-builder-name", "finapp-sql-text-not-constant",
            "unbounded-body", "finapp-provider-body-unbounded",
            "weak-digest", "finapp-java-weak-digest",
            "cors-wildcard", "finapp-spring-cors-wildcard",
            "secret-in-source", "finapp-secret-private-key-block");

    /** A row of {@code baseline.tsv}: fingerprint, rule, path, excerpt, until, reason. */
    private static final int BASELINE_COLUMNS = 6;

    /**
     * The baseline stays a record of exceptions: a fraction of the statements and adapters the rules read. Above this
     * ratio it holds the codebase's idiom, and the rule, not the baseline, is what needs the attention.
     */
    private static final double BASELINE_RATIO_LIMIT = 0.15;

    private static final Pattern STATEMENT_CALL = Pattern.compile("\\.(?:prepareStatement|prepareCall)\\s*\\(");

    private static Path root;
    private static Map<String, List<String>> jobs;
    private static String imageScan;
    private static String sast;
    private static String baseline;
    private static String accepted;
    private static String threatModel;
    private static Set<String> ruleIds;
    private static long statements;

    @BeforeAll
    static void readTheFiles() {
        root = EvidenceCitations.repositoryRoot();
        jobs = jobs(EvidenceCitations.read(root.resolve(WORKFLOW)));
        imageScan = EvidenceCitations.read(root.resolve(IMAGE_SCAN));
        sast = EvidenceCitations.read(root.resolve(SAST));
        baseline = EvidenceCitations.read(root.resolve(BASELINE));
        accepted = EvidenceCitations.read(root.resolve(ACCEPTED));
        threatModel = EvidenceCitations.read(root.resolve(THREAT_MODEL));
        ruleIds = new TreeSet<>();
        try (Stream<Path> files = Files.list(root.resolve(RULES))) {
            files.filter(file -> file.getFileName().toString().endsWith(".yml")).forEach(file -> {
                Matcher id = Pattern.compile("(?m)^\\s*- id:\\s*(\\S+)\\s*$").matcher(EvidenceCitations.read(file));
                while (id.find()) {
                    ruleIds.add(id.group(1));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        statements = countStatements();
    }

    @Test
    @DisplayName("every image built is scanned for vulnerabilities, secrets and misconfiguration at HIGH and CRITICAL,"
            + " and each of the five plants must be refused by name")
    void everyImageIsScannedAtTheBar() {
        assertThat(barViolations(imageScan)).as(IMAGE_SCAN).isEmpty();
    }

    @Test
    @DisplayName("each image job scans the image it built and keeps its SBOM: the application image by discovery, with"
            + " the application image required; the migrator by name; the weekly schedule runs both jobs")
    void ciScansBothImagesAndKeepsTheirSboms() {
        // Exact lines. "--only" on the image job would drop its requirement of the application image; the migrator job
        // without it would look for an application image its runner never built.
        assertJobScansWhatItBuilt("image", "./infra/scripts/image-build.sh", "run: ./infra/scripts/image-scan.sh");
        assertJobScansWhatItBuilt("migrator-image", "./infra/scripts/migrator-image-build.sh",
                "run: ./infra/scripts/image-scan.sh --only finapp-migrator");
        assertThat(EvidenceCitations.read(root.resolve(WORKFLOW))).contains("cron:");
        assertThat(imageScan).as("the databases are this run's own, never kept between runs, or a weekly re-scan reads"
                        + " last week's").contains("--download-db-only").contains("--download-java-db-only")
                .contains("VOLUME=").doesNotContain("actions/cache");
    }

    @Test
    @DisplayName("each image's SBOM is written by the scan and asserted to describe the image: its OS packages, every"
            + " third-party jar by path, and no test-scope library")
    void everySbomDescribesItsImage() {
        assertThat(imageScan)
                .contains("--format cyclonedx")
                .contains("lists no operating-system package")
                .contains("omits third-party jars the image holds")
                .contains("lists a test-scope library");
        assertThat(job("dependency-scan"))
                .as("the dependency scan's SBOM is the build's, which the per-image one replaces as what ships")
                .anySatisfy(line -> assertThat(line).contains("cyclonedxBom"));
    }

    @Test
    @DisplayName("every finding carried by the image scan has a reason and an expiry and is in the threat register")
    void everyFindingCarriedIsInTheRegister() {
        assertThat(acceptedViolations(accepted, threatModel, LocalDate.now(Clock.systemUTC()))).as(ACCEPTED).isEmpty();
        assertThat(EvidenceCitations.read(root.resolve(DEPENDENCY_SCAN)))
                .as("the dependency scan honours the one list of carried findings, so a finding carried is carried once")
                .contains("--ignorefile");
    }

    @Test
    @DisplayName("the static analysis runs in its own job, with no network, over the vendored rules only, and reads the"
            + " whole of every module's src/main")
    void theStaticAnalysisRunsOverTheVendoredRulesOffline() {
        assertThat(job("sast")).anySatisfy(line -> assertThat(line).contains("./infra/scripts/sast.sh"))
                .as("the plants-only mode, for a rule author, skips the tree and is never what CI runs")
                .noneMatch(line -> line.contains("--plants-only"));
        assertThat(sastViolations(sast)).as(SAST).isEmpty();
        SAST_PLANTS.keySet().stream().filter(plant -> !plant.equals("secret-in-source")).forEach(plant -> assertThat(
                        root.resolve("infra/semgrep/plants/" + plant + "/Plant.java"))
                .as("the plant %s exists, so its name means the shape it plants (one small file per shape)", plant)
                .exists());
        String pins = EvidenceCitations.read(root.resolve(PINS));
        assertThat(pins).containsPattern("(?m)^SEMGREP_DIGEST=sha256:[0-9a-f]{64}\\s*$");
        assertThat(EvidenceCitations.read(root.resolve(PIN_CHECK)))
                .as("the Semgrep pin is checked for freshness like the others")
                .contains("check semgrep");
    }

    @Test
    @DisplayName("T45: the rule refusing a statement whose text is not built from constants exists, with its companion,"
            + " its own tests and its plants")
    void theStatementRuleIsVendoredAndPlanted() {
        assertThat(ruleIds)
                .contains("finapp-sql-text-not-constant", "finapp-sql-helper-given-non-constant");
        String rule = EvidenceCitations.read(root.resolve(RULES + "/sql.yml"));
        assertThat(rule).contains("prepareStatement").contains("prepareCall").contains("executeQuery")
                .contains("executeUpdate").contains("addBatch").contains("focus-metavariable: $SQL");
        String fixture = EvidenceCitations.read(root.resolve(RULES + "/sql.java"));
        assertThat(fixture)
                .as("every shape of a statement built from a value is a planted line")
                .containsPattern("// ruleid: finapp-sql-text-not-constant")
                .contains("String.format(")
                .contains(".formatted(")
                .contains("StringBuilder")
                .contains("// ok: finapp-sql-text-not-constant")
                .contains("// ruleid: finapp-sql-helper-given-non-constant");
    }

    @Test
    @DisplayName("T43: the rule flagging a provider's answer read with no bound exists, with its own tests and its plant")
    void theUnboundedBodyRuleIsVendoredAndPlanted() {
        assertThat(ruleIds).contains("finapp-provider-body-unbounded");
        String fixture = EvidenceCitations.read(root.resolve(RULES + "/provider-body.java"));
        assertThat(fixture).contains("// ruleid: finapp-provider-body-unbounded")
                .contains("BodyHandlers.ofByteArray()")
                .contains("readAllBytes()")
                .contains("readNBytes(BOUND + 1)");
    }

    @Test
    @DisplayName("every baseline row names a real rule and file and carries a reason and an unexpired date, and the"
            + " baseline stays a small fraction of the statements")
    void theBaselineIsSmallAndReasoned() {
        List<String> rows = baselineRows(baseline);
        assertThat(baselineViolations(rows, ruleIds, relative -> Files.exists(root.resolve(relative)),
                LocalDate.now(Clock.systemUTC()))).as(BASELINE).isEmpty();
        assertThat(statements).as("the tree's statements were counted").isGreaterThan(1000);
        double ratio = (double) rows.size() / statements;
        assertThat(ratio)
                .as("%s rows against %s statements: a baseline this large is the rule's defect, not an inventory",
                        rows.size(), statements)
                .isLessThan(BASELINE_RATIO_LIMIT);
    }

    @Test
    @DisplayName("every rule above refuses a planted violation")
    void theRulesRefuseTheirPlants() {
        String clean = imageScan;
        assertThat(barViolations(clean.replace("SEVERITY=HIGH,CRITICAL", "SEVERITY=CRITICAL")))
                .as("the bar lowered to CRITICAL").isNotEmpty();
        assertThat(barViolations(clean + "\ntrivy image --ignore-unfixed --input x\n"))
                .as("unfixed findings skipped").isNotEmpty();
        assertThat(barViolations(clean.replace("--scanners vuln,secret,misconfig", "--scanners vuln")))
                .as("secrets and misconfiguration no longer scanned").isNotEmpty();
        assertThat(barViolations(clean.replace("refuses high-only \"CVE-2021-23337\"", "true")))
                .as("the threshold plant removed").isNotEmpty();
        assertThat(barViolations(clean + "\n-v /var/run/docker.sock:/var/run/docker.sock\n"))
                .as("the scanner given the Docker socket").isNotEmpty();

        assertThat(sastViolations(sast.replace("--network none", "--network bridge")))
                .as("the rules allowed to come from the network").isNotEmpty();
        assertThat(sastViolations(sast + "\nsemgrep --config auto .\n"))
                .as("a registry ruleset").isNotEmpty();
        assertThat(sastViolations(sast.replace("--timeout=0", "--timeout=5")))
                .as("a rule allowed to time out and pass").isNotEmpty();
        assertThat(sastViolations(sast.replace("refuses sql-concatenation", "true")))
                .as("the SQL plant removed").isNotEmpty();

        LocalDate today = LocalDate.of(2026, 11, 8);
        assertThat(baselineViolations(
                List.of("0123456789abcdef\tfinapp-sql-text-not-constant\ta/b.java\tx\t2026-12-31\tshort"),
                ruleIds, path -> true, today))
                .as("a row without a reason").isNotEmpty();
        assertThat(baselineViolations(
                List.of("0123456789abcdef\tfinapp-sql-text-not-constant\ta/b.java\tx\t2026-01-01\ta reason long enough"
                        + " to read as one"),
                ruleIds, path -> true, today))
                .as("a row expired").isNotEmpty();
        assertThat(baselineViolations(
                List.of("0123456789abcdef\tno-such-rule\ta/b.java\tx\t2026-12-31\ta reason long enough to read as one"),
                ruleIds, path -> true, today))
                .as("a row for a rule that does not exist").isNotEmpty();

        String register = "12. **The scan findings**\n   - `CVE-1` until 2026-12-31\n13. other\n";
        assertThat(acceptedViolations("vulnerabilities:\n  - id: CVE-1\n    expired_at: 2026-12-31\n", register, today))
                .as("a finding with no statement").isNotEmpty();
        assertThat(acceptedViolations("vulnerabilities:\n  - id: CVE-1\n    statement: a reason long enough to read\n",
                register, today)).as("a finding with no expiry").isNotEmpty();
        assertThat(acceptedViolations(
                "vulnerabilities:\n  - id: CVE-2\n    statement: a reason long enough to read\n    expired_at: 2026-12-31\n",
                register, today)).as("a finding the register does not list").isNotEmpty();
        assertThat(acceptedViolations(
                "vulnerabilities:\n  - id: CVE-1\n    statement: a reason long enough to read\n    expired_at: 2027-06-30\n",
                register, today)).as("a finding the register carries to a different date").isNotEmpty();
    }

    // ------------------------------------------------------------------------------------------- the rules

    /** What the image scan script must and must not say. */
    static List<String> barViolations(String script) {
        List<String> problems = new ArrayList<>();
        String code = withoutComments(script);
        if (!Pattern.compile("(?m)^SEVERITY=" + BAR + "\\s*$").matcher(code).find()) {
            problems.add("the bar is not SEVERITY=" + BAR);
        }
        require(problems, code, "--severity \"$SEVERITY\"", "the scan does not apply the bar");
        require(problems, code, "--exit-code \"$FOUND\"", "the scan does not fail on a finding");
        require(problems, code, "--scanners vuln,secret,misconfig", "not every scanner is on");
        require(problems, code, "--image-config-scanners misconfig,secret", "the image's own config is not scanned");
        require(problems, code, "--ignorefile /accepted/accepted-findings.yaml", "the accepted list is not the one list");
        require(problems, code, "--input", "the image is not read from a saved tar");
        require(problems, code, "reference=finapp-*:$REVISION", "not every image built is taken");
        require(problems, code, "finapp-app:$REVISION", "the application image is not required");
        require(problems, code, "IMAGES=\"$2:$REVISION\"", "--only does not scan exactly that repository at this commit");
        require(problems, code, "dock image inspect \"$IMAGES\"", "--only does not require the image it names");
        for (String flag : NARROWING_TRIVY_FLAGS) {
            if (code.contains(flag)) {
                problems.add("a flag that narrows the scan: " + flag);
            }
        }
        if (code.contains("docker.sock")) {
            problems.add("the scanner is given the Docker socket");
        }
        IMAGE_PLANTS.forEach((plant, reason) -> {
            if (!Pattern.compile("(?m)^refuses " + Pattern.quote(plant) + " \"" + Pattern.quote(reason) + "\"")
                    .matcher(code).find()) {
                problems.add("the plant is not refused by its reason: " + plant + " (" + reason + ")");
            }
        });
        return problems;
    }

    /** What the static analysis script must and must not say. */
    static List<String> sastViolations(String script) {
        List<String> problems = new ArrayList<>();
        String code = withoutComments(script);
        require(problems, code, "--network none", "the analysis can reach the network");
        require(problems, code, "--config \"$RULES\"", "the rules are not the vendored rules");
        require(problems, code, "--timeout=0", "a rule can time out and pass");
        require(problems, code, "--timeout-threshold=0", "a file can be given up on and pass");
        require(problems, code, "--max-target-bytes=0", "a large file can be skipped and pass");
        require(problems, code, "--metrics=off", "the run reports to Semgrep");
        require(problems, code, ": > /scratch/tree/.semgrepignore", "Semgrep's default ignore list applies");
        require(problems, code, "/repo/*/src/main", "the modules are listed rather than found");
        require(problems, code, "--test", "the rules' own tests do not run");
        require(problems, code, "$COMPARE /scratch/tree.json", "findings are not compared with the baseline");
        for (String forbidden : List.of("--config auto", "--config=auto", "--config p/", "--config=p/", "--config r/",
                "--pro", "--dry-run", "--exclude ", "--exclude=", "--exclude-rule")) {
            if (code.contains(forbidden)) {
                problems.add("a flag that widens or narrows what runs: " + forbidden);
            }
        }
        SAST_PLANTS.forEach((plant, rule) -> {
            if (!Pattern.compile("(?m)^refuses " + Pattern.quote(plant) + " " + Pattern.quote(rule) + "\\s*$")
                    .matcher(code).find()) {
                problems.add("the plant is not refused by its rule: " + plant + " (" + rule + ")");
            }
        });
        return problems;
    }

    /** Every finding carried: a reason, an expiry in the future, and the same id and date in the register. */
    static List<String> acceptedViolations(String yaml, String register, LocalDate today) {
        List<String> problems = new ArrayList<>();
        String item = ownerItem12(register);
        if (item == null) {
            problems.add("the register has no owner item 12 carrying the scan findings");
            return problems;
        }
        Map<String, Map<String, String>> entries = entries(yaml);
        if (entries.isEmpty() && yaml.contains("id:")) {
            problems.add("no entry was parsed");
        }
        entries.forEach((id, fields) -> {
            String statement = fields.getOrDefault("statement", "");
            if (statement.strip().length() < 20) {
                problems.add(id + " has no reason");
            }
            String expiry = fields.get("expired_at");
            if (expiry == null) {
                problems.add(id + " has no expiry");
            } else if (LocalDate.parse(expiry).isBefore(today)) {
                problems.add(id + " expired on " + expiry);
            }
            Matcher listed = Pattern.compile("`" + Pattern.quote(id) + "`[^\\n]*?until\\s+(\\d{4}-\\d{2}-\\d{2})")
                    .matcher(item);
            if (!listed.find()) {
                problems.add(id + " is carried by " + ACCEPTED + " and is not in the register's owner item 12");
            } else if (expiry != null && !listed.group(1).equals(expiry)) {
                problems.add(id + " expires " + expiry + " in the file and " + listed.group(1) + " in the register");
            }
        });
        Matcher listed = Pattern.compile("`((?:CVE|GHSA)-[A-Za-z0-9-]+)`").matcher(item);
        while (listed.find()) {
            if (!entries.containsKey(listed.group(1))) {
                problems.add(listed.group(1) + " is in the register's owner item 12 and is not carried by " + ACCEPTED);
            }
        }
        return problems;
    }

    /** Every baseline row is well formed and carries a reason; every rule named exists; every path exists. */
    static List<String> baselineViolations(List<String> rows, Set<String> rules, Predicate<String> exists, LocalDate today) {
        List<String> problems = new ArrayList<>();
        for (String row : rows) {
            String[] columns = row.split("\t", -1);
            if (columns.length != BASELINE_COLUMNS) {
                problems.add("not " + BASELINE_COLUMNS + " columns: " + row);
                continue;
            }
            if (!columns[0].matches("[0-9a-f]{16}")) {
                problems.add("not a fingerprint: " + row);
            }
            if (!rules.contains(columns[1])) {
                problems.add("no such rule: " + columns[1]);
            }
            if (!exists.test(columns[2])) {
                problems.add("no such file: " + columns[2]);
            }
            if (LocalDate.parse(columns[4]).isBefore(today)) {
                problems.add("expired: " + row);
            }
            if (columns[5].strip().length() < 20) {
                problems.add("no reason: " + row);
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------------------------------ the helpers

    private static void require(List<String> problems, String code, String text, String problem) {
        if (!code.contains(text)) {
            problems.add(problem + " (the script no longer says " + text + ")");
        }
    }

    private static List<String> baselineRows(String text) {
        List<String> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                rows.add(line);
            }
        }
        return rows;
    }

    private static Map<String, Map<String, String>> entries(String yaml) {
        Map<String, Map<String, String>> entries = new LinkedHashMap<>();
        Map<String, String> current = null;
        for (String line : yaml.split("\\R")) {
            Matcher id = Pattern.compile("^\\s*-\\s*id:\\s*(\\S+)\\s*$").matcher(line);
            if (id.matches()) {
                current = new LinkedHashMap<>();
                entries.put(id.group(1), current);
                continue;
            }
            Matcher field = Pattern.compile("^\\s+(statement|expired_at):\\s*(.*)$").matcher(line);
            if (current != null && field.matches()) {
                current.put(field.group(1), field.group(2).strip());
            }
        }
        return entries;
    }

    /** The text of owner item 12, the numbered item of the register's section 6. */
    private static String ownerItem12(String register) {
        Matcher item = Pattern.compile("(?ms)^12\\.\\s.*?(?=^\\d+\\.\\s|^## |\\z)").matcher(register);
        return item.find() ? item.group() : null;
    }

    private static String withoutComments(String script) {
        StringBuilder code = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.stripLeading().startsWith("#")) {
                code.append(line).append('\n');
            }
        }
        return code.toString();
    }

    private static long countStatements() {
        long count = 0;
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> sources = Files.walk(main)) {
                    for (Path source : sources.filter(file -> file.toString().endsWith(".java")).toList()) {
                        Matcher call = STATEMENT_CALL.matcher(EvidenceCitations.read(source));
                        while (call.find()) {
                            count++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return count;
    }

    private static void assertJobScansWhatItBuilt(String name, String build, String scan) {
        List<String> lines = job(name);
        int built = indexOf(lines, build);
        assertThat(built).as("the %s job builds its image", name).isNotNegative();
        int scanned = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(scan)) {
                scanned = i;
            }
        }
        assertThat(scanned).as("the %s job's step is exactly '%s', after the build", name, scan).isGreaterThan(built);
        assertThat(lines).as("the plants-only mode skips the real images and is never what CI runs")
                .noneMatch(line -> line.contains("--plants-only"));
        assertThat(lines).as("the %s job keeps its image's SBOM with the run", name)
                .anySatisfy(line -> assertThat(line).contains("path: build/image-sboms/"));
        assertThat(lines).as("a %s job restricted to an event would drop the weekly re-scan", name)
                .noneMatch(line -> line.matches("^    if:.*"));
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> job(String name) {
        assertThat(jobs).as("the workflow's jobs were read").containsKey(name);
        return jobs.get(name);
    }

    private static Map<String, List<String>> jobs(String workflow) {
        Map<String, List<String>> byJob = new LinkedHashMap<>();
        boolean inJobs = false;
        String current = null;
        Pattern job = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
        for (String raw : workflow.split("\\R", -1)) {
            String line = raw;
            Matcher comment = Pattern.compile("(^|\\s)#").matcher(line);
            if (comment.find()) {
                line = line.substring(0, comment.start());
            }
            if (line.startsWith("jobs:")) {
                inJobs = true;
                continue;
            }
            if (inJobs && !line.isBlank() && !line.startsWith(" ")) {
                inJobs = false;
                current = null;
            }
            Matcher matcher = job.matcher(line);
            if (inJobs && matcher.matches()) {
                current = matcher.group(1);
                byJob.put(current, new ArrayList<>());
            } else if (current != null) {
                byJob.get(current).add(line);
            }
        }
        return byJob;
    }
}
