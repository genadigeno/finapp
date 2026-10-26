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
     * The classes that open a risk transaction and record no span of their own, each argued: the gauges' readings (a
     * meter's refresh, no flow - `RiskProofMetrics`, `RescreeningMetrics`, `RiskProfileMetrics`), the transaction port
     * and its Spring implementation, and kyc's re-screening port, called by {@code RescreeningDriver} inside its
     * {@code risk.rescreen} span.
     */
    private static final java.util.Set<String> UNSPANNED = java.util.Set.of("RiskProofMetrics.java",
            "RescreeningMetrics.java", "RiskProfileMetrics.java", "RiskTransactions.java", "SpringRiskTransactions.java",
            "KycPartyRescreening.java");

    @Test
    @DisplayName("every app class that opens a risk transaction holds a RiskFlowScope - a step never runs unspanned -"
            + " but the gauges' readings, the transaction port and kyc's port inside the rescreening span")
    void everyStepIsSpanned() throws Exception {
        List<String> unspanned = new ArrayList<>();
        for (Path file : javaFiles(appRisk())) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            boolean opens = source.contains(".inTransaction(") || source.contains(".inSnapshot(");
            if (opens && !source.contains("RiskFlowScope") && !UNSPANNED.contains(name)) {
                unspanned.add(name);
            }
        }
        assertThat(unspanned).as("risk steps recorded by no span (INV-AUD-02's trace, PHASE_13_PLAN.md section 15)")
                .isEmpty();
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
