package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.app.session.RequiresSession;
import com.finapp.payments.PaymentIntent;
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
import java.util.List;
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
                // The withdrawal door's desk (P13-TSK-009): it asks the step-up advice through an app port.
                .anyMatch(name -> name.endsWith(".WithdrawalService"))
                .anyMatch(name -> name.endsWith(".CheckoutSessionController"))
                // The transfer door and its desk (P13-TSK-008): a session route outside /me.
                .anyMatch(name -> name.endsWith(".TransferController"))
                .anyMatch(name -> name.endsWith(".TransferService"))
                // The checkout confirmation door and its desk (P13-TSK-010): the step-up advice through an app port,
                // the refusal the intent's generic failure; and the customer's payment door, which reads it back.
                .anyMatch(name -> name.endsWith(".CheckoutConfirmationController"))
                .anyMatch(name -> name.endsWith(".CheckoutService"))
                .anyMatch(name -> name.endsWith(".PaymentController"))
                .anyMatch(name -> name.endsWith(".PaymentService"))
                // The cross-border payment door and its desk (P13-TSK-011): the step-up advice through an app port,
                // the seams' refusal crossborder's own 422.
                .anyMatch(name -> name.endsWith(".CrossBorderPaymentController"))
                .anyMatch(name -> name.endsWith(".CrossBorderPaymentDesk"));
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

    /**
     * The checkout payment's refusal, one step further (`P13-TSK-010`; C6): a payment risk refused carries its own
     * {@code RISK_REFUSED} on the intent - payments' record - and no surface renders it. The customer and the merchant
     * read the intent's generic {@code FAILED} (the payment read's {@code failureReason} is the attempt's mapped reason,
     * and a refused intent has no attempt), so no surface class may read {@code PaymentIntent.failureReason()} at all.
     */
    @ArchTest
    static void noSurfaceReadsTheIntentsRiskRefusal(JavaClasses imported) {
        intentReasonRule(imported).check(imported);
    }

    @Test
    @DisplayName("a session route rendering the intent's own failure reason (RISK_REFUSED) is refused (P13-TSK-010)")
    void theRuleRefusesARenderedIntentReason() {
        JavaClasses planted = imported(PlantedPaymentController.class, PaymentIntent.class);
        EvaluationResult result = intentReasonRule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedPaymentController");
    }

    // ------------------------------------------------------------------ the reach, made whole (P13-TSK-018)

    /**
     * The seams through which a customer's or merchant's request may reach {@code risk} (`P13-TSK-018`;
     * {@code DESIGN-018} D13) - each an {@code app} class implementing a consumer's own port and answering the
     * consumer's own vocabulary (a verdict, a boolean, a rating code), never a risk record: the transfer seams
     * (`-008`), the withdrawal seam (`-009`), the checkout payment seam (`-010`), the cross-border seams (`-011`), each
     * with its door's step-up advice; credit's risk signal (`-007`); and the one read of whose party an operator is,
     * which implements risk's acting-party port beside credit's, lending's and bnpl's (`IdentityActingParty`). Nothing
     * else on the surface's whole reach may depend on a risk type.
     */
    static final Set<String> SEAMS = Set.of(
            "com.finapp.app.risk.RiskTransferLimitCheck",
            "com.finapp.app.risk.RiskTransferRiskDecision",
            "com.finapp.app.risk.RiskTransferStepUpAdvice",
            "com.finapp.app.risk.RiskWithdrawalRiskDecision",
            "com.finapp.app.risk.RiskWithdrawalStepUpAdvice",
            "com.finapp.app.risk.RiskPaymentRiskDecision",
            "com.finapp.app.risk.RiskCheckoutStepUpAdvice",
            "com.finapp.app.risk.RiskCrossBorderRiskDecision",
            "com.finapp.app.risk.RiskCrossBorderLimitCheck",
            "com.finapp.app.risk.RiskCrossBorderStepUpAdvice",
            "com.finapp.app.risk.RiskCreditRiskSignal",
            "com.finapp.app.credit.IdentityActingParty");

    /**
     * <strong>The whole reach</strong> (`P13-TSK-018`; the entry's "every route, desk and repository reachable from a
     * customer or merchant principal"): from every surface class, every {@code com.finapp} class it depends on, and on
     * from those - and, for an interface or an abstract class reached, every implementation the build has, which is how
     * a desk reaches the adapter Spring gives it. The reach stops at {@code risk} (what is judged) and at an enumerated
     * seam (the boundary itself). No class on it but a seam may depend on a risk type.
     */
    @ArchTest
    static void theSurfacesWholeReachTouchesRiskOnlyThroughTheSeams(JavaClasses imported) {
        Reach reach = reach(imported);
        assertThat(reach.violations())
                .as("a class on a customer's or merchant's reach that depends on risk outside the enumerated seams")
                .isEmpty();
        assertThat(reach.seamsReached())
                .as("every enumerated seam is on the reach - a seam no surface reaches is a stale entry")
                .containsExactlyInAnyOrderElementsOf(SEAMS);
    }

    /** The reach's census - not vacuous: the surface's desks, its repositories and its seams are on it. */
    @ArchTest
    static void theWholeReachIsNotEmpty(JavaClasses imported) {
        Reach reach = reach(imported);
        assertThat(reach.reached())
                .as("the reach follows a desk to its repository and a port to its adapter")
                .contains("com.finapp.app.risk.RiskWithdrawalRiskDecision")
                .anyMatch(name -> name.endsWith(".JdbcWithdrawalStore"))
                .hasSizeGreaterThan(200);
    }

    @Test
    @DisplayName("P13-TSK-018: a /me route whose desk reaches risk two hops away, through a port's implementation, is"
            + " refused; an enumerated seam is the boundary")
    void theReachRefusesASecondHop() {
        JavaClasses planted = imported(PlantedMeControllerWithService.class, PlantedService.class, PlantedPort.class,
                PlantedPortAdapter.class, RiskRating.class);
        Reach reach = reach(planted);
        assertThat(reach.violations()).singleElement().asString().contains("PlantedPortAdapter");
    }

    /**
     * {@code risk}'s tables are read by {@code risk}'s stores alone (`P13-TSK-018`; {@code INV-RSK-08}): no production
     * source outside the module names one - so no route, desk or repository anywhere reads a risk table by its own SQL,
     * around the types the reach rule follows. The tables are read from risk's own migrations.
     */
    @Test
    @DisplayName("P13-TSK-018: no production source outside risk names a risk table - its tables are read through its"
            + " own stores alone")
    void noSourceOutsideRiskNamesARiskTable() throws java.io.IOException {
        java.nio.file.Path root = repositoryRoot();
        Set<String> tables = new TreeSet<>();
        try (Stream<java.nio.file.Path> migrations = java.nio.file.Files.list(
                root.resolve("risk/src/main/resources/db/migration/risk"))) {
            for (java.nio.file.Path migration : migrations.toList()) {
                java.util.regex.Matcher created = java.util.regex.Pattern.compile("CREATE TABLE risk\\.([a-z_]+)")
                        .matcher(java.nio.file.Files.readString(migration));
                while (created.find()) {
                    tables.add(created.group(1));
                }
            }
        }
        assertThat(tables).as("risk's tables are read").contains("risk_case", "risk_alert", "risk_assessment")
                .hasSizeGreaterThanOrEqualTo(20);
        java.util.regex.Pattern named = java.util.regex.Pattern.compile("\\brisk\\.(" + String.join("|", tables)
                + ")\\b");
        Set<String> naming = new TreeSet<>();
        try (Stream<java.nio.file.Path> modules = java.nio.file.Files.list(root)) {
            for (java.nio.file.Path module : modules.filter(path -> !path.getFileName().toString().equals("risk"))
                    .map(path -> path.resolve("src/main/java")).filter(java.nio.file.Files::isDirectory).toList()) {
                try (Stream<java.nio.file.Path> sources = java.nio.file.Files.walk(module)) {
                    for (java.nio.file.Path source : sources.filter(path -> path.toString().endsWith(".java"))
                            .toList()) {
                        if (named.matcher(java.nio.file.Files.readString(source)).find()) {
                            naming.add(root.relativize(source).toString());
                        }
                    }
                }
            }
        }
        assertThat(naming).as("a production source outside risk that names a risk table").isEmpty();
    }

    /** What the reach found: every class on it, the seams it met, and the classes that reach risk outside them. */
    record Reach(Set<String> reached, Set<String> seamsReached, Set<String> violations) {}

    static Reach reach(JavaClasses classes) {
        Set<String> reached = new TreeSet<>();
        Set<String> seams = new TreeSet<>();
        Set<String> violations = new TreeSet<>();
        java.util.Deque<JavaClass> pending = new java.util.ArrayDeque<>();
        for (JavaClass candidate : classes) {
            if (isSurface(candidate)) {
                pending.add(candidate);
            }
        }
        while (!pending.isEmpty()) {
            JavaClass current = pending.pop();
            if (!reached.add(current.getName())) {
                continue;
            }
            if (SEAMS.contains(current.getName())) {
                seams.add(current.getName());
                continue;
            }
            List<String> riskTargets = current.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .filter(target -> target.getPackageName().startsWith("com.finapp.risk"))
                    .map(JavaClass::getName).distinct().toList();
            if (!riskTargets.isEmpty()) {
                violations.add(current.getName() + " -> " + riskTargets);
            }
            current.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .filter(RiskReachable::onTheReach)
                    .forEach(pending::add);
            if (current.isInterface() || current.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier
                    .ABSTRACT)) {
                current.getAllSubclasses().stream().filter(RiskReachable::onTheReach).forEach(pending::add);
            }
        }
        return new Reach(reached, seams, violations);
    }

    /** The reach follows the platform's own classes, never into risk (what it judges) nor beyond com.finapp. */
    private static final class RiskReachable {
        static boolean onTheReach(JavaClass target) {
            String pkg = target.getPackageName();
            return pkg.startsWith("com.finapp") && !pkg.startsWith("com.finapp.risk");
        }
    }

    private static java.nio.file.Path repositoryRoot() {
        java.nio.file.Path directory = java.nio.file.Path.of("").toAbsolutePath();
        while (directory != null) {
            if (java.nio.file.Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + java.nio.file.Path.of("").toAbsolutePath());
    }

    // ------------------------------------------------------------------ the rule

    static ArchRule intentReasonRule(JavaClasses classes) {
        return noClasses()
                .that(onTheSurface(guardedClassNames(classes)))
                .should()
                .callMethod(PaymentIntent.class, "failureReason")
                .because(
                        "a payment refused by risk answers the intent's generic FAILED (C6, INV-RSK-08): the intent's"
                                + " own RISK_REFUSED is payments' record and the operator's, never a customer's or a"
                                + " merchant's");
    }

    private static DescribedPredicate<JavaClass> onTheSurface(Set<String> guarded) {
        return new DescribedPredicate<>("on the customer or merchant surface, or a direct app dependency of it") {
            @Override
            public boolean test(JavaClass candidate) {
                return guarded.contains(candidate.getName());
            }
        };
    }

    static ArchRule rule(JavaClasses classes) {
        Set<String> guarded = guardedClassNames(classes);
        DescribedPredicate<JavaClass> onTheSurface = onTheSurface(guarded);
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

    /** A customer session route rendering the intent's own failure reason - the payment door's shape. */
    @RestController
    @RequestMapping(path = "/planted-payments")
    @RequiresSession
    static class PlantedPaymentController {
        String reason(PaymentIntent intent) {
            return intent.failureReason().map(Enum::name).orElse(null);
        }
    }

    /** A customer route whose service reaches risk only through a port's implementation - two hops (P13-TSK-018). */
    @RestController
    @RequestMapping(path = "/me/planted-reach")
    static class PlantedMeControllerWithService {
        PlantedService service() {
            return new PlantedService(null);
        }
    }

    static class PlantedService {
        private final PlantedPort port;

        PlantedService(PlantedPort port) {
            this.port = port;
        }

        boolean refused() {
            return port.refused();
        }
    }

    interface PlantedPort {
        boolean refused();
    }

    /** The adapter Spring would give the service - not an enumerated seam, and it reads a risk type. */
    static class PlantedPortAdapter implements PlantedPort {
        @Override
        public boolean refused() {
            return RiskRating.HIGH.ordinal() > 0;
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
