package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AmbientSchedulingProbe;
import com.finapp.ledger.ProcessLocalLockProbe;
import com.finapp.ledger.StaticMutableStateProbe;
import com.finapp.ledger.SynchronizedMethodProbe;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correctness never rests on there being one of us (ADR-0014, {@code DISTRIBUTED_EXECUTION.md}).
 *
 * <h2>Why this is a build failure and not a design rule</h2>
 *
 * <p>ADR-0014 says every service runs as N concurrent instances and N is never 1. That has been a
 * written rule since {@code P0-TSK-016}, and written rules decay — which is not speculation here:
 * the audit that produced ADR-0014 found a <strong>real defect</strong> in code that had already
 * passed review. {@code IdempotentExecutor} compared a lease timestamp written by one instance's
 * clock against a bound computed from another's, so an instance running six minutes fast would
 * consider a neighbour's fresh claim abandoned and execute a money-moving command the neighbour
 * was still executing. It passed every test, because every test ran in one JVM with one clock.
 *
 * <p>The patterns below are the mechanically detectable half of that assumption. They do not make
 * a design multi-instance correct — no rule can — but each one is a construct that <em>only</em>
 * means something within a single process, so its presence is a claim about coordination that is
 * false the moment a second instance starts.
 *
 * <h2>What each rule catches, and why it is worse than no lock at all</h2>
 *
 * <ul>
 *   <li><strong>{@code synchronized}</strong>, method or block, and <strong>process-local
 *       locks</strong> ({@code ReentrantLock}, {@code Semaphore}, latches, barriers). A lock held
 *       in one JVM says nothing to the other nine. The code then reads as though the race was
 *       handled, which is why it survives review.
 *   <li><strong>Static mutable state.</strong> A cache, counter or registry in a static field is
 *       per-instance, so each replica has a different answer and none of them is authoritative
 *       ({@code CLAUDE.md} rule 12).
 *   <li><strong>Ambient scheduling.</strong> Every instance runs the scheduler, so a job with no
 *       lease runs N times. `DISTRIBUTED_EXECUTION.md` §5 requires a scheduled job to be either
 *       idempotent or leased, and a bare {@code ScheduledExecutorService} declares neither.
 * </ul>
 *
 * <h2>The one rule that is not an ArchUnit rule</h2>
 *
 * <p>{@code synchronized} <em>blocks</em> are invisible to ArchUnit, which models accesses rather
 * than instructions — verified by probe, where the block method reported no modifiers at all. That
 * check reads bytecode directly; see {@link MonitorInstructions}.
 */
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class NoSingleInstanceAssumptionRulesTest {

    /**
     * The non-authoritative process-local uses, from {@code DISTRIBUTED_EXECUTION.md} §3.
     *
     * <p>Each is a {@code ThreadLocal} carrying per-flow context within one instance. They are
     * permitted because they coordinate <strong>nothing</strong>: each instance carries its own,
     * losing one costs traceability or refuses an operation, and neither outcome can make two
     * instances disagree about a financial fact. That is the test an exemption has to pass — not
     * "it is convenient" but "it cannot affect correctness".
     *
     * <p>Named individually rather than by package or by type. A type-wide exemption for
     * {@code ThreadLocal} would admit the next one without anyone deciding, and the register in
     * §3 is the thing that is supposed to force the decision.
     */
    private static final Set<String> PERMITTED_PROCESS_LOCAL_STATE =
            Set.of(
                    "com.finapp.platform.correlation.CorrelationContext.CURRENT",
                    "com.finapp.platform.security.SecurityContext.CURRENT");

    /** Types whose only purpose is coordination inside one JVM. */
    private static final Set<String> PROCESS_LOCAL_LOCKS =
            Set.of(
                    "java.util.concurrent.locks.Lock",
                    "java.util.concurrent.locks.ReadWriteLock",
                    "java.util.concurrent.locks.ReentrantLock",
                    "java.util.concurrent.locks.ReentrantReadWriteLock",
                    "java.util.concurrent.locks.StampedLock",
                    "java.util.concurrent.Semaphore",
                    "java.util.concurrent.CountDownLatch",
                    "java.util.concurrent.CyclicBarrier",
                    "java.util.concurrent.Phaser",
                    "java.util.concurrent.Exchanger");

    /** Types that schedule work with no lease, so every instance runs it. */
    private static final Set<String> AMBIENT_SCHEDULERS =
            Set.of(
                    "java.util.concurrent.ScheduledExecutorService",
                    "java.util.concurrent.ScheduledThreadPoolExecutor",
                    "java.util.Timer",
                    "java.util.TimerTask",
                    "org.springframework.scheduling.annotation.Scheduled",
                    "org.springframework.scheduling.TaskScheduler");

    /**
     * Field types that hold mutable state by construction.
     *
     * <p>Concrete types rather than interfaces, deliberately: {@code static final Set<String> X =
     * Set.of(...)} is declared as {@code Set} and is immutable, and flagging it would be a false
     * positive on a constant — the kind that gets a rule turned off (ADR-0019). A mutable
     * collection assigned to an interface-typed static field is caught by the constructor rule
     * below instead.
     */
    private static final Set<String> MUTABLE_STATE_TYPES =
            Set.of(
                    "java.util.HashMap",
                    "java.util.LinkedHashMap",
                    "java.util.TreeMap",
                    "java.util.concurrent.ConcurrentHashMap",
                    "java.util.concurrent.ConcurrentSkipListMap",
                    "java.util.ArrayList",
                    "java.util.LinkedList",
                    "java.util.HashSet",
                    "java.util.LinkedHashSet",
                    "java.util.TreeSet",
                    "java.util.concurrent.CopyOnWriteArrayList",
                    "java.util.concurrent.CopyOnWriteArraySet",
                    "java.util.concurrent.ConcurrentLinkedQueue",
                    "java.util.concurrent.ConcurrentLinkedDeque",
                    "java.util.concurrent.LinkedBlockingQueue",
                    "java.util.concurrent.atomic.AtomicInteger",
                    "java.util.concurrent.atomic.AtomicLong",
                    "java.util.concurrent.atomic.AtomicBoolean",
                    "java.util.concurrent.atomic.AtomicReference",
                    "java.util.concurrent.atomic.LongAdder",
                    "java.util.concurrent.atomic.DoubleAdder");

    /**
     * {@code noMethods()}, not {@code noClasses()}.
     *
     * <p>The first version was {@code noClasses().should().haveModifier(SYNCHRONIZED)}, which
     * checks the modifiers of the <strong>class</strong> - and a class cannot be synchronized, so
     * the rule was incapable of firing. Caught by its own teeth test on the first run. It is the
     * same defect {@code P0-TST-008} found in {@code secretsAreWrapped}: a rule aimed at the wrong
     * target reads correctly, passes, and protects nothing.
     */
    @ArchTest
    static final ArchRule noMethodIsSynchronized =
            noMethods()
                    .should()
                    .haveModifier(JavaModifier.SYNCHRONIZED)
                    .because(
                            "a monitor is held inside one JVM, so with N instances the invariant it"
                                + " appears to protect is protected in none of them - and the code"
                                + " reads as though the race was handled (ADR-0014)");

    /**
     * {@code classes()}, NOT {@code noClasses()}.
     *
     * <p>The first version used {@code noClasses().should(condition)} and was <strong>incapable of
     * failing</strong>. That form inverts a custom condition's events - it reports as violations
     * the things the condition marks <em>satisfied</em> - and this condition only ever emits
     * {@code violated(...)}, so the inversion left it nothing to report.
     *
     * <p>This is the identical defect {@code P0-TST-008} found in {@code secretsAreWrapped} and
     * wrote up at length, reproduced one task later by the person who wrote it up. The teeth tests
     * are the only reason it did not ship a second time.
     */
    @ArchTest
    static final ArchRule nothingUsesAProcessLocalLock =
            classes()
                    .should(useAnyOf(PROCESS_LOCAL_LOCKS, "a process-local lock"))
                    .because(
                            "these coordinate threads within one process and say nothing to the"
                                + " other instances. Coordination that must hold across instances"
                                + " belongs in the database - an advisory lock, a unique"
                                + " constraint, or a conditional UPDATE (DISTRIBUTED_EXECUTION.md"
                                + " §5)");

    /** {@code classes()}, not {@code noClasses()} - see {@link #nothingUsesAProcessLocalLock}. */
    @ArchTest
    static final ArchRule nothingSchedulesAmbiently =
            classes()
                    .should(useAnyOf(AMBIENT_SCHEDULERS, "an ambient scheduler"))
                    .because(
                            "every instance runs the scheduler, so a job with no lease runs N times."
                                + " A scheduled financial process must be idempotent per period"
                                + " (INV-IDEM-02) or take an explicit database lease, and a bare"
                                + " scheduler declares neither");

    @ArchTest
    static final ArchRule noStaticMutableState =
            classes()
                    .should(notHoldStaticMutableState())
                    .because(
                            "a cache, counter or registry in a static field is per-instance, so"
                                + " every replica has a different answer and none is authoritative"
                                + " (CLAUDE.md rule 12). Authoritative state lives in the database");

    /**
     * The coverage guard, for the reason every rule suite here has one: a rule that sees nothing
     * passes, and reports safety it never checked.
     */
    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed = new TreeSet<>();
        imported.stream().map(ProductionModules::of).filter(Objects::nonNull).forEach(analysed::add);

        assertThat(analysed)
                .as("the rules must see every module with production code, or they protect only some")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());
    }

    // ---------------------------------------------------------------------------------------
    // The rule ArchUnit cannot express.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("no production method enters a monitor - the synchronized block ArchUnit cannot see")
    void noSynchronizedBlocks() {
        assertThat(MonitorInstructions.inProductionCode())
                .as(
                        "a synchronized block is a MONITORENTER instruction rather than an access"
                            + " flag, so ArchUnit is blind to it - the block method reports no"
                            + " modifiers at all. It takes a lock in one JVM exactly as a"
                            + " synchronized method does (ADR-0014, ADR-0024)")
                .isEmpty();
    }

    @Test
    @DisplayName("the bytecode sweep reaches every module with production code")
    void theBytecodeSweepCoversEveryModule() {
        // Per MODULE, not by a count - and that distinction was earned. The first version asserted
        // "more than 100 methods scanned", which passed while the sweep was reading `app`'s classes
        // and nothing else: a consumed module arrives on the runtime classpath as a JAR, and the
        // sweep only walked directories. A synchronized block planted in `platform` was not caught,
        // and the count guard saw nothing wrong because `app` alone has plenty of methods.
        //
        // Derived from the classpath by the same helper the ArchUnit coverage guard uses, so the
        // two cannot disagree about what production code is.
        assertThat(MonitorInstructions.modulesScanned())
                .as("a module the sweep never reads is a module where a synchronized block is legal")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());
    }

    // ---------------------------------------------------------------------------------------
    // Teeth.
    // ---------------------------------------------------------------------------------------

    // Four tests rather than one, so a failure names the rule that failed. The combined version
    // stopped at the first assertion and said nothing about the other three.

    @Test
    @DisplayName("the synchronized-method rule rejects a synchronized method")
    void synchronizedMethodsAreRejected() {
        assertRejects(noMethodIsSynchronized, SynchronizedMethodProbe.class);
    }

    @Test
    @DisplayName("the lock rule rejects a ReentrantLock")
    void processLocalLocksAreRejected() {
        assertRejects(nothingUsesAProcessLocalLock, ProcessLocalLockProbe.class);
    }

    @Test
    @DisplayName("the scheduling rule rejects a ScheduledExecutorService")
    void ambientSchedulingIsRejected() {
        assertRejects(nothingSchedulesAmbiently, AmbientSchedulingProbe.class);
    }

    @Test
    @DisplayName("the static-state rule rejects a static ConcurrentHashMap")
    void staticMutableStateIsRejected() {
        assertRejects(noStaticMutableState, StaticMutableStateProbe.class);
    }

    @Test
    @DisplayName("and accepts the documented non-authoritative uses")
    void thePermittedUsesAreAccepted() {
        // The exemptions are real rather than decorative: both classes genuinely hold a static
        // ThreadLocal, so a rule without the exemption would fire on them. Asserted below.
        JavaClasses permitted =
                new ClassFileImporter()
                        .importClasses(
                                com.finapp.platform.correlation.CorrelationContext.class,
                                com.finapp.platform.security.SecurityContext.class);

        assertThatCode(() -> noStaticMutableState.check(permitted)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the exemptions are load-bearing, not decorative")
    void theExemptionsAreReal() {
        // An exemption for a class that holds no static state would pass while protecting nothing.
        // The same rule with an empty exemption set must fire on both, or the register in
        // DISTRIBUTED_EXECUTION.md §3 is describing something that could not have been flagged.
        ArchRule withoutExemptions =
                classes().should(staticStateCondition(Set.of())).because("probe");

        for (Class<?> exempted :
                new Class<?>[] {
                    com.finapp.platform.correlation.CorrelationContext.class,
                    com.finapp.platform.security.SecurityContext.class
                }) {
            JavaClasses one = new ClassFileImporter().importClasses(exempted);
            assertThatThrownBy(() -> withoutExemptions.check(one))
                    .as("%s must really hold static state, or its exemption is empty", exempted)
                    .isInstanceOf(AssertionError.class);
        }
    }

    private static void assertRejects(ArchRule rule, Class<?> violation) {
        JavaClasses violating = new ClassFileImporter().importClasses(violation);

        assertThatThrownBy(() -> rule.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violation.getSimpleName());
    }

    // ---------------------------------------------------------------------------------------
    // Conditions.
    // ---------------------------------------------------------------------------------------

    /**
     * Any dependency on one of {@code types}: a field, a local, a signature, a call or an
     * annotation.
     *
     * <p>{@code getDirectDependenciesFromSelf} rather than a field check, and the difference is
     * load-bearing: probing found it catches a {@code ReentrantLock} used only as a local variable
     * and a {@code @Scheduled} annotation, which a field-typed rule would both miss - and
     * {@code @Scheduled} is how a Spring developer would actually introduce ambient scheduling.
     */
    private static ArchCondition<JavaClass> useAnyOf(Set<String> types, String what) {
        return new ArchCondition<>("not use " + what) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> types.contains(dependency.getTargetClass().getFullName()))
                        .forEach(
                                dependency ->
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        item,
                                                        item.getName()
                                                                + " uses "
                                                                + dependency
                                                                        .getTargetClass()
                                                                        .getName()
                                                                + " ("
                                                                + what
                                                                + "): "
                                                                + dependency.getDescription())));
            }
        };
    }

    private static ArchCondition<JavaClass> notHoldStaticMutableState() {
        return staticStateCondition(PERMITTED_PROCESS_LOCAL_STATE);
    }

    /**
     * Static state, in the two shapes that matter.
     *
     * <p>A <strong>non-final</strong> static field is mutable whatever its type. A
     * <strong>final</strong> one is mutable when its type is, which is why the check is against
     * concrete types rather than interfaces — a {@code Set.of(...)} constant is declared as
     * {@code Set} and must not be flagged.
     */
    private static ArchCondition<JavaClass> staticStateCondition(Set<String> exempt) {
        return new ArchCondition<>("not hold static mutable state") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaField field : item.getFields()) {
                    if (!field.getModifiers().contains(JavaModifier.STATIC)) {
                        continue;
                    }
                    // An enum's `$VALUES` is a static final array the compiler generates, and it is
                    // the reason arrays cannot simply be flagged. Excluding synthetic fields
                    // removes it without weakening anything a person wrote.
                    if (field.getModifiers().contains(JavaModifier.SYNTHETIC)) {
                        continue;
                    }
                    String name = item.getFullName() + "." + field.getName();
                    if (exempt.contains(name)) {
                        continue;
                    }
                    String type = field.getRawType().getFullName();
                    // Arrays are mutable however final the reference is, and a static one is
                    // per-instance shared state exactly as a HashMap would be. Found by probing:
                    // `private static final String[] CACHE = {...}` went straight through the
                    // first version of this rule.
                    boolean mutableByType =
                            MUTABLE_STATE_TYPES.contains(type)
                                    || field.getRawType().isArray()
                                    || "java.lang.ThreadLocal".equals(type);
                    boolean mutableByReference = !field.getModifiers().contains(JavaModifier.FINAL);
                    if (mutableByType || mutableByReference) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        item,
                                        name
                                                + " is static "
                                                + (mutableByReference ? "and not final" : type)
                                                + ", so each instance has its own copy and none of"
                                                + " them is authoritative (ADR-0014)"));
                    }
                }

                // The declared type is not the whole story. `static final Map<K,V> CACHE = new
                // ConcurrentHashMap<>()` declares `java.util.Map`, which is also the type of an
                // immutable `Map.of(...)` constant - so the field check above cannot tell them
                // apart and must not try, or every constant becomes a violation.
                //
                // What distinguishes them is the CONSTRUCTION. A static initialiser that builds a
                // mutable collection has built one, whatever the field says. This was described in
                // this class's own javadoc as "caught by the constructor rule below" before the
                // constructor rule existed - found because the probe fixture used exactly that
                // shape and was not rejected.
                item.getConstructorCallsFromSelf().stream()
                        .filter(call -> "<clinit>".equals(call.getOrigin().getName()))
                        .filter(
                                call ->
                                        MUTABLE_STATE_TYPES.contains(
                                                call.getTargetOwner().getFullName()))
                        .filter(call -> !exempt.contains(item.getFullName() + ".<clinit>"))
                        .forEach(
                                call ->
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        item,
                                                        item.getFullName()
                                                                + " builds a "
                                                                + call.getTargetOwner().getName()
                                                                + " in its static initialiser, so"
                                                                + " the state is per-instance"
                                                                + " whatever the field is declared"
                                                                + " as (ADR-0014)")));
            }
        };
    }
}
