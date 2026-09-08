package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every invariant of every phase reached has a recorded demonstration that its test can fail
 * (`P0-TSK-038`, extended by `P1-TSK-024`).
 *
 * <p>{@code PHASE_GATES.md} §3 criterion 3 requires that every in-scope {@code INV-*} have "at
 * least one test that fails if the invariant is broken", and {@code DEFINITION_OF_DONE.md} §3
 * blocks done on "a test that would still pass if the invariant it claims to protect were
 * removed". Neither is satisfiable by inspection — a test that cannot fail looks exactly like a
 * test that passes — so the demonstrations are recorded in {@code MUTATION_TESTING.md} and this
 * holds that document to the catalogue and to the code.
 *
 * <p><strong>What this converts.</strong> Before it, "does every Phase 0 invariant have a
 * demonstrated-to-fail test?" was answerable only by reading forty change-log entries, and the
 * answer was no: {@code INV-MON-05} had a test and no recorded demonstration, and its identifier
 * appeared nowhere in the project's records. Criterion 3 is now checked continuously rather than
 * discovered at the gate.
 *
 * <h2>Extended to every phase reached, and the current one is derived rather than written down</h2>
 *
 * <p>The first version enforced <strong>Phase 0 only</strong>, which left the {@code INV-IDN} group in
 * exactly the regime the Phase 0 &rarr; 1 transition created it to escape — <em>"a materially weaker
 * regime than every other property on this platform gets"</em>. It now requires a demonstration for
 * every invariant whose phase is at most the current one, and reads the current phase from
 * {@code CURRENT_STATE.md}, whose stated role is to be <em>the canonical description of where the
 * project is</em>. A constant here would be the stale list this repository closes by derivation
 * everywhere else, and it would need editing again at Phase 2.
 *
 * <h2>The row grammar was narrower than the rows people write</h2>
 *
 * <p>It admitted exactly one backticked reference and nothing after it, and the register is written
 * with lists and trailing prose — {@code `A`, `B#c`} and {@code `A` (nine tests, one per route)}.
 * <strong>Nine rows did not parse</strong>, eight of them written during Phase 1, and the guard was
 * therefore not checking that the tests they name exist. A register whose rows the guard cannot read
 * reports coverage it does not have, which is {@code P0-TST-008}'s finding in the very artefact built
 * to prevent that class of defect.
 *
 * <p>The reference column is now parsed as a <strong>list</strong>, so every test a row names is
 * checked. That is strictly more coverage than normalising the register to one reference per row,
 * which would have been fixing the document to suit the parser.
 *
 * <p><strong>What it cannot check</strong> is that a recorded procedure still reproduces —
 * re-running one means mutating production code or the schema, which a build must not do to
 * itself. That residual risk is why the register records the <em>form</em> of each demonstration,
 * and why an in-suite proof is preferred wherever one is possible.
 */
@Tag("architecture")
@DisplayName("Mutation demonstrations (P0-TSK-038)")
class MutationDemonstrationTest {

    private static final String REGISTER = "docs/project/MUTATION_TESTING.md";
    private static final String CATALOGUE = "docs/domain/FINANCIAL_INVARIANTS.md";
    private static final String BACKLOG = "docs/project/BACKLOG.md";
    private static final String STATE = "docs/project/CURRENT_STATE.md";

    /** An `INV-*` heading in the catalogue, and the phase line that follows it. */
    private static final Pattern INVARIANT_HEADING = Pattern.compile("^### (INV-[A-Z]+-\\d+)");

    private static final Pattern PHASE_LINE = Pattern.compile("^\\*\\*Phase:\\*\\*\\s*(.+)$");

    /**
     * A §2 register row: invariant, the test that demonstrates it, and the form.
     *
     * <p>The test reference is {@code Class} or {@code Class#method} — the method form wherever one
     * method carries the property, which {@code INV-MON-05} showed is not a formality: its mutation
     * is caught by exactly one of that class's seven tests.
     */
    private static final Pattern INVARIANT_ROW =
            Pattern.compile("^\\|\\s*`(INV-[A-Z]+-\\d+)`\\s*\\|([^|]*)\\|\\s*(In-suite|Recorded)\\s*\\|");

    /**
     * One test reference inside the second column.
     *
     * <p>Found repeatedly rather than anchored, because a row legitimately names several — a
     * property proven by a build rule <em>and</em> a behavioural test is two controls blind in
     * different directions, and the register says so.
     */
    private static final Pattern TEST_REFERENCE =
            Pattern.compile("`([A-Za-z0-9_]+)(?:#([A-Za-z0-9_]+))?`");

    /** A §4 register row: the `P{n}-TST-*` item it covers. */
    private static final Pattern ITEM_ROW = Pattern.compile("^\\|\\s*`(P\\d+-TST-\\d+)`\\s*\\|");

    /**
     * A test item declared by a backlog heading.
     *
     * <p>Not anchored to the start, because Phase 1 declares its test items <em>inside</em> task
     * headings — {@code **P1-TSK-009 — `P1-TST-001`: credentials never leak**} — where Phase 0
     * gave them headings of their own. Anchoring to either shape finds nothing for the other and
     * passes vacuously; one pattern that finds the identifier wherever a bold heading declares it
     * covers both, and the next phase's shape as well.
     */
    private static final Pattern BACKLOG_ITEM = Pattern.compile("^\\*\\*.*?(P\\d+-TST-\\d+)");

    /** The phase headings in {@code CURRENT_STATE.md} §Current Phase. */
    private static final Pattern PHASE_HEADING = Pattern.compile("^\\*\\*Phase (\\d+) —");

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every invariant of every phase reached has a demonstration")
    void everyInvariantOfEveryPhaseReachedHasADemonstration() {
        Set<String> demonstrated = new TreeSet<>(invariantRows().keySet());

        assertThat(demonstrated)
                .as(
                        """
                        %s section 2 must record a demonstration for every invariant %s marks as \
                        belonging to a phase this project has reached (currently %d).

                        PHASE_GATES.md criterion 3 requires each to have a test that FAILS when the \
                        invariant is broken. An invariant with a test and no demonstration is the \
                        case this register exists for: nothing distinguishes it from one whose test \
                        cannot fail.""",
                        REGISTER, CATALOGUE, currentPhase())
                .containsAll(invariantsUpToCurrentPhase());
    }

    @Test
    @DisplayName("every line that looks like a register row parses as one")
    void everyRowLikeLineParses() {
        // THE SAME DEFECT ONE LEVEL OUT, found by the completion gate probing its own fix.
        //
        // everyRowNamesATest catches a row that PARSES and names nothing. It cannot catch a row that
        // fails INVARIANT_ROW entirely - a typo in the form column, an extra pipe, a reflowed line -
        // because such a row is not in the map at all. Probed rather than reasoned about: changing
        // one row's form from "In-suite" to "Insuite" left the build green, and that row's invariant
        // stayed covered only because it happens to have siblings.
        //
        // So the outer check is the one that has to be structural: every line in §2 that LOOKS like
        // an invariant row must parse as one. A parser that silently drops what it cannot read is
        // the whole finding of this task, and leaving the outer case open would have reproduced it.
        List<String> unparsed = new ArrayList<>();
        for (String line : registerSection("## 2.")) {
            if (line.startsWith("| `INV-") && !INVARIANT_ROW.matcher(line).find()) {
                unparsed.add(line.length() > 90 ? line.substring(0, 90) + "…" : line);
            }
        }

        assertThat(unparsed)
                .as("a §2 line that looks like a row and does not parse is invisible to every other"
                        + " assertion here. The form column must read exactly `In-suite` or"
                        + " `Recorded`, and the reference column must contain no pipe")
                .isEmpty();

        // And the sweep must have seen rows at all, or the assertion above passes over nothing.
        assertThat(registerSection("## 2.").stream().filter(l -> l.startsWith("| `INV-")).count())
                .as("no row-like line was seen, so this proved nothing")
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("every row names at least one test, so a row the parser cannot read is a failure")
    void everyRowNamesATest() {
        // THE ASSERTION THAT MAKES WIDENING THE GRAMMAR SAFE.
        //
        // The previous grammar admitted one backticked reference and nothing after it, and the
        // register is written with lists and trailing prose - so NINE rows did not parse, eight of
        // them written during Phase 1. They were not reported as broken; they were simply absent,
        // which meant everyNamedTestExists and everyNamedMethodExists never looked at them.
        //
        // A parser that skips what it cannot read is worse than one that fails on it, because the
        // register keeps looking complete. This is what turns an unparseable row into a build
        // failure instead of a silent omission.
        // PER ROW, not per invariant - and a mutation established that the difference is the whole
        // assertion. The first version read invariantRows(), which MERGES the references of every
        // row for one invariant; INV-IDN-06 has two rows, so emptying one of them left the merged
        // list non-empty and the mutation SURVIVED. A check defeated by the very merging that makes
        // the rest of this guard convenient is a check that reports coverage it does not have.
        Map<String, String> empty = new TreeMap<>();
        rowReferences()
                .forEach(
                        (row, references) -> {
                            if (references.isEmpty()) {
                                empty.put(row, "names no test this parser could read");
                            }
                        });

        assertThat(empty)
                .as("a row whose reference column names no `Class` or `Class#method` records"
                        + " nothing, and nothing else here would notice")
                .isEmpty();
    }

    @Test
    @DisplayName("every invariant the register names exists in the catalogue")
    void everyRegisteredInvariantExists() {
        // The other direction, and it was missing: a row for `INV-ZZZ-99` - an invariant that
        // appears nowhere in the catalogue - passed cleanly, because the check above is
        // containsAll and says nothing about rows the catalogue does not know.
        //
        // Found by review, by planting exactly that row. It is the "register describing something
        // that does not exist" defect ColumnClassificationTest checks in both directions and
        // AuditableActionRegistryTest in three, and it matters for the same reason: an entry that
        // has quietly stopped applying to anything is indistinguishable from one that still does.
        //
        // Not restricted to Phase 0. A later-phase invariant demonstrated early is welcome; an
        // invariant that does not exist is a typo.
        assertThat(invariantRows().keySet())
                .as("%s names an invariant %s does not define", REGISTER, CATALOGUE)
                .isSubsetOf(allInvariants());
    }

    @Test
    @DisplayName("every P0-TST item has a demonstration")
    void everyTestItemHasADemonstration() {
        assertThat(itemRows())
                .as("%s section 4 must cover every P{n}-TST-* item in %s", REGISTER, BACKLOG)
                .containsAll(backlogTestItems());
    }

    @Test
    @DisplayName("every test named in the register exists")
    void everyNamedTestExists() {
        Map<String, JavaClass> testClasses = testClassesBySimpleName();

        Map<String, String> missing = new TreeMap<>();
        invariantRows()
                .forEach(
                        (invariant, references) ->
                                references.stream()
                                        .filter(r -> !testClasses.containsKey(r.className()))
                                        .forEach(
                                                r ->
                                                        missing.put(
                                                                invariant + " -> " + r.className(),
                                                                "does not exist")));

        assertThat(missing)
                .as(
                        "a demonstration naming a test that was renamed or deleted records nothing."
                                + " Classes swept: %s",
                        testClasses.size())
                .isEmpty();
    }

    @Test
    @DisplayName("every method the register names exists on its class")
    void everyNamedMethodExists() {
        // Applies to EVERY row that names a method, not only the in-suite ones - which is what the
        // first name of this test claimed, inaccurately. For an in-suite row it is the link that
        // makes the label mean something: the row claims a proof that runs on every build, and if
        // the method were renamed away the claim would be false with nothing else saying so. For a
        // recorded row it is the difference between naming the test that actually protects the
        // property and naming its class and hoping.
        //
        // It earned its place immediately: the INV-IDEM-03 row written during this task's review
        // named a method that does not exist, and this is what found it.
        Map<String, JavaClass> testClasses = testClassesBySimpleName();

        Map<String, String> missing = new TreeMap<>();
        invariantRows()
                .forEach(
                        (invariant, references) ->
                                references.forEach(
                                        reference -> {
                                            if (reference.methodName() == null) {
                                                return;
                                            }
                                            JavaClass javaClass =
                                                    testClasses.get(reference.className());
                                            if (javaClass == null) {
                                                return; // reported by everyNamedTestExists
                                            }
                                            boolean present =
                                                    javaClass.getMethods().stream()
                                                            .anyMatch(
                                                                    m ->
                                                                            m.getName()
                                                                                    .equals(
                                                                                        reference
                                                                                            .methodName()));
                                            if (!present) {
                                                missing.put(
                                                        invariant + " -> " + reference.className(),
                                                        "has no " + reference.methodName());
                                            }
                                        }));

        assertThat(missing)
                .as("an in-suite demonstration must name a method that is actually there")
                .isEmpty();

        // This test skips a row whose class it cannot find, deferring to everyNamedTestExists -
        // which means on its own it would pass over an empty sweep having checked nothing. Assert
        // it actually resolved the in-suite rows, so it does not depend on a sibling for coverage.
        long naming =
                invariantRows().values().stream()
                        .flatMap(List::stream)
                        .filter(reference -> reference.methodName() != null)
                        .count();
        long checked =
                invariantRows().values().stream()
                        .flatMap(List::stream)
                        .filter(reference -> reference.methodName() != null)
                        .filter(reference -> testClasses.containsKey(reference.className()))
                        .count();
        assertThat(checked)
                .as("no method-naming row was resolved, so this assertion proved nothing")
                .isEqualTo(naming);
        assertThat(checked).isPositive();
    }

    @Test
    @DisplayName("an in-suite demonstration is preferred, and the register says which are not")
    void theRegisterDistinguishesTheTwoForms() {
        // Not a threshold on how many are in-suite - that would be a number nobody could justify.
        // What matters is that BOTH forms are present and labelled, because a register in which
        // every row said the same thing would have stopped carrying information.
        Set<String> forms = new TreeSet<>();
        rowForms().values().forEach(forms::add);

        assertThat(forms)
                .as("the form column distinguishes a proof that runs from one that was run once")
                .containsExactly("In-suite", "Recorded");
    }

    @Test
    @DisplayName("the registers and the catalogue are actually parsed")
    void everythingIsActuallyRead() {
        // Without this, a reformatted table would empty every set above and the containsAll
        // assertions would pass over nothing at all - the vacuity P0-TST-007 found in a privilege
        // check and the P0-TSK-033 review found in a register parser. Named entries rather than
        // counts, because a count is satisfied by parsing the wrong table.
        assertThat(currentPhase())
                .as("a current phase of 0 would silently reduce this guard to what it was before"
                        + " P1-TSK-024, and the register would stop covering Phase 1 with nothing"
                        + " reporting it")
                .isGreaterThanOrEqualTo(1);

        assertThat(invariantsUpToCurrentPhase())
                .hasSizeGreaterThan(10)
                .contains(
                        "INV-MON-01",
                        "INV-MON-05",
                        "INV-HIST-03",
                        "INV-AUD-02",
                        // Phase 1. INV-AUD-03 is here deliberately: it is `Phase: 1 onward` and is
                        // NOT in the INV-IDN group, so a guard extended only to INV-IDN-* - which
                        // is what this task's own text asked for - would have missed it.
                        "INV-IDN-01",
                        "INV-IDN-02",
                        "INV-AUD-03");
        assertThat(invariantRows().keySet())
                .hasSizeGreaterThan(10)
                .contains("INV-MON-05", "INV-EVT-01", "INV-IDN-06");
        assertThat(itemRows())
                .hasSizeGreaterThan(5)
                .contains("P0-TST-001", "P0-TST-009", "P1-TST-001");
        assertThat(backlogTestItems())
                .hasSizeGreaterThan(5)
                .contains("P0-TST-004", "P1-TST-003");
    }

    // ------------------------------------------------------------------

    private record TestReference(String className, String methodName) {}

    /**
     * The phase this project has reached, from {@code CURRENT_STATE.md} §Current Phase.
     *
     * <p><strong>Derived, not written down.</strong> A constant would be the stale list this
     * repository closes by derivation everywhere else, and it would need editing again at Phase 2 —
     * which is precisely the "extension not needed again" this task was asked for.
     *
     * <p>The <em>highest</em> phase the section names, rather than the one marked
     * {@code IN_PROGRESS}: a status word is prose that changes shape between phases, and a phase
     * that has been reached does not stop having been reached when it completes.
     */
    private static int currentPhase() {
        int highest = -1;
        for (String line : stateSection("## Current Phase")) {
            Matcher heading = PHASE_HEADING.matcher(line);
            if (heading.find()) {
                highest = Math.max(highest, Integer.parseInt(heading.group(1)));
            }
        }
        if (highest < 0) {
            throw new IllegalStateException(
                    "No phase heading in " + STATE + " section Current Phase. Without one, "
                            + "\"every phase reached\" would silently become \"no phase at all\".");
        }
        return highest;
    }

    /** The lines of one {@code CURRENT_STATE.md} section, bounded as {@link #registerSection} is. */
    private static List<String> stateSection(String heading) {
        List<String> lines = new ArrayList<>();
        boolean inSection = false;
        for (String line : readRepositoryFile(STATE).split("\\R")) {
            if (line.startsWith("## ")) {
                if (inSection) {
                    break;
                }
                inSection = line.equals(heading);
                continue;
            }
            if (inSection) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Every invariant the catalogue defines, whatever its phase. */
    private static Set<String> allInvariants() {
        Set<String> all = new TreeSet<>();
        for (String line : readRepositoryFile(CATALOGUE).split("\\R")) {
            Matcher heading = INVARIANT_HEADING.matcher(line);
            if (heading.find()) {
                all.add(heading.group(1));
            }
        }
        return all;
    }

    /** Invariants the catalogue marks as belonging to a phase this project has reached. */
    private static Set<String> invariantsUpToCurrentPhase() {
        Set<String> reached = new TreeSet<>();
        int current = currentPhase();
        String invariant = null;

        for (String line : readRepositoryFile(CATALOGUE).split("\\R")) {
            Matcher heading = INVARIANT_HEADING.matcher(line);
            if (heading.find()) {
                invariant = heading.group(1);
                continue;
            }
            Matcher phase = PHASE_LINE.matcher(line);
            if (invariant == null || !phase.find()) {
                continue;
            }
            for (int reachedPhase = 0; reachedPhase <= current; reachedPhase++) {
                if (mentionsPhase(phase.group(1), reachedPhase)) {
                    reached.add(invariant);
                    break;
                }
            }
        }
        return reached;
    }

    /**
     * Whether a phase line names {@code phase} as a standalone number.
     *
     * <p>Several are multi-phase — {@code "0 (kernel), 4 (transfers), 5 (payments)"} — so this cannot
     * be an equality check, and the boundaries matter in both directions: without them "1" would
     * match the 1 inside "13" and "0" the 0 inside "10".
     */
    private static boolean mentionsPhase(String phaseLine, int phase) {
        return Pattern.compile("(?<![0-9])" + phase + "(?![0-9])").matcher(phaseLine).find();
    }

    /**
     * Every §2 row, and every test reference in it.
     *
     * <p>A <strong>list</strong> per row, because a row legitimately names several - a property
     * proven by a build rule and by a behavioural test is two controls blind in different
     * directions, and the register says so. The previous parser took the first and required the
     * column to end there, so nine rows matched nothing at all and were silently skipped.
     *
     * <p>An invariant may also appear on more than one row; the references merge, because both rows
     * are claims about the same property and both must name tests that exist.
     */
    private static Map<String, List<TestReference>> invariantRows() {
        Map<String, List<TestReference>> rows = new LinkedHashMap<>();
        for (String line : registerSection("## 2.")) {
            Matcher row = INVARIANT_ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            List<TestReference> references =
                    rows.computeIfAbsent(row.group(1), key -> new ArrayList<>());
            Matcher reference = TEST_REFERENCE.matcher(row.group(2));
            while (reference.find()) {
                references.add(new TestReference(reference.group(1), reference.group(2)));
            }
        }
        return rows;
    }

    /**
     * The references of every §2 row, keyed by position so two rows for one invariant stay apart.
     *
     * <p>{@link #invariantRows} merges them, which is right for <em>does every named test exist?</em>
     * and wrong for <em>does every row name one?</em> — merging is precisely what let an unreadable
     * row hide behind a readable sibling.
     */
    private static Map<String, List<TestReference>> rowReferences() {
        Map<String, List<TestReference>> rows = new LinkedHashMap<>();
        int position = 0;
        for (String line : registerSection("## 2.")) {
            Matcher row = INVARIANT_ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            List<TestReference> references = new ArrayList<>();
            Matcher reference = TEST_REFERENCE.matcher(row.group(2));
            while (reference.find()) {
                references.add(new TestReference(reference.group(1), reference.group(2)));
            }
            rows.put(row.group(1) + " (row " + position++ + ")", references);
        }
        return rows;
    }

    /** The form column of every §2 row, keyed by the row's position so duplicates survive. */
    private static Map<String, String> rowForms() {
        Map<String, String> forms = new LinkedHashMap<>();
        int position = 0;
        for (String line : registerSection("## 2.")) {
            Matcher row = INVARIANT_ROW.matcher(line);
            if (row.find()) {
                forms.put(row.group(1) + "#" + position++, row.group(3));
            }
        }
        return forms;
    }

    private static Set<String> itemRows() {
        Set<String> items = new TreeSet<>();
        for (String line : registerSection("## 4.")) {
            Matcher row = ITEM_ROW.matcher(line);
            if (row.find()) {
                items.add(row.group(1));
            }
        }
        return items;
    }

    /**
     * The lines of one register section.
     *
     * <p>Bounded to the section rather than matched across the document. Three times in
     * {@code P0-TSK-037} the defect was pattern-matching a whole file — a bullet list that ran into
     * the next heading, an ADR phrase found in an unrelated sentence, and a table row shape that
     * matched another table entirely.
     */
    private static List<String> registerSection(String heading) {
        List<String> lines = new ArrayList<>();
        boolean inSection = false;

        for (String line : readRepositoryFile(REGISTER).split("\\R")) {
            if (line.startsWith("## ")) {
                if (inSection) {
                    break;
                }
                inSection = line.startsWith(heading);
                continue;
            }
            if (inSection) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Set<String> backlogTestItems() {
        Set<String> items = new TreeSet<>();
        for (String line : readRepositoryFile(BACKLOG).split("\\R")) {
            Matcher item = BACKLOG_ITEM.matcher(line);
            if (item.find()) {
                items.add(item.group(1));
            }
        }
        return items;
    }

    /**
     * Every test class in the build, by simple name.
     *
     * <p>Read from each module's compiled test output on disk, because a module's test classes are
     * deliberately not on another module's classpath. {@code app}'s test tasks declare those
     * directories as an input and depend on their compilation ({@code app/build.gradle.kts}) — the
     * declaration {@code P0-TSK-036} discovered was load-bearing when a mutation survived on a
     * stale class file.
     */
    private static Map<String, JavaClass> testClassesBySimpleName() {
        Map<String, JavaClass> byName = new LinkedHashMap<>();
        Path root = repositoryRoot();

        for (String module : ProductionModules.onClasspathWithProductionClasses()) {
            Path testClasses = root.resolve(module).resolve("build/classes/java/test");
            if (!Files.isDirectory(testClasses)) {
                continue;
            }
            new ClassFileImporter()
                    .importPath(testClasses)
                    .forEach(javaClass -> byName.putIfAbsent(javaClass.getSimpleName(), javaClass));
        }
        return byName;
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    private static String readRepositoryFile(String relativePath) {
        Path path = repositoryRoot().resolve(relativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

}
