package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaGenericArrayType;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Mechanical enforcement of {@code INV-MON-01} — no binary floating point on the path of a
 * monetary value (P0-TSK-008).
 *
 * <p>This is the platform's first non-negotiable financial rule ({@code CLAUDE.md} rule 1) and
 * the first entry in {@code DEFINITION_OF_DONE.md} §3's blocking anti-patterns. Binary floating
 * point cannot represent {@code 0.1}; the error is small, it accumulates, and it silently
 * creates or destroys money in a way no assertion on a happy path will ever notice.
 *
 * <p><strong>Why the rule is default-deny over all production code, rather than scoped to
 * "financial packages".</strong> A list of financial packages is a denylist, and a denylist is
 * wrong in exactly the case that matters: a new package is unprotected by default, and nothing
 * reports that it was forgotten. The P0-TSK-007 review already found this failure mode once —
 * a class directly in {@code com.finapp} was silently exempt from every boundary rule. Here the
 * asymmetry is starker still: this repository is financial infrastructure, so a package that
 * cannot touch money is the exception, not the rule. Default-deny makes every exemption a
 * visible edit to {@link #EXEMPT_CLASSES} that a reviewer must justify, instead of an omission
 * nobody can see.
 *
 * <p><strong>The four surfaces checked.</strong> A monetary value reaches a {@code double} in
 * more ways than a field declaration:
 *
 * <ol>
 *   <li>fields — {@code private double amount}, including {@code double[]} and {@code List<Double>};
 *   <li>method and constructor signatures — parameters and returns, the way a value crosses a boundary;
 *   <li>calls into floating-point APIs — {@code new BigDecimal(0.1)}, {@code Double.parseDouble},
 *       {@code BigDecimal::doubleValue}, {@code ResultSet::getDouble}. These are the dangerous
 *       ones: they leave no trace in any signature, so a rule that only reads declarations
 *       would pass over the most common way money actually loses precision;
 *   <li>reads and writes of a floating-point field — the one surface that is neither a
 *       declaration of ours nor a call.
 * </ol>
 *
 * <p><strong>The gap, stated honestly.</strong> One shape escapes: a {@code double} local
 * computed only from compile-time constants and narrowed by a cast —
 * {@code long cents = (long) (0.1 * 3 * 100);}. Two properties of the compiler put it out of
 * reach. A cast is a bytecode instruction, and ArchUnit models declarations and accesses rather
 * than instruction streams. And javac inlines {@code static final double} literals, so even
 * {@code Math.PI} leaves no field access behind to detect — which is why the fourth rule is
 * about non-constant fields, and why claiming it covers constants would have been false.
 *
 * <p>The gap is narrow: such a value is inert unless it is stored, returned, passed, or derived
 * from something not constant, and every one of those is caught above. It is recorded rather
 * than papered over, because a rule believed to be total is more dangerous than one whose edge
 * is known.
 *
 * <p>Tests are excluded from the sweep: a test may legitimately construct a {@code double} to
 * prove that something rejects it, and the nested fixtures below do exactly that.
 */
@Tag("architecture")
// NOT a duplicate of the line above. ArchUnit runs @ArchTest fields under its OWN
// JUnit Platform engine, and that engine's descriptors read `ArchTag` - they cannot
// see JUnit's `Tag` at all. Without this every rule below carried no tag, so
// `unitTest` (which selects by EXCLUSION) took them and `architectureTest` (which
// selects by INCLUSION) got none. P1-TSK-025; TestTaxonomyTest now requires the pair.
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class NoFloatingPointMoneyRulesTest {

    /** The types this rule exists to keep away from money, by their {@code JavaClass} names. */
    private static final Set<String> FLOATING_POINT_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    /**
     * Classes exempted from the rule - deliberately few, each named with its argument below. (It
     * opened "deliberately empty", which it has not been since the first metrics exemption.)
     *
     * <p>An exemption belongs here only if the value provably cannot reach a monetary path — a
     * sampling ratio or a latency percentile, never an amount, a rate, a fee or a balance.
     * Adding an entry is a visible change to the architecture rules and needs the same scrutiny
     * as changing an invariant, which is the point: the alternative is that someone weakens or
     * deletes the rule the first time it is inconvenient.
     *
     * <h2>The four entries below, and why they are not the thin end of a wedge</h2>
     *
     * <p>{@code P0-TSK-029} added the first two and they were the first since this rule was
     * written; {@code P1-TSK-029} added the second two, and they are the <strong>same case</strong>
     * rather than a new one. The values are a <strong>count of unpublished outbox rows</strong>, an
     * <strong>age in whole seconds</strong>, and a <strong>count of live sessions</strong>. None is
     * money, none is derived from money, and none can reach a monetary path: every one comes from
     * a {@code count(*)} or a timestamp difference, and every one goes to a metrics registry and
     * nowhere else.
     *
     * <p>That the second pair is the same case as the first is what makes this list healthy rather
     * than growing: a fifth entry for something that is <em>not</em> a Micrometer gauge over a row
     * count would be a new decision and should be argued as one.
     *
     * <p>The {@code double} is <strong>imposed by Micrometer</strong>, whose {@code Gauge} takes a
     * {@code ToDoubleFunction} — there is no integer gauge to use instead. The alternative to an
     * exemption was to publish no outbox metrics at all, leaving ADR-0005's named signals
     * unmonitored and the recorded debt unpaid.
     *
     * <p><strong>What was NOT exempted.</strong> The same rule also failed on {@code
     * OutboxBacklog}, which read the age as a {@code double} and floored it. That was fixed rather
     * than exempted — the SQL now casts to {@code bigint} — because there the floating point was
     * avoidable, and "it is only a metric" is exactly the reasoning that spreads the habit to
     * something that is not. An exemption is for what the platform cannot control, never a
     * shortcut past what it can.
     */
    private static final Set<String> EXEMPT_CLASSES =
            Set.of(
                    "com.finapp.app.telemetry.OutboxMetrics",
                    "com.finapp.app.telemetry.OutboxMetrics$Cached",
                    // P1-TSK-029. A count of LIVE SESSIONS, published through the same
                    // ToDoubleFunction Micrometer's Gauge imposes. The count itself is a `long` all
                    // the way from `count(*)` to the registry boundary - SessionStore.countLive
                    // returns `long`, not `double`, because that half WAS avoidable and "it is only
                    // a metric" is the reasoning that spreads the habit to something that is not.
                    "com.finapp.app.telemetry.IdentityMetrics",
                    "com.finapp.app.telemetry.IdentityMetrics$Cached",
                    // P2-TSK-010. The SAME case a third time: a count of OPEN review tasks -
                    // a `long` from count(*) all the way to the registry boundary
                    // (ReviewTaskStore.countOpen returns long) - published through the
                    // ToDoubleFunction Micrometer's Gauge imposes.
                    "com.finapp.app.telemetry.KycMetrics",
                    "com.finapp.app.telemetry.KycMetrics$Cached",
                    // P6-TSK-011. The SAME case again: a count of OPEN payout destination
                    // changes - a `long` from count(*) all the way to the registry boundary
                    // (PayoutDestinationStore.countOpen returns long) - published through the
                    // ToDoubleFunction Micrometer's Gauge imposes.
                    "com.finapp.app.telemetry.MerchantMetrics",
                    "com.finapp.app.telemetry.MerchantMetrics$Cached",
                    // P3-TSK-010. The SAME case again: a count of DRIFTING projection rows -
                    // a `long` from ProjectionVerification.Report all the way to the registry
                    // boundary - published through the ToDoubleFunction Micrometer's Gauge
                    // imposes. The comparison itself folds through Money and never leaves
                    // the ledger module; only the tally reaches this class.
                    "com.finapp.app.telemetry.LedgerMetrics",
                    "com.finapp.app.telemetry.LedgerMetrics$Cached",
                    // P3-TSK-019. The SAME case again: per-currency trial-balance VERDICTS
                    // (0 balanced / 1 out / NaN unverifiable) - never the imbalance amount,
                    // which deliberately does not leave TrialBalance at all - published
                    // through the ToDoubleFunction Micrometer's Gauge imposes. The sweep's
                    // own arithmetic is exact BigDecimal inside the ledger module; the only
                    // floating point is the registry boundary's.
                    "com.finapp.app.telemetry.LedgerMetrics$TrialCached",
                    // P3-TSK-020. The SAME case a sixth time: the ACTIVE hold COUNT - a
                    // `long` from HoldStore.countActive to the registry boundary - published
                    // through the ToDoubleFunction Micrometer's Gauge imposes. A count,
                    // never an amount (INV-AUD-02).
                    "com.finapp.app.telemetry.LedgerMetrics$HoldCached",
                    // P5-TSK-017. The SAME case again, a seventh time: the COUNT of payment
                    // operations stranded in an unknown state and the AGE IN SECONDS of the
                    // oldest - longs from the stores to the registry boundary, published
                    // through the ToDoubleFunction Micrometer's Gauge imposes, with NaN the
                    // sentinel for unreadable. Counts and seconds, never an amount: the
                    // money parked behind an unknown refund is deliberately NOT published
                    // (INV-AUD-02), which is why there is no monetary value in this class
                    // to be imprecise about.
                    "com.finapp.app.telemetry.PaymentMetrics",
                    "com.finapp.app.telemetry.PaymentMetrics$Cached",
                    // P6-TSK-013. The SAME case again, the PaymentMetrics shape exactly: the
                    // COUNT of payouts without an answer past their due and the AGE IN SECONDS
                    // of the oldest - longs from MerchantPayoutStore.UnknownReading to the
                    // registry boundary, published through the ToDoubleFunction Micrometer's
                    // Gauge imposes, with NaN the sentinel for unreadable. The merchant money
                    // parked behind those payouts' holds is deliberately NOT published
                    // (INV-AUD-02), so there is no monetary value here to be imprecise about.
                    "com.finapp.app.telemetry.MerchantPayoutMetrics",
                    "com.finapp.app.telemetry.MerchantPayoutMetrics$Cached",
                    // P7-TSK-009. The SAME case again, the PaymentMetrics shape exactly:
                    // the COUNT of initiations awaiting the payer and of confirmations
                    // parked in suspense, and the AGE IN SECONDS of each oldest - longs
                    // from the stores' UnknownReading to the registry boundary, published
                    // through the ToDoubleFunction Micrometer's Gauge imposes, with NaN
                    // the sentinel for unreadable. The parked AMOUNTS are deliberately NOT
                    // published (INV-AUD-02, INV-REC-05's gauge is a count and an age), so
                    // there is no monetary value here to be imprecise about.
                    "com.finapp.app.telemetry.PayInMetrics",
                    "com.finapp.app.telemetry.PayInMetrics$Cached",
                    // P7-TSK-013. The SAME case again, the PaymentMetrics shape exactly: the
                    // COUNT of counterparty accounts below zero, per purpose - longs out of
                    // the ledger's grouped count, published through the ToDoubleFunction
                    // Micrometer's Gauge imposes, with NaN the sentinel for unreadable. The
                    // negative BALANCES are deliberately not published (INV-AUD-02: the gauge
                    // is a count), so there is no monetary value here to be imprecise about.
                    "com.finapp.app.telemetry.NegativePositionMetrics",
                    "com.finapp.app.telemetry.NegativePositionMetrics$Cached",
                    // P7-TSK-014. The SAME case again: the COUNT of chargebacks near or past
                    // their respond-by deadline - a long out of the dispute rows, published
                    // through the ToDoubleFunction Micrometer's Gauge imposes, NaN the sentinel
                    // for unreadable. No monetary value here to be imprecise about.
                    "com.finapp.app.telemetry.DisputeDeadlineMetrics",
                    "com.finapp.app.telemetry.DisputeDeadlineMetrics$Cached",
                    // P7-TSK-015. The SAME case again, twice: disputes COUNTED per stage, and a
                    // stuck-operation pair's count and oldest wait in whole seconds - longs out
                    // of the rows, published through the ToDoubleFunction Micrometer's Gauge
                    // imposes, NaN the sentinel for unreadable. No monetary value in either.
                    "com.finapp.app.telemetry.DisputeStageMetrics",
                    "com.finapp.app.telemetry.DisputeStageMetrics$Cached",
                    "com.finapp.app.telemetry.StuckOperationMetrics",
                    "com.finapp.app.telemetry.StuckOperationMetrics$Cached",
                    // P8-TSK-003. The SAME case again, the PaymentMetrics shape exactly: the
                    // COUNT of non-terminal settlement files per source and the AGE IN whole
                    // SECONDS of each source's oldest - longs from the store's PendingReading
                    // to the registry boundary, published through the ToDoubleFunction
                    // Micrometer's Gauge imposes, NaN the sentinel for unreadable. A count
                    // and an age, never an amount: no monetary value here to be imprecise
                    // about.
                    "com.finapp.app.telemetry.SettlementFileMetrics",
                    "com.finapp.app.telemetry.SettlementFileMetrics$Cached",
                    // P8-TSK-007. The SAME case again: CURRENCIES FAILING the position
                    // proof, UNEXPLAINED LINE counts and OPEN EXPECTATION counts - longs
                    // from PositionProof.Report to the registry boundary, published through
                    // the ToDoubleFunction Micrometer's Gauge imposes, NaN the sentinel for
                    // an unreadable sweep. Counts, never amounts (ADR-0072): the identity's
                    // Money arithmetic lives in PositionProof and folds through the kernel.
                    "com.finapp.app.telemetry.ReconciliationMetrics",
                    "com.finapp.app.telemetry.ReconciliationMetrics$Cached",
                    // P2-TSK-001. The SAME case again, not a new one: counts of published,
                    // failed and dead-lettered events - ints out of RelayPollResult - published
                    // through Counter.increment(double), the only instrument Micrometer offers.
                    // The counts are ints end to end; the double appears at the registry
                    // boundary and nowhere else.
                    "com.finapp.app.eventing.OutboxRelaySchedule",
                    // P2-TSK-002. The consuming twin of the entry above, same argument
                    // verbatim: ints out of ReceiverPollResult, Counter.increment(double) at
                    // the registry boundary, floating point nowhere else in the class.
                    "com.finapp.app.eventing.InboxConsumers$Loop");

    // ---------------------------------------------------------------------
    // Guard: the rules must actually see the code they claim to protect.
    //
    // Every rule below is vacuously satisfied over classes that were never
    // imported. A rule that protects INV-MON-01 while seeing nothing is not a
    // weak rule, it is a false report of safety on the platform's most
    // fundamental financial guarantee.
    //
    // The first version of this guard asserted only that Money was analysed.
    // That was demonstrated insufficient during this task's completion review:
    // narrowing the sweep to sharedkernel left the guard green while a double
    // planted in platform's MoneyColumns went entirely undetected, and the
    // build passed. Coverage is therefore derived from the classpath, so a
    // module that stops being analysed fails the build instead of quietly
    // losing its protection.
    // ---------------------------------------------------------------------

    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed =
                imported.stream()
                        .map(ProductionModules::of)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());

        assertThat(analysed)
                .as("every module with production classes must be within reach of INV-MON-01")
                .containsAll(ProductionModules.onClasspathWithProductionClasses());

        // Named explicitly as well as derived. Money is the reason this rule exists, and a
        // classpath-derived expectation would not notice if sharedkernel were ever reduced to
        // package-info and therefore legitimately excluded from the derivation above.
        assertThat(imported.stream().map(JavaClass::getFullName))
                .as("the class this invariant exists for must be analysed")
                .contains("com.finapp.sharedkernel.money.Money");
    }

    // ---------------------------------------------------------------------
    // The rules
    // ---------------------------------------------------------------------

    @ArchTest
    static final ArchRule noFieldHoldsAFloatingPointValue =
            everyProductionClassShould(declareNoFloatingPointField())
                    .because(
                            "a field is where a value rests. A monetary amount held in a double "
                                + "has already lost precision by the time anything reads it");

    @ArchTest
    static final ArchRule noSignatureCarriesAFloatingPointValue =
            everyProductionClassShould(declareNoFloatingPointSignature())
                    .because(
                            "parameters and returns are how a value crosses a boundary; a "
                                + "floating-point signature makes imprecision part of a contract");

    @ArchTest
    static final ArchRule noCallReachesAFloatingPointApi =
            everyProductionClassShould(callNoFloatingPointApi())
                    .because(
                            "new BigDecimal(0.1) and BigDecimal::doubleValue destroy precision "
                                + "without appearing in any declaration, which makes them the "
                                + "leak a declaration-only rule would miss");

    @ArchTest
    static final ArchRule noFloatingPointFieldIsAccessed =
            everyProductionClassShould(accessNoFloatingPointField())
                    .because(
                            "reading or writing a floating-point field is the first step of a "
                                + "floating-point computation, and is the one surface neither a "
                                + "declaration nor a call would show");

    // ---------------------------------------------------------------------
    // Teeth.
    //
    // DOD-TEST requires a rule to be demonstrated failing when what it protects
    // is broken. Doing that by hand proves it once, on one machine, on one day.
    // These fixtures prove it on every build, and — the half that is easy to
    // forget — that the rule still passes on code that is clean, so it cannot
    // degrade into one that fails for everything.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("each rule rejects the violation it exists to catch")
    void rulesRejectTheirViolations() {
        assertRejects(noFieldHoldsAFloatingPointValue, DoubleField.class);
        assertRejects(noFieldHoldsAFloatingPointValue, BoxedDoubleField.class);
        assertRejects(noFieldHoldsAFloatingPointValue, DoubleArrayField.class);
        assertRejects(noFieldHoldsAFloatingPointValue, GenericDoubleField.class);
        assertRejects(noSignatureCarriesAFloatingPointValue, DoubleParameter.class);
        assertRejects(noSignatureCarriesAFloatingPointValue, DoubleReturn.class);
        assertRejects(noSignatureCarriesAFloatingPointValue, DoubleConstructorParameter.class);
        assertRejects(noCallReachesAFloatingPointApi, CallsBigDecimalDoubleConstructor.class);
        assertRejects(noCallReachesAFloatingPointApi, CallsDoubleValue.class);
        assertRejects(noFloatingPointFieldIsAccessed, AccessesAFloatingPointField.class);
    }

    @Test
    @DisplayName("the rules pass on exact monetary code, so they are not merely always-failing")
    void rulesAcceptExactCode() {
        JavaClasses clean = new ClassFileImporter().importClasses(ExactMoneyArithmetic.class);

        assertThatCode(
                        () -> {
                            noFieldHoldsAFloatingPointValue.check(clean);
                            noSignatureCarriesAFloatingPointValue.check(clean);
                            noCallReachesAFloatingPointApi.check(clean);
                            noFloatingPointFieldIsAccessed.check(clean);
                        })
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // Recursive bounds.
    //
    // A type variable's bound may name the variable itself - E extends Enum<E>,
    // T extends Comparable<T> - and ArchUnit models that as the cyclic object
    // graph it is. The walk below once followed it forever: during P8-TSK-014 a
    // production helper declared <E extends Enum<E>> made this suite throw
    // StackOverflowError, which is neither a pass nor a failure, and the helper
    // was rewritten to get past a crash in the rule rather than a finding.
    //
    // Two properties, so a fix cannot buy one with the other: the walk ends,
    // and a floating-point type reached only THROUGH such a bound is still
    // found. Stopping at every type variable would satisfy the first and go
    // blind to the second.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a recursive type bound is walked to an end, so exact code declaring one passes")
    void recursiveBoundsDoNotOverflowTheWalk() {
        JavaClasses clean = new ClassFileImporter().importClasses(RecursivelyBoundExactCode.class);

        assertThatCode(
                        () -> {
                            noFieldHoldsAFloatingPointValue.check(clean);
                            noSignatureCarriesAFloatingPointValue.check(clean);
                            noCallReachesAFloatingPointApi.check(clean);
                            noFloatingPointFieldIsAccessed.check(clean);
                        })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a floating-point type reached only through a recursive bound is still rejected")
    void recursiveBoundsDoNotHideFloatingPoint() {
        assertRejects(noSignatureCarriesAFloatingPointValue, DoubleBehindARecursiveBound.class);
        assertRejects(noSignatureCarriesAFloatingPointValue, FloatInsideARecursiveBound.class);
        assertRejects(noFieldHoldsAFloatingPointValue, RecursivelyBoundDoubleField.class);
    }

    private static void assertRejects(ArchRule rule, Class<?> violation) {
        JavaClasses violating = new ClassFileImporter().importClasses(violation);

        // Caught first and asserted after: assertThatThrownBy fails on a clean pass before .as()
        // is applied, so a rule gone blind reported "Expecting code to raise a throwable" without
        // naming the fixture it let through - the one failure this helper exists to explain.
        assertThat(catchThrowable(() -> rule.check(violating)))
                .as("%s must reject %s", rule.getDescription(), violation.getSimpleName())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violation.getSimpleName());
    }

    // ---------------------------------------------------------------------
    // Conditions
    // ---------------------------------------------------------------------

    /**
     * Applies a condition to every production class in the platform.
     *
     * <p>Phrased as a condition over all {@code com.finapp} classes for the same reason as the
     * boundary rules: ArchUnit fails a rule whose {@code that()} clause matches nothing, and
     * suppressing that would make a rule pass for the wrong reason.
     */
    private static ArchRule everyProductionClassShould(ArchCondition<JavaClass> condition) {
        return classes().that().resideInAPackage("com.finapp..").should(condition);
    }

    private static ArchCondition<JavaClass> declareNoFloatingPointField() {
        return new ArchCondition<>("declare no floating-point field") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (isExempt(javaClass)) {
                    return;
                }
                for (JavaField field : javaClass.getFields()) {
                    if (isFloatingPoint(field.getType())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass,
                                        "field "
                                                + field.getFullName()
                                                + " is "
                                                + field.getType().getName()
                                                + " (INV-MON-01)"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> declareNoFloatingPointSignature() {
        return new ArchCondition<>("declare no floating-point parameter or return type") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (isExempt(javaClass)) {
                    return;
                }
                javaClass
                        .getCodeUnits()
                        .forEach(
                                codeUnit -> {
                                    for (JavaType parameter : codeUnit.getParameterTypes()) {
                                        if (isFloatingPoint(parameter)) {
                                            events.add(
                                                    SimpleConditionEvent.violated(
                                                            javaClass,
                                                            codeUnit.getFullName()
                                                                    + " takes "
                                                                    + parameter.getName()
                                                                    + " (INV-MON-01)"));
                                        }
                                    }
                                    if (codeUnit instanceof JavaMethod method
                                            && isFloatingPoint(method.getReturnType())) {
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        javaClass,
                                                        method.getFullName()
                                                                + " returns "
                                                                + method.getReturnType().getName()
                                                                + " (INV-MON-01)"));
                                    }
                                });
            }
        };
    }

    /**
     * Forbids calling anything that takes or returns a floating-point value.
     *
     * <p>This is the rule that catches {@code new BigDecimal(0.1)}: the constructor is in the
     * JDK, so no declaration of ours mentions a {@code double}, yet the value is corrupted at
     * the call.
     */
    private static ArchCondition<JavaClass> callNoFloatingPointApi() {
        return new ArchCondition<>("call no method or constructor that uses floating point") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (isExempt(javaClass)) {
                    return;
                }
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    boolean usesFloatingPoint =
                            anyFloatingPoint(call.getTarget().getRawParameterTypes())
                                    || isFloatingPoint(call.getTarget().getRawReturnType());
                    if (usesFloatingPoint) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass, call.getDescription() + " (INV-MON-01)"));
                    }
                }
                for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                    if (anyFloatingPoint(call.getTarget().getRawParameterTypes())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass, call.getDescription() + " (INV-MON-01)"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> accessNoFloatingPointField() {
        return new ArchCondition<>("access no floating-point field") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (isExempt(javaClass)) {
                    return;
                }
                for (JavaFieldAccess access : javaClass.getFieldAccessesFromSelf()) {
                    if (isFloatingPoint(access.getTarget().getRawType())) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        javaClass, access.getDescription() + " (INV-MON-01)"));
                    }
                }
            }
        };
    }

    // ---------------------------------------------------------------------
    // Type inspection
    // ---------------------------------------------------------------------

    private static boolean isExempt(JavaClass javaClass) {
        return EXEMPT_CLASSES.contains(javaClass.getFullName());
    }

    private static boolean anyFloatingPoint(List<JavaClass> types) {
        return types.stream().anyMatch(NoFloatingPointMoneyRulesTest::isFloatingPoint);
    }

    /**
     * Whether a type is, contains, or is an array of a floating-point type.
     *
     * <p>The recursion is what makes {@code List<Double>} and {@code Map<String, double[]>}
     * violations rather than {@code List} and {@code Map}. A rule that only inspected the raw
     * type would let a collection of amounts through, and a collection of amounts is where the
     * error compounds fastest.
     *
     * <p><strong>Each type variable is walked once.</strong> A bound may name its own variable -
     * {@code E extends Enum<E>} - and ArchUnit returns that cycle as it is, so following bounds
     * unconditionally never ends: {@code P8-TSK-014} met it as a {@code StackOverflowError}. A
     * variable met again answers {@code false}, and that is exact rather than a guess. The
     * verdict is a pure disjunction, so the earlier visit has either answered {@code false}
     * already - a {@code true} would have ended the walk - or is still being answered further up,
     * over the very bounds the repeat would re-read. Only the repeat is skipped, never the
     * variable's other bounds, which is what keeps a {@code Double} behind the cycle in view.
     *
     * <p>Visits are tracked by identity: a variable is its declaration, and two methods may each
     * declare a {@code T} with different bounds.
     */
    private static boolean isFloatingPoint(JavaType type) {
        return isFloatingPoint(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean isFloatingPoint(JavaType type, Set<JavaTypeVariable<?>> walked) {
        // The erasure covers the plain and array cases: List<Double> erases to List, but
        // double[] and Double[] erase to themselves.
        if (isFloatingPointErasure(type.toErasure())) {
            return true;
        }
        return switch (type) {
            case JavaParameterizedType parameterized ->
                    anyType(parameterized.getActualTypeArguments(), walked);
            case JavaGenericArrayType array -> isFloatingPoint(array.getComponentType(), walked);
            case JavaWildcardType wildcard ->
                    anyType(wildcard.getUpperBounds(), walked)
                            || anyType(wildcard.getLowerBounds(), walked);
            case JavaTypeVariable<?> variable ->
                    walked.add(variable) && anyType(variable.getUpperBounds(), walked);
            default -> false;
        };
    }

    private static boolean isFloatingPointErasure(JavaClass javaClass) {
        JavaClass base = javaClass.isArray() ? javaClass.getBaseComponentType() : javaClass;
        return FLOATING_POINT_TYPES.contains(base.getName());
    }

    private static boolean anyType(List<JavaType> types, Set<JavaTypeVariable<?>> walked) {
        return types.stream().anyMatch(type -> isFloatingPoint(type, walked));
    }

    // ---------------------------------------------------------------------
    // Fixtures.
    //
    // Deliberate violations, one per surface. They are test classes, so
    // DoNotIncludeTests keeps them out of the sweep over production code; the
    // teeth tests import them explicitly.
    // ---------------------------------------------------------------------

    @SuppressWarnings("unused")
    static final class DoubleField {
        double amount = 0.0d;
    }

    @SuppressWarnings("unused")
    static final class BoxedDoubleField {
        Double amount = Double.valueOf(0.0d);
    }

    @SuppressWarnings("unused")
    static final class DoubleArrayField {
        double[] instalments = new double[0];
    }

    @SuppressWarnings("unused")
    static final class GenericDoubleField {
        List<Double> instalments = List.of();
    }

    @SuppressWarnings("unused")
    static final class DoubleParameter {
        long toCents(double amount) {
            return (long) amount;
        }
    }

    @SuppressWarnings("unused")
    static final class DoubleReturn {
        double rate() {
            return 0.0d;
        }
    }

    @SuppressWarnings("unused")
    static final class DoubleConstructorParameter {
        DoubleConstructorParameter(double amount) {
            // The value is discarded; the signature is the violation.
        }
    }

    @SuppressWarnings("unused")
    static final class CallsBigDecimalDoubleConstructor {
        BigDecimal amount() {
            // The canonical money bug: this is 0.1000000000000000055511151231257827...
            return new BigDecimal(0.1);
        }
    }

    @SuppressWarnings("unused")
    static final class CallsDoubleValue {
        long cents(BigDecimal amount) {
            return Math.round(amount.doubleValue() * 100);
        }
    }

    /**
     * A floating-point field that is not a compile-time constant, so reading it survives
     * compilation. {@code Math.PI} would not: javac inlines {@code static final double}
     * literals, which is the limitation recorded in the class javadoc.
     */
    @SuppressWarnings("unused")
    static final class MutableRateHolder {
        static double rate;
    }

    @SuppressWarnings("unused")
    static final class AccessesAFloatingPointField {
        long apply(long amount) {
            return (long) (amount * MutableRateHolder.rate);
        }
    }

    /** The shape the platform actually uses: exact, integral, no floating point anywhere. */
    @SuppressWarnings("unused")
    static final class ExactMoneyArithmetic {
        private final long minorUnits;

        ExactMoneyArithmetic(long minorUnits) {
            this.minorUnits = minorUnits;
        }

        BigDecimal toAmount(int scale) {
            return BigDecimal.valueOf(minorUnits, scale);
        }
    }

    /**
     * Exact code whose generics name themselves: the shapes that overflowed the walk. {@code
     * enumParam} is the {@code P8-TSK-014} helper's signature verbatim; the class's own bound is
     * the self-typed builder's, reached through a field; {@code ordered} closes a cycle of two
     * variables rather than one.
     */
    @SuppressWarnings("unused")
    static class RecursivelyBoundExactCode<S extends RecursivelyBoundExactCode<S>> {
        private S self;

        private static <E extends Enum<E>> Optional<E> enumParam(
                String name, String value, Class<E> type) {
            return value == null ? Optional.empty() : Optional.of(Enum.valueOf(type, value));
        }

        <T extends Comparable<T>> T larger(T left, T right) {
            return left.compareTo(right) >= 0 ? left : right;
        }

        <A extends Comparable<B>, B extends Comparable<A>> boolean ordered(A first, B second) {
            return first.compareTo(second) <= 0;
        }
    }

    /**
     * The {@code Double} sits in the bound AFTER the one that recurses, so a walk that abandons a
     * variable on meeting its cycle - rather than skipping only the repeated visit - misses it.
     */
    @SuppressWarnings("unused")
    static final class DoubleBehindARecursiveBound {
        <T extends Comparable<T> & Supplier<Double>> long toCents(T amount) {
            return 0L; // The signature is the violation.
        }
    }

    /** The {@code float[]} sits in the recursing bound itself, beside the self-reference. */
    @SuppressWarnings("unused")
    static final class FloatInsideARecursiveBound {
        <E extends Map<E, float[]>> Optional<E> rates() {
            return Optional.empty();
        }
    }

    /** A class-level recursive bound carrying a {@code Double}, reached through a field. */
    @SuppressWarnings("unused")
    static final class RecursivelyBoundDoubleField<S extends Comparable<S> & Supplier<Double>> {
        private S rate;
    }
}
