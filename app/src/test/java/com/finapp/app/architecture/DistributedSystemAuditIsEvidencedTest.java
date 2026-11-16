package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EvidenceCitations.Citation;
import com.finapp.app.architecture.EvidenceCitations.IssueCitation;
import com.finapp.app.architecture.EvidenceCitations.OwnerItemCitation;
import com.finapp.app.architecture.EvidenceCitations.PathCitation;
import com.finapp.app.architecture.EvidenceCitations.TaskCitation;
import com.finapp.app.architecture.EvidenceCitations.Unreadable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code DISTRIBUTED_EXECUTION.md} section 4b, the Phase 16 audit at ten ({@code P16-TSK-031}; {@code PHASE_16_PLAN.md} section 16), held
 * to its evidence.
 *
 * <p>The audit's worth is that every answer rests on something that can be re-read: a test the build runs, or a run the experiment register
 * records. So the eleven questions and the seven items are read from the plan's own section 16, never listed here; every citation resolves
 * through {@link EvidenceCitations}, the threat model's and the readiness review's grammar; and two forms are this audit's own - {@code run
 * E30 FAILED 2026-11-13}, which must be a run {@code RESILIENCE_EXPERIMENTS.md} records with that verdict on that date, and {@code owed E38},
 * which must be a registered experiment no run has filled. The backlog's probe is the first of this class's plants: a claim citing a run
 * that does not exist goes red.
 *
 * <p>The readiness review's rules carry over (its {@code ProductionReadinessIsEvidencedTest}): a document never holds an answer, a bare
 * file holds one only with a pin, a PostgreSQL- or Kafka-tier test has not run fresh since Phase 15 began and never counts, and a gap names
 * a live owner. Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the distributed-system audit answers every question of the plan with evidence that resolves (P16-TSK-031)")
class DistributedSystemAuditIsEvidencedTest {

    private static final String AUDIT = "docs/architecture/DISTRIBUTED_EXECUTION.md";
    private static final String PLAN = "docs/project/PHASE_16_PLAN.md";
    private static final String REGISTER = "docs/architecture/RESILIENCE_EXPERIMENTS.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";
    private static final String POSTPONED = "docs/project/POSTPONED_ISSUES.md";
    private static final String THREAT_MODEL = "docs/architecture/THREAT_MODEL.md";

    private static final String QUESTIONS_HEADER = "| # | Question | Answer at ten | Evidence | Ruling | Gap and owner |";
    private static final String ITEMS_HEADER = "| Item | Re-read at ten | Evidence | Ruling | Owner |";
    private static final Set<String> QUESTION_RULINGS = Set.of("PASS", "GAP");
    private static final Set<String> ITEM_RULINGS = Set.of("Confirmed", "Corrected");
    private static final String NONE = "—";

    private static final Pattern RUN = Pattern.compile("^run ([EF]\\d{2,3}) (PASSED|FAILED) (\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern OWED = Pattern.compile("^owed ([EF]\\d{2,3})$");
    private static final Pattern RECORDED = Pattern.compile("(PASSED|FAILED) on (\\d{4}-\\d{2}-\\d{2}) at \\d+ replicas");
    private static final Pattern REGISTER_ROW = Pattern.compile("^\\| ([EF]\\d{2,3}) \\|");
    private static final Pattern DATABASE_TIER = Pattern.compile("@Tag\\(\"(database|kafka|own-container)\"\\)");
    private static final Pattern POSTPONED_ROW = Pattern.compile("(?m)^\\| (PI-\\d{3}) \\| `([A-Z]+)`");
    private static final Pattern OWNER_ITEMS = Pattern.compile("(?m)^(\\d+)\\. \\*\\*");
    private static final Pattern SEPARATOR = Pattern.compile("^\\|[-:| ]+\\|$");

    private static Path root;
    private static String section;
    private static List<String> questions;
    private static List<String> items;
    private static Set<String> registered;
    private static Set<String> recordedRuns;
    private static Set<String> experimentsRun;
    private static Map<String, String> statuses;
    private static Map<String, String> postponed;
    private static Set<Integer> ownerItems;

    @BeforeAll
    static void readEverything() {
        root = EvidenceCitations.repositoryRoot();
        section = section(read(AUDIT));
        String plan = read(PLAN);
        questions = questionsOf(plan);
        items = itemsOf(plan);
        registered = new HashSet<>();
        recordedRuns = new HashSet<>();
        experimentsRun = new HashSet<>();
        for (String line : read(REGISTER).split("\n")) {
            Matcher row = REGISTER_ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            List<String> cells = cells(line);
            if (cells.size() < 12) {
                continue; // the scenario map and the designed-only table share the id form; only the experiments' tables hold a result
            }
            String id = row.group(1);
            registered.add(id);
            Matcher run = RECORDED.matcher(cells.get(cells.size() - 2));
            while (run.find()) {
                recordedRuns.add(id + " " + run.group(1) + " " + run.group(2));
                experimentsRun.add(id);
            }
        }
        statuses = EvidenceCitations.backlogStatuses(root.resolve(BACKLOG));
        postponed = new HashMap<>();
        Matcher issue = POSTPONED_ROW.matcher(read(POSTPONED));
        while (issue.find()) {
            postponed.put(issue.group(1), issue.group(2));
        }
        ownerItems = new HashSet<>();
        Matcher item = OWNER_ITEMS.matcher(read(THREAT_MODEL));
        while (item.find()) {
            ownerItems.add(Integer.parseInt(item.group(1)));
        }
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------ the real audit

    @Test
    @DisplayName("not vacuous: the plan's eleven questions and seven items, the register's runs and the owners were all read")
    void everythingWasRead() {
        assertThat(questions).as("the plan's section 16 questions").hasSize(11).contains("concurrency correctness", "resource isolation");
        assertThat(items).as("the plan's section 16 items").hasSize(7);
        assertThat(registered).as("the register's experiments").hasSizeGreaterThan(80).contains("E01", "E38", "F01");
        assertThat(recordedRuns).as("the runs the register records").contains("E01 PASSED 2026-11-13", "E30 FAILED 2026-11-13");
        assertThat(statuses).as("the backlog's entries").containsKey("X-TSK-008");
        assertThat(postponed).as("the postponed issues").containsKeys("PI-023", "PI-025");
        assertThat(ownerItems).as("the threat model's owner items").contains(42);
    }

    @Test
    @DisplayName("every question of the plan is answered once, in the plan's order, and every item re-read once, with a ruling of the audit's")
    void everyQuestionAndItemIsRuled() {
        assertThat(problems(section)).as("%s section 4b", AUDIT).isEmpty();
    }

    // ------------------------------------------------------------------ plants: the same reading, one defect each

    @Test
    @DisplayName("the backlog's probe: a claim citing a run that does not exist is refused, and so is a run cited with a verdict it did not have")
    void aRunThatDoesNotExistIsRefused() {
        assertRefused(inRow("| 1 | Concurrency correctness |", "`run E01 PASSED 2026-11-13`", "`run E38 PASSED 2026-11-15`"),
                "run E38 PASSED 2026-11-15");
        assertRefused(inRow("| 1 | Concurrency correctness |", "`run E30 FAILED 2026-11-13`", "`run E30 PASSED 2026-11-13`"),
                "run E30 PASSED 2026-11-13");
    }

    @Test
    @DisplayName("an owed run that has run, or that is not registered, is refused; an owed run is never evidence")
    void anOwedRunIsAnOwedRun() {
        assertRefused(inRow("| 1 | Concurrency correctness |", "`owed E38`", "`owed E01`"), "owed E01");
        assertRefused(inRow("| 1 | Concurrency correctness |", "`owed E38`", "`owed E99`"), "owed E99");
        assertRefused(inRow("| A poisoned row at a sweep's head,", "`run E28 PASSED 2026-11-13`", "`owed E28`"), "owed E28");
    }

    @Test
    @DisplayName("a document, a bare file and a PostgreSQL-tier test hold nothing; an owner in the evidence column is refused")
    void onlyWhatRunsHoldsAnAnswer() {
        String row = "| 1 | Concurrency correctness |";
        String evidence = "`app/src/test/java/com/finapp/app/architecture/NoSingleInstanceAssumptionRulesTest.java`, ";
        assertRefused(inRow(row, evidence, "`docs/architecture/CAPACITY_MODEL.md`, " + evidence), "CAPACITY_MODEL.md");
        assertRefused(inRow(row, evidence, "`compose.yaml`, " + evidence), "compose.yaml");
        assertRefused(inRow(row, evidence,
                "`platform/src/test/java/com/finapp/platform/outbox/OutboxRelayTest.java#orderingIsPreservedWithinAnAggregate`, " + evidence),
                "OutboxRelayTest");
        assertRefused(inRow(row, evidence, "`PI-025`, " + evidence), "PI-025");
    }

    @Test
    @DisplayName("a GAP names a live owner: a finished task owns nothing")
    void aGapNamesALiveOwner() {
        String row = section.lines().filter(line -> line.startsWith("| 9 | Replay |")).findFirst().orElseThrow();
        String planted = row.replace("| PASS | A day's replay at load, `owed E49`, under `PI-025` |", "| GAP | `P16-TSK-017` |");
        assertThat(planted).isNotEqualTo(row);
        assertRefused(section.replace(row, planted), "P16-TSK-017");
    }

    @Test
    @DisplayName("a question left out, or an item reworded, is refused: both lists are the plan's")
    void theListsAreThePlans() {
        String row = section.lines().filter(line -> line.startsWith("| 11 | Resource isolation |")).findFirst().orElseThrow();
        assertRefused(section.replace(row + "\n", ""), "resource isolation");
        assertRefused(section.replace("| The connection budget at ten |", "| The connection budget |"), "The connection budget at ten");
    }

    /** The section with one table row changed: a plant must land in the table, never in the prose that shows the forms. */
    private static String inRow(String rowStart, String from, String to) {
        String row = section.lines().filter(line -> line.startsWith(rowStart)).findFirst().orElseThrow();
        assertThat(row).as("the row holds what the plant replaces").contains(from);
        return section.replace(row, row.replace(from, to));
    }

    private static void assertRefused(String planted, String named) {
        assertThat(planted).as("the plant changed the section").isNotEqualTo(section);
        assertThat(problems(planted)).as("refused, naming %s", named).anySatisfy(problem -> assertThat(problem).contains(named));
    }

    // ------------------------------------------------------------------ the reading

    /** Every problem with a section-4b text: the real one, or a planted copy. */
    static List<String> problems(String text) {
        List<String> problems = new ArrayList<>();
        List<List<String>> questionRows = table(text, QUESTIONS_HEADER, 6);
        List<List<String>> itemRows = table(text, ITEMS_HEADER, 5);

        List<String> asked = new ArrayList<>();
        for (List<String> row : questionRows) {
            String where = "question " + row.get(0) + " (" + row.get(1) + ")";
            asked.add(row.get(1).toLowerCase());
            if (!row.get(0).equals(String.valueOf(asked.size()))) {
                problems.add(where + ": numbered out of order");
            }
            if (!QUESTION_RULINGS.contains(row.get(4))) {
                problems.add(where + ": ruled '" + row.get(4) + "', not one of " + QUESTION_RULINGS);
            }
            problems.addAll(evidenceProblems(where, row.get(3)));
            problems.addAll(ownerProblems(where, row.get(5), row.get(4).equals("GAP")));
        }
        if (!asked.equals(questions)) {
            problems.add("the questions answered, " + asked + ", are not the plan's section 16, " + questions);
        }

        List<String> reread = new ArrayList<>();
        for (List<String> row : itemRows) {
            String where = "item '" + row.get(0) + "'";
            reread.add(row.get(0));
            if (!ITEM_RULINGS.contains(row.get(3))) {
                problems.add(where + ": ruled '" + row.get(3) + "', not one of " + ITEM_RULINGS);
            }
            problems.addAll(evidenceProblems(where, row.get(2)));
            problems.addAll(ownerProblems(where, row.get(4), false));
        }
        if (!reread.equals(items)) {
            problems.add("the items re-read, " + reread + ", are not the plan's section 16 table, " + items);
        }
        return problems;
    }

    private static List<String> evidenceProblems(String where, String cell) {
        List<String> problems = new ArrayList<>();
        boolean held = false;
        StringBuilder rest = new StringBuilder();
        for (String token : tokens(cell)) {
            Matcher run = RUN.matcher(token);
            if (run.matches()) {
                if (recordedRuns.contains(run.group(1) + " " + run.group(2) + " " + run.group(3))) {
                    held = true;
                } else {
                    problems.add(where + ": `" + token + "` is a run " + REGISTER + " does not record");
                }
            } else if (OWED.matcher(token).matches()) {
                problems.add(where + ": `" + token + "` in the evidence column: an owed run holds nothing; it goes in the gap column");
            } else {
                rest.append('`').append(token).append("` ");
            }
        }
        for (Citation citation : EvidenceCitations.citationsIn(rest.toString())) {
            if (!(citation instanceof PathCitation path)) {
                problems.add(where + ": " + describe(citation) + " in the evidence column: an owner goes in the gap column");
                continue;
            }
            String cited = "`" + path.path() + path.member().map(member -> "#" + member).orElse("") + "`";
            Optional<String> unresolved = EvidenceCitations.problemWith(root, path);
            if (unresolved.isPresent()) {
                problems.add(where + ": " + cited + " " + unresolved.get());
            } else if (path.path().startsWith("docs/")) {
                problems.add(where + ": " + cited + " is a document: it states a property and holds none");
            } else if (!path.isTestSource() && path.pins().isEmpty()) {
                problems.add(where + ": " + cited + " is a bare file: pin the text that holds the claim, or cite the test");
            } else if (path.isTestSource() && DATABASE_TIER.matcher(EvidenceCitations.read(root.resolve(path.path()))).find()) {
                problems.add(where + ": " + cited + " needs PostgreSQL or Kafka and has not run fresh: it never holds an answer");
            } else if (path.isTestSource()) {
                held = true;
            }
        }
        if (!held) {
            problems.add(where + ": cites no test the build runs and no recorded run");
        }
        return problems;
    }

    private static List<String> ownerProblems(String where, String cell, boolean ownerRequired) {
        List<String> problems = new ArrayList<>();
        if (cell.equals(NONE)) {
            if (ownerRequired) {
                problems.add(where + ": a GAP names its owner");
            }
            return problems;
        }
        boolean owned = false;
        StringBuilder rest = new StringBuilder();
        for (String token : tokens(cell)) {
            Matcher owed = OWED.matcher(token);
            if (owed.matches()) {
                if (!registered.contains(owed.group(1))) {
                    problems.add(where + ": `" + token + "` names no registered experiment");
                } else if (experimentsRun.contains(owed.group(1))) {
                    problems.add(where + ": `" + token + "` has run - cite its run, or say what is still owed");
                }
            } else if (RUN.matcher(token).matches()) {
                problems.add(where + ": `" + token + "` in the gap column: a recorded run is evidence");
            } else {
                rest.append('`').append(token).append("` ");
            }
        }
        for (Citation citation : EvidenceCitations.citationsIn(rest.toString())) {
            switch (citation) {
                case TaskCitation task -> {
                    String status = statuses.get(task.id());
                    if (status == null) {
                        problems.add(where + ": `" + task.id() + "` is not an entry of " + BACKLOG);
                    } else if (!EvidenceCitations.LIVE_STATUSES.contains(status)) {
                        problems.add(where + ": `" + task.id() + "` is " + status + ": a finished task owns nothing");
                    } else {
                        owned = true;
                    }
                }
                case IssueCitation issue -> {
                    if (!"POSTPONED".equals(postponed.get(issue.id()))) {
                        problems.add(where + ": `" + issue.id() + "` is no postponement in " + POSTPONED);
                    } else {
                        owned = true;
                    }
                }
                case OwnerItemCitation item -> {
                    if (!ownerItems.contains(item.number())) {
                        problems.add(where + ": `owner item " + item.number() + "` is not an item of " + THREAT_MODEL);
                    } else {
                        owned = true;
                    }
                }
                case PathCitation path -> problems.add(where + ": `" + path.path() + "` in the gap column: evidence goes beside the answer");
                case Unreadable unreadable -> problems.add(where + ": `" + unreadable.token() + "` is no citation the audit defines");
            }
        }
        if (ownerRequired && !owned) {
            problems.add(where + ": a GAP names a live owner: a task, a postponed issue or an owner item");
        }
        return problems;
    }

    private static String describe(Citation citation) {
        return switch (citation) {
            case TaskCitation task -> "`" + task.id() + "`";
            case IssueCitation issue -> "`" + issue.id() + "`";
            case OwnerItemCitation item -> "`owner item " + item.number() + "`";
            case Unreadable unreadable -> "`" + unreadable.token() + "`";
            case PathCitation path -> "`" + path.path() + "`";
        };
    }

    private static List<String> tokens(String cell) {
        List<String> tokens = new ArrayList<>();
        Matcher token = EvidenceCitations.BACKTICKED.matcher(cell);
        while (token.find()) {
            tokens.add(token.group(1).trim());
        }
        return tokens;
    }

    // ------------------------------------------------------------------ parsing

    private static String section(String document) {
        int start = document.indexOf("\n## 4b. ");
        assertThat(start).as("%s has its section 4b", AUDIT).isNotNegative();
        int end = document.indexOf("\n## ", start + 1);
        return document.substring(start + 1, end < 0 ? document.length() : end);
    }

    /** The plan's list, in its own words: "... partial outages: a, b, ..., j and k, each answered with a citation". */
    private static List<String> questionsOf(String plan) {
        String flat = plan.replaceAll("\\s+", " ");
        Matcher list = Pattern.compile("partial outages: (.+?), each answered with a citation").matcher(flat);
        assertThat(list.find()).as("%s section 16 lists the audit's questions", PLAN).isTrue();
        String[] parts = list.group(1).replace(" and ", ", ").split(", ");
        return Arrays.stream(parts).map(String::trim).map(String::toLowerCase).toList();
    }

    /** The first column of the plan's section 16 table, in order. */
    private static List<String> itemsOf(String plan) {
        int start = plan.indexOf("| Item | Reading today | Single-instance assumption? |");
        assertThat(start).as("%s section 16 has its table of items", PLAN).isNotNegative();
        List<String> found = new ArrayList<>();
        for (String line : plan.substring(start).split("\n")) {
            if (!line.startsWith("|")) {
                break;
            }
            if (line.startsWith("| Item |") || SEPARATOR.matcher(line).matches()) {
                continue;
            }
            found.add(cells(line).get(0));
        }
        return found;
    }

    private static List<List<String>> table(String text, String header, int width) {
        int start = text.indexOf(header);
        List<List<String>> rows = new ArrayList<>();
        if (start < 0) {
            return rows;
        }
        for (String line : text.substring(start).split("\n")) {
            if (!line.startsWith("|")) {
                break;
            }
            if (line.equals(header) || SEPARATOR.matcher(line).matches()) {
                continue;
            }
            List<String> cells = cells(line);
            assertThat(cells).as("a row of %s columns: %s", width, line).hasSize(width);
            rows.add(cells);
        }
        return rows;
    }

    private static List<String> cells(String line) {
        String inner = line.substring(1, line.length() - (line.endsWith("|") ? 1 : 0));
        return Arrays.stream(inner.split("\\|", -1)).map(String::trim).toList();
    }
}
