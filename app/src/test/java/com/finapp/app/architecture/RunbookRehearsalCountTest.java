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
 * <p>Section 11.38 holds one row per runbook section: every numbered {@code ##} section of §1-§10 ({@code 3b} among them), every
 * {@code ###} subsection of §11, and every unnumbered {@code ##} section ("Provider callbacks are hints"). Each row names its owning
 * module, how many of its procedures were rehearsed out of how many it holds, where, on what date and on what commit, and, where
 * nothing or not everything was rehearsed, why, from a closed list. The headings are read from the runbook itself, so a section a
 * later change adds with no row turns this red, which is criterion 4's intent at the phase's exit.
 *
 * <p><strong>What it holds now and what once filled.</strong> The rehearsals are counted from the {@code kind} runs at the task's
 * gate. Until {@code P15-TSK-032}'s entry reads {@code COMPLETE} a cell may read {@code pending}; every cell that is filled is
 * judged as strictly before the gate as after it, and from the gate no cell may read {@code pending}. The table's shape, a row
 * per section and no row for a section that does not exist, holds from the first commit.
 *
 * <p>A procedure is counted by the section's own words; this test holds a floor under the count, the section's sub-headings one
 * level down, so a sub-heading added with no change to its row's count is refused. It cannot hold that a record is true: the
 * record is a run's, and the runbook section that holds it says what the run asserted.
 */
@Tag("architecture")
@DisplayName("the runbook's rehearsal count holds a row per section, each rehearsed or saying why not (P15-TSK-032)")
class RunbookRehearsalCountTest {

    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";
    private static final String SETTINGS = "settings.gradle.kts";
    private static final String TASK = "P15-TSK-032";

    /** The record's own section: what it reads is not a procedure, and a row for itself would be circular. */
    private static final String RECORD = "11.38";

    private static final String PENDING = "pending";
    private static final String NONE = "—";

    /** Why a section may stand unrehearsed, the closed list section 11.38 states. */
    private static final Set<String> REASONS = Set.of("no account", "Phase 16 drill", "real provider", "real person");

    /** Where a rehearsal may run (criterion 4: on {@code kind} or compose; the containers of the checks and the restore). */
    private static final Set<String> PLACES = Set.of("kind", "compose", "containers");

    private static final Pattern HEADING = Pattern.compile("^(#{1,6}) ");
    private static final Pattern DOMAIN_HEADING =Pattern.compile("^## ([0-9]+[a-z]?)\\. ");
    private static final Pattern PLATFORM_HEADING = Pattern.compile("^### (11\\.[0-9]+(?:\\.[0-9]+)?[a-z]?) ");
    private static final Pattern UNNUMBERED_HEADING = Pattern.compile("^## ([A-Z][^(0-9][^(]*?)(?: \\(.*)?$");
    private static final Pattern COUNT = Pattern.compile("^(pending|[0-9]+) / ([0-9]+)$");
    private static final Pattern COMMIT = Pattern.compile("^`[0-9a-f]{7,40}`$");
    private static final Pattern OWNER = Pattern.compile("`([a-z]+)`");
    private static final Pattern MODULE = Pattern.compile("(?m)^include\\(\"([a-z]+)\"\\)");

    private static String runbook;
    private static Map<String, Integer> sections;
    private static Map<String, Row> rows;
    private static Set<String> modules;
    private static boolean complete;

    /** One row of the count, as written. */
    private record Row(String section, String owner, String count, String where, String date, String commit, String reason) {

        boolean pending() {
            return count.startsWith(PENDING);
        }

        int rehearsed() {
            return Integer.parseInt(parts().group(1));
        }

        int procedures() {
            return Integer.parseInt(parts().group(2));
        }

        private Matcher parts() {
            Matcher parts = COUNT.matcher(count);
            assertThat(parts.matches()).as("§%s's count `%s` reads `<rehearsed> / <procedures>`", section, count).isTrue();
            return parts;
        }

        boolean domain() {
            return !section.startsWith("11.") && Character.isDigit(section.charAt(0));
        }
    }

    @BeforeAll
    static void readTheRunbook() {
        Path root = EvidenceCitations.repositoryRoot();
        runbook = EvidenceCitations.read(root.resolve(RUNBOOK)).replace("\r\n", "\n");
        sections = sections(runbook);
        rows = rows(record(runbook));
        modules = new TreeSet<>();
        Matcher module = MODULE.matcher(EvidenceCitations.read(root.resolve(SETTINGS)));
        while (module.find()) {
            modules.add(module.group(1));
        }
        complete = "COMPLETE".equals(EvidenceCitations.backlogStatuses(root.resolve(BACKLOG)).get(TASK));
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
            assertThat(row.procedures()).as("§%s counts its procedures", row.section()).isPositive();
            assertThat(row.procedures()).as("§%s counts no fewer procedures than its sub-headings", row.section())
                    .isGreaterThanOrEqualTo(sections.get(row.section()));
        }
    }

    @Test
    @DisplayName("a filled row is rehearsed where criterion 4 allows, dated and on a commit, or says why not from the closed list")
    void everyFilledRowIsRehearsedOrSaysWhyNot() {
        int judged = 0;
        for (Row row : rows.values()) {
            assertThat(row.reason()).as("§%s's reason", row.section()).isIn(union(REASONS, Set.of(NONE)));
            if (row.pending()) {
                continue;
            }
            judged++;
            int rehearsed = row.rehearsed();
            assertThat(rehearsed).as("§%s rehearses no more procedures than it holds", row.section()).isLessThanOrEqualTo(row.procedures());
            if (rehearsed == 0) {
                assertThat(row.reason()).as("§%s, rehearsed nowhere, says why from the closed list", row.section()).isIn(REASONS);
                assertThat(List.of(row.where(), row.date(), row.commit())).as("§%s names no run", row.section()).containsOnly(NONE);
                continue;
            }
            for (String place : row.where().split(", ")) {
                assertThat(place).as("§%s was rehearsed on kind, compose or containers", row.section()).isIn(PLACES);
            }
            assertThat(date(row)).as("§%s's date", row.section()).isNotNull();
            assertThat(row.commit()).as("§%s's commit", row.section()).matches(COMMIT);
            if (rehearsed == row.procedures()) {
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
                assertThat(row.rehearsed()).as("§%s's sample", row.section()).isPositive();
            }
        }
    }

    @Test
    @DisplayName("once the task's entry is COMPLETE no cell reads pending: the counts are the runs', filled at the gate")
    void nothingIsPendingOnceTheTaskIsComplete() {
        if (!complete) {
            return;
        }
        for (Row row : rows.values()) {
            assertThat(List.of(row.count(), row.where(), row.date(), row.commit()))
                    .as("§%s is filled at the gate", row.section())
                    .noneMatch(cell -> cell.contains(PENDING));
        }
    }

    // ------------------------------------------------------------------------------------ reading

    /** Each section's name, mapped to its sub-headings one level down; fenced blocks are not headings. */
    private static Map<String, Integer> sections(String text) {
        Map<String, Integer> found = new LinkedHashMap<>();
        String open = null;
        int level = 0;
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
            int depth = heading.group(1).length();
            String name = sectionName(line);
            if (name != null) {
                assertThat(found.put(name, 0)).as("the section %s is headed once", name).isNull();
                open = name;
                level = depth;
            } else if (open != null && depth <= level) {
                open = null;
            } else if (open != null && depth == level + 1) {
                found.merge(open, 1, Integer::sum);
            }
        }
        return found;
    }

    /** The row's name for a heading that counts as a section, or null. */
    private static String sectionName(String line) {
        Matcher domain = DOMAIN_HEADING.matcher(line);
        if (domain.find()) {
            int number = Integer.parseInt(domain.group(1).replaceAll("[a-z]", ""));
            return number >= 1 && number <= 10 ? domain.group(1) : null;
        }
        Matcher platform = PLATFORM_HEADING.matcher(line);
        if (platform.find()) {
            return RECORD.equals(platform.group(1)) ? null : platform.group(1);
        }
        Matcher unnumbered = UNNUMBERED_HEADING.matcher(line);
        return unnumbered.find() ? unnumbered.group(1).strip() : null;
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
                    assertThat(cells).as("the count table's columns").containsExactly("Section", "Owner", "Rehearsed / procedures",
                            "Where", "Date", "Commit", "Procedure only because");
                }
                continue;
            }
            if (cells.get(0).matches("-+")) {
                continue;
            }
            assertThat(cells).as("a count row has seven cells: %s", line).hasSize(7);
            String section = cells.get(0).replaceFirst("^§", "");
            Row row = new Row(section, cells.get(1), cells.get(2), cells.get(3), cells.get(4), cells.get(5), cells.get(6));
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
