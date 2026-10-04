package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FxProvider;
import com.finapp.fx.FxProviders;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A booked conversion never waits on a provider (`P9-TSK-009`; {@code INV-FX-09}, ADR-0076
 * section 2): no class of the conversion's transaction - the service, its line composer, the trade
 * store, the desk, the participants - may reach {@link FxProvider} or {@link FxProviders}, so a
 * provider's timeout, failure or unknown state can never sit inside the transaction that changes a
 * customer's balances. The cover is sent elsewhere, later (`P9-TSK-012`). A planted violation
 * proves the rule bites.
 */
@DisplayName("no provider port is reachable from the conversion (P9-TSK-009, INV-FX-09)")
@Tag("architecture")
class NoProviderPortInConversionTest {

    /** The conversion's transaction, class by class. */
    static final List<String> CONVERSION = List.of(
            "com.finapp.fx.FxConversion",
            "com.finapp.fx.ConversionLines",
            "com.finapp.fx.TradeStore",
            "com.finapp.fx.JdbcTradeStore",
            "com.finapp.app.fx.FxConversionDesk",
            "com.finapp.app.fx.PartyConversionParticipants");

    static ArchRule rule(List<String> classes) {
        return noClasses()
                .that().haveNameMatching(String.join("|", classes.stream().map(c -> Pattern.quote(c) + "(\\$.*)?").toList()))
                .should().dependOnClassesThat().haveNameMatching("com\\.finapp\\.fx\\.FxProvider(s)?(\\$.*)?")
                .because("a booked conversion never waits on, and is never changed by, a provider (INV-FX-09)");
    }

    @Test
    @DisplayName("no class of the conversion's transaction depends on the provider port")
    void theConversionReachesNoProvider() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp.fx", "com.finapp.app.fx");
        for (String name : CONVERSION) {
            assertThat(production.contain(name)).as("not vacuous: %s is imported", name).isTrue();
        }
        rule(CONVERSION).check(production);
    }

    @Test
    @DisplayName("the rule bites: a planted conversion collaborator that asks a provider is refused")
    void thePlantedReachIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedConversion.class, FxProvider.class);
        EvaluationResult result = rule(List.of(PlantedConversion.class.getName())).evaluate(planted);
        assertThat(result.hasViolation()).isTrue();
    }

    /** Asks a provider inside what claims to be the conversion - exactly what the rule forbids. */
    static final class PlantedConversion {
        String code(FxProvider provider) {
            return provider.code();
        }
    }
}
