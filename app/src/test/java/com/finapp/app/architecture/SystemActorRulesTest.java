package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.ScopedActorProbe;
import com.finapp.ledger.SystemActorProbe;
import com.finapp.platform.security.SecurityContext;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Claiming the platform acted is a deliberate act, not a way to fill in a parameter.
 *
 * <p><strong>The failure this prevents.</strong> Every audit record needs an {@link
 * com.finapp.platform.security.Actor}, so every call site that writes one has a parameter to
 * satisfy. When no actor has been established, the shortest way to make the code compile is
 * {@code Actor.SYSTEM} - and it is available, public and constant. The result is a record that
 * says the platform did something a person did. Nothing fails, the record looks complete, and
 * {@code INV-HIST-03} makes it permanent.
 *
 * <p>This is the same shape as ADR-0019's argument for redaction: the safe thing must be the
 * default and the unsafe thing must be hard to reach, because relying on people not to take the
 * shortcut is relying on nobody ever being in a hurry.
 *
 * <p>So the constant has one legitimate reader: {@link SecurityContext#enterSystem()}, which
 * establishes a scope and says so. Everything else asks the context who is acting and gets an
 * error when nobody is - which is the honest answer, and a wiring defect that shows up on the
 * first request rather than in an audit years later.
 *
 * <p><strong>What this does not claim.</strong> It cannot stop a caller writing
 * {@code new Actor("system", ActorType.SYSTEM)} by hand. That is a deliberate forgery rather than
 * a shortcut, and no static rule distinguishes it from a legitimate actor built from a real
 * identity - which is the whole reason actors are constructible at all. The rule removes the easy
 * path, which is the one people actually take.
 */
@Tag("architecture")
// NOT a duplicate of the line above. ArchUnit runs @ArchTest fields under its OWN
// JUnit Platform engine, and that engine's descriptors read `ArchTag` - they cannot
// see JUnit's `Tag` at all. Without this every rule below carried no tag, so
// `unitTest` (which selects by EXCLUSION) took them and `architectureTest` (which
// selects by INCLUSION) got none. P1-TSK-025; TestTaxonomyTest now requires the pair.
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class SystemActorRulesTest {

    private static final String ACTOR = "com.finapp.platform.security.Actor";

    /** The one component whose job is to say "the platform is acting here". */
    private static final String SYSTEM_ACTOR_OWNER = "com.finapp.platform.security.SecurityContext";

    @ArchTest
    static final ArchRule onlyTheSecurityContextClaimsTheSystemActor =
            noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName(SYSTEM_ACTOR_OWNER)
                    .should()
                    // getField, not accessField: accessField also matches the WRITE in Actor's own
                    // static initialiser, so the rule would report the constant's own declaration
                    // as a violation of itself. Reading it is the shortcut being closed.
                    .getField(ACTOR, "SYSTEM")
                    .because(
                            "an audit record needs an actor, so Actor.SYSTEM is the shortest way to"
                                + " make a call site compile when none was established - and it"
                                + " records the platform as having done what a person did"
                                + " (INV-AUD-01). Establish a scope instead:"
                                + " SecurityContext.enterSystem() for platform-initiated work, and"
                                + " SecurityContext.require() everywhere else, which fails rather"
                                + " than inventing an actor");

    /**
     * The coverage guard, for the reason every rule suite here has one: a rule that sees nothing
     * passes, and reports safety it never checked.
     *
     * <p>This suite was written without it, which is the deviation {@code P0-TST-008} identified as
     * the mechanism by which a security rule can sit behind a green test while protecting nothing.
     * The four sibling suites all had one; this one now does too.
     */
    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed = new TreeSet<>();
        imported.stream().map(ProductionModules::of).filter(Objects::nonNull).forEach(analysed::add);

        assertThat(analysed)
                .as("the rule must see every module that has production code, or it protects only some of them")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());
    }

    @Test
    @DisplayName("the rule rejects a module reaching for the system actor")
    void theRuleHasTeeth() {
        assertRejects(onlyTheSecurityContextClaimsTheSystemActor, SystemActorProbe.class);
    }

    @Test
    @DisplayName("the exemption is load-bearing, not decorative")
    void theExemptionIsReal() {
        // An exemption for a class that never performs the access would pass while protecting
        // nothing - the rule would look careful and be vacuous. So the SAME rule without the
        // exclusion is run against SecurityContext, and it must fire: that is what proves the
        // exclusion is carrying weight rather than describing a case that cannot arise.
        //
        // Checking the exempted rule against SecurityContext alone cannot show this: the that()
        // clause removes the only class, and ArchUnit rejects a rule that checked nothing - which
        // is itself the vacuity guard doing its job.
        ArchRule withoutTheExemption =
                noClasses().should().getField(ACTOR, "SYSTEM").because("probe");
        JavaClasses owner = new ClassFileImporter().importClasses(SecurityContext.class);

        assertThatThrownBy(() -> withoutTheExemption.check(owner))
                .as("SecurityContext must really read Actor.SYSTEM, or the exemption is empty")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SecurityContext");
    }

    @Test
    @DisplayName("and ordinary code that establishes a scope properly is untouched")
    void cleanCodeIsAccepted() {
        // The positive control the rule needs: a class that uses the context correctly must pass,
        // or the rule could be rejecting everything and the teeth test would not notice.
        JavaClasses clean = new ClassFileImporter().importClasses(ScopedActorProbe.class);

        assertThatCode(() -> onlyTheSecurityContextClaimsTheSystemActor.check(clean))
                .doesNotThrowAnyException();
    }

    private static void assertRejects(ArchRule rule, Class<?> violation) {
        JavaClasses violating = new ClassFileImporter().importClasses(violation);

        assertThatThrownBy(() -> rule.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violation.getSimpleName());
    }
}
