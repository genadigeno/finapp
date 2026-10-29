package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.GlClassification;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunStatus;
import com.finapp.app.merchant.RequiresMerchantKey;
import com.finapp.app.session.RequiresSession;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <strong>No customer or merchant surface reaches {@code accounting}</strong> (`P14-TSK-001`; {@code PHASE_14_PLAN.md}
 * sections 10, 12, 21; ADR-0113): the GL, the periods, the report runs and the regulatory submissions are the operator's
 * finance desks' and nothing a customer or a merchant can call reads them - a customer's balance is the ledger's own
 * read, never a GL figure, and no report run's frozen content (aggregates, {@code CONFIDENTIAL}) reaches a customer or
 * merchant response.
 *
 * <p><strong>The surface</strong> is {@code NoCustomerSurfaceReadsRiskTest}'s, shared rather than copied
 * ({@code isSurface}, {@code guardedClassNames}): every {@code @RestController} mapped under {@code /me}, every controller
 * that is, or has a method, {@code @RequiresMerchantKey}, and every {@code @RequiresSession} controller not mapped under
 * {@code /operator}. Operator routes ({@code /operator/accounting/...}, {@code /operator/regulatory/...}) are not the
 * surface: they are the desks this guard keeps separate from it.
 *
 * <p><strong>Two rules.</strong> The surface and its direct {@code app} dependencies (its desks) depend on no
 * {@code com.finapp.accounting} type; and <strong>the whole reach</strong> - from every surface class, every
 * {@code com.finapp} class it depends on and on from those, and for an interface or an abstract class reached, every
 * implementation the build has, stopping only at {@code accounting} itself - holds no class that depends on an
 * accounting type. Unlike risk's reach there are <strong>no seams</strong>: no customer or merchant door has a reason to
 * consult accounting at all, so a later task that finds one adds an enumerated seam here, reviewed.
 *
 * <p><strong>Armed before the first door.</strong> Nothing in {@code app} depends on {@code accounting} yet, so both
 * rules pass today on the production classes; the planted probes in this source - a {@code /me} controller, a
 * merchant-key controller, a {@code /me} controller's desk and a {@code /me} route reaching accounting two hops away
 * through a port's implementation, each holding an accounting type, and an operator lookalike that must NOT be flagged
 * - prove both able to fail and no wider than claimed.
 */
@Tag("architecture")
// ArchUnit's own engine reads ArchTag, not JUnit's Tag (P1-TSK-025; TestTaxonomyTest requires the pair).
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class NoCustomerSurfaceReadsAccountingTest {

    private static final String ACCOUNTING = "com.finapp.accounting";

    @ArchTest
    static void noCustomerOrMerchantSurfaceReachesAccounting(JavaClasses imported) {
        rule(imported).check(imported);
    }

    @ArchTest
    static void theSurfacesWholeReachNeverTouchesAccounting(JavaClasses imported) {
        Reach reach = reach(imported);
        assertThat(reach.violations())
                .as("a class on a customer's or merchant's reach that depends on accounting - there is no seam")
                .isEmpty();
        // The census: the reach follows a desk to its repository, over the platform's own classes, or the rule above
        // guards nothing; and the guard sees accounting's classes at all, or it could not tell a dependency on them.
        assertThat(reach.reached())
                .anyMatch(name -> name.endsWith(".WithdrawalController"))
                .anyMatch(name -> name.endsWith(".JdbcWithdrawalStore"))
                .hasSizeGreaterThan(200);
        assertThat(imported.containPackage(ACCOUNTING))
                .as("the accounting module's classes are imported - a guard over a package it cannot see is vacuous")
                .isTrue();
    }

    @Test
    @DisplayName("a /me controller holding an accounting type is refused")
    void theRuleRefusesACustomerController() {
        JavaClasses planted = imported(PlantedMeController.class, PeriodStatus.class);
        EvaluationResult result = rule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedMeController");
    }

    @Test
    @DisplayName("a merchant-key controller holding an accounting type is refused")
    void theRuleRefusesAMerchantController() {
        JavaClasses planted = imported(PlantedMerchantController.class, ReportKind.class);
        EvaluationResult result = rule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedMerchantController");
    }

    @Test
    @DisplayName("a /me controller whose desk holds an accounting type is refused at the desk")
    void theRuleRefusesACustomerDesk() {
        JavaClasses planted = imported(PlantedMeControllerWithDesk.class, PlantedDesk.class, GlClassification.class);
        EvaluationResult result = rule(planted).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains("PlantedDesk");
    }

    @Test
    @DisplayName("a /me route whose service reaches accounting two hops away, through a port's implementation, is"
            + " refused by the whole reach")
    void theReachRefusesASecondHop() {
        JavaClasses planted = imported(PlantedMeControllerWithService.class, PlantedService.class, PlantedPort.class,
                PlantedPortAdapter.class, RunStatus.class);
        assertThat(reach(planted).violations()).singleElement().asString().contains("PlantedPortAdapter");
    }

    @Test
    @DisplayName("an operator controller holding an accounting type is not the surface, and passes")
    void theRuleLeavesTheOperatorDeskAlone() {
        JavaClasses planted = imported(LookalikeOperatorController.class, ReportKind.class);
        assertThat(NoCustomerSurfaceReadsRiskTest.guardedClassNames(planted)).isEmpty();
        assertThat(reach(planted).violations()).isEmpty();
    }

    // ------------------------------------------------------------------ the rules

    static ArchRule rule(JavaClasses classes) {
        Set<String> guarded = NoCustomerSurfaceReadsRiskTest.guardedClassNames(classes);
        return noClasses()
                .that(new DescribedPredicate<JavaClass>("on the customer or merchant surface, or a direct app dependency"
                        + " of it") {
                    @Override
                    public boolean test(JavaClass candidate) {
                        return guarded.contains(candidate.getName());
                    }
                })
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ACCOUNTING + "..")
                .because("the GL, periods, report runs and regulatory submissions are the operator's finance desks'"
                        + " (PHASE_14_PLAN.md section 10): no customer or merchant surface reads accounting");
    }

    /** What the reach found: every class on it, and the classes on it that depend on accounting. */
    record Reach(Set<String> reached, Set<String> violations) {}

    static Reach reach(JavaClasses classes) {
        Set<String> reached = new TreeSet<>();
        Set<String> violations = new TreeSet<>();
        Deque<JavaClass> pending = new ArrayDeque<>();
        for (JavaClass candidate : classes) {
            if (NoCustomerSurfaceReadsRiskTest.isSurface(candidate)) {
                pending.add(candidate);
            }
        }
        while (!pending.isEmpty()) {
            JavaClass current = pending.pop();
            if (!reached.add(current.getName())) {
                continue;
            }
            List<String> targets = current.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .filter(target -> target.getPackageName().startsWith(ACCOUNTING))
                    .map(JavaClass::getName).distinct().toList();
            if (!targets.isEmpty()) {
                violations.add(current.getName() + " -> " + targets);
            }
            current.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .filter(NoCustomerSurfaceReadsAccountingTest::onTheReach)
                    .forEach(pending::add);
            if (current.isInterface() || current.getModifiers().contains(JavaModifier.ABSTRACT)) {
                current.getAllSubclasses().stream().filter(NoCustomerSurfaceReadsAccountingTest::onTheReach)
                        .forEach(pending::add);
            }
        }
        return new Reach(reached, violations);
    }

    /** The reach follows the platform's own classes, never into accounting (what it guards) nor beyond com.finapp. */
    private static boolean onTheReach(JavaClass target) {
        String pkg = target.getPackageName();
        return pkg.startsWith("com.finapp") && !pkg.startsWith(ACCOUNTING);
    }

    private static JavaClasses imported(Class<?>... classes) {
        return new ClassFileImporter().importClasses(classes);
    }

    // ------------------------------------------------------------------ the planted probes

    /** A customer route reaching an accounting type directly. */
    @RestController
    @RequestMapping(path = "/me/planted-books")
    static class PlantedMeController {
        PeriodStatus status() {
            return PeriodStatus.OPEN;
        }
    }

    /** A merchant-key route reaching an accounting type directly. */
    @RestController
    @RequiresMerchantKey
    static class PlantedMerchantController {
        ReportKind kind() {
            return ReportKind.BALANCES;
        }
    }

    /** A customer route whose desk reaches an accounting type. */
    @RestController
    @RequestMapping(path = "/me/planted-books-desk")
    static class PlantedMeControllerWithDesk {
        PlantedDesk desk() {
            return new PlantedDesk();
        }
    }

    static class PlantedDesk {
        GlClassification classification() {
            return GlClassification.ASSET;
        }
    }

    /** A customer route whose service reaches accounting only through a port's implementation - two hops. */
    @RestController
    @RequestMapping(path = "/me/planted-books-reach")
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

        boolean issued() {
            return port.issued();
        }
    }

    interface PlantedPort {
        boolean issued();
    }

    /** The adapter Spring would give the service - and it reads an accounting type. */
    static class PlantedPortAdapter implements PlantedPort {
        @Override
        public boolean issued() {
            return RunStatus.FINAL.issuable();
        }
    }

    /** An operator route reaching an accounting type - the finance desk, session-authenticated, not the surface. */
    @RestController
    @RequestMapping(path = "/operator/accounting/planted")
    @RequiresSession
    static class LookalikeOperatorController {
        ReportKind kind() {
            return ReportKind.TRIAL_BALANCE;
        }
    }
}
