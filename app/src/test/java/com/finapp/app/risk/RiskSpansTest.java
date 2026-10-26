package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.telemetry.Spans;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.risk.RiskSpans;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Risk's spans (`P13-TSK-024`; {@code PHASE_13_PLAN.md} section 15: "{@code RiskFlowScope} per step, no attributes";
 * bnpl's {@code BnplSpansTest}): every {@link RiskSpans} constant is recorded somewhere in main code - {@code app}'s
 * doors, sweeps and steps through {@link RiskFlowScope}, {@code risk}'s own steps (T-eval, T-advise, the monitoring rule
 * leg, the alert's raise) through the platform {@link Spans} port; every recording carries NO attribute, so no score,
 * rule, reason, amount, party, subject, case or report id can reach the trace backend ({@code INV-RSK-08},
 * {@code INV-AUD-02}); and every {@code app} class handed a {@code RiskFlowScope} records through it.
 */
@DisplayName("risk's spans are recorded, by name, with no attribute (P13-TSK-024)")
class RiskSpansTest {

    /** A span recorded through the port: {@code spans.within(<name>, <attributes>, ...}. */
    private static final Pattern WITHIN = Pattern.compile("\\bspans\\.within\\(\\s*([^,]+),\\s*([^,]+),");

    /** A step recorded through the scope: {@code .door(RiskSpans.X}, {@code .sweep(...}, {@code .step(...}. */
    private static final Pattern SCOPED = Pattern.compile("\\.(door|sweep|step)\\(\\s*(RiskSpans\\.[A-Z_]+|span)\\b");

    @Test
    @DisplayName("every RiskSpans constant is a distinct risk.* name recorded somewhere in main code")
    void everySpanIsRecorded() throws Exception {
        Map<String, String> constants = new TreeMap<>();
        for (Field field : RiskSpans.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        assertThat(constants).hasSizeGreaterThanOrEqualTo(30);
        assertThat(constants.values()).doesNotHaveDuplicates()
                .allSatisfy(span -> assertThat(span).matches("risk(\\.[a-z]+)+"));
        String sources = sources(appRisk()) + "\n" + sources(riskModule());
        assertThat(constants.keySet()).allSatisfy(constant -> assertThat(sources)
                .as("RiskSpans.%s is recorded somewhere in main code", constant).contains("RiskSpans." + constant));
    }

    @Test
    @DisplayName("every span recorded through the port in risk's and app's risk code carries no attribute - Map.of()")
    void everyRecordingCarriesNoAttribute() throws Exception {
        int seen = 0;
        for (Path root : List.of(appRisk(), riskModule())) {
            for (Path file : javaFiles(root)) {
                Matcher within = WITHIN.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (within.find()) {
                    seen++;
                    assertThat(within.group(2).strip()).as("%s records %s", file.getFileName(), within.group(1))
                            .isEqualTo("Map.of()");
                }
            }
        }
        assertThat(seen).as("the scope's three legs and risk's own steps").isGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("RiskFlowScope's door, sweep and step each record the span by name with no attribute, and run the work")
    void theScopeRecordsNoAttribute() {
        Recording spans = new Recording();
        RiskFlowScope scope = new RiskFlowScope(spans);
        assertThat(scope.door(RiskSpans.CASE_OPEN, () -> "door")).isEqualTo("door");
        assertThat(scope.sweep(RiskSpans.MONITORING_INTAKE, () -> "sweep")).isEqualTo("sweep");
        List<String> ran = new ArrayList<>();
        scope.step(RiskSpans.SIGNAL_RECORD, () -> ran.add("step"));
        assertThat(ran).containsExactly("step");
        assertThat(spans.names).containsExactly(RiskSpans.CASE_OPEN, RiskSpans.MONITORING_INTAKE, RiskSpans.SIGNAL_RECORD);
        assertThat(spans.attributes).allSatisfy(attributes -> assertThat(attributes).isEmpty());
    }

    @Test
    @DisplayName("every app class handed a RiskFlowScope records its steps through it, each by a RiskSpans name")
    void everyHolderOfTheScopeUsesIt() throws Exception {
        int holders = 0;
        for (Path file : javaFiles(appRisk())) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            if (!source.contains("private final RiskFlowScope ") || name.equals("RiskFlowScope.java")) {
                continue;
            }
            holders++;
            Matcher scoped = SCOPED.matcher(source);
            assertThat(scoped.find()).as("%s holds a RiskFlowScope and records a step through it", name).isTrue();
        }
        assertThat(holders).as("the desks, the drivers, the schedule and the intake").isGreaterThanOrEqualTo(15);
    }

    /**
     * The transaction-opening methods deliberately outside a span call, each argued - keyed {@code File#method}. Every
     * other {@code .inTransaction(} / {@code .inSnapshot(} in {@code app}'s risk code is lexically inside a
     * {@code flow.door(}, {@code flow.sweep(}, {@code scope.step(} or {@code spans.within(} call, or in a private method
     * every call of which is (the desks' {@code keyed} helpers, run inside their door's lambda).
     */
    static final Map<String, String> UNSPANNED = Map.ofEntries(
            // A gauge's reading - a meter's refresh off the scrape thread, no flow and no step of one.
            Map.entry("RescreeningMetrics.java#over", "a gauge's reading"),
            Map.entry("RiskProfileMetrics.java#over", "a gauge's reading"),
            Map.entry("RiskProofMetrics.java#over", "a gauge's reading"),
            Map.entry("RiskAgeMetrics.java#over", "a gauge's reading"),
            // kyc's re-screening port: RescreeningRun.ask calls it inside RescreeningDriver's risk.rescreen span.
            Map.entry("KycPartyRescreening.java#ask", "inside the driver's risk.rescreen span, one class up"),
            // The door's answer: the row its spanned, keyed act wrote, re-read for the receipt after the act commits.
            Map.entry("RiskActionDesk.java#receipt", "the receipt's re-read after the spanned act"),
            Map.entry("RiskAlertDesk.java#receipt", "the receipt's re-read after the spanned act"),
            Map.entry("RiskCaseDesk.java#receipt", "the receipt's re-read after the spanned act"),
            Map.entry("RiskOverrideDesk.java#receipt", "the receipt's re-read after the spanned act"),
            Map.entry("RiskReportDesk.java#receipt", "the receipt's re-read after the spanned act"),
            // A spanned read's audit record (risk.*Read, risk.Replayed) or a refusal's FAILED record, written in a
            // transaction of its own before the answer - the door's trail, its work already spanned.
            Map.entry("RiskAssessmentDesk.java#replayDay", "the spanned replay's audit record"),
            Map.entry("RiskAssessmentDesk.java#replay", "the spanned replay's audit record"),
            Map.entry("RiskAssessmentDesk.java#refused", "a refusal's FAILED record"),
            Map.entry("RiskEvidenceDesk.java#read", "the spanned read's audit record or refusal"),
            Map.entry("RiskOperationsReportDesk.java#served", "the spanned report's audit record or refusal"),
            Map.entry("RiskOverrideDesk.java#refusalsRecorded", "a refusal's FAILED record"),
            Map.entry("RiskProfileDesk.java#read", "the spanned read's audit record or refusal"),
            Map.entry("RiskQueueDesk.java#alerts", "the spanned page's audit record or refusal"),
            Map.entry("RiskQueueDesk.java#cases", "the spanned page's audit record or refusal"),
            Map.entry("RiskQueueDesk.java#file", "the spanned file's audit record or refusal"),
            Map.entry("RiskReportDesk.java#read", "the spanned read's audit record or refusal"),
            // The profile leg's failure path: the failed recompute (spanned) rolled back; its permit deferred alone.
            Map.entry("RiskMonitoringSchedule.java#recomputeDueProfiles", "the failed recompute's deferral"));

    private static final Pattern TRANSACTION = Pattern.compile("\\.(inTransaction|inSnapshot)\\(");
    private static final Pattern SPAN_CALL = Pattern.compile("(flow|scope|spans)\\.(door|sweep|step|within)\\s*$");
    private static final Pattern MEMBER = Pattern.compile("^    [A-Za-z<][^=;\\n]*?\\b(\\w+)\\(", Pattern.MULTILINE);

    @Test
    @DisplayName("every risk transaction app code opens is inside a span call - directly, or in a private method every"
            + " call of which is - but the enumerated, argued few (P13-TSK-024's review)")
    void everyStepIsSpanned() throws Exception {
        java.util.Set<String> unspanned = new java.util.TreeSet<>();
        int sites = 0;
        for (Path file : javaFiles(appRisk())) {
            String text = stripped(Files.readString(file, StandardCharsets.UTF_8));
            List<int[]> members = members(text);
            Matcher call = TRANSACTION.matcher(text);
            while (call.find()) {
                sites++;
                if (!spanned(text, members, call.start(), 0)) {
                    unspanned.add(file.getFileName() + "#" + memberName(text, members, call.start()));
                }
            }
        }
        assertThat(sites).as("the scan reads the risk code's transactions").isGreaterThan(60);
        assertThat(unspanned).as("risk steps outside every span - span them, or argue them in UNSPANNED")
                .isEqualTo(new java.util.TreeSet<>(UNSPANNED.keySet()));
    }

    /** Whether {@code position} is inside a span call's parentheses, or in a private method every call of which is. */
    private static boolean spanned(String text, List<int[]> members, int position, int depth) {
        if (insideSpanCall(text, position)) {
            return true;
        }
        int[] member = enclosing(members, position);
        if (member == null || depth > 3 || !text.substring(member[0], member[1]).contains("private ")) {
            return false;
        }
        String name = memberName(text, members, position);
        Matcher call = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\(").matcher(text);
        int calls = 0;
        while (call.find()) {
            if (call.start() >= member[0] && call.start() < member[1]) {
                continue; // the declaration itself
            }
            calls++;
            if (!spanned(text, members, call.start(), depth + 1)) {
                return false;
            }
        }
        return calls > 0;
    }

    /** Walks out through the unmatched opening parentheses before {@code position}, looking for a span call's. */
    private static boolean insideSpanCall(String text, int position) {
        int depth = 0;
        for (int at = position - 1; at >= 0; at--) {
            char character = text.charAt(at);
            if (character == ')') {
                depth++;
            } else if (character == '(') {
                if (depth == 0) {
                    if (SPAN_CALL.matcher(text.substring(Math.max(0, at - 40), at)).find()) {
                        return true;
                    }
                } else {
                    depth--;
                }
            }
        }
        return false;
    }

    /** Each member declaration's head: [start, end of its head's line]. */
    private static List<int[]> members(String text) {
        List<int[]> members = new ArrayList<>();
        Matcher member = MEMBER.matcher(text);
        while (member.find()) {
            members.add(new int[] {member.start(), member.end()});
        }
        return members;
    }

    private static int[] enclosing(List<int[]> members, int position) {
        int[] found = null;
        for (int[] member : members) {
            if (member[0] <= position) {
                found = member;
            }
        }
        return found;
    }

    private static String memberName(String text, List<int[]> members, int position) {
        int[] member = enclosing(members, position);
        if (member == null) {
            return "?";
        }
        Matcher head = MEMBER.matcher(text.substring(member[0], member[1]));
        return head.find() ? head.group(1) : "?";
    }

    /** {@code source} with its comments and string literals blanked - their parentheses are no code's. */
    private static String stripped(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int at = 0;
        while (at < source.length()) {
            if (source.startsWith("//", at)) {
                int end = source.indexOf('\n', at);
                end = end < 0 ? source.length() : end;
                out.append(" ".repeat(end - at));
                at = end;
            } else if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at + 2);
                end = end < 0 ? source.length() : end + 2;
                for (int i = at; i < end; i++) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                }
                at = end;
            } else if (source.charAt(at) == '"') {
                int end = at + 1;
                while (end < source.length() && source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                out.append('"').append(" ".repeat(Math.max(0, end - at - 1))).append('"');
                at = end + 1;
            } else {
                out.append(source.charAt(at));
                at++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------

    private static Path appRisk() {
        return RepositoryPaths.locate("app/build.gradle.kts").getParent().resolve("src/main/java/com/finapp/app/risk");
    }

    private static Path riskModule() {
        return RepositoryPaths.locate("risk/build.gradle.kts").getParent().resolve("src/main/java/com/finapp/risk");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String sources(Path root) throws IOException {
        List<String> texts = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            texts.add(Files.readString(file, StandardCharsets.UTF_8));
        }
        return String.join("\n", texts);
    }

    /** Records each span's name and attributes, running the work. */
    private static final class Recording implements Spans {
        final List<String> names = new ArrayList<>();
        final List<Map<String, String>> attributes = new ArrayList<>();

        @Override
        public <T> T within(String name, Map<String, String> identifiers, Supplier<T> work) {
            names.add(name);
            attributes.add(identifiers);
            return work.get();
        }
    }
}
