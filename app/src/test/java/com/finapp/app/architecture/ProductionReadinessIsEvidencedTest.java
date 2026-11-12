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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The readiness review's every cell is ruled, and every ruling rests on evidence that resolves or names who holds its gap
 * ({@code P15-TSK-034}).
 *
 * <p>{@code docs/architecture/PRODUCTION_READINESS.md} rules each deployable unit (six tables of thirteen rows) and each
 * module of {@code finapp-app} (twenty-three tables of four rows). A review like this fails by reading as assurance it does
 * not hold, so each rule below refuses one way of doing that:
 *
 * <ol>
 *   <li>every line of sections 3 and 4 that looks like a table row parses as one: five columns, a dimension the standard
 *       names, each unit's thirteen rows and each module's four exactly once, the modules the build's own;
 *   <li>every cell is ruled: {@code MET as rehearsed}, {@code GAP} or {@code N/A}, and the multi-instance row {@code PASS},
 *       {@code GAP} or {@code N/A}; none is left {@code —};
 *   <li>a {@code MET} or {@code PASS} cell cites repository paths that resolve through the threat model's mechanism
 *       ({@link EvidenceCitations}): the file exists, a {@code #member} is a test declared in it, every pin holds. It never
 *       cites a document under {@code docs/} (a document states a property and holds none), a file that is not a test
 *       without a content pin, or a {@code database}, {@code kafka} or {@code own-container} test, none of which has run
 *       fresh since Phase 15 began ({@code POSTPONED_ISSUES.md} PI-023); a {@code PASS} cites a test, never design alone;
 *   <li>a {@code GAP} cell names who holds it: a live backlog entry, a postponed issue the register holds
 *       {@code POSTPONED}, or an owner item of {@code THREAT_MODEL.md} section 6; never a finished task, which owns nothing.
 *       What it cites as holding obeys rule 3's forms, a database-tier test admitted only beside PI-023, which owes it;
 *   <li>an {@code N/A} cell states its reason;
 *   <li>the evidence column cites no task, issue or item (a promise is not evidence), and the gap column no path;
 *   <li>every open remediation entry ({@code P14-REM-*}), every postponed issue, and {@code X-TSK-008} (the owner's routed
 *       note) is the owner of a gap, so none can be read as met; a remediation entry a "Named gaps" paragraph lists is open;
 *   <li>no ruling, evidence or gap cell carries compliance wording or calls anything ready for production;
 *   <li>the review is not vacuous: every ruling occurs, above floors.
 * </ol>
 *
 * <p>Hermetic: it reads files only (the review, {@code BACKLOG.md}, {@code POSTPONED_ISSUES.md}, {@code THREAT_MODEL.md},
 * {@code settings.gradle.kts} and the evidence cited). {@code app/build.gradle.kts} declares the documents tree and the
 * infrastructure trees as inputs of every test task, so an edited citation re-runs it.
 */
@Tag("architecture")
@DisplayName("the readiness review rules every cell, on evidence that resolves or a gap with its owner (P15-TSK-034)")
class ProductionReadinessIsEvidencedTest {

    private static final String REVIEW = "docs/architecture/PRODUCTION_READINESS.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";
    private static final String POSTPONED = "docs/project/POSTPONED_ISSUES.md";
    private static final String THREAT_MODEL = "docs/architecture/THREAT_MODEL.md";
    private static final String SETTINGS = "settings.gradle.kts";

    /** The documents tree: a document states a property and never holds one. */
    private static final String DOCUMENTS = "docs/";

    /** The standard's twelve dimensions and its multi-instance row, in its order (section 2). */
    private static final List<String> UNIT_DIMENSIONS = List.of("Deployment", "Health checks", "Logging", "Metrics", "Tracing",
            "Alerting", "Security", "Resource limits", "Failure handling", "Recovery", "Ownership", "Runbooks", "Multi-instance");

    /** The module-level dimensions (section 4): the rest are the unit's. */
    private static final List<String> MODULE_DIMENSIONS = List.of("Metrics", "Alerting", "Ownership", "Runbooks");

    private static final List<String> UNITS = List.of("finapp-app", "finapp-migrator", "PostgreSQL", "Kafka", "Telemetry", "Edge");

    /** Built by the build and not modules of the application: the boot jar's own project and the migrator's. */
    private static final Set<String> NOT_MODULES = Set.of("app", "migrator");

    private static final String MULTI_INSTANCE = "Multi-instance";
    private static final String MET = "MET as rehearsed";
    private static final String PASS = "PASS";
    private static final String GAP = "GAP";
    private static final String NOT_APPLICABLE = "N/A";
    private static final String NONE = "—";
    private static final String DATABASE_PASS = "PI-023";

    /** The tiers that need a real PostgreSQL or Kafka: none has run fresh since Phase 15 began (PI-023). */
    private static final Pattern DATABASE_TIER = Pattern.compile("@Tag\\(\"(database|kafka|own-container)\"\\)");

    /** Compliance wording (PHASE_15_PLAN.md section 1.1) and a readiness claim (the entry's exclusion). */
    private static final Pattern FORBIDDEN_WORDING = Pattern.compile(
            "(?i)(ready for production|production[- ]ready|\\bcomplian(t|ce)\\b|\\bcertifi(ed|cation)\\b|PCI DSS|SOC 2|ISO 27001)");

    private static final Pattern UNIT_HEADING = Pattern.compile("^### 3\\.\\d+ `?([A-Za-z-]+)`?$");
    private static final Pattern MODULE_HEADING = Pattern.compile("^### 4\\.\\d+ `([a-z]+)`$");
    private static final Pattern SEPARATOR = Pattern.compile("^\\|[-:| ]+\\|$");
    private static final Pattern REMEDIATION = Pattern.compile("P14-REM-\\d{3}");
    private static final Pattern POSTPONED_ROW = Pattern.compile("(?m)^\\| (PI-\\d{3}) \\| `([A-Z]+)`");
    private static final Pattern OWNER_ITEMS = Pattern.compile("(?m)^(\\d+)\\. \\*\\*");
    private static final Pattern MODULE = Pattern.compile("(?m)^include\\(\"([a-z]+)\"\\)");

    /** The routed note on the entry (the owner, 2026-11-08): this remainder is a named gap, horizon before production use. */
    private static final String ROUTED_REMAINDER = "X-TSK-008";

    /** Floors, well below the review's counts and well above what a broken table leaves. */
    private static final int MIN_MET = 30;
    private static final int MIN_GAP = 40;
    private static final int MIN_NOT_APPLICABLE = 10;

    private record Row(String table, String dimension, String ruling, String evidence, String gap) {
        String where() {
            return table + " / " + dimension;
        }

        boolean multiInstance() {
            return dimension.equals(MULTI_INSTANCE);
        }
    }

    private static Path root;
    private static String review;
    private static Map<String, String> statuses;
    private static Map<String, String> postponedIssues;
    private static Set<Integer> ownerItems;
    private static Set<String> modules;
    private static Map<String, List<Row>> tables;
    private static List<String> unparseable;
    private static List<String> namedGapLines;

    @BeforeAll
    static void readTheReview() {
        root = EvidenceCitations.repositoryRoot();
        review = EvidenceCitations.read(root.resolve(REVIEW)).replace("\r\n", "\n");
        statuses = EvidenceCitations.backlogStatuses(root.resolve(BACKLOG));
        postponedIssues = new TreeMap<>();
        Matcher issue = POSTPONED_ROW.matcher(EvidenceCitations.read(root.resolve(POSTPONED)));
        while (issue.find()) {
            postponedIssues.put(issue.group(1), issue.group(2));
        }
        ownerItems = new TreeSet<>();
        Matcher item = OWNER_ITEMS.matcher(section(EvidenceCitations.read(root.resolve(THREAT_MODEL)), "## 6. "));
        while (item.find()) {
            ownerItems.add(Integer.parseInt(item.group(1)));
        }
        modules = new TreeSet<>();
        Matcher module = MODULE.matcher(EvidenceCitations.read(root.resolve(SETTINGS)));
        while (module.find()) {
            if (!NOT_MODULES.contains(module.group(1))) {
                modules.add(module.group(1));
            }
        }
        parseTables();
    }

    @Test
    @DisplayName("every row of sections 3 and 4 parses, and each unit and module holds its dimensions exactly once")
    void everyTableIsWhole() {
        assertThat(unparseable).as("rows of %s that do not parse", REVIEW).isEmpty();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        UNITS.forEach(unit -> expected.put(unit, UNIT_DIMENSIONS));
        modules.forEach(module -> expected.put("module " + module, MODULE_DIMENSIONS));
        Map<String, List<String>> found = new LinkedHashMap<>();
        tables.forEach((table, rows) -> found.put(table, rows.stream().map(Row::dimension).toList()));
        assertThat(found).as("each unit's thirteen rows and each of the build's modules' four, in the standard's order")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("every cell is ruled: MET as rehearsed, GAP or N/A, the multi-instance row PASS, GAP or N/A")
    void everyCellIsRuled() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : rows()) {
            Set<String> allowed = row.multiInstance() ? Set.of(PASS, GAP, NOT_APPLICABLE) : Set.of(MET, GAP, NOT_APPLICABLE);
            if (!allowed.contains(row.ruling())) {
                problems.put(row.where(), "is ruled '" + row.ruling() + "', not one of " + allowed);
            }
        }
        assertThat(problems).as("rulings in %s", REVIEW).isEmpty();
    }

    @Test
    @DisplayName("a MET or PASS cell rests on paths that resolve: never a document, a bare file or an unrun database test")
    void everyMetCellRestsOnEvidenceThatResolves() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : rows()) {
            if (!row.ruling().equals(MET) && !row.ruling().equals(PASS)) {
                continue;
            }
            List<String> rowProblems = new ArrayList<>(evidenceProblems(row, false));
            List<Citation> evidence = EvidenceCitations.citationsIn(row.evidence());
            if (evidence.stream().noneMatch(c -> c instanceof PathCitation)) {
                rowProblems.add("cites no evidence");
            }
            if (row.ruling().equals(PASS)
                    && evidence.stream().noneMatch(c -> c instanceof PathCitation path && path.isTestSource())) {
                rowProblems.add("a PASS cites a counted test, never design alone");
            }
            if (row.ruling().equals(MET) && !row.gap().equals(NONE)) {
                rowProblems.add("a MET cell has no gap: its gap column is '" + NONE + "'");
            }
            if (row.ruling().equals(PASS) && !row.gap().equals(NONE)) {
                rowProblems.addAll(ownerProblems(row, false));
            }
            if (!rowProblems.isEmpty()) {
                problems.put(row.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("MET and PASS cells in %s", REVIEW).isEmpty();
    }

    @Test
    @DisplayName("a GAP cell names a live owner (an open entry, a postponed issue or an owner item), and what holds resolves")
    void everyGapNamesItsOwner() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : rows()) {
            if (!row.ruling().equals(GAP)) {
                continue;
            }
            boolean owedToTheDatabasePass = EvidenceCitations.citationsIn(row.gap()).stream()
                    .anyMatch(c -> c instanceof IssueCitation issue && issue.id().equals(DATABASE_PASS));
            List<String> rowProblems = new ArrayList<>(evidenceProblems(row, owedToTheDatabasePass));
            rowProblems.addAll(ownerProblems(row, true));
            if (!rowProblems.isEmpty()) {
                problems.put(row.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("GAP cells in %s", REVIEW).isEmpty();
    }

    @Test
    @DisplayName("an N/A cell states its reason and names no gap")
    void everyNotApplicableCellSaysWhy() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : rows()) {
            if (!row.ruling().equals(NOT_APPLICABLE)) {
                continue;
            }
            List<String> rowProblems = new ArrayList<>(evidenceProblems(row, false));
            if (row.evidence().isBlank() || row.evidence().equals(NONE) || row.evidence().length() < 20) {
                rowProblems.add("an N/A cell states its reason in the evidence column");
            }
            if (!row.gap().equals(NONE)) {
                rowProblems.add("an N/A cell names no gap: its gap column is '" + NONE + "'");
            }
            if (!rowProblems.isEmpty()) {
                problems.put(row.where(), String.join("; ", rowProblems));
            }
        }
        assertThat(problems).as("N/A cells in %s", REVIEW).isEmpty();
    }

    @Test
    @DisplayName("no open remediation entry, postponed issue or the routed X-TSK-008 is read as met: each owns a gap")
    void nothingOpenIsReadAsMet() {
        Set<String> owners = new TreeSet<>();
        for (Row row : rows()) {
            if (row.ruling().equals(GAP) || row.ruling().equals(PASS)) {
                for (Citation citation : EvidenceCitations.citationsIn(row.gap())) {
                    switch (citation) {
                        case TaskCitation task -> owners.add(task.id());
                        case IssueCitation issue -> owners.add(issue.id());
                        default -> { }
                    }
                }
            }
        }
        Set<String> open = new TreeSet<>();
        statuses.forEach((id, status) -> {
            if (id.startsWith("P14-REM-") && EvidenceCitations.LIVE_STATUSES.contains(status)) {
                open.add(id);
            }
        });
        postponedIssues.forEach((id, status) -> {
            if (status.equals("POSTPONED")) {
                open.add(id);
            }
        });
        open.add(ROUTED_REMAINDER);
        assertThat(open).as("the open entries and issues were read").contains("P14-REM-001", "PI-004", "PI-023", "PI-024");
        assertThat(open).as("open entries and postponed issues that own no gap in %s: none may be read as met", REVIEW)
                .isSubsetOf(owners);

        Map<String, String> closed = new TreeMap<>();
        for (String line : namedGapLines) {
            Matcher entry = REMEDIATION.matcher(line);
            while (entry.find()) {
                String status = statuses.get(entry.group());
                if (status == null || !EvidenceCitations.LIVE_STATUSES.contains(status)) {
                    closed.put(entry.group(), "is " + status + ": a 'Named gaps' paragraph lists open entries alone");
                }
            }
        }
        assertThat(namedGapLines).as("the 'Named gaps' paragraphs were read").isNotEmpty();
        assertThat(closed).as("remediation entries listed as named gaps").isEmpty();
    }

    @Test
    @DisplayName("no ruling, evidence or gap cell claims compliance or calls anything ready for production")
    void noCellClaimsComplianceOrReadiness() {
        Map<String, String> problems = new LinkedHashMap<>();
        for (Row row : rows()) {
            Matcher wording = FORBIDDEN_WORDING.matcher(row.ruling() + " " + row.evidence() + " " + row.gap());
            if (wording.find()) {
                problems.put(row.where(), "says '" + wording.group() + "'");
            }
        }
        assertThat(problems).as("wording in %s's cells (PHASE_15_PLAN.md section 1.1)", REVIEW).isEmpty();
    }

    @Test
    @DisplayName("the review is not vacuous: its tables, owners and every ruling were read, above floors")
    void theReviewIsNotVacuous() {
        assertThat(modules).as("the build's modules were read").hasSize(23).contains("sharedkernel", "platform", "accounting");
        assertThat(statuses).as("backlog entries parsed").hasSizeGreaterThan(400);
        assertThat(postponedIssues).as("postponed issues parsed").containsKeys("PI-004", "PI-023", "PI-024");
        assertThat(ownerItems).as("the threat model's owner items parsed").contains(1, 42);
        Map<String, Long> byRuling = new TreeMap<>();
        rows().forEach(row -> byRuling.merge(row.ruling(), 1L, Long::sum));
        assertThat(byRuling.getOrDefault(MET, 0L)).as("MET cells").isGreaterThanOrEqualTo(MIN_MET);
        assertThat(byRuling.getOrDefault(GAP, 0L)).as("GAP cells").isGreaterThanOrEqualTo(MIN_GAP);
        assertThat(byRuling.getOrDefault(NOT_APPLICABLE, 0L)).as("N/A cells").isGreaterThanOrEqualTo(MIN_NOT_APPLICABLE);
        assertThat(rows().stream().filter(Row::multiInstance).map(Row::ruling)).as("multi-instance rulings").contains(PASS, GAP);
    }

    // ------------------------------------------------------------------ the rules' parts

    /** What is wrong with a cell's evidence column: what it cites must be a path that resolves, in a form that counts. */
    private static List<String> evidenceProblems(Row row, boolean owedToTheDatabasePass) {
        List<String> problems = new ArrayList<>();
        for (Citation citation : EvidenceCitations.citationsIn(row.evidence())) {
            if (!(citation instanceof PathCitation path)) {
                problems.add(describe(citation) + " in the evidence column: evidence is a path that resolves; a task, an"
                        + " issue or an item is an owner, and goes in the gap column");
                continue;
            }
            String cited = "`" + path.path() + path.member().map(m -> "#" + m).orElse("") + "`";
            Optional<String> unresolved = EvidenceCitations.problemWith(root, path);
            if (unresolved.isPresent()) {
                // Named, never thrown: a file that is not there has no tier to read, and the cell is refused by name.
                problems.add(cited + " " + unresolved.get());
                continue;
            }
            if (path.path().startsWith(DOCUMENTS)) {
                problems.add(cited + " is a document: it states a property and holds none (the standard's 'what never"
                        + " counts'); cite the test or the pinned file that holds it");
            } else if (!path.isTestSource() && path.pins().isEmpty()) {
                problems.add(cited + " is a bare file: its existence proves nothing, so pin the text that holds the claim"
                        + " (`contains: ...`, `lacks: ...`) or cite the test that asserts it");
            } else if (path.isTestSource() && databaseTier(path) && !owedToTheDatabasePass) {
                problems.add(cited + " needs PostgreSQL or Kafka and has not run fresh since Phase 15 began: it never counts"
                        + " as met, and a gap citing it names " + DATABASE_PASS);
            }
        }
        return problems;
    }

    /** What is wrong with a cell's gap column: every citation is a live owner, and a gap names at least one. */
    private static List<String> ownerProblems(Row row, boolean required) {
        List<String> problems = new ArrayList<>();
        List<Citation> citations = EvidenceCitations.citationsIn(row.gap());
        if (required && citations.stream().noneMatch(c -> !(c instanceof PathCitation) && !(c instanceof Unreadable))) {
            problems.add("a gap names its owner: a live backlog entry, a postponed issue or an owner item of " + THREAT_MODEL);
        }
        for (Citation citation : citations) {
            switch (citation) {
                case TaskCitation task -> {
                    String status = statuses.get(task.id());
                    if (status == null) {
                        problems.add("`" + task.id() + "` is not an entry header in " + BACKLOG);
                    } else if (!EvidenceCitations.LIVE_STATUSES.contains(status)) {
                        problems.add("`" + task.id() + "` is " + status + ": a finished or postponed task owns nothing;"
                                + " name what holds the gap now");
                    }
                }
                case IssueCitation issue -> {
                    String status = postponedIssues.get(issue.id());
                    if (status == null) {
                        problems.add("`" + issue.id() + "` is not in " + POSTPONED + "'s table");
                    } else if (!status.equals("POSTPONED")) {
                        problems.add("`" + issue.id() + "` is " + status + ", no longer a postponement holding a gap");
                    }
                }
                case OwnerItemCitation item -> {
                    if (!ownerItems.contains(item.number())) {
                        problems.add("`owner item " + item.number() + "` is not a numbered item of " + THREAT_MODEL
                                + " section 6");
                    }
                }
                case PathCitation path -> problems.add("`" + path.path() + "` in the gap column: a gap names its owner;"
                        + " what holds goes in the evidence column");
                case Unreadable unreadable -> problems.add("`" + unreadable.token() + "` is no citation the review defines");
            }
        }
        return problems;
    }

    private static boolean databaseTier(PathCitation path) {
        return DATABASE_TIER.matcher(EvidenceCitations.read(root.resolve(path.path()))).find();
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

    // ------------------------------------------------------------------ parsing

    private static List<Row> rows() {
        return tables.values().stream().flatMap(List::stream).toList();
    }

    private static void parseTables() {
        tables = new LinkedHashMap<>();
        unparseable = new ArrayList<>();
        namedGapLines = new ArrayList<>();
        String table = null;
        boolean inside = false;
        StringBuilder namedGaps = null;
        for (String line : review.split("\n", -1)) {
            // A "Named gaps" paragraph runs to its blank line, so an entry wrapped onto a later line is read too.
            if (namedGaps != null) {
                if (!line.isBlank()) {
                    namedGaps.append(' ').append(line);
                    continue;
                }
                namedGapLines.add(namedGaps.toString());
                namedGaps = null;
            }
            if (line.startsWith("## 3. ")) {
                inside = true;
                continue;
            }
            if (line.startsWith("## ") && !line.startsWith("## 3. ") && !line.startsWith("## 4. ")) {
                inside = false;
                table = null;
                continue;
            }
            if (!inside) {
                continue;
            }
            Matcher unit = UNIT_HEADING.matcher(line);
            Matcher module = MODULE_HEADING.matcher(line);
            if (unit.matches()) {
                table = unit.group(1);
                tables.put(table, new ArrayList<>());
                continue;
            }
            if (module.matches()) {
                table = "module " + module.group(1);
                tables.put(table, new ArrayList<>());
                continue;
            }
            if (line.startsWith("### ")) {
                unparseable.add("an unrecognised heading: " + line);
                table = null;
                continue;
            }
            if (line.startsWith("**Named gaps")) {
                namedGaps = new StringBuilder(line);
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("|") || SEPARATOR.matcher(trimmed).matches() || trimmed.startsWith("| Dimension |")) {
                continue;
            }
            List<String> columns = columns(line);
            if (table == null || !line.startsWith("| ") || columns.size() != 5) {
                unparseable.add((table == null ? "outside a table" : table) + ": " + abbreviate(line));
                continue;
            }
            tables.get(table).add(new Row(table, columns.get(0), columns.get(2).replace("`", ""), columns.get(3),
                    columns.get(4)));
        }
    }

    private static List<String> columns(String line) {
        String trimmed = line.trim();
        if (!trimmed.endsWith("|")) {
            return List.of();
        }
        return Arrays.stream(trimmed.substring(1, trimmed.length() - 1).split("\\|", -1)).map(String::trim).toList();
    }

    /** The lines of the {@code ## } section whose heading starts with {@code prefix}, to the next one. */
    private static String section(String document, String prefix) {
        StringBuilder lines = new StringBuilder();
        boolean inside = false;
        for (String line : document.split("\\R", -1)) {
            if (line.startsWith(prefix)) {
                inside = true;
                continue;
            }
            if (inside && line.startsWith("## ")) {
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
