package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;

/**
 * Mechanical enforcement of the module boundaries in
 * {@code docs/architecture/MODULE_ARCHITECTURE.md} §6 (P0-TSK-007).
 *
 * <p><strong>Why these exist.</strong> Gradle enforces dependency <em>direction</em>: a module
 * sees only what its build file declares, and a reverse edge fails with a circular-dependency
 * error. Gradle cannot express the finer rules — that a module's internals are private, that
 * an entity is not referenced across a boundary, that the shared kernel stays framework-free.
 * Those rested on review until this class existed, and a boundary rule that is documented but
 * unenforced decays within weeks.
 *
 * <p><strong>Package convention these rules assume.</strong> A module's root package is
 * {@code com.finapp.<module>}. Everything under {@code com.finapp.<module>.internal} is
 * private to that module; everything else in the module root is its published surface.
 *
 * <p><strong>Why every rule is phrased as a condition over all {@code com.finapp} classes</strong>
 * rather than the more natural {@code noClasses().that().resideInAPackage("...sharedkernel..")}:
 * ArchUnit fails a rule whose {@code that()} clause matches nothing, and most modules have no
 * production code yet. Suppressing that with {@code allowEmptyShould(true)} would make the
 * rules pass for the wrong reason and keep passing if they later stopped matching. Phrased
 * this way each rule always evaluates against whatever code exists, and gains reach as modules
 * are filled in, with no edit here.
 *
 * <p><strong>Scope.</strong> Only production classes are analysed
 * ({@link ImportOption.DoNotIncludeTests}) — a test may legitimately reach into internals to
 * verify them, and the rules describe the shipped architecture, not the test scaffolding.
 *
 * <p><strong>Not here:</strong> the no-floating-point-money rule is P0-TSK-008 and
 * additionally depends on {@code Money} existing (P0-TSK-009).
 */
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryRulesTest {

    private static final String ROOT = "com.finapp.";

    private static final String SHAREDKERNEL = "sharedkernel";
    private static final String PLATFORM = "platform";
    private static final String APP = "app";

    /** Packages a framework-free module may not touch. */
    private static final List<String> FRAMEWORK_PACKAGES =
            List.of(
                    "org.springframework.",
                    "jakarta.persistence.",
                    "javax.persistence.",
                    "jakarta.transaction.",
                    "org.hibernate.");

    // ---------------------------------------------------------------------
    // Guard: the analysis must actually see production code.
    //
    // Every rule below is vacuously satisfied if nothing was imported. A
    // misconfigured importer would turn this whole class into decoration that
    // reports success — which is worse than having no rules, because it invites
    // confidence. This asserts the suite has something real to work on.
    // ---------------------------------------------------------------------

    @ArchTest
    static void analysisSeesProductionClasses(JavaClasses imported) {
        assertThat(imported)
                .as("production classes under com.finapp visible to the architecture rules")
                .isNotEmpty();

        Set<String> modulesSeen =
                imported.stream()
                        .map(ModuleBoundaryRulesTest::moduleOf)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertThat(modulesSeen)
                .as("the composition root must be among the analysed modules")
                .contains(APP);
    }

    // ---------------------------------------------------------------------
    // Dependency direction: app -> business modules -> platform -> sharedkernel
    //
    // Gradle already makes these structurally impossible today. They are kept as
    // defence in depth: Gradle's guarantee lasts exactly as long as nobody "fixes"
    // a build file, and the cost of being wrong is that the ledger stops being
    // extractable and reasonable about in isolation.
    // ---------------------------------------------------------------------

    @ArchTest
    static final ArchRule sharedkernelDependsOnNoOtherModule =
            everyFinappClassShould(moduleMayOnlyDependOn(SHAREDKERNEL, SHAREDKERNEL))
                    .because(
                            "sharedkernel is the bottom of the dependency graph; a dependency "
                                + "upward would make the financial kernel untestable in isolation "
                                + "and would create a cycle");

    @ArchTest
    static final ArchRule platformDependsOnlyOnSharedkernel =
            everyFinappClassShould(moduleMayOnlyDependOn(PLATFORM, PLATFORM, SHAREDKERNEL))
                    .because(
                            "platform is the module every other module may depend on, so anything "
                                + "it depends on is transitively coupled to everything");

    @ArchTest
    static final ArchRule nothingDependsOnApp =
            everyFinappClassShould(notDependOnModule(APP))
                    .because(
                            "app is the composition root: it may depend on every module and no "
                                + "module may depend on it");

    // ---------------------------------------------------------------------
    // Framework isolation
    // ---------------------------------------------------------------------

    @ArchTest
    static final ArchRule sharedkernelIsFrameworkFree =
            everyFinappClassShould(moduleUsesNoFramework(SHAREDKERNEL))
                    .because(
                            "sharedkernel holds Money, currency and rounding. Keeping it free of "
                                + "any framework is what lets the financial kernel be unit-tested "
                                + "without a container, and stops a persistence concern from "
                                + "shaping a monetary type");

    // ---------------------------------------------------------------------
    // Encapsulation: a module's internals and entities are its own
    // ---------------------------------------------------------------------

    @ArchTest
    static final ArchRule moduleInternalsArePrivateToTheirModule =
            everyFinappClassShould(notReachIntoAnotherModulesInternals())
                    .because(
                            "a module exposes a published interface; reaching past it couples a "
                                + "caller to a detail the owning module is free to change, which "
                                + "is how a modular monolith quietly becomes a monolith");

    @ArchTest
    static final ArchRule entitiesAreNotReferencedAcrossModules =
            everyFinappClassShould(notReferenceAnotherModulesEntities())
                    .because(
                            "cross-context references are typed identifiers, not object graphs. An "
                                + "entity reference across a boundary creates a shared persistence "
                                + "model and, with it, a second owner for someone else's state");

    // ---------------------------------------------------------------------
    // Conditions
    // ---------------------------------------------------------------------

    /**
     * Applies a condition to every production class in the platform. See the class javadoc for
     * why every rule is scoped this way rather than by the module the rule is about.
     */
    private static ArchRule everyFinappClassShould(ArchCondition<JavaClass> condition) {
        return classes().that().resideInAPackage("com.finapp..").should(condition);
    }

    /** Within {@code com.finapp}, {@code module} may depend only on {@code permitted} modules. */
    private static ArchCondition<JavaClass> moduleMayOnlyDependOn(
            String module, String... permitted) {
        String description =
                "keep " + module + " depending only on " + String.join(" and ", permitted);
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                if (!module.equals(moduleOf(origin))) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetModule = moduleOf(dependency.getTargetClass());
                    if (targetModule == null || List.of(permitted).contains(targetModule)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                }
            }
        };
    }

    /** No class outside {@code module} may depend on anything inside it. */
    private static ArchCondition<JavaClass> notDependOnModule(String module) {
        return new ArchCondition<>("not depend on the " + module + " module") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                if (module.equals(moduleOf(origin))) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    if (module.equals(moduleOf(dependency.getTargetClass()))) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** {@code module} may not depend on any framework package. */
    private static ArchCondition<JavaClass> moduleUsesNoFramework(String module) {
        return new ArchCondition<>("keep " + module + " free of framework dependencies") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                if (!module.equals(moduleOf(origin))) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String target = dependency.getTargetClass().getFullName();
                    if (FRAMEWORK_PACKAGES.stream().anyMatch(target::startsWith)) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Forbids reaching into {@code com.finapp.<other>.internal}. A module may freely use its
     * own internals; that is what they are for.
     */
    private static ArchCondition<JavaClass> notReachIntoAnotherModulesInternals() {
        return new ArchCondition<>("not reach into another module's internal package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetModule = moduleOf(target);
                    if (targetModule == null || targetModule.equals(originModule)) {
                        continue;
                    }
                    if (isInternalTo(target, targetModule)) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Forbids depending on a persistence entity owned by another module.
     *
     * <p>The annotation is matched by name rather than by type: JPA is deliberately absent from
     * the classpath of modules that persist nothing, and a rule that only works once someone
     * adds that dependency is a rule that is missing exactly when it is first needed.
     */
    private static ArchCondition<JavaClass> notReferenceAnotherModulesEntities() {
        return new ArchCondition<>("not reference another module's persistence entities") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetModule = moduleOf(target);
                    if (targetModule == null || targetModule.equals(originModule)) {
                        continue;
                    }
                    if (isPersistenceEntity(target)) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    }
                }
            }
        };
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** The module a class belongs to, or {@code null} if it is not one of ours. */
    private static String moduleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(ROOT)) {
            return null;
        }
        String remainder = packageName.substring(ROOT.length());
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }

    private static boolean isInternalTo(JavaClass javaClass, String module) {
        String internalRoot = ROOT + module + ".internal";
        String packageName = javaClass.getPackageName();
        return packageName.equals(internalRoot) || packageName.startsWith(internalRoot + ".");
    }

    private static boolean isPersistenceEntity(JavaClass javaClass) {
        return javaClass.isAnnotatedWith("jakarta.persistence.Entity")
                || javaClass.isAnnotatedWith("javax.persistence.Entity");
    }
}
