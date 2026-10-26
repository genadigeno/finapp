package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The tipping-off control at the log sink (`P13-TSK-018`; {@code INV-RSK-08}, {@code INV-AUD-02};
 * {@code PHASE_13_PLAN.md} section 11: "logs carry ids and counts"). <strong>Every {@code risk} log site is enumerated,
 * and each carries only ids, counts, bounded codes and an exception's class name</strong> - never a subject, a reason,
 * a note, an amount, a score, a rule's parameter, an exception's message or its stack trace (an SQL failure's message
 * quotes the refused row: {@code StorageFailuresCarryNoRowTest}'s finding).
 *
 * <ul>
 *   <li><strong>The risk module logs nothing</strong> - no logging API in {@code risk/src/main/java}: its domain answers
 *       and records; the composition decides what a line says.</li>
 *   <li><strong>Every log call in {@code app}'s risk package is in {@link #SITES}</strong> - its file, the format
 *       string and the exact argument expressions - and every registered site exists: a new line, or a line whose
 *       arguments changed, fails until it is argued for here.</li>
 *   <li><strong>Each registered argument has an admitted shape</strong> ({@link #ADMITTED}): an exception's
 *       {@code getClass().getSimpleName()}, an id's {@code value()}, an enum's {@code name()} or a closed-code accessor, a
 *       count - and none a forbidden one ({@link #FORBIDDEN}: a message, a {@code toString}, a subject's reference, a
 *       reason or a text). The format is a literal: no value is concatenated into it, and no argument is a bare
 *       throwable (a stack trace).</li>
 * </ul>
 *
 * Read from the sources, the {@code SendPermitsAreTheDatabasesTest} idiom: what a line logs is its argument text.
 */
@Tag("architecture")
@DisplayName("every risk log site is enumerated and carries only ids, counts and codes (P13-TSK-018, INV-RSK-08)")
class RiskLogSitesAreEnumeratedTest {

    /** A log call - its level and the opening parenthesis; the arguments are read by balance from there. */
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\(");

    /** What marks a logging API in a source. */
    private static final Pattern LOGGING_API = Pattern.compile(
            "org\\.slf4j|java\\.util\\.logging|System\\.Logger|System\\.(out|err)\\b|@Slf4j|@Log4j|LoggerFactory");

    /**
     * Every risk log site: {@code File.java|format|argument|argument…}, each argument exactly as written (whitespace
     * collapsed). The reason each is safe is the line's own comment in the source; the shapes are held below.
     */
    static final Set<String> SITES = Set.of(
            "CaseLegDriver.java|\"Risk case fold of a queued alert met it moved\"",
            "CaseLegDriver.java|\"Risk case fold of a queued alert failed: {}\"|failure.getClass().getSimpleName()",
            "CaseProhibitionDriver.java|\"Risk case prohibition deferred to the case leg: {}\""
                    + "|failure.getClass().getSimpleName()",
            "RiskActionDriver.java|\"Risk action claim failed: {}\"|failure.getClass().getSimpleName()",
            "RiskActionDriver.java|\"Risk action dispatch failed: {}\"|failure.getClass().getSimpleName()",
            "RiskActionDriver.java|\"Risk action record failed: {}\"|failure.getClass().getSimpleName()",
            "RiskActionSchedule.java|\"Risk actions driven: {}\"|driven",
            // P13-TSK-021: the filing's drive - a claim, a call or a record that failed: the exception's class alone,
            // never a filing reference, the authority's answer or the report's content.
            "RegulatoryFilingDriver.java|\"Regulatory filing claim failed: {}\"|failure.getClass().getSimpleName()",
            "RegulatoryFilingDriver.java|\"Regulatory filing call failed: {}\"|failure.getClass().getSimpleName()",
            "RegulatoryFilingDriver.java|\"Regulatory filing record failed: {}\"|failure.getClass().getSimpleName()",
            // P13-TSK-022: the rescreening leg - a batch, a claim, a kyc ask or a conclusion that failed: the exception's
            // class alone, never a party, a list, a screening or a key; the leg's count of conclusions.
            "RescreeningDriver.java|\"Rescreening enqueue batch failed: {}\"|failure.getClass().getSimpleName()",
            "RescreeningDriver.java|\"Rescreening claim failed: {}\"|failure.getClass().getSimpleName()",
            "RescreeningDriver.java|\"Rescreening conclusion failed: {}\"|failure.getClass().getSimpleName()",
            "RescreeningMetrics.java|\"Rescreening kyc ask failed: {}\"|cause",
            "RescreeningMetrics.java|\"The rescreening backlog could not be read: {}\""
                    + "|unreadable.getClass().getSimpleName()",
            "RiskActionSchedule.java|\"Rescreening permits concluded: {}\"|done",
            "RiskActionSchedule.java|\"Rescreening leg failed: {}\"|failure.getClass().getSimpleName()",
            "RiskActionSchedule.java|\"Risk action claim failed: {}\"|failure.getClass().getSimpleName()",
            "RiskActionSchedule.java|\"Risk action sweep failed: {}\"|failure.getClass().getSimpleName()",
            "RiskEvaluationMetrics.java|\"risk evaluation fail-safe recorded: assessment={} operation={} cause={}\""
                    + "|report.assessment().value()|operation.name()|report.unavailable().map(Enum::name).orElse(\"NONE\")",
            "RiskEvaluationMetrics.java|\"risk alerts unraised behind their savepoint: assessment={} operation={}"
                    + " cause={}\"|assessment.value()|operation.name()|cause",
            "RiskMonitoringMetrics.java|\"The monitoring figures could not be read: {}\""
                    + "|unreadable.getClass().getSimpleName()",
            // P13-TSK-020: a party's rule leg rolled back alone - the cause's class name, never the party or a message.
            "RiskMonitoringMetrics.java|\"Risk monitoring subject failed: {}\"|cause",
            "RiskCaseBacklogMetrics.java|\"The risk case backlog could not be read: {}\""
                    + "|unreadable.getClass().getSimpleName()",
            "RiskMonitoringSchedule.java|\"Risk monitoring intake batch failed: {}\"|failure.getClass().getSimpleName()",
            "RiskMonitoringSchedule.java|\"Risk case leg acts: {}\"|done",
            "RiskMonitoringSchedule.java|\"Risk case leg failed: {}\"|failure.getClass().getSimpleName()",
            "RiskMonitoringSchedule.java|\"Risk profile recompute failed: {}\""
                    + "|failed.getCause().getClass().getSimpleName()",
            "RiskMonitoringSchedule.java|\"Risk profile recompute deferral failed: {}\""
                    + "|deferral.getClass().getSimpleName()",
            "RiskMonitoringSchedule.java|\"Risk profile recompute failed: {}\"|failure.getClass().getSimpleName()",
            "RiskMonitoringSchedule.java|\"Risk profile recompute: {}\"|outcome.get().kind()",
            "RiskMonitoringSchedule.java|\"Risk monitoring sweep failed: {}\"|failure.getClass().getSimpleName()",
            "RiskPolicyMetrics.java|\"risk policy gauge unreadable: {}\"|unreadable.getClass().getSimpleName()",
            "RiskSignalIntake.java|\"No risk signal from {} {}: its payment was never judged\"|event.eventType()"
                    + "|event.eventId().value()",
            "RiskSignalIntake.java|\"Risk signal {} from {} {}: {}\"|signal.kind()|event.eventType()"
                    + "|event.eventId().value()|outcome");

    /**
     * The shapes an argument may take: an exception's class name; an id's value; an enum's name or a closed code's
     * accessor ({@code kind()}, {@code eventType()} - an event type is a code); a count or a code held in a local the
     * line's method computes ({@code driven}, {@code done}, {@code outcome}, {@code cause} - each argued in its source).
     */
    static final List<Pattern> ADMITTED = List.of(
            Pattern.compile("[a-z][A-Za-z]*(\\.getCause\\(\\))?\\.getClass\\(\\)\\.getSimpleName\\(\\)"),
            Pattern.compile("[a-z][A-Za-z.()]*\\.value\\(\\)"),
            Pattern.compile("[a-z][A-Za-z.()]*\\.(name|kind|eventType)\\(\\)"),
            Pattern.compile("report\\.unavailable\\(\\)\\.map\\(Enum::name\\)\\.orElse\\(\"NONE\"\\)"),
            Pattern.compile("driven|done|outcome|cause"));

    /** Never in a risk log line, whatever its shape otherwise. */
    static final Pattern FORBIDDEN = Pattern.compile(
            "getMessage|getLocalizedMessage|toString|printStackTrace|\\bref\\(|subject|reason\\(|text\\(|amount|score"
                    + "|note|correlation|actor|party|getStackTrace");

    @Test
    @DisplayName("the risk module logs nothing: no logging API in its production sources")
    void theRiskModuleLogsNothing() {
        List<Path> sources = sources(repositoryRoot().resolve("risk/src/main/java"));
        assertThat(sources).as("the guard reads risk's sources").hasSizeGreaterThan(100);
        List<String> logging = new ArrayList<>();
        for (Path source : sources) {
            if (LOGGING_API.matcher(read(source)).find()) {
                logging.add(source.getFileName().toString());
            }
        }
        assertThat(logging).as("a risk domain class that logs").isEmpty();
    }

    @Test
    @DisplayName("every log call in app's risk package is a registered site, and every registered site exists")
    void everySiteIsRegistered() {
        Set<String> found = sitesIn(repositoryRoot().resolve("app/src/main/java/com/finapp/app/risk"));
        assertThat(found).as("the guard sees the risk log sites").hasSizeGreaterThanOrEqualTo(20);
        assertThat(found).as("a log site not in SITES - register it, with its shape argued").isSubsetOf(SITES);
        assertThat(SITES).as("a registered site that no longer exists").isSubsetOf(found);
    }

    @Test
    @DisplayName("each registered site's format is a literal and each argument an admitted shape, none forbidden")
    void everyArgumentIsAdmitted() {
        for (String site : SITES) {
            String[] parts = site.split("\\|");
            assertThat(parts[1]).as(site).startsWith("\"").endsWith("\"").doesNotContain("\" +").doesNotContain("+ \"");
            for (int i = 2; i < parts.length; i++) {
                assertThat(admitted(parts[i])).as("%s: the argument %s", parts[0], parts[i]).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the rule has teeth: a message, a subject's reference, a reason, a bare throwable and a concatenated"
            + " format are each refused")
    void theRuleHasTeeth() {
        for (String argument : List.of("failure.getMessage()", "alert.subject().ref()", "proposal.reason()", "failure",
                "riskCase.toString()", "action.amountMinor()")) {
            assertThat(admitted(argument)).as(argument).isFalse();
        }
        Set<String> planted = sitesOf("Planted.java", "class Planted { void x() { log.warn(\"failed: \" + subject,"
                + " failure); log.info(\"ok {}\", alert.id().value()); } }");
        assertThat(planted).containsExactlyInAnyOrder("Planted.java|\"failed: \" + subject|failure",
                "Planted.java|\"ok {}\"|alert.id().value()");
        assertThat(SITES).doesNotContainAnyElementsOf(planted);
    }

    // ------------------------------------------------------------------ the reading

    static boolean admitted(String argument) {
        if (FORBIDDEN.matcher(argument).find()) {
            return false;
        }
        return ADMITTED.stream().anyMatch(shape -> shape.matcher(argument).matches());
    }

    private static Set<String> sitesIn(Path directory) {
        Set<String> sites = new TreeSet<>();
        for (Path source : sources(directory)) {
            sites.addAll(sitesOf(source.getFileName().toString(), read(source)));
        }
        return sites;
    }

    /** Every log call of {@code text}: the file, then each top-level argument, whitespace collapsed. */
    static Set<String> sitesOf(String file, String text) {
        String code = text.replaceAll("(?m)^\\s*//.*$", "").replaceAll("/\\*(?s).*?\\*/", "");
        Set<String> sites = new TreeSet<>();
        Matcher call = LOG_CALL.matcher(code);
        while (call.find()) {
            List<String> arguments = arguments(code, call.end());
            sites.add(file + "|" + String.join("|", arguments));
        }
        return sites;
    }

    /** The top-level arguments from {@code start} (just after the opening parenthesis) to its closing one. */
    private static List<String> arguments(String code, int start) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\') {
                    current.append(code.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                if (depth == 0) {
                    arguments.add(collapsed(current.toString()));
                    return arguments;
                }
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                arguments.add(collapsed(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        throw new IllegalStateException("an unbalanced log call");
    }

    private static String collapsed(String argument) {
        // Adjacent literals joined: a format wrapped across lines is one literal.
        return argument.replaceAll("\\s+", " ").strip().replaceAll("\" \\+ \"", "");
    }

    private static List<Path> sources(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
    private static Path repositoryRoot() {
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
