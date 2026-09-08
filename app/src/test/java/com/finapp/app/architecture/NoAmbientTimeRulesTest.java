package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.SystemClockProbe;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Time is injected, never read from the environment (P0-TSK-013, ADR-0006).
 *
 * <p><strong>Why this is a build failure and not a review note.</strong> Accrual, fee
 * assessment, period close, value dating, hold expiry, settlement ageing and idempotency-key
 * expiry are all behaviours whose entire content is what happens as time passes. A component
 * that reads {@code Instant.now()} cannot be tested at a boundary — you cannot put it just
 * before midnight, just after a rate expires, or on the last day of a closing period — and it
 * cannot be replayed, so a decision it made yesterday cannot be reproduced tomorrow
 * ({@code INV-CRD-01}, {@code INV-ACC-04}). The defect is not that the code is wrong; it is
 * that nothing can ever demonstrate whether it is.
 *
 * <p><strong>Two rules, because there are two different things to control.</strong>
 *
 * <ul>
 *   <li>{@link #noAmbientTimeIsRead} forbids reading the environment's time directly —
 *       {@code Instant.now()}, {@code System.currentTimeMillis()}, {@code new Date()}. These
 *       cannot be substituted by any means; there is no seam. Forbidden everywhere, with no
 *       exemption.
 *   <li>{@link #onlyTheCompositionRootBuildsASystemClock} allows {@code Clock.systemUTC()} in
 *       {@code app} alone. A system clock has to be constructed somewhere or nothing can be
 *       injected; the composition root is the one place whose job that is. Anywhere else it is
 *       the same defect wearing the abstraction's clothes.
 * </ul>
 *
 * <p><strong>What is deliberately allowed.</strong> {@code Instant.now(clock)} and
 * {@code LocalDate.now(clock)} take a clock and are the idiomatic call — the rule matches on
 * the zero-argument overloads only. Getting that wrong would push people away from the correct
 * API, which is how a well-meant rule makes a codebase worse.
 *
 * <p><strong>What no rule can check.</strong> An injected clock still has to be the
 * <em>right</em> clock, and system time is not a business date. A posting date, a value date
 * and the instant a request arrived are three different things; substituting one for another
 * is a domain error that reads perfectly. See {@code DOMAIN_MODEL.md} §Time.
 */
@Tag("architecture")
// NOT a duplicate of the line above. ArchUnit runs @ArchTest fields under its OWN
// JUnit Platform engine, and that engine's descriptors read `ArchTag` - they cannot
// see JUnit's `Tag` at all. Without this every rule below carried no tag, so
// `unitTest` (which selects by EXCLUSION) took them and `architectureTest` (which
// selects by INCLUSION) got none. P1-TSK-025; TestTaxonomyTest now requires the pair.
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class NoAmbientTimeRulesTest {

    /** Types whose zero-argument {@code now()} reads the environment. */
    private static final Set<String> TIME_TYPES =
            Set.of(
                    "java.time.Instant",
                    "java.time.LocalDate",
                    "java.time.LocalDateTime",
                    "java.time.LocalTime",
                    "java.time.OffsetDateTime",
                    "java.time.OffsetTime",
                    "java.time.ZonedDateTime",
                    "java.time.Year",
                    "java.time.YearMonth",
                    "java.time.MonthDay",
                    "java.time.chrono.HijrahDate",
                    "java.time.chrono.JapaneseDate",
                    "java.time.chrono.MinguoDate",
                    "java.time.chrono.ThaiBuddhistDate");

    /**
     * Other ways to reach the environment, none of them substitutable.
     *
     * <p>The zone entries are here for the same reason as the clock ones. Which <em>date</em>
     * an instant falls on depends on the zone it is read in, so
     * {@code instant.atZone(ZoneId.systemDefault())} makes a payment cut-off, a period boundary
     * or a value date depend on how the server happens to be configured. That is a dating
     * defect that no amount of clock injection prevents, and it moves when the deployment does.
     *
     * <p>An earlier version of this set also listed
     * {@code TemporalAdjusters.firstDayOfNextMonth}. That was a mistake found in this task's own
     * review: the adjuster is applied to a date the caller already holds and reads nothing at
     * all. Forbidding it would have pushed people off a correct, deterministic API — precisely
     * the failure this rule's javadoc warns about.
     */
    private static final Set<String> AMBIENT_CALLS =
            Set.of(
                    "java.lang.System.currentTimeMillis",
                    "java.lang.System.nanoTime",
                    "java.util.Calendar.getInstance",
                    "java.time.ZoneId.systemDefault",
                    "java.util.TimeZone.getDefault");

    /**
     * Constructors that capture the current time.
     *
     * <p>{@code java.sql.Timestamp} was listed here and removed: it has no no-argument
     * constructor, so the entry could never match and was coverage that looked real.
     */
    private static final Set<String> AMBIENT_CONSTRUCTORS =
            Set.of("java.util.Date", "java.util.GregorianCalendar");

    /**
     * {@link Clock} factories that read the environment. {@code Clock.fixed} and
     * {@code Clock.offset} take their time from an argument, so they are not here.
     */
    private static final Set<String> SYSTEM_CLOCK_FACTORIES =
            Set.of("systemUTC", "systemDefaultZone", "system", "tickMillis", "tickSeconds", "tickMinutes");

    /** The composition root: the one module whose job is deciding where time comes from. */
    private static final String COMPOSITION_ROOT = "app";

    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed =
                imported.stream()
                        .map(ProductionModules::of)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());

        assertThat(analysed)
                .as("every module with production classes must be within reach of the time rules")
                .containsAll(ProductionModules.onClasspathWithProductionClasses());
    }

    @ArchTest
    static final ArchRule noAmbientTimeIsRead =
            everyProductionClassShould(readNoAmbientTime())
                    .because(
                            "a component that reads the environment's clock cannot be placed at "
                                + "a boundary by a test and cannot be replayed, so nothing can "
                                + "ever demonstrate whether its dating is correct");

    @ArchTest
    static final ArchRule onlyTheCompositionRootBuildsASystemClock =
            everyProductionClassShould(buildNoSystemClockOutside(COMPOSITION_ROOT))
                    .because(
                            "a system clock must be constructed somewhere or nothing can be "
                                + "injected, and the composition root is the one place whose job "
                                + "that is; anywhere else it is ambient time with an abstraction "
                                + "wrapped round it");

    // ---------------------------------------------------------------------
    // Teeth. Fixtures are test classes, so DoNotIncludeTests keeps them out of
    // the sweep above; the tests below import them explicitly.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("each rule rejects the violation it exists to catch")
    void rulesRejectTheirViolations() {
        assertRejects(noAmbientTimeIsRead, ReadsInstantNow.class);
        assertRejects(noAmbientTimeIsRead, ReadsLocalDateNow.class);
        assertRejects(noAmbientTimeIsRead, ReadsCurrentTimeMillis.class);
        assertRejects(noAmbientTimeIsRead, ConstructsADate.class);
        assertRejects(noAmbientTimeIsRead, ReferencesInstantNow.class);
        assertRejects(noAmbientTimeIsRead, ReadsTheAmbientZone.class);
        // Declared in package com.finapp.ledger so the module-scoped rule can see it as a
        // module other than the composition root. See SystemClockProbe for why.
        assertRejects(onlyTheCompositionRootBuildsASystemClock, SystemClockProbe.class);
    }

    @Test
    @DisplayName("the composition root may build a system clock, so the exemption is real and scoped")
    void theCompositionRootIsExempt() {
        // The other half of a module-scoped rule: it must permit what it means to permit.
        // BuildsASystemClock sits in com.finapp.app.architecture, so the rule sees it as the
        // composition root. If this failed, wiring a clock would be impossible anywhere.
        JavaClasses inCompositionRoot = new ClassFileImporter().importClasses(BuildsASystemClock.class);

        assertThatCode(() -> onlyTheCompositionRootBuildsASystemClock.check(inCompositionRoot))
                .doesNotThrowAnyException();

        // And the exemption must not leak: the same call from another module is rejected.
        JavaClasses inAnotherModule = new ClassFileImporter().importClasses(SystemClockProbe.class);

        assertThatThrownBy(() -> onlyTheCompositionRootBuildsASystemClock.check(inAnotherModule))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("the clock-taking overloads are allowed, so the rule does not push people off the right API")
    void rulesAcceptInjectedTime() {
        JavaClasses clean = new ClassFileImporter().importClasses(UsesAnInjectedClock.class);

        assertThatCode(
                        () -> {
                            noAmbientTimeIsRead.check(clean);
                            onlyTheCompositionRootBuildsASystemClock.check(clean);
                        })
                .doesNotThrowAnyException();
    }

    private static void assertRejects(ArchRule rule, Class<?> violation) {
        JavaClasses violating = new ClassFileImporter().importClasses(violation);

        assertThatThrownBy(() -> rule.check(violating))
                .as("%s must reject %s", rule.getDescription(), violation.getSimpleName())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violation.getSimpleName());
    }

    // ---------------------------------------------------------------------
    // Conditions
    // ---------------------------------------------------------------------

    private static ArchRule everyProductionClassShould(ArchCondition<JavaClass> condition) {
        return classes().that().resideInAPackage("com.finapp..").should(condition);
    }

    private static ArchCondition<JavaClass> readNoAmbientTime() {
        return new ArchCondition<>("read no ambient time") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    if (isAmbient(call.getTargetOwner().getFullName(), call.getName(), call.getTarget().getRawParameterTypes().isEmpty())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass, call.getDescription() + " reads ambient time"));
                    }
                }
                // A method reference is not a method call in the bytecode - it is an
                // invokedynamic whose target sits in the bootstrap attributes - so the loop
                // above cannot see it. Found by probing during this task's review:
                // `Supplier<Instant> s = Instant::now;` passed the rule completely. A rule that
                // is one syntax away from being bypassed is not enforcement.
                for (JavaMethodReference reference : javaClass.getMethodReferencesFromSelf()) {
                    if (isAmbient(
                            reference.getTargetOwner().getFullName(),
                            reference.getName(),
                            reference.getTarget().getRawParameterTypes().isEmpty())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass,
                                        reference.getDescription() + " references ambient time"));
                    }
                }
                for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                    if (AMBIENT_CONSTRUCTORS.contains(call.getTargetOwner().getFullName())
                            && call.getTarget().getRawParameterTypes().isEmpty()) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass, call.getDescription() + " captures ambient time"));
                    }
                }
            }
        };
    }

    /**
     * Whether a target reads the environment.
     *
     * <p>{@code takesNoArguments} is what separates {@code Instant.now()} from
     * {@code Instant.now(clock)}: the second is the idiomatic, injectable call and must stay
     * allowed.
     */
    private static boolean isAmbient(String owner, String name, boolean takesNoArguments) {
        boolean zeroArgNow = "now".equals(name) && takesNoArguments && TIME_TYPES.contains(owner);
        return zeroArgNow || AMBIENT_CALLS.contains(owner + "." + name);
    }

    private static ArchCondition<JavaClass> buildNoSystemClockOutside(String permittedModule) {
        return new ArchCondition<>("construct a system clock only in " + permittedModule) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (permittedModule.equals(ProductionModules.of(javaClass))) {
                    return;
                }
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    if ("java.time.Clock".equals(call.getTargetOwner().getFullName())
                            && SYSTEM_CLOCK_FACTORIES.contains(call.getName())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass,
                                        call.getDescription()
                                                + " builds a system clock outside the composition root"));
                    }
                }
            }
        };
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    @SuppressWarnings("unused")
    static final class ReadsInstantNow {
        Instant when() {
            return Instant.now();
        }
    }

    @SuppressWarnings("unused")
    static final class ReadsLocalDateNow {
        LocalDate today() {
            return LocalDate.now();
        }
    }

    @SuppressWarnings("unused")
    static final class ReadsCurrentTimeMillis {
        long millis() {
            return System.currentTimeMillis();
        }
    }

    @SuppressWarnings("unused")
    static final class ConstructsADate {
        Date when() {
            return new Date();
        }
    }

    @SuppressWarnings("unused")
    static final class BuildsASystemClock {
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @SuppressWarnings("unused")
    static final class ReferencesInstantNow {
        java.util.function.Supplier<Instant> when() {
            // Not a method call in the bytecode. Passed the rule until this task's review.
            return Instant::now;
        }
    }

    @SuppressWarnings("unused")
    static final class ReadsTheAmbientZone {
        LocalDate dateOf(Instant instant) {
            // Which date this instant falls on now depends on how the server is configured.
            return instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
    }

    /** The shape the platform actually uses: the clock arrives, it is never fetched. */
    @SuppressWarnings("unused")
    static final class UsesAnInjectedClock {
        private final Clock clock;

        UsesAnInjectedClock(Clock clock) {
            this.clock = clock;
        }

        Instant when() {
            return Instant.now(clock);
        }

        LocalDate today() {
            return LocalDate.now(clock);
        }

        long millis() {
            return clock.millis();
        }

        /** Pure: applied to a date the caller already holds, reads nothing. */
        LocalDate endOfMonth(LocalDate date) {
            return date.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        }

        /** An explicit zone is a decision, not an ambient read. */
        LocalDate dateIn(Instant instant, java.time.ZoneId zone) {
            return instant.atZone(zone).toLocalDate();
        }
    }
}
