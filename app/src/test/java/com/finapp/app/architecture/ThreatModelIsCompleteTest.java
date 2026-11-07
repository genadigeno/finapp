package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EvidenceCitations.Citation;
import com.finapp.app.architecture.EvidenceCitations.PathCitation;
import com.finapp.app.architecture.EvidenceCitations.TaskCitation;
import com.finapp.app.architecture.EvidenceCitations.Unreadable;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The threat register is complete, and every claim in it resolves ({@code P15-TSK-001}).
 *
 * <p>{@code docs/architecture/THREAT_MODEL.md} is Phase 15's security baseline. Every later task builds a
 * control it names, and {@code PHASE_GATES.md} §5 Phase 15 criterion 1 reads it. A register like this fails
 * by reading as assurance it does not hold: a control called existing whose evidence is absent, a promise
 * whose task finished without keeping it, a CRITICAL threat with nothing against it, or a parser that matched
 * nothing and so found nothing wrong. Each test below closes one of those, so a failure names its defect:
 *
 * <ol>
 *   <li>every line that looks like a row parses as one, so nothing is skipped unseen;
 *   <li>the ten trust boundaries of {@code PHASE_15_PLAN.md} §9.1 are present by id and name;
 *   <li>each boundary answers all six STRIDE categories, with threats or {@code N/A} and a reason;
 *   <li>the boundary matrix and the register agree, triple for triple;
 *   <li>every rating is the cell of the written rule, and the rule is the one this test holds;
 *   <li>every threat has a disposition, and a {@code CONTROL} threat has a row;
 *   <li>no CRITICAL or HIGH threat is left without a control or the owner's dated acceptance;
 *   <li>every {@code EXISTS} row's evidence exists (and every {@code #method} is declared), every
 *       {@code PLANNED} or {@code REMEDIATION} row cites a backlog entry, and nothing in the column is
 *       unreadable;
 *   <li>no promise outlives its task: a row citing a task that is no longer live is red, naming the task;
 *   <li>every task id the document names is a backlog entry;
 *   <li>the register is not vacuous: the counts parsed meet the minimums recorded here.
 * </ol>
 *
 * <p><strong>The choice in rule 8.</strong> A {@code PLANNED} row is a promise. When its task becomes
 * {@code COMPLETE}, the promise is kept, and the evidence belongs in the row, or it is broken, and the row is a
 * gap. Either way the register is wrong until that task's gate rewrites it, so the flip turns this test red.
 * A postponed task's promise has no owner, so it is refused too. The cost is deliberate: every Phase 15 gate
 * edits the model.
 *
 * <p>Hermetic: it reads files only. It carries the {@code architecture} tag to run with the package's other
 * document guards ({@code AdrRegistersAreReconciledTest}), and {@code app/build.gradle.kts} declares the
 * documents and the non-code evidence it reads as test inputs.
 */
@Tag("architecture")
@DisplayName("the threat register is complete and its evidence resolves (P15-TSK-001)")
class ThreatModelIsCompleteTest {

    private static final String MODEL = "docs/architecture/THREAT_MODEL.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";

    /** {@code PHASE_15_PLAN.md} §9.1's ten boundaries, by id and name. A rename is a deliberate edit here. */
    private static final Map<String, String> BOUNDARIES = orderedMap(
            "B1", "Internet → edge",
            "B2", "Edge → application",
            "B3", "Operator network → operator ingress",
            "B4", "Application → PostgreSQL",
            "B5", "Application → Kafka",
            "B6", "Application → providers",
            "B7", "Providers → callback routes",
            "B8", "CI → registry",
            "B9", "GitOps → cluster",
            "B10", "Humans → cluster and cloud");

    /** The six STRIDE categories as the matrix names them, and their letters in the register. */
    private static final Map<String, String> CATEGORIES = orderedMap(
            "Spoofing", "S",
            "Tampering", "T",
            "Repudiation", "R",
            "Information disclosure", "I",
            "Denial of service", "D",
            "Elevation of privilege", "E");

    private static final List<String> LIKELIHOODS = List.of("HIGH", "MEDIUM", "LOW");
    private static final List<String> IMPACTS = List.of("SEVERE", "HIGH", "MEDIUM", "LOW");
    private static final Set<String> RATINGS = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final Set<String> STATES = Set.of("EXISTS", "PLANNED", "REMEDIATION");

    /**
     * The rating rule: impact, then likelihood HIGH, MEDIUM, LOW. {@code THREAT_MODEL.md} §1.3 writes the same
     * table, and {@link #everyThreatIsRatedByTheWrittenRule} holds the two equal, so neither can be softened
     * alone.
     */
    private static final Map<String, List<String>> RULE = orderedMap(
            "SEVERE", List.of("CRITICAL", "CRITICAL", "HIGH"),
            "HIGH", List.of("HIGH", "HIGH", "MEDIUM"),
            "MEDIUM", List.of("MEDIUM", "MEDIUM", "LOW"),
            "LOW", List.of("LOW", "LOW", "LOW"));

    /**
     * The minimums: the counts at writing (2026-11-08). A renamed heading or a broken table parses fewer, and
     * fails here, rather than leaving every rule above checking an empty register. A count may grow freely. It
     * falls below these only by a deliberate edit to this file, made with the reason. {@code PLANNED} rows
     * carry no minimum: they become {@code EXISTS} as the phase delivers.
     */
    private static final int MIN_THREATS = 44;
    private static final int MIN_ANSWERED_CELLS = 57;
    private static final int MIN_CONTROL_ROWS = 197;
    private static final int MIN_EXISTS_ROWS = 88;

    private static final Pattern BOUNDARY_HEADING = Pattern.compile("^### (B\\d+) — (.+)$");
    private static final Pattern THREAT_ID = Pattern.compile("^T\\d+$");
    private static final Pattern WHERE = Pattern.compile("^(B\\d+):([STRIDE])$");
    private static final Pattern ACCEPTED =
            Pattern.compile("^ACCEPTED by (.+?) on (\\d{4}-\\d{2}-\\d{2})(?:, until (\\d{4}-\\d{2}-\\d{2}))?$");
    private static final Pattern RULE_ROW =
            Pattern.compile("^\\| (SEVERE|HIGH|MEDIUM|LOW) \\| (\\w+) \\| (\\w+) \\| (\\w+) \\|\\s*$");
    private static final Pattern ANY_TASK_ID =
            Pattern.compile("`((?:P\\d+-(?:TSK|TST|DOC|REM)-\\d{3})|X-TSK-\\d{3})`");

    private record Cell(String boundary, String category, List<String> threats, String note) {}

    private record Threat(String id, String where, String likelihood, String impact, String rating,
            String disposition) {}

    private record Control(String threat, String control, String state, String evidence) {}

    private static Path root;
    private static String model;
    private static Map<String, String> statuses;
    private static Map<String, String> boundaryNames;
    private static List<String> boundaryOrder;
    private static List<Cell> cells;
    private static List<Threat> threats;
    private static List<Control> controls;
    private static Map<String, List<String>> writtenRule;
    private static List<String> unparseable;

    @BeforeAll
    static void parseTheModel() {
        root = EvidenceCitations.repositoryRoot();
        model = EvidenceCitations.read(root.resolve(MODEL));
        statuses = EvidenceCitations.backlogStatuses(root.resolve(BACKLOG));
        unparseable = new ArrayList<>();
        parseBoundaries(section("## 2. "));
        parseRegister(section("## 3. "));
        parseControls(section("## 4. "));
        parseRule(subsection("### 1.3 "));
    }

    @Test
    @DisplayName("the ten trust boundaries of PHASE_15_PLAN.md section 9.1 are modelled, each once, by name")
    void theTenBoundariesAreModelled() {
        assertThat(boundaryOrder)
                .as("%s section 2 must model each boundary of PHASE_15_PLAN.md section 9.1 exactly once, in"
                        + " order, as '### B<n> — <name>'", MODEL)
                .containsExactlyElementsOf(BOUNDARIES.keySet());
        assertThat(boundaryNames)
                .as("each boundary's name is the plan's: a renamed boundary is a different boundary")
                .isEqualTo(BOUNDARIES);
    }

    @Test
    @DisplayName("every boundary answers all six STRIDE categories, with threats or N/A and its reason")
    void everyBoundaryAnswersAllSixStrideCategories() {
        Map<String, String> problems = new TreeMap<>();
        for (String boundary : boundaryOrder) {
            List<String> answered = cells.stream()
                    .filter(cell -> cell.boundary().equals(boundary))
                    .map(Cell::category)
                    .toList();
            if (!answered.equals(List.copyOf(CATEGORIES.keySet()))) {
                problems.put(boundary, "answers " + answered + ", not the six categories once each, in order");
            }
        }
        for (Cell cell : cells) {
            if (cell.threats().isEmpty() && cell.note().length() < 40) {
                problems.put(cell.boundary() + " " + cell.category(),
                        "is N/A without a reason of at least a sentence: '" + cell.note() + "'");
            }
        }
        assertThat(problems).as("STRIDE answered per boundary").isEmpty();
    }

    @Test
    @DisplayName("every line that looks like a row of the matrix, the register or the controls parses as one")
    void everyRowParses() {
        // A row the parser skips is a threat or a control every other test here silently stops checking - the
        // shape P1-TSK-024 found in the mutation register's parser, closed at design time as
        // AdrRegistersAreReconciledTest closes it for the ADR index.
        assertThat(unparseable)
                .as("lines in %s's matrix, register or controls that look like rows and did not parse", MODEL)
                .isEmpty();
    }

    @Test
    @DisplayName("the boundary matrix and the register agree, (boundary, category, threat) for each")
    void theMatrixAndTheRegisterAgree() {
        Set<String> fromMatrix = new TreeSet<>();
        for (Cell cell : cells) {
            for (String threat : cell.threats()) {
                fromMatrix.add(cell.boundary() + ":" + CATEGORIES.get(cell.category()) + " " + threat);
            }
        }
        Set<String> fromRegister = new TreeSet<>();
        Map<String, String> unreadable = new TreeMap<>();
        for (Threat threat : threats) {
            for (String place : threat.where().split(",")) {
                Matcher where = WHERE.matcher(place.trim());
                if (!where.matches() || !BOUNDARIES.containsKey(where.group(1))) {
                    unreadable.put(threat.id(), "'" + place.trim() + "' is not B<1-10>:<S|T|R|I|D|E>");
                    continue;
                }
                fromRegister.add(where.group(1) + ":" + where.group(2) + " " + threat.id());
            }
        }
        assertThat(unreadable).as("every place in the register's Where column parses").isEmpty();
        assertThat(fromMatrix)
                .as("a threat at a boundary in one place and not the other is a threat the model both claims and"
                        + " omits: section 2's cells and section 3's Where column must agree exactly")
                .isEqualTo(fromRegister);
    }

    @Test
    @DisplayName("every threat is rated by the written rule, and the rule is the one this guard holds")
    void everyThreatIsRatedByTheWrittenRule() {
        assertThat(writtenRule)
                .as("%s section 1.3's table is the rule: it changes here and there together, never alone", MODEL)
                .isEqualTo(RULE);
        Map<String, String> problems = new TreeMap<>();
        Set<String> seen = new TreeSet<>();
        for (Threat threat : threats) {
            if (!seen.add(threat.id())) {
                problems.put(threat.id(), "appears twice in the register");
            }
            if (!LIKELIHOODS.contains(threat.likelihood())) {
                problems.put(threat.id(), "likelihood '" + threat.likelihood() + "' is not one of " + LIKELIHOODS);
                continue;
            }
            if (!IMPACTS.contains(threat.impact())) {
                problems.put(threat.id(), "impact '" + threat.impact() + "' is not one of " + IMPACTS);
                continue;
            }
            if (!RATINGS.contains(threat.rating())) {
                problems.put(threat.id(), "rating '" + threat.rating() + "' is not one of " + RATINGS);
                continue;
            }
            String ruled = RULE.get(threat.impact()).get(LIKELIHOODS.indexOf(threat.likelihood()));
            if (!ruled.equals(threat.rating())) {
                problems.put(threat.id(), "is rated " + threat.rating() + ", but the rule gives " + ruled + " for "
                        + threat.likelihood() + " likelihood and " + threat.impact() + " impact");
            }
        }
        assertThat(problems).as("ratings by the rule of section 1.3").isEmpty();
    }

    @Test
    @DisplayName("every threat has a disposition, and a CONTROL threat has at least one row")
    void everyThreatHasADisposition() {
        Set<String> controlled = controls.stream().map(Control::threat).collect(Collectors.toSet());
        LocalDate today = LocalDate.now(Clock.systemUTC());
        Map<String, String> problems = new TreeMap<>();
        for (Threat threat : threats) {
            String disposition = threat.disposition();
            if (disposition.equals("CONTROL")) {
                if (!controlled.contains(threat.id())) {
                    problems.put(threat.id(), "is CONTROL with no row in section 4");
                }
            } else if (disposition.equals("PROPOSED ACCEPTANCE")) {
                continue; // allowed below HIGH; the next test refuses it at HIGH and above
            } else {
                Matcher accepted = ACCEPTED.matcher(disposition);
                if (!accepted.matches()) {
                    problems.put(threat.id(), "has no disposition this register defines: '" + disposition + "'"
                            + " (CONTROL, ACCEPTED by <owner> on <YYYY-MM-DD>[, until <YYYY-MM-DD>], or PROPOSED"
                            + " ACCEPTANCE)");
                    continue;
                }
                Optional<String> invalid = invalidDate(accepted.group(2));
                if (accepted.group(1).isBlank() || invalid.isPresent()) {
                    problems.put(threat.id(), "is accepted without a named owner or a real date");
                    continue;
                }
                if (accepted.group(3) != null) {
                    if (invalidDate(accepted.group(3)).isPresent()) {
                        problems.put(threat.id(), "has an acceptance whose expiry is not a date");
                    } else if (LocalDate.parse(accepted.group(3)).isBefore(today)) {
                        problems.put(threat.id(), "has an acceptance that expired on " + accepted.group(3)
                                + ": the owner renews it, or a control replaces it");
                    }
                }
            }
        }
        assertThat(problems).as("dispositions").isEmpty();
    }

    @Test
    @DisplayName("no CRITICAL or HIGH threat is left without a control or the owner's dated acceptance")
    void noCriticalOrHighThreatIsLeftWithoutAControl() {
        Set<String> controlled = controls.stream().map(Control::threat).collect(Collectors.toSet());
        Map<String, String> problems = new TreeMap<>();
        for (Threat threat : threats) {
            if (!threat.rating().equals("CRITICAL") && !threat.rating().equals("HIGH")) {
                continue;
            }
            boolean hasControl = threat.disposition().equals("CONTROL") && controlled.contains(threat.id());
            boolean ownerAccepted = ACCEPTED.matcher(threat.disposition()).matches();
            if (!hasControl && !ownerAccepted) {
                problems.put(threat.id(), "is " + threat.rating() + " with disposition '" + threat.disposition()
                        + "': only the owner accepts a risk, so until the owner does, a CRITICAL or HIGH"
                        + " threat needs a control");
            }
        }
        assertThat(problems).as("CRITICAL and HIGH threats").isEmpty();
    }

    @Test
    @DisplayName("every control row cites evidence that exists, in the form its state requires")
    void everyControlCitesEvidenceThatExists() {
        Set<String> registered = threats.stream().map(Threat::id).collect(Collectors.toSet());
        Map<String, String> problems = new LinkedHashMap<>();
        for (int i = 0; i < controls.size(); i++) {
            Control row = controls.get(i);
            String where = "section 4 row " + (i + 1) + " (" + row.threat() + ", '" + abbreviate(row.control()) + "')";
            if (!registered.contains(row.threat())) {
                problems.put(where, "names a threat the register does not hold");
            }
            if (!STATES.contains(row.state())) {
                problems.put(where, "has state '" + row.state() + "', not one of " + STATES);
                continue;
            }
            List<Citation> citations = EvidenceCitations.citationsIn(row.evidence());
            if (citations.isEmpty()) {
                problems.put(where, "cites no evidence");
                continue;
            }
            List<String> rowProblems = new ArrayList<>();
            for (Citation citation : citations) {
                switch (citation) {
                    case Unreadable unreadable ->
                            rowProblems.add("`" + unreadable.token() + "` is neither a repository path nor a task id");
                    case PathCitation path -> {
                        if (!row.state().equals("EXISTS")) {
                            rowProblems.add("`" + path.path() + "`: a " + row.state() + " row cites a task id, never"
                                    + " a file - a built control is an EXISTS row");
                        } else {
                            EvidenceCitations.problemWith(root, path).ifPresent(problem -> rowProblems.add(
                                    "`" + path.path() + path.method().map(m -> "#" + m).orElse("") + "` " + problem));
                        }
                    }
                    case TaskCitation task -> {
                        if (row.state().equals("EXISTS")) {
                            rowProblems.add("`" + task.id() + "`: an EXISTS row cites the file that holds the"
                                    + " control, never a task");
                        } else if (!statuses.containsKey(task.id())) {
                            rowProblems.add("`" + task.id() + "` is not an entry header in " + BACKLOG);
                        } else if (row.state().equals("PLANNED") && !task.id().startsWith("P15-")) {
                            rowProblems.add("`" + task.id() + "`: a PLANNED row cites a Phase 15 task; an earlier"
                                    + " remediation entry or a cross-cutting item is a REMEDIATION row");
                        } else if (row.state().equals("REMEDIATION") && task.id().startsWith("P15-")) {
                            rowProblems.add("`" + task.id() + "`: a Phase 15 task is a PLANNED row");
                        }
                    }
                }
            }
            if (!rowProblems.isEmpty()) {
                problems.put(where, String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("control evidence in %s", MODEL).isEmpty();
    }

    @Test
    @DisplayName("no promise outlives its task: a PLANNED or REMEDIATION row cites only live tasks")
    void noPromiseOutlivesItsTask() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Control row : controls) {
            if (!row.state().equals("PLANNED") && !row.state().equals("REMEDIATION")) {
                continue;
            }
            for (Citation citation : EvidenceCitations.citationsIn(row.evidence())) {
                if (!(citation instanceof TaskCitation task) || !statuses.containsKey(task.id())) {
                    continue; // the evidence test owns an unreadable or unknown citation
                }
                String status = statuses.get(task.id());
                if (!EvidenceCitations.LIVE_STATUSES.contains(status)) {
                    problems.put(row.threat() + " '" + abbreviate(row.control()) + "'",
                            task.id() + " is " + status + ". "
                                    + (status.equals("COMPLETE")
                                            ? "Its gate replaces this promise with the evidence it built (an EXISTS"
                                                    + " row), keeping a " + row.state() + " row only for a remainder"
                                                    + " another live task owns"
                                            : "A promise whose task is not live has no owner: name the task that"
                                                    + " now owns it, or record the gap with the owner"));
                }
            }
        }
        assertThat(problems).as("promises in %s whose task is no longer live", MODEL).isEmpty();
    }

    @Test
    @DisplayName("every task id the model names anywhere is a backlog entry with a readable status")
    void everyTaskTheModelNamesIsABacklogEntry() {
        Map<String, String> problems = new TreeMap<>();
        Matcher id = ANY_TASK_ID.matcher(model);
        while (id.find()) {
            String status = statuses.get(id.group(1));
            if (status == null) {
                problems.put(id.group(1), "is not an entry header in " + BACKLOG);
            } else if (status.equals("UNREADABLE")) {
                problems.put(id.group(1), "has an entry whose status cannot be read");
            }
        }
        assertThat(problems).as("task ids in %s", MODEL).isEmpty();
    }

    @Test
    @DisplayName("the register is not vacuous: what it parsed meets the recorded minimums")
    void theRegisterIsNotVacuous() {
        assertThat(boundaryOrder).as("boundaries parsed").hasSize(BOUNDARIES.size());
        assertThat(cells).as("STRIDE cells parsed: six per boundary").hasSize(BOUNDARIES.size() * CATEGORIES.size());
        assertThat(cells.stream().filter(cell -> !cell.threats().isEmpty()).count())
                .as("cells answered with threats").isGreaterThanOrEqualTo(MIN_ANSWERED_CELLS);
        assertThat(threats).as("threats parsed").hasSizeGreaterThanOrEqualTo(MIN_THREATS);
        assertThat(threats.stream().map(Threat::id).toList())
                .as("T1-T14 carried forward with the numbers PHASE_15_PLAN.md section 9.2 gave them")
                .containsAll(IntStream.rangeClosed(1, 14).mapToObj(n -> "T" + n).toList());
        assertThat(controls).as("control rows parsed").hasSizeGreaterThanOrEqualTo(MIN_CONTROL_ROWS);
        assertThat(controls.stream().filter(row -> row.state().equals("EXISTS")).count())
                .as("EXISTS rows parsed").isGreaterThanOrEqualTo(MIN_EXISTS_ROWS);
        assertThat(writtenRule).as("the rating rule's rows parsed").hasSize(RULE.size());
        assertThat(statuses).as("backlog entries parsed").hasSizeGreaterThan(400);
    }

    // ------------------------------------------------------------------ parsing

    private static void parseBoundaries(List<String> lines) {
        boundaryNames = new LinkedHashMap<>();
        boundaryOrder = new ArrayList<>();
        cells = new ArrayList<>();
        String current = null;
        for (String line : lines) {
            Matcher heading = BOUNDARY_HEADING.matcher(line);
            if (heading.matches()) {
                current = heading.group(1);
                boundaryOrder.add(current);
                boundaryNames.put(current, heading.group(2).trim());
                continue;
            }
            if (current == null || !isRow(line) || line.startsWith("| Category |")) {
                continue;
            }
            List<String> columns = columns(line);
            if (columns.size() != 3 || !CATEGORIES.containsKey(columns.get(0))) {
                unparseable.add(current + ": " + line);
                continue;
            }
            String answer = columns.get(1);
            List<String> threatIds;
            if (answer.equals("N/A")) {
                threatIds = List.of();
            } else {
                threatIds = Arrays.stream(answer.split(",")).map(String::trim).toList();
                if (threatIds.stream().anyMatch(t -> !THREAT_ID.matcher(t).matches())) {
                    unparseable.add(current + ": " + line);
                    continue;
                }
            }
            cells.add(new Cell(current, columns.get(0), threatIds, columns.get(2)));
        }
    }

    private static void parseRegister(List<String> lines) {
        threats = new ArrayList<>();
        for (String line : lines) {
            if (!isRow(line) || line.startsWith("| ID |")) {
                continue;
            }
            List<String> columns = columns(line);
            if (columns.size() != 7 || !THREAT_ID.matcher(columns.get(0)).matches()) {
                unparseable.add("register: " + line);
                continue;
            }
            threats.add(new Threat(columns.get(0), columns.get(2), columns.get(3), columns.get(4), columns.get(5),
                    columns.get(6).replace("`", "").trim()));
        }
    }

    private static void parseControls(List<String> lines) {
        controls = new ArrayList<>();
        for (String line : lines) {
            if (!isRow(line) || line.startsWith("| Threat |")) {
                continue;
            }
            List<String> columns = columns(line);
            if (columns.size() != 4 || !THREAT_ID.matcher(columns.get(0)).matches()) {
                unparseable.add("controls: " + line);
                continue;
            }
            controls.add(new Control(columns.get(0), columns.get(1), columns.get(2), columns.get(3)));
        }
    }

    private static void parseRule(List<String> lines) {
        writtenRule = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher row = RULE_ROW.matcher(line);
            if (row.matches()) {
                writtenRule.put(row.group(1), List.of(row.group(2), row.group(3), row.group(4)));
            } else if (line.startsWith("| Impact")
                    && !line.replace(" ", "").equals("|Impact\\Likelihood|HIGH|MEDIUM|LOW|")) {
                writtenRule.put("header", List.of(line)); // a reordered header is a different rule
            }
        }
    }

    /** A table row other than a separator. */
    private static boolean isRow(String line) {
        return line.startsWith("| ") && !line.startsWith("|---");
    }

    private static List<String> columns(String line) {
        String trimmed = line.trim();
        if (!trimmed.endsWith("|")) {
            return List.of();
        }
        return Arrays.stream(trimmed.substring(1, trimmed.length() - 1).split("\\|", -1))
                .map(String::trim)
                .toList();
    }

    /** The lines of the {@code ## } section whose heading starts with {@code prefix}, to the next one. */
    private static List<String> section(String prefix) {
        return between(prefix, line -> line.startsWith("## "));
    }

    /** The lines of the {@code ### } subsection whose heading starts with {@code prefix}. */
    private static List<String> subsection(String prefix) {
        return between(prefix, line -> line.startsWith("## ") || line.startsWith("### "));
    }

    private static List<String> between(String prefix, Predicate<String> end) {
        List<String> lines = new ArrayList<>();
        boolean inside = false;
        for (String line : model.split("\\R", -1)) {
            if (line.startsWith(prefix)) {
                inside = true;
                continue;
            }
            if (inside && end.test(line)) {
                break;
            }
            if (inside) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Optional<String> invalidDate(String text) {
        try {
            LocalDate.parse(text);
            return Optional.empty();
        } catch (DateTimeParseException e) {
            return Optional.of(text);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> orderedMap(Object... keysAndValues) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], (V) keysAndValues[i + 1]);
        }
        return map;
    }
}
