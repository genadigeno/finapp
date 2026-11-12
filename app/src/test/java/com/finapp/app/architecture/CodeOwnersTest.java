package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The production approval boundary as the repository can hold it (`P15-TSK-017`, C8; {@code THREAT_MODEL.md} T11 and T28): every
 * path that reaches production under pull-based GitOps, or governs what does, has a code owner in {@code .github/CODEOWNERS}, read
 * the way GitHub reads it (the last matching line wins, so a later line naming nobody un-owns). Whether GitHub enforces it is a
 * repository setting, the owner's to apply ({@code THREAT_MODEL.md} section 6 item 4), and nothing here claims it.
 */
@Tag("architecture")
@DisplayName("every path that reaches or governs production has a code owner, read as GitHub reads the file (P15-TSK-017)")
class CodeOwnersTest {

    private static final String FILE = ".github/CODEOWNERS";

    /** What is owned, as data apart from the reading of it. */
    private static final class Owned {

        /** The paths the entry and its routed notes name, and what else renders into production or decides what does. */
        static final List<String> PATHS = List.of(
                "deploy/overlays/production/kustomization.yaml",
                "deploy/overlays/production/release.yaml",
                "deploy/edge/overlays/production/kustomization.yaml",
                "deploy/components/secret-delivery/externalsecret-app.yaml",
                "deploy/components/delivery-order/kustomization.yaml",
                "deploy/delivery/production/applications.yaml",
                "deploy/delivery/roots/production.yaml",
                "deploy/base/deployment.yaml",
                "deploy/admission/clusterimagepolicies.yaml",
                "deploy/namespaces/namespaces.yaml",
                "deploy/evidence/staging/a-record.yaml",
                "infra/terraform/environments/production/main.tf",
                "infra/terraform/modules/registry/main.tf",
                "infra/scripts/promote.sh",
                "infra/scripts/Promotion.java",
                "infra/scripts/key-ring-activation-check.sh",
                "docs/architecture/CONFIGURATION_REGISTER.md",
                "docs/migrations/released-schema-floor.yaml",
                ".github/CODEOWNERS",
                ".github/workflows/ci.yml",
                // What the owned checks read (the review of P15-TSK-017): the release name, the markers, the guards and the lint, the catalog.
                "build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts",
                "ledger/src/main/resources/db/migration/ledger/V001__a-migration.sql",
                "platform/src/main/resources/db/migration/platform/V099__another.sql",
                "app/src/test/java/com/finapp/app/architecture/MigrationLint.java",
                "app/src/test/java/com/finapp/app/architecture/MigrationsAreExpandOnlyTest.java",
                "app/src/test/java/com/finapp/app/architecture/ThreatModelIsCompleteTest.java",
                "app/src/test/java/com/finapp/app/architecture/CodeOwnersTest.java",
                "gradle/libs.versions.toml",
                "gradle/verification-metadata.xml");

        /** The trees every file of which reaches production, or decides what does, at its merge. */
        static final List<String> TREES = List.of("deploy", "infra", "build-logic", "gradle", "app/src/test/java/com/finapp/app/architecture");

        /** Where a module keeps its migrations, below the module's directory. */
        static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

        private Owned() {}
    }

    private static Path root;
    private static List<Rule> rules;

    private record Rule(String pattern, Pattern regex, List<String> owners) {}

    @BeforeAll
    static void readTheFile() {
        root = EvidenceCitations.repositoryRoot();
        rules = parse(EvidenceCitations.read(root.resolve(FILE)));
    }

    @Test
    @DisplayName("each path the entry names, and what the owned checks read, has an owner")
    void everyProductionPathIsOwned() {
        assertThat(rules).as("the file was read").isNotEmpty();
        for (String path : Owned.PATHS) {
            assertThat(owners(rules, path)).as("%s: a change here reaches production at its merge, so it needs a second person", path).isNotEmpty();
        }
    }

    @Test
    @DisplayName("every file of the trees that reach production, and every module's migrations, has an owner")
    void everyFileOfTheOwnedTreesIsOwned() {
        List<String> files = new ArrayList<>();
        for (String tree : Owned.TREES) {
            files.addAll(filesUnder(root.resolve(tree)));
        }
        // Every module's migrations, read from the tree: a module added later is held without a line here.
        List<String> migrations = new ArrayList<>();
        for (Path module : children(root)) {
            migrations.addAll(filesUnder(module.resolve(Owned.MIGRATIONS)));
        }
        assertThat(migrations).as("the migrations were read").hasSizeGreaterThan(300);
        files.addAll(migrations);
        assertThat(files.stream().filter(path -> owners(rules, path).isEmpty()).toList()).isEmpty();
    }

    @Test
    @DisplayName("every line names an owner, so no later line un-owns a path; GitHub reads this file and no other")
    void noLineUnownsAPath() {
        for (Rule rule : rules) {
            assertThat(rule.owners()).as("%s names nobody, which un-owns whatever matched an earlier line", rule.pattern()).isNotEmpty();
            assertThat(rule.owners()).allSatisfy(owner -> assertThat(owner).matches("@[A-Za-z0-9-]+(/[A-Za-z0-9_.-]+)?"));
        }
        // GitHub reads .github/CODEOWNERS first, then the root's, then docs/'s: a second file would be a second, unread, set of rules.
        assertThat(root.resolve("CODEOWNERS")).doesNotExist();
        assertThat(root.resolve("docs/CODEOWNERS")).doesNotExist();
    }

    @Test
    @DisplayName("the reading is GitHub's: anchored and unanchored patterns, a directory, a glob, and the last match winning, each on planted lines")
    void theMatcherReadsGitHubsSyntax() {
        List<Rule> planted = parse("""
                # a comment
                /deploy/                         @owner
                *.md                             @writers
                docs/                            @docs-team
                /deploy/overlays/kind/           @kind-people
                /infra/terraform/environments/production/main.tf
                """);
        assertThat(owners(planted, "deploy/overlays/production/kustomization.yaml")).containsExactly("@owner");
        assertThat(owners(planted, "deploy/overlays/kind/kustomization.yaml")).as("the later line wins").containsExactly("@kind-people");
        assertThat(owners(planted, "README.md")).containsExactly("@writers");
        assertThat(owners(planted, "a/docs/b.txt")).as("an unanchored directory matches at any depth").containsExactly("@docs-team");
        assertThat(owners(planted, "x/deploy/y.yaml")).as("an anchored one only at the root").isEmpty();
        assertThat(owners(planted, "infra/terraform/environments/production/main.tf")).as("a line naming nobody un-owns").isEmpty();
        assertThat(owners(planted, "deployment.yaml")).isEmpty();
    }

    // ------------------------------------------------------------------------------------------- GitHub's reading

    private static List<Rule> parse(String text) {
        List<Rule> parsed = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String content = line.strip();
            if (content.isEmpty() || content.startsWith("#")) {
                continue;
            }
            String[] fields = content.split("\\s+");
            List<String> owners = new ArrayList<>(List.of(fields).subList(1, fields.length));
            parsed.add(new Rule(fields[0], regex(fields[0]), owners));
        }
        return parsed;
    }

    /** gitignore's grammar as GitHub applies it to CODEOWNERS: a leading slash anchors, a trailing slash is a directory's contents. */
    private static Pattern regex(String pattern) {
        boolean anchored = pattern.startsWith("/") || pattern.substring(0, pattern.length() - 1).contains("/");
        String body = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        boolean directory = body.endsWith("/");
        if (directory) {
            body = body.substring(0, body.length() - 1);
        }
        StringBuilder regex = new StringBuilder(anchored ? "^" : "^(?:.*/)?");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '*' && i + 1 < body.length() && body.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append(directory ? "/.*$" : "(?:/.*)?$");
        return Pattern.compile(regex.toString());
    }

    // ------------------------------------------------------------------------------------------- the tree

    /** Every regular file below a directory, as a repository path; none when the directory does not exist. */
    private static List<String> filesUnder(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> walked = Files.walk(directory)) {
            return walked.filter(Files::isRegularFile).map(file -> root.relativize(file).toString().replace('\\', '/')).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> children(Path directory) {
        try (Stream<Path> listed = Files.list(directory)) {
            return listed.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> owners(List<Rule> rules, String path) {
        List<String> owners = List.of();
        for (Rule rule : rules) {
            if (rule.regex().matcher(path).matches()) {
                owners = rule.owners();
            }
        }
        return owners;
    }
}
