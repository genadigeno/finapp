package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.ExchangeRate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation never converts (`P9-TSK-011`; {@code INV-REC-08}, PHASE_9_PLAN.md section 12.9.3):
 * no class of {@code settlement} or {@code reconciliation} uses an {@link ExchangeRate}, nor any
 * class that can reach one - transitively - so an FX leg is explained in its own currency, a rate
 * difference is a leg's amount difference ({@code FX_LEG_DIFFERS}), and no break is ever "resolved"
 * by converting one currency into another. {@code ExchangeRate} lives in {@code sharedkernel}, which
 * both modules may see, so the module-isolation tests cannot hold this line; this rule does. A
 * planted settlement-side class reaching a rate through a helper proves it bites.
 */
@Tag("architecture")
@DisplayName("reconciliation never converts: no settlement or reconciliation class reaches an exchange rate (P9-TSK-011, INV-REC-08)")
class ReconciliationNeverConvertsTest {

    static ArchRule rule(String classNamePattern) {
        return noClasses()
                .that().haveNameMatching(classNamePattern)
                .should().transitivelyDependOnClassesThat().haveFullyQualifiedName(ExchangeRate.class.getName())
                .because("reconciliation explains each currency on its own and never converts (INV-REC-08)");
    }

    @Test
    @DisplayName("no class of settlement or reconciliation can reach an exchange rate")
    void neverConverts() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
        assertThat(production.contain("com.finapp.settlement.BatchAcceptance")).as("not vacuous").isTrue();
        assertThat(production.contain("com.finapp.reconciliation.Matching")).as("not vacuous").isTrue();
        assertThat(production.contain(ExchangeRate.class.getName())).as("the rate is imported").isTrue();
        rule("com\\.finapp\\.(settlement|reconciliation)\\..*").check(production);
    }

    @Test
    @DisplayName("the rule bites: a planted class reaching a rate through a helper is refused")
    void thePlantedReachIsRefused() {
        JavaClasses planted = new ClassFileImporter()
                .importClasses(PlantedMatcher.class, PlantedHelper.class, ExchangeRate.class);
        EvaluationResult result = rule(Pattern.quote(PlantedMatcher.class.getName())).evaluate(planted);
        assertThat(result.hasViolation()).as("a two-hop reach is still a reach").isTrue();
    }

    /** A planted matcher that "explains" a difference by converting - through a helper. */
    static final class PlantedMatcher {
        Object explain() {
            return new PlantedHelper().rate();
        }
    }

    /** The helper that holds the rate. */
    static final class PlantedHelper {
        ExchangeRate rate() {
            return null;
        }
    }
}
