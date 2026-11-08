package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The CI workflow holds the supply-chain and least-privilege properties the threat register counts as built
 * ({@code P15-TSK-001}'s fix round; {@code THREAT_MODEL.md} T5, T6 and T30).
 *
 * <p>The register first cited {@code .github/workflows/ci.yml} by its existence alone, which a later edit could
 * falsify with every guard green: {@code -003}, {@code -006} and {@code -007} all add jobs to this file. Each claim
 * the register makes of the workflow is asserted here instead, so widening the token, referencing a secret or
 * pinning an action to a tag fails the build until the register is rewritten with it.
 *
 * <p>What this does not hold: the repository's settings (branch protection, environments), which are not in the
 * repository, and any workflow file other than {@code ci.yml} or local action, of which there is none today
 * ({@link #thereIsOneWorkflow} refuses either until this test reads it).
 */
@Tag("architecture")
@DisplayName("the CI workflow is locked down as the threat register says (P15-TSK-001)")
class CiWorkflowIsLockedDownTest {

    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final String DEPENDABOT = ".github/dependabot.yml";
    private static final String SCANNER_PINS = "infra/scanner-pins.sh";

    /** An action reference pinned to a full commit: {@code owner/repo[/path]@<40 hex>}. */
    private static final Pattern PINNED_ACTION = Pattern.compile("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$");

    /** A {@code uses:} key in block style ({@code - uses: x}) or flow style ({@code - { uses: x, with: ... }}). */
    private static final Pattern USES = Pattern.compile("(?:^|[\\s{,])uses:\\s*([^\\s,}]+)");

    /**
     * A reference to the secrets context: a member ({@code secrets.X}, {@code secrets['X']}), the whole context in an
     * expression ({@code toJSON(secrets)}), or a {@code secrets:} key ({@code secrets: inherit} to a reusable
     * workflow, or a map of them). A step merely named "... for secrets" is none of these.
     */
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("\\bsecrets\\s*[.\\[]"),
            Pattern.compile("\\$\\{\\{[^}]*\\bsecrets\\b"),
            Pattern.compile("\\(\\s*secrets\\s*\\)"),
            Pattern.compile("(?:^|[\\s{,-])secrets\\s*:"));

    /** The secret scan's script, whose gitleaks mode decides whether history is scanned. */
    private static final String SECRET_SCAN = "infra/scripts/secret-scan.sh";
    private static final Pattern JOB = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
    private static final Pattern PERMISSION_ENTRY = Pattern.compile("^(\\s+)([a-z-]+):\\s*(\\S+)\\s*$");

    private static Path root;
    private static List<String> lines;
    private static Map<String, List<String>> jobs;

    @BeforeAll
    static void readTheWorkflow() {
        root = EvidenceCitations.repositoryRoot();
        lines = new ArrayList<>();
        for (String line : EvidenceCitations.read(root.resolve(WORKFLOW)).split("\\R", -1)) {
            lines.add(withoutComment(line));
        }
        jobs = new LinkedHashMap<>();
        boolean inJobs = false;
        String current = null;
        for (String line : lines) {
            if (line.startsWith("jobs:")) {
                inJobs = true;
                continue;
            }
            if (inJobs && !line.isBlank() && !line.startsWith(" ")) {
                inJobs = false;
                current = null;
            }
            Matcher job = JOB.matcher(line);
            if (inJobs && job.matches()) {
                current = job.group(1);
                jobs.put(current, new ArrayList<>());
            } else if (current != null) {
                jobs.get(current).add(line);
            }
        }
    }

    @Test
    @DisplayName("the workflow token is read-only everywhere: every permissions entry is read or none")
    void theTokenIsReadOnly() {
        Map<String, String> granted = new TreeMap<>();
        boolean topLevelContentsRead = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.trim().startsWith("permissions:")) {
                continue;
            }
            String inline = line.trim().substring("permissions:".length()).trim();
            if (!inline.isEmpty()) {
                granted.put("line " + (i + 1), inline);
                continue;
            }
            int indent = line.indexOf("permissions:");
            for (int j = i + 1; j < lines.size(); j++) {
                Matcher entry = PERMISSION_ENTRY.matcher(lines.get(j));
                if (lines.get(j).isBlank()) {
                    continue;
                }
                if (!entry.matches() || entry.group(1).length() <= indent) {
                    break;
                }
                granted.put("line " + (j + 1) + " " + entry.group(2), entry.group(3));
                if (indent == 0 && entry.group(2).equals("contents") && entry.group(3).equals("read")) {
                    topLevelContentsRead = true;
                }
            }
        }
        assertThat(topLevelContentsRead)
                .as("the workflow-wide token is scoped to contents: read, the property T30's row counts")
                .isTrue();
        assertThat(granted)
                .as("a permissions entry granting more than read: the register's T30 row must be rewritten with it")
                .allSatisfy((where, scope) -> assertThat(scope).as(where).isIn("read", "none", "read-all"));
    }

    @Test
    @DisplayName("no job references a secret")
    void noJobReferencesASecret() {
        List<String> referencing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (SECRETS.stream().anyMatch(secrets -> secrets.matcher(line).find())) {
                referencing.add("line " + (i + 1) + ": " + line.trim());
            }
        }
        assertThat(referencing).as("a CI job holding a secret is a CI credential the register says does not exist")
                .isEmpty();
    }

    @Test
    @DisplayName("every action is pinned to a full commit SHA, and the pins are proposed for update weekly")
    void everyActionIsPinnedToACommit() {
        List<String> uses = new ArrayList<>();
        List<String> unpinned = new ArrayList<>();
        for (String line : lines) {
            Matcher use = USES.matcher(line);
            while (use.find()) {
                String reference = use.group(1).replace("\"", "").replace("'", "");
                uses.add(reference);
                // A local action (./...) is refused too: its own uses: lines are a second set of actions this test
                // would have to read, and there is none today (thereIsOneWorkflow).
                if (!PINNED_ACTION.matcher(reference).matches()) {
                    unpinned.add(reference);
                }
            }
        }
        assertThat(uses).as("the workflow's actions were read").hasSizeGreaterThanOrEqualTo(4);
        assertThat(unpinned)
                .as("an action pinned to a tag or a branch can be repointed by its owner, and a local action's own"
                        + " actions are unread here")
                .isEmpty();
        assertThat(EvidenceCitations.read(root.resolve(DEPENDABOT)))
                .as("the SHA pins have an update path, or they rot")
                .contains("package-ecosystem: github-actions")
                .contains("interval: weekly");
    }

    @Test
    @DisplayName("the secret-scan job checks out the whole history and its script scans it in gitleaks' git mode")
    void theHistoryIsScannedForSecrets() {
        assertThat(job("secret-scan"))
                .as("history, not the working tree: a secret committed and later removed is still disclosed")
                .anySatisfy(line -> assertThat(line).contains("fetch-depth: 0"))
                .anySatisfy(line -> assertThat(line).contains("./infra/scripts/secret-scan.sh"));
        String script = EvidenceCitations.read(root.resolve(SECRET_SCAN));
        assertThat(Pattern.compile("(?m)^\\s*git\\s+/repo\\b").matcher(script).find())
                .as("%s runs gitleaks' git mode over the mounted repository, which reads every commit", SECRET_SCAN)
                .isTrue();
        assertThat(Pattern.compile("(?m)^\\s*dir\\s+/repo\\b|--no-git").matcher(script).find())
                .as("%s scans only the working tree, so a removed secret is never found", SECRET_SCAN)
                .isFalse();
    }

    @Test
    @DisplayName("the dependency-scan job scans the build's SBOM")
    void theSbomIsScanned() {
        assertThat(job("dependency-scan"))
                .anySatisfy(line -> assertThat(line).contains("cyclonedxBom"))
                .anySatisfy(line -> assertThat(line).contains("./infra/scripts/dependency-scan.sh"));
    }

    @Test
    @DisplayName("every scanner image is pinned by digest, and the pins' freshness is checked on a schedule")
    void everyScannerImageIsPinnedByDigest() {
        String pins = EvidenceCitations.read(root.resolve(SCANNER_PINS));
        Set<String> repositories = new TreeSet<>();
        Set<String> digested = new TreeSet<>();
        Matcher repository = Pattern.compile("(?m)^([A-Z_]+)_REPOSITORY=\\S+").matcher(pins);
        while (repository.find()) {
            repositories.add(repository.group(1));
        }
        Matcher digest = Pattern.compile("(?m)^([A-Z_]+)_DIGEST=sha256:[0-9a-f]{64}\\s*$").matcher(pins);
        while (digest.find()) {
            digested.add(digest.group(1));
        }
        assertThat(repositories).as("the scanner images were read").isNotEmpty();
        assertThat(digested).as("each scanner image's digest, a full sha256").isEqualTo(repositories);
        assertThat(job("pinned-images"))
                .anySatisfy(line -> assertThat(line).contains("./infra/scripts/check-pinned-images.sh"));
        assertThat(lines).as("the workflow runs on a schedule").anySatisfy(line -> assertThat(line).contains("cron:"));
    }

    @Test
    @DisplayName("there is one workflow and no local action, so these properties hold for every workflow the repository has")
    void thereIsOneWorkflow() {
        assertThat(root.resolve(".github/workflows").toFile().list())
                .as("a second workflow is a second token and a second set of actions this test does not read")
                .containsExactly("ci.yml");
        assertThat(root.resolve(".github/actions"))
                .as("a local action's action.yml holds uses: lines and secrets this test does not read: read them here"
                        + " before adding one")
                .doesNotExist();
    }

    private static List<String> job(String name) {
        assertThat(jobs).as("the workflow's jobs were read").containsKey(name);
        return jobs.get(name);
    }

    /** A YAML comment runs from a {@code #} preceded by whitespace (or at the line's start) to its end. */
    private static String withoutComment(String line) {
        Matcher comment = Pattern.compile("(^|\\s)#").matcher(line);
        return comment.find() ? line.substring(0, comment.start()) : line;
    }
}
