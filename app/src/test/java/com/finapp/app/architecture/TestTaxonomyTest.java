package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.tierprobe.TierProbes;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The test taxonomy, enforced (`P0-TSK-036`).
 *
 * <p>A taxonomy that exists only in a document is a taxonomy that describes the test suite as it
 * was on the day it was written. Four things are therefore held to each other by this suite: the
 * tiers Gradle registers, the tiers {@link TestTier} declares, the tier each test class is
 * actually in, and the tiers CI invokes.
 *
 * <p><strong>The failure this exists to prevent is silent in every direction.</strong> A test in
 * the wrong tier still compiles and still passes; it is simply slower than it should be, or — far
 * worse — it runs in a task nothing invokes and reports nothing at all. That last one is not
 * hypothetical here: the {@code P0-TSK-027} review found CI naming {@code :platform:databaseTest}
 * explicitly, a list of one that went stale the moment a second module gained database tests, so
 * {@code :app:databaseTest} would have run on one developer's machine and nowhere else. Splitting
 * one test task into four multiplies the number of ways to make that mistake, which is why the
 * split arrives with these guards rather than before them.
 */
@Tag("architecture")
@DisplayName("Test taxonomy (P0-TSK-036)")
class TestTaxonomyTest {

    private static final String TIERS_PROPERTY = "finapp.test.tiers";
    private static final String EXTERNAL_TIERS_PROPERTY = "finapp.test.tiers.external";
    private static final String TESTING_DOCUMENT = "docs/project/TESTING.md";
    private static final String CI_WORKFLOW = ".github/workflows/ci.yml";

    /** Annotations that make a class something a test runner will execute. */
    private static final Set<String> TEST_METHOD_ANNOTATIONS =
            Set.of(
                    "org.junit.jupiter.api.Test",
                    "org.junit.jupiter.api.RepeatedTest",
                    "org.junit.jupiter.api.TestFactory",
                    "org.junit.jupiter.api.TestTemplate",
                    "org.junit.jupiter.params.ParameterizedTest");

    /** Class-level annotations that hand the class to a runner of their own. */
    private static final Set<String> CLASS_LEVEL_RUNNER_ANNOTATIONS =
            Set.of("com.tngtech.archunit.junit.AnalyzeClasses");

    /** Annotations that make a FIELD an executable test. ArchUnit's `@ArchTest` is one. */
    private static final Set<String> FIELD_LEVEL_TEST_ANNOTATIONS =
            Set.of("com.tngtech.archunit.junit.ArchTest");

    /** The class-level annotation that makes a class an ArchUnit suite. */
    private static final String ANALYZE_CLASSES = "com.tngtech.archunit.junit.AnalyzeClasses";

    /** JUnit's tag, which Jupiter reads and ArchUnit's engine does not. */
    private static final String JUNIT_TAG = "org.junit.jupiter.api.Tag";

    /** ArchUnit's tag, which its engine reads and Jupiter does not. */
    private static final String ARCH_TAG = "com.tngtech.archunit.junit.ArchTag";

    /**
     * Tags that carry no scheduling meaning but are deliberate selectors.
     *
     * <p>Empty, and that is the point: the vocabulary is closed, so a tag outside it fails the
     * build rather than being silently ignored. Adding one here is a decision somebody makes,
     * which is the whole difference between a selector and a typo.
     */
    private static final Set<String> NON_TIER_TAGS = Set.of();

    // ------------------------------------------------------------------
    // The tier a test is in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every test class declares a tier at least as heavy as the one it needs")
    void everyTestClassDeclaresATierAtLeastAsHeavyAsItNeeds() {
        Map<String, String> understated = new TreeMap<>();

        for (SelectedTest testClass : testClasses()) {
            TestTier required = testClass.requiredTier();
            TestTier declared = testClass.declaredTier();
            if (!declared.atLeast(required)) {
                understated.put(
                        testClass.name(),
                        "needs " + required.name() + " but is in " + declared.name());
            }
        }

        assertThat(understated)
                .as(
                        "a test that needs more than its tier provides runs in a task that cannot"
                            + " give it — add @Tag(\"%s\") or the tag for the tier it needs. The"
                            + " reverse is allowed: declaring a heavier tier is how the"
                            + " undetectable cases are handled (see"
                            + " aTierCanBeDeclaredHeavierThanItIsDetected).",
                        TestTier.DATABASE.tag())
                .isEmpty();
    }

    @Test
    @DisplayName("no test class declares two tiers")
    void noTestClassDeclaresTwoTiers() {
        // TestTier.declaredBy throws on a second tier tag, because a class in two tiers runs
        // twice and its tier becomes a function of which task selected it first.
        assertThat(testClasses()).allSatisfy(SelectedTest::declaredTier);
    }

    @Test
    @DisplayName("an ArchUnit suite carries the tier tag BOTH engines read")
    void everyArchUnitSuiteIsTaggedForBothEngines() {
        // THE PROPERTY: both engines must agree which tier a class belongs to.
        //
        // A class annotated @AnalyzeClasses is executed by TWO JUnit Platform engines. Jupiter runs
        // its @Test methods and reads JUnit's @Tag; ArchUnit runs its @ArchTest FIELDS under its own
        // engine, whose descriptors read `com.tngtech.archunit.junit.ArchTag` and cannot see @Tag at
        // all - established by disassembling AbstractArchUnitTestDescriptor.findTagsOn, not by
        // reading documentation.
        //
        // So a suite tagged only with @Tag leaves every rule field UNTAGGED, and the two tier tasks
        // then disagree in opposite directions: `architectureTest` selects by INCLUSION and gets
        // none of them, while `unitTest` selects by EXCLUSION and takes all of them. That is what
        // P1-TSK-025 found - 28 rule fields in the wrong tier, and `ModuleBoundaryRulesTest`, which
        // has no @Test method at all, producing NO RESULT FILE in the architecture tier: not a suite
        // that ran zero cases, a suite that did not appear.
        //
        // WHY NO EXISTING GUARD SAW IT. `theTiersPartitionTheHermeticSuite` asserts a SUM, and the
        // sum was right - every rule was in exactly one tier. A check on a total cannot see a
        // misallocation that preserves the total.
        Map<String, String> disagreeing = new TreeMap<>();
        int suites = 0;
        for (SelectedTest testClass : testClasses()) {
            if (!testClass.outer().isAnnotatedWith(ANALYZE_CLASSES)) {
                continue;
            }
            suites++;
            Set<String> junitTags = tagValues(testClass.outer(), JUNIT_TAG);
            Set<String> archTags = tagValues(testClass.outer(), ARCH_TAG);
            if (!junitTags.equals(archTags)) {
                disagreeing.put(
                        testClass.name(), "@Tag" + junitTags + " but @ArchTag" + archTags);
            }
        }

        assertThat(suites)
                .as("the sweep must find the ArchUnit suites, or this guard checks nothing - the"
                        + " vacuity every document- and reflection-backed check here has to answer")
                .isPositive();

        assertThat(disagreeing)
                .as("an @AnalyzeClasses suite must carry @ArchTag as well as @Tag, with the same"
                        + " value. Without it every @ArchTest rule below is untagged, and the tier"
                        + " that selects by inclusion silently runs none of them")
                .isEmpty();
    }

    /**
     * The stated limit, recorded rather than left for somebody to discover.
     *
     * <p>This asserts that the two annotations <strong>agree</strong>, not that ArchUnit reads
     * {@code ArchTag} — that is a fact about the engine, and asserting it would mean disassembling
     * a dependency on every build. If ArchUnit ever started reading {@code @Tag}, this guard would
     * go on requiring an {@code @ArchTag} that had become unnecessary.
     *
     * <p><strong>That errs in the safe direction and is why the shape was chosen.</strong> A false
     * requirement is a build failure somebody investigates; a false pass is silence. The same
     * reasoning the contract classifier uses ({@code P0-TSK-026}: a false BREAKING is visible and
     * fixable, a false COMPATIBLE fails at the customer).
     */
    private static Set<String> tagValues(JavaClass javaClass, String annotation) {
        if (!javaClass.isAnnotatedWith(annotation)) {
            return Set.of();
        }
        Object value = javaClass.getAnnotationOfType(annotation).get("value").orElseThrow();
        return Set.of(String.valueOf(value));
    }

    @Test
    @DisplayName("no tier is empty")
    void noTierIsEmpty() {
        // A Test task whose tag selects nothing PASSES, writes no result file, and says nothing —
        // proven by running :sharedkernel:sliceTest, which is legitimately empty and reports
        // BUILD SUCCESSFUL in one second. That is survivable per module and not survivable across
        // the repository: CI runs `build`, which runs `test` and NEVER the tier tasks, so a tier
        // that had quietly become empty would be discovered only by a developer wondering why
        // their command was so fast.
        Map<TestTier, Long> members = new TreeMap<>();
        for (TestTier tier : TestTier.values()) {
            members.put(
                    tier,
                    testClasses().stream().filter(t -> t.declaredTier() == tier).count());
        }

        assertThat(members)
                .as("a tier with no members is a task that reports success for work it did not do")
                .allSatisfy((tier, count) -> assertThat(count).as("%s", tier).isPositive());
    }

    @Test
    @DisplayName("every @Tag value is one the taxonomy declares")
    void everyTagIsADeclaredTag() {
        // The vocabulary is closed, for the reason AuditableAction's is: an unrecognised tag is
        // silently ignored, so @Tag("databse") reads as a tier and schedules nothing.
        //
        // It was caught when probed, but by luck rather than by design — the class was also a
        // @SpringBootTest, so detection floored it at SLICE and the misspelling surfaced as
        // "needs SLICE but is in UNIT". A class detection cannot see would have had no floor.
        Set<String> declared = new TreeSet<>();
        Stream.of(TestTier.values())
                .filter(tier -> !tier.isDefaultTier())
                .forEach(tier -> declared.add(tier.tag()));
        declared.addAll(NON_TIER_TAGS);

        Map<String, Set<String>> unknown = new TreeMap<>();
        for (SelectedTest testClass : testClasses()) {
            Set<String> outside = new TreeSet<>(TestTier.tagValues(testClass.outer()));
            outside.removeAll(declared);
            if (!outside.isEmpty()) {
                unknown.put(testClass.name(), outside);
            }
        }

        assertThat(unknown)
                .as(
                        "an unrecognised tag is ignored, not rejected — add it to TestTier as a"
                                + " tier or to NON_TIER_TAGS as a deliberate selector. Declared:"
                                + " %s",
                        declared)
                .isEmpty();
    }

    @Test
    @DisplayName("detection fires on each tier's signature, and not on a class that needs nothing")
    void detectionFiresOnEachSignature() {
        // The teeth. Without these, the guard above would pass over a suite in which detection
        // silently matched nothing — the vacuity that ProductionModules exists to prevent for the
        // other rule suites, in the one place their coverage guard cannot reach.
        assertThat(detectedTierOf(TierProbes.NeedsADatabase.class)).isEqualTo(TestTier.DATABASE);
        assertThat(detectedTierOf(TierProbes.NeedsASpringContext.class)).isEqualTo(TestTier.SLICE);
        assertThat(detectedTierOf(TierProbes.NeedsTheCompiledClasses.class))
                .isEqualTo(TestTier.ARCHITECTURE);
        assertThat(detectedTierOf(TierProbes.NeedsNothing.class)).isEqualTo(TestTier.UNIT);

        // The false positive that narrowed the signature. Mentioning java.sql.Connection is not
        // needing a database: NoDirectBrokerPublicationRulesTest's fixture declares
        // OutboxWriter<Connection> to model production's shape and opens nothing. Detection now
        // keys on ACQUISITION — DriverManager, DataSource, a container, the harness — and a rule
        // that pushed a hermetic ArchUnit suite into the database tier is a rule people disable.
        assertThat(detectedTierOf(TierProbes.MentionsAConnection.class)).isEqualTo(TestTier.UNIT);
    }

    @Test
    @DisplayName("a tier can be declared heavier than it is detected, and that is deliberate")
    void aTierCanBeDeclaredHeavierThanItIsDetected() {
        // Detection reads the test class's own bytecode, so it sees a JDBC call and cannot see a
        // @SpringBootTest reaching PostgreSQL through the application's own DataSource — which is
        // exactly what DashboardQueriesResolveTest and TraceAcrossDatabaseTest do: neither names
        // a database anywhere, and both need one. There is no bytecode in those classes to detect,
        // because the requirement belongs to the context they start.
        //
        // Over-declaration is therefore the supported way to say "this needs more than you can
        // see", and it must not be reported as a violation.
        assertThat(TestTier.UNIT.atLeast(TestTier.UNIT)).isTrue();
        assertThat(TestTier.DATABASE.atLeast(TestTier.SLICE)).isTrue();
        assertThat(TestTier.SLICE.atLeast(TestTier.DATABASE)).isFalse();
    }

    @Test
    @DisplayName("the sweep sees the test classes of every module")
    void theSweepSeesEveryModule() {
        // The vacuity guard, and it is not decorative: this test reads class files from sibling
        // modules' build directories rather than from its own classpath, so a module whose tests
        // had not been compiled would contribute nothing and every assertion above would pass
        // over it in silence.
        Map<String, Integer> counts = new TreeMap<>();
        testClassesByModule().forEach((module, classes) -> counts.put(module, classes.size()));

        assertThat(counts.keySet())
                .as("every module with production code must also have its tests swept")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());
        assertThat(counts.values())
                .as("a module contributing no test classes means the sweep did not reach it")
                .allSatisfy(count -> assertThat(count).isPositive());
    }

    @Test
    @DisplayName("every class named *Test is selected by the sweep, and every selected class is")
    void everyClassNamedTestIsSelected() {
        // Two discovery rules cross-checking each other, because each is blind where the other
        // is not. Selection is by execution marker, which is what a runner actually uses; naming
        // is what a human reads. A class the marker rule misses does not fail — it is simply
        // absent, and the tier guard above then reports one fewer violation than exists.
        //
        // This is not hypothetical: it caught ModuleBoundaryRulesTest, whose @ArchTest fields
        // carry no method annotation, being skipped by the whole taxonomy.
        Set<String> selected = new LinkedHashSet<>();
        testClasses().forEach(testClass -> selected.add(testClass.name()));

        Set<String> namedTest = new LinkedHashSet<>();
        Set<String> allClasses = new LinkedHashSet<>();
        Path root = repositoryRoot();
        for (String module : ProductionModules.onClasspathWithProductionClasses()) {
            Path testClasses = root.resolve(module).resolve("build/classes/java/test");
            if (!Files.isDirectory(testClasses)) {
                continue;
            }
            new ClassFileImporter()
                    .importPath(testClasses)
                    .forEach(
                            javaClass -> {
                                allClasses.add(javaClass.getName());
                                // Top-level only, matching the sweep: a @Nested class is named
                                // for what it groups (Arithmetic, Overflow) and is not required
                                // to end in Test.
                                if (javaClass.getEnclosingClass().isEmpty()
                                        && javaClass.getSimpleName().endsWith("Test")) {
                                    namedTest.add(javaClass.getName());
                                }
                            });
        }

        assertThat(namedTest)
                .as("a class named *Test that no runner marker selects is invisible to the sweep")
                .isSubsetOf(selected);
        assertThat(selected)
                .as("a test the runner executes must be named *Test, so a reader can find it")
                .isSubsetOf(namedTest);
        assertThat(allClasses)
                .as("the name sweep must actually have read some classes")
                .hasSizeGreaterThan(selected.size());
    }

    // ------------------------------------------------------------------
    // The tiers themselves
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gradle registers exactly the tiers TestTier declares")
    void gradleRegistersExactlyTheDeclaredTiers() {
        // A build script and a Java enum cannot share a constant, so the build passes its
        // definition across as data and this asserts the two agree. Without it, adding a tier in
        // one place and not the other produces either a task nothing checks or a check for a task
        // that does not exist.
        Map<String, String> fromGradle = tierTasksFromGradle();

        Map<String, String> fromJava = new LinkedHashMap<>();
        for (TestTier tier : TestTier.values()) {
            fromJava.put(tier.taskName(), tier.tag());
        }

        assertThat(fromGradle)
                .as("finapp.java-conventions and TestTier disagree about the tiers")
                .containsExactlyInAnyOrderEntriesOf(fromJava);
    }

    @Test
    @DisplayName("exactly one tier is the default, and it is the one Gradle selects by exclusion")
    void exactlyOneTierIsTheDefault() {
        // The property that makes it impossible for a test to run in no tier at all. If the
        // default tier ever acquired a tag of its own, an untagged test would be selected by
        // nothing — and would report nothing, which looks identical to passing.
        assertThat(Stream.of(TestTier.values()).filter(TestTier::isDefaultTier).toList())
                .containsExactly(TestTier.UNIT);
    }

    @Test
    @DisplayName("only the tiers needing external infrastructure are excluded from `test`")
    void onlyExternalTiersAreExcludedFromTest() {
        // `build` must keep running every hermetic tier. If a tier were added to this set by
        // mistake it would silently leave `./gradlew build`, and the tests in it would run only
        // in the one CI job that names their task.
        Set<String> external =
                Set.of(System.getProperty(EXTERNAL_TIERS_PROPERTY, "").split(","));

        assertThat(external)
                .as("only the database tier needs something outside the JVM today")
                .containsExactly(TestTier.DATABASE.taskName());
    }

    // ------------------------------------------------------------------
    // CI runs all tiers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CI invokes a task covering every tier")
    void ciInvokesATaskCoveringEveryTier() {
        Set<String> invoked = gradleTasksInvokedByCi();

        Map<String, String> uncovered = new TreeMap<>();
        for (TestTier tier : TestTier.values()) {
            if (!invoked.contains(tier.coveringCiTask())) {
                uncovered.put(tier.name(), "needs '" + tier.coveringCiTask() + "'");
            }
        }

        assertThat(uncovered)
                .as(
                        "a tier CI never invokes is a tier whose tests run on developers' machines"
                            + " and nowhere else. %s invokes: %s",
                        CI_WORKFLOW, invoked)
                .isEmpty();
    }

    @Test
    @DisplayName("CI invokes tier tasks unqualified, so a new module is covered automatically")
    void ciInvokesTierTasksUnqualified() {
        // `:platform:databaseTest` was the real defect: a module-qualified task name is a list of
        // one, and it went stale the moment a second module gained database tests. An unqualified
        // name runs the task in every module that has it.
        String workflow = readRepositoryFile(CI_WORKFLOW);

        List<String> qualified = new ArrayList<>();
        for (TestTier tier : TestTier.values()) {
            Matcher matcher =
                    Pattern.compile("(?m)^\\s*run:.*gradlew[^\\n]*:(\\w+):" + tier.taskName())
                            .matcher(workflow);
            while (matcher.find()) {
                qualified.add(matcher.group());
            }
        }

        assertThat(qualified)
                .as("a module-qualified tier task covers that module only")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // The document
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TESTING.md names exactly the tiers that exist, with their task and tag")
    void theDocumentNamesExactlyTheTiersThatExist() {
        // The tier table is the authority, not the prose. Scanning the whole document for
        // backticked names ending in "Test" was the first attempt and it is wrong in the
        // expensive direction: it matched every test class the document mentions —
        // ErrorCodeRegistryTest, ColumnClassificationTest — and reported them as tier tasks that
        // no longer exist. A guard that fires on correct prose is a guard someone deletes.
        // Scanned within the tier section only, not across the document. Matching the ROW SHAPE
        // anywhere was the first version, and P0-TSK-037 immediately broke it: §5a describes the
        // provider harness with an | **Outbound** | ... | table, which is the same shape, so the
        // guard reported two tiers that do not exist. Bounding the section makes the collision
        // impossible rather than guarding against it - the identical correction
        // ProviderFailureCoverageTest needed for CLAUDE.md's bullet list, in the same session.
        Map<String, String> rows = new TreeMap<>();
        Pattern tableRow = Pattern.compile("^\\|\\s*\\*\\*(\\w+)\\*\\*\\s*\\|(.*)$");
        boolean inTierSection = false;

        for (String line : readRepositoryFile(TESTING_DOCUMENT).split("\\R")) {
            if (line.startsWith("## ")) {
                if (inTierSection) {
                    break;
                }
                inTierSection = line.startsWith("## 1.");
                continue;
            }
            if (!inTierSection) {
                continue;
            }
            Matcher row = tableRow.matcher(line);
            if (row.matches()) {
                rows.put(row.group(1), row.group(2));
            }
        }

        assertThat(rows.keySet())
                .as("%s must have exactly one tier-table row per tier", TESTING_DOCUMENT)
                .isEqualTo(
                        Stream.of(TestTier.values())
                                .map(tier -> tier.name().toLowerCase(Locale.ROOT))
                                .collect(Collectors.toCollection(TreeSet::new)));

        Map<String, String> wrong = new TreeMap<>();
        for (TestTier tier : TestTier.values()) {
            String row = rows.get(tier.name().toLowerCase(Locale.ROOT));
            if (!row.contains("`" + tier.taskName() + "`")) {
                wrong.put(tier.name(), "row does not name task `" + tier.taskName() + "`");
            } else if (!tier.isDefaultTier() && !row.contains("`" + tier.tag() + "`")) {
                wrong.put(tier.name(), "row does not name tag `" + tier.tag() + "`");
            }
        }
        assertThat(wrong).as("%s describes a tier incorrectly", TESTING_DOCUMENT).isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static TestTier detectedTierOf(Class<?> type) {
        return TestTier.requiredBy(new ClassFileImporter().importClass(type));
    }

    /**
     * One test class as the taxonomy sees it: a top-level class plus its {@code @Nested} tree.
     *
     * <p>The tree is folded in rather than treated as separate tests because JUnit inherits a
     * {@code @Tag} from an enclosing class downward. The tier is therefore a property of the outer
     * class, and both halves of the comparison have to be computed over the whole tree: the tag
     * is only ever on the outside, and the requirement — a JDBC call, a Spring annotation — may
     * be only on the inside.
     */
    private record SelectedTest(JavaClass outer, List<JavaClass> nested) {

        String name() {
            return outer.getName();
        }

        /** The heaviest requirement anywhere in the tree. */
        TestTier requiredTier() {
            TestTier required = TestTier.requiredBy(outer);
            for (JavaClass javaClass : nested) {
                required = TestTier.heavier(required, TestTier.requiredBy(javaClass));
            }
            return required;
        }

        TestTier declaredTier() {
            return TestTier.declaredBy(outer);
        }
    }

    /** Every class a test runner would execute, from every module. */
    private static List<SelectedTest> testClasses() {
        return testClassesByModule().values().stream().flatMap(List::stream).toList();
    }

    private static Map<String, List<SelectedTest>> testClassesByModule() {
        Map<String, List<SelectedTest>> byModule = new TreeMap<>();
        Path root = repositoryRoot();

        for (String module : ProductionModules.onClasspathWithProductionClasses()) {
            Path testClasses = root.resolve(module).resolve("build/classes/java/test");
            if (!Files.isDirectory(testClasses)) {
                // Recorded as an empty list rather than skipped: theSweepSeesEveryModule turns
                // this into a named failure, whereas skipping would make it invisible.
                byModule.put(module, List.of());
                continue;
            }
            byModule.put(module, selectTestClasses(new ClassFileImporter().importPath(testClasses)));
        }
        return byModule;
    }

    /**
     * The top-level classes a runner will execute, each with its {@code @Nested} tree.
     *
     * <p>Selection is by the presence of an execution marker, never by the class name. Gradle
     * discovers tests from bytecode, so a class named {@code FooSpec} still runs, and a
     * name-based sweep would silently exempt exactly the classes that had already departed from
     * the convention.
     *
     * <p><strong>A marker is not always on a method of the class itself.</strong> Two shapes broke
     * the first version of this, and {@link #everyClassNamedTestIsSelected} found both — which is
     * what it is for, since a discovery rule that misses a class does not fail, it reports one
     * fewer:
     *
     * <ul>
     *   <li>{@link ModuleBoundaryRulesTest}, the oldest and most fundamental rule suite here — the
     *       one enforcing {@code app -> platform -> sharedkernel} — declares no {@code @Test}
     *       method at all: ArchUnit's runner executes {@code @ArchTest} <em>fields</em> under a
     *       class-level {@code @AnalyzeClasses};
     *   <li>{@code MoneyTest} holds only {@code @Nested} classes, so every test method belongs to
     *       an inner class and the outer one — where the tag has to go — carries no marker.
     * </ul>
     */
    private static List<SelectedTest> selectTestClasses(JavaClasses imported) {
        Map<String, List<JavaClass>> nestedByOuter = new LinkedHashMap<>();
        List<JavaClass> topLevel = new ArrayList<>();
        for (JavaClass javaClass : imported) {
            if (javaClass.getEnclosingClass().isPresent()) {
                nestedByOuter
                        .computeIfAbsent(outermost(javaClass).getName(), key -> new ArrayList<>())
                        .add(javaClass);
            } else {
                topLevel.add(javaClass);
            }
        }

        List<SelectedTest> selected = new ArrayList<>();
        for (JavaClass javaClass : topLevel) {
            List<JavaClass> nested = nestedByOuter.getOrDefault(javaClass.getName(), List.of());
            boolean executable =
                    declaresAnExecutionMarker(javaClass)
                            || nested.stream()
                                    .anyMatch(TestTaxonomyTest::declaresAnExecutionMarker);
            if (executable) {
                selected.add(new SelectedTest(javaClass, nested));
            }
        }
        return selected;
    }

    private static JavaClass outermost(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    private static boolean declaresAnExecutionMarker(JavaClass javaClass) {
        boolean hasRunnerAnnotation =
                javaClass.getAnnotations().stream()
                        .anyMatch(
                                annotation ->
                                        CLASS_LEVEL_RUNNER_ANNOTATIONS.contains(
                                                annotation.getRawType().getName()));
        if (hasRunnerAnnotation) {
            return true;
        }
        for (JavaMethod method : javaClass.getMethods()) {
            boolean isTestMethod =
                    method.getAnnotations().stream()
                            .anyMatch(
                                    annotation ->
                                            TEST_METHOD_ANNOTATIONS.contains(
                                                    annotation.getRawType().getName()));
            if (isTestMethod) {
                return true;
            }
        }
        return javaClass.getFields().stream()
                .anyMatch(
                        field ->
                                field.getAnnotations().stream()
                                        .anyMatch(
                                                annotation ->
                                                        FIELD_LEVEL_TEST_ANNOTATIONS.contains(
                                                                annotation.getRawType().getName())));
    }

    /** Tier task name to tag, as the build declared it. */
    private static Map<String, String> tierTasksFromGradle() {
        String declaration = System.getProperty(TIERS_PROPERTY);
        assertThat(declaration)
                .as(
                        "%s is not set — this test must run through Gradle, which is where the"
                                + " tier declaration lives",
                        TIERS_PROPERTY)
                .isNotNull();

        Map<String, String> tiers = new LinkedHashMap<>();
        for (String entry : declaration.split(",")) {
            int equals = entry.indexOf('=');
            tiers.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
        return tiers;
    }

    /** Every Gradle task named on a `run:` line of the CI workflow. */
    private static Set<String> gradleTasksInvokedByCi() {
        Set<String> tasks = new LinkedHashSet<>();
        Matcher lines =
                Pattern.compile("(?m)^\\s*run:\\s*(.*gradlew.*)$")
                        .matcher(readRepositoryFile(CI_WORKFLOW));
        while (lines.find()) {
            for (String token : lines.group(1).trim().split("\\s+")) {
                if (!token.startsWith("-") && !token.contains("gradlew")) {
                    // Both the bare name and the qualified one, so `:platform:databaseTest`
                    // still counts as invoking the task — ciInvokesTierTasksUnqualified is what
                    // objects to the qualification, and it must be the test that says so.
                    tasks.add(token.substring(token.lastIndexOf(':') + 1));
                }
            }
        }
        return tasks;
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
