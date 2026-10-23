package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.app.session.RequiresSession;
import com.finapp.risk.CaseKind;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskRating;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tipping-off control at the surface (`P13-TSK-001`; {@code INV-RSK-08}; {@code PHASE_13_PLAN.md} sections 9, 11;
 * ADR-0107 decision 8): <strong>no customer or merchant surface reaches {@code risk}</strong>. A customer's answer to a
 * held or refused movement is the operation's generic outcome; nothing of an alert, a case, a report or an investigation
 * - not even that one exists - is derivable from a customer- or merchant-facing response.
 *
 * <p><strong>The surface, precisely.</strong> Every {@code @RestController} mapped under {@code /me} (the customer's own
 * routes), every controller that is, or has a method, {@code @RequiresMerchantKey} (the merchant's key-authenticated
 * routes), and - since `P13-TSK-008` - every controller that is {@code @RequiresSession} as a class and is not mapped
 * under {@code /operator} (the customer's session routes outside {@code /me}: {@code /transfers}, {@code /beneficiaries},
 * {@code /payments}, {@code /sessions}, the checkout confirmation, the MFA step-up), plus the {@code app} classes each of
 * those depends on directly - its desks. None of them may depend on a type of {@code com.finapp.risk..}. Operator routes
 * ({@code /operator/risk/...}) are not the surface: they are the desks the guard exists to keep separate from it.
 *
 * <p><strong>Why the third clause.</strong> The customer's transfer door is {@code POST /v1/transfers}, not under
 * {@code /me}, and it is the first customer door to sit beside a risk judgement (`P13-TSK-008`: the step-up advice before
 * the command). The rule as first written did not see it at all; the door was built to the rule's standard anyway (an
 * {@code app} port answering a boolean, implemented in the risk composition), and the clause makes that a checked fact
 * rather than a claim.
 *
 * <p><strong>What this does not claim.</strong> The reach is one hop: a desk's own dependency on {@code risk} two hops
 * from a controller is not seen here. The seam adapters are wired in the consumers' bean configurations, never in a
 * desk, so the hop that matters is the one checked; a later task adding a second hop adds it to the probes below. A
 * response that differs in timing with a case open is outside this guard's claims.
 *
 * <p><strong>Proven able to fail.</strong> The rule is computed over the classes it is given, so the probes below are
 * planted in this test's own source - a {@code /me} controller holding a risk type, a merchant-key controller holding
 * one, a {@code /me} controller whose desk holds one, and an operator lookalike that must NOT be flagged - and the rule
 * is run over exactly those.
 */
@Tag("architecture")
// ArchUnit's own engine reads ArchTag, not JUnit's Tag (P1-TSK-025; TestTaxonomyTest requires the pair).
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class NoCustomerSurfaceReadsRiskTest {

    private static final String RISK = "com.finapp.risk..";
    private static final String APP = "com.finapp.app";

    @ArchTest
    static void noCustomerOrMerchantSurfaceReachesRisk(JavaClasses imported) {
        rule(imported).check(imported);
    }

    /** The coverage guard every rule suite here has: a rule that sees nothing passes, and reports safety it never checked. */
    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed = new TreeSet<>();
        imported.stream().map(ProductionModules::of).filter(Objects::nonNull).forEach(analysed::add);
        assertThat(analysed)
                .as("the rule must see every module that has production code, or it protects only some of them")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());
    }

    @ArchTest
    static void theSurfaceIsNotEmpty(JavaClasses imported) {
        // The customer's withdrawal door and the merchant's checkout session door exist; a surface the matcher cannot
        // see is a guard over nothing.
        assertThat(guardedClassNames(imported))
                .anyMatch(name -> name.endsWith(".WithdrawalController"))
                .anyMatch(name -> name.endsWith(".CheckoutSessionController"))
                // The transfer door and its desk (P13-TSK-008): a session route outside /me.
                .anyMatch(name -> name.endsWith(".TransferController"))
                .anyMatch(name -> name.endsWith(".TransferService"));
    }

    @Test
    @DisplayName("a session route outside /me holding a risk type is refused (P13-TSK-008)")
    void theRuleRefusesASessionRouteOutsideMe() {
        JavaClasses planted = imported(PlantedSessionController.class, RiskDecision.class);
        EvaluationResult result = rule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedSessionController");
    }

    @Test
    @DisplayName("a /me controller holding a risk type is refused")
    void theRuleRefusesACustomerController() {
        JavaClasses planted = imported(PlantedMeController.class, RiskDecision.class);
        EvaluationResult result = rule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedMeController");
    }

    @Test
    @DisplayName("a merchant-key controller holding a risk type is refused")
    void theRuleRefusesAMerchantController() {
        JavaClasses planted = imported(PlantedMerchantController.class, CaseKind.class);
        EvaluationResult result = rule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedMerchantController");
    }

    @Test
    @DisplayName("a /me controller whose desk holds a risk type is refused at the desk")
    void theRuleRefusesACustomerDesk() {
        JavaClasses planted = imported(PlantedMeControllerWithDesk.class, PlantedDesk.class, RiskRating.class);
        EvaluationResult result = rule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedDesk");
    }

    @Test
    @DisplayName("an operator controller holding a risk type is not the surface, and passes")
    void theRuleLeavesTheOperatorDeskAlone() {
        JavaClasses planted = imported(LookalikeOperatorController.class, RiskDecision.class);
        assertThat(guardedClassNames(planted)).isEmpty();
        // ArchUnit refuses a rule whose that() clause selected nothing, which is its own vacuity guard: with no surface
        // among these classes there is nothing to evaluate, and that is the assertion.
    }

    // ------------------------------------------------------------------ the rule

    static ArchRule rule(JavaClasses classes) {
        Set<String> guarded = guardedClassNames(classes);
        DescribedPredicate<JavaClass> onTheSurface =
                new DescribedPredicate<>("on the customer or merchant surface, or a direct app dependency of it") {
                    @Override
                    public boolean test(JavaClass candidate) {
                        return guarded.contains(candidate.getName());
                    }
                };
        return noClasses()
                .that(onTheSurface)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(RISK)
                .because(
                        "nothing of an alert, a case, a report or an investigation reaches a customer or merchant"
                                + " surface (INV-RSK-08, the tipping-off control): a customer's answer to a held or"
                                + " refused movement is the operation's generic outcome, and the operator's risk desks"
                                + " are the only readers of risk's tables");
    }

    /** The surface's classes and their direct {@code app} dependencies, by name. */
    static Set<String> guardedClassNames(JavaClasses classes) {
        Set<String> names = new TreeSet<>();
        for (JavaClass candidate : classes) {
            if (isSurface(candidate)) {
                names.add(candidate.getName());
                candidate.getDirectDependenciesFromSelf().stream()
                        .map(Dependency::getTargetClass)
                        .filter(target -> target.getPackageName().startsWith(APP))
                        .map(JavaClass::getName)
                        .forEach(names::add);
            }
        }
        return names;
    }

    private static boolean isSurface(JavaClass candidate) {
        if (!candidate.isAnnotatedWith(RestController.class)) {
            return false;
        }
        if (mappedUnder(candidate, "/me")) {
            return true;
        }
        if (candidate.isAnnotatedWith(RequiresSession.class) && !mappedUnder(candidate, "/operator")) {
            return true;
        }
        if (candidate.isAnnotatedWith(RequiresMerchantKey.class)) {
            return true;
        }
        return candidate.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(RequiresMerchantKey.class));
    }

    /** True when the class-level mapping is {@code root} or under it. */
    private static boolean mappedUnder(JavaClass candidate, String root) {
        if (!candidate.isAnnotatedWith(RequestMapping.class)) {
            return false;
        }
        RequestMapping mapping = candidate.getAnnotationOfType(RequestMapping.class);
        return Stream.concat(Stream.of(mapping.path()), Stream.of(mapping.value()))
                .anyMatch(path -> path.equals(root) || path.startsWith(root + "/"));
    }

    private static JavaClasses imported(Class<?>... classes) {
        return new ClassFileImporter().importClasses(classes);
    }

    // ------------------------------------------------------------------ the planted probes

    /** A customer route reaching a risk type directly. */
    @RestController
    @RequestMapping(path = "/me/planted")
    static class PlantedMeController {
        RiskDecision decision() {
            return RiskDecision.ALLOW;
        }
    }

    /** A merchant-key route reaching a risk type directly. */
    @RestController
    @RequiresMerchantKey
    static class PlantedMerchantController {
        CaseKind kind() {
            return CaseKind.AML;
        }
    }

    /** A customer route whose desk reaches a risk type. */
    @RestController
    @RequestMapping(path = "/me/planted-desk")
    static class PlantedMeControllerWithDesk {
        PlantedDesk desk() {
            return new PlantedDesk();
        }
    }

    static class PlantedDesk {
        RiskRating rating() {
            return RiskRating.LOW;
        }
    }

    /** A customer session route outside /me reaching a risk type directly - the transfer door's shape. */
    @RestController
    @RequestMapping(path = "/planted-transfers")
    @RequiresSession
    static class PlantedSessionController {
        RiskDecision decision() {
            return RiskDecision.ALLOW;
        }
    }

    /**
     * An operator route reaching a risk type - the desks the guard keeps separate from the surface, not the surface -
     * session-authenticated as every operator route is, and still not the surface.
     */
    @RestController
    @RequestMapping(path = "/operator/risk/planted")
    @RequiresSession
    static class LookalikeOperatorController {
        RiskDecision decision() {
            return RiskDecision.DENY;
        }
    }
}
