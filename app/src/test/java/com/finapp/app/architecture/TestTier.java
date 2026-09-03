package com.finapp.app.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The test tiers, and the rule deciding which one a test class belongs to.
 *
 * <p>A tier is defined by <strong>what a test needs in order to run</strong>. That is the only
 * axis on which membership can be decided mechanically, and it is the axis that matters for
 * scheduling: a test needing a real PostgreSQL cannot share a task with one needing nothing,
 * because the task then costs what its heaviest member costs and fails wherever that member's
 * infrastructure is absent.
 *
 * <p>The constants are declared in <strong>escalation order</strong>: each tier needs strictly
 * more than the one before it, so {@link #ordinal()} is a meaningful comparison and
 * {@link #atLeast} is well defined. {@code P0-TSK-036}; the document is
 * {@code docs/project/TESTING.md} and the Gradle side is {@code finapp.java-conventions}.
 *
 * <p><strong>Detection is deliberately one-directional.</strong> {@link #requiredBy} reports the
 * heaviest requirement <em>visible in the test class's own bytecode</em>. A class may legitimately
 * declare a heavier tier than that — see {@code TestTaxonomyTest} for why the reverse cannot be
 * checked — so the enforced rule is "declared at least as heavy as detected", never equality.
 */
enum TestTier {

    /**
     * Needs nothing beyond the JVM. The <strong>default</strong> tier: it carries no tag, and its
     * Gradle task selects by <em>excluding</em> every other tier's tag rather than by including
     * one of its own. That is what makes it impossible for a test to run in no tier at all.
     */
    UNIT("", "unitTest"),

    /**
     * Needs the compiled classes of every module — a build output rather than a service, but not
     * something a test of a single class requires.
     */
    ARCHITECTURE("architecture", "architectureTest"),

    /** Needs a Spring application context. */
    SLICE("slice", "sliceTest"),

    /** Needs a real PostgreSQL. */
    DATABASE("database", "databaseTest");

    /**
     * Type-name prefixes whose presence in a test class proves it needs that tier.
     *
     * <p>Each entry is a facility a test cannot use without the tier's prerequisite being
     * present. They are prefixes rather than exact names so that a new class in the same package
     * is covered without anyone remembering — the same reasoning as the discovery in
     * {@code CommittedConfigurationHoldsNoSecretTest}.
     */
    private static final List<String> ARCHITECTURE_SIGNATURE = List.of("com.tngtech.archunit.");

    private static final List<String> SLICE_SIGNATURE =
            List.of(
                    "org.springframework.boot.test.",
                    "org.springframework.test.",
                    "org.springframework.boot.builder.");

    /**
     * Acquiring a connection, never merely mentioning one.
     *
     * <p>The first version listed {@code java.sql.} wholesale and immediately produced a false
     * positive: {@link NoDirectBrokerPublicationRulesTest} is an ArchUnit suite whose fixture
     * declares {@code OutboxWriter<java.sql.Connection>} to model the shape production code uses.
     * It opens nothing. Reporting it as needing PostgreSQL would have pushed a hermetic rule suite
     * into the database tier — and a rule with false positives is a rule somebody turns off, which
     * is the argument {@code secretsAreWrapped} already makes for keeping its vocabulary narrow.
     *
     * <p>So the signature is the small set of things that actually <em>obtain</em> a database:
     * the driver's entry point, a pooled source, a container, or the shared harness.
     *
     * <p><strong>The harness has its own package because of this rule.</strong> The three database
     * harnesses first sat in {@code com.finapp.platform.testing} beside {@code RepositoryPaths},
     * which reads a file and needs no database — and a prefix cannot tell them apart, so three
     * hermetic contract tests were immediately reported as needing PostgreSQL. Splitting
     * {@code ...testing.database} out keeps the prefix self-maintaining: a fourth harness added
     * there is covered without anyone editing this list, and a general test helper is not.
     */
    private static final List<String> DATABASE_SIGNATURE =
            List.of(
                    "java.sql.DriverManager",
                    "javax.sql.DataSource",
                    "org.testcontainers.",
                    // The shared harness: DatabaseRoles, SimulatedInstance, DatabaseUnderTest.
                    "com.finapp.platform.testing.database.");

    private static final String TAG_ANNOTATION = "org.junit.jupiter.api.Tag";

    private final String tag;
    private final String taskName;

    TestTier(String tag, String taskName) {
        this.tag = tag;
        this.taskName = taskName;
    }

    /** The JUnit tag selecting this tier; empty for the default tier, which carries none. */
    String tag() {
        return tag;
    }

    String taskName() {
        return taskName;
    }

    boolean isDefaultTier() {
        return tag.isEmpty();
    }

    /**
     * The task a CI job must invoke for this tier to run.
     *
     * <p>The hermetic tiers are all reached through {@code build}, because {@code build} depends
     * on {@code check} which depends on {@code test}, and {@code test} excludes only the tiers
     * needing external infrastructure. That chain is Gradle's own contract rather than something
     * this repository configures, which is why it is asserted here as a constant rather than
     * introspected — a test JVM cannot see the task graph that launched it.
     */
    String coveringCiTask() {
        return this == DATABASE ? taskName : "build";
    }

    /** The heavier of two tiers. */
    static TestTier heavier(TestTier left, TestTier right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    boolean atLeast(TestTier other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * The tier a class's own bytecode proves it needs.
     *
     * <p>Returns {@link #UNIT} when nothing is detected, which is a claim about what is
     * <em>visible</em> and not a claim that the class needs nothing — see the class javadoc.
     */
    static TestTier requiredBy(JavaClass testClass) {
        Set<String> referenced = referencedTypeNames(testClass);
        if (matches(referenced, DATABASE_SIGNATURE)) {
            return DATABASE;
        }
        if (matches(referenced, SLICE_SIGNATURE)) {
            return SLICE;
        }
        if (matches(referenced, ARCHITECTURE_SIGNATURE)) {
            return ARCHITECTURE;
        }
        return UNIT;
    }

    /**
     * The tier a class declares by its {@code @Tag} annotations.
     *
     * @throws IllegalStateException if it declares more than one, which would make the class's
     *     tier a function of which task happened to select it first
     */
    static TestTier declaredBy(JavaClass testClass) {
        Set<TestTier> declared = new LinkedHashSet<>();
        for (String value : tagValues(testClass)) {
            tierWithTag(value).ifPresent(declared::add);
        }
        if (declared.size() > 1) {
            throw new IllegalStateException(
                    testClass.getName() + " declares more than one tier: " + declared);
        }
        return declared.isEmpty() ? UNIT : declared.iterator().next();
    }

    /** Every {@code @Tag} value on the class, tier tags and others alike. */
    static Set<String> tagValues(JavaClass testClass) {
        Set<String> values = new LinkedHashSet<>();
        for (JavaAnnotation<?> annotation : testClass.getAnnotations()) {
            if (annotation.getRawType().getName().equals(TAG_ANNOTATION)) {
                annotation.get("value").ifPresent(value -> values.add(String.valueOf(value)));
            }
        }
        return values;
    }

    static Optional<TestTier> tierWithTag(String tag) {
        return Arrays.stream(values()).filter(tier -> tier.tag.equals(tag)).findFirst();
    }

    private static boolean matches(Set<String> referenced, List<String> signature) {
        return referenced.stream().anyMatch(name -> signature.stream().anyMatch(name::startsWith));
    }

    /**
     * Every type the class refers to, annotations included.
     *
     * <p>Annotations are added explicitly rather than relied on: {@code @SpringBootTest} is the
     * single strongest indicator that a class needs a Spring context, and a rule that depended on
     * ArchUnit happening to model annotation types as dependencies would be one release away from
     * silently detecting nothing.
     */
    private static Set<String> referencedTypeNames(JavaClass testClass) {
        Set<String> names = new LinkedHashSet<>();
        testClass.getDirectDependenciesFromSelf()
                .forEach(dependency -> names.add(dependency.getTargetClass().getName()));
        testClass.getAnnotations()
                .forEach(annotation -> names.add(annotation.getRawType().getName()));
        testClass.getMethods()
                .forEach(
                        method ->
                                method.getAnnotations()
                                        .forEach(
                                                annotation ->
                                                        names.add(
                                                                annotation
                                                                        .getRawType()
                                                                        .getName())));
        testClass.getFields()
                .forEach(field -> names.add(field.getRawType().getName()));
        return names;
    }
}
