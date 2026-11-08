package com.finapp.app.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a register document cites its evidence, and how a citation is resolved against the repository
 * ({@code P15-TSK-001}).
 *
 * <p>Two documents cite evidence in one grammar: {@code docs/architecture/THREAT_MODEL.md} (held by
 * {@link ThreatModelIsCompleteTest}) and {@code docs/architecture/PRODUCTION_READINESS.md}, which
 * {@code P15-TSK-034} fills and whose guard reuses this class (its backlog entry: "the threat-model
 * guard's mechanism reused"). A citation is a backticked token, and it is exactly one of:
 *
 * <ul>
 *   <li>a <strong>repository path</strong>, relative to the root, with or without an extension
 *       ({@code app/Dockerfile}, {@code .github/CODEOWNERS}), optionally {@code #member}: the file must
 *       exist, and the member must be a <em>test</em> declared in it - a method annotated as a JUnit test
 *       or an {@code @ArchTest} rule field - never a helper or a call. A whole test source cited without a
 *       member must declare at least one test;
 *   <li>a <strong>content pin</strong>, {@code contains: text} or {@code lacks: text}, attached to the
 *       path cited just before it: that file must contain, or must not contain, the text;
 *   <li>a <strong>task id</strong> ({@code P15-TSK-019}, {@code P14-REM-004}, {@code X-TSK-010}): it must
 *       be an entry header in {@code docs/project/BACKLOG.md}, with a readable status;
 *   <li>a <strong>postponed issue</strong> ({@code PI-004}): a header in
 *       {@code docs/project/POSTPONED_ISSUES.md};
 *   <li>an <strong>owner item</strong> ({@code owner item 2}): a numbered item of the citing document's
 *       owner items.
 * </ul>
 *
 * <p>Anything else in backticks is {@link Unreadable}, and every caller fails on it. A parser that skips
 * what it cannot read lets the register look complete while it checks nothing (the {@code P1-TSK-024}
 * lesson every register guard here applies).
 */
final class EvidenceCitations {

    /** A repository path, extension optional, and an optional {@code #member}. */
    static final Pattern PATH =
            Pattern.compile("^([A-Za-z0-9_.][A-Za-z0-9_./-]*)(?:#([A-Za-z_][A-Za-z0-9_]*))?$");

    /** A backlog id: a phase item ({@code P15-TSK-019}, {@code P14-REM-004}) or a cross-cutting one. */
    static final Pattern TASK_ID = Pattern.compile("^(?:P\\d+-(?:TSK|TST|DOC|REM)-\\d{3}|X-TSK-\\d{3})$");

    /** A postponed issue: a header of {@code POSTPONED_ISSUES.md}. */
    static final Pattern POSTPONED_ISSUE = Pattern.compile("^PI-\\d{3}$");

    /** An owner item of the citing document. */
    static final Pattern OWNER_ITEM = Pattern.compile("^owner item (\\d+)$");

    /** A content pin on the path cited just before it. */
    static final Pattern PIN = Pattern.compile("^(contains|lacks): (.+)$");

    /** Every backticked token in a cell. */
    static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /** A backlog entry's header line: {@code **P15-TSK-019 — Title** — `PLANNED`}. */
    private static final Pattern ENTRY_HEADER =
            Pattern.compile("^\\*\\*((?:P\\d+-(?:TSK|TST|DOC|REM)-\\d{3})|X-TSK-\\d{3}) — ");

    /** A postponed issue's header line in {@code POSTPONED_ISSUES.md}. */
    private static final Pattern ISSUE_HEADER = Pattern.compile("(?m)^## (PI-\\d{3}) — ");

    /**
     * The status after the bold title. The title can span lines and can itself hold backticks
     * ({@code The `kind` deployment}), so the status is the first backticked token after the
     * <em>closing</em> bold marker, never the first backticked token on the line.
     */
    private static final Pattern STATUS_AFTER_TITLE = Pattern.compile("^\\s*—\\s*`([A-Z_]+)`");

    /** The statuses under which a task still owns a promise (`PHASE_GATES.md` §1's vocabulary). */
    static final Set<String> LIVE_STATUSES = Set.of("PLANNED", "READY", "IN_PROGRESS");

    /** Files whose {@code #member} citations can be checked: a declaration in Java or Kotlin. */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".java", ".kt");

    /** The annotations that make a declaration a test the build runs. */
    private static final Pattern TEST_ANNOTATION =
            Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|ArchTest)\\b");

    /** A test annotation on a line of its own, as code writes it: never one a comment or a javadoc mentions. */
    private static final Pattern DECLARED_TEST_ANNOTATION =
            Pattern.compile("(?m)^[ \\t]*@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate|ArchTest)\\b");

    /** Leading words of a statement, never of a declaration: a call line is not evidence. */
    private static final Set<String> STATEMENT_WORDS =
            Set.of("new", "else", "assert", "return", "throw", "if", "for", "while", "case", "do", "try");

    private EvidenceCitations() {}

    /** One parsed citation. */
    sealed interface Citation permits PathCitation, TaskCitation, IssueCitation, OwnerItemCitation, Unreadable {}

    /** A repository path, the member it names if any, and the content pins that follow it. */
    record PathCitation(String path, Optional<String> member, List<Pin> pins) implements Citation {

        /** A file under a module's test sources: a test, not a bare file. */
        boolean isTestSource() {
            return path.contains("/src/test/") && SOURCE_EXTENSIONS.stream().anyMatch(path::endsWith);
        }
    }

    /** {@code contains: text} or {@code lacks: text}. */
    record Pin(boolean mustContain, String text) {}

    /** A backlog id. */
    record TaskCitation(String id) implements Citation {}

    /** A postponed issue id. */
    record IssueCitation(String id) implements Citation {}

    /** An owner item number. */
    record OwnerItemCitation(int number) implements Citation {}

    /** A backticked token that is none of the above, or a pin with no path before it: a failure. */
    record Unreadable(String token) implements Citation {}

    /** Every backticked citation in a cell, in order, each pin folded into the path before it. */
    static List<Citation> citationsIn(String cell) {
        List<Citation> citations = new ArrayList<>();
        Matcher token = BACKTICKED.matcher(cell);
        while (token.find()) {
            String text = token.group(1).trim();
            Matcher pin = PIN.matcher(text);
            if (pin.matches()) {
                Citation previous = citations.isEmpty() ? null : citations.get(citations.size() - 1);
                if (previous instanceof PathCitation path) {
                    List<Pin> pins = new ArrayList<>(path.pins());
                    pins.add(new Pin(pin.group(1).equals("contains"), pin.group(2)));
                    citations.set(citations.size() - 1, new PathCitation(path.path(), path.member(), List.copyOf(pins)));
                } else {
                    citations.add(new Unreadable(text + " (a pin follows a path, and nothing else)"));
                }
                continue;
            }
            citations.add(parse(text));
        }
        return citations;
    }

    static Citation parse(String token) {
        if (TASK_ID.matcher(token).matches()) {
            return new TaskCitation(token);
        }
        if (POSTPONED_ISSUE.matcher(token).matches()) {
            return new IssueCitation(token);
        }
        Matcher ownerItem = OWNER_ITEM.matcher(token);
        if (ownerItem.matches()) {
            return new OwnerItemCitation(Integer.parseInt(ownerItem.group(1)));
        }
        Matcher path = PATH.matcher(token);
        if (path.matches()) {
            return new PathCitation(path.group(1), Optional.ofNullable(path.group(2)), List.of());
        }
        return new Unreadable(token);
    }

    /**
     * Why a path citation does not resolve, or empty when it does: the file exists in the repository, its
     * {@code #member} is a test declared in it, and every pin holds.
     */
    static Optional<String> problemWith(Path root, PathCitation citation) {
        Path file = root.resolve(citation.path()).normalize();
        if (!file.startsWith(root)) {
            return Optional.of("leaves the repository");
        }
        if (!Files.isRegularFile(file)) {
            return Optional.of("is not a file in the repository");
        }
        String content = read(file);
        for (Pin pin : citation.pins()) {
            if (content.contains(pin.text()) != pin.mustContain()) {
                return Optional.of((pin.mustContain() ? "no longer contains" : "now contains") + " the pinned text '"
                        + pin.text() + "'");
            }
        }
        if (citation.member().isEmpty()) {
            // A whole test file counts as a test only if it declares one: a helper or a fixture under src/test is a
            // bare file that happens to sit beside the tests.
            return citation.isTestSource() && !DECLARED_TEST_ANNOTATION.matcher(content).find()
                    ? Optional.of("is a test source that declares no test (no @Test, @ParameterizedTest, @ArchTest or"
                            + " other test annotation)")
                    : Optional.empty();
        }
        String name = citation.member().get();
        if (SOURCE_EXTENSIONS.stream().noneMatch(citation.path()::endsWith)) {
            return Optional.of("names #" + name + " in a file that is not Java or Kotlin source");
        }
        return testDeclared(content, name)
                ? Optional.empty()
                : Optional.of("declares no test #" + name + " (a method annotated as a test, or an @ArchTest rule)");
    }

    /**
     * Whether {@code name} is declared in {@code source} as a test: a method whose declaration line starts with
     * modifiers or a return type (never a statement word such as {@code new}, {@code return} or {@code else}),
     * or an {@code ArchRule} field, and whose annotation block - the lines above it back to the previous member
     * - carries a test annotation.
     */
    static boolean testDeclared(String source, String name) {
        String[] lines = source.split("\\R", -1);
        Pattern method = Pattern.compile(
                "^[ \\t]*((?:[\\w@<>\\[\\],.?]+[ \\t]+)+)" + Pattern.quote(name) + "[ \\t]*\\(");
        Pattern rule = Pattern.compile("^[ \\t]*(?:[\\w]+[ \\t]+)*ArchRule[ \\t]+" + Pattern.quote(name) + "[ \\t]*=");
        for (int i = 0; i < lines.length; i++) {
            Matcher declaration = method.matcher(lines[i]);
            boolean isMethod = declaration.find()
                    && !STATEMENT_WORDS.contains(declaration.group(1).trim().split("[ \\t]+")[0]);
            boolean isRule = rule.matcher(lines[i]).find();
            if ((isMethod || isRule) && annotatedAsTest(lines, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotatedAsTest(String[] lines, int declaration) {
        if (TEST_ANNOTATION.matcher(lines[declaration]).find()) {
            return true;
        }
        // Back to the previous member: a blank line, a statement's end, a lone closing brace, or the opening line of a
        // class or method. Comment lines inside the annotation block (an @ValueSource's own comments) are passed over,
        // and so is an annotation argument's opening brace ("strings = {").
        for (int i = declaration - 1; i >= 0 && i >= declaration - 40; i--) {
            String line = lines[i].trim();
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }
            if (line.isEmpty() || line.endsWith(";") || line.equals("}")
                    || (line.endsWith("{") && (line.contains(") ") || line.contains("class ")))) {
                return false;
            }
            if (TEST_ANNOTATION.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every backlog entry header and its status, in document order. An entry whose status cannot be read
     * maps to {@code UNREADABLE}, so a caller can refuse it by name rather than miss it.
     */
    static Map<String, String> backlogStatuses(Path backlog) {
        String[] lines = read(backlog).split("\\R", -1);
        Map<String, String> statuses = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher header = ENTRY_HEADER.matcher(lines[i]);
            if (!header.find()) {
                continue;
            }
            StringBuilder joined = new StringBuilder(lines[i]);
            for (int next = i + 1; next < Math.min(lines.length, i + 3); next++) {
                joined.append(' ').append(lines[next]);
            }
            int closing = joined.indexOf("**", 2);
            Matcher status = closing < 0
                    ? null
                    : STATUS_AFTER_TITLE.matcher(joined.substring(closing + 2));
            statuses.put(header.group(1), status != null && status.find() ? status.group(1) : "UNREADABLE");
        }
        return statuses;
    }

    /** Every postponed issue id {@code POSTPONED_ISSUES.md} holds as a header. */
    static Set<String> postponedIssues(Path register) {
        Set<String> issues = new TreeSet<>();
        Matcher header = ISSUE_HEADER.matcher(read(register));
        while (header.find()) {
            issues.add(header.group(1));
        }
        return issues;
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
