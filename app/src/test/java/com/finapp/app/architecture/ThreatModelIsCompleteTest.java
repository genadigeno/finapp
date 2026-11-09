package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EvidenceCitations.Citation;
import com.finapp.app.architecture.EvidenceCitations.IssueCitation;
import com.finapp.app.architecture.EvidenceCitations.OwnerItemCitation;
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
 *   <li>every line that looks like a row of a register table parses as one, indented or malformed included;
 *   <li>the ten trust boundaries of {@code PHASE_15_PLAN.md} §9.1 are present by id and name;
 *   <li>each boundary answers all six STRIDE categories, with threats or {@code N/A} and a reason;
 *   <li>the boundary matrix and the register agree, triple for triple;
 *   <li>every rating is the cell of the written rule, and the rule is the one this test holds;
 *   <li>every threat has a disposition: a {@code CONTROL} threat has a row that is not a gap, an acceptance
 *       is the owner's with a real date and is not past its {@code until};
 *   <li>no CRITICAL or HIGH threat is left without a control or the owner's acceptance;
 *   <li>every row's evidence is in the form its state requires and resolves: an {@code EXISTS} row's files
 *       exist, each {@code #member} is a test declared in its file (a whole test file declares at least one),
 *       each content pin holds, and a CRITICAL or HIGH threat's {@code EXISTS} row never rests on a document
 *       under {@code docs/}, nor on a bare non-test file without a pin; a {@code GAP} row names a postponed
 *       issue or an owner item, an owner and a date;
 *   <li>no promise outlives its task: every task the document cites - in a row or in its prose, long or short
 *       id - is live, the register's own task excepted; a red names the task and the section;
 *   <li>every task id the document names, a short {@code -NNN} included, is a backlog entry;
 *   <li>the register's summary line counts what the register holds;
 *   <li>once Phase 15 is complete ({@code CURRENT_STATE.md}), every CRITICAL or HIGH threat has an
 *       {@code EXISTS} row or the owner's acceptance ({@code PHASE_GATES.md} §5 Phase 15 criterion 1 as written),
 *       and no acceptance is still only proposed;
 *   <li>the register is not vacuous: what it parsed meets structural floors.
 * </ol>
 *
 * <p><strong>The choice in rule 9.</strong> A {@code PLANNED} row is a promise. When its task becomes
 * {@code COMPLETE}, the promise is kept, and the evidence belongs in the row, or it is broken, and the row is a
 * {@code GAP}. Either way the register is wrong until that task's gate rewrites it, so the flip turns this test
 * red. A postponed or cut task's promise has no owner, so it is refused too, until it is rewritten as a
 * {@code GAP} naming the postponed issue or owner item that holds it. The same holds of the prose: a residual or
 * an owner item saying what a task will do is a promise too, so a finished task's id anywhere in the model is red
 * until its gate rewrites the sentence. The one exception is {@code P15-TSK-001}, cited as the register's author.
 * The cost is deliberate: every Phase 15 gate edits the model.
 *
 * <p>Hermetic: it reads files only ({@code THREAT_MODEL.md}, {@code BACKLOG.md}, {@code POSTPONED_ISSUES.md},
 * Phase 15's status line in {@code CURRENT_STATE.md}, and the evidence the register cites). It carries the
 * {@code architecture} tag to run with the package's other document guards ({@code AdrRegistersAreReconciledTest}),
 * and {@code app/build.gradle.kts} declares the documents and the non-code evidence it reads as test inputs.
 */
@Tag("architecture")
@DisplayName("the threat register is complete and its evidence resolves (P15-TSK-001)")
class ThreatModelIsCompleteTest {

    private static final String MODEL = "docs/architecture/THREAT_MODEL.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";
    private static final String POSTPONED = "docs/project/POSTPONED_ISSUES.md";
    private static final String STATE = "docs/project/CURRENT_STATE.md";
    private static final String PHASE_15_HEADING = "**Phase 15 — Production Hardening**";

    /**
     * The documents tree, never built evidence for a CRITICAL or HIGH threat, pinned or not. A rehearsal record
     * ({@code P15-TSK-032}'s) counts only once a gate widens this grammar deliberately, naming the records'
     * directory and form, with a rule here holding them.
     */
    private static final String DOCUMENTS = "docs/";

    /** The register's own task: the model cites it as its author, history rather than a promise. */
    private static final String OWN_TASK = "P15-TSK-001";

    /** A completed phase's status line, as `CURRENT_STATE.md` writes it: the token itself, not any mention. */
    private static final Pattern COMPLETED_STATUS = Pattern.compile("^Status:\\s+\\S+\\s+\\*\\*`COMPLETE`\\*\\*");

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
    private static final Set<String> STATES = Set.of("EXISTS", "PLANNED", "REMEDIATION", "GAP");

    /** Who may accept a risk: the model's §1.4 names the owner alone. */
    private static final Set<String> ACCEPTING_ROLES = Set.of("the owner");

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
     * Structural floors, well below today's counts so an honest demotion or a task's rewrite never forces an edit
     * here, and well above what a renamed heading or a broken table would leave. Threats are never deleted
     * (§1.5), so their floor is today's count; the rest are floors, not totals.
     */
    private static final int MIN_THREATS = 47;
    private static final int MIN_ANSWERED_CELLS = 50;
    private static final int MIN_CONTROL_ROWS = 100;
    private static final int MIN_EXISTS_ROWS = 40;

    private static final Pattern BOUNDARY_HEADING = Pattern.compile("^### (B\\d+) — (.+)$");
    private static final Pattern THREAT_ID = Pattern.compile("^T\\d+$");
    private static final Pattern WHERE = Pattern.compile("^(B\\d+):([STRIDE])$");
    private static final Pattern ACCEPTED =
            Pattern.compile("^ACCEPTED by (.+?) on (\\d{4}-\\d{2}-\\d{2})(?:, until (\\d{4}-\\d{2}-\\d{2}))?$");
    private static final Pattern GAP_OWNER = Pattern.compile("owner: ([^,;]+), since (\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern RULE_ROW =
            Pattern.compile("^\\| (SEVERE|HIGH|MEDIUM|LOW) \\| (\\w+) \\| (\\w+) \\| (\\w+) \\|\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("^\\|[-:| ]+\\|$");
    private static final Pattern SUMMARY = Pattern.compile(
            "(?m)^\\*\\*Summary\\.\\*\\* (\\d+) threats: (\\d+) CRITICAL, (\\d+) HIGH, (\\d+) MEDIUM, (\\d+) LOW\\."
                    + " (\\d+) (?:is|are) `CONTROL`\\.");
    private static final Pattern ANY_TASK_ID =
            Pattern.compile("`((?:P\\d+-(?:TSK|TST|DOC|REM)-\\d{3})|X-TSK-\\d{3})`");
    private static final Pattern SHORT_TASK_ID = Pattern.compile("`-(\\d{3})`");
    private static final Pattern OWNER_ITEMS = Pattern.compile("(?m)^(\\d+)\\. \\*\\*");

    private record Cell(String boundary, String category, List<String> threats, String note) {}

    private record Threat(String id, String where, String likelihood, String impact, String rating,
            String disposition) {}

    private record Control(String threat, String control, String state, String evidence) {}

    private static Path root;
    private static String model;
    private static Map<String, String> statuses;
    private static Set<String> postponedIssues;
    private static Set<Integer> ownerItems;
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
        postponedIssues = EvidenceCitations.postponedIssues(root.resolve(POSTPONED));
        ownerItems = new TreeSet<>();
        Matcher item = OWNER_ITEMS.matcher(String.join("\n", section("## 6. ")));
        while (item.find()) {
            ownerItems.add(Integer.parseInt(item.group(1)));
        }
        unparseable = new ArrayList<>();
        parseBoundaries(section("## 2. "));
        parseRegister(section("## 3. "));
        parseControls(section("## 4. "));
        parseRule(subsection("### 1.3 "));
    }

    @Test
    @DisplayName("every line that looks like a row of the matrix, the register or the controls parses as one")
    void everyRowParses() {
        // A row the parser skips is a threat or a control every other test here silently stops checking - the
        // shape P1-TSK-024 found in the mutation register's parser, closed at design time as
        // AdrRegistersAreReconciledTest closes it for the ADR index. Any line of a register section whose first
        // character, spaces aside, is a pipe is a row: indented, missing its spacing or short of a column, it is
        // red here rather than invisible everywhere else.
        assertThat(unparseable)
                .as("lines in %s's matrix, register or controls that look like rows and did not parse", MODEL)
                .isEmpty();
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
    @DisplayName("every threat has a disposition: CONTROL with a row that is not a gap, or the owner's dated acceptance")
    void everyThreatHasADisposition() {
        Set<String> controlled = controls.stream()
                .filter(row -> !row.state().equals("GAP"))
                .map(Control::threat)
                .collect(Collectors.toSet());
        Set<String> withAnyRow = controls.stream().map(Control::threat).collect(Collectors.toSet());
        LocalDate today = LocalDate.now(Clock.systemUTC());
        Map<String, String> problems = new TreeMap<>();
        for (Threat threat : threats) {
            String disposition = threat.disposition();
            if (disposition.equals("CONTROL")) {
                if (!controlled.contains(threat.id())) {
                    problems.put(threat.id(), "is CONTROL with no row in section 4 that is not a GAP");
                }
            } else if (disposition.equals("PROPOSED ACCEPTANCE")) {
                if (!withAnyRow.contains(threat.id())) {
                    problems.put(threat.id(), "is proposed for acceptance with no row saying what stands meanwhile");
                }
            } else {
                Matcher accepted = ACCEPTED.matcher(disposition);
                if (!accepted.matches()) {
                    problems.put(threat.id(), "has no disposition this register defines: '" + disposition + "'"
                            + " (CONTROL, ACCEPTED by the owner on <YYYY-MM-DD>[, until <YYYY-MM-DD>], or PROPOSED"
                            + " ACCEPTANCE)");
                    continue;
                }
                if (!ACCEPTING_ROLES.contains(accepted.group(1)) || invalidDate(accepted.group(2)).isPresent()) {
                    problems.put(threat.id(), "is accepted by '" + accepted.group(1) + "' on '" + accepted.group(2)
                            + "': only " + ACCEPTING_ROLES + " accepts a risk, on a real date");
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
    @DisplayName("no CRITICAL or HIGH threat is left without a control or the owner's acceptance")
    void noCriticalOrHighThreatIsLeftWithoutAControl() {
        Set<String> controlled = controls.stream()
                .filter(row -> !row.state().equals("GAP"))
                .map(Control::threat)
                .collect(Collectors.toSet());
        Map<String, String> problems = new TreeMap<>();
        for (Threat threat : threats) {
            if (!severe(threat)) {
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
    @DisplayName("every row's evidence resolves in the form its state requires")
    void everyControlCitesEvidenceThatExists() {
        Map<String, Threat> registered = threats.stream()
                .collect(Collectors.toMap(Threat::id, threat -> threat, (a, b) -> a, TreeMap::new));
        Map<String, String> problems = new LinkedHashMap<>();
        for (int i = 0; i < controls.size(); i++) {
            Control row = controls.get(i);
            String where = "section 4 row " + (i + 1) + " (" + row.threat() + ", '" + abbreviate(row.control()) + "')";
            Threat threat = registered.get(row.threat());
            if (threat == null) {
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
                rowProblems.addAll(problemsWith(row, threat, citation));
            }
            if (row.state().equals("GAP")) {
                if (citations.stream().noneMatch(c -> c instanceof IssueCitation || c instanceof OwnerItemCitation)) {
                    rowProblems.add("a GAP row names the postponed issue (`PI-NNN`) or the owner item (`owner item N`)"
                            + " that holds it");
                }
                Matcher owner = GAP_OWNER.matcher(row.evidence());
                if (!owner.find() || invalidDate(owner.group(2)).isPresent()) {
                    rowProblems.add("a GAP row states 'owner: <who>, since <YYYY-MM-DD>'");
                }
            }
            if (!rowProblems.isEmpty()) {
                problems.put(where, String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("control evidence in %s", MODEL).isEmpty();
    }

    @Test
    @DisplayName("no promise outlives its task: every task the model cites, in a row or in prose, short ids included, is live")
    void noPromiseOutlivesItsTask() {
        Map<String, String> problems = new LinkedHashMap<>();
        // Prose first: a residual, an owner item or a scope note saying what a task will do reads as true until that
        // task's gate rewrites it ("SASL_PLAINTEXT is admitted today; -026 widens the guard"). Every line outside
        // section 4's rows is read; the rows are judged below, with the advice their state needs.
        String heading = "the preamble";
        for (String line : model.split("\\R", -1)) {
            if (line.startsWith("## ") || line.startsWith("### ")) {
                heading = line.replaceFirst("^#+ ", "");
                continue;
            }
            List<String> columns = columns(line);
            if (columns.size() == 4 && THREAT_ID.matcher(columns.get(0)).matches()) {
                continue; // a section 4 row
            }
            Set<String> cited = new TreeSet<>();
            Matcher id = ANY_TASK_ID.matcher(line);
            while (id.find()) {
                cited.add(id.group(1));
            }
            Matcher shortId = SHORT_TASK_ID.matcher(line);
            while (shortId.find()) {
                cited.add("P15-TSK-" + shortId.group(1));
            }
            for (String task : cited) {
                String status = statuses.get(task);
                if (task.equals(OWN_TASK) || status == null || EvidenceCitations.LIVE_STATUSES.contains(status)) {
                    continue; // an unknown id is the backlog-entry rule's
                }
                problems.merge(task + " in '" + heading + "'", task + " is " + status + ". "
                        + (status.equals("COMPLETE")
                                ? "Its gate rewrites what this section says of it: a residual it closed is struck, a"
                                        + " promise it kept becomes a section 4 EXISTS row, and the register cites a"
                                        + " finished task by the evidence it left, never by its id"
                                : "Name the task that now owns this, or the postponed issue or owner item that holds"
                                        + " it"), (a, b) -> a);
            }
        }
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
                                                    + " another live task owns, or a GAP row for a remainder nobody"
                                                    + " owns"
                                            : "A promise whose task is not live has no owner: name the task that"
                                                    + " now owns it, or rewrite it as a GAP row naming the postponed"
                                                    + " issue or the owner item that holds it"));
                }
            }
        }
        assertThat(problems).as("tasks %s cites that are no longer live (row or section)", MODEL).isEmpty();
    }

    @Test
    @DisplayName("every task id the model names, a short -NNN included, is a backlog entry with a readable status")
    void everyTaskTheModelNamesIsABacklogEntry() {
        Map<String, String> problems = new TreeMap<>();
        Set<String> named = new TreeSet<>();
        Matcher id = ANY_TASK_ID.matcher(model);
        while (id.find()) {
            named.add(id.group(1));
        }
        Matcher shortId = SHORT_TASK_ID.matcher(model);
        while (shortId.find()) {
            named.add("P15-TSK-" + shortId.group(1));
        }
        for (String task : named) {
            String status = statuses.get(task);
            if (status == null) {
                problems.put(task, "is not an entry header in " + BACKLOG);
            } else if (status.equals("UNREADABLE")) {
                problems.put(task, "has an entry whose status cannot be read");
            }
        }
        // Was a count (more than 30), and a count decays by design: an id leaves this document when its task finishes, so
        // the floor fell to 30 at P15-TSK-039's gate. What cannot leave is the register's author, §1.5's one exception.
        assertThat(named).as("the model's task ids were read, short and long").contains("P15-TSK-001");
        assertThat(problems).as("task ids in %s", MODEL).isEmpty();
    }

    @Test
    @DisplayName("section 3's summary line counts what the register holds")
    void theSummaryCountsWhatTheRegisterHolds() {
        // A summary is a second copy of the register, and second copies decay: the "words left behind" class.
        Matcher summary = SUMMARY.matcher(String.join("\n", section("## 3. ")));
        assertThat(summary.find())
                .as("%s section 3 must carry its summary line in the form the guard reads", MODEL)
                .isTrue();
        Map<String, Long> byRating = threats.stream()
                .collect(Collectors.groupingBy(Threat::rating, TreeMap::new, Collectors.counting()));
        List<Long> counted = List.of(
                (long) threats.size(),
                byRating.getOrDefault("CRITICAL", 0L),
                byRating.getOrDefault("HIGH", 0L),
                byRating.getOrDefault("MEDIUM", 0L),
                byRating.getOrDefault("LOW", 0L),
                threats.stream().filter(threat -> threat.disposition().equals("CONTROL")).count());
        List<Long> written = IntStream.rangeClosed(1, 6)
                .mapToObj(group -> Long.parseLong(summary.group(group)))
                .toList();
        assertThat(written)
                .as("the summary's threat, CRITICAL, HIGH, MEDIUM, LOW and CONTROL counts against the register's")
                .isEqualTo(counted);
    }

    @Test
    @DisplayName("once Phase 15 is complete, every CRITICAL or HIGH threat has built evidence or the owner's acceptance")
    void atThePhaseExitEveryCriticalOrHighThreatHasBuiltEvidence() {
        // PHASE_GATES.md section 5 Phase 15 criterion 1: "every CRITICAL and HIGH threat names a control whose
        // evidence (a test, a policy, a file) exists, or an acceptance with an owner and a date". Before the exit a
        // PLANNED row is an honest answer for a control not yet built; at the exit it is not, and the phase's own
        // status line says which applies. Rule 9 already turns a finished task's promise red; this catches the one
        // promise it cannot, a CRITICAL or HIGH threat answered at the exit only by a REMEDIATION row.
        if (!phase15IsComplete()) {
            return;
        }
        Set<String> built = controls.stream()
                .filter(row -> row.state().equals("EXISTS"))
                .map(Control::threat)
                .collect(Collectors.toSet());
        Map<String, String> problems = new TreeMap<>();
        for (Threat threat : threats) {
            if (severe(threat) && !built.contains(threat.id()) && !ACCEPTED.matcher(threat.disposition()).matches()) {
                problems.put(threat.id(), "is " + threat.rating() + " at Phase 15's exit with no EXISTS row and no"
                        + " acceptance by the owner");
            }
        }
        assertThat(problems).as("criterion 1 at the exit").isEmpty();
    }

    @Test
    @DisplayName("once Phase 15 is complete, no acceptance is still only proposed, at any rating")
    void atThePhaseExitNoAcceptanceIsStillProposed() {
        // A proposal is the owner's decision waiting. Allowed below HIGH while the phase runs, it must be decided
        // by the exit: accepted with a date, or answered by a control.
        if (!phase15IsComplete()) {
            return;
        }
        assertThat(threats.stream()
                        .filter(threat -> threat.disposition().equals("PROPOSED ACCEPTANCE"))
                        .map(Threat::id)
                        .toList())
                .as("threats still awaiting the owner's decision at Phase 15's exit")
                .isEmpty();
    }

    @Test
    @DisplayName("the register is not vacuous: what it parsed meets structural floors")
    void theRegisterIsNotVacuous() {
        assertThat(boundaryOrder).as("boundaries parsed").hasSize(BOUNDARIES.size());
        assertThat(cells).as("STRIDE cells parsed: six per boundary").hasSize(BOUNDARIES.size() * CATEGORIES.size());
        assertThat(cells.stream().filter(cell -> !cell.threats().isEmpty()).count())
                .as("cells answered with threats").isGreaterThanOrEqualTo(MIN_ANSWERED_CELLS);
        assertThat(threats).as("threats parsed; a threat is never deleted").hasSizeGreaterThanOrEqualTo(MIN_THREATS);
        assertThat(threats.stream().map(Threat::id).toList())
                .as("T1-T14 carried forward with the numbers PHASE_15_PLAN.md section 9.2 gave them")
                .containsAll(IntStream.rangeClosed(1, 14).mapToObj(n -> "T" + n).toList());
        assertThat(controls).as("control rows parsed").hasSizeGreaterThanOrEqualTo(MIN_CONTROL_ROWS);
        assertThat(controls.stream().filter(row -> row.state().equals("EXISTS")).count())
                .as("EXISTS rows parsed").isGreaterThanOrEqualTo(MIN_EXISTS_ROWS);
        Set<String> withARow = controls.stream().map(Control::threat).collect(Collectors.toSet());
        assertThat(threats.stream()
                        .filter(threat -> !ACCEPTED.matcher(threat.disposition()).matches())
                        .map(Threat::id)
                        .filter(id -> !withARow.contains(id))
                        .toList())
                .as("every threat not accepted by the owner has at least one row in section 4")
                .isEmpty();
        assertThat(writtenRule).as("the rating rule's rows parsed").hasSize(RULE.size());
        assertThat(statuses).as("backlog entries parsed").hasSizeGreaterThan(400);
        assertThat(postponedIssues).as("postponed issues parsed").contains("PI-004");
        assertThat(ownerItems).as("the model's owner items parsed").isNotEmpty();
    }

    // ------------------------------------------------------------------ evidence

    /** What is wrong with one citation of one row, given the row's state and its threat's rating. */
    private static List<String> problemsWith(Control row, Threat threat, Citation citation) {
        List<String> problems = new ArrayList<>();
        String state = row.state();
        switch (citation) {
            case Unreadable unreadable ->
                    problems.add("`" + unreadable.token() + "` is no citation this register defines");
            case PathCitation path -> {
                if (!state.equals("EXISTS")) {
                    problems.add("`" + path.path() + "`: only an EXISTS row cites a file");
                    break;
                }
                EvidenceCitations.problemWith(root, path).ifPresent(problem -> problems.add(
                        "`" + path.path() + path.member().map(m -> "#" + m).orElse("") + "` " + problem));
                if (threat != null && severe(threat) && path.path().startsWith(DOCUMENTS)) {
                    problems.add("`" + path.path() + "` is a document cited as built evidence for a " + threat.rating()
                            + " threat: a document states a control, pinned or not, and never builds one; cite the"
                            + " test, policy or file that holds it (PRODUCTION_READINESS.md's 'what never counts')");
                } else if (threat != null && severe(threat) && !path.isTestSource() && path.pins().isEmpty()) {
                    problems.add("`" + path.path() + "` is a bare file cited as built evidence for a "
                            + threat.rating() + " threat: its existence proves nothing, so back it with a test that"
                            + " asserts the property, or pin the text that holds it (`contains: ...`, `lacks: ...`)");
                }
            }
            case TaskCitation task -> {
                if (!state.equals("PLANNED") && !state.equals("REMEDIATION")) {
                    problems.add("`" + task.id() + "`: a task is cited by a PLANNED or REMEDIATION row, never " + state);
                } else if (!statuses.containsKey(task.id())) {
                    problems.add("`" + task.id() + "` is not an entry header in " + BACKLOG);
                } else if (state.equals("PLANNED") && !task.id().startsWith("P15-")) {
                    problems.add("`" + task.id() + "`: a PLANNED row cites a Phase 15 task; an earlier remediation"
                            + " entry or a cross-cutting item is a REMEDIATION row");
                } else if (state.equals("REMEDIATION") && task.id().startsWith("P15-")) {
                    problems.add("`" + task.id() + "`: a Phase 15 task is a PLANNED row");
                }
            }
            case IssueCitation issue -> {
                if (!state.equals("GAP")) {
                    problems.add("`" + issue.id() + "`: a postponed issue is cited by a GAP row");
                } else if (!postponedIssues.contains(issue.id())) {
                    problems.add("`" + issue.id() + "` is not a header in " + POSTPONED);
                }
            }
            case OwnerItemCitation item -> {
                if (!state.equals("GAP")) {
                    problems.add("`owner item " + item.number() + "`: an owner item is cited by a GAP row");
                } else if (!ownerItems.contains(item.number())) {
                    problems.add("`owner item " + item.number() + "` is not a numbered item of section 6");
                }
            }
        }
        return problems;
    }

    private static boolean severe(Threat threat) {
        return threat.rating().equals("CRITICAL") || threat.rating().equals("HIGH");
    }

    private static boolean phase15IsComplete() {
        List<String> state = Arrays.asList(EvidenceCitations.read(root.resolve(STATE)).split("\\R", -1));
        int heading = state.indexOf(PHASE_15_HEADING);
        assertThat(heading)
                .as("%s must still carry '%s', or the exit rules read nothing", STATE, PHASE_15_HEADING)
                .isNotNegative();
        String status = state.get(heading + 1);
        assertThat(status).as("the line after Phase 15's heading is its status").startsWith("Status:");
        return COMPLETED_STATUS.matcher(status).find();
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Whether a line of a register section is meant as a table row: its first character, spaces aside, is a pipe.
     * A separator or the table's header is skipped; every other such line must parse, and one that is indented
     * is recorded as unparseable before anything else looks at it.
     */
    private static boolean tableLine(String line, String where, String header) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("|")) {
            return false;
        }
        if (!line.equals(line.stripLeading()) || !line.startsWith("| ")) {
            if (!SEPARATOR.matcher(trimmed).matches()) {
                unparseable.add(where + ": " + line);
            }
            return false;
        }
        return !SEPARATOR.matcher(trimmed).matches() && !line.startsWith(header);
    }

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
            if (!tableLine(line, current == null ? "matrix" : current, "| Category |")) {
                continue;
            }
            List<String> columns = columns(line);
            if (current == null || columns.size() != 3 || !CATEGORIES.containsKey(columns.get(0))) {
                unparseable.add((current == null ? "matrix" : current) + ": " + line);
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
            if (!tableLine(line, "register", "| ID |")) {
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
            if (!tableLine(line, "controls", "| Threat |")) {
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
