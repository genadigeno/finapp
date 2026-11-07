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
 *   <li>a <strong>repository path</strong>, relative to the root, optionally {@code #method}: the
 *       file must exist, and the method must be <em>declared</em> in it, not merely named;
 *   <li>a <strong>task id</strong> ({@code P15-TSK-019}, {@code P14-REM-004}, {@code X-TSK-010}):
 *       it must be an entry header in {@code docs/project/BACKLOG.md}, with a readable status.
 * </ul>
 *
 * <p>Anything else in backticks is {@link Unreadable}, and every caller fails on it. A parser that skips
 * what it cannot read lets the register look complete while it checks nothing (the {@code P1-TSK-024}
 * lesson every register guard here applies).
 */
final class EvidenceCitations {

    /**
     * A repository path: at least one character, a file extension, and an optional {@code #method}.
     * Root files ({@code compose.yaml}) and dot-directories ({@code .github/...}) are admitted.
     */
    static final Pattern PATH =
            Pattern.compile("^([A-Za-z0-9_.][A-Za-z0-9_./-]*\\.[A-Za-z0-9]+)(?:#([A-Za-z_][A-Za-z0-9_]*))?$");

    /** A backlog id: a phase item ({@code P15-TSK-019}, {@code P14-REM-004}) or a cross-cutting one. */
    static final Pattern TASK_ID = Pattern.compile("^(?:P\\d+-(?:TSK|TST|DOC|REM)-\\d{3}|X-TSK-\\d{3})$");

    /** Every backticked token in a cell. */
    static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /** A backlog entry's header line: {@code **P15-TSK-019 — Title** — `PLANNED`}. */
    private static final Pattern ENTRY_HEADER =
            Pattern.compile("^\\*\\*((?:P\\d+-(?:TSK|TST|DOC|REM)-\\d{3})|X-TSK-\\d{3}) — ");

    /**
     * The status after the bold title. The title can span lines and can itself hold backticks
     * ({@code The `kind` deployment}), so the status is the first backticked token after the
     * <em>closing</em> bold marker, never the first backticked token on the line.
     */
    private static final Pattern STATUS_AFTER_TITLE = Pattern.compile("^\\s*—\\s*`([A-Z_]+)`");

    /** The statuses under which a task still owns a promise (`PHASE_GATES.md` §1's vocabulary). */
    static final Set<String> LIVE_STATUSES = Set.of("PLANNED", "READY", "IN_PROGRESS");

    /** Files whose {@code #method} citations can be checked: a declaration in Java or Kotlin. */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".java", ".kt");

    private EvidenceCitations() {}

    /** One parsed citation. */
    sealed interface Citation permits PathCitation, TaskCitation, Unreadable {}

    /** A repository path, with the method it names if any. */
    record PathCitation(String path, Optional<String> method) implements Citation {}

    /** A backlog id. */
    record TaskCitation(String id) implements Citation {}

    /** A backticked token that is neither: a failure for every caller. */
    record Unreadable(String token) implements Citation {}

    /** Every backticked citation in a cell, in order. */
    static List<Citation> citationsIn(String cell) {
        List<Citation> citations = new ArrayList<>();
        Matcher token = BACKTICKED.matcher(cell);
        while (token.find()) {
            citations.add(parse(token.group(1).trim()));
        }
        return citations;
    }

    static Citation parse(String token) {
        if (TASK_ID.matcher(token).matches()) {
            return new TaskCitation(token);
        }
        Matcher path = PATH.matcher(token);
        if (path.matches()) {
            return new PathCitation(path.group(1), Optional.ofNullable(path.group(2)));
        }
        return new Unreadable(token);
    }

    /**
     * Why a path citation does not resolve, or empty when it does. A {@code #method} must be declared in
     * the file: a line whose leading tokens are a return type or modifiers and then the name and an opening
     * parenthesis. A call ({@code x.name(}), an assignment and a {@code return name(} are not declarations.
     */
    static Optional<String> problemWith(Path root, PathCitation citation) {
        Path file = root.resolve(citation.path()).normalize();
        if (!file.startsWith(root)) {
            return Optional.of("leaves the repository");
        }
        if (!Files.isRegularFile(file)) {
            return Optional.of("is not a file in the repository");
        }
        if (citation.method().isEmpty()) {
            return Optional.empty();
        }
        String name = citation.method().get();
        if (SOURCE_EXTENSIONS.stream().noneMatch(citation.path()::endsWith)) {
            return Optional.of("names #" + name + " in a file that is not Java or Kotlin source");
        }
        Pattern declaration = Pattern.compile(
                "(?m)^[ \\t]*(?!return\\b)(?:[\\w@<>\\[\\],.?]+[ \\t]+)+" + Pattern.quote(name) + "[ \\t]*\\(");
        return declaration.matcher(read(file)).find()
                ? Optional.empty()
                : Optional.of("declares no method " + name);
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
