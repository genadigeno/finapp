package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EvidenceCitations.Citation;
import com.finapp.app.architecture.EvidenceCitations.PathCitation;
import com.finapp.app.architecture.EvidenceCitations.TaskCitation;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The resilience experiment register holds every experiment with every field, and a result only with its evidence
 * ({@code P16-TSK-001}, ADR-0127 decision 2).
 *
 * <p>{@code docs/architecture/RESILIENCE_EXPERIMENTS.md} is Phase 16's record of every failure experiment: section 3 holds
 * {@code PHASE_16_PLAN.md} section 9.2's experiments ({@code E01}...), section 4.1 the plan's fifteen financial-correctness
 * scenarios of section 13, section 4.2 one experiment per scenario ({@code F01}...), and section 5 what can only be designed
 * here ({@code D01}...). A register like this fails by letting a chaos run be recorded as a pass without a hypothesis, a stop
 * or its evidence, so each rule below refuses one way of doing that:
 *
 * <ol>
 *   <li>every line of those sections that looks like a table row parses: the header is the register's, every row has its
 *       columns and an id of its section's form, and no id repeats;
 *   <li>every experiment carries every field: scenario, hypothesis, affected units, expected behaviour, safety, success
 *       criteria, stop, instances and who runs it; only the result and remediation slots may stay {@code —}. Every
 *       scenario and designed-only row is filled too;
 *   <li>a filled result names, per run, its verdict, its date (a real date, not before the register existed), the replica
 *       count it ran at, its commit and its artefacts path (relative, never leaving the tree it names);
 *   <li>a failed result names the backlog task that remediates it, and while the standing (last) result is a failure, one
 *       of the tasks named is still live: a failure nobody can still act on is not recorded;
 *   <li>every scenario maps to at least one registered experiment, and every scenario experiment serves a scenario;
 *   <li>an {@code N/A} names its reason;
 *   <li>every experiment states the replica count it runs at, never fewer than three, today's minimum ({@code minReplicas});
 *   <li>every experiment of the plan's section 9.2 and every scenario of its section 13 is in the register, so none is
 *       dropped silently;
 *   <li>an experiment not yet run names a live task that runs it, so a task cannot finish leaving its row empty;
 *   <li>what the scenario table says already proves part of a scenario is a test that exists, declared as a test, in the
 *       tier the register says (a database test is never called hermetic);
 *   <li>a designed-only row names its local analogue, every experiment it names is registered, and until the owner accepts
 *       it a live task holds it;
 *   <li>the register names no URL, IPv4 address, host name under a public or cluster-internal suffix, host and port, or
 *       assigned credential;
 *   <li>the register is not vacuous: every table was read, above floors.
 * </ol>
 *
 * <p>Hermetic: it reads files only (the register, {@code PHASE_16_PLAN.md}, {@code BACKLOG.md} and the tests the scenario
 * table cites). {@code app/build.gradle.kts} declares the documents tree as an input of every test task, so an edited row
 * re-runs it. It reuses the readiness and threat-model guards' citation grammar ({@link EvidenceCitations}).
 */
@Tag("architecture")
@DisplayName("the resilience experiment register holds every experiment with every field, and a result only with its evidence (P16-TSK-001)")
class ResilienceExperimentsAreRegisteredTest {

    private static final String REGISTER = "docs/architecture/RESILIENCE_EXPERIMENTS.md";
    private static final String PLAN = "docs/project/PHASE_16_PLAN.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";

    /** The day the register was written: no run recorded in it can be older. */
    private static final LocalDate REGISTER_WRITTEN = LocalDate.of(2026, 11, 12);

    /** Today's minimum replica count ({@code deploy/base/horizontalpodautoscaler.yaml}, {@code minReplicas}; ADR-0014). */
    private static final int MINIMUM_REPLICAS = 3;

    private static final String EMPTY = "—";

    private static final List<String> EXPERIMENT_COLUMNS = List.of("ID", "Scenario", "Hypothesis", "Affected",
            "Expected behaviour", "Safety", "Success criteria", "Stop", "Instances", "Runs in", "Result", "Remediation");
    private static final List<String> SCENARIO_COLUMNS =
            List.of("#", "Scenario", "Already proven in part", "Not proven", "Experiments");
    private static final List<String> DESIGNED_ONLY_COLUMNS = List.of("ID", "Designed-only scenario",
            "Why it cannot run here", "Local analogue", "Written by", "Status");

    // The experiment columns by position.
    private static final int INSTANCES = 8;
    private static final int RUNS_IN = 9;
    private static final int RESULT = 10;
    private static final int REMEDIATION = 11;

    // The scenario and designed-only columns by position.
    private static final int ALREADY_PROVEN = 2;
    private static final int EXPERIMENTS = 4;
    private static final int LOCAL_ANALOGUE = 3;
    private static final int WRITTEN_BY = 4;
    private static final int STATUS = 5;

    /** The register's tables: the heading that opens each, its columns and the form of its ids. */
    private enum Table {
        EXPERIMENTS("## 3. ", EXPERIMENT_COLUMNS, Pattern.compile("E\\d{2}")),
        SCENARIOS("### 4.1 ", SCENARIO_COLUMNS, Pattern.compile("[1-9]\\d?")),
        SCENARIO_EXPERIMENTS("### 4.2 ", EXPERIMENT_COLUMNS, Pattern.compile("F\\d{2}")),
        DESIGNED_ONLY("## 5. ", DESIGNED_ONLY_COLUMNS, Pattern.compile("D\\d{2}"));

        private final String heading;
        private final List<String> columns;
        private final Pattern id;

        Table(String heading, List<String> columns, Pattern id) {
            this.heading = heading;
            this.columns = columns;
            this.id = id;
        }
    }

    /** One run's result: verdict, date, replicas, commit, artefacts. */
    private static final Pattern RUN = Pattern.compile(
            "(PASSED|FAILED) on (\\d{4}-\\d{2}-\\d{2}) at (\\d+) replicas?, commit `([0-9a-f]{7,40})`, artefacts `([^`]+)`");

    /** A relative path under the tree that holds it: no root, no drive, no scheme, no parent step. */
    private static final Pattern ARTEFACTS = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_./-]*$");

    /** A replica count as the Instances column states it: {@code 3 replicas}, {@code 3 and 10 replicas}. */
    private static final Pattern REPLICAS = Pattern.compile("\\b(\\d+)(?: and (\\d+))? replicas\\b");

    /** An {@code N/A} with its reason; a bare one, or one with a word for a reason, is refused. */
    private static final Pattern NOT_APPLICABLE = Pattern.compile("^N/A: \\S.{9,}$");

    /** An experiment id named in a scenario's or a designed-only row's cell. */
    private static final Pattern EXPERIMENT_ID = Pattern.compile("\\b([EF]\\d{2})\\b");

    /** A designed-only row's status: open, or decided by the owner on a date. */
    private static final Pattern STATUS_OPEN = Pattern.compile("^DESIGNED-ONLY$");
    private static final Pattern STATUS_ACCEPTED =
            Pattern.compile("^ACCEPTED as designed-only by the owner on (\\d{4}-\\d{2}-\\d{2})$");

    /** A test cited in the scenario table, followed by the tier the register says it is in. */
    private static final Pattern CITED_TEST = Pattern.compile("`([^`]+)` \\[([a-z-]+)]");

    /** The tags that place a test in a tier needing infrastructure, or in a Spring slice. */
    private static final Pattern TIER_TAG = Pattern.compile("@Tag\\(\"(database|kafka|own-container|slice)\"\\)");

    /**
     * An endpoint, host or credential: a URL's scheme, an IPv4 address, a host and port, a host name under a public or a
     * cluster-internal suffix, a secret assigned. A bare word (a Service's short name) is not caught: the register names
     * units, never how to reach them, and a reviewer reads the rest.
     */
    private static final List<Pattern> ENDPOINTS_AND_CREDENTIALS = List.of(
            Pattern.compile("://"),
            Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"),
            Pattern.compile("\\b[a-z][a-z0-9.-]*:\\d{2,5}\\b"),
            Pattern.compile("(?i)\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|io|dev|cloud|internal|local|svc)\\b"),
            Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_-]?key)\\s*[:=]\\s*\\S"));

    /** The plan's experiments and scenarios: the rows of its sections 9.2 and 13. */
    private static final Pattern PLAN_EXPERIMENT = Pattern.compile("^\\| (E\\d{2}) \\|");
    private static final Pattern PLAN_SCENARIO = Pattern.compile("^\\| (\\d+) \\|");

    /** Floors, at the register's own counts: a table that stops parsing cannot pass as a short one. */
    private static final int MIN_EXPERIMENTS = 26;
    private static final int MIN_SCENARIO_EXPERIMENTS = 15;
    private static final int MIN_DESIGNED_ONLY = 5;

    private record Row(Table table, String id, List<String> cells) {
        String cell(int index) {
            return cells.get(index);
        }

        String where() {
            return table.name().toLowerCase() + " " + id;
        }
    }

    private static Path root;
    private static String register;
    private static Map<String, String> statuses;
    private static Map<Table, List<Row>> tables;
    private static Map<Table, String> headers;
    private static List<String> unparseable;
    private static Set<String> planExperiments;
    private static Set<String> planScenarios;

    @BeforeAll
    static void readTheRegister() {
        root = EvidenceCitations.repositoryRoot();
        register = EvidenceCitations.read(root.resolve(REGISTER)).replace("\r\n", "\n");
        statuses = EvidenceCitations.backlogStatuses(root.resolve(BACKLOG));
        String plan = EvidenceCitations.read(root.resolve(PLAN)).replace("\r\n", "\n");
        planExperiments = idsIn(section(plan, "### 9.2 "), PLAN_EXPERIMENT);
        planScenarios = idsIn(section(plan, "## 13. "), PLAN_SCENARIO);
        parseTables();
    }

    @Test
    @DisplayName("every row of the register's tables parses: its header, its columns, an id of its form, never twice")
    void everyTableRowParses() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Table table : Table.values()) {
            String header = headers.get(table);
            if (header == null) {
                problems.put(table.name(), "no table under '" + table.heading.trim() + "'");
            } else if (!header.equals(String.join(" | ", table.columns))) {
                problems.put(table.name(), "its header reads '" + header + "', not the register's columns " + table.columns);
            }
        }
        unparseable.forEach(line -> problems.put(line, "does not parse"));
        Set<String> seen = new TreeSet<>();
        for (Row row : rows()) {
            if (!seen.add(row.table() == Table.SCENARIOS ? "scenario " + row.id() : row.id())) {
                problems.put(row.where(), "is registered twice: an id is never reused");
            }
        }
        assertThat(problems).as("tables of %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("every experiment carries every field; only the result and remediation slots may stay empty")
    void everyRowCarriesEveryField() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : rows()) {
            boolean experiment = row.table() == Table.EXPERIMENTS || row.table() == Table.SCENARIO_EXPERIMENTS;
            List<String> missing = new ArrayList<>();
            for (int i = 1; i < row.cells().size(); i++) {
                if (experiment && (i == RESULT || i == REMEDIATION)) {
                    continue;
                }
                String cell = row.cell(i);
                if (cell.isBlank() || cell.equals(EMPTY)) {
                    missing.add(row.table().columns.get(i));
                }
            }
            if (!missing.isEmpty()) {
                problems.put(row.where(), "has no " + String.join(", no ", missing));
            }
        }
        assertThat(problems).as("fields missing from rows of %s (a stop, a safety, a hypothesis is never optional)", REGISTER)
                .isEmpty();
    }

    @Test
    @DisplayName("a filled result names, for each run, its verdict, date, replica count, commit and artefacts")
    void everyResultNamesItsEvidence() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : experiments()) {
            String result = row.cell(RESULT);
            if (result.equals(EMPTY) || result.startsWith("N/A")) {
                continue;
            }
            List<String> rowProblems = new ArrayList<>();
            List<Matcher> runs = runs(result);
            if (runs.isEmpty()) {
                rowProblems.add("its result '" + result + "' is not '<PASSED|FAILED> on <date> at <n> replicas, commit"
                        + " `<sha>`, artefacts `<path>`', several joined by '; '");
            }
            for (Matcher run : runs) {
                try {
                    LocalDate date = LocalDate.parse(run.group(2));
                    if (date.isBefore(REGISTER_WRITTEN)) {
                        rowProblems.add("a run dated " + date + ", before the register was written");
                    }
                } catch (DateTimeParseException notADate) {
                    rowProblems.add("'" + run.group(2) + "' is not a date");
                }
                if (Integer.parseInt(run.group(3)) < 1) {
                    rowProblems.add("a run at no replica");
                }
                String artefacts = run.group(5);
                if (!ARTEFACTS.matcher(artefacts).matches() || List.of(artefacts.split("/")).contains("..")) {
                    rowProblems.add("artefacts '" + artefacts + "' are not a relative path inside the tree that holds them");
                }
            }
            if (!rowProblems.isEmpty()) {
                problems.put(row.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("results in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("a failed result names its remediation, and a standing failure a task that is still live")
    void aFailureNamesALiveRemediation() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : experiments()) {
            List<Matcher> runs = runs(row.cell(RESULT));
            if (runs.stream().noneMatch(run -> run.group(1).equals("FAILED"))) {
                continue;
            }
            List<String> rowProblems = new ArrayList<>(taskProblems(row.cell(REMEDIATION)));
            List<String> tasks = tasksIn(row.cell(REMEDIATION));
            if (tasks.isEmpty()) {
                rowProblems.add("a failed result names no remediation task");
            }
            boolean standingFailure = runs.get(runs.size() - 1).group(1).equals("FAILED");
            if (standingFailure && tasks.stream().noneMatch(ResilienceExperimentsAreRegisteredTest::live)) {
                rowProblems.add("its standing result failed and no task it names is still live (" + describe(tasks)
                        + "): a failure nobody can still act on");
            }
            if (!rowProblems.isEmpty()) {
                problems.put(row.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("failed results in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("every scenario maps to a registered experiment, and every scenario experiment serves a scenario")
    void everyScenarioMapsToAnExperiment() {
        Set<String> registered = new TreeSet<>();
        experiments().forEach(row -> registered.add(row.id()));
        Map<String, String> problems = new LinkedHashMap<>();
        Set<String> served = new TreeSet<>();
        for (Row scenario : tables.get(Table.SCENARIOS)) {
            Set<String> named = idsIn(scenario.cell(EXPERIMENTS), EXPERIMENT_ID);
            served.addAll(named);
            if (named.isEmpty()) {
                problems.put(scenario.where(), "maps to no experiment");
            }
            named.stream().filter(id -> !registered.contains(id))
                    .forEach(id -> problems.put(scenario.where() + " -> " + id, "is not a registered experiment"));
        }
        tables.get(Table.SCENARIO_EXPERIMENTS).stream().filter(row -> !served.contains(row.id()))
                .forEach(row -> problems.put(row.where(), "serves no scenario"));
        assertThat(problems).as("scenarios and their experiments in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("an N/A names its reason")
    void everyNotApplicableNamesItsReason() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : rows()) {
            for (int i = 1; i < row.cells().size(); i++) {
                String cell = row.cell(i);
                if (cell.startsWith("N/A") && !NOT_APPLICABLE.matcher(cell).matches()) {
                    problems.put(row.where() + " / " + row.table().columns.get(i), "'" + cell + "' names no reason");
                }
            }
        }
        assertThat(problems).as("N/A cells in %s: 'N/A: ' and the reason", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("every experiment states the replica count it runs at, never fewer than three")
    void everyExperimentStatesItsInstanceCount() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : experiments()) {
            String instances = row.cell(INSTANCES);
            if (instances.startsWith("N/A")) {
                if (!row.cell(RESULT).startsWith("N/A")) {
                    problems.put(row.where(), "an experiment that runs states its replica count, not N/A");
                }
                continue;
            }
            Matcher count = REPLICAS.matcher(instances);
            if (!count.find()) {
                problems.put(row.where(), "'" + instances + "' states no replica count ('<n> replicas')");
                continue;
            }
            for (String number : new String[] {count.group(1), count.group(2)}) {
                if (number != null && Integer.parseInt(number) < MINIMUM_REPLICAS) {
                    problems.put(row.where(), number + " replicas, below today's minimum of " + MINIMUM_REPLICAS);
                }
            }
        }
        assertThat(problems).as("instance counts in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("every experiment of the plan's section 9.2 and every scenario of its section 13 is registered")
    void everyPlannedExperimentAndScenarioIsRegistered() {
        Set<String> experiments = new TreeSet<>();
        tables.get(Table.EXPERIMENTS).forEach(row -> experiments.add(row.id()));
        Set<String> scenarios = new TreeSet<>();
        tables.get(Table.SCENARIOS).forEach(row -> scenarios.add(row.id()));
        assertThat(experiments).as("the plan's experiments (%s section 9.2) in %s", PLAN, REGISTER)
                .containsAll(planExperiments);
        assertThat(scenarios).as("the plan's scenarios (%s section 13) in %s, exactly", PLAN, REGISTER)
                .isEqualTo(planScenarios);
    }

    @Test
    @DisplayName("an experiment not yet run names a live task that runs it, and every task named is an entry")
    void anUnrunExperimentNamesALiveRunner() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : experiments()) {
            String runsIn = row.cell(RUNS_IN);
            if (runsIn.startsWith("N/A")) {
                if (!row.cell(RESULT).startsWith("N/A")) {
                    problems.put(row.where(), "an experiment that runs names who runs it, not N/A");
                }
                continue;
            }
            List<String> rowProblems = new ArrayList<>(taskProblems(runsIn));
            List<String> tasks = tasksIn(runsIn);
            if (tasks.isEmpty()) {
                rowProblems.add("names no task that runs it");
            } else if (row.cell(RESULT).equals(EMPTY) && tasks.stream().noneMatch(ResilienceExperimentsAreRegisteredTest::live)) {
                rowProblems.add("has no result and no task it names is still live (" + describe(tasks)
                        + "): a task finished and left its row empty");
            }
            if (!rowProblems.isEmpty()) {
                problems.put(row.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("who runs each experiment in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("what already proves part of a scenario is a test that exists, in the tier the register says")
    void whatAlreadyProvesAScenarioResolvesInItsTier() {
        Map<String, String> problems = new LinkedHashMap<>();
        int cited = 0;
        for (Row scenario : tables.get(Table.SCENARIOS)) {
            String cell = scenario.cell(ALREADY_PROVEN);
            long backticked = EvidenceCitations.BACKTICKED.matcher(cell).results().count();
            List<String> rowProblems = new ArrayList<>();
            Matcher test = CITED_TEST.matcher(cell);
            long paired = 0;
            while (test.find()) {
                paired++;
                cited++;
                problemWithCitedTest(test.group(1), test.group(2)).ifPresent(rowProblems::add);
            }
            if (paired != backticked) {
                rowProblems.add("every backticked citation here is a test followed by its tier, [db], [kafka],"
                        + " [own-container], [slice] or [hermetic]");
            }
            if (!rowProblems.isEmpty()) {
                problems.put(scenario.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(cited).as("tests cited in the scenario table").isGreaterThanOrEqualTo(15);
        assertThat(problems).as("what proves each scenario in part, in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("a designed-only row names its local analogue, and a live task holds it until the owner accepts it")
    void everyDesignedOnlyRowIsHeld() {
        Set<String> registered = new TreeSet<>();
        experiments().forEach(row -> registered.add(row.id()));
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : tables.get(Table.DESIGNED_ONLY)) {
            List<String> rowProblems = new ArrayList<>();
            idsIn(row.cell(LOCAL_ANALOGUE), EXPERIMENT_ID).stream().filter(id -> !registered.contains(id))
                    .forEach(id -> rowProblems.add("its analogue " + id + " is not a registered experiment"));
            rowProblems.addAll(taskProblems(row.cell(WRITTEN_BY)));
            String status = row.cell(STATUS);
            Matcher accepted = STATUS_ACCEPTED.matcher(status);
            if (STATUS_OPEN.matcher(status).matches()) {
                List<String> tasks = tasksIn(row.cell(WRITTEN_BY));
                if (tasks.stream().noneMatch(ResilienceExperimentsAreRegisteredTest::live)) {
                    rowProblems.add("is open and no task it names is still live (" + describe(tasks)
                            + "): the owner accepts it or a live task holds it");
                }
            } else if (accepted.matches()) {
                try {
                    LocalDate.parse(accepted.group(1));
                } catch (DateTimeParseException notADate) {
                    rowProblems.add("'" + accepted.group(1) + "' is not a date");
                }
            } else {
                rowProblems.add("its status '" + status + "' is neither DESIGNED-ONLY nor ACCEPTED as designed-only by the"
                        + " owner on <date>");
            }
            if (!rowProblems.isEmpty()) {
                problems.put(row.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("designed-only rows in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("the register names no URL, address, host name, host and port, or assigned credential")
    void theRegisterNamesNoEndpointOrCredential() {
        Map<String, String> found = new LinkedHashMap<>();
        String[] lines = register.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Pattern pattern : ENDPOINTS_AND_CREDENTIALS) {
                Matcher match = pattern.matcher(lines[i]);
                if (match.find()) {
                    found.put("line " + (i + 1), "'" + match.group() + "'");
                }
            }
        }
        assertThat(found).as("endpoints, hosts or credentials in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("the register is not vacuous: every table, the plan and the backlog were read, above floors")
    void theRegisterIsNotVacuous() {
        assertThat(statuses).as("backlog entries parsed").hasSizeGreaterThan(400).containsKey("P16-TSK-027");
        assertThat(planExperiments).as("the plan's experiments parsed").hasSizeGreaterThanOrEqualTo(MIN_EXPERIMENTS);
        assertThat(planScenarios).as("the plan's scenarios parsed").hasSize(15);
        assertThat(tables.get(Table.EXPERIMENTS)).as("experiments read").hasSizeGreaterThanOrEqualTo(MIN_EXPERIMENTS);
        assertThat(tables.get(Table.SCENARIOS)).as("scenarios read").hasSize(15);
        assertThat(tables.get(Table.SCENARIO_EXPERIMENTS)).as("scenario experiments read")
                .hasSizeGreaterThanOrEqualTo(MIN_SCENARIO_EXPERIMENTS);
        assertThat(tables.get(Table.DESIGNED_ONLY)).as("designed-only rows read").hasSizeGreaterThanOrEqualTo(MIN_DESIGNED_ONLY);
    }

    // ------------------------------------------------------------------ the rules' parts

    /** The runs a result cell records, oldest first; empty when the cell is not wholly runs joined by '; '. */
    private static List<Matcher> runs(String result) {
        List<Matcher> runs = new ArrayList<>();
        if (result.equals(EMPTY) || result.startsWith("N/A")) {
            return runs;
        }
        for (String part : result.split("; ", -1)) {
            Matcher run = RUN.matcher(part.trim());
            if (!run.matches()) {
                return List.of();
            }
            runs.add(run);
        }
        return runs;
    }

    /** The backlog ids a cell cites. */
    private static List<String> tasksIn(String cell) {
        List<String> tasks = new ArrayList<>();
        for (Citation citation : EvidenceCitations.citationsIn(cell)) {
            if (citation instanceof TaskCitation task) {
                tasks.add(task.id());
            }
        }
        return tasks;
    }

    /** What is wrong with a cell that names tasks: each is an entry header of the backlog, and nothing else is cited. */
    private static List<String> taskProblems(String cell) {
        List<String> problems = new ArrayList<>();
        for (Citation citation : EvidenceCitations.citationsIn(cell)) {
            if (citation instanceof TaskCitation task) {
                if (!statuses.containsKey(task.id())) {
                    problems.add("`" + task.id() + "` is not an entry header in " + BACKLOG);
                }
            } else {
                problems.add(citation + " is not a backlog id");
            }
        }
        return problems;
    }

    private static boolean live(String task) {
        return EvidenceCitations.LIVE_STATUSES.contains(statuses.get(task));
    }

    private static String describe(List<String> tasks) {
        List<String> described = new ArrayList<>();
        tasks.forEach(task -> described.add(task + " " + statuses.get(task)));
        return String.join(", ", described);
    }

    /** Why a test the scenario table cites does not hold there, or empty when it does. */
    private static Optional<String> problemWithCitedTest(String token, String tier) {
        Citation citation = EvidenceCitations.parse(token);
        if (!(citation instanceof PathCitation path) || !path.isTestSource() || path.member().isEmpty()) {
            return Optional.of("`" + token + "` is not a test cited as Class.java#member");
        }
        Optional<String> unresolved = EvidenceCitations.problemWith(root, path);
        if (unresolved.isPresent()) {
            return Optional.of("`" + token + "` " + unresolved.get());
        }
        Set<String> tags = new TreeSet<>();
        TIER_TAG.matcher(EvidenceCitations.read(root.resolve(path.path()))).results().forEach(tag -> tags.add(tag.group(1)));
        boolean needsInfrastructure = tags.contains("database") || tags.contains("kafka") || tags.contains("own-container");
        boolean holds = switch (tier) {
            case "db" -> tags.contains("database") && !tags.contains("own-container");
            case "kafka" -> tags.contains("kafka");
            case "own-container" -> tags.contains("own-container");
            case "slice" -> tags.contains("slice") && !needsInfrastructure;
            case "hermetic" -> !needsInfrastructure;
            default -> false;
        };
        return holds ? Optional.empty() : Optional.of("`" + token + "` is labelled [" + tier + "] and carries the tags " + tags);
    }

    // ------------------------------------------------------------------ parsing

    private static List<Row> rows() {
        return tables.values().stream().flatMap(List::stream).toList();
    }

    private static List<Row> experiments() {
        List<Row> experiments = new ArrayList<>(tables.get(Table.EXPERIMENTS));
        experiments.addAll(tables.get(Table.SCENARIO_EXPERIMENTS));
        return experiments;
    }

    private static void parseTables() {
        tables = new LinkedHashMap<>();
        headers = new LinkedHashMap<>();
        unparseable = new ArrayList<>();
        for (Table table : Table.values()) {
            tables.put(table, new ArrayList<>());
        }
        Table current = null;
        boolean fenced = false;
        for (String line : register.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            if (line.startsWith("## ") || line.startsWith("### ")) {
                current = null;
                for (Table table : Table.values()) {
                    if (line.startsWith(table.heading)) {
                        current = table;
                    }
                }
                continue;
            }
            if (current == null || !line.trim().startsWith("|")) {
                continue;
            }
            List<String> columns = columns(line);
            if (line.startsWith("|") && !columns.isEmpty() && columns.stream().allMatch(column -> column.matches(":?-+:?"))) {
                continue;
            }
            if (!line.startsWith("| ") || columns.isEmpty()) {
                unparseable.add(current.name() + ": " + abbreviate(line));
                continue;
            }
            if (!headers.containsKey(current)) {
                headers.put(current, String.join(" | ", columns));
                continue;
            }
            if (columns.size() != current.columns.size() || !current.id.matcher(columns.get(0)).matches()) {
                unparseable.add(current.name() + ": " + abbreviate(line));
                continue;
            }
            tables.get(current).add(new Row(current, columns.get(0), columns));
        }
    }

    /** The cells of a table row; empty when the line does not close its last cell. */
    private static List<String> columns(String line) {
        String trimmed = line.trim();
        if (!trimmed.endsWith("|") || trimmed.length() < 2) {
            return List.of();
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.substring(1, trimmed.length() - 1).split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    /** Every first group of {@code pattern} in {@code text}, line by line, in order. */
    private static Set<String> idsIn(String text, Pattern pattern) {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : text.split("\n", -1)) {
            Matcher id = pattern.matcher(line);
            while (id.find()) {
                ids.add(id.group(1));
            }
        }
        return ids;
    }

    /** The lines of the section whose heading starts with {@code prefix}, to the next heading of its level or above. */
    private static String section(String document, String prefix) {
        StringBuilder lines = new StringBuilder();
        boolean inside = false;
        String level = prefix.substring(0, prefix.indexOf(' ') + 1);
        for (String line : document.split("\n", -1)) {
            if (line.startsWith(prefix)) {
                inside = true;
                continue;
            }
            if (inside && line.startsWith("#") && line.indexOf(' ') > 0
                    && line.indexOf(' ') <= level.length() - 1 && line.substring(0, line.indexOf(' ')).matches("#+")) {
                break;
            }
            if (inside) {
                lines.append(line).append('\n');
            }
        }
        return lines.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 100 ? text : text.substring(0, 97) + "...";
    }
}
