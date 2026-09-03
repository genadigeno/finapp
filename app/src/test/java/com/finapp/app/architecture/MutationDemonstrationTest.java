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
 * Every Phase 0 invariant has a recorded demonstration that its test can fail (`P0-TSK-038`).
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
            Pattern.compile(
                    "^\\|\\s*`(INV-[A-Z]+-\\d+)`\\s*\\|\\s*`([A-Za-z0-9_]+)(?:#([A-Za-z0-9_]+))?`"
                            + "\\s*\\|\\s*(In-suite|Recorded)\\s*\\|");

    /** A §4 register row: the `P0-TST-*` item it covers. */
    private static final Pattern ITEM_ROW = Pattern.compile("^\\|\\s*`(P0-TST-\\d+)`\\s*\\|");

    /** A completed test item in the backlog. */
    private static final Pattern BACKLOG_ITEM = Pattern.compile("^\\*\\*(P0-TST-\\d+)\\b");

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every Phase 0 invariant has a demonstration")
    void everyPhaseZeroInvariantHasADemonstration() {
        Set<String> demonstrated = new TreeSet<>(invariantRows().keySet());

        assertThat(demonstrated)
                .as(
                        """
                        %s section 2 must record a demonstration for every invariant %s marks as \
                        Phase 0.

                        PHASE_GATES.md criterion 3 requires each to have a test that FAILS when the \
                        invariant is broken. An invariant with a test and no demonstration is the \
                        case this register exists for: nothing distinguishes it from one whose test \
                        cannot fail.""",
                        REGISTER, CATALOGUE)
                .containsAll(phaseZeroInvariants());
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
                .as("%s section 4 must cover every P0-TST-* item in %s", REGISTER, BACKLOG)
                .containsAll(backlogTestItems());
    }

    @Test
    @DisplayName("every test named in the register exists")
    void everyNamedTestExists() {
        Map<String, JavaClass> testClasses = testClassesBySimpleName();

        Map<String, String> missing = new TreeMap<>();
        invariantRows()
                .forEach(
                        (invariant, reference) -> {
                            JavaClass javaClass = testClasses.get(reference.className());
                            if (javaClass == null) {
                                missing.put(invariant, reference.className() + " does not exist");
                            }
                        });

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
                        (invariant, reference) -> {
                            if (reference.methodName() == null) {
                                return;
                            }
                            JavaClass javaClass = testClasses.get(reference.className());
                            if (javaClass == null) {
                                return; // reported by everyNamedTestExists
                            }
                            boolean present =
                                    javaClass.getMethods().stream()
                                            .anyMatch(m -> m.getName().equals(reference.methodName()));
                            if (!present) {
                                missing.put(
                                        invariant,
                                        reference.className() + " has no " + reference.methodName());
                            }
                        });

        assertThat(missing)
                .as("an in-suite demonstration must name a method that is actually there")
                .isEmpty();

        // This test skips a row whose class it cannot find, deferring to everyNamedTestExists -
        // which means on its own it would pass over an empty sweep having checked nothing. Assert
        // it actually resolved the in-suite rows, so it does not depend on a sibling for coverage.
        long checked =
                invariantRows().values().stream()
                        .filter(reference -> reference.methodName() != null)
                        .filter(reference -> testClasses.containsKey(reference.className()))
                        .count();
        assertThat(checked)
                .as("no in-suite row was resolved, so this assertion proved nothing")
                .isEqualTo(
                        invariantRows().values().stream()
                                .filter(reference -> reference.methodName() != null)
                                .count());
        assertThat(checked).isPositive();
    }

    @Test
    @DisplayName("an in-suite demonstration is preferred, and the register says which are not")
    void theRegisterDistinguishesTheTwoForms() {
        // Not a threshold on how many are in-suite - that would be a number nobody could justify.
        // What matters is that BOTH forms are present and labelled, because a register in which
        // every row said the same thing would have stopped carrying information.
        Set<String> forms = new TreeSet<>();
        invariantRows().values().forEach(reference -> forms.add(reference.form()));

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
        assertThat(phaseZeroInvariants())
                .hasSizeGreaterThan(10)
                .contains("INV-MON-01", "INV-MON-05", "INV-HIST-03", "INV-AUD-02");
        assertThat(invariantRows().keySet())
                .hasSizeGreaterThan(10)
                .contains("INV-MON-05", "INV-EVT-01");
        assertThat(itemRows()).hasSizeGreaterThan(5).contains("P0-TST-001", "P0-TST-009");
        assertThat(backlogTestItems()).hasSizeGreaterThan(5).contains("P0-TST-004");
    }

    // ------------------------------------------------------------------

    private record TestReference(String className, String methodName, String form) {}

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

    /** Invariants the catalogue marks as Phase 0. */
    private static Set<String> phaseZeroInvariants() {
        Set<String> phaseZero = new TreeSet<>();
        String current = null;

        for (String line : readRepositoryFile(CATALOGUE).split("\\R")) {
            Matcher heading = INVARIANT_HEADING.matcher(line);
            if (heading.find()) {
                current = heading.group(1);
                continue;
            }
            Matcher phase = PHASE_LINE.matcher(line);
            if (current != null && phase.find() && mentionsPhaseZero(phase.group(1))) {
                phaseZero.add(current);
            }
        }
        return phaseZero;
    }

    /**
     * A phase line names Phase 0 if "0" appears as a standalone number.
     *
     * <p>Several are multi-phase — {@code "0 (kernel), 4 (transfers), 5 (payments)"} — so this
     * cannot be an equality check, and it must not match the 0 inside "10".
     */
    private static boolean mentionsPhaseZero(String phaseLine) {
        return Pattern.compile("(?<![0-9])0(?![0-9])").matcher(phaseLine).find();
    }

    private static Map<String, TestReference> invariantRows() {
        Map<String, TestReference> rows = new LinkedHashMap<>();
        for (String line : registerSection("## 2.")) {
            Matcher row = INVARIANT_ROW.matcher(line);
            if (row.find()) {
                rows.put(
                        row.group(1),
                        new TestReference(row.group(2), row.group(3), row.group(4)));
            }
        }
        return rows;
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
