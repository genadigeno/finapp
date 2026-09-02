package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * No credential literal reaches committed configuration, except the one marked local default.
 *
 * <h2>Why this exists, given that CI already runs a secret scanner</h2>
 *
 * <p>Because "secret scanning green" and "no secret in the repository" are different claims, and
 * {@code P0-TSK-031}'s acceptance criterion asks for both. This was established by probing the
 * pinned gitleaks image rather than by reasoning about it. Four plausible committed secrets, one
 * commit each:
 *
 * <table>
 *   <caption>What the scanner actually caught</caption>
 *   <tr><td>{@code -----BEGIN RSA PRIVATE KEY-----}</td><td>caught</td></tr>
 *   <tr><td>{@code db.password=} plus 32 random alphanumerics</td><td>caught</td></tr>
 *   <tr><td>a real-shaped AWS key pair</td><td>caught</td></tr>
 *   <tr><td>{@code password: hunter2} in a YAML</td><td><strong>missed</strong></td></tr>
 *   <tr><td>{@code POSTGRES_PASSWORD: correcthorse}</td><td><strong>missed</strong></td></tr>
 * </table>
 *
 * <p>gitleaks is an entropy-and-pattern detector. It is very good at a key that looks like a key,
 * and it is blind to a memorable password on a key named {@code password} - because there is
 * nothing about {@code hunter2} to detect. A memorable password is what a human commits, and the
 * two shapes it missed are exactly the shape this repository's configuration already has.
 *
 * <p>So the scanner cannot be the control for clause one. It is a net for what leaks past the
 * control, over the whole of history, which is a genuinely different and worthwhile job.
 *
 * <h2>The rule</h2>
 *
 * <p>Default-deny, on the same argument ADR-0019 makes for fields: a rule people must remember is
 * opt-in with extra steps. In any committed configuration file, a value assigned to a
 * credential-named key must be one of
 *
 * <ul>
 *   <li>a placeholder with no default - {@code ${FINAPP_DB_APP_PASSWORD}} - the deployed form;
 *   <li>a placeholder whose default is exactly {@link DatabaseCredentialGuard#MARKED_LOCAL_DEFAULT};
 *   <li>that same marked default as a bare literal, for the two file types that have no
 *       placeholder mechanism at all;
 *   <li>an expression, in a build script, that names no literal.
 * </ul>
 *
 * <p>Anything else fails the build. That covers the case the scanner misses, and it does a second
 * job for free: the marked default is written in six files across three languages, none of which
 * can import a Java constant. Holding every one of them to
 * {@link DatabaseCredentialGuard#MARKED_LOCAL_DEFAULT} is what actually makes that value
 * single-sourced. The comment in {@code compose.yaml} claiming it appeared in three places was
 * already wrong when this test was written; a comment cannot count and this can.
 *
 * <h2>What this cannot do</h2>
 *
 * <p>It reads keys, so it inherits the limit named in {@code SECURITY_ARCHITECTURE.md}: a
 * credential parked under an innocent key - {@code value:}, {@code arg:} - has nothing to match on.
 * That is the half gitleaks covers, when the value looks like a secret. Neither mechanism is
 * complete and they fail in different directions, which is the reason to run both.
 */
class CommittedConfigurationHoldsNoSecretTest {

    /**
     * Key-name words that mean "this holds a credential".
     *
     * <p>Deliberately the same vocabulary as {@code secretsAreWrapped}, minus the card-data and
     * authentication entries that describe runtime values rather than configuration. {@code key} is
     * absent for the reason ADR-0019 gives: an idempotency key is not a secret, and a rule with
     * false positives is a rule somebody turns off.
     */
    private static final Set<String> CREDENTIAL_WORDS =
            Set.of(
                    "password",
                    "passwd",
                    "passphrase",
                    "secret",
                    "apikey",
                    "privatekey",
                    "secretkey",
                    "signingkey",
                    "credential",
                    "credentials",
                    "token",
                    // An API credential in configuration is usually written as the header that
                    // carries it. Both were wrongly dropped from the first version of this list as
                    // "authentication data"; they are configuration credentials, and
                    // `authorization: Bearer abc123` went straight through until a probe found it.
                    "authorization",
                    "bearer");

    /** Splits camelCase, snake_case and SCREAMING_CASE into lower-case words. */
    private static final Pattern WORD_BOUNDARY =
            Pattern.compile("[_\\-.\\s]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * {@code key: value}, {@code key=value}, {@code key = "value"}.
     *
     * <p>Every occurrence on a line is examined, not just the first. The first version used
     * {@code find()} once and therefore only ever saw a line's <em>leading</em> token, which meant
     * {@code LOGIN PASSWORD 'x'} was read as the key {@code LOGIN} and skipped - so the SQL role
     * script, one of the four files this test names, <strong>was not being checked at all</strong>.
     * A real password planted there passed the build cleanly.
     */
    private static final Pattern KEY_VALUE =
            Pattern.compile("([A-Za-z][A-Za-z0-9_.\\-]*)\\s*[:=]\\s*(\\S[^\\n]*)");

    /**
     * SQL's separator-free form: {@code PASSWORD 'literal'}.
     *
     * <p>The value must be <strong>quoted</strong>. Accepting a bare word after whitespace would
     * make every sentence containing "password" an assignment - {@code the password of the
     * migrator role} would report the literal {@code of} - and a rule with false positives is a
     * rule somebody turns off (ADR-0019).
     */
    private static final Pattern KEY_QUOTED =
            Pattern.compile("([A-Za-z][A-Za-z0-9_.\\-]*)\\s+('[^']*'|\"[^\"]*\")");

    /** {@code ${VAR:default}} (Spring) and {@code ${VAR:-default}} (shell and Compose). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::-?([^}]*))?}");

    /**
     * A build script's placeholder: the name of the variable, not a default.
     *
     * <p>Removed before literals are extracted, exactly as {@link #PLACEHOLDER} is for YAML.
     * Without this, {@code environmentVariable("FINAPP_DB_APP_PASSWORD")} reads as a credential
     * literal - which is a false positive on the very line that proves the value is externalised.
     */
    private static final Pattern BUILD_SCRIPT_PLACEHOLDER =
            Pattern.compile("(?:environmentVariable|systemProperty|gradleProperty)\\(\"[^\"]*\"\\)");

    /** Block comments, so KDoc prose in a build script is not read as configuration. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

    /**
     * Where configuration lives. Discovered by extension, never by a list of known files.
     *
     * <p>{@code .sh} is here because {@code infra/scripts/} exists and shell is where
     * {@code PGPASSWORD=} and {@code Authorization: Bearer} actually get written. It was missing
     * from the first version, found by probing rather than by review.
     */
    private static final Set<String> SCANNED_EXTENSIONS =
            Set.of(".yaml", ".yml", ".properties", ".sql", ".kts", ".env", ".conf", ".ini", ".sh");

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".gradle", ".idea", "build", "out", "node_modules");

    @Test
    @DisplayName("every credential-named key in committed configuration is externalised")
    void noCommittedConfigurationHoldsACredentialLiteral() {
        List<String> offences = new ArrayList<>();
        List<Path> scanned = configurationFiles();

        for (Path file : scanned) {
            for (String offence : offencesIn(file)) {
                offences.add(relative(file) + ": " + offence);
            }
        }

        assertThat(offences)
                .as(
                        "a credential-named key must be a placeholder, or the marked local default"
                            + " (%s), and nothing else. gitleaks does not catch a memorable"
                            + " password on a password key - this does. See"
                            + " docs/architecture/SECRET_MANAGEMENT.md.",
                        DatabaseCredentialGuard.MARKED_LOCAL_DEFAULT)
                .isEmpty();
    }

    @Test
    @DisplayName("the scan actually reaches the files that hold the credentials")
    void theScanIsNotVacuous() {
        // Without this the test above passes triumphantly over an empty list - the failure mode
        // P0-TST-007 found in a privilege check and P0-TSK-008 found in a coverage guard. A
        // renamed directory, a wrong working directory or a broken walk would all look like
        // success. So the four files that are known to carry the marked default are named, and a
        // fifth that carries none is not: this asserts the walk finds real content, not a count.
        List<String> found = configurationFiles().stream().map(this::relative).toList();

        assertThat(found)
                .as("the walk must reach every file that actually carries a credential default")
                .contains(
                        "compose.yaml",
                        "app/src/main/resources/application.yaml",
                        "platform/build.gradle.kts",
                        "infra/postgres/initdb/00-roles.sql");
    }

    @Test
    @DisplayName("the marked local default is written identically in every file that uses it")
    void theMarkedDefaultIsSingleSourced() {
        // The single-sourcing claim, asserted from the other direction. The rule above rejects a
        // DIFFERENT literal; this proves the value is genuinely present in more than one file, so
        // that rejection is protecting something. Both are needed: a rule that would reject drift
        // in a value nobody uses any more is a rule that has quietly stopped mattering.
        List<String> carriers =
                configurationFiles().stream()
                        .filter(f -> read(f).contains(DatabaseCredentialGuard.MARKED_LOCAL_DEFAULT))
                        .map(this::relative)
                        .toList();

        assertThat(carriers)
                .as("the marked local default must be the value every local fallback uses")
                .hasSizeGreaterThanOrEqualTo(4)
                .contains("compose.yaml", "app/src/main/resources/application.yaml");
    }

    @ParameterizedTest
    @DisplayName("the shapes a credential is actually written in are rejected")
    @CsvSource(
            delimiter = '|',
            value = {
                // A plain value under a credential key: the shape gitleaks cannot see.
                "p.yaml | password: hunter2",
                "p.yaml | password: \"hunter2\"",
                "p.yaml | apiToken: abc123def",
                "p.properties | db.password=hunter2",
                "p.env | DB_PASSWORD=hunter2",
                // Compose's list form, where the assignment is inside a YAML sequence item.
                "p.yaml | - POSTGRES_PASSWORD=hunter2",
                // An API credential is usually written as the header carrying it.
                "p.yaml | authorization: Bearer abc123",
                // SQL has no placeholder mechanism and no separator, and the credential word is
                // never the first token on the line. This is what the single-find version missed.
                "p.sql | ALTER ROLE r WITH PASSWORD 'hunter2';",
                "p.sql | CREATE ROLE r WITH LOGIN PASSWORD 'hunter2';",
                // Shell, where PGPASSWORD is standard and has no word boundary to split on.
                "p.sh | PGPASSWORD=hunter2 psql -c 'select 1'",
                "p.sh | curl -H \"authorization: Bearer sk-abc123\" https://example",
                // A build script's literal sits on a fluent continuation line with no key on it.
                // The variable name is what identifies it, exactly as a YAML key does - so this
                // uses the name platform/build.gradle.kts actually uses. A build script credential
                // under an innocent variable name is the documented limit, not a separate hole.
                "p.kts | val migratorPassword = env(\"X\").orElse(\"hunter2\").get()"
            })
    void credentialShapesAreRejected(String fileName, String content) {
        assertThat(offencesIn(content, fileName.endsWith(".kts")))
                .as("this shape must be reported: %s", content)
                .isNotEmpty();
    }

    @ParameterizedTest
    @DisplayName("the shapes that are not credentials are left alone")
    @CsvSource(
            delimiter = '|',
            value = {
                // The two permitted forms.
                "p.yaml | password: ${FINAPP_DB_APP_PASSWORD}",
                "p.yaml | password: ${FINAPP_DB_APP_PASSWORD:local-development-only-not-a-secret}",
                "p.yaml | POSTGRES_PASSWORD: ${X:-local-development-only-not-a-secret}",
                "p.sql | LOGIN PASSWORD 'local-development-only-not-a-secret'",
                // A build script assigning a variable, and naming a variable, are both fine.
                "p.kts | password = migratorPassword",
                "p.kts | val pw = providers.environmentVariable(\"FINAPP_DB_APP_PASSWORD\").get()",
                // False positives that would make somebody turn the rule off. `passwordless` is
                // an authentication feature; `tokenizer` is load-bearing vocabulary in a platform
                // whose PCI scope rests on tokenisation.
                "p.yaml | passwordless: true",
                "p.yaml | tokenizer: standard",
                "p.yaml | idempotencyKey: abc123"
            })
    void innocentShapesAreAccepted(String fileName, String content) {
        assertThat(offencesIn(content, fileName.endsWith(".kts")))
                .as("this shape must NOT be reported: %s", content)
                .isEmpty();
    }

    @Test
    @DisplayName("the build declares as an input exactly the file types this rule scans")
    void theBuildInputMatchesWhatIsScanned() {
        // The extension list exists twice - here, and as the `committedConfiguration` input filter
        // in app/build.gradle.kts - because Gradle cannot read a Java constant. If they drift, an
        // edit to a file type only this side knows about leaves :app:test UP-TO-DATE and the build
        // goes green over a file nothing opened. That is the same "check that reports success for
        // work it did not do" defect this repository has now met four times, so it gets a guard
        // rather than a comment.
        String buildScript = read(repositoryRoot().resolve("app/build.gradle.kts"));
        int start = buildScript.indexOf("committedConfiguration");
        assertThat(start).as("the input declaration must still be findable").isPositive();

        String declaration = buildScript.substring(Math.max(0, start - 700), start);
        Set<String> declared = new java.util.TreeSet<>();
        Matcher include = Pattern.compile("\"\\*\\*/\\*(\\.[a-z]+)\"").matcher(declaration);
        while (include.find()) {
            declared.add(include.group(1));
        }

        assertThat(declared)
                .as("app/build.gradle.kts must declare every extension this rule scans")
                .isEqualTo(new java.util.TreeSet<>(SCANNED_EXTENSIONS));
    }

    @Test
    @DisplayName("prose is not configuration, so a comment naming a credential is not a finding")
    void proseIsNotAnAssignment() {
        // The reason KEY_QUOTED requires a quoted value. Accepting a bare word after whitespace
        // would report the literal `of` here, on every file that documents its own credentials.
        assertThat(offencesIn("/** The password of the migrator role. */\nval x = 1", true))
                .isEmpty();
        assertThat(offencesIn("-- the password below is a marked local default\n", false))
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // The rule itself.
    // ---------------------------------------------------------------------------------------

    private List<String> offencesIn(Path file) {
        return offencesIn(read(file), file.getFileName().toString().endsWith(".kts"));
    }

    /**
     * The rule, over content rather than a file, so the shape tables above exercise exactly the
     * code the repository walk runs. A second implementation for the tests would be a second thing
     * to keep correct, and the one that mattered would be the untested one.
     */
    private List<String> offencesIn(String raw, boolean buildScript) {
        String content = BLOCK_COMMENT.matcher(raw).replaceAll(" ");
        List<String> offences = new ArrayList<>();

        for (String line : logicalLines(content, buildScript)) {
            String stripped = withoutComment(line, buildScript).trim();
            for (Pattern form : List.of(KEY_VALUE, KEY_QUOTED)) {
                Matcher assignment = form.matcher(stripped);
                // while, not if: a line's first token is usually not the interesting one.
                while (assignment.find()) {
                    String key = assignment.group(1);
                    if (!namesACredential(key)) {
                        continue;
                    }
                    for (String literal : literalsIn(assignment.group(2), buildScript)) {
                        if (!literal.equals(DatabaseCredentialGuard.MARKED_LOCAL_DEFAULT)) {
                            offences.add(
                                    "key '"
                                            + key
                                            + "' is assigned a credential literal that is not the"
                                            + " marked local default");
                        }
                    }
                }
            }
        }
        return offences;
    }

    /**
     * The literal parts of a value: the default inside each placeholder, or the whole value when
     * there is no placeholder at all.
     *
     * <p>In a build script only a quoted string counts, because a bare token there is an
     * identifier - {@code password = migratorPassword} assigns a variable, not a credential. In
     * YAML, properties and SQL a bare token is a literal, which is why they are treated the other
     * way round.
     */
    private List<String> literalsIn(String rawValue, boolean buildScript) {
        String value = rawValue.trim();
        if (buildScript) {
            // The name of an environment variable is not a credential. Removed for the same
            // reason a ${...} shell is: what matters is the DEFAULT behind the placeholder.
            value = BUILD_SCRIPT_PLACEHOLDER.matcher(value).replaceAll(" ");
        }
        List<String> literals = new ArrayList<>();

        Matcher placeholder = PLACEHOLDER.matcher(value);
        boolean sawPlaceholder = false;
        while (placeholder.find()) {
            sawPlaceholder = true;
            String fallback = placeholder.group(2);
            if (fallback != null && !fallback.isBlank()) {
                literals.add(unquote(fallback.trim()));
            }
        }
        if (sawPlaceholder) {
            return literals;
        }

        if (buildScript) {
            Matcher quoted = Pattern.compile("\"([^\"]*)\"").matcher(value);
            while (quoted.find()) {
                if (!quoted.group(1).isBlank()) {
                    literals.add(quoted.group(1));
                }
            }
            return literals;
        }

        String bare = unquote(value);
        // A trailing SQL clause or YAML comment leaves noise behind the value; take the first
        // token, which is the value itself in every shape this scans.
        int space = bare.indexOf(' ');
        if (space > 0) {
            bare = bare.substring(0, space);
        }
        return bare.isBlank() ? literals : List.of(bare);
    }

    /**
     * Joins a build script's fluent continuations into one statement.
     *
     * <p>Without this, {@code .orElse("local-development-only-not-a-secret")} sits on a line with
     * no key on it and the rule never looks at it - which is precisely where three of this
     * repository's six local defaults live.
     */
    private List<String> logicalLines(String content, boolean buildScript) {
        List<String> lines = List.of(content.split("\r?\n", -1));
        if (!buildScript) {
            return lines;
        }
        List<String> joined = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.trim().startsWith(".") && !current.isEmpty()) {
                current.append(' ').append(line.trim());
            } else {
                if (!current.isEmpty()) {
                    joined.add(current.toString());
                }
                current = new StringBuilder(line);
            }
        }
        if (!current.isEmpty()) {
            joined.add(current.toString());
        }
        return joined;
    }

    private String withoutComment(String line, boolean buildScript) {
        int marker = buildScript ? line.indexOf("//") : line.indexOf('#');
        String withoutHash = marker >= 0 ? line.substring(0, marker) : line;
        int dashes = withoutHash.indexOf("--");
        return dashes >= 0 && !buildScript ? withoutHash.substring(0, dashes) : withoutHash;
    }

    private boolean namesACredential(String key) {
        for (String word : WORD_BOUNDARY.split(key)) {
            if (CREDENTIAL_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        // Splitting alone misses a run-together name with no boundary to split on. `PGPASSWORD`
        // is one word to the splitter and is the standard PostgreSQL variable, so a shell script
        // using it went straight through - found by probing.
        //
        // `endsWith`, not `contains`, and that is the whole of the care taken here: `contains`
        // would flag `passwordless`, which is an authentication feature and not a credential, and
        // would flag `tokenizer` in a platform whose PCI scope is built on tokenisation. A rule
        // with false positives is a rule somebody turns off (ADR-0019).
        String normalised = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return CREDENTIAL_WORDS.stream().anyMatch(normalised::endsWith);
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    // ---------------------------------------------------------------------------------------
    // Discovery.
    // ---------------------------------------------------------------------------------------

    private List<Path> configurationFiles() {
        Path root = repositoryRoot();
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            return SKIPPED_DIRECTORIES.contains(name)
                                    ? FileVisitResult.SKIP_SUBTREE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith)) {
                                found.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
        return new ArrayList<>(new LinkedHashSet<>(found));
    }

    /** The directory holding {@code settings.gradle.kts}: the repository root, by definition. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    private String relative(Path file) {
        return repositoryRoot().relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
