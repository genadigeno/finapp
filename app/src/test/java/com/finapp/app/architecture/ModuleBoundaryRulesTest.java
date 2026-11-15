package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.LedgerReachProbes;
import com.finapp.ledger.AppendsInsideTheLedgerProbe;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.SqlStorePortProbe;
import com.finapp.ledger.SqlStoreProbe;
import com.finapp.ledger.TimestampConverterProbe;
import com.finapp.ledger.TrialBalance;
import com.finapp.ledger.internal.InternalTypeProbe;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Mechanical enforcement of the module boundaries in
 * {@code docs/architecture/MODULE_ARCHITECTURE.md} §6 (P0-TSK-007).
 *
 * <p><strong>Why these exist.</strong> Gradle enforces dependency <em>direction</em>: a module
 * sees only what its build file declares, and a reverse edge fails with a circular-dependency
 * error. Gradle cannot express the finer rules — that a module's internals are private (its
 * internal package and, since P15-REM-001, the classes that execute its SQL), that only the
 * ledger appends to the journal, that the shared kernel stays framework-free.
 * Those rested on review until this class existed, and a boundary rule that is documented but
 * unenforced decays within weeks.
 *
 * <p><strong>Package convention these rules assume.</strong> A module's root package is
 * {@code com.finapp.<module>}. Everything under {@code com.finapp.<module>.internal} is
 * private to that module, and so is every class of a business module that executes SQL
 * (P15-REM-001: no module has an internal package, so the package half alone read nothing);
 * everything else in the module root is its published surface.
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
 * <p><strong>Not here:</strong> {@code INV-MON-01} (no binary floating point) is enforced by
 * {@link NoFloatingPointMoneyRulesTest}. It is a financial invariant rather than a module
 * boundary, and it is scoped default-deny over every class rather than by module, so keeping it
 * separate stops two different scoping rationales sharing one file.
 */
@Tag("architecture")
// NOT a duplicate of the line above. ArchUnit runs @ArchTest fields under its OWN
// JUnit Platform engine, and that engine's descriptors read `ArchTag` - they cannot
// see JUnit's `Tag` at all. Without this every rule below carried no tag, so
// `unitTest` (which selects by EXCLUSION) took them and `architectureTest` (which
// selects by INCLUSION) got none. P1-TSK-025; TestTaxonomyTest now requires the pair.
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryRulesTest {

    private static final String ROOT = "com.finapp.";

    private static final String SHAREDKERNEL = "sharedkernel";
    private static final String PLATFORM = "platform";
    private static final String APP = "app";
    private static final String LEDGER = "ledger";

    /** The journal's write port; {@link #onlyTheLedgerAppendsToTheJournal} holds its {@code append} to the ledger. */
    private static final String JOURNAL_ENTRY_STORE = "com.finapp.ledger.JournalEntryStore";

    private static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";

    /** What marks a class as one that executes SQL - a module's adapter over its own tables: Spring's jdbc, */
    private static final String SPRING_JDBC = "org.springframework.jdbc.";

    /** ... any use of a statement or a result set, */
    private static final Set<String> STATEMENT_TYPES =
            Set.of("java.sql.Statement", "java.sql.PreparedStatement", "java.sql.CallableStatement", "java.sql.ResultSet");

    /** ... or a connection asked for a statement. */
    private static final Set<String> CONNECTION_STATEMENTS = Set.of("prepareStatement", "prepareCall", "createStatement");

    /** Packages a framework-free module may not touch. */
    private static final List<String> FRAMEWORK_PACKAGES =
            List.of(
                    "org.springframework.",
                    "jakarta.persistence.",
                    "javax.persistence.",
                    "jakarta.transaction.",
                    "org.hibernate.");

    // ---------------------------------------------------------------------
    // Guard: the analysis must actually see the code it claims to check.
    //
    // Every rule below is vacuously satisfied for a module whose classes were
    // never imported. A misconfigured importer, or a module dropped from app's
    // dependencies, would turn those rules into decoration that reports success
    // — worse than having no rules, because it invites confidence.
    // ---------------------------------------------------------------------

    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed =
                imported.stream()
                        .map(ProductionModules::of)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());

        assertThat(analysed)
                .as("modules visible to the architecture rules")
                .isNotEmpty();

        // Asserting only that *some* module was analysed is not enough: it would pass if a
        // module were dropped from the analysed classpath, which is precisely the silent
        // loss of coverage this guard exists to prevent. So the expected set is derived
        // from the classpath itself — every module output carrying at least one real class
        // must be represented in the import.
        assertThat(analysed)
                .as("every module on the classpath that has production classes must be analysed")
                .containsAll(ProductionModules.onClasspathWithProductionClasses());
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

    @ArchTest
    static final ArchRule productionClassesLiveInAModulePackage =
            everyFinappClassShould(resideInAModulePackage())
                    .because(
                            "every rule here is scoped by the module a class belongs to, derived "
                                + "from com.finapp.<module>. A class directly in com.finapp has no "
                                + "module and would be silently exempt from all of them");

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
    // Encapsulation: a module's internals are its own
    //
    // P15-REM-001 (ADR-0006's coupling review): this rule read only
    // com.finapp.<module>.internal, and no module has one - every module is a flat
    // package of public types - so it passed over empty data. It now also reads
    // what a module's private state actually is: the classes that execute its SQL.
    // A business module's class that executes a statement (prepares or uses a JDBC
    // statement or result set, or uses Spring's jdbc) reads or writes that module's
    // tables, so no other business module may name it;
    // the one exception is the composition root's wiring, app's @Configuration
    // classes, which must construct the adapter to hand its port to a caller.
    //
    // The entity rule that stood beside it read JPA @Entity, and there is no
    // object-relational mapper (ADR-0033): it guarded nothing and was retired
    // 2026-11-15 by P15-REM-001. What could cross in its place - a store - crosses
    // as its port, which is the published surface; its adapter is held here.
    // ---------------------------------------------------------------------

    @ArchTest
    static final ArchRule moduleInternalsArePrivateToTheirModule =
            everyFinappClassShould(notReachIntoAnotherModulesInternals())
                    .because(
                            "a module exposes a published interface; reaching past it couples a "
                                + "caller to a detail the owning module is free to change, which "
                                + "is how a modular monolith quietly becomes a monolith. A class that "
                                + "executes a module's SQL is that module's state: another module "
                                + "reaches it through its port, and only the composition root's "
                                + "wiring constructs it");

    // ---------------------------------------------------------------------
    // INV-LED-04's static half: only the ledger appends to the journal
    //
    // Until P15-REM-001 this rested on the dependency direction alone: every
    // module that depends on ledger could call JournalEntryStore.append outside
    // the ledger's command API (PostingService), and no rule would see it. The
    // database half - V004's triggers refusing an unbalanced or line-less entry
    // whoever writes it - is unchanged; this is the wall in front of it.
    // ---------------------------------------------------------------------

    @ArchTest
    static final ArchRule onlyTheLedgerAppendsToTheJournal =
            everyFinappClassShould(notAppendToTheJournalOutsideTheLedger())
                    .because(
                            "INV-LED-04: the ledger is the sole writer of postings. A journal entry "
                                + "appended outside the ledger's command API skips its validation, "
                                + "its idempotency scope and its balance projection");

    // ---------------------------------------------------------------------
    // Teeth (P15-REM-001; ADR-0006's verification rule: a rule never seen to fail
    // is not known to work). The probes are test classes declaring the packages of
    // ledger, accounts and app, so DoNotIncludeTests keeps them out of the sweep
    // above; these tests import them explicitly, beside the real classes they
    // reach, and run the very rules the sweep runs.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the internals rule refuses a reach into another module's internal package, a business module's reach into another's SQL,"
            + " and app's reach to a store adapter outside its wiring, a record nested in one included")
    void theInternalsRuleRefusesEachReach() {
        assertRejects(moduleInternalsArePrivateToTheirModule, LedgerReachProbes.ReachesLedgerInternals.class, InternalTypeProbe.class);
        assertRejects(moduleInternalsArePrivateToTheirModule, LedgerReachProbes.ReachesALedgerSqlService.class, TrialBalance.class);
        assertRejects(moduleInternalsArePrivateToTheirModule, CompositionRootReachProbes.NamesAStoreAdapterOutsideTheWiring.class,
                JdbcLedgerAccountStore.class);
        assertRejects(moduleInternalsArePrivateToTheirModule, CompositionRootReachProbes.NamesANestedTypeOfAStoreAdapter.class,
                SqlStoreProbe.class, SqlStoreProbe.Row.class, SqlStorePortProbe.class);
    }

    @Test
    @DisplayName("the internals rule permits what it means to permit: the wiring constructing an adapter, and app calling a module's published"
            + " service that runs its own statements")
    void theInternalsRulePermitsTheWiringAndAPublishedService() {
        assertPermits(moduleInternalsArePrivateToTheirModule, WiresAStoreAdapterProbe.class, JdbcLedgerAccountStore.class);
        assertPermits(moduleInternalsArePrivateToTheirModule, CompositionRootReachProbes.CallsAModulesSqlService.class, TrialBalance.class);
        // The gate's finding: touching java.sql is not executing SQL. A Timestamp converted, an SQLException's state read and
        // a connection asked whether it is read-only read no table, so a sibling module may name the class that does them.
        assertPermits(moduleInternalsArePrivateToTheirModule, LedgerReachProbes.NamesALedgerClassThatRunsNoStatement.class,
                TimestampConverterProbe.class);
    }

    @Test
    @DisplayName("INV-LED-04: the journal rule refuses an append outside the ledger - on the port, by a method reference, through the adapter -"
            + " and permits the ledger's own append and a read")
    void theJournalRuleRefusesAnAppendOutsideTheLedger() {
        assertRejects(onlyTheLedgerAppendsToTheJournal, LedgerReachProbes.AppendsToTheJournal.class, JournalEntryStore.class);
        assertRejects(onlyTheLedgerAppendsToTheJournal, LedgerReachProbes.AppendsByMethodReference.class, JournalEntryStore.class);
        assertRejects(onlyTheLedgerAppendsToTheJournal, CompositionRootReachProbes.AppendsThroughTheJournalAdapter.class,
                JdbcJournalEntryStore.class, JournalEntryStore.class);
        assertPermits(onlyTheLedgerAppendsToTheJournal, AppendsInsideTheLedgerProbe.class, JournalEntryStore.class);
        assertPermits(onlyTheLedgerAppendsToTheJournal, LedgerReachProbes.ReadsTheJournal.class, JournalEntryStore.class);
    }

    private static void assertRejects(ArchRule rule, Class<?> violation, Class<?>... beside) {
        JavaClasses imported = new ClassFileImporter().importClasses(with(violation, beside));
        assertThatThrownBy(() -> rule.check(imported))
                .as("%s must reject %s", rule.getDescription(), violation.getSimpleName())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violation.getSimpleName());
    }

    private static void assertPermits(ArchRule rule, Class<?> permitted, Class<?>... beside) {
        JavaClasses imported = new ClassFileImporter().importClasses(with(permitted, beside));
        assertThatCode(() -> rule.check(imported)).as("%s must permit %s", rule.getDescription(), permitted.getSimpleName())
                .doesNotThrowAnyException();
    }

    private static Class<?>[] with(Class<?> first, Class<?>... rest) {
        return Stream.concat(Stream.of(first), Stream.of(rest)).toArray(Class<?>[]::new);
    }

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

    /** Every production class must sit under {@code com.finapp.<module>}, never directly in {@code com.finapp}. */
    private static ArchCondition<JavaClass> resideInAModulePackage() {
        return new ArchCondition<>("reside in a module package under com.finapp") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (moduleOf(javaClass) == null) {
                    events.add(
                            SimpleConditionEvent.violated(
                                    javaClass,
                                    javaClass.getName() + " is in com.finapp directly, so it belongs to no module"));
                }
            }
        };
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
     * Forbids reaching another module's internals. Three readings, each over what the code actually is:
     * <ul>
     *   <li>anything under {@code com.finapp.<other>.internal}, from any module;
     *   <li><strong>between business modules</strong>, any class of the other module that executes SQL - with the classes
     *       nested in it. A business module reaches another through its ports, its values and its SQL-free services; a class
     *       that runs the other's statements is the other's state, whatever it is called;
     *   <li><strong>from {@code app}</strong>, a business module's store adapter - a class that executes SQL and implements an
     *       interface a business module declares, its port - except in the composition root's wiring, the
     *       {@code @Configuration} classes, which construct the adapter to hand its port to the code that uses it. A desk, a
     *       guard or a service in {@code app} reaches a store as the rest of the platform does, through its port. What a
     *       module publishes as a service, even one that runs its own statements ({@code TrialBalance},
     *       {@code TransferExecution}), is the module's API and {@code app}'s to call: the composition root is its caller.
     * </ul>
     * A module may freely use its own internals; that is what they are for.
     *
     * <p>The SQL classes are found, not listed: {@link ArchCondition#init} reads every analysed class first, so a new store is
     * private from the day it executes a statement, with no package to remember. {@code platform}'s JDBC classes are not held: the
     * outbox, the inbox, idempotency and audit are the infrastructure every module is built on, and the dependency direction
     * already confines who sees them.
     */
    private static ArchCondition<JavaClass> notReachIntoAnotherModulesInternals() {
        return new ArchCondition<>("not reach into another module's internals: its internal package, its SQL, or from app outside the wiring its store adapters") {
            private Set<String> executingSql = Set.of();
            private Set<String> storeAdapters = Set.of();

            @Override
            public void init(Collection<JavaClass> allObjectsToTest) {
                List<JavaClass> sql = allObjectsToTest.stream()
                        .filter(javaClass -> isBusinessModule(moduleOf(javaClass)))
                        .filter(ModuleBoundaryRulesTest::executesSql)
                        .toList();
                executingSql = sql.stream().map(javaClass -> topLevel(javaClass).getName()).collect(Collectors.toUnmodifiableSet());
                // The port may sit on the class that runs the statement or on the top-level class it is nested in (a row
                // mapper nested in its adapter); either way the top-level class is the adapter.
                storeAdapters = sql.stream()
                        .filter(javaClass -> implementsAPort(javaClass) || implementsAPort(topLevel(javaClass)))
                        .map(javaClass -> topLevel(javaClass).getName())
                        .collect(Collectors.toUnmodifiableSet());
            }

            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originModule = moduleOf(origin);
                boolean wiring = APP.equals(originModule) && isConfiguration(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    target = target.isArray() ? target.getBaseComponentType() : target;
                    String targetModule = moduleOf(target);
                    if (targetModule == null || targetModule.equals(originModule) || wiring) {
                        continue;
                    }
                    String owner = topLevel(target).getName();
                    if (isInternalTo(target, targetModule)) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()));
                    } else if (isBusinessModule(originModule) && executingSql.contains(owner)) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()
                                + " - " + owner + " executes " + targetModule + "'s SQL"));
                    } else if (APP.equals(originModule) && storeAdapters.contains(owner)) {
                        events.add(SimpleConditionEvent.violated(origin, dependency.getDescription()
                                + " - " + owner + " is " + targetModule + "'s store adapter; outside the wiring, use its port"));
                    }
                }
            }
        };
    }

    /**
     * Forbids a call to {@code JournalEntryStore.append} - on the port or on any implementation of it, by a call or by a
     * method reference - from any class outside {@code com.finapp.ledger}. The owner is read by name, so the rule needs
     * nothing of the ledger on the classpath that a planted probe would not bring.
     */
    private static ArchCondition<JavaClass> notAppendToTheJournalOutsideTheLedger() {
        return new ArchCondition<>("not append to the journal outside the ledger") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                if (LEDGER.equals(moduleOf(origin))) {
                    return;
                }
                Stream.concat(origin.getMethodCallsFromSelf().stream(), origin.getMethodReferencesFromSelf().stream())
                        .filter(access -> access.getName().equals("append"))
                        .filter(access -> access.getTargetOwner().isAssignableTo(JOURNAL_ENTRY_STORE))
                        .forEach(access -> events.add(SimpleConditionEvent.violated(origin, access.getDescription())));
            }
        };
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** The module a class belongs to, or {@code null} if it is not one of ours. */
    private static String moduleOf(JavaClass javaClass) {
        return ProductionModules.of(javaClass);
    }

    private static boolean isInternalTo(JavaClass javaClass, String module) {
        String internalRoot = ROOT + module + ".internal";
        String packageName = javaClass.getPackageName();
        return packageName.equals(internalRoot) || packageName.startsWith(internalRoot + ".");
    }

    /** A module that owns a bounded context's state: not the composition root, the infrastructure or the kernel. */
    private static boolean isBusinessModule(String module) {
        return module != null && !Set.of(APP, PLATFORM, SHAREDKERNEL).contains(module);
    }

    /**
     * The class's own code executes a statement: it prepares or creates one on a {@code Connection}, uses a statement or a result
     * set, or uses Spring's jdbc ({@code JdbcClient}, {@code JdbcTemplate}, {@code DataSourceUtils}). The gate's finding: reading
     * any {@code java.sql} access would have counted a class that only converts a {@code Timestamp}, maps an {@code SQLException}'s
     * state or asks a connection whether it is read-only - none of which reads or writes a table.
     */
    private static boolean executesSql(JavaClass javaClass) {
        return javaClass.getAccessesFromSelf().stream().anyMatch(access -> {
            String owner = access.getTargetOwner().getName();
            return owner.startsWith(SPRING_JDBC) || STATEMENT_TYPES.contains(owner)
                    || (owner.equals("java.sql.Connection") && CONNECTION_STATEMENTS.contains(access.getName()));
        });
    }

    /** The class implements, directly or through a supertype, an interface a business module declares: a port. */
    private static boolean implementsAPort(JavaClass javaClass) {
        return javaClass.getAllRawInterfaces().stream().anyMatch(port -> isBusinessModule(moduleOf(port)));
    }

    /** A nested class is its top-level class's: a record nested in an adapter is the adapter's, whatever it calls. */
    private static JavaClass topLevel(JavaClass javaClass) {
        JavaClass top = javaClass;
        while (top.getEnclosingClass().isPresent()) {
            top = top.getEnclosingClass().get();
        }
        return top;
    }

    /** The class, or the top-level class it is nested in, is a Spring configuration: the composition root's wiring. */
    private static boolean isConfiguration(JavaClass javaClass) {
        JavaClass top = topLevel(javaClass);
        return top.isAnnotatedWith(CONFIGURATION) || top.isMetaAnnotatedWith(CONFIGURATION);
    }
}
