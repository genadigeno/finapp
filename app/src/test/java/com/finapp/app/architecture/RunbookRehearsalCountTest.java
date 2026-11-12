package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The runbook's rehearsal count is the runbook's own (`P15-TSK-032`; {@code PHASE_GATES.md} §5 Phase 15 criterion 4: "the count
 * is reported per section"; {@code OPERATIONS_RUNBOOK.md} section 11.38).
 *
 * <p>Section 11.38 holds one row per runbook section. What a section is, read from the headings: every numbered {@code ##}
 * section from 1 to 10 ({@code 3b} among them) and every unnumbered {@code ##} section ("Provider callbacks are hints") is one;
 * a numbered {@code ##} section from 11 on is a container, and each {@code ###} under it, numbered or not, is one. Any other
 * heading at those two levels is refused as unclassified, so a heading form this test was not written for asks for a decision
 * rather than escaping the count. Each row names its owning modules; how many of its procedures were rehearsed, how many read
 * against the code, and how many it holds; where, on what date, on what commit and in which run; and, where nothing or not
 * everything was rehearsed, why, from a closed list.
 *
 * <p><strong>What it holds, and what it does not.</strong> It holds the table's form against the runbook: a row per section and
 * none for a section that does not exist, an owner that is a module, a count that accounts for every procedure, a date that
 * parses, a commit that is hexadecimal, a reason from the closed list. <strong>It is not evidence that anything was
 * rehearsed</strong>: a row saying so is a person's statement, and the run's own record, in the section it rehearsed, is what
 * says what was asserted. A commit is not checked to exist, and a date is not checked against the run.
 *
 * <p><strong>Before and after the gate.</strong> The rehearsals are counted from the {@code kind} runs at the task's gate. Until
 * {@code P15-TSK-032}'s entry reads {@code COMPLETE} a row's cells may read {@code pending}; a filled row is judged as strictly
 * before the gate as after it, and from the gate no cell may read {@code pending}. The entry's status must be read as one this
 * test knows: an entry missing, or a header it cannot read, fails rather than leaving {@code pending} allowed for good.
 *
 * <p><strong>The floor.</strong> A procedure is counted by the section's own words; what this test holds under that count is
 * the section's sub-headings one level down: a row may not count fewer procedures than its section has sub-headings, so a
 * sub-heading added with its row unchanged is refused. A procedure added as a paragraph moves no floor, and nothing here can see
 * it.
 */
@Tag("architecture")
@DisplayName("the runbook's rehearsal count holds a row per section, each procedure rehearsed or read, and says why not (P15-TSK-032)")
class RunbookRehearsalCountTest {

    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";
    private static final String SETTINGS = "settings.gradle.kts";
    private static final String TASK = "P15-TSK-032";

    /** The record's own section: what it reads is not a procedure, and a row for itself would be circular. */
    private static final String RECORD = "11.38";

    private static final String PENDING = "pending";
    private static final String NONE = "—";

    /** The statuses an entry's header may read for this test to know whether the gate has passed. */
    private static final Set<String> KNOWN_STATUSES = Set.of("PLANNED", "READY", "IN_PROGRESS", "COMPLETE");

    /** Why a section may stand unrehearsed, the closed list section 11.38 states. */
    private static final Set<String> REASONS = Set.of("no account", "Phase 16 drill", "real provider", "real person");

    /** Where a rehearsal may run (criterion 4: on {@code kind} or compose; the containers of the checks and the restore). */
    private static final Set<String> PLACES = Set.of("kind", "compose", "containers");

    private static final List<String> COLUMNS = List.of("Section", "Owner", "Rehearsed", "Read", "Procedures", "Where", "Date",
            "Commit", "Run", "Procedure only because");

    private static final Pattern HEADING = Pattern.compile("^(#{1,6}) (.*)$");
    private static final Pattern NUMBERED_TOP = Pattern.compile("^([0-9]+)([a-z]?)\\. \\S");
    private static final Pattern NUMBERED_SUB = Pattern.compile("^([0-9]+\\.[0-9]+(?:\\.[0-9]+)?[a-z]?) \\S");
    private static final Pattern TITLED = Pattern.compile("^([A-Z][^(]*?)(?: \\(.*)?$");
    private static final Pattern COMMITS = Pattern.compile("^`[0-9a-f]{7,40}`(?:, `[0-9a-f]{7,40}`)*$");
    private static final Pattern RUN = Pattern.compile("^`[^`|]+`$");
    private static final Pattern OWNER = Pattern.compile("`([a-z]+)`");
    private static final Pattern MODULE = Pattern.compile("(?m)^include\\(\"([a-z]+)\"\\)");

    private static Map<String, Integer> sections;
    private static List<String> unclassified;
    private static Map<String, Row> rows;
    private static Set<String> modules;
    private static String status;
    private static boolean complete;

    /** One row of the count, as written. */
    private record Row(String section, String owner, String rehearsed, String read, String procedures, String where, String date,
            String commit, String run, String reason) {

        boolean pending() {
            return PENDING.equals(rehearsed) || PENDING.equals(read);
        }

        int rehearsedCount() {
            return count(rehearsed, "rehearsed");
        }

        int readCount() {
            return count(read, "read");
        }

        int procedureCount() {
            return count(procedures, "procedures");
        }

        private int count(String cell, String column) {
            assertThat(cell).as("§%s's %s is a count", section, column).matches("[0-9]+");
            return Integer.parseInt(cell);
        }

        boolean domain() {
            Matcher number = NUMBERED_TOP.matcher(section + ". x");
            return number.find() && Integer.parseInt(number.group(1)) <= 10;
        }
    }

    @BeforeAll
    static void readTheRunbook() {
        Path root = EvidenceCitations.repositoryRoot();
        String runbook = EvidenceCitations.read(root.resolve(RUNBOOK)).replace("\r\n", "\n");
        unclassified = new ArrayList<>();
        sections = sections(runbook, unclassified);
        rows = rows(record(runbook));
        modules = new TreeSet<>();
        Matcher module = MODULE.matcher(EvidenceCitations.read(root.resolve(SETTINGS)));
        while (module.find()) {
            modules.add(module.group(1));
        }
        status = EvidenceCitations.backlogStatuses(root.resolve(BACKLOG)).get(TASK);
        complete = "COMPLETE".equals(status);
    }

    @Test
    @DisplayName("every heading at the section levels is one this test classifies: a section, a container or a sub-heading")
    void everyHeadingIsClassified() {
        assertThat(unclassified).as("headings this test does not classify: decide what they are, and change the test with them")
                .isEmpty();
    }

    @Test
    @DisplayName("every runbook section has exactly one row in section 11.38, and every row names a section that exists")
    void everySectionHasItsRowAndEveryRowItsSection() {
        assertThat(sections).as("the headings were read: §1-§10, §11's subsections and the unnumbered sections").hasSizeGreaterThan(40)
                .containsKeys("1", "3b", "10", "11.1.1", "11.1b", "11.12", "Provider callbacks are hints");
        assertThat(modules).as("the modules were read").contains("platform", "ledger", "reconciliation");
        assertThat(rows.keySet()).as("section 11.38's rows are exactly the runbook's sections").isEqualTo(sections.keySet());
    }

    @Test
    @DisplayName("every row names its owning modules, and counts at least one procedure and no fewer than its sub-headings")
    void everyRowIsOwnedAndCounted() {
        for (Row row : rows.values()) {
            List<String> owners = new ArrayList<>();
            Matcher owner = OWNER.matcher(row.owner());
            while (owner.find()) {
                owners.add(owner.group(1));
            }
            assertThat(owners).as("§%s names an owner", row.section()).isNotEmpty();
            assertThat(modules).as("§%s's owners are modules of the build", row.section()).containsAll(owners);
            assertThat(row.procedureCount()).as("§%s counts its procedures", row.section()).isPositive();
            assertThat(row.procedureCount()).as("§%s counts no fewer procedures than its sub-headings", row.section())
                    .isGreaterThanOrEqualTo(sections.get(row.section()));
        }
    }

    @Test
    @DisplayName("a filled row accounts for every procedure, rehearsed or read; a rehearsal says where, when, on what and which"
            + " run; a section rehearsed nowhere says why from the closed list")
    void everyFilledRowAccountsForEveryProcedure() {
        int judged = 0;
        for (Row row : rows.values()) {
            assertThat(row.reason()).as("§%s's reason", row.section()).isIn(union(REASONS, Set.of(NONE)));
            if (row.pending()) {
                assertThat(List.of(row.rehearsed(), row.read())).as("§%s is pending whole", row.section()).containsOnly(PENDING);
                continue;
            }
            judged++;
            int rehearsed = row.rehearsedCount();
            assertThat(rehearsed + row.readCount()).as("§%s: every procedure rehearsed or read, none that nobody looked at",
                    row.section()).isEqualTo(row.procedureCount());
            if (rehearsed == 0) {
                assertThat(row.reason()).as("§%s, rehearsed nowhere, says why from the closed list", row.section()).isIn(REASONS);
                assertThat(List.of(row.where(), row.date(), row.commit(), row.run())).as("§%s names no run", row.section())
                        .containsOnly(NONE);
                continue;
            }
            for (String place : row.where().split(", ")) {
                assertThat(place).as("§%s was rehearsed on kind, compose or containers", row.section()).isIn(PLACES);
            }
            assertThat(date(row)).as("§%s's date parses", row.section()).isNotNull();
            assertThat(row.commit()).as("§%s's commits", row.section()).matches(COMMITS);
            assertThat(row.run()).as("§%s's run", row.section()).matches(RUN);
            if (rehearsed == row.procedureCount()) {
                assertThat(row.reason()).as("§%s, rehearsed whole, owes no reason", row.section()).isEqualTo(NONE);
            }
        }
        assertThat(judged).as("a positive control: some rows are filled and judged before the gate").isPositive();
    }

    @Test
    @DisplayName("every domain section §1-§10 has at least one procedure rehearsed, criterion 4's counted sample")
    void everyDomainSectionHasARehearsedSample() {
        List<Row> domain = rows.values().stream().filter(Row::domain).toList();
        assertThat(domain).as("the domain sections' rows").hasSize(11);
        for (Row row : domain) {
            if (!row.pending()) {
                assertThat(row.rehearsedCount()).as("§%s's sample", row.section()).isPositive();
            }
        }
    }

    @Test
    @DisplayName("the task's status is read, and once it is COMPLETE no cell reads pending: the counts are the runs', filled at the gate")
    void theStatusIsReadAndNothingIsPendingOnceComplete() {
        assertThat(status).as("%s's entry header in %s, read as a status this test knows", TASK, BACKLOG).isIn(KNOWN_STATUSES);
        if (!complete) {
            return;
        }
        for (Row row : rows.values()) {
            assertThat(List.of(row.rehearsed(), row.read(), row.where(), row.date(), row.commit(), row.run()))
                    .as("§%s is filled at the gate", row.section())
                    .noneMatch(cell -> cell.contains(PENDING));
        }
    }

    // ------------------------------------------------------------------------------------ reading

    /**
     * Each section's name, mapped to its sub-headings one level down. A numbered {@code ##} from 1 to 10, or an unnumbered one,
     * is a section; a numbered {@code ##} from 11 on is a container whose {@code ###} children are sections; a {@code ###} under a
     * section is a sub-heading. Fenced blocks are not headings. A level-2 or level-3 heading that fits none of these, or a second
     * level-1 heading, is unclassified.
     */
    private static Map<String, Integer> sections(String text, List<String> unclassified) {
        Map<String, Integer> found = new LinkedHashMap<>();
        String open = null;
        int openLevel = 0;
        boolean inContainer = false;
        boolean titled = false;
        boolean fenced = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (fenced || !heading.find()) {
                continue;
            }
            int level = heading.group(1).length();
            String title = heading.group(2);
            if (level == 1) {
                if (titled) {
                    unclassified.add(line);
                }
                titled = true;
                continue;
            }
            if (open != null && level <= openLevel) {
                open = null;
            }
            String name = null;
            if (level == 2) {
                inContainer = false;
                Matcher numbered = NUMBERED_TOP.matcher(title);
                Matcher named = TITLED.matcher(title);
                if (numbered.find()) {
                    if (Integer.parseInt(numbered.group(1)) <= 10) {
                        name = numbered.group(1) + numbered.group(2);
                    } else if (numbered.group(2).isEmpty()) {
                        inContainer = true;
                    } else {
                        unclassified.add(line);
                    }
                } else if (named.find()) {
                    name = named.group(1).strip();
                } else {
                    unclassified.add(line);
                }
            } else if (level == 3 && inContainer) {
                Matcher numbered = NUMBERED_SUB.matcher(title);
                Matcher named = TITLED.matcher(title);
                if (numbered.find()) {
                    name = numbered.group(1);
                } else if (named.find()) {
                    name = named.group(1).strip();
                } else {
                    unclassified.add(line);
                }
                if (RECORD.equals(name)) {
                    name = null;
                    open = null;
                    continue;
                }
            } else if (open != null && level == openLevel + 1) {
                found.merge(open, 1, Integer::sum);
                continue;
            } else if (level <= 3 && open == null && !inContainer) {
                unclassified.add(line);
                continue;
            }
            if (name != null) {
                assertThat(found.put(name, 0)).as("the section %s is headed once", name).isNull();
                open = name;
                openLevel = level;
            }
        }
        return found;
    }

    /** Section 11.38, from its heading to the next of its level or above. */
    private static String record(String text) {
        int start = text.indexOf("\n### " + RECORD + " ");
        assertThat(start).as("the runbook has section %s", RECORD).isPositive();
        Matcher next = Pattern.compile("(?m)^#{2,3} ").matcher(text);
        return next.find(start + 2) ? text.substring(start, next.start()) : text.substring(start);
    }

    /** The count table: the first table whose header's first cell is {@code Section}. */
    private static Map<String, Row> rows(String record) {
        Map<String, Row> read = new LinkedHashMap<>();
        boolean inTable = false;
        for (String line : record.split("\n", -1)) {
            if (!line.startsWith("|")) {
                if (inTable) {
                    break;
                }
                continue;
            }
            List<String> cells = cells(line);
            if (!inTable) {
                inTable = "Section".equals(cells.get(0));
                if (inTable) {
                    assertThat(cells).as("the count table's columns").isEqualTo(COLUMNS);
                }
                continue;
            }
            if (cells.get(0).matches("-+")) {
                continue;
            }
            assertThat(cells).as("a count row has %d cells: %s", COLUMNS.size(), line).hasSize(COLUMNS.size());
            String section = cells.get(0).replaceFirst("^§", "");
            Row row = new Row(section, cells.get(1), cells.get(2), cells.get(3), cells.get(4), cells.get(5), cells.get(6),
                    cells.get(7), cells.get(8), cells.get(9));
            assertThat(read.put(section, row)).as("§%s has one row", section).isNull();
        }
        assertThat(read).as("section %s's count table was read", RECORD).isNotEmpty();
        return read;
    }

    private static List<String> cells(String line) {
        String inner = line.strip();
        inner = inner.substring(1, inner.endsWith("|") ? inner.length() - 1 : inner.length());
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static LocalDate date(Row row) {
        try {
            return LocalDate.parse(row.date());
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    private static Set<String> union(Set<String> one, Set<String> other) {
        Set<String> both = new TreeSet<>(one);
        both.addAll(other);
        return both;
    }
}
